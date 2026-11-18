package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.optimizer.adventureNameScope
import io.github.juwanhwang.autoqqpet.pet.AdventureInfo
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 2026-10-16 真机审计后的修复回归：
 * ① 恢复合并（刚记的数不能被旧备份盖掉）；② 结算补记未登记任务（疲劳/台账）；
 * ③ 奖励杂键清洗；④ 只冒险时长偏好过滤。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreMergeTest {

    @Test fun mergePairsTakesMaxPerKey() {
        val merged = DailyStore.mergePairsMax("coinbag:3;coinbag_gold:55", "coinbag:4;coinbag_gold:90;spark_poke:17")
        val m = merged.split(";").associate { it.substringBefore(':') to it.substringAfter(':').toInt() }
        assertEquals(mapOf("coinbag" to 4, "coinbag_gold" to 90, "spark_poke" to 17), m)
    }

    @Test fun mergeLedgerUnionsByStoryId() {
        val local = "08:00:00|work|45m|6400_a\n08:45:00|work|45m|6400_b"
        val backup = "08:00:00|work|45m|6400_a\n07:15:00|study|45m|6100_c"
        val ids = DailyStore.mergeLedger(local, backup).split("\n").map { it.split('|')[3] }
        assertEquals(listOf("6400_a", "6400_b", "6100_c"), ids)
    }

    @Test fun mergeCurveUnionsAndSorts() {
        val merged = DailyStore.mergeCurve("100:500;300:700", "200:600;300:700")
        assertEquals("100:500;200:600;300:700", merged)
    }

    @Test fun settledUncountedWritesLedgerAndFatigue() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit().clear().apply()
        val st = DailyStore(ctx)
        st.counts()   // 定住 day
        val startMs = java.time.LocalDate.now().atTime(3, 11).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(st.noteSettledUncounted("6100_unreg", "study", startMs, 2700))
        assertEquals(45, st.fatigueMinutes())
        assertTrue(st.inLedger("6100_unreg"))
        // 再补一次：已在台账 → 不重复记、不重复加疲劳
        assertFalse(st.noteSettledUncounted("6100_unreg", "study", startMs, 2700))
        assertEquals(45, st.fatigueMinutes())
        // 冒险走冒险分钟，不进疲劳
        assertTrue(st.noteSettledUncounted("6700_unreg", "adventure", startMs, 45))
        assertEquals(45, st.fatigueMinutes())
        assertEquals(1, st.adventureMinutes())
    }

    @Test fun rewardNameGateDropsJunk() {
        assertTrue(Parsers.rewardNameOk("金币"))
        assertTrue(Parsers.rewardNameOk("基础学分"))
        assertTrue(Parsers.rewardNameOk("工分"))
        assertFalse(Parsers.rewardNameOk("工分 +"))
        assertFalse(Parsers.rewardNameOk("疲惫，收益减少"))
        assertFalse(Parsers.rewardNameOk(""))
        assertFalse(Parsers.rewardNameOk("这是一个很长的叙事文本"))
    }

    @Test fun adventureNameScopeFilters() {
        fun adv(name: String, sec: Long) = AdventureInfo(name, 0, 0, false, Attr.NONE, 0, 0, 0, 0, (sec / 60).toInt(), sec, true)
        val items = listOf(adv("附近走走", 45), adv("诗和远方", 7200))
        assertEquals(listOf("附近走走"), adventureNameScope(items, "附近走走").map { it.name })
        assertEquals(listOf("诗和远方"), adventureNameScope(items, "诗和远方").map { it.name })
        assertEquals(2, adventureNameScope(items, "").size)
        // 选的名字不在列表里 → 回退全部，不停工
        assertEquals(2, adventureNameScope(items, "不存在的冒险").size)
    }
}
