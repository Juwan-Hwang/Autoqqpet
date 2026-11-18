package io.github.juwanhwang.autoqqpet.ui

import io.github.juwanhwang.autoqqpet.ui.AlertActions.Act
import io.github.juwanhwang.autoqqpet.ui.data.FullSnapshot
import io.github.juwanhwang.autoqqpet.ui.settings.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主页提醒条：每种提醒的去向（不再一律“看详情 → 诊断”）。 */
class AlertActionsTest {
    @Test fun perAlertActions() {
        assertEquals(listOf(Act.OPEN_QQ, Act.DISMISS), AlertActions.forId("mail").map { it.act })
        assertEquals(listOf("去 QQ 领", "知道了"), AlertActions.forId("mail").map { it.label })
        assertEquals(listOf(Act.RECALL), AlertActions.forId("recall").map { it.act })
        assertEquals(Group.CARE, AlertActions.forId("care_blocked").single().group)
        assertEquals(Group.CARE, AlertActions.forId("idle").single().group)
        assertEquals(Group.MONEY, AlertActions.forId("fee").single().group)
        assertTrue(AlertActions.forId("fee").single().label.contains("金币与雇佣"))
        assertEquals(listOf(Act.DISMISS), AlertActions.forId("pay_tired").map { it.act })
        assertEquals(Act.SPARK, AlertActions.forId("spark").single().act)
        assertEquals(Act.SPARK, AlertActions.forId("spark_tonight").single().act)
        assertEquals(Act.DIAGNOSE, AlertActions.forId("whatever").single().act)
        assertEquals(Act.RECALL, AlertActions.forLegacy("疑似被好友雇佣，去召回").single().act)
        assertEquals(Act.DISMISS, AlertActions.forLegacy("宠物疲劳").single().act)
    }

    /** R7：旧版按附件数挂的信箱提醒（没有 src）不显示；0x9c19 来的照常。 */
    @Test fun staleMailAlertDropped() {
        val old = FullSnapshot.parse("""{"v":1,"alerts":[{"id":"mail","level":"info","text":"信箱有 6 封","at":1}]}""")!!
        assertTrue(old.alerts.isEmpty())
        val ok = FullSnapshot.parse("""{"v":1,"alerts":[{"id":"mail","level":"info","text":"去领","at":1,"incident":"d1","src":"mailclaim"}]}""")!!
        assertEquals("d1", ok.alerts.single().incident)
    }
}
