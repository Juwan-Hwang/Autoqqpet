package io.github.juwanhwang.autoqqpet.ui.screens

import io.github.juwanhwang.autoqqpet.ui.theme.animateDpAtLeast
import io.github.juwanhwang.autoqqpet.ui.theme.animateFloatIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.juwanhwang.autoqqpet.pet.GoalType
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.pet.Rule
import io.github.juwanhwang.autoqqpet.pet.RuleThen
import io.github.juwanhwang.autoqqpet.pet.RuleWhen
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.Step
import io.github.juwanhwang.autoqqpet.pet.StepKind
import io.github.juwanhwang.autoqqpet.pet.Wrap
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.components.AevumButton
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.AevumSwitch
import io.github.juwanhwang.autoqqpet.ui.components.AevumDialogHost
import io.github.juwanhwang.autoqqpet.ui.components.AevumSheet
import io.github.juwanhwang.autoqqpet.ui.components.ButtonTone
import io.github.juwanhwang.autoqqpet.ui.components.DialogSpec
import io.github.juwanhwang.autoqqpet.ui.components.GlyphBadge
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.IconButtonCircle
import io.github.juwanhwang.autoqqpet.ui.components.NumberStepper
import io.github.juwanhwang.autoqqpet.ui.components.Page
import io.github.juwanhwang.autoqqpet.ui.components.PetGlyph
import io.github.juwanhwang.autoqqpet.ui.components.PetMood
import io.github.juwanhwang.autoqqpet.ui.components.Pill
import io.github.juwanhwang.autoqqpet.ui.components.SectionLabel
import io.github.juwanhwang.autoqqpet.ui.components.SegmentOption
import io.github.juwanhwang.autoqqpet.ui.components.SegmentedPicker
import io.github.juwanhwang.autoqqpet.ui.components.ToastHost
import io.github.juwanhwang.autoqqpet.ui.components.pressable
import io.github.juwanhwang.autoqqpet.ui.components.rememberToast
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.rememberHaptics
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
import io.github.juwanhwang.autoqqpet.ui.theme.snappySpring
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// 次数与分钟的可输范围：与 Schedule.sanitized() 的钳位一致（两边不一致会“输了却被改掉”）
private val COUNT_RANGE = 1..99
private val MINUTES_RANGE = 5..720

/**
 * 排班编辑器。
 *  - 步骤是一叠卡片：按住左侧把手上下拖，其他卡片用弹簧让位；松手才保存（拖拽过程中绝不写盘）。
 *  - 每一步用一个分步面板设置：做什么 → 哪一组 → 哪一条 → 做到什么程度 → 多少；取消不留痕迹。
 *  - 规则读起来是一句话：“体力低于 30 时，先照顾”。
 *  - 只有最后一步能无限循环；被挤离末尾时降为 1 次，并当场说明。
 * 开关只有一个：主页「怎么干活」的「排班」（adv_mode=custom），这里只读它或一键切过去。
 */
