package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// PA moments — the deterministic engine behind the AI gateway's "one calm
// thing at a time" surface. Zero-LLM: every moment is composed from real data
// so it renders instantly and never hallucinates. Register mirrors Brief.kt —
// a good PA's one-two sentences, never guilt-tripping. Port of
// lib/assistant/moments.ts (+ UnstuckCore/Logic/Moments.swift).
//
// Exactly ONE moment surfaces at a time. Ids are stable per firing (e.g.
// 'evening-sweep:2026-08-29') so a dismissal recorded by the caller sticks
// for that firing window. Every moment's LAST action is a dismiss.
//
// Determinism: no randomness, no wall-clock reads — the clock is state.now
// (epoch ms, read in the system zone like every other :core helper) and the
// calendar date is state.todayIso, both supplied by the caller. Date math is
// local-calendar safe (java.time.LocalDate: parse field-by-field, never a UTC
// round-trip).
//
// Input types. This module is built in parallel with the profile-facts layer
// (ProfileFact/RitualPrefs) and the insights layer (Tone), so it declares its
// OWN minimal, collision-free inputs: MomentTone, MomentFact, MomentRituals.
// The surface maps the app types onto them (a one-line `when` / constructor).

/** Tone dispatch — gentle = soft suggestion, honest = direct, minimal = shortest. Wire = the web's 'gentle'|'honest'|'minimal'. */
enum class MomentTone(val wire: String) {
    GENTLE("gentle"), HONEST("honest"), MINIMAL("minimal");

    companion object {
        fun fromWire(s: String?): MomentTone = entries.firstOrNull { it.wire == s } ?: GENTLE
    }
}

/**
 * The slice of a profile fact the engine reads: its id (moment ids embed it),
 * the text, the category wire value ('person' | 'rhythm' | 'constraint' |
 * 'preference' | 'context') and the optional date the fact refers to.
 */
data class MomentFact(
    val id: String,
    val fact: String,
    val category: String = "context",
    /** 'YYYY-MM-DD' (a longer ISO stamp is accepted; only the date prefix is read). */
    val whenIso: String? = null,
)

/**
 * Which recurring PA moments run — itself a personalisation choice. Morning +
 * evening default ON, the weekly ones opt-in (the web's `unstuck-pa-rituals`).
 */
data class MomentRituals(
    val morning: Boolean = true,
    val evening: Boolean = true,
    val friday: Boolean = false,
    val sunday: Boolean = false,
) {
    companion object {
        val DEFAULTS = MomentRituals()
    }
}

data class MomentState(
    val tasks: List<TaskItem> = emptyList(),
    val blocks: List<CalBlock> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val reasons: List<ReasonLog> = emptyList(),
    val facts: List<MomentFact> = emptyList(),
    /** Self-reported friction points from onboarding, e.g. ['Starting','Switching']. */
    val struggles: List<String> = emptyList(),
    /** 'YYYY-MM-DD' (local). */
    val todayIso: String,
    /** Wall clock, epoch ms — only its local hours/minutes drive the time gates. */
    val now: Long,
    /** Caller persists dismissals keyed by Moment.id. */
    val isDismissed: (String) -> Boolean = { false },
)

enum class MomentKind(val wire: String) { RITUAL("ritual"), NOTICE("notice"), RELATIONSHIP("relationship") }

/**
 * What tapping an action does — the surface runs it (carry / schedule /
 * create / hand a message to the chat / dismiss).
 */
sealed class MomentRun {
    data class CarryTasks(val taskIds: List<String>) : MomentRun()
    data class Schedule(val taskId: String, val date: String, val time: String? = null) : MomentRun()
    data class CreateTask(val name: String, val estimateMin: Int? = null) : MomentRun()
    data class Chat(val message: String) : MomentRun()
    data object Dismiss : MomentRun()
}

data class MomentAction(val label: String, val run: MomentRun)

data class Moment(
    val id: String,
    val kind: MomentKind,
    val priority: Int,
    /** Same-priority tiebreak weight (the web keeps it on the candidate; exposed for the surface/tests). */
    val salience: Int,
    val text: String,
    val actions: List<MomentAction>,
)

// ── Local-date + phrase plumbing ────────────────────────────────────────────

private val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

private fun addDaysIsoLocal(iso: String, n: Int): String = LocalDate.parse(iso).plusDays(n.toLong()).toString()

