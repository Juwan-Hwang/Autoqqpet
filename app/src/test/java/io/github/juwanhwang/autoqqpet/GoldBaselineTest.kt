package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.pet.GoldBaseline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 今日金币基线的非模块支出校正（2026-10-20）：用户在官方界面手动花钱会把余额拉低，
 * 基线不跟着降，求解器（工分/属性优先）就把这笔消费当成今天的亏损、计划被带偏去
 * 赚钱。校正口径：余额跌幅先用模块自家支出解释，解释不了的差额等额下调基线；
 * 余额不降则什么都不动（自家支出累计由 DailyStore 在每次观测后清零，不在本函数里）。
 */
class GoldBaselineTest {

    @Test fun noChangeKeepsBaseline() {
        val r = GoldBaseline.adjusted(start = 1000, last = 500, selfSpend = 0, observed = 500)
        assertEquals(1000, r.newStart)
        assertEquals(0, r.unexplained)
    }

    @Test fun moduleSpendFullyExplainsDrop() {
        // 跌的 50 全是模块自己买补给花的：基线不动
        val r = GoldBaseline.adjusted(start = 1000, last = 500, selfSpend = 50, observed = 450)
        assertEquals(1000, r.newStart)
        assertEquals(0, r.unexplained)
    }

    @Test fun manualSpendLowersBaselineBySameAmount() {
        // 实测场景：基线 5000，用户手动花掉 4000（余额 4600 → 600），模块自己没花
        val r = GoldBaseline.adjusted(start = 5000, last = 4600, selfSpend = 0, observed = 600)
        assertEquals(4000, r.unexplained)
        assertEquals(1000, r.newStart)
    }

    @Test fun mixedSpendOnlyUnexplainedPartLowers() {
        // 跌 4000 里 100 是模块买补给，只有剩下的 3900 算非模块支出
        val r = GoldBaseline.adjusted(start = 5000, last = 4600, selfSpend = 100, observed = 600)
        assertEquals(3900, r.unexplained)
        assertEquals(1100, r.newStart)
    }

    @Test fun manualSpendBeyondBaselineFloorsAtZero() {
        // 非模块支出比基线还大：基线最多降到 0，不出负数
        val r = GoldBaseline.adjusted(start = 100, last = 5000, selfSpend = 0, observed = 0)
        assertEquals(5000, r.unexplained)
        assertEquals(0, r.newStart)
    }

    @Test fun balanceRiseLeavesBaselineAlone() {
        // 余额上涨（赚钱或手动收入）不抬基线；自家支出累计的清零由 DailyStore 在观测后做
        val r = GoldBaseline.adjusted(start = 1000, last = 500, selfSpend = 30, observed = 800)
        assertEquals(1000, r.newStart)
        assertEquals(0, r.unexplained)
    }

    @Test fun selfSpendLargerThanDropDoesNotRaiseBaseline() {
        // 自家支出累计比实际跌幅大（期间还有收入抵掉一部分）：只解释到跌幅为止
        val r = GoldBaseline.adjusted(start = 1000, last = 500, selfSpend = 300, observed = 450)
        assertEquals(1000, r.newStart)
        assertEquals(0, r.unexplained)
    }
}
