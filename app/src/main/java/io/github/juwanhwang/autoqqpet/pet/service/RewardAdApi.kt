package io.github.juwanhwang.autoqqpet.pet.service

import android.app.Activity
import android.content.Intent
import io.github.juwanhwang.autoqqpet.hook.ActivityTracker
import io.github.juwanhwang.autoqqpet.hook.HostRuntime
import io.github.juwanhwang.autoqqpet.log.PLog
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.random.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 激励视频奖励（QQ 宠物的「看视频」奖励）。
 *
 * 页面侧走 `QQKuiklyRewardVideoModule.call(load/show/report)`，宿主实现最终落在
 * `au2.i`（`VasRewardAdHelper` 单例）上。这里复用同一个 helper，自动流程在后台也能调用。
 *
 * 抓包依据（2026-11-11，QQ 9.3.80）：
 *  - rewardType=60：结算页「看视频 +金币」，rewardItem={petId, storyId}
 *  - rewardType=61：每日广告免费福利，rewardItem={scene:"gift_bundle", gift_bundle:{gift_id:"10002", pet_id}}
 *  - load 回 adSequence；show 要 adSequence；report 要 adSequence + isFinished + rewardType + rewardItem
 */
class RewardAdApi(@Suppress("unused") private val loader: ClassLoader) {

    companion object {
        const val TYPE_STORY_COIN = 60
        const val TYPE_GIFT_BUNDLE = 61
        const val DAILY_GIFT_ID = "10002"

        private const val TAG = "RewardAd"
        private const val BIZ_SRC = "biz_src_kuiklyRewardVideo"
        private const val CUSTOM_REWARD_TEXT = "已获得奖励"

        /** 每日广告免费福利的 rewardItem（gift_bundle + 固定礼包 10002）。纯函数，可单测。 */
        fun dailyGiftItem(petId: String): String =
            JSONObject()
                .put("scene", "gift_bundle")
                .put("gift_bundle", JSONObject().put("gift_id", DAILY_GIFT_ID).put("pet_id", petId))
                .toString()

        /** 结算页看视频加金币的 rewardItem。纯函数，可单测。 */
        fun storyCoinItem(petId: String, storyId: String): String =
            JSONObject().put("petId", petId).put("storyId", storyId).toString()
    }

    data class AdResult(
        val ok: Boolean,
        val code: Int,
        val message: String,
        val remote: String = "",
        val isEnd: Boolean = false,
    )

    data class ShowResult(val isEnd: Boolean, val elapsed: Int, val total: Int)

    private class AdError(message: String) : Exception(message)

    /** 反射元数据；宿主版本不匹配时这里会失败，由 [meta] 吞掉并让功能静默降级。 */
    private class Meta(loader: ClassLoader) {
        val helperClass: Class<*> = loader.loadClass("au2.i")
        val helper: Any = helperClass.getField("a").get(null)

        val loadParams: Class<*> = loader.loadClass("cu2.b")
        val reportParams: Class<*> = loader.loadClass("cu2.a")
        val callbackClass: Class<*> = loader.loadClass("kt2.a")
        val showCallbackClass: Class<*> = loader.loadClass("bu2.a")
        val rspClass: Class<*> = loader.loadClass("com.tencent.mobileqq.vas.adv.common.pb.vac_adv_get\$VacAdvRsp")
        val reportInfoClass: Class<*> = loader.loadClass("com.tencent.mobileqq.vas.adv.common.pb.adv_report\$RewardReportInfo")
        val adInfoClass: Class<*> = loader.loadClass("tencent.gdt.qq_ad_get\$QQAdGetRsp\$AdInfo")

        val loadCtor: Constructor<*> = loadParams.getConstructor(
            Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType, String::class.java,
        )
        val reportCtor: Constructor<*> = reportParams.getConstructor(
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType, rspClass, reportInfoClass,
        )

        val load: Method = helperClass.getMethod("h", loadParams, callbackClass)
        val show: Method = helperClass.getMethod(
            "i", Activity::class.java, adInfoClass, WeakReference::class.java, String::class.java, String::class.java,
        )
        val report: Method = helperClass.getMethod("g", reportParams, callbackClass)
        val getRsp: Method = helperClass.getMethod("d", Long::class.javaPrimitiveType)
        val nextSeq: Method = helperClass.getMethod("b")
        val store: Method = helperClass.getMethod("j", Long::class.javaPrimitiveType, rspClass)
    }

