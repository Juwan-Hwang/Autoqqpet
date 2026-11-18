package io.github.juwanhwang.autoqqpet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import io.github.juwanhwang.autoqqpet.diag.Diagnostics
import io.github.juwanhwang.autoqqpet.store.Store
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.EulaTerms
import io.github.juwanhwang.autoqqpet.ui.components.AevumButton
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.AevumDialogHost
import io.github.juwanhwang.autoqqpet.ui.components.BusyDialog
import io.github.juwanhwang.autoqqpet.ui.components.ButtonTone
import io.github.juwanhwang.autoqqpet.ui.components.DialogSpec
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.Hairline
import io.github.juwanhwang.autoqqpet.ui.components.Page
import io.github.juwanhwang.autoqqpet.ui.components.ProgressRing
import io.github.juwanhwang.autoqqpet.ui.components.SectionLabel
import io.github.juwanhwang.autoqqpet.ui.components.ToastHost
import io.github.juwanhwang.autoqqpet.ui.components.rememberToast
import io.github.juwanhwang.autoqqpet.ui.data.Backend
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.data.PetSnapshot
import io.github.juwanhwang.autoqqpet.ui.settings.KeepAliveSetup
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class Verdict { OK, WARN, FAIL, UNKNOWN }

internal data class Check(
    val title: String,
    val detail: String,
    val verdict: Verdict,
    val action: String? = null,
    val run: (() -> Unit)? = null,
    /** 第二个按钮（与主按钮并列时用）。 */
    val action2: String? = null,
    val run2: (() -> Unit)? = null,
)

/**
 * 运行诊断：把“它为什么没在干活”拆成几项能逐一核对的体检，
 * 再给一份可复制的运行快照和一个直达反馈的入口。
 */
