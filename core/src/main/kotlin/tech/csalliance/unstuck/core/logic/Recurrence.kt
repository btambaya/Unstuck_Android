package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.RecurrenceSerializer
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import java.time.LocalDate

// Port of lib/recurrence.ts. Materialise + regenerate cal_blocks for
// repeating tasks. Pure functions, no I/O — callers pipe the diff through
// sync. A task has at most one recurrence; when set, the client materialises
// cal_blocks RECURRENCE_HORIZON_DAYS ahead at the same start time. Past
// occurrences are preserved; future ones are regenerated on edit.

/** How far ahead we materialise occurrences on create/edit (8 weeks). */
const val RECURRENCE_HORIZON_DAYS = 56

data class MaterializedOccurrence(val date: String, val startTime: String)

private val Recurrence.daysOfWeek: List<Int>?
    get() = (this as? Recurrence.Weekly)?.daysOfWeek

/** Normalise stored weekly days to the canonical 0=Sun…6=Sat range. A row written
 *  by a different convention (e.g. JS getDay()'s 7 for Sunday, or a stray
 *  out-of-range value) would otherwise never match dayOfWeekJs (0..6), silently
 *  yielding zero occurrences. `((it % 7) + 7) % 7` folds any int into 0..6. */
fun normalizeWeekdays(days: List<Int>): List<Int> = days.map { ((it % 7) + 7) % 7 }.distinct()

private fun matchesRecurrence(r: Recurrence, startDate: Long, candidate: Long, candidateIso: String): Boolean {
    if (Time.startOfDayMillis(candidate) < Time.startOfDayMillis(startDate)) return false
    return when (r) {
        is Recurrence.Daily -> true
        is Recurrence.Weekly -> Time.dayOfWeekJs(candidate) in normalizeWeekdays(r.daysOfWeek)
        // Clamp the start day to the candidate month's length so a task set to the
        // 29th/30th/31st still fires on the last day of shorter months (Feb etc.)
        // instead of being silently skipped (web does this clamp).
        is Recurrence.Monthly -> Time.dayOfMonth(candidate) == minOf(Time.dayOfMonth(startDate), Time.daysInMonth(candidate))
        // From the CIVIL date only (spec §4): never a difference of instants, which
        // a spring-forward shortens to 13.958 days and a floor turns into an off week.
        is Recurrence.EveryNWeeks -> RecurrenceSerializer.strictYmd(candidateIso)?.let { everyNWeeksHas(r, it) } ?: false
    }
}

// ── every N weeks (every-n-weeks spec §4, Ahmad 2026-09-24) ──
//
// Week one is the ISO (Monday) week holding the rule's anchor; every interval-th
// week after it, and before it, counts. The week index is whole weeks between
// Mondays in EPOCH DAYS from the civil Y/M/D fields (LocalDate.toEpochDay) —
// never the ISO week-of-year number (2026 W53 and 2027 W01 would both be "on"),
// never a difference of instants (a DST day is 23 or 25 hours long).

/** The rule's days that are real weekdays (0=Sun…6=Sat), distinct and sorted.
 *  Out-of-range values are dropped, never folded into 0…6 (V11). */
fun everyNWeeksDays(r: Recurrence.EveryNWeeks): List<Int> = r.daysOfWeek.filter { it in 0..6 }.distinct().sorted()

/** The spec §2 validity rule for a rule built in memory (the codec never builds
 *  an invalid one): interval ≥ 1 and a real YYYY-MM-DD anchor. */
fun isValidEveryNWeeks(r: Recurrence.EveryNWeeks): Boolean =
    r.interval >= 1 && RecurrenceSerializer.strictYmd(r.anchor) != null

/** Epoch day of the Monday of [d]'s ISO week (1970-01-01 was a Thursday). */
private fun mondayEpochDay(d: LocalDate): Long {
    val e = d.toEpochDay()
    return e - Math.floorMod(e + 3, 7L)
}

/** Is [d] one of [r]'s dates (no start bound, no until)? */
private fun everyNWeeksHas(r: Recurrence.EveryNWeeks, d: LocalDate): Boolean {
    if (r.interval < 1) return false
    val anchor = RecurrenceSerializer.strictYmd(r.anchor) ?: return false
    if (d.dowJs() !in everyNWeeksDays(r)) return false
    val weeks = (mondayEpochDay(d) - mondayEpochDay(anchor)) / 7
    return Math.floorMod(weeks, r.interval.toLong()) == 0L
}

/** Do [a] and [b] fall in weeks the same whole number of [interval]s apart —
 *  the same "on" weeks for an every-[interval]-weeks rule? */
fun sameSeriesWeeks(a: String, b: String, interval: Int): Boolean {
    val da = RecurrenceSerializer.strictYmd(a) ?: return false
    val db = RecurrenceSerializer.strictYmd(b) ?: return false
    if (interval < 1) return false
    return Math.floorMod((mondayEpochDay(da) - mondayEpochDay(db)) / 7, interval.toLong()) == 0L
}

/** Monday ('YYYY-MM-DD') of the week holding [iso]; [iso] itself when malformed. */
fun mondayIso(iso: String): String =
    RecurrenceSerializer.strictYmd(iso)?.let { IsoDate.format(LocalDate.ofEpochDay(mondayEpochDay(it))) } ?: iso

