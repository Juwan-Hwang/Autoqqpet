package io.github.juwanhwang.autoqqpet.ui.screens

import io.github.juwanhwang.autoqqpet.ui.theme.animateFloatIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.BuildConfig
import io.github.juwanhwang.autoqqpet.ui.AlertActions
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.SettingsAnchor
import io.github.juwanhwang.autoqqpet.ui.Route
import io.github.juwanhwang.autoqqpet.ui.components.AevumButton
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.AevumDialogHost
import io.github.juwanhwang.autoqqpet.ui.components.BigToggle
import io.github.juwanhwang.autoqqpet.ui.components.ButtonTone
import io.github.juwanhwang.autoqqpet.ui.components.DialogSpec
import io.github.juwanhwang.autoqqpet.ui.components.FadingText
import io.github.juwanhwang.autoqqpet.ui.components.GlyphBadge
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.IconButtonCircle
import io.github.juwanhwang.autoqqpet.ui.components.PetGlyph
import io.github.juwanhwang.autoqqpet.ui.components.PetMood
import io.github.juwanhwang.autoqqpet.ui.components.Pill
import io.github.juwanhwang.autoqqpet.ui.components.ProgressRing
import io.github.juwanhwang.autoqqpet.ui.components.PulseDot
import io.github.juwanhwang.autoqqpet.ui.components.RollingNumber
import io.github.juwanhwang.autoqqpet.ui.components.SectionLabel
import io.github.juwanhwang.autoqqpet.ui.components.SegmentOption
import io.github.juwanhwang.autoqqpet.ui.components.SegmentedPicker
import io.github.juwanhwang.autoqqpet.ui.components.SkeletonLine
import io.github.juwanhwang.autoqqpet.ui.components.ToastHost
import io.github.juwanhwang.autoqqpet.ui.components.rememberToast
import io.github.juwanhwang.autoqqpet.ui.components.shimmer
import io.github.juwanhwang.autoqqpet.ui.data.HeartbeatFields
import io.github.juwanhwang.autoqqpet.ui.data.LedgerEntry
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.data.Phase
import io.github.juwanhwang.autoqqpet.ui.data.PetSnapshot
import io.github.juwanhwang.autoqqpet.ui.data.TaskKind
import io.github.juwanhwang.autoqqpet.ui.data.employedSubtitle
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumColors
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
import io.github.juwanhwang.autoqqpet.ui.theme.snappySpring
import kotlin.math.roundToLong

/**
 * 主页 =「此刻」。只回答三件事：它现在在干什么、托管开没开、按什么方式干。
 * 其余都在设置里。信息自上而下按“需要你知道的程度”排：舞台 → 需要处理的提醒 → 开关 → 方式 → 数值 → 今天。
 */
