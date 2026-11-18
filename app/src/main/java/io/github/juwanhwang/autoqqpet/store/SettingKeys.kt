package io.github.juwanhwang.autoqqpet.store

import android.content.SharedPreferences

/**
 * 设置键的**单一登记表**（名称/类型/默认值/含义）。docs/CONTRACT.md §1 与这里逐条对应。
 *
 * 存储与流向（三处同名同型，前端只需要认这一张表）：
 *  1. 模块 App 进程 `autoqqpet_ui`（ModulePrefs.NAME）：界面写；`AutoqqpetApplication` 监听后合并进主存；
 *  2. 主存 `autoqqpet_store/settings_json`（模块自有目录）：QQ 进程启动/每 5 分钟拉一次；
 *  3. QQ 进程 `autoqqpet_settings`：QQ 侧唯一真正生效的那份，由 `setopt` 等广播或主存回灌写入。
 *
 * 新增键只要登记在这里：同步通道（[Store.SETTINGS_KEYS]）、类型强制（JSON 不分 Int/Long 的坑）、
 * `setopt` 校验、诊断打印都自动覆盖。
 */
object SettingKeys {

    enum class Type { BOOL, INT, LONG, STRING }

    data class Def(
        val key: String,
        val type: Type,
        val default: Any,
        /** 界面可直接用的短标题（中文）。 */
        val title: String,
        /** 含义与取值说明（写进 CONTRACT）。 */
        val desc: String,
        /** 数值的合法范围（仅 INT/LONG）。 */
        val range: LongRange? = null,
        /** 合法取值（仅 STRING 枚举）。 */
        val choices: List<String> = emptyList(),
        /** 能力是否基于未完全抓到字段的推断（界面应标“实验”）。 */
        val experimental: Boolean = false,
    )

    private fun b(k: String, d: Boolean, t: String, desc: String, exp: Boolean = false) = Def(k, Type.BOOL, d, t, desc, experimental = exp)
    private fun i(k: String, d: Int, t: String, desc: String, r: LongRange) = Def(k, Type.INT, d, t, desc, range = r)

    // —— 基础（v1 已有，键名与默认值不变）——
    val AUTO_ON = b("auto_on", false, "自动托管", "自动托管总开关，从主页开关。")
    val ATTR = Def("attr", Type.STRING, "力量", "目标属性", "属性优先时要练的属性，最优调度和旧学习优先都用它；选「均衡」时学习在力量、智力、魅力之间轮着来", choices = listOf("力量", "智力", "魅力", "均衡"))
    val ADV_MODE = Def(
        "adv_mode", Type.STRING, "auto", "怎么干活",
        "auto=最优调度，按自动目标算今天的最优安排，每趟重算；mix=学习优先，旧逻辑，疲惫后转冒险；work=打工优先，旧逻辑，疲惫后转冒险；only=只重复冒险；custom=自定义排班",
        choices = listOf("auto", "mix", "work", "only", "custom"),
    )
    val AUTO_GOAL = Def(
        "auto_goal", Type.STRING, "attr", "自动目标",
        "最优调度的目标：gold=金币优先，先派冒险把当天金币池跑到见底（连续 30 趟没金币才算见底），再去打工；points=工分优先，按所处疲劳档折算后收入盖不住补给耗费的工不排，收入未知的工不拦；attr=属性优先。后两挡默认要求最终金币不能比早上少，不够就先安排赚钱；属性优先填了「今日可亏金币」后，底线按填的数放宽",
        choices = listOf("gold", "points", "attr"),
    )
    val STUDY_BRAKE = Def(
        "study_brake", Type.STRING, "no10", "学习停课线",
        "最优调度属性优先时，学费不打折、收益按疲劳打折：none=不设线，打折也上；full=只在全额时段上课；no10=学工累计满 12 小时进一折时段就停课，是默认",
        choices = listOf("none", "full", "no10"),
    )
    val BISCUIT_PRICE = i("biscuit_price", 5, "饼干单价", "最优调度算账用的饼干单价；游戏改价了就改这里", 0L..1_000_000L)
    val SOAP_PRICE = i("soap_price", 2, "香皂片单价", "最优调度算账用的香皂片单价；游戏改价了就改这里", 0L..1_000_000L)
    val ADV_MAX = i("adv_max", 0, "每日冒险上限", "0=不限，正数是每天最多冒险次数", 0L..1_000_000L)
    val ADV_ONLY_NAME = Def(
        "adv_only_name", Type.STRING, "", "只冒险刷哪个",
        "只冒险模式固定刷哪一个冒险，按名字存，与排班里选冒险是同一份目录，比如「附近走走」45 秒、「诗和远方」2 小时。空着就是自动，每轮选当时最划算的一条；选的那个一时做不了就回退自动。主页选了「只冒险」时可直接切换",
    )
    val ADV_WEATHER_RECALL_ON = b("adv_weather_recall_on", true, "天色不对自动召回", "冒险刚出发就发现天色不对时，立刻召回重新出发")
    val WORK_CAREER = Def("work_career", Type.LONG, 0L, "打工方向", "打工的职业方向，1 到 8，0=自动；时长档位每轮按实时读数选", range = 0L..99L)
    val HIRE_ALERT_ON = b("hire_alert_on", false, "被雇佣提醒", "确认被好友雇走且进度到 71% 时提醒；开了「增强识别」后，亲手开的打工也会提醒。自动召回开着时不提醒")
    val RECALL_ON = b("recall_on", true, "被雇走时自动召回", "确认是被好友雇走、不是自己开的打工，且进度到 71% 时自动召回")
    val RECALL_MODE = Def("recall_mode", Type.STRING, "best_split", "召回时机", "best_split=进度到 71% 再召回；immediate=一发现立刻召回", choices = listOf("best_split", "immediate"))
    val RECALL_EXTERNAL_ON = b("recall_external_on", false, "增强识别", "被雇佣时模块偶尔认不出来、就不会召回。打开后模块不再核对通知：只要宠物在打工、这趟工又不是模块开的，就按上面选的时机召回。注意：你自己在 QQ 里亲手开的工也会被召回")
    val HIRE_ON = b("hire_on", false, "开工雇佣好友", "开工时雇佣一个好友，优先挑加成最高的，不能雇就换下一个")
    val SCHEDULE_JSON = Def("schedule_json", Type.STRING, "", "自定义排班", "整份排班的数据，干活方式选排班时生效")

