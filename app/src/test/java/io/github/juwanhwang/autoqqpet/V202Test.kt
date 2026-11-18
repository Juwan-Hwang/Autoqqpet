package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.CatalogReader
import io.github.juwanhwang.autoqqpet.optimizer.DayPlanner
import io.github.juwanhwang.autoqqpet.optimizer.Kind
import io.github.juwanhwang.autoqqpet.optimizer.PayMonitor
import io.github.juwanhwang.autoqqpet.optimizer.RunnerConfig
import io.github.juwanhwang.autoqqpet.optimizer.Snapshot
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.Caps
import io.github.juwanhwang.autoqqpet.pet.CareerInfo
import io.github.juwanhwang.autoqqpet.pet.DoneLedger
import io.github.juwanhwang.autoqqpet.pet.FatigueText
import io.github.juwanhwang.autoqqpet.pet.FriendPolicy
import io.github.juwanhwang.autoqqpet.pet.JobInfo
import io.github.juwanhwang.autoqqpet.pet.LikeVisitor
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.SceneOption
import io.github.juwanhwang.autoqqpet.pet.SparkEntry
import io.github.juwanhwang.autoqqpet.pet.SparkPolicy
import io.github.juwanhwang.autoqqpet.pet.SparkStage
import io.github.juwanhwang.autoqqpet.pet.SparkTonight
import io.github.juwanhwang.autoqqpet.pet.Step
import io.github.juwanhwang.autoqqpet.pet.StepKind
import io.github.juwanhwang.autoqqpet.pet.GoalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2026-10-15 这一轮（R8–R10 与追加项）的纯逻辑单测。 */
class V202Test {

    // ---------- 火花：默认踩所有好友，只有黑名单 ----------

    private fun s(uin: String, st: SparkStage, days: Int = 3, gap: Int = -1, mutual: Boolean = false) = SparkEntry(uin, "n$uin", "", st, days, gap, mutual)

    @Test fun sparkTargetsAreAllFriendsMinusBlacklistOrdered() {
        val sparks = listOf(s("2", SparkStage.REKINDLE, 9, 3), s("3", SparkStage.LIT, 20), s("4", SparkStage.LIT, 5), s("6", SparkStage.REKINDLE, 2, 6))
        val roster = listOf("1", "2", "3", "4", "5", "6", "7", "self")
        val t = SparkPolicy.dailyTargets(roster, setOf("5"), setOf("7"), "self", sparks, skipToday = setOf("1"), lastPokedDay = emptyMap())
        // 待重燃（离清零近的先：gap 6 → 3）→ 在燃（天数多的先）→ 其余；5 黑名单、7 已踩、1 今天被拒、self 剔除
        assertEquals(listOf("6", "2", "3", "4"), t)
    }

    @Test fun sparkOthersSortedByLastInteraction() {
        val t = SparkPolicy.dailyTargets(listOf("1", "2", "3"), emptySet(), emptySet(), "", emptyList(), lastPokedDay = mapOf("3" to 100, "2" to 90))
        assertEquals(listOf("3", "2", "1"), t)
    }

    /** 总开关开/关 × 好友/非好友 × 黑名单。 */
    @Test fun returnPokeMatrix() {
        val v = listOf(LikeVisitor("F", "friend", 1, true), LikeVisitor("S", "stranger", 1, true), LikeVisitor("B", "blocked", 1, true), LikeVisitor("FB", "friend-blocked", 1, true))
        val roster = setOf("F", "FB")
        val bl = setOf("B", "FB")
        // 总开关开：好友已在每日计划 → 只回踩非好友；黑名单排除
        assertEquals(listOf("S"), SparkPolicy.returnTargets(true, v, roster, bl, emptySet(), "self", dailyOn = true))
        // 总开关关：谁踩了你就回踩，好友也算；黑名单照样排除
        assertEquals(listOf("F", "S"), SparkPolicy.returnTargets(true, v, roster, bl, emptySet(), "self", dailyOn = false))
        // 回踩关：都不踩
        assertTrue(SparkPolicy.returnTargets(false, v, roster, bl, emptySet(), "self", dailyOn = false).isEmpty())
        // 今天已踩过的不重复
        assertEquals(listOf("S"), SparkPolicy.returnTargets(true, v, roster, bl, setOf("F"), "self", dailyOn = false))
    }

    @Test fun sparkProgressAndNames() {
        assertEquals(2 to 4, SparkPolicy.progress(listOf("1", "2", "3", "4", "5"), setOf("5"), setOf("1", "2", "9"), ""))
        assertEquals("a、b、c 等 5 人", SparkPolicy.names(listOf("a", "b", "c", "d", "e")))
        assertEquals("a、b", SparkPolicy.names(listOf("a", "b")))
    }

