package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import io.github.juwanhwang.autoqqpet.pet.JobInfo
import io.github.juwanhwang.autoqqpet.pet.Settlement

/**
 * 疲劳（收益降效）判定 —— 两路证据，都是服务器给的数字，不再有任何拍定时长：
 *  1. **目录读数 vs 今日峰值**（v1.1 起）：同一档位（subEventType）本次预计收益比今天见过的最高值低 ≥5% 且 ≥1 单位；
 *  2. **结算实收 vs 开工时预期**（v2 新增）：到期后读 0x9874 结算明细，打工的“工分 +N”/学习的属性加成比开工时目录给的少 ≥5% 且 ≥1。
 *     这是“这一条实际拿到多少”，比任何读数推算都直接（DEV-NOTES 2026-10-14：奖励明细结构就在 0x9874）。
 * 两路命中的档位并集进 [Assessment.penalized]，规划器只跳过这些档。
 */
object PayMonitor {

    const val DROP_PCT = 5

    data class Assessment(
        val penalized: Set<Long>, val notes: List<String>, val allGone: Boolean,
        /** 展示用：比今日首读低的档数 / 是否全部可做档都比首读低。 */
        val droppedCount: Int = 0, val allDropped: Boolean = false,
    )

    /**
     * @param peak `(key, 本次每小时收益×100) → 今日此前峰值×100`（0=今日首读）；由 DailyStore.payPeak 提供，同时更新峰值。
     * @param settleDrops 结算实收判定为降效的档位（当日）
     */
    fun assess(courses: List<CourseInfo>, jobs: List<JobInfo>, settleDrops: Set<Long>, peak: (String, Int) -> Int): Assessment =
        displayOnly(assessRaw(courses, jobs, settleDrops, peak))

    /**
     * 2026-10-15 结论：**目录报酬本身就是服务器按疲劳下调后的当前读数**（证据：DEV-NOTES 10-12 真机现场“30min 课 60→16/h，同一天同一档降 73%”；
     * 10-10 joblist “数字是当日疲劳降效后的读数”）→ 决策直接按当前报酬排序就已经把疲劳算进去了，再“跟峰值比、命中就剔档”等于双重惩罚。
     * 所以：**不再剔档**（penalized 恒空、allGone 恒 false），降幅只作展示统计（notes：本次 vs 今日首读）。
     */
    fun displayOnly(a: Assessment): Assessment = Assessment(emptySet(), a.notes, false, droppedCount = a.penalized.size, allDropped = a.allGone)

    /**
     * 整体疲劳判定（用户确认的规则）：疲劳 = 当天学习+打工累计满 8 小时。疲劳文案是**按档**给的（这一档做完会跨线），
     * 所以文案只给那一档降权（DayPlanner.crossFactor）；只有 **已满 8 小时** 或 **所有可做学工档都带文案** 才整体疲劳 →
     * penalized = 全部可做学工档、allGone = true（只去冒险）。
     */
    fun withText(a: Assessment, textTired: Map<Long, String>, courses: List<CourseInfo>, jobs: List<JobInfo>, fatigueMin: Int = 0): Assessment {
        val all = (courses.filter { it.canDo } .map { it.subEventType } + jobs.filter { it.canDo }.map { it.subEventType }).toSet()
        val full = fatigueMin >= FATIGUE_CAP_MIN
        val allText = all.isNotEmpty() && all.all { it in textTired }
        val notes = a.notes + textTired.filterKeys { it in all }.map { (k, v) -> "档位 #$k 服务器提示「$v」（只降权这一档）" } +
            (if (full) listOf("今日学工已满 ${fatigueMin / 60}h${fatigueMin % 60}m（8 小时疲劳线）") else emptyList())
        return if (full || allText) a.copy(penalized = all, allGone = true, notes = notes) else a.copy(notes = notes)
    }

    /** 原始比较（单测与展示用）：本次读数 vs 今日首读（[peak] 由调用方提供“首读”）。 */
    fun assessRaw(courses: List<CourseInfo>, jobs: List<JobInfo>, settleDrops: Set<Long>, peak: (String, Int) -> Int): Assessment {
        val penalized = HashSet<Long>()
        val notes = ArrayList<String>()
        fun scan(sub: Long, key: String, label: String, perHourX100: Int) {
            if (perHourX100 <= 0) return
            val prev = peak(key, perHourX100)
            if (prev <= 0) return
            val drop = (prev - perHourX100) * 100 / prev
            if (drop < DROP_PCT || prev - perHourX100 < 100) return
            penalized += sub
            notes += "$label 本次 ${"%.1f".format(perHourX100 / 100.0)}，今日首读 ${"%.1f".format(prev / 100.0)}（降 $drop%）"
        }
        val cs = courses.filter { it.canDo && it.durationMin > 0 }
        val js = jobs.filter { it.canDo && it.durationMin > 0 }
        cs.forEach { scan(it.subEventType, "s${it.subEventType}", "课「${it.name}」${it.durationMin}min", it.attrGain * 60 * 100 / it.durationMin) }
        js.forEach { scan(it.subEventType, "w${it.subEventType}", "岗「${it.name}」${it.durationMin}min", it.points * 60 * 100 / it.durationMin) }
        val dropsHere = settleDrops.filter { sub -> cs.any { it.subEventType == sub } || js.any { it.subEventType == sub } }
        // 结算实收只用于校准/学习，不再判降效（settleDrops 参数保留兼容，不进 penalized）
        @Suppress("UNUSED_VARIABLE") val unused = dropsHere
        val anything = cs.isNotEmpty() || js.isNotEmpty()
        val allGone = penalized.isNotEmpty() && anything &&
            (cs.isEmpty() || cs.all { it.subEventType in penalized }) && (js.isEmpty() || js.all { it.subEventType in penalized })
        return Assessment(penalized, notes, allGone)
    }

    /**
     * 结算实收是否低于预期。[kind]=work 比工分、study 比属性加成；冒险目录不报数，不判。
     * @return 人读说明；null = 没降（或没有可比数据）
     */
    fun settlementDrop(kind: String, expected: Int, s: Settlement): String? {
        if (expected <= 0) return null
        val actual = when (kind) {
            "work" -> s.points
            "study" -> s.attrGain
            else -> return null
        }
        if (actual <= 0) return null          // 明细里没认出这一项：不知道 ≠ 降了
        val drop = (expected - actual) * 100 / expected
        return if (drop >= DROP_PCT && expected - actual >= 1) "实收 $actual < 预期 $expected（降 $drop%）" else null
    }
}
