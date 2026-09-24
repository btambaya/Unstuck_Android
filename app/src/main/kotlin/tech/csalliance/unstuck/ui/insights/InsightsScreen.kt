package tech.csalliance.unstuck.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.COMEBACK_LABELS
import tech.csalliance.unstuck.core.logic.CalibrationDot
import tech.csalliance.unstuck.core.logic.DEFAULT_AREAS
import tech.csalliance.unstuck.core.logic.INTERRUPTIONS_MIN_LINKED
import tech.csalliance.unstuck.core.logic.InsightsSpan
import tech.csalliance.unstuck.core.logic.NO_AREA_LABEL
import tech.csalliance.unstuck.core.logic.PeriodData
import tech.csalliance.unstuck.core.logic.PeriodRange
import tech.csalliance.unstuck.core.logic.PeriodWindow
import tech.csalliance.unstuck.core.logic.calibrationDots
import tech.csalliance.unstuck.core.logic.calibrationHitRate
import tech.csalliance.unstuck.core.logic.captureBreakdown
import tech.csalliance.unstuck.core.logic.comebackBins
import tech.csalliance.unstuck.core.logic.earliestActivityDay
import tech.csalliance.unstuck.core.logic.hourDayHeatmap
import tech.csalliance.unstuck.core.logic.hourLabel
import tech.csalliance.unstuck.core.logic.inPeriodWindow
import tech.csalliance.unstuck.core.logic.insightsComparisonLabel
import tech.csalliance.unstuck.core.logic.insightsFacts
import tech.csalliance.unstuck.core.logic.insightsPeriodLabel
import tech.csalliance.unstuck.core.logic.insightsRange
import tech.csalliance.unstuck.core.logic.interruptionBins
import tech.csalliance.unstuck.core.logic.localToday
import tech.csalliance.unstuck.core.logic.pauseAnatomy
import tech.csalliance.unstuck.core.logic.periodDur
import tech.csalliance.unstuck.core.logic.periodFmtDay
import tech.csalliance.unstuck.core.logic.periodMinutes
import tech.csalliance.unstuck.core.logic.periodParseYmd
import tech.csalliance.unstuck.core.logic.slipping
import tech.csalliance.unstuck.core.logic.topInsights
import tech.csalliance.unstuck.core.logic.weekdayAreaHours
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdSegment
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.StatCard
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

// Insights — Report (this period's story) and Deep dive (patterns). Every
// number comes from the shared periodFacts engine (core/logic/PeriodFacts.kt)
// over the D1-filtered sessions, so it equals what the assistant's
// get_period_review / get_insights say for the same period (analytics fixes
// 2026-09-24). Observations, never a score: no streaks, neutral deltas.

private const val SLIP_SHOWN = 8

