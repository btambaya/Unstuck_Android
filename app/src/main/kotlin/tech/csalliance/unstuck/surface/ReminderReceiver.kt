package tech.csalliance.unstuck.surface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp

/**
 * Re-arms pending reminder alarms on boot AND on app update — without handling
 * MY_PACKAGE_REPLACED the update both DROPPED all pending reminders and fell
 * through to fire a bogus "Coming up · your task is starting". This is the only
 * exported reminder receiver (the system sends these broadcasts from another
 * UID) and it deliberately reads NO intent extras, so other apps can't abuse it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        (context.applicationContext as? UnstuckApp)?.let { ReminderScheduler.reschedule(it) }
    }
}

/**
 * Fires a time/schedule notification when its exact alarm goes off. The
 * `EXTRA_KIND` set by [ReminderScheduler] selects the moment:
 *  - "lead"    → pre-task "Coming up" (tap opens the task).
 *  - "atstart" → "starts now" with Start / Reschedule shade actions.
 *  - "drifted" → "didn't get to it?" follow-up (also Start / Reschedule).
 * For atstart/drifted we re-check at fire time that the task isn't already done
 * or being focused, so the nudge never fires for something already handled.
 *
 * NOT exported: the alarm PendingIntents target this class by explicit component
 * (created by this app), so delivery still works — but no third-party app can
 * inject spoofed extras to post a phishing notification under Unstuck's identity.
 * Boot/update rescheduling lives in [BootReceiver].
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "lead"
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME)?.takeIf { it.isNotBlank() } ?: "your task"
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID).orEmpty()
        val lead = intent.getIntExtra(EXTRA_LEAD, 0)
        val drifted = kind == "drifted"
        NotificationChannels.ensureAll(context)

        fun post() {
            if (kind == "lead") {
                val body = if (lead > 0) "$taskName — in $lead minutes." else "$taskName is starting."
                val deepLink = if (taskId.isNotBlank()) "unstuck://task/$taskId" else "unstuck://today"
                // External calendar events have a blank task id — key the notif id off the
                // block id instead so two events close in time don't share one id (overwrite).
                NotificationRenderer.renderPush(context, kind = "reminder", title = "Coming up", body = body, deepLink = deepLink, notifId = NotifIds.reminder(taskId.ifBlank { blockId }))
            } else {
                NotificationRenderer.postTaskStarting(context, taskName, taskId, blockId, drifted)
            }
        }

        // Validate at fire time so a STALE alarm doesn't post a PHANTOM. A task
        // can be deleted (or rescheduled) server-side while this device is
        // offline, so sync() never gets to cancel its already-armed alarm — it
        // then fires for a task that's no longer there ("got a reminder for a
        // task I don't see anywhere"). Suppress when the block (the schedule) is
        // gone, or the task is gone / done / being focused. ALL kinds are now
        // checked — previously only atstart/drifted re-checked, and only for
        // `done`, so a deleted task (or any `lead` reminder) still fired.
        // Only suppress on a CONFIRMED-absent read; a store read error leaves a
        // possibly-real reminder intact.
        val app = context.applicationContext as? UnstuckApp
        if (app == null) { post(); return }   // no app context → can't validate; best-effort
        val pending = goAsync()
        app.graph.scope.launch {
            try {
                // Bound the validation reads: on a cold/DB-locked path these suspend
                // reads can run long enough that the OS kills the receiver before
                // anything fires — a MISSED reminder. If they don't finish in time,
                // fall through to a best-effort post() (a possibly-real reminder is
                // better than a silently-dropped one — same bias as a read error).
                val validated = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    val blocks = runCatching { app.graph.store.blocks().first() }.getOrNull()
                    val tasks = runCatching { app.graph.store.tasks().first() }.getOrNull()
                    if (blocks != null && blocks.none { it.id == blockId }) return@withTimeoutOrNull false   // schedule gone
                    if (taskId.isNotBlank() && tasks != null) {
                        val task = tasks.firstOrNull { it.id == taskId }
                        if (task == null || task.done) return@withTimeoutOrNull false   // task deleted or already done
                    }
                    val focusingIt = runCatching { app.graph.store.getLiveSession()?.taskId == taskId }.getOrNull() == true
                    if ((kind == "atstart" || drifted) && focusingIt) return@withTimeoutOrNull false
                    true   // confirmed still relevant → post
                }
                // null = reads timed out → best-effort post; false = confirmed-absent → suppress.
                if (validated != false) post()
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
