package io.github.juwanhwang.autoqqpet.net


/**
 * OIDB 命令三元组 (oidbName, command, subCommand)。
 * [subKnown]=false 表示命令号与含义已定性、但**子命令号没抓到过**（只有静态证据）：
 * 这类命令由 [io.github.juwanhwang.autoqqpet.pet.service.PetSession.sendFlexible] 按候选 sub 试，
 * 服务器回 150（no privilege，sub 错时的固定码）就换下一个，试通的记住。
 */
data class Oidb(val name: String, val command: Int, val subCommand: Int, val subKnown: Boolean = true) {
    /** 换一个 sub 的同一条命令（名字后缀跟着变：`0x9c11_0` → `0x9c11_1`）。 */
    fun withSub(sub: Int): Oidb = copy(name = name.substringBeforeLast('_') + "_" + sub, subCommand = sub, subKnown = true)
}

/**
 * **命令号单一真源**。所有发包都从这里取三元组，别处不得再出现 `0x9xxx_n` 字面量。
 *
 * 证据等级照抄 `tools/pet_recon/meanings.tsv`（实测 / 抓包实测 / 静态），每条注释写清依据；
 * 字段布局写在 `pet/service/` 对应方法上（那里才是协议细节）。
 */
object Commands {
    /**
     * 唯一的命令三元组构造口：命令名是明文常量（`OidbSvcTrpcTcp.0x9c19_0`），
     * 命令号与子命令号从名字现算（cmd=0x9c19, sub=0）。名字仍按 5 参原样发给 QQ 宿主。
     */
    private fun op(name: String, subKnown: Boolean = true): Oidb {
        val tail = name.substringAfterLast('.')               // "0x9c19_0"
        val cmd = tail.substringBefore('_').removePrefix("0x").toInt(16)
        val sub = tail.substringAfterLast('_').toInt()
        return Oidb(name, cmd, sub, subKnown)
    }

