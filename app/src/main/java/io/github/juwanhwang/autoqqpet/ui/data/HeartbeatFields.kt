package io.github.juwanhwang.autoqqpet.ui.data

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs

/**
 * 心跳字段注册表：后端往 STATUS 广播里加一个 extra，这里加一行，仪表盘就会显示它。
 * 字段缺失 = 显示“—”；类型不对 = 当作缺失。绝不因为某个字段崩。
 *
 * 新增一项（例）：
 *   Metric("mood", "心情", Glyphs.Smile, max = 100.0),
 */
object HeartbeatFields {

    data class Metric(
        val key: String,
        val label: String,
        val glyph: ImageVector,
        /** 有上限的数值（0–max）会画一根进度条；没有就只显示数字。 */
        val max: Double? = null,
        val unit: String = "",
        /** 兼容别名：后端最终用了另一个键名也能对上。 */
        val aliases: List<String> = emptyList(),
    )

    /** 宠物数值（后端在心跳里带上即显示；见 DESIGN.md「与后端的约定」）。 */
    val metrics: List<Metric> = listOf(
        Metric("hunger", "体力", Glyphs.Bolt, max = 100.0, aliases = listOf("stamina")),
        Metric("clean", "清洁", Glyphs.Drop, max = 100.0),
        Metric("feel", "心情", Glyphs.Smile, max = 100.0, aliases = listOf("mood")),
        Metric("gold", "金币", Glyphs.Coin),
        Metric("total", "综合", Glyphs.Sparkle),
    )

    fun number(map: Map<String, Any?>, m: Metric): Double? {
        for (k in listOf(m.key) + m.aliases) {
            val v = map[k] ?: continue
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            }
            if (d != null && !d.isNaN() && !d.isInfinite()) return d
        }
        return null
    }

    fun string(map: Map<String, Any?>, key: String): String? =
        (map[key] as? String)?.takeIf { it.isNotBlank() }

    fun long(map: Map<String, Any?>, key: String): Long? = when (val v = map[key]) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    /** 宠物名（后端可选字段 pet_name）。 */
    fun petName(map: Map<String, Any?>): String? = string(map, "pet_name")

    /**
     * 心跳里明说的当前任务（后端可选字段）：task_kind=study|work|adventure、task_name、
     * task_start_at / task_end_at（墙钟毫秒）。缺 kind 或已过期则视为没有。
     */
    fun task(map: Map<String, Any?>, now: Long): CurrentTask? {
        val kind = TaskKind.ofTag(string(map, "task_kind")) ?: return null
        val end = long(map, "task_end_at")?.takeIf { it > 0 }
        if (end != null && end <= now) return null
        return CurrentTask(kind, string(map, "task_name"), long(map, "task_start_at")?.takeIf { it > 0 }, end)
    }
}
