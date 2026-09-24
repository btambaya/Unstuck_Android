package tech.csalliance.unstuck.core.logic

// Confirm-first, enforced in CODE (the registry's `confirm: true` tools:
// delete_task, cancel_focus, delete_list, leave_list, delete_area, delete_tag).
//
// Why: the rule used to live in the prompt only. James's TestFlight thread
// (build 51, 2026-09-13) showed "✓ Deleted "Pack ski gear checklist"" and
// "✓ Deleted "Gym"" under replies to "Show park run on calendar" and "Add
// travel to Skipton tomorrow at 3pm" — the model really called delete_task
// while answering requests that had nothing to do with deleting. Now the text
// harness runs a destructive tool only when the user's LATEST message asked
// for it, or said yes to the assistant's question proposing it; otherwise the
// call is refused (nothing runs) and the model asks instead.
//
// The rule (the same three cases as web confirm-first.ts and iOS
// ConfirmFirst.swift):
//  • the user's message carries the action's verb (delete / remove / cancel /
//    leave …), not negated ("don't delete it" is no request), AND points at
//    THIS thing: its name, or a set right after the verb ("remove them all",
//    "delete my done tasks"). When the target has no name to check
//    (cancel_focus: there is only the running session), the verb is enough;
//  • OR the verb with "it"/"that"/"them" or a pick ("delete the first one")
//    when the assistant's previous reply named this thing — "delete it" never
//    reaches a task nobody mentioned;
//  • OR the assistant's previous reply ASKED about the action — its last
//    question has the verb and names this thing (or "it"/"them" for a thing
//    the reply named, or all of them) — and the user's message says yes
//    ("yes", "sure", "go ahead", "do it" — a short answer) or is little more
//    than its name ("just Gym"). A message opening with no / keep / wait /
//    don't is never that answer ("No, keep Gym").
// A refused call is `error:` — no receipt, and it never disarms the
// fabrication guard.

object ConfirmFirstRules {
    /** The registry's confirm-first tools. :core cannot see the generated
     *  ToolRegistry (it lives in :app); ToolRegistryParityTest pins this copy
     *  to ToolRegistry.CONFIRM_FIRST. */
    val TOOLS: Set<String> = setOf("delete_task", "cancel_focus", "delete_list", "leave_list", "delete_area", "delete_tag")

    private const val DELETE_VERBS =
        "delete[sd]?|deleting|remove[sd]?|removing|erase[sd]?|erasing|bin|binned|trash(?:ed)?|scrap(?:ped)?|drop(?:ped)?|" +
            "ditch(?:ed)?|clear(?:ed)?|cancel(?:led|ed|ling|ing)?|wipe[sd]?|purge[sd]?|get rid of|got rid of|throw away|throw out|toss(?:ed)?"

    // Bare "stop" / "end" are too common ("the end of the day") to count on
    // their own for the one confirm-first tool whose target has no name.
    private const val CANCEL_VERBS =
        "cancel(?:led|ed|ling|ing)?|abandon(?:ed)?|abort(?:ed)?|discard(?:ed)?|scrap(?:ped)?|quit|give up|don'?t log|" +
            "without (?:logging|saving)|(?:stop|end|kill|bin|drop|ditch|forget)(?= (?:it|this|that|the|my|focus|focusing|session|timer)\\b)"

    private const val LEAVE_VERBS =
        "leave|leaving|exit|quit|unsubscribe|remove me|take me off|get me off|drop out|step out|opt out"

    private fun verbs(tool: String): String = when (tool) {
        "cancel_focus" -> CANCEL_VERBS
        "leave_list" -> LEAVE_VERBS
        else -> DELETE_VERBS
    }

    private val verbRes = HashMap<String, Regex>()
    private fun verbRe(tool: String): Regex = synchronized(verbRes) { verbRes.getOrPut(tool) { Regex("\\b(?:${verbs(tool)})\\b") } }

    /** The word the refusal uses for the action. */
    private fun actionWord(tool: String): String = when (tool) {
        "cancel_focus" -> "cancel"
        "leave_list" -> "leave"
        else -> "delete"
    }

    /** "don't delete", "do not remove", "never cancel", "no need to leave" — the
     *  text right before the verb ends in a negation (+ up to two words). */
    private val NEGATION = Regex("\\b(?:don'?t|do not|never|not|no need to|without|stop)\\s+(?:[\\p{L}']+\\s+){0,2}$")

