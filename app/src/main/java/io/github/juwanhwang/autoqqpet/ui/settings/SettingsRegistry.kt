package io.github.juwanhwang.autoqqpet.ui.settings

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.store.SettingKeys
import io.github.juwanhwang.autoqqpet.store.SettingKeys.Def
import io.github.juwanhwang.autoqqpet.store.Store
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.Route
import io.github.juwanhwang.autoqqpet.ui.components.DialogSpec
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.data.Backend
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.UiPrefs
import org.json.JSONObject

/**
 * 设置注册表 —— 设置页完全由这张表生成（数据驱动、声明式）。
 *
 * 加一个开关只需加一行，例如后端新增了 `setfoo op=on|off` 与 prefs 键 `foo_on`：
 *
 *   Toggle("foo_on", Group.PLAN, "某某功能", Glyphs.Sparkle, on = "开着时会发生什么。", off = "关着时会发生什么。", action = "setfoo"),
 *
 * 行为约定（与后端的契约见 docs/design/DESIGN.md）：
 *  - Toggle：写 prefs[key] 布尔 + 广播 `action op=on|off`；`local = true` 时只写本机外观偏好、不广播。
 *  - Choice：写 prefs[key] 字符串 + 广播 `action op=<值>`。
 *  - Number：写 prefs[key] 整数 + 广播 `action op=<op> n=<值>`。
 *  - Select / Action / Link：逻辑在 lambda 里（目录、名单这类动态内容）。
 * 要让 QQ 进程重装后也能恢复，新键还需加进 Store.SETTINGS_KEYS（后端负责）。
 *
 * 文案规则：title 说“是什么”，on/off/summary 说“会发生什么”，不解释实现。
 */
/**
 * 设置按主题分组（2026-10-16 v2.1 重排）：一个设置只出现在一个地方。
 * 照着“火花”页的做法——相关的开关、上限、提醒放在一起；火花的全部设置只在火花页里，这里只留入口。
 */
enum class Group(val title: String) {
    KEEPALIVE("后台运行"),
    PLAN("干活"),
    MONEY("金币与雇佣"),
    HIRED("被好友雇走"),
    CARE("照顾与补给"),
    FREEBIES("每日白捡"),
    SPARK("火花"),
    PK("PK"),
    FRIEND_CARE("帮好友照顾"),
    FRIENDS("好友名单"),
    DATA("数据与报告"),
    LOOK("外观"),
    SUPPORT("诊断与反馈"),
    ABOUT("关于"),
}

data class Option(val value: String, val label: String, val detail: String? = null)

/** 设置项渲染时能用到的环境（由设置页实现）。 */
interface SettingsEnv {
    val context: Context
    val backend: Backend
    fun bool(key: String, def: Boolean): Boolean
    fun string(key: String, def: String): String
    fun int(key: String, def: Int): Int
    fun navigate(route: Route)
    fun dialog(spec: DialogSpec)
    fun openFriendList(which: String)
    fun note(text: String)
}

sealed class Setting(
    val id: String,
    val group: Group,
    val title: String,
    val glyph: ImageVector,
    val visible: (SettingsEnv) -> Boolean,
)

class Toggle(
    val key: String,
    group: Group,
    title: String,
    glyph: ImageVector,
    val on: String,
    val off: String,
    val action: String? = null,
    val default: Boolean = false,
    val local: Boolean = false,
    /** 打开前先确认（讲清后果）；为 null 直接打开。 */
    val confirmOn: ((SettingsEnv) -> DialogSpec)? = null,
    val enabledWhen: (SettingsEnv) -> Boolean = { true },
    /** 实验功能：命令含义已定，但字段号或数据来源仍是推断（SettingKeys.Def.experimental）。 */
    val experimental: Boolean = false,
    visible: (SettingsEnv) -> Boolean = { true },
    /** 开着时的文案随其它设置变化（为 null 用 [on]）。 */
    val onText: ((SettingsEnv) -> String)? = null,
) : Setting(key, group, title, glyph, visible)

class Choice(
    val key: String,
    group: Group,
    title: String,
    glyph: ImageVector,
    val options: List<Option>,
    val default: String,
    val action: String? = null,
    val local: Boolean = false,
    visible: (SettingsEnv) -> Boolean = { true },
) : Setting(key, group, title, glyph, visible)

class NumberSetting(
    val key: String,
    group: Group,
    title: String,
    glyph: ImageVector,
    val unit: String,
    val range: IntRange,
    val default: Int,
    val presets: List<Int>,
    val summary: (Int) -> String,
    val action: String? = null,
    val op: String? = null,
    visible: (SettingsEnv) -> Boolean = { true },
    /**
     * “每日上限”类：存储里代表“不限”的值（-1；adv_max 沿用 0）。非 null 时编辑面板是显式的“不限 / 限制为 N”，
     * 选“限制”才出现数字（N ≥ 1、无上界），**任何时候都不显示“0 = 不限”**。
     */
    val unlimitedValue: Int? = null,
    /** 步进一次的步长（时刻类用 30 分钟），默认 1。 */
    val step: Int = 1,
    /** 到边界绕回（时刻类：23:30 再加 → 00:00），默认不绕、夹在 range 内。 */
    val wrap: Boolean = false,
    /** 值在步进器中间与预设钮上的显示格式（如分钟数 → "23:30"）；为 null 显示原数字 + unit。 */
    val formatValue: ((Int) -> String)? = null,
    /** 编辑面板里步进器下方的一句补充说明。 */
    val sheetNote: String? = null,
) : Setting(key, group, title, glyph, visible) {
    fun isUnlimited(v: Int): Boolean = unlimitedValue != null && (v == unlimitedValue || v < 1)
    /** 摘要：不限时统一说“不限”。 */
    fun summaryOf(v: Int): String = if (isUnlimited(v)) "不限" else summary(v)
}

class Select(
    id: String,
    group: Group,
    title: String,
    glyph: ImageVector,
    val options: (SettingsEnv) -> List<Option>,
    val current: (SettingsEnv) -> String,
    val onPick: (SettingsEnv, String) -> Unit,
    val summary: (SettingsEnv) -> String?,
    val sheetNote: (SettingsEnv) -> String? = { null },
    visible: (SettingsEnv) -> Boolean = { true },
) : Setting(id, group, title, glyph, visible)

class Action(
    id: String,
    group: Group,
    title: String,
    glyph: ImageVector,
    val summary: (SettingsEnv) -> String?,
    val run: (SettingsEnv) -> Unit,
    visible: (SettingsEnv) -> Boolean = { true },
) : Setting(id, group, title, glyph, visible)

/** 外观：一排圆形主题色色板，点一下立即生效。 */
class AccentPicker(
    id: String,
    group: Group,
    title: String,
    glyph: ImageVector,
    visible: (SettingsEnv) -> Boolean = { true },
) : Setting(id, group, title, glyph, visible)

