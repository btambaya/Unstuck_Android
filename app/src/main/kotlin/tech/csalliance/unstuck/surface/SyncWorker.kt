package tech.csalliance.unstuck.surface

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import tech.csalliance.unstuck.UnstuckApp
import java.util.concurrent.TimeUnit

// Periodic best-effort sync (flush outbox + hydrate) while the app is
// backgrounded — the Android analog of the iOS BGTaskScheduler refresh.
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? UnstuckApp ?: return Result.success()
        runCatching { app.graph.coordinator?.syncNow() }
        return Result.success()
    }

    companion object {
        private const val NAME = "unstuck_periodic_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
