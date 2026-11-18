package io.github.juwanhwang.autoqqpet.pet.service

import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.net.Commands
import io.github.juwanhwang.autoqqpet.net.Oidb
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.pet.QQPetBridge
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import java.util.concurrent.ConcurrentHashMap

/**
 * 写请求被服务器以 135075（petID 不匹配）拒绝时的自愈策略（纯逻辑，可单测）：
 * 刷新 petId 后**只重试一次**；刷新失败、或新 petId 与旧的一样（说明不是缓存过期）→ 原样抛原错，绝不循环。
 * petId 嵌在包体里，所以 [send] 每次调用都必须用当前 petId 现拼包体，重试时自然带上新值。
 */
internal object PetIdRefresh {
    fun <T> run(send: () -> T, currentPetId: () -> String, refreshPetId: () -> String): T {
        try {
            return send()
        } catch (e: QQPetBridge.ServerError) {
            if (e.code != Proto.CODE_PET_ID_MISMATCH) throw e
            val old = currentPetId()
            val fresh = runCatching { refreshPetId() }.getOrDefault("")
            if (fresh.isEmpty() || fresh == old) throw e
            return send()
        }
    }
}

/**
 * 各命令族共享的会话：发包桥 + 本人 petId。
 * 线程：可被 tick 线程与命令线程同时用；bridge 每次调用自带 latch，彼此独立；
 * petId 读一次后缓存（volatile），只在被服务器判失效（135075）时才刷新重读。
 */
class PetSession(val bridge: QQPetBridge) {

    @Volatile
    var petId: String = ""
        private set

    @Volatile
    var petName: String = ""
        private set

    fun selfUin(): String = runCatching { bridge.getSelfUin() }.getOrDefault("").orEmpty()

    /** 读一次本人资料（拿 petId）。 */
    fun loadProfile(): io.github.juwanhwang.autoqqpet.pet.OwnPetProfile {
        val p = Parsers.ownProfile(bridge.read(Commands.OWN_PROFILE, ByteArray(0)))
            ?: throw QQPetBridge.NotReady("服务器未返回本人宠物资料或缺 petId（账号可能未开通 QQ 宠物）")
        petId = p.petId
        petName = p.petName
        return p
    }

    fun ensurePetId(): String {
        if (petId.isEmpty()) loadProfile()
        return petId
    }

    /**
     * petId 被服务器判失效（135075）时刷新：先清缓存再重读资料。
     * @return 新 petId；刷新失败返回空串（缓存保持为空，下次 ensurePetId 会再读，不留旧值继续撞墙）。
     */
    private fun refreshPetId(): String {
        val old = petId
        petId = ""
        val fresh = runCatching { loadProfile().petId }
            .onFailure { PLog.w("PetSession", "petId 刷新失败：${it.message}") }
            .getOrDefault("")
        if (fresh.isNotEmpty() && fresh != old) PLog.i("PetSession", "🔄 petId 已被服务器判失效（135075），已重新获取，本次写请求带新值重试一次")
        return fresh
    }

    fun read(oidb: Oidb, body: ByteArray): ByteArray = bridge.read(oidb, body)

    /**
     * 写请求（全仓写命令的唯一中央入口）：包体以 lambda 传入，因为 petId 嵌在包体里——
     * 被 135075 拒时先刷新 petId、再**现拼一次包体**重试（见 [PetIdRefresh]），重试带的是新 petId 而不是原样重发。
     */
    fun write(oidb: Oidb, buildBody: () -> ByteArray): ByteArray =
        PetIdRefresh.run(
            send = { bridge.writeOnce(oidb, buildBody()) },
            currentPetId = { petId },
            refreshPetId = { refreshPetId() },
        )

    /** sub 未抓到的命令，试通过的 sub 记在这（进程级，重启后重新试一次）。 */
    private val learnedSub = ConcurrentHashMap<Int, Int>()

    /**
     * 发 sub 未知的命令：按 [Commands.FLEX_SUBS] 依次试，**只有**服务器回 150（sub 错）才换下一个——
     * 150 是“没权限/没这个子命令”，不会产生业务副作用，所以对写命令换 sub 也不算重发。
     */
    fun sendFlexible(base: Oidb, buildBody: () -> ByteArray, write: Boolean): ByteArray {
        if (base.subKnown) return if (write) write(base, buildBody) else read(base, buildBody())
        learnedSub[base.command]?.let { sub ->
            val o = base.withSub(sub)
            return if (write) write(o, buildBody) else read(o, buildBody())
        }
        var last: Throwable? = null
        for (sub in Commands.FLEX_SUBS) {
            val o = base.withSub(sub)
            try {
                val r = if (write) write(o, buildBody) else read(o, buildBody())
                learnedSub[base.command] = sub
                PLog.i("PetSession", "🔎 ${base.name.substringBeforeLast('_')} 的子命令试通：_$sub")
                return r
            } catch (e: QQPetBridge.ServerError) {
                if (e.code != Commands.CODE_WRONG_SUB) throw e
                last = e
            }
        }
        throw last ?: QQPetBridge.NotReady("${base.name} 所有候选子命令都不通")
    }
}
