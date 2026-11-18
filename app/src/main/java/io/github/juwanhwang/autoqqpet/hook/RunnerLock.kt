package io.github.juwanhwang.autoqqpet.hook

import io.github.juwanhwang.autoqqpet.log.PLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * 跨进程单实例锁（R10）：QQ 的同一个数据目录下只允许**一个**进程跑循环。
 *
 * 为什么光判进程名不够：`currentProcessName()` 在注入时刻可能为 null，旧代码在 null 时直接放行，
 * 于是子进程里也建了一份 runner——它的 SharedPreferences 是启动时读的旧缓存（多进程之间不同步），
 * 发出去的快照就是“召回=关”，还会把整份旧 prefs 写回磁盘覆盖主进程刚写的值。
 *
 * 用 `FileChannel.tryLock`（fcntl 记录锁）：持锁进程一死内核就释放，不会残留；同一进程内由调用方的 `probed` 防重入。
 * 锁文件放 QQ 的 files/ 下，句柄存在静态字段里直到进程退出。
 */
object RunnerLock {
    private const val TAG = "Autoqqpet"
    const val FILE = "autoqqpet-runner.lock"

    @Volatile private var channel: FileChannel? = null
    @Volatile private var lock: FileLock? = null

    val held: Boolean get() = lock?.isValid == true

    /**
     * 拿锁；拿不到时按 [retries] × [gapMs] 重试（主进程被杀重开时旧进程可能还没死透）。
     * @return true=本进程是唯一的循环进程
     */
    fun acquire(dir: File, retries: Int = 3, gapMs: Long = 4_000L): Boolean {
        if (held) return true
        repeat(retries.coerceAtLeast(1)) { i ->
            val r = runCatching {
                val ch = RandomAccessFile(File(dir, FILE), "rw").channel
                val l = ch.tryLock()
                if (l != null) {
                    channel = ch; lock = l
                    runCatching { ch.truncate(0); ch.write(java.nio.ByteBuffer.wrap("${android.os.Process.myPid()} ${System.currentTimeMillis()}".toByteArray())) }
                    true
                } else { runCatching { ch.close() }; false }
            }.getOrElse { PLog.w(TAG, "🔒 单实例锁异常：${it.message}"); false }
            if (r) return true
            if (i < retries - 1) runCatching { Thread.sleep(gapMs) }
        }
        return false
    }

    /** 锁文件里记的持锁 pid（诊断用；拿不到为空）。 */
    fun holderInfo(dir: File): String = runCatching { File(dir, FILE).readText().trim() }.getOrDefault("")
}
