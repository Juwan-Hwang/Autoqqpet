package io.github.juwanhwang.autoqqpet.hook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.juwanhwang.autoqqpet.BuildConfig
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.optimizer.Alerts
import io.github.juwanhwang.autoqqpet.optimizer.AutoRunner
import io.github.juwanhwang.autoqqpet.optimizer.CatalogReader
import io.github.juwanhwang.autoqqpet.optimizer.RunnerConfig
import io.github.juwanhwang.autoqqpet.optimizer.RunnerView
import io.github.juwanhwang.autoqqpet.optimizer.Scheduler
import io.github.juwanhwang.autoqqpet.optimizer.TickStatus
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.FriendManager
import io.github.juwanhwang.autoqqpet.pet.FriendPolicy
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.SideStore
import io.github.juwanhwang.autoqqpet.store.SettingKeys

/**
 * QQ 进程内的全部运行时状态（v1 散落在 ApplicationHook 的 companion 里）。
 *
 * 线程模型（写清楚，避免再出并发双计）：
 *  - `Autoqqpet-Probe`：启动时初始化一次；
 *  - `Autoqqpet-Tick`（[Workers.tick] 单线程）：tick 只经 [TickDriver.request] 进来（闸门：在跑/排着就丢弃，不排队）；
 *  - `Autoqqpet-Cmd`（[Workers.cmd] 小线程池）：其余调试/查询命令，可能与 tick 并行，所以 AutoRunner 内部还有 TICK_LOCK 兜底（失败即丢弃）；
 *  - `Autoqqpet-Heartbeat`：每 60s 发一帧心跳快照，**只读** [view]/DailyStore，不发网络请求；
 *  - 主线程：AlarmScheduler 的 Handler 只投递 tick 广播。
 * 本对象的可变字段都是 @Volatile，写入只发生在初始化/重建（Probe 或 Tick 线程）。
 */
object HostRuntime {
    private const val TAG = "Autoqqpet"
    const val SETTINGS = "autoqqpet_settings"
    const val ACTION_STATUS = "io.github.juwanhwang.autoqqpet.STATUS"

    @Volatile var service: QQPetService? = null
    @Volatile var selfUin: String? = null
    @Volatile var policy: FriendPolicy? = null
    @Volatile var friends: FriendManager? = null
    @Volatile var daily: DailyStore? = null
    @Volatile var runner: AutoRunner? = null
    @Volatile var scheduler: Scheduler? = null
    @Volatile var autoEnabled = false

    /** 跨 runner 重建保留的提醒与视图（重建只换配置，不该把提醒与最近读数清掉）。 */
    val alerts = Alerts()
    val view = RunnerView()

    const val ALERT_PREFS = "autoqqpet_alerts"
    private const val KEY_DISMISSED = "dismissed"
    /** R7：新版本第一轮清掉旧版按附件数挂的信箱提醒（一次性标记）。 */
    private const val KEY_MAIL_RESET = "mail_reset_v2"

    /**
     * 启动时（第一轮 tick 之前）：接上“划掉”持久化，并做 R7 一次性清理——
     * 旧版把 0x9c11 的“带附件邮件数”当可领数挂提醒（真机 6 封全是领完的历史邮件），新版只认 0x9c19 的结果。
     */
    fun installAlerts(ctx: Context) {
        val sp = ctx.getSharedPreferences(ALERT_PREFS, Context.MODE_PRIVATE)
        alerts.loadDismissed(Alerts.decode(sp.getString(KEY_DISMISSED, "")))
        alerts.persist = { m -> sp.edit().putString(KEY_DISMISSED, Alerts.encode(m)).apply() }
        if (!sp.getBoolean(KEY_MAIL_RESET, false)) {
            alerts.clear(Alerts.MAIL); alerts.forgetDismissed(Alerts.MAIL); view.mailClaimable = -1
            sp.edit().putBoolean(KEY_MAIL_RESET, true).apply()
            logLine("📮 新版本第一轮：清掉旧版按附件数挂的信箱提醒（以后只认一键领取的结果）")
        }
    }

    /** 本进程名（持锁后记下，快照/诊断用；R10）。 */
    @Volatile var processName: String = "?"

    /** 最近一次配置一致性自检（R10）。 */
    @Volatile var cfgCheck: io.github.juwanhwang.autoqqpet.optimizer.CfgCheck.Result? = null
    @Volatile private var lastPersistAt = 0L
    @Volatile private var lastCfgCheckAt = 0L
    private const val CFG_CHECK_EVERY_MS = 5 * 60_000L

