package tech.csalliance.unstuck.surface

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * Central registry of notification channels — one per "moment" so each gets the
 * design's importance (reminders/paused = heads-up, recap = silent-default,
 * daily = low). Created once at app start. Ids are NEW + stable: channel
 * importance is immutable after first creation, so we never reuse the old
 * "unstuck_push" id. All channels hide content on the lock screen by default
 * (VISIBILITY_PRIVATE → "Unlock to read", per the design's privacy default).
 */
object NotificationChannels {
    const val GROUP = "unstuck"
    const val REMINDERS = "unstuck_reminders"   // A1/A2/F1 — pre-task, time-critical (HIGH)
    const val RECAP = "unstuck_recap"           // B3 — session-end recap (DEFAULT, silent)
    const val PAUSED = "unstuck_paused"         // B2 — paused-too-long check-in (HIGH heads-up)
    const val DAILY = "unstuck_daily"           // C1/C2 — morning/evening brief (LOW)
    const val NUDGES = "unstuck_nudges"         // overflow / gentle (MIN, silent)
    const val FOCUS_ONGOING = "focus_timer"     // B1 — live focus session (LOW, ongoing; existing id)
    const val COLLAB = "unstuck_collab"         // shared-list collaboration: shared/done/late (HIGH heads-up)
    const val CALLS = "unstuck_calls"           // "Unstuck calls you" — the ring (HIGH, ringtone + vibration, full-screen)
    /** The LIVE conversation's foreground-service notification (DEFAULT, silent,
     *  ongoing). Its own channel on purpose: it carries the "End" action — the
     *  only way to hang up from outside the app — so it must not ride on
     *  [FOCUS_ONGOING], a channel a user may reasonably switch off to silence the
     *  focus timer, and an active call must not read as a low-priority
     *  "Focus session" entry in the shade. */
    const val CALL_ONGOING = "unstuck_call_ongoing"
    /** The QUIET call results — "outside your hours" / "calls are off" (LOW,
     *  silent, no vibration). They are posted the moment the server rings,
     *  which can be 3 am, so they must not sound or pop up; missed / busy /
     *  voice-failed stay on [REMINDERS]. A NEW id because a channel's
     *  importance can't change after creation (parity with iOS build 78, 0f24908). */
    const val CALL_NOTES = "unstuck_call_notes"

    /** Brand accents for notification tint (running = coral, paused = amber). */
    const val CORAL = 0xFFE89077.toInt()
    const val AMBER = 0xFFE0A33A.toInt()

    fun ensureAll(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Unstuck"))
        fun ch(id: String, name: String, importance: Int, silent: Boolean = false, desc: String? = null, vibrate: Boolean = true) {
            val c = NotificationChannel(id, name, importance).apply {
                group = GROUP
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                if (silent) setSound(null, null)
                if (!vibrate) enableVibration(false)
                if (desc != null) description = desc
            }
            mgr.createNotificationChannel(c)
        }
        ch(REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
        ch(RECAP, "Session recap", NotificationManager.IMPORTANCE_DEFAULT, silent = true)
        ch(PAUSED, "Paused check-ins", NotificationManager.IMPORTANCE_HIGH)
        ch(DAILY, "Daily brief", NotificationManager.IMPORTANCE_LOW, silent = true)
        ch(NUDGES, "Gentle nudges", NotificationManager.IMPORTANCE_MIN, silent = true)
        ch(FOCUS_ONGOING, "Focus session", NotificationManager.IMPORTANCE_LOW, desc = "Shows the running focus timer")
        ch(COLLAB, "Shared lists", NotificationManager.IMPORTANCE_HIGH, desc = "When a shared list is shared with you, finished, or running late")
        // The live call's own ongoing entry. DEFAULT (not LOW) so it sits with the
        // conversation rather than under the fold, and silent + no vibration
        // because the phone is already in one. Never FOCUS_ONGOING: silencing the
        // focus timer — a reasonable thing to do — would otherwise take the only
        // hang-up affordance away while the microphone kept running.
        ch(CALL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_DEFAULT, silent = true, vibrate = false, desc = "Shows the call you're on with Unstuck, with an End button")
        ch(CALL_NOTES, "Call notes", NotificationManager.IMPORTANCE_LOW, silent = true, vibrate = false, desc = "The notes of a call this phone declined — outside your call hours, or with Calls off")
        // The ring channel: HIGH so it heads-up / full-screens, the device's
        // RINGTONE (not the notification tone) under USAGE_NOTIFICATION_RINGTONE so
        // it follows the ringer volume + silent/vibrate modes like a phone call,
        // and a phone-like vibration pattern. Lock screen PUBLIC: it carries only
        // "Unstuck · <label>" (what a caller-id line would show); the notes stay
        // behind the full-screen activity's keyguard check. Sound/vibration are
        // system-managed, so the ring survives our process being killed mid-ring.
        // Importance is immutable after creation (a rename lands, a downgrade won't).
        mgr.createNotificationChannel(
            NotificationChannel(CALLS, "Calls from Unstuck", NotificationManager.IMPORTANCE_HIGH).apply {
                group = GROUP
                description = "When Unstuck calls you about a task you asked to be called about"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = CALL_VIBRATION
            },
        )
    }

    /** Ring-ring pause, like a phone: 1 s on, 1 s off, repeated (ms). */
    val CALL_VIBRATION = longArrayOf(0, 1000, 1000, 1000, 1000, 1000)
}

/** Stable notification ids — re-issuing the same id updates in place (no stacking). */
object NotifIds {
    const val FOCUS = 1001        // the live ongoing focus notification
    const val RECAP = 2001
    const val PAUSED = 2002
    const val BRIEF = 2003
    const val COLLAB = 2004
    /** THE incoming-call ring. One ring at a time is the rule (a second call while
     *  one is up ends as `busy`), so a single id: a retried push updates in place. */
    const val CALL = 2005
    /** The in-call foreground-service notification (calls/CallVoiceService). A
     *  DIFFERENT slot from [CALL]: the ringer cancels its ring on Answer while
     *  this one stays up for the length of the conversation. */
    const val CALL_VOICE = 2006
    // Per-task notifications are offset by a 16-bit hash of the task id. The bases
    // are spaced 0x10000 apart so the three families never collide (a pre-task
    // reminder, a "starts now", and a drift ping for the same task can coexist).
    const val REMINDER_BASE = 0x30000  // A1 pre-task reminder
    const val ATSTART_BASE = 0x40000   // A2 "starts now" (Start / Reschedule)
    const val DRIFTED_BASE = 0x50000   // A4 didn't-start follow-up
    // 0x60000 is the content-hashed generic push family (Push.kt).
    const val CALL_RESULT_BASE = 0x70000 // what happened to a call (missed / busy / outside hours / voice failed)

    private fun forTask(base: Int, taskId: String) = base + (taskId.hashCode() and 0xFFFF)
    fun reminder(taskId: String) = forTask(REMINDER_BASE, taskId)
    fun atStart(taskId: String) = forTask(ATSTART_BASE, taskId)
    fun drifted(taskId: String) = forTask(DRIFTED_BASE, taskId)
    /** Per-call result notification: the later verdict for the same call replaces
     *  the earlier one; results for two calls coexist. */
    fun callResult(callId: String) = forTask(CALL_RESULT_BASE, callId)
}
