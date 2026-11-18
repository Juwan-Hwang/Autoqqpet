package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.FriendManager
import io.github.juwanhwang.autoqqpet.pet.FriendPolicy
import io.github.juwanhwang.autoqqpet.pet.HireDetect
import io.github.juwanhwang.autoqqpet.pet.HirePolicy
import io.github.juwanhwang.autoqqpet.pet.HiredNotice
import io.github.juwanhwang.autoqqpet.pet.LikeVisitor
import io.github.juwanhwang.autoqqpet.pet.SparkEntry
import io.github.juwanhwang.autoqqpet.pet.SparkPolicy
import io.github.juwanhwang.autoqqpet.pet.SparkStage
import io.github.juwanhwang.autoqqpet.pet.StoryOutcome
import io.github.juwanhwang.autoqqpet.pet.StoryStatus
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import io.github.juwanhwang.autoqqpet.pet.service.FriendApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-15 实测交接里定死的协议事实：每条解析/策略一个用例。
 * 数据形状照抄交接里的字段表；数值是假的（不放任何账号数据）。
 */
class ProtocolFactsTest {

    private fun f32(field: Int, v: Float): ByteArray {
        val bits = v.toRawBits()
        return byteArrayOf(((field shl 3) or 5).toByte(), bits.toByte(), (bits shr 8).toByte(), (bits shr 16).toByte(), (bits shr 24).toByte())
    }

    // ———— B：三属性 ————

    @Test fun attributesTakeDenominatorZeroGroup() {
        // 先给总量（/0），后面是逐职业“当前/门槛”——门槛不能被当成当前值
        val body = pb {
            msg(1) { str(3, "力量839/0 智力1607/0 魅力6468/0") }
            msg(2) { str(1, "力量839/225"); str(2, "智力1607/113"); str(3, "魅力6468/412") }
        }
        val a = Parsers.attributes(body)!!
        assertEquals(839L, a.strength); assertEquals(1607L, a.intel); assertEquals(6468L, a.charm)
        assertEquals(8914L, a.power)   // 与 0x9ad4 自身战力逐位相等
    }

    @Test fun attributesFromLargeTextAndMissing() {
        val big = "x".repeat(6000) + "{\"t\":\"力量1503/0\",\"u\":\"智力1700/0\",\"v\":\"魅力1571/0\"}"   // > 4KB 文本
        assertEquals(4774L, Parsers.attributes(pb { str(1, big) })!!.power)
        assertNull(Parsers.attributes(pb { str(1, "力量1/0 智力2/0") }))
        assertNull(Parsers.attributes(ByteArray(0)))
    }

    // ———— C12：每日经验 ————

    @Test fun dailyExpParse() {
        val body = pb {
            raw(f32(1, 1.2f)); num(2, 86); num(3, 120)
            msg(4) { str(1, "syt4vbtj02"); str(2, "给自己的宠物喂食/洗澡"); num(3, 50); num(4, 50); num(5, 50); num(10, 50); num(11, 50)
                str(6, "mqqapi://qpet/open?a=1&target_page=3000"); str(13, "每2体力/清洁+1经验,上限50") }
            msg(4) { str(1, "visit_friend"); str(2, "去好友家踩一踩"); num(3, 10); num(4, 3); num(5, 3); num(10, 30) }
            msg(4) { str(1, "enter_home"); str(2, "每天回家看看"); num(4, 0); num(5, 1) }
        }
        val e = Parsers.dailyExp(body)!!
        assertEquals(86L, e.today); assertEquals(120L, e.cap)
        assertEquals(1.2, e.multiplier, 1e-6)
        assertEquals(3, e.tasks.size)
        assertTrue(e.selfCareFull)                       // 按 target_page=3000 认出“自己护理”，不认 key
        assertTrue(e.tasks[1].full)                      // 踩 3/3
        assertFalse(e.tasks[2].full)
        assertFalse(e.full)
        assertNull(Parsers.dailyExp(ByteArray(0)))
    }

    @Test fun selfCareMatchedByChineseNameWhenNoTargetPage() {
        val e = Parsers.dailyExp(pb { num(2, 10); num(3, 120); msg(4) { str(1, "opaque"); str(2, "给自己的宠物喂食/洗澡"); num(4, 12); num(5, 50); num(10, 12); num(11, 50) } })!!
        assertEquals("给自己的宠物喂食/洗澡", e.selfCare!!.name)
        assertFalse(e.selfCareFull)
    }

