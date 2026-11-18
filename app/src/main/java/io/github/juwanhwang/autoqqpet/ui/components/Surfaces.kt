package io.github.juwanhwang.autoqqpet.ui.components

import io.github.juwanhwang.autoqqpet.ui.theme.animateFloatIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.pressSpring

/**
 * 按压缩放：按下收到 [pressedScale]，松手带一点弹簧回弹。只改 graphicsLayer，不引起重排。
 */
@Composable
fun Modifier.pressable(
    source: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatIn(if (isPressed) pressedScale else 1f, label = "press", range = 0.5f..1.5f, spec = pressSpring())   // 缩放：允许回弹，但夹在正数
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** 卡片：发丝线 + 微弱层级，不用重阴影。传 [onClick] 即成为可点卡片（带按压缩放与水波）。 */
@Composable
fun AevumCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.lg),
    color: Color = Aevum.colors.surface,
    border: Color? = Aevum.colors.line,
    contentPadding: PaddingValues = PaddingValues(Space.lg),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val base = modifier
        .then(if (onClick != null) Modifier.pressable(source, 0.985f) else Modifier)
        .clip(shape)
        .background(color)
        .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), shape) else Modifier)
        .then(
            if (onClick != null) Modifier.clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            ) else Modifier,
        )
    Column(base.padding(contentPadding), content = content)
}

/** 分组小标题（屏幕阅读器视为标题）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Space.xs, end = Space.xs, top = Space.xxl, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = AevumType.micro,
            color = Aevum.colors.inkMuted,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(Aevum.colors.line),
    )
}

/** 圆形底的图标徽记（设置行、时间线节点）。 */
@Composable
fun GlyphBadge(
    glyph: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    background: Color = tint.copy(alpha = 0.12f),
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** 列表行：图标 + 标题 + 说明 + 尾部（值/箭头/开关）。整行可点，最小 56dp。 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    glyph: ImageVector? = null,
    glyphTint: Color = Aevum.colors.ink,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    trailing: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = role,
                    onClick = onClick,
                ) else Modifier,
            )
            .padding(horizontal = Space.lg, vertical = Space.md)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            GlyphBadge(glyph, glyphTint, size = 34.dp)
            Spacer(Modifier.width(Space.md))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AevumType.bodyStrong, color = Aevum.colors.ink)
            if (!summary.isNullOrBlank()) {
                Text(summary, style = AevumType.caption, color = Aevum.colors.inkMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.md))
            trailing()
        }
    }
}

/** 小胶囊标签（“托管中”“今日已疲劳”）。 */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    glyph: ImageVector? = null,
    dot: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            PulseDot(color, Modifier.size(8.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (glyph != null) {
            Icon(glyph, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = AevumType.label, color = color, maxLines = 1)
    }
}
