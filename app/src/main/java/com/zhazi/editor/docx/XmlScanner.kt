package com.zhazi.editor.docx

/**
 * 轻量 XML 令牌扫描器：记录每个令牌在源字符串中的精确偏移，
 * 这是"外科手术式"编辑的基础——只替换需要改的 <w:r> 元素，其余字节原样保留。
 *
 * 面向 OOXML part 文件（word/document.xml），实践中不含 DOCTYPE/CDATA，
 * 但仍做了宽容处理；文本内容只会以实体形式出现（Word 如此写出）。
 */
enum class TokenType { START_TAG, END_TAG, SELF_CLOSE, TEXT, COMMENT, PI, MISC }

class XmlToken(
    val type: TokenType,
    val name: String?,      // 标签名（仅标签令牌）
    val start: Int,         // 令牌首字符偏移
    val end: Int,           // 令牌结束后的偏移（不含）
    val text: String? = null, // TEXT 令牌的内容（CDATA 已剥壳，未解码实体）
)

object XmlScanner {

    fun scan(s: String): List<XmlToken> {
        val out = ArrayList<XmlToken>()
        val n = s.length
        var i = 0
        while (i < n) {
            if (s[i] != '<') {
                var j = i + 1
                while (j < n && s[j] != '<') j++
                out.add(XmlToken(TokenType.TEXT, null, i, j, s.substring(i, j)))
                i = j
                continue
            }
            when {
                s.startsWith("<!--", i) -> {
                    val e = s.indexOf("-->", i + 4)
                    val end = if (e < 0) n else e + 3
                    out.add(XmlToken(TokenType.COMMENT, null, i, end))
                    i = end
                }
                s.startsWith("<![CDATA[", i) -> {
                    val e = s.indexOf("]]>", i + 9)
                    val end = if (e < 0) n else e + 3
                    out.add(XmlToken(TokenType.TEXT, null, i, end, s.substring(i + 9, if (e < 0) n else e)))
                    i = end
                }
                s.startsWith("<?", i) -> {
                    val e = s.indexOf("?>", i + 2)
                    val end = if (e < 0) n else e + 2
                    out.add(XmlToken(TokenType.PI, null, i, end))
                    i = end
                }
                s.startsWith("<!", i) -> {
                    var j = i + 2
                    var depth = 1
                    while (j < n && depth > 0) {
                        when (s[j]) {
                            '<' -> depth++
                            '>' -> depth--
                        }
                        j++
                    }
                    out.add(XmlToken(TokenType.MISC, null, i, j))
                    i = j
                }
                else -> {
                    // 普通标签：引号内容忍 '>'（如属性值中含 >）
                    var j = i + 1
                    var quote: Char? = null
                    while (j < n) {
                        val ch = s[j]
                        if (quote != null) {
                            if (ch == quote) quote = null
                        } else when (ch) {
                            '"', '\'' -> quote = ch
                            '>' -> break
                        }
                        j++
                    }
                    val end = if (j < n) j + 1 else n
                    val inner = s.substring(i + 1, minOf(j, n))
                    val isEnd = inner.startsWith("/")
                    val selfClose = inner.endsWith("/")
                    out.add(
                        XmlToken(
                            when {
                                isEnd -> TokenType.END_TAG
                                selfClose -> TokenType.SELF_CLOSE
                                else -> TokenType.START_TAG
                            },
                            tagName(inner), i, end
                        )
                    )
                    i = end
                }
            }
        }
        return out
    }

    private fun tagName(inner: String): String {
        var t = inner
        if (t.startsWith("/")) t = t.substring(1)
        if (t.endsWith("/")) t = t.substring(0, t.length - 1)
        t = t.trimStart()
        val k = t.indexOfFirst { it.isWhitespace() }
        return if (k >= 0) t.substring(0, k) else t
    }

    private val ENT = Regex("&#x([0-9a-fA-F]+);|&#([0-9]+);|&(amp|lt|gt|quot|apos);")

    fun decodeEntities(s: String): String =
        ENT.replace(s) { m ->
            runCatching {
                when {
                    m.groupValues[1].isNotEmpty() -> String(Character.toChars(m.groupValues[1].toInt(16)))
                    m.groupValues[2].isNotEmpty() -> String(Character.toChars(m.groupValues[2].toInt()))
                    else -> when (m.groupValues[3]) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        else -> "'"
                    }
                }
            }.getOrDefault(m.value)
        }

    fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '\r' -> {} // OOXML 文本中不写裸回车
                ch.code < 0x20 && ch != '\t' && ch != '\n' -> {} // 非法控制字符丢弃
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun escapeAttr(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
