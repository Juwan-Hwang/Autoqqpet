package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.pet.QQPetBridge
import io.github.juwanhwang.autoqqpet.pet.service.PetIdRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * #14（公开仓）：2.0.1 用户日志里故事类写命令从某刻起持续被 135075「请求petID不匹配」拒绝——
 * 服务端换了 petId，模块缓存的旧值一直撞墙。自愈策略：刷新 petId 后只重试一次；
 * 刷新后仍被拒、或新旧 petId 相同（不是缓存过期）→ 原样抛错，不循环。
 */
class PetIdRefreshTest {

    private fun mismatch() = QQPetBridge.ServerError(Proto.CODE_PET_ID_MISMATCH, "请求petID不匹配")

    @Test fun firstMismatchRefreshesAndRetriesOnceWithNewId() {
        var petId = "old-id"
        var refreshCalls = 0
        val sentWith = mutableListOf<String>()
        val out = PetIdRefresh.run(
            send = {
                sentWith += petId
                if (sentWith.size == 1) throw mismatch()
                "ok"
            },
            currentPetId = { petId },
            refreshPetId = { refreshCalls++; petId = "new-id"; petId },
        )
        assertEquals("ok", out)
        assertEquals(1, refreshCalls)
        // 两次发送用的分别是旧、新 petId：重试不是原样重发
        assertEquals(listOf("old-id", "new-id"), sentWith)
    }

    @Test fun secondMismatchPropagatesWithoutLooping() {
        var petId = "old-id"
        var sends = 0
        val e = assertThrows(QQPetBridge.ServerError::class.java) {
            PetIdRefresh.run(
                send = { sends++; throw mismatch() },
                currentPetId = { petId },
                refreshPetId = { petId = "new-id"; petId },
            )
        }
        assertEquals(Proto.CODE_PET_ID_MISMATCH, e.code)
        assertEquals("只重试一次", 2, sends)
    }

    @Test fun sameIdAfterRefreshDoesNotRetry() {
        var sends = 0
        assertThrows(QQPetBridge.ServerError::class.java) {
            PetIdRefresh.run(
                send = { sends++; throw mismatch() },
                currentPetId = { "same-id" },
                refreshPetId = { "same-id" },
            )
        }
        assertEquals("新旧相同 = 不是缓存过期，不重试", 1, sends)
    }

    @Test fun failedRefreshDoesNotRetry() {
        var sends = 0
        assertThrows(QQPetBridge.ServerError::class.java) {
            PetIdRefresh.run(
                send = { sends++; throw mismatch() },
                currentPetId = { "old-id" },
                refreshPetId = { throw QQPetBridge.NotReady("读资料失败") },
            )
        }
        assertEquals(1, sends)
    }

    @Test fun otherErrorsPassThroughUntouched() {
        var sends = 0
        val e = assertThrows(QQPetBridge.ServerError::class.java) {
            PetIdRefresh.run(
                send = { sends++; throw QQPetBridge.ServerError(135004, "结算条件不满足") },
                currentPetId = { "old-id" },
                refreshPetId = { throw AssertionError("非 135075 不许刷新") },
            )
        }
        assertEquals(135004, e.code)
        assertEquals(1, sends)
    }

    @Test fun successOnFirstTryNeverRefreshes() {
        val out = PetIdRefresh.run(
            send = { "ok" },
            currentPetId = { "id" },
            refreshPetId = { throw AssertionError("成功路径不许刷新") },
        )
        assertEquals("ok", out)
    }
}
