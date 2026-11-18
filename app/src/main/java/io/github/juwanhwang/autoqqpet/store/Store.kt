package io.github.juwanhwang.autoqqpet.store

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.juwanhwang.autoqqpet.log.PLog
import org.json.JSONObject

/**
 * 「设置 / 当日进度」的**主存**，放在模块自己的数据目录里。
 *
 * 为什么要挪：这两份数据以前一直写在**被 Hook 应用（QQ）的数据目录**里 —— 那是别人家的目录。
 * 真机 2026-10-11 撞上：QQ 从 9.3.65 升到 9.3.75（重装）后，用户精心调好的任务模式、打工方向、
 * 当日累计与台账全部消失（`counts` 变空、设置回默认）。模块自有目录只有卸载本模块才会清，
 * QQ 怎么重装/清数据都不受影响，所以配置的真值应该放这儿。
 *
 * 通道为什么是广播而不是 ContentProvider：Android 11+ 的包可见性限制下，
 * 跨包访问 provider 需要**调用方**（QQ）在它的清单里声明 `<queries>`，而我们改不了 QQ 的清单；
 * 真机实测 QQ 进程里报 `Failed to find provider info …` / `Unknown authority`（shell 同一 URI 却能通）。
 * 广播不受包可见性限制，且这条通道本来就在用 —— QQ 进程的 `STATUS`/`JOBS` 心跳就是这么进来的。
 */
object Store {

    private const val TAG = "Store"
    const val FILE = "autoqqpet_store"
    const val ACTION = "io.github.juwanhwang.autoqqpet.STORE"

    /**
     * 跨进程广播的签名级权限（清单里同名 `<permission>`，protectionLevel=signature）：发送方
     * 必须是与本模块同签名的应用，也就是只有模块 App 自己。挂在「接收方跑在 QQ 进程里、
     * 合法发送方是模块 App」的注册点上（命令广播、SideStore 的 STORE 接收端）。
     * 反方向（发送方是 QQ 进程）挂不了：QQ 用腾讯的签名，永远拿不到这个权限——那几条通道
     * 的决定与理由见清单注释与 docs/CONTRACT.md §3.4。
     */
    const val PERMISSION_BROADCAST = "com.juwanh.autoqqpet.permission.BROADCAST"
    const val EXTRACT_SETTINGS = "settings_json"
    const val EXTRACT_DAILY = "daily_json"
    /** 好友名单与最近一次 PK 对手也进主存：它们存在 QQ 目录，界面直接读不到（“拉黑刚才那个”需要知道是谁）。 */
    const val EXTRACT_FRIENDS = "friends_json"
    /**
     * 诊断快照（QQ 进程生成的纯文本：版本、读数、目录候选与淘汰原因、当日决策计数、当日日志尾部）。
     * 为什么必须走这个通道：日志写在 QQ 进程的文件里，模块 App 是另一个应用，无 Root 就读不到。
     */
    const val EXTRACT_DIAG = "diag_json"
    const val EXTRACT_DIAG_AT = "diag_at"
    /** 打工目录（模块 App 存的 `work_catalog` Tier 串）随 ask 回信送回 QQ，灌 QQ 侧岗位缓存（R3）。 */
    const val EXTRACT_CATALOG = "work_catalog"
    /** QQ 进程刚起来、本地配置是空的时候用：向模块 App 要一次备份。 */
    const val EXTRA_ASK = "ask"

    /** 模块 App 自己的包名（= applicationId，与 namespace 不同）。 */
    const val MODULE_PKG = "com.juwanh.autoqqpet"

    /**
     * 需要同步进主存的设置键（与 QQ 进程读的 `autoqqpet_settings` 键同名同型）。
     * 由 [SettingKeys] 登记表派生：新增设置只登记一处。主题、协议指纹、目录缓存这些 UI 自用键不在其中。
     */
    val SETTINGS_KEYS: Set<String> = SettingKeys.ALL.map { it.key }.toSet()

