package io.github.juwanhwang.autoqqpet.pet

import org.json.JSONArray
import org.json.JSONObject

/** 排班步骤做什么。PK/访问/踩踩不在序列里：它们一直是 AutoRunner.maybeBypass() 的旁路，与排班无关。 */
enum class StepKind(val cn: String) { WORK("打工"), STUDY("学习"), ADVENTURE("冒险") }

/** 做到什么程度算这一步完成。FOREVER 只允许出现在最后一步（中间的能循环就永远轮不到后面的）。 */
enum class GoalType(val cn: String) { COUNT("次数"), MINUTES("时长"), FOREVER("无限循环") }

/** 例外规则的触发条件。 */
enum class RuleWhen(val cn: String) {
    GOLD_BELOW("金币低于"),
    STUDY_FEE_UNAFFORDABLE("学费不够"),
    STAMINA_BELOW("体力低于"),
    CLEAN_BELOW("清洁低于"),
    STEP_UNAVAILABLE("这一步当前做不了"),
    PAY_TIRED("今日已判疲劳"),
}

/** 例外规则的处理方式。 */
enum class RuleThen(val cn: String) {
    PAUSE("停下来"),
    ADVENTURE("去冒险"),
    WORK("去打工"),
    STUDY("去学习"),
    CARE("先照顾"),
    SKIP_STEP("跳过这一步"),
}

/** 跑完一整轮之后怎么办。 */
enum class Wrap(val cn: String) { LOOP("回到第一步继续"), STOP("停下来等明天") }

/**
 * 一步：做【什么】直到【满足什么】。
 *
 * 打工与学习都是“先选一组、再选具体做哪一条”两级（用户要求：所有岗位/课程平铺在一步里太多，
 * 而第二级必须带课程名与收获，不能只给一个分钟数）：
 *  - [career]：打工的职业 type（0 = 任意职业）；
 *  - [attr]：学习要加的属性（力量/智力/魅力/随机；空串 = 任意）。学园阶段不选：只能往上走、不能回去；
 *  - [sub]：选条目时那一条的编号（subEventType）；0 = 自动，在本组里按本轮实时读数选最划算的一条。
 *  - [dur]：选条目时那一条的时长（分钟）。**钉住的其实是组＋时长，不是那一条本身**（用户 2026-10-27 定）：
 *    学园升级会把整套课程同名换编号、换数值，编号认不到就整步停摆；按组＋时长认，升级后同组同时长的
 *    条目直接接上，组内选哪一条仍按本轮实时读数取最划算的（与最优调度选条目的口径一致）。
 * [groupName] / [itemName] 是选条目时的名字快照：显示用；外加给没有 [dur] 的旧排班做一次性迁移
 * （编号已失效时，按当年选中的名字在当前目录里读出它的时长，见 SchedulePlanner）。
 */
data class Step(
    val id: String,
    val kind: StepKind,
    val career: Long = 0,
    val attr: String = "",
    val sub: Long = 0,
    val groupName: String = "",
    val itemName: String = "",
    val goal: GoalType = GoalType.COUNT,
    val n: Int = 1,
    /** 钉住的时长（分钟）；0 = 没钉时长（自动步，或还没迁移的旧排班）。 */
    val dur: Int = 0,
    /** 临时禁用：关掉的步执行时整步跳过，配置与当日进度都留着，重新打开接着算（免得切换配置要删了重加）。 */
    val enabled: Boolean = true,
) {
    /** 界面上的一行，例：`打工 · 闪耀星屋 · 练习室加练 10分钟 · 6 次`。 */
    fun summary(): String {
        val group = when (kind) {
            StepKind.WORK -> groupName.ifBlank { if (career > 0) "type=$career" else "任意职业" }
            StepKind.STUDY -> attr.ifBlank { "任意属性" }
            StepKind.ADVENTURE -> "冒险"
        }
        val what = when {
            dur > 0 && itemName.isNotBlank() -> "$itemName ${dur}分钟"
            dur > 0 -> "${dur}分钟"
            else -> itemName.ifBlank { if (sub > 0) "#$sub" else "自动（取最划算的一条）" }
        }
        return "${kind.cn} · $group · $what · " + when (goal) {
            GoalType.FOREVER -> "无限循环"
            GoalType.COUNT -> "$n 次"
            GoalType.MINUTES -> "$n 分钟"
        }
    }

    /** 进度后缀（界面卡片显示"还差 3 次"用）；无限循环没有目标值。 */
    fun progressText(count: Int, minutes: Int): String = when (goal) {
        GoalType.FOREVER -> "已 $count 次"
        GoalType.COUNT -> "$count/$n 次"
        GoalType.MINUTES -> "$minutes/$n 分钟"
    }
}

