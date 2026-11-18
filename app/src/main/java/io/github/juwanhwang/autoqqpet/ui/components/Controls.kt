package io.github.juwanhwang.autoqqpet.ui.components

import io.github.juwanhwang.autoqqpet.ui.theme.animateDpAtLeast
import io.github.juwanhwang.autoqqpet.ui.theme.animateFloatIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.rememberHaptics
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
import io.github.juwanhwang.autoqqpet.ui.theme.snappySpring

/**
 * 托管总开关：整条可点的大胶囊，拇指带弹簧滑到另一头。
 * 开着时胶囊里有一道很慢的光扫过（“它在干活”）；减少动效时不扫。
 */
@Composable
fun BigToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    labelOn: String,
    labelOff: String,
    hintOn: String,
    hintOff: String,
    modifier: Modifier = Modifier,
) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val track by animateColorAsState(if (checked) c.accent else c.surface, snappySpring(), label = "track")
    val content by animateColorAsState(if (checked) c.onAccent else c.ink, snappySpring(), label = "content")
    val sub by animateColorAsState(if (checked) c.onAccent.copy(alpha = 0.78f) else c.inkMuted, snappySpring(), label = "sub")
    val reduced = reducedMotion
    val sweep = if (checked && !reduced) {
        rememberInfiniteTransition(label = "sweep")
            .animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "sweep_x").value
    } else -1f
    val thumbSize = 48.dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .pressable(source, 0.98f)
            .clip(RoundedCornerShape(Radius.xl))
            .background(track)
            .border(BorderStroke(1.dp, if (checked) Color.Transparent else c.line), RoundedCornerShape(Radius.xl))
            .drawBehind {
                if (sweep > -0.5f) {
                    val w = size.width
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                            start = Offset(w * sweep - w * 0.25f, 0f),
                            end = Offset(w * sweep + w * 0.25f, size.height),
                        ),
                    )
                }
            }
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = {
                    if (it) haptics.toggleOn() else haptics.toggleOff()
                    onCheckedChange(it)
                },
            )
            .semantics { stateDescription = if (checked) labelOn else labelOff }
            .padding(Space.md),
    ) {
        val travel = maxWidth - thumbSize
        val x by animateDpAtLeast(if (checked) travel.coerceAtLeast(0.dp) else 0.dp, label = "thumb", spec = snappySpring())   // offset：允许轻微过冲，但夹 ≥0
        // 崩溃修复（2026-10-15）：padding 用不过冲的弹簧并夹 ≥0；旧版欠阻尼弹簧回弹取到负值 → "Padding must be non-negative"
        val padStart by animateDpAtLeast(if (checked) 0.dp else thumbSize + Space.md, label = "pad_s")
        val padEnd by animateDpAtLeast(if (checked) thumbSize + Space.md else 0.dp, label = "pad_e")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = padStart, end = padEnd)
                .padding(horizontal = Space.sm)
                .align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FadingText(if (checked) labelOn else labelOff, AevumType.title, content)
                FadingText(if (checked) hintOn else hintOff, AevumType.caption, sub, maxLines = 2)
            }
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = x)
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (checked) c.onAccent else c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.Power, null, tint = if (checked) c.accent else c.onAccent, modifier = Modifier.size(22.dp))
        }
    }
}

