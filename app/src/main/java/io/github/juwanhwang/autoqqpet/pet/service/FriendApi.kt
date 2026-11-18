package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.net.pb
import io.github.juwanhwang.autoqqpet.pet.CoinBag
import io.github.juwanhwang.autoqqpet.pet.FriendStatus
import io.github.juwanhwang.autoqqpet.pet.HiredNotice
import io.github.juwanhwang.autoqqpet.pet.LikeVisitor
import io.github.juwanhwang.autoqqpet.pet.SparkEntry
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers

/** 好友族：好友池、踩踩、访问、好友状态(能否雇佣)、一键照顾、福袋、取消雇佣。 */
class FriendApi(private val s: PetSession) {

    /** PK 好友池一项（也是雇佣/互动候选）。 */
    data class Friend(val uin: String, val petId: String, val powerF3: Long, val powerF4: Long, val nick: String = "", val bagId: String = "") {
        /** 取非零的那个；两个都有值时信 f4（与自身 PK_POWER 一致）。 */
        val power: Long get() = if (powerF4 > 0) powerF4 else powerF3
        val displayName: String get() = nick.ifBlank { uin }
    }

    /**
     * 好友池 PK_FRIEND_LIST(0x985d_0)：f2=source(6)，翻页带 f1=cursor（原样回传 varint/bytes）。
     * 服务器一页只发 10 人：跟 cursor 翻到 hasMore=0（最多 [maxPages] 页，挡空页与 cursor 不前进）。
     */
    fun pool(source: Int = 6, maxPages: Int = 20): List<Friend> {
        val out = LinkedHashMap<String, Friend>()
        var cursor: ProtoBuf.WireValue? = null
        var page = 0
        while (page++ < maxPages) {
            val c = cursor
            val body = pb {
                num(2, source)
                when {
                    c == null -> Unit
                    c.wireType == ProtoBuf.WIRE_VARINT -> num(1, c.value as Long)
                    else -> bytes(1, c.value as ByteArray)
                }
            }
            val p = runCatching { Parsers.pkPage(s.read(Commands.PK_FRIEND_LIST, body)) }.getOrNull() ?: break
            val before = out.size
            p.items.forEach { out.putIfAbsent(it.uin, Friend(it.uin, it.petId, it.powerF3, it.powerF4, it.nick, it.bagId)) }
            if (p.cursor == null || !p.hasMore || p.cursor == cursor || out.size == before) break
            cursor = p.cursor
        }
        return out.values.toList()
    }

    /** 踩踩 FRIEND_POKE(0x985b_0)：f1=好友uin(varint)。 */
    fun poke(friendUin: String): String = Parsers.describe(s.write(Commands.FRIEND_POKE) { ProtoBuf.fieldVarint(1, friendUin.toLong()) })

    /** 访问好友家 = REPORT_EVENT：f1=好友petId,f2=好友uin,f3=path{1000,100,0}。 */
    fun visit(friendUin: String, friendPetId: String): String = Parsers.describe(s.write(Commands.REPORT_EVENT) { pb {
        str(1, friendPetId); str(2, friendUin)
        msg(3) { num(1, Proto.PAGE_FRIEND_HOME); num(2, 100); num(3, 0) }
        bytes(4, ByteArray(0))
    } })

    /**
     * 摸好友 = REPORT_EVENT(0x96a6_1)：f1=好友petId,f2=好友uin,f3=path{1000,300,301}。
     * 抓包实测 2026-11-10 23:09（AIRequestModule 通道）：回包 `.4.2={1:次数, 2:好友心情增量}`（实测 +2.0）。
     * f4/f90 是页面侧的埋点元数据（路径序列化 + 来源 ID），原样照抄抓包，时间戳用当前毫秒。
     * @return 好友心情增量（读不到回包=0.0；写失败由调用方 PetResult 包装）
     */
    fun pat(friendUin: String, friendPetId: String, nowMs: Long = System.currentTimeMillis()): Double =
        Parsers.patMoodDelta(s.write(Commands.REPORT_EVENT) { pb {
            str(1, friendPetId); str(2, friendUin)
            msg(3) { num(1, 1000); num(2, 300); num(3, 301) }
            msg(4) {
                num(6, 1); bytes(9, PAT_META); num(10, nowMs); num(11, 1000)
                num(12, 0); num(13, 0); num(20, 0); num(21, 0)
            }
            bytes(5, ByteArray(0))
            msg(90) { num(1, 0); bytes(2, PAT_META); num(4, 1); msg(6) { str(1, "ID"); str(2, "22686") } }
            num(99, 0)
        } })

    /** 摸好友请求里两处重复的路径元数据（抓包原文，恒定值）。 */
    private val PAT_META = byteArrayOf(
        0x12, 0x12, 0x0a, 0x05, 0x08, 0x01, 0x10, 0x90.toByte(), 0x4e, 0x12, 0x07,
        0x34, 0x30, 0x30, 0x30, 0x31, 0x32, 0x30, 0x30, 0x02,
    )

    /**
     * 好友宠物状态 FRIEND_STATUS(0x9875_1)：`{1:好友petId, 2:好友uin, 3:自己petId, 4:1, 100:2}`
     * （抓包实测 2026-10-14 20:49:24）。**能不能雇**直接看它（每日上限/已达上限/在忙文案）。
     */
    fun status(friendUin: String, friendPetId: String): FriendStatus = Parsers.friendStatus(s.read(Commands.FRIEND_STATUS, pb {
        str(1, friendPetId); str(2, friendUin); str(3, s.ensurePetId()); num(4, 1); num(100, Proto.OUTDOOR_V)
    }))