/** 一条例外规则：条件命中就按 [then] 办。规则按顺序匹配，第一条命中生效。 */
data class Rule(
    val id: String,
    val cond: RuleWhen,
    val value: Int = 0,
    val then: RuleThen = RuleThen.SKIP_STEP,
) {
    fun summary(): String = when (cond) {
        RuleWhen.GOLD_BELOW, RuleWhen.STAMINA_BELOW, RuleWhen.CLEAN_BELOW -> "${cond.cn} $value → ${then.cn}"
        else -> "${cond.cn} → ${then.cn}"
    }
}

/**
 * 自定义排班：一条流水线 + 一组例外规则。
 *
 * 心智模型：按顺序做「什么 → 做到什么程度」，做完进下一步；出岔子时按用户定的规则处理，
 * 而不是程序自己猜。照顾（喂食/洗澡）永远优先于排班 —— 不喂就干不动活，所以它不进序列，
 * 只作为规则里的一个可选动作。
 *
 * **没有自己的开关**：启用与否完全由“怎么干活”里的「自定义排班」那一挡决定（`adv_mode=custom`），
 * 免得两个开关指向同一件事、状态不同步。
 *
 * 持久化在 `autoqqpet_settings` 的 [PREF_KEY] 键里（整份 JSON），并随设置一起镜像进模块主存，
 * 所以 QQ 被重装也不会把排班丢掉。
 */