    // ———— C9：火花 ————

    @Test fun sparkListParse() {
        val body = pb {
            msg(1) { msg(1) { str(1, "好友A"); str(5, "10001"); str(8, "PET1"); str(11, "蒜头鹅") }; str(7, "出门中")
                msg(10) { num(1, 1); num(2, 20) }; msg(16) { str(1, "."); str(2, "和你互踩了20天，今日还没互踩哦") } }
            msg(1) { msg(1) { str(1, "好友C"); str(5, "10002") }; msg(10) { num(1, 1); num(2, 15) }; str(16, "和你互踩了15天，小火苗升腾中") }
            msg(1) { msg(1) { str(1, "好友D"); str(5, "10003") }; msg(10) { num(1, 2); num(2, 19) }; str(16, "和你6天没互踩，今日互踩即可重燃") }
            msg(1) { msg(1) { str(1, "路人"); str(5, "10004") }; msg(10) { num(1, 3) } }
            str(2, "{\"remain\":[],\"total\":7,\"version\":1}")
        }
        val l = Parsers.sparks(body)
        assertEquals(4, l.size)
        assertEquals(SparkEntry("10001", "好友A", "PET1", SparkStage.LIT, 20, -1, false, l[0].text), l[0])
        assertTrue(l[1].pokedToday)
        assertEquals(SparkStage.REKINDLE, l[2].stage); assertEquals(19, l[2].days); assertEquals(6, l[2].gapDays)
        assertEquals(SparkStage.UNLIT, l[3].stage); assertEquals(0, l[3].days)
    }

    @Test fun sparkPolicyAllFriendsMinusBlacklist() {
        val sparks = listOf(
            SparkEntry("1", "a", "", SparkStage.LIT, 3),
            SparkEntry("2", "b", "", SparkStage.REKINDLE, 9, 2),
            SparkEntry("3", "c", "", SparkStage.LIT, 5, pokedToday = true),
        )
        val t = SparkPolicy.dailyTargets(listOf("1", "2", "3", "4", "5", "self", "-p"), setOf("5"), setOf("4"), "self", sparks)
        assertEquals(listOf("2", "1"), t)   // 待重燃优先；3 文案已显示今日互踩；4 今天踩过；5 黑名单；self/非数字剔除
        assertTrue(SparkPolicy.dailyTargets(emptyList(), emptySet(), emptySet(), "self").isEmpty())   // 名册空 = 什么都不做
    }

    @Test fun returnPokePolicy() {
        val v = listOf(LikeVisitor("7", "x", 1, true), LikeVisitor("8", "y", 2, true), LikeVisitor("9", "z", 1, false), LikeVisitor("self", "me", 1, true))
        assertTrue(SparkPolicy.returnTargets(false, v, emptySet(), emptySet(), emptySet(), "self").isEmpty())   // 默认关
        assertEquals(listOf("7"), SparkPolicy.returnTargets(true, v, setOf("8"), emptySet(), emptySet(), "self"))   // 8 是好友（已在每日计划）
        assertTrue(SparkPolicy.returnTargets(true, v, emptySet(), emptySet(), setOf("7", "8"), "self").isEmpty())
    }

    @Test fun sparkAtRiskReminder() {
        val l = listOf(
            SparkEntry("1", "a", "", SparkStage.REKINDLE, 19, 6),
            SparkEntry("2", "b", "", SparkStage.REKINDLE, 7, 1),
            SparkEntry("3", "c", "", SparkStage.REKINDLE, 7, -1),
            SparkEntry("4", "d", "", SparkStage.REKINDLE, 7, 6),
        )
        assertEquals(listOf("1", "3"), SparkPolicy.atRisk(l, setOf("4")).map { it.uin })   // 4 在黑名单
        assertEquals(1, SparkPolicy.daysLeft(l[0]))
    }

    @Test fun sparkCacheRoundTrip() {
        val e = SparkEntry("123", "名字", "", SparkStage.REKINDLE, 9, 3, true)
        val text = listOf(e.uin, e.nick, e.stage.code, e.days, e.gapDays, 1).joinToString("⋄")
        assertEquals(listOf(e), FriendPolicy.decodeSparks(text))
        assertTrue(FriendPolicy.decodeSparks("").isEmpty())
    }

