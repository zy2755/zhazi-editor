package com.zhazi.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhazi.editor.docx.FontPatch
import com.zhazi.editor.docx.RunFmt

private val BarBg = Color(0xFF202020)

private val PALETTE = listOf(
    "黑" to "000000",
    "红" to "FF0000",
    "橙" to "ED7D31",
    "黄" to "FFC000",
    "绿" to "00B050",
    "蓝" to "0070C0",
    "紫" to "7030A0",
    "灰" to "808080",
    "白" to "FFFFFF",
)

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

@Composable
fun FormatBar(
    selCount: Int,
    fmt: RunFmt?,
    brushActive: Boolean,
    onToggleBrush: () -> Unit,
    onPatch: (FontPatch) -> Unit,
    onClearSel: () -> Unit,
    onSelectAllPara: () -> Unit,
) {
    Surface(
        color = BarBg,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (brushActive) {
                    Text(
                        "刷子模式：点目标词块应用源格式，再点格式刷退出",
                        color = Color(0xFF9DBBFF),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "已选 ${selCount} 块",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = onSelectAllPara) { Text("全选本段", fontSize = 13.sp) }
                TextButton(onClick = onClearSel) { Text("取消", fontSize = 13.sp) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                // 加粗 / 斜体
                ToggleBtn(
                    "B", active = fmt?.bold == true,
                    fontStyle = FontStyle.Normal, weight = FontWeight.Bold,
                ) { onPatch(FontPatch(bold = !(fmt?.bold ?: false))) }
                ToggleBtn(
                    "I", active = fmt?.italic == true,
                    fontStyle = FontStyle.Italic, weight = FontWeight.Normal,
                ) { onPatch(FontPatch(italic = !(fmt?.italic ?: false))) }

                BarDot()

                // 字号（半磅 → 磅，奇数半磅显示 0.5）
                Text(
                    fmt?.sizeHp?.let { hp ->
                        if (hp % 2 == 0) "${hp / 2}pt" else "${hp / 2.0}pt"
                    } ?: "继承",
                    color = Color(0xFFBBBBBB), fontSize = 13.sp,
                )
                SmallBtn("A−") {
                    val cur = fmt?.sizeHp ?: 21
                    onPatch(FontPatch(fontSizeHp = (cur - 2).coerceIn(4, 144)))
                }
                SmallBtn("A+") {
                    val cur = fmt?.sizeHp ?: 21
                    onPatch(FontPatch(fontSizeHp = (cur + 2).coerceIn(4, 144)))
                }

                BarDot()

                // 颜色
                PALETTE.forEach { (name, hex) ->
                    ColorDot(name, hex) { onPatch(FontPatch(colorHex = hex)) }
                }
                TextButton(onClick = { onPatch(FontPatch(colorReset = true)) }) {
                    Text("原色", fontSize = 13.sp)
                }

                BarDot()

                // 字体
                FontMenu(fmt?.font) { onPatch(FontPatch(font = it)) }
                TextButton(onClick = { onPatch(FontPatch(fontReset = true)) }) {
                    Text("原字体", fontSize = 13.sp)
                }

                BarDot()

                // 格式刷：以选中词块的显式格式为源，点目标词块应用
                ToggleBtn(
                    "格式刷",
                    active = brushActive,
                    fontStyle = FontStyle.Normal,
                    weight = FontWeight.Normal,
                ) { onToggleBrush() }

                BarDot()

                TextButton(onClick = { onPatch(FontPatch(clearAll = true)) }) {
                    Text("清除格式", fontSize = 13.sp, color = Color(0xFFFF8A8A))
                }
            }
        }
    }
}

@Composable
private fun BarDot() {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(1.dp, 22.dp)
            .background(Color(0xFF3A3A3A))
    )
}

@Composable
private fun ToggleBtn(
    label: String,
    active: Boolean,
    fontStyle: FontStyle,
    weight: FontWeight,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) SelBlue else Color(0xFF2E2E2E))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (active) Color.White else Color(0xFFDDDDDD), fontSize = 15.sp, fontWeight = weight, fontStyle = fontStyle)
    }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2E2E2E))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFFDDDDDD), fontSize = 14.sp)
    }
}

@Composable
private fun ColorDot(name: String, hex: String, onClick: () -> Unit) {
    val color = runCatching {
        Color(0xFF000000L or hex.toLong(16))
    }.getOrDefault(Color.Gray)
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, fontSize = 11.sp, color = if (name == "白" || name == "黄") Color.Black else Color.White)
    }
}

@Composable
private fun FontMenu(current: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2E2E2E))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                current ?: "字体",
                color = if (current != null) Color.White else Color(0xFFBBBBBB),
                fontSize = 14.sp,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FONTS.forEach { (label, value) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (current == value) "✓ $label" else label,
                            fontSize = 15.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}
