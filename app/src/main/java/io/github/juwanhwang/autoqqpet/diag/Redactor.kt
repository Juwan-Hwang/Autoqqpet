package io.github.juwanhwang.autoqqpet.diag

import java.security.MessageDigest

/**
 * 诊断包脱敏：日志要贴到公开的 issue 里，所以**默认全遮**，只留下能判断问题的结构。
 *
 * 遮什么（都是能定位到人的东西）：
 *  - QQ 号 / 好友 uin：8~11 位纯数字，留首尾各 2 位（`12***89`）；
 *  - petId：不透明串，换成一致性哈希（同一只宠物在任何报告里都是同一个 `#a1b2c3d4`，方便串案例，但推不出是谁）；
 *  - storyId 里的 UUID：只留场景前缀（6700_）+ 短哈希，UUID 本身没用又唯一；
 *  - 昵称 / token / cookie / 密码类键值：整值替换；
 *  - 疑似会话票据的长 base64/URL 串。
 *
 * 留什么（诊断必需且不指向身份）：金币、体力/清洁/心情读数、各档收益、rate、候选数、被剔除原因、
 * 时间戳、机型、QQ 与模块版本、事件录文本。金币位数一般 ≤7，不会被 uin 规则误伤（阈值取 8 位起）。
 */
object Redactor {

    /** 一致性短哈希：同一输入在任何报告里得到同一个标记。 */
    fun tag(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("00000000").take(8)

    /** QQ 号这类纯数字：留首尾各 2 位，中间打星。 */
    fun maskDigits(s: String): String = when {
        s.length <= 4 -> "****"
        else -> s.take(2) + "*".repeat(s.length - 4) + s.takeLast(2)
    }

    /** uin：8~11 位，前后不接着数字（避免吃掉长数字串的一段和 13 位毫秒时间戳）。 */
    private val UIN = Regex("(?<!\\d)\\d{8,11}(?!\\d)")

    /** petId：显式键值写法，或是不透明串字段值。 */
    private val PET_ID = Regex("(?i)(petId|pet_id|宠物ID|pid)\\s*[=:]\\s*([A-Za-z0-9%_+/=\\-]{6,})")

    /** storyId：6100_/6400_/6700_ + UUID。 */
    private val STORY = Regex("\\b(\\d{4})_[0-9a-fA-F]{8}-[0-9a-fA-F\\-]{8,}\\b")

    /** 昵称：出现在 nick= / 昵名: 后面，到分隔符为止。 */
    private val NICK = Regex("(?i)(nick|昵称|备注)\\s*[=:]\\s*([^,;\\s⋄|\\n]{1,32})")

    /** 任何看起来像凭证的键值。 */
    private val SECRET = Regex(
        "(?i)\\b(token|skey|a2|daccid|pskey|sid|sess|cookie|password|passwd|pwd|auth|authorization|key)\\b\\s*[=:]\\s*\\S+"
    )

    /** 长 base64/URL 安全串（会话票据、加密体）。 */
    private val BLOB = Regex("(?<![A-Za-z0-9_%\\-+/])[A-Za-z0-9_%\\-+/]{40,}={0,2}")

    fun scrub(text: String): String {
        var t = text
        t = SECRET.replace(t) { "${it.groupValues[1]}=***(已脱敏)" }
        t = PET_ID.replace(t) { "${it.groupValues[1]}=宠物#${tag(it.groupValues[2])}" }
        t = NICK.replace(t) { "${it.groupValues[1]}=#${tag(it.groupValues[2]).take(4)}" }
        t = STORY.replace(t) { "${it.groupValues[1]}_${tag(it.value).take(8)}" }
        t = UIN.replace(t) { maskDigits(it.value) }
        // 最后兜一遍长串：petId 有时不带键名直接出现在文本里
        t = BLOB.replace(t) { m -> if (m.value.any { it.isDigit() } && m.value.any { it.isLetter() }) "«${tag(m.value)}»" else m.value }
        return t
    }
}
