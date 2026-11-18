package io.github.juwanhwang.autoqqpet.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.store.SettingsStamp
import io.github.juwanhwang.autoqqpet.store.Store
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * QQ 进程侧访问【模块自有主存】的客户端 —— 只走广播。
 *
 * 为什么不是 ContentProvider：Android 11+ 跨包读 provider 需要**调用方**（QQ）在清单里声明
 * `<queries>`，而我们改不了 QQ 的清单。真机实测：shell 直接 `content call` 同一个 URI 通，
 * QQ 进程里却是 `Failed to find provider info … / Unknown authority`。广播不受包可见性限制，
 * 而且这条通道本来就在用（QQ 的 `STATUS`/`JOBS` 心跳就是它）。
 *
 * 兼容性口径（三种情况都要照顾到，否则就是“修好新的、弄坏旧的”）：
 *  1) **旧版本升上来**：主存是空的 → ask 收不到东西（或收到空）→ 继续用本地值，随后正常上交；
 *  2) **QQ 被重装/清数据**：本地空、主存有 → ask 拿回备份灌回本地，用户的设置与当日进度不再连带丢；
 *  3) **模块 App 够不着**（被卸载、冷启动太慢超时）：完全退回本改动之前的行为，绝不因此停摆。
 */
object SideStore {

    private const val TAG = "SideStore"
    /** 等回信的时间上限：模块 App 冷启动一般 1s 内，超时就先用本地值跑，迟到再补。 */
    private const val ASK_WAIT_MS = 2_500L
    /** 当日进度上交的最小间隔：决策周期本就 ≥45s，这里只是防抖。 */
    private const val PUSH_MIN_MS = 50_000L

    @Volatile
    private var appCtx: Context? = null
    @Volatile
    private var installed = false
    @Volatile
    private var lastPush = 0L

    /** 收到过的备份内容（等 DailyStore/FriendPolicy 建好后再应用当日进度与名单）。 */
    @Volatile
    private var pendingDailyJson: String? = null

    @Volatile
    private var pendingFriendsJson: String? = null

    /** 本次是否接上了广播主存通道（设置页用它如实说明配置存在哪儿）。 */
    val channelInstalled: Boolean get() = installed

    /** 本次启动是否真的从主存恢复过（日志/设置要如实说明配置存在哪儿）。 */
    @Volatile
    var restored: Boolean = false
        private set

    /** 迟到恢复时用来通知外层“设置变了，重建一下”的回调。 */
    @Volatile
    var onRestored: (() -> Unit)? = null