class Link(
    id: String,
    group: Group,
    title: String,
    glyph: ImageVector,
    val route: Route,
    val summary: (SettingsEnv) -> String? = { null },
    visible: (SettingsEnv) -> Boolean = { true },
) : Setting(id, group, title, glyph, visible)

private typealias K = SettingKeys

/** 默认值只取 SettingKeys 一处，界面不再各写一份。 */
private fun bd(d: Def): Boolean = d.default as Boolean
private fun id(d: Def): Int = d.default as Int
private fun sd(d: Def): String = d.default as String

object SettingsRegistry {

    val all: List<Setting> = listOf(
        // ── 干活 ──
        // “去哪打工”（work_career）已移到主页（打工优先时显示）；这里只显示目录状态，手动刷新在「运行诊断」里
        Link(
            "catalog_info", Group.PLAN, "打工目录", Glyphs.Briefcase, Route.DIAGNOSTICS,
            summary = { env -> catalogStamp(env) },
        ),
        Choice(
            K.ADV_MODE.key, Group.PLAN, "干活方式", Glyphs.Briefcase,
            options = listOf(
                Option("auto", "最优调度", "按自动目标算今天的最优安排，就是主页选的那挡"),
                Option("mix", "学习优先", "旧逻辑，疲惫后转冒险"),
                Option("work", "工作优先", "旧逻辑，疲惫后转冒险"),
                Option("only", "只冒险", "不学习、不打工，只刷冒险"),
                Option("custom", "排班", "按排班页排好的顺序做"),
            ),
            default = sd(K.ADV_MODE), action = Backend.SETOPT,
        ),
        Choice(
            K.STUDY_BRAKE.key, Group.PLAN, "学习停课线", Glyphs.Briefcase,
            options = listOf(
                Option("none", "不设线", "属性优先时打折也照上，属性总量最大"),
                Option("full", "只在全额时段上课", "学工累计过 8 小时就停课"),
                Option("no10", "一折时段停课", "学工累计过 12 小时就停课，默认这挡"),
            ),
            default = sd(K.STUDY_BRAKE), action = Backend.SETOPT,
        ),
        Link(
            "schedule", Group.PLAN, "排班", Glyphs.Calendar, Route.SCHEDULE,
            summary = { env ->
                val s = env.backend.loadSchedule()
                val on = env.backend.mode == "custom"
                when {
                    s.steps.isEmpty() -> "还没有排班"
                    on -> "正在使用 · ${s.steps.size} 步"
                    else -> "${s.steps.size} 步 · 未使用"
                }
            },
        ),
        NumberSetting(
            "adv_max", Group.PLAN, "每天最多冒险", Glyphs.Compass,
            unit = "次", range = 1..1_000_000, default = 0, presets = listOf(5, 10, 20, 50),
            summary = { n -> "$n 次，到了就改回学习或打工" },
            action = "adv", op = "limit", unlimitedValue = 0,
        ),
        NumberSetting(
            K.SHORT_ADV_GAP_S.key, Group.PLAN, "任务间隔", Glyphs.Compass,
            unit = "秒", range = 0..3600, default = id(K.SHORT_ADV_GAP_S), presets = listOf(0, 10, 60, 180),
            summary = { n -> if (n <= 0) "一件接一件不停" else "一件任务做完后，隔 $n 秒再开始下一件" },
            action = Backend.SETOPT,
        ),
        NumberSetting(
            K.SETTLE_BUFFER_S.key, Group.PLAN, "结算等待", Glyphs.Compass,
            unit = "秒", range = 0..300, default = id(K.SETTLE_BUFFER_S), presets = listOf(0, 5, 20, 60),
            summary = { n -> if (n <= 0) "任务时间到了，马上结算" else "任务时间到了之后，等 $n 秒再去结算" },
            action = Backend.SETOPT,
        ),
        Toggle(
            K.WORK_ITEM_ON.key, Group.PLAN, "打工自动用加成道具", Glyphs.Drop,
            on = "每趟打工开始后自动用一瓶打工加成道具（元气美式/波波奶茶等，每杯 +500 金币）；背包里没有就跳过。",
            off = "不自动喝。",
            action = Backend.SETOPT, default = bd(K.WORK_ITEM_ON),
        ),
        Toggle(
            K.STUDY_ITEM_ON.key, Group.PLAN, "学习自动用加成道具", Glyphs.Drop,
            on = "每趟学习开始后自动用一瓶学习加成道具（高钙奶/咕噜酸奶等，每杯 +1 属性）；每趟限 1 杯，背包里没有就跳过。",
            off = "不自动喝。",
            action = Backend.SETOPT, default = bd(K.STUDY_ITEM_ON),
        ),
        NumberSetting(
            K.ENCOURAGE_TIMES.key, Group.PLAN, "学习/打工鼓励次数", Glyphs.Sparkle,
            unit = "次", range = 0..10, default = id(K.ENCOURAGE_TIMES), presets = listOf(0, 1, 3, 5, 10),
            summary = { n ->
                when {
                    n <= 0 -> "不自动鼓励"
                    n == 1 -> "每次学习/打工开始后鼓励 1 次"
                    else -> "每次学习/打工共鼓励 $n 次：第 1 次在开始后，其余在进行中陆续补上"
                }
            },
            action = Backend.SETOPT,
        ),
        Toggle(
            K.ADV_WEATHER_RECALL_ON.key, Group.PLAN, "天色不对自动召回", Glyphs.Compass,
            on = "自动或排班派出的冒险刚出发就发现天色不对时，立刻召回重新出发。手动开的冒险不查。",
            off = "天色不对的冒险也照常跑完。",
            action = Backend.SETOPT, default = bd(K.ADV_WEATHER_RECALL_ON),
        ),

        Toggle(
            K.PROGRESS_ON.key, Group.DATA, "实时任务叙事", Glyphs.Sparkle,
            on = "主页显示宠物此刻在做什么，比如“正在为客人占卜…”。",
            off = "只显示任务名与倒计时。",
            action = Backend.SETOPT, default = bd(K.PROGRESS_ON),
        ),
        Toggle(
            K.SETTLE_VIEW_ON.key, Group.DATA, "读结算明细", Glyphs.Target,
            on = "每次结算读服务器的奖励明细：算进今日收益，也用来判断哪一档干累了。",
            off = "不读明细，疲劳只按目录读数判断。",
            action = Backend.SETOPT, default = bd(K.SETTLE_VIEW_ON),
        ),
        Toggle(
            K.LEARN_REWARDS_ON.key, Group.DATA, "用实收校准收益", Glyphs.Pulse,
            on = "目录不报金币的岗位，用它过去真实拿到的金币参与比价，至少有两次样本才用。",
            off = "只按目录上的数字比价。",
            action = Backend.SETOPT, default = bd(K.LEARN_REWARDS_ON),
            visible = { env -> env.bool(K.SETTLE_VIEW_ON.key, bd(K.SETTLE_VIEW_ON)) },
        ),
        Toggle(
            K.SETTLE_CLAIM_ON.key, Group.DATA, "到期发领奖", Glyphs.Check,
            on = "到点像在 QQ 页面里一样点一下领奖。",
            off = "不点，等服务器到点自己结算。",
            action = Backend.SETOPT, default = bd(K.SETTLE_CLAIM_ON),
        ),
        Toggle(
            K.ATTR_READ_ON.key, Group.DATA, "读属性与战力", Glyphs.Target,
            on = "每小时读一次力量、智力、魅力，主页显示，PK 也按真实战力挑对手。",
            off = "不读，主页不显示属性，PK 只按名单挑。",
            action = Backend.SETOPT, default = bd(K.ATTR_READ_ON),
        ),
        Toggle(
            K.EXP_READ_ON.key, Group.DATA, "读每日经验", Glyphs.Sparkle,
            on = "每小时读一次今日经验和各项任务进度，主页显示。",
            off = "不读。",
            action = Backend.SETOPT, default = bd(K.EXP_READ_ON),
        ),

        // ── 后台运行（A：不拉前台也一直跑）──
        Action(
            "keepalive_help", Group.KEEPALIVE, "让它在后台一直跑", Glyphs.Info,
            summary = { "电池、闹钟、作用域怎么设" },
            run = { env -> env.dialog(DialogSpec(title = "后台一直跑需要的手机设置", message = KeepAliveSetup.HELP, confirm = "知道了", dismiss = null)) },
        ),
        Action(
            "exact_alarm_qq", Group.KEEPALIVE, "QQ 的精确闹钟", Glyphs.Clock,
            summary = { env -> exactAlarmText(env) },
            run = { env -> if (!KeepAliveSetup.openExactAlarm(env.context)) env.note("打不开系统设置，请手动在 设置 → 应用 → QQ → 闹钟和提醒 里开") },
        ),
        Toggle(
            K.ALARM_CLOCK_ON.key, Group.KEEPALIVE, "强力唤醒", Glyphs.Bell,
            on = "唤醒用系统闹钟级别，息屏省电模式下也准点。状态栏会出现闹钟图标。",
            off = "用普通精确唤醒。",
            action = Backend.SETOPT, default = bd(K.ALARM_CLOCK_ON),
            confirmOn = {
                DialogSpec(
                    title = "打开强力唤醒？",
                    message = "唤醒会以“闹钟”的身份排进系统，省电模式不会推迟它。\n\n" +
                        "状态栏会常驻一个闹钟图标，锁屏可能显示“下一个闹钟”的时间。",
                    confirm = "打开",
                    dismiss = "先不用",
                )
            },
        ),
        Toggle(
            K.SCREEN_PAUSE_ON.key, Group.KEEPALIVE, "息屏时暂停", Glyphs.Bell,
            on = "屏幕熄灭期间不发任何请求：不派新活、不照顾、不轮询，到期的任务也先不结算。",
            off = "息屏也照常跑。",
            action = Backend.SETOPT, default = bd(K.SCREEN_PAUSE_ON),
        ),
        Choice(
            K.NOTIFY_OPEN.key, Group.KEEPALIVE, "点通知打开", Glyphs.Bell,
            options = listOf(
                Option("qq", "QQ", "点常驻通知和提醒通知打开 QQ（默认，与一直以来的行为相同）"),
                Option("app", "模块", "点常驻通知和提醒通知打开模块主界面"),
            ),
            default = sd(K.NOTIFY_OPEN), action = Backend.SETOPT,
        ),
        Toggle(
            K.NIGHT_PAUSE_ON.key, Group.KEEPALIVE, "夜间暂停", Glyphs.Bell,
            on = "在设定的夜间时段（默认 1:30–6:30）不发任何请求：不派新活、不照顾、不轮询，到期的任务也先不结算。",
            off = "夜间也照常跑。",
            action = Backend.SETOPT, default = bd(K.NIGHT_PAUSE_ON),
        ),
        NumberSetting(
            K.NIGHT_PAUSE_START.key, Group.KEEPALIVE, "夜间暂停开始", Glyphs.Clock,
            unit = "", range = 0..1439, default = id(K.NIGHT_PAUSE_START), presets = listOf(1380, 0, 90),
            summary = { m -> "每天 " + io.github.juwanhwang.autoqqpet.pet.SparkTonight.hhmm(m) + " 开始，本机时间" },
            action = Backend.SETOPT, step = 30, wrap = true,
            formatValue = { m -> io.github.juwanhwang.autoqqpet.pet.SparkTonight.hhmm(m) },
            sheetNote = "开始晚于结束就是跨过零点，比如 23:00 到 02:00。",
            visible = { env -> env.bool(K.NIGHT_PAUSE_ON.key, bd(K.NIGHT_PAUSE_ON)) },
        ),
        NumberSetting(
            K.NIGHT_PAUSE_END.key, Group.KEEPALIVE, "夜间暂停结束", Glyphs.Clock,
            unit = "", range = 0..1439, default = id(K.NIGHT_PAUSE_END), presets = listOf(390, 120, 420),
            summary = { m -> "每天 " + io.github.juwanhwang.autoqqpet.pet.SparkTonight.hhmm(m) + " 结束，本机时间" },
            action = Backend.SETOPT, step = 30, wrap = true,
            formatValue = { m -> io.github.juwanhwang.autoqqpet.pet.SparkTonight.hhmm(m) },
            visible = { env -> env.bool(K.NIGHT_PAUSE_ON.key, bd(K.NIGHT_PAUSE_ON)) },
        ),
        NumberSetting(
            K.LOG_KEEP_DAYS.key, Group.KEEPALIVE, "日志保留", Glyphs.Calendar,
            unit = "天", range = 1..14, default = id(K.LOG_KEEP_DAYS), presets = listOf(1, 3, 7, 14),
            summary = { n -> if (n <= 1) "只留今天" else "保留最近 $n 天，含今天" },
            action = Backend.SETOPT,
        ),

        // ── 金币与雇佣 ──
        NumberSetting(
            K.GOLD_RESERVE.key, Group.MONEY, "金币底线", Glyphs.Coin,
            unit = "金币", range = 0..100_000, default = id(K.GOLD_RESERVE), presets = listOf(0, 300, 1000, 3000),
            summary = { n -> if (n <= 0) "不留底，能花就花" else "交学费后至少还剩 $n" },
            action = Backend.SETOPT,
        ),
        NumberSetting(
            K.ATTR_LOSS_BUDGET.key, Group.MONEY, "今日可亏金币", Glyphs.Coin,
            unit = "金币", range = 0..100_000, default = id(K.ATTR_LOSS_BUDGET), presets = listOf(0, 500, 1000, 3000),
            summary = { n -> if (n <= 0) "一分不亏：属性优先也要保住早上的金币，不够先赚钱" else "属性优先时，今天最多净花 $n 金币上课，不为了保住金币先去赚钱" },
            action = Backend.SETOPT,
        ),
        Toggle(
            K.HIRE_ON.key, Group.MONEY, "开工雇佣好友", Glyphs.Users,
            on = "开工时雇佣一个好友，优先挑加成最高的，不能雇就换下一个。",
            off = "只干自己的。",
            action = Backend.SETOPT, default = bd(K.HIRE_ON),
        ),
        NumberSetting(
            K.HIRE_MAX.key, Group.MONEY, "每天最多雇几次", Glyphs.Users,
            unit = "次", range = 1..1_000_000, default = id(K.HIRE_MAX), presets = listOf(1, 3, 5, 10),
            summary = { n -> "$n 次" },
            action = Backend.SETOPT, unlimitedValue = -1,
            visible = { env -> env.bool(K.HIRE_ON.key, bd(K.HIRE_ON)) },
        ),
        Action(
            "hire_list", Group.MONEY, "雇佣名单", Glyphs.Users,
            summary = { env ->
                val f = friends(env)
                val n = csv(f.optString("hire_list")).size
                val mode = f.optString("hire_list_mode").takeIf { it == "wl" || it == "bl" } ?: "wl"
                when {
                    n == 0 -> "不限 · 开工时自动挑最合适的"
                    mode == "wl" -> "白名单 · 只雇勾选的 $n 位好友"
                    else -> "黑名单 · 不雇勾选的 $n 位好友"
                }
            },
            run = { env -> env.openFriendList("hire") },
            visible = { env -> env.bool(K.HIRE_ON.key, bd(K.HIRE_ON)) },
        ),
        Toggle(
            K.RECALL_ON.key, Group.HIRED, "被雇走时自动召回", Glyphs.Bolt,
            on = "确认被好友雇走后，进度到 71% 自动叫回来：拿满基础工资和 75% 分成，干完不召回只拿 10% 分成。",
            off = "不召回，让它干完。",
            action = Backend.SETOPT, default = bd(K.RECALL_ON),
            confirmOn = {
                DialogSpec(
                    title = "打开自动召回？",
                    message = "只召回确认是被好友雇走的打工（服务器通知里有“雇佣了”、且不是 Autoqqpet 开的）；你在 QQ 里亲手开的打工默认不会被召回，除非再打开下面那项「增强识别」。",
                    confirm = "打开",
                    dismiss = "先不用",
                )
            },
        ),
        Choice(
            K.RECALL_MODE.key, Group.HIRED, "什么时候召回", Glyphs.Clock,
            options = listOf(
                Option("best_split", "拿到大头再召回", "进度过 71% 时召回，分成最多"),
                Option("immediate", "立刻召回", "一发现就叫回来"),
            ),
            default = sd(K.RECALL_MODE), action = Backend.SETOPT,
            visible = { env -> env.bool(K.RECALL_ON.key, bd(K.RECALL_ON)) },
        ),
        Toggle(
            K.RECALL_EXTERNAL_ON.key, Group.HIRED, "增强识别", Glyphs.Bolt,
            on = "认不出是不是被雇走时也直接按上面的时机召回，不再核对通知。包括你在 QQ 里亲手开的工，它会被中途叫回、按当时进度结算。",
            off = "只处理确认被好友雇走的打工，亲手开的不动。",
            action = Backend.SETOPT, default = bd(K.RECALL_EXTERNAL_ON),
            confirmOn = {
                DialogSpec(
                    title = "打开增强识别？",
                    message = "打开后，模块认不出是不是被雇走的打工也会直接召回；你在 QQ 里亲手开的工同样会被中途叫回、按当时进度结算，不会等它干完。确定要打开？",
                    confirm = "打开",
                    dismiss = "先不用",
                )
            },
            visible = { env -> env.bool(K.RECALL_ON.key, bd(K.RECALL_ON)) || env.bool(K.HIRE_ALERT_ON.key, bd(K.HIRE_ALERT_ON)) },
        ),

        // ── 照顾与补给 ──
        NumberSetting(
            K.STAMINA_FLOOR.key, Group.CARE, "体力低于多少就喂", Glyphs.Bolt,
            unit = "", range = 0..100, default = id(K.STAMINA_FLOOR), presets = listOf(20, 30, 50, 70),
            summary = { n -> "体力低于 $n 先喂饱再干活" },
            action = Backend.SETOPT,
        ),
        NumberSetting(
            K.CLEAN_FLOOR.key, Group.CARE, "清洁低于多少就洗", Glyphs.Drop,
            unit = "", range = 0..100, default = id(K.CLEAN_FLOOR), presets = listOf(20, 30, 50, 70),
            summary = { n -> "清洁低于 $n 先洗干净再干活" },
            action = Backend.SETOPT,
        ),
        Toggle(
            K.FEED_FULL_ON.key, Group.CARE, "饱食不足马上喂", Glyphs.Bolt,
            on = "照顾检查时只要饱食没满就喂到满，不等低于体力地板。",
            off = "体力低于地板了才喂（默认）。",
            action = Backend.SETOPT, default = bd(K.FEED_FULL_ON),
        ),
        Toggle(
            K.AUTO_BUY.key, Group.CARE, "用完自动补货", Glyphs.Coin,
            on = "饼干或香皂用完，用金币买 10 个。",
            off = "用完就停下照顾，等你补货。",
            action = Backend.SETOPT, default = bd(K.AUTO_BUY),
        ),
        NumberSetting(
            K.BISCUIT_PRICE.key, Group.CARE, "饼干单价", Glyphs.Coin,
            unit = "金币", range = 0..10_000, default = id(K.BISCUIT_PRICE), presets = listOf(5, 10, 20),
            summary = { n -> "最优调度按 $n 金币一块饼干算补给账" },
            action = Backend.SETOPT,
        ),
        NumberSetting(
            K.SOAP_PRICE.key, Group.CARE, "香皂片单价", Glyphs.Coin,
            unit = "金币", range = 0..10_000, default = id(K.SOAP_PRICE), presets = listOf(2, 5, 10),
            summary = { n -> "最优调度按 $n 金币一片香皂算补给账" },
            action = Backend.SETOPT,
        ),

        // ── 提醒 ──
        Toggle(
            "hire_alert_on", Group.HIRED, "被雇佣时提醒", Glyphs.Bell,
            on = "确认被好友雇走、进度到 71% 时发通知提醒你去召回，自动召回开着时不提醒。",
            off = "不提醒。",
            action = "hirealert",
        ),
        Toggle(
            K.HIRED_POLL_ON.key, Group.HIRED, "检查是否被雇佣", Glyphs.Users,
            on = "每 10 分钟看一眼 QQ 宠物的消息，找“雇佣了你”的通知。",
            off = "不看。认不出被雇佣，自动召回和提醒都不会生效。",
            action = Backend.SETOPT, default = bd(K.HIRED_POLL_ON),
        ),

        Toggle(
            K.DAILY_REPORT_ON.key, Group.DATA, "每日报告", Glyphs.Calendar,
            on = "每天零点把昨天做了什么、赚了多少归档，主页能看到。",
            off = "不生成报告。",
            action = Backend.SETOPT, default = bd(K.DAILY_REPORT_ON),
        ),

        // ── 每日白捡（只领自己的东西，不花金币）──
        Toggle(
            K.CHECKIN_FOOD_ON.key, Group.FREEBIES, "每日签到领食物", Glyphs.Check,
            on = "每天领一次签到送的饼干。",
            off = "不自动领。",
            action = Backend.SETOPT, default = bd(K.CHECKIN_FOOD_ON),
        ),
        Toggle(
            K.MAIL_CLAIM_ON.key, Group.FREEBIES, "信箱一键领取", Glyphs.Bell,
            on = "每 6 小时一键领一次信箱奖励；没有可领的就跳过。",
            off = "不自动领。",
            action = Backend.SETOPT, default = bd(K.MAIL_CLAIM_ON), experimental = true,
        ),
        // “信箱可领提醒”（mail_check_on）已从界面移除：信箱列表没有“已领”标志，只读判断不了可不可领（R7），键保留兼容
        Toggle(
            K.COINBAG_ON.key, Group.FREEBIES, "自动领福袋", Glyphs.Coin,
            on = "自己名下、好友头像上，有福袋就自动领。",
            off = "不领福袋。",
            action = Backend.SETOPT, default = bd(K.COINBAG_ON),
        ),
        NumberSetting(
            K.COINBAG_MAX.key, Group.FREEBIES, "每天最多领福袋", Glyphs.Coin,
            unit = "个", range = 1..1_000_000, default = id(K.COINBAG_MAX), presets = listOf(5, 10, 20, 50),
            summary = { n -> "$n 个" },
            action = Backend.SETOPT, unlimitedValue = -1,
            visible = { env -> env.bool(K.COINBAG_ON.key, bd(K.COINBAG_ON)) },
        ),
        NumberSetting(
            K.COINBAG_EVERY_MIN.key, Group.FREEBIES, "福袋扫描间隔", Glyphs.Coin,
            unit = "分钟", range = 1..60, default = id(K.COINBAG_EVERY_MIN), presets = listOf(3, 5, 10, 20),
            summary = { n -> "$n 分钟" },
            action = Backend.SETOPT,
            visible = { env -> env.bool(K.COINBAG_ON.key, bd(K.COINBAG_ON)) },
        ),
        Toggle(
            K.SVIP_GIFT_ON.key, Group.FREEBIES, "每日领 SVIP 礼包", Glyphs.Sparkle,
            on = "每天自动领取 SVIP 每日礼包（点券、饼干等）；不是 SVIP 或今天已领过会跳过。",
            off = "不自动领取。",
            action = Backend.SETOPT, default = bd(K.SVIP_GIFT_ON),
        ),
        Toggle(
            K.DIARY_LIKE_ON.key, Group.FREEBIES, "日记点赞", Glyphs.Heart,
            on = "每天给最近几篇日记点赞；已经点过的服务器不会理会，不会重复计。",
            off = "不自动点赞。",
            action = Backend.SETOPT, default = bd(K.DIARY_LIKE_ON),
        ),
        Toggle(
            K.DAILY_ITEM_ON.key, Group.FREEBIES, "每日领学习道具", Glyphs.Sparkle,
            on = "每天领一次免费学习道具（高钙奶 ×1，出门学习可用）；已经领过会跳过。",
            off = "不自动领。",
            action = Backend.SETOPT, default = bd(K.DAILY_ITEM_ON),
        ),
        Toggle(
            K.AD_GIFT_ON.key, Group.FREEBIES, "每日广告福利", Glyphs.Sparkle,
            on = "每天领取一次每日广告福利。",
            off = "不领取。",
            action = Backend.SETOPT, default = bd(K.AD_GIFT_ON),
        ),
        Toggle(
            K.AD_COIN_ON.key, Group.FREEBIES, "结算广告金币", Glyphs.Coin,
            on = "打工结算后自动领取结算页的广告金币。",
            off = "不领取。",
            action = Backend.SETOPT, default = bd(K.AD_COIN_ON),
        ),
        Toggle(
            K.AD_BACKGROUND_CLAIM_ON.key, Group.FREEBIES, "后台领取广告奖励", Glyphs.Sparkle,
            on = "跳过广告播放直接上报完成领取（默认关）。这属于伪造广告曝光，可能触发账号风控或封禁，请自行评估风险。",
            off = "正常播放广告后再领取。",
            action = Backend.SETOPT, default = bd(K.AD_BACKGROUND_CLAIM_ON),
        ),
        Link(
            "ad_sweep", Group.FREEBIES, "历史广告补领", Glyphs.Coin, Route.AD_SWEEP,
            summary = { "查看进度" },
        ),
        // ── 好友 ──
        Link(
            "spark", Group.SPARK, "火花", Glyphs.Flame, Route.SPARK,
            summary = { env -> sparkSummary(env) },
        ),
        // 火花的开关/上限/提醒只在火花页（SPARK_PAGE），这里不重复放（B4：回踩访客、每天自动踩、熄灭提醒曾两处都有）
        Toggle(
            K.PK_ON.key, Group.PK, "任务中顺带 PK", Glyphs.Target,
            on = "主任务进行时顺手挑比自己弱的好友 PK。",
            off = "不 PK。",
            action = Backend.SETOPT, default = bd(K.PK_ON),
        ),
        Toggle(
            K.PK_BLACKLIST_ON_LOSS.key, Group.PK, "PK 输了拉黑对方", Glyphs.Target,
            on = "PK 输了就把对方排除出互动（黑名单模式加进名单、白名单模式从名单移除），之后不再和他 PK（默认）。",
            off = "PK 输了也不处理，还能再和他打。",
            action = Backend.SETOPT, default = bd(K.PK_BLACKLIST_ON_LOSS),
            visible = { env -> env.bool(K.PK_ON.key, bd(K.PK_ON)) },
        ),
        NumberSetting(
            K.PK_MAX.key, Group.PK, "每天最多 PK", Glyphs.Target,
            unit = "次", range = 1..1_000_000, default = id(K.PK_MAX), presets = listOf(5, 10, 20, 50),
            summary = { n -> "$n 次" },
            action = Backend.SETOPT, unlimitedValue = -1,
            visible = { env -> env.bool(K.PK_ON.key, bd(K.PK_ON)) },
        ),
        NumberSetting(
            K.PK_MARGIN.key, Group.PK, "只挑多弱的对手", Glyphs.Target,
            unit = "%", range = 50..100, default = id(K.PK_MARGIN), presets = listOf(70, 80, 90, 95),
            summary = { n -> "对手战力不超过我的 $n%" },
            action = Backend.SETOPT,
            visible = { env -> env.bool(K.PK_ON.key, bd(K.PK_ON)) },
        ),
        Toggle(
            K.FRIEND_CARE_ON.key, Group.FRIEND_CARE, "替好友喂食洗澡", Glyphs.Heart,
            on = "好友饿了脏了，用你自己的饼干和香皂帮一把。",
            off = "只照顾自己的宠物。",
            action = Backend.SETOPT, default = bd(K.FRIEND_CARE_ON),
        ),
        NumberSetting(
            K.FRIEND_CARE_FLOOR.key, Group.FRIEND_CARE, "好友低于多少才帮", Glyphs.Heart,
            unit = "", range = 0..100, default = id(K.FRIEND_CARE_FLOOR), presets = listOf(30, 50, 60, 80),
            summary = { n -> "体力或清洁低于 $n 才出手" },
            action = Backend.SETOPT,
            visible = { env -> env.bool(K.FRIEND_CARE_ON.key, bd(K.FRIEND_CARE_ON)) },
        ),
        NumberSetting(
            K.FRIEND_CARE_MAX.key, Group.FRIEND_CARE, "每天最多帮几次", Glyphs.Heart,
            unit = "次", range = 1..1_000_000, default = id(K.FRIEND_CARE_MAX), presets = listOf(5, 10, 20, 50),
            summary = { n -> "喂食和洗澡合计 $n 次" },
            action = Backend.SETOPT, unlimitedValue = -1,
            visible = { env -> env.bool(K.FRIEND_CARE_ON.key, bd(K.FRIEND_CARE_ON)) },
        ),
        Toggle(
            K.FEED_PUSH_ON.key, Group.FRIEND_CARE, "喂完告诉对方", Glyphs.Chat,
            on = "替好友喂完，给对方发一条“我喂了你的宠物”，和在 QQ 里亲手喂一样。",
            off = "悄悄喂，对方收不到通知。",
            action = Backend.SETOPT, default = bd(K.FEED_PUSH_ON),
            visible = { env -> env.bool(K.FRIEND_CARE_ON.key, bd(K.FRIEND_CARE_ON)) },
        ),
        Toggle(
            K.FRIEND_ONECLICK_ON.key, Group.FRIEND_CARE, "好友一键照顾", Glyphs.Sparkle,
            on = "对需要照顾的好友用 QQ 的一键照顾，道具不够时服务器会直接扣金币。",
            off = "不用一键照顾。",
            action = Backend.SETOPT, default = bd(K.FRIEND_ONECLICK_ON),
            confirmOn = { env ->
                DialogSpec(
                    title = "一键照顾会花金币",
                    message = "道具不够时，服务器会自动用你的金币补上，花多少由服务器决定。\n\n" +
                        "每天最多 ${env.int(K.FRIEND_ONECLICK_MAX.key, id(K.FRIEND_ONECLICK_MAX))} 次。" +
                        "同时开着「替好友喂食洗澡」时，以那个为准。",
                    confirm = "打开",
                    dismiss = "先不用",
                )
            },
        ),
        NumberSetting(
            K.FRIEND_ONECLICK_MAX.key, Group.FRIEND_CARE, "每天最多一键照顾", Glyphs.Sparkle,
            unit = "次", range = 1..1_000_000, default = id(K.FRIEND_ONECLICK_MAX), presets = listOf(1, 3, 5, 10),
            summary = { n -> "$n 次 · 每次可能花金币" },
            action = Backend.SETOPT, unlimitedValue = -1,
            visible = { env -> env.bool(K.FRIEND_ONECLICK_ON.key, bd(K.FRIEND_ONECLICK_ON)) },
        ),
        Action(
            "friend_list", Group.FRIENDS, "互动名单", Glyphs.Users,
            summary = { env ->
                val f = friends(env)
                val n = csv(f.optString("friend_list")).size
                val mode = f.optString("friend_list_mode").takeIf { it == "wl" || it == "bl" } ?: "bl"
                when {
                    n == 0 -> "不限 · 和所有好友 PK，也帮他们照顾"
                    mode == "wl" -> "白名单 · 只和勾选的 $n 位好友互动"
                    else -> "黑名单 · 不和勾选的 $n 位好友互动。输掉的对手会自动加进来。"
                }
            },
            run = { env -> env.openFriendList("friend") },
        ),
        Action(
            "refresh_roster", Group.FRIENDS, "刷新好友列表", Glyphs.Refresh,
            summary = { env ->
                val n = roster(env).size
                if (n == 0) "还没有好友列表" else "共 $n 位好友"
            },
            run = { env ->
                env.backend.requestRoster()
                env.note("已向 QQ 要最新好友列表")
            },
        ),

        // ── 外观 ──
        Choice(
            UiPrefs.KEY_THEME, Group.LOOK, "主题", Glyphs.Contrast,
            options = listOf(
                Option("system", "跟随系统"),
                Option("light", "浅色"),
                Option("dark", "深色"),
            ),
            default = "system", local = true,
        ),
        AccentPicker(UiPrefs.KEY_ACCENT, Group.LOOK, "主题色", Glyphs.Palette),
        Toggle(
            UiPrefs.KEY_REDUCE_MOTION, Group.LOOK, "减少动效", Glyphs.Motion,
            on = "过渡瞬时完成，团子不再呼吸。",
            off = "保留过渡与微动效。",
            local = true,
            enabledWhen = { env -> !Motion.systemReduced(env.context) },
        ),

        // ── 诊断与反馈 ──
        Link(
            "diagnostics", Group.SUPPORT, "运行诊断", Glyphs.Pulse, Route.DIAGNOSTICS,
            summary = { "体检、运行快照、提交反馈" },
        ),

        // ── 关于 ──
        Link("about", Group.ABOUT, "关于 Autoqqpet", Glyphs.Info, Route.ABOUT, summary = { "v${io.github.juwanhwang.autoqqpet.BuildConfig.VERSION_NAME} · 许可协议与隐私" }),
        Link("license", Group.ABOUT, "许可协议", Glyphs.Lock, Route.LICENSE),
    )

