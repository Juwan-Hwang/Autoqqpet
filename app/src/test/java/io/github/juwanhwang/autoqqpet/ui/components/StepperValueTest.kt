package io.github.juwanhwang.autoqqpet.ui.components

import io.github.juwanhwang.autoqqpet.pet.SparkTonight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数字步进的纯逻辑：不绕回时与原先的夹值行为一致（日志保留等既有项不受影响）；
 * 绕回时档位按步长对齐，最高档再加回到最低、最低档再减跳到最高档（夜间暂停时刻 0..1439、步长 30）。
 */
class StepperValueTest {

    @Test fun clampedStepOneKeepsLegacyBehavior() {
        val r = 1..14
        assertEquals(6, steppedValue(5, r, 1, 1, wrap = false))
        assertEquals(4, steppedValue(5, r, 1, -1, wrap = false))
        assertEquals(14, steppedValue(14, r, 1, 1, wrap = false))
        assertEquals(1, steppedValue(1, r, 1, -1, wrap = false))
    }

    @Test fun clampedBigStepCoercesIntoRange() {
        val r = 0..1439
        assertEquals(1439, steppedValue(1430, r, 30, 1, wrap = false))
        assertEquals(0, steppedValue(10, r, 30, -1, wrap = false))
    }

    @Test fun wrapStepsWithinAlignedStops() {
        val r = 0..1439
        assertEquals(120, steppedValue(90, r, 30, 1, wrap = true))
        assertEquals(60, steppedValue(90, r, 30, -1, wrap = true))
        assertEquals(1410, steppedValue(1380, r, 30, 1, wrap = true))
        assertEquals(30, steppedValue(0, r, 30, 1, wrap = true))
    }

    @Test fun wrapAroundBothEnds() {
        val r = 0..1439
        // 最高档 1410（23:30）再加 → 00:00；00:00 再减 → 23:30。跨零点时段就靠这个绕圈设出来。
        assertEquals(0, steppedValue(1410, r, 30, 1, wrap = true))
        assertEquals(1410, steppedValue(0, r, 30, -1, wrap = true))
        // 存量若是非对齐值，加一步同样绕回最低档，不越界。
        assertEquals(0, steppedValue(1439, r, 30, 1, wrap = true))
    }

    @Test fun repeatPlanStartsSingleStep() {
        assertEquals(90L to 1, repeatStepPlan(0, wrap = false))
        assertEquals(90L to 1, repeatStepPlan(1199, wrap = false))
    }

    @Test fun repeatPlanAcceleratesInTwoStages() {
        assertEquals(70L to 5, repeatStepPlan(1200, wrap = false))
        assertEquals(70L to 5, repeatStepPlan(2599, wrap = false))
        assertEquals(60L to 20, repeatStepPlan(2600, wrap = false))
        assertEquals(60L to 20, repeatStepPlan(60_000, wrap = false))
    }

    @Test fun repeatPlanNeverAcceleratesWhenWrapping() {
        assertEquals(90L to 1, repeatStepPlan(0, wrap = true))
        assertEquals(90L to 1, repeatStepPlan(60_000, wrap = true))
    }

    @Test fun hhmmFormatsMinuteOfDay() {
        assertEquals("00:00", SparkTonight.hhmm(0))
        assertEquals("01:30", SparkTonight.hhmm(90))
        assertEquals("06:30", SparkTonight.hhmm(390))
        assertEquals("23:00", SparkTonight.hhmm(1380))
        assertEquals("23:30", SparkTonight.hhmm(1410))
        assertEquals("23:59", SparkTonight.hhmm(1439))
    }
}
