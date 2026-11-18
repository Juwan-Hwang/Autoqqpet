package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.EncouragePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动鼓励次数与补发节奏（issue #5，v2.1.2）：默认 1 次与旧行为一致，其余次数按间隔补发。 */
class EncouragePlanTest {

    @Test fun defaultOneMeansFirstOnly() {
        assertEquals(1, EncouragePlan.left(1, 0))
        assertTrue(EncouragePlan.due(1, 0, 0L, 1_000L))                       // 还没发过：立刻可发
        assertEquals(0, EncouragePlan.left(1, 1))
        assertFalse(EncouragePlan.due(1, 1, 1_000L, 999_000L))                 // 发满了：不再发
        assertEquals(Long.MAX_VALUE, EncouragePlan.nextAt(1, 1, 1_000L))
    }

    @Test fun zeroDisables() {
        assertEquals(0, EncouragePlan.left(0, 0))
        assertFalse(EncouragePlan.due(0, 0, 0L, 1_000L))
        assertEquals(Long.MAX_VALUE, EncouragePlan.nextAt(0, 0, 0L))
    }

    @Test fun extraTimesWaitForGap() {
        val t0 = 100_000L
        assertFalse(EncouragePlan.due(5, 1, t0, t0 + EncouragePlan.GAP_MS - 1))  // 间隔没到
        assertTrue(EncouragePlan.due(5, 1, t0, t0 + EncouragePlan.GAP_MS))       // 间隔刚到
        assertEquals(t0 + EncouragePlan.GAP_MS, EncouragePlan.nextAt(5, 1, t0))
        assertEquals(3, EncouragePlan.left(5, 2))
        assertFalse(EncouragePlan.due(5, 5, t0, t0 + 99 * EncouragePlan.GAP_MS)) // 5 次发满
    }
}
