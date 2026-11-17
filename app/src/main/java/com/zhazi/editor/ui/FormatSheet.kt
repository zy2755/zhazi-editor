package com.zhazi.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhazi.editor.docx.FontPatch
import com.zhazi.editor.docx.RunFmt

private val BarBg = Color(0xFF202020)
private val BtnBg = Color(0xFF2E2E2E)
private val SheetBg = Color(0xFF1B1B1B)

// 字体颜色 10 列基色（Word 风格：白/黑/灰 + 主题色）
private val FONT_COLS: List<List<String>> = run {
    val grays = listOf("FFFFFF", "F2F2F2", "D9D9D9", "BFBFBF", "808080", "595959")
    val darks = listOf("000000", "1A1A1A", "333333", "4D4D4D", "666666", "7F7F7F")
    val accents = listOf("C00000", "ED7D31", "FFC000", "548235", "00B0F0", "2E74B5", "7030A0", "44546A")
    listOf(grays, darks) + accents.map { hex ->
        val c = Color(0xFF000000L or hex.toLong(16))
        listOf(hex,
            toHex(lerp(c, Color.White, 0.6f)),
            toHex(lerp(c, Color.White, 0.3f)),
            toHex(lerp(c, Color.Black, 0.25f)),
            toHex(lerp(c, Color.Black, 0.5f)),
            toHex(lerp(c, Color.Black, 0.75f)))
    }
}

private val HIGHLIGHT_ROWS: List<List<String>> = listOf(
    listOf("yellow", "brightGreen", "turquoise", "pink", "blue"),
    listOf("red", "darkBlue", "darkCyan", "darkGreen", "darkMagenta"),
    listOf("darkRed", "darkYellow", "darkGray", "lightGray", "white"),
)

private val HIGHLIGHT_LABELS = mapOf(
    "yellow" to "黄色", "green" to "鲜绿", "turquoise" to "青绿", "pink" to "粉色",
    "blue" to "蓝色", "red" to "红色", "darkBlue" to "深蓝", "darkCyan" to "深青",
    "darkGreen" to "深绿", "darkMagenta" to "深紫", "darkRed" to "深红",
    "darkYellow" to "深黄", "darkGray" to "深灰", "lightGray" to "浅灰",
    "white" to "白色", "none" to "无",
)

private fun toHex(c: Color): String {
    fun ch(v: Float) = (v * 255f).toInt().toString(16).padStart(2, '0').uppercase()
    return ch(c.red) + ch(c.green) + ch(c.blue)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(
    selCount: Int,
    fmt: RunFmt?,
    brushActive: Boolean,
    onToggleBrush: () -> Unit,
    onPatch: (FontPatch) -> Unit,
    onClearSel: () -> Unit,
    onSelectAllPara: () -> Unit,
) {
    var showFontColor by remember { mutableStateOf(false) }
    var showHighlight by remember { mutableStateOf(false) }

    Surface(color = BarBg, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 行 0：选择状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (brushActive) {
                    Text(
                        "刷子模式：点目标词块应用，再点格式刷退出",
                        color = Color(0xFF9DBBFF), fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "已选 ${selCount} 块",
                        color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = onSelectAllPara) { Text("全选本段", fontSize = 13.sp) }
                TextButton(onClick = onClearSel) { Text("取消", fontSize = 13.sp) }
            }

            // 行 1：字体 + 字号
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FontMenu(fmt?.font, Modifier.weight(1f)) { onPatch(FontPatch(font = it)) }
                TextButton(onClick = { onPatch(FontPatch(fontReset = true)) }) {
                    Text("原字体", fontSize = 12.sp)
                }
                Text(
                    fmt?.sizeHp?.let { hp ->
                        if (hp % 2 == 0) "${hp / 2}pt" else "${hp / 2.0}pt"
                    } ?: "继承",
                    color = Color(0xFFBBBBBB), fontSize = 14.sp,
                )
                SmallBtn("A−") {
                    onPatch(FontPatch(fontSizeHp = ((fmt?.sizeHp ?: 21) - 2).coerceIn(4, 144)))
                }
                SmallBtn("A+") {
                    onPatch(FontPatch(fontSizeHp = ((fmt?.sizeHp ?: 21) + 2).coerceIn(4, 144)))
                }
            }

            // 行 2：B I U S | 格式刷 清除格式
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                ToggleBtn("B", fmt?.bold == true, weight = FontWeight.Bold) {
                    onPatch(FontPatch(bold = !(fmt?.bold ?: false)))
                }
                ToggleBtn("I", fmt?.italic == true, fontStyle = FontStyle.Italic) {
                    onPatch(FontPatch(italic = !(fmt?.italic ?: false)))
                }
                ToggleBtn("U", fmt?.underline == true, decoration = TextDecoration.Underline) {
                    onPatch(FontPatch(underline = !(fmt?.underline ?: false)))
                }
                ToggleBtn("S", fmt?.strike == true, decoration = TextDecoration.LineThrough) {
                    onPatch(FontPatch(strike = !(fmt?.strike ?: false)))
                }
                Spacer(Modifier.weight(1f))
                ToggleBtn("格式刷", brushActive) { onToggleBrush() }
                TextButton(onClick = { onPatch(FontPatch(clearAll = true)) }) {
                    Text("清除格式", fontSize = 13.sp, color = Color(0xFFFF8A8A))
                }
            }

            // 行 3：字体颜色
            SheetRow(
                label = "字体颜色",
                swatch = { ColorUnderlineSwatch(fmt?.colorHex ?: "auto") },
            ) { showFontColor = true }

            // 行 4：突出显示
            SheetRow(
                label = "突出显示",
                swatch = {
                    ColorBlockSwatch(
                        fmt?.highlight?.let { HIGHLIGHT_HEX[it.lowercase()]?.let { v -> Color(0xFF000000L or v) } }
                            ?: Color.Transparent,
                    )
                },
            ) { showHighlight = true }
        }
    }

    if (showFontColor) {
        PaletteSheet(
            title = "字体颜色",
            current = fmt?.colorHex,
            rows = FONT_COLS,
            resetLabel = "原色（继承）",
            onDismiss = { showFontColor = false },
            onPick = { hex ->
                showFontColor = false
                onPatch(FontPatch(colorHex = hex))
            },
            onReset = {
                showFontColor = false
                onPatch(FontPatch(colorReset = true))
            },
        )
    }
    if (showHighlight) {
        PaletteSheet(
            title = "突出显示",
            current = fmt?.highlight,
            rows = HIGHLIGHT_ROWS,
            resetLabel = "无高亮",
            onDismiss = { showHighlight = false },
            onPick = { name ->
                showHighlight = false
                onPatch(FontPatch(highlightName = name))
            },
            onReset = {
                showHighlight = false
                onPatch(FontPatch(highlightReset = true))
            },
        )
    }
}

