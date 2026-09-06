package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// The Insights screen, rendered as text for the assistant (`get_insights`).
//
// `renderInsights` is a pure function the assistant calls to answer "how was
// my week?", "am I underestimating?", "what keeps stopping me?", "what's
// slipping?" from REAL data. It takes the same live collections the Insights
// screen reads, scopes them with the SAME window rule (Monday 00:00 / the 1st
// / everything) and runs the SAME derivations (Analytics.kt), so every number
// the model quotes equals what the user sees on the Insights tab. Port of
// lib/assistant/insights-read.ts (+ UnstuckCore/Logic/InsightsRead.swift).
//
// Output: compact plain text (no markdown), ≤ INSIGHTS_MAX_CHARS, prefixed
// `ok:` like the other assistant tool results. Each section is omitted (or
// says so in one short line) when there is nothing behind it — never a
// fabricated "sample" number.
//
// Date-only values (calendar-block dates, header dates) use LOCAL getters —
// never a UTC ISO round-trip, which shifts the day near midnight for anyone
// east or west of UTC.

enum class InsightsWindow(val wire: String) {
    WEEK("week"), MONTH("month"), ALL("all");

    companion object {
        /** null for anything but week | month | all (the executor then errors). */
        fun fromWire(s: String?): InsightsWindow? = entries.firstOrNull { it.wire == s }
    }
}

/** Hard ceiling on the report. Past it, insight sub-lines drop first, then we truncate. */
const val INSIGHTS_MAX_CHARS = 1200

/** Same slack `calibrationHitRate` uses — "within 5 min" on the Estimates card. */
private const val SLACK_MIN = 5
private const val MAX_SLIPS = 5
private const val MAX_PAUSES = 3
private const val MAX_INSIGHTS = 3
private const val MAX_NAME = 40

private val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Heatmap columns (timeOfDayHeatmap): six 2-hour buckets from 7am, Mon–Fri only. */
private val HEAT_BUCKETS = listOf("7–9am", "9–11am", "11am–1pm", "1–3pm", "3–5pm", "5–7pm")

/** Same display order as the DeepDive "Captures by kind" band, with the web's wire names. */
private val TAG_ORDER = listOf(
    CaptureTag.FOLLOW_UP to "follow-up", CaptureTag.IDEA to "idea", CaptureTag.EDIT to "edit",
    CaptureTag.QUESTION to "question", CaptureTag.DISTRACTION to "distraction",
)

// ---- window scoping (mirrors lib/analytics-window.ts)

