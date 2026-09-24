package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

// Analytics derivation helpers — pure functions over the live collections.
// Port of lib/analytics.ts. The Compose Report + DeepDive charts consume
// these; each chart decides whether it has enough data for real numbers.
//
// Every function that reads sessions applies the shared D1 filter itself
// (FocusFilter.kt: drop < 60 s, clamp runaways), so no caller can forget it
// (analytics fixes 2026-09-24, cross-check P0-1 / P0-7).

// Floor for the qualitative "Worth noticing" insights only — a single session
// shouldn't claim a "strongest day". The numeric cards + charts no longer gate
// on this (they show real data from the first session); kept low so the prose
// insights still surface early.
const val REAL_DATA_THRESHOLD = 3
private const val HOUR = 3600.0

private fun parseDate(iso: String): Long? = Time.parseMillis(iso)

/** Monday-anchored weekday index: Mon=0 … Sun=6. */
fun dayOfWeekIdx(epochMs: Long): Int = (Time.dayOfWeekJs(epochMs) + 6) % 7

// H1 — weekday × area stacked bars

data class StackedBar(val d: String, val data: MutableList<Double>)

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
val DEFAULT_AREAS = listOf("Work", "Personal", "Home", "Health", "Volunteering")

/** The "No area" series label (sessions with no task, a deleted task, or a
 *  task with no area). */
const val NO_AREA_LABEL = "No area"

/** One series of the "When focus happens" chart: a named area, or the
 *  "No area" bucket ([area] == null). */
data class AreaSeries(val name: String, val area: String?)

/** The chart: its series, and Mon..Sun hours per series (same order). */
data class AreaBars(val series: List<AreaSeries>, val days: List<StackedBar>)

/** A session's area as the chart files it: its task's area, or null (no task,
 *  a deleted task, a blank area). */
private fun sessionArea(s: Session, byId: Map<String, TaskItem>): String? =
    s.taskId?.let { byId[it] }?.lifeArea?.takeIf { it.isNotBlank() }

/** The chart's series — web's rule (lib/period-facts.ts `areaSeries`, the
 *  cross-platform pick of 2026-09-24): the user's areas in their order, then
 *  any OTHER area a counted session's task carries, by name (an area renamed
 *  or removed from the list, a custom one from another device), then
 *  "No area" when a counted session has none. An area off the list is shown
 *  under its own name, never folded into "No area". */
fun areaSeries(sessions: List<Session>, tasks: List<TaskItem>, areas: List<String> = DEFAULT_AREAS): List<AreaSeries> {
    val byId = tasks.associateBy { it.id }
    val known = areas.toSet()
    val extra = java.util.TreeSet<String>()
    var noArea = false
    for (s in countableSessions(sessions)) {
        val a = sessionArea(s, byId)
        if (a == null) noArea = true else if (a !in known) extra += a
    }
    return areas.map { AreaSeries(it, it) } + extra.map { AreaSeries(it, it) } +
        (if (noArea) listOf(AreaSeries(NO_AREA_LABEL, null)) else emptyList())
}

/** Hours per Monday-anchored weekday × [areaSeries] — every counted minute
 *  lands in a series (cross-check P0-5: 22% of prod focus used to vanish). */
fun weekdayAreaBars(sessions: List<Session>, tasks: List<TaskItem>, areas: List<String> = DEFAULT_AREAS): AreaBars {
    val counted = countableSessions(sessions)
    val series = areaSeries(counted, tasks, areas)
    val byId = tasks.associateBy { it.id }
    val days = DAY_LABELS.map { StackedBar(it, MutableList(series.size) { 0.0 }) }
    for (s in counted) {
        val d = parseDate(s.completedAt) ?: continue
        val a = sessionArea(s, byId)
        val i = series.indexOfFirst { it.area == a }
        if (i < 0) continue
        days[dayOfWeekIdx(d)].data[i] += s.actualSec / HOUR
    }
    return AreaBars(series, days)
}

/** Total hours per series, in [AreaBars.series] order. */
fun areaTotals(bars: AreaBars): List<Double> = bars.series.indices.map { i -> bars.days.sumOf { it.data[i] } }

// H2 — estimate-vs-actual scatter

data class CalibrationDot(val e: Int, val a: Int, val t: String)

fun calibrationDots(sessions: List<Session>, tasks: List<TaskItem>, cap: Int = 24): List<CalibrationDot> {
    val byId = tasks.associateBy { it.id }
    val sorted = countableSessions(sessions).sortedByDescending { it.completedAt } // desc
    val out = mutableListOf<CalibrationDot>()
    for (s in sorted.take(cap)) {
        val taskId = s.taskId ?: continue
        val task = byId[taskId] ?: continue
        out.add(CalibrationDot(task.estimateMin, Math.round(s.actualSec / 60.0).toInt(), task.name))
    }
    return out
}