    /**
     * 存的是 Long 的键。JSON 不分 Int/Long（5 会被解成 Int），而本地用 getLong 读 ——
     * 恢复时必须按这份名单强制写回 Long，否则“备份里有值、读出来却是默认”
     * （真机 2026-10-11 模拟重装验证：`work_career` 没恢复成 type=5）。设置部分由登记表派生，`pend.end` 是当日进度里的。
     */
    val LONG_KEYS: Set<String> = SettingKeys.ALL.filter { it.type == SettingKeys.Type.LONG }.map { it.key }.toSet() +
        setOf("pend.end", "pend.sub", "gold.start.at")

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 收到 QQ 进程送来的备份，落盘（模块自有目录）。 */
    fun saveBackup(context: Context, slot: String, json: String?) {
        if (json.isNullOrEmpty()) return
        if (slot == EXTRACT_DAILY && dailyOverwriteBlocked(json, prefs(context).getString(EXTRACT_DAILY, null))) {
            // 防清空保护：主存里已是今天有内容的账本，QQ 上交一份同日空账本（典型成因是那一侧
            // 刚被重装/清数据、本地还没记上数）——收下就会把唯一的完整备份换成空壳，
            // 下次恢复只能从零重记（2026-10-19 卸载重装模块 App 后今日统计被清的事故链一环）。
            PLog.i(TAG, "🛡 QQ 上交的是今天的空账本，主存里已有今天的记录，不收下，保留主存这份")
            return
        }
        prefs(context).edit().putString(slot, json).apply()
        PLog.d(TAG, "已收下主存备份 $slot（${json.length} 字节）")
    }

    /** 日账本载荷算不算「空」：没有一行计数（`counts` 缺失/空白）且没有台账（`ledger` 缺失/空白）。保守口径，两样都缺才算空。 */
    internal fun dailyLedgerIsEmpty(counts: String?, ledger: String?): Boolean =
        counts.isNullOrBlank() && ledger.isNullOrBlank()

    /**
     * 防清空保护的判定（纯函数，可测）：**同一天的账本，有内容的一侧永远不能被空的一侧盖掉。**
     * 拦下的充要条件：来件带有效日号（≥0）且与本地同日、来件是空账本、本地已有内容。
     * 跨天不拦（新一天的账本为空是正常的，清零走 DailyStore 的跨天归档）；来件没带日号不拦
     * （证不出同日）；两边都空不拦（首次安装、都还没记数，收下无害）。
     */
    internal fun dailyEmptyOverwriteBlocked(
        incomingDay: Int, incomingCounts: String?, incomingLedger: String?,
        localDay: Int, localCounts: String?, localLedger: String?,
    ): Boolean =
        incomingDay >= 0 && incomingDay == localDay &&
            dailyLedgerIsEmpty(incomingCounts, incomingLedger) &&
            !dailyLedgerIsEmpty(localCounts, localLedger)

    /** [dailyEmptyOverwriteBlocked] 的 JSON 入口：任一侧解析不出就按「不拦」走原有行为。 */
    internal fun dailyOverwriteBlocked(incomingJson: String, localJson: String?): Boolean {
        val inc = runCatching { JSONObject(incomingJson) }.getOrNull() ?: return false
        val local = runCatching { JSONObject(localJson ?: "") }.getOrNull() ?: return false
        return dailyEmptyOverwriteBlocked(
            inc.optInt("day", -1), inc.optString("counts", ""), inc.optString("ledger", ""),
            local.optInt("day", -1), local.optString("counts", ""), local.optString("ledger", ""),
        )
    }

    /** 诊断快照单独收：它不是配置，不参与恢复，只给界面打包用。 */
    fun saveDiag(context: Context, text: String, at: Long) {
        prefs(context).edit().putString(EXTRACT_DIAG, text).putLong(EXTRACT_DIAG_AT, at).apply()
        PLog.i(TAG, "🧾 已收下诊断快照（${text.length} 字符）")
    }

    fun read(context: Context, slot: String): String? = prefs(context).getString(slot, null)

