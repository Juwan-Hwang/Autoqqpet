package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.Reward
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 实收向前兼容自愈（2026-10-19 真机：旧包把故事结算的脏名「工分 +」整条丢弃，
 * 今天 3 份工、162 工分没进 rewards 记录）：当天日志的「🧾 结算记录」行里完整 rewards 还在，
 * rewards() 按天对账一次、逐名取 max 把丢掉的数补回来。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreRewardsHealTest {

    private fun freshStore(): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit().clear().apply()
        java.io.File(ctx.filesDir, "autoqqpet-logs").deleteRecursively()   // 当天日志文件也清掉，免得用例间串扰
        val st = DailyStore(ctx)
        st.counts()   // 定住 day，避免跨天清零混进测试
        return st
    }

    private fun settleLine(id: String, rewards: String) =
        "🧾 结算记录 id=$id kind=work name=\"日常拍摄通告\" gold=0 rewards=$rewards"

    @Test fun droppedPointsAreHealedFromLog() {
        val st = freshStore()
        // 已存 rewards 里没有工分（被旧包整条丢了），日志里有三笔「工分 +:54」
        repeat(3) { st.pushEvent(settleLine("6400_$it", "工分 +:54")) }
        assertEquals(162, st.rewards()["工分"])
        // 幂等：再读一次结果不变（不会把日志汇总重复叠加）
        assertEquals(162, st.rewards()["工分"])
    }

    @Test fun storedHigherValueNeverRegresses() {
        val st = freshStore()
        // 已存金币（含 PK 等不走结算记录的来源）高于日志汇总时取 max、不回退
        st.addRewards(listOf(Reward("金币", 500)))
        st.pushEvent(settleLine("6400_1", "金币:100"))
        assertEquals(500, st.rewards()["金币"])
    }

    @Test fun statusRewardsFromLogStillRecorded() {
        val st = freshStore()
        // 状态值照旧进记录（不进展示是另一层的事，这里不许滤）
        st.pushEvent(settleLine("6700_1", "心情值:5,体力值:-3"))
        assertEquals(5, st.rewards()["心情值"])
        assertEquals(-3, st.rewards()["体力值"])
    }

    @Test fun junkNamesInLogAreSkipped() {
        val st = freshStore()
        st.pushEvent(settleLine("6400_1", "疲惫，收益减少:6,工分:10"))
        assertEquals(10, st.rewards()["工分"])
        assertEquals(null, st.rewards()["疲惫，收益减少"])
    }

    @Test fun storedVariantKeysAreFolded() {
        val st = freshStore()
        // 旧包能把「基础魅力」单独存下（过得了旧校验）：读一次就折回本体合并，变体键消失
        st.addRewards(listOf(Reward("基础魅力", 3), Reward("魅力", 4)))
        assertEquals(7, st.rewards()["魅力"])
        assertEquals(null, st.rewards()["基础魅力"])
    }

    @Test fun foldedStoredAndLogDoNotDoubleCount() {
        val st = freshStore()
        // 同一笔结算：已存是变体名、日志是完整记录——折回后取 max，不能算成两遍
        st.addRewards(listOf(Reward("基础魅力", 3)))
        st.pushEvent(settleLine("6100_1", "基础魅力:3"))
        assertEquals(3, st.rewards()["魅力"])
    }

    @Test fun noLogLinesMeansUnchanged() {
        val st = freshStore()
        st.addRewards(listOf(Reward("工分", 7)))
        assertEquals(7, st.rewards()["工分"])
    }
}
