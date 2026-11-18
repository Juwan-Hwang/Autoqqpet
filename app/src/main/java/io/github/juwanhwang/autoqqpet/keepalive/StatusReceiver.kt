package io.github.juwanhwang.autoqqpet.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.log.PLog

/**
 * 接收 QQ 进程发来的心跳/状态广播，更新 StatusStore 并确保前台服务在跑。
 *
 * 【为什么要回填一份 auto_on 到本地偏好】真值 `autoqqpet_settings/auto_on` 写在 QQ 进程的数据目录里，
 * 模块 App 进程读同名文件只会拿到默认 false；2026-10-09 的停摆就是因为看门狗与开机自启都读不到它，
 * 于是"心跳超时→拉起 QQ 救活循环"这条兜底链一步都没走。这里每帧把 auto_on 镜像进本进程可读的
 * [ModulePrefs.NAME]，进程重启后（看门狗、BootReceiver）也还能知道"上次是开着的"。
 */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "io.github.juwanhwang.autoqqpet.STATUS") return
        val state = intent.getStringExtra("state") ?: "运行中"
        val auto = intent.getBooleanExtra("auto_on", StatusStore.autoOn)
        StatusStore.autoOn = auto
        StatusStore.nextWakeAt = intent.getLongExtra("next_wake_at", StatusStore.nextWakeAt)
        StatusStore.alertText = intent.getStringExtra("alert").orEmpty()
        // “连续请求回不来”的标记也每帧带上来：进程活着时光看心跳看不出来它连不上
        StatusStore.netBadAt = intent.getLongExtra("net_bad_at", 0L)
        StatusStore.update(state)
        val snap = intent.getStringExtra("snapshot")
        val ed = context.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(ModulePrefs.KEY_AUTO_ON, auto)
        // 新版快照落盘：界面进程重启后也能立刻画出上一帧（旧版 QQ 侧不带这个 extra 时保持原样）
        if (!snap.isNullOrEmpty()) {
            StatusStore.snapshotJson = snap
            ed.putString(ModulePrefs.KEY_STATUS_SNAPSHOT, snap).putLong(ModulePrefs.KEY_STATUS_SNAPSHOT_AT, System.currentTimeMillis())
        }
        ed.apply()
        if (!intent.getBooleanExtra("heartbeat", false))
            PLog.d("StatusReceiver", "心跳到达 state=${state.take(40)} auto=$auto 距下次唤醒=${StatusStore.untilWakeSec()}s")
        // “需用户动手”的提醒没文本了就直接撤掉，**不能只靠服务里的 syncReminder**：
        // 服务没在跑时那条路径不会执行（真机 2026-10-11 实测：关掉提醒后 1003 仍挂在通知栏）。
        if (StatusStore.alertText.isEmpty()) {
            runCatching {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .cancel(KeepAliveService.REMINDER_NOTIF_ID)
            }
        }
        if (auto) KeepAliveService.start(context)
        KeepAliveService.refresh(context)
    }
}
