package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.FriendManager
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import io.github.juwanhwang.autoqqpet.pet.SparkPolicy
import io.github.juwanhwang.autoqqpet.pet.SparkTonight
import org.json.JSONObject

/**
 * 日常杂事（不占宠物主任务时间的那些）：
 *  - 每日白捡：签到领食物 0x9c6d_0、信箱一键领取 0x9c19_0（无可领 136610 当无事，可不可领只看它）、好友福袋；
 *  - 读数：三属性 0x9b62、每日经验 0x9abe、通知 0x994a（被雇佣）；
 *  - 好友：火花（白名单每天踩、回踩）、代照顾/一键照顾。
 *
 * 节流两层：本类按**间隔**（进程内，QQ 重启会早一点再跑一次，无害）；每日**次数上限**在 FriendPolicy/DailyStore 里持久。
 * 任何一项失败都不影响其它项与主循环。
 */
class SideTasks(
    private val service: QQPetService,
    private val daily: DailyStore,
    private val friends: FriendManager?,
    private val config: RunnerConfig,
    private val view: RunnerView,
    private val alerts: Alerts,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val MAIL_EVERY_MS = 6 * 3600_000L
        private const val FRIEND_CARE_EVERY_MS = 30 * 60_000L
        // 福袋扫描间隔不在这里写死：走设置 coinbag_every_min（默认 5 分钟，2026-10-30 用户定——
        // 旧写死 10 分钟时袋子常被先到的人领空）。下一次扫描时刻由 AutoRunner 加进唤醒排程准时醒。
        private const val READ_EVERY_MS = 3600_000L
        private const val NOTICE_EVERY_MS = 10 * 60_000L
        private const val SPARK_LIST_EVERY_MS = 3 * 3600_000L
        /** 火花每轮间隔：每轮只踩 [RunnerConfig.sparkPerRound] 人，5 分钟一轮 ≈ 每小时 72 人（默认 6/轮），上千好友一天内踩完。 */
        private const val SPARK_EVERY_MS = 5 * 60_000L
        private const val SPARK_TONIGHT_EVERY_MS = 30 * 60_000L
        const val KEY_TONIGHT_SENT = "spark-tonight-sent"
        const val KEY_TONIGHT_CHECKED = "spark-tonight-checked"
        const val KEY_TONIGHT_NODATA = "spark-tonight-nodata"
        /** 日记点赞每天给最近几天点赞（服务器会忽略已点的，多给几天兜住漏跑）。 */
        private const val DIARY_LIKE_TAIL = 3
        private val last = HashMap<String, Long>()   // 跨 runner 重建保留（companion）
        @Volatile private var lastNoticeAt = 0L
        /** 今晚火花提醒槽里挂着的事件键（跨 runner 重建保留）。 */
        @Volatile private var tonightKey: String? = null

        @Synchronized
        private fun due(key: String, everyMs: Long, now: Long = System.currentTimeMillis()): Boolean {
            val t = last[key] ?: 0L
            if (now - t < everyMs) return false
            last[key] = now
            return true
        }

        /** 离下一次到点还有多久：没扫过（lastMs<=0）算马上到点；已过点算 0。纯函数，单测覆盖。 */
        internal fun waitUntil(lastMs: Long, everyMs: Long, now: Long): Long =
            if (lastMs <= 0L) 0L else (lastMs + everyMs - now).coerceAtLeast(0L)
    }

    /**
     * 离下一次福袋扫描还有多久（主循环拿它把唤醒提前到扫描点，保证间隔是真的）。
     * 没开福袋 / 好友通道不在 / 今天已领到上限 → 不用为它醒，返回 [Long.MAX_VALUE]。
     */
    fun coinBagWaitMs(now: Long = System.currentTimeMillis()): Long {
        if (!config.coinBagOn || friends == null) return Long.MAX_VALUE
        val cap = config.coinBagMaxPerDay
        if (cap > 0 && daily.count("coinbag") >= cap) return Long.MAX_VALUE
        val every = config.coinBagEveryMin.coerceAtLeast(1) * 60_000L
        return waitUntil(last["coinbag"] ?: 0L, every, now)
    }

    /** 跑一轮到点的杂事；返回做了什么（日志/状态用）。 */
    fun runDue(): List<String> {
        val done = ArrayList<String>()
        fun step(on: Boolean, block: () -> String?) { if (on) runCatching { block()?.let { done += it } }.onFailure { log("🧹 杂事出错：${it.message}") } }
        step(config.checkinFoodOn && daily.count("checkin_try") == 0) { checkin() }
        step(config.svipGiftOn && daily.count("svip_try") == 0) { svipGift() }
        step(config.diaryLikeOn && daily.count("diary_like_try") == 0) { diaryLike() }
        step(config.dailyItemOn && daily.count("daily_item_try") == 0) { dailyItem() }
        step(config.adGiftOn && daily.count("ad_gift_try") == 0) { adGift() }
        step((config.mailClaimOn || config.mailCheckOn) && due("mail", MAIL_EVERY_MS) && mailDue()) { mail() }
        step(config.attrReadOn && due("attrs", READ_EVERY_MS)) { readAttrs() }
        step(config.expReadOn && due("exp", READ_EVERY_MS)) { readExp() }
        step(config.hiredPollOn && due("notice", NOTICE_EVERY_MS)) { pollHired() }
        val f = friends
        if (f != null) {
            step((config.sparkOn || config.sparkRemindOn || (config.patOn && config.patSparkOnly)) && due("sparklist", SPARK_LIST_EVERY_MS)) { sparkList(f) }
            step((config.sparkOn || config.sparkReturnOn) && due("spark", SPARK_EVERY_MS)) {
                val n = f.sparkRound(config.sparkPerRound, config.sparkMaxPerDay, config.sparkReturnOn, f.cachedSparks(), dailyOn = config.sparkOn)
                view.sparkProgress = SparkPolicy.progress(f.sparkRosterCached(), f.sparkRosterCached().filter { !f.sparkAllows(it) }.toSet(), f.pokedToday(), f.selfUinOrEmpty())
                if (n > 0) "踩踩 $n 人" else null
            }
            step(config.patOn && due("pat", SPARK_EVERY_MS)) {
                val sparks = f.cachedSparks()
                if (config.patSparkOnly && sparks.isEmpty()) null   // 火花快照还没有：sparklist 步骤会刷（3 小时节流）
                else {
                    val n = f.patRound(config.patSparkOnly, sparks, config.sparkPerRound)
                    if (n > 0) "摸好友 $n 人" else null
                }
            }
            sparkTonightStep(f)
            step((config.friendCareOn || config.friendOneClickOn) && due("fcare", FRIEND_CARE_EVERY_MS)) {
                val n = f.careRound(config.friendCareFloor, config.friendCareMaxPerDay, oneClick = config.friendOneClickOn && !config.friendCareOn,
                    oneClickCap = config.friendOneClickMaxPerDay, push = config.feedPushOn)
                if (n > 0) "代照顾好友 $n 次" else null
            }
            step(config.coinBagOn && due("coinbag", config.coinBagEveryMin.coerceAtLeast(1) * 60_000L)) {
                val n = f.coinBagRound(config.coinBagMaxPerDay, config.sparkPerRound)
                if (n > 0) "领福袋 $n 个" else null
            }
        }
        return done
    }

    /**
     * 信箱（R7，真机 2026-10-15 08:30：0x9c19 回 136610，同一轮却报“信箱可领 6”——那 6 封是早就领完的历史邮件）。
     *
     * **判据只有 0x9c19 的返回码**（定案，勿改）：成功 = 领到了；**136610 = 没东西可领**；其它 = 未确认。
     * 0x9c11 的“带附件”不等于“可领”（列表里没有已领标志，抓包未见），所以**不再拿附件数当可领数**，也不再据此挂提醒。
     * 只开“提醒”不开“领取”时无从判断（判断要靠 0x9c19，而它本身就是领取），只记一行说明、不报可领。
     */
    fun mail(): String? {
        if (!config.mailClaimOn) {
            if (daily.count("mail-check-note") == 0) {
                daily.increment("mail-check-note")
                log("📮 信箱：只开了提醒没开领取—— 列表没有“已领”标志，无法判断是否可领，不再按附件数提醒（开「信箱一键领取」以结果为准）")
            }
            view.mailClaimable = -1
            return null
        }
        val c = PetResult.write { service.mail.batchClaim() }
        return when {
            c is PetResult.Ok -> {
                daily.increment("mail_claim"); view.mailClaimable = 0; alerts.clear(Alerts.MAIL)
                // 领到了什么：回包结构没抓到过成功样本，只报字节数；不再读 0x9c11 凑“领了几封”（历史邮件会把数字带偏）
                log("📮 信箱一键领取：已领取（回包 ${c.value.size}B）"); "信箱已领取"
            }
            (c as? PetResult.Rejected)?.code == Proto.CODE_MAIL_NOTHING -> {
                view.mailClaimable = 0; alerts.clear(Alerts.MAIL)
                log("📮 信箱：没有可领的（136610）"); null
            }
            c is PetResult.Rejected && c.code >= 100_000 -> {
                // 服务器明确拒绝（业务码 ≥100000，排除 150 这类通道/权限码）、但不是“没东西可领”：说明信箱里有东西、只是自动领不了（例如要走活动页）→ 提醒去 QQ 领。
                // 这是唯一挂信箱提醒的地方（R7：不再按 0x9c11 附件数提醒）；code 的含义是推断（没抓到过成功/失败样本）。
                view.mailClaimable = 1
                alerts.set(Alerts.MAIL, "信箱里有奖励没能自动领取（服务器返回 ${c.code}），去 QQ 宠物信箱手动领一下。", Alerts.Level.INFO, src = Alerts.SRC_MAIL_CLAIM)
                log("📮 信箱一键领取被拒（${c.describe()}）：挂提醒去 QQ 手动领"); null
            }
            else -> { log("📮 信箱一键领取未确认：${c.describe()}（不重发，下一轮再看）"); null }
        }
    }

    /** 信箱节流（持久，QQ 重启也不提前重跑）：距上次 ≥ 6h 才再领一次。 */
    private fun mailDue(): Boolean {
        val nowH = (System.currentTimeMillis() / 3600_000L).toInt()
        val last = daily.count("mail-last-h")
        if (last > 0 && nowH - last < (MAIL_EVERY_MS / 3600_000L).toInt()) return false
        daily.setCount("mail-last-h", nowH)
        return true
    }

    /** 签到领食物：每天一次；回包 f1 = 领后库存，空回包 = 今天已领（交接 §9.7）。 */
    fun checkin(): String? {
        daily.increment("checkin_try")
        val r = PetResult.write { service.inventory.claimCheckinFood() }
        return when {
            r is PetResult.Ok && r.value != null -> { daily.increment("checkin"); log("📅 签到领食物成功，饼干现有 ${r.value}"); "签到领食物" }
            r is PetResult.Ok -> { log("📅 签到食物今天已领过（空回包）"); null }
            else -> { log("📅 签到领食物未确认：${r.describe()}；明天再试"); null }
        }
    }

    /** 每日免费学习道具（0x9e9c，2026-11-11 定案）：调用即领、幂等（已领回状态 2），每天一次。 */
    fun dailyItem(): String? {
        daily.increment("daily_item_try")
        val r = PetResult.write { service.item.claimDaily() }
        return when {
            r is PetResult.Ok -> { daily.increment("daily_item"); log("🍼 每日学习道具：已领取（高钙奶，出门学习可用）"); "每日学习道具" }
            else -> { log("🍼 每日学习道具领取未确认：${r.describe()}，明天再试"); null }
        }
    }

    /**
     * 每日广告免费福利（2026-11-11 抓包）：rewardType=61，gift_bundle 固定礼包 10002。
     * 领取失败只记一行，今天不再试。
     */
    fun adGift(): String? {
        daily.increment("ad_gift_try")
        val petId = runCatching { service.ensurePetId() }.getOrDefault("")
        if (petId.isEmpty()) { log("📺 广告福利：还没读到 petId，今天先跳过"); return null }
        val r = service.rewardAd.watchDailyGift(petId, config.adBackgroundClaimOn)
        return if (r.ok) {
            daily.increment("ad_gift")
            log("📺 广告福利：已领取每日广告免费福利")
            "看广告领福利"
        } else {
            log("📺 广告福利：${r.message}")
            null
        }
    }

    /**
     * 日记点赞（2026-11-11 定案）：0x9762 读本月列表 → 给**最近 [DIARY_LIKE_TAIL] 天**的日记各点一次赞（0x9ba3）。
     * 已点过的服务器静默忽略（回空包），所以不需要本地记录；每天一次、量很小。
     */
    fun diaryLike(): String? {
        daily.increment("diary_like_try")
        val month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
        val r = PetResult.read { service.diary.month(month) }
        val entries = r.getOrNull() ?: run { log("📔 日记列表读取失败（${r.describe()}），明天再试"); return null }
        if (entries.isEmpty()) { log("📔 日记：本月还没有日记"); return null }
        var liked = 0
        for (e in entries.takeLast(DIARY_LIKE_TAIL)) {
            val w = PetResult.write { service.diary.like(e.uuid) }
            when {
                w is PetResult.Ok && w.value.isNotEmpty() -> { liked++; log("📔 给 ${e.date} 的日记点赞：${w.value}") }
                w is PetResult.Ok -> { /* 空回包 = 已经点过，服务器忽略 */ }
                else -> log("📔 给 ${e.date} 的日记点赞未确认：${w.describe()}")
            }
        }
        return if (liked > 0) "日记点赞 $liked 篇" else null
    }

    /**
     * 每日 SVIP 礼包（2026-11-10 定案）：0x9bd4 读列表拿 SubActId → WebSSO 0x9377 直调领取。
     * 判定口径：`.4.1=1` 才领；`-4020（中了用户日限制）`=今天已经领过。读失败也算今天试过，避免打转。
     */
    fun svipGift(): String? {
        daily.increment("svip_try")
        val r = PetResult.read { service.vip.receiveInfo() }
        val g = r.getOrNull() ?: run { log("🎁 SVIP 礼包：读取状态失败（${r.describe()}），明天再试"); return null }
        if (!g.claimable) {
            log("🎁 SVIP 礼包：今天没有可领的（状态 ${g.state}${if (g.items.isEmpty()) "" else "，内容 ${g.items.joinToString("、")}"}）")
            return null
        }
        val items = g.items.joinToString("、").ifEmpty { "礼包" }
        val ret = service.vip.claim(g.subActId) { json ->
            val code = runCatching { JSONObject(json).optInt("Code", Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
            when {
                code == 0 -> { daily.increment("svip_claim"); log("🎁 SVIP 礼包领取成功：$items") }
                code == -4020 -> log("🎁 SVIP 礼包：今天已经领过了（-4020）")
                json.isEmpty() -> log("🎁 SVIP 礼包：没收到回包（$items，稍后看日志）")
                else -> log("🎁 SVIP 礼包领取被拒：$json")
            }
        }
        log("🎁 SVIP 礼包：已发起领取（$items）· $ret")
        return "SVIP 礼包"
    }

    fun readAttrs(): String? {
        val r = PetResult.read { service.profile.attributes() }
        val a = r.getOrNull() ?: run { log("💪 读三属性失败：${r.describe()}"); return null }
        view.attrs = a; view.attrsAt = System.currentTimeMillis()
        return null
    }

    fun readExp(): String? {
        val r = PetResult.read { service.profile.dailyExp() }
        val e = r.getOrNull() ?: run { log("✨ 读每日经验失败：${r.describe()}"); return null }
        view.exp = e; view.expAt = System.currentTimeMillis()
        return null
    }

    /** 拉 0x994a 找“雇佣了”（服务器不推送）。新出现的一条记日志并挂 INFO 提醒（任务结束后自然清掉）。 */
    fun pollHired(): String? {
        val r = PetResult.read { service.friend.hiredNotices() }
        val list = r.getOrNull() ?: return null
        view.hiredNotices = list; view.hiredNoticesAt = System.currentTimeMillis()
        val newest = list.maxByOrNull { it.atMillis } ?: return null
        if (newest.atMillis <= lastNoticeAt) return null
        val first = lastNoticeAt == 0L
        lastNoticeAt = newest.atMillis
        if (first && System.currentTimeMillis() - newest.atMillis > 6 * 3600_000L) return null   // 启动时读到的旧通知不报
        daily.increment("hired_notice")
        log("🤝 通知：被 ${newest.employer.ifBlank { "好友" }} 雇佣（${java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(newest.atMillis))}）")
        return "被 ${newest.employer.ifBlank { "好友" }} 雇佣"
    }

    /** 火花列表（实验：请求体推断）→ 交主存给界面；白名单里快断的提醒。 */
    private fun sparkList(f: FriendManager): String? {
        val list = f.refreshSparks() ?: return null
        if (!config.sparkRemindOn) return null
        val risk = SparkPolicy.atRisk(list, list.map { it.uin }.filter { !f.sparkAllows(it) }.toSet())
        if (risk.isEmpty()) { alerts.clear(Alerts.SPARK); return null }
        val text = risk.take(3).joinToString("、") { e ->
            val left = SparkPolicy.daysLeft(e)
            "${e.nick.ifBlank { e.uin }}（${e.days} 天${if (left >= 0) "，再断 $left 天清零" else ""}）"
        } + if (risk.size > 3) " 等 ${risk.size} 人" else ""
        alerts.set(Alerts.SPARK, "火花快清零了：$text。连续 7 天不互踩会清零，今天互踩一次就能重燃。", Alerts.Level.INFO)
        return null
    }

    /**
     * 火花“今晚熄灭”提醒（并进现有调度：随 tick 的杂事阶段跑；[wakeHint] 让状态机把唤醒对齐到设定时刻，不新增驱动源）。
     * 到点后每 30 分钟复查一次：一天只“响”一次（[Alerts] 文本变化由通知侧静默更新），互踩完就撤。
     */
    private fun sparkTonightStep(f: FriendManager) {
        if (!config.sparkTonightOn) { alerts.clear(Alerts.SPARK_TONIGHT); tonightKey = null; return }
        val now = java.time.LocalTime.now()
        if (!SparkTonight.due(now, config.sparkTonightAtMin)) {
            if (tonightKey != null) { alerts.clear(Alerts.SPARK_TONIGHT); tonightKey = null }   // 过了午夜：昨天那条作废
            return
        }
        if (!due("sparktonight", SPARK_TONIGHT_EVERY_MS)) return
        runCatching { sparkTonight(f, now) }.onFailure { log("🔥 今晚火花检查出错：${it.message}") }
    }

    internal fun sparkTonight(f: FriendManager, now: java.time.LocalTime) {
        daily.setCount(KEY_TONIGHT_CHECKED, 1)
        val sparks = f.refreshSparks()
        val visitors = PetResult.read { service.friend.likeVisitors() }.getOrNull()
        val rosterSet = f.sparkRosterCached().toSet()
        val v = SparkTonight.evaluate(
            now, config.sparkTonightAtMin, sparks, visitors, f.pokedToday(), rosterSet, rosterSet.filter { !f.sparkAllows(it) }.toSet(), f.selfUinOrEmpty(),
        )
        applyTonight(v)
    }

    /** 判定结果 → 提醒槽（一天一次 + 按事件去重，规则见 [SparkTonight.action]）。 */
    internal fun applyTonight(v: SparkTonight.Verdict) {
        when (SparkTonight.action(v, daily.count(KEY_TONIGHT_SENT) > 0, tonightKey, daily.count(KEY_TONIGHT_NODATA) > 0)) {
            SparkTonight.Action.NONE, SparkTonight.Action.KEEP -> Unit
            SparkTonight.Action.LOG_NO_DATA -> {
                daily.increment(KEY_TONIGHT_NODATA)
                log("🔥 今晚火花：读不到${(v as SparkTonight.Verdict.NoData).what}（请求体实验），本次不提醒")
            }
            SparkTonight.Action.CLEAR -> {
                if (v is SparkTonight.Verdict.AllDone) log("🔥 今晚火花：都互踩完了，撤掉提醒")
                alerts.clear(Alerts.SPARK_TONIGHT); tonightKey = null
            }
            SparkTonight.Action.NOTIFY -> {
                val r = v as SparkTonight.Verdict.Remind
                daily.increment(KEY_TONIGHT_SENT); log("🔥 ${r.text}")
                alerts.set(Alerts.SPARK_TONIGHT, r.text, Alerts.Level.INFO, incident = r.key); tonightKey = r.key
            }
            SparkTonight.Action.UPDATE_SILENT -> {
                val r = v as SparkTonight.Verdict.Remind
                alerts.set(Alerts.SPARK_TONIGHT, r.text, Alerts.Level.INFO, incident = r.key); tonightKey = r.key
            }
        }
    }

    /** 状态机排唤醒时的对齐点：今天还没检查过、开关开着、设定时刻在将来 → 返回那个时刻（墙钟 ms），否则 0。 */
    fun wakeHint(nowMs: Long = System.currentTimeMillis()): Long {
        if (!config.sparkTonightOn || friends == null || daily.count(KEY_TONIGHT_CHECKED) > 0) return 0L
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val at = today.atStartOfDay(zone).plusMinutes(config.sparkTonightAtMin.toLong()).toInstant().toEpochMilli()
        return if (at > nowMs) at else 0L
    }
}