    @Test fun sparkListMigrationIsIdempotent() {
        val old = mapOf<String, Any?>(FriendPolicy.KEY_SPARK_WL to "1,2", FriendPolicy.KEY_SPARK_BL to "3, 4,,x")
        val ch = SparkPolicy.migrateLists(old)
        assertTrue(ch.containsKey(FriendPolicy.KEY_SPARK_WL) && ch[FriendPolicy.KEY_SPARK_WL] == null)   // 白名单丢弃
        assertEquals("3,4", ch[FriendPolicy.KEY_SPARK_BL])                                                 // 不踩 → 黑名单
        // 应用一次后再跑：无改动
        val after = HashMap(old); ch.forEach { (k, v) -> if (v == null) after.remove(k) else after[k] = v }
        assertTrue(SparkPolicy.migrateLists(after).isEmpty())
    }

    @Test fun atRiskUsesBlacklistNotWhitelist() {
        val l = listOf(s("1", SparkStage.REKINDLE, 9, 5), s("2", SparkStage.REKINDLE, 9, 6), s("3", SparkStage.LIT, 9))
        assertEquals(listOf("1"), SparkPolicy.atRisk(l, setOf("2")).map { it.uin })
    }

    // ---------- 今晚熄灭提醒 ----------

    @Test fun tonightOnlyAfterTimeAndOnlyLitNotMutual() {
        val sparks = listOf(s("1", SparkStage.LIT), s("2", SparkStage.LIT, mutual = true), s("3", SparkStage.REKINDLE, 5, 2), s("4", SparkStage.LIT), s("5", SparkStage.LIT))
        val visitors = listOf(LikeVisitor("4", "x", 1, true))
        assertEquals(SparkTonight.Verdict.NotYet, SparkTonight.evaluate(LocalTime.of(20, 59), 21 * 60, sparks, visitors, emptySet(), emptySet(), emptySet()))
        val v = SparkTonight.evaluate(LocalTime.of(21, 0), 21 * 60, sparks, visitors, setOf("4"), emptySet(), setOf("5")) as SparkTonight.Verdict.Remind
        // 1：都没踩；2：服务器说已互踩；3：待重燃不算；4：双向完成；5：黑名单
        assertEquals(listOf("1"), v.misses.map { it.uin })
        assertTrue(v.text.contains("你还没踩") && v.text.contains("对方还没踩你"))
    }

    @Test fun tonightNoDataNoAlertAndAllDoneClears() {
        assertTrue(SparkTonight.evaluate(LocalTime.of(22, 0), 1260, null, emptyList(), emptySet(), emptySet(), emptySet()) is SparkTonight.Verdict.NoData)
        assertTrue(SparkTonight.evaluate(LocalTime.of(22, 0), 1260, emptyList(), null, emptySet(), emptySet(), emptySet()) is SparkTonight.Verdict.NoData)
        assertEquals(SparkTonight.Verdict.AllDone, SparkTonight.evaluate(LocalTime.of(22, 0), 1260, listOf(s("1", SparkStage.LIT, mutual = true)), emptyList(), emptySet(), emptySet(), emptySet()))
    }

    @Test fun tonightLongListUsesFirstThreeNames() {
        val many = (1..10).map { s("$it", SparkStage.LIT) }
        val v = SparkTonight.evaluate(LocalTime.of(23, 0), 1260, many, emptyList(), emptySet(), emptySet(), emptySet()) as SparkTonight.Verdict.Remind
        assertTrue(v.text.contains("等 10 人"))
    }

    @Test fun tonightDedupOncePerDay() {
        val r = SparkTonight.Verdict.Remind(emptyList(), "t", "1,2")
        assertEquals(SparkTonight.Action.NOTIFY, SparkTonight.action(r, sentToday = false, currentKey = null))
        assertEquals(SparkTonight.Action.KEEP, SparkTonight.action(r, sentToday = true, currentKey = "1,2"))          // 同一事件不重复推
        assertEquals(SparkTonight.Action.UPDATE_SILENT, SparkTonight.action(r, sentToday = true, currentKey = "1,2,3"))
        assertEquals(SparkTonight.Action.NONE, SparkTonight.action(r, sentToday = true, currentKey = null))         // 今天响过、已撤：不再推
        assertEquals(SparkTonight.Action.CLEAR, SparkTonight.action(SparkTonight.Verdict.AllDone, true, "1,2"))
        assertEquals(SparkTonight.Action.LOG_NO_DATA, SparkTonight.action(SparkTonight.Verdict.NoData("x"), false, null))
        assertEquals(SparkTonight.Action.NONE, SparkTonight.action(SparkTonight.Verdict.NoData("x"), false, null, noDataLoggedToday = true))
    }

    // ---------- 每日上限：-1 = 不限 ----------