    @Test fun likeVisitorsTodayFromHeaderCount() {
        val body = pb {
            msg(1) { msg(1) { str(2, "甲"); msg(6) { str(4, "ss:100:201:1791000000000") }; msg(7) { num(6, 3); str(7, "出门中") } } }
            msg(1) { msg(1) { str(2, "乙"); msg(6) { str(4, "ss:100:202:1791000000000") }; msg(7) { num(6, 1) } } }
            str(3, "312"); str(4, "1")
        }
        val v = Parsers.likeVisitors(body, "100")
        assertEquals(listOf("201", "202"), v.map { it.uin })
        assertEquals(listOf(true, false), v.map { it.today })
        assertEquals(3, v[0].count)
    }

    /** 交接 §9.9 的嵌套形态：根 f4 是汇总消息（.3=总获得、.4=今日新增），根上没有可读的今日数时也要钻进去读。 */
    @Test fun likeVisitorsTodayFromNestedHeader() {
        val body = pb {
            msg(1) { msg(1) { str(2, "甲"); msg(6) { str(4, "ss:100:201:1791000000000") }; msg(7) { num(6, 3) } } }
            msg(1) { msg(1) { str(2, "乙"); msg(6) { str(4, "ss:100:202:1791000000000") }; msg(7) { num(6, 1) } } }
            msg(4) { num(3, 312); num(4, 1) }
        }
        val v = Parsers.likeVisitors(body, "100")
        assertEquals(listOf("201", "202"), v.map { it.uin })
        assertEquals(listOf(true, false), v.map { it.today })
    }

    /** meanings.tsv 0x985e_0 的完整实测形态：记录也在 f4 里面（.4.1），今日数 .4.4、总数 .4.3（均为串）。 */
    @Test fun likeVisitorsFullNestedShape() {
        val body = pb {
            msg(4) {
                msg(1) { msg(1) { str(2, "甲"); msg(6) { str(4, "ss:100:201:1791000000000") }; msg(7) { num(6, 3); str(7, "出门中") } } }
                msg(1) { msg(1) { str(2, "乙"); msg(6) { str(4, "ss:100:202:1791000000000") }; msg(7) { num(6, 1) } } }
                str(3, "312"); str(4, "1"); str(5, "{\"start\":20,\"limit\":20}")
            }
        }
        val v = Parsers.likeVisitors(body, "100")
        assertEquals(listOf("201", "202"), v.map { it.uin })
        assertEquals(listOf(true, false), v.map { it.today })
        assertEquals(3, v[0].count)
        // 游标与今日数单独可读（翻页逻辑靠它们）
        assertEquals("{\"start\":20,\"limit\":20}", Parsers.likeNextCursor(body))
        assertEquals(1, Parsers.likeTodayCount(body))
    }

    /** 翻页后的记录按全局序号判“今日”（第 2 页第 1 条 = 全局第 2 条），且 uin 可从记录 .1.1 varint 兜底读出（抓包实测形态）。 */
    @Test fun likeVisitorsSecondPageGlobalIndex() {
        val page2 = pb {
            msg(4) {
                msg(1) { msg(1) { num(1, 301); str(2, "丙") } }
                msg(1) { msg(1) { num(1, 302); str(2, "丁") } }
                str(3, "312"); str(4, "2")
            }
        }
        val v = Parsers.likeVisitors(page2, "100", todayNOverride = 2, indexOffset = 1)
        assertEquals(listOf("301", "302"), v.map { it.uin })
        assertEquals(listOf(true, false), v.map { it.today })
    }

    // ———— C11：福袋 id ————

    @Test fun bagIdFromRecordField21() {
        val hash = "5e6f7a8b-fedcba9876543210fedcba9876543210fed"
        // 线上真形态：子消息 {1:"6400-<hash>"}（0x0a + 长度字节）
        assertEquals("6400-$hash", Parsers.bagIdOf(pb { str(1, "6400-$hash") }))
        // 交接里的文本读法：“⏎16100-<hash>” → 去换行与前导 1
        assertEquals("6100-abcdef12", Parsers.bagIdOf("\n16100-abcdef12".toByteArray()))
        assertEquals("", Parsers.bagIdOf(ByteArray(0)))
        assertEquals("", Parsers.bagIdOf("hello".toByteArray()))
    }

