package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.hook.LoaderProbes
import io.github.juwanhwang.autoqqpet.optimizer.Alerts
import io.github.juwanhwang.autoqqpet.optimizer.CfgCheck
import io.github.juwanhwang.autoqqpet.optimizer.RunnerConfig
import io.github.juwanhwang.autoqqpet.store.MapPrefs
import io.github.juwanhwang.autoqqpet.store.PrefsXml
import io.github.juwanhwang.autoqqpet.store.SettingsStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R7/R10 与提醒划掉的纯逻辑单测。 */
class R10Test {

    @Test fun stampGuard() {
        assertTrue(SettingsStamp.accept(0, 0))          // 升级第一次：两边都没戳，照旧灌
        assertTrue(SettingsStamp.accept(5, 0))
        assertFalse(SettingsStamp.accept(0, 5))         // 本地有戳，没戳的旧数据不收
        assertFalse(SettingsStamp.accept(4, 5))         // 旧的不能覆盖新的
        assertTrue(SettingsStamp.accept(5, 5))
        assertTrue(SettingsStamp.accept(6, 5))
    }

    @Test fun prefsXmlParse() {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="recall_on" value="true" />
    <int name="pk_max" value="-1" />
    <long name="work_career" value="5" />
    <long name="__at" value="1760500000000" />
    <string name="attr">智力</string>
    <string name="schedule_json">{&quot;v&quot;:1,&quot;s&quot;:&quot;a&lt;b &amp; c&quot;}</string>
    <string name="empty"></string>
    <float name="f" value="1.5" />
</map>"""
        val m = PrefsXml.parse(xml)
        assertEquals(true, m["recall_on"])
        assertEquals(-1, m["pk_max"])
        assertEquals(5L, m["work_career"])
        assertEquals(1760500000000L, m["__at"])
        assertEquals("智力", m["attr"])
        assertEquals("{\"v\":1,\"s\":\"a<b & c\"}", m["schedule_json"])
        assertEquals("", m["empty"])
        assertEquals(1.5f, m["f"])
    }

    @Test fun cfgCheckFindsRecallMismatch() {
        val disk = mapOf<String, Any?>("recall_on" to true, "attr" to "智力")
        val memory = mapOf<String, Any?>("recall_on" to true, "attr" to "智力")
        val stale = RunnerConfig.fromPrefs(MapPrefs(disk)).copy(recallEnabled = false)
        val r = CfgCheck.compare(disk, memory, stale, 1L)
        assertFalse(r.ok)
        assertTrue(r.diskVsMemory.isEmpty())
        assertTrue(r.runnerVsDisk.any { it.contains("召回=关") })
        val ok = CfgCheck.compare(disk, memory, RunnerConfig.fromPrefs(MapPrefs(disk)), 1L)
        assertTrue(ok.ok)
        assertEquals("一致", ok.line())
        val other = CfgCheck.compare(disk, mapOf("recall_on" to false, "attr" to "智力"), null, 1L)
        assertEquals(listOf("recall_on: 磁盘=true 内存=false"), other.diskVsMemory)
        assertFalse(CfgCheck.compare(null, memory, null, 1L).diskRead)
    }

    @Test fun cmdlineName() {
        assertEquals("com.tencent.mobileqq", LoaderProbes.parseCmdline("com.tencent.mobileqq\u0000\u0000".toByteArray()))
        assertEquals("com.tencent.mobileqq:MSF", LoaderProbes.parseCmdline("com.tencent.mobileqq:MSF".toByteArray()))
        assertNull(LoaderProbes.parseCmdline(byteArrayOf(0, 0)))
        assertEquals(false, LoaderProbes.isMainProcess("com.tencent.mobileqq:tool"))
        assertEquals(true, LoaderProbes.isMainProcess("com.tencent.mobileqq"))
        assertNull(LoaderProbes.isMainProcess(null))
    }

    @Test fun alertDismissSticksPerIncident() {
        val a = Alerts()
        var saved: Map<String, Long> = emptyMap()
        a.persist = { saved = it }
        a.set(Alerts.RECALL, "去召回 50%", incident = "6400_a")
        assertTrue(a.dismiss(Alerts.RECALL, "6400_a"))
        assertTrue(a.all().isEmpty())
        a.set(Alerts.RECALL, "去召回 80%", incident = "6400_a")      // 同一事件：不再出现
        assertTrue(a.all().isEmpty())
        a.set(Alerts.RECALL, "去召回 10%", incident = "6400_b")      // 新事件：重新提醒
        assertEquals("6400_b", a.all().single().incident)
        // 持久化往返
        val b = Alerts(); b.loadDismissed(Alerts.decode(Alerts.encode(saved)))
        b.set(Alerts.RECALL, "x", incident = "6400_a")
        assertTrue(b.all().isEmpty())
        // 默认事件键=当天
        a.set(Alerts.PAY_TIRED, "累了", Alerts.Level.INFO)
        assertEquals(Alerts.dayKey(), a.get(Alerts.PAY_TIRED)!!.incident)
        a.dismiss(Alerts.PAY_TIRED, null)
        a.set(Alerts.PAY_TIRED, "累了（文本变了）", Alerts.Level.INFO)
        assertNull(a.get(Alerts.PAY_TIRED))
        a.forgetDismissed(Alerts.PAY_TIRED)
        a.set(Alerts.PAY_TIRED, "累了", Alerts.Level.INFO)
        assertTrue(a.get(Alerts.PAY_TIRED) != null)
    }

    @Test fun mailAlertCarriesSource() {
        val a = Alerts()
        a.set(Alerts.MAIL, "去 QQ 领", Alerts.Level.INFO, src = Alerts.SRC_MAIL_CLAIM)
        assertEquals("mailclaim", a.get(Alerts.MAIL)!!.src)
    }
}
