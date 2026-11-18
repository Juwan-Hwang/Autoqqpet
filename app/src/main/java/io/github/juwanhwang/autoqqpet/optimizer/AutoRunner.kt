package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.CareManager
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.FriendManager
import io.github.juwanhwang.autoqqpet.pet.FriendPolicy
import io.github.juwanhwang.autoqqpet.pet.HirePolicy
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import io.github.juwanhwang.autoqqpet.pet.SceneEconomics
import io.github.juwanhwang.autoqqpet.pet.SceneOption
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.Step
import io.github.juwanhwang.autoqqpet.pet.StepKind
import io.github.juwanhwang.autoqqpet.pet.service.ItemFamily
import io.github.juwanhwang.autoqqpet.pet.StoryStatus
import io.github.juwanhwang.autoqqpet.pet.TaskManager

/**
 * 自动执行状态机。一次 [tick] 依次走这些**阶段**，每个阶段要么“处理完、排好下一次唤醒、返回”，要么放行到下一阶段：
 *
 * ```
 * READY? ─否→ 60s 后重试
 *   │
 * RECALL   服务器 recallable + 不是我们开的 + 用户开了召回 → 召回 / 排到时机复查
 *   │
 * BUSY     有任务还剩时间 → 排到期（>5min 先排 5min 复查：重判降效、读叙事、做杂事、偶尔 PK）
 *   │
 * SETTLE   到期未结算 → 0x9760 action=1000 领奖 + 0x9874 读明细（计收益/学实收/判疲劳）
 *   │
 * CARE     体力/清洁低于地板 → 喂/洗（连拒 3 次当天绕过）
 *   │
 * CHORES   到点的杂事（信箱/签到/好友代照顾/福袋）
 *   │
 * DECIDE   只冒险 / 排班 / 规划器 三挡之一给出决策 → EXECUTE 开任务并验证 → 排到期
 * ```
 *
 * 不变的铁律（DEV-NOTES）：写命令空回包【绝不重发】、先复查服务器（推断生效）；任何分支必排下一次唤醒（[armed] 兜底）；
 * tick 全局串行（[TICK_LOCK]），撞车**丢弃不排队**并留日志（R2）；永不抢前台（本类不碰界面）。
 *
 * 线程模型：tick 只在调用者线程同步执行（命令执行器的 tick 专用线程），网络调用都是阻塞的 bridge 调用；
 * 心跳线程只读 [view]，不进本类的锁。
 */

/**
 * 开工被服务器拒绝后的重试间隔：同一拒绝码连着被拒时逐步拉长，10 分钟封顶。
 * 2026-10-23 真机：被雇召回后服务器以 135052 连拒约 76 分钟，固定 30 秒一试空转了几十次。
 */
internal fun startRetryDelayMs(streak: Int): Long = when (streak.coerceAtLeast(1)) {
    1 -> 30_000L
    2 -> 60_000L
    3 -> 120_000L
    4 -> 300_000L
    else -> 600_000L
}

