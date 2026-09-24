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
import kotlinx.coroutines.withTimeoutOrNull
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.sync.liveUserId
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
        // syncNow reports the outcome itself: a thrown-or-not check could never see a
        // failure (the pull swallows them) nor a worker process whose session was still
        // loading (Android audit 2026-09-23, A2). No sync engine configured → nothing to do.
        val synced = runCatching { app.graph.coordinator?.syncNow() ?: true }.getOrDefault(false)
        // That pull's flush can confirm minted days, whose Google pushes then run on
        // the coordinator's Google worker (stage 2, rule G — Ahmad 2026-09-23 "every
        // day, everywhere"). Give them this worker's window rather than a process
        // that may be frozen or reclaimed the moment doWork returns.
        runCatching { withTimeoutOrNull(GOOGLE_DRAIN_MS) { app.graph.coordinator?.write?.awaitGoogleIdle() } }
        // A rotated FCM token that couldn't be registered when it arrived (offline, no
        // session yet) goes now, as the live user (Android audit 2026-09-23, A2).
        app.graph.coordinator?.let { c ->
            PendingPushToken.get(applicationContext)?.let { token ->
                PendingPushToken.register(applicationContext, token, liveUser = { c.session.ensure().liveUserId }) {
                    c.push.registerDevice(applicationContext, it)
                }
            }
        }
        // Refresh the Start-Next widget from the latest local store regardless. The
        // in-app updater only runs while AppViewModel is alive, so without this the
        // widget goes stale across process death.
        runCatching {
            val store = app.graph.store
            // Exclude tasks I've assigned away — a delegated task is someone else's now
            // and must never surface in the widget's Start-Next (same rule as the Today
            // list + the in-app widget updater). Best-effort: an offline/failed badge read just
            // yields no exclusions rather than blocking the widget refresh.
            val assigned = runCatching {
                tech.csalliance.unstuck.core.model.assignedOutIds(
                    app.graph.coordinator?.circle?.myTaskShareBadges() ?: emptyMap(),
                )
            }.getOrDefault(emptySet())
            val rec = tech.csalliance.unstuck.core.logic.pickStartNext(
                store.tasks().first(), store.blocks().first(), store.getLiveSession()?.taskId, null, assigned,
            )
            writeStartNext(applicationContext, rec?.name, rec?.estimateMin)
            StartNextWidget().updateAll(applicationContext)
        }
        return if (synced) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "unstuck_periodic_sync"

        /** How long the worker waits for the Google pushes its pull confirmed. */
        private const val GOOGLE_DRAIN_MS = 30_000L

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
