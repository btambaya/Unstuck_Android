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
 * Time/schedule notifications driven by on-device exact alarms (punctual, and
 * they fire even when the app is closed; rescheduled on boot). Per upcoming
 * block we keep up to three alarms, gated by the user's [tech.csalliance.unstuck.NotificationLevel]:
 *  - LEAD    (A1) at (start − leadMinutes) — "Coming up". All levels (lead > 0).
 *  - ATSTART (A2) at start                 — "starts now" + Start / Reschedule. Balanced+.
 *  - DRIFTED (A4) at start + 10m           — "didn't get to it?" follow-up. Coach.
 * Best-effort: falls back to an inexact alarm if exact-alarm permission isn't granted.
 */
object ReminderScheduler {
    private const val PREFS = "unstuck.reminders"
    private const val KEY_SCHEDULED = "scheduled"
    private const val HORIZON_MS = 2L * 86_400_000 // schedule 48h ahead
    private const val DRIFT_MS = 10L * 60_000      // A4 fires 10 min after start

    private enum class Kind(val tag: String) { LEAD("lead"), ATSTART("atstart"), DRIFTED("drifted") }

    /** Re-sync reminders whenever the blocks or tasks change while the app is alive. */
    fun observe(app: UnstuckApp) {
        app.graph.scope.launch {
            app.graph.store.blocks().combine(app.graph.store.tasks()) { b, t -> b to t }
                .collect { (blocks, tasks) -> runCatching { sync(app, blocks, tasks) } }
        }
    }

    /** Rebuild all alarms from the current store (used after a reboot or a settings change). */
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
        val s = settingsStore.load()
        val globalLead = s.reminderLeadMin
        val level = s.notificationLevel
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_SCHEDULED, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()
        val nowSet = mutableSetOf<String>()

        fun arm(b: CalBlock, kind: Kind, fireAt: Long, lead: Int) {
            if (fireAt <= now || fireAt > now + HORIZON_MS) return
            setAlarm(ctx, am, b, kind, lead, fireAt)
            nowSet += key(b.id, kind)
        }

        for (b in blocks) {
            val isExternal = b.kind == CalBlockKind.EXTERNAL
            val isTask = isTaskBlock(b)
            if (!isTask && !isExternal) continue
            val startMs = blockStartMs(b) ?: continue
            if (isTask && tasks.firstOrNull { it.id == b.taskId }?.done == true) continue
            val taskId = b.taskId.orEmpty()

            // A1 pre-task — every level. External events use the global lead; tasks the override.
            val lead = if (isExternal) globalLead else (taskId.takeIf { it.isNotBlank() }?.let { settingsStore.reminderOverride(it) } ?: globalLead)
            if (lead > 0) arm(b, Kind.LEAD, startMs - lead * 60_000L, lead)
            // A2 starts-now (Start / Reschedule) — task blocks, Balanced+.
            if (isTask && level.atStart) arm(b, Kind.ATSTART, startMs, 0)
            // A4 didn't-start follow-up — task blocks, Coach.
            if (isTask && level.drifted) arm(b, Kind.DRIFTED, startMs + DRIFT_MS, 0)
        }
        (prev - nowSet).forEach { cancelKey(ctx, am, it) }
        prefs.edit().putString(KEY_SCHEDULED, nowSet.joinToString(",")).apply()
    }

    private fun key(blockId: String, kind: Kind) = "${kind.tag}:$blockId"

    private fun pendingIntent(ctx: Context, kind: Kind, blockId: String, taskName: String, taskId: String, lead: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("${kind.tag}:$blockId")
            .putExtra(ReminderReceiver.EXTRA_KIND, kind.tag)
            .putExtra(ReminderReceiver.EXTRA_TASK_NAME, taskName)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderReceiver.EXTRA_BLOCK_ID, blockId)
            .putExtra(ReminderReceiver.EXTRA_LEAD, lead)
        return PendingIntent.getBroadcast(ctx, key(blockId, kind).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun setAlarm(ctx: Context, am: AlarmManager, b: CalBlock, kind: Kind, lead: Int, fireAt: Long) {
        val pi = pendingIntent(ctx, kind, b.id, b.taskName, b.taskId ?: "", lead)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        else am.set(AlarmManager.RTC_WAKEUP, fireAt, pi)
    }

    /** Cancel a "tag:blockId" entry (recreates the matching PendingIntent to cancel it). */
    private fun cancelKey(ctx: Context, am: AlarmManager, entry: String) {
        val tag = entry.substringBefore(':')
        val blockId = entry.substringAfter(':')
        val kind = Kind.entries.firstOrNull { it.tag == tag } ?: return
        am.cancel(pendingIntent(ctx, kind, blockId, "", "", 0))
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
