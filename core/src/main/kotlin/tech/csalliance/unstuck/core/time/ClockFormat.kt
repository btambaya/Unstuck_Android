package tech.csalliance.unstuck.core.time

import java.text.DateFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The phone's own 12/24-hour preference (Settings › System › Date & time ›
 * "Use 24-hour format"). The app reads it once per surface through the app
 * layer's accessor (`DeviceClock`) and hands it to [ClockFormat] — every clock
 * time the user sees follows it, one rule app-wide (Ahmad, 2026-09-24: the
 * phone said "14:02" while Today said "2:02 PM" and the calendar said "14").
 */
enum class ClockMode { H12, H24 }

/**
 * THE formatter for a clock time the user sees — the one place a time of day
 * becomes display text. Pure: the mode (and locale, for the AM/PM symbols) is
 * passed in; nothing here reads the device.
 *
 *  - [time]        "14:30" / "2:30 PM"
 *  - [hour]        whole hours in tight spots (grid hour labels): "14:00" / "2 PM"
 *  - [compactHour] the tightest spots (heatmap axis): "14:00" / "2pm"
 *  - [range]       "14:00–15:30" / "2:00–3:30 PM" (one meridiem when both ends
 *                  share it, else "11:30 AM–1:00 PM"); en dash
 *
 * Digits are ASCII in every locale (as the wire times are — see [WireTime]).
 * NOT for machine text: storage, sync, tool arguments/results the model reads,
 * logs — those stay 'HH:MM' through [WireTime].
 */
object ClockFormat {
    private const val DAY_MIN = 24 * 60

    /** "14:30" (24-hour) / "2:30 PM" (12-hour, the locale's AM/PM symbols). */
    fun time(hour: Int, minute: Int, mode: ClockMode, locale: Locale = Locale.getDefault()): String =
        ofMinutes(hour * 60 + minute, mode, locale)

    /** A minute of the day (wraps past midnight: 1500 → 01:00) → [time]. */
    fun ofMinutes(minuteOfDay: Int, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        val t = Math.floorMod(minuteOfDay, DAY_MIN)
        val h = t / 60
        val m = t % 60
        return when (mode) {
            ClockMode.H24 -> WireTime.hm(h, m)
            ClockMode.H12 -> "${h12(h)}:${WireTime.pad2(m)} ${meridiem(h, locale)}"
        }
    }

    /** A wire time ("HH:MM", "H:MM" or "HH:MM:SS") → [time]. Anything that
     *  isn't a time of day comes back exactly as given (never invent a time). */
    fun time(hhmm: String, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        val t = minutesOf(hhmm) ?: return hhmm
        return ofMinutes(t, mode, locale)
    }

    /** An instant's local wall clock → [time]. */
    fun time(epochMs: Long, mode: ClockMode, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val z = Instant.ofEpochMilli(epochMs).atZone(zone)
        return time(z.hour, z.minute, mode, locale)
    }

    /** A whole hour where space is tight (the calendar grids' hour labels):
     *  "14:00" / "2 PM". 24 (the end of a day) reads as midnight. */
    fun hour(hour: Int, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        val h = Math.floorMod(hour, 24)
        return when (mode) {
            ClockMode.H24 -> WireTime.hm(h, 0)
            ClockMode.H12 -> "${h12(h)} ${meridiem(h, locale)}"
        }
    }

    /** A whole hour in the tightest spots (the Insights heatmap axis, which read
     *  "6am" / "12pm" before): "14:00" / "2pm" — the 12-hour form keeps that
     *  compactness (no space, lower-case symbol); the 24-hour form is never a
     *  bare "14". */
    fun compactHour(hour: Int, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        val h = Math.floorMod(hour, 24)
        return when (mode) {
            ClockMode.H24 -> WireTime.hm(h, 0)
            ClockMode.H12 -> "${h12(h)}${meridiem(h, locale).lowercase(locale)}"
        }
    }

