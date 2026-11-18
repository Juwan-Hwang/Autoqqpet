package io.github.juwanhwang.autoqqpet.pet

/** 宠物数值（心情/体力/清洁/综合/金币）。 */
data class PetValues(
    val feel: Double = 0.0,
    val hunger: Double = 0.0,
    val clean: Double = 0.0,
    val total: Double = 0.0,
    val gold: Double = 0.0,
)

/** 本人宠物身份。petId 为服务端下发的 base64 串。 */
data class OwnPetProfile(
    val userId: String,
    val petId: String,
    val petName: String = "",
)

/** 食物目录条目（FEED_TIMES 0x9949_1 的 field4）。 */
data class FoodItem(
    val foodId: String,
    val name: String,
    val balance: Long,
    val resourceId: Long = 0,
)

/** 洗护商城条目（BATH_ITEM_CONFIG 0x9bf1_1 的 field1）。 */
data class BathItem(
    val itemId: String,
    val name: String,
    val goldPrice: Long = 0,
    val cleanGain: Long = 0,
    val moodGain: Long = 0,
)

/** 户外任务状态（STORY_STATUS 0x975a_1）。 */
data class StoryStatus(
    val storyId: String,
    val stateCode: Long,
    val remainingSeconds: Long,
    val durationSeconds: Long,
    val startedAt: Long,
    val recallable: Boolean,
) {
    /** 已进行秒数（服务器没给总时长时为 0）。 */
    val elapsedSeconds: Long get() = (durationSeconds - remainingSeconds).coerceAtLeast(0)

    /** 进度 0..1；时长未知时返回 -1。 */
    val progress: Double get() = if (durationSeconds > 0) (elapsedSeconds.toDouble() / durationSeconds).coerceIn(0.0, 1.0) else -1.0

    /**
     * 被雇佣的**必要条件**：打工中状态码 51（2026-10-15 01:45 被雇实测 `{1:51, 2:599, 3:600}`）。
     * ⚠ 自己开的打工同样是 51 → 只凭它不能下结论，确认要再配 0x994a“雇佣了”通知或结算名里的“被雇佣”
     * （[io.github.juwanhwang.autoqqpet.pet.HireDetect]）。**storyId 前缀不能区分**（被雇也是 `6400_`）。
     */
    val workingState: Boolean get() = stateCode == WORKING_STATE

    /**
     * 能否召回——**不看服务器 recallable**：2026-10-15 01:52 被雇召回实测，`.5` 根本没下发，`0x9760 action=6000` 照样成功。
     * 只要还在进行中就算“可以试”；该不该召回由进度规则 + “是不是被雇佣”决定。
     */
    val canRecall: Boolean get() = remainingSeconds > 0

    /** storyId 前缀 = 场景：6100学 / 6400工（**被雇也是 6400**）/ 6700冒险 / 6900PK。 */
    val kindPrefix: String get() = storyId.take(4)

    /** 进度规则本身（到拿大头区间就该提醒/召回）。“算不算被雇佣”由调用方判断。 */
    fun shouldRemindRecall(): Boolean = progress >= RECALL_AT_PROGRESS && remainingSeconds > 0

    companion object {
        /** 打工进行中的状态码（自己开的与被雇佣的**都是这个值**）。 */
        const val WORKING_STATE = 51L

        /** 官方分成表（交接 §7）71~100% 区间下边界：被雇方召回拿 100% 基础 + 75% 增益；自然到期只拿 10% 增益。 */
        const val RECALL_AT_PROGRESS = io.github.juwanhwang.autoqqpet.net.Proto.RECALL_AT_PROGRESS

        /**
         * 0x975a 有时只回 `{1:状态,2:剩余,3:总}` 不带 storyId（打工 51 实测即如此）：状态读有在跑的任务却没有 storyId 时，
         * 用开工登记的 pendingId 补上（种类对得上才补：状态 51 = 打工，只配登记为 work 的）。v2.1.1 修打工后主页显示“稍作休息”。
         */
        fun fillStoryId(st: StoryStatus, pendingId: String, pendingKind: String): StoryStatus {
            if (st.storyId.isNotEmpty() || st.remainingSeconds <= 0 || pendingId.isEmpty()) return st
            val compatible = pendingKind.isEmpty() || if (st.workingState) pendingKind == "work" else true
            return if (compatible) st.copy(storyId = pendingId) else st
        }
    }
}

