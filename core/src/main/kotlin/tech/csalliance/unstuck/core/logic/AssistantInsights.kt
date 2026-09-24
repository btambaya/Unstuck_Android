package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.time.DAY_MS
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.ZoneId

// Grounded assistant insights — pure, zero-LLM derivations from the user's
// REAL history (focus sessions, reason logs, interview facts). Each helper
// returns plain strings/numbers the prompt builder can drop in verbatim, so
// the model opens with something true about THIS user instead of generic
// coaching. Port of lib/assistant/insights.ts (+ AssistantInsights.swift).
//
// Date math follows the Patterns.kt conventions: local-calendar getters for
// anything user-facing (hours, short dates), never a UTC round-trip for
// display. Timestamps go through Time.parseMillis (ISO instant, offset,
// zone-less local stamp, or a bare date → local midnight).

/** Nudge tone. Wire = the web's 'gentle' | 'honest' | 'minimal'. */
enum class Tone(val wire: String) {
    GENTLE("gentle"), HONEST("honest"), MINIMAL("minimal");

    /** The moments engine's own tone type (Moments.kt declares it to stay collision-free). */
    fun toMomentTone(): MomentTone = when (this) {
        GENTLE -> MomentTone.GENTLE
        HONEST -> MomentTone.HONEST
        MINIMAL -> MomentTone.MINIMAL
    }

    companion object {
        fun fromWire(s: String?): Tone = entries.firstOrNull { it.wire == s } ?: GENTLE
    }
}

data class GoldenHours(
    /** Local START hours of the band, contiguous, 2–3 entries, e.g. [9, 10]. */
    val hours: List<Int>,
    /** Human phrasing, e.g. 'mornings around 9–11'. */
    val label: String,
    /** Fraction (0–1) of focused seconds whose session started inside the band. */
    val share: Double,
    /** e.g. 'Deep focus lands best around 9–11am (from 34 real sessions)'. */
    val factText: String,
)

data class StruggleProfile(
    /** First declared struggle, normalized to canonical casing when it matches. */
    val primary: String?,
    /** Recent reason logs corroborate the declared struggle (≥5 in 30 days). */
    val confirmed: Boolean,
    /** One warm context line for the model; null when nothing was declared. */
    val line: String?,
    /** Starting is among the declared struggles → lead with a tiny first step. */
    val offerFirstStep: Boolean,
)

/** A (category, fact) pair for [toneFromFacts] callers that don't hold a full [ProfileFact]. */
data class ToneFact(val category: String, val fact: String)

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun matches(pattern: String, text: String): Boolean =
    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)

// ---- goldenHours

/** Time-of-day word for a band, keyed by its FIRST hour. */
private fun daypart(startHour: Int): String = when (startHour) {
    in 5..7 -> "early mornings"
    in 8..11 -> "mornings"
    in 12..14 -> "early afternoons"
    in 15..16 -> "late afternoons"
    in 17..20 -> "evenings"
    else -> "late nights"
}

/** 9 → 9, 13 → 1, 0/12/24 → 12. */
private fun hourNum(h: Int): Int = if (h % 12 == 0) 12 else h % 12

/** 24 is midnight — 'am'. */
private fun meridiem(h: Int): String = if (h < 12 || h == 24) "am" else "pm"

/** '9–11am', '1–4pm', '11am–1pm'. `end` is exclusive, may be 24. */
private fun fmtRange(start: Int, end: Int): String =
    if (meridiem(start) == meridiem(end)) "${hourNum(start)}–${hourNum(end)}${meridiem(end)}"
    else "${hourNum(start)}${meridiem(start)}–${hourNum(end)}${meridiem(end)}"

