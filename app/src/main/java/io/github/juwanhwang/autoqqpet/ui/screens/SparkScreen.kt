package io.github.juwanhwang.autoqqpet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.pet.SparkStage
import io.github.juwanhwang.autoqqpet.pet.SparkTonight
import io.github.juwanhwang.autoqqpet.store.SettingKeys
import io.github.juwanhwang.autoqqpet.store.Store
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.components.AevumButton
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.AevumSheet
import io.github.juwanhwang.autoqqpet.ui.components.AevumSwitch
import io.github.juwanhwang.autoqqpet.ui.components.ButtonTone
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.Hairline
import io.github.juwanhwang.autoqqpet.ui.components.Page
import io.github.juwanhwang.autoqqpet.ui.components.Pill
import io.github.juwanhwang.autoqqpet.ui.components.SectionLabel
import io.github.juwanhwang.autoqqpet.ui.components.SegmentOption
import io.github.juwanhwang.autoqqpet.ui.components.SegmentedPicker
import io.github.juwanhwang.autoqqpet.ui.components.ToastHost
import io.github.juwanhwang.autoqqpet.ui.components.rememberToast
import io.github.juwanhwang.autoqqpet.ui.data.Backend
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.data.SparkModel
import io.github.juwanhwang.autoqqpet.ui.data.SparkRow
import io.github.juwanhwang.autoqqpet.ui.settings.SettingsRegistry
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.rememberHaptics

/**
 * 火花（2026-10-15 重做，面向成百上千好友）：
 *  - 逻辑：每天踩 QQ 宠物好友，名单可切换黑/白模式（黑名单：名单内不踩；白名单：只踩名单内，名单为空 = 踩所有）；
 *  - 顶部摘要（在燃 / 待重燃 / 今日已踩 x/y）+ 总开关；默认只展开“需要关注”（待重燃按剩余天数、今天没互踩的在燃）；
 *  - 全部好友收起，点开是 Lazy 列表 + 搜索 + 筛选 + 排序；每行只有一个名单小操作；
 *  - 名单单独入口：切换模式、搜索、多选批量移出；从好友列表多选添加。
 * 数据：主存 `friends_json`（QQ 进程上交的副本）。行上的 blocked 恒表示“不会被踩”（见 SparkData）。
 */
