package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// periodFacts — the Insights page's numbers for one period, from the SAME
// engine get_period_review renders (PeriodReview.kt: resolvePeriod,
// collectWindow, planFacts, stillOpenToday). So "Done 9 · Focused 2h 50m" on
// the page is exactly the review's "done 9 … focus 2h 50m" for that period
// (analytics plan 2026-09-24, D2/D3). Pure; every zone step takes [zone].
//
// Periods (D2): a week runs Monday 00:00 → Sunday, a month from the 1st, local.
// The page steps back to any past week or month (offset −1 = last week /
// last month). The comparison is the previous equivalent period, cut at the
// same minute of the day while the period includes today (the ref's rules).

enum class InsightsSpan { WEEK, MONTH, ALL }

private const val DAY_MS = 86_400_000L

private fun ymd(d: LocalDate): String = IsoDate.format(d)

/** The local 'YYYY-MM-DD' of [nowMs] in [zone]. */
fun localToday(nowMs: Long, zone: ZoneId): String = ymd(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())

/** The period the page shows. WEEK / MONTH: [offset] ≤ 0 steps back from the
 *  current one (−1 = last week / last month). ALL: from [earliest] (the first
 *  activity day; today when there is none) to today, with no comparison
 *  (prevFrom/prevTo equal `from`; callers check [PeriodRange.p] == "all"). */
fun insightsRange(span: InsightsSpan, offset: Int, today: String, earliest: String?): PeriodRange {
    val t = periodParseYmd(today)!!
    return when (span) {
        InsightsSpan.WEEK -> {
            val anchor = ymd(weekStartDate(t).plusWeeks(minOf(0, offset).toLong()))
            (resolvePeriod(PeriodReviewArgs("week_of", anchor, null, null), today) as PeriodResolution.Ok).range
        }
        InsightsSpan.MONTH -> {
            val anchor = ymd(t.withDayOfMonth(1).plusMonths(minOf(0, offset).toLong()))
            (resolvePeriod(PeriodReviewArgs("month_of", anchor, null, null), today) as PeriodResolution.Ok).range
        }
        InsightsSpan.ALL -> {
            val from = earliest?.takeIf { it <= today } ?: today
            val days = ChronoUnit.DAYS.between(periodParseYmd(from)!!, t).toInt() + 1
            PeriodRange("all", from, today, today, true, days, from, from)
        }
    }
}

/** The first local day with any activity — web's rule (lib/period-facts.ts
 *  `firstActivityDay`, the cross-platform pick of 2026-09-24): the earliest of
 *  a task created, a task done (its completedAt), and a COUNTED session's end
 *  (an accidental sub-minute start is no activity). Null when there is none.
 *  "All time" starts here and the page's ‹ stepper stops here. */
fun earliestActivityDay(data: PeriodData, zone: ZoneId): String? {
    var best: String? = null
    fun see(day: String?) { if (day != null && (best == null || day < best!!)) best = day }
    for (t in data.tasks) {
        see(data.stamp(t.createdAt, zone)?.day)
        if (t.done) see(data.stamp(t.completedAt, zone)?.day)
    }
    // PeriodData holds the counted sessions only (the D1 filter runs there).
    for (s in data.sessions) see(data.stamp(s.completedAt, zone)?.day)
    return best
}

/** Page label for a period: "This week", "Last week", "7–13 Sep"; "This month",
 *  "August", "August 2025"; "All time". */
fun insightsPeriodLabel(r: PeriodRange, today: String): String {
    val t = periodParseYmd(today)!!
    val f = periodParseYmd(r.from)!!
    return when (r.p) {
        "all" -> "All time"
        "week_of" -> {
            val thisMon = weekStartDate(t)
            when (f) {
                thisMon -> "This week"
                thisMon.minusWeeks(1) -> "Last week"
                else -> {
                    val to = periodParseYmd(r.to)!!
                    val mon = SHORT_MONTHS
                    val yr = if (to.year != t.year) " ${to.year}" else ""
                    if (f.monthValue == to.monthValue) "${f.dayOfMonth}–${to.dayOfMonth} ${mon[to.monthValue - 1]}$yr"
                    else "${f.dayOfMonth} ${mon[f.monthValue - 1]} – ${to.dayOfMonth} ${mon[to.monthValue - 1]}$yr"
                }
            }
        }
        else -> {
            if (f.year == t.year && f.monthValue == t.monthValue) "This month"
            else "${FULL_MONTHS[f.monthValue - 1]}${if (f.year != t.year) " ${f.year}" else ""}"
        }
    }
}

