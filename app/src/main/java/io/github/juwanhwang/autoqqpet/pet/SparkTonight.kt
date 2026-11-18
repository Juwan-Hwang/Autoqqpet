package io.github.juwanhwang.autoqqpet.pet

import java.time.LocalTime

/**
 * 火花“今晚会熄灭”提醒（2026-10-15 新增，纯函数，可单测）。
 *
 * 与 [SparkPolicy.atRisk]（“快清零”：待重燃且已断 ≥5 天，连续 7 天不互踩清零）是**两件事**：
 *  - 本提醒：火花**正在燃**（阶段 1 已点亮），但**今天还没完成互踩** → 过了 24 点就熄灭、进入“待重燃”；
 *  - 快清零：已经在“待重燃”里断了好几天，再断就清零。
 * 两者用不同的提醒 id（[io.github.juwanhwang.autoqqpet.optimizer.Alerts.SPARK_TONIGHT] / `SPARK`）与不同的通知渠道，可以分别开关。
 *
 * “完成互踩” = 我今天踩了对方（本模块踩记录，0x985b 成功或 136202）**并且**对方今天踩了我（0x985e 被踩明细“今日”里有他），
 * 或者火花列表文案已经显示今日已互踩。单向的分开写“你还没踩 / 对方还没踩你”。黑名单不提醒。
 * 数据（0x9b87 / 0x985e）读不到就**不提醒**（实验请求体，宁可不报也不误报）。
 */
object SparkTonight {

    const val DEFAULT_AT_MIN = 21 * 60

    data class Miss(val uin: String, val nick: String, val iPoked: Boolean, val theyPoked: Boolean, val whitelisted: Boolean)

    sealed interface Verdict {
        /** 还没到时间（或已过午夜，属于新的一天）。 */
        data object NotYet : Verdict
        /** 数据缺失：不提醒，只在诊断里记一行。 */
        data class NoData(val what: String) : Verdict
        /** 都互踩完了（或没有在燃的火花）→ 撤掉今天的提醒。 */
        data object AllDone : Verdict
        data class Remind(val misses: List<Miss>, val text: String, val key: String) : Verdict
    }

    /** 到点了吗：设定时刻 ≤ 现在 < 24:00（按设备本地时区；过了午夜就是新一天）。 */
    fun due(now: LocalTime, atMinuteOfDay: Int): Boolean {
        val at = atMinuteOfDay.coerceIn(0, 24 * 60 - 1)
        return now.hour * 60 + now.minute >= at
    }

    fun evaluate(
        now: LocalTime,
        atMinuteOfDay: Int,
        sparks: List<SparkEntry>?,
        visitors: List<LikeVisitor>?,
        pokedToday: Set<String>,
        whitelist: Set<String>,
        blacklist: Set<String>,
        selfUin: String = "",
    ): Verdict {
        if (!due(now, atMinuteOfDay)) return Verdict.NotYet
        if (sparks == null) return Verdict.NoData("火花列表")
        if (visitors == null) return Verdict.NoData("被踩明细")
        val visitedMe = visitors.filter { it.today }.map { it.uin }.toSet()
        val misses = sparks.asSequence()
            .filter { it.stage == SparkStage.LIT && it.uin.isNotEmpty() && it.uin != selfUin && it.uin !in blacklist && !it.pokedToday }
            .map { Miss(it.uin, it.nick.ifBlank { it.uin }, it.uin in pokedToday, it.uin in visitedMe, it.uin in whitelist) }
            .filter { !(it.iPoked && it.theyPoked) }
            .distinctBy { it.uin }
            .toList()
        if (misses.isEmpty()) return Verdict.AllDone
        return Verdict.Remind(misses, text(misses), key(misses))
    }

    /** 事件键：同一批人 = 同一事件（去重用）。 */
    fun key(misses: List<Miss>): String = misses.map { it.uin }.sorted().joinToString(",")

    fun text(misses: List<Miss>): String {
        fun names(l: List<Miss>) = SparkPolicy.names(l.map { it.nick }, 3)
        val head = "今晚 24 点前不互踩，和 ${names(misses)} 的火花会熄灭（进入待重燃）。"
        // 白名单的人我方通常已经踩过 → 重点提示“对方还没踩你”，排在前面
        val theyNot = misses.filter { !it.theyPoked }.sortedByDescending { it.whitelisted }
        val iNot = misses.filter { !it.iPoked }
        val parts = ArrayList<String>()
        if (theyNot.isNotEmpty()) parts += "对方还没踩你：${names(theyNot)}"
        if (iNot.isNotEmpty()) parts += "你还没踩：${names(iNot)}"
        return head + parts.joinToString("；")
    }

    /** 判定 → 对提醒槽做什么（一天只“响”一次 + 按事件去重）。 */
    enum class Action { NONE, LOG_NO_DATA, NOTIFY, UPDATE_SILENT, KEEP, CLEAR }

    /**
     * @param sentToday 今天是否已经发出过（响过）一次
     * @param currentKey 提醒槽里现在挂着的事件键（null = 没挂）
     */
    fun action(v: Verdict, sentToday: Boolean, currentKey: String?, noDataLoggedToday: Boolean = false): Action = when (v) {
        Verdict.NotYet -> if (currentKey != null) Action.CLEAR else Action.NONE
        is Verdict.NoData -> if (noDataLoggedToday) Action.NONE else Action.LOG_NO_DATA   // 不报、不撤，只记一行
        Verdict.AllDone -> if (currentKey != null) Action.CLEAR else Action.NONE
        is Verdict.Remind -> when {
            !sentToday -> Action.NOTIFY
            currentKey == v.key -> Action.KEEP                 // 同一事件不重复推
            currentKey == null -> Action.NONE                  // 今天响过且已撤/被划掉：不再推第二次
            else -> Action.UPDATE_SILENT                       // 名单变了（有人踩完）：静默更新
        }
    }

    /** 分钟数 ↔ "HH:mm"（界面用）。 */
    fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60 % 24, minuteOfDay % 60)
}