@Composable
fun SparkScreen(live: LiveState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = Aevum.colors
    val toast = rememberToast()
    var bump by remember { mutableIntStateOf(0) }
    val version = live.dataVersion + bump + (live.now / 5000).toInt()   // 每 5 秒重读一次主存副本
    val model = remember(version) { SparkModel.parse(Store.prefs(ctx).getString(Store.EXTRACT_FRIENDS, "{}")) }
    val backend = live.backend
    fun bool(d: SettingKeys.Def) = backend.bool(d.key, d.default as Boolean)
    /** 名单成员增删（原始名单口径，与模式无关）。 */
    fun setListed(uins: Collection<String>, add: Boolean) {
        val next = if (add) model.blacklist + uins else model.blacklist - uins.toSet()
        backend.setSparkBlacklist(next); bump++
    }
    /** 行上的“踩/不踩”翻转：换成名单增删（黑名单模式加名单 = 不踩；白名单模式加名单 = 踩）。 */
    fun toggleRow(r: SparkRow) {
        val add = (model.listMode == "wl") == r.blocked
        setListed(listOf(r.uin), add)
        toast.show(when {
            add && model.listMode == "wl" -> "已加入白名单：会踩"
            add -> "已拉黑：不再踩"
            model.listMode == "wl" -> "已移出白名单：不再踩"
            else -> "已移出黑名单"
        })
    }

    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf("days") }
    var blSheet by remember { mutableStateOf(false) }
    val attention = remember(model) { model.attention }
    val filtered = remember(model, query, chip, sort) { SparkModel.filter(model.rows, query, chip, sort) }

    Box(Modifier.fillMaxSize()) {
        Page(title = "火花", onBack = onBack, subtitle = "和好友连续互踩的天数。名单可切换黑/白模式。") {
            item(key = "summary") {
                Spacer(Modifier.height(Space.sm))
                AevumCard(contentPadding = PaddingValues(Space.lg)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("在燃", model.lit.toString(), c.work)
                        Stat("待重燃", model.rekindle.toString(), c.danger)
                        Stat("今日已踩", if (model.target > 0) "${model.pokedToday}/${model.target}" else "—", c.accent)
                    }
                }
            }
            item(key = "spark_settings") {
                Spacer(Modifier.height(Space.md))
                // 火花的全部设置（每天自动踩、回踩访客、每轮/每天上限、今晚熄灭提醒与时间、快清零提醒）只在这里（B4）
                SettingsCard(live, SettingsRegistry.sparkPage, note = { toast.show(it) }, onChanged = { bump++ })
            }
            item(key = "blacklist") {
                Spacer(Modifier.height(Space.md))
                AevumCard(contentPadding = PaddingValues(0.dp), onClick = { blSheet = true }, onClickLabel = "管理火花名单") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.lg, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyphs.Shield, null, tint = c.ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text("火花名单", style = AevumType.bodyStrong, color = c.ink)
                            Text(if (model.listMode == "wl") "白名单模式：只踩名单里的人（名单为空 = 踩所有）" else "黑名单模式：名单里的人永不踩、不回踩、不提醒", style = AevumType.caption, color = c.inkMuted)
                        }
                        Text("${model.blacklist.size} 人", style = AevumType.label, color = c.inkMuted)
                        Icon(Glyphs.ChevronRight, null, tint = c.inkFaint, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (model.rows.isEmpty()) {
                item(key = "empty") {
                    Spacer(Modifier.height(Space.lg))
                    AevumCard(color = c.sunken, border = null) {
                        Text("还没有好友与火花数据。托管跑起来后会自动读到；也可以点下面刷新。", style = AevumType.body, color = c.inkMuted)
                    }
                }
            } else {
                item(key = "attention_label") {
                    SectionLabel("需要关注 · ${attention.size}", trailing = {
                        Text(if (model.readAt > 0) "${Copy.ago(live.now - model.readAt)}读取" else "还没读过", style = AevumType.label, color = c.inkMuted)
                    })
                }
                if (attention.isEmpty()) {
                    item(key = "attention_empty") {
                        AevumCard(color = c.sunken, border = null) { Text("没有待重燃的，在燃的今天都互踩过了。", style = AevumType.caption, color = c.inkMuted) }
                    }
                }
                items(attention, key = { "a_" + it.uin }) { r ->
                    SparkRowItem(r, model.listMode, onToggleBlock = { toggleRow(r) })
                }
                item(key = "all_header") {
                    Spacer(Modifier.height(Space.md))
                    AevumCard(contentPadding = PaddingValues(0.dp), onClick = { showAll = !showAll }, onClickLabel = if (showAll) "收起全部好友" else "展开全部好友") {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Space.lg), verticalAlignment = Alignment.CenterVertically) {
                            Text("全部好友（${model.rows.size}）", style = AevumType.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                            Text(if (showAll) "收起" else "展开", style = AevumType.label, color = c.accent)
                        }
                    }
                }
                if (showAll) {
                    item(key = "all_tools") {
                        Column {
                            Spacer(Modifier.height(Space.sm))
                            SearchField(query, "搜昵称或 QQ 号") { query = it }
                            Spacer(Modifier.height(Space.sm))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                listOf("lit" to "在燃", "rekindle" to "待重燃", "unlit" to "未点亮", "blocked" to "不踩").forEach { (k, label) ->
                                    Chip(label, chip == k) { chip = if (chip == k) null else k }
                                }
                            }
                            Spacer(Modifier.height(Space.sm))
                            SegmentedPicker(
                                options = listOf(SegmentOption("days", "按天数"), SegmentOption("stage", "按阶段")),
                                selectedKey = sort, onSelect = { sort = it }, contentDescription = "排序",
                            )
                            Spacer(Modifier.height(Space.xs))
                            Text("${filtered.size} 人", style = AevumType.label, color = c.inkMuted, modifier = Modifier.padding(Space.xs))
                        }
                    }
                    items(filtered, key = { "f_" + it.uin }) { r ->
                        SparkRowItem(r, model.listMode, onToggleBlock = { toggleRow(r) })
                    }
                }
            }
            item(key = "refresh") {
                Spacer(Modifier.height(Space.lg))
                AevumButton("刷新火花列表", onClick = { backend.requestSparks(); backend.requestRoster(); toast.show("已向 QQ 要最新火花与好友列表") },
                    tone = ButtonTone.SECONDARY, glyph = Glyphs.Refresh)
                Spacer(Modifier.height(Space.sm))
                Text("连踩 3 天点亮；漏一天进入「待重燃」，天数保留；连续 7 天不互踩才清零。",
                    style = AevumType.caption, color = c.inkMuted, modifier = Modifier.padding(horizontal = Space.xs))
            }
        }
        ToastHost(toast)
    }
    if (blSheet) BlacklistSheet(model, onDismiss = { blSheet = false },
        onApply = { uins, add ->
            setListed(uins, add); blSheet = false
            toast.show(if (add) "已加入名单 ${uins.size} 人" else "已移出名单 ${uins.size} 人")
        },
        onModeChange = { m -> backend.setSparkListMode(m); bump++ },
    )
}