/** 小开关（设置行尾）。 */
@Composable
fun AevumSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Aevum.colors
    val track by animateColorAsState(if (checked) c.accent else c.lineStrong, snappySpring(), label = "sw_track")
    val x by animateDpAtLeast(if (checked) 22.dp else 2.dp, label = "sw_x", spec = snappySpring())
    val scale by animateFloatIn(if (checked) 1f else 0.86f, label = "sw_scale", range = 0f..1.2f, spec = snappySpring())
    Box(
        modifier
            .size(width = 50.dp, height = 30.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(26.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

data class SegmentOption(val key: String, val label: String)

/**
 * 分段选择：选中块用弹簧滑过去，每换一格给一下轻触感。
 * 文字可换行（大字体下不截断），整行等分。
 */
@Composable
fun SegmentedPicker(
    options: List<SegmentOption>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    val idx = options.indexOfFirst { it.key == selectedKey }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(c.sunken)
            .padding(4.dp)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        val n = options.size.coerceAtLeast(1)
        val cell = maxWidth / n
        val x by animateDpAtLeast(cell * idx.coerceAtLeast(0), label = "seg_x", spec = snappySpring())
        val alpha by animateFloatIn(if (idx >= 0) 1f else 0f, label = "seg_a")   // 进 Color.copy(alpha)：>1 会抛异常
        val indicator = c.surface
        val shadow = c.line
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val w = with(density) { cell.toPx() }
                    val left = with(density) { x.toPx() }
                    val r = with(density) { (Radius.md - 4.dp).toPx() }
                    drawRoundRect(shadow.copy(alpha = alpha), Offset(left, 1f), Size(w, size.height), CornerRadius(r, r))
                    drawRoundRect(indicator.copy(alpha = alpha), Offset(left, 0f), Size(w, size.height - 1f), CornerRadius(r, r))
                }
                .selectableGroup(),
        ) {
            options.forEachIndexed { i, o ->
                val selected = i == idx
                val color by animateColorAsState(if (selected) c.ink else c.inkMuted, snappySpring(), label = "seg_c")
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(Radius.md - 4.dp))
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = {
                                if (!selected) {
                                    haptics.segment()
                                    onSelect(o.key)
                                }
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        o.label,
                        style = if (selected) AevumType.bodyStrong else AevumType.body,
                        color = color,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

enum class ButtonTone { PRIMARY, SECONDARY, GHOST, DANGER }

@Composable
fun AevumButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.PRIMARY,
    glyph: ImageVector? = null,
    enabled: Boolean = true,
    fill: Boolean = true,
) {
    val c = Aevum.colors
    val source = remember { MutableInteractionSource() }
    val (bg, fg) = when (tone) {
        ButtonTone.PRIMARY -> c.accent to c.onAccent
        ButtonTone.SECONDARY -> c.sunken to c.ink
        ButtonTone.GHOST -> Color.Transparent to c.accent
        ButtonTone.DANGER -> c.danger.copy(alpha = 0.12f) to c.danger
    }
    val alpha by animateFloatIn(if (enabled) 1f else 0.4f, label = "btn_a")
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 52.dp)
            .pressable(source, 0.97f)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.xl, vertical = Space.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Icon(glyph, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.sm))
        }
        Text(text, style = AevumType.bodyStrong, color = fg, textAlign = TextAlign.Center)
    }
}

/** 圆形图标按钮（顶栏返回、设置入口）。触控区 48dp。 */
@Composable
fun IconButtonCircle(
    glyph: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Aevum.colors.ink,
    background: Color = Aevum.colors.surface,
    size: Dp = 44.dp,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .pressable(source, 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .border(BorderStroke(1.dp, Aevum.colors.line), CircleShape)
                .clickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                )
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 步进一位后的值（纯逻辑，供单测）。不绕回时就是夹进 [range]；
 * 绕回时档位是 range.first 起每 [step] 一档、最高档是不超过 range.last 的最后一档：
 * 越过最高档回到 range.first，低于 range.first 跳到最高档（如 0..1439、步长 30：1410 ↔ 0）。
 */
fun steppedValue(value: Int, range: IntRange, step: Int, dir: Int, wrap: Boolean): Int {
    if (step <= 0) return value.coerceIn(range)
    val raw = value + dir * step
    if (!wrap) return raw.coerceIn(range)
    val top = range.first + (range.last - range.first) / step * step
    return when {
        raw > top -> range.first
        raw < range.first -> top
        else -> raw
    }
}

/** 长按连发的节奏（纯逻辑，供单测）：已按住 [heldMs] 毫秒时，返回（距下一次触发的间隔毫秒、一次走几步）。
 * 普通数值越按越快；绕回档位（时刻类）不加速，一圈档位不多、加速只会乱转。 */
fun repeatStepPlan(heldMs: Long, wrap: Boolean): Pair<Long, Int> {
    if (wrap) return 90L to 1
    return when {
        heldMs < 1200 -> 90L to 1
        heldMs < 2600 -> 70L to 5
        else -> 60L to 20
    }
}

/**
 * 步进钮：点按走一步；按住不放进入连发（节奏见 [repeatStepPlan]），走到边界自己停。
 * 视觉与 [IconButtonCircle] 一致：48dp 触控区、44dp 圆面、按下缩到 0.9。
 * [onStep] 吃一个步数倍率，返回这次有没有真的动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepperButton(
    glyph: ImageVector,
    contentDescription: String,
    background: Color,
    wrap: Boolean,
    onStep: (Int) -> Boolean,
) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val latestOnStep by rememberUpdatedState(onStep)
    var holding by remember { mutableStateOf(false) }

    // 松手、或手势被滚动抢走时停下连发
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release || interaction is PressInteraction.Cancel) {
                holding = false
            }
        }
    }
    // 连发循环：长按成立后按节奏走
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        var heldMs = 500L // 长按门槛本身约半秒
        while (true) {
            val (interval, mult) = repeatStepPlan(heldMs, wrap)
            delay(interval)
            heldMs += interval
            if (!latestOnStep(mult)) {
                haptics.reject()
                holding = false
                break
            }
        }
    }

    Box(
        Modifier
            .size(48.dp)
            .pressable(source, 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(background)
                .border(BorderStroke(1.dp, c.line), CircleShape)
                .combinedClickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onLongClickLabel = "连续$contentDescription",
                    onClick = { if (latestOnStep(1)) haptics.tick() else haptics.reject() },
                    onLongClick = { holding = true },
                )
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, null, tint = c.ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** 点数字弹出的直接输入：只收数字，确定后夹回 [range]。 */
@Composable
private fun NumberInputDialog(
    unit: String,
    range: IntRange,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    var field by remember {
        mutableStateOf(TextFieldValue(initial.toString(), selection = TextRange(0, initial.toString().length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.xl))
                .background(c.surfaceRaised)
                .padding(Space.xxl),
        ) {
            Text("输入数值", style = AevumType.title, color = c.ink)
            Spacer(Modifier.height(Space.sm))
            Text("范围 ${range.first} – ${range.last}".let { if (unit.isBlank()) it else "$it $unit" }, style = AevumType.caption, color = c.inkMuted)
            Spacer(Modifier.height(Space.lg))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(c.sunken)
                    .padding(horizontal = Space.md, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { v ->
                        val digits = v.text.filter { it.isDigit() }.take(9)
                        field = TextFieldValue(digits, selection = TextRange(digits.length))
                    },
                    singleLine = true,
                    textStyle = AevumType.numeral.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = "输入数值" },
                )
            }
            Spacer(Modifier.height(Space.xxl))
            AevumButton("确定", onClick = {
                val n = field.text.toIntOrNull()
                if (n != null) {
                    haptics.confirm()
                    onConfirm(n.coerceIn(range))
                }
                onDismiss()
            })
            Spacer(Modifier.height(Space.sm))
            AevumButton("取消", onClick = onDismiss, tone = ButtonTone.SECONDARY)
        }
    }
}

