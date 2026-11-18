package io.github.juwanhwang.autoqqpet.optimizer

import java.util.concurrent.ConcurrentHashMap

/**
 * “需要用户动手/知道”的提醒，按 id 分槽（v1 只有一个槽：任何一处 `alert(null)` 会把别人的提醒也撤掉）。
 * 随心跳整表发给模块 App（快照 `alerts` 字段）；旧的 `alert` extra 仍带“最新一条 action 级”文本，保持兼容。
 *
 * **可划掉**：每条提醒带一个“事件键” [Alert.incident]（默认=当天；召回=storyId；今晚火花=当天事件键）。
 * 用户在界面划掉 → `alert op=dismiss id incident` 回到 QQ → [dismiss] 记下 `id|incident`（持久，见 [persist]），
 * 同一事件之后再 [set] 都不再出现；换了事件（新的一天/新的被雇任务）才重新提醒。
 */
class Alerts {
    enum class Level { INFO, ACTION }

    data class Alert(val id: String, val level: Level, val text: String, val at: Long, val incident: String = "", val src: String = "")

    private val map = ConcurrentHashMap<String, Alert>()
    /** 已划掉的 `id|incident` → 划掉时刻。 */
    private val dismissed = ConcurrentHashMap<String, Long>()

    /** 持久化钩子（QQ 侧由 HostRuntime 接到 prefs；单测里为空）。 */
    @Volatile var persist: ((Map<String, Long>) -> Unit)? = null

    /**
     * 设一条；文本没变不刷新时间（通知侧按文本去重，避免反复响铃）。
     * [incident] 为空 = 当天（[dayKey]）；[src] 记来源（如信箱只认 `0x9c19`）。已被划掉的同一事件不再挂。
     */
    fun set(id: String, text: String, level: Level = Level.ACTION, incident: String? = null, src: String = "") {
        val inc = incident?.takeIf { it.isNotBlank() } ?: dayKey()
        if (dismissed.containsKey(key(id, inc))) { map.remove(id); return }
        val cur = map[id]
        if (cur != null && cur.text == text && cur.level == level && cur.incident == inc) return
        map[id] = Alert(id, level, text, System.currentTimeMillis(), inc, src)
    }

    fun clear(id: String) { map.remove(id) }

    fun get(id: String): Alert? = map[id]

    /** 用户划掉：[incident] 为空则按当前挂着的那条的事件键。@return 是否真划掉了一条 */
    fun dismiss(id: String, incident: String?): Boolean {
        val cur = map[id]
        val inc = incident?.takeIf { it.isNotBlank() } ?: cur?.incident ?: dayKey()
        dismissed[key(id, inc)] = System.currentTimeMillis()
        prune()
        persist?.invoke(HashMap(dismissed))
        if (cur != null && cur.incident == inc) { map.remove(id); return true }
        return false
    }

    /** 恢复持久化的划掉记录（启动时）。 */
    fun loadDismissed(m: Map<String, Long>) { dismissed.putAll(m); prune() }

    fun dismissedKeys(): Set<String> = dismissed.keys.toSet()

    /** 忘掉某类提醒的全部划掉记录（R7 新版本第一轮清信箱用）。 */
    fun forgetDismissed(id: String) {
        if (dismissed.keys.removeIf { it.startsWith("$id|") }) persist?.invoke(HashMap(dismissed))
    }

    fun all(): List<Alert> = map.values.sortedByDescending { it.at }

    /** 兼容旧通道：最新一条需要动手的文本（没有就 null → 模块 App 撤 1003 通知）。 */
    fun legacyText(): String? = map.values.filter { it.level == Level.ACTION }.maxByOrNull { it.at }?.text

    /** 只留 3 天内、最多 80 条。 */
    private fun prune(now: Long = System.currentTimeMillis()) {
        dismissed.entries.removeIf { now - it.value > 3 * 86_400_000L }
        if (dismissed.size > 80) dismissed.entries.sortedBy { it.value }.take(dismissed.size - 80).forEach { dismissed.remove(it.key) }
    }

    companion object {
        const val RECALL = "recall"
        const val CARE_BLOCKED = "care_blocked"
        const val PAY_TIRED = "pay_tired"
        const val FEE = "fee"
        const val IDLE = "idle"
        /** 信箱：**只**由 0x9c19 一键领取的结果挂（src=`0x9c19`，R7）。 */
        const val MAIL = "mail"
        const val MANUAL = "manual"
        /** 火花快清零（待重燃 ≥5 天，7 天清零）。 */
        const val SPARK = "spark"
        /** 火花今晚熄灭（在燃但今天没双向互踩）——与 [SPARK] 独立的 id 与通知渠道。 */
        const val SPARK_TONIGHT = "spark_tonight"
        const val HIRED = "hired"
        const val SRC_MAIL_CLAIM = "mailclaim" // 信箱一键领取的来源标记（不是命令号）

        fun key(id: String, incident: String) = "$id|$incident"
        fun dayKey(): String = "d" + io.github.juwanhwang.autoqqpet.pet.Day.today()

        /** 持久化编码：`id|incident@ms` 以换行分隔（incident 里不会有换行）。 */
        fun encode(m: Map<String, Long>): String = m.entries.joinToString("\n") { "${it.key}@${it.value}" }
        fun decode(s: String?): Map<String, Long> = s.orEmpty().split('\n').mapNotNull { line ->
            val i = line.lastIndexOf('@'); if (i <= 0) null else line.substring(0, i) to (line.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
    }
}
