package io.github.juwanhwang.autoqqpet.hook

import android.content.Context
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.pet.QQPetBridge
import io.github.juwanhwang.autoqqpet.pet.QQPetService

/**
 * ClassLoader 选择与排障探针（DEV-NOTES 2026-10-10：9.3.70 同进程两份 `mqq.app.MobileQQ`，
 * 框架给的 param.classLoader 那份从未初始化，拿它反射即 NPE；Application 自己 classloader 那份才是好的）。
 */
object LoaderProbes {
    private const val TAG = "Autoqqpet"
    const val TARGET = "com.tencent.mobileqq"

    /** 当前在用的 loader（启动兜底为 param.classLoader，延迟后重选）。 */
    @Volatile var classLoaderRef: ClassLoader? = null

    /**
     * 当前进程名（R10）：`ActivityThread.currentProcessName()` 在 onPackageReady 时可能还是 null（进程绑定前），
     * 那时依次退到 `Application.getProcessName()`（API 28+）与 `/proc/self/cmdline`，三者都拿不到才是 null。
     * 只有主进程 `com.tencent.mobileqq` 可以跑循环：子进程（:MSF/:tool/:qzone…）各有一份 SharedPreferences 缓存，
     * 跑起来就是第二个 runner，拿旧设置发快照、甚至把整份旧 prefs 写回磁盘。
     */
    fun currentProcessName(): String? {
        runCatching { Class.forName("android.app.ActivityThread").getMethod("currentProcessName").invoke(null) as? String }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { android.app.Application.getProcessName() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return cmdlineProcessName()
    }

    /** `/proc/self/cmdline` 第一个 NUL 之前就是进程名（zygote fork 后由 setArgV0 写入）。 */
    fun cmdlineProcessName(): String? = runCatching {
        java.io.File("/proc/self/cmdline").readBytes().let { b -> parseCmdline(b) }
    }.getOrNull()

    /** 纯函数（可单测）：cmdline 字节 → 进程名；空/只有 NUL → null。 */
    fun parseCmdline(b: ByteArray): String? {
        val end = b.indexOf(0.toByte()).let { if (it < 0) b.size else it }
        return String(b, 0, end, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() && it != "<pre-initialized>" }
    }

    /** 是否允许在本进程跑循环：名字确定且不是主进程 → 否；名字未知 → 交给 [RunnerLock] 再判。 */
    fun isMainProcess(name: String?): Boolean? = name?.let { it == TARGET }

    /**
     * 选对 ClassLoader：**以“谁的 `MobileQQ.sMobileQQ` 非 null”为硬判据**（无版本分支，自然向后兼容），
     * 并且**必须在延迟之后选**（onPackageReady 刚进来时两份都没初始化，选早了必然退回坏的那份）。
     */
    fun pick(fallback: ClassLoader?): ClassLoader? {
        val appLoader = runCatching { HostRuntime.appContext()?.classLoader }.getOrNull()
        val candidates = listOfNotNull(appLoader, fallback)
        for (ld in candidates) {
            val ok = runCatching { ld.loadClass("mqq.app.MobileQQ").getField("sMobileQQ").get(null) != null }.getOrDefault(false)
            if (ok) { PLog.i(TAG, "🧬 使用 loader=${System.identityHashCode(ld)}（MobileQQ 已被 Application 初始化）"); return ld }
            PLog.w(TAG, "🧬 跳过 loader=${System.identityHashCode(ld)}：它那份 MobileQQ.sMobileQQ=null")
        }
        PLog.w(TAG, "🧬 两份 loader 都还没初始化，先用现成的等心跳重选")
        return candidates.firstOrNull()
    }

    fun envProbe() {
        val ctx = HostRuntime.appContext()
        val vv = runCatching {
            val ai = ctx!!.packageManager.getPackageInfo(TARGET, 0)
            "${ai.versionName}(${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ai)})"
        }.getOrDefault("?")
        PLog.i(TAG, "🧪 环境自检：进程=${currentProcessName() ?: "?"} QQ=$vv 入口=${HostRuntime.service?.entryName ?: "未建立"} petId=${HostRuntime.service?.petId?.take(12) ?: "-"}")
        val mq = runCatching { classLoaderRef!!.loadClass("mqq.app.MobileQQ") }.getOrNull() ?: run { PLog.w(TAG, "   加载 mqq.app.MobileQQ 失败"); return }
        listOf("sMobileQQ").forEach { f ->
            PLog.i(TAG, "   MobileQQ.$f=" + runCatching { mq.getField(f).get(null)?.javaClass?.simpleName ?: "null" }.getOrElse { "字段不存在:${it.javaClass.simpleName}" })
        }
        listOf("getRef", "getInstance", "peekRuntime", "getAppRuntime").forEach { mn ->
            val r = runCatching { mq.getMethod(mn).invoke(null) }
            PLog.i(TAG, "   $mn() → " + (r.getOrNull()?.javaClass?.simpleName ?: "无/失败：${r.exceptionOrNull()?.javaClass?.simpleName}"))
        }
    }

    fun loaderProbe() {
        val loaders = LinkedHashMap<String, ClassLoader?>()
        loaders["current"] = classLoaderRef
        loaders["app.classLoader"] = runCatching { (HostRuntime.appContext() as Context).classLoader }.getOrNull()
        loaders.forEach { (tag, ld) ->
            if (ld == null) { PLog.i(TAG, "   🔸 $tag → null"); return@forEach }
            val inst = runCatching { ld.loadClass("mqq.app.MobileQQ").getField("sMobileQQ").get(null) }
            PLog.i(TAG, "   🔸 $tag loader=${System.identityHashCode(ld)} sMobileQQ=" +
                if (inst.isFailure) "取失败" else if (inst.getOrNull() == null) "null" else "已初始化")
            val r = runCatching { QQPetService(QQPetBridge(ld)).readOwnProfile() }
            PLog.i(TAG, "   🧪 用 $tag 发只读 → " + (r.getOrNull()?.let { "成功 petId=${it.petId.take(12)}" } ?: "失败：${r.exceptionOrNull()?.message?.take(150)}"))
        }
    }
}