    // ── 火花（设置页与火花页共用同一份定义，文案一致）──

    fun sparkDailyToggle(g: Group) = Toggle(
        K.SPARK_ON.key, g, "每天自动踩好友", Glyphs.Flame,
        on = "每天踩所有 QQ 宠物好友各一次，火花名单里排除的人除外（白名单模式只踩名单里的人）。",
        off = "不自动踩好友。",
        action = Backend.SETOPT, default = bd(K.SPARK_ON),
    )

    fun sparkReturnToggle(g: Group) = Toggle(
        K.SPARK_RETURN_ON.key, g, "回踩访客", Glyphs.Flame,
        on = "非好友访客踩了你，就自动回踩一次，每人每天一次，火花名单排除的人不算。好友已在每日自动踩里。",
        off = "不回踩非好友访客。",
        action = Backend.SETOPT, default = bd(K.SPARK_RETURN_ON),
        onText = { env ->
            if (env.bool(K.SPARK_ON.key, bd(K.SPARK_ON))) "非好友访客踩了你，就自动回踩一次，每人每天一次，火花名单排除的人不算。好友已在每日自动踩里。"
            else "谁踩了你就回踩一次，好友和非好友访客都算，每人每天一次，火花名单排除的人不算。"
        },
        confirmOn = { env ->
            DialogSpec(
                title = "打开回踩访客？",
                message = (if (env.bool(K.SPARK_ON.key, bd(K.SPARK_ON))) "非好友访客踩了你，就自动回踩一次，每人每天一次，火花名单排除的人不算。好友已在每日自动踩里。"
                else "谁踩了你就回踩一次，好友和非好友访客都算，每人每天一次，火花名单排除的人不算。") +
                    "\n\n访客不一定认识，不想踩的人在火花页把他排除即可（黑名单模式加进名单，白名单模式移出名单）。",
                confirm = "打开",
                dismiss = "先不用",
            )
        },
    )

