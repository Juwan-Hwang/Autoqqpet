package io.github.juwanhwang.autoqqpet.ui.screens

import io.github.juwanhwang.autoqqpet.ui.theme.animateDpAtLeast
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.ui.Route
import io.github.juwanhwang.autoqqpet.ui.components.AevumButton
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.AevumDialogHost
import io.github.juwanhwang.autoqqpet.ui.components.AevumSheet
import io.github.juwanhwang.autoqqpet.ui.components.SegmentOption
import io.github.juwanhwang.autoqqpet.ui.components.SegmentedPicker
import io.github.juwanhwang.autoqqpet.ui.components.AevumSwitch
import io.github.juwanhwang.autoqqpet.ui.components.ButtonTone
import io.github.juwanhwang.autoqqpet.ui.components.DialogSpec
import io.github.juwanhwang.autoqqpet.ui.components.FadingText
import io.github.juwanhwang.autoqqpet.ui.components.GlyphBadge
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.Hairline
import io.github.juwanhwang.autoqqpet.ui.components.ListRow
import io.github.juwanhwang.autoqqpet.ui.components.NumberStepper
import io.github.juwanhwang.autoqqpet.ui.components.Page
import io.github.juwanhwang.autoqqpet.ui.components.SectionLabel
import io.github.juwanhwang.autoqqpet.ui.components.ToastHost
import io.github.juwanhwang.autoqqpet.ui.components.rememberToast
import io.github.juwanhwang.autoqqpet.ui.data.Backend
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.settings.AccentPicker
import io.github.juwanhwang.autoqqpet.ui.settings.Action
import io.github.juwanhwang.autoqqpet.ui.settings.Choice
import io.github.juwanhwang.autoqqpet.ui.components.Pill
import io.github.juwanhwang.autoqqpet.ui.settings.Group
import io.github.juwanhwang.autoqqpet.ui.settings.Link
import io.github.juwanhwang.autoqqpet.ui.settings.NumberSetting
import io.github.juwanhwang.autoqqpet.ui.settings.Option
import io.github.juwanhwang.autoqqpet.ui.settings.Select
import io.github.juwanhwang.autoqqpet.ui.settings.Setting
import io.github.juwanhwang.autoqqpet.ui.settings.SettingsEnv
import io.github.juwanhwang.autoqqpet.ui.settings.SettingsRegistry
import io.github.juwanhwang.autoqqpet.ui.settings.Toggle
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.rememberHaptics
import io.github.juwanhwang.autoqqpet.ui.theme.snappySpring

private sealed class SheetReq {
    data class ChoiceSheet(val s: Choice) : SheetReq()
    data class SelectSheet(val s: Select) : SheetReq()
    data class NumberSheet(val s: NumberSetting) : SheetReq()
    data class Friends(val which: String) : SheetReq()
}

/**
 * 设置页：完全由 [SettingsRegistry] 生成。这里只负责“每种设置长什么样、点了怎么交互”。
 */