/**
 * THE helper every writer chooses week one with (spec §4/§5): the Monday of the
 * first date on or after [fromIso] whose weekday is in [days]. A start on an off
 * weekday (a Friday for a Thursday rule) makes the NEXT Thursday's week week
 * one, instead of pushing the first Thursday back N weeks. Scans 0…6 days, so it
 * is only meaningful for a non-empty day set; with none it is [fromIso]'s Monday.
 */
fun seriesAnchor(days: List<Int>, fromIso: String): String {
    val from = RecurrenceSerializer.strictYmd(fromIso) ?: return fromIso
    val valid = days.filter { it in 0..6 }.toSet()
    for (i in 0..6) {
        val d = from.plusDays(i.toLong())
        if (d.dowJs() in valid) return IsoDate.format(LocalDate.ofEpochDay(mondayEpochDay(d)))
    }
    return IsoDate.format(LocalDate.ofEpochDay(mondayEpochDay(from)))
}

/**
 * The first date on or after [fromIso] that [rule] (weekly or every N weeks)
 * has, from the RULE alone — never from blocks, which may have been moved by
 * hand. Null past `until`, for another kind, or when the rule has no day.
 * Scans at most 7·N days.
 */
fun nextRuleDate(rule: Recurrence?, fromIso: String): String? {
    val from = RecurrenceSerializer.strictYmd(fromIso) ?: return null
    val span = when (rule) {
        is Recurrence.Weekly -> 7
        is Recurrence.EveryNWeeks -> if (isValidEveryNWeeks(rule)) 7 * minOf(rule.interval, 520) else return null
        else -> return null
    }
    val until = rule.until
    for (i in 0 until span) {
        val d = from.plusDays(i.toLong())
        val iso = IsoDate.format(d)
        if (until != null && iso > until) return null
        val hit = when (rule) {
            is Recurrence.Weekly -> d.dowJs() in normalizeWeekdays(rule.daysOfWeek)
            is Recurrence.EveryNWeeks -> everyNWeeksHas(rule, d)
            else -> false
        }
        if (hit) return iso
    }
    return null
}

/** Does [rule] have the civil date [iso] (its days and weeks, and until)? For
 *  weekly and every N weeks; false for any other kind. */
fun ruleHasDate(rule: Recurrence?, iso: String): Boolean {
    val d = RecurrenceSerializer.strictYmd(iso) ?: return false
    if (rule?.until?.let { iso > it } == true) return false
    return when (rule) {
        is Recurrence.Weekly -> d.dowJs() in normalizeWeekdays(rule.daysOfWeek)
        is Recurrence.EveryNWeeks -> everyNWeeksHas(rule, d)
        else -> false
    }
}

/** One "Starts" chip (spec §6): the series' first day on or after the base in
 *  one week, and that week's Monday (the anchor tapping it writes). */
data class StartsChip(val date: String, val anchor: String)

/**
 * The "Starts" chips for an every-[interval]-weeks rule on [days]: [interval]
 * consecutive weeks from the week of [seriesAnchor] (days, [baseIso]), each the
 * first series day ≥ [baseIso] in its week. Base Thu 24 Sep, Thursdays, N2 →
 * Thu 24 Sep · Thu 1 Oct; base Fri 25 Sep → Thu 1 Oct · Thu 8 Oct.
 */
fun startsChips(days: List<Int>, interval: Int, baseIso: String): List<StartsChip> {
    val base = RecurrenceSerializer.strictYmd(baseIso) ?: return emptyList()
    val valid = days.filter { it in 0..6 }.toSet()
    if (valid.isEmpty() || interval < 1) return emptyList()
    val first = RecurrenceSerializer.strictYmd(seriesAnchor(days, baseIso)) ?: return emptyList()
    val out = ArrayList<StartsChip>()
    for (k in 0 until interval) {
        val monday = first.plusDays(7L * k)
        val day = (0..6).map { monday.plusDays(it.toLong()) }.firstOrNull { !it.isBefore(base) && it.dowJs() in valid } ?: continue
        out += StartsChip(IsoDate.format(day), IsoDate.format(monday))
    }
    return out
}

/**
 * The base the "Starts" chips and the default week one of an every-N-weeks
 * EDIT start from (spec §5/§6), given the rule the task has now ([current]):
 *  • already every N weeks with the same N → the rule's next date, so the
 *    stored weeks are the pre-selected chip;
 *  • weekly, or every N weeks with another N → the week of the CURRENT rule's
 *    next date (never before today), so the next occurrence never jumps (E3);
 *  • daily, monthly, no repeat (or the create sheet: [current] null) → [startIso]
 *    (the picked day / the edit's start, else today).
 */
fun nWeeksBase(current: Recurrence?, newInterval: Int, todayIso: String, startIso: String): String = when {
    current is Recurrence.EveryNWeeks && isValidEveryNWeeks(current) && current.interval == newInterval ->
        nextRuleDate(current, todayIso) ?: todayIso
    current is Recurrence.Weekly || (current is Recurrence.EveryNWeeks && isValidEveryNWeeks(current)) ->
        maxOf(todayIso, nextRuleDate(current, todayIso)?.let(::mondayIso) ?: todayIso)
    else -> startIso
}

