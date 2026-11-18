package io.github.juwanhwang.autoqqpet.ui

import android.graphics.Color as AColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.screens.AdSweepScreen
import io.github.juwanhwang.autoqqpet.ui.screens.AboutScreen
import io.github.juwanhwang.autoqqpet.ui.screens.DiagnosticsScreen
import io.github.juwanhwang.autoqqpet.ui.screens.HomeScreen
import io.github.juwanhwang.autoqqpet.ui.screens.LicenseScreen
import io.github.juwanhwang.autoqqpet.ui.screens.OnboardingScreen
import io.github.juwanhwang.autoqqpet.ui.screens.ScheduleScreen
import io.github.juwanhwang.autoqqpet.ui.screens.SettingsScreen
import io.github.juwanhwang.autoqqpet.ui.screens.SparkScreen
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumTheme
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion

/** 页面。单 Activity + 自管返回栈：页面切换才能做连续的空间动效，且不必为每页登记 Activity。 */
enum class Route { HOME, SETTINGS, SCHEDULE, DIAGNOSTICS, ABOUT, LICENSE, SPARK, AD_SWEEP }

class Navigator {
    val stack = mutableStateListOf(Route.HOME)
    var forward by mutableStateOf(true)
        private set

    val current: Route get() = stack.last()

    fun push(r: Route) {
        if (stack.last() == r) return
        forward = true
        stack.add(r)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

/**
 * 根：先过合规闸（免费与风险声明 → 协议读到底才可同意；协议文本一变就重新确认），再进主界面。
 */
@Composable
fun AevumApp(live: LiveState, navigator: Navigator, gate: AgreementGate, onExit: () -> Unit) {
    AevumTheme {
        SystemBarsEffect()
        Box(Modifier.fillMaxSize().background(Aevum.colors.canvas)) {
            val reduced = reducedMotion
            AnimatedContent(
                targetState = gate.agreed,
                transitionSpec = {
                    if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else (fadeIn(tween(Motion.LONG, easing = Motion.Emphasized)) + scaleIn(tween(Motion.LONG, easing = Motion.Emphasized), initialScale = 1.04f)) togetherWith
                        (fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.SHORT), targetScale = 0.96f))
                },
                label = "gate",
            ) { agreed ->
                // 源码公开版停用协议确认页：启动直接进主界面；原拦截逻辑原样保留在下方注释里。
                // if (!agreed) {
                //     OnboardingScreen(
                //         returning = gate.returning,
                //         onAgreed = { gate.markAgreed() },
                //         onExit = onExit,
                //     )
                // } else {
                    MainHost(live, navigator)
                // }
            }
        }
    }
}

/** 合规状态（可观察）。 */
class AgreementGate(private val activity: ComponentActivity, private val onAgreed: () -> Unit = {}) {
    // 源码公开版停用协议确认页：视为已同意，不再拦截；原写法保留在注释里。
    // var agreed by mutableStateOf(EulaTerms.agreed(activity))
    var agreed by mutableStateOf(true)
        private set

    /** 以前同意过旧版本（协议改版）——引导页直接进协议，不再重复免费声明。 */
    val returning: Boolean
        get() = activity.getSharedPreferences(MainActivity.PREFS, android.content.Context.MODE_PRIVATE)
            .getString(EulaTerms.KEY_DIGEST, null) != null

    fun markAgreed() {
        EulaTerms.markAgreed(activity)
        activity.getSharedPreferences(MainActivity.PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(MainActivity.KEY_NOTICE_SEEN, true).apply()
        agreed = true
        onAgreed()
    }

    fun recheck() {
        // 源码公开版停用协议确认页：不再按协议指纹回退到确认页；原逻辑保留在注释里。
        // agreed = EulaTerms.agreed(activity)
    }
}

@Composable
private fun MainHost(live: LiveState, nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    val reduced = reducedMotion
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    AnimatedContent(
        targetState = nav.current,
        transitionSpec = { routeTransition(nav.forward, reduced) },
        label = "nav",
    ) { route ->
        holder.SaveableStateProvider(route.name) {
            val back: () -> Unit = { nav.pop() }
            when (route) {
                Route.HOME -> HomeScreen(live, onOpen = { nav.push(it) })
                Route.SETTINGS -> SettingsScreen(live, onBack = back, onOpen = { nav.push(it) })
                Route.SCHEDULE -> ScheduleScreen(live, onBack = back)
                Route.DIAGNOSTICS -> DiagnosticsScreen(live, onBack = back)
                Route.ABOUT -> AboutScreen(onBack = back, onOpen = { nav.push(it) })
                Route.LICENSE -> LicenseScreen(onBack = back)
                Route.SPARK -> SparkScreen(live, onBack = back)
                Route.AD_SWEEP -> AdSweepScreen(live, onBack = back)
            }
        }
    }
}

/** 进深方向的共享轴：前进时新页从右侧 1/6 处滑入，旧页略缩并淡出；返回反过来。 */
private fun routeTransition(forward: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val enterSpec = tween<androidx.compose.ui.unit.IntOffset>(Motion.MEDIUM + 60, easing = Motion.Emphasized)
    val exitSpec = tween<androidx.compose.ui.unit.IntOffset>(Motion.MEDIUM, easing = Motion.Emphasized)
    return if (forward) {
        (slideInHorizontally(enterSpec) { it / 6 } + fadeIn(tween(Motion.MEDIUM, 40, Motion.Standard))) togetherWith
            (slideOutHorizontally(exitSpec) { -it / 12 } + fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.MEDIUM), targetScale = 0.97f))
    } else {
        (slideInHorizontally(enterSpec) { -it / 12 } + fadeIn(tween(Motion.MEDIUM, 40, Motion.Standard)) + scaleIn(tween(Motion.MEDIUM), initialScale = 0.97f)) togetherWith
            (slideOutHorizontally(exitSpec) { it / 6 } + fadeOut(tween(Motion.SHORT)))
    }
}

/** 状态栏/导航栏图标颜色跟随应用内主题（而不是系统主题）。 */
@Composable
private fun SystemBarsEffect() {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val dark = Aevum.colors.isDark
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
        else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
