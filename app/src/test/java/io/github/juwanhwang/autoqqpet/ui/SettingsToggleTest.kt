package io.github.juwanhwang.autoqqpet.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.data.Backend
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.screens.SettingsCard
import io.github.juwanhwang.autoqqpet.ui.settings.Group
import io.github.juwanhwang.autoqqpet.ui.settings.Toggle
import io.github.juwanhwang.autoqqpet.ui.theme.AevumTheme
import io.github.juwanhwang.autoqqpet.ui.theme.LocalReducedMotion
import io.github.juwanhwang.autoqqpet.ui.theme.UiPrefs
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1.1：设置开关点了要**当场**切换（旧版要退出重进才变）。
 * 根因是行里直接读 SharedPreferences（不可观察），父级 bump 重组时行入参未变被 strong skipping 跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsToggleTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "test_toggle_on"

    @Before fun setUp() {
        ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        EulaTerms.markAgreed(ctx)   // 非 local 开关要过协议闸
        UiPrefs.init(ctx)
    }

    private fun show(local: Boolean): LiveState {
        val live = LiveState(ctx)
        val row = Toggle(key, Group.PLAN, "测试开关", Glyphs.Moon, on = "开着", off = "关着",
            action = if (local) null else Backend.SETOPT, default = false, local = local)
        compose.setContent {
            AevumTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SettingsCard(live, listOf(row), note = {})
                }
            }
        }
        compose.waitForIdle()
        return live
    }

    @Test fun tapFlipsImmediately_setopt() {
        val live = show(local = false)
        compose.onNodeWithText("测试开关").assertIsOff()
        compose.onNodeWithText("测试开关").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("测试开关").assertIsOn()
        compose.onNodeWithText("开着").assertExists()
        assertTrue(live.backend.prefs.getBoolean(key, false))
        compose.onNodeWithText("测试开关").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("测试开关").assertIsOff()
    }

    @Test fun tapFlipsImmediately_local() {
        show(local = true)
        compose.onNodeWithText("测试开关").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("测试开关").assertIsOn()
    }

    /** QQ 侧回写（StoreReceiver → 界面 prefs）也要当帧反映，不用等心跳/重进。 */
    @Test fun externalWriteReflectsImmediately() {
        show(local = false)
        compose.onNodeWithText("测试开关").assertIsOff()
        compose.runOnUiThread {
            ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit().putBoolean(key, true).commit()
        }
        compose.waitForIdle()
        compose.onNodeWithText("测试开关").assertIsOn()
    }
}
