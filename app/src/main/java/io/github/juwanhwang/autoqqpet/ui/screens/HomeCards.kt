package io.github.juwanhwang.autoqqpet.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.juwanhwang.autoqqpet.pet.DailyReport
import io.github.juwanhwang.autoqqpet.pet.parse.Parsers
import io.github.juwanhwang.autoqqpet.ui.Copy
import io.github.juwanhwang.autoqqpet.ui.components.AevumCard
import io.github.juwanhwang.autoqqpet.ui.components.Glyphs
import io.github.juwanhwang.autoqqpet.ui.components.RollingNumber
import io.github.juwanhwang.autoqqpet.ui.components.SectionLabel
import io.github.juwanhwang.autoqqpet.ui.data.FullSnapshot
import io.github.juwanhwang.autoqqpet.ui.theme.Aevum
import io.github.juwanhwang.autoqqpet.ui.theme.AevumType
import io.github.juwanhwang.autoqqpet.ui.theme.Motion
import io.github.juwanhwang.autoqqpet.ui.theme.Radius
import io.github.juwanhwang.autoqqpet.ui.theme.Space
import io.github.juwanhwang.autoqqpet.ui.theme.reducedMotion
import kotlin.math.abs

/**
 * 主页的两张“账本”卡：今日收益（净变化 + 金币曲线 + 实收奖励）与昨日报告。
 * 数据全部来自后端完整快照；没有快照时整张卡不出现（不画假数据）。
 */

/** 收益卡主数字的口径：跟着当前目标走。 */
internal enum class EarningsHero { GOLD, POINTS, ATTR }

/**
 * 模式 → 主数字口径：最优调度按自动目标（金币/工分/属性）；老逻辑工作优先按工分、学习优先按属性；
 * 只冒险、排班等没有单一目标的模式保持金币。
 */
internal fun earningsHero(mode: String, autoGoal: String): EarningsHero = when {
    mode == "auto" -> when (autoGoal) { "points" -> EarningsHero.POINTS; "gold" -> EarningsHero.GOLD; else -> EarningsHero.ATTR }
    mode == "work" -> EarningsHero.POINTS
    mode == "mix" -> EarningsHero.ATTR
    else -> EarningsHero.GOLD
}

/**
 * 今日某项实发奖励的合计（工分、目标属性都从这里取）。
 * 汇总前先把每个已存名字过一遍与解析同套的归一（剥尾部运算符、折叠基础/奖励/额外变体），
 * 再按归一后的名字精确汇总——存量脏名（「工分 +」）与变体名（「基础魅力」）都能算进本体口径。
 */
internal fun rewardTotal(rewards: List<Pair<String, Long>>, name: String): Long {
    val target = Parsers.foldRewardVariant(name)
    return rewards.sumOf { (n, v) -> if (Parsers.cleanRewardName(n) == target) v else 0L }
}

/** 属性口径合计：指定了目标属性就只算它；没指定或选了「均衡」时是力量+智力+魅力三属性合计。 */
internal fun attrTotal(rewards: List<Pair<String, Long>>, targetAttr: String): Long =
    if (targetAttr.isBlank() || targetAttr == "均衡") {
        rewardTotal(rewards, "力量") + rewardTotal(rewards, "智力") + rewardTotal(rewards, "魅力")
    } else {
        rewardTotal(rewards, targetAttr)
    }

