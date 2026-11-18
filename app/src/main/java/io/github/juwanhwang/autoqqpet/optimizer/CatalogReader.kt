package io.github.juwanhwang.autoqqpet.optimizer

import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.pet.CareerInfo
import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import io.github.juwanhwang.autoqqpet.pet.DailyStore
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.pet.JobInfo
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import io.github.juwanhwang.autoqqpet.pet.SceneEconomics
import io.github.juwanhwang.autoqqpet.pet.SceneOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * 学工目录读取（决策与中途复查共用一份读法）。
 *
 * 读失败 ≠ 没课（真机 2026-10-14 06:46：课程读失败被当空目录，“学习优先”凭空去打了工）：
 * 某一类读失败时沿用上一份可用目录并写明。成功但为空才照实下结论。
 *
 * 岗位（R3，真机 2026-10-15：13 次“岗位本轮读失败…沿用上一份 0 档”，打工池饿死）：
 *  - **按职业分别处理**：哪个职业读成功就用哪个的新读数；读失败的职业单独回落到它自己的上一份；部分成功照样合并；
 *  - **回落顺序**：本进程上一份 → 持久化缓存 [JobCache]（QQ 侧落盘，模块 App 的 `work_catalog` 也会灌进来）；
 *    旧版“沿用上一份”取的是进程内 lastJobs，QQ 一重启就是空的；
 *  - 读成功但一条都没有 → 用 f100=3 再试一次（见 StoryApi.jobs 的请求体核对说明）。
 *
 * 复用（2026-10-15）：**静态结构**（学院阶段、已解锁职业、各职业的岗位名/subEvent/时长/消耗）按“游戏日”（每天 5:00 刷新）缓存；
 * **动态读数**（canDo、工分/属性这些会随疲劳被服务器下调的数、学费够不够）不能缓存——
 * 已确认的事实：目录工分是服务器按当日疲劳下调后的读数（DEV-NOTES 10-10/10-11，疲劳判定就靠它），canDo 随体力/清洁/金币变。
 * 所以每轮只读：课程 1 包（学费/属性/可做）+ **只读一个职业**的岗位（钉住的职业，否则缓存里最划算的那个）；
 * 阶段与职业列表当天复用；其余职业的缓存结构只给界面列表用、**不进决策**（决策只用现读的动态数）。每天第一次、阶段变化、职业解锁变化、开工被拒（[invalidate]）时全量重读。
 * 旧版每次决策：阶段 1 + 课程 1 + 职业 1 + 岗位 ≤4 = **≤7 包**；现在常态 **2 包**（课程 + 1 个职业）。
 */
