package io.github.juwanhwang.autoqqpet.pet

import java.util.concurrent.atomic.AtomicInteger

/**
 * 发包结果计数（进程级，R4）：AutoRunner 在一轮 tick 前后取差值，判断这一轮“读请求是不是整轮都挂了”。
 * 读与写分开计：写请求空回包/没回包是已知行为（重复写服务端回空包），**不参与**“会话断了”的判断。
 */
object IoStats {
    val readOk = AtomicInteger()
    val readFail = AtomicInteger()
    val writeOk = AtomicInteger()
    val writeEmpty = AtomicInteger()
    val writeTimeout = AtomicInteger()

    data class Snap(val readOk: Int, val readFail: Int, val writeEmpty: Int, val writeTimeout: Int) {
        operator fun minus(o: Snap) = Snap(readOk - o.readOk, readFail - o.readFail, writeEmpty - o.writeEmpty, writeTimeout - o.writeTimeout)
    }

    fun snap() = Snap(readOk.get(), readFail.get(), writeEmpty.get(), writeTimeout.get())
}
