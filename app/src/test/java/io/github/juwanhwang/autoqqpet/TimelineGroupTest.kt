package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.ui.data.LedgerEntry
import io.github.juwanhwang.autoqqpet.ui.data.TaskKind
import io.github.juwanhwang.autoqqpet.ui.data.groupTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间线“连续同类合并”的纯逻辑（用户 2026-10-16 提：几十条 45 秒冒险把早上的记录冲没了）。 */
class TimelineGroupTest {

    private fun e(kind: TaskKind, clock: String, sec: Int) =
        LedgerEntry(at = 0L, clock = clock, kind = kind, minutes = sec / 60, duplicate = false, seconds = sec)

    @Test fun consecutiveSameKindMergesWithCountAndTotal() {
        // 展示顺序：最新在前
        val groups = groupTimeline(listOf(
            e(TaskKind.ADVENTURE, "11:08", 45), e(TaskKind.ADVENTURE, "11:06", 45),
            e(TaskKind.ADVENTURE, "11:05", 44), e(TaskKind.WORK, "09:15", 2700),
        ))
        assertEquals(2, groups.size)
        assertTrue(groups[0].merged)
        assertEquals(3, groups[0].count)
        assertEquals(45 + 45 + 44, groups[0].totalSeconds)
        assertEquals("11:08", groups[0].head.clock)   // 最新一条的时间
        assertEquals("11:05", groups[0].tail.clock)   // 最早一条
        assertFalse(groups[1].merged)
        assertEquals(TaskKind.WORK, groups[1].kind)
    }

    @Test fun sameKindSeparatedByOtherKindDoesNotMerge() {
        val groups = groupTimeline(listOf(
            e(TaskKind.ADVENTURE, "11:08", 45), e(TaskKind.WORK, "10:00", 2700), e(TaskKind.ADVENTURE, "09:00", 45),
        ))
        assertEquals(3, groups.size)
        assertTrue(groups.all { !it.merged })
    }

    @Test fun emptyAndSingle() {
        assertTrue(groupTimeline(emptyList()).isEmpty())
        val one = groupTimeline(listOf(e(TaskKind.STUDY, "08:00", 2700)))
        assertEquals(1, one.size)
        assertEquals(1, one[0].count)
        assertEquals(2700, one[0].totalSeconds)
    }
}
