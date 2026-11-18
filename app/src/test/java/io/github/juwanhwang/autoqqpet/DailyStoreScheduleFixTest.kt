package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.optimizer.stepMinutesForElapsed
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 排班计数三修（2026-10-29 群友实报后），统一账本（2.1.13）后按台账行验证同一批语义：
 * 换轮只清做完的步、没做完的进度随台账归属搬进新一轮；开工写行即带归属，结算补记的行同样带；
 * 冒险步分钟开工时不记名义时长，完成落账时按实际用时算（召回趟只跑几十秒不按整趟算）。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreScheduleFixTest {

    private fun freshStore(): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return DailyStore(ctx)
    }

    /** 今天某分钟时刻（毫秒）：用结算补记按指定开始时间落行，避免同秒落行被台账去重当成同一趟。 */
    private fun todayAt(minuteOfDay: Int): Long =
        java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + minuteOfDay * 60_000L

    @Test fun carryMovesProgressIntoNewRound() {
        val s = freshStore()
        s.noteSettledUncounted("6100_a", "study", todayAt(60), 2700, stepId = "a")
        s.noteSettledUncounted("6100_b", "study", todayAt(120), 2700, stepId = "a")
        assertEquals(2 to 90, s.stepProgress("a"))
        val next = s.scheduleRound() + 1
        s.retagStepRound("a", next)
        s.setSchedule(0, next)
        assertEquals(2 to 90, s.stepProgress("a"))
    }

    @Test fun progressStaysInOldRoundWithoutCarry() {
        val s = freshStore()
        s.noteSettledUncounted("6100_a", "study", todayAt(60), 2700, stepId = "a")
        assertEquals(1 to 45, s.stepProgress("a"))
        s.setSchedule(0, s.scheduleRound() + 1)   // 没搬运就换轮：旧轮进度自然作废
        assertEquals(0 to 0, s.stepProgress("a"))
    }

    @Test fun advStepMinutesDeferredUntilSettle() {
        val s = freshStore()
        s.countTaskOnce("6700_1", "adventure", 120, adventureMinutes = true, seconds = 7200, stepId = "a")
        assertEquals(1 to 0, s.stepProgress("a"))            // 开工只记次数，分钟不按名义时长记
        assertEquals("a", s.journalStepOf("6700_1"))
        s.noteDone("6700_1", "adventure", 150)               // 完成落账：实际 150 秒
        assertEquals(1 to 3, s.stepProgress("a"))            // 分钟按实际用时向上取整到 3
        s.setSchedule(0, s.scheduleRound() + 1)              // 换轮后旧轮归属不再认
        assertNull(s.journalStepOf("6700_1"))
        assertEquals(0 to 0, s.stepProgress("a"))
    }

    @Test fun unconfirmedIntentRoundTripAndDayScope() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val s = freshStore()
        assertNull(s.unconfirmedIntent())
        s.noteUnconfirmedIntent("a", "study", 45, 123_000L)
        val got = s.unconfirmedIntent()!!
        assertEquals("a", got.stepId)
        assertEquals("study", got.tag)
        assertEquals(45, got.minutes)
        assertEquals(123_000L, got.sentAtMs)
        s.clearUnconfirmedIntent()
        assertNull(s.unconfirmedIntent())
        // 昨天的意图不认（手写一条旧日期进去）
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        prefs.edit().putString("unc_intent", "0|a|study|45|123000").commit()
        assertNull(s.unconfirmedIntent())
    }

    @Test fun elapsedToStepMinutesRounds() {
        assertEquals(1, stepMinutesForElapsed(45))
        assertEquals(3, stepMinutesForElapsed(150))
        assertEquals(0, stepMinutesForElapsed(20))
        assertEquals(2, stepMinutesForElapsed(90))
        assertEquals(0, stepMinutesForElapsed(-5))
    }
}
