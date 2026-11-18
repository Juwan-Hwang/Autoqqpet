package io.github.juwanhwang.autoqqpet.optimizer

import kotlin.math.min
import kotlin.math.roundToLong

/** 自动挡的优化目标（设置 auto_goal）：金币优先 / 工分优先 / 属性优先。 */
enum class AutoGoal { GOLD, POINTS, ATTR }

/**
 * 数学最优调度求解器（纯函数，无 Android 依赖，可单测）。
 *
 * 模型（全部参数由调用方从**实时目录 + 结算学习值**喂入，这里不写死任何单趟产出——
 * 每个人等级/进度不同、升级后目录立刻变，靠每趟结算后重解来跟上）：
 * - 时间只规划到当日 24:00；学习/打工的时长共同累计疲劳，按官方三档（0–480 分钟 100%、
 *   480–720 是 25%、720 后 10%）对跨档任务做分钟级分段折算，作用于课程属性增益与岗位的金币/工分。
 * - 冒险不占疲劳；金币按期望值计，共享一个当日金币池（池深由观测标定），池子见底就不再排。
 * - 补给只有饼干（+10 体力）与香皂片（+10 清洁）参与求解（单价已证最优，价格由设置喂入）；
 *   补给不单独占动作：每个动作执行前按需即时补到够用（封顶 100），花销计入金币——
 *   补早补晚同价、晚补只会更灵活，即时补给不损失最优性，还免得补给分支把状态空间撑爆。
 * - 目标：GOLD=期末金币最大；POINTS/ATTR=对应总量最大，但期末金币不得低于基线
 * - POINTS 另有净亏刹车：某趟工按其所处疲劳档折算后的收入盖不住补给耗费就不排（收入未知的工豁免）
 *   （今日起始金币与预留金取大）；任何时刻金币不得为负。若基线本身已达不到（今天已经花超），
 *   退而求期末金币最大（先回血），不硬凑目标。
 *
 * 算法：按学工累计分钟分层的动态规划，每层只留 Pareto 非支配集（同等或更优的局面只留一个），
 * 带硬上限防爆；触顶时用已找到的最优可行解（滚动时域下每趟重解，近似误差会被下一趟修正）。
 * 冒险在搜索里**按批展开**（一趟转移 = 连刷至多 [ADV_BATCH] 趟）：45 秒一趟的冒险若逐趟展开，
 * 一个疲劳层就能塞下上千个只差一趟冒险的中间局面，预算全耗在冒险链上、学工组合反而算不完
 * （2026-10-17 工分优先实测：240 次求解全部触顶，计划里工永远排在几百趟冒险之后，
 * 执行层只走第一步，于是第一步永远是冒险、整个上午没打过工）。整批的金币期望、补给消耗、
 * 池子扣减都按批算，批大小还会被池子余量与剩余时间自动收小，边界精度不丢。
 * 终局排名在目标值之后先比先手（学/工在前、冒险在后），然后才比金币：冒险不占
 * 疲劳，先冒险后打工的计划工分不减、金币还更多，若先手只在完全同分时才比，
 * 这类计划会一直压过先打工的计划，而执行层只走第一步，正事会被一再顺延。
 *
 * 2026-10-18 两道防线（冒险收益高、池子满时搜索触顶退化，第一步会选错岗位）：
 * 一是目标感知的金币封顶比较——POINTS/ATTR 下金币只需过基线，超出「基线 + 剩余时间
 * 最多还能花掉的学费与补给」的部分在支配与排名里不再产生差异（只钳比较值，真实记账
 * 与基线判定不动），冒险靠多刷金币撑出来的近似局面互相支配、前沿塌缩；
 * 二是贪心保底——求解前先按单位时间收益（POINTS 纯工分/分钟、ATTR 目标属性/分钟、
 * GOLD 金币/分钟）滚动一条完整可行计划垫底，DP 再触顶也不会返回比它差的计划。
 */
