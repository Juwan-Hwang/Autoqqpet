package io.github.juwanhwang.autoqqpet.pet.parse

import io.github.juwanhwang.autoqqpet.net.Msg
import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.pet.Attributes
import io.github.juwanhwang.autoqqpet.pet.BathItem
import io.github.juwanhwang.autoqqpet.pet.DailyExp
import io.github.juwanhwang.autoqqpet.pet.DiaryEntry
import io.github.juwanhwang.autoqqpet.pet.ItemStack
import io.github.juwanhwang.autoqqpet.pet.ExpTask
import io.github.juwanhwang.autoqqpet.pet.HiredNotice
import io.github.juwanhwang.autoqqpet.pet.LikeVisitor
import io.github.juwanhwang.autoqqpet.pet.Mail
import io.github.juwanhwang.autoqqpet.pet.SparkEntry
import io.github.juwanhwang.autoqqpet.pet.SparkStage
import io.github.juwanhwang.autoqqpet.pet.StoryEntry
import io.github.juwanhwang.autoqqpet.pet.StoryOutcome
import io.github.juwanhwang.autoqqpet.pet.VipGift
import io.github.juwanhwang.autoqqpet.pet.CareerInfo
import io.github.juwanhwang.autoqqpet.pet.CoinBag
import io.github.juwanhwang.autoqqpet.pet.FoodItem
import io.github.juwanhwang.autoqqpet.pet.FriendStatus
import io.github.juwanhwang.autoqqpet.pet.OwnPetProfile
import io.github.juwanhwang.autoqqpet.pet.PKPower
import io.github.juwanhwang.autoqqpet.pet.PageRules
import io.github.juwanhwang.autoqqpet.pet.PetValues
import io.github.juwanhwang.autoqqpet.pet.Reward
import io.github.juwanhwang.autoqqpet.pet.SceneOption
import io.github.juwanhwang.autoqqpet.pet.Settlement
import io.github.juwanhwang.autoqqpet.pet.StartedStory
import io.github.juwanhwang.autoqqpet.pet.StoryProgress
import io.github.juwanhwang.autoqqpet.pet.StoryStatus

/**
 * 回包解析（纯函数，不碰 bridge/Android，单元测试直接喂字节）。
 *
 * 两类写法，注释里分清：
 *  - **按字段号**：字段布局已真机实测（profile/数值/状态/目录/规则……）；
 *  - **按内容**：命令含义已定、但字段号没完整记下来的（结算明细、进度叙事、福袋、好友状态文案）——
 *    按“文本形如 X +N”“含‘雇佣’‘上限’”去认，宁可漏也不错认。
 */
object Parsers {

    private fun parse(b: ByteArray): Msg = if (b.isEmpty()) emptyMap() else ProtoBuf.parseMessage(b)

    /** 0x99f2：field1=profile{f1=名, f5=uin, f8=petId}。null=没开通/缺 petId。 */
    fun ownProfile(body: ByteArray): OwnPetProfile? {
        val p = ProtoBuf.sub(parse(body), 1)
        val petId = ProtoBuf.firstString(p, 8).trim()
        if (petId.isEmpty()) return null
        return OwnPetProfile(ProtoBuf.firstString(p, 5).trim(), petId, ProtoBuf.firstString(p, 1).trim())
    }

    /** 0x96f2：root.f1 → {f1..f5 各 {f3=float}}=心情/体力/清洁/综合/金币。 */
    fun displayRoot(body: ByteArray): Msg = ProtoBuf.sub(parse(body), 1)

    fun currentOf(root: Msg, field: Int): Double = ProtoBuf.firstFloat(ProtoBuf.sub(root, field), 3)

    fun values(common: Msg, gold: Msg?): PetValues = PetValues(
        feel = currentOf(common, 1), hunger = currentOf(common, 2), clean = currentOf(common, 3),
        total = currentOf(common, 4), gold = gold?.let { currentOf(it, 5) } ?: 0.0,
    )

    /** 0x9949：根 f4 重复 {f1=余量,f2=resourceId,f3=名,f4=foodId}。 */
    fun foods(body: ByteArray): List<FoodItem> = ProtoBuf.repeated(parse(body), 4).map {
        FoodItem(ProtoBuf.firstString(it, 4), ProtoBuf.firstString(it, 3), ProtoBuf.firstVarint(it, 1), ProtoBuf.firstVarint(it, 2))
    }

    /** 0x9bf1：根 f1 重复 {f1=名,f2=itemId,f5=价,f6=清洁,f12=心情}。 */
    fun bathItems(body: ByteArray): List<BathItem> = ProtoBuf.repeated(parse(body), 1).map {
        BathItem(
            ProtoBuf.firstString(it, 2), ProtoBuf.firstString(it, 1), ProtoBuf.firstVarint(it, 5),
            ProtoBuf.firstVarint(it, 6), ProtoBuf.firstVarint(it, 12),
        )
    }

    /** 0x9bf2：根 f1 {f1 重复 {f1=itemId,f2=数量}}。 */
    fun bathInventory(body: ByteArray): Map<String, Long> =
        ProtoBuf.repeated(ProtoBuf.sub(parse(body), 1), 1).associate { ProtoBuf.firstString(it, 1) to ProtoBuf.firstVarint(it, 2) }

    /** 0x975a：root.f2=storyId；root.f1={1:状态,2:剩余,3:总,4:开始,5:recallable}。 */
    fun storyStatus(body: ByteArray): StoryStatus {
        val root = parse(body)
        val d = ProtoBuf.sub(root, 1)
        return StoryStatus(
            storyId = ProtoBuf.firstString(root, 2),
            stateCode = ProtoBuf.firstVarint(d, 1),
            remainingSeconds = ProtoBuf.firstVarint(d, 2),
            durationSeconds = ProtoBuf.firstVarint(d, 3),
            startedAt = ProtoBuf.firstVarint(d, 4),
            recallable = ProtoBuf.firstVarint(d, 5) != 0L,
        )
    }

