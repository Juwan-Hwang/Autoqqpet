package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 冒险收益学习口径（2026-10-17）：学习均值只许统计「池子还活着时」的样本。
 * 此前跨天累计总平均把到顶后的 0 金币趟全混进去，实测收敛到真实期望的一半左右，
 * 样本越多错得越稳。两道防线：判到顶时回滚连段 0 样本；跨天时把冒险学习项清掉、
 * 只留昨天活池段的每趟实收作种子。打工学习不受影响（跨天保留）。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreAdvLearnTest {

    private fun freshStore(): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return DailyStore(ctx)
    }

    @Test fun rollbackRemovesStreakZerosFromAverage() {
        val s = freshStore()
        // 活池样本 7 趟共 126（均值 18），其后在到顶判定窗口里又喂了 30 趟 0（冒险按时长记账，档位 30 分钟）
        repeat(7) { s.learnReward("a30", 18, 0) }
        repeat(30) { s.learnReward("a30", 0, 0) }
        s.rollbackAdventureZeros(30, 30)
        assertEquals(18.0, s.learnedGold("a30")!!, 1e-9)
    }

    @Test fun dayRollSeedsLiveEvAndResetsAdventureLearning() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val s = DailyStore(ctx)
        s.counts()   // 先定住 day：首次 rollDay（day=-1）会清台账，必须赶在种行之前触发
        // 昨天：冒险 50 趟、当日金币 500、已判到顶（尾部连 30 趟无金币）→ 活池均值 500/(50-30)=25
        // 统一账本（2.1.13）后趟数从台账行数：种 50 行互不重叠的冒险行（旧版靠 setCount 的存量计数已退役）
        val lines = (0 until 50).joinToString("\n") { i ->
            val sec = 60 + i * 180
            "%02d:%02d:%02d|adventure|45s|6700_t$i".format(sec / 3600, sec / 60 % 60, sec % 60)
        }
        prefs.edit().putString("ledger", lines)
            .putString("ledger_ids", (0 until 50).joinToString(",") { "6700_t$it" }).commit()
        s.setCount("adv_gold", 500)
        s.setCount("adv_nogold_streak", 30)
        s.noteAdvGoldCapped()
        s.learnReward("a30", 13, 0)
        s.learnReward("a30", 13, 0)
        repeat(2) { s.learnReward("w6400", 300, 54) }
        // 把存档日期拨回昨天，下一次读触发跨天结算
        prefs.edit().putInt("day", Day.today() - 1).commit()
        assertFalse(s.advGoldCapped())  // 触发跨天，并顺带验到顶标记已清
        assertEquals(25.0, s.advLiveEv()!!, 1e-9)
        assertNull(s.learnedGold("a30"))               // 冒险学习按天重开
        assertEquals(300.0, s.learnedGold("w6400")!!, 1e-9)  // 打工学习跨天保留
    }
}
