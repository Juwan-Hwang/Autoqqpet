package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.PauseGate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #11（公开仓，已承诺「下个版本加上」）：息屏不发包 + 夜间时段不发包。
 * 夜间时段可自定（默认 01:30–06:30），开始晚于结束表示跨零点。这里只测纯判断：
 * 默认与自定时段的边界、跨零点命中、两开关组合、暂停时下一次唤醒排多久。
 */
class PauseGateTest {

    private fun at(h: Int, m: Int, s: Int = 0): LocalDateTime = LocalDateTime.of(2026, 10, 19, h, m, s)
    private fun tm(h: Int, m: Int): LocalTime = LocalTime.of(h, m)

    private val defStart = PauseGate.DEFAULT_START_MIN   // 01:30
    private val defEnd = PauseGate.DEFAULT_END_MIN       // 06:30

    @Test fun defaultWindowBoundaries() {
        assertFalse(PauseGate.inWindow(tm(1, 29), defStart, defEnd))
        assertTrue(PauseGate.inWindow(tm(1, 30), defStart, defEnd))
        assertTrue(PauseGate.inWindow(tm(6, 29), defStart, defEnd))
        assertFalse(PauseGate.inWindow(tm(6, 30), defStart, defEnd))
        assertFalse(PauseGate.inWindow(tm(0, 0), defStart, defEnd))
        assertFalse(PauseGate.inWindow(tm(12, 0), defStart, defEnd))
    }

    /** 跨零点时段 23:00–02:00：开始一侧（当晚）与结束一侧（次日凌晨）都命中，两头边界不含结束时刻。 */
    @Test fun crossMidnightWindow() {
        val s = 23 * 60; val e = 2 * 60
        assertFalse(PauseGate.inWindow(tm(22, 59), s, e))
        assertTrue(PauseGate.inWindow(tm(23, 0), s, e))
        assertTrue(PauseGate.inWindow(tm(23, 59), s, e))
        assertTrue(PauseGate.inWindow(tm(0, 0), s, e))
        assertTrue(PauseGate.inWindow(tm(1, 59), s, e))
        assertFalse(PauseGate.inWindow(tm(2, 0), s, e))
        assertFalse(PauseGate.inWindow(tm(12, 0), s, e))
    }

    /** 改成不跨零点的自定时段 22:00–23:30：只认当天这一段，凌晨不命中。 */
    @Test fun customSameDayWindow() {
        val s = 22 * 60; val e = 23 * 60 + 30
        assertFalse(PauseGate.inWindow(tm(21, 59), s, e))
        assertTrue(PauseGate.inWindow(tm(22, 0), s, e))
        assertTrue(PauseGate.inWindow(tm(23, 29), s, e))
        assertFalse(PauseGate.inWindow(tm(23, 30), s, e))
        assertFalse(PauseGate.inWindow(tm(0, 30), s, e))
    }

    /** 开始 == 结束是空时段：任何时刻都不暂停，别误读成全天。 */
    @Test fun emptyWindowNeverPauses() {
        assertFalse(PauseGate.inWindow(tm(0, 0), 0, 0))
        assertFalse(PauseGate.inWindow(tm(1, 30), 90, 90))
        assertFalse(PauseGate.inWindow(tm(23, 59), 90, 90))
        assertNull(PauseGate.check(screenOn = false, nightOn = true, nightStartMin = 90, nightEndMin = 90, interactive = true, now = at(1, 30)))
    }

    @Test fun bothOffNeverPauses() {
        assertNull(PauseGate.check(screenOn = false, nightOn = false, nightStartMin = defStart, nightEndMin = defEnd, interactive = false, now = at(3, 0)))
        assertNull(PauseGate.check(screenOn = false, nightOn = false, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(3, 0)))
    }

    @Test fun screenPauseOnlyWhenScreenOff() {
        assertNotNull(PauseGate.check(screenOn = true, nightOn = false, nightStartMin = defStart, nightEndMin = defEnd, interactive = false, now = at(12, 0)))
        assertNull(PauseGate.check(screenOn = true, nightOn = false, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(12, 0)))
        // 白天息屏与夜间时段无关：时段内亮屏不触发息屏暂停
        assertNull(PauseGate.check(screenOn = true, nightOn = false, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(3, 0)))
    }

    @Test fun nightPauseArmsUntilWindowEnd() {
        val p = PauseGate.check(screenOn = false, nightOn = true, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(1, 30))
        assertNotNull(p)
        assertEquals("1:30 起正好 5 小时", 5 * 60 * 60_000L, p!!.armMs)
        val p2 = PauseGate.check(screenOn = false, nightOn = true, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(6, 29, 30))
        assertEquals(30_000L, p2!!.armMs)
        assertNull(PauseGate.check(screenOn = false, nightOn = true, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(6, 30)))
        assertNull(PauseGate.check(screenOn = false, nightOn = true, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(1, 29)))
    }

    /** 跨零点时段的唤醒：开始一侧睡到次日结束时刻，结束一侧睡到当天结束时刻；文案写的是自定时段。 */
    @Test fun crossMidnightArmsToEnd() {
        val s = 23 * 60; val e = 2 * 60
        val evening = PauseGate.check(screenOn = false, nightOn = true, nightStartMin = s, nightEndMin = e, interactive = true, now = at(23, 30))
        assertNotNull(evening)
        assertEquals("23:30 → 次日 02:00", 150 * 60_000L, evening!!.armMs)
        assertTrue(evening.text.contains("23:00–02:00"))
        val afterMidnight = PauseGate.check(screenOn = false, nightOn = true, nightStartMin = s, nightEndMin = e, interactive = true, now = at(0, 30))
        assertEquals("00:30 → 当天 02:00", 90 * 60_000L, afterMidnight!!.armMs)
        val lastMinute = PauseGate.check(screenOn = false, nightOn = true, nightStartMin = s, nightEndMin = e, interactive = true, now = at(1, 59, 30))
        assertEquals(30_000L, lastMinute!!.armMs)
        assertNull(PauseGate.check(screenOn = false, nightOn = true, nightStartMin = s, nightEndMin = e, interactive = true, now = at(2, 0)))
        assertNull(PauseGate.check(screenOn = false, nightOn = true, nightStartMin = s, nightEndMin = e, interactive = true, now = at(22, 59)))
    }

    @Test fun unionWhenBothOn() {
        // 夜间时段内且亮屏：夜间暂停（一次睡到时段结束）
        val night = PauseGate.check(screenOn = true, nightOn = true, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(2, 0))
        assertNotNull(night)
        assertTrue(night!!.text.contains("夜间"))
        assertEquals(4 * 60 * 60_000L + 30 * 60_000L, night.armMs)
        // 白天息屏：息屏暂停，按复查间隔醒
        val day = PauseGate.check(screenOn = true, nightOn = true, nightStartMin = defStart, nightEndMin = defEnd, interactive = false, now = at(12, 0))
        assertNotNull(day)
        assertTrue(day!!.text.contains("息屏"))
        assertEquals(PauseGate.SCREEN_RECHECK_MS, day.armMs)
        // 白天亮屏：都不暂停
        assertNull(PauseGate.check(screenOn = true, nightOn = true, nightStartMin = defStart, nightEndMin = defEnd, interactive = true, now = at(12, 0)))
    }
}