    // —— v2 新增 ——
    val GOLD_RESERVE = i("gold_reserve", 300, "金币预留", "交学费后金币仍要至少剩这个数", 0L..100_000L)
    val ATTR_LOSS_BUDGET = i("attr_loss_budget", 0, "今日可亏金币", "只对最优调度的属性优先有用：今天为了上课最多可以净花掉多少金币，期末金币底线改成早上金币减这个数，但不会低于金币预留；0=一分不亏，是默认", 0L..100_000L)
    val STAMINA_FLOOR = i("stamina_floor", 30, "体力地板", "低于此值先喂食", 0L..100L)
    val CLEAN_FLOOR = i("clean_floor", 30, "清洁地板", "低于此值先洗澡", 0L..100L)
    val FEED_FULL_ON = b("feed_full_on", false, "饱食不足马上喂", "照顾检查时只要饱食没满就喂到满，不等低于体力地板；食物不够或喂食被临时禁止时照旧跳过")
    val AUTO_BUY = b("auto_buy_supplies", true, "自动补货", "饼干/香皂用完自动用金币买 10 个")
    val HIRE_MAX = i("hire_max", -1, "每日雇佣次数", "雇佣开工每天最多多少次", -1L..1_000_000L)
    val PK_ON = b("pk_on", true, "任务中顺带 PK", "主任务进行中随机顺带打 PK，只挑比自己弱的；输了拉不拉黑看「PK 输了拉黑对方」")
    val PK_BLACKLIST_ON_LOSS = b("pk_blacklist_on_loss", true, "PK 输了拉黑对方", "PK 输了就把对方排除出互动：黑名单模式下加进互动名单，白名单模式下从互动名单移除，之后不再和他 PK；关掉后输了也不处理")
    val PK_MAX = i("pk_max", 10, "每日 PK 上限", "-1=不限", -1L..1_000_000L)
    val SETTLE_VIEW_ON = b("settle_view_on", true, "读结算明细", "任务到期后读奖励明细，计入今日收益并用于判疲劳")
    val LEARN_REWARDS_ON = b("learn_rewards_on", true, "用实收校准收益", "目录不报金币时，按职业和时长用过去真实拿到的金币参与择优（按满档还原，雇佣工单独记账），有一次实收就能用")
    val PROGRESS_ON = b("progress_on", true, "读任务叙事", "任务进行中读一次进度，主页显示宠物正在做什么")
    val ENCOURAGE_TIMES = i("encourage_times", 1, "自动鼓励次数", "每次学习或打工开始后自动鼓励几次，冒险不支持；0=不鼓励", 0L..10L)
    val MAIL_CHECK_ON = b("mail_check_on", false, "信箱可领提醒", "信箱列表没有已领标志，看不出能不能领，所以不再按附件数提醒；能不能领以信箱一键领取的结果为准")
    val CHECKIN_FOOD_ON = b("checkin_food_on", true, "每日签到领食物", "每天领一次签到送的食物，今天领过就不再领")
    val MAIL_CLAIM_ON = b("mail_claim_on", true, "信箱一键领取", "信箱有附件时一键全部领取，没有可领的就跳过", exp = true)
    val COINBAG_ON = b("coinbag_on", true, "自动领福袋", "自己名下的、好友头像上的福袋都自动领")
    val COINBAG_MAX = i("coinbag_max", -1, "每日领福袋上限", "-1=不限，是默认；正数是每天最多领几个", -1L..1_000_000L)
    val COINBAG_EVERY_MIN = i("coinbag_every_min", 5, "福袋扫描间隔", "每隔多少分钟扫一次有没有新福袋；到点准时扫，不等手头的任务结束；默认 5 分钟", 1L..60L)
    val SVIP_GIFT_ON = b("svip_gift_on", false, "每日领 SVIP 礼包", "每天自动领取 QQ SVIP 每日礼包（点券、饼干等）；不是 SVIP 或今天已领过会跳过")
    val DIARY_LIKE_ON = b("diary_like_on", false, "日记点赞", "每天给最近几篇日记点赞；已经点过的服务器不会理会，不会重复计")
    val DAILY_ITEM_ON = b("daily_item_on", false, "每日领学习道具", "每天领一次免费学习道具（高钙奶×1，出门学习可用）；已经领过会跳过")
    val AD_GIFT_ON = b("ad_gift_on", false, "每日广告福利", "每天领取一次每日广告福利")
    val AD_COIN_ON = b("ad_coin_on", false, "结算广告金币", "打工结算后自动领取结算页的广告金币")
    val AD_BACKGROUND_CLAIM_ON = b("ad_background_claim_on", false, "后台领取广告奖励", "跳过广告播放直接上报完成领取；属于伪造广告曝光，可能触发账号风控或封禁，默认关（正常播放后领取）")
    val AD_SWEEP_MAX = i("ad_sweep_max", 500, "历史广告补领每次扫描条数", "每次补领最多扫描多少条出门记录，0=不限（一直扫到最早一条），默认 500", 0L..2000L)
    val WORK_ITEM_ON = b("work_item_on", false, "打工自动用加成道具", "每趟打工开始后自动用一瓶打工加成道具（元气美式/波波奶茶等，每杯+500金币）；背包里没有就跳过")
    val STUDY_ITEM_ON = b("study_item_on", false, "学习自动用加成道具", "每趟学习开始后自动用一瓶学习加成道具（高钙奶/咕噜酸奶等，每杯+1属性）；每趟限 1 杯，背包里没有就跳过")
    val FRIEND_CARE_ON = b("friend_care_on", false, "替好友喂食洗澡", "好友体力或清洁低于阈值时，用自己的饼干、香皂帮他喂食洗澡")
    val FRIEND_CARE_MAX = i("friend_care_max", 10, "每天最多帮几次", "喂食和洗澡合计次数；-1=不限", -1L..1_000_000L)
    val FRIEND_CARE_FLOOR = i("friend_care_floor", 60, "好友低于多少才帮", "好友体力/清洁低于此值才动手", 0L..100L)
    val FRIEND_ONECLICK_ON = b("friend_oneclick_on", false, "好友一键照顾", "对需要照顾的好友用一键照顾，道具不够时服务器自动花金币补")
    val FRIEND_ONECLICK_MAX = i("friend_oneclick_max", 3, "每日一键照顾上限", "每次可能花金币；-1=不限", -1L..1_000_000L)
    val DAILY_REPORT_ON = b("daily_report_on", true, "每日报告", "每天零点生成昨日报告，含次数、时长、金币净变化和奖励")

