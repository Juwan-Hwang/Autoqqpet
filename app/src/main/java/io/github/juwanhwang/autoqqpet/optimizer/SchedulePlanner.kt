package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.pet.RuleThen
import io.github.juwanhwang.autoqqpet.pet.RuleWhen
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.Step
import io.github.juwanhwang.autoqqpet.pet.StepKind
import io.github.juwanhwang.autoqqpet.pet.Wrap

/** 一步为什么做不了：区分开才能判"学费不够"这类规则到底该不该触发。 */
enum class BlockReason { NONE, NO_SUCH_SUB, NOT_DOABLE, NO_MONEY, QUOTA, PAY_TIRED }

/**
 * 排班执行器：把 [Schedule] 这条流水线跑在实时快照上。
 *
 * 只接管"这一轮做什么主任务"，其它一律复用既有口径：
 *  - 照顾永远优先（体力/清洁低于地板先喂先洗，被服务端拒收时不饿死循环）；
 *  - 条目收益仍由 [DayPlanner] 的 rate 公式现算（这里一份公式都不写，避免两处漂移）；
 *    rate 在排班里**只用于同一步内自动选哪一条**，不作拦步条件（靠 rate 取舍是自动模式的事）；
 *  - **排班是用户下达的指令，不是建议**（2026-10-16 定，公开仓 issue #6/#5）：疲劳/降效不得把
 *    步骤剔掉或拦下——`penalizedSubs` 在这里一概不看，学工满 8 小时也一样；疲劳只经用户自己
 *    写的 `PAY_TIRED` 例外规则介入，没写这条规则就照常按顺序做完（无限循环步永不因疲劳停）；
 *  - 仍算硬条件的只有“物理上做不了”：目录 `canDo=false`、学费不够（含用户自设的金币预留
 *    `goldReserve`，走 NO_MONEY 规则链）、冒险次数用完；
 *  - PK / 访问 / 踩踩是旁路，不在此处（见 AutoRunner.maybeBypass）。
 *
 * 轮次语义：`wrap=loop` 回到第一步时换轮（由 AutoRunner 递增轮次实现），**做完的步**计数作废、
 * 从头再来；**没做完的步**（被规则跳过、差几次没达标）由 AutoRunner 按 [Result.incompleteOnWrap]
 * 把进度带进新一轮。不然"学习 6 次"若按天累计、一轮跑完后第一步永远处于已完成状态，循环
 * 名存实亡；若换轮时一并清零，没做完的步前面做的次数全不算数、反复重做（2026-10-29 群友实报）。
 */
class SchedulePlanner(private val auto: DayPlanner) {

    /** @param wrapped 本次是否跨过了末尾回到第一步 @param blocked 当前步被什么挡住（NONE=能正常做） */
    data class Result(
        val decision: Decision,
        val cursor: Int,
        val step: Step?,
        val wrapped: Boolean = false,
        val finishedRound: Boolean = false,
        val blocked: BlockReason = BlockReason.NONE,
        /** wrapped 时还没做完的步：换轮后它们的进度要带进新一轮（见类注释的轮次语义）。 */
        val incompleteOnWrap: Set<String> = emptySet(),
    )

    fun decide(
        sch: Schedule,
        fromCursor: Int,
        progressOf: (Step) -> Pair<Int, Int>,
        s: Snapshot,
    ): Result {
        val r = decideInner(sch, fromCursor, progressOf, s)
        if (!r.wrapped) return r
        val incomplete = sch.steps.filter { st ->
            if (!st.enabled) false
            else { val (c, m) = progressOf(st); !completed(st, c, m) }
        }.map { it.id }.toSet()
        return r.copy(incompleteOnWrap = incomplete)
    }

