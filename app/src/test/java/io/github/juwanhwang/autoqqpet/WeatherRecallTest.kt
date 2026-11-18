package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import io.github.juwanhwang.autoqqpet.pet.service.StoryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-19 实测钉死的两件事：
 * 召回包（0x9760 action=6000）的 f4 必须是 3——发 0 时冒险召回被服务器以 135004 拒绝，官方页面发的就是 3；
 * 冒险进度里出现「天色不对」条目 = 天气趟、整趟零金币，出发约 5 秒就能从 0x975f 进度里读到。
 */
class WeatherRecallTest {

    @Test fun recallBodyCarriesF4Eq3() {
        val body = StoryApi.recallBody("6700_03a3673c-61aa-46fd-92d6-42720bb4b958", "pet-10001")
        val m = ProtoBuf.parseMessage(body)
        assertEquals("6700_03a3673c-61aa-46fd-92d6-42720bb4b958", ProtoBuf.firstString(m, 1))
        assertEquals(6000L, ProtoBuf.firstVarint(m, 2))
        assertEquals("pet-10001", ProtoBuf.firstString(m, 3))
        assertEquals(3L, ProtoBuf.firstVarint(m, 4))
        assertEquals(2L, ProtoBuf.firstVarint(m, 100))
    }

    /** 0x975f 进度回包的形状：根层 f1 是条目列表，每条 {1:条目时刻秒, 2:当时剩余秒, 3:JSON 富文本}。 */
    private fun progressBody(vararg entries: Triple<Long, Long, String>): ByteArray = pb {
        for ((at, remain, json) in entries) msg(1) { num(1, at); num(2, remain); str(3, json) }
    }

    @Test fun weatherEntryDetectedInProgress() {
        val body = progressBody(
            Triple(1_760_000_000L, 45L, """[{"text":"東雪蓮出门了，今天去附近走走。"}]"""),
            Triple(1_760_000_005L, 40L, """[{"text":"我发现天色不对，好像要发生什么..."}]"""),
            Triple(1_760_000_045L, 0L, """[{"text":"天色放晴了，東雪蓮回到了家。"}]"""),
        )
        val p = Parsers.storyProgress("6700_x", body)
        assertTrue(p.lines.any { it.contains("天色不对") })
        assertTrue(p.weatherWarned)
    }

    /**
     * 结算回包条目表（body.f1.9）的形状与实测一致：6 段里同一叙事出现两次
     * （回放表与条目表各一份），提取时按文本去重，实际剩 4 条，顺序与剩余秒取第一次出现。
     */
    private fun outcomeBody(): ByteArray = pb {
        msg(1) {
            msg(9) { num(1, 1_760_000_000L); num(2, 45L); str(3, """[{"text":"東雪蓮出门了，今天去附近走走。"}]""") }
            msg(9) { num(1, 1_760_000_005L); num(2, 40L); str(3, """[{"text":"我发现天色不对，好像要发生什么..."}]""") }
            msg(9) { num(1, 1_760_000_015L); num(2, 30L); str(3, """[{"text":"天空中"},{"text":"阴云密布，要下雨了。"}]""") }
            msg(9) { num(1, 1_760_000_045L); num(2, 0L); str(3, """[{"text":"天色放晴，東雪蓮回到了家。"}]""") }
            msg(9) { num(1, 1_760_000_005L); num(2, 40L); str(3, """[{"text":"我发现天色不对，好像要发生什么..."}]""") }
            msg(9) { num(1, 1_760_000_000L); num(2, 45L); str(3, """[{"text":"東雪蓮出门了，今天去附近走走。"}]""") }
        }
    }

    @Test fun storyEntriesExtractedAndDeduped() {
        val entries = Parsers.storyEntries(outcomeBody())
        assertEquals(4, entries.size)
        assertEquals(listOf(45L, 40L, 30L, 0L), entries.map { it.remainSec })
        assertTrue(entries[0].text.contains("出门了"))
        assertTrue(entries[1].text.contains("天色不对"))
        assertEquals("天空中 阴云密布，要下雨了。", entries[2].text)   // 多段 text 拼接
        assertTrue(entries[3].text.contains("回到了家"))
        // 结局解析顺带把条目表带出来，runner 结案时直接用
        assertEquals(entries, Parsers.storyOutcome(outcomeBody()).entries)
    }