class AutoRunner(
    private val service: QQPetService,
    private val daily: DailyStore,
    private val friends: FriendManager?,
    private val policy: FriendPolicy?,
    private val scheduler: Scheduler,
    val config: RunnerConfig,
    private val log: (String) -> Unit,
    val alerts: Alerts = Alerts(),
    val view: RunnerView = RunnerView(),
    /** 读到目录后回传界面（岗位 = runner 维护的结构缓存；课程/冒险 = 本轮读数）。 */
    private val pushCatalog: (List<JobCatalog.Tier>, List<JobCatalog.CourseTier>, List<JobCatalog.AdventureTier>) -> Unit = { _, _, _ -> },
    /** 排班现读（改了下一轮就生效，不依赖重建；真机 2026-10-13 的教训）。 */
    private val liveScheduleJson: () -> String = { config.scheduleJson },
    /** 每 5 分钟向模块 App 补拉一次设置（非阻塞）。 */
    private val pullSettings: () -> Unit = {},
    /** 岗位目录持久缓存（R3：部分职业读失败时按职业回落）。 */
    jobCache: CatalogReader.JobCache = CatalogReader.JobCache.NONE,
    /** 屏幕是否亮着（息屏暂停用；宿主从 QQ 进程侧 PowerManager.isInteractive 读，查不到按亮着算、不误停）。 */
    private val screenInteractive: () -> Boolean = { true },
) {

    /** 同一拒绝码的连续被拒次数：跨 pending 周期保留（清了 pending 也接着数），开工成功或换码才清零。退避按它算。 */
    private var rejectCode = 0
    private var rejectStreak = 0

    companion object {
        /** 冒险金币到顶的经验门槛：当日出过金币后连续这么多趟无金币判到顶（2026-10-16 全天自然最长连段 15 趟，取两倍）。 */
        private const val ADV_NOGOLD_STREAK = 30
        /** 照顾单轮最多连续补几次（一颗饼干/一块香皂只 +10，与 CareManager.careUntil 的 MAX_ACTIONS 同口径）。 */
        private const val CARE_MAX_ACTIONS = 30
        /** 长任务中途复查间隔：用户提前召回后最多晚 5 分钟发现宠物空了（真机 2026-10-11）。 */
        private const val RECHECK_MS = 5 * 60_000L
        /** 长任务中途重判降效的间隔。 */
        private const val PAY_RECHECK_MS = 30 * 60_000L
        /** 全局 tick 锁：改设置重建出的新 runner 也受它管（真机 2026-10-11 并发双计）。 */
        private val TICK_LOCK = java.util.concurrent.locks.ReentrantLock()
        /** 有请求在锁忙时被丢弃过（R2：只留一个标记，由驱动方排一次补跑）。 */
        @Volatile private var droppedWhileBusy = false
        fun consumeDropped(): Boolean = droppedWhileBusy.also { droppedWhileBusy = false }
        private const val KEY_PAY_TIRED_NOTICE = "pay-tired-notice"
        /** 设置页/快照读的“当下是否判疲劳”实时键（每轮覆写，不能与一次性提醒键混用）。 */
        const val KEY_PAY_TIRED_NOW = "pay-tired"
        private const val KEY_FEE_ALERT = "sch-fee-alert"
        /** 短冒险（≤5 分钟）判定。 */
        const val SHORT_ADV_SEC = 300L
        /** 天色不对第一次检查点（条目在出发后约 5 秒出现，多留 1.5 秒余量）；之后的重试节奏见 [WeatherCheckPlan]。 */
        private const val WEATHER_CHECK_AFTER_MS = WeatherCheckPlan.FIRST_AFTER_MS
        /** 写命令未确认后的核对：读几次、间隔多久（D3/D4）。 */
        private const val VERIFY_TRIES = 2
        private const val VERIFY_GAP_MS = 6_000L
        /** 外部任务详情只读一次（storyId → 名称/开始秒）。 */
        private val externalInfo = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
        /** 出门记录对账间隔（B3：今日计数以服务器流水为准补齐）。 */
        private const val HISTORY_SYNC_MS = 30 * 60_000L
        @Volatile private var lastHistorySync = 0L
        /** 短冒险节奏（D5/D6）：进程级，跨 runner 重建保留。 */
        internal val pace = ShortAdventurePace()

        /**
         * 结算结果的处置分界：只有服务器明确拒绝（[PetResult.Rejected]）才算“未结算成功、不许记账”；
         * 回包为空/未确认（[PetResult.Unverified]）不算拒绝，仍走原有明细读取路径确认。
         */
        internal fun settleRejection(r: PetResult<*>?): PetResult.Rejected? = r as? PetResult.Rejected
    }

    /** 每次现建（很便宜）：PK 是否可选要看当日 PK 次数，满了就别再提 PK（否则会每 20s 空转一次“已达上限”）。 */
    private val planner: DayPlanner get() = DayPlanner(
        config.targetAttr,
        studyAttrOrder = if (config.balancedStudy) StudyRotation.orderFrom(daily.studyRotationNext()) else emptyList(),
        config.goldReserve, config.staminaFloor, config.cleanFloor,
        pkEnabled = config.pkOn && friends != null && io.github.juwanhwang.autoqqpet.pet.Caps.allows(policy?.dayCount("pk") ?: 0, config.pkMaxPerDay),
        goldPerAttr = config.goldPerAttr, workFirst = config.workFirst,
        // 冒险时速的分母用最近几天实测的每趟真实耗时（每次现建正好每轮刷新，样本攒够自动生效）
        learnedTripMinutes = daily.adventureTripMinutesByName(),
    )
    private val care = CareManager(service, daily, config.autoBuySupplies, log,
        { alerts.set(Alerts.CARE_BLOCKED, it) }, soapPrice = config.soapPrice)
    private val catalogReader = CatalogReader(service, daily, config, log, jobCache)
    private val recall = RecallGuard(service, daily, config, alerts, view, log)
    private val chores = SideTasks(service, daily, friends, config, view, alerts, log)

    private val schedule: Schedule get() = Schedule.parse(liveScheduleJson().ifBlank { config.scheduleJson })
    private val scheduleActive: Boolean get() = config.scheduleMode && schedule.usable
    private var activeStep: Step? = null

    // ———— 唤醒 ————
    private var armed = false
    private fun arm(delayMs: Long, tag: String) = armAt(System.currentTimeMillis() + delayMs, tag)
    private fun armAt(wallMs: Long, tag: String) {
        armed = true
        // 火花“今晚熄灭”检查的时刻比这次唤醒早 → 唤醒对齐到那个时刻（并进现有调度，不新增驱动源）
        val hint = runCatching { chores.wakeHint() }.getOrDefault(0L)
        if (hint > System.currentTimeMillis() && hint < wallMs) scheduler.scheduleAt(hint, "$tag+spark-tonight")
        else scheduler.scheduleAt(wallMs, tag)
    }

    /**
     * 结案时按「上一件结案时刻 + 任务间隔」精确排一次唤醒（与天色不对检查点同一套 armAt 机制），
     * 唤醒即走决策派发。结案那一笔就钉住时刻，不只靠本轮后段的间隔检查接力——天气召回原来
     * 只排固定 6 秒、还要再等一轮才轮到间隔检查，设 5 秒实际常拖到十几秒（2026-10-19 真机）。
     * 没设间隔 / 还没有结案时刻 → 不排，返回 false，调用方走原有兜底。
     */
    private fun armTaskGapWake(): Boolean {
        val at = TaskGapWake.atMillis(daily.lastTaskEnd(), config.taskGapSec) ?: return false
        armAt(at, "task-gap")
        return true
    }

    // ———— “连不上”判定（R4：只看读请求；整轮读全挂才算坏轮；连续 2 轮才报）————
    private val net = NetHealth(2)
    val networkStaleAt: Long get() = net.staleAt

    /** 最近一次的降效档位（dry-run `plan` 复用，避免两套口径）。 */
    @Volatile var lastPenalizedSubs: Set<Long> = emptySet(); private set
    private var lastPayNotes: List<String> = emptyList()
    private var lastSettingsPull = 0L
    private var lastCatalogPush = 0L
    private var lastPayRecheck = 0L

    fun scheduleStatus(): String = if (!scheduleActive) "" else daily.scheduleStatus(schedule.steps)

    /**
     * @return 本次 tick 简述（格式见 [TickStatus]）。整体兜底：任何分支都必定排下一次唤醒，绝不静默停摆。
     *
     * 并发（R2）：已有一轮在跑就**丢弃本次**并留一个“跑完再补一次”的标记（[consumeDropped]），绝不排队接着跑。
     * 正常路径上 [io.github.juwanhwang.autoqqpet.hook.TickDriver] 的闸门已经挡掉了重复请求，这里是第二道保险。
     */
    fun tick(): String {
        if (!TICK_LOCK.tryLock()) {
            droppedWhileBusy = true
            log("⏭ 并发 tick：已有一轮在跑，本次丢弃（跑完补一次）")
            return TickStatus.skipped("已有一轮在跑")
        }
        try {
            armed = false
            view.lastTickAt = System.currentTimeMillis()
            val io0 = io.github.juwanhwang.autoqqpet.pet.IoStats.snap()
            var netish = false
            val r = try {
                tickInner()
            } catch (t: Throwable) {
                netish = NetHealth.looksNet(t.message)
                view.noteError(t.message ?: t.javaClass.simpleName)
                if (netish) {
                    // 读超时：不是“出错”，是“这一下没读到”。部分读成功（会话活着）→ 30s 后再走；整轮都没读到 → 60s
                    val io = io.github.juwanhwang.autoqqpet.pet.IoStats.snap() - io0
                    val wait = if (io.readOk > 0) 30_000L else 60_000L
                    view.noteActivity("waiting", "读超时，${wait / 1000}s 后重试")
                    log("⏱ 读请求没回来（${t.message?.take(60)}；本轮读成功 ${io.readOk} 次/失败 ${io.readFail} 次），${wait / 1000}s 后重试")
                    arm(wait, "net-retry")
                    TickStatus.netRetry(t.message)
                } else {
                    view.noteActivity("error", "出错，60s 后重试")
                    log("⏱ tick 异常(60s后重试): ${t.message}")
                    arm(60_000, "err")
                    TickStatus.error(t.message)
                }
            }
            val io = io.github.juwanhwang.autoqqpet.pet.IoStats.snap() - io0
            val bad = NetHealth.isBadRound(io.readOk, io.readFail, notReady = r.startsWith("not-ready"))
            if (net.round(bad)) log("📵 连续 ${net.fails} 轮读请求全部没回来，疑似 QQ 会话/网络断了")
            if (io.writeEmpty + io.writeTimeout > 0) log("ℹ️ 本轮写请求空回包 ${io.writeEmpty} 次 / 无回包 ${io.writeTimeout} 次（不计入连不上判定）")
            if (!armed) { log("⚠️ 本次 tick 未排下次唤醒($r)，兜底 60s 后再走一轮"); arm(60_000, "fallback") }
            return r
        } finally {
            TICK_LOCK.unlock()
        }
    }

    /** 上一轮的暂停原因（只在进入/切换暂停时写一条日志，免得整夜每分钟刷一行）。 */
    private var lastPauseText = ""

    private fun tickInner(): String {
        // PAUSE（#11）：息屏/夜间暂停命中时本轮不发任何包——连 READY 的读也不发，只在本地排下一次唤醒。
        // 暂停不丢活：到期结算与断点恢复本来就能吃数小时延迟（到期不领也到账、明细与流水事后可对），
        // 恢复后的第一轮就按 SETTLE/认领路径把暂停期间到期的任务续上。
        val pause = PauseGate.check(config.screenPauseOn, config.nightPauseOn, config.nightPauseStartMin, config.nightPauseEndMin, screenInteractive(), java.time.LocalDateTime.now())
        if (pause != null) {
            view.noteActivity("paused", pause.text)
            if (lastPauseText != pause.text) { log("⏸ ${pause.text}"); lastPauseText = pause.text }
            arm(pause.armMs, "paused")
            return "paused:${pause.text}"
        }
        lastPauseText = ""

        // READY
        val ready = PetResult.read { service.ensurePetId() }
        if (!ready.ok) {
            log("⚠️ 未就绪(读资料失败): ${ready.describe()}")
            view.noteActivity("not_ready", "等待 QQ 就绪"); arm(60_000, "notready"); return "not-ready"
        }

        val story = currentStory()
        // 开工登记丢了的先领回（认领在窗口内、种类对得上就是自家刚开的那份），领回后 isMine 自然为真
        if (story != null && story.remainingSeconds > 0 && !recall.isMine(story)) claimBackIfOurs(story)
        view.noteStory(story, story?.let { recall.isMine(it) })
        if (story != null && story.remainingSeconds > 0 && !recall.isMine(story)) describeExternal(story)

        // RECALL
        when (val o = recall.handle(story)) {
            is RecallGuard.Outcome.Recalled -> {
                // 被雇召回 = 提前结束：按实际进行的时间记一次“完成的打工”
                if (o.ok && story != null) daily.noteDone(story.storyId, "work", story.elapsedSeconds.toLong())
                // 与天气召回同一条展示写回路径：结案当场切走旧趟倒计时，别残留到下一轮
                if (o.ok) view.noteRecalled("🔄 被雇的活已召回，重新出发")
                arm(6_000, "after-recall"); return "recalled:${o.storyId}"
            }
            is RecallGuard.Outcome.WaitUntil -> { armAt(o.atMillis, "recallcheck"); bypassPk(); return "recall-wait:${o.storyId}" }
            RecallGuard.Outcome.None -> Unit
        }

        // BUSY
        if (story != null && story.remainingSeconds > 0) return busy(story)

        // SETTLE
        settlePhase(story)
        syncHistoryIfStale()

        // CARE：低于设定线就一直补到达线再往下走（一颗饼干/一块香皂只 +10，一次补不够）。
        // 单次未生效沿用原路径：30 秒后重试；连续补满 CARE_MAX_ACTIONS 次仍没达线就如实
        // 记一笔、继续干活，不死等（下轮还会再补）。
        var v = service.readValues().also { view.noteValues(it); noteGoldObserved(it.gold.toInt()) }
        // 喂食目标：「饱食不足马上喂」开着时是喂满（100），关着时是体力地板（#23）。洗澡不跟这个开关走。
        val feedTarget = if (config.feedFullOn) 100 else config.staminaFloor
        var careTries = 0
        while (v.hunger < feedTarget && !care.feedBlocked() && careTries < CARE_MAX_ACTIONS) {
            if (!care.feed(v.hunger)) {
                view.noteActivity("care", "喂食未生效"); arm(30_000, "care"); return "喂食未生效，30秒后重试"
            }
            careTries++
            v = service.readValues().also { view.noteValues(it) }
        }
        if (v.hunger < feedTarget && careTries >= CARE_MAX_ACTIONS && !care.feedBlocked())
            log("🍖 连续喂食 $careTries 次后体力仍未达目标（${v.hunger.toInt()} < $feedTarget），先继续干活，下轮再补")
        careTries = 0
        while (v.clean < config.cleanFloor && !care.bathBlocked() && careTries < CARE_MAX_ACTIONS) {
            if (!care.bath(v.clean)) {
                view.noteActivity("care", "洗澡未生效"); arm(30_000, "care"); return "洗澡未生效，30秒后重试"
            }
            careTries++
            val after = service.readValues().also { view.noteValues(it) }
            val rose = after.clean > v.clean
            v = after
            if (!rose) break // 没涨（多半没香皂可用）：不再空转，与 careUntil 同口径
        }
        if (v.clean < config.cleanFloor && careTries >= CARE_MAX_ACTIONS && !care.bathBlocked())
            log("🛁 连续洗澡 $careTries 次后清洁仍未达设定线（${v.clean.toInt()} < ${config.cleanFloor}），先继续干活，下轮再补")
        if (!care.feedBlocked() && !care.bathBlocked()) alerts.clear(Alerts.CARE_BLOCKED)

        // CHORES
        chores.runDue().takeIf { it.isNotEmpty() }?.let { log("🧹 杂事：${it.joinToString("；")}") }

        // 任务间隔先查（2026-10-19）：间隔没歇够就直接睡到歇够再来，不读包不求解。
        // 原来间隔查在 startMain（决策之后），被拦下的轮次把算好的决策整轮扔掉、下一 tick
        // 从读包起全量重算，间隔开着时基本每轮白算一次。护理在前面已做完（补给不等间隔）；
        // startMain 里留同一检查作兜底（口径一致，正常不会再触发）。
        val gapWait = taskGapWaitMs()
        if (gapWait > 0) {
            // 福袋扫描点比间隔先到就先醒来扫一轮（CHORES 在间隔检查之前已跑过当轮的），扫完继续歇
            val bagWaitMs = chores.coinBagWaitMs()
            val wakeMs = minOf(gapWait, bagWaitMs)
            log("⏳ 任务间隔：上一件刚结束，${(wakeMs + 999) / 1000}s 后再开下一件")
            view.noteActivity("waiting", "任务间隔 ${(gapWait + 999) / 1000}s")
            arm(wakeMs, if (bagWaitMs < gapWait) "coinbag" else "task-gap"); return "idle:任务间隔"
        }

        // 喂食 Ban 冷却闸（#15）：冷却没结束、体力又已不高于下限时，不再派耗体力的新活——
        // 旧行为是照常派冒险把体力耗到 0，排班与出发全被服务器挡下成死局。状态文案如实写冷却原因，
        // 唤醒排在冷却结束与 5 分钟复查的先到者，冷却一过就回上面的 CARE 把体力补回来。
        if (care.feedBanCooling() && (if (config.feedFullOn) v.hunger < feedTarget else v.hunger <= config.staminaFloor)) {
            val leftMs = care.feedBanRemainingMs()
            val why = "喂食被服务端临时禁止（还剩约 ${(leftMs + 59_999) / 60_000} 分钟冷却），体力 ${v.hunger.toInt()} 未达喂食目标 $feedTarget，先不派新活，等冷却结束重试喂食"
            log("⏸ $why")
            view.noteActivity("care", why)
            arm(minOf(leftMs, 5 * 60_000L).coerceAtLeast(5_000L), "care-ban")
            return "care-ban"
        }

        // DECIDE + EXECUTE
        pullSettingsIfStale()
        v = service.readValues().also { view.noteValues(it) }
        val snap = buildSnapshot(v)
        val d = decide(snap)
        return execute(d, snap)
    }

    // ———————————————— 阶段实现 ————————————————

    private fun currentStory(): StoryStatus? =
        PetResult.read { service.story.status() }.getOrNull()?.takeIf { it.storyId.isNotEmpty() || it.remainingSeconds > 0 }
            ?.let { StoryStatus.fillStoryId(it, daily.pendingId(), daily.pendingKind()) }

    private fun busy(story: StoryStatus): String {
        val waitMs = story.remainingSeconds * 1000L + config.settleBufferMs
        if (daily.pendingId().isEmpty()) daily.setPending(story.storyId, kindTag(story),
            TaskClock.endAt(story.startedAt, story.durationSeconds, System.currentTimeMillis(), story.remainingSeconds), (story.remainingSeconds / 60).toInt(),
            durationSec = story.durationSeconds, name = externalInfo[story.storyId]?.first.orEmpty())
        val label = daily.pendingName().ifBlank { externalInfo[story.storyId]?.first.orEmpty() }.ifBlank { story.kindPrefix }
        view.noteActivity(kindTag(story).ifBlank { "busy" }, "进行中「$label」剩 ${story.remainingSeconds}s")
        // 天色不对检查（模块派出的冒险，查到有结论为止）：到点且命中就当场召回结案，本轮到此为止
        weatherRecallClose(story)?.let { return it }
        if (config.progressOn) PetResult.read { service.story.progress(story.storyId) }.getOrNull()?.latest?.takeIf { it.isNotBlank() }?.let { view.narrative = io.github.juwanhwang.autoqqpet.pet.parse.RichText.truncate(it, 80) }
        // 鼓励补发（v2.1.2）：本模块开的学/工任务、次数没发满、间隔已到 → 补一次；
        // 还没到间隔就把下一次唤醒提前到可发时刻，不然最短也要等 5 分钟复查，短任务根本等不到补发
        val nowMs = System.currentTimeMillis()
        val encApplies = daily.pendingId() == story.storyId
        if (encApplies && EncouragePlan.due(daily.pendingEncourageTarget(), daily.pendingEncourageDone(), daily.pendingEncourageAt(), nowMs)) encourageOnce(story.storyId)
        val encNextAt = if (encApplies) EncouragePlan.nextAt(daily.pendingEncourageTarget(), daily.pendingEncourageDone(), daily.pendingEncourageAt()) else Long.MAX_VALUE
        val encWaitMs = if (encNextAt == Long.MAX_VALUE) Long.MAX_VALUE else (encNextAt - nowMs).coerceAtLeast(1_000L)
        val recheck = waitMs > RECHECK_MS
        val weatherWaitMs = weatherCheckWaitMs(story, nowMs)
        // 福袋扫描到点（v2.1.6）：不等复查/结算，这一轮就扫（杂事各自按间隔把关，重复调用无害）；
        // 扫完再算下一次，把唤醒提前到扫描点——与鼓励/天色同一套“提前唤醒办点事”的口径
        if (chores.coinBagWaitMs(nowMs) <= 0L)
            chores.runDue().takeIf { it.isNotEmpty() }?.let { log("🧹 杂事：${it.joinToString("；")}") }
        val bagWaitMs = chores.coinBagWaitMs(nowMs)
        val armInMs = minOf(if (recheck) RECHECK_MS else waitMs, encWaitMs, weatherWaitMs, bagWaitMs)
        armAt(nowMs + armInMs, when { weatherWaitMs != Long.MAX_VALUE && armInMs == weatherWaitMs -> "weather-check"; encWaitMs != Long.MAX_VALUE && armInMs == encWaitMs -> "encourage"; bagWaitMs != Long.MAX_VALUE && armInMs == bagWaitMs -> "coinbag"; recheck -> "recheck"; else -> "settle" })
        if (recheck) {
            // 复查顺手：重判降效（长任务期间降效也能实时发现）、读数值进收益曲线、做到点的杂事
            // 复查降效：30 分钟一次就够（旧版每 5 分钟全量读 ≤7 包）；目录结构走缓存，只读课程 + 一个职业
            if (daily.pendingKind() != "adventure" && System.currentTimeMillis() - lastPayRecheck >= PAY_RECHECK_MS) {
                lastPayRecheck = System.currentTimeMillis()
                runCatching { applyPay(catalogReader.read().let { assessPay(it.courses, it.jobs) }) }
            }
            PetResult.read { service.readValues() }.getOrNull()?.let { view.noteValues(it); noteGoldObserved(it.gold.toInt()) }
            chores.runDue().takeIf { it.isNotEmpty() }?.let { log("🧹 杂事：${it.joinToString("；")}") }
            // 顺带 PK 必须在复查分支里也掷骰子：旧版只在“剩 ≤5 分钟”的收尾分支调，长任务全程
            // （每 5 分钟一次复查）都走不到 → 一整天只打出 1 场（2026-10-16 真机：pk=1，上限 10）。
            bypassPk()
            return when {
                weatherWaitMs != Long.MAX_VALUE && armInMs == weatherWaitMs ->
                    "busy:${story.storyId} 剩${story.remainingSeconds}s（${armInMs / 1000}s 后查天色）"
                encWaitMs != Long.MAX_VALUE && armInMs == encWaitMs ->
                    "busy:${story.storyId} 剩${story.remainingSeconds}s（${armInMs / 1000}s 后补鼓励）"
                bagWaitMs != Long.MAX_VALUE && armInMs == bagWaitMs ->
                    "busy:${story.storyId} 剩${story.remainingSeconds}s（${armInMs / 1000}s 后扫福袋）"
                else -> "busy:${story.storyId} 剩${story.remainingSeconds}s（${RECHECK_MS / 60_000}min 后复查）"
            }
        }
        bypassPk()
        return "busy:${story.storyId} 剩${story.remainingSeconds}s"
    }

    /** 离下一次天色检查还差多少毫秒；这一趟不查（开关关 / 没挂记号也接不上手 / 已判完）返回 [Long.MAX_VALUE]。 */
    private fun weatherCheckWaitMs(story: StoryStatus, nowMs: Long): Long {
        if (!config.weatherRecallOn) return Long.MAX_VALUE
        if (daily.weatherCheckFor() == story.storyId) {
            val next = daily.weatherCheckNextAt()
            if (next > 0) return (next - nowMs).coerceAtLeast(0L)
            // 旧版记号没排时刻：按出发时刻算第一次（与旧版同一口径）
            if (story.startedAt <= 0) return 0L   // 开始时刻读不到：记号还挂着就是还没查过，第一个 tick 就查
            return (story.startedAt * 1000 + WEATHER_CHECK_AFTER_MS - nowMs).coerceAtLeast(0L)
        }
        // 没挂记号：能接手（自家派出、没判过、剩余够）就马上查，接不上就不查
        return if (WeatherCheckPlan.shouldAdopt(daily.weatherCheckFor(), daily.weatherCheckDoneFor(),
                daily.pendingId(), daily.pendingKind(), daily.pendingDispatchedAt(), story.storyId, story.remainingSeconds)) 0L
        else Long.MAX_VALUE
    }

    /**
     * 天色不对检查：到点读进度（0x975f），条目里有「天色不对」就立刻召回这一趟。
     * 查到有结论为止（节奏与上限见 [WeatherCheckPlan]）：没读到可用结果（没回包/条目为空）不摘记号，
     * 隔几秒再查，试满才放弃；读到条目且没命中 = 结论；命中后召回写失败同样重试。
     * 派出时没挂上记号的自家冒险（旧版派出、重启后领回等）在这里接手补挂，手动/外部故事不接。
     * 召回成功按普通结算同一套记账结案（趟数在派出时已计、这里记完成），金币如实记 0，
     * 与普通结算一样计入连续无金币连段（理由见 [noteAdventureGold]）。
     * @return 召回成功结案时返回本轮状态串（调用方直接收尾）；不适用 / 未到点 / 没命中 / 还要重试返回 null，继续原 busy 流程。
     */
    private fun weatherRecallClose(story: StoryStatus): String? {
        if (!config.weatherRecallOn) return null
        if (daily.weatherCheckFor() != story.storyId) {
            if (!WeatherCheckPlan.shouldAdopt(daily.weatherCheckFor(), daily.weatherCheckDoneFor(),
                    daily.pendingId(), daily.pendingKind(), daily.pendingDispatchedAt(), story.storyId, story.remainingSeconds)) return null
            daily.armWeatherCheck(story.storyId, System.currentTimeMillis())
        }
        if (weatherCheckWaitMs(story, System.currentTimeMillis()) > 0) return null
        val name = daily.pendingName()
        val prog = PetResult.read { service.story.progress(story.storyId) }.getOrNull()
        if (WeatherCheckPlan.inconclusive(prog?.lines)) {
            val tries = daily.weatherCheckTries()
            val next = WeatherCheckPlan.nextAfterReadFail(tries, System.currentTimeMillis())
            if (next == null) {
                log("🌦 「$name」天色检查连着 ${tries + 1} 次没读到进度，这趟不再查，照常跑完")
                daily.finishWeatherCheck(story.storyId)
            } else {
                if (tries == 0) log("🌦 「$name」天色检查没读到进度，过几秒再查")
                daily.noteWeatherReadTry(next)
            }
            return null
        }
        val p = prog!!   // inconclusive 已挡住没回包/条目为空，这里必有进度
        if (!p.weatherWarned) { daily.finishWeatherCheck(story.storyId); return null }
        val r = PetResult.write { service.story.recall(story.storyId) }
        val o = r.getOrNull()
        if (!r.ok || o == null) {
            val tries = daily.weatherCheckRecallTries()
            val next = WeatherCheckPlan.nextAfterRecallFail(tries, System.currentTimeMillis())
            if (next == null) {
                log("🌦 「$name」天色不对，但召回连着 ${tries + 1} 次没成功（${r.describe()}），这趟照常跑完再结算")
                daily.finishWeatherCheck(story.storyId)
            } else {
                log("🌦 「$name」天色不对，召回没成功（${r.describe()}），过几秒再试")
                daily.noteWeatherRecallTry(next)
            }
            return null
        }
        daily.finishWeatherCheck(story.storyId)
        log(if (o.gold > 0) "🌦 「$name」刚出发就天色不对，已召回（还带回 ${o.gold} 金币）"
            else "🌦 「$name」刚出发就天色不对，这趟不会有金币——已召回，马上重开")
        // 结构化结算记录与普通结算同形，日志对账口径一致
        log("🧾 结算记录 id=${story.storyId} kind=adventure name=\"${o.name}\" gold=${o.gold} rewards=" + o.rewards.joinToString(",") { "${it.name}:${it.amount}" })
        if (o.rewards.isNotEmpty()) {
            daily.attachRewards(story.storyId, o.rewards)   // 统一账本：记到这趟的台账行上
            log("🧾 实收：" + o.rewards.joinToString(" ") { "${it.name}+${it.amount}" })
        }
        noteAdventureGold(o.gold, daily.pendingDuration(), o.rewards.filter { it.name.contains("工分") }.sumOf { it.amount })
        // 被召回的短趟：墙钟照记，完成数不记（口径见 noteAdventurePace）；只记登记中的这一趟
        if (story.storyId == daily.pendingId()) noteAdventurePace(completed = false)
        daily.noteDone(story.storyId, "adventure", story.elapsedSeconds.toLong())
        daily.markSettled(story.storyId)
        daily.noteTaskEnd()   // 与普通结算同一口径：任务间隔从这一刻起算
        daily.clearPending()
        // 主页展示当场切走并写明是召回：不清 view.story 的话，旧趟倒计时会残留到下一轮才被覆盖
        view.noteRecalled("🌦 天色不对，已召回，重新出发")
        // 重开走任务间隔的精确唤醒（结案时刻 + 设定间隔）；没设间隔才用原来的固定 6 秒
        if (!armTaskGapWake()) arm(6_000, "after-weather-recall")
        return "weather-recalled:${story.storyId}"
    }

    private fun kindTag(st: StoryStatus): String = when (st.kindPrefix) {
        "6100" -> "study"; "6400", "6500" -> "work"; "6700" -> "adventure"; "6900" -> "pk"; else -> ""
    }

    /**
     * 到期结算 + 结算明细。三个样本证明到期不发 1000 也到账（交接 §9.13）→ 发不发由 `settle_claim_on` 决定（默认发，像人在页面）。
     * 1000 的回包与 0x9874 同形：**回包里已有奖励就不再读 0x9874**（省一包）。同一 storyId 只处理一次（markSettled）。
     */
    private fun settlePhase(story: StoryStatus?) {
        val pendingId = daily.pendingId()
        val target = when {
            story != null && story.storyId.isNotEmpty() && !daily.isSettled(story.storyId) -> story.storyId
            pendingId.isNotEmpty() && !daily.isSettled(pendingId) -> pendingId
            else -> null
        }
        if (target == null) {
            if (pendingId.isNotEmpty()) daily.clearPending()
            return
        }
        var pre: io.github.juwanhwang.autoqqpet.pet.StoryOutcome? = null
        var rejected: PetResult.Rejected? = null
        if (story?.storyId == target && config.settleClaimOn) {
            val r = PetResult.write { service.story.settle(target) }
            pre = r.getOrNull()
            rejected = settleRejection(r)
            log("🧾 结算 $target → ${pre?.takeIf { !it.empty }?.let { "「${it.name}」" + it.rewards.joinToString(" ") { w -> "${w.name}+${w.amount}" } } ?: r.describe()}")
        }
        if (rejected != null) {
            // 服务器明确拒绝（如 135004 结算条件不满足）= 这趟根本没结算成功：不读明细、不记实收、
            // 不写完成。继续读 0x9874 明细只会从未结算的故事页上误捞出假奖励
            // （2026-10-19 真机：被拒后仍写出「结算记录 name="" gold=1 rewards=金币:1」幻影记录，还连写两遍）。
            // 登记按“已结束但未结算成功”收掉：标记已处理 + 清登记，免得下一 tick 拿同一故事再试一遍。
            // 回包为空/未验证（Unverified）不算拒绝，不走这里，仍按原有路径读明细确认。
            log("🧾 结算被拒 code=${rejected.code} ${rejected.message}（$target）：未结算成功，不记实收、不计完成")
            // 耗时口径与奖励口径分开：奖励不记，但这趟实打实占掉的墙钟要记（不记完成数）
            if (target == pendingId && daily.pendingKind() == "adventure") noteAdventurePace(completed = false)
            daily.markLedgerRejected(target)   // 统一账本：台账行打「被拒」标，完成数与步进度从此不认它（旧版要靠各计数点自觉不记，漏一处就虚增）
            daily.markSettled(target)
            daily.noteTaskEnd()
            armTaskGapWake()   // 这趟已结案：按任务间隔精确排下一件的唤醒
            if (pendingId.isEmpty() || target == pendingId || daily.pendingEnd() <= System.currentTimeMillis()) daily.clearPending()
            else log("🧾 本次被拒的是 $target，登记中的 $pendingId 未到期，保留登记")
            return
        }
        if (pre?.hiredSide == true) daily.increment("hired_settled")
        pre?.let { o -> io.github.juwanhwang.autoqqpet.pet.FatigueText.detect(o.notice, o.title)?.let { noteTiredText(if (target == pendingId) daily.pendingSub() else 0L, it, "结算回包") } }
        // 种类：登记的 > 状态读到的 > storyId 前缀（6100/6400/6700）。以前三者都缺时“今天”会漏记（B3：今日学习只记到 1 次）
        // 提前算：readSettlement 也要按真实种类记冒险金币累计（它单靠 pendingKind 认延迟结算会认错趟）。
        val tag = (if (target == pendingId && daily.pendingKind().isNotEmpty()) daily.pendingKind() else story?.takeIf { it.storyId == target }?.let { kindTag(it) }.orEmpty())
            .ifEmpty { io.github.juwanhwang.autoqqpet.pet.OutingKind.ofStoryId(target).tag.takeIf { it != "pk" }.orEmpty() }
        if (config.settleViewOn) readSettlement(target, pre, tag)
        // “今天”统计：到期结算 = 完成，时长按任务总时长（同一 storyId 只记一次）
        val sec = story?.takeIf { it.storyId == target && it.durationSeconds > 0 }?.durationSeconds
            ?: if (target == pendingId) daily.pendingDurationSec() else 0L
        // 昨天就已结束的旧任务不算今天的完成（2026-10-16 幻影记录的另一扇门）：状态会把“最近一趟”
        // 一直挂着，跨天后 settled/done 去重集已清，旧 id 会被当成今天刚完成的记进 done 计数。
        // 只拦“不是本模块在等的、且按它自己的开始+时长在今天 0 点前就已结束”的；
        // 跨午夜完成的在等任务（target == pendingId）不在此列，照常计入。
        val stale = target != pendingId && story?.takeIf { it.storyId == target }?.let { st ->
            st.startedAt > 0 && st.durationSeconds > 0 &&
                (st.startedAt + st.durationSeconds) * 1000 < java.time.LocalDate.ofEpochDay(io.github.juwanhwang.autoqqpet.pet.Day.today().toLong())
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } == true
        if (stale) log("🧾 $target 在今天开始前就已结束（旧任务），不计入今天完成数")
        else daily.noteDone(target, tag.ifEmpty { if (pre?.hiredSide == true) "work" else "" }, sec)   // 种类认不出就不记
        // 从没进过账的一趟（开工未确认漏登记/外部开的）：趁结算当口用真实开始时间补进台账，
        // 免得台账缺行、疲劳少算，事后再被对账补记借时间（2026-10-16 幻影案的源头链）。
        // 统一账本后补行即补齐所有口径；这趟若正是排班步派的（未确认开工对得上），归属一并写进行里。
        if (!stale) {
            story?.takeIf { it.storyId == target }?.let { st ->
                if (st.startedAt > 0 && sec > 0) {
                    val intent = daily.unconfirmedIntent()
                    val startMs = st.startedAt * 1000
                    val intentStep = if (intent != null && intent.tag == tag &&
                        startMs >= intent.sentAtMs - 120_000 && startMs <= intent.sentAtMs + 15 * 60_000
                    ) intent.stepId else ""
                    if (!daily.inLedger(target)) {
                        if (daily.noteSettledUncounted(target, tag, startMs, sec, intentStep)) {
                            log("🧾 $target 此前未登记，已按真实开始时间补进台账（结算补记）")
                            if (intentStep.isNotEmpty()) log("🧾 $target 与此前未确认的开工对得上，排班归属已一并补记（结算补记）")
                        }
                    } else if (intentStep.isNotEmpty() && daily.retagLedgerStep(target, intentStep)) {
                        log("🧾 $target 与此前未确认的开工对得上，排班归属已补记（结算补记）")
                    }
                    if (intent != null && intent.tag == tag) daily.clearUnconfirmedIntent()
                }
            }
        }
        // 本模块派出的冒险跑完并结算：整趟墙钟记完成一笔（求解器实测耗时的样本）
        if (tag == "adventure" && target == pendingId && !stale) noteAdventurePace(completed = true)
        // 排班冒险步的分钟：台账行的时长已由 noteDone 按任务总时长落账、stepProgress 按「结」行现算，
        // 不再单独补记（统一账本 2.1.13；召回趟走天气/被雇路径，那边 noteDone 传的是实际进行秒数）。
        daily.markSettled(target)
        maybeWatchSettleAd(target, tag, stale)
        daily.noteTaskEnd()   // 任务间隔的起算点（上一件任务到此为止）
        armTaskGapWake()   // 结案这一笔就按「结案时刻 + 设定间隔」钉住下一次唤醒，不等周期 tick
        // 只清“就是这一趟”的登记：结算的若是另一趟（延迟结算），别把还在跑的登记任务一起清掉
        // （2026-10-16 01:51 实测：延迟结算后登记被清，下一轮误判空闲又去开工、被 135054 挡回）。
        if (pendingId.isEmpty() || target == pendingId || daily.pendingEnd() <= System.currentTimeMillis()) daily.clearPending()
        else log("🧾 本次结算的是 $target，登记中的 $pendingId 未到期，保留登记")
    }

    /**
     * 结算页「看视频 +N 金币」（rewardType 60）：只在打工结算、开关打开、非旧任务时尝试一次。
     * 广告奖励领取失败只记日志，不影响结算与后续调度。
     */
    private fun maybeWatchSettleAd(storyId: String, tag: String, stale: Boolean) {
        if (!config.adCoinOn || stale || tag != "work") return
        val petId = runCatching { service.ensurePetId() }.getOrDefault("")
        if (petId.isEmpty()) { log("📺 结算广告：还没读到 petId，跳过"); return }
        val r = service.rewardAd.watchStoryCoin(petId, storyId, config.adBackgroundClaimOn)
        if (r.ok) log("📺 结算广告：已领取（$storyId）")
        else log("📺 结算广告：${r.message}（$storyId）")
    }

    /** 结算明细（1000 回包优先，没有再读 0x9874）→ 今日实收、按档位学习实收金币、实收低于预期判降效。 */
    private fun readSettlement(storyId: String, pre: io.github.juwanhwang.autoqqpet.pet.StoryOutcome? = null, kindHint: String = "") {
        val kind = kindHint.ifEmpty { if (storyId == daily.pendingId()) daily.pendingKind() else "" }
        val sub = if (storyId == daily.pendingId()) daily.pendingSub() else 0L
        val expected = if (storyId == daily.pendingId()) daily.pendingExpected() else 0
        val s = if (pre != null && pre.rewards.isNotEmpty()) io.github.juwanhwang.autoqqpet.pet.Settlement(storyId, emptyList(), pre.rewards)
        else PetResult.read { service.story.settlementView(storyId, System.currentTimeMillis()) }.getOrNull() ?: return
        io.github.juwanhwang.autoqqpet.pet.FatigueText.detect(*s.narrative.toTypedArray())?.let { noteTiredText(sub, it, "结算叙事") }
        // 结构化结算记录：一行一趟、自带 id/种类/金币，事后解析不用再靠上下文给“实收”行配对（2026-10-17 分析全天日志时的教训）
        log("🧾 结算记录 id=$storyId kind=${kind.ifEmpty { "?" }} name=\"${pre?.name ?: ""}\" gold=${s.gold} rewards=" + s.rewards.joinToString(",") { "${it.name}:${it.amount}" })
        if (s.rewards.isEmpty()) { log("🧾 结算明细未认出奖励条目（${s.narrative.size} 行叙事）"); return }
        daily.attachRewards(storyId, s.rewards)   // 统一账本：实收记到这趟自己的台账行上（行未落账先缓冲）
        log("🧾 实收：" + s.rewards.joinToString(" ") { "${it.name}+${it.amount}" })
        if (kind == "work" && sub > 0) {
            // 另按「职业+时长」记一份（w2c/hc 键）：岗位编号会随职业升级漂移、名字也可能变，
            // 职业与时长是固定结构——定价以这份账为准（见 CatalogReader.withLearned），编号账留作兜底。
            // 职业/时长缺失（接手任务等）时只写编号账，不写残缺键。
            val dur = daily.pendingDuration()
            val career = daily.pendingCareer()
            if (pre?.employerSide == true) {
                // 雇佣工单独记账（h 键）：雇好友的宠物替工，工钱与本职业目录价是两套数，
                // 混进同一均值两边都失真。雇佣工资实测近似固定、不随本宠疲劳档走，按实收原值记。
                daily.learnReward("h$sub", s.gold, s.points)
                if (dur > 0 && career > 0) daily.learnReward("hc${career}t$dur", s.gold, s.points)
            } else {
                // 自做工按满档还原后入账（w2 键）：实收被疲劳档折过（低至一折），原值记进均值
                // 会把满档收益系统性低估，求解时还要再乘一次档位折扣，等于折两道。旧 w 键是
                // 折后原值的混合均值、口径已污染，自 2.1.4 起不再读写。
                val full = OptimalPlanner.fullTierEquivalent(s.gold, daily.fatigueMinutes() - dur, dur)
                daily.learnReward("w2$sub", full, s.points)
                if (dur > 0 && career > 0) daily.learnReward("w2c${career}t$dur", full, s.points)
            }
        }
        if (kind == "adventure") noteAdventureGold(s.gold, daily.pendingDuration(), s.points)
        // 实收只用于校准/学习（learnReward），不再据此剔档
        PayMonitor.settlementDrop(kind, expected, s)?.let { log("🧾 档位 #$sub $it（仅记录，决策按当前目录报酬）") }
    }

    /**
     * 冒险真实耗时记账（求解器修准用，聚合与口径在 [io.github.juwanhwang.autoqqpet.pet.AdvPace]）：
     * 本模块派出的冒险结案时，按登记里的派发时刻算整趟墙钟记一笔。
     * [completed] 只认「跑完并结算」；被天色不对召回、结算被拒收尾的趟只记耗时不记完成——
     * 它们实打实吃掉了墙钟，求解器算「每完成一趟的真实耗时」时要把这段时间摊进去。
     * 登记没有派发时刻的（接手/外部任务）不记：起算点不准，宁可少样本不要假样本。
     * 与金币口径互不干扰：到顶连段与 learnReward 的记账都在 [noteAdventureGold] 那边，本函数不碰。
     */
    private fun noteAdventurePace(completed: Boolean) {
        val at = daily.pendingDispatchedAt()
        if (at <= 0) return
        val elapsed = System.currentTimeMillis() - at
        if (elapsed <= 0) return
        daily.noteAdventureTrip(daily.pendingName(), elapsed, completed)
    }

    /**
     * 冒险金币日上限的经验判定（2026-10-16 全天日志实测：金币来自「偶遇/捡到」两类事件，
     * 16:01 累计 5375 后这两类事件整体消失、只剩天气事件，服务器不给任何到顶文案，所以只能靠数据判）。
     * 累计与连段记在 counts（adv_gold / adv_nogold_streak，随跨天清零）；判到顶后打 [DailyStore.noteAdvGoldCapped]，
     * 自动/只冒险模式当天不再派冒险（排班不读这个标记，照常按用户的计划走）。
     * 判到顶后不再喂 learnReward；判定窗口内已喂的连段 0 样本在判定当刻回滚，免得拉低收益估算。
     * 天色不对召回的趟同样计入连段、同样喂 learnReward：实测规律是没出金币有且仅有天气不好
     * 这一种情况（没有天气提示的趟必有金币），天气趟正是奖池枯竭的唯一证据；门槛 30 也是按
     * 2026-10-16 全天含天气趟的最长自然连段（15 趟）取两倍定的。召回功能上线时曾把天气趟
     * 排除在连段外，到顶判定从此永远攒不满、事实上失效（2026-10-29 复盘恢复原设计）。
     */
    private fun noteAdventureGold(gold: Long, durMin: Int, points: Long) {
        if (gold > 0) {
            daily.setCount("adv_gold", daily.count("adv_gold") + gold.toInt())
            daily.setCount("adv_nogold_streak", 0)
        } else {
            daily.setCount("adv_nogold_streak", daily.count("adv_nogold_streak") + 1)
        }
        val streak = daily.count("adv_nogold_streak")
        if (!daily.advGoldCapped() && daily.count("adv_gold") > 0 && streak >= ADV_NOGOLD_STREAK) {
            daily.noteAdvGoldCapped()
            daily.noteAdvCapObserved(daily.count("adv_gold"))   // 池深标定：求解器按最近一次观测估余量
            daily.rollbackAdventureZeros(durMin, streak)       // 连段这批 0 样本是到顶后喂的，从学习均值里回滚
            log("🪙 今日冒险金币已达上限（累计约 ${daily.count("adv_gold")}，连续 $streak 趟无金币），今天不再派冒险")
        }
        // 天气趟的 0 样本照常喂收益学习：真到顶时判定那一刻会把连段这批 0 样本回滚掉，不拉低估值
        // 收益学习按时长记账（a<时长分钟>）：冒险名可能随版本/等级变化，时长是固定结构
        if (!daily.advGoldCapped()) daily.learnReward("a$durMin", gold, points)
    }

    /** 记一笔余额观测；若判出非模块支出（用户在官方界面手动花钱），基线已在 DailyStore 里等额下调，这里留一行日志说明。 */
    private fun noteGoldObserved(gold: Int) {
        val unexplained = daily.noteGold(gold)
        if (unexplained > 0) log("📉 检测到非模块支出 $unexplained 金币，今日金币基线已下调至 ${daily.goldStart()}")
    }

    /** 本轮学习的目标属性：选了「均衡」时按轮转指针与本轮可用课程定（见 [StudyRotation]），否则就是设置里的目标属性。 */
    private fun studyTargetAttr(snap: Snapshot): Attr =
        if (config.balancedStudy) StudyRotation.resolve(daily.studyRotationNext(), snap.courses.filter { it.canDo }.map { it.attr }.toSet())
        else config.targetAttr

    /** 最优调度（adv_mode=auto）：把实时快照喂给求解器，只执行最优计划的第一步；求解异常时退回旧逻辑。 */
    private fun solverDecision(snap: Snapshot): Decision {
        val input = solverInput(snap)
        // 金币优先·冒险先行（2026-10-31 用户定）：冒险金币池是当天的、过期作废，打工随时能做——
        // 池子没见底（只认连续 30 趟无金币的到顶判定，估算池深不算）之前先派冒险，见底后整天
        // 回到求解器比价。挑哪趟冒险用求解器同一套数（期望金币/真实周期＝时薪最高者）。
        if (config.autoGoal == AutoGoal.GOLD && !snap.advGoldCapped) {
            val adv = input.adventures.maxByOrNull { it.evGold / it.cycleMin }
            if (adv != null) {
                if (snap.stamina < adv.staminaCost)
                    return Decision(Kind.FEED, "biscuit", "最优调度[金币优先]：冒险先行，先补体力（${snap.stamina} < 需 ${adv.staminaCost}）", cause = "最优·先照顾")
                if (snap.clean < adv.cleanCost)
                    return Decision(Kind.BATH, "soap", "最优调度[金币优先]：冒险先行，先补清洁（${snap.clean} < 需 ${adv.cleanCost}）", cause = "最优·先照顾")
                return Decision(Kind.ADVENTURE, adv.name, "最优调度[金币优先]：冒险先行（池子未见底，先派「${adv.name}」），见底后转打工", sub = adv.sub, cause = "最优·金币优先·冒险先行")
            }
        }
        val plan = runCatching { OptimalPlanner.solve(input) }
            .onFailure { log("🧮 最优调度求解失败，退回旧逻辑：${it.message}") }
            .getOrNull()
        val first = plan?.first ?: return if (plan == null) planner.decide(snap)
            else Decision(Kind.IDLE, "", "最优调度：今天没有划算的可行安排", cause = "最优·无安排")
        val goalCn = when (config.autoGoal) { AutoGoal.GOLD -> "金币优先"; AutoGoal.POINTS -> "工分优先"; AutoGoal.ATTR -> "属性优先" }
        val kind = when (first.kind) {
            OptimalPlanner.ActKind.STUDY -> Kind.STUDY
            OptimalPlanner.ActKind.WORK -> Kind.WORK
            OptimalPlanner.ActKind.ADVENTURE -> Kind.ADVENTURE
            OptimalPlanner.ActKind.FEED -> Kind.FEED
            OptimalPlanner.ActKind.BATH -> Kind.BATH
        }
        // 求解器按“即时补给”建模（计划里补给已折进各动作）；真开工时体力/清洁不够，先走一趟照顾，下一轮重解接着排
        val costs = when (first.kind) {
            OptimalPlanner.ActKind.STUDY -> snap.courses.firstOrNull { it.subEventType == first.sub }?.let { it.staminaCost to it.cleanCost }
            OptimalPlanner.ActKind.WORK -> snap.jobs.firstOrNull { it.subEventType == first.sub }?.let { it.staminaCost to it.cleanCost }
            OptimalPlanner.ActKind.ADVENTURE -> snap.adventures.firstOrNull { it.subEventType == first.sub }?.let { it.staminaCost to it.cleanCost }
            else -> null
        }
        if (costs != null && snap.stamina < costs.first)
            return Decision(Kind.FEED, "biscuit", "最优调度[$goalCn]：先补体力（${snap.stamina} < 需 ${costs.first}）再开工", cause = "最优·先照顾")
        if (costs != null && snap.clean < costs.second)
            return Decision(Kind.BATH, "soap", "最优调度[$goalCn]：先补清洁（${snap.clean} < 需 ${costs.second}）再开工", cause = "最优·先照顾")
        return Decision(kind, first.name, "最优调度[$goalCn]：${plan.summary}", sub = first.sub, cause = "最优·$goalCn")
    }

    /**
     * 求解器输入：全部来自实时目录/快照/结算学习（不定死任何单趟产出）。
     * 口径：目录与学习值按“满档基准”喂入，疲劳由求解器按官方三档往后折算；
     * 冒险期望来自结算学习（a<名字>），没样本又没目录价的冒险不进计划（没数据不猜）。
     * 池深：最近一次到顶观测（advCapObserved），没观测过用 2026-10-16 实测 5375 作先验，触发一次就换真值；
     * 估算只用来收敛规划，未触发到顶判定前剩余池留试探余量、不归零停派（见下方 poolLeft）。
     */
    private fun solverInput(snap: Snapshot): OptimalPlanner.Input {
        val lt = java.time.LocalTime.now()
        val capEst = daily.advCapObserved().takeIf { it > 0 } ?: 5375
        val advs = snap.adventures.filter { it.canDo }.mapNotNull { a ->
            // 期望来源：今天活池样本的学习均值（为正才用，≤0 按未判——见 DailyStore.adventureEvGold）
            // → 昨天活池实收（种子）→ 目录值；都没有就不进计划
            val ev = daily.adventureEvGold(a.durationMin) ?: if (a.incomeKnown) a.income.toDouble() else null
            ev?.let {
                OptimalPlanner.AdventureIn(
                    a.name, a.subEventType, it,
                    // 一趟的周期同样优先用实测真实耗时（它本身已含间隔/等待/召回摊销，不再另加任务间隔）；没样本回退名义时长+间隔
                    cycleMin = daily.adventureTripMinutes(a.name) ?: ((a.durationSec + config.taskGapSec) / 60.0),
                    staminaCost = a.staminaCost, cleanCost = a.cleanCost,
                )
            }
        }
        // 剩余池：池深是估算（实测到顶值或 2026-10-16 先验），估算减到零不等于真到顶——
        // 真到顶只认「连续 30 趟无金币」的连段判定（见 noteAdventureGold）。未触发判定前
        // 永远留约一批（ADV_BATCH 趟）的期望金币作试探余量，让趟继续跑、连段继续攒；
        // 判定一触发，advGoldCapped 接管归零，并记下当日真实池深替换估算。
        val probeFloor = (advs.maxOfOrNull { it.evGold } ?: 0.0) * OptimalPlanner.ADV_BATCH
        val poolLeft = if (daily.advGoldCapped()) 0.0
            else maxOf((capEst - daily.count("adv_gold")).toDouble(), probeFloor)
        // 雇佣定价：自动雇佣开着、今日雇佣次数没到上限时，派单层会给打工带上雇佣（hirePlan
        // 判定的近似，好友当下可不可雇是运行时状态、不进模型）——此时岗位按雇佣价定价
        // （见下方 hireGold）；次数到上限后恢复自做价。雇人不花钱，不看金币留底。
        val hirePriced = config.autoHireFriend &&
            (config.hireMaxPerDay < 0 || daily.count("hire") < config.hireMaxPerDay)
        return OptimalPlanner.Input(
            goal = config.autoGoal,
            studyMinFactor = when (config.studyBrake) { "full" -> 0.99; "no10" -> 0.24; else -> 0.0 },
            nowMin = lt.hour * 60 + lt.minute,
            fatigueMin = snap.fatigueMin,
            gold = snap.gold, stamina = snap.stamina, clean = snap.clean,
            dayStartGold = daily.goldStart().takeIf { it >= 0 } ?: snap.gold,
            reserveGold = config.goldReserve,
            lossBudget = config.attrLossBudget,
            courses = snap.courses.filter { it.canDo }.map { c ->
                OptimalPlanner.CourseIn(
                    c.name, c.subEventType,
                    targetGain = when {
                        c.attr == studyTargetAttr(snap) -> c.attrGain.toDouble()
                        c.attr == io.github.juwanhwang.autoqqpet.pet.Attr.RANDOM -> c.attrGain / 3.0
                        else -> 0.0
                    },
                    fee = c.fee, staminaCost = c.staminaCost, cleanCost = c.cleanCost, durationMin = c.durationMin,
                )
            },
            jobs = snap.jobs.filter { it.canDo }.map { j ->
                // 雇佣价 = 目录本金 + 今日所选好友在该岗位的加成上限（两个数都是服务器标的，
                // 上限由挑人时记进 daily.bonusCaps）；上限还没记到先按本金算——加成只多不少，
                // 不会低估到自做以下。目录本金也没有时才退回雇佣实收学习值（hc 键、h 键兜底）。
                val hireGold = if (hirePriced) {
                    if (j.incomeKnown) (j.income + (daily.bonusCap(j.careerType, j.durationMin) ?: 0)).toDouble()
                    else daily.learnedGold("hc${j.careerType}t${j.durationMin}", 1) ?: daily.learnedGold("h${j.subEventType}", 1)
                } else null
                val hirePoints = if (hirePriced) daily.learnedPoints("hc${j.careerType}t${j.durationMin}", 1) ?: daily.learnedPoints("h${j.subEventType}", 1) else null
                OptimalPlanner.JobIn(
                    j.name, j.subEventType,
                    income = hireGold ?: if (j.incomeKnown) j.income.toDouble() else 0.0,
                    incomeKnown = hireGold != null || j.incomeKnown,
                    // 工分认目录标称不认学习值（学习值是折后均值，会折两道，见 OptimalPlanner.jobPoints）
                    points = OptimalPlanner.jobPoints(j.points, hirePoints),
                    staminaCost = j.staminaCost, cleanCost = j.cleanCost, durationMin = j.durationMin,
                )
            },
            adventures = advs,
            advPoolLeft = poolLeft,
            biscuitPrice = config.biscuitPrice, soapPrice = config.soapPrice,
        )
    }

    private fun decide(snap: Snapshot): Decision {
        activeStep = null
        var why = ""
        var code = ""
        val d = when {
            config.adventureOnly -> { why = "只冒险挡（今日已跑 ${daily.count("adventure")} 次）"; code = "只冒险挡"; decideAdventureOnly(snap) }
            config.solverMode -> solverDecision(snap)
            !scheduleActive -> planner.decide(snap)
            else -> {
                val before = daily.scheduleCursor()
                val r = SchedulePlanner(planner).decide(schedule, before, { st -> daily.stepProgress(st.id) }, snap)
                if (r.wrapped) {
                    // 换轮：做完的步留在旧轮作废；没做完的步把台账归属改写到新一轮，别让它前面做的次数白费
                    val nextRound = daily.scheduleRound() + 1
                    r.incompleteOnWrap.forEach { id -> daily.retagStepRound(id, nextRound) }
                    daily.setSchedule(r.cursor, nextRound)
                } else if (r.cursor != before) daily.setSchedule(r.cursor)
                activeStep = r.step
                // 步进度倒退哨兵（只记日志、不改行为）：同轮同位的步进度只许涨不许跌；跌了多半是排班被另一份副本
                // 覆盖、步 id 变了（2026-11-10 Hey 实报 187→0 当时无从定位）。高水位按步位存，id 变了也照样比得出。
                r.step?.let { st ->
                    val roundNow = daily.scheduleRound()
                    val (pc, pm) = daily.stepProgress(st.id)
                    val peak = daily.stepPeak(roundNow, r.cursor)
                    when {
                        peak != null && (pc < peak.second || pm < peak.third) -> {
                            log("⚠️ 排班步进度倒退：第 ${r.cursor + 1} 步第 $roundNow 轮由 ${peak.second} 次/${peak.third} 分钟变为 $pc 次/$pm 分钟" +
                                (if (peak.first != st.id) "（步 id 由 ${peak.first} 变为 ${st.id}）" else "") +
                                "；台账步归属现状：${daily.stepTagSummary()}")
                            daily.noteStepPeak(roundNow, r.cursor, st.id, pc, pm)   // 按现值重置：同一次倒退只报一次
                        }
                        peak == null || peak.first != st.id || pc > peak.second || pm > peak.third ->
                            daily.noteStepPeak(roundNow, r.cursor, st.id, maxOf(pc, peak?.second ?: 0), maxOf(pm, peak?.third ?: 0))
                    }
                }
                if (r.blocked == BlockReason.NO_MONEY) noteFeeShortfall(r.step, snap)
                if (r.finishedRound) log("📋 ${r.decision.reason}")
                why = "排班第 ${r.cursor + 1} 步 ${r.step?.summary() ?: "（无步骤）"}；本步做不了的原因=${r.blocked}"
                code = "排班·${r.step?.kind?.cn ?: "?"}/${r.blocked}"
                r.decision
            }
        }
        val trace = d.trace.ifBlank { why }
        daily.noteDecision(d.kind.name, d.cause.ifBlank { code })
        view.lastDecision = "${d.kind} ${d.detail}".trim()
        log("🧮 决策${if (config.adventureOnly) "[只冒险]" else if (scheduleActive) "[排班]" else ""}: ${d.kind} ${d.detail} —— ${d.reason}" +
            (if (trace.isEmpty()) "" else " ｜ 依据: $trace"))
        return d
    }

    private fun execute(d: Decision, snap: Snapshot): String = when (d.kind) {
        Kind.STUDY, Kind.WORK, Kind.ADVENTURE -> startMain(d, snap)
        Kind.FEED -> if (care.feed(snap.stamina.toDouble())) { arm(5_000, "after-feed"); "care-feed" } else { arm(30_000, "care"); "喂食未生效，30秒后重试" }
        Kind.BATH -> if (care.bath(snap.clean.toDouble())) { arm(5_000, "after-bath"); "care-bath" } else { arm(30_000, "care"); "洗澡未生效，30秒后重试" }
        Kind.PK -> { view.noteActivity("pk", "PK 中"); friends?.pkOnce(config.pkMaxPerDay, config.pkMarginPct); arm(20_000, "bypass"); "pk" }
        Kind.IDLE -> {
            val why = idleWhy(d, snap)
            log("🛑 本轮无事可做：$why")
            view.noteActivity("idle", "无事可做：${why.take(40)}")
            if (care.feedBlocked() || care.bathBlocked()) alerts.set(Alerts.IDLE, "无事可做：$why") else alerts.clear(Alerts.IDLE)
            // 休息默认 5 分钟；福袋扫描点更近时提前醒来扫一轮（扫完仍无事可做会再回到这里重新歇）
            val bagWaitMs = chores.coinBagWaitMs()
            arm(minOf(5 * 60_000L, bagWaitMs), if (bagWaitMs < 5 * 60_000L) "coinbag" else "idle"); "idle:$why"
        }
    }

    private fun decideAdventureOnly(snap: Snapshot): Decision {
        if (snap.advGoldCapped) { log("⚠️ 只冒险：今日冒险金币已达上限，回退常规决策"); return planner.decide(snap) }
        if (snap.adventureLeft <= 0) { log("⚠️ 只冒险：今日已达冒险上限(${config.adventureMaxPerDay})，回退常规决策"); return planner.decide(snap) }
        val usable = snap.adventures.filter { it.canDo }
        val best = adventureNameScope(usable, config.adventureOnlyName).maxByOrNull { planner.adventureRate(it) }
        if (best != null) return Decision(Kind.ADVENTURE, best.name, "每${best.durationSec}s 重复一轮（已跑 ${daily.count("adventure")} 次）${if (config.adventureOnlyName.isNotBlank()) "｜固定刷「${config.adventureOnlyName}」" else ""}", best.subEventType)
        log("⚠️ 只冒险：服务器无可用冒险${snap.rawAdventures.firstOrNull { it.unavailableReason.isNotEmpty() }?.let { "（${it.unavailableReason}）" } ?: ""}，回退常规决策")
        return planner.decide(snap)
    }

    /** 任务间隔还剩多少毫秒（<= 0 = 已歇够或没设间隔）。起算点是上一件任务的结算时刻。 */
    private fun taskGapWaitMs(): Long {
        val lastEnd = daily.lastTaskEnd()
        return if (lastEnd > 0 && config.taskGapSec > 0)
            lastEnd + config.taskGapSec * 1000L - System.currentTimeMillis()
        else 0L
    }

    /**
     * 开主任务并验证。写命令回包空/异常 → 不重发，复查服务器：有新任务就按【推断生效】接管，
     * 种类与时长按服务器（storyId 前缀 + 总时长）登记（真机：被雇佣 4h 打工曾被记成 45 秒冒险）。
     */
    private fun startMain(d: Decision, snap: Snapshot): String {
        val kind = d.kind
        val raw = when (kind) {
            Kind.STUDY -> snap.rawCourses.bySubOrName(d.sub, d.detail)
            Kind.WORK -> snap.rawJobs.bySubOrName(d.sub, d.detail)
            else -> snap.rawAdventures.bySubOrName(d.sub, d.detail)
        } ?: run { log("⚠️ 找不到决策指定的动作(${kind}/${d.detail})"); arm(60_000, "retry"); return "plan-mismatch" }

        // 预期（开工时目录给的数）：结算后与实收对比判疲劳
        val (durSec, expected) = when (kind) {
            Kind.STUDY -> snap.courses.firstOrNull { it.subEventType == raw.subEventType && it.name == raw.name }.let { (it?.durationSec ?: 600L) to (it?.attrGain ?: 0) }
            Kind.WORK -> snap.jobs.firstOrNull { it.subEventType == raw.subEventType && it.name == raw.name }.let { (it?.durationSec ?: 600L) to (it?.points ?: 0) }
            else -> (SceneEconomics.parseAdventure(raw).durationSec) to 0
        }
        // 短冒险节奏（D5/D6）：只剩连续未确认/失败的指数退避；最小间隔已推广为全局“任务间隔”（见上）。
        // 冒险奖励不固定（目录写“属性+0”不代表没收益，v2.1.1 起不再因此跳过）。
        val shortAdv = kind == Kind.ADVENTURE && durSec in 1..SHORT_ADV_SEC
        if (shortAdv) {
            val wait = pace.waitMs(System.currentTimeMillis(), 0L)
            if (wait > 0) {
                log("⏳ 短冒险节奏：${wait / 1000}s 后再开（${pace.describe()}）")
                view.noteActivity("waiting", "短冒险间隔 ${wait / 1000}s")
                arm(wait, "adv-pace"); return "idle:短冒险间隔"
            }
        }

        // 本地登记优先于状态读：登记里有一趟没到期的任务就绝不下新单。状态读偶发延迟时会把
        // 在跑的任务读成空闲（2026-10-16 01:51 实测：延迟结算后误判空闲又去开工、被 135054 挡回）。
        // 登记到期后守卫自动放行，僵尸登记不会把开工永久堵死（到期结算在 decide 之前已先跑过）。
        val pendId = daily.pendingId()
        if (pendId.isNotEmpty() && daily.pendingEnd() > System.currentTimeMillis()) {
            log("🔁 登记核对：本地有一趟未到期的任务（sid=$pendId）→ 不下新单，按它到期再来")
            arm(30_000, "pending-guard"); return "busy-pending"
        }

        // 任务间隔（原“短冒险间隔”推广到所有主任务，用户 2026-10-16 定）：上一件结算后歇够再开下一件。
        // tickInner 在读包/求解之前已先查过一次，这里是兜底（同一口径，正常不会触发）。
        val gapWait = taskGapWaitMs()
        if (gapWait > 0) {
            log("⏳ 任务间隔：上一件刚结束，${(gapWait + 999) / 1000}s 后再开下一件")
            view.noteActivity("waiting", "任务间隔 ${(gapWait + 999) / 1000}s")
            arm(gapWait, "task-gap"); return "idle:任务间隔"
        }

        val hireTarget = if (kind == Kind.WORK) snap.jobs.firstOrNull { d.sub > 0 && it.subEventType == d.sub }
            ?: snap.jobs.firstOrNull { it.name == d.detail } else null
        val hire = if (kind == Kind.WORK) hirePlan(hireTarget) else null
        if (kind != Kind.ADVENTURE) TaskManager.gateAndReport(service, if (kind == Kind.STUDY) Proto.SCENE_SCHOOL else Proto.SCENE_WORK, strict = false, log = log)

        // 写前探活（A）：先发一个便宜的读（0x975a 状态）。读都回不来 = MSF 会话此刻不通，写出去多半也收不到回包
        // → 不发写，30s 后重来；同时顺带确认没有在跑的任务（防止上一发“已发出未确认”的写其实已生效）。
        val probe = PetResult.read { service.story.status() }
        if (probe !is PetResult.Ok) {
            log("📡 写前探活失败（${probe.describe()}）→ 本轮不发开始命令，30s 后重试")
            view.noteActivity("waiting", "QQ 网络通道未就绪，稍后重试")
            arm(30_000, "probe"); return TickStatus.netRetry("写前探活失败")
        }
        probe.value.takeIf { it.storyId.isNotEmpty() && it.remainingSeconds > 0 }?.let { busyNow ->
            log("🔁 写前核对：服务器已有在跑的 ${busyNow.kindPrefix}（sid=${busyNow.storyId}）→ 不再下单，接管")
            return busy(busyNow)
        }

        val sentAt = System.currentTimeMillis()
        if (shortAdv) pace.noteSent(sentAt)
        // 开工先认领（2026-10-19 真机缺陷）：回包丢失 + 写后核对又没读到时，下面的登记会整段落空，
        // 下一轮就把自家任务误判成外人开的。先把种类/目录名/发出时刻记进 DailyStore（不依赖回包）；
        // 登记成功（下方统一登记处）与服务器明确拒绝时清掉，只有“未确认”路径留着它等认领领回。
        daily.noteStartClaim(when (kind) { Kind.STUDY -> "study"; Kind.WORK -> "work"; else -> "adventure" }, raw.name, sentAt)
        // 派单：打工带雇佣时，雇谁开工被服务器明确拒绝就按排名换下一位，都雇不成自己开工（见 startWorkWalking）
        val outcome = if (kind == Kind.WORK) startWorkWalking(raw, hire, hireTarget)
            else StartOutcome(PetResult.write { if (kind == Kind.STUDY) service.story.startSchool(raw) else service.story.startAdventure(raw) }, null, false)
        val r = outcome.result
        val hired = outcome.hired
        val started = r.getOrNull()
        var sid = started?.storyId.orEmpty()
        var effKind = kind
        var durFinal = (started?.durationSeconds?.takeIf { it > 0 } ?: durSec).coerceAtLeast(20L)
        var startSec = started?.startedAt ?: 0L
        var inferred = false
        var finishedAlready = false
        if (sid.isEmpty() && r is PetResult.Rejected) {
            // 服务器明确拒绝 = 真失败（不是“未确认”）；认领同步作废，免得误领用户随后手动开的同类任务
            daily.clearStartClaim()
            rejectStreak = if (r.code == rejectCode) rejectStreak + 1 else 1
            rejectCode = r.code
            if (r.code != io.github.juwanhwang.autoqqpet.net.Commands.CODE_WRONG_SUB &&
                !CatalogReader.invalidateForReject(r.code, System.currentTimeMillis())) {
                log("  开工仍被拒 code=${r.code}：与上次同码，目录不再重复全量刷新")
            }
            io.github.juwanhwang.autoqqpet.pet.FatigueText.detect(r.message)?.let { noteTiredText(raw.subEventType, it, "开工回包") }
            if (shortAdv) pace.noteFail(System.currentTimeMillis())
            val n = daily.bumpPendingRetry()
            log("❌ 启动失败（服务器拒绝，retry=$n）: ${d.detail} ${r.describe()}")
            val delayMs = startRetryDelayMs(rejectStreak)
            log("  开工被拒退避：code=${r.code} 连续第 $rejectStreak 次被拒，${delayMs / 1000} 秒后再试")
            if (n >= config.maxStartRetry) { daily.clearPending(); arm(maxOf(delayMs, 10 * 60_000L), "backoff") } else arm(delayMs, "startretry")
            return "start-failed"
        }
        if (sid.isEmpty()) {
            // 空回包 / 无回包（Unverified）：两者都不可靠（D4）→ 统一读状态核对，绝不重发
            val v = verifyStart(kind, sentAt)
            if (v != null) {
                sid = v.storyId; inferred = true; finishedAlready = v.finished
                effKind = when (io.github.juwanhwang.autoqqpet.pet.OutingKind.ofStoryId(sid)) {
                    io.github.juwanhwang.autoqqpet.pet.OutingKind.STUDY -> Kind.STUDY
                    io.github.juwanhwang.autoqqpet.pet.OutingKind.WORK -> Kind.WORK
                    io.github.juwanhwang.autoqqpet.pet.OutingKind.ADVENTURE -> Kind.ADVENTURE
                    else -> kind
                }
                if (v.durationSec > 0) durFinal = v.durationSec
                if (v.startSec > 0) startSec = v.startSec
                log("✅ $kind 回包${r.describe()}，读状态核对：${v.how} → 已生效，按 $effKind 登记 sid=$sid（不重发）")
            } else {
                // 未确认 ≠ 失败：只是核对不到。不立即重下单，按退避等一会再走决策（下一轮开头还会再读状态）
                if (shortAdv) pace.noteFail(System.currentTimeMillis())
                val n = daily.bumpPendingRetry()
                val backoff = (60_000L shl (n - 1).coerceIn(0, 4)).coerceAtMost(10 * 60_000L)
                // 排班步记下意图：这趟若其实跑了、之后在结算时被补记，对上开始时刻就把步的次数补回去
                activeStep?.let {
                    val tag0 = when (kind) { Kind.STUDY -> "study"; Kind.WORK -> "work"; else -> "adventure" }
                    daily.noteUnconfirmedIntent(it.id, tag0, ((durFinal + 59) / 60).toInt().coerceAtLeast(1), sentAt)
                }
                log("⚠️ 启动已发出但未确认（${r.describe()}；核对 $VERIFY_TRIES 次服务器均未见新任务，unconfirmed=$n）→ 按未生效处理，${backoff / 1000}s 后再决策（不立即重发）")
                view.noteActivity("waiting", "开始命令未确认，${backoff / 1000}s 后核对")
                arm(backoff, "unconfirmed"); return "start-unconfirmed"
            }
        }
        var afterStory: StoryStatus? = null
        if (!inferred) {
            // 回包给了 storyId：服务器已受理。状态读暂时看不到运行（短冒险可能已结束/读延迟）也按已开登记，下一轮结算阶段核对
            val after = currentStory()
            afterStory = after
            if (after == null || after.storyId != sid || after.remainingSeconds <= 0) {
                log("ℹ️ 回包已给 sid=$sid，但状态读暂未见运行（${after?.storyId ?: "空"}）→ 按已开登记，到期结算时再核对")
            } else if (after.startedAt > 0) startSec = after.startedAt
        }
        if (shortAdv) pace.noteOk()
        rejectCode = 0; rejectStreak = 0   // 开工成功：被拒退避清零
        if (hired != null && !inferred) noteHire(hired, outcome.reuse)
        val durMin = ((durFinal + 59) / 60).toInt().coerceAtLeast(1)
        val tag = when (effKind) { Kind.STUDY -> "study"; Kind.WORK -> "work"; else -> "adventure" }
        val counted = daily.countTaskOnce(sid, tag, durMin, effKind == Kind.ADVENTURE, seconds = durFinal, stepId = activeStep?.id ?: "")
        if (counted) daily.clearUnconfirmedIntent()
        daily.noteStarted(sid)
        // 均衡学习轮转：真正派出一趟学习（非排班步、非重复登记）后，指针跳到所派课程属性的下一个
        if (counted && config.balancedStudy && effKind == Kind.STUDY && activeStep == null) {
            val used = snap.courses.firstOrNull { it.subEventType == raw.subEventType && it.name == raw.name }?.attr
            if (used != null) daily.setStudyRotationNext(StudyRotation.indexAfter(daily.studyRotationNext(), used))
        }
        // 到期时刻以服务器开始时间戳（秒）为准（B7）：本地“发出时刻 + 时长”会因回包延迟/核对耗时偏几秒到几十秒
        val endAt = TaskClock.endAt(startSec, durFinal, System.currentTimeMillis())
        daily.setPending(sid, tag, endAt, durMin,
            sub = if (inferred) 0 else raw.subEventType, expected = if (inferred) 0 else expected, name = raw.name, durationSec = durFinal,
            // 职业在开工时定格（打工定价按「职业+时长」记账，编号/名字都会漂移、职业不会）；非打工记 0
            career = if (effKind == Kind.WORK) snap.jobs.firstOrNull { it.subEventType == raw.subEventType }?.careerType ?: 0L else 0L,
            // 鼓励目标次数在开工时定格：推断生效（没拿到回包）与冒险不鼓励（冒险发 0x9c44 报 135011）
            encourageTarget = if (!inferred && effKind != Kind.ADVENTURE) config.encourageTimes else 0,
            // 派发时刻（发出开始命令那一刻）：冒险耗时记账的起算点，结案时按它算整趟墙钟
            dispatchedAtMs = sentAt)
        daily.clearStartClaim()   // 登记已落账（回包直达与推断生效两条路都汇到这里），认领完成使命
        // 学费在开课时由服务器扣下：记进模块自家支出，免得下轮读金币时被当成用户手动消费、下调今日基线
        if (effKind == Kind.STUDY) {
            val fee = snap.courses.firstOrNull { it.subEventType == raw.subEventType && it.name == raw.name }?.fee ?: 0
            if (fee > 0) daily.noteModuleGoldSpend(fee)
        }
        // 自动鼓励第 1 次在开工后立刻发（旧版一直如此）；设了 N>1 次时其余由 busy() 按间隔补发
        if (daily.pendingEncourageTarget() > 0) encourageOnce(sid)
        // 自动用任务加成道具（2026-11-11）：学习/打工各一档，服务器按背包里的 SKU 扣
        maybeUseTaskItem(sid, effKind, inferred)
        // 天色不对检查：模块派出的冒险都挂记号（含排班，2026-10-19 用户拍板；不想要的人关
        // 「天色不对自动召回」开关即可，不给排班单设开关）。手动/外部故事根本不经这里。
        // 挂记号，并把第一次唤醒提前到检查点——不然短冒险要到到期结算才有 tick，检查窗口早就过了
        val weatherArmed = !finishedAlready && effKind == Kind.ADVENTURE && config.weatherRecallOn
        if (weatherArmed) daily.armWeatherCheck(sid, System.currentTimeMillis() + WEATHER_CHECK_AFTER_MS)
        val endDelay = (endAt - System.currentTimeMillis()).coerceAtLeast(0) + if (effKind == Kind.ADVENTURE) config.adventureSettleBufferMs else config.settleBufferMs
        if (finishedAlready) log("ℹ️ 这一趟在核对时已经结束（出门记录里已有），马上结算")
        val wakeDelay = if (weatherArmed) minOf(endDelay, WEATHER_CHECK_AFTER_MS) else endDelay
        armAt(System.currentTimeMillis() + if (finishedAlready) 3_000L else wakeDelay, if (weatherArmed && wakeDelay < endDelay) "weather-check" else "settle")
        view.noteActivity(tag, "${cn(effKind)}「${raw.name}」${durFinal}s")
        // 主页立刻显示“正在…”（v2.1.1）：开工后把任务写进 view（之前只在每轮开头读状态，开工后要等 45 分钟才更新 → 主页一直显示稍作休息）
        if (!finishedAlready) {
            val live = afterStory?.takeIf { it.storyId == sid && it.remainingSeconds > 0 }
                ?: StoryStatus(sid, 0, ((endAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0), durFinal, startSec, true)
            view.noteStory(live, true)
        }
        log("✅ 已${if (hired != null && !inferred) "雇佣${hired.first}" else ""}${cn(effKind)}「${d.detail}」sid=$sid 时长${durFinal}s，${endDelay / 1000}s 后到期结算" +
            (if (inferred) "（推断生效）" else "") + (if (!counted) "（已进过账，不重复计入）" else ""))
        return "started:$tag:${d.detail}"
    }


    /**
     * 发一次鼓励并记账（pend.enc_*）。成功才计数；服务器明确拒绝 = 本趟到顶/不支持，
     * 把已发记满、本趟停发；通信未确认不计数，靠 [EncouragePlan.GAP_MS] 间隔在 busy() 里再试。
     */
    private fun encourageOnce(sid: String) {
        val target = daily.pendingEncourageTarget()
        if (target <= 0 || daily.pendingEncourageDone() >= target) return
        val r = PetResult.write { service.story.encourage(sid) }
        daily.setPendingEncourageAt(System.currentTimeMillis())
        when {
            r.ok -> {
                val done = daily.pendingEncourageDone() + 1
                daily.setPendingEncourageDone(done)
                log("👏 鼓励 $done/$target 次（sid=$sid）")
            }
            r is PetResult.Rejected -> {
                daily.setPendingEncourageDone(target)
                log("👏 鼓励被服务器拒绝，本趟不再发：${r.describe()}")
            }
            else -> log("👏 鼓励未确认，稍后复查时再试：${r.describe()}")
        }
    }

    // ———————————————— 外部任务 / 出门记录 / 写后核对 ————————————————

    /**
     * 开工认领领回：[startMain] 发指令前记了认领，但回包与写后核对都丢了登记时，认领会留下来；
     * 状态读到一份不认识的在跑任务时先对认领——窗口内（[StartClaim.WINDOW_MS]）、种类一致就是自家
     * 刚开的那份（状态读阶段拿不到任务名，名字不参与这次判定，见 [StartClaim.matches]）。
     * 按接手口径补登记成正式 pending：到期走 [TaskClock]（与 [busy] 的接手登记同一算法），
     * 目录预期与鼓励目标记 0（与“推断生效”登记一致：没拿到开工回包就没有预期可记）。
     * 在跑的只有这一份，旧 pending 指向的那份已不在跑，setPending 直接覆盖即接手。领回后清认领。
     * @return 是否领回（领回后调用方按自家任务继续走）。
     */
    private fun claimBackIfOurs(st: StoryStatus): Boolean {
        val (cKind, cName, cAt) = daily.startClaim()
        if (cAt <= 0L) return false
        val now = System.currentTimeMillis()
        if (StartClaim.expired(cAt, now)) { daily.clearStartClaim(); return false }
        if (!StartClaim.matches(cKind, cName, cAt, kindTag(st), "", now)) return false
        daily.clearStartClaim()
        daily.noteStarted(st.storyId)
        daily.setPending(st.storyId, kindTag(st),
            TaskClock.endAt(st.startedAt, st.durationSeconds, now, st.remainingSeconds),
            (st.remainingSeconds / 60).toInt(),
            name = cName, durationSec = st.durationSeconds,
            // 认领里存的就是当初发出开始命令的时刻，认领回来的趟耗时记账从那时起算，不从领回算
            dispatchedAtMs = cAt)
        val oc = io.github.juwanhwang.autoqqpet.pet.OutingKind.ofStoryId(st.storyId)
        val label = if (cName.isNotEmpty()) "「$cName」" else ""
        log("👀 这份${oc.cn}$label 是刚刚开的那份，开工登记丢了，已补回来（sid=${st.storyId}）")
        return true
    }

    /**
     * 不是本模块开的任务（用户在 QQ 里手动开的、被雇的…）：读一次 0x9874 详情拿真名与服务器开始时间，给主页显示（B1）。
     * 每个 storyId 只读一次；读不到就算了（界面显示“外出中”）。
     */
    private fun describeExternal(st: StoryStatus) {
        if (externalInfo.containsKey(st.storyId)) { view.externalName = externalInfo[st.storyId]?.first.orEmpty(); return }
        val d = PetResult.read { service.story.detail(st.storyId) }.getOrNull()
        val name = d?.title.orEmpty()
        externalInfo[st.storyId] = name to (d?.startSec ?: 0L)
        if (externalInfo.size > 50) externalInfo.keys.take(25).forEach { externalInfo.remove(it) }
        view.externalName = name
        log("👀 宠物在外：${io.github.juwanhwang.autoqqpet.pet.OutingKind.ofStoryId(st.storyId).cn}「${name.ifEmpty { "未知" }}」不是 Autoqqpet 开的（sid=${st.storyId}，剩 ${st.remainingSeconds}s）→ 等它结束再接手")
    }

    /**
     * 出门记录对账（B3）：每 30 分钟读一页 0x9876，把今天的、还没记过的已结束记录补进“今天”（noteDone 按 storyId 去重）。
     * 只认能读出时间戳且落在今天的记录；时间戳认不出就不补（宁可少记不乱记）。外部任务（手动/被雇）也会因此进统计。
     */
    private fun syncHistoryIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastHistorySync < HISTORY_SYNC_MS) return
        lastHistorySync = now
        val page = PetResult.read { service.story.history() }.getOrNull() ?: return
        val today = io.github.juwanhwang.autoqqpet.pet.Day.today()
        val running = view.story?.takeIf { it.remainingSeconds > 0 }?.storyId.orEmpty()
        var added = 0
        var reads = 0
        val addedDesc = ArrayList<String>()
        for (rec in page.records) {
            if (rec.atMs <= 0 || io.github.juwanhwang.autoqqpet.pet.Day.of(rec.atMs) != today) continue
            val tag = rec.kind.tag.takeIf { it == "study" || it == "work" || it == "adventure" } ?: continue
            // 还在跑 / 还在等结算的不补（进行中不算「今天」；结算阶段自己处理）；本模块开的已在台账里
            if (rec.storyId == daily.pendingId() || rec.storyId == running || daily.isMine(rec.storyId)) continue
            if (daily.inLedger(rec.storyId)) continue
            // v2.1.1：补进**台账**（与时间线、「今天」同源），开始时间与时长取单条详情；读不到开始时间就不补（宁可少记不乱记）
            if (reads++ >= 6) break
            val d = PetResult.read { service.story.detail(rec.storyId) }.getOrNull() ?: continue
            if (d.startSec <= 0) continue
            // 详情归属核对（2026-10-16 幻影记录实锤）：详情回包的 storyId 是请求参数回填、不可信，
            // 但场景（.11）是服务器给的——与列表条目的种类对不上，说明这份详情不是这一条的
            // （真机案例：拿打工的详情给一条学习记录补记，开始时间/时长全借错，还因种类不同绕过去重）。
            // 场景缺失（0）时 StoryDetail.kind 会回退到按 id 猜，与 rec.kind 恒等、核对自动放行。
            if (d.kind != rec.kind) {
                log("📒 对账跳过：记录 ${rec.storyId} 是${rec.kind.cn}，详情场景却是${d.kind.cn}，疑似详情串条，不补记")
                continue
            }
            val sec = maxOf(d.courseMinutes * 60, d.beats.maxOfOrNull { it.second } ?: 0L)
            val startMs = d.startSec * 1000
            if (startMs + sec * 1000 > System.currentTimeMillis()) continue   // 还没结束
            if (daily.appendReconciled(rec.storyId, tag, startMs, sec)) {
                added++
                // 补记必须留明细（2026-10-16 排查“今天多出一条学习”时，旧日志只有条数、无从对账）
                addedDesc += "${rec.kind.cn}「${rec.title.ifEmpty { d.title }}」${rec.storyId}"
            }
        }
        if (added > 0) log("📒 出门记录对账：补记今天已结束的 $added 条（含不是本模块开的）：${addedDesc.joinToString("；")}")
    }

    private data class Verified(val storyId: String, val how: String, val durationSec: Long = 0, val startSec: Long = 0, val finished: Boolean = false)

    /**
     * 写命令（0x975e）回包空/没回包后的核对（D3/D4）：先读状态（busy:storyId），再读出门记录最新条目（短冒险可能已结束）。
     * 认新任务的条件：storyId 前缀与本次下单的场景一致、不是已结算/上一条登记的任务；出门记录里还要求时间戳不早于下单时刻 60 秒前。
     */
    private fun verifyStart(kind: Kind, sentAt: Long): Verified? {
        val prefix = when (kind) { Kind.STUDY -> setOf("6100"); Kind.WORK -> setOf("6400", "6500"); else -> setOf("6700") }
        repeat(VERIFY_TRIES) { i ->
            if (i > 0) runCatching { Thread.sleep(VERIFY_GAP_MS) }
            val st = PetResult.read { service.story.status() }.getOrNull()
            if (st != null && st.storyId.isNotEmpty() && st.remainingSeconds > 0 && st.kindPrefix in prefix &&
                st.storyId != daily.pendingId() && !daily.isSettled(st.storyId)) {
                return Verified(st.storyId, "状态读到 busy:${st.storyId}", st.durationSeconds, st.startedAt)
            }
            val page = PetResult.read { service.story.history(pageSize = 5) }.getOrNull()
            val rec = page?.records?.firstOrNull { it.storyId.take(4) in prefix && !daily.isSettled(it.storyId) && !daily.isMine(it.storyId) }
            if (rec != null && rec.atMs >= sentAt - 60_000L) {
                return Verified(rec.storyId, "出门记录最新一条「${rec.title}」", finished = true)
            }
        }
        return null
    }

    private fun cn(k: Kind) = when (k) { Kind.STUDY -> "开课"; Kind.WORK -> "开工"; else -> "冒险" }

    /**
     * 任务加成道具（2026-11-11 抓包定案）：开工成功后按类型查背包（0x9bd5），有就用一颗（0x9d9e）。
     * 使用命令只传**道具族 id**（学习 356 / 打工 357），服务端按该号背包里的 SKU 扣——不同账号持有什么 SKU 都能用。
     * 学习每趟限 1 杯（135109），只发一次。任何失败只记日志，不影响任务。
     */
    private fun maybeUseTaskItem(sid: String, kind: Kind, inferred: Boolean) {
        val family = when (kind) { Kind.STUDY -> ItemFamily.STUDY; Kind.WORK -> ItemFamily.WORK; else -> return }
        val on = if (kind == Kind.STUDY) config.studyItemOn else config.workItemOn
        if (!on || sid.isEmpty() || inferred) return
        runCatching {
            val inv = PetResult.read { service.item.inventory(family) }.getOrNull().orEmpty()
            val stock = inv.firstOrNull { it.count > 0 }
            if (stock == null) {
                log("🍹 任务道具：背包里没有${if (kind == Kind.STUDY) "学习" else "打工"}加成道具，跳过")
                return
            }
            when (val r = PetResult.write { service.item.use(family, sid) }) {
                is PetResult.Ok -> log("🍹 已用一颗${if (kind == Kind.STUDY) "学习" else "打工"}加成道具「${stock.name}」：${r.value}")
                is PetResult.Rejected ->
                    if (r.code == 135109) log("🍹 本趟已喝过 1 杯，跳过（${stock.name}）")
                    else log("🍹 道具使用被拒：${r.describe()}")
                else -> log("🍹 道具使用未确认：${r.describe()}")
            }
        }.onFailure { log("🍹 自动用道具出错：${it.message}") }
    }

    /** 雇佣计划：先试谁（已建立的复用对象，或排名第一位）；[active] 非空表示第一位是复用对象。 */
    private data class HirePlan(val candidates: List<HirePolicy.HireCandidate>, val active: Pair<String, String>?)

    private data class StartOutcome(val result: PetResult<io.github.juwanhwang.autoqqpet.pet.StartedStory>, val hired: Pair<String, String>?, val reuse: Boolean)

    /**
     * 雇佣决定：雇人打工不收费（250 是 PK 请保镖的费用，与雇人无关）——每日次数上限按雇佣开工
     * 班次计，新建立与复用都算，达到上限后复用也不再雇；额度内有今天已建立的雇佣就直接复用，
     * 新雇看开关与金币留底，不按工时算回本；能不能雇先由 0x9875 说了算，派工被拒再由排名往下试。
     */
    private fun hirePlan(target: io.github.juwanhwang.autoqqpet.pet.JobInfo?): HirePlan? {
        val active = policy?.activeHire()
        val v = HirePolicy.judge(config.autoHireFriend, daily.count("hire"), config.hireMaxPerDay,
            reusable = active != null)
        if (!v.hire) { if (config.autoHireFriend) log("🤝 本轮不雇佣：${v.reason}"); return null }
        if (v.reuse && active != null) {
            // 先按当前名单复检：人是早些时候挑的，名单可能已经变了（拉黑、移出白名单）——名单说不行就不再复用（2026-11-10 群友实报）
            if (policy?.hireReuseAllowed(active.first, service.selfUin()) == false) {
                log("🤝 已建立的雇佣对象已被名单排除，不复用，重新挑人")
                policy.clearActiveHire()
                val v2 = HirePolicy.judge(config.autoHireFriend, daily.count("hire"), config.hireMaxPerDay)
                if (!v2.hire) { log("🤝 本轮不雇佣：${v2.reason}"); return null }
            } else {
                val st = PetResult.read { service.friend.status(active.first, active.second) }.getOrNull()
                if (st == null || st.hireable) { log("🤝 ${v.reason}"); return HirePlan(listOf(HirePolicy.HireCandidate(active.first, active.second)), active) }
                log("🤝 已建立的雇佣对象现在不可雇（${st.reason}），不复用"); policy.clearActiveHire()
                val v2 = HirePolicy.judge(config.autoHireFriend, daily.count("hire"), config.hireMaxPerDay)
                if (!v2.hire) { log("🤝 本轮不雇佣：${v2.reason}"); return null }
            }
        }
        val first = HirePolicy.rankedHireable(service, policy, service.selfUin(), log = log,
            targetCareer = target?.careerType ?: 0, targetDurationMin = target?.durationMin ?: 0, maxCollect = 1)
        return if (first.isEmpty()) null else HirePlan(first, null)
    }

    /**
     * 打工派单（2026-11-03 用户定）：按排名从上到下逐个试着雇——状态说能雇、派工却被服务器
     * 明确拒绝（群友 ChangLe 实报：同一位好友连拒多轮、整条打工线堵死）就换下一位；
     * 排名里都试遍还雇不成，就不雇了、自己开工。雇佣只是加成，绝不能把开工堵住。
     * 只有“明确拒绝”才换人：回包未确认（可能已生效）绝不叠发第二发，照旧走核对路径。
     */
    private fun startWorkWalking(
        raw: io.github.juwanhwang.autoqqpet.pet.SceneOption,
        plan: HirePlan?,
        target: io.github.juwanhwang.autoqqpet.pet.JobInfo?,
    ): StartOutcome {
        val tried = mutableSetOf<String>()
        var queue: List<HirePolicy.HireCandidate> = plan?.candidates ?: emptyList()
        var idx = 0
        var freshLoaded = plan?.active == null   // 第一位是复用对象时，排名名单还没拉
        while (true) {
            val cand: HirePolicy.HireCandidate? = when {
                idx < queue.size -> queue[idx++]
                !freshLoaded -> {
                    freshLoaded = true
                    queue = queue + HirePolicy.rankedHireable(service, policy, service.selfUin(), log = log,
                        targetCareer = target?.careerType ?: 0, targetDurationMin = target?.durationMin ?: 0,
                        exclude = tried)
                    continue
                }
                else -> null   // 候选试遍 → 自己开工（最后一发）
            }
            if (cand != null) tried += cand.uin
            val attempt = PetResult.write { service.story.startWork(raw, cand?.let { it.uin to it.petId }) }
            if (attempt is PetResult.Rejected && cand != null) {
                log("🤝 雇佣 ${cand.uin} 开工被服务器拒绝（${attempt.describe()}），换下一位")
                continue
            }
            if (cand != null && cand.caps.isNotEmpty()) {
                target?.let { daily.noteBonusCaps(it.careerType, cand.caps) }
            }
            if (cand == null && plan != null && tried.isNotEmpty()) log("🤝 排名里能雇的都试过了，没雇成，自己开工")
            return StartOutcome(attempt, cand?.let { it.uin to it.petId }, cand != null && plan?.active == (cand.uin to cand.petId))
        }
    }

    /** 开工成功且带了雇佣：新建立与复用都记一次数（每日上限按雇佣开工班次计）；新雇佣另登记为“已建立”（当天复用靠它）。雇人不花钱，没有扣费可确认。 */
    private fun noteHire(hired: Pair<String, String>, reuse: Boolean) {
        daily.noteHire()
        if (!reuse) policy?.setActiveHire(hired.first, hired.second)
        log("🤝 雇佣 ${hired.first}：${if (reuse) "复用今天已建立的雇佣" else "已建立，今天内可复用"}")
    }

    private fun List<SceneOption>.bySubOrName(sub: Long, name: String): SceneOption? =
        (if (sub > 0) firstOrNull { it.subEventType == sub } else null) ?: firstOrNull { it.name == name }

    private fun noteFeeShortfall(st: Step?, snap: Snapshot) {
        if (st == null || st.kind != StepKind.STUDY || daily.count(KEY_FEE_ALERT) > 0) return
        daily.increment(KEY_FEE_ALERT)
        val course = snap.courses.firstOrNull { st.sub > 0L && it.subEventType == st.sub }
            ?: snap.courses.firstOrNull { st.itemName.isNotBlank() && it.name == st.itemName }
            ?: snap.courses.filter { (st.attr.isEmpty() || attrCn(it.attr) == st.attr) && (st.dur <= 0 || it.durationMin == st.dur) }.minByOrNull { it.fee }
            ?: snap.courses.filter { st.attr.isEmpty() || attrCn(it.attr) == st.attr }.minByOrNull { it.fee }
        val text = "学费不够：「${course?.name ?: (st.attr.ifBlank { "本学院" } + "的课")}」要 ${course?.fee ?: 0}，金币只有 ${snap.gold}（还要留 ${config.goldReserve}）。已按排班规则继续。"
        log("💰 $text"); alerts.set(Alerts.FEE, text)   // 用户要求：学费不够要弹提醒（ACTION 级 → 通知）
    }

    private fun attrCn(a: Attr): String = when (a) { Attr.STRENGTH -> "力量"; Attr.INTEL -> "智力"; Attr.CHARM -> "魅力"; Attr.RANDOM -> "随机"; else -> "属性" }

    private fun idleWhy(d: Decision, snap: Snapshot): String {
        val reasons = (snap.rawCourses + snap.rawJobs + snap.rawAdventures).map { it.unavailableReason.trim() }.filter { it.isNotEmpty() }.distinct()
        val careNote = when {
            care.feedBlocked() && care.bathBlocked() -> "；喂食/洗澡都被服务端拒收"
            care.feedBlocked() -> "；喂食被服务端拒收"
            care.bathBlocked() -> "；洗澡被服务端拒收"
            else -> ""
        }
        val server = if (reasons.isEmpty()) "" else "；服务器说：" + reasons.joinToString("、").take(70)
        return "${d.reason.take(60)}（体力${snap.stamina}/清洁${snap.clean}/金币${snap.gold}）$careNote$server".take(170)
    }

    /** 主任务进行中顺带 PK（三分之一概率，受开关与每日上限约束）。 */
    private fun bypassPk() {
        val f = friends ?: return
        if (!config.pkOn || java.util.Random().nextInt(3) != 0) return
        runCatching { f.pkOnce(config.pkMaxPerDay, config.pkMarginPct) }
    }

    private fun pullSettingsIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastSettingsPull < 5 * 60_000L) return
        lastSettingsPull = now
        runCatching { pullSettings() }
    }

    // ———————————————— 疲劳与快照 ————————————————

    private fun assessPay(courses: List<io.github.juwanhwang.autoqqpet.pet.CourseInfo>, jobs: List<io.github.juwanhwang.autoqqpet.pet.JobInfo>): PayMonitor.Assessment {
        val base = PayMonitor.assess(courses, jobs, emptySet()) { k, v -> runCatching { daily.payFirst(k, v) }.getOrDefault(0) }
        return PayMonitor.withText(base, textTired, courses, jobs, daily.fatigueMinutes())
    }

    /** 服务器疲劳文案命中的档位（sub → 原文）：**只代表这一档会跨 8 小时线**。当日有效，跨 runner 重建保留在 DailyStore。 */
    private val textTired: Map<Long, String>
        get() = daily.settleDrops().associateWith { "（今日已记录）" } + textTiredMem

    private val textTiredMem = HashMap<Long, String>()

    /** 记一条疲劳文案：只标那一档（会跨线），绝不据此判全部学工疲劳。不知道是哪一档（sub<=0）就只记日志。 */
    private fun noteTiredText(sub: Long, text: String, where: String) {
        if (sub <= 0) { log("💤 服务器疲劳提示（$where，档位未知）：「$text」——只记录，不判整体疲劳"); return }
        if (textTiredMem.containsKey(sub)) return
        textTiredMem[sub] = text
        daily.markSettleDrop(sub)
        log("💤 服务器疲劳提示（$where）：「$text」→ 档位 #$sub 做完会跨 8 小时线，降权（其它短档照常）")
    }

    /** 降效只作展示（见 [PayMonitor.displayOnly]）：决策直接按当前目录报酬排序，疲劳已经体现在读数里。 */
    private fun applyPay(pay: PayMonitor.Assessment) {
        lastPenalizedSubs = pay.penalized
        view.payTired = pay.allDropped; view.penalizedCount = pay.droppedCount
        daily.setCount(KEY_PAY_TIRED_NOW, if (pay.allDropped) 1 else 0)
        if (pay.notes == lastPayNotes) return
        lastPayNotes = pay.notes
        if (pay.notes.isEmpty()) { alerts.clear(Alerts.PAY_TIRED); return }
        log("💤 目录报酬比今日首读低（展示统计，决策已按当前报酬排序）：${pay.notes.joinToString("；")}")
        if (pay.allDropped && daily.count(KEY_PAY_TIRED_NOTICE) == 0) {
            daily.increment(KEY_PAY_TIRED_NOTICE)
            alerts.set(Alerts.PAY_TIRED, "今日学工报酬都比早上低了（宠物疲劳），会按当前报酬自动在学工与冒险之间取舍；明天自动恢复。", Alerts.Level.INFO)
        }
    }

    private fun buildSnapshot(v: io.github.juwanhwang.autoqqpet.pet.PetValues): Snapshot {
        val io0 = io.github.juwanhwang.autoqqpet.pet.IoStats.snap()
        // 排班步骤点名的职业必须进决策快照：打工方向钉子与单焦点现读是给最优调度的收窄，
        // 漏进排班会让步骤的职业永远不在名单里、整表判 NO_SUCH_SUB 停摆（2026-10-30 群友实报）
        val rosterCareers = if (scheduleActive)
            schedule.steps.filter { it.kind == StepKind.WORK }.mapNotNull { it.career.takeIf { c -> c > 0 } }.toSet()
        else emptySet()
        val cat = catalogReader.read(rosterCareers)
        view.stage = cat.stage
        view.catalogPackets = cat.packets
        val rawAdv = PetResult.read { service.story.adventures() }.getOrNull().orEmpty()
        val adventures = rawAdv.map { SceneEconomics.parseAdventure(it) }
        val now = System.currentTimeMillis()
        if (cat.fullRefresh || now - lastCatalogPush >= 30 * 60_000L) {
            lastCatalogPush = now
            runCatching {
                // 与界面共用一个数据源：岗位用 runner 维护的结构缓存（全部职业），时间戳随之更新
                pushCatalog(
                    catalogReader.cachedTiers(),
                    cat.rawCourses.map { JobCatalog.ofCourse(SceneEconomics.parseCourse(it), it.duration, cat.stage) },
                    rawAdv.map { JobCatalog.ofAdventure(SceneEconomics.parseAdventure(it), it.duration) },
                )
            }
        }
        val advLeft = if (config.adventureMaxPerDay > 0) (config.adventureMaxPerDay - daily.count("adventure")).coerceAtLeast(0) else Int.MAX_VALUE
        (cat.rawCourses + cat.rawJobs).forEach { o -> io.github.juwanhwang.autoqqpet.pet.FatigueText.detect(o)?.let { noteTiredText(o.subEventType, it, "目录") } }
        val pay = assessPay(cat.courses, cat.jobs)
        applyPay(pay)
        val io = io.github.juwanhwang.autoqqpet.pet.IoStats.snap() - io0
        view.decisionPackets = io.readOk + io.readFail
        log("📦 本轮决策读包 ${io.readOk + io.readFail} 个（目录 ${cat.packets}${if (cat.fullRefresh) "，结构全量刷新" else "，结构走缓存"}）")
        return Snapshot(
            gold = v.gold.toInt(), stamina = v.hunger.toInt(), clean = v.clean.toInt(),
            fatigueMin = daily.fatigueMinutes(), courses = cat.courses, jobs = cat.jobs,
            biscuits = PetResult.read { service.inventory.biscuits().toInt() }.getOrNull() ?: 0,
            soap = PetResult.read { service.inventory.soap().toInt() }.getOrNull() ?: 0,
            rawCourses = cat.rawCourses, rawJobs = cat.rawJobs,
            adventures = adventures, rawAdventures = rawAdv, adventureLeft = advLeft,
            advGoldCapped = daily.advGoldCapped(),
            feedBlocked = care.feedBlocked(), bathBlocked = care.bathBlocked(),
            penalizedSubs = pay.penalized, studyWorkTired = pay.allGone,
            fatigueTextSubs = textTired.keys,
        )
    }

    /** 诊断快照（只读只算，不开任务不结算）。 */
    fun diagnose(): String = RunnerDiagnostics.render(
        config, daily, planner, scheduleActive, schedule, scheduleStatus(),
        snapshot = runCatching { buildSnapshot(service.readValues()) }.getOrNull(),
        stage = runCatching { service.story.schoolStage() }.getOrNull(),
    )

    /** 给 dry-run `plan` 用：用循环同一套快照与规划器口径算一次（不落盘、不执行）。 */
    fun dryRun(): Pair<Decision, Snapshot> {
        val snap = buildSnapshot(service.readValues())
        val d = when {
            config.adventureOnly -> decideAdventureOnly(snap)
            scheduleActive -> SchedulePlanner(planner).decide(schedule, daily.scheduleCursor(), { st -> daily.stepProgress(st.id) }, snap).decision
            else -> planner.decide(snap)
        }
        return d to snap
    }
}

/**
 * 任务间隔的精确唤醒时刻（纯逻辑，单测覆盖）：上一件结案时刻 + 设定间隔秒数。
 * 没设间隔（<= 0）或还没有结案时刻（<= 0）→ null，调用方走原有兜底排程。
 * 算出的时刻已过也原样返回——armAt/调度侧会按已到期处理、马上唤醒，不在这里吞掉，
 * 否则结案处理本身耗时超过间隔时又要多等一轮。
 */
internal object TaskGapWake {
    fun atMillis(lastEndMs: Long, gapSec: Int): Long? =
        if (lastEndMs > 0 && gapSec > 0) lastEndMs + gapSec * 1000L else null
}
