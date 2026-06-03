package tech.csalliance.unstuck.surface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp

/**
 * Fires a time/schedule notification when its exact alarm goes off. The
 * `EXTRA_KIND` set by [ReminderScheduler] selects the moment:
 *  - "lead"    → pre-task "Coming up" (tap opens the task).
 *  - "atstart" → "starts now" with Start / Reschedule shade actions.
 *  - "drifted" → "didn't get to it?" follow-up (also Start / Reschedule).
 * For atstart/drifted we re-check at fire time that the task isn't already done
 * or being focused, so the nudge never fires for something already handled.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm pending alarms on boot AND on app update — without handling
        // MY_PACKAGE_REPLACED the update both DROPPED all pending reminders and
        // fell through to fire a bogus "Coming up · your task is starting".
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            (context.applicationContext as? UnstuckApp)?.let { ReminderScheduler.reschedule(it) }
            return
        }
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "lead"
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME)?.takeIf { it.isNotBlank() } ?: "your task"
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID).orEmpty()
        val lead = intent.getIntExtra(EXTRA_LEAD, 0)
        NotificationChannels.ensureAll(context)

        if (kind == "lead") {
            val body = if (lead > 0) "$taskName — in $lead minutes." else "$taskName is starting."
            val deepLink = if (taskId.isNotBlank()) "unstuck://task/$taskId" else "unstuck://today"
            NotificationRenderer.renderPush(context, kind = "reminder", title = "Coming up", body = body, deepLink = deepLink, notifId = NotifIds.reminder(taskId))
            return
        }

        // atstart / drifted — re-check the task is still worth nudging about.
        val drifted = kind == "drifted"
        val app = context.applicationContext as? UnstuckApp
        if (app == null) {
            NotificationRenderer.postTaskStarting(context, taskName, taskId, blockId, drifted)
            return
        }
        val pending = goAsync()
        app.graph.scope.launch {
            try {
                val done = runCatching { app.graph.store.tasks().first().firstOrNull { it.id == taskId }?.done }.getOrNull() == true
                val focusingIt = runCatching { app.graph.store.getLiveSession()?.taskId == taskId }.getOrNull() == true
                if (!done && !focusingIt) NotificationRenderer.postTaskStarting(context, taskName, taskId, blockId, drifted)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TASK_NAME = "taskName"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_BLOCK_ID = "blockId"
        const val EXTRA_LEAD = "lead"
    }
}