@Composable
fun SettingsScreen(live: LiveState, onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val context = LocalContext.current
    val version = live.dataVersion   // 跨进程数据一变就重组
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var sheet by remember { mutableStateOf<SheetReq?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val toast = rememberToast()

    val env = remember(context) {
        object : SettingsEnv {
            override val context: Context = context
            override val backend: Backend = live.backend
            override fun bool(key: String, def: Boolean) = backend.bool(key, def)
            override fun string(key: String, def: String) = backend.string(key, def)
            override fun int(key: String, def: Int) = backend.int(key, def)
            override fun navigate(route: Route) = onOpen(route)
            override fun dialog(spec: DialogSpec) { dialog = spec }
            override fun openFriendList(which: String) {
                backend.requestRoster()
                sheet = SheetReq.Friends(which)
            }
            override fun note(text: String) = toast.show(text)
        }
    }
    // 行的“数据版本”：偏好版本（可观察，见 Backend.prefsVersion）+ 跨进程数据版本 + 本页 bump。
    // 作为 SettingRow 的入参传下去——入参变了行就不会被 strong skipping 跳过（v2.1.1 开关不立即切换的根因）。
    val rowVersion = live.backend.observe() + version + bump

    // 主页提醒条“去设置 · 某组”：滚到那一组的标题（page_title 占第 0 项，之后每组 标签+卡片 两项）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val anchor = io.github.juwanhwang.autoqqpet.ui.SettingsAnchor.pending
    androidx.compose.runtime.LaunchedEffect(anchor) {
        val g = anchor ?: return@LaunchedEffect
        val visible = Group.entries.filter { gr -> SettingsRegistry.all.any { it.group == gr && it.visible(env) } }
        val pos = visible.indexOf(g)
        if (pos >= 0) runCatching { listState.animateScrollToItem(2 + pos * 2) }   // 0=页标题、1=搜索框，之后每组两项
        io.github.juwanhwang.autoqqpet.ui.SettingsAnchor.pending = null
    }

    Box(Modifier.fillMaxSize()) {
        Page(title = "设置", onBack = onBack, state = listState) {
            item(key = "search") { SearchBar(query) { query = it } }
            val searching = query.isNotBlank()
            var anyMatch = false
            Group.entries.forEach { group ->
                val rows = SettingsRegistry.all.filter {
                    it.group == group && it.visible(env) && (!searching || SettingsRegistry.matches(it, env, query))
                }
                if (rows.isEmpty()) return@forEach
                anyMatch = true
                item(key = "g_" + group.name) { SectionLabel(group.title) }
                // key 带上数据版本：LazyColumn 的 item 内容不会随外层入参自动重组（2026-10-16 实测：
                // bump/dataVersion 变了，行内的副标题仍是旧值——名单改完不刷新就是它）。版本一变整卡重建。
                item(key = "c_" + group.name + "#" + rowVersion) {
                    AevumCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        rows.forEachIndexed { i, s ->
                            SettingRow(
                                s, env, rowVersion,
                                onChanged = { bump++ },
                                openSheet = { sheet = it },
                            )
                            if (i != rows.lastIndex) Hairline(inset = 62.dp)
                        }
                    }
                }
            }
            if (searching && !anyMatch) {
                item(key = "no_match") {
                    Text(
                        "没有找到相关设置",
                        style = AevumType.body, color = Aevum.colors.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xxl),
                    )
                }
            }
        }
        ToastHost(toast)
        AevumDialogHost(dialog) { dialog = null }
    }

    SettingsSheets(sheet, env, close = { sheet = null }, note = { toast.show(it) }, changed = { bump++ })
}

/** 设置搜索框：样式与名单面板里的搜索框一致（sunken 底 + 搜索图标 + 有内容时给清空）。 */
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) {
    val c = Aevum.colors
    Box(Modifier.fillMaxWidth().padding(bottom = Space.sm)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(c.sunken)
                .padding(horizontal = Space.md, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Search, null, tint = c.inkMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.sm))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索设置", style = AevumType.body, color = c.inkMuted)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = AevumType.body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "搜索设置" },
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(Space.sm))
                Icon(
                    Glyphs.Close, null, tint = c.inkMuted,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { onQuery("") }
                        .padding(3.dp)
                        .semantics { contentDescription = "清空搜索" },
                )
            }
        }
    }
}

