package io.github.juwanhwang.autoqqpet.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.juwanhwang.autoqqpet.ui.settings.Group

/**
 * 主页提醒条每一种提醒“下一步做什么”（纯函数，可单测）。以前一律“看详情 → 诊断页”，用户点进去找不到要处理的东西。
 * 按钮上直接写去向（“去设置 · 照顾与补给”），不让人猜。每条后端提醒都可以划掉（[Act.DISMISS] 或右上角 ×）。
 */
object AlertActions {
    enum class Act { OPEN_QQ, DISMISS, RECALL, SETTINGS, SPARK, DIAGNOSE, MODULE_HELP }

    data class Btn(val label: String, val act: Act, val group: Group? = null)

    fun settings(g: Group) = Btn("去设置 · ${g.title}", Act.SETTINGS, g)

    /** 快照 `alerts[].id` → 按钮（第一个是主按钮）。 */
    fun forId(id: String): List<Btn> = when (id) {
        "mail" -> listOf(Btn("去 QQ 领", Act.OPEN_QQ), Btn("知道了", Act.DISMISS))
        "recall" -> listOf(Btn("立即召回", Act.RECALL))
        "care_blocked", "idle" -> listOf(settings(Group.CARE))
        "fee" -> listOf(settings(Group.MONEY))
        "pay_tired" -> listOf(Btn("知道了", Act.DISMISS))
        "spark", "spark_tonight" -> listOf(Btn("去火花页", Act.SPARK))
        else -> listOf(Btn("去诊断", Act.DIAGNOSE))
    }

    /** 旧后端只给一行 `alert` 文本（没有 id）：雇佣 → 召回；疲劳 → 知道了；其它 → 诊断。 */
    fun forLegacy(text: String): List<Btn> = when {
        text.contains("雇佣") -> listOf(Btn("立即召回", Act.RECALL))
        text.contains("疲劳") -> listOf(Btn("知道了", Act.DISMISS))
        else -> listOf(Btn("去诊断", Act.DIAGNOSE))
    }

    /** 是否已经有“知道了”按钮（有就不再画右上角 ×）。 */
    fun hasDismiss(b: List<Btn>) = b.any { it.act == Act.DISMISS }

    /** 旧文本提醒的本地划掉键（没有事件键，按文本认同一件事）。 */
    fun legacyKey(text: String) = "legacy|" + text.hashCode()

    fun key(id: String, incident: String) = "$id|$incident"
}

/** 设置页“跳到某一组”的一次性请求（主页提醒条按钮用）。 */
object SettingsAnchor {
    var pending by mutableStateOf<Group?>(null)
}
