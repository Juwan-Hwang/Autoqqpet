package io.github.juwanhwang.autoqqpet.hook

import android.app.Activity
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/**
 * 记录 QQ 进程里最近一个 onResume 的 Activity。
 *
 * 为什么要它：激励视频的 `VasAdvSupport` 需要真实 Activity 启动 GDT 播放页，
 * 而自动流程跑在后台线程、没有页面引用。`ActivityThread.mActivities` 在 Android 14
 * 上受 hidden API 限制，反射不一定通，所以直接在 `Activity.onResume` 上留一个弱引用。
 * 只记引用，不改生命周期、不拦截返回值。
 */
object ActivityTracker {

    private const val TAG = "ActivityTracker"

    @Volatile private var ref: WeakReference<Activity>? = null
    @Volatile private var installed = false

    fun install(module: XposedModule, loader: ClassLoader) {
        if (installed) return
        installed = true
        runCatching {
            val cls = loader.loadClass("android.app.Activity")
            val onResume = cls.getDeclaredMethod("onResume")
            module.hook(onResume)
                .setId("autoqqpet_activity_resume")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    (chain.thisObject as? Activity)?.let { ref = WeakReference(it) }
                    chain.proceed()
                }
            PLog.i(TAG, "前台 Activity 跟踪已装")
        }.onFailure { PLog.w(TAG, "前台 Activity 跟踪装不上：${it.javaClass.simpleName}: ${it.message}") }
    }

    fun current(): Activity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