object OptimalPlanner {

    data class CourseIn(
        val name: String, val sub: Long, val targetGain: Double,
        val fee: Int, val staminaCost: Int, val cleanCost: Int, val durationMin: Int,
    )

    data class JobIn(
        val name: String, val sub: Long, val income: Double, val points: Double,
        val staminaCost: Int, val cleanCost: Int, val durationMin: Int,
        /** 收入有没有实数（目录已知或结算学到）。未知时求解器按 0 估，净亏刹车不拦——先跑出数据再说。 */
        val incomeKnown: Boolean = true,
    )

    data class AdventureIn(
        val name: String, val sub: Long, val evGold: Double, val cycleMin: Double,
        val staminaCost: Int = 0, val cleanCost: Int = 0,
    )

    data class Input(
        val goal: AutoGoal,
        val nowMin: Int,
        val fatigueMin: Int,
        val gold: Int,
        val stamina: Int,
        val clean: Int,
        val dayStartGold: Int,
        val reserveGold: Int,
        /** 属性优先今日可亏金币（用户设置 attr_loss_budget）：期末金币底线从 dayStartGold 放宽这个数，
         *  但不穿 reserveGold。其他目标不用。 */
        val lossBudget: Int = 0,
        val courses: List<CourseIn>,
        val jobs: List<JobIn>,
        val adventures: List<AdventureIn>,
        val advPoolLeft: Double,
        val biscuitPrice: Int,
        val soapPrice: Int,
        val endMin: Int = 1440,
        /** 属性优先的停课线：一节课按所处时段折算的平均收益系数低于它就不排（0=不设线）。用户设置 study_brake 映射而来。 */
        val studyMinFactor: Double = 0.0,
    )

    enum class ActKind { STUDY, WORK, ADVENTURE, FEED, BATH }
    data class Act(val kind: ActKind, val sub: Long, val name: String)

    data class Plan(
        val first: Act?,
        val studies: Int, val works: Int, val adventures: Int, val feeds: Int, val baths: Int,
        val endGold: Double, val totalPoints: Double, val totalAttr: Double,
        val summary: String,
    )

    /** 冒险按批展开时一趟转移最多连刷的趟数（45 秒一趟时约 15 分钟一格）。 */
    const val ADV_BATCH = 20

    /** 总展开预算默认值：触顶就用已找到的最优（滚动重解会修正）。 */
    const val MAX_OFFERS = 2_000_000

    /** 单个疲劳层的 Pareto 集上限默认值：超出按终局排名截断。 */
    const val MAX_LAYER = 3_000

    /** [fromFatigue, fromFatigue+durMin) 落在三档里的加权平均倍率。 */
    fun tierFactor(fromFatigue: Int, durMin: Int): Double {
        if (durMin <= 0) return 1.0
        fun overlap(a0: Int, a1: Int, b0: Int, b1: Int): Int =
            maxOf(0, minOf(a1, b1) - maxOf(a0, b0))
        val end = fromFatigue + durMin
        val weighted = overlap(fromFatigue, end, 0, 480) * 1.0 +
            overlap(fromFatigue, end, 480, 720) * 0.25 +
            overlap(fromFatigue, end, 720, Int.MAX_VALUE) * 0.10
        return weighted / durMin
    }

    /**
     * 结算金币按这趟占用的疲劳段还原为满档值（岗位收入学习的入账口径）：实收是被疲劳档
     * 折过的（低至一折），不还原就入账会把满档收益系统性低估，求解时还要再乘一次
     * [tierFactor]，等于折两道。段信息不全（时长未知/起点为负）或倍率过低（噪声放大）时不还原。
     */
    fun fullTierEquivalent(gold: Long, fatigueStart: Int, durMin: Int): Long {
        if (durMin <= 0 || fatigueStart < 0) return gold
        val f = tierFactor(fatigueStart, durMin)
        return if (f >= 0.05) (gold / f).roundToLong() else gold
    }

