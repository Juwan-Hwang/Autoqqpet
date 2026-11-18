package io.github.juwanhwang.autoqqpet.keepalive

/** 模块 App 进程内的共享状态（由 StatusReceiver 写入，KeepAliveService 读取渲染通知）。 */
object StatusStore {
    @Volatile
    var status: String = "未启动"

    @Volatile
    var lastHeartbeat: Long = 0L

    @Volatile
    var autoOn: Boolean = false

    /** QQ 进程告知的“下一次唤醒时刻”（墙钟 ms）；0 = 它手上没排程（状态机已停摆）。 */
    @Volatile
    var nextWakeAt: Long = 0L

    /** 需用户动手才能解决的提醒文本（如“被雇佣该召回了”）；空=无提醒。 */
    @Volatile
    var alertText: String = ""

    /**
     * QQ 进程报“连续请求回不来”的时刻（墙钟 ms）；0 = 正常。
     * 为什么单独要这个信号：进程活着时**心跳照发**（它是本进程内的一个定时广播，不依赖网络），
     * 所以“QQ 会话/通道断了、所有宠物请求都在超时”这种半死状态光看心跳看不出来（真机今天 31 次回调超时，
     * 看门狗一次都没介入）。
     */
    @Volatile
    var netBadAt: Long = 0L

    /** 最近一帧心跳快照 JSON（CONTRACT §2）；空=还没收到过新版快照。 */
    @Volatile
    var snapshotJson: String = ""

    fun update(text: String) {
        status = text
        lastHeartbeat = System.currentTimeMillis()
    }

    /** 只更新展示文本，不冒充心跳（看门狗自己写状态时用它，否则会把“无心跳”这个事实遮掉）。 */
    fun note(text: String) {
        status = text
    }

    /** 距已排定的唤醒还有多少秒；<0 表示无排程或已过期。 */
    fun untilWakeSec(): Long = if (nextWakeAt == 0L) -1 else (nextWakeAt - System.currentTimeMillis()) / 1000

    /** 距上次心跳的秒数；<0 表示从未收到。 */
    fun sinceHeartbeatSec(): Long =
        if (lastHeartbeat == 0L) -1 else (System.currentTimeMillis() - lastHeartbeat) / 1000

    /** 连续超时已持续多少秒；0 = 没报过连不上。 */
    fun netBadSec(): Long = if (netBadAt == 0L) 0 else (System.currentTimeMillis() - netBadAt) / 1000
}
