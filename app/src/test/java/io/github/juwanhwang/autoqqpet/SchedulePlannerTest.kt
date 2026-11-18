package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.BlockReason
import io.github.juwanhwang.autoqqpet.optimizer.DayPlanner
import io.github.juwanhwang.autoqqpet.optimizer.Kind
import io.github.juwanhwang.autoqqpet.optimizer.SchedulePlanner
import io.github.juwanhwang.autoqqpet.optimizer.Snapshot
import io.github.juwanhwang.autoqqpet.pet.AdventureInfo
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import io.github.juwanhwang.autoqqpet.pet.GoalType
import io.github.juwanhwang.autoqqpet.pet.JobInfo
import io.github.juwanhwang.autoqqpet.pet.Rule
import io.github.juwanhwang.autoqqpet.pet.RuleThen
import io.github.juwanhwang.autoqqpet.pet.RuleWhen
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.Step
import io.github.juwanhwang.autoqqpet.pet.StepKind
import io.github.juwanhwang.autoqqpet.pet.Wrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePlannerTest {

    private val course = CourseInfo("短课", 11, Attr.STRENGTH, 30, 100, 0, 0, 30, 1800, true)
    private val job = JobInfo("占卜", 3, 21, 0, false, 14, 0, 0, 45, 2700, 14, true)
    private val walk = AdventureInfo("附近走走", 0, 0, false, Attr.NONE, 0, 0, 0, 0, 1, 45, true)
    private fun snap(gold: Int = 2000) = Snapshot(gold, 80, 80, 0, listOf(course), listOf(job), 10, 10, adventures = listOf(walk))

    /** 学工满 8 小时（整体疲劳）且全部学工档都被判降效的快照：自动模式下这时只剩冒险。 */
    private fun snapFatigued() = Snapshot(
        2000, 80, 80, 480, listOf(course), listOf(job), 10, 10,
        adventures = listOf(walk), studyWorkTired = true, penalizedSubs = setOf(11L, 21L),
    )
    private val planner = SchedulePlanner(DayPlanner(Attr.STRENGTH, goldReserve = 300))

    private val sch = Schedule(
        wrap = Wrap.LOOP,
        steps = listOf(Step("a", StepKind.WORK, goal = GoalType.COUNT, n = 2), Step("b", StepKind.STUDY, goal = GoalType.COUNT, n = 1)),
    )

    @Test fun firstUnfinishedStepRuns() {
        val r = planner.decide(sch, 0, { 0 to 0 }, snap())
        assertEquals(Kind.WORK, r.decision.kind)
        assertEquals(0, r.cursor)
    }

    @Test fun completedStepAdvances() {
        val r = planner.decide(sch, 0, { st -> if (st.id == "a") 2 to 90 else 0 to 0 }, snap())
        assertEquals(Kind.STUDY, r.decision.kind)
        assertEquals(1, r.cursor)
    }

    @Test fun loopWrapsToFirstStep() {
        val r = planner.decide(sch, 1, { 5 to 500 }, snap())
        assertTrue(r.wrapped)
        assertEquals(0, r.cursor)
    }

    @Test fun wrapReportsIncompleteStepsForCarryOver() {
        // a 已做完、b 差一次没达标且当前做不了（目录里没课）：换轮时只有 b 要把进度带进新一轮，
        // a 必须留在旧轮作废——不然带错了，做完的步永远做不完、没做完的步又被清零（2026-10-29 群友实报）
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(
                Step("a", StepKind.WORK, goal = GoalType.COUNT, n = 2),
                Step("b", StepKind.STUDY, goal = GoalType.COUNT, n = 2),
            ),
        )
        val noCourses = Snapshot(2000, 80, 80, 0, emptyList(), listOf(job), 10, 10, adventures = listOf(walk))
        val r = planner.decide(s, 0, { st -> if (st.id == "a") 2 to 90 else 1 to 30 }, noCourses)
        assertTrue(r.wrapped)
        assertEquals(setOf("b"), r.incompleteOnWrap)
    }

    @Test fun wrapWithAllStepsCompleteCarriesNothing() {
        val r = planner.decide(sch, 1, { 5 to 500 }, snap())
        assertTrue(r.wrapped)
        assertTrue(r.incompleteOnWrap.isEmpty())
    }

    @Test fun stopFinishesRound() {
        val r = planner.decide(sch.copy(wrap = Wrap.STOP), 1, { 5 to 500 }, snap())
        assertTrue(r.finishedRound)
        assertEquals(Kind.IDLE, r.decision.kind)
    }

    @Test fun feeRuleRedirectsAndReportsNoMoney() {
        val s = Schedule(
            steps = listOf(Step("b", StepKind.STUDY, n = 1)),
            rules = listOf(Rule("r", RuleWhen.STUDY_FEE_UNAFFORDABLE, then = RuleThen.ADVENTURE)),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap(gold = 350))
        assertEquals(Kind.ADVENTURE, r.decision.kind)
        assertEquals(BlockReason.NO_MONEY, r.blocked)
    }

    // ---------- 排班按用户设定走：疲劳/降效不得越级拦步（公开仓 issue #6 / #5，2026-10-16 定） ----------

    @Test fun foreverStudyStepSurvivesFullFatigue() {
        // #6 现场：单步排班「学习 · 无限循环」，满 8 小时后旧版报 NOT_DOABLE 空转一整天
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(Step("a", StepKind.STUDY, goal = GoalType.FOREVER)),
        )
        val r = planner.decide(s, 0, { 5 to 225 }, snapFatigued())
        assertEquals(Kind.STUDY, r.decision.kind)
        assertEquals(BlockReason.NONE, r.blocked)
        assertEquals(0, r.cursor)
    }

    @Test fun countedStudyStepFinishesBeforeAdventureAfterFatigue() {
        // #5 追评现场：先学 16 次再冒险，学到第 10 次遇疲劳——剩下的 6 次必须先做完
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(
                Step("a", StepKind.STUDY, goal = GoalType.COUNT, n = 16),
                Step("b", StepKind.ADVENTURE, goal = GoalType.COUNT, n = 1),
            ),
        )
        val at10 = planner.decide(s, 0, { st -> if (st.id == "a") 10 to 450 else 0 to 0 }, snapFatigued())
        assertEquals(Kind.STUDY, at10.decision.kind)
        assertEquals(0, at10.cursor)
        // 第 16 次做完，才轮到下一步冒险
        val at16 = planner.decide(s, 0, { st -> if (st.id == "a") 16 to 720 else 0 to 0 }, snapFatigued())
        assertEquals(Kind.ADVENTURE, at16.decision.kind)
        assertEquals(1, at16.cursor)
    }

    @Test fun workStepSurvivesFullFatigue() {
        val s = Schedule(steps = listOf(Step("a", StepKind.WORK, goal = GoalType.COUNT, n = 2)))
        val r = planner.decide(s, 0, { 0 to 0 }, snapFatigued())
        assertEquals(Kind.WORK, r.decision.kind)
        assertEquals(BlockReason.NONE, r.blocked)
    }

    @Test fun zeroRatePinnedCourseStillRuns() {
        // 疲劳后目录读数变低、rate 算出来 ≤ 0：自动模式会放弃，用户钉死的课照上
        val zero = CourseInfo("零收益课", 12, Attr.INTEL, 0, 100, 10, 0, 30, 1800, true)
        val s = Schedule(steps = listOf(Step("a", StepKind.STUDY, sub = 12, goal = GoalType.FOREVER)))
        val snap = Snapshot(
            2000, 80, 80, 480, listOf(zero), emptyList(), 10, 10,
            adventures = listOf(walk), studyWorkTired = true, penalizedSubs = setOf(12L),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap)
        assertEquals(Kind.STUDY, r.decision.kind)
        assertEquals("零收益课", r.decision.detail)
    }

    @Test fun payTiredRuleStillRedirectsToAdventure() {
        // 疲劳要介入排班，只能经用户自己写的 PAY_TIRED 规则——写了就照规则办
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(
                Step("a", StepKind.STUDY, goal = GoalType.COUNT, n = 16),
                Step("b", StepKind.ADVENTURE, goal = GoalType.COUNT, n = 1),
            ),
            rules = listOf(Rule("r", RuleWhen.PAY_TIRED, then = RuleThen.ADVENTURE)),
        )
        val r = planner.decide(s, 0, { st -> if (st.id == "a") 10 to 450 else 0 to 0 }, snapFatigued())
        assertEquals(Kind.ADVENTURE, r.decision.kind)
        assertEquals(BlockReason.PAY_TIRED, r.blocked)
    }

    @Test fun payTiredRulePauseStops() {
        val s = Schedule(
            steps = listOf(Step("a", StepKind.STUDY, goal = GoalType.FOREVER)),
            rules = listOf(Rule("r", RuleWhen.PAY_TIRED, then = RuleThen.PAUSE)),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snapFatigued())
        assertEquals(Kind.IDLE, r.decision.kind)
        assertEquals(BlockReason.PAY_TIRED, r.blocked)
    }

    @Test fun payTiredRuleDoesNotBlockAdventureStep() {
        // 冒险是疲劳时的兜底：就算写了 PAY_TIRED 规则，轮到冒险步也不能再被疲劳赶走
        val s = Schedule(
            steps = listOf(Step("a", StepKind.ADVENTURE, goal = GoalType.FOREVER)),
            rules = listOf(Rule("r", RuleWhen.PAY_TIRED, then = RuleThen.PAUSE)),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snapFatigued())
        assertEquals(Kind.ADVENTURE, r.decision.kind)
    }

    // ---------- 钉选 = 组＋时长：学园升级整套换编号/名字也不能停摆（2026-10-27 真机） ----------

    @Test fun pinnedStudyFollowsAttrAndDurationAfterStageUpgrade() {
        // 现场回放：早上钉的领航员特训是 sub=6141001，中午学园升级后同名课变成 6142001（+26），旧编号在目录里消失
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(Step("a", StepKind.STUDY, attr = "力量", sub = 6141001, itemName = "领航员特训", dur = 45, goal = GoalType.FOREVER)),
        )
        val snap = Snapshot(
            2000, 80, 80, 0,
            listOf(
                CourseInfo("领航员特训", 6142001, Attr.STRENGTH, 26, 162, 0, 0, 45, 2700, true),
                CourseInfo("宗师格斗特训", 6142004, Attr.STRENGTH, 36, 486, 0, 0, 135, 8100, true),
                CourseInfo("巨星自我修养", 6142002, Attr.CHARM, 26, 162, 0, 0, 45, 2700, true),
            ),
            emptyList(), 10, 10, adventures = listOf(walk),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap)
        assertEquals(Kind.STUDY, r.decision.kind)
        assertEquals("领航员特训", r.decision.detail)
        assertEquals(6142001L, r.decision.sub)
        assertEquals(BlockReason.NONE, r.blocked)
    }

    @Test fun pinnedStudyPicksBestRateWithinSameAttrAndDuration() {
        // 同组同时长有两条：编号还认得出旧的那条，也要按当前读数选更划算的一条（与最优调度选条目同口径）
        val s = Schedule(
            steps = listOf(Step("a", StepKind.STUDY, attr = "力量", sub = 901, itemName = "旧课", dur = 45, goal = GoalType.FOREVER)),
        )
        val snap = Snapshot(
            2000, 80, 80, 0,
            listOf(
                CourseInfo("旧课", 901, Attr.STRENGTH, 20, 100, 0, 0, 45, 2700, true),
                CourseInfo("新课", 902, Attr.STRENGTH, 30, 100, 0, 0, 45, 2700, true),
            ),
            emptyList(), 10, 10, adventures = listOf(walk),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap)
        assertEquals(Kind.STUDY, r.decision.kind)
        assertEquals("新课", r.decision.detail)
        assertEquals(902L, r.decision.sub)
    }

    @Test fun pinnedStudyBlocksWhenNoCourseOfThatDuration() {
        // 组还在、时长档没了（只剩 135 分钟的课）：这才是真的做不了，照旧报 NO_SUCH_SUB
        val s = Schedule(
            steps = listOf(Step("a", StepKind.STUDY, attr = "力量", sub = 6141001, itemName = "领航员特训", dur = 45, goal = GoalType.FOREVER)),
        )
        val snap = Snapshot(
            2000, 80, 80, 0,
            listOf(CourseInfo("宗师格斗特训", 6142004, Attr.STRENGTH, 36, 486, 0, 0, 135, 8100, true)),
            emptyList(), 10, 10, adventures = listOf(walk),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap)
        assertEquals(Kind.IDLE, r.decision.kind)
        assertEquals(BlockReason.NO_SUCH_SUB, r.blocked)
    }

    @Test fun legacyStepWithoutDurationMigratesByName() {
        // 旧排班没有 dur：编号已失效，靠当年选中的名字在新目录里读出时长（45 分钟），
        // 不能因为 135 分钟那条 rate 更高就把时长也换了
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(Step("a", StepKind.STUDY, attr = "力量", sub = 6141001, itemName = "领航员特训", goal = GoalType.FOREVER)),
        )
        val snap = Snapshot(
            2000, 80, 80, 0,
            listOf(
                CourseInfo("领航员特训", 6142001, Attr.STRENGTH, 26, 162, 0, 0, 45, 2700, true),
                CourseInfo("宗师格斗特训", 6142004, Attr.STRENGTH, 80, 486, 0, 0, 135, 8100, true),
            ),
            emptyList(), 10, 10, adventures = listOf(walk),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap)
        assertEquals(Kind.STUDY, r.decision.kind)
        assertEquals("领航员特训", r.decision.detail)
        assertEquals(6142001L, r.decision.sub)
    }

    @Test fun pinnedWorkFollowsCareerAndDurationAfterUpgrade() {
        val s = Schedule(
            steps = listOf(Step("a", StepKind.WORK, career = 7, sub = 6471001, itemName = "旧岗位", dur = 45, goal = GoalType.FOREVER)),
        )
        val snap = Snapshot(
            2000, 80, 80, 0, emptyList(),
            listOf(
                JobInfo("新岗位", 7, 6472001, 0, false, 41, 0, 0, 45, 2700, 41, true),
                JobInfo("长岗位", 7, 6472002, 0, false, 200, 0, 0, 120, 7200, 200, true),
            ),
            10, 10, adventures = listOf(walk),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap)
        assertEquals(Kind.WORK, r.decision.kind)
        assertEquals("新岗位", r.decision.detail)
        assertEquals(6472001L, r.decision.sub)
    }

    @Test fun parseKeepsPinnedDurationAndDefaultsLegacy() {
        val s = Schedule(
            steps = listOf(Step("a", StepKind.STUDY, attr = "力量", sub = 5, itemName = "某课", dur = 45, goal = GoalType.FOREVER, n = 0)),
        )
        assertEquals(s, Schedule.parse(Schedule.encode(s)))
        val legacy = Schedule.parse("{\"v\":1,\"wrap\":\"loop\",\"steps\":[{\"id\":\"x\",\"kind\":\"STUDY\",\"attr\":\"力量\",\"sub\":6141001,\"item\":\"领航员特训\",\"goal\":\"FOREVER\"}]}")
        assertEquals(0, legacy.steps[0].dur)
        assertEquals(6141001L, legacy.steps[0].sub)
    }

    @Test fun parseSanitizesForeverOnlyLast() {
        val json = "{\"v\":1,\"wrap\":\"loop\",\"steps\":[{\"id\":\"x\",\"kind\":\"WORK\",\"goal\":\"FOREVER\"},{\"id\":\"y\",\"kind\":\"STUDY\",\"goal\":\"FOREVER\"}]}"
        val p = Schedule.parse(json)
        assertEquals(GoalType.COUNT, p.steps[0].goal)
        assertEquals(GoalType.FOREVER, p.steps[1].goal)
        val again = Schedule.parse(Schedule.encode(p))
        assertEquals(p, again)
        assertEquals(Schedule.OFF, Schedule.parse("{\"v\":99}"))
        assertEquals(Schedule.OFF, Schedule.parse("not json"))
    }

    @Test fun disabledStepIsSkipped() {
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(
                Step("a", StepKind.WORK, goal = GoalType.COUNT, n = 2, enabled = false),
                Step("b", StepKind.STUDY, goal = GoalType.COUNT, n = 1),
            ),
        )
        val r = planner.decide(s, 0, { 0 to 0 }, snap())
        assertEquals(Kind.STUDY, r.decision.kind)
        assertEquals(1, r.cursor)
    }

    @Test fun disabledStepKeepsProgressAndExcludesFromWrapCarry() {
        // a 做完了、b 禁用中：换轮的待带进度里不该有 b（它没在跑，进度原样留着即可）
        val s = Schedule(
            wrap = Wrap.LOOP,
            steps = listOf(
                Step("a", StepKind.WORK, goal = GoalType.COUNT, n = 1),
                Step("b", StepKind.STUDY, goal = GoalType.COUNT, n = 3, enabled = false),
            ),
        )
        val r = planner.decide(s, 0, { st -> if (st.id == "a") 1 to 45 else 0 to 0 }, snap())
        assertTrue(r.wrapped)
        assertTrue("禁用步不应进待带进度：${r.incompleteOnWrap}", "b" !in r.incompleteOnWrap)
    }

    @Test fun allDisabledFallsBackToIdleAndUnusable() {
        val s = Schedule(
            steps = listOf(Step("a", StepKind.WORK, goal = GoalType.COUNT, n = 1, enabled = false)),
        )
        assertTrue("全禁用时排班应视为不可用（上层回退自动模式）", !s.usable)
        val r = planner.decide(s, 0, { 0 to 0 }, snap())
        assertEquals(Kind.IDLE, r.decision.kind)
    }

    @Test fun disabledFlagSurvivesEncodeParseAndDefaultsOn() {
        val s = Schedule(
            steps = listOf(
                Step("a", StepKind.WORK, goal = GoalType.COUNT, n = 1, enabled = false),
                Step("b", StepKind.STUDY, goal = GoalType.COUNT, n = 1),
            ),
        )
        val p = Schedule.parse(Schedule.encode(s))
        assertEquals(s, p)
        // 旧排班 JSON 没有 enabled 字段：一律按启用读，不能把老用户的步全禁了
        val legacy = Schedule.parse("""{"v":1,"wrap":"loop","steps":[{"id":"a","kind":"WORK","goal":"COUNT","n":1}],"rules":[]}""")
        assertTrue(legacy.steps.single().enabled)
        assertTrue(legacy.usable)
    }
}
