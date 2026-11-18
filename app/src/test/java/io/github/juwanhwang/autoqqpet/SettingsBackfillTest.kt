package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.store.SettingsStamp
import io.github.juwanhwang.autoqqpet.store.Store
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动补齐（2026-10-19）：整份替换时期被 QQ 上交删掉的设置只剩界面 prefs 里有、主存缺键；
 * 通道修好后存量缺口不自愈——启动只做主存→界面（防 R10），界面→主存只有改动监听的逐键
 * delta，用户不重新拨一次就永远缺着。补齐的安全边界：只补主存完全没有的键，主存已有的
 * 值哪怕与界面不同也绝不覆盖；两边都没有的键不凭空造。
 * 本测试走生产实际用的计算接缝（Store.missingSettingsDelta），再按 mergeSettingsLocal
 * 对可用增量的处理（逐键 put 进主存 JSON）合成结果断言——增量已过同一道可用过滤
 * （登记键、界面值非空、主存缺失必然与现值不同），put 的结果与生产路径一致。
 */
class SettingsBackfillTest {

    /** 把补齐增量按 mergeSettingsLocal 的方式逐键 put 进主存 JSON。 */
    private fun applied(master: JSONObject, ui: Map<String, Any?>): JSONObject {
        val out = JSONObject(master.toString())
        Store.missingSettingsDelta(master, ui).forEach { (k, v) -> out.put(k, v) }
        return out
    }

    @Test fun missingKeyBackfilledFromUi() {
        val master = JSONObject().put("adv_mode", "auto").put(SettingsStamp.KEY, 1_000L)
        val ui = mapOf<String, Any?>("screen_pause_on" to true, "adv_mode" to "only")
        assertEquals(mapOf<String, Any?>("screen_pause_on" to true), Store.missingSettingsDelta(master, ui))
        assertTrue(applied(master, ui).getBoolean("screen_pause_on"))   // 缺的键补上，值 = 界面值
    }

    @Test fun existingMasterKeyNeverOverwritten() {
        val master = JSONObject().put("night_pause_start", 90)
        val ui = mapOf<String, Any?>("night_pause_start" to 1380)
        assertTrue(Store.missingSettingsDelta(master, ui).isEmpty())
        assertEquals(90, applied(master, ui).getInt("night_pause_start"))   // 主存已有，界面值不同也不碰
    }

    @Test fun absentInBothIsNotInvented() {
        val master = JSONObject().put("adv_mode", "auto")
        val ui = mapOf<String, Any?>("adv_mode" to "auto")
        val out = applied(master, ui)
        assertFalse(out.has("screen_pause_on"))   // 两边都没有，不凭空造键
        assertFalse(out.has("settle_buffer_s"))
    }

    @Test fun uiNullAndNonRegistryKeysIgnored() {
        val master = JSONObject()
        val ui = mapOf<String, Any?>("screen_pause_on" to null, "theme_mode" to "dark")
        // 界面值为空不补；主题这类 UI 自用键不在登记表里，不是同步对象
        assertTrue(Store.missingSettingsDelta(master, ui).isEmpty())
    }

    @Test fun jsonNullInMasterCountsAsMissing() {
        val master = JSONObject().put("settle_buffer_s", JSONObject.NULL)
        val ui = mapOf<String, Any?>("settle_buffer_s" to 20)
        assertEquals(20, applied(master, ui).getInt("settle_buffer_s"))   // JSON null 不算有值，照补
    }

    @Test fun theFiveKeysLostOnDeviceAllBackfill() {
        // 真机存量：5 个新设置只在界面、主存全缺；界面值都取与默认不同的那份
        val master = JSONObject().put("adv_mode", "only").put(SettingsStamp.KEY, 1_000L)
        val ui = mapOf<String, Any?>(
            "screen_pause_on" to true,          // 默认关
            "night_pause_start" to 1380,        // 默认 90
            "night_pause_end" to 120,           // 默认 390
            "settle_buffer_s" to 20,            // 默认 5
            "adv_weather_recall_on" to false,   // 默认开
            "adv_mode" to "work",               // 主存已有，不该被它盖
        )
        val out = applied(master, ui)
        assertTrue(out.getBoolean("screen_pause_on"))
        assertEquals(1380, out.getInt("night_pause_start"))
        assertEquals(120, out.getInt("night_pause_end"))
        assertEquals(20, out.getInt("settle_buffer_s"))
        assertFalse(out.getBoolean("adv_weather_recall_on"))
        assertEquals("only", out.getString("adv_mode"))
    }
}