@Composable
fun InsightsScreen(vm: AppViewModel, deep: Boolean, onBack: () -> Unit, onToggleDeep: (Boolean) -> Unit) {
    val c = UTheme.colors
    val allSessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val lifeAreas by vm.lifeAreas.collectAsStateWithLifecycle()
    val allCaptures by vm.captures.collectAsStateWithLifecycle()
    val allReasons by vm.reasonLogs.collectAsStateWithLifecycle()
    var span by remember { mutableStateOf(InsightsSpan.WEEK) }
    var offset by remember { mutableIntStateOf(0) }
    // A one-shot period to open on (the Today pill's "Last week", a deep link).
    LaunchedEffect(Unit) { vm.consumeInsightsOpenAt()?.let { (s, o) -> span = s; offset = o } }
    // The window rolls over at midnight / Monday on a screen left open.
    var now by remember { mutableLongStateOf(vm.nowMs()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); now = vm.nowMs() } }

    val zone = remember { ZoneId.systemDefault() }
    val data = remember(tasks, blocks, allSessions, allCaptures, allReasons) { PeriodData(tasks, blocks, allSessions, allCaptures, allReasons) }
    val today = localToday(now, zone)
    val earliest = remember(data) { earliestActivityDay(data, zone) }
    val range = remember(span, offset, today, earliest) { insightsRange(span, offset, today, earliest) }
    val facts = remember(data, range, now) { insightsFacts(data, range, now, zone) }

    // The window's rows, already filtered (sessions: counted ones; the heatmap
    // takes the raw ones so it can place each session from its real start).
    val sessions = facts.cur.sessions
    val captures = facts.cur.captures
    val reasons = facts.cur.pauses
    val rawSessions = remember(allSessions, range, now) {
        val win = windowOf(range, now, zone)
        allSessions.filter { inPeriodWindow(it.completedAt, win, zone) }
    }
    val hasFocus = sessions.isNotEmpty()
    val areaNames = remember(lifeAreas) { lifeAreas.sortedBy { it.sortOrder }.map { it.name }.ifEmpty { DEFAULT_AREAS } }

    val dots = remember(sessions, tasks) { calibrationDots(sessions, tasks) }
    val hit = remember(dots) { if (dots.isNotEmpty()) (calibrationHitRate(dots) * 100).roundToInt() else 0 }
    val slips = remember(tasks, now) { slipping(tasks, now) }
    val comeback = remember(reasons) { comebackBins(reasons) }
    val periodName = insightsPeriodLabel(range, today)

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            item {
                SectionLabel("REFLECTION · ${periodName.uppercase()}", color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
                Text(if (deep) "Let's look closer. Calmly." else "Observations, not a score.", style = UFont.serifItalic(28), color = c.ink, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 12.dp)) { MdSegment(listOf("Report", "Deep dive"), if (deep) "Deep dive" else "Report") { onToggleDeep(it == "Deep dive") } }
                PeriodSelector(
                    span = span,
                    range = range,
                    today = today,
                    canBack = span != InsightsSpan.ALL && earliest != null && earliest < range.from,
                    canForward = span != InsightsSpan.ALL && offset < 0,
                    onSpan = { span = it; offset = 0 },
                    onBack = { offset -= 1 },
                    onForward = { offset = minOf(0, offset + 1) },
                )
            }

            if (facts.isEmpty) item {
                EmptyPeriodNote(
                    periodName = periodName,
                    current = range.clipped,
                    month = span == InsightsSpan.MONTH,
                    hasHistory = earliest != null && earliest < range.from,
                    // From the current week/month, offer the one before as a link.
                    previous = if (offset == 0 && span != InsightsSpan.ALL) {
                        val prev = insightsFacts(data, insightsRange(span, -1, today, earliest), now, zone)
                        if (prev.isEmpty) null else (if (span == InsightsSpan.WEEK) "Last week" else insightsPeriodLabel(prev.range, today)) +
                            ": ${prev.doneCount} done · ${periodDur(periodMinutes(prev.focusSec))} focused"
                    } else null,
                    onPrevious = { offset = -1 },
                )
            }

            if (!deep) {
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Estimates", if (dots.isNotEmpty()) "$hit%" else "—", "${dots.size} tracked", c.greenSoft, c.greenInk, "landed within 5 min")
                        StatCard("Focus sessions", "${sessions.size}", "${captures.size} captures", c.blueSoft, c.blueInk, "a minute or longer, this period")
                        StatCard("Gentle friction", "${slips.size} tasks", if (slips.isEmpty()) "All clear." else "Watch these", if (slips.isEmpty()) c.greenSoft else c.amberSoft, if (slips.isEmpty()) c.greenInk else c.amberInk, "slipping")
                    }
                }
                if (hasFocus) {
                    item {
                        val weekdayBars = remember(sessions, tasks, areaNames) { weekdayAreaHours(sessions, tasks, areaNames, withNoArea = true).map { it.d to it.data } }
                        StackedBars("When focus happens", weekdayBars, areaNames + NO_AREA_LABEL, lifeAreas)
                    }
                    if (dots.isNotEmpty()) item { CalibrationScatter(dots, hit) }
                    item {
                        val interruptions = remember(captures, sessions) { interruptionBins(captures, sessions) }
                        // Fewer than 3 captures linked to a session say nothing (P0-11).
                        if (interruptions.sum() >= INTERRUPTIONS_MIN_LINKED) {
                            Histogram(
                                "When interruptions happen", interruptions, c.ink3,
                                labels = List(interruptions.size) { i -> if (i % 3 == 0) "${i * 3}m" else "" }.let { it.dropLast(1) + "27m+" },
                                noun = "captures",
                            )
                        }
                    }
                    item {
                        val insights = remember(sessions, tasks, captures, reasons, now) { topInsights(sessions, tasks, captures, reasons, now) }
                        if (insights.isNotEmpty()) {
                            SectionLabel("Worth noticing", Modifier.padding(top = 18.dp, bottom = 6.dp))
                            insights.forEach { ins ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), radius = 14) {
                                    Column { Text(ins.title, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink); Text(ins.sub, style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 4.dp)) }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    // Median over counted SECONDS (round once at display).
                    val median = remember(sessions) {
                        val secs = sessions.map { it.actualSec }.sorted()
                        if (secs.isEmpty()) 0 else periodMinutes(secs[secs.size / 2])
                    }
                    val timed = comeback.sum()
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Focused", periodDur(periodMinutes(facts.focusSec)), caption = "in ${sessions.size} session${if (sessions.size == 1) "" else "s"}", modifier = Modifier.weight(1f))
                            StatCard("Median", if (hasFocus) periodDur(median) else "—", caption = "per session", modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Back <5m", if (timed > 0) "${(comeback[0] * 100.0 / timed).roundToInt()}%" else "—", caption = "of timed pauses", modifier = Modifier.weight(1f))
                            StatCard("Captures", "${captures.size}", caption = "kept this period", modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    val pauses = remember(reasons) { pauseAnatomy(reasons) }
                    if (pauses.isNotEmpty()) {
                        SectionLabel("What pauses you", Modifier.padding(top = 18.dp, bottom = 6.dp))
                        Card(Modifier.fillMaxWidth(), radius = 14) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Real pause lengths when any were measured; else a count axis
                                // (web parity) — never a row of "0m" slivers (P0-3).
                                val byMinutes = pauses.any { it.minutes > 0 }
                                val max = if (byMinutes) pauses.maxOf { it.minutes }.coerceAtLeast(0.001) else pauses.maxOf { it.count }.toDouble().coerceAtLeast(1.0)
                                pauses.forEach { p ->
                                    val v = if (byMinutes) p.minutes else p.count.toDouble()
                                    LabeledBar(p.reason, (v / max).toFloat(), if (byMinutes) "${p.minutes.roundToInt()}m · ${p.count}×" else "${p.count}×", c.ink2)
                                }
                                if (!byMinutes) Text("Pause lengths fill in as you resume from a pause.", style = UFont.sans(11), color = c.ink3)
                            }
                        }
                    }
                }
                item {
                    if (comeback.sum() > 0) {
                        Histogram("How fast you come back", comeback, c.ink2, labels = COMEBACK_LABELS, noun = "pauses",
                            caption = "Time from pausing to picking it back up.")
                    } else if (reasons.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("How fast you come back", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                                Text("Starts filling in once you resume from a pause — the time away is measured from then on.", style = UFont.sans(12), color = c.ink3)
                            }
                        }
                    }
                }
                item {
                    val breakdown = remember(captures) { captureBreakdown(captures) }
                    if (captures.isNotEmpty()) {
                        SectionLabel("Captures by kind", Modifier.padding(top = 18.dp, bottom = 6.dp))
                        Card(Modifier.fillMaxWidth(), radius = 14) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val max = breakdown.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                                breakdown.entries.forEach { (tag, n) -> LabeledBar(tag.name.lowercase().replace('_', '-'), n.toFloat() / max, "$n", c.primary) }
                            }
                        }
                    }
                }
                if (slips.isNotEmpty()) {
                    item {
                        SectionLabel("The slip detector · ${slips.size}", Modifier.padding(top = 18.dp, bottom = 6.dp))
                        slips.take(SLIP_SHOWN).forEach { s ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), radius = 12) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(s.name, style = UFont.sans(13, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                                    Text("${s.moveCount}× · ${s.weeks}w", style = UFont.mono(11), color = c.ink3)
                                }
                            }
                        }
                        if (slips.size > SLIP_SHOWN) Text("+${slips.size - SLIP_SHOWN} more", style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (hasFocus) item { Heatmap(remember(rawSessions, zone) { hourDayHeatmap(rawSessions, zone) }) }
            }
            item { Box(Modifier.padding(24.dp)) {} }
        }
    }
}

