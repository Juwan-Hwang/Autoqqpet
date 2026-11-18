package io.github.juwanhwang.autoqqpet.pet

/**
 * 服务器疲劳文案信号（2026-10-15）：目录项、开工回包、结算回包、错误 detail 里出现“疲惫/疲劳/累了/很累/太累”等字样，
 * 就直接判该档（或全部学工）今日疲劳——**优先级高于数值**；只做子串弱匹配，记录原文。
 *
 * 样本情况（如实）：仓库里**没有**我方宠物疲劳文案的真机样本；唯一出处是 Cooky 参考里的
 * “该好友今天很累了，无法继续被雇佣”——那是**好友不能再被雇**，不是我方疲劳，所以带“好友/雇佣”的句子一律排除。
 */
object FatigueText {
    private val HIT = Regex("疲惫|疲劳|累了|很累|太累|累坏|没力气|筋疲力尽")
    private val EXCLUDE = Regex("好友|雇佣|被雇")

    /** @return 命中的原文（截 60 字）；null = 没命中。 */
    fun detect(vararg texts: String?): String? = texts.asSequence().filterNotNull().map { it.trim() }
        .firstOrNull { it.isNotEmpty() && HIT.containsMatchIn(it) && !EXCLUDE.containsMatchIn(it) }?.take(60)

    /** 目录项的所有文本字段。 */
    fun detect(o: SceneOption): String? = detect(o.unavailableReason, o.description, o.extra, o.reward)
}
