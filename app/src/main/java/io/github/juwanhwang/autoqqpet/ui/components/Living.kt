package io.github.juwanhwang.autoqqpet.ui.components

import io.github.juwanhwang.autoqqpet.ui.theme.animateFloatIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.gentleSpring
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
import kotlin.math.cos
import kotlin.math.sin

/** 呼吸点：实心点 + 向外扩散的淡环（心跳、托管中）。减少动效时只剩实心点。 */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier, active: Boolean = true) {
    val reduced = reducedMotion
    val t = if (active && !reduced) {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "pulse_t").value
    } else 0f
    Canvas(modifier) {
        val r = size.minDimension / 2f
        if (active && !reduced) {
            drawCircle(color.copy(alpha = 0.45f * (1f - t)), radius = r * (1f + 1.4f * t))
        }
        drawCircle(color, radius = r)
    }
}

/** 骨架闪光：数据未到时的占位。减少动效时是静态的浅色块。 */
fun Modifier.shimmer(shape: RoundedCornerShape = RoundedCornerShape(8.dp)): Modifier = composed {
    val base = Aevum.colors.sunken
    val glow = Aevum.colors.line
    val reduced = reducedMotion
    val x = if (reduced) 0f else {
        val inf = rememberInfiniteTransition(label = "shimmer")
        inf.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmer_x").value
    }
    this
        .clip(shape)
        .background(base)
        .drawWithContent {
            if (!reduced) {
                val w = size.width
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, glow.copy(alpha = 0.9f), Color.Transparent),
                        start = Offset(w * x - w * 0.5f, 0f),
                        end = Offset(w * x + w * 0.5f, size.height),
                    ),
                )
            }
        }
}

@Composable
fun SkeletonLine(width: Dp, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(width)
            .height(height)
            .clearAndSetSemantics { contentDescription = "正在载入" }
            .shimmer(),
    )
}

/**
 * 进度环。[progress] 为 null 时是“不确定”：一段弧缓慢绕圈（减少动效时静止在顶部）。
 * 弧头有一颗亮点，让人一眼看出“走到哪了”。
 */
@Composable
fun ProgressRing(
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    track: Color = color.copy(alpha = 0.14f),
    stroke: Dp = 10.dp,
) {
    val reduced = reducedMotion
    val animated by animateFloatIn(progress ?: 0f, label = "ring", spec = gentleSpring())
    val spin = if (progress == null && !reduced) {
        val inf = rememberInfiniteTransition(label = "ring_spin")
        inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin").value
    } else 0f
    Canvas(modifier) {
        val sw = stroke.toPx()
        val d = size.minDimension - sw
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arcSize = Size(d, d)
        // 底轨：整圈一笔画（不分段、无端帽），满/空都没有接缝
        drawCircle(track, radius = d / 2f, center = center, style = Stroke(sw))
        val start: Float
        val sweep: Float
        if (progress == null) {
            start = -90f + spin
            sweep = 70f
        } else {
            start = -90f
            sweep = (360f * animated).coerceIn(0f, 360f)
        }
        if (sweep > 0.5f) {
            // v2.1.1 修“3 点钟方向一道硬接缝”：Brush.sweepGradient 的 0°（色带起止点）固定在 3 点钟，
            // 而弧从 12 点开始画 → 渐变在 3 点钟从末色跳回首色。现在把画布转到弧的起点再从 0° 画，
            // 色标按实际扫过的比例放：起点淡 → 弧头满色；1.0 处回到淡色，让圆头端帽（落在 359° 附近）也是淡色、不突兀。
            val f = (sweep / 360f).coerceIn(0.001f, 1f)
            val faded = color.copy(alpha = 0.55f)
            val brush = Brush.sweepGradient(
                colorStops = if (f >= 0.999f) arrayOf(0f to faded, 1f to color) else arrayOf(0f to faded, f to color, 1f to faded),
                center = center,
            )
            rotate(start, pivot = center) {
                drawArc(
                    brush = brush, startAngle = 0f, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
            val a = Math.toRadians((start + sweep).toDouble())
            val c = Offset(center.x + (d / 2f) * cos(a).toFloat(), center.y + (d / 2f) * sin(a).toFloat())
            drawCircle(Color.White.copy(alpha = 0.9f), radius = sw * 0.22f, center = c)
        }
    }
}

/**
 * 滚动数字：只有变化的那几位上下滚动（变大往上、变小往下），等宽数字不抖。
 * [value] 为 null 时显示“—”。
 */
@Composable
fun RollingNumber(
    value: Long?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    suffix: String = "",
    contentDescription: String? = null,
) {
    val text = value?.toString() ?: "—"
    val reduced = reducedMotion
    Row(
        modifier.clearAndSetSemantics { this.contentDescription = contentDescription ?: (text + suffix) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 从个位往左对齐编号，位数变化时只有新增的那位进场
        text.forEachIndexed { i, ch ->
            val slot = text.length - i
            RollingChar(ch, slot, style, color, reduced)
        }
        if (suffix.isNotEmpty()) Text(suffix, style = style, color = color)
    }
}

@Composable
private fun RollingChar(ch: Char, slot: Int, style: TextStyle, color: Color, reduced: Boolean) {
    androidx.compose.runtime.key(slot) {
        AnimatedContent(
            targetState = ch,
            transitionSpec = {
                if (reduced) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    val up = (targetState.digitToIntOrNull() ?: 0) >= (initialState.digitToIntOrNull() ?: 0)
                    val dir = if (up) 1 else -1
                    (slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it * dir } + fadeIn(tween(Motion.SHORT))) togetherWith
                        (slideOutVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it * dir } + fadeOut(tween(Motion.SHORT)))
                }
            },
            label = "digit",
        ) { c ->
            Text(c.toString(), style = style, color = color)
        }
    }
}

/** 文本换内容时的交叉淡入 + 轻微上移（标题、说明随状态切换）。 */
@Composable
fun FadingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val reduced = reducedMotion
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else (fadeIn(enterTweenSpec()) + slideInVertically(enterTweenSpec()) { it / 4 }) togetherWith fadeOut(tween(Motion.MICRO))
        },
        modifier = modifier,
        label = "fading_text",
    ) { t ->
        Text(t, style = style, color = color, maxLines = maxLines)
    }
}

private fun <T> enterTweenSpec() = tween<T>(Motion.MEDIUM, easing = Motion.Emphasized)