    // ———————————————— 读取（可安全重试）————————————————
    /** 本人宠物资料（空 body）。实测 2026-09-24。 */
    val OWN_PROFILE = op("OidbSvcTrpcTcp.0x99f2_1")
    /** 数值/金币；**f1 传好友 petId 即读好友三属性**（抓包实测 2026-10-14 20:49:32）。 */
    val DISPLAY = op("OidbSvcTrpcTcp.0x96f2_1")
    /** 食物库存/目录。 */
    val FEED_TIMES = op("OidbSvcTrpcTcp.0x9949_1")
    val BATH_ITEM_CONFIG = op("OidbSvcTrpcTcp.0x9bf1_1")
    val BATH_INVENTORY = op("OidbSvcTrpcTcp.0x9bf2_1")
    /** 页面规则（服务端当日剩余次数 + 可执行路径）。实测 2026-10-06。 */
    val PAGE_RULES = op("OidbSvcTrpcTcp.0x96a4_1")
    /** 户外任务状态（剩余/总时长/可召回）。必须 f100=2。 */
    val STORY_STATUS = op("OidbSvcTrpcTcp.0x975a_1")
    /** 进行中故事的通用进度轮询（打工中客户端每 7s 一次；PK 只是复用）。抓包实测 2026-10-14 19:16。 */
    val STORY_PROGRESS = op("OidbSvcTrpcTcp.0x975f_1")
    /** 到期结算展示页（含奖励明细）。抓包实测 2026-10-14 19:33:12。 */
    val SETTLE_VIEW = op("OidbSvcTrpcTcp.0x9874_1")
    /** 好友宠物数值（旧路，f1=好友 UIN）。新代码用 [DISPLAY] 直读好友 petId，本条只给诊断兜底。 */
    val FRIEND_PROFILE = op("OidbSvcTrpcTcp.0x976c_0")
    /** 好友宠物状态 + **能不能雇佣的真值**（= Sso_PetOutdoor_GetOtherPetStatusInfo）。抓包实测 2026-10-14 20:49:24。 */
    val FRIEND_STATUS = op("OidbSvcTrpcTcp.0x9875_1")
    val PK_POWER = op("OidbSvcTrpcTcp.0x9ad4_1")
    val PK_FRIEND_LIST = op("OidbSvcTrpcTcp.0x985d_0")
    /** 课程/岗位/冒险目录（按 f1 区分场景；冒险目录 f100=3）。 */
    val SCENE_CATALOG = op("OidbSvcTrpcTcp.0x9ab2_1")
    /** 学园阶段/职业总览。 */
    val OUTDOOR_OVERVIEW = op("OidbSvcTrpcTcp.0x9b60_1")
    /** 金币袋列表/前置查询（福袋）。抓包实测 2026-10-14 20:08:34，sub=0。 */
    val COIN_BAG_LIST = op("OidbSvcTrpcTcp.0x9d71_0")
    /**
     * 事件消息聚合：`{1:自己petId, 2:msgTypes 打包字节}`，sub=0。抓包定案 2026-10-17 02:10–02:23
     * （recon 19970ca）：**自己的福袋 id 在响应 body `.1.2.1`**——袋未领时每次查询都报、领完即无，
     * 可当“当前有没有自己的袋”的权威查询。请求带全部 11 个 msgTypes 才是拿到袋消息的那一形态。
     * （曾误以为 0x9955_1 是自己福袋查询：有袋时它响应 body 照样空、与福袋无关，已否证，勿再用。）
     */
    val HOME_EVENTS = op("OidbSvcTrpcTcp.0x9acb_0")
    /**
     * 信箱：邮件列表 + 可领附件（PetMailBoxRepository.getClaimableCount）。**sub=0**（抓包实测 2026-10-15 00:56：
     * `_0` 回 7409 字节，`_1` 回 150 no privilege）。请求 `{1:petId}`。
     */
    val MAIL_LIST = op("OidbSvcTrpcTcp.0x9c11_0")
    /** 职业树/属性总览：回包**文案**里带 `力量839/0 智力1607/0 魅力6468/0`；f1 传好友 petId 即读好友。抓包实测 2026-10-14 23:15/23:23、10-15 00:59。 */
    val JOB_TREE = op("OidbSvcTrpcTcp.0x9b62_1")
    /** 每日经验/任务总览 `{1:petId, 2:1}`：body f1=倍率 float、f2=今日经验、f3=上限、f4=子任务。抓包实测 2026-10-15 00:27/00:31/00:34。 */
    val DAILY_EXP = op("OidbSvcTrpcTcp.0x9abe_0")
    /** 火花列表 fetchSparkList：每条 `.1.5 uin / .1.1 昵名 / .10.1 阶段 / .10.2 天数`。抓包实测 2026-10-15 01:28；**sub 与请求体没抓到**（只看到回包）→ 按 sub 0/1 试。 */
    val SPARK_LIST = op("OidbSvcTrpcTcp.0x9b87_0", subKnown = false)
    /**
     * 被踩明细 fetchLikeList（来踩过我的人，**不一定是好友**）：`.3` 总数、`.4` 今日新增。抓包实测 2026-10-15 01:28。
     * **sub=0**：QQ 自己的请求三次独立抓包全是 _0；`_1` 是 10-14 落地时记错的（9f3637e），954b0a5（10-15）已改回 _0。
     * 2026-10-16 凌晨真机连拒 24 次 150 的那台构建应仍是 _1 那代（同进程连拒成串，与 _0 代“回一次即进程内停用”的行为对不上）。
     * 请求体同样以抓包归档为准：首页空 body、翻页带 `{1: 分页JSON}`（详见 FriendApi.likeVisitors），旧猜测 `{1:petId, 100:2}` 已弃。
     * （命令名已按此实测定为 _0，勿回滚到 _1。）
     */
    val LIKE_LIST = op("OidbSvcTrpcTcp.0x985e_0")
    /**
     * 《出门记录》历史流水（学习/打工/冒险/PK）。2026-10-16 抓包：请求 `{1:游标 ms(翻页才带), 2:每页 20, 3:自己 petId, 100:2}`；
     * 每条 `{1:storyId, 3:标题, 4:结果枚举, 5:叙事, 8:恒 2}`。用于：外部任务识别、写命令未确认时的核对、今日计数对账。
     */
    val OUTING_HISTORY = op("OidbSvcTrpcTcp.0x9876_1")
    /**
     * VIP/SVIP 可领礼包列表（空 body）。抓包 2026-11-10 21:23：回包 `.4.1`=领取状态（1=可领、2=已领）、
     * `.4.3`=SubActId（领取时交给 WebSSO 0x9377 的 data.SubActId）、`.4.2`=奖励条目（图标 + “点券*5”/“饼干*20”）。
     */
    val VIP_RECEIVE = op("OidbSvcTrpcTcp.0x9bd4_0")
    /**
     * 行为上报 ReportAction：自摸 `{1:"101", 2:"P1002", 3:"P1002", 4:时间戳ms, 5:token}`，**回包为空是常态**，
     * 服务端在这条上报上结算金币（每日一次，+6~+7）。抓包 2026-11-10 22:38 纯净对照。
     */
    val REPORT_ACTION = op("OidbSvcTrpcTcp.0x9bb1_0")
    /** 日记按月分页列表：`{1:petId, 3:"YYYY-MM"}`；回包 `.1.1` 按天 `{1:日期, 2:重复{1:日记uuid}}`。抓包 2026-11-11。 */
    val DIARY_MONTH = op("OidbSvcTrpcTcp.0x9762_0")
    /** 日记点赞：`{1:petId, 2:日记uuid}`，回包 `.1`=感谢文案；已点过的回空包（静默忽略）。抓包 2026-11-11。 */
    val DIARY_LIKE = op("OidbSvcTrpcTcp.0x9ba3_0")
    /**
     * 任务道具使用：`{1:petId, 2:storyId, 3:族id, 4:{1:族id,2:场景,3:1}, 5:trace}`，场景 1=打工、2=学习。
     * 回包带效果（如「金币 +500」/「力量 +1」）与剩余数量；学习每趟限 1 杯（135109）。抓包 2026-11-11。
     */
    val ITEM_USE = op("OidbSvcTrpcTcp.0x9d9e_0")
    /** 按道具族 id 查背包：`{1:族id, 2:petId}`；回包 `.1` 重复 `{1:族id, 2:数量, 6:名称, 7:描述}`。抓包 2026-11-11。 */
    val ITEM_INVENTORY = op("OidbSvcTrpcTcp.0x9bd5_1")
    /** 每日免费学习道具福利列表/领取：`{1:petId}`；调用即领（幂等，已领回状态 2）。抓包 2026-11-11。 */
    val DAILY_ITEM = op("OidbSvcTrpcTcp.0x9e9c_1")
    /** 消息/通知中心列表：“01:45 雇佣了 <昵名>，快去看看TA吧！”就在这里（不是推送，要拉）。抓包实测 2026-10-15 01:46:35；请求体几乎空。 */
    val NOTICE_LIST = op("OidbSvcTrpcTcp.0x994a_1")
    // ———————————————— 写入（one-shot，空响应禁止重发）————————————————
    /** 喂食（自身 f4=petId；好友向多带 f1=uin/f2=昵称/f4=好友petId）。 */
    val FEED = op("OidbSvcTrpcTcp.0x992d_1")
    val BUY_FOOD = op("OidbSvcTrpcTcp.0x99df_1")
    /** 洗澡（好友向 {1:好友petId,2:道具,3:数量,4:好友uin}，抓包实测 2026-10-14 20:49:35）。 */
    val DO_BATH = op("OidbSvcTrpcTcp.0x9bf3_1")
    /** 商城下单（买洗护品；一键照顾也会自动走它）。成功码是 1 不是 0。 */
    val BUY_BATH_ITEM = op("OidbSvcTrpcTcp.0x9bd0_0")
    val REPORT_EVENT = op("OidbSvcTrpcTcp.0x96a6_1")
    val FRIEND_POKE = op("OidbSvcTrpcTcp.0x985b_0")
    /** 开课/开工/冒险/PK 统一入口；带 f4 即雇佣（雇人打工不收费；250 是 PK 请保镖的费用，不走这里）。 */
    val STORY_START = op("OidbSvcTrpcTcp.0x975e_1")
    /** 结算：action 1000=到期领奖；action 6000=召回/提前结束/PK 结算（抓包实测 2026-10-14 19:17:51 / 19:50:56 / 20:43:28）。 */
    val STORY_SETTLE = op("OidbSvcTrpcTcp.0x9760_1")
    /** 鼓励（仅学工；冒险报 135011）。 */
    val STORY_ENCOURAGE = op("OidbSvcTrpcTcp.0x9c44_1")
    /** 一键照顾执行（对好友也有效，会自动花金币下单 0x9bd0）。抓包实测 2026-10-14 20:50:00。 */
    val ONE_CLICK_CARE = op("OidbSvcTrpcTcp.0x9b80_1")
    /** 开/领好友金币袋（福袋）。抓包实测 2026-10-14 20:08:34，sub=0。 */
    val COIN_BAG_OPEN = op("OidbSvcTrpcTcp.0x9d70_0")
    /** 取消雇佣好友代打。抓包实测 2026-10-14 20:42:47。 */
    val HIRE_CANCEL = op("OidbSvcTrpcTcp.0x9bb3_0")
    /**
     * 每日签到领食物 `{1:petId, 100:2}`，**sub=0**（`_1` 回 150）。抓包实测 2026-10-15 01:05 一发即成功：
     * 回包 f1=**操作后的现有库存**（不是发放量）；同一天再发回**空响应**（= 今天已领，不是错误）。
     */
    val CHECKIN_FOOD = op("OidbSvcTrpcTcp.0x9c6d_0")
    /** 信箱一键全部领取 batchClaim `{1:petId, 100:2}`，sub=0；无可领时回 136610（权威“没东西可领”码）。抓包实测 2026-10-15 01:05:32。 */
    val MAIL_BATCH_CLAIM = op("OidbSvcTrpcTcp.0x9c19_0")
    /** 代喂后的“我喂了你宠物”推送 Sso_PetFeed_PushFeedMsg `{1:好友uin串, 2:零宽昵称, 4:1, 5:0, 6:"1"}`。静态坐实 + 三个真机样本（回包全空）。 */
    val FEED_PUSH = op("OidbSvcTrpcTcp.0x99ef_1")

