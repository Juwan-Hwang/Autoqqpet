package io.github.juwanhwang.autoqqpet.ui

import android.content.Context
import java.security.MessageDigest

/**
 * 协议“是否已同意”的判定依据：**文本指纹**，不是版本号。
 *
 * 为什么不用版本号：版本号要靠人记得改——改了文案忘改号，用户看不到新条款；
 * 忘了改文案只改了号，又会平白打扰所有人。指纹由文本自动算出：
 *   - 条款内容变 → 指纹变 → 要求重新确认（这正是它该起作用的时候）；
 *   - 内容没动 → 指纹不变 → 老用户再来不会被反复追问。
 * 同意状态落在模块 App 自己的 prefs 里（与 auto_on 同一份镜像文件，键名独立、互不影响）。
 */
object EulaTerms {

    private const val ASSET = "eula.md"

    /** 已同意条款的指纹存在这个键下；空/不匹配 = 尚未同意当前这版条款。 */
    const val KEY_DIGEST = "eula_digest"

    /** 协议正文（读失败也返回一段稳定文本，保证两侧指纹一致、行为可预期）。 */
    fun text(context: Context): String = try {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        "读取协议文本失败：${t.message}\n\n请重新安装，或在项目主页 仓库查看 EULA.md。"
    }

    /** 当前条款的指纹。 */
    fun digest(context: Context): String = sha256(text(context))

    /** 当前这版条款是否已同意。 */
    fun agreed(context: Context): Boolean =
        prefs(context).getString(KEY_DIGEST, null) == digest(context)

    /** 同意当前这版条款。 */
    fun markAgreed(context: Context) {
        prefs(context).edit().putString(KEY_DIGEST, digest(context)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