/** 课程/岗位/冒险目录通用条目（f50 canDo, f51 按钮文案或不可用原因, f52 subEvent, f15 额外收益文本）。 */
data class SceneOption(
    val name: String,
    val subEventType: Long,
    val canDo: Boolean,
    val cost: String = "",
    val duration: String = "",
    val reward: String = "",
    val unavailableReason: String = "",
    /** f15：形如 "+10点"（工分/经验）。 */
    val extra: String = "",
    /** 描述文本（岗位 f14 / 冒险 f10 或 f14），可用于可见收益线索。 */
    val description: String = "",
    /** f12（仅岗位）：工资区间展示串，形如 "302～453（雇佣金币加成：0～151）"——前数是本金、括号末尾是这口目录语境下的雇佣加成上限。2026-10-31 抓包证实：结算本金与 f8 的数字分毫不差（71 对 71），雇佣加成实得落在 0～上限内。 */
    val wageRange: String = "",
)

/** 页面规则 PAGE_RULES(0x96a4_1)：trace 内 JSON 的 count 是【服务端声明的当日剩余次数】，paths 是服务器下发的可执行事件。 */
data class PageRules(
    val trace: String = "",
    val serverCount: Int? = null,
    val paths: List<Triple<Long, Long, Long>> = emptyList(),
) {
    /** 服务器是否下发了 (page,eventType) 这一类事件；无路径信息时返回 null 表示"未知, 不阻断"。 */
    fun allows(page: Long, eventType: Long): Boolean? =
        if (paths.isEmpty()) null else paths.any { it.first == page && it.second == eventType }

    fun count(path: Triple<Long, Long, Long>): Int = paths.count { it == path }
}

/** 职业条目（OUTDOOR_OVERVIEW 0x9b60_1 的 f1 item）：statusCode==3 或名字为 ??? 即未解锁。 */
data class CareerInfo(
    val name: String,
    val careerType: Long,
    val statusCode: Long = 0,
    val message: String = "",
) {
    val unlocked: Boolean get() = careerType > 0 && statusCode != 3L && name.isNotEmpty() && name != "???"
}

/** PK 战力与主导属性类型（PK_POWER 0x9ad4_1）；dominantType 为服务端枚举(1/2/3 对应三属性)，好友响应可能为 0。 */
data class PKPower(
    val petId: String,
    val power: Long,
    val dominantType: Long,
)

/** 好友宠物实时数值（FRIEND_PROFILE 0x976c_0，请求用好友 UIN）。 */
data class FriendPet(
    val uin: String,
    val petId: String,
    val values: PetValues,
)

/** 开始任务回包（0x975e）：f1=storyId，f4={1:state,2:总时长秒,3:剩余秒,4:开始时间戳秒}（抓包实测 2026-10-14）。 */
data class StartedStory(
    val storyId: String,
    val state: Long = 0,
    val durationSeconds: Long = 0,
    val remainingSeconds: Long = 0,
    val startedAt: Long = 0,
)

/**
 * 好友宠物状态（0x9875_1）。回包 f1.1=每日被雇佣上限（实测 20）、f1.6=“对方宠物今天被雇佣次数已达上限”；
 * 好友在忙时另有“宠物正在写作业呢，稍后再来吧”这类文案（字段位置没定，按文案认）。
 */
data class FriendStatus(
    val hireDailyCap: Int,
    val notices: List<String>,
) {
    /** 被雇佣次数已达上限（服务器原文判定，不是我们猜）。 */
    val hireCapReached: Boolean get() = notices.any { it.contains("上限") && it.contains("雇佣") }
    /** 好友宠物正忙（文案判定）。 */
    val busy: Boolean get() = notices.any { it.contains("稍后再来") || it.contains("正在") }
    /** 能不能雇：没有任何阻断文案。 */
    val hireable: Boolean get() = !hireCapReached && !busy
    val reason: String get() = notices.firstOrNull { it.contains("上限") || it.contains("稍后") || it.contains("正在") }.orEmpty()
}

/** 结算展示页（0x9874_1）里的一条奖励，如“工分 +3”“金币 +120”。 */
data class Reward(val name: String, val amount: Long)

/** 结算展示页：叙事文案 + 奖励明细。 */
/** 结算/召回回包条目表（body.f1.9）里的一条见闻：[atSec] 出现时刻（秒）、[remainSec] 当时剩余秒数、[text] 纯文本。 */
data class StoryEntry(val atSec: Long, val remainSec: Long, val text: String)