fun calibrationHitRate(dots: List<CalibrationDot>, slackMin: Int = 5): Double {
    if (dots.isEmpty()) return 0.0
    val hits = dots.count { abs(it.a - it.e) <= slackMin }
    return hits.toDouble() / dots.size
}

// H3 — interruption histogram (captures as the proxy)

/** Captures linked to a counted session, by minutes into it. Pass the RAW
 *  sessions: the start is inferred as completedAt − the REAL actualSec (as the
 *  heatmap does), so a forgotten timer's captures land where they were taken,
 *  not hours "before the start" of its clamped length. That start still
 *  ignores paused time, so a capture taken before a pause can read as before
 *  the start: it is clamped into the first bin instead of being dropped
 *  (cross-check P0-11). The screen hides the chart below
 *  [INTERRUPTIONS_MIN_LINKED] linked captures. */
fun interruptionBins(captures: List<Capture>, sessions: List<Session>, binMin: Int = 3, binCount: Int = 10): List<Int> {
    if (binCount < 1) return emptyList()
    val bins = IntArray(binCount)
    val sessionStart = HashMap<String, Double>()
    for (s in sessions) {
        if (!isCountable(s)) continue
        Time.parseMillis(s.completedAt)?.let { sessionStart[s.id] = it - s.actualSec * 1000.0 }
    }
    for (c in captures) {
        val sid = c.sessionId ?: continue
        val start = sessionStart[sid] ?: continue
        val at = Time.parseMillis(c.at) ?: continue
        val intoMin = maxOf(0.0, (at - start) / 60_000.0)
        val idx = min(binCount - 1, floor(intoMin / binMin).toInt())
        bins[idx]++
    }
    return bins.toList()
}

/** The interruptions chart needs at least this many linked captures to say anything. */
const val INTERRUPTIONS_MIN_LINKED = 3

// H4 — hour × day heatmap (7 days × 24 hours, minutes)

/** Focus minutes per Monday-anchored weekday (rows, Mon=0) × local hour
 *  (columns 0–23), spread over the hours each session actually spanned: from
 *  its start (completedAt − actualSec) for its COUNTED length. Weekends and
 *  evenings count (cross-check P0-2: the old Mon–Fri 7am–7pm grid by end hour
 *  showed 20% of prod focus). Pass the RAW sessions: the start comes from the
 *  real length, the amount from the D1 clamp. */
fun hourDayHeatmap(sessions: List<Session>, zone: ZoneId = ZoneId.systemDefault()): List<List<Double>> {
    val grid = List(7) { MutableList(24) { 0.0 } }
    for (s in sessions) {
        val counted = countedSec(s)
        if (counted <= 0) continue
        val end = parseDate(s.completedAt) ?: continue
        var t = end - s.actualSec * 1000L
        var left = counted * 1000L
        while (left > 0) {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val hourEnd = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val chunk = minOf(left, hourEnd - t)
            grid[(z.dayOfWeek.value + 6) % 7][z.hour] += chunk / 60_000.0
            t += chunk
            left -= chunk
        }
    }
    return grid
}

/** "9am", "12pm", "7pm" — an hour-of-day label. */
fun hourLabel(h: Int): String = when {
    h == 0 || h == 24 -> "12am"
    h < 12 -> "${h}am"
    h == 12 -> "12pm"
    else -> "${h - 12}pm"
}

// H5 — pause anatomy

data class PauseBar(val reason: String, val minutes: Double, val count: Int)

fun pauseAnatomy(reasonLogs: List<ReasonLog>): List<PauseBar> {
    val minutesByReason = HashMap<String, Double>()
    val countByReason = HashMap<String, Int>()
    val order = mutableListOf<String>()
    for (r in reasonLogs) {
        val key = r.reason.ifEmpty { "Other" }
        if (countByReason[key] == null) order.add(key)
        countByReason[key] = (countByReason[key] ?: 0) + 1
        val dur = r.durationSec
        if (dur != null && dur > 0) minutesByReason[key] = (minutesByReason[key] ?: 0.0) + dur / 60.0
    }
    return order
        .map { PauseBar(it, minutesByReason[it] ?: 0.0, countByReason[it] ?: 0) }
        .sortedWith(compareByDescending<PauseBar> { it.minutes }.thenByDescending { it.count })
        .take(6)
}

// H6 — how fast you come back (analytics decision D5)

/** Bin edges (minutes) for the pause-to-resume histogram: <5, 5–10, 10–15,
 *  15–30, 30–60, 1h+. */
val COMEBACK_EDGES = listOf(5, 10, 15, 30, 60)
val COMEBACK_LABELS = listOf("<5m", "5–10m", "10–15m", "15–30m", "30–60m", "1h+")