    // —— 2026-10-15 协议实测后新增 ——
    val SETTLE_CLAIM_ON = b("settle_claim_on", true, "到期发领奖包", "任务到期时像人在页面一样点一下领奖")
    val ATTR_READ_ON = b("attr_read_on", true, "读属性与战力", "每小时读一次力量、智力、魅力，主页显示，PK 挑对手也用它")
    val EXP_READ_ON = b("exp_read_on", true, "读每日经验", "每小时读一次今日经验、上限和子任务进度，主页显示")
    val PK_MARGIN = i("pk_margin", 83, "PK 战力余量", "只挑战力不超过我方百分之 N 的对手", 50L..100L)
    val SPARK_ON = b("spark_on", false, "每天自动踩好友", "每天把好友列表里的宠物好友各踩一次，陌生人不踩，火花名单排除的人除外（白名单模式只踩名单里的人）；每轮最多踩几人见下一项")
    val SPARK_MAX = i("spark_max", -1, "每日踩踩上限", "踩好友加回踩合计的每日总上限；-1=不限，是默认", -1L..1_000_000L)
    val SPARK_PER_ROUND = i("spark_per_round", 6, "每轮最多踩几人", "每 5 分钟一轮，一轮最多踩几人；默认 6 人", 1L..30L)
    val SPARK_REMIND_ON = b("spark_remind_on", false, "火花快清零提醒", "不在火花名单排除范围内的好友，火花待重燃且已断 5 天时提醒，连续 7 天不互踩就会清零", exp = true)
    val SPARK_TONIGHT_ON = b("spark_tonight_on", false, "火花今晚熄灭提醒", "到设定时刻，还在燃但今天还没双向互踩的好友会收到一条提醒，过 24 点火花熄灭进入待重燃。一天最多一次，互踩完自动撤，读不到数据不提醒。与快清零提醒互相独立", exp = true)
    val SPARK_TONIGHT_AT = i("spark_tonight_at", 21 * 60, "火花提醒时间", "提醒时刻，按手机本地时间，默认 21:00", 0L..1439L)
    val SPARK_RETURN_ON = b("spark_return_on", false, "回踩访客", "今天踩了你的人当天回踩一次，每人每天一次，火花名单排除的人除外。自动踩开着时只管非好友访客，好友已在每日自动踩里；自动踩关着时好友访客也算")
    val PAT_ON = b("pat_on", false, "自动摸好友", "每天把好友的宠物各摸一次，好友心情 +2；火花名单排除的人不摸（白名单模式只摸名单里的人）")
    val PAT_SPARK_ONLY = b("pat_spark_only", false, "只摸有火花的好友", "只摸火花列表里的好友，普通好友不摸")
    val HIRED_POLL_ON = b("hired_poll_on", true, "检查是否被雇佣", "每 10 分钟看一次通知找“雇佣了”，用来提醒被雇佣；关掉后不再定时看，但自动召回需要确认被雇佣时仍会现拉一次通知")
    val FEED_PUSH_ON = b("feed_push_on", false, "喂完通知好友", "替好友喂食成功后给对方发一条通知，在页面里亲手喂会自动发，替人喂不会")
    // —— 2026-10-16 v2.1：后台保活与节奏 ——
    val ALARM_CLOCK_ON = b("alarm_clock_on", false, "强力唤醒", "长间隔唤醒改用系统闹钟级别，息屏省电下也准点；状态栏会出现闹钟图标，锁屏会显示下一个闹钟")
    val SCREEN_PAUSE_ON = b("screen_pause_on", false, "息屏时暂停", "屏幕熄灭期间不发任何请求：不派新活、不照顾、不轮询，到期的任务也先不结算")
    val NOTIFY_OPEN = Def("notify_open", Type.STRING, "qq", "点通知打开", "点开模块发的通知（常驻、提醒）时打开哪个应用：qq=QQ（默认，与一直以来的行为相同）；app=模块主界面", choices = listOf("qq", "app"))
    val NIGHT_PAUSE_ON = b("night_pause_on", false, "夜间暂停", "在设定的夜间时段不发任何请求：不派新活、不照顾、不轮询，到期的任务也先不结算")
    val NIGHT_PAUSE_START = i("night_pause_start", 90, "夜间暂停开始", "夜间暂停从几点开始，按手机本地时间，默认 01:30；开始晚于结束就是跨过零点，比如 23:00 到 02:00", 0L..1439L)
    val NIGHT_PAUSE_END = i("night_pause_end", 390, "夜间暂停结束", "夜间暂停到几点结束，按手机本地时间，默认 06:30", 0L..1439L)
    // 键名沿用 short_adv_gap_s（已存的值不丢）：2026-10-16 起语义推广为所有主任务的间隔，默认 20→10 秒
    val SHORT_ADV_GAP_S = i("short_adv_gap_s", 10, "任务间隔", "一件任务做完后，隔多少秒再开始下一件；0 表示一件接一件不停", 0L..3600L)
    val SETTLE_BUFFER_S = i("settle_buffer_s", 5, "结算等待", "任务时间到了之后，等多少秒再去结算；设得太小，服务器可能还没结清而拒绝", 0L..300L)
    val LOG_KEEP_DAYS = i("log_keep_days", 3, "日志保留天数", "按天的日志文件保留几天，含今天，1 到 14", 1L..14L)