data class Settlement(val storyId: String, val narrative: List<String>, val rewards: List<Reward>, val entries: List<StoryEntry> = emptyList()) {
    fun amountOf(vararg names: String): Long = rewards.filter { r -> names.any { r.name.contains(it) } }.sumOf { it.amount }
    val gold: Long get() = amountOf("金币")
    val points: Long get() = amountOf("工分")
    val attrGain: Long get() = amountOf("力量", "智力", "魅力")
}

/** 进行中故事的进度轮询（0x975f_1）：叙事流水（字段号未定，按可读文本收；解析时已用 RichText 清洗）。 */
data class StoryProgress(val storyId: String, val lines: List<String>) {
    /** 最后一条清洗后仍有实质中文内容的叙事行（图片/样式/URL 行已在解析时丢掉；这里再保险一次）。 */
    val latest: String get() = lines.lastOrNull { io.github.juwanhwang.autoqqpet.pet.parse.RichText.isNarrative(it) }.orEmpty()

    /**
     * 进度条目里命中了 [WEATHER_MARKERS] 任一标记 = 这一趟整趟零金币
     * （「天色不对」条目在出发后约 5 秒出现，2026-10-19 抓包+全量日志钉死）。
     */
    val weatherWarned: Boolean get() = lines.any { line -> WEATHER_MARKERS.any { line.contains(it) } }

    companion object {
        /**
         * 预示这一趟零金币的条目标记词，任一命中即算这一类。
         * 目前只有「天色不对」一条（原文「我发现天色不对，好像要发生什么...」）；
         * 以后发现新标记，同样先实测钉死再往这里加。
         */
        val WEATHER_MARKERS: List<String> = listOf("天色不对")
    }
}

/**
 * 金币袋（0x9d70/0x9d71）。⚠ f11/f12 **不是恒定字段**（2026-10-15 好友A那袋根本没有）→ 只当参考；
 * 可靠的是文案 `body.1.3.2`（「5个福袋共 330 金币，已领2个」）+ 自己余额前后差。
 */
data class CoinBag(
    val bagId: String,
    val total: Long,
    val remaining: Long,
    val summary: String,
)

/** 三属性总量（0x9b62 文案里分母为 0 的那组）。战力 = 三者之和（与 0x9ad4 自身战力逐位相等，交接 §1）。 */
data class Attributes(val strength: Long, val intel: Long, val charm: Long) {
    val power: Long get() = strength + intel + charm
    fun of(a: Attr): Long = when (a) { Attr.STRENGTH -> strength; Attr.INTEL -> intel; Attr.CHARM -> charm; else -> 0 }
}

/** 每日经验子任务（0x9abe f4）。key 不可靠（自己护理那项是不透明串），按 [targetPage]/中文名匹配。 */
data class ExpTask(
    val key: String, val name: String,
    /** f4 完成量 / f5 上限（次数型就是次数 3/3；护理型是消耗点数口径）。 */
    val done: Long, val cap: Long,
    /** f10 今日已得经验（标称值，未乘倍率）/ f11 该项经验上限（护理型 50；次数型常缺省）。 */
    val gotExp: Long, val expCap: Long,
    val targetPage: Long, val rule: String,
) {
    /** 自己喂食/洗澡：target_page=3000，或中文名带“自己”且是喂/洗（key 是不透明串，不认 key）。 */
    val isSelfCare: Boolean get() = targetPage == 3000L || (name.contains("自己") && (name.contains("喂") || name.contains("洗")))
    val full: Boolean get() = (cap > 0 && done >= cap) || (expCap > 0 && gotExp >= expCap)
}

/** 每日经验总览（0x9abe_0）：只信 body 层的 f2（今日，已乘倍率）与 f3（上限 120），别把子项加总。 */
data class DailyExp(val today: Long, val cap: Long, val multiplier: Double, val tasks: List<ExpTask>) {
    val full: Boolean get() = cap in 1..today
    val selfCare: ExpTask? get() = tasks.firstOrNull { it.isSelfCare }
    /** 自己护理这项今天已拿满：再为经验多喂多洗毫无意义（交接 §9.2：满了之后动作回包全空、经验不动）。 */
    val selfCareFull: Boolean get() = selfCare?.full == true
}

