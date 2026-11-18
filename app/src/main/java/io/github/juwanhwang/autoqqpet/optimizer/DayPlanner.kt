package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.AdventureInfo
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import io.github.juwanhwang.autoqqpet.pet.JobInfo

/** 决策动作。 */
enum class Kind { STUDY, WORK, ADVENTURE, PK, FEED, BATH, IDLE }

/**
 * 只冒险模式“固定刷哪个”的过滤（用户 2026-10-16 要求，与排班里选冒险同一种选法：按名字）：
 * 冒险目录实测就两条——「附近走走」45 秒与「诗和远方」2 小时，不存在中间档，
 * 所以按名字钉死最直白。名字为空 = 自动（不过滤）；所选名字不在可用列表里时回退全部，不停工。
 */
internal fun adventureNameScope(items: List<AdventureInfo>, name: String): List<AdventureInfo> {
    if (name.isBlank()) return items
    return items.filter { it.name == name }.ifEmpty { items }
}

data class Decision(
    val kind: Kind, val detail: String, val reason: String, val sub: Long = 0L,
    /**
     * 日志专用：这一轮“为什么是这个、另外三个为什么不是”。
     * 内容全部由 [DayPlanner.decide] 自己已经算出的中间量拼出来（不是另算一套），
     * 所以不会出现“实际做的与解释的不一样”。不参与任何决策。
     */
    val trace: String = "",
    /** 短码（进“当日决策统计”）：量级小、只按计数增长，用来回答“为什么一整天都在冒险”。 */
    val cause: String = "",
)

/** 快照：喂给规划器的实时状态。 */
data class Snapshot(
    val gold: Int,
    val stamina: Int,
    val clean: Int,
    /** 今日学工累计分钟（只用于展示与诊断，不再参与收益折算）。 */
    val fatigueMin: Int,
    val courses: List<CourseInfo>,
    val jobs: List<JobInfo>,
    val biscuits: Int,
    val soap: Int,
    val rawCourses: List<io.github.juwanhwang.autoqqpet.pet.SceneOption> = emptyList(),
    val rawJobs: List<io.github.juwanhwang.autoqqpet.pet.SceneOption> = emptyList(),
    val adventures: List<AdventureInfo> = emptyList(),
    val rawAdventures: List<io.github.juwanhwang.autoqqpet.pet.SceneOption> = emptyList(),
    /** 今日还能冒险几次；>0 参与择优，Int.MAX_VALUE 表示不限。 */
    val adventureLeft: Int = Int.MAX_VALUE,
    /** 今日冒险金币已达上限（结算判定）：冒险不再进候选池（与次数用完分开记，日志要分清是哪种）。 */
    val advGoldCapped: Boolean = false,
    /**
     * 今日学工已整体疲惫（所有可做档位都被降效剔除，即 `penalized` 覆盖完了 `courses + jobs`）。
     *
     * ⚠ 本文件里的 [decide] **不读这个字段**：学工退出候选是靠 `penalizedSubs` 逐档剔除实现的（效果等价）。
     * 它真正的使用方是排班的 `PAY_TIRED` 规则与诊断报告。别误以为这里有一个“疲惫就关掉整天学工”的开关。
     * 以前确实是用“学工累计满 N 小时”当门槛，已废弃（那与游戏真实降效时点不对齐）。
     */
    val studyWorkTired: Boolean = false,
    /**
     * 今天已被削的目录项（按 subEventType，同一个职业的不同时长档是不同值）。
     * 降效往往是【按档位】发生的：30 分钟课被削而 10 分钟课没削，此时应该先把没削的短档做完，
     * 没得做了才去冒险 —— 而不是把整类学工全关掉（真机 2026-10-12 用户指出）。
     */
    val penalizedSubs: Set<Long> = emptySet(),
    /**
     * 照顾已被服务端拒到位（真机 2026-10-10：喂食/洗澡一直回 `Ban state forbit write req`）。
     * true 时不再拿 FEED/BATH 占住决策，低体力/低清洁只影响收益 —— 不然照顾 gate 会把整个循环饿死。
     */
    val feedBlocked: Boolean = false,
    val bathBlocked: Boolean = false,
    /** 带服务器疲劳文案的档位（这一档做完会跨过 8 小时疲劳线）：只降权该档，不剔整类。 */
    val fatigueTextSubs: Set<Long> = emptySet(),
)

