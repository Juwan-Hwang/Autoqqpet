package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.HireDetect
import io.github.juwanhwang.autoqqpet.pet.HiredNotice
import io.github.juwanhwang.autoqqpet.pet.OutingKind
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import io.github.juwanhwang.autoqqpet.pet.StoryStatus
import io.github.juwanhwang.autoqqpet.pet.TaskManager

/**
 * 被雇佣时的召回与提醒（交接 §7 官方分成表 + §9.10 被雇实测）。
 *
 * 自动召回的条件（全部是确定信号）：
 *  1. 用户开着 `recall_on`（默认开：71% 召回拿 100% 基础 + 75% 增益分成，自然到期只拿 10% 增益）；
 *  2. 这条任务**不是本模块开的**（本地台账）；
 *  3. **确认被雇佣**：`state=51` + 0x994a 里有一条落在任务时间窗内、且没被别的任务认领过的“雇佣了”通知（[HireDetect]，认领即绑定、一生只认一份工）；
 *     例外：用户开了 `recall_external_on` 时，不是本模块开的打工不等通知确认、直接按同一套时机处理（亲手开的工也算，用户在设置里明确知情）；
 *  4. 进度到 71%（官方区间下边界）或用户选了“立刻召回”。
 * **不再看服务器 recallable**：被雇召回实测 `.5` 缺省也成功。召回回包 `.1.16.7` 直接给到账金币 → 成功就不再复读状态。
 */
