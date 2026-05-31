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

    /** Map the server's `data.kind` to a channel + stable id. */
    private fun channelFor(kind: String?): Pair<String, Int> = when (kind) {
        "session_recap" -> NotificationChannels.RECAP to NotifIds.RECAP
        "paused_checkin" -> NotificationChannels.PAUSED to NotifIds.PAUSED
        "morning_brief", "evening_preview", "daily_nudge" -> NotificationChannels.DAILY to NotifIds.BRIEF
        "reminder", "event_soon" -> NotificationChannels.REMINDERS to NotifIds.REMINDER_BASE
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
        val (channel, defaultId) = channelFor(kind)
        val id = notifId ?: defaultId
        val n = base(context, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, deepLink))
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** Post the paused-too-long check-in locally, with Resume / Snooze / End. */
    fun postPausedCheckin(context: Context, taskName: String) {
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
    }
}
