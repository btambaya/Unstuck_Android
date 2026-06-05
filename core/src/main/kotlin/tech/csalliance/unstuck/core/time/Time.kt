package tech.csalliance.unstuck.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

// Reproduce the JS Date semantics the web logic relies on: timestamps are
// ISO strings, date math is LOCAL (ZoneId.systemDefault(), like JS Date),
// ISO strings compare lexicographically. Tests run with -Duser.timezone=UTC.

const val DAY_MS: Long = 24L * 60 * 60 * 1000

object Time {
    /** ISO-8601 → epoch millis, or null (mirrors Date.parse → NaN). */
    fun parseMillis(iso: String): Long? =
        try { Instant.parse(iso).toEpochMilli() }
        catch (_: Exception) {
            try { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            catch (_: Exception) {
                // Offset-less local datetime ("2026-06-06T14:00"), then bare date
                // ("2026-06-06") — interpret in the system zone instead of returning null
                // (which would otherwise read as 00:00 / a 15-min sliver for such inputs).
                val zone = ZoneId.systemDefault()
                try { LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli() }
                catch (_: Exception) {
                    try { LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli() } catch (_: Exception) { null }
                }
            }
        }

    /** Local-midnight epoch ms for the day containing `now`. */
    fun startOfDayMillis(now: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Local-midnight epoch ms for civil (year, month[1-based], day). */
    fun civil(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun addDaysMillis(epochMs: Long, n: Int): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            .plusDays(n.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** JS getDay convention: 0=Sun … 6=Sat. */
    fun dayOfWeekJs(epochMs: Long): Int =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).dayOfWeek.value % 7

    fun dayOfMonth(epochMs: Long): Int =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).dayOfMonth

    /** Number of days in the calendar month containing [epochMs] (28–31). */
    fun daysInMonth(epochMs: Long): Int =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().lengthOfMonth()

    fun hourOf(epochMs: Long): Int =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).hour

    fun minuteOf(epochMs: Long): Int =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).minute

    /** Whole-day difference floor(a) − floor(b), matching the web's
     *  Math.round((midnightA − midnightB)/oneDay). */
    fun wholeDaysBetween(a: Long, b: Long): Int {
        val diff = startOfDayMillis(a) - startOfDayMillis(b)
        return Math.round(diff.toDouble() / DAY_MS).toInt()
    }
}

object Clock {
    fun todayIso(): String = dateIso(System.currentTimeMillis())
    fun dateIso(epochMs: Long): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }
}
