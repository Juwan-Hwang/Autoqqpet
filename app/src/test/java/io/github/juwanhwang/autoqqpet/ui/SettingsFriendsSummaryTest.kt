package io.github.juwanhwang.autoqqpet.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 真机反馈（2026-10-16）：名单在面板里全部取消并保存后，设置页「互动名单」下面的说明
 * 还停在“只和勾选的 2 位好友”，要退出重进才变成“不限”。
 * 根因：LazyColumn 的 item 内容不随外层数据版本重组，行一直拿着首次组合时的副标题；
 * 修复是把数据版本编进 item 的 key（SettingsScreen），版本一变整卡重建。
 * 2.1.10 名单合并后本测试改走合并名单（friend_list + 白名单模式）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsFriendsSummaryTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences(Store.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        EulaTerms.markAgreed(ctx)
        UiPrefs.init(ctx)
        Store.prefs(ctx).edit().putString(
            Store.EXTRACT_FRIENDS,
            """{"friend_list":"111,222","friend_list_mode":"wl","roster":"111⋄小明~222⋄小红"}""",
        ).commit()
    }

    private fun content(live: LiveState) {
        compose.setContent {
            AevumTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SettingsScreen(live, onBack = {}, onOpen = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("互动名单"))
        compose.onNodeWithText("白名单 · 只和勾选的 2 位好友互动").assertExists()
    }

    @Test fun whitelistSummaryRefreshesAfterSave() {
        val live = LiveState(ctx)
        live.start()   // 与 MainActivity.onStart 一致：监听主存变化 → dataVersion
        content(live)
        compose.onNodeWithText("互动名单").performClick()
        compose.waitForIdle()
        // 面板里把两人全部取消勾选并保存（面板加了模式切换行后变高，测试小屏里列表一次只容一行，第二行先滚出来再点）
        compose.onNodeWithText("小明").performClick()
        val scrollNodes = compose.onAllNodes(hasScrollAction())
        scrollNodes[scrollNodes.fetchSemanticsNodes().size - 1].performScrollToNode(hasText("小红"))
        compose.onNodeWithText("小红").performClick()
        compose.waitForIdle()
        // 保存按钮在测试小屏里被面板高度顶出窗口、触摸注入会落到遮罩上把面板关掉（真机面板高度没这问题）；
        // 这里要验的是保存落盘本身，直接走语义点击
        compose.onNodeWithText("保存").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val saved = Store.prefs(ctx).getString(Store.EXTRACT_FRIENDS, "") ?: ""
        assertTrue("名单没存进主存：$saved", saved.contains("\"friend_list\":\"\""))
        ShadowLooper.shadowMainLooper().idleFor(600, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("互动名单"))
        compose.onNodeWithText("不限 · 和所有好友 PK，也帮他们照顾").assertExists()
        compose.onNodeWithText("白名单 · 只和勾选的 2 位好友互动").assertDoesNotExist()
    }

    /** 对照：不走面板直接改数据，副标题也要跟。 */
    @Test fun summaryFollowsDirectDataChange() {
        val live = LiveState(ctx)
        live.start()
        content(live)
        live.backend.setList("friend", emptyList(), "wl")
        ShadowLooper.shadowMainLooper().idleFor(600, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        compose.onNodeWithText("不限 · 和所有好友 PK，也帮他们照顾").assertExists()
    }
}
