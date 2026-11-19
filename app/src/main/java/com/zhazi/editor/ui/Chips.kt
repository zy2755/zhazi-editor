package com.zhazi.editor.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhazi.editor.docx.Paragraph
import com.zhazi.editor.docx.RunFmt
import com.zhazi.editor.docx.RprOps
import kotlin.math.abs

val ChipBg = Color(0xFF292929)
val ChipFg = Color(0xFFE6E6E6)
val ProtectedBg = Color(0xFF1C1C1C)
val ProtectedFg = Color(0xFF7A7A7A)
val SelBlue = Color(0xFF3D7EFF)

// OOXML ST_HighlightColor 名称 → 显示色
val HIGHLIGHT_HEX: Map<String, Long> = mapOf(
    "yellow" to 0xFFFF00, "green" to 0x00FF00, "turquoise" to 0x40E0D0,
    "pink" to 0xFF00FF, "blue" to 0x0000FF, "red" to 0xFF0000,
    "darkBlue" to 0x00008B, "darkCyan" to 0x008B8B, "darkGreen" to 0x006400,
    "darkMagenta" to 0x8B008B, "darkRed" to 0x8B0000, "darkYellow" to 0x808000,
    "darkGray" to 0xA9A9A9, "lightGray" to 0xD3D3D3,
    "black" to 0x000000, "white" to 0xFFFFFF,
)

internal fun safeColor(hex: String?): Color? {
    if (hex == null || hex == "auto") return null
    val h = hex.removePrefix("#")
    if (h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

private fun highlightColor(name: String?): Color? =
    name?.let { HIGHLIGHT_HEX[it.lowercase()]?.let { v -> Color(0xFF000000L or v) } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParaChips(
    para: Paragraph,
    chips: List<IntRange>,
    selChips: Set<Int>,
    onToggle: (Int) -> Unit,
    onSplit: (Int) -> Unit,
    onMerge: (Int) -> Unit,
    onDragSelect: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) {
        Spacer(modifier.height(12.dp))
        return
    }
    // 每个词块在 FlowRow 内的包围盒，供拖选命中测试
    val chipRects = remember(chips) { arrayOfNulls<Rect>(chips.size) }
    fun isEditable(ci: Int): Boolean =
        chips.getOrNull(ci)?.let { para.runs[para.runIndexAt(it.first)].editable } ?: false

    // 横向拖选：用官方 draggable 做轴判别——竖向滑动让位给列表滚动，横向确认后才启动
    var dragBaseIdx by remember(chips) { mutableIntStateOf(-1) }
    var dragLastPos by remember(chips) { mutableStateOf(Offset.Zero) }
    // draggable 的 onDragStarted 给的是窗口坐标；onGloballyPositioned 记录 FlowRow
    // 在窗口中的偏移，用于把窗口坐标换算回 FlowRow 本地坐标
    var containerWinOffset by remember { mutableStateOf(Offset.Zero) }
    fun hitChip(p: Offset): Int {
        var found = -1
        for (i in chipRects.indices) {
            val r = chipRects[i] ?: continue
            if (r.contains(p)) found = i
        }
        return found
    }

    FlowRow(
        modifier = modifier.pointerInput(para.text, chips, para) {
            awaitEachGesture {
                // 不依赖 awaitFirstDown 的时序（down 可能已被词块的 clickable 消费），
                // 从收到的第一帧开始跟踪；Initial pass 确保父级先于子级看到事件。
                // 横向占优 → 进入滑选并消费事件（列表不滚、词块 click 取消）；
                // 纵向占优 → 一票否决本段手势，事件照常由列表滚动处理。
                var downPos: Offset? = null
                var selecting = false
                var vetoed = false
                var startIdx = -1
                fun hitChip(p: Offset): Int {
                    var found = -1
                    for (i in chipRects.indices) {
                        val r = chipRects[i] ?: continue
                        if (r.contains(p)) found = i
                    }
                    return found
                }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val pos = change.position
                    if (downPos == null) downPos = pos
                    val base = downPos!!
                    if (!selecting && !vetoed) {
                        val dx = pos.x - base.x
                        val dy = pos.y - base.y
                        if (abs(dy) > viewConfiguration.touchSlop && abs(dy) >= abs(dx)) {
                            vetoed = true // 纵向滚动，本段手势不再参与滑选
                        } else if (abs(dx) > viewConfiguration.touchSlop * 3f && abs(dx) > abs(dy) * 2f) {
                            val sIdx = hitChip(base)
                            if (sIdx >= 0 && isEditable(sIdx)) {
                                selecting = true
                                startIdx = sIdx
                                onDragSelect(startIdx, startIdx)
                            } else {
                                vetoed = true // 起点不在可编辑词块上
                            }
                        }
                    } else if (selecting) {
                        val idx = hitChip(pos)
                        if (idx >= 0) onDragSelect(startIdx, idx)
                        change.consume()
                    }
                }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        chips.forEachIndexed { ci, r ->
            val run = para.runs[para.runIndexAt(r.first)]
            val fmt = remember(run) { run.rprXml?.let { RprOps.readFmt(RprOps.parseChildren(it)) } }
            Box(
                Modifier.onGloballyPositioned { coords ->
                    chipRects[ci] = coords.boundsInParent()
                }
            ) {
                ChipView(
                    text = para.text.substring(r.first, r.last + 1),
                    selected = ci in selChips,
                    editable = run.editable,
                    fmt = fmt,
                    onClick = { if (run.editable) onToggle(ci) },
                    onSplit = { if (run.editable) onSplit(ci) },
                    onMerge = { onMerge(ci) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChipView(
    text: String,
    selected: Boolean,
    editable: Boolean,
    fmt: RunFmt?,
    onClick: () -> Unit,
    onSplit: () -> Unit,
    onMerge: () -> Unit,
) {
    val hlBg = highlightColor(fmt?.highlight)
    val bg = when {
        selected -> SelBlue
        !editable -> ProtectedBg
        hlBg != null && fmt?.highlight != "none" -> hlBg
        else -> ChipBg
    }
    val fg = when {
        selected -> Color.White
        !editable -> ProtectedFg
        else -> safeColor(fmt?.colorHex) ?: ChipFg
    }
    val size = fmt?.sizeHp?.let { (it / 2f).coerceIn(9f, 26f).sp } ?: 17.sp
    val weight = if (fmt?.bold == true) FontWeight.Bold else FontWeight.Normal
    val style = if (fmt?.italic == true) FontStyle.Italic else FontStyle.Normal
    val deco = listOfNotNull(
        if (fmt?.underline == true) TextDecoration.Underline else null,
        if (fmt?.strike == true) TextDecoration.LineThrough else null,
    ).let { if (it.isEmpty()) null else TextDecoration.combine(it) }

    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onSplit,
                onLongClick = onMerge,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = text.replace("\t", "⇥").replace("\n", "↵"),
            color = fg,
            fontSize = size,
            fontWeight = weight,
            fontStyle = style,
            textDecoration = deco,
            maxLines = 1,
            softWrap = false,
        )
    }
}
