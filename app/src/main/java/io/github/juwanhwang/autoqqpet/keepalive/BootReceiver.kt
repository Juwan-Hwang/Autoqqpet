package io.github.juwanhwang.autoqqpet.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.log.PLog

/**
 * 开机 / 应用更新后，若自动模式上次是开着的，就拉起保活前台服务。
 * BOOT_COMPLETED 属于系统豁免场景，可以在这里起前台服务（QQ 进程内心跳那条广播不行）。
 * 拉起后由看门狗负责把 QQ 也带起来并补发 auto，整条循环自行接回。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 注意：这里必须读模块进程自己的那份镜像（StatusReceiver 每帧回填 + UI 写入），
        // 读 "autoqqpet_settings" 的话在 QQ 数据目录之外永远是 false（2026-10-09 复盘）。
        val sp = context.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE)
        val auto = sp.getBoolean(ModulePrefs.KEY_AUTO_ON, false)
        PLog.i("Boot", "${intent.action} auto_on(镜像)=$auto")
        if (auto) KeepAliveService.start(context)
    }
}
