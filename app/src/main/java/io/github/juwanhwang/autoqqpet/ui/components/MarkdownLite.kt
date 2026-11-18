package io.github.juwanhwang.autoqqpet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space

/**
 * 只认协议里用到的那点 Markdown：# 标题、> 引用、- / 1. 列表、``` 代码块、**粗体**、`代码`、---。
 * 文本本身不改一个字（同意指纹按原文算），这里只负责排版。
 */
@Composable
fun MarkdownLite(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parseBlocks(source) }
    val c = Aevum.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        blocks.forEach { b ->
            when (b) {
                is Block.H -> {
                    Spacer(Modifier.height(if (b.level <= 1) Space.xs else Space.md))
                    Text(
                        inline(b.text, c.ink, c.sunken),
                        style = if (b.level <= 1) AevumType.headline else AevumType.title,
                        color = c.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                is Block.P -> Text(inline(b.text, c.ink, c.sunken), style = AevumType.body, color = c.ink)
                is Block.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(c.accentSoft)
                        .padding(Space.md),
                ) {
                    Text(inline(b.text, c.ink, c.sunken), style = AevumType.caption, color = c.ink)
                }
                is Block.Item -> Row(Modifier.fillMaxWidth().padding(start = Space.xs)) {
                    Text(b.marker, style = AevumType.body, color = c.inkMuted, modifier = Modifier.width(22.dp))
                    Text(inline(b.text, c.ink, c.sunken), style = AevumType.body, color = c.ink, modifier = Modifier.weight(1f))
                }
                is Block.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(c.sunken)
                        .padding(Space.md),
                ) {
                    Text(b.text, style = AevumType.mono, color = c.ink)
                }
                Block.Rule -> Hairline(Modifier.padding(vertical = Space.sm))
            }
        }
    }
}

private sealed class Block {
    data class H(val level: Int, val text: String) : Block()
    data class P(val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class Item(val marker: String, val text: String) : Block()
    data class Code(val text: String) : Block()
    data object Rule : Block()
}

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    // 兜底：HTML 注释（维护说明）一律不显示——sync_eula.py 生成时已去掉，这里防手改 assets
    val lines = src.replace("\r\n", "\n").replace(Regex("<!--[\\s\\S]*?-->"), "").split('\n')
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.P(para.toString().trim())
        para.clear()
    }
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out += Block.Code(code.toString().trimEnd())
            }
            t.isEmpty() -> flush()
            t == "---" -> { flush(); out += Block.Rule }
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length
                out += Block.H(level, t.drop(level).trim())
            }
            t.startsWith(">") -> {
                flush()
                val q = StringBuilder(t.removePrefix(">").trim())
                while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) {
                    i++; q.append('\n').append(lines[i].trimStart().removePrefix(">").trim())
                }
                out += Block.Quote(q.toString())
            }
            t.startsWith("- ") -> { flush(); out += Block.Item("•", t.drop(2).trim()) }
            Regex("^\\d+\\. ").containsMatchIn(t) -> {
                flush()
                val n = t.substringBefore('.')
                out += Block.Item("$n.", t.substringAfter(". ").trim())
            }
            // 列表项的续行（缩进）并入上一项
            raw.startsWith("  ") && out.lastOrNull() is Block.Item && para.isEmpty() -> {
                val last = out.removeAt(out.lastIndex) as Block.Item
                out += last.copy(text = last.text + "\n" + t)
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(t)
            }
        }
        i++
    }
    flush()
    return out
}

/** 行内：**粗体** 与 `代码`。 */
private fun inline(text: String, ink: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) { append(text.substring(i)); i = text.length } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { append(text.substring(i)); i = text.length } else {
                        withStyle(SpanStyle(background = codeBg, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
