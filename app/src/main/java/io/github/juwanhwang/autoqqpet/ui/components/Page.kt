package io.github.juwanhwang.autoqqpet.ui.components

import io.github.juwanhwang.autoqqpet.ui.theme.animateFloatIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.snappySpring

/**
 * 二级页骨架：大标题随滚动收进顶栏（iOS/One UI 用户都熟悉的样子），
 * 顶栏在内容滚到下面时才长出底色与发丝线，静止时完全透明、不压内容。
 */
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    actions: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 80 } }
    val barAlpha by animateFloatIn(if (collapsed) 1f else 0f, label = "bar")
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier.fillMaxSize().background(Aevum.colors.canvas)) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Space.maxContent)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = top + 64.dp, bottom = bottom + Space.section),
        ) {
            item(key = "page_title") {
                Box(Modifier.graphicsLayer { alpha = 1f - barAlpha }) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(top = Space.sm, bottom = Space.sm)) {
                        Text(title, style = AevumType.hero, color = Aevum.colors.ink, modifier = Modifier.semantics { heading() })
                        if (subtitle != null) {
                            Spacer(Modifier.height(Space.xs))
                            Text(subtitle, style = AevumType.body, color = Aevum.colors.inkMuted)
                        }
                    }
                }
            }
            content()
        }
        TopBar(title = title, onBack = onBack, collapsedAlpha = barAlpha, actions = actions)
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    collapsedAlpha: Float,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = Aevum.colors
    val bg by animateColorAsState(c.canvas.copy(alpha = collapsedAlpha), label = "bar_bg")
    Box(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = Space.maxContent + Space.gutter)
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButtonCircle(Glyphs.Back, "返回", onBack)
            } else {
                Spacer(Modifier.width(Space.md))
            }
            Text(
                title,
                style = AevumType.title,
                color = c.ink,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Space.sm)
                    .graphicsLayer { alpha = collapsedAlpha },
            )
            actions?.invoke()
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .graphicsLayer { alpha = collapsedAlpha }
                .background(c.line),
        )
    }
}
