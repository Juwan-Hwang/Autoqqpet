package io.github.juwanhwang.autoqqpet.hook

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.optimizer.Scheduler

/**
 * 唤醒调度：到点发一条 ACTION_TICK(action=tick, src=alarm, gen=N) 广播，驱动 AutoRunner.tick()。
 * 用进程内专用 action（不导出）而不是 ACTION_CMD：后者自 v2.0.2 起挂签名级权限，而 tick 的
 * 发送方是 QQ 进程自己（腾讯签名、拿不到该权限）——两条通道在 ApplicationHook 分开注册。
 *
 * 2026-10-16 改（真机 10-15 有 6 段 12–16 分钟停摆，都在无人操作时段 = Doze 节流；QQ 调到前台即恢复）：
 *  - **每次排程都挂一个系统闹钟**（之前 ≤5min 只走进程内 Handler：Doze 下进程线程被冻、Handler 不走，正是停摆来源）；
 *    闹钟类型按能力取最强：`setAlarmClock`（用户开了“强力唤醒”）> `setExactAndAllowWhileIdle`（有精确闹钟权限）> `setAndAllowWhileIdle`；
 *  - 短延时**同时**走 Handler 快路径（秒级准点）；两条路共用一个代号，谁先到谁跑，另一条在 TickDriver 被当旧代丢掉 →
 *    不会双触发；同一 requestCode 的闹钟每次排程都被新的替换，不会堆积。
 *  - 进程被杀时系统闹钟仍会把 QQ 拉起来（PendingIntent 属于 QQ），这是不抢前台、又能接回循环的唯一系统通道。
 */
class AlarmScheduler(private val context: Context, private val actionCmd: String) : Scheduler {

    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 唤醒代号：**两条通道共用**，每次排程递增，只有最新一代能驱动 tick（R2）。
     * 旧版只有 Handler 路径看代号：先排了一个 >5min 的 AlarmManager、后来又排了一个短的 Handler，
     * 两个都会响 → 同一轮被驱动两遍。现在：走 Handler 时先撤掉 AlarmManager；广播里带上代号，
     * 收到旧代号的闹钟直接丢弃（[isCurrent]）。
     */
    @Volatile
    var generation = 0
        private set

    /** 广播里的代号是不是当前这一代（0 = 还没排过任何唤醒，比如进程刚被闹钟拉起，放行）。 */
    fun isCurrent(gen: Int): Boolean = generation == 0 || gen == generation

    /**
     * 最近一次排定的唤醒时刻（墙钟 ms）。0 = 手上没排任何将来会醒的闹钟。
     * 心跳会把这个值带给模块 App 进程，看门狗据此判断“状态机是否已经停摆”：
     * 只看心跳新旧不够——30 分钟一期的课期间本来就没动静，会误判为挂死反复拉起 QQ。
     */
    @Volatile
    var nextWakeAt: Long = 0L
        private set

    companion object {
        private const val TAG = "Alarm"
        private const val FAST_MAX_MS = 5 * 60_000L
        private const val FAST_GRACE_MS = 3_000L
        const val EXTRA_SRC = "src"
        const val EXTRA_GEN = "gen"
        const val SRC_ALARM = "alarm"
    }

    private fun tickIntent(gen: Int): Intent = Intent(actionCmd).setPackage(context.packageName)
        .putExtra("action", "tick").putExtra(EXTRA_SRC, SRC_ALARM).putExtra(EXTRA_GEN, gen)

    private fun pi(gen: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, 1001, tickIntent(gen), flags)
    }

    /** 用户开的“强力唤醒”（setAlarmClock：Doze 下不节流，代价是状态栏闹钟图标）。由 HostRuntime 按设置更新。 */
    @Volatile var alarmClock: Boolean = false

    /** 最近一次实际用了哪种闹钟（诊断/快照用）。 */
    @Volatile var lastAlarmKind: String = ""
        private set

    @Synchronized
    override fun scheduleAt(wallMillis: Long, tag: String) {
        val delay = (wallMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val at = System.currentTimeMillis() + delay
        nextWakeAt = at   // 两条通道共用一个口径：记下排到了何时
        val my = ++generation
        if (delay <= FAST_MAX_MS) handler.postDelayed({ if (my == generation) fireTick(my) }, delay)
        // 系统闹钟兜底（短延时也挂：Doze 冻住进程时 Handler 不走）。快路径先到时 tick 会排新一代并替换掉这个闹钟。
        // 短延时的系统闹钟晚 3s：正常情况 Handler 先跑，闹钟到点时已是旧代 → 丢弃，不双跑。
        val alarmAt = if (delay <= FAST_MAX_MS) at + FAST_GRACE_MS else at
        lastAlarmKind = setAlarm(alarmAt, pi(my))
        PLog.d(TAG, "已排 tick @$tag 于 +${delay / 1000}s（gen=$my，${if (delay <= FAST_MAX_MS) "快路径+" else ""}$lastAlarmKind）")
    }

    /** @return 实际用的闹钟种类。 */
    private fun setAlarm(at: Long, op: PendingIntent): String {
        try {
            if (alarmClock && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && canExact()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), op); return "alarmClock"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op); return "exactIdle"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op); return "idle" }
            am.set(AlarmManager.RTC_WAKEUP, at, op); return "set"
        } catch (t: Throwable) {
            // SecurityException（精确闹钟权限被收回）→ 退回不精确的 allow-while-idle
            PLog.w(TAG, "精确闹钟失败（${t.javaClass.simpleName}），退回 setAndAllowWhileIdle")
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op) else am.set(AlarmManager.RTC_WAKEUP, at, op)
                "idle"
            }.getOrElse { PLog.e(TAG, "排程失败", it); "none" }
        }
    }

    /** QQ 进程能不能用精确闹钟（Android 12+ 要看 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM，在 QQ 的清单里，我们改不了，只能查）。 */
    fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

    /** 只在主线程投递广播（tick 本身由接收器交给 tick 线程执行，不会堵 UI）。 */
    private fun fireTick(gen: Int) {
        runCatching { am.cancel(pi(gen)) }   // 快路径先到：撤掉同代的兜底闹钟
        try {
            context.sendBroadcast(tickIntent(gen))
        } catch (t: Throwable) {
            PLog.e(TAG, "进程内 tick 投递失败 : ${t.message}")
        }
    }

    override fun scheduleAfter(delayMillis: Long, tag: String) =
        scheduleAt(System.currentTimeMillis() + delayMillis, tag)
}
