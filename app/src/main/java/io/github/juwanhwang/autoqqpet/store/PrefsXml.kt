package io.github.juwanhwang.autoqqpet.store

import android.content.SharedPreferences

/**
 * 直接读 SharedPreferences 的 XML 文件（R10 诊断 `cfgcheck`）：内存里的 prefs 是进程缓存，
 * 别的进程写了磁盘它也不知道；要知道“磁盘上到底是什么”只能绕开缓存自己解析。纯函数，可单测。
 */
object PrefsXml {
    private val TAG_RE = Regex("""<(boolean|int|long|float|string)\s+name="([^"]*)"(?:\s+value="([^"]*)")?\s*(/>|>([\s\S]*?)</string>)""")

    fun parse(xml: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (m in TAG_RE.findAll(xml)) {
            val type = m.groupValues[1]
            val name = unescape(m.groupValues[2])
            val attr = m.groupValues[3]
            val v: Any? = when (type) {
                "boolean" -> attr.toBooleanStrictOrNull()
                "int" -> attr.toIntOrNull()
                "long" -> attr.toLongOrNull()
                "float" -> attr.toFloatOrNull()
                else -> if (m.groupValues[4] == "/>") attr.let(::unescape) else unescape(m.groupValues[5])
            }
            if (v != null) out[name] = v
        }
        return out
    }

    fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "lt" -> "<"; "gt" -> ">"; "amp" -> "&"; "quot" -> "\""; "apos" -> "'"
                else -> runCatching {
                    val cp = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(cp))
                }.getOrDefault(m.value)
            }
        }
    }
}

/** 只读的 Map → SharedPreferences（让 [io.github.juwanhwang.autoqqpet.optimizer.RunnerConfig.fromPrefs] 能吃磁盘解析结果）。 */
class MapPrefs(private val map: Map<String, Any?>) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