    fun sparkTonightToggle(g: Group) = Toggle(
        K.SPARK_TONIGHT_ON.key, g, "今晚熄灭提醒", Glyphs.Bell,
        on = "到提醒时间还有在燃的好友今天没互踩完，就提醒一次：过了 24 点火花会熄灭、进入待重燃。互踩完自动撤。",
        off = "不提醒。",
        action = Backend.SETOPT, default = bd(K.SPARK_TONIGHT_ON), experimental = true,
    )

    fun patToggle(g: Group) = Toggle(
        K.PAT_ON.key, g, "自动摸好友", Glyphs.Smile,
        on = "每天把好友的宠物各摸一次，好友心情 +2；火花名单排除的人不摸（白名单模式只摸名单里的人）。",
        off = "不自动摸。",
        action = Backend.SETOPT, default = bd(K.PAT_ON),
    )

    fun patSparkOnlyToggle(g: Group) = Toggle(
        K.PAT_SPARK_ONLY.key, g, "只摸有火花的好友", Glyphs.Flame,
        on = "只摸火花列表里的好友，普通好友不摸。",
        off = "所有好友都摸。",
        action = Backend.SETOPT, default = bd(K.PAT_SPARK_ONLY),
        visible = { env -> env.bool(K.PAT_ON.key, bd(K.PAT_ON)) },
    )

