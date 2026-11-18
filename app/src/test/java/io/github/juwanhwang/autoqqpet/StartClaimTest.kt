package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.StartClaim
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开工认领判定（2026-10-19 真机缺陷：自己开的工登记丢了被误判成外人开的）。 */
class StartClaimTest {

    private val t0 = 1_700_000_000_000L

    @Test fun matchingKindWithinWindowClaims() {
        assertTrue(StartClaim.matches("work", "商场临时商演", t0, "work", "", t0 + 22_000L))      // 实录间隔约 22 秒
        assertTrue(StartClaim.matches("study", "", t0, "study", "", t0))                             // 名字未知只按种类
        assertTrue(StartClaim.matches("adventure", "附近走走", t0, "adventure", "附近走走", t0 + 60_000L))
        assertTrue(StartClaim.matches("work", "", t0, "work", "", t0 + StartClaim.WINDOW_MS))        // 窗口边界含
    }

    @Test fun kindMismatchNeverClaims() {
        assertFalse(StartClaim.matches("work", "", t0, "study", "", t0 + 1_000L))
        assertFalse(StartClaim.matches("study", "", t0, "adventure", "", t0 + 1_000L))
        assertFalse(StartClaim.matches("work", "", t0, "", "", t0 + 1_000L))                          // 种类认不出绝不领
        assertFalse(StartClaim.matches("work", "", t0, "pk", "", t0 + 1_000L))
    }

    @Test fun expiredClaimDoesNotClaim() {
        assertFalse(StartClaim.matches("work", "", t0, "work", "", t0 + StartClaim.WINDOW_MS + 1))
        assertFalse(StartClaim.matches("work", "", t0, "work", "", t0 + 60 * 60_000L))
        assertTrue(StartClaim.expired(t0, t0 + StartClaim.WINDOW_MS + 1))
        assertFalse(StartClaim.expired(t0, t0 + StartClaim.WINDOW_MS))
        assertFalse(StartClaim.matches("work", "", t0, "work", "", t0 - 1_000L))                      // 时钟倒退不认
    }

    @Test fun noClaimMeansExternal() {
        assertFalse(StartClaim.matches("", "", 0L, "work", "", t0))
        assertFalse(StartClaim.matches("work", "", 0L, "work", "", t0))                               // 没时刻 = 没认领
        assertFalse(StartClaim.expired(0L, t0))
    }

    @Test fun namesMustAgreeWhenBothKnown() {
        assertFalse(StartClaim.matches("work", "商场临时商演", t0, "work", "粉丝会预录整备", t0 + 1_000L))
        assertTrue(StartClaim.matches("work", "商场临时商演", t0, "work", "商场临时商演", t0 + 1_000L))
        assertTrue(StartClaim.matches("work", "", t0, "work", "粉丝会预录整备", t0 + 1_000L))          // 认领没名字不拦
        assertTrue(StartClaim.matches("work", "商场临时商演", t0, "work", "", t0 + 1_000L))            // 观测没名字不拦
    }
}
