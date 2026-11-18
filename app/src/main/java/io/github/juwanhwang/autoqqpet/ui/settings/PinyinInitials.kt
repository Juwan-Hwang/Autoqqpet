package io.github.juwanhwang.autoqqpet.ui.settings

import java.nio.charset.Charset

/**
 * 汉字转拼音首字母（设置搜索用：输 hh 能搜到「火花」）。
 *
 * 办法是 GB2312 一级字库的排列规律：一级汉字（0xB0A1–0xD7F9）按拼音排序，
 * 拿每个字母的起始编码当分界就能定出首字母。二级字（按部首排）定不了，跳过；
 * 多音字按一级字库排序时的读音算，个别字可能对不上，搜索场景够用。
 */
object PinyinInitials {

    private val GB2312: Charset? = runCatching { Charset.forName("GB2312") }.getOrNull()

    /** 各首字母在一级字库里的起始编码（GB2312 双字节拼成一个整数）。没有 I、U、V。 */
    private val BOUNDS: List<Pair<Char, Int>> = listOf(
        'a' to 0xB0A1, 'b' to 0xB0C5, 'c' to 0xB2C1, 'd' to 0xB4EE, 'e' to 0xB6EA,
        'f' to 0xB7A2, 'g' to 0xB8C1, 'h' to 0xB9FE, 'j' to 0xBBF7, 'k' to 0xBFA6,
        'l' to 0xC0AC, 'm' to 0xC2E8, 'n' to 0xC4C3, 'o' to 0xC5B6, 'p' to 0xC5BE,
        'q' to 0xC6DA, 'r' to 0xC8BB, 's' to 0xC8F6, 't' to 0xCBFA, 'w' to 0xCDDA,
        'x' to 0xCEF4, 'y' to 0xD1B9, 'z' to 0xD4D1,
    )

    /** 单个汉字的拼音首字母（小写）；不是一级汉字、编码不可用时返回 null。 */
    fun initialOf(ch: Char): Char? {
        val cs = GB2312 ?: return null
        val bytes = runCatching { ch.toString().toByteArray(cs) }.getOrNull() ?: return null
        if (bytes.size != 2) return null
        val code = (bytes[0].toInt() and 0xFF) shl 8 or (bytes[1].toInt() and 0xFF)
        if (code < 0xB0A1 || code > 0xD7F9) return null
        var result = 'a'
        for ((letter, bound) in BOUNDS) {
            if (code >= bound) result = letter else break
        }
        return result
    }

    /**
     * 一段文字的首字母串：汉字换首字母，英文字母与数字原样（统一小写），其余字符跳过。
     * 例：「火花快清零提醒」→ "hhkqltx"、「SVIP 礼包提醒」→ "sviplbtx"。
     */
    fun initialsOf(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in 'a'..'z', in '0'..'9' -> sb.append(ch)
                in 'A'..'Z' -> sb.append(ch.lowercaseChar())
                else -> initialOf(ch)?.let { sb.append(it) }
            }
        }
        return sb.toString()
    }
}
