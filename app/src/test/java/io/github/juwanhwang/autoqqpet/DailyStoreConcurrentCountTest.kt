package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 每日计数并发回归（2026-10-18 真机：日志 10 条 PK 胜利、昨日报告只剩 8 次）：
 * increment 是对 "counts" 整串 CSV 的读-改-写，主循环与 PK 旁路等通道并发时后写覆盖先写。
 * 计数读写上了同一把锁之后，多线程并发增数一分都不能丢。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreConcurrentCountTest {

    @Test fun concurrentIncrementsLoseNothing() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit().clear().apply()
        val st = DailyStore(ctx)
        st.counts()   // 定住 day，避免跨天清零混进测试

        val pkThreads = 8
        val pkPerThread = 250
        val winThreads = 4
        val winPerThread = 250
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(pkThreads + winThreads)
        repeat(pkThreads) { pool.submit { gate.await(); repeat(pkPerThread) { st.increment("pk") } } }
        repeat(winThreads) { pool.submit { gate.await(); repeat(winPerThread) { st.increment("pk_win") } } }
        gate.countDown()
        pool.shutdown()
        assertTrue("并发增数应在超时前跑完", pool.awaitTermination(120, TimeUnit.SECONDS))

        assertEquals(pkThreads * pkPerThread, st.count("pk"))
        assertEquals(winThreads * winPerThread, st.count("pk_win"))
    }
}