@Composable
fun HomeScreen(live: LiveState, onOpen: (Route) -> Unit) {
    val snap = live.snapshot
    val now = live.now
    val c = Aevum.colors
    val backend = live.backend
    val list = rememberLazyListState()
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var dismissBump by remember { mutableIntStateOf(0) }
    val toast = rememberToast()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reduced = reducedMotion

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        LazyColumn(
            state = list,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Space.maxContent)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = top + Space.sm, bottom = bottom + Space.section),
        ) {
            item(key = "header") {
                Staggered(0) { Header(snap, onSettings = { onOpen(Route.SETTINGS) }) }
            }
            item(key = "stage") {
                // 滚动视差：舞台以 0.4 倍速上移并轻微淡出
                Box(
                    Modifier.graphicsLayer {
                        if (!reduced && list.firstVisibleItemIndex <= 1) {
                            val off = if (list.firstVisibleItemIndex == 1) list.firstVisibleItemScrollOffset.toFloat() else 0f
                            translationY = off * 0.4f
                            alpha = 1f - (off / 1400f).coerceIn(0f, 0.5f)
                        }
                    },
                ) {
                    Staggered(1) { Stage(snap, now, onDiagnose = { onOpen(Route.DIAGNOSTICS) }) }
                }
            }
            item(key = "alert") {
                val dismissed = remember(live.dataVersion, dismissBump) { backend.dismissedAlerts() }
                AlertStrip(snap, dismissed, onAct = { b, id, incident ->
                    when (b.act) {
                        AlertActions.Act.MODULE_HELP -> dialog = DialogSpec(
                            title = "在 LSPosed 里启用",
                            message = "1. 打开 LSPosed（或 Vector），启用 Autoqqpet\n2. 作用域勾选 QQ\n3. 彻底关掉 QQ 再打开\n\n回到这里，状态会自动更新。",
                            confirm = "知道了",
                        )
                        AlertActions.Act.DISMISS -> { if (id != null) { backend.dismissAlert(id, incident); dismissBump++ } }
                        AlertActions.Act.OPEN_QQ -> if (!backend.openQQ()) toast.show("打不开 QQ：请手动打开 QQ → 宠物 去领取")
                        AlertActions.Act.RECALL -> { backend.recallNow(); toast.show("已让 QQ 立即召回，结果几秒后更新") }
                        AlertActions.Act.SETTINGS -> { SettingsAnchor.pending = b.group; onOpen(Route.SETTINGS) }
                        AlertActions.Act.SPARK -> onOpen(Route.SPARK)
                        AlertActions.Act.DIAGNOSE -> onOpen(Route.DIAGNOSTICS)
                    }
                })
            }
            item(key = "toggle") {
                Staggered(2) {
                    Column {
                        Spacer(Modifier.height(Space.lg))
                        BigToggle(
                            checked = snap.autoOn,
                            onCheckedChange = { on -> if (on) backend.startAuto() else backend.stopAuto(); live.refresh() },
                            labelOn = "托管中",
                            labelOff = "开启托管",
                            hintOn = "通知栏可随时查看进度",
                            hintOff = "它会按下面的方式自己干活",
                        )
                    }
                }
            }
            item(key = "mode") {
                Staggered(3) {
                    Column {
                        SectionLabel("怎么干活")
                        SegmentedPicker(
                            options = Copy.homeModes.map { SegmentOption(it.key, it.label) },
                            selectedKey = snap.mode,
                            onSelect = { backend.setMode(it); live.refresh() },
                            contentDescription = "干活方式",
                        )
                        Spacer(Modifier.height(Space.sm))
                        FadingText(
                            Copy.modes.firstOrNull { it.key == snap.mode }?.detail.orEmpty(),
                            AevumType.caption, c.inkMuted,
                            modifier = Modifier.padding(horizontal = Space.xs),
                        )
                        AnimatedVisibility(
                            visible = snap.mode == "custom",
                            enter = if (reduced) fadeIn(androidx.compose.animation.core.snap()) else expandVertically() + fadeIn(),
                            exit = if (reduced) fadeOut(androidx.compose.animation.core.snap()) else shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                Spacer(Modifier.height(Space.sm))
                                AevumButton("编辑排班", onClick = { onOpen(Route.SCHEDULE) }, tone = ButtonTone.SECONDARY, glyph = Glyphs.Calendar)
                            }
                        }
                    }
                }
            }
            item(key = "focus") {
                Staggered(4) {
                    // 学习优先 → “练什么”；打工优先 → 同一位置换成“去哪打工”；只冒险/自定义 → 隐藏
                    Column {
                        AnimatedVisibility(
                            visible = snap.mode == "mix",
                            enter = if (reduced) fadeIn(androidx.compose.animation.core.snap()) else expandVertically() + fadeIn(),
                            exit = if (reduced) fadeOut(androidx.compose.animation.core.snap()) else shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                SectionLabel("练什么")
                                SegmentedPicker(
                                    options = Copy.attrs.map { SegmentOption(it, it) },
                                    selectedKey = snap.attr,
                                    onSelect = { backend.setAttr(it); live.refresh() },
                                    contentDescription = "要练的属性",
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = snap.mode == "work",
                            enter = if (reduced) fadeIn(androidx.compose.animation.core.snap()) else expandVertically() + fadeIn(),
                            exit = if (reduced) fadeOut(androidx.compose.animation.core.snap()) else shrinkVertically() + fadeOut(),
                        ) {
                            CareerPicker(backend, live.dataVersion) { live.refresh() }
                        }
                        AnimatedVisibility(
                            visible = snap.mode == "auto",
                            enter = if (reduced) fadeIn(androidx.compose.animation.core.snap()) else expandVertically() + fadeIn(),
                            exit = if (reduced) fadeOut(androidx.compose.animation.core.snap()) else shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                SectionLabel("优先什么")
                                SegmentedPicker(
                                    options = Copy.autoGoals.map { SegmentOption(it.key, it.label) },
                                    selectedKey = snap.autoGoal,
                                    onSelect = { backend.setAutoGoal(it); live.refresh() },
                                    contentDescription = "最优调度的目标",
                                )
                                Spacer(Modifier.height(Space.sm))
                                FadingText(
                                    Copy.autoGoals.firstOrNull { it.key == snap.autoGoal }?.detail.orEmpty(),
                                    AevumType.caption, c.inkMuted,
                                    modifier = Modifier.padding(horizontal = Space.xs),
                                )
                                AnimatedVisibility(visible = snap.autoGoal == "attr") {
                                    Column {
                                        Spacer(Modifier.height(Space.sm))
                                        SectionLabel("练什么")
                                        SegmentedPicker(
                                            options = Copy.attrs.map { SegmentOption(it, it) },
                                            selectedKey = snap.attr,
                                            onSelect = { backend.setAttr(it); live.refresh() },
                                            contentDescription = "要练的属性",
                                        )
                                    }
                                }
                                AnimatedVisibility(visible = snap.autoGoal == "points") {
                                    Column {
                                        Spacer(Modifier.height(Space.sm))
                                        CareerPicker(backend, live.dataVersion) { live.refresh() }
                                    }
                                }
                            }
                        }
                        AnimatedVisibility(
                            visible = snap.mode == "only",
                            enter = if (reduced) fadeIn(androidx.compose.animation.core.snap()) else expandVertically() + fadeIn(),
                            exit = if (reduced) fadeOut(androidx.compose.animation.core.snap()) else shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                SectionLabel("刷哪个")
                                val catalog = androidx.compose.runtime.remember(live.dataVersion) { backend.adventureCatalog() }
                                val advOptions = listOf(SegmentOption("", "自动")) +
                                    catalog.map { SegmentOption(it.name, "${it.name}·${it.duration.ifBlank { "${it.minutes}分钟" }}") } +
                                    (if (snap.advOnlyName.isNotBlank() && catalog.none { it.name == snap.advOnlyName })
                                        listOf(SegmentOption(snap.advOnlyName, snap.advOnlyName)) else emptyList())
                                SegmentedPicker(
                                    options = advOptions,
                                    selectedKey = snap.advOnlyName,
                                    onSelect = { backend.setAdvOnlyName(it); live.refresh() },
                                    contentDescription = "只冒险时固定刷哪个冒险",
                                )
                            }
                        }
                    }
                }
            }
            item(key = "metrics") {
                Staggered(5) { Metrics(snap, now) }
            }
            snap.full?.pet?.takeIf { it.attrs != null || it.exp != null }?.let { p ->
                item(key = "attrs") {
                    Staggered(6) { AttrsCard(p.attrs, p.exp, snap.attr) }
                }
            }
            snap.full?.today?.let { e ->
                item(key = "earnings") {
                    Staggered(6) { Column { Spacer(Modifier.height(Space.sm)); EarningsCard(e, earningsHero(snap.mode, snap.autoGoal), snap.attr) } }
                }
            }
            item(key = "today") {
                Staggered(7) { Today(snap) }
            }
            item(key = "timeline") {
                Staggered(8) { Timeline(snap, now) }
            }
            snap.full?.yesterday?.let { r ->
                item(key = "yesterday") {
                    Staggered(9) { YesterdayCard(r) }
                }
            }
            item(key = "footer") {
                Text(
                    "Autoqqpet · v${BuildConfig.VERSION_NAME}",
                    style = AevumType.caption, color = c.inkMuted, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .testTag("footer")
                        .fillMaxWidth()
                        .padding(top = Space.section),
                )
            }
        }
        ToastHost(toast)
        AevumDialogHost(dialog) { dialog = null }
    }
}

