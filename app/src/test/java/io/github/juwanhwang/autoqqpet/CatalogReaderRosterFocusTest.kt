package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.CatalogReader
import io.github.juwanhwang.autoqqpet.optimizer.RunnerConfig
import io.github.juwanhwang.autoqqpet.pet.CareerInfo
import io.github.juwanhwang.autoqqpet.pet.SceneOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 排班步骤的职业必须进决策（2026-10-30 群友实报）：打工方向钉子把目录池收窄到单个职业后，
 * 排班第一步点名的另一个职业永远不在快照里，每轮判 NO_SUCH_SUB 全表停摆、无限「稍作休息」。
 * 修法：read(extraFocusCareers) 传入步骤职业——不受钉子收窄、每轮现读。
 */
class CatalogReaderRosterFocusTest {

    private class MemCache : CatalogReader.JobCache {
        var v = ""
        override fun load() = v
        override fun save(json: String) { v = json }
    }

    private fun at(h: Int, m: Int = 0) =
        ZonedDateTime.of(2026, 10, 30, h, m, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun reader(cache: MemCache, now: () -> Long, calls: MutableList<String>) =
        CatalogReader(
            null, null, RunnerConfig(workCareerType = 1L), {}, cache,
            fetchJobs = { t, _ -> calls += "jobs$t"; listOf(SceneOption("岗$t", 6400000L + t, true, duration = "45分钟", extra = "+14点")) },
            fetchCareers = { calls += "careers"; listOf(CareerInfo("A", 1), CareerInfo("B", 2)) },
            fetchStage = { calls += "stage"; 2L },
            fetchCourses = { calls += "courses"; emptyList() },
            learnedGold = { null },
        ).also { it.clock = now }

    @Test fun pinnedCareerHidesRosterCareerUntilForced() {
        CatalogReader.resetMemoryForTest()
        val cache = MemCache(); val calls = ArrayList<String>(); var now = at(9)
        val r = reader(cache, { now }, calls)
        r.read() // 全量首读：暖缓存
        calls.clear(); now = at(9, 30)

        // 常态（钉子=职业1）：决策里只有职业 1 的岗位——排班点名职业 2 时必然 NO_SUCH_SUB
        val pinned = r.read()
        assertFalse(pinned.fullRefresh)
        assertEquals(listOf("岗1"), pinned.jobs.map { it.name })

        // 传入排班步骤的职业 2：不受钉子收窄、且本轮现读（calls 里有 jobs2）
        calls.clear()
        val forced = r.read(extraFocusCareers = setOf(2L))
        assertTrue(calls.contains("jobs2"))
        assertTrue(forced.jobs.any { it.name == "岗2" && it.careerType == 2L })
    }
}