/**
 * 数学最优调度器：在"金币自给 + 疲劳降效"约束下，最大化目标属性的每分钟成长。
 * 学习/打工折算到同一量纲“目标属性等效/小时”后取 rate 最高者：
 *  - 学习：属性直接产出；
 *  - 打工：金币与工分按 [goldPerAttr] / [pointsValue] 折算；
 *  - rate 相差在 [studyTieBreak] 以内时优先学习（属性才是最终目的）。
 *
 * 这套旧逻辑里**冒险不参与比价**：学习优先/工作优先两挡下，学工被降效剔空了才去冒险
 * （2026-10-13 的原始设计，本类的历史行为）。注意用户 2026-10-17 已明确纠正过口径：
 * 冒险有真实收益、不许说成“兜底”；最优调度（auto）里冒险按金币期望正常参与比较——别拿本注释当现行原则。
 * 这个游戏里学习打工不存在“做不了”：当日次数用不完、任务随时可开，
 * 体力/清洁不够是照顾问题（该喂喂、该洗洗）而不是拦路条件。
 * （新号会遇到的只是两种暂时状态：职业未解锁、金币不够交学费。）
 * 为什么不给冒险参比（issue #4 真机证据）：冒险目录不回任何收益，拿“基线/次 × 60/分钟”折算会把
 * 45 秒短冒险放大成假高分（29.3/h vs 真实课程 11.4/h），一天跑 463 次、只学 2 次。
 * “只重复冒险”与“自定义排班”两挡由 AutoRunner 自己走另外的路径，不受这里影响。
 */