    private val meta: Meta? by lazy {
        runCatching { Meta(loader) }
            .onFailure { PLog.w(TAG, "广告组件不可用（QQ 版本可能已变）：${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
    }

    /**
     * 领取一次广告奖励。成功 = 服务器 remoteCode=0。任何失败都收成 [AdResult]，不抛给自动流程。
     *
     * [background]=true 时在后台完成领取；服务端拒绝时回退到正常流程。
     */
    @Synchronized
    fun watch(
        rewardType: Int,
        rewardItem: String,
        timeoutMs: Long = 180_000L,
        background: Boolean = false,
    ): AdResult {
        val m = meta ?: return AdResult(false, -1, "广告组件不可用（QQ 版本可能已变）")
        var last = ""
        var backgroundLeft = background
        for (attempt in 0 until 2) {
            try {
                val seq = load(m, rewardType, rewardItem)
                if (backgroundLeft) {
                    backgroundLeft = false
                    Thread.sleep(900L + Random.nextLong(1_200L))
                    PLog.i(TAG, "后台领取 type=$rewardType seq=$seq")
                    val remote = report(m, seq, true, rewardType, rewardItem)
                    val remoteCode = runCatching { JSONObject(remote).optInt("remoteCode", 0) }.getOrDefault(0)
                    if (remoteCode == 0) return AdResult(true, 0, "已领取", remote, true)
                    last = "后台领取未通过（remoteCode=$remoteCode），改正常播放"
                    PLog.w(TAG, last)
                    continue
                }
                PLog.i(TAG, "广告已加载 type=$rewardType seq=$seq，开始播放")
                val shown = show(m, seq, timeoutMs)
                if (!shown.isEnd) {
                    runCatching { report(m, seq, false, rewardType, rewardItem) }
                    return AdResult(false, 1, "广告没有看完", isEnd = false)
                }
                val remote = report(m, seq, true, rewardType, rewardItem)
                val remoteCode = runCatching { JSONObject(remote).optInt("remoteCode", 0) }.getOrDefault(0)
                return if (remoteCode == 0) {
                    AdResult(true, 0, "已看完", remote, true)
                } else {
                    AdResult(false, -2, "服务器未发奖（remoteCode=$remoteCode）", remote, true)
                }
            } catch (t: Throwable) {
                last = t.message ?: t.javaClass.simpleName
                PLog.w(TAG, "广告第 ${attempt + 1} 次失败：$last")
                if (last.contains("前台页面")) break
                Thread.sleep(1_500L)
            }
        }
        return AdResult(false, -1, last.ifEmpty { "广告失败" })
    }

    /** 每日广告免费福利（rewardType 61）。 */
    fun watchDailyGift(petId: String, background: Boolean = false): AdResult =
        watch(TYPE_GIFT_BUNDLE, dailyGiftItem(petId), background = background)

    /** 结算页看视频加金币（rewardType 60）。 */
    fun watchStoryCoin(petId: String, storyId: String, background: Boolean = false): AdResult =
        watch(TYPE_STORY_COIN, storyCoinItem(petId, storyId), background = background)

    private fun load(m: Meta, rewardType: Int, rewardItem: String): Long {
        val latch = CountDownLatch(1)
        var rsp: Any? = null
        var err = ""
        val cb = Proxy.newProxyInstance(loader, arrayOf(m.callbackClass), InvocationHandler { _, method, args ->
            when (method.name) {
                "a" -> {
                    rsp = args?.getOrNull(0)
                    latch.countDown()
                }
                "b" -> {
                    err = errorText(args)
                    latch.countDown()
                }
            }
            null
        })
        val seq = m.nextSeq.invoke(m.helper) as Long
        val params = m.loadCtor.newInstance(rewardType, rewardItem, 0, "")
        m.load.invoke(m.helper, params, cb)
        if (!latch.await(25, TimeUnit.SECONDS)) throw AdError("广告加载超时")
        val got = rsp ?: throw AdError("广告加载失败${if (err.isNotEmpty()) "：$err" else ""}")
        m.store.invoke(m.helper, seq, got)
        return seq
    }

    private fun show(m: Meta, seq: Long, timeoutMs: Long): ShowResult {
        val rsp = m.getRsp.invoke(m.helper, seq) ?: throw AdError("广告已过期（没有加载结果）")
        val adInfo = firstAdInfo(rsp) ?: throw AdError("广告内容为空（可能已过期）")
        val activity = activity() ?: throw AdError("QQ 没有前台页面，无法播放广告")
        val latch = CountDownLatch(1)
        var end = false
        var elapsed = 0
        var total = 0
        val cb = Proxy.newProxyInstance(loader, arrayOf(m.showCallbackClass), InvocationHandler { _, method, args ->
            if (method.name == "a") {
                end = args?.getOrNull(0) as? Boolean ?: false
                elapsed = (args?.getOrNull(1) as? Int) ?: 0
                total = (args?.getOrNull(2) as? Int) ?: 0
                latch.countDown()
            }
            null
        })
        runOnMain {
            m.show.invoke(m.helper, activity, adInfo, WeakReference<Any>(cb), BIZ_SRC, CUSTOM_REWARD_TEXT)
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw AdError("广告播放超时（没有收到播放完成回调）")
        PLog.i(TAG, "广告播放结束 isEnd=$end elapsed=$elapsed total=$total")
        return ShowResult(end, elapsed, total)
    }

    private fun report(m: Meta, seq: Long, isFinished: Boolean, rewardType: Int, rewardItem: String): String {
        val rsp = m.getRsp.invoke(m.helper, seq) ?: throw AdError("广告加载结果已失效")
        val latch = CountDownLatch(1)
        var reportRsp: Any? = null
        var err = ""
        val cb = Proxy.newProxyInstance(loader, arrayOf(m.callbackClass), InvocationHandler { _, method, args ->
            when (method.name) {
                "a" -> {
                    reportRsp = args?.getOrNull(0)
                    latch.countDown()
                }
                "b" -> {
                    err = errorText(args)
                    latch.countDown()
                }
            }
            null
        })
        val params = m.reportCtor.newInstance(seq, isFinished, rewardType, rewardItem, 0, rsp, null)
        m.report.invoke(m.helper, params, cb)
        if (!latch.await(25, TimeUnit.SECONDS)) throw AdError("奖励上报超时")
        val got = reportRsp ?: throw AdError("奖励上报失败${if (err.isNotEmpty()) "：$err" else ""}")
        return describeReport(got)
    }

    private fun firstAdInfo(rsp: Any): Any? = runCatching {
        val msgs = rsp.javaClass.getField("vac_adv_msgs").get(rsp)
        val list = msgs.javaClass.getMethod("get").invoke(msgs) as? List<*> ?: return null
        val meta = list.firstOrNull() ?: return null
        meta.javaClass.getField("adv_rsp").get(meta)
    }.getOrNull()

    private fun describeReport(rsp: Any): String {
        val retCode = runCatching {
            val f = rsp.javaClass.getField("ret_code").get(rsp)
            f.javaClass.getMethod("get").invoke(f) as? Int ?: 0
        }.getOrDefault(0)
        val errMsg = runCatching {
            val f = rsp.javaClass.getField("error_msg").get(rsp)
            f.javaClass.getMethod("get").invoke(f) as? String ?: ""
        }.getOrDefault("")
        val busi = runCatching {
            val f = rsp.javaClass.getField("busi_buffer").get(rsp)
            val bsm = f.javaClass.getMethod("get").invoke(f)
            String(bsm.javaClass.getMethod("toByteArray").invoke(bsm) as ByteArray, Charsets.UTF_8)
        }.getOrDefault("")
        return JSONObject()
            .put("code", 0)
            .put("remoteCode", retCode)
            .put("remoteErrMsg", errMsg)
            .put("remoteBusiBuffer", busi)
            .toString()
    }

    private fun errorText(args: Array<Any?>?): String {
        val msg = args?.getOrNull(1)?.toString().orEmpty()
        val code = args?.getOrNull(0)?.toString().orEmpty()
        return msg.ifEmpty { code }.ifEmpty { "未知错误" }
    }

    private fun activity(): Activity? {
        ActivityTracker.current()?.let { return it }
        val ctx = HostRuntime.appContext() ?: return null
        runCatching {
            ctx.startActivity(
                Intent()
                    .setClassName("com.tencent.mobileqq", "com.tencent.mobileqq.activity.SplashActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        repeat(40) {
            Thread.sleep(200L)
            ActivityTracker.current()?.let { return it }
        }
        return ActivityTracker.current()
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var thrown: Throwable? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw AdError("启动广告页超时")
        thrown?.let { throw it }
    }
}