    fun sparkTonightTime(g: Group) = Select(
        "spark_tonight_at", g, "提醒时间", Glyphs.Clock,
        options = { (34..47).map { half -> val m = half * 30; Option(m.toString(), io.github.juwanhwang.autoqqpet.pet.SparkTonight.hhmm(m)) } },
        current = { env -> env.int(K.SPARK_TONIGHT_AT.key, id(K.SPARK_TONIGHT_AT)).toString() },
        onPick = { env, v -> env.backend.setInt(K.SPARK_TONIGHT_AT.key, v.toIntOrNull() ?: id(K.SPARK_TONIGHT_AT), Backend.SETOPT, null); env.note("提醒时间已更新") },
        summary = { env -> "每天 " + io.github.juwanhwang.autoqqpet.pet.SparkTonight.hhmm(env.int(K.SPARK_TONIGHT_AT.key, id(K.SPARK_TONIGHT_AT))) + "，本机时间" },
        sheetNote = { "按手机本地时间。" },
        visible = { env -> env.bool(K.SPARK_TONIGHT_ON.key, bd(K.SPARK_TONIGHT_ON)) },
    )

    /** 火花页里的全部设置（设置页只放入口，避免两处各有一份）。 */
    val sparkPage: List<Setting> by lazy {
        listOf(
            sparkDailyToggle(Group.SPARK),
            sparkReturnToggle(Group.SPARK),
            patToggle(Group.SPARK),
            patSparkOnlyToggle(Group.SPARK),
            NumberSetting(
                K.SPARK_PER_ROUND.key, Group.SPARK, "每轮最多踩几人", Glyphs.Flame,
                unit = "人", range = 1..30, default = id(K.SPARK_PER_ROUND), presets = listOf(3, 6, 10, 20),
                summary = { n -> "每 5 分钟一轮、每轮最多 $n 人；领福袋也按这个节奏" },
                action = Backend.SETOPT,
            ),
            NumberSetting(
                K.SPARK_MAX.key, Group.SPARK, "每天最多踩", Glyphs.Flame,
                unit = "次", range = 1..1_000_000, default = id(K.SPARK_MAX), presets = listOf(50, 100, 300, 1000),
                summary = { n -> "踩好友和回踩访客合计 $n 次" },
                action = Backend.SETOPT, unlimitedValue = -1,
                visible = { env -> env.bool(K.SPARK_ON.key, bd(K.SPARK_ON)) || env.bool(K.SPARK_RETURN_ON.key, bd(K.SPARK_RETURN_ON)) },
            ),
            sparkTonightToggle(Group.SPARK),
            sparkTonightTime(Group.SPARK),
            Toggle(
                K.SPARK_REMIND_ON.key, Group.SPARK, "火花快清零提醒", Glyphs.Bell,
                on = "火花在“待重燃”里断了 5 天时提醒，连续 7 天不互踩就清零。与“今晚熄灭”分开提醒。",
                off = "不提醒。",
                action = Backend.SETOPT, default = bd(K.SPARK_REMIND_ON), experimental = true,
            ),
        )
    }

