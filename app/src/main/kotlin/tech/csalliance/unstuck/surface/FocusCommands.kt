package tech.csalliance.unstuck.surface

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.data.LocalStore
import java.time.Instant

/**
 * Process-level focus mutations, shared by AppViewModel and the notification
 * action receiver so acting from the shade and from the UI stay consistent
 * (one implementation of the live-session writes). Mirrors AppViewModel's
 * pause/resume/finishFocus, operating directly on graph.store + write.
 */
object FocusCommands {
    private fun nowIso(): String = Instant.now().toString()

    // onComplete runs after the (async) write commits — the shade receiver
    // passes a goAsync() PendingResult.finish() so the process stays alive
    // until the Room write lands (was fire-and-forget → truncatable).
    fun pause(app: UnstuckApp, onComplete: () -> Unit = {}) = run(app, onComplete) { store ->
        store.getLiveSession()?.let { store.setLiveSession(FocusTimer.pause(it, System.currentTimeMillis())) }
    }

    fun resume(app: UnstuckApp, onComplete: () -> Unit = {}) = run(app, onComplete) { store ->
        store.getLiveSession()?.let {
            val r = FocusTimer.resume(it, System.currentTimeMillis())
            store.setLiveSession(r)
            // Re-arm the ongoing notification's chronometer at the POST-resume start so
            // it doesn't count the pause gap (was left at the stale pre-pause start).
            FocusTimerService.update(app, paused = false, startMs = r.sessionStart)
        }
    }

    /** End the session (no mark-done) — records the Session + accumulates focus time. */
    fun end(app: UnstuckApp, onComplete: () -> Unit = {}) = run(app, onComplete) { store ->
        val live = store.getLiveSession()
        if (live != null) {
            val elapsed = FocusTimer.elapsedSec(live, System.currentTimeMillis())
            val task = store.tasks().first().firstOrNull { it.id == live.taskId }
            val write = app.graph.coordinator?.write
            write?.upsertSession(
                Session(id = newUuid(), taskId = live.taskId, taskName = task?.name ?: "", estimateMin = live.sessionEstimateMin, actualSec = elapsed, completedAt = nowIso()),
            )
            task?.let { write?.upsertTask(it.copy(totalFocused = it.totalFocused + elapsed, updatedAt = nowIso())) }
            store.setLiveSession(null)
            // Ending from the notification means the user is away → server may push the recap.
            runCatching { app.graph.coordinator?.notifications?.sessionRecap(task?.name ?: "", away = true) }
        }
    }

    private inline fun run(app: UnstuckApp, crossinline onComplete: () -> Unit = {}, crossinline block: suspend (LocalStore) -> Unit) {
        app.graph.scope.launch {
            try { runCatching { block(app.graph.store) } } finally { onComplete() }
        }
    }
}