    private fun decideInner(
        sch: Schedule,
        fromCursor: Int,
        progressOf: (Step) -> Pair<Int, Int>,
        s: Snapshot,
    ): Result {
        val steps = sch.steps
        if (steps.isEmpty()) return Result(Decision(Kind.IDLE, "", "排班没有步骤"), 0, null)

        // 1) 照顾优先：这是物理前提，排在流水线的任何一步之前
        if (!s.feedBlocked && s.stamina < auto.staminaFloor)
            return Result(Decision(Kind.FEED, "biscuit", "排班·体力${s.stamina}<${auto.staminaFloor}，先喂"), fromCursor, null)
        if (!s.bathBlocked && s.clean < auto.cleanFloor)
            return Result(Decision(Kind.BATH, "soap", "排班·清洁${s.clean}<${auto.cleanFloor}，先洗"), fromCursor, null)

        var idx = fromCursor.coerceIn(0, steps.size - 1)
        var wrapped = false

        // 2) 找到第一个未完成的步骤（可能连着好几步早就达标了，一次走完；禁用的步直接当已走过）
        var hops = 0
        while (hops++ <= steps.size) {
            val st = steps[idx]
            val (c, m) = progressOf(st)
            if (st.enabled && !completed(st, c, m)) break
            if (idx + 1 < steps.size) { idx++; continue }
            if (sch.wrap == Wrap.STOP) {
                return Result(
                    Decision(Kind.IDLE, "", "排班本轮 ${steps.count { it.enabled }} 步已全部完成"), idx, null, finishedRound = true,
                )
            }
            idx = 0; wrapped = true      // 回到第一步：本轮计数作废，所以它必然未完成
            break
        }

        // 3) 执行这一步；做不了就走例外规则（规则本身也可能指向另一类任务）
        //    疲劳例外：只有用户自己写了 PAY_TIRED 规则时，整体疲劳才把学/工步当作“被挡住”
        //    交给规则链处置；没写这条规则，疲劳对排班毫无影响，照常 entryFor。
        //    冒险步不吃这一条：冒险本来就是疲劳时的兜底，不能再被疲劳赶走。
        var guard = 0
        var blocked = BlockReason.NONE
        while (guard++ <= steps.size) {
            val st = steps[idx]
            if (!st.enabled) {
                // 禁用的步整步跳过：不算「做不了」，不触发规则，往后挪一格（末尾同样受 wrap 约束）
                if (idx + 1 < steps.size) { idx++; continue }
                if (sch.wrap == Wrap.STOP)
                    return Result(
                        Decision(Kind.IDLE, "", "排班剩余步骤都做不了（$blocked），本轮结束"), idx, st, wrapped,
                        finishedRound = true, blocked = blocked,
                    )
                idx = 0; wrapped = true
                continue
            }
            val tiredBlock = s.studyWorkTired && st.kind != StepKind.ADVENTURE &&
                sch.rules.any { it.cond == RuleWhen.PAY_TIRED }
            val pick = if (tiredBlock) null else entryFor(st, s)
            if (pick != null) {
                val (c, m) = progressOf(st)
                val tag = "排班 第${idx + 1}/${steps.size}步（${st.summary()}，已 ${st.progressText(c, m)}）"
                return Result(pick.decision.copy(reason = pick.decision.reason + " ｜ " + tag), idx, st, wrapped, blocked = blocked)
            }
            val why = if (tiredBlock) BlockReason.PAY_TIRED else lastBlock
            blocked = why
            val rule = sch.rules.firstOrNull { fires(it, st, s, why) }
            val then = rule?.then ?: RuleThen.SKIP_STEP     // 没写规则时的默认：跳过，绝不空转
            when (then) {
                RuleThen.PAUSE -> return Result(
                    Decision(Kind.IDLE, "", "已按规则停下来：${st.kind.cn}「${st.labelOrSub}」做不了（$why）"), idx, st, wrapped, blocked = blocked,
                )
                RuleThen.CARE -> {
                    // 只有真的低于地板才照顾，否则等于原地打转
                    if (!s.feedBlocked && s.stamina < auto.staminaFloor)
                        return Result(Decision(Kind.FEED, "biscuit", "规则·先照顾（体力${s.stamina}）"), idx, st, wrapped, blocked = blocked)
                    if (!s.bathBlocked && s.clean < auto.cleanFloor)
                        return Result(Decision(Kind.BATH, "soap", "规则·先照顾（清洁${s.clean}）"), idx, st, wrapped, blocked = blocked)
                }
                RuleThen.WORK, RuleThen.STUDY, RuleThen.ADVENTURE -> {
                    val alt = entryFor(Step("alt", kindOf(then)), s)
                    if (alt != null) {
                        blocked = why     // 改做了别的，但“那一步做不了”仍要报给上层（学费不够要提醒用户）
                        return Result(
                            alt.decision.copy(reason = alt.decision.reason + " ｜ 排班·规则改做${then.cn}（第${idx + 1}步 $why）"),
                            idx, st, wrapped, blocked = why,
                        )
                    }
                }
                RuleThen.SKIP_STEP -> Unit
            }
            // 跳过这一步：往后挪一格，末尾同样受 wrap 约束
            if (idx + 1 < steps.size) { idx++; continue }
            if (sch.wrap == Wrap.STOP)
                return Result(
                    Decision(Kind.IDLE, "", "排班剩余步骤都做不了（$why），本轮结束"), idx, st, wrapped,
                    finishedRound = true, blocked = blocked,
                )
            idx = 0; wrapped = true
        }
        return Result(Decision(Kind.IDLE, "", "排班所有步骤都做不了（$blocked）"), idx, null, wrapped, blocked = blocked)
    }