    /**
     * 求解器给一个岗位定的工分：目录标称（服务器现读的满档值）优先。
     * 实收学习值是被疲劳档折过的原始均值——拿它当满档值喂进来，求解时还要再乘一次
     * [tierFactor]，等于折两道，长档被压得最狠（2026-11-06 群友实报：工分优先全天跑
     * 10 分钟短工，目录里 45 分档明明 72 点/小时却排不上；且不跑就没新样本，永远纠不回来）。
     * 目录没给工分（0）时才回退学习值，保住「先跑出数据再说」的老口径。
     */
    fun jobPoints(catalogPoints: Int, learned: Double?): Double =
        if (catalogPoints > 0) catalogPoints.toDouble() else learned ?: 0.0

    private data class State(
        val t: Double, val fatigue: Int, val gold: Double, val stamina: Int, val clean: Int,
        val pool: Double, val points: Double, val attr: Double,
        val first: Act?, val studies: Int, val works: Int, val advs: Int, val feeds: Int, val baths: Int,
    )

    fun solve(inp: Input, maxOffers: Int = MAX_OFFERS, maxLayer: Int = MAX_LAYER, timeBudgetMs: Long = 3000): Plan? {
        if (inp.courses.isEmpty() && inp.jobs.isEmpty() && inp.adventures.isEmpty()) return null
        val horizon = (inp.endMin - inp.nowMin).toDouble()
        if (horizon <= 0) return null
        // 期末金币底线：默认不低于 max(早上金币, 预留金)；属性优先填了可亏额度就放宽到
        // 早上金币减去额度——拿金币换属性，不再为了守底线先去赚钱；预留金是绝对底线，放宽不穿它。
        val floorGold = if (inp.goal == AutoGoal.ATTR)
            maxOf(inp.reserveGold, inp.dayStartGold - inp.lossBudget).toDouble()
        else maxOf(inp.dayStartGold, inp.reserveGold).toDouble()

        val start = State(0.0, inp.fatigueMin, inp.gold.toDouble(), inp.stamina, inp.clean,
            inp.advPoolLeft, 0.0, 0.0, null, 0, 0, 0, 0, 0)

        // —— 目标感知的金币封顶（只钳比较用的值，State 里的真实记账、基线可行性判定一律不动）——
        // POINTS/ATTR 下期末金币只需过基线，多出的金币只能花在后续学费与补给上；超出
        // 「基线 + 当天最多还能花掉的钱」的部分，在支配比较与终局排名里不再产生差异，
        // 只差在多余金币上的局面视为等值——冒险靠多刷金币撑出来的大批近似局面会因此互相支配、
        // 前沿塌缩，预算留给学工组合。上界全部从输入现推：学费按单位时间学费率最高的课铺满
        // 全天，补给按单位时间消耗率最高的学工动作铺满、冒险补给再受池深约束（刷干池子
        // 所需趟数封顶），不写死数字。GOLD 不封顶：金币就是目标本身。
        val feeRate = inp.courses.filter { it.durationMin > 0 }
            .maxOfOrNull { it.fee.toDouble() / it.durationMin } ?: 0.0
        val burnRateSW = maxOf(
            inp.courses.filter { it.durationMin > 0 }
                .maxOfOrNull { (it.staminaCost + it.cleanCost).toDouble() / it.durationMin } ?: 0.0,
            inp.jobs.filter { it.durationMin > 0 }
                .maxOfOrNull { (it.staminaCost + it.cleanCost).toDouble() / it.durationMin } ?: 0.0,
        )
        val pricedAdventures = inp.adventures.filter { it.evGold > 0 && it.cycleMin > 0 }
        val advMinEv = pricedAdventures.minOfOrNull { it.evGold }
        val advMinCycle = pricedAdventures.minOfOrNull { it.cycleMin }
        val advMaxBurn = inp.adventures.maxOfOrNull { it.staminaCost + it.cleanCost } ?: 0
        val priceMax = maxOf(inp.biscuitPrice, inp.soapPrice)
        // 封顶值在整场求解里取同一个数（基线 + 从求解起点算全天最多还能花掉的钱）：
        // 若按每个局面各自的剩余时间算，剩时多的局面封顶更高、比较起来反而占便宜，会系统性
        // 压过「已经把时间花在刷金币上」的局面——封顶必须只是金币本身的函数，处处一致。
        val goldCapGlobal: Double = run {
            var spend = horizon * feeRate + (horizon * burnRateSW / 10.0 + 2.0) * priceMax
            if (advMinEv != null && advMinCycle != null && advMaxBurn > 0) {
                val trips = min(horizon / advMinCycle, inp.advPoolLeft / advMinEv)
                spend += (trips * advMaxBurn / 10.0 + 2.0) * priceMax
            }
            floorGold + spend
        }
        fun cappedGold(s: State): Double =
            if (inp.goal == AutoGoal.GOLD) s.gold else min(s.gold, goldCapGlobal)

        /** A 在每个维度都不差于 B 且时间/疲劳不更多 → B 没必要再展开。金币按封顶口径比（见上）。 */
        fun dominates(a: State, b: State): Boolean =
            a.t <= b.t && a.fatigue <= b.fatigue && cappedGold(a) >= cappedGold(b) && a.stamina >= b.stamina &&
                a.clean >= b.clean && a.pool >= b.pool && a.points >= b.points && a.attr >= b.attr

        // 先手偏好：学/工是正事，冒险是填充。排名里排在金币之前（见 rank）——
        // 只在终局完全同分时才比先手的话，「先冒险后打工」的计划工分不减、
        // 金币更多，会一直压过先打工的计划，而执行层只走第一步，正事被无限期
        // 顺延（靠「最后总能塞下」兜底太脆，不算修好）。
        // 属性优先例外：打工在这一挡只是凑学费的手段，和冒险同类，而冒险凑钱更快、
        // 又不占疲劳——先手只认学习，其次冒险，打工排最后。否则属性涨幅清零时
        // （比如课程被停课线剔光），同分计划永远打工开局，执行层只走第一步、
        // 重解又得同一计划，一整天卡在打工上（2026-10-29 真机实测：疲劳 755 分后
        // 连续三轮决策都是同一趟 10 分钟工、计划里 605 趟冒险一趟没跑）。
        fun firstPref(s: State): Int =
            if (inp.goal == AutoGoal.ATTR) when (s.first?.kind) {
                ActKind.STUDY -> 2
                ActKind.ADVENTURE -> 1
                else -> 0
            } else when (s.first?.kind) {
                ActKind.STUDY, ActKind.WORK -> 2
                ActKind.ADVENTURE -> 1
                else -> 0
            }
        // 终局排名：先比可行性（POINTS/ATTR 的期末基线，用真实金币判），再比目标，
        // 然后比先手（正事先行），最后才比金币（先封顶、后真实）；不可行时仍按
        // 真实金币比回血进度，次序不变。
        fun rank(s: State): DoubleArray {
            val feasible = if (inp.goal == AutoGoal.GOLD) 1.0 else if (s.gold >= floorGold - 1e-9) 1.0 else 0.0
            return when (inp.goal) {
                AutoGoal.GOLD -> doubleArrayOf(1.0, s.gold, s.attr, s.points)
                AutoGoal.POINTS -> if (feasible > 0) doubleArrayOf(feasible, s.points, firstPref(s).toDouble(), cappedGold(s), s.gold)
                    else doubleArrayOf(feasible, s.gold, s.points, s.attr, s.attr)
                AutoGoal.ATTR -> if (feasible > 0) doubleArrayOf(feasible, s.attr, firstPref(s).toDouble(), cappedGold(s), s.gold)
                    else doubleArrayOf(feasible, s.gold, s.attr, s.points, s.points)
            }
        }
        fun better(a: State, b: State): Boolean {
            val ra = rank(a); val rb = rank(b)
            for (i in ra.indices) {
                if (ra[i] > rb[i] + 1e-9) return true
                if (rb[i] > ra[i] + 1e-9) return false
            }
            return firstPref(a) > firstPref(b)
        }

        var best: State = start
        var truncated = false
        var offers = 0

        // 按“学工累计分钟（疲劳）”分层做动态规划：学习/打工只会把层数往大推，冒险/补给留在本层。
        // 每层只留 Pareto 集，新局面入层时与全层比支配——跨代支配也一并生效，
        // 不会像按动作数逐代展开那样把深处的均衡最优解饿死或淹没在展开预算里。
        val layers = java.util.TreeMap<Int, ArrayList<State>>()
        val pending = java.util.TreeMap<Int, java.util.ArrayDeque<State>>()

        fun insert(ns: State) {
            offers++
            if (better(ns, best)) best = ns
            val set = layers.getOrPut(ns.fatigue) { ArrayList() }
            for (e in set) if (dominates(e, ns)) {
                // 与层内已有局面完全等价（互相支配）时，留先手更好的那个当代表：
                // 打工的孩子在上一层处理时就生成、冒险的孩子在本层才生成，
                // 不换代表的话「先冒险」血统会系统性地先占住每个坐标，
                // 「先打工」的等价局面永远生不出来，同分 tie-break 也无从生效。
                if (dominates(ns, e) && firstPref(ns) > firstPref(e)) {
                    set.remove(e); set += ns
                    pending.getOrPut(ns.fatigue) { java.util.ArrayDeque() } += ns
                }
                return
            }
            set.removeAll { dominates(ns, it) }
            if (set.size >= maxLayer) {
                truncated = true
                // 层满：只在比当前层内最差还强时才挤进来
                val worst = set.minWithOrNull { a, b -> if (better(a, b)) 1 else if (better(b, a)) -1 else 0 } ?: return
                if (!better(ns, worst)) return
                set.remove(worst)
            }
            set += ns
            pending.getOrPut(ns.fatigue) { java.util.ArrayDeque() } += ns
        }

        // 即时补给：把体力/清洁按需补到够做这个动作（封顶 100），学费与补给钱一并扣；
        // 金币不够（含补给）返回 null，这个动作在当前局面下做不了。
        fun withSupplies(s: State, costSt: Int, costCl: Int, fee: Int): State? {
            var st = s.stamina; var cl = s.clean; var gold = s.gold; var f = 0; var b = 0
            while (st < costSt) {
                if (gold < inp.biscuitPrice || st >= 100) return null
                gold -= inp.biscuitPrice; st = min(100, st + 10); f++
            }
            while (cl < costCl) {
                if (gold < inp.soapPrice || cl >= 100) return null
                gold -= inp.soapPrice; cl = min(100, cl + 10); b++
            }
            if (gold < fee) return null
            return s.copy(gold = gold - fee, stamina = st, clean = cl, feeds = s.feeds + f, baths = s.baths + b)
        }

        // 整批补给：总量可以超过 100（中途分批喂/洗），按缺口向上取整买齐，钱不够整批做不了。
        // 中途不饿着的前提是单趟消耗 ≤ 100（目录实测远小于此），按需在趟间补即可。
        fun withBatchSupplies(s: State, totalSt: Int, totalCl: Int): State? {
            var gold = s.gold; var f = 0; var b = 0
            if (totalSt > s.stamina) { f = (totalSt - s.stamina + 9) / 10; gold -= f * inp.biscuitPrice }
            if (totalCl > s.clean) { b = (totalCl - s.clean + 9) / 10; gold -= b * inp.soapPrice }
            if (gold < 0) return null
            return s.copy(gold = gold, stamina = s.stamina + f * 10, clean = s.clean + b * 10,
                feeds = s.feeds + f, baths = s.baths + b)
        }

        // —— 贪心保底种子：按单位时间收益先滚动一条完整可行计划，给 best 垫底 ——
        // 转移口径与 DP 完全一致（同一套即时补给、疲劳分档、工分净亏刹车、冒险按批），
        // 只是每步只取当前单位时间收益最高的动作，收益按目标算：
        // POINTS 用纯工分/分钟（不掺金币——掺了会把高金币低工分的短工抬到前面，
        // 那正是旧逻辑混合 rate 在工分优先下会犯的错）、ATTR 用目标属性增益/分钟、
        // GOLD 用金币/分钟（冒险也参与比价）。冒险在 POINTS/ATTR 下不产出目标，
        // 只在期末基线还差钱、或属性优先时正收益课只是暂时买不起时按批补金币。
        // 贪心计划是状态空间里真实可达的局面：垫底之后 DP 再怎么触顶，
        // 返回的计划也不会比它差——这是「优化版不输旧逻辑」的硬保证。
        fun greedySeed(): State {
            var s = start
            var guard = 0
            while (guard++ < 20000) {
                var next: State? = null
                var bestRate = 0.0
                if (inp.goal == AutoGoal.ATTR) for (c in inp.courses) {
                    if (c.durationMin <= 0 || s.t + c.durationMin > horizon) continue
                    val tf = tierFactor(s.fatigue, c.durationMin)
                    if (tf < inp.studyMinFactor - 1e-9) continue
                    val rate = c.targetGain * tf / c.durationMin
                    if (rate <= bestRate) continue
                    val base = withSupplies(s, c.staminaCost, c.cleanCost, c.fee) ?: continue
                    bestRate = rate
                    next = base.copy(
                        t = s.t + c.durationMin, fatigue = s.fatigue + c.durationMin,
                        stamina = base.stamina - c.staminaCost, clean = base.clean - c.cleanCost,
                        attr = s.attr + c.targetGain * tf,
                        first = s.first ?: Act(ActKind.STUDY, c.sub, c.name), studies = s.studies + 1,
                    )
                }
                for (j in inp.jobs) {
                    if (j.durationMin <= 0 || s.t + j.durationMin > horizon) continue
                    val tf = tierFactor(s.fatigue, j.durationMin)
                    // 工分净亏刹车与 DP 同口径（收入未知的工豁免）
                    if (inp.goal == AutoGoal.POINTS && j.incomeKnown) {
                        val supplyCost = j.staminaCost / 10.0 * inp.biscuitPrice + j.cleanCost / 10.0 * inp.soapPrice
                        if (j.income * tf < supplyCost - 1e-9) continue
                    }
                    val rate = when (inp.goal) {
                        AutoGoal.POINTS -> j.points * tf / j.durationMin
                        AutoGoal.GOLD -> (if (j.incomeKnown) j.income else 0.0) * tf / j.durationMin
                        AutoGoal.ATTR -> 0.0
                    }
                    if (rate <= bestRate) continue
                    val base = withSupplies(s, j.staminaCost, j.cleanCost, 0) ?: continue
                    bestRate = rate
                    next = base.copy(
                        t = s.t + j.durationMin, fatigue = s.fatigue + j.durationMin,
                        gold = base.gold + j.income * tf,
                        stamina = base.stamina - j.staminaCost, clean = base.clean - j.cleanCost,
                        points = s.points + j.points * tf,
                        first = s.first ?: Act(ActKind.WORK, j.sub, j.name), works = s.works + 1,
                    )
                }
                if (inp.goal == AutoGoal.GOLD) for (a in inp.adventures) {
                    if (a.evGold <= 0 || a.cycleMin <= 0 || s.t + a.cycleMin > horizon || s.pool <= 0.01) continue
                    val rate = a.evGold / a.cycleMin
                    if (rate <= bestRate) continue
                    val n = minOf(ADV_BATCH, kotlin.math.ceil(s.pool / a.evGold).toInt(), ((horizon - s.t) / a.cycleMin).toInt())
                    if (n <= 0) continue
                    val base = withBatchSupplies(s, a.staminaCost * n, a.cleanCost * n) ?: continue
                    val gain = min(a.evGold * n, s.pool)
                    bestRate = rate
                    next = base.copy(
                        t = s.t + a.cycleMin * n, gold = base.gold + gain, pool = s.pool - gain,
                        stamina = base.stamina - a.staminaCost * n, clean = base.clean - a.cleanCost * n,
                        first = s.first ?: Act(ActKind.ADVENTURE, a.sub, a.name), advs = s.advs + n,
                    )
                }
                if (next != null) { s = next; continue }
                // 没有正收益主动作了：需要补金币才用冒险（基线差钱 / 买不起正收益课），补完回去继续排正事
                val earnForBaseline = inp.goal != AutoGoal.GOLD && s.gold < floorGold - 1e-9
                val earnForCourse = inp.goal == AutoGoal.ATTR && inp.courses.any { c ->
                    c.durationMin > 0 && s.t + c.durationMin <= horizon && c.targetGain > 0 &&
                        tierFactor(s.fatigue, c.durationMin) >= inp.studyMinFactor - 1e-9 &&
                        withSupplies(s, c.staminaCost, c.cleanCost, c.fee) == null
                }
                if ((!earnForBaseline && !earnForCourse) || s.pool <= 0.01) break
                var advNext: State? = null
                for (a in inp.adventures) {
                    if (a.evGold <= 0 || a.cycleMin <= 0 || s.t + a.cycleMin > horizon) continue
                    val n = minOf(ADV_BATCH, kotlin.math.ceil(s.pool / a.evGold).toInt(), ((horizon - s.t) / a.cycleMin).toInt())
                    if (n <= 0) continue
                    val base = withBatchSupplies(s, a.staminaCost * n, a.cleanCost * n) ?: continue
                    val gain = min(a.evGold * n, s.pool)
                    advNext = base.copy(
                        t = s.t + a.cycleMin * n, gold = base.gold + gain, pool = s.pool - gain,
                        stamina = base.stamina - a.staminaCost * n, clean = base.clean - a.cleanCost * n,
                        first = s.first ?: Act(ActKind.ADVENTURE, a.sub, a.name), advs = s.advs + n,
                    )
                    break
                }
                if (advNext == null) break
                s = advNext
            }
            return s
        }

        val greedy = greedySeed()
        if (better(greedy, best)) best = greedy

        // 时间预算：展开中每约 1024 次查一次表，超时就拿当前最优收工（贪心种子
        // 已垫底，不会交白卷）。全量搜索在真机上要几十秒，宠物干等不起、也耗电。
        val deadlineNs = System.nanoTime() + timeBudgetMs * 1_000_000L
        var timeCheckedOffers = 0
        fun timeExpired(): Boolean {
            if (offers - timeCheckedOffers < 1024) return false
            timeCheckedOffers = offers
            return System.nanoTime() >= deadlineNs
        }
        var timeUp = false

        insert(start)
        while (pending.isNotEmpty() && offers < maxOffers && !timeUp) {
            val m = pending.firstKey()
            val q = pending[m]!!
            while (q.isNotEmpty() && offers < maxOffers) {
                if (timeExpired()) { timeUp = true; truncated = true; break }
                val s = q.removeFirst()
                // —— 学习 ——
                for (c in inp.courses) {
                    if (c.durationMin <= 0 || s.t + c.durationMin > horizon) continue
                    val f = tierFactor(s.fatigue, c.durationMin)
                    // 属性优先的停课线（用户设置）：学费不打折、收益打折——折得太狠的课不排，
                    // 免得拿全额学费买一折属性。按这节课跨段加权后的平均系数判。
                    if (inp.goal == AutoGoal.ATTR && f < inp.studyMinFactor - 1e-9) continue
                    val base = withSupplies(s, c.staminaCost, c.cleanCost, c.fee) ?: continue
                    insert(base.copy(
                        t = s.t + c.durationMin, fatigue = s.fatigue + c.durationMin,
                        stamina = base.stamina - c.staminaCost, clean = base.clean - c.cleanCost,
                        attr = s.attr + c.targetGain * f,
                        first = s.first ?: Act(ActKind.STUDY, c.sub, c.name), studies = s.studies + 1,
                    ))
                }
                // —— 打工 ——
                for (j in inp.jobs) {
                    if (j.durationMin <= 0 || s.t + j.durationMin > horizon) continue
                    val f = tierFactor(s.fatigue, j.durationMin)
                    // 工分优先的净亏刹车（用户 2026-10-17 定的）：按这趟所处时段的折扣算，
                    // 收入盖不住它的补给耗费（体力/清洁按单价折钱）就别排——不拿前期的盈余
                    // 去补贴深档的一折工分。跑之前用目录/学习值就能判，不用真跑一趟；
                    // 收入还未知的岗位不拦，先跑出数据（否则永远学不到）。
                    if (inp.goal == AutoGoal.POINTS && j.incomeKnown) {
                        val supplyCost = j.staminaCost / 10.0 * inp.biscuitPrice + j.cleanCost / 10.0 * inp.soapPrice
                        if (j.income * f < supplyCost - 1e-9) continue
                    }
                    val base = withSupplies(s, j.staminaCost, j.cleanCost, 0) ?: continue
                    insert(base.copy(
                        t = s.t + j.durationMin, fatigue = s.fatigue + j.durationMin,
                        gold = base.gold + j.income * f,
                        stamina = base.stamina - j.staminaCost, clean = base.clean - j.cleanCost,
                        points = s.points + j.points * f,
                        first = s.first ?: Act(ActKind.WORK, j.sub, j.name), works = s.works + 1,
                    ))
                }
                // —— 冒险（不占疲劳，金币池共享）：按批展开，一趟转移连刷至多 ADV_BATCH 趟 ——
                if (s.pool > 0.01) for (a in inp.adventures) {
                    if (a.evGold <= 0 || a.cycleMin <= 0 || s.t + a.cycleMin > horizon) continue
                    val poolTrips = kotlin.math.ceil(s.pool / a.evGold).toInt()      // 刷干池子要几趟
                    val timeTrips = ((horizon - s.t) / a.cycleMin).toInt()           // 剩余时间装得下几趟
                    val n = minOf(ADV_BATCH, poolTrips, timeTrips)
                    if (n <= 0) continue
                    val totalSt = a.staminaCost * n; val totalCl = a.cleanCost * n
                    val base = withBatchSupplies(s, totalSt, totalCl) ?: continue
                    val gain = min(a.evGold * n, s.pool)
                    insert(base.copy(
                        t = s.t + a.cycleMin * n, gold = base.gold + gain, pool = s.pool - gain,
                        stamina = base.stamina - totalSt, clean = base.clean - totalCl,
                        first = s.first ?: Act(ActKind.ADVENTURE, a.sub, a.name), advs = s.advs + n,
                    ))
                }
            }
            if (q.isEmpty()) pending.remove(m)
        }
        if (offers >= maxOffers) truncated = true

        val b = best
        val summary = "学×${b.studies} 工×${b.works} 冒险×${b.advs} 饼干×${b.feeds} 香皂×${b.baths}" +
            "｜预计最终金币 ${b.gold.toInt()} 工分 ${b.points.toInt()} 目标属性 +${(b.attr * 10).toInt() / 10.0}" +
            (if (truncated) "（搜索触顶，近似解）" else "")
        return Plan(b.first, b.studies, b.works, b.advs, b.feeds, b.baths, b.gold, b.points, b.attr, summary)
    }
}
