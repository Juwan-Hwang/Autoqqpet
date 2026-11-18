package io.github.juwanhwang.autoqqpet.ui.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.keepalive.KeepAliveService
import io.github.juwanhwang.autoqqpet.pet.AdSweepState
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.store.SettingKeys
import io.github.juwanhwang.autoqqpet.store.Store
import io.github.juwanhwang.autoqqpet.ui.EulaTerms

/**
 * 界面与后端之间唯一的出口。
 *
 * 界面只做三件事：写模块自己的偏好（[ModulePrefs.NAME]，由 Application 的监听器镜像进主存）、
 * 向 QQ 进程发 CMD 广播、拉起保活服务。动作名、extra 键与 prefs 键与旧版逐字一致，
 * 换界面不换协议（docs/design/DESIGN.md「与后端的约定」）。
 *
 * 未同意当前版本协议时一律不写不发（[guard]）——首启流程本来就进不来，这里是第二道闸。
 */
class Backend(context: Context) {

    private val ctx = context.applicationContext
    val prefs: SharedPreferences = ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE)

    /**
     * 界面偏好的“版本号”（Compose 可观察）：`autoqqpet_ui` 里任何键一变就 +1（v2.1.1 修设置开关不立即切换）。
     *
     * 为什么需要：SharedPreferences 本身不可观察。旧做法是设置页父级读一个 bump 计数器、行里直接 `prefs.getBoolean`，
     * 但 Kotlin 2.x 的 Compose 默认 strong skipping：行的入参（设置项、env、回调）一个都没变，父级重组时**整行被跳过**，
     * 开关就停在旧值，退出重进（重新组合）才对。现在所有读偏好的地方都先读这个 State，谁读谁订阅，值一变就精确重组。
     * 监听器常驻（不像 LiveState 那样只在可见时注册、还有 150ms 防抖），本页写入和 QQ 回写都当帧生效。
     */
    val prefsVersion: MutableIntState = mutableIntStateOf(0)

    /** 强引用持有：SharedPreferences 内部用弱引用存监听器。 */
    private val prefsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> prefsVersion.intValue++ }

    init { runCatching { prefs.registerOnSharedPreferenceChangeListener(prefsWatcher) } }

    /** 读偏好前先读版本号：在 Composable 里调用即订阅，值变了自动重组。 */
    fun observe(): Int = prefsVersion.intValue

    fun bool(key: String, def: Boolean): Boolean { observe(); return prefs.getBoolean(key, def) }
    fun string(key: String, def: String): String { observe(); return prefs.getString(key, def) ?: def }
    fun int(key: String, def: Int): Int { observe(); return prefs.getInt(key, def) }

    /** 本页刚写完：不等监听器回调，立刻 +1（监听器只在值真变时回调，这里保证点了就重组）。 */
    private fun touched() { prefsVersion.intValue++ }

    /** 协议正文在运行期不会变：指纹算一次就够（每秒刷新的快照会反复问）。 */
    private val digest: String by lazy { EulaTerms.digest(ctx) }

    // 源码公开版停用协议确认页：设置写入/命令下发不再要求先同意协议；原判定保留在注释里。
    // fun agreed(): Boolean = prefs.getString(EulaTerms.KEY_DIGEST, null) == digest
    fun agreed(): Boolean = true

    private inline fun guard(block: () -> Unit): Boolean {
        if (!agreed()) return false
        block()
        return true
    }

    /** 发一条 CMD 广播给 QQ 进程（QQ 不在时会丢——设置另有主存兜底，循环每 5 分钟补拉）。 */
    fun send(action: String, extras: Intent.() -> Unit = {}) {
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_CMD).setPackage(QQ).putExtra("action", action).apply(extras))
        }
    }

    // ── 主页 ─────────────────────────────────────────

    val autoOn: Boolean get() = prefs.getBoolean(ModulePrefs.KEY_AUTO_ON, false)
    val attr: String get() = prefs.getString(ModulePrefs.KEY_ATTR, "力量") ?: "力量"

    /** 旧版曾有一挡 off（只学工不冒险），已取消；读到按 mix 处理。未设置过的新装默认 auto（最优调度）。 */
    val mode: String get() = (prefs.getString(KEY_MODE, "auto") ?: "auto").let { if (it == "off") "mix" else it }

    /** 最优调度的目标（gold/points/attr）：写 UI 存 + 走通用 setopt 通道让 QQ 侧立即生效。 */
    val autoGoal: String get() = prefs.getString(KEY_AUTO_GOAL, "attr") ?: "attr"

    fun setAutoGoal(value: String) = guard {
        prefs.edit().putString(KEY_AUTO_GOAL, value).apply()
        touched()
        setOpt(KEY_AUTO_GOAL, value)
    }

    fun setAttr(value: String) = guard {
        prefs.edit().putString(ModulePrefs.KEY_ATTR, value).apply()
        touched()
        send("setattr") { putExtra("attr", value) }
    }

    fun setMode(key: String) = guard {
        prefs.edit().putString(KEY_MODE, key).apply()
        touched()
        send("adv") { putExtra("op", key) }
    }

    /** 只冒险模式固定刷的冒险名（空=自动）：写 UI 存 + 走通用 setopt 通道让 QQ 侧立即生效。 */
    val advOnlyName: String get() = prefs.getString(KEY_ADV_ONLY_NAME, "") ?: ""

    fun setAdvOnlyName(value: String) = guard {
        prefs.edit().putString(KEY_ADV_ONLY_NAME, value).apply()
        touched()
        setOpt(KEY_ADV_ONLY_NAME, value)
    }

    /** 冒险目录（与排班选冒险同一份，QQ 侧读到后回传主存）：给主页「刷哪个」拼选项。 */
    fun adventureCatalog(): List<io.github.juwanhwang.autoqqpet.pet.JobCatalog.AdventureTier> =
        io.github.juwanhwang.autoqqpet.pet.JobCatalog.decodeAdventures(
            prefs.getString(io.github.juwanhwang.autoqqpet.pet.JobCatalog.KEY_ADVENTURES, "") ?: "",
        )

    /** 开启托管：先拉起 QQ（接收器是动态注册的，进程不在收不到），再下发，再起保活。 */
    fun startAuto() = guard {
        prefs.edit().putBoolean(ModulePrefs.KEY_AUTO_ON, true).apply()
        touched()
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(QQ)
                ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        send("auto") { putExtra("attr", attr) }
        KeepAliveService.start(ctx)
    }

    fun stopAuto() {
        // 停止永远放行：哪怕协议改版尚未重新确认，也必须能把正在跑的托管关掉
        prefs.edit().putBoolean(ModulePrefs.KEY_AUTO_ON, false).apply()
        touched()
        send("autooff")
    }

    // ── 设置 ─────────────────────────────────────────

    fun setCareer(type: Long) = guard {
        prefs.edit().putLong(JobCatalog.KEY_CAREER, type).apply()
        touched()
        send("setwork") { putExtra("career", type) }
    }

    fun requestCatalog() = send("joblist")

    fun requestRoster() = send("roster")

    fun requestDiag() = send("diag")

    /**
     * 通用设置通道（CONTRACT §1.1）：`setopt --es key <键> --es value <字符串>`。
     * prefs 由调用方先写好（QQ 不在线时靠主存兜底），这里只负责让在线的 QQ 立即生效并推一帧快照。
     */
    fun setOpt(key: String, value: Any) {
        send(SETOPT) { putExtra("key", key).putExtra("value", value.toString()) }
    }

    /** 通用开关：写 prefs + 发 `action op=on|off`；action 为 [SETOPT] 时走通用通道。 */
    fun setToggle(key: String, value: Boolean, action: String?) = guard {
        prefs.edit().putBoolean(key, value).apply()
        touched()
        when (action) {
            null -> Unit
            SETOPT -> setOpt(key, value)
            else -> send(action) { putExtra("op", if (value) "on" else "off") }
        }
    }

    /** 通用单选：写 prefs + 发 `action op=<值>`；action 为 [SETOPT] 时走通用通道。 */
    fun setChoice(key: String, value: String, action: String?) = guard {
        prefs.edit().putString(key, value).apply()
        touched()
        when (action) {
            null -> Unit
            SETOPT -> setOpt(key, value)
            else -> send(action) { putExtra("op", value) }
        }
    }

    /** 通用整数：写 prefs + 发 `action op=<op> n=<值>`；action 为 [SETOPT] 时走通用通道。 */
    fun setInt(key: String, value: Int, action: String?, op: String?) = guard {
        prefs.edit().putInt(key, value).apply()
        touched()
        when (action) {
            null -> Unit
            SETOPT -> setOpt(key, value)
            else -> send(action) {
                if (op != null) putExtra("op", op)
                putExtra("n", value)
            }
        }
    }

    // ── 提醒（主页提醒条）────────────────────────────

    /** 本地已划掉的提醒（`id|incident`；旧文本提醒为 `legacy|hash`）：QQ 那边确认前先藏起来。 */
    fun dismissedAlerts(): Set<String> = prefs.getStringSet(KEY_ALERT_DISMISSED, emptySet()).orEmpty()

    /** 划掉：本地先藏，再写回 QQ（`alert op=dismiss`），同一事件 QQ 不再挂。 */
    fun dismissAlert(id: String, incident: String) {
        val key = if (id == "legacy") incident else "$id|$incident"
        val cur = dismissedAlerts().toMutableSet().apply { add(key) }
        // 只留最近 60 条，防无限增长
        val keep = if (cur.size > 60) cur.toList().takeLast(60).toSet() else cur
        prefs.edit().putStringSet(KEY_ALERT_DISMISSED, keep).apply()
        touched()
        // 旧文本提醒（老后端）只在本地藏；新后端写回 QQ
        if (id != "legacy") send("alert") { putExtra("op", "dismiss").putExtra("id", id).putExtra("incident", incident) }
    }

    /** 立即召回（提醒条“立即召回”）：不等 71%。 */
    fun recallNow() = guard { send("recall") { putExtra("mode", "immediate") } }

    /** 拉起 QQ（去领信箱）。@return 是否拉起成功 */
    fun openQQ(): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(QQ) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** 配置自检（R10）：QQ 现跑一次 cfgcheck，runner 落后就重建；结果随下一帧快照回来。 */
    fun cfgCheck() = send("cfgcheck") { putExtra("op", "heal") }

    /** 立即刷新：托管开着就马上跑一轮，关着只读数值并推一帧快照。 */
    /** 历史广告补领：查询当前进度。 */
    fun queryAdSweep() = send(AdSweepState.CMD_STATUS)

    /** 历史广告补领：开始一轮（QQ 侧在后台跑，进度走广播）。每条数可在设置里调（默认 500）。 */
    fun startAdSweep() = send("adall") {
        putExtra("max", prefs.getInt(SettingKeys.AD_SWEEP_MAX.key, 500))
        putExtra("background", if (prefs.getBoolean(SettingKeys.AD_BACKGROUND_CLAIM_ON.key, false)) "1" else "0")
    }
    fun cancelAdSweep() = send("adsweepstop")
    fun refreshNow() = send("refresh")

    /** 只要一帧最新快照（不发网络请求）。 */
    fun askSnapshot() = send("snapshot")

    /** 名单整份写回（op=set）：先把主存副本写对，界面当场就是新值。[mode] 非空时连模式一起存（wl=白名单/bl=黑名单）。 */
    fun setList(which: String, uins: Collection<String>, mode: String? = null) = guard {
        val csv = uins.joinToString(",")
        val key = when (which) { "hire" -> "hire_list"; else -> "friend_list" }
        val modeKey = when (which) { "hire" -> "hire_list_mode"; else -> "friend_list_mode" }
        val entries = if (mode != null) mapOf(key to csv, modeKey to mode) else mapOf(key to csv)
        Store.mergeFriendsLocal(ctx, entries)
        send(which) {
            putExtra("op", "set").putExtra("uins", csv)
            if (mode != null) putExtra("mode", mode)
        }
    }

    /**
     * 火花名单整份写回。先写主存副本（界面当场是新值），再发 `spark` 给 QQ。
     * 顺手把旧的 `spark_wl` 从副本里删掉（迁移在 QQ 侧做，这里只是别再显示）。
     */
    fun setSparkBlacklist(bl: Collection<String>) = guard {
        val b = bl.map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct()
        Store.mergeFriendsLocal(ctx, mapOf("spark_bl" to b.joinToString(","), "spark_wl" to ""))
        send("spark") { putExtra("bl", b.joinToString(",")) }
    }

    /** 火花名单模式（wl=只踩名单内 / bl=名单内不踩，默认）。 */
    fun setSparkListMode(mode: String) = guard {
        Store.mergeFriendsLocal(ctx, mapOf("spark_list_mode" to mode))
        send("spark") { putExtra("op", "mode").putExtra("mode", mode) }
    }

    /** 让 QQ 现读一次火花列表（结果经主存 friends_json 回来）。 */
    fun requestSparks() = send("sparks")

    /** 排班：本地 + 主存 + QQ 三处一起写（广播丢了也能靠主存自愈）。 */
    fun saveSchedule(model: Schedule) = guard {
        val text = Schedule.encode(model)
        prefs.edit().putString(Schedule.PREF_KEY, text).apply()
        touched()
        Store.mergeSettingsLocal(ctx, mapOf(Schedule.PREF_KEY to text))
        send("schedule") { putExtra("json", text) }
    }

    /** 排班 JSON：本地副本优先，没存过再从主存的设置快照里找（QQ 重装后也能接回来）。 */
    fun loadSchedule(): Schedule {
        val local = prefs.getString(Schedule.PREF_KEY, null)
        val s = if (!local.isNullOrBlank()) Schedule.parse(local)
        else Schedule.parse(runCatching { Store.settingsJson(ctx).optString(Schedule.PREF_KEY) }.getOrNull())
        return withUniqueIds(s)
    }

    /**
     * 步骤 id 去重：列表与拖拽都按 id 认人，重复会让列表直接崩。
     * 旧版用“毫秒 % 100000”生成 id，理论上会撞；撞了就给后一个换新 id（进度只按 id 记，影响仅限撞号的那一步）。
     */
    fun withUniqueIds(s: Schedule): Schedule {
        val seen = HashSet<String>()
        var changed = false
        val steps = s.steps.map { st ->
            if (seen.add(st.id)) st else {
                changed = true
                st.copy(id = newStepId(seen).also { seen.add(it) })
            }
        }
        return if (changed) s.copy(steps = steps) else s
    }

    fun newStepId(taken: Set<String>): String {
        var n = System.currentTimeMillis() % 100000
        while ("s$n" in taken) n = (n + 1) % 100000
        return "s$n"
    }

    companion object {
        const val ACTION_CMD = "io.github.juwanhwang.autoqqpet.CMD"
        const val QQ = "com.tencent.mobileqq"
        const val KEY_MODE = "adv_mode"
        const val KEY_ADV_ONLY_NAME = "adv_only_name"
        const val KEY_AUTO_GOAL = "auto_goal"
        const val SETOPT = "setopt"
        const val KEY_ALERT_DISMISSED = "alert_dismissed"
        const val PROJECT_URL = "https://github.com/Juwan-Hwang/Autoqqpet"
        const val ISSUE_URL = "https://github.com/Juwan-Hwang/Autoqqpet/issues/new"
        const val GROUP_URL = "https://t.me/Juwan_H"
    }
}