class CatalogReader(
    private val service: QQPetService?,
    private val daily: DailyStore?,
    private val config: RunnerConfig,
    private val log: (String) -> Unit,
    private val cache: JobCache = JobCache.NONE,
    /** 测试注入：按职业读岗位。默认走 service。 */
    private val fetchJobs: (Long, Long) -> List<SceneOption> = { type, ver -> service!!.story.jobs(type, version = ver) },
    private val fetchCareers: () -> List<CareerInfo> = { service!!.story.careers() },
    private val fetchStage: () -> Long = { service!!.story.schoolStage() },
    private val fetchCourses: (Long) -> List<SceneOption> = { service!!.story.courses(it) },
    /** 岗位学习读数：种子/首个真实样本即可用（minSamples=1）——岗位工资近乎固定，攒到 2 个才定价会让新账空窗期里金币优先无工可打。 */
    private val learnedGold: (String) -> Double? = { k -> daily?.learnedGold(k, 1) },
) {
    data class Catalog(
        val courses: List<CourseInfo>, val rawCourses: List<SceneOption>,
        val jobs: List<JobInfo>, val rawJobs: List<SceneOption>,
        val stage: Long = 0,
        /** 本轮各职业的来源：fresh / memory / cache / gated / missing（诊断与单测用）。 */
        val jobSources: Map<Long, String> = emptyMap(),
        /** 本次 read 实际发出的读请求数（诊断/回报用）。 */
        val packets: Int = 0,
        /** 本次是否全量刷新了静态结构。 */
        val fullRefresh: Boolean = false,
    )

    /**
     * 岗位目录持久缓存（按职业存原始条目）。QQ 侧实现落在 `autoqqpet_catalog` prefs；单测用内存实现。
     * 格式：`{"at":ms,"careers":{"<type>":{"name":"…","jobs":[{n,s,c,cost,d,r,u,x,desc}]}}}`。
     */
    interface JobCache {
        fun load(): String
        fun save(json: String)

        companion object {
            val NONE = object : JobCache { override fun load() = ""; override fun save(json: String) = Unit }

            fun encode(map: Map<Long, Pair<String, List<SceneOption>>>, at: Long): String {
                val careers = JSONObject()
                map.forEach { (type, v) ->
                    val arr = JSONArray()
                    v.second.forEach { o ->
                        arr.put(JSONObject().put("n", o.name).put("s", o.subEventType).put("c", o.canDo).put("cost", o.cost)
                            .put("d", o.duration).put("r", o.reward).put("u", o.unavailableReason).put("x", o.extra).put("desc", o.description)
                            .put("w", o.wageRange))
                    }
                    careers.put(type.toString(), JSONObject().put("name", v.first).put("jobs", arr))
                }
                return JSONObject().put("at", at).put("careers", careers).toString()
            }

            fun decode(json: String): Map<Long, Pair<String, List<SceneOption>>> {
                if (json.isBlank()) return emptyMap()
                val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
                val careers = root.optJSONObject("careers") ?: return emptyMap()
                val out = LinkedHashMap<Long, Pair<String, List<SceneOption>>>()
                careers.keys().forEach { k ->
                    val type = k.toLongOrNull() ?: return@forEach
                    val o = careers.optJSONObject(k) ?: return@forEach
                    val arr = o.optJSONArray("jobs") ?: return@forEach
                    val jobs = (0 until arr.length()).mapNotNull { i ->
                        val j = arr.optJSONObject(i) ?: return@mapNotNull null
                        val sub = j.optLong("s", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                        SceneOption(j.optString("n"), sub, j.optBoolean("c", true), j.optString("cost"), j.optString("d"),
                            j.optString("r"), j.optString("u"), j.optString("x"), j.optString("desc"), j.optString("w"))
                    }
                    if (jobs.isNotEmpty()) out[type] = o.optString("name") to jobs
                }
                return out
            }

            /**
             * 模块 App 那份 `work_catalog`（joblist 全量读回传的 Tier 串）→ 缓存条目。
             * 只有名字、subEvent、时长、工分、体力/清洁消耗，足够开工（startWork 只要名字+subEvent）与比价。
             */
            fun fromTiers(tiers: List<JobCatalog.Tier>): Map<Long, Pair<String, List<SceneOption>>> =
                tiers.filter { it.careerType > 0 && it.subEventType > 0 }.groupBy { it.careerType }.mapValues { (_, list) ->
                    list.first().careerName to list.map { t ->
                        SceneOption(t.jobName, t.subEventType, t.canDo, cost = "体力${t.stamina} 清洁${t.clean}",
                            duration = t.duration.ifBlank { "${t.minutes}分钟" }, extra = "+${t.points}点")
                    }
                }
        }
    }

    companion object {
        /** 调试（`failread what=courses|careers|stage|jobs|none`）：让某类读取必失败，验证沿用分支。 */
        @Volatile
        var failRead: String = ""

        /** 进程内上一份（跨 runner 重建保留：改设置会重建 runner，不该把“上一份”一起丢掉）。 */
        private val memJobs = HashMap<Long, List<SceneOption>>()
        private val memNames = HashMap<Long, String>()
        @Volatile private var lastCareersMem: List<CareerInfo> = emptyList()

        fun resetMemoryForTest() { synchronized(memJobs) { memJobs.clear(); memNames.clear() }; lastCareersMem = emptyList(); coldReason = "启动" }

        /** 游戏日：每天 5:00（设备本地时区）刷新。 */
        fun gameDay(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
            java.time.Instant.ofEpochMilli(ms).atZone(zone).minusHours(5).toLocalDate().toEpochDay()

        /** 非空 = 下一次 read 必须全量重读结构（原因写日志）。跨 runner 重建保留。 */
        @Volatile private var coldReason: String = "启动"

        /** 让缓存立即失效（开工被拒、职业升级、阶段变化…）。 */
        fun invalidate(reason: String) { coldReason = reason }

        @Volatile private var lastRejectCode = 0
        @Volatile private var lastRejectAtMs = 0L

        /** 开工被拒的同码全量刷新间隔：同一个拒绝码 10 分钟内只让目录全量重读一次。 */
        private const val REJECT_FULL_MS = 10 * 60_000L

        /**
         * 开工被拒专用失效：同一拒绝码短时间内反复出现时只失效第一次，返回这次是否真的标了失效。
         * 2026-10-23 真机：135052 连拒约 76 分钟，每次被拒都全量重读目录，白白多发一串包。
         */
        fun invalidateForReject(code: Int, nowMs: Long): Boolean {
            if (code == lastRejectCode && nowMs - lastRejectAtMs < REJECT_FULL_MS) return false
            lastRejectCode = code; lastRejectAtMs = nowMs
            invalidate("开工被拒 code=$code")
            return true
        }
    }

    /** 时钟（单测注入）。 */
    var clock: () -> Long = System::currentTimeMillis
    private var packets = 0
    private fun <T> counted(what: String, block: () -> T): PetResult<T> { packets++; return guarded(what, block) }

    private var lastStage = 0L
    private var lastCourses: List<CourseInfo> = emptyList()
    private var lastRawCourses: List<SceneOption> = emptyList()

    private fun <T> guarded(what: String, block: () -> T): PetResult<T> =
        if (failRead == what) PetResult.Transport("调试注入：$what 读取失败") else PetResult.read(block)

    /**
     * @param extraFocusCareers 必须进决策的职业（排班步骤点名的职业）：不受 [RunnerConfig.workCareerType]
     * 打工方向收窄、且每轮现读。排班是用户指令——最优调度的选职业逻辑（钉子＋单焦点）若把步骤的职业
     * 挡在快照外，排班每轮都在空名单里找岗位、判 NO_SUCH_SUB 全表停摆（2026-10-30 群友实报）。
     */
    fun read(extraFocusCareers: Set<Long> = emptySet()): Catalog {
        packets = 0
        val meta = Meta.decode(runCatching { cache.load() }.getOrDefault(""))
        val today = gameDay(clock())
        var full = coldReason.isNotEmpty() || meta.day != today
        val why = when { coldReason.isNotEmpty() -> coldReason; meta.day != today -> "新的一天（5:00 刷新）"; else -> "" }
        if (full) {
            counted("stage") { fetchStage() }.getOrNull()?.let { st ->
                if (meta.stage >= 0 && st != meta.stage) log("🎓 学院阶段变化 ${meta.stage}→$st，目录全量重读")
                lastStage = st
            }
        } else if (lastStage <= 0 && meta.stage > 0) lastStage = meta.stage
        val stage = lastStage

        val cr = counted("courses") { fetchCourses(stage) }
        val rawCourses: List<SceneOption>
        val courses: List<CourseInfo>
        if (cr is PetResult.Ok) {
            rawCourses = cr.value
            courses = rawCourses.map { SceneEconomics.parseCourse(it) }
            if (courses.isNotEmpty()) { lastCourses = courses; lastRawCourses = rawCourses }
        } else {
            courses = lastCourses; rawCourses = lastRawCourses
            log("🎓 学院课程本轮读失败（不是没课），沿用上一份 ${courses.size} 门：${cr.describe()}")
        }

        val (rawJobs, sources) = readJobs(full, meta, extraFocusCareers)
        val jobs = rawJobs.map { (type, o) -> withLearned(SceneEconomics.parseJob(o, type)) }
        if (full) {
            coldReason = ""
            log("📚 目录结构全量刷新（$why）：职业 ${sources.size} 个，读包 $packets 个")
        }
        saveMeta(today, stage)
        return Catalog(courses, rawCourses, jobs, rawJobs.map { it.second }, stage, sources, packets, full)
    }

    /** 缓存里的结构元数据：游戏日、阶段、已解锁职业（与岗位缓存同一份 JSON）。 */
    data class Meta(val day: Long = -1, val stage: Long = -1, val careers: List<CareerInfo> = emptyList()) {
        companion object {
            fun decode(json: String): Meta {
                val o = runCatching { JSONObject(json) }.getOrNull() ?: return Meta()
                val arr = o.optJSONArray("career_list")
                val careers = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { c -> CareerInfo(c.optString("name"), c.optLong("t")) }?.takeIf { it.careerType > 0 }
                }
                return Meta(o.optLong("day", -1), o.optLong("stage", -1), careers)
            }
        }
    }

    private fun saveMeta(day: Long, stage: Long, careers: List<CareerInfo>? = null) = runCatching {
        val o = runCatching { JSONObject(cache.load()) }.getOrNull() ?: JSONObject()
        o.put("day", day).put("stage", stage)
        if (careers != null) o.put("career_list", JSONArray().apply { careers.forEach { put(JSONObject().put("t", it.careerType).put("name", it.name)) } })
        cache.save(o.toString())
    }

    /** 缓存里所有职业的岗位结构（给界面 work_catalog 用：runner 与界面共用一个数据源）。 */
    fun cachedTiers(): List<JobCatalog.Tier> = JobCache.decode(runCatching { cache.load() }.getOrDefault("")).flatMap { (type, v) ->
        v.second.map { o -> JobCatalog.of(SceneEconomics.parseJob(o, type), v.first, o.duration) }
    }

    /** @return (careerType, 条目) 列表（只含 canDo），以及每个职业的来源。 */
    private fun readJobs(full0: Boolean, meta: Meta, extraFocusCareers: Set<Long> = emptySet()): Pair<List<Pair<Long, SceneOption>>, Map<Long, String>> {
        var full = full0
        val cached by lazy { JobCache.decode(runCatching { cache.load() }.getOrDefault("")) }

        val careers: List<CareerInfo> = if (!full && meta.careers.isNotEmpty()) meta.careers else {
            val careersR = counted("careers") { fetchCareers() }
            careersR.getOrNull()?.filter { it.unlocked }?.also { fresh ->
                lastCareersMem = fresh
                if (meta.careers.isNotEmpty() && fresh.map { it.careerType }.toSet() != meta.careers.map { it.careerType }.toSet()) {
                    log("💼 已解锁职业变化（${meta.careers.size}→${fresh.size} 个），岗位全量重读"); full = true
                }
                saveMeta(meta.day, meta.stage, fresh)
            } ?: lastCareersMem.ifEmpty { meta.careers.ifEmpty { cached.map { (t, v) -> CareerInfo(v.first, t) } } }
                .also { log("💼 职业列表本轮读失败，沿用上一份 ${it.size} 个：${careersR.describe()}") }
        }

        val pool = if (config.workCareerType > 0) {
            val pinHit = careers.filter { it.careerType == config.workCareerType }
            val extras = careers.filter { it.careerType in extraFocusCareers }
            when {
                // 钉子与必读职业都落空才走原有兜底；必读职业命中时钉子失效也不连累它
                pinHit.isEmpty() && extras.isEmpty() && careers.isNotEmpty() -> {
                    log("⚠️ 指定的打工方向 type=${config.workCareerType} 当前不可用，本轮回到全职业横比")
                    careers
                }
                pinHit.isEmpty() && extras.isEmpty() ->
                    listOf(CareerInfo(cached[config.workCareerType]?.first ?: "type=${config.workCareerType}", config.workCareerType))
                else -> pinHit + extras
            }
        } else careers

        val out = ArrayList<Pair<Long, SceneOption>>()
        val sources = LinkedHashMap<Long, String>()
        val fresh = LinkedHashMap<Long, Pair<String, List<SceneOption>>>()
        val failedNames = ArrayList<String>()
        // 只读一个职业的动态读数：钉住的职业；否则缓存里最划算的那个（各职业四档实测常常完全相同）。
        // 全量刷新（每天第一次/失效）或缓存里缺这个职业时才把池子里的都读一遍。
        val focus0: Set<Long> = when {
            full -> pool.map { it.careerType }.toSet()
            pool.any { cached[it.careerType] == null } -> pool.filter { cached[it.careerType] == null }.map { it.careerType }.toSet() +
                setOfNotNull(bestCached(pool, cached))
            else -> setOfNotNull(bestCached(pool, cached) ?: pool.firstOrNull()?.careerType)
        }
        // 必读职业（排班步骤点名的）恒在焦点内：不现读就进不了决策，排班会拿着缓存名单判死步骤
        val focus: Set<Long> = if (extraFocusCareers.isEmpty()) focus0
            else focus0 + pool.map { it.careerType }.filter { it in extraFocusCareers }
        for (c in pool) {
            if (c.careerType !in focus && cached[c.careerType] != null) {
                // 非焦点职业：结构在缓存里，但报酬/可做是动态的 → **不进决策**（缓存里的旧数可能偏高，会把决策带偏）；
                // 只留给界面列表。焦点职业每轮现读，决策只用现读数。
                sources[c.careerType] = "cache"
                continue
            }
            var r = counted("jobs") { fetchJobs(c.careerType, Proto.OUTDOOR_V) }
            if (r is PetResult.Ok && r.value.isEmpty()) {
                // 读成功但空：用 f100=3 再试一次（冒险目录的实锤教训；岗位目录 f100=2 已验证能回全量，这只是兜底）
                val r3 = counted("jobs") { fetchJobs(c.careerType, Proto.ADVENTURE_CATALOG_V) }
                if (r3 is PetResult.Ok && r3.value.isNotEmpty()) { log("💼 「${c.name}」f100=2 回空、f100=3 回 ${r3.value.size} 条"); r = r3 }
            }
            when {
                r is PetResult.Ok && r.value.isNotEmpty() -> {
                    fresh[c.careerType] = c.name to r.value
                    synchronized(memJobs) { memJobs[c.careerType] = r.value; memNames[c.careerType] = c.name }
                    r.value.filter { it.canDo }.forEach { out += c.careerType to it }
                    sources[c.careerType] = "fresh"
                }
                r is PetResult.Ok -> sources[c.careerType] = "empty"   // 照实：服务器说这个职业没岗位
                r is PetResult.Rejected && (r.code in Proto.CAREER_GATE_CODES || r.message.contains("未达到该职业参与要求")) -> {
                    log("💼 「${c.name}」未达参与要求，跳过"); sources[c.careerType] = "gated"
                }
                else -> {
                    // 只有这个职业读失败：单独回落，不连累别的职业
                    val mem = synchronized(memJobs) { memJobs[c.careerType] }
                    val fallback = mem ?: cached[c.careerType]?.second
                    if (fallback != null) {
                        fallback.filter { it.canDo }.forEach { out += c.careerType to it }
                        sources[c.careerType] = if (mem != null) "memory" else "cache"
                    } else sources[c.careerType] = "missing"
                    failedNames += "${c.name}(${(r as? PetResult.Rejected)?.code ?: r.describe().take(30)})"
                }
            }
        }
        if (failedNames.isNotEmpty()) {
            val fb = sources.count { it.value == "memory" || it.value == "cache" }
            log("💼 岗位本轮 ${failedNames.size}/${pool.size} 个职业读失败（不是没岗位）：${failedNames.joinToString("、")}；" +
                "新读 ${sources.count { it.value == "fresh" }} 个，回落 $fb 个（进程内 ${sources.count { it.value == "memory" }} / 缓存 ${sources.count { it.value == "cache" }}），合计 ${out.size} 档")
        }
        if (full) {
            // 目录补全（只写缓存，不进决策）：界面目录是这份持久缓存的镜像，决策每轮只现读焦点职业，
            // 其余职业若从没被读过就永远不在缓存里，界面名单会少职业（2026-10-29 报修：开了 7 个只显示 2 个）。
            // 全量刷新时把决策没读的已解锁职业顺带读一遍并入缓存，名单才跟得上解锁进度。
            val rest = careers.filter { c -> sources[c.careerType] == null }
            var filled = 0
            for (c in rest) {
                var r = counted("jobs") { fetchJobs(c.careerType, Proto.OUTDOOR_V) }
                if (r is PetResult.Ok && r.value.isEmpty()) {
                    val r3 = counted("jobs") { fetchJobs(c.careerType, Proto.ADVENTURE_CATALOG_V) }
                    if (r3 is PetResult.Ok && r3.value.isNotEmpty()) r = r3
                }
                if (r is PetResult.Ok && r.value.isNotEmpty()) {
                    fresh[c.careerType] = c.name to r.value
                    filled++
                }
            }
            if (filled > 0) log("💼 目录补全：另把 $filled 个职业的岗位读进缓存，界面目录共 ${fresh.size + cached.count { it.key !in fresh }} 个职业")
        }
        if (fresh.isNotEmpty()) runCatching {
            // 结构对比：读到的岗位名/subEvent 与缓存不一致 → 记一笔（缓存随即被新读数覆盖）
            fresh.forEach { (t, v) ->
                val old = cached[t]?.second?.map { it.subEventType to it.name }?.toSet()
                if (old != null && old != v.second.map { it.subEventType to it.name }.toSet()) log("💼 「${v.first}」岗位结构与缓存不一致，已按新读数更新")
            }
            // 按职业合并进持久缓存：本轮读到的覆盖，没读到的保留旧的；元数据保留
            val merged = LinkedHashMap(cached); merged.putAll(fresh)
            val root = runCatching { JSONObject(cache.load()) }.getOrNull() ?: JSONObject()
            val enc = JSONObject(JobCache.encode(merged, clock()))
            root.put("at", enc.get("at")).put("careers", enc.get("careers"))
            cache.save(root.toString())
        }
        return out to sources
    }

    /** 缓存里工分/小时最高的职业（只看可做的档）。 */
    private fun bestCached(pool: List<CareerInfo>, cached: Map<Long, Pair<String, List<SceneOption>>>): Long? =
        pool.mapNotNull { c ->
            val best = cached[c.careerType]?.second?.filter { it.canDo }?.map { SceneEconomics.parseJob(it, c.careerType) }
                ?.maxOfOrNull { j -> if (j.durationMin > 0) j.points * 60.0 / j.durationMin else 0.0 } ?: return@mapNotNull null
            c.careerType to best
        }.maxByOrNull { it.second }?.first

    /**
     * 目录不报金币时，用结算明细里同一档位的**历史实收均值**补上（定价读数 ≥1 个样本，开关 learn_rewards_on）。
     * 这样打工的金币收益来自服务器真实发放，而不是 0 或拍脑袋的估值。
     * 先读 w2c 键（2.1.7 起按「职业+时长」记账：岗位编号会随职业升级漂移、名字也可能变，
     * 职业与时长档位是游戏的固定结构），编号键 w2 兜底（2.1.4 起的满档还原口径）；
     * 旧 w 键是折后原值的混合均值，不再读。
     */
    private fun withLearned(j: JobInfo): JobInfo {
        if (j.incomeKnown || !config.learnRewardsOn) return j
        val g = learnedGold("w2c${j.careerType}t${j.durationMin}") ?: learnedGold("w2${j.subEventType}") ?: return j
        if (g <= 0) return j
        return j.copy(income = g.toInt(), incomeKnown = true, incomeLearned = true)
    }
}