/** Whole days from [fromIso] to [toIso] (local calendar dates). */
private fun daysUntil(fromIso: String, toIso: String): Int =
    ChronoUnit.DAYS.between(LocalDate.parse(fromIso), LocalDate.parse(toIso)).toInt()

/** "Sat 12 Sept" — how a PA says a near date out loud. */
private fun fmtDate(isoDate: String): String {
    val d = LocalDate.parse(isoDate)
    return "${DAY_SHORT[d.dowJs()]} ${d.dayOfMonth} ${MONTH_SHORT_GB[d.monthValue - 1]}"
}

private fun minutesOfDay(now: Long): Int = Time.hourOf(now) * 60 + Time.minuteOf(now)

/** 'HH:MM' → minutes since midnight (a missing/invalid part reads 0). */
private fun hmToMinutes(hm: String): Int {
    val parts = hm.split(":")
    val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    return h * 60 + m
}

/** Local calendar date of an ISO timestamp (instant, offset or zone-less local stamp), or null when unparseable. */
private fun dateOfStamp(stamp: String?): String? {
    if (stamp.isNullOrEmpty()) return null
    val ms = Time.parseMillis(stamp) ?: return null
    return Clock.dateIso(ms)
}

/** Leading name of a fact, profile.ts convention: "Maleek — son, 9" → "Maleek". */
private fun leadName(fact: String): String {
    val seps = setOf('—', '–', '-')
    return fact.takeWhile { !it.isWhitespace() && it !in seps }.trim()
}

private val ISO_DATE_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}")
private val BIRTHDAY = Regex("birthday|turning\\s+\\d", RegexOption.IGNORE_CASE)
private val GIFT = Regex("gift|birthday|present", RegexOption.IGNORE_CASE)

/**
 * Per-name whole-word regexes (`\bMaleek\b`), compiled once per distinct
 * name — pickMoment runs on every gateway body evaluation. Bounded: a
 * profile has a handful of people, but a runaway caller must not grow this
 * without limit.
 */
private object NameRegexCache {
    private val cache = HashMap<String, Regex>()

    fun regex(name: String): Regex = synchronized(cache) {
        cache[name]?.let { return it }
        if (cache.size >= 256) cache.clear()
        val re = Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
        cache[name] = re
        re
    }
}

/** Whole-word, case-insensitive name match ("Maleek" ≠ "Maleeka"). */
private fun containsName(text: String, name: String): Boolean = NameRegexCache.regex(name).containsMatchIn(text)

// Filler words dropped when lifting the activity out of a block name:
// "Take Maleek to rehearsals" → "rehearsals".
private val FILLER = setOf(
    "to", "the", "a", "an", "for", "at", "with", "in", "on", "of", "and",
    "from", "his", "her", "their", "my", "our", "up",
)

/** The words after [name] in a block title, minus filler; null if nothing survives. */
private fun activityAfterName(taskName: String, name: String): String? {
    val words = taskName.trim().split(Regex("\\s+"))
    // `[^\p{L}\p{N}’'-]` stripped from each word.
    fun strip(w: String): String = w.filter { it.isLetterOrDigit() || it == '’' || it == '\'' || it == '-' }
    val i = words.indexOfFirst { strip(it).equals(name, ignoreCase = true) }
    if (i < 0) return null
    val rest = words.drop(i + 1).map(::strip).filter { it.isNotEmpty() && it.lowercase() !in FILLER }
    return if (rest.isEmpty()) null else rest.joinToString(" ")
}

private fun byTone(tone: MomentTone, gentle: String, honest: String, minimal: String): String = when (tone) {
    MomentTone.GENTLE -> gentle
    MomentTone.HONEST -> honest
    MomentTone.MINIMAL -> minimal
}

private fun dismiss(label: String = "Not now"): MomentAction = MomentAction(label, MomentRun.Dismiss)

/** JS number → string for a value already rounded to one decimal ("4.5", "5"). */
private fun fmtTenths(tenths: Int): String =
    if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"

// A candidate is a moment plus a same-priority tiebreak weight (Moment.salience).
private typealias Candidate = Moment

// ════════════════════════════════════════════════════════════════════════════
// RELATIONSHIP (priority 30)
// ════════════════════════════════════════════════════════════════════════════

/**
 * dates-that-matter — a fact carrying a whenIso 3–14 days ahead (2 days out is
 * too late to be useful ahead-of-time nudging territory… it fires at 3, and 15
 * is too early). One id per fact+date, so a dismissal covers the whole window.
 */