@Composable
private fun SettingsSheets(req: SheetReq?, env: SettingsEnv, close: () -> Unit, note: (String) -> Unit, changed: () -> Unit) {
    when (req) {
        is SheetReq.ChoiceSheet -> OptionSheet(
            title = req.s.title,
            note = null,
            options = req.s.options,
            current = env.string(req.s.key, req.s.default),
            onDismiss = { close() },
            onPick = { v ->
                close()
                if (req.s.local) env.backend.prefs.edit().putString(req.s.key, v).apply()
                else env.backend.setChoice(req.s.key, v, req.s.action)
                changed()
            },
        )
        is SheetReq.SelectSheet -> OptionSheet(
            title = req.s.title,
            note = req.s.sheetNote(env),
            options = req.s.options(env),
            current = req.s.current(env),
            onDismiss = { close() },
            onPick = { v ->
                close()
                req.s.onPick(env, v)
                changed()
            },
        )
        is SheetReq.NumberSheet -> NumberSheet(req.s, env.int(req.s.key, req.s.default), onDismiss = { close() }) { v ->
            close()
            env.backend.setInt(req.s.key, v, req.s.action, req.s.op)
            note("已保存")
            changed()
        }
        is SheetReq.Friends -> FriendListSheet(env, req.which, onDismiss = { close() }) { chosen, mode ->
            close()
            env.backend.setList(req.which, chosen, mode)
            note(when (req.which) { "hire" -> "雇佣名单已保存"; else -> "互动名单已保存" })
            changed()
        }
        null -> Unit
    }
}

/**
 * 一组设置做成一张卡片（设置页每组一张；火花页也用它，B4：火花的全部设置只在火花页、一处维护）。
 * 自带弹窗/底部面板状态；提示文字交给调用方的 Toast。
 */
@Composable
fun SettingsCard(live: LiveState, rows: List<Setting>, note: (String) -> Unit, onOpen: (Route) -> Unit = {}, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val noteFn = note
    val version = live.dataVersion
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var sheet by remember { mutableStateOf<SheetReq?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    val env = remember(context) {
        object : SettingsEnv {
            override val context: Context = context
            override val backend: Backend = live.backend
            override fun bool(key: String, def: Boolean) = backend.bool(key, def)
            override fun string(key: String, def: String) = backend.string(key, def)
            override fun int(key: String, def: Int) = backend.int(key, def)
            override fun navigate(route: Route) = onOpen(route)
            override fun dialog(spec: DialogSpec) { dialog = spec }
            override fun openFriendList(which: String) {
                backend.requestRoster()
                sheet = SheetReq.Friends(which)
            }
            override fun note(text: String) = noteFn(text)
        }
    }
    val rowVersion = live.backend.observe() + version + bump
    val visible = rows.filter { it.visible(env) }
    if (visible.isNotEmpty()) {
        AevumCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            visible.forEachIndexed { i, s ->
                SettingRow(s, env, rowVersion, onChanged = { bump++; onChanged() }, openSheet = { sheet = it })
                if (i != visible.lastIndex) Hairline(inset = 62.dp)
            }
        }
    }
    AevumDialogHost(dialog) { dialog = null }
    SettingsSheets(sheet, env, close = { sheet = null }, note = note, changed = { bump++; onChanged() })
}

