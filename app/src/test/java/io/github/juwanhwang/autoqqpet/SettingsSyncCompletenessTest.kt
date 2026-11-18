package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.store.SettingKeys
import io.github.juwanhwang.autoqqpet.store.SettingsStamp
import io.github.juwanhwang.autoqqpet.store.Store
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置同步丢键（2026-10-19 真机）：用户在界面打开了「息屏时暂停」，界面 prefs 里是开，
 * 主存与 QQ 侧却都没有这个键，RunnerConfig 只能读默认值（关）——改了跟没改一样。
 * 成因是 receiveHostSettings 收下 QQ 上交时整份替换主存：QQ 上交的只有它本地实际有的键，
 * 用户在 QQ 没在跑时改的设置只落了界面与主存，被下一次上交整份盖掉；分键新鲜度只保护
 * 上交里实际 differing 的键，救不了上交根本不含的键。
 * 本测试走生产实际用的合并接缝（Store.mergedHostSettings）：主存含登记表**全部**键、
 * 上交只含一部分键且戳不旧，断言合并后每个键都在、值对，且都能被 QQ 侧按登记类型应用。
 */
class SettingsSyncCompletenessTest {

    /** 给每个键造一个与默认值不同的样本值（数值尽量落在合法范围内）。 */
    private fun sampleValue(d: SettingKeys.Def): Any = when (d.type) {
        SettingKeys.Type.BOOL -> !(d.default as Boolean)
        SettingKeys.Type.INT -> pickNumber(d, (d.default as Int).toLong()).toInt()
        SettingKeys.Type.LONG -> pickNumber(d, d.default as Long)
        SettingKeys.Type.STRING -> d.choices.firstOrNull { it != d.default } ?: "sync-test-value"
    }

    private fun pickNumber(d: SettingKeys.Def, def: Long): Long {
        val r = d.range ?: return def + 7
        return when {
            def + 7 in r -> def + 7
            def - 7 in r -> def - 7
            else -> r.first { it != def }
        }
    }

    private fun putJson(o: JSONObject, d: SettingKeys.Def, v: Any) {
        when (v) {
            is Boolean -> o.put(d.key, v)
            is Int -> o.put(d.key, v)
            is Long -> o.put(d.key, v)
            else -> o.put(d.key, v.toString())
        }
    }

    @Test fun syncKeySetIsDerivedFromRegistry() {
        // 同步通道的键集必须与登记表分毫不差，且登记表内部没有重名键
        assertEquals(SettingKeys.ALL.map { it.key }.toSet(), Store.SETTINGS_KEYS)
        assertEquals(SettingKeys.ALL.size, SettingKeys.ALL.map { it.key }.toSet().size)
    }

    @Test fun everyRegisteredKeySurvivesPartialHostUpload() {
        // 主存：界面逐项改设置合并进来的那份，含登记表全部键、值都不是默认
        val master = JSONObject()
        val expected = HashMap<String, Any>()
        for (d in SettingKeys.ALL) {
            val v = sampleValue(d)
            expected[d.key] = v
            putJson(master, d, v)
        }
        master.put(SettingsStamp.KEY, 1_000L)

        // QQ 上交：只有它本地有的键——这批 2.0.2 新增键从没落进 QQ prefs，整份都不在；
        // 另有两个共有键在 QQ 侧是不同的值，合并应以它为准
        val missingFromHost = setOf(
            "adv_weather_recall_on", "screen_pause_on", "night_pause_on",
            "night_pause_start", "night_pause_end", "settle_buffer_s",
        )
        val upload = JSONObject()
        for (d in SettingKeys.ALL) {
            if (d.key in missingFromHost) continue
            putJson(upload, d, expected[d.key]!!)
        }
        upload.put("pk_on", SettingKeys.PK_ON.default as Boolean)
        upload.put("adv_mode", "work")
        upload.put(SettingsStamp.KEY, 2_000L)

        // 戳不旧、上交会被收下——旧逻辑正是在这一步整份替换、把主存独有的键删光
        assertTrue(SettingsStamp.accept(2_000L, 1_000L))

        val merged = JSONObject(Store.mergedHostSettings(master.toString(), upload.toString(), emptySet(), now = 3_000L))
        assertEquals(3_000L, merged.getLong(SettingsStamp.KEY))

        for (d in SettingKeys.ALL) {
            assertTrue("合并后丢了键 ${d.key}", merged.has(d.key))
            val want: Any = when (d.key) {
                "pk_on" -> SettingKeys.PK_ON.default
                "adv_mode" -> "work"
                else -> expected[d.key]!!
            }
            assertTrue("${d.key} 合并后的值不对", Store.sameValue(want, merged.opt(d.key)))
            // QQ 侧应用结果（SideStore.applySettings 走 SettingKeys.put → coerce）：每个键都能原样吃下
            val coerced = SettingKeys.coerce(d, merged.opt(d.key))
            assertTrue("${d.key} 无法被 QQ 侧应用", coerced != null && Store.sameValue(want, coerced))
        }
        // 这次丢的正是点名这批：主存独有，必须保住界面改的值
        assertTrue(merged.getBoolean("screen_pause_on"))
    }

    @Test fun protectedKeyStillWinsOverHostUpload() {
        // 分键新鲜度语义不能因为改成合并而回退：刚在界面改过的键仍以本地为准
        val master = JSONObject().put("screen_pause_on", true).put(SettingsStamp.KEY, 1_000L)
        val upload = JSONObject().put("screen_pause_on", false).put(SettingsStamp.KEY, 2_000L)
        val merged = JSONObject(
            Store.mergedHostSettings(master.toString(), upload.toString(), protectedKeys = setOf("screen_pause_on"), now = 3_000L),
        )
        assertTrue(merged.getBoolean("screen_pause_on"))
    }
}
