package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.hook.StatusSnapshot
import io.github.juwanhwang.autoqqpet.ui.data.StatusParser
import io.github.juwanhwang.autoqqpet.ui.data.TaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1.1：开工后主页要立刻显示“正在学习/打工/冒险”（真机：打工已开，主页却是“稍作休息”）。
 * 根因：快照里的 task 只来自每轮开头的状态读（开工前 = 空闲），开工后到期前没有新一轮 → 快照一直报空闲。
 * 现在服务器读数不在跑时用开工登记（pending）补 task。Robolectric 只为 org.json 的 Android 实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskPhaseMappingTest {

    private val now = 1_760_000_000_000L

    @Test fun startedStateStrings() {
        listOf("study" to TaskKind.STUDY, "work" to TaskKind.WORK, "adventure" to TaskKind.ADVENTURE).forEach { (tag, kind) ->
            val h = StatusParser.parse("started:$tag:校园小通告")
            assertEquals(tag, h.startedTag)
            assertEquals("校园小通告", h.startedDetail)
            assertEquals(kind, TaskKind.ofTag(h.startedTag))
            // 前缀“续跑·/启动·”不影响
            assertEquals(kind, TaskKind.ofTag(StatusParser.parse("续跑·started:$tag:x").startedTag))
        }
        assertTrue(StatusParser.parse("busy:6400_abc 剩120s").busy)
        assertEquals(120L, StatusParser.parse("busy:6400_abc 剩120s").busyRemainSec)
        assertTrue(StatusParser.parse("employed-wait").employed)
        assertNull(StatusParser.parse("idle:短冒险间隔").startedTag)
    }

    @Test fun pendingTaskForEachKind() {
        listOf("study" to "6100_1", "work" to "6400_1", "adventure" to "6700_1").forEach { (tag, sid) ->
            val o = StatusSnapshot.pendingTask(sid, tag, now + 2_656_000, 2700, "校园小通告", now)
            assertNotNull(o); o!!
            assertEquals(tag, o.getString("kind"))
            assertEquals("校园小通告", o.getString("name"))
            assertEquals(2656L, o.getLong("remaining_sec"))
            assertEquals(now + 2_656_000, o.getLong("ends_at"))
            assertEquals(now + 2_656_000 - 2_700_000, o.getLong("started_at"))
        }
        // 种类没登记 → 按 storyId 前缀（6500 旧打工口径也算打工）
        assertEquals("work", StatusSnapshot.pendingTask("6500_9", "", now + 60_000, 600, "", now)!!.getString("kind"))
    }

    @Test fun pendingTaskExpiredOrMissing() {
        assertNull(StatusSnapshot.pendingTask("", "work", now + 60_000, 600, "", now))
        assertNull(StatusSnapshot.pendingTask("6400_1", "work", now - 1, 600, "", now))
        assertNull(StatusSnapshot.pendingTask("6900_1", "pk", now + 60_000, 600, "", now))   // PK 不是主任务
    }

    /** 打工状态回包不带 storyId（{1:51,2:剩余,3:总}）→ 用开工登记补；种类对不上不补。 */
    @Test fun workStatusWithoutStoryIdIsFilledFromPending() {
        val st = io.github.juwanhwang.autoqqpet.pet.StoryStatus("", 51, 2600, 2700, 0, false)
        assertEquals("6400_9", io.github.juwanhwang.autoqqpet.pet.StoryStatus.fillStoryId(st, "6400_9", "work").storyId)
        assertEquals("", io.github.juwanhwang.autoqqpet.pet.StoryStatus.fillStoryId(st, "6100_9", "study").storyId)
        val done = st.copy(remainingSeconds = 0)
        assertEquals("", io.github.juwanhwang.autoqqpet.pet.StoryStatus.fillStoryId(done, "6400_9", "work").storyId)
        val withId = st.copy(storyId = "6400_x")
        assertEquals("6400_x", io.github.juwanhwang.autoqqpet.pet.StoryStatus.fillStoryId(withId, "6400_9", "work").storyId)
    }

    /** 快照 → 界面：pending 补出来的 task 能被 FullSnapshot 解析成正在打工。 */
    @Test fun snapshotRoundTripShowsWork() {
        val task = StatusSnapshot.pendingTask("6400_1", "work", now + 2_656_000, 2700, "校园小通告", now)!!
        val raw = org.json.JSONObject().put("v", 1).put("at", now).put("task", task).toString()
        val full = io.github.juwanhwang.autoqqpet.ui.data.FullSnapshot.parse(raw)!!
        assertEquals(TaskKind.WORK, full.task!!.kind)
        assertEquals("校园小通告", full.task!!.name)
        assertEquals(now + 2_656_000, full.task!!.endsAt)
    }
}
