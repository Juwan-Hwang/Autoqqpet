package io.github.juwanhwang.autoqqpet.hook

import android.os.Handler
import android.os.Looper
import io.github.juwanhwang.autoqqpet.hook.cmd.Workers
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.optimizer.AutoRunner
import io.github.juwanhwang.autoqqpet.optimizer.Debouncer
import io.github.juwanhwang.autoqqpet.optimizer.TickGate
import io.github.juwanhwang.autoqqpet.optimizer.TickStatus

/**
 * tick 的**唯一入口**（R2/R5，真机 2026-10-15）。所有驱动源都从这里过：
 *
 * | 来源 | 何时 | 是否受最短间隔限制 |
 * |---|---|---|
 * | `alarm` | 状态机自己排的唤醒（AlarmScheduler，带代号） | 否（当前代）；旧代直接丢 |
 * | `tick` | 不带代号的 tick 广播（adb/旧版本挂着的闹钟） | 是 |
 * | `auto` | 界面开托管、看门狗补发 | 是 |
 * | `refresh` | 界面“立即刷新” | 是 |
 * | `setting` | 改设置后的复查（**防抖合并**，见 [settingChanged]） | 是 |
 * | `resume`/`heal`/`merged` | QQ 启动续跑 / 换 ClassLoader 后复查 / 合并后的补跑 | resume/heal 是，merged 否 |
 *
 * 规则：一轮在跑（或已在 tick 线程排着）时再来的请求一律**丢弃**，最多留一个“跑完补一次”的标记 →
 * 结束后**排**一次补跑（经 Scheduler，受代号管），而不是立刻接着再跑一遍。
 * 状态广播也只在这里发（单一生产者）：被丢弃的请求不改写状态。
 */
object TickDriver {
    private const val TAG = "TickDriver"
    /** 改设置后安静多久才复查（UI 侧另有 600ms 防抖，这里兜后端：连续 setopt 合并成一次）。 */
    const val SETTING_QUIET_MS = 8_000L
    const val SETTING_MAX_WAIT_MS = 60_000L

    val gate = TickGate()
    /** 已驱动过的闹钟代号（同代两条通道只跑一次）。 */
    @Volatile private var lastRunGen = 0
    /** 这些来源只是“补一轮”：状态机手上有新鲜排程时不另起。 */
    private val PASSIVE = setOf("watchdog", "auto-resend", "tick")
    /** 最近一轮超过这么久没跑过，就算排程看着还在也放行补一轮（排程可能被系统吞了）。 */
    private const val STALE_TICK_MS = 20 * 60_000L
    private const val WAKE_TIMEOUT_MS = 90_000L

