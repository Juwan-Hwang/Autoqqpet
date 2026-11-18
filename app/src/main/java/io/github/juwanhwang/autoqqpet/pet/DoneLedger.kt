package io.github.juwanhwang.autoqqpet.pet

/**
 * “今天”统计只算**已完成**的任务（2026-10-15）：次数与时长**同源同口径**，都在完成时记一次（按 storyId 去重）。
 *
 * 完成的时点：到期结算（我们发 1000 或读到服务端自结）→ 时长 = 任务总时长；被雇召回/提前结束 → 时长 = 实际进行的时间。
 * 开工时**不**动这些数；规划用的“今日学工累计分钟”（DailyStore.fatigueMinutes，含进行中，防超排 8 小时）是另一份，互不混用。
 * PK 不进这里（单独计 pk/pk_win）。
 */
object DoneLedger {
    const val K_STUDY = "done_study"
    const val K_WORK = "done_work"
    const val K_ADV = "done_adventure"
    const val K_SW_SEC = "done_sw_sec"
    const val K_ADV_SEC = "done_adv_sec"

    /** @return 是否记入（同一 storyId 只记一次；种类认不出不记）。 */
    fun apply(counts: MutableMap<String, Int>, doneIds: MutableSet<String>, storyId: String, tag: String, seconds: Long): Boolean {
        val key = when (tag) { "study" -> K_STUDY; "work" -> K_WORK; "adventure" -> K_ADV; else -> return false }
        if (storyId.isNotEmpty() && !doneIds.add(storyId)) return false
        counts[key] = (counts[key] ?: 0) + 1
        val secKey = if (tag == "adventure") K_ADV_SEC else K_SW_SEC
        counts[secKey] = (counts[secKey] ?: 0) + seconds.coerceAtLeast(0).coerceAtMost(24 * 3600L).toInt()
        return true
    }

    /** 给报告/快照用的“已完成”口径：study/work/adventure 换成已完成次数，其余计数原样。 */
    fun reportCounts(counts: Map<String, Int>): Map<String, Int> = counts + mapOf(
        "study" to (counts[K_STUDY] ?: 0), "work" to (counts[K_WORK] ?: 0), "adventure" to (counts[K_ADV] ?: 0),
    )

    fun studyWorkMin(counts: Map<String, Int>): Int = (counts[K_SW_SEC] ?: 0) / 60
    fun adventureMin(counts: Map<String, Int>): Int = (counts[K_ADV_SEC] ?: 0) / 60
}
