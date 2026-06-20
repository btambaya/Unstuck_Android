package tech.csalliance.unstuck.surface

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp
import java.util.concurrent.TimeUnit

/**
 * Paused-too-long check-in (design moment B2). When a focus session is paused
 * we arm a one-shot check ~14 min out (OneTimeWork has no 15-min floor and
 * survives process death). On fire it asks the server whether a push is allowed
 * (daily cap + preference + self-mute), and only then posts the local check-in.
 */
object PausedCheckinScheduler {
    private const val WORK = "paused-checkin"
    private const val DELAY_MIN = 14L

    fun arm(context: Context, taskName: String) {
        // settings.load() reads SharedPreferences (disk I/O); off the main thread so a
        // shade-action receiver / Compose caller never blocks the UI thread on it.
        val app = context.applicationContext as? UnstuckApp
        val scope = app?.graph?.scope
        if (scope == null) {
            // No app graph (shouldn't happen) — fall back to a one-shot background thread.
            Thread { armOnBackground(context, app, taskName) }.start()
            return
        }
        scope.launch(Dispatchers.IO) { armOnBackground(context, app, taskName) }
    }

    private fun armOnBackground(context: Context, app: UnstuckApp?, taskName: String) {
        // Respect the notification level — Calm doesn't do paused check-ins.
        val level = app?.graph?.settings?.load()?.notificationLevel
        if (level != null && !level.pausedCheckin) return
        val req = OneTimeWorkRequestBuilder<PausedCheckinWorker>()
            .setInitialDelay(DELAY_MIN, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_TASK to taskName))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun snooze(context: Context, taskName: String) = arm(context, taskName)

    const val KEY_TASK = "taskName"
}

class PausedCheckinWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? UnstuckApp ?: return Result.success()
        // Still paused? (resume/end cancel this work, but guard against races.)
        val live = runCatching { app.graph.store.getLiveSession() }.getOrNull()
        if (live == null || !live.paused) return Result.success()
        val notifs = app.graph.coordinator?.notifications ?: return Result.success()
        val allowed = runCatching { notifs.pausedCheckin() }.getOrDefault(false)
        if (allowed) {
            val taskName = inputData.getString(PausedCheckinScheduler.KEY_TASK) ?: "your task"
            NotificationRenderer.postPausedCheckin(applicationContext, taskName)
        }
        return Result.success()
    }
}
