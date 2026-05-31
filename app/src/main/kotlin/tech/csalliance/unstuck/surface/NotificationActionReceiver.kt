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
            ACTION_PAUSE -> {
                FocusCommands.pause(app)
                FocusTimerService.update(context, paused = true)
                PausedCheckinScheduler.arm(context, taskName)
            }
            ACTION_RESUME -> {
                FocusCommands.resume(app)
                FocusTimerService.update(context, paused = false)
                PausedCheckinScheduler.cancel(context)
                NotificationManagerCompat.from(context).cancel(NotifIds.PAUSED)
            }
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(NotifIds.PAUSED)
                PausedCheckinScheduler.snooze(context, taskName)
            }
            ACTION_END -> {
                FocusCommands.end(app)
                PausedCheckinScheduler.cancel(context)
                NotificationManagerCompat.from(context).cancel(NotifIds.PAUSED)
                FocusTimerService.stop(context)
            }
            ACTION_CAPTURE -> context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OPEN_CAPTURE, true),
            )
        }
    }

    companion object {
        const val ACTION_PAUSE = "tech.csalliance.unstuck.action.PAUSE"
        const val ACTION_RESUME = "tech.csalliance.unstuck.action.RESUME"
        const val ACTION_SNOOZE = "tech.csalliance.unstuck.action.SNOOZE"
        const val ACTION_END = "tech.csalliance.unstuck.action.END"
        const val ACTION_CAPTURE = "tech.csalliance.unstuck.action.CAPTURE"
        const val EXTRA_TASK_NAME = "taskName"
        const val EXTRA_OPEN_CAPTURE = "openCapture"
    }
}
