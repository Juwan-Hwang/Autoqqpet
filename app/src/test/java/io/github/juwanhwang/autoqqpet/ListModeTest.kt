package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.pet.FriendPolicy
import io.github.juwanhwang.autoqqpet.pet.HirePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名单黑/白模式（2.1.10）：统一判定 listAllows、旧双名单迁移、雇佣名单按模式过滤。
 * 口径：名单为空两种模式都不限；白名单只认名单内；黑名单排除名单内。
 */
class ListModeTest {

    @Test fun emptyListAllowsEveryoneInBothModes() {
        assertTrue(FriendPolicy.listAllows(FriendPolicy.MODE_WL, emptySet(), "111"))
        assertTrue(FriendPolicy.listAllows(FriendPolicy.MODE_BL, emptySet(), "111"))
    }

    @Test fun whitelistModeOnlyAllowsListed() {
        val list = setOf("111", "222")
        assertTrue(FriendPolicy.listAllows(FriendPolicy.MODE_WL, list, "111"))
        assertFalse(FriendPolicy.listAllows(FriendPolicy.MODE_WL, list, "333"))
    }

    @Test fun blacklistModeExcludesListed() {
        val list = setOf("111", "222")
        assertFalse(FriendPolicy.listAllows(FriendPolicy.MODE_BL, list, "111"))
        assertTrue(FriendPolicy.listAllows(FriendPolicy.MODE_BL, list, "333"))
    }

    @Test fun migrationWhitelistBecomesWhitelistModeMinusBlacklist() {
        // 老口径：只认白名单、黑名单再排除 → 新名单 = 白名单 − 黑名单
        val changes = FriendPolicy.friendListMigration(mapOf("whitelist" to "111,222,333", "blacklist" to "222"))
        assertEquals("111,333", changes[FriendPolicy.KEY_FRIEND_LIST])
        assertEquals(FriendPolicy.MODE_WL, changes[FriendPolicy.KEY_FRIEND_LIST_MODE])
        assertTrue(changes.containsKey("whitelist") && changes["whitelist"] == null)
        assertTrue(changes.containsKey("blacklist") && changes["blacklist"] == null)
    }

    @Test fun migrationEmptyWhitelistBecomesBlacklistMode() {
        val changes = FriendPolicy.friendListMigration(mapOf("whitelist" to "", "blacklist" to "999"))
        assertEquals("999", changes[FriendPolicy.KEY_FRIEND_LIST])
        assertEquals(FriendPolicy.MODE_BL, changes[FriendPolicy.KEY_FRIEND_LIST_MODE])
    }

    @Test fun migrationNoOldListsBecomesEmptyBlacklistMode() {
        val changes = FriendPolicy.friendListMigration(emptyMap<String, Any>())
        assertEquals("", changes[FriendPolicy.KEY_FRIEND_LIST])
        assertEquals(FriendPolicy.MODE_BL, changes[FriendPolicy.KEY_FRIEND_LIST_MODE])
    }

    @Test fun migrationIsIdempotent() {
        val changes = FriendPolicy.friendListMigration(mapOf(FriendPolicy.KEY_FRIEND_LIST_MODE to "wl", "whitelist" to "111"))
        assertTrue(changes.isEmpty())
    }

    @Test fun migrationPreservesOldEligibility() {
        // 迁移前后 isEligible 的等价物（listAllows）对每个 uin 的结论必须一致
        val cases = listOf(
            mapOf("whitelist" to "111,222", "blacklist" to "222"),
            mapOf("whitelist" to "", "blacklist" to "333"),
            mapOf("whitelist" to "", "blacklist" to ""),
            mapOf("whitelist" to "111", "blacklist" to ""),
        )
        for (old in cases) {
            val changes = FriendPolicy.friendListMigration(old)
            val newList = (changes[FriendPolicy.KEY_FRIEND_LIST] ?: "").split(",").filter { it.isNotEmpty() }.toSet()
            val newMode = changes[FriendPolicy.KEY_FRIEND_LIST_MODE]!!
            val wl = (old["whitelist"] ?: "").split(",").filter { it.isNotEmpty() }.toSet()
            val bl = (old["blacklist"] ?: "").split(",").filter { it.isNotEmpty() }.toSet()
            for (uin in listOf("111", "222", "333", "444")) {
                val oldOk = uin !in bl && (wl.isEmpty() || uin in wl)
                assertEquals("case=$old uin=$uin", oldOk, FriendPolicy.listAllows(newMode, newList, uin))
            }
        }
    }

    @Test fun designatedFilterFollowsMode() {
        val pool = listOf("111", "222", "333")
        assertEquals(pool, HirePolicy.designatedFilter(pool, emptySet(), FriendPolicy.MODE_WL))
        assertEquals(pool, HirePolicy.designatedFilter(pool, emptySet(), FriendPolicy.MODE_BL))
        assertEquals(listOf("222"), HirePolicy.designatedFilter(pool, setOf("222"), FriendPolicy.MODE_WL))
        assertEquals(listOf("111", "333"), HirePolicy.designatedFilter(pool, setOf("222"), FriendPolicy.MODE_BL))
    }
}
