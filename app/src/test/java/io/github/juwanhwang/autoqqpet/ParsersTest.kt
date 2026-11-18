package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test fun storyStatus() {
        val body = pb { msg(1) { num(1, 51); num(2, 1200); num(3, 2700); num(4, 1760000000); num(5, 1) }; str(2, "6400_abc") }
        val st = Parsers.storyStatus(body)
        assertEquals("6400_abc", st.storyId)
        assertEquals(1200L, st.remainingSeconds)
        assertEquals(1500L, st.elapsedSeconds)
        assertTrue(st.recallable)
        assertTrue(st.canRecall)
        assertEquals("6400", st.kindPrefix)
        assertEquals(1500.0 / 2700, st.progress, 1e-9)
    }

    @Test fun finishedStoryCannotRecall() {
        val st = Parsers.storyStatus(pb { msg(1) { num(3, 600); num(4, 1); num(5, 1) }; str(2, "6700_x") })
        assertEquals(0L, st.remainingSeconds)
        assertFalse(st.canRecall)
    }

    @Test fun startedStory() {
        val s = Parsers.startedStory(pb { str(1, "6400_sid"); msg(4) { num(1, 51); num(2, 600); num(3, 600); num(4, 1760000000) } })
        assertEquals("6400_sid", s.storyId)
        assertEquals(600L, s.durationSeconds)
        assertEquals("", Parsers.startedStory(ByteArray(0)).storyId)
    }

    @Test fun friendStatusHireCap() {
        val full = Parsers.friendStatus(pb { msg(1) { num(1, 20); str(6, "对方宠物今天被雇佣次数已达上限") } })
        assertEquals(20, full.hireDailyCap)
        assertTrue(full.hireCapReached)
        assertFalse(full.hireable)
        val busy = Parsers.friendStatus(pb { msg(1) { num(1, 20) }; msg(3) { str(1, "宠物正在写作业呢，稍后再来吧") } })
        assertTrue(busy.busy)
        assertFalse(busy.hireable)
        val ok = Parsers.friendStatus(pb { msg(1) { num(1, 20) } })
        assertTrue(ok.hireable)
    }

    @Test fun settlementRewardsTextForm() {
        val body = pb { msg(1) { str(1, "宠物在街角占卜摊忙了一下午，客人络绎不绝"); msg(2) { str(1, "工分 +3"); str(2, "https://x/icon.png") }; msg(2) { str(1, "金币+120") } } }
        val s = Parsers.settlement("sid", body)
        assertEquals(3L, s.points)
        assertEquals(120L, s.gold)
        assertTrue(s.narrative.first().startsWith("宠物在街角"))
    }

    @Test fun storyOutcomeWashesRewardNames() {
        // 0x9760 回包：名称字段是服务器显示标签——「工分 +」带运算符尾巴、「疲惫，收益减少」是文案伪条目
        val body = pb {
            msg(1) {
                msg(3) {
                    msg(1) { str(2, "工分 +"); num(3, 6) }
                    msg(1) { str(2, "金币"); num(3, 120) }
                    msg(1) { str(2, "疲惫，收益减少"); num(3, 10) }
                }
            }
        }
        val o = Parsers.storyOutcome(body)
        assertEquals(listOf("工分" to 6L, "金币" to 120L), o.rewards.map { it.name to it.amount })
    }

    @Test fun rewardVariantNamesFoldAndMerge() {
        // 基础魅力/奖励魅力/魅力 是同一种奖励：归一后并成一条、数值相加
        val body = pb {
            msg(1) {
                msg(3) {
                    msg(1) { str(2, "基础魅力"); num(3, 3) }
                    msg(1) { str(2, "奖励魅力"); num(3, 2) }
                    msg(1) { str(2, "魅力"); num(3, 5) }
                }
            }
        }
        val o = Parsers.storyOutcome(body)
        assertEquals(listOf("魅力" to 10L), o.rewards.map { it.name to it.amount })
        // 折叠口径：只有已知本体才折，不认识的变体原样保留
        assertEquals("学分", Parsers.foldRewardVariant("基础学分"))
        assertEquals("金币", Parsers.foldRewardVariant("额外金币"))
        assertEquals("基础运气", Parsers.foldRewardVariant("基础运气"))
        assertEquals("魅力", Parsers.cleanRewardName("奖励魅力 +"))
    }

    @Test fun settlementFoldsVariantNames() {
        // 9874 文本形态同样折叠：「基础魅力 +3」记成魅力
        val body = pb { str(1, "基础魅力 +3") }
        val s = Parsers.settlement("sid", body)
        assertEquals(listOf("魅力" to 3L), s.rewards.map { it.name to it.amount })
    }

    @Test fun settlementRewardsPairForm() {
        val body = pb { msg(1) { msg(2) { str(1, "力量"); num(2, 30); str(3, "https://x/i.png") } } }
        val s = Parsers.settlement("sid", body)
        assertEquals(30L, s.attrGain)
    }

    @Test fun settlementPairFormRejectsDialogueWithHugeNumber() {
        // 2026-11-03 群友实证：台词「嘻嘻」+ 大数字（像 id）+ 图标被形态②误捞成奖励
        val body = pb { msg(1) { msg(2) { str(1, "嘻嘻"); num(2, 2050888); str(3, "https://x/i.png") } } }
        val s = Parsers.settlement("sid", body)
        assertTrue(s.rewards.none { it.name == "嘻嘻" })
    }

    @Test fun settlementPairFormRejectsUnknownNameEvenWithSmallQty() {
        val body = pb { msg(1) { msg(2) { str(1, "嘻嘻"); num(2, 5); str(3, "https://x/i.png") } } }
        val s = Parsers.settlement("sid", body)
        assertTrue(s.rewards.none { it.name == "嘻嘻" })
    }

    @Test fun settlementPairFormRejectsHugeQtyEvenForKnownName() {
        val body = pb { msg(1) { msg(2) { str(1, "金币"); num(2, 2050888); str(3, "https://x/i.png") } } }
        val s = Parsers.settlement("sid", body)
        assertEquals(0L, s.gold)
    }

    @Test fun settlementTextFormRejectsDialogueAndHugeQty() {
        // 2026-11-04 群友实证：台词「谁还不是宝宝 +927873」从文本形态①漏进实发——统一口径后形态①同样只认已知奖励、数量 ≤9999
        val body = pb { msg(1) { str(1, "谁还不是宝宝 +927873"); str(2, "嘻嘻 +5"); str(3, "金币 +10000"); str(4, "金币 +120"); str(5, "看视频 +25") } }
        val s = Parsers.settlement("sid", body)
        assertEquals(listOf("金币" to 120L, "看视频" to 25L), s.rewards.map { it.name to it.amount })
    }

    @Test fun storyOutcomeRejectsDialogueAndHugeQty() {
        // 结构化条目同罪：台词名、超界数量一律不收；正常条目与看视频加成照常
        val body = pb {
            msg(1) {
                msg(3) {
                    msg(1) { str(2, "谁还不是宝宝"); num(3, 927873) }
                    msg(1) { str(2, "金币"); num(3, 6758106) }
                    msg(1) { str(2, "金币"); num(3, 120) }
                    msg(1) { str(2, "看视频"); num(3, 25) }
                }
            }
        }
        val o = Parsers.storyOutcome(body)
        assertEquals(listOf("金币" to 120L, "看视频" to 25L), o.rewards.map { it.name to it.amount })
    }

    @Test fun coinBag() {
        val b = Parsers.coinBag("6400-abcdef", pb { num(11, 197); num(12, 192); msg(4) { str(2, "5个福袋共 197 金币，已领1个") } })
        assertEquals(197L, b.total)
        assertEquals(192L, b.remaining)
        assertTrue(b.summary.contains("福袋"))
        val nested = Parsers.coinBag("x", pb { msg(1) { num(11, 50); num(12, 40) } })
        assertEquals(40L, nested.remaining)
    }

    @Test fun coinBagIdsByShape() {
        val ids = Parsers.coinBagIds(pb { msg(2) { str(1, "6400-9f8e7d6c5b"); str(2, "不是袋") }; str(3, "6400_storyid") })
        assertEquals(listOf("6400-9f8e7d6c5b"), ids)
    }

    @Test fun coinBagIdsOwnShortHashFriendLongHashAndEmpty() {
        // 抓包实测 2026-10-17：自己的袋 id 是短 hash、好友的是长 hash，都要认；无袋回包为空 → 空列表
        val own = Parsers.coinBagIds(pb { msg(4) { str(1, "6400-6ad24a17-00374a34ffae050a") } })
        assertEquals(listOf("6400-6ad24a17-00374a34ffae050a"), own)
        val friend = Parsers.coinBagIds(pb { msg(4) { str(2, "6100-1a2b3c4d-0123456789abcdef0123456789abcdef012") } })
        assertEquals(listOf("6100-1a2b3c4d-0123456789abcdef0123456789abcdef012"), friend)
        assertEquals(emptyList<String>(), Parsers.coinBagIds(ByteArray(0)))
    }

    @Test fun pageRules() {
        val r = Parsers.pageRules(pb { str(1, "{\"count\": 7}"); msg(2) { msg(1) { num(1, 6000); num(2, 6400); num(3, 6401) } } })
        assertEquals(7, r.serverCount)
        assertEquals(true, r.allows(6000, 6400))
        assertEquals(false, r.allows(6000, 6100))
    }

    @Test fun pkPagePaging() {
        val page = Parsers.pkPage(pb {
            msg(1) { msg(1) { str(1, "旺旺"); str(8, "petA") }; msg(2) { num(1, 10001); str(2, "小明") }; msg(14) { num(4, 5000) } }
            num(2, 99); num(3, 1)
        })
        assertEquals(1, page.items.size)
        assertEquals("10001", page.items[0].uin)
        assertEquals("小明", page.items[0].nick)
        assertTrue(page.hasMore)
    }

    @Test fun mailListCountsAttachments() {
        val body = pb {
            msg(1) { str(1, "m1"); str(3, "邀请好友领养"); msg(7) { str(1, "act"); str(2, "tok"); msg(4) { num(1, 1); str(2, "点券"); num(4, 50) } } }
            msg(1) { str(1, "m2"); str(3, "公告") }
        }
        val mails = Parsers.mails(body)
        assertEquals(2, mails.size)
        assertTrue(mails[0].hasAttachment)
        assertEquals("点券", mails[0].items.single().name)
        assertEquals(50L, mails[0].items.single().amount)
        assertFalse(mails[1].hasAttachment)
        assertEquals(1L, Parsers.mailClaimable(body))
        assertEquals(0L, Parsers.mailClaimable(ByteArray(0)))
    }
}
