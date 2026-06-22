package tech.csalliance.unstuck.core.logic

// FocusCopilot — the pure, on-device, ZERO-LLM brain of the "Hands-Free Focus
// Copilot" (Phase 1: spoken progress alerts + hands-free voice replies during a
// focus block). No Android, no network, no assistant client — every function
// here is a deterministic function of (estimate, level, accumulated focus secs,
// what already fired) or (utterance). MUST stay identical across iOS / web /
// Android, so it lives in :core and is unit-tested headless.
//
// Cadence by coaching level (reuses the existing Calm/Balanced/Coach level —
// see app NotificationLevel, mapped to CopilotLevel at the wiring layer):
//   Calm     → AT_TIME only.
//   Balanced → T_MINUS_5 (only if E>10) + AT_TIME.
//   Coach    → HALFWAY (if E>=20) + T_MINUS_5 (if E>10) + AT_TIME
//              + OVERRUN every +5 min while over (capped at 2 OVERRUNs).
//   E<=5     → AT_TIME only (regardless of level).
// Each milestone fires AT MOST ONCE (the caller passes the set already fired).
// Time is ACCUMULATED FOCUS seconds (paused time is excluded by the caller).
// OVERRUN is suppressed once the user has said "keep going" (the no-re-nag flag).

/** Coaching intensity — mirrors the app's NotificationLevel (Calm/Balanced/Coach). */
enum class CopilotLevel { CALM, BALANCED, COACH }

/** A spoken progress checkpoint. Most are questions (open a listen window);
 *  HALFWAY is a statement (speak-only). */
enum class FocusMilestone {
    HALFWAY,   // statement — no listen window
    T_MINUS_5, // question
    AT_TIME,   // question
    OVERRUN;   // question

    /** Whether this milestone poses a question (→ the wiring opens a mic window). */
    val isQuestion: Boolean get() = this != HALFWAY
}

/** A parsed hands-free command (keyword match — NO LLM). */
sealed interface FocusCommand {
    /** Finish the session now ("nice work"). */
    object Stop : FocusCommand
    /** Add [minutes] to the block (clamped [1,120]). */
    data class Extend(val minutes: Int) : FocusCommand
    /** "keep going" / "in the zone" / "not yet" — set the no-re-nag flag. */
    object KeepGoing : FocusCommand
    /** Save a capture with the verbatim [text] (no LLM rewriting). */
    data class Capture(val text: String) : FocusCommand
    /** Unrecognized — do nothing (fall back to the visual buttons). */
    object None : FocusCommand
}

/** The effect a command resolves to, plus the exact spoken acknowledgement. */
sealed interface FocusEffect {
    val ack: String

    /** Extend by [minutes] and recompute overrun; "Added {n} minutes." */
    data class Extend(val minutes: Int) : FocusEffect {
        override val ack: String get() = "Added $minutes ${minutePlural(minutes)}."
    }
    /** Set the no-re-nag flag; "Okay, keep going." */
    object KeepGoing : FocusEffect { override val ack: String get() = "Okay, keep going." }
    /** Finish the session; "Nice work." */
    object Stop : FocusEffect { override val ack: String get() = "Nice work." }
    /** Save a verbatim capture (the wiring attaches the sessionId); "Got it." */
    data class Capture(val text: String) : FocusEffect { override val ack: String get() = "Got it." }
    /** No effect — keep focusing silently. */
    object None : FocusEffect { override val ack: String get() = "" }
}

object FocusCopilot {

    // --- scheduling ---

