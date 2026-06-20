package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

// Analytics derivation helpers — pure functions over the live collections.
// Port of lib/analytics.ts. The Compose Report + DeepDive charts consume
// these; each chart decides whether it has enough data for real numbers.

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

fun weekdayAreaHours(
    sessions: List<Session>,
    tasks: List<TaskItem>,
    areas: List<String> = DEFAULT_AREAS,
): List<StackedBar> {
    // Unassigned tasks (lifeArea == null) drop out — never coerced to 'Work'.
    val taskArea = HashMap<String, String>()
    for (t in tasks) t.lifeArea?.let { taskArea[t.id] = it }
    val out = DAY_LABELS.map { StackedBar(it, MutableList(areas.size) { 0.0 }) }
    for (s in sessions) {
        val taskId = s.taskId ?: continue
        val area = taskArea[taskId] ?: continue
        val ai = areas.indexOf(area); if (ai < 0) continue
        val d = parseDate(s.completedAt) ?: continue
        out[dayOfWeekIdx(d)].data[ai] += s.actualSec / HOUR
    }
    return out
}

// H2 — estimate-vs-actual scatter

data class CalibrationDot(val e: Int, val a: Int, val t: String)

fun calibrationDots(sessions: List<Session>, tasks: List<TaskItem>, cap: Int = 24): List<CalibrationDot> {
    val byId = tasks.associateBy { it.id }
    val sorted = sessions.sortedByDescending { it.completedAt } // desc
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

fun interruptionBins(captures: List<Capture>, sessions: List<Session>, binMin: Int = 3, binCount: Int = 10): List<Int> {
    if (binCount < 1) return emptyList()
    val bins = IntArray(binCount)
    val sessionStart = HashMap<String, Double>()
    for (s in sessions) {
        Time.parseMillis(s.completedAt)?.let { sessionStart[s.id] = it - s.actualSec * 1000.0 }
    }
    for (c in captures) {
        val sid = c.sessionId ?: continue
        val start = sessionStart[sid] ?: continue
        val at = Time.parseMillis(c.at) ?: continue
        val intoMin = (at - start) / 60_000.0
        if (intoMin < 0) continue
        val idx = min(binCount - 1, floor(intoMin / binMin).toInt())
        bins[idx]++
    }
    return bins.toList()
}

// H4 — time-of-day heatmap (5 weekdays × 6 two-hour buckets from 7am)

fun timeOfDayHeatmap(sessions: List<Session>): List<List<Double>> {
    val grid = MutableList(5) { MutableList(6) { 0.0 } }
    for (s in sessions) {
        val d = parseDate(s.completedAt) ?: continue
        val dow = dayOfWeekIdx(d)
        if (dow > 4) continue
        val bucket = floor((Time.hourOf(d) - 7) / 2.0).toInt()
        if (bucket < 0 || bucket > 5) continue
        grid[dow][bucket] += s.actualSec / HOUR
    }
    return grid
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

// H6 — re-entry distribution

fun reEntryDistribution(sessions: List<Session>, binMin: Int = 5, binCount: Int = 12): List<Int> {
    if (binCount < 1) return emptyList()
    val bins = IntArray(binCount)
    val byTask = HashMap<String, MutableList<Session>>()
    for (s in sessions) {
        val taskId = s.taskId ?: continue
        byTask.getOrPut(taskId) { mutableListOf() }.add(s)
    }
    for (list in byTask.values) {
        list.sortBy { it.completedAt }
        for (i in 1 until list.size) {
            val prevEnd = Time.parseMillis(list[i - 1].completedAt) ?: continue
            val thisEnd = Time.parseMillis(list[i].completedAt) ?: continue
            val gapMin = (thisEnd - prevEnd) / 60_000.0 - list[i].actualSec / 60.0
            if (gapMin <= 0) continue
            val idx = min(binCount - 1, floor(gapMin / binMin).toInt())
            bins[idx]++
        }
    }
    return bins.toList()
}

// H7 — slip detector

data class SlipRow(val name: String, val weeks: Int, val moveCount: Int)

fun slipping(tasks: List<TaskItem>, now: Long = System.currentTimeMillis()): List<SlipRow> {
    val out = mutableListOf<SlipRow>()
    for (t in tasks) {
        if (t.done) continue
        val ageDays = Time.parseMillis(t.createdAt)?.let { (now - it) / (24.0 * 60 * 60 * 1000) } ?: 0.0
        val moves = t.moveCount ?: 0
        if (ageDays >= 21 || moves >= 3) {
            out.add(SlipRow(t.name, maxOf(0, floor(ageDays / 7).toInt()), moves))
        }
    }
    return out
        .sortedWith(compareByDescending<SlipRow> { it.moveCount }.thenByDescending { it.weeks })
        .take(6)
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
): List<Insight> {
    val out = mutableListOf<Insight>()

    if (sessions.size >= REAL_DATA_THRESHOLD) {
        // 1. Best weekday by focus minutes.
        val byDay = DoubleArray(7)
        for (s in sessions) parseDate(s.completedAt)?.let { byDay[dayOfWeekIdx(it)] += s.actualSec / 60.0 }
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
        val dots = calibrationDots(sessions, tasks)
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
    val slips = slipping(tasks)
    slips.firstOrNull()?.let { top ->
        val reason = if (top.moveCount >= 3) "rescheduled ${top.moveCount} times" else "${top.weeks}+ weeks on the list"
        out.add(Insight("\"${top.name}\" keeps slipping.", "$reason. Remove it, or break it down differently?"))
    }

    return out.take(3)
}
