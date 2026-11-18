package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.HireDetect
import io.github.juwanhwang.autoqqpet.pet.HiredNotice
import io.github.juwanhwang.autoqqpet.pet.StoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 2026-10-23 真机误判的回归：雇佣通知只按时间窗认人时，上一份工的通知会落进下一份工的窗口。
 * 当天实况（本机时区）：15:14 好友雇用户打 4 小时工（通知 15:14），用户在官方界面手动召回，
 * 15:17 自己开了一份 45 分钟工——旧判定把手动工确认成被雇佣。
 * 修复口径：一条通知一生只认一份工（绑定持久化在 DailyStore，重启不复活）。
 */
@RunWith(RobolectricTestRunner::class)
class HireNoticeBindingTest {

    private fun ms(hh: Int, mm: Int, ss: Int = 0): Long =
        LocalDateTime.of(2026, 10, 23, hh, mm, ss).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun work(storyId: String, startedAtMs: Long, durationSec: Long, remainingSec: Long) = StoryStatus(
        storyId = storyId, stateCode = StoryStatus.WORKING_STATE,
        remainingSeconds = remainingSec, durationSeconds = durationSec,
        startedAt = startedAtMs / 1000, recallable = false,
    )

    /** 15:14 那条通知（属于 4 小时工）与当天的两份工。 */
    private val notice = HiredNotice("些", ms(15, 14, 30), 6400)
    private val hiredJob = work("6400_old", ms(15, 14, 29), 4 * 3600, 14369)
    private val manualJob = work("6400_new", ms(15, 17, 17), 2700, 2531)

    @Test fun genuineHireSameMinuteStillConfirms() {
        // 同分钟的真实雇佣：通知与任务几乎同时出现，无绑定时照常确认
        val found = HireDetect.match(hiredJob, mine = false, listOf(notice), emptyMap(), ms(15, 15, 0))
        assertNotNull(found)
        assertEquals(notice, found)
        // 绑给它自己之后，后续每一轮仍能继续确认（等待 71% 的轮次靠这个）
        val binds = mapOf(HireDetect.noticeKey(notice) to hiredJob.storyId)
        assertNotNull(HireDetect.match(hiredJob, mine = false, listOf(notice), binds, ms(16, 0, 0)))
    }

    @Test fun noticeOfPreviousJobMustNotConfirmNextJob() {
        // 复现当天链条：旧工在跑时先把通知绑到旧工（确认/切换作废殊途同归），新工不得再被确认
        val binds = HashMap<String, String>()
        HireDetect.match(hiredJob, mine = false, listOf(notice), binds, ms(15, 15, 0))
            ?.let { binds[HireDetect.noticeKey(it)] = hiredJob.storyId }
        assertNull(HireDetect.match(manualJob, mine = false, listOf(notice), binds, ms(15, 20, 8)))
        // 就算不先确认：旧工被换掉时 spendable 也应把这条通知挑出来作废
        val spend = HireDetect.spendable(hiredJob, listOf(notice), emptyMap(), ms(15, 20, 8))
        assertEquals(listOf(notice), spend)
        val binds2 = mapOf(HireDetect.noticeKey(notice) to hiredJob.storyId)
        assertNull(HireDetect.match(manualJob, mine = false, listOf(notice), binds2, ms(15, 20, 8)))
        // 对照组：没有绑定时旧逻辑确实会误确认（证明这组时间戳能复现故障）
        assertNotNull(HireDetect.match(manualJob, mine = false, listOf(notice), emptyMap(), ms(15, 20, 8)))
    }

    @Test fun bindingPersistsAcrossStoreInstances() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit().clear().apply()
        val st = DailyStore(ctx)
        st.counts()   // 定住 day
        val key = HireDetect.noticeKey(notice)
        st.bindHireNotice(key, hiredJob.storyId)
        // 换一个实例（模拟进程重启后重新读盘）：绑定还在，同一通知不得二次确认别份工
        val reloaded = DailyStore(ctx).hireNoticeBinds()
        assertEquals(hiredJob.storyId, reloaded[key])
        assertNull(HireDetect.match(manualJob, mine = false, listOf(notice), reloaded, ms(15, 20, 8)))
        assertNotNull(HireDetect.match(hiredJob, mine = false, listOf(notice), reloaded, ms(15, 15, 0)))
        // 已绑定的通知不会被改绑给别人
        st.bindHireNotice(key, manualJob.storyId)
        assertEquals(hiredJob.storyId, DailyStore(ctx).hireNoticeBinds()[key])
        assertTrue(DailyStore(ctx).hireNoticeBinds().isNotEmpty())
    }
}
