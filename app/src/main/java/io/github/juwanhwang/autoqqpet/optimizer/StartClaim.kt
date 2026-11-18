package io.github.juwanhwang.autoqqpet.optimizer

/**
 * 开工认领的判定（纯逻辑，时间由调用方传入，单测用）。
 *
 * 背景（2026-10-19 真机）：开工写请求回包丢失、写后核对又没读到任务时，开工登记会落空；
 * 下一轮状态读到这份正在跑的任务却不认识（pending 还指着上一份、started 台账里也没有），
 * 就误判成“不是 Autoqqpet 开的”并干等到它结束。为此 [AutoRunner.startMain] 在发开工指令
 * 之前先在 DailyStore 记一条认领（种类/目录名/发出时刻，不依赖回包），登记成功或服务器
 * 明确拒绝时立刻清掉；只有“未确认”会把认领留下来，等状态读到任务时靠这里的判定领回。
 */
object StartClaim {

    /**
     * 认领有效窗口：10 分钟。
     * 下限侧：登记正常时本轮内就落账（回包直达或 verifyStart 两次核对，十几秒）；登记丢了，
     * 下一轮 tick 读状态最早几十秒、最迟不超过“未确认”退避的上限（10 分钟）就会再读到它
     * ——窗口与这个最长复查间隔对齐，保证领回时认领还没过期。
     * 上限侧：再长没有意义。超过 10 分钟还没被任何一轮状态读领走，这份开工多半没生效；
     * 窗口拉得越长，越容易把用户在这段时间里手动开的同类任务误认成自家的。
     */
    const val WINDOW_MS = 10 * 60_000L

    /**
     * 在外这份任务是不是认领的那份。kind 用 DailyStore 的 tag 口径（study/work/adventure）：
     *  - 认领必须存在（[claimAtMs] > 0、种类非空）且没过期（时钟倒退也不认）；
     *  - 种类必须一致：[seenKind] 认不出来（空）绝不领，种类不同绝不领；
     *  - 名字两边都有时必须一致；只有一边有名字时不以名字拦（状态读阶段通常拿不到名字，
     *    调用方传空即可，只按种类判）。
     */
    fun matches(claimKind: String, claimName: String, claimAtMs: Long,
                seenKind: String, seenName: String, nowMs: Long): Boolean {
        if (claimAtMs <= 0L || claimKind.isEmpty()) return false
        val age = nowMs - claimAtMs
        if (age < 0L || age > WINDOW_MS) return false
        if (seenKind.isEmpty() || seenKind != claimKind) return false
        if (claimName.isNotEmpty() && seenName.isNotEmpty() && claimName != seenName) return false
        return true
    }

    /** 认领是否已过期（调用方据此顺手清掉，别让死认领一直躺着）。 */
    fun expired(claimAtMs: Long, nowMs: Long): Boolean =
        claimAtMs > 0L && nowMs - claimAtMs > WINDOW_MS
}