private fun datesThatMatter(state: MomentState, tone: MomentTone): Candidate? {
    class Hit(val fact: MomentFact, val whenIso: String, val du: Int)
    val hits = ArrayList<Hit>()
    for (f in state.facts) {
        val whenIso = f.whenIso ?: continue
        if (whenIso.length < 10 || !ISO_DATE_PREFIX.containsMatchIn(whenIso)) continue
        val w = whenIso.substring(0, 10)
        // The prefix regex says "shaped like a date"; it does NOT say the date
        // EXISTS. "2026-02-30" passes it, and java.time's parse throws where the
        // web's date math would silently roll over — an uncaught throw here takes
        // the whole gateway card (and the Today screen composing it) down. Facts
        // reach this store from the web and iOS, which guard with the same regex
        // only, so skip the impossible ones rather than trusting the writer.
        if (parseYmdOrNull(w) == null) continue
        val du = daysUntil(state.todayIso, w)
        if (du < 3 || du > 14) continue
        hits.add(Hit(f, w, du))
    }
    if (hits.isEmpty()) return null
    // Soonest date first; fact id breaks ties for full determinism.
    hits.sortWith(compareBy<Hit> { it.du }.thenBy { it.fact.id })
    val hit = hits[0]
    val fact = hit.fact
    val whenIso = hit.whenIso
    val du = hit.du

    val name = leadName(fact.fact)
    val isBirthday = BIRTHDAY.containsMatchIn(fact.fact)

    if (isBirthday) {
        // Already sorted? An open task naming both the person and the gift/birthday
        // means the PA has nothing to add — stay quiet.
        val covered = state.tasks.any { !it.done && containsName(it.name, name) && GIFT.containsMatchIn(it.name) }
        if (covered) return null
        return Moment(
            id = "dates-that-matter:${fact.id}:$whenIso",
            kind = MomentKind.RELATIONSHIP,
            priority = 30,
            salience = 100 + (14 - du),
            text = byTone(
                tone,
                gentle = "$name’s birthday is ${fmtDate(whenIso)} — gift sorted?",
                honest = "Straight up: $name’s birthday is ${fmtDate(whenIso)} and there’s no gift task yet. Want one?",
                minimal = "$name’s birthday — ${fmtDate(whenIso)}.",
            ),
            actions = listOf(
                MomentAction("Sort the gift", MomentRun.CreateTask(name = "Get $name’s birthday gift")),
                dismiss(),
            ),
        )
    }

    return Moment(
        id = "dates-that-matter:${fact.id}:$whenIso",
        kind = MomentKind.RELATIONSHIP,
        priority = 30,
        salience = 100 + (14 - du),
        text = byTone(
            tone,
            gentle = "${fmtDate(whenIso)} — ${fact.fact}. Want a task for it?",
            honest = "Straight up: ${fmtDate(whenIso)} is getting close — ${fact.fact}. Prepare something?",
            minimal = "${fmtDate(whenIso)} — ${fact.fact}.",
        ),
        actions = listOf(
            MomentAction("Make it a task", MomentRun.CreateTask(name = "Prepare: ${fact.fact}".take(80))),
            dismiss(),
        ),
    )
}

/**
 * how-did-it-go — yesterday held a block whose name carries a person we know
 * ("Take Maleek to rehearsals" + fact "Maleek — son…"). One id per block, and
 * the window is only ever yesterday, so it naturally fires once.
 */
private fun howDidItGo(state: MomentState, tone: MomentTone): Candidate? {
    val yesterday = addDaysIsoLocal(state.todayIso, -1)
    val people = state.facts
        .filter { it.category == "person" }
        .map { leadName(it.fact) }
        .filter { it.length >= 2 }
    if (people.isEmpty()) return null

    val hits = state.blocks
        .filter { b -> b.date == yesterday && !b.skipped && people.any { containsName(b.taskName, it) } }
        .sortedWith(compareBy<CalBlock> { it.startTime }.thenBy { it.id })
    val block = hits.firstOrNull() ?: return null

    val name = people.first { containsName(block.taskName, it) }
    val activity = activityAfterName(block.taskName, name)

    return Moment(
        id = "how-did-it-go:${block.id}",
        kind = MomentKind.RELATIONSHIP,
        priority = 30,
        salience = 200, // one-day window — outranks a birthday still ≥3 days out
        text = if (activity != null) {
            byTone(
                tone,
                gentle = "How did $name’s $activity go yesterday?",
                honest = "Straight up: how did $name’s $activity go yesterday?",
                minimal = "$name’s $activity — how’d it go?",
            )
        } else {
            byTone(
                tone,
                gentle = "How did ‘${block.taskName}’ go yesterday?",
                honest = "Straight up: how did ‘${block.taskName}’ go yesterday?",
                minimal = "‘${block.taskName}’ — how’d it go?",
            )
        },
        actions = listOf(
            MomentAction("Talk about it", MomentRun.Chat("Tell me how it went: ${block.taskName}")),
            dismiss(),
        ),
    )
}

