package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.pb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoBufTest {

    @Test fun varintRoundTrip() {
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 38752L, Long.MAX_VALUE, -1L)) {
            val msg = ProtoBuf.parseMessage(ProtoBuf.fieldVarint(3, v))
            assertEquals(v, ProtoBuf.firstVarint(msg, 3))
        }
    }

    @Test fun negativeVarintIsTenBytes() {
        assertEquals(10, ProtoBuf.varint(-1L).size)
    }

    @Test fun builderAndStrings() {
        val body = pb { str(1, "pet-123"); num(100, 2); msg(4) { str(1, "petX"); str(2, "10001") } }
        val m = ProtoBuf.parseMessage(body)
        assertEquals("pet-123", ProtoBuf.firstString(m, 1))
        assertEquals(2L, ProtoBuf.firstVarint(m, 100))
        val sub = ProtoBuf.sub(m, 4)
        assertEquals("petX", ProtoBuf.firstString(sub, 1))
        assertEquals("10001", ProtoBuf.firstString(sub, 2))
    }

    @Test fun groupIsParsedAsLenBytes() {
        // field 5 start-group (tag 0x2b) { field1 varint 7 } end-group (tag 0x2c)
        val bytes = byteArrayOf(0x2b, 0x08, 0x07, 0x2c, 0x10, 0x09)
        val m = ProtoBuf.parseMessage(bytes)
        assertEquals(7L, ProtoBuf.firstVarint(ProtoBuf.sub(m, 5), 1))
        assertEquals(9L, ProtoBuf.firstVarint(m, 2))
    }

    @Test fun fixed32Float() {
        // field 3 fixed32 = 100.0f (0x42c80000 little endian)
        val bytes = byteArrayOf(0x1d, 0x00, 0x00, 0xc8.toByte(), 0x42)
        assertEquals(100.0, ProtoBuf.firstFloat(ProtoBuf.parseMessage(bytes), 3), 1e-6)
    }

    @Test fun repeatedAndHex() {
        val body = pb { msg(1) { str(1, "a") }; msg(1) { str(1, "b") } }
        assertEquals(listOf("a", "b"), ProtoBuf.repeated(ProtoBuf.parseMessage(body), 1).map { ProtoBuf.firstString(it, 1) })
        assertArrayEquals(body, ProtoBuf.hex(ProtoBuf.toHex(body)))
    }

    @Test fun stringsWalksNestedText() {
        val body = pb { msg(4) { msg(1) { str(1, "宠物认真工作了一整天"); str(2, "工分 +3") } } }
        val texts = ProtoBuf.strings(body).map { it.second }
        assertTrue(texts.contains("宠物认真工作了一整天"))
        assertTrue(texts.contains("工分 +3"))
    }
}
