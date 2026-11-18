package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.TaskGapWake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 任务间隔精确唤醒（2026-10-19 真机：间隔设 5 秒，实际「上一件结算 → 下一件出发」总在
 * 10–17 秒，多出的时间耗在等下一轮 tick 接力）。结案时把唤醒钉在「结案时刻 + 设定间隔」，
 * 唤醒时刻的计算口径在这里钉死：就是结案时刻平移间隔秒数，不多不少。
 */
class TaskGapWakeTest {

    @Test fun wakeAtLastEndPlusGap() {
        val end = 1_760_000_000_000L
        assertEquals(end + 5_000L, TaskGapWake.atMillis(end, 5))
        assertEquals(end + 60_000L, TaskGapWake.atMillis(end, 60))
    }

    @Test fun noGapConfiguredMeansNoWake() {
        assertNull(TaskGapWake.atMillis(1_760_000_000_000L, 0))
        assertNull(TaskGapWake.atMillis(1_760_000_000_000L, -3))
    }

    @Test fun noCloseOutYetMeansNoWake() {
        assertNull(TaskGapWake.atMillis(0L, 5))
        assertNull(TaskGapWake.atMillis(-1L, 5))
    }

    /** 结案处理本身就耗时超过间隔时，算出的时刻已过也要原样返回，交给调度侧按已到期马上唤醒。 */
    @Test fun pastTargetIsReturnedAsIs() {
        assertEquals(6_000L, TaskGapWake.atMillis(1_000L, 5))
    }
}
