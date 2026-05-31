package tech.csalliance.unstuck.surface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tech.csalliance.unstuck.UnstuckApp

/** Fires a pre-task reminder when its exact alarm goes off (design A1/A2/F1). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            (context.applicationContext as? UnstuckApp)?.let { ReminderScheduler.reschedule(it) }
            return
        }
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME)?.takeIf { it.isNotBlank() } ?: "your task"
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val lead = intent.getIntExtra(EXTRA_LEAD, 0)
        val body = if (lead > 0) "$taskName — in $lead minutes." else "$taskName is starting."
        val deepLink = if (taskId.isNotBlank()) "unstuck://task/$taskId" else "unstuck://today"
        val id = NotifIds.REMINDER_BASE + (taskId.hashCode() and 0xFFFF)
        NotificationChannels.ensureAll(context)
        NotificationRenderer.renderPush(context, kind = "reminder", title = "Coming up", body = body, deepLink = deepLink, notifId = id)
    }

    companion object {
        const val EXTRA_TASK_NAME = "taskName"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_LEAD = "lead"
    }
}
