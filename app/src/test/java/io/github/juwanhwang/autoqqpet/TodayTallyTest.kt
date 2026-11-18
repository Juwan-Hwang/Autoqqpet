package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.pet.TodayTally
import io.github.juwanhwang.autoqqpet.ui.data.LedgerParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** v2.1.1：「今天」与时间线同源——台账去重、进行中不算（真机 2026-10-16：时间线 5 条学习，今天却显示 6 次 + 冒险 1 次）。 */
class TodayTallyTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 16)
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val ledger = listOf(
        "00:01:10|study|45m|6100_a",
        "00:46:20|study|45m|6100_b",
        "01:34:05|study|45m|6100_c",
        "01:34:40|study|45m|6100_c2",          // 同种类 35 秒内又一条（换号/重复上报）→ 同一条
        "02:21:00|study|45m|6100_d",
        "02:21:00|study|45m|6100_d|重复进账",   // 显式重复
        "03:57:30|study|45m|6100_e",            // 进行中
        "02:30:00|study|45m|6100_b",            // 同 storyId 补记 → 不算
    )

    @Test fun screenshotCase_fourDoneThreeHours_noPhantomAdventure() {
        val t = TodayTally.tally(ledger, now = at(4, 20), day = day, zone = zone)
        assertEquals(4, t.study)
        assertEquals(0, t.work)
        assertEquals(0, t.adventure)
        assertEquals(3 * 3600, t.studyWorkSec)
        assertEquals(0, t.adventureSec)
    }

    @Test fun inProgressCountsOnlyAfterItEnds() {
        assertEquals(4, TodayTally.tally(ledger, now = at(4, 42), day = day, zone = zone).study)   // 03:57:30+45m = 04:42:30，还差 30 秒
        assertEquals(5, TodayTally.tally(ledger, now = at(4, 43), day = day, zone = zone).study)
    }

    @Test fun yesterdayReportSameRules() {
        val t = TodayTally.tally(ledger, now = Long.MAX_VALUE, day = day, zone = zone)
        assertEquals(5, t.study)
        assertEquals(5 * 45, t.studyWorkMin)
        assertEquals(mapOf("study" to 5, "work" to 0, "adventure" to 0, "pk" to 2), t.into(mapOf("study" to 9, "pk" to 2)))
    }

    @Test fun adventureSecondsAndReconciledLine() {
        val lines = listOf("05:00:00|adventure|45s|6700_x", "05:10:00|work|45m|6400_y|补记", "05:10:30|work|45m|6400_z")
        val t = TodayTally.tally(lines, now = at(23, 0), day = day, zone = zone)
        assertEquals(1, t.adventure); assertEquals(45, t.adventureSec)
        assertEquals(1, t.work); assertEquals(45 * 60, t.studyWorkSec)
    }

    /** 真机 2026-10-16 下午：45 秒短冒险背靠背（开始间隔约 55 秒 < SAME_START_MS），
     * 旧去重把隔一趟误判成同一趟的重复记录——364 趟只算出 150 趟。不重叠就是两趟。 */
    @Test fun backToBackShortAdventuresAllCounted() {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        val lines = (0 until 10).map { i ->
            val start = day.atTime(10, 0).plusSeconds(i * 55L)
            "${start.format(fmt)}|adventure|45s|6700_b$i"
        }
        val t = TodayTally.tally(lines, now = at(23, 0), day = day, zone = zone)
        assertEquals(10, t.adventure)
        assertEquals(10 * 45, t.adventureSec)
    }

    /** 同一天的完整台账（早上学工 + 几百趟冒险）进来时，学工计数不能被冒险淹掉。 */
    @Test fun heavyAdventureDayKeepsStudyWorkCounts() {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        val lines = ArrayList<String>()
        for (i in 0 until 6) lines += "${day.atTime(0, 5).plusMinutes(i * 50L).format(fmt)}|study|45m|6100_s$i"
        for (i in 0 until 6) lines += "${day.atTime(5, 10).plusMinutes(i * 50L).format(fmt)}|work|45m|6400_w$i"
        for (i in 0 until 400) lines += "${day.atTime(10, 20).plusSeconds(i * 55L).format(fmt)}|adventure|45s|6700_h$i"
        val t = TodayTally.tally(lines, now = at(23, 59), day = day, zone = zone)
        assertEquals(6, t.study)
        assertEquals(6, t.work)
        assertEquals(400, t.adventure)
        assertEquals(12 * 45 * 60, t.studyWorkSec)
        assertEquals(400 * 45, t.adventureSec)
    }

    @Test fun timelineUsesSameDedupe() {
        val tl = LedgerParser.parse(ledger, 0, zone)
        // 时间线按时间排序；被去重掉的行标 duplicate（界面过滤掉），剩 5 条学习（含进行中）
        assertEquals(5, tl.count { !it.duplicate })
        assertEquals(tl.sortedBy { it.at }, tl)
    }
}
