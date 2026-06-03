package tech.csalliance.unstuck.surface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import tech.csalliance.unstuck.MainActivity
import tech.csalliance.unstuck.UnstuckApp

/**
 * Handles the live-notification / paused-check-in action buttons so the user can
 * act from the shade without opening the app. All mutations go through the
 * shared [FocusCommands] (same writes as AppViewModel).
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? UnstuckApp ?: return
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME) ?: "your task"
        when (intent.action) {
            // The write-performing actions keep the receiver alive via goAsync()
            // until the async Room write commits — otherwise tapping from the
            // shade on a cold/low-priority process can truncate the coroutine
            // and leave the live session in an inconsistent state.
            ACTION_PAUSE -> {
                val pending = goAsync()
                FocusCommands.pause(app) { pending.finish() }
                FocusTimerService.update(context, paused = true)
                PausedCheckinScheduler.arm(context, taskName)
            }
            ACTION_RESUME -> {
                val pending = goAsync()
                // FocusCommands.resume re-arms the service chronometer with the correct
                // post-resume start (passing it here would use the stale pre-pause one).
                FocusCommands.resume(app) { pending.finish() }
                PausedCheckinScheduler.cancel(context)
                NotificationManagerCompat.from(context).cancel(NotifIds.PAUSED)
            }
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(NotifIds.PAUSED)
                PausedCheckinScheduler.snooze(context, taskName)
            }
            ACTION_END -> {
                val pending = goAsync()
                FocusCommands.end(app) { pending.finish() }
                PausedCheckinScheduler.cancel(context)
                NotificationManagerCompat.from(context).cancel(NotifIds.PAUSED)
                FocusTimerService.stop(context)
            }
            ACTION_CAPTURE -> context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OPEN_CAPTURE, true),
            )
            // "Reschedule" on the starts-now / drift notification — move the block to the
            // next free slot today, in the background (no need to open the app).
            ACTION_RESCHEDULE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                val blockId = intent.getStringExtra(EXTRA_BLOCK_ID).orEmpty()
                val drifted = intent.getBooleanExtra(EXTRA_DRIFTED, false)
                NotificationManagerCompat.from(context)
                    .cancel(if (drifted) NotifIds.drifted(taskId) else NotifIds.atStart(taskId))
                val pending = goAsync()
                ScheduleCommands.rescheduleToNextSlot(app, blockId, taskId, taskName) { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "tech.csalliance.unstuck.action.PAUSE"
        const val ACTION_RESUME = "tech.csalliance.unstuck.action.RESUME"
        const val ACTION_SNOOZE = "tech.csalliance.unstuck.action.SNOOZE"
        const val ACTION_END = "tech.csalliance.unstuck.action.END"
        const val ACTION_CAPTURE = "tech.csalliance.unstuck.action.CAPTURE"
        const val ACTION_RESCHEDULE = "tech.csalliance.unstuck.action.RESCHEDULE"
        const val EXTRA_TASK_NAME = "taskName"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_BLOCK_ID = "blockId"
        const val EXTRA_DRIFTED = "drifted"
        const val EXTRA_OPEN_CAPTURE = "openCapture"
    }
}
