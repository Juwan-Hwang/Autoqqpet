package io.github.juwanhwang.autoqqpet.ui

import io.github.juwanhwang.autoqqpet.ui.data.TaskKind

/**
 * 统一术语与可复用文案（术语表见 docs/design/DESIGN.md「文案」）。
 * 一个概念只有一个叫法：托管 / 暂停托管、学习、打工、冒险、照顾、排班、心跳。
 */
object Copy {

    data class Mode(val key: String, val label: String, val detail: String)

    /** 干活方式：key 是写进 adv_mode 的值（不能改），label/detail 只是呈现。 */
    val modes = listOf(
        Mode("auto", "最优调度", "按下面选的目标算出今天的最优安排，每做完一趟重新算。"),
        Mode("mix", "学习优先", "旧逻辑，疲惫后转冒险。"),
        Mode("work", "打工优先", "旧逻辑，疲惫后转冒险。"),
        Mode("only", "只冒险", "不学习、不打工，只刷冒险；刷哪个在下面选。"),
        Mode("custom", "排班", "按你排好的顺序做，做完一步进下一步。"),
    )

    /** 最优调度的目标：key 写进 auto_goal。 */
    val autoGoals = listOf(
        Mode("gold", "金币优先", "今天以攒金币为主，冒险金币也算在内。"),
        Mode("points", "工分优先", "今天以攒工分为主，但最终金币不能比早上少；按当时折扣算净亏钱的工不排。"),
        Mode("attr", "属性优先", "今天以练属性为主，但最终金币不能比早上少。"),
    )

    fun autoGoalLabel(key: String): String = autoGoals.firstOrNull { it.key == key }?.label ?: "属性优先"

    /** 主页「怎么干活」只摆日常三挡；学习优先/打工优先是旧逻辑挡，收在设置里（modeLabel 仍认全部）。 */
    val homeModes = modes.filter { it.key in listOf("auto", "only", "custom") }

    fun modeLabel(key: String): String = modes.firstOrNull { it.key == key }?.label ?: modes.first().label

    val attrs = listOf("力量", "智力", "魅力", "均衡")

    fun kindLabel(k: TaskKind): String = when (k) {
        TaskKind.STUDY -> "学习"
        TaskKind.WORK -> "打工"
        TaskKind.ADVENTURE -> "冒险"
        TaskKind.CARE -> "照顾"
        TaskKind.REST -> "休息"
    }

    fun kindDoing(k: TaskKind): String = when (k) {
        TaskKind.STUDY -> "正在上课"
        TaskKind.WORK -> "正在打工"
        TaskKind.ADVENTURE -> "正在冒险"
        TaskKind.CARE -> "正在照顾"
        TaskKind.REST -> "在休息"
    }

    /** 2 小时 5 分 / 12 分 / 不到 1 分 */
    /** 分钟数 → 文案。**0 就是“0 分”**，绝不说“不到 1 分”（那只用于 0 < 秒 < 60，见 [durationSec]）。 */
    fun duration(minutes: Int): String = when {
        minutes <= 0 -> "0 分"
        minutes < 60 -> "$minutes 分"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }

    /** 秒数 → 文案：0 → “0 分”；0 < 秒 < 60 → “不到 1 分钟”；其余按分钟。 */
    fun durationSec(sec: Int): String = when {
        sec <= 0 -> "0 分"
        sec < 60 -> "不到 1 分钟"
        else -> duration(sec / 60)
    }

    /** 单条任务时长：不足 1 分钟就写秒（短冒险 45 秒），整分写分钟。 */
    fun durationSecExact(sec: Int): String = when {
        sec <= 0 -> "0 分"
        sec < 60 -> "$sec 秒"
        sec % 60 != 0 && sec < 600 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> duration((sec + 30) / 60)
    }

    /** 倒计时 mm:ss 或 h:mm:ss。 */
    fun countdown(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 给读屏用的倒计时。 */
    fun countdownSpoken(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h > 0 -> "还剩 $h 小时 $m 分钟"
            m > 0 -> "还剩 $m 分钟"
            else -> "不到 1 分钟"
        }
    }

    /** “刚刚 / 3 分钟前 / 2 小时前”。 */
    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 10 -> "刚刚"
            s < 60 -> "$s 秒前"
            s < 3600 -> "${s / 60} 分钟前"
            s < 86_400 -> "${s / 3600} 小时前"
            else -> "${s / 86_400} 天前"
        }
    }

    fun clock(at: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

    const val NOT_TENCENT = "与腾讯无关"
}
