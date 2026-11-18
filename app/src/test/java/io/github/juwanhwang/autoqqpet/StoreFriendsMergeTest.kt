package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.store.Store
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 名单副标题不刷新（2026-10-16 真机）：打开面板触发的名册刷新回信生成得晚、内容旧，
 * 落盘时把刚保存的新名单盖回旧值。Store.receiveFriends 的新鲜度合并：旧回信不许盖名单键。
 */
@RunWith(RobolectricTestRunner::class)
class StoreFriendsMergeTest {

    private val local = """{"whitelist":"","blacklist":"999","roster":"111⋄小明"}"""

    @Test fun staleReplyCannotClobberUserLists() {
        // 回信 __at(100) 比本地编辑(200) 旧：名单键留本地，其余（名册）照收
        val incoming = """{"whitelist":"111,222","blacklist":"","roster":"111⋄小明~222⋄小红","__at":100}"""
        val out = JSONObject(Store.mergeFriendsJson(local, incoming, localAt = 200))
        assertEquals("", out.getString("whitelist"))
        assertEquals("999", out.getString("blacklist"))
        assertEquals("111⋄小明~222⋄小红", out.getString("roster"))
    }

    @Test fun freshReplyOverwrites() {
        val incoming = """{"whitelist":"111","blacklist":"","__at":300}"""
        val out = JSONObject(Store.mergeFriendsJson(local, incoming, localAt = 200))
        assertEquals("111", out.getString("whitelist"))
    }

    @Test fun legacyReplyWithoutStampOverwrites() {
        val incoming = """{"whitelist":"111","blacklist":""}"""
        val out = JSONObject(Store.mergeFriendsJson(local, incoming, localAt = 200))
        assertEquals("111", out.getString("whitelist"))
    }
}
