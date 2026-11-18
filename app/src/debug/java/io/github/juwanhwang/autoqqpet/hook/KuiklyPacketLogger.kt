package io.github.juwanhwang.autoqqpet.hook

import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * 抓包：宠物页面（Kuikly 动态模块）与宿主之间的唯一出入口。
 *
 * 静态依据（2026-10-14 从 qq_9375.apk 与真机下发的页面包里读出来的，不是猜的）：
 * 页面模块里 `w1/r.c` 带两个常量串 —— 模块名 `QQKuiklyPlatformApi` 与方法名 `sendPbRequest`，
 * 宿主类 `com.tencent.mobileqq.ntcompose.export.modules.QQKuiklyPlatformApi` 只暴露
 * `call(String name, params, Function1 cb)` 一个分发口（真正的实现方法已被混淆成 v/y/w）。
 * 所以钩 `call` 就能看到：哪个页面在发哪条命令、参数是什么、服务器回了什么。
 *
 * 这也解释了旧 PacketLogger 在 18 个 Java 发包点全落空：页面流量根本不走
 * `qqpet.delegate`/`ProtoUtils`/NT 分发层，而是这条 Kuikly 模块桥 + VSNetwork 栈。
 *
 * 开关：广播 `packet op=on|off|status`；默认关，关着时不改任何参数、不建代理，零行为影响。
 */
object KuiklyPacketLogger {

    private const val TAG = "PKT"
    private const val API_CLASS = "com.tencent.mobileqq.ntcompose.export.modules.QQKuiklyPlatformApi"

    /**
     * 单次开启最多记多少行。包体已不截断、长包拆成多行，所以额度按“行”放宽：
     * 一个 36KB 回包（职业树）≈25 行，4 万行大约能容 1500 个包。
     */
    private const val MAX_LINES = 40_000

    /** 单行上限（字符）：logcat 单条约 4K，超了整条会被静默砍掉（真机就这样丢过 36KB 职业树与 7.4KB 信箱回包）。 */
    private const val CHUNK = 3000

    /** 本地取数方法每次都刷两行，会把额度吃光——它们不是命令。 */
    private val NOISY = setOf("getUin", "getUid", "getNetworkType", "isLoggable", "getDeviceInfo")

    /**
     * **只有这些调用才允许换回调**（白名单，不是黑名单）。
     *
     * `call` 是这个模块的总分发口，除了发包，QQ 还拿它做 `listenNetworkTypeChange`、`clearImageMemoryCache`、
     * `setEdgePopWidth` 这类本地调用。那些调用里的回调对象常被 QQ 当作**注册/反注册的身份 key**
     * （同一个对象注册、再拿同一个对象反注册），或被派到 UI 线程执行；换成代理会让反注册对不上号、
     * 监听泄漏，代理上的 equals/hashCode 也不再等于原对象。
     * 发包回调是一次性的、只在回包时调一次 `invoke`，换掉才是安全的；其它一律原样透传，只记请求行。
     * sendWebSSORequest（SVIP 礼包领取等走这条 Jsapi 通道）与 sendPbRequest 同样是一次性回包回调。
     */
    private val WRAP_CALLBACK = setOf("sendPbRequest", "sendWebSSORequest")

    @Volatile
    var enabled = false
        private set

    /** 最近一次发包调用所在的 Kuikly 模块实例（webssosend 重放用；页面销毁后该实例会拒绝发送）。 */
    @Volatile
    var lastApiInstance: Any? = null
        private set

    @Volatile
    private var installed = false

    private var hooked = 0
    private var delegateHooked = 0
    private val lines = AtomicInteger(0)

    fun install(module: XposedModule, loader: ClassLoader) {
        if (installed) return
        installed = true
        val cls = runCatching { loader.loadClass(API_CLASS) }.getOrNull()
        if (cls == null) {
            PLog.i(TAG, "抓包不可用：这台机器的 QQ 里没有 $API_CLASS")
            return
        }
        for (m in cls.declaredMethods) {
            val p = m.parameterTypes
            if (m.name != "call" || p.size != 3 || p[0] != String::class.java) continue
            if (hookOne(module, m)) hooked++
        }
        PLog.i(TAG, "抓包 hook 已装：$hooked 个 call 重载（默认关，广播 packet op=on 打开）")
        hookVsNetwork(module, loader)
        hookPetModule(module, loader)
        hookRewardVideoModule(module, loader)
    }

