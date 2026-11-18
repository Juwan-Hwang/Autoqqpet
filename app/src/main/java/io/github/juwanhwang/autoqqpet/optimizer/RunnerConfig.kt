package io.github.juwanhwang.autoqqpet.optimizer

import android.content.SharedPreferences
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.store.SettingKeys as K

/**
 * 循环的全部可调参数。**只有 [fromPrefs] 一处读设置**（v1 在 ApplicationHook.rebuildRunner 与 runPlan 各读一份，口径会漂）。
 * 改设置 → 重建 runner（[io.github.juwanhwang.autoqqpet.hook.HostRuntime.rebuildRunner]），排班 JSON 例外：每轮现读。
 */
data class RunnerConfig(
    val targetAttr: Attr = Attr.STRENGTH,
    /** 目标属性选了「均衡」：学习在力量/智力/魅力之间轮转（见 [StudyRotation]）；[targetAttr] 此时只是占位。 */
    val balancedStudy: Boolean = false,
    val goldReserve: Int = 300,
    /** 属性优先今日可亏金币（设置 attr_loss_budget）：期末金币底线放宽用，见 [OptimalPlanner]。 */
    val attrLossBudget: Int = 0,
    val staminaFloor: Int = 30,
    val cleanFloor: Int = 30,
    /** 饱食不足马上喂（#23，默认关）：开时照顾检查的喂食目标是喂满（100），不是地板。 */
    val feedFullOn: Boolean = false,
    val maxStartRetry: Int = 3,
    /** 到期后多等一会再结算（学工）；秒数来自设置「结算等待」（settle_buffer_s，默认 5），毫秒在 fromPrefs 换算。 */
    val settleBufferMs: Long = 5_000,
    /** 冒险专用缓冲：固定 3 秒，不随「结算等待」走，45 秒的短趟等不起。 */
    val adventureSettleBufferMs: Long = 3_000,
    val adventureMaxPerDay: Int = 0,
    val recallEnabled: Boolean = true,
    val recallMode: String = "best_split",
    /** 不是本模块开的打工（被雇、用户亲手开的）也按召回时机处理，不等通知确认（默认关）。 */
    val recallExternalOn: Boolean = false,
    val autoHireFriend: Boolean = false,
    val hireMaxPerDay: Int = -1,
    val autoBuySupplies: Boolean = true,
    val goldPerAttr: Double = 40.0,
    val adventureOnly: Boolean = false,
    /** 只冒险模式固定刷的冒险名（空=自动）；见 SettingKeys.ADV_ONLY_NAME。 */
    val adventureOnlyName: String = "",
    /** 天色不对自动召回（模块派出的冒险都查，含排班；手动/外部故事不经派出路径，不查）。 */
    val weatherRecallOn: Boolean = true,
    /** 最优调度（adv_mode=auto）：自动挡走数学求解器，目标见 [autoGoal]。 */
    val solverMode: Boolean = false,
    val autoGoal: AutoGoal = AutoGoal.ATTR,
    /** 求解器算账用的补给单价（设置可改，游戏改价不用等发版）。 */
    val biscuitPrice: Int = 5,
    val soapPrice: Int = 2,
    /** 属性优先的停课线（设置 study_brake 原文 none/full/no10，求解输入时映射成最低收益系数）。 */
    val studyBrake: String = "no10",
    val workFirst: Boolean = false,
    /** 打工方向（职业 type）；0=自动。时间档位不由用户定，每轮按实时读数选。 */
    val workCareerType: Long = 0,
    /** “疑似被雇佣→提醒手动召回”（默认关：协议分不出被雇佣与用户手动开的打工）。 */
    val hireReminder: Boolean = false,
    val scheduleJson: String = "",
    val scheduleMode: Boolean = false,
    val pkOn: Boolean = true,
    val pkMaxPerDay: Int = 10,
    val settleViewOn: Boolean = true,
    val learnRewardsOn: Boolean = true,
    val progressOn: Boolean = true,
    val mailCheckOn: Boolean = false,
    val checkinFoodOn: Boolean = true,
    val mailClaimOn: Boolean = true,
    val settleClaimOn: Boolean = true,
    val attrReadOn: Boolean = true,
    val expReadOn: Boolean = true,
    val pkMarginPct: Int = 90,
    val sparkOn: Boolean = true,
    val sparkMaxPerDay: Int = -1,
    val sparkPerRound: Int = 6,
    val sparkRemindOn: Boolean = true,
    val sparkReturnOn: Boolean = false,
    val sparkTonightOn: Boolean = true,
    val sparkTonightAtMin: Int = 21 * 60,
    val hiredPollOn: Boolean = true,
    val feedPushOn: Boolean = false,
    val coinBagOn: Boolean = false,
    val coinBagMaxPerDay: Int = -1,
    val coinBagEveryMin: Int = 5,
    /** 每日白捡：自动领 SVIP 礼包（0x9bd4 读状态 + WebSSO 0x9377 领取）。 */
    val svipGiftOn: Boolean = false,
    /** 每日白捡：给最近几篇日记点赞（0x9762 列表 + 0x9ba3 点赞）。 */
    val diaryLikeOn: Boolean = false,
    /** 每日白捡：每日免费学习道具（0x9e9c）。 */
    val dailyItemOn: Boolean = false,
    /** 每日白捡：看广告领「每日广告免费福利」（rewardType 61）。 */
    val adGiftOn: Boolean = false,
    /** 结算页看广告加金币（rewardType 60），目前只对打工结算尝试。 */
    val adCoinOn: Boolean = false,
    /** 广告奖励后台领取：领取时不打开播放页。 */
    val adBackgroundClaimOn: Boolean = false,
    /** 开工后自动用任务加成道具（0x9bd5 查背包 + 0x9d9e 使用）：打工族 357。 */
    val workItemOn: Boolean = false,
    /** 开工后自动用任务加成道具：学习族 356（每趟限 1 杯）。 */
    val studyItemOn: Boolean = false,
    /** 火花类：自动摸好友（0x96a6，好友心情 +2），名单口径与火花一致。 */
    val patOn: Boolean = false,
    /** 只摸有火花的好友。 */
    val patSparkOnly: Boolean = false,
    val friendCareOn: Boolean = false,
    val friendCareMaxPerDay: Int = 10,
    val friendCareFloor: Int = 60,
    val friendOneClickOn: Boolean = false,
    val friendOneClickMaxPerDay: Int = 3,
    val dailyReportOn: Boolean = true,
    /** 两次短冒险之间的最小间隔（秒）。 */
    val taskGapSec: Int = 10,
    /** 每次学习/打工自动鼓励几次（0x9c44）；默认 1 与旧行为一致，0=不鼓励。 */
    val encourageTimes: Int = 1,
    /** 息屏时暂停（#11，默认关）：屏幕熄灭期间 runner 不发任何包。 */
    val screenPauseOn: Boolean = false,
    /** 夜间暂停（#11，默认关）：在自定时段内 runner 不发任何包。 */
    val nightPauseOn: Boolean = false,
    /** 夜间暂停时段起止（当天第几分钟，本机时间）：默认 01:30–06:30；开始 > 结束表示跨零点。 */
    val nightPauseStartMin: Int = PauseGate.DEFAULT_START_MIN,
    val nightPauseEndMin: Int = PauseGate.DEFAULT_END_MIN,
) {
    /** 任务模式原文（mix/work/only/custom）。 */
    val mode: String get() = when { adventureOnly -> "only"; scheduleMode -> "custom"; solverMode -> "auto"; workFirst -> "work"; else -> "mix" }

    companion object {
        fun attrOf(name: String): Attr = when (name) { "智力" -> Attr.INTEL; "魅力" -> Attr.CHARM; else -> Attr.STRENGTH }

        fun fromPrefs(sp: SharedPreferences): RunnerConfig {
            val mode = K.string(sp, K.ADV_MODE).let { if (it == "off") "mix" else it }
            val attrName = K.string(sp, K.ATTR)
            return RunnerConfig(
                targetAttr = attrOf(attrName),
                balancedStudy = attrName == "均衡",
                goldReserve = K.int(sp, K.GOLD_RESERVE),
                attrLossBudget = K.int(sp, K.ATTR_LOSS_BUDGET),
                staminaFloor = K.int(sp, K.STAMINA_FLOOR),
                cleanFloor = K.int(sp, K.CLEAN_FLOOR),
                feedFullOn = K.bool(sp, K.FEED_FULL_ON),
                adventureMaxPerDay = K.int(sp, K.ADV_MAX),
                recallEnabled = K.bool(sp, K.RECALL_ON),
                recallMode = K.string(sp, K.RECALL_MODE),
                recallExternalOn = K.bool(sp, K.RECALL_EXTERNAL_ON),
                autoHireFriend = K.bool(sp, K.HIRE_ON),
                hireMaxPerDay = K.int(sp, K.HIRE_MAX),
                autoBuySupplies = K.bool(sp, K.AUTO_BUY),
                adventureOnly = mode == "only",
                adventureOnlyName = K.string(sp, K.ADV_ONLY_NAME),
                weatherRecallOn = K.bool(sp, K.ADV_WEATHER_RECALL_ON),
                solverMode = mode == "auto",
                autoGoal = when (K.string(sp, K.AUTO_GOAL)) { "gold" -> AutoGoal.GOLD; "points" -> AutoGoal.POINTS; else -> AutoGoal.ATTR },
                biscuitPrice = K.int(sp, K.BISCUIT_PRICE),
                soapPrice = K.int(sp, K.SOAP_PRICE),
                studyBrake = K.string(sp, K.STUDY_BRAKE),
                workFirst = mode == "work",
                workCareerType = K.long(sp, K.WORK_CAREER),
                hireReminder = K.bool(sp, K.HIRE_ALERT_ON),
                scheduleJson = K.string(sp, K.SCHEDULE_JSON),
                scheduleMode = mode == "custom",
                pkOn = K.bool(sp, K.PK_ON),
                pkMaxPerDay = K.int(sp, K.PK_MAX),
                settleViewOn = K.bool(sp, K.SETTLE_VIEW_ON),
                learnRewardsOn = K.bool(sp, K.LEARN_REWARDS_ON),
                progressOn = K.bool(sp, K.PROGRESS_ON),
                mailCheckOn = K.bool(sp, K.MAIL_CHECK_ON),
                checkinFoodOn = K.bool(sp, K.CHECKIN_FOOD_ON),
                coinBagOn = K.bool(sp, K.COINBAG_ON),
                coinBagMaxPerDay = K.int(sp, K.COINBAG_MAX),
                coinBagEveryMin = K.int(sp, K.COINBAG_EVERY_MIN),
                svipGiftOn = K.bool(sp, K.SVIP_GIFT_ON),
                diaryLikeOn = K.bool(sp, K.DIARY_LIKE_ON),
                dailyItemOn = K.bool(sp, K.DAILY_ITEM_ON),
                adGiftOn = K.bool(sp, K.AD_GIFT_ON),
                adCoinOn = K.bool(sp, K.AD_COIN_ON),
                adBackgroundClaimOn = K.bool(sp, K.AD_BACKGROUND_CLAIM_ON),
                workItemOn = K.bool(sp, K.WORK_ITEM_ON),
                studyItemOn = K.bool(sp, K.STUDY_ITEM_ON),
                patOn = K.bool(sp, K.PAT_ON),
                patSparkOnly = K.bool(sp, K.PAT_SPARK_ONLY),
                friendCareOn = K.bool(sp, K.FRIEND_CARE_ON),
                friendCareMaxPerDay = K.int(sp, K.FRIEND_CARE_MAX),
                friendCareFloor = K.int(sp, K.FRIEND_CARE_FLOOR),
                friendOneClickOn = K.bool(sp, K.FRIEND_ONECLICK_ON),
                friendOneClickMaxPerDay = K.int(sp, K.FRIEND_ONECLICK_MAX),
                dailyReportOn = K.bool(sp, K.DAILY_REPORT_ON),
                mailClaimOn = K.bool(sp, K.MAIL_CLAIM_ON),
                settleClaimOn = K.bool(sp, K.SETTLE_CLAIM_ON),
                attrReadOn = K.bool(sp, K.ATTR_READ_ON),
                expReadOn = K.bool(sp, K.EXP_READ_ON),
                pkMarginPct = K.int(sp, K.PK_MARGIN),
                sparkOn = K.bool(sp, K.SPARK_ON),
                sparkMaxPerDay = K.int(sp, K.SPARK_MAX),
                sparkPerRound = K.int(sp, K.SPARK_PER_ROUND),
                sparkRemindOn = K.bool(sp, K.SPARK_REMIND_ON),
                sparkReturnOn = K.bool(sp, K.SPARK_RETURN_ON),
                sparkTonightOn = K.bool(sp, K.SPARK_TONIGHT_ON),
                sparkTonightAtMin = K.int(sp, K.SPARK_TONIGHT_AT),
                hiredPollOn = K.bool(sp, K.HIRED_POLL_ON),
                feedPushOn = K.bool(sp, K.FEED_PUSH_ON),
                taskGapSec = K.int(sp, K.SHORT_ADV_GAP_S),
                settleBufferMs = K.int(sp, K.SETTLE_BUFFER_S) * 1000L,
                encourageTimes = K.int(sp, K.ENCOURAGE_TIMES),
                screenPauseOn = K.bool(sp, K.SCREEN_PAUSE_ON),
                nightPauseOn = K.bool(sp, K.NIGHT_PAUSE_ON),
                nightPauseStartMin = K.int(sp, K.NIGHT_PAUSE_START),
                nightPauseEndMin = K.int(sp, K.NIGHT_PAUSE_END),
            )
        }
    }

    /** 一行人读摘要（启动日志/诊断）。 */
    fun summary(): String =
        "目标=${if (balancedStudy) "均衡" else targetAttr.toString()} 模式=$mode 打工方向=${if (workCareerType > 0) "type=$workCareerType" else "自动"} 预留=$goldReserve 地板=$staminaFloor/$cleanFloor " +
            "召回=${if (recallEnabled) recallMode else "关"}${if (recallExternalOn) "+外部" else ""} 雇佣=${if (!autoHireFriend) "关" else if (hireMaxPerDay < 0) "开·不限" else "开≤$hireMaxPerDay 次/天"} PK=${if (pkOn) "≤$pkMaxPerDay/天" else "关"} " +
            "结算明细=$settleViewOn 实收校准=$learnRewardsOn 信箱=${if (mailClaimOn) "领" else if (mailCheckOn) "提醒" else "关"} 签到=$checkinFoodOn 福袋=$coinBagOn SVIP礼包=$svipGiftOn 日记点赞=$diaryLikeOn 每日道具=$dailyItemOn 广告福利=$adGiftOn 结算广告=$adCoinOn 打工道具=$workItemOn 学习道具=$studyItemOn 代照顾=$friendCareOn 一键照顾=$friendOneClickOn " +
            "火花=${if (sparkOn) "全部好友 ${sparkPerRound}人/轮" + (if (sparkMaxPerDay > 0) " ≤$sparkMaxPerDay/天" else "") else "关"} 摸好友=${if (patOn) (if (patSparkOnly) "只摸火花好友" else "全部好友") else "关"} 回踩=$sparkReturnOn PK余量=$pkMarginPct% 查被雇=$hiredPollOn 鼓励=${encourageTimes}次/趟"
}