class RecallGuard(
    private val service: QQPetService,
    private val daily: DailyStore,
    private val config: RunnerConfig,
    private val alerts: Alerts,
    private val view: RunnerView,
    private val log: (String) -> Unit,
) {
    companion object {
        /** 按需拉通知的最短间隔（定时拉在 SideTasks，10 分钟一次）。 */
        private const val NOTICE_FRESH_MS = 5 * 60_000L

        /** 外部工刚开始、待确认的那段时间里，拉通知的间隔放宽到一分钟一次（只读、便宜）。 */
        private const val UNCONFIRMED_FRESH_MS = 60_000L

        /** 待确认窗口：工开始后这么久还没等到通知，就按没有处理，不再加密拉取。 */
        private const val UNCONFIRMED_WINDOW_SEC = 30 * 60L

        /** 归属判定本体（实例 [isMine] 直接委托；独立出来是因为整套服务在 QQ 进程外构造不出来、测试只能直测这一层）：只认开工台账，在跑登记不算。 */
        internal fun isMine(storyId: String, daily: DailyStore): Boolean = daily.isMine(storyId)
    }

    sealed interface Outcome {
        data object None : Outcome
        /** 已召回（或尝试过），调用方 6s 后复查。 */
        data class Recalled(val storyId: String, val ok: Boolean) : Outcome
        /** 确认被雇但未到时机：调用方在 [atMillis] 复查。 */
        data class WaitUntil(val storyId: String, val atMillis: Long) : Outcome
    }

    /**
     * 本模块开的：只认开工台账（本模块派出的每趟都在台账里，认领领回的也会补记）。
     * **不能把在跑登记（pending）算数**：busy() 会给外部任务也登记一份在跑记录（算结束时间用），
     * 若那也算自家的，被雇工第一眼没匹配上通知（通知还没拉到/缓存新鲜期）就会被登记吞成自家工，
     * 此后每轮都在这里短路、再也不确认——71% 召回整趟失效（2026-10-31 图图报告定因）。
     */
    fun isMine(st: StoryStatus): Boolean = isMine(st.storyId, daily)

    /** 上一轮看到的在跑任务：它被换掉/结束时，给它的通知要一并作废（见 [spendNoticesOfPrevious]）。 */
    private var lastStory: StoryStatus? = null

    /** 确认来源：通知确认（被雇）/ 外部打工开关（用户自担，含亲手开的工）。 */
    enum class Source { NOTICE, EXTERNAL }

    /** 已经为哪份工记过“拉了通知也没匹配上”的诊断（每份工只记一次，免得刷屏）。 */
    private val noMatchLogged = mutableSetOf<String>()

    /**
     * 这份工按什么口径算“该召回/该提醒”的：
     *  1. 先认通知（0x994a 的“雇佣了”落在时间窗内）——服务器明说的被雇；
     *  2. 通知缓存旧了就现拉一次。**确认用途的拉取不受「检查是否被雇佣」开关限制**：
     *     那个开关只管定时轮询（提醒用）；以前共用一个开关，关掉它的人自动召回会
     *     永远确认不了、还一点日志都没有（2026-11-03 群友 NewWay 的日志形态正是如此）；
     *  3. 用户开了「不是我开的打工也召回」时，不是本模块开的打工直接算数，不等通知。
     */
    fun confirmSource(st: StoryStatus): Source? {
        if (isMine(st) || !st.workingState || st.remainingSeconds <= 0) return null
        if (matchNotice(st) != null) return Source.NOTICE
        val now = System.currentTimeMillis()
        val freshMs = if (st.elapsedSeconds in 0..UNCONFIRMED_WINDOW_SEC) UNCONFIRMED_FRESH_MS else NOTICE_FRESH_MS
        if (now - view.hiredNoticesAt >= freshMs) {
            val r = PetResult.read { service.friend.hiredNotices() }
            view.hiredNoticesAt = System.currentTimeMillis()
            r.getOrNull()?.let { view.hiredNotices = it } ?: log("📬 读通知失败：${r.describe()}")
            if (matchNotice(st) != null) return Source.NOTICE
            logNoMatchOnce(st)
        }
        if (config.recallExternalOn && OutingKind.ofStoryId(st.storyId) == OutingKind.WORK) return Source.EXTERNAL
        return null
    }

    /** 是否确认被雇佣（只认通知口径，外部开关不算）。 */
    fun hired(st: StoryStatus): Boolean = confirmSource(st) == Source.NOTICE

    /**
     * 拉了通知也没能确认这份外部工时，在日志里留一行证据（每份工一次）：通知列表里
     * 到底有没有雇佣通知、最近的一条是什么时刻——下次再有人报“没认出被雇”，
     * 看当天日志就能分清是通知没来、时刻对不上，还是文案变了。
     */
    private fun logNoMatchOnce(st: StoryStatus) {
        if (!noMatchLogged.add(st.storyId)) return
        val notices = view.hiredNotices
        val startMs = if (st.startedAt > 0) st.startedAt * 1000 else System.currentTimeMillis() - st.elapsedSeconds * 1000
        val nearest = notices.minByOrNull { kotlin.math.abs(it.atMillis - startMs) }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val nearText = nearest?.let { "最近的一条雇佣通知是 ${fmt.format(java.util.Date(it.atMillis))}（${it.employer.ifBlank { "好友" }}）" } ?: "通知列表里一条雇佣通知都没有"
        log("📬 外部打工 ${st.storyId} 待确认：刚拉的通知里没有落在它时间窗内的雇佣通知（共 ${notices.size} 条，$nearText；这份工 ${fmt.format(java.util.Date(startMs))} 开始）")
    }

    /**
     * 找一条能确认 [st] 被雇佣的通知（时间窗 + 未被别的任务认领，口径见 [HireDetect.match]）；
     * 找到就把这条通知绑到 [st] 上——它这一天只认这一份工。
     */
    private fun matchNotice(st: StoryStatus): HiredNotice? {
        val m = HireDetect.match(st, false, view.hiredNotices, daily.hireNoticeBinds(), System.currentTimeMillis()) ?: return null
        daily.bindHireNotice(HireDetect.noticeKey(m), st.storyId)
        return m
    }

    /**
     * 当前任务与上一轮不同（上一份被召回/干完/被换掉）时，把上一份工窗口内还没被认领的通知
     * 绑到上一份工上作废：那些通知本来就是冲着它来的。确认那一刻可能已经错过
     * （上一份工在两轮之间就被用户在官方界面召回了），所以要在观察到切换时补绑，
     * 否则旧通知会落进下一份工的窗口把新工误判成被雇佣（2026-10-23 真机）。
     */
    private fun spendNoticesOfPrevious(story: StoryStatus?) {
        val prev = lastStory ?: return
        if (story != null && story.storyId == prev.storyId) return
        val now = System.currentTimeMillis()
        HireDetect.spendable(prev, view.hiredNotices, daily.hireNoticeBinds(), now)
            .forEach { daily.bindHireNotice(HireDetect.noticeKey(it), prev.storyId) }
        lastStory = null
    }

    fun handle(story: StoryStatus?): Outcome {
        spendNoticesOfPrevious(story)
        if (story != null && story.remainingSeconds > 0) lastStory = story
        val source = story?.takeIf { !daily.isSettled(it.storyId) }?.let { confirmSource(it) }
        val isHired = source != null
        view.hiredNow = isHired
        remind(story, source)
        if (story == null || !isHired || !config.recallEnabled) return Outcome.None
        val label = if (source == Source.EXTERNAL) "外部打工" else "被雇打工"
        if (TaskManager.shouldRecall(story, config.recallMode)) {
            val r = PetResult.write { service.story.recall(story.storyId) }
            val o = r.getOrNull()
            val ok = if (o != null && (o.recallGold > 0 || o.partial || !o.empty)) true else {
                val after = PetResult.read { service.story.status() }.getOrNull()
                r.ok && (after == null || after.storyId != story.storyId || after.remainingSeconds <= 0)
            }
            daily.markSettled(story.storyId)          // 无论成败都去重，避免对同一条死循环
            if (ok) {
                daily.clearPending(); daily.increment("recall"); alerts.clear(Alerts.RECALL)
                if ((o?.gold ?: 0) > 0) daily.setCount("recall_gold", daily.count("recall_gold") + (o?.gold ?: 0).toInt())
            }
            log(if (ok) "🔄 $label 召回 ${story.storyId}（策略=${config.recallMode} 进度=${pct(story)}%）→ 到账 ${o?.gold ?: "?"} 金币${if (o?.partial == true) "（${o.notice}）" else ""}"
            else "⚠️ 召回未确认生效：${r.describe()}，不再重试该 storyId")
            return Outcome.Recalled(story.storyId, ok)
        }
        val toSplit = (story.durationSeconds * StoryStatus.RECALL_AT_PROGRESS - story.elapsedSeconds).coerceAtLeast(1.0)
        val waitMs = if (story.durationSeconds > 0) toSplit.toLong() * 1000 + 5_000 else 60_000L
        log("⏳ $label ${story.storyId} 进度=${pct(story)}%，${waitMs / 1000}s 后到 71% 再召回")
        return Outcome.WaitUntil(story.storyId, System.currentTimeMillis() + waitMs)
    }

    /** 提醒（开关 hire_alert_on）：已确认（被雇，或外部打工开关下的外部工）、自动召回没开、进度到 71% 时提醒去 QQ 点召回。 */
    private fun remind(story: StoryStatus?, source: Source?) {
        val want = story != null && source != null && config.hireReminder && !config.recallEnabled
        if (!want || story == null) {
            if (daily.recallNotifiedFor().isNotEmpty()) daily.markRecallNotified("")
            alerts.clear(Alerts.RECALL)
            return
        }
        if (!story.shouldRemindRecall()) return
        val text = if (source == Source.EXTERNAL)
            "宠物在外打工（不是 Autoqqpet 开的），进度 ${pct(story)}% 已到你设定的召回区间，去 QQ 点『召回』（还剩约 ${story.remainingSeconds / 60} 分钟）。"
        else
            "宠物被好友雇去打工，进度 ${pct(story)}% 已进入分成最高的区间，去 QQ 点『召回』（还剩约 ${story.remainingSeconds / 60} 分钟）。"
        if (daily.recallNotifiedFor() != story.storyId) { daily.markRecallNotified(story.storyId); log("🔔 $text") }
        alerts.set(Alerts.RECALL, text, incident = story.storyId)
    }

    private fun pct(st: StoryStatus) = "%.0f".format(st.progress.coerceAtLeast(0.0) * 100)
}
