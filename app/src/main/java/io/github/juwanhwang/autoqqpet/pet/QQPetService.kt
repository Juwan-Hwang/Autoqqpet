package io.github.juwanhwang.autoqqpet.pet

import io.github.juwanhwang.autoqqpet.pet.service.DebugApi
import io.github.juwanhwang.autoqqpet.pet.service.FriendApi
import io.github.juwanhwang.autoqqpet.pet.service.InventoryApi
import io.github.juwanhwang.autoqqpet.pet.service.MailApi
import io.github.juwanhwang.autoqqpet.pet.service.PetSession
import io.github.juwanhwang.autoqqpet.pet.service.ProfileApi
import io.github.juwanhwang.autoqqpet.pet.service.StoryApi
import io.github.juwanhwang.autoqqpet.pet.service.VipApi
import io.github.juwanhwang.autoqqpet.pet.service.DiaryApi
import io.github.juwanhwang.autoqqpet.pet.service.ItemApi
import io.github.juwanhwang.autoqqpet.pet.service.RewardAdApi

/**
 * 宠物协议门面：按命令族分文件（`pet/service/`），命令号只在 `net/Commands.kt`，回包解析只在 `pet/parse/Parsers.kt`。
 *
 * | 族 | 文件 | 命令 |
 * |---|---|---|
 * | 数值 | [ProfileApi] | 0x96f2（自己/好友）、0x9ad4 |
 * | 库存/照顾 | [InventoryApi] | 0x9949 0x9bf1 0x9bf2 0x992d 0x99df 0x9bf3 0x9bd0 0x9c6d |
 * | 主任务 | [StoryApi] | 0x975a 0x975f 0x9874 0x9b60 0x9ab2 0x975e 0x9760 0x9c44 0x96a4 0x96a6 |
 * | 好友 | [FriendApi] | 0x985d 0x985b 0x96a6 0x9875 0x9b80 0x9d70 0x9d71 0x9bb3 |
 * | 信箱 | [MailApi] | 0x9c11 |
 */
class QQPetService(bridge: QQPetBridge) {

    val session = PetSession(bridge)
    val profile = ProfileApi(session)
    val inventory = InventoryApi(session)
    val story = StoryApi(session)
    val friend = FriendApi(session)
    val mail = MailApi(session)
    val vip = VipApi(session)
    val diary = DiaryApi(session)
    val item = ItemApi(session)
    /** 激励视频（daily gift / 结算加金币）；宿主版本不匹配时内部静默降级。 */
    val rewardAd = RewardAdApi(bridge.classLoader())
    val debug = DebugApi(session)

    /** 本人 petId（读过资料后才有）。 */
    val petId: String get() = session.petId

    /** 本人 UIN（用于把自己从好友候选里排除）。 */
    fun selfUin(): String = session.selfUin()

    /** 发包入口类名（按形状发现的）；只供日志与“版本不适配”提示用。 */
    val entryName: String = bridge.entryName

    fun readOwnProfile(): OwnPetProfile = session.loadProfile()

    fun ensurePetId(): String = session.ensurePetId()

    /** 自己的数值（含金币）。 */
    fun readValues(): PetValues = profile.values()
}