    /** 0x975e 回包：f1=storyId；f4={1:state,2:总秒,3:剩余秒,4:开始秒}（抓包实测 2026-10-14 19:50:11）。空回包 → 空 storyId。 */
    fun startedStory(body: ByteArray): StartedStory {
        if (body.isEmpty()) return StartedStory("")
        val root = parse(body)
        val t = ProtoBuf.sub(root, 4)
        return StartedStory(
            ProtoBuf.firstString(root, 1), ProtoBuf.firstVarint(t, 1), ProtoBuf.firstVarint(t, 2),
            ProtoBuf.firstVarint(t, 3), ProtoBuf.firstVarint(t, 4),
        )
    }

    /** 0x9ab2 目录：根 f1 重复 {f1 名,f6 消耗,f7 时长,f8 收获,f10/f14 描述,f15 “+N点”,f50 canDo,f51 原因,f52 subEvent}。 */
    fun sceneOptions(body: ByteArray): List<SceneOption> = ProtoBuf.repeated(parse(body), 1).map {
        SceneOption(
            name = ProtoBuf.firstString(it, 1),
            subEventType = ProtoBuf.firstVarint(it, 52),
            canDo = ProtoBuf.firstVarint(it, 50) != 0L,
            cost = ProtoBuf.firstString(it, 6),
            duration = ProtoBuf.firstString(it, 7),
            reward = ProtoBuf.firstString(it, 8),
            unavailableReason = ProtoBuf.firstString(it, 51),
            extra = ProtoBuf.firstString(it, 15),
            description = textOf(it, 14).ifBlank { textOf(it, 10) },
            wageRange = ProtoBuf.firstString(it, 12),
        )
    }

    /** 同字段号在岗位上放图标二进制、在冒险上放中文描述：乱码一律当空。 */
    private fun textOf(msg: Msg, field: Int): String {
        val raw = ProtoBuf.firstBytes(msg, field)
        if (raw.isEmpty()) return ""
        val s = String(raw, Charsets.UTF_8)
        val bad = s.count { it == '\uFFFD' || (it.code < 0x20 && it != '\n') || it.code in 0x80..0x7ff }
        return if (bad == 0) s.trim() else ""
    }

    /** 0x9b60(f1=6100) 学园阶段：resp f4。 */
    fun schoolStage(body: ByteArray): Long = ProtoBuf.firstVarint(parse(body), 4)

    /** 0x9b60(f1=6400) 职业：resp f1 重复 {f1 名,f4 状态,f5 文案,f20 type}。 */
    fun careers(body: ByteArray): List<CareerInfo> = ProtoBuf.repeated(parse(body), 1).map {
        CareerInfo(ProtoBuf.firstString(it, 1), ProtoBuf.firstVarint(it, 20), ProtoBuf.firstVarint(it, 4), ProtoBuf.firstString(it, 5))
    }

    /** 0x96a4：f1=trace(JSON 含 count)，f2*=event{f1=path{1:page,2:eventType,3:subEvent}}。 */
    fun pageRules(body: ByteArray): PageRules {
        val root = parse(body)
        val trace = ProtoBuf.firstString(root, 1)
        val count = Regex("\"count\"\\s*:\\s*(-?\\d+)").find(trace)?.groupValues?.get(1)?.toIntOrNull()
        val paths = ProtoBuf.repeated(root, 2).mapNotNull { ev ->
            val p = ProtoBuf.sub(ev, 1)
            if (p.isEmpty()) null else Triple(ProtoBuf.firstVarint(p, 1), ProtoBuf.firstVarint(p, 2), ProtoBuf.firstVarint(p, 3))
        }
        return PageRules(trace, count, paths)
    }

    /** 0x9ad4：只有**自身**响应 info#4 是战力（#3 主导属性）；好友响应字段含义未定 → 0 表示不知道。 */
    fun pkPower(petId: String, body: ByteArray): PKPower {
        val info = ProtoBuf.sub(parse(body), 1)
        val selfShape = ProtoBuf.firstBytes(info, 4).isNotEmpty() || ProtoBuf.firstVarint(info, 4) > 0
        return if (selfShape) PKPower(petId, ProtoBuf.firstVarint(info, 4), ProtoBuf.firstVarint(info, 3)) else PKPower(petId, 0, 0)
    }

    /** PK 好友池一页。 */
    data class PkPage(val items: List<PkItem>, val cursor: ProtoBuf.WireValue?, val hasMore: Boolean)
    data class PkItem(val uin: String, val petId: String, val powerF3: Long, val powerF4: Long, val nick: String, val bagId: String = "")

    /**
     * 0x985d：f1 重复记录。两种形态都认：
     *  - 旧（2026-09 实测 PK 池）：`{f1=profile{f1 宠物名,f8 petId}, f2=user{f1 uin varint,f2 昵称}, f14=power{f3,f4}}`；
     *  - 新（2026-10-15 底部好友栏）：`{f1={1 昵名, 5 uin 串, 8 petId, 11 宠物名}, f2={2 …}, f7 状态文案, f21 福袋标记}`。
     * f2=cursor，f3=hasMore。
     */
    fun pkPage(body: ByteArray): PkPage {
        val root = parse(body)
        val items = ProtoBuf.repeated(root, 1).mapNotNull {
            val profile = ProtoBuf.sub(it, 1)
            val user = ProtoBuf.sub(it, 2)
            val power = ProtoBuf.sub(it, 14)
            val petId = ProtoBuf.firstString(profile, 8)
            val oldUin = ProtoBuf.firstVarint(user, 1)
            val uin = if (oldUin > 0) oldUin.toString() else uinOf(profile, 5)
            if (petId.isEmpty() || uin.isEmpty() || uin == "0") null
            else PkItem(
                uin, petId, ProtoBuf.firstVarint(power, 3), ProtoBuf.firstVarint(power, 4),
                if (oldUin > 0) ProtoBuf.firstString(user, 2).ifBlank { ProtoBuf.firstString(profile, 1) } else ProtoBuf.firstString(profile, 1),
                bagId = bagIdOf(ProtoBuf.firstBytes(it, 21)),
            )
        }
        return PkPage(items, root[2]?.firstOrNull(), ProtoBuf.firstVarint(root, 3) > 0)
    }

