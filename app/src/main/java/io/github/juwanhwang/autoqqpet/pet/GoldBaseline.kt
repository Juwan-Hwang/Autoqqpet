package io.github.juwanhwang.autoqqpet.pet

/**
 * 今日金币基线的非模块支出校正（纯逻辑，可测）。
 *
 * 求解器的金币基线取今日起始金币（DailyStore 的 `gold.start`）：工分/属性优先时，
 * 计划期末金币达不到基线，排名就退化成先比金币（回血）。这条基线本意是「别让
 * 模块自己的经营把钱造没了」，但用户在官方界面手动花钱也会把余额拉低——基线
 * 不跟着降，求解器就会把用户的消费当成今天的亏损，计划被带偏去赚钱
 * （2026-10-20 实测：用户手动花掉 4000+ 后，工分优先的第一份计划从
 * 「工×23、冒险×0」变成「工×1、冒险×145」）。
 *
 * 口径：每次观测余额时，先用「自上次观测以来模块自己的支出累计」解释跌幅，
 * 解释不了的差额视为非模块支出，把基线等额下调（最低到 0）。余额上涨不处理：
 * 不抬基线，模块只是更有余量；手动收入同理不处理。
 */
internal object GoldBaseline {

    /** 校正结果：[newStart] 校正后的基线；[unexplained] 本次判定的非模块支出额（0 = 没有）。 */
    data class Result(val newStart: Int, val unexplained: Int)

    /**
     * 由基线 [start]、上次观测余额 [last]、模块自家支出累计 [selfSpend] 与
     * 本次观测余额 [observed] 算校正结果。
     */
    fun adjusted(start: Int, last: Int, selfSpend: Int, observed: Int): Result {
        val drop = last - observed
        if (drop <= 0) return Result(start, 0)
        val unexplained = drop - minOf(selfSpend.coerceAtLeast(0), drop)
        if (unexplained <= 0) return Result(start, 0)
        return Result((start - unexplained).coerceAtLeast(0), unexplained)
    }
}
