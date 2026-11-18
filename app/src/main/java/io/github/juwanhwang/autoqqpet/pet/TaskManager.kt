package io.github.juwanhwang.autoqqpet.pet

import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.net.Proto

/**
 * 主任务的**手动入口**（调试广播 learn/work/adventure/settle/recall/hirework…）。
 * 自动循环不走这里（AutoRunner 自己按规划器的决策开任务），但两边共用 [gateAndReport] 与服务层，口径一致。
 */
class TaskManager(private val service: QQPetService) {

    companion object {
        private const val TAG = "TaskManager"
        /** 服务端“好友无法继续被雇佣”的文案关键字：命中就改为自己开工（不重发同一包）。 */
        private const val HIRE_TIRED = "无法继续被雇佣"

        /**
         * 召回时机（纯函数，AutoRunner 也用）：**不看服务器 recallable**（被雇召回实测缺省也成功）。
         * best_split：进度到 [minProgress]（官方分成表 71%）；immediate：立即。“该不该召回（是不是被雇）”由调用方判。
         */
        fun shouldRecall(st: StoryStatus, mode: String, minProgress: Double = StoryStatus.RECALL_AT_PROGRESS): Boolean {
            if (st.remainingSeconds <= 0) return false
            return mode == "immediate" || st.progress >= minProgress
        }

        /**
         * 行为合规链：GetPageRules(0x96a4) → 门禁 → ReportEvent(0x96a6) → 再开任务（与真机客户端顺序一致）。
         * strict=true（学工）：服务器下发了路径却不含本事件 → 不开；paths 为空（请求形状/版本差异）不拦，免得全停摆。
         * strict=false（冒险）：只记录不拦（冒险事件编号未逐条实测，避免误拦自己的功能）。
         */
        fun gateAndReport(service: QQPetService, eventType: Long, strict: Boolean, log: (String) -> Unit = { PLog.d(TAG, it) }): Boolean {
            val rules = PetResult.read { service.story.pageRules(Proto.PAGE_OUTDOOR) }.getOrNull()
                ?: run { log("🚦 页面规则读取失败，放行"); return true }
            val path = rules.paths.firstOrNull { it.first == Proto.PAGE_OUTDOOR && it.second == eventType }
            if (path == null) {
                if (strict && rules.paths.isNotEmpty()) {
                    log("🚦 服务器已下发 ${rules.paths.size} 条路径但无事件 $eventType（服务端剩余=${rules.serverCount ?: "未知"}）→ 不开新任务")
                    return false
                }
                return true
            }
            PetResult.write { service.story.reportEvent(path.first, path.second, path.third) }
            return true
        }
    }

    fun currentTask(): StoryStatus? = PetResult.read { service.story.status() }.getOrNull()?.takeIf { it.storyId.isNotEmpty() }

    /** 只有“还剩时间”才算占用：state=0 残留直接开新任务能成功（抓包实测 2026-10-14），不再顺手结算残留。 */
    private fun busy(): Boolean {
        val st = currentTask() ?: return false
        if (st.remainingSeconds > 0) { PLog.i(TAG, "任务 '${st.storyId}' 还剩 ${st.remainingSeconds}s，占用中"); return true }
        return false
    }

    private fun isCareerGate(t: PetResult<*>): Boolean =
        (t as? PetResult.Rejected)?.let { it.code in Proto.CAREER_GATE_CODES || it.message.contains("未达到该职业参与要求") } == true

    /** 开一节课：canDo 中时长最短者。返回 storyId（空=失败）。 */
    fun learn(): String {
        service.ensurePetId()
        if (busy() || !gateAndReport(service, Proto.SCENE_SCHOOL, strict = true)) return ""
        val stage = service.story.schoolStage()
        val course = service.story.courses(stage).filter { it.canDo }.minByOrNull { minutes(it.duration) }
            ?: run { PLog.w(TAG, "无可用课程"); return "" }
        val sid = service.story.startSchool(course).storyId
        PLog.i(TAG, "🎓 开课「${course.name}」(${course.duration}) → storyId=$sid")
        if (sid.isNotEmpty()) PetResult.write { service.story.encourage(sid) }
        return sid
    }

