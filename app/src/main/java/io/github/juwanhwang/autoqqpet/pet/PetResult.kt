package io.github.juwanhwang.autoqqpet.pet

/**
 * 统一错误模型。业务层（Care/Task/Friend/Side 各管理器、AutoRunner）只看这个，不再各自 `runCatching` 后猜异常类型。
 *
 * 分类依据是“下一步该怎么办”，不是异常来自哪：
 *  - [Ok]：拿到了结果；
 *  - [Rejected]：服务器明确拒绝（正的业务码，如 135004/14561）——**不重试**，按业务处理；
 *  - [Transport]：传输层/超时/负码——**可等下一轮再试**（读命令 bridge 内部已按规则原地重试过）；
 *  - [Unverified]：写命令发出去了但回包为空/异常——**绝不重发**，交给调用方用状态复查判生效（DEV-NOTES 写命令幂等）；
 *  - [Skipped]：本地规则没让它发（开关关、每日上限、金币不够、名单拦）——不是错误，只记原因。
 */
sealed interface PetResult<out T> {
    data class Ok<T>(val value: T) : PetResult<T>
    data class Rejected(val code: Int, val message: String) : PetResult<Nothing>
    data class Transport(val message: String) : PetResult<Nothing>
    data class Unverified(val message: String) : PetResult<Nothing>
    data class Skipped(val reason: String) : PetResult<Nothing>

    val ok: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    /** 人读的一句话（日志用）。 */
    fun describe(): String = when (this) {
        is Ok -> "成功 ${value.toString().take(120)}"
        is Rejected -> "服务器拒绝 code=$code ${message.take(80)}"
        is Transport -> "通信失败 ${message.take(80)}"
        is Unverified -> "已发出但未确认 ${message.take(80)}"
        is Skipped -> "跳过：$reason"
    }

    companion object {
        /** 包一层读调用：异常按类型归类。 */
        inline fun <T> read(block: () -> T): PetResult<T> = try {
            Ok(block())
        } catch (e: QQPetBridge.ServerError) {
            Rejected(e.code, e.detail)
        } catch (e: Throwable) {
            Transport(e.message ?: e.javaClass.simpleName)
        }

        /** 包一层写调用：非业务码的异常一律算 [Unverified]（可能已生效，禁止重发）。 */
        inline fun <T> write(block: () -> T): PetResult<T> = try {
            Ok(block())
        } catch (e: QQPetBridge.ServerError) {
            Rejected(e.code, e.detail)
        } catch (e: Throwable) {
            Unverified(e.message ?: e.javaClass.simpleName)
        }
    }
}

inline fun <T, R> PetResult<T>.map(f: (T) -> R): PetResult<R> = when (this) {
    is PetResult.Ok -> PetResult.Ok(f(value))
    is PetResult.Rejected -> this
    is PetResult.Transport -> this
    is PetResult.Unverified -> this
    is PetResult.Skipped -> this
}