    /** Minutes of the day [startMin]..[endMin] (either may run past midnight):
     *  "14:00–15:30" / "2:00–3:30 PM" / "11:30 AM–1:00 PM". */
    fun range(startMin: Int, endMin: Int, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        val a = Math.floorMod(startMin, DAY_MIN)
        val b = Math.floorMod(endMin, DAY_MIN)
        if (mode == ClockMode.H24) return "${ofMinutes(a, mode, locale)}–${ofMinutes(b, mode, locale)}"
        val sameHalf = (a < 12 * 60) == (b < 12 * 60)
        if (!sameHalf) return "${ofMinutes(a, mode, locale)}–${ofMinutes(b, mode, locale)}"
        return "${h12(a / 60)}:${WireTime.pad2(a % 60)}–${ofMinutes(b, mode, locale)}"
    }

    /** Two wire times → [range]; an end that isn't a time is kept as given. */
    fun range(start: String, end: String, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        val a = minutesOf(start)
        val b = minutesOf(end)
        if (a == null || b == null) return "${time(start, mode, locale)}–${time(end, mode, locale)}"
        return range(a, b, mode, locale)
    }

    /** A block's slot — its start plus its length — as a [range]. */
    fun span(start: String, durationMin: Int, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        val a = minutesOf(start) ?: return time(start, mode, locale)
        return range(a, a + durationMin.coerceAtLeast(0), mode, locale)
    }

    private const val WIRE_HM = "([01]\\d|2[0-3]):([0-5]\\d)"
    /** Not already a 12-hour time: no AM/PM (any case, dotted or not) after it. */
    private const val NO_MERIDIEM = "(?![\\d:])(?!\\s?(?i:[ap]\\.?m\\b))"
    /** …nor the start of a 12-hour range whose end carries it ("10:00–11:00 PM"). */
    private const val NOT_RANGE_START_12H = "(?!\\s?[–-]\\s?\\d{1,2}:\\d{2}\\s?(?i:[ap]\\.?m\\b))"
    /** Not inside a number, a longer time, or an ISO stamp's "…T14:05". */
    private const val NOT_AFTER = "(?<![\\d:.T])"
    private val WIRE_RANGE = Regex("$NOT_AFTER$WIRE_HM\\s?[–-]\\s?$WIRE_HM$NO_MERIDIEM")
    private val WIRE_TIME = Regex("$NOT_AFTER$WIRE_HM$NO_MERIDIEM$NOT_RANGE_START_12H")

    /** Every standalone wire time ("14:05") and wire range ("08:00–21:00")
     *  inside [text], shown in [mode] ([time] / [range]). For the one place
     *  machine text reaches the screen: an assistant-contract refusal the
     *  Call-me card / the test call show verbatim (the model keeps reading the
     *  24-hour original). Dates, ISO stamps, durations and a time already
     *  carrying AM/PM are left alone; 24-hour mode is the identity. */
    fun localizeTimes(text: String, mode: ClockMode, locale: Locale = Locale.getDefault()): String {
        if (mode == ClockMode.H24) return text
        val ranged = WIRE_RANGE.replace(text) { m ->
            val g = m.groupValues
            range(g[1].toInt() * 60 + g[2].toInt(), g[3].toInt() * 60 + g[4].toInt(), mode, locale)
        }
        return WIRE_TIME.replace(ranged) { m ->
            time(m.groupValues[1].toInt(), m.groupValues[2].toInt(), mode, locale)
        }
    }

    /** "HH:MM" / "H:MM" / "HH:MM:SS" → minutes since midnight, or null. */
    fun minutesOf(hhmm: String): Int? {
        val p = hhmm.trim().split(':')
        if (p.size !in 2..3) return null
        if (p[0].isEmpty() || p[0].length > 2 || p[1].length != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun h12(h24: Int): Int = if (h24 % 12 == 0) 12 else h24 % 12

    private fun meridiem(h24: Int, locale: Locale): String {
        val (am, pm) = amPm(locale)
        return if (h24 < 12) am else pm
    }

    @Volatile private var symbolCache: Pair<Locale, Pair<String, String>>? = null

    /** The locale's AM / PM symbols ("AM"/"PM" in en-US, "am"/"pm" in en-GB);
     *  "AM"/"PM" when a locale has none. */
    fun amPm(locale: Locale): Pair<String, String> {
        symbolCache?.let { (l, s) -> if (l == locale) return s }
        val raw = runCatching { DateFormatSymbols.getInstance(locale).amPmStrings }.getOrNull()
        val am = raw?.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "AM"
        val pm = raw?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "PM"
        val s = am to pm
        symbolCache = locale to s
        return s
    }
}
