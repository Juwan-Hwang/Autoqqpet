package io.github.juwanhwang.autoqqpet.pet

import android.content.Context
import io.github.juwanhwang.autoqqpet.log.PLog

/**
 * 每日持久化（幂等基础）：按天记录各动作次数、已结算的 storyId、上次执行时间。
 * 跨天自动清零计数；storyId 结算去重避免重复领奖。存于 QQ 进程内 SharedPreferences。
 */
class DailyStore(context: Context) {

    /**
     * 计数（"counts" 整串）的读-改-写锁：主循环、PK 旁路、杂事等通道会并发运行，
     * increment 是对整串 CSV 先读后写，不上锁时同一窗口的两次增数后写覆盖先写、计数静默丢失
     * （真机 2026-10-18：日志 10 条 PK 胜利，昨日报告只剩 8 次）。所有走 parseCounts 的
     * 读改写入口（increment / setCount / noteDone / noteHire）统一锁在这把锁上。
     */
    private val countsLock = Any()

    private val prefs = context.applicationContext.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
    private val appCtx = context.applicationContext

    init {
        // 【任意一次写入都顺带交一份到主存】挂监听而不是逐个改写法：以后新增的计数字段不会漏掉同步。
        // SideStore.backupDaily 内部有 50s 防抖，事件录/台账频繁写也不会一直发广播；
        // 开工/结算/跨天这种不能丢的节点另走 [syncUpNow] 强制上交。
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, _ -> runCatching { SideStore.backupDaily(prefs) } }
        }
    }

    /** 上交当日进度到主存（模块自有目录）；关键节点用这个强制一发，不等防抖。上交前先把界面要读的每步进度现算回填（见 [refreshSchSteps]）。 */
    fun syncUpNow() {
        runCatching { refreshSchSteps() }
        runCatching { SideStore.backupDaily(prefs, force = true) }
    }

    /**
     * 启动时从主存恢复当日进度。真机 2026-10-11：QQ 被重装后 QQ 目录里的累计与台账全没了，
     * 靠这一步找回（主存那份若是旧的一天，[rollDayIfNeeded] 会照旧清零，不会把昨天的量带进今天）。
     */
    fun adoptFromStore(): Int {
        val json = SideStore.takePendingDaily() ?: return 0
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return 0
        // 防清空保护：主存回送的是今天的空账本、本地已有今天的记录 → 不许整表盖回。
        // 空账本的典型成因是主存那份曾被一份空上交占住（见 Store.saveBackup 的同口径拦截），
        // 或模块 App 刚重装完主存还没攒下东西。拦下后把本地这份强制上交，主存随即自愈。
        if (io.github.juwanhwang.autoqqpet.store.Store.dailyEmptyOverwriteBlocked(
                obj.optInt("day", -1), obj.optString("counts", ""), obj.optString("ledger", ""),
                prefs.getInt("day", -1), prefs.getString("counts", ""), prefs.getString("ledger", ""),
            )
        ) {
            PLog.i("DailyStore", "🛡 主存备份是今天的空账本，本地已有今天的记录，不覆盖；把本地这份重新上交主存")
            syncUpNow()
            return 0
        }
        // 同一天的恢复不能整表覆盖：本地可能刚记上主存备份里还没有的数（真机 2026-10-16：
        // 04:10 刚领的福袋计数被 04:11 的恢复盖掉，coinbag 4 次领成变 3 次）。同日时对
        // 单调类数据做合并：计数/奖励/决策统计逐项取大、疲劳与冒险分钟取大、id 集取并集、
        // 台账按 storyId 并集、金币曲线按时刻并集；其余键（pend.* 等状态）仍以备份为准。
        val sameDay = obj.optInt("day", -1) == prefs.getInt("day", -2)
        val ed = prefs.edit()
        var n = 0
        obj.keys().forEach { k ->
            val v = obj.get(k)
            when {
                // 同 SideStore：pend.end 是按 Long 读的，JSON 会把它降级成 Int，必须强制写回
                k in io.github.juwanhwang.autoqqpet.store.Store.LONG_KEYS && v is Number -> ed.putLong(k, v.toLong())
                v is Int -> ed.putInt(k, v)
                v is Long -> ed.putLong(k, v)
                v is Boolean -> ed.putBoolean(k, v)
                v is String -> ed.putString(k, v)
            }
            n++
        }
        if (sameDay) {
            for (k in listOf("counts", "rewards", "dec_stats")) {
                if (obj.has(k)) ed.putString(k, mergePairsMax(prefs.getString(k, "") ?: "", obj.optString(k, "")))
            }
            for (k in listOf("fatigue", "adv_minutes")) {
                if (obj.has(k)) ed.putInt(k, maxOf(prefs.getInt(k, 0), obj.optInt(k, 0)))
            }
            for (k in listOf("ledger_ids", "done_ids", "settled", "counted_ids", "started_ids")) {
                if (obj.has(k)) ed.putString(k, unionCsv(prefs.getString(k, "") ?: "", obj.optString(k, "")))
            }
            if (obj.has("ledger")) ed.putString("ledger", mergeLedger(prefs.getString("ledger", "") ?: "", obj.optString("ledger", "")))
            if (obj.has("gold_curve")) ed.putString("gold_curve", mergeCurve(prefs.getString("gold_curve", "") ?: "", obj.optString("gold_curve", "")))
        }
        ed.apply()
        PLog.i("DailyStore", "🗂 已从主存恢复当日进度 $n 项：${snapshot()}")
        return n
    }

    companion object {
        /** 任务台账比日志长寿：每条任务一行，专治“汇总数对不上但现场日志已被冲掉”这类问题。
         * 上限要盖住一整天的行数：「今天」计数是从台账现算的，行被挤掉计数就跟着丢——
         * 最密口径（45 秒冒险 + 10 秒间隔）一天约 1600 趟，旧上限 300 在真机 2026-10-16
         * 把早上的学工行挤没了（当天 376 趟结算，「今天」学习/打工显示 0 次）。 */
        private const val LEDGER_CAP = 3000
        /** 单日日志的体积上限：超了就只保留后半（真机上一天最多几千行，正常碰不到）。 */
        private const val LOG_MAX_BYTES = 8 * 1024 * 1024
        /** 读日志的默认行数上限：诊断包够用又不把内存拉满。 */
        private const val LOG_TAIL_MAX = 3000
        /** 金币曲线最多保留的点数（10 分钟一个点，一天 144 个）。 */
        private const val CURVE_CAP = 160

        /** "k:v;k:v" 两份逐项取大合并（计数/奖励/决策统计/金币曲线共用形状）。 */
        internal fun mergePairsMax(local: String, backup: String): String {
            fun parse(s: String): LinkedHashMap<String, Long> {
                val m = LinkedHashMap<String, Long>()
                s.split(";").forEach { part ->
                    val kv = part.split(":")
                    if (kv.size == 2) kv[1].toLongOrNull()?.let { m[kv[0]] = it }
                }
                return m
            }
            val m = parse(local)
            parse(backup).forEach { (k, v) -> m[k] = maxOf(m[k] ?: Long.MIN_VALUE, v) }
            return m.entries.joinToString(";") { "${it.key}:${it.value}" }
        }

        /**
         * 当天日志里「🧾 结算记录」行的 rewards 汇总（按归一后的名字）：这些行写的是解析后未过滤的
         * 完整 rewards，旧包被脏名整条丢掉的结算还能从这里找回。洗不干净的名字（疲劳文案等伪条目）
         * 跳过；行被长度截断留下的残对解析不出数值，也跳过。同一 storyId 只计第一条，
         * 防同一故事的重复结算记录行被重复汇总。
         */
        internal fun ledgerRewardSums(lines: List<String>): Map<String, Int> {
            val sums = LinkedHashMap<String, Int>()
            val seenIds = HashSet<String>()
            for (line in lines) {
                if (!line.contains("结算记录")) continue
                // 同一 storyId 只计第一条：同一故事被重复写出的结算记录行（旧版被拒重试等）不重复汇总
                val id = line.substringAfter("id=", "").substringBefore(' ').trim()
                if (id.isNotEmpty() && !seenIds.add(id)) continue
                val seg = line.substringAfter("rewards=", "").trim()
                if (seg.isEmpty()) continue
                for (pair in seg.split(",")) {
                    val name = pair.substringBeforeLast(':', "").trim()
                    val amount = pair.substringAfterLast(':', "").trim().toIntOrNull() ?: continue
                    if (name.isEmpty()) continue
                    val clean = io.github.juwanhwang.autoqqpet.pet.parse.Parsers.cleanRewardName(name) ?: continue
                    sums[clean] = (sums[clean] ?: 0) + amount
                }
            }
            return sums
        }

        /** 逗号 id 集取并集（本地顺序在前，备份独有的追加在后）。 */
        internal fun unionCsv(local: String, backup: String, cap: Int = 600): String =
            (local.split(',') + backup.split(',')).filter { it.isNotEmpty() }.distinct().takeLast(cap).joinToString(",")

        /** 台账按 storyId（第 4 段）并集：本地行在前，备份独有的行按备份顺序追加。 */
        internal fun mergeLedger(local: String, backup: String): String {
            fun idOf(line: String): String = line.split('|').getOrNull(3).orEmpty()
            val kept = local.split('\n').filter { it.isNotBlank() }.toMutableList()
            val seen = kept.map { idOf(it) }.filter { it.isNotEmpty() }.toHashSet()
            backup.split('\n').filter { it.isNotBlank() }.forEach { line ->
                val id = idOf(line)
                if (id.isEmpty() || id !in seen) { kept += line; if (id.isNotEmpty()) seen += id }
            }
            return kept.takeLast(LEDGER_CAP).joinToString("\n")
        }

        /** 金币曲线按时间戳并集（同戳本地优先），按时间升序。 */
        internal fun mergeCurve(local: String, backup: String): String =
            mergePairsMax(local, backup).split(";").filter { it.isNotBlank() }
                .sortedBy { it.substringBefore(':').toLongOrNull() ?: 0L }
                .takeLast(CURVE_CAP).joinToString(";")
    }

    /** 本地日期（与 FriendPolicy 共用 [Day] 口径，避免各自实现漂移）。 */
    private fun today(): Int = Day.today()

    /** 若跨天则重置计数；首次升版先丢弃旧版脏累数据。 */
    private fun rollDayIfNeeded() {
        if (prefs.getInt("schema", 1) < 2) {
            // 旧版 fatigue 从不清零（曾累到 7390min），且含冒险时长；一次性丢弃，否则今天会被误判“学工已满 8h”。
            prefs.edit().remove("fatigue").remove("adv_minutes").putInt("schema", 2).apply()
        }
        if (!prefs.getBoolean("journal_v2", false)) {
            // 统一账本（2.1.13）一次性衔接：旧版步进度只存在 sch_steps 里、不在台账行上，
            // 原样存进 legacy 覆盖层，今天余下时间 stepProgress = 覆盖层 + 台账现算，跨天自动失效；
            // fatigue/adv_minutes 旧存量作废（改由台账现算，旧行种类与时长都在、数得回来），
            // 这两个键从此只作“人工修正偏移量”用（见 fatigueMinutes）。
            val e = prefs.edit().putBoolean("journal_v2", true)
            (prefs.getString("sch_steps", "") ?: "").takeIf { it.isNotEmpty() }
                ?.let { e.putString("sch_steps_legacy", it) }
            e.remove("fatigue").remove("adv_minutes").apply()
        }
        val stored = prefs.getInt("day", -1)
        val t = today()
        if (stored != t) {
            // 先把昨天收成一份报告（清零之前；报告本身跨天保留，供界面与 report 广播读）
            if (stored > 0) runCatching { archiveReport(stored) }
            // 冒险学习值按天重开（必须赶在 counts 清零前结算）：先把昨天「池子还活着时」的
            // 每趟实收存成种子（adv_ev_live），再清掉 a* 学习项。学习均值若跨天累计、又混进
            // 到顶后的 0 金币趟，只会收敛到活/死两段的混合值（2026-10-17 实测约为真实期望的一半），
            // 还与求解器的金币池封顶重复扣账；按天重开后均值只反映当天活池样本，种子负责开局。
            if (stored > 0) {
                val counts = parseCounts()
                val advGold = counts["adv_gold"] ?: 0
                // 冒险趟数改从台账现算：counts 里的 adventure 自统一账本起不再单独记（见 counts()）
                val advTrips = journalStarted("adventure")
                val liveTrips = if (prefs.getBoolean("adv_gold_capped", false))
                    (advTrips - (counts["adv_nogold_streak"] ?: 0)).coerceAtLeast(0) else advTrips
                if (advGold > 0 && liveTrips >= 10)
                    prefs.edit().putString("adv_ev_live", (advGold.toDouble() / liveTrips).toString()).apply()
                val lm = learnedMap()
                if (lm.keys.any { it.startsWith("a") })
                    prefs.edit().putString("reward_avg", learnedText(lm.filterKeys { !it.startsWith("a") })).apply()
            }
            // 必须连 fatigue/adv_minutes 一起清：之前只清 counts/settled，导致“今日学工时长”永久累积，
            // 累到 12h 后疲劳系数永远 0.1，且“满 8 小时只冒险”会从此把学工永久堵死。
            prefs.edit()
                .putInt("day", t)
                .remove("counts")
                .remove("settled")
                .remove("fatigue")
                .remove("adv_minutes")
                .remove("counted_ids")     // 任务进账去重集也按天重新开始
                .remove("done_ids")        // “已完成”去重集同上
                .remove("pay_peak")         // 当日收益峰值（疲惫判定依据）每天重新取基准
                .remove("pay_first")        // 当日各档首读（降幅展示）
                .remove("pay_peak_bak")     // 清除前的存档也只属于当天，隔日恢复会把昨天的峰值带进今天
                .remove("ledger")           // 台账与 id 集同样按天开新页
                .remove("ledger_ids")
                .remove("sch_steps")        // 排班的每步进度与游标也按天重新开始
                .remove("sch_peak")         // 步进度倒退哨兵的高水位同上
                .remove("sch_steps_legacy") // 统一账本衔接覆盖层同上（只在升版当天存在）
                .remove("rbuf")             // 台账实收缓冲（条目未落行先到的实收）按天清
                .remove("sch_state")
                .remove("dec_stats")        // 当日决策计数按天清（诊断靠它看“一整天为什么冒险”）
                .remove("events")           // 旧的 prefs 环形录彻底退役，不再写也不再占空间
                .remove("last_task_end")    // 任务间隔起算点按天重开（昨晚的结束时间不拦今天第一件）
                .remove("hire_notice_binds")    // 雇佣通知的绑定按天重开（通知本身就是当天的）
                .remove("bonus_caps")       // 雇佣加成上限表按天重开（本金随宠物状态浮动，昨天的上限不带进今天）
                .remove("gold.start")       // 今日起始金币 / 金币曲线 / 实收奖励 / 雇佣支出：报告已归档，按天重开
                .remove("gold.start.at")
                .remove("gold.last")        // 基线校正用的上次观测余额与自家支出累计，同样按天重开
                .remove("gold.self")
                .remove("gold_curve")
                .remove("rewards")
                .remove("settle_drop")
                .remove("adv_gold_capped")  // 冒险金币到顶标记按天重开（累计/连段在 counts 里，随 counts 一起清）
                .remove("feed-fail-ban-until")  // 照顾 Ban 冷却的到期时刻按天重开（命中次数在 counts 里同清）
                .remove("bath-fail-ban-until")
                .apply()
            dropOlderLogs()                 // 日志文件一天一个，保留 log_keep_days 天（默认 3）
        }
    }

    fun count(action: String): Int = counts()[action] ?: 0

    fun increment(action: String) {
        rollDayIfNeeded()
        synchronized(countsLock) {
            val map = parseCounts()
            map[action] = (map[action] ?: 0) + 1
            prefs.edit().putString("counts", map.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
        }
    }

    /**
     * 直接设某个计数值（0 即删除）。只有 [increment] 不够：照顾失败次数在成功一次后必须归零，
     * 否则早上被拒三次、晚上就会误判成“服务端不让照顾”。计数仍然跨天自动清零（走 [rollDayIfNeeded]）。
     */
    fun setCount(action: String, value: Int) {
        rollDayIfNeeded()
        synchronized(countsLock) {
            val map = parseCounts()
            if (value > 0) map[action] = value else map.remove(action)
            prefs.edit().putString("counts", map.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
        }
    }

    /**
     * 记下“某个目录项本次读到的单位时间收益”（放大 100 倍存整数），返回今天此前见过的最高值（0=今日首读到）。
     * 峰值只升不降、跨天清零。
     *
     * 为什么用它判疲劳：以前用“学工累计满 8 小时”当阈值，那是拍定的时长，与游戏真实降效不对齐。
     * 而现在每轮本来就会把课程/岗位目录读一遍，里面就带着本次预计收益（打工工分、学习属性）：
     * 宠物疲劳时服务端会把这些数字整体压低，所以【同一条目录项今天比刚才读到的少了】才是真降效。
     */
    /** 今日该目录项的**首读**（展示降幅用）：没有就记下本次并返回 0。跨天随 counts 一起清。 */
    fun payFirst(key: String, valueX100: Int): Int {
        rollDayIfNeeded()
        val map = parsePairs("pay_first")
        val prev = map[key]
        if (prev != null) return prev
        map[key] = valueX100
        prefs.edit().putString("pay_first", writePairs(map)).apply()
        return 0
    }

    fun payPeak(key: String, valueX100: Int): Int {
        rollDayIfNeeded()
        val map = parsePairs("pay_peak")
        val prev = map[key] ?: 0
        if (valueX100 > prev) {
            map[key] = valueX100
            prefs.edit().putString("pay_peak", writePairs(map)).apply()
        }
        return prev
    }

    /** 调试用：把某个 key 的今日峰值抬高 n%（验证降效判定与影响）。 */
    fun bumpPayPeak(key: String, pct: Int) {
        if (key.isEmpty()) return
        rollDayIfNeeded()
        val map = parsePairs("pay_peak")
        val cur = map[key] ?: 0
        if (cur <= 0) return
        map[key] = cur * (100 + pct.coerceIn(1, 300)) / 100
        prefs.edit().putString("pay_peak", writePairs(map)).apply()
    }

    /**
     * 调试用：清掉指定 key（key 为空则全部）的基准，下次读到从头取。
     *
     * ⚙ 疲惫判定完全拿这份基准做比较（本轮实读 vs 今日最高），所以“清全部”等于**把当天的疲惫保护整局抹掉**：
     * 真机 2026-10-14 实测过——清基准后原本已判降效的 11 个档位全部“恢复”，宠物从“冒险兜底”变成继续打工。
     * 因此清之前先存档（只留最近一份），误清可用 [restorePayPeak] 拿回。
     */
    fun clearPayPeak(key: String) {
        rollDayIfNeeded()
        if (key.isEmpty()) {
            archivePayPeak()
            prefs.edit().remove("pay_peak").apply()
            return
        }
        archivePayPeak()
        val map = parsePairs("pay_peak")
        map.remove(key)
        prefs.edit().putString("pay_peak", writePairs(map)).apply()
    }

    /** 把当前整份基准存到备用键（覆盖式）；本来就是空的就不存，免得把“空”当成可恢复的备份。 */
    private fun archivePayPeak() {
        val cur = prefs.getString("pay_peak", "").orEmpty()
        if (cur.isBlank()) return
        prefs.edit().putString("pay_peak_bak", cur).apply()
    }

    /**
     * 恢复上一次 clear 前的基准。没有存档就返回 false（调用方得如实说，不能假装恢复了）。
     * 恢复后不删存档：万一又要清一次，clear 会用新值重新覆盖存档。
     */
    fun restorePayPeak(): Boolean {
        rollDayIfNeeded()
        val bak = prefs.getString("pay_peak_bak", "").orEmpty()
        if (bak.isBlank()) return false
        prefs.edit().putString("pay_peak", bak).apply()
        return true
    }

    /** 调试用：把当日峰值拼成人读串。 */
    fun payPeaks(): String {
        val map = parsePairs("pay_peak")
        if (map.isEmpty()) return "暂无（今天还没读到可比的目录项）"
        return map.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${"%.1f".format(it.value / 100.0)}" }
    }

    private fun parsePairs(key: String): MutableMap<String, Int> {
        val m = HashMap<String, Int>()
        (prefs.getString(key, "") ?: "").split(";").forEach {
            val kv = it.split(":")
            if (kv.size == 2) kv[1].toIntOrNull()?.let { v -> m[kv[0]] = v }
        }
        return m
    }

    private fun writePairs(map: Map<String, Int>): String =
        map.entries.joinToString(";") { "${it.key}:${it.value}" }

    private fun parseCounts(): MutableMap<String, Int> {
        val csv = prefs.getString("counts", "") ?: ""
        val m = HashMap<String, Int>()
        csv.split(";").forEach {
            val kv = it.split(":")
            if (kv.size == 2) kv[1].toIntOrNull()?.let { v -> m[kv[0]] = v }
        }
        return m
    }

    /** storyId 是否已结算过（幂等）。 */
    fun isSettled(storyId: String): Boolean {
        rollDayIfNeeded()
        return (prefs.getString("settled", "") ?: "").split(",").contains(storyId)
    }

    fun markSettled(storyId: String) {
        rollDayIfNeeded()
        val cur = (prefs.getString("settled", "") ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
        cur += storyId
        prefs.edit().putString("settled", cur.joinToString(",")).apply()
        syncUpNow()   // 结算是不可丢的节点：已结算名单立刻上交，重启后不会重复领同一份奖
    }

    /** 今日冒险金币已判到上限（经验判定：当日出过金币后连续多趟无金币）；跨天在 [rollDayIfNeeded] 里清。 */
    fun advGoldCapped(): Boolean {
        rollDayIfNeeded()
        return prefs.getBoolean("adv_gold_capped", false)
    }

    fun noteAdvGoldCapped() {
        rollDayIfNeeded()
        prefs.edit().putBoolean("adv_gold_capped", true).apply()
    }

    /** 观测到的冒险金币日上限（最近一次判到顶时当日的冒险金币累计；跨天保留，供求解器估池深）。0=还没观测到。 */
    fun advCapObserved(): Int = prefs.getInt("adv_cap_observed", 0)

    fun noteAdvCapObserved(v: Int) {
        if (v > 0) prefs.edit().putInt("adv_cap_observed", v).apply()
    }

    /** 快照，供日志/统计。 */
    fun snapshot(): String {
        rollDayIfNeeded()
        return "day=${today()} counts=[${prefs.getString("counts", "")}] 学工${fatigueMinutes()}min 冒险${adventureMinutes()}min " +
            "settled=${(prefs.getString("settled", "") ?: "").count { it == ',' } + 1}"
    }

    // ---- 疲劳时长累计：只统计【学习+打工】（跨天清零）----
    // ---- 统一账本（2.1.13）：台账是唯一账本，计数/步进度/实收/疲劳都从它现算 ----
    /**
     * 今天台账的条目（已去重）。纯读 prefs、不触发 rollDay——rollDay 自己也要在清账前用它数昨天的趟数。
     * [day] 缺省今天；rollDay 结算昨天种子时传存量日。
     */
    internal fun journalEntries(day: Int = today()): List<TodayTally.Entry> =
        TodayTally.dedupe(ledger().mapNotNull {
            TodayTally.parse(it, java.time.LocalDate.ofEpochDay(day.toLong()))
        })

    /** 今日已开工某类任务的趟数（结算被拒的不算——它从没真正完成过开工）。 */
    internal fun journalStarted(tag: String, day: Int = today()): Int =
        journalEntries(day).count { it.tag == tag && !it.rejected }

    /** 台账里全部条目的实收汇总（名称在写入时已归一）。[lines] 缺省今天台账；归档昨日报告时传昨日行。 */
    internal fun journalRewards(lines: List<String> = ledger()): Map<String, Int> {
        val day = java.time.LocalDate.ofEpochDay(today().toLong())
        val m = LinkedHashMap<String, Int>()
        for (e in TodayTally.dedupe(lines.mapNotNull { TodayTally.parse(it, day) }))
            for ((name, amt) in e.rewards) m[name] = (m[name] ?: 0) + amt
        return m
    }

    /** 改写某条台账行（按 storyId 找第一条非重复行）；行不存在返回 false。 */
    private fun rewriteLedgerLine(storyId: String, transform: (MutableList<String>) -> Unit): Boolean {
        if (storyId.isEmpty()) return false
        val lines = ledger().toMutableList()
        val idx = lines.indexOfFirst {
            val f = it.split('|')
            f.getOrNull(3)?.trim() == storyId && f.drop(4).none { m -> m.contains("重复") }
        }
        if (idx < 0) return false
        val f = lines[idx].split('|').toMutableList()
        transform(f)
        lines[idx] = f.joinToString("|")
        prefs.edit().putString("ledger", lines.joinToString("\n")).apply()
        return true
    }

    /** 给台账行设置「步=stepId@round」归属（已有归属不覆盖：第一次认领为准，换轮搬运走 [retagStepRound]）。 */
    fun retagLedgerStep(storyId: String, stepId: String, round: Int = scheduleRound()): Boolean {
        rollDayIfNeeded()
        if (stepId.isEmpty()) return false
        return rewriteLedgerLine(storyId) { f ->
            if (f.drop(4).none { it.startsWith("步=") }) f += "步=$stepId@$round"
        }
    }

    /** 结算被服务器明确拒绝：给台账行打「被拒」标——完成数与步进度从此不认它，疲劳仍按墙钟算。 */
    fun markLedgerRejected(storyId: String) {
        rollDayIfNeeded()
        rewriteLedgerLine(storyId) { f -> if (f.none { it == "被拒" }) f += "被拒" }
    }

    /**
     * 这趟已完成：把台账行的时长改成实际秒数并打「结」标（[noteDone] 的统一账本实现）。
     * 提前结束（召回）传实际进行秒数；到期结算传任务总时长，与名义时长相同、等于无操作。
     */
    fun attachOutcome(storyId: String, seconds: Long): Boolean {
        rollDayIfNeeded()
        if (seconds <= 0) return false
        return rewriteLedgerLine(storyId) { f ->
            val dur = if (seconds % 60 != 0L) "${seconds}s" else "${seconds / 60}m"
            if (f.size >= 3) f[2] = dur
            if (f.none { it == "结" }) f += "结"
        }
    }

    /**
     * 把一趟的结算实收记到它自己的台账行上（「赏=」字段，名称先归一、脏名丢弃——与 [addRewards] 同一过滤）。
     * 行还没落账（结算补记在同一流程后段才写行）时先存进缓冲，写行时由 [drainRewardBuffer] 并入。
     */
    fun attachRewards(storyId: String, rewards: List<Reward>) {
        rollDayIfNeeded()
        val clean = rewards.mapNotNull { r ->
            val key = io.github.juwanhwang.autoqqpet.pet.parse.Parsers.cleanRewardName(r.name)
                ?: r.name.takeIf { io.github.juwanhwang.autoqqpet.pet.parse.Parsers.rewardNameOk(it) }
                ?: return@mapNotNull null
            key.take(10) to r.amount.toInt()
        }
        if (clean.isEmpty() || storyId.isEmpty()) return
        val encoded = clean.groupBy({ it.first }, { it.second }).entries
            .joinToString(",") { "${it.key}:${it.value.sum()}" }
        val done = rewriteLedgerLine(storyId) { f ->
            val i = f.indexOfFirst { it.startsWith("赏=") }
            if (i >= 0) f[i] = "赏=$encoded" else f += "赏=$encoded"
        }
        if (!done) {
            val buf = (prefs.getString("rbuf", "") ?: "").split(';').filter { it.isNotEmpty() }.toMutableList()
            buf.removeAll { it.substringBefore('=') == storyId }
            buf += "$storyId=$encoded"
            prefs.edit().putString("rbuf", buf.takeLast(50).joinToString(";")).apply()
        }
    }

    /** 写台账行时取走该 storyId 缓冲中的实收编码（没有返回 null）。 */
    private fun drainRewardBuffer(storyId: String): String? {
        if (storyId.isEmpty()) return null
        val buf = (prefs.getString("rbuf", "") ?: "").split(';').filter { it.isNotEmpty() }
        val hit = buf.firstOrNull { it.substringBefore('=') == storyId } ?: return null
        prefs.edit().putString("rbuf", buf.filter { it != hit }.joinToString(";")).apply()
        return hit.substringAfter('=', "")
    }

    /**
     * 学工累计分钟（规划口径）：台账里学习+打工各行的时长之和（含进行中、含被拒——墙钟实打实占了）。
     * 冒险不计入——否则“满 8 小时后只能冒险”会被冒险自己堵死。
     * prefs 的 fatigue 键自统一账本起只存人工修正偏移（[setFatigue]/[addFatigue] 写它），正常恒为 0。
     */
    fun fatigueMinutes(): Int {
        rollDayIfNeeded()
        var sum = 0
        // 每行按开工时的分钟口径向上取整（与旧版 minutes 参数一致：90 秒的一趟旧记 2 分钟）
        for (e in journalEntries()) if (e.tag == "study" || e.tag == "work") sum += ((e.seconds + 59) / 60).toInt()
        return sum + prefs.getInt("fatigue", 0)
    }

    fun addFatigue(min: Int) {
        rollDayIfNeeded()
        prefs.edit().putInt("fatigue", prefs.getInt("fatigue", 0) + min).apply()
    }

    /** 直接设定当日学工分钟（修正口径）：把偏移量拨到目标值与台账现算之差。 */
    fun setFatigue(min: Int) {
        rollDayIfNeeded()
        val adj = prefs.getInt("fatigue", 0)
        prefs.edit().putInt("fatigue", min - (fatigueMinutes() - adj)).apply()
    }

    /** 今日冒险累计分钟（台账现算：各冒险行时长之和，结算后按实际时长修正）；adv_minutes 键只存人工偏移。 */
    fun adventureMinutes(): Int {
        rollDayIfNeeded()
        var sum = 0
        for (e in journalEntries()) if (e.tag == "adventure") sum += ((e.seconds + 59) / 60).toInt()
        return sum + prefs.getInt("adv_minutes", 0)
    }

    fun addAdventureMinutes(min: Int) {
        rollDayIfNeeded()
        prefs.edit().putInt("adv_minutes", prefs.getInt("adv_minutes", 0) + min.coerceAtLeast(0)).apply()
    }

    /**
     * 按 storyId 进账：同一条任务只记一次。这是台账唯一的开工写入口——行里同时带上排班归属
     * （[stepId]），步进度从此只是这批行的一种现算，不再另开一本（见 [stepProgress]）。
     * 为什么需要去重：tick 可能并发（改完设置会 3 秒后复查，与闹钟 tick 撞上），
     * 真机 2026-10-11 实测同一条 45 分钟打工被记了两遍（实际 4 次却计成 8 次、学工多算到 480min），
     * 直接把用户设的 8 小时预算假用完，整天只能去冒险。
     */
    fun countTaskOnce(storyId: String, tag: String, minutes: Int, adventureMinutes: Boolean = false, seconds: Long = minutes * 60L, stepId: String = "", stepRound: Int = -1): Boolean {
        rollDayIfNeeded()
        val dup = storyId.isNotEmpty() && ledgerIds().contains(storyId)
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        // 不足整分的按秒记（“附近走走”实际 45 秒，旧版记成 1m → 界面时长与倒计时都对不上）
        val dur = if (seconds > 0 && seconds % 60 != 0L) "${seconds}s" else "${minutes}m"
        var line = "$t|$tag|$dur|$storyId${if (dup) "|重复进账" else ""}"
        if (!dup) {
            if (stepId.isNotEmpty()) line += "|步=$stepId@${if (stepRound >= 0) stepRound else scheduleRound()}"
            drainRewardBuffer(storyId)?.takeIf { it.isNotEmpty() }?.let { line += "|赏=$it" }
        }
        val kept = (ledger() + line).takeLast(LEDGER_CAP)
        prefs.edit().putString(
            "ledger", kept.joinToString("\n"),
        ).putString("ledger_ids", (ledgerIds() + storyId).distinct().takeLast(LEDGER_CAP).joinToString(",")).apply()
        if (dup) return false        // 同一条任务不再次计入（之前就是这里双计把 4 次记成 8 次）
        syncUpNow()                 // 开工是不可丢的节点：立刻上交主存
        return true
    }

    /**
     * 出门记录对账补记（v2.1.1）：把不是本模块开的、今天已结束的任务写进**台账**（时间线与「今天」同源），带“补记”标记。
     * 已在台账里（同 storyId，或同种类开始时间相差 < 90 秒）就不补。@return 是否补进
     *
     * 2026-10-16 追加**跨种类时间重叠**去重：宠物同一时刻只能干一件事，一条候选记录的时间段
     * 若与台账里已有条目重叠，哪怕种类不同也必是同一趟出门（真机案例：详情串条把打工的
     * 07:44:59/45m 借给一条学习记录，旧去重只认同种类，于是“一学一工同时记上”）。
     */
    fun appendReconciled(storyId: String, tag: String, startMs: Long, seconds: Long): Boolean {
        rollDayIfNeeded()
        if (storyId.isEmpty() || startMs <= 0 || Day.of(startMs) != today()) return false
        if (ledgerIds().contains(storyId)) return false
        val day = java.time.LocalDate.ofEpochDay(today().toLong())
        val existing = ledger().mapNotNull { TodayTally.parse(it, day) }
        if (existing.any { it.storyId == storyId || (it.tag == tag && kotlin.math.abs(it.startMs - startMs) < TodayTally.SAME_START_MS) }) return false
        val endMs = startMs + seconds.coerceAtLeast(0) * 1000
        if (seconds > 0 && existing.any { startMs < it.endMs && it.startMs < endMs }) return false
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(startMs))
        val dur = if (seconds > 0 && seconds % 60 != 0L) "${seconds}s" else "${seconds.coerceAtLeast(0) / 60}m"
        val line = "$t|$tag|$dur|$storyId|补记|结"
        prefs.edit().putString("ledger", (ledger() + line).takeLast(LEDGER_CAP).joinToString("\n"))
            .putString("ledger_ids", (ledgerIds() + storyId).distinct().takeLast(LEDGER_CAP).joinToString(",")).apply()
        syncUpNow()
        return true
    }

    fun inLedger(storyId: String): Boolean = storyId.isNotEmpty() && ledgerIds().contains(storyId)

    /** 最近一件任务的结算时刻（任务间隔起算点）；按天清（rollDay 名单里）。 */
    /** 照顾 Ban 冷却的到期时刻（epoch ms，0=没在冷却）；键名由照顾的失败键派生（feed-fail → feed-fail-ban-until）。 */
    fun banUntilMs(failKey: String): Long = prefs.getLong("$failKey-ban-until", 0L)

    fun setBanUntilMs(failKey: String, ms: Long) {
        if (ms > 0) prefs.edit().putLong("$failKey-ban-until", ms).apply()
        else prefs.edit().remove("$failKey-ban-until").apply()
    }

    fun noteTaskEnd(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_task_end", atMs).apply()
    }

    fun lastTaskEnd(): Long = prefs.getLong("last_task_end", 0L)

    /**
     * 结算时补记一趟**从没进过账**的已完成任务（开工未确认被漏登记、或外部开的）：
     * 用它真实的开始时间与时长写台账（带「结」标，[stepId] 非空时一并带上排班归属——
     * 计数/疲劳/步进度都从台账现算，补上这一行几本账同时对齐，不再逐本补）。
     * 为什么需要：真机 2026-10-16 那趟 03:11 的学习没登记 → 完成时只进了 done 计数，
     * 疲劳少算 45 分钟、台账缺行，之后还逼得对账去“借”别条记录的时间补记。
     * 在结算当口用状态里的真实 startedAt 补上，三处一起堵死。已在台账里就跳过（防与开工进账重复）。
     */
    fun noteSettledUncounted(storyId: String, tag: String, startMs: Long, seconds: Long, stepId: String = "", stepRound: Int = -1): Boolean {
        if (storyId.isEmpty() || tag.isEmpty() || startMs <= 0 || seconds <= 0) return false
        rollDayIfNeeded()
        if (Day.of(startMs) != today() || ledgerIds().contains(storyId)) return false
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(startMs))
        val dur = if (seconds % 60 != 0L) "${seconds}s" else "${seconds / 60}m"
        var line = "$t|$tag|$dur|$storyId|结算补记|结"
        if (stepId.isNotEmpty()) line += "|步=$stepId@${if (stepRound >= 0) stepRound else scheduleRound()}"
        drainRewardBuffer(storyId)?.takeIf { it.isNotEmpty() }?.let { line += "|赏=$it" }
        prefs.edit().putString("ledger", (ledger() + line).takeLast(LEDGER_CAP).joinToString("\n"))
            .putString("ledger_ids", (ledgerIds() + storyId).distinct().takeLast(LEDGER_CAP).joinToString(",")).apply()
        syncUpNow()
        return true
    }

    private fun ledgerIds(): List<String> =
        (prefs.getString("ledger_ids", "") ?: "").split(',').filter { it.isNotEmpty() }

    // ---- 排班：轮次 / 游标 / 每步进度（跨天归零）----
    /**
     * 轮次：`wrap=loop` 回到第一步时递增。
     * 为什么需要轮次：“学习 6 次”若按天累计，一轮跑完后第一步永远处于已完成状态，“回到第一步”就名存实亡。
     * 所以进度键 = 轮次:步骤id，换轮等于天然清零。
     */
    fun scheduleRound(): Int = schState()[1].toIntOrNull() ?: 0

    fun scheduleCursor(): Int = schState()[2].toIntOrNull() ?: 0

    /** 推进到某一步（可选同时换轮，换轮时各步进度自然作废）。 */
    fun setSchedule(cursor: Int, round: Int = scheduleRound()) {
        rollDayIfNeeded()
        prefs.edit().putString("sch_state", "${today()};$round;$cursor").apply()
    }

    /**
     * 某步本轮已做的（次数, 分钟）——**从台账现算**（统一账本 2.1.13）：
     * 带「步=本步@本轮」归属、未被拒的行计次数；学习/打工分钟按行时长（开工即记，与旧口径一致），
     * 冒险分钟只认已确认完成（「结」）的行、按结算时修正过的实际时长（召回趟只跑几十秒，
     * 按整趟记会把排班分钟提前凑满，2026-10-29 群友实报）。升版当天的 legacy 覆盖层叠加在最下层。
     */
    fun stepProgress(stepId: String): Pair<Int, Int> {
        rollDayIfNeeded()
        val round = scheduleRound()
        var count = 0; var minutes = 0
        for (e in journalEntries()) {
            if (e.stepId != stepId || e.stepRound != round || e.rejected) continue
            count++
            minutes += when {
                e.tag == "adventure" -> if (e.finished) ((e.seconds + 59) / 60).toInt() else 0
                else -> ((e.seconds + 59) / 60).toInt()
            }
        }
        val legacy = legacyStepProgress(stepId, round)
        return (count + legacy.first) to (minutes + legacy.second)
    }

    // ---- 步进度倒退哨兵的高水位（供 AutoRunner 在决策时比对；记录格式 `轮次:步位:步id:次数:分钟`，分号分隔，按天清）----
    fun stepPeak(round: Int, pos: Int): Triple<String, Int, Int>? {
        val rec = (prefs.getString("sch_peak", "") ?: "").split(';')
            .firstOrNull { val f = it.split(':'); f.size >= 5 && f[0].toIntOrNull() == round && f[1].toIntOrNull() == pos }
            ?: return null
        val f = rec.split(':')
        return Triple(f[2], f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0)
    }

    fun noteStepPeak(round: Int, pos: Int, stepId: String, count: Int, minutes: Int) {
        val kept = (prefs.getString("sch_peak", "") ?: "").split(';')
            .filter { val f = it.split(':'); f.isNotEmpty() && !(f.size >= 5 && f[0].toIntOrNull() == round && f[1].toIntOrNull() == pos) }
        prefs.edit().putString("sch_peak", (kept + "$round:$pos:$stepId:$count:$minutes").joinToString(";")).apply()
    }

    /** 台账里现有的步归属一览（哨兵报警时附带，定位旧账挂在哪个 id 名下）：`步id=次数/分钟`，跨轮合并，计数口径与 [stepProgress] 一致。 */
    fun stepTagSummary(): String {
        val byId = LinkedHashMap<String, Pair<Int, Int>>()
        for (e in journalEntries()) {
            if (e.stepId.isEmpty() || e.rejected) continue
            val (c, m) = byId[e.stepId] ?: (0 to 0)
            val add = when {
                e.tag == "adventure" -> if (e.finished) ((e.seconds + 59) / 60).toInt() else 0
                else -> ((e.seconds + 59) / 60).toInt()
            }
            byId[e.stepId] = (c + 1) to (m + add)
        }
        if (byId.isEmpty()) return "（无）"
        return byId.entries.joinToString("，") { (id, cm) -> "$id=${cm.first}次/${cm.second}分钟" }
    }

    /**
     * 界面要读的每步进度（旧格式 `轮次:步id:次数:分钟;…`）——统一账本后没人再增量写这个键，
     * 排班页卡片却仍按它读，结果 2.1.13 起卡片上的进度永远是 0（2026-11-10 群友 Hey 与用户本人实测）。
     * 这里在每次上交主存前从台账现算回填：口径与 [stepProgress] 完全一致（同一个函数出数），
     * 界面读法不用改；跨天时台账清空，这个键随之清空（rollDay 也会删它）。
     */
    private fun refreshSchSteps() {
        val round = scheduleRound()
        val ids = LinkedHashSet<String>()
        for (e in journalEntries()) if (e.stepRound == round && e.stepId.isNotEmpty()) ids += e.stepId
        // 升版当天的覆盖层里可能有今天还没落台账行的步，一并带上，别让它的进度在界面上凭空消失
        (prefs.getString("sch_steps_legacy", "") ?: "").split(';').forEach {
            val f = it.split(':')
            if (f.size >= 4 && f[0].toIntOrNull() == round && f[1].isNotEmpty()) ids += f[1]
        }
        val text = ids.joinToString(";") { id ->
            val (c, m) = stepProgress(id)
            "$round:$id:$c:$m"
        }
        val ed = prefs.edit()
        if (text.isEmpty()) ed.remove("sch_steps") else ed.putString("sch_steps", text)
        ed.apply()
    }

    /** 升版当天的旧步进度覆盖层（sch_steps 原样快照）；没有覆盖层或轮次对不上时为 0。 */
    private fun legacyStepProgress(stepId: String, round: Int): Pair<Int, Int> {
        val rec = (prefs.getString("sch_steps_legacy", "") ?: "").split(';')
            .firstOrNull { it.startsWith("$round:$stepId:") } ?: return 0 to 0
        val f = rec.split(':')
        return (f.getOrNull(2)?.toIntOrNull() ?: 0) to (f.getOrNull(3)?.toIntOrNull() ?: 0)
    }

    /**
     * 换轮搬运：把某步在本轮的台账归属改写到新一轮（做完的步不搬、留在旧轮自然作废）。
     * 为什么需要：换轮的本意只是让做完的步重新可做；没做完的步进度清零的话，它前面做过的
     * 次数全不算数、整段排班反复重做（2026-10-29 群友实报：学习设 8 次实际学十几次）。
     * 统一账本后进度就是台账行的归属，搬运 = 改归属，不再另存一份进度。
     */
    fun retagStepRound(stepId: String, toRound: Int) {
        rollDayIfNeeded()
        val from = scheduleRound()
        if (stepId.isEmpty() || toRound == from) return
        val lines = ledger().map { line ->
            val f = line.split('|').toMutableList()
            val i = f.indexOfFirst { it.startsWith("步=") }
            if (i >= 0) {
                val v = f[i].removePrefix("步=")
                if (v.substringBeforeLast('@') == stepId && v.substringAfterLast('@', "").toIntOrNull() == from)
                    f[i] = "步=$stepId@$toRound"
            }
            f.joinToString("|")
        }
        var e = prefs.edit().putString("ledger", lines.joinToString("\n"))
        // 升版当天的覆盖层同理搬运（记录形如 round:stepId:count:minutes）
        val legacy = prefs.getString("sch_steps_legacy", "") ?: ""
        if (legacy.isNotEmpty()) {
            val moved = legacy.split(';').filter { it.isNotEmpty() }.map { rec ->
                val f = rec.split(':')
                if (f.size >= 4 && f[0].toIntOrNull() == from && f[1] == stepId) "$toRound:${f[1]}:${f[2]}:${f[3]}" else rec
            }
            e = e.putString("sch_steps_legacy", moved.joinToString(";"))
        }
        e.apply()
    }

    /** 这趟台账行上的排班归属（轮次对不上不认，免得把旧轮的趟认成这一轮的）。 */
    fun journalStepOf(storyId: String): String? {
        rollDayIfNeeded()
        val e = journalEntries().firstOrNull { it.storyId == storyId } ?: return null
        return e.stepId.takeIf { it.isNotEmpty() && e.stepRound == scheduleRound() }
    }

    /** 开工未确认时记下的意图：那一趟若后来在结算时被补记，对上号就把排班步的次数补回去。 */
    data class UnconfirmedIntent(val stepId: String, val tag: String, val minutes: Int, val sentAtMs: Long)

    fun noteUnconfirmedIntent(stepId: String, tag: String, minutes: Int, sentAtMs: Long) {
        if (stepId.isEmpty()) return
        prefs.edit().putString("unc_intent", "${today()}|$stepId|$tag|$minutes|$sentAtMs").apply()
    }

    fun unconfirmedIntent(): UnconfirmedIntent? {
        val f = (prefs.getString("unc_intent", "") ?: "").split('|')
        if (f.size < 5 || f[0].toIntOrNull() != today()) return null
        return UnconfirmedIntent(f[1], f[2], f[3].toIntOrNull() ?: 0, f[4].toLongOrNull() ?: 0L)
    }

    fun clearUnconfirmedIntent() {
        prefs.edit().remove("unc_intent").apply()
    }

    /** 排班状态一行（settings 与界面共用）。 */
    fun scheduleStatus(steps: List<Step>): String {
        rollDayIfNeeded()
        if (steps.isEmpty()) return "排班无步骤"
        val idx = scheduleCursor().coerceIn(0, steps.size - 1)
        val st = steps[idx]
        val (c, m) = stepProgress(st.id)
        val group = when (st.kind) {
            StepKind.WORK -> st.groupName.ifBlank { if (st.career > 0) "type=${st.career}" else "任意职业" }
            StepKind.STUDY -> st.attr.ifBlank { "任意属性" }
            StepKind.ADVENTURE -> "冒险"
        }
        val what = st.itemName.ifBlank { if (st.sub > 0) "#${st.sub}" else "自动" }
        return "排班 第${idx + 1}/${steps.size}步 ${st.kind.cn}「$group」· $what 已 ${st.progressText(c, m)}（第 ${scheduleRound() + 1} 轮）"
    }

    private fun schState(): List<String> {
        rollDayIfNeeded()
        return (prefs.getString("sch_state", "") ?: "").split(';') + List(3) { "" }
    }

    /** 任务台账：每条任务一行（时间|种类|时长|storyId，重复进账会被标出来）。 */
    fun ledger(): List<String> =
        (prefs.getString("ledger", "") ?: "").split('\n').filter { it.isNotBlank() }

    /** 台账里按前缀分类的条数（事后对账用，不用现场蹲日志）。 */
    fun ledgerSummary(): String {
        val g = ledger().map { it.split('|').getOrNull(1) ?: "?" }.groupingBy { it }.eachCount()
        return g.entries.joinToString(" ") { "${it.key}=${it.value}" } +
            "，重复进账 ${ledger().count { it.contains("重复进账") }} 次"
    }

    /**
     * 事件日志：【按天一个文件】，追加写；跨天开新文件并删掉昨天的（“一天清一次”）。
     *
     * 为什么不塞在 prefs 的一个字符串里：以前那是 150 条环形截断，整天跨度根盖不住；
     * 而“整天都记”要是放进一个 prefs 字符串，就等于每写一行都要重写整个文件（一天几千行、几百 KB）。
     * 文件只追尾部，才是这个量级的正确容器。logcat 一夜被冲掉的问题（三星缓冲上限 5MiB）也顺带解决。
     */
    @Synchronized
    fun pushEvent(msg: String, maxLen: Int = 400) {
        val line = LogLine.format(msg, maxLen = maxLen)
        runCatching {
            val f = dayLogFile()
            synchronized(LogLine.LOCK) {
                if (f.length() > LOG_MAX_BYTES) {
                    // 体积失控的兼底：只留后半（一天真记满 8MB 的概率极低，但不能因此把模块卡住）
                    val keep = runCatching { f.readLines() }.getOrDefault(emptyList())
                    f.writeText(keep.drop(keep.size / 2).joinToString("\n") + "\n")
                }
                LogLine.append(f, line)
            }
        }
    }

    /** 今天的日志文件（文件名带日期，跨天自然开新页）。 */
    private fun dayLogFile(): java.io.File {
        val dir = java.io.File(appCtx.filesDir, "autoqqpet-logs").apply { if (!exists()) mkdirs() }
        return java.io.File(dir, "${Day.todayDate()}.log")
    }

    /**
     * 删掉超过保留期的日志文件（2026-10-16 起保留 `log_keep_days` 天，默认 3，含今天）。
     * 以前跨天就删昨天的 → 早上排查前一晚的停摆时日志已经没了。
     */
    private fun dropOlderLogs() {
        runCatching {
            val keep = logKeepDays()
            val today = Day.todayDate()
            java.io.File(appCtx.filesDir, "autoqqpet-logs").listFiles()?.forEach {
                if (LogRetention.shouldDelete(it.name, today, keep)) it.delete()
            }
        }
    }

    private fun logKeepDays(): Int = runCatching {
        val sp = appCtx.getSharedPreferences("autoqqpet_settings", android.content.Context.MODE_PRIVATE)
        io.github.juwanhwang.autoqqpet.store.SettingKeys.int(sp, io.github.juwanhwang.autoqqpet.store.SettingKeys.LOG_KEEP_DAYS)
    }.getOrDefault(3).coerceIn(1, 14)

    /** 某天的日志（诊断用；不存在为空）。 */
    fun eventsOf(date: java.time.LocalDate): List<String> = runCatching {
        java.io.File(java.io.File(appCtx.filesDir, "autoqqpet-logs"), "$date.log").readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** 当日全部日志（方法名保持原样，调用方不用改）。 */
    fun events(): List<String> =
        runCatching { dayLogFile().readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** 最近 n 行现场。 */
    fun recent(n: Int = 200): List<String> = events().takeLast(n.coerceIn(1, LOG_TAIL_MAX))

    /** 诊断包用：当日日志尾部（按字符数截，保证广播不致于撑爆 binder）。 */
    fun logTailChars(maxChars: Int = 120_000): String {
        val out = StringBuilder()
        var used = 0
        for (line in events().asReversed()) {
            if (used + line.length + 1 > maxChars) break
            out.insert(0, "$line\n")
            used += line.length + 1
        }
        return out.toString()
    }

    /** 今天记了多少行（设置页与诊断包头部用它报体量）。 */
    fun logLines(): Int = events().size

    /**
     * 当日“为什么这么决”的计数（键 = 动作/原因）。
     *
     * 日志文件已经是一整天了，但要回答“一整天到底为什么都在冒险”还得靠计数：
     * 翻两千行不如一句“ADVENTURE/rate 最高 ×137、STUDY ×3”。计数一天就几百字节，跟着其他当日数据一起清。
     */
    fun noteDecision(kind: String, cause: String) {
        if (cause.isBlank()) return
        rollDayIfNeeded()
        val key = "$kind/${cause.take(14)}"
        val m = decisionStats().toMutableMap()
        m[key] = (m[key] ?: 0) + 1
        prefs.edit().putString("dec_stats", m.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
    }

    /** 当日决策计数（已按次数降序）；诊断包与 `settings` 广播都打这个。 */
    fun decisionStats(): Map<String, Int> =
        (prefs.getString("dec_stats", "") ?: "")
            .split(';').mapNotNull { e ->
                val i = e.lastIndexOf(':')
                if (i <= 0) return@mapNotNull null
                e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: 0)
            }.toMap()

    fun decisionStatsText(): String =
        decisionStats().entries.sortedByDescending { it.value }
            .joinToString(" ⋅ ") { "${it.key}×${it.value}" }
            .ifBlank { "尚无决策记录" }

    // ---- 被雇佣“该召回了”提醒的去重（存进磁盘，runner 重建/重启也不会重复提醒）----
    /** 已就哪条雇佣任务提醒过（空=没有）。 */
    fun recallNotifiedFor(): String = prefs.getString("recall_notified", "") ?: ""

    fun markRecallNotified(storyId: String) {
        prefs.edit().putString("recall_notified", storyId).apply()
    }

    // ---- 雇佣通知与任务的绑定（按天持久化，重启不复活；口径见 HireDetect）----
    /**
     * 已绑定的雇佣通知：通知身份（[HireDetect.noticeKey]）→ 它认过的 storyId。
     * 一条“雇佣了”通知一生只认一份工：确认时绑上，那份工结束/被换掉时没被认领的也绑到它身上作废，
     * 免得上一份工的通知落进下一份工的时间窗、把用户自己开的工误判成被雇佣（2026-10-23 真机）。
     */
    fun hireNoticeBinds(): Map<String, String> {
        rollDayIfNeeded()
        val raw = prefs.getString("hire_notice_binds", "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        return raw.split(';').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else unescBind(p.substring(0, i)) to p.substring(i + 1)
        }.toMap()
    }

    /** 把一条雇佣通知绑到一份工上（已绑给同一份则不变；不会改绑给别人）。 */
    fun bindHireNotice(key: String, storyId: String) {
        if (key.isEmpty() || storyId.isEmpty()) return
        val cur = hireNoticeBinds()
        if (cur.containsKey(key)) return
        val next = cur + (key to storyId)
        prefs.edit().putString("hire_notice_binds",
            next.entries.joinToString(";") { "${escBind(it.key)}=${it.value}" }).apply()
    }

    /** 通知身份里带雇主昵称，可能含分隔符，落盘前转义。 */
    private fun escBind(s: String): String = s.replace("%", "%25").replace(";", "%3B").replace("=", "%3D")
    private fun unescBind(s: String): String = s.replace("%3D", "=").replace("%3B", ";").replace("%25", "%")

    // ---- “这条任务是不是本模块开的” ----
    /**
     * 记下我们亲自开起来的 storyId。
     * 为什么要记：真机 2026-10-11 发现被雇佣与自己开工在 STORY_STATUS 里长得一模一样
     * （都是 `6400_` 前缀 + `state=51`），唯一可靠的区分就是“这条是不是我们开的”，
     * 才能判断“当前在跑的任务是别人安排的”并提醒用户去召回。
     * 故意不按天清零（跨天的长任务仍算我们开的），只保留最近 60 条。
     */
    fun noteStarted(storyId: String) {
        if (storyId.isEmpty()) return
        val cur = prefs.getString("started_ids", "") ?: ""
        if (cur.split(',').contains(storyId)) return
        val kept = (cur.split(',').filter { it.isNotEmpty() } + storyId).takeLast(60)
        prefs.edit().putString("started_ids", kept.joinToString(",")).apply()
    }

    /** 这条 storyId 是否由本模块亲自开启（没记过则 false，包括 QQ 界面手动开的与被好友雇佣的）。 */
    fun isMine(storyId: String): Boolean =
        storyId.isNotEmpty() && (prefs.getString("started_ids", "") ?: "").split(',').contains(storyId)

    // ---- 今日收益：起始金币、金币曲线、实收奖励、雇佣支出（跨天随报告归档后清零）----

    /**
     * 每轮读到金币就记一笔：当天第一次记为起点；曲线 ≥10 分钟一个点（或变动 ≥50），最多 [CURVE_CAP] 个。
     * 同时校正今日基线：余额比上次观测（`gold.last`）低时，先扣掉模块自家支出
     * （`gold.self`，由 [noteModuleGoldSpend] 累计），还解释不了的差额视为用户在官方
     * 界面手动花的，把基线等额下调（口径见 [GoldBaseline]）。观测后自家支出累计清零。
     * @return 本次判定的非模块支出额（基线下调额）；0 = 本轮没有下调。
     */
    fun noteGold(gold: Int, now: Long = System.currentTimeMillis()): Int {
        rollDayIfNeeded()
        val ed = prefs.edit()
        var unexplained = 0
        if (!prefs.contains("gold.start")) {
            ed.putInt("gold.start", gold).putLong("gold.start.at", now)
            ed.putInt("gold.last", gold).putInt("gold.self", 0)
        } else if (!prefs.contains("gold.last")) {
            // 旧版存量只有基线、没有上次观测：从这轮起开始跟踪，本轮不判（没有可比的上一笔）
            ed.putInt("gold.last", gold).putInt("gold.self", 0)
        } else {
            val start = prefs.getInt("gold.start", gold)
            val r = GoldBaseline.adjusted(
                start = start,
                last = prefs.getInt("gold.last", gold),
                selfSpend = prefs.getInt("gold.self", 0),
                observed = gold,
            )
            unexplained = r.unexplained
            if (r.newStart != start) ed.putInt("gold.start", r.newStart)
            ed.putInt("gold.last", gold).putInt("gold.self", 0)
        }
        val curve = goldCurve()
        val last = curve.lastOrNull()
        if (last == null || now / 1000 - last.first >= 600 || kotlin.math.abs(gold - last.second) >= 50) {
            val kept = (curve + ((now / 1000) to gold)).takeLast(CURVE_CAP)
            ed.putString("gold_curve", kept.joinToString(";") { "${it.first}:${it.second}" })
        }
        ed.apply()
        return unexplained
    }

    /**
     * 记一笔模块自己的金币支出（买补给、学费等，动作已发出并被受理后调）：
     * 下次 [noteGold] 先拿它解释余额下跌，不把模块自家花销误判成用户手动消费。
     */
    fun noteModuleGoldSpend(amount: Int) {
        if (amount <= 0) return
        rollDayIfNeeded()
        prefs.edit().putInt("gold.self", prefs.getInt("gold.self", 0) + amount).apply()
    }

    /** 今日起始金币；-1=今天还没读到过。 */
    fun goldStart(): Int {
        rollDayIfNeeded()
        return prefs.getInt("gold.start", -1)
    }

    /** 金币曲线：(epoch 秒, 金币)。 */
    fun goldCurve(): List<Pair<Long, Int>> =
        (prefs.getString("gold_curve", "") ?: "").split(';').mapNotNull {
            val kv = it.split(':')
            val t = kv.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val g = kv.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            t to g
        }

    /**
     * 场外实收累加进今日（名称 → 合计）：只给**没有台账行**的来源用（照顾好友的实收等）。
     * 任务结算的实收走 [attachRewards] 记到这趟自己的台账行上，不再进这本存量——
     * 否则同一笔会在存量与台账两处各记一遍（统一账本 2.1.13）。杂键在此再兜一层（见 Parsers.rewardNameOk）。
     */
    fun addRewards(rewards: List<Reward>) {
        val clean = rewards.filter { io.github.juwanhwang.autoqqpet.pet.parse.Parsers.rewardNameOk(it.name) }
        if (clean.isEmpty()) return
        rollDayIfNeeded()
        val m = parsePairs("rewards")
        clean.forEach { r -> m[r.name.take(10)] = (m[r.name.take(10)] ?: 0) + r.amount.toInt() }
        prefs.edit().putString("rewards", writePairs(m)).apply()
    }

    /** 今日实收 = 场外存量（照顾等）+ 台账各行「赏=」现算。两边来源不重叠，永远不会双计。 */
    fun rewards(): Map<String, Int> {
        rollDayIfNeeded()
        healRewardsFromLog()
        val m = parsePairs("rewards")
        for ((name, amt) in journalRewards()) m[name] = (m[name] ?: 0) + amt
        return m
    }

    /**
     * 向前兼容自愈（2026-10-19）：旧包的 [addRewards] 用 rewardNameOk 把脏名结算整条丢过——
     * 故事结算给的「工分 +」过不了校验，今天 3 份工、162 工分没进 rewards 记录；
     * 但当天日志的「🧾 结算记录」行里每笔的完整 rewards 都还在。按天只做一次对账合并
     * （见 [mergeLedgerRewards]）；写回后结果稳定、重复调用幂等，当天没有结算记录行时行为与原来一致。
     */
    private fun healRewardsFromLog() {
        if (prefs.getInt("rewards_healed_day", -1) == today()) return
        prefs.edit().putInt("rewards_healed_day", today()).apply()
        mergeLedgerRewards(events())
    }

    /**
     * 把一段日志里的结算记录 rewards 并进已存 rewards（自愈的实际动作，不查日界、可重复调用）：
     * ① 先把存量键整体过一遍归一折回本体、同名相加——旧包能存下「基础魅力」这类变体键
     * （它过得了旧校验），不折的话新口径汇总时会把它与台账补回值算成两遍；
     * 洗不过的键保留原样，宁可留着也不丢数据。
     * ② 再与日志汇总逐名取 max：已存值只会因整条丢弃而偏低、不会偏高；
     * PK、福袋等不走这条日志的来源只会让已存值更高，不会被日志汇总盖小。
     */
    private fun mergeLedgerRewards(lines: List<String>) {
        val stored = parsePairs("rewards")
        val folded = LinkedHashMap<String, Int>()
        for ((k, v) in stored) {
            val key = io.github.juwanhwang.autoqqpet.pet.parse.Parsers.cleanRewardName(k) ?: k
            folded[key] = (folded[key] ?: 0) + v
        }
        // 统一账本后：台账行已带实收（rewards() 另行现算合并），日志汇总再取大会把同一笔算两遍——
        // 今天已有台账实收时只做存量归一折回，不再并日志。
        if (journalRewards().isEmpty()) {
            val sums = ledgerRewardSums(lines)
            // 存量里没有的名字直接采用汇总（汇总可以是负数，如体力值净消耗，别拿 0 当缺省比掉）；已有的才取大
            for ((name, sum) in sums) {
                val cur = folded[name]
                if (cur == null || sum > cur) folded[name] = sum
            }
        }
        if (folded != stored) {
            prefs.edit().putString("rewards", writePairs(folded)).apply()
            PLog.i("DailyStore", "🧾 实收已按归一口径对账：存量折回本体并与结算记录取大，$folded")
        }
    }

    /** 记一次新雇佣（只记 `hire` 次数）：雇人打工不收费，没有支出金额可记（旧 `hire_spent` 从此恒为 0）。 */
    fun noteHire() {
        increment("hire")
    }

    /**
     * 一次性迁移（2.1.5）：w2 账（满档还原口径）刚启用时是空的，所有岗位在金币优先下按 0 估、
     * 池子刷干也只会闲着。把旧 w 账里已有 ≥2 个样本的岗位均值折成 1 个样本种进 w2 当底价——
     * 旧均值被疲劳折过、偏低，但不为零、岗位之间的高低顺序大体还在；之后真实结算按新口径
     * 入账，几趟就把种子冲淡。已有 w2 记录的岗位不覆盖；雇佣账（h 键）旧账里没有，不种。
     */
    private fun seedJobLearnFromLegacyIfNeeded() {
        if (prefs.getBoolean("w2_seeded", false)) return
        val m = learnedMap()
        var changed = false
        for ((k, v) in m.entries.toList()) {
            if (!k.startsWith("w") || k.startsWith("w2") || v[0] < 2) continue
            val nk = "w2" + k.substring(1)
            if (m.containsKey(nk)) continue
            m[nk] = longArrayOf(1, v[1] / v[0], v[2] / v[0])
            changed = true
        }
        val e = prefs.edit().putBoolean("w2_seeded", true)
        if (changed) e.putString("reward_avg", learnedText(m))
        e.apply()
    }

    /**
     * 同一目录项的历史实收（最多 64 项）：次数、金币合计、工分合计。
     * `w<sub>` **跨天保留**——岗位定价以目录本金为准，这里是目录没给价时的兜底与对账来源；
     * `a<名字>` **按天重开**（见 [rollDayIfNeeded] 的结算），冒险收益分活池/到顶两段，跨天混平均只会得到假期望。
     */
    fun learnReward(key: String, gold: Long, points: Long) {
        seedJobLearnFromLegacyIfNeeded()
        val m = learnedMap()
        val cur = m[key] ?: longArrayOf(0, 0, 0)
        m[key] = longArrayOf(cur[0] + 1, cur[1] + gold, cur[2] + points)
        prefs.edit().putString("reward_avg", learnedText(m)).apply()
    }

    private fun learnedText(m: Map<String, LongArray>): String =
        m.entries.toList().takeLast(64).joinToString(";") { "${it.key}:${it.value[0]}:${it.value[1]}:${it.value[2]}" }

    /**
     * 冒险金币判到顶时，把触发判定的连段 0 金币样本从该冒险的学习均值里回滚掉。
     * 那批样本是在「已到顶但还没判出来」的窗口里喂进去的，留着会把每趟期望往下拉；
     * 它们金币都是 0，只需把样本数减掉 [zeros]，合计不动。
     */
    fun rollbackAdventureZeros(durMin: Int, zeros: Int) {
        if (zeros <= 0) return
        val m = learnedMap()
        val key = "a$durMin"
        val cur = m[key] ?: return
        val n = cur[0] - zeros
        when {
            n > 0 -> m[key] = longArrayOf(n, cur[1], cur[2])
            cur[1] == 0L && cur[2] == 0L -> m.remove(key)
            else -> m[key] = longArrayOf(1, cur[1], cur[2])  // 只剩出过金的样本：计数至少留 1
        }
        prefs.edit().putString("reward_avg", learnedText(m)).apply()
    }

    /** 最近一个完整日里「池子还活着时」的冒险每趟实收金币（跨天时由 [rollDayIfNeeded] 结算存下）；没有为 null。 */
    fun advLiveEv(): Double? = prefs.getString("adv_ev_live", null)?.toDoubleOrNull()?.takeIf { it > 0 }

    /**
     * 冒险在求解器里的定价期望（按时长定位，冒险名可能随版本/等级变化、时长是固定结构）：
     * 今日同档时长的学习均值为正才用它；均值 ≤ 0 按「还没判」处理，回退昨天活池实收种子。
     * 均值恰好为 0 多半是几趟坏天气的 0 金币趟拉出来的——
     * 「没油水了」只认连续 30 趟无金币的到顶判定（见 AutoRunner.noteAdventureGold），
     * 不许学习均值提前宣布，更不许据此把冒险踢出计划。
     */
    fun adventureEvGold(durMin: Int): Double? =
        learnedGold("a$durMin")?.takeIf { it > 0 } ?: advLiveEv()

    /**
     * 冒险真实耗时记一笔（派发→结案的墙钟）：[completed] = 跑完并结算；
     * 被天色不对召回、结算被拒收尾的趟只记耗时不记完成（口径见 [AdvPace]）。
     * 按天分桶存 `adv_pace`、跨天保留最近几天——与 `a<名字>` 实收按天重开不同，
     * 耗时要的是滚动多日的真实节奏，不能只看当天。
     */
    fun noteAdventureTrip(name: String, elapsedMs: Long, completed: Boolean) {
        if (name.isBlank() || elapsedMs <= 0) return
        val updated = AdvPace.recorded(prefs.getString("adv_pace", null), name, today(), elapsedMs, completed)
        if (updated.isNotEmpty()) prefs.edit().putString("adv_pace", updated).apply()
    }

    /** 某冒险「每完成一趟的真实耗时（分钟）」，最近几天实测；样本不足为 null（求解器回退名义时长）。 */
    fun adventureTripMinutes(name: String): Double? =
        AdvPace.minutesPerCompletion(prefs.getString("adv_pace", null), name, today())

    /** 全部冒险的实测耗时表（求解器构造时一次取全表）。 */
    fun adventureTripMinutesByName(): Map<String, Double> =
        AdvPace.minutesByName(prefs.getString("adv_pace", null), today())

    /** 平均实收金币；样本 < [minSamples] 时返回 null（不拿一次的偶然值当规律）。 */
    fun learnedGold(key: String, minSamples: Int = 2): Double? {
        seedJobLearnFromLegacyIfNeeded()
        val v = learnedMap()[key] ?: return null
        return if (v[0] >= minSamples) v[1].toDouble() / v[0] else null
    }

    /** 平均实收工分；样本 < [minSamples] 时返回 null（口径与 [learnedGold] 一致）。 */
    fun learnedPoints(key: String, minSamples: Int = 2): Double? {
        seedJobLearnFromLegacyIfNeeded()
        val v = learnedMap()[key] ?: return null
        return if (v[0] >= minSamples) v[2].toDouble() / v[0] else null
    }

    /**
     * 记下今日所选好友在某职业各时长上的雇佣加成上限（挑人时从那位好友的目录现读）。
     * 求解器给「带雇佣的班次」定价 = 目录本金 + 这里的上限——两个数都是服务器标的，不估系数。
     * 按天存（rollDayIfNeeded 清），后记的覆盖先记的（当天换人就按新人的表算）。
     */
    fun noteBonusCaps(careerType: Long, capsByDuration: Map<Int, Int>) {
        if (careerType <= 0 || capsByDuration.isEmpty()) return
        rollDayIfNeeded()
        val m = bonusCapMap()
        capsByDuration.forEach { (dur, cap) -> if (cap >= 0) m["c${careerType}t$dur"] = cap }
        prefs.edit().putString("bonus_caps", m.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
    }

    /** 今日所选好友在该岗位上的雇佣加成上限；没记过为 null。 */
    fun bonusCap(careerType: Long, durationMin: Int): Int? = bonusCapMap()["c${careerType}t$durationMin"]

    private fun bonusCapMap(): LinkedHashMap<String, Int> {
        val m = LinkedHashMap<String, Int>()
        (prefs.getString("bonus_caps", "") ?: "").split(';').forEach {
            val f = it.split(':')
            if (f.size == 2) f[1].toIntOrNull()?.let { v -> m[f[0]] = v }
        }
        return m
    }

    fun learnedSummary(): String = learnedMap().entries.joinToString(" ") { (k, v) ->
        "$k×${v[0]}=金${if (v[0] > 0) v[1] / v[0] else 0}/分${if (v[0] > 0) v[2] / v[0] else 0}"
    }.ifBlank { "暂无" }

    private fun learnedMap(): LinkedHashMap<String, LongArray> {
        val m = LinkedHashMap<String, LongArray>()
        (prefs.getString("reward_avg", "") ?: "").split(';').forEach {
            val f = it.split(':')
            if (f.size == 4) m[f[0]] = longArrayOf(f[1].toLongOrNull() ?: 0, f[2].toLongOrNull() ?: 0, f[3].toLongOrNull() ?: 0)
        }
        return m
    }

    /** 当日计数整表（快照/报告用）。 */
    /** 记一次**完成**（结算/召回/提前结束），按 storyId 去重；见 [DoneLedger]。 */
    /**
     * 记一趟完成（统一账本 2.1.13）：不再单独维护 done 计数——完成数由 [counts] 从台账现算，
     * 这里只把完成事实落到这趟自己的台账行上（实际时长 + 「结」标，见 [attachOutcome]）。
     * 同一 storyId 重复调用幂等（行已是实际时长、已有「结」标）。种类认不出的不记（与旧口径一致）。
     */
    fun noteDone(storyId: String, tag: String, seconds: Long): Boolean {
        if (tag != "study" && tag != "work" && tag != "adventure") return false
        return attachOutcome(storyId, seconds)
    }

    /** 「今天」次数与时长的唯一口径：台账去重、只算已完成（见 [TodayTally]）。 */
    fun doneTally(now: Long = System.currentTimeMillis()): TodayTally.Tally {
        rollDayIfNeeded()
        return TodayTally.tally(ledger(), now, java.time.LocalDate.ofEpochDay(today().toLong()))
    }

    fun counts(): Map<String, Int> {
        rollDayIfNeeded()
        val m = parseCounts()
        // 统一账本（2.1.13）：会话类计数全部从台账现算，存量同名键不再单独维护——
        // study/work/adventure = 今日已开工（被拒不算）；done_* = 今日已完成（与「今天」页同一口径）。
        m["study"] = journalStarted("study")
        m["work"] = journalStarted("work")
        m["adventure"] = journalStarted("adventure")
        val t = doneTally()
        m[DoneLedger.K_STUDY] = t.study
        m[DoneLedger.K_WORK] = t.work
        m[DoneLedger.K_ADV] = t.adventure
        m[DoneLedger.K_SW_SEC] = t.studyWorkSec
        m[DoneLedger.K_ADV_SEC] = t.adventureSec
        return m
    }

    /** 今日报告（未归档的实时版本），JSON 串。 */
    fun todayReport(currentGold: Int = -1): String = doneTally().let { t -> DailyReport.build(
        day = today(), counts = t.into(counts()), studyWorkMin = t.studyWorkMin, adventureMin = t.adventureMin,
        goldStart = prefs.getInt("gold.start", -1), goldEnd = if (currentGold >= 0) currentGold else goldCurve().lastOrNull()?.second ?: -1,
        rewards = rewards(), decisions = decisionStats(),
    ) }

    /** 最近一次归档的“昨日报告”（JSON 串，空=还没有）。 */
    fun lastReport(): String = prefs.getString("report_last", "") ?: ""

    private fun archiveReport(day: Int) {
        // 归档前先按被归档那天的日志做一次实收对账（与 rewards() 同一套自愈）：
        // 免得白天没读过快照、丢掉的脏名工分就这么进了昨日报告。这里不走 rewards()——它会回调 rollDayIfNeeded 造成递归。
        mergeLedgerRewards(eventsOf(java.time.LocalDate.ofEpochDay(day.toLong())))
        // 昨日报告与「今天」同一口径：台账去重、只算已完成（归档时一天已过完，now 取无穷大 = 全部已结束）
        val t = TodayTally.tally(ledger(), Long.MAX_VALUE, java.time.LocalDate.ofEpochDay(day.toLong()))
        // done_* 自统一账本起不再单独存（counts() 是现算覆盖，但它会回调 rollDay、归档中不能用）——在这里就地覆盖
        val yCounts = parseCounts()
        yCounts[DoneLedger.K_STUDY] = t.study; yCounts[DoneLedger.K_WORK] = t.work; yCounts[DoneLedger.K_ADV] = t.adventure
        yCounts[DoneLedger.K_SW_SEC] = t.studyWorkSec; yCounts[DoneLedger.K_ADV_SEC] = t.adventureSec
        // 实收 = 场外存量 + 昨日台账各行实收（与 rewards() 同一口径；此刻 ledger 还是昨天的行）
        val yRewards = parsePairs("rewards")
        for ((name, amt) in journalRewards()) yRewards[name] = (yRewards[name] ?: 0) + amt
        val json = DailyReport.build(
            day = day, counts = t.into(yCounts), studyWorkMin = t.studyWorkMin, adventureMin = t.adventureMin,
            goldStart = prefs.getInt("gold.start", -1), goldEnd = goldCurve().lastOrNull()?.second ?: -1,
            rewards = yRewards, decisions = decisionStats(),
        )
        prefs.edit().putString("report_last", json).apply()
        PLog.i("DailyStore", "📊 昨日报告已归档：${DailyReport.oneLine(json)}")
    }

    // ---- 待验证任务（启动后记录，下次 tick 校验是否真的在跑/到期）----
    /**
     * 记录刚启动的主任务。[sub]/[expected]/[name] 是开工时目录给的“预期”（打工=工分、学习=属性），
     * 到期后与 0x9874 结算明细的实收比对判疲劳（见 PayMonitor.settlementDrop）。
     */
    fun setPending(storyId: String, kind: String, expectedEndMillis: Long, durationMin: Int, sub: Long = 0, expected: Int = 0, name: String = "", durationSec: Long = 0, encourageTarget: Int = 0, dispatchedAtMs: Long = 0, career: Long = 0) {
        prefs.edit().putLong("pend.sec", durationSec).putLong("pend.at", dispatchedAtMs)
            .putString("pend.id", storyId).putString("pend.kind", kind)
            .putLong("pend.end", expectedEndMillis).putInt("pend.dur", durationMin)
            .putLong("pend.sub", sub).putInt("pend.exp", expected).putString("pend.name", name)
            .putLong("pend.career", career)
            .putInt("pend.retry", 0)
            // 自动鼓励记账（v2.1.2）：目标次数只由开工时写入（>0 才补发），已发次数/上次时刻随补发更新
            .putInt("pend.enc_target", encourageTarget).putInt("pend.enc_done", 0).putLong("pend.enc_at", 0L)
            .putString("pend.weather", "")   // 新登记作废上一趟的天色检查记号（记号只由派出方在登记后按需挂）
            .remove("pend.weather.tries").remove("pend.weather.rtries").remove("pend.weather.next").remove("pend.weather.done")
            .apply()
    }

    // ---- 开工认领：发开工指令之前先记下（种类/目录名/发出时刻），与 pend.* 同处管理 ----
    /**
     * 记一条开工认领（覆盖上一条）。认领只有一条、一次性的：登记成功或服务器明确拒绝就清，
     * 只有“开工未确认”会把它留下，等状态读到任务时按 [io.github.juwanhwang.autoqqpet.optimizer.StartClaim] 的窗口领回。
     * 不按天清（与 pend.* 同口径）；过期由读取方懒清，窗口只有 10 分钟，残留无害。
     */
    fun noteStartClaim(kind: String, name: String, atMs: Long) {
        prefs.edit().putString("claim.kind", kind).putString("claim.name", name).putLong("claim.at", atMs).apply()
    }

    /** 当前认领：（种类 tag、目录名、发出时刻 ms）；没认领时时刻为 0。 */
    fun startClaim(): Triple<String, String, Long> = Triple(
        prefs.getString("claim.kind", "") ?: "",
        prefs.getString("claim.name", "") ?: "",
        prefs.getLong("claim.at", 0),
    )

    fun clearStartClaim() {
        if (!prefs.contains("claim.at")) return
        prefs.edit().remove("claim.kind").remove("claim.name").remove("claim.at").apply()
    }

    fun pendingId(): String = prefs.getString("pend.id", "") ?: ""
    fun pendingKind(): String = prefs.getString("pend.kind", "") ?: ""

    /** 本模块发出这趟任务的墙钟时刻（开工登记时由发出方带入；接手/外部任务为 0）。冒险耗时记账用。 */
    fun pendingDispatchedAt(): Long = prefs.getLong("pend.at", 0)
    fun pendingEnd(): Long = prefs.getLong("pend.end", 0)
    fun pendingDuration(): Int = prefs.getInt("pend.dur", 0)
    /** 待结算任务的总时长（秒）：优先用开工时记下的精确秒数，没有就按分钟换算。 */
    fun pendingDurationSec(): Long = prefs.getLong("pend.sec", 0L).takeIf { it > 0 } ?: pendingDuration() * 60L
    fun pendingSub(): Long = prefs.getLong("pend.sub", 0)
    /** 这趟打工所属职业（开工登记时带入；接手/非打工为 0）。定价按职业+时长记账用，编号会漂移、职业不会。 */
    fun pendingCareer(): Long = prefs.getLong("pend.career", 0)
    fun pendingExpected(): Int = prefs.getInt("pend.exp", 0)
    fun pendingName(): String = prefs.getString("pend.name", "") ?: ""

    // —— 自动鼓励记账（与 pending 同生命周期；target=0 表示这一趟不补发，如冒险/手动任务）——
    fun pendingEncourageTarget(): Int = prefs.getInt("pend.enc_target", 0)
    fun pendingEncourageDone(): Int = prefs.getInt("pend.enc_done", 0)
    fun pendingEncourageAt(): Long = prefs.getLong("pend.enc_at", 0L)
    fun setPendingEncourageDone(n: Int) { prefs.edit().putInt("pend.enc_done", n.coerceAtLeast(0)).apply() }
    fun setPendingEncourageAt(ms: Long) { prefs.edit().putLong("pend.enc_at", ms).apply() }

    // —— 天色不对检查记号（与 pending 同生命周期）：查到有结论为止，节奏见 WeatherCheckPlan ——
    /** 挂上检查记号：派出自动冒险并登记后调用，[nextAtMs] 是第一次检查时刻；计数清零。 */
    fun armWeatherCheck(storyId: String, nextAtMs: Long) {
        prefs.edit().putString("pend.weather", storyId)
            .putInt("pend.weather.tries", 0).putInt("pend.weather.rtries", 0)
            .putLong("pend.weather.next", nextAtMs).apply()
    }
    /** 待检查的 storyId（空 = 这一趟不查或已判完）。 */
    fun weatherCheckFor(): String = prefs.getString("pend.weather", "") ?: ""
    /** 已读进度几次（没拿到结论的也算）。 */
    fun weatherCheckTries(): Int = prefs.getInt("pend.weather.tries", 0)
    /** 已试召回几次。 */
    fun weatherCheckRecallTries(): Int = prefs.getInt("pend.weather.rtries", 0)
    /** 下一次检查时刻（ms）；0 = 没排（旧版记号），按出发时刻推算第一次。 */
    fun weatherCheckNextAt(): Long = prefs.getLong("pend.weather.next", 0L)
    /** 记一次读进度尝试，并把下一次检查排到 [nextAtMs]。 */
    fun noteWeatherReadTry(nextAtMs: Long) {
        prefs.edit().putInt("pend.weather.tries", weatherCheckTries() + 1)
            .putLong("pend.weather.next", nextAtMs).apply()
    }
    /** 记一次召回尝试，并把下一次重试排到 [nextAtMs]。 */
    fun noteWeatherRecallTry(nextAtMs: Long) {
        prefs.edit().putInt("pend.weather.rtries", weatherCheckRecallTries() + 1)
            .putLong("pend.weather.next", nextAtMs).apply()
    }
    /** 已判完的 storyId（有结论/试满放弃/召回成功都记）：判完的不再接手、不再查。 */
    fun weatherCheckDoneFor(): String = prefs.getString("pend.weather.done", "") ?: ""
    /** 结案：摘记号并记下已判完的是哪一趟（同一趟之后不再查）。 */
    fun finishWeatherCheck(storyId: String) {
        prefs.edit().remove("pend.weather").remove("pend.weather.tries")
            .remove("pend.weather.rtries").remove("pend.weather.next")
            .putString("pend.weather.done", storyId).apply()
    }
    // —— 均衡学习轮转指针（跨天保留，不随日账本清）：下一次轮到的属性序号，顺序见 StudyRotation ——
    /** 下一次轮到的属性序号（0 起）。 */
    fun studyRotationNext(): Int = prefs.getInt("study.rot", 0)
    /** 记下下一次轮到的属性序号。 */
    fun setStudyRotationNext(i: Int) { prefs.edit().putInt("study.rot", i).apply() }

    /** 摘掉记号（不留已判完记录）：开关关掉时用，别让旧记号在重开后诈尸。 */
    fun clearWeatherCheck() {
        if (prefs.contains("pend.weather")) prefs.edit().remove("pend.weather")
            .remove("pend.weather.tries").remove("pend.weather.rtries").remove("pend.weather.next").apply()
    }

    fun clearPending() {
        prefs.edit().remove("pend.id").remove("pend.kind").remove("pend.end").remove("pend.dur").remove("pend.retry").remove("pend.at")
            .remove("pend.sub").remove("pend.exp").remove("pend.name")
            .remove("pend.enc_target").remove("pend.enc_done").remove("pend.enc_at").remove("pend.weather")
            .remove("pend.weather.tries").remove("pend.weather.rtries").remove("pend.weather.next").remove("pend.weather.done").apply()
    }

    /** 结算实收低于预期的档位（当日有效）：与目录峰值比较一起构成 penalizedSubs。 */
    fun markSettleDrop(sub: Long) {
        if (sub <= 0) return
        rollDayIfNeeded()
        val cur = settleDrops() + sub
        prefs.edit().putString("settle_drop", cur.joinToString(",")).apply()
    }

    fun settleDrops(): Set<Long> {
        rollDayIfNeeded()
        return (prefs.getString("settle_drop", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet()
    }

    /** 启动失败重试计数（有界）。 */
    fun bumpPendingRetry(): Int {
        val n = (prefs.getInt("pend.retry", 0)) + 1
        prefs.edit().putInt("pend.retry", n).apply()
        return n
    }
}
