package tech.csalliance.unstuck.surface

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.findFreeSlotsForDate
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.WireTime
import java.time.Instant

/**
 * Process-level "Reschedule" for the starts-now / drift notification action —
 * moves a task's block to the next free slot today (else +1h), bumps its
 * move-count (a real slip signal), re-arms the on-device alarms for the new
 * time, and confirms in the shade. Runs without opening the app.
 */
object ScheduleCommands {
    fun rescheduleToNextSlot(app: UnstuckApp, blockId: String, taskId: String, taskName: String, onComplete: () -> Unit = {}) {
        val write = app.graph.coordinator?.write ?: run { onComplete(); return }
        app.graph.scope.launch {
            try {
                runCatching {
                    val store = app.graph.store
                    val blocks = store.blocks().first()
                    val block = blocks.firstOrNull { it.id == blockId } ?: return@runCatching
                    val task = store.tasks().first().firstOrNull { it.id == taskId }
                    val estimate = task?.estimateMin ?: block.durationMinutes
                    val today = Clock.todayIso()
                    val now = System.currentTimeMillis()
                    val slot = findFreeSlotsForDate(blocks, estimate, today, now, limit = 1).firstOrNull()
                    val newDate = slot?.date ?: today
                    val newTime = slot?.startTime ?: plusHour(block.startTime)

                    write.upsertCalBlock(block.copy(date = newDate, startTime = newTime))
                    task?.let { write.upsertTask(bumpMoveCount(it, Instant.now().toString())) }
                    ReminderScheduler.reschedule(app)
                    NotificationRenderer.postRescheduleConfirmation(app.applicationContext, taskName, newTime, taskId)
                }
                // Push the move to the server before the receiver lets go (goAsync):
                // the debounced drain would run after it, when a cached process may
                // already be frozen, and it used to find no session there anyway —
                // web / iPhone and the server's calls kept the old slot (Android audit
                // 2026-09-23, A2). The drain establishes the session itself; bounded
                // inside the broadcast window.
                runCatching {
                    withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
                        runCatching { app.graph.coordinator?.flushOutbox() }.onFailure { if (it is CancellationException) throw it }
                        // The Google PATCH of the move runs on the coordinator's Google
                        // worker since stage 2, no longer inside upsertCalBlock: wait for
                        // it in the same window, or a process frozen once the receiver
                        // lets go left the event at the old slot (stage 2 review, Ahmad
                        // 2026-09-23).
                        write.awaitGoogleIdle()
                    }
                }
            } finally { onComplete() }
        }
    }

    /** The in-receiver drain (session + push) and the Google push, together inside
     *  the ~10 s goAsync window. */
    private const val FLUSH_TIMEOUT_MS = 8_000L

    /** HH:MM + 60 min, clamped to the end of the day. */
    private fun plusHour(hhmm: String): String {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 9
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val total = (h * 60 + m + 60).coerceAtMost(23 * 60 + 59)
        return WireTime.hm(total / 60, total % 60)
    }
}
