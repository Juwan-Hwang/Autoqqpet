package io.github.juwanhwang.autoqqpet.ui.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.juwanhwang.autoqqpet.EnabledState
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.keepalive.KeepAliveService
import io.github.juwanhwang.autoqqpet.keepalive.StatusStore
import io.github.juwanhwang.autoqqpet.pet.AdSweepState
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.store.Store
import org.json.JSONObject
import java.time.LocalDate

/**
 * 界面的数据源：把散在各处的跨进程信息（心跳内存态、主存里的当日进度、模块偏好）
 * 融合成一份 [PetSnapshot]，每秒刷新一次（只读内存与 SharedPreferences 缓存，很便宜）。
 *
 * 心跳广播额外再收一份**全部字段**（不只 StatusReceiver 认识的那几个），
 * 这样后端以后往心跳里加字段，界面不改代码就能拿到，交给 [HeartbeatFields] 决定怎么显示。
 * 只在界面可见时注册（onStart/onStop），不在后台耗电。
 */
class LiveState(context: Context) {

    private val ctx = context.applicationContext
    val backend = Backend(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val cache: SharedPreferences = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE)

    var snapshot by mutableStateOf(PetSnapshot.EMPTY)
        private set
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    /** 历史广告补领进度（QQ 进程广播，断线后从缓存恢复）。 */
    var adSweep by mutableStateOf(AdSweepState.Progress.parse(cache.getString(KEY_AD_SWEEP, null)) ?: AdSweepState.Progress())
        private set

    /** 每当跨进程数据（目录、名单、诊断快照）有变化就 +1，供设置页等重读。 */
    var dataVersion by mutableIntStateOf(0)
        private set

    /** 回到前台时调用：电池豁免/精确闹钟这类“系统状态型”摘要不在任何 prefs 里，只能靠重组重读。 */
    fun bumpData() { dataVersion++ }

