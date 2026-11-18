package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.CatalogReader
import io.github.juwanhwang.autoqqpet.optimizer.startRetryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开工被拒退避（2026-10-23 真机：135052 连拒约 76 分钟、固定 30 秒空转、每次还全量刷目录）：
 * 同码连拒要逐步拉长间隔，且同码短时间内不许反复触发目录全量刷新。
 */
class StartRejectBackoffTest {

    @Test fun delayGrowsWithStreakAndCaps() {
        assertEquals(30_000L, startRetryDelayMs(1))
        assertEquals(60_000L, startRetryDelayMs(2))
        assertEquals(120_000L, startRetryDelayMs(3))
        assertEquals(300_000L, startRetryDelayMs(4))
        assertEquals(600_000L, startRetryDelayMs(5))
        assertEquals(600_000L, startRetryDelayMs(42))
        assertEquals(30_000L, startRetryDelayMs(0))
    }

    @Test fun sameRejectCodeInvalidatesCatalogOncePerWindow() {
        val t0 = 1_700_000_000_000L
        assertTrue(CatalogReader.invalidateForReject(135052, t0))
        assertFalse(CatalogReader.invalidateForReject(135052, t0 + 60_000L))
        assertFalse(CatalogReader.invalidateForReject(135052, t0 + 9 * 60_000L))
        assertTrue(CatalogReader.invalidateForReject(135052, t0 + 11 * 60_000L))
        // 换一个码立即失效，不等窗口
        assertTrue(CatalogReader.invalidateForReject(135011, t0 + 11 * 60_000L + 1_000L))
    }
}