/** The window a range's facts were read with (the same cut rule). */
private fun windowOf(r: PeriodRange, nowMs: Long, zone: ZoneId): PeriodWindow {
    val z = Instant.ofEpochMilli(nowMs).atZone(zone)
    return PeriodWindow(r.from, r.end, if (r.clipped) z.hour * 60 + z.minute else null)
}

/** Week | Month | All time, then ‹ This week · 21–27 Sep (so far) › and the
 *  comparison caption. › stops at the current period; ‹ at the first activity. */
@Composable
private fun PeriodSelector(
    span: InsightsSpan,
    range: PeriodRange,
    today: String,
    canBack: Boolean,
    canForward: Boolean,
    onSpan: (InsightsSpan) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val c = UTheme.colors
    val names = mapOf(InsightsSpan.WEEK to "Week", InsightsSpan.MONTH to "Month", InsightsSpan.ALL to "All time")
    Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        MdSegment(names.values.toList(), names.getValue(span)) { pick -> names.entries.first { it.value == pick }.key.let(onSpan) }
        if (span != InsightsSpan.ALL) {
            val year = periodParseYmd(today)!!.year
            val dates = if (range.from == range.end) periodFmtDay(range.from, year) else "${periodFmtDay(range.from, year)} – ${periodFmtDay(range.end, year)}"
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepArrow("‹", "Previous ${if (span == InsightsSpan.WEEK) "week" else "month"}", canBack, onBack)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(insightsPeriodLabel(range, today), style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
                    Text(dates + if (range.clipped) " · so far" else "", style = UFont.sans(11), color = c.ink3)
                    insightsComparisonLabel(range, today)?.let { Text(it, style = UFont.sans(11), color = c.ink3) }
                }
                StepArrow("›", "Next ${if (span == InsightsSpan.WEEK) "week" else "month"}", canForward, onForward)
            }
        } else {
            Text("Everything since ${periodFmtDay(range.from, periodParseYmd(today)!!.year)}", style = UFont.sans(11), color = c.ink3, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun StepArrow(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = UTheme.colors
    Box(
        Modifier.minimumInteractiveComponentSize().size(40.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = UFont.sans(22), color = if (enabled) c.ink else c.line2)
    }
}

/** Honest empty state: never "No focus sessions yet" to someone with history
 *  (cross-check P0-8). With history, point at the period before. */
@Composable
private fun EmptyPeriodNote(periodName: String, current: Boolean, month: Boolean, hasHistory: Boolean, previous: String?, onPrevious: () -> Unit) {
    val c = UTheme.colors
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), radius = 14) {
        Column {
            if (hasHistory) {
                val name = if (periodName.startsWith("This ") || periodName.startsWith("Last ")) periodName.replaceFirstChar { it.lowercase() } else periodName
                val headline = when {
                    current -> "Nothing logged $name yet."
                    month -> "Nothing logged in $name."
                    else -> "Nothing logged $name."
                }
                Text(headline, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                Text("That's fine — it fills in as you tick things off and focus.", style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                if (previous != null) {
                    Text(
                        "$previous →", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink,
                        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onPrevious).padding(vertical = 4.dp),
                    )
                }
            } else {
                Text("Nothing logged yet.", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                Text("Your reflection fills in here as you tick things off and focus.", style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StackedBars(title: String, bars: List<Pair<String, List<Double>>>, areas: List<String>, lifeAreas: List<tech.csalliance.unstuck.core.model.LifeArea>) {
    val c = UTheme.colors
    val max = bars.maxOfOrNull { it.second.sum() }?.coerceAtLeast(0.001) ?: 0.001
    // "No area" (the last series) is ink4 — never an area's colour.
    fun colorOf(i: Int): Color = if (areas.getOrNull(i) == NO_AREA_LABEL) c.ink4 else tech.csalliance.unstuck.ui.components.areaColorFor(areas.getOrElse(i) { "" }, lifeAreas, c)
    val totals = areas.indices.map { i -> bars.sumOf { it.second.getOrElse(i) { 0.0 } } }
    // Spoken summary so screen-reader users get the per-day totals the bars encode.
    val a11y = "$title. " + bars.joinToString("; ") { (day, data) ->
        val m = (data.sum() * 60).roundToInt()
        "$day ${periodDur(m)}"
    }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            bars.forEach { (day, data) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day, style = UFont.mono(10), color = c.ink3, modifier = Modifier.width(30.dp))
                    Row(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(c.bg2)) {
                        data.forEachIndexed { i, v ->
                            val frac = (v / max).toFloat().coerceIn(0f, 1f)
                            if (frac > 0f) Box(Modifier.weight(frac.coerceAtLeast(0.0001f), fill = true).fillMaxHeight().background(colorOf(i)))
                        }
                        val rest = 1f - (data.sum() / max).toFloat().coerceIn(0f, 1f)
                        if (rest > 0f) Spacer(Modifier.weight(rest))
                    }
                }
            }
            // Only series with time in this period; wraps on a narrow phone.
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                areas.forEachIndexed { i, a ->
                    if (totals[i] > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.height(8.dp).width(8.dp).clip(RoundedCornerShape(2.dp)).background(colorOf(i)))
                        Text(a, style = UFont.sans(9), color = c.ink3)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationScatter(dots: List<CalibrationDot>, hitPct: Int) {
    val c = UTheme.colors
    // Square the axes off a shared max so the y=x reference reads as a true
    // 45° "perfect estimate" line (web parity — components/analytics/report.tsx).
    val maxVal = (listOf(70) + dots.flatMap { listOf(it.e, it.a) }).max()
    // Scatter is a Canvas with no inherent semantics — describe the key numbers.
    val a11y = "Estimate calibration scatter. ${dots.size} sessions; " +
        "$hitPct% landed within 5 minutes of the estimate."
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Estimate calibration", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text("$hitPct% of ${dots.size} recent sessions landed within 5 min of the task's estimate.", style = UFont.sans(11), color = c.ink3)
            Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp)) {
                val pad = 8.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                fun px(v: Int) = pad + (v.toFloat() / maxVal) * w
                fun py(v: Int) = (size.height - pad) - (v.toFloat() / maxVal) * h
                drawLine(c.line2, Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad), 1.dp.toPx())
                drawLine(c.line2, Offset(pad, pad), Offset(pad, size.height - pad), 1.dp.toPx())
                drawLine(c.line2, Offset(px(0), py(0)), Offset(px(maxVal), py(maxVal)), 1.dp.toPx())
                dots.forEach { d ->
                    val within = abs(d.e - d.a) <= 5
                    drawCircle(
                        if (within) c.green else c.coral,
                        4.dp.toPx(),
                        Offset(px(d.e.coerceAtMost(maxVal)), py(d.a.coerceAtMost(maxVal))),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("→ estimate (0–${maxVal}m)", style = UFont.mono(9), color = c.ink3)
                Text("↑ actual", style = UFont.mono(9), color = c.ink3)
            }
        }
    }
}

/** Bars with an x label under each (blank labels are skipped). */
@Composable
private fun Histogram(title: String, bins: List<Int>, color: Color, labels: List<String>, noun: String, caption: String? = null) {
    val c = UTheme.colors
    val max = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
    val a11y = "$title. ${bins.sum()} $noun: " + bins.indices.joinToString(", ") { i -> "${labels.getOrElse(i) { "" }.ifEmpty { "bin ${i + 1}" }} ${bins[i]}" }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            if (caption != null) Text(caption, style = UFont.sans(11), color = c.ink3)
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                bins.forEach { v ->
                    val frac = (v.toFloat() / max).coerceIn(0.02f, 1f)
                    Box(Modifier.weight(1f).fillMaxHeight(frac).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (v > 0) color else c.bg2))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                bins.indices.forEach { i ->
                    Text(labels.getOrElse(i) { "" }, style = UFont.mono(9), color = c.ink3, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LabeledBar(label: String, frac: Float, value: String, color: Color) {
    val c = UTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = UFont.sans(12), color = c.ink2)
            Text(value, style = UFont.mono(10), color = c.ink3)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)).background(c.bg2)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(color))
        }
    }
}

/** 7 days × 24 hours, by the hours each session spanned (P0-2). Ink scale. */
@Composable
private fun Heatmap(grid: List<List<Double>>) {
    val c = UTheme.colors
    val max = (grid.flatten().maxOrNull() ?: 0.0).coerceAtLeast(0.001)
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val a11y = run {
        val dayTotals = grid.mapIndexed { i, row -> days[i] to row.sum() }
        val busiest = dayTotals.maxByOrNull { it.second }
        var peakH = -1; var peakV = 0.0
        for (h in 0 until 24) { val v = grid.sumOf { it[h] }; if (v > peakV) { peakV = v; peakH = h } }
        "Focus by hour and weekday. ${periodDur(grid.flatten().sum().roundToInt())} in all" +
            (busiest?.takeIf { it.second > 0 }?.let { ", busiest on ${it.first}" } ?: "") +
            (if (peakH >= 0) ", most around ${hourLabel(peakH)}" else "") + "."
    }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Hour × day", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text("When your focus actually ran, this period.", style = UFont.sans(11), color = c.ink3, modifier = Modifier.padding(bottom = 4.dp))
            grid.forEachIndexed { d, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(days[d], style = UFont.sans(10), color = c.ink3, modifier = Modifier.width(28.dp))
                    row.forEach { v ->
                        val t = (v / max).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(2.dp)).background(if (v <= 0.0) c.bg2 else lerp(c.line2, c.ink, 0.15f + 0.85f * t)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(28.dp))
                listOf(0, 6, 12, 18).forEach { h ->
                    Text(hourLabel(h), style = UFont.mono(9), color = c.ink3, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
