package tech.csalliance.unstuck.core.logic

import java.time.LocalDate
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode

// Port of lib/assistant/suggestions.ts. Dynamic assistant suggestions — every
// chip derives from the user's ACTUAL data (real task names, real list names)
// and only appears when applicable (Ahmad, 2026-08-02: "the suggestion should
// be dynamic based on user's data", and "I'd like to action the suggestions
// without typing … refine the tasks without thinking too much about what would
// help me work on them").
//
// Chip taps send `message` through the normal guardrailed agent path — no
// special-cased local execution, no new server surface. Anything the agent
// changes comes back as an undoable receipt. Pure + unit-tested: the copy and
// the predicates must stay identical to web, so the chips read the same on
// every platform.

/** One chip: what it says, and what it actually asks the agent. */
data class AssistantSuggestion(
    /** Chip label (short, may truncate the entity name). */
    val label: String,
    /** The message actually sent to the agent on tap. */
    val message: String,
)

data class SuggestionGroups(
    val gettingStarted: List<AssistantSuggestion> = emptyList(),
    /** Plan/schedule work without typing — the "do it for me" group. */
    val planAndSchedule: List<AssistantSuggestion> = emptyList(),
    /** Sharpen what's already there (first steps, breakdowns, tidying). */
    val refine: List<AssistantSuggestion> = emptyList(),
) {
    val isEmpty: Boolean get() = gettingStarted.isEmpty() && planAndSchedule.isEmpty() && refine.isEmpty()
}

private fun shorten(s: String, max: Int = 24): String =
    if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

/** Open, non-deferred, non-template tasks — the assistant's working set. */
private fun openTasks(tasks: List<TaskItem>): List<TaskItem> =
    tasks.filter { !it.done && it.recurrence == null }

/** Saturday/Sunday dates (YYYY-MM-DD) for the coming weekend, from today.
 *  On a Saturday the weekend IS today. Null when [todayIso] can't be parsed —
 *  the chip is then skipped rather than sending the agent invented dates. */
internal fun nextWeekend(todayIso: String): Pair<String, String>? {
    val base = runCatching { LocalDate.parse(todayIso) }.getOrNull() ?: return null
    val jsDow = base.dayOfWeek.value % 7          // JS getDay(): 0=Sun … 6=Sat
    val offset = if (jsDow == 6) 0L else ((6 - jsDow + 7) % 7).toLong()
    val sat = base.plusDays(offset)
    val sun = sat.plusDays(1)
    return IsoDate.format(sat) to IsoDate.format(sun)
}

