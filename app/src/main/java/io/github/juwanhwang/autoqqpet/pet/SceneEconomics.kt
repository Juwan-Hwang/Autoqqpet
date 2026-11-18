package io.github.juwanhwang.autoqqpet.pet

/** 属性类型。 */
enum class Attr { STRENGTH, INTEL, CHARM, RANDOM, NONE }

/** 一门课的结构化经济数据（从 cost/duration/reward markdown 解析）。 */
data class CourseInfo(
    val name: String,
    val subEventType: Long,
    val attr: Attr,
    val attrGain: Int,       // 单课属性加成(不含 A+ 奖励)
    val fee: Int,            // 学费金币
    val staminaCost: Int,    // 体力消耗
    val cleanCost: Int,      // 清洁消耗
    val durationMin: Int,    // 时长(分钟，向上取整，用于疲劳/预算统计)
    val durationSec: Long = 0,   // 精确秒数，用于排到期结算
    val canDo: Boolean,
)

/** 一个岗位的结构化经济数据。 */
data class JobInfo(
    val name: String,
    val careerType: Long,
    val subEventType: Long,
    val income: Int,         // 金币毛收入（0 且 incomeKnown=false 表示真机目录未回）
    val incomeKnown: Boolean = false,
    val points: Int = 0,     // f15 "+N点"（工分/经验，用于职业升星）
    val staminaCost: Int,
    val cleanCost: Int,
    val durationMin: Int,
    val durationSec: Long = 0,
    val credit: Int,         // 工分
    val canDo: Boolean,
    /** income 来自结算明细的历史实收均值（不是目录原文）。 */
    val incomeLearned: Boolean = false,
    /** 本次目录读数语境下的雇佣加成上限（f12 括号末尾；带好友读目录时就是那位好友的上限，自己读是服务器展示上限）。-1 = 目录没给。 */
    val hireBonusCap: Int = -1,
) {
    /** 本次读数下的“工分/小时”（横比档位的唯一可比量）。 */
    val pointsPerHour: Double get() = if (durationMin <= 0) 0.0 else points * 60.0 / durationMin
}

/** 一次冒险的结构化经济数据。 */
data class AdventureInfo(
    val name: String,
    val subEventType: Long,
    val income: Int,         // 金币收益（真机目录通常不回，靠描述文本）
    val incomeKnown: Boolean = false,
    val attr: Attr,
    val attrGain: Int,
    val points: Int = 0,
    val staminaCost: Int,
    val cleanCost: Int,
    val durationMin: Int,
    val durationSec: Long = 0,
    val canDo: Boolean,
    val reason: String = "",  // 不可用原因（f51，can=true 时实为按钮文案）
)

object SceneEconomics {

    private val ATTR_RE = Regex("(力量|智力|魅力|随机属性)\\+\\s*(\\d+)")
    private val STAMINA_RE = Regex("体力\\s*(\\d+)")
    private val CLEAN_RE = Regex("清洁\\s*(\\d+)")
    private val FEE_RE = Regex("([\\d,]+)\\s*\\(当前")   // 学费: 首个 "N(当前"
    private val NUM_RE = Regex("(\\d+)")
    /** 显式金币收益：只认“金币N”，不再从任意数字里猜（真机 reward 常是图标 markdown/描述）。 */
    private val GOLD_RE = Regex("金币\\s*[+＋]?\\s*(\\d+)")
    /** f15 的“+10点”（工分/经验）。 */
    private val POINTS_RE = Regex("(\\d+)\\s*点")