/** Window start (epoch ms): Monday 00:00 local for WEEK, the 1st for MONTH, null for ALL. */
fun insightsWindowStart(window: InsightsWindow, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (window) {
        InsightsWindow.ALL -> null
        InsightsWindow.WEEK -> weekStartDate(day).atStartOfDay(zone).toInstant().toEpochMilli()
        InsightsWindow.MONTH -> day.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

private fun inWindow(iso: String, lo: Long?): Boolean {
    if (lo == null) return true
    val t = Time.parseMillis(iso) ?: return false
    return t >= lo
}

fun insightsWindowLabel(window: InsightsWindow): String = when (window) {
    InsightsWindow.WEEK -> "WEEK SO FAR"
    InsightsWindow.MONTH -> "MONTH SO FAR"
    InsightsWindow.ALL -> "ALL TIME"
}

// ---- small formatters

/** Identical to the DeepDive "Focus this week" stat: `2h 45m`. */
private fun fmtHM(sec: Int): String = "${sec / 3600}h ${(sec % 3600) / 60}m"

/** `Wed 2 Sep` — local getters only. */
private fun fmtDay(ms: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone)
    return "${DAY_SHORT[d.dayOfWeek.value % 7]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
}

private fun plural(n: Int, word: String): String = "$n $word${if (n == 1) "" else "s"}"
private fun pct(frac: Double): Int = jsRound(frac * 100)
private fun signed(n: Int): String = if (n > 0) "+$n" else "$n"

/** JS `toFixed(1)`: ties round away from zero (1.25 → "1.3"), unlike printf's half-even. */
private fun toFixed1(x: Double): String = String.format(Locale.ROOT, "%.1f", Math.round(x * 10) / 10.0)

private fun quote(name: String): String {
    val clean = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    val shown = if (clean.length > MAX_NAME) clean.substring(0, MAX_NAME - 1) + "…" else clean
    return "\"$shown\""
}

/** DeepDive's median: sorted, then the element at floor(n/2). */
private fun medianSec(sessions: List<Session>): Int {
    val arr = sessions.map { it.actualSec }.sorted()
    return arr[arr.size / 2]
}

private enum class Verdict { UNDERESTIMATING, OVERESTIMATING, ABOUT_RIGHT }

/** One-line verdict from the calibration dots. A miss is anything outside the
 *  ±5 min slack. "Underestimating" when the over-runs outnumber the
 *  under-runs AND make up at least ~30% of the tracked sessions (so a single
 *  outlier among many hits can't flip the verdict); symmetric for over. */
private fun calibrationVerdict(over: Int, under: Int, n: Int): Verdict {
    val floor = n * 0.3
    if (over > under && over >= floor) return Verdict.UNDERESTIMATING
    if (under > over && under >= floor) return Verdict.OVERESTIMATING
    return Verdict.ABOUT_RIGHT
}

private fun verdictText(v: Verdict): String = when (v) {
    Verdict.UNDERESTIMATING -> "underestimating (things take longer than you plan)"
    Verdict.OVERESTIMATING -> "overestimating (things finish sooner than you plan)"
    Verdict.ABOUT_RIGHT -> "about right"
}

// ---- the report

fun renderInsights(
    tasks: List<TaskItem>,
    sessions: List<Session>,
    captures: List<Capture>,
    reasons: List<ReasonLog>,
    blocks: List<CalBlock>,
    nowMs: Long,
    window: InsightsWindow,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val start = insightsWindowStart(window, nowMs, zone)
    val scopedSessions = sessions.filter { inWindow(it.completedAt, start) }
    val scopedCaptures = captures.filter { inWindow(it.at, start) }
    val reasonLogs = reasons.filter { inWindow(it.at, start) }

    val lines = ArrayList<String>()

    // Header — which window, which local dates.
    val range = if (start != null) "${fmtDay(start, zone)} – ${fmtDay(nowMs, zone)}" else "to ${fmtDay(nowMs, zone)}"
    lines += "ok: Insights, ${insightsWindowLabel(window).lowercase()} ($range)."

    // Focus: total + count + median (DeepDive stat strip), by area (Report
    // stacked bars), peak weekday slot (DeepDive heatmap).
    if (scopedSessions.isEmpty()) {
        lines += "Focus: no focus sessions in this window."
    } else {
        val totalSec = scopedSessions.sumOf { it.actualSec }
        lines += "Focus: ${fmtHM(totalSec)} across ${plural(scopedSessions.size, "session")}, median ${jsRound(medianSec(scopedSessions) / 60.0)}m."

        // By area over the Report's default area order (web AREA_ORDER).
        val bars = weekdayAreaHours(scopedSessions, tasks)
        val areaLines = ArrayList<String>()
        for ((i, area) in DEFAULT_AREAS.withIndex()) {
            val hours = bars.sumOf { it.data[i] }
            if (hours > 0) areaLines += "$area ${toFixed1(hours)}h"
        }
        if (areaLines.isNotEmpty()) lines += "By area: ${areaLines.joinToString(", ")}."

        val grid = timeOfDayHeatmap(scopedSessions)
        var peakDow = -1
        var peakBucket = -1
        var peakHours = 0.0
        for ((dow, row) in grid.withIndex()) {
            for ((bucket, hours) in row.withIndex()) {
                if (hours > peakHours) { peakDow = dow; peakBucket = bucket; peakHours = hours }
            }
        }
        if (peakHours > 0) {
            // Heatmap rows are Mon..Fri (Monday-anchored index 0..4).
            lines += "Peak slot: ${DAY_SHORT[peakDow + 1]} ${HEAT_BUCKETS[peakBucket]} (${jsRound(peakHours * 60)} min)."
        }
    }

    // Estimates: the calibration card + a verdict the model can hand back
    // when asked "am I underestimating?".
    val dots = calibrationDots(scopedSessions, tasks)
    if (dots.isNotEmpty()) {
        val hit = calibrationHitRate(dots, SLACK_MIN)
        val over = dots.count { it.a - it.e > SLACK_MIN }
        val under = dots.count { it.e - it.a > SLACK_MIN }
        val meanDelta = jsRound(dots.sumOf { it.a - it.e }.toDouble() / dots.size)
        lines += "Estimates: ${pct(hit)}% of ${plural(dots.size, "estimated session")} landed within $SLACK_MIN min; " +
            "$over ran over, $under ran under; actual vs estimate averages ${signed(meanDelta)} min. " +
            "Verdict: ${verdictText(calibrationVerdict(over, under, dots.size))}."
    } else if (scopedSessions.isNotEmpty()) {
        lines += "Estimates: no sessions linked to an estimated task yet."
    }

    // Pauses: DeepDive "What pauses you" — reason × count (+ real minutes when logged).
    val pauses = pauseAnatomy(reasonLogs)
    if (pauses.isEmpty()) {
        lines += "Pauses: none logged in this window."
    } else {
        val top = pauses.take(MAX_PAUSES)
            .joinToString(", ") { "${it.reason} ${it.count}x${if (it.minutes > 0) " (${jsRound(it.minutes)}m)" else ""}" }
        lines += "Pauses: ${plural(reasonLogs.size, "reason")} logged; top: $top."
    }

    // Interruptions: Report histogram — captures written mid-session, by minutes in.
    val bins = interruptionBins(scopedCaptures, scopedSessions)
    val linked = bins.sum()
    if (linked > 0) {
        val peakIdx = bins.indexOf(bins.maxOrNull() ?: 0).coerceAtLeast(0)
        lines += "Interruptions: ${plural(linked, "capture")} mid-session, most around ${peakIdx * 3}–${(peakIdx + 1) * 3} min in."
    }

    // Re-entry: DeepDive "Re-entry within 5m" — only when a gap was measurable.
    val reentry = reEntryDistribution(scopedSessions)
    val gaps = reentry.sum()
    if (gaps > 0) {
        lines += "Re-entry: ${pct(reentry[0].toDouble() / gaps)}% of ${plural(gaps, "return")} to a task came within 5 min."
    }

    // Slipping: Report "Gentle friction" count + DeepDive slip detector names.
    val slips = slipping(tasks, nowMs)
    if (slips.isEmpty()) {
        lines += "Slipping: none."
    } else {
        val shown = slips.take(MAX_SLIPS).joinToString("; ") { "${quote(it.name)} (moved ${it.moveCount}x, ${it.weeks}wk on list)" }
        val more = if (slips.size > MAX_SLIPS) " +${slips.size - MAX_SLIPS} more" else ""
        lines += "Slipping: ${plural(slips.size, "task")} — $shown$more."
    }

    // Captures by kind (window-scoped, same order as the DeepDive band).
    if (scopedCaptures.isEmpty()) {
        lines += "Captures: none."
    } else {
        val counts = captureBreakdown(scopedCaptures)
        val kinds = TAG_ORDER.filter { (counts[it.first] ?: 0) > 0 }.joinToString(", ") { "${it.second} ${counts[it.first] ?: 0}" }
        lines += "Captures: ${scopedCaptures.size} — $kinds."
    }

    // Planned: own calendar blocks dated inside the window up to today (local
    // date strings, so a block for "today" is today's in every timezone).
    val todayStr = IsoDate.format(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
    val startStr = start?.let { IsoDate.format(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }
    val planned = blocks.filter { b ->
        !b.skipped &&
            b.kind != CalBlockKind.EXTERNAL && b.externalEventId.isNullOrEmpty() &&
            b.date <= todayStr &&
            (startStr == null || b.date >= startStr)
    }
    if (planned.isNotEmpty()) {
        val mins = planned.sumOf { it.durationMinutes }
        lines += "Planned: ${plural(planned.size, "calendar block")} (${fmtHM(mins * 60)}) dated in this window."
    }

    // Worth noticing: the Report's own narrative cards — on OUR clock, so a
    // fixed-clock render is deterministic down to the slip ages.
    val insights = topInsights(scopedSessions, tasks, scopedCaptures, reasonLogs, nowMs).take(MAX_INSIGHTS)

    fun compose(withSubs: Boolean): String {
        if (insights.isEmpty()) return lines.joinToString("\n")
        val noticing = insights.map { "- ${it.title}${if (withSubs) " ${it.sub}" else ""}" }
        return (lines + listOf("Worth noticing:") + noticing).joinToString("\n")
    }

    val full = compose(withSubs = true)
    if (full.length <= INSIGHTS_MAX_CHARS) return full
    val titlesOnly = compose(withSubs = false)
    if (titlesOnly.length <= INSIGHTS_MAX_CHARS) return titlesOnly
    return titlesOnly.substring(0, INSIGHTS_MAX_CHARS - 1) + "…"
}
