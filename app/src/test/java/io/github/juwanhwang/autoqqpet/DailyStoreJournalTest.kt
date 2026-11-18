package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.Reward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 统一账本（2.1.13）：台账是唯一账本——步进度、今日计数、疲劳、实收全部从台账行现算。
 * 覆盖 2026-11-07 群友 Mormont 实报的形态：登记丢失的那节结算补行后步进度自动对齐；
 * 结算被拒的行不计完成与步进度、但墙钟仍进疲劳；实收记在行上、与场外存量不双计。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreJournalTest {

    private fun freshStore(): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return DailyStore(ctx)
    }

    private fun todayAt(minuteOfDay: Int): Long =
        java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + minuteOfDay * 60_000L

    /** Mormont 形态：11 节学习全结算，步上原本只记 10 节——丢登记的那节补行并补归属后，步进度必须与台账一致。 */
    @Test fun lostRegistrationSessionReconcilesStepProgress() {
        val s = freshStore()
        for (i in 0 until 10) {
            s.noteSettledUncounted("6100_s$i", "study", todayAt(i * 60), 1800, stepId = "t2")
        }
        // 第 11 节：开工登记丢了，结算时先进台账（无归属），认出是未确认开工后补归属
        s.noteSettledUncounted("6100_lost", "study", todayAt(600), 1800)
        assertEquals(10 to 300, s.stepProgress("t2"))
        assertTrue(s.retagLedgerStep("6100_lost", "t2"))
        assertEquals(11 to 330, s.stepProgress("t2"))
        // 今日口径同源：已开工与已完成都应是 11
        assertEquals(11, s.counts()["study"])
        assertEquals(11, s.doneTally(Long.MAX_VALUE).study)
        assertEquals(330 * 60, s.doneTally(Long.MAX_VALUE).studyWorkSec)
    }

    /** 结算被拒：完成数与步进度不认，疲劳按墙钟照算（与被拒分支“耗时与奖励分开”的口径一致）。 */
    /** 哨兵高水位与步归属一览：高水位按轮次＋步位存取；归属一览按 stepProgress 同口径汇总每个步 id 名下的账。 */
    @Test fun stepPeakAndTagSummary() {
        val s = freshStore()
        assertNull(s.stepPeak(0, 1))
        s.noteStepPeak(0, 1, "s_old", 7, 187)
        assertEquals(Triple("s_old", 7, 187), s.stepPeak(0, 1))
        assertNull(s.stepPeak(0, 2))          // 步位不同互不干扰
        assertNull(s.stepPeak(1, 1))          // 轮次不同互不干扰
        s.noteStepPeak(0, 1, "s_new", 0, 0)   // 覆盖同一格
        assertEquals(Triple("s_new", 0, 0), s.stepPeak(0, 1))

        s.noteSettledUncounted("idA", "adventure", todayAt(60), 45 * 60L, stepId = "s1")   // 跑满 45 分钟
        s.noteSettledUncounted("idB", "adventure", todayAt(120), 45 * 60L, stepId = "s1")
        s.noteSettledUncounted("idC", "gold", todayAt(180), 12 * 60L, stepId = "s2")
        val sum = s.stepTagSummary()
        assertTrue(sum, sum.contains("s1=2次/90分钟"))
        assertTrue(sum, sum.contains("s2=1次/12分钟"))
    }

    @Test fun rejectedSessionExcludedFromProgressButCountsFatigue() {
        val s = freshStore()
        s.countTaskOnce("6100_r", "study", 30, seconds = 1800, stepId = "a")
        assertEquals(1 to 30, s.stepProgress("a"))
        s.markLedgerRejected("6100_r")
        assertEquals(0 to 0, s.stepProgress("a"))
        assertEquals(0, s.counts()["study"])
        assertEquals(0, s.doneTally(Long.MAX_VALUE).study)
        assertEquals(30, s.fatigueMinutes())
    }

    /** 实收记在台账行上：先到（行未落账走缓冲）与后到（直接写行）都只算一次；场外存量另算、不双计。 */
    @Test fun rewardsLiveOnLedgerRows() {
        val s = freshStore()
        s.attachRewards("6400_a", listOf(Reward("金币", 324), Reward("工分", 54)))   // 行还没落账 → 缓冲
        s.noteSettledUncounted("6400_a", "work", todayAt(60), 2700)                  // 落行时并入缓冲
        s.noteSettledUncounted("6400_b", "work", todayAt(180), 2700)
        s.attachRewards("6400_b", listOf(Reward("金币", 100)))                       // 行已在 → 直接写行
        s.addRewards(listOf(Reward("金币", 7)))                                      // 场外来源（照顾等）
        val r = s.rewards()
        assertEquals(324 + 100 + 7, r["金币"])
        assertEquals(54, r["工分"])
        // 再读一次不翻倍（现算是幂等的）
        assertEquals(324 + 100 + 7, s.rewards()["金币"])
    }

    /** 疲劳与今日计数都从同一批行出现算：混排一天后几本账必须互相一致。 */
    @Test fun allViewsDeriveFromSameRows() {
        val s = freshStore()
        s.noteSettledUncounted("6100_1", "study", todayAt(0), 1800, stepId = "a")
        s.noteSettledUncounted("6100_2", "study", todayAt(60), 1800, stepId = "a")
        s.noteSettledUncounted("6400_1", "work", todayAt(120), 2700, stepId = "b")
        s.noteSettledUncounted("6700_1", "adventure", todayAt(240), 45, stepId = "c")
        assertEquals(2 to 60, s.stepProgress("a"))
        assertEquals(1 to 45, s.stepProgress("b"))
        assertEquals(1 to 1, s.stepProgress("c"))   // 冒险 45 秒按实际用时向上取整 1 分钟
        assertEquals(105, s.fatigueMinutes())       // 30+30+45，冒险不进疲劳
        assertEquals(1, s.adventureMinutes())       // 45 秒按开工分钟口径向上取整 1 分钟（秒数在 tally 里另有精确值）
        val t = s.doneTally(Long.MAX_VALUE)
        assertEquals(2, t.study); assertEquals(1, t.work); assertEquals(1, t.adventure)
        assertEquals((1800 + 1800 + 2700), t.studyWorkSec)
        assertEquals(45, t.adventureSec)
        val c = s.counts()
        assertEquals(2, c["study"]); assertEquals(1, c["work"]); assertEquals(1, c["adventure"])
    }

    /** 界面读的每步进度（sch_steps）：统一账本后必须由台账现算回填，否则排班页卡片永远是 0（2.1.13 实报）。 */
    @Test fun schStepsDerivedFromJournalForUi() {
        val s = freshStore()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        // 冒险步结算两趟：45 秒整趟 + 8 秒被召回，都按实际秒数向上取整为 1 分钟
        s.noteSettledUncounted("6700_a", "adventure", todayAt(60), 45, stepId = "s1")
        s.noteSettledUncounted("6700_b", "adventure", todayAt(120), 8, stepId = "s1")
        assertEquals("0:s1:2:2", prefs.getString("sch_steps", null))
        // 与执行侧现算口径一致
        assertEquals(2 to 2, s.stepProgress("s1"))
    }
}
