package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.Msg
import io.github.juwanhwang.autoqqpet.net.Oidb
import io.github.juwanhwang.autoqqpet.net.ProtoBuf
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.net.pb

/** 调试族：原样发包、把回包字段树打成一行。只给调试广播用，自动流程不调。 */
class DebugApi(private val s: PetSession) {

    fun rawSend(name: String, command: Int, sub: Int, bodyHex: String, read: Boolean): String {
        val body = if (bodyHex.isEmpty()) ByteArray(0) else ProtoBuf.hex(bodyHex)
        val o = Oidb(name, command, sub)
        val resp = if (read) s.read(o, body) else s.write(o) { body }
        return "resp(${resp.size})=" + ProtoBuf.toHex(resp)
    }

    fun dumpCmd(name: String, command: Int, sub: Int, bodyHex: String): String =
        dump(s.read(Oidb(name, command, sub), if (bodyHex.isEmpty()) ByteArray(0) else ProtoBuf.hex(bodyHex)))

    fun dumpStory(): String = dump(s.read(Commands.STORY_STATUS, pb { str(1, s.ensurePetId()); num(100, Proto.OUTDOOR_V) }))

    fun dumpProfile(): String = dump(s.read(Commands.OWN_PROFILE, ByteArray(0)))

    /** 字段树 → 一行（`|` 分隔）。 */
    fun dump(raw: ByteArray): String =
        StringBuilder().also { into(it, runCatching { ProtoBuf.parseMessage(raw) }.getOrDefault(emptyMap()), "", 0) }.toString().replace("\n", " | ")

    private fun into(sb: StringBuilder, msg: Msg, prefix: String, depth: Int) {
        if (depth > 8) return
        for ((num, vs) in msg.toSortedMap()) for (v in vs) {
            when (v.wireType) {
                ProtoBuf.WIRE_VARINT -> sb.append("\n$prefix#$num varint=${v.value}")
                ProtoBuf.WIRE_LEN -> {
                    val b = v.value as ByteArray
                    if (ProtoBuf.looksText(b) && b.size < 96) sb.append("\n$prefix#$num str='${String(b, Charsets.UTF_8)}'")
                    else {
                        sb.append("\n$prefix#$num bytes(${b.size})")
                        val before = sb.length
                        runCatching { into(sb, ProtoBuf.parseMessage(b), "$prefix  .$num.", depth + 1) }
                        if (sb.length == before) sb.append(" hex=" + ProtoBuf.toHex(b.copyOf(minOf(40, b.size))))
                    }
                }
                ProtoBuf.WIRE_FIXED32 -> sb.append("\n$prefix#$num f32=${ProtoBuf.firstFloat(mapOf(num to listOf(v)), num)}")
                else -> sb.append("\n$prefix#$num wt=${v.wireType}")
            }
        }
    }
}
