package io.github.juwanhwang.autoqqpet.optimizer

/**
 * 天色不对检查的节奏与重试（公开仓 issue #11 复盘，2026-10-20）。
 *
 * 旧版是「一次性检查」：出发满 6.5 秒后的第一个 tick 读一次进度，
 * 读到就判，读不到（回包为空/超时）就摘记号、整趟不再查。真机日志里漏召回的几趟全栽在这上面：
 *  - 检查的那一读失败 → 日志自记「天色检查没读到进度，这趟不再查」，坏天气跑满全程；
 *  - 唤醒被顶掉/迟到，整趟唯一的一次检查机会跟着没了。
 *
 * 新版把检查做成「查到有结论为止」：
 *  - 第一次仍在出发后 [FIRST_AFTER_MS]（条目约出发后 5 秒出现，留 1.5 秒余量）；
 *  - 没读到可用结果（回包为空、条目列表为空都算没结论）不算失败到底，隔 [RETRY_MS] 再查，
 *    最多查 [MAX_READ_TRIES] 次（含第一次），试满才放弃这一趟；
 *  - 命中后召回写失败同理：最多试 [MAX_RECALL_TRIES] 次（含第一次）；
 *  - 读到条目且没有天色不对 = 结论（天气条目出发后约 5 秒就定型，不会中途才冒出来），不再查。
 *
 * 纯逻辑，时间与计数由调用方传入（单测用）。
 */
object WeatherCheckPlan {

    /** 第一次检查在出发后多久：条目约 5 秒出现，取 6.5 秒与旧版一致。 */
    const val FIRST_AFTER_MS = 6_500L

    /** 没结论/写失败后，隔多久再试一次。 */
    const val RETRY_MS = 8_000L

    /** 读进度最多试几次（含第一次）。45 秒的短冒险也够试满：6.5s、14.5s、22.5s、30.5s。 */
    const val MAX_READ_TRIES = 4

    /** 命中后召回最多试几次（含第一次）。 */
    const val MAX_RECALL_TRIES = 3

    /** 接手检查的最低剩余秒数：剩太短时召回也省不下什么时间，不接。 */
    const val ADOPT_MIN_REMAINING_SEC = 12L

    /** 这次读进度没拿到结论之后：返回下一次检查时刻；试满返回 null = 放弃这一趟。 */
    fun nextAfterReadFail(triesBefore: Int, nowMs: Long): Long? =
        if (triesBefore + 1 >= MAX_READ_TRIES) null else nowMs + RETRY_MS

    /** 这次召回没写成功之后：返回下一次重试时刻；试满返回 null = 放弃召回、照常跑完。 */
    fun nextAfterRecallFail(triesBefore: Int, nowMs: Long): Long? =
        if (triesBefore + 1 >= MAX_RECALL_TRIES) null else nowMs + RETRY_MS

    /** 读到的进度算不算「没结论」：没回包、或条目列表为空 = 没结论，要重试；有条目才是结论。 */
    fun inconclusive(lines: List<String>?): Boolean = lines.isNullOrEmpty()

    /**
     * 接手检查：这一趟是本模块派出的冒险（登记里有派出时刻）、派出时却没挂上检查记号
     * （旧版本派出的、进程重启后领回的等），且还没判过、剩余时间够——由当前 tick 补挂记号接着查。
     * 手动/外部故事没有派出时刻，永远不接。
     */
    fun shouldAdopt(
        armedFor: String, doneFor: String, pendingId: String, pendingKind: String,
        dispatchedAtMs: Long, storyId: String, remainingSec: Long,
    ): Boolean =
        armedFor.isEmpty() && doneFor != storyId && pendingId == storyId &&
            pendingKind == "adventure" && dispatchedAtMs > 0 && remainingSec > ADOPT_MIN_REMAINING_SEC
}
