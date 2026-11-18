package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.Alerts
import io.github.juwanhwang.autoqqpet.optimizer.PayMonitor
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import io.github.juwanhwang.autoqqpet.pet.DailyReport
import io.github.juwanhwang.autoqqpet.pet.Day
import io.github.juwanhwang.autoqqpet.pet.HirePolicy
import io.github.juwanhwang.autoqqpet.pet.JobInfo
import io.github.juwanhwang.autoqqpet.pet.Reward
import io.github.juwanhwang.autoqqpet.pet.Settlement
import io.github.juwanhwang.autoqqpet.pet.TaskManager
import io.github.juwanhwang.autoqqpet.pet.StoryStatus
import io.github.juwanhwang.autoqqpet.store.SettingKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTest {

    @Test fun dayBoundaryIsLocalNotUtc() {
        val sh = ZoneId.of("Asia/Shanghai")
        // 北京时间 10-15 00:30 = UTC 10-14 16:30：必须算 10-15（旧 UTC 口径会算成 10-14）
        val t = ZonedDateTime.of(2026, 10, 15, 0, 30, 0, 0, sh).toInstant().toEpochMilli()
        val before = ZonedDateTime.of(2026, 10, 14, 23, 59, 0, 0, sh).toInstant().toEpochMilli()
        assertEquals(Day.of(before, sh) + 1, Day.of(t, sh))
        assertEquals(java.time.LocalDate.of(2026, 10, 15).toEpochDay().toInt(), Day.of(t, sh))
        assertTrue((t / 86_400_000L).toInt() != Day.of(t, sh))
    }

    private fun job(sub: Long, points: Int, min: Int) = JobInfo("j$sub", 1, sub, 0, false, points, 0, 0, min, min * 60L, points, true)

    @Test fun payDropPerTier() {
        val peaks = HashMap<String, Int>()
        val peak: (String, Int) -> Int = { k, v -> val prev = peaks[k] ?: 0; if (v > prev) peaks[k] = v; prev }
        // 第一次读：建立基准，不判
        assertTrue(PayMonitor.assess(emptyList(), listOf(job(1, 14, 45), job(2, 3, 10)), emptySet(), peak).penalized.isEmpty())
        // 45min 档 14→10（降 28%），10min 档不变 → 只剔 45min 档，没“全降”
        // 2026-10-15：数值降幅只作展示（决策已按当前报酬排序），不剔档
        val a = PayMonitor.assess(emptyList(), listOf(job(1, 10, 45), job(2, 3, 10)), emptySet(), peak)
        assertTrue(a.penalized.isEmpty()); assertEquals(1, a.droppedCount); assertFalse(a.allGone)
        assertEquals(setOf(1L), PayMonitor.assessRaw(emptyList(), listOf(job(1, 9, 45), job(2, 3, 10)), emptySet(), peak).penalized)
    }

    @Test fun payDropIgnoresSmallJitter() {
        val peaks = hashMapOf("s7" to 6100)
        val c = CourseInfo("c", 7, Attr.STRENGTH, 30, 0, 0, 0, 30, 1800, true)   // 60.0/h vs 峰值 61.0：差 <1 单位
        assertTrue(PayMonitor.assess(listOf(c), emptyList(), emptySet()) { k, _ -> peaks[k] ?: 0 }.penalized.isEmpty())
    }

    @Test fun settlementDrop() {
        val s = Settlement("x", emptyList(), listOf(Reward("工分", 10)))
        assertNotNull(PayMonitor.settlementDrop("work", 14, s))
        assertNull(PayMonitor.settlementDrop("work", 10, s))
        assertNull(PayMonitor.settlementDrop("adventure", 14, s))
        assertNull(PayMonitor.settlementDrop("work", 14, Settlement("x", emptyList(), emptyList())))   // 没认出 ≠ 降了
    }

    @Test fun hirePolicy() {
        assertFalse(HirePolicy.judge(false, 0, 3).hire)
        assertTrue(HirePolicy.judge(true, 0, 3).hire)        // 雇人不花钱、不看金币留底：只看开关与次数
        assertFalse(HirePolicy.judge(true, 3, 3).hire)
    }

    @Test fun recallIgnoresServerRecallableFlag() {
        // 2026-10-15 被雇召回实测：.5 recallable 缺省，召回照样成功 → 只看进度
        val base = StoryStatus("6400_x", 51, 300, 2700, 0, recallable = false)
        assertTrue(TaskManager.shouldRecall(base, "best_split"))          // 进度 89% ≥ 71%
        assertFalse(TaskManager.shouldRecall(base.copy(remainingSeconds = 2000), "best_split"))
        assertTrue(TaskManager.shouldRecall(base.copy(remainingSeconds = 2000), "immediate"))
        assertFalse(TaskManager.shouldRecall(base.copy(remainingSeconds = 0), "immediate"))
    }

    @Test fun settingKeysRegistry() {
        val keys = SettingKeys.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        SettingKeys.ALL.forEach { d ->
            val ok = when (d.type) {
                SettingKeys.Type.BOOL -> d.default is Boolean
                SettingKeys.Type.INT -> d.default is Int
                SettingKeys.Type.LONG -> d.default is Long
                SettingKeys.Type.STRING -> d.default is String
            }
            assertTrue("默认值类型不符：${d.key}", ok)
            assertEquals("默认值必须能通过自己的校验：${d.key}", d.default, SettingKeys.coerce(d, d.default))
        }
        assertEquals(5L, SettingKeys.coerce(SettingKeys.WORK_CAREER, 5))          // JSON 的 Int → Long
        assertEquals(true, SettingKeys.coerce(SettingKeys.HIRE_ON, "on"))
        assertNull(SettingKeys.coerce(SettingKeys.ADV_MODE, "off"))               // 撤下的挡不接受
        assertNull(SettingKeys.coerce(SettingKeys.STAMINA_FLOOR, "150"))          // 越界
        // v1 的键名一个都不能少（兼容旧界面与主存）
        listOf("auto_on", "attr", "adv_mode", "adv_max", "work_career", "hire_alert_on", "recall_on", "recall_mode", "hire_on", "schedule_json")
            .forEach { assertNotNull(it, SettingKeys.def(it)) }
    }

    @Test fun dailyReport() {
        val json = DailyReport.build(20375, mapOf("work" to 4, "hire" to 1, "hire_spent" to 250), 180, 3, 1000, 1350, mapOf("工分" to 12))
        val o = JSONObject(json)
        assertEquals(350, o.getInt("gold_delta"))
        assertEquals(250, o.getInt("hire_spent"))
        assertEquals(12, o.getJSONObject("rewards").getInt("工分"))
        assertTrue(DailyReport.oneLine(json).contains("金币+350"))
        assertEquals(0, JSONObject(DailyReport.build(1, emptyMap(), 0, 0, -1, 500, emptyMap())).getInt("gold_delta"))
    }

    @Test fun alertsSlots() {
        val a = Alerts()
        a.set(Alerts.RECALL, "去召回")
        a.set(Alerts.MAIL, "有信", Alerts.Level.INFO)
        assertEquals("去召回", a.legacyText())
        a.clear(Alerts.RECALL)
        assertNull(a.legacyText())             // INFO 不进通知通道
        assertEquals(1, a.all().size)
    }
}