    /**
     * The single milestone that has just come due, or null. Pure function of:
     *  - [estimateMin] the block estimate E (minutes),
     *  - [level] the coaching level,
     *  - [focusedSec] ACCUMULATED focus seconds (paused time already excluded),
     *  - [alreadyFired] the one-shot milestones already spoken this session
     *    (HALFWAY / T_MINUS_5 / AT_TIME; OVERRUN is tracked by count below),
     *  - [overrunCount] how many OVERRUN prompts have already fired this session,
     *  - [keepGoing] true once the user said "keep going" (suppresses OVERRUN).
     *
     * Returns at most one milestone per call (the earliest unfired due one), so
     * the caller never double-fires. Returns null when nothing new is due.
     */
    fun dueMilestone(
        estimateMin: Int,
        level: CopilotLevel,
        focusedSec: Int,
        alreadyFired: Set<FocusMilestone>,
        overrunCount: Int = 0,
        keepGoing: Boolean = false,
    ): FocusMilestone? {
        val e = estimateMin
        if (e <= 0) return null
        val estSec = e * 60
        // Evaluate in chronological order; return the first DUE + UNFIRED one.

        // HALFWAY — Coach only, and only for blocks of 20 min or more.
        if (level == CopilotLevel.COACH && e >= 20 && e > 5 &&
            FocusMilestone.HALFWAY !in alreadyFired && focusedSec >= estSec / 2
        ) return FocusMilestone.HALFWAY

        // T_MINUS_5 — Balanced + Coach, only if E>10 (and E>5).
        if (level != CopilotLevel.CALM && e > 10 &&
            FocusMilestone.T_MINUS_5 !in alreadyFired && focusedSec >= estSec - 5 * 60
        ) return FocusMilestone.T_MINUS_5

        // AT_TIME — every level (this is the only one for Calm and for E<=5).
        if (FocusMilestone.AT_TIME !in alreadyFired && focusedSec >= estSec)
            return FocusMilestone.AT_TIME

        // OVERRUN — Coach only (and E>5: tiny blocks get AT_TIME only), every
        // +5 min past the estimate, capped at 2, suppressed once the user said
        // "keep going". Tracked by [overrunCount] since a Set<enum> can't hold
        // more than one OVERRUN.
        if (level == CopilotLevel.COACH && e > 5 && !keepGoing && overrunCount < OVERRUN_CAP) {
            val overByMin = ((focusedSec - estSec).coerceAtLeast(0)) / 60
            // The (k+1)-th overrun is due at +5*(k+1) minutes over.
            if (overByMin >= (overrunCount + 1) * 5) return FocusMilestone.OVERRUN
        }
        return null
    }

    const val OVERRUN_CAP = 2

    // --- spoken lines ({n} = whole minutes) ---

    /** The line spoken for [milestone] given the block [estimateMin] and the
     *  ACCUMULATED [focusedSec] (used to compute {n}). */
    fun line(milestone: FocusMilestone, estimateMin: Int, focusedSec: Int): String {
        val estSec = estimateMin * 60
        return when (milestone) {
            FocusMilestone.HALFWAY -> {
                val leftMin = ((estSec - focusedSec).coerceAtLeast(0)) / 60
                "Halfway there — about $leftMin ${minutePlural(leftMin)} left."
            }
            FocusMilestone.T_MINUS_5 -> "Five minutes left. Want to wrap up, or add time?"
            FocusMilestone.AT_TIME -> "That's your block. Add five, stop, or keep going?"
            FocusMilestone.OVERRUN -> {
                val overMin = ((focusedSec - estSec).coerceAtLeast(0)) / 60
                "You're $overMin ${minutePlural(overMin)} over. Stop here, or keep going?"
            }
        }
    }

    // --- command parsing (keyword, case-insensitive, NO LLM) ---

    /**
     * Map a raw spoken [utterance] to a [FocusCommand]. Priority (highest first):
     *   stop > extend(n) > keepGoing > capture > none.
     * Pure string matching only — never calls an LLM or any network.
     */
    fun parseCommand(utterance: String): FocusCommand {
        val raw = utterance.trim()
        if (raw.isEmpty()) return FocusCommand.None
        val u = raw.lowercase()

        // 1) STOP — highest priority so "stop" always wins.
        if (containsAny(u, STOP_WORDS)) return FocusCommand.Stop

        // 2) EXTEND(n) — "add/extend/give me <number>" → clamp [1,120].
        //    "add time" / "more time" → 5. (KeepGoing phrases handled below.)
        parseExtend(u)?.let { return FocusCommand.Extend(it) }

        // 3) KEEP GOING — "keep going" / "in the zone" / "not yet".
        if (containsAny(u, KEEP_GOING_WORDS)) return FocusCommand.KeepGoing

        // 4) CAPTURE — a leading capture verb → save the remainder verbatim.
        captureRemainder(raw)?.let { return if (it.isBlank()) FocusCommand.None else FocusCommand.Capture(it) }

        return FocusCommand.None
    }

