package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Assistant time cognisance — the local-clock helpers the executor and the
// context builder reason from. Port of the time section of
// lib/assistant/tools.ts (`localNowHM`, `freeWindowsToday`, `rejectPastTime`,
// `rejectPastDate`, and the `upcoming` map `buildAssistantContext` hands the
// model) and UnstuckCore/Logic/AssistantTime.swift. Pure: the caller passes
// today's date + the wall clock (or an [AssistantClock]).
//
// Dates are LOCAL 'YYYY-MM-DD' strings and times LOCAL 'HH:MM' — never
// ISO-8601 UTC for a date-only value (a UTC midnight reads back as the
// previous day west of Greenwich). Day arithmetic goes through
// java.time.LocalDate (DST-safe), matching the web's `setDate()`.

// ---- Local-date plumbing shared by the assistant modules

/** 'YYYY-MM-DD' helpers with the web's `parseIso` / `fmtIso` / `addDaysIso` /
 *  `mondayOf` semantics (tools.ts, patterns.ts, moments.ts). Named IsoDate so
 *  it never shadows java.time.LocalDate inside this package. */
object IsoDate {
    /** Local calendar date for 'YYYY-MM-DD', or null when malformed or
     *  impossible ("2026-02-30") — never a throw (see [parseYmdOrNull]). */
    fun parse(iso: String): LocalDate? = parseYmdOrNull(iso)

    /** Field-by-field 'YYYY-MM-DD' (never a UTC round-trip). */
    fun format(d: LocalDate): String = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

    /** `iso` advanced by `n` whole days (DST-safe). Malformed input is
     *  returned unchanged — no comparison ever matches it, like the web's NaN date. */
    fun addDays(iso: String, n: Int): String = parse(iso)?.let { format(it.plusDays(n.toLong())) } ?: iso

    /** Monday of the week containing `iso`. */
    fun mondayOf(iso: String): String = parse(iso)?.let { format(weekStartDate(it)) } ?: iso

    /** JS `getDay()` of a 'YYYY-MM-DD' date: 0=Sun … 6=Sat; 0 when unparseable
     *  (the AppViewModel `jsDayOfWeek` contract). */
    fun dayOfWeek(iso: String): Int = parse(iso)?.dowJs() ?: 0

    /** Whole days from `fromIso` to `toIso` (local midnights). */
    fun daysUntil(fromIso: String, toIso: String): Int {
        val a = parse(fromIso) ?: return 0
        val b = parse(toIso) ?: return 0
        return java.time.temporal.ChronoUnit.DAYS.between(a, b).toInt()
    }

