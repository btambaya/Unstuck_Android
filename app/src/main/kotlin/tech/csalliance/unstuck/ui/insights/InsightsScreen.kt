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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.DEFAULT_AREAS
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
    fun inWin(iso: String): Boolean = (Time.parseMillis(iso) ?: 0L) >= cutoff
    val sessions = allSessions.filter { inWin(it.completedAt) }
    val captures = allCaptures.filter { inWin(it.at) }
    val reasons = allReasons.filter { inWin(it.at) }
    val enough = sessions.size >= 5   // REAL_DATA_THRESHOLD — below this, show placeholders not numbers

    val dots = calibrationDots(sessions, tasks)
    val hit = if (dots.isNotEmpty()) (calibrationHitRate(dots) * 100).roundToInt() else 0
    val slips = slipping(tasks, now)
    val totalMin = sessions.sumOf { it.actualSec } / 60
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
                if (!enough) item { ThresholdNote(sessions.size) }
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Estimates", if (enough) "$hit%" else "—", "${sessions.size} sessions", c.greenSoft, c.greenInk, "landed within 5 min")
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
                        val areaNames = lifeAreas.map { it.name }.ifEmpty { DEFAULT_AREAS }
                        StackedBars("When focus happens", weekdayAreaHours(sessions, tasks, areaNames).map { it.d to it.data }, areaNames, lifeAreas)
                    }
                    item { Histogram("When interruptions happen", interruptionBins(captures, sessions), c.coral) }
                    item {
                        val insights = topInsights(sessions, tasks, captures, reasons)
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
                if (!enough) item { ThresholdNote(sessions.size) }
                item {
                    // Median over raw SECONDS (round once at display) — truncating each
                    // session to whole minutes first skewed the median (web parity).
                    val secs = sessions.map { it.actualSec }.sorted()
                    val median = if (secs.isEmpty()) 0 else (secs[secs.size / 2] / 60.0).roundToInt()
                    val reentry = reEntryDistribution(sessions)
                    val reentryPct = reentry.sum().let { if (it == 0) 0 else (reentry[0] * 100.0 / it).roundToInt() }
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Median", if (enough) "${median}m" else "—", caption = "across ${sessions.size} sessions", modifier = Modifier.weight(1f))
                            StatCard("On estimate", if (enough) "$hit%" else "—", caption = "within 5 min", modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("Re-entry <5m", if (enough) "$reentryPct%" else "—", caption = "fast comebacks", modifier = Modifier.weight(1f))
                            StatCard("Captures", if (enough) "${captures.size}" else "—", caption = "kept this window", modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    val pauses = pauseAnatomy(reasons)
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
                item { Histogram("How fast you come back", reEntryDistribution(sessions), c.primary) }
                item {
                    val breakdown = captureBreakdown(captures)
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
                item { Heatmap(timeOfDayHeatmap(sessions)) }
            }
            item { Box(Modifier.padding(24.dp)) {} }
        }
    }
}

@Composable
private fun ThresholdNote(n: Int) {
    val c = UTheme.colors
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), radius = 14) {
        Column {
            Text("Patterns appear after a few sessions.", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text("$n of 5 focus sessions so far — numbers stay gentle until then.", style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun StackedBars(title: String, bars: List<Pair<String, List<Double>>>, areas: List<String>, lifeAreas: List<tech.csalliance.unstuck.core.model.LifeArea>) {
    val c = UTheme.colors
    val max = bars.maxOfOrNull { it.second.sum() }?.coerceAtLeast(0.001) ?: 0.001
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
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
private fun Histogram(title: String, bins: List<Int>, color: Color) {
    val c = UTheme.colors
    val max = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
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
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
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