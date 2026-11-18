package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.CareBan
import io.github.juwanhwang.autoqqpet.pet.CareBanState
import io.github.juwanhwang.autoqqpet.pet.CareManager
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #15（公开仓）：喂食撞一次 `Ban state forbit write req` 就被锁一整天，35 秒后洗澡明明成功了，
 * 喂食却再没重试，体力被冒险耗到 0 后全线死局。现在的口径：Ban 只进冷却（15 分钟起、翻倍封顶），
 * 冷却到期自动重探，再撞才延长；语义性连拒的当天跳过口径不变。
 */
@RunWith(RobolectricTestRunner::class)
class CareBanTest {

    @Test fun banTextRecognized() {
        assertTrue(CareBan.isBanText("已发出但未确认 QQPetBridge: transport code=-1 Ban state forbit write req"))
        assertTrue(CareBan.isBanText("BAN STATE"))
        assertFalse(CareBan.isBanText("服务器说：你的宠物体力不足，请回家补充体力"))
        assertFalse(CareBan.isBanText("回调超时"))
    }

    @Test fun cooldownDoublesAndCaps() {
        val min = 60_000L
        assertEquals(15 * min, CareBan.cooldownMs(1))
        assertEquals(30 * min, CareBan.cooldownMs(2))
        assertEquals(60 * min, CareBan.cooldownMs(3))
        assertEquals(120 * min, CareBan.cooldownMs(4))
        assertEquals(240 * min, CareBan.cooldownMs(5))
        assertEquals(CareBan.MAX_MS, CareBan.cooldownMs(6))
        assertEquals(CareBan.MAX_MS, CareBan.cooldownMs(30))
    }

    @Test fun cooldownExpiresThenRebanExtends() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit().clear().commit()
        val daily = DailyStore(ctx)
        val state = CareBanState(daily)
        val key = CareManager.KEY_FEED_FAIL
        val t0 = 1_760_000_000_000L

        assertFalse(state.cooling(key, t0))
        // 第一次撞 Ban：冷却 15 分钟
        assertEquals(CareBan.BASE_MS, state.noteBan(key, t0))
        assertTrue(state.cooling(key, t0 + 1))
        assertTrue(state.cooling(key, t0 + CareBan.BASE_MS - 1))
        // 到期：放行重探
        assertFalse(state.cooling(key, t0 + CareBan.BASE_MS))
        assertEquals(0L, state.remainingMs(key, t0 + CareBan.BASE_MS))
        // 重探再撞 Ban：冷却翻倍到 30 分钟
        val t1 = t0 + CareBan.BASE_MS
        assertEquals(CareBan.BASE_MS * 2, state.noteBan(key, t1))
        assertTrue(state.cooling(key, t1 + CareBan.BASE_MS + 1))
        assertFalse(state.cooling(key, t1 + CareBan.BASE_MS * 2))
        // 清掉后立刻可重试（care op=reset 与成功路径走这里）
        state.clear(key)
        assertFalse(state.cooling(key, t1))
    }
}