// ════════════════════════════════════════════════════════════════════════════
// RITUALS (priority 20 — pref-gated, one per day each)
// ════════════════════════════════════════════════════════════════════════════

/**
 * first-touch — before noon, with ≥1 open task. The anchor is picked exactly
 * like Brief.kt (first live timed block still ahead, else the day's first
 * block); with nothing on the calendar, the shortest unscheduled task is the
 * lightest way in.
 */
private fun firstTouch(state: MomentState, tone: MomentTone): Candidate? {
    // 05:00–11:59 only: a night owl opening the app at 1am is still on
    // YESTERDAY's evening, not tomorrow's morning (flow review, 2026-08-30).
    val hour = Time.hourOf(state.now)
    if (hour >= 12 || hour < 5) return null
    val open = state.tasks.filter { !it.done && it.recurrence == null }
    if (open.isEmpty()) return null

    val todayBlocks = liveBlocksToday(state.blocks, state.todayIso)

    var text: String
    if (todayBlocks.isNotEmpty()) {
        val nowHm = hmOfMillis(state.now)
        val anchor = anchorBlock(todayBlocks, nowHm)!!
        // Prefer the task's current name (blocks denormalize it and can go stale).
        val name = state.tasks.firstOrNull { it.id == anchor.taskId }?.name ?: anchor.taskName
        val trimmed = anchor.startTime.trim()
        val label = if (trimmed.isEmpty()) "‘$name’" else "‘$name’ at $trimmed"
        text = byTone(
            tone,
            gentle = "Morning. $label is the anchor — want the day built around it?",
            honest = "Straight up: $label is the day’s anchor. Build around it?",
            minimal = "$label. Build around it?",
        )
    } else {
        // Shortest unscheduled open task; any open task as a last resort.
        val scheduledIds = state.blocks.mapNotNull { it.taskId }.filter { it.isNotEmpty() }.toHashSet()
        val pool = open.filter { it.later != true && it.id !in scheduledIds }
        val from = if (pool.isEmpty()) open else pool
        var lightest = from[0]
        for (t in from) if (t.estimateMin < lightest.estimateMin) lightest = t
        text = byTone(
            tone,
            gentle = "Morning. ‘${lightest.name}’ (~${lightest.estimateMin} min) is a light way in — build the day from there?",
            honest = "Straight up: nothing’s timed yet. ‘${lightest.name}’ (~${lightest.estimateMin} min) is the lightest way in.",
            minimal = "‘${lightest.name}’ first? ~${lightest.estimateMin} min.",
        )
    }

    if ("Starting" in state.struggles) text += " I’ll give you the first ten minutes."

    return Moment(
        id = "first-touch:${state.todayIso}",
        kind = MomentKind.RITUAL,
        priority = 20,
        salience = 100,
        text = text,
        actions = listOf(
            MomentAction("Plan my day", MomentRun.Chat("Plan my day around the anchor")),
            dismiss(),
        ),
    )
}

/**
 * evening-sweep — from 17:30, offer to carry today's misses forward. Only
 * timed blocks that have already ENDED count as "didn't happen" (a 20:00
 * block at 18:00 is still tonight's plan, and an untimed block can still
 * happen any time). If a leftover has slipped ≥3 times, the sweep names it
 * and adds the shrink path.
 */
