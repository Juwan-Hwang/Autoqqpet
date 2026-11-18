package io.github.juwanhwang.autoqqpet.pet

/**
 * “每日上限”类设置的统一口径（2026-10-15）：**-1 = 不限**，N ≥ 1 = 每天最多 N。
 * 用户原话：有风控考虑也不应剥夺用户无限的需求 → 风控靠请求节奏（每轮上限、间隔、抖动），不靠封顶。
 * 例外：`adv_max` 沿用 0 = 不限（用户指定存储兼容），由 [allowsZeroUnlimited] 判断。
 */
object Caps {
    const val UNLIMITED = -1
    const val MAX = 1_000_000L

    fun allows(usedToday: Int, cap: Int): Boolean = cap < 0 || usedToday < cap
    fun allowsZeroUnlimited(usedToday: Int, cap: Int): Boolean = cap <= 0 || usedToday < cap
    fun label(cap: Int, unit: String = "次"): String = if (cap < 0) "不限" else "$cap $unit"

    /**
     * 迁移旧值（幂等）：旧版上限 0 的真实含义是“一次都不做”（tryAcquire 里 `0 >= 0` 恒拒）→ 改成关掉对应开关 + 上限回默认；
     * [legacyDefault] 非空时，等于旧默认值的改成新默认（只用于默认变成“不限”的键）。
     * @return (新上限, 是否要关开关)；无需改动返回 null。
     */
    fun migrate(old: Int?, newDefault: Int, legacyDefault: Int? = null): Pair<Int, Boolean>? = when {
        old == null -> null
        old == 0 -> newDefault to true
        legacyDefault != null && old == legacyDefault && newDefault != legacyDefault -> newDefault to false
        else -> null
    }
}