    /** 完成判定：无限循环永不完成；次数/分钟按"达到即完成"（长任务超出目标值算完成，不追回）。 */
    private fun completed(st: Step, count: Int, minutes: Int): Boolean = when (st.goal) {
        io.github.juwanhwang.autoqqpet.pet.GoalType.FOREVER -> false
        io.github.juwanhwang.autoqqpet.pet.GoalType.COUNT -> count >= st.n
        io.github.juwanhwang.autoqqpet.pet.GoalType.MINUTES -> minutes >= st.n
    }

    private fun kindOf(t: RuleThen): StepKind = when (t) {
        RuleThen.WORK -> StepKind.WORK
        RuleThen.STUDY -> StepKind.STUDY
        else -> StepKind.ADVENTURE
    }

    /** 上一次 [entryFor] 挡住的原因（供规则判定用）。 */
    private var lastBlock: BlockReason = BlockReason.NONE

    /**
     * 钉选步的候选集：按【组＋时长】取，不按编号认条目（语义见 [Step.dur]）。
     * 时长的来源按可信度排：步骤存了 [Step.dur] 就用它；没存（旧排班）先按编号在组里找到那一条读它的时长，
     * 编号也失效了（升级换代）就按当年选中的名字在当前目录全池里读出时长——名字是升级后唯一还认得出的线索，
     * 只用这一次把时长读出来，之后匹配仍只看组＋时长。三条路都读不出时长 = 这一档真没了，返回 null。
     * 自动步（没钉编号也没钉时长）整组都是候选。
     */
    private fun <T> pinnedPool(
        st: Step, group: List<T>, pool: List<T>,
        subOf: (T) -> Long, durOf: (T) -> Int, nameOf: (T) -> String,
    ): List<T>? {
        if (st.sub <= 0L && st.dur <= 0) return group
        val target = when {
            st.dur > 0 -> st.dur
            else -> group.firstOrNull { subOf(it) == st.sub }?.let(durOf)
                ?: pool.firstOrNull { st.itemName.isNotBlank() && nameOf(it) == st.itemName }?.let(durOf)
        } ?: return null
        return group.filter { durOf(it) == target }.ifEmpty { null }
    }

