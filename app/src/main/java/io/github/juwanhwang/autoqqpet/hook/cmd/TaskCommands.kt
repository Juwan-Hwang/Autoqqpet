package io.github.juwanhwang.autoqqpet.hook.cmd

import io.github.juwanhwang.autoqqpet.hook.HostRuntime
import io.github.juwanhwang.autoqqpet.hook.AdSweepHost
import io.github.juwanhwang.autoqqpet.net.Proto
import io.github.juwanhwang.autoqqpet.optimizer.AdHistorySweeper
import io.github.juwanhwang.autoqqpet.optimizer.Alerts
import io.github.juwanhwang.autoqqpet.optimizer.SideTasks
import io.github.juwanhwang.autoqqpet.pet.CareManager
import io.github.juwanhwang.autoqqpet.pet.HirePolicy
import io.github.juwanhwang.autoqqpet.pet.JobCatalog
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.SceneEconomics
import io.github.juwanhwang.autoqqpet.pet.TaskManager
import io.github.juwanhwang.autoqqpet.store.SettingKeys

/** 主任务 / 照顾 / 目录 / 日常杂事的手动入口。 */
object TaskCommands {

    private fun orFail(sid: String) = if (sid.isEmpty()) "失败（见上一条原因）" else "storyId=$sid"