@Composable
fun ScheduleScreen(live: LiveState, onBack: () -> Unit) {
    val c = Aevum.colors
    val backend = live.backend
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val list = rememberLazyListState()
    @Suppress("UNUSED_VARIABLE") val v = live.dataVersion

    var model by remember { mutableStateOf(backend.loadSchedule()) }
    var note by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var wizard by remember { mutableStateOf<Int?>(null) }      // 正在编辑的步骤序号；-1 = 新增
    var stepMenu by remember { mutableStateOf<Int?>(null) }
    var ruleWizard by remember { mutableStateOf(false) }

    // 拖拽状态
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var awaitIndex by remember { mutableIntStateOf(-1) }

    val custom = live.snapshot.mode == "custom"
    val (cursor, progress) = remember(live.dataVersion, live.now / 5000) { readProgress(live) }

    fun commit(next: Schedule, toastText: String? = null) {
        val lost = next.steps.filterIndexed { i, s -> s.goal == GoalType.FOREVER && i != next.steps.size - 1 }
        note = if (lost.isEmpty()) "" else "无限循环只能留在最后一步，已把离开末尾的那一步改成 1 次。想让它循环，拖回最后。"
        model = backend.withUniqueIds(next.sanitized())
        backend.saveSchedule(model)
        if (toastText != null) toast.show(toastText)
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in model.steps.indices || to !in model.steps.indices) return
        val l = model.steps.toMutableList()
        l.add(to, l.removeAt(from))
        model = model.copy(steps = l)
    }

    Box(Modifier.fillMaxSize()) {
        Page(title = "排班", onBack = onBack, state = list, subtitle = "按顺序做，做完一步进下一步。") {
            // ── 启用状态 ──
            item(key = "mode") {
                AevumCard(modifier = Modifier.padding(top = Space.lg), contentPadding = PaddingValues(Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphBadge(Glyphs.Calendar, if (custom) c.success else c.inkMuted, size = 38.dp)
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(if (custom) "正在按排班干活" else "排班未启用", style = AevumType.bodyStrong, color = c.ink)
                            Text(
                                if (custom) "主页「怎么干活」选的是排班。" else "现在按「${Copy.modeLabel(live.snapshot.mode)}」干活。",
                                style = AevumType.caption, color = c.inkMuted,
                            )
                        }
                    }
                    AnimatedVisibility(visible = !custom && model.steps.isNotEmpty()) {
                        Column {
                            Spacer(Modifier.height(Space.md))
                            AevumButton("改用排班干活", onClick = {
                                haptics.confirm()
                                backend.setMode("custom")
                                live.refresh()
                                toast.show("已改用排班")
                            }, tone = ButtonTone.SECONDARY)
                        }
                    }
                }
            }
            // ── 跑完一轮后 ──
            item(key = "wrap") {
                Column {
                    SectionLabel("跑完一轮后")
                    SegmentedPicker(
                        options = Wrap.entries.map { SegmentOption(it.name, it.cn) },
                        selectedKey = model.wrap.name,
                        onSelect = { k -> commit(model.copy(wrap = Wrap.valueOf(k))) },
                        contentDescription = "跑完一轮后",
                    )
                }
            }
            // ── 步骤 ──
            item(key = "steps_label") {
                SectionLabel(
                    if (model.steps.isEmpty()) "步骤" else "步骤 · ${model.steps.size}",
                    trailing = {
                        if (custom && model.steps.isNotEmpty()) {
                            Text("第 ${(cursor + 1).coerceAtMost(model.steps.size)} 步进行中", style = AevumType.label, color = c.accent)
                        }
                    },
                )
            }
            item(key = "note") {
                AnimatedVisibility(visible = note.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = Space.sm)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(c.warning.copy(alpha = 0.10f))
                            .padding(Space.md),
                    ) {
                        Icon(Glyphs.Info, null, tint = c.warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.sm))
                        Text(note, style = AevumType.caption, color = c.ink)
                    }
                }
            }
            if (model.steps.isEmpty()) {
                item(key = "empty") {
                    EmptySchedule(onTemplate = { tpl, name ->
                        haptics.confirm()
                        commit(tpl, "已套用「$name」")
                    })
                }
            }
            itemsIndexed(model.steps, key = { _, s -> "step_" + s.id }) { index, step ->
                val dragging = dragId == step.id
                val lift by animateFloatIn(if (dragging) 1f else 0f, label = "lift")
                val (cnt, mins) = progress[step.id] ?: (0 to 0)
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .then(
                            if (dragging) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(Motion.SHORT), fadeOutSpec = tween(Motion.SHORT),
                                placementSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                            ),
                        )
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            val s = 1f + 0.03f * lift
                            scaleX = s; scaleY = s
                        }
                        .padding(bottom = Space.sm),
                ) {
                    StepCard(
                        index = index,
                        step = step,
                        isLast = index == model.steps.lastIndex,
                        current = custom && index == cursor,
                        count = cnt, minutes = mins,
                        lift = lift,
                        onClick = { wizard = index },
                        onToggle = { on ->
                            val l = model.steps.toMutableList()
                            l[index] = step.copy(enabled = on)
                            commit(model.copy(steps = l), if (on) "已启用这一步" else "已禁用这一步")
                        },
                        onMenu = { haptics.longPress(); stepMenu = index },
                        onMoveBy = { d ->
                            val to = (index + d).coerceIn(0, model.steps.lastIndex)
                            if (to != index) { move(index, to); commit(model, "已移动") }
                        },
                        dragHandle = Modifier.reorderHandle(
                            key = "step_" + step.id,
                            onStart = { dragId = step.id; dragOffset = 0f; awaitIndex = -1; haptics.dragStart() },
                            onDrag = { dy ->
                                dragOffset += dy
                                val info = list.layoutInfo.visibleItemsInfo
                                val cur = info.firstOrNull { it.key == "step_" + step.id } ?: return@reorderHandle
                                if (awaitIndex >= 0 && cur.index != awaitIndex) return@reorderHandle   // 上一次交换还没落到布局上
                                awaitIndex = -1
                                val center = cur.offset + dragOffset + cur.size / 2f
                                val i = model.steps.indexOfFirst { it.id == step.id }
                                val next = info.firstOrNull { it.index == cur.index + 1 && (it.key as? String)?.startsWith("step_") == true }
                                val prev = info.firstOrNull { it.index == cur.index - 1 && (it.key as? String)?.startsWith("step_") == true }
                                if (next != null && center > next.offset + next.size / 2f && i < model.steps.lastIndex) {
                                    move(i, i + 1)
                                    dragOffset -= (next.offset + next.size - (cur.offset + cur.size)).toFloat()
                                    awaitIndex = cur.index + 1
                                    haptics.segment()
                                } else if (prev != null && center < prev.offset + prev.size / 2f && i > 0) {
                                    move(i, i - 1)
                                    dragOffset += (cur.offset - prev.offset).toFloat()
                                    awaitIndex = cur.index - 1
                                    haptics.segment()
                                }
                            },
                            onEnd = {
                                haptics.dragEnd()
                                // 松手才落盘；位置差用弹簧收回
                                commit(model)
                                val from = dragOffset
                                scope.launch {
                                    animate(from, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> dragOffset = v }
                                    dragId = null
                                }
                            },
                        ),
                    )
                }
            }
            item(key = "add_step") {
                Column {
                    AevumButton("添加一步", glyph = Glyphs.Plus, tone = ButtonTone.SECONDARY, onClick = {
                        when {
                            model.steps.lastOrNull()?.goal == GoalType.FOREVER -> dialog = DialogSpec(
                                "最后一步是无限循环",
                                "无限循环必须留在最后。先把那一步改成次数或时长，再添加新步骤。", "好",
                            )
                            else -> wizard = -1
                        }
                    })
                    if (model.steps.size > 1) {
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "按住左侧把手拖动排序；轻点编辑，长按更多操作。卡片上的开关能临时禁用一步，配置不用删。",
                            style = AevumType.caption, color = c.inkMuted,
                            modifier = Modifier.padding(horizontal = Space.xs),
                        )
                    }
                }
            }
            // ── 规则 ──
            item(key = "rules") {
                Column {
                    SectionLabel("做不了的时候")
                    AevumCard(contentPadding = PaddingValues(vertical = Space.xs)) {
                        if (model.rules.isEmpty()) {
                            Text(
                                "没有规则时：当前这步做不了，就跳到下一步。",
                                style = AevumType.body, color = c.inkMuted,
                                modifier = Modifier.padding(Space.lg),
                            )
                        }
                        model.rules.forEachIndexed { i, r ->
                            RuleRow(r, onDelete = {
                                dialog = DialogSpec(
                                    title = "删掉这条规则？",
                                    message = ruleSentence(r),
                                    confirm = "删除", danger = true, dismiss = "取消",
                                    onConfirm = { commit(model.copy(rules = model.rules.filterIndexed { k, _ -> k != i }), "已删除") },
                                )
                            })
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    AevumButton("添加规则", glyph = Glyphs.Plus, tone = ButtonTone.GHOST, onClick = {
                        if (model.rules.size >= Schedule.MAX_RULES) dialog = DialogSpec("规则已满", "最多 ${Schedule.MAX_RULES} 条。", "好")
                        else ruleWizard = true
                    })
                    Text(
                        "规则从上往下匹配，第一条命中的生效。喂食洗澡永远优先，不用写进排班。",
                        style = AevumType.caption, color = c.inkMuted,
                        modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.sm),
                    )
                }
            }
        }
        ToastHost(toast)
        AevumDialogHost(dialog) { dialog = null }
    }

    // ── 面板 ──
    wizard?.let { idx ->
        StepWizard(
            live = live,
            initial = if (idx >= 0) model.steps.getOrNull(idx) ?: Schedule.newStep()
            else Schedule.newStep().copy(id = backend.newStepId(model.steps.map { it.id }.toSet())),
            isNew = idx < 0,
            isLast = idx < 0 || idx == model.steps.lastIndex,
            onDismiss = { wizard = null },
            onSave = { step ->
                wizard = null
                val l = model.steps.toMutableList()
                if (idx in l.indices) l[idx] = step else l += step
                commit(model.copy(steps = l), if (idx < 0) "已添加" else "已保存")
            },
        )
    }
    stepMenu?.let { idx ->
        val step = model.steps.getOrNull(idx)
        if (step == null) stepMenu = null else StepMenu(
            step = step,
            onDismiss = { stepMenu = null },
            onToggle = {
                stepMenu = null
                val l = model.steps.toMutableList()
                l[idx] = step.copy(enabled = !step.enabled)
                commit(model.copy(steps = l), if (step.enabled) "已禁用这一步" else "已启用这一步")
            },
            onEdit = { stepMenu = null; wizard = idx },
            onDuplicate = {
                stepMenu = null
                val l = model.steps.toMutableList()
                l.add(idx + 1, step.copy(id = backend.newStepId(l.map { it.id }.toSet())))
                commit(model.copy(steps = l), "已复制")
            },
            onDelete = {
                stepMenu = null
                val l = model.steps.toMutableList().apply { removeAt(idx) }
                commit(model.copy(steps = l), "已删除")
            },
        )
    }
    if (ruleWizard) {
        RuleWizard(onDismiss = { ruleWizard = false }) { r ->
            ruleWizard = false
            commit(model.copy(rules = model.rules + r), "已添加规则")
        }
    }
}

