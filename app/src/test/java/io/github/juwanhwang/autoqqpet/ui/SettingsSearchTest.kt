package io.github.juwanhwang.autoqqpet.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.store.Store
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.screens.SettingsScreen
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

/** 设置搜索：按标题、按登记说明、按二层菜单的选项都能搜到；搜不到有空状态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsSearchTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences(Store.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        EulaTerms.markAgreed(ctx)
        UiPrefs.init(ctx)
        val live = LiveState(ctx)
        compose.setContent {
            AevumTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SettingsScreen(live, onBack = {}, onOpen = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun type(q: String) {
        compose.onNodeWithContentDescription("搜索设置").performTextInput(q)
        compose.waitForIdle()
    }

    @Test fun searchByTitle() {
        type("福袋")
        compose.onNodeWithText("自动领福袋").assertExists()
    }

    @Test fun searchByDescription() {
        // “闹钟图标”只出现在登记说明里，标题里没有
        type("闹钟图标")
        compose.onNodeWithText("强力唤醒").assertExists()
    }

    @Test fun searchBySheetOption() {
        // “立刻召回”是「什么时候召回」二层菜单里的选项，标题与说明里都没有
        type("立刻召回")
        compose.onNodeWithText("什么时候召回").assertExists()
    }

    @Test fun searchByPinyinInitials() {
        // 输拼音首字母 hh 要能找到「火花」，且不能带出福袋上限（它的语料首字母里没有 hh）
        // 「火花」会同时出现在分组标题与入口行上（两个节点），所以数节点而不是单点断言
        type("hh")
        assertTrue(compose.onAllNodesWithText("火花").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("每天最多领福袋").assertDoesNotExist()
    }

    @Test fun searchSparkDoesNotBringCoinbagCap() {
        // 回归：福袋上限的登记说明曾写到它与火花共用节奏，搜「火花」被误带出来
        type("火花")
        assertTrue(compose.onAllNodesWithText("火花").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("每天最多领福袋").assertDoesNotExist()
    }

    @Test fun searchNoResult() {
        type("不存在的词xyz")
        compose.onNodeWithText("没有找到相关设置").assertExists()
    }
}
