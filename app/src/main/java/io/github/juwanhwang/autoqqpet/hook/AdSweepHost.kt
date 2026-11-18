package io.github.juwanhwang.autoqqpet.hook

import android.content.Intent
import io.github.juwanhwang.autoqqpet.BuildConfig
import io.github.juwanhwang.autoqqpet.pet.AdSweepState

/** 历史广告补领的当前进度：保存在 QQ 进程，按需广播给模块 App。 */
object AdSweepHost {
    @Volatile private var state = AdSweepState.Progress()
    @Volatile private var cancelRequested = false
    private val lock = Any()

    fun current(): AdSweepState.Progress = synchronized(lock) { state }

    fun requestCancel() { cancelRequested = true }

    fun clearCancel() { cancelRequested = false }

    fun isCancelRequested(): Boolean = cancelRequested

    fun publish(p: AdSweepState.Progress) {
        synchronized(lock) { state = p }
        val ctx = HostRuntime.appContext() ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(AdSweepState.ACTION)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra(AdSweepState.EXTRA, p.json()),
            )
        }
    }
}