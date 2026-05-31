package tech.csalliance.unstuck.surface

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context

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

    /** Brand accents for notification tint (running = coral, paused = amber). */
    const val CORAL = 0xFFE89077.toInt()
    const val AMBER = 0xFFE0A33A.toInt()

    fun ensureAll(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Unstuck"))
        fun ch(id: String, name: String, importance: Int, silent: Boolean = false, desc: String? = null) {
            val c = NotificationChannel(id, name, importance).apply {
                group = GROUP
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                if (silent) setSound(null, null)
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
    }
}

/** Stable notification ids — re-issuing the same id updates in place (no stacking). */
object NotifIds {
    const val FOCUS = 1001        // the live ongoing focus notification
    const val RECAP = 2001
    const val PAUSED = 2002
    const val BRIEF = 2003
    // Per-task notifications are offset by a 16-bit hash of the task id. The bases
    // are spaced 0x10000 apart so the three families never collide (a pre-task
    // reminder, a "starts now", and a drift ping for the same task can coexist).
    const val REMINDER_BASE = 0x30000  // A1 pre-task reminder
    const val ATSTART_BASE = 0x40000   // A2 "starts now" (Start / Reschedule)
    const val DRIFTED_BASE = 0x50000   // A4 didn't-start follow-up

    private fun forTask(base: Int, taskId: String) = base + (taskId.hashCode() and 0xFFFF)
    fun reminder(taskId: String) = forTask(REMINDER_BASE, taskId)
    fun atStart(taskId: String) = forTask(ATSTART_BASE, taskId)
    fun drifted(taskId: String) = forTask(DRIFTED_BASE, taskId)
}
