package tech.csalliance.unstuck.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.DEFAULT_AREAS
import tech.csalliance.unstuck.core.logic.CalibrationDot
import tech.csalliance.unstuck.core.logic.calibrationDots
import tech.csalliance.unstuck.core.logic.calibrationHitRate
import tech.csalliance.unstuck.core.logic.captureBreakdown
import tech.csalliance.unstuck.core.logic.interruptionBins
import tech.csalliance.unstuck.core.logic.pauseAnatomy
import tech.csalliance.unstuck.core.logic.reEntryDistribution
import tech.csalliance.unstuck.core.logic.slipping
import tech.csalliance.unstuck.core.logic.timeOfDayHeatmap
import tech.csalliance.unstuck.core.logic.topInsights
import tech.csalliance.unstuck.core.logic.weekdayAreaHours
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdSegment
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.StatCard
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(vm: AppViewModel, deep: Boolean, onBack: () -> Unit, onToggleDeep: (Boolean) -> Unit) {
    val c = UTheme.colors
    val allSessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val lifeAreas by vm.lifeAreas.collectAsStateWithLifecycle()
    val allCaptures by vm.captures.collectAsStateWithLifecycle()
    val allReasons by vm.reasonLogs.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf("Week") }   // Week | Month | All

    val now = vm.nowMs()
    // Calendar-anchored windows (web parity): week = since Monday 00:00, month = since the 1st.
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.LocalDate.now(zone)
    val cutoff = when (range) {
        "Week" -> day.minusDays(((day.dayOfWeek.value + 6) % 7).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        "Month" -> day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        else -> 0L
    }
    // Window filters memoized on the source list + cutoff so re-bucketing only
    // happens when the data or the selected range actually changes.
    val sessions = remember(allSessions, cutoff) { allSessions.filter { (Time.parseMillis(it.completedAt) ?: 0L) >= cutoff } }
    val captures = remember(allCaptures, cutoff) { allCaptures.filter { (Time.parseMillis(it.at) ?: 0L) >= cutoff } }
    val reasons = remember(allReasons, cutoff) { allReasons.filter { (Time.parseMillis(it.at) ?: 0L) >= cutoff } }
    // Show real numbers + charts from the FIRST session (kept calm) instead of
    // blanking everything to "—" until 5. The qualitative "Worth noticing"
    // insights keep a small floor of their own (REAL_DATA_THRESHOLD) so a single
    // session can't claim a "strongest day".
    val enough = sessions.isNotEmpty()

    val dots = remember(sessions, tasks) { calibrationDots(sessions, tasks) }
    val hit = remember(dots) { if (dots.isNotEmpty()) (calibrationHitRate(dots) * 100).roundToInt() else 0 }
    val slips = remember(tasks, now) { slipping(tasks, now) }
    val totalMin = remember(sessions) { sessions.sumOf { it.actualSec } / 60 }
    // Computed once (was run twice: the deep-dive "Re-entry <5m" stat + the
    // "How fast you come back" histogram). Shared across both deep-dive items.
    val reEntry = remember(sessions) { reEntryDistribution(sessions) }
    val rangeLabel = range.uppercase().let { if (it == "ALL") "ALL TIME" else it }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            item {
                SectionLabel("REFLECTION · $rangeLabel", color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
                Text(if (deep) "Let's look closer. Calmly." else "Observations, not a score.", style = UFont.serifItalic(28), color = c.ink, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 12.dp)) { MdSegment(listOf("Report", "Deep dive"), if (deep) "Deep dive" else "Report") { onToggleDeep(it == "Deep dive") } }
                Box(Modifier.padding(top = 8.dp, bottom = 6.dp)) { MdSegment(listOf("Week", "Month", "All"), range) { range = it } }
            }

            if (!deep) {
                if (!enough) item { ThresholdNote() }
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Estimates", if (dots.isNotEmpty()) "$hit%" else "—", "${sessions.size} sessions", c.greenSoft, c.greenInk, "landed within 5 min")
                        // Headline is the session COUNT — titling it "Re-entries" overstated it
                        // (the first session isn't a re-entry; the real <5m re-entry rate lives in
                        // the Deep dive). Label it for what the number actually is.
                        StatCard("Focus sessions", if (enough) "${sessions.size}" else "—", "${captures.size} captures", c.blueSoft, c.blueInk, "completed this window")
                        StatCard("Gentle friction", "${slips.size} tasks", if (slips.isEmpty()) "All clear." else "Watch these", if (slips.isEmpty()) c.greenSoft else c.amberSoft, if (slips.isEmpty()) c.greenInk else c.amberInk, "slipping")
                    }
                }
                if (enough) {
                    item {
                        // Drive the chart from the user's OWN areas — DEFAULT_AREAS dropped
                        // every custom/renamed area's hours and greyed the legend.
                        val areaNames = remember(lifeAreas) { lifeAreas.map { it.name }.ifEmpty { DEFAULT_AREAS } }
                        val weekdayBars = remember(sessions, tasks, areaNames) { weekdayAreaHours(sessions, tasks, areaNames).map { it.d to it.data } }
                        StackedBars("When focus happens", weekdayBars, areaNames, lifeAreas)
                    }
                    if (dots.isNotEmpty()) item { CalibrationScatter(dots, hit) }
                    item {
                        val interruptions = remember(captures, sessions) { interruptionBins(captures, sessions) }
                        Histogram("When interruptions happen", interruptions, c.coral)
                    }
                    item {
                        val insights = remember(sessions, tasks, captures, reasons) { topInsights(sessions, tasks, captures, reasons) }
                        if (insights.isNotEmpty()) {
                            SectionLabel("Worth noticing", Modifier.padding(top = 18.dp, bottom = 6.dp))
                            insights.take(4).forEach { ins ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), radius = 14) {
                                    Column { Text(ins.title, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink); Text(ins.sub, style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 4.dp)) }
                                }
                            }
                        }
                    }
                }
            } else {
                if (!enough) item { ThresholdNote() }
                item {
                    // Median over raw SECONDS (round once at display) — truncating each
                    // session to whole minutes first skewed the median (web parity).
                    val median = remember(sessions) {
                        val secs = sessions.map { it.actualSec }.sorted()
                        if (secs.isEmpty()) 0 else (secs[secs.size / 2] / 60.0).roundToInt()
                    }
                    val reentryPct = remember(reEntry) { reEntry.sum().let { if (it == 0) 0 else (reEntry[0] * 100.0 / it).roundToInt() } }
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Median", if (enough) "${median}m" else "—", caption = "across ${sessions.size} sessions", modifier = Modifier.weight(1f))
                            StatCard("On estimate", if (dots.isNotEmpty()) "$hit%" else "—", caption = "within 5 min", modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Re-entry <5m", if (enough) "$reentryPct%" else "—", caption = "fast comebacks", modifier = Modifier.weight(1f))
                            StatCard("Captures", if (enough) "${captures.size}" else "—", caption = "kept this window", modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    val pauses = remember(reasons) { pauseAnatomy(reasons) }
                    if (pauses.isNotEmpty()) {
                        SectionLabel("What pauses you", Modifier.padding(top = 18.dp, bottom = 6.dp))
                        Card(Modifier.fillMaxWidth(), radius = 14) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val max = pauses.maxOf { it.minutes }.coerceAtLeast(0.001)
                                pauses.forEach { p -> LabeledBar(p.reason, (p.minutes / max).toFloat(), "${p.minutes.roundToInt()}m · ${p.count}", c.coral) }
                            }
                        }
                    }
                }
                item { Histogram("How fast you come back", reEntry, c.primary) }
                item {
                    val breakdown = remember(captures) { captureBreakdown(captures) }
                    // Guard on the captures themselves — breakdown always has the 5 fixed
                    // tag keys (all 0 when empty), so it would otherwise draw 5 zero bars.
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
                        SectionLabel("The slip detector", Modifier.padding(top = 18.dp, bottom = 6.dp))
                        slips.take(8).forEach { s ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), radius = 12) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(s.name, style = UFont.sans(13, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                                    Text("${s.moveCount}× · ${s.weeks}w", style = UFont.mono(11), color = c.ink3)
                                }
                            }
                        }
                    }
                }
                item { Heatmap(remember(sessions) { timeOfDayHeatmap(sessions) }) }
            }
            item { Box(Modifier.padding(24.dp)) {} }
        }
    }
}

