package com.zhazi.editor

import com.zhazi.editor.docx.DocxEditor
import com.zhazi.editor.docx.DocxParser
import com.zhazi.editor.docx.DocxZip
import com.zhazi.editor.docx.FontPatch
import com.zhazi.editor.docx.RprOps
import com.zhazi.editor.docx.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * docx 引擎单元测试（桌面 JVM，无 Android 依赖）。
 * 测试文档：src/test/resources/sample.docx
 */
class EngineTest {

    private val sampleBytes: ByteArray by lazy {
        javaClass.classLoader!!.getResourceAsStream("sample.docx")!!.readBytes()
    }
    private val originalXml: String by lazy {
        DocxZip.readDocumentXml(sampleBytes)!!
    }

    // ---- 解析 ----

    @Test
    fun `解析 - 段落数与文本`() {
        val doc = DocxParser.parse(originalXml)
        assertEquals(5, doc.paragraphs.size)
        assertEquals("保留适量空白。帽子、发饰等标志性部件应完整呈现。", doc.paragraphs[0].text)
        assertEquals("采用潦草、简省的粗蜡笔与油画棒画法，像随手快速画出的可爱小涂鸦。", doc.paragraphs[1].text)
        assertEquals("轮廓用\t粗重、不规则的黑色笔触。", doc.paragraphs[2].text)
        assertEquals("  A&B <测试> 尾部空格 ", doc.paragraphs[4].text)
    }

    @Test
    fun `解析 - 含tab的run受保护`() {
        val para = DocxParser.parse(originalXml).paragraphs[2]
        assertEquals(3, para.runs.size)
        assertTrue(para.runs[0].editable)
        assertFalse(para.runs[1].editable)
        assertEquals("\t粗重", para.runs[1].text)
    }

    // ---- 分词 ----

