package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.optimizer.RecallGuard
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 归属判断回归（2026-10-31 图图报告）：被雇工第一眼没匹配上通知时，busy() 会先给它登记
 * 一份在跑记录（pending）；若归属判断把 pending 也算「本模块开的」，这趟工就被永久吞成
 * 自家工、召回守卫再也不确认，71% 自动召回整趟失效。归属只认开工台账。
 * 直测 RecallGuard.isMine 的判定本体（整套服务在 QQ 进程外构造不出来）。
 */
@RunWith(RobolectricTestRunner::class)
class RecallOwnershipTest {

    private fun daily(): DailyStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit().clear().commit()
        return DailyStore(ctx)
    }

    @Test fun pendingRegistrationDoesNotMakeExternalStoryMine() {
        val daily = daily()
        // 图图那趟被雇工的 id（6400 前缀）
        val externalId = "6400_aee6f923"
        assertFalse(RecallGuard.isMine(externalId, daily))
        // busy() 给外部任务登记在跑记录后，归属不能变
        daily.setPending(externalId, "work", System.currentTimeMillis() + 2690_000, 45,
            name = "偶遇 雾野", durationSec = 2700)
        assertFalse(RecallGuard.isMine(externalId, daily))
    }

    @Test fun startedLedgerStoryIsMine() {
        val daily = daily()
        daily.noteStarted("6400_self00001")
        assertTrue(RecallGuard.isMine("6400_self00001", daily))
        // 台账里的工登记在跑记录后仍是自家的（本模块开工本来就两个都记）
        daily.setPending("6400_self00001", "work", System.currentTimeMillis() + 600_000, 10, durationSec = 600)
        assertTrue(RecallGuard.isMine("6400_self00001", daily))
    }
}
