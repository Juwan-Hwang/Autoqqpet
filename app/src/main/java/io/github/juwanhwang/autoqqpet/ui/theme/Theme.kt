package io.github.juwanhwang.autoqqpet.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.juwanhwang.autoqqpet.ModulePrefs

val LocalAevumColors = staticCompositionLocalOf { LightColors }
val LocalReducedMotion = staticCompositionLocalOf { false }

/** 取令牌的统一入口：`Aevum.colors.ink`。 */
object Aevum {
    val colors: AevumColors
        @Composable get() = LocalAevumColors.current
}

/**
 * 界面自用的偏好（只影响外观，不下发给 QQ）。可观察：改了立刻重组。
 * 键名沿用旧版（ui_theme_mode），老用户的主题选择升级后不丢。
 */
object UiPrefs {
    const val KEY_THEME = "ui_theme_mode"          // system | light | dark
    /** 预设主题色（2026-10-15 取代“跟随壁纸取色”）：teal | ocean | pine | amber | coral | graphite，默认 teal。 */
    const val KEY_ACCENT = "ui_theme_accent"
    /** 已删除的旧键（跟随壁纸取色）：旧值忽略，[init] 里一次性移除（幂等）。 */
    const val KEY_DYNAMIC_LEGACY = "ui_dynamic_color"
    const val KEY_REDUCE_MOTION = "ui_reduce_motion"
    private const val KEY_OCEAN_MIGRATED = "ui_ocean_default_v21"

    var theme by mutableStateOf("system")
        private set
    var accent by mutableStateOf(AccentTheme.DEFAULT)
        private set
    var reduceMotion by mutableStateOf(false)
        private set

    private var sp: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            KEY_THEME, KEY_ACCENT, KEY_REDUCE_MOTION -> read(p)
        }
    }

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(ModulePrefs.NAME, Context.MODE_PRIVATE)
        sp = p
        migrate(p)
        read(p)
        p.registerOnSharedPreferenceChangeListener(listener)
    }

    /** 幂等迁移：删掉旧的“跟随壁纸取色”键；不认识的主题色回默认。 */
    fun migrate(p: SharedPreferences) {
        val ed = p.edit(); var n = 0
        if (p.contains(KEY_DYNAMIC_LEGACY)) { ed.remove(KEY_DYNAMIC_LEGACY); n++ }
        val a = p.getString(KEY_ACCENT, null)
        if (a != null && AccentTheme.of(a).key != a) { ed.putString(KEY_ACCENT, AccentTheme.DEFAULT.key); n++ }
        // v2.1：默认改海蓝。一次性把“还停在旧默认青碧”的切到海蓝（之后用户再选青碧会被尊重）
        if (!p.getBoolean(KEY_OCEAN_MIGRATED, false)) {
            if (a == null || a == AccentTheme.TEAL.key) { ed.putString(KEY_ACCENT, AccentTheme.OCEAN.key) }
            ed.putBoolean(KEY_OCEAN_MIGRATED, true); n++
        }
        if (n > 0) ed.apply()
    }

    private fun read(p: SharedPreferences) {
        theme = p.getString(KEY_THEME, "system") ?: "system"
        accent = AccentTheme.of(p.getString(KEY_ACCENT, null))
        reduceMotion = p.getBoolean(KEY_REDUCE_MOTION, false)
    }

    /** 单测/截图用：直接指定主题色。 */
    fun setAccentForPreview(a: AccentTheme) { accent = a }
}