// ── 顶部 ─────────────────────────────────────────

@Composable
private fun Header(snap: PetSnapshot, onSettings: () -> Unit) {
    val c = Aevum.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("AUTOQQPET", style = AevumType.micro, color = c.inkMuted)
            Spacer(Modifier.height(2.dp))
            val name = HeartbeatFields.petName(snap.heartbeat)
            Text(name ?: greeting(), style = AevumType.title, color = c.ink, modifier = Modifier.semantics { heading() })
        }
        IconButtonCircle(Glyphs.Sliders, "设置", onSettings)
    }
}

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..18 -> "下午好"
        else -> "晚上好"
    }
}

// ── 舞台 ─────────────────────────────────────────

private data class StageLook(
    val tint: Color,
    val mood: PetMood,
    val title: String,
    val subtitle: String,
    val progress: Float?,
    val showRing: Boolean,
    val countdown: String?,
    val countdownSpoken: String?,
    /** 服务器给的此刻叙事，单独一行斜体灰字。 */
    val narrative: String? = null,
    /** 叙事暂时没有（或洗完只剩垃圾）时的占位，灰字、不加引号。 */
    val narrativePlaceholder: String? = null,
)

private fun look(s: PetSnapshot, now: Long, c: AevumColors): StageLook {
    val task = s.task
    return when (s.phase) {
        Phase.CHECKING -> StageLook(c.rest, PetMood.AWAKE, "正在检查", "确认模块是否已在 LSPosed 启用", null, true, null, null)
        Phase.MODULE_OFF -> StageLook(c.danger, PetMood.WORRIED, "模块还没启用", "在 LSPosed 里启用 Autoqqpet 并勾选 QQ", 0f, false, null, null)
        Phase.PAUSED -> StageLook(c.rest, PetMood.SLEEP, "托管已暂停", "已暂停，不会安排任务", 0f, false, null, null)
        Phase.CONNECTING -> StageLook(c.accent, PetMood.AWAKE, "正在连接 QQ", "等它的第一次心跳，通常不超过一分钟", null, true, null, null)
        Phase.LOST -> StageLook(
            c.warning, PetMood.WORRIED, "QQ 没有回应",
            "已 ${Copy.ago(now - s.lastHeartbeatAt).removeSuffix("前")}没有心跳，正在静默尝试接回", 0f, false, null, null,
        )
        Phase.OFFLINE -> StageLook(
            c.warning, PetMood.WORRIED, "QQ 连不上服务器",
            "已持续 ${Copy.ago(now - s.netBadAt).removeSuffix("前")}，恢复后自动接着干", null, true, null, null,
        )
        Phase.CARE -> StageLook(c.care, PetMood.BUSY, "正在照顾", "喂食、洗澡，吃饱洗净再干活", null, true, null, null)
        Phase.EMPLOYED -> StageLook(c.accent, PetMood.BUSY, "被好友雇去打工了", employedSubtitle(s.full?.config), null, true, null, null)
        Phase.OUTING -> StageLook(c.rest, PetMood.BUSY, "宠物可能在外面", "最近一次状态显示在忙；等 QQ 下一帧快照确认", null, true, null, null)
        Phase.TASK -> {
            val t = task!!
            // v2.1.1：进度环、倒计时、叙事跟主题色走（用户要求“换主题时这些也跟着换”）；
            // 任务种类由标题/图标/今天卡片里的分类色区分，照顾/休息仍用语义色
            val tint = when (t.kind) {
                TaskKind.CARE -> c.care
                TaskKind.REST -> c.rest
                else -> c.accent
            }
            val remain = t.remainingMs(now)
            // 外部任务（用户在 QQ 里手动开的/被雇的）：名字来自服务器详情（0x9874），明确标“外出中”，不冒充托管任务
            val external = t.mine == false
            val sub = if (external) "${Copy.kindLabel(t.kind)}${t.name?.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""} · 不是 Autoqqpet 开的，结束后接手"
                else (t.name?.takeIf { it.isNotBlank() } ?: Copy.kindLabel(t.kind))
            StageLook(
                tint, PetMood.BUSY, if (external) "宠物外出中" else Copy.kindDoing(t.kind),
                sub,
                t.progress(now), true,
                remain?.let { Copy.countdown(it) }, remain?.let { Copy.countdownSpoken(it) },
                narrative = t.narrative?.takeIf { io.github.juwanhwang.autoqqpet.pet.parse.RichText.isNarrative(it) },
                narrativePlaceholder = if (t.mine != false) "叙事稍后更新…" else null,
            )
        }
        Phase.IDLE -> {
            val wake = s.nextWakeAt
            val sub = if (wake > now) "下一次 ${Copy.clock(wake)} 醒来" else "正在安排下一件事"
            StageLook(c.accent, PetMood.AWAKE, "稍作休息", sub, null, wake <= now, if (wake > now) Copy.countdown(wake - now) else null, null)
        }
    }
}

