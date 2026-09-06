package tech.csalliance.unstuck.core.logic

import java.time.Instant
import java.time.ZoneId

// CallSettings — the device-local "Calls from Unstuck" preferences and their
// pure rules, 1:1 with iOS App/Calls/CallSettings.swift (the storage half
// lives in :app calls/CallSettingsStore.kt, per-uid SharedPreferences).
//
// The allowed hours are applied ON RECEIPT (outside → the call ends as
// `declined` silently + a notification). The SERVER window is 06:00–23:00
// inclusive (request_call refuses outside it); this client window is the
// user's own, narrower guard.

data class CallSettings(
    /** The kill-switch for incoming calls on this device (Settings › Calls). */
    val enabled: Boolean = true,
    /** "HH:MM" — start of the allowed window (inclusive). */
    val hoursStart: String = DEFAULT_HOURS_START,
    /** "HH:MM" — end of the allowed window (exclusive). */
    val hoursEnd: String = DEFAULT_HOURS_END,
    /** Minutes before a block a task-anchored call rings (the lead chips 5/10/15/30). */
    val defaultLeadMin: Int = DEFAULT_LEAD_MIN,
) {
    companion object {
        const val DEFAULT_HOURS_START = "06:00"
        const val DEFAULT_HOURS_END = "23:00"
        const val DEFAULT_LEAD_MIN = 10
        val DEFAULTS = CallSettings()
    }
}

object CallSettingsLogic {
    /** The server's booking window ("HH:MM", inclusive both ends). "HH:MM"
     *  strings compare lexicographically, so `nowHM in SERVER_WINDOW` works. */
    val SERVER_WINDOW: ClosedRange<String> = "06:00".."23:00"
    /** The lead chips the task editor offers. */
    val LEAD_OPTIONS: List<Int> = listOf(5, 10, 15, 30)

    /** Is `nowHM` inside the user's window? Start inclusive, end exclusive;
     *  start == end → always allowed; end < start → an overnight window
     *  (e.g. 22:00–02:00); an unparseable bound → always allowed (never lock
     *  the user out over a corrupt preference). */
    fun withinHours(nowHM: String, s: CallSettings): Boolean =
        withinWindow(nowHM, s.hoursStart, s.hoursEnd)

    fun withinWindow(nowHM: String, start: String, end: String): Boolean {
        val t = minutesOfDay(nowHM) ?: return true
        val a = minutesOfDay(start) ?: return true
        val b = minutesOfDay(end) ?: return true
        if (a == b) return true
        if (a < b) return t >= a && t < b
        return t >= a || t < b
    }

    /** Server booking window check (inclusive 06:00 … 23:00). */
    fun withinServerWindow(nowHM: String): Boolean {
        val t = minutesOfDay(nowHM) ?: return true
        val a = minutesOfDay(SERVER_WINDOW.start)!!
        val b = minutesOfDay(SERVER_WINDOW.endInclusive)!!
        return t in a..b
    }

    /** "HH:MM" → minutes since midnight, or null when malformed. */
    fun minutesOfDay(hhmm: String): Int? {
        val p = hhmm.trim().split(':')
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** Local "HH:MM" for an epoch-ms instant. */
    fun hhmm(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** A stored "HH:MM" that parses, else null (the caller falls back to the default). */
    fun validHM(s: String?): String? = s?.takeIf { minutesOfDay(it) != null }?.trim()

    /** A stored lead that is one of the chips (or at least positive), else the default. */
    fun validLead(v: Int?): Int = v?.takeIf { it > 0 } ?: CallSettings.DEFAULT_LEAD_MIN
}
