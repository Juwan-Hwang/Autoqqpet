package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.pet.DailyReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 报告摘要的「实收[...]」滤掉暂时状态值（心情/体力/清洁）：记录照旧，只是不展示。
 */
@RunWith(RobolectricTestRunner::class)
class DailyReportOneLineTest {

    @Test fun oneLineHidesStatusRewards() {
        val json = DailyReport.build(
            day = 20000,
            counts = mapOf("study" to 1, "pk" to 10, "pk_win" to 8),
            studyWorkMin = 45, adventureMin = 0,
            goldStart = 1000, goldEnd = 1200,
            rewards = mapOf("金币" to 350, "工分" to 12, "心情值" to 993, "体力值" to 162, "清洁值" to 190),
        )
        val line = DailyReport.oneLine(json)
        assertTrue(line.contains("金币+350"))
        assertTrue(line.contains("工分+12"))
        assertFalse(line.contains("心情值"))
        assertFalse(line.contains("体力值"))
        assertFalse(line.contains("清洁值"))
    }
}
