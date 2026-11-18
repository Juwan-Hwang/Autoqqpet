package io.github.juwanhwang.autoqqpet.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自绘线性图标：24 网格、1.8 线宽、圆头圆角。
 * 不引入图标库（体积 + 风格统一）；着色由 Icon 的 tint 决定。
 */
object Glyphs {

    private fun glyph(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.dot(cx: Float, cy: Float) = circle(cx, cy, 0.35f)

    val Back by lazy { glyph("back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    val ChevronRight by lazy { glyph("chevron_right") { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) } }
    val ChevronDown by lazy { glyph("chevron_down") { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) } }
    val Close by lazy { glyph("close") { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) } }
    val Check by lazy { glyph("check") { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) } }
    val Plus by lazy { glyph("plus") { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) } }
    val Minus by lazy { glyph("minus") { moveTo(5f, 12f); horizontalLineTo(19f) } }
    val Grip by lazy {
        glyph("grip") {
            moveTo(7f, 8.5f); horizontalLineTo(17f)
            moveTo(7f, 12f); horizontalLineTo(17f)
            moveTo(7f, 15.5f); horizontalLineTo(17f)
        }
    }
    val More by lazy { glyph("more") { dot(6f, 12f); dot(12f, 12f); dot(18f, 12f) } }

    val Sliders by lazy {
        glyph("sliders") {
            moveTo(4f, 7.5f); horizontalLineTo(12.5f); moveTo(17.5f, 7.5f); horizontalLineTo(20f)
            circle(15f, 7.5f, 2.3f)
            moveTo(4f, 16.5f); horizontalLineTo(6.5f); moveTo(11.5f, 16.5f); horizontalLineTo(20f)
            circle(9f, 16.5f, 2.3f)
        }
    }

    // ── 任务 ──
    val Book by lazy {
        glyph("book") {
            moveTo(12f, 6.5f)
            curveTo(9.6f, 5f, 6.6f, 4.8f, 4f, 5.6f); verticalLineTo(18.6f)
            curveTo(6.6f, 17.8f, 9.6f, 18f, 12f, 19.5f)
            curveTo(14.4f, 18f, 17.4f, 17.8f, 20f, 18.6f); verticalLineTo(5.6f)
            curveTo(17.4f, 4.8f, 14.4f, 5f, 12f, 6.5f); close()
            moveTo(12f, 6.5f); verticalLineTo(19.5f)
        }
    }
    val Briefcase by lazy {
        glyph("briefcase") {
            moveTo(5.5f, 7.5f); horizontalLineTo(18.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); verticalLineTo(17f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(5.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); verticalLineTo(9.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
            moveTo(9f, 7.5f); verticalLineTo(6f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); horizontalLineTo(13.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f); verticalLineTo(7.5f)
            moveTo(3.5f, 12.5f); horizontalLineTo(20.5f)
        }
    }
    val Compass by lazy {
        glyph("compass") {
            circle(12f, 12f, 8.5f)
            moveTo(15.5f, 8.5f); lineTo(13.4f, 13.4f); lineTo(8.5f, 15.5f); lineTo(10.6f, 10.6f); close()
        }
    }
    val Heart by lazy {
        glyph("heart") {
            moveTo(12f, 19.5f)
            curveTo(7f, 16.2f, 4f, 13.2f, 4f, 9.6f)
            curveTo(4f, 7.3f, 5.8f, 5.5f, 8.1f, 5.5f)
            curveTo(9.7f, 5.5f, 11.1f, 6.4f, 12f, 7.8f)
            curveTo(12.9f, 6.4f, 14.3f, 5.5f, 15.9f, 5.5f)
            curveTo(18.2f, 5.5f, 20f, 7.3f, 20f, 9.6f)
            curveTo(20f, 13.2f, 17f, 16.2f, 12f, 19.5f); close()
        }
    }
    val Moon by lazy {
        glyph("moon") {
            moveTo(19f, 14.5f)
            arcToRelative(7.5f, 7.5f, 0f, true, true, -9.5f, -9.5f)
            arcToRelative(6f, 6f, 0f, false, false, 9.5f, 9.5f); close()
        }
    }

    // ── 数值 ──
    val Bolt by lazy {
        glyph("bolt") {
            moveTo(13f, 3f); lineTo(5.5f, 13.5f); horizontalLineTo(11.5f); lineTo(10.5f, 21f)
            lineTo(18.5f, 10.5f); horizontalLineTo(12.5f); close()
        }
    }
    val Drop by lazy {
        glyph("drop") {
            moveTo(12f, 3.5f)
            curveTo(9f, 7.5f, 6.5f, 10.5f, 6.5f, 14f)
            arcToRelative(5.5f, 5.5f, 0f, false, false, 11f, 0f)
            curveTo(17.5f, 10.5f, 15f, 7.5f, 12f, 3.5f); close()
        }
    }
    /** 火花（连续互踩）。 */
    val Flame by lazy {
        glyph("flame") {
            moveTo(12f, 3.5f)
            curveTo(13f, 7f, 17.5f, 9f, 17.5f, 14f)
            arcToRelative(5.5f, 5.5f, 0f, false, true, -11f, 0f)
            curveTo(6.5f, 11f, 8.5f, 9.5f, 9.5f, 8f)
            curveTo(10f, 10f, 11f, 11f, 12f, 11.5f)
            curveTo(12.5f, 9f, 12.5f, 6f, 12f, 3.5f); close()
        }
    }
    val Coin by lazy { glyph("coin") { circle(12f, 12f, 8.5f); circle(12f, 12f, 4.5f) } }
    val Smile by lazy {
        glyph("smile") {
            circle(12f, 12f, 8.5f)
            moveTo(8.5f, 14f); curveTo(9.6f, 15.6f, 14.4f, 15.6f, 15.5f, 14f)
            moveTo(9f, 9.6f); verticalLineTo(10.4f); moveTo(15f, 9.6f); verticalLineTo(10.4f)
        }
    }
    val Sparkle by lazy {
        glyph("sparkle") {
            moveTo(12f, 3.5f); lineTo(13.8f, 10.2f); lineTo(20.5f, 12f); lineTo(13.8f, 13.8f)
            lineTo(12f, 20.5f); lineTo(10.2f, 13.8f); lineTo(3.5f, 12f); lineTo(10.2f, 10.2f); close()
        }
    }

    // ── 设置与系统 ──
    val Bell by lazy {
        glyph("bell") {
            moveTo(6f, 16.5f); verticalLineTo(11f)
            arcToRelative(6f, 6f, 0f, false, true, 12f, 0f); verticalLineTo(16.5f)
            lineTo(19.5f, 18f); horizontalLineTo(4.5f); close()
            moveTo(10f, 20.5f); arcToRelative(2f, 2f, 0f, false, false, 4f, 0f)
        }
    }
    val Users by lazy {
        glyph("users") {
            circle(9f, 8.5f, 3.2f)
            moveTo(3.5f, 19f); curveTo(3.5f, 16f, 6f, 14f, 9f, 14f); curveTo(12f, 14f, 14.5f, 16f, 14.5f, 19f)
            circle(16.8f, 9f, 2.5f)
            moveTo(16.8f, 14f); curveTo(19f, 14f, 20.5f, 15.6f, 20.5f, 18f)
        }
    }
    val Contrast by lazy { glyph("contrast") { circle(12f, 12f, 8.5f); moveTo(12f, 3.5f); verticalLineTo(20.5f); moveTo(12f, 8f); horizontalLineTo(19.5f); moveTo(12f, 12f); horizontalLineTo(20.5f); moveTo(12f, 16f); horizontalLineTo(19.5f) } }
    val Pulse by lazy {
        glyph("pulse") {
            moveTo(3f, 12f); horizontalLineTo(7f); lineTo(9f, 7f); lineTo(13f, 17f); lineTo(15f, 12f); horizontalLineTo(21f)
        }
    }
    val Info by lazy { glyph("info") { circle(12f, 12f, 8.5f); moveTo(12f, 11f); verticalLineTo(16f); dot(12f, 8f) } }
    val Shield by lazy {
        glyph("shield") {
            moveTo(12f, 3.5f); lineTo(19f, 6f); verticalLineTo(11.5f)
            curveTo(19f, 15.5f, 16f, 18.8f, 12f, 20.5f)
            curveTo(8f, 18.8f, 5f, 15.5f, 5f, 11.5f); verticalLineTo(6f); close()
        }
    }
    val External by lazy {
        glyph("external") {
            moveTo(14f, 4.5f); horizontalLineTo(19.5f); verticalLineTo(10f)
            moveTo(19.5f, 4.5f); lineTo(11f, 13f)
            moveTo(18f, 14f); verticalLineTo(18f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f); horizontalLineTo(6.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f); verticalLineTo(8f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); horizontalLineTo(10.5f)
        }
    }
    val Chat by lazy {
        glyph("chat") {
            moveTo(5f, 5.5f); horizontalLineTo(19f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f); verticalLineTo(15f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f); horizontalLineTo(11f)
            lineTo(7f, 20f); verticalLineTo(16.5f); horizontalLineTo(5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f); verticalLineTo(7f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); close()
        }
    }
    val Copy by lazy {
        glyph("copy") {
            moveTo(10f, 9f); horizontalLineTo(18f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f); verticalLineTo(19f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f); horizontalLineTo(10f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f); verticalLineTo(10f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, -1f); close()
            moveTo(6f, 15f); horizontalLineTo(5.5f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f); verticalLineTo(5f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, -1f); horizontalLineTo(14f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f); verticalLineTo(5.5f)
        }
    }
    val Refresh by lazy {
        glyph("refresh") {
            moveTo(19.5f, 12f)
            arcToRelative(7.5f, 7.5f, 0f, true, true, -2.4f, -5.5f)
            moveTo(19.5f, 4f); verticalLineTo(8.5f); horizontalLineTo(15f)
        }
    }
    val Trash by lazy {
        glyph("trash") {
            moveTo(4.5f, 7f); horizontalLineTo(19.5f)
            moveTo(9.5f, 7f); verticalLineTo(5f); horizontalLineTo(14.5f); verticalLineTo(7f)
            moveTo(6.5f, 7f); lineTo(7.5f, 19.5f); horizontalLineTo(16.5f); lineTo(17.5f, 7f)
        }
    }
    val Edit by lazy { glyph("edit") { moveTo(14.5f, 5.5f); lineTo(18.5f, 9.5f); lineTo(9f, 19f); horizontalLineTo(5f); verticalLineTo(15f); close() } }
    val Duplicate by lazy {
        glyph("duplicate") {
            moveTo(8f, 8f); horizontalLineTo(18f); verticalLineTo(18f); horizontalLineTo(8f); close()
            moveTo(13f, 10.5f); verticalLineTo(15.5f); moveTo(10.5f, 13f); horizontalLineTo(15.5f)
            moveTo(5f, 15f); verticalLineTo(5f); horizontalLineTo(15f)
        }
    }
    val Power by lazy {
        glyph("power") {
            moveTo(12f, 3.5f); verticalLineTo(11f)
            moveTo(7.2f, 6.6f); arcToRelative(7.5f, 7.5f, 0f, true, false, 9.6f, 0f)
        }
    }
    val Calendar by lazy {
        glyph("calendar") {
            moveTo(9.5f, 7f); horizontalLineTo(20f); moveTo(9.5f, 12f); horizontalLineTo(20f); moveTo(9.5f, 17f); horizontalLineTo(20f)
            circle(5f, 7f, 1f); circle(5f, 12f, 1f); circle(5f, 17f, 1f)
        }
    }
    val Search by lazy { glyph("search") { circle(11f, 11f, 6.5f); moveTo(16f, 16f); lineTo(20f, 20f) } }
    val Warning by lazy {
        glyph("warning") {
            moveTo(12f, 4f); lineTo(21f, 19.5f); horizontalLineTo(3f); close()
            moveTo(12f, 10f); verticalLineTo(14f); dot(12f, 16.8f)
        }
    }
    val SignalOff by lazy {
        glyph("signal_off") {
            moveTo(2.8f, 9f); arcToRelative(13.5f, 13.5f, 0f, false, true, 18.4f, 0f)
            moveTo(5.8f, 12.4f); arcToRelative(9f, 9f, 0f, false, true, 12.4f, 0f)
            moveTo(8.9f, 15.7f); arcToRelative(4.5f, 4.5f, 0f, false, true, 6.2f, 0f)
            dot(12f, 19f)
            moveTo(4f, 3.5f); lineTo(20f, 20.5f)
        }
    }
    val Clock by lazy { glyph("clock") { circle(12f, 12f, 8.5f); moveTo(12f, 7.5f); verticalLineTo(12f); lineTo(15f, 14f) } }
    val Code by lazy { glyph("code") { moveTo(8.5f, 7f); lineTo(3.5f, 12f); lineTo(8.5f, 17f); moveTo(15.5f, 7f); lineTo(20.5f, 12f); lineTo(15.5f, 17f) } }
    val Lock by lazy {
        glyph("lock") {
            moveTo(6.5f, 10.5f); horizontalLineTo(17.5f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f); verticalLineTo(19f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f); horizontalLineTo(6.5f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f); verticalLineTo(11.5f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, -1f); close()
            moveTo(8.5f, 10.5f); verticalLineTo(8f)
            arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f); verticalLineTo(10.5f)
        }
    }
    val Motion by lazy {
        glyph("motion") {
            circle(15f, 12f, 4.5f)
            moveTo(3.5f, 9f); horizontalLineTo(8f); moveTo(3.5f, 12f); horizontalLineTo(9.5f); moveTo(3.5f, 15f); horizontalLineTo(8f)
        }
    }
    val Palette by lazy {
        glyph("palette") {
            moveTo(12f, 3.5f)
            arcToRelative(8.5f, 8.5f, 0f, true, false, 0f, 17f)
            curveTo(13.4f, 20.5f, 14f, 19.6f, 13.5f, 18.4f)
            curveTo(13f, 17.2f, 13.8f, 16f, 15.2f, 16f); horizontalLineTo(17f)
            arcToRelative(3.5f, 3.5f, 0f, false, false, 3.5f, -3.5f)
            curveTo(20.5f, 7.6f, 16.7f, 3.5f, 12f, 3.5f); close()
            dot(8f, 12f); dot(9.5f, 8f); dot(14f, 7.5f)
        }
    }
    val Target by lazy { glyph("target") { circle(12f, 12f, 8.5f); circle(12f, 12f, 4.5f); dot(12f, 12f) } }
}
