package io.github.juwanhwang.autoqqpet.optimizer

/**
 * 短冒险节奏（D5/D6，2026-10-16 日志：疲劳削档后只剩“附近走走”时反复下发、连续撞超时，疑似被服务端限流）。
 *  - 两次短冒险之间至少隔 [gapMs]（设置 `short_adv_gap_s`，默认 20s）；
 *  - 连续失败/未确认 n 次后退避 1→2→4→8→16→30 分钟（成功一次清零）。
 * 纯逻辑、线程安全、时间由调用方传入（单测用）。
 */
class ShortAdventurePace {
    private var lastSentAt = 0L
    private var fails = 0
    private var backoffUntil = 0L

    @Synchronized fun noteSent(now: Long) { lastSentAt = now }
    @Synchronized fun noteOk() { fails = 0; backoffUntil = 0L }

    @Synchronized fun noteFail(now: Long) {
        fails++
        backoffUntil = now + backoffMs(fails)
    }

    /** 还要等多久（ms，0 = 现在就能开）。 */
    @Synchronized fun waitMs(now: Long, gapMs: Long): Long {
        val byGap = if (lastSentAt > 0 && gapMs > 0) lastSentAt + gapMs - now else 0L
        val byBackoff = if (backoffUntil > now) backoffUntil - now else 0L
        return maxOf(byGap, byBackoff, 0L)
    }

    @get:Synchronized val failures: Int get() = fails

    @Synchronized fun describe(): String = if (fails == 0) "按最小间隔" else "连续未成功 $fails 次，退避 ${backoffMs(fails) / 60_000} 分钟"

    companion object {
        fun backoffMs(fails: Int): Long = if (fails <= 0) 0L else (60_000L shl (fails - 1).coerceAtMost(5)).coerceAtMost(30 * 60_000L)
    }
}

/** 任务到期时刻（B7）：有服务器开始时间戳（秒）+ 总时长时以它为准；否则退回“剩余秒/总时长 + 现在”。 */
object TaskClock {
    fun endAt(startSec: Long, durationSec: Long, now: Long, remainingSec: Long = -1): Long = when {
        startSec > 1_000_000_000L && durationSec > 0 -> startSec * 1000 + durationSec * 1000
        remainingSec >= 0 -> now + remainingSec * 1000
        else -> now + durationSec.coerceAtLeast(0) * 1000
    }
}
