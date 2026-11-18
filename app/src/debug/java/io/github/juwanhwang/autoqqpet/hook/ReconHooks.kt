package io.github.juwanhwang.autoqqpet.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.juwanhwang.autoqqpet.hook.cmd.CommandRouter
import io.github.juwanhwang.autoqqpet.hook.cmd.DebugCommands
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 侦察/抓包能力的接缝 —— **DEBUG 版（真实实现）**。
 *
 * 这个文件只存在于 `src/debug`，所以 release 变体编译时根本看不到它，也看不到它引用的
 * [KuiklyPacketLogger]、[DebugCommands]。release 变体用的是 `src/release/.../ReconHooks.kt` 的空实现。
 *
 * 为什么用源集切分而不是 `if (BuildConfig.DEBUG)` 守卫：当前 `proguard-rules.pro` 对
 * `hook.**` 整包 `-keep`，即便代码里 DEBUG 分支被删，R8 仍会保留 KuiklyPacketLogger 类与它的
 * 常量串（`QQKuiklyPlatformApi` / `sendPbRequest`）。源集切分让这些符号在 release 编译里**不存在**，
 * 与 keep 规则无关，可被 `grep`/`dexdump` 直接证伪。
 */
object ReconHooks {
    const val PRESENT = true

    fun installPacketLogger(module: XposedModule, loader: ClassLoader) =
        KuiklyPacketLogger.install(module, loader)

    fun installDelegatePacketLogger(module: XposedModule, methods: List<Method>) =
        KuiklyPacketLogger.installDelegate(module, methods)

    fun registerDebugCommands(router: CommandRouter) = DebugCommands.register(router)

    /**
     * 子进程（:tool 等）只装抓包：自动逻辑仍只在主进程跑，但抓包目标可能就在子进程
     * （例如 QQ 的激励视频/广告走 `:tool`）。装完钩子后动态注册一个**只认 packet 开关**的接收器；
     * 等宿主 Application 就绪再注册（子进程启动早期拿不到 context）。
     */
    fun installCaptureOnly(module: XposedModule, loader: ClassLoader) {
        KuiklyPacketLogger.install(module, loader)
        Thread {
            repeat(30) {
                val ctx = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as? Context
                }.getOrNull()
                if (ctx != null) {
                    runCatching {
                        ContextCompat.registerReceiver(
                            ctx,
                            object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, i: Intent?) {
                                    if (i?.action != CMD_ACTION || i.getStringExtra("action") != "packet") return
                                    when (i.getStringExtra("op")) {
                                        "on" -> KuiklyPacketLogger.setEnabled(true)
                                        "off" -> KuiklyPacketLogger.setEnabled(false)
                                    }
                                }
                            },
                            IntentFilter(CMD_ACTION),
                            ContextCompat.RECEIVER_EXPORTED,
                        )
                        PLog.i("PKT", "子进程抓包就绪：packet 开关可用（${KuiklyPacketLogger.status()}）")
                    }.onFailure { PLog.w("PKT", "子进程抓包接收器注册失败：${it.message}") }
                    return@Thread
                }
                Thread.sleep(1000)
            }
        }.apply { isDaemon = true }.start()
    }

    private const val CMD_ACTION = "io.github.juwanhwang.autoqqpet.CMD"
}
