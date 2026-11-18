package io.github.juwanhwang.autoqqpet.pet

/**
 * 打工目录的跨进程搬运与用户选择口径。
 *
 * 【为什么要搬运】职业×档位这份目录只有在 **QQ 进程**里才读得到（要在它的进程内发 OIDB），
 * 而下拉框跑在模块自己的 UI 进程；两边连数据目录都不共用（同 auto_on 那个坑）。
 * 所以约定：QQ 进程读到了就编成一行串，用 [ACTION] 广播回来，UI 进程存进 prefs 再渲染下拉框。
 *
 * 【编码格式】最朴素的 `字段|字段|…` ，记录之间用 `,`。岗位/职业名都是中文短语，正常不含分隔符，
 * 但一律洗掉这三个字符，免得将来 QQ 改名字把整行结构打断（宁可名字少个符号，也不要界面读不出东西）。
 */
object JobCatalog {

    /** QQ 进程 → UI 进程：打工目录回传。 */
    const val ACTION = "io.github.juwanhwang.autoqqpet.JOBS"

    // UI 进程 prefs（ModulePrefs.NAME）里的键
    const val KEY_CATALOG = "work_catalog"
    const val KEY_CATALOG_AT = "work_catalog_at"
    const val KEY_CAREER = "work_career"   // 0 = 自动（哪里划算去哪里）
    // 打工时长档与具体课程不存全局键：要钉哪一档是排班步骤自己的事（见 pet/Schedule.kt）
    /** 课程目录（排班要能选到具体哪门课，所以与岗位一样得跨过进程送一份） */
    const val KEY_COURSES = "course_catalog"
    const val KEY_COURSES_AT = "course_catalog_at"
    /** 冒险目录：排班里“冒险”那一步也要能选具体哪个档（附近走走 / 远足 …），不能只给“自动” */
    const val KEY_ADVENTURES = "adventure_catalog"
    const val KEY_ADVENTURES_AT = "adventure_catalog_at"
    // 时间档位不入库也不入界面：每轮拿服务器的实时读数现算（见 Tier.pointsPerHour 的注释）。

    /** 一个可选项 = 某职业下的某个时间档位。 */
    data class Tier(
        val careerType: Long,
        val careerName: String,
        val subEventType: Long,
        val jobName: String,
        /** 服务器给的时长原文，如“45分钟”，用于显示。 */
        val duration: String,
        val minutes: Int,
        /** 目录里唯一可比的收益量（f15“+N点”工分）；金币目录不回。 */
        val points: Int,
        val stamina: Int,
        val clean: Int,
        val canDo: Boolean,
    ) {
        /** 工分/小时：横比四个档位的口径。【只用本次读数算】——宠物疲劳时服务端下发的数字会整体偏低，
         * 不能拿某一天的结果当固定结论（真机实测：同样四档的相对性价比会随疲劳/体力变化）。 */
        val pointsPerHour: Double get() = if (minutes <= 0) 0.0 else points * 60.0 / minutes
    
        val pphText: String get() = "%.1f".format(pointsPerHour)
    
        /** 下拉框里的一行。数字全部来自本次服务器读数，带“当前”字样提醒它不是定数。 */
        val label: String
            get() = "$careerName · $jobName（$duration，当前 +${points}点 ≈ $pphText 点/小时" +
                (if (canDo) "" else "，当前不可做") + "）"
    }
    
    /**
     * 一门课的可选项（排班编辑器用）。
     * 与 [Tier] 同构：字段都是【本次读数】，只用来让人认出来“是哪门课、多长、大概多少收益”，不参与决策。
     */
    data class CourseTier(
        val subEventType: Long,
        val name: String,
        val duration: String,
        val minutes: Int,
        /** 属性类别与单课加成（中文，仅显示）。 */
        val attrLabel: String,
        val attrGain: Int,
        val fee: Int,
        val stamina: Int,
        val clean: Int,
        val canDo: Boolean,
        /** 所属学园阶段（0~4）：课程目录按阶段读，跨阶段选课必须记住它来自哪个阶段。 */
        val stage: Long = -1L,
    ) {
        /** 一行：`巨星自我修养（45分钟，+30魅力 学费141）`；不可做的标出来，别让人选了才发现做不了。 */
        val label: String
            get() = "$name（$duration，+$attrGain$attrLabel 学费$fee" +
                (if (canDo) "" else "，当前不可做") + "）"
    }