/** 当日排班进度（主存里的当日快照）：游标 + 每步 次数/分钟。 */
private fun readProgress(live: LiveState): Pair<Int, Map<String, Pair<Int, Int>>> {
    val raw = live.dailyRaw() ?: return 0 to emptyMap()
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return 0 to emptyMap()
    val st = obj.optString("sch_state").split(';')
    val round = st.getOrNull(1)?.toIntOrNull() ?: 0
    val cursor = st.getOrNull(2)?.toIntOrNull() ?: 0
    val map = HashMap<String, Pair<Int, Int>>()
    obj.optString("sch_steps").split(';').forEach {
        val f = it.split(':')
        if (f.size >= 4 && f[0].toIntOrNull() == round) map[f[1]] = (f[2].toIntOrNull() ?: 0) to (f[3].toIntOrNull() ?: 0)
    }
    return cursor to map
}

// ── 步骤卡 ───────────────────────────────────────

private fun kindTint(k: StepKind, c: io.github.juwanhwang.autoqqpet.ui.theme.AevumColors) = when (k) {
    StepKind.STUDY -> c.study
    StepKind.WORK -> c.work
    StepKind.ADVENTURE -> c.adventure
}

private fun kindGlyph(k: StepKind): ImageVector = when (k) {
    StepKind.STUDY -> Glyphs.Book
    StepKind.WORK -> Glyphs.Briefcase
    StepKind.ADVENTURE -> Glyphs.Compass
}

