package io.github.juwanhwang.autoqqpet.optimizer

/**
 * 自动鼓励的次数与节奏（公开仓 issue #5：用户要“学习、打工自动鼓励（N）次”可自定义）。
 *
 * 现状（v2.1.1 及以前）：开工拿到 storyId 后自动鼓励恰好 1 次（`0x9c44`，仅学习/打工，
 * 冒险发了会被服务器拒 135011），成绩由默认档提到 A/A+。
 *
 * v2.1.2 起次数可设（`encourage_times`，默认 1 = 与旧行为一致，0 = 不鼓励，上限 10）：
 *  - 第 1 次仍在开工后立刻发（与旧版同一时机，真机已验证）；
 *  - 其余次数**不在开工时连发**：参考实现（Cooky）里鼓励按钮约每 12 秒才出现一次，
 *    连发大概率被服务器拒；改为任务进行中由 BUSY 复查按 [GAP_MS] 间隔补发；
 *  - 服务器明确拒绝（Rejected）就停：本趟不再补发（调用方把已发次数记满实现）；
 *  - 次数只对本模块开的学/工任务生效（开工时把目标次数记进 pending），手动/被雇的任务不补。
 *
 * 纯逻辑，时间由调用方传入（单测用）。
 */
object EncouragePlan {

    /** 两次鼓励之间的最小间隔：按钮约 12 秒出现一次（Cooky 参考），留余量取 20 秒。 */
    const val GAP_MS = 20_000L

    /** 还差几次（目标 ≤ 0 或已发 ≥ 目标都是 0）。 */
    fun left(target: Int, done: Int): Int = (target - done).coerceAtLeast(0)

    /** 现在能补下一次吗：次数没用完，且距上次已过 [GAP_MS]（lastAtMs ≤ 0 = 还没发过）。 */
    fun due(target: Int, done: Int, lastAtMs: Long, nowMs: Long): Boolean =
        left(target, done) > 0 && (lastAtMs <= 0L || nowMs - lastAtMs >= GAP_MS)

    /** 下一次可补发的时刻（ms）；次数已用完返回 [Long.MAX_VALUE]。 */
    fun nextAt(target: Int, done: Int, lastAtMs: Long): Long =
        if (left(target, done) <= 0) Long.MAX_VALUE else maxOf(lastAtMs + GAP_MS, 0L)
}
