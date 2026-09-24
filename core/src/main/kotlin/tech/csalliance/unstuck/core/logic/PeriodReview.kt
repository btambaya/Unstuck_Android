package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// get_period_review — "how has my week been?" — and the periodFacts engine the
// Insights page reads (week-review-spec.md; the port of period-review.ref.mjs,
// line for line). The shared vectors (PeriodReviewVectors.generated.kt) decide
// every byte of the output on all three platforms.
//
// ZONE DISCIPLINE: every zone-dependent step uses the [zone] parameter —
// `today` from nowMs + zone, and this file's OWN timestamp grammar (§3.1)
// resolving zone-less stamps with LocalDateTime.atZone(zone). Never Clock.*,
// Time.*, Time.parseMillis or IsoDate.dateOfStamp here: they read
// ZoneId.systemDefault(), which the tests pin to UTC so the New York vectors
// catch any stray call.
//
// Sessions go through the shared D1 filter (FocusFilter.kt) before anything
// is counted, so the review's focus numbers equal the Insights page's.

data class PeriodReviewArgs(val period: String?, val date: String?, val from: String?, val to: String?)

val PERIOD_NAMES = listOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "week_of", "month_of", "dates")
private const val PERIOD_LIST = "today, yesterday, this_week, last_week, this_month, last_month, week_of (with date), month_of (with date), or dates (with from and to)"
private val DAY_ABBR = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MON_ABBR = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTH_FULL = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
const val PERIOD_MAX_DAYS = 93
const val PERIOD_MAX_CHARS = 1600
private const val NAME_MAX = 40        // code points, incl. the "…"
private const val MAX_TASK_NAMES = 5
private const val MAX_SERIES = 3
private const val MAX_SLIPPED = 3
private const val MAX_DEADLINES = 2
private const val MAX_PAUSES = 3
private const val MAX_AREAS = 3

// ── local civil dates ('YYYY-MM-DD') ────────────────────────────────────────

private val YMD = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

