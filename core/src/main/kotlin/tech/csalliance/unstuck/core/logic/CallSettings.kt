package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.core.model.CallRequest
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
//
// The three PROACTIVE calls (morning plan / evening wrap-up / check-in after a
// block — calls build-out 2026-09-20, migration 072) are ACCOUNT-wide:
// `notification_preferences.call_*`, read/written through the sync layer's
// PreferencesClient and cached per uid on the device ([CallProactivePrefs] +
// the pending-push flag [CallProactiveSync] arbitrates).

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

    /** "14:05" → "2:05pm", "09:00" → "9am", "12:30" → "12:30pm", "00:15" →
     *  "12:15am" — a time the way people say it (the after-block opening;
     *  iOS CallSettings.spokenTime). Anything that isn't HH:MM comes back as given. */
    fun spokenTime(hhmm: String): String {
        val m = minutesOfDay(hhmm) ?: return hhmm
        val h24 = m / 60
        val min = m % 60
        val suffix = if (h24 < 12) "am" else "pm"
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return if (min == 0) "$h12$suffix" else "%d:%02d%s".format(h12, min, suffix)
    }
}

/** The account-wide proactive-call toggles + times (`notification_preferences.
 *  call_morning_enabled / call_morning_time / call_evening_enabled /
 *  call_evening_time / call_after_block_enabled`, migration 072). All OFF by
 *  default — Ahmad's decision: the proactive calls are opt-in options. 1:1
 *  with iOS `CallProactivePrefs`. */
@Serializable
data class CallProactivePrefs(
    val morningEnabled: Boolean = false,
    /** "HH:MM" local. */
    val morningTime: String = DEFAULT_MORNING_TIME,
    val eveningEnabled: Boolean = false,
    val eveningTime: String = DEFAULT_EVENING_TIME,
    val afterBlockEnabled: Boolean = false,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_MORNING_TIME = "08:30"
        const val DEFAULT_EVENING_TIME = "18:00"
        val DEFAULTS = CallProactivePrefs()

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Tolerant: bad / blank JSON → the defaults (never crash a launch over a
         *  corrupt preference); a stored time that doesn't parse → its default. */
        fun fromJson(s: String?): CallProactivePrefs {
            if (s.isNullOrBlank()) return DEFAULTS
            val p = runCatching { json.decodeFromString(serializer(), s) }.getOrNull() ?: return DEFAULTS
            return p.copy(
                morningTime = hhmm(p.morningTime) ?: DEFAULT_MORNING_TIME,
                eveningTime = hhmm(p.eveningTime) ?: DEFAULT_EVENING_TIME,
            )
        }

        /** A Postgres `time` as PostgREST emits it ("08:30:00", "08:30:00.000")
         *  or a bare "HH:MM" → "HH:MM"; null for null / garbage. */
        fun hhmm(raw: String?): String? {
            val t = raw?.trim() ?: return null
            if (t.length < 5) return null
            val head = t.substring(0, 5)
            val p = head.split(':')
            if (p.size != 2) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return "%02d:%02d".format(h, m)
        }
    }
}

/** The device-cache ↔ server arbitration for [CallProactivePrefs] (the
 *  NotificationPrefs pattern, iOS `applyServerCallProactivePrefs`): a toggle
 *  made here while offline is RE-PUSHED on the next hydrate, never pulled
 *  over by the server's older value; otherwise the server (what every other
 *  device sees) wins; no server row yet → keep what we have. */
object CallProactiveSync {
    /** What the device should hold after a pull. */
    fun resolve(local: CallProactivePrefs, server: CallProactivePrefs?, pendingPush: Boolean): CallProactivePrefs =
        if (pendingPush) local else (server ?: local)

    /** Does the pull need to push the local copy up first? */
    fun shouldPush(pendingPush: Boolean): Boolean = pendingPush
}

/** The one-time Settings › Calls nudge for a phone whose ring cannot be a real
 *  ring — Android's twin of the iOS VoIP-registration nudge: on API 34+ the
 *  USE_FULL_SCREEN_INTENT special access is pre-granted only to apps Play
 *  classifies as calling / alarm; without it the ring degrades to a heads-up
 *  notification. Shown until the grant lands or the user dismisses it; the
 *  device-status line keeps saying so either way. Pure — Settings supplies the facts. */
object CallRingNudge {
    fun shouldShow(canRing: Boolean, dismissed: Boolean): Boolean = !canRing && !dismissed
}

/** Settings › Calls "Test call now": the row it books and the rows a retry
 *  replaces (iOS CallSettingsView.testCallLabel / previousTestCalls). */
object TestCallLogic {
    const val LABEL = "Test call"
    const val NOTE = "This is what a call from Unstuck sounds like"
    const val KIND = "test"

    /** The live rows a new test call cancels first: kind `test`, or — for a row
     *  an older build booked before kinds existed — the test label (case-
     *  insensitive). Two test rows would ring twice, and the one-live-call-
     *  per-label rule would refuse the retry otherwise. */
    fun previousTestCalls(live: List<CallRequest>): List<CallRequest> =
        live.filter { it.isLive && (it.isTestCall || it.label.trim().equals(LABEL, ignoreCase = true)) }
}
