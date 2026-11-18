package io.github.juwanhwang.autoqqpet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「QQ 的后台限制」真查 QQ 的电池状态（isIgnoringBatteryOptimizations 对任意包都能查）：
 * 不受限制 = OK、受限 = WARN 并给跳转、查不到 = UNKNOWN 并给跳转。没有手动确认。
 */
class DiagnosticsQqBatteryTest {

    @Test
    fun ignoringIsOk() {
        val ch = qqBatteryCheck(state = true, openBattery = {})
        assertEquals("QQ 的后台限制", ch.title)
        assertEquals(Verdict.OK, ch.verdict)
        assertNull(ch.action)
        assertNull(ch.action2)
    }

    @Test
    fun restrictedIsWarnWithSettingsAction() {
        var opened = false
        val ch = qqBatteryCheck(state = false, openBattery = { opened = true })
        assertEquals("QQ 的后台限制", ch.title)
        assertEquals(Verdict.WARN, ch.verdict)
        assertEquals("去设置", ch.action)
        assertNotNull(ch.run)
        ch.run!!.invoke()
        assertEquals(true, opened)
    }

    @Test
    fun unknownIsUnknownWithSettingsAction() {
        var opened = false
        val ch = qqBatteryCheck(state = null, openBattery = { opened = true })
        assertEquals("QQ 的后台限制", ch.title)
        assertEquals(Verdict.UNKNOWN, ch.verdict)
        assertEquals("去设置", ch.action)
        assertNotNull(ch.run)
        ch.run!!.invoke()
        assertEquals(true, opened)
    }
}
