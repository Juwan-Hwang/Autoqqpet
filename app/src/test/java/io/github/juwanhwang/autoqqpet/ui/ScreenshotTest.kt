package io.github.juwanhwang.autoqqpet.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.background
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.ui.data.CurrentTask
import io.github.juwanhwang.autoqqpet.ui.data.LedgerEntry
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.data.PetSnapshot
import io.github.juwanhwang.autoqqpet.ui.data.FullSnapshot
import io.github.juwanhwang.autoqqpet.ui.data.Phase
import io.github.juwanhwang.autoqqpet.ui.data.TaskKind
import io.github.juwanhwang.autoqqpet.ui.data.TodayStats
import io.github.juwanhwang.autoqqpet.ui.screens.AboutScreen
import io.github.juwanhwang.autoqqpet.ui.screens.DiagnosticsScreen
import io.github.juwanhwang.autoqqpet.ui.screens.HomeScreen
import io.github.juwanhwang.autoqqpet.ui.screens.LicenseScreen
import io.github.juwanhwang.autoqqpet.ui.screens.OnboardingScreen
import io.github.juwanhwang.autoqqpet.ui.screens.ScheduleScreen
import io.github.juwanhwang.autoqqpet.ui.screens.SettingsScreen
import io.github.juwanhwang.autoqqpet.ui.theme.AevumTheme
import io.github.juwanhwang.autoqqpet.ui.theme.LocalReducedMotion
import io.github.juwanhwang.autoqqpet.ui.theme.UiPrefs
import io.github.juwanhwang.autoqqpet.ui.theme.AccentTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 界面截图：把每个页面在浅色/深色下渲染成 PNG（app/build/outputs/roborazzi/），
 * 用来人工审视排版、对比度与缺数据时的降级。顺带保证每个页面都能完整组合一遍、不崩。
 * 环境动效在截图里关闭（减少动效模式），画面是静态终态。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1500dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    init {
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun shoot(name: String, accent: AccentTheme = AccentTheme.OCEAN, content: @Composable () -> Unit) {
        render(accent, content)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun render(accent: AccentTheme = AccentTheme.OCEAN, content: @Composable () -> Unit) {
        UiPrefs.init(ctx)
        UiPrefs.setAccentForPreview(accent)   // 静态状态跨用例共享：每张都显式指定（默认青碧）
        compose.setContent {
            AevumTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) { content() }
            }
        }
        compose.waitForIdle()
    }

    /** 只截某个带 testTag 的节点（今天卡、提醒条）。 */
    private fun shootTag(name: String, tag: String, content: @Composable () -> Unit) {
        render(content = content)
        compose.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun alertsJson(now: Long, vararg a: Triple<String, String, String>): String =
        a.joinToString(",", "[", "]") { (id, level, text) ->
            """{"id":"$id","level":"$level","text":"$text","at":$now,"incident":"d1"${if (id == "mail") ",\"src\":\"mailclaim\"" else ""}}"""
        }

    /** 带一条提醒的主页快照。 */
    private fun withAlert(now: Long, id: String, level: String, text: String): PetSnapshot {
        val base = runningSnapshot(now)
        val json = """{"v":1,"at":$now,"alerts":${alertsJson(now, Triple(id, level, text))},"health":{"pay_tired":${id == "pay_tired"}}}"""
        return base.copy(full = FullSnapshot.parse(json), alert = "")
    }

    private fun live(): LiveState = LiveState(ctx)

    private fun runningSnapshot(now: Long) = PetSnapshot.EMPTY.copy(
        moduleEnabled = true, agreed = true, autoOn = true, attr = "智力", mode = "mix",
        status = "started:work:街角占卜摊", lastHeartbeatAt = now - 12_000, nextWakeAt = now + 29 * 60_000,
        task = CurrentTask(
            TaskKind.WORK, "街角占卜摊 · 45分钟", now - 15 * 60_000, now + 30 * 60_000 + 12_000,
            narrative = "团子正在为一位迷路的旅人看手相…", mine = true,
        ),
        phase = Phase.TASK,
        today = TodayStats(study = 3, work = 4, adventure = 12, studyWorkMinutes = 315, adventureMinutes = 42, recalls = 0, tired = false),
        timeline = listOf(
            LedgerEntry(now - 300 * 60_000, "08:10", TaskKind.STUDY, 45, false),
            LedgerEntry(now - 250 * 60_000, "09:00", TaskKind.STUDY, 45, false),
            LedgerEntry(now - 200 * 60_000, "09:50", TaskKind.WORK, 45, false),
            LedgerEntry(now - 120 * 60_000, "11:10", TaskKind.ADVENTURE, 1, false),
            LedgerEntry(now - 15 * 60_000, "13:05", TaskKind.WORK, 45, false),
        ),
        heartbeat = mapOf("hunger" to 72, "clean" to 58.0, "feel" to 90, "gold" to 12850, "total" to 54.1, "pet_name" to "团子"),
        heartbeatAt = now - 12_000,
        full = FullSnapshot.parse(fullJson(now)),
    )

    /** 与 CONTRACT §2.2 同形的一帧快照：今日收益、金币曲线、实发奖励、提醒、昨日报告。 */
    private fun fullJson(now: Long): String {
        val day = java.time.LocalDate.now().toString()
        val curve = (0..16).joinToString(",") { i ->
            val g = 12000 + i * 55 + (if (i % 3 == 0) -40 else 30) + (if (i > 9) 120 else 0)
            "[${now - (16 - i) * 20 * 60_000L},$g]"
        }
        return """
        {"v":1,"at":$now,
         "pet":{"name":"团子","feel":90,"hunger":72,"clean":58,"total":54.1,"gold":12850,
           "attrs":{"strength":839,"intel":1607,"charm":6468,"power":8914,"at":$now},
           "exp":{"today":86,"cap":120,"mult":1.2,"self_care_full":true,"tasks":[
             {"name":"给自己的宠物喂食/洗澡","done":50,"cap":50,"exp":50,"full":true},
             {"name":"去好友家踩一踩","done":3,"cap":3,"exp":30,"full":true},
             {"name":"每天回家看看","done":1,"cap":1,"exp":10,"full":true}]}},
         "today":{"day":"$day","counts":{"study":3,"work":4,"adventure":12,"pk":2},
           "study_work_min":315,"adventure_min":42,"gold_start":12000,"gold_now":12850,"gold_delta":850,
           "hire_spent":250,"rewards":{"智力":96,"工分":42,"金币":610},"gold_curve":[$curve]},
         "report_last":{"day":"${java.time.LocalDate.now().minusDays(1)}","study":5,"work":6,"adventure":20,"pk":4,"pk_win":3,
           "hire":1,"hire_spent":250,"friend_care":2,"coinbag":1,"study_work_min":540,"adventure_min":60,"gold_delta":1320},
         "alerts":[{"id":"spark","level":"info","text":"火花快断了：好友B（19 天，再断 1 天清零）。今天互踩一次就能重燃。","at":$now}],
         "health":{"pay_tired":false,"mail_claimable":2}}
        """.trimIndent()
    }

    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun home_running() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(runningSnapshot(now), now) }
        shoot("home_running_light") { HomeScreen(l) {} }
    }

    @Test @Config(qualifiers = "w400dp-h3000dp-night-xxhdpi") fun home_running_dark() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(runningSnapshot(now), now) }
        shoot("home_running_dark") { HomeScreen(l) {} }
    }

    @Test fun home_paused_empty() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(PetSnapshot.EMPTY.copy(moduleEnabled = true, agreed = true, phase = Phase.PAUSED), now) }
        shoot("home_paused_light") { HomeScreen(l) {} }
    }

    @Test @Config(qualifiers = "+night") fun home_lost_dark() {
        val now = System.currentTimeMillis()
        val s = runningSnapshot(now).copy(phase = Phase.LOST, lastHeartbeatAt = now - 9 * 60_000, task = null, alert = "")
        val l = live().apply { debugSet(s, now) }
        shoot("home_lost_dark") { HomeScreen(l) {} }
    }

    @Test fun home_module_off() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(PetSnapshot.EMPTY.copy(moduleEnabled = false, agreed = true, autoOn = true, phase = Phase.MODULE_OFF), now) }
        shoot("home_module_off_light") { HomeScreen(l) {} }
    }

    @Config(qualifiers = "w400dp-h6400dp-xxhdpi")
    @Test fun settings() {
        val l = live()
        shoot("settings_light") { SettingsScreen(l, onBack = {}, onOpen = {}) }
    }

    @Test @Config(qualifiers = "w400dp-h6400dp-night-xxhdpi") fun settings_dark() {
        val l = live()
        shoot("settings_dark") { SettingsScreen(l, onBack = {}, onOpen = {}) }
    }

    /** 火花页：主存副本里放一份火花列表 + 名单（昵称/号码都是假的）。 */
    private fun seedSparks() {
        val sparks = listOf(
            "10001⋄好友A⋄1⋄20⋄-1⋄0", "10002⋄好友C⋄1⋄15⋄-1⋄1", "10003⋄好友D⋄2⋄19⋄6⋄0",
            "10004⋄好友E⋄1⋄6⋄-1⋄0", "10005⋄一个名字特别特别长的好友昵称⋄2⋄7⋄2⋄0", "10006⋄路人⋄3⋄0⋄-1⋄0",
        ).joinToString("~")
        val json = org.json.JSONObject()
            .put("sparks", sparks).put("sparks_at", (System.currentTimeMillis() - 20 * 60_000).toString())
            .put("spark_bl", "10006")
            .put("roster", "10001⋄好友A~20001⋄老同学~20002⋄同事")
        io.github.juwanhwang.autoqqpet.store.Store.prefs(ctx).edit()
            .putString(io.github.juwanhwang.autoqqpet.store.Store.EXTRACT_FRIENDS, json.toString()).commit()
    }

    @Config(qualifiers = "w400dp-h2400dp-xxhdpi")
    @Test fun spark() {
        seedSparks()
        shoot("spark_light") { io.github.juwanhwang.autoqqpet.ui.screens.SparkScreen(live(), onBack = {}) }
    }

    @Test @Config(qualifiers = "w400dp-h2400dp-night-xxhdpi") fun spark_dark() {
        seedSparks()
        shoot("spark_dark") { io.github.juwanhwang.autoqqpet.ui.screens.SparkScreen(live(), onBack = {}) }
    }

    @Test fun spark_empty() {
        shoot("spark_empty_light") { io.github.juwanhwang.autoqqpet.ui.screens.SparkScreen(live(), onBack = {}) }
    }

    @Test fun schedule() {
        ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit()
            .putString(Schedule.PREF_KEY, Schedule.encode(Schedule.templates()[2].second)).commit()
        val l = live()
        shoot("schedule_light") { ScheduleScreen(l, onBack = {}) }
    }

    @Test fun schedule_empty() {
        val l = live()
        shoot("schedule_empty_light") { ScheduleScreen(l, onBack = {}) }
    }

    @Test fun diagnostics() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(runningSnapshot(now), now) }
        shoot("diagnostics_light") { DiagnosticsScreen(l, onBack = {}) }
    }

    @Test fun about() {
        shoot("about_light") { AboutScreen(onBack = {}, onOpen = {}) }
    }

    @Test @Config(qualifiers = "+night") fun license_dark() {
        shoot("license_dark") { LicenseScreen(onBack = {}) }
    }

    @Test fun onboarding_notice() {
        shoot("onboarding_notice_light") { OnboardingScreen(returning = false, onAgreed = {}, onExit = {}) }
    }

    @Test fun onboarding_eula() {
        shoot("onboarding_eula_light") { OnboardingScreen(returning = true, onAgreed = {}, onExit = {}) }
    }

    // ── 2026-10-15 追加：主题、今天卡、提醒条、排班步骤数 ──────────────────

    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun home_teal_light() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(runningSnapshot(now), now) }
        shoot("home_teal_light") { HomeScreen(l) {} }
    }

    @Test @Config(qualifiers = "w400dp-h3000dp-night-xxhdpi") fun home_teal_dark() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(runningSnapshot(now), now) }
        shoot("home_teal_dark") { HomeScreen(l) {} }
    }

    private fun fakeEnv(): io.github.juwanhwang.autoqqpet.ui.settings.SettingsEnv = object : io.github.juwanhwang.autoqqpet.ui.settings.SettingsEnv {
        override val context: Context = ctx
        override val backend = io.github.juwanhwang.autoqqpet.ui.data.Backend(ctx)
        override fun bool(key: String, def: Boolean) = def
        override fun string(key: String, def: String) = def
        override fun int(key: String, def: Int) = def
        override fun navigate(route: Route) = Unit
        override fun dialog(spec: io.github.juwanhwang.autoqqpet.ui.components.DialogSpec) = Unit
        override fun openFriendList(which: String) = Unit
        override fun note(text: String) = Unit
    }

    @Config(qualifiers = "w400dp-h300dp-xxhdpi")
    @Test fun theme_picker() {
        shoot("theme_picker_light") {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(io.github.juwanhwang.autoqqpet.ui.theme.Aevum.colors.surface)) {
                io.github.juwanhwang.autoqqpet.ui.screens.AccentSwatchRow(fakeEnv())
            }
        }
    }

    @Test @Config(qualifiers = "w400dp-h300dp-night-xxhdpi") fun theme_picker_dark() {
        shoot("theme_picker_dark_coral", AccentTheme.CORAL) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(io.github.juwanhwang.autoqqpet.ui.theme.Aevum.colors.surface)) {
                io.github.juwanhwang.autoqqpet.ui.screens.AccentSwatchRow(fakeEnv())
            }
        }
    }

    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun today_study_only() {
        val now = System.currentTimeMillis()
        val s = runningSnapshot(now).copy(
            full = null,
            today = TodayStats(study = 2, work = 0, adventure = 0, studyWorkMinutes = 90, adventureMinutes = 0, recalls = 0, tired = false,
                studyWorkSec = 90 * 60, adventureSec = 0),
        )
        val l = live().apply { debugSet(s, now) }
        shootTag("today_study_only", "today") { HomeScreen(l) {} }
    }

    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun today_empty() {
        val now = System.currentTimeMillis()
        val s = runningSnapshot(now).copy(
            full = null,
            today = TodayStats(study = 0, work = 0, adventure = 0, studyWorkMinutes = 0, adventureMinutes = 0, recalls = 0, tired = false,
                studyWorkSec = 0, adventureSec = 0),
        )
        val l = live().apply { debugSet(s, now) }
        shootTag("today_empty", "today") { HomeScreen(l) {} }
    }

    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun alert_mail() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(withAlert(now, "mail", "info", "信箱里有奖励没能自动领取（服务器返回 136611），去 QQ 宠物信箱手动领一下。"), now) }
        shootTag("alert_mail", "alert_strip") { HomeScreen(l) {} }
    }

    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun alert_recall() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(withAlert(now, "recall", "action", "宠物被好友雇去打工，进度 72% 已进入分成最高的区间，去 QQ 点『召回』（还剩约 12 分钟）。"), now) }
        shootTag("alert_recall", "alert_strip") { HomeScreen(l) {} }
    }

    @Test @Config(qualifiers = "w400dp-h3000dp-night-xxhdpi") fun alert_pay_tired_dark() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(withAlert(now, "pay_tired", "info", "今日学工报酬都比早上低了（宠物疲劳），会按当前报酬自动在学工与冒险之间取舍；明天自动恢复。"), now) }
        shootTag("alert_pay_tired_dark", "alert_strip") { HomeScreen(l) {} }
    }

    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun alert_care_blocked() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(withAlert(now, "care_blocked", "action", "喂食被服务器连拒 3 次，今天先跳过照顾。"), now) }
        shootTag("alert_care_blocked", "alert_strip") { HomeScreen(l) {} }
    }

    /** 排班步骤不设上限：14 步，标题“步骤 · 14”。 */
    @Config(qualifiers = "w400dp-h4000dp-xxhdpi")
    @Test fun schedule_many_steps() {
        val steps = (1..14).map { i ->
            io.github.juwanhwang.autoqqpet.pet.Step("s$i", if (i % 2 == 0) io.github.juwanhwang.autoqqpet.pet.StepKind.WORK else io.github.juwanhwang.autoqqpet.pet.StepKind.STUDY,
                goal = io.github.juwanhwang.autoqqpet.pet.GoalType.COUNT, n = i)
        }
        val model = Schedule(wrap = io.github.juwanhwang.autoqqpet.pet.Wrap.LOOP, steps = steps)
        ctx.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE).edit().putString(Schedule.PREF_KEY, Schedule.encode(model)).commit()
        shoot("schedule_steps_14") { ScheduleScreen(live(), onBack = {}) }
    }

    /** 主页最底部：应用名与版本（Autoqqpet · v<版本>）。 */
    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun home_footer() {
        val now = System.currentTimeMillis()
        val l = live().apply { debugSet(runningSnapshot(now), now) }
        shootTag("home_footer", "footer") { HomeScreen(l) {} }
    }

    /** 协议页开头：不能出现维护说明/注释残留。 */
    @Test fun license_light() {
        shoot("license_light") { LicenseScreen(onBack = {}) }
    }
}
