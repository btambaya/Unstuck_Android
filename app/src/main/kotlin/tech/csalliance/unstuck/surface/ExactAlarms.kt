package tech.csalliance.unstuck.surface

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import tech.csalliance.unstuck.NotificationLevel
import tech.csalliance.unstuck.SettingsState
import tech.csalliance.unstuck.UnstuckApp

/**
 * Exact alarms ("Alarms & reminders"), which punctual reminders need on Android
 * 12+. Android 14+ denies SCHEDULE_EXACT_ALARM by default to a new install, so a
 * new user's reminders fell back to Doze-deferred alarms, a later grant never
 * re-armed the ones already set, and the only request ran once from
 * MainActivity.onCreate, racing the session restore (Android audit 2026-09-23,
 * A15). The app keeps the user-grantable SCHEDULE_EXACT_ALARM, not the restricted
 * USE_EXACT_ALARM (Play allows that only for alarm-clock and calendar apps).
 */
object ExactAlarms {
    private const val PREFS = "unstuck.app"
    /** The flag MainActivity's old one-time prompt set: whoever it already asked
     *  is not asked again (Settings keeps the way back). */
    private const val KEY_ASKED = "exactAlarmPrompted"

    /** Whether reminders can fire on time. Always true below Android 12. */
    fun granted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return true
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(true)
    }

    /** Whether any alarm-driven moment is on: lead reminders at every level, or
     *  the start-now / didn't-start alarms Balanced and Coach arm. */
    fun wanted(s: SettingsState): Boolean = s.reminderLeadMin > 0 || s.notificationLevel != NotificationLevel.CALM

    fun asked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) = prefs(context).edit().putBoolean(KEY_ASKED, true).apply()

    /** The one-time in-app ask: only when it would change something and nothing
     *  else holds the screen (the guided tour, a pushed screen, focus). */
    internal fun shouldAsk(granted: Boolean, wanted: Boolean, asked: Boolean, screenFree: Boolean): Boolean =
        !granted && wanted && !asked && screenFree

    /** Open Unstuck's system "Alarms & reminders" page. False when it can't open. */
    fun openSystemPage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The user allowed exact alarms (Settings › Alarms & reminders). Every reminder
 * armed before that went in inexact and stays that way until re-armed, so re-arm
 * them all now. The system sends this only to our package; it reads no extras,
 * and a spoofed one would only re-arm the same alarms.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        rearm(context.applicationContext)
    }

    companion object {
        /** The re-arm itself (a seam for tests). */
        @Volatile internal var rearm: (Context) -> Unit = { context ->
            (context as? UnstuckApp)?.let { ReminderScheduler.reschedule(it) }
        }
    }
}