/**
 * The user's proven focus window, from the last 60 days of sessions. Each
 * session's START hour (completedAt minus actualSec, local time) is weighted
 * by actualSec — one long deep-work block outvotes a scatter of two-minute
 * dabs. Requires ≥10 qualifying sessions, else null (don't invent a pattern
 * from noise).
 *
 * Band selection: the best contiguous 2-hour window (never wrapping
 * midnight; ties prefer the window whose first hour is heavier, then the
 * earlier start), extended to 3 hours when an adjacent hour genuinely
 * carries weight (≥ a quarter of the 2-hour band).
 */
fun goldenHours(sessions: List<Session>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): GoldenHours? {
    val windowStart = nowMs - 60 * DAY_MS
    val bins = IntArray(24)
    var total = 0
    var count = 0
    // The shared D1 filter: accidental starts never count toward the 10, and a
    // forgotten timer can't outvote real sessions (it weighs its COUNTED
    // length). Its start hour comes from its REAL length — the same start the
    // Insights heatmap uses — not the clamped one, which put a timer forgotten
    // overnight at 4am, hours after it really began.
    for (s in sessions) {
        val counted = countedSec(s)
        if (counted <= 0) continue
        val endMs = Time.parseMillis(s.completedAt) ?: continue
        if (endMs < windowStart || endMs > nowMs) continue
        val startHour = Instant.ofEpochMilli(endMs - s.actualSec * 1000L).atZone(zone).hour
        bins[startHour] += counted
        total += counted
        count += 1
    }
    if (count < 10 || total <= 0) return null

    var start = 0
    var sum = -1
    for (h in 0..22) {
        val w = bins[h] + bins[h + 1]
        if (w > sum || (w == sum && bins[h] > bins[start])) { start = h; sum = w }
    }

    var hours = listOf(start, start + 1)
    val left = if (start - 1 >= 0) bins[start - 1] else 0
    val right = if (start + 2 <= 23) bins[start + 2] else 0
    val heavier = maxOf(left, right)
    if (heavier > 0 && heavier.toDouble() >= sum.toDouble() / 4) {
        hours = if (left >= right) listOf(start - 1, start, start + 1) else listOf(start, start + 1, start + 2)
    }

    val bandSum = hours.sumOf { bins[it] }
    val first = hours[0]
    val end = hours[hours.size - 1] + 1   // exclusive — [9,10] reads 9–11
    return GoldenHours(
        hours = hours,
        label = "${daypart(first)} around $first–$end",
        share = bandSum.toDouble() / total.toDouble(),
        factText = "Deep focus lands best around ${fmtRange(first, end)} (from $count real sessions)",
    )
}

// ---- struggleProfile

private val CANONICAL_STRUGGLES = listOf("Starting", "Sustaining", "Switching", "Stopping", "Recovering")

private val STRUGGLE_LINES = mapOf(
    "Starting" to "Their hard part is Starting — offer a tiny first step before anything else.",
    "Sustaining" to "Their hard part is Sustaining — keep the session small and check in before the energy fades.",
    "Switching" to "Their hard part is Switching — help them come back to one thing instead of adding another.",
    "Stopping" to "Their hard part is Stopping — give clear permission to wrap up and call it done.",
    "Recovering" to "Their hard part is Recovering — make restarting feel tiny, with zero guilt about the gap.",
)

/** Does one reason log corroborate the given struggle? Deliberately simple
 *  keyword/action heuristics — evidence, not diagnosis: many switch/distraction
 *  logs look like Switching; long pause durations look like Sustaining. */
private fun corroborates(struggle: String, log: ReasonLog): Boolean = when (struggle) {
    "Starting" -> matches("start|begin|procrastinat|put(ting)?\\s+(it\\s+)?off|avoid|dread|blank", log.reason)
    "Sustaining" -> (log.action == ReasonAction.PAUSE && (log.durationSec ?: 0) >= 300) ||
        matches("tired|drain|energy|steam|fatigue|fad(ed|ing)|lost focus|can'?t focus", log.reason)
    "Switching" -> log.action == ReasonAction.SWITCH ||
        matches("distract|switch|rabbit\\s*hole|jump|shiny|another (task|thing|idea)", log.reason)
    "Stopping" -> matches("stop|kept going|one more|overr[au]n|ran (way )?over|too long|hyperfocus", log.reason)
    "Recovering" -> matches("resum|recover|restart|re-?engag|get(ting)? back|com(e|ing) back|warm(ing)? up", log.reason)
    else -> false
}

