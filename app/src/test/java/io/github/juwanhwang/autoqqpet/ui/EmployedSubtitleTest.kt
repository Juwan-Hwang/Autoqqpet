package io.github.juwanhwang.autoqqpet.ui

import io.github.juwanhwang.autoqqpet.ui.data.FullSnapshot.SnapConfig
import io.github.juwanhwang.autoqqpet.ui.data.employedSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主页被雇佣副标题的三挡口径（2026-10-23 用户投诉：关了自动召回，主页还写「按设置召回」）。
 * 文案不许承诺设置里没有的召回；提醒开着才许写提醒。
 */
class EmployedSubtitleTest {

    @Test fun recallOnBestSplitKeepsSplitWording() {
        assertEquals(
            "进度到 71% 按设置召回（分成最高），之后接着干自己的",
            employedSubtitle(SnapConfig(recallOn = true, recallMode = "best_split", hireAlertOn = false)),
        )
    }

    @Test fun recallOnImmediateSaysImmediate() {
        assertEquals(
            "会按设置立即召回，之后接着干自己的",
            employedSubtitle(SnapConfig(recallOn = true, recallMode = "immediate", hireAlertOn = false)),
        )
    }

    @Test fun recallOffWithReminderPromisesReminderOnly() {
        assertEquals(
            "进度到 71% 会提醒你去召回，之后接着干自己的",
            employedSubtitle(SnapConfig(recallOn = false, recallMode = "best_split", hireAlertOn = true)),
        )
    }

    @Test fun recallOffNoReminderMustNotPromiseRecall() {
        assertEquals(
            "自动召回已关，之后接着干自己的",
            employedSubtitle(SnapConfig(recallOn = false, recallMode = "immediate", hireAlertOn = false)),
        )
    }

    @Test fun missingConfigFallsBackToLegacyWording() {
        assertEquals("进度到 71% 按设置召回（分成最高），之后接着干自己的", employedSubtitle(null))
        assertEquals(
            "进度到 71% 按设置召回（分成最高），之后接着干自己的",
            employedSubtitle(SnapConfig(recallOn = null, recallMode = null, hireAlertOn = null)),
        )
    }
}