class DayPlanner(
    val targetAttr: Attr,
    /** 均衡学习时本轮的属性优先级（从轮到的属性起轮转，见 [StudyRotation]）；空 = 只按 [targetAttr]。 */
    val studyAttrOrder: List<Attr> = emptyList(),
    val goldReserve: Int = 300,       // 预留金币(职业升级等)
    val staminaFloor: Int = 20,       // 体力低于此先照顾
    val cleanFloor: Int = 20,
    val pkEnabled: Boolean = true,
    val adventureEnabled: Boolean = true,
    /**
     * 工作优先：只要打工还有 rate>0 的档位就直接去打工，学习让位。
     * 与“学习优先”的区别只在第 5 步的取舍顺序，其余（照顾地板、预算、疲劳降效）完全一致。
     */
    val workFirst: Boolean = false,
    val goldPerAttr: Double = 40.0,
    val studyTieBreak: Double = 1.05, // 学习 rate ≥ 其他最优×此系数时就选学习
    /** 岗位目录 f8 回本金（2026-10-31 抓包证实）；冒险目录不回金币数值。
     * 1 工分折算成目标属性等效值的系数——**经验估值，无协议依据**（工分只用于职业升星，游戏里没有兑换比）。 */
    val pointsValue: Double = 0.2,
    /** 冒险无任何可见收益时的基线价值（等效属性/次）——**经验估值**；冒险在学习/工作优先两挡不参比，只影响只冒险/排班挡里冒险之间的取舍。 */
    val adventureBaseline: Double = 0.5,   // 按“每次”计，短冒险每小时 rate 自然更高
    val staminaWeight: Double = 0.1,  // 1 点体力消耗折算的属性等效
    val cleanWeight: Double = 0.04,   // 1 点清洁消耗同上
    /**
     * 冒险实测耗时（分钟 / 每完成一趟，按冒险名字）：由 runner 按最近几天「派发→结案」的
     * 墙钟学出来喂入（见 AdvPace）。某名字不在表里 = 样本不足，[adventureRate] 回退名义时长
     * （冷启动行为与没有这份数据时完全一致）。
     */
    val learnedTripMinutes: Map<String, Double> = emptyMap(),
) {

    private fun costOf(stamina: Int, clean: Int): Double = stamina * staminaWeight + clean * cleanWeight

    /**
     * v1 的“学工满 8h 降到 0.25、满 12h 降到 0.1”系数已删除：那是拍定的时长门槛，而目录里的数字
     * **本来就是服务端按当日疲劳降效后的读数**（DEV-NOTES 2026-10-10 joblist 实测），再乘一次等于双重惩罚。
     * 疲劳现在只由读数本身 + [Snapshot.penalizedSubs]（同档位比今日峰值 / 结算实收比预期）体现。
     */

    // ---- rate 公式集中在这里（真实决策与 dry-run 打印共用，避免两处算法漂移）----

    /** 单课：目标属性直接产出（等效属性/小时），再扣体力/清洁。 */
    fun courseRate(c: CourseInfo, factor: Double = 1.0): Double =
        if (c.durationMin <= 0) 0.0 else c.attrGain * factor * 60.0 / c.durationMin - costOf(c.staminaCost, c.cleanCost)

    /** 打工的已知价值：金币（仅当目录真回了“金币N”）+ 工分折算。 */
    fun jobValue(j: JobInfo): Double =
        (if (j.incomeKnown) j.income / goldPerAttr else 0.0) + j.points * pointsValue

    fun jobRate(j: JobInfo, factor: Double = 1.0): Double =
        if (j.durationMin <= 0) 0.0 else jobValue(j) * factor * 60.0 / j.durationMin - costOf(j.staminaCost, j.cleanCost)

    /**
     * 冒险价值：冒险奖励不固定（目录常写“属性+0/点0”，实际结算仍会给东西），所以**永远不按 0 价值处理**：
     * 至少按 [adventureBaseline] 当作“每次价值”，目录报的数更高时用报数。
     * 固定/次 → 时长越短每小时 rate 越高（与真机玩家刷短冒险的经验一致，也满足“可重复冒险”）。
     */
    fun adventureValue(a: AdventureInfo): Double {
        val known = (if (a.incomeKnown) a.income / goldPerAttr else 0.0) + a.attrGain + a.points * pointsValue
        return maxOf(known, adventureBaseline)
    }

    /**
     * 按每小时算（“附近走走”45 秒，按向上取整的 1 分钟算会把它的时速低估 25%，B6）。
     * 分母优先用实测的「每完成一趟真实耗时」：名义时长只算趟本身，真实一趟还要摊上
     * 结算等待、任务间隔、决策读包和被召回短趟的墙钟，只按名义算会把时速系统性高估
     * （2026-10-19 真机：实测约 1.7 分钟才完成一趟，名义只有 45 秒）。没样本才回退名义。
     */
    fun adventureRate(a: AdventureInfo): Double {
        val nominal = if (a.durationSec > 0) a.durationSec / 60.0 else a.durationMin.toDouble()
        val minutes = learnedTripMinutes[a.name]?.takeIf { it > 0 } ?: nominal
        return if (minutes <= 0.0) 0.0 else adventureValue(a) * 60.0 / minutes - costOf(a.staminaCost, a.cleanCost)
    }

    /**
     * 疲劳规则（用户确认 2026-10-15）：**当天学习+打工累计满 8 小时**才疲劳；冒险与 PK 不计入。
     * 剩余窗口 = 480 − 今日学工已累计分钟。时长 ≤ 剩余的档照常；会跨线的档（本地算出会跨，或服务器给了疲劳文案）
     * 按“跨线部分降效”折算：收益 × (线内分钟 + 跨线分钟 × [CROSS_PENALTY]) / 时长 —— 降权而不是剔除。
     * 只有满 8 小时、或**所有**可做学工档都带疲劳文案时，才整体疲劳（AutoRunner 把它们放进 penalizedSubs）。
     */
    fun crossFactor(durationMin: Int, fatigueMin: Int, textHit: Boolean): Double {
        if (durationMin <= 0) return 1.0
        val remaining = (FATIGUE_CAP_MIN - fatigueMin).coerceAtLeast(0)
        var within = remaining.coerceAtMost(durationMin)
        // 服务器说这一档会疲惫，而本地算出不会跨线 → 本地累计可能偏少，信服务器：按一半在线内估
        if (textHit && within >= durationMin) within = durationMin / 2
        if (within >= durationMin) return 1.0
        return (within + (durationMin - within) * CROSS_PENALTY) / durationMin
    }

    fun decide(s: Snapshot): Decision {
        // 降效是“按档位”剔除的：只跳过今天被削的那几条，没被削的（往往是短档）照做。
        val freshCourses = s.courses.filterNot { it.subEventType in s.penalizedSubs }
        val freshJobs = s.jobs.filterNot { it.subEventType in s.penalizedSubs }
    
        // 1) 照顾优先：体力/清洁过低无法高效学习/打工；但服务端已拒收这类写请求时不拿它卡死全天
        if (!s.feedBlocked && s.stamina < staminaFloor) return Decision(Kind.FEED, "biscuit", "体力${s.stamina}<地板，先喂", trace = "照顾优先：体力${s.stamina}<地板${staminaFloor}（未参比学工）", cause = "照顾·体力")
        if (!s.bathBlocked && s.clean < cleanFloor) return Decision(Kind.BATH, "soap", "清洁${s.clean}<地板，先洗", trace = "照顾优先：清洁${s.clean}<地板${cleanFloor}（未参比学工）", cause = "照顾·清洁")
    
        // 2) 目标属性最优课（可负担：学费后金币仍 >= 预留）
        val affordableCourses = freshCourses.filter {
            it.canDo && it.durationMin > 0 && (s.gold - it.fee) >= goldReserve
        }
        val attrOrder = studyAttrOrder.ifEmpty { listOf(targetAttr) }
        val targetCourses = attrOrder.firstNotNullOfOrNull { a -> affordableCourses.filter { it.attr == a }.ifEmpty { null } } ?: emptyList()
        val studyPool = targetCourses.ifEmpty { affordableCourses.filter { it.attr == Attr.RANDOM } }
        val bestStudy = studyPool.maxByOrNull { courseRate(it, crossFactor(it.durationMin, s.fatigueMin, it.subEventType in s.fatigueTextSubs)) }

        // 3) 候选池：学习/冒险/打工折算成同一量纲（目标属性等效/小时）
        val cands = ArrayList<Cand>()
        bestStudy?.let {
            val r = courseRate(it, crossFactor(it.durationMin, s.fatigueMin, it.subEventType in s.fatigueTextSubs))
            if (r > 0) cands += Cand(
                Kind.STUDY, it.name, r,
                "+${it.attrGain}${attrLabel(it.attr)} ${it.durationMin}min 学费${it.fee}", 0, it.subEventType,
            )
        }
        if (adventureEnabled && s.adventureLeft > 0 && !s.advGoldCapped) {
            for (a in s.adventures.filter { it.canDo }) {
                val r = adventureRate(a)
                if (r > 0) cands += Cand(
                    Kind.ADVENTURE, a.name, r,
                    "金币${if (a.incomeKnown) a.income else "?"} 属性+${a.attrGain} 点${a.points} ${if (a.durationSec in 1..299) "${a.durationSec}s" else "${a.durationMin}min"} 今日剩余=${if (s.adventureLeft == Int.MAX_VALUE) "不限" else s.adventureLeft}",
                    1, a.subEventType,
                )
            }
        }
        for (j in freshJobs.filter { it.canDo }) {
            val r = jobRate(j, crossFactor(j.durationMin, s.fatigueMin, j.subEventType in s.fatigueTextSubs))
            if (r > 0) cands += Cand(Kind.WORK, j.name, r, "金币${if (j.incomeKnown) j.income else "?"} 工分${j.points} ${j.durationMin}min", 2, j.subEventType)
        }

        // ↓↓↓ 以下只用于日志（trace）：全部取上面已经算好的量与纯计数，不参与任何筛选与择优。
        // 有这一行才能从日志直接分辨：“学习 读0门”是读不到课，“学费不够3门”是金币问题，“rate 最高”才是真的划算。
        val poolText = runCatching {
            fun drops(items: List<String?>): String =
                items.filterNotNull().ifEmpty { return "" }.joinToString("，") { "（剔：$it）" }

            "学习 读${s.courses.size}门" +
                drops(listOf(
                    if (s.courses.any { it.subEventType in s.penalizedSubs }) "降效${s.courses.count { it.subEventType in s.penalizedSubs }}" else null,
                    if (s.courses.any { !it.canDo }) "不可做${s.courses.count { !it.canDo }}" else null,
                    if (csPoor(s) > 0) "学费不够${csPoor(s)}" else null,
                )) +
                "→可负担${affordableCourses.size}→进池${studyPool.size}（目标属性${targetCourses.size}门） " +
                "打工 读${s.jobs.size}档" +
                drops(listOf(
                    if (s.jobs.any { it.subEventType in s.penalizedSubs }) "降效${s.jobs.count { it.subEventType in s.penalizedSubs }}" else null,
                    if (s.jobs.any { !it.canDo }) "不可做${s.jobs.count { !it.canDo }}" else null,
                )) +
                "→进池${cands.count { it.kind == Kind.WORK }}" +
                (if (s.jobs.isEmpty() && s.rawJobs.isEmpty()) "（目录没给岗位：职业未解锁或读取失败）" else "") + " " +
                "冒险 读${s.adventures.size}个" +
                drops(listOf(
                    if (!adventureEnabled) "开关关" else null,
                    if (adventureEnabled && s.advGoldCapped) "金币已达上限" else null,
                    if (adventureEnabled && s.adventureLeft <= 0) "今日次数用完" else null,
                    if (s.adventures.any { !it.canDo }) "不可做${s.adventures.count { !it.canDo }}" else null,
                )) +
                "→进池${cands.count { it.kind == Kind.ADVENTURE }} 剩余=" +
                (if (s.adventureLeft == Int.MAX_VALUE) "不限" else s.adventureLeft) +
                " ｜ 金币${s.gold} 体力${s.stamina} 清洁${s.clean}"
        }.getOrDefault("")

        // ↓↓↓ 冒险在“学习优先 / 工作优先”两挡下是**最低优先级**：学工干不动（疲惫）了才去。
        // 为什么不拿它参与 rate 比价（issue #4 真机证据，小米 9.3.70）：冒险目录**不回任何收益**
        // （金币?、属性+0、点0），只能用拍定的基线 0.5/次；而 rate 是按小时折算的，
        // 45 秒短冒险被放大成 29.3/小时，永远压过真实的 11.4/小时课程 → 一天 463 次冒险、只学习 2 次。
        // “只重复冒险”与“自定义排班”两挡不走这里（那里冒险是用户明确要的）。
        val workStudy = cands.filter { it.kind != Kind.ADVENTURE }
        val pool = if (workStudy.isEmpty()) cands else workStudy

        // 4) 无候选 → PK 兼顾金币，否则空闲
        if (pool.isEmpty()) {
            val why = if (s.penalizedSubs.isNotEmpty()) "本日能做的学工档位都被削（已跳过 ${s.penalizedSubs.size} 项），只去冒险" else "学工冒险均不可用/不划算"
            val noCatalog = s.courses.isEmpty() && s.jobs.isEmpty() && s.adventures.isEmpty()
            val note = "$poolText ⇒ 三边都无进池候选（$why）"
            val code = if (noCatalog) "目录全空" else if (s.penalizedSubs.isNotEmpty()) "降效剔尽" else "无划算项"
            if (pkEnabled) return Decision(Kind.PK, "", "$why，走 PK 赚金币", trace = note, cause = code)
            return Decision(Kind.IDLE, "", "$why，无事可做", trace = note, cause = code)
        }

        // 5) 择优（只在上面的池子里选）：rate 最高；工作优先时打工直接上位，否则学习在 tie-break 区间内优先
        val top = pool.maxByOrNull { it.rate }!!
        val study = cands.filter { it.kind == Kind.STUDY }.maxByOrNull { it.rate }
        val work = cands.filter { it.kind == Kind.WORK }.maxByOrNull { it.rate }
        val chosen = when {
            workFirst && work != null -> work
            study != null && study.rate * studyTieBreak >= top.rate -> study
            else -> top
        }
        val ranked = cands.sortedByDescending { it.rate }.take(3).joinToString(" | ") { "${it.kind}:${"%.1f".format(it.rate)}" }
        val candText = " ｜ 候选[$ranked]"
        // 降效时把原因写清楚（否则日志上只看到“一直在冒险”，不知道为什么）
        val tag = when {
            s.penalizedSubs.isNotEmpty() && work == null && study == null -> "[已跳过被削档 ${s.penalizedSubs.size} 项，只剩冒险] "
            s.penalizedSubs.isNotEmpty() -> "[已跳过被削档 ${s.penalizedSubs.size} 项，先做没被削的] "
            workFirst && work != null -> "[工作优先] "
            else -> ""
        }
        // ↓↓↓ 只用于日志：下面的判定全部拿上面已有的对象做【同一引用比较】，不重建一遍择优条件，
        // 所以不可能与 `chosen` 矛盾；万一拼字符串报错也只是 trace 为空，影不了决策。
        val rule = when {
            workStudy.isEmpty() && chosen.kind == Kind.ADVENTURE -> "学工无可用收益，冒险兜底"
            workFirst && chosen === work -> "工作优先"
            chosen === study && study !== top -> "学习靠 tie-break 上位"
            else -> "rate 最高"
        }
        val traceText = runCatching {
            "$poolText ⇒ 选 ${chosen.kind}「${chosen.name}」rate=${"%.1f".format(chosen.rate)}（$rule）" +
                " 学习=" + (study?.let { "%.1f".format(it.rate) } ?: "无") +
                " 打工=" + (work?.let { "%.1f".format(it.rate) } ?: "无") +
                " 冒险=" + (cands.filter { it.kind == Kind.ADVENTURE }.maxOfOrNull { it.rate }?.let { "%.1f".format(it) } ?: "无")
        }.getOrDefault("")
        return Decision(chosen.kind, chosen.name, "$tag rate=${"%.1f".format(chosen.rate)}等效属性/h ｜ ${chosen.note}$candText", chosen.sub, trace = traceText, cause = rule)
    }

    /**
     * 候选项（统一量纲后的一个可执行主任务）。
     * [sub] 带上目录项编号：执行时按它取原始发包参数，比按名字找可靠（同名条目真机见过）。
     */
    private data class Cand(
        val kind: Kind, val name: String, val rate: Double, val note: String, val priority: Int, val sub: Long = 0L,
    )

    private fun attrLabel(a: Attr): String = when (a) {
        Attr.STRENGTH -> "力量"; Attr.INTEL -> "智力"; Attr.CHARM -> "魅力"
        Attr.RANDOM -> "随机"; else -> "属性"
    }

    /** 日志用：因为“学费后余币不够预留”而被拦掉的课数（与 decide 上面的可负担条件同一个式子）。 */
    private fun csPoor(s: Snapshot): Int =
        freshOf(s).count { it.canDo && it.durationMin > 0 && (s.gold - it.fee) < goldReserve }

    /** 日志用：与 decide 里同一个 freshCourses（剔除今天被降效的档）。 */
    private fun freshOf(s: Snapshot): List<CourseInfo> = s.courses.filterNot { it.subEventType in s.penalizedSubs }
}

/** 8 小时疲劳线（分钟）与跨线部分的收益系数（真机 10-12：30min 课 60→16/h ≈ 0.27）。 */
const val FATIGUE_CAP_MIN = 480
const val CROSS_PENALTY = 0.25