@Composable
private fun Stat(label: String, value: String, tint: Color) {
    val c = Aevum.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = AevumType.title, color = tint, maxLines = 1)
        Text(label, style = AevumType.caption, color = c.inkMuted)
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { if (it) haptics.toggleOn() else haptics.toggleOff(); onChange(it) })
            .semantics { stateDescription = if (checked) "已开启" else "已关闭" }
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AevumType.bodyStrong, color = c.ink)
            Text(text, style = AevumType.caption, color = c.inkMuted)
        }
        Spacer(Modifier.width(Space.md))
        AevumSwitch(checked)
    }
}

/** 紧凑一行：首字头像、昵称、阶段徽章与天数；只有一个名单小操作（文案随名单模式）。 */
@Composable
private fun SparkRowItem(r: SparkRow, listMode: String, onToggleBlock: () -> Unit) {
    val c = Aevum.colors
    val tint = stageTint(r.stage, r.blocked)
    // 操作 = 把“踩/不踩”翻转：黑名单模式下不踩的人在名单里（按钮是移出/拉黑），白名单模式下踩的人在名单里（按钮是移出/加入）
    val inList = if (listMode == "wl") !r.blocked else r.blocked
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(r.initial, style = AevumType.label, color = tint)
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(r.nick, style = AevumType.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail(r, listMode), style = AevumType.caption, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.sm))
        Pill(badge(r, listMode), tint)
        Spacer(Modifier.width(Space.sm))
        Text(
            if (inList) "移出" else if (listMode == "wl") "加入" else "拉黑", style = AevumType.label, color = if (inList) c.accent else c.inkMuted,
            modifier = Modifier.clip(RoundedCornerShape(Radius.sm)).clickable(role = Role.Button, onClickLabel = if (inList) "移出名单" else "加入名单") { onToggleBlock() }
                .padding(horizontal = Space.sm, vertical = Space.sm),
        )
    }
}

private fun badge(r: SparkRow, listMode: String): String = when {
    r.blocked -> if (listMode == "wl") "不在白名单" else "黑名单"
    r.stage == SparkStage.LIT -> "在燃 ${r.days}天"
    r.stage == SparkStage.REKINDLE -> "待重燃 ${r.days}天"
    else -> "未点亮"
}

private fun detail(r: SparkRow, listMode: String): String = when {
    r.blocked -> if (listMode == "wl") "不在白名单里，不踩" else "不踩、不回踩、不提醒"
    r.stage == SparkStage.REKINDLE -> if (r.daysLeft >= 0) "再断 ${r.daysLeft} 天清零 · 今天互踩就能重燃" else "今天互踩就能重燃"
    r.stage == SparkStage.LIT -> if (r.mutualToday) "今天已互踩" else if (r.pokedToday) "你已踩 · 等对方踩你" else "今天还没互踩"
    else -> if (r.pokedToday) "今天已踩" else "连踩 3 天点亮"
}

