package io.github.juwanhwang.autoqqpet.diag

import android.content.Context
import android.content.pm.PackageManager
import io.github.juwanhwang.autoqqpet.BuildConfig
import io.github.juwanhwang.autoqqpet.store.Store
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断包：把"这一台机器此刻是什么状态"打成一个文本，供反馈到 issue。
 *
 * 数据从哪儿来（关键约束：两个进程不共用数据目录）：
 *  - QQ 进程那一大段（读数、目录候选与淘汰原因、当日决策统计、当日日志）由 `diag` 广播现场生成，
 *    经主存通道送进来（[Store.EXTRACT_DIAG]）—— 日志文件在 QQ 私有目录里，这边无 Root 读不到；
 *  - 设置、当日进度、好友名单是主存里的三份 JSON（本来就在同步）；
 *  - 版本与机型由本进程自己读，不用问 QQ。
 *
 * 全文一律过 [Redactor.scrub]；issue 里预填的是【摘要】（URL 有长度限制），
 * 完整文件写到 App 私有外部目录并给出路径，用户在网页上拖拽上传即可。
 */
object Diagnostics {

    private const val QQ_PKG = "com.tencent.mobileqq"

    /** 今天有没有 QQ 侧送来的快照（没有就说明还没触发过一次采集）。 */
    fun hasHostSnapshot(context: Context): Boolean = !Store.read(context, Store.EXTRACT_DIAG).isNullOrBlank()

    fun snapshotAge(context: Context): Long {
        val at = Store.prefs(context).getLong(Store.EXTRACT_DIAG_AT, 0L)
        return if (at <= 0L) -1L else System.currentTimeMillis() - at
    }