private fun stepGroup(s: Step): String = when (s.kind) {
    StepKind.WORK -> s.groupName.ifBlank { if (s.career > 0) "type=${s.career}" else "任意职业" }
    StepKind.STUDY -> s.attr.ifBlank { "任意属性" }
    StepKind.ADVENTURE -> "冒险"
}

private fun stepItem(s: Step): String = s.itemName.ifBlank { if (s.sub > 0) "#${s.sub}" else "自动选最划算的" }

private fun stepGoal(s: Step): String = when (s.goal) {
    GoalType.FOREVER -> "一直循环"
    GoalType.COUNT -> "${s.n} 次"
    GoalType.MINUTES -> Copy.duration(s.n)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepCard(
    index: Int,
    step: Step,
    isLast: Boolean,
    current: Boolean,
    count: Int,
    minutes: Int,
    lift: Float,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMenu: () -> Unit,
    onMoveBy: (Int) -> Unit,
    dragHandle: Modifier,
) {
    val c = Aevum.colors
    val tint = kindTint(step.kind, c)
    val border by animateColorAsState(if (current) tint else c.line, snappySpring(), label = "step_border")
    val source = remember { MutableInteractionSource() }
    val elevation by animateDpAtLeast(if (lift > 0.01f) 12.dp else 0.dp, label = "elev")   // shadow 不接受负数
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(Radius.lg), clip = false)
            .pressable(source, 0.985f)
            .clip(RoundedCornerShape(Radius.lg))
            .background(c.surface)
            .border(BorderStroke(if (current) 1.5.dp else 1.dp, border), RoundedCornerShape(Radius.lg))
            .combinedClickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClickLabel = "编辑这一步",
                onLongClickLabel = "更多操作",
                onLongClick = onMenu,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "第 ${index + 1} 步，${Copy.kindLabel(toKind(step.kind))}，${stepGroup(step)}，${stepItem(step)}，${stepGoal(step)}"
                if (!step.enabled) stateDescription = "已禁用" else if (current) stateDescription = "进行中"
                customActions = listOf(
                    CustomAccessibilityAction("上移") { onMoveBy(-1); true },
                    CustomAccessibilityAction("下移") { onMoveBy(1); true },
                    CustomAccessibilityAction(if (step.enabled) "禁用这一步" else "启用这一步") { onToggle(!step.enabled); true },
                    CustomAccessibilityAction("更多操作") { onMenu(); true },
                )
            }
            .padding(end = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 拖拽把手：48dp 触控区
        Box(
            dragHandle
                .size(width = 44.dp, height = 72.dp)
                .semantics { contentDescription = "拖动排序" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.Grip, null, tint = c.inkFaint, modifier = Modifier.size(20.dp))
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f))
                .graphicsLayer { alpha = if (step.enabled) 1f else 0.45f },
            contentAlignment = Alignment.Center,
        ) {
            Icon(kindGlyph(step.kind), null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f).padding(vertical = Space.md).graphicsLayer { alpha = if (step.enabled) 1f else 0.45f }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}  ${Copy.kindLabel(toKind(step.kind))} · ${stepGroup(step)}",
                    style = AevumType.bodyStrong, color = c.ink, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!step.enabled) {
                    Spacer(Modifier.width(Space.sm))
                    Pill("已禁用", c.inkMuted)
                } else if (current) {
                    Spacer(Modifier.width(Space.sm))
                    Pill("进行中", tint, dot = true)
                }
            }
            Text("${stepItem(step)} · ${stepGoal(step)}", style = AevumType.caption, color = c.inkMuted, maxLines = 2)
            // 进度
            val target = when (step.goal) {
                GoalType.COUNT -> step.n.toFloat()
                GoalType.MINUTES -> step.n.toFloat()
                GoalType.FOREVER -> 0f
            }
            val done = when (step.goal) {
                GoalType.COUNT -> count.toFloat()
                GoalType.MINUTES -> minutes.toFloat()
                GoalType.FOREVER -> 0f
            }
            if (target > 0f && (done > 0f || current)) {
                Spacer(Modifier.height(Space.sm))
                val f by animateFloatIn((done / target).coerceIn(0f, 1f), label = "step_p")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(c.sunken)) {
                        Box(Modifier.fillMaxWidth(f).height(4.dp).clip(RoundedCornerShape(50)).background(tint))
                    }
                    Spacer(Modifier.width(Space.sm))
                    Text(step.progressText(count, minutes), style = AevumType.label, color = c.inkMuted)
                }
            } else if (step.goal == GoalType.FOREVER && isLast && count > 0) {
                Text("今天已 $count 次", style = AevumType.label, color = c.inkMuted)
            }
        }
        Box(
            Modifier
                .size(48.dp)
                .clickable { onToggle(!step.enabled) }
                .semantics { contentDescription = if (step.enabled) "禁用这一步" else "启用这一步" },
            contentAlignment = Alignment.Center,
        ) {
            AevumSwitch(checked = step.enabled)
        }
        IconButtonCircle(Glyphs.More, "更多操作", onMenu, background = Color.Transparent, size = 36.dp)
    }
}