/** 数字步进：− 数字 +，外加几个常用值一点即到。加减钮可长按连发；点中间的数字可以直接输入（[formatValue] 非空时中间与预设都显示格式化文本，如时刻，此时不提供输入）。 */
@Composable
fun NumberStepper(
    value: Int,
    range: IntRange,
    unit: String,
    onChange: (Int) -> Unit,
    presets: List<Int>,
    step: Int = 1,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    formatValue: ((Int) -> String)? = null,
) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    // 走 mult 步（长按连发用倍率）；一步都没动返回 false。读最新值，连发途中不拿旧值覆盖。
    fun stepBy(dir: Int, mult: Int): Boolean {
        var v = latestValue
        repeat(mult) {
            val nv = steppedValue(v, range, step, dir, wrap)
            if (nv != v) v = nv
        }
        if (v == latestValue) return false
        latestOnChange(v)
        return true
    }
    var editing by remember { mutableStateOf(false) }
    val inputSource = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(Glyphs.Minus, "减少", c.sunken, wrap) { mult -> stepBy(-1, mult) }
            Spacer(Modifier.width(Space.xl))
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .widthIn(min = 120.dp)
                    .then(
                        if (formatValue == null) Modifier.clickable(
                            interactionSource = inputSource,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "输入数值",
                            onClick = { editing = true },
                        ) else Modifier,
                    ),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (formatValue != null) {
                    Text(
                        formatValue(value), style = AevumType.numeralXL, color = c.ink,
                        modifier = Modifier.semantics { contentDescription = formatValue(value) },
                    )
                } else {
                    RollingNumber(value.toLong(), AevumType.numeralXL, c.ink, contentDescription = "$value $unit")
                    Spacer(Modifier.width(6.dp))
                    Text(unit, style = AevumType.bodyStrong, color = c.inkMuted, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Spacer(Modifier.width(Space.xl))
            StepperButton(Glyphs.Plus, "增加", c.sunken, wrap) { mult -> stepBy(1, mult) }
        }
        Spacer(Modifier.height(Space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            presets.filter { it in range }.forEach { p ->
                val selected = p == value
                val bg by animateColorAsState(if (selected) c.accentSoft else c.sunken, snappySpring(), label = "preset")
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { haptics.segment(); onChange(p) })
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(formatValue?.invoke(p) ?: "$p", style = AevumType.label, color = if (selected) c.accent else c.ink)
                }
            }
        }
    }
    if (editing) {
        NumberInputDialog(
            unit = unit,
            range = range,
            initial = value,
            onDismiss = { editing = false },
            onConfirm = { onChange(it) },
        )
    }
}