@Composable
internal fun EarningsCard(e: FullSnapshot.Earnings, hero: EarningsHero = EarningsHero.GOLD, targetAttr: String = "") {
    val c = Aevum.colors
    val up = e.goldDelta >= 0
    val goldTint = if (up) c.success else c.danger
    // 主数字：金币口径 = 净变化（可负）；工分/属性口径 = 今日实发合计（只增不减，恒为 +）
    val heroValue = when (hero) {
        EarningsHero.GOLD -> e.goldDelta
        EarningsHero.POINTS -> rewardTotal(e.rewards, "工分")
        // 没指定目标属性时退回三属性合计，别拿一个匹配不到任何奖励的口径显示假 +0
        EarningsHero.ATTR -> attrTotal(e.rewards, targetAttr)
    }
    val heroLabel = when (hero) {
        EarningsHero.GOLD -> "金币"
        EarningsHero.POINTS -> "工分"
        EarningsHero.ATTR -> targetAttr.ifBlank { "属性" }
    }
    val heroTint = if (hero == EarningsHero.GOLD) goldTint else c.success
    val heroDesc = when (hero) {
        EarningsHero.GOLD -> "今日金币净变化 ${if (up) "增加" else "减少"} ${abs(e.goldDelta)}"
        EarningsHero.POINTS -> "今日工分增加 $heroValue"
        EarningsHero.ATTR -> "今日$heroLabel 增加 $heroValue"
    }
    Column {
        SectionLabel("今日收益", trailing = {
            if (e.goldNow != null) Text("现有 ${e.goldNow} 金币", style = AevumType.label, color = c.inkMuted)
        })
        AevumCard(contentPadding = PaddingValues(Space.lg)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = heroDesc
                },
            ) {
                Text(if (heroValue >= 0) "+" else "−", style = AevumType.numeralXL, color = heroTint)
                RollingNumber(abs(heroValue), AevumType.numeralXL, heroTint)
                Spacer(Modifier.width(Space.xs))
                Text(heroLabel, style = AevumType.caption, color = c.inkMuted, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                buildString {
                    // 非金币口径时小字保留金币现状（今日变化 + 起步数），金币信息不丢
                    if (hero != EarningsHero.GOLD) append("今日金币 ${if (up) "+" else "−"}${abs(e.goldDelta)} · ")
                    append(if (e.goldStart != null) "今早 ${e.goldStart} 起步" else "从今天第一次心跳算起")
                    if (e.hireSpent > 0) append(" · 已含雇佣支出 ${e.hireSpent}")
                },
                style = AevumType.caption, color = c.inkMuted,
            )
            if (hero == EarningsHero.GOLD && e.curve.size >= 2) {
                Spacer(Modifier.height(Space.lg))
                GoldCurve(e.curve, goldTint, Modifier.fillMaxWidth().height(96.dp))
                Spacer(Modifier.height(Space.xs))
                Row(Modifier.fillMaxWidth()) {
                    Text(Copy.clock(e.curve.first().first), style = AevumType.micro, color = c.inkFaint, modifier = Modifier.weight(1f))
                    Text(Copy.clock(e.curve.last().first), style = AevumType.micro, color = c.inkFaint)
                }
            }
            // 实收明细滤掉心情/体力/清洁：那是暂时状态值、不是收益（记录照记，只是不展示）
            val shownRewards = e.rewards.filter { !DailyReport.isStatusReward(it.first) }
            if (shownRewards.isNotEmpty()) {
                Spacer(Modifier.height(Space.lg))
                Text("服务器实发", style = AevumType.label, color = c.inkMuted)
                Spacer(Modifier.height(Space.sm))
                RewardChips(shownRewards)
            }
        }
    }
}

/**
 * 属性与经验：三属性总量（服务器 0x9b62 文案里的真值）、战力 = 三者之和、今日经验进度。
 * 两样都没读到时整张卡不出现。[target] = 当前要练的属性（中文），那一格用强调色。
 */
@Composable
internal fun AttrsCard(attrs: FullSnapshot.Attrs?, exp: FullSnapshot.Exp?, target: String) {
    val c = Aevum.colors
    Column {
        SectionLabel("属性与经验", trailing = {
            if (attrs != null) Text("战力 ${attrs.power}", style = AevumType.label, color = c.inkMuted)
        })
        AevumCard(contentPadding = PaddingValues(Space.lg)) {
            if (attrs != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf("力量" to attrs.strength, "智力" to attrs.intel, "魅力" to attrs.charm).forEach { (k, v) ->
                        Stat(k, "$v", if (k == target) c.accent else c.ink, Modifier.weight(1f))
                    }
                    Stat("战力", "${attrs.power}", c.warning, Modifier.weight(1f))
                }
            }
            if (exp != null && exp.cap > 0) {
                if (attrs != null) Spacer(Modifier.height(Space.lg))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("今日经验", style = AevumType.label, color = c.inkMuted, modifier = Modifier.weight(1f))
                    Text("${exp.today} / ${exp.cap}", style = AevumType.bodyStrong, color = c.ink)
                }
                Spacer(Modifier.height(Space.sm))
                val f = (exp.today.toFloat() / exp.cap).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(c.sunken)
                        .semantics { contentDescription = "今日经验 ${exp.today}，上限 ${exp.cap}" },
                ) {
                    Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(50)).background(c.accent))
                }
                val notes = buildList {
                    if (exp.mult > 1.0) add("今日倍率 ×${"%.1f".format(exp.mult)}")
                    if (exp.selfCareFull) add("自己喂洗的经验已满")
                    exp.tasks.filter { it.cap in 1..9 }.forEach { add("${it.name.take(8)} ${it.done}/${it.cap}") }
                }
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(Space.sm))
                    Text(notes.joinToString(" · "), style = AevumType.caption, color = c.inkMuted)
                }
            }
        }
    }
}