    val ALL: List<Def> = listOf(
        AUTO_ON, ATTR, ADV_MODE, AUTO_GOAL, STUDY_BRAKE, ADV_MAX, ADV_ONLY_NAME, ADV_WEATHER_RECALL_ON, WORK_CAREER, HIRE_ALERT_ON, RECALL_ON, RECALL_MODE, RECALL_EXTERNAL_ON, HIRE_ON, SCHEDULE_JSON,
        GOLD_RESERVE, ATTR_LOSS_BUDGET, STAMINA_FLOOR, CLEAN_FLOOR, FEED_FULL_ON, AUTO_BUY, BISCUIT_PRICE, SOAP_PRICE, HIRE_MAX, PK_ON, PK_BLACKLIST_ON_LOSS, PK_MAX,
        SETTLE_VIEW_ON, LEARN_REWARDS_ON, PROGRESS_ON, ENCOURAGE_TIMES, MAIL_CHECK_ON, CHECKIN_FOOD_ON, COINBAG_ON, COINBAG_MAX, COINBAG_EVERY_MIN, SVIP_GIFT_ON, DIARY_LIKE_ON, DAILY_ITEM_ON, AD_GIFT_ON, AD_COIN_ON, AD_BACKGROUND_CLAIM_ON, AD_SWEEP_MAX,
        WORK_ITEM_ON, STUDY_ITEM_ON,
        FRIEND_CARE_ON, FRIEND_CARE_MAX, FRIEND_CARE_FLOOR, FRIEND_ONECLICK_ON, FRIEND_ONECLICK_MAX, DAILY_REPORT_ON,
        MAIL_CLAIM_ON, SETTLE_CLAIM_ON, ATTR_READ_ON, EXP_READ_ON, PK_MARGIN, SPARK_ON, SPARK_MAX, SPARK_PER_ROUND, SPARK_REMIND_ON,
        SPARK_TONIGHT_ON, SPARK_TONIGHT_AT, SPARK_RETURN_ON, PAT_ON, PAT_SPARK_ONLY, HIRED_POLL_ON, FEED_PUSH_ON,
        ALARM_CLOCK_ON, SCREEN_PAUSE_ON, NOTIFY_OPEN, NIGHT_PAUSE_ON, NIGHT_PAUSE_START, NIGHT_PAUSE_END, SHORT_ADV_GAP_S, SETTLE_BUFFER_S, LOG_KEEP_DAYS,
    )