    /**
     * tick 期间在 QQ 进程里持一个短 PARTIAL_WAKE_LOCK（≤90s，跑完即放）：闹钟把 CPU 叫醒后，
     * 系统只保证 onReceive 那一下不睡；后面的网络读写要十几秒，中途进 Doze 就会出现“回调超时”。QQ 自己声明了 WAKE_LOCK。
     */
    private fun acquireWake(): android.os.PowerManager.WakeLock? = runCatching {
        val ctx = HostRuntime.appContext() ?: return null
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "autoqqpet:tick").apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT_MS) }
    }.onFailure { PLog.w(TAG, "tick 唤醒锁获取失败：${it.message}") }.getOrNull()
    private val settings = Debouncer(SETTING_QUIET_MS, SETTING_MAX_WAIT_MS)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val settingCheck = Runnable { fireSettingIfDue() }

    /**
     * 请求跑一轮。[gen] 只对 `alarm` 有意义。[after] 在 tick 线程上、拿到结果后调用（日志用）。
     * @return 是否真的会跑（false = 被丢弃/合并）。
     */
    fun request(source: String, gen: Int = -1, after: (String) -> Unit = {}): Boolean {
        if (!HostRuntime.autoEnabled) { PLog.i(TAG, "⏸ auto 未开启，$source 忽略"); return false }
        val alarm = source == AlarmScheduler.SRC_ALARM
        val sch = HostRuntime.scheduler as? AlarmScheduler
        if (alarm) {
            if (sch != null && !sch.isCurrent(gen)) { PLog.d(TAG, "丢弃旧代闹钟 gen=$gen（当前 ${sch.generation}）"); return false }
            // 同一代既有 Handler 快路径又有系统闹钟兜底：先到的跑，后到的丢（A：单一触发源）
            if (gen > 0 && gen == lastRunGen) { PLog.d(TAG, "同代 gen=$gen 已由另一条通道驱动过，丢弃"); return false }
        }
        // 外部“补一轮”（看门狗/auto 补发）：状态机自己已排好未到期的唤醒 → 不另起一轮（D2：消除 auto+alarm 同秒双跑）
        if (source in PASSIVE && !gate.running) {
            val next = sch?.nextWakeAt ?: 0L
            val now = System.currentTimeMillis()
            if (next > now && HostRuntime.runner != null && now - (HostRuntime.runner?.view?.lastTickAt ?: 0L) < STALE_TICK_MS) {
                PLog.i(TAG, "⏭ $source：状态机已排 ${(next - now) / 1000}s 后的唤醒，不另起一轮")
                return false
            }
        }
        when (gate.offer(source, exempt = alarm || source == "merged")) {
            TickGate.Verdict.MERGED -> { note("⏭ $source：已有一轮在跑/排着，丢弃（跑完补一次）"); return false }
            TickGate.Verdict.TOO_SOON -> { note("⏭ $source：距上一轮不足 ${gate.minIntervalMs / 1000}s，丢弃（上一轮已排好下次唤醒）"); return false }
            TickGate.Verdict.RUN -> Unit
        }
        if (alarm && gen > 0) lastRunGen = gen
        Workers.tick.execute {
            var res = "no-runner"
            val wl = acquireWake()
            try {
                res = HostRuntime.runner?.tick() ?: "no-runner"
                after(res)
                PLog.i(TAG, "⏱ [$source] tick → $res")
                if (!TickStatus.isSkip(res)) HostRuntime.sendStatus(res)
            } catch (t: Throwable) {
                PLog.e(TAG, "tick 驱动异常", t)
            } finally {
                runCatching { if (wl?.isHeld == true) wl.release() }
                val mergedFrom = gate.done()
                val dropped = AutoRunner.consumeDropped()
                if (mergedFrom != null || dropped) {
                    // 排一次补跑（受代号管）：会顶掉本轮刚排的唤醒，但补跑那一轮会重新排，所以不会丢唤醒
                    runCatching { HostRuntime.scheduler?.scheduleAfter(gate.minIntervalMs, "merged:${mergedFrom ?: "lock"}") }
                    PLog.i(TAG, "↻ 期间有请求被合并（${mergedFrom ?: "lock"}），${gate.minIntervalMs / 1000}s 后补跑一次")
                }
            }
        }
        return true
    }

    /**
     * 改了会影响决策的设置（R2）：**不立刻跑**，也不顶掉状态机自己排的唤醒；
     * 最后一次改动后安静 [SETTING_QUIET_MS] 再复查一次（连续改 8 次 = 复查 1 次）。
     */
    fun settingChanged(reason: String) {
        if (!HostRuntime.autoEnabled) return
        val at = settings.note()
        PLog.i(TAG, "⚡ 设置已变（$reason）→ 合并中（已 ${settings.pending} 项），安静 ${SETTING_QUIET_MS / 1000}s 后复查一轮")
        main.removeCallbacks(settingCheck)
        main.postDelayed(settingCheck, (at - System.currentTimeMillis()).coerceAtLeast(200L))
    }

    private fun fireSettingIfDue() {
        val n = settings.fireIfDue()
        if (n == 0) {
            if (settings.pending > 0) main.postDelayed(settingCheck, 1_000L)
            return
        }
        // 状态机自己的唤醒就在眼前（≤ 安静期），复查没意义：它醒来就按新设置跑
        val next = (HostRuntime.scheduler as? AlarmScheduler)?.nextWakeAt ?: 0L
        if (next > 0 && next - System.currentTimeMillis() in 0..SETTING_QUIET_MS) {
            PLog.i(TAG, "⚡ $n 项设置变更已合并；下一轮 ${(next - System.currentTimeMillis()) / 1000}s 后本来就会跑，不另起")
            return
        }
        request("setting") { PLog.i(TAG, "⚡ 合并 $n 项设置变更后复查一轮") }
    }

    private fun note(msg: String) {
        PLog.i(TAG, msg)
        runCatching { HostRuntime.daily?.pushEvent(msg) }
    }
}