@Composable
private fun ThresholdNote() {
    val c = UTheme.colors
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), radius = 14) {
        Column {
            Text("No focus sessions yet.", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text("Your reflection fills in here as you focus — come back after a session or two.", style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun StackedBars(title: String, bars: List<Pair<String, List<Double>>>, areas: List<String>, lifeAreas: List<tech.csalliance.unstuck.core.model.LifeArea>) {
    val c = UTheme.colors
    val max = bars.maxOfOrNull { it.second.sum() }?.coerceAtLeast(0.001) ?: 0.001
    // Spoken summary so screen-reader users get the per-day totals the bars encode.
    val a11y = "$title. " + bars.joinToString("; ") { (day, data) ->
        val total = data.sum()
        val h = total / 60.0
        "$day ${if (h >= 1) "${(h * 10).roundToInt() / 10.0}h" else "${total.roundToInt()}m"}"
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
                            if (frac > 0f) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(tech.csalliance.unstuck.ui.components.areaColorFor(areas.getOrElse(i) { "" }, lifeAreas, c)))
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                areas.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) { Box(Modifier.height(8.dp).width(8.dp).clip(RoundedCornerShape(2.dp)).background(tech.csalliance.unstuck.ui.components.areaColorFor(a, lifeAreas, c))); Text(a, style = UFont.sans(9), color = c.ink3) } }
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
            Text("$hitPct% of recent sessions landed within 5 min of estimate.", style = UFont.sans(11), color = c.ink3)
            Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp)) {
                val pad = 8.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                fun px(v: Int) = pad + (v.toFloat() / maxVal) * w
                fun py(v: Int) = (size.height - pad) - (v.toFloat() / maxVal) * h
                // Axes.
                drawLine(c.line2, Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad), 1.dp.toPx())
                drawLine(c.line2, Offset(pad, pad), Offset(pad, size.height - pad), 1.dp.toPx())
                // y = x reference (estimate == actual).
                drawLine(c.line2, Offset(px(0), py(0)), Offset(px(maxVal), py(maxVal)), 1.dp.toPx())
                // Dots: green within 5 min of estimate, coral when off.
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
                Text("→ estimate", style = UFont.mono(9), color = c.ink3)
                Text("↑ actual", style = UFont.mono(9), color = c.ink3)
            }
        }
    }
}

