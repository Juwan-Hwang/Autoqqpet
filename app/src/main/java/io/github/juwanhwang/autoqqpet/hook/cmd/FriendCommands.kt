package io.github.juwanhwang.autoqqpet.hook.cmd

import io.github.juwanhwang.autoqqpet.hook.HostRuntime
import io.github.juwanhwang.autoqqpet.pet.PetResult
import io.github.juwanhwang.autoqqpet.store.SettingKeys

/** 好友互动、名单、福袋。全部受 FriendPolicy 名单与每日上限约束。 */
object FriendCommands {

    private fun cfgInt(d: SettingKeys.Def): Int = HostRuntime.settings()?.let { SettingKeys.int(it, d) } ?: (d.default as Int)

    fun register(r: CommandRouter) = r.register(
        CommandSpec("pk", Lane.TICK, "打一场 PK") { c -> HostRuntime.friends?.pkOnce(cfgInt(SettingKeys.PK_MAX), cfgInt(SettingKeys.PK_MARGIN)) ?: c.log("好友模块未就绪") },
        CommandSpec("visit", Lane.CMD, "访问一个好友家") { c -> HostRuntime.friends?.visitOnce() ?: c.log("好友模块未就绪") },
        CommandSpec("poke", Lane.CMD, "踩踩一个好友") { c -> HostRuntime.friends?.pokeOnce() ?: c.log("好友模块未就绪") },
        CommandSpec("roster", Lane.CMD, "刷新好友名册并交主存") { c ->
            val n = runCatching { HostRuntime.friends?.refreshRoster() ?: 0 }.getOrDefault(0)
            c.log("👥 好友名册：$n 人；互动名单=${HostRuntime.policy?.friendList()}（模式=${HostRuntime.policy?.friendListMode()}）")
        },
        CommandSpec("friend", Lane.CMD, "互动名单 op=set|add|rm --es uins a,b --es mode wl|bl") { c ->
            HostRuntime.policy?.let { p ->
                val next = when (c.op) { "add" -> p.friendList() + c.uins; "rm" -> p.friendList() - c.uins.toSet(); else -> c.uins.toSet() }
                p.setFriendList(next)
                c.str("mode").takeIf { it.isNotEmpty() }?.let { p.setFriendListMode(it) }
                c.log("互动名单=$next 模式=${p.friendListMode()}")
            }
        },
        CommandSpec("wl", Lane.CMD, "（旧）白名单 = 互动名单 + 白名单模式 op=set|add|rm --es uins a,b") { c ->
            HostRuntime.policy?.let { p ->
                val next = when (c.op) { "add" -> p.friendList() + c.uins; "rm" -> p.friendList() - c.uins.toSet(); else -> c.uins.toSet() }
                p.setFriendList(next); p.setFriendListMode("wl"); c.log("互动名单（白名单模式）=$next")
            }
        },
        CommandSpec("bl", Lane.CMD, "（旧）黑名单 = 互动名单 + 黑名单模式 op=set|add|rm --es uins a,b") { c ->
            HostRuntime.policy?.let { p ->
                val next = when (c.op) { "add" -> p.friendList() + c.uins; "rm" -> p.friendList() - c.uins.toSet(); else -> c.uins.toSet() }
                p.setFriendList(next); p.setFriendListMode("bl"); c.log("互动名单（黑名单模式）=$next")
            }
        },
        CommandSpec("hire", Lane.CMD, "雇佣名单 op=set|add|rm --es uins a,b --es mode wl|bl（空=不限）") { c ->
            HostRuntime.policy?.let { p ->
                val next = when (c.op) { "add" -> p.hireList() + c.uins; "rm" -> p.hireList() - c.uins.toSet(); else -> c.uins.toSet() }
                p.setHireList(next)
                c.str("mode").takeIf { it.isNotEmpty() }?.let { p.setHireListMode(it) }
                c.log("雇佣名单=$next 模式=${p.hireListMode()}")
            }
        },
        CommandSpec("spark", Lane.CMD, "火花名单整份写回 --es bl c,d；op=mode --es mode wl|bl 切模式（默认黑名单模式）") { c ->
            HostRuntime.policy?.let { p ->
                if (c.op == "mode") { p.setSparkListMode(c.str("mode")); c.log("🔥 火花名单模式=${p.sparkListMode()}"); return@let }
                val bl = c.str("bl").split(",").map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }
                p.setSparkBlacklist(bl); c.log("🔥 火花名单=${p.sparkBlacklist().size} 人 模式=${p.sparkListMode()}")
            }
        },
        CommandSpec("sparks", Lane.CMD, "现读火花列表并交主存（实验）") { c ->
            val f = HostRuntime.friends ?: run { c.log("好友模块未就绪"); return@CommandSpec }
            val l = f.refreshSparks()
            c.log("🔥 火花 ${l?.size ?: "读取失败"} 条" + l.orEmpty().take(12).joinToString("") { "\n   · ${it.nick} ${it.stage.cn} ${it.days}天${if (it.pokedToday) " 今日已互踩" else ""}" })
        },
        CommandSpec("sparkrun", Lane.TICK, "立刻跑一轮火花踩（按火花名单模式，受每轮上限；回踩开关）") { c ->
            val f = HostRuntime.friends ?: run { c.log("好友模块未就绪"); return@CommandSpec }
            val ret = HostRuntime.settings()?.let { SettingKeys.bool(it, SettingKeys.SPARK_RETURN_ON) } ?: false
            val on = HostRuntime.settings()?.let { SettingKeys.bool(it, SettingKeys.SPARK_ON) } ?: true
            c.log("👣 本轮踩了 ${f.sparkRound(cfgInt(SettingKeys.SPARK_PER_ROUND), cfgInt(SettingKeys.SPARK_MAX), ret, f.cachedSparks(), dailyOn = on)} 人")
        },
        CommandSpec("lists", Lane.CMD, "打印各名单与模式") { c -> HostRuntime.policy?.let { c.log("互动名单=${it.friendList()}（${it.friendListMode()}） 雇佣名单=${it.hireList()}（${it.hireListMode()}） 火花名单=${it.sparkBlacklist()}（${it.sparkListMode()}）") } },
        CommandSpec("friendpet", Lane.CMD, "好友三属性（f1=好友 petId）--es pet") { c ->
            val s = c.service() ?: return@CommandSpec
            if (c.pet.isEmpty()) { c.log("friendpet 需要 --es pet <好友petId>"); return@CommandSpec }
            c.log("🫧 好友 ${c.pet.take(12)}… → " + PetResult.read { s.profile.friendValues(c.pet) }.describe())
        },
        CommandSpec("fcare", Lane.TICK, "代喂代洗：--es uin --es pet 指定一个好友；留空则按名单轮一圈（--ei n 阈值，默认设置值）") { c ->
            val f = HostRuntime.friends ?: run { c.log("好友模块未就绪"); return@CommandSpec }
            val floor = if (c.n > 0) c.n else cfgInt(SettingKeys.FRIEND_CARE_FLOOR)
            val target = if (c.uin.isNotEmpty() && c.pet.isNotEmpty()) c.uin to c.pet else null
            c.log("🫧 代照顾完成 ${f.careRound(floor, cfgInt(SettingKeys.FRIEND_CARE_MAX), false, 0, target)} 次")
        },
        CommandSpec("foneclick", Lane.TICK, "对好友一键照顾（会花金币）--es uin --es pet") { c ->
            val f = HostRuntime.friends ?: run { c.log("好友模块未就绪"); return@CommandSpec }
            val target = if (c.uin.isNotEmpty() && c.pet.isNotEmpty()) c.uin to c.pet else null
            c.log("🧺 一键照顾完成 ${f.careRound(cfgInt(SettingKeys.FRIEND_CARE_FLOOR), 0, true, cfgInt(SettingKeys.FRIEND_ONECLICK_MAX), target)} 次")
        },
        CommandSpec("coinbag", Lane.TICK, "福袋：--es bag <袋id> --es pet <袋主petId> 直接领；留空则轮一圈领（先自己的、再好友的）") { c ->
            val s = c.service() ?: return@CommandSpec
            val bag = c.str("bag")
            if (bag.isNotEmpty()) {
                c.log("🧧 查袋 → " + PetResult.read { s.friend.coinBagInfo(bag) }.describe())
                if (c.pet.isNotEmpty()) c.log("🧧 领袋 → " + PetResult.write { s.friend.openCoinBag(c.pet, bag) }.describe())
                return@CommandSpec
            }
            c.log("🧧 本轮领到 ${HostRuntime.friends?.coinBagRound(cfgInt(SettingKeys.COINBAG_MAX)) ?: 0} 个")
        },
        CommandSpec("cancelhire", Lane.CMD, "取消雇佣好友--es pet <好友petId>") { c ->
            val s = c.service() ?: return@CommandSpec
            if (c.pet.isEmpty()) { c.log("需要 --es pet"); return@CommandSpec }
            c.log("🤝 取消雇佣 → " + PetResult.write { s.friend.cancelHire(c.pet) }.describe())
        },
        CommandSpec("pkpower", Lane.CMD, "战力与主导属性（--es pet 空=自己）") { c ->
            val s = c.service() ?: return@CommandSpec
            c.log("⚔️ " + PetResult.read { s.profile.pkPower(c.pet.ifEmpty { s.ensurePetId() }) }.describe())
        },
        CommandSpec("pkpool", Lane.CMD, "好友池与战力字段探针") { c ->
            val s = c.service() ?: return@CommandSpec
            val mine = PetResult.read { s.profile.pkPower() }.getOrNull()
            val pool = PetResult.read { s.friend.pool() }.getOrNull().orEmpty()
            c.log("⚔️ 我方战力=${mine?.power ?: "?"}；好友池 ${pool.size} 人")
            pool.take(12).chunked(4).forEachIndexed { i, p -> c.log("   ⚔[$i] " + p.joinToString(" ⋅") { "uin=${it.uin} f3=${it.powerF3} f4=${it.powerF4}" }) }
        },
    )
}
