package io.github.juwanhwang.autoqqpet.ui.screens

import io.github.juwanhwang.autoqqpet.ui.theme.animateFloatIn
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.EulaTerms
import io.github.juwanhwang.autoqqpet.ui.components.AevumButton
import io.github.juwanhwang.autoqqpet.ui.components.ButtonTone
import io.github.juwanhwang.autoqqpet.ui.components.GlyphBadge
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.MarkdownLite
import io.github.juwanhwang.autoqqpet.ui.components.PetGlyph
import io.github.juwanhwang.autoqqpet.ui.components.PetMood
import io.github.juwanhwang.autoqqpet.ui.data.Backend
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.rememberHaptics
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
import io.github.juwanhwang.autoqqpet.ui.theme.snappySpring

/**
 * 首启（合规流程，不可省略）：
 *  1) 免费与风险声明 —— 三件事说清楚再往下；
 *  2) 许可协议 —— 必须滚到文末，“我已阅读并同意”才可勾选；勾选后才可同意；
 *     “不同意并退出”与系统返回都直接退出，不算同意。
 * 协议文本一变（指纹变）就再走一次；老用户只看第 2 步，不重复声明。
 */
@Composable
fun OnboardingScreen(returning: Boolean, onAgreed: () -> Unit, onExit: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(if (returning) 1 else 0) }
    val reduced = reducedMotion
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(Motion.MEDIUM + 80, easing = Motion.Emphasized)) { it / 5 * dir } + fadeIn(tween(Motion.MEDIUM))) togetherWith
                    (slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it / 8 * dir } + fadeOut(tween(Motion.SHORT)))
            }
        },
        label = "onboarding",
    ) { s ->
        when (s) {
            0 -> NoticeStep(onNext = { step = 1 }, onExit = onExit)
            else -> EulaStep(
                returning = returning,
                onAgreed = onAgreed,
                onBack = { if (returning) onExit() else step = 0 },
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun NoticeStep(onNext: () -> Unit, onExit: () -> Unit) {
    val c = Aevum.colors
    val context = LocalContext.current
    BackHandler { onExit() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Space.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = Space.maxContent).fillMaxWidth()) {
            Spacer(Modifier.height(Space.xxxl))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Glow(c.accent, Modifier.size(260.dp))
                PetGlyph(PetMood.AWAKE, body = c.surface, face = c.ink, leaf = c.adventure, modifier = Modifier.size(150.dp))
            }
            Spacer(Modifier.height(Space.xl))
            Text("AUTOQQPET", style = AevumType.micro, color = c.inkMuted)
            Spacer(Modifier.height(Space.sm))
            Text("QQ 宠物，\n交给它照看。", style = AevumType.hero, color = c.ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.sm))
            Text("学习、打工、冒险、喂食洗澡，它按你的意思安排，在手机 QQ 里自己跑。", style = AevumType.body, color = c.inkMuted)
            Spacer(Modifier.height(Space.xxl))

            Text("开始之前，三件事", style = AevumType.micro, color = c.inkMuted, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.md))
            Staggered(0) {
                Promise(
                    Glyphs.Coin, c.success, "完全免费",
                    "没有内购、会员和广告，也不收集任何数据。向你收费、卖“激活码”的都是二次打包，请只从 Releases 下载。",
                )
            }
            Staggered(1) {
                Promise(
                    Glyphs.Warning, c.warning, "风险自负",
                    "它调用手机 QQ 自己的宠物接口，属于非官方自动化。平台可能限制功能或封号，通常无法申诉。请只在自己的账号上使用。",
                )
            }
            Staggered(2) {
                Promise(
                    Glyphs.Shield, c.accent, Copy.NOT_TENCENT,
                    "个人作品，源码公开（仅限非商业用途），不是官方产品，也没有获得任何授权或背书。",
                )
            }
            Spacer(Modifier.height(Space.xxl))
            AevumButton("我明白，继续", onClick = onNext)
            Spacer(Modifier.height(Space.sm))
            AevumButton("项目主页", onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Backend.PROJECT_URL))) }
            }, tone = ButtonTone.GHOST, glyph = Glyphs.External)
            Spacer(Modifier.height(Space.xl))
        }
    }
}

@Composable
private fun Promise(glyph: ImageVector, tint: Color, title: String, body: String) {
    val c = Aevum.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Space.sm)
            .clip(RoundedCornerShape(Radius.lg))
            .background(c.surface)
            .border(BorderStroke(1.dp, c.line), RoundedCornerShape(Radius.lg))
            .padding(Space.lg),
    ) {
        GlyphBadge(glyph, tint, size = 38.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = AevumType.bodyStrong, color = c.ink)
            Spacer(Modifier.height(2.dp))
            Text(body, style = AevumType.caption, color = c.inkMuted)
        }
    }
}

