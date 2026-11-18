package io.github.juwanhwang.autoqqpet.net

import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.HashMap

/**
 * 直调 QQ 的 VSNetworkHelper.sendRequest 发 WebSSO，不依赖活着的 Kuikly 页面实例。
 *
 * 静态链路（2026-11-10，qq 9.3.75 classes4 反编译确认）：
 *   QQKuiklyPlatformApi.sendWebSSORequest(params)
 *     → WebSsoBody$WebSsoRequestBody(data).toByteArray()
 *     → QQKuiklyPlatformApi$sendVSNetworkRequest$1(cmd, body, withoutLogin, ?, preloadKey, timeout, transInfo, isSupportRetry, traceId)
 *     → VSNetworkHelper.getInstance().sendRequest(Context, BaseRequest, OnVSRspCallBack)
 *
 * 类名与构造器按签名扫描（混淆名跨版本会变），任何一步失败都返回错误描述、不抛异常。
 */
object WebSsoDirect {

    /**
     * @param paramsJson 与页面 `sendWebSSORequest` 的参数同构：
     *   {"cmd":"…","data":{…},"traceId":"…","withoutLogin":0,"timeout":6,"isSupportRetry":false}
     * @param onResult 结果回调（参数为宿主回调的原始入参，通常是 code/errMsg/bundle）
     */
    fun send(paramsJson: String, onResult: (Array<Any?>?) -> Unit = {}): String = runCatching {
        val params = JSONObject(paramsJson)
        val cmd = params.optString("cmd")
        require(cmd.isNotEmpty()) { "缺少 cmd" }
        val data = params.optJSONObject("data") ?: JSONObject()
        val traceId = params.optString("traceId")
        val withoutLogin = params.optInt("withoutLogin", 0)
        val timeout = params.optInt("timeout", 6)
        val retry = params.optBoolean("isSupportRetry", false)

        val loader = hostLoader()
        val ctx = hostContext() ?: return "拿不到 Application context"
        val bodyBytes = buildSsoBody(loader, data.toString())
        val request = newWebSsoRequest(loader, cmd, bodyBytes, withoutLogin, timeout, retry, traceId)
        val (helper, send) = findSender(loader)
        val cbType = send.parameterTypes[2]
        val cb = Proxy.newProxyInstance(cbType.classLoader ?: loader, arrayOf(cbType)) { proxy, m, args ->
            when (m.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "WebSsoDirectCb"
                else -> { runCatching { onResult(args) }; null }
            }
        }
        val ret = send.invoke(helper, ctx, request, cb)
        "已发送(return=$ret)"
    }.getOrElse { "失败: ${it.javaClass.simpleName}: ${it.message}" }

    private fun hostLoader(): ClassLoader =
        hostContext()?.classLoader ?: ClassLoader.getSystemClassLoader()

    private fun hostContext(): android.content.Context? = runCatching {
        // ActivityThread 是 @hide：反射取宿主 Application 的 context 与 classLoader
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.content.Context
    }.getOrNull()

    /** WebSsoBody$WebSsoRequestBody：唯一的 PBStringField 就是 data 字段（不写死混淆字段名）。 */
    private fun buildSsoBody(loader: ClassLoader, data: String): ByteArray {
        val cls = loader.loadClass("com.tencent.mobileqq.WebSsoBody\$WebSsoRequestBody")
        val body = cls.getDeclaredConstructor().newInstance()
        val field = cls.declaredFields.firstOrNull { it.type.name.endsWith("PBStringField") }
            ?: error("WebSsoRequestBody 里找不到 PBStringField")
        field.isAccessible = true
        val pb = field.get(body) ?: error("PBStringField 实例为 null")
        pb.javaClass.methods.first { it.name == "set" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
            .invoke(pb, data)
        return cls.getMethod("toByteArray").invoke(body) as ByteArray
    }

    /** 优先按名加载匿名请求类（$1 不是成员类，declaredClasses 看不到），失败再按签名扫成员类兜底。 */
    private fun newWebSsoRequest(
        loader: ClassLoader, cmd: String, body: ByteArray, withoutLogin: Int,
        timeout: Int, retry: Boolean, traceId: String,
    ): Any {
        val pkg = "com.tencent.mobileqq.ntcompose.export.modules.QQKuiklyPlatformApi"
        val apiCls = loader.loadClass(pkg)
        val byName = sequenceOf("$1", "$2", "$3").mapNotNull { suffix ->
            runCatching { loader.loadClass(pkg + "\$sendVSNetworkRequest" + suffix) }.getOrNull()
        }.firstOrNull { c ->
            c.declaredConstructors.any { it.parameterTypes.size == 9 && it.parameterTypes[0] == String::class.java }
        }
        val ctor = byName?.declaredConstructors?.firstOrNull {
            it.parameterTypes.size == 9 && it.parameterTypes[0] == String::class.java
        } ?: apiCls.declaredClasses.flatMap { it.declaredConstructors.toList() }.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 9 && p[0] == String::class.java && p[1] == ByteArray::class.java &&
                p[5] == Integer.TYPE && p[8] == String::class.java
        } ?: error("找不到 WebSSO 匿名请求类（sendVSNetworkRequest\\$1 签名变了）")
        ctor.isAccessible = true
        // 实参顺序以 9.3.80 类体为准（iput 字段名反推）：
        // (traceId→overrideTraceId, pbReqBytes, timeout(秒), isSupportRetry, cmd, withoutLogin, transInfoMap, useGuest, guestTinyId)
        val args = arrayOf<Any?>(
            traceId, body, timeout, retry, cmd, withoutLogin, HashMap<String, String>(), false, "",
        )
        return ctor.newInstance(*args)
    }

    private fun findSender(loader: ClassLoader): Pair<Any, java.lang.reflect.Method> {
        val cls = loader.loadClass("com.tencent.biz.richframework.network.VSNetworkHelper")
        val helper = cls.getMethod("getInstance").invoke(null)
        val send = cls.methods.firstOrNull { m ->
            m.name == "sendRequest" && m.parameterTypes.size == 3 &&
                m.parameterTypes[0].name.endsWith("Context") &&
                m.parameterTypes[1].name.endsWith("BaseRequest") &&
                m.parameterTypes[2].name.endsWith("OnVSRspCallBack")
        } ?: error("VSNetworkHelper.sendRequest(Context, BaseRequest, OnVSRspCallBack) 找不到了")
        return helper to send
    }
}
