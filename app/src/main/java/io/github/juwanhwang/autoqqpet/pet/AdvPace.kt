package io.github.juwanhwang.autoqqpet.pet

/**
 * 冒险真实耗时记账（纯逻辑，可测）：按「名字 × 天」分桶，记每次派发的墙钟耗时
 * （派发→结案）与其中跑完的趟数，滚动最近 [WINDOW_DAYS] 天聚合出
 * 「每完成一趟的真实耗时」，供求解器修准冒险时速。
 *
 * 为什么需要：一趟冒险的真实墙钟不止目录名义时长——还有结算等待、任务间隔、决策读包，
 * 以及被天色不对召回的短趟（约 6 秒就召回、整轮二三十秒，不产出任何东西却实打实吃掉时间）。
 * 只按名义时长算时速会系统性高估（2026-10-19 真机：短冒险实测约 1.7 分钟才完成一趟，
 * 模型按名义 45 秒算）。口径：被召回/未结算的趟只进总耗时、不进完成数，于是
 * 「总耗时 ÷ 完成数」天然把召回趟的墙钟摊进每一趟完成里；自动召回关着时天气趟跑满，
 * 走正常结算路径记为完成，口径自动一致。
 *
 * 存量格式（DailyStore 的 `adv_pace`）：`名字|天:派发数:完成数:总耗时ms;…`，
 * 与 `reward_avg` 同一 prefs、同一「; 分条、字段 : 分隔」的既有风格。名字含分隔符的
 * 条目解析时跳过（冒险目录名是普通中文名，与 reward_avg 按名字记键的既有暴露面相同）。
 */
internal object AdvPace {

    /** 滚动窗口：只看最近这么多天（今天在内）。 */
    const val WINDOW_DAYS = 7

    /** 窗口内完成样本至少这么多趟才信实测值；不足返回 null，求解器回退名义时长（冷启动与现版一致）。 */
    const val MIN_COMPLETIONS = 3

    /** 桶数上限（名字数 × 天数），防无界增长；超了先丢最旧的天。 */
    const val MAX_BUCKETS = 96

    data class Bucket(val name: String, val day: Int, val dispatches: Long, val completions: Long, val elapsedMs: Long)

    fun parse(text: String?): List<Bucket> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val bar = part.lastIndexOf('|')
            if (bar <= 0) return@mapNotNull null
            val f = part.substring(bar + 1).split(':')
            if (f.size != 4) return@mapNotNull null
            val day = f[0].toIntOrNull() ?: return@mapNotNull null
            val disp = f[1].toLongOrNull() ?: return@mapNotNull null
            val done = f[2].toLongOrNull() ?: return@mapNotNull null
            val ms = f[3].toLongOrNull() ?: return@mapNotNull null
            Bucket(part.substring(0, bar), day, disp, done, ms)
        }
    }

    fun format(buckets: List<Bucket>): String =
        buckets.joinToString(";") { "${it.name}|${it.day}:${it.dispatches}:${it.completions}:${it.elapsedMs}" }

    /**
     * 记一笔并返回新存量：同名字同天合并进同一桶；顺手清掉窗口外的旧桶
     * （以这笔的 [day] 为右界）与超量旧桶。耗时 ≤0 的不记（测不出时长就没有信息量）。
     */
    fun recorded(text: String?, name: String, day: Int, elapsedMs: Long, completed: Boolean): String {
        if (name.isBlank() || elapsedMs <= 0) return text ?: ""
        val buckets = parse(text).toMutableList()
        val i = buckets.indexOfFirst { it.name == name && it.day == day }
        if (i >= 0) {
            val b = buckets[i]
            buckets[i] = b.copy(
                dispatches = b.dispatches + 1,
                completions = b.completions + if (completed) 1 else 0,
                elapsedMs = b.elapsedMs + elapsedMs,
            )
        } else {
            buckets += Bucket(name, day, 1, if (completed) 1 else 0, elapsedMs)
        }
        val floor = day - WINDOW_DAYS + 1
        val kept = buckets.filter { it.day >= floor }.sortedBy { it.day }
        return format(if (kept.size > MAX_BUCKETS) kept.takeLast(MAX_BUCKETS) else kept)
    }

    /** 某名字在窗口内的「每完成一趟真实耗时（分钟）」；完成样本不足返回 null。 */
    fun minutesPerCompletion(text: String?, name: String, today: Int): Double? {
        var done = 0L
        var ms = 0L
        for (b in parse(text)) {
            if (b.name != name || b.day !in (today - WINDOW_DAYS + 1)..today) continue
            done += b.completions
            ms += b.elapsedMs
        }
        return if (done >= MIN_COMPLETIONS) ms.toDouble() / done / 60_000.0 else null
    }

    /** 窗口内全部有足够样本的名字的实测值（求解器构造时一次取全表）。 */
    fun minutesByName(text: String?, today: Int): Map<String, Double> {
        val sums = LinkedHashMap<String, LongArray>()
        for (b in parse(text)) {
            if (b.day !in (today - WINDOW_DAYS + 1)..today) continue
            val a = sums.getOrPut(b.name) { longArrayOf(0, 0) }
            a[0] += b.completions
            a[1] += b.elapsedMs
        }
        return sums.entries.mapNotNull { (name, a) ->
            if (a[0] >= MIN_COMPLETIONS) name to (a[1].toDouble() / a[0] / 60_000.0) else null
        }.toMap()
    }
}
