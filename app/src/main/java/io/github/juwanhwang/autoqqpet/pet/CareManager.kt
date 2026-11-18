package io.github.juwanhwang.autoqqpet.pet

import io.github.juwanhwang.autoqqpet.net.Proto

/**
 * 照顾 Ban state 的冷却策略（纯逻辑，可单测）：撞一次 Ban 先冷却 [BASE_MS]，冷却到期重探一次；
 * 重探再撞 Ban 才延长（翻倍，封顶 [MAX_MS]）。起步 15 分钟参考仓内短冒险退避梯（1→2→4→8→16→30 分钟）的中段：
 * 够长，不会在风控窗口里反复撞；够短，体力还不至于在冷却里耗穿。
 * 旧口径（2026-10-10）是撞一次 Ban 直接锁一整天——2026-10-19 公开仓 #15 实锤误伤：Ban 只出现一次、
 * 35 秒后洗澡就成功了，喂食却被锁到体力耗尽、全线死局。
 */
internal object CareBan {
    const val BASE_MS = 15 * 60_000L
    const val MAX_MS = 6 * 60 * 60_000L

    /** 服务端“封禁状态禁止写请求”的关键字（这种拒绝多撞几次也不会好）。 */
    private val KEYWORDS = listOf("ban state", "forbit write", "forbid write")

    fun isBanText(why: String): Boolean = KEYWORDS.any { why.contains(it, ignoreCase = true) }

    /** 第 [hits] 次撞 Ban 的冷却时长（hits 从 1 起）：15→30→60→120→240→360 分钟封顶。 */
    fun cooldownMs(hits: Int): Long {
        var ms = BASE_MS
        repeat((hits - 1).coerceIn(0, 10)) { ms = (ms * 2).coerceAtMost(MAX_MS) }
        return ms.coerceAtMost(MAX_MS)
    }
}

/**
 * Ban 冷却状态（存 DailyStore：到期时刻走 prefs 长整型，命中次数走 counts 的 `<key>-ban`，都随跨天清零）。
 * 与语义性连拒计数（[CareManager.FAIL_LIMIT]）分开记：冷却只管“临时禁止写请求”，到期自动放行重探。
 */
internal class CareBanState(private val daily: DailyStore?) {
    fun untilMs(key: String): Long = daily?.banUntilMs(key) ?: 0L
    fun cooling(key: String, nowMs: Long): Boolean = untilMs(key) > nowMs
    fun remainingMs(key: String, nowMs: Long): Long = (untilMs(key) - nowMs).coerceAtLeast(0L)

    /** 记一次撞 Ban：命中次数 +1，按 [CareBan.cooldownMs] 设到期时刻；返回本次冷却毫秒。 */
    fun noteBan(key: String, nowMs: Long): Long {
        val d = daily ?: return CareBan.BASE_MS
        val hits = d.count("$key-ban") + 1
        d.setCount("$key-ban", hits)
        val ms = CareBan.cooldownMs(hits)
        d.setBanUntilMs(key, nowMs + ms)
        return ms
    }

    fun clear(key: String) {
        daily?.setBanUntilMs(key, 0L)
        daily?.setCount("$key-ban", 0)
    }
}

/**
 * 照顾的**唯一实现**（v1 里 AutoRunner 与 CareManager 各有一份喂/洗逻辑，已合并到这里）。
 *
 * 写操作幂等：喂食/洗澡以“重读体力/清洁是否上涨”为成功依据，不信回包（回包常为空）。
 * 风控：同一类语义性连拒 [FAIL_LIMIT] 次当天跳过；`Ban state forbit write req` 是临时禁止写请求，
 * 不锁一整天——进 [CareBan] 冷却，到期重探，再撞才延长（#15）。
 */
