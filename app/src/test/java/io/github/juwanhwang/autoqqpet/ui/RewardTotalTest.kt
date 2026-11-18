package io.github.juwanhwang.autoqqpet.ui

import io.github.juwanhwang.autoqqpet.ui.screens.attrTotal
import io.github.juwanhwang.autoqqpet.ui.screens.rewardTotal
import org.junit.Assert.assertEquals
import org.junit.Test

/** 收益口径汇总：先归一再精确匹配，存量脏名与变体名都能算进本体口径。 */
class RewardTotalTest {

    @Test fun dirtyStoredNameCountsIntoBase() {
        // 台账里可能残留带尾部运算符的脏键（「工分 +」是解析洗名上线前存的）
        val rewards = listOf("工分 +" to 10L, "工分" to 5L, "金币" to 100L)
        assertEquals(15L, rewardTotal(rewards, "工分"))
        assertEquals(100L, rewardTotal(rewards, "金币"))
    }

    @Test fun variantNamesCountIntoBase() {
        val rewards = listOf("基础魅力" to 3L, "奖励魅力" to 2L, "魅力" to 4L, "额外智力" to 7L)
        assertEquals(9L, rewardTotal(rewards, "魅力"))
        assertEquals(7L, rewardTotal(rewards, "智力"))
        assertEquals(0L, rewardTotal(rewards, "力量"))
    }

    @Test fun junkNamesNeverCounted() {
        // 疲劳文案这类伪条目洗名后不合规，任何口径都不许把它蒙进来
        val rewards = listOf("疲惫，收益减少" to 6L, "工分" to 1L)
        assertEquals(1L, rewardTotal(rewards, "工分"))
        assertEquals(0L, attrTotal(rewards, ""))
    }

    @Test fun blankTargetAttrFallsBackToAllThreeAttrs() {
        val rewards = listOf("力量" to 1L, "基础智力" to 2L, "魅力" to 3L, "工分" to 50L)
        assertEquals(6L, attrTotal(rewards, ""))
        assertEquals(3L, attrTotal(rewards, "魅力"))
    }
}
