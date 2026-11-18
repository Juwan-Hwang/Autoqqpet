package io.github.juwanhwang.autoqqpet

import android.app.Application
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.store.Store
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** 模块 App 自身进程的启用状态（由 libxposed service 回调推断）。 */
object EnabledState {
    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * 运行在模块 App 进程。libxposed 在本模块被 LSPosed 启用时把 XposedService 推到本进程，
 * 收到 onServiceBind 即代表已启用（与 QQ 是否运行无关）。
 */
class AutoqqpetApplication : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
        clearStaleAlerts()
        mirrorSettingsToStore()
    }

    /**
     * 界面设置镜像进主存的监听器。**必须用字段强引用持有**：SharedPreferences 只以弱引用
     * 保存监听器，方法里临时注册的 lambda 注册完就可能被 GC 回收——回收之后界面改什么
     * 都进不了主存，QQ 又没在跑时就只剩界面 prefs 里有，表现同样是“改了不生效”。
     * （Backend 的 prefsWatcher 为同一个坑用了字段持有，这里原先漏了。）
     */
    private val settingsMirror = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        // QQ 侧设置正在写进界面（R8）：不往回合并，防回环
        if (key != null && !Store.applyingFromHost) runCatching { syncKeys(p, setOf(key)) }
    }

    /**
     * 界面上改一项设置，就顺手写进【主存】（模块自有目录）。
     *
     * 为什么需要：UI 的改动本来靠广播送到 QQ 进程去落盘，但**QQ 没在跑时送不到**，
     * 那样主存就拿不到新值，下次 QQ 拉起时反倒会被旧值覆盖回去。
     * 监听器在一处统一处理，不依赖每个写设置的地方记得多调一次（以后新增的设置项也不会漏）。
     * 只同步“QQ 进程真会读的那几个键”，主题/协议指纹/目录缓存这些 UI 自用的不插手。
     */
    private fun mirrorSettingsToStore() {
        runCatching {
            val sp = getSharedPreferences(ModulePrefs.NAME, android.content.Context.MODE_PRIVATE)
            // 启动时以“主存 → 界面”为主（R8）：界面 prefs 可能是旧的，反向全量合并会给旧值盖上新戳、再灌回 QQ（R10）
            Store.applyStoreToUi(this)
            // 存量自愈：主存里完全没有、界面里有值的设置键补进主存——反向只开这一道口子，
            // 只补空位不覆盖（安全边界见 Store.backfillMissingSettings）。放在注册监听之前：
            // 补齐只写主存、不写界面 prefs，不会触发镜像监听，无回环；启动对齐在这一步做完，
            // 之后的改动再走监听的逐键 delta。
            Store.backfillMissingSettings(this)
            sp.registerOnSharedPreferenceChangeListener(settingsMirror)
        }
    }

    private fun syncKeys(sp: android.content.SharedPreferences, keys: Collection<String>) {
        val delta = keys.filter { it in Store.SETTINGS_KEYS }.associateWith { sp.all[it] }
        if (delta.values.any { it != null }) Store.mergeSettingsLocal(this, delta)
    }

    /**
     * 进程一活起来就把“需用户动手”的红色提醒/召回提醒清掉。
     * 为什么要在这里：这些提醒本来靠 QQ 侧心跳带回来的空文本撤销（StatusReceiver / KeepAliveService），
     * 但模块 App 一旦被强停，广播送不进来、旧通知就只能挂在通知栏上（真机 2026-10-11 实测）。
     * 真有需要时它们会在下一次心跳（最多 60s）里重新挂上来，不会因此漏提醒。
     */
    private fun clearStaleAlerts() {
        runCatching {
            (getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).apply {
                cancel(io.github.juwanhwang.autoqqpet.keepalive.KeepAliveService.ALERT_NOTIF_ID)
                cancel(io.github.juwanhwang.autoqqpet.keepalive.KeepAliveService.REMINDER_NOTIF_ID)
            }
            PLog.i("App", "🧹 已清掉上次残留的提醒通知")
        }
    }

    override fun onServiceBind(service: XposedService) {
        PLog.i("App", "已启用 framework=${service.frameworkName}")
        EnabledState.set(true)
    }

    override fun onServiceDied(service: XposedService) {
        PLog.i("App", "未启用/服务断开")
        EnabledState.set(false)
    }
}
