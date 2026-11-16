package com.zhazi.editor.docx

import java.text.BreakIterator
import java.util.Locale

/**
 * 炸字分词：用 ICU BreakIterator（词典分词）把段落文本切成词块。
 * forcedBreaks 里的位置会被强制切开（双击词块 → 拆成单字）。
 */
object Segmenter {

    /** 含汉字的词块超过该长度时降级为单字块（设备词典缺失时的兜底） */
    private const val MAX_HAN_CHIP = 5

    fun chips(text: String, forcedBreaks: Set<Int> = emptySet()): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val bounds = sortedSetOf<Int>()
        bounds.add(0)
        bounds.add(text.length)

        val bi = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
        bi.setText(text)
        var b = bi.first()
        while (b != BreakIterator.DONE) {
            bounds.add(b)
            b = bi.next()
        }
        for (p in forcedBreaks) if (p in 1 until text.length) bounds.add(p)

        val list = bounds.toList()
        val out = ArrayList<IntRange>(list.size)
        for (k in 0 until list.size - 1) {
            val s = list[k]
            val e = list[k + 1] // 不含
            val piece = text.substring(s, e)
            if (piece.isBlank()) continue // 纯空白不成块
            if (piece.length > MAX_HAN_CHIP && hasHan(piece)) {
                // 词典失效兜底：长汉字串拆成单字
                var i = s
                while (i < e) {
                    out.add(IntRange(i, i))
                    i++
                }
            } else {
                out.add(IntRange(s, e - 1))
            }
        }
        return out
    }

    /** 词块拆成单字所需的强制切分位置 */
    fun charBreaks(range: IntRange): Set<Int> =
        (range.first + 1..range.last).toSet()

    private fun hasHan(s: String): Boolean = s.any { ch ->
        val c = ch.code
        c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF || c in 0xF900..0xFAFF
    }
}
