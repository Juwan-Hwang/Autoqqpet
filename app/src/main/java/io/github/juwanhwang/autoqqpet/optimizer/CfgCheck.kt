package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.store.MapPrefs
import io.github.juwanhwang.autoqqpet.store.SettingKeys

/**
 * 配置一致性自检（R10）：**磁盘上的 prefs**、**本进程缓存的 prefs**、**正在跑的 runner 配置**三者对比。
 *  - 磁盘 ≠ 缓存：有别的进程写过同一个文件（多进程各跑一份），或 apply 还没落盘（几百毫秒内，偶发）；
 *  - 缓存 ≠ runner：改了设置但 runner 没重建（rebuild 时机漏了）。
 * 纯函数，可单测；结果进快照 `health.cfg_check`，诊断页显示，命令 `cfgcheck` 现跑一次。
 */
object CfgCheck {
    data class Result(
        val at: Long,
        /** 磁盘与内存缓存不同的键：`键: 磁盘=… 内存=…`。 */
        val diskVsMemory: List<String>,
        /** runner 配置与“按磁盘值重算的配置”不同的摘要片段：`磁盘 → 运行`。 */
        val runnerVsDisk: List<String>,
        val diskRead: Boolean,
    ) {
        val ok: Boolean get() = diskRead && diskVsMemory.isEmpty() && runnerVsDisk.isEmpty()
        fun line(): String = when {
            !diskRead -> "读不到磁盘上的设置文件"
            ok -> "一致"
            else -> (diskVsMemory.map { "缓存 $it" } + runnerVsDisk.map { "运行 $it" }).joinToString("；")
        }
    }

    fun compare(disk: Map<String, Any?>?, memory: Map<String, Any?>, running: RunnerConfig?, now: Long = System.currentTimeMillis()): Result {
        if (disk == null) return Result(now, emptyList(), emptyList(), false)
        val keys = SettingKeys.ALL.map { it.key }
        val dm = keys.mapNotNull { k ->
            val a = disk[k]; val b = memory[k]
            if (same(a, b)) null else "$k: 磁盘=${show(a)} 内存=${show(b)}"
        }
        val rv = if (running == null) emptyList() else {
            val fromDisk = RunnerConfig.fromPrefs(MapPrefs(disk))
            if (fromDisk == running) emptyList() else diffTokens(fromDisk.summary(), running.summary())
        }
        return Result(now, dm, rv, true)
    }

    /** 两行摘要按空格切片，列出不同的片段（`召回=best_split → 召回=关`）。 */
    fun diffTokens(a: String, b: String): List<String> {
        val ta = a.split(' ').filter { it.isNotBlank() }
        val tb = b.split(' ').filter { it.isNotBlank() }
        val out = ArrayList<String>()
        for (i in 0 until maxOf(ta.size, tb.size)) {
            val x = ta.getOrNull(i); val y = tb.getOrNull(i)
            if (x != y) out += "${x ?: "∅"} → ${y ?: "∅"}"
        }
        return out.ifEmpty { listOf("（摘要相同，但排班等长字段不同）") }
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a == null || b == null -> a == b
        a is Number && b is Number -> a.toLong() == b.toLong()
        else -> a == b
    }

    private fun show(v: Any?): String = when (v) { null -> "∅"; is String -> if (v.length > 24) "\"${v.take(24)}…\"" else "\"$v\""; else -> v.toString() }
}
