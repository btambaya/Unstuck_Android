package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Tasks › Completed is grouped by WHEN a task was finished (owner request
// 2026-09-24: one long list → foldable "Today / Yesterday / … " sections).
// Same rules on iOS, Android and web:
//   Today · Yesterday · Earlier this week (Monday of this week up to the day
//   before yesterday) · Last week (the previous Monday–Sunday) · Earlier (older,
//   or no completedAt). LOCAL calendar days, weeks start MONDAY. The day before
//   today is always "Yesterday", so on a Monday "Earlier this week" is empty and
//   Saturday is the newest day in "Last week". Empty sections are omitted.

enum class CompletedSection(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    EARLIER_THIS_WEEK("Earlier this week"),
    LAST_WEEK("Last week"),
    EARLIER("Earlier");

    /** Today and Yesterday start open; the older sections start folded. */
    val defaultExpanded: Boolean get() = this == TODAY || this == YESTERDAY
}

data class CompletedGroup<T>(val section: CompletedSection, val items: List<T>)

/** The section a completion at [completedAtMs] falls in, seen from [nowMs] in
 *  [zone]. Calendar-day math (LocalDate), never "now − 24h", so a 23/25-hour DST
 *  day still breaks at local midnight. A future time (clock skew) reads as Today;
 *  no timestamp → Earlier. */
fun completedSectionOf(completedAtMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): CompletedSection {
    if (completedAtMs == null) return CompletedSection.EARLIER
    val today = localDay(nowMs, zone)
    val day = localDay(completedAtMs, zone)
    val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
    return when {
        !day.isBefore(today) -> CompletedSection.TODAY
        day == today.minusDays(1) -> CompletedSection.YESTERDAY
        !day.isBefore(monday) -> CompletedSection.EARLIER_THIS_WEEK
        !day.isBefore(monday.minusWeeks(1)) -> CompletedSection.LAST_WEEK
        else -> CompletedSection.EARLIER
    }
}

/** Group [items] into the non-empty sections, in section order, newest first
 *  inside each (rows without a parseable completedAt sink to the end of Earlier,
 *  keeping their incoming order). [completedAt] reads a row's ISO timestamp. */
fun <T> groupCompleted(
    items: List<T>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    completedAt: (T) -> String?,
): List<CompletedGroup<T>> {
    val stamped = items.map { it to completedAt(it)?.let(Time::parseMillis) }
    val bySection = stamped.groupBy { completedSectionOf(it.second, nowMs, zone) }
    return CompletedSection.entries.mapNotNull { s ->
        val rows = bySection[s] ?: return@mapNotNull null
        // sortedByDescending is stable, so equal / missing stamps keep their order.
        CompletedGroup(s, rows.sortedByDescending { it.second ?: Long.MIN_VALUE }.map { it.first })
    }
}

private fun localDay(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