@Composable
private fun Stage(snap: PetSnapshot, now: Long, onDiagnose: () -> Unit) {
    val c = Aevum.colors
    val l = look(snap, now, c)
    val tint by animateColorAsState(l.tint, snappySpring(), label = "stage_tint")
    AevumCard(
        modifier = Modifier.fillMaxWidth().padding(top = Space.md),
        shape = RoundedCornerShape(Radius.xl),
        contentPadding = PaddingValues(Space.xl),
    ) {
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            Glow(tint, Modifier.size(280.dp), strength = if (c.isDark) 0.28f else 0.20f)
            val ringA by animateFloatIn(if (l.showRing || l.progress != null) 1f else 0f, label = "ring_a")
            Box(
                Modifier
                    .size(220.dp)
                    .graphicsLayer { alpha = ringA }
                    .semantics {
                        if (l.progress != null) progressBarRangeInfo = ProgressBarRangeInfo(l.progress, 0f..1f)
                        contentDescription = l.countdownSpoken ?: l.title
                    },
            ) {
                ProgressRing(l.progress, tint, Modifier.fillMaxSize(), stroke = 12.dp)
            }
            PetGlyph(
                mood = l.mood,
                body = if (c.isDark) Color(0xFFF4F2FA) else Color.White,
                face = Color(0xFF1C1B22),
                leaf = c.adventure,
                modifier = Modifier.size(132.dp),
            )
        }
        Spacer(Modifier.height(Space.md))
        Column(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FadingText(l.title, AevumType.hero, c.ink)
            Spacer(Modifier.height(Space.xs))
            FadingText(l.subtitle, AevumType.body, c.inkMuted, maxLines = 2)
            val story = l.narrative
            val placeholder = story == null && l.narrativePlaceholder != null
            AnimatedVisibility(visible = story != null || placeholder, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    if (story != null) "“$story”" else l.narrativePlaceholder.orEmpty(),
                    style = AevumType.caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = if (story != null) tint else c.inkFaint,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(top = Space.sm, start = Space.lg, end = Space.lg),
                )
            }
            if (l.countdown != null) {
                Spacer(Modifier.height(Space.md))
                Text(
                    l.countdown,
                    style = AevumType.numeralXL,
                    color = tint,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = l.countdownSpoken ?: l.countdown },
                )
            }
        }
        Spacer(Modifier.height(Space.lg))
        Pulse(snap, now, onDiagnose)
    }
}

