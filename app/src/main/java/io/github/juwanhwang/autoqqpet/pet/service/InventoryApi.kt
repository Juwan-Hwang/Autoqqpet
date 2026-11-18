package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.BathItem
import io.github.juwanhwang.autoqqpet.pet.FoodItem
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers

/** 照顾/库存族：食物、洗护、商城、喂食、洗澡、签到食物。 */
class InventoryApi(private val s: PetSession) {

    fun foods(): List<FoodItem> = Parsers.foods(s.read(Commands.FEED_TIMES, ByteArray(0)))

    fun biscuits(): Long = foods().firstOrNull { it.foodId == Proto.BISCUIT_FOOD_ID }?.balance ?: 0L

    fun bathItems(): List<BathItem> = Parsers.bathItems(s.read(Commands.BATH_ITEM_CONFIG, ProtoBuf.fieldVarint(1, 1)))

    fun bathInventory(): Map<String, Long> = Parsers.bathInventory(s.read(Commands.BATH_INVENTORY, ProtoBuf.fieldVarint(1, 1)))

    fun soap(): Long = bathInventory()[Proto.SOAP_ITEM_ID] ?: 0L

    /** 喂自己 FEED(0x992d_1)：f4=petId，f11=foodId。回包常为空，成功与否靠前后体力差判。 */
    fun feed(foodId: String = Proto.BISCUIT_FOOD_ID): String =
        Parsers.describe(s.write(Commands.FEED) { pb { str(4, s.ensurePetId()); if (foodId.isNotEmpty()) str(11, foodId) } })

    /**
     * 喂好友 FEED(0x992d_1)，抓包实测 2026-10-14 20:49:30 / 23:49：
     * `{1:好友uin（串）, 2:零宽占位昵称, 4:好友petId, 5:0, 10:{6:1}, 11:"1"}`。
     * 回包为空 → 调用方用好友体力前后差判生效。
     */
    fun feedFriend(friendUin: String, friendPetId: String, foodId: String = Proto.BISCUIT_FOOD_ID): String =
        Parsers.describe(s.write(Commands.FEED) { pb {
            str(1, friendUin); str(2, Proto.ZW_NICK); str(4, friendPetId); num(5, 0); msg(10) { num(6, 1) }; str(11, foodId)
        } })

    /**
     * 代喂后的推送 FEED_PUSH(0x99ef_1)：`{1:好友uin串, 2:零宽昵称, 4:1, 5:0, 6:"1"}`（交接 §9.14；f4/f6 产品上只能是 1）。
     * 页面喂完 2 秒会自己发；我们走 delegate 代喂不会触发，开关打开才补发（对方才收得到“谁喂了我”）。
     */
    fun feedPush(friendUin: String): String = Parsers.describe(s.write(Commands.FEED_PUSH) { pb {
        str(1, friendUin); str(2, Proto.ZW_NICK); num(4, 1); num(5, 0); str(6, "1")
    } })

    /** 买饼干 BUY_FOOD(0x99df_1)：f1=数量；resp f1 余量,f2 金币,f3 买到,f4 花费。 */
    fun buyFood(count: Int): String {
        val root = ProtoBuf.parseMessage(s.write(Commands.BUY_FOOD) { ProtoBuf.fieldVarint(1, count.toLong()) })
        return "balance=${ProtoBuf.firstVarint(root, 1)} gold=${ProtoBuf.firstVarint(root, 2)} bought=${ProtoBuf.firstVarint(root, 3)} cost=${ProtoBuf.firstVarint(root, 4)}"
    }

    /**
     * 洗澡 DO_BATH(0x9bf3_1)。
     * 自身：`{1:自己petId, 2:itemId, 3:数量}`；
     * 好友（抓包实测 2026-10-14 20:49:35）：`{1:**好友petId**, 2:itemId, 3:数量, 4:好友uin}`。
     * ⚠ 旧版代洗把 f1 填成了**自己的** petId（只多带了 f4），与抓包不符，已改。
     * 回包 {1:清洁,2:心情,3:剩余,…}。
     */
    fun bath(itemId: String = Proto.SOAP_ITEM_ID, count: Int = 1, friendUin: String = "", friendPetId: String = ""): String =
        bathRaw(itemId, count, friendUin, friendPetId).let { resp ->
            if (resp.isEmpty()) "(空回包)" else ProtoBuf.parseMessage(resp).let { r ->
                "clean=${ProtoBuf.firstVarint(r, 1)} mood=${ProtoBuf.firstVarint(r, 2)} remaining=${ProtoBuf.firstVarint(r, 3)}"
            }
        }

    /** 同 [bath]，返回原始回包（f1 = 洗后清洁，可省一次复读，见 [Parsers.bathClean]）。 */
    fun bathRaw(itemId: String = Proto.SOAP_ITEM_ID, count: Int = 1, friendUin: String = "", friendPetId: String = ""): ByteArray {
        val target = if (friendUin.isNotEmpty() && friendPetId.isNotEmpty()) friendPetId else s.ensurePetId()
        return s.write(Commands.DO_BATH) { pb {
            str(1, target); str(2, itemId); num(3, count)
            if (friendUin.isNotEmpty()) str(4, friendUin)
        } }
    }

    /** 买洗护品 BUY_BATH_ITEM(0x9bd0_0)：f1={1:平台,2:1001,3:petId}, f2=1001, f3={1:355,2:itemId,3:数量}, f4=场景21。成功码 1。 */
    fun buyBathItem(itemId: String, count: Int): String {
        require(count > 0) { "数量必须>0" }
        val resp = s.write(Commands.BUY_BATH_ITEM) { pb {
            msg(1) { num(1, 1); num(2, Proto.MALL_CURRENCY); str(3, s.ensurePetId()) }
            num(2, Proto.MALL_CURRENCY)
            msg(3) { num(1, Proto.MALL_CATEGORY_BATH); num(2, itemId.toLongOrNull() ?: 1L); num(3, count) }
            num(4, 21)
        } }
        val r = if (resp.isEmpty()) emptyMap() else ProtoBuf.parseMessage(resp)
        return "result=${ProtoBuf.firstVarint(r, 1)} order='${ProtoBuf.firstString(r, 2)}'"
    }

    /**
     * 每日签到领食物 CHECKIN_FOOD(0x9c6d_0) `{1:petId, 100:2}`（抓包实测 2026-10-15 01:05，一发即成功）。
     * @return 领后**现有库存**（回包 f1）；null = 空回包 = 今天已经领过。
     */
    fun claimCheckinFood(): Long? =
        Parsers.checkinStock(s.write(Commands.CHECKIN_FOOD) { pb { str(1, s.ensurePetId()); num(100, Proto.OUTDOOR_V) } })
}