@Composable
private fun stageTint(s: SparkStage, blocked: Boolean): Color {
    val c = Aevum.colors
    return when {
        blocked -> c.inkFaint
        s == SparkStage.LIT -> c.work
        s == SparkStage.REKINDLE -> c.danger
        else -> c.inkMuted
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Aevum.colors
    Box(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).background(if (selected) c.accentSoft else c.sunken)
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = AevumType.label, color = if (selected) c.accent else c.ink) }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Aevum.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.sunken).padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.Search, null, tint = c.inkMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.sm))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = AevumType.body, color = c.inkMuted)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = AevumType.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            )
        }
    }
}

/** 火花名单：先切黑/白模式；名单内的可搜索、多选批量移出；“从好友添加”可搜索、多选加入。 */
@Composable
private fun BlacklistSheet(model: SparkModel, onDismiss: () -> Unit, onApply: (List<String>, Boolean) -> Unit, onModeChange: (String) -> Unit) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    var view by remember { mutableStateOf(if (model.blacklist.isEmpty()) "add" else "list") }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val wl = model.listMode == "wl"
    fun inList(r: SparkRow) = if (wl) !r.blocked else r.blocked
    val pool = remember(model, view, query) {
        val base = if (view == "list") model.rows.filter { inList(it) } + model.blacklist.filter { u -> model.rows.none { it.uin == u } }
            .map { SparkRow(it, it, SparkStage.UNKNOWN, 0, -1, false, false, !wl) }
        else model.rows.filter { !inList(it) }
        val q = query.trim()
        if (q.isEmpty()) base else base.filter { it.nick.contains(q, true) || it.uin.contains(q) }
    }
    AevumSheet(onDismiss = onDismiss, sheetGesturesEnabled = false, title = "火花名单", subtitle = if (wl) "白名单模式：只踩名单里的人；名单为空时踩所有好友。" else "黑名单模式：名单里的人永不踩、不回踩、不提醒。") {
        SegmentedPicker(
            options = listOf(SegmentOption("bl", "黑名单"), SegmentOption("wl", "白名单")),
            selectedKey = model.listMode, onSelect = { onModeChange(it) }, contentDescription = "名单模式",
        )
        Spacer(Modifier.height(Space.sm))
        SegmentedPicker(
            options = listOf(SegmentOption("list", "名单内 ${model.blacklist.size}"), SegmentOption("add", "从好友添加")),
            selectedKey = view, onSelect = { view = it; chosen = emptySet() }, contentDescription = "名单视图",
        )
        Spacer(Modifier.height(Space.sm))
        SearchField(query, "搜昵称或 QQ 号") { query = it }
        Spacer(Modifier.height(Space.xs))
        Text("已选 ${chosen.size} / ${pool.size}", style = AevumType.label, color = c.inkMuted, modifier = Modifier.padding(Space.xs))
        // 列表高度钉死：条数多到会滚动时用固定 380dp，不用“最高 380”让高度随内容测量漂移
        // （高度一漂，半屏面板的停靠锚点就跟着重算，大力上滑到顶后会持续抽搐）。
        LazyColumn(Modifier.fillMaxWidth().then(if (pool.size >= 6) Modifier.height(380.dp) else Modifier.heightIn(max = 380.dp))) {
            items(pool, key = { it.uin }) { r ->
                val on = r.uin in chosen
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.sm))
                        .toggleable(value = on, role = Role.Checkbox, onValueChange = { haptics.tick(); chosen = if (it) chosen + r.uin else chosen - r.uin })
                        .padding(horizontal = Space.sm, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.nick, style = AevumType.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.uin, style = AevumType.caption, color = c.inkMuted)
                    }
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) c.accent else c.sunken),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Glyphs.Check, null, tint = c.onAccent, modifier = Modifier.size(15.dp)) }
                }
            }
        }
        Spacer(Modifier.height(Space.lg))
        if (view == "list") AevumButton("移出所选（${chosen.size}）", onClick = { if (chosen.isNotEmpty()) onApply(chosen.toList(), false) }, tone = ButtonTone.SECONDARY)
        else AevumButton("加入所选（${chosen.size}）", onClick = { if (chosen.isNotEmpty()) onApply(chosen.toList(), true) })
    }
}
