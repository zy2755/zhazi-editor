package com.zhazi.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhazi.editor.docx.Paragraph
import com.zhazi.editor.docx.RunFmt
import com.zhazi.editor.docx.RprOps

val ChipBg = Color(0xFF292929)
val ChipFg = Color(0xFFE6E6E6)
val ProtectedBg = Color(0xFF1C1C1C)
val ProtectedFg = Color(0xFF7A7A7A)
val SelBlue = Color(0xFF3D7EFF)

private fun safeColor(hex: String?): Color? {
    if (hex == null || hex == "auto") return null
    val h = hex.removePrefix("#")
    if (h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParaChips(
    para: Paragraph,
    chips: List<IntRange>,
    selChips: Set<Int>,
    onToggle: (Int) -> Unit,
    onSplit: (Int) -> Unit,
    onMerge: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) {
        Spacer(modifier.height(12.dp))
        return
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        chips.forEachIndexed { ci, r ->
            val run = para.runs[para.runIndexAt(r.first)]
            val fmt = remember(run) { run.rprXml?.let { RprOps.readFmt(RprOps.parseChildren(it)) } }
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
    val bg = when {
        selected -> SelBlue
        !editable -> ProtectedBg
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
            maxLines = 1,
            softWrap = false,
        )
    }
}