@Composable
fun AevumTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (UiPrefs.theme) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val target = remember(dark, UiPrefs.accent) { UiPrefs.accent.apply(if (dark) DarkColors else LightColors, dark) }
    // 每次组合都读一次：用户在系统里切了「移除动画」，回到应用就生效
    val reduced = UiPrefs.reduceMotion || Motion.systemReduced(context)
    // 切主题色平滑过渡（减少动效时瞬时）
    val spec = if (reduced) androidx.compose.animation.core.snap<androidx.compose.ui.graphics.Color>() else androidx.compose.animation.core.tween(Motion.MEDIUM)
    val accent by androidx.compose.animation.animateColorAsState(target.accent, spec, label = "accent")
    val accentSoft by androidx.compose.animation.animateColorAsState(target.accentSoft, spec, label = "accentSoft")
    val canvas by androidx.compose.animation.animateColorAsState(target.canvas, spec, label = "canvas")
    val surface by androidx.compose.animation.animateColorAsState(target.surface, spec, label = "surface")
    val raised by androidx.compose.animation.animateColorAsState(target.surfaceRaised, spec, label = "raised")
    val sunken by androidx.compose.animation.animateColorAsState(target.sunken, spec, label = "sunken")
    val colors = target.copy(accent = accent, accentSoft = accentSoft, canvas = canvas, surface = surface, surfaceRaised = raised, sunken = sunken)

    val material = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.canvas, surface = colors.surface, onSurface = colors.ink,
            onSurfaceVariant = colors.inkMuted, outline = colors.lineStrong, outlineVariant = colors.line,
            surfaceContainerLow = colors.surface, surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surfaceRaised, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.canvas, surface = colors.surface, onSurface = colors.ink,
            onSurfaceVariant = colors.inkMuted, outline = colors.lineStrong, outlineVariant = colors.line,
            surfaceContainerLow = colors.surface, surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surfaceRaised, error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalAevumColors provides colors,
        LocalReducedMotion provides reduced,
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/**
 * 预设主题色（克制、不用紫/靛）。每个主题浅色/深色单独调 accent：
 *  - 浅色 accent 在白底上放白字 ≥ 4.5:1（按钮），在画布上当文字 ≥ 4.5:1；
 *  - 深色 accent 提亮，onAccent 用深色，≥ 4.5:1。
 * **底色（画布/卡片/浮层/凹槽）所有主题走同一条路**（v2.1.1，之前只有海蓝换底色）：
 * 由 [swatch] 的色相按海蓝那套明度/饱和度推出来，见 [ThemePalette]；推完再按文字对比度兜底。
 * [swatch] 是色板上显示的品牌色（用户给的色值）。
 */
enum class AccentTheme(
    val key: String, val label: String, val swatch: Long,
    val light: Long, val lightSoft: Long,
    val dark: Long, val darkOn: Long,
) {
    TEAL("teal", "青碧", 0xFF0E8C83, 0xFF0B7D75, 0xFFE2F2EF, 0xFF5FD3C6, 0xFF0B1F1D),
    OCEAN("ocean", "海蓝", 0xFF2F6FB3, 0xFF2A64A3, 0xFFD5E5F5, 0xFF8AB8EC, 0xFF0C1828),
    PINE("pine", "松绿", 0xFF4C8A3F, 0xFF3F7A33, 0xFFE7F1E3, 0xFF93CF83, 0xFF0F1D0C),
    AMBER("amber", "琥珀", 0xFFC27A12, 0xFF9A5F06, 0xFFF7ECDB, 0xFFF0B455, 0xFF241604),
    CORAL("coral", "珊瑚", 0xFFD2594A, 0xFFB8473A, 0xFFF8E6E3, 0xFFFF9A8B, 0xFF2A0F0B),
    GRAPHITE("graphite", "石墨", 0xFF4A5560, 0xFF4A5560, 0xFFE8EBEE, 0xFFB9C3CD, 0xFF14181C);

    /** 这个主题的整套底色（纯计算、可单测）。 */
    fun palette(dark: Boolean): ThemePalette.Surfaces =
        if (dark) ThemePalette.dark(swatch, accentText = this.dark, inkMuted = DarkColors.inkMuted.argb())
        else ThemePalette.light(swatch, accentText = light, inkMuted = LightColors.inkMuted.argb())

    fun apply(base: AevumColors, dark: Boolean): AevumColors {
        fun c(v: Long) = androidx.compose.ui.graphics.Color(v)
        val p = palette(dark)
        return base.copy(
            accent = c(if (dark) this.dark else light),
            onAccent = c(if (dark) darkOn else 0xFFFFFFFF),
            accentSoft = c(p.soft ?: lightSoft),
            canvas = c(p.canvas), surface = c(p.surface), surfaceRaised = c(p.raised), sunken = c(p.sunken),
        )
    }

    companion object {
        /** 默认海蓝（v2.1 起；v2.0 默认青碧）。 */
        val DEFAULT = OCEAN
        fun of(key: String?): AccentTheme = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