/** The date, or null unless a REAL date in exactly this form with year ≥ 1900. */
fun periodParseYmd(s: String): LocalDate? {
    val m = YMD.matchEntire(s) ?: return null
    val y = m.groupValues[1].toInt()
    if (y < 1900) return null
    return try { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (_: DateTimeException) { null }
}

private fun fmtYmd(d: LocalDate): String = IsoDate.format(d)
private fun addDaysYmd(s: String, n: Int): String = fmtYmd(periodParseYmd(s)!!.plusDays(n.toLong()))
private fun mondayOfYmd(s: String): String = fmtYmd(weekStartDate(periodParseYmd(s)!!))
private fun firstOfMonth(s: String): String = s.substring(0, 8) + "01"
private fun daysInMonth(s: String): Int = periodParseYmd(s)!!.lengthOfMonth()
private fun lastOfMonth(s: String): String = s.substring(0, 8) + pad2(daysInMonth(s))
private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()
private fun daysBetween(a: String, b: String): Int = ChronoUnit.DAYS.between(periodParseYmd(a)!!, periodParseYmd(b)!!).toInt()

// ── instants: ONE grammar on every platform (§3.1) ──────────────────────────
//   YYYY-MM-DD 'T' HH:MM [:SS [.fraction(1-9 digits, truncated to ms)]] [Z | ±HH | ±HHMM | ±HH:MM]
// Fields must be in range (no roll-over). With a zone it is that instant;
// without one it is LOCAL wall-clock time in [zone] (a skipped wall time moves
// forward by the gap, a repeated one takes the earlier offset — exactly what
// LocalDateTime.atZone does). Anything else is unparseable (null).

private val STAMP = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?(Z|[+-]\\d{2}(?::?\\d{2})?)?")

/** Epoch ms of [stamp] under the §3.1 grammar, or null. */
fun periodStampMs(stamp: String?, zone: ZoneId): Long? {
    if (stamp == null) return null
    val m = STAMP.matchEntire(stamp) ?: return null
    val g = m.groupValues
    val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt(); val h = g[4].toInt(); val mi = g[5].toInt()
    val sec = if (g[6].isEmpty()) 0 else g[6].toInt()
    val ms = if (g[7].isEmpty()) 0 else (g[7] + "00").substring(0, 3).toInt()
    if (y < 1900 || mo < 1 || mo > 12 || d < 1 || d > YearMonth.of(y, mo).lengthOfMonth() || h > 23 || mi > 59 || sec > 59) return null
    val z = g[8]
    if (z.isEmpty()) return LocalDateTime.of(y, mo, d, h, mi, sec, ms * 1_000_000).atZone(zone).toInstant().toEpochMilli()
    var off = 0
    if (z != "Z") {
        val oh = z.substring(1, 3).toInt()
        val om = if (z.length > 3) z.substring(z.length - 2).toInt() else 0
        if (oh > 23 || om > 59) return null
        off = (if (z[0] == '-') -1 else 1) * (oh * 60 + om)
    }
    val utc = LocalDateTime.of(y, mo, d, h, mi, sec, ms * 1_000_000).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
    return utc - off * 60_000L
}

/** Local 'YYYY-MM-DD' of [stamp] in [zone], or null when absent / unparseable. */
fun periodDayOf(stamp: String?, zone: ZoneId): String? =
    periodStampMs(stamp, zone)?.let { fmtYmd(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }

private fun minuteOfMs(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }

// ── text ────────────────────────────────────────────────────────────────────

private val WS = Regex("[\\t\\n\\x0B\\f\\r \\u00A0]+")
private val WS_EDGES = Regex("^[\\t\\n\\x0B\\f\\r \\u00A0]+|[\\t\\n\\x0B\\f\\r \\u00A0]+$")
private fun trimWs(s: String): String = WS_EDGES.replace(s, "")

/** §4.1 cleanName: whitespace runs → one space, one edge space stripped,
 *  "(untitled)" when empty, >40 code points → first 39 + "…". */
fun periodCleanName(s: String?): String {
    var c = WS.replace(s ?: "", " ")
    if (c.startsWith(" ")) c = c.substring(1)
    if (c.endsWith(" ")) c = c.substring(0, c.length - 1)
    if (c.isEmpty()) return "(untitled)"
    val cps = c.codePointCount(0, c.length)
    return if (cps > NAME_MAX) c.substring(0, c.offsetByCodePoints(0, NAME_MAX - 1)) + "…" else c
}

private fun q(s: String?): String = "\"${periodCleanName(s)}\""
private fun plural(n: Int, one: String): String = "$n ${if (n == 1) one else one + "s"}"

/** Integer seconds → rounded minutes (§4.1 `minutes`). */
fun periodMinutes(sec: Int): Int = (sec + 30) / 60

/** `45m` / `2h` / `1h 5m` (§4.1 `dur`). */
fun periodDur(m: Int): String = if (m < 60) "${m}m" else if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
private fun signedInt(d: Int): String = if (d == 0) "same" else if (d > 0) "+$d" else "-${-d}"
private fun signedDur(d: Int): String = if (d == 0) "same" else if (d > 0) "+${periodDur(d)}" else "-${periodDur(-d)}"

/** `Www D Mmm`, plus ` YYYY` when the year isn't [todayYear]. Fixed English names. */
fun periodFmtDay(s: String, todayYear: Int): String {
    val d = periodParseYmd(s)!!
    return "${DAY_ABBR[d.dayOfWeek.value % 7]} ${d.dayOfMonth} ${MON_ABBR[d.monthValue - 1]}${if (d.year != todayYear) " ${d.year}" else ""}"
}

private fun fmtRange(a: String, b: String, y: Int): String = if (a == b) periodFmtDay(a, y) else "${periodFmtDay(a, y)} – ${periodFmtDay(b, y)}"

internal fun isTemplateTask(t: TaskItem?): Boolean = t != null && t.recurrence != null
private fun areaOf(t: TaskItem?): String? {
    val a = t?.lifeArea ?: return null
    return if (WS.replace(a, "").isNotEmpty()) periodCleanName(a) else null
}

// ── period resolution (§3.2) ────────────────────────────────────────────────

/** A resolved span. [to] is the nominal last day (the week's Sunday, the month's
 *  last day); [end] = min(to, today) is the last REVIEWED day. */
data class PeriodRange(
    val p: String,
    val from: String,
    val to: String,
    val end: String,
    val clipped: Boolean,
    val days: Int,
    val prevFrom: String,
    val prevTo: String,
)

sealed class PeriodResolution {
    data class Ok(val range: PeriodRange) : PeriodResolution()
    data class Err(val message: String) : PeriodResolution()
}

private class Need(val v: String? = null, val error: String? = null)

fun resolvePeriod(args: PeriodReviewArgs, today: String): PeriodResolution {
    fun s(v: String?): String? = v?.let { trimWs(it) }?.takeIf { it.isNotEmpty() }
    val raw = mapOf("period" to args.period, "date" to args.date, "from" to args.from, "to" to args.to)
    fun arg(k: String): String? = s(raw[k])
    val p = (arg("period") ?: "").lowercase()
    if (p.isEmpty()) return PeriodResolution.Err("error: period required — $PERIOD_LIST")
    if (p !in PERIOD_NAMES) return PeriodResolution.Err("error: unknown period \"$p\" — use $PERIOD_LIST")
    fun need(k: String, alias: String? = null): Need {
        val key = if (arg(k) == null && alias != null && arg(alias) != null) alias else k
        val v = arg(key) ?: return Need(error = "error: period=$p needs $k (YYYY-MM-DD)")
        if (periodParseYmd(v) == null) return Need(error = "error: $key must be a real date as YYYY-MM-DD (got \"$v\")")
        return Need(v = v)
    }
    val from: String
    val to: String
    val group: String
    when (p) {
        "today" -> { from = today; to = today; group = "day" }
        "yesterday" -> { from = addDaysYmd(today, -1); to = from; group = "day" }
        "this_week" -> { from = mondayOfYmd(today); to = addDaysYmd(from, 6); group = "week" }
        "last_week" -> { from = addDaysYmd(mondayOfYmd(today), -7); to = addDaysYmd(from, 6); group = "week" }
        "week_of" -> { val r = need("date"); r.error?.let { return PeriodResolution.Err(it) }; from = mondayOfYmd(r.v!!); to = addDaysYmd(from, 6); group = "week" }
        "this_month" -> { from = firstOfMonth(today); to = lastOfMonth(today); group = "month" }
        "last_month" -> { from = firstOfMonth(addDaysYmd(firstOfMonth(today), -1)); to = lastOfMonth(from); group = "month" }
        "month_of" -> { val r = need("date"); r.error?.let { return PeriodResolution.Err(it) }; from = firstOfMonth(r.v!!); to = lastOfMonth(r.v); group = "month" }
        else -> { // dates
            val a = need("from", "date"); a.error?.let { return PeriodResolution.Err(it) }
            from = a.v!!
            if (arg("to") == null) to = from
            else { val b = need("to"); b.error?.let { return PeriodResolution.Err(it) }; to = b.v!! }
            if (to < from) return PeriodResolution.Err("error: to ($to) is before from ($from)")
            group = "day"
        }
    }
    if (from > today) return PeriodResolution.Err("error: $from is after today ($today) — a review only covers what already happened; for what's coming up use get_schedule")
    val clipped = to >= today
    val end = if (to < today) to else today
    val days = daysBetween(from, end) + 1
    if (days > PERIOD_MAX_DAYS) return PeriodResolution.Err("error: that's $days days — a review covers at most $PERIOD_MAX_DAYS days; ask which week or month they mean, or review it in parts")
    val prevFrom: String
    val prevTo: String
    when (group) {
        "week" -> { prevFrom = addDaysYmd(from, -7); prevTo = addDaysYmd(end, -7) }
        "month" -> {
            prevFrom = firstOfMonth(addDaysYmd(from, -1))
            prevTo = if (clipped) prevFrom.substring(0, 8) + pad2(minOf(periodParseYmd(end)!!.dayOfMonth, daysInMonth(prevFrom))) else lastOfMonth(prevFrom)
        }
        else -> { prevFrom = addDaysYmd(from, -days); prevTo = addDaysYmd(from, -1) }
    }
    return PeriodResolution.Ok(PeriodRange(p, from, to, end, clipped, days, prevFrom, prevTo))
}

private fun label(r: PeriodRange, y: Int): String {
    val range = fmtRange(r.from, r.end, y)
    return when (r.p) {
        "today" -> "today ($range, so far)"
        "yesterday" -> "yesterday ($range)"
        "this_week" -> "this week ($range, so far)"
        "last_week" -> "last week ($range)"
        "week_of" -> "the week $range${if (r.clipped) " (so far)" else ""}"
        "this_month" -> "this month ($range, so far)"
        "last_month" -> "last month ($range)"
        "month_of" -> { val d = periodParseYmd(r.from)!!; "${MONTH_FULL[d.monthValue - 1]} ${d.year} ($range${if (r.clipped) ", so far" else ""})" }
        else -> "$range${if (r.clipped) " (so far)" else ""}"
    }
}

// ── one window's facts (the ref's collect(); the shared periodFacts core) ──

/** Everything a review / the Insights page reads. [sessions] may be raw: the
 *  D1 filter is applied here, once. */
class PeriodData(
    val tasks: List<TaskItem>,
    val blocks: List<CalBlock>,
    sessions: List<Session>,
    val captures: List<Capture>,
    val reasons: List<ReasonLog>,
) {
    val sessions: List<Session> = countableSessions(sessions)
    val byId: Map<String, TaskItem> = tasks.associateBy { it.id }
}

/** `{from, to}` inclusive local dates; [cut] = minute-of-day on `to`, or null. */
data class PeriodWindow(val from: String, val to: String, val cut: Int?)

/** One window's counted rows — the ref's `collect`. */
class WindowFacts(
    val plainDone: List<TaskItem>,
    val occDone: List<CalBlock>,
    val sessions: List<Session>,
    val captures: List<Capture>,
    val pauses: List<ReasonLog>,
    val created: List<TaskItem>,
) {
    val focusSec: Int = sessions.sumOf { secOf(it) }
    val doneCount: Int get() = plainDone.size + occDone.size
}

internal fun secOf(s: Session): Int = maxOf(0, s.actualSec)

/** The window predicate: the stamp's local day is in [from, to], and on `to`
 *  its minute-of-day is ≤ the cut when one applies. */
fun inPeriodWindow(stamp: String?, win: PeriodWindow, zone: ZoneId): Boolean {
    val ms = periodStampMs(stamp, zone) ?: return false
    val d = fmtYmd(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    if (d < win.from || d > win.to) return false
    return win.cut == null || d != win.to || minuteOfMs(ms, zone) <= win.cut
}

fun collectWindow(data: PeriodData, win: PeriodWindow, zone: ZoneId): WindowFacts {
    fun inWin(stamp: String?) = inPeriodWindow(stamp, win, zone)
    val plainDone = data.tasks.filter { !isTemplateTask(it) && it.done && inWin(it.completedAt) }
    val occDone = data.blocks.filter { b ->
        isTaskBlock(b) && isTemplateTask(data.byId[b.taskId]) && b.done && !b.skipped &&
            (if (periodStampMs(b.completedAt, zone) != null) inWin(b.completedAt) else b.date >= win.from && b.date <= win.to)
    }
    return WindowFacts(
        plainDone = plainDone,
        occDone = occDone,
        sessions = data.sessions.filter { inWin(it.completedAt) },
        captures = data.captures.filter { inWin(it.at) },
        pauses = data.reasons.filter { inWin(it.at) },
        created = data.tasks.filter { inWin(it.createdAt) },
    )
}

/** The local day an occurrence's check-off counts on: its completedAt's day,
 *  else its block date. */
fun occurrenceDay(b: CalBlock, zone: ZoneId): String = periodDayOf(b.completedAt, zone) ?: b.date

// ── plan vs outcome (§3.4, judged days only) ────────────────────────────────

data class SlippedTask(val task: TaskItem, val date: String)
data class SeriesCount(val id: String, val name: String, val n: Int)

class PlanFacts(
    /** Plain tasks planned on a judged day → the latest such block date. */
    val plannedPlain: Map<String, String>,
    val plainDoneToPlan: Int,
    /** Planned plain tasks not done by the period's end, plan date asc. */
    val slipped: List<SlippedTask>,
    val occPlanned: Int,
    val occDoneToPlan: Int,
    /** Occurrences on judged days, not done and not skipped, by series. */
    val missed: List<SeriesCount>,
    /** Occurrences in [from, end] (today included) skipped on purpose. */
    val skipped: Int,
    /** Plain tasks whose deadline in [from, end] passed unmet. */
    val deadlines: List<TaskItem>,
) {
    val planned: Int get() = plannedPlain.size + occPlanned
    val doneToPlan: Int get() = plainDoneToPlan + occDoneToPlan
    val missedTotal: Int get() = missed.sumOf { it.n }
    val recorded: Boolean get() = planned > 0 || slipped.isNotEmpty() || missed.isNotEmpty() || skipped > 0 || deadlines.isNotEmpty()
}

/** The Plan facts of [r], or null when no day is judged yet (today / a Monday
 *  this week: today isn't over, so it is never judged). */
fun planFacts(data: PeriodData, r: PeriodRange, nowMs: Long, zone: ZoneId): PlanFacts? {
    val judgeTo = if (r.clipped) addDaysYmd(r.end, -1) else r.end
    if (judgeTo < r.from) return null
    val plannedPlain = LinkedHashMap<String, String>()
    var occPlanned = 0
    var occDoneN = 0
    var skipped = 0
    val missed = LinkedHashMap<String, SeriesCount>()
    for (b in data.blocks) {
        if (!isTaskBlock(b)) continue
        val t = data.byId[b.taskId] ?: continue   // orphan block — ignored
        if (isTemplateTask(t)) {
            if (b.date < r.from || b.date > r.end) continue
            if (b.skipped) { skipped++; continue }  // skips count through today
            if (b.date > judgeTo) continue
            occPlanned++
            if (b.done) occDoneN++
            else { val g = missed[t.id] ?: SeriesCount(t.id, periodCleanName(t.name), 0); missed[t.id] = g.copy(n = g.n + 1) }
        } else if (b.date >= r.from && b.date <= judgeTo) {
            val was = plannedPlain[t.id]
            if (was == null || b.date > was) plannedPlain[t.id] = b.date
        }
    }
    var plainDoneN = 0
    val slipped = ArrayList<SlippedTask>()
    for ((id, date) in plannedPlain) {
        val t = data.byId[id]!!
        if (t.done && (periodStampMs(t.completedAt, zone) == null || periodDayOf(t.completedAt, zone)!! <= r.end)) plainDoneN++
        else slipped += SlippedTask(t, date)
    }
    slipped.sortWith(compareBy<SlippedTask> { it.date }.thenBy { periodCleanName(it.task.name) }.thenBy { it.task.id })
    val deadlines = data.tasks.filter { t ->
        val due = periodStampMs(t.dueAt, zone)
        if (isTemplateTask(t) || due == null || due >= nowMs) return@filter false
        val d = periodDayOf(t.dueAt, zone)!!
        if (d < r.from || d > r.end) return@filter false
        val doneAt = if (t.done) periodStampMs(t.completedAt, zone) else null
        !(doneAt != null && doneAt <= due)
    }.sortedWith(compareBy<TaskItem> { periodStampMs(it.dueAt, zone)!! }.thenBy { it.id })
    val missedSorted = missed.values.sortedWith(compareByDescending<SeriesCount> { it.n }.thenBy { it.name }.thenBy { it.id })
    return PlanFacts(plannedPlain, plainDoneN, slipped, occPlanned, occDoneN, missedSorted, skipped, deadlines)
}

/** Still open today (clipped periods only): today's open occurrences plus the
 *  distinct open plain tasks with a block today. */
fun stillOpenToday(data: PeriodData, r: PeriodRange): Int {
    if (!r.clipped) return 0
    var n = 0
    val openPlain = HashSet<String>()
    for (b in data.blocks) {
        if (!isTaskBlock(b) || b.date != r.end) continue
        val t = data.byId[b.taskId] ?: continue
        if (isTemplateTask(t)) { if (!b.done && !b.skipped) n++ }
        else if (!t.done) openPlain += t.id
    }
    return n + openPlain.size
}

// ── the review (§4) ─────────────────────────────────────────────────────────

fun renderPeriodReview(
    args: PeriodReviewArgs,
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    sessions: List<Session>,
    captures: List<Capture>,
    reasons: List<ReasonLog>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    historyFloor: String? = null,
    blocksPartial: Boolean = false,
): String {
    val nowZ = Instant.ofEpochMilli(nowMs).atZone(zone)
    val today = fmtYmd(nowZ.toLocalDate())
    val y = nowZ.year
    val r = when (val res = resolvePeriod(args, today)) {
        is PeriodResolution.Err -> return res.message
        is PeriodResolution.Ok -> res.range
    }
    val data = PeriodData(tasks, blocks, sessions, captures, reasons)
    // Both windows stop at the same minute-of-day on their last day when the period includes today.
    val cut = if (r.clipped) nowZ.hour * 60 + nowZ.minute else null
    val cur = collectWindow(data, PeriodWindow(r.from, r.end, cut), zone)
    val prev = collectWindow(data, PeriodWindow(r.prevFrom, r.prevTo, cut), zone)
    val byId = data.byId
    val header = "ok: review of ${label(r, y)}."
    val lines = arrayListOf(header)

    // 1. Done
    val doneTotal = cur.doneCount
    val plain = cur.plainDone.sortedWith(
        compareByDescending<TaskItem> { it.estimateMin }
            .thenBy { periodStampMs(it.completedAt, zone)!! }
            .thenBy { it.id },
    )
    val series = LinkedHashMap<String, SeriesCount>()
    for (b in cur.occDone) {
        val id = b.taskId!!
        val g = series[id] ?: SeriesCount(id, periodCleanName(byId[id]!!.name), 0)
        series[id] = g.copy(n = g.n + 1)
    }
    val seriesSorted = series.values.sortedWith(compareByDescending<SeriesCount> { it.n }.thenBy { it.name }.thenBy { it.id })
    val doneParts = ArrayList<String>()
    if (plain.isNotEmpty()) {
        val more = if (plain.size > MAX_TASK_NAMES) " +${plain.size - MAX_TASK_NAMES} more" else ""
        doneParts += "${plural(plain.size, "task")} — ${plain.take(MAX_TASK_NAMES).joinToString(", ") { q(it.name) }}$more"
    }
    if (seriesSorted.isNotEmpty()) {
        val more = if (seriesSorted.size > MAX_SERIES) " +${seriesSorted.size - MAX_SERIES} more" else ""
        doneParts += "${plural(cur.occDone.size, "repeating check-off")} — ${seriesSorted.take(MAX_SERIES).joinToString(", ") { "\"${it.name}\" ×${it.n}" }}$more"
    }
    lines += if (doneParts.isNotEmpty()) "Done: ${doneParts.joinToString("; plus ")}." else "Done: nothing marked done."

    // 2. Focus
    val n = cur.sessions.size
    if (n == 0) lines += "Focus: no focus sessions logged."
    else {
        val longest = cur.sessions.sortedWith(
            compareByDescending<Session> { secOf(it) }.thenBy { periodStampMs(it.completedAt, zone)!! }.thenBy { it.id },
        ).first()
        if (n == 1) lines += "Focus: 1 session, ${periodDur(periodMinutes(cur.focusSec))} on ${q(longest.taskName)}."
        else {
            var line = "Focus: $n sessions, ${periodDur(periodMinutes(cur.focusSec))} in all, average ${periodDur(periodMinutes(cur.focusSec / n))}, longest ${periodDur(periodMinutes(secOf(longest)))} on ${q(longest.taskName)}"
            if (n >= 3) {
                // Group by task id (else cleaned name); the name shown is the NEWEST session's.
                val newestFirst = cur.sessions.sortedWith(compareByDescending<Session> { periodStampMs(it.completedAt, zone)!! }.thenBy { it.id })
                val groups = LinkedHashMap<String, Triple<String, String, Int>>()   // key → (key, name, sec)
                for (s in newestFirst) {
                    val key = if (!s.taskId.isNullOrEmpty()) "id:${s.taskId}" else "name:${periodCleanName(s.taskName)}"
                    val g = groups[key] ?: Triple(key, periodCleanName(s.taskName), 0)
                    groups[key] = g.copy(third = g.third + secOf(s))
                }
                val top = groups.values.sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.second }.thenBy { it.first }).first()
                line += "; most time on \"${top.second}\" (${periodDur(periodMinutes(top.third))})"
            }
            lines += "$line."
        }
    }

    // 3. Plan — judged days are from..end, minus today (today isn't over).
    val plan = planFacts(data, r, nowMs, zone)
    var planRecorded = false
    if (plan != null) {
        val bits = ArrayList<String>()
        if (plan.slipped.isNotEmpty()) {
            val items = plan.slipped.take(MAX_SLIPPED).joinToString(", ") { (t, date) ->
                if (t.done) "${q(t.name)} (${periodFmtDay(date, y)}, done later)"
                else "${q(t.name)} (${periodFmtDay(date, y)}, still open) [id=${t.id}]"
            }
            val more = if (plan.slipped.size > MAX_SLIPPED) " +${plan.slipped.size - MAX_SLIPPED} more" else ""
            bits += "${plural(plan.slipped.size, "task")} slipped — $items$more"
        }
        if (plan.missed.isNotEmpty()) {
            val more = if (plan.missed.size > MAX_SERIES) " +${plan.missed.size - MAX_SERIES} more" else ""
            bits += "${plural(plan.missedTotal, "repeating day")} missed — ${plan.missed.take(MAX_SERIES).joinToString(", ") { "\"${it.name}\" ×${it.n}" }}$more"
        }
        if (plan.skipped > 0) bits += "${plural(plan.skipped, "repeating day")} skipped on purpose"
        if (plan.deadlines.isNotEmpty()) {
            val more = if (plan.deadlines.size > MAX_DEADLINES) " +${plan.deadlines.size - MAX_DEADLINES} more" else ""
            bits += "${plural(plan.deadlines.size, "deadline")} missed — ${plan.deadlines.take(MAX_DEADLINES).joinToString(", ") { "${q(it.name)} (${periodFmtDay(periodDayOf(it.dueAt, zone)!!, y)})" }}$more"
        }
        planRecorded = plan.planned > 0 || bits.isNotEmpty()
        lines += when {
            plan.planned > 0 -> "Plan: ${plan.doneToPlan} of ${plan.planned} planned done${if (bits.isNotEmpty()) "; " + bits.joinToString("; ") else ""}."
            bits.isNotEmpty() -> "Plan: ${bits.joinToString("; ")}."
            else -> "Plan: nothing was scheduled in this period."
        }
    }

    // 4. Still open today (only when the period includes today)
    val stillOpen = stillOpenToday(data, r)
    if (stillOpen > 0) lines += "Still open today: ${plural(stillOpen, "planned item")}."

    // 5. Also
    val also = ArrayList<String>()
    if (cur.created.isNotEmpty()) also += "added ${plural(cur.created.size, "task")}"
    if (cur.captures.isNotEmpty()) also += plural(cur.captures.size, "capture")
    if (cur.pauses.isNotEmpty()) {
        val g = LinkedHashMap<String, Int>()
        for (x in cur.pauses) {
            val k = if (WS.replace(x.reason, "").isNotEmpty()) periodCleanName(x.reason) else "Other"
            g[k] = (g[k] ?: 0) + 1
        }
        also += "pauses: ${g.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(MAX_PAUSES).joinToString(", ") { "${it.key} ×${it.value}" }}"
    }
    if (r.days >= 2) {
        val perDay = perDayCounts(cur, zone)
        val best = perDay.entries.sortedWith(
            compareByDescending<Map.Entry<String, DayCount>> { it.value.done }.thenByDescending { it.value.sec }.thenBy { it.key },
        ).firstOrNull()
        if (best != null && best.value.done >= 2) {
            val fm = periodMinutes(best.value.sec)
            also += "busiest day ${periodFmtDay(best.key, y)} (${best.value.done} done${if (fm > 0) ", ${periodDur(fm)} focus" else ""})"
        }
        also += "active ${perDay.values.count { it.done > 0 || it.sessions > 0 }} of ${r.days} days"
    }
    // No calendar-event count: Google meetings are per-device local mirrors (§3.8).

    // 6. By area
    val areas = doneByAreaCounts(cur, byId)
    val areaLine = if (areas.isNotEmpty())
        "By area: ${areas.take(MAX_AREAS).joinToString(", ") { "${it.first} ${it.second}" }}."
    else null

    // 7. Before that
    val prevRange = fmtRange(r.prevFrom, r.prevTo, y) + if (r.clipped) ", up to the same time" else ""
    val prevDone = prev.doneCount
    val prevEmpty = prevDone == 0 && prev.sessions.isEmpty()
    val curMin = periodMinutes(cur.focusSec)
    val prevMin = periodMinutes(prev.focusSec)
    val compare = if (prevEmpty) "Before that ($prevRange): nothing done and no focus logged."
    else "Before that ($prevRange): done $doneTotal vs $prevDone (${signedInt(doneTotal - prevDone)}), focus ${periodDur(curMin)} vs ${periodDur(prevMin)} (${signedDur(curMin - prevMin)}), sessions $n vs ${prev.sessions.size} (${signedInt(n - prev.sessions.size)})."

    // 8. Notes
    val notes = ArrayList<String>()
    if (r.p == "this_week" && r.days <= 2) notes += "note: only ${plural(r.days, "day")} into this week so far — if they meant the week that just ended, call again with period=last_week."
    if (r.p == "this_month" && r.days <= 3) notes += "note: only ${plural(r.days, "day")} into this month so far — if they meant the month that just ended, call again with period=last_month."
    if (historyFloor != null && historyFloor.isNotEmpty() && historyFloor > r.prevFrom) notes += "note: this device only holds focus, pause and capture history from ${periodFmtDay(historyFloor, y)} — anything before then isn't counted."
    if (blocksPartial) notes += "note: this device may be missing some calendar slots (over the 1,000-slot sync limit) — repeating check-offs and plan numbers may be low."

    // Nothing at all in the period → one explicit line (never "empty week").
    val nothing = doneTotal == 0 && n == 0 && cur.captures.isEmpty() && cur.pauses.isEmpty() &&
        cur.created.isEmpty() && stillOpen == 0 && !planRecorded
    val head: List<String> = if (nothing) listOf("${header.dropLast(1)}: nothing recorded — nothing marked done, no focus sessions, nothing planned or missed, no captures.")
    else lines + (if (also.isNotEmpty()) listOf("Also: ${also.joinToString("; ")}.") else emptyList()) + listOfNotNull(areaLine)
    val tail = (if (nothing && prevEmpty) emptyList() else listOf(compare)) + notes
    return capPeriodReview(head, tail, areaLine)
}

