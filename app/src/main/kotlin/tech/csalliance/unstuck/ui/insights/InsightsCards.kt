package tech.csalliance.unstuck.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.logic.DayFacts
import tech.csalliance.unstuck.core.logic.InsightsFacts
import tech.csalliance.unstuck.core.logic.InsightsSpan
import tech.csalliance.unstuck.core.logic.PlanFacts
import tech.csalliance.unstuck.core.logic.SeriesRhythm
import tech.csalliance.unstuck.core.logic.SeriesState
import tech.csalliance.unstuck.core.logic.TrendBar
import tech.csalliance.unstuck.core.logic.UnstuckWin
import tech.csalliance.unstuck.core.logic.neutralDelta
import tech.csalliance.unstuck.core.logic.neutralDurDelta
import tech.csalliance.unstuck.core.logic.periodCleanName
import tech.csalliance.unstuck.core.logic.periodDayOf
import tech.csalliance.unstuck.core.logic.periodDur
import tech.csalliance.unstuck.core.logic.periodFmtDay
import tech.csalliance.unstuck.core.logic.periodMinutes
import tech.csalliance.unstuck.core.logic.periodParseYmd
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.components.areaColorFor
import java.time.ZoneId

// The Report's story cards (analytics opportunities #2–#7, 2026-09-24): what
// got done, when, what came unstuck, the plan against what happened, the
// repeating tasks, and the trend. All from InsightsFacts, so every number is
// the one get_period_review reads out. Ink scale + the user's area colours
// only; no streaks; deltas are neutral text, never coloured.

private val WEEKDAY_INITIALS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Done · Focused · Showed up, each with a neutral change against the period
 *  before (none for All time). Counts always show, including 0. */
@Composable
internal fun HeadlineRow(f: InsightsFacts, span: InsightsSpan) {
    val prev = f.prev
    val showedUpSub = if (span == InsightsSpan.WEEK) null else "of ${f.daysSoFar} days"
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeadlineStat(
            "Done", "${f.doneCount}",
            sub = listOfNotNull(
                f.cur.plainDone.size.takeIf { it > 0 }?.let { "$it task${if (it == 1) "" else "s"}" },
                f.cur.occDone.size.takeIf { it > 0 }?.let { "$it repeating" },
                f.cur.created.size.takeIf { it > 0 }?.let { "added $it" },
            ).joinToString(" · ").ifEmpty { null },
            delta = prev?.let { neutralDelta(f.doneCount - it.doneCount) },
            modifier = Modifier.weight(1f),
        )
        HeadlineStat(
            "Focused", periodDur(periodMinutes(f.focusSec)),
            sub = "${f.sessionCount} session${if (f.sessionCount == 1) "" else "s"}",
            delta = prev?.let { neutralDurDelta(f.focusSec, it.focusSec) },
            modifier = Modifier.weight(1f),
        )
        HeadlineStat(
            "Showed up", if (span == InsightsSpan.WEEK) "${f.showedUp} of ${f.daysSoFar}" else "${f.showedUp}",
            sub = showedUpSub ?: "days",
            delta = f.prevShowedUp?.let { neutralDelta(f.showedUp - it) },
            modifier = Modifier.weight(1f),
            dots = if (span == InsightsSpan.WEEK) f.days else null,
        )
    }
}

@Composable
private fun HeadlineStat(label: String, value: String, sub: String?, delta: String?, modifier: Modifier, dots: List<DayFacts>? = null) {
    val c = UTheme.colors
    val a11y = "$label $value${sub?.let { ", $it" } ?: ""}${delta?.let { ", $it vs before" } ?: ""}"
    Card(modifier.semantics(mergeDescendants = true) { contentDescription = a11y }, radius = 16, pad = 12) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionLabel(label)
            Text(value, style = UFont.sans(20, FontWeight.SemiBold), color = c.ink, maxLines = 1)
            if (dots != null) {
                // One dot per day: filled = showed up, hollow = a quiet day, faint = still to come.
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    dots.forEach { d ->
                        val active = d.done > 0 || d.sessions > 0
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(if (active) c.ink else Color.Transparent)
                                .border(1.dp, if (d.future) c.line else if (active) c.ink else c.ink4, CircleShape),
                        )
                    }
                }
            } else if (sub != null) {
                Text(sub, style = UFont.sans(11), color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (delta != null) Text("$delta vs before", style = UFont.sans(10), color = c.ink3, maxLines = 1)
        }
    }
}