/** Time from pause to resume, from the pause reasons that carry a length
 *  (`reason_logs.duration_sec`, written on resume since 2026-09-24). This
 *  replaced the old same-task session gap, which in prod measured days
 *  between sessions (cross-check P0-6). All zeros = nothing measured yet. */
fun comebackBins(reasons: List<ReasonLog>): List<Int> {
    val bins = IntArray(COMEBACK_EDGES.size + 1)
    for (r in reasons) {
        val sec = r.durationSec ?: continue
        if (sec <= 0) continue
        val min = sec / 60.0
        val idx = COMEBACK_EDGES.indexOfFirst { min < it }.let { if (it < 0) COMEBACK_EDGES.size else it }
        bins[idx]++
    }
    return bins.toList()
}

// H7 — slip detector

data class SlipRow(val name: String, val weeks: Int, val moveCount: Int)

/** Open tasks that keep slipping: ≥ 21 days on the list or moved ≥ 3×. Never a
 *  repeating task's series (it is never "done" — its days are judged
 *  separately) and never a task parked in Later on purpose (cross-check P0-4).
 *  The FULL list — callers cap at display, so a count is always the true one. */
fun slipping(tasks: List<TaskItem>, now: Long = System.currentTimeMillis()): List<SlipRow> {
    val out = mutableListOf<SlipRow>()
    for (t in tasks) {
        if (t.done || t.recurrence != null || t.later == true) continue
        val ageDays = Time.parseMillis(t.createdAt)?.let { (now - it) / (24.0 * 60 * 60 * 1000) } ?: 0.0
        val moves = t.moveCount ?: 0
        if (ageDays >= 21 || moves >= 3) {
            out.add(SlipRow(t.name, maxOf(0, floor(ageDays / 7).toInt()), moves))
        }
    }
    return out
        .sortedWith(compareByDescending<SlipRow> { it.moveCount }.thenByDescending { it.weeks })
}

// capture flow breakdown

fun captureBreakdown(captures: List<Capture>): Map<CaptureTag, Int> {
    val out = linkedMapOf(
        CaptureTag.FOLLOW_UP to 0, CaptureTag.IDEA to 0, CaptureTag.EDIT to 0,
        CaptureTag.QUESTION to 0, CaptureTag.DISTRACTION to 0,
    )
    for (c in captures) out[c.tag] = (out[c.tag] ?: 0) + 1
    return out
}

// Insight engine — the Report "WORTH NOTICING" cards

data class Insight(val title: String, val sub: String)

private val WEEKDAY_NAMES =
    listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")

fun topInsights(
    sessions: List<Session>,
    tasks: List<TaskItem>,
    captures: List<Capture>,
    reasonLogs: List<ReasonLog>,
    /** Injectable so the caller's clock reaches [slipping] (web/iOS parity —
     *  get_insights renders against a fixed nowMs, not the wall clock). */
    now: Long = System.currentTimeMillis(),
): List<Insight> {
    val out = mutableListOf<Insight>()
    val counted = countableSessions(sessions)

    if (counted.size >= REAL_DATA_THRESHOLD) {
        // 1. Best weekday by focus minutes.
        val byDay = DoubleArray(7)
        for (s in counted) parseDate(s.completedAt)?.let { byDay[dayOfWeekIdx(it)] += s.actualSec / 60.0 }
        val maxVal = byDay.maxOrNull()
        if (maxVal != null && maxVal > 0) {
            val idx = byDay.indexOfFirst { it == maxVal }
            out.add(
                Insight(
                    "${WEEKDAY_NAMES[idx]} are your strongest day.",
                    "${Math.round(maxVal).toInt()} focused minutes — more than any other day this window. Stack harder work here.",
                ),
            )
        }

        // 2. Calibration tightening.
        val dots = calibrationDots(counted, tasks)
        if (dots.size >= 3) {
            val hit = calibrationHitRate(dots)
            val phrase = when {
                hit >= 0.75 -> "you're nailing your estimates"
                hit >= 0.5 -> "your estimates are improving"
                else -> "estimates are still settling"
            }
            out.add(
                Insight(
                    "Estimates within 5 min ${Math.round(hit * 100).toInt()}% of the time.",
                    "${dots.size} recent sessions tracked — $phrase. The calibration card shows where outliers landed.",
                ),
            )
        }
    }

    // 3. Slipping task (works even at low session counts).
    val slips = slipping(tasks, now)
    slips.firstOrNull()?.let { top ->
        val reason = if (top.moveCount >= 3) "rescheduled ${top.moveCount} times" else "${top.weeks}+ weeks on the list"
        out.add(Insight("\"${top.name}\" keeps slipping.", "$reason. Remove it, or break it down differently?"))
    }

    return out.take(3)
}
