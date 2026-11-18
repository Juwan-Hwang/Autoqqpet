package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.ItemStack
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import kotlin.random.Random

/**
 * 任务道具族（2026-11-11 抓包）：使用命令只传**族 id**，服务端按该号背包里的 SKU 扣，
 * 所以不同账号持有什么 SKU 都能自动用。
 */
object ItemFamily {
    /** 学习加成（高钙奶/咕噜酸奶…），每杯 +1 属性、提高 A+ 概率；每趟学习限 1 杯（135109）。 */
    const val STUDY = 356L
    /** 打工加成（元气美式/波波奶茶…），每杯 +金币、提高打工表现。 */
    const val WORK = 357L
    /** 场景号：1=打工、2=学习。 */
    fun scene(family: Long): Long = if (family == STUDY) 2L else 1L
}

class ItemApi(private val s: PetSession) {

    /** 0x9bd5：按族查背包 `{1:族id, 2:petId}`。 */
    fun inventory(family: Long): List<ItemStack> = Parsers.itemInventory(
        s.read(Commands.ITEM_INVENTORY, pb { num(1, family); str(2, s.ensurePetId()) }),
    )

    /**
     * 0x9d9e：使用 `{1:petId, 2:storyId, 3:族id, 4:{1:族id,2:场景,3:1}, 5:trace}`。
     * 回包是效果描述（如「金币 +500」）；学习重复喝会拒 135109。
     */
    fun use(family: Long, storyId: String): String = Parsers.describe(s.write(Commands.ITEM_USE) { pb {
        str(1, s.ensurePetId()); str(2, storyId); num(3, family)
        msg(4) { num(1, family); num(2, ItemFamily.scene(family)); num(3, 1) }
        str(5, "${System.currentTimeMillis()}_${Random.nextInt(100_000, 10_000_000)}")
    } })

    /** 0x9e9c：每日免费学习道具（调用即领，幂等），回包是福利列表。 */
    fun claimDaily(): String = Parsers.describe(s.write(Commands.DAILY_ITEM) { pb { str(1, s.ensurePetId()) } })
}