/** 心跳行：点亮的点 + “心跳 12 秒前 · 下次 21:40”。没有数据时是骨架。 */
@Composable
private fun Pulse(snap: PetSnapshot, now: Long, onDiagnose: () -> Unit) {
    val c = Aevum.colors
    val hbAge = if (snap.lastHeartbeatAt > 0) now - snap.lastHeartbeatAt else -1L
    val healthy = hbAge in 0 until PetSnapshot.STALE_MS && snap.netBadAt == 0L
    val color = when {
        !snap.autoOn -> c.rest
        hbAge < 0 -> c.inkFaint
        healthy -> c.success
        else -> c.warning
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(c.sunken)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color, Modifier.size(8.dp), active = snap.autoOn && healthy)
        Spacer(Modifier.width(Space.sm))
        if (snap.autoOn && hbAge < 0) {
            SkeletonLine(width = 140.dp, height = 12.dp)
            Spacer(Modifier.weight(1f))
        } else {
            val text = buildString {
                append(if (hbAge >= 0) "心跳 ${Copy.ago(hbAge)}" else "未收到心跳")
                if (snap.autoOn && snap.nextWakeAt > now) append(" · 下次 ${Copy.clock(snap.nextWakeAt)}")
            }
            Text(text, style = AevumType.label, color = c.inkMuted, modifier = Modifier.weight(1f))
        }
        Text(
            "诊断",
            style = AevumType.label,
            color = c.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .linkClick("打开运行诊断", onDiagnose)
                .padding(horizontal = Space.xs),
        )
    }
}

/** 小号文字链接的可点区域（最小 36dp 高，带水波，读屏读作按钮）。 */
@Composable
private fun Modifier.linkClick(label: String, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            role = Role.Button,
            onClickLabel = label,
            onClick = onClick,
        )
        .padding(vertical = 8.dp)
}

// ── 去哪打工（打工优先时显示）────────────────────

/** 内联职业选择：复用设置登记表的 careerOptions/careerSummary/careerNote；写入走 backend.setCareer（JobCatalog.KEY_CAREER）。 */
@Composable
private fun CareerPicker(backend: io.github.juwanhwang.autoqqpet.ui.data.Backend, version: Int, onChanged: () -> Unit) {
    val c = Aevum.colors
    val haptics = io.github.juwanhwang.autoqqpet.ui.theme.rememberHaptics()
    var bump by remember { mutableIntStateOf(0) }
    val key = version + bump
    val options = remember(key) { io.github.juwanhwang.autoqqpet.ui.settings.SettingsRegistry.careerOptions(backend.prefs) }
    val pinned = remember(key) { backend.prefs.getLong(io.github.juwanhwang.autoqqpet.pet.JobCatalog.KEY_CAREER, 0L).toString() }
    val note = remember(key) { io.github.juwanhwang.autoqqpet.ui.settings.SettingsRegistry.careerNote(backend.prefs) }
    Column {
        SectionLabel("去哪打工", trailing = {
            Text(io.github.juwanhwang.autoqqpet.ui.settings.SettingsRegistry.careerSummary(backend.prefs), style = AevumType.label, color = c.inkMuted, maxLines = 1)
        })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            options.forEach { o ->
                val selected = o.value == pinned
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(if (selected) c.accentSoft else c.sunken)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            haptics.segment(); backend.setCareer(o.value.toLongOrNull() ?: 0L); bump++; onChanged()
                        })
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(o.label, style = AevumType.label, color = if (selected) c.accent else c.ink, maxLines = 1) }
            }
        }
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(Space.xs))
            Text(note, style = AevumType.caption, color = c.inkMuted, modifier = Modifier.padding(horizontal = Space.xs))
        }
    }
}