/**
 * Week one (the anchor Monday) an every-[newInterval]-weeks rule on [newDays] is
 * written with when no "Starts" chip was picked (spec §5): the STORED anchor for
 * an edit that keeps N (days, time or until changed — such an edit never moves
 * the weeks, E2), written as its Monday (spec §0 rule 3: writers normalise;
 * readers take the Monday of any anchor, so the weeks are the same) — else
 * [seriesAnchor] from [nWeeksBase].
 */
fun nWeeksAnchor(current: Recurrence?, newDays: List<Int>, newInterval: Int, todayIso: String, startIso: String): String =
    if (current is Recurrence.EveryNWeeks && isValidEveryNWeeks(current) && current.interval == newInterval) mondayIso(current.anchor)
    else seriesAnchor(newDays, nWeeksBase(current, newInterval, todayIso, startIso))

/**
 * Scheduling a series on [chosenIso] (Schedule, "Start repeating", the
 * assistant's first placement) means "the series starts here" (spec §5, Ahmad
 * 2026-09-24): an every-N-weeks rule is re-anchored to [seriesAnchor] (its days,
 * [chosenIso]). Null when there is nothing to change — another kind, or a day
 * whose weeks are the ones the rule already has — so the caller writes the task
 * row only when the weeks really move, BEFORE any plan or top-up reads the rule.
 */
fun reanchorForSchedule(rule: Recurrence?, chosenIso: String): Recurrence.EveryNWeeks? {
    if (rule !is Recurrence.EveryNWeeks || !isValidEveryNWeeks(rule)) return null
    if (everyNWeeksDays(rule).isEmpty() || RecurrenceSerializer.strictYmd(chosenIso) == null) return null
    val anchor = seriesAnchor(rule.daysOfWeek, chosenIso)
    if (sameSeriesWeeks(anchor, rule.anchor, rule.interval)) return null
    return rule.copy(anchor = anchor)
}

/**
 * The first [limit] dates on or after [todayIso] that [rule] has AND that hold a
 * live (open) occurrence of [taskId] — what set_task_recurrence's ok line names
 * (spec §7.2), read from the store after the writes. Today's own occurrence
 * counts while it is open.
 */
fun liveRuleDates(rule: Recurrence?, taskId: String, blocks: List<CalBlock>, todayIso: String, limit: Int = 2): List<String> =
    blocks.asSequence()
        .filter { it.taskId == taskId && isTaskBlock(it) && !it.done && !it.skipped && it.date >= todayIso }
        .map { it.date }
        .filter { ruleHasDate(rule, it) }
        .distinct().sorted().take(limit).toList()

/** Date/time pairs for a recurrence starting at startDate/startTime, going
 *  horizonDays ahead (inclusive of startDate). Stops at recurrence.until
 *  (inclusive) when set. `startDate` is a local-midnight epoch ms. */
fun materializeOccurrences(
    recurrence: Recurrence,
    startDate: Long,
    startTime: String,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): List<MaterializedOccurrence> {
    val out = mutableListOf<MaterializedOccurrence>()
    val untilIso = recurrence.until
    for (i in 0 until horizonDays) {
        val day = Time.addDaysMillis(startDate, i)
        val iso = Clock.dateIso(day)
        if (untilIso != null && iso > untilIso) break
        if (matchesRecurrence(recurrence, startDate, day, iso)) {
            out.add(MaterializedOccurrence(iso, startTime))
        }
    }
    return out
}

/** Diff to align a task's existing cal_blocks with `recurrence`: keep past
 *  occurrences, delete mismatched future ones, add missing ones. `todayIso`
 *  is injected so the boundary is testable.
 *
 *  The three lists are DISJOINT by id (rule B, deterministic-occurrence-ids.md
 *  §3b — stage 2, "same id for same day", Ahmad 2026-09-23), so callers may
 *  write them in any order:
 *   • [toUpsert] — NEW occurrences, each with its deterministic id: MINTS,
 *     written insert-if-absent (`insert_or_retime`);
 *   • [toRetime] — existing rows rewritten in place: an occurrence whose
 *     deterministic id the plan would otherwise delete and mint again (a time
 *     change). A plain upsert; the row keeps its Google mapping;
 *   • [toDelete] — ids to delete. */
data class RegenPlan(
    val toUpsert: List<CalBlock>,
    val toDelete: List<String>,
    val toRetime: List<CalBlock> = emptyList(),
)

/**
 * THE anchor a recurrence change regenerates from: the task's earliest LIVE
 * timed block at or after today, falling back to its latest past timed block
 * so a series with only history keeps its time of day. Null when the task has
 * no timed block at all (nothing to anchor on).
 *
 * Why (parity with iOS build 79, audit 2026-09-21): [regenerateForTask]
 * deletes every future block whose date|time doesn't match the anchor's, so
 * the anchor decides what survives. The editor and the assistant both used the
 * earliest block of ANY kind — done history at a time the series left long
 * ago, or a timeless legacy block. "Make Office every Monday at 11" then
 * deleted the Monday 11:00 block and rebuilt the series at the old time; and a
 * series whose first block was 56+ days old materialised nothing after today,
 * so any repeat edit deleted every future occurrence and added none.
 */