    /** 完整诊断文本（已脱敏）。 */
    fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("【模块 App】${BuildConfig.VERSION_NAME}（code ${BuildConfig.VERSION_CODE}）")
        sb.appendLine("【手机 QQ】${qqVersion(context)}")
        sb.appendLine("【设备】${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} ⋅ Android ${android.os.Build.VERSION.RELEASE}（sdk ${android.os.Build.VERSION.SDK_INT}）")
        sb.appendLine("【生成时间】${stamp()}")
        sb.appendLine()
        sb.appendLine("【设置（主存）】")
        sb.appendLine(Store.read(context, Store.EXTRACT_SETTINGS) ?: "（空）")
        sb.appendLine("【当日进度（主存）】")
        sb.appendLine(Store.read(context, Store.EXTRACT_DAILY)?.take(4000) ?: "（空）")
        sb.appendLine("【名单（主存，已脱敏）】")
        sb.appendLine(Store.read(context, Store.EXTRACT_FRIENDS)?.take(2000) ?: "（空）")
        sb.appendLine()
        val host = Store.read(context, Store.EXTRACT_DIAG)
        if (host.isNullOrBlank()) {
            sb.appendLine("【QQ 进程侧快照】无 —— 点“采集运行状态”让模块向 QQ 要一份，再重新生成。")
        } else {
            val age = snapshotAge(context)
            sb.appendLine("【QQ 进程侧快照】${if (age < 0) "时间未知" else "${(age / 60_000).coerceAtLeast(0)} 分钟前"}")
            sb.appendLine(host)
        }
        return Redactor.scrub(sb.toString())
    }

    /** 写盘：私有外部目录（不用任何权限，也能被文件管理器/上传对话框选中）。 */
    fun write(context: Context, text: String): File? = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics").apply { mkdirs() }
        val f = File(dir, "autoqqpet-diag-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        f.writeText(text)
        // 只留最近 10 份，免得越反馈越多
        dir.listFiles()?.sortedBy { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        f
    }.getOrNull()

    /**
     * issue 正文：固定头部 + **最近的尽可能多的日志行**。
     *
     * 全文贴不了：body 走 URL，而中文编码后每个字占 9 个字符，请求 URI 超长会被直接拒掉。
     * 所以按预算从日志尾部往前装（最新的先留），装不下的如实写“已省略最早 N 行”；
     * 完整一份同时写成本地文件并复制到剪贴板 —— 用户不必修任何东西，要补时粘贴即可。
     */
    fun issueBody(text: String, budgetChars: Int = 5400): String {
        val lines = text.split('\n').filter { it.isNotBlank() }
        val logStart = lines.indexOfFirst { it.contains("行日志】") }
        val meta = if (logStart >= 0) lines.take(logStart) else lines
        val logs = if (logStart >= 0) lines.drop(logStart + 1) else emptyList()

        // 固定头部：能一眼看出环境与“它现在怎么想”的那几行，优先保住；
        // 但头部本身也受预算管：单行上限随预算缩小、还装不下就从末尾 drop（保住最前面的版本/设备行）。
        // 2026-11-03 群友实证：头部几行长中文就把预算吃光，旧口径只裁日志、头部原样保留，
        // 最后拼出的链接照样被 GitHub 以“URL too long”拒掉。
        val keep = listOf(
            "【模块 App】", "【手机 QQ】", "【设备】", "模式：", "目录：", "【读数】", "今日学工",
            "【此刻会怎么选】", "【依据】", "【当日决策统计】", "【当日计数】", "步 ",
        )
        var headCap = 300
        var head = meta.filter { m -> keep.any { m.contains(it) } }.map { it.take(headCap) }
        while (head.sumOf { urlCost(it) } > budgetChars * 3 / 5 && (headCap > 40 || head.size > 3)) {
            if (headCap > 40) {
                headCap = (headCap / 2).coerceAtLeast(40)
                head = meta.filter { m -> keep.any { m.contains(it) } }.map { it.take(headCap) }
            } else head = head.dropLast(1)
        }

        fun note(skipped: Int) = if (skipped > 0) "（最早的 $skipped 行因链接长度装不下；完整日志已复制到剪贴板，见下方粘贴位）"
        else "（以上是今天全部日志）"

        fun render(head: List<String>, take: List<String>, note: String) = buildString {
            appendLine("## 现象")
            appendLine("（补一句：你期望它做什么、它实际做了什么、从什么时候开始）")
            appendLine()
            appendLine("## 运行状态（自动生成，已脱敏）")
            appendLine("```")
            head.forEach { appendLine(it) }
            appendLine("```")
            appendLine()
            appendLine("## 当日日志 $note")
            appendLine("```")
            take.forEach { appendLine(it) }
            if (take.isEmpty()) appendLine("（今天还没有日志行）")
            appendLine("```")
            // 留一个【粘贴位】：用户不必猜要粘到哪，也不用上传任何文件
            appendLine()
            appendLine("## 完整日志（已自动复制到剪贴板）")
            appendLine()
            appendLine("长按此处粘贴即可；不粘也不影响提交（上面已有最近若干行）。")
        }

        // 骨架（头部+固定文字，按最长的 note 计）先量出来，剩下预算全给日志：【从尾往前】装，最新的先留
        var used = urlCost(render(head, emptyList(), note(logs.size)))
        val take = ArrayList<String>()
        for (l in logs.asReversed()) {
            val c = urlCost(l.take(400))
            if (used + c > budgetChars) break
            take.add(0, l.take(400))
            used += c
        }
        return render(head, take, note((logs.size - take.size).coerceAtLeast(0)))
    }

    /** 一行占多少 URL 字符：非 ASCII 每个算 9（UTF-8 三字节 × %XX），ASCII 按 1:1。 */
    private fun urlCost(s: String): Int = s.sumOf { if (it.code < 0x80) 1 else 9 } + 1

    /**
     * 选一份能过 URL 长度检查的预填正文（不超 [URL_HARD_CAP]）。
     * 逐轮缩小日志预算，而不是最后硬截（硬截会把代码块切坏）。
     */
    fun prefillBody(text: String): String {
        val u = "https://github.com/Juwan-Hwang/Autoqqpet/issues/new"
        for (budget in listOf(5400, 4000, 2600, 1400)) {
            val body = issueBody(text, budget)
            val enc = runCatching { java.net.URLEncoder.encode(body, "UTF-8").length }.getOrDefault(0)
            if (u.length + enc + 200 <= URL_HARD_CAP) return body
        }
        // 兜底也必须真能过：最小预算下 issueBody 已把头部压到最小、日志裁空，成品远低于上限；
        // 不许再出现“最小预算也超长却硬返回”的情况（2026-11-03 群友点开就是 GitHub 的 too long 页）。
        return issueBody(text, 1000)
    }

    /** 由已算好的正文拼 issue 链接（正文不重算，剪贴板才能按同一份长度做预算）。 */
    fun issueUrl(title: String, body: String): String {
        val u = "https://github.com/Juwan-Hwang/Autoqqpet/issues/new"
        val t = runCatching { java.net.URLEncoder.encode(title, "UTF-8") }.getOrDefault("")
        val e = runCatching { java.net.URLEncoder.encode(body, "UTF-8") }.getOrDefault("")
        return "$u?title=$t&body=$e"
    }

    private const val URL_HARD_CAP = 6000   // 实测：超过这个长度 GitHub 开始 500/502/414

    /**
     * GitHub 正文上限（用户实测：“Body can not be longer than 65536 characters”）。
     * 剪贴板那一块必须按【65536 − 预填正文已经占的位数 − 给用户自己写的余量】裁，
     * 否则用户一粘贴就报错，反而比不附日志更坑。
     */
    const val GITHUB_BODY_CAP = 65_536
    /**
     * 给用户自己写的余量（字符）：现象描述、复现步骤、来回追问都得有地方写，
     * 不能把正文塞到贴不动。8000 字符≈八千个汉字，足够写一大段；
     * 宁可少贴几百行旧日志（最新的行永远优先保留），也不让用户一粘贴就报错。
     */
    private const val USER_HEADROOM = 8_000

    /**
     * 剪贴板里放的东西：【能直接粘到 issue 正文末尾】的完整日志块，并且【保证粘完不超 65536】。
     *
     * 为什么不只放原文：预填正文里已经有 `## 运行状态` 与 `## 当日日志` 两个标题，
     * 粘一段无结构的原文进去会接不上（[Tsundoku]：堆一堆没人整理的内容只会让反馈更难读）。
     * 裁剪从【旧的行】开刀，最新的永远保留。
     */
    fun pasteBlock(text: String, prefilledBodyLength: Int): String {
        val budget = (GITHUB_BODY_CAP - prefilledBodyLength - USER_HEADROOM).coerceAtLeast(1_000)
        val lines = text.split('\n').filter { it.isNotBlank() }
        val logStart = lines.indexOfFirst { it.contains("行日志】") }
        val logs = if (logStart >= 0) lines.drop(logStart + 1) else lines

        val scaffold = "\n## 当日日志（完整，App 自动附上）\n```\n```\n"
        var used = scaffold.length
        val take = ArrayList<String>()
        for (l in logs.asReversed()) {
            val c = l.length + 1
            if (used + c > budget) break
            take.add(0, l)
            used += c
        }
        val skipped = (logs.size - take.size).coerceAtLeast(0)
        val headTail = if (skipped > 0) "（为不超 GitHub 正文上限，已省略最早 $skipped 行）" else ""
        return buildString {
            appendLine()
            appendLine("## 当日日志（完整，App 自动附上）$headTail")
            appendLine("```")
            take.forEach { appendLine(it) }
            if (take.isEmpty()) appendLine("（没装下任何日志行）")
            append("```")
        }
    }

    private fun qqVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(QQ_PKG, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName}（code $code）"
    }.getOrElse { if (it is PackageManager.NameNotFoundException) "未安装" else "读不到：${it.message?.take(40)}" }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
