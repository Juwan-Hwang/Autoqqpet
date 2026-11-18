package io.github.juwanhwang.autoqqpet.pet

import android.content.Context
import io.github.juwanhwang.autoqqpet.log.PLog

/**
 * 好友互动名单策略（仿 Sesame-M AutoBlackListMap 思路）：
 *  - 名单统一成「一份名单 + 黑/白模式」：黑名单模式＝名单里的人排除，白名单模式＝只认名单里的人，
 *    名单为空时两种模式都不限。三处各一份：互动名单、雇佣名单（默认白名单模式）、火花名单（默认黑名单模式）；
 *  - 自动拉黑：连续失败达阈值自动拉黑 N 天，超期自动解禁重试（仅记模块自动拉黑的）；不占模式、恒排除；
 *  - 福袋只看互动名单的黑名单口径（白名单模式不限制福袋，沿用旧行为）；
 *  - 每日上限：好友互动是风控敏感项（参考 copilot #19 封号教训），对每类动作设每日硬上限。
 *
 * 持久化在 QQ 进程内 SharedPreferences("autoqqpet_friends")，后续设置页通过广播读写。
 */
class FriendPolicy(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("autoqqpet_friends", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "FriendPolicy"
        private const val AUTO_BLACK_DAYS = 3          // 自动拉黑持续天数
        private const val FAIL_THRESHOLD = 3           // 连续失败多少次触发自动拉黑
        const val DAY_CAP_PK = 10                       // 每日 PK 上限（保守）
        const val DAY_CAP_VISIT = 20                    // 每日访问上限
        const val DAY_CAP_POKE = 20                     // 每日踩踩上限
        /** 旧版“每天踩”白名单：2026-10-15 起废弃（默认踩所有好友），迁移时删除。 */
        const val KEY_SPARK_WL = "spark_wl"
        const val KEY_SPARK_SCHEMA = "spark_schema"
        const val SPARK_SCHEMA = 2
        /** 今天踩失败（被拒、非重复）而跳过的人：`日号;uin;uin…`。 */
        const val KEY_SPARK_SKIP = "spark_skip"
        /** 今天已摸过（0x96a6）的好友：同样按日号存，跨天自动失效。 */
        const val KEY_PAT_DONE = "pat_done"
        /** 上次踩过的日号：`uin:日号,…`（排序“上次互动”用，最多留 3000 人）。 */
        const val KEY_SPARK_LAST = "spark_last"
        /** 失败退避：`下次允许的毫秒;连续失败次数`。 */
        const val KEY_SPARK_BACKOFF = "spark_backoff"
        const val KEY_ROSTER_AT = "roster_at"
        const val KEY_SPARK_BL = "spark_bl"
        const val KEY_SPARK_POKED = "spark_poked"
        const val KEY_SPARKS = "sparks"
        const val KEY_SPARKS_AT = "sparks_at"
        const val KEY_HIRE_ACTIVE = "hire.active"
        /** 雇佣名单的 prefs 键（csv uin）。 */
        const val KEY_HIRE_LIST = "hire_list"
        /** 名单模式值：白名单（只认名单内）/ 黑名单（排除名单内）。 */
        const val MODE_WL = "wl"
        const val MODE_BL = "bl"
        const val KEY_FRIEND_LIST = "friend_list"
        const val KEY_FRIEND_LIST_MODE = "friend_list_mode"
        const val KEY_HIRE_LIST_MODE = "hire_list_mode"
        const val KEY_SPARK_LIST_MODE = "spark_list_mode"

        /** 名单放行（纯函数，可单测）：名单为空恒放行；白名单模式只放行名单内，黑名单模式只放行名单外。 */
        fun listAllows(mode: String, list: Set<String>, uin: String): Boolean =
            list.isEmpty() || if (mode == MODE_BL) uin !in list else uin in list

        /**
         * 旧互动双名单（whitelist/blacklist）→ 合并名单 + 模式（纯函数，可单测，幂等：已迁过返回空）。
         * 白名单非空的老口径是“只认白名单、黑名单再排除”→ 白名单模式、名单取差集；
         * 白名单为空 → 黑名单模式、名单取原黑名单。返回值里 null = 删除该键。
         */
        fun friendListMigration(all: Map<String, *>): Map<String, String?> {
            if (all.containsKey(KEY_FRIEND_LIST_MODE)) return emptyMap()
            fun old(key: String): Set<String> =
                (all[key] as? String)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            val wl = old("whitelist"); val bl = old("blacklist")
            return if (wl.isNotEmpty()) mapOf(
                KEY_FRIEND_LIST to (wl - bl).joinToString(","), KEY_FRIEND_LIST_MODE to MODE_WL,
                "whitelist" to null, "blacklist" to null,
            ) else mapOf(
                KEY_FRIEND_LIST to bl.joinToString(","), KEY_FRIEND_LIST_MODE to MODE_BL,
                "whitelist" to null, "blacklist" to null,
            )
        }

        /** 与 [rememberSparks] 同一编码；界面进程也用它解主存副本。 */
        fun decodeSparks(text: String): List<SparkEntry> = text.split("~").mapNotNull {
            val f = it.split("⋄")
            if (f.size < 6 || f[0].isEmpty()) null
            else SparkEntry(f[0], f[1], "", SparkStage.of(f[2].toLongOrNull() ?: 0), f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: -1, f[5] == "1")
        }
    }

    /** 与 DailyStore 同一口径的本地“今天”；旧数据存的是 UTC 日号，会自动被判为过期而归零。 */
    private fun today(): Int = Day.today()

    // ---- 名单读写（互动名单：合并名单 + 模式） ----
    fun friendList(): Set<String> = csv(prefs.getString(KEY_FRIEND_LIST, "") ?: "")
    fun friendListMode(): String =
        prefs.getString(KEY_FRIEND_LIST_MODE, "")?.takeIf { it == MODE_WL || it == MODE_BL } ?: MODE_BL
    fun setFriendList(uins: Collection<String>) { prefs.edit().putString(KEY_FRIEND_LIST, uins.joinToString(",")).apply(); syncUp() }
    fun setFriendListMode(mode: String) {
        if (mode != MODE_WL && mode != MODE_BL) return
        prefs.edit().putString(KEY_FRIEND_LIST_MODE, mode).apply(); syncUp()
    }

    /**
     * PK 输了把对方排除出互动：黑名单模式加进名单，白名单模式从名单移除。
     * @return "bl" 加进名单 / "wl" 移出名单 / "" 本来就不在互动范围内、没动。
     */
    fun excludeForPkLoss(uin: String): String {
        if (uin.isEmpty()) return ""
        return if (friendListMode() == MODE_BL) {
            if (uin in friendList()) "" else { setFriendList(friendList() + uin); "bl" }
        } else {
            if (uin in friendList()) { setFriendList(friendList() - uin); "wl" } else ""
        }
    }

    /** 福袋排除口径（沿用旧行为）：自动拉黑恒排除；互动名单只在黑名单模式下排除名单内，白名单模式不限制福袋。 */
    fun bagBlocked(uin: String): Boolean =
        isAutoBlacklisted(uin) || (friendListMode() == MODE_BL && uin in friendList())

    /** 互动名单迁移（幂等，见 [friendListMigration]）。@return 改动键数。 */
    fun migrateFriendLists(): Int {
        val changes = friendListMigration(prefs.all)
        if (changes.isEmpty()) return 0
        val ed = prefs.edit()
        changes.forEach { (k, v) -> if (v == null) ed.remove(k) else ed.putString(k, v) }
        ed.apply(); syncUp()
        return changes.size
    }

    /** 雇佣名单：白名单模式（默认）非空时只在名单内挑，黑名单模式排除名单内（名单内仍按现有口径排序）；为空 = 不限制。 */
    fun hireList(): Set<String> = csv(prefs.getString(KEY_HIRE_LIST, "") ?: "")
    fun hireListMode(): String =
        prefs.getString(KEY_HIRE_LIST_MODE, "")?.takeIf { it == MODE_WL || it == MODE_BL } ?: MODE_WL
    fun setHireList(uins: Collection<String>) { prefs.edit().putString(KEY_HIRE_LIST, uins.joinToString(",")).apply(); syncUp() }
    fun setHireListMode(mode: String) {
        if (mode != MODE_WL && mode != MODE_BL) return
        prefs.edit().putString(KEY_HIRE_LIST_MODE, mode).apply(); syncUp()
    }

    // ---- 与主存同步：名单存在 QQ 目录，界面直接读不到；上交后“拉黑刚才那个”才成为可能 ----

    /** 名单/最近对手变更后上交主存。 */
    fun syncUp() = runCatching { SideStore.backupFriends(prefs) }

    /** 启动时从主存拿回名单（QQ 被重装后不至于把用户的白/黑名单也丢干净）。 */
    fun adoptFromStore(): Int {
        val json = SideStore.takePendingFriends() ?: return 0
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return 0
        val ed = prefs.edit()
        var n = 0
        obj.keys().forEach { k ->
            when (val v = obj.get(k)) {
                is String -> ed.putString(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Boolean -> ed.putBoolean(k, v)
            }
            n++
        }
        // 名单（黑/白/火花黑名单）以备份整表为准、不做并集——并集会把用户刚移除的人又并回来（移除必须生效）。
        // 当日行为记录（已踩/已领袋/计数帽）才同日合并，名册与火花缓存按各自的时间戳取新。
        fun dayList(key: String, backup: String): String {
            val local = prefs.getString(key, "") ?: ""
            val lp = local.split(";"); val bp = backup.split(";")
            if (lp.firstOrNull() != bp.firstOrNull()) return backup   // 不同天：以备份为准（本地多半是昨天的残留）
            return (lp.drop(1) + bp.drop(1)).filter { it.isNotEmpty() }.distinct()
                .joinToString(";", prefix = (lp.firstOrNull() ?: "") + ";")
        }
        for (k in listOf(KEY_SPARK_POKED, "bags")) {
            if (obj.has(k)) ed.putString(k, dayList(k, obj.optString(k, "")))
        }
        obj.keys().forEach { k ->
            if (k.startsWith("cap:") && obj.has(k)) {
                val lp = (prefs.getString(k, "") ?: "").split(";"); val bp = obj.optString(k, "").split(";")
                if (lp.size == 2 && bp.size == 2 && lp[0] == bp[0]) {
                    ed.putString(k, "${lp[0]};${maxOf(lp[1].toIntOrNull() ?: 0, bp[1].toIntOrNull() ?: 0)}")
                }
            }
        }
        if (obj.has(KEY_SPARK_LAST)) {
            fun parse(s: String): Map<String, Int> = s.split(",").mapNotNull {
                val kv = it.split(":"); if (kv.size == 2) kv[1].toIntOrNull()?.let { v -> kv[0] to v } else null
            }.toMap()
            val m = parse(prefs.getString(KEY_SPARK_LAST, "") ?: "").toMutableMap()
            parse(obj.optString(KEY_SPARK_LAST, "")).forEach { (u, d) -> m[u] = maxOf(m[u] ?: 0, d) }
            ed.putString(KEY_SPARK_LAST, m.entries.joinToString(",") { "${it.key}:${it.value}" })
        }
        if (obj.has(KEY_ROSTER_AT) && rosterAt() > (obj.optString(KEY_ROSTER_AT, "").toLongOrNull() ?: 0L)) {
            ed.putString("roster", prefs.getString("roster", "") ?: "").putString(KEY_ROSTER_AT, rosterAt().toString())
        }
        if (obj.has("sparks_at") && (prefs.getString("sparks_at", "")?.toLongOrNull() ?: 0L) >
            (obj.optString("sparks_at", "").toLongOrNull() ?: 0L)) {
            ed.putString("sparks", prefs.getString("sparks", "") ?: "").putString("sparks_at", prefs.getString("sparks_at", "") ?: "")
        }
        ed.apply()
        if (n > 0) PLog.i(TAG, "🗂 名单已从主存恢复（互动名单=${friendList().size} 人/${friendListMode()} 雇佣名单=${hireList().size} 人/${hireListMode()}）")
        return n
    }

    // ---- 最近一次 PK 对手（界面拿去做“拉黑刚才那个”）----

    /** 记一笔刚打过的对手与其结果（输赢由 FriendManager 用金币差判定）。 */
    fun rememberPk(uin: String, won: Boolean, power: Long) {
        prefs.edit().putString("last.pk.uin", uin).putInt("last.pk.won", if (won) 1 else 0)
            .putLong("last.pk.power", power).putInt("last.pk.day", today()).apply()
        syncUp()
    }

    fun lastPkUin(): String = prefs.getString("last.pk.uin", "") ?: ""
    fun lastPkWon(): Boolean = (prefs.getInt("last.pk.won", -1)) == 1
    fun lastPkPower(): Long = prefs.getLong("last.pk.power", 0)

    // ---- 好友名册（界面“随时拉黑任何人”靠它，不需要先打过一次）----

    /**
     * 存一份服务器好友池的 `uin⋄昵称` 清单（条目之间用 ~，字段之间用 ⋄；同一个字符分隔两级会把解析搞乱）。
     * 每次读到列表都覆盖一次；交给主存后界面就能按名字选人。
     */
    fun rememberRoster(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return       // 空回包不清掉已有的名册
        val text = items.joinToString("~") { (uin, nick) -> "$uin⋄${nick.replace("⋄", "").replace("~", "").ifBlank { uin }}" }
        prefs.edit().putString("roster", text).putString(KEY_ROSTER_AT, System.currentTimeMillis().toString()).apply()
        syncUp()
    }

    /** 名册：(uin, 显示名)。 */
    fun roster(): List<Pair<String, String>> =
        (prefs.getString("roster", "") ?: "").split("~").mapNotNull {
            val kv = it.split("⋄")
            if (kv.size < 2 || kv[0].isEmpty()) null else kv[0] to kv[1]
        }

    /** 是否允许对该好友互动：自动拉黑恒排除，其余按互动名单的模式判定。 */
    fun isEligible(uin: String, selfUin: String?): Boolean {
        if (uin.isEmpty() || uin == selfUin) return false
        if (isAutoBlacklisted(uin)) return false
        return listAllows(friendListMode(), friendList(), uin)
    }

    // ---- 自动拉黑（连续失败达阈值）----
    /** value 格式: hits;lastDay;blackDay(0=未拉黑) 。 */
    fun noteFailure(uin: String) {
        val day = today()
        val parts = (prefs.getString("auto:$uin", "") ?: "").split(";")
        val hits = ((parts.getOrNull(0)?.toIntOrNull() ?: 0) + 1)
        val lastDay = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val newHits = if (lastDay == day) hits else 1   // 每天重计连续
        val blackDay = if (newHits >= FAIL_THRESHOLD) day else 0
        prefs.edit().putString("auto:$uin", "$newHits;$day;$blackDay").apply()
        if (blackDay != 0) PLog.i(TAG, "🚫 好友 $uin 连续失败 $newHits 次，自动拉黑至 ${day + AUTO_BLACK_DAYS}")
        syncUp()
    }

    fun noteSuccess(uin: String) {
        prefs.edit().remove("auto:$uin").apply()
        syncUp()
    }

    private fun isAutoBlacklisted(uin: String): Boolean {
        val parts = (prefs.getString("auto:$uin", "") ?: "").split(";")
        val blackDay = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (blackDay == 0) return false
        if (today() >= blackDay + AUTO_BLACK_DAYS) {   // 超期解禁
            prefs.edit().remove("auto:$uin").apply()
            PLog.i(TAG, "✅ 好友 $uin 自动拉黑到期解禁")
            return false
        }
        return true
    }

    // ---- 每日上限 ----
    fun dayCount(key: String): Int {
        val parts = (prefs.getString("cap:$key", "") ?: "").split(";")
        val d = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val c = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return if (d == today()) c else 0
    }

    /** 记一次并判断是否还在每日上限内；cap < 0 = 不限（只计数，用于报告/诊断）。 */
    fun tryAcquire(key: String, cap: Int): Boolean {
        val cur = dayCount(key)
        if (!Caps.allows(cur, cap)) {
            PLog.i(TAG, "🈵 $key 今日已达上限 $cap，跳过")
            return false
        }
        prefs.edit().putString("cap:$key", "${today()};${cur + 1}").apply()
        return true
    }

    // ---- 福袋去重：一个袋 id 当天只碰一次（无论成败），跨天自动失效 ----
    fun bagSeen(bagId: String): Boolean {
        val parts = (prefs.getString("bags", "") ?: "").split(";")
        return parts.firstOrNull() == today().toString() && parts.drop(1).contains(bagId)
    }

    fun markBag(bagId: String) {
        val parts = (prefs.getString("bags", "") ?: "").split(";")
        val kept = if (parts.firstOrNull() == today().toString()) parts.drop(1).filter { it.isNotEmpty() } else emptyList()
        prefs.edit().putString("bags", (listOf(today().toString()) + (kept + bagId).takeLast(200)).joinToString(";")).apply()
    }

    // ---- 火花名单（默认黑名单模式：名单里的人不踩；白名单模式：只踩名单里的人）----
    fun sparkBlacklist(): Set<String> = csv(prefs.getString(KEY_SPARK_BL, "") ?: "")
    fun setSparkBlacklist(bl: Collection<String>) {
        prefs.edit().putString(KEY_SPARK_BL, bl.map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct().joinToString(","))
            .remove(KEY_SPARK_WL).apply()
        syncUp()
    }
    fun sparkListMode(): String =
        prefs.getString(KEY_SPARK_LIST_MODE, "")?.takeIf { it == MODE_WL || it == MODE_BL } ?: MODE_BL
    fun setSparkListMode(mode: String) {
        if (mode != MODE_WL && mode != MODE_BL) return
        prefs.edit().putString(KEY_SPARK_LIST_MODE, mode).apply(); syncUp()
    }
    /** 火花是否踩这个人（按火花名单模式判定）。 */
    fun sparkAllows(uin: String): Boolean = listAllows(sparkListMode(), sparkBlacklist(), uin)

    /** 旧三态名单迁移（幂等，见 [SparkPolicy.migrateLists]）。@return 改动键数。 */
    fun migrateSpark(): Int {
        val changes = SparkPolicy.migrateLists(prefs.all)
        if (changes.isEmpty()) return 0
        val ed = prefs.edit()
        changes.forEach { (k, v) -> if (v == null) ed.remove(k) else ed.putString(k, v) }
        ed.apply(); syncUp()
        return changes.size
    }

    fun sparkSkipToday(): Set<String> = daySet(KEY_SPARK_SKIP)
    fun markSparkSkip(uin: String) = putDaySet(KEY_SPARK_SKIP, sparkSkipToday() + uin)

    /** 今天已摸过的好友（0x96a6，每人每天一次）。 */
    fun patToday(): Set<String> = daySet(KEY_PAT_DONE)
    fun markPat(uin: String) = putDaySet(KEY_PAT_DONE, patToday() + uin)

    fun sparkLastDays(): Map<String, Int> = (prefs.getString(KEY_SPARK_LAST, "") ?: "").split(",").mapNotNull {
        val kv = it.split(":"); if (kv.size == 2) kv[0] to (kv[1].toIntOrNull() ?: return@mapNotNull null) else null
    }.toMap()

    /** 退避：返回下次允许的毫秒（0=不退避）。 */
    fun sparkBackoffUntil(): Long = (prefs.getString(KEY_SPARK_BACKOFF, "") ?: "").split(";").firstOrNull()?.toLongOrNull() ?: 0L
    fun noteSparkFailure(now: Long = System.currentTimeMillis()): Long {
        val fails = ((prefs.getString(KEY_SPARK_BACKOFF, "") ?: "").split(";").getOrNull(1)?.toIntOrNull() ?: 0) + 1
        val wait = (5 * 60_000L shl (fails - 1).coerceAtMost(4)).coerceAtMost(60 * 60_000L)   // 5→10→20→40→60 分钟
        prefs.edit().putString(KEY_SPARK_BACKOFF, "${now + wait};$fails").apply()
        return wait
    }
    fun clearSparkBackoff() { if (prefs.contains(KEY_SPARK_BACKOFF)) prefs.edit().remove(KEY_SPARK_BACKOFF).apply() }

    fun rosterAt(): Long = prefs.getString(KEY_ROSTER_AT, "")?.toLongOrNull() ?: 0L

    private fun daySet(key: String): Set<String> {
        val parts = (prefs.getString(key, "") ?: "").split(";")
        return if (parts.firstOrNull() == today().toString()) parts.drop(1).filter { it.isNotEmpty() }.toSet() else emptySet()
    }
    private fun putDaySet(key: String, set: Set<String>) {
        prefs.edit().putString(key, (listOf(today().toString()) + set.toList()).joinToString(";")).apply()
    }

    /** 今天已踩过的人（按好友逐个记，跨天自动失效）。 */
    fun pokedToday(): Set<String> {
        val parts = (prefs.getString(KEY_SPARK_POKED, "") ?: "").split(";")
        return if (parts.firstOrNull() == today().toString()) parts.drop(1).filter { it.isNotEmpty() }.toSet() else emptySet()
    }

    /** 记“今天踩过”（不再截 300：好友可能上千，重启也不能重踩）+ 上次互动日。 */
    fun markPoked(uin: String) {
        val cur = pokedToday() + uin
        val last = LinkedHashMap(sparkLastDays()); last.remove(uin); last[uin] = today()
        val lastText = last.entries.toList().takeLast(3000).joinToString(",") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_SPARK_POKED, (listOf(today().toString()) + cur.toList()).joinToString(";"))
            .putString(KEY_SPARK_LAST, lastText).apply()
        syncUp()
    }

    /** 火花列表快照交主存给界面看：`uin⋄昵名⋄阶段⋄天数⋄断天数⋄今日已踩`，条目以 ~ 分隔。 */
    fun rememberSparks(items: List<SparkEntry>, at: Long = System.currentTimeMillis()) {
        val text = items.joinToString("~") {
            listOf(it.uin, it.nick.replace("⋄", "").replace("~", "").ifBlank { it.uin }, it.stage.code, it.days, it.gapDays, if (it.pokedToday) 1 else 0).joinToString("⋄")
        }
        prefs.edit().putString(KEY_SPARKS, text).putString(KEY_SPARKS_AT, at.toString()).apply()   // 存串：主存回灌时 JSON 不分 Int/Long
        syncUp()
    }

    fun sparks(): List<SparkEntry> = decodeSparks(prefs.getString(KEY_SPARKS, "") ?: "")

    // ---- 已建立的雇佣（当天内复用同一雇佣，不重复挑人）----
    fun activeHire(): Pair<String, String>? {
        val p = (prefs.getString(KEY_HIRE_ACTIVE, "") ?: "").split(";")
        if (p.size < 3 || p[0] != today().toString() || p[1].isEmpty() || p[2].isEmpty()) return null
        return p[1] to p[2]
    }

    fun setActiveHire(uin: String, petId: String) { prefs.edit().putString(KEY_HIRE_ACTIVE, "${today()};$uin;$petId").apply() }
    fun clearActiveHire() { prefs.edit().remove(KEY_HIRE_ACTIVE).apply() }

    /**
     * 已建立的雇佣还能不能复用：按**当前**名单复检（与新挑人同一套：互动名单/自动拉黑 + 雇佣名单模式）。
     * 以前复用只问服务器可不可雇、完全不看名单——早上建立雇佣、中午把人拉黑，模块照雇不误（2026-11-10 群友实报）。
     */
    fun hireReuseAllowed(uin: String, selfUin: String?): Boolean =
        isEligible(uin, selfUin) && HirePolicy.designatedAllows(uin, hireList(), hireListMode())

    private fun csv(s: String): Set<String> = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
