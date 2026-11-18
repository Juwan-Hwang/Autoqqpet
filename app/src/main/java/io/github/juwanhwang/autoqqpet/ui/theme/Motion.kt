package io.github.juwanhwang.autoqqpet.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 动效令牌。原则：动效只用来说明“什么变了、从哪来到哪去”，不做装饰。
 *
 * - 交互反馈（按压、开关、拖拽）用弹簧：可被打断、有物理惯性；
 * - 进出场用强调减速曲线：快出发、慢落地；
 * - 环境动效（宠物呼吸、心跳点）周期 ≥ 2.4s，幅度 ≤ 4%；
 * - 系统「移除动画」或应用内「减少动效」打开时：一律瞬时（[reduced]），环境动效停止。
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val MICRO = 120
    const val SHORT = 200
    const val MEDIUM = 320
    const val LONG = 480
    const val AMBIENT = 3200
    const val STAGGER = 40

    /** 系统「动画时长缩放」被关到 0 = 用户要求移除动画。 */
    fun systemReduced(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
}

/** 当前是否减少动效（系统或应用内任一打开即为真）。 */
val reducedMotion: Boolean
    @Composable @ReadOnlyComposable get() = LocalReducedMotion.current

/** 按压/松手：轻微过冲的弹簧。 */
@Composable
@ReadOnlyComposable
fun <T> pressSpring(): FiniteAnimationSpec<T> =
    if (reducedMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 700f)

/**
 * 颜色、位移（offset/translation）这类**允许过冲**的量：利落、轻微过冲（阻尼比 0.78，约 2% 过冲）。
 *
 * ⚠ 崩溃教训（真机 2026-10-15，托管大开关必崩）：这条弹簧是欠阻尼的，数值会冲过目标再回来。
 * 它驱动 `Modifier.padding` 在 0dp↔N dp 之间切换时，回弹瞬间取到负值 →
 * `IllegalArgumentException: Padding must be non-negative`，主线程崩溃。
 * **几何量（padding/size/width/height/shadow/weight）与 alpha/比例一律不要直接用它**，用下面的
 * [animateDpAtLeast] / [animateFloatIn]（不过冲 + 夹紧）。`MotionSafetyTest` 会扫源码拦住直接调用。
 */
@Composable
@ReadOnlyComposable
fun <T> snappySpring(): FiniteAnimationSpec<T> =
    if (reducedMotion) snap() else spring(dampingRatio = 0.78f, stiffness = 520f)

/** 几何量与 alpha 专用：同样利落，但**临界阻尼、不过冲**。 */
@Composable
@ReadOnlyComposable
fun <T> geometrySpring(): FiniteAnimationSpec<T> =
    if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

/**
 * 安全的 Dp 动画：默认不过冲的弹簧，且结果**夹到 ≥ [min]**（默认 0dp）——用于 padding/size/width/height/shadow。
 * 即使调用方传进来一个会过冲的 spec，也不会把负数交给布局。
 */
@Composable
fun animateDpAtLeast(target: Dp, label: String, min: Dp = 0.dp, spec: AnimationSpec<Dp> = geometrySpring()): State<Dp> {
    val raw = animateDpAsState(target.coerceAtLeast(min), spec, label = label)
    return remember(raw, min) { derivedStateOf { raw.value.coerceAtLeast(min) } }
}

/**
 * 安全的 Float 动画：默认不过冲，结果夹到 [range]（默认 0..1）——用于 alpha、进度比例、`Color.copy(alpha)`、weight。
 * （`Color.copy(alpha = 1.02f)` 会直接抛异常，分段选择器的指示器就是这么个隐患。）
 */
@Composable
fun animateFloatIn(
    target: Float,
    label: String,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    spec: AnimationSpec<Float> = geometrySpring(),
): State<Float> {
    val raw = animateFloatAsState(target.coerceIn(range), spec, label = label)
    return remember(raw, range) { derivedStateOf { raw.value.coerceIn(range) } }
}

/** 纯函数版本（单测用）：几何量夹紧。 */
fun safeDp(v: Dp, min: Dp = 0.dp): Dp = v.coerceAtLeast(min)
fun safeUnit(v: Float, range: ClosedFloatingPointRange<Float> = 0f..1f): Float = if (v.isNaN()) range.start else v.coerceIn(range)

/** 布局与位置变化：柔和。 */
@Composable
@ReadOnlyComposable
fun <T> gentleSpring(): FiniteAnimationSpec<T> =
    if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 320f)

/** 进场：强调减速。 */
@Composable
@ReadOnlyComposable
fun <T> enterTween(duration: Int = Motion.MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> =
    if (reducedMotion) snap() else tween(duration, delay, Motion.Emphasized)

/** 退场：加速离开，时长更短。 */
@Composable
@ReadOnlyComposable
fun <T> exitTween(duration: Int = Motion.SHORT): FiniteAnimationSpec<T> =
    if (reducedMotion) snap() else tween(duration, 0, Motion.Exit)

@Suppress("unused")
internal fun <T> instant(): AnimationSpec<T> = snap()

/**
 * 触感反馈。走平台 View 的 performHapticFeedback：系统“触感反馈”关掉时自动静默，
 * 新常量在旧系统上降级为最接近的老常量。
 */
class Haptics(private val view: View) {
    fun toggleOn() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else confirmOrTap())
    fun toggleOff() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK)
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun segment() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = perform(confirmOrTap())
    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun dragStart() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.LONG_PRESS)
    fun dragEnd() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    private fun confirmOrTap() =
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP

    private fun perform(constant: Int) {
        runCatching { view.performHapticFeedback(constant) }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return androidx.compose.runtime.remember(view) { Haptics(view) }
}
