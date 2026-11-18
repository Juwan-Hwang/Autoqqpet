package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.ui.settings.PinyinInitials
import org.junit.Assert.assertEquals
import org.junit.Test

/** 拼音首字母（设置搜索用）：常见词的首字母要对，英文与数字原样保留，标点跳过。 */
class PinyinInitialsTest {

    @Test fun commonWords() {
        assertEquals("hh", PinyinInitials.initialsOf("火花"))
        assertEquals("xx", PinyinInitials.initialsOf("学习"))
        assertEquals("dg", PinyinInitials.initialsOf("打工"))
        assertEquals("mx", PinyinInitials.initialsOf("冒险"))
        assertEquals("lfd", PinyinInitials.initialsOf("领福袋"))
        assertEquals("mrqd", PinyinInitials.initialsOf("每日签到"))
        assertEquals("zdlhyfd", PinyinInitials.initialsOf("自动领好友福袋"))
        assertEquals("hhkqltx", PinyinInitials.initialsOf("火花快清零提醒"))
    }

    @Test fun latinAndDigitsKeptPunctuationSkipped() {
        assertEquals("sviplb", PinyinInitials.initialsOf("SVIP 礼包"))
        assertEquals("hh", PinyinInitials.initialsOf("（火花）"))
        assertEquals("pk83", PinyinInitials.initialsOf("PK 83"))
    }
}
