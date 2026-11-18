package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.Wrap

/**
 * 诊断快照文本（设置页“反馈问题”附带）。“此刻会怎么选”是真调了一次决策（纯函数，不发包不改状态），
 * 所以能直接回答“为什么不学习/为什么一直冒险”。
 */
object RunnerDiagnostics {

    fun render(
        config: RunnerConfig, daily: DailyStore, planner: DayPlanner,
        scheduleActive: Boolean, schedule: Schedule, scheduleStatus: String,
        snapshot: Snapshot?, stage: Long?,
    ): String = buildString {
        appendLine("【设置】${config.summary()}")
        if (snapshot == null) appendLine("【读数】快照读失败（下面只有当日计数）")
        else {
            val s = snapshot
            appendLine("【读数】金币=${s.gold} 体力=${s.stamina} 清洁=${s.clean} 饼干=${s.biscuits} 香皂=${s.soap}")
            appendLine("        目录：课程 ${s.courses.size} / 岗位 ${s.jobs.size} / 冒险 ${s.adventures.size}（可做 ${s.adventures.count { it.canDo }}）被削档=${s.penalizedSubs.size}")
            runCatching {
                if (scheduleActive) {
                    val r = SchedulePlanner(planner).decide(schedule, daily.scheduleCursor(), { st -> daily.stepProgress(st.id) }, s)
                    appendLine("【此刻会怎么选】${r.decision.kind} ${r.decision.detail} —— ${r.decision.reason}")
                    appendLine("【依据】排班第 ${r.cursor + 1}/${schedule.steps.size} 步（第 ${daily.scheduleRound() + 1} 轮）；阻塞=${r.blocked}；跑完一轮=${if (schedule.wrap == Wrap.LOOP) "回到第一步" else "停下"}")
                    schedule.steps.forEachIndexed { i, st ->
                        val (c, mn) = daily.stepProgress(st.id)
                        appendLine("  步 ${i + 1} ${st.summary()} → 已 ${st.progressText(c, mn)}${if (!st.enabled) "（已禁用）" else ""}${if (i == r.cursor) " ← 当前" else ""}")
                    }
                } else {
                    val d = planner.decide(s)
                    appendLine("【此刻会怎么选】${d.kind} ${d.detail} —— ${d.reason}")
                    if (d.trace.isNotEmpty()) appendLine("【依据】${d.trace}")
                }
            }
            s.courses.take(12).forEach { appendLine("  课 ${it.name} ${it.durationMin}min +${it.attrGain}${it.attr} 学费${it.fee} can=${it.canDo} rate=${"%.1f".format(planner.courseRate(it))}") }
            s.jobs.take(16).forEach {
                appendLine("  岗 ${it.name} ${it.durationMin}min 金币${if (it.incomeKnown) it.income.toString() + (if (it.incomeLearned) "(实收均值)" else "") else "?"} 工分${it.points} can=${it.canDo} rate=${"%.1f".format(planner.jobRate(it))}")
            }
            s.adventures.forEach { appendLine("  冒险 ${it.name} ${if (it.durationSec in 1..299) "${it.durationSec}s" else "${it.durationMin}min"} can=${it.canDo} rate=${"%.1f".format(planner.adventureRate(it))}") }
        }
        appendLine("【学园阶段】${stage ?: "?"}")
        appendLine("【当日计数】${daily.snapshot()}")
        appendLine("【今日实收】${daily.rewards().entries.joinToString(" ") { "${it.key}+${it.value}" }.ifBlank { "暂无" }} ｜ 起始金币 ${daily.goldStart()}")
        appendLine("【历史实收均值】${daily.learnedSummary()}")
        appendLine("【当日决策统计】${daily.decisionStatsText()}")
        appendLine("【台账】${daily.ledgerSummary()}")
        if (scheduleStatus.isNotEmpty()) appendLine("【$scheduleStatus】")
        appendLine("【当日日志】共 ${daily.logLines()} 行（末尾见下）")
        append(daily.logTailChars(100_000))
    }
}