/** Per-day focus bars with the day's done count above each; days still to come
 *  are faint. "Busiest" follows the review's rule (most done, then most focus,
 *  then earliest; only when ≥ 2 done). */
@Composable
internal fun DailyRhythmCard(days: List<DayFacts>, span: InsightsSpan, todayYear: Int) {
    val c = UTheme.colors
    if (days.isEmpty()) return
    val maxSec = days.maxOf { it.focusSec }.coerceAtLeast(1)
    val busiest = days.filter { !it.future }.sortedWith(
        compareByDescending<DayFacts> { it.done }.thenByDescending { it.focusSec }.thenBy { it.date },
    ).firstOrNull()?.takeIf { it.done >= 2 }
    val a11y = "Daily rhythm. " + days.filter { !it.future }.joinToString("; ") { d ->
        "${periodFmtDay(d.date, todayYear)}: ${d.done} done, ${periodDur(periodMinutes(d.focusSec))} focus"
    }
    val week = span == InsightsSpan.WEEK
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Daily rhythm", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text(
                busiest?.let { "Busiest: ${periodFmtDay(it.date, todayYear)} — ${it.done} done${if (periodMinutes(it.focusSec) > 0) ", ${periodDur(periodMinutes(it.focusSec))} focus" else ""}." }
                    ?: "Bars are focus time; the number above is what got done.",
                style = UFont.sans(11), color = c.ink3,
            )
            Row(Modifier.fillMaxWidth().height(if (week) 96.dp else 84.dp), horizontalArrangement = Arrangement.spacedBy(if (week) 8.dp else 2.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { d ->
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (week && d.done > 0) Text("${d.done}", style = UFont.mono(10), color = c.ink2)
                        else if (!week && d.done > 0) Box(Modifier.size(4.dp).clip(CircleShape).background(c.ink2))
                        val frac = d.focusSec.toFloat() / maxSec
                        Box(
                            Modifier.padding(top = 2.dp).fillMaxWidth().fillMaxHeight(if (d.focusSec > 0) (0.08f + 0.72f * frac) else 0.04f)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(if (d.future) c.bg2 else if (d.focusSec > 0) c.ink2 else c.line),
                        )
                    }
                }
            }
            if (week) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    days.indices.forEach { i ->
                        Text(WEEKDAY_INITIALS[i % 7], style = UFont.mono(9), color = c.ink3, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                // A day-of-month label every 7th day, each over its own 7-day stretch.
                Row(Modifier.fillMaxWidth()) {
                    days.chunked(7).forEach { chunk ->
                        Text("${periodParseYmd(chunk.first().date)!!.dayOfMonth}", style = UFont.mono(9), color = c.ink3, maxLines = 1, modifier = Modifier.weight(chunk.size.toFloat()))
                    }
                }
            }
        }
    }
}

/** "Got unstuck": things finished after waiting a week or more, or after being
 *  moved twice. Hidden when empty — there is never a "no wins" state. */
@Composable
internal fun GotUnstuckCard(wins: List<UnstuckWin>) {
    val c = UTheme.colors
    if (wins.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Got unstuck", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Text("Finished after waiting a while — the hard ones.", style = UFont.sans(11), color = c.ink3)
            wins.take(3).forEach { w ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(periodCleanName(w.task.name), style = UFont.sans(13, FontWeight.Medium), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val chip = listOfNotNull(
                        w.waitedDays.takeIf { it >= 1 }?.let { "waited $it day${if (it == 1) "" else "s"}" },
                        w.moves.takeIf { it >= 2 }?.let { "moved $it×" },
                    ).joinToString(" · ")
                    if (chip.isNotEmpty()) Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(chip, style = UFont.sans(10), color = c.ink2, maxLines = 1)
                    }
                }
            }
            if (wins.size > 3) Text("+${wins.size - 3} more", style = UFont.sans(11), color = c.ink3)
        }
    }
}

