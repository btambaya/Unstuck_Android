package tech.csalliance.unstuck.ui.assistant

import tech.csalliance.unstuck.core.logic.Moment
import tech.csalliance.unstuck.core.logic.MomentFact
import tech.csalliance.unstuck.core.logic.MomentRituals
import tech.csalliance.unstuck.core.logic.MomentState
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.composeBrief
import tech.csalliance.unstuck.core.logic.pickMoment
import tech.csalliance.unstuck.core.logic.toneFromFacts
import tech.csalliance.unstuck.core.logic.usableToday
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time

// The pure half of the AI gateway card on Today — port of the reducer,
// memo and input key in iOS App/Features/GatewayCard.swift (GatewayActions,
// GatewayMemo, GatewayInputs) and the derivations in
// components/dashboard/gateway-card.tsx. Nothing here touches the store or
// Compose: the ViewModel applies the writes through the assistant's own
// AssistantApi seam (the SAME path every tool takes) and the card only draws.

// ── action reducer ──────────────────────────────────────────────────────────

/** What a moment action wants written. `confirmation == null` means NOTHING
 *  happened (nothing to carry, the task is gone): no ✓ line, no writes. */
data class GatewayWrites(
    val blocks: List<CalBlock> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val confirmation: String? = null,
)

object GatewayActions {
    /** `carry_tasks`: move today's unfinished blocks for [taskIds] to tomorrow.
     *  Carries only what was actually on today. A recurring task usually ALREADY
     *  has tomorrow's occurrence — moving today's block would double it up, so
     *  today's is marked skipped instead (same outcome, no duplicate). Every real
     *  carry bumps the task's moveCount — an honest slip counter is what makes
     *  Slip Radar honest. Nothing moved → null confirmation ("Carried 0 to
     *  tomorrow" was a lie the host showed as ✓). */
    fun carryTasks(
        taskIds: List<String>, tasks: List<TaskItem>, blocks: List<CalBlock>,
        todayIso: String, tomorrowIso: String, nowIso: String,
    ): GatewayWrites {
        val outBlocks = ArrayList<CalBlock>()
        val outTasks = ArrayList<TaskItem>()
        var n = 0
        for (id in taskIds) {
            val b = blocks.firstOrNull { it.taskId == id && it.date == todayIso && !it.done && !it.skipped } ?: continue
            val tomorrowTaken = blocks.any { it.taskId == id && it.date == tomorrowIso && !it.skipped }
            outBlocks += if (tomorrowTaken) b.copy(skipped = true) else b.copy(date = tomorrowIso)
            n += 1
            tasks.firstOrNull { it.id == id }?.let { outTasks += bumpMoveCount(it, nowIso) }
        }
        return GatewayWrites(outBlocks, outTasks, if (n > 0) "Carried $n to tomorrow." else null)
    }

    /** `schedule`: move only a LIVE, upcoming block for the task (grabbing a
     *  skipped or historical one said "Blocked ✓" while dragging history around);
     *  otherwise create a fresh block. The anchor is the SOONEST live block (date,
     *  then start time), not whichever the store listed first. No moveCount bump
     *  (web parity — booking a habit gap isn't a slip). A task that no longer
     *  exists → nothing (a block titled "Task" pointing at a ghost was the old
     *  behaviour). */
    fun schedule(
        taskId: String, date: String, time: String?, tasks: List<TaskItem>, blocks: List<CalBlock>,
        todayIso: String, newId: String,
    ): GatewayWrites {
        val t = tasks.firstOrNull { it.id == taskId } ?: return GatewayWrites()
        val anchor = blocks
            .filter { it.taskId == taskId && !it.done && !it.skipped && it.date >= todayIso }
            .minWithOrNull(compareBy<CalBlock> { it.date }.thenBy { it.startTime }.thenBy { it.id })
        val block = if (anchor != null) {
            anchor.copy(date = date, startTime = time ?: anchor.startTime)
        } else {
            CalBlock(id = newId, taskId = taskId, taskName = t.name, startTime = time ?: "09:00",
                durationMinutes = t.estimateMin, date = date, kind = CalBlockKind.TASK)
        }
        return GatewayWrites(listOf(block), emptyList(), "Blocked — ${t.name}, $date${time?.let { " $it" } ?: ""}.")
    }

    /** `create_task`: a plain new task (estimate defaults to 25 like the web). */
    fun createTask(name: String, estimateMin: Int?, id: String, nowIso: String): GatewayWrites {
        val t = TaskItem(id = id, name = name, estimateMin = estimateMin ?: 25, totalFocused = 0, done = false,
            createdAt = nowIso, updatedAt = nowIso)
        return GatewayWrites(emptyList(), listOf(t), "Added “$name”.")
    }
}

// ── brief + moment memo ─────────────────────────────────────────────────────

/** One-slot memo for the card's derived values. Compose re-evaluates the card
 *  on every keystroke in the composer and every live-session tick; without
 *  this each evaluation re-ran composeBrief + pickMoment (derivePatterns over
 *  every block, name regexes over every task). Keyed on everything the engines
 *  read — recompute only when one of those actually changed (plan risk 11). */
class GatewayMemo<K, V> {
    private var slot: Pair<K, V>? = null
    var computeCount = 0
        private set

    @Synchronized
    fun value(key: K, compute: () -> V): V {
        slot?.let { (k, v) -> if (k == key) return v }
        val v = compute()
        computeCount += 1
        slot = key to v
        return v
    }
}

