package io.github.juwanhwang.autoqqpet.pet

import io.github.juwanhwang.autoqqpet.log.PLog

/**
 * 雇佣好友的取舍（纯函数部分可单测）。
 *
 * 实测事实（2026-10-19 纠正）：
 *  - 雇佣不单独发包，`0x975e` 带 f4 即雇；**雇人打工本身不收费**——此前以为的 250 金币是 PK 请保镖
 *    的一次性费用，与雇人无关（2026-10-15 测到的 −250 就发生在 PK 新请保镖那一局，
 *    见 docs/game-economy-model.md §1.8 的订正注记）；同类参考实现雇人时也不计任何成本，互证。
 *  - 当天已建立的雇佣可直接复用（activeHire），不重复挑人。
 *  - 每日次数上限按雇佣开工的班次计：新建立与复用每开工一次各算一次，达到上限后复用也不再雇。
 *  - 收益模型（2026-10-31 抓包证实）：一个岗位一个本金（目录 f8），雇佣只是在本金上加一段
 *    0～上限的加成（目录 f12 按好友标明上限，结算实得落在区间内）——雇人恒不亏，所以雇不雇
 *    只看开关与次数，不看金币、不算回本；挑人按加成上限从高到低排、依次问可雇取第一个。
 *  - 对方能不能被雇看 `0x9875_1`（每日上限 20、已达上限文案）。
 */
object HirePolicy {

    data class Verdict(val hire: Boolean, val reason: String, val reuse: Boolean = false)

    /**
     * 雇不雇只看：开关、每日次数上限、有没有今天已建立的雇佣可复用。
     * 次数上限按雇佣开工班次计（[usedToday] 含新建立与复用的开工），所以上限检查排在复用之前：
     * 达到上限后连复用也不雇。雇人本身不花钱、加成最差为 0，雇了恒不亏——不看金币留底、更不算回本。
     * @param usedToday 今天已带雇佣开工的次数（新建立与复用都算）
     * @param reusable 今天已经建立过、还没取消的雇佣（额度内有就直接用，不重复挑人）
     */
    fun judge(
        enabled: Boolean, usedToday: Int, dailyCap: Int,
        reusable: Boolean = false,
    ): Verdict = when {
        !enabled -> Verdict(false, "雇佣开关关")
        !Caps.allows(usedToday, dailyCap) -> Verdict(false, "今日雇佣已 $usedToday/$dailyCap 次")
        reusable -> Verdict(true, "复用今天已建立的雇佣", reuse = true)
        else -> Verdict(true, "今日第 ${usedToday + 1}/${Caps.label(dailyCap)} 次雇佣")
    }

    /**
     * 按加成上限给候选人排队（纯函数，可单测）：上限高的在前；查不到上限的垫底；
     * 上限相同的保持原顺序（稳定排序）。
     */
    fun rankByBonus(uins: List<String>, capOf: (String) -> Int?): List<String> =
        uins.sortedByDescending { capOf(it) ?: -1 }

    /**
     * 雇佣指定名单过滤（纯函数，可单测）：名单为空 = 不限制、原样返回；
     * 非空只留名单内的人（保持原顺序，名单内仍按调用方原有口径排序）。
     */
    fun designatedOnly(uins: List<String>, designated: Set<String>): List<String> =
        if (designated.isEmpty()) uins else uins.filter { it in designated }

    /** 名单过滤（纯函数，可单测）：名单为空两种模式都不限；白名单模式只留名单内，黑名单模式去掉名单内。 */
    fun designatedFilter(uins: List<String>, designated: Set<String>, mode: String): List<String> =
        if (designated.isEmpty()) uins
        else if (mode == FriendPolicy.MODE_BL) uins.filter { it !in designated }
        else uins.filter { it in designated }

    /** 单人名单判定（纯函数，可单测）：这个人按当前名单口径还许不许雇——与 [designatedFilter] 同一套规则，供复用复检用。 */
    fun designatedAllows(uin: String, designated: Set<String>, mode: String): Boolean =
        designatedFilter(listOf(uin), designated, mode).isNotEmpty()

    /** 按顺序返回第一个可雇的 uin（最多看 [probe] 个）；没有则 null（纯函数，可单测）。 */
    fun firstHireableUin(candidates: List<String>, probe: Int, hireable: (String) -> Boolean): String? {
        for (u in candidates.take(probe)) if (hireable(u)) return u
        return null
    }

    /** 排名中的一位候选：[caps] = 他在目标职业各时长上的雇佣加成上限（求解器定价用）。 */
    data class HireCandidate(val uin: String, val petId: String, val caps: Map<Int, Int> = emptyMap())

