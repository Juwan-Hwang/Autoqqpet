package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.pet.Attributes
import io.github.juwanhwang.autoqqpet.pet.DailyExp
import io.github.juwanhwang.autoqqpet.pet.PKPower
import io.github.juwanhwang.autoqqpet.pet.PetValues
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers

/** 数值族：0x96f2 数值/金币（自己与好友同一条）、0x9ad4 战力。 */
class ProfileApi(private val s: PetSession) {

    /**
     * 数值 DISPLAY(0x96f2_1)：body f1=petId, f2=0x01 取四维；f2=0x06 取金币。
     * **f1 传好友 petId 就是好友的数值**（抓包实测 2026-10-14 20:49:32，回包 100.0/60.0 两个 float 对得上）。
     * [withGold]=false 时少发一个包（好友不需要金币）。
     */
    fun values(petId: String = s.ensurePetId(), withGold: Boolean = true): PetValues {
        val common = Parsers.displayRoot(s.read(Commands.DISPLAY, pb { str(1, petId); bytes(2, byteArrayOf(0x01)) }))
        val gold = if (withGold) Parsers.displayRoot(s.read(Commands.DISPLAY, pb { str(1, petId); bytes(2, byteArrayOf(0x06)) })) else null
        return Parsers.values(common, gold)
    }

    /** 好友三属性：同一条 0x96f2，f1=好友 petId（取代旧的 0x976c 递归找字段）。 */
    fun friendValues(friendPetId: String): PetValues = values(friendPetId, withGold = false)

    /** PK_POWER(0x9ad4_1)：body f1=petId,f100=2。 */
    fun pkPower(petId: String = s.ensurePetId()): PKPower =
        Parsers.pkPower(petId, s.read(Commands.PK_POWER, pb { str(1, petId); num(100, 2) }))

    /** 诊断：旧的 0x976c 原始字段树（f1=好友 UIN）。 */
    fun friendProfileRaw(friendUin: String): ByteArray = s.read(Commands.FRIEND_PROFILE, ProtoBuf.fieldString(1, friendUin))

    /**
     * 三属性总量 JOB_TREE(0x9b62_1) `{1:petId, 100:2}`；**petId 传好友的就是好友那份**（交接 §1，好友B 1503/1700/1571）。
     * 回包约 36KB；解析失败/缺项返回 null。战力 = 三者之和。
     */
    fun attributes(petId: String = s.ensurePetId()): Attributes? =
        Parsers.attributes(s.read(Commands.JOB_TREE, pb { str(1, petId); num(100, Proto.OUTDOOR_V) }))

    /** 每日经验 DAILY_EXP(0x9abe_0) `{1:petId, 2:1}`（交接 §9.2–9.4）。 */
    fun dailyExp(): DailyExp? = Parsers.dailyExp(s.read(Commands.DAILY_EXP, pb { str(1, s.ensurePetId()); num(2, 1) }))
}
