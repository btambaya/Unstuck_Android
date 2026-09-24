package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock

// Port of lib/use-usable-today.ts. "Usable time today" — scheduled minutes still
// ahead of you (done and skipped blocks excluded) minus meetings (external
// blocks) and soft placeholders (settle/lunch buffers).
// The assistant's context strip shows the SAME number the Today rail does, so
// one definition serves both.

/** "45m" / "3h" / "2h 40m" — the web fmtHrs, character for character. */
fun fmtHrs(mins: Int): String {
    val h = mins / 60
    val m = mins % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

data class UsableToday(
    /** Scheduled-minus-meetings-minus-buffers, floored at 0. */
    val usableMins: Int,
    val totalScheduled: Int,
    val meetingMins: Int,
    val bufferedMins: Int,
)

fun usableToday(allBlocks: List<CalBlock>, todayIso: String): UsableToday {
    // Blocks already ticked off or skipped on purpose are not time you still
    // have (cross-check P0-10: a skipped 30-min block read "30m usable").
    val blocks = allBlocks.filter { it.date == todayIso && !it.done && !it.skipped }
    fun total(predicate: (CalBlock) -> Boolean) = blocks.filter(predicate).sumOf { it.durationMinutes }
    val totalScheduled = total { true }
    val meetingMins = total(::isExternalBlock)
    val bufferedMins = total(::isPlaceholderBlock)
    return UsableToday(
        usableMins = (totalScheduled - meetingMins - bufferedMins).coerceAtLeast(0),
        totalScheduled = totalScheduled,
        meetingMins = meetingMins,
        bufferedMins = bufferedMins,
    )
}
