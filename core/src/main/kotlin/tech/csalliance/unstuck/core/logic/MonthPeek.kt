package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.SharedBlock
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// The Month grid's two pure pieces (ported 1:1 from iOS `MonthView` +
// `MonthDayPeekSheet`, commit bba2e92):
//
//   • the heat scale — how BUSY a day is, so the month reads as a load map;
//   • the day peek — everything sitting on one day, split into the three
//     sections the sheet renders.
//
// Both used to live inline in the composable, which meant neither could be
// tested and the heat map quietly measured the wrong thing.

// ── Heat: how busy a day is ──────────────────────────────────────────────────

/** Floor on the month heat scale, in minutes. Without it a single 8-hour day
 *  sets the ceiling and flattens an otherwise normal week to nothing; with it,
 *  three scheduled hours already paints a full-strength cell. */
const val MONTH_BUSY_FLOOR_MIN = 180

/**
 * Scheduled minutes per 'YYYY-MM-DD': the user's own non-skipped blocks (task,
 * placeholder AND external — anything that eats the day) plus the non-skipped
 * blocks of tasks shared with them. Days with nothing on them are absent.
 *
 * This is deliberately NOT focus density (minutes actually focused, from
 * sessions): a heat map keyed on what got done leaves every future day blank,
 * so the month said nothing about the week ahead (tester, 2026-09-08).
 */
fun busyMinutesByDay(own: List<CalBlock>, shared: List<SharedBlock>): Map<String, Int> {
    val acc = HashMap<String, Int>()
    own.forEach { b ->
        if (!b.skipped && b.durationMinutes > 0) acc[b.date] = (acc[b.date] ?: 0) + b.durationMinutes
    }
    shared.forEach { b ->
        if (!b.skipped && b.durationMinutes > 0) acc[b.date] = (acc[b.date] ?: 0) + b.durationMinutes
    }
    return acc
}

/** The top of the heat ramp: the busiest day, never below [MONTH_BUSY_FLOOR_MIN]. */
fun busyScaleMax(byDay: Map<String, Int>): Int =
    maxOf(MONTH_BUSY_FLOOR_MIN, byDay.values.maxOrNull() ?: 0)

// ── Day peek: what is on one day ─────────────────────────────────────────────

/**
 * One day's contents, in the order the peek sheet renders them: my planned task
 * blocks, anything shared with me, then the rest of my calendar (external events
 * + placeholders), which are display-only.
 */
data class DayPeek(
    val planned: List<CalBlock>,
    val shared: List<SharedBlock>,
    val events: List<CalBlock>,
) {
    val isEmpty: Boolean get() = planned.isEmpty() && shared.isEmpty() && events.isEmpty()
}

/**
 * Everything on [iso], from the full block sets the calendar already holds.
 * Skipped occurrences are cancelled for that day and never show; shared blocks
 * go through [liveSharedBlocks] (drops skipped + external, sorts by start).
 * Own blocks sort by start time.
 */
fun dayPeek(iso: String, own: List<CalBlock>, shared: List<SharedBlock>): DayPeek {
    val mine = own.filter { it.date == iso && !it.skipped }.sortedBy { it.startTime }
    return DayPeek(
        planned = mine.filter { isTaskBlock(it) },
        shared = liveSharedBlocks(shared.filter { it.date == iso }),
        events = mine.filterNot { isTaskBlock(it) },
    )
}

/** "09:00 · 45m" / "9:00 AM · 1h" / "14:00 · 1h 30m" — a peek row's meta
 *  line, the start in the phone's [clock] mode. */
fun blockSlotText(startTime: String, minutes: Int, clock: ClockMode, locale: Locale = Locale.getDefault()): String =
    "${ClockFormat.time(startTime, clock, locale)} · ${fmtDuration(minutes) ?: "${minutes}m"}"

/** "Tue, Sep 8" — the peek sheet's title. Unparseable input is returned as-is. */
fun peekDayTitle(iso: String): String {
    val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    val mon = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return "$dow, $mon ${d.dayOfMonth}"
}
