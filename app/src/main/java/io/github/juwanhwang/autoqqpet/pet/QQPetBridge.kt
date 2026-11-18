package io.github.juwanhwang.autoqqpet.pet

import android.os.Bundle
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.net.Oidb
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 进程内 QQ 宠物发包桥。反射调用 QQ 现成的宠物协议委托类：
 *   - void c(byte[] body, String oidbName, int command, int subCommand, PetPbDelegate$a cb)  // OIDB
 *   - void a(byte[] body, String cmd, PetPbDelegate$a cb)                                    // SSO
 * 回调接口（`...PetPbDelegate$a`）：
 *   - void onResult(int code, byte[] data, Bundle extras)   // code=服务端 errorCode，data=已剥 envelope 的业务 body
 *
 * 说明：只传业务 body，envelope/签名/加密/会话由 QQ 自身处理（见 docs/qqpet-protocol-spec.md §1）。
 *
 * 【为什么不写死类名】那个委托类是混淆名单字母，而且 **QQ 每次大版本都会换**：
 * 9.3.65 是 `com.tencent.mobileqq.qqpet.delegate.l`，9.3.70 已经变成 `…delegate.m`
 * （真机 2026-10-10：写死 `l` 直接 `NoSuchMethodException: …l.c([BLjava/lang/String;II…)`，整个模块静默失效）。
 * 现在按【方法形状】运行时发现：只要委托包里有个类带 `x(byte[], String, int, int, <接口>)`，它就是入口，
 * 名字叫什么、回调接口叫什么都无所谓；下次再改名也不用改代码。
 */
class QQPetBridge(private val classLoader: ClassLoader) {

    /** 宿主 ClassLoader（广告等反射入口复用）。 */
    fun classLoader(): ClassLoader = classLoader

    companion object {
        private const val TAG = "QQPetBridge"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        /**
         * 调试用（`settimeout --ei ms 1`，0=恢复默认）：把**只读**请求的等待上限临时压到极短，
         * 用来确定性地造出“回调超时”——这个场景真机自然发生过（一天 31 次）但没法靠断网复现
         *（飞行模式下 QQ 仍会回包），而不自造就没法验证超时后的自愈链。
         * 只作用在 read：写操作不能因调试开关被削短（回包慢不等于没生效，会误判）。
         */
        @Volatile
        var readTimeoutOverrideMs: Long = 0L
        /** 宠物委托所在包（这个包名从 9.3.x 以来稳定，变的只是里面的单字母类名）。 */
        private const val DELEGATE_PKG = "com.tencent.mobileqq.qqpet.delegate."
        /** 已验证过的名字优先试，少绕几圈：9.3.70=m、9.3.65=l。 */
        private val PREFERRED = listOf("m", "l")
        /**
         * 非单字母的候选名（QQ 9.3.75 开头把宠物委托从混淆单字母改成了可读名）。
         * 放在 PREFERRED 之后：旧版 QQ 依旧先命中 m/l，命中类与顺序完全不变（向后兼容）。
         */
        private val NAMED = listOf("QQPetPbDelegate", "PetPbDelegate", "QQPetPbDelegateImpl")
        private const val MOBILEQQ_CLASS = "mqq.app.MobileQQ"
    }

    /** 发现结果：委托实例 + 两个发包方法 + 回调接口（代理用）。 */
    private class Entry(
        val delegate: Any,
        val oidb: Method,
        val sso: Method?,
        val callback: Class<*>,
        val where: String,
    )

    /**
     * 按形状找入口。判定条件很严（参数个数/类型/顺序全对，且末位参数是接口且该接口有 onResult），
     * 宁可不找到也不能错认一个同名方法。找不到时直说：界面/日志会说明“QQ 版本已变、待适配”。
     *
     * 两扫策略（向后兼容优先）：
     *  第一扫只认旧形状 (byte[], String, int cmd, int sub, cb) —— 9.3.65/9.3.70 走的就是它，
     *  而 9.3.75 虽然多了个 3 参重载，但 **5 参那个仍在**（真机 dexdump 核过），所以三个版本统一走这一扫；
     *  只有第一扫全部落空（万一哪天 QQ 删了带 cmd/sub 的重载）才去试 3 参形状，不影响老版本任何行为。
     *  为什么先扫类名而不是只扫 a..z：9.3.75 把类名改成了 QQPetPbDelegate，单字母列表根本碰不到它；
     *  而旧的 delegate.l（现在是下载器）/delegate.m（现在是异常工具）形状对不上，会被自然跳过、不会错认。
     */
    private fun discover(): Entry {
        val names = (PREFERRED + NAMED + ('a'..'z').map { it.toString() }).distinct()
        // 第一扫：只认带 cmd/sub 的 5 参形状（三个已知 QQ 版本都用这个，也是老行为）
        for (n in names) findEntry(n, wide = true)?.let { return it }
        // 第二扫：实在找不到了，才接受不带 cmd/sub 的 3 参形状（oidbName 本身就形如 0x9ab2_1）
        for (n in names) findEntry(n, wide = false)?.let { return it }
        throw NotReady("未在 ${DELEGATE_PKG} 里找到宠物发包入口（试了 ${names.size} 个候选类名 × 两种参数形状；QQ 版本可能已变，需适配）")
    }