private fun eveningSweep(state: MomentState, tone: MomentTone): Candidate? {
    val nowMin = minutesOfDay(state.now)
    if (nowMin < 17 * 60 + 30) return null

    val taskById = HashMap<String, TaskItem>()
    for (t in state.tasks) taskById.putIfAbsent(t.id, t)
    val leftovers = ArrayList<TaskItem>()
    val seen = HashSet<String>()
    val missed = state.blocks
        .filter { b ->
            b.date == state.todayIso && !b.done && !b.skipped &&
                !b.taskId.isNullOrEmpty() && b.startTime.trim().isNotEmpty()
        }
        .sortedBy { it.startTime }
    for (b in missed) {
        if (hmToMinutes(b.startTime) + b.durationMinutes > nowMin) continue // still ahead tonight
        val task = taskById[b.taskId!!] ?: continue
        if (task.done || task.id in seen) continue
        seen.add(task.id)
        leftovers.add(task)
    }
    if (leftovers.isEmpty()) return null

    val n = leftovers.size
    val taskIds = leftovers.map { it.id }
    val carry = MomentAction("Carry $n to tomorrow", MomentRun.CarryTasks(taskIds))
    val leave = dismiss(if (n == 1) "Leave it" else "Leave them")

    // The honest variant fires on a chronic slipper regardless of tone level —
    // pretending 'Tax form' just "didn't happen today" would be the real unkindness.
    var slipper: TaskItem? = null
    for (t in leftovers) {
        val m = t.moveCount ?: 0
        if (m >= 3 && m > (slipper?.moveCount ?: 0)) slipper = t
    }
    if (slipper != null) {
        val m = slipper.moveCount ?: 0
        return Moment(
            id = "evening-sweep:${state.todayIso}",
            kind = MomentKind.RITUAL,
            priority = 20,
            salience = 400,
            text = byTone(
                tone,
                gentle = "‘${slipper.name}’ has slipped $m times — want to carry it, shrink it, or let it go?",
                honest = "Straight up: ‘${slipper.name}’ has slipped $m times — carry, shrink, or let it go?",
                minimal = "‘${slipper.name}’: slipped $m×. Carry, shrink, or drop?",
            ),
            actions = listOf(
                carry,
                MomentAction("Shrink it", MomentRun.Chat("Help me shrink ‘${slipper.name}’ into a first step")),
                leave,
            ),
        )
    }

    return Moment(
        id = "evening-sweep:${state.todayIso}",
        kind = MomentKind.RITUAL,
        priority = 20,
        salience = 400,
        text = byTone(
            tone,
            gentle = if (n == 1) {
                "One thing didn’t happen today — carry it to tomorrow?"
            } else {
                "${countWord(n)} things didn’t happen today — carry them to tomorrow?"
            },
            honest = "Straight up: $n ${if (n == 1) "thing" else "things"} didn’t happen today. Carry ${if (n == 1) "it" else "them"} to tomorrow?",
            minimal = "$n left over — carry to tomorrow?",
        ),
        actions = listOf(carry, leave),
    )
}

/** friday-review — Friday from 15:00, once ≥5 focus sessions landed this week. */
private fun fridayReview(state: MomentState, tone: MomentTone): Candidate? {
    val today = LocalDate.parse(state.todayIso)
    if (today.dowJs() != 5 || Time.hourOf(state.now) < 15) return null

    val weekStartIso = weekStartDate(today).toString()
    val thisWeek = countableSessions(state.sessions).filter { s ->
        val d = dateOfStamp(s.completedAt) ?: return@filter false
        d >= weekStartIso && d <= state.todayIso
    }
    if (thisWeek.size < 5) return null

    var best = thisWeek[0]
    for (s in thisWeek) if (s.actualSec > best.actualSec) best = s
    val bestAt = Time.parseMillis(best.completedAt) ?: return null
    val day = DAY_NAMES_FULL[Time.dayOfWeekJs(bestAt)]
    val h = Time.hourOf(bestAt)
    val part = if (h < 12) "morning" else if (h < 18) "afternoon" else "evening"
    val n = thisWeek.size

    return Moment(
        id = "friday-review:${state.todayIso}",
        kind = MomentKind.RITUAL,
        priority = 20,
        salience = 300,
        text = byTone(
            tone,
            gentle = "Week in three minutes? $n focus blocks, best run $day $part.",
            honest = "Straight up: $n focus blocks this week, best run $day $part. Worth three minutes?",
            minimal = "$n focus blocks. Review the week?",
        ),
        actions = listOf(
            MomentAction("Review the week", MomentRun.Chat("Let’s do the week review")),
            dismiss(),
        ),
    )
}

