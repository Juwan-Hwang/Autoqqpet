package io.github.juwanhwang.autoqqpet.ui

import io.github.juwanhwang.autoqqpet.ui.theme.AccentTheme
import io.github.juwanhwang.autoqqpet.ui.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1.1：所有预设都换底色（同一条推导路径），且文字对比度都 ≥ 4.5:1。 */
class ThemePaletteTest {

    private val inkLight = 0xFF17161BL
    private val mutedLight = 0xFF66636DL
    private val inkDark = 0xFFF3F2F6L
    private val mutedDark = 0xFFA6A4AEL

    private fun check(name: String, fg: Long, bg: Long) {
        val c = ThemePalette.contrast(fg, bg)
        assertTrue("$name 对比度 ${"%.2f".format(c)} < 4.5", c >= 4.5)
    }

    @Test fun everyPresetTintsBackgroundAndKeepsContrast() {
        AccentTheme.entries.forEach { t ->
            val l = t.palette(dark = false)
            listOf("canvas" to l.canvas, "sunken" to l.sunken, "surface" to l.surface).forEach { (n, bg) ->
                check("${t.key}/浅/$n/ink", inkLight, bg)
                check("${t.key}/浅/$n/inkMuted", mutedLight, bg)
            }
            check("${t.key}/浅/canvas/accent", t.light, l.canvas)
            check("${t.key}/浅/accent 白字", 0xFFFFFFFFL, t.light)

            val d = t.palette(dark = true)
            listOf("canvas" to d.canvas, "surface" to d.surface, "raised" to d.raised, "sunken" to d.sunken).forEach { (n, bg) ->
                check("${t.key}/深/$n/ink", inkDark, bg)
                check("${t.key}/深/$n/inkMuted", mutedDark, bg)
                check("${t.key}/深/$n/accent", t.dark, bg)
            }
            check("${t.key}/深/soft/accent", t.dark, d.soft!!)
            check("${t.key}/深/accent 上的 onAccent", t.darkOn, t.dark)
            // 底色真的带色（不是中性默认），且卡片和画布分得开
            assertNotEquals(0xFFF6F5F2L, l.canvas)
            assertNotEquals(0xFF0D0D11L, d.canvas)
            assertTrue("${t.key} 深色卡片要比画布亮", ThemePalette.luminance(d.surface) > ThemePalette.luminance(d.canvas))
        }
        // 每个主题的画布各不相同 → 切主题背景一定会变
        assertEquals(AccentTheme.entries.size, AccentTheme.entries.map { it.palette(false).canvas }.toSet().size)
        assertEquals(AccentTheme.entries.size, AccentTheme.entries.map { it.palette(true).canvas }.toSet().size)
    }

    /** 海蓝用同一条路推出来，应与 v2.1.0 手调值几乎一致（每通道差 ≤ 3）。 */
    @Test fun oceanMatchesHandTuned() {
        fun near(a: Long, b: Long) = (0..2).all { i -> kotlin.math.abs(((a shr (i * 8)) and 0xFF) - ((b shr (i * 8)) and 0xFF)) <= 3 }
        val l = AccentTheme.OCEAN.palette(false)
        val d = AccentTheme.OCEAN.palette(true)
        assertTrue(near(l.canvas, 0xFFE3EFF9L))
        assertTrue(near(l.sunken, 0xFFEAF2FAL))
        assertTrue(near(d.canvas, 0xFF0A1726L))
        assertTrue(near(d.surface, 0xFF13243AL))
        assertTrue(near(d.raised, 0xFF182B44L))
        assertTrue(near(d.sunken, 0xFF08131FL))
    }

    @Test fun hslRoundTrip() {
        listOf(0xFF2F6FB3L, 0xFFC27A12L, 0xFF4A5560L, 0xFFFFFFFFL, 0xFF000000L).forEach { c ->
            val (h, s, l) = ThemePalette.hsl(c)
            assertEquals(c, ThemePalette.fromHsl(h, s, l))
        }
    }
}