    @Test
    fun `分词 - 覆盖首尾且不产生纯空白块`() {
        val text = DocxParser.parse(originalXml).paragraphs[0].text
        val chips = Segmenter.chips(text)
        assertEquals(0, chips.first().first)
        assertEquals(text.length - 1, chips.last().last)
        assertTrue(chips.all { text.substring(it.first, it.last + 1).isNotBlank() })
        // 打印词块便于人工检查
        println("词块: " + chips.joinToString(" | ") { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `分词 - 双击拆字`() {
        val text = DocxParser.parse(originalXml).paragraphs[0].text
        val chips = Segmenter.chips(text)
        val first = chips.first()
        val sub = Segmenter.chips(text, Segmenter.charBreaks(first))
        assertEquals(first.first..first.first, sub.first { it.first == first.first })
        // 首块按字符数拆开
        val splitCount = sub.count { it.first in first.first..first.last }
        assertEquals(first.last - first.first + 1, splitCount)
    }

    @Test
    fun `分词 - 长汉字块降级为单字`() {
        // 任何含汉字的词块都不应超过 5 字（设备词典缺失时的兜底保证）
        DocxParser.parse(originalXml).paragraphs.forEach { para ->
            Segmenter.chips(para.text).forEach { r ->
                val piece = para.text.substring(r.first, r.last + 1)
                if (piece.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS }) {
                    assertTrue("词块过长: [$piece]", piece.length <= 5)
                }
            }
        }
    }

    // ---- 格式化与拼接 ----

    @Test
    fun `格式化 - 段内设置颜色字号取消加粗`() {
        val doc = DocxParser.parse(originalXml)
        // 段落2：[0,2)="采用"  [2,4)="潦草"(加粗)  其余为第三个 run
        val start = doc.paragraphs[1].runs[0].text.length
        val newXml = DocxEditor.applyFormat(
            doc, 1, start, start + 2,
            FontPatch(colorHex = "0070C0", fontSizeHp = 36, bold = false)
        )
        val doc1 = DocxParser.parse(newXml)
        assertEquals(doc.paragraphs[1].text, doc1.paragraphs[1].text)

        val liaocao = doc1.paragraphs[1].runs.first { it.text == "潦草" }
        val fmt = RprOps.readFmt(RprOps.parseChildren(liaocao.rprXml))
        assertEquals("0070C0", fmt.colorHex)
        assertEquals(36, fmt.sizeHp)
        assertEquals(false, fmt.bold)

        // 相邻 run 未被触碰
        val rest = doc1.paragraphs[1].runs.first { it.text.startsWith("、简省") }
        assertNull(rest.rprXml)
        assertTrue(rest.editable)

        // 书签仍在
        assertTrue(newXml.contains("<w:bookmarkStart"))
    }

    @Test
    fun `格式化 - 跨run选择自动拆run并保留原字号`() {
        val doc = DocxParser.parse(originalXml)
        val newXml = DocxEditor.applyFormat(doc, 0, 0, 4, FontPatch(colorHex = "FF8800"))
        val doc2 = DocxParser.parse(newXml)
        assertEquals(doc.paragraphs[0].text, doc2.paragraphs[0].text)

        assertEquals("FF8800", RprOps.readFmt(RprOps.parseChildren(doc2.paragraphs[0].runs[0].rprXml)).colorHex)
        assertEquals("FF8800", RprOps.readFmt(RprOps.parseChildren(doc2.paragraphs[0].runs[1].rprXml)).colorHex)
        // "保留"原有红色被覆盖，但字号 21 保留
        assertEquals(21, RprOps.readFmt(RprOps.parseChildren(doc2.paragraphs[0].runs[0].rprXml)).sizeHp)
        // 未被选中的第三个 run 原样（因部分覆盖拆分，它现在是最后一个 run）
        val restRun = doc2.paragraphs[0].runs.last()
        assertEquals("。帽子、发饰等标志性部件应完整呈现。", restRun.text)
        assertNull(restRun.rprXml)
    }

    @Test
    fun `格式化 - 清除全部格式`() {
        val doc = DocxParser.parse(originalXml)
        val doc2 = DocxParser.parse(DocxEditor.applyFormat(doc, 0, 0, 4, FontPatch(colorHex = "FF8800")))
        val doc3 = DocxParser.parse(DocxEditor.applyFormat(doc2, 0, 0, 4, FontPatch(clearAll = true)))
        assertNull(doc3.paragraphs[0].runs[0].rprXml)
        assertNull(doc3.paragraphs[0].runs[1].rprXml)
        assertEquals(doc.paragraphs[0].text, doc3.paragraphs[0].text)
    }

    @Test
    fun `格式化 - 空补丁原样返回`() {
        val doc = DocxParser.parse(originalXml)
        assertEquals(originalXml, DocxEditor.applyFormat(doc, 0, 0, 2, FontPatch()))
    }

    @Test
    fun `格式化 - 选中区包含受保护run时跳过该run`() {
        val doc = DocxParser.parse(originalXml)
        // 段落3: "轮廓用"(0,3) + "\t粗重"(3,6,受保护) + "、不规则的黑色笔触。"(6..)
        // 选中 [0, 8)：覆盖受保护 run，但它必须保持原样
        val newXml = DocxEditor.applyFormat(doc, 2, 0, 8, FontPatch(colorHex = "00B050"))
        val doc1 = DocxParser.parse(newXml)
        assertEquals(doc.paragraphs[2].text, doc1.paragraphs[2].text)
        val before = doc.paragraphs[2].runs[1]
        val after = doc1.paragraphs[2].runs[1]
        assertEquals(before.xmlEnd - before.xmlStart, after.xmlEnd - after.xmlStart)
        assertTrue(originalXml.substring(before.xmlStart, before.xmlEnd) ==
            newXml.substring(after.xmlStart, after.xmlEnd))
        // 两侧可编辑 run 已上色
        assertEquals("00B050", RprOps.readFmt(RprOps.parseChildren(doc1.paragraphs[2].runs[0].rprXml)).colorHex)
        assertEquals("00B050", RprOps.readFmt(RprOps.parseChildren(doc1.paragraphs[2].runs[2].rprXml)).colorHex)
    }

    @Test
    fun `格式化 - 格式刷语义重置为继承`() {
        val doc = DocxParser.parse(originalXml)
        // "潦草" 是加粗 run；格式刷语义：源无显式属性时，目标也应移除
        val newXml = DocxEditor.applyFormat(doc, 1, 2, 4, FontPatch(boldReset = true))
        val doc1 = DocxParser.parse(newXml)
        val fmt = RprOps.readFmt(RprOps.parseChildren(doc1.paragraphs[1].runs.first { it.text == "潦草" }.rprXml))
        assertNull(fmt.bold)
        assertEquals(doc.paragraphs[1].text, doc1.paragraphs[1].text)
    }

    // ---- 词块拆分 / 合并 ----

    @Test
    fun `词块 - 双击拆成单字`() {
        val state = EditorState()
        val doc = DocxParser.parse(originalXml)
        val chips0 = state.chipsOf(doc, 0)
        // 找第一个多字块（桌面 JVM 无词典可能全是单字块）
        val idx = chips0.indexOfFirst { it.first != it.last }
        assertTrue(idx >= 0)
        val target = chips0[idx]
        state.splitChip(doc, 0, idx)
        val chips1 = state.chipOverrides[0]!!
        assertEquals(chips0.size + (target.last - target.first), chips1.size)
        // 该块变成单字块
        assertEquals(target.first..target.first, chips1[idx])
        // 覆盖范围不变
        assertEquals(chips0.first().first, chips1.first().first)
        assertEquals(chips0.last().last, chips1.last().last)
    }

    @Test
    fun `词块 - 长按与右块合并`() {
        val state = EditorState()
        val doc = DocxParser.parse(originalXml)
        val chips0 = state.chipsOf(doc, 1)
        assertTrue(chips0.size >= 2)
        state.mergeChip(doc, 1, 0)
        val chips1 = state.chipOverrides[1]!!
        assertEquals(chips0.size - 1, chips1.size)
        assertEquals(chips0[0].first..chips0[1].last, chips1[0])
    }

    @Test
    fun `词块 - 末块长按与左块合并`() {
        val state = EditorState()
        val doc = DocxParser.parse(originalXml)
        val chips0 = state.chipsOf(doc, 1)
        val lastIdx = chips0.size - 1
        state.mergeChip(doc, 1, lastIdx)
        val chips1 = state.chipOverrides[1]!!
        assertEquals(chips0.size - 1, chips1.size)
        assertEquals(chips0[lastIdx - 1].first..chips0[lastIdx].last, chips1.last())
    }

    @Test
    fun `词块 - 与受保护块合并被拒绝`() {
        val state = EditorState()
        val doc = DocxParser.parse(originalXml)
        // 段落3: [轮廓][用][粗重(受保护)][、]...
        // 先拆"粗重"确认能定位受保护块，然后尝试把可编辑块与它合并
        val chips = state.chipsOf(doc, 2)
        val protIdx = chips.indexOfFirst {
            doc.paragraphs[2].runs[doc.paragraphs[2].runIndexAt(it.first)].let { !it.editable }
        }
        assertTrue(protIdx > 0)
        state.mergeChip(doc, 2, protIdx - 1) // 可编辑块 与 受保护块 合并 → 拒绝
        assertNull(state.chipOverrides[2])
        assertNotNull(state.errorMsg)
    }

    @Test
    fun `词块 - 拆分后选择清除`() {
        val state = EditorState()
        val doc = DocxParser.parse(originalXml)
        val idx = state.chipsOf(doc, 0).indexOfFirst { it.first != it.last }
        assertTrue(idx >= 0)
        state.selPara = 0
        state.selChips = setOf(idx)
        state.splitChip(doc, 0, idx)
        assertTrue(state.selChips.isEmpty())
    }

    @Test
    fun `词块 - 应用格式后选择保留`() {
        val state = EditorState()
        state.xml = originalXml
        val doc = DocxParser.parse(originalXml)
        state.selPara = 1
        state.selChips = setOf(1)
        state.applyPatch(doc, FontPatch(colorHex = "FF0000"))
        // 应用后选中保留，可继续 A-/A+ 连续调节
        assertEquals(setOf(1), state.selChips)
        assertEquals(1, state.undoStack.size)
        // 格式确实生效
        val doc1 = DocxParser.parse(state.xml!!)
        val chips1 = state.chipsOf(doc1, 1)
        val run = doc1.paragraphs[1].runs[doc1.paragraphs[1].runIndexAt(chips1[1].first)]
        assertEquals("FF0000", RprOps.readFmt(RprOps.parseChildren(run.rprXml)).colorHex)
    }

    @Test
    fun `格式化 - 高亮下划线删除线`() {
        val doc = DocxParser.parse(originalXml)
        val newXml = DocxEditor.applyFormat(
            doc, 1, 2, 4,
            FontPatch(highlightName = "yellow", underline = true, strike = true)
        )
        val doc1 = DocxParser.parse(newXml)
        assertEquals(doc.paragraphs[1].text, doc1.paragraphs[1].text)
        val fmt = RprOps.readFmt(
            RprOps.parseChildren(doc1.paragraphs[1].runs.first { it.text == "潦草" }.rprXml)
        )
        assertEquals("yellow", fmt.highlight)
        assertEquals(true, fmt.underline)
        assertEquals(true, fmt.strike)

        // 重置回继承
        val newXml2 = DocxEditor.applyFormat(
            doc1, 1, 2, 4,
            FontPatch(underlineReset = true, strikeReset = true, highlightReset = true)
        )
        val fmt2 = RprOps.readFmt(
            RprOps.parseChildren(
                DocxParser.parse(newXml2).paragraphs[1].runs.first { it.text == "潦草" }.rprXml
            )
        )
        assertNull(fmt2.underline)
        assertNull(fmt2.strike)
        assertNull(fmt2.highlight)
    }

    // ---- zip 回写 ----

    @Test
    fun `zip回写 - 其他条目字节一致且输出XML可解析`() {
        val doc = DocxParser.parse(originalXml)
        val newXml = DocxEditor.applyFormat(doc, 0, 0, 4, FontPatch(colorHex = "0070C0"))
        val out = DocxZip.rewrite(sampleBytes, newXml)

        // 条目逐一对比
        val zinA = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(sampleBytes))
        val zinB = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out))
        while (true) {
            val a = zinA.nextEntry ?: break
            val b = zinB.nextEntry
            assertEquals(a.name, b?.name)
            if (a.name != DocxZip.DOC_ENTRY) {
                assertTrue(a.name, zinA.readBytes().contentEquals(zinB.readBytes()))
            } else {
                zinB.readBytes()
            }
        }
        assertNull(zinB.nextEntry)

        // 输出的 document.xml 可被标准解析器解析
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(newXml.toByteArray()))

        // 回写结果可再次解析且文本一致
        val docOut = DocxParser.parse(DocxZip.readDocumentXml(out)!!)
        assertEquals(doc.paragraphs.size, docOut.paragraphs.size)
        doc.paragraphs.indices.forEach { assertEquals(doc.paragraphs[it].text, docOut.paragraphs[it].text) }
    }
}
