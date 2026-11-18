package io.github.juwanhwang.autoqqpet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import kotlinx.coroutines.delay

/** 轻提示的状态：show() 一次显示 2.4 秒；连续 show 会顺延。 */
class ToastState {
    var text by mutableStateOf<String?>(null)
        private set
    internal var serial by mutableIntStateOf(0)
        private set

    fun show(t: String) {
        text = t
        serial++
    }

    internal fun clear() {
        text = null
    }
}

@Composable
fun rememberToast() = remember { ToastState() }

/** 底部浮起的墨色小胶囊，读屏会礼貌播报。 */
@Composable
fun BoxScope.ToastHost(state: ToastState) {
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(state.serial) {
        val t = state.text ?: return@LaunchedEffect
        shown = t
        visible = true
        delay(2400)
        visible = false
        state.clear()
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = Space.xxl),
        enter = slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it } + fadeIn(),
        exit = slideOutVertically(tween(Motion.SHORT)) { it / 2 } + fadeOut(),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Aevum.colors.ink)
                .padding(horizontal = Space.xl, vertical = Space.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Check, null, tint = Aevum.colors.canvas, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.sm))
            Text(shown, style = AevumType.bodyStrong, color = Aevum.colors.canvas)
        }
    }
}