    /**
     * 按排名返回**服务器说能雇**的候选（0x9875 无“已达上限/在忙”文案），最多收 [maxCollect] 个。
     * 雇佣名单按 [FriendPolicy] 的名单模式过滤：名单内的人都不在池里、或都不可雇，就返回空（日志写明原因）。
     *
     * 给了目标岗位（[targetCareer] > 0）时按加成挑人：先带每位候选的 petId 读一遍目标职业目录，
     * 按目标时长岗位的加成上限从高到低排队，再依次问可雇；读不到目录或目录里没这个岗位的人
     * 上限按未知垫底。没给目标岗位时按池子原顺序最多问 [probe] 个（手动命令路径）。
     * [exclude] 里的人直接跳过（派工已被拒过的，同一轮不再问）。
     */
    fun rankedHireable(
        service: QQPetService, policy: FriendPolicy?, selfUin: String, probe: Int = 5, log: (String) -> Unit = {},
        targetCareer: Long = 0, targetDurationMin: Int = 0, maxCollect: Int = Int.MAX_VALUE,
        exclude: Set<String> = emptySet(),
    ): List<HireCandidate> {
        val pool = PetResult.read { service.friend.pool() }.getOrNull().orEmpty()
            .filter { it.uin != selfUin && it.petId.isNotEmpty() && it.uin !in exclude && (policy?.isEligible(it.uin, selfUin) ?: true) }
        val designated = policy?.hireList().orEmpty()
        val candidates = designatedFilter(pool.map { it.uin }, designated, policy?.hireListMode() ?: FriendPolicy.MODE_WL)
        if (designated.isNotEmpty() && candidates.isEmpty()) {
            log("🤝 雇佣名单有 ${designated.size} 人，但当前都不在可雇好友池里，本轮不雇")
            return emptyList()
        }
        val byUin = pool.associateBy { it.uin }
        val capTables = HashMap<String, Map<Int, Int>>()
        val order: List<String>
        val walkLimit: Int
        if (targetCareer > 0) {
            val caps = HashMap<String, Int>()
            for (uin in candidates) {
                val f = byUin.getValue(uin)
                val options = PetResult.read { service.story.jobs(targetCareer, f.petId) }.getOrNull() ?: continue
                val parsed = options.map { SceneEconomics.parseJob(it, targetCareer) }
                val table = parsed.filter { it.hireBonusCap >= 0 }.associate { it.durationMin to it.hireBonusCap }
                if (table.isNotEmpty()) capTables[uin] = table
                parsed.firstOrNull { it.durationMin == targetDurationMin }?.hireBonusCap
                    ?.takeIf { it >= 0 }?.let { caps[uin] = it }
            }
            order = rankByBonus(candidates) { caps[it] }
            walkLimit = order.size
            val top = order.firstOrNull()?.let { caps[it] }
            if (top != null) log("🤝 按加成排队：最高上限 $top（${byUin.getValue(order.first()).displayName}），依次问可雇")
        } else {
            order = candidates
            walkLimit = probe
        }
        val out = ArrayList<HireCandidate>()
        for (uin in order.take(walkLimit)) {
            if (out.size >= maxCollect) break
            val f = byUin.getValue(uin)
            val st = PetResult.read { service.friend.status(f.uin, f.petId) }
            val s = st.getOrNull()
            when {
                s == null -> log("🤝 查好友 ${f.displayName} 可否雇佣失败：${st.describe()}")
                s.hireable -> { log("🤝 好友 ${f.displayName} 可雇（每日被雇上限 ${s.hireDailyCap}）"); out += HireCandidate(f.uin, f.petId, capTables[uin].orEmpty()) }
                else -> log("🤝 好友 ${f.displayName} 不可雇：${s.reason}")
            }
        }
        if (out.isEmpty()) {
            if (designated.isNotEmpty()) log("🤝 雇佣名单内的 ${candidates.size} 人都不可雇，本轮不雇")
            PLog.d("HirePolicy", "好友池 ${pool.size} 人里问了 $walkLimit 个都不可雇")
        }
        return out
    }

    /**
     * 从好友池里挑一个**服务器说能雇**的（[rankedHireable] 的第一位）。
     * 挑中后经 [onBonusCaps] 回传那位好友在该职业各时长上的上限表（求解器定价用）。
     * @return (uin, petId) 或 null
     */
    fun pickHireable(
        service: QQPetService, policy: FriendPolicy?, selfUin: String, probe: Int = 5, log: (String) -> Unit = {},
        targetCareer: Long = 0, targetDurationMin: Int = 0, onBonusCaps: (Map<Int, Int>) -> Unit = {},
    ): Pair<String, String>? {
        val first = rankedHireable(service, policy, selfUin, probe, log, targetCareer, targetDurationMin, maxCollect = 1)
            .firstOrNull() ?: return null
        if (first.caps.isNotEmpty()) onBonusCaps(first.caps)
        return first.uin to first.petId
    }
}
