package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.AutoGoal
import io.github.juwanhwang.autoqqpet.optimizer.OptimalPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 求解器回归：主用例直接采用外人 issue（qq-pet-copilot#11）公布的输入与精确最优解——
 * 能复现它，说明三档疲劳分段、补给支配与期末约束的建模都对得上。
 * issue 输入：课 20 分钟/学费 70/耗体力 10/耗清洁 4；工 10 分钟/+77 金币/耗 4/2；
 * 饼干 5 金币 +10 体力、香皂片 2 金币 +10 清洁（虾仁/沐浴球被单价支配，不参与）；
 * 起点金币 701、当日已累计学工 60 分钟、剩余 1380 分钟、期末金币不低于 701。
 * 其最优解：学习 18 次、打工 102 次、饼干 59、香皂片 28、期末 707、疲劳折算学习 17.1 次。
 * （由消耗总量反推，其模型的体力/清洁起点为 0：59×10≥588、28×10≥276 恰好严丝合缝。）
 */
class OptimalPlannerTest {

    private fun issueInput(stamina: Int = 0, clean: Int = 0) = OptimalPlanner.Input(
        goal = AutoGoal.ATTR, nowMin = 60, fatigueMin = 60, gold = 701,
        stamina = stamina, clean = clean, dayStartGold = 701, reserveGold = 0,
        courses = listOf(OptimalPlanner.CourseIn("课", 1, targetGain = 16.0, fee = 70, staminaCost = 10, cleanCost = 4, durationMin = 20)),
        jobs = listOf(OptimalPlanner.JobIn("工", 2, income = 77.0, points = 0.0, staminaCost = 4, cleanCost = 2, durationMin = 10)),
        adventures = emptyList(), advPoolLeft = 0.0, biscuitPrice = 5, soapPrice = 2,
    )

    @Test fun reproducesIssueOptimum() {
        val plan = OptimalPlanner.solve(issueInput(), timeBudgetMs = 120_000)!!
        val m = "实际计划：${plan.summary}"
        assertEquals(m, 18, plan.studies)
        assertEquals(m, 102, plan.works)
        assertEquals(m, 59, plan.feeds)
        assertEquals(m, 28, plan.baths)
        assertEquals(m, 707.0, plan.endGold, 0.01)
        assertEquals(m, 17.1 * 16, plan.totalAttr, 0.01)
    }

    @Test fun tierFactorSegments() {
        assertEquals(1.0, OptimalPlanner.tierFactor(0, 60), 1e-9)
        assertEquals(0.25, OptimalPlanner.tierFactor(480, 60), 1e-9)
        assertEquals(0.10, OptimalPlanner.tierFactor(720, 60), 1e-9)
        // 跨 480 线：前 10 分钟满档、后 10 分钟 25%
        assertEquals((10 + 10 * 0.25) / 20, OptimalPlanner.tierFactor(470, 20), 1e-9)
        // 跨 720 线：前 10 分钟 25%、后 10 分钟 10%
        assertEquals((10 * 0.25 + 10 * 0.10) / 20, OptimalPlanner.tierFactor(710, 20), 1e-9)
    }

    @Test fun fullTierEquivalentRestoresDiscountedGold() {
        // 满档段：原样
        assertEquals(144L, OptimalPlanner.fullTierEquivalent(144, 0, 10))
        // 25% 档：36 → 144
        assertEquals(144L, OptimalPlanner.fullTierEquivalent(36, 480, 10))
        // 10% 档：14 → 140
        assertEquals(140L, OptimalPlanner.fullTierEquivalent(14, 720, 10))
        // 跨 480 线（前 10 分钟满档、后 10 分钟 25%，倍率 0.625）：90 → 144
        assertEquals(144L, OptimalPlanner.fullTierEquivalent(90, 470, 20))
        // 段信息不全：不还原
        assertEquals(50L, OptimalPlanner.fullTierEquivalent(50, -5, 10))
        assertEquals(50L, OptimalPlanner.fullTierEquivalent(50, 100, 0))
    }

