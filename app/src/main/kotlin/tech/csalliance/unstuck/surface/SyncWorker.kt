package tech.csalliance.unstuck.surface

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.first
import tech.csalliance.unstuck.UnstuckApp
import java.util.concurrent.TimeUnit

// Periodic best-effort sync (flush outbox + hydrate) while the app is
// backgrounded — the Android analog of the iOS BGTaskScheduler refresh.
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? UnstuckApp ?: return Result.success()
        // A failed sync (transient REST/network error) used to be swallowed and the
        // worker always reported success, so WorkManager never retried — the device
        // just sat un-synced until the next 30-min tick. Surface it as Result.retry()
        // so WorkManager applies the exponential backoff configured on the request.
        val synced = runCatching { app.graph.coordinator?.syncNow() }.isSuccess
        // Refresh the Start-Next widget from the latest local store regardless. The
        // in-app updater only runs while AppViewModel is alive, so without this the
        // widget goes stale across process death.
        runCatching {
            val store = app.graph.store
            val rec = tech.csalliance.unstuck.core.logic.pickStartNext(
                store.tasks().first(), store.blocks().first(), store.getLiveSession()?.taskId, null,
            )
            writeStartNext(applicationContext, rec?.name, rec?.estimateMin)
            StartNextWidget().updateAll(applicationContext)
        }
        return if (synced) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "unstuck_periodic_sync"

        fun schedule(context: Context) {
            // Only run when there's a network — a sync with no connection just wakes
            // the device to fail.
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // Exponential backoff on Result.retry() (a transient sync failure)
                // instead of waiting out the full 30-min period.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
