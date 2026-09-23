package tech.csalliance.unstuck.calls

import android.content.Context
import tech.csalliance.unstuck.core.logic.CallProactivePrefs
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.core.time.WireTime

// Device-local "Calls from Unstuck" preferences, PER ACCOUNT — the phone's own
// guard applied ON RECEIPT (a ring outside the allowed hours is declined
// quietly + a notification), the kill-switch for calls, and the default lead
// for task-anchored calls. Port of iOS App/Calls/CallSettings.swift (which is
// device-wide there) onto the phase-1 per-uid SharedPreferences pattern
// (AppViewModel.paPrefs: file "unstuck.pa", key "<base>.<uid>") so a second
// account on this phone never inherits the first one's hours, and the sign-out
// scrub (`paPrefs.edit().clear()`, risk 9) wipes these with the rituals.
//
// The SERVER window is 06:00–23:00 (request_call refuses outside it —
// CallSettingsLogic.SERVER_WINDOW); this is the user's narrower guard.
//
// The three PROACTIVE calls (calls build-out 2026-09-20) are ACCOUNT-wide —
// `notification_preferences.call_*` through PreferencesClient — and cached here
// per uid as the device copy ([loadProactive] / [saveProactive]) with a
// pending-push flag ([pendingProactivePush]): a toggle made offline is
// re-pushed on the next hydrate instead of being pulled over (iOS
// CallSettings.proactive / pendingProactivePush). The one-time full-screen-
// intent nudge's dismissal lives here too ([ringNudgeDismissed]).
object CallSettingsStore {
    /** The phase-1 gateway prefs file (rituals / dismissals / interview flag). */
    const val FILE = "unstuck.pa"
    const val KEY_ENABLED = "calls.enabled"
    const val KEY_HOURS_START = "calls.hoursStart"
    const val KEY_HOURS_END = "calls.hoursEnd"
    const val KEY_DEFAULT_LEAD = "calls.defaultLead"
    /** The proactive toggles + times, one JSON blob (CallProactivePrefs.toJson). */
    const val KEY_PROACTIVE = "calls.proactive"
    /** True while a proactive toggle made here hasn't reached the server yet. */
    const val KEY_PROACTIVE_PENDING = "calls.proactivePending"
    /** The user dismissed the "allow full-screen calls" nudge on this install. */
    const val KEY_RING_NUDGE_DISMISSED = "calls.ringNudgeDismissed"

    /** The lead chips the editor + Settings offer (iOS CallSettings.leadOptions). */
    val LEAD_OPTIONS: List<Int> = tech.csalliance.unstuck.core.logic.CallSettingsLogic.LEAD_OPTIONS

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(base: String, uid: String) = "$base.$uid"

    /** This account's settings — defaults for a field that is missing or invalid
     *  (an "HH:MM" that doesn't parse, a lead ≤ 0), never a crash. */
    fun load(context: Context, uid: String): CallSettings {
        val p = prefs(context)
        val d = CallSettings()
        return CallSettings(
            enabled = p.getBoolean(key(KEY_ENABLED, uid), d.enabled),
            hoursStart = validHM(p.getString(key(KEY_HOURS_START, uid), null)) ?: d.hoursStart,
            hoursEnd = validHM(p.getString(key(KEY_HOURS_END, uid), null)) ?: d.hoursEnd,
            defaultLeadMin = p.getInt(key(KEY_DEFAULT_LEAD, uid), d.defaultLeadMin).takeIf { it > 0 } ?: d.defaultLeadMin,
        )
    }

    fun save(context: Context, uid: String, s: CallSettings) {
        prefs(context).edit()
            .putBoolean(key(KEY_ENABLED, uid), s.enabled)
            .putString(key(KEY_HOURS_START, uid), s.hoursStart)
            .putString(key(KEY_HOURS_END, uid), s.hoursEnd)
            .putInt(key(KEY_DEFAULT_LEAD, uid), s.defaultLeadMin)
            .apply()
    }

    /** Drop this account's keys (the sign-out scrub also clears the whole file). */
    fun clear(context: Context, uid: String) {
        prefs(context).edit()
            .remove(key(KEY_ENABLED, uid))
            .remove(key(KEY_HOURS_START, uid))
            .remove(key(KEY_HOURS_END, uid))
            .remove(key(KEY_DEFAULT_LEAD, uid))
            .remove(key(KEY_PROACTIVE, uid))
            .remove(key(KEY_PROACTIVE_PENDING, uid))
            .remove(key(KEY_RING_NUDGE_DISMISSED, uid))
            .apply()
    }

    // ── proactive calls (server-backed; this is the device cache) ────────────

    /** The cached proactive toggles + times; the defaults (all off) until the
     *  server row has been read or the user toggled one here. */
    fun loadProactive(context: Context, uid: String): CallProactivePrefs =
        CallProactivePrefs.fromJson(prefs(context).getString(key(KEY_PROACTIVE, uid), null))

    fun saveProactive(context: Context, uid: String, p: CallProactivePrefs) {
        prefs(context).edit().putString(key(KEY_PROACTIVE, uid), p.toJson()).apply()
    }

    /** True while a toggle made on this device hasn't reached
     *  `notification_preferences` yet — the hydrate pull re-pushes it rather
     *  than pulling the server's older value over it. */
    fun pendingProactivePush(context: Context, uid: String): Boolean =
        prefs(context).getBoolean(key(KEY_PROACTIVE_PENDING, uid), false)

    fun setPendingProactivePush(context: Context, uid: String, pending: Boolean) {
        val e = prefs(context).edit()
        if (pending) e.putBoolean(key(KEY_PROACTIVE_PENDING, uid), true) else e.remove(key(KEY_PROACTIVE_PENDING, uid))
        e.apply()
    }

    // ── the ring nudge ───────────────────────────────────────────────────────

    /** The one-time "allow full-screen calls" nudge was dismissed on this install. */
    fun ringNudgeDismissed(context: Context, uid: String): Boolean =
        prefs(context).getBoolean(key(KEY_RING_NUDGE_DISMISSED, uid), false)

    fun setRingNudgeDismissed(context: Context, uid: String, dismissed: Boolean) {
        val e = prefs(context).edit()
        if (dismissed) e.putBoolean(key(KEY_RING_NUDGE_DISMISSED, uid), true) else e.remove(key(KEY_RING_NUDGE_DISMISSED, uid))
        e.apply()
    }

    /** "HH:MM" with 0 ≤ H < 24, 0 ≤ M < 60 → itself; anything else → null. An
     *  older build saved the picked hours in the phone's own digits ("٠٨:٠٠"); they
     *  come back in ASCII, like every HH:MM the app compares and sends (Android
     *  audit 2026-09-23, A12). */
    fun validHM(s: String?): String? {
        if (s == null) return null
        val m = Regex("^(\\d{2}):(\\d{2})$").find(WireTime.asciiDigits(s.trim())) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) m.value else null
    }
}
