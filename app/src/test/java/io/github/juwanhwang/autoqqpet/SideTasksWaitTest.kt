package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.SideTasks
import org.junit.Assert.assertEquals
import org.junit.Test

/** 福袋扫描点计算（主循环据此把唤醒提前到扫描点）：没扫过=马上，已过点=0，未到点=剩余。 */
class SideTasksWaitTest {

    @Test fun neverScannedIsDueNow() {
        assertEquals(0L, SideTasks.waitUntil(0L, 300_000L, 1_000_000L))
    }

    @Test fun waitsRemainderOfInterval() {
        assertEquals(299_000L, SideTasks.waitUntil(1_000_000L, 300_000L, 1_001_000L))
    }

    @Test fun overdueIsZeroNotNegative() {
        assertEquals(0L, SideTasks.waitUntil(1_000_000L, 300_000L, 1_500_000L))
    }
}
