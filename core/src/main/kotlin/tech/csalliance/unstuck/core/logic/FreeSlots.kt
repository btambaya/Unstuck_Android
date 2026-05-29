package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Free-slot finder + conflict detector for scheduling. Pure functions.
// Port of lib/free-slots.ts. Dates flow as local-midnight epoch ms (Long).

data class Slot(val date: String, val label: String, val startTime: String)

data class Conflict(val block: CalBlock, val overlapMin: Int)

private val DOW_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

private fun parseHM(hhmm: String): Pair<Int, Int> {
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return h to m
}

private fun parseHhmm(hhmm: String): Int {
    val (h, m) = parseHM(hhmm)
    return h * 60 + m
}

private fun pad2(n: Int): String = "%02d".format(n)

private fun hhmmFromMin(totalMin: Int): String = "${pad2(totalMin / 60)}:${pad2(totalMin % 60)}"

/** 12-hour time with AM/PM, e.g. "9:00 AM", "2:30 PM", "12:15 AM". */
fun formatTime(hhmm: String): String {
    val (h, m) = parseHM(hhmm)
    val period = if (h >= 12) "PM" else "AM"
    val h12 = ((h + 11) % 12) + 1
    return "$h12:${pad2(m)} $period"
}

private fun dayLabelFor(d: Long, today: Long): String {
    val diffDays = Time.wholeDaysBetween(d, today)
    if (diffDays == 0) return "Today"
    if (diffDays == 1) return "Tomorrow"
    return DOW_LABELS[Time.dayOfWeekJs(d)]
}

/** Scan upcoming days for free windows large enough for `durationMin`. Returns
 *  at most `limit` slots chronologically. Every block on a day is consumed
 *  (task, placeholder, external all represent real conflicts). */
fun findFreeSlots(
    blocks: List<CalBlock>,
    durationMin: Int,
    now: Long,
    startDate: Long? = null,
    daysToScan: Int = 4,
    dayStartMin: Int = 8 * 60,
    dayEndMin: Int = 18 * 60,
    limit: Int = 9,
): List<Slot> {
    val start = startDate ?: now
    val out = mutableListOf<Slot>()
    val nowMin = Time.hourOf(now) * 60 + Time.minuteOf(now)

    var d = 0
    while (d < daysToScan && out.size < limit) {
        val day = Time.addDaysMillis(Time.startOfDayMillis(start), d)
        val dayIso = Clock.dateIso(day)
        val dayBlocks = blocks
            .filter { it.date == dayIso }
            .map { parseHhmm(it.startTime) to parseHhmm(it.startTime) + it.durationMinutes }
            .sortedBy { it.first }

        val isToday = Time.startOfDayMillis(day) == Time.startOfDayMillis(now)
        val startMin = if (isToday) {
            max(dayStartMin, (ceil((nowMin + 5) / 15.0).toInt()) * 15)
        } else {
            dayStartMin
        }

        var cursor = startMin
        val step = max(durationMin, 30) // back-to-back no closer than 30min
        val scan = dayBlocks + (dayEndMin to dayEndMin)
        for (block in scan) {
            val gapEnd = min(block.first, dayEndMin)
            while (cursor + durationMin <= gapEnd) {
                val hhmm = hhmmFromMin(cursor)
                out.add(Slot(dayIso, "${dayLabelFor(day, now)} · ${formatTime(hhmm)}", hhmm))
                if (out.size >= limit) return out
                cursor += step
            }
            cursor = max(cursor, block.second)
        }
        d++
    }
    return out
}

/** Slots for a specific date only (no multi-day scanning). */
fun findFreeSlotsForDate(
    blocks: List<CalBlock>,
    durationMin: Int,
    isoDate: String,
    now: Long,
    limit: Int = 6,
    dayStartMin: Int = 8 * 60,
    dayEndMin: Int = 18 * 60,
): List<Slot> {
    val parts = isoDate.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return emptyList()
    val (y, m, d) = parts
    val day = Time.civil(y, m, d)
    return findFreeSlots(
        blocks, durationMin, now, startDate = day,
        daysToScan = 1, dayStartMin = dayStartMin, dayEndMin = dayEndMin, limit = limit,
    )
}

/** Every block on `date` overlapping the proposed slot, sorted by start.
 *  `excludeBlockId` skips a block being edited in-place. */
fun findConflicts(
    date: String,
    startTime: String,
    durationMin: Int,
    blocks: List<CalBlock>,
    excludeBlockId: String? = null,
): List<Conflict> {
    val startMin = parseHhmm(startTime)
    val endMin = startMin + durationMin
    val out = mutableListOf<Conflict>()
    for (b in blocks) {
        if (b.date != date) continue
        if (excludeBlockId != null && b.id == excludeBlockId) continue
        val bStart = parseHhmm(b.startTime)
        val bEnd = bStart + b.durationMinutes
        val overlap = max(0, min(endMin, bEnd) - max(startMin, bStart))
        if (overlap > 0) out.add(Conflict(b, overlap))
    }
    return out.sortedBy { parseHhmm(it.block.startTime) }
}

/** A block's time range for conflict pills, e.g. "9:00 AM–10:00 AM". */
fun blockTimeRange(b: CalBlock): String {
    val startMin = parseHhmm(b.startTime)
    val endMin = startMin + b.durationMinutes
    return "${formatTime(hhmmFromMin(startMin))}–${formatTime(hhmmFromMin(endMin))}"
}