    /**
     * 结尾全角问号不得变乱码（code 34 真机一条召回结案条目末尾出现「…什么事情呢��」，
     * 同窗口其余条目正常）：把这句原文按线上形状造包——整段一段、问号单独一段、多字节字在中间——
     * 解析链（线长切片 → UTF-8 解码 → JSON 抽段 → 清洗）都应原样保住「？」、不产生替换符。
     */
    @Test fun entryEndingWithFullwidthQuestionMarkStaysIntact() {
        val body = pb {
            msg(1) {
                msg(9) { num(1, 1_760_000_000L); num(2, 45L); str(3, """[{"text":"我出门了，这次会遇到什么事情呢？"}]""") }
                msg(9) { num(1, 1_760_000_005L); num(2, 40L); str(3, """[{"text":"我出门了，这次会遇到什么事情呢"},{"text":"？"}]""") }
                msg(9) { num(1, 1_760_000_010L); num(2, 35L); str(3, """[{"text":"東雪蓮问：今天会下雨吗？大家都说不会。"}]""") }
            }
        }
        val entries = Parsers.storyEntries(body)
        assertEquals(3, entries.size)
        assertEquals("我出门了，这次会遇到什么事情呢？", entries[0].text)
        assertTrue(entries[1].text.contains("？"))
        assertTrue(entries[2].text.endsWith("不会。"))
        assertTrue(entries.none { it.text.contains('\uFFFD') })
    }

    // —— 检查重试节奏（issue #11 复盘：旧版读一次失败就整趟放弃，坏天气跑满全程）——

    @Test fun readFailRetriesUntilMaxThenGivesUp() {
        val now = 1_760_000_000_000L
        // 前 3 次没读到都还能再试（共最多 4 次）
        assertEquals(now + 8_000L, io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.nextAfterReadFail(0, now))
        assertEquals(now + 8_000L, io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.nextAfterReadFail(1, now))
        assertEquals(now + 8_000L, io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.nextAfterReadFail(2, now))
        // 第 4 次还没读到 → 放弃这一趟
        assertEquals(null, io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.nextAfterReadFail(3, now))
    }

    @Test fun recallFailRetriesUntilMaxThenGivesUp() {
        val now = 1_760_000_000_000L
        assertEquals(now + 8_000L, io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.nextAfterRecallFail(0, now))
        assertEquals(now + 8_000L, io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.nextAfterRecallFail(1, now))
        assertEquals(null, io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.nextAfterRecallFail(2, now))
    }

    @Test fun emptyProgressIsInconclusiveNotClear() {
        assertTrue(io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.inconclusive(null))
        assertTrue(io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.inconclusive(emptyList()))
        assertFalse(io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan.inconclusive(listOf("東雪蓮出门了")))
    }

    @Test fun adoptOnlyForOwnUnjudgedAdventureWithTimeLeft() {
        val plan = io.github.juwanhwang.autoqqpet.optimizer.WeatherCheckPlan
        // 自家派出的、没挂记号、没判过、剩余够 → 接手
        assertTrue(plan.shouldAdopt("", "", "6700_a", "adventure", 123L, "6700_a", 30L))
        // 已挂着记号 / 已判完 → 不接
        assertFalse(plan.shouldAdopt("6700_b", "", "6700_a", "adventure", 123L, "6700_a", 30L))
        assertFalse(plan.shouldAdopt("", "6700_a", "6700_a", "adventure", 123L, "6700_a", 30L))
        // 不是登记中的这一趟 / 不是冒险 / 没有派出时刻（手动或外部故事）→ 不接
        assertFalse(plan.shouldAdopt("", "", "6700_b", "adventure", 123L, "6700_a", 30L))
        assertFalse(plan.shouldAdopt("", "", "6700_a", "study", 123L, "6700_a", 30L))
        assertFalse(plan.shouldAdopt("", "", "6700_a", "adventure", 0L, "6700_a", 30L))
        // 剩余太短，召回也省不下时间 → 不接
        assertFalse(plan.shouldAdopt("", "", "6700_a", "adventure", 123L, "6700_a", 12L))
    }

    @Test fun normalProgressHasNoWeatherWarning() {
        val body = progressBody(
            Triple(1_760_000_000L, 45L, """[{"text":"東雪蓮出门了，今天去附近走走。"}]"""),
            Triple(1_760_000_008L, 37L, """[{"text":"遇到了一只小狗，一起玩了一会儿。"}]"""),
            Triple(1_760_000_045L, 0L, """[{"text":"東雪蓮带着捡到的金币回到了家。"}]"""),
        )
        val p = Parsers.storyProgress("6700_x", body)
        assertTrue(p.lines.isNotEmpty())
        assertFalse(p.weatherWarned)
    }
}