fun recurrenceAnchor(taskId: String, blocks: List<CalBlock>, todayIso: String): CalBlock? {
    val mine = blocks.filter { it.taskId == taskId && isTaskBlock(it) && it.startTime.isNotEmpty() }
    val live = mine.filter { !it.done && !it.skipped && it.date >= todayIso }
    live.minByOrNull { it.date + it.startTime }?.let { return it }
    return mine.maxByOrNull { it.date + it.startTime }
}

/**
 * [keepIds] are rows the edit must keep where they are ([RecurrenceStart.keepId]):
 * never deleted, never rewritten, and they count as HELD, so the day whose id
 * they carry is not minted again. They go INTO the plan, not around it: a caller
 * filtering `toDelete` afterwards could not stop rule B from moving a kept row
 * (deterministic-occurrence-ids.md §3b).
 *
 * Deterministic ids (stage 2 — "same id for same day", Ahmad 2026-09-23; parity
 * with iOS build 85 and web) add two rules:
 *  • rule A — a desired occurrence whose id a KEPT row already holds (moved,
 *    done, skipped, kept, history) is not minted: the day's occurrence lives on
 *    elsewhere, and a mint would twin it;
 *  • rule B — a desired occurrence whose id is a row in the delete set (a time
 *    change: the 07:00 row is deleted and the 09:00 one minted with the SAME id)
 *    becomes that row rewritten in place ([RegenPlan.toRetime]). Emitted as
 *    delete + mint, the assistant's set_task_recurrence (mints, then deletes)
 *    cancelled the mint with the delete and the day was lost.
 */
fun regenerateForTask(
    task: TaskItem,
    recurrence: Recurrence?,
    existingBlocks: List<CalBlock>,
    todayIso: String,
    startTime: String,
    startDate: Long,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
    keepIds: Set<String> = emptySet(),
): RegenPlan {
    val existing = existingBlocks.filter { it.taskId == task.id && isTaskBlock(it) }
    val futureExisting = existing.filter { it.date > todayIso }

    if (recurrence == null) {
        // Clearing recurrence — delete future occurrences but keep any the user
        // already completed/skipped (history), and any the edit keeps, same as web.
        return RegenPlan(emptyList(), futureExisting.filter { !it.done && !it.skipped && it.id !in keepIds }.map { it.id })
    }

    // A weekly recurrence with NO valid days (empty, or all out-of-range so they
    // normalise away) would materialise zero occurrences — regenerate would then
    // DELETE every future block and upsert nothing, silently erasing the series.
    // That's almost certainly a corrupt/legacy row, not an intentional "never
    // repeat", so skip regeneration and leave existing blocks untouched.
    if (recurrence is Recurrence.Weekly && normalizeWeekdays(recurrence.daysOfWeek).isEmpty()) {
        return RegenPlan(emptyList(), emptyList())
    }
    // The same for an every-N-weeks rule that can't produce a date (an invalid
    // one, or no day in 0…6): never read as "delete the whole future".
    if (recurrence is Recurrence.EveryNWeeks && (!isValidEveryNWeeks(recurrence) || everyNWeeksDays(recurrence).isEmpty())) {
        return RegenPlan(emptyList(), emptyList())
    }

    val desired = materializeOccurrences(recurrence, startDate, startTime, horizonDays)
        .filter { it.date > todayIso }
    val desiredKeys = desired.map { "${it.date}|${it.startTime}" }.toSet()
    val existingFutureKeys = futureExisting.map { "${it.date}|${it.startTime}" }.toSet()

    // Never delete an occurrence already completed/skipped — that would erase
    // history (and resurrect a done day as undone on retime).
    val toDelete = futureExisting.filter { !it.done && !it.skipped && "${it.date}|${it.startTime}" !in desiredKeys }.map { it.id }
    // A kept row is HELD: never deleted, never rewritten.
    val deleteSet = toDelete.filterTo(HashSet()) { it !in keepIds }
    val existingById = LinkedHashMap<String, CalBlock>()
    for (b in existing) existingById.putIfAbsent(b.id, b)

    val toUpsert = ArrayList<CalBlock>()
    val toRetime = ArrayList<CalBlock>()
    for (o in desired) {
        if ("${o.date}|${o.startTime}" in existingFutureKeys) continue
        val id = occurrenceId(task.id, o.date)
        val row = existingById[id]
        if (row != null && id in deleteSet) {
            // Rule B: the same id deleted + minted → rewrite it in place, from the
            // EXISTING row (it keeps its Google mapping). The net effect of the old
            // delete + fresh mint: the new date/time, open again.
            deleteSet.remove(id)
            toRetime += row.copy(
                date = o.date, startTime = o.startTime, taskName = task.name,
                durationMinutes = clampDurationMin(task.estimateMin),
                done = false, skipped = false, completedAt = null,
            )
        } else if (row != null) {
            continue   // rule A: held by a kept row (moved, done, skipped, kept, history)
        } else {
            toUpsert += occurrenceBlock(task, o)
        }
    }
    return RegenPlan(toUpsert, toDelete.filter { it in deleteSet }, toRetime)
}

/** A new occurrence block for [task] — the one place a series mints a block.
 *  Its id is the DETERMINISTIC [occurrenceId] (stage 2, "same id for same day",
 *  Ahmad 2026-09-23): two devices minting the same day land on one row instead
 *  of twins. Written insert-if-absent, never over a row. The server's CHECK is
 *  `duration_minutes between 5 and 1440`, so a 2-minute task minted occurrences
 *  it refused on flush, which then lived on this one phone for ever (parity with
 *  iOS build 81, audit 2026-09-22 C4). */
