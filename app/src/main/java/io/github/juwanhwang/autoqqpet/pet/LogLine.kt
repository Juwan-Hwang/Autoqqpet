package io.github.juwanhwang.autoqqpet.pet

import java.io.File
import java.io.FileOutputStream
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日志行（R6，真机 2026-10-15：出现 `07:51`、`07:54` 这种缺秒的时间戳）。
 *
 * 根因不是并发写，而是格式：旧代码用 `LocalTime.now().withNano(0).toString()`，**秒为 0 时 ISO 格式会省掉秒**（`07:51` 而不是 `07:51:00`）。
 * 它恰好落在“同一秒多行”的并发 tick 附近，看起来像写坏了。教训：新读数（像是并发写坏）与已知事实（单行 append）冲突时，先怀疑读数的解释。
 *
 * 现在：固定 `HH:mm:ss`（[DateTimeFormatter] 不可变、线程安全，不用 SimpleDateFormat），
 * 整行（含换行）一次 `write` 进以追加模式打开的文件，进程内再加一把锁 → 多线程同时写也不会交错、不会半行。
 */
object LogLine {
    val LOCK = Any()
    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun format(msg: String, time: LocalTime = LocalTime.now(ZoneId.systemDefault()), maxLen: Int = 400): String =
        "${FMT.format(time)} ${msg.replace("\r", " ").replace("\n", " ").take(maxLen)}\n"

    /** 整行原子追加（一次 write 系统调用；O_APPEND 下同进程/跨进程都不会交错）。 */
    fun append(file: File, line: String) {
        synchronized(LOCK) {
            FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        }
    }
}
