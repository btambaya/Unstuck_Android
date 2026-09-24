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

/** Monday-anchored day names for the hour × day heatmap rows. */
private val DAY_MON_FIRST = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

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

/** DeepDive's median: sorted, then the element at floor(n/2). [sessions] are counted ones. */
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
    /** The user's own area names in their order — the SAME list the Insights
     *  screen's "When focus happens" chart uses (DEFAULT_AREAS when they have
     *  none), so the assistant's By area equals the screen (cross-check P1-1). */
    areas: List<String> = DEFAULT_AREAS,
): String {
    val start = insightsWindowStart(window, nowMs, zone)
    // The shared D1 filter: no sub-minute starts, runaway timers clamped.
    val rawScoped = sessions.filter { inWindow(it.completedAt, start) }
    val scopedSessions = countableSessions(rawScoped)
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

        // By area — the screen's own list plus its "No area" bar.
        val bars = weekdayAreaHours(scopedSessions, tasks, areas, withNoArea = true)
        val areaLines = ArrayList<String>()
        for ((i, area) in (areas + NO_AREA_LABEL).withIndex()) {
            val hours = bars.sumOf { it.data[i] }
            if (hours > 0) areaLines += "$area ${toFixed1(hours)}h"
        }
        if (areaLines.isNotEmpty()) lines += "By area: ${areaLines.joinToString(", ")}."

        // Peak slot — the Deep dive heatmap (7 days × 24 h, by the hours each
        // session spanned), read in 2-hour steps.
        val grid = hourDayHeatmap(rawScoped, zone)
        var peakDow = -1
        var peakHour = -1
        var peakMin = 0.0
        for ((dow, row) in grid.withIndex()) {
            for (h in 0 until 24 step 2) {
                val m = row[h] + row[h + 1]
                if (m > peakMin + 1e-9) { peakDow = dow; peakHour = h; peakMin = m }
            }
        }
        if (peakMin > 0) {
            lines += "Peak slot: ${DAY_MON_FIRST[peakDow]} ${hourLabel(peakHour)}–${hourLabel(peakHour + 2)} (${jsRound(peakMin)} min)."
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
    // Same gate as the screen: fewer than 3 linked captures says nothing.
    if (linked >= INTERRUPTIONS_MIN_LINKED) {
        val peakIdx = bins.indexOf(bins.maxOrNull() ?: 0).coerceAtLeast(0)
        lines += "Interruptions: ${plural(linked, "capture")} mid-session, most around ${peakIdx * 3}–${(peakIdx + 1) * 3} min in."
    }

    // Coming back: DeepDive "How fast you come back" — pause → resume, only
    // when a pause length was measured.
    val back = comebackBins(reasonLogs)
    val measured = back.sum()
    if (measured > 0) {
        lines += "Coming back: ${pct(back[0].toDouble() / measured)}% of ${plural(measured, "timed pause")} ended within 5 min."
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
