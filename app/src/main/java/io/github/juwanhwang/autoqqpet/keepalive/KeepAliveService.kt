package io.github.juwanhwang.autoqqpet.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.R
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.store.SettingKeys

/**
 * 强保活前台服务：
 *  - 持 PARTIAL_WAKE_LOCK，防 CPU 深度睡眠 → QQ 侧闹钟准点触发；
 *  - 常驻通知实时显示：目标属性 / 当前在做什么 / 是否正常 / 距上次心跳；
 *  - 看门狗：QQ 心跳断了（进程被杀/冻结）或状态机没排下次唤醒（停摆）→ 救活循环。
 *
 * 真机实测（One UI / Android 14）：后台 `startActivity` 会被系统**静丢弃**（不报错）。
 * 2026-10-13 起本服务**彻底不做前台拉起**（用户定的：任何时候都不能抢用户屏幕），
 * 救活只用静默唤进程（查 provider）+ 补发广播 + 必要时发通知。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "autoqqpet_status"
        const val NOTIF_ID = 1001
        const val ALERT_CHANNEL_ID = "autoqqpet_alert"
        const val ALERT_NOTIF_ID = 1002
        /** “该召回了”这类需用户动手的提醒（由 QQ 进程随心跳带上来，任务不在了自动掉） */
        const val REMINDER_NOTIF_ID = 1003
        /** 火花“今晚熄灭”（在燃但今天没双向互踩）——独立渠道与通知 id。 */
        const val SPARK_TONIGHT_CHANNEL_ID = "autoqqpet_spark_tonight"
        const val SPARK_TONIGHT_NOTIF_ID = 1005
        /** 火花“快清零”（待重燃 ≥5 天）——独立渠道与通知 id，与上一条分开开关。 */
        const val SPARK_CLEAR_CHANNEL_ID = "autoqqpet_spark_clear"
        const val SPARK_CLEAR_NOTIF_ID = 1006
        const val ACTION_REFRESH = "io.github.juwanhwang.autoqqpet.REFRESH"
        private const val TAG = "KeepAlive"
        private const val QQ = "com.tencent.mobileqq"
        private const val ACTION_CMD = "io.github.juwanhwang.autoqqpet.CMD"
        private const val STALE_SEC = 180   // QQ 侧每 60s 一帧心跳，超 3 分钟无心跳视为进程没了
        /** 排定的唤醒过期多久仍没跑就算“被 Doze 吞了”（旧版 180s；10-15 停摆 12–16 分钟，缩到 90s 尽早补）。 */
        private const val OVERDUE_SEC = 90
        const val ACTION_CHECK = "io.github.juwanhwang.autoqqpet.WATCHDOG_CHECK"
        private const val REVIVE_COOLDOWN_MS = 90_000L   // 补发指令的节流，别每 15s 一次
        private const val SILENT_RETRY_MS = 45_000L      // 静默唤醒的起始间隔，也是“给它多久回应”的等待窗口
        private const val MAX_SILENT_RETRY_MS = 30 * 60_000L   // 退避封顶
        /** 放在进程级：服务被系统重建时不会把同一次事故再提醒一遍。 */
        @Volatile private var incidentAlerted = false

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: IllegalStateException) {
                // Android 12+ 在后台广播(StatusReceiver 心跳)里调 startForegroundService 会抛
                // ForegroundServiceStartNotAllowedException(ISAE 子类)。服务的正当拉起入口是
                // MainActivity(前台)与 BootReceiver(开机/更新, 系统豁免)，此处仅尽力而为，忽略以免崩掉进程。
            }
        }

        fun refresh(ctx: Context) {
            ctx.sendBroadcast(Intent(ACTION_REFRESH).setPackage(ctx.packageName))
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    private var lastReviveAt = 0L
    private var lastSilentWakeAt = 0L
    private var reviveAttempts = 0
    /**
     * 本次“出事”是否已经发过红色提醒。**一次事故只发一次**：用户划掉就是看到了，不再重发；
     * 只有循环真正恢复健康（[loopHealthy]）才清零，下次再出事才会再提醒。
     * 修 2026-10-14 用户反馈：QQ 退出登录/不在线时，“连不上”通知隔一会就弹一次。
     */
    private var alertRaisedThisIncident: Boolean
        get() = incidentAlerted
        set(v) { incidentAlerted = v }

    /**
     * 静默唤醒的退避间隔：45s → 90s → 3min → 6min → … 封顶 30min。
     * QQ 退出登录时唤多少次都没用，每 45s 唤一次只会白耗电、刷日志。
     */
    private fun silentRetryMs(): Long {
        val shift = (reviveAttempts - 1).coerceIn(0, 6)
        return (SILENT_RETRY_MS shl shift).coerceAtMost(MAX_SILENT_RETRY_MS)
    }
    private var lastReminder = ""
    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            watchdog()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoqqpet:keepalive")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(10 * 60 * 1000L /*10min cap, 循环续期*/)
        startForeground(NOTIF_ID, build("启动中…", false))
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wakeLock.let { if (!it.isHeld) it.acquire(10 * 60 * 1000L) }
        updateNotification()
        // 模块侧兜底闹钟到点：Doze 下 Handler 可能没走，这里补跑一次看门狗（只会补发 tick，不碰界面）
        if (intent?.action == ACTION_CHECK) runCatching { watchdog() }
        return START_STICKY
    }

    /**
     * 模块侧兜底闹钟（A）：在 QQ 排的下次唤醒之后 [OVERDUE_SEC] 秒醒一次做看门狗检查。
     * 用不需要权限的 setAndAllowWhileIdle（Doze 下也会放行，只是不精确）；同一个 PendingIntent 每次替换，不堆积。
     * QQ 自己的闹钟准点时这次检查什么都不做（循环健康）。
     */
    private fun armOverdueCheck() {
        val next = StatusStore.nextWakeAt
        val now = System.currentTimeMillis()
        if (!StatusStore.autoOn || next <= 0L) return
        val at = maxOf(next + OVERDUE_SEC * 1000L, now + 60_000L)
        if (at == lastCheckArmedAt) return
        lastCheckArmedAt = at
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = PendingIntent.getService(this, 31, Intent(this, KeepAliveService::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable())
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { PLog.w(TAG, "兜底检查闹钟排程失败：${it.message}") }
    }
    private var lastCheckArmedAt = 0L

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L)
        armOverdueCheck()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ok = loopHealthy()
        nm.notify(NOTIF_ID, build(StatusStore.status, ok))
        if (ok) nm.cancel(ALERT_NOTIF_ID)   // 已接回循环就把红色提醒掉，不积垃圾通知
        syncReminder(nm)
        syncSparkAlerts(nm)
    }

    /** 每条火花提醒上次发出的文本与日号（一天只响一次；同文本不重发；划掉后当天不再推）。 */
    private val sparkPosted = HashMap<Int, Pair<String, Long>>()

    /**
     * 火花两种提醒走各自渠道：`spark_tonight`（今晚熄灭）与 `spark`（快清零）。来源是 QQ 侧快照的 `alerts`；
     * 槽里没有了就撤通知（互踩完 / 过午夜）。一天之内：第一次发出会响；之后文本变化只在通知仍挂着时静默更新。
     */
    private fun syncSparkAlerts(nm: NotificationManager) {
        val alerts = runCatching { org.json.JSONObject(StatusStore.snapshotJson).optJSONArray("alerts") }.getOrNull()
        val texts = HashMap<String, String>()
        if (alerts != null) for (i in 0 until alerts.length()) {
            val o = alerts.optJSONObject(i) ?: continue
            texts[o.optString("id")] = o.optString("text")
        }
        postSpark(nm, SPARK_TONIGHT_NOTIF_ID, SPARK_TONIGHT_CHANNEL_ID, "火花今晚会熄灭", texts["spark_tonight"])
        postSpark(nm, SPARK_CLEAR_NOTIF_ID, SPARK_CLEAR_CHANNEL_ID, "火花快要清零了", texts["spark"])
    }

    private fun postSpark(nm: NotificationManager, id: Int, channel: String, title: String, text: String?) {
        if (text.isNullOrBlank()) { nm.cancel(id); return }
        val day = System.currentTimeMillis() / 86_400_000L
        val last = sparkPosted[id]
        if (last != null && last.first == text) return
        val showing = runCatching { nm.activeNotifications.any { it.id == id } }.getOrDefault(false)
        if (last != null && last.second == day && !showing) return   // 今天已经响过且被划掉了：不再推
        sparkPosted[id] = text to day
        val launch = tapLaunchIntent()
        val pi = launch?.let { PendingIntent.getActivity(this, id, it, PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable()) }
        nm.notify(
            id,
            NotificationCompat.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build(),
        )
    }

    /** 同步“需用户动手”的提醒（如被好友雇佣该召回了）：有文本就挂通知，没文本就收掉。 */
    private fun syncReminder(nm: NotificationManager) {
        val text = StatusStore.alertText
        if (text.isEmpty()) { nm.cancel(REMINDER_NOTIF_ID); lastReminder = ""; return }
        // 文本没变就不重发：高优先级通道每 15 秒重发会被部分 ROM 反复响铃/置顶
        if (text == lastReminder) return
        lastReminder = text
        val launch = tapLaunchIntent()
        val pi = launch?.let {
            PendingIntent.getActivity(this, 22, it, PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable())
        }
        // 标题跟着内容走：这个通道既报“疑似被雇佣”，也报“今日疲劳”，不能一律写成被雇佣
        val title = when {
            text.contains("雇佣") -> "疑似被好友雇佣，该去召回了"
            text.contains("疲劳") -> "宠物今天疲劳了"
            else -> "Autoqqpet 提醒"
        }
        nm.notify(
            REMINDER_NOTIF_ID,
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n点本通知打开${tapTargetName()}。"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setOngoing(false)
                .apply { if (pi != null) setContentIntent(pi) }
                .build(),
        )
    }

    /**
     * 循环健康判据（两个都得成立）：
     *  1) 心跳新鲜 → QQ 进程还活着（auto 开着时每 60s 一帧，与任务长短无关）；
     *  2) 状态机手上有未到期的唤醒计划（给 3 分钟 Doze 余量）→ 它还会自己醒回来。
     * 只看心跳新旧不够：30 分钟的课期间本来就有心跳空白；而 2026-10-09 05:18 那次是 QQ 好好活着
     * 但 tick 走完没排下次唤醒，循环已经死了，得靠第 2 条才能看出来。
     */
    private fun loopHealthy(): Boolean =
        StatusStore.sinceHeartbeatSec() in 0 until STALE_SEC && StatusStore.untilWakeSec() > -OVERDUE_SEC &&
            StatusStore.netBadSec() == 0L

    /**
     * 静默唤醒 QQ：查一下 QQ 导出的 OpenApiProvider 只会把主进程拽起来，不弹任何界面。
     * 真机取证（2026-10-09 23:14）：这样起来的进程里模块照常加载、只读探针能收发协议、续跑 tick 能开任务，
     * 而窗口焦点全程不是 QQ。先试它，别一上来就抢用户前台。
     * provider 初始化可能要同步等 QQ 冷启动数秒，所以丢到子线程做，不堵服务主线程。
     * 补发的 auto 广播可能比进程就绪更早到（动态接收器还未注册）而丢失；不影响：
     * QQ 启动时发现自动模式开着会自己“♻️ 续跑 tick”接回循环（已真机验）。
     */
    private fun wakeQqSilently() {
        Thread {
            val ok = runCatching {
                contentResolver.query(Uri.parse("content://$QQ.openapi.provider"), null, null, null)?.close()
                true
            }.getOrElse {
                PLog.w(TAG, "🌙 静默唤醒 QQ 失败: ${it.message?.take(70)}")
                false
            }
            PLog.i(TAG, "🌙 静默唤醒 QQ（查 provider 只唤进程）→ $ok")
        }.apply { name = "Autoqqpet-WakeQq"; isDaemon = true; start() }
    }

    /**
     * 看门狗：auto 开着但循环不健康 → **只做不打扰用户的动作**。
     *
     * 用户 2026-10-13 定死：**任何时候都不允许把 QQ 拉到前台抢用户屏幕**，所以原先
     * “静默唤不回 → 退化为抢前台”那一整段已删除（连 `startActivity` 路径一并抽掉）。
     * 剩下的救活手段只有两种：静默唤进程（查 provider）、补发 auto 广播；实在需要人动手时只发通知。
     */
    private fun watchdog() {
        val sp = getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE)
        // auto_on 的真值在 QQ 进程自己的数据目录里（autoqqpet_settings），本进程读不到；
        // 只看 StatusReceiver 每帧镜像回来的这份 + UI 本地记忆。曾在这里读到默认 false，导致看门狗从未触发过。
        val auto = sp.getBoolean(ModulePrefs.KEY_AUTO_ON, false) || StatusStore.autoOn
        StatusStore.autoOn = auto
        if (!auto) return
        if (loopHealthy()) {
            reviveAttempts = 0
            alertRaisedThisIncident = false
            // 循环一恢复就撤销那条红色提醒：它只该在真需要人动手时挂着
            cancelAlertIfShowing()
            return
        }
        val now = System.currentTimeMillis()
        val attr = sp.getString(ModulePrefs.KEY_ATTR, "力量") ?: "力量"
        val since = StatusStore.sinceHeartbeatSec()
        val untilWake = StatusStore.untilWakeSec()
        val netBadSec = StatusStore.netBadSec()
        val hbStale = since !in 0 until STALE_SEC
        val why = "心跳=${if (since < 0) "无" else "${since}s"} 下次唤醒=${if (untilWake < 0) "无/已过期" else "${untilWake}s后"}" +
            (if (netBadSec > 0) " 连续超时=${netBadSec}s前起" else "")
        if (hbStale) {
            // 心跳断了才需要唤进程；每 SILENT_RETRY_MS 一次，节流内不重复动作
            if (now - lastSilentWakeAt < silentRetryMs()) return
            lastSilentWakeAt = now
            reviveAttempts++
            wakeQqSilently()
            sendBroadcast(Intent(ACTION_CMD).setPackage(QQ).putExtra("action", "auto").putExtra("attr", attr))
            StatusStore.note("看门狗：已静默唤醒 QQ 并补发auto")
            PLog.w(TAG, "🚨 看门狗触发（$why）→ 静默唤进程 + 补发auto（第 $reviveAttempts 次，下次间隔 ${silentRetryMs() / 1000}s）")
            // 静默唤了好几轮仍无心跳：只发通知让用户自己点，绝不抢前台
            if (reviveAttempts >= 3) raiseAlert(why, since < 0, false)
            return
        }
        if (netBadSec > 0) {
            // 进程活着、心跳正常，但请求全部回不来：只能静默唤进程 + 补发指令（都不抓前台）。
            // 曾想再试“绑 QQ 后台服务”把 `:MSF` 网络子进程也拽一下，但 2026-10-13 用 `settimeout ms=1`
            // 自造超时实测：MsfService / MSFAliveJobService / QQHuaweiPushService **全部 `Not allowed to bind`**（Android 11+ 不可绑），
            // 同一时刻查 provider 返回 true —— 所以只留 provider 这一条，绑定代码已删。
            if (now - lastSilentWakeAt < silentRetryMs()) return
            lastSilentWakeAt = now
            reviveAttempts++
            wakeQqSilently()
            nudgeTick()
            StatusStore.note("看门狗：请求连续超时，已静默唤进程 + 补一轮")
            PLog.w(TAG, "📵 看门狗触发（$why）→ 静默唤进程 + 补一轮 tick（第 $reviveAttempts 次，下次间隔 ${silentRetryMs() / 1000}s）")
            // 连不上持续 10 分钟以上才算“需要人动手”（这种多半要用户自己在 QQ 里重连/重登）
            if (netBadSec > 600) raiseAlert(why, false, true)
            return
        }
        // 心跳新鲜但手上没排下次唤醒 = 状态机停摆；QQ 好好活着，补发一条广播即可，不动界面
        if (now - lastReviveAt < REVIVE_COOLDOWN_MS) return
        lastReviveAt = now
        nudgeTick()
        StatusStore.note("看门狗：唤醒过期未跑，已补一轮")
        PLog.w(TAG, "🚨 看门狗触发（$why）→ 补一轮 tick（src=watchdog）")
    }

    /**
     * 进程活着时只补一轮 tick（src=watchdog），**不发 auto**：auto 会重建 runner、与状态机自己的闹钟同秒各跑一轮（D2）。
     * QQ 侧 TickDriver 对 watchdog 来源再判一次：状态机还排着未到期的唤醒就不跑。
     */
    private fun nudgeTick() {
        sendBroadcast(Intent(ACTION_CMD).setPackage(QQ).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("action", "tick").putExtra("src", "watchdog"))
    }

    /**
     * 需要人动手时的红色提醒（**只发通知，绝不自己拉前台**）。【说实话】：本进程无权限确认 QQ 进程存在与否，
     * 所以只能根据“心跳从未回过”还是“心跳中途断了”分情况给结论：
     *  - 从未回过：本质上是模块没注入到 QQ（框架未启用/作用域未勾，或 QQ 升级后不适配）；
     *  - 连不上（心跳正常但请求全超时）：多半要用户自己在 QQ 里重连/重新登录；
     *  - 其他：按“QQ 被系统冻住、静默唤不回来”写。
     */
    private fun raiseAlert(why: String, neverHeartbeat: Boolean, netBad: Boolean) {
        // 一次事故只提醒一次（用户划掉也不再补发），恢复健康后才重新武装
        if (alertRaisedThisIncident) return
        alertRaisedThisIncident = true
        alertShown = true
        val launch = tapLaunchIntent()
        val tapQi = launch?.let {
            PendingIntent.getActivity(this, 20, it, PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable())
        }
        val tapName = tapTargetName()
        val (title, text, big) = if (neverHeartbeat) {
            Triple(
                "QQ 里没有本模块的心跳",
                "一次心跳都没收到。QQ 可能好好在跑，只是模块没加载进去。",
                "一次心跳都没收到。\n\n这不等于 QQ 没在跑——只是模块没进到它进程里。\n" +
                    "依次检查：\n" +
                    "① 框架里确认 Autoqqpet 已启用，且作用域勾了 QQ（装新版 APK 后作用域容易被清）；\n" +
                    "② 彻底关掉 QQ 再打开一次；\n" +
                    "③ 刚升级过手机 QQ 的话，可能等模块适配（项目主页 Issues 有进展）。\n\n" +
                    "点本通知打开$tapName；心跳一恢复提醒自动消失。",
            )
        } else if (netBad) {
            Triple(
                "QQ 连不上宠物服务器",
                "可能是 QQ 退出登录了或网络断了。打开 QQ 登录后会自动接着干，这条提醒不会再重复弹。",
                "进程没死，但所有宠物协议请求都超时（已持续 10 分钟以上）。\n" +
                    "这类情况多半需要你在 QQ 里手动重连或重新登录。\n" +
                    "本模块不会自动把 QQ 拉到前台（不会抢你屏幕），只会越来越慢地在后台重试（最长 30 分钟一次）。\n" +
                    "这次故障只提醒这一次；恢复后再出问题才会再提醒。\n\n诊断：$why",
            )
        } else {
            Triple(
                "唤不回 QQ，需要你动一下",
                "多次静默尝试仍无心跳 → 点本通知打开$tapName，程序会自动接回。",
                "多次静默尝试仍未拿到心跳（本模块不会自己把 QQ 拉到前台）。\n" +
                    "点本通知打开$tapName 即自动接回；心跳一恢复这条提醒会自动消失。\n\n诊断：$why",
            )
        }
        val b = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
        if (tapQi != null) b.setContentIntent(tapQi)
        // 不再挂“去开启悬浮窗”这个动作：本模块已取消前台拉起路径，要用户动手的提醒只能由他自己点通知。
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_NOTIF_ID, b.build())
    }

    private fun build(state: String, healthy: Boolean): Notification {
        val hb = StatusStore.sinceHeartbeatSec()
        val wake = StatusStore.untilWakeSec()
        val wakeText = if (wake < 0) "无排程" else "${wake}s 后排下次唤醒"
        val hbText = if (hb < 0) "无心跳" else "心跳 ${hb}s 前 · $wakeText"
        val statusLine = if (healthy) "正常 · $hbText" else "可能已停 · $hbText · 点按恢复"
        val launch = tapLaunchIntent()
        val pi = if (launch != null)
            PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable())
        else
            PendingIntent.getActivity(this, 0, Intent(), PendingIntent.FLAG_UPDATE_CURRENT or pendingFlagImmutable())
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("QQ 宠物托管 · ${if (StatusStore.autoOn) "运行中" else "已暂停"}")
            .setContentText(state)
            .setContentInfo(statusLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$state\n$statusLine"))
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    /** 「点通知打开」设置（notify_open）：true = 点通知打开模块主界面，false = 打开 QQ（默认，与旧行为相同）。 */
    private fun openModuleOnTap(): Boolean =
        SettingKeys.string(getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE), SettingKeys.NOTIFY_OPEN) == "app"

    /** 点通知时给正文文案用的目标名。 */
    private fun tapTargetName(): String = if (openModuleOnTap()) "模块" else "QQ"

    /** 点击通知要打开的应用的启动 Intent（按设置选；目标应用取不到时退回另一个）。 */
    private fun tapLaunchIntent(): Intent? =
        if (openModuleOnTap()) {
            packageManager.getLaunchIntentForPackage(packageName) ?: packageManager.getLaunchIntentForPackage(QQ)
        } else {
            packageManager.getLaunchIntentForPackage(QQ) ?: packageManager.getLaunchIntentForPackage(packageName)
        }

    private fun pendingFlagImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    /** 红色提醒是否正挂着（内存标志；重启进程后会丢，所以真撤销前还要查系统）。 */
    private var alertShown = false

    /**
     * 循环健康时把红色提醒撤销掉。用 activeNotifications 而不是只看内存标志：
     * 重装/重启后旧通知可能还挂在系统里（真机 2026-10-10 就是这么出现“一条说跑不了、一条说运行中”相互矛盾的）。
     */
    private fun cancelAlertIfShowing() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val showing = alertShown ||
            runCatching { nm.activeNotifications.any { it.id == ALERT_NOTIF_ID } }.getOrDefault(false)
        if (!showing) return
        alertShown = false
        runCatching { nm.cancel(ALERT_NOTIF_ID) }
        PLog.i(TAG, "✅ 心跳已恢复，自动撤销「需要动手」提醒")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
            if (nm.getNotificationChannel(SPARK_TONIGHT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(SPARK_TONIGHT_CHANNEL_ID, "火花今晚熄灭提醒", NotificationManager.IMPORTANCE_DEFAULT))
            }
            if (nm.getNotificationChannel(SPARK_CLEAR_CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(SPARK_CLEAR_CHANNEL_ID, "火花快清零提醒", NotificationManager.IMPORTANCE_DEFAULT))
            }
            // 红色提醒通道：只在“拉不起 QQ”这类需要用户动手的情况下用
            if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL_ID, "托管中断提醒", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }
}