    private var started = false
    private var lastDailyRaw: String? = null
    private var lastDaily: DailyParsed = DailyParsed.EMPTY

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                ACTION_STATUS -> intent.extras?.let { saveHeartbeat(it) }
                AdSweepState.ACTION -> saveAdSweep(intent)
                JobCatalog.ACTION -> dataVersion++
            }
            // 心跳先到 StatusReceiver 还是先到这里不确定：稍等一拍再读，拿到的就是它写完的值
            main.postDelayed({ refresh() }, 120)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = refresh()
    }

    /**
     * 跨进程数据变了 → 重读（R8：QQ 侧写设置后 StoreReceiver 把差异写进 `autoqqpet_ui`，这里收到）。
     * 一次同步可能连写十几个键：**防抖 150ms** 只 bump 一次；本监听只读不写，所以不会回环。
     */
    private val bumpRunnable = Runnable { dataVersion++; refresh() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(bumpRunnable)
        main.postDelayed(bumpRunnable, 150)
    }

    private fun saveAdSweep(intent: Intent) {
        val p = AdSweepState.Progress.parse(intent.getStringExtra(AdSweepState.EXTRA)) ?: return
        adSweep = p
        cache.edit().putString(KEY_AD_SWEEP, p.json()).apply()
    }
    fun start() {
        if (started) return
        started = true
        val exported = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        val local = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        runCatching {
            // 发送方是 QQ（另一个应用），必须声明导出
            val f = IntentFilter().apply { addAction(ACTION_STATUS); addAction(JobCatalog.ACTION); addAction(AdSweepState.ACTION) }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(statusReceiver, f, exported)
            else ctx.registerReceiver(statusReceiver, f)
        }
        runCatching {
            val f = IntentFilter(KeepAliveService.ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(refreshReceiver, f, local)
            else ctx.registerReceiver(refreshReceiver, f)
        }
        backend.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Store.prefs(ctx).registerOnSharedPreferenceChangeListener(prefsListener)
        main.post(ticker)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(ticker)
        main.removeCallbacks(bumpRunnable)
        runCatching { ctx.unregisterReceiver(statusReceiver) }
        runCatching { ctx.unregisterReceiver(refreshReceiver) }
        backend.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        Store.prefs(ctx).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    /** 仅供截图测试：直接给一份快照（不经过跨进程读取）。 */
    internal fun debugSet(s: PetSnapshot, at: Long) {
        snapshot = s
        now = at
    }

    /** 主存里当日进度的原文（排班编辑器读每步进度用）。 */
    fun dailyRaw(): String? = Store.read(ctx, Store.EXTRACT_DAILY)

    fun refresh() {
        now = System.currentTimeMillis()
        snapshot = runCatching { read(now) }.getOrElse { snapshot }
    }

    // ── 读 ───────────────────────────────────────────

    private fun read(now: Long): PetSnapshot {
        val enabled: Boolean? = when {
            EnabledState.enabled -> true
            // 启用状态由 libxposed 异步回调推过来：进程刚起的两秒内还不能下“没启用”的结论
            SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 2500 -> null
            else -> false
        }
        val hbAt = StatusStore.lastHeartbeat
        val status = if (hbAt > 0) StatusStore.status else null
        val hint = StatusParser.parse(status)
        val daily = daily()
        val full = fullSnapshot()
        val hb = heartbeat().let { (map, at) -> overlay(map, full) to maxOf(at, full?.at ?: 0L) }
        // 快照新鲜时以它为准（没任务就是没任务）；只有快照缺失/过期才用当日进度与台账推断（B1：不显示假任务）
        val snapFresh = full != null && now - full.at <= PetSnapshot.STALE_MS
        val task = fullTask(now, full) ?: if (snapFresh) null else deriveTask(now, hint, daily, hb.first)
        val autoOn = backend.autoOn

        val phase = when {
            enabled == null -> Phase.CHECKING
            enabled == false -> Phase.MODULE_OFF
            !autoOn -> Phase.PAUSED
            hbAt == 0L -> if (task != null) Phase.TASK else Phase.CONNECTING
            now - hbAt > PetSnapshot.STALE_MS -> Phase.LOST
            StatusStore.netBadAt > 0 -> Phase.OFFLINE
            hint.care -> Phase.CARE
            hint.employed || full?.task?.hired == true -> Phase.EMPLOYED
            task != null -> Phase.TASK
            // “宠物外出中”只在没有新鲜快照时按状态文本兜底（B1）：快照是 QQ 每 60s 读服务器后的真值，
            // 它说没有任务就是没有——旧版这里拿一条早已过期的“busy:…”状态串显示假的外出中
            hint.busy && (full == null || now - full.at > PetSnapshot.STALE_MS) -> Phase.OUTING
            else -> Phase.IDLE
        }

        return PetSnapshot(
            moduleEnabled = enabled,
            agreed = backend.agreed(),
            autoOn = autoOn,
            attr = backend.attr,
            mode = backend.mode,
            advOnlyName = backend.advOnlyName,
            autoGoal = backend.autoGoal,
            status = status,
            lastHeartbeatAt = hbAt,
            nextWakeAt = StatusStore.nextWakeAt,
            netBadAt = StatusStore.netBadAt,
            alert = StatusStore.alertText,
            task = task,
            phase = phase,
            today = fullToday(full) ?: daily.today,
            timeline = daily.timeline,
            heartbeat = hb.first,
            heartbeatAt = hb.second,
            full = full,
        )
    }

    // ── v2 完整快照（首选数据源） ─────────────────────

    private var fullRaw: String? = null
    private var fullCache: FullSnapshot? = null

    /** StatusReceiver 每帧写进内存与 prefs 的快照：内存优先（最新），进程刚起时读 prefs 里的上一帧。 */
    private fun fullSnapshot(): FullSnapshot? {
        val raw = StatusStore.snapshotJson.ifEmpty { backend.prefs.getString(ModulePrefs.KEY_STATUS_SNAPSHOT, null).orEmpty() }
        if (raw == fullRaw) return fullCache
        fullRaw = raw
        fullCache = FullSnapshot.parse(raw)
        return fullCache
    }

    /** 让数值磁贴等按旧键名读的地方也能吃到快照里的宠物数值（心跳里没有的才补）。 */
    private fun overlay(map: Map<String, Any?>, full: FullSnapshot?): Map<String, Any?> {
        val p = full?.pet ?: return map
        val out = HashMap(map)
        fun put(k: String, v: Any?) { if (v != null && out[k] == null) out[k] = v }
        put("hunger", p.hunger); put("clean", p.clean); put("feel", p.feel)
        put("gold", p.gold); put("total", p.total); put("pet_name", p.name)
        return out
    }

    private fun fullTask(now: Long, full: FullSnapshot?): CurrentTask? {
        val t = full?.task ?: return null
        val kind = t.kind ?: return null
        val end = t.endsAt ?: return null
        if (end <= now) return null
        val start = t.startedAt ?: if (t.totalSec > 0) end - t.totalSec * 1000 else null
        return CurrentTask(kind, t.name, start, end, narrative = t.narrative, mine = t.mine)
    }

    private fun fullToday(full: FullSnapshot?): TodayStats? {
        val t = full?.today ?: return null
        if (t.day != LocalDate.now().toString()) return null
        return TodayStats(
            study = t.count("study"),
            work = t.count("work"),
            adventure = t.count("adventure"),
            studyWorkMinutes = t.studyWorkMin,
            adventureMinutes = t.adventureMin,
            recalls = t.count("recall"),
            tired = full.health.payTired,
            studyWorkSec = t.studyWorkSec,
            adventureSec = t.adventureSec,
        )
    }

    /**
     * 当前任务的来源按可信度排：
     *  1) 心跳里明说的 task_*（后端扩展字段，最准）；
     *  2) 当日进度里的 pend.*（精确到秒，但上交有防抖，可能晚一拍）；
     *  3) 台账最后一条（开工即强制上交；时长按分钟向上取整，最多多算 59 秒）。
     * 名字只来自状态文本 `started:<种类>:<名字>` 或心跳 task_name；对不上就不显示名字，不猜。
     */
    private fun deriveTask(now: Long, hint: StatusHint, d: DailyParsed, hb: Map<String, Any?>): CurrentTask? {
        HeartbeatFields.task(hb, now)?.let { return it }
        val name = { kind: TaskKind -> if (TaskKind.ofTag(hint.startedTag) == kind) hint.startedDetail else null }
        val pendKind = TaskKind.ofTag(d.pendKind)
        if (pendKind != null && d.pendEnd > now) {
            val start = if (d.pendDurMin > 0) d.pendEnd - d.pendDurMin * 60_000L else null
            return CurrentTask(pendKind, name(pendKind), start, d.pendEnd)
        }
        val last = d.timeline.lastOrNull { !it.duplicate } ?: return null
        val end = last.at + last.seconds * 1000L
        if (end <= now || last.at > now + 60_000) return null
        return CurrentTask(last.kind, name(last.kind), last.at, end)
    }

    private data class DailyParsed(
        val today: TodayStats?,
        val timeline: List<LedgerEntry>,
        val pendKind: String?,
        val pendEnd: Long,
        val pendDurMin: Int,
    ) {
        companion object {
            val EMPTY = DailyParsed(null, emptyList(), null, 0, 0)
        }
    }

    private fun daily(): DailyParsed {
        val raw = Store.read(ctx, Store.EXTRACT_DAILY)
        // 同一天的数据如果没变就不重新解析；跨天时 raw 不变但“今天”变了，所以把日期也算进缓存键
        val key = raw + "#" + LocalDate.now().toEpochDay()
        if (key == lastDailyRaw) return lastDaily
        lastDailyRaw = key
        lastDaily = runCatching { parseDaily(raw) }.getOrDefault(DailyParsed.EMPTY)
        return lastDaily
    }

    private fun parseDaily(raw: String?): DailyParsed {
        if (raw.isNullOrBlank()) return DailyParsed.EMPTY
        val o = JSONObject(raw)
        val isToday = o.optLong("day", -1) == LocalDate.now().toEpochDay()
        val pendKind = o.optString("pend.kind").ifBlank { null }
        val pendEnd = o.optLong("pend.end", 0)
        val pendDur = o.optInt("pend.dur", 0)
        if (!isToday) return DailyParsed(null, emptyList(), pendKind, pendEnd, pendDur)
        val counts = o.optString("counts").split(';').mapNotNull {
            val kv = it.split(':')
            if (kv.size == 2) kv[0].trim() to (kv[1].trim().toIntOrNull() ?: 0) else null
        }.toMap()
        // “今天”唯一口径（v2.1.1）：与时间线同一份台账，去重、只算已完成（TodayTally）
        val ledger = o.optString("ledger").split('\n').filter { it.isNotBlank() }
        val t = io.github.juwanhwang.autoqqpet.pet.TodayTally.tally(ledger, System.currentTimeMillis(), LocalDate.now())
        val today = TodayStats(
            study = t.study,
            work = t.work,
            adventure = t.adventure,
            studyWorkMinutes = t.studyWorkMin,
            adventureMinutes = t.adventureMin,
            studyWorkSec = t.studyWorkSec, adventureSec = t.adventureSec,
            recalls = counts["recall"] ?: 0,
            tired = (counts["pay-tired"] ?: 0) > 0,
        )
        return DailyParsed(today, LedgerParser.parse(ledger, 0), pendKind, pendEnd, pendDur)
    }

    // ── 心跳全量字段缓存 ─────────────────────────────

    private var hbCacheRaw: String? = null
    private var hbCache: Pair<Map<String, Any?>, Long> = emptyMap<String, Any?>() to 0L

    private fun heartbeat(): Pair<Map<String, Any?>, Long> {
        val raw = cache.getString(KEY_HB, null)
        if (raw == hbCacheRaw) return hbCache
        hbCacheRaw = raw
        hbCache = runCatching {
            val o = JSONObject(raw ?: "{}")
            val map = HashMap<String, Any?>()
            o.keys().forEach { k -> map[k] = o.opt(k) }
            Pair<Map<String, Any?>, Long>(map.toMap(), cache.getLong(KEY_HB_AT, 0L))
        }.getOrDefault(emptyMap<String, Any?>() to 0L)
        return hbCache
    }

    private fun saveHeartbeat(b: Bundle) {
        runCatching {
            val o = JSONObject()
            for (k in b.keySet()) {
                @Suppress("DEPRECATION")
                when (val v = b.get(k)) {
                    null -> Unit
                    is Number, is Boolean, is String -> o.put(k, v)
                    else -> o.put(k, v.toString())
                }
            }
            cache.edit().putString(KEY_HB, o.toString()).putLong(KEY_HB_AT, System.currentTimeMillis()).apply()
        }
    }

    companion object {
        const val ACTION_STATUS = "io.github.juwanhwang.autoqqpet.STATUS"
        private const val KEY_AD_SWEEP = "ad_sweep"
        private const val CACHE = "autoqqpet_ui_cache"
        private const val KEY_HB = "heartbeat_json"
        private const val KEY_HB_AT = "heartbeat_at"
    }
}
