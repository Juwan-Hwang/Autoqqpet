package io.github.juwanhwang.autoqqpet.hook

import io.github.juwanhwang.autoqqpet.optimizer.AutoRunner
import io.github.juwanhwang.autoqqpet.pet.Day
import org.json.JSONArray
import org.json.JSONObject

/**
 * 心跳快照（STATUS 广播的 `snapshot` extra，JSON 串）。字段表见 docs/CONTRACT.md §2，**改字段必须同步改契约并升 [VERSION]**。
 * 只读内存视图与 DailyStore（本地 prefs），不发网络请求：QQ 连不上时心跳照样能发。
 */
object StatusSnapshot {
    const val VERSION = 2

    fun build(rt: HostRuntime, nextWakeAt: Long, netBadAt: Long): String {
        val now = System.currentTimeMillis()
        val v = rt.view
        val d = rt.daily
        val cfg = rt.runner?.config
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("at", now)
        o.put("auto_on", rt.autoEnabled)
        o.put("state", rt.lastState)
        o.put("activity", JSONObject().put("kind", v.activity).put("text", v.activityText).put("decision", v.lastDecision))

        val pet = JSONObject()
        pet.put("name", rt.service?.session?.petName.orEmpty())
        v.values?.let {
            pet.put("feel", round1(it.feel)).put("hunger", round1(it.hunger)).put("clean", round1(it.clean))
                .put("total", round1(it.total)).put("gold", it.gold.toLong()).put("at", v.valuesAt)
        }
        pet.put("stage", v.stage)
        v.attrs?.let { a ->
            pet.put("attrs", JSONObject().put("strength", a.strength).put("intel", a.intel).put("charm", a.charm)
                .put("power", a.power).put("at", v.attrsAt))
        }
        v.exp?.let { e ->
            pet.put("exp", JSONObject().put("today", e.today).put("cap", e.cap).put("mult", Math.round(e.multiplier * 100) / 100.0)
                .put("self_care_full", e.selfCareFull).put("at", v.expAt)
                .put("tasks", JSONArray().apply {
                    e.tasks.forEach { t -> put(JSONObject().put("name", t.name).put("done", t.done).put("cap", t.cap).put("exp", t.gotExp).put("full", t.full)) }
                }))
        }
        o.put("pet", pet)

        val st = v.story
        // v2.1.1 修“开工后主页显示稍作休息”：view.story 只在每轮开头读一次（开工前那次 = 空闲），
        // 开工后到期前不再有新的一轮，快照就一直报 task=null，而快照新鲜时界面以它为准 → 显示空闲。
        // 现在：服务器读数没有在跑的任务时，用开工时登记的 pending（storyId/种类/服务器开始时间推出的到期时刻/名字）补上。
        val serverLive = st != null && st.storyId.isNotEmpty() && st.storyId.length >= 4 &&
            io.github.juwanhwang.autoqqpet.optimizer.TaskClock.endAt(st.startedAt, st.durationSeconds, v.storyAt, st.remainingSeconds) > now
        val pend = if (!serverLive && d != null) pendingTask(d.pendingId(), d.pendingKind(), d.pendingEnd(), d.pendingDurationSec(), d.pendingName(), now) else null
        if (pend != null) {
            o.put("task", pend.put("mine", d?.isMine(d.pendingId()) ?: JSONObject.NULL).put("hired", v.hiredNow).put("narrative", v.narrative))
        } else if (st != null && (st.storyId.isNotEmpty() || st.remainingSeconds > 0)) {
            val elapsed = ((now - v.storyAt) / 1000).coerceAtLeast(0)
            val total = st.durationSeconds
            // 以服务器开始时间戳为准（B7）：endsAt = 开始秒 + 总时长；没有开始时间戳才退回“读到时的剩余秒 − 读后流逝”
            val endsAt = io.github.juwanhwang.autoqqpet.optimizer.TaskClock.endAt(st.startedAt, total, v.storyAt, st.remainingSeconds)
            val remaining = if (st.startedAt > 1_000_000_000L && total > 0) ((endsAt - now) / 1000).coerceAtLeast(0) else (st.remainingSeconds - elapsed).coerceAtLeast(0)
            o.put("task", JSONObject()
                .put("story_id", st.storyId)
                .put("kind", kindOf(st.kindPrefix).let { k -> if (k == "unknown" && st.workingState) "work" else k })
                .put("name", (if (d != null && d.pendingId() == st.storyId) d.pendingName() else "").ifEmpty { v.externalName })
                .put("state", st.stateCode)
                .put("total_sec", total)
                .put("remaining_sec", remaining)
                .put("progress", if (total > 0) round3(((total - remaining).toDouble() / total).coerceIn(0.0, 1.0)) else -1.0)
                .put("started_at", st.startedAt * 1000)
                .put("ends_at", if (remaining > 0) endsAt else 0L)
                .put("mine", v.storyMine ?: JSONObject.NULL)
                .put("recallable", st.recallable)
                .put("hired", v.hiredNow)
                .put("narrative", v.narrative))
        } else o.put("task", JSONObject.NULL)

        if (d != null) {
            val counts = d.counts()
            val done = d.doneTally()   // 「今天」唯一口径（台账去重、只算已完成），与界面时间线同源
            val goldNow = v.values?.gold?.toLong() ?: -1L
            val goldStart = d.goldStart().toLong()
            o.put("today", JSONObject()
                .put("day", Day.todayDate().toString())
                // “今天”只算已完成（study/work/adventure 次数与时长同源：完成时记）；开工计数放 started，规划用分钟放 planning_study_work_min
                .put("counts", JSONObject().apply { done.into(counts).filterKeys { !it.contains('-') }.forEach { (k, c) -> put(k, c) } })
                .put("started", JSONObject().put("study", counts["study"] ?: 0).put("work", counts["work"] ?: 0).put("adventure", counts["adventure"] ?: 0))
                .put("study_work_min", done.studyWorkMin)
                .put("adventure_min", done.adventureMin)
                .put("study_work_sec", done.studyWorkSec)
                .put("adventure_sec", done.adventureSec)
                .put("planning_study_work_min", d.fatigueMinutes())
                .put("gold_start", goldStart)
                .put("gold_now", goldNow)
                .put("gold_delta", if (goldStart >= 0 && goldNow >= 0) goldNow - goldStart else 0)
                .put("hire", counts["hire"] ?: 0)
                .put("hire_spent", counts["hire_spent"] ?: 0)
                .put("rewards", JSONObject().apply { d.rewards().forEach { (k, c) -> put(k, c) } })
                .put("gold_curve", JSONArray().apply { d.goldCurve().forEach { (t, g) -> put(JSONArray().put(t * 1000).put(g)) } })
                .put("decisions", JSONObject().apply { d.decisionStats().entries.sortedByDescending { it.value }.take(6).forEach { put(it.key, it.value) } }))
            o.put("report_last", d.lastReport().takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
        }

        o.put("next_wake_at", nextWakeAt)
        o.put("health", JSONObject()
            .put("last_tick_at", v.lastTickAt)
            .put("alarm_kind", (rt.scheduler as? AlarmScheduler)?.lastAlarmKind ?: "")
            .put("exact_alarm_ok", runCatching { (rt.scheduler as? AlarmScheduler)?.canExact() }.getOrNull() ?: JSONObject.NULL)
            .put("net_bad_at", netBadAt)
            .put("last_error", v.lastError)
            .put("last_error_at", v.lastErrorAt)
            .put("feed_blocked", (d?.count("feed-fail") ?: 0) >= 3 || (d?.banUntilMs("feed-fail") ?: 0) > now)
            .put("bath_blocked", (d?.count("bath-fail") ?: 0) >= 3 || (d?.banUntilMs("bath-fail") ?: 0) > now)
            .put("pay_tired", (d?.count(AutoRunner.KEY_PAY_TIRED_NOW) ?: 0) > 0)
            .put("penalized", v.penalizedCount)
            .put("mail_claimable", v.mailClaimable)
            .put("decision_packets", v.decisionPackets)
            .put("catalog_packets", v.catalogPackets)
            .put("spark_poked", v.sparkProgress.first)
            .put("spark_target", v.sparkProgress.second)
            .put("process", rt.processName)
            .put("pid", android.os.Process.myPid())
            .put("cfg_check", rt.cfgCheck?.let { c ->
                JSONObject().put("ok", c.ok).put("at", c.at).put("text", c.line())
                    .put("disk_vs_memory", JSONArray(c.diskVsMemory)).put("runner_vs_disk", JSONArray(c.runnerVsDisk))
            } ?: JSONObject.NULL)
            .put("entry", rt.service?.entryName.orEmpty())
            .put("pet_ready", rt.service?.petId?.isNotEmpty() == true))
        o.put("alerts", JSONArray().apply {
            rt.alerts.all().forEach { a -> put(JSONObject().put("id", a.id).put("level", a.level.name.lowercase()).put("text", a.text).put("at", a.at)
                .put("incident", a.incident).put("src", a.src)) }
        })
        if (cfg != null) o.put("config", JSONObject()
            .put("mode", cfg.mode).put("attr", if (cfg.balancedStudy) "均衡" else cfg.targetAttr.name).put("work_career", cfg.workCareerType)
            .put("recall_on", cfg.recallEnabled)
            .put("recall_mode", cfg.recallMode)
            .put("hire_alert_on", cfg.hireReminder)
            .put("summary", cfg.summary())
            .put("settings_at", rt.settings()?.getLong(io.github.juwanhwang.autoqqpet.store.SettingsStamp.KEY, 0L) ?: 0L))
        return o.toString()
    }

    /**
     * 开工登记（pending）→ 快照 task。到期了/没登记/种类认不出返回 null。纯函数（单测）。
     * 到期时刻 [end] 开工时已按服务器开始时间戳算好（TaskClock.endAt）。
     */
    fun pendingTask(id: String, kind: String, end: Long, durSec: Long, name: String, now: Long): JSONObject? {
        if (id.isEmpty() || end <= now) return null
        val main = setOf("study", "work", "adventure")
        val k = kind.takeIf { it in main } ?: kindOf(id.take(4)).takeIf { kind.isEmpty() && it in main } ?: return null
        val remaining = ((end - now) / 1000).coerceAtLeast(0)
        val total = durSec.coerceAtLeast(remaining)
        return JSONObject()
            .put("story_id", id)
            .put("kind", k)
            .put("name", name)
            .put("state", 0)
            .put("total_sec", total)
            .put("remaining_sec", remaining)
            .put("progress", if (total > 0) round3(((total - remaining).toDouble() / total).coerceIn(0.0, 1.0)) else -1.0)
            .put("started_at", if (total > 0) end - total * 1000 else 0L)
            .put("ends_at", end)
            .put("recallable", true)
            .put("source", "pending")
    }

    private fun kindOf(prefix: String) = when (prefix) { "6100" -> "study"; "6400", "6500" -> "work"; "6700" -> "adventure"; "6900" -> "pk"; else -> "unknown" }
    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun round3(x: Double) = Math.round(x * 1000) / 1000.0
}