    /** 开一个工：只遍历已解锁职业，取本次读数里工分/小时最高的档。[hired]=(uin,petId)。 */
    fun work(hired: Pair<String, String>? = null, careerType: Long = 0): String {
        service.ensurePetId()
        if (busy() || !gateAndReport(service, Proto.SCENE_WORK, strict = true)) return ""
        val all = PetResult.read { service.story.careers() }.getOrNull().orEmpty().filter { it.unlocked }
        val careers = if (careerType > 0) all.filter { it.careerType == careerType }.ifEmpty { all } else all
        for (career in careers) {
            val jr = PetResult.read { service.story.jobs(career.careerType, hired?.second.orEmpty()) }
            val jobs = jr.getOrNull()?.filter { it.canDo }
            if (jobs == null) {
                PLog.i(TAG, if (isCareerGate(jr)) "💼 「${career.name}」未达参与要求，跳过" else "读岗位失败 ${career.name}: ${jr.describe()}")
                continue
            }
            val job = jobs.maxByOrNull { SceneEconomics.parseJob(it, career.careerType).pointsPerHour } ?: continue
            var r = PetResult.write { service.story.startWork(job, hired) }
            if (hired != null && r is PetResult.Rejected && r.message.contains(HIRE_TIRED)) {
                PLog.w(TAG, "🤝 好友 ${hired.first} 已疲劳，改为自己开工")
                r = PetResult.write { service.story.startWork(job) }
            }
            val sid = r.getOrNull()?.storyId.orEmpty()
            if (sid.isEmpty()) { PLog.w(TAG, "开工失败 ${career.name}/${job.name}: ${r.describe()}"); continue }
            PLog.i(TAG, "💼 ${if (hired != null) "雇佣${hired.first}开工" else "开工"}「${job.name}」(${job.duration}) → storyId=$sid")
            PetResult.write { service.story.encourage(sid) }
            return sid
        }
        PLog.w(TAG, "没有可开工的岗位（已解锁职业 ${careers.size} 个）")
        return ""
    }

    /** 冒险：canDo 且最短（可按名字指定），可附带雇佣。 */
    fun adventure(preferName: String = "", hired: Pair<String, String>? = null): String {
        service.ensurePetId()
        if (busy()) return ""
        gateAndReport(service, Proto.SCENE_ADVENTURE, strict = false)
        val options = PetResult.read { service.story.adventures(hired?.second.orEmpty()) }.getOrNull().orEmpty()
        val option = if (preferName.isNotEmpty()) options.firstOrNull { it.canDo && it.name == preferName }
        else options.filter { it.canDo && it.name.isNotEmpty() }.minByOrNull { minutes(it.duration) }
        if (option == null) {
            PLog.w(TAG, "无可用冒险" + (options.firstOrNull { it.unavailableReason.isNotEmpty() }?.let { "（${it.unavailableReason}）" } ?: ""))
            return ""
        }
        val sid = service.story.startAdventure(option, hired).storyId
        PLog.i(TAG, "🗺️ 冒险「${option.name}」(${option.duration}) → storyId=$sid")
        return sid
    }

    /** 手动结算（到期领奖 action=1000；force 不管剩余时间）。 */
    fun settle(force: Boolean = false): String {
        val st = currentTask() ?: return "无进行中任务"
        if (st.remainingSeconds > 0 && !force) return "未到期（剩 ${st.remainingSeconds}s，可用 recall 提前结束）"
        val r = PetResult.write { service.story.settle(st.storyId) }
        PLog.i(TAG, "🧾 结算 ${st.storyId} → ${r.describe()}")
        return r.describe()
    }

    /** 手动召回（0x9760 action=6000）：不要求 recallable；回包带到账金币就不复读状态。 */
    fun recall(mode: String = "best_split"): String {
        val st = currentTask() ?: return "无进行中任务"
        if (!shouldRecall(st, mode)) return "wait(进度=${"%.1f".format(st.progress * 100)}%，未到召回时机)"
        val r = PetResult.write { service.story.recall(st.storyId) }
        val o = r.getOrNull()
        if (o != null && !o.empty) { PLog.i(TAG, "🔄 召回 ${st.storyId} → $o"); return "ok gold=${o.gold}" }
        Thread.sleep(1200)
        val after = currentTask()
        val ok = r.ok && (after == null || after.storyId != st.storyId || after.remainingSeconds <= 0)
        PLog.i(TAG, "🔄 召回 ${st.storyId}(策略=$mode) → ${r.describe()}；复查=${after?.storyId ?: "无"}")
        return if (ok) "ok" else "unverified"
    }

    private fun minutes(text: String): Long = SceneEconomics.durationMinutes(text).toLong().takeIf { it > 0 } ?: Long.MAX_VALUE
}
