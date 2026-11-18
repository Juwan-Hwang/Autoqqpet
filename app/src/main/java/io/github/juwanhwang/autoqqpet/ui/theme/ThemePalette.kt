package io.github.juwanhwang.autoqqpet.ui.theme

import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 主题底色推导（v2.1.1：所有预设统一走这里，不再只有海蓝换底色）。
 *
 * 做法：以 v2.1.0 手调的海蓝底色为“模板”（每层的明度与饱和度），换成各主题 swatch 的色相；
 * 饱和度按 swatch 相对海蓝的饱和度等比缩放（石墨这种灰主题底色也接近灰，不会突然变彩色）。
 * 推完逐层检查文字对比度（次要文字 inkMuted、accent 当文字都 ≥ [MIN_CONTRAST]），不够就把明度往外推一点。
 * 纯 Kotlin，无 Android 依赖，单测直接跑。
 */
object ThemePalette {

    /** [soft] = 品牌色浅底；浅色为 null（沿用各主题手调的 lightSoft：浅色卡片是白的，手调值本来就对白底调过）。 */
    data class Surfaces(val canvas: Long, val surface: Long, val raised: Long, val sunken: Long, val soft: Long?)

    /** 留一点余量：4.5 是 WCAG AA 正文下限。 */
    const val MIN_CONTRAST = 4.6

    /** 海蓝的 swatch（模板的饱和度基准）。 */
    private const val REF_SWATCH = 0xFF2F6FB3

    // 模板：v2.1.0 海蓝手调值的 HSL 明度/饱和度（浅色卡片/浮层保持纯白）
    private val LIGHT_CANVAS = 0xFFE3EFF9
    private val LIGHT_SUNKEN = 0xFFEAF2FA
    private val DARK_CANVAS = 0xFF0A1726
    private val DARK_SURFACE = 0xFF13243A
    private val DARK_RAISED = 0xFF182B44
    private val DARK_SUNKEN = 0xFF08131F
    /** 深色的品牌色浅底：卡片也染了色，浅底得跟着推，不然和卡片糊成一片。 */
    private val DARK_SOFT = 0xFF1C3552

    fun light(swatch: Long, accentText: Long, inkMuted: Long): Surfaces {
        val texts = listOf(inkMuted, accentText)
        return Surfaces(
            canvas = derive(swatch, LIGHT_CANVAS, texts, lighter = true),
            surface = 0xFFFFFFFF,
            raised = 0xFFFFFFFF,
            sunken = derive(swatch, LIGHT_SUNKEN, texts, lighter = true),
            soft = null,
        )
    }

    fun dark(swatch: Long, accentText: Long, inkMuted: Long): Surfaces {
        val texts = listOf(inkMuted, accentText)
        return Surfaces(
            canvas = derive(swatch, DARK_CANVAS, texts, lighter = false),
            surface = derive(swatch, DARK_SURFACE, texts, lighter = false),
            raised = derive(swatch, DARK_RAISED, texts, lighter = false),
            sunken = derive(swatch, DARK_SUNKEN, texts, lighter = false),
            soft = derive(swatch, DARK_SOFT, listOf(accentText), lighter = false),
        )
    }

    /** 模板层 [template] 换成 [swatch] 的色相；对比度不够就往 [lighter] 方向推明度。 */
    private fun derive(swatch: Long, template: Long, texts: List<Long>, lighter: Boolean): Long {
        val (h, s, _) = hsl(swatch)
        val (_, refS, _) = hsl(REF_SWATCH)
        val (_, ts, tl) = hsl(template)
        val sat = ts * min(1.0, if (refS > 0) s / refS else 0.0)
        var l = tl
        var out = fromHsl(h, sat, l)
        var guard = 0
        while (texts.any { contrast(it, out) < MIN_CONTRAST } && guard++ < 60) {
            l = if (lighter) min(1.0, l + 0.005) else max(0.0, l - 0.005)
            out = fromHsl(h, sat, l)
        }
        return out
    }

    // ── 色彩小工具（ARGB Long）────────────────────────

    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun luminance(c: Long): Double {
        fun ch(v: Long): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    /** → (色相 0..1, 饱和度 0..1, 明度 0..1)。 */
    fun hsl(c: Long): Triple<Double, Double, Double> {
        val r = ((c shr 16) and 0xFF) / 255.0; val g = ((c shr 8) and 0xFF) / 255.0; val b = (c and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2
        if (mx == mn) return Triple(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6
            g -> ((b - r) / d + 2) / 6
            else -> ((r - g) / d + 4) / 6
        }
        return Triple(h, s, l)
    }

    fun fromHsl(h: Double, s: Double, l: Double): Long {
        fun hue(p: Double, q: Double, t0: Double): Double {
            var t = t0; if (t < 0) t += 1.0; if (t > 1) t -= 1.0
            return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p }
        }
        val (r, g, b) = if (abs(s) < 1e-9) Triple(l, l, l) else {
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            Triple(hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3))
        }
        fun to8(x: Double) = Math.round(x.coerceIn(0.0, 1.0) * 255).toLong()
        return 0xFF000000L or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }
}

/** Compose Color → ARGB Long（sRGB）。 */
internal fun androidx.compose.ui.graphics.Color.argb(): Long =
    (toArgb().toLong() and 0xFFFFFFFFL)
