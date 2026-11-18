package io.github.juwanhwang.autoqqpet.ui.data

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 宠物在做的事。颜色、图标、文案都按它取（DESIGN.md「状态即颜色」）。 */
enum class TaskKind { STUDY, WORK, ADVENTURE, CARE, REST;

    companion object {
        fun ofTag(tag: String?): TaskKind? = when (tag?.trim()) {
            "study" -> STUDY
            "work" -> WORK
            "adventure" -> ADVENTURE
            else -> null
        }
    }
}

/**
 * 仪表盘的“此刻”是哪一种局面。顺序即优先级：越靠前越需要用户知道。
 */
enum class Phase {
    CHECKING,    // 刚启动，模块启用状态还没回来
    MODULE_OFF,  // LSPosed 里没启用
    PAUSED,      // 托管关着
    CONNECTING,  // 托管开着但还没收到过心跳
    LOST,        // 心跳断了
    OFFLINE,     // QQ 活着但请求连续回不来
    TASK,        // 有一条进行中的任务（学习/打工/冒险）
    CARE,        // 正在喂食/洗澡
    EMPLOYED,    // 被好友雇去打工
    OUTING,      // 有一条不是本模块开的任务
    IDLE,        // 托管中、手上没任务，等下一次醒来
}

@Immutable
data class CurrentTask(
    val kind: TaskKind,
    val name: String?,
    val startAt: Long?,
    val endAt: Long?,
    /** 服务器给的此刻叙事（“宠物正在…”），没有就是 null。 */
    val narrative: String? = null,
    /** true=本模块开的；false=外来；null=不知道。 */
    val mine: Boolean? = null,
) {
    fun progress(now: Long): Float? {
        val s = startAt ?: return null
        val e = endAt ?: return null
        if (e <= s) return null
        return ((now - s).toFloat() / (e - s)).coerceIn(0f, 1f)
    }

    fun remainingMs(now: Long): Long? = endAt?.let { (it - now).coerceAtLeast(0) }
}

@Immutable
data class LedgerEntry(
    val at: Long,
    val clock: String,       // HH:mm
    val kind: TaskKind,
    val minutes: Int,
    val duplicate: Boolean,
    /** 精确秒数（短冒险 45 秒不能按 1 分钟算）；旧台账只有分钟时 = minutes×60。 */
    val seconds: Int = minutes * 60,
)

/**
 * 时间线展示分组：连续同类事件合并成一行（用户 2026-10-16 提：一上午几十条 45 秒冒险逐条列，
 * 早上的正经记录被冲到几十条开外翻不着）。只影响展示，台账原文与计数口径不动。
 */
@Immutable
data class TimelineGroup(
    /** 组内最新一条（倒序展示时排在最前）。 */
    val head: LedgerEntry,
    /** 组内最早一条。 */
    val tail: LedgerEntry,
    val count: Int,
    val totalSeconds: Int,
) {
    val kind: TaskKind get() = head.kind
    val merged: Boolean get() = count > 1
}

/** 把已按展示顺序（最新在前）排好的条目按“连续同类”分组；不连续的同类不合并。 */
fun groupTimeline(entries: List<LedgerEntry>): List<TimelineGroup> {
    val out = ArrayList<TimelineGroup>()
    for (e in entries) {
        val last = out.lastOrNull()
        if (last != null && last.kind == e.kind) {
            out[out.lastIndex] = TimelineGroup(head = last.head, tail = e, count = last.count + 1, totalSeconds = last.totalSeconds + e.seconds)
        } else {
            out += TimelineGroup(head = e, tail = e, count = 1, totalSeconds = e.seconds)
        }
    }
    return out
}

@Immutable
data class TodayStats(
    val study: Int,
    val work: Int,
    val adventure: Int,
    val studyWorkMinutes: Int,
    val adventureMinutes: Int,
    val recalls: Int,
    val tired: Boolean,
    /** 已完成任务的精确秒数（区分“0”与“不到 1 分钟”）；-1 = 旧版没给。 */
    val studyWorkSec: Int = -1,
    val adventureSec: Int = -1,
)

/**
 * 界面看到的一切（只读快照）。每个字段都可能缺：后端没发、还没到、或旧版本根本没有。
 * 缺的一律是 null / 空，由界面降级成骨架或“—”，绝不抛异常。
 */