@Composable
private fun EulaStep(returning: Boolean, onAgreed: () -> Unit, onBack: () -> Unit, onExit: () -> Unit) {
    val c = Aevum.colors
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val text = remember { EulaTerms.text(context) }
    val scroll = rememberScrollState()
    val tail = with(LocalDensity.current) { 48.dp.toPx() }
    var readToEnd by rememberSaveable { mutableStateOf(false) }
    var checked by rememberSaveable { mutableStateOf(false) }
    val progress by remember {
        derivedStateOf { if (scroll.maxValue <= 0) 1f else (scroll.value.toFloat() / scroll.maxValue).coerceIn(0f, 1f) }
    }
    // 读到底（或内容短到一屏装得下）才放行；一旦放行不再收回
    LaunchedEffect(scroll.value, scroll.maxValue) {
        if (!readToEnd && (scroll.maxValue == 0 || scroll.value >= scroll.maxValue - tail)) {
            readToEnd = true
            haptics.confirm()
        }
    }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = Space.maxContent).fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.lg)) {
                Text(if (returning) "协议有更新" else "许可协议", style = AevumType.hero, color = c.ink, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (returning) "条款改过了，请读完新版再继续使用。" else "读到最后，才能同意。",
                    style = AevumType.body, color = c.inkMuted,
                )
                Spacer(Modifier.height(Space.md))
                ReadingBar(progress, readToEnd)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = Space.gutter),
                ) {
                    MarkdownLite(text)
                    Spacer(Modifier.height(Space.xl))
                }
                // 底部渐隐：提示“下面还有”
                val fade by animateFloatIn(if (readToEnd) 0f else 1f, label = "fade")
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .graphicsLayer { alpha = fade }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, c.canvas))),
                )
            }
        }
        // 底部操作区
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Column(Modifier.widthIn(max = Space.maxContent).fillMaxWidth().padding(Space.gutter)) {
                AgreeCheck(enabled = readToEnd, checked = checked, onChange = { checked = it })
                Spacer(Modifier.height(Space.md))
                AevumButton(
                    "同意并继续",
                    onClick = { if (readToEnd && checked) { haptics.confirm(); onAgreed() } },
                    enabled = readToEnd && checked,
                )
                Spacer(Modifier.height(Space.xs))
                AevumButton("不同意并退出", onClick = onExit, tone = ButtonTone.GHOST)
            }
        }
    }
}

@Composable
private fun ReadingBar(progress: Float, done: Boolean) {
    val c = Aevum.colors
    val color by animateColorAsState(if (done) c.success else c.accent, label = "bar_c")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(c.sunken),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceAtLeast(0.02f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
        Spacer(Modifier.width(Space.md))
        Text(
            if (done) "已读完" else "已读 ${(progress * 100).toInt()}%",
            style = AevumType.label, color = if (done) c.success else c.inkMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun AgreeCheck(enabled: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Aevum.colors
    val haptics = rememberHaptics()
    val box by animateColorAsState(if (checked) c.accent else Color.Transparent, snappySpring(), label = "chk")
    val alpha by animateFloatIn(if (enabled) 1f else 0.4f, label = "chk_a")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .toggleable(
                value = checked, enabled = enabled, role = Role.Checkbox,
                onValueChange = { haptics.tick(); onChange(it) },
            )
            .semantics { stateDescription = if (!enabled) "请先读到协议末尾" else if (checked) "已勾选" else "未勾选" }
            .graphicsLayer { this.alpha = alpha }
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(box)
                .border(BorderStroke(1.5.dp, if (checked) c.accent else c.lineStrong), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Glyphs.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column {
            Text("我已阅读并同意", style = AevumType.bodyStrong, color = c.ink)
            if (!enabled) Text("滚动到协议末尾后可勾选", style = AevumType.caption, color = c.inkMuted)
        }
    }
}

/** 柔光：主色的径向渐变，在团子身后缓慢呼吸。 */
@Composable
fun Glow(color: Color, modifier: Modifier = Modifier, strength: Float = 0.22f) {
    Canvas(modifier) {
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = strength), color.copy(alpha = 0f)),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension / 2f,
            ),
        )
    }
}

/** 进场错峰：第 n 个元素晚 n×40ms，从下方 16dp 浮上来。只在第一次出现时播放。 */
@Composable
fun Staggered(index: Int, content: @Composable () -> Unit) {
    val reduced = reducedMotion
    var played by rememberSaveable { mutableStateOf(false) }
    val p = remember { Animatable(if (played || reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played && !reduced) {
            p.animateTo(1f, tween(Motion.LONG, delayMillis = 60 + index * Motion.STAGGER, easing = Motion.Emphasized))
        }
        played = true
    }
    val dy = with(LocalDensity.current) { 16.dp.toPx() }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * dy }) { content() }
}
