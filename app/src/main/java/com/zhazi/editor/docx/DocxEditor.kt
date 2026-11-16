package com.zhazi.editor.docx

/** 一次格式化补丁。null 值 = 不动；*Reset = 移除该属性（恢复继承） */
data class FontPatch(
    val fontSizeHp: Int? = null,
    val sizeReset: Boolean = false,
    val colorHex: String? = null,
    val colorReset: Boolean = false,
    val font: String? = null,
    val fontReset: Boolean = false,
    val bold: Boolean? = null,
    val boldReset: Boolean = false,
    val italic: Boolean? = null,
    val italicReset: Boolean = false,
    val clearAll: Boolean = false, // 清除全部显式字符格式
) {
    val isEmpty: Boolean
        get() = fontSizeHp == null && !sizeReset && colorHex == null && !colorReset &&
            font == null && !fontReset && bold == null && !boldReset &&
            italic == null && !italicReset && !clearAll
}

/**
 * 外科手术式编辑：只替换被选中的 <w:r> 元素本身，
 * 段落里其余任何 XML（书签、proofErr、图片、表格……）字节级原样保留。
 */
object DocxEditor {

    fun applyFormat(doc: DocxDocument, paraIdx: Int, selStart: Int, selEnd: Int, patch: FontPatch): String {
        if (patch.isEmpty) return doc.documentXml
        val para = doc.paragraphs.getOrNull(paraIdx) ?: return doc.documentXml

        data class Splice(val start: Int, val end: Int, val xml: String)
        val splices = ArrayList<Splice>()

        for ((k, run) in para.runs.withIndex()) {
            if (!run.editable) continue
            val rs = para.runTextStarts[k]
            val re = rs + run.text.length
            if (re <= selStart || rs >= selEnd) continue

            val from = maxOf(rs, selStart) - rs
            val to = minOf(re, selEnd) - rs

            val children = RprOps.parseChildren(run.rprXml)
            if (patch.clearAll) {
                RprOps.remove(
                    children,
                    "w:sz", "w:szCs", "w:color", "w:rFonts",
                    "w:b", "w:bCs", "w:i", "w:iCs",
                    "w:u", "w:highlight", "w:strike", "w:vertAlign"
                )
            } else {
                if (patch.sizeReset) RprOps.remove(children, "w:sz", "w:szCs")
                patch.fontSizeHp?.let { RprOps.patchSize(children, it) }

                if (patch.colorReset) RprOps.remove(children, "w:color")
                patch.colorHex?.let { RprOps.patchColor(children, it) }

                if (patch.fontReset) RprOps.remove(children, "w:rFonts")
                patch.font?.let { RprOps.patchFont(children, it) }

                if (patch.boldReset) RprOps.remove(children, "w:b", "w:bCs")
                patch.bold?.let { RprOps.patchFlag(children, it, "w:b", "w:bCs") }
                if (patch.italicReset) RprOps.remove(children, "w:i", "w:iCs")
                patch.italic?.let { RprOps.patchFlag(children, it, "w:i", "w:iCs") }
            }
            val newRpr = RprOps.serialize(children)

            val sb = StringBuilder()
            fun emit(text: String, rpr: String?) {
                if (text.isEmpty()) return
                sb.append("<w:r>")
                if (rpr != null) sb.append(rpr)
                sb.append("<w:t xml:space=\"preserve\">")
                    .append(XmlScanner.escapeXml(text))
                    .append("</w:t></w:r>")
            }
            emit(run.text.substring(0, from), run.rprXml)
            emit(run.text.substring(from, to), newRpr)
            emit(run.text.substring(to), run.rprXml)

            splices.add(Splice(run.xmlStart, run.xmlEnd, sb.toString()))
        }

        if (splices.isEmpty()) return doc.documentXml

        val xml = doc.documentXml
        val sorted = splices.sortedBy { it.start }
        val sb = StringBuilder(xml.length + 256)
        var pos = 0
        for (s in sorted) {
            sb.append(xml, pos, s.start).append(s.xml)
            pos = s.end
        }
        sb.append(xml, pos, xml.length)
        return sb.toString()
    }
}

/** docx 包读写：除 word/document.xml 外的所有条目字节级原样复制 */
object DocxZip {

    const val DOC_ENTRY = "word/document.xml"
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun readDocumentXml(bytes: ByteArray): String? {
        val zin = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
        while (true) {
            val e = zin.nextEntry ?: return null
            if (e.name == DOC_ENTRY) {
                val data = zin.readBytes()
                return data.toString(Charsets.UTF_8)
            }
        }
    }

    fun rewrite(original: ByteArray, newXml: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(original.size + 4096)
        val zout = java.util.zip.ZipOutputStream(out)
        val zin = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(original))
        while (true) {
            val e = zin.nextEntry ?: break
            val data = if (e.name == DOC_ENTRY) newXml.toByteArray(Charsets.UTF_8) else zin.readBytes()
            zout.putNextEntry(java.util.zip.ZipEntry(e.name))
            zout.write(data)
            zout.closeEntry()
        }
        zout.finish()
        zout.close()
        return out.toByteArray()
    }
}
