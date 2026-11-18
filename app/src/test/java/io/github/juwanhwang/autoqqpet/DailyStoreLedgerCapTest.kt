package io.github.juwanhwang.autoqqpet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 台账上限回归（真机 2026-10-16）：当天结算 376 趟，台账旧上限 300 行把早上的学工行挤掉，
 * 而「今天」计数（doneTally）是从台账现算的——页面学习/打工直接归零。
 * 上限必须盖住一整天的行数（45 秒冒险背靠背一天约 1600 趟）。
 */
@RunWith(RobolectricTestRunner::class)
class DailyStoreLedgerCapTest {

    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val zone = ZoneId.systemDefault()

    private fun at(t: LocalTime): Long =
        LocalDate.now().atTime(t).atZone(zone).toInstant().toEpochMilli()

    @Test fun morningStudyWorkSurviveAdventureFlood() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val st = DailyStore(ctx)
        st.counts()   // 先定 day=今天，避免跨天清零误伤

        // 种台账：凌晨 350 趟短冒险（55 秒一趟）+ 早上 2 趟学习
        val lines = ArrayList<String>()
        val ids = ArrayList<String>()
        for (i in 0 until 350) {
            val id = "6700_f%03d".format(i)
            lines += "${LocalTime.of(0, 0).plusSeconds(i * 55L).format(fmt)}|adventure|45s|$id"
            ids += id
        }
        for ((h, id) in listOf(6 to "6100_morning1", 7 to "6100_morning2")) {
            lines += "${LocalTime.of(h, 0).format(fmt)}|study|45m|$id"
            ids += id
        }
        ctx.getSharedPreferences("autoqqpet_daily", Context.MODE_PRIVATE).edit()
            .putString("ledger", lines.joinToString("\n"))
            .putString("ledger_ids", ids.joinToString(","))
            .apply()
        assertEquals(352, st.ledger().size)

        // 再经真实追加路径补一趟中午的打工：旧上限会在这一步把台账砍回 300 行、学工行先丢
        assertTrue(st.appendReconciled("6400_noon", "work", at(LocalTime.of(12, 0)), 2700))

        assertEquals(353, st.ledger().size)
        val t = st.doneTally(at(LocalTime.of(23, 59)))
        assertEquals(2, t.study)
        assertEquals(1, t.work)
        assertEquals(350, t.adventure)
    }
}
