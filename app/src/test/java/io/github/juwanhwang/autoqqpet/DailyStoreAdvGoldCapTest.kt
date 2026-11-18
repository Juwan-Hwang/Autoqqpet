package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.Day
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 冒险金币到顶标记（2026-10-17）：置位后当天可读，跨天必须自动清——不清的话第二天一整天不派冒险。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreAdvGoldCapTest {

    @Test fun flagRoundTripAndClearsNextDay() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val s = DailyStore(ctx)
        assertFalse(s.advGoldCapped())
        s.noteAdvGoldCapped()
        assertTrue(s.advGoldCapped())
        // 把存档日期拨回昨天，下一次读触发跨天清理，标记与当日计数都应消失
        prefs.edit().putInt("day", Day.today() - 1).commit()
        assertFalse(s.advGoldCapped())
    }
}