    @Test fun capsUnlimitedAndMigration() {
        assertTrue(Caps.allows(10_000, -1))
        assertTrue(Caps.allows(4, 5)); assertFalse(Caps.allows(5, 5))
        assertTrue(Caps.allowsZeroUnlimited(999, 0))   // adv_max 沿用 0=不限
        assertEquals("不限", Caps.label(-1))
        // 旧 0 = 一次都不做 → 关开关 + 回默认
        assertEquals(-1 to true, Caps.migrate(0, -1))
        assertEquals(10 to true, Caps.migrate(0, 10))
        // 旧默认值迁成新默认（福袋 10 → 不限）；用户自己设的其它值不动
        assertEquals(-1 to false, Caps.migrate(10, -1, legacyDefault = 10))
        assertNull(Caps.migrate(7, -1, legacyDefault = 10))
        assertNull(Caps.migrate(-1, -1, legacyDefault = 10))   // 幂等
        assertNull(Caps.migrate(null, -1))
    }

    // ---------- 排班：步数不设上限 ----------

    @Test fun scheduleHasNoStepLimitAndDedupesIds() {
        val steps = (1..40).map { Step("s1", StepKind.WORK, goal = GoalType.COUNT, n = 2) }
        val s = Schedule(steps = steps).sanitized()
        assertEquals(40, s.steps.size)
        assertEquals(40, s.steps.map { it.id }.toSet().size)
        val round = Schedule.parse(Schedule.encode(s))
        assertEquals(40, round.steps.size)
    }

    // ---------- 疲劳：8 小时线，文案只降权该档 ----------

    private fun job(sub: Long, points: Int, min: Int) = JobInfo("j$sub", 1, sub, 0, false, points, 0, 0, min, min * 60L, points, true)

    @Test fun crossFactorDownweightsOnlyCrossingTier() {
        val p = DayPlanner(targetAttr = Attr.STRENGTH)
        assertEquals(1.0, p.crossFactor(45, 420, false), 1e-9)            // 剩 60 分钟，45 分钟档不跨线
        assertTrue(p.crossFactor(120, 420, true) < 0.7)                    // 2 小时档跨线 → 降权
        assertEquals(1.0, p.crossFactor(45, 0, false), 1e-9)
        assertTrue(p.crossFactor(120, 0, true) < 1.0)                      // 服务器文案优先于本地累计
    }

    @Test fun remaining60MinPicks45MinTierNotAdventure() {
        val p = DayPlanner(targetAttr = Attr.STRENGTH, workFirst = true)
        val jobs = listOf(job(1, 14, 45), job(2, 39, 240), job(3, 24, 120))
        val snap = Snapshot(gold = 5000, stamina = 100, clean = 100, fatigueMin = 420, courses = emptyList(), jobs = jobs, biscuits = 9, soap = 9,
            fatigueTextSubs = setOf(2L, 3L))
        val d = p.decide(snap)
        assertEquals(Kind.WORK, d.kind)
        assertEquals(1L, d.sub)
    }

    @Test fun onlyAllTiersWithTextMeansWholeDayTired() {
        val jobs = listOf(job(1, 14, 45), job(2, 39, 240))
        val base = PayMonitor.Assessment(emptySet(), emptyList(), false)
        val some = PayMonitor.withText(base, mapOf(2L to "会疲惫"), emptyList(), jobs, fatigueMin = 420)
        assertTrue(some.penalized.isEmpty()); assertFalse(some.allGone)
        val all = PayMonitor.withText(base, mapOf(1L to "会疲惫", 2L to "会疲惫"), emptyList(), jobs, fatigueMin = 420)
        assertTrue(all.allGone); assertEquals(setOf(1L, 2L), all.penalized)
        val full = PayMonitor.withText(base, emptyMap(), emptyList(), jobs, fatigueMin = 480)
        assertTrue(full.allGone)
    }

    @Test fun fatigueTextWeakMatchExcludesFriendHire() {
        assertNotNull(FatigueText.detect("宠物已经疲惫了"))
        assertNotNull(FatigueText.detect(null, "做完会很累"))
        assertNull(FatigueText.detect("该好友今天很累了，无法继续被雇佣"))
        assertNull(FatigueText.detect("开心打工"))
        assertNotNull(FatigueText.detect(SceneOption("x", 1, true, description = "这一档会让宠物疲劳")))
    }

    // ---------- “今天”只算已完成 ----------