    /** 在候选类里找匹配的发包方法；[wide]=true 要 5 参（带 cmd/sub），false 时只认 3 参。 */
    private fun findEntry(name: String, wide: Boolean): Entry? {
        val cls = try {
            classLoader.loadClass(DELEGATE_PKG + name)
        } catch (_: Throwable) {
            return null
        }
        val methods = try {
            cls.methods.toList()
        } catch (_: Throwable) {
            return null
        }
        val p = ByteArray::class.java
        val s = String::class.java
        val i = Int::class.javaPrimitiveType
        val want = if (wide) 5 else 3
        val oidb = methods.firstOrNull { m ->
            val t = m.parameterTypes
            t.size == want && t[0] == p && t[1] == s &&
                (!wide || (t[2] == i && t[3] == i)) &&
                t[want - 1].isInterface && t[want - 1].methods.any { it.name == "onResult" }
        } ?: return null
        // SSO 同型方法只在 5 参入口存在时再找（3 参入口本身就是 3 参，别把自己当“另一条通道”）
        val sso = if (wide) methods.firstOrNull { m ->
            val t = m.parameterTypes
            t.size == 3 && t[0] == p && t[1] == s && t[2] == oidb.parameterTypes[want - 1]
        }?.takeIf { it != oidb } else null
        val inst = try {
            cls.getDeclaredConstructor().newInstance()
        } catch (t: Throwable) {
            PLog.w(TAG, "候选 $DELEGATE_PKG$name 形状匹上了但建不出实例: ${t.message?.take(60)}")
            return null
        }
        PLog.i(TAG, "🔌 发包入口 ：${cls.name}.${oidb.name}（${want} 参${if (wide) "，带 cmd/sub" else "，不带 cmd/sub"}）｜ " +
            "回调=${oidb.parameterTypes[want - 1].name}" + (if (sso != null) " ｜ SSO=${sso.name}" else ""))
        return Entry(inst, oidb, sso, oidb.parameterTypes[want - 1], cls.name)
    }

    private val entry: Entry = discover()
    /** 回调接口（发现到的那个第 5 参数类型）。 */
    private val callbackInterface: Class<*> = entry.callback
    private val sendOidbMethod: Method = entry.oidb
    private val delegate: Any = entry.delegate

    /** 给抓包用：发现到的发包方法（OIDB + 可选 SSO）。 */
    fun sendMethodsForHook(): List<Method> = listOf(entry.oidb) + listOfNotNull(entry.sso)

    /** 发现到的入口类名（供日志与“版本不适配”提示用）。 */
    val entryName: String get() = entry.where

    /** 服务端返回非 0 errorCode。 */
    class ServerError(val code: Int, val detail: String) : Exception("$TAG: OIDB errorCode=$code $detail")

    /** 无有效登录会话 / 未就绪 / 调用失败。 */
    class NotReady(msg: String) : Exception("$TAG: $msg")

    /** 取得当前登录 QQ 号；未登录返回 null。 */
    fun getSelfUin(): String? = try {
        val mobileqq = classLoader.loadClass(MOBILEQQ_CLASS)
        val sMobileQQ = mobileqq.getField("sMobileQQ").get(null) ?: return null
        val runtime = sMobileQQ.javaClass.getMethod("peekAppRuntime").invoke(sMobileQQ) ?: return null
        val uin = runtime.javaClass.getMethod("getCurrentAccountUin").invoke(runtime)
        val s = uin?.toString()?.trim().orEmpty()
        if (s.isNotEmpty() && s.all { it.isDigit() } && s != "0") s else null
    } catch (t: Throwable) {
        PLog.w(TAG, "getSelfUin 失败: ${t.message}")
        null
    }

