package io.github.juwanhwang.autoqqpet.pet.parse

/**
 * 服务器叙事富文本清洗（真机 2026-10-15：主页“实时叙事”显示成 `"![#20px\n\n#20px] (https://qqpet.gtimg.com/pet-outdoor…`）。
 *
 * 0x975f 进度 / 0x9874 结算的叙事行里混着 markdown 图片 `![..](..)`、尺寸样式标记 `#20px`、图标 URL、残留引号括号。
 * 规则：去掉这些标记 → 压空白 → 只保留**含实质中文**的行；截断按码点（不切半个代理对/表情），
 * 而且是在清洗**之后**截，所以不会截出半个标记。
 */
object RichText {
    private val MD_IMAGE = Regex("""!\[[^\]]*]\s*\([^)]*\)?""", RegexOption.DOT_MATCHES_ALL)
    private val MD_IMAGE_OPEN = Regex("""!\[[^\]]*]?""", RegexOption.DOT_MATCHES_ALL)   // 被截断、没闭合的图片头
    private val MD_LINK = Regex("""\[([^\]]*)]\s*\([^)]*\)""")
    private val URL = Regex("""(?:https?://|www\.)\S*""", RegexOption.IGNORE_CASE)
    private val STYLE = Regex("""#\s*\d+(?:\.\d+)?\s*(?:px|dp|sp|pt|em|rem|%)?\b""", RegexOption.IGNORE_CASE)
    private val COLOR = Regex("""#[0-9a-fA-F]{6,8}\b""")
    private val TAG = Regex("""</?[a-zA-Z][^>]*>""")
    private val JUNK = Regex("""[\\"'“”‘’`*_~<>{}\[\]()（）【】|]+""")
    private val SPACE = Regex("""\s+""")
    private val HAN = Regex("""\p{IsHan}""")
    /** 服务器叙事常是 JSON 片段 `[{"text":"巨星自我修养开始了!"}]`：先把 text 值抠出来。 */
    private val JSON_TEXT = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    /** JSON 被截断/引号被吃掉后残留的 `text:` 前缀（真机 2026-10-16：主页显示“text:巨星自我修养开始了!”）。 */
    private val TEXT_KEY = Regex("""(?i)(?<![A-Za-z])text\s*[:：]\s*""")

    /** 一行富文本 → 干净文本（可能为空串）。 */
    fun clean(raw: String): String {
        var s = raw
        JSON_TEXT.findAll(s).map { it.groupValues[1] }.toList().takeIf { it.isNotEmpty() }?.let { vs ->
            s = vs.joinToString(" ") { it.replace("\\\"", "\"").replace("\\/", "/") }
        }
        s = s.replace("\\n", " ").replace("\\r", " ")
        s = TEXT_KEY.replace(s, " ")
        s = MD_IMAGE.replace(s, " ")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = MD_IMAGE_OPEN.replace(s, " ")
        s = URL.replace(s, " ")
        s = TAG.replace(s, " ")
        s = COLOR.replace(s, " ")
        s = STYLE.replace(s, " ")
        s = JUNK.replace(s, " ")
        s = SPACE.replace(s, " ").trim()
        return s.trim(' ', '/', '·', '、', ',', '，', ':', '：', ';', '；', '-', '!', '#')
    }

    /**
     * 标题/名称类短文本：只抠 JSON 的 text 值、去掉 `text:` 前缀、压空白——**保留全角括号**
     * （“替摊主看摊（被雇佣）”的括号是被雇判据，不能像叙事那样当垃圾符号洗掉）。
     */
    fun title(raw: String): String {
        var s = raw
        JSON_TEXT.findAll(s).map { it.groupValues[1] }.toList().takeIf { it.isNotEmpty() }?.let { vs -> s = vs.joinToString(" ") }
        s = TEXT_KEY.replace(s, " ")
        return SPACE.replace(s, " ").trim().trim('"', '[', ']', '{', '}')
    }

    /** 清洗后还有实质中文内容（至少 2 个汉字）才算一行叙事。 */
    fun isNarrative(cleaned: String): Boolean = HAN.findAll(cleaned).take(2).count() >= 2

    /** 清洗一批行，丢掉没内容的，去重保序。 */
    fun narrativeLines(raw: List<String>): List<String> =
        raw.asSequence().map { clean(it) }.filter { isNarrative(it) }.distinct().toList()

    /** 按码点安全截断（不切半个代理对），超长加省略号。 */
    fun truncate(s: String, maxCodePoints: Int): String {
        if (s.codePointCount(0, s.length) <= maxCodePoints) return s
        val end = s.offsetByCodePoints(0, (maxCodePoints - 1).coerceAtLeast(1))
        return s.substring(0, end).trimEnd() + "…"
    }
}