// ---- 底部菜单里的行 ----

@Composable
private fun SheetRow(label: String, swatch: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        swatch()
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("›", color = Color(0xFF888888), fontSize = 18.sp)
    }
}

/** 字体颜色图标：A + 底部色条 */
@Composable
private fun ColorUnderlineSwatch(hex: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("A", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .size(width = 20.dp, height = 5.dp)
                .background(safeColor(hex) ?: Color.White)
        )
    }
}

/** 突出显示图标：A + 高亮底色块 */
@Composable
private fun ColorBlockSwatch(color: Color) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (color == Color.Transparent) Color(0xFF333333) else color),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun ToggleBtn(
    label: String,
    active: Boolean,
    fontStyle: FontStyle = FontStyle.Normal,
    weight: FontWeight = FontWeight.Normal,
    decoration: TextDecoration? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) SelBlue else BtnBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (active) Color.White else Color(0xFFDDDDDD),
            fontSize = 15.sp,
            fontWeight = weight,
            fontStyle = fontStyle,
            textDecoration = decoration,
        )
    }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BtnBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFFDDDDDD), fontSize = 14.sp)
    }
}

@Composable
private fun FontMenu(current: String?, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BtnBg)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                current ?: "字体（继承）",
                color = if (current != null) Color.White else Color(0xFFBBBBBB),
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FONTS.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(if (current == value) "✓ $label" else label, fontSize = 15.sp) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

private val FONTS = listOf(
    "宋体" to "SimSun",
    "黑体" to "SimHei",
    "微软雅黑" to "Microsoft YaHei",
    "楷体" to "KaiTi",
    "仿宋" to "FangSong",
    "Arial" to "Arial",
    "Times New Roman" to "Times New Roman",
    "Calibri" to "Calibri",
)

// ---- 色板弹窗（图三样式：主题色网格）----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaletteSheet(
    title: String,
    current: String?,
    rows: List<List<String>>,
    resetLabel: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetBg) {
        Column(Modifier.padding(horizontal = 12.dp).navigationBarsPadding()) {
            Text(title, color = Color(0xFF9DBBFF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            rows.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    row.forEach { hex ->
                        val color = Color(0xFF000000L or hex.toLong(16))
                        val selected = current?.equals(hex, ignoreCase = true) == true
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                                .border(
                                    width = if (selected) 2.dp else 0.5.dp,
                                    color = if (selected) Color.White else Color(0x44FFFFFF),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .clickable { onPick(hex) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(resetLabel, color = Color(0xFFBBBBBB)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