    /** A set right after the verb — the user asked for the lot, whichever
     *  ones: "remove them all", "delete my done tasks", "clear the old ones". */
    private val SET = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:all|everything|every|each|both|tasks|lists|areas|tags|items|ones)\\b")
    private val LEAVE_SET = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:all|both|lists|groups)\\b")

    /** A pointer right after the verb — "delete it", "remove that task" —
     *  which only means THIS thing when the previous reply named it. For
     *  leave_list, "leave it" means "keep it": only the list itself counts. */
    private val REFERENT = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:it|that|this|these|those|them)\\b")
    private val LEAVE_REFERENT = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:list|group|them)\\b")

    /** "delete the first one", "remove the other": the thing the assistant's
     *  previous reply named, picked without naming it. */
    private val PICK = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:one|first|second|third|last|other|former|latter)\\b")

    private val AFFIRM = Regex(
        "^(?:yes|yeah|yep|yup|yea|sure|ok|okay|go ahead|go for it|do it|please do|confirm(?:ed)?|correct|absolutely|" +
            "definitely|of course|affirmative|that'?s right|that'?s fine|y)\\b",
    )

    /** An answer that opens by declining is never a yes — "No, keep Gym". */
    private val DECLINE = Regex("^(?:no|nope|nah|not|don'?t|do not|keep|wait|hold on|hang on|never ?mind|leave it|stop)\\b")

    /** A "no" anywhere in a short answer — "yes, don't" is no yes. */
    private val NEGATES = Regex("\\b(?:no|nope|nah|not|don'?t|do not|never|wait|keep)\\b")

    private val STOPWORDS = setOf("the", "and", "for", "with", "my", "your", "our", "list", "task", "area", "tag")

    private fun norm(s: String): String = s.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trim()

    private fun words(s: String): List<String> = norm(s).split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** Plural / possessive folded: "runs", "run's" → "run". */
    private fun stem(w: String): String = w.removeSuffix("'s").let { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }

    /** The name's words that identify it (a name of only short or common words keeps them all). */
    private fun nameWords(name: String): Set<String> {
        val all = words(name)
        val strong = all.filter { it.length >= 3 && it !in STOPWORDS }
        return strong.ifEmpty { all }.map(::stem).toSet()
    }

    /** Does [text] name [target] — any of its identifying words, plural or possessive alike? */
    fun mentions(text: String, target: String): Boolean {
        val want = nameWords(target)
        if (want.isEmpty()) return false
        return words(text).any { stem(it) in want }
    }

    /** The action's verb in [text], not negated just before it. */
    fun asksFor(tool: String, text: String): Boolean = verbMatches(tool, norm(text)).isNotEmpty()

    private fun verbMatches(tool: String, t: String): List<MatchResult> =
        verbRe(tool).findAll(t).filter { m -> !NEGATION.containsMatchIn(t.substring(0, m.range.first)) }.toList()

    /** The verb, then [pointer] within a few words ("delete it", "remove them all"). */
    private fun pointsAfterVerb(tool: String, t: String, pointer: Regex): Boolean =
        verbMatches(tool, t).any { m -> pointer.containsMatchIn(t.substring(m.range.last + 1)) }

    private fun setRe(tool: String) = if (tool == "leave_list") LEAVE_SET else SET
    private fun referentRe(tool: String) = if (tool == "leave_list") LEAVE_REFERENT else REFERENT

    /** A short yes ("yes", "sure, go ahead", "yep do it") — never "ok, add milk
     *  to my list", never "yes, don't". */
    fun affirms(text: String): Boolean {
        val t = norm(text).trimStart('"', '\'', '(', ' ')
        return AFFIRM.containsMatchIn(t) && words(t).size <= 4 && !NEGATES.containsMatchIn(t)
    }

    /** What the assistant's reply last ASKED: its last question sentence
     *  ("Gym has no slots left. Delete it?" → "Delete it?"), null when it asked
     *  nothing. A report before an unrelated question ("I deleted X. Want me to
     *  move Gym?") proposes no delete. A '.' inside a time ("8.30") doesn't end
     *  a sentence. */
    fun lastQuestion(text: String): String? {
        val q = text.lastIndexOf('?')
        if (q < 0) return null
        var start = 0
        for (i in q - 1 downTo 0) {
            val ch = text[i]
            if (ch == '\n' || ((ch == '.' || ch == '!' || ch == '?') && text.getOrNull(i + 1)?.isWhitespace() == true)) { start = i + 1; break }
        }
        return text.substring(start, q + 1).trim()
    }

    /**
     * Null when [tool] may run: not a confirm-first tool, or the user asked for
     * it (see the file comment). Otherwise the `error:` the model reads instead
     * of a result — nothing ran. [target] is the thing's display name (the
     * task, list, area or tag), null when unknown or when there is only one
     * (cancel_focus). [previousAssistant] is the assistant's last visible reply
     * before [userText].
     */
    fun refusal(tool: String, target: String?, userText: String, previousAssistant: String?): String? {
        if (tool !in TOOLS) return null
        val user = norm(userText)
        val prev = previousAssistant?.let(::norm).orEmpty()
        val named = target != null && mentions(user, target)
        val prevNamed = target != null && mentions(prev, target)
        // The user asked for it this turn: by name or as a set — or with "it" /
        // a pick when the previous reply named this thing.
        if (asksFor(tool, user) &&
            (target == null || named || pointsAfterVerb(tool, user, setRe(tool)) ||
                (prevNamed && (pointsAfterVerb(tool, user, referentRe(tool)) || pointsAfterVerb(tool, user, PICK))))
        ) return null
        // They said yes to (or answered with the name of) the thing the
        // assistant's last question proposed doing this to.
        val question = previousAssistant?.let(::lastQuestion)?.let(::norm)
        val proposed = question != null && asksFor(tool, question) &&
            (target == null || mentions(question, target) || pointsAfterVerb(tool, question, setRe(tool)) ||
                (prevNamed && pointsAfterVerb(tool, question, referentRe(tool))))
        val answersWithName = named && words(user).size <= words(target!!).size + 2
        if (proposed && !DECLINE.containsMatchIn(user) && (affirms(user) || answersWithName)) return null
        val what = when {
            tool == "cancel_focus" -> "the focus session${if (target != null) " on \"$target\"" else ""}"
            target != null -> "\"$target\""
            else -> "that"
        }
        val ask = when (tool) {
            "cancel_focus" -> "(\"Cancel this session without logging it?\" — to keep the time, finish_focus ends it instead)"
            else -> "(\"${actionWord(tool).replaceFirstChar { it.uppercase() }} ${if (target != null) "\"$target\"" else "it"}?\")"
        }
        return "error: not confirmed — the user's latest message didn't ask to ${actionWord(tool)} $what, so nothing was changed. " +
            "Don't call $tool unless they ask for it: if you think they want it, ask ONE short question $ask and call it only after they say yes."
    }
}