@Composable
private fun SettingRow(s: Setting, env: SettingsEnv, version: Int, onChanged: () -> Unit, openSheet: (SheetReq) -> Unit) {
    // version 只用来让入参随数据变化（防 strong skipping 跳过本行）；行内读偏好另经 env 订阅 Backend.prefsVersion
    val c = Aevum.colors
    val haptics = rememberHaptics()
    when (s) {
        is Toggle -> {
            val enabled = s.enabledWhen(env)
            val checked = if (!enabled) true else env.bool(s.key, s.default)
            fun apply(v: Boolean) {
                if (v) haptics.toggleOn() else haptics.toggleOff()
                if (s.local) env.backend.prefs.edit().putBoolean(s.key, v).apply()
                else env.backend.setToggle(s.key, v, s.action)
                onChanged()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .toggleable(
                        value = checked, enabled = enabled, role = Role.Switch,
                        onValueChange = { v ->
                            val confirm = s.confirmOn
                            if (v && confirm != null) {
                                val spec = confirm(env)
                                env.dialog(spec.copy(onConfirm = { apply(true) }))
                            } else apply(v)
                        },
                    )
                    .semantics { stateDescription = if (checked) "已开启" else "已关闭" }
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphBadge(s.glyph, c.ink, size = 34.dp, background = c.sunken)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, style = AevumType.bodyStrong, color = c.ink, modifier = Modifier.weight(1f, fill = false))
                        if (s.experimental) {
                            Spacer(Modifier.width(Space.xs))
                            Pill("实验", c.care)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    FadingText(
                        if (!enabled) "系统已移除动画，这里跟随系统。" else if (checked) (s.onText?.invoke(env) ?: s.on) else s.off,
                        AevumType.caption, c.inkMuted,
                    )
                }
                Spacer(Modifier.width(Space.md))
                AevumSwitch(checked, enabled = enabled)
            }
        }
        is Choice -> {
            val cur = env.string(s.key, s.default)
            val label = s.options.firstOrNull { it.value == cur }?.label ?: s.options.first().label
            ListRow(
                s.title, glyph = s.glyph, glyphTint = c.ink,
                onClick = { openSheet(SheetReq.ChoiceSheet(s)) },
                trailing = { ValueChevron(label) },
            )
        }
        is NumberSetting -> {
            val v = env.int(s.key, s.default)
            ListRow(
                s.title, summary = s.summaryOf(v), glyph = s.glyph, glyphTint = c.ink,
                onClick = { openSheet(SheetReq.NumberSheet(s)) },
                trailing = { ValueChevron(null) },
            )
        }
        is Select -> ListRow(
            s.title, summary = s.summary(env), glyph = s.glyph, glyphTint = c.ink,
            onClick = { openSheet(SheetReq.SelectSheet(s)) },
            trailing = { ValueChevron(null) },
        )
        is Action -> ListRow(
            s.title, summary = s.summary(env), glyph = s.glyph, glyphTint = c.ink,
            onClick = { s.run(env); onChanged() },
            trailing = { ValueChevron(null) },
        )
        is Link -> ListRow(
            s.title, summary = s.summary(env), glyph = s.glyph, glyphTint = c.ink,
            onClick = { env.navigate(s.route) },
            trailing = { ValueChevron(null) },
        )
        is AccentPicker -> AccentSwatchRow(env)
    }
}

/** 主题色：一排圆形色板，选中的描边 + 打勾，点一下立即生效（过渡在 AevumTheme 里，遵守减少动效）。 */
@Composable
fun AccentSwatchRow(env: SettingsEnv) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    val current = io.github.juwanhwang.autoqqpet.ui.theme.UiPrefs.accent
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md)) {
        Text("主题色", style = AevumType.bodyStrong, color = c.ink)
        Text("${current.label} · 点一下立即切换", style = AevumType.caption, color = c.inkMuted)
        Spacer(Modifier.height(Space.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            io.github.juwanhwang.autoqqpet.ui.theme.AccentTheme.entries.forEach { t ->
                val selected = t == current
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .border(BorderStroke(if (selected) 2.5.dp else 0.dp, if (selected) c.ink else androidx.compose.ui.graphics.Color.Transparent), CircleShape)
                            .padding(if (selected) 4.dp else 0.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color(if (c.isDark) t.dark else t.swatch))
                            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                haptics.segment()
                                env.backend.prefs.edit().putString(io.github.juwanhwang.autoqqpet.ui.theme.UiPrefs.KEY_ACCENT, t.key).apply()
                            })
                            .semantics { contentDescription = "主题色 ${t.label}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Glyphs.Check, null, tint = if (c.isDark) androidx.compose.ui.graphics.Color(t.darkOn) else androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, style = AevumType.caption, color = if (selected) c.ink else c.inkMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ValueChevron(value: String?) {
    val c = Aevum.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null) {
            Text(value, style = AevumType.label, color = c.inkMuted)
            Spacer(Modifier.width(Space.xs))
        }
        Icon(Glyphs.ChevronRight, null, tint = c.inkFaint, modifier = Modifier.size(18.dp))
    }
}