    /** uin 有时是串（新形态 `.1.5`）有时是 varint：两种都认，只收纯数字。 */
    private fun uinOf(m: Msg, field: Int): String {
        val v = ProtoBuf.firstVarint(m, field)
        if (v > 0) return v.toString()
        return ProtoBuf.firstString(m, field).trim().takeIf { t -> t.isNotEmpty() && t.all { c -> c.isDigit() } }.orEmpty()
    }

    private val BAG_IN_TEXT = Regex("6[1-9]00-[0-9A-Za-z_\\-]{4,80}")

    /**
     * 福袋标记 `.21` → 袋 id。线上形如 `⏎16100-<hash>`：其实是子消息 `{1:"6100-<hash>"}`（0x0a=字段1、0x31=长度）；
     * 交接里“去掉换行与前导 1”是把它当文本看的等价读法。两种都认：先按子消息取 f1，失败再按文本剥 `\n` 与前导 `1`。
     */
    fun bagIdOf(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        runCatching { ProtoBuf.parseMessage(raw) }.getOrNull()?.let { m ->
            val f1 = ProtoBuf.firstString(m, 1).trim()
            if (BAG_ID.matches(f1)) return f1
        }
        var t = String(raw, Charsets.UTF_8).trim { it == '\n' || it == '\r' || it.isWhitespace() }
        if (t.startsWith("1") && BAG_ID.matches(t.substring(1))) t = t.substring(1)
        return if (BAG_ID.matches(t)) t else BAG_IN_TEXT.find(t)?.value.orEmpty()
    }

    /**
     * 0x9875 好友状态。字段 f1.1=每日被雇佣上限 是抓包对齐过的；阻断文案按内容收（f1.6 是“已达上限”，
     * 忙碌文案的位置没记下来），所以文案部分扫整棵树。
     */
    fun friendStatus(body: ByteArray): FriendStatus {
        val root = parse(body)
        val cap = ProtoBuf.firstVarint(ProtoBuf.sub(root, 1), 1).toInt()
        val notices = ProtoBuf.strings(body).map { it.second.trim() }
            .filter { s -> s.isNotEmpty() && s.length <= 60 && listOf("雇佣", "上限", "稍后", "正在").any { s.contains(it) } }
            .distinct()
        return FriendStatus(cap, notices)
    }

    private val REWARD_TEXT = Regex("^(.{1,10}?)\\s*[+＋xX×*]\\s*(\\d{1,7})$")

