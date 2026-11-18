package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.pet.HirePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 雇佣指定名单的过滤与挑选（纯函数）：名单为空不限制、非空只出名单内人、名单内全不可雇则空。
 */
class HireDesignatedListTest {

    private val pool = listOf("111", "222", "333", "444")

    @Test fun emptyDesignatedMeansNoRestriction() {
        assertEquals(pool, HirePolicy.designatedOnly(pool, emptySet()))
    }

    @Test fun designatedKeepsOnlyListedInPoolOrder() {
        // 名单顺序不影响结果顺序：保持好友池原有顺序
        assertEquals(listOf("222", "444"), HirePolicy.designatedOnly(pool, setOf("444", "222")))
    }

    @Test fun designatedNoneInPoolGivesEmpty() {
        assertEquals(emptyList<String>(), HirePolicy.designatedOnly(pool, setOf("999")))
    }

    @Test fun firstHireablePicksFirstAvailable() {
        val picked = HirePolicy.firstHireableUin(pool, probe = 5) { it == "333" }
        assertEquals("333", picked)
    }

    @Test fun designatedAllUnavailableGivesNull() {
        // 名单内的人都不可雇：挑不到人（本轮不雇）
        val candidates = HirePolicy.designatedOnly(pool, setOf("222", "333"))
        val picked = HirePolicy.firstHireableUin(candidates, probe = 5) { false }
        assertNull(picked)
    }

    @Test fun probeLimitIsRespected() {
        // 可雇的人在 probe 名额之外：同样挑不到
        val picked = HirePolicy.firstHireableUin(pool, probe = 2) { it == "444" }
        assertNull(picked)
    }

    /** 复用已建立雇佣前的单人名单复检：与挑人同一套口径（2026-11-10 群友实报：中途拉黑后仍被复用雇佣）。 */
    @Test fun designatedAllowsFollowsListMode() {
        val bl = io.github.juwanhwang.autoqqpet.pet.FriendPolicy.MODE_BL
        val wl = io.github.juwanhwang.autoqqpet.pet.FriendPolicy.MODE_WL
        // 名单为空：两种模式都不限
        assertEquals(true, HirePolicy.designatedAllows("111", emptySet(), bl))
        assertEquals(true, HirePolicy.designatedAllows("111", emptySet(), wl))
        // 黑名单模式：名单内排除、名单外放行
        assertEquals(false, HirePolicy.designatedAllows("111", setOf("111", "222"), bl))
        assertEquals(true, HirePolicy.designatedAllows("333", setOf("111", "222"), bl))
        // 白名单模式：只认名单内
        assertEquals(true, HirePolicy.designatedAllows("111", setOf("111", "222"), wl))
        assertEquals(false, HirePolicy.designatedAllows("333", setOf("111", "222"), wl))
    }
}
