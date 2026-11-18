package io.github.juwanhwang.autoqqpet.hook.cmd

import io.github.juwanhwang.autoqqpet.hook.HostRuntime
import io.github.juwanhwang.autoqqpet.hook.KuiklyPacketLogger
import io.github.juwanhwang.autoqqpet.hook.LoaderProbes
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.optimizer.CatalogReader
import io.github.juwanhwang.autoqqpet.pet.AdSweepState
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.pet.QQPetBridge
import io.github.juwanhwang.autoqqpet.pet.service.RewardAdApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** 排障/校准用命令。不进自动流程；有副作用的都在 help 里写明。 */
object DebugCommands {

    fun register(r: CommandRouter) = r.register(
        CommandSpec("envprobe", Lane.CMD, "环境自检（进程/QQ 版本/入口/MobileQQ 单例）") { _ -> LoaderProbes.envProbe() },
        CommandSpec("loaderprobe", Lane.CMD, "逐个 ClassLoader 比较 MobileQQ 副本并真发一次只读") { _ -> LoaderProbes.loaderProbe() },
        CommandSpec("rawsend", Lane.CMD, "原样发包 --es cmd --ei command --ei sub --es body <hex> --es read 1|0（写命令有副作用）") { c ->
            val s = c.service() ?: return@CommandSpec
            c.log("📤 rawsend → " + PetResult.read { s.debug.rawSend(c.str("cmd"), c.int("command"), c.int("sub"), c.str("body"), c.str("read") == "1") }.describe())
        },
        CommandSpec("dbgcmd", Lane.CMD, "读任意命令并打印字段树 --es cmd --ei command --ei sub --es body") { c ->
            val s = c.service() ?: return@CommandSpec
            c.log("🐛 RESP ${c.str("cmd")}: " + PetResult.read { s.debug.dumpCmd(c.str("cmd"), c.int("command"), c.int("sub"), c.str("body")) }.describe())
        },
        CommandSpec("dbgstory", Lane.CMD, "STORY_STATUS 字段树") { c -> c.service()?.let { c.log("🐛 " + PetResult.read { it.debug.dumpStory() }.describe()) } },
        CommandSpec("dbgprofile", Lane.CMD, "OWN_PROFILE 字段树") { c -> c.service()?.let { c.log("🐛 " + PetResult.read { it.debug.dumpProfile() }.describe()) } },
        CommandSpec("settimeout", Lane.CMD, "只读等待上限 --ei ms（0=恢复 15s），用于自造回调超时") { c ->
            QQPetBridge.readTimeoutOverrideMs = c.int("ms").toLong()
            c.log(if (QQPetBridge.readTimeoutOverrideMs > 0) "⏱ 只读等待上限 ${QQPetBridge.readTimeoutOverrideMs}ms" else "⏱ 恢复默认 15s")
        },
        CommandSpec("failread", Lane.CMD, "目录读取故障注入 --es what courses|careers|stage|none") { c ->
            val w = c.str("what")
            CatalogReader.failRead = if (w == "none" || w.isBlank()) "" else w
            c.log(if (CatalogReader.failRead.isEmpty()) "🧪 目录读取已恢复" else "🧪 已注入：${CatalogReader.failRead} 读失败（验完 failread what=none）")
        },
        CommandSpec("paypeak", Lane.CMD, "收益峰值 op=show|bump(--es key --ei n)|clear(--es key)|restore") { c ->
            val d = HostRuntime.daily ?: return@CommandSpec
            val key = c.str("key")
            when (c.op) {
                "bump" -> { d.bumpPayPeak(key, if (c.n > 0) c.n else 30); c.log("📈 已把 $key 峰值抬高 ${if (c.n > 0) c.n else 30}%") }
                "clear" -> { d.clearPayPeak(key); c.log("📈 已清基准 ${key.ifEmpty { "全部" }}（已存档，可 op=restore）") }
                "restore" -> c.log(if (d.restorePayPeak()) "📈 已恢复：${d.payPeaks()}" else "📈 没有可恢复的存档")
                else -> c.log("📈 当日峰值：${d.payPeaks()} ｜ 结算降效档=${d.settleDrops()} ｜ 实收均值：${d.learnedSummary()}")
            }
            HostRuntime.rearmSoon("收益基准")
        },
        CommandSpec("fatigue", Lane.CMD, "当日学工累计 op=show|set --ei n（只展示用，已不参与决策）") { c ->
            val d = HostRuntime.daily ?: return@CommandSpec
            if (c.op == "set") d.setFatigue(c.n.coerceAtLeast(0))
            c.log("🧮 学工 ${d.fatigueMinutes()}min ｜ 冒险 ${d.adventureMinutes()}min ｜ ${d.snapshot()}")
        },
        CommandSpec("packet", Lane.CMD, "Kuikly 页面抓包 op=on|off|status") { c ->
            when (c.str("op")) { "on" -> KuiklyPacketLogger.setEnabled(true); "off" -> KuiklyPacketLogger.setEnabled(false) }
            c.log("📦 抓包：enabled=${KuiklyPacketLogger.enabled} ${KuiklyPacketLogger.status()}")
        },
        CommandSpec("webssosend", Lane.CMD, "经 Kuikly 桥原样发 WebSSO --es json <完整参数JSON>（先开抓包并进宠物页；写命令有副作用）") { c ->
            val api = KuiklyPacketLogger.lastApiInstance
            if (api == null) {
                c.log("📭 没有活着的 Kuikly 实例：先 packet op=on，再进宠物页触发一次发包")
                return@CommandSpec
            }
            val json = c.str("json")
            if (json.isBlank()) {
                c.log("用法：--es json '{\"cmd\":…,\"data\":{…}}'")
                return@CommandSpec
            }
            val ret = runCatching {
                val loader = api.javaClass.classLoader
                val fn1 = loader.loadClass("kotlin.jvm.functions.Function1")
                val cb = Proxy.newProxyInstance(loader, arrayOf(fn1)) { proxy, m, ms ->
                    when (m.name) {
                        "invoke" -> { PLog.i("PKT", "[webssosend 回包] ${ms?.firstOrNull()}"); null }
                        "equals" -> proxy === ms?.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "WebSsoSendCb"
                        else -> null
                    }
                }
                val call = api.javaClass.methods.first { m ->
                    m.name == "call" && m.parameterTypes.size == 3 &&
                        m.parameterTypes[0] == String::class.java &&
                        m.parameterTypes[1] == String::class.java &&
                        m.parameterTypes[2] == fn1
                }
                try {
                    call.invoke(api, "sendWebSSORequest", json, cb)
                } catch (e: InvocationTargetException) {
                    throw e.targetException ?: e
                }
            }
            val err = ret.exceptionOrNull()?.let { " 抛错=${it.javaClass.simpleName}: ${it.message}" } ?: ""
            c.log("📤 webssosend 同步返回=${ret.getOrNull()}$err（回包看 PKT：[webssosend 回包] / [回调入参 sendWebSSORequest]）")
        },
        CommandSpec("webssodirect", Lane.CMD, "直调 VSNetworkHelper 发 WebSSO --es json <参数JSON>（不依赖页面实例；写命令有副作用）") { c ->
            val json = c.str("json")
            if (json.isBlank()) {
                c.log("用法：--es json '{\"cmd\":…,\"data\":{…}}'")
                return@CommandSpec
            }
            val ret = io.github.juwanhwang.autoqqpet.net.WebSsoDirect.send(json) { args ->
                PLog.i("PKT", "[webssodirect 回包] " + (args ?: emptyArray()).joinToString(",") { it?.toString() ?: "null" })
            }
            c.log("📡 webssodirect → $ret")
        },
        CommandSpec("svipgift", Lane.CMD, "手动跑一次 SVIP 礼包流程（0x9bd4 读取 + WebSSO 领取；写命令）") { c ->
            val s = c.service() ?: return@CommandSpec
            val r = PetResult.read { s.vip.receiveInfo() }
            c.log("🎁 读取：" + r.describe())
            val g = r.getOrNull() ?: return@CommandSpec
            c.log("🎁 state=${g.state} subActId=${g.subActId.ifEmpty { "无" }} items=${g.items.joinToString("、").ifEmpty { "无" }}")
            if (!g.claimable) return@CommandSpec
            c.log("🎁 发起领取 → " + s.vip.claim(g.subActId) { json -> PLog.i("PKT", "[svipgift 回包] $json") })
        },
        CommandSpec("patfriend", Lane.CMD, "摸一个好友 --es uin --es petid（0x96a6，好友心情 +2；写命令）") { c ->
            val s = c.service() ?: return@CommandSpec
            val uin = c.str("uin")
            val petId = c.str("petid")
            if (uin.isBlank() || petId.isBlank()) {
                c.log("用法：--es uin <好友uin> --es petid <好友petId>")
                return@CommandSpec
            }
            c.log("🫳 摸好友 → " + PetResult.write { s.friend.pat(uin, petId) }.describe())
        },
        CommandSpec("patnext", Lane.CMD, "跑一轮自动摸好友 --ei n（默认 1 人，黑名单口径；写命令）") { c ->
            val f = HostRuntime.friends ?: return@CommandSpec
            val n = if (c.int("n") > 0) c.int("n") else 1
            c.log("🫳 本轮摸好友 ${f.patRound(sparkOnly = false, sparks = f.cachedSparks(), perRound = n)} 人")
        },
        CommandSpec("diarylike", Lane.CMD, "读本月日记并给最近几篇点赞（0x9762 + 0x9ba3；写命令）") { c ->
            val s = c.service() ?: return@CommandSpec
            val month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
            val r = PetResult.read { s.diary.month(month) }
            c.log("📔 列表：" + r.describe() + " 共 ${r.getOrNull()?.size ?: 0} 篇")
            val list = r.getOrNull() ?: return@CommandSpec
            list.takeLast(3).forEach { e ->
                c.log("📔 ${e.date} → " + PetResult.write { s.diary.like(e.uuid) }.describe())
                Thread.sleep(1500)
            }
        },
        CommandSpec("iteminv", Lane.CMD, "按族查背包 --ei family（默认 356 学习 / 357 打工）") { c ->
            val s = c.service() ?: return@CommandSpec
            val f = if (c.int("family") > 0) c.int("family").toLong() else 356L
            c.log("🎒 族 $f：" + PetResult.read { s.item.inventory(f) }.describe())
        },
        CommandSpec("itemuse", Lane.CMD, "用一颗任务道具 --ei family --es sid（写命令）") { c ->
            val s = c.service() ?: return@CommandSpec
            val f = if (c.int("family") > 0) c.int("family").toLong() else 356L
            val sid = c.str("sid")
            if (sid.isBlank()) {
                c.log("用法：--ei family 356|357 --es sid <storyId>")
                return@CommandSpec
            }
            c.log("🍹 " + PetResult.write { s.item.use(f, sid) }.describe())
        },
        CommandSpec("dailyitem", Lane.CMD, "手动领一次每日免费学习道具（0x9e9c；写命令）") { c ->
            val s = c.service() ?: return@CommandSpec
            c.log("🍼 " + PetResult.write { s.item.claimDaily() }.describe())
        },
        CommandSpec("adgift", Lane.CMD, "手动看一次每日广告福利（rewardType 61；写操作）--es background 1 后台领取") { c ->
            val s = c.service() ?: return@CommandSpec
            val petId = runCatching { s.ensurePetId() }.getOrDefault("")
            if (petId.isEmpty()) { c.log("📺 还没读到 petId"); return@CommandSpec }
            val r = s.rewardAd.watchDailyGift(petId, background = c.str("background") == "1")
            c.log("📺 adgift ok=${r.ok} code=${r.code} msg=${r.message}" + if (r.remote.isNotEmpty()) " remote=${r.remote}" else "")
        },
        CommandSpec("adcoin", Lane.CMD, "手动看一次结算广告 --es sid <storyId>（rewardType 60；写操作）--es background 1 跳过播放=伪造曝光，有风控风险") { c ->
            val s = c.service() ?: return@CommandSpec
            val sid = c.str("sid")
            if (sid.isBlank()) { c.log("用法：--es sid <storyId>"); return@CommandSpec }
            val petId = runCatching { s.ensurePetId() }.getOrDefault("")
            if (petId.isEmpty()) { c.log("📺 还没读到 petId"); return@CommandSpec }
            val r = s.rewardAd.watchStoryCoin(petId, sid, background = c.str("background") == "1")
            c.log("📺 adcoin ok=${r.ok} code=${r.code} msg=${r.message}" + if (r.remote.isNotEmpty()) " remote=${r.remote}" else "")
        },
        // adall / adsweepstatus 已挪到 main 的 TaskCommands（正式包也注册，默认正常播放）
        CommandSpec("outings", Lane.CMD, "读《出门记录》 --ei pages 3 --ei size 20") { c ->
            val s = c.service() ?: return@CommandSpec
            val pages = c.int("pages", 3).coerceIn(1, 30)
            val size = c.int("size", 20).coerceIn(1, 50)
            var cursor = 0L
            var printed = 0
            var stop = false
            for (p in 1..pages) {
                val page = PetResult.read { s.story.history(size, cursor) }.getOrNull()
                if (page == null) { c.log("📔 出门记录读取失败"); stop = true; break }
                for (r in page.records) {
                    c.log("📔 ${r.kind.cn} ${r.storyId} ${r.title} ${r.resultLabel} ${r.atMs}")
                    printed++
                }
                val next = page.nextCursorMs
                if (next <= 0L || next == cursor) { stop = true; break }
                cursor = next
            }
            c.log("📔 共打印 $printed 条" + if (!stop) "（还有下一页）" else "")
        },
    )
}