/**
 * sunday-runway — Sunday from 16:00, look at next week. An overloaded weekday
 * (>4h scheduled Mon–Fri) wins; otherwise ≥3 unscheduled tasks earn a gentle
 * "rough it out" offer.
 */
private fun sundayRunway(state: MomentState, tone: MomentTone): Candidate? {
    val today = LocalDate.parse(state.todayIso)
    val todayDow = today.dowJs()
    if (todayDow != 0 || Time.hourOf(state.now) < 16) return null

    val id = "sunday-runway:${state.todayIso}"

    // Next week's weekdays: Mon (+1) … Fri (+5).
    var heaviestDow = -1
    var heaviestLoad = 0
    for (offset in 1..5) {
        val date = today.plusDays(offset.toLong()).toString()
        val load = state.blocks
            .filter { it.date == date && !it.done && !it.skipped }
            .sumOf { it.durationMinutes }
        if (load > 240 && load > heaviestLoad) {
            heaviestDow = (offset % 7 + todayDow) % 7
            heaviestLoad = load
        }
    }
    if (heaviestDow >= 0) {
        val day = DAY_NAMES_FULL[heaviestDow]
        val hrs = fmtTenths(jsRound(heaviestLoad / 60.0 * 10))
        return Moment(
            id = id, kind = MomentKind.RITUAL, priority = 20, salience = 200,
            text = byTone(
                tone,
                gentle = "$day looks wall-to-wall — want to thin it out?",
                honest = "Straight up: $day has ${hrs}h scheduled. Thin it out?",
                minimal = "$day: ${hrs}h. Thin it?",
            ),
            actions = listOf(
                MomentAction("Thin out $day", MomentRun.Chat("Help me thin out $day")),
                dismiss(),
            ),
        )
    }

    val scheduledIds = state.blocks.mapNotNull { it.taskId }.filter { it.isNotEmpty() }.toHashSet()
    val unscheduled = state.tasks.filter {
        !it.done && it.later != true && it.recurrence == null && it.id !in scheduledIds
    }
    if (unscheduled.size < 3) return null
    val n = unscheduled.size
    return Moment(
        id = id, kind = MomentKind.RITUAL, priority = 20, salience = 200,
        text = byTone(
            tone,
            gentle = "Rough out next week? $n tasks are still unscheduled.",
            honest = "Straight up: $n tasks have no slot next week. Rough it out?",
            minimal = "$n unscheduled. Rough out next week?",
        ),
        actions = listOf(
            MomentAction("Rough out next week", MomentRun.Chat("Let’s rough out next week")),
            dismiss(),
        ),
    )
}

// ════════════════════════════════════════════════════════════════════════════
// NOTICES (priority 10 — at most one surfaces; the strongest wins)
// ════════════════════════════════════════════════════════════════════════════

/**
 * slip-radar — an open task rescheduled ≥3 times. The highest non-dismissed
 * count wins (dismissal is filtered INSIDE the rule so the next-worst slipper
 * can take the slot). The id carries the count, so a dismissal sticks until
 * the task slips again.
 */
private fun slipRadar(state: MomentState, tone: MomentTone): Candidate? {
    val slippers = state.tasks
        .filter { !it.done && it.later != true && (it.moveCount ?: 0) >= 3 }
        .filter { !state.isDismissed("slip-radar:${it.id}:${it.moveCount ?: 0}") }
        .sortedWith(compareByDescending<TaskItem> { it.moveCount ?: 0 }.thenBy { it.id })
    val t = slippers.firstOrNull() ?: return null

    val n = t.moveCount ?: 0
    val starting = "Starting" in state.struggles

    return Moment(
        id = "slip-radar:${t.id}:$n",
        kind = MomentKind.NOTICE,
        priority = 10,
        salience = 300 + n,
        text = if (starting) {
            byTone(
                tone,
                gentle = "‘${t.name}’ has moved $n times — starting is the hard part, not the task. Want a 10-minute first step?",
                honest = "Straight up: ‘${t.name}’ has moved $n times. Starting is the hard part, not the task — take a 10-minute first step?",
                minimal = "‘${t.name}’: moved $n×. Starting’s the hard part — take 10 minutes?",
            )
        } else {
            byTone(
                tone,
                gentle = "‘${t.name}’ has moved $n times. Shrink it to a 10-minute step, park it, or let it go?",
                honest = "Straight up: ‘${t.name}’ has moved $n times. Shrink it to a 10-minute step, park it, or let it go?",
                minimal = "‘${t.name}’: moved $n×. Shrink, park, or drop?",
            )
        },
        actions = listOf(
            MomentAction("Shrink it", MomentRun.Chat("Help me shrink ‘${t.name}’ into a first step")),
            dismiss(),
        ),
    )
}

