package io.github.juwanhwang.autoqqpet.pet

/**
 * 火花（与某好友连续互踩天数）的取舍（纯函数，可单测）。
 *
 * 官方规则（交接 §9.9，用户提供 FAQ 口径）：连踩 3 天点亮；漏一天进“待重燃”（天数保留）；**连续 7 天不互踩才清除**。
 *
 * 2026-10-15 用户改的逻辑（替换“白名单每天踩”）：
 *  - **默认每天踩所有 QQ 宠物好友**（好友列表 0x985d 里的人，不含陌生人），只排除**黑名单**；
 *  - 黑名单 = 永不踩、不回踩、不提醒；
 *  - 回踩只管**不在好友列表里**、但今天踩了我的人（好友本来就天天踩），独立开关、默认关；
 *  - 好友可能成百上千：每轮只踩 N 人（[FriendManager.sparkRound] 限流/抖动/退避），按日去重、持久化。
 */
object SparkPolicy {

    /** 7 天清除；断到第 [REMIND_GAP] 天就提醒（还剩 ≤2 天）。 */
    const val CLEAR_DAYS = 7
    const val REMIND_GAP = 5

    /**
     * 今天还要踩谁（按优先级排好）：好友名册 − 黑名单 − 自己 − 今天已踩 − 今天踩失败跳过的 − 服务器说今日已互踩的。
     * 顺序：待重燃（离清零越近越先）→ 在燃（天数多的先，断了损失大）→ 其余按上次互动日（近的先；没记录的最后，保持名册顺序）。
     */
    fun dailyTargets(
        roster: Collection<String>, blacklist: Set<String>, pokedToday: Set<String>, selfUin: String,
        sparks: List<SparkEntry> = emptyList(), skipToday: Set<String> = emptySet(), lastPokedDay: Map<String, Int> = emptyMap(),
    ): List<String> {
        val byUin = sparks.associateBy { it.uin }
        val base = roster.asSequence().map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct()
            .filter { it != selfUin && it !in blacklist && it !in pokedToday && it !in skipToday && byUin[it]?.pokedToday != true }
            .toList()
        val order = base.withIndex().associate { it.value to it.index }
        return base.sortedWith(
            compareBy<String> { when (byUin[it]?.stage) { SparkStage.REKINDLE -> 0; SparkStage.LIT -> 1; else -> 2 } }
                .thenByDescending { u -> byUin[u]?.let { if (it.stage == SparkStage.REKINDLE) it.gapDays else it.days } ?: -1 }
                .thenByDescending { lastPokedDay[it] ?: Int.MIN_VALUE }
                .thenBy { order[it] ?: 0 },
        )
    }

    /**
     * 回踩对象：今天来踩过我的人 − 黑名单 − 自己 − 今天已踩。
     * [dailyOn]=每天自动踩好友开着 → 好友已经在每日计划里，回踩只管**非好友访客**（排除名册）；
     * 关着 → “谁踩了你就回踩”，好友也算进来（黑名单照样排除）。
     */
    fun returnTargets(
        enabled: Boolean, visitors: List<LikeVisitor>, roster: Set<String>, blacklist: Set<String>, pokedToday: Set<String>, selfUin: String,
        dailyOn: Boolean = true,
    ): List<String> = if (!enabled) emptyList() else
        visitors.filter { it.today }.map { it.uin }.distinct()
            .filter { it.isNotEmpty() && it != selfUin && (!dailyOn || it !in roster) && it !in blacklist && it !in pokedToday }

    /** 快清零提醒：处于待重燃、已断 ≥ [REMIND_GAP] 天（读不出断了几天时，待重燃就提醒），黑名单除外。 */
    fun atRisk(sparks: List<SparkEntry>, blacklist: Set<String>): List<SparkEntry> =
        sparks.filter { it.uin !in blacklist && it.stage == SparkStage.REKINDLE && (it.gapDays < 0 || it.gapDays >= REMIND_GAP) }

    /** 还剩几天被清除（读不出返回 -1）。 */
    fun daysLeft(e: SparkEntry): Int = if (e.gapDays < 0) -1 else (CLEAR_DAYS - e.gapDays).coerceAtLeast(0)

    /** 今日已踩 x / 应踩 y（y = 名册 − 黑名单 − 自己；x 只数名册里的人）。 */
    fun progress(roster: Collection<String>, blacklist: Set<String>, pokedToday: Set<String>, selfUin: String): Pair<Int, Int> {
        val target = roster.filter { it.isNotEmpty() && it != selfUin && it !in blacklist }.toSet()
        return target.count { it in pokedToday } to target.size
    }

    /** 名单里的人名，长了只写前 [max] 个 + “等 N 人”。 */
    fun names(nicks: List<String>, max: Int = 3): String =
        nicks.take(max).joinToString("、") + if (nicks.size > max) " 等 ${nicks.size} 人" else ""

    /**
     * 旧名单迁移（幂等）：旧三态“每天踩(spark_wl) / 不管 / 不踩(spark_bl)”→ 只剩黑名单。
     * 每天踩的白名单直接丢弃（现在默认就踩所有好友）；不踩的保留为黑名单；“不管”这一态取消。
     * @param old 好友存储的全部键值；@return 要写入的新值（null = 删除该键）；无需改动返回空表。
     */
    fun migrateLists(old: Map<String, Any?>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        if (old.containsKey(FriendPolicy.KEY_SPARK_WL)) out[FriendPolicy.KEY_SPARK_WL] = null
        val bl = (old[FriendPolicy.KEY_SPARK_BL] as? String).orEmpty()
        val clean = bl.split(",").map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct().joinToString(",")
        if (old.containsKey(FriendPolicy.KEY_SPARK_BL) && clean != bl) out[FriendPolicy.KEY_SPARK_BL] = clean
        if ((old[FriendPolicy.KEY_SPARK_SCHEMA] as? String) != FriendPolicy.SPARK_SCHEMA.toString()) out[FriendPolicy.KEY_SPARK_SCHEMA] = FriendPolicy.SPARK_SCHEMA.toString()
        return out
    }
}