/** 单选面板：每项一行，选中的有实心圆点；点一下即生效并收起。 */
@Composable
private fun OptionSheet(
    title: String,
    note: String?,
    options: List<Option>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    AevumSheet(onDismiss = onDismiss, title = title, subtitle = note) {
        Column(Modifier.selectableGroup()) {
            options.forEach { o ->
                val selected = o.value == current
                val bg by animateColorAsState(if (selected) c.accentSoft else c.surfaceRaised, snappySpring(), label = "opt")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(bg)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            haptics.segment(); onPick(o.value)
                        })
                        .heightIn(min = 56.dp)
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.label, style = AevumType.bodyStrong, color = c.ink)
                        if (o.detail != null) Text(o.detail, style = AevumType.caption, color = c.inkMuted)
                    }
                    Radio(selected)
                }
            }
        }
    }
}

@Composable
fun Radio(selected: Boolean) {
    val c = Aevum.colors
    val ring by animateColorAsState(if (selected) c.accent else c.lineStrong, snappySpring(), label = "radio")
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(BorderStroke(2.dp, ring), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val dot by animateDpAtLeast(if (selected) 10.dp else 0.dp, label = "radio_dot")   // size 不接受负数
        Box(Modifier.size(dot).clip(CircleShape).background(c.accent))
    }
}

@Composable
private fun NumberSheet(s: NumberSetting, initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val capped = s.unlimitedValue != null
    var unlimited by remember { mutableStateOf(capped && s.isUnlimited(initial)) }
    // 限制模式的起始数：当前值（≥1）或第一个预设；永远不从 0 开始
    var v by remember { mutableIntStateOf(if (capped && s.isUnlimited(initial)) (s.presets.firstOrNull() ?: 1) else initial.coerceIn(s.range)) }
    val reduced = io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
    AevumSheet(onDismiss = onDismiss, title = s.title, subtitle = if (unlimited) "不限" else s.summary(v)) {
        Spacer(Modifier.height(Space.md))
        if (capped) {
            SegmentedPicker(
                options = listOf(SegmentOption("unlimited", "不限"), SegmentOption("limit", "限制为 N ${s.unit}".trim())),
                selectedKey = if (unlimited) "unlimited" else "limit",
                onSelect = { unlimited = it == "unlimited" },
                contentDescription = "${s.title}：不限或限制",
            )
            Spacer(Modifier.height(Space.md))
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !unlimited,
            enter = if (reduced) androidx.compose.animation.fadeIn(androidx.compose.animation.core.snap()) else androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = if (reduced) androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap()) else androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            NumberStepper(v, s.range, s.unit, onChange = { v = it }, presets = s.presets, step = s.step, wrap = s.wrap, formatValue = s.formatValue)
        }
        if (s.sheetNote != null) {
            Spacer(Modifier.height(Space.md))
            Text(s.sheetNote, style = AevumType.caption, color = Aevum.colors.inkMuted)
        }
        Spacer(Modifier.height(Space.xxl))
        AevumButton("保存", onClick = { onSave(if (capped && unlimited) s.unlimitedValue!! else v.coerceAtLeast(if (capped) 1 else s.range.first)) })
    }
}

