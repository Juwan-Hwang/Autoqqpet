package io.github.juwanhwang.autoqqpet.pet

import java.time.LocalDate

/** 日志文件名 `yyyy-MM-dd.log` ↔ 日期（纯函数，有单测）。 */
object LogRetention {
    fun dateOf(fileName: String): LocalDate? =
        if (!fileName.endsWith(".log")) null else runCatching { LocalDate.parse(fileName.removeSuffix(".log")) }.getOrNull()

    /** 保留 [keepDays] 天（含今天）时，[fileName] 该不该删。不认识的文件名一律删（目录只放日志）。 */
    fun shouldDelete(fileName: String, today: LocalDate, keepDays: Int): Boolean {
        val d = dateOf(fileName) ?: return true
        return d.isBefore(today.minusDays((keepDays.coerceIn(1, 14) - 1).toLong()))
    }
}
