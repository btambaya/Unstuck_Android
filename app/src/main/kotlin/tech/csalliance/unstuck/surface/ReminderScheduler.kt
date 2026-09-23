package tech.csalliance.unstuck.surface

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Best-effort: without exact-alarm access (Android 14+ denies it to a new install)
 * they fall back to an inexact alarm that still fires in Doze, and are all
 * re-armed exact once the user allows it ([ExactAlarmPermissionReceiver],
 * [resyncIfNowExact]).
 */
object ReminderScheduler {
    private const val PREFS = "unstuck.reminders"
    private const val KEY_SCHEDULED = "scheduled"
    /** Whether the last [sync] armed exact alarms. */
    private const val KEY_ARMED_EXACT = "armedExact"
    private const val HORIZON_MS = 2L * 86_400_000 // schedule 48h ahead
    private const val DRIFT_MS = 10L * 60_000      // A4 fires 10 min after start

    internal enum class Kind(val tag: String) { LEAD("lead"), ATSTART("atstart"), DRIFTED("drifted") }

    /** One alarm [sync] arms: the block, which of the three, when it fires, and
     *  the lead the "Coming up" copy announces. */
    internal data class Plan(val block: CalBlock, val kind: Kind, val fireAt: Long, val lead: Int)

    /** Re-sync reminders whenever the blocks or tasks change while the app is alive.
     *  Debounced + de-duped on an alarm-relevant projection so a burst of unrelated
     *  store writes doesn't rebuild the whole alarm set (with its blocking settings +
     *  SharedPreferences IO) on every emission; the actual sync runs off the main
     *  thread on Dispatchers.IO. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)   // debounce()
    fun observe(app: UnstuckApp) {
        app.graph.scope.launch {
            app.graph.store.blocks().combine(app.graph.store.tasks()) { b, t -> b to t }
                // Projection = only the fields the alarm set is derived from (block
                // timing/identity + which referenced tasks are done). Settings-driven
                // changes (lead overrides, level) go through reschedule() separately.
                .distinctUntilChanged { (oldB, oldT), (newB, newT) -> alarmSignature(oldB, oldT) == alarmSignature(newB, newT) }
                .debounce(500)
                .collect { (blocks, tasks) -> runCatching { withContext(Dispatchers.IO) { sync(app, blocks, tasks) } } }
        }
    }

    /** Stable signature of everything [sync] reads from the store, so identical
     *  re-emissions (or changes to unrelated fields like a task's name) don't trigger
     *  a full alarm rebuild. The block's own done / skipped are in it: ticking or
     *  skipping one day of a series changes only the BLOCK, and without them the
     *  emission was dropped here and that day's armed alarms were never cancelled
     *  (parity with iOS build 81, audit 2026-09-22 C2). */
    internal fun alarmSignature(
        blocks: List<CalBlock>,
        tasks: List<tech.csalliance.unstuck.core.model.TaskItem>,
    ): List<String> {
        val doneTaskIds = tasks.asSequence().filter { it.done }.map { it.id }.toSet()
        return blocks.map { b ->
            "${b.id}|${b.date}|${b.startTime}|${b.durationMinutes}|${b.kind}|${b.taskId}|${b.taskId in doneTaskIds}|${b.done}|${b.skipped}"
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

    /** Re-arm every reminder when exact alarms are allowed now but the last [sync]
     *  armed them inexact: the user granted "Alarms & reminders" and came back
     *  (the permission broadcast does the same; this covers a missed one). */
    fun resyncIfNowExact(app: UnstuckApp) {
        val prefs = app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (needsExactResync(ExactAlarms.granted(app.applicationContext), prefs.getBoolean(KEY_ARMED_EXACT, true))) reschedule(app)
    }

    internal fun needsExactResync(exactNow: Boolean, armedExact: Boolean): Boolean = exactNow && !armedExact

    private fun sync(app: UnstuckApp, blocks: List<CalBlock>, tasks: List<tech.csalliance.unstuck.core.model.TaskItem>) {
        val ctx = app.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val settingsStore = app.graph.settings
        val s = settingsStore.load()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_SCHEDULED, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()
        val nowSet = mutableSetOf<String>()
        val exact = ExactAlarms.granted(ctx)
        val plans = planReminders(blocks, tasks, s.notificationLevel, s.reminderLeadMin, settingsStore::reminderOverride, System.currentTimeMillis())
        for (p in plans) {
            setAlarm(ctx, am, p.block, p.kind, p.lead, p.fireAt, exact)
            nowSet += key(p.block.id, p.kind)
        }
        (prev - nowSet).forEach { cancelKey(ctx, am, it) }
        prefs.edit().putString(KEY_SCHEDULED, nowSet.joinToString(",")).putBoolean(KEY_ARMED_EXACT, exact).apply()
    }

    /** The alarms that should exist right now — the decision half of [sync], pure
     *  so it is unit-tested (iOS `planReminders` is the port of this loop). Only
     *  fire times in (now, now + 48h] are planned. */
    internal fun planReminders(
        blocks: List<CalBlock>,
        tasks: List<tech.csalliance.unstuck.core.model.TaskItem>,
        level: tech.csalliance.unstuck.NotificationLevel,
        globalLead: Int,
        leadOverride: (taskId: String) -> Int?,
        now: Long,
    ): List<Plan> {
        val out = ArrayList<Plan>()
        fun arm(b: CalBlock, kind: Kind, fireAt: Long, lead: Int) {
            if (fireAt <= now || fireAt > now + HORIZON_MS) return
            out += Plan(b, kind, fireAt, lead)
        }

        for (b in blocks) {
            val isExternal = b.kind == CalBlockKind.EXTERNAL
            val isTask = isTaskBlock(b)
            if (!isTask && !isExternal) continue
            val startMs = blockStartMs(b) ?: continue
            if (isTask && tasks.firstOrNull { it.id == b.taskId }?.done == true) continue
            // A repeating task keeps each day's tick and "Skip this day" on the
            // BLOCK (migration 033) — the template's `done` never flips — so a day
            // already handled must not ring "Coming up" / "Time to start" /
            // "Didn't get to it?". The server dispatcher's predicate (054, kept in
            // 070); it also covers a one-off block skipped by skip_occurrence or
            // carry_to_tomorrow (parity with iOS build 81, audit 2026-09-22 C2).
            if (isTask && (b.done || b.skipped)) continue
            val taskId = b.taskId.orEmpty()

            // A1 pre-task — every level. External events use the global lead; tasks the override.
            val lead = if (isExternal) globalLead else (taskId.takeIf { it.isNotBlank() }?.let(leadOverride) ?: globalLead)
            if (lead > 0) arm(b, Kind.LEAD, startMs - lead * 60_000L, lead)
            // A2 starts-now (Start / Reschedule) — task blocks, Balanced+.
            if (isTask && level.atStart) arm(b, Kind.ATSTART, startMs, 0)
            // A4 didn't-start follow-up — task blocks, Coach.
            if (isTask && level.drifted) arm(b, Kind.DRIFTED, startMs + DRIFT_MS, 0)
        }
        return out
    }

    private fun key(blockId: String, kind: Kind) = "${kind.tag}:$blockId"

    private fun pendingIntent(ctx: Context, kind: Kind, blockId: String, taskName: String, taskId: String, lead: Int, startAt: Long = 0L): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("${kind.tag}:$blockId")
            .putExtra(ReminderReceiver.EXTRA_KIND, kind.tag)
            .putExtra(ReminderReceiver.EXTRA_TASK_NAME, taskName)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderReceiver.EXTRA_BLOCK_ID, blockId)
            .putExtra(ReminderReceiver.EXTRA_LEAD, lead)
            .putExtra(ReminderReceiver.EXTRA_START_AT, startAt)
        return PendingIntent.getBroadcast(ctx, key(blockId, kind).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun setAlarm(ctx: Context, am: AlarmManager, b: CalBlock, kind: Kind, lead: Int, fireAt: Long, exact: Boolean) {
        // The block's start rides along so a reminder delivered late can say so.
        val pi = pendingIntent(ctx, kind, b.id, b.taskName, b.taskId ?: "", lead, blockStartMs(b) ?: 0L)
        arm(am, fireAt, pi, exact)
    }

    /** Exact when allowed. Otherwise an inexact alarm that still fires in Doze: the
     *  plain set() this used was deferred to Doze's maintenance windows, so on a
     *  new Android 14+ install a reminder armed with the phone on a desk arrived an
     *  hour or more late (Android audit 2026-09-23, A15). */
    internal fun arm(am: AlarmManager, fireAt: Long, pi: PendingIntent, exact: Boolean) {
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
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