    /** 课程 → 一行串（与 encode 同一套分隔符清洗）。 */
    fun encodeCourses(items: List<CourseTier>): String =
        items.joinToString(",") {
            listOf(
                it.subEventType, clean(it.name), clean(it.duration), it.minutes, clean(it.attrLabel),
                it.attrGain, it.fee, it.stamina, it.clean, if (it.canDo) 1 else 0, it.stage,
            ).joinToString("|")
        }

    /** UI 侧读回课程；格式不对的记录直接丢。 */
    fun decodeCourses(text: String): List<CourseTier> {
        if (text.isBlank()) return emptyList()
        return text.split(',').mapNotNull { rec ->
            val f = rec.split('|')
            if (f.size < 10) return@mapNotNull null
            CourseTier(
                subEventType = f[0].toLongOrNull() ?: return@mapNotNull null,
                name = f[1], duration = f[2], minutes = f[3].toIntOrNull() ?: 0,
                attrLabel = f[4], attrGain = f[5].toIntOrNull() ?: 0, fee = f[6].toIntOrNull() ?: 0,
                stamina = f[7].toIntOrNull() ?: 0, clean = f[8].toIntOrNull() ?: 0, canDo = f[9] == "1",
                stage = f.getOrNull(10)?.toLongOrNull() ?: -1L,
            )
        }
    }

    /** 从服务器课程条目拼一条课程记录（数字与决策同一口径，都是本次读数）。 */
    fun ofCourse(c: CourseInfo, durationText: String, stage: Long = -1L): CourseTier = CourseTier(
        subEventType = c.subEventType,
        name = c.name,
        duration = durationText.ifEmpty { "${c.durationMin}分钟" },
        minutes = c.durationMin,
        attrLabel = when (c.attr) {
            Attr.STRENGTH -> "力量"; Attr.INTEL -> "智力"; Attr.CHARM -> "魅力"; Attr.RANDOM -> "随机"; else -> "属性"
        },
        attrGain = c.attrGain,
        fee = c.fee, stamina = c.staminaCost, clean = c.cleanCost, canDo = c.canDo, stage = stage,
    )

    /** 一个冒险选项（只到界面用来“按名字选人”，数字仍是本次读数）。 */
    data class AdventureTier(
        val subEventType: Long,
        val name: String,
        val duration: String,
        val minutes: Int,
        val canDo: Boolean,
    ) {
        val label: String
            get() = "$name（${duration.ifBlank { "${minutes}分钟" }}" + (if (canDo) "" else "，当前不可做") + "）"
    }

    fun encodeAdventures(items: List<AdventureTier>): String =
        items.joinToString(",") {
            listOf(it.subEventType, clean(it.name), clean(it.duration), it.minutes, if (it.canDo) 1 else 0).joinToString("|")
        }

    fun decodeAdventures(text: String): List<AdventureTier> {
        if (text.isBlank()) return emptyList()
        return text.split(',').mapNotNull { rec ->
            val f = rec.split('|')
            if (f.size < 5) return@mapNotNull null
            AdventureTier(
                subEventType = f[0].toLongOrNull() ?: return@mapNotNull null,
                name = f[1], duration = f[2], minutes = f[3].toIntOrNull() ?: 0, canDo = f[4] == "1",
            )
        }
    }

    /** 从服务器冒险条目拼一条记录。 */
    fun ofAdventure(a: AdventureInfo, durationText: String): AdventureTier = AdventureTier(
        subEventType = a.subEventType,
        name = a.name,
        duration = durationText.ifEmpty { "${a.durationMin}分钟" },
        minutes = a.durationMin,
        canDo = a.canDo,
    )

    /** 下拉框内容：目录里出现过的职业（按服务器给的顺序，只包含该账号当前解得开的）。 */
    fun careers(tiers: List<Tier>): List<Pair<Long, String>> {
        val seen = LinkedHashMap<Long, String>()
        tiers.forEach { if (it.careerType > 0 && !seen.containsKey(it.careerType)) seen[it.careerType] = it.careerName }
        return seen.entries.map { it.key to it.value }
    }

    /** 本次读数下性价比最高的【一组】档位。
     * 之所以是一组而不是一个：真机实测 8 个职业的四个档位数字完全相同（拿任何一个算出来都一样），
     * 这种情况给个“最佳职业/最佳档”是误导，所以并列时只说“并列”。 */
    data class Best(val tied: List<Tier>) {
        val unique: Boolean get() = tied.size == 1
        val perHour: String get() = tied.first().pphText
    }