/** "vs the same point last week" / "vs last week" / "vs August" … the
 *  comparison caption under the stepper (null for All time). */
fun insightsComparisonLabel(r: PeriodRange, today: String): String? {
    if (r.p == "all") return null
    val soFar = if (r.clipped) "the same point " else ""
    return if (r.p == "week_of") "vs ${soFar}the week before"
    else {
        val pf = periodParseYmd(r.prevFrom)!!
        val t = periodParseYmd(today)!!
        "vs ${soFar}${if (r.clipped) "in " else ""}${FULL_MONTHS[pf.monthValue - 1]}${if (pf.year != t.year) " ${pf.year}" else ""}"
    }
}

private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val FULL_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** One day of the Daily rhythm chart. [future] = after the last reviewed day. */
data class DayFacts(val date: String, val done: Int, val focusSec: Int, val sessions: Int, val future: Boolean)

/** A repeating task's day in the period: filled / dash / hollow / faint. */
enum class SeriesState { DONE, SKIPPED, OPEN, UPCOMING }

data class SeriesDot(val date: String, val state: SeriesState)

data class SeriesRhythm(val taskId: String, val name: String, val lifeArea: String?, val dots: List<SeriesDot>) {
    val kept: Int get() = dots.count { it.state == SeriesState.DONE }
    /** Days that were due so far: done or still open. A day skipped on purpose
     *  was a choice, not a day owed, so it never counts against them ("kept 5
     *  of 6", not "of 7"); days still to come aren't due yet. Same as web
     *  `due` / iOS `dueSoFar`. */
    val soFar: Int get() = dots.count { it.state == SeriesState.DONE || it.state == SeriesState.OPEN }
}

/** A plain task finished in the period after waiting ≥ 7 days or being moved ≥ 2×. */
data class UnstuckWin(val task: TaskItem, val waitedDays: Int, val moves: Int)

const val WIN_MIN_WAIT_DAYS = 7
const val WIN_MIN_MOVES = 2

class InsightsFacts(
    val range: PeriodRange,
    val cur: WindowFacts,
    /** Null for All time. */
    val prev: WindowFacts?,
    /** Every day in [from, to] (future days flagged) — the Daily rhythm bars. */
    val days: List<DayFacts>,
    /** Days in [from, end] with ≥1 done or ≥1 session ("Showed up N of M"). */
    val showedUp: Int,
    val prevShowedUp: Int?,
    val plan: PlanFacts?,
    val stillOpenToday: Int,
    val series: List<SeriesRhythm>,
    val wins: List<UnstuckWin>,
    /** Named areas, count desc then name asc (the review's By area order). */
    val doneByArea: List<Pair<String, Int>>,
    val doneNoArea: Int,
) {
    val doneCount: Int get() = cur.doneCount
    val focusSec: Int get() = cur.focusSec
    val sessionCount: Int get() = cur.sessions.size
    /** Days so far (the "M" in "Showed up N of M days"). */
    val daysSoFar: Int get() = range.days
    val isEmpty: Boolean get() = cur.doneCount == 0 && cur.sessions.isEmpty() && cur.captures.isEmpty() && cur.pauses.isEmpty() && cur.created.isEmpty()
}

