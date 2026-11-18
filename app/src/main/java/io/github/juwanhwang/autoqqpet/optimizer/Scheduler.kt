package io.github.juwanhwang.autoqqpet.optimizer

/** 唤醒调度抽象：AutoRunner 用它安排下一次 tick。 */
interface Scheduler {
    /** 在 wallMillis(System.currentTimeMillis 基准) 唤醒一次 tick。 */
    fun scheduleAt(wallMillis: Long, tag: String)

    /** 固定延迟（相对现在的毫秒）唤醒。 */
    fun scheduleAfter(delayMillis: Long, tag: String)
}
