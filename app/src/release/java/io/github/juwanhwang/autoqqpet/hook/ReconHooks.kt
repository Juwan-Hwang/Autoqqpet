package io.github.juwanhwang.autoqqpet.hook

import io.github.juwanhwang.autoqqpet.hook.cmd.CommandRouter
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 侦察/抓包能力的接缝 —— **RELEASE 版（空实现）**。
 *
 * release 变体里根本不存在 KuiklyPacketLogger 与 DebugCommands（它们只在 `src/debug`）。
 * 因此 release APK/dex 里不会出现抓包类、`packet` 命令，也不会出现常量串
 * `QQKuiklyPlatformApi` / `sendPbRequest`。这是 Priority-1 的核心：把“抓包/侦察”能力整体从
 * 发布包里拿掉，debug 包里保持完整可用。
 *
 * 全部方法空转，签名与 debug 版逐字一致（Android 变体编译把 main + release 合并编译）。
 */
object ReconHooks {
    const val PRESENT = false

    @Suppress("UNUSED_PARAMETER")
    fun installPacketLogger(module: XposedModule, loader: ClassLoader) { /* release: 无抓包 */ }

    @Suppress("UNUSED_PARAMETER")
    fun installDelegatePacketLogger(module: XposedModule, methods: List<Method>) { /* release: 无抓包 */ }

    @Suppress("UNUSED_PARAMETER")
    fun registerDebugCommands(router: CommandRouter) { /* release: 不注册调试/侦察命令 */ }

    @Suppress("UNUSED_PARAMETER")
    fun installCaptureOnly(module: XposedModule, loader: ClassLoader) { /* release: 无抓包 */ }
}