    /**
     * 取这一步要做的那条：先按【组】筛（打工看职业、学习看属性），钉选步再按【时长】收窄（见 [pinnedPool]），
     * 最后在本轮实时读数里选最划算的一条；自动步就是整组里选最划算的一条。
     * 返回 null 表示做不了，原因写在 [lastBlock]。
     */
    private fun entryFor(st: Step, s: Snapshot): Pick? {
        // 不看 s.penalizedSubs：降效是自动模式的取舍依据；排班里用户点名要做的档，降效了也做。
        return when (st.kind) {
            StepKind.STUDY -> {
                val pool = s.courses.filter { it.canDo && it.durationMin > 0 }
                if (pool.isEmpty()) return block(BlockReason.NOT_DOABLE)
                val group = if (st.attr.isEmpty()) pool else pool.filter { attrCn(it.attr) == st.attr }
                if (group.isEmpty()) return block(BlockReason.NO_SUCH_SUB)
                val tiered = pinnedPool(st, group, pool, { it.subEventType }, { it.durationMin }, { it.name })
                    ?: return block(BlockReason.NO_SUCH_SUB)
                val canPay = tiered.filter { s.gold - it.fee >= auto.goldReserve }
                if (canPay.isEmpty()) return block(BlockReason.NO_MONEY)
                // rate 只排序不拦步：疲劳后读数变低、甚至 rate ≤ 0，用户排的课照上
                // （与 v2.1.1 冒险“目录写属性+0 不等于白跑”的口径一致）
                val best = canPay.maxByOrNull { auto.courseRate(it) } ?: return block(BlockReason.NOT_DOABLE)
                Pick(Decision(Kind.STUDY, best.name, "学习 rate=${"%.1f".format(auto.courseRate(best))}", best.subEventType))
            }
            StepKind.WORK -> {
                val pool = s.jobs.filter { it.canDo && it.durationMin > 0 }
                if (pool.isEmpty()) return block(BlockReason.NOT_DOABLE)
                val group = if (st.career <= 0L) pool else pool.filter { it.careerType == st.career }
                if (group.isEmpty()) return block(BlockReason.NO_SUCH_SUB)
                val tiered = pinnedPool(st, group, pool, { it.subEventType }, { it.durationMin }, { it.name })
                    ?: return block(BlockReason.NO_SUCH_SUB)
                // 同学习：rate 只排序不拦步，用户排的工照打
                val best = tiered.maxByOrNull { auto.jobRate(it) } ?: return block(BlockReason.NOT_DOABLE)
                Pick(Decision(Kind.WORK, best.name, "打工 rate=${"%.1f".format(auto.jobRate(best))}", best.subEventType))
            }
            StepKind.ADVENTURE -> {
                if (s.adventureLeft <= 0) return block(BlockReason.QUOTA)
                val pool = s.adventures.filter { it.canDo }
                if (pool.isEmpty()) return block(BlockReason.NOT_DOABLE)
                // 冒险的 subEventType 真机实测为 0（服务端靠名字认条目），所以这一类按名字钉，不看 sub
                val tiered = when {
                    st.itemName.isNotBlank() -> pool.filter { it.name == st.itemName }
                    st.sub > 0L -> pool.filter { it.subEventType == st.sub }
                    else -> pool
                }
                if (tiered.isEmpty()) return block(BlockReason.NO_SUCH_SUB)
                val best = tiered.maxByOrNull { auto.adventureRate(it) } ?: return block(BlockReason.NOT_DOABLE)
                Pick(Decision(Kind.ADVENTURE, best.name, "冒险 rate=${"%.1f".format(auto.adventureRate(best))}", best.subEventType))
            }
        }
    }

    /** 属性中文名（与界面上的选项措辞一致，排班步骤里存的就是这个字串）。 */
    private fun attrCn(a: io.github.juwanhwang.autoqqpet.pet.Attr): String = when (a) {
        io.github.juwanhwang.autoqqpet.pet.Attr.STRENGTH -> "力量"
        io.github.juwanhwang.autoqqpet.pet.Attr.INTEL -> "智力"
        io.github.juwanhwang.autoqqpet.pet.Attr.CHARM -> "魅力"
        io.github.juwanhwang.autoqqpet.pet.Attr.RANDOM -> "随机"
        else -> "属性"
    }

    private data class Pick(val decision: Decision)

    private fun block(r: BlockReason): Pick? {
        lastBlock = r
        return null
    }

    /** 规则是否命中。[why] 是这一步做不了的原因（没有原因就不匹配"做不了"类条件）。 */
    private fun fires(r: io.github.juwanhwang.autoqqpet.pet.Rule, st: Step, s: Snapshot, why: BlockReason): Boolean = when (r.cond) {
        RuleWhen.GOLD_BELOW -> s.gold < r.value
        RuleWhen.STAMINA_BELOW -> s.stamina < r.value
        RuleWhen.CLEAN_BELOW -> s.clean < r.value
        RuleWhen.PAY_TIRED -> s.studyWorkTired
        RuleWhen.STEP_UNAVAILABLE -> why != BlockReason.NONE
        RuleWhen.STUDY_FEE_UNAFFORDABLE -> st.kind == StepKind.STUDY && why == BlockReason.NO_MONEY
    }

    /** 界面与日志共用的“这一步在做什么”的一行说明。 */
    private val Step.labelOrSub: String
        get() = itemName.ifBlank {
            when (kind) {
                StepKind.WORK -> groupName.ifBlank { if (career > 0) "type=$career" else "任意职业" }
                StepKind.STUDY -> attr.ifBlank { "任意属性" }
                StepKind.ADVENTURE -> "冒险"
            }
        }
}

/** 冒险步按实际用时折算的进度分钟：四舍五入到整分（45 秒的召回趟记 1 分，150 秒的整趟记 3 分）。 */
internal fun stepMinutesForElapsed(elapsedSec: Long): Int =
    ((elapsedSec + 30) / 60).toInt().coerceAtLeast(0)