@Immutable
data class PetSnapshot(
    val moduleEnabled: Boolean?,
    val agreed: Boolean,
    val autoOn: Boolean,
    val attr: String,
    val mode: String,
    val status: String?,
    val lastHeartbeatAt: Long,
    val nextWakeAt: Long,
    val netBadAt: Long,
    val alert: String,
    val task: CurrentTask?,
    val phase: Phase,
    val today: TodayStats?,
    val timeline: List<LedgerEntry>,
    /** 心跳里的全部字段（含今后新增的），由 [HeartbeatFields] 注册表决定怎么显示。 */
    val heartbeat: Map<String, Any?>,
    val heartbeatAt: Long,
    /** 后端完整快照（v2）；旧后端或还没收到时为 null。 */
    val full: FullSnapshot? = null,
    /** 只冒险模式固定刷的冒险名（空=自动），主页「刷哪个」用。 */
    val advOnlyName: String = "",
    /** 最优调度的目标（gold/points/attr），主页「优先什么」用。 */
    val autoGoal: String = "attr",
) {
    companion object {
        val EMPTY = PetSnapshot(
            moduleEnabled = null, agreed = false, autoOn = false, attr = "力量", mode = "auto",
            status = null, lastHeartbeatAt = 0, nextWakeAt = 0, netBadAt = 0, alert = "",
            task = null, phase = Phase.CHECKING, today = null, timeline = emptyList(),
            heartbeat = emptyMap(), heartbeatAt = 0,
        )

        /** 与看门狗同一个口径：超过 3 分钟没心跳 = 失联。 */
        const val STALE_MS = 180_000L
    }
}

/** 状态文本里能读出的信息（后端 tick 的返回串，格式见 AutoRunner）。 */
internal data class StatusHint(
    val startedTag: String? = null,
    val startedDetail: String? = null,
    val busy: Boolean = false,
    val busyRemainSec: Long? = null,
    val employed: Boolean = false,
    val care: Boolean = false,
    val paused: Boolean = false,
    val notReady: Boolean = false,
)

internal object StatusParser {
    private val remain = Regex("剩(\\d+)s")

    fun parse(raw: String?): StatusHint {
        if (raw.isNullOrBlank()) return StatusHint()
        // “续跑·…” “启动·…” 只是前缀
        val s = raw.trim().let { t ->
            if (t.startsWith("续跑·") || t.startsWith("启动·")) t.substring(3).trim() else t
        }
        return when {
            s.startsWith("started:") -> {
                val rest = s.removePrefix("started:")
                StatusHint(startedTag = rest.substringBefore(':'), startedDetail = rest.substringAfter(':', "").ifBlank { null })
            }
            s.startsWith("busy:") -> StatusHint(busy = true, busyRemainSec = remain.find(s)?.groupValues?.get(1)?.toLongOrNull())
            s.startsWith("employed-wait") -> StatusHint(employed = true)
            s.contains("喂食") || s.contains("洗澡") -> StatusHint(care = true)
            s.contains("已暂停") -> StatusHint(paused = true)
            s.startsWith("not-ready") -> StatusHint(notReady = true)
            else -> StatusHint()
        }
    }
}

/** 台账一行：`HH:mm:ss|tag|Nm 或 Ns|storyId[|重复进账]`（2026-10-16 起不足整分的记秒，如“附近走走”45s）。 */
internal object LedgerParser {
    fun parse(lines: List<String>, take: Int, zone: ZoneId = ZoneId.systemDefault()): List<LedgerEntry> {
        val today = LocalDate.now(zone)
        // 与「今天」计数同一套去重（TodayTally.dedupe）：没被保留的行标成 duplicate，时间线与计数永远一致
        val kept = io.github.juwanhwang.autoqqpet.pet.TodayTally.dedupe(
            lines.mapNotNull { io.github.juwanhwang.autoqqpet.pet.TodayTally.parse(it, today, zone) },
        ).map { it.startMs to it.tag }.toMutableList()
        val all = lines.mapNotNull { line ->
            val f = line.split('|')
            if (f.size < 3) return@mapNotNull null
            val kind = TaskKind.ofTag(f[1]) ?: return@mapNotNull null
            val time = runCatching { LocalTime.parse(f[0].trim()) }.getOrNull() ?: return@mapNotNull null
            val dur = f[2].trim()
            val seconds = if (dur.endsWith("s")) dur.removeSuffix("s").toIntOrNull() ?: 0 else (dur.removeSuffix("m").toIntOrNull() ?: 0) * 60
            val minutes = (seconds + 59) / 60
            val at = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
            val keep = kept.remove(at to f[1].trim())
            LedgerEntry(
                at = at,
                clock = "%02d:%02d".format(time.hour, time.minute),
                kind = kind,
                minutes = minutes,
                duplicate = !keep || f.drop(4).any { it.contains("重复") },
                seconds = seconds,
            )
        }.sortedBy { it.at }
        return if (take <= 0) all else all.takeLast(take.coerceAtMost(all.size))
    }
}
