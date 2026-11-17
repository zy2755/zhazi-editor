package com.zhazi.editor.docx

/**
 * rPr（run 属性）操作：把 <w:rPr> 解析成"子元素原样片段"列表，
 * 修改时只动目标标签，其余标签字节级保留；新标签按 OOXML CT_RPr 规定的
 * 顺序插入，避免 Word 报"内容有问题"。
 */
class PrChild(val qname: String, val raw: String)

class RunFmt(
    val sizeHp: Int?,     // 字号（半磅），null = 继承
    val colorHex: String?,// 颜色，null = 继承
    val font: String?,    // 中文字体名，null = 继承
    val bold: Boolean?,   // null = 继承
    val italic: Boolean?,
    val underline: Boolean?,
    val strike: Boolean?,
    val highlight: String?, // w:highlight 的颜色名，null = 继承（未设置）
)

object RprOps {

    // CT_RPr 子元素的规范顺序（截取常用全集）
    private val ORDER = arrayOf(
        "w:rStyle", "w:rFonts", "w:b", "w:bCs", "w:i", "w:iCs", "w:caps", "w:smallCaps",
        "w:strike", "w:dstrike", "w:outline", "w:shadow", "w:emboss", "w:imprint", "w:noProof",
        "w:snapToGrid", "w:vanish", "w:webHidden", "w:color", "w:spacing", "w:w", "w:kern",
        "w:position", "w:sz", "w:szCs", "w:highlight", "w:u", "w:effect", "w:bdr", "w:shd",
        "w:fitText", "w:vertAlign", "w:rtl", "w:cs", "w:em", "w:lang", "w:eastAsianLayout",
        "w:specVanish", "w:oMath"
    )

    private fun orderIdx(q: String) = ORDER.indexOf(q)

    private class TEl(val name: String, val start: Int) {
        var end: Int = start
        val children = ArrayList<TEl>()
    }

    /** 把 rPrXml 解析为直接子元素列表（原样片段） */
    fun parseChildren(rprXml: String?): MutableList<PrChild> {
        val out = ArrayList<PrChild>()
        if (rprXml.isNullOrBlank()) return out
        val tokens = XmlScanner.scan(rprXml)
        val root = TEl("#", 0)
        val stack = ArrayDeque<TEl>()
        stack.addLast(root)
        for (t in tokens) {
            when (t.type) {
                TokenType.START_TAG -> {
                    val el = TEl(t.name ?: "?", t.start)
                    stack.last().children.add(el)
                    stack.addLast(el)
                }
                TokenType.SELF_CLOSE -> {
                    val el = TEl(t.name ?: "?", t.start).also { it.end = t.end }
                    stack.last().children.add(el)
                }
                TokenType.END_TAG -> {
                    while (stack.size > 1) {
                        val top = stack.removeLast()
                        top.end = t.end
                        if (top.name == t.name) break
                    }
                }
                else -> {}
            }
        }
        // 顶层若只有一个 w:rPr（传入的是完整 <w:rPr>…</w:rPr>），取其内部子元素
        var container = root
        if (root.children.size == 1 && root.children[0].name == "w:rPr") {
            container = root.children[0]
        }
        for (c in container.children) out.add(PrChild(c.name, rprXml.substring(c.start, c.end)))
        return out
    }

    /** 在规范位置插入/替换标签 */
    fun upsert(children: MutableList<PrChild>, qname: String, newRaw: String) {
        children.removeAll { it.qname == qname }
        val idx = orderIdx(qname)
        var insertAt = children.size
        for (k in children.indices) {
            if (orderIdx(children[k].qname) > idx) { insertAt = k; break }
        }
        children.add(insertAt, PrChild(qname, newRaw))
    }

    fun remove(children: MutableList<PrChild>, vararg qnames: String) {
        val set = qnames.toSet()
        children.removeAll { it.qname in set }
    }

    /** 序列化为完整 <w:rPr>…</w:rPr>；无内容时返回 null */
    fun serialize(children: List<PrChild>): String? =
        if (children.isEmpty()) null
        else children.joinToString("", prefix = "<w:rPr>", postfix = "</w:rPr>") { it.raw }

    // ---- 属性读写 ----

    private val ATTR = Regex("([\\w.:\\-]+)\\s*=\\s*\"([^\"]*)\"")

