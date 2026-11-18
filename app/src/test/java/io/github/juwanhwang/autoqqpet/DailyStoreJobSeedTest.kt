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
 * 岗位学习新账（w2，满档还原口径）的种子迁移：升级前旧账（w 键）直接写进 prefs 模拟，
 * 首次读/写学习表时把 ≥2 样本岗位的均值折成 1 个样本种进 w2 当底价，
 * 免得新账空窗期里金币优先把所有岗位按 0 估、池子刷干只闲着。
 * 种子值是整数除法后的均值（底价口径，差不到 1 个金币），断言按整数期望写。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreJobSeedTest {

    private fun storeWithRewardAvg(raw: String): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("reward_avg", raw).commit()
        return DailyStore(ctx)
    }

    @Test fun seedsLegacyMeansAsSingleSamples() {
        val s = storeWithRewardAvg("w6471202:14:3098:556;w6471201:5:254:35;a附近走走:3:30:0")
        // 触发迁移后按单样本口径读：旧均值（整数除法）折成 1 个样本
        assertEquals((3098 / 14).toDouble(), s.learnedGold("w26471202", 1)!!, 1e-9)
        assertEquals((556 / 14).toDouble(), s.learnedPoints("w26471202", 1)!!, 1e-9)
        assertEquals((254 / 5).toDouble(), s.learnedGold("w26471201", 1)!!, 1e-9)
        // 默认 2 样本口径下种子不算数（种子只服务岗位定价的单样本读法）
        assertNull(s.learnedGold("w26471202"))
        // 冒险账不被迁移碰
        assertEquals(10.0, s.learnedGold("a附近走走")!!, 1e-9)
    }

    @Test fun skipsThinLegacyAndKeepsExistingW2() {
        val s = storeWithRewardAvg("w6471002:1:50:5;w6471202:3:900:90;w26471202:1:999:99;w6471201:3:900:90")
        // 旧账只有 1 个样本的岗位不种
        assertNull(s.learnedGold("w26471002", 1))
        // 已有 w2 记录不被种子覆盖（w6471202 的种子键正是 w26471202）
        assertEquals(999.0, s.learnedGold("w26471202", 1)!!, 1e-9)
        // 正常种子照常落地
        assertEquals(300.0, s.learnedGold("w26471201", 1)!!, 1e-9)
    }
}