    /** 本次读数下【某一职业】性价比最高的那些档（自动档的口径就是它；不可做的不参与）。 */
    fun best(tiers: List<Tier>, careerType: Long = 0): Best? {
        val pool = tiers.filter { it.canDo && (careerType <= 0 || it.careerType == careerType) }
        if (pool.isEmpty()) return null
        val top = pool.maxOf { roundRate(it.pointsPerHour) }
        // 先化成 0.1 精度的整数再比，避开浮点尾数把“完全相等”误判成“略高一点”
        return Best(pool.filter { roundRate(it.pointsPerHour) >= top })
    }

    /** 给日志与界面用的一句话结论（数字均来自本次读数）；并列时不报唯一选项。 */
    fun verdict(tiers: List<Tier>, careerType: Long = 0): String? {
        val b = best(tiers, careerType) ?: return null
        return if (b.unique) {
            val t = b.tied.first()
            "「${t.jobName}」（${t.duration}）≈ ${b.perHour} 点/小时"
        } else {
            "本次读数共 ${b.tied.size} 项并列第一（≈ ${b.perHour} 点/小时，各职业/档位无差别）"
        }
    }

    private fun roundRate(x: Double): Long = Math.round(x * 10)

    /**
     * 当场现算的版本：给一组 (显示名, 分钟, 工分) 就回“唯一最优”或“几项并列”。
     * 给 QQ 进程用：那边的数字就在手上，没必要绕一圈去读界面的缓存。
     */
    fun verdictOf(pairs: List<Triple<String, Int, Int>>): String? {
        val pool = pairs.filter { it.second > 0 }
        if (pool.isEmpty()) return null
        val rates = pool.map { Math.round(it.third * 10.0 / it.second) }
        val top = rates.max()
        val tied = pool.filterIndexed { i, _ -> rates[i] >= top }
        val perHour = "%.1f".format(tied.first().third * 60.0 / tied.first().second)
        return if (tied.size == 1) "「${tied.first().first}」${tied.first().second}min ≈ $perHour 点/小时"
        else "${tied.size} 项并列 ≈ $perHour 点/小时（本次读数无差别）"
    }

    /** QQ 进程侧：把刚读到的目录编成一行串发回 UI 进程。 */
    fun encode(tiers: List<Tier>): String =
        tiers.joinToString(",") {
            listOf(
                it.careerType, clean(it.careerName), it.subEventType, clean(it.jobName),
                clean(it.duration), it.minutes, it.points, it.stamina, it.clean, if (it.canDo) 1 else 0,
            ).joinToString("|")
        }

    /** UI 侧：读回目录（格式不对的记录直接丢，不让半条脏数据毁掉整个下拉框）。 */
    fun decode(text: String): List<Tier> {
        if (text.isBlank()) return emptyList()
        return text.split(',').mapNotNull { rec ->
            val f = rec.split('|')
            if (f.size < 10) return@mapNotNull null
            Tier(
                careerType = f[0].toLongOrNull() ?: return@mapNotNull null,
                careerName = f[1],
                subEventType = f[2].toLongOrNull() ?: return@mapNotNull null,
                jobName = f[3],
                duration = f[4],
                minutes = f[5].toIntOrNull() ?: 0,
                points = f[6].toIntOrNull() ?: 0,
                stamina = f[7].toIntOrNull() ?: 0,
                clean = f[8].toIntOrNull() ?: 0,
                canDo = f[9] == "1",
            )
        }
    }

    /** 从服务器条目拼一条目录记录（收益/消耗数字走 SceneEconomics，与决策同一口径）。 */
    fun of(job: JobInfo, careerName: String, durationText: String): Tier = Tier(
        careerType = job.careerType,
        careerName = careerName,
        subEventType = job.subEventType,
        jobName = job.name,
        duration = durationText.ifEmpty { "${job.durationMin}分钟" },
        minutes = job.durationMin,
        points = job.points,
        stamina = job.staminaCost,
        clean = job.cleanCost,
        canDo = job.canDo,
    )

    /** 目录读数已过期（超过 10 分钟）：疲劳/体力一变，服务器给的数字就变，旧读数只能当参考。 */
    fun isStale(at: Long, now: Long = System.currentTimeMillis()): Boolean = at <= 0L || now - at > 10 * 60_000L

    private fun clean(s: String): String = s.replace('|', ' ').replace(',', ' ').replace('，', ' ')
}
