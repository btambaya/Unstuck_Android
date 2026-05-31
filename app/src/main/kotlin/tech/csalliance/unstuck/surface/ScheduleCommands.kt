package tech.csalliance.unstuck.surface

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.findFreeSlotsForDate
import tech.csalliance.unstuck.core.time.Clock
import java.time.Instant

/**
 * Process-level "Reschedule" for the starts-now / drift notification action —
 * moves a task's block to the next free slot today (else +1h), bumps its
 * move-count (a real slip signal), re-arms the on-device alarms for the new
 * time, and confirms in the shade. Runs without opening the app.
 */
object ScheduleCommands {
    fun rescheduleToNextSlot(app: UnstuckApp, blockId: String, taskId: String, taskName: String) {
        val write = app.graph.coordinator?.write ?: return
        app.graph.scope.launch {
            runCatching {
                val store = app.graph.store
                val blocks = store.blocks().first()
                val block = blocks.firstOrNull { it.id == blockId } ?: return@launch
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
        }
    }

    /** HH:MM + 60 min, clamped to the end of the day. */
    private fun plusHour(hhmm: String): String {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 9
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val total = (h * 60 + m + 60).coerceAtMost(23 * 60 + 59)
        return "%02d:%02d".format(total / 60, total % 60)
    }
}