    @Test fun friendBarNewShapeWithBag() {
        val body = pb {
            msg(1) { msg(1) { str(1, "好友A"); str(5, "3001"); str(8, "PETA"); str(11, "蒜头鹅") }; msg(2) { str(2, "x") }; str(7, "出门中")
                msg(21) { str(1, "6400-aaaaaaaa-bbbb") } }
            msg(1) { msg(1) { str(1, "无袋"); str(5, "3002"); str(8, "PETB") } }
            msg(1) { msg(1) { str(1, "旧形态宠物名"); str(8, "PETC") }; msg(2) { num(1, 3003); str(2, "旧昵称") }; msg(14) { num(4, 4774) } }
            num(3, 0)
        }
        val p = Parsers.pkPage(body)
        assertEquals(listOf("3001", "3002", "3003"), p.items.map { it.uin })
        assertEquals("6400-aaaaaaaa-bbbb", p.items[0].bagId)
        assertEquals("", p.items[1].bagId)
        assertEquals("好友A", p.items[0].nick)
        assertEquals("旧昵称", p.items[2].nick)
        assertEquals(4774L, p.items[2].powerF4)
    }

    @Test fun coinBagSummaryFromFixedField() {
        val b = Parsers.coinBag("6400-x", pb { msg(1) { msg(3) { num(1, 2); str(2, "5个福袋共 330 金币，已领2个") } }; num(2, 1) })
        assertEquals("5个福袋共 330 金币，已领2个", b.summary)
    }

    // ———— A3：PK 输赢 ————

    @Test fun pkResultFromTitleNotGold() {
        val win = Parsers.storyOutcome(pb { msg(1) { str(7, "挑战 某某"); msg(3) { msg(1) { num(1, 1); str(2, "金币"); num(3, 54) }; msg(1) { str(2, "心情值"); num(3, 2) } }
            msg(18) { str(1, "挑战 某某"); str(8, "大获全胜") } } })
        assertEquals(true, win.pkWon); assertEquals(54L, win.gold); assertEquals(2, win.rewards.size)
        // 输局也给 15 金币：goldDelta>0 判赢是错的
        val lose = Parsers.storyOutcome(pb { msg(1) { str(12, "不要灰心…"); msg(3) { msg(1) { str(2, "金币"); num(3, 15) } }; msg(18) { str(8, "惜败") } } })
        assertEquals(false, lose.pkWon); assertEquals(15L, lose.gold)
        assertNull(Parsers.storyOutcome(pb { msg(1) { str(7, "x") } }).pkWon)
        assertTrue(Parsers.storyOutcome(ByteArray(0)).empty)
    }

    // ———— A2：召回回包 ————

    @Test fun recallOutcomeGoldAndPartial() {
        val o = Parsers.storyOutcome(pb { msg(1) { str(7, "替摊主看摊（被雇佣）"); str(12, "未获得完整收益"); msg(16) { str(1, "替摊主看摊（被雇佣）"); num(7, 47) } } })
        assertEquals(47L, o.recallGold); assertEquals(47L, o.gold)
        assertTrue(o.partial); assertTrue(o.hiredSide); assertFalse(o.employerSide)
        assertTrue(HireDetect.fromOutcome(o))
        val employer = StoryOutcome(name = "练习室加练（雇佣）")
        assertTrue(employer.employerSide); assertFalse(employer.hiredSide)
    }

    // ———— A5 / C13：被雇判定 ————

    @Test fun hiredDetectionNeedsState51AndNotice() {
        val start = 1_791_999_904L
        val st = StoryStatus("6400_uuid", 51, 300, 600, start, recallable = false)
        val notice = HiredNotice("雇主", start * 1000 + 20_000, 6400)
        val now = start * 1000 + 300_000
        assertTrue(HireDetect.confirmed(st, mine = false, notices = listOf(notice), nowMs = now))
        assertFalse(HireDetect.confirmed(st, mine = true, notices = listOf(notice), nowMs = now))                         // 本模块开的
        assertFalse(HireDetect.confirmed(st.copy(stateCode = 2), mine = false, notices = listOf(notice), nowMs = now))    // 不是打工态
        assertFalse(HireDetect.confirmed(st, mine = false, notices = emptyList(), nowMs = now))                           // 只有 51 不够
        assertFalse(HireDetect.confirmed(st, false, listOf(notice.copy(atMillis = start * 1000 - 3_600_000)), now))       // 旧通知
        assertFalse(StoryStatus("6400_x", 51, 0, 600, start, false).let { HireDetect.confirmed(it, false, listOf(notice), now) })
    }

