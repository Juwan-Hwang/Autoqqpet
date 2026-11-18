package io.github.juwanhwang.autoqqpet.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.juwanhwang.autoqqpet.hook.cmd.CommandRouter
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.FriendManager
import io.github.juwanhwang.autoqqpet.pet.FriendPolicy
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.QQPetBridge
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import io.github.juwanhwang.autoqqpet.pet.SideStore
import io.github.juwanhwang.autoqqpet.BuildConfig
import io.github.juwanhwang.autoqqpet.store.SettingKeys
import io.github.juwanhwang.autoqqpet.store.Store
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed 模块入口（QQ 主进程内，META-INF/xposed/java_init.list 声明）。只做三件事：
 *  1. 延迟后**选对 ClassLoader**、按形状发现发包入口、建运行时（[HostRuntime]）；
 *  2. 注册命令广播（分发见 [CommandRouter]，每族一个文件）；
 *  3. 心跳线程：每 60s 一帧快照 + ClassLoader 自愈。
 */
class ApplicationHook : XposedModule() {

    companion object {
        const val TAG = "Autoqqpet"
        const val ACTION_CMD = "io.github.juwanhwang.autoqqpet.CMD"

        /** 进程内闹钟 tick 专用 action：发送方只有 QQ 进程自己（AlarmScheduler），与跨应用的
         *  ACTION_CMD 分开注册——那条挂签名级权限，QQ 自己没有那个权限（腾讯签名）。 */
        const val ACTION_TICK = "io.github.juwanhwang.autoqqpet.TICK"
        private const val PROBE_DELAY_MS = 8_000L
        private const val HEARTBEAT_MS = 60_000L
        @Volatile private var probed = false
        @Volatile private var receiverRegistered = false
        @Volatile private var heartBeating = false
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        PLog.i(TAG, "module loaded api=$apiVersion framework=$frameworkName")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != LoaderProbes.TARGET) return
        val process = LoaderProbes.currentProcessName()
        if (LoaderProbes.isMainProcess(process) == false) {
            // debug：子进程也装抓包（抓包目标可能在 :tool 等子进程）；release 的 ReconHooks 是空实现
            if (ReconHooks.PRESENT) runCatching { ReconHooks.installCaptureOnly(this, param.classLoader) }
            PLog.i(TAG, "跳过子进程 $process")
            return
        }
        if (process == null) PLog.w(TAG, "进程名暂时未知：延后到初始化时再判，并以单实例锁兜底（R10）")
        if (probed) return
        probed = true
        // 选 loader 不能在这里做：此刻 QQ 的 Application 还没建好，两份 loader 都看起来“没初始化”。
        LoaderProbes.classLoaderRef = param.classLoader
        Thread {
            try {
                Thread.sleep(PROBE_DELAY_MS)
                init(param.classLoader)
            } catch (t: Throwable) {
                PLog.e(TAG, "初始化失败", t)
            }
        }.apply { name = "Autoqqpet-Probe"; start() }
    }

    private fun init(fallback: ClassLoader) {
        // R10：只有主进程、且只有一个进程能跑循环。名字到这时一般已能取到；取不到就全靠单实例锁。
        val process = LoaderProbes.currentProcessName()
        if (LoaderProbes.isMainProcess(process) == false) { PLog.i(TAG, "初始化时确认是子进程 $process，不跑循环"); return }
        val ctx0 = HostRuntime.appContext() ?: run { PLog.w(TAG, "拿不到 Application context"); return }
        if (!RunnerLock.acquire(ctx0.filesDir)) {
            PLog.w(TAG, "🔒 另一个进程已持有循环锁（${RunnerLock.holderInfo(ctx0.filesDir)}），本进程 ${process ?: "?"}/${android.os.Process.myPid()} 不跑循环")
            return
        }
        HostRuntime.processName = process ?: "?"
        PLog.i(TAG, "🔒 已持有单实例锁：进程 ${HostRuntime.processName} pid=${android.os.Process.myPid()}")
        val loader = LoaderProbes.pick(fallback) ?: fallback
        LoaderProbes.classLoaderRef = loader
        buildBridge(loader)
        runCatching { ActivityTracker.install(this, loader) }.onFailure { PLog.w(TAG, "Activity 跟踪安装失败：${it.message}") }
        runCatching { ReconHooks.installPacketLogger(this, loader) }.onFailure { PLog.w(TAG, "抓包 hook 安装失败：${it.message}") }
        val ctx = HostRuntime.appContext() ?: run { PLog.w(TAG, "拿不到 Application context"); return }
        val settings = ctx.getSharedPreferences(HostRuntime.SETTINGS, Context.MODE_PRIVATE)
        // 先恢复再建对象：配置与当日进度的主存在模块自有目录（QQ 重装/清数据不会连带丢）；拿不到就用本地值。
        SideStore.install(ctx)
        SideStore.onRestored = {
            PLog.i(TAG, "🗂 主存回信晚到了：按恢复后的设置重建循环")
            SettingKeys.migrate(settings)
            HostRuntime.rebuildRunner(); HostRuntime.rearmSoon("主存恢复")
        }
        if (SideStore.requestRestore(ctx)) PLog.i(TAG, "🗂 已从主存恢复设置")
        SettingKeys.migrate(settings).takeIf { it > 0 }?.let {
            PLog.i(TAG, "🔧 设置迁移：改动 $it 项")
            // 迁移改了 QQ 侧的值：盖时间戳并上交，界面（R8）与主存都跟上，免得主存旧值下次又灌回来
            HostRuntime.persist { }
        }
        HostRuntime.policy = FriendPolicy(ctx).also { it.adoptFromStore(); it.migrateSpark().takeIf { n -> n > 0 }?.let { n -> PLog.i(TAG, "🔥 火花名单迁移：改动 $n 项（白名单作废，默认踩所有好友）") }; it.migrateFriendLists().takeIf { n -> n > 0 }?.let { n -> PLog.i(TAG, "👥 互动名单迁移：改动 $n 项（黑白双名单合并为一份名单 + 模式）") } }
        HostRuntime.daily = DailyStore(ctx).also { it.adoptFromStore() }
        HostRuntime.installAlerts(ctx)
        rebuildFriends()
        runCatching { HostRuntime.friends?.refreshRoster() ?: 0 }.onSuccess { if (it > 0) PLog.i(TAG, "👥 好友名册已刷新（$it 人）") }
        HostRuntime.scheduler = AlarmScheduler(ctx, ACTION_TICK)
        HostRuntime.autoEnabled = SettingKeys.bool(settings, SettingKeys.AUTO_ON)
        HostRuntime.rebuildRunner()
        registerCmdReceiver(ctx)
        startHeartbeat()
        selfTest()
        if (HostRuntime.autoEnabled) {
            // 续跑：QQ 被杀重开后若自动模式开着，立刻补一个 tick 接回循环（走 tick 专用线程，与闹钟 tick 串行）
            PLog.i(TAG, "♻️ 检测到自动模式开启，QQ 启动续跑 tick")
            TickDriver.request("resume") { r -> PLog.i(TAG, "续跑 tick → $r") }
        } else HostRuntime.sendStatus("待命")
    }

    private fun buildBridge(loader: ClassLoader) {
        val bridge = QQPetBridge(loader)
        runCatching { ReconHooks.installDelegatePacketLogger(this, bridge.sendMethodsForHook()) }.onFailure { PLog.w(TAG, "原生侧抓包安装失败：${it.message}") }
        HostRuntime.service = QQPetService(bridge)
        HostRuntime.selfUin = bridge.getSelfUin()
    }

    private fun rebuildFriends() {
        val s = HostRuntime.service ?: return
        val p = HostRuntime.policy ?: return
        HostRuntime.friends = FriendManager(s, p, HostRuntime.selfUin, HostRuntime.daily, HostRuntime::logLine) {
            HostRuntime.settings()?.let { SettingKeys.bool(it, SettingKeys.PK_BLACKLIST_ON_LOSS) } ?: true
        }
    }

    private fun selfTest() {
        val s = HostRuntime.service ?: return
        val r = PetResult.read { s.readOwnProfile().let { p -> p to s.readValues() } }
        r.getOrNull()?.let { (p, v) ->
            HostRuntime.view.noteValues(v)
            PLog.i(TAG, "✅ 只读探针：宠物=${p.petName} | 心情=${v.feel} 体力=${v.hunger} 清洁=${v.clean} 金币=${v.gold}")
        } ?: PLog.w(TAG, "❌ 只读探针失败：${r.describe()}")
    }

    private fun registerCmdReceiver(ctx: Context) {
        if (receiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CMD) CommandRouter.dispatch(intent)
            }
        }
        val tickReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_TICK) CommandRouter.dispatch(intent)
            }
        }
        try {
            // 跨应用通道（模块 App → QQ）：release 挂签名级权限，只有同签名的模块 App 能发进来
            // （2026-10-19 审计 F1：此前任意应用都能发全部命令）。debug 构建不挂权限，保留
            // adb `am broadcast` 调试通道——shell 拿不到签名权限，发布版则永远锁死。
            if (BuildConfig.DEBUG) {
                androidx.core.content.ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_CMD), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            } else {
                androidx.core.content.ContextCompat.registerReceiver(
                    ctx, receiver, IntentFilter(ACTION_CMD),
                    Store.PERMISSION_BROADCAST, null, androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
                )
            }
            // 进程内通道（QQ 自己 → 自己：闹钟 tick）：不导出。同应用发送照常送达，
            // 模块 App 与其它应用都进不来，它们各有自己的路（App 走上面那条）。
            androidx.core.content.ContextCompat.registerReceiver(ctx, tickReceiver, IntentFilter(ACTION_TICK), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            PLog.i(TAG, "已注册命令广播接收器: $ACTION_CMD（${CommandRouter.names().size} 条命令）")
        } catch (t: Throwable) {
            PLog.e(TAG, "注册广播失败", t)
        }
    }

    /**
     * 心跳：auto 开着就每 60s 报一帧（30 分钟一节课期间本来就没动静，只靠 tick 播报看门狗会误判挂死）。
     * 有了独立心跳，“心跳断了”才等价于“QQ 进程没了/被冻结”。顺带做 ClassLoader 自愈。
     */
    private fun startHeartbeat() {
        if (heartBeating) return
        heartBeating = true
        Thread {
            while (true) {
                try { Thread.sleep(HEARTBEAT_MS) } catch (_: InterruptedException) { break }
                if (HostRuntime.autoEnabled) HostRuntime.sendStatus(HostRuntime.lastState, heartbeat = true)
                healClassLoader()
                runCatching { HostRuntime.maybeCfgCheck() }
            }
        }.apply { name = "Autoqqpet-Heartbeat"; isDaemon = true; start() }
    }

    /** 连 petId 都没拿到（冷启动太快选错 loader）→ 重选 loader + 重建发包桥/runner 并立刻复查。 */
    private fun healClassLoader() {
        val s = HostRuntime.service ?: return
        if (s.petId.isNotEmpty()) return
        val better = runCatching { LoaderProbes.pick(LoaderProbes.classLoaderRef) }.getOrNull() ?: return
        if (better === LoaderProbes.classLoaderRef) return
        PLog.i(TAG, "🩹 换 ClassLoader 重建发包桥：${System.identityHashCode(LoaderProbes.classLoaderRef)} → ${System.identityHashCode(better)}")
        LoaderProbes.classLoaderRef = better
        runCatching {
            buildBridge(better)
            rebuildFriends()
            HostRuntime.rebuildRunner()
            if (HostRuntime.autoEnabled) TickDriver.request("heal") { r -> PLog.i(TAG, "🩹 重建后复查 → $r") }
        }.onFailure { PLog.w(TAG, "🩹 重建失败：${it.message?.take(90)}") }
    }
}