private fun occurrenceBlock(task: TaskItem, o: MaterializedOccurrence): CalBlock = CalBlock(
    id = occurrenceId(task.id, o.date), taskId = task.id, taskName = task.name,
    startTime = o.startTime, durationMinutes = clampDurationMin(task.estimateMin),
    date = o.date, kind = CalBlockKind.TASK,
)

/** The most common start time in [pool]. Ties go to the time used most across
 *  [tieBreak], then to the one used most recently, then to the later string.
 *  Null when nothing in the pool has a time. */
private fun mostCommonStartTime(pool: List<CalBlock>, tieBreak: List<CalBlock>): String? {
    val total = tieBreak.filter { it.startTime.isNotEmpty() }.groupingBy { it.startTime }.eachCount()
    return pool.filter { it.startTime.isNotEmpty() }.groupBy { it.startTime }.entries
        .maxWithOrNull(
            compareBy<Map.Entry<String, List<CalBlock>>>(
                { it.value.size }, { total[it.key] ?: 0 }, { it.value.maxOf { b -> b.date } }, { it.key },
            ),
        )?.key
}

/**
 * The time of day a series runs at: the most common start time among the
 * task's timed blocks in the [horizonDays] up to [frontierIso] (all of its
 * timed blocks when none fall in that window). Ties go to the time used most
 * across every block passed in, then to the most recent. Null when no block
 * has a time.
 *
 * Why not the next open occurrence (parity with iOS build 81, audit
 * 2026-09-22 C1): that is often the one the user moved by hand — the
 * notification's Reschedule, a calendar drag, schedule_task — and taking it as
 * the series time copied the whole series at the moved time. The window keeps
 * a whole-series re-plan to a new time from being outvoted by older history.
 */
fun recurrenceSeriesTime(
    taskId: String,
    blocks: List<CalBlock>,
    frontierIso: String,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): String? {
    val timed = blocks.filter { it.taskId == taskId && isTaskBlock(it) && it.startTime.isNotEmpty() }
    val from = IsoDate.addDays(frontierIso, -(horizonDays - 1))
    val recent = timed.filter { it.date >= from && it.date <= frontierIso }
    return mostCommonStartTime(recent.ifEmpty { timed }, tieBreak = timed)
}

private data class SeriesDay(val day: Int, val votes: Int)

/** The day of the month a monthly series runs on, voted by its LAST THREE
 *  occurrences (any state) with how many of them agree on it. A month-end
 *  clamp counts for the longer day (a 31st series shows Feb 28 / Apr 30), so
 *  the clamp recovers to 31. A 1-1 split goes to the earlier day: a hand move
 *  of one occurrence almost always pushes it later (carry to tomorrow, "push
 *  it back"). Null when there are no blocks. */
private fun recurrenceSeriesDay(blocks: List<CalBlock>): SeriesDay? {
    val dates = blocks.mapNotNull { IsoDate.parse(it.date) }.sorted().takeLast(3)
    var best: SeriesDay? = null
    for (day in dates.map { it.dayOfMonth }.toSet()) {
        val votes = dates.count { it.dayOfMonth == minOf(day, it.lengthOfMonth()) }
        val b = best
        if (b != null && (votes < b.votes || (votes == b.votes && day > b.day))) continue
        best = SeriesDay(day, votes)
    }
    return best
}

/** How many days either side of one of the series' dates a block still counts
 *  as that date's occurrence, moved: under half the gap to the neighbouring
 *  dates, so it is nearer that date than any other. A monthly date is at
 *  least 28 days from the next; a daily one has no room. */
internal fun occurrenceReach(r: Recurrence): Int = when (r) {
    is Recurrence.Daily -> 0
    is Recurrence.Weekly -> {
        val sorted = normalizeWeekdays(r.daysOfWeek).sorted()
        if (sorted.isEmpty()) 0
        else ((sorted.zipWithNext { a, b -> b - a } + (7 - sorted.last() + sorted.first())).min() - 1) / 2
    }
    is Recurrence.Monthly -> 14
    // Over the 7N-day cycle, with ISO positions (Mon=0 … Sun=6) of week one's
    // days: the gaps are the consecutive differences plus the wrap. N = 1 equals
    // the weekly value for every day set.
    is Recurrence.EveryNWeeks -> {
        val iso = everyNWeeksDays(r).map { (it + 6) % 7 }.sorted()
        if (iso.isEmpty() || r.interval < 1) 0
        else ((iso.zipWithNext { a, b -> b - a } + (7 * r.interval - iso.last() + iso.first())).min() - 1) / 2
    }
}

/** The nearest date on or before [onOrBefore] whose day of month is exactly
 *  [day] ([materializeOccurrences] takes a monthly series' day from its start
 *  date, so a clamped Feb 28 would turn a 31st series into a 28th one). */
private fun monthlyStart(day: Int, onOrBefore: String): String {
    for (back in 0 until 62) {
        val d = IsoDate.addDays(onOrBefore, -back)
        if (IsoDate.parse(d)?.dayOfMonth == day) return d
    }
    return onOrBefore
}

/** Where a recurrence EDIT regenerates the series from (setRecurrence,
 *  set_task_recurrence). */
