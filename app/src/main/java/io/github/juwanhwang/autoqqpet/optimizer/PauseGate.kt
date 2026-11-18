package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.SparkTonight
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 暂停闸（公开仓 #11，作者已承诺「下个版本加上」）：两个开关任一生效就全停——
 * 息屏暂停（屏幕熄灭期间）与夜间暂停（自定时段，默认 1:30–6:30）。暂停期间 runner 不发任何包：
 * 不派新活、不照顾、状态轮询与到期结算都延后；恢复后按既有迟到结算/断点恢复路径续上
 * （到期不发领奖包也到账，结算明细与出门记录对账都能吃数小时延迟，见 AutoRunner.settlePhase）。
 * 纯逻辑：时间与屏幕状态都由调用方传入，可单测。
 */
object PauseGate {
    /** 夜间时段默认值（当天第几分钟）：1:30 开始、6:30 结束，与 `night_pause_start/end` 设置的默认值一致。 */
    const val DEFAULT_START_MIN = 1 * 60 + 30
    const val DEFAULT_END_MIN = 6 * 60 + 30
    /** 息屏暂停的复查间隔：亮屏后最多晚这么久就恢复。 */
    const val SCREEN_RECHECK_MS = 60_000L

    /** 命中暂停时：[text] 是给状态行/日志看的原因，[armMs] 是下一次唤醒排多久。 */
    data class Pause(val text: String, val armMs: Long)

    /**
     * 时刻是否落在夜间时段内（结束时刻不含）。开始 < 结束是当天时段；开始 > 结束表示跨过零点
     * （如 23:00–02:00：23:00 起到次日 02:00 前）；开始 == 结束视为空时段，永不命中。
     * 起止按分钟比，秒数不参与（时段设置本身只有分钟粒度）。
     */
    fun inWindow(t: LocalTime, startMin: Int, endMin: Int): Boolean {
        val m = t.hour * 60 + t.minute
        return when {
            startMin < endMin -> m >= startMin && m < endMin
            startMin > endMin -> m >= startMin || m < endMin
            else -> false
        }
    }

    /**
     * @param screenOn 息屏暂停开关；[nightOn] 夜间暂停开关（任一生效即暂停，同时生效取并集）
     * @param nightStartMin / [nightEndMin] 夜间时段起止（当天第几分钟，来自设置）
     * @param interactive 屏幕当前是否亮着（QQ 进程侧 PowerManager.isInteractive）
     * @return 命中返回 [Pause]，否则 null。夜间与息屏同时命中时按夜间算（一次睡到时段结束再复查屏幕）。
     */
    fun check(screenOn: Boolean, nightOn: Boolean, nightStartMin: Int, nightEndMin: Int, interactive: Boolean, now: LocalDateTime): Pause? {
        if (nightOn && inWindow(now.toLocalTime(), nightStartMin, nightEndMin)) {
            val endToday = now.toLocalDate().atTime(nightEndMin / 60, nightEndMin % 60)
            val end = if (endToday.isAfter(now)) endToday else endToday.plusDays(1)
            val ms = Duration.between(now, end).toMillis().coerceAtLeast(1_000L)
            return Pause("夜间暂停中（${SparkTonight.hhmm(nightStartMin)}–${SparkTonight.hhmm(nightEndMin)} 不发包）", ms)
        }
        if (screenOn && !interactive) return Pause("息屏暂停中（亮屏后继续）", SCREEN_RECHECK_MS)
        return null
    }
}
