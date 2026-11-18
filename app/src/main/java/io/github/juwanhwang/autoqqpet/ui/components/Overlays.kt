package io.github.juwanhwang.autoqqpet.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** 一个弹窗要说的全部内容。界面状态里只存它，渲染统一走 [AevumDialogHost]。 */
data class DialogSpec(
    val title: String,
    val message: String,
    val confirm: String,
    val onConfirm: (() -> Unit)? = null,
    val dismiss: String? = null,
    val onDismiss: (() -> Unit)? = null,
    val danger: Boolean = false,
    /** false = 只能二选一（点外面/返回键不关）。 */
    val cancelable: Boolean = true,
)

/**
 * 弹窗：从 92% 弹簧放大进场。按钮纵向排列（中文按钮文字长短不一，横排容易挤）。
 */
@Composable
fun AevumDialogHost(spec: DialogSpec?, onClose: () -> Unit) {
    if (spec == null) return
    Dialog(
        onDismissRequest = {
            if (spec.cancelable) {
                spec.onDismiss?.invoke()
                onClose()
            }
        },
        properties = DialogProperties(dismissOnBackPress = spec.cancelable, dismissOnClickOutside = spec.cancelable),
    ) {
        PopIn {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xl))
                    .background(Aevum.colors.surfaceRaised)
                    .padding(Space.xxl),
            ) {
                Text(spec.title, style = AevumType.title, color = Aevum.colors.ink, modifier = Modifier.semantics { heading() })
                if (spec.message.isNotBlank()) {
                    Spacer(Modifier.height(Space.sm))
                    Text(spec.message, style = AevumType.body, color = Aevum.colors.inkMuted)
                }
                Spacer(Modifier.height(Space.xxl))
                AevumButton(
                    spec.confirm,
                    onClick = {
                        onClose()
                        spec.onConfirm?.invoke()
                    },
                    tone = if (spec.danger) ButtonTone.DANGER else ButtonTone.PRIMARY,
                )
                if (spec.dismiss != null) {
                    Spacer(Modifier.height(Space.sm))
                    AevumButton(
                        spec.dismiss,
                        onClick = {
                            onClose()
                            spec.onDismiss?.invoke()
                        },
                        tone = ButtonTone.SECONDARY,
                    )
                }
            }
        }
    }
}

/** 正在进行（采集诊断等）：转圈 + 一句话。不可取消，结束由调用方关闭。 */
@Composable
fun BusyDialog(text: String?) {
    if (text == null) return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        PopIn {
            Row(
                Modifier
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(Aevum.colors.surfaceRaised)
                    .padding(horizontal = Space.xxl, vertical = Space.xl)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(null, Aevum.colors.accent, Modifier.size(28.dp), stroke = 3.5.dp)
                Spacer(Modifier.width(Space.lg))
                Text(text, style = AevumType.bodyStrong, color = Aevum.colors.ink)
            }
        }
    }
}

@Composable
private fun PopIn(content: @Composable () -> Unit) {
    val reduced = reducedMotion
    val p = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f))
    }
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) a.animateTo(1f, tween(Motion.SHORT))
    }
    Box(
        Modifier.graphicsLayer {
            val s = 0.92f + 0.08f * p.value
            scaleX = s; scaleY = s
            alpha = a.value
        },
    ) { content() }
}

/**
 * 底部面板（选择器、向导）。圆角 32dp，自带拖拽把手，内容区处理导航栏留白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AevumSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    sheetGesturesEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = Aevum.colors.surfaceRaised,
        scrimColor = Aevum.colors.scrim,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = Space.md, bottom = Space.sm)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Aevum.colors.lineStrong),
            )
        },
    ) {
        // 高度口径必须确定：面板最高到可用高度的 94%，停靠锚点不随内容测量漂移。
        // 抽搐史：内容含可滚动列表时，大力上滑顶到屏幕顶端后锚点反复重算、面板持续抽搐（2026-10-23
        // 先以高度封顶 + 调用方把列表高度钉死处理）；真机上仍有残余——面板被拖/甩到顶锚点后，内层列表的
        // 嵌套滚动继续喂位移、和锚点弹簧反复抢夺。2026-11-03 起名单类面板（内容是长列表）直接关面板手势
        // （sheetGesturesEnabled=false）：面板纹丝不动、只有列表自己滚，关闭走遮罩/返回/按钮。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.94f)
                    .navigationBarsPadding()
                    .padding(horizontal = Space.xl)
                    .padding(bottom = Space.xl),
                verticalArrangement = Arrangement.Top,
            ) {
                if (title != null) {
                    Text(title, style = AevumType.headline, color = Aevum.colors.ink, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) {
                        Spacer(Modifier.height(Space.xs))
                        Text(subtitle, style = AevumType.caption, color = Aevum.colors.inkMuted)
                    }
                    Spacer(Modifier.height(Space.lg))
                }
                content()
            }
        }
    }
}