data class RecurrenceStart(
    /** YYYY-MM-DD, handed to [regenerateForTask] as its startDate. */
    val date: String,
    /** HH:MM. */
    val startTime: String,
    /** Stretched so the horizon still ends 8 weeks after the anchor. */
    val horizonDays: Int,
    /** A block the edit must NOT delete although the plan lists it: this
     *  month's occurrence, moved later off a series day that has passed. */
    val keepId: String? = null,
)

/**
 * An edit keeps the series' OWN time and day. The time is the most common one
 * among its live upcoming occurrences once there are at least two (else the
 * anchor's, which keeps the "Office every Monday at 11" fix: one live 11:00
 * block over 09:15 history still gives 11:00). A monthly series takes its day
 * from the last three occurrences when two of them agree. Null when the task
 * has no timed block to anchor on.
 *
 * Why (parity with iOS build 81, audit 2026-09-22 C1): [regenerateForTask]
 * deletes every future block that doesn't match the start's date|time, and the
 * start was an arbitrary block. Editing only the repeat's end date on a day
 * when the next occurrence had been moved by hand deleted the whole series and
 * rebuilt it at the moved time (or, monthly, on the moved day).
 */
fun recurrenceEditStart(
    taskId: String,
    recurrence: Recurrence?,
    blocks: List<CalBlock>,
    todayIso: String,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): RecurrenceStart? {
    val anchor = recurrenceAnchor(taskId, blocks, todayIso) ?: return null
    val timed = blocks.filter { it.taskId == taskId && isTaskBlock(it) && it.startTime.isNotEmpty() }
    val live = timed.filter { !it.done && !it.skipped && it.date >= todayIso }
    val time = if (live.size >= 2) mostCommonStartTime(live, tieBreak = timed) ?: anchor.startTime else anchor.startTime
    // Every N weeks: the rule decides the weeks, so the edit regenerates from
    // TODAY over the full 56 days (spec §5). A start on the anchor block's day
    // or its Monday ended the window up to 6 days before the top-up's
    // today + 55, and every on-week block in that gap was deleted, then minted
    // again by the next top-up (vector E1).
    if (recurrence is Recurrence.EveryNWeeks) return RecurrenceStart(todayIso, time, horizonDays)
    // Two of the last three must agree before the day moves off the anchor's:
    // switching a weekly series to monthly keeps the next occurrence's day.
    val series = if (recurrence is Recurrence.Monthly) recurrenceSeriesDay(timed) else null
    if (series == null || series.votes < 2) return RecurrenceStart(anchor.date, time, horizonDays)
    val date = monthlyStart(series.day, onOrBefore = anchor.date)
    val back = IsoDate.daysUntil(date, anchor.date)
    // The anchor is this month's occurrence moved later, and the series day it
    // left has passed: regenerateForTask only wants dates after today, so it
    // deleted the moved one and the month lost its occurrence. Kept unless it
    // is past `until` or too far out to be this month's (then it is next
    // month's, moved earlier, and is re-aligned).
    val ownsPassedDay = date <= todayIso && anchor.date > todayIso &&
        back <= occurrenceReach(Recurrence.Monthly()) &&
        (recurrence?.until?.let { anchor.date <= it } ?: true)
    return RecurrenceStart(date, time, horizonDays + back, keepId = if (ownsPassedDay) anchor.id else null)
}

/**
 * The occurrences the horizon top-up adds for one repeating task: the TAIL only —
 * dates after both its latest block (the frontier) and today, up to today +
 * [horizonDays] - 1, at the series' own time ([recurrenceSeriesTime], or
 * [seriesTime] when the caller has just placed the series explicitly — the
 * placed occurrence is then the frontier, and a monthly series keeps its day).
 * Port of iOS build 85's recurrenceTopUp (audit 2026-09-22 C1 + C21); Android
 * had no top-up, so a series last edited here ran out 8 weeks later unless an
 * iOS device of the same user extended it.
 *
 * Why the tail only: a top-up that rebuilt the whole 8 weeks from the next open
 * occurrence brought back every occurrence the user deleted, unscheduled or
 * moved at its old slot, and a moved next occurrence copied the whole series at
 * its new time. Extending only past the frontier never fills a date the series
 * already covered.
 *  - Blocks after the horizon don't count toward the frontier, so one
 *    occurrence moved months ahead can't stop the series extending; a series
 *    with nothing in the horizon but blocks beyond it was re-planned to start
 *    later on purpose and is left alone.
 *  - The span starts at the frontier, so a series idle for more than 8 weeks
 *    comes back from tomorrow.
 *  - A monthly series keeps its day of month (recurrenceSeriesDay), not the
 *    frontier's, which may be a moved or clamped one. The vote reads blocks up
 *    to a month past the horizon, so a re-plan to a later day wins.
 *  - A date with one of the task's blocks within reach (occurrenceReach; any
 *    state, past the horizon too) already has its occurrence, moved.
 *  - Today is never minted, matching [regenerateForTask].
 *  - Rule A (stage 2): a date whose deterministic occurrence id one of the
 *    task's blocks already holds (any date, any state, past the horizon too) is
 *    never minted — that occurrence was moved further than occurrenceReach, and
 *    it lives on there.
 * Every block it returns carries [occurrenceId]: two devices topping up the same
 * tail mint the same ids, and the server keeps one row per day.
 */
