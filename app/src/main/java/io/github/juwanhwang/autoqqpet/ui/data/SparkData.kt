package io.github.juwanhwang.autoqqpet.ui.data

import io.github.juwanhwang.autoqqpet.pet.Day
import io.github.juwanhwang.autoqqpet.pet.FriendPolicy
import io.github.juwanhwang.autoqqpet.pet.SparkEntry
import io.github.juwanhwang.autoqqpet.pet.SparkPolicy
import io.github.juwanhwang.autoqqpet.pet.SparkStage
import org.json.JSONObject

/**
 * 火花页/设置页共用的数据视图（纯函数，可单测）：来源是主存 `friends_json`（QQ 进程上交的副本）。
 * 一行 = 一个好友：名册（0x985d）∪ 火花列表（0x9b87）合并；火花名单一份 `spark_bl` + 模式 `spark_list_mode`。
 * 行上的 [SparkRow.blocked] 恒表示“不会被踩”：黑名单模式 = 在名单内，白名单模式 = 不在名单内。
 */
data class SparkRow(
    val uin: String,
    val nick: String,
    val stage: SparkStage,
    val days: Int,
    val gapDays: Int,
    val mutualToday: Boolean,
    val pokedToday: Boolean,
    val blocked: Boolean,
) {
    /** 待重燃还剩几天清零（-1 不知道）。 */
    val daysLeft: Int get() = if (stage == SparkStage.REKINDLE && gapDays >= 0) (SparkPolicy.CLEAR_DAYS - gapDays).coerceAtLeast(0) else -1
    val initial: String get() = nick.trim().firstOrNull()?.toString() ?: "?"
}

data class SparkModel(
    val rows: List<SparkRow>,
    val lit: Int,
    val rekindle: Int,
    val pokedToday: Int,
    val target: Int,
    val blacklist: Set<String>,
    val readAt: Long,
    /** 火花名单模式："wl" 白名单 / "bl" 黑名单（默认）。 */
    val listMode: String = "bl",
) {
    /** 需要关注：待重燃（按剩余天数升序），再是今天还没互踩的在燃好友。黑名单不进。 */
    val attention: List<SparkRow> get() =
        rows.filter { !it.blocked && it.stage == SparkStage.REKINDLE }.sortedWith(compareBy({ if (it.daysLeft < 0) 99 else it.daysLeft }, { -it.days })) +
            rows.filter { !it.blocked && it.stage == SparkStage.LIT && !it.mutualToday }.sortedByDescending { it.days }

    companion object {
        val EMPTY = SparkModel(emptyList(), 0, 0, 0, 0, emptySet(), 0L)

        fun parse(friendsJson: String?, today: Int = Day.today()): SparkModel {
            val f = runCatching { JSONObject(friendsJson ?: "{}") }.getOrDefault(JSONObject())
            val sparks: List<SparkEntry> = FriendPolicy.decodeSparks(f.optString("sparks"))
            val roster = f.optString("roster").split("~").mapNotNull {
                val kv = it.split("⋄"); if (kv.size < 2 || kv[0].isEmpty()) null else kv[0] to kv[1]
            }.distinctBy { it.first }
            val bl = f.optString("spark_bl").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val listMode = f.optString("spark_list_mode").takeIf { it == "wl" || it == "bl" } ?: "bl"
            val pokedParts = f.optString("spark_poked").split(";")
            val poked = if (pokedParts.firstOrNull() == today.toString()) pokedParts.drop(1).filter { it.isNotEmpty() }.toSet() else emptySet()
            val bySpark = sparks.associateBy { it.uin }
            val uins = LinkedHashSet<String>().apply { roster.forEach { add(it.first) }; sparks.forEach { add(it.uin) } }
            val names = roster.toMap()
            val rows = uins.map { u ->
                val s = bySpark[u]
                SparkRow(u, s?.nick?.takeIf { it.isNotBlank() && it != u } ?: names[u] ?: u, s?.stage ?: SparkStage.UNLIT, s?.days ?: 0, s?.gapDays ?: -1,
                    s?.pokedToday == true, u in poked, !FriendPolicy.listAllows(listMode, bl, u))
            }
            val excluded = roster.map { it.first }.filter { !FriendPolicy.listAllows(listMode, bl, it) }.toSet()
            val (x, y) = SparkPolicy.progress(roster.map { it.first }, excluded, poked, "")
            return SparkModel(rows, rows.count { it.stage == SparkStage.LIT && !it.blocked }, rows.count { it.stage == SparkStage.REKINDLE && !it.blocked },
                x, y, bl, f.optString("sparks_at").toLongOrNull() ?: 0L, listMode)
        }

        /** 列表筛选与排序（全部好友页）。 */
        fun filter(rows: List<SparkRow>, query: String, chip: String?, sort: String): List<SparkRow> {
            val q = query.trim()
            val base = rows.filter { r ->
                (q.isEmpty() || r.nick.contains(q, true) || r.uin.contains(q)) && when (chip) {
                    "lit" -> r.stage == SparkStage.LIT && !r.blocked
                    "rekindle" -> r.stage == SparkStage.REKINDLE && !r.blocked
                    "unlit" -> (r.stage == SparkStage.UNLIT || r.stage == SparkStage.UNKNOWN) && !r.blocked
                    "blocked" -> r.blocked
                    else -> true
                }
            }
            return when (sort) {
                "stage" -> base.sortedWith(compareBy<SparkRow> { when (it.stage) { SparkStage.REKINDLE -> 0; SparkStage.LIT -> 1; else -> 2 } }.thenByDescending { it.days })
                else -> base.sortedWith(compareByDescending<SparkRow> { it.days }.thenBy { it.nick })
            }
        }
    }
}