/** quiet-win — a chronic slipper completed yesterday deserves one calm nod. */
private fun quietWin(state: MomentState, tone: MomentTone): Candidate? {
    val yesterday = addDaysIsoLocal(state.todayIso, -1)
    val wins = state.tasks
        .filter { it.done && (it.moveCount ?: 0) >= 3 && dateOfStamp(it.completedAt) == yesterday }
        .sortedWith(compareByDescending<TaskItem> { it.moveCount ?: 0 }.thenBy { it.id })
    val t = wins.firstOrNull() ?: return null

    val n = t.moveCount ?: 0
    return Moment(
        id = "quiet-win:${t.id}:$yesterday",
        kind = MomentKind.NOTICE,
        priority = 10,
        salience = 200 + n,
        text = byTone(
            tone,
            gentle = "‘${t.name}’ finally happened after $n dodges. That’s the hard kind of done.",
            honest = "Straight up: ‘${t.name}’ took $n dodges and still got done. That’s the hard kind of done.",
            minimal = "‘${t.name}’ — done after $n dodges.",
        ),
        actions = listOf(dismiss("Noted")),
    )
}

/** habit-gap — the calendar shows a habit (Patterns.kt) whose next slot is empty. */
private fun habitGap(state: MomentState, tone: MomentTone): Candidate? {
    val gaps = patternGaps(
        derivePatterns(state.tasks, state.blocks, state.todayIso),
        state.blocks,
        state.todayIso,
    )
    if (gaps.isEmpty()) return null
    val soonest = gaps.sortedWith(
        compareBy<Gap> { it.dueDate }.thenByDescending { it.weeksSeen }.thenBy { it.taskId },
    )
    val gap = soonest[0]
    val day = DAY_NAMES_FULL[gap.dow]

    return Moment(
        id = "habit-gap:${gap.taskId}:${gap.dueDate}",
        kind = MomentKind.NOTICE,
        priority = 10,
        salience = 100 + gap.weeksSeen,
        text = byTone(
            tone,
            gentle = probeQuestion(gap),
            honest = "Straight up: no ‘${gap.taskName}’ on the calendar for $day. Book it?",
            minimal = "‘${gap.taskName}’ $day — book it?",
        ),
        actions = listOf(
            MomentAction(
                label = if (gap.time != null) "Book $day ${gap.time}" else "Book $day",
                run = MomentRun.Schedule(taskId = gap.taskId, date = gap.dueDate, time = gap.time),
            ),
            dismiss(),
        ),
    )
}

// ════════════════════════════════════════════════════════════════════════════
// Selection
// ════════════════════════════════════════════════════════════════════════════

/**
 * The one moment to surface right now, or null for a quiet gateway.
 * Relationship beats ritual beats notice; within a tier the salience weights
 * above break the tie, then the id — fully deterministic for a given state.
 */
fun pickMoment(state: MomentState, prefs: MomentRituals, tone: MomentTone): Moment? {
    val candidates = ArrayList<Candidate>()
    fun consider(c: Candidate?) {
        if (c != null && !state.isDismissed(c.id)) candidates.add(c)
    }

    // Relationship
    consider(howDidItGo(state, tone))
    consider(datesThatMatter(state, tone))

    // Rituals (pref-gated)
    if (prefs.morning) consider(firstTouch(state, tone))
    if (prefs.evening) consider(eveningSweep(state, tone))
    if (prefs.friday) consider(fridayReview(state, tone))
    if (prefs.sunday) consider(sundayRunway(state, tone))

    // Notices — only the strongest one is ever in the running.
    val notices = listOfNotNull(slipRadar(state, tone), quietWin(state, tone), habitGap(state, tone))
        .filter { !state.isDismissed(it.id) }
        .sortedWith(compareByDescending<Candidate> { it.salience }.thenBy { it.id })
    notices.firstOrNull()?.let { candidates.add(it) }

    candidates.sortWith(
        compareByDescending<Candidate> { it.priority }.thenByDescending { it.salience }.thenBy { it.id },
    )
    return candidates.firstOrNull()
}