    @Test fun todayCountsOnlyCompleted() {
        val counts = HashMap<String, Int>(); val ids = HashSet<String>()
        counts["work"] = 1                                    // 开工时的计数（规划用）——“今天”不看它
        assertEquals(0, DoneLedger.reportCounts(counts)["work"])
        assertEquals(0, DoneLedger.studyWorkMin(counts))
        assertTrue(DoneLedger.apply(counts, ids, "6400_1", "work", 45 * 60))
        assertEquals(1, DoneLedger.reportCounts(counts)["work"])
        assertEquals(45, DoneLedger.studyWorkMin(counts))
        assertFalse(DoneLedger.apply(counts, ids, "6400_1", "work", 45 * 60))   // 同一 storyId 只记一次
        assertTrue(DoneLedger.apply(counts, ids, "7600_2", "adventure", 45))
        assertEquals(0, DoneLedger.adventureMin(counts)); assertEquals(45, counts[DoneLedger.K_ADV_SEC])
        assertFalse(DoneLedger.apply(counts, ids, "x", "pk", 60))               // PK 不进
    }

    // ---------- 目录缓存 ----------

    private class MemCache : CatalogReader.JobCache { var v = ""; override fun load() = v; override fun save(json: String) { v = json } }

    private fun reader(cache: MemCache, now: () -> Long, careers: () -> List<CareerInfo>, stage: () -> Long = { 2L }, calls: MutableList<String>) =
        CatalogReader(
            null, null, RunnerConfig(), {}, cache,
            fetchJobs = { t, _ -> calls += "jobs$t"; listOf(SceneOption("岗$t", 6400000L + t, true, duration = "45分钟", extra = "+14点")) },
            fetchCareers = { calls += "careers"; careers() },
            fetchStage = { calls += "stage"; stage() },
            fetchCourses = { calls += "courses"; emptyList() },
            learnedGold = { null },
        ).also { it.clock = now }

    private fun at(h: Int, m: Int = 0, day: Int = 15) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun catalogCacheHitThen5amInvalidation() {
        CatalogReader.resetMemoryForTest()
        val cache = MemCache(); val calls = ArrayList<String>(); var now = at(9)
        val careers = listOf(CareerInfo("A", 1), CareerInfo("B", 2), CareerInfo("C", 3))
        val r = reader(cache, { now }, { careers }, calls = calls)
        val first = r.read()
        assertTrue(first.fullRefresh); assertEquals(listOf("stage", "courses", "careers", "jobs1", "jobs2", "jobs3"), calls)
        calls.clear(); now = at(9, 30)
        val second = r.read()
        assertFalse(second.fullRefresh)
        assertEquals(listOf("courses", "jobs1"), calls)        // 常态：课程 + 一个职业 = 2 包
        assertEquals(2, second.packets)
        calls.clear(); now = at(4, 59, 16)                       // 次日 4:59 仍是同一游戏日
        assertFalse(r.read().fullRefresh)
        calls.clear(); now = at(5, 1, 16)                        // 过 5:00 → 全量重读
        assertTrue(r.read().fullRefresh)
        assertTrue(calls.containsAll(listOf("stage", "careers")))
    }

    @Test fun catalogInvalidatesOnRejectAndCareerChange() {
        CatalogReader.resetMemoryForTest()
        val cache = MemCache(); val calls = ArrayList<String>(); var now = at(10)
        var careers = listOf(CareerInfo("A", 1))
        val r = reader(cache, { now }, { careers }, calls = calls)
        r.read(); calls.clear()
        CatalogReader.invalidate("开工被拒 code=135004")
        now = at(10, 5)
        assertTrue(r.read().fullRefresh)
        assertTrue("careers" in calls)
        // 职业升级/解锁：全量刷新时读到的职业集合变化 → 岗位全量重读
        calls.clear(); careers = listOf(CareerInfo("A", 1), CareerInfo("B", 2))
        CatalogReader.invalidate("职业升级")
        r.read()
        assertTrue("jobs2" in calls)
    }

    @Test fun catalogDecisionUsesOnlyFreshCareer() {
        CatalogReader.resetMemoryForTest()
        val cache = MemCache(); val calls = ArrayList<String>(); var now = at(11)
        val r = reader(cache, { now }, { listOf(CareerInfo("A", 1), CareerInfo("B", 2)) }, calls = calls)
        r.read()
        now = at(11, 10)
        val c = r.read()
        // 非焦点职业只在缓存里（给界面），不进决策
        assertEquals(1, c.jobs.map { it.careerType }.toSet().size)
        assertEquals("cache", c.jobSources.values.firstOrNull { it == "cache" })
    }

    @Test fun catalogAutoPoolIncludesAllCareers() {
        CatalogReader.resetMemoryForTest()
        val cache = MemCache(); val calls = ArrayList<String>(); var now = at(9)
        val careers = (1L..6L).map { CareerInfo("职业$it", it) }
        val r = reader(cache, { now }, { careers }, calls = calls)
        val c = r.read()
        assertTrue(c.fullRefresh)
        // 自动模式不再只取前 4 个：全部已解锁职业都进岗位池、全量刷新逐个读
        assertEquals((1L..6L).map { "jobs$it" }.toSet(), calls.filter { it.startsWith("jobs") }.toSet())
        assertEquals((1L..6L).toSet(), c.jobSources.keys)
    }
}
