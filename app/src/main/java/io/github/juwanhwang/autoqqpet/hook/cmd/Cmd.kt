package io.github.juwanhwang.autoqqpet.hook.cmd

import android.content.Intent
import io.github.juwanhwang.autoqqpet.hook.HostRuntime
import io.github.juwanhwang.autoqqpet.log.PLog
import io.github.juwanhwang.autoqqpet.pet.QQPetService
import java.util.concurrent.Executors

/** 一条命令广播的参数（`am broadcast -a io.github.juwanhwang.autoqqpet.CMD --es action <a> …`）。 */
class CmdContext(val intent: Intent) {
    val action: String = intent.getStringExtra("action").orEmpty()
    val op: String = intent.getStringExtra("op") ?: "set"
    val n: Int = intent.getIntExtra("n", 0)
    val uin: String = (intent.getStringExtra("uin") ?: "").trim()
    val pet: String = intent.getStringExtra("pet") ?: ""
    fun str(key: String, def: String = ""): String = intent.getStringExtra(key) ?: def
    fun int(key: String, def: Int = 0): Int = intent.getIntExtra(key, def)
    fun long(key: String, def: Long = 0L): Long = intent.getLongExtra(key, def)

    /** uin 列表只收纯数字（shell 吃出的 `-p` 曾被永久写进黑名单）。 */
    val uins: List<String> = (intent.getStringExtra("uins") ?: "").split(",").map { it.trim() }
        .filter { it.isNotEmpty() && it.all { c -> c.isDigit() } }

    val rt get() = HostRuntime

    /** 需要发包的命令用它拿 service；没就绪直接说，不静默返回。 */
    fun service(): QQPetService? = HostRuntime.service ?: run { log("⚠️ 发包桥未就绪，指令 $action 忽略"); null }

    fun log(msg: String) { PLog.i("Autoqqpet", msg) }
}

/** 命令跑在哪条线：TICK=单线程串行（会动状态机的）；CMD=小线程池（查询/调试）。 */
enum class Lane { TICK, CMD }

class CommandSpec(val name: String, val lane: Lane, val help: String, val run: (CmdContext) -> Unit)

/** 执行器：见 HostRuntime 头部的线程模型说明。 */
object Workers {
    val tick = Executors.newSingleThreadExecutor { r -> Thread(r, "Autoqqpet-Tick").apply { isDaemon = true } }
    val cmd = Executors.newFixedThreadPool(2) { r -> Thread(r, "Autoqqpet-Cmd").apply { isDaemon = true } }
}

/**
 * 命令分发：v1 是 ApplicationHook 里一个 700 行的 `when`。现在每族一个文件注册，表即文档（`help` 命令打印全部）。
 */
object CommandRouter {
    private val table = LinkedHashMap<String, CommandSpec>()

    fun register(vararg specs: CommandSpec) = specs.forEach { table[it.name] = it }

    init {
        CoreCommands.register(this)
        TaskCommands.register(this)
        FriendCommands.register(this)
        io.github.juwanhwang.autoqqpet.hook.ReconHooks.registerDebugCommands(this)
        register(CommandSpec("help", Lane.CMD, "打印全部命令") { c -> table.values.forEach { c.log("  ${it.name}（${it.lane}）— ${it.help}") } })
    }

    fun names(): List<String> = table.keys.toList()

    fun dispatch(intent: Intent) {
        val c = CmdContext(intent)
        val spec = table[c.action] ?: run { c.log("未知指令: ${c.action}"); return }
        runCatching { HostRuntime.daily?.pushEvent("▶ 指令 ${c.action} op=${c.op}") }
        val job = Runnable {
            try { spec.run(c) } catch (t: Throwable) { PLog.e("Autoqqpet", "❌ 指令 ${c.action} 失败", t) }
        }
        if (spec.lane == Lane.TICK) Workers.tick.execute(job) else Workers.cmd.execute(job)
    }
}