    /** sub 未知的命令按这个顺序试。信箱/签到两次踩坑后定的规矩：**从 0 开始试**（2026-10-15 交接 §9.7）。 */
    val FLEX_SUBS = listOf(0, 1)
    /** sub 错时服务器回的固定码（150 no privilege，真机 2026-10-09 枚举召回时观察到）。 */
    const val CODE_WRONG_SUB = 150
}

/** 场景号/页面号/版本号等协议常量（同样只在这里出现一次）。 */
object Proto {
    const val SCENE_SCHOOL = 6100L
    const val SCENE_WORK = 6400L
    const val SCENE_HIRED = 6500L
    const val SCENE_ADVENTURE = 6700L
    const val SCENE_PK = 6900L
    const val PK_SUB_EVENT = 6901L

    /** 户外行为页（学工/冒险共用）与好友家页。 */
    const val PAGE_OUTDOOR = 6000L
    const val PAGE_FRIEND_HOME = 1000L

    /** 户外协议版本：绝大多数命令 2；只有冒险目录（0x9ab2, f1=6700）用 3（抓包实锤）。 */
    const val OUTDOOR_V = 2L
    const val ADVENTURE_CATALOG_V = 3L

    const val SETTLE_ACTION_CLAIM = 1000L
    const val SETTLE_ACTION_RECALL = 6000L
    /** 召回包（0x9760 action=6000）的 f4：必须是 3。2026-10-19 实测：发 0 时冒险召回被服务器以 135004 拒绝，官方页面召回发的就是 3，冒险与打工同一包形。 */
    const val SETTLE_RECALL_F4 = 3L