    /** 从任意文本里提工分/经验点数。 */
    fun pointsOf(vararg texts: String): Int =
        texts.mapNotNull { POINTS_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0

    /** 从任意文本里提金币收益；返回 null 表示“未告知”，不能当 0 参与比价。 */
    fun goldOf(vararg texts: String): Int? =
        texts.mapNotNull { GOLD_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()

    /**
     * 岗位本金：f8 是图标 markdown + 纯数字（如 "![…](…) 302"），整串里唯一的数字就是本金。
     * 2026-10-31 抓包证实与结算明细里的「本金」分毫不差。串里没有数字、或混着别的文字数字
     * （不是纯图标+数字形态）时返回 null，宁可未知也不猜——调用方回退旧口径。
     */
    fun baseGoldOf(reward: String): Int? {
        val tail = reward.substringAfterLast(')').trim()
        return tail.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * 雇佣加成上限：f12 形如 "302～453（雇佣金币加成：0～151）"，括号区间末尾的数就是上限。
     * 没有括号区间返回 null（目录没给雇佣预告）。
     */
    fun wageCapOf(wageRange: String): Int? {
        val inner = wageRange.substringAfterLast('：', "").substringBefore('）')
        return inner.substringAfterLast('～').trim().toIntOrNull()
    }

    fun parseCourse(c: SceneOption): CourseInfo {
        val rewardHead = c.reward.substringBefore("[")   // 去掉 mqqapi 提示链接
        val m = ATTR_RE.find(rewardHead)
        val attr = when (m?.groupValues?.get(1)) {
            "力量" -> Attr.STRENGTH; "智力" -> Attr.INTEL; "魅力" -> Attr.CHARM
            "随机属性" -> Attr.RANDOM; else -> Attr.NONE
        }
        val gain = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return CourseInfo(
            name = c.name, subEventType = c.subEventType, attr = attr, attrGain = gain,
            fee = firstInt(FEE_RE, c.cost) ?: firstInt(NUM_RE, c.cost) ?: 0,
            staminaCost = STAMINA_RE.find(c.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            cleanCost = CLEAN_RE.find(c.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            durationMin = durationMinutes(c.duration), durationSec = durationSecondsOf(c.duration),
            canDo = c.canDo,
        )
    }

    /**
     * 岗位：f6=体力/清洁消耗, f7=时长, f15="+N点"(工分)；金币在本金字段 f8（图标 markdown + 数字，
     * 2026-10-31 抓包证实与结算本金一致）与工资区间 f12（雇佣加成上限）。income 优先取 f8 本金，
     * 抠不到才认文本里的“金币N”，再没有就 incomeKnown=false 交由学习账/点数定价兜底。
     */
    fun parseJob(c: SceneOption, careerType: Long): JobInfo {
        // 本金优先认 f8 的目录原文（服务器现读、与结算本金分毫不差）；抠不到才回退旧口径的文本线索
        val gold = baseGoldOf(c.reward) ?: goldOf(c.reward, c.cost, c.extra, c.description)
        return JobInfo(
            name = c.name, careerType = careerType, subEventType = c.subEventType,
            income = gold ?: 0, incomeKnown = gold != null, points = pointsOf(c.extra, c.reward),
            staminaCost = STAMINA_RE.find(c.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            cleanCost = CLEAN_RE.find(c.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            durationMin = durationMinutes(c.duration), durationSec = durationSecondsOf(c.duration),
            credit = pointsOf(c.extra), canDo = c.canDo,
            hireBonusCap = wageCapOf(c.wageRange) ?: -1,
        )
    }

    /** 冒险：同样只认显式“金币N”；f8 常是描述文本，不能当收益。 */
    fun parseAdventure(c: SceneOption): AdventureInfo {
        val head = c.reward.substringBefore("[")
        val m = ATTR_RE.find(head) ?: ATTR_RE.find(c.description)
        val attr = when (m?.groupValues?.get(1)) {
            "力量" -> Attr.STRENGTH; "智力" -> Attr.INTEL; "魅力" -> Attr.CHARM
            "随机属性" -> Attr.RANDOM; else -> Attr.NONE
        }
        val gain = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val gold = goldOf(c.reward, c.cost, c.extra, c.description)
        val durSec = durationSecondsOf(c.duration).let { if (it > 0) it else 1800L }
        val dur = ((durSec + 59) / 60).toInt()
        return AdventureInfo(
            name = c.name, subEventType = c.subEventType, income = gold ?: 0, incomeKnown = gold != null,
            attr = attr, attrGain = gain, points = pointsOf(c.extra, c.reward),
            staminaCost = STAMINA_RE.find(c.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            cleanCost = CLEAN_RE.find(c.cost)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            durationMin = dur, durationSec = durSec, canDo = c.canDo, reason = if (c.canDo) "" else c.unavailableReason,
        )
    }

    private fun firstInt(re: Regex, s: String): Int? =
        re.find(s)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

    /** 精确秒数：真机存在“45秒”这种短任务，按分钟排期会把重复循环拖慢 60 倍。 */
    fun durationSecondsOf(text: String): Long {
        val h = Regex("(\\d+)\\s*小时").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val mm = Regex("(\\d+)\\s*分").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val ss = Regex("(\\d+)\\s*秒").find(text)?.groupValues?.get(1)?.toLongOrNull()
        return if (h == 0L && mm == null && ss != null) ss
        else h * 3600 + (mm ?: 0) * 60 + (ss ?: 0)
    }

    /** "45秒"/"10分钟"/"1小时30分" → 分钟（向上取整，用于时长统计）；纯数字按分钟。 */
    fun durationMinutes(text: String): Int {
        val sec = durationSecondsOf(text)
        return if (sec > 0) ((sec + 59) / 60).toInt()
        else NUM_RE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