    @Test fun goldGoalPicksHighRateLongJobWhenPoolIsSmall() {
        // 2026-10-30 用户实测之问：金币优先下 45 分钟高收益工（满档 360）必须进计划。
        // 池子只有 100 金币：冒险刷干就没了，剩余时间靠打工赚钱，第一步应是那趟长工。
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.GOLD, nowMin = 0, fatigueMin = 0, gold = 1000,
            stamina = 100, clean = 100, dayStartGold = 1000, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(OptimalPlanner.JobIn("长工", 7, income = 360.0, points = 40.0, staminaCost = 10, cleanCost = 10, durationMin = 45)),
            adventures = listOf(OptimalPlanner.AdventureIn("附近走走", 9, evGold = 10.0, cycleMin = 1.0, staminaCost = 1, cleanCost = 1)),
            advPoolLeft = 100.0, biscuitPrice = 5, soapPrice = 2, endMin = 120,
        )
        val plan = OptimalPlanner.solve(inp)!!
        assertEquals("实际计划：${plan.summary}", OptimalPlanner.ActKind.WORK, plan.first?.kind)
        assertTrue("实际计划：${plan.summary}", plan.works >= 1)
        assertTrue("实际计划：${plan.summary}", plan.endGold >= 1800.0)
    }

    @Test fun goldGoalIncludesAdventureUntilPoolDrains() {
        // 冒险期望 30 金币/次、1 分钟一趟，池子 90；打工 10 分钟 +77。纯金币目标下应先把池子刷干再打工
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.GOLD, nowMin = 0, fatigueMin = 0, gold = 0,
            stamina = 100, clean = 100, dayStartGold = 0, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(OptimalPlanner.JobIn("工", 2, income = 77.0, points = 0.0, staminaCost = 0, cleanCost = 0, durationMin = 10)),
            adventures = listOf(OptimalPlanner.AdventureIn("附近走走", 3, evGold = 30.0, cycleMin = 1.0)),
            advPoolLeft = 90.0, biscuitPrice = 5, soapPrice = 2, endMin = 60,
        )
        val plan = OptimalPlanner.solve(inp)!!
        assertEquals(3, plan.adventures)                 // 90 / 30 = 3 趟刷干
        assertTrue(plan.endGold > 90.0)                  // 剩余时间继续打工挣钱
    }

    @Test fun pointsGoalRespectsEndGoldFloor() {
        // 高工分工不给钱、还耗体力要买饼干；没钱垫底时不能光刷工分把金币花穿
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.POINTS, nowMin = 0, fatigueMin = 0, gold = 100,
            stamina = 100, clean = 100, dayStartGold = 100, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(
                // 刷分工收入未知（incomeKnown=false）：净亏刹车不拦它，本测试专验期末底线怎么兜住这种工
                OptimalPlanner.JobIn("刷分", 2, income = 0.0, points = 50.0, staminaCost = 50, cleanCost = 0, durationMin = 10, incomeKnown = false),
                OptimalPlanner.JobIn("挣钱", 3, income = 60.0, points = 1.0, staminaCost = 0, cleanCost = 0, durationMin = 10),
            ),
            adventures = emptyList(), advPoolLeft = 0.0, biscuitPrice = 5, soapPrice = 2, endMin = 120,
        )
        val plan = OptimalPlanner.solve(inp)!!
        assertTrue("期末金币应不低于基线 100，实际 ${plan.endGold}", plan.endGold >= 100.0 - 1e-9)
        assertTrue("应刷到工分，实际 ${plan.totalPoints}", plan.totalPoints > 0.0)
    }

    @Test fun pointsBrakeSkipsNetLossJobs() {
        // 收入 20、补给耗费折钱 5.8（体力 10→饼干 5 + 清洁 4→香皂 0.8）：全额档净赚，
        // 25% 档收入只剩 5 < 5.8 净亏 → 刹车后只做满 8 趟全额工（480 分钟），不再拿盈余补贴深档
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.POINTS, nowMin = 0, fatigueMin = 0, gold = 1000,
            stamina = 100, clean = 100, dayStartGold = 1000, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(OptimalPlanner.JobIn("低薪", 1, income = 20.0, points = 5.0, staminaCost = 10, cleanCost = 4, durationMin = 60)),
            adventures = emptyList(), advPoolLeft = 0.0, biscuitPrice = 5, soapPrice = 2, endMin = 1440,
        )
        val plan = OptimalPlanner.solve(inp)!!
        assertEquals(8, plan.works)
        assertEquals(40.0, plan.totalPoints, 1e-9)
        assertTrue(plan.endGold >= 1000.0)
    }

    @Test fun pointsBrakeSparesUnknownIncome() {
        // 同上但收入未知：刹车不拦，靠体力存量能做满 10 趟（第 11 趟要买饼干、期末金币会跌破底线）
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.POINTS, nowMin = 0, fatigueMin = 0, gold = 1000,
            stamina = 100, clean = 100, dayStartGold = 1000, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(OptimalPlanner.JobIn("低薪", 1, income = 0.0, points = 5.0, staminaCost = 10, cleanCost = 4, durationMin = 60, incomeKnown = false)),
            adventures = emptyList(), advPoolLeft = 0.0, biscuitPrice = 5, soapPrice = 2, endMin = 1440,
        )
        val plan = OptimalPlanner.solve(inp)!!
        assertEquals(10, plan.works)
        assertEquals(42.5, plan.totalPoints, 1e-9)
    }

    @Test fun attrStudyBrakeStopsAtTenthBand() {
        // 免费课（隔离资金约束）：全额档 8 节、25% 档 4 节；停课线 no10（系数 <0.24 不排）→ 12 节、属性 144
        fun input(factor: Double) = OptimalPlanner.Input(
            goal = AutoGoal.ATTR, nowMin = 0, fatigueMin = 0, gold = 10000,
            stamina = 100, clean = 100, dayStartGold = 0, reserveGold = 0,
            courses = listOf(OptimalPlanner.CourseIn("课", 1, targetGain = 16.0, fee = 0, staminaCost = 0, cleanCost = 0, durationMin = 60)),
            jobs = emptyList(), adventures = emptyList(), advPoolLeft = 0.0,
            biscuitPrice = 5, soapPrice = 2, endMin = 1440, studyMinFactor = factor,
        )
        val no10 = OptimalPlanner.solve(input(0.24))!!
        assertEquals(12, no10.studies)
        assertEquals(144.0, no10.totalAttr, 1e-9)
        val full = OptimalPlanner.solve(input(0.99))!!
        assertEquals(8, full.studies)
        assertEquals(128.0, full.totalAttr, 1e-9)
        val none = OptimalPlanner.solve(input(0.0))!!
        assertTrue("不设线应学得更多，实际 ${none.studies}", none.studies > 12)
    }

    @Test fun pointsGoalWithFastAdventureWorksInsteadOfTreadmill() {
        // 2026-10-17 真机实测的局面：工分优先，45 分钟一趟的工（54 工分）与 45 秒一趟的冒险
        // （期望 12.6、池子 5375）并存。冒险逐趟展开时搜索被冒险链淹没、240 次求解全部触顶，
        // 计划第一步永远是冒险、整个上午 0 趟工。按批展开后必须算得完、且第一步是打工。
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.POINTS, nowMin = 229, fatigueMin = 90, gold = 25000,
            stamina = 100, clean = 100, dayStartGold = 25000, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(OptimalPlanner.JobIn("校园小通告", 2, income = 302.0, points = 54.0, staminaCost = 10, cleanCost = 10, durationMin = 45)),
            adventures = listOf(OptimalPlanner.AdventureIn("附近走走", 3, evGold = 12.6, cycleMin = 0.75, staminaCost = 5, cleanCost = 5)),
            advPoolLeft = 5375.0, biscuitPrice = 5, soapPrice = 2, endMin = 1440,
        )
        val plan = OptimalPlanner.solve(inp, timeBudgetMs = 120_000)!!
        assertTrue("不应触顶：${plan.summary}", !plan.summary.contains("近似解"))
        assertEquals("第一步应是打工：${plan.summary}", OptimalPlanner.ActKind.WORK, plan.first?.kind)
        assertTrue("工应排满大半天：${plan.summary}", plan.works >= 15)
        assertTrue("冒险只填剩余空档：${plan.summary}", plan.adventures in 1..200)
    }

    @Test fun adventureBatchDrainsPoolInGoldGoal() {
        // 只有冒险时按批展开也要把池子刷干：5375 的池子、每趟期望 12.6 → 约 427 趟
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.GOLD, nowMin = 0, fatigueMin = 0, gold = 0,
            stamina = 100, clean = 100, dayStartGold = 0, reserveGold = 0,
            courses = emptyList(), jobs = emptyList(),
            adventures = listOf(OptimalPlanner.AdventureIn("附近走走", 3, evGold = 12.6, cycleMin = 0.75)),
            advPoolLeft = 5375.0, biscuitPrice = 5, soapPrice = 2, endMin = 1440,
        )
        val plan = OptimalPlanner.solve(inp, timeBudgetMs = 120_000)!!
        assertTrue("不应触顶：${plan.summary}", !plan.summary.contains("近似解"))
        assertTrue("应刷干池子：${plan.summary}", plan.adventures in 420..435)
        assertEquals(5375.0, plan.endGold, 13.0)
    }

    @Test fun emptyCatalogReturnsNull() {
        val inp = issueInput().copy(courses = emptyList(), jobs = emptyList())
        assertEquals(null, OptimalPlanner.solve(inp))
    }

    // ——— 2026-10-18 真机复现：工分优先在冒险洪水下选错岗位 ———
    // 2026-10-18 00:01 起真机连选 7 次 10 分钟的「日常拍摄通告」（10 工分），不选 45 分钟的
    // 「商场临时商演」（54 工分）：跨天结算启用新的冒险活池种子（EV≈12.3）且金币池回满，
    // 搜索被冒险链撑爆触顶，返回的近似计划第一步被短工时岗位占据。老逻辑按单位时间
    // 收益选岗本来选得对（商演 54/45=1.2 工分/分钟 > 日常 10/10=1.0）。
    // 输入按 01:11 诊断快照校准：岗位 4 个（同一职业，日常在目录首位）、金币 26797、
    // 预留金 300、目标工分；商演金币目录未回、用结算学习均值 32 补上（≥2 样本时
    // CatalogReader 就是这么喂的），其余两岗金币未知；消耗为目录近似值。

    private fun snapshotCourses() = listOf(
        OptimalPlanner.CourseIn("领航员特训", 22, targetGain = 0.0, fee = 162, staminaCost = 12, cleanCost = 8, durationMin = 45),
        OptimalPlanner.CourseIn("巨星自我修养", 21, targetGain = 32.0, fee = 162, staminaCost = 15, cleanCost = 10, durationMin = 45),
        OptimalPlanner.CourseIn("灵犀演说特训", 23, targetGain = 0.0, fee = 162, staminaCost = 10, cleanCost = 12, durationMin = 45),
        OptimalPlanner.CourseIn("宗师格斗", 24, targetGain = 0.0, fee = 486, staminaCost = 30, cleanCost = 22, durationMin = 135),
        OptimalPlanner.CourseIn("歌剧院首席", 25, targetGain = 80.0, fee = 486, staminaCost = 36, cleanCost = 25, durationMin = 135),
        OptimalPlanner.CourseIn("策略模拟", 26, targetGain = 0.0, fee = 486, staminaCost = 28, cleanCost = 20, durationMin = 135),
        OptimalPlanner.CourseIn("云顶夏令营", 27, targetGain = 0.0, fee = 486, staminaCost = 33, cleanCost = 28, durationMin = 135),
    )

    private fun snapshotJobs() = listOf(
        OptimalPlanner.JobIn("日常拍摄通告", 11, income = 77.0, points = 10.0, staminaCost = 8, cleanCost = 6, durationMin = 10),
        OptimalPlanner.JobIn("商场临时商演", 12, income = 32.0, points = 54.0, staminaCost = 14, cleanCost = 9, durationMin = 45),
        OptimalPlanner.JobIn("粉丝会预录整备", 13, income = 0.0, points = 96.0, staminaCost = 26, cleanCost = 18, durationMin = 120, incomeKnown = false),
        OptimalPlanner.JobIn("连夜准备首秀", 14, income = 0.0, points = 156.0, staminaCost = 40, cleanCost = 30, durationMin = 240, incomeKnown = false),
    )

    private fun snapshotInput(
        goal: AutoGoal, pool: Double = 5375.0, jobs: List<OptimalPlanner.JobIn> = snapshotJobs(),
    ) = OptimalPlanner.Input(
        goal = goal, nowMin = 1, fatigueMin = 0, gold = 26797,
        stamina = 100, clean = 100, dayStartGold = 26797, reserveGold = 300,
        courses = snapshotCourses(), jobs = jobs,
        adventures = listOf(OptimalPlanner.AdventureIn("附近走走", 3, evGold = 12.3, cycleMin = 0.79, staminaCost = 5, cleanCost = 5)),
        advPoolLeft = pool, biscuitPrice = 5, soapPrice = 2,
        studyMinFactor = if (goal == AutoGoal.ATTR) 0.24 else 0.0,
    )

    @Test fun pointsSnapshotFirstIsTopRateJob() {
        val plan = OptimalPlanner.solve(snapshotInput(AutoGoal.POINTS), timeBudgetMs = 120_000)!!
        assertEquals("第一步应是工分/分钟最高的商场临时商演：${plan.summary}",
            OptimalPlanner.ActKind.WORK, plan.first?.kind)
        assertEquals("第一步应是商场临时商演：${plan.summary}", 12L, plan.first?.sub)
        assertTrue("总工分不应低于贪心水平：${plan.summary}", plan.totalPoints >= 600.0)
    }

    @Test fun pointsSnapshotFirstIsTopRateJobReversedOrder() {
        val plan = OptimalPlanner.solve(snapshotInput(AutoGoal.POINTS, jobs = snapshotJobs().reversed()), timeBudgetMs = 120_000)!!
        assertEquals("目录逆序时第一步仍应是商场临时商演：${plan.summary}", 12L, plan.first?.sub)
    }

    @Test fun pointsDeepPoolFirstStaysTopRateJob() {
        // 池深是「最近一次到顶观测」标定的：池子更深时冒险洪水更猛，旧版在这组输入上
        // 第一步会翻到日常拍摄通告（本地复现：工分只剩约 454）。修后必须仍是商演。
        val plan = OptimalPlanner.solve(snapshotInput(AutoGoal.POINTS, pool = 10000.0), timeBudgetMs = 120_000)!!
        assertEquals("深池时第一步仍应是商场临时商演：${plan.summary}", 12L, plan.first?.sub)
    }

    @Test fun pointsTinyBudgetGreedyFloorStillTopRateJob() {
        // 展开预算人为压到 300：DP 根本搜不完，此时返回的计划只能靠贪心保底——
        // 第一步仍必须是单位时间工分最高的商场临时商演（优化版不输旧逻辑的硬保证）。
        val plan = OptimalPlanner.solve(snapshotInput(AutoGoal.POINTS), maxOffers = 300)!!
        assertEquals("小预算下第一步仍应是商场临时商演：${plan.summary}", 12L, plan.first?.sub)
    }

    @Test fun attrSnapshotFirstIsTopRateCharmCourse() {
        // 学习侧同场景：属性优先（魅力）、停课线 no10；45 分钟课 0.71 属性/分钟
        // 高于 135 分钟课的 0.59，第一步应是「巨星自我修养」，不是长课也不是冒险。
        val plan = OptimalPlanner.solve(snapshotInput(AutoGoal.ATTR), timeBudgetMs = 120_000)!!
        assertEquals("第一步应是学习：${plan.summary}", OptimalPlanner.ActKind.STUDY, plan.first?.kind)
        assertEquals("第一步应是巨星自我修养：${plan.summary}", 21L, plan.first?.sub)
    }

    // ——— 2026-10-18 真机复查：先手排名与求解耗时 ———

    @Test fun pointsTiePrefersWorkFirstOverAdventure() {
        // 工分同分时先手定胜负：岗位 60 分钟 10 工分，剩下的时间只够刷冒险。
        // 先冒险后打工的计划工分一分不少、金币还更多——排名若先比金币，
        // 第一步就会是冒险，正事被顺延。先手必须排在金币之前。
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.POINTS, nowMin = 0, fatigueMin = 0, gold = 1000,
            stamina = 100, clean = 100, dayStartGold = 1000, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(OptimalPlanner.JobIn("工", 2, income = 50.0, points = 10.0, staminaCost = 0, cleanCost = 0, durationMin = 60)),
            adventures = listOf(OptimalPlanner.AdventureIn("附近走走", 3, evGold = 12.3, cycleMin = 0.75)),
            advPoolLeft = 5375.0, biscuitPrice = 5, soapPrice = 2, endMin = 90,
        )
        val plan = OptimalPlanner.solve(inp, timeBudgetMs = 120_000)!!
        assertEquals("工分同分时第一步应是打工：${plan.summary}", OptimalPlanner.ActKind.WORK, plan.first?.kind)
        assertEquals("工分应拿满：${plan.summary}", 10.0, plan.totalPoints, 1e-9)
        assertTrue("剩余空档应拿去冒险：${plan.summary}", plan.adventures > 0)
    }

    @Test fun attrTiePrefersStudyFirstOverAdventure() {
        // 属性优先同理：课程 60 分钟 +16 属性，剩下的时间只够刷冒险。
        // 先冒险后学习的计划属性一分不少、金币还更多——排名若先比金币，
        // 第一步就会是冒险。先手必须排在金币之前（与工分优先同口径）。
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.ATTR, nowMin = 0, fatigueMin = 0, gold = 1000,
            stamina = 100, clean = 100, dayStartGold = 1000, reserveGold = 0,
            courses = listOf(OptimalPlanner.CourseIn("课", 1, targetGain = 16.0, fee = 0, staminaCost = 0, cleanCost = 0, durationMin = 60)),
            jobs = emptyList(),
            adventures = listOf(OptimalPlanner.AdventureIn("附近走走", 3, evGold = 12.3, cycleMin = 0.75)),
            advPoolLeft = 5375.0, biscuitPrice = 5, soapPrice = 2, endMin = 90,
        )
        val plan = OptimalPlanner.solve(inp, timeBudgetMs = 120_000)!!
        assertEquals("属性同分时第一步应是学习：${plan.summary}", OptimalPlanner.ActKind.STUDY, plan.first?.kind)
        assertEquals("属性应拿满：${plan.summary}", 16.0, plan.totalAttr, 1e-9)
        assertTrue("剩余空档应拿去冒险：${plan.summary}", plan.adventures > 0)
    }

    @Test fun attrLossBudgetLetsStudySpendBelowDayStart() {
        // 起点金币就是早上金币，一节课 100 学费、+16 属性，没有任何赚钱手段：
        // 不给亏损额度时，期末不能低于早上金币，一节课都不敢上；
        // 给 300 就正好花掉 300 上 3 节，底线停在早上金币减 300。
        fun inp(loss: Int) = OptimalPlanner.Input(
            goal = AutoGoal.ATTR, nowMin = 0, fatigueMin = 0, gold = 1000,
            stamina = 100, clean = 100, dayStartGold = 1000, reserveGold = 0,
            lossBudget = loss,
            courses = listOf(OptimalPlanner.CourseIn("课", 1, targetGain = 16.0, fee = 100, staminaCost = 0, cleanCost = 0, durationMin = 20)),
            jobs = emptyList(), adventures = emptyList(), advPoolLeft = 0.0,
            biscuitPrice = 5, soapPrice = 2, endMin = 200,
        )
        val strict = OptimalPlanner.solve(inp(0))!!
        assertEquals("不亏口径下不应上课：${strict.summary}", 0, strict.studies)
        val relaxed = OptimalPlanner.solve(inp(300))!!
        assertEquals("亏损额度内应把课上满：${relaxed.summary}", 3, relaxed.studies)
        assertEquals(700.0, relaxed.endGold, 0.01)
        assertEquals(OptimalPlanner.ActKind.STUDY, relaxed.first?.kind)
    }

    @Test fun attrDeepFatigueStartsWithAdventureNotWork() {
        // 2026-10-29 真机场景：学工疲劳 755 分，课程系数只剩 0.10、被停课线 0.24 剔光，
        // 属性涨幅清零。此时第一步应该是冒险（凑钱更快、又不占疲劳），不是打工——
        // 旧口径把打工的先手排在冒险前面，执行层只走第一步、重解又得同一计划，
        // 一整天卡在同一趟 10 分钟工上，计划里的几百趟冒险一趟都轮不到。
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.ATTR, nowMin = 768, fatigueMin = 755, gold = 12600,
            stamina = 100, clean = 100, dayStartGold = 12600, reserveGold = 300,
            studyMinFactor = 0.24,
            courses = listOf(OptimalPlanner.CourseIn("课", 1, targetGain = 45.0, fee = 50, staminaCost = 0, cleanCost = 0, durationMin = 45)),
            jobs = listOf(OptimalPlanner.JobIn("工", 2, income = 60.0, points = 0.0, staminaCost = 0, cleanCost = 0, durationMin = 10)),
            adventures = listOf(OptimalPlanner.AdventureIn("冒险", 3, evGold = 8.8, cycleMin = 0.75)),
            advPoolLeft = 5300.0, biscuitPrice = 5, soapPrice = 2,
        )
        val plan = OptimalPlanner.solve(inp, timeBudgetMs = 120_000)!!
        assertEquals("课程应被停课线剔光：${plan.summary}", 0, plan.studies)
        assertEquals("第一步应是冒险：${plan.summary}", OptimalPlanner.ActKind.ADVENTURE, plan.first?.kind)
        assertTrue("计划里应有冒险：${plan.summary}", plan.adventures > 0)
    }

    @Test fun tinyTimeBudgetStillReturnsGreedyFloor() {
        // 时间预算压到 50ms：全量搜索跑不完，必须按时收工，且交出的计划
        // 不能差于贪心保底——第一步仍是贪心会选的那趟正事。
        val inp = snapshotInput(AutoGoal.POINTS)
        val t0 = System.nanoTime()
        val plan = OptimalPlanner.solve(inp, timeBudgetMs = 50)!!
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("应在宽松时间内返回，实际 ${elapsedMs}ms", elapsedMs < 20_000)
        assertTrue("应标注近似解：${plan.summary}", plan.summary.contains("近似解"))
        assertEquals("第一步应是贪心会选的正事：${plan.summary}", OptimalPlanner.ActKind.WORK, plan.first?.kind)
    }

    @Test fun jobPointsPrefersCatalogOverLearned() {
        // 目录标称优先：学习值是折后均值，当满档值用会折两道（2026-11-06 奎格迈尔实报）
        assertEquals(54.0, OptimalPlanner.jobPoints(54, 6.0), 1e-9)
        assertEquals(10.0, OptimalPlanner.jobPoints(10, null), 1e-9)
        // 目录没给工分时才回退学习值；都没有就是 0
        assertEquals(6.0, OptimalPlanner.jobPoints(0, 6.0), 1e-9)
        assertEquals(0.0, OptimalPlanner.jobPoints(0, null), 1e-9)
    }

    @Test fun pointsGoalRuns45MinTierDespitePoisonedLearnedPoints() {
        // 复现 2026-11-06 实报：45 分档目录标称 +54（72 点/小时）优于 10 分档 +10（60 点/小时），
        // 但 45 分档的历史实收学习值被疲劳折过只剩 6——旧口径拿学习值喂求解器，全天只跑短工。
        // 修复后定价走 jobPoints（目录优先），工分优先的第一步必须是 45 分档。
        val inp = OptimalPlanner.Input(
            goal = AutoGoal.POINTS, nowMin = 0, fatigueMin = 0, gold = 1000,
            stamina = 100, clean = 100, dayStartGold = 1000, reserveGold = 0,
            courses = emptyList(),
            jobs = listOf(
                OptimalPlanner.JobIn("短工", 1, income = 70.0, points = OptimalPlanner.jobPoints(10, 10.0), staminaCost = 0, cleanCost = 0, durationMin = 10),
                OptimalPlanner.JobIn("长工", 2, income = 302.0, points = OptimalPlanner.jobPoints(54, 6.0), staminaCost = 0, cleanCost = 0, durationMin = 45),
            ),
            adventures = emptyList(), advPoolLeft = 0.0, biscuitPrice = 5, soapPrice = 2, endMin = 90,
        )
        val plan = OptimalPlanner.solve(inp)!!
        assertEquals(2L, plan.first?.sub)
        assertEquals(108.0, plan.totalPoints, 1e-9)
    }
}