    // ———————————————— 广告激励视频桥：QQKuiklyRewardVideoModule ————————————————

    /**
     * 打工结算页的「看视频 +N 金币」走这条专用桥（2026-11-11 静态定位）：
     * `loadRewardVideoAd` / `showRewardVideoAd` / `reportRewardVideoAd`（报奖，带广告 SDK 的 adRsp）。
     * 宿主类名未混淆：`com.tencent.mobileqq.vas.adv.kuikly.reward.QQKuiklyRewardVideoModule`。
     */
    private val REWARD_VIDEO_CLASS = "com.tencent.mobileqq.vas.adv.kuikly.reward.QQKuiklyRewardVideoModule"

    private fun hookRewardVideoModule(module: XposedModule, loader: ClassLoader) {
        val cls = runCatching { loader.loadClass(REWARD_VIDEO_CLASS) }.getOrNull() ?: return
        var n = 0
        for (m in cls.declaredMethods) {
            val p = m.parameterTypes
            if (m.name != "call" || p.size != 3 || p[0] != String::class.java) continue
            if (hookRewardOne(module, m)) n++
        }
        if (n > 0) PLog.i(TAG, "奖励视频桥钩子已装：$n 个 call（与 packet 开关共用，默认关）")
    }

    private fun hookRewardOne(module: XposedModule, method: Method): Boolean = runCatching {
        module.hook(method)
            .setId("autoqqpet_pkt_reward_" + System.identityHashCode(method))
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                val name = args.getOrNull(0) as? String ?: "?"
                if (!enabled || lines.get() >= MAX_LINES) {
                    chain.proceed(args)
                } else {
                    val wrapped = wrapCallback(args.getOrNull(2), name)
                    log("[RewardAd.$name 请求] ${brief(args.getOrNull(1))}")
                    val ret = try {
                        if (wrapped == null) chain.proceed(args) else chain.proceed(arrayOf(args[0], args[1], wrapped))
                    } catch (t: Throwable) {
                        log("[RewardAd.$name 抛错] ${t.javaClass.simpleName}: ${t.message}")
                        throw t
                    }
                    log("[RewardAd.$name 同步返回] ${brief(ret)}")
                    ret
                }
            }
        true
    }.getOrDefault(false)

    // ———————————————— 宠物专用桥：QQPetModule.call ————————————————

    /**
     * 宠物 Kuikly 页（ai_pet_* 动态包）还有一个**专用桥**：`QQPetModule.call(name, params, cb)`。
     * 结算页的「看视频 +N 金币」等页面动作走这里（2026-11-11 截图定位），通用桥和 VSNetwork 都看不到。
     * 类名跨版本会变（9.3.75 叫 aa，9.3.80 叫 QQPetModule）→ 按已知名字逐个试。
     */
    private val PET_MODULE_CLASSES = listOf(
        "com.tencent.mobileqq.qqpet.kuikly.module.QQPetModule",
        "com.tencent.mobileqq.qqpet.kuikly.module.aa",
    )

    private var petHooked = 0

    private fun hookPetModule(module: XposedModule, loader: ClassLoader) {
        for (name in PET_MODULE_CLASSES) {
            val cls = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            for (m in cls.declaredMethods) {
                val p = m.parameterTypes
                if (m.name != "call" || p.size != 3 || p[0] != String::class.java) continue
                if (hookPetOne(module, m)) petHooked++
            }
        }
        if (petHooked > 0) PLog.i(TAG, "宠物桥钩子已装：$petHooked 个 call（与 packet 开关共用，默认关）")
    }

    private fun hookPetOne(module: XposedModule, method: Method): Boolean = runCatching {
        module.hook(method)
            .setId("autoqqpet_pkt_pet_" + System.identityHashCode(method))
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                val name = args.getOrNull(0) as? String ?: "?"
                if (!enabled || lines.get() >= MAX_LINES) {
                    chain.proceed(args)
                } else {
                    // register*/unregister* 的回调是注册身份 key（同一对象注册/反注册），不能换
                    val wrap = !name.startsWith("register") && !name.startsWith("unregister") && !name.startsWith("unRegister")
                    val wrapped = if (wrap) wrapCallback(args.getOrNull(2), name) else null
                    log("[PetMod.$name 请求] ${brief(args.getOrNull(1))}")
                    val ret = try {
                        if (wrapped == null) chain.proceed(args) else chain.proceed(arrayOf(args[0], args[1], wrapped))
                    } catch (t: Throwable) {
                        log("[PetMod.$name 抛错] ${t.javaClass.simpleName}: ${t.message}")
                        throw t
                    }
                    log("[PetMod.$name 同步返回] ${brief(ret)}")
                    ret
                }
            }
        true
    }.getOrDefault(false)

    private fun hookOne(module: XposedModule, method: Method): Boolean = runCatching {
        module.hook(method)
            .setId("autoqqpet_pkt_" + System.identityHashCode(method))
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                // libxposed 的 chain.args 是 List，proceed 要数组（旧 PacketLogger 同样这么转）
                val args = chain.args.toTypedArray()
                val name = args.getOrNull(0) as? String ?: "?"
                if (enabled && name in WRAP_CALLBACK) lastApiInstance = chain.thisObject
                if (args.size != 3 || name in NOISY || !enabled || lines.get() >= MAX_LINES) {
                    chain.proceed(args)
                } else {
                    val cb = args[2]
                    // 非发包调用：参数一个都不动，回调对象保持原身份
                    val wrapped = if (name in WRAP_CALLBACK) wrapCallback(cb, name) else null
                    log("[$name 请求] ${brief(args[1])}")
                    val ret = try {
                        if (wrapped == null) chain.proceed(args) else chain.proceed(arrayOf(args[0], args[1], wrapped))
                    } catch (t: Throwable) {
                        log("[$name 抛错] ${t.javaClass.simpleName}: ${t.message}")
                        throw t
                    }
                    log("[$name 同步返回] ${brief(ret)}")
                    ret
                }
            }
        true
    }.getOrDefault(false)

    /**
     * 把回调换成"记一行再转调原回调"的代理。
     * 用原对象自己实现的接口建代理（类型集合保持一致），任何失败都退回原回调——抓包不能改变行为。
     */
    private fun wrapCallback(cb: Any?, name: String): Any? {
        if (cb == null) return null
        // 宿主签名是 Function1：回调必须只靠接口被调用。若它是 Kotlin lambda 以外的具体类
        // （可能被 QQ 强转回原类型），或者根本不是 Function1，就不包，宁可少记一行。
        val fn1 = runCatching { cb.javaClass.classLoader?.loadClass("kotlin.jvm.functions.Function1") }.getOrNull()
            ?: return null
        if (!fn1.isInstance(cb)) return null
        return runCatching {
            Proxy.newProxyInstance(cb.javaClass.classLoader, arrayOf(fn1)) { proxy, m, ms ->
                when (m.name) {
                    // Object 方法别转给原对象：equals 拿到的是代理自己，转过去永远 false
                    "equals" -> proxy === ms?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "PktProxy(" + cb.toString() + ")"
                    else -> {
                        val call = try {
                            m.invoke(cb, *(ms ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException ?: e   // 原回调抛什么就原样抛什么，别包一层
                        }
                        // 真响应在回调的**入参**里，返回值往往只是 Unit；两边都记才能定性
                        if (enabled && m.name == "invoke") log("[回调入参 $name] ${brief(ms?.firstOrNull())} → 返回 ${brief(call)}")
                        call
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * 记一行；超过 [CHUNK] 拆成多行，行首 `[[i/n]]`，由 tools/pet_recon/capture.py 的 merge_chunks() 拼回。
     * 计数原子化：两条钩子（页面侧/原生侧）可能在不同线程同时记。
     */
    private fun log(s: String) {
        if (s.length <= CHUNK) {
            if (lines.incrementAndGet() > MAX_LINES) return
            PLog.i(TAG, s)
            return
        }
        val parts = s.chunked(CHUNK)
        for ((i, part) in parts.withIndex()) {
            if (lines.incrementAndGet() > MAX_LINES) return
            PLog.i(TAG, "[[${i + 1}/${parts.size}]] $part")
        }
    }

    /** 只留能定性的部分：字符串原样（截断）、字节数组转 hex、其它取 toString。 */
    private fun brief(o: Any?): String = when (o) {
        null -> "null"
        is ByteArray -> "bin(${o.size})=${hex(o, 200_000)}"   // 不在这里截：过长交给 log() 拆多行
        is Collection<*> -> o.joinToString(",", limit = 6) { brief(it) }
        is Array<*> -> o.joinToString(",", limit = 6) { brief(it) }
        else -> o.toString().take(200_000)
    }

    private fun hex(b: ByteArray, cap: Int): String {
        val hx = "0123456789abcdef".toCharArray()
        val sb = StringBuilder()
        for (x in b) {
            if (sb.length >= cap * 2) { sb.append("…"); break }
            val v = x.toInt() and 0xFF
            sb.append(hx[v shr 4]).append(hx[v and 0xF])
        }
        return sb.toString()
    }

    /** 打开时清零计数，方便按一次操作读一段。 */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) lines.set(0)
    }

    fun status(): String = "kuikly=$hooked delegate=$delegateHooked 行=${lines.get().coerceAtMost(MAX_LINES)}"

    // ———————————————— 原生侧：qqpet.delegate 发包方法 ————————————————

    /**
     * 再钩一条路：`qqpet.delegate` 的发包方法（由 QQPetBridge 的发现结果提供，不写死类名）。
     * 属性面板、原生 ergo 主页不走 Kuikly 桥，走这条；我们自己的发包也走这条。同一个 `packet` 开关，关着时不建代理。
     * 与页面侧同样的保护：只包“一次性”的结果回调（带 onResult 的接口），Object 方法不转发，解包 InvocationTargetException，任何失败退回原回调。
     */
    @Volatile private var delegateInstalled = false

    fun installDelegate(module: XposedModule, methods: List<Method>) {
        if (delegateInstalled) return
        delegateInstalled = true
        for (m in methods) if (hookDelegateOne(module, m)) delegateHooked++
        PLog.i(TAG, "原生侧抓包已装：$delegateHooked 个发包方法（与页面侧共用 packet 开关，默认关）")
    }

    private fun hookDelegateOne(module: XposedModule, method: Method): Boolean = runCatching {
        val cbIndex = method.parameterTypes.indexOfLast { t -> t.isInterface && t.methods.any { it.name == "onResult" } }
        if (cbIndex < 0) return@runCatching false
        val cbType = method.parameterTypes[cbIndex]
        module.hook(method)
            .setId("autoqqpet_pkt_native_" + System.identityHashCode(method))
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                if (!enabled || lines.get() >= MAX_LINES || args.size <= cbIndex || args[cbIndex] == null) {
                    chain.proceed(args)
                } else {
                    val name = args.getOrNull(1) as? String ?: "?"
                    val tag = if (args.size >= 5) "$name ${args.getOrNull(2)}/${args.getOrNull(3)}" else name
                    log("[原生发 $tag] ${brief(args.getOrNull(0))}")
                    val wrapped = wrapOnResult(args[cbIndex]!!, cbType, tag)
                    chain.proceed(if (wrapped == null) args else args.copyOf().also { it[cbIndex] = wrapped })
                }
            }
        true
    }.getOrDefault(false)

    /** onResult(code, body, …) 的代理：只实现声明的回调接口；Object 方法自己答；原回调抛什么原样抛。 */
    private fun wrapOnResult(cb: Any, type: Class<*>, tag: String): Any? = runCatching {
        if (!type.isInstance(cb)) return@runCatching null
        Proxy.newProxyInstance(cb.javaClass.classLoader ?: type.classLoader, arrayOf(type)) { proxy, m, ms ->
            when (m.name) {
                "equals" -> proxy === ms?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PktNativeProxy(" + cb.toString() + ")"
                else -> {
                    if (enabled && m.name == "onResult") log("[原生应 $tag] code=${ms?.getOrNull(0)} body=${brief(ms?.getOrNull(1))}")
                    try {
                        m.invoke(cb, *(ms ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException ?: e
                    }
                }
            }
        }
    }.getOrNull()

    // ———————————————— 兜底：VSNetworkHelper.sendRequest ————————————————

    /**
     * 钩 VSNetwork 栈的总发送口：宠物页面专用桥（QQPetModule）、WebSSO、原生 ergo 页面的请求最终都从这里出去。
     * 只记请求类名/命令/包体，并把结果回调包一层记回包；不改参数、不改返回值，关着时零影响。
     */
    private const val VSNET_CLASS = "com.tencent.biz.richframework.network.VSNetworkHelper"

    @Volatile private var vsInstalled = false
    private var vsHooked = 0

    private fun hookVsNetwork(module: XposedModule, loader: ClassLoader) {
        if (vsInstalled) return
        vsInstalled = true
        val cls = runCatching { loader.loadClass(VSNET_CLASS) }.getOrNull()
        if (cls == null) {
            PLog.i(TAG, "VSNet 钩子不可用：这台机器的 QQ 里没有 $VSNET_CLASS")
            return
        }
        for (m in cls.declaredMethods) {
            if (m.name != "sendRequest") continue
            if (hookVsOne(module, m)) vsHooked++
        }
        PLog.i(TAG, "VSNetwork 钩子已装：$vsHooked 个 sendRequest 重载（与 packet 开关共用，默认关）")
    }

    private fun hookVsOne(module: XposedModule, method: Method): Boolean = runCatching {
        module.hook(method)
            .setId("autoqqpet_pkt_vs_" + System.identityHashCode(method))
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                if (!enabled || lines.get() >= MAX_LINES) {
                    chain.proceed(args)
                } else {
                    log("[VSNet 请求] ${describeVsRequest(args)}")
                    val types = method.parameterTypes
                    var wrapped = false
                    for (i in args.indices) {
                        val t = types.getOrNull(i) ?: continue
                        if (t.isInterface && t.name.endsWith("OnVSRspCallBack")) {
                            val p = wrapVsCallback(args[i], t)
                            if (p != null) { args[i] = p; wrapped = true }
                        }
                    }
                    val ret = try {
                        chain.proceed(args)
                    } catch (t: Throwable) {
                        log("[VSNet 抛错] ${t.javaClass.simpleName}: ${t.message}")
                        throw t
                    }
                    log("[VSNet 同步返回] ${brief(ret)}")
                    ret
                }
            }
        true
    }.getOrDefault(false)

    /** 从参数里找带 getCmdName()/getRequestByteData() 的请求对象（BaseRequest 子类）；包体取不到时兜底扫 byte[] 字段。 */
    private fun describeVsRequest(args: Array<Any?>): String {
        for (a in args) {
            if (a == null) continue
            val cls = a.javaClass
            val cmd = runCatching { cls.getMethod("getCmdName").invoke(a) as? String }.getOrNull()
            var body = runCatching { cls.getMethod("getRequestByteData").invoke(a) as? ByteArray }.getOrNull()
            if (body == null) {
                body = runCatching {
                    val f = cls.declaredFields.firstOrNull { it.type == ByteArray::class.java } ?: return@runCatching null
                    f.isAccessible = true
                    f.get(a) as? ByteArray
                }.getOrNull()
            }
            if (cmd != null || body != null) return "${cls.simpleName} cmd=$cmd body=${brief(body)}"
        }
        return args.joinToString(",") { brief(it) }
    }

    /** 回包回调代理：只实现声明的接口，Object 方法自答，原回调抛什么原样抛。 */
    private fun wrapVsCallback(cb: Any?, type: Class<*>): Any? {
        if (cb == null || !type.isInstance(cb)) return null
        return runCatching {
            Proxy.newProxyInstance(cb.javaClass.classLoader ?: type.classLoader, arrayOf(type)) { proxy, m, ms ->
                when (m.name) {
                    "equals" -> proxy === ms?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "PktVsProxy(" + cb.toString() + ")"
                    else -> {
                        if (enabled) log("[VSNet 回包 ${m.name}] " + (ms ?: emptyArray()).joinToString(",") { brief(it) })
                        try {
                            m.invoke(cb, *(ms ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException ?: e
                        }
                    }
                }
            }
        }.getOrNull()
    }
}