    const val BISCUIT_FOOD_ID = "1"
    const val SOAP_ITEM_ID = "1"
    /** 商城货币类型（金币）与洗护品类。 */
    const val MALL_CURRENCY = 1001L
    const val MALL_CATEGORY_BATH = 355L

    /** 服务器“未达到该职业参与要求”业务码：门禁信息，不算硬失败。 */
    val CAREER_GATE_CODES = setOf(14561, 135061)
    /** 冒险不支持鼓励。 */
    const val CODE_NO_ENCOURAGE = 135011
    /** 宠物结算条件不满足（召回不可用时）。 */
    const val CODE_SETTLE_NOT_READY = 135004
    /** 请求 petID 不匹配：服务端换了 petId、本地缓存还是旧的（2.0.1 用户日志实锤：故事类写命令从某刻起持续被此码拒绝、读命令正常）。写路径靠刷新 petId 后重试一次自愈（见 PetSession）。 */
    const val CODE_PET_ID_MISMATCH = 135075
    /** 信箱一键领取“暂时还没有可领取的奖励呢”（= 无事可做，不是错误）。 */
    const val CODE_MAIL_NOTHING = 136610
    /** 踩踩“今天已经踩过”一类的重复码（真机 2026-09-24 起一直是它）。 */
    const val CODE_POKE_REPEAT = 136202
    /** 召回时机：官方分成表 71~100% 档拿 100% 基础工资 + 75% 增益分成（自然到期只 10%）。 */
    const val RECALL_AT_PROGRESS = 0.71
    /** 零宽占位昵称（客户端在 0x992d/0x99ef 里传的就是它：U+2062）。 */
    const val ZW_NICK = "\u2062"
}
