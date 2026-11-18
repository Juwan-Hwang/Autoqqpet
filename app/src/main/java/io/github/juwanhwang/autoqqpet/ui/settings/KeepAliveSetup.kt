package io.github.juwanhwang.autoqqpet.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台运行引导（A）：不申请任何新权限，只**查状态 + 跳到系统设置页**让用户自己点。
 *  - 电池优化：`PowerManager.isIgnoringBatteryOptimizations(包名)` 对任意包都能查；
 *    跳转用不需要权限的“电池优化列表/应用详情”页（不用 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，许可协议里的权限清单不变）。
 *  - 精确闹钟：闹钟是 QQ 进程排的，看的是 **QQ** 的 SCHEDULE_EXACT_ALARM（QQ 侧随快照报上来）。
 */
object KeepAliveSetup {
    const val QQ = "com.tencent.mobileqq"

    fun ignoringBattery(ctx: Context, pkg: String): Boolean? = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(pkg)
    }.getOrNull()

    /** 打开某个应用的电池设置：先试应用详情（三星/多数 ROM 在这里有“电池 → 不受限制”），不行再开电池优化总列表。 */
    fun openBattery(ctx: Context, pkg: String): Boolean = start(ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** QQ 的“闹钟和提醒”（精确闹钟）开关页；Android 12 以下没有这个限制。 */
    fun openExactAlarm(ctx: Context, pkg: String = QQ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        start(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    else start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    private fun start(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            val ok = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    const val HELP = "要让托管在息屏、无人操作时一直跑，不用把 QQ 拉到前台：\n\n" +
        "① 本模块和 QQ 都设为电池“不受限制”，在系统设置 → 应用里分别选“电池 → 不受限制”；两者的当前状态和跳转入口在「运行诊断」页里。三星还要把两者从“休眠应用/深度休眠应用”里移出。\n" +
        "② QQ 的“闹钟和提醒”保持允许，也就是精确闹钟：模块在 QQ 进程里排的唤醒靠它准点。\n" +
        "③ LSPosed 里本模块作用域只勾 QQ，包名 com.tencent.mobileqq；装新版后作用域可能被清，记得重勾并强行停止 QQ 一次。\n" +
        "④ 最近任务里把 QQ 锁定，可选，能减少被系统清理。\n\n" +
        "仍有停摆时可打开“强力唤醒”，状态栏会出现闹钟图标。"
}