@Composable
fun DiagnosticsScreen(live: LiveState, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = Aevum.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val toast = rememberToast()
    val snap = live.snapshot
    val now = live.now
    @Suppress("UNUSED_VARIABLE") val v = live.dataVersion
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    val checks = checks(
        context, snap, now,
        onCfgCheck = { live.backend.cfgCheck(); toast.show("已让 QQ 现做一次自检，结果几秒后更新") },
    )
    val passed = checks.count { it.verdict == Verdict.OK }
    val problems = checks.count { it.verdict == Verdict.FAIL || it.verdict == Verdict.WARN }
    val unknowns = checks.count { it.verdict == Verdict.UNKNOWN }

    /** 现场采集：发 diag → 等主存里的快照时间戳变新（最多 25 秒）。等不到要明说，不拿旧的冒充。 */
    fun collect(then: (fresh: Boolean) -> Unit) {
        val before = Store.prefs(context).getLong(Store.EXTRACT_DIAG_AT, 0L)
        busy = "正在向 QQ 要运行快照…"
        live.backend.requestDiag()
        scope.launch {
            val deadline = System.currentTimeMillis() + 25_000
            var fresh = false
            while (System.currentTimeMillis() < deadline) {
                delay(600)
                if (Store.prefs(context).getLong(Store.EXTRACT_DIAG_AT, 0L) > before) { fresh = true; break }
            }
            busy = null
            then(fresh)
        }
    }

    fun submit() {
        val text = Diagnostics.collect(context)
        Diagnostics.write(context, text)
        val body = Diagnostics.prefillBody(text)
        copy(context, Diagnostics.pasteBlock(text, body.length))
        val url = Diagnostics.issueUrl("【反馈】（写上一句现象）", body)
        dialog = DialogSpec(
            title = "完整日志已复制",
            message = "到 issue 页面最下方长按粘贴即可。\n不粘也能提交：正文里已附上最近的日志与运行状态。\n\nQQ 号、宠物 ID、好友昵称已自动打码。",
            confirm = "打开 GitHub",
            onConfirm = { open(context, url) },
            dismiss = "先不打开",
        )
    }

    Box(Modifier.fillMaxSize()) {
        Page(title = "运行诊断", onBack = onBack) {
            item(key = "summary") {
                AevumCard(modifier = Modifier.padding(top = Space.lg), contentPadding = PaddingValues(Space.xl)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            ProgressRing(passed.toFloat() / checks.size.coerceAtLeast(1), if (problems == 0 && unknowns == 0) c.success else c.warning, Modifier.size(64.dp), stroke = 6.dp)
                            Text("$passed/${checks.size}", style = AevumType.label, color = c.ink)
                        }
                        Spacer(Modifier.width(Space.lg))
                        Column(Modifier.weight(1f)) {
                            // 判断不了（UNKNOWN）既不是通过也不是问题：以前不计进任何一边，
                            // 6/10 通过、4 项判断不了时也显示“全部通过”，是错的
                            Text(
                                when {
                                    problems > 0 -> "有 $problems 项需要留意"
                                    unknowns > 0 -> "有 $unknowns 项暂时判断不了"
                                    else -> "一切正常"
                                },
                                style = AevumType.title, color = c.ink,
                            )
                            Text(
                                when {
                                    problems > 0 && unknowns > 0 -> "另有 $unknowns 项暂时判断不了。逐项写在下面。"
                                    problems > 0 -> "逐项原因和处理办法写在下面。"
                                    unknowns > 0 -> "缺的数据有了才能判断。逐项写在下面。"
                                    else -> "全部 ${checks.size} 项通过。"
                                },
                                style = AevumType.caption, color = c.inkMuted,
                            )
                        }
                    }
                }
            }
            item(key = "checks_label") { SectionLabel("体检") }
            item(key = "checks") {
                AevumCard(contentPadding = PaddingValues(0.dp)) {
                    checks.forEachIndexed { i, ch ->
                        CheckRow(ch)
                        if (i != checks.lastIndex) Hairline(inset = 56.dp)
                    }
                }
            }
            item(key = "snapshot_label") { SectionLabel("运行快照") }
            item(key = "snapshot") {
                val age = Diagnostics.snapshotAge(context)
                AevumCard(contentPadding = PaddingValues(Space.lg)) {
                    Text(
                        if (age < 0) "还没有来自 QQ 的快照" else "QQ 的快照：${Copy.ago(age)}",
                        style = AevumType.bodyStrong, color = c.ink,
                    )
                    Text(
                        "包含版本、设置、今天的进度与最近的日志。只在本机，打码后才会离开这台手机。",
                        style = AevumType.caption, color = c.inkMuted,
                    )
                    Spacer(Modifier.height(Space.md))
                    AevumButton("采集最新快照", glyph = Glyphs.Refresh, tone = ButtonTone.SECONDARY, onClick = {
                        collect { fresh ->
                            report = Diagnostics.collect(context)
                            expanded = true
                            if (fresh) { haptics.confirm(); toast.show("已采集") }
                            else dialog = DialogSpec(
                                "没等到 QQ 的回应",
                                "QQ 里的模块没有回话（可能刚被关掉或正在重启）。下面显示的是本机已有的信息。", "好",
                            )
                        }
                    })
                    Spacer(Modifier.height(Space.sm))
                    AevumButton(if (expanded) "收起全文" else "查看全文", tone = ButtonTone.GHOST, onClick = {
                        if (report == null) report = Diagnostics.collect(context)
                        expanded = !expanded
                    })
                    AnimatedVisibility(visible = expanded && report != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            Spacer(Modifier.height(Space.sm))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(c.sunken)
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                                    .padding(Space.md),
                            ) {
                                // 长按可选中任意一段复制（v2.1.1），不只“复制全文”
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(report.orEmpty(), style = AevumType.mono, color = c.ink, softWrap = false)
                                }
                            }
                            Spacer(Modifier.height(Space.sm))
                            AevumButton("复制全文", glyph = Glyphs.Copy, tone = ButtonTone.SECONDARY, onClick = {
                                copy(context, report.orEmpty()); haptics.confirm(); toast.show("已复制")
                            })
                        }
                    }
                }
            }
            item(key = "feedback_label") { SectionLabel("反馈") }
            item(key = "feedback") {
                AevumCard(contentPadding = PaddingValues(Space.lg)) {
                    Text("遇到问题？", style = AevumType.bodyStrong, color = c.ink)
                    Text(
                        "会打开 GitHub 新建 issue，并先附上一份现采的运行快照。不会替你上传任何东西，提交与否由你决定。",
                        style = AevumType.caption, color = c.inkMuted,
                    )
                    Spacer(Modifier.height(Space.md))
                    AevumButton("带快照反馈", glyph = Glyphs.Chat, onClick = { collect { submit() } })
                    Spacer(Modifier.height(Space.xs))
                    AevumButton("不带快照，直接反馈", tone = ButtonTone.GHOST, onClick = { open(context, Backend.ISSUE_URL) })
                    if (Backend.GROUP_URL.isNotEmpty()) {
                        Spacer(Modifier.height(Space.xs))
                        AevumButton("去 Telegram 群里问", glyph = Glyphs.Users, tone = ButtonTone.GHOST, onClick = { open(context, Backend.GROUP_URL) })
                    }
                }
            }
        }
        ToastHost(toast)
        AevumDialogHost(dialog) { dialog = null }
        BusyDialog(busy)
    }
}

