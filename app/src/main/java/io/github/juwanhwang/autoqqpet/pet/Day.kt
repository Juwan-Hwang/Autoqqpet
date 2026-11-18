package io.github.juwanhwang.autoqqpet.pet

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 全局"今天"的**唯一**口径：设备本地日期（用户理解的"今天"）。
 *
 * 血泪教训（DEV-NOTES 2026-10-06 ⑤）：DailyStore 与 FriendPolicy 曾各自用 `currentTimeMillis()/86400000`（UTC 日界），
 * 北京时间 00:00–08:00 仍算昨天，每日次数/时长/疲劳不按时重置。日志文件名、每日上限、当日计数、每日报告
 * 一律走这里，不许再出现第二份日界计算。
 */
object Day {
    /** 本地纪元日号（跨天比较用）。 */
    fun today(): Int = of(System.currentTimeMillis())

    /** 今天的日期（日志文件名等）。 */
    fun todayDate(): LocalDate = LocalDate.ofEpochDay(today().toLong())

    /** 任意时刻属于哪一天（按 [zone]，默认设备时区）；单测用它验证日界。 */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay().toInt()
}
