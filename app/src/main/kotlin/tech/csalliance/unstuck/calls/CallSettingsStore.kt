package tech.csalliance.unstuck.calls

import android.content.Context
import tech.csalliance.unstuck.core.logic.CallSettings

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
object CallSettingsStore {
    /** The phase-1 gateway prefs file (rituals / dismissals / interview flag). */
    const val FILE = "unstuck.pa"
    const val KEY_ENABLED = "calls.enabled"
    const val KEY_HOURS_START = "calls.hoursStart"
    const val KEY_HOURS_END = "calls.hoursEnd"
    const val KEY_DEFAULT_LEAD = "calls.defaultLead"

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

    /** Drop this account's four keys (the sign-out scrub also clears the whole file). */
    fun clear(context: Context, uid: String) {
        prefs(context).edit()
            .remove(key(KEY_ENABLED, uid))
            .remove(key(KEY_HOURS_START, uid))
            .remove(key(KEY_HOURS_END, uid))
            .remove(key(KEY_DEFAULT_LEAD, uid))
            .apply()
    }

    /** "HH:MM" with 0 ≤ H < 24, 0 ≤ M < 60 → itself; anything else → null. */
    fun validHM(s: String?): String? {
        if (s == null) return null
        val m = Regex("^(\\d{2}):(\\d{2})$").find(s.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) m.value else null
    }
}