    /**
     * 界面改设置时直接合并进主存（同进程，不用 binder）。
     * 这样即使 QQ 当时没在跑、广播送不到，改动也已经落到持久主存。
     */
    fun mergeSettingsLocal(context: Context, delta: Map<String, Any?>) {
        val sp = prefs(context)
        val obj = runCatching { JSONObject(sp.getString(EXTRACT_SETTINGS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        // 只合并真变化的键（R10：值没变就不盖新戳，免得“没改动的旧值”因为戳更新而压过 QQ 侧的新值）
        val usable = delta.filterKeys { it in SETTINGS_KEYS }.filterValues { it != null }
            .filter { (k, v) -> !sameValue(obj.opt(k), v) }
        if (usable.isEmpty()) return
        usable.forEach { (k, v) -> obj.put(k, v) }
        val now = System.currentTimeMillis()
        obj.put(SettingsStamp.KEY, now)
        // 分键新鲜度：记下每个键的本地修改时刻（receiveHostSettings 用），过期记录顺手清掉
        val ats = runCatching { JSONObject(sp.getString(SETTINGS_LOCAL_KEYS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        usable.keys.forEach { ats.put(it, now) }
        ats.keys().asSequence().toList().forEach { if (now - ats.optLong(it, 0L) > 600_000L) ats.remove(it) }
        sp.edit().putString(EXTRACT_SETTINGS, obj.toString()).putString(SETTINGS_LOCAL_KEYS, ats.toString()).apply()
    }

    /**
     * 启动补齐的增量（纯函数，可测）：登记表里、主存完全没有、界面 prefs 里有值的键 → 界面值。
     * 只认「主存缺失」：主存已有的键哪怕与界面值不同也绝不进增量（安全边界见 [backfillMissingSettings]）。
     * 主存里是 JSON null 的按缺失算——那不算有值。
     */
    internal fun missingSettingsDelta(storeJson: JSONObject, uiValues: Map<String, Any?>): Map<String, Any?> =
        SETTINGS_KEYS
            .filter { k -> (!storeJson.has(k) || storeJson.opt(k) == JSONObject.NULL) && uiValues[k] != null }
            .associateWith { k -> uiValues[k] }

    /**
     * 启动自愈：把「界面里有、主存里完全没有」的设置键用界面值补进主存
     * （走 [mergeSettingsLocal] 的逐键合并与分键戳记账），返回实际补进的键数。
     *
     * 为什么需要：整份替换时期（2026-10-19 修复前）被 QQ 上交删掉的键只剩界面 prefs 里有
     * （真机：息屏时暂停、夜间暂停起止、结算等待、天色不对自动召回 5 个）。通道修好之后
     * 今后的改动不会再丢，但存量缺口不会自己长回来：启动时主存→界面是单向的（防 R10），
     * 界面→主存只有改动监听的逐键 delta，用户不重新拨一次就永远缺着，QQ 侧也永远拿不到。
     *
     * 为什么只补缺失是安全的（与 R10 的分界）：R10 防的是「给旧值盖新戳、压过更新的值」。
     * 补齐只往主存的**空位**写：凡是已经过戳比较进了主存的值（界面改动、QQ 上交合并）一律不碰，
     * 不存在把谁的新值压下去。而「主存缺、界面有」只可能来自「这份值从没成功同步过」——
     * QQ 拿到设置只有两条路：从主存灌回（主存没有，它手里就不会有更新的值）、或界面改设置
     * 的广播（送达时界面监听已先把同一值合进主存，主存就不会缺）。所以不存在「QQ 攥着一份
     * 更新的值、等着被补齐值压过」的情形，补进去的界面值就是用户最后设定的那份；它按
     * [mergeSettingsLocal] 的规矩记分键戳，之后与 QQ 上交的合并裁决与「用户刚重新拨过一次」无异。
     *
     * 补齐只写主存、不写界面 prefs，不会触发界面的镜像监听（它只盯界面 prefs），无回环；
     * 也不直接碰 QQ——主存补齐后，走既有的 QQ 拉取 / 上交逐键合并链，键自然会到 QQ 侧。
     */
    fun backfillMissingSettings(context: Context): Int {
        val ui = context.getSharedPreferences(io.github.juwanhwang.autoqqpet.ModulePrefs.NAME, Context.MODE_PRIVATE)
        val delta = missingSettingsDelta(settingsJson(context), ui.all)
        if (delta.isEmpty()) return 0
        mergeSettingsLocal(context, delta)
        val after = settingsJson(context)
        val filled = delta.keys.filter { after.has(it) }
        if (filled.isNotEmpty()) PLog.i(TAG, "🧩 主存缺 ${filled.size} 项设置，已用界面现值补齐：${filled.joinToString("、")}")
        return filled.size
    }

    /** JSON 里的值与 prefs 里的值是否相同（JSON 不分 Int/Long，数字按 long 比）。 */
    fun sameValue(a: Any?, b: Any?): Boolean = when {
        a == null || a == JSONObject.NULL -> b == null
        a is Number && b is Number -> a.toLong() == b.toLong()
        else -> a == b
    }

    /** 正在把 QQ 侧的设置写进界面 prefs（R8）：Application 的镜像监听见此标志就不往回合并（防回环）。 */
    @Volatile var applyingFromHost: Boolean = false
        private set

    /** 界面本地改设置的分键时刻（主存 prefs 里的 JSON）：合并 QQ 上交时，刚改过的键不许被旧值盖。 */
    const val SETTINGS_LOCAL_KEYS = "settings_local_keys"

    /** 分键新鲜度窗口：界面改完这么久内，QQ 上交的同键旧值不采纳（它整包戳新只是因为改了别的键）。 */
    const val SETTINGS_GUARD_WINDOW_MS = 90_000L

    fun localKeyAts(context: Context): Map<String, Long> = runCatching {
        val o = JSONObject(prefs(context).getString(SETTINGS_LOCAL_KEYS, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it, 0L) }.filterValues { it > 0 }
    }.getOrDefault(emptyMap())

    /** 在 [windowMs] 内被界面改过的键（纯函数，可测）。 */
    fun freshLocalKeys(ats: Map<String, Long>, now: Long, windowMs: Long = SETTINGS_GUARD_WINDOW_MS): Set<String> =
        ats.filterValues { now - it in 0..windowMs }.keys

    /**
     * 设置合并（纯函数，可测）：incoming 逐键并入 local，但 [protectedKeys] 里本地有的值保留——
     * 这些键刚在界面改过，QQ 那份还没跟上。
     */
    fun mergeSettingsJson(localJson: String, incomingJson: String, protectedKeys: Set<String>): String {
        val local = runCatching { JSONObject(localJson) }.getOrDefault(JSONObject())
        val inc = runCatching { JSONObject(incomingJson) }.getOrDefault(JSONObject())
        inc.keys().forEach { k ->
            if (k in protectedKeys && local.has(k)) return@forEach
            local.put(k, inc.opt(k))
        }
        return local.toString()
    }

    /**
     * 收下 QQ 上交后应落盘的主存内容（纯函数，可测）：逐键合并，**绝不整份替换**。
     * 上交里有的键以它为准（[protectedKeys] 除外，保留本地）；主存独有的键原样保留；
     * 最后盖 [now]——QQ 那份戳是它自己落盘时盖的、比主存新，不盖新戳它会按“比我旧”
     * 拒收回灌，只能等重启才对齐（2026-10-17 真机连带症状）。
     */
    internal fun mergedHostSettings(localJson: String, incomingJson: String, protectedKeys: Set<String>, now: Long): String {
        val mergedObj = JSONObject(mergeSettingsJson(localJson, incomingJson, protectedKeys))
        mergedObj.put(SettingsStamp.KEY, now)
        return mergedObj.toString()
    }

    /**
     * 收到 QQ 上交的设置（R8/R10）：
     *  - 戳比主存旧 → 拒收，并把主存这份回送 QQ（谁新听谁的）；
     *  - 刚在界面改过的键（分键新鲜度）→ QQ 上交的旧值不采纳；
     *  - 收下的一律逐键合并后落盘并回送 QQ，合并结果同步写进 `autoqqpet_ui`（界面的 prefs 监听因此 bump dataVersion 重读）。
     *
     * 为什么绝不能整份替换：QQ 上交的只有它本地 prefs 里实际有的键。用户在 QQ 没在跑、
     * 命令广播没送达时改的设置只落了界面与主存，QQ 那份里没有——旧逻辑整份替换会把
     * 这些键从主存里静默删掉，此后 QQ 每次从主存恢复也拿不到、只能读默认值（2026-10-19
     * 真机：息屏暂停等几个新设置在界面开着，主存与 QQ 侧却都没有，改了跟没改一样）。
     * 分键新鲜度也救不了这种丢法：protected 只从上交里 differing 的键里选，上交根本不含这些键。
     * @return 写进界面 prefs 的键数（-1=拒收）
     */
    fun receiveHostSettings(context: Context, json: String): Int {
        val inc = runCatching { JSONObject(json) }.getOrNull() ?: return 0
        val sp = prefs(context)
        val cur = runCatching { JSONObject(sp.getString(EXTRACT_SETTINGS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (!SettingsStamp.accept(inc.optLong(SettingsStamp.KEY, 0L), cur.optLong(SettingsStamp.KEY, 0L))) {
            PLog.i(TAG, "🗂 QQ 上交的设置比主存旧（${inc.optLong(SettingsStamp.KEY)} < ${cur.optLong(SettingsStamp.KEY)}），不收；回送主存这份")
            sendToHost(context, cur.toString(), null)
            return -1
        }
        val differs = inc.keys().asSequence()
            .filter { k -> SettingKeys.def(k) != null && !sameValue(inc.opt(k), cur.opt(k)) }.toSet()
        val protected = freshLocalKeys(localKeyAts(context), System.currentTimeMillis()).intersect(differs)
        if (protected.isNotEmpty()) {
            PLog.i(TAG, "🛡 ${protected.joinToString("、")} 刚在界面改过，QQ 上交的旧值不采纳；合并后回送 QQ")
        }
        val merged = mergedHostSettings(cur.toString(), json, protected, System.currentTimeMillis())
        saveBackup(context, EXTRACT_SETTINGS, merged)
        val n = applyToUi(context, JSONObject(merged))
        sendToHost(context, merged, null)
        return n
    }

    /** 界面进程启动时：主存 → 界面 prefs（只补差异）。不反向：界面 prefs 可能是旧的（R8 之前 QQ 侧改动从不回写界面）。 */
    fun applyStoreToUi(context: Context): Int = applyToUi(context, settingsJson(context))

    private fun applyToUi(context: Context, src: JSONObject): Int {
        val ui = context.getSharedPreferences(io.github.juwanhwang.autoqqpet.ModulePrefs.NAME, Context.MODE_PRIVATE)
        val all = ui.all
        val ed = ui.edit()
        var n = 0
        src.keys().forEach { k ->
            val def = SettingKeys.def(k) ?: return@forEach
            val v = SettingKeys.coerce(def, src.opt(k)) ?: return@forEach
            if (sameValue(v, all[k])) return@forEach
            if (SettingKeys.put(ed, def, v)) n++
        }
        if (n > 0) {
            applyingFromHost = true
            try { ed.commit() } finally { applyingFromHost = false }
            PLog.i(TAG, "🗂 QQ 侧设置有 $n 项与界面不同，已同步到界面")
        }
        return n
    }
    /** 主存里那份设置（界面用它显示"手机上实际生效的设置"，不必等 QQ 应答）。 */
    fun settingsJson(context: Context): JSONObject = runCatching {
        JSONObject(prefs(context).getString(EXTRACT_SETTINGS, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    /**
     * 界面改名单时直接合并进主存的那份副本。
     *
     * 为什么需要：名单的真身在 QQ 进程里，界面读的是主存副本；改完等 QQ 广播回来再刷，
     * 用户看到的就是“保存了但状态没变，退出重进才对”（真机反馈）。
     * 界面自己先把副本写对（同进程，不用 binder），QQ 随后上传的是同一份值，不会分叉。
     */
    /** 界面最后一次本地改名单的时刻：比它旧的 QQ 回信不许盖名单键（见 [receiveFriends]）。 */
    const val FRIENDS_LOCAL_AT = "friends_local_at"

    /** 用户在界面里亲手定的名单键：新鲜度只认本地编辑，不许被旧回信盖掉。 */
    private val FRIENDS_USER_KEYS = listOf("friend_list", "friend_list_mode", "hire_list", "hire_list_mode", "spark_bl", "spark_list_mode", "blacklist", "whitelist")

    fun mergeFriendsLocal(context: Context, delta: Map<String, Any?>) {
        val usable = delta.filterValues { it != null }
        if (usable.isEmpty()) return
        val sp = prefs(context)
        val obj = runCatching { JSONObject(sp.getString(EXTRACT_FRIENDS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        usable.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString(EXTRACT_FRIENDS, obj.toString())
            .putLong(FRIENDS_LOCAL_AT, System.currentTimeMillis()).apply()
    }

    /**
     * 收下 QQ 送来的好友副本。真机 2026-10-16：打开名单面板时界面顺手要了一次名册刷新，
     * 那份回信在服务器读完后才生成（内容是改之前的名单），落盘时把刚保存的新名单整表盖回旧值，
     * 副标题就一直停在旧人数。带 `__at` 的回信若比界面最后一次本地编辑旧，名单键以本地为准、其余照收；
     * 没有 `__at` 的旧版回信维持整表收下（同版升级后不会出现）。
     */
    fun receiveFriends(context: Context, json: String) {
        val sp = prefs(context)
        val merged = mergeFriendsJson(sp.getString(EXTRACT_FRIENDS, null), json, sp.getLong(FRIENDS_LOCAL_AT, 0L))
        sp.edit().putString(EXTRACT_FRIENDS, merged).apply()
    }

    /** [receiveFriends] 的纯逻辑：返回应落盘的 JSON。 */
    internal fun mergeFriendsJson(localJson: String?, incomingJson: String, localAt: Long): String {
        val incoming = runCatching { JSONObject(incomingJson) }.getOrNull() ?: return localJson ?: incomingJson
        val incomingAt = incoming.optLong("__at", 0L)
        if (localAt <= 0L || incomingAt <= 0L || incomingAt >= localAt) return incomingJson
        val local = runCatching { JSONObject(localJson ?: "{}") }.getOrDefault(JSONObject())
        for (k in FRIENDS_USER_KEYS) if (local.has(k)) incoming.put(k, local.get(k))
        return incoming.toString()
    }

    /** 把主存内容回送给 QQ 进程（恢复用）。显式指名目标包，不需要对方“看得见”我们。 */
    fun sendToHost(context: Context, settingsJson: String?, dailyJson: String?, friendsJson: String? = null, catalog: String? = null) {
        val i = Intent(ACTION).setPackage(QQ_PKG).apply {
            putExtra(EXTRA_FROM, MODULE_PKG)
            catalog?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRACT_CATALOG, it) }
            settingsJson?.let { putExtra(EXTRACT_SETTINGS, it) }
            dailyJson?.let { putExtra(EXTRACT_DAILY, it) }
            friendsJson?.let { putExtra(EXTRACT_FRIENDS, it) }
        }
        runCatching { context.sendBroadcast(i) }.onFailure { PLog.d(TAG, "回送主存失败：${it.message?.take(70)}") }
    }

    /** QQ 进程侧发送用的构造器（同一个 action，不指名包就没人收）。 */
    fun hostIntent(
        settingsJson: String? = null, dailyJson: String? = null, friendsJson: String? = null, ask: String? = null,
        diag: String? = null,
    ): Intent = Intent(ACTION).setPackage(MODULE_PKG).apply {
        putExtra(EXTRA_FROM, QQ_PKG)
        ask?.let { putExtra(EXTRA_ASK, it) }
        settingsJson?.let { putExtra(EXTRACT_SETTINGS, it) }
        dailyJson?.let { putExtra(EXTRACT_DAILY, it) }
        friendsJson?.let { putExtra(EXTRACT_FRIENDS, it) }
        diag?.let {
            putExtra(EXTRACT_DIAG, it)
            putExtra(EXTRACT_DIAG_AT, System.currentTimeMillis())
        }
    }

    private const val QQ_PKG = "com.tencent.mobileqq"
    const val EXTRA_FROM = "from"
}