/** Plan vs followed through — the review's Plan line as a picture: done to
 *  plan, done later, still open, skipped on purpose. Counts, never a grade;
 *  "open", never "missed". The still-open list is something to act on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanCard(plan: PlanFacts?, todayYear: Int, zone: ZoneId) {
    val c = UTheme.colors
    if (plan == null) return
    val later = plan.slipped.count { it.task.done }
    val openPlain = plan.slipped.filter { !it.task.done }
    val open = openPlain.size + plan.missedTotal
    if (plan.planned == 0 && plan.skipped == 0 && plan.deadlines.isEmpty()) return
    val a11y = "Plan versus followed through. ${plan.doneToPlan} of ${plan.planned} planned done; $later done later; $open still open; ${plan.skipped} skipped on purpose."
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plan vs followed through", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            if (plan.planned > 0) {
                Text("Followed through on ${plan.doneToPlan} of ${plan.planned} planned.", style = UFont.sans(12), color = c.ink2)
                val total = (plan.planned + plan.skipped).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.bg2)) {
                    listOf(plan.doneToPlan to c.ink, later to c.ink3, open to c.ink4).forEach { (n, col) ->
                        if (n > 0) Box(Modifier.weight(n.toFloat() / total).fillMaxHeight().background(col))
                    }
                    if (plan.skipped > 0) Box(Modifier.weight(plan.skipped.toFloat() / total).fillMaxHeight().border(1.dp, c.ink4))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Legend(c.ink, "${plan.doneToPlan} done")
                    if (later > 0) Legend(c.ink3, "$later done later")
                    if (open > 0) Legend(c.ink4, "$open still open")
                    if (plan.skipped > 0) Legend(null, "${plan.skipped} skipped on purpose")
                }
            } else if (plan.skipped > 0) {
                Text("${plan.skipped} repeating day${if (plan.skipped == 1) "" else "s"} skipped on purpose.", style = UFont.sans(12), color = c.ink2)
            }
            if (openPlain.isNotEmpty() || plan.missed.isNotEmpty()) {
                SectionLabel("Still open from earlier", Modifier.padding(top = 2.dp))
                openPlain.take(3).forEach { s ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(periodCleanName(s.task.name), style = UFont.sans(13), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("planned ${periodFmtDay(s.date, todayYear)}", style = UFont.sans(11), color = c.ink3)
                    }
                }
                plan.missed.take(3).forEach { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.name, style = UFont.sans(13), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${m.n} day${if (m.n == 1) "" else "s"} open", style = UFont.sans(11), color = c.ink3)
                    }
                }
            }
            if (plan.deadlines.isNotEmpty()) {
                val names = plan.deadlines.take(2).joinToString(", ") { t ->
                    "${periodCleanName(t.name)} (${periodDayOf(t.dueAt, zone)?.let { periodFmtDay(it, todayYear) } ?: "—"})"
                }
                Text("Due and still open: $names${if (plan.deadlines.size > 2) " +${plan.deadlines.size - 2} more" else ""}", style = UFont.sans(11), color = c.ink3)
            }
        }
    }
}

@Composable
private fun Legend(color: Color?, text: String) {
    val c = UTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color ?: Color.Transparent).border(1.dp, color ?: c.ink4, RoundedCornerShape(2.dp)))
        Text(text, style = UFont.sans(10), color = c.ink3)
    }
}

/** One row of dots per repeating task — filled = done, dash = skipped on
 *  purpose, hollow = open, faint = still to come. "Kept N of M so far"; no
 *  streak, no chain. Hidden when the period has no repeating days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepeatingRhythmCard(series: List<SeriesRhythm>, lifeAreas: List<LifeArea>) {
    val c = UTheme.colors
    if (series.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Repeating tasks", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            series.take(5).forEach { s ->
                val a11y = "${s.name}: kept ${s.kept} of ${s.soFar} so far"
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = a11y }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.lifeArea != null) Box(Modifier.size(7.dp).clip(CircleShape).background(areaColorFor(s.lifeArea, lifeAreas, c)))
                        Text(s.name, style = UFont.sans(13, FontWeight.Medium), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("kept ${s.kept} of ${s.soFar} so far", style = UFont.sans(11), color = c.ink3)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.dots.forEach { d -> RhythmDot(d.state) }
                    }
                }
            }
            if (series.size > 5) Text("+${series.size - 5} more", style = UFont.sans(11), color = c.ink3)
        }
    }
}

@Composable
private fun RhythmDot(state: SeriesState) {
    val c = UTheme.colors
    when (state) {
        SeriesState.DONE -> Box(Modifier.size(10.dp).clip(CircleShape).background(c.ink))
        SeriesState.OPEN -> Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, c.ink3, CircleShape))
        SeriesState.UPCOMING -> Box(Modifier.size(10.dp).clip(CircleShape).border(1.dp, c.line, CircleShape))
        SeriesState.SKIPPED -> Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(8.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.ink3))
        }
    }
}

/** 8 weeks / 6 months (or every week since the start, up to 26) of done and
 *  focus, ending at the selected period — which is marked with the ink/bg pair. */
