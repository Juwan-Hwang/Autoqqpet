package io.github.juwanhwang.autoqqpet.pet

import org.json.JSONObject
import java.time.LocalDate

/**
 * 每日报告（纯函数）：次数、时长、金币净变化、实收奖励、雇佣支出、决策分布。
 * 字段名是对前端的契约（docs/CONTRACT.md §2.6），改名要同步改契约。
 */
object DailyReport {

    /**
     * 暂时状态值（2026-10-19 用户定性）：心情/体力/清洁是状态、不是收益。
     * 记录照旧进 rewards，但用户可见的实收展示（收益卡明细、报告摘要）一律滤掉。
     */
    val STATUS_REWARD_NAMES = setOf("心情值", "体力值", "清洁值")

    fun isStatusReward(name: String): Boolean = name in STATUS_REWARD_NAMES

    fun build(
        day: Int,
        counts: Map<String, Int>,
        studyWorkMin: Int,
        adventureMin: Int,
        goldStart: Int,
        goldEnd: Int,
        rewards: Map<String, Int>,
        decisions: Map<String, Int> = emptyMap(),
    ): String {
        val o = JSONObject()
        o.put("day", LocalDate.ofEpochDay(day.toLong()).toString())
        o.put("study", counts["study"] ?: 0)
        o.put("work", counts["work"] ?: 0)
        o.put("adventure", counts["adventure"] ?: 0)
        o.put("pk", counts["pk"] ?: 0)
        o.put("pk_win", counts["pk_win"] ?: 0)
        o.put("recall", counts["recall"] ?: 0)
        o.put("hire", counts["hire"] ?: 0)
        o.put("hire_spent", counts["hire_spent"] ?: 0)
        o.put("friend_care", counts["friend_care"] ?: 0)
        o.put("coinbag", counts["coinbag"] ?: 0)
        o.put("coinbag_gold", counts["coinbag_gold"] ?: 0)
        o.put("checkin", counts["checkin"] ?: 0)
        o.put("study_work_min", studyWorkMin)
        o.put("adventure_min", adventureMin)
        o.put("gold_start", goldStart)
        o.put("gold_end", goldEnd)
        o.put("gold_delta", if (goldStart >= 0 && goldEnd >= 0) goldEnd - goldStart else 0)
        o.put("rewards", JSONObject().apply { rewards.forEach { (k, v) -> put(k, v) } })
        o.put("decisions", JSONObject().apply { decisions.entries.sortedByDescending { it.value }.take(8).forEach { put(it.key, it.value) } })
        return o.toString()
    }

    /** 一行人读摘要（日志与通知用）。 */
    fun oneLine(json: String): String = runCatching {
        val o = JSONObject(json)
        val rw = o.optJSONObject("rewards")
        val rewards = rw?.keys()?.asSequence()?.filter { !isStatusReward(it) }?.joinToString(" ") { "$it+${rw.optInt(it)}" }.orEmpty()
        "${o.optString("day")} 学${o.optInt("study")} 工${o.optInt("work")} 冒险${o.optInt("adventure")} PK${o.optInt("pk")}" +
            " 学工${o.optInt("study_work_min")}min 金币${signed(o.optInt("gold_delta"))}" +
            (if (o.optInt("hire_spent") > 0) " 雇佣-${o.optInt("hire_spent")}" else "") +
            (if (rewards.isNotEmpty()) " 实收[$rewards]" else "")
    }.getOrDefault("")

    private fun signed(v: Int) = if (v >= 0) "+$v" else "$v"
}
