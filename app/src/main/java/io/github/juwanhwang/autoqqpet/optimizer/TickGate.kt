package io.github.juwanhwang.autoqqpet.optimizer

/**
 * tick 闸门（R2，真机 2026-10-15）：**不排队，只丢弃/合并**。
 *
 * 旧做法：`TICK_LOCK.tryLock()` 失败后 `lock()` 等着接上，外加 tick 单线程执行器本身也是个队列 →
 * 闹钟、auto、refresh、setopt 复查同一秒各来一发时，会一遍接一遍地全跑完（一轮实际 2~4 遍，请求量成倍）。
 *
 * 现在：
 *  - 已有一轮在跑（或已在执行器里排着）→ 本次**丢弃**，只记一个“跑完再补一次”的标记（多次请求合并成一个）；
 *  - 距上一轮开始不到 [minIntervalMs] 的**外部**请求（auto/refresh/setting/手动 tick）→ 丢弃（上一轮刚跑完并已排好下次唤醒）；
 *  - 状态机自己排的唤醒（当前代的闹钟，[exempt]=true）不受最短间隔限制，否则会把它自己的“5 秒后复查”吃掉而停摆。
 *
 * 纯逻辑、线程安全、可注入时钟（单测用）。
 */
class TickGate(
    val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MIN_INTERVAL_MS = 4_000L
    }

    enum class Verdict { RUN, MERGED, TOO_SOON }

    private var busy = false
    private var again: String? = null
    private var lastStartAt = 0L

    /** 统计（诊断用）。 */
    @Volatile var merged = 0; private set
    @Volatile var tooSoon = 0; private set

    @Synchronized
    fun offer(source: String, exempt: Boolean = false): Verdict {
        if (busy) { if (again == null) again = source; merged++; return Verdict.MERGED }
        val now = clock()
        if (!exempt && lastStartAt > 0 && now - lastStartAt in 0 until minIntervalMs) { tooSoon++; return Verdict.TOO_SOON }
        busy = true; lastStartAt = now
        return Verdict.RUN
    }

    /** 一轮结束。@return 期间被合并掉的第一个请求来源（null = 没人等），调用方据此**排一次**补跑，而不是立刻接着跑。 */
    @Synchronized
    fun done(): String? { busy = false; return again.also { again = null } }

    @get:Synchronized val running: Boolean get() = busy
}

/**
 * 防抖（R2：连续 setopt 合并）：最后一次变更后安静 [quietMs] 才放行一次；持续不断的变更最迟 [maxWaitMs] 也放行一次。
 * 纯逻辑：[note] 记一次变更并返回“应在何时检查”，[fireIfDue] 到点时判断是否真该放行（放行后清空）。
 */
class Debouncer(
    private val quietMs: Long,
    private val maxWaitMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var firstAt = 0L
    private var lastAt = 0L
    private var count = 0

    /** @return 建议的检查时刻（墙钟 ms）。 */
    @Synchronized
    fun note(): Long {
        val now = clock()
        if (count == 0) firstAt = now
        lastAt = now; count++
        return dueAt()
    }

    @Synchronized
    private fun dueAt(): Long = minOf(lastAt + quietMs, firstAt + maxWaitMs)

    /** @return 合并掉的变更条数（>0 = 该放行一次），未到点返回 0。 */
    @Synchronized
    fun fireIfDue(): Int {
        if (count == 0 || clock() < dueAt()) return 0
        val n = count
        count = 0; firstAt = 0; lastAt = 0
        return n
    }

    @get:Synchronized val pending: Int get() = count
}

/**
 * “连不上”判定（R4）。只有**读请求整轮都失败**（一次成功的读都没有，且是超时/传输层失败或 READY 读不到）才算坏的一轮；
 * 写请求空回包/没回包一律不算（重复写服务端会回空包，这是已知行为，不代表会话断了）。连续 [threshold] 个坏轮才报。
 */
class NetHealth(private val threshold: Int = 2, private val clock: () -> Long = System::currentTimeMillis) {
    var fails = 0; private set
    @Volatile var staleAt = 0L; private set

    /** @return true = 本次刚刚跨过阈值（调用方据此记一次日志）。 */
    @Synchronized
    fun round(bad: Boolean): Boolean {
        if (!bad) { fails = 0; staleAt = 0L; return false }
        fails++
        if (fails >= threshold && staleAt == 0L) { staleAt = clock(); return true }
        return false
    }

    companion object {
        /** 一轮是不是“读全挂了”：本轮至少一次读失败（超时/传输层/未就绪），且没有任何一次读成功。 */
        fun isBadRound(readOk: Int, readFail: Int, notReady: Boolean): Boolean =
            readOk == 0 && (readFail > 0 || notReady)

        /** 异常/结果里像网络问题的文案（只对读有意义）。 */
        fun looksNet(msg: String?): Boolean =
            msg != null && (msg.contains("回调超时") || msg.contains("transport code") || msg.contains("返回空响应"))
    }
}

/**
 * 状态字符串唯一出口（R5）：通知、面板、快照读到的都是这一种格式。来源（启动/续跑/闹钟）**不进状态串**，只进日志。
 *
 * 格式（CONTRACT §2.1 `state`）：
 *  `started:<study|work|adventure>:<名>` · `busy:<storyId> 剩<N>s` · `recalled:<sid>` · `recall-wait:<sid>` ·
 *  `idle:<原因>` · `care-feed`/`care-bath` · `not-ready` · `net-retry:<原因>`（读超时，稍后重试） ·
 *  `error:<原因>`（真异常） · `skipped:<原因>`（本次请求被闸门丢弃，**不覆盖**上一个状态）。
 */
object TickStatus {
    private val PREFIXES = listOf("启动·", "续跑·", "重建·")

    /** 去掉历史遗留的来源前缀（旧版本、旧快照里可能还有）。 */
    fun normalize(raw: String): String {
        var s = raw.trim()
        PREFIXES.forEach { p -> if (s.startsWith(p)) s = s.removePrefix(p).trim() }
        return s
    }

    fun error(msg: String?): String = "error:${(msg ?: "未知").take(60)}"
    fun netRetry(msg: String?): String = "net-retry:${(msg ?: "读超时").take(60)}"
    fun skipped(why: String): String = "skipped:$why"

    /** 被丢弃的请求不应改写对外状态。 */
    fun isSkip(s: String): Boolean = s.startsWith("skipped:")
}
