package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 冒险定价期望的归零防线（2026-10-31 真机实报）：附近走走两趟坏天气 0 金币把学习均值
 * 拉到恰好 0，求解器按 ev>0 过滤直接把它踢出计划，没跑过的长冒险乘虚而入。
 * 「没油水」只认连续 30 趟无金币的到顶判定；均值 ≤0 按未判处理、回退活池种子。
 * 期望值按 DailyStore.learnedGold（默认 ≥2 样本）与 advLiveEv 的语义推导。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreAdventureEvTest {

    private fun storeWith(rawRewardAvg: String?, liveEv: String?): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        val e = prefs.edit().clear()
        if (rawRewardAvg != null) e.putString("reward_avg", rawRewardAvg)
        if (liveEv != null) e.putString("adv_ev_live", liveEv)
        e.commit()
        return DailyStore(ctx)
    }

    @Test fun zeroLearnedAverageFallsBackToLiveSeed() {
        val s = storeWith("a30:2:0:0", "10.75")
        assertEquals(10.75, s.adventureEvGold(30)!!, 1e-9)
    }

    @Test fun positiveLearnedAverageWinsOverSeed() {
        val s = storeWith("a30:3:60:0", "10.75")
        assertEquals(20.0, s.adventureEvGold(30)!!, 1e-9)
    }

    @Test fun zeroAverageWithoutSeedReturnsNullNotZero() {
        val s = storeWith("a30:2:0:0", null)
        assertNull(s.adventureEvGold(30))
    }

    @Test fun noDataReturnsNull() {
        val s = storeWith(null, null)
        assertNull(s.adventureEvGold(30))
    }
}