fun recurrenceTopUp(
    task: TaskItem,
    existingBlocks: List<CalBlock>,
    todayIso: String,
    seriesTime: String? = null,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): List<CalBlock> {
    val recurrence = task.recurrence ?: return emptyList()
    // An unrecognised kind decodes to an inert sentinel: nothing to extend.
    if (RecurrenceSerializer.isUnknown(recurrence)) return emptyList()
    val mine = existingBlocks.filter { it.taskId == task.id && isTaskBlock(it) }
    val lastIso = IsoDate.addDays(todayIso, horizonDays - 1)
    val inHorizon = mine.filter { it.date <= lastIso }
    if (inHorizon.none { it.date > todayIso } && mine.any { it.date > lastIso }) return emptyList()
    val frontier = inHorizon.maxOfOrNull { it.date } ?: return emptyList()
    if (IsoDate.parse(frontier) == null) return emptyList()
    val time = seriesTime ?: recurrenceSeriesTime(task.id, inHorizon, frontier, horizonDays) ?: return emptyList()
    val floor = maxOf(frontier, todayIso)
    if (floor >= lastIso) return emptyList()
    var start = frontier
    val voters = mine.filter { it.date <= IsoDate.addDays(lastIso, 31) }
    if (seriesTime == null && recurrence is Recurrence.Monthly) {
        recurrenceSeriesDay(voters)?.let { start = monthlyStart(it.day, onOrBefore = frontier) }
    }
    val startMs = IsoDate.parse(start)?.let { Time.civil(it.year, it.monthValue, it.dayOfMonth) } ?: return emptyList()
    val reach = occurrenceReach(recurrence)
    val held = mine.mapTo(HashSet()) { it.id }
    return materializeOccurrences(recurrence, startMs, time, IsoDate.daysUntil(start, lastIso) + 1)
        .filter { it.date > floor }
        .filter { o ->
            val lo = IsoDate.addDays(o.date, -reach)
            val hi = IsoDate.addDays(o.date, reach)
            mine.none { it.date >= lo && it.date <= hi }
        }
        .filter { occurrenceId(task.id, it.date) !in held }
        .map { occurrenceBlock(task, it) }
}

/** What scheduling a series onto a day at a time must do on that day once a
 *  [RegenPlan] is applied. */
sealed class ChosenDateAction {
    /** The day already has its occurrence: nothing to write. */
    data object Covered : ChosenDateAction()
    /** Move this existing block to the chosen time (and un-skip it). */
    data class Retime(val block: CalBlock) : ChosenDateAction()
    /** Nothing on the day: mint a new occurrence. */
    data object Mint : ChosenDateAction()
}

/**
 * Pure decision behind scheduleTaskNow's guarantee on the chosen day (and the
 * assistant's schedule_task on a series). An existing block the plan is about
 * to DELETE does not count (or the day would end up empty); a planned upsert
 * on the date does.
 *
 * Why not "any block on the day covers it" (parity with iOS build 81, audit
 * 2026-09-22 C7): regenerateForTask never touches today, so scheduling a
 * series for today at 16:00 left today's occurrence at 07:00, and a SKIPPED
 * occurrence counted as coverage, so the day just scheduled stayed hidden. Now
 * an open occurrence at another time is retimed, a skipped one is retimed and
 * un-skipped, and a done one still covers the day so no second open copy
 * appears. Retiming rather than minting keeps one block per task per day.
 */
fun recurrenceChosenDateAction(existing: List<CalBlock>, plan: RegenPlan, iso: String, startTime: String): ChosenDateAction {
    // Rule B′ (stage 2): a row the plan rewrites (toRetime) is treated exactly like
    // one it deletes — it is moving to its own date, so it can't be the chosen
    // day's occurrence (counted, the day the user picked could end up empty, or
    // the row got two writes). A planned block covers the day only by its NEW date.
    if ((plan.toUpsert + plan.toRetime).any { it.date == iso }) return ChosenDateAction.Covered
    val moving = plan.toDelete.toSet() + plan.toRetime.map { it.id }
    val onDay = existing.filter { it.date == iso && isTaskBlock(it) && it.id !in moving }
    val live = onDay.filter { !it.done && !it.skipped }
    if (live.any { it.startTime == startTime }) return ChosenDateAction.Covered
    live.minByOrNull { it.startTime }?.let { return ChosenDateAction.Retime(it) }
    if (onDay.any { it.done }) return ChosenDateAction.Covered
    onDay.firstOrNull { it.skipped }?.let { return ChosenDateAction.Retime(it) }
    return ChosenDateAction.Mint
}

/** The chosen day's write ([recurrenceChosenDateWrite]). */
sealed class ChosenDateWrite {
    /** The day already has its occurrence. */
    data object None : ChosenDateWrite()
    /** A plain upsert: a retime, an in-place rewrite of the day's own row, or a
     *  random-id block. */
    data class Upsert(val block: CalBlock) : ChosenDateWrite()
    /** A deterministic mint: written insert-if-absent (`insert_or_retime`). */
    data class Insert(val block: CalBlock) : ChosenDateWrite()
}

