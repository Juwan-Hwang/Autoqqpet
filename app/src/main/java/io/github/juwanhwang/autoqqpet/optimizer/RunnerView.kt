package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.Attributes
import io.github.juwanhwang.autoqqpet.pet.DailyExp
import io.github.juwanhwang.autoqqpet.pet.HiredNotice
import io.github.juwanhwang.autoqqpet.pet.PetValues
import io.github.juwanhwang.autoqqpet.pet.StoryStatus

/**
 * 循环最近一次看到的世界（只由 tick 线程写，心跳线程读；字段各自 volatile，读到的是“某一刻”的一致近似即可）。
 * 心跳快照（hook/StatusSnapshot）只读这里与 DailyStore，**不发任何网络请求**——心跳必须在 QQ 连不上时也照常发。
 */
class RunnerView {
    @Volatile var values: PetValues? = null
    @Volatile var valuesAt: Long = 0
    @Volatile var story: StoryStatus? = null
    @Volatile var storyAt: Long = 0
    /** 当前任务是不是本模块开的（null=不知道/没任务）。 */
    @Volatile var storyMine: Boolean? = null
    /** 不是本模块开的任务的真名（0x9874 详情里读的；读不到为空）。 */
    @Volatile var externalName: String = ""
    /** 0x975f 最新一行叙事（“宠物正在…”）。 */
    @Volatile var narrative: String = ""
    /** 当前在做什么：study/work/adventure/pk/care/idle/busy/not_ready/error。 */
    @Volatile var activity: String = "idle"
    @Volatile var activityText: String = "待命"
    @Volatile var lastDecision: String = ""
    @Volatile var lastError: String = ""
    @Volatile var lastErrorAt: Long = 0
    @Volatile var lastTickAt: Long = 0
    @Volatile var payTired: Boolean = false
    @Volatile var penalizedCount: Int = 0
    @Volatile var mailClaimable: Long = -1
    /** 火花今日已踩 x / 应踩 y（y = 好友名册 − 黑名单）。 */
    @Volatile var sparkProgress: Pair<Int, Int> = 0 to 0
    /** 最近一轮决策读了几个包（目录部分 / 整轮快照）。 */
    @Volatile var catalogPackets: Int = 0
    @Volatile var decisionPackets: Int = 0
    @Volatile var stage: Long = -1
    /** 0x9b62 三属性总量（每小时读一次）。 */
    @Volatile var attrs: Attributes? = null
    @Volatile var attrsAt: Long = 0
    /** 0x9abe 今日经验。 */
    @Volatile var exp: DailyExp? = null
    @Volatile var expAt: Long = 0
    /** 0x994a 最近一次拉到的“雇佣了”通知（RecallGuard 用来确认被雇）。 */
    @Volatile var hiredNotices: List<HiredNotice> = emptyList()
    @Volatile var hiredNoticesAt: Long = 0
    /** 当前任务已确认是被好友雇走的。 */
    @Volatile var hiredNow: Boolean = false
    fun noteValues(v: PetValues) { values = v; valuesAt = System.currentTimeMillis() }

    fun noteStory(st: StoryStatus?, mine: Boolean?) {
        story = st; storyMine = mine; storyAt = System.currentTimeMillis()
        if (st == null || st.remainingSeconds <= 0) { narrative = ""; externalName = "" }
    }

    /**
     * 一趟任务被召回结案：当场把展示切走。[story] 只在每轮 tick 开头重读，结案那一笔不清它，
     * 主页就会继续拿旧趟的开始时间+总时长推倒计时，直到下一轮才被覆盖（code 34 真机实测）。
     * 不默默清：[text] 写明是召回（如「🌦 天色不对，已召回，重新出发」），保留到下一趟派出被正常覆盖为止。
     */
    fun noteRecalled(text: String) {
        story = null; storyMine = null; storyAt = System.currentTimeMillis()
        narrative = ""; externalName = ""; hiredNow = false
        noteActivity("idle", text)
    }

    fun noteActivity(kind: String, text: String) { activity = kind; activityText = text }

    fun noteError(msg: String) { lastError = msg.take(160); lastErrorAt = System.currentTimeMillis() }
}