    /** 历史广告二级页里的设置（设置页只放入口）。 */
    val adSweepPage: List<Setting> by lazy {
        listOf(
            NumberSetting(
                K.AD_SWEEP_MAX.key, Group.FREEBIES, "每次扫描记录条数", Glyphs.Coin,
                unit = "条", range = 10..2000, default = id(K.AD_SWEEP_MAX), presets = listOf(500, 1000, 2000),
                summary = { n -> "每次最多扫描 $n 条出门记录，挑其中未领的已完成打工补领" },
                action = Backend.SETOPT, unlimitedValue = 0,
            ),
        )
    }

    private fun exactAlarmText(env: SettingsEnv): String {
        val snap = io.github.juwanhwang.autoqqpet.ui.data.FullSnapshot.parse(
            env.backend.prefs.getString(io.github.juwanhwang.autoqqpet.ModulePrefs.KEY_STATUS_SNAPSHOT, null))
        val h = snap?.health
        val kind = when (h?.alarmKind) { "alarmClock" -> "闹钟级"; "exactIdle" -> "精确"; "idle" -> "不精确 · 可能被省电推迟"; else -> null }
        return when (h?.exactAlarmOk) {
            true -> "已允许" + (kind?.let { " · 当前唤醒：$it" } ?: "")
            false -> "未允许 · 唤醒会被省电推迟，点此去开"
            null -> "等 QQ 报告状态，托管跑起来后显示"
        }
    }

