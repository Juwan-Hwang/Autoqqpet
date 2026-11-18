package io.github.juwanhwang.autoqqpet.pet

/**
 * 《出门记录》与单条详情（2026-10-16 协议新发现，见 docs/qqpet-protocol-spec.md §出门记录）。
 *
 * - 0x9876_1 = 出门记录列表（学习/打工/冒险/PK 历史流水），分页 `{1:游标(上一页最后一条时间戳 ms), 2:每页条数 20, 3:自己 petId, 100:2}`；
 *   每条 `{1:storyId, 3:标题, 4:结果枚举, 5:叙事文案, 8:恒 2}`。
 * - 0x9874_1 = 单条详情（传任意 storyId），与 0x975b_1 成对发出（页面行为；我们只读 0x9874）。
 *
 * 结果枚举（.1.8 = .1.15.4，列表里 .4）**跨类型共用**，解读前先看场景：学习 1=A+ 2=A 3=B+；PK 4=胜 6=负；7 未解。
 */
enum class OutingKind(val prefix: String, val cn: String) {
    STUDY("6100", "学习"), WORK("6400", "打工"), ADVENTURE("6700", "冒险"), PK("6900", "PK"), UNKNOWN("", "出门");

    val tag: String get() = when (this) { STUDY -> "study"; WORK -> "work"; ADVENTURE -> "adventure"; PK -> "pk"; UNKNOWN -> "" }

    companion object {
        /** storyId 前缀：6100_学习 / 6400_打工 / 6700_冒险 / 6900_挑战(PK)；6500 旧口径也算打工。 */
        fun ofStoryId(sid: String): OutingKind = when (sid.take(4)) {
            "6100" -> STUDY; "6400", "6500" -> WORK; "6700" -> ADVENTURE; "6900" -> PK; else -> UNKNOWN
        }

        /** 场景号（.1.11）。 */
        fun ofScene(scene: Long): OutingKind = when (scene) {
            6100L -> STUDY; 6400L, 6500L -> WORK; 6700L -> ADVENTURE; 6900L -> PK; else -> UNKNOWN
        }
    }
}

object OutingResult {
    /** 结果枚举 → 人读（按场景解；未知的如实写“结果 N”，不猜）。 */
    fun label(kind: OutingKind, code: Int): String = when {
        code <= 0 -> ""
        kind == OutingKind.STUDY -> when (code) { 1 -> "A+"; 2 -> "A"; 3 -> "B+"; else -> "结果 $code" }
        kind == OutingKind.PK -> when (code) { 4 -> "胜"; 6 -> "负"; else -> "结果 $code" }
        else -> "结果 $code"
    }

    /** PK 胜负（只认 4/6；其它返回 null）。 */
    fun pkWon(kind: OutingKind, code: Int): Boolean? = if (kind != OutingKind.PK) null else when (code) { 4 -> true; 6 -> false; else -> null }
}

/** 出门记录列表的一条。[atMs] 是从记录里认出的时间戳（毫秒，认不出为 0）——翻页游标用它。 */
data class OutingRecord(
    val storyId: String,
    val title: String,
    val result: Int,
    val narrative: String,
    val atMs: Long = 0,
) {
    val kind: OutingKind get() = OutingKind.ofStoryId(storyId)
    val resultLabel: String get() = OutingResult.label(kind, result)
    /** 故事名带“（被雇佣）”才是被雇判据（“（雇佣）”是我雇别人）。 */
    val hiredSide: Boolean get() = title.contains("（被雇佣）") || title.contains("(被雇佣)")
    val employerSide: Boolean get() = !hiredSide && (title.contains("（雇佣）") || title.contains("(雇佣)"))
}

data class OutingPage(val records: List<OutingRecord>) {
    /** 下一页游标 = 本页最后一条的时间戳（认不出就没法翻页 → 0）。 */
    val nextCursorMs: Long get() = records.lastOrNull()?.atMs ?: 0L
}

/** 属性奖励块 `.1.3.1 = {2:属性名, 3:数值, 7:同值}`：直接读本次加的属性，不用拿 0x9b62 前后差。 */
data class AttrGain(val name: String, val value: Long)

/** 叙事流水一段（.1.9 = {2:第几秒, 3:文案}）。 */
data class StoryBeat(val second: Long, val text: String)

/**
 * 0x9874_1 单条详情。字段（body 下 .1）：.7 标题、.8 结果枚举（=.15.4）、.10 开始时间戳秒、.11 场景、.13 课程 id、
 * .15.12 课程分钟数、.9 叙事流水、.12 文案（如「今日奖励已达上限，本次未获得额外奖励」）、.3.1 奖励条目（属性/金币/工分…）。
 */
data class StoryDetail(
    val storyId: String,
    val title: String = "",
    val result: Int = 0,
    val scene: Long = 0,
    val startSec: Long = 0,
    val courseId: Long = 0,
    val courseMinutes: Long = 0,
    val notice: String = "",
    val beats: List<StoryBeat> = emptyList(),
    val rewards: List<Reward> = emptyList(),
) {
    val kind: OutingKind get() = OutingKind.ofScene(scene).takeIf { it != OutingKind.UNKNOWN } ?: OutingKind.ofStoryId(storyId)
    val resultLabel: String get() = OutingResult.label(kind, result)
    /** 本次加的属性（力量/智力/魅力）。A+ 记录可能没有这一块——不要假设“A+ 额外魅力+3”。 */
    val attrGains: List<AttrGain> get() = rewards.filter { it.name in ATTRS }.map { AttrGain(it.name, it.amount) }
    val gold: Long get() = rewards.filter { it.name.contains("金币") }.sumOf { it.amount }
    /** PK 等每日奖励已达上限（本次没有额外奖励），算收益时要带上。 */
    val dailyCapReached: Boolean get() = notice.contains("上限") && (notice.contains("未获得") || notice.contains("没有"))
    val hiredSide: Boolean get() = title.contains("（被雇佣）") || title.contains("(被雇佣)")
    val empty: Boolean get() = title.isEmpty() && result == 0 && scene == 0L && startSec == 0L && rewards.isEmpty() && beats.isEmpty()

    companion object { val ATTRS = setOf("力量", "智力", "魅力") }
}