fun buildSuggestions(
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    collections: List<ItemCollection>,
    todayIso: String,
    /** The phone's 12/24-hour setting: a chip's message lands in the thread as
     *  the user's own words, so its clock time reads the phone's way ("nothing
     *  before 10:00" / "10am" — the web's copy on a 12-hour phone). */
    clock: ClockMode = ClockMode.H12,
): SuggestionGroups {
    val open = openTasks(tasks)
    val gettingStarted = mutableListOf<AssistantSuggestion>()
    val planAndSchedule = mutableListOf<AssistantSuggestion>()
    val refine = mutableListOf<AssistantSuggestion>()

    val liveBlocks = blocks.filter { !it.done && !it.skipped }
    val todayBlocks = liveBlocks.filter { it.date == todayIso }
    val scheduledIds = liveBlocks.filter { it.date >= todayIso }.mapNotNull { it.taskId }.toSet()
    val unscheduled = open.filter { it.later != true && it.id !in scheduledIds }
    val laterPile = open.filter { it.later == true }
    val noFirstStep = open.filter { it.later != true && it.firstPhysicalAction.isNullOrBlank() }

    // ---- Getting started -------------------------------------------------
    if (open.isNotEmpty()) {
        gettingStarted += AssistantSuggestion("What should I work on next?", "What should I work on next?")
    }
    if (todayBlocks.size >= 3 || open.size >= 6) {
        gettingStarted += AssistantSuggestion("I’m overwhelmed", "I’m overwhelmed.")
    }
    if (todayBlocks.isNotEmpty()) {
        gettingStarted += AssistantSuggestion("What’s realistic today?", "What’s realistic for me today?")
    }

    // ---- Plan & schedule (tap → the agent actually schedules) -------------
    if (unscheduled.isNotEmpty()) {
        val n = unscheduled.size
        planAndSchedule += if (n == 1) {
            AssistantSuggestion(
                "Find time for “${shorten(unscheduled[0].name, 18)}”",
                "Find a realistic slot for \"${unscheduled[0].name}\" in the next few days and schedule it.",
            )
        } else {
            AssistantSuggestion(
                "Schedule my $n unscheduled tasks",
                "I have $n unscheduled tasks. Spread them across realistic slots over the next few days " +
                    "and schedule them — keep my existing blocks and don't overload any one day.",
            )
        }
    }
    if (todayBlocks.isNotEmpty()) {
        planAndSchedule += AssistantSuggestion(
            "Move today’s leftovers to tomorrow",
            "Anything still unfinished on today’s plan — reschedule it to sensible times tomorrow.",
        )
    }
    // Weekend planning: only when the light/personal stuff exists to move.
    val weekendable = open.filter {
        it.later != true && it.estimateMin <= 45 &&
            (it.lifeArea == "Home" || it.lifeArea == "Personal" || it.lifeArea == "Health")
    }
    if (weekendable.size >= 2) {
        nextWeekend(todayIso)?.let { (sat, sun) ->
            planAndSchedule += AssistantSuggestion(
                "Plan a quiet weekend",
                "Schedule my lighter personal and home tasks across $sat and $sun, spaced out with " +
                    "breathing room — nothing before ${ClockFormat.compactHour(10, clock)}, and leave the rest of the weekend free.",
            )
        }
    }
    if (todayBlocks.isEmpty() && open.isNotEmpty()) {
        planAndSchedule += AssistantSuggestion(
            "Block out my day",
            "Build me a realistic plan for today from my open tasks and schedule the blocks.",
        )
    }

    // ---- Refine (sharpen what exists) ------------------------------------
    val chunky = noFirstStep.filter { it.estimateMin >= 45 }.sortedByDescending { it.estimateMin }.firstOrNull()
    if (chunky != null) {
        refine += AssistantSuggestion(
            "Break down “${shorten(chunky.name)}”",
            "Break down \"${chunky.name}\" — give it a first physical action and split it into steps.",
        )
    }
    if (noFirstStep.size >= 2) {
        val n = noFirstStep.size
        refine += AssistantSuggestion(
            "Add first steps to $n tasks",
            "$n of my tasks have no first physical action. Give each one a concrete, physical first " +
                "step — something I could literally start in the next minute.",
        )
    }
    if (laterPile.size >= 2) {
        val n = laterPile.size
        refine += AssistantSuggestion(
            "Tidy my Later pile ($n)",
            "I have $n tasks parked in Later. Look through them and tell me which are actually worth " +
                "bringing back this week — then bring those back.",
        )
    }
    val openIds = open.map { it.id }.toSet()
    val slipped = open.any { (it.moveCount ?: 0) >= 2 } ||
        blocks.any { it.date < todayIso && !it.done && !it.skipped && it.taskId != null && it.taskId in openIds }
    if (slipped) {
        refine += AssistantSuggestion("What keeps slipping?", "What keeps slipping, and what should I do about it?")
    }

    // Lists: keep the classic add-to-groceries affordance.
    val lists = collections.filter { it.archived != true }
    val groceries = lists.firstOrNull { GROCERY_RE.containsMatchIn(it.name) }
    if (groceries != null) {
        refine += AssistantSuggestion(
            "Add to ${shorten(groceries.name, 16)}",
            "Add an item to my \"${groceries.name}\" list — ask me what to add.",
        )
    } else if (lists.isNotEmpty()) {
        refine += AssistantSuggestion(
            "Add to “${shorten(lists[0].name, 16)}”",
            "I want to add an item to my \"${lists[0].name}\" list.",
        )
    }

    return SuggestionGroups(gettingStarted, planAndSchedule, refine)
}

private val GROCERY_RE = Regex("grocer|shopping", RegexOption.IGNORE_CASE)