/** 昨日报告：一句话总结 + 两排格子（次数/金币 + 实收涨幅）。 */
@Composable
internal fun YesterdayCard(r: FullSnapshot.DayReport) {
    val c = Aevum.colors
    val up = r.goldDelta >= 0
    Column {
        SectionLabel("昨天", trailing = { Text(r.day, style = AevumType.label, color = c.inkMuted) })
        AevumCard(contentPadding = PaddingValues(Space.lg)) {
            Text(headline(r), style = AevumType.bodyStrong, color = c.ink)
            Spacer(Modifier.height(Space.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Stat("学习", "${r.study}", c.study, Modifier.weight(1f), unit = "次")
                Stat("打工", "${r.work}", c.work, Modifier.weight(1f), unit = "次")
                Stat("冒险", "${r.adventure}", c.adventure, Modifier.weight(1f), unit = "次")
                Stat("金币", (if (up) "+" else "−") + abs(r.goldDelta), if (up) c.success else c.danger, Modifier.weight(1f))
            }
            // 第二排：昨日实收涨幅（魅力/智力/力量/工分），紧贴第一排正下方，口径固定、不随今日目标变；没有就是 +0
            Spacer(Modifier.height(Space.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Stat("魅力", "+${r.rewardGain("魅力")}", c.care, Modifier.weight(1f))
                Stat("智力", "+${r.rewardGain("智力")}", c.study, Modifier.weight(1f))
                Stat("力量", "+${r.rewardGain("力量")}", c.adventure, Modifier.weight(1f))
                Stat("工分", "+${r.rewardGain("工分")}", c.work, Modifier.weight(1f))
            }
            val extras = buildList {
                if (r.pk > 0) add("PK ${r.pk} 场赢 ${r.pkWin}")
                if (r.hire > 0) add("雇佣 ${r.hire} 次")
                if (r.friendCare > 0) add("帮好友 ${r.friendCare} 次")
                if (r.coinbag > 0) add("福袋 ${r.coinbag} 个")
            }
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(Space.md))
                Text(extras.joinToString(" · "), style = AevumType.caption, color = c.inkMuted)
            }
        }
    }
}

/** 昨日某项实收合计（按归一后的名字取：旧报告里的变体名、带尾部运算符的脏名都洗净后算进本体）。 */
private fun FullSnapshot.DayReport.rewardGain(name: String): Long =
    rewards.filter { Parsers.cleanRewardName(it.first) == name }.sumOf { it.second }

private fun headline(r: FullSnapshot.DayReport): String {
    val busy = r.studyWorkMin + r.adventureMin
    val sign = if (r.goldDelta >= 0) "赚了 ${r.goldDelta}" else "花了 ${-r.goldDelta}"
    return if (busy <= 0) "昨天基本在休息，金币$sign。" else "忙了 ${Copy.duration(busy)}，金币$sign。"
}

@Composable
private fun Stat(label: String, value: String, tint: Color, modifier: Modifier, unit: String? = null) {
    val c = Aevum.colors
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(c.sunken)
            .padding(horizontal = Space.sm, vertical = Space.md),
    ) {
        Text(label, style = AevumType.micro, color = c.inkMuted)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(value, style = AevumType.bodyStrong, color = tint, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.weight(1f))
                Text(unit, style = AevumType.micro, color = c.inkMuted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RewardChips(rewards: List<Pair<String, Long>>) {
    val c = Aevum.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        rewards.take(8).forEach { (name, n) ->
            val glyph = when {
                name.contains("金币") -> Glyphs.Coin
                name.contains("力量") || name.contains("智力") || name.contains("魅力") -> Glyphs.Sparkle
                else -> Glyphs.Check
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(c.sunken)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(glyph, null, tint = c.inkMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text("$name +$n", style = AevumType.label, color = c.ink)
            }
        }
    }
}

/**
 * 金币曲线：平滑折线 + 渐隐填充 + 终点呼吸点。首次出现时从左往右“画”出来（减少动效时直接成形）。
 */
@Composable
private fun GoldCurve(points: List<Pair<Long, Long>>, tint: Color, modifier: Modifier) {
    val c = Aevum.colors
    val reduced = reducedMotion
    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(points.size) {
        if (!reduced && reveal.value < 1f) reveal.animateTo(1f, tween(Motion.LONG * 2, easing = Motion.Emphasized))
    }
    val t0 = points.first().first
    val t1 = points.last().first.coerceAtLeast(t0 + 1)
    val g0 = points.minOf { it.second }
    val g1 = points.maxOf { it.second }.coerceAtLeast(g0 + 1)
    Box(modifier.semantics { contentDescription = "今日金币曲线，从 ${points.first().second} 到 ${points.last().second}" }) {
        Canvas(Modifier.matchParentSize()) {
            val pad = 6.dp.toPx()
            fun at(p: Pair<Long, Long>) = Offset(
                pad + (size.width - 2 * pad) * ((p.first - t0).toFloat() / (t1 - t0)),
                pad + (size.height - 2 * pad) * (1f - (p.second - g0).toFloat() / (g1 - g0)),
            )
            val pts = points.map(::at)
            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    val mx = (a.x + b.x) / 2
                    cubicTo(mx, a.y, mx, b.y, b.x, b.y)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(pts.last().x, size.height)
                lineTo(pts.first().x, size.height)
                close()
            }
            // 基线
            drawLine(c.line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            clipRect(right = size.width * reveal.value) {
                drawPath(fill, Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0f))))
                drawPath(line, tint, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (reveal.value >= 0.999f) {
                val end = pts.last()
                drawCircle(tint.copy(alpha = 0.18f), 9.dp.toPx(), end)
                drawCircle(tint, 4.dp.toPx(), end)
            }
        }
    }
}