// ── 提醒条 ───────────────────────────────────────

/**
 * 一条提醒的样子与去向。[alertId] 非空 = 可划掉（后端提醒用 id + 事件键；旧文本提醒为 "legacy"）；
 * 模块未启用/失联/请求回不来是实时状态，不是提醒，不给划掉。
 */
private data class AlertLook(
    val glyph: ImageVector,
    val tint: Color,
    val title: String,
    val body: String,
    val buttons: List<AlertActions.Btn>,
    val alertId: String? = null,
    val incident: String = "",
)

private fun alertTitle(id: String): String = when (id) {
    "recall" -> "该召回了"
    "care_blocked" -> "照顾被服务器拒了"
    "fee" -> "学费不够"
    "idle" -> "现在没事可做"
    "pay_tired" -> "宠物今天累了"
    "mail" -> "信箱有东西要手动领"
    "spark" -> "火花快清零了"
    "spark_tonight" -> "今晚火花会熄"
    else -> "需要你看一眼"
}

private fun alertGlyph(id: String): ImageVector = when (id) {
    "care_blocked", "fee", "idle" -> Glyphs.Warning
    "pay_tired" -> Glyphs.Moon
    "spark", "spark_tonight" -> Glyphs.Flame
    "recall" -> Glyphs.Users
    else -> Glyphs.Bell
}

private fun pickAlert(snap: PetSnapshot, dismissed: Set<String>, c: AevumColors): AlertLook? {
    if (snap.phase == Phase.MODULE_OFF) return AlertLook(
        Glyphs.Warning, c.danger, "模块未启用", "QQ 里没有加载 Autoqqpet，托管不会生效。",
        listOf(AlertActions.Btn("怎么启用", AlertActions.Act.MODULE_HELP)),
    )
    val full = snap.full
    val top = full?.alerts?.firstOrNull { AlertActions.key(it.id, it.incident) !in dismissed }
    if (top != null) return AlertLook(
        alertGlyph(top.id),
        if (top.level == io.github.juwanhwang.autoqqpet.ui.data.FullSnapshot.Level.ACTION) c.care else c.accent,
        alertTitle(top.id), top.text, AlertActions.forId(top.id), top.id, top.incident,
    )
    // 旧后端（没有完整快照）才看那一行 alert 文本；新后端的同一条已经在上面的列表里
    if (full == null && snap.alert.isNotBlank() && AlertActions.legacyKey(snap.alert) !in dismissed) return AlertLook(
        Glyphs.Bell, c.care,
        when {
            snap.alert.contains("雇佣") -> "疑似被好友雇佣"
            snap.alert.contains("疲劳") -> "宠物今天累了"
            else -> "需要你看一眼"
        },
        snap.alert, AlertActions.forLegacy(snap.alert), "legacy", AlertActions.legacyKey(snap.alert),
    )
    return when (snap.phase) {
        Phase.LOST -> AlertLook(
            Glyphs.SignalOff, c.warning, "QQ 失联", "常驻失联多半是系统省电冻结了 QQ：把 QQ 的电池设为“不受限制”。",
            listOf(AlertActions.Btn("去诊断", AlertActions.Act.DIAGNOSE)),
        )
        Phase.OFFLINE -> AlertLook(
            Glyphs.SignalOff, c.warning, "请求回不来", "QQ 进程在，但宠物请求连续超时。通常会自己恢复。",
            listOf(AlertActions.Btn("去诊断", AlertActions.Act.DIAGNOSE)),
        )
        else -> null
    }
}