    // ── 打工目录 ──


    fun careerOptions(env: SettingsEnv): List<Option> = careerOptions(env.backend.prefs)
    fun careerSummary(env: SettingsEnv): String = careerSummary(env.backend.prefs)
    fun careerNote(env: SettingsEnv): String? = careerNote(env.backend.prefs)

    private fun tiers(p: android.content.SharedPreferences) = JobCatalog.decode(p.getString(JobCatalog.KEY_CATALOG, "").orEmpty())

    /** “去哪打工”的选项（主页内联选择器与旧设置页共用）。 */
    fun careerOptions(p: android.content.SharedPreferences): List<Option> {
        val pinned = p.getLong(JobCatalog.KEY_CAREER, 0L)
        val listed = JobCatalog.careers(tiers(p))
        val out = mutableListOf(Option("0", "自动", "哪里划算去哪里"))
        listed.forEach { (type, name) -> out += Option(type.toString(), name) }
        if (pinned > 0L && listed.none { it.first == pinned }) {
            out += Option(pinned.toString(), "当前选择 · 目录里暂未读到")
        }
        return out
    }

    fun careerSummary(p: android.content.SharedPreferences): String {
        val pinned = p.getLong(JobCatalog.KEY_CAREER, 0L)
        if (pinned <= 0L) return "自动 · 哪里划算去哪里"
        return JobCatalog.careers(tiers(p)).firstOrNull { it.first == pinned }?.second ?: "type=$pinned"
    }

