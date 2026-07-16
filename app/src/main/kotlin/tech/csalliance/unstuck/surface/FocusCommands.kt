package tech.csalliance.unstuck.surface

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.SharedFocusLedger
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.sync.SharedFocusLogResult
import java.time.Instant

/**
 * Process-level focus mutations, shared by AppViewModel and the notification
 * action receiver so acting from the shade and from the UI stay consistent
 * (one implementation of the live-session writes). Mirrors AppViewModel's
 * pause/resume/finishFocus, operating directly on graph.store + write.
 */
object FocusCommands {
    private fun nowIso(): String = Instant.now().toString()

    /** Stamp a LOCAL shade control with the next shared (rev, atMs) when the session is
     *  a live partner co-focus (rev cursors only exist on those) — the same atomic
     *  stamp as AppViewModel.mutateLive(control = true), so the session-lifetime
     *  broadcaster (when the process still has one) rebroadcasts it and observers
     *  classify the change as local (core.logic.remotePaused / sharedRevFloor). */
    private fun stampControl(cur: LiveSession, next: LiveSession): LiveSession =
        if (next != cur && (cur.sharedSessionRev != null || cur.lastAppliedRev != null)) {
            next.copy(
                sharedSessionRev = maxOf(cur.sharedSessionRev ?: 0, cur.lastAppliedRev ?: 0) + 1,
                sharedSessionAtMs = System.currentTimeMillis(),
            )
        } else {
            next
        }

    // onComplete runs after the (async) write commits — the shade receiver
    // passes a goAsync() PendingResult.finish() so the process stays alive
    // until the Room write lands (was fire-and-forget → truncatable).
    fun pause(app: UnstuckApp, onComplete: () -> Unit = {}) = run(app, onComplete) { store ->
        store.getLiveSession()?.let { store.setLiveSession(stampControl(it, FocusTimer.pause(it, System.currentTimeMillis()))) }
    }

    fun resume(app: UnstuckApp, onComplete: () -> Unit = {}) = run(app, onComplete) { store ->
        store.getLiveSession()?.let {
            val r = stampControl(it, FocusTimer.resume(it, System.currentTimeMillis()))
            store.setLiveSession(r)
            // Re-arm the ongoing notification's chronometer at the POST-resume start so
            // it doesn't count the pause gap (was left at the stale pre-pause start).
            FocusTimerService.update(app, paused = false, startMs = r.sessionStart)
        }
    }

    /** End the session (no mark-done) — records the Session + accumulates focus time.
     *  Shared sessions route through the log_shared_focus ledger (one-true-shared-
     *  session): clearing the row first lets AppViewModel's session-lifetime channel
     *  (when the process still has one) broadcast `ended` to the partner best-effort. */
    fun end(app: UnstuckApp, onComplete: () -> Unit = {}) = run(app, onComplete) { store ->
        val live = store.getLiveSession() ?: return@run
        val elapsed = FocusTimer.elapsedSec(live, System.currentTimeMillis())
        val write = app.graph.coordinator?.write
        val circle = app.graph.coordinator?.circle
        val sharedTitle = live.sharedTitle
        if (sharedTitle != null) {
            // Shared-with-me session (T3): the task is the OWNER's — never mint an own
            // Session row / task upsert for a foreign task id (this was the shade's
            // broken branch). Accrue onto the owner via the idempotent ledger instead;
            // the RPC clamps (client clamp inside logSharedFocus + the 12h server cap).
            // Durable: a transient failure persists a retry drained on next foreground.
            val sid = live.id ?: newUuid()
            store.setLiveSession(null)
            runCatching { SharedFocusLedger.logOrQueue(app.graph.settings, circle, live.taskId, elapsed, sid, live.sessionEstimateMin) }
            runCatching { app.graph.coordinator?.notifications?.sessionRecap(sharedTitle, away = true) }
            return@run
        }
        val task = store.tasks().first().firstOrNull { it.id == live.taskId }
        write?.upsertSession(
            // Reuse the live-session id so captures taken during the session join back
            // to this Session row (the interruption histogram keys on it) — matches
            // AppViewModel.finishFocus. A fresh uuid orphaned them.
            Session(id = live.id ?: newUuid(), taskId = live.taskId, taskName = task?.name ?: "", estimateMin = live.sessionEstimateMin, actualSec = elapsed, completedAt = nowIso()),
        )
        store.setLiveSession(null)
        if (live.sharedSessionRev != null || live.lastAppliedRev != null) {
            // Owner side of a live partner co-focus (rev cursors only exist there): the
            // total accrues EXCLUSIVELY via the ledger — exactly-once per session id, so
            // the partner finalizing the SAME session can't double-count. Skip the bump.
            // Durable: FAILED → persisted retry; NOT_ALLOWED (the share was revoked
            // mid-session, so the cursors mis-route an effectively-own session here) →
            // keep the minutes via the direct bump the ledger refused.
            val r = runCatching {
                SharedFocusLedger.logOrQueue(app.graph.settings, circle, live.taskId, elapsed, live.id ?: newUuid(), live.sessionEstimateMin)
            }.getOrElse { SharedFocusLogResult.FAILED }
            if (r == SharedFocusLogResult.NOT_ALLOWED) {
                val add = FocusTimer.clampSharedElapsedSec(elapsed, live.sessionEstimateMin)
                if (add > 0) task?.let { write?.upsertTask(it.copy(totalFocused = it.totalFocused + add, updatedAt = nowIso())) }
            }
        } else {
            task?.let { write?.upsertTask(it.copy(totalFocused = it.totalFocused + elapsed, updatedAt = nowIso())) }
        }
        // Ending from the notification means the user is away → server may push the recap.
        runCatching { app.graph.coordinator?.notifications?.sessionRecap(task?.name ?: "", away = true) }
    }

    private inline fun run(app: UnstuckApp, crossinline onComplete: () -> Unit = {}, crossinline block: suspend (LocalStore) -> Unit) {
        app.graph.scope.launch {
            try { runCatching { block(app.graph.store) } } finally { onComplete() }
        }
    }
}