@Composable
private fun AlertStrip(
    snap: PetSnapshot,
    dismissed: Set<String>,
    onAct: (AlertActions.Btn, String?, String) -> Unit,
) {
    val c = Aevum.colors
    val a = pickAlert(snap, dismissed, c)
    AnimatedVisibility(visible = a != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        if (a != null) {
            Row(
                Modifier
                    .testTag("alert_strip")
                    .fillMaxWidth()
                    .padding(top = Space.md)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(a.tint.copy(alpha = 0.10f))
                    .border(BorderStroke(1.dp, a.tint.copy(alpha = 0.25f)), RoundedCornerShape(Radius.lg))
                    .padding(start = Space.lg, top = Space.lg, bottom = Space.md, end = Space.sm)
                    .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.Top,
            ) {
                Icon(a.glyph, null, tint = a.tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = AevumType.bodyStrong, color = c.ink)
                    Spacer(Modifier.height(2.dp))
                    Text(a.body, style = AevumType.caption, color = c.inkMuted)
                    Spacer(Modifier.height(Space.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalAlignment = Alignment.CenterVertically) {
                        a.buttons.forEachIndexed { i, b ->
                            val primary = i == 0
                            Text(
                                if (primary && b.act != AlertActions.Act.DISMISS) b.label + " ›" else b.label,
                                style = AevumType.label,
                                color = if (primary) a.tint else c.inkMuted,
                                modifier = Modifier.linkClick(b.label) { onAct(b, a.alertId, a.incident) },
                            )
                        }
                    }
                }
                if (a.alertId != null && !AlertActions.hasDismiss(a.buttons)) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button, onClickLabel = "划掉这条提醒") {
                                onAct(AlertActions.Btn("划掉", AlertActions.Act.DISMISS), a.alertId, a.incident)
                            }
                            .semantics { contentDescription = "划掉这条提醒" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Glyphs.Close, null, tint = c.inkMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ── 数值 ─────────────────────────────────────────

@Composable
private fun Metrics(snap: PetSnapshot, now: Long) {
    val c = Aevum.colors
    val metrics = HeartbeatFields.metrics
    val any = metrics.any { HeartbeatFields.number(snap.heartbeat, it) != null }
    Column {
        SectionLabel("宠物", trailing = {
            if (any && snap.heartbeatAt > 0) Text(Copy.ago(now - snap.heartbeatAt), style = AevumType.label, color = c.inkMuted)
        })
        // 两列网格（LazyColumn 里不嵌套懒网格，直接按行排）
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                row.forEach { m ->
                    MetricTile(m, HeartbeatFields.number(snap.heartbeat, m), waiting = !any, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (!any) {
            Text(
                "数值随 QQ 的心跳上报，收到后显示在这里。",
                style = AevumType.caption, color = c.inkMuted,
                modifier = Modifier.padding(horizontal = Space.xs),
            )
        }
    }
}

@Composable
private fun MetricTile(m: HeartbeatFields.Metric, value: Double?, waiting: Boolean, modifier: Modifier) {
    val c = Aevum.colors
    val tint = when (m.key) {
        "hunger" -> c.work
        "clean" -> c.adventure
        "feel" -> c.care
        "gold" -> c.warning
        else -> c.accent
    }
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(c.surface)
            .border(BorderStroke(1.dp, c.line), RoundedCornerShape(Radius.lg))
            .padding(Space.lg)
            .semantics(mergeDescendants = true) {
                contentDescription = m.label + "，" + (value?.let { fmt(it) + m.unit } ?: "暂无数据")
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(m.glyph, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.label, style = AevumType.label, color = c.inkMuted)
        }
        Spacer(Modifier.height(Space.sm))
        if (value == null && waiting) {
            Box(Modifier.width(56.dp).height(24.dp).shimmer())
        } else {
            RollingNumber(value?.roundToLong(), AevumType.numeral, c.ink, suffix = if (value != null) m.unit else "")
        }
        val max = m.max
        if (max != null) {
            Spacer(Modifier.height(Space.sm))
            val f = ((value ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
            val anim by animateFloatIn(f, label = "meter")
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(c.sunken)) {
                Box(Modifier.fillMaxWidth(anim).height(4.dp).clip(RoundedCornerShape(50)).background(tint))
            }
        }
    }
}

private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)

// ── 今天 ─────────────────────────────────────────

@Composable
private fun Today(snap: PetSnapshot) {
    val c = Aevum.colors
    val t = snap.today
    Column(Modifier.testTag("today")) {
        SectionLabel("今天", trailing = {
            if (t?.tired == true) Pill("学工已疲劳", c.care, glyph = Glyphs.Moon)
        })
        AevumCard(contentPadding = PaddingValues(Space.lg)) {
            Row(Modifier.fillMaxWidth()) {
                CountCell("学习", t?.study, c.study, Glyphs.Book, Modifier.weight(1f))
                CountCell("打工", t?.work, c.work, Glyphs.Briefcase, Modifier.weight(1f))
                CountCell("冒险", t?.adventure, c.adventure, Glyphs.Compass, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.lg))
            // 只算已完成：次数与时长同源。某一类次数为 0 且时长为 0 → 不显示它的时长，构成条也不画
            val swSec = t?.let { if (it.studyWorkSec >= 0) it.studyWorkSec else it.studyWorkMinutes * 60 } ?: 0
            val advSec = t?.let { if (it.adventureSec >= 0) it.adventureSec else it.adventureMinutes * 60 } ?: 0
            val swShown = t != null && (t.study + t.work > 0 || swSec > 0)
            val advShown = t != null && (t.adventure > 0 || advSec > 0)
            if (t == null) {
                Text("今天的数据还没同步过来", style = AevumType.label, color = c.inkMuted)
            } else if (!swShown && !advShown) {
                Text("今天还没有完成的任务", style = AevumType.label, color = c.inkMuted)
            } else {
                val sw = if (swShown) swSec.toFloat() else 0f
                val adv = if (advShown) advSec.toFloat() else 0f
                val total = (sw + adv).coerceAtLeast(1f)
                Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(c.sunken)) {
                    if (sw > 0f) Box(Modifier.weight(sw / total).fillMaxSize().background(c.accent))
                    if (adv > 0f) Box(Modifier.weight(adv / total).fillMaxSize().background(c.adventure))
                }
                Spacer(Modifier.height(Space.sm))
                Row(Modifier.fillMaxWidth()) {
                    if (swShown) Text("学工 ${Copy.durationSec(swSec)}", style = AevumType.label, color = c.inkMuted, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    if (advShown) Text("冒险 ${Copy.durationSec(advSec)}", style = AevumType.label, color = c.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun CountCell(label: String, value: Int?, tint: Color, glyph: ImageVector, modifier: Modifier) {
    val c = Aevum.colors
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$label ${value ?: 0} 次" },
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(glyph, null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, style = AevumType.label, color = c.inkMuted)
        }
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            RollingNumber(value?.toLong(), AevumType.numeral, c.ink)
            Spacer(Modifier.width(3.dp))
            Text("次", style = AevumType.caption, color = c.inkMuted, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

// ── 时间线 ───────────────────────────────────────

@Composable
private fun Timeline(snap: PetSnapshot, now: Long) {
    val c = Aevum.colors
    val entries = snap.timeline.filter { !it.duplicate }.asReversed()
    val groups = io.github.juwanhwang.autoqqpet.ui.data.groupTimeline(entries)
    Column {
        SectionLabel("时间线")
        AevumCard(contentPadding = PaddingValues(vertical = Space.sm)) {
            if (groups.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = Space.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PetGlyph(PetMood.SLEEP, body = c.sunken, face = c.inkMuted, leaf = c.inkFaint, modifier = Modifier.size(84.dp))
                    Spacer(Modifier.height(Space.md))
                    Text("今天还没开工", style = AevumType.bodyStrong, color = c.ink)
                    Text("开工后每一件事都会记在这里。", style = AevumType.caption, color = c.inkMuted)
                }
            } else {
                groups.take(30).forEachIndexed { i, g ->
                    TimelineRow(g, live = i == 0 && g.head.at + g.head.seconds * 1000L > now, last = i == groups.size.coerceAtMost(30) - 1)
                }
                val shown = groups.take(30).sumOf { it.count }
                if (entries.size > shown) {
                    Text(
                        "还有 ${entries.size - shown} 条更早的记录",
                        style = AevumType.caption, color = c.inkMuted,
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(g: io.github.juwanhwang.autoqqpet.ui.data.TimelineGroup, live: Boolean, last: Boolean) {
    val e = g.head
    val c = Aevum.colors
    val tint = when (e.kind) {
        TaskKind.STUDY -> c.study
        TaskKind.WORK -> c.work
        TaskKind.ADVENTURE -> c.adventure
        TaskKind.CARE -> c.care
        TaskKind.REST -> c.rest
    }
    val glyph = when (e.kind) {
        TaskKind.STUDY -> Glyphs.Book
        TaskKind.WORK -> Glyphs.Briefcase
        TaskKind.ADVENTURE -> Glyphs.Compass
        TaskKind.CARE -> Glyphs.Heart
        TaskKind.REST -> Glyphs.Moon
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .semantics(mergeDescendants = true) {
                contentDescription = "${e.clock} ${Copy.kindLabel(e.kind)} " +
                    (if (g.merged) "共 ${g.count} 次，合计 ${Copy.durationSecExact(g.totalSeconds)}" else Copy.durationSecExact(e.seconds)) +
                    if (live) "，进行中" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.clock, style = AevumType.label, color = c.inkMuted, modifier = Modifier.width(48.dp))
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            // 竖向连接线
            if (!last) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(top = 30.dp)
                        .width(1.5.dp)
                        .height(28.dp)
                        .background(c.line),
                )
            }
            GlyphBadge(glyph, tint, size = 30.dp)
        }
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f).padding(vertical = Space.md)) {
            Text(Copy.kindLabel(e.kind), style = AevumType.bodyStrong, color = c.ink)
            Text(
                if (g.merged) "共 ${g.count} 次 · 合计 ${Copy.durationSecExact(g.totalSeconds)}" else Copy.durationSecExact(e.seconds),
                style = AevumType.caption, color = c.inkMuted,
            )
        }
        if (live) Pill("进行中", tint, dot = true)
    }
}