@Composable
private fun CheckRow(ch: Check) {
    val c = Aevum.colors
    val (glyph, tint) = when (ch.verdict) {
        Verdict.OK -> Glyphs.Check to c.success
        Verdict.WARN -> Glyphs.Warning to c.warning
        Verdict.FAIL -> Glyphs.Close to c.danger
        Verdict.UNKNOWN -> Glyphs.Info to c.inkMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics(mergeDescendants = true) {
                contentDescription = ch.title + "，" + when (ch.verdict) {
                    Verdict.OK -> "正常"; Verdict.WARN -> "需要留意"; Verdict.FAIL -> "有问题"; Verdict.UNKNOWN -> "无法自动判断"
                } + "，" + ch.detail
            },
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(glyph, tint)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(ch.title, style = AevumType.bodyStrong, color = c.ink)
            Text(ch.detail, style = AevumType.caption, color = c.inkMuted)
            val actions = listOfNotNull(
                if (ch.action != null && ch.run != null) ch.action to ch.run else null,
                if (ch.action2 != null && ch.run2 != null) ch.action2 to ch.run2 else null,
            )
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(Space.sm))
                Row {
                    actions.forEachIndexed { i, (label, fn) ->
                        if (i > 0) Spacer(Modifier.width(Space.sm))
                        AevumButton(label, onClick = fn, tone = ButtonTone.SECONDARY, fill = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(glyph: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

private fun checks(
    context: Context, s: PetSnapshot, now: Long, onCfgCheck: () -> Unit = {},
): List<Check> {
    val out = mutableListOf<Check>()
    out += Check(
        "许可协议",
        if (s.agreed) "已同意当前版本（指纹 ${EulaTerms.digest(context).take(8)}）" else "当前版本尚未同意",
        if (s.agreed) Verdict.OK else Verdict.FAIL,
    )
    out += when (s.moduleEnabled) {
        true -> Check("LSPosed 模块", "已启用", Verdict.OK)
        false -> Check("LSPosed 模块", "未启用。在 LSPosed 里启用 Autoqqpet、勾选 QQ，然后重启 QQ。", Verdict.FAIL)
        null -> Check("LSPosed 模块", "正在确认…", Verdict.UNKNOWN)
    }
    val qq = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(Backend.QQ, 0).versionName
    }.getOrNull()
    out += if (qq != null) Check("手机 QQ", "已安装 · $qq", Verdict.OK)
    else Check("手机 QQ", "没有找到手机 QQ", Verdict.FAIL)

    if (s.autoOn) {
        val age = if (s.lastHeartbeatAt > 0) now - s.lastHeartbeatAt else -1L
        out += when {
            age < 0 -> Check("心跳", "打开本页后还没收到。心跳每分钟一次，稍等片刻。", Verdict.UNKNOWN)
            age < PetSnapshot.STALE_MS -> Check("心跳", "最近一次 ${Copy.ago(age)}", Verdict.OK)
            else -> Check("心跳", "已 ${Copy.ago(age)}没有心跳：QQ 可能被系统冻结或杀掉。", Verdict.FAIL)
        }
        out += when {
            s.nextWakeAt <= 0L -> Check("唤醒计划", "QQ 侧还没有排下一次唤醒", if (age in 0 until PetSnapshot.STALE_MS) Verdict.WARN else Verdict.UNKNOWN)
            s.nextWakeAt > now - PetSnapshot.STALE_MS -> Check("唤醒计划", "下一次 ${Copy.clock(s.nextWakeAt)}", Verdict.OK)
            else -> Check("唤醒计划", "计划时间已过去很久，循环可能卡住了；会自动尝试恢复。", Verdict.WARN)
        }
        // R10：QQ 侧磁盘设置 / 进程缓存 / 正在跑的配置三者是否一致（例：设置里开了召回，运行配置却是“召回=关”）
        val cc = s.full?.health?.cfgCheck
        val proc = s.full?.health?.process?.let { p -> " · 进程 $p" }.orEmpty()
        out += when {
            cc == null -> Check("设置是否生效", "还没有自检结果（QQ 每 5 分钟自检一次）", Verdict.UNKNOWN, action = "立即自检", run = onCfgCheck)
            cc.ok -> Check("设置是否生效", "磁盘上的设置、QQ 进程里的设置、正在跑的配置一致$proc（${Copy.ago(now - cc.at)}）", Verdict.OK, action = "再查一次", run = onCfgCheck)
            else -> Check("设置是否生效", "不一致：${cc.text}$proc。点“立即自检”会让 QQ 重建配置；仍不一致请强行停止 QQ 后重开。", Verdict.FAIL, action = "立即自检", run = onCfgCheck)
        }
        out += if (s.netBadAt > 0) Check("宠物请求", "已连续超时 ${Copy.ago(now - s.netBadAt).removeSuffix("前")}，通常会自行恢复", Verdict.WARN)
        else Check("宠物请求", "正常", if (age in 0 until PetSnapshot.STALE_MS) Verdict.OK else Verdict.UNKNOWN)
    } else {
        out += Check("托管", "已暂停，心跳与唤醒不做检查", Verdict.UNKNOWN)
    }

    val notif = NotificationManagerCompat.from(context).areNotificationsEnabled()
    out += Check(
        "通知", if (notif) "已允许" else "被关闭：看不到进度，也收不到需要你动手的提醒",
        if (notif) Verdict.OK else Verdict.WARN,
        action = if (notif) null else "去开启",
        run = if (notif) null else ({ openNotificationSettings(context) }),
    )
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    out += Check(
        "本应用的电池优化", if (ignoring) "不受限制" else "受限：后台保活可能被系统打断",
        if (ignoring) Verdict.OK else Verdict.WARN,
        action = if (ignoring) null else "去设置",
        run = if (ignoring) null else ({ open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }),
    )
    out += qqBatteryCheck(
        state = KeepAliveSetup.ignoringBattery(context, KeepAliveSetup.QQ),
        openBattery = { KeepAliveSetup.openBattery(context, KeepAliveSetup.QQ) },
    )
    return out
}

/**
 * 「QQ 的后台限制」这一项：`PowerManager.isIgnoringBatteryOptimizations(包名)` 对任意包都能查
 * （设置页保活向导一直在用同一个查询），所以这里直接真查 QQ 的状态：
 * true = 不受限制（OK）；false = 受限（WARN）；null = 查询本身失败、拿不到值（UNKNOWN）。
 * 没有手动确认：查到什么就是什么。
 */
internal fun qqBatteryCheck(state: Boolean?, openBattery: () -> Unit): Check = when (state) {
    true -> Check(
        "QQ 的后台限制",
        "不受限制",
        Verdict.OK,
    )
    false -> Check(
        "QQ 的后台限制",
        "受电池优化限制：QQ 在后台可能被系统杀掉。点「去设置」把 QQ 的电池改为“不受限制”。",
        Verdict.WARN,
        action = "去设置",
        run = openBattery,
    )
    null -> Check(
        "QQ 的后台限制",
        "查不到 QQ 的电池状态。可以去系统设置里确认 QQ 的电池是“不受限制”。",
        Verdict.UNKNOWN,
        action = "去设置",
        run = openBattery,
    )
}

private fun openNotificationSettings(context: Context) {
    val i = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
    open(context, i)
}

internal fun open(context: Context, url: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

internal fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun copy(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("autoqqpet-diag", text))
    }
}
