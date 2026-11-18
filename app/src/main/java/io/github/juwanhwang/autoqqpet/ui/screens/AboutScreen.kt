package io.github.juwanhwang.autoqqpet.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.BuildConfig
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.EulaTerms
import io.github.juwanhwang.autoqqpet.ui.Route
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.Hairline
import io.github.juwanhwang.autoqqpet.ui.components.ListRow
import io.github.juwanhwang.autoqqpet.ui.components.MarkdownLite
import io.github.juwanhwang.autoqqpet.ui.components.Page
import io.github.juwanhwang.autoqqpet.ui.components.PetGlyph
import io.github.juwanhwang.autoqqpet.ui.components.PetMood
import io.github.juwanhwang.autoqqpet.ui.components.Pill
import io.github.juwanhwang.autoqqpet.ui.components.SectionLabel
import io.github.juwanhwang.autoqqpet.ui.data.Backend
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Space

/** 关于：它是什么、它承诺什么、去哪找它。 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val c = Aevum.colors
    val context = LocalContext.current
    Page(title = "关于", onBack = onBack) {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(top = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Glow(c.accent, Modifier.size(200.dp))
                    PetGlyph(PetMood.AWAKE, body = c.surface, face = c.ink, leaf = c.adventure, modifier = Modifier.size(112.dp))
                }
                Spacer(Modifier.height(Space.md))
                Text("Autoqqpet", style = AevumType.headline, color = c.ink)
                Text("QQ 宠物自动托管 · ${BuildConfig.VERSION_NAME}", style = AevumType.caption, color = c.inkMuted)
                Spacer(Modifier.height(Space.md))
                Pill(Copy.NOT_TENCENT, c.inkMuted)
            }
        }
        item(key = "promises_label") { SectionLabel("承诺") }
        item(key = "promises") {
            AevumCard(contentPadding = PaddingValues(0.dp)) {
                ListRow("免费", summary = "没有内购、会员、广告。收费的都不是本项目。", glyph = Glyphs.Coin, glyphTint = c.success)
                Hairline(inset = 62.dp)
                ListRow("只在本机", summary = "不申请网络权限，不采集、不上传。宠物请求由 QQ 自己发往腾讯。", glyph = Glyphs.Lock, glyphTint = c.accent)
                Hairline(inset = 62.dp)
                ListRow("不抢屏幕", summary = "不在后台把 QQ 拉到前台，没有悬浮窗。需要你动手时只发通知。", glyph = Glyphs.Shield, glyphTint = c.adventure)
            }
        }
        item(key = "links_label") { SectionLabel("链接") }
        item(key = "links") {
            AevumCard(contentPadding = PaddingValues(0.dp)) {
                ListRow("项目主页", summary = "下载、更新日志、使用说明", glyph = Glyphs.External, onClick = { open(context, Backend.PROJECT_URL) })
                Hairline(inset = 62.dp)
                if (Backend.GROUP_URL.isNotEmpty()) {
                    ListRow("Telegram 交流群", summary = "提问、反馈、更新消息", glyph = Glyphs.Users, onClick = { open(context, Backend.GROUP_URL) })
                    Hairline(inset = 62.dp)
                }
                ListRow("反馈问题", summary = "带上运行快照，更快定位", glyph = Glyphs.Chat, onClick = { onOpen(Route.DIAGNOSTICS) })
                Hairline(inset = 62.dp)
                ListRow("许可协议", summary = "用户许可协议；源码按 PolyForm Noncommercial 1.0.0 公开", glyph = Glyphs.Lock, onClick = { onOpen(Route.LICENSE) })
            }
        }
        item(key = "fine") {
            Text(
                "「QQ」「QQ 宠物」及相关标识归腾讯所有。本项目是个人作品，与腾讯没有任何关联，也未获授权或背书。\n使用即表示你了解并接受非官方自动化的风险。",
                style = AevumType.caption, color = c.inkMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Space.xxl, start = Space.md, end = Space.md),
            )
        }
    }
}

/** 协议全文（只读）。同意流程在首启引导里；这里展示已同意的版本指纹。 */
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val c = Aevum.colors
    val context = LocalContext.current
    val text = remember { EulaTerms.text(context) }
    val agreed = remember { EulaTerms.agreed(context) }
    val digest = remember { EulaTerms.digest(context).take(12) }
    Page(title = "许可协议", onBack = onBack, subtitle = if (agreed) "你已同意此版本 · 指纹 $digest" else "此版本尚未同意") {
        item(key = "body") {
            AevumCard(modifier = Modifier.padding(top = Space.lg), contentPadding = PaddingValues(Space.xl)) {
                MarkdownLite(text)
            }
        }
    }
}