@Composable
private fun Histogram(title: String, bins: List<Int>, color: Color) {
    val c = UTheme.colors
    val max = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
    // Each bar is a Box with no semantics — summarize the bin counts + total.
    val a11y = "$title. ${bins.sum()} sessions across ${bins.size} buckets: " +
        bins.joinToString(", ")
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                bins.forEach { v ->
                    val frac = (v.toFloat() / max).coerceIn(0.02f, 1f)
                    Box(Modifier.weight(1f).fillMaxHeight(frac).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (v > 0) color else c.bg2))
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

@Composable
private fun Heatmap(grid: List<List<Double>>) {
    val c = UTheme.colors
    val max = (grid.flatten().maxOrNull() ?: 0.0).coerceAtLeast(0.001)
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
    // Color-only grid — name the busiest day and its focus minutes for SR users.
    val a11y = run {
        val dayTotals = grid.mapIndexed { i, row -> (days.getOrElse(i) { "Day ${i + 1}" }) to row.sum() }
        val busiest = dayTotals.maxByOrNull { it.second }
        val totalMin = grid.flatten().sum().roundToInt()
        "Focus by hour and weekday heatmap. $totalMin total focus minutes" +
            (busiest?.takeIf { it.second > 0 }?.let { ", busiest on ${it.first}" } ?: "") + "."
    }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hour × day", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            grid.forEachIndexed { d, row ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(days.getOrElse(d) { "" }, style = UFont.sans(11), color = c.ink3, modifier = Modifier.width(30.dp))
                    row.forEach { v ->
                        val t = (v / max).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(if (v <= 0.0) c.bg2 else lerp(c.bg2, c.green, 0.2f + 0.7f * t)))
                    }
                }
            }
        }
    }
}