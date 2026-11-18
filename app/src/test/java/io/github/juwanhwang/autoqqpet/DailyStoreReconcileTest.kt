package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 对账补记去重（2026-10-16 幻影记录实锤后补）：
 * 真机台账里出现过 `07:44:59|study|45m|6100_…|补记` —— 一条学习借了同刻打工的开始时间与时长，
 * 旧去重只认同种类，于是“今天”同时多记一学一工。宠物同一时刻只能干一件事，时间段重叠即同一趟。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreReconcileTest {

    private fun at(hour: Int, min: Int): Long =
        java.time.LocalDate.now().atTime(hour, min).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 种下一条已存在的台账（今早 08:00 的一趟 45 分钟打工），返回 store。 */
    private fun storeWithLedger(): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val st = DailyStore(ctx)
        st.counts()   // 先让 rollDay 把 day 定在今天，避免种数据后被跨天清零误伤
        val line = "08:00:00|work|45m|6400_seed0000000000000000000000000001"
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit()
            .putString("ledger", line)
            .putString("ledger_ids", "6400_seed0000000000000000000000000001")
            .apply()
        return st
    }

    @Test fun crossKindOverlapIsSameOuting() {
        val st = storeWithLedger()
        // 学习记录借了打工的时间段（开始只差 30 秒、时长相同）→ 同一趟，不补
        assertFalse(st.appendReconciled("6100_phantom", "study", at(8, 0) + 30_000, 2700))
    }

    @Test fun fullyInsideOverlapAlsoBlocked() {
        val st = storeWithLedger()
        assertFalse(st.appendReconciled("6100_phantom2", "study", at(8, 10), 600))
    }

    @Test fun nonOverlappingExternalStillBackfilled() {
        val st = storeWithLedger()
        // 中午一趟真·外部学习，与早上的打工不重叠 → 照常补
        assertTrue(st.appendReconciled("6100_real", "study", at(12, 0), 2700))
    }

    @Test fun backToBackIsNotOverlap() {
        val st = storeWithLedger()
        // 08:45 紧接着开始的下一趟（首尾相接）是真记录 → 要补
        assertTrue(st.appendReconciled("6400_next", "work", at(8, 45), 2700))
    }
}
