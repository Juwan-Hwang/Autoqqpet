package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.SceneEconomics
import io.github.juwanhwang.autoqqpet.pet.SceneOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEconomicsTest {

    @Test fun durationSecondsAndMinutes() {
        assertEquals(45L, SceneEconomics.durationSecondsOf("45秒"))         // 曾被当 45 分钟（排期错 60 倍）
        assertEquals(1, SceneEconomics.durationMinutes("45秒"))
        assertEquals(600L, SceneEconomics.durationSecondsOf("10分钟"))
        assertEquals(5400L, SceneEconomics.durationSecondsOf("1小时30分"))
        assertEquals(7200L, SceneEconomics.durationSecondsOf("2小时"))
        assertEquals(240, SceneEconomics.durationMinutes("4小时"))
        assertEquals(30, SceneEconomics.durationMinutes("30"))
        assertEquals(0L, SceneEconomics.durationSecondsOf(""))
    }

    @Test fun parseCourse() {
        val c = SceneEconomics.parseCourse(SceneOption("武术身法课", 6110001, true, cost = "141(当前 900) 体力10 清洁5", duration = "30分钟", reward = "力量+30[提示](mqqapi://x)"))
        assertEquals(Attr.STRENGTH, c.attr)
        assertEquals(30, c.attrGain)
        assertEquals(141, c.fee)
        assertEquals(10, c.staminaCost)
        assertEquals(5, c.cleanCost)
        assertEquals(30, c.durationMin)
    }

    @Test fun parseJobOnlyTrustsExplicitGold() {
        val j = SceneEconomics.parseJob(SceneOption("街角占卜摊", 6431101, true, cost = "体力14 清洁7", duration = "45分钟", extra = "+14点"), 3)
        assertFalse(j.incomeKnown)
        assertEquals(14, j.points)
        assertEquals(45, j.durationMin)
        assertEquals(14 * 60.0 / 45, j.pointsPerHour, 1e-9)
        val g = SceneEconomics.parseJob(SceneOption("x", 1, true, duration = "10分钟", reward = "金币+50"), 1)
        assertTrue(g.incomeKnown)
        assertEquals(50, g.income)
    }

    @Test fun parseJobReadsCatalogBaseAndBonusCap() {
        // 2026-10-31 抓包原文形态：f8 = 图标 markdown + 本金；f12 = 图标 + 区间（雇佣金币加成：0～上限）
        val icon = "![#20px #20px](https://qqpet.gtimg.com/pet-outdoor/result/icon/1776409721409.png)"
        val self = SceneEconomics.parseJob(SceneOption("陪练基础课", 6451102, true,
            cost = "体力10 清洁5", duration = "45分钟", reward = "$icon 290",
            extra = "+54点", wageRange = "$icon 290～$icon 435（雇佣金币加成：0～145）"), 5)
        assertTrue(self.incomeKnown)
        assertEquals(290, self.income)
        assertEquals(145, self.hireBonusCap)
        // 带好友读同一岗位：本金不变、上限是那位好友的（好友B 45 分钟档实测 121）
        val friend = SceneEconomics.parseJob(SceneOption("陪练基础课", 6451202, true,
            duration = "45分钟", reward = "$icon 302", wageRange = "$icon 302～$icon 423（雇佣金币加成：0～121）"), 5)
        assertEquals(302, friend.income)
        assertEquals(121, friend.hireBonusCap)
        // 没给工资区间：上限未知（-1），本金照常
        val bare = SceneEconomics.parseJob(SceneOption("陪练基础课", 6451202, true, duration = "45分钟", reward = "$icon 302"), 5)
        assertEquals(302, bare.income)
        assertEquals(-1, bare.hireBonusCap)
        // f8 不是「图标+纯数字」形态：不猜，回退未知
        val odd = SceneEconomics.parseJob(SceneOption("x", 1, true, duration = "10分钟", reward = "奖励见详情 2"), 1)
        assertFalse(odd.incomeKnown)
    }

    @Test fun adventureDefaultsTo30MinWhenUnknown() {
        val a = SceneEconomics.parseAdventure(SceneOption("附近走走", 0, true, duration = "45秒"))
        assertEquals(45L, a.durationSec)
        assertEquals(1, a.durationMin)
        val b = SceneEconomics.parseAdventure(SceneOption("?", 0, true, duration = ""))
        assertEquals(1800L, b.durationSec)
    }
}