    /** Local calendar date of an ISO timestamp, or null when unparseable —
     *  the web's `dateOfStamp`. A timestamp without a zone designator is
     *  parsed as LOCAL time (like JS `new Date('2026-08-23T18:00:00')`). */
    fun dateOfStamp(stamp: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (stamp.isNullOrEmpty()) return null
        val ms = Time.parseMillis(stamp) ?: return null
        return format(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    }
}

/** JS `getDay()` of a 'YYYY-MM-DD' (0=Sun … 6=Sat), 0 when unparseable. */
fun jsDayOfWeek(iso: String): Int = IsoDate.dayOfWeek(iso)

private val DONE_WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/** "today" / "yesterday" / "Fri 19 Sep" for a stamp (a completedAt or a
 *  createdAt), in the device's zone against the app's local [today]; null when
 *  there is no stamp. get_tasks dates its lines with it, so an all-time
 *  completed list is never read back as "today" (parity with iOS build 75,
 *  f125845 `doneWhenLabel`). */
fun doneWhenLabel(stamp: String?, today: String, zone: ZoneId = ZoneId.systemDefault()): String? {
    val day = IsoDate.dateOfStamp(stamp, zone) ?: return null
    if (day == today) return "today"
    if (day == IsoDate.addDays(today, -1)) return "yesterday"
    return IsoDate.parse(day)?.format(DONE_WHEN_FORMAT) ?: day
}

// ---- HH:MM helpers

internal fun hmPad2(n: Int): String = "%02d".format(n)

/** `hmToMin`: 'HH:MM' → minutes since midnight (a missing/invalid part reads 0). */
fun hmToMin(hm: String): Int {
    val p = hm.split(":")
    val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
    val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    return h * 60 + m
}

fun minToHM(n: Int): String = "${hmPad2(n / 60)}:${hmPad2(n % 60)}"

/** Local wall-clock "HH:MM" of an instant — the ONLY time the model should reason from. */
fun localNowHM(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(nowMs).atZone(zone)
    return "${hmPad2(t.hour)}:${hmPad2(t.minute)}"
}

/** The clock the assistant reasons from — injectable so the executor and the
 *  context builder are testable at a pinned instant. */
class AssistantClock(
    val nowMs: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Local 'YYYY-MM-DD' of now. */
    fun todayIso(): String = IsoDate.format(Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate())

    /** Local 'HH:MM' of now. */
    fun nowHM(): String = localNowHM(nowMs(), zone)

    companion object {
        val SYSTEM = AssistantClock()

        /** A clock frozen at [ms] (tests, previews). */
        fun fixed(ms: Long, zone: ZoneId = ZoneId.systemDefault()) = AssistantClock({ ms }, zone)
    }
}

/** Lower-case weekday names in JS `getDay()` order — the context's `todayWeekday` / `upcoming` keys. */
val ASSISTANT_DAY_NAMES = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

/** Capitalised weekday names in JS `getDay()` order — used by the past-date refusal. */
val WEEKDAY_NAMES_CAP = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private const val DAY_END_MIN = 21 * 60

// ---- Free windows + past-time / past-date refusals

/** One open stretch of today, 'HH:MM'–'HH:MM'. */
data class FreeWindow(val from: String, val to: String)

/** Free windows for the REST of today (from the next quarter-hour after now
 *  until 21:00, minus every live block), ≥20 min, max 4. Deterministic, so
 *  "schedule two tasks today" at 15:07 can't be answered with 10:00. */
fun freeWindowsToday(blocks: List<CalBlock>, today: String, nowHM: String): List<FreeWindow> {
    val start = ((hmToMin(nowHM) + 5 + 14) / 15) * 15
    val busy = blocks
        .filter { it.date == today && !it.done && !it.skipped && it.startTime.isNotBlank() }
        .map { b -> hmToMin(b.startTime).let { s -> s to s + (b.durationMinutes.takeIf { it > 0 } ?: 30) } }
        .sortedBy { it.first }
    val out = ArrayList<FreeWindow>()
    var cursor = start
    for ((s, e) in busy) {
        if (s - cursor >= 20) out += FreeWindow(minToHM(cursor), minToHM(minOf(s, DAY_END_MIN)))
        cursor = maxOf(cursor, e)
        if (cursor >= DAY_END_MIN) break
    }
    if (DAY_END_MIN - cursor >= 20) out += FreeWindow(minToHM(cursor), minToHM(DAY_END_MIN))
    return out.take(4)
}

/** A time TODAY that has already passed is refused, with what's actually free —
 *  the model was proposing 10:00 at 15:00 (tester, 2026-09-02). Null = fine. */
fun rejectPastTime(blocks: List<CalBlock>, today: String, date: String, startTime: String?, nowHM: String): String? {
    if (startTime.isNullOrEmpty() || date != today) return null
    if (hmToMin(startTime) > hmToMin(nowHM)) return null
    val free = freeWindowsToday(blocks, today, nowHM)
    val freeTxt = if (free.isEmpty()) "nothing usable is left today — offer tomorrow"
    else "free today: " + free.joinToString(", ") { "${it.from}–${it.to}" }
    return "error: $startTime today is already past (it's $nowHM now). Ask for a later time or another day — $freeTxt."
}

private val ISO_DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** A schedule date before today is almost always the model's date math going
 *  wrong ("Monday" → last Monday). Refuse with the next occurrence so it can
 *  re-call correctly (2026-09-02). Null = fine. */
fun rejectPastDate(today: String, date: String, weekdayNames: List<String> = WEEKDAY_NAMES_CAP): String? {
    if (!ISO_DATE_RE.matches(date)) return "error: date must be YYYY-MM-DD (got \"$date\")"
    if (date >= today) return null
    val dow = IsoDate.dayOfWeek(date)
    val todayDow = IsoDate.dayOfWeek(today)
    val ahead = (((dow - todayDow) + 7) % 7).let { if (it == 0) 7 else it }
    val next = IsoDate.addDays(today, ahead)
    val name = weekdayNames[dow]
    return "error: $date is in the PAST (today is $today). If the user meant the coming $name, use $next — see context.upcoming. Never schedule into the past."
}

// ---- Context dates

/** Deterministic date resolution for the model: `tomorrow`, every weekday
 *  name → its NEXT date (1–7 days ahead; today's own weekday means next
 *  week), and `next_week_monday`. The model must COPY these verbatim. */
fun upcomingDates(today: String): Map<String, String> {
    val upcoming = LinkedHashMap<String, String>()
    upcoming["tomorrow"] = IsoDate.addDays(today, 1)
    val todayDow = IsoDate.dayOfWeek(today)
    for (i in 1..7) upcoming[ASSISTANT_DAY_NAMES[(todayDow + i) % 7]] = IsoDate.addDays(today, i)
    upcoming["next_week_monday"] = IsoDate.addDays(IsoDate.mondayOf(today), 7)
    return upcoming
}

/** Lower-case weekday of `today` ('monday'), the context's `todayWeekday`. */
fun weekdayName(today: String): String = ASSISTANT_DAY_NAMES[IsoDate.dayOfWeek(today)]

/** The context's `nowNote`: "it is 15:07 on wednesday — times earlier than this today are already gone". */
fun nowNote(today: String, nowHM: String): String =
    "it is $nowHM on ${weekdayName(today)} — times earlier than this today are already gone"
