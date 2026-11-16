package com.zhazi.editor.docx

/**
 * 文档模型：只"理解"正文段落里的普通文本 run（w:r 只含 w:rPr + w:t），
 * 其余内容（表格、图片、域、超链接内文本……）一律作为"受保护"文本展示，
 * 编辑时绝不触碰其原始 XML——这是保证不毁文档的关键。
 */
class RunRef(
    val xmlStart: Int,      // 该 <w:r>…</w:r> 在 document.xml 中的起始偏移
    val xmlEnd: Int,        // 结束偏移（不含）
    val text: String,       // 解码后的显示文本
    val editable: Boolean,  // 是否可被炸字格式化
    val rprXml: String?,    // 原始 <w:rPr>…</w:rPr> 片段（含标签），可为 null
)

class Paragraph(
    val xmlStart: Int,
    val xmlEnd: Int,
    val runs: List<RunRef>,
) {
    val text: String = runs.joinToString("") { it.text }

    // 每个 run 的文本在 text 中的起始偏移
    val runTextStarts: IntArray = IntArray(runs.size).also {
        var acc = 0
        for (k in runs.indices) {
            it[k] = acc
            acc += runs[k].text.length
        }
    }

    /** 包含字符位置 pos 的 run 下标（越界时返回最后一个） */
    fun runIndexAt(pos: Int): Int {
        for (k in runs.indices) {
            if (pos < runTextStarts[k] + runs[k].text.length) return k
        }
        return runs.size - 1
    }
}

class DocxDocument(val documentXml: String, val paragraphs: List<Paragraph>)

object DocxParser {

    private val PLAIN_RUN_CHILDREN = setOf("w:rPr", "w:t", "w:lastRenderedPageBreak")
    // 这些容器里的 run 一律只读展示（MVP 不在容器内做格式化）
    private val PROTECTED_CONTAINERS = setOf("w:hyperlink", "w:ins", "w:smartTag", "w:fldSimple")

    fun parse(xml: String): DocxDocument {
        val root = buildTree(XmlScanner.scan(xml), xml.length)
        val docEl = root.children.firstOrNull { it.name == "w:document" }
            ?: return DocxDocument(xml, emptyList())
        val body = docEl.children.firstOrNull { it.name == "w:body" }
            ?: return DocxDocument(xml, emptyList())

        val paras = ArrayList<Paragraph>()
        for (b in body.children) {
            when (b.name) {
                "w:p" -> paras.add(parseParagraph(xml, b))
                "w:tbl" -> paras.add(placeholder(xml, b, "［表格 · 暂不支持编辑］"))
                "w:sdt" -> paras.add(placeholder(xml, b, "［内容控件 · 暂不支持编辑］"))
            }
        }
        return DocxDocument(xml, paras)
    }

    // ---- 元素树 ----

    private class El(val name: String, val start: Int) {
        var end: Int = start
        val children = ArrayList<El>()
        val texts = ArrayList<String>()
    }

    private fun buildTree(tokens: List<XmlToken>, totalLen: Int): El {
        val root = El("#root", 0).also { it.end = totalLen }
        val stack = ArrayDeque<El>()
        stack.addLast(root)
        for (t in tokens) {
            when (t.type) {
                TokenType.START_TAG -> {
                    val el = El(t.name ?: "?", t.start)
                    stack.last().children.add(el)
                    stack.addLast(el)
                }
                TokenType.SELF_CLOSE -> {
                    val el = El(t.name ?: "?", t.start).also { it.end = t.end }
                    stack.last().children.add(el)
                }
                TokenType.END_TAG -> {
                    // 宽容处理：弹到名字匹配为止
                    while (stack.size > 1) {
                        val top = stack.removeLast()
                        top.end = t.end
                        if (top.name == t.name) break
                    }
                }
                TokenType.TEXT -> stack.last().texts.add(t.text ?: "")
                else -> {}
            }
        }
        while (stack.size > 1) stack.removeLast().end = totalLen
        return root
    }

    // ---- 段落 / run ----

    private fun parseParagraph(xml: String, pEl: El): Paragraph {
        val runs = ArrayList<RunRef>()
        for (c in pEl.children) {
            when {
                c.name == "w:r" -> addRun(xml, c, runs, protected = false)
                c.name in PROTECTED_CONTAINERS ->
                    c.children.filter { it.name == "w:r" }.forEach { addRun(xml, it, runs, protected = true) }
            }
        }
        return Paragraph(pEl.start, pEl.end, runs)
    }

    private fun addRun(xml: String, rEl: El, out: MutableList<RunRef>, protected: Boolean) {
        var rprRaw: String? = null
        val parts = StringBuilder()
        var special = false
        for (c in rEl.children) {
            when (c.name) {
                "w:rPr" -> rprRaw = xml.substring(c.start, c.end)
                "w:t" -> parts.append(c.texts.joinToString("") { XmlScanner.decodeEntities(it) })
                "w:lastRenderedPageBreak" -> {}
                "w:tab" -> { parts.append('\t'); special = true }
                "w:br", "w:cr" -> { parts.append('\n'); special = true }
                else -> special = true // drawing / fldChar / sym / delText …
            }
        }
        val plain = !special && rEl.children.all { it.name in PLAIN_RUN_CHILDREN }
        val text = parts.toString()
        val editable = !protected && plain && text.isNotEmpty()
        out.add(RunRef(rEl.start, rEl.end, text, editable, rprRaw))
    }

    private fun placeholder(xml: String, el: El, label: String) = Paragraph(
        el.start, el.end,
        listOf(RunRef(el.start, el.end, label, editable = false, rprXml = null))
    )
}
