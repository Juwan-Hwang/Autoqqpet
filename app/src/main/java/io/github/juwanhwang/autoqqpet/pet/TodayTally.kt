package io.github.juwanhwang.autoqqpet.pet

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * “今天”学习/打工/冒险的次数与时长：**唯一口径**（v2.1.1）。主页「今天」卡片、QQ 侧快照、今日/昨日报告都从这里算，
 * 与主页「时间线」用的是同一份台账，所以两边永远对得上。
 *
 * 真机 2026-10-16：时间线 5 条学习（第 5 条进行中），「今天」却显示学习 6 次、冒险 1 次、学工 4 小时 30 分——
 * 旧口径是另一份完成计数（DoneLedger），出门记录对账补记、未确认后再核对等多个入口各自累加，和台账脱节。
 *
 * 规则：
 *  - 台账行 `HH:mm:ss|tag|Nm 或 Ns|storyId[|重复进账][|补记][|步=<stepId>@<round>][|赏=<名>:<量>,...][|结][|被拒]`；
 *  - 标了“重复”的行不算；同一 storyId 只算第一条；同种类、开始相差 < [SAME_START_MS] **且时间段真重叠**的视为同一条（storyId 缺失/换号时兜底）——45 秒短冒险背靠背时开始只差约 55 秒但不重叠，是两趟，不能合并（真机 2026-10-16：364 趟冒险被算成 150 趟）；
 *  - **进行中（开始 + 时长 > now）不算**，只算已完成；
 *  - 标了“被拒”的行不算（结算被服务器明确拒绝 = 根本没完成，旧版按行尾时长到点硬算成完成）；
 *  - 只认 study/work/adventure（PK、照顾不进这里）。
 *
 * 统一账本（2.1.13）：行尾的「步=」是这趟的排班归属、「赏=」是它的结算实收、「结」是完成已确认
 * （结算/补记写入）、「被拒」是结算被拒。排班步进度、今日已完成、实收汇总都从同一批行现算，
 * 不再各存一本账（见 DailyStore 的派生读法）。
 * 纯函数，单测直接跑。
 */
object TodayTally {

    const val SAME_START_MS = 90_000L

    data class Entry(
        val startMs: Long, val tag: String, val seconds: Long, val storyId: String, val duplicate: Boolean,
        /** 排班归属：stepId 与轮次（无归属时 stepId 为空、round 为 -1）。 */
        val stepId: String = "", val stepRound: Int = -1,
        /** 这趟的结算实收（名称已归一）；没结算/没读到明细时为空。 */
        val rewards: Map<String, Int> = emptyMap(),
        /** 结算被服务器明确拒绝：不计完成、不计步进度（墙钟占用由 DailyStore 疲劳口径单独处理）。 */
        val rejected: Boolean = false,
        /** 完成已确认：到期结算、提前结束或补记写入时打上；冒险步分钟只认带此标记的行。 */
        val finished: Boolean = false,
    ) {
        val endMs: Long get() = startMs + seconds * 1000L
    }

    data class Tally(val study: Int = 0, val work: Int = 0, val adventure: Int = 0, val studyWorkSec: Int = 0, val adventureSec: Int = 0) {
        val studyWorkMin: Int get() = studyWorkSec / 60
        val adventureMin: Int get() = adventureSec / 60
        /** 报告/快照的计数表：study/work/adventure 换成本口径，其余原样。 */
        fun into(counts: Map<String, Int>): Map<String, Int> = counts + mapOf("study" to study, "work" to work, "adventure" to adventure)
    }

    fun parse(line: String, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Entry? {
        val f = line.split('|')
        if (f.size < 3) return null
        val tag = f[1].trim()
        val time = runCatching { LocalTime.parse(f[0].trim()) }.getOrNull() ?: return null
        val dur = f[2].trim()
        val seconds = if (dur.endsWith("s")) dur.removeSuffix("s").toLongOrNull() ?: 0L else (dur.removeSuffix("m").toLongOrNull() ?: 0L) * 60L
        var stepId = ""; var stepRound = -1
        var rewards: Map<String, Int> = emptyMap()
        var rejected = false; var finished = false
        for (mark in f.drop(4)) {
            val m = mark.trim()
            when {
                m.startsWith("步=") -> {
                    val v = m.removePrefix("步=")
                    stepId = v.substringBeforeLast('@')
                    stepRound = v.substringAfterLast('@', "").toIntOrNull() ?: -1
                }
                m.startsWith("赏=") -> {
                    val sums = LinkedHashMap<String, Int>()
                    for (pair in m.removePrefix("赏=").split(',')) {
                        val name = pair.substringBeforeLast(':', "").trim()
                        val amt = pair.substringAfterLast(':', "").trim().toIntOrNull() ?: continue
                        if (name.isNotEmpty()) sums[name] = (sums[name] ?: 0) + amt
                    }
                    rewards = sums
                }
                m == "被拒" -> rejected = true
                m == "结" -> finished = true
            }
        }
        return Entry(
            startMs = day.atTime(time).atZone(zone).toInstant().toEpochMilli(),
            tag = tag, seconds = seconds.coerceAtLeast(0),
            storyId = f.getOrNull(3)?.trim().orEmpty(),
            duplicate = f.drop(4).any { it.contains("重复") },
            stepId = stepId, stepRound = stepRound, rewards = rewards,
            rejected = rejected, finished = finished,
        )
    }

    /** 去重后的条目（保留第一次出现的那条；含进行中，时间线也用它的口径）。 */
    fun dedupe(entries: List<Entry>): List<Entry> {
        val seenIds = HashSet<String>()
        val kept = ArrayList<Entry>()
        for (e in entries.sortedBy { it.startMs }) {
            if (e.duplicate) continue
            if (e.storyId.isNotEmpty() && !seenIds.add(e.storyId)) continue
            if (kept.any { it.tag == e.tag && kotlin.math.abs(it.startMs - e.startMs) < SAME_START_MS && it.startMs < e.endMs && e.startMs < it.endMs }) continue
            kept += e
        }
        return kept
    }

    fun tally(lines: List<String>, now: Long, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Tally =
        tallyEntries(lines.mapNotNull { parse(it, day, zone) }, now)

    fun tallyEntries(entries: List<Entry>, now: Long): Tally {
        var st = 0; var wk = 0; var adv = 0; var sw = 0L; var advSec = 0L
        for (e in dedupe(entries)) {
            if (e.rejected) continue     // 结算被拒 = 没完成（旧版到点硬算，是“次数虚增”的一条来源）
            if (e.endMs > now) continue   // 进行中不算（用户规则：「今天」只算已完成）
            when (e.tag) {
                "study" -> { st++; sw += e.seconds }
                "work" -> { wk++; sw += e.seconds }
                "adventure" -> { adv++; advSec += e.seconds }
            }
        }
        return Tally(st, wk, adv, sw.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), advSec.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
