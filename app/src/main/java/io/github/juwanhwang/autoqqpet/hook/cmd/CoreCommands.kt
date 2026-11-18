package io.github.juwanhwang.autoqqpet.hook.cmd

import io.github.juwanhwang.autoqqpet.BuildConfig
import io.github.juwanhwang.autoqqpet.hook.AlarmScheduler
import io.github.juwanhwang.autoqqpet.hook.HostRuntime
import io.github.juwanhwang.autoqqpet.hook.TickDriver
import io.github.juwanhwang.autoqqpet.optimizer.Alerts
import io.github.juwanhwang.autoqqpet.pet.DailyReport
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.Schedule
import io.github.juwanhwang.autoqqpet.pet.SideStore
import io.github.juwanhwang.autoqqpet.store.SettingKeys

/** 托管控制与设置类命令（UI 主要用这一组；字段契约见 docs/CONTRACT.md §3）。 */
object CoreCommands {

    fun register(r: CommandRouter) = r.register(
        CommandSpec("auto", Lane.CMD, "开启自动托管（--es attr 力量|智力|魅力 可选）并请求跑一轮（经闸门：已在跑/刚跑过就不重复跑）") { c ->
            val attr = c.str("attr").ifBlank { null }
            val wasOn = HostRuntime.autoEnabled
            val sp = HostRuntime.settings()
            val attrChanged = attr != null && sp?.getString(SettingKeys.ATTR.key, null) != attr
            HostRuntime.persist { ed -> ed.putBoolean(SettingKeys.AUTO_ON.key, true); if (attr != null) ed.putString(SettingKeys.ATTR.key, attr) }
            HostRuntime.autoEnabled = true
            // 已经开着且设置没变（看门狗补发的 auto 多半是这种）就不重建 runner：重建会丢掉进程内节流状态
            if (!wasOn || attrChanged || HostRuntime.runner == null) HostRuntime.rebuildRunner()
            c.log("▶️ 自动模式开启${if (wasOn) "（本来就开着）" else ""}")
            // 本来就开着且没改设置（看门狗/界面重复补发）→ 当“补一轮”处理：状态机已排好唤醒就不另起（D2）
            TickDriver.request(if (wasOn && !attrChanged) "auto-resend" else "auto")
        },
        CommandSpec("autooff", Lane.CMD, "关闭自动托管") { c ->
            HostRuntime.persist { it.putBoolean(SettingKeys.AUTO_ON.key, false) }
            HostRuntime.autoEnabled = false
            c.log("⏹ 自动模式关闭"); HostRuntime.sendStatus("已暂停")
        },
        CommandSpec("tick", Lane.CMD, "闹钟驱动的一轮（auto 关着时忽略；带 src=alarm 与代号的才是状态机自己排的）") { c ->
            TickDriver.request(c.str(AlarmScheduler.EXTRA_SRC, "tick").ifBlank { "tick" }, c.int(AlarmScheduler.EXTRA_GEN, -1))
        },
        CommandSpec("refresh", Lane.TICK, "立即刷新：auto 开着就请求跑一轮（经闸门）；关着只读数值/任务并推一帧快照（不开任务）") { c ->
            if (HostRuntime.autoEnabled) {
                if (!TickDriver.request("refresh")) HostRuntime.sendStatus(HostRuntime.lastState)
                return@CommandSpec
            }
            val s = c.service() ?: return@CommandSpec
            PetResult.read { s.readValues() }.getOrNull()?.let { HostRuntime.view.noteValues(it); HostRuntime.daily?.noteGold(it.gold.toInt()) }
            PetResult.read { s.story.status() }.getOrNull()?.let { st ->
                val d = HostRuntime.daily
                val filled = io.github.juwanhwang.autoqqpet.pet.StoryStatus.fillStoryId(st, d?.pendingId().orEmpty(), d?.pendingKind().orEmpty())
                HostRuntime.view.noteStory(filled.takeIf { it.storyId.isNotEmpty() || it.remainingSeconds > 0 }, d?.isMine(filled.storyId))
            }
            HostRuntime.sendStatus(HostRuntime.lastState)
        },
        CommandSpec("snapshot", Lane.CMD, "立刻推一帧状态快照（不发网络请求）") { _ -> HostRuntime.sendStatus(HostRuntime.lastState) },
        CommandSpec("setattr", Lane.CMD, "只改目标属性 --es attr") { c ->
            val v = c.str("attr", "力量")
            if (!HostRuntime.setOption(SettingKeys.ATTR.key, v)) { c.log("⚠️ 属性值不合法：$v"); return@CommandSpec }
            HostRuntime.rebuildRunner(); c.log("🎯 目标属性已改为 $v"); HostRuntime.rearmSoon("目标属性")
        },
        CommandSpec("adv", Lane.CMD, "任务模式 op=auto|mix|work|only|custom；op=limit --ei n 改每日冒险上限") { c ->
            when (c.op) {
                // 五挡逐一登记：else 绝不能猜（2026-10-17 踩过：auto 没登记、落进 else 被写成 mix，
                // 界面显示最优调度、实际跑学习优先，还连带后面切只冒险/排班滑回）
                "auto", "mix", "only", "work", "custom" -> HostRuntime.setOption(SettingKeys.ADV_MODE.key, c.op)
                "limit" -> HostRuntime.setOption(SettingKeys.ADV_MAX.key, c.n.coerceAtLeast(0).toString())
                else -> { c.log("⚠️ 未知任务模式：${c.op}"); return@CommandSpec }
            }
            HostRuntime.rebuildRunner(); HostRuntime.rearmSoon("任务模式")
        },
        CommandSpec("setwork", Lane.CMD, "打工方向 --el career <type>（0=自动）") { c ->
            val career = c.long("career").coerceAtLeast(0L)
            HostRuntime.setOption(SettingKeys.WORK_CAREER.key, career.toString())
            HostRuntime.rebuildRunner(); c.log("💼 打工方向=${if (career > 0) "type=$career" else "自动"}"); HostRuntime.rearmSoon("打工方向")
        },
        CommandSpec("setopt", Lane.CMD, "通用设置：--es key <键> --es value <值>（按 SettingKeys 登记表校验类型与范围）") { c ->
            val key = c.str("key"); val value = c.str("value")
            if (key == SettingKeys.AUTO_ON.key) { c.log("⚠️ auto_on 请用 auto/autooff 命令"); return@CommandSpec }
            if (!HostRuntime.setOption(key, value)) { c.log("⚠️ setopt 拒绝：键 '$key' 未登记或值 '$value' 不合法"); return@CommandSpec }
            if (key == SettingKeys.HIRE_ALERT_ON.key && value.lowercase() in setOf("false", "0", "off")) HostRuntime.alerts.clear(Alerts.RECALL)
            HostRuntime.rebuildRunner(); c.log("⚙️ $key=$value 已生效"); HostRuntime.rearmSoon(key)
            HostRuntime.sendStatus(HostRuntime.lastState)
        },
        CommandSpec("getopts", Lane.CMD, "打印全部登记设置的当前值") { c ->
            val sp = HostRuntime.settings() ?: return@CommandSpec
            SettingKeys.ALL.forEach { d -> c.log("  ${d.key}=${sp.all[d.key] ?: "${d.default}(默认)"}  // ${d.title}${if (d.experimental) "（实验）" else ""}") }
        },
        // —— 兼容 v1 的专用开关命令（新前端可统一用 setopt）——
        CommandSpec("hirealert", Lane.CMD, "疑似被雇佣提醒 op=on|off（默认 off，会误报）") { c ->
            HostRuntime.setOption(SettingKeys.HIRE_ALERT_ON.key, (c.op == "on").toString())
            HostRuntime.rebuildRunner(); if (c.op != "on") HostRuntime.alerts.clear(Alerts.RECALL)
            HostRuntime.sendStatus(HostRuntime.lastState)
        },
        CommandSpec("setrecall", Lane.CMD, "自动召回 op=on|off，--es mode best_split|immediate") { c ->
            if (c.op == "on" || c.op == "off") HostRuntime.setOption(SettingKeys.RECALL_ON.key, (c.op == "on").toString())
            c.intent.getStringExtra("mode")?.let { HostRuntime.setOption(SettingKeys.RECALL_MODE.key, if (it == "immediate") "immediate" else "best_split") }
            HostRuntime.rebuildRunner(); HostRuntime.rearmSoon("召回")
        },
        CommandSpec("sethire", Lane.CMD, "开工自动雇佣好友 op=on|off") { c ->
            HostRuntime.setOption(SettingKeys.HIRE_ON.key, (c.op == "on").toString())
            HostRuntime.rebuildRunner(); HostRuntime.rearmSoon("自动雇佣")
        },
        CommandSpec("schedule", Lane.CMD, "自定义排班：--es json <整份> 写入；op=show|reset|clear") { c ->
            val sp = HostRuntime.settings() ?: return@CommandSpec
            val json = c.str("json")
            if (json.isNotBlank()) {
                val parsed = Schedule.parse(json)
                if (parsed.steps.isEmpty()) { c.log("📋 排班 JSON 解不出步骤（${json.length} 字节），不写入"); return@CommandSpec }
                HostRuntime.persist { it.putString(Schedule.PREF_KEY, Schedule.encode(parsed)) }
                // 步 id 与指纹一并留档：步进度按 id 记在台账上，哪天进度归零要能对出是哪一步的 id 变了（2026-11-10 Hey 实报后加）
                c.log("📋 排班已写入：${parsed.summary()}｜步id=${parsed.steps.joinToString(",") { it.id }}｜指纹=${Integer.toHexString(Schedule.encode(parsed).hashCode())}")
                HostRuntime.rebuildRunner(); HostRuntime.rearmSoon("排班")
                return@CommandSpec
            }
            when (c.op) {
                "reset" -> { HostRuntime.daily?.setSchedule(0, 0); c.log("📋 排班游标回到第 1 步，轮次归零") }
                "clear" -> { HostRuntime.persist { it.remove(Schedule.PREF_KEY) }; HostRuntime.rebuildRunner(); c.log("📋 已清空排班") }
                else -> {
                    val cur = Schedule.parse(sp.getString(Schedule.PREF_KEY, ""))
                    c.log("📋 ${cur.summary()}（模式=${HostRuntime.advModeCn(SettingKeys.string(sp, SettingKeys.ADV_MODE))}）")
                    c.log("   ${HostRuntime.runner?.scheduleStatus().orEmpty().ifBlank { "未接管决策" }}")
                }
            }
        },
        CommandSpec("settings", Lane.CMD, "打印当前生效设置与今日累计") { c ->
            c.log("⚙️ 自动=${if (HostRuntime.autoEnabled) "运行中" else "已暂停"} ${HostRuntime.runner?.config?.summary() ?: "（runner 未建）"}")
            HostRuntime.daily?.let { d ->
                c.log("   今日累计：${d.snapshot()}")
                c.log("   任务台账：${d.ledgerSummary()}")
                c.log("   今日实收：${d.rewards()} 起始金币=${d.goldStart()}")
            }
            HostRuntime.runner?.scheduleStatus()?.takeIf { it.isNotEmpty() }?.let { c.log("   $it") }
            c.log("   配置主存：" + when {
                SideStore.restored -> "已用模块自有目录的主存"
                SideStore.channelInstalled -> "可达，但本次没回备份"
                else -> "模块 App 不可达 → 只用 QQ 目录里的本地副本"
            })
        },
        CommandSpec("report", Lane.CMD, "打印今日报告（op=last 打印昨日归档）") { c ->
            val d = HostRuntime.daily ?: return@CommandSpec
            val json = if (c.op == "last") d.lastReport() else d.todayReport(HostRuntime.view.values?.gold?.toInt() ?: -1)
            c.log("📊 ${if (c.op == "last") "昨日" else "今日"}报告：${DailyReport.oneLine(json).ifBlank { "暂无" }}")
            c.log("   $json")
        },
        CommandSpec("diag", Lane.CMD, "诊断快照交模块 App（设置页“反馈问题”附带）") { c ->
            val s = HostRuntime.service
            val ctx = HostRuntime.appContext()
            val qqVer = runCatching {
                @Suppress("DEPRECATION")
                ctx?.packageManager?.getPackageInfo("com.tencent.mobileqq", 0)?.versionName ?: "?"
            }.getOrDefault("?")
            val head = buildString {
                appendLine("【版本与环境】")
                appendLine("  模块 ${BuildConfig.VERSION_NAME}（code ${BuildConfig.VERSION_CODE}）")
                appendLine("  QQ $qqVer ⋅ ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ⋅ Android ${android.os.Build.VERSION.RELEASE}（sdk ${android.os.Build.VERSION.SDK_INT}）")
                appendLine("  抓取于 ${java.time.LocalDateTime.now().withNano(0)}")
                appendLine("  宠物=${s?.petId?.ifBlank { "（未读到）" } ?: "（桥未建）"} 自动化=${if (HostRuntime.autoEnabled) "开" else "关"} 入口=${s?.entryName ?: "?"}")
                appendLine("  主存通道=${if (SideStore.channelInstalled) "可达" else "不可达"}")
            }
            val text = head + (HostRuntime.runner?.diagnose() ?: "（runner 还没建起来）\n")
            SideStore.sendDiag(text)
            c.log("🧾 诊断快照已交模块 App（${text.length} 字符）")
        },
        CommandSpec("recent", Lane.CMD, "打印当日最近 n 行日志（--ei n）") { c ->
            val list = HostRuntime.daily?.recent(if (c.n > 0) c.n.coerceAtMost(150) else 40).orEmpty()
            if (list.isEmpty()) c.log("🔙 暂无事件记录")
            list.chunked(5).forEachIndexed { i, part -> c.log("🔙[$i] " + part.joinToString(" ⋅")) }
        },
        CommandSpec("ledger", Lane.CMD, "打印任务台账（--ei n）") { c ->
            val list = HostRuntime.daily?.ledger().orEmpty()
            if (list.isEmpty()) { c.log("📒 台账为空"); return@CommandSpec }
            c.log("📒 台账共 ${list.size} 条：${HostRuntime.daily?.ledgerSummary()}")
            list.takeLast((if (c.n > 0) c.n else 20).coerceAtMost(60)).chunked(4).forEachIndexed { i, p -> c.log("   📒[$i] " + p.joinToString(" ⋅")) }
        },
        CommandSpec("alert", Lane.CMD, "提醒：op=dismiss --es id <id> [--es incident <事件键>] 划掉（持久，同一事件不再出现）；op=list 打印") { c ->
            when (c.op) {
                "dismiss" -> {
                    val id = c.str("id")
                    if (id.isBlank()) { c.log("需要 --es id"); return@CommandSpec }
                    val hit = HostRuntime.alerts.dismiss(id, c.str("incident"))
                    c.log("🔕 提醒 $id 已划掉${if (hit) "" else "（当前没挂着，记下事件键防再出现）"}")
                    HostRuntime.sendStatus(HostRuntime.lastState)
                }
                else -> {
                    HostRuntime.alerts.all().forEach { a -> c.log("🔔 ${a.id}[${a.level}] 事件=${a.incident} ${a.text}") }
                    c.log("🔕 已划掉：${HostRuntime.alerts.dismissedKeys().joinToString(", ").ifBlank { "无" }}")
                }
            }
        },
        CommandSpec("cfgcheck", Lane.CMD, "配置自检（R10）：磁盘 prefs vs 进程缓存 vs runner 配置；op=heal 时 runner 落后就重建") { c ->
            val r = HostRuntime.runCfgCheck(heal = c.op == "heal")
            if (r == null) c.log("🩺 配置自检：拿不到 context/设置") else {
                c.log("🩺 配置自检（进程 ${HostRuntime.processName} pid=${android.os.Process.myPid()} 锁=${io.github.juwanhwang.autoqqpet.hook.RunnerLock.held}）：${r.line()}")
                c.log("   运行中：${HostRuntime.runner?.config?.summary() ?: "（runner 未建）"}")
                c.log("   主存回信拒收（比本地旧）：${SideStore.staleRejected} 次")
            }
            HostRuntime.sendStatus(HostRuntime.lastState)
        },
        CommandSpec("remind", Lane.CMD, "调试：手动挂/撕一条提醒（--es text …；op=off 撕掉）") { c ->
            if (c.op == "off") HostRuntime.alerts.clear(Alerts.MANUAL)
            else HostRuntime.alerts.set(Alerts.MANUAL, c.str("text").ifEmpty { "测试提醒：这是一条手动下发的提醒" })
            HostRuntime.sendStatus(HostRuntime.lastState)
        },
    )
}
