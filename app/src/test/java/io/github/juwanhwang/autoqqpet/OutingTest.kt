package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.optimizer.ShortAdventurePace
import io.github.juwanhwang.autoqqpet.optimizer.TaskClock
import io.github.juwanhwang.autoqqpet.pet.LogRetention
import io.github.juwanhwang.autoqqpet.pet.OutingKind
import io.github.juwanhwang.autoqqpet.pet.OutingResult
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import io.github.juwanhwang.autoqqpet.pet.parse.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 2026-10-16 v2.1：出门记录 0x9876_1 / 单条详情 0x9874_1 解析、富文本 text: 前缀、短冒险节奏、日志保留、到期时刻。 */
class OutingTest {

    @Test fun historyListParses() {
        val body = pb {
            msg(1) { str(1, "6100_aaaaaaaa-1111"); str(3, "武术身法课"); num(4, 1); str(5, """[{"text":"认真上完了课"}]"""); num(6, 1_760_600_000_123); num(8, 2) }
            msg(1) { str(1, "6900_bbbbbbbb-2222"); str(3, "挑战 好友A"); num(4, 6); str(5, "惜败"); num(8, 2) }
            msg(1) { str(1, "6400_cccccccc-3333"); str(3, "替摊主看摊（被雇佣）"); num(4, 7); num(8, 2) }
        }
        val page = Parsers.outingHistory(body)
        assertEquals(3, page.records.size)
        val a = page.records[0]
        assertEquals(OutingKind.STUDY, a.kind)
        assertEquals("A+", a.resultLabel)
        assertEquals("认真上完了课", a.narrative)
        assertEquals(1_760_600_000_123L, a.atMs)
        val pk = page.records[1]
        assertEquals("负", pk.resultLabel)
        assertEquals(false, OutingResult.pkWon(pk.kind, pk.result))
        assertEquals(0L, pk.atMs)
        assertTrue(page.records[2].hiredSide)
        assertEquals("结果 7", page.records[2].resultLabel)   // 7 未解：如实显示，不猜
        assertEquals(0L, page.nextCursorMs)   // 最后一条没时间戳 → 不能翻页
    }

    @Test fun historyNestedItemLayer() {
        // 抓包记作 .1.1：条目可能再包一层
        val body = pb { msg(1) { msg(1) { str(1, "6700_dddddddd-4444"); str(3, "附近走走"); num(4, 0) } } }
        val r = Parsers.outingHistory(body).records.single()
        assertEquals("6700_dddddddd-4444", r.storyId)
        assertEquals(OutingKind.ADVENTURE, r.kind)
        assertEquals("", r.resultLabel)
    }

    @Test fun historyEmptyBody() { assertTrue(Parsers.outingHistory(ByteArray(0)).records.isEmpty()) }

    @Test fun detailParsesResultAttrsAndCap() {
        val body = pb {
            msg(1) {
                msg(3) {
                    msg(1) { num(1, 1); str(2, "智力"); num(3, 12); num(7, 12) }
                    msg(1) { str(2, "金币"); num(3, 54) }
                }
                str(7, "数学思维课")
                num(8, 2)
                msg(9) { num(2, 5); str(3, """[{"text":"开始上课了"}]""") }
                msg(9) { num(2, 155); str(3, """[{"text":"下课铃响了"}]""") }
                num(10, 1_760_590_000)
                num(11, 6100)
                str(12, "今日奖励已达上限，本次未获得额外奖励")
                num(13, 6131002)
                msg(15) { num(4, 2); num(12, 30) }
            }
        }
        val d = Parsers.storyDetail("6100_x", body)
        assertEquals(OutingKind.STUDY, d.kind)
        assertEquals("A", d.resultLabel)
        assertEquals(1_760_590_000L, d.startSec)
        assertEquals(6131002L, d.courseId)
        assertEquals(30L, d.courseMinutes)
        assertEquals(listOf("智力" to 12L), d.attrGains.map { it.name to it.value })
        assertEquals(54L, d.gold)
        assertTrue(d.dailyCapReached)
        assertEquals(2, d.beats.size)
        assertEquals(155L, d.beats[1].second)
        assertEquals("下课铃响了", d.beats[1].text)
    }

    @Test fun detailResultFallsBackTo15_4AndNoAttrBlock() {
        val d = Parsers.storyDetail("6900_y", pb { msg(1) { str(7, "挑战 好友B"); num(11, 6900); msg(15) { num(4, 4) } } })
        assertEquals("胜", d.resultLabel)
        assertTrue(d.attrGains.isEmpty())    // A+/PK 记录可能没有属性块：不假设
        assertFalse(d.dailyCapReached)
    }

    @Test fun richTextStripsTextPrefix() {
        assertEquals("巨星自我修养开始了", RichText.clean("""[{"text":"巨星自我修养开始了!"}]"""))
        assertEquals("巨星自我修养开始了", RichText.clean("text:巨星自我修养开始了!"))
        assertEquals("巨星自我修养开始了", RichText.clean("{text:巨星自我修养开始了!}"))
        assertTrue(RichText.clean("context: 保留单词里的 text").contains("context"))
    }

    @Test fun storyIdKinds() {
        assertEquals(OutingKind.STUDY, OutingKind.ofStoryId("6100_x"))
        assertEquals(OutingKind.WORK, OutingKind.ofStoryId("6400_x"))
        assertEquals(OutingKind.ADVENTURE, OutingKind.ofStoryId("6700_x"))
        assertEquals(OutingKind.PK, OutingKind.ofStoryId("6900_x"))
        assertEquals("study", OutingKind.ofStoryId("6100_x").tag)
    }

    @Test fun shortAdventurePace() {
        val p = ShortAdventurePace()
        assertEquals(0L, p.waitMs(1_000, 20_000))
        p.noteSent(1_000)
        assertEquals(15_000L, p.waitMs(6_000, 20_000))
        p.noteFail(6_000)
        assertEquals(60_000L, p.waitMs(6_000, 20_000))          // 第一次失败退避 1 分钟
        p.noteFail(6_000); p.noteFail(6_000)
        assertEquals(240_000L, p.waitMs(6_000, 0))              // 1→2→4 分钟
        repeat(10) { p.noteFail(6_000) }
        assertEquals(30 * 60_000L, p.waitMs(6_000, 0))          // 封顶 30 分钟
        p.noteOk()
        assertEquals(0L, p.waitMs(100_000, 20_000))
    }

    @Test fun taskClockPrefersServerStart() {
        assertEquals((1_760_000_000L + 45) * 1000, TaskClock.endAt(1_760_000_000L, 45, now = 9_999_999_999_999L))
        assertEquals(1_000L + 30_000L, TaskClock.endAt(0, 45, now = 1_000L, remainingSec = 30))
        assertEquals(1_000L + 45_000L, TaskClock.endAt(0, 45, now = 1_000L))
    }

    @Test fun logRetentionKeepsThreeDays() {
        val today = LocalDate.of(2026, 10, 16)
        assertFalse(LogRetention.shouldDelete("2026-10-16.log", today, 3))
        assertFalse(LogRetention.shouldDelete("2026-10-14.log", today, 3))
        assertTrue(LogRetention.shouldDelete("2026-10-13.log", today, 3))
        assertTrue(LogRetention.shouldDelete("2026-10-15.log", today, 1))
        assertTrue(LogRetention.shouldDelete("junk.txt", today, 3))
        assertNull(LogRetention.dateOf("x.log"))
    }
}