/** Everything `composeBrief` + `pickMoment` read, as an equatable key. The
 *  MINUTE (not the instant) is in the key: the brief's "N minutes before" and
 *  the 17:30 gates only move once a minute. */
data class GatewayInputs(
    val tasks: List<TaskItem>,
    val blocks: List<CalBlock>,
    val sessions: List<Session>,
    val reasons: List<ReasonLog>,
    val facts: List<ProfileFact>,
    val struggles: List<String>,
    val rituals: RitualPrefs,
    val dismissed: Set<String>,
    val todayIso: String,
    val minute: Long,
) {
    companion object {
        fun minute(nowMs: Long): Long = nowMs / 60_000
    }
}

data class GatewayDerived(val brief: String, val moment: Moment?) {
    companion object {
        val EMPTY = GatewayDerived("", null)
    }
}

/** The brief line: "About N usable minutes before it" must be the gap to the
 *  anchor, capped by usable time — not the day's total. */
fun gatewayBriefLine(tasks: List<TaskItem>, blocks: List<CalBlock>, todayIso: String, nowMs: Long): String {
    val nowMin = Time.hourOf(nowMs) * 60 + Time.minuteOf(nowMs)
    val next = blocks
        .filter { it.date == todayIso && !it.done && !it.skipped && it.startTime.isNotEmpty() }
        .mapNotNull { b ->
            val p = b.startTime.split(":").mapNotNull { it.toIntOrNull() }
            if (p.size == 2) p[0] * 60 + p[1] else null
        }
        .filter { it >= nowMin }
        .minOrNull()
    val usable = usableToday(blocks, todayIso).usableMins
    val before = next?.let { maxOf(0, minOf(usable, it - nowMin)) }
    return composeBrief(tasks, blocks, todayIso, nowMs, before)
}

/** The engine's state from the app's rows — the one place the app types map
 *  onto the engine's collision-free inputs. Only ACTIVE facts feed the engine. */
fun gatewayMomentState(inputs: GatewayInputs, nowMs: Long): MomentState {
    val dismissed = inputs.dismissed
    return MomentState(
        tasks = inputs.tasks, blocks = inputs.blocks, sessions = inputs.sessions, reasons = inputs.reasons,
        facts = inputs.facts.filter { it.active }.map { MomentFact(it.id, it.fact, it.category.raw, it.whenIso) },
        struggles = inputs.struggles, todayIso = inputs.todayIso, now = nowMs,
        isDismissed = { it in dismissed },
    )
}

fun RitualPrefs.toMomentRituals(): MomentRituals = MomentRituals(morning, evening, friday, sunday)

/** The brief + the one moment for [inputs]. Pure; the ViewModel memoises it. */
fun deriveGateway(inputs: GatewayInputs, nowMs: Long): GatewayDerived {
    val brief = gatewayBriefLine(inputs.tasks, inputs.blocks, inputs.todayIso, nowMs)
    // A moment must never take down Today (web: try/catch around pickMoment).
    val moment = runCatching {
        pickMoment(gatewayMomentState(inputs, nowMs), inputs.rituals.toMomentRituals(), toneFromFacts(inputs.facts).toMomentTone())
    }.getOrNull()
    return GatewayDerived(brief, moment)
}

// ── struggles ───────────────────────────────────────────────────────────────

private val CANONICAL_STRUGGLE_NAMES = listOf("Starting", "Sustaining", "Switching", "Stopping", "Recovering")
private val LEGACY_STRUGGLES = mapOf(
    "getting started" to "Starting",
    "switching tasks" to "Switching",
    "distraction" to "Sustaining",     // web: "I drift after a few minutes"
    "time blindness" to "Stopping",    // web: "hyperfocus runs me into the ground"
    "overwhelm" to "Starting",         // web: "tasks feel impossible to begin"
)

/** The onboarding struggles as the engine + the assistant context read them:
 *  Android's picker (and old web rows) saved legacy labels ("Getting started"),
 *  the moments engine + struggleProfile read the canonical five. Order kept
 *  (the first is the declared primary), duplicates dropped, unknowns skipped
 *  (iOS AppModel.canonicalStruggles). */
fun canonicalStruggles(raw: List<String>): List<String> {
    val out = ArrayList<String>()
    for (s in raw) {
        val key = s.trim().lowercase()
        val mapped = CANONICAL_STRUGGLE_NAMES.firstOrNull { it.lowercase() == key } ?: LEGACY_STRUGGLES[key] ?: continue
        if (mapped !in out) out += mapped
    }
    return out
}

// ── composer chips ──────────────────────────────────────────────────────────

/** label = what's drawn (the ✦ is decoration); spoken = the a11y label. */
data class GatewayChip(val label: String, val spoken: String, val message: String)

val GATEWAY_CHIPS: List<GatewayChip> = listOf(
    GatewayChip("✦ Plan my day", "Plan my day", "Plan my day — what should I start with and what order makes sense?"),
    GatewayChip("Brain-dump", "Brain-dump", "I want to brain-dump everything on my mind — ready?"),
    GatewayChip("What’s this week?", "What’s this week?", "What have I got coming up this week?"),
)

const val GATEWAY_PLACEHOLDER = "Ask me anything — or hand me your whole day…"
const val GATEWAY_INTERVIEW_PILL = "✦ Personalise your assistant — two minutes, skip anything"