    /**
     * 奖励名清洗：真机统计里混进过「工分 +」「疲惫，收益减少」这类杂键——前者是带运算符的标签被整段当成名，
     * 后者是结算页的疲劳文案被“短文本 + 任意 varint + 图标”的形态②误捞。真奖励名（金币/学分/工分/魅力…）
     * 都不带标点、运算符与这些文案词；不合规的直接丢弃，别让它进今日实收统计。
     */
    fun rewardNameOk(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > 8) return false
        if (n.any { it in "，、+＋xX×*" }) return false
        if (listOf("收益", "疲惫", "雇佣", "上限").any { n.contains(it) }) return false
        return true
    }

    /** 已知奖励本体：带变体前缀的名字折回本体用。 */
    private val REWARD_BASES = setOf("金币", "工分", "力量", "智力", "魅力", "学分", "心情值", "体力值", "清洁值")
    private val REWARD_VARIANT_PREFIXES = listOf("基础", "奖励", "额外")

    /**
     * 奖励入口统一口径（2026-11-04 推广）：三个入口（结算文本形态①、拼凑形态②、故事结局结构化条目）
     * 一律只收名字折回后落在已知集合内、且单条数量在界内的条目。「看视频」是服务器实发的
     * 加成名（不在本体集合里，单独加进来）。单条数量上限 9999：真实单次发放到不了万，
     * 超过的都是 id 类大数字配着台词被误捞（2026-11-03 嘻嘻 +2050888、2026-11-04 谁还不是宝宝 +927873，
     * 后者正是从没设闸的形态①/结构化条目漏进来的）。
     */
    private val REWARD_KNOWN = REWARD_BASES + "看视频"
    private const val REWARD_MAX_QTY = 9_999L

    /**
     * 同物异名折叠（2026-10-19 用户裁定）：服务器同一种奖励会换着名字报——「基础魅力」「奖励魅力」
     * 实际上就是魅力。基础X/奖励X/额外X 折回本体 X（X 须是已知本体，不认识的不动），
     * 让台账、日志、收益统计从解析源头就只有一个名字。
     */
    fun foldRewardVariant(name: String): String {
        for (p in REWARD_VARIANT_PREFIXES) {
            if (name.startsWith(p)) {
                val base = name.substring(p.length)
                if (base in REWARD_BASES) return base
            }
        }
        return name
    }

    /**
     * 服务器奖励标签洗名（与 [settlement] 同规）：标签可能是「工分 +」「金币 +120」这种带运算符
     * 尾巴的整段文本，先剥掉尾部的运算符/数字后缀，再折叠同物异名（基础魅力→魅力），
     * 最后按折叠后的名字过 [rewardNameOk]；不合规（疲劳文案等伪条目）返回 null 丢弃。
     * 数量仍以条目自带字段为准，这里只洗名。
     */
    fun cleanRewardName(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val m = REWARD_TEXT.find(t)
        val name = (m?.groupValues?.get(1) ?: t).trim().trimEnd('+', '＋', 'x', 'X', '×', '*', ' ').trim()
        val folded = foldRewardVariant(name)
        return folded.takeIf { rewardNameOk(it) }
    }

    /**
     * 0x9874 结算展示页：奖励条目有“名称/数量/图标URL”三样（抓包实测 2026-10-14 19:33:12，如“工分 +3”；
     * 2026-10-31 抓包复核：条目是 f1=图标、f2=“名称 +数量”文本、f4=拆账明细）。
     * 两种形态都认：① 一条文本就是“名称 +数量”（服务器渲染的展示文本）；
     * ② 同一子消息里一个短名称 + 一个数量 varint + 图标 URL（拼凑推断）。
     * 两形态共用 [REWARD_KNOWN] 的统一口径（只认已知奖励名、单条数量 ≤9999）：
     * 2026-11-03 群友实证台词「嘻嘻」被形态②误捞成“嘻嘻 +2050888”后，闸先只加在形态②上；
     * 2026-11-04 又有台词「谁还不是宝宝 +927873」从没设闸的形态①漏进来，遂推广到全部入口。
     * 其余长文本当叙事。
     */
    fun settlement(storyId: String, body: ByteArray): Settlement {
        val rewards = LinkedHashMap<String, Long>()
        val narrative = ArrayList<String>()
        val strs = ProtoBuf.strings(body)
        for ((_, s) in strs) {
            val t = s.trim()
            if (t.isEmpty() || t.startsWith("http")) continue
            val m = REWARD_TEXT.find(t)
            if (m != null) {
                val name = foldRewardVariant(m.groupValues[1].trim())
                val qty = m.groupValues[2].toLong()
                if (name in REWARD_KNOWN && rewardNameOk(name) && qty in 1..REWARD_MAX_QTY && name !in rewards) rewards[name] = qty
            } else if (t.length >= 6) RichText.clean(t).takeIf { RichText.isNarrative(it) }?.let { narrative += it }
        }
        // 形态②：子消息 {短名称, 数量}
        fun walk(msg: Msg, depth: Int) {
            if (depth > 6) return
            val texts = msg.values.flatten().filter { it.wireType == ProtoBuf.WIRE_LEN }
                .map { it.value as ByteArray }.filter { ProtoBuf.looksText(it) }.map { String(it, Charsets.UTF_8).trim() }
            val name = texts.firstOrNull { it.length in 1..8 && !it.startsWith("http") && REWARD_TEXT.find(it) == null && !it.any { c -> c.isDigit() } }
            val qty = msg.values.flatten().firstOrNull { it.wireType == ProtoBuf.WIRE_VARINT && (it.value as Long) in 1..REWARD_MAX_QTY }?.value as? Long
            val folded = name?.let { foldRewardVariant(it) }
            if (folded != null && qty != null && folded in REWARD_KNOWN && rewardNameOk(folded) && folded !in rewards && texts.any { it.startsWith("http") }) rewards[folded] = qty
            for (v in msg.values.flatten()) {
                if (v.wireType != ProtoBuf.WIRE_LEN) continue
                val raw = v.value as ByteArray
                if (ProtoBuf.looksText(raw) && (raw[0].toInt() and 0xFF) !in setOf(0x08, 0x0a, 0x10, 0x12, 0x18, 0x1a)) continue
                runCatching { ProtoBuf.parseMessage(raw) }.getOrNull()?.let { walk(it, depth + 1) }
            }
        }
        runCatching { walk(parse(body), 0) }
        return Settlement(storyId, narrative.distinct(), rewards.map { Reward(it.key, it.value) }, storyEntries(body))
    }

    /**
     * 结算/召回回包（0x9760/0x9874）body.f1.9 条目表 → 逐条见闻。
     * 每条 `{1:出现时刻秒, 2:当时剩余秒, 3:JSON 富文本}`；JSON 抽各段 `text` 拼接后按 [RichText.clean] 洗成纯文本。
     * 同一叙事在回包里会出现两次（回放表与条目表各一份，2026-10-19 实测 6 段里有重复、实际约 4 条），
     * 按文本去重、只留第一次出现（带它自己的时刻/剩余秒）。
     */
    fun storyEntries(body: ByteArray): List<StoryEntry> {
        if (body.isEmpty()) return emptyList()
        val r = ProtoBuf.sub(parse(body), 1)
        return ProtoBuf.repeated(r, 9).mapNotNull { e ->
            val text = RichText.clean(ProtoBuf.firstString(e, 3))
            if (text.isEmpty()) null else StoryEntry(ProtoBuf.firstVarint(e, 1), ProtoBuf.firstVarint(e, 2), text)
        }.distinctBy { it.text }
    }

    /** 0x975f 进度轮询：收全部可读文本当叙事（字段号未定；昵称、流水都在里面）。 */
    fun storyProgress(storyId: String, body: ByteArray): StoryProgress =
        StoryProgress(storyId, RichText.narrativeLines(ProtoBuf.strings(body).map { it.second.trim() }.filter { it != storyId }))

    /**
     * 0x9d70/0x9d71 金币袋：f11=总额、f12=余额（抓包时 197/192，与“5个福袋共 197 金币，已领1个”对齐）。
     * 抓包记录写的是“回包里 f11/f12”，没注明在哪一层 → 先找根层，再找 f1 层。
     */
    fun coinBag(bagId: String, body: ByteArray): CoinBag {
        val root = parse(body)
        val layer = if (root.containsKey(11) || root.containsKey(12)) root else ProtoBuf.sub(root, 1)
        val fixed = ProtoBuf.firstString(ProtoBuf.sub(ProtoBuf.sub(root, 1), 3), 2)   // body.1.3.2「5个福袋共 330 金币，已领2个」
        val summary = fixed.ifBlank { ProtoBuf.strings(body).map { it.second }.firstOrNull { it.contains("福袋") || it.contains("金币") }.orEmpty() }
        return CoinBag(bagId, ProtoBuf.firstVarint(layer, 11), ProtoBuf.firstVarint(layer, 12), summary)
    }

    /** 袋 id 的样子：`<场景号>-<随机串>`（抓包 `6400-…`，前缀=来源场景）。 */
    private val BAG_ID = Regex("^6[1-9]00-[0-9A-Za-z_\\-]{6,64}$")

    /**
     * 从任意回包里收集形如袋 id 的串。用在 0x9acb_0（事件聚合，查自己的袋）：定案字段是 body `.1.2.1`，
     * 但事件流结构多变，先按形状全量扫、再由调用方按好友池排除。袋 id 是完整三段
     * （如 `6400-6ad24a17-00374a34ffae050aaad9c25831244ce207b`）——先前“自己的袋是短 hash”的说法
     * 是被截断显示骗了，不成立；这个正则只认「场景号-串」的形状，不按长度分谁的袋。
     */
    fun coinBagIds(body: ByteArray): List<String> =
        ProtoBuf.strings(body).map { it.second.trim() }.filter { BAG_ID.matches(it) }.distinct()

    /**
     * 0x9c11_0 信箱（交接 §9.6）：body.f1 = repeated 邮件
     * `{1:id, 3:标题, 4:正文, 5:发件人, 6:时间ms, 7:附件{1:活动id, 2:领取token, 4:[{1:类型,2:物品名,3:图标,4:数量}]}, 8:URL}`。
     * 有附件（f7 带 token 或物品）就算“有东西可领”。id/token 是账号数据，不往外带。
     */
    fun mails(body: ByteArray): List<Mail> = ProtoBuf.repeated(parse(body), 1).map { m ->
        val att = ProtoBuf.sub(m, 7)
        val items = ProtoBuf.repeated(att, 4).mapNotNull { it2 ->
            val name = ProtoBuf.firstString(it2, 2).trim()
            if (name.isEmpty()) null else Reward(name, ProtoBuf.firstVarint(it2, 4))
        }
        Mail(ProtoBuf.firstString(m, 3).trim(), items, att.isNotEmpty() && (ProtoBuf.firstString(att, 2).isNotEmpty() || items.isNotEmpty()))
    }

    /** 可领附件数（提醒用）。 */
    fun mailClaimable(body: ByteArray): Long = mails(body).count { it.hasAttachment }.toLong()

    // ———————————————— 2026-10-15 交接新增 ————————————————

    private val ATTR_PAIR = Regex("(力量|智力|魅力)\\s*(\\d+)\\s*/\\s*(\\d+)")
    private val ATTR_ONLY = Regex("(力量|智力|魅力)\\s*(\\d+)")

    /**
     * 0x9b62_1 职业树 → 三属性总量。数在**文案**里：`力量839/0 智力1607/0 魅力6468/0` 是自己的总量（分母 0 那组），
     * 后面逐职业的 `力量839/225` 是“当前/历史门槛”（交接 §9.5：门槛不当决策输入）。
     * 36KB 回包里的文本可能超过 [ProtoBuf.looksText] 的 4KB 上限 → 直接把整包按 UTF-8 解码后跑正则（中文与数字在同一字段里是连续字节）。
     * 三项缺一返回 null。
     */
    fun attributes(body: ByteArray): Attributes? {
        if (body.isEmpty()) return null
        val text = String(body, Charsets.UTF_8)
        val got = HashMap<String, Long>()
        for (m in ATTR_PAIR.findAll(text)) if (m.groupValues[3] == "0") got.putIfAbsent(m.groupValues[1], m.groupValues[2].toLong())
        if (got.size < 3) for (m in ATTR_PAIR.findAll(text)) got.putIfAbsent(m.groupValues[1], m.groupValues[2].toLong())
        if (got.size < 3) for (m in ATTR_ONLY.findAll(text)) got.putIfAbsent(m.groupValues[1], m.groupValues[2].toLong())
        val s = got["力量"] ?: return null
        val i = got["智力"] ?: return null
        val c = got["魅力"] ?: return null
        return Attributes(s, i, c)
    }

    private val TARGET_PAGE = Regex("target_page=(\\d+)")

    /** 0x9abe_0 每日经验：body f1=倍率(float) f2=今日经验 f3=上限 f4*=子任务（字段表见交接 §9.3）。 */
    fun dailyExp(body: ByteArray): DailyExp? {
        if (body.isEmpty()) return null
        val root = parse(body)
        if (!root.containsKey(2) && !root.containsKey(3)) return null
        val tasks = ProtoBuf.repeated(root, 4).map {
            ExpTask(
                key = ProtoBuf.firstString(it, 1), name = ProtoBuf.firstString(it, 2).trim(),
                done = ProtoBuf.firstVarint(it, 4), cap = ProtoBuf.firstVarint(it, 5),
                gotExp = ProtoBuf.firstVarint(it, 10), expCap = ProtoBuf.firstVarint(it, 11),
                targetPage = TARGET_PAGE.find(ProtoBuf.firstString(it, 6))?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                rule = ProtoBuf.firstString(it, 13).trim(),
            )
        }
        return DailyExp(ProtoBuf.firstVarint(root, 2), ProtoBuf.firstVarint(root, 3), ProtoBuf.firstFloat(root, 1), tasks)
    }

    private val GAP_DAYS = Regex("(\\d+)\\s*天没互踩")

    /**
     * 0x9b87 火花列表（交接 §9.9）：body.f1 = repeated 记录
     * `{1:{1 对方昵名, 5 uin, 8 petId, 11 宠物名}, 7 状态文案, 10:{1 阶段 1/2/3, 2 连续天数}, 16 文案}`。
     * 文案“小火苗升腾中”= 今日已互踩；“N天没互踩，今日互踩即可重燃”给出断了几天。
     */
    fun sparks(body: ByteArray): List<SparkEntry> = ProtoBuf.repeated(parse(body), 1).mapNotNull { r ->
        val p = ProtoBuf.sub(r, 1)
        val uin = uinOf(p, 5)
        if (uin.isEmpty()) return@mapNotNull null
        val st = ProtoBuf.sub(r, 10)
        val text = textsOf(ProtoBuf.firstBytes(r, 16)).joinToString("")
        SparkEntry(
            uin = uin, nick = ProtoBuf.firstString(p, 1).trim(), petId = ProtoBuf.firstString(p, 8).trim(),
            stage = SparkStage.of(ProtoBuf.firstVarint(st, 1)), days = ProtoBuf.firstVarint(st, 2).toInt(),
            gapDays = GAP_DAYS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
            pokedToday = text.contains("升腾"), text = text.trim(),
        )
    }

    /**
     * 0x9762 日记月列表：root.f1（容器）→ 重复「天」`{1:"YYYY-MM-DD", 2:重复{1:日记uuid, 3:字节, 7:petId}}`。
     * 抓包 2026-11-11。
     */
    fun diaryMonth(body: ByteArray): List<DiaryEntry> =
        ProtoBuf.repeated(ProtoBuf.sub(parse(body), 1), 1).flatMap { day ->
            val date = ProtoBuf.firstString(day, 1).trim()
            if (date.isEmpty()) emptyList() else ProtoBuf.repeated(day, 2).mapNotNull { item ->
                val uuid = ProtoBuf.firstString(item, 1).trim()
                if (uuid.isEmpty()) null else DiaryEntry(date, uuid)
            }
        }

    /** 0x9ba3 日记点赞回包：`.1`=日记的感谢文案；空回包=已经点过（服务器静默忽略）。 */
    fun diaryLikeText(body: ByteArray): String =
        if (body.isEmpty()) "" else ProtoBuf.firstString(parse(body), 1).trim()

    /**
     * 0x9bd5 背包（按族）：inner `.1` 重复 `{1:族id, 2:数量, 6:名称, 7:描述}`；
     * 模块自读拿到的是内层 body，抓包日志里的 `.4.x` 是信封位置，两种都兼容。抓包 2026-11-11。
     */
    fun itemInventory(body: ByteArray): List<ItemStack> {
        val root = parse(body)
        val inner = ProtoBuf.sub(root, 4)
        val d = if (inner.isNotEmpty()) inner else root
        return ProtoBuf.repeated(d, 1).mapNotNull {
            val fam = ProtoBuf.firstVarint(it, 1)
            if (fam <= 0) null else ItemStack(
                fam, ProtoBuf.firstVarint(it, 2), ProtoBuf.firstString(it, 6).trim(), ProtoBuf.firstString(it, 7).trim(),
            )
        }
    }

    /**
     * 0x9bd4 VIP/SVIP 礼包：{1=领取状态(1可领/2已领), 2=重复{2:奖励文案}, 3=SubActId}。
     * 模块自读拿到的是**内层 body**（OIDB 信封已被桥剥掉）；抓包日志里看到的 `.4.x` 是信封内的位置，两种都兼容。抓包 2026-11-10。
     */
    fun vipGift(body: ByteArray): VipGift {
        val root = parse(body)
        val inner = ProtoBuf.sub(root, 4)
        val d = if (inner.isNotEmpty()) inner else root
        val items = ProtoBuf.repeated(d, 2).mapNotNull { ProtoBuf.firstString(it, 2).trim().ifEmpty { null } }
        return VipGift(ProtoBuf.firstVarint(d, 1).toInt(), ProtoBuf.firstString(d, 3).trim(), items)
    }

    /**
     * 摸好友回包 0x96a6：{2: {1:次数, 2:心情增量(float)}}（内层）；抓包信封里是 `.4.2`。读不到=0。
     */
    fun patMoodDelta(body: ByteArray): Double {
        if (body.isEmpty()) return 0.0
        val root = parse(body)
        val inner = ProtoBuf.sub(root, 4)
        val d = if (inner.isNotEmpty()) inner else root
        return ProtoBuf.firstFloat(ProtoBuf.sub(d, 2), 2)
    }

    private val SS_KEY = Regex("^ss:(\\d+):(\\d+):(\\d+)$")

    /**
     * 0x985e 被踩明细。**权威结构以 meanings.tsv 0x985e_0 条为准**（2026-10-15 01:28 完整抓包 16672 字节、
     * 13 条记录与界面逐项对账）：顶层 f4 是汇总消息 `{.1=repeated 记录, .3=总获得"312", .4=今日新增"1", .5=分页JSON}`，
     * 记录内层 `{1:{2 访客昵名, 6:{4 "ss:<自己uin>:<对方uin>:<ms>"}, 7:{6 累计次数, 7 状态文案}}}`。
     * 旧实现把记录与今日数都读在根上（根 f1 / 根 f4）——回包真回来也一条都解不出，这是回踩长期为 0 的解析侧主因。
     * 现按嵌套形态读，根形态仅作兜底（旧单测样本）；“今日”= 列表前 todayN 条（列表按时间倒序），
     * 今日数两处都读不到时按 0（宁可不回踩也不错踩）。
     */
    fun likeVisitors(body: ByteArray, selfUin: String, todayNOverride: Int = -1, indexOffset: Int = 0): List<LikeVisitor> {
        val root = parse(body)
        val wrap = ProtoBuf.sub(root, 4)
        val todayN = if (todayNOverride >= 0) todayNOverride else likeTodayCount(body)
        val records = ProtoBuf.repeated(wrap, 1).ifEmpty { ProtoBuf.repeated(root, 1) }
        return records.mapIndexedNotNull { idx, r ->
            val rec: Msg = ProtoBuf.sub(r, 1).ifEmpty { r }
            val raw = ProtoBuf.firstBytes(r, 1)
            val keys = (textsOf(raw) + textsOf(ProtoBuf.firstBytes(rec, 6))).mapNotNull { SS_KEY.find(it.trim()) }
            val uin = keys.firstNotNullOfOrNull { k -> listOf(k.groupValues[1], k.groupValues[2]).firstOrNull { it != selfUin } }
                ?: ProtoBuf.firstVarint(rec, 1).takeIf { it > 0 }?.toString()
                ?: return@mapIndexedNotNull null
            val more = ProtoBuf.sub(rec, 7)
            LikeVisitor(uin, ProtoBuf.firstString(rec, 2).trim(), ProtoBuf.firstVarint(more, 6).toInt(), indexOffset + idx < todayN)
        }.distinctBy { it.uin }
    }

    /** 0x985e 回包里的“今日新增”条数（汇总消息 .4.4，串或数；根 f4 兜底）。读不到按 0。 */
    fun likeTodayCount(body: ByteArray): Int {
        val root = parse(body)
        val wrap = ProtoBuf.sub(root, 4)
        return ProtoBuf.firstVarint(wrap, 4).takeIf { it > 0 }?.toInt()
            ?: ProtoBuf.firstString(wrap, 4).trim().toIntOrNull()
            ?: ProtoBuf.firstVarint(root, 4).takeIf { it > 0 }?.toInt()
            ?: ProtoBuf.firstString(root, 4).trim().toIntOrNull() ?: 0
    }

    /** 0x985e 回包的下一页游标（汇总消息 .4.5 的分页 JSON，如 `{"start":20,"limit":20}`）；没有返回空串。 */
    fun likeNextCursor(body: ByteArray): String {
        val wrap = ProtoBuf.sub(parse(body), 4)
        return ProtoBuf.firstString(wrap, 5).trim().takeIf { it.startsWith("{") }.orEmpty()
    }

    /**
     * 0x994a 通知列表：每条 `{1:对方uin, 2:富文本[“01:45 雇佣了”,“<昵名>”,“，快去看看TA吧！”], 3:时间戳ms, 5:昵名, 6:场景 6400}`。
     * 只挑“雇佣了”那类（交接 §9.10）。字段位置没完整定，文本按内容认、时刻取 f3。
     */
    fun hiredNotices(body: ByteArray): List<HiredNotice> = ProtoBuf.repeated(parse(body), 1).mapNotNull { r ->
        val texts = textsOfMsg(r)
        if (texts.none { it.contains("雇佣了") }) return@mapNotNull null
        val nick = ProtoBuf.firstString(r, 5).trim().ifEmpty { texts.firstOrNull { !it.contains("雇佣") && !it.contains("看看") && it.length in 1..24 }.orEmpty() }
        HiredNotice(nick, ProtoBuf.firstVarint(r, 3), ProtoBuf.firstVarint(r, 6))
    }

    /** 0x9c6d 签到回包：f1 = 操作后的现有库存；**空回包 = 今天已领**（返回 null）。 */
    fun checkinStock(body: ByteArray): Long? = if (body.isEmpty()) null else ProtoBuf.firstVarint(parse(body), 1)

    /** 0x9bf3 洗澡回包 f1 = 洗后清洁（写包回包首数 = 服务器新值，交接 §9.7④）；空 → null。 */
    fun bathClean(body: ByteArray): Long? = if (body.isEmpty()) null else ProtoBuf.firstVarint(parse(body), 1).takeIf { it > 0 }

    /** 0x9760/0x9874 回包的故事结局（字段表见 [StoryOutcome]）。 */
    fun storyOutcome(body: ByteArray): StoryOutcome {
        if (body.isEmpty()) return StoryOutcome()
        val r = ProtoBuf.sub(parse(body), 1)
        val rawRewards = ProtoBuf.repeated(ProtoBuf.sub(r, 3), 1).mapNotNull {
            // 名称字段是服务器的显示标签（「工分 +」这类带运算符的整段），必须洗名再收，
            // 否则日志渲染成「工分 ++6」、统计里还多一本杂账（与 settlement 同规，2026-10-19）。
            // 2026-11-04 推广统一口径：洗名后还须是已知奖励、数量在界内——结构化条目同样会
            // 混进台词配大数字（「谁还不是宝宝 +927873」），「字段分开」不等于可信。
            // 数量留负数余地（体力值等净消耗条目可能为负），只拦绝对值超界与 0。
            val n = cleanRewardName(ProtoBuf.firstString(it, 2)) ?: return@mapNotNull null
            val qty = ProtoBuf.firstVarint(it, 3)
            if (foldRewardVariant(n) !in REWARD_KNOWN || qty == 0L || qty > REWARD_MAX_QTY || qty < -REWARD_MAX_QTY) return@mapNotNull null
            Reward(n, qty)
        }
        // 洗名折叠后同名的条目（魅力/基础魅力/奖励魅力）在这里并成一条、数值相加，源头只出一个名字
        val mergedRewards = LinkedHashMap<String, Long>()
        rawRewards.forEach { mergedRewards[it.name] = (mergedRewards[it.name] ?: 0L) + it.amount }
        val rewards = mergedRewards.map { Reward(it.key, it.value) }
        return StoryOutcome(
            name = ProtoBuf.firstString(r, 7).trim().ifEmpty { ProtoBuf.firstString(ProtoBuf.sub(r, 16), 1).trim() },
            notice = ProtoBuf.firstString(r, 12).trim(),
            title = ProtoBuf.firstString(ProtoBuf.sub(r, 18), 8).trim(),
            rewards = rewards,
            recallGold = ProtoBuf.firstVarint(ProtoBuf.sub(r, 16), 7),
            entries = storyEntries(body),
        )
    }

    /** 一段字节里的全部可读文本（是文本就自己，是消息就递归）。 */
    fun textsOf(raw: ByteArray): List<String> {
        if (raw.isEmpty()) return emptyList()
        val nested = ProtoBuf.strings(raw).map { it.second }
        if (nested.isNotEmpty()) return nested
        return if (ProtoBuf.looksText(raw)) listOf(String(raw, Charsets.UTF_8)) else emptyList()
    }

    private fun textsOfMsg(m: Msg): List<String> = m.values.flatten().filter { it.wireType == ProtoBuf.WIRE_LEN }
        .flatMap { v -> val b = v.value as ByteArray; if (ProtoBuf.looksText(b)) listOf(String(b, Charsets.UTF_8)) + ProtoBuf.strings(b).map { it.second } else textsOf(b) }

    /** 写命令通用回包：{f1=result, f3=msg}。 */
    fun describe(body: ByteArray): String {
        if (body.isEmpty()) return "(空回包)"
        val root = parse(body)
        return "result=${ProtoBuf.firstVarint(root, 1)} msg='${ProtoBuf.firstString(root, 3)}' bytes=${body.size}"
    }
    // ———————————————— 出门记录 / 单条详情（2026-10-16）————————————————

    private val STORY_ID = Regex("^\\d{4}_[0-9A-Za-z-]{6,}$")
    /** 毫秒时间戳的合理区间（2020-09 ~ 2040）：记录里认时间戳只认这个范围的 varint。 */
    private val MS_RANGE = 1_600_000_000_000L..2_200_000_000_000L
    private val SEC_RANGE = 1_600_000_000L..2_200_000_000L

    /**
     * 0x9876_1 出门记录：body.f1 = repeated `{1:storyId, 3:标题, 4:结果, 5:叙事, 8:2}`。
     * 抓包记的是 `.1.1` 形式，可能多包一层 → item.f1 不像 storyId 就再往 item.f1 里找一层。
     * 时间戳字段号没记 → 在条目直接字段里找第一个落在毫秒区间（或秒区间×1000）的 varint，认不出为 0。
     */
    fun outingHistory(body: ByteArray): io.github.juwanhwang.autoqqpet.pet.OutingPage {
        if (body.isEmpty()) return io.github.juwanhwang.autoqqpet.pet.OutingPage(emptyList())
        val recs = ProtoBuf.repeated(parse(body), 1).mapNotNull { r0 ->
            val r = if (STORY_ID.matches(ProtoBuf.firstString(r0, 1).trim())) r0 else ProtoBuf.sub(r0, 1)
            val sid = ProtoBuf.firstString(r, 1).trim()
            if (!STORY_ID.matches(sid)) return@mapNotNull null
            io.github.juwanhwang.autoqqpet.pet.OutingRecord(
                storyId = sid,
                title = RichText.title(ProtoBuf.firstString(r, 3)),
                result = ProtoBuf.firstVarint(r, 4).toInt(),
                narrative = RichText.clean(ProtoBuf.firstString(r, 5)),
                atMs = timestampMs(r, skip = setOf(1, 3, 4, 5, 8)),
            )
        }.distinctBy { it.storyId }
        return io.github.juwanhwang.autoqqpet.pet.OutingPage(recs)
    }

    private fun timestampMs(r: Msg, skip: Set<Int>): Long {
        for ((f, vs) in r.entries.sortedBy { it.key }) {
            if (f in skip) continue
            for (v in vs) {
                if (v.wireType != ProtoBuf.WIRE_VARINT) continue
                val n = v.value as Long
                if (n in MS_RANGE) return n
                if (n in SEC_RANGE) return n * 1000
            }
        }
        return 0L
    }

    /**
     * 0x9874_1 单条详情（body.f1）：.7 标题 / .8 结果（缺省取 .15.4）/ .10 开始秒 / .11 场景 / .13 课程 id / .15.12 课程分钟 /
     * .9 叙事流水 {2:第几秒, 3:文案} / .12 文案（PK 每日上限）/ .3.1 奖励条目 {2:名称, 3:数值, 7:同值}。
     */
    fun storyDetail(storyId: String, body: ByteArray): io.github.juwanhwang.autoqqpet.pet.StoryDetail {
        if (body.isEmpty()) return io.github.juwanhwang.autoqqpet.pet.StoryDetail(storyId)
        val r = ProtoBuf.sub(parse(body), 1)
        val f15 = ProtoBuf.sub(r, 15)
        val rewards = ProtoBuf.repeated(ProtoBuf.sub(r, 3), 1).mapNotNull {
            val n = RichText.title(ProtoBuf.firstString(it, 2))
            val v = ProtoBuf.firstVarint(it, 3).takeIf { x -> x != 0L } ?: ProtoBuf.firstVarint(it, 7)
            if (n.isEmpty()) null else io.github.juwanhwang.autoqqpet.pet.Reward(n, v)
        }
        val beats = ProtoBuf.repeated(r, 9).mapNotNull {
            val t = RichText.clean(ProtoBuf.firstString(it, 3))
            if (!RichText.isNarrative(t)) null else io.github.juwanhwang.autoqqpet.pet.StoryBeat(ProtoBuf.firstVarint(it, 2), t)
        }
        val start = ProtoBuf.firstVarint(r, 10).let { if (it in MS_RANGE) it / 1000 else it }
        return io.github.juwanhwang.autoqqpet.pet.StoryDetail(
            storyId = storyId,
            title = RichText.title(ProtoBuf.firstString(r, 7)).ifEmpty { RichText.title(ProtoBuf.firstString(ProtoBuf.sub(r, 16), 1)) },
            result = (ProtoBuf.firstVarint(r, 8).takeIf { it > 0 } ?: ProtoBuf.firstVarint(f15, 4)).toInt(),
            scene = ProtoBuf.firstVarint(r, 11),
            startSec = start.takeIf { it in SEC_RANGE } ?: 0L,
            courseId = ProtoBuf.firstVarint(r, 13),
            courseMinutes = ProtoBuf.firstVarint(f15, 12),
            notice = RichText.clean(ProtoBuf.firstString(r, 12)),
            beats = beats,
            rewards = rewards,
        )
    }

}