    fun careerNote(p: android.content.SharedPreferences): String? {
        val t = tiers(p)
        if (t.isEmpty()) return "还没有目录：托管跑起来后会自动读到。"
        val pinned = p.getLong(JobCatalog.KEY_CAREER, 0L)
        val best = JobCatalog.best(t, if (pinned > 0) pinned else 0L) ?: return null
        val tie = if (best.unique) "" else "，多个档位并列"
        return "时长档位每轮自动取最划算的一档。当前最高约 ${best.perHour} 点/小时$tie。"
    }

    /** 只显示信息：后台自动维护（每天 5:00 后与职业/阶段变化时重读），不再显示“已过期”。 */
    private fun catalogStamp(env: SettingsEnv): String {
        val at = env.backend.prefs.getLong(JobCatalog.KEY_CATALOG_AT, 0L)
        if (at <= 0L) return "自动维护 · 托管跑起来后自动读到"
        val n = JobCatalog.careers(tiers(env.backend.prefs)).size
        return "自动维护 · $n 个职业 · 上次更新 ${Copy.clock(at)}"
    }

    // ── 好友名单（主存副本） ──

    fun friends(env: SettingsEnv): JSONObject = runCatching {
        JSONObject(Store.prefs(env.context).getString(Store.EXTRACT_FRIENDS, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    fun csv(s: String, sep: String = ","): List<String> = s.split(sep).map { it.trim() }.filter { it.isNotEmpty() }

    /** 搜索语料：标题、分组名、登记表里那条的标题与说明、二层菜单的内容（选项、预设、确认弹窗文案）。 */
    fun searchCorpus(s: Setting, env: SettingsEnv): List<String> {
        val out = mutableListOf(s.title, s.group.title)
        val key = when (s) {
            is Toggle -> s.key
            is Choice -> s.key
            is NumberSetting -> s.key
            else -> s.id
        }
        SettingKeys.def(key)?.let { out += it.title; out += it.desc }
        fun safe(f: () -> String?) {
            runCatching { f() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        when (s) {
            is Toggle -> {
                out += s.on; out += s.off
                s.onText?.let { t -> safe { t(env) } }
                s.confirmOn?.let { cf -> safe { cf(env).title }; safe { cf(env).message } }
            }
            is Choice -> s.options.forEach { o -> out += o.label; o.detail?.let { out += it } }
            is NumberSetting -> {
                out += s.unit
                s.presets.forEach { p -> out += p.toString(); out += s.summary(p) }
                out += s.summary(s.default)
            }
            is Select -> {
                safe { s.summary(env) }; safe { s.sheetNote(env) }
                runCatching { s.options(env) }.getOrDefault(emptyList()).forEach { o -> out += o.label; o.detail?.let { out += it } }
            }
            is Action -> safe { s.summary(env) }
            is Link -> safe { s.summary(env) }
            is AccentPicker -> out += "主题色"
        }
        return out
    }

    /** 设置搜索：语料里任意一处包含关键词（不分大小写）即算命中；纯字母关键词还按拼音首字母搜（hh → 火花）。 */
    fun matches(s: Setting, env: SettingsEnv, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val corpus = searchCorpus(s, env)
        if (corpus.any { it.lowercase().contains(q) }) return true
        if (q.all { it in 'a'..'z' || it in '0'..'9' }) {
            return corpus.any { PinyinInitials.initialsOf(it).contains(q) }
        }
        return false
    }

    /** 名册：`uin⋄昵称` 以 ~ 分隔。 */
    fun roster(env: SettingsEnv): List<Pair<String, String>> =
        csv(friends(env).optString("roster"), "~").mapNotNull {
            val kv = it.split("⋄")
            if (kv.size < 2 || kv[0].isEmpty()) null else kv[0] to kv[1]
        }.distinctBy { it.first }

    /** 火花页入口的摘要：火花名单人数 + 最近一次读到的火花人数。 */
    fun sparkSummary(env: SettingsEnv): String {
        val m = io.github.juwanhwang.autoqqpet.ui.data.SparkModel.parse(Store.prefs(env.context).getString(Store.EXTRACT_FRIENDS, "{}"))
        val on = listOfNotNull(
            if (env.bool(K.SPARK_ON.key, bd(K.SPARK_ON))) "每天自动踩" else null,
            if (env.bool(K.SPARK_RETURN_ON.key, bd(K.SPARK_RETURN_ON))) "回踩" else null,
            if (env.bool(K.SPARK_TONIGHT_ON.key, bd(K.SPARK_TONIGHT_ON))) "熄灭提醒" else null,
        ).joinToString("、").ifEmpty { "全部关闭" }
        if (m.rows.isEmpty()) return "$on · 还没有好友与火花数据"
        return "$on · 在燃 ${m.lit} · 待重燃 ${m.rekindle} · 今日已踩 ${m.pokedToday}/${m.target}" + if (m.blacklist.isNotEmpty()) " · 火花名单 ${m.blacklist.size}（${if (m.listMode == "wl") "白" else "黑"}）" else ""
    }

    private fun listSummary(env: SettingsEnv, key: String, empty: String): String {
        val n = csv(friends(env).optString(key)).size
        return if (n == 0) empty else "$n 人"
    }
}
