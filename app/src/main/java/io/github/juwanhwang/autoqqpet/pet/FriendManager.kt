package io.github.juwanhwang.autoqqpet.pet

import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.pet.service.FriendApi
import kotlin.random.Random

/**
 * 好友互动编排：PK / 访问 / 踩踩 / 代喂代洗 / 一键照顾 / 福袋。
 * 全部经 [FriendPolicy] 名单门控 + 每日上限 + 失败自动拉黑；动作间随机间隔（风控敏感，参考 copilot #19）。
 */
class FriendManager(
    private val service: QQPetService,
    private val policy: FriendPolicy,
    private val selfUin: String?,
    private val daily: DailyStore? = null,
    private val log: (String) -> Unit = { PLog.i("Friend", it) },
    /** PK 输了是否拉黑对方（设置「PK 输了拉黑对方」，默认开＝旧行为）；每次打之前现读，改设置立即生效。 */
    private val pkBlacklistOnLoss: () -> Boolean = { true },
) {

    companion object {
        /** 每轮代照顾/福袋最多看几个好友（一轮发包数有上限）。 */
        private const val ROUND_FRIENDS = 6
        /** 选 PK 对手时最多为几个候选现读战力（每个一条 0x9b62，结果缓存 6 小时）。 */
        private const val PK_PROBE = 8
        private const val POWER_TTL_MS = 6 * 3600_000L
        private const val SELF_POWER_TTL_MS = 3600_000L
        /** 火花名册多久全量翻一次（好友上千时一次要翻上百页）。 */
        private const val ROSTER_TTL_MS = 6 * 3600_000L

        /**
         * PK 对手资格集合（纯函数，可单测）：战力已知时只留 ≤ 我方 [marginPct]% 的（资格口径不变）；
         * 我方战力未知、或没人战力已知时资格无从判，整批候选都算（名单 + 输一次拉黑兜底）。
         */
        fun qualified(cands: List<Pair<FriendApi.Friend, Long>>, mine: Long, marginPct: Int): List<FriendApi.Friend> {
            if (cands.isEmpty()) return emptyList()
            val known = cands.filter { it.second > 0 }
            if (mine <= 0 || known.isEmpty()) return cands.map { it.first }
            return known.filter { it.second * 100 <= mine * marginPct }.map { it.first }
        }

        /**
         * PK 选敌（纯函数，可单测）：在 [qualified] 资格集合里均匀随机抽一个。
         * 以前固定挑最弱的那个，2026-10-18 全天 10 场全打在同一个人身上，用户要求打散；
         * 有人知道战力而没人够弱 → 集合为空 → 不打（与原口径一致）。
         */
        fun choose(cands: List<Pair<FriendApi.Friend, Long>>, mine: Long, marginPct: Int, random: Random = Random.Default): FriendApi.Friend? =
            qualified(cands, mine, marginPct).randomOrNull(random)
    }

    private var selfPowerCache = -1L
    /** 共用节流器：上次好友写请求的时刻（进程级，跨轮）。 */
    @Volatile private var lastFriendWriteAt = 0L
    /** 被踩明细第一次读通时留一行日志（每进程一次）：成功读取默认无痕，出问题时无从分辨“读通了没人”与“根本没读通”。 */
    @Volatile private var visitorReadLogged = false
    private var selfPowerAt = 0L
    private val powerCache = HashMap<String, Pair<Long, Long>>()   // petId → (战力, 读取时刻)

    /** 我方战力：三属性之和（0x9b62）优先，读不到退回 0x9ad4。 */
    fun selfPower(): Long {
        val now = System.currentTimeMillis()
        if (selfPowerCache < 0 || now - selfPowerAt > SELF_POWER_TTL_MS) {
            val attrs = PetResult.read { service.profile.attributes() }.getOrNull()
            selfPowerCache = attrs?.power ?: PetResult.read { service.profile.pkPower().power }.getOrNull() ?: 0L
            selfPowerAt = now
            log("⚔️ 我方战力=${if (selfPowerCache > 0) selfPowerCache else "未读到"}${attrs?.let { "（力${it.strength}/智${it.intel}/魅${it.charm}）" } ?: ""}")
        }
        return selfPowerCache
    }

    /** 好友战力：0x9b62 传好友 petId → 三属性求和（交接 §1）；好友池自带战力字段时先用它。缓存 6 小时。 */
    fun friendPower(f: FriendApi.Friend): Long {
        if (f.power > 0) return f.power
        val now = System.currentTimeMillis()
        powerCache[f.petId]?.let { (p, at) -> if (now - at < POWER_TTL_MS) return p }
        val p = PetResult.read { service.profile.attributes(f.petId)?.power ?: 0L }.getOrNull() ?: 0L
        powerCache[f.petId] = p to now
        return p
    }

    private fun pool(): List<FriendApi.Friend> {
        val p = PetResult.read { service.friend.pool() }.getOrNull().orEmpty()
        if (p.isNotEmpty()) policy.rememberRoster(p.map { it.uin to it.displayName })
        return p
    }

    private fun eligible(): List<FriendApi.Friend> = pool().filter { policy.isEligible(it.uin, selfUin) }

    /** 主动刷名册（QQ 启动与界面“刷新好友列表”）。 */
    fun refreshRoster(): Int = pool().size

    internal fun pickOpponent(pool: List<FriendApi.Friend>, marginPct: Int = 90): FriendApi.Friend? {
        val el = pool.filter { policy.isEligible(it.uin, selfUin) }.take(PK_PROBE)
        val mine = selfPower()
        val cands = el.map { it to friendPower(it) }
        val pick = choose(cands, mine, marginPct)
        if (pick == null) log("⚔️ 看了 ${cands.size} 个好友，没人战力 ≤ 我方 $mine 的 $marginPct%，本轮不打 PK")
        return pick
    }

    private fun benign(r: PetResult<*>): Boolean {
        val m = when (r) { is PetResult.Rejected -> r.message; is PetResult.Unverified -> r.message; else -> "" }
        return listOf("重复", "已", "上限", "累", "频", "次数").any { m.contains(it) } || (r as? PetResult.Rejected)?.code == 136202
    }

    /**
     * 打一场 PK：开始(0x975e f1=6900) → 等 8~10s（PK 只有 8 秒）→ 结算 0x9760 action=6000（被拒再试 1000：页面两种都发过）。
     * **输赢只读结算称号 `.1.18.8`（大获全胜/惜败）**，回包没有就读 0x9874 结算页；绝不拿金币差判（输局也给 15 金币）。
     * 省包：不再前后各读一次数值。输了拉黑（「PK 输了拉黑对方」开着时，用户定的）；读不出输赢不拉黑。
     */
    fun pkOnce(cap: Int = FriendPolicy.DAY_CAP_PK, marginPct: Int = 90): Boolean {
        service.ensurePetId()
        val opp = pickOpponent(pool(), marginPct) ?: run { log("无通过名单的可用对手"); return false }
        if (!policy.tryAcquire("pk", cap)) return false
        val power = friendPower(opp)
        val sid = PetResult.write { service.story.startPk(opp.uin, opp.petId) }.getOrNull()?.storyId.orEmpty()
        if (!sid.startsWith("6900_")) {
            log("⚔️ PK 未开成 uin=${opp.uin} sid='$sid'"); policy.noteFailure(opp.uin); policy.rememberPk(opp.uin, false, power); return false
        }
        sleepRandom(8_500, 10_500)
        var r: PetResult<StoryOutcome> = PetResult.write { service.story.recall(sid) }
        if (r is PetResult.Rejected) r = PetResult.write { service.story.settle(sid) }
        var o = r.getOrNull() ?: StoryOutcome()
        if (o.pkWon == null) PetResult.read { service.story.settlementOutcome(sid, System.currentTimeMillis()) }.getOrNull()?.let { v -> if (!v.empty) o = v }
        daily?.increment("pk")
        if (o.rewards.isNotEmpty()) daily?.addRewards(o.rewards)
        val loot = o.rewards.joinToString(" ") { "${it.name}${if (it.amount >= 0) "+" else ""}${it.amount}" }
        return when (o.pkWon) {
            true -> {
                policy.noteSuccess(opp.uin); policy.rememberPk(opp.uin, true, power); daily?.increment("pk_win")
                log("⚔️ PK「${o.title}」赢 ${opp.displayName}（战力 $power）$loot"); true
            }
            false -> {
                policy.noteFailure(opp.uin); policy.rememberPk(opp.uin, false, power)
                if (pkBlacklistOnLoss()) {
                    val how = policy.excludeForPkLoss(opp.uin)
                    val note = when (how) {
                        "bl" -> "已加入互动名单（黑名单模式，不再互动，设置页可放出）"
                        "wl" -> "已从互动名单移除（白名单模式，不再互动，设置页可加回）"
                        else -> "对方本来就不在互动范围内"
                    }
                    log("⚔️ PK「${o.title}」输给 ${opp.displayName}（战力 $power）$loot → $note")
                } else {
                    log("⚔️ PK「${o.title}」输给 ${opp.displayName}（战力 $power）$loot → 按设置不拉黑")
                }
                false
            }
            null -> {
                log("⚔️ PK ${opp.displayName} 结算 ${r.describe()}：读不出输赢（无结算称号），不拉黑"); r.ok
            }
        }
    }

    fun visitOnce(): Boolean {
        service.ensurePetId()
        val f = eligible().firstOrNull() ?: run { log("无可用好友"); return false }
        if (!policy.tryAcquire("visit", FriendPolicy.DAY_CAP_VISIT)) return false
        val r = PetResult.write { service.friend.visit(f.uin, f.petId) }
        if (r.ok) policy.noteSuccess(f.uin) else policy.noteFailure(f.uin)
        log("🏠 访问 ${f.displayName} → ${r.describe()}")
        return r.ok
    }

    fun pokeOnce(): Boolean {
        val f = eligible().firstOrNull() ?: run { log("无可用好友"); return false }
        if (!policy.tryAcquire("poke", FriendPolicy.DAY_CAP_POKE)) return false
        val r = PetResult.write { service.friend.poke(f.uin) }
        when {
            r.ok -> policy.noteSuccess(f.uin)
            benign(r) -> Unit
            else -> policy.noteFailure(f.uin)
        }
        log("👋 踩踩 ${f.displayName} → ${r.describe()}")
        return r.ok
    }

    /** 好友三属性（0x96f2 f1=好友 petId，抓包实测；取代旧 0x976c 递归找字段）。 */
    fun friendValues(petId: String): PetValues? = PetResult.read { service.profile.friendValues(petId) }.getOrNull()

    /**
     * 代喂代洗一轮：好友体力/清洁低于 [floor] 就用**自己的**饼干/香皂（不替好友花金币买）照顾一下，
     * 以好友数值前后差判生效。[oneClick]=true 时改用 0x9b80 一键照顾（服务器会自动花金币补道具，另有上限）。
     * @return 本轮成功照顾的次数
     */
    fun careRound(floor: Int, cap: Int, oneClick: Boolean, oneClickCap: Int, specific: Pair<String, String>? = null, push: Boolean = false): Int {
        service.ensurePetId()
        val targets = if (specific != null) listOf(FriendApi.Friend(specific.first, specific.second, 0, 0))
        else eligible().shuffled().take(ROUND_FRIENDS)
        var done = 0
        for (f in targets) {
            if (f.petId.isEmpty()) continue
            val v = friendValues(f.petId) ?: continue
            val needFeed = v.hunger < floor
            val needBath = v.clean < floor
            if (!needFeed && !needBath) continue
            if (oneClick) {
                if (!policy.tryAcquire("foneclick", oneClickCap)) break
                val r = PetResult.write { service.friend.oneClickCare(f.petId) }
                sleepRandom(800, 1500)
                val after = friendValues(f.petId)
                val ok = r.getOrNull() == true || (after != null && (after.hunger > v.hunger || after.clean > v.clean))
                log("🧺 一键照顾 ${f.displayName}：体力 ${v.hunger.toInt()}→${after?.hunger?.toInt() ?: "?"} 清洁 ${v.clean.toInt()}→${after?.clean?.toInt() ?: "?"} ${r.describe()}")
                if (ok) { done++; daily?.increment("friend_oneclick"); policy.noteSuccess(f.uin) } else policy.noteFailure(f.uin)
                continue
            }
            if (needFeed && Caps.allows(policy.dayCount("fcare"), cap)) {
                val stock = PetResult.read { service.inventory.biscuits() }.getOrNull() ?: 0L
                if (stock <= 0) log("🍪 自己没饼干了，不代喂 ${f.displayName}")
                else if (policy.tryAcquire("fcare", cap)) {
                    val r = PetResult.write { service.inventory.feedFriend(f.uin, f.petId) }
                    sleepRandom(700, 1200)
                    val after = friendValues(f.petId)
                    val ok = after != null && after.hunger > v.hunger
                    log("🍖 代喂 ${f.displayName} 体力 ${v.hunger.toInt()}→${after?.hunger?.toInt() ?: "?"}：${if (ok) "成功" else "未见上涨 ${r.describe()}"}")
                    if (ok) {
                        done++; daily?.increment("friend_care")
                        if (push) log("📨 通知 ${f.displayName}「我喂了你宠物」→ " + PetResult.write { service.inventory.feedPush(f.uin) }.describe())
                    } else policy.noteFailure(f.uin)
                }
            }
            if (needBath && Caps.allows(policy.dayCount("fcare"), cap)) {
                val soap = PetResult.read { service.inventory.soap() }.getOrNull() ?: 0L
                if (soap <= 0) log("🧼 自己没香皂了，不代洗 ${f.displayName}")
                else if (policy.tryAcquire("fcare", cap)) {
                    val r = PetResult.write { service.inventory.bath(Proto.SOAP_ITEM_ID, 1, f.uin, f.petId) }
                    sleepRandom(700, 1200)
                    val after = friendValues(f.petId)
                    val ok = after != null && after.clean > v.clean
                    log("🛁 代洗 ${f.displayName} 清洁 ${v.clean.toInt()}→${after?.clean?.toInt() ?: "?"}：${if (ok) "成功" else "未见上涨 ${r.describe()}"}")
                    if (ok) { done++; daily?.increment("friend_care") } else policy.noteFailure(f.uin)
                }
            }
            if (!Caps.allows(policy.dayCount("fcare"), cap) && !oneClick) break
        }
        return done
    }

    /**
     * 福袋一轮。两个来源，领取走同一条链（0x9d71 `{1:自己petId, 3:袋id}` 查详情 → 0x9d70 领，区别只在领取时 f2 传谁）：
     *  - **自己的袋**：0x9acb_0 事件聚合查（抓包定案 2026-10-17 02:10–02:23）：袋 id 在回包 `.1.2.1`，
     *    未领会重复报、领完即无，不用记账；领取时 f2 传自己 petId。事件流里好友的袋也会出现，
     *    所以先扫好友池、同轮已知是好友的 id 从自己候选里剔掉（拿错会白占一次领取记账）。
     *  - **好友的袋**：好友栏 0x985d 每条记录的 `.21` 是福袋标记（**在就是有，领完服务器自己去掉**，不用自己记账），
     *    领取时 f2 传好友 petId。只看好友栏里的人（非好友客户端本地就拦，服务器不认）。
     * 到手 = 领前后金币差；文案 `.1.3.2` 记日志。
     */
    fun coinBagRound(cap: Int, perRound: Int = 6): Int {
        service.ensurePetId()
        var opened = 0
        var gold: Long? = null   // 第一袋开领前才读（没袋就不读）
        val budget = perRound.coerceAtLeast(1)   // 每轮上限（与火花踩同一个“每轮最多几人”），剩下的下一轮再领

        // 领一袋。@return 还能不能继续（每轮上限或每日上限用完 → false，停下）
        fun claim(ownerPetId: String, label: String, bag: String): Boolean {
            if (policy.bagSeen(bag)) return true
            if (opened >= budget) return false
            policy.markBag(bag)
            if (!policy.tryAcquire("coinbag", cap)) return false
            pace()
            PetResult.read { service.friend.coinBagInfo(bag) }
            sleepRandom(500, 1_000)
            if (gold == null) gold = PetResult.read { service.profile.values().gold.toLong() }.getOrNull()
            val r = PetResult.write { service.friend.openCoinBag(ownerPetId, bag) }
            sleepRandom(600, 1_000)
            val after = PetResult.read { service.profile.values().gold.toLong() }.getOrNull()
            val before = gold
            val got = if (before != null && after != null) (after - before).coerceAtLeast(0) else 0
            if (after != null) gold = after
            log("🧧 领 $label 的福袋 → ${r.describe()}；${r.getOrNull()?.summary.orEmpty()} 到手 $got 金币（余额差）")
            if (r.ok) { opened++; daily?.let { d -> d.increment("coinbag"); d.setCount("coinbag_gold", d.count("coinbag_gold") + got.toInt()) } }
            sleepRandom(1_000, 2_000)
            return true
        }

        // 先扫好友池：既是好友袋的领取名单，也是自己候选的排除表（事件流里混有好友的袋）
        val withBag = pool().filter { it.bagId.isNotEmpty() && it.uin != selfUin && !policy.bagBlocked(it.uin) }
        val friendBagIds = withBag.map { it.bagId }.toSet()
        // 先领自己的
        val ownIds = PetResult.read { service.friend.ownCoinBagIds() }.getOrNull().orEmpty()
            .filter { it !in friendBagIds }
        for (bag in ownIds) {
            if (!claim(service.ensurePetId(), "自己", bag)) return opened
        }
        // 再领好友的
        for (f in withBag) {
            if (!claim(f.petId, f.displayName, f.bagId)) return opened
        }
        return opened
    }

    /**
     * 火花名册：好友列表 0x985d（不含陌生人）。好友可能上千 → **6 小时才全量翻一次页**（最多 150 页），平时用落盘的名册，
     * 不让每轮火花都去翻几十页（R2：绝不放大请求量）。
     */
    fun sparkRoster(now: Long = System.currentTimeMillis()): List<String> {
        val cached = policy.roster().map { it.first }
        if (cached.isNotEmpty() && now - policy.rosterAt() < ROSTER_TTL_MS) return cached
        val p = PetResult.read { service.friend.pool(maxPages = 150) }.getOrNull().orEmpty()
        if (p.isNotEmpty()) { policy.rememberRoster(p.map { it.uin to it.displayName }); return p.map { it.uin } }
        return cached
    }

    /**
     * 火花一轮（2026-10-15 新逻辑）：**默认踩所有好友，除了黑名单**；可选回踩不在好友列表里的陌生人。
     * 规模与风控：每轮最多 [perRound] 人、每次之间 1.5~4s 随机间隔；今天已踩/今天被拒的持久化（重启不重踩）；
     * 传输层失败立刻停下并退避（5→10→20→40→60 分钟）；[dailyCap]>0 时另有每日总上限（0=不限）。
     * 踩 = 0x985b_0 `{1:好友uin}`，不伴随其他包（交接 §9.3）。136202（今天踩过了）也算完成。
     * @return 本轮踩成的人数
     */
    fun sparkRound(
        perRound: Int, dailyCap: Int, returnOn: Boolean, sparks: List<SparkEntry>, now: Long = System.currentTimeMillis(), dailyOn: Boolean = true,
    ): Int {
        if (!dailyOn && !returnOn) return 0
        val until = policy.sparkBackoffUntil()
        if (until > now) return 0
        val self = selfUin.orEmpty()
        val poked = policy.pokedToday()
        val roster = sparkRoster(now)
        // SparkPolicy 吃的是“排除集”：按火花名单模式把名单换算成这批名册里要排除的人
        val bl = roster.filter { !policy.sparkAllows(it) }.toSet()
        // 总开关关、回踩开：只跑回踩（好友也算进回踩对象）
        val targets = if (dailyOn) SparkPolicy.dailyTargets(roster, bl, poked, self, sparks, policy.sparkSkipToday(), policy.sparkLastDays()) else emptyList()
        // 被踩明细只在本轮还有空位时才读：好友多时，回踩要等当天好友踩完才开始（省一包，见 DEV-NOTES）
        val visitors = if (returnOn && targets.size < perRound) PetResult.read { service.friend.likeVisitors() }.let { r ->
            r.getOrNull() ?: run { log("👣 读被踩明细失败：${r.describe()}"); emptyList() }
        } else emptyList()
        if (visitors.isNotEmpty() && !visitorReadLogged) {
            visitorReadLogged = true
            log("👣 被踩明细读通了：访客共 ${visitors.size} 人，其中今日 ${visitors.count { it.today }} 人")
        }
        val back = SparkPolicy.returnTargets(returnOn, visitors, roster.toSet(), bl, poked + targets + policy.sparkSkipToday(), self, dailyOn)
        val plan = (targets.map { it to "火花" } + back.map { it to "回踩" }).take(perRound.coerceAtLeast(1))
        if (plan.isEmpty()) return 0
        val names = sparks.associate { it.uin to it.nick } + policy.roster().toMap()
        var done = 0
        for (pair in plan) {
            val (uin, why) = pair
            if (dailyCap > 0 && !policy.tryAcquire("spark", dailyCap)) break
            pace()
            val r = PetResult.write { service.friend.poke(uin) }
            val repeat = (r as? PetResult.Rejected)?.code == Proto.CODE_POKE_REPEAT
            val name = names[uin] ?: visitors.firstOrNull { it.uin == uin }?.nick ?: uin
            when {
                r.ok || repeat -> {
                    policy.markPoked(uin); done++
                    daily?.increment(if (why == "回踩") "spark_return" else "spark_poke")
                }
                r is PetResult.Rejected -> { policy.markSparkSkip(uin); log("👣 $why 踩 $name 被拒（${r.code}），今天跳过此人") }
                else -> {
                    val wait = policy.noteSparkFailure(now)
                    log("👣 $why 踩 $name 未确认（${r.describe()}），本轮停下，${wait / 60_000} 分钟后再试")
                    break
                }
            }
        }
        if (done > 0) policy.clearSparkBackoff()
        val (x, y) = SparkPolicy.progress(roster, bl, policy.pokedToday(), self)
        log("👣 火花本轮踩 $done 人（今日已踩 $x / $y）")
        return done
    }

    /** 摸好友池缓存（petId 只有好友池/火花列表里有；6 小时刷新一次，不放大请求量）。 */
    private var patPoolCache: Pair<Long, List<FriendApi.Friend>>? = null

    private fun patPool(now: Long): List<FriendApi.Friend> {
        val (at, list) = patPoolCache ?: (0L to emptyList())
        if (list.isNotEmpty() && now - at < ROSTER_TTL_MS) return list
        val p = pool()
        if (p.isNotEmpty()) patPoolCache = now to p
        return p
    }

    /**
     * 摸好友一轮（0x96a6，好友心情 +2）：默认摸所有好友；**名单口径与火花一致**——黑名单模式排除名单内的人、
     * 白名单模式只摸名单内的人（[policy.sparkAllows]）。[sparkOnly]=只摸火花列表里的人（火花条目自带 petId，不翻好友池）。
     * 每人每天一次（[FriendPolicy.patToday] 持久，跨天自动失效）；每轮最多 [perRound] 人，写请求沿用好友写节流。
     * @return 本轮摸成的人数
     */
    fun patRound(sparkOnly: Boolean, sparks: List<SparkEntry>, perRound: Int, now: Long = System.currentTimeMillis()): Int {
        val self = selfUin.orEmpty()
        val done = policy.patToday()
        val targets = ArrayList<FriendApi.Friend>()
        if (sparkOnly) {
            sparks.forEach { e ->
                if (e.uin.isNotEmpty() && e.petId.isNotEmpty() && e.uin != self &&
                    policy.sparkAllows(e.uin) && e.uin !in done
                ) targets += FriendApi.Friend(e.uin, e.petId, 0, 0, e.nick)
            }
        } else {
            patPool(now).forEach { f ->
                if (f.uin != self && policy.sparkAllows(f.uin) && f.uin !in done) targets += f
            }
        }
        if (targets.isEmpty()) return 0
        val names = sparks.associate { it.uin to it.nick } + policy.roster().toMap()
        var n = 0
        for (f in targets.take(perRound.coerceAtLeast(1))) {
            pace()
            val r = PetResult.write { service.friend.pat(f.uin, f.petId, now) }
            val name = f.displayName.ifBlank { names[f.uin] ?: f.uin }
            when {
                r.ok -> {
                    policy.markPat(f.uin); n++
                    daily?.increment("pat")
                    log("🫳 摸 ${name}：好友心情 +${("%.1f".format(r.getOrNull() ?: 0.0)).trimEnd('0').trimEnd('.')}")
                }
                else -> log("🫳 摸 $name 未确认：${r.describe()}（今天先跳过）")
            }
        }
        return n
    }

    /** 现读火花列表并交主存（界面火花页的数据源）；失败返回 null（保留上次快照）。 */
    fun refreshSparks(): List<SparkEntry>? {
        val r = PetResult.read { service.friend.sparks() }
        val list = r.getOrNull() ?: run { log("🔥 读火花列表失败：${r.describe()}"); return null }
        if (list.isNotEmpty()) policy.rememberSparks(list)
        return list
    }
    /** 上次读到的火花列表（本地副本）。 */
    fun cachedSparks(): List<SparkEntry> = policy.sparks()

    fun sparkBlacklist(): Set<String> = policy.sparkBlacklist()
    fun sparkAllows(uin: String): Boolean = policy.sparkAllows(uin)
    fun sparkRosterCached(): List<String> = policy.roster().map { it.first }
    fun pokedToday(): Set<String> = policy.pokedToday()
    fun selfUinOrEmpty(): String = selfUin.orEmpty()

    /**
     * 好友写请求的共用节流器（火花踩、福袋共用）：任意两次好友写之间至少隔 1.5~4s 随机，跨轮也算
     *（风控靠节奏，不靠封顶）。
     */
    private fun pace() {
        val gap = Random.nextLong(1_500, 4_000)
        val wait = lastFriendWriteAt + gap - System.currentTimeMillis()
        if (wait > 0) sleepRandom(wait.toInt(), wait.toInt() + 1)
        lastFriendWriteAt = System.currentTimeMillis()
    }

    private fun sleepRandom(min: Int, max: Int) = try {
        Thread.sleep(Random.nextLong(min.toLong(), max.toLong()))
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