/** 名单编辑：可搜索的多选列表 + 黑/白模式切换。初始勾选与模式来自当前名单；保存是整份写回。 */
@Composable
private fun FriendListSheet(env: SettingsEnv, which: String, onDismiss: () -> Unit, onSave: (List<String>, String) -> Unit) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    val roster = remember { SettingsRegistry.roster(env) }
    val friendsJson = remember { SettingsRegistry.friends(env) }
    val current = remember {
        SettingsRegistry.csv(friendsJson.optString(when (which) { "hire" -> "hire_list"; else -> "friend_list" })).toSet()
    }
    var mode by remember {
        mutableStateOf(friendsJson.optString(when (which) { "hire" -> "hire_list_mode"; else -> "friend_list_mode" })
            .takeIf { it == "wl" || it == "bl" } ?: if (which == "hire") "wl" else "bl")
    }
    var chosen by remember { mutableStateOf(current) }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, roster) {
        if (query.isBlank()) roster else roster.filter { it.second.contains(query, true) || it.first.contains(query) }
    }
    AevumSheet(
        onDismiss = onDismiss,
        sheetGesturesEnabled = false,
        title = when (which) { "hire" -> "雇佣名单"; else -> "互动名单" },
        subtitle = when {
            which == "hire" && mode == "wl" -> "白名单模式：开工雇人时只从勾选的好友里挑；一个都不勾 = 不限。"
            which == "hire" -> "黑名单模式：开工雇人时不雇勾选的好友；一个都不勾 = 不限。"
            mode == "wl" -> "白名单模式：只和勾选的好友互动（PK、照顾、互踩）；一个都不勾 = 不限。"
            else -> "黑名单模式：勾选的好友不会被互动（PK、照顾、互踩）。"
        },
    ) {
        // 名单模式：白名单 = 只认名单内，黑名单 = 排除名单内；名单为空时两种都不限
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AevumButton("白名单", onClick = { haptics.tick(); mode = "wl" }, tone = if (mode == "wl") ButtonTone.PRIMARY else ButtonTone.SECONDARY, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.sm))
            AevumButton("黑名单", onClick = { haptics.tick(); mode = "bl" }, tone = if (mode == "bl") ButtonTone.PRIMARY else ButtonTone.SECONDARY, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Space.sm))
        Text(if (mode == "wl") "白名单：只对名单里的好友生效。" else "黑名单：名单里的好友会被排除。", style = AevumType.caption, color = c.inkMuted)
        Spacer(Modifier.height(Space.md))
        if (roster.isEmpty()) {
            Text("还没有好友列表。QQ 在运行时，几秒后再打开这里。", style = AevumType.body, color = c.inkMuted)
            Spacer(Modifier.height(Space.lg))
            AevumButton("刷新好友列表", onClick = { env.backend.requestRoster(); onDismiss() }, tone = ButtonTone.SECONDARY, glyph = Glyphs.Refresh)
        } else {
        // 搜索框
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(c.sunken)
                .padding(horizontal = Space.md, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Search, null, tint = c.inkMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.sm))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜昵称或 QQ 号", style = AevumType.body, color = c.inkMuted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = AevumType.body.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "搜索好友" },
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text("已选 ${chosen.size} / ${roster.size}", style = AevumType.label, color = c.inkMuted, modifier = Modifier.padding(Space.xs))
        // 列表高度钉死：条数多到会滚动时用固定 380dp，不用“最高 380”让高度随内容测量漂移
        // （高度一漂，半屏面板的停靠锚点就跟着重算，大力上滑到顶后会持续抽搐）。
        LazyColumn(Modifier.fillMaxWidth().then(if (shown.size >= 6) Modifier.height(380.dp) else Modifier.heightIn(max = 380.dp))) {
            items(shown, key = { it.first }) { (uin, name) ->
                val on = uin in chosen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .toggleable(value = on, role = Role.Checkbox, onValueChange = {
                            haptics.tick()
                            chosen = if (it) chosen + uin else chosen - uin
                        })
                        .padding(horizontal = Space.sm, vertical = Space.sm)
                        .animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { "（无昵称）" }, style = AevumType.bodyStrong, color = c.ink)
                        Text(uin, style = AevumType.caption, color = c.inkMuted)
                    }
                    CheckBoxDot(on)
                }
            }
        }
        Spacer(Modifier.height(Space.lg))
        AevumButton("保存", onClick = { haptics.confirm(); onSave(chosen.toList(), mode) })
        if (chosen.isNotEmpty()) {
            Spacer(Modifier.height(Space.xs))
            AevumButton("清空", onClick = { haptics.tick(); chosen = emptySet() }, tone = ButtonTone.GHOST)
        }
        }
    }
}

@Composable
private fun CheckBoxDot(on: Boolean) {
    val c = Aevum.colors
    val bg by animateColorAsState(if (on) c.accent else androidx.compose.ui.graphics.Color.Transparent, snappySpring(), label = "cb")
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(BorderStroke(1.5.dp, if (on) c.accent else c.lineStrong), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Glyphs.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
    }
}
