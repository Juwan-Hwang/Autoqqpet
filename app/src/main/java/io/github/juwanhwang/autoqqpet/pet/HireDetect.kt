package io.github.juwanhwang.autoqqpet.pet

/**
 * “我是不是被好友雇走了”的判定（纯函数，可单测）。依据全部来自 2026-10-15 01:45 被雇实测（交接 §9.10）：
 *  1. 被雇时我方**不发任何包**，任务在 0x975a 里是 `state=51`、storyId 仍是 `6400_`（**前缀不能区分**）；
 *  2. 服务器发一条站内通知（0x994a：“01:45 雇佣了 <昵名>”），但**要去拉**，不是推送；
 *  3. 结算/召回回包的故事名带“（被雇佣）”。
 * 自己开的打工同样是 51 → 51 只是必要条件；确认要靠 2 或 3。
 *
 * 通知只按时间窗认人会认错（2026-10-23 真机）：15:14 的雇佣通知属于一份 4 小时工，
 * 用户在官方界面手动召回后 15:17 自己开了一份新工，那条通知正好落进新工的时间窗，
 * 新工被误确认成被雇佣。所以通知还要与任务**绑定**：一条通知一生只认它确认过的那份工，
 * 那份工结束或被换掉后这条通知即作废（绑定由 DailyStore 按天持久化，重启不复活）。
 */
object HireDetect {

    /** 通知时刻允许早于任务开始的余量（服务端先写任务后发通知，或时钟有偏差）。 */
    private const val SLACK_MS = 3 * 60_000L

    /** 一条通知的稳定身份（重复拉取同一条不变）：时刻 + 场景 + 雇主。 */
    fun noticeKey(n: HiredNotice): String = "${n.atMillis}:${n.scene}:${n.employer}"

    /** [st] 的确认时间窗（毫秒）：开始前留 [SLACK_MS] 余量，到任务结束为止。 */
    private fun windowMs(st: StoryStatus, nowMs: Long): LongRange {
        val start = if (st.startedAt > 0) st.startedAt * 1000 else nowMs - st.elapsedSeconds * 1000
        val end = start + (if (st.durationSeconds > 0) st.durationSeconds else 4 * 3600L) * 1000
        return (start - SLACK_MS)..end
    }

    /** 这条通知的时刻是否落在 [st] 的时间窗内（只看时间，不看绑定）。 */
    fun matchesWindow(st: StoryStatus, n: HiredNotice, nowMs: Long): Boolean = n.atMillis in windowMs(st, nowMs)

    /**
     * 为 [st] 找一条能确认它被雇佣的通知：时刻落在窗内，且这条通知没被绑给别的任务
     * （[binds] = 通知身份 → 它认过的 storyId）。已绑给 [st] 自己的通知可以继续确认它
     * （同一份工每轮都要能确认到）；绑给别人的、或已被上一份工作废的，都不算。
     * @param mine 这条任务是不是本模块开的（本地台账）
     * @param nowMs 当前时刻（startedAt 缺省时用 “now − 总时长” 推开始时间）
     */
    fun match(st: StoryStatus, mine: Boolean, notices: List<HiredNotice>, binds: Map<String, String>, nowMs: Long): HiredNotice? {
        if (mine || !st.workingState || st.remainingSeconds <= 0) return null
        return notices.firstOrNull { n ->
            matchesWindow(st, n, nowMs) && (binds[noticeKey(n)].let { it == null || it == st.storyId })
        }
    }

    /** 不带绑定口径的旧确认（只看时间窗）：仅供对照，新代码用 [match]。 */
    fun confirmed(st: StoryStatus, mine: Boolean, notices: List<HiredNotice>, nowMs: Long): Boolean =
        match(st, mine, notices, emptyMap(), nowMs) != null

    /**
     * [prev] 这份工在跑时落进它窗口、且还没绑给任何人的通知：它被换掉/结束时，
     * 这些通知应当绑到它身上作废——它们本来就是冲着这份工来的，不能留给下一份工认领。
     */
    fun spendable(prev: StoryStatus, notices: List<HiredNotice>, binds: Map<String, String>, nowMs: Long): List<HiredNotice> =
        notices.filter { n -> binds[noticeKey(n)] == null && matchesWindow(prev, n, nowMs) }

    /** 结算名里带“被雇佣”（弱匹配：防改版改字，只认子串）。 */
    fun fromOutcome(o: StoryOutcome): Boolean = o.hiredSide
}