data class Schedule(
    val wrap: Wrap = Wrap.LOOP,
    val steps: List<Step> = emptyList(),
    val rules: List<Rule> = emptyList(),
) {
    /** 排班里有可执行的步骤吗（没步骤、或步骤全被禁用时，即使选了“自定义”也要回退自动择优）。 */
    val usable: Boolean get() = steps.any { it.enabled }

    /** 一行摘要（设置页入口用）。 */
    fun summary(): String = when {
        steps.isEmpty() -> "还没有步骤"
        else -> steps.size.toString() + " 步 · " + steps.joinToString(" → ") { it.summary() }
    }

    /**
     * 收输入：把不该有的东西挡在执行之外（界面已经限制了，这里再校验一次，不信任输入）。
     * - 非最后一步的"无限循环"降级成"1 次"；
     * - 步数**不设上限**（2026-10-15 用户要求去掉 12 步上限）；规则上限 [MAX_RULES]；
     * - 步骤 id 去重（id 撞号会让界面列表的 key 冲突而崩，长排班里复制/添加更容易撞）；
     * - 次数/分钟越界的钳回范围；空 label 保留（"自动"就是这个意思）。
     */
    fun sanitized(): Schedule {
        if (steps.isEmpty()) return copy(rules = rules.take(MAX_RULES))
        val seen = HashSet<String>()
        val fixed = steps.mapIndexed { i, s0 ->
            var id = s0.id.ifBlank { "s$i" }
            if (!seen.add(id)) { var k = 2; while (!seen.add("$id-$k")) k++; id = "$id-$k" }
            val s = if (id == s0.id) s0 else s0.copy(id = id)
            val forever = s.goal == GoalType.FOREVER && i == steps.size - 1
            when {
                forever -> s.copy(goal = GoalType.FOREVER, n = 0)
                s.goal == GoalType.COUNT -> s.copy(n = s.n.coerceIn(1, 99))
                s.goal == GoalType.MINUTES -> s.copy(n = s.n.coerceIn(5, 720))
                else -> s.copy(goal = GoalType.COUNT, n = 1)
            }
        }
        return copy(steps = fixed, rules = rules.take(MAX_RULES))
    }

    companion object {
        const val PREF_KEY = "schedule_json"
        const val SCHEMA_V = 1
        /** 规则上限：规则是“从上往下第一条命中生效”，6 种条件 × 少量阈值已足够；8 条之后几乎不可能被匹配到，留着上限防误操作。 */
        const val MAX_RULES = 8

        /** 关掉排班 = 完全走现有自动模式（默认值，老用户升级零变化）。 */
        val OFF = Schedule()

        /** 解析：格式不对、版本不认识、字段缺失一律退回 [OFF]，绝不崩也绝不瞎跑。 */
        fun parse(text: String?): Schedule {
            if (text.isNullOrBlank()) return OFF
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return OFF
            if (o.optInt("v", -1) > SCHEMA_V) return OFF      // 新版写的排班，旧程序不猜
            val steps = arrayListOf<Step>()
            o.optJSONArray("steps").orEmpty().let { arr ->
                for (i in 0 until arr.length()) {
                    val st = arr.optJSONObject(i) ?: continue
                    val kind = st.optString("kind").toEnumOrNull<StepKind>() ?: continue
                    val goal = st.optString("goal").toEnumOrNull<GoalType>() ?: GoalType.COUNT
                    steps += Step(
                        id = st.optString("id").ifBlank { "s$i" },
                        kind = kind,
                        career = st.optLong("career", 0L),
                        attr = st.optString("attr", ""),
                        sub = st.optLong("sub", 0L),
                        groupName = st.optString("group", ""),
                        itemName = st.optString("item", ""),
                        goal = goal,
                        n = st.optInt("n", 1),
                        dur = st.optInt("dur", 0).coerceIn(0, 1440),
                        enabled = st.optBoolean("enabled", true),
                    )
                }
            }
            val rules = arrayListOf<Rule>()
            o.optJSONArray("rules").orEmpty().let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val cond = r.optString("cond").toEnumOrNull<RuleWhen>() ?: continue
                    val then = r.optString("then").toEnumOrNull<RuleThen>() ?: RuleThen.SKIP_STEP
                    rules += Rule(id = r.optString("id").ifBlank { "r$i" }, cond = cond, value = r.optInt("value", 0), then = then)
                }
            }
            return Schedule(
                wrap = o.optString("wrap").toEnumOrNull<Wrap>() ?: Wrap.LOOP,
                steps = steps, rules = rules,
            ).sanitized()
        }

        fun encode(s: Schedule): String {
            val o = JSONObject()
            o.put("v", SCHEMA_V)
            o.put("wrap", s.wrap.name.lowercase())
            o.put("steps", JSONArray().apply {
                s.steps.forEach {
                    put(
                        JSONObject().put("id", it.id).put("kind", it.kind.name)
                            .put("career", it.career).put("attr", it.attr).put("sub", it.sub)
                            .put("group", it.groupName).put("item", it.itemName)
                            .put("goal", it.goal.name).put("n", it.n).put("dur", it.dur)
                            .put("enabled", it.enabled),
                    )
                }
            })
            o.put("rules", JSONArray().apply {
                s.rules.forEach {
                    put(JSONObject().put("id", it.id).put("cond", it.cond.name)
                        .put("value", it.value).put("then", it.then.name))
                }
            })
            return o.toString()
        }

        /** 界面"添加一步"的默认值：加在末尾，所以允许无限循环。 */
        fun newStep(kind: StepKind = StepKind.WORK) =
            Step(id = "s" + System.currentTimeMillis() % 100000, kind = kind, goal = GoalType.COUNT, n = 3)

        /**
         * 预设模板（空态与"从模板开始"用）。
         * 从零排班对普通用户太难，所以给三条最常见的；模板里的 label 留空 = 每轮现算最优，
         * 不会因为某天目录改名就失效。
         */
        fun templates(): List<Pair<String, Schedule>> = listOf(
            "打工为主" to Schedule(
                wrap = Wrap.LOOP,
                steps = listOf(
                    Step("t1", StepKind.WORK, goal = GoalType.COUNT, n = 6),
                    Step("t2", StepKind.STUDY, goal = GoalType.COUNT, n = 2),
                ),
            ),
            "学习为主" to Schedule(
                wrap = Wrap.LOOP,
                steps = listOf(
                    Step("t1", StepKind.STUDY, goal = GoalType.COUNT, n = 6),
                    Step("t2", StepKind.WORK, goal = GoalType.COUNT, n = 2),
                ),
            ),
            "学工与冒险轮换" to Schedule(
                wrap = Wrap.LOOP,
                steps = listOf(
                    Step("t1", StepKind.STUDY, goal = GoalType.MINUTES, n = 90),
                    Step("t2", StepKind.WORK, goal = GoalType.MINUTES, n = 60),
                    Step("t3", StepKind.ADVENTURE, goal = GoalType.COUNT, n = 5),
                ),
            ),
        )

        private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? = runCatching {
            enumValueOf<T>(trim().uppercase())
        }.getOrNull()

        private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()
    }
}
