package io.github.juwanhwang.autoqqpet.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion

/** 团子此刻的神态。 */
enum class PetMood { AWAKE, BUSY, SLEEP, WORRIED }

/**
 * 「团子」：本应用自己的吉祥物（不是 QQ 宠物形象，避免任何商标联想）。
 * 全部用 Canvas 画：身体是一团软糯的圆，头顶一株小芽；会呼吸、会眨眼，睡着时冒“z”。
 * 减少动效时定格为静态。
 */
@Composable
fun PetGlyph(
    mood: PetMood,
    body: Color,
    face: Color,
    leaf: Color,
    modifier: Modifier = Modifier,
    blush: Color = Color(0xFFFF8FA3),
) {
    val reduced = reducedMotion
    var breath = 0f
    var blink = 1f
    var drift = 0f
    if (!reduced) {
        val inf = rememberInfiniteTransition(label = "pet")
        val period = if (mood == PetMood.BUSY) 1400 else Motion.AMBIENT
        breath = inf.animateFloat(
            0f, 1f, infiniteRepeatable(tween(period / 2, easing = Motion.Standard), RepeatMode.Reverse), label = "breath",
        ).value
        blink = inf.animateFloat(
            0f, 1f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = 4200
                    1f at 0
                    1f at 3900
                    0.08f at 4000
                    1f at 4120
                },
            ),
            label = "blink",
        ).value
        drift = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "z").value
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        drawPet(mood, body, face, leaf, blush, breath, blink, drift, reduced)
    }
}

