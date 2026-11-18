package io.github.juwanhwang.autoqqpet.net

/**
 * 极简 protobuf wire 编解码，移植自 qq-pet-interface-copilot 的 `qqpet_app/proto.py`。
 * 目的：我们自己按 wire 格式构造业务 body / 解析响应，不依赖 QQ 的混淆 protobuf 类，
 * 从根本上规避"客户端混淆名随版本漂移"的问题（协议命令号/字段是服务端契约）。
 *
 * wire type：0=varint，1=fixed64，2=length-delimited，5=fixed32。
 */
object ProtoBuf {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LEN = 2
    const val WIRE_START_GROUP = 3
    const val WIRE_END_GROUP = 4
    const val WIRE_FIXED32 = 5

    /** 单个字段：wireType + 值（varint 为 Long，其余为 ByteArray，fixed64 也归为 ByteArray[8]）。 */
    data class WireValue(val wireType: Int, val value: Any)

    // ---- 编码 ----

    /** LEB128；负数按 64 位无符号补码编码（与 proto.py 的 `value & (1<<64)-1` 等价，用逻辑右移实现）。 */
    fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>(10)
        var v = value
        while (true) {
            val part = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(part.toByte())
                break
            } else {
                out.add((part or 0x80).toByte())
            }
        }
        return out.toByteArray()
    }

    fun fieldVarint(number: Int, value: Long): ByteArray =
        concat(varint((number shl 3).toLong()), varint(value))

    fun fieldBytes(number: Int, value: ByteArray): ByteArray =
        concat(varint(((number shl 3) or WIRE_LEN).toLong()), varint(value.size.toLong()), value)

    fun fieldString(number: Int, value: String): ByteArray =
        fieldBytes(number, value.toByteArray(Charsets.UTF_8))

    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    // ---- 解码 ----

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            i++
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return Pair(value, i)
            shift += 7
            if (shift > 63) throw IllegalArgumentException("protobuf varint 太长")
        }
        throw IllegalArgumentException("protobuf varint 被截断")
    }

    /**
     * 解析一个 message，返回 field 号 -> 该号出现的所有 (wireType, value)。
     * 支持 proto2 group（wire type 3/4）：把 group 内容原样当作 length-delimited 字节保存，
     * 这样 firstBytes/firstString/嵌套解析等现有 API 不变。QQ 岗位收益、商城项就嵌在 group 里。
     */
    fun parseMessage(data: ByteArray): Map<Int, MutableList<WireValue>> = parseBody(data, 0, data.size, -1).first

    /** @param stopAtGroup 非 -1 时为某字段号读 group，遇到同号 EndGroup 就停。@return (字段表, 下一个可读偏移) */
    private fun parseBody(data: ByteArray, from: Int, until: Int, stopAtGroup: Int): Pair<Map<Int, MutableList<WireValue>>, Int> {
        val result = HashMap<Int, MutableList<WireValue>>()
        var offset = from
        while (offset < until) {
            val (tag, next) = readVarint(data, offset)
            offset = next
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            if (wireType == WIRE_END_GROUP) {
                // 遇到与当前 group 匹配的结束标记 → 本层结束；不匹配则当作噪声跳过，不让整个解析崩掉。
                if (stopAtGroup == number) return Pair(result, offset)
                continue
            }
            require(number != 0) { "protobuf 字段号为 0" }
            when (wireType) {
                WIRE_VARINT -> {
                    val (v, n) = readVarint(data, offset)
                    offset = n
                    result.getOrPut(number) { ArrayList() }.add(WireValue(wireType, v))
                }
                WIRE_FIXED64 -> {
                    require(offset + 8 <= data.size) { "fixed64 被截断" }
                    result.getOrPut(number) { ArrayList() }.add(WireValue(wireType, data.copyOfRange(offset, offset + 8)))
                    offset += 8
                }
                WIRE_LEN -> {
                    val (size, n) = readVarint(data, offset)
                    offset = n
                    require(offset + size <= data.size) { "length-delimited 字段被截断" }
                    result.getOrPut(number) { ArrayList() }.add(WireValue(wireType, data.copyOfRange(offset, offset + size.toInt())))
                    offset += size.toInt()
                }
                WIRE_FIXED32 -> {
                    require(offset + 4 <= data.size) { "fixed32 被截断" }
                    result.getOrPut(number) { ArrayList() }.add(WireValue(wireType, data.copyOfRange(offset, offset + 4)))
                    offset += 4
                }
                WIRE_START_GROUP -> {
                    val contentStart = offset
                    val (_, after) = parseBody(data, contentStart, until, number)
                    // 回退掉刚读过的 EndGroup tag，得到纯 group 内容字节
                    val endTagLen = varint((number shl 3 or WIRE_END_GROUP).toLong()).size
                    val contentEnd = (after - endTagLen).coerceAtLeast(contentStart)
                    result.getOrPut(number) { ArrayList() }.add(WireValue(WIRE_LEN, data.copyOfRange(contentStart, contentEnd)))
                    offset = after
                }
                else -> throw IllegalArgumentException("暂不支持 wire type $wireType")
            }
        }
        return Pair(result, offset)
    }

    fun firstVarint(msg: Map<Int, List<WireValue>>, field: Int, default: Long = 0L): Long {
        val vs = msg[field] ?: return default
        val v = vs.firstOrNull { it.wireType == WIRE_VARINT } ?: return default
        return v.value as Long
    }

    fun firstBytes(msg: Map<Int, List<WireValue>>, field: Int): ByteArray {
        val vs = msg[field] ?: return ByteArray(0)
        val v = vs.firstOrNull { it.wireType == WIRE_LEN } ?: return ByteArray(0)
        return v.value as ByteArray
    }

    fun firstString(msg: Map<Int, List<WireValue>>, field: Int): String =
        String(firstBytes(msg, field), Charsets.UTF_8)

    /** fixed32（4 字节小端 float），用于宠物心情/体力等数值字段。 */
    fun firstFloat(msg: Map<Int, List<WireValue>>, field: Int, default: Double = 0.0): Double {
        val vs = msg[field] ?: return default
        val v = vs.firstOrNull { it.wireType == WIRE_FIXED32 } ?: return default
        val b = v.value as ByteArray
        val bits = (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits).toDouble()
    }

    // ---- 便捷：构造与遍历（业务层统一用这些，不再手拼 concat）----

    /** 子消息：解析 [field] 的第一个 length-delimited 值；没有就是空表。 */
    fun sub(msg: Msg, field: Int): Msg {
        val raw = firstBytes(msg, field)
        return if (raw.isEmpty()) emptyMap() else runCatching { parseMessage(raw) }.getOrDefault(emptyMap())
    }

    /** 重复子消息。解析失败的项直接丢，不让一条坏数据拖垮整页。 */
    fun repeated(msg: Msg, field: Int): List<Msg> =
        msg[field]?.filter { it.wireType == WIRE_LEN }
            ?.mapNotNull { runCatching { parseMessage(it.value as ByteArray) }.getOrNull() }
            ?: emptyList()

    /** 是否像人能读的 UTF-8 文本（中文/ASCII 可见字符，无替换符与控制符）。 */
    fun looksText(b: ByteArray): Boolean {
        if (b.isEmpty() || b.size > 4096) return false
        val s = String(b, Charsets.UTF_8)
        if (s.contains('\uFFFD')) return false
        return s.all { it == '\n' || it.code >= 0x20 && it.code != 0x7f }
    }

    /**
     * 深度优先收集整棵树里的可读字符串（带路径，如 `4.1.2`）。
     * 只用于**结构未完全定死**的回包（结算明细、进度叙事）：按内容认，不按字段号认。
     */
    fun strings(data: ByteArray, maxDepth: Int = 8): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun walk(b: ByteArray, path: String, depth: Int) {
            if (depth > maxDepth) return
            val msg = runCatching { parseMessage(b) }.getOrNull()
            if (msg == null) {
                if (looksText(b)) out += path to String(b, Charsets.UTF_8)
                return
            }
            for ((num, vs) in msg.toSortedMap()) for (v in vs) {
                if (v.wireType != WIRE_LEN) continue
                val raw = v.value as ByteArray
                val p = if (path.isEmpty()) "$num" else "$path.$num"
                // 先当文本：短文本恰好也是合法 protobuf 的概率不低（例如“+3”），文本优先
                if (looksText(raw) && !nestedLooksBetter(raw)) out += p to String(raw, Charsets.UTF_8)
                else walk(raw, p, depth + 1)
            }
        }
        walk(data, "", 0)
        return out
    }

    /** 常见的“第一个字段”的 tag 字节（f1~f5 的 varint/len/fixed32）：以它开头且能完整解析，就当嵌套消息。 */
    private val COMMON_TAGS = setOf(0x08, 0x0a, 0x0d, 0x10, 0x12, 0x15, 0x18, 0x1a, 0x1d, 0x20, 0x22, 0x25, 0x28, 0x2a)

    /** 一段字节同时“像文本”又“能当 protobuf 解”时：以常见 tag 开头且整段可解析 → 当嵌套消息。 */
    private fun nestedLooksBetter(raw: ByteArray): Boolean {
        if ((raw[0].toInt() and 0xFF) !in COMMON_TAGS) return false
        val msg = runCatching { parseMessage(raw) }.getOrNull() ?: return false
        return msg.isNotEmpty()
    }

    /** 整棵树里所有 varint（带路径）。 */
    fun varints(data: ByteArray, maxDepth: Int = 6): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        fun walk(b: ByteArray, path: String, depth: Int) {
            if (depth > maxDepth) return
            val msg = runCatching { parseMessage(b) }.getOrNull() ?: return
            for ((num, vs) in msg.toSortedMap()) for (v in vs) {
                val p = if (path.isEmpty()) "$num" else "$path.$num"
                when (v.wireType) {
                    WIRE_VARINT -> out += p to (v.value as Long)
                    WIRE_LEN -> (v.value as ByteArray).let { raw -> if (!looksText(raw) || nestedLooksBetter(raw)) walk(raw, p, depth + 1) }
                }
            }
        }
        walk(data, "", 0)
        return out
    }

    /** hex 串 → 字节（调试广播 rawsend/dbgcmd 用）。 */
    fun hex(s: String): ByteArray {
        val t = s.filterNot { it.isWhitespace() }
        require(t.length % 2 == 0) { "hex 长度必须是偶数" }
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

/** 一个已解析的 message：字段号 → 该号出现的全部值。 */
typealias Msg = Map<Int, List<ProtoBuf.WireValue>>

/**
 * 请求体构造器：`pb { str(1, petId); num(100, 2) }`。字段按调用顺序写出（服务器不在乎顺序，但抓包对照时一致更好认）。
 */
class PbBuilder {
    private val parts = ArrayList<ByteArray>()
    fun num(field: Int, v: Long) { parts += ProtoBuf.fieldVarint(field, v) }
    fun num(field: Int, v: Int) = num(field, v.toLong())
    fun str(field: Int, v: String) { parts += ProtoBuf.fieldString(field, v) }
    fun bytes(field: Int, v: ByteArray) { parts += ProtoBuf.fieldBytes(field, v) }
    fun msg(field: Int, block: PbBuilder.() -> Unit) { parts += ProtoBuf.fieldBytes(field, pb(block)) }
    fun raw(b: ByteArray) { parts += b }
    fun build(): ByteArray = ProtoBuf.concat(*parts.toTypedArray())
}

fun pb(block: PbBuilder.() -> Unit): ByteArray = PbBuilder().apply(block).build()
