package io.github.juwanhwang.autoqqpet.pet

import org.json.JSONObject

/** 历史广告补领的跨进程进度（QQ 进程算 → 模块 App 显示）。 */
object AdSweepState {
    const val ACTION = "io.github.juwanhwang.autoqqpet.AD_SWEEP"
    const val EXTRA = "progress"
    const val CMD_STATUS = "adsweepstatus"

    data class Progress(
        val running: Boolean = false,
        val scanned: Int = 0,
        val total: Int = 0,
        val done: Int = 0,
        val ok: Int = 0,
        val refused: Int = 0,
        val failed: Int = 0,
        val skippedDone: Int = 0,
        val pending: Int = 0,
        /** 当前扫到哪条记录的时间戳（毫秒），0 表示还没开始/不适用。 */
        val scanAt: Long = 0,
        val current: String = "",
        val stop: String = "",
        val startedAt: Long = 0L,
        val endedAt: Long = 0L,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

        fun json(): String = JSONObject()
            .put("running", running)
            .put("scanned", scanned)
            .put("total", total)
            .put("done", done)
            .put("ok", ok)
            .put("refused", refused)
            .put("failed", failed)
            .put("skippedDone", skippedDone)
            .put("pending", pending)
            .put("scanAt", scanAt)
            .put("current", current)
            .put("stop", stop)
            .put("startedAt", startedAt)
            .put("endedAt", endedAt)
            .toString()

        companion object {
            fun parse(raw: String?): Progress? = runCatching {
                val o = JSONObject(raw ?: return null)
                Progress(
                    running = o.optBoolean("running"),
                    scanned = o.optInt("scanned"),
                    total = o.optInt("total"),
                    done = o.optInt("done"),
                    ok = o.optInt("ok"),
                    refused = o.optInt("refused"),
                    failed = o.optInt("failed"),
                    skippedDone = o.optInt("skippedDone"),
                    pending = o.optInt("pending"),
                    scanAt = o.optLong("scanAt"),
                    current = o.optString("current"),
                    stop = o.optString("stop"),
                    startedAt = o.optLong("startedAt"),
                    endedAt = o.optLong("endedAt"),
                )
            }.getOrNull()
        }
    }
}