private fun toKind(k: StepKind) = when (k) {
    StepKind.STUDY -> io.github.juwanhwang.autoqqpet.ui.data.TaskKind.STUDY
    StepKind.WORK -> io.github.juwanhwang.autoqqpet.ui.data.TaskKind.WORK
    StepKind.ADVENTURE -> io.github.juwanhwang.autoqqpet.ui.data.TaskKind.ADVENTURE
}

/** 把手上的拖拽手势：按下即开始（不需要长按），把位移交给调用方。 */
private fun Modifier.reorderHandle(
    key: String,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(key) {
        detectDragGestures(
            onDragStart = { onStart() },
            onDragEnd = { onEnd() },
            onDragCancel = { onEnd() },
            onDrag = { change, amount ->
                change.consume()
                onDrag(amount.y)
            },
        )
    },
)

@Composable
private fun EmptySchedule(onTemplate: (Schedule, String) -> Unit) {
    val c = Aevum.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Space.lg))
        PetGlyph(PetMood.AWAKE, body = c.surface, face = c.ink, leaf = c.adventure, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(Space.md))
        Text("还没有排班", style = AevumType.title, color = c.ink)
        Text("从一个模板开始，再按需要改。", style = AevumType.caption, color = c.inkMuted)
        Spacer(Modifier.height(Space.lg))
        Schedule.templates().forEachIndexed { i, (name, tpl) ->
            Staggered(i) {
                AevumCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
                    onClick = { onTemplate(tpl, name) },
                    onClickLabel = "套用$name",
                ) {
                    Text(name, style = AevumType.bodyStrong, color = c.ink)
                    Spacer(Modifier.height(Space.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        tpl.steps.forEachIndexed { k, s ->
                            if (k > 0) Icon(Glyphs.ChevronRight, null, tint = c.inkFaint, modifier = Modifier.size(14.dp))
                            Pill("${Copy.kindLabel(toKind(s.kind))} ${stepGoal(s)}", kindTint(s.kind, c))
                        }
                    }
                }
            }
        }
    }
}

// ── 步骤菜单 ─────────────────────────────────────

@Composable
private fun StepMenu(step: Step, onDismiss: () -> Unit, onToggle: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val c = Aevum.colors
    AevumSheet(onDismiss = onDismiss, title = "${Copy.kindLabel(toKind(step.kind))} · ${stepGroup(step)}", subtitle = "${stepItem(step)} · ${stepGoal(step)}") {
        MenuRow(Glyphs.Edit, "编辑", c.ink, onEdit)
        MenuRow(Glyphs.Power, if (step.enabled) "禁用这一步" else "启用这一步", c.ink, onToggle)
        MenuRow(Glyphs.Duplicate, "复制一份", c.ink, onDuplicate)
        MenuRow(Glyphs.Trash, "删除", c.danger, onDelete)
    }
}

@Composable
private fun MenuRow(glyph: ImageVector, text: String, tint: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Radius.md))
            .combinedClickableCompat(source, onClick)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.lg))
        Text(text, style = AevumType.bodyStrong, color = tint)
    }
}

@Composable
private fun Modifier.combinedClickableCompat(source: MutableInteractionSource, onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)

// ── 规则 ─────────────────────────────────────────

private fun ruleSentence(r: Rule): String {
    val whenText = when (r.cond) {
        RuleWhen.GOLD_BELOW -> "金币低于 ${r.value} 时"
        RuleWhen.STAMINA_BELOW -> "体力低于 ${r.value} 时"
        RuleWhen.CLEAN_BELOW -> "清洁低于 ${r.value} 时"
        RuleWhen.STUDY_FEE_UNAFFORDABLE -> "学费不够时"
        RuleWhen.STEP_UNAVAILABLE -> "这一步做不了时"
        RuleWhen.PAY_TIRED -> "今天学累了时"
    }
    return "$whenText，${r.then.cn}"
}

private fun ruleGlyph(r: RuleWhen): ImageVector = when (r) {
    RuleWhen.GOLD_BELOW, RuleWhen.STUDY_FEE_UNAFFORDABLE -> Glyphs.Coin
    RuleWhen.STAMINA_BELOW -> Glyphs.Bolt
    RuleWhen.CLEAN_BELOW -> Glyphs.Drop
    RuleWhen.STEP_UNAVAILABLE -> Glyphs.Warning
    RuleWhen.PAY_TIRED -> Glyphs.Moon
}

@Composable
private fun RuleRow(r: Rule, onDelete: () -> Unit) {
    val c = Aevum.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = Space.lg, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphBadge(ruleGlyph(r.cond), c.inkMuted, size = 30.dp, background = c.sunken)
        Spacer(Modifier.width(Space.md))
        Text(ruleSentence(r), style = AevumType.body, color = c.ink, modifier = Modifier.weight(1f))
        IconButtonCircle(Glyphs.Trash, "删除规则：${ruleSentence(r)}", onDelete, tint = c.inkMuted, background = Color.Transparent, size = 36.dp)
    }
}