    // --- push-to-talk capture (Phase 1.5: pure dictation, NO parseCommand) ---

    /**
     * Turn a raw spoken [transcript] into the body of a VERBATIM capture, or null
     * if there's nothing to save. This is the ENTIRE brain of push-to-talk
     * capture: it trims surrounding whitespace and returns null on blank — it does
     * NOT run [parseCommand] or any keyword matching, so "I should stop
     * procrastinating" is saved word-for-word and is NEVER interpreted as a stop
     * command. Pure, deterministic, on-device — no LLM, no network, no rewriting.
     */
    fun captureFromTranscript(transcript: String): String? =
        transcript.trim().ifBlank { null }

    /** Resolve a [command] into its [FocusEffect] (+ spoken ack). The wiring runs
     *  the effect against the real session; this stays pure. */
    fun effectFor(command: FocusCommand): FocusEffect = when (command) {
        is FocusCommand.Stop -> FocusEffect.Stop
        is FocusCommand.Extend -> FocusEffect.Extend(command.minutes)
        is FocusCommand.KeepGoing -> FocusEffect.KeepGoing
        is FocusCommand.Capture -> FocusEffect.Capture(command.text)
        is FocusCommand.None -> FocusEffect.None
    }

    // --- internals ---

    private val STOP_WORDS = listOf("stop here", "stop", "done", "finish", "end", "that's it", "thats it")
    private val KEEP_GOING_WORDS = listOf("keep going", "in the zone", "not yet")
    private val ADD_TIME_PHRASES = listOf("add time", "more time")
    private val EXTEND_VERBS = listOf("add", "extend", "give me")
    private val CAPTURE_PREFIXES = listOf("capture", "note", "remember", "remind me", "add a task")

    // word → minutes (only the supported extend amounts)
    private val NUMBER_WORDS = mapOf(
        "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20,
        "5" to 5, "10" to 10, "15" to 15, "20" to 20,
    )

    private fun containsAny(u: String, words: List<String>): Boolean =
        words.any { wordPresent(u, it) }

    /** True if [phrase] occurs in [u] on word boundaries (so "stop" doesn't match
     *  "stopwatch", and "end" doesn't match "weekend"). */
    private fun wordPresent(u: String, phrase: String): Boolean {
        val p = phrase.lowercase()
        var from = 0
        while (true) {
            val idx = u.indexOf(p, from)
            if (idx < 0) return false
            val before = if (idx == 0) ' ' else u[idx - 1]
            val afterIdx = idx + p.length
            val after = if (afterIdx >= u.length) ' ' else u[afterIdx]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = idx + 1
        }
    }

    private fun parseExtend(u: String): Int? {
        // "add time" / "more time" → 5 (no explicit number).
        val addTime = ADD_TIME_PHRASES.any { wordPresent(u, it) }
        // Only treat as an extend if an extend verb is present (so "give me 5"
        // counts but a bare "5" alone does not start an extend).
        val hasVerb = EXTEND_VERBS.any { wordPresent(u, it) }
        if (!hasVerb && !addTime) return null
        // Find a supported number anywhere after the verb.
        val n = NUMBER_WORDS.entries.firstOrNull { wordPresent(u, it.key) }?.value
        return when {
            n != null -> n.coerceIn(1, 120)
            addTime -> 5
            else -> null
        }
    }

    private fun captureRemainder(raw: String): String? {
        val u = raw.lowercase()
        // Longest prefix first so "add a task" wins over a bare "add".
        val prefix = CAPTURE_PREFIXES.sortedByDescending { it.length }
            .firstOrNull { u.startsWith(it) } ?: return null
        return raw.substring(prefix.length).trimStart(' ', ',', ':', '-', '.').trim()
    }
}

/** "minute" / "minutes" for an integer count. */
internal fun minutePlural(n: Int): String = if (n == 1) "minute" else "minutes"