/** The user's declared friction point, cross-checked against what their last
 *  30 days of reason logs actually show. `confirmed` needs ≥5 corroborating
 *  logs — a single bad Tuesday isn't a pattern. `nowMs` defaults to the wall
 *  clock (the web reads `Date.now()`). */
fun struggleProfile(struggles: List<String>, reasons: List<ReasonLog>, nowMs: Long = System.currentTimeMillis()): StruggleProfile {
    val declared = struggles.firstOrNull()?.trim() ?: ""
    val offerFirstStep = struggles.any { it.trim().lowercase() == "starting" }
    if (declared.isEmpty()) return StruggleProfile(primary = null, confirmed = false, line = null, offerFirstStep = offerFirstStep)

    val primary = CANONICAL_STRUGGLES.firstOrNull { it.lowercase() == declared.lowercase() } ?: declared
    val cutoff = nowMs - 30 * DAY_MS
    val corroborating = reasons.count { r ->
        val at = Time.parseMillis(r.at) ?: return@count false
        at >= cutoff && corroborates(primary, r)
    }
    return StruggleProfile(
        primary = primary,
        confirmed = corroborating >= 5,
        line = STRUGGLE_LINES[primary] ?: "Their hard part is $primary — meet them there before anything else.",
        offerFirstStep = offerFirstStep,
    )
}

// ---- toneFromFacts

/** Nudge tone from the interview's style-preference fact ('Prefers gentle
 *  nudges — suggest, never push' / 'Wants to be kept honest — direct nudges
 *  are welcome' / 'Minimal nudging — only speak up when it really matters').
 *  Only nudge-flavored facts are considered so an unrelated fact mentioning
 *  'direct' can't hijack the tone. Default: gentle — never harsher than asked. */
@JvmName("toneFromToneFacts")
fun toneFromFacts(facts: List<ToneFact>): Tone {
    for (f in facts) {
        if (!matches("nudg", f.fact) && !matches("style|tone|nudge|prefer", f.category)) continue
        val text = f.fact.lowercase()
        if (text.contains("gentle")) return Tone.GENTLE
        if (text.contains("honest") || text.contains("direct")) return Tone.HONEST
        if (text.contains("minimal") || text.contains("barely")) return Tone.MINIMAL
    }
    return Tone.GENTLE
}

fun toneFromFacts(facts: List<ProfileFact>): Tone =
    toneFromFacts(facts.map { ToneFact(it.category.raw, it.fact) })

// ---- quietWinLine

/** One line celebrating a much-rescheduled task finally getting done —
 *  acknowledging the dodges without shame. Null under 3 moves: an ordinary
 *  completion doesn't need a backstory. */
fun quietWinLine(taskName: String, moveCount: Int, tone: Tone): String? {
    if (moveCount < 3) return null
    return when (tone) {
        Tone.HONEST -> "That’s “$taskName” done after $moveCount dodges. The hard kind of done."
        Tone.MINIMAL -> "“$taskName” — done, after $moveCount tries."
        Tone.GENTLE -> "“$taskName” finally happened — it dodged you $moveCount times, and you got it anyway."
    }
}

// ---- factCitation

/** '“Mornings are the good hours” (you told me 12 Aug)'. Date-only strings
 *  parse as LOCAL midnight (never UTC, which shifts a day west of Greenwich);
 *  full timestamps render via local getters. */
fun factCitation(fact: String, createdAt: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val ms = Time.parseMillis(createdAt) ?: return "“$fact”"
    val d = Instant.ofEpochMilli(ms).atZone(zone)
    return "“$fact” (you told me ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]})"
}

fun factCitation(f: ProfileFact): String = factCitation(f.fact, f.createdAt)
