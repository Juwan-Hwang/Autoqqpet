package io.github.juwanhwang.autoqqpet.optimizer

import android.content.Context
import io.github.juwanhwang.autoqqpet.pet.AdSweepState
import io.github.juwanhwang.autoqqpet.pet.OutingKind
import io.github.juwanhwang.autoqqpet.pet.OutingRecord
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import kotlin.random.Random

/**
 * 历史结算广告奖励补领（rewardType 60）。
 *
 * 读《出门记录》0x9876_1（游标 = 上一页最后一条时间戳），挑已完成的打工，跳过本地已处理集合，
 * 再逐条走 [QQPetService.rewardAd] 领取结算页的广告奖励。
 *
 * 同一条 story 只发一次奖；已领过的 report 会回 remoteCode=1000360，计入“已领过”并落盘，
 * 下一轮不再碰。加载阶段失败的条目不落盘，留到下次再试；连续 [FAIL_STREAK_LIMIT] 条失败就停手。
 * 进度通过 [onProgress] 交给调用方广播给界面。
 */
class AdHistorySweeper(
    private val service: QQPetService,
    private val context: Context,
    private val log: (String) -> Unit,
    private val onProgress: (AdSweepState.Progress) -> Unit = {},
) {
    companion object {
        private const val PREFS = "autoqqpet_ad_history"
        private const val KEY_DONE = "done_csv"
        private const val KEY_CURSOR = "scan_cursor_ms"
        private const val KEY_PENDING = "pending_csv"
        private const val KEY_SCAN_DONE = "scan_done"
        private const val MAX_DONE = 1500
        private const val MAX_PENDING = 2000
        private const val PAGE = 20
        private const val FAIL_STREAK_LIMIT = 4
        const val DEFAULT_MAX_SCAN = 500

        data class Outcome(
            val scanned: Int = 0,
            val work: Int = 0,
            val tried: Int = 0,
            val ok: Int = 0,
            val refused: Int = 0,
            val failed: Int = 0,
            val skippedDone: Int = 0,
            val stop: String = "",
        ) {
            fun describe(): String = buildString {
                append("扫描 $scanned 条，打工 $work 条，跳过已领 $skippedDone，")
                append("尝试 $tried，成功 $ok，已领过/被拒 $refused，失败 $failed")
                if (stop.isNotEmpty()) append("（$stop）")
            }
        }
    }

    fun sweep(
        // 每次扫描多少条《出门记录》（不是尝试补领多少条）；扫到条数就停。
        maxScan: Int = DEFAULT_MAX_SCAN,
        workOnly: Boolean = true,
        // 默认走正常播放再上报；background=true（跳过播放直接报完成）属于伪造曝光，只允许显式开启。
        background: Boolean = false,
        // 出门记录不设页数上限（2026-11-12 用户要求）：一直翻到空页/游标不前进为止。
        maxPages: Int = Int.MAX_VALUE,
        cancelled: () -> Boolean = { false },
    ): Outcome {
        val startedAt = System.currentTimeMillis()
        // 0/负数表示不限：一直扫到最早一条记录为止。
        val scanLimit = if (maxScan <= 0) Int.MAX_VALUE else maxScan
        fun report(
            running: Boolean = true,
            scanned: Int = 0,
            total: Int = 0,
            done: Int = 0,
            ok: Int = 0,
            refused: Int = 0,
            failed: Int = 0,
            skippedDone: Int = 0,
            pending: Int = 0,
            scanAt: Long = 0,
            current: String = "",
            stop: String = "",
        ) {
            onProgress(
                AdSweepState.Progress(
                    running = running, scanned = scanned, total = total, done = done,
                    ok = ok, refused = refused, failed = failed, skippedDone = skippedDone,
                    pending = pending, scanAt = scanAt, current = current, stop = stop,
                    startedAt = startedAt, endedAt = if (running) 0L else System.currentTimeMillis(),
                ),
            )
        }

        report(current = "正在读取记录")
        val petId = runCatching { service.ensurePetId() }.getOrElse {
            report(running = false, stop = "还没读到 petId")
            return Outcome(stop = "还没读到 petId")
        }
        if (petId.isEmpty()) {
            report(running = false, stop = "还没读到 petId")
            return Outcome(stop = "还没读到 petId")
        }

        val done = doneSet().toMutableSet()
        // 已扫出来但还没补的条目。只有这一段扫描真的扫完了，才会拿它们去补；
        // 扫到一半取消的，下一轮先把这段扫完再一起补。
        val pending = pendingIds().toMutableSet().apply { removeAll { it in done } }
        val seen = HashSet<String>()
        val candidates = ArrayList<OutingRecord>()
        // 上次扫到哪：从更早的记录接着扫，条数上限才不会被最新那批已处理的记录白白吃掉。
        var cursor = savedCursor()
        val resumedFrom = cursor
        var pages = 0
        var scanned = 0
        var work = 0
        var skippedDone = 0
        var stop = ""
        var hitScanLimit = false
        var reachedEnd = false
        var lastScannedAt = 0L

        if (scanDoneFlag() && pending.isNotEmpty()) {
            log("📺 上一段已扫描完，接着补 ${pending.size} 条")
            pending.forEach { candidates += OutingRecord(it, it, 1, "") }
        } else {
            if (pending.isNotEmpty()) log("📺 继续上次没扫完的那段（已有 ${pending.size} 条待补），扫完一起补")
            log(
                "📺 扫描起点：" + if (resumedFrom > 0L) "接着上次的位置（${stamp(resumedFrom)} 之前的记录）"
                else "从最新记录开始",
            )
            scan@ while (pages < maxPages) {
                if (cancelled()) { stop = "已取消"; break }
                if (scanned >= scanLimit) { hitScanLimit = true; break }
                val page = PetResult.read { service.story.history(PAGE, cursor) }.getOrNull()
                if (page == null) { stop = "记录读取失败"; break }
                if (page.records.isEmpty()) { reachedEnd = true; break }
                for (r in page.records) {
                    if (scanned >= scanLimit) { hitScanLimit = true; break@scan }
                    if (!seen.add(r.storyId)) continue
                    scanned++
                    if (r.atMs > 0) lastScannedAt = r.atMs
                    if (workOnly && r.kind != OutingKind.WORK) continue
                    work++
                    if (r.result <= 0) continue
                    if (r.storyId in done) { skippedDone++; continue }
                    candidates += r
                    pending += r.storyId
                }
                pages++
                report(
                    scanned = scanned,
                    pending = pending.size,
                    scanAt = lastScannedAt,
                    current = (if (resumedFrom > 0L) "接着上次的位置" else "从最新记录开始") + " · 本轮已扫描 $scanned 条",
                )
                val next = page.nextCursorMs
                if (next <= 0L || next == cursor) { reachedEnd = true; break }
                cursor = next
                saveCursor(cursor)
            }
            if (hitScanLimit) {
                stop = if (scanLimit == Int.MAX_VALUE) "" else "本次扫描条数已扫完"
                if (lastScannedAt > 0L) saveCursor(lastScannedAt)
            }
            savePending(pending)
            // 扫到一半取消/读取失败 → 这一段还没扫完，下一轮继续扫
            saveScanDone(reachedEnd || hitScanLimit)
        }

        var tried = 0
        var ok = 0
        var refused = 0
        var failed = 0
        var streak = 0
        report(scanned = scanned, total = candidates.size, skippedDone = skippedDone, pending = pending.size, scanAt = lastScannedAt, current = "")
        for (r in candidates) {
            if (cancelled()) { stop = "已取消"; break }
            tried++
            val res = service.rewardAd.watchStoryCoin(petId, r.storyId, background)
            when {
                res.ok -> {
                    ok++; streak = 0; done += r.storyId; pending -= r.storyId
                    log("📺 历史广告 ${r.title}（${r.storyId}）：已领")
                }
                res.remote.isNotEmpty() -> {
                    refused++; streak = 0; done += r.storyId; pending -= r.storyId
                    log("📺 历史广告 ${r.title}（${r.storyId}）：${res.message}，记为已处理")
                }
                else -> {
                    failed++; streak++
                    log("📺 历史广告 ${r.title}（${r.storyId}）：${res.message}")
                    if (res.message.contains("广告组件不可用")) { stop = "广告组件不可用"; break }
                    if (streak >= FAIL_STREAK_LIMIT) { stop = "连续 $FAIL_STREAK_LIMIT 条失败，停手"; break }
                }
            }
            saveDone(done)
            savePending(pending)
            report(
                scanned = scanned, total = candidates.size, done = tried, ok = ok,
                refused = refused, failed = failed, skippedDone = skippedDone, pending = pending.size,
                scanAt = lastScannedAt, current = r.title, stop = "",
            )
            Thread.sleep(1_000L + Random.nextLong(600L))
        }
        saveDone(done)
        savePending(pending)
        if (pending.isEmpty()) {
            // 这一段全部补完：下次重新开始新的一段；整轮扫到底了才回到最新记录
            saveScanDone(false)
            if (reachedEnd) saveCursor(0L)
        }
        if (stop.isEmpty() && candidates.isEmpty()) stop = "没有可补领的记录"
        report(
            running = false, scanned = scanned, total = candidates.size, done = tried,
            ok = ok, refused = refused, failed = failed, skippedDone = skippedDone,
            pending = pending.size, scanAt = lastScannedAt, stop = stop,
        )
        return Outcome(scanned, work, tried, ok, refused, failed, skippedDone, stop)
    }

    private fun doneSet(): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DONE, "").orEmpty()
            .split(',').filter { it.isNotBlank() }.toSet()

    private fun savedCursor(): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CURSOR, 0L)

    private fun stamp(ms: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))

    private fun saveCursor(ms: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_CURSOR, ms).apply()
    }

    private fun pendingIds(): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, "").orEmpty()
            .split(',').filter { it.isNotBlank() }.toSet()

    private fun savePending(ids: Collection<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING, ids.take(MAX_PENDING).joinToString(",")).apply()
    }

    /** 这一段记录是否已经扫完（扫完才能拿 pending 去补）。 */
    private fun scanDoneFlag(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCAN_DONE, false)

    private fun saveScanDone(v: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SCAN_DONE, v).apply()
    }

    private fun saveDone(set: Set<String>) {
        val csv = set.toList().takeLast(MAX_DONE).joinToString(",")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DONE, csv).apply()
    }
}