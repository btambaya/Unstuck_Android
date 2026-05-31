package tech.csalliance.unstuck.surface

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pre-task reminders (design A1/A2 + Google event-soon F1). Watches the
 * scheduled blocks and keeps an exact alarm per upcoming block at
 * (start − leadMinutes). Lead = the per-task override, else the global default
 * from settings (0 = off). Exact alarms must be punctual ("5 min before"), so
 * AlarmManager (not WorkManager); rescheduled on boot. Best-effort — falls back
 * to an inexact alarm if exact-alarm permission isn't granted.
 */
object ReminderScheduler {
    private const val PREFS = "unstuck.reminders"
    private const val KEY_SCHEDULED = "scheduled"
    private const val HORIZON_MS = 2L * 86_400_000 // schedule 48h ahead

    /** Re-sync reminders whenever the blocks or tasks change while the app is alive. */
    fun observe(app: UnstuckApp) {
        app.graph.scope.launch {
            app.graph.store.blocks().combine(app.graph.store.tasks()) { b, t -> b to t }
                .collect { (blocks, tasks) -> runCatching { sync(app, blocks, tasks) } }
        }
    }

    /** Rebuild all alarms from the current store (used after a reboot). */
    fun reschedule(app: UnstuckApp) {
        app.graph.scope.launch {
            val blocks = runCatching { app.graph.store.blocks().first() }.getOrDefault(emptyList())
            val tasks = runCatching { app.graph.store.tasks().first() }.getOrDefault(emptyList())
            runCatching { sync(app, blocks, tasks) }
        }
    }

    private fun sync(app: UnstuckApp, blocks: List<CalBlock>, tasks: List<tech.csalliance.unstuck.core.model.TaskItem>) {
        val ctx = app.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val settingsStore = app.graph.settings
        val globalLead = settingsStore.load().reminderLeadMin
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_SCHEDULED, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()
        val nowSet = mutableSetOf<String>()

        for (b in blocks) {
            val isExternal = b.kind == CalBlockKind.EXTERNAL
            if (!isTaskBlock(b) && !isExternal) continue
            val startMs = blockStartMs(b) ?: continue
            val lead = if (isExternal) globalLead else (b.taskId?.let { settingsStore.reminderOverride(it) } ?: globalLead)
            if (lead <= 0) continue
            val fireAt = startMs - lead * 60_000L
            if (fireAt <= now || fireAt > now + HORIZON_MS) continue
            if (isTaskBlock(b) && tasks.firstOrNull { it.id == b.taskId }?.done == true) continue
            setAlarm(ctx, am, b, lead, fireAt)
            nowSet += b.id
        }
        (prev - nowSet).forEach { cancel(ctx, am, it) }
        prefs.edit().putString(KEY_SCHEDULED, nowSet.joinToString(",")).apply()
    }

    private fun pendingIntent(ctx: Context, blockId: String, taskName: String, taskId: String, lead: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("reminder:$blockId")
            .putExtra(ReminderReceiver.EXTRA_TASK_NAME, taskName)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderReceiver.EXTRA_LEAD, lead)
        return PendingIntent.getBroadcast(ctx, blockId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun setAlarm(ctx: Context, am: AlarmManager, b: CalBlock, lead: Int, fireAt: Long) {
        val pi = pendingIntent(ctx, b.id, b.taskName, b.taskId ?: "", lead)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        else am.set(AlarmManager.RTC_WAKEUP, fireAt, pi)
    }

    private fun cancel(ctx: Context, am: AlarmManager, blockId: String) {
        am.cancel(pendingIntent(ctx, blockId, "", "", 0))
    }

    private fun blockStartMs(b: CalBlock): Long? {
        val d = b.date.split("-").mapNotNull { it.toIntOrNull() }
        val t = b.startTime.split(":").mapNotNull { it.toIntOrNull() }
        if (d.size != 3 || t.size < 2) return null
        return runCatching {
            LocalDate.of(d[0], d[1], d[2]).atTime(t[0], t[1]).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