    /** 原始回包（福袋 id 发现用）。 */
    fun statusRaw(friendUin: String, friendPetId: String): ByteArray = s.read(Commands.FRIEND_STATUS, pb {
        str(1, friendPetId); str(2, friendUin); str(3, s.ensurePetId()); num(4, 1); num(100, Proto.OUTDOOR_V)
    })

    /**
     * 一键照顾执行 ONE_CLICK_CARE(0x9b80_1)：抓包实测 2026-10-14 20:50:00（对好友）：`{1:好友petId, 2:2, 5:4, 6:3}`，回包 {1:1}=成功。
     * f2/f5/f6 是客户端算好的“预期饼干/洗护/经验”小整数（模块日志 expectBiscuitCost/expectSoapCost/expectExpGain），
     * 我们**原样重放抓到的那组值**；服务器会按需自动下单（0x9bd0）花金币。
     */
    fun oneClickCare(targetPetId: String, f2: Long = 2, f5: Long = 4, f6: Long = 3): Boolean {
        val r = s.write(Commands.ONE_CLICK_CARE) { pb { str(1, targetPetId); num(2, f2); num(5, f5); num(6, f6) } }
        return r.isNotEmpty() && ProtoBuf.firstVarint(ProtoBuf.parseMessage(r), 1) == 1L
    }

    /** 金币袋列表/前置 COIN_BAG_LIST(0x9d71_0)：`{1:自己petId, 3:袋id}`（不带好友 petId；抓包实测 2026-10-14 20:08:34）。 */
    fun coinBagInfo(bagId: String): CoinBag = Parsers.coinBag(bagId, s.read(Commands.COIN_BAG_LIST, pb { str(1, s.ensurePetId()); str(3, bagId) }))

    /**
     * 自己名下的福袋 id HOME_EVENTS(0x9acb_0)：`{1:自己petId, 2:11 个 msgTypes 打包}`，
     * 袋 id 在回包 body `.1.2.1`（抓包定案 2026-10-17 02:10–02:23，未领会重复报、领完即无）。
     * 回包是事件聚合，好友的袋也会出现在里面——调用方要按好友池排除，不是扫到的都算自己的。
     */
    fun ownCoinBagIds(): List<String> = Parsers.coinBagIds(s.read(Commands.HOME_EVENTS, pb {
        str(1, s.ensurePetId())
        bytes(2, byteArrayOf(0x01, 0x03, 0x04, 0x05, 0x08, 0x09, 0x0b, 0x0f, 0x0a, 0x0e, 0x0d))
    }))

    /**
     * 开/领金币袋 COIN_BAG_OPEN(0x9d70_0)：`{1:自己petId, 2:袋主petId, 3:袋id, 11:2}`。
     * 领自己的袋与领好友的袋是同一条命令，区别只在 f2 传谁（抓包实测 2026-10-17 01:04：拆自己袋时 f2=自己petId）。
     */
    fun openCoinBag(ownerPetId: String, bagId: String): CoinBag = Parsers.coinBag(bagId, s.write(Commands.COIN_BAG_OPEN) { pb {
        str(1, s.ensurePetId()); str(2, ownerPetId); str(3, bagId); num(11, 2)
    } })

    /** 取消雇佣 HIRE_CANCEL(0x9bb3_0)：`{1:自己petId, 2:好友petId}`（抓包实测 2026-10-14 20:42:47）。 */
    fun cancelHire(friendPetId: String): String = Parsers.describe(s.write(Commands.HIRE_CANCEL) { pb { str(1, s.ensurePetId()); str(2, friendPetId) } })

    /**
     * 火花列表 SPARK_LIST(0x9b87)：**请求体与 sub 都没抓到**（只有回包）→ 按清扫惯例 `{1:petId, 100:2}`、sub 0/1 试。实验。
     */
    fun sparks(): List<SparkEntry> =
        Parsers.sparks(s.sendFlexible(Commands.SPARK_LIST, { pb { str(1, s.ensurePetId()); num(100, Proto.OUTDOOR_V) } }, write = false))

    /**
     * 被踩明细 LIKE_LIST(0x985e_0)：列的是“来踩过我的人”，可能是陌生人。
     * 请求体以抓包归档（recon-raw，2026-10-15 01:26/01:28/01:32 三次）为准：**首页就是空 body**，
     * 翻页才带 `{1: 回包 .4.5 的分页 JSON}`（实测原文 `{"start":20,"limit":20}`）。旧版猜的 `{1:petId, 100:2}` 已弃。
     * 今日访客超过一页（20 人）时按全局序号续读，最多 3 页；服务器拒绝（150 等）直接抛给调用方记日志——
     * 旧版“回一次 150 就在进程内永久停用”的做法已废，那会让回踩无声无息地死一整天、日志里还看不出死因。
     */
    fun likeVisitors(): List<LikeVisitor> {
        val self = s.selfUin()
        val first = s.read(Commands.LIKE_LIST, ByteArray(0))
        val todayN = Parsers.likeTodayCount(first)
        val out = Parsers.likeVisitors(first, self).toMutableList()
        var cursor = Parsers.likeNextCursor(first)
        var pages = 1
        while (cursor.isNotEmpty() && out.count { it.today } < todayN && pages < 3) {
            val next = runCatching { s.read(Commands.LIKE_LIST, pb { str(1, cursor) }) }.getOrNull() ?: break
            out += Parsers.likeVisitors(next, self, todayNOverride = todayN, indexOffset = out.size)
            cursor = Parsers.likeNextCursor(next)
            pages++
        }
        return out.distinctBy { it.uin }
    }

    /** 通知中心 NOTICE_LIST(0x994a_1)：请求体几乎空。只挑“雇佣了”。 */
    fun hiredNotices(): List<HiredNotice> = Parsers.hiredNotices(s.read(Commands.NOTICE_LIST, ByteArray(0)))
}