    /** 只读命令：空响应可安全重试。 */
    fun read(oidb: Oidb, body: ByteArray, attempts: Int = 3, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray {
        val budget = if (readTimeoutOverrideMs > 0) readTimeoutOverrideMs else timeoutMs
        var last: Exception = NotReady("${oidb.name} 读取失败")
        repeat(attempts.coerceAtLeast(1)) {
            try {
                val r = sendOidb(oidb, body, budget)
                if (r.isNotEmpty()) { IoStats.readOk.incrementAndGet(); return r }
                last = NotReady("${oidb.name} 返回空响应")
            } catch (e: ServerError) {
                IoStats.readOk.incrementAndGet()   // 服务器回了业务码 = 会话是通的
                throw e // 业务错误不重试
            } catch (e: Exception) {
                last = e
                // 回调压根没来（到点没任何回音）——原地重试只是把一轮 tick 多拖十几秒（默认 3 次×15s），
                // 网络没通时重试也不会有结果；真机今天自然发生过 31 次这种超时。
                // 只有“空响应”值得原地重试（同一条请求第二次常常就带回包）。
                if (e is NotReady && e.message?.contains("回调超时") == true) { IoStats.readFail.incrementAndGet(); throw e }
            }
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        IoStats.readFail.incrementAndGet()
        throw last
    }

    /** 单次写入：超时/空响应【禁止重发】，交上层用状态复查判定是否已生效。 */
    fun writeOnce(oidb: Oidb, body: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray = try {
        sendOidb(oidb, body, timeoutMs).also { (if (it.isEmpty()) IoStats.writeEmpty else IoStats.writeOk).incrementAndGet() }
    } catch (e: NotReady) {
        // 写请求没回包：只计数，不算“会话断了”（R4：重复写服务端会回空包/不回）
        IoStats.writeTimeout.incrementAndGet(); throw e
    }

    private fun sendOidb(oidb: Oidb, body: ByteArray, timeoutMs: Long): ByteArray {
        val arity = sendOidbMethod.parameterTypes.size
        val result = invoke(timeoutMs) { cb ->
            // 5 参：(body, oidbName, cmd, sub, cb)——9.3.65/9.3.70/9.3.75 都是这条；
            // 3 参：(body, oidbName, cb)——仅当第二扫兜底时才会用到
            if (arity == 5) sendOidbMethod.invoke(delegate, body, oidb.name, oidb.command, oidb.subCommand, cb)
            else sendOidbMethod.invoke(delegate, body, oidb.name, cb)
        }
        if (result.isEmpty()) PLog.d(TAG, "${oidb.name} 空响应")
        return result
    }

    /** SSO 发包（新版可能不带这个方法，发现不到时 [entry] 里就是 null）。目前业务走 OIDB，保留供调试。 */
    @Suppress("unused")
    private fun sendSso(cmd: String, body: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray {
        val m = entry.sso ?: throw NotReady("当前 QQ 版本没有 SSO 发包方法（只有 OIDB）")
        return invoke(timeoutMs) { cb -> m.invoke(delegate, body, cmd, cb) }
    }

    /** 反射发起调用并把异步回调收敛为同步返回的 body。 */
    private fun invoke(timeoutMs: Long, caller: (Any) -> Unit): ByteArray {
        val latch = CountDownLatch(1)
        var code = Int.MIN_VALUE
        var data: ByteArray = ByteArray(0)
        var message = ""
        var thrown: Throwable? = null

        val handler = InvocationHandler { proxy, method: Method, args: Array<Any?>? ->
            when (method.name) {
                "onResult" -> {
                    try {
                        code = (args?.getOrNull(0) as? Int) ?: -1
                        data = (args?.getOrNull(1) as? ByteArray) ?: ByteArray(0)
                        val bundle = args?.getOrNull(2) as? Bundle
                        message = try {
                            bundle?.getString("data_error_msg") ?: ""
                        } catch (_: Throwable) {
                            ""
                        }
                    } catch (t: Throwable) {
                        thrown = t
                    } finally {
                        latch.countDown()
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "QQPetBridgeCallback"
                else -> null
            }
        }
        val cb = Proxy.newProxyInstance(classLoader, arrayOf(callbackInterface), handler)

        try {
            caller(cb)
        } catch (t: Throwable) {
            // 反射调用报出的往往是 InvocationTargetException，真正的异常在 cause 里；
            // 只打 t.message 会得到 null（真机 9.3.70 适配时就这么被坑过一轮），必须拆到底。
            val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
            val head = real.stackTrace.firstOrNull()?.let { " @${it.fileName ?: it.className}:${it.lineNumber}" }.orEmpty()
            throw NotReady("发起调用失败: ${real.javaClass.simpleName}: ${real.message?.take(120) ?: "(无消息)"}$head")
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw NotReady("回调超时")
        }
        thrown?.let { throw it }
        if (code != 0) {
            // 负码（如 -1 wait serverResp timeout）是 QQ 传输层错误，多为瞬时，应可重试；
            // 正码才是服务端业务 errorCode（如 319/135061），不可重试。
            if (code < 0) throw NotReady("transport code=$code $message")
            throw ServerError(code, message)
        }
        return data
    }
}
