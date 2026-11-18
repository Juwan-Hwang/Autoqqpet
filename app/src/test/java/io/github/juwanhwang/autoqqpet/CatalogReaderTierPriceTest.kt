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
 * 岗位定价按时长档位查账（2026-10-31 真机实报）：岗位编号会随职业升级漂移
 * （陪练基础课 6451002 → 6451102），历史实收记在旧编号键下、定价按新编号查，
 * 永远查不到 → 金币优先里所有岗位按 0 估价、从不入选。修法：定价先查档位键
 * （w2c<职业>t<时长分钟>），编号键兜底。本测试的岗位编号 6451102 在账里没有任何条目，
 * 价格只能来自档位键——断言按此语义推导。
 */
class CatalogReaderTierPriceTest {

    private class MemCache : CatalogReader.JobCache {
        var v = ""
        override fun load() = v
        override fun save(json: String) { v = json }
    }

    private fun at(h: Int, m: Int = 0) =
        ZonedDateTime.of(2026, 10, 31, h, m, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun reader(prices: Map<String, Double>): CatalogReader {
        var now = at(9)
        return CatalogReader(
            null, null, RunnerConfig(workCareerType = 1L), {}, MemCache(),
            fetchJobs = { _, _ -> listOf(SceneOption("陪练基础课", 6451102L, true, duration = "45分钟", extra = "+14点")) },
            fetchCareers = { listOf(CareerInfo("竹影武馆", 1)) },
            fetchStage = { 2L },
            fetchCourses = { emptyList() },
            learnedGold = { k -> prices[k] },
        ).also { it.clock = { now } }
    }

    @Test fun tierKeyPricesJobWhenSubKeyMisses() {
        CatalogReader.resetMemoryForTest()
        val r = reader(mapOf("w2c1t45" to 241.0))
        r.read() // 全量首读暖缓存
        val job = r.read().jobs.single { it.name == "陪练基础课" }
        assertTrue(job.incomeKnown)
        assertEquals(241, job.income)
    }

    @Test fun subKeyStillWorksAsFallback() {
        CatalogReader.resetMemoryForTest()
        val r = reader(mapOf("w26451102" to 200.0))
        r.read()
        val job = r.read().jobs.single { it.name == "陪练基础课" }
        assertTrue(job.incomeKnown)
        assertEquals(200, job.income)
    }

    @Test fun noLearnedPriceLeavesJobUnpriced() {
        CatalogReader.resetMemoryForTest()
        val r = reader(emptyMap())
        r.read()
        val job = r.read().jobs.single { it.name == "陪练基础课" }
        assertFalse(job.incomeKnown)
        assertEquals(0, job.income)
    }
}
