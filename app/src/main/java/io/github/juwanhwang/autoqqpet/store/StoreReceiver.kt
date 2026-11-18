package io.github.juwanhwang.autoqqpet.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.juwanhwang.autoqqpet.log.PLog

/**
 * 主存备份的接收端（跑在模块 App 进程）。QQ 进程与它之间只能靠广播：
 * 跨包 ContentProvider 在 Android 11+ 需要调用方（QQ）声明 `<queries>`，我们改不了它。
 *
 * 两种消息：
 *  - 带来 `settings_json` / `daily_json`：QQ 侧的最新值，落盘到模块自有目录（主存）；
 *  - 带来 `ask`：QQ 本地刚被清过（重装/清数据），把备份原样回送一次。
 */
class StoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // `from` 自 v2.0.2 起只作日志标记、不再作鉴权：它由发送方自填，任何应用都能冒充
        // （2026-10-19 审计 F2）。本端的发送方是 QQ 进程（腾讯签名），平台没有能挂的签名
        // 权限——挂上就等于断掉整条备份通道；真正的鉴权收口在对侧：QQ 里的 SideStore
        // 接收端已挂签名级权限，只收模块 App 的回送。本端载荷另有时间戳守卫判新旧
        // （设置 SettingsStamp、名单 __at），冒充一份也只能灌进主存备份，生效仍要过守卫。
        val fromPkg = intent.getStringExtra(Store.EXTRA_FROM)
        if (fromPkg != QQ && fromPkg != Store.MODULE_PKG) {
            PLog.d(TAG, "主存广播来源标记异常（仅记录）：$fromPkg")
        }
        val settings = intent.getStringExtra(Store.EXTRACT_SETTINGS)
        val daily = intent.getStringExtra(Store.EXTRACT_DAILY)
        val friends = intent.getStringExtra(Store.EXTRACT_FRIENDS)
        // R8/R10：QQ 上交的设置按 __at 判新旧；收下后把差异写进界面 prefs（界面监听 → dataVersion+1 → 重读）
        settings?.let { Store.receiveHostSettings(context, it) }
        daily?.let { Store.saveBackup(context, Store.EXTRACT_DAILY, it) }
        friends?.let { Store.receiveFriends(context, it) }
        // 诊断快照走同一个通道（它不是配置，不参与上面的 ask 回送）
        intent.getStringExtra(Store.EXTRACT_DIAG)?.let {
            Store.saveDiag(context, it, intent.getLongExtra(Store.EXTRACT_DIAG_AT, System.currentTimeMillis()))
        }

        if (intent.getStringExtra(Store.EXTRA_ASK) != null) {
            val sp = Store.prefs(context)
            Store.sendToHost(
                context,
                sp.getString(Store.EXTRACT_SETTINGS, null),
                sp.getString(Store.EXTRACT_DAILY, null),
                sp.getString(Store.EXTRACT_FRIENDS, null),
                catalog = context.getSharedPreferences(io.github.juwanhwang.autoqqpet.ModulePrefs.NAME, Context.MODE_PRIVATE)
                    .getString(io.github.juwanhwang.autoqqpet.pet.JobCatalog.KEY_CATALOG, null),
            )
            PLog.i(TAG, "🗂 QQ 索要主存备份 → 已回送（设置 ${sp.getString(Store.EXTRACT_SETTINGS, "")?.length ?: 0}B、" +
                "进度 ${sp.getString(Store.EXTRACT_DAILY, "")?.length ?: 0}B）")
        }
    }

    companion object {
        private const val TAG = "Store"
        private const val QQ = "com.tencent.mobileqq"
    }
}
