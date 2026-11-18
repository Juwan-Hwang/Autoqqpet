package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.pet.HirePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日雇佣次数的新口径（2026-10-20）：上限数的是雇佣开工的班次——新建立与复用每次
 * 开工都算一次，上限对复用同样生效（达到上限后本轮不雇佣）；默认值 -1 表示不限。
 * 此前数的是新建立关系的个数，复用完全绕过上限，名单里后面的人轮不到被雇。
 */
class HireCapPerShiftTest {

    @Test fun reuseWithinCapPassesAsReuse() {
        val v = HirePolicy.judge(true, usedToday = 2, dailyCap = 3, reusable = true)
        assertTrue(v.hire); assertTrue(v.reuse)
    }

    @Test fun reuseAtCapRejectedWithCountReason() {
        val v = HirePolicy.judge(true, usedToday = 3, dailyCap = 3, reusable = true)
        assertFalse(v.hire)
        assertEquals("今日雇佣已 3/3 次", v.reason)
    }

    @Test fun newHireAtCapRejected() {
        val v = HirePolicy.judge(true, usedToday = 3, dailyCap = 3)
        assertFalse(v.hire)
        assertEquals("今日雇佣已 3/3 次", v.reason)
    }

    @Test fun unlimitedCapNeverBlocksReuseOrNewHire() {
        val reuse = HirePolicy.judge(true, usedToday = 100, dailyCap = -1, reusable = true)
        assertTrue(reuse.hire); assertTrue(reuse.reuse)
        assertTrue(HirePolicy.judge(true, usedToday = 100, dailyCap = -1).hire)
    }

    @Test fun newHireWithinCapReasonCountsThisShift() {
        val v = HirePolicy.judge(true, usedToday = 1, dailyCap = 3)
        assertTrue(v.hire); assertFalse(v.reuse)
        assertTrue(v.reason.startsWith("今日第 2/"))
    }

    @Test fun atCapReasonIsCount() {
        val v = HirePolicy.judge(true, usedToday = 3, dailyCap = 3)
        assertFalse(v.hire)
        assertEquals("今日雇佣已 3/3 次", v.reason)
    }

    @Test fun rankByBonusOrdersDescUnknownLastStableTies() {
        val caps = mapOf("a" to 61, "b" to 121, "c" to 97)
        // b 上限最高在前；d 没查到上限垫底；a/c 按上限降序
        assertEquals(listOf("b", "c", "a", "d"), HirePolicy.rankByBonus(listOf("a", "b", "c", "d")) { caps[it] })
        // 上限相同保持原顺序
        val ties = mapOf("x" to 50, "y" to 50, "z" to 80)
        assertEquals(listOf("z", "x", "y"), HirePolicy.rankByBonus(listOf("x", "y", "z")) { ties[it] })
    }
}