/** The page's facts for [r] (from [insightsRange]). */
fun insightsFacts(data: PeriodData, r: PeriodRange, nowMs: Long, zone: ZoneId): InsightsFacts {
    val nowZ = Instant.ofEpochMilli(nowMs).atZone(zone)
    val cut = if (r.clipped) nowZ.hour * 60 + nowZ.minute else null
    val cur = collectWindow(data, PeriodWindow(r.from, r.end, cut), zone)
    val prev = if (r.p == "all") null else collectWindow(data, PeriodWindow(r.prevFrom, r.prevTo, cut), zone)
    val perDay = perDayCounts(cur, data, zone)
    val days = ArrayList<DayFacts>()
    var d = periodParseYmd(r.from)!!
    val last = periodParseYmd(r.to)!!
    while (!d.isAfter(last)) {
        val k = ymd(d)
        val c = perDay[k] ?: DayCount()
        days += DayFacts(k, c.done, c.sec, c.sessions, k > r.end)
        d = d.plusDays(1)
    }
    val showedUp = perDay.values.count { it.done > 0 || it.sessions > 0 }
    val prevShowedUp = prev?.let { p -> perDayCounts(p, data, zone).values.count { it.done > 0 || it.sessions > 0 } }
    return InsightsFacts(
        range = r,
        cur = cur,
        prev = prev,
        days = days,
        showedUp = showedUp,
        prevShowedUp = prevShowedUp,
        plan = if (r.p == "all") null else planFacts(data, r, nowMs, zone),
        stillOpenToday = stillOpenToday(data, r),
        series = seriesRhythm(data, r),
        wins = unstuckWins(cur, data, zone),
        doneByArea = doneByAreaCounts(cur, data.byId),
        doneNoArea = doneWithoutArea(cur, data.byId),
    )
}

/** Each repeating task with ≥1 occurrence dated in [from, to]: one dot per
 *  occurrence by its block date (done / skipped on purpose / open / still to
 *  come — today is never "open"). Sorted by kept desc, then days due so far
 *  desc, then name, then id — web's order (lib/period-facts.ts `seriesRhythm`,
 *  the cross-platform pick of 2026-09-24). No streaks: it's a count of days,
 *  never a chain. */
fun seriesRhythm(data: PeriodData, r: PeriodRange): List<SeriesRhythm> {
    val judgeTo = if (r.clipped) IsoDate.addDays(r.end, -1) else r.end
    val byTask = LinkedHashMap<String, MutableList<SeriesDot>>()
    for (b in data.blocks.sortedWith(compareBy({ it.date }, { it.id }))) {
        if (!isTaskBlock(b) || b.date < r.from || b.date > r.to) continue
        val t = data.byId[b.taskId] ?: continue
        if (!isTemplateTask(t)) continue
        val state = when {
            b.skipped -> SeriesState.SKIPPED
            b.done -> SeriesState.DONE
            b.date > judgeTo -> SeriesState.UPCOMING
            else -> SeriesState.OPEN
        }
        byTask.getOrPut(t.id) { ArrayList() } += SeriesDot(b.date, state)
    }
    return byTask.map { (id, dots) ->
        val t = data.byId[id]!!
        SeriesRhythm(id, periodCleanName(t.name), t.lifeArea, dots)
    }.sortedWith(compareByDescending<SeriesRhythm> { it.kept }.thenByDescending { it.soFar }.thenBy { it.name }.thenBy { it.taskId })
}

/** "Got unstuck": plain tasks done in the window that had waited ≥ 7 days
 *  (completedAt − createdAt) or been moved ≥ 2×. Longest wait first. */
fun unstuckWins(cur: WindowFacts, data: PeriodData, zone: ZoneId): List<UnstuckWin> =
    cur.plainDone.mapNotNull { t ->
        val done = data.stamp(t.completedAt, zone)?.ms ?: return@mapNotNull null
        val created = data.stamp(t.createdAt, zone)?.ms
        val waited = if (created != null && done > created) ((done - created) / DAY_MS).toInt() else 0
        val moves = t.moveCount ?: 0
        if (waited >= WIN_MIN_WAIT_DAYS || moves >= WIN_MIN_MOVES) UnstuckWin(t, waited, moves) else null
    }.sortedWith(compareByDescending<UnstuckWin> { it.waitedDays }.thenByDescending { it.moves }.thenBy { periodCleanName(it.task.name) }.thenBy { it.task.id })