    /** 心跳线程每帧调：5 分钟一次。 */
    fun maybeCfgCheck() {
        if (System.currentTimeMillis() - lastCfgCheckAt < CFG_CHECK_EVERY_MS) return
        runCfgCheck(heal = true)
    }

    /**
     * 磁盘 prefs vs 内存缓存 vs runner 配置（R10）。刚写过设置的 3 秒内 apply 可能还没落盘，那时先不判。
     * [heal]：内存与磁盘一致、只有 runner 落后 → 重建 runner（rebuild 时机漏了的自愈）。
     */
    fun runCfgCheck(heal: Boolean): io.github.juwanhwang.autoqqpet.optimizer.CfgCheck.Result? {
        val ctx = appContext() ?: return null
        val sp = settings() ?: return null
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 3_000L) return cfgCheck
        lastCfgCheckAt = now
        val file = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/$SETTINGS.xml")
        val disk = runCatching { io.github.juwanhwang.autoqqpet.store.PrefsXml.parse(file.readText()) }.getOrNull()
        var r = io.github.juwanhwang.autoqqpet.optimizer.CfgCheck.compare(disk, sp.all, runner?.config, now)
        if (heal && r.diskRead && r.diskVsMemory.isEmpty() && r.runnerVsDisk.isNotEmpty()) {
            PLog.w(TAG, "🩺 配置自检：runner 落后于设置（${r.runnerVsDisk.joinToString("；")}），重建")
            rebuildRunner()
            r = io.github.juwanhwang.autoqqpet.optimizer.CfgCheck.compare(disk, sp.all, runner?.config, now)
        }
        if (!r.ok && r.line() != cfgCheck?.line()) PLog.w(TAG, "🩺 配置自检：${r.line()}")
        cfgCheck = r
        return r
    }

    /** 最后一次对外播报的业务状态，心跳帧复用它。 */
    @Volatile var lastState: String = "待命"

    fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) { null }

    fun settings(): SharedPreferences? = appContext()?.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    /** 屏幕是否亮着（息屏暂停读它，在 QQ 进程侧查 PowerManager）；查不到按亮着算，宁可不停也不能误停。 */
    fun screenOn(): Boolean = runCatching {
        val ctx = appContext() ?: return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isInteractive ?: true
    }.getOrDefault(true)

    /** 日志一个出口：logcat + 按天日志文件。 */
    fun logLine(msg: String) {
        PLog.i(TAG, msg)
        runCatching { daily?.pushEvent(msg) }
    }

    /** 按持久化设置(重)建 runner。 */
    fun rebuildRunner() {
        val s = service ?: return
        val d = daily ?: return
        val sched = scheduler ?: return
        val sp = settings() ?: return
        (sched as? AlarmScheduler)?.alarmClock = SettingKeys.bool(sp, SettingKeys.ALARM_CLOCK_ON)
        val cfg = RunnerConfig.fromPrefs(sp)
        runner = AutoRunner(
            s, d, friends, policy, sched, cfg, ::logLine, alerts, view,
            pushCatalog = { tiers, courses, adv -> sendCatalog(tiers, courses, adv) },
            liveScheduleJson = { settings()?.getString(Schedule.PREF_KEY, "") ?: "" },
            pullSettings = { SideStore.refreshSettings() },
            jobCache = jobCache,
            screenInteractive = { screenOn() },
        )
        PLog.i(TAG, "🎯 当前配置：${cfg.summary()}")
    }

    /**
     * 岗位目录持久缓存（QQ 侧，R3）：每轮读到的职业按职业合并进来；某职业读失败时回落到这里。
     * 冷启动时模块 App 那份 `work_catalog`（joblist 全量）会随主存回信灌进来（只补缺的职业，不覆盖新读数）。
     */
    val jobCache = object : CatalogReader.JobCache {
        private fun sp() = appContext()?.getSharedPreferences(CATALOG_PREFS, Context.MODE_PRIVATE)
        override fun load(): String = sp()?.getString("jobs", "") ?: ""
        override fun save(json: String) { sp()?.edit()?.putString("jobs", json)?.apply() }
    }
    const val CATALOG_PREFS = "autoqqpet_catalog"

    /** 模块 App 送来的 `work_catalog`（Tier 串）→ 只补 QQ 侧缓存里没有的职业。@return 补了几个职业。 */
    fun seedJobCache(tierText: String): Int {
        val add = CatalogReader.JobCache.fromTiers(JobCatalog.decode(tierText))
        if (add.isEmpty()) return 0
        val have = CatalogReader.JobCache.decode(jobCache.load())
        val missing = add.filterKeys { it !in have }
        if (missing.isEmpty()) return 0
        val root = runCatching { org.json.JSONObject(jobCache.load()) }.getOrNull() ?: org.json.JSONObject()
        val enc = org.json.JSONObject(CatalogReader.JobCache.encode(LinkedHashMap(have).apply { putAll(missing) }, System.currentTimeMillis()))
        jobCache.save(root.put("careers", enc.get("careers")).put("at", enc.get("at")).toString())   // 保留 day/stage/职业列表元数据
        return missing.size
    }

    /** 写设置（QQ 侧生效的那份）并上交主存。 */
    fun persist(block: (SharedPreferences.Editor) -> Unit) {
        val sp = settings() ?: return
        lastPersistAt = System.currentTimeMillis()
        val ed = sp.edit(); block(ed); ed.putLong(io.github.juwanhwang.autoqqpet.store.SettingsStamp.KEY, System.currentTimeMillis()); ed.apply()
        runCatching { SideStore.backupSettings(sp) }
    }

    /** 按登记表写一个键；不认识的键或值不合法返回 false 且不写。 */
    fun setOption(key: String, raw: String): Boolean {
        val def = SettingKeys.def(key) ?: return false
        var ok = false
        persist { ok = SettingKeys.put(it, def, raw) }
        return ok
    }

    /**
     * 改完会影响决策的设置后复查一轮（否则下一次唤醒可能在几小时后）。R2：**防抖合并**——连续改多项只复查一次，
     * 且不再顶掉状态机自己排的唤醒（旧版每次 setopt 都 3 秒后硬跑一轮）。见 [TickDriver.settingChanged]。
     */
    fun rearmSoon(reason: String) = TickDriver.settingChanged(reason)

    /**
     * 心跳/状态广播：旧 extras 全保留（兼容现有界面与看门狗），新增 `snapshot` JSON（docs/CONTRACT.md §2）。
     * R5：状态串只有一种格式（[TickStatus]），来源前缀（启动·/续跑·）不再进状态；被闸门丢弃的请求不改写状态。
     */
    fun sendStatus(state0: String, heartbeat: Boolean = false) {
        val ctx = appContext() ?: return
        val state = TickStatus.normalize(state0).let { if (TickStatus.isSkip(it)) lastState else it }
        if (!heartbeat) {
            lastState = state
            runCatching { daily?.pushEvent("状态 $state") }
        }
        val nextWake = (scheduler as? AlarmScheduler)?.nextWakeAt ?: 0L
        val netBad = runCatching { runner?.networkStaleAt ?: 0L }.getOrDefault(0L)
        val snap = runCatching { StatusSnapshot.build(this, nextWake, netBad) }
            .onFailure { PLog.w(TAG, "快照拼装失败：${it.message}") }.getOrNull()
        try {
            ctx.sendBroadcast(
                Intent(ACTION_STATUS)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra("state", state)
                    .putExtra("auto_on", autoEnabled)
                    .putExtra("next_wake_at", nextWake)
                    .putExtra("net_bad_at", netBad)
                    .putExtra("heartbeat", heartbeat)
                    .putExtra("alert", alerts.legacyText())
                    .putExtra("snapshot", snap)
                    .putExtra("contract_v", StatusSnapshot.VERSION),
            )
        } catch (t: Throwable) {
            PLog.e(TAG, "sendStatus 失败", t)
        }
    }

    /** 学工目录 → UI 进程（打工下拉框与排班编辑器的数据源）。 */
    fun sendCatalog(
        tiers: List<JobCatalog.Tier>,
        courses: List<JobCatalog.CourseTier> = emptyList(),
        adventures: List<JobCatalog.AdventureTier> = emptyList(),
    ) {
        val ctx = appContext() ?: return
        try {
            ctx.sendBroadcast(
                Intent(JobCatalog.ACTION).setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra("catalog", JobCatalog.encode(tiers))
                    .putExtra("courses", JobCatalog.encodeCourses(courses))
                    .putExtra("adventures", JobCatalog.encodeAdventures(adventures))
                    .putExtra("at", System.currentTimeMillis()),
            )
            PLog.i(TAG, "📤 目录已回传界面（岗位 ${tiers.size} / 课程 ${courses.size} / 冒险 ${adventures.size}）")
        } catch (t: Throwable) {
            PLog.e(TAG, "目录回传失败", t)
        }
    }

    fun advModeCn(m: String): String = when (m) {
        "auto" -> "最优调度"
        "only" -> "只重复冒险"
        "work" -> "工作优先"
        "custom" -> "自定义排班"
        else -> "学习优先（学工疲惫才冒险）"
    }
}
