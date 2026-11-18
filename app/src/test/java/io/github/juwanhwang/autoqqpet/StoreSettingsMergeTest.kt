package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.store.Store
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切挡立刻滑回（2026-10-17 真机）：界面刚把 adv_mode 切成 auto，QQ 侧因改了别的键整包盖新戳上交，
 * 里面 adv_mode 还是旧值——整包戳比较拦不住，旧值把主存和界面一起盖回去，重启 QQ 拉主存才恢复。
 * 分键新鲜度：刚在界面改过的键，QQ 上交的旧值不采纳；其余键照常合并。
 */
class StoreSettingsMergeTest {

    private val local = """{"adv_mode":"auto","auto_goal":"attr","__at":1000}"""
    private val incoming = """{"adv_mode":"work","auto_goal":"gold","pk_on":true,"__at":2000}"""

    @Test fun freshLocalKeySurvivesHostUpload() {
        val out = JSONObject(Store.mergeSettingsJson(local, incoming, protectedKeys = setOf("adv_mode")))
        assertEquals("auto", out.getString("adv_mode"))       // 刚改的，留本地
        assertEquals("gold", out.getString("auto_goal"))      // 没保护的，照收 QQ 的
        assertTrue(out.getBoolean("pk_on"))                   // 新键照收
    }

    @Test fun unprotectedKeyTakesIncoming() {
        val out = JSONObject(Store.mergeSettingsJson(local, incoming, protectedKeys = emptySet()))
        assertEquals("work", out.getString("adv_mode"))
    }

    @Test fun protectedKeyAbsentLocallyTakesIncoming() {
        val out = JSONObject(Store.mergeSettingsJson("""{"__at":1000}""", incoming, protectedKeys = setOf("adv_mode")))
        assertEquals("work", out.getString("adv_mode"))       // 本地根本没有这个键，无可保护
    }

    @Test fun freshLocalKeysWindow() {
        val ats = mapOf("adv_mode" to 1_000L, "auto_goal" to 500L, "old" to 10L)
        assertEquals(setOf("adv_mode", "auto_goal"), Store.freshLocalKeys(ats, now = 1_500L, windowMs = 1_000L))
        assertTrue(Store.freshLocalKeys(ats, now = 999_999L, windowMs = 1_000L).isEmpty())
    }
}