private fun DrawScope.drawPet(
    mood: PetMood, body: Color, face: Color, leaf: Color, blush: Color,
    breath: Float, blink: Float, drift: Float, reduced: Boolean,
) {
    val s = size.minDimension
    val ox = (size.width - s) / 2f
    val oy = (size.height - s) / 2f
    translate(ox, oy) {
        val cx = s / 2f
        val bottom = s * 0.88f
        // 呼吸：以底部为支点，纵向 +3%、横向 −1.5%
        val sy = 1f + 0.03f * breath
        val sx = 1f - 0.015f * breath
        scale(sx, sy, pivot = Offset(cx, bottom)) {
            val top = s * 0.28f
            val l = s * 0.12f
            val r = s * 0.88f

            // 地面投影
            drawOval(
                color = face.copy(alpha = 0.08f),
                topLeft = Offset(cx - s * 0.30f, bottom - s * 0.02f),
                size = Size(s * 0.60f, s * 0.06f),
            )

            // 小芽
            val stemTop = top - s * 0.10f
            drawLine(leaf, Offset(cx, top + s * 0.01f), Offset(cx, stemTop), strokeWidth = s * 0.028f, cap = StrokeCap.Round)
            val leafL = Path().apply {
                moveTo(cx, stemTop + s * 0.01f)
                cubicTo(cx - s * 0.04f, stemTop - s * 0.07f, cx - s * 0.15f, stemTop - s * 0.06f, cx - s * 0.16f, stemTop - s * 0.01f)
                cubicTo(cx - s * 0.12f, stemTop + s * 0.04f, cx - s * 0.04f, stemTop + s * 0.04f, cx, stemTop + s * 0.01f)
                close()
            }
            val leafR = Path().apply {
                moveTo(cx, stemTop)
                cubicTo(cx + s * 0.03f, stemTop - s * 0.09f, cx + s * 0.13f, stemTop - s * 0.10f, cx + s * 0.15f, stemTop - s * 0.05f)
                cubicTo(cx + s * 0.12f, stemTop + s * 0.01f, cx + s * 0.04f, stemTop + s * 0.03f, cx, stemTop)
                close()
            }
            drawPath(leafL, leaf)
            drawPath(leafR, leaf)

            // 身体
            val bodyPath = Path().apply {
                moveTo(cx, top)
                cubicTo(cx + s * 0.30f, top, r, top + s * 0.14f, r, s * 0.60f)
                cubicTo(r, s * 0.80f, cx + s * 0.22f, bottom, cx, bottom)
                cubicTo(cx - s * 0.22f, bottom, l, s * 0.80f, l, s * 0.60f)
                cubicTo(l, top + s * 0.14f, cx - s * 0.30f, top, cx, top)
                close()
            }
            drawPath(
                bodyPath,
                Brush.verticalGradient(listOf(body, body.copy(alpha = 0.92f)), startY = top, endY = bottom),
            )
            // 高光
            drawOval(
                Color.White.copy(alpha = 0.35f),
                topLeft = Offset(cx - s * 0.24f, top + s * 0.07f),
                size = Size(s * 0.16f, s * 0.08f),
            )

            // 脸
            val eyeY = s * 0.57f
            val eyeDx = s * 0.13f
            val eyeW = s * 0.068f
            val eyeH = s * 0.095f
            when (mood) {
                PetMood.SLEEP -> {
                    val st = Stroke(width = s * 0.026f, cap = StrokeCap.Round)
                    for (dx in listOf(-eyeDx, eyeDx)) {
                        drawArc(face, 20f, 140f, false, Offset(cx + dx - eyeW, eyeY - eyeH * 0.6f), Size(eyeW * 2, eyeH * 0.9f), style = st)
                    }
                }
                PetMood.WORRIED -> {
                    for (dx in listOf(-eyeDx, eyeDx)) {
                        drawOval(face, Offset(cx + dx - eyeW / 2, eyeY - eyeH / 2), Size(eyeW, eyeH))
                    }
                    val st = Stroke(width = s * 0.022f, cap = StrokeCap.Round)
                    drawLine(face, Offset(cx - eyeDx - eyeW, eyeY - eyeH * 1.0f), Offset(cx - eyeDx + eyeW * 0.6f, eyeY - eyeH * 1.25f), strokeWidth = st.width, cap = StrokeCap.Round)
                    drawLine(face, Offset(cx + eyeDx + eyeW, eyeY - eyeH * 1.0f), Offset(cx + eyeDx - eyeW * 0.6f, eyeY - eyeH * 1.25f), strokeWidth = st.width, cap = StrokeCap.Round)
                }
                else -> {
                    val h = eyeH * blink
                    val lookUp = if (mood == PetMood.BUSY) -s * 0.012f else 0f
                    for (dx in listOf(-eyeDx, eyeDx)) {
                        drawOval(face, Offset(cx + dx - eyeW / 2, eyeY - h / 2 + lookUp), Size(eyeW, h.coerceAtLeast(s * 0.008f)))
                        if (blink > 0.6f) {
                            drawCircle(Color.White.copy(alpha = 0.85f), radius = eyeW * 0.18f, center = Offset(cx + dx + eyeW * 0.15f, eyeY - h * 0.22f + lookUp))
                        }
                    }
                }
            }
            // 腮红
            for (dx in listOf(-s * 0.22f, s * 0.22f)) {
                drawOval(blush.copy(alpha = 0.45f), Offset(cx + dx - s * 0.045f, s * 0.645f), Size(s * 0.09f, s * 0.045f))
            }
            // 嘴
            val mouth = Stroke(width = s * 0.02f, cap = StrokeCap.Round)
            if (mood == PetMood.WORRIED) {
                drawArc(face, 200f, 140f, false, Offset(cx - s * 0.035f, s * 0.665f), Size(s * 0.07f, s * 0.05f), style = mouth)
            } else {
                drawArc(face, 20f, 140f, false, Offset(cx - s * 0.035f, s * 0.625f), Size(s * 0.07f, s * 0.05f), style = mouth)
            }
        }
        // 睡着：两个“z”斜向上飘，越飘越淡
        if (mood == PetMood.SLEEP && !reduced) {
            for (k in 0..1) {
                val t = (drift + k * 0.5f) % 1f
                val zx = s * (0.70f + 0.10f * t)
                val zy = s * (0.30f - 0.18f * t)
                val zs = s * (0.05f + 0.03f * t)
                val a = (1f - t) * 0.8f
                val zp = Path().apply {
                    moveTo(zx, zy); lineTo(zx + zs, zy); lineTo(zx, zy + zs); lineTo(zx + zs, zy + zs)
                }
                drawPath(zp, face.copy(alpha = a), style = Stroke(width = s * 0.016f, cap = StrokeCap.Round))
            }
        }
    }
}
