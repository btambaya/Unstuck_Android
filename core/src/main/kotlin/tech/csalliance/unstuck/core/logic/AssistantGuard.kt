package tech.csalliance.unstuck.core.logic

// Fabrication guard — the claim detector + the self-correction stripper the
// harness runs on every no-tool reply. Port of `looksLikeActionClaim`,
// `refersToEarlierTurn` and `stripSelfCorrection` from lib/assistant/receipts.ts
// (the 2026-09-05 sentence-aware revision, which UnstuckCore/Logic/
// AssistantGuard.swift mirrors), regex for regex, so the web battery's
// verdicts hold on Android. Pure. java.util.regex has the same `\b`,
// lookahead and lookbehind semantics as JS for these patterns.

object AssistantGuard {

    private fun re(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // ---- stripSelfCorrection

    // "Sorry to hear…" is sympathy, not a self-correction — leave it. Scoped to
    // APOLOGY forms that reference the check ("sorry", "my mistake", "I
    // said/claimed…", "I didn't actually…", "correction"). A bare "Actually, I
    // scheduled it for Friday…" or "Let me add that now" is a truthful leading
    // sentence after the tool ran (harness audit, 2026-09-05).
    private val APOLOGY_LEAD = re(
        "^(?:(?:oh|ah|oops|hmm|right|wait|okay|ok)[,!.]?\\s*)?" +
            "(?:sorry(?! to hear| that you| you| about your)|apolog\\w*|my (?:mistake|bad|apologies)" +
            "|i (?:said|claimed|mentioned|stated|didn'?t actually|hadn'?t actually|mistakenly)" +
            "|correction|that was (?:wrong|a mistake|an error)|to correct)" +
            "[^.!?\\n]*[.!?\\n]+\\s*",
    )

    // The same apology forms as a whole TRAILING sentence ("Sorry for the
    // confusion earlier.") — the user never saw a claim to be confused by.
    private val APOLOGY_TAIL = re(
        "(?:^|(?<=[.!?\\n]\\s))(?:(?:oh|ah|oops|hmm|right|okay|ok)[,!.]?\\s*)?" +
            "(?:sorry(?! to hear| that you| you| about your)|apolog\\w*|my (?:mistake|bad|apologies)" +
            "|i (?:said|claimed|mentioned|stated|didn'?t actually|hadn'?t actually|mistakenly)" +
            "|correction|that was (?:wrong|a mistake|an error))" +
            "[^.!?\\n]*[.!?]*\\s*$",
    )

    // Bridging sentences that only make sense right AFTER an apology ("Let me
    // add it now.", "Actually, I hadn't added it yet!", "Adding it now."). They
    // are stripped only in that position — standing alone at the head of a
    // reply they're the model's real answer.
    private val CONTINUATION = re(
        "^(?:actually,?\\s+i|let me (?:fix|correct|add|do|create|schedule|save|move|redo) (?:that|it|this)\\b[^.!?\\n]*now" +
            "|(?:adding|doing|creating|scheduling|saving|moving) (?:it|that|this) now)[^.!?\\n]*[.!?\\n]+\\s*",
    )

    /** After a fabrication-guard bounce the model tends to ANSWER THE CHECK
     *  ("Sorry — I said I added it but I didn't; adding it now") even though the
     *  user never saw the hidden claim. When the tool really ran on the retry,
     *  strip that self-correction so the user just gets the answer. Pure;
     *  never returns an empty string (an apology-only turn is still the reply). */
    fun stripSelfCorrection(text: String): String {
        val original = text.trim()
        var out = original
        var apologised = false
        for (i in 0 until 4) {
            if (APOLOGY_LEAD.containsMatchIn(out)) {
                out = APOLOGY_LEAD.replaceFirst(out, "").trim(); apologised = true; continue
            }
            if (apologised && CONTINUATION.containsMatchIn(out)) {
                out = CONTINUATION.replaceFirst(out, "").trim(); continue
            }
            break
        }
        if (out.isEmpty()) return original
        for (i in 0 until 2) {
            if (!APOLOGY_TAIL.containsMatchIn(out)) break
            val next = APOLOGY_TAIL.replaceFirst(out, "").trim()
            if (next.isEmpty()) break // never strip the whole reply
            out = next
        }
        return out
    }

    // ---- looksLikeActionClaim

    // Verbs a completed-action claim is built from. Kept wide on purpose: once
    // openers vary ("Booked.", "Skipped gym today.") the guard must still see
    // them — an unmatched verb is an unbounced lie (harness audit, 2026-09-05).
    private const val CLAIM_VERBS =
        "added|created|scheduled|rescheduled|moved|shifted|pushed|postponed|completed|deleted|removed|saved|updated|blocked" +
            "|booked|skipped|reopened|ticked|unticked|renamed|unscheduled|carried|paused|resumed|extended|cancelled|canceled" +
            "|forgot|forgotten|remembered|promoted|started|set|turned|noted|archived|unarchived|resolved|captured|shared|unshared"

    // The leads that open honest sentences ("Set aside 20 minutes for it?",
    // "Shared tasks show up under…", "Turned out…", "Started already?").
    private val LEAD_VERBS: String = CLAIM_VERBS.split("|")
        .filter { it !in setOf("set", "turned", "shared", "started") }
        .joinToString("|")

    private val EARLIER_TURN = re(
        "\\b(?:earlier|previously|already|last time|a (?:moment|minute|while) ago|yesterday" +
            "|(?:as|like) I (?:said|mentioned|noted|told you)|when you asked" +
            "|in (?:my|the) (?:last|previous) (?:message|reply|turn))\\b",
    )

    /** A claim that talks about an EARLIER turn ("I moved it earlier", "as I
     *  said", "already added yesterday") is not a claim about THIS turn — the
     *  harness can only vouch for this turn's tool calls, and bouncing a
     *  truthful recap invited the model to redo the action (a duplicate task,
     *  harness audit 2026-09-05). Pure, per sentence. NOT "before" / "this
     *  morning": "moved it before your meeting" is a this-turn claim. */
    fun refersToEarlierTurn(sentence: String): Boolean = EARLIER_TURN.containsMatchIn(sentence)

    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+|\\n+")

    /** Split on sentence ends, keeping sentences non-empty. */
    private fun sentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    private val CLAIM_PATTERNS: List<Regex> = listOf(
        re("^(done|all set|sorted|booked|blocked)\\b"),
        // "Set to 240 minutes per day — done." / "Reminders on, done." (settings lies, 2026-09-02)
        re("(?:—|–|-|,|;)\\s*(?:all )?done[.!]?\\s*$"),
        re("^(?:set|turned|switched|changed)\\b.*(?:\\bdone\\b|✓)"),
        re("\\bI(?:['’]| ha)ve (?:$CLAIM_VERBS)\\b"),
        re("\\b(?:$CLAIM_VERBS) [\"“'‘]"),
        // Passive perfect: "the task has been created/moved…"
        re("\\b(?:has|have) been (?:$CLAIM_VERBS)\\b"),
        // Sentence leads with the verb: "Created the task for you." / "Skipped gym today."
        re("^(?:$LEAD_VERBS)\\b"),
        // "I (just) created/moved the/your/that task…"
        re("\\bI (?:just )?(?:$CLAIM_VERBS) (?:the|your|that|a|an|it|them|this|those|these)\\b"),
        // Memory promises with nothing behind them: "I'll make a note of it",
        // "noted", "I'll remember that" — remembering IS save_profile_fact.
        re("\\bI(?:['’]ll| will)? ?(?:take|make) a? ?note\\b"),
        re("\\bI(?:['’]ll| will) (?:remember|keep that in mind|note that)\\b"),
        // Compliance promises that require persistence: "I'll skip/stop using
        // the name" — unsaved, that's forgotten by the next session.
        re("\\bI(?:['’]ll| will) (?:skip|stop|avoid|drop|leave out|not (?:use|say|mention))\\b"),
        re("^noted\\b"),
    )

    private fun sentenceClaimsAction(c: String): Boolean = CLAIM_PATTERNS.any { it.containsMatchIn(c) }

    /** Does a no-tool-call reply read like a claimed COMPLETED action or a
     *  memory promise? ("Done — added 'X'", "the task has been created",
     *  "I'll make a note of that"). Used by BOTH the text loop and the voice
     *  session guard. Deliberately careful: honest answers ABOUT existing state
     *  ("your dentist slot is on Friday") must not trip it, and neither must a
     *  truthful reference to a PREVIOUS turn's action ("I moved it earlier") —
     *  only a sentence about THIS turn counts. */
    fun looksLikeActionClaim(content: String?): Boolean {
        val whole = (content ?: "").trim()
        if (whole.isEmpty()) return false
        return sentences(whole).any { !refersToEarlierTurn(it) && sentenceClaimsAction(it) }
    }
}
