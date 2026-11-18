package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.AutoRunner
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.StoryOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 2026-10-19 真机：故事还没干完就被拉去结算，服务器以 135004「宠物结算条件不满足」明确拒绝，
 * 旧结算路径仍读明细误捞出「金币:1」写了两遍幻影结算记录。修复口径：
 * 只有服务器明确拒绝才算未结算成功、不许记账；空回包/未验证不算拒绝。
 * 实收对账汇总同一 storyId 只计第一条，台账里已有重复行也不重复汇总。
 */
@RunWith(RobolectricTestRunner::class)
class SettleRejectionTest {

    @Test fun onlyExplicitRejectionBlocksBookkeeping() {
        val rej = AutoRunner.settleRejection(PetResult.Rejected(135004, "宠物结算条件不满足"))
        assertNotNull(rej)
        assertEquals(135004, rej!!.code)
        // 回包为空/未确认、通信失败、成功、没发结算——都不算拒绝，仍走原有明细路径
        assertNull(AutoRunner.settleRejection(PetResult.Unverified("回包为空")))
        assertNull(AutoRunner.settleRejection(PetResult.Transport("超时")))
        assertNull(AutoRunner.settleRejection(PetResult.Ok(StoryOutcome())))
        assertNull(AutoRunner.settleRejection(null))
    }

    @Test fun duplicateStoryIdInLedgerSumsCountedOnce() {
        val phantom = "🧾 结算记录 id=6400_3a62173f kind=work name=\"\" gold=1 rewards=金币:1"
        val sums = DailyStore.ledgerRewardSums(
            listOf(
                phantom,
                phantom,   // 同一故事被拒后重试又写了一遍，只计第一条
                "🧾 结算记录 id=6400_aaaa kind=work name=\"商场临时商演\" gold=326 rewards=金币:326,工分 +:54",
                "🧾 结算记录 id=6400_bbbb kind=work name=\"商场临时商演\" gold=326 rewards=金币:326,工分 +:54",
            ),
        )
        assertEquals(1 + 326 + 326, sums["金币"])
        assertEquals(108, sums["工分"])
    }
}