/**
 * §3b′ of deterministic-occurrence-ids.md (stage 2, Ahmad 2026-09-23; parity
 * with iOS build 85 and web): what guaranteeing the chosen day writes, computed
 * AFTER regenerate and applied before any of the plan's writes are dispatched
 * (Schedule on a series, "Start repeating", the create sheet, and — with an
 * empty plan — a series' first placement). Returns the plan (possibly minus one
 * delete) and the write; the plan's three lists and the write are disjoint.
 *  • Covered → None; Retime(b) → b at [startTime], un-skipped.
 *  • Mint, with id = occurrenceId(task, iso):
 *    1. id is in plan.toDelete → that row is taken OUT of the delete and
 *       rewritten in place onto the day (from the existing row, so its Google
 *       mapping survives — a fresh block would null it);
 *    2. a block with id survives elsewhere (moved, done early, kept) → a block
 *       with a RANDOM id: the user asked for this day explicitly, and the
 *       surviving row is never taken over;
 *    3. otherwise → the deterministic mint.
 */
fun recurrenceChosenDateWrite(
    task: TaskItem,
    existing: List<CalBlock>,
    plan: RegenPlan,
    iso: String,
    startTime: String,
): Pair<RegenPlan, ChosenDateWrite> {
    return when (val action = recurrenceChosenDateAction(existing, plan, iso, startTime)) {
        ChosenDateAction.Covered -> plan to ChosenDateWrite.None
        is ChosenDateAction.Retime -> plan to ChosenDateWrite.Upsert(action.block.copy(startTime = startTime, skipped = false))
        ChosenDateAction.Mint -> {
            val id = occurrenceId(task.id, iso)
            val duration = clampDurationMin(task.estimateMin)
            val row = existing.firstOrNull { it.id == id && it.taskId == task.id && isTaskBlock(it) }
            when {
                row != null && id in plan.toDelete -> plan.copy(toDelete = plan.toDelete - id) to ChosenDateWrite.Upsert(
                    row.copy(
                        date = iso, startTime = startTime, taskName = task.name, durationMinutes = duration,
                        done = false, skipped = false, completedAt = null,
                    ),
                )
                row != null -> plan to ChosenDateWrite.Upsert(
                    CalBlock(id = newUuid(), taskId = task.id, taskName = task.name, startTime = startTime,
                        durationMinutes = duration, date = iso, kind = CalBlockKind.TASK),
                )
                else -> plan to ChosenDateWrite.Insert(
                    CalBlock(id = id, taskId = task.id, taskName = task.name, startTime = startTime,
                        durationMinutes = duration, date = iso, kind = CalBlockKind.TASK),
                )
            }
        }
    }
}

/**
 * Does the create sheet need a time before it may add this task? [date] is
 * null for Later.
 *
 * Why (parity with iOS build 81, audit 2026-09-22 C7): every occurrence is a
 * timed block and nothing can invent the time later, so a repeating task saved
 * without one had zero occurrences and showed nowhere but Tasks → Recurring.
 * The free-slot finder stops at 18:00, so that was the normal evening case, and
 * Later + Repeat got there too. A one-off for a later day with no time got no
 * block and silently lost its day. A one-off for today may still be added
 * without a time.
 */
fun newTaskNeedsTime(repeats: Boolean, date: String?, todayIso: String, pickedTime: String?): Boolean {
    if (date == null) return repeats
    if (pickedTime != null) return false
    return repeats || date != todayIso
}

private val DOW_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTH_LABELS =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDays(days: List<Int>): String {
    val sorted = days.toSortedSet().toList()
    if (sorted.size == 5 && listOf(1, 2, 3, 4, 5).all { it in sorted }) return "weekdays"
    if (sorted.size == 2 && 0 in sorted && 6 in sorted) return "weekends"
    return sorted.filter { it in DOW_LABELS.indices }.joinToString("/") { DOW_LABELS[it] }
}

/** Short human label for the detail pane / row chips. */
fun recurrenceLabel(r: Recurrence?): String {
    // An unrecognised recurrence kind decodes to a no-op sentinel (see
    // RecurrenceSerializer.UNKNOWN_UNTIL) — render nothing, not a bogus "until 0001" chip.
    if (r == null || RecurrenceSerializer.isUnknown(r)) return ""
    val base = when (r) {
        is Recurrence.Daily -> "Repeats daily"
        is Recurrence.Weekly -> if (r.daysOfWeek.size == 7) "Repeats daily" else "Repeats ${formatDays(r.daysOfWeek)}"
        is Recurrence.Monthly -> "Repeats monthly"
        // Out-of-range days are dropped before formatting; N = 1 reads exactly
        // like weekly; an invalid rule (or one with no real day) reads "".
        is Recurrence.EveryNWeeks -> {
            val days = everyNWeeksDays(r)
            if (!isValidEveryNWeeks(r) || days.isEmpty()) return ""
            when {
                r.interval == 1 -> if (days.size == 7) "Repeats daily" else "Repeats ${formatDays(days)}"
                days.size == 7 -> "Repeats every day, every ${r.interval} weeks"
                else -> "Repeats every ${r.interval} weeks on ${formatDays(days)}"
            }
        }
    }
    val until = r.until
    if (until != null) {
        val parts = until.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) {
            val (y, m, d) = parts
            if (m in 1..12) return "$base until ${MONTH_LABELS[m - 1]} $d, $y"
        }
    }
    return base
}