    fun attrVal(rawTag: String, name: String): String? {
        for (m in ATTR.findAll(rawTag)) {
            if (m.groupValues[1] == name) return XmlScanner.decodeEntities(m.groupValues[2])
        }
        return null
    }

    private fun buildTag(qname: String, attrs: Map<String, String>): String {
        val body = attrs.entries.joinToString(" ") { (k, v) ->
            "$k=\"${XmlScanner.escapeAttr(v)}\""
        }
        return if (body.isEmpty()) "<$qname/>" else "<$qname $body/>"
    }

    /** 读取 run 当前显式格式（不含样式继承） */
    fun readFmt(children: List<PrChild>): RunFmt {
        var sz: Int? = null; var color: String? = null
        var font: String? = null; var b: Boolean? = null; var i: Boolean? = null
        var u: Boolean? = null; var s: Boolean? = null; var hl: String? = null
        for (c in children) {
            when (c.qname) {
                "w:sz" -> sz = attrVal(c.raw, "w:val")?.toIntOrNull()
                "w:color" -> color = attrVal(c.raw, "w:val")
                "w:rFonts" -> font = attrVal(c.raw, "w:eastAsia") ?: attrVal(c.raw, "w:ascii")
                "w:b" -> b = flagVal(c.raw)
                "w:i" -> i = flagVal(c.raw)
                "w:u" -> u = attrVal(c.raw, "w:val")?.let { it != "none" } ?: true
                "w:strike" -> s = flagVal(c.raw)
                "w:highlight" -> hl = attrVal(c.raw, "w:val")
            }
        }
        return RunFmt(sz, color, font, b, i, u, s, hl)
    }

    private fun flagVal(rawTag: String): Boolean =
        attrVal(rawTag, "w:val")?.let { it != "0" && !it.equals("false", true) } ?: true

    // ---- 具体补丁 ----

    fun patchSize(children: MutableList<PrChild>, hp: Int) {
        upsert(children, "w:sz", buildTag("w:sz", mapOf("w:val" to hp.toString())))
        upsert(children, "w:szCs", buildTag("w:szCs", mapOf("w:val" to hp.toString())))
    }

    fun patchColor(children: MutableList<PrChild>, hex: String) {
        upsert(children, "w:color", buildTag("w:color", mapOf("w:val" to hex)))
    }

    /** 设置字体：ascii/hAnsi/eastAsia 一起改；已有的 cs/hint 等属性保留 */
    fun patchFont(children: MutableList<PrChild>, font: String) {
        val existing = children.firstOrNull { it.qname == "w:rFonts" }
        val attrs = LinkedHashMap<String, String>()
        if (existing != null) {
            for (m in ATTR.findAll(existing.raw)) {
                val k = m.groupValues[1]
                if (k != "w:ascii" && k != "w:hAnsi" && k != "w:eastAsia") {
                    attrs[k] = XmlScanner.decodeEntities(m.groupValues[2])
                }
            }
        }
        attrs["w:ascii"] = font
        attrs["w:hAnsi"] = font
        attrs["w:eastAsia"] = font
        upsert(children, "w:rFonts", buildTag("w:rFonts", attrs))
    }

    fun patchFlag(children: MutableList<PrChild>, on: Boolean, q: String, qCs: String) {
        val raw = if (on) "<$q/>" else buildTag(q, mapOf("w:val" to "0"))
        val rawCs = if (on) "<$qCs/>" else buildTag(qCs, mapOf("w:val" to "0"))
        upsert(children, q, raw)
        upsert(children, qCs, rawCs)
    }

    /** 下划线：single / none */
    fun patchUnderline(children: MutableList<PrChild>, on: Boolean) {
        upsert(children, "w:u", buildTag("w:u", mapOf("w:val" to if (on) "single" else "none")))
    }

    /** 删除线：有 / 无 */
    fun patchStrike(children: MutableList<PrChild>, on: Boolean) {
        val raw = if (on) "<w:strike/>" else buildTag("w:strike", mapOf("w:val" to "0"))
        upsert(children, "w:strike", raw)
    }

    /** 突出显示：ST_HighlightColor 名称（"none" = 显式无高亮） */
    fun patchHighlight(children: MutableList<PrChild>, name: String) {
        upsert(children, "w:highlight", buildTag("w:highlight", mapOf("w:val" to name)))
    }
}