@Composable
internal fun TrendCard(bars: List<TrendBar>, span: InsightsSpan) {
    val c = UTheme.colors
    if (bars.isEmpty() || bars.all { it.done == 0 && it.focusSec == 0 }) return
    val maxDone = bars.maxOf { it.done }.coerceAtLeast(1)
    val maxSec = bars.maxOf { it.focusSec }.coerceAtLeast(1)
    val unit = if (span == InsightsSpan.MONTH) "month" else "week"
    val a11y = "Trend by $unit. " + bars.joinToString("; ") { "${it.label}${if (it.soFar) " so far" else ""}: ${it.done} done, ${periodDur(periodMinutes(it.focusSec))} focus" }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = a11y }, radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (span == InsightsSpan.MONTH) "6 months" else if (span == InsightsSpan.ALL) "Every week so far" else "8 weeks", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(c.ink, "done")
                Legend(c.ink4, "focus")
            }
            Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(if (bars.size > 12) 2.dp else 6.dp), verticalAlignment = Alignment.Bottom) {
                bars.forEach { b ->
                    Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f).fillMaxHeight(if (b.done > 0) 0.06f + 0.94f * b.done / maxDone else 0.02f).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(if (b.done > 0) c.ink else c.line))
                        Box(Modifier.weight(1f).fillMaxHeight(if (b.focusSec > 0) 0.06f + 0.94f * b.focusSec.toFloat() / maxSec else 0.02f).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(if (b.focusSec > 0) c.ink4 else c.line))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (bars.size > 12) 2.dp else 6.dp)) {
                val every = if (bars.size > 12) 4 else if (bars.size > 6) 2 else 1
                val sel = bars.indexOfFirst { it.selected }
                bars.forEachIndexed { i, b ->
                    // Every `every`-th label, never crowding the selected one.
                    val show = b.selected || (i % every == 0 && (sel < 0 || kotlin.math.abs(sel - i) >= every))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (show) {
                            if (b.selected) Box(Modifier.clip(RoundedCornerShape(4.dp)).background(c.ink).padding(horizontal = 2.dp)) {
                                Text(b.label, style = UFont.mono(8), color = c.bg, maxLines = 1)
                            } else Text(b.label, style = UFont.mono(8), color = c.ink3, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Done by area (the user's areas + No area) — where the finished things
 *  belonged. Area colours; No area in ink4. */
@Composable
internal fun DoneByAreaCard(byArea: List<Pair<String, Int>>, noArea: Int, lifeAreas: List<LifeArea>) {
    val c = UTheme.colors
    val rows = byArea + if (noArea > 0) listOf("No area" to noArea) else emptyList()
    if (rows.isEmpty()) return
    val max = rows.maxOf { it.second }.coerceAtLeast(1)
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 18) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Done by area", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            rows.forEach { (name, n) ->
                val col = if (name == "No area" && byArea.none { it.first == "No area" }) c.ink4 else areaColorFor(name, lifeAreas, c)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = UFont.sans(12), color = c.ink2)
                        Text("$n", style = UFont.mono(10), color = c.ink3)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)).background(c.bg2)) {
                        Box(Modifier.fillMaxWidth((n.toFloat() / max).coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(col))
                    }
                }
            }
        }
    }
}
