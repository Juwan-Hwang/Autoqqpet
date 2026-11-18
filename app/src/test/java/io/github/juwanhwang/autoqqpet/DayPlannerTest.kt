package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.DayPlanner
import io.github.juwanhwang.autoqqpet.optimizer.Kind
import io.github.juwanhwang.autoqqpet.optimizer.Snapshot
import io.github.juwanhwang.autoqqpet.pet.AdventureInfo
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import io.github.juwanhwang.autoqqpet.pet.JobInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DayPlannerTest {

    private fun course(name: String, sub: Long, gain: Int, min: Int, fee: Int = 100, attr: Attr = Attr.STRENGTH) =
        CourseInfo(name, sub, attr, gain, fee, 0, 0, min, min * 60L, true)

    private fun job(name: String, sub: Long, points: Int, min: Int, income: Int = 0) =
        JobInfo(name, 1, sub, income, income > 0, points, 0, 0, min, min * 60L, points, true)

    private val walk = AdventureInfo("附近走走", 0, 0, false, Attr.NONE, 0, 0, 0, 0, 1, 45, true)

    private fun snap(
        courses: List<CourseInfo> = emptyList(), jobs: List<JobInfo> = emptyList(), adv: List<AdventureInfo> = listOf(walk),
        gold: Int = 2000, stamina: Int = 80, clean: Int = 80, penalized: Set<Long> = emptySet(),
    ) = Snapshot(gold, stamina, clean, 0, courses, jobs, 10, 10, adventures = adv, penalizedSubs = penalized)

    @Test fun careFloorsComeFirst() {
        val p = DayPlanner(Attr.STRENGTH, staminaFloor = 30, cleanFloor = 30)
        assertEquals(Kind.FEED, p.decide(snap(stamina = 10)).kind)
        assertEquals(Kind.BATH, p.decide(snap(clean = 10)).kind)
        // 服务端拒收照顾时不拿它卡死
        assertEquals(Kind.STUDY, p.decide(snap(courses = listOf(course("c", 1, 30, 30)), stamina = 10).copy(feedBlocked = true)).kind)
    }

    @Test fun picksHigherRateAndStudyTieBreak() {
        val p = DayPlanner(Attr.STRENGTH)
        // 学习 60/h vs 打工 14 点/45min*0.2 ≈ 3.7/h → 学习
        val d = p.decide(snap(courses = listOf(course("短课", 11, 30, 30)), jobs = listOf(job("占卜", 21, 14, 45))))
        assertEquals(Kind.STUDY, d.kind)
        assertEquals(11L, d.sub)
    }

    @Test fun workFirstOverridesStudy() {
        val p = DayPlanner(Attr.STRENGTH, workFirst = true)
        val d = p.decide(snap(courses = listOf(course("短课", 11, 30, 30)), jobs = listOf(job("占卜", 21, 14, 45))))
        assertEquals(Kind.WORK, d.kind)
    }

    @Test fun learnedGoldMakesWorkCompetitive() {
        val p = DayPlanner(Attr.STRENGTH)
        // 打工实收 400 金币/10min → 400/40*6=60/h + 工分；比 30/h 的课高出 tie-break 区间
        val d = p.decide(snap(courses = listOf(course("长课", 11, 15, 30)), jobs = listOf(job("占卜", 21, 3, 10, income = 400))))
        assertEquals(Kind.WORK, d.kind)
    }

    @Test fun reserveBlocksUnaffordableCourse() {
        val p = DayPlanner(Attr.STRENGTH, goldReserve = 300)
        val d = p.decide(snap(courses = listOf(course("贵课", 11, 30, 30, fee = 500)), jobs = listOf(job("占卜", 21, 14, 45)), gold = 600))
        assertEquals(Kind.WORK, d.kind)
    }

    @Test fun adventureOnlyWhenStudyWorkPenalized() {
        val p = DayPlanner(Attr.STRENGTH)
        val s = snap(courses = listOf(course("短课", 11, 30, 30)), jobs = listOf(job("占卜", 21, 14, 45)), penalized = setOf(11L, 21L))
        assertEquals(Kind.ADVENTURE, p.decide(s).kind)
    }

    @Test fun adventureNeverBeatsDoableStudyOrWork() {
        val p = DayPlanner(Attr.STRENGTH)
        // 45 秒冒险按基线折算 40/h，远高于 3.7/h 的打工，但冒险是最低优先级（issue #4）
        assertEquals(Kind.WORK, p.decide(snap(jobs = listOf(job("占卜", 21, 14, 45)))).kind)
    }

    @Test fun nothingToDoGoesPkOrIdle() {
        assertEquals(Kind.PK, DayPlanner(Attr.STRENGTH, pkEnabled = true).decide(snap(adv = emptyList())).kind)
        assertEquals(Kind.IDLE, DayPlanner(Attr.STRENGTH, pkEnabled = false).decide(snap(adv = emptyList())).kind)
    }

    @Test fun adventureBlockedWhenGoldCapped() {
        val p = DayPlanner(Attr.STRENGTH)
        assertEquals(Kind.ADVENTURE, p.decide(snap()).kind)
        // 金币到顶：次数没用完也停冒险；没别的能做的就无事可做（关 PK 隔离变量），有学工照常做学工
        assertEquals(Kind.IDLE, DayPlanner(Attr.STRENGTH, pkEnabled = false).decide(snap().copy(advGoldCapped = true)).kind)
        assertEquals(Kind.STUDY, p.decide(snap(courses = listOf(course("短课", 11, 30, 30))).copy(advGoldCapped = true)).kind)
    }
}
