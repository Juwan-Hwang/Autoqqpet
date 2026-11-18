package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.diag.Diagnostics
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 带快照反馈的 issue 链接长度（2026-11-03 群友实证）：
 * 头部长中文行把预算吃光时，旧口径拼出的链接被 GitHub 以 “Your request URL is too long” 拒掉。
 * 无论快照多长，prefillBody 拼出的链接都必须 ≤6000，且状态头部要保住。
 * （方括号用转义写：字面方括号对会被某些工具当标记吞掉。）
 */
class DiagnosticsUrlTest {

    private fun fatSnapshot(): String {
        val sb = StringBuilder()
        sb.appendLine("\u3010模块 App\u30112.1.10（code 58）")
        sb.appendLine("\u3010手机 QQ\u30119.3.80（code 16750）")
        sb.appendLine("\u3010设备\u3011samsung SM-S9380 ⋅ Android 15（sdk 35）")
        // 几行命中 keep 模式的超长中文（依据/此刻会怎么选这类在真机上就是长句）
        repeat(6) { sb.appendLine("\u3010依据\u3011" + "当前报酬比今日首读低很多，决策已按当前报酬重新排序，优先派报酬没有塌方的岗位与课程。".repeat(4)) }
        repeat(4) { sb.appendLine("\u3010此刻会怎么选\u3011" + "先把冒险派到见底再转打工，雇佣按加成排名从上到下逐个尝试，都雇不到就自己开工。".repeat(4)) }
        sb.appendLine("\u3010今日运行日志\u3011")
        repeat(300) { i -> sb.appendLine("18:0${i % 10}:11 🧮 决策: 冒险 附近走走 —— 最优调度[属性优先]：冒险先行（池子未见底），见底后转打工") }
        return sb.toString()
    }

    @Test fun prefillUrlNeverExceedsCapEvenWithFatHead() {
        val url = Diagnostics.issueUrl("\u3010反馈\u3011（写上一句现象）", Diagnostics.prefillBody(fatSnapshot()))
        assertTrue("url length ${url.length} > 6000", url.length <= 6000)
    }

    @Test fun prefillKeepsStatusHeadAndSomeLog() {
        val body = Diagnostics.prefillBody(fatSnapshot())
        assertTrue(body.contains("## 运行状态"))
        assertTrue(body.contains("\u3010模块 App\u30112.1.10"))
        assertTrue(body.contains("🧮 决策"))
    }

    @Test fun smallSnapshotKeepsEverything() {
        val text = "\u3010模块 App\u30112.1.10（code 58）\n\u3010今日运行日志\u3011\n10:00:01 ▶ 指令 tick op=set\n"
        val body = Diagnostics.prefillBody(text)
        assertTrue(body.contains("（以上是今天全部日志）"))
        assertTrue(body.contains("▶ 指令 tick op=set"))
    }
}