/** The §4.2 length cap: drop By area, then Also, then cut the HEAD (never the
 *  tail — the comparison and the notes) at [maxChars] UTF-16 units, dropping a
 *  dangling high surrogate and marking the cut with "…". */
fun capPeriodReview(headIn: List<String>, tail: List<String>, areaLine: String?, maxChars: Int = PERIOD_MAX_CHARS): String {
    var head = headIn
    fun joined() = (head + tail).joinToString("\n")
    var text = joined()
    if (text.length > maxChars && areaLine != null) { head = head.filter { it != areaLine }; text = joined() }
    if (text.length > maxChars) { head = head.filter { !it.startsWith("Also: ") }; text = joined() }
    if (text.length > maxChars) {
        val tailText = tail.joinToString("\n")
        val room = maxOf(0, maxChars - 1 - (if (tailText.isNotEmpty()) tailText.length + 1 else 0))
        var cut = head.joinToString("\n").take(room)
        if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
        text = cut + "…" + (if (tailText.isNotEmpty()) "\n" + tailText else "")
    }
    return text
}

/** Per local day: done (plain by completedAt's day, occurrences by
 *  [occurrenceDay]) and focus seconds / sessions by the session's END day. */
data class DayCount(val done: Int = 0, val sec: Int = 0, val sessions: Int = 0)

