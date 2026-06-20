package tech.csalliance.unstuck.surface

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tech.csalliance.unstuck.MainActivity
import tech.csalliance.unstuck.R

/**
 * Builds + posts notifications in the design voice. Incoming pushes are routed
 * by a `kind` data field to the right channel; the paused check-in is rendered
 * locally (client-timer driven). Lock-screen previews are off (private) with an
 * "Unlock to read" public version.
 */
object NotificationRenderer {

    /** True when the user has notifications enabled for the app (master toggle). When
     *  false, NotificationManagerCompat.notify() silently no-ops anyway — but we must
     *  ALSO skip the NotificationLog "shown" entry, or the in-app unread badge counts a
     *  notification the user never actually saw. */
    private fun enabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Map the server's `data.kind` to a channel + stable id. */
    private fun channelFor(kind: String?): Pair<String, Int> = when (kind) {
        "session_recap" -> NotificationChannels.RECAP to NotifIds.RECAP
        "paused_checkin" -> NotificationChannels.PAUSED to NotifIds.PAUSED
        "morning_brief", "evening_preview", "daily_nudge" -> NotificationChannels.DAILY to NotifIds.BRIEF
        "reminder", "event_soon" -> NotificationChannels.REMINDERS to NotifIds.REMINDER_BASE
        "collection_share" -> NotificationChannels.COLLAB to NotifIds.COLLAB
        else -> NotificationChannels.RECAP to NotifIds.RECAP
    }

    private fun openApp(context: Context, deepLink: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (deepLink != null) intent.data = Uri.parse(deepLink)
        return PendingIntent.getActivity(context, deepLink.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_orbit)
            .setColor(NotificationChannels.CORAL)
            .setGroup(NotificationChannels.GROUP)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_orbit)
                    .setContentTitle("unstuck")
                    .setContentText("Unlock to read")
                    .build(),
            )
            .setAutoCancel(true)

    /** Render an incoming FCM push (or a local reminder). Pass [notifId] to keep
     *  distinct items (e.g. per-task reminders) from replacing each other. */
    fun renderPush(context: Context, kind: String?, title: String, body: String, deepLink: String?, notifId: Int? = null) {
        if (!enabled(context)) return // don't log a "shown" entry for a notification the system suppresses
        val (channel, defaultId) = channelFor(kind)
        val id = notifId ?: defaultId
        val n = base(context, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, deepLink))
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        NotificationLog.add(context, kind, title, body, deepLink)
    }

    /** Post the paused-too-long check-in locally, with Resume / Snooze / End. */
    fun postPausedCheckin(context: Context, taskName: String) {
        if (!enabled(context)) return
        fun action(act: String) = PendingIntent.getBroadcast(
            context, act.hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(act)
                .putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, taskName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = base(context, NotificationChannels.PAUSED)
            .setColor(NotificationChannels.AMBER)
            .setContentTitle("Did you step away?")
            .setContentText(taskName)
            .setContentIntent(openApp(context, "unstuck://today"))
            .addAction(0, "Resume", action(NotificationActionReceiver.ACTION_RESUME))
            .addAction(0, "Snooze", action(NotificationActionReceiver.ACTION_SNOOZE))
            .addAction(0, "End", action(NotificationActionReceiver.ACTION_END))
            .build()
        NotificationManagerCompat.from(context).notify(NotifIds.PAUSED, n)
        NotificationLog.add(context, "paused_checkin", "Did you step away?", taskName, "unstuck://today")
    }

    /** A scheduled task's start time (A2), or the didn't-start follow-up (A4).
     *  Two shade actions that work with the app closed: **Start** (opens straight
     *  into Focus for the task and begins the timer) and **Reschedule** (one tap →
     *  moves the block to the next free slot today). */
    fun postTaskStarting(context: Context, taskName: String, taskId: String, blockId: String, drifted: Boolean) {
        if (!enabled(context)) return
        val title = if (drifted) "Didn't get to it?" else "Time to start"
        val body = if (drifted) "“$taskName” was set for a little while ago — want to start now?" else "“$taskName” starts now."
        // Start → launch the activity into the focus screen for this task.
        val startPI = PendingIntent.getActivity(
            context, ("start:$taskId").hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse("unstuck://focus/$taskId")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Reschedule → broadcast handled in the background (no need to open the app).
        val reschedPI = PendingIntent.getBroadcast(
            context, ("resched:$blockId").hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_RESCHEDULE)
                .putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
                .putExtra(NotificationActionReceiver.EXTRA_BLOCK_ID, blockId)
                .putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, taskName)
                .putExtra(NotificationActionReceiver.EXTRA_DRIFTED, drifted),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val id = if (drifted) NotifIds.drifted(taskId) else NotifIds.atStart(taskId)
        val n = base(context, NotificationChannels.REMINDERS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, "unstuck://task/$taskId"))
            .addAction(0, "Start", startPI)
            .addAction(0, "Reschedule", reschedPI)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        NotificationLog.add(context, if (drifted) "drifted" else "atstart", title, body, "unstuck://task/$taskId")
    }

    /** Brief confirmation after a one-tap Reschedule (replaces the start-now notif). */
    fun postRescheduleConfirmation(context: Context, taskName: String, newTime: String, taskId: String) {
        if (!enabled(context)) return
        val n = base(context, NotificationChannels.REMINDERS)
            .setContentTitle("Rescheduled")
            .setContentText("“$taskName” moved to ${formatClock(newTime)}.")
            .setContentIntent(openApp(context, "unstuck://task/$taskId"))
            .setTimeoutAfter(8_000)
            .build()
        NotificationManagerCompat.from(context).notify(NotifIds.atStart(taskId), n)
    }

    /** HH:MM (24h) → a friendly 12h clock for copy. */
    private fun formatClock(hhmm: String): String {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return hhmm
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val period = if (h >= 12) "PM" else "AM"
        val h12 = ((h + 11) % 12) + 1
        return "%d:%02d %s".format(h12, m, period)
    }
}