class CareManager(
    private val service: QQPetService,
    private val daily: DailyStore?,
    private val autoBuy: Boolean = true,
    private val log: (String) -> Unit = {},
    private val alert: (String) -> Unit = {},
    /** 香皂单价（与求解器同源，来自设置 soap_price）：买洗护的回包只有成功码、没有金额，记账按单价×数量。 */
    private val soapPrice: Int = 2,
) {

    companion object {
        const val FAIL_LIMIT = 3
        const val KEY_FEED_FAIL = "feed-fail"
        const val KEY_BATH_FAIL = "bath-fail"
        private const val RESTOCK = 10
        private const val SETTLE_MS = 700L
        private const val MAX_ACTIONS = 30
    }

    private val ban = CareBanState(daily)

    /** 被拦下 = 语义性连拒满额（当天跳过），或正处在 Ban 冷却里（到期自动放行）。 */
    fun feedBlocked(): Boolean = (daily?.count(KEY_FEED_FAIL) ?: 0) >= FAIL_LIMIT || ban.cooling(KEY_FEED_FAIL, System.currentTimeMillis())
    fun bathBlocked(): Boolean = (daily?.count(KEY_BATH_FAIL) ?: 0) >= FAIL_LIMIT || ban.cooling(KEY_BATH_FAIL, System.currentTimeMillis())

    /** 喂食正处在 Ban 冷却里（runner 据此决定要不要停派耗体力的新活）。 */
    fun feedBanCooling(): Boolean = ban.cooling(KEY_FEED_FAIL, System.currentTimeMillis())

    /** 喂食 Ban 冷却还剩多少毫秒（0 = 没在冷却）。 */
    fun feedBanRemainingMs(): Long = ban.remainingMs(KEY_FEED_FAIL, System.currentTimeMillis())

    /** 清连拒计数（风控解除后想立刻重试：`care op=reset`）。 */
    fun reset() {
        daily?.setCount(KEY_FEED_FAIL, 0); daily?.setCount(KEY_BATH_FAIL, 0)
        daily?.setCount("$KEY_FEED_FAIL-announced", 0); daily?.setCount("$KEY_BATH_FAIL-announced", 0)
        ban.clear(KEY_FEED_FAIL); ban.clear(KEY_BATH_FAIL)
    }

    /** 喂一颗饼干（缺货先补），以体力上涨为准。 */
    fun feed(fromHunger: Double): Boolean {
        val stock = PetResult.read { service.inventory.biscuits() }.getOrNull() ?: 0L
        if (stock <= 0L && autoBuy) {
            log("🍪 饼干用完，自动补货 $RESTOCK 个…")
            val buy = PetResult.write { service.inventory.buyFood(RESTOCK) }
            log("  买饼干 → " + buy.describe())
            // 买货回包带实付金额（cost 字段）：记成模块自家支出，免得今日基线把这笔算成用户手动消费
            buy.getOrNull()?.let { text ->
                Regex("""cost=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { daily?.noteModuleGoldSpend(it) }
            }
        }
        val r = PetResult.write { service.inventory.feed(Proto.BISCUIT_FOOD_ID) }
        pause()
        val now = service.profile.values(withGold = false).hunger
        val ok = now > fromHunger
        log(if (ok) "🍖 喂食 ${fromHunger.toInt()}→${now.toInt()}" else "🍖 喂食无效(${fromHunger.toInt()}→${now.toInt()})：${r.describe()}")
        if (ok) { daily?.setCount(KEY_FEED_FAIL, 0); ban.clear(KEY_FEED_FAIL) } else noteFail(KEY_FEED_FAIL, "喂食", r.describe())
        return ok
    }

    /** 用一块香皂（缺货先补），以清洁上涨为准。没香皂又买不到 → 返回 true（不拿它卡住循环）。 */
    fun bath(fromClean: Double): Boolean {
        var soap = PetResult.read { service.inventory.soap() }.getOrNull() ?: 0L
        if (soap <= 0L && autoBuy) {
            log("🧼 香皂用完，自动购买 $RESTOCK 个…")
            val buy = PetResult.write { service.inventory.buyBathItem(Proto.SOAP_ITEM_ID, RESTOCK) }
            log("  买香皂 → " + buy.describe())
            // 买洗护回包只有成功码（result=1）没有金额：确认买成后按单价×数量记模块自家支出
            val code = buy.getOrNull()?.let { Regex("""result=(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            if (code == 1L) daily?.noteModuleGoldSpend(RESTOCK * soapPrice)
            soap = PetResult.read { service.inventory.soap() }.getOrNull() ?: 0L
        }
        if (soap <= 0L) { log("🛁 无香皂(购买未成功)，跳过洗澡"); return true }
        val r = PetResult.write { service.inventory.bathRaw(Proto.SOAP_ITEM_ID, 1) }
        // 写包回包首数 = 服务器新值（交接 §9.7④：洗澡回 f1=90 时清洁就是 90）→ 有值就不再复读
        val replied = r.getOrNull()?.let { io.github.juwanhwang.autoqqpet.pet.parse.Parsers.bathClean(it) }
        val now = replied?.toDouble() ?: run { pause(); service.profile.values(withGold = false).clean }
        val ok = now > fromClean
        log(if (ok) "🛁 洗澡 ${fromClean.toInt()}→${now.toInt()}" else "🛁 洗澡无效(${fromClean.toInt()}→${now.toInt()})：${r.describe()}")
        if (ok) { daily?.setCount(KEY_BATH_FAIL, 0); ban.clear(KEY_BATH_FAIL) } else noteFail(KEY_BATH_FAIL, "洗澡", r.describe())
        return ok
    }

    /** 手动入口：反复喂/洗直到达到目标（单轮最多 [MAX_ACTIONS] 次）。 */
    fun careUntil(hungerTarget: Double, cleanTarget: Double): List<String> {
        val done = ArrayList<String>()
        var guard = 0
        var v = service.profile.values(withGold = false)
        while (v.hunger < hungerTarget && guard++ < MAX_ACTIONS && !feedBlocked()) {
            if (!feed(v.hunger)) break
            v = service.profile.values(withGold = false); done += "喂→${v.hunger.toInt()}"
        }
        guard = 0
        while (v.clean < cleanTarget && guard++ < MAX_ACTIONS && !bathBlocked()) {
            if (!bath(v.clean)) break
            val after = service.profile.values(withGold = false)
            if (after.clean <= v.clean) break
            v = after; done += "洗→${v.clean.toInt()}"
        }
        log("📋 照顾结束: 心情=${v.feel} 体力=${v.hunger} 清洁=${v.clean}")
        return done
    }

    private fun noteFail(key: String, cn: String, why: String) {
        val d = daily ?: return
        if (CareBan.isBanText(why)) {
            // 临时禁止写请求：不进语义连拒计数、不锁一整天，进冷却，到期自动重探（#15）
            val ms = ban.noteBan(key, System.currentTimeMillis())
            val min = (ms + 59_999L) / 60_000L
            log("🚫 ${cn}被服务端临时禁止写请求（${why.take(60)}），先冷却 $min 分钟，冷却结束会自动再试；再撞上会延长冷却")
            alert("${cn}被服务端临时禁止写请求，冷却 $min 分钟，到期自动重试。")
            return
        }
        val n = d.count(key) + 1
        d.setCount(key, n)
        if (n < FAIL_LIMIT) {
            log("⚠️ ${cn}第 $n 次未生效（满 $FAIL_LIMIT 次当天跳过）：${why.take(90)}")
            return
        }
        if (d.count("$key-announced") == 0) {
            d.increment("$key-announced")
            val text = "${cn}被服务端拒绝（${why.take(60)}），今天不再重试；已绕过照顾，继续学工/冒险。"
            log("🚫 $text")
            alert(text)
        }
    }

    private fun pause() = try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
}
