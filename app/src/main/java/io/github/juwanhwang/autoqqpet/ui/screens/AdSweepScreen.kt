package io.github.juwanhwang.autoqqpet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.pet.AdSweepState
import io.github.juwanhwang.autoqqpet.ui.components.AevumButton
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.ButtonTone
import io.github.juwanhwang.autoqqpet.ui.components.Page
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.settings.SettingsRegistry
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Space

/** 历史广告补领：进度看这里，退出页面后仍在后台继续。 */
@Composable
fun AdSweepScreen(live: LiveState, onBack: () -> Unit) {
    val p = live.adSweep
    val c = Aevum.colors
    LaunchedEffect(Unit) { live.backend.queryAdSweep() }

    Page("历史广告", onBack = onBack, subtitle = "结算广告奖励补领") {
        item(key = "limit") {
            Spacer(Modifier.height(Space.md))
            SettingsCard(live, SettingsRegistry.adSweepPage, note = {})
        }
        item(key = "progress") {
            Spacer(Modifier.height(Space.md))
            AevumCard {
                Text(headline(p), style = AevumType.title, color = c.ink)
                Spacer(Modifier.height(Space.md))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(c.line),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p.fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(c.accent),
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Text(summary(p), style = AevumType.body, color = c.inkMuted)
                if (p.running && p.total > 0 && p.current.isNotEmpty()) {
                    Spacer(Modifier.height(Space.xs))
                    Text(p.current, style = AevumType.caption, color = c.inkMuted)
                }
                Spacer(Modifier.height(Space.lg))
                AevumButton(
                    text = when {
                        p.running -> "补领中…"
                        p.endedAt > 0L -> "再次补领"
                        else -> "开始补领"
                    },
                    onClick = { live.backend.startAdSweep() },
                    enabled = !p.running,
                )
                if (p.running) {
                    Spacer(Modifier.height(Space.sm))
                    AevumButton(
                        text = "取消补领",
                        onClick = { live.backend.cancelAdSweep() },
                        tone = ButtonTone.SECONDARY,
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    "补领会按正常流程加载并播放广告后再上报；跳过广告播放属于伪造曝光，可能触发账号风控或封禁，请自行评估风险。",
                    style = AevumType.caption, color = c.inkMuted,
                )
            }
        }
    }
}

private fun headline(p: AdSweepState.Progress): String = when {
    p.running && p.total <= 0 -> if (p.scanned > 0) "正在扫描 · 已扫 ${p.scanned} 条" else "正在扫描"
    p.running -> "补领中 ${p.done}/${p.total}"
    p.endedAt > 0L -> "已完成"
    else -> "未开始"
}

private fun summary(p: AdSweepState.Progress): String = when {
    p.running && p.total <= 0 ->
        (if (p.scanAt > 0) "正从 ${dayStamp(p.scanAt)} 之前的记录往更早扫" else "正在读取《出门记录》") +
            (if (p.pending > 0) " · 已找到 ${p.pending} 条" else "")
    p.running -> "成功 ${p.ok} · 失败 ${p.failed}" +
        (if (p.skippedDone > 0) " · 跳过已领 ${p.skippedDone}" else "") +
        (if (p.pending > 0) " · 已找到待补 ${p.pending} 条" else "")
    p.endedAt > 0L -> buildString {
        if (p.stop.isNotEmpty()) append(p.stop)
        val parts = mutableListOf<String>()
        if (p.scanned > 0) parts += "本轮扫描 ${p.scanned} 条"
        if (p.scanned > 0 && p.scanAt > 0) parts += "扫到 ${dayStamp(p.scanAt)}"
        if (p.skippedDone > 0) parts += "跳过已领 ${p.skippedDone} 条"
        parts += "成功 ${p.ok} 条"
        if (p.failed > 0) parts += "失败 ${p.failed} 条"
        append("（" + parts.joinToString("，") + "）")
        if (p.pending > 0) append(" · 还有 ${p.pending} 条已找到没补，再次补领会先补它们")
    }
    else -> "从《出门记录》补领未领取的结算广告奖励"
}

private fun dayStamp(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))