/** 火花阶段（0x9b87 `.10.1`，2026-10-15 01:28 12 条样本定死）。 */
enum class SparkStage(val code: Int, val cn: String) {
    LIT(1, "已点亮"), REKINDLE(2, "待重燃"), UNLIT(3, "未点亮"), UNKNOWN(0, "未知");
    companion object { fun of(c: Long): SparkStage = entries.firstOrNull { it.code.toLong() == c } ?: UNKNOWN }
}

/**
 * 一条火花记录。[days]=连续互踩天数（`.10.2`，未点亮时缺省 0）；[gapDays]=“N天没互踩”里的 N（只有待重燃有）；
 * [pokedToday]=文案是“小火苗升腾中”（今日已互踩）。
 */
data class SparkEntry(
    val uin: String, val nick: String, val petId: String, val stage: SparkStage, val days: Int,
    val gapDays: Int = -1, val pokedToday: Boolean = false, val text: String = "",
)

/** 被踩明细一条（0x985e）：来踩过我的人（可能是陌生人）。[today]=属于“今日新增”的那几条。 */
data class LikeVisitor(val uin: String, val nick: String, val count: Int, val today: Boolean)

/** 日记一篇（0x9762 月列表）：[date]="YYYY-MM-DD"，[uuid] 点赞/详情都用它。抓包 2026-11-11。 */
data class DiaryEntry(val date: String, val uuid: String)

/**
 * 背包里的一格任务道具（0x9bd5）：[family] 是道具族 id（356=学习、357=打工），[count] 是持有数量。
 * 同族可以有多个 SKU（如学习族的高钙奶/咕噜酸奶），使用命令只传族 id。抓包 2026-11-11。
 */
data class ItemStack(val family: Long, val count: Long, val name: String, val desc: String)

/**
 * SVIP/VIP 每日礼包（0x9bd4）：[state] 1=可领取、2=已领取；[subActId] 领取时交给 WebSSO 0x9377；
 * [items] 奖励文案（如 “点券*5”/“饼干*20”）。抓包实测 2026-11-10。
 */
data class VipGift(val state: Int, val subActId: String, val items: List<String>) {
    val claimable: Boolean get() = state == 1 && subActId.isNotEmpty()
}

/** 0x994a 通知里的“被雇佣”一条：雇主昵名 + 通知时刻（毫秒）。 */
data class HiredNotice(val employer: String, val atMillis: Long, val scene: Long)

/** 信箱一封（0x9c11_0 body.f1）：只收界面/日志需要的部分；邮件 id 与领取 token 是账号数据，不外传。 */
data class Mail(val title: String, val items: List<Reward>, val hasAttachment: Boolean)

/**
 * 写包/结算回包里的“故事结局”（0x9760 / 0x9874 同形，body.f1 下）：
 * `.7` 故事名（含“被雇佣”/“雇佣”）、`.12` 小提示（“未获得完整收益”）、`.18.8` 结算称号（大获全胜/惜败）、
 * `.3.1*` 奖励 `{2:名称,3:数量}`、`.16.7` 召回到账金币。
 */
data class StoryOutcome(
    val name: String = "",
    val notice: String = "",
    val title: String = "",
    val rewards: List<Reward> = emptyList(),
    val recallGold: Long = 0,
    /** 条目表（`.9`）全文：冒险结案时逐条入日志回查；不参与 [empty] 判定（召回成败口径不变）。 */
    val entries: List<StoryEntry> = emptyList(),
) {
    val empty: Boolean get() = name.isEmpty() && title.isEmpty() && rewards.isEmpty() && recallGold == 0L && notice.isEmpty()
    /** PK 胜负：只认结算称号；没有称号返回 null（**绝不拿金币 >0 判赢**：输局也给 15 金币）。 */
    val pkWon: Boolean? get() = when {
        title.contains("败") || title.contains("输") -> false
        title.contains("胜") || title.contains("赢") -> true
        else -> null
    }
    /** 我们是被雇的一方（结算名带“被雇佣”）。 */
    val hiredSide: Boolean get() = name.contains("被雇佣")
    /** 我们是雇主（名字带“雇佣”但不带“被”）。 */
    val employerSide: Boolean get() = !hiredSide && name.contains("雇佣")
    val gold: Long get() = rewards.filter { it.name.contains("金币") }.sumOf { it.amount }.takeIf { it > 0 } ?: recallGold
    val partial: Boolean get() = notice.contains("未获得完整收益")
}
