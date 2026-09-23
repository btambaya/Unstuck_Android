package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CallKind
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The day's facts for a call, read from the local store as the call connects
 * (pure; CallVoiceService feeds it the Room snapshots of tasks + blocks).
 * Lines go into the call instructions' context ([CallScript.instructions]
 * `dayContext`), so "what got done today" and "what's on today" are answered
 * from here, not from a tool call the model may skip — and never from an
 * undated list. Zubair's evening call (2026-09-20 19:01): the model asked HIM
 * what got done, then read an undated all-time completed list as "today".
 * 1:1 port of iOS `CallDayContext` (parity with iOS build 75, f125845).
 */
object CallDayContext {
    const val MAX_NAMES = 12

    fun lines(
        kind: CallKind,
        tasks: List<TaskItem>,
        blocks: List<CalBlock>,
        today: String,
        nowHM: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val out = mutableListOf("today: $today (${weekdayName(today)}), now $nowHM")
        val byId = LinkedHashMap<String, TaskItem>()
        for (t in tasks) byId.putIfAbsent(t.id, t)
        val todays = blocks.filter { it.date == today }.sortedBy { it.startTime }
        // The task's current name first (a block's copy can be stale after a rename).
        fun name(b: CalBlock): String = b.taskId?.let { byId[it]?.name } ?: b.taskName.ifEmpty { "?" }
        // Done today: tasks ticked today (by the LOCAL day of completedAt) +
        // today's occurrence blocks ticked.
        val doneRaw = mutableListOf<String>()
        for (t in tasks) if (t.done && localDate(t.completedAt, zone) == today) doneRaw += t.name
        for (b in todays) if (b.done) doneRaw += name(b)
        val done = dedupe(doneRaw)
        // Still open today: today's live blocks whose task isn't done.
        val open = todays.filter { b ->
            if (b.done || b.skipped) return@filter false
            val t = b.taskId?.let { byId[it] }
            !(t != null && t.done)
        }
        val plan = todays.map { b ->
            val taskDone = b.taskId?.let { byId[it]?.done } ?: false
            "${b.startTime} ${name(b)}" + when {
                b.done || taskDone -> " · done"
                b.skipped -> " · skipped"
                else -> ""
            }
        }
        val tomorrow = addDaysIso(today, 1)
        val firstTomorrow = blocks.filter { it.date == tomorrow && !it.done && !it.skipped }.minByOrNull { it.startTime }
        fun names(xs: List<String>): String =
            if (xs.size <= MAX_NAMES) xs.joinToString(", ")
            else xs.take(MAX_NAMES).joinToString(", ") + " and ${xs.size - MAX_NAMES} more"
        val doneLine = if (done.isEmpty()) "done today: nothing ticked off yet" else "done today (${done.size}): ${names(done)}"
        val openLine = if (open.isEmpty()) "still open today: nothing"
            else "still open today (${open.size}): " + names(open.map { "${name(it)} (${it.startTime})" })
        val planLine = if (plan.isEmpty()) "today's plan: nothing scheduled"
            else "today's plan (${plan.size}): " + plan.take(MAX_NAMES).joinToString("; ") +
                (if (plan.size > MAX_NAMES) "; and ${plan.size - MAX_NAMES} more" else "")
        val tomorrowLine = firstTomorrow?.let { "tomorrow starts with: ${name(it)} at ${it.startTime}" } ?: "tomorrow: nothing scheduled yet"
        when (kind) {
            CallKind.EVENING -> out += listOf(doneLine, openLine, tomorrowLine)
            CallKind.MORNING -> out += listOf(planLine, doneLine)
            CallKind.AFTER_BLOCK -> out += listOf(openLine, doneLine)
            CallKind.REQUESTED, CallKind.TEST -> out += planLine
        }
        return out
    }

    /** 'YYYY-MM-DD' in [zone] for an ISO-8601 instant ("2026-09-20T08:10:00.000Z",
     *  or with an offset); null when absent or unparseable. */
    fun localDate(iso: String?, zone: ZoneId): String? {
        if (iso.isNullOrEmpty()) return null
        val instant = runCatching { Instant.parse(iso) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: return null
        return instant.atZone(zone).toLocalDate().toString()
    }

    private fun dedupe(xs: List<String>): List<String> {
        val seen = HashSet<String>()
        return xs.filter { seen.add(it.lowercase()) }
    }
}