/** One bar of the trend chart. */
data class TrendBar(val from: String, val label: String, val done: Int, val focusSec: Int, val selected: Boolean, val soFar: Boolean)

/** The trend ending at the selected period: 8 weeks (Week), 6 months (Month),
 *  or every week since the first activity, up to 26 (All time). Each bar is the
 *  same [collectWindow] the headline uses, with the same "so far" cut. */
fun insightsTrend(data: PeriodData, span: InsightsSpan, offset: Int, nowMs: Long, zone: ZoneId): List<TrendBar> {
    val today = localToday(nowMs, zone)
    val nowZ = Instant.ofEpochMilli(nowMs).atZone(zone)
    val cutNow = nowZ.hour * 60 + nowZ.minute
    val out = ArrayList<TrendBar>()
    fun bar(r: PeriodRange, label: String, selected: Boolean) {
        val w = collectWindow(data, PeriodWindow(r.from, r.end, if (r.clipped) cutNow else null), zone)
        out += TrendBar(r.from, label, w.doneCount, w.focusSec, selected, r.clipped)
    }
    when (span) {
        InsightsSpan.WEEK -> for (k in 7 downTo 0) {
            val r = insightsRange(InsightsSpan.WEEK, offset - k, today, null)
            val f = periodParseYmd(r.from)!!
            bar(r, "${f.dayOfMonth} ${SHORT_MONTHS[f.monthValue - 1]}", k == 0)
        }
        InsightsSpan.MONTH -> for (k in 5 downTo 0) {
            val r = insightsRange(InsightsSpan.MONTH, offset - k, today, null)
            bar(r, SHORT_MONTHS[periodParseYmd(r.from)!!.monthValue - 1], k == 0)
        }
        InsightsSpan.ALL -> {
            val first = earliestActivityDay(data, zone) ?: today
            val weeks = (ChronoUnit.WEEKS.between(weekStartDate(periodParseYmd(first)!!), weekStartDate(periodParseYmd(today)!!)).toInt() + 1).coerceIn(1, 26)
            for (k in weeks - 1 downTo 0) {
                val r = insightsRange(InsightsSpan.WEEK, -k, today, null)
                val f = periodParseYmd(r.from)!!
                bar(r, "${f.dayOfMonth} ${SHORT_MONTHS[f.monthValue - 1]}", false)
            }
        }
    }
    return out
}

/** This week's window facts (Monday-anchored, so far, cut at this minute) —
 *  exactly what the Insights page reads for This week ([insightsFacts]'s
 *  `cur`), so the Today pill's "2h 5m focused" and "3 done" are the page's
 *  Focused and Done (D3). */
fun thisWeekFacts(data: PeriodData, nowMs: Long, zone: ZoneId): WindowFacts {
    val today = localToday(nowMs, zone)
    val r = insightsRange(InsightsSpan.WEEK, 0, today, null)
    val nowZ = Instant.ofEpochMilli(nowMs).atZone(zone)
    return collectWindow(data, PeriodWindow(r.from, r.end, nowZ.hour * 60 + nowZ.minute), zone)
}

/** This week's counted focus seconds (Monday-anchored, so far) — the Today
 *  pill's number, identical to the Insights page's This week "Focused" (D3). */
fun thisWeekFocusSec(data: PeriodData, nowMs: Long, zone: ZoneId): Int = thisWeekFacts(data, nowMs, zone).focusSec

/** "+2" / "same" / "−3" — a neutral change (never coloured, never judged). */
fun neutralDelta(d: Int): String = if (d == 0) "same" else if (d > 0) "+$d" else "−${-d}"

/** "+1h 5m" / "same" / "−40m" on rounded minutes (the review's rule). */
fun neutralDurDelta(curSec: Int, prevSec: Int): String {
    val d = periodMinutes(curSec) - periodMinutes(prevSec)
    return if (d == 0) "same" else if (d > 0) "+${periodDur(d)}" else "−${periodDur(-d)}"
}
