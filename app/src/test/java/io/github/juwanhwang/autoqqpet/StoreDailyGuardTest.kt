package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.store.Store
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防清空保护（2026-10-19 真机事故）：同一天的日账本，有内容的一侧永远不能被空的一侧盖掉。
 * 判定点两处共用 [Store.dailyEmptyOverwriteBlocked]：主存收 QQ 上交（saveBackup）、
 * QQ 启动恢复本地（DailyStore.adoptFromStore）。
 */
class StoreDailyGuardTest {

    private val counts = "done_work:3;pk:2"
    private val ledger = "08:00:00|work|45m|6400_abc"

    @Test fun sameDayEmptyOverFullBlocked() {
        assertTrue(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = 100, incomingCounts = "", incomingLedger = "",
                localDay = 100, localCounts = counts, localLedger = ledger,
            )
        )
        // 来件 counts/ledger 键整个缺失（解析成 null）同样算空
        assertTrue(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = 100, incomingCounts = null, incomingLedger = null,
                localDay = 100, localCounts = counts, localLedger = "",
            )
        )
        // 本地只要有台账（计数还没记上）也算有内容，照样拦
        assertTrue(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = 100, incomingCounts = "", incomingLedger = "",
                localDay = 100, localCounts = "", localLedger = ledger,
            )
        )
    }

    @Test fun sameDayNonEmptyIncomingPasses() {
        assertFalse(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = 100, incomingCounts = counts, incomingLedger = ledger,
                localDay = 100, localCounts = counts, localLedger = ledger,
            )
        )
        // 来件只有计数没台账也不算空，正常收下
        assertFalse(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = 100, incomingCounts = counts, incomingLedger = "",
                localDay = 100, localCounts = "", localLedger = ledger,
            )
        )
    }

    @Test fun crossDayEmptyNotBlocked() {
        // 跨天：新一天账本为空是正常的，不得拦（清零归档走 DailyStore 自己的跨天逻辑）
        assertFalse(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = 101, incomingCounts = "", incomingLedger = "",
                localDay = 100, localCounts = counts, localLedger = ledger,
            )
        )
    }

    @Test fun bothEmptyNotBlocked() {
        // 两边都空（首次安装、都还没记数）：收下无害，不拦
        assertFalse(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = 100, incomingCounts = "", incomingLedger = "",
                localDay = 100, localCounts = "", localLedger = "",
            )
        )
    }

    @Test fun missingDayNotBlocked() {
        // 来件没带日号：证不出同日，不拦（走原有行为）
        assertFalse(
            Store.dailyEmptyOverwriteBlocked(
                incomingDay = -1, incomingCounts = "", incomingLedger = "",
                localDay = -1, localCounts = counts, localLedger = ledger,
            )
        )
    }

    @Test fun jsonEntryPoints() {
        val full = """{"day":100,"counts":"done_work:3","ledger":"08:00:00|work|45m|6400_abc"}"""
        val empty = """{"day":100,"schema":2}"""
        assertTrue(Store.dailyOverwriteBlocked(empty, full))
        assertFalse(Store.dailyOverwriteBlocked(full, empty))
        assertFalse(Store.dailyOverwriteBlocked(empty, """{"day":99,"counts":"done_work:9","ledger":"x"}"""))
        assertFalse(Store.dailyOverwriteBlocked(empty, null))
        assertFalse(Store.dailyOverwriteBlocked("not json", full))
    }
}