    fun register(r: CommandRouter) = r.register(
        /**
         * 批量补领历史结算广告奖励（正式包也注册）。默认**正常播放广告后上报**；
         * `--es background 1` 跳过播放直接上报完成 = 伪造广告曝光，可能触发账号风控/封禁，界面已注明风险，仅显式传参才走。
         */
        CommandSpec("adall", Lane.CMD, "批量补领历史结算广告 --ei max 500（0=不限）--es kind work|all --es background 1（默认 0=正常播放；1=跳过播放，伪造曝光有风控风险）") { c ->
            val s = c.service() ?: return@CommandSpec
            val ctx = HostRuntime.appContext() ?: run { c.log("📺 拿不到 context"); return@CommandSpec }
            if (AdSweepHost.current().running) { c.log("📺 已有补领在跑"); return@CommandSpec }
            val max = c.int("max", 500)
            val maxScan = if (max <= 0) 0 else max.coerceIn(10, 2000)
            val workOnly = c.str("kind", "work") != "all"
            val background = c.str("background") == "1"
            AdSweepHost.clearCancel()
            val out = AdHistorySweeper(s, ctx, { c.log(it) }, { AdSweepHost.publish(it) })
                .sweep(maxScan, workOnly, background, cancelled = { AdSweepHost.isCancelRequested() })
            c.log("📺 adall " + out.describe())
        },
        CommandSpec("adsweepstop", Lane.CMD, "取消正在跑的历史广告补领") { c ->
            AdSweepHost.requestCancel()
            c.log("📺 已请求取消，当前这条广告处理完就停")
        },
        CommandSpec("adsweepstatus", Lane.CMD, "查历史广告补领进度") { _ ->
            AdSweepHost.publish(AdSweepHost.current())
        },
        CommandSpec("read", Lane.CMD, "只读自检：资料/数值/库存/商城") { c ->
            val s = c.service() ?: return@CommandSpec
            val p = s.readOwnProfile()
            val v = s.readValues()
            c.log("✅ 只读探针 uin=${s.selfUin()}：宠物=${p.petName} | 心情=${v.feel} 体力=${v.hunger} 清洁=${v.clean} 综合=${v.total} 金币=${v.gold}")
            c.log("🍪 食物: " + s.inventory.foods().joinToString { "${it.name}(id=${it.foodId})=${it.balance}" })
            c.log("🧼 洗护商城: " + s.inventory.bathItems().joinToString { "${it.name}(id=${it.itemId}) 价=${it.goldPrice}" })
            c.log("🧴 洗护库存: ${s.inventory.bathInventory()}")
            HostRuntime.view.noteValues(v)
        },
        CommandSpec("scenes", Lane.CMD, "场景只读校准：状态/学园/职业/冒险/好友池") { c ->
            val s = c.service() ?: return@CommandSpec
            c.log("📍 " + PetResult.read { s.story.status() }.describe())
            c.log("🎓 " + PetResult.read { val st = s.story.schoolStage(); "阶段=$st " + s.story.courses(st).joinToString(";") { "${it.name}[sub=${it.subEventType},can=${it.canDo},${it.duration}]" } }.describe())
            c.log("💼 " + PetResult.read { s.story.careers().joinToString(";") { "${it.name}(type=${it.careerType},status=${it.statusCode})" } }.describe())
            c.log("🗺️ " + PetResult.read { s.story.adventures().joinToString(";") { "${it.name}[can=${it.canDo},${it.duration}${if (it.unavailableReason.isNotEmpty()) ",${it.unavailableReason}" else ""}]" } }.describe())
            c.log("⚔️ " + PetResult.read { s.friend.pool().take(5).joinToString(";") { "${it.uin}(power=${it.power})" } }.describe())
        },
        CommandSpec("status", Lane.CMD, "当前任务状态") { c ->
            val s = c.service() ?: return@CommandSpec
            val st = TaskManager(s).currentTask()
            c.log(if (st == null) "ℹ️ 无进行中主任务" else
                "ℹ️ 进行中: '${st.storyId}' state=${st.stateCode} 剩=${st.remainingSeconds}s 总=${st.durationSeconds}s 可召回=${st.recallable} 进度=${"%.0f".format(st.progress * 100)}% " +
                    "本模块开的=${HostRuntime.daily?.isMine(st.storyId)}")
        },
        CommandSpec("progress", Lane.CMD, "读当前任务的进度叙事") { c ->
            val s = c.service() ?: return@CommandSpec
            val st = TaskManager(s).currentTask() ?: run { c.log("ℹ️ 无进行中任务"); return@CommandSpec }
            val r = PetResult.read { s.story.progress(st.storyId) }
            c.log("📜 ${st.storyId} 叙事：" + (r.getOrNull()?.lines?.takeLast(6)?.joinToString(" ⋅ ") ?: r.describe()))
        },
        CommandSpec("settleview", Lane.CMD, "读结算明细--es sid <storyId>，空=上一条台账") { c ->
            val s = c.service() ?: return@CommandSpec
            val sid = c.str("sid").ifBlank { HostRuntime.daily?.ledger()?.lastOrNull()?.split('|')?.getOrNull(3).orEmpty() }
            if (sid.isEmpty()) { c.log("⚠️ 没有 storyId"); return@CommandSpec }
            val r = PetResult.read { s.story.settlementView(sid, System.currentTimeMillis()) }
            r.getOrNull()?.let { c.log("🧾 $sid 奖励：${it.rewards.joinToString(" ") { w -> "${w.name}+${w.amount}" }.ifBlank { "（未认出）" }} ｜叙事 ${it.narrative.take(3)}") }
                ?: c.log("🧾 $sid → ${r.describe()}")
        },
        CommandSpec("learn", Lane.TICK, "手动开一节课") { c -> c.service()?.let { c.log("🎓 开课 → " + orFail(TaskManager(it).learn())) } },
        CommandSpec("work", Lane.TICK, "手动开工（按打工方向）") { c ->
            val s = c.service() ?: return@CommandSpec
            val career = HostRuntime.settings()?.let { SettingKeys.long(it, SettingKeys.WORK_CAREER) } ?: 0L
            c.log("💼 开工 → " + orFail(TaskManager(s).work(careerType = career)))
        },
        CommandSpec("adventure", Lane.TICK, "手动冒险（--es name 指定）") { c -> c.service()?.let { c.log("🧭 冒险 → " + orFail(TaskManager(it).adventure(c.str("name")))) } },
        CommandSpec("settle", Lane.TICK, "手动结算（op=force 不管剩余）") { c -> c.service()?.let { c.log(TaskManager(it).settle(force = c.op == "force")) } },
        CommandSpec("recall", Lane.TICK, "手动召回（--es mode best_split|immediate；不看 recallable）；界面“立即召回”发 mode=immediate") { c ->
            c.service()?.let {
                val r = TaskManager(it).recall(c.str("mode", "best_split"))
                c.log("🔄 召回结果: $r")
                // 界面“立即召回”走这里：成功就撤提醒、记账并推一帧，主页当场变
                if (r.startsWith("ok")) {
                    HostRuntime.daily?.increment("recall")
                    HostRuntime.alerts.clear(io.github.juwanhwang.autoqqpet.optimizer.Alerts.RECALL)
                    // 推帧前先把 runner 视图里的旧趟切走，不然这一帧仍按旧趟的开始+时长推倒计时
                    HostRuntime.runner?.view?.noteRecalled("🔄 已召回，重新出发")
                }
                HostRuntime.sendStatus(if (r.startsWith("ok")) "已召回" else HostRuntime.lastState)
            }
        },
        CommandSpec("hirework", Lane.TICK, "雇佣好友开工（--es uin/pet，留空则用挑一个可雇的）") { c ->
            val s = c.service() ?: return@CommandSpec
            val pair = if (c.uin.isNotEmpty()) c.uin to c.pet else HirePolicy.pickHireable(s, HostRuntime.policy, s.selfUin(), log = c::log)
            if (pair == null) c.log("🤝 没有可雇佣的好友") else {
                val sid = TaskManager(s).work(pair)
                if (sid.isNotEmpty()) HostRuntime.daily?.noteHire()
                c.log("🤝 雇佣开工 ${pair.first} → ${orFail(sid)}")
            }
        },
        CommandSpec("hireadv", Lane.TICK, "雇佣好友冒险") { c ->
            val s = c.service() ?: return@CommandSpec
            val pair = if (c.uin.isNotEmpty()) c.uin to c.pet else HirePolicy.pickHireable(s, HostRuntime.policy, s.selfUin(), log = c::log)
            if (pair == null) c.log("🤝 没有可雇佣的好友") else {
                val sid = TaskManager(s).adventure("", pair)
                if (sid.isNotEmpty()) HostRuntime.daily?.noteHire()
                c.log("🤝 雇佣冒险 ${pair.first} → ${orFail(sid)}")
            }
        },
        CommandSpec("hirecheck", Lane.CMD, "查好友能否被雇--es uin --es pet") { c ->
            val s = c.service() ?: return@CommandSpec
            if (c.uin.isEmpty() || c.pet.isEmpty()) { c.log("需要 --es uin 与 --es pet"); return@CommandSpec }
            val r = PetResult.read { s.friend.status(c.uin, c.pet) }
            c.log("🤝 ${c.uin} → " + (r.getOrNull()?.let { "可雇=${it.hireable} 每日上限=${it.hireDailyCap} 文案=${it.notices}" } ?: r.describe()))
        },
        CommandSpec("joblist", Lane.CMD, "枚举已解锁职业×档位并回传界面（同时回传课程与冒险）") { c ->
            val s = c.service() ?: return@CommandSpec
            val careers = PetResult.read { s.story.careers() }.getOrNull() ?: run { c.log("读职业列表失败"); return@CommandSpec }
            val catalog = ArrayList<JobCatalog.Tier>()
            c.log("🧭 职业 ${careers.size} 个，其中可做 ${careers.count { it.unlocked }} 个")
            careers.filter { it.unlocked }.forEach { cr ->
                val jobs = PetResult.read { s.story.jobs(cr.careerType) }.getOrNull() ?: run { c.log("   💼「${cr.name}」读岗位失败"); return@forEach }
                jobs.forEach { j ->
                    val info = SceneEconomics.parseJob(j, cr.careerType)
                    catalog += JobCatalog.of(info, cr.name, j.duration)
                    c.log("      · ${cr.name}/${j.name} ｜ ${j.duration} ｜ +${info.points}点 ≈ ${"%.1f".format(info.pointsPerHour)} 点/小时｜ ${if (j.canDo) "可做" else "不可做"}")
                }
            }
            JobCatalog.verdict(catalog)?.let { c.log("🧭 本次读数性价比：$it") }
            // 全量读到的也灌进 QQ 侧岗位缓存（R3：循环里某职业读失败时回落用）
            runCatching { HostRuntime.seedJobCache(JobCatalog.encode(catalog)) }
            val stage = PetResult.read { s.story.schoolStage() }.getOrNull() ?: 0L
            val courses = PetResult.read { s.story.courses(stage) }.getOrNull().orEmpty().map { JobCatalog.ofCourse(SceneEconomics.parseCourse(it), it.duration, stage) }
            val advs = PetResult.read { s.story.adventures() }.getOrNull().orEmpty().map { JobCatalog.ofAdventure(SceneEconomics.parseAdventure(it), it.duration) }
            HostRuntime.sendCatalog(catalog, courses, advs)
        },
        CommandSpec("rules", Lane.CMD, "页面规则 --ei page（默认 6000）") { c ->
            val s = c.service() ?: return@CommandSpec
            val page = c.int("page", Proto.PAGE_OUTDOOR.toInt()).toLong()
            val r = PetResult.read { s.story.pageRules(page) }
            r.getOrNull()?.let { c.log("🚦 page=$page 服务端剩余=${it.serverCount ?: "未知"} 路径${it.paths.size}条: " + it.paths.take(12).joinToString("; ") { p -> "${p.first}/${p.second}/${p.third}" }) }
                ?: c.log("🚦 ${r.describe()}")
        },
        CommandSpec("plan", Lane.CMD, "dry-run：按循环同一口径算一次决策（不执行）") { c ->
            val rn = HostRuntime.runner ?: run { c.log("runner 未建"); return@CommandSpec }
            val (d, snap) = rn.dryRun()
            c.log("🧮 决策(dry-run): ${d.kind} ${d.detail} —— ${d.reason}")
            if (d.trace.isNotEmpty()) c.log("   依据: ${d.trace}")
            c.log("   快照: 金币${snap.gold} 体力${snap.stamina} 清洁${snap.clean} 课${snap.courses.size} 岗${snap.jobs.size} 冒险${snap.adventures.size} 被削档${snap.penalizedSubs.size}")
        },
        CommandSpec("care", Lane.TICK, "照顾：默认补到 hunger/clean 目标（--ei hunger/clean，默认 80）；op=reset 清连拒；op=status") { c ->
            val s = c.service() ?: return@CommandSpec
            val cm = CareManager(s, HostRuntime.daily, true, c::log)
            when (c.op) {
                "reset" -> { cm.reset(); HostRuntime.alerts.clear(Alerts.CARE_BLOCKED); c.log("🧽 已清除照顾连拒计数") }
                "status" -> c.log("🧽 照顾被拒：喂食 ${HostRuntime.daily?.count(CareManager.KEY_FEED_FAIL)} / 洗澡 ${HostRuntime.daily?.count(CareManager.KEY_BATH_FAIL)}")
                else -> cm.careUntil(c.int("hunger", 80).toDouble().takeIf { it > 0 } ?: 80.0, c.int("clean", 80).toDouble().takeIf { it > 0 } ?: 80.0)
            }
        },
        CommandSpec("feed", Lane.TICK, "喂一次（验证体力差）") { c ->
            val s = c.service() ?: return@CommandSpec
            CareManager(s, null, false, c::log).feed(s.profile.values(withGold = false).hunger)
        },
        CommandSpec("bath", Lane.TICK, "洗一次（验证清洁差）") { c ->
            val s = c.service() ?: return@CommandSpec
            CareManager(s, null, false, c::log).bath(s.profile.values(withGold = false).clean)
        },
        CommandSpec("buyfood", Lane.CMD, "买饼干 --ei n（默认 5）") { c -> c.service()?.let { c.log("💰 买饼干: " + PetResult.write { it.inventory.buyFood(if (c.n > 0) c.n else 5) }.describe()) } },
        CommandSpec("buybath", Lane.CMD, "买洗护品 --es item 1|2 --ei n") { c ->
            val s = c.service() ?: return@CommandSpec
            c.log("🧼 买洗护品 → " + PetResult.write { s.inventory.buyBathItem(c.str("item", "1"), if (c.n > 0) c.n else 10) }.describe())
            c.log("   库存: " + PetResult.read { s.inventory.bathInventory() }.describe())
        },
        CommandSpec("mail", Lane.TICK, "信箱：读列表，开着一键领取就领") { c ->
            val s = c.service() ?: return@CommandSpec
            val rn = HostRuntime.runner ?: return@CommandSpec
            val r = SideTasks(s, HostRuntime.daily ?: return@CommandSpec, null, rn.config, HostRuntime.view, HostRuntime.alerts, c::log).mail()
            c.log("📮 信箱：${r ?: "无可领（或读取失败，见上）"}"); HostRuntime.sendStatus(HostRuntime.lastState)
        },
        CommandSpec("mailclaim", Lane.TICK, "信箱一键领取（136610=没东西可领）") { c ->
            val s = c.service() ?: return@CommandSpec
            c.log("📮 一键领取 → " + PetResult.write { s.mail.batchClaim().size }.describe())
        },
        CommandSpec("checkin", Lane.TICK, "领一次签到食物（空回包=今天已领；不计今日自动次数）") { c ->
            val s = c.service() ?: return@CommandSpec
            val r = PetResult.write { s.inventory.claimCheckinFood() }
            c.log("📅 签到食物 → " + if (r is PetResult.Ok) (r.value?.let { "成功，饼干现有 $it" } ?: "今天已领（空回包）") else r.describe())
        },
        CommandSpec("attrs", Lane.CMD, "三属性与战力（--es pet 空=自己）") { c ->
            val s = c.service() ?: return@CommandSpec
            val a = PetResult.read { s.profile.attributes(c.pet.ifEmpty { s.ensurePetId() }) }
            a.getOrNull()?.let { if (c.pet.isEmpty()) { HostRuntime.view.attrs = it; HostRuntime.view.attrsAt = System.currentTimeMillis() } }
            c.log("💪 " + a.getOrNull()?.let { "力量 ${it.strength} 智力 ${it.intel} 魅力 ${it.charm} 战力 ${it.power}" }.orEmpty().ifEmpty { a.describe() })
        },
        CommandSpec("exp", Lane.CMD, "今日经验") { c ->
            val s = c.service() ?: return@CommandSpec
            val e = PetResult.read { s.profile.dailyExp() }
            e.getOrNull()?.let { x ->
                HostRuntime.view.exp = x; HostRuntime.view.expAt = System.currentTimeMillis()
                c.log("✨ 今日经验 ${x.today}/${x.cap}（倍率 ${"%.2f".format(x.multiplier)}）自己护理满=${x.selfCareFull}")
                x.tasks.forEach { t -> c.log("   · ${t.name} ${t.done}/${t.cap} 得 ${t.gotExp}${if (t.full) " ✔" else ""}") }
            } ?: c.log("✨ " + e.describe())
        },
        CommandSpec("notices", Lane.CMD, "拉通知，列“雇佣了”") { c ->
            val s = c.service() ?: return@CommandSpec
            val r = PetResult.read { s.friend.hiredNotices() }
            r.getOrNull()?.let { HostRuntime.view.hiredNotices = it; HostRuntime.view.hiredNoticesAt = System.currentTimeMillis() }
            c.log("📬 被雇佣通知 → " + r.describe())
        },
    )
}