    @Test fun hiredNoticeParse() {
        val body = pb {
            msg(1) { str(1, "5001"); msg(2) { str(1, "01:45 雇佣了") }; msg(2) { str(1, "雇主昵名"); str(2, "font_weight:2") }; msg(2) { str(1, "，快去看看TA吧！") }
                num(3, 1_792_000_000_000L); str(4, "uuid"); str(5, "雇主昵名"); num(6, 6400) }
            msg(1) { str(1, "5002"); msg(2) { str(1, "喂了你的宠物") }; num(3, 1L) }
        }
        val n = Parsers.hiredNotices(body)
        assertEquals(1, n.size)
        assertEquals("雇主昵名", n[0].employer); assertEquals(1_792_000_000_000L, n[0].atMillis); assertEquals(6400L, n[0].scene)
    }

    // ———— A4 / C14：雇佣判断（2026-10-19 纠正：雇人不收费，没有回本一说）————

    @Test fun hireJudgeHasNoBreakEven() {
        // 短工也照常雇：judge 不看工时（此前 45 分钟工因“增益 229 < 250”被误拒），
        // 也不看金币留底（2026-10-31 定版：雇人不花钱、加成最差为 0，雇了恒不亏）
        assertTrue(HirePolicy.judge(true, 0, 3).hire)
        // 只剩次数上限（按开工班次计，满额后复用也拒）与开关
        assertFalse(HirePolicy.judge(true, 3, 3).hire)
        val reuse = HirePolicy.judge(true, 2, 3, reusable = true)
        assertTrue(reuse.hire); assertTrue(reuse.reuse)
        assertFalse(HirePolicy.judge(true, 3, 3, reusable = true).hire)
        assertFalse(HirePolicy.judge(false, 0, 3, reusable = true).hire)
    }

    // ———— B：PK 选敌 ————

    @Test fun pkOpponentByRealPower() {
        fun f(u: String) = FriendApi.Friend(u, "P$u", 0, 0)
        val c = listOf(f("a") to 9000L, f("b") to 4774L, f("c") to 7000L, f("d") to 0L)
        // 资格集合：战力 ≤ 8914×90%=8022 的已知战力者 = b、c（a 太强；d 战力未知、有人已知时不入选）
        assertEquals(listOf("b", "c"), FriendManager.qualified(c, 8914, 90).map { it.uin })
        assertNull(FriendManager.choose(listOf(f("a") to 9000L, f("c") to 8500L), 8914, 90))   // 8500 > 8914×90%
        assertEquals("d", FriendManager.choose(listOf(f("d") to 0L), 8914, 90)!!.uin)          // 谁都不知道 → 整批算资格（名单兜底）
        assertEquals(c.map { it.first.uin }, FriendManager.qualified(c, 0, 90).map { it.uin })  // 我方战力未知 → 整批算资格
    }

    @Test fun pkOpponentRandomWithinQualified() {
        fun f(u: String) = FriendApi.Friend(u, "P$u", 0, 0)
        val c = listOf(f("a") to 9000L, f("b") to 4774L, f("c") to 7000L, f("d") to 0L)
        val rnd = kotlin.random.Random(20261019)
        val seen = (1..300).map { FriendManager.choose(c, 8914, 90, rnd)!!.uin }.toSet()
        // 合格的两个都抽得到（打散）；不合格的（a 太强、d 未知）永远抽不到
        assertEquals(setOf("b", "c"), seen)
    }

    // ———— A1 / C10：签到、洗澡回包即新值 ————

    @Test fun writeRepliesCarryNewValue() {
        assertEquals(40L, Parsers.checkinStock(pb { num(1, 40); num(2, 5) }))
        assertNull(Parsers.checkinStock(ByteArray(0)))                 // 同一天重发 = 空回包 = 已领
        assertEquals(90L, Parsers.bathClean(pb { num(1, 90); num(2, 10); num(3, 28); num(5, 100) }))
        assertNull(Parsers.bathClean(ByteArray(0)))
    }

    @Test fun protoHelperSanity() {
        assertEquals(1.2, ProtoBuf.firstFloat(ProtoBuf.parseMessage(f32(1, 1.2f)), 1), 1e-6)
    }
}
