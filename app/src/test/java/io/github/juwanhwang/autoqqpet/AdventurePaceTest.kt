package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.DayPlanner
import io.github.juwanhwang.autoqqpet.pet.AdvPace
import io.github.juwanhwang.autoqqpet.pet.AdventureInfo
import io.github.juwanhwang.autoqqpet.pet.Attr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冒险真实耗时（2026-10-19）：只按名义时长算时速会系统性高估——一趟的真实墙钟还摊着
 * 结算等待、任务间隔与被天色不对召回的短趟。记账按天分桶、滚动最近几天聚合：
 * 「总耗时 ÷ 完成数」就是每完成一趟的真实耗时；被召回趟只进耗时不进完成数。
 * 样本不足（冷启动）返回 null，求解器回退名义时长，与没有这份数据时行为一致。
 */
class AdventurePaceTest {

    private val name = "附近走走"

    /** 依次记账造一段多日记录：每项 = (天, 耗时ms, 是否完成)。 */
    private fun ledger(vararg rows: Triple<Int, Long, Boolean>): String {
        var text: String? = null
        for ((day, ms, done) in rows) text = AdvPace.recorded(text, name, day, ms, done)
        return text ?: ""
    }

    @Test fun multiDayHalfRecalledDiscountsPerCompletion() {
        // 两天、6 次派发：3 趟跑完（约 100 秒一趟的整轮墙钟）、3 趟被召回（约 27 秒）
        val text = ledger(
            Triple(98, 102_000L, true), Triple(98, 27_000L, false),
            Triple(98, 102_000L, true), Triple(98, 27_000L, false),
            Triple(100, 100_000L, true), Triple(100, 26_000L, false),
        )
        val minutes = AdvPace.minutesPerCompletion(text, name, today = 100)
        // 总耗时 384 秒 ÷ 3 次完成 = 128 秒/趟
        assertEquals(384_000.0 / 3 / 60_000.0, minutes!!, 1e-9)
        // 若不摊召回趟，只会算出约 101 秒——实测值必须明显更贵，时速才算修准
        assertTrue(minutes > 101_000.0 / 60_000.0)
        // 全表口径与单查一致
        assertEquals(minutes, AdvPace.minutesByName(text, today = 100)[name]!!, 1e-9)
    }

    @Test fun tooFewCompletionsIsColdStart() {
        val text = ledger(Triple(100, 100_000L, true), Triple(100, 100_000L, true), Triple(100, 27_000L, false))
        assertNull(AdvPace.minutesPerCompletion(text, name, today = 100))   // 只完成 2 趟，不拿偶然当规律
        assertTrue(AdvPace.minutesByName(text, today = 100).isEmpty())
        assertNull(AdvPace.minutesPerCompletion(null, name, today = 100))
        assertNull(AdvPace.minutesPerCompletion("", name, today = 100))
    }

    @Test fun windowExcludesOldDays() {
        // 10 天前攒满样本也不算数：窗口只看最近 WINDOW_DAYS 天
        val old = ledger(
            Triple(90, 100_000L, true), Triple(90, 100_000L, true),
            Triple(90, 100_000L, true), Triple(90, 100_000L, true),
        )
        assertNull(AdvPace.minutesPerCompletion(old, name, today = 100))
        // 记新账时窗口外的旧桶会被清掉，存量不会越攒越旧
        val pruned = AdvPace.recorded(old, name, day = 100, elapsedMs = 90_000L, completed = true)
        assertTrue(AdvPace.parse(pruned).none { it.day == 90 })
    }

    @Test fun invalidRecordsIgnored() {
        assertEquals("", AdvPace.recorded(null, name, day = 100, elapsedMs = 0L, completed = true))
        assertEquals("x", AdvPace.recorded("x", "", day = 100, elapsedMs = 5_000L, completed = true))
        assertTrue(AdvPace.parse("garbage;;a|b:c").isEmpty())   // 坏条目跳过，不炸
    }

    @Test fun adventureRateUsesMeasuredMinutes() {
        val walk = AdventureInfo(name, 0, 0, false, Attr.NONE, 0, 0, 0, 0, 1, 45, true)
        // 名义：价值 0.5（基线）× 60 ÷ 0.75 分钟 = 40/小时
        val nominal = DayPlanner(Attr.STRENGTH).adventureRate(walk)
        assertEquals(40.0, nominal, 1e-9)
        // 实测 2 分钟/趟：0.5 × 60 ÷ 2 = 15/小时，按真实耗时打折
        val measured = DayPlanner(Attr.STRENGTH, learnedTripMinutes = mapOf(name to 2.0)).adventureRate(walk)
        assertEquals(15.0, measured, 1e-9)
        // 表里没有这个名字（样本不足被过滤）→ 与名义完全一致
        val other = DayPlanner(Attr.STRENGTH, learnedTripMinutes = mapOf("诗和远方" to 130.0)).adventureRate(walk)
        assertEquals(nominal, other, 1e-9)
    }
}