@Composable
private fun RuleWizard(onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf(Rule(id = "r" + System.currentTimeMillis() % 100000, cond = RuleWhen.STEP_UNAVAILABLE)) }
    val numeric = draft.cond == RuleWhen.GOLD_BELOW || draft.cond == RuleWhen.STAMINA_BELOW || draft.cond == RuleWhen.CLEAN_BELOW
    val titles = listOf("什么时候", "低于多少", "怎么办")
    AevumSheet(onDismiss = onDismiss) {
        WizardHeader(titles[page], page = if (numeric) page else if (page == 2) 1 else 0, total = if (numeric) 3 else 2, onBack = if (page > 0) {
            { page = if (page == 2 && !numeric) 0 else page - 1 }
        } else null)
        WizardPages(page) { p ->
            when (p) {
                0 -> ChoiceList(
                    RuleWhen.entries.map { ChoiceItem(it.name, it.cn, glyph = ruleGlyph(it)) },
                    selected = null,
                ) { k ->
                    val cond = RuleWhen.valueOf(k)
                    val isNum = cond == RuleWhen.GOLD_BELOW || cond == RuleWhen.STAMINA_BELOW || cond == RuleWhen.CLEAN_BELOW
                    draft = draft.copy(
                        cond = cond,
                        value = when (cond) { RuleWhen.GOLD_BELOW -> 500; RuleWhen.STAMINA_BELOW, RuleWhen.CLEAN_BELOW -> 30; else -> 0 },
                    )
                    page = if (isNum) 1 else 2
                }
                1 -> {
                    val gold = draft.cond == RuleWhen.GOLD_BELOW
                    var value by remember { mutableIntStateOf(draft.value) }
                    Column {
                        NumberStepper(
                            value, if (gold) 0..100000 else 1..100, if (gold) "金币" else "点",
                            onChange = { value = it },
                            presets = if (gold) listOf(100, 500, 1000, 5000) else listOf(10, 20, 30, 50),
                            step = if (gold) 100 else 5,
                        )
                        Spacer(Modifier.height(Space.xl))
                        AevumButton("继续", onClick = { draft = draft.copy(value = value); page = 2 })
                    }
                }
                else -> ChoiceList(
                    RuleThen.entries.map { ChoiceItem(it.name, it.cn) },
                    selected = draft.then.name,
                ) { k -> onSave(draft.copy(then = RuleThen.valueOf(k))) }
            }
        }
    }
}

// ── 步骤向导 ─────────────────────────────────────

private enum class WizPage { KIND, GROUP, ITEM, GOAL, NUMBER }

