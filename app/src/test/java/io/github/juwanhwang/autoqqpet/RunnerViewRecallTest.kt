package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.RunnerView
import io.github.juwanhwang.autoqqpet.pet.StoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * code 34 真机：天色不对召回结案后，主页还按被召回那趟的原始时长往下走倒计时，
 * 过一会儿才突然跳到新一趟——view.story 只在每轮开头重读，结案那一笔没清它。
 * noteRecalled 必须当场清掉旧趟展示，并把活动文案写明是召回（不清 narrative/被雇标记）。
 */
class RunnerViewRecallTest {

    @Test fun noteRecalledClearsOldStoryAndSaysSo() {
        val view = RunnerView()
        view.noteStory(
            StoryStatus(storyId = "6700_abc", stateCode = 1, remainingSeconds = 40, durationSeconds = 45, startedAt = 1_760_000_000L, recallable = true),
            mine = true,
        )
        view.narrative = "我发现天色不对，好像要发生什么..."
        view.hiredNow = true

        view.noteRecalled("🌦 天色不对，已召回，重新出发")

        assertNull("旧趟不能再留在展示里推倒计时", view.story)
        assertNull(view.storyMine)
        assertEquals("", view.narrative)
        assertEquals("", view.externalName)
        assertEquals(false, view.hiredNow)
        assertEquals("🌦 天色不对，已召回，重新出发", view.activityText)
    }
}
