package io.github.juwanhwang.autoqqpet.ui.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * 后端心跳里的完整快照（STATUS 广播 extra `snapshot`，结构见 docs/CONTRACT.md §2.2）。
 *
 * 这是界面的**首选数据源**：后端算好的任务、今日收益、提醒、昨日报告都在这里。
 * 每个字段都可能缺（旧后端、QQ 没起来、刚跨天），缺的一律是 null / 空，界面降级显示，绝不抛异常。
 */
@Immutable
data class FullSnapshot(
    val version: Int,
    val at: Long,
    val pet: PetValues?,
    val task: SnapTask?,
    val today: Earnings?,
    val yesterday: DayReport?,
    val alerts: List<SnapAlert>,
    val health: Health,
    val activityText: String?,
    val decision: String?,
    /** 召回相关设置（显式字段，界面不许去解析摘要字符串猜）；旧快照没有就 null。 */
    val config: SnapConfig? = null,
) {
    @Immutable
    data class PetValues(
        val name: String?,
        val feel: Double?,
        val hunger: Double?,
        val clean: Double?,
        val total: Double?,
        val gold: Long?,
        val stage: Int?,
        /** 三属性总量（0x9b62）；没读过为 null。 */
        val attrs: Attrs? = null,
        /** 今日经验（0x9abe）；没读过为 null。 */
        val exp: Exp? = null,
    )

    @Immutable
    data class Attrs(val strength: Long, val intel: Long, val charm: Long, val power: Long, val at: Long)

    @Immutable
    data class ExpTask(val name: String, val done: Long, val cap: Long, val exp: Long, val full: Boolean)

    @Immutable
    data class Exp(val today: Long, val cap: Long, val mult: Double, val selfCareFull: Boolean, val tasks: List<ExpTask>)

    @Immutable
    data class SnapTask(
        val kind: TaskKind?,
        val rawKind: String,
        val name: String?,
        val totalSec: Long,
        val progress: Float?,
        val startedAt: Long?,
        val endsAt: Long?,
        /** true=本模块开的；false=外来（被雇佣或你在 QQ 里手动开的）；null=不知道。 */
        val mine: Boolean?,
        val recallable: Boolean,
        val narrative: String?,
        /** 已确认是被好友雇走的（state=51 + 0x994a 通知）。 */
        val hired: Boolean = false,
    )

    /** 快照 config 里与召回有关的设置原值（缺字段 = null，界面据此决定文案，不猜摘要）。 */
    @Immutable
    data class SnapConfig(
        val recallOn: Boolean?,
        val recallMode: String?,
        val hireAlertOn: Boolean?,
    )

    @Immutable
    data class Earnings(
        val day: String,
        val counts: Map<String, Int>,
        val studyWorkMin: Int,
        val adventureMin: Int,
        val goldStart: Long?,
        val goldNow: Long?,
        val goldDelta: Long,
        val hireSpent: Int,
        val rewards: List<Pair<String, Long>>,
        /** 金币曲线 [(墙钟毫秒, 金币)]，按时间升序。 */
        val curve: List<Pair<Long, Long>>,
        /** 已完成任务精确秒数（-1 = 旧版没给）。短冒险 45 秒，不能用分钟口径。 */
        val studyWorkSec: Int = -1,
        val adventureSec: Int = -1,
    ) {
        fun count(k: String): Int = counts[k] ?: 0
    }

    @Immutable
    data class DayReport(
        val day: String,
        val study: Int,
        val work: Int,
        val adventure: Int,
        val pk: Int,
        val pkWin: Int,
        val hire: Int,
        val hireSpent: Int,
        val friendCare: Int,
        val coinbag: Int,
        val studyWorkMin: Int,
        val adventureMin: Int,
        val goldDelta: Long,
        val goldEnd: Long?,
        val rewards: List<Pair<String, Long>>,
    )

    enum class Level { ACTION, INFO }

    @Immutable
    data class SnapAlert(
        val id: String,
        val level: Level,
        val text: String,
        val at: Long,
        /** 事件键：划掉时回传给 QQ（同一事件不再出现）。旧后端没有 → 空。 */
        val incident: String = "",
        /** 来源（信箱只认 `0x9c19`）。 */
        val src: String = "",
    )

    /** 配置一致性自检（R10）：磁盘 prefs / 进程缓存 / runner 配置。 */
    @Immutable
    data class CfgCheckView(val ok: Boolean, val text: String, val at: Long)

    @Immutable
    data class Health(
        val feedBlocked: Boolean,
        val bathBlocked: Boolean,
        val payTired: Boolean,
        val penalized: Int,
        val mailClaimable: Int,
        val lastError: String?,
        val petReady: Boolean?,
        val cfgCheck: CfgCheckView? = null,
        /** 发这帧快照的 QQ 进程（R10：应只有主进程）。 */
        val process: String? = null,
        val pid: Int = 0,
        /** QQ 侧最近一次用的闹钟种类（alarmClock/exactIdle/idle），以及 QQ 能否排精确闹钟（null=旧版没报）。 */
        val alarmKind: String? = null,
        val exactAlarmOk: Boolean? = null,
        val lastTickAt: Long = 0,
    )

    companion object {
        /** 解析失败返回 null（交给旧数据源兜底），绝不抛。 */
        fun parse(raw: String?): FullSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching { from(JSONObject(raw)) }.getOrNull()
        }

        private fun from(o: JSONObject): FullSnapshot {
            val act = o.optJSONObject("activity")
            return FullSnapshot(
                version = o.optInt("v", 0),
                at = o.optLong("at", 0),
                pet = o.optJSONObject("pet")?.let(::pet),
                task = o.optJSONObject("task")?.let(::task),
                today = o.optJSONObject("today")?.let(::earnings),
                yesterday = o.optJSONObject("report_last")?.let(::report),
                alerts = o.optJSONArray("alerts")?.let(::alerts).orEmpty(),
                health = health(o.optJSONObject("health")),
                activityText = act?.str("text"),
                decision = act?.str("decision"),
                config = o.optJSONObject("config")?.let(::config),
            )
        }

        private fun config(o: JSONObject) = SnapConfig(
            recallOn = if (o.has("recall_on")) o.optBoolean("recall_on") else null,
            recallMode = o.str("recall_mode"),
            hireAlertOn = if (o.has("hire_alert_on")) o.optBoolean("hire_alert_on") else null,
        )

        private fun pet(o: JSONObject) = PetValues(
            name = o.str("name"),
            feel = o.num("feel"),
            hunger = o.num("hunger"),
            clean = o.num("clean"),
            total = o.num("total"),
            gold = o.num("gold")?.toLong(),
            stage = if (o.has("stage")) o.optInt("stage") else null,
            attrs = o.optJSONObject("attrs")?.let { a ->
                Attrs(a.optLong("strength"), a.optLong("intel"), a.optLong("charm"),
                    a.optLong("power", a.optLong("strength") + a.optLong("intel") + a.optLong("charm")), a.optLong("at"))
            },
            exp = o.optJSONObject("exp")?.let { e ->
                val ts = e.optJSONArray("tasks")
                Exp(
                    e.optLong("today"), e.optLong("cap"), e.optDouble("mult", 1.0).takeIf { !it.isNaN() } ?: 1.0, e.optBoolean("self_care_full"),
                    if (ts == null) emptyList() else (0 until ts.length()).mapNotNull { i ->
                        ts.optJSONObject(i)?.let { t -> ExpTask(t.optString("name"), t.optLong("done"), t.optLong("cap"), t.optLong("exp"), t.optBoolean("full")) }
                    },
                )
            },
        )

        private fun task(o: JSONObject): SnapTask {
            val raw = o.optString("kind")
            val prog = o.optDouble("progress", -1.0)
            return SnapTask(
                kind = TaskKind.ofTag(raw),
                rawKind = raw,
                name = o.str("name"),
                totalSec = o.optLong("total_sec", 0),
                progress = if (prog in 0.0..1.0) prog.toFloat() else null,
                startedAt = o.optLong("started_at", 0).takeIf { it > 0 },
                endsAt = o.optLong("ends_at", 0).takeIf { it > 0 },
                mine = if (o.isNull("mine") || !o.has("mine")) null else o.optBoolean("mine"),
                recallable = o.optBoolean("recallable", false),
                // 老版本 QQ 侧可能还在发没清洗的富文本（装新版后没 force-stop QQ），界面再洗一次
                narrative = o.str("narrative")?.let { io.github.juwanhwang.autoqqpet.pet.parse.RichText.clean(it) }
                    ?.takeIf { io.github.juwanhwang.autoqqpet.pet.parse.RichText.isNarrative(it) }
                    ?.let { io.github.juwanhwang.autoqqpet.pet.parse.RichText.truncate(it, 80) },
                hired = o.optBoolean("hired", false),
            )
        }

        private fun earnings(o: JSONObject): Earnings {
            val counts = HashMap<String, Int>()
            o.optJSONObject("counts")?.let { c -> c.keys().forEach { k -> counts[k] = c.optInt(k, 0) } }
            val start = o.optLong("gold_start", -1).takeIf { it >= 0 }
            val now = o.optLong("gold_now", -1).takeIf { it >= 0 }
            return Earnings(
                day = o.optString("day"),
                counts = counts,
                studyWorkMin = o.optInt("study_work_min", 0),
                adventureMin = o.optInt("adventure_min", 0),
                goldStart = start,
                goldNow = now,
                goldDelta = o.optLong("gold_delta", 0),
                hireSpent = o.optInt("hire_spent", 0),
                rewards = rewards(o.optJSONObject("rewards")),
                curve = curve(o.optJSONArray("gold_curve")),
                studyWorkSec = if (o.has("study_work_sec")) o.optInt("study_work_sec") else -1,
                adventureSec = if (o.has("adventure_sec")) o.optInt("adventure_sec") else -1,
            )
        }

        private fun report(o: JSONObject) = DayReport(
            day = o.optString("day"),
            study = o.optInt("study"),
            work = o.optInt("work"),
            adventure = o.optInt("adventure"),
            pk = o.optInt("pk"),
            pkWin = o.optInt("pk_win"),
            hire = o.optInt("hire"),
            hireSpent = o.optInt("hire_spent"),
            friendCare = o.optInt("friend_care"),
            coinbag = o.optInt("coinbag"),
            studyWorkMin = o.optInt("study_work_min"),
            adventureMin = o.optInt("adventure_min"),
            goldDelta = o.optLong("gold_delta"),
            goldEnd = o.optLong("gold_end", -1).takeIf { it >= 0 },
            rewards = rewards(o.optJSONObject("rewards")),
        )

        private fun alerts(a: JSONArray): List<SnapAlert> = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val text = o.str("text") ?: return@mapNotNull null
            SnapAlert(
                id = o.optString("id"),
                level = if (o.optString("level") == "action") Level.ACTION else Level.INFO,
                text = text,
                at = o.optLong("at", 0),
                incident = o.optString("incident"),
                src = o.optString("src"),
            )
        }
            // R7：信箱提醒只认 0x9c19 一键领取的结果；旧版按附件数挂的（没有 src）一律不显示
            .filter { it.id != "mail" || it.src == "mailclaim" }
            .sortedWith(compareBy<SnapAlert> { it.level.ordinal }.thenByDescending { it.at })

        private fun health(o: JSONObject?) = Health(
            feedBlocked = o?.optBoolean("feed_blocked") == true,
            bathBlocked = o?.optBoolean("bath_blocked") == true,
            payTired = o?.optBoolean("pay_tired") == true,
            penalized = o?.optInt("penalized", 0) ?: 0,
            mailClaimable = o?.optInt("mail_claimable", -1) ?: -1,
            lastError = o?.str("last_error"),
            petReady = if (o == null || !o.has("pet_ready")) null else o.optBoolean("pet_ready"),
            cfgCheck = o?.optJSONObject("cfg_check")?.let { c -> CfgCheckView(c.optBoolean("ok"), c.optString("text"), c.optLong("at")) },
            process = o?.str("process"),
            pid = o?.optInt("pid", 0) ?: 0,
            alarmKind = o?.str("alarm_kind"),
            exactAlarmOk = if (o == null || !o.has("exact_alarm_ok") || o.isNull("exact_alarm_ok")) null else o.optBoolean("exact_alarm_ok"),
            lastTickAt = o?.optLong("last_tick_at", 0) ?: 0,
        )

        private fun rewards(o: JSONObject?): List<Pair<String, Long>> {
            o ?: return emptyList()
            val out = ArrayList<Pair<String, Long>>()
            o.keys().forEach { k -> out += k to o.optLong(k, 0) }
            return out.filter { it.second != 0L }.sortedByDescending { it.second }
        }

        private fun curve(a: JSONArray?): List<Pair<Long, Long>> {
            a ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONArray(i) ?: return@mapNotNull null
                if (p.length() < 2) null else p.optLong(0) to p.optLong(1)
            }.sortedBy { it.first }
        }

        private fun JSONObject.str(k: String): String? =
            if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }

        private fun JSONObject.num(k: String): Double? =
            if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() && !it.isInfinite() }
    }
}

/**
 * 主页「被好友雇去打工了」的副标题：跟着召回设置走，不许承诺设置里没有的事。
 * 旧快照没有 config 字段（null）时沿用召回开着的说法（升级前的默认状态）。
 */
fun employedSubtitle(cfg: FullSnapshot.SnapConfig?): String = when {
    cfg?.recallOn == false && cfg.hireAlertOn == true ->
        "进度到 71% 会提醒你去召回，之后接着干自己的"
    cfg?.recallOn == false ->
        "自动召回已关，之后接着干自己的"
    cfg?.recallMode == "immediate" ->
        "会按设置立即召回，之后接着干自己的"
    else ->
        "进度到 71% 按设置召回（分成最高），之后接着干自己的"
}