@Composable
private fun StepWizard(
    live: LiveState,
    initial: Step,
    isNew: Boolean,
    isLast: Boolean,
    onDismiss: () -> Unit,
    onSave: (Step) -> Unit,
) {
    val c = Aevum.colors
    val prefs = live.backend.prefs
    var page by remember { mutableStateOf(WizPage.KIND) }
    var draft by remember { mutableStateOf(initial) }
    val history = remember { mutableListOf<WizPage>() }
    fun go(p: WizPage) { history += page; page = p }
    var works by remember { mutableStateOf(JobCatalog.decode(prefs.getString(JobCatalog.KEY_CATALOG, "").orEmpty())) }
    var courses by remember { mutableStateOf(JobCatalog.decodeCourses(prefs.getString(JobCatalog.KEY_COURSES, "").orEmpty())) }
    var advs by remember { mutableStateOf(JobCatalog.decodeAdventures(prefs.getString(JobCatalog.KEY_ADVENTURES, "").orEmpty())) }
    // 目录原地刷新：点刷新后盯住目录存档时间戳，回传一落盘就重读三份目录，名单当场更新不用重开这一步
    var refreshing by remember { mutableStateOf(false) }
    var refreshStamp by remember { mutableLongStateOf(0L) }
    fun refreshCatalog() {
        if (refreshing) return
        refreshStamp = prefs.getLong(JobCatalog.KEY_CATALOG_AT, 0L)
        live.backend.requestCatalog()
        refreshing = true
    }
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        repeat(30) {
            delay(700)
            if (prefs.getLong(JobCatalog.KEY_CATALOG_AT, 0L) != refreshStamp) {
                works = JobCatalog.decode(prefs.getString(JobCatalog.KEY_CATALOG, "").orEmpty())
                courses = JobCatalog.decodeCourses(prefs.getString(JobCatalog.KEY_COURSES, "").orEmpty())
                advs = JobCatalog.decodeAdventures(prefs.getString(JobCatalog.KEY_ADVENTURES, "").orEmpty())
                refreshing = false
                return@LaunchedEffect
            }
        }
        refreshing = false
    }

    val pages = buildList {
        add(WizPage.KIND)
        if (draft.kind != StepKind.ADVENTURE) add(WizPage.GROUP)
        add(WizPage.ITEM); add(WizPage.GOAL)
        if (draft.goal != GoalType.FOREVER) add(WizPage.NUMBER)
    }
    val title = when (page) {
        WizPage.KIND -> if (isNew) "新的一步：做什么" else "这一步做什么"
        WizPage.GROUP -> if (draft.kind == StepKind.STUDY) "加哪个属性" else "去哪打工"
        WizPage.ITEM -> "做哪一条"
        WizPage.GOAL -> "做到什么程度"
        WizPage.NUMBER -> if (draft.goal == GoalType.COUNT) "做几次" else "做多久"
    }
    AevumSheet(onDismiss = onDismiss) {
        WizardHeader(title, page = pages.indexOf(page).coerceAtLeast(0), total = pages.size, onBack = if (history.isNotEmpty()) {
            { page = history.removeAt(history.lastIndex) }
        } else null)
        WizardPages(page.ordinal) { k ->
            when (WizPage.entries[k]) {
                WizPage.KIND -> ChoiceList(
                    StepKind.entries.map { ChoiceItem(it.name, it.cn, glyph = kindGlyph(it), tint = kindTint(it, c)) },
                    selected = draft.kind.name,
                ) { k ->
                    val kind = StepKind.valueOf(k)
                    // 换种类时丢掉旧种类里的组与条目（避免“打工步骤带着属性”这种脏组合）；禁用状态留着
                    if (kind != draft.kind) draft = Step(id = draft.id, kind = kind, goal = draft.goal, n = draft.n, enabled = draft.enabled)
                    go(if (kind == StepKind.ADVENTURE) WizPage.ITEM else WizPage.GROUP)
                }
                WizPage.GROUP -> if (draft.kind == StepKind.STUDY) {
                    val attrs = listOf("", "力量", "智力", "魅力", "随机")
                    ChoiceList(attrs.map { ChoiceItem(it.ifEmpty { "*" }, it.ifEmpty { "任意属性" }) }, selected = draft.attr.ifEmpty { "*" }) { k ->
                        draft = draft.copy(attr = if (k == "*") "" else k)
                        go(WizPage.ITEM)
                    }
                } else {
                    val careers = listOf(0L to "任意职业") + JobCatalog.careers(works)
                    if (careers.size == 1) {
                        MissingCatalog(onRefresh = { refreshCatalog() }, refreshing = refreshing)
                    } else {
                        Column {
                            ChoiceList(careers.map { ChoiceItem(it.first.toString(), it.second) }, selected = draft.career.toString()) { k ->
                                val type = k.toLongOrNull() ?: 0L
                                draft = draft.copy(career = type, groupName = if (type <= 0L) "" else careers.firstOrNull { it.first == type }?.second.orEmpty())
                                go(WizPage.ITEM)
                            }
                            Spacer(Modifier.height(Space.lg))
                            AevumButton(if (refreshing) "正在刷新…" else "刷新打工目录", onClick = { refreshCatalog() }, tone = ButtonTone.SECONDARY, glyph = Glyphs.Refresh, enabled = !refreshing)
                        }
                    }
                }
                WizPage.ITEM -> {
                    // (key, 名字, 说明)。冒险的 subEventType 真机为 0（服务端靠名字认），所以冒险按名字钉
                    val items: List<Triple<String, String, String>> = when (draft.kind) {
                        StepKind.WORK -> works.filter { draft.career <= 0L || it.careerType == draft.career }.map {
                            Triple(it.subEventType.toString() + "|" + it.jobName, it.jobName,
                                "${it.duration} · +${it.points} 点 · 约 ${it.pphText} 点/小时" + if (it.canDo) "" else " · 当前不可做")
                        }
                        StepKind.STUDY -> courses.filter { draft.attr.isEmpty() || it.attrLabel == draft.attr }.map {
                            Triple(it.subEventType.toString() + "|" + it.name, it.name,
                                "${it.duration} · +${it.attrGain}${it.attrLabel} · 学费 ${it.fee}" + if (it.canDo) "" else " · 当前不可做")
                        }
                        StepKind.ADVENTURE -> advs.map {
                            Triple("0|" + it.name, it.name, it.duration.ifBlank { "${it.minutes} 分钟" } + if (it.canDo) "" else " · 当前不可做")
                        }
                    }
                    val options = listOf(ChoiceItem("0|", "自动", "每轮在这一组里选当时最划算的一条")) +
                        items.map { ChoiceItem(it.first, it.second, it.third) }
                    val sel = if (draft.kind == StepKind.ADVENTURE) "0|" + draft.itemName else draft.sub.toString() + "|" + draft.itemName
                    Column {
                        if (items.isEmpty()) {
                            Text("这一组还没读到可选项，可以先用“自动”，或稍后刷新目录。", style = AevumType.caption, color = c.inkMuted)
                            Spacer(Modifier.height(Space.sm))
                        }
                        if (draft.kind != StepKind.ADVENTURE) {
                            Text("钉住的是这一条的时长，目录里的条目以后换了名字也能接着做。", style = AevumType.caption, color = c.inkMuted)
                            Spacer(Modifier.height(Space.sm))
                        }
                        ChoiceList(options, selected = if (draft.sub == 0L && draft.itemName.isEmpty()) "0|" else sel) { k ->
                            val sub = k.substringBefore('|').toLongOrNull() ?: 0L
                            val itemName = k.substringAfter('|', "")
                            // 钉住的是组＋时长：把选中这条的时长一并存下，编号/名字只是当时的快照
                            val minutes = when (draft.kind) {
                                StepKind.STUDY -> courses.firstOrNull { it.subEventType == sub && it.name == itemName }?.minutes ?: 0
                                StepKind.WORK -> works.firstOrNull { it.subEventType == sub && it.jobName == itemName }?.minutes ?: 0
                                StepKind.ADVENTURE -> 0
                            }
                            draft = draft.copy(sub = sub, itemName = itemName, dur = if (sub == 0L) 0 else minutes)
                            go(WizPage.GOAL)
                        }
                    }
                }
                WizPage.GOAL -> {
                    val goals = buildList {
                        add(ChoiceItem(GoalType.COUNT.name, "做够次数", "例如 6 次后进下一步"))
                        add(ChoiceItem(GoalType.MINUTES.name, "做够时长", "按任务时长累计，单位分钟"))
                        if (isLast) add(ChoiceItem(GoalType.FOREVER.name, "一直循环", "只有最后一步可以"))
                    }
                    ChoiceList(goals, selected = draft.goal.name) { k ->
                        val g = GoalType.valueOf(k)
                        if (g == GoalType.FOREVER) {
                            onSave(draft.copy(goal = GoalType.FOREVER, n = 0))
                        } else {
                            val range = if (g == GoalType.COUNT) COUNT_RANGE else MINUTES_RANGE
                            val n = if (g == draft.goal) draft.n.coerceIn(range) else if (g == GoalType.COUNT) 3 else 60
                            draft = draft.copy(goal = g, n = n)
                            go(WizPage.NUMBER)
                        }
                    }
                }
                WizPage.NUMBER -> {
                    val count = draft.goal == GoalType.COUNT
                    var n by remember(draft.goal) { mutableIntStateOf(draft.n) }
                    Column {
                        NumberStepper(
                            n, if (count) COUNT_RANGE else MINUTES_RANGE, if (count) "次" else "分钟",
                            onChange = { n = it },
                            presets = if (count) listOf(1, 3, 6, 10) else listOf(30, 60, 120, 240),
                            step = if (count) 1 else 5,
                        )
                        Spacer(Modifier.height(Space.xl))
                        AevumButton(if (isNew) "添加这一步" else "保存", onClick = { onSave(draft.copy(n = n)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingCatalog(onRefresh: () -> Unit, refreshing: Boolean) {
    val c = Aevum.colors
    Column {
        Text("还没有打工目录", style = AevumType.bodyStrong, color = c.ink)
        Spacer(Modifier.height(Space.xs))
        Text("QQ 在运行时，点下面刷新，名单马上就会出现。", style = AevumType.caption, color = c.inkMuted)
        Spacer(Modifier.height(Space.lg))
        AevumButton(if (refreshing) "正在刷新…" else "刷新打工目录", onClick = onRefresh, tone = ButtonTone.SECONDARY, glyph = Glyphs.Refresh, enabled = !refreshing)
    }
}

// ── 向导通用件 ───────────────────────────────────

@Composable
private fun WizardHeader(title: String, page: Int, total: Int, onBack: (() -> Unit)?) {
    val c = Aevum.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(visible = onBack != null) {
            IconButtonCircle(Glyphs.Back, "上一步", { onBack?.invoke() }, background = c.sunken, size = 36.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = Space.xs)) {
            Text(title, style = AevumType.headline, color = c.ink)
        }
        // 步骤点
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(total) { i ->
                val w by animateDpAtLeast(if (i == page) 18.dp else 6.dp, label = "dot", min = 1.dp)
                val col by animateColorAsState(if (i <= page) c.accent else c.lineStrong, snappySpring(), label = "dot_c")
                Box(Modifier.height(6.dp).width(w).clip(RoundedCornerShape(50)).background(col))
            }
        }
    }
    Spacer(Modifier.height(Space.lg))
}

@Composable
private fun WizardPages(key: Int, content: @Composable (Int) -> Unit) {
    val reduced = reducedMotion
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else {
                val dir = if (targetState >= initialState) 1 else -1
                (slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it / 4 * dir } + fadeIn(tween(Motion.SHORT))) togetherWith
                    (slideOutHorizontally(tween(Motion.SHORT, easing = Motion.Emphasized)) { -it / 6 * dir } + fadeOut(tween(Motion.MICRO)))
            }
        },
        label = "wizard",
    ) { k ->
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { content(k) }
    }
}

private data class ChoiceItem(val key: String, val label: String, val detail: String? = null, val glyph: ImageVector? = null, val tint: Color? = null)

@Composable
private fun ChoiceList(items: List<ChoiceItem>, selected: String?, onPick: (String) -> Unit) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    Column(Modifier.selectableGroup()) {
        items.forEach { it0 ->
            val on = it0.key == selected
            val bg by animateColorAsState(if (on) c.accentSoft else c.sunken, snappySpring(), label = "choice")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(bg)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { haptics.segment(); onPick(it0.key) })
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (it0.glyph != null) {
                    Icon(it0.glyph, null, tint = it0.tint ?: c.ink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.md))
                }
                Column(Modifier.weight(1f)) {
                    Text(it0.label, style = AevumType.bodyStrong, color = c.ink)
                    if (it0.detail != null) Text(it0.detail, style = AevumType.caption, color = c.inkMuted)
                }
                Icon(Glyphs.ChevronRight, null, tint = if (on) c.accent else c.inkFaint, modifier = Modifier.size(18.dp))
            }
        }
    }
}
