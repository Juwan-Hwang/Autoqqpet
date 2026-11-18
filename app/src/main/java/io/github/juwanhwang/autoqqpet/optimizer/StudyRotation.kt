package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.Attr

/**
 * 均衡学习（2026-10-20 用户定）：目标属性选「均衡」时，学习在力量→智力→魅力之间轮着来。
 *
 * 各属性课程的收益一样，没必要在属性之间挑来挑去——轮转最简单也最可预测。
 * 指针存 DailyStore（跨天保留），每真正派出一趟学习就前进一步：
 *  - 本轮生效属性 = 从指针处按轮转顺序数的第一个「有可用课程」的属性（轮到的那个正好没课，
 *    就顺延到下一个有课的，别为等一门课停工）；三个都没课时用轮到的那个，上层自有回退；
 *  - 派出后指针跳到「实际派出的那门课的属性」的下一个（顺延派出的按顺延的算）；
 *  - 随机属性课不算任何一个属性的趟，指针不动；排班步自带属性，也不走轮转。
 *
 * 纯逻辑（单测用）。
 */
object StudyRotation {

    /** 轮转顺序：力量→智力→魅力→力量…… */
    val ORDER: List<Attr> = listOf(Attr.STRENGTH, Attr.INTEL, Attr.CHARM)

    /** 序号对应的属性（任意整数序号都收，自动取模）。 */
    fun attrAt(index: Int): Attr = ORDER[((index % ORDER.size) + ORDER.size) % ORDER.size]

    /** 从 [index] 起的完整轮转顺序（本轮属性优先级）。 */
    fun orderFrom(index: Int): List<Attr> = ORDER.indices.map { attrAt(index + it) }

    /** 本轮生效属性：轮转顺序里第一个有可用课程的；都没有就用轮到的那个。 */
    fun resolve(index: Int, available: Set<Attr>): Attr =
        orderFrom(index).firstOrNull { it in available } ?: attrAt(index)

    /** 派出 [used] 属性的课之后，指针应指向的序号；[used] 不在轮转里返回原值。 */
    fun indexAfter(index: Int, used: Attr): Int =
        if (used in ORDER) (ORDER.indexOf(used) + 1) % ORDER.size else index
}
