package io.github.juwanhwang.autoqqpet.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌 · 色彩。规则见 docs/design/DESIGN.md「色彩」。
 *
 * 一句话：纸与墨做底，颜色只留给“宠物此刻在做什么”。
 * 界面里不出现写死的色值，全部从 [AevumColors] 取。
 */
@Immutable
data class AevumColors(
    val canvas: Color,        // 页面底
    val surface: Color,       // 卡片
    val surfaceRaised: Color, // 浮层（弹窗、底部面板）
    val sunken: Color,        // 卡片内的槽（数值磁贴、输入框）
    val line: Color,          // 发丝线
    val lineStrong: Color,
    val ink: Color,           // 正文
    val inkMuted: Color,      // 次要文字（对 canvas ≥ 4.5:1）
    val inkFaint: Color,      // 仅装饰/禁用（不承载信息）
    val accent: Color,        // 品牌色：选中、主按钮、焦点
    val onAccent: Color,
    val accentSoft: Color,    // 品牌色的浅底
    val study: Color,         // 学习
    val work: Color,          // 打工
    val adventure: Color,     // 冒险
    val care: Color,          // 照顾
    val rest: Color,          // 空闲/暂停
    val success: Color,
    val warning: Color,
    val danger: Color,
    val scrim: Color,
    val isDark: Boolean,
)

internal val LightColors = AevumColors(
    canvas = Color(0xFFF6F5F2),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    sunken = Color(0xFFEFEDE8),
    line = Color(0xFFE6E3DC),
    lineStrong = Color(0xFFD6D2C9),
    ink = Color(0xFF17161B),
    inkMuted = Color(0xFF66636D),
    inkFaint = Color(0xFFA5A2AA),
    accent = Color(0xFF0B7D75),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE2F2EF),
    study = Color(0xFF2A64A3),
    work = Color(0xFFB35A00),
    adventure = Color(0xFF0B7A6E),
    care = Color(0xFFC92A62),
    rest = Color(0xFF66636D),
    success = Color(0xFF12784A),
    warning = Color(0xFFA65A00),
    danger = Color(0xFFC0302B),
    scrim = Color(0x66101014),
    isDark = false,
)

internal val DarkColors = AevumColors(
    canvas = Color(0xFF0D0D11),
    surface = Color(0xFF16161C),
    surfaceRaised = Color(0xFF1D1D24),
    sunken = Color(0xFF101015),
    line = Color(0xFF26262F),
    lineStrong = Color(0xFF34343F),
    ink = Color(0xFFF3F2F6),
    inkMuted = Color(0xFFA6A4AE),
    inkFaint = Color(0xFF5E5C66),
    accent = Color(0xFF5FD3C6),
    onAccent = Color(0xFF0B1F1D),
    accentSoft = Color(0xFF15302D),
    study = Color(0xFF8AB8EC),
    work = Color(0xFFF2A646),
    adventure = Color(0xFF3FD0BD),
    care = Color(0xFFFF7EAA),
    rest = Color(0xFFA6A4AE),
    success = Color(0xFF4CD992),
    warning = Color(0xFFF5B547),
    danger = Color(0xFFFF6E6A),
    scrim = Color(0x99000000),
    isDark = true,
)

/** 间距：4 的倍数。页面左右留白固定 [gutter]。 */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val section = 40.dp
    val gutter = 20.dp

    /** 可点区域下限（无障碍）。 */
    val touch = 48.dp

    /** 内容最大宽度：平板/横屏不拉满。 */
    val maxContent = 600.dp
}

/** 圆角。越大的容器圆角越大，嵌套时内圆角 = 外圆角 − 内边距。 */
object Radius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
    val sheet = 32.dp
}