    private val BY_KEY = ALL.associateBy { it.key }

    fun def(key: String): Def? = BY_KEY[key]

    /** 设置存储的结构版本（迁移用）。 */
    const val SCHEMA_KEY = "settings_schema"
    const val SCHEMA = 4

    // —— 读：统一带默认值，不让各处各写一份默认 ——
    fun bool(sp: SharedPreferences, d: Def): Boolean = sp.getBoolean(d.key, d.default as Boolean)
    fun int(sp: SharedPreferences, d: Def): Int = sp.getInt(d.key, d.default as Int)
    fun long(sp: SharedPreferences, d: Def): Long = sp.getLong(d.key, d.default as Long)
    fun string(sp: SharedPreferences, d: Def): String = sp.getString(d.key, d.default as String) ?: (d.default as String)

    /**
     * 文本 → 规范化的值（`setopt` 与主存回灌共用）。不合法返回 null 并且**不写**：
     * 宁可不改也不能写进一个类型错的值（读的时候 ClassCastException 会把整轮 tick 打断）。
     */
    fun coerce(d: Def, raw: Any?): Any? = when (d.type) {
        Type.BOOL -> when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) { "1", "true", "on", "yes" -> true; "0", "false", "off", "no" -> false; else -> null }
            else -> null
        }
        Type.INT, Type.LONG -> {
            val v = when (raw) { is Number -> raw.toLong(); is String -> raw.trim().toLongOrNull(); else -> null }
            v?.takeIf { d.range == null || it in d.range }?.let { if (d.type == Type.INT) it.toInt() else it }
        }
        Type.STRING -> (raw as? String ?: raw?.toString())?.takeIf { d.choices.isEmpty() || it in d.choices }
    }

    /** 按登记类型写入；返回是否真的写了。 */
    fun put(ed: SharedPreferences.Editor, d: Def, value: Any?): Boolean {
        val v = coerce(d, value) ?: return false
        when (d.type) {
            Type.BOOL -> ed.putBoolean(d.key, v as Boolean)
            Type.INT -> ed.putInt(d.key, v as Int)
            Type.LONG -> ed.putLong(d.key, v as Long)
            Type.STRING -> ed.putString(d.key, v as String)
        }
        return true
    }

    /**
     * 迁移：把旧版遗留值改成现行口径。幂等，每次 QQ 启动都跑一遍也无害。
     *  - `adv_on`（v0.x 的布尔开关）→ `adv_mode`；
     *  - `adv_mode=off`（“不冒险”挡已撤下）→ `mix`；
     *  - 类型不对的键（例如主存回灌把 Long 写成了 Int）按登记类型重写，写不回就删掉回到默认。
     * @return 改动的键数
     */
    fun migrate(sp: SharedPreferences): Int {
        var n = 0
        val ed = sp.edit()
        val all = sp.all
        if (!all.containsKey(ADV_MODE.key) && all.containsKey("adv_on")) {
            ed.putString(ADV_MODE.key, "mix"); n++   // 旧 adv_on=false 即“不冒险”，该挡已并入 mix
        }
        if (all.containsKey("adv_on")) { ed.remove("adv_on"); n++ }
        if (all[ADV_MODE.key] == "off") { ed.putString(ADV_MODE.key, "mix"); n++ }
        for (d in ALL) {
            val v = all[d.key] ?: continue
            val okType = when (d.type) {
                Type.BOOL -> v is Boolean
                Type.INT -> v is Int
                Type.LONG -> v is Long
                Type.STRING -> v is String
            }
            if (okType) continue
            if (!put(ed, d, v)) ed.remove(d.key)
            n++
        }
        // schema 4（2026-10-15）：每日上限统一为 -1=不限；旧 0（=一次都不做）→ 关掉对应开关；默认变成“不限”的键旧默认值迁过去
        if ((all[SCHEMA_KEY] as? Int ?: 0) < 4) {
            listOf(
                Triple(SPARK_MAX, SPARK_ON, 20), Triple(COINBAG_MAX, COINBAG_ON, 10), Triple(PK_MAX, PK_ON, null),
                Triple(HIRE_MAX, HIRE_ON, null), Triple(FRIEND_CARE_MAX, FRIEND_CARE_ON, null), Triple(FRIEND_ONECLICK_MAX, FRIEND_ONECLICK_ON, null),
            ).forEach { (cap, toggle, legacy) ->
                val m = io.github.juwanhwang.autoqqpet.pet.Caps.migrate(all[cap.key] as? Int, cap.default as Int, legacy) ?: return@forEach
                ed.putInt(cap.key, m.first); n++
                if (m.second) { ed.putBoolean(toggle.key, false); n++ }
            }
        }
        if ((all[SCHEMA_KEY] as? Int ?: 0) < SCHEMA) { ed.putInt(SCHEMA_KEY, SCHEMA); n++ }
        if (n > 0) ed.apply()
        return n
    }
}