fun perDayCounts(w: WindowFacts, zone: ZoneId): Map<String, DayCount> {
    val perDay = LinkedHashMap<String, DayCount>()
    fun at(d: String) = perDay[d] ?: DayCount()
    for (t in w.plainDone) { val d = periodDayOf(t.completedAt, zone)!!; perDay[d] = at(d).let { it.copy(done = it.done + 1) } }
    for (b in w.occDone) { val d = occurrenceDay(b, zone); perDay[d] = at(d).let { it.copy(done = it.done + 1) } }
    for (s in w.sessions) { val d = periodDayOf(s.completedAt, zone)!!; perDay[d] = at(d).let { it.copy(sec = it.sec + secOf(s), sessions = it.sessions + 1) } }
    return perDay
}

/** Done per life area (named areas only, count desc then name asc) — the
 *  review's "By area" line and the page's Done-by-area bars (which add a
 *  "No area" bar from [doneWithoutArea]). */
fun doneByAreaCounts(w: WindowFacts, byId: Map<String, TaskItem>): List<Pair<String, Int>> {
    val areas = LinkedHashMap<String, Int>()
    for (t in w.plainDone) areaOf(t)?.let { areas[it] = (areas[it] ?: 0) + 1 }
    for (b in w.occDone) areaOf(byId[b.taskId])?.let { areas[it] = (areas[it] ?: 0) + 1 }
    return areas.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
}

/** Done items with no area (the page's "No area" bar). */
fun doneWithoutArea(w: WindowFacts, byId: Map<String, TaskItem>): Int =
    w.plainDone.count { areaOf(it) == null } + w.occDone.count { areaOf(byId[it.taskId]) == null }