    private var latch = CountDownLatch(1)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getStringExtra(Store.EXTRA_FROM) != Store.MODULE_PKG) return
            var touched = false
            i.getStringExtra(Store.EXTRACT_SETTINGS)?.let { json ->
                val n = applySettings(c, json)
                if (n > 0) {
                    touched = true
                    PLog.i(TAG, "🗂 主存里有 ${n} 项设置比本地新，已灌回")
                }
            }
            i.getStringExtra(Store.EXTRACT_DAILY)?.let { pendingDailyJson = it }
            i.getStringExtra(Store.EXTRACT_FRIENDS)?.let { pendingFriendsJson = it }
            i.getStringExtra(Store.EXTRACT_CATALOG)?.let { cat ->
                runCatching { io.github.juwanhwang.autoqqpet.hook.HostRuntime.seedJobCache(cat) }
                    .onSuccess { n -> if (n > 0) PLog.i(TAG, "💼 用模块 App 的打工目录补了 $n 个职业的岗位缓存") }
            }
            restored = true
            latch.countDown()
            if (touched) runCatching { onRestored?.invoke() }
        }
    }

    /** 注册接收端（QQ 进程内），必须在发 ask 之前调用。 */
    fun install(ctx: Context) {
        if (installed) return
        appCtx = ctx
        runCatching {
            // 挂签名级权限（2026-10-19 审计 F2）：本端的合法发送方只有模块 App（Store.sendToHost），
            // 它与本模块同签名、持有该权限；同机其它应用发来的 STORE 广播到不了这里。
            // 反方向（QQ → 模块 App 的 StoreReceiver）发送方是 QQ 自己，挂不了，见清单注释。
            androidx.core.content.ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(Store.ACTION),
                Store.PERMISSION_BROADCAST, null, androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            installed = true
        }.onFailure { PLog.d(TAG, "主存接收端注册失败 ：${it.message?.take(60)}") }
    }

    /**
     * 向模块 App 要一次备份并等一小会儿。返回是否拿到了内容。
     * 拿不到不报错也不重试：本改动之前的行为就是这样，绝不让循环卡在这儿。
     */
    fun requestRestore(ctx: Context): Boolean {
        install(ctx)
        if (!installed) return false
        latch = CountDownLatch(1)
        runCatching { ctx.sendBroadcast(Store.hostIntent(ask = "both")) }
        val got = runCatching { latch.await(ASK_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (got && restored) PLog.i(TAG, "🗂 已向模块 App 取回主存备份（设置与当日进度）")
        else PLog.d(TAG, "本次未取到主存备份")
        return got && restored
    }

    /** 当日进度：等 DailyStore 建好后由它来取。 */
    fun takePendingDaily(): String? = pendingDailyJson.also { pendingDailyJson = null }

    /** 好友名单与最近 PK 对手：等 FriendPolicy 建好后由它来取。 */
    fun takePendingFriends(): String? = pendingFriendsJson.also { pendingFriendsJson = null }

    /** 名单变更后上交主存（很小，一个 uin 集合 + 最近对手，不防抖）。带 `__at` 生成戳：主存据此拒收旧回信（见 Store.receiveFriends）。 */
    fun backupFriends(sp: SharedPreferences) {
        val ctx = appCtx ?: return
        val obj = JSONObject()
        sp.all.forEach { (k, v) ->
            // 只上交界面与恢复需要的部分：名单、自动拉黑记录、最近一次 PK 对手、好友名册
            if (v != null && (k.startsWith("whitelist") || k.startsWith("blacklist") || k.startsWith("friend_list") || k.startsWith("hire_list") ||
                    k.startsWith("auto:") || k.startsWith("last.pk") || k.startsWith("roster") || k.startsWith("spark"))
            ) obj.put(k, v)
        }
        obj.put("__at", System.currentTimeMillis())
        runCatching { ctx.sendBroadcast(Store.hostIntent(friendsJson = obj.toString())) }
    }

    /** 设置整份上交（键少、只在改动时发，不防抖）。带 `__at` 戳（R10），主存与界面据此判新旧（R8：界面收到即重读）。 */
    fun backupSettings(sp: SharedPreferences) {
        val ctx = appCtx ?: return
        val obj = JSONObject()
        sp.all.forEach { (k, v) -> if (v != null && k in Store.SETTINGS_KEYS) obj.put(k, v) }
        obj.put(SettingsStamp.KEY, sp.getLong(SettingsStamp.KEY, 0L))
        runCatching { ctx.sendBroadcast(Store.hostIntent(settingsJson = obj.toString())) }
    }

    /** 拒收旧回信的次数（诊断用）。 */
    @Volatile var staleRejected: Int = 0
        private set

    /**
     * 把诊断快照交给模块 App（一次性、体积可到上百 KB，不进防抖）。
     * 日志文件在 QQ 的私有目录里，界面进程无 Root 是读不到的，只能这样送过去。
     */
    fun sendDiag(text: String) {
        val ctx = appCtx ?: return
        runCatching { ctx.sendBroadcast(Store.hostIntent(diag = text)) }
            .onFailure { PLog.d(TAG, "诊断快照送不出去：${it.message?.take(60)}") }
    }

    /** 当日进度上交；[force] 用于开工/结算/跨天这类不能丢的节点，其余按 50s 防抖。 */
    fun backupDaily(sp: SharedPreferences, force: Boolean = false) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < PUSH_MIN_MS) return
        lastPush = now
        val obj = JSONObject()
        sp.all.forEach { (k, v) ->
            // 事件环形录体积最大且只供现场排障，不进主存；台账（ledger）留着，它才是事后对账的依据
            if (v != null && k != "events") obj.put(k, v)
        }
        runCatching { ctx.sendBroadcast(Store.hostIntent(dailyJson = obj.toString())) }
    }

    /**
     * 主动问模块 App 要一份最新设置（**非阻塞**：回信到达时由上面的 receiver 落盘）。
     * 为什么需要：界面改完只会发一次广播，QQ 当时不在/被冻结就丢了，然后“改了不生效”会一直挂到下次重启。
     * 循环里每 5 分钟补拉一次，最多晚 5 分钟自愈。
     */
    fun refreshSettings() {
        val ctx = appCtx ?: return
        if (!installed) return
        runCatching { ctx.sendBroadcast(Store.hostIntent(ask = "settings")) }
    }

    /** 把主存里那份设置灌回本地 prefs（键名与类型与本地完全一致，所以决策代码不用改）。 */
    private fun applySettings(ctx: Context, json: String): Int {
        val sp = ctx.getSharedPreferences("autoqqpet_settings", Context.MODE_PRIVATE)
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        // R10：时间戳守卫——主存那份比本地旧就整份拒收，并把本地这份上交让主存自愈
        val inAt = obj.optLong(SettingsStamp.KEY, 0L)
        val localAt = sp.getLong(SettingsStamp.KEY, 0L)
        if (!SettingsStamp.accept(inAt, localAt)) {
            staleRejected++
            PLog.i(TAG, "🗂 主存回信比本地旧（${inAt} < ${localAt}），不回灌；改为上交本地这份")
            backupSettings(sp)
            return 0
        }
        val ed = sp.edit()
        val before = sp.all
        var changed = 0
        obj.keys().forEach { k ->
            if (k == SettingsStamp.KEY) return@forEach
            val v = obj.get(k)
            // 只统计真变化的键：主存回包里每次都是全量，拿“键数”当变化量会让循环每 5 分钟重建一次
            val differs = when (v) {
                is Boolean -> before[k] as? Boolean != v
                is String -> before[k] as? String != v
                is Number -> (before[k] as? Number)?.toLong() != v.toLong()
                else -> true
            }
            if (!differs) return@forEach
            val def = io.github.juwanhwang.autoqqpet.store.SettingKeys.def(k)
            val wrote = when {
                // 登记过的键按登记类型写（JSON 不区分 Int/Long：work_career 本地按 Long 读，不强制就“备份里有、读出来是默认”）
                def != null -> io.github.juwanhwang.autoqqpet.store.SettingKeys.put(ed, def, v)
                k in Store.LONG_KEYS && v is Number -> { ed.putLong(k, v.toLong()); true }
                v is Int -> { ed.putInt(k, v); true }
                v is Long -> { ed.putLong(k, v); true }
                v is Boolean -> { ed.putBoolean(k, v); true }
                v is String -> { ed.putString(k, v); true }
                else -> false
            }
            if (!wrote) return@forEach
            changed++
        }
        if (inAt > localAt) ed.putLong(SettingsStamp.KEY, inAt)
        if (changed > 0 || inAt > localAt) ed.apply()
        return changed
    }
}
