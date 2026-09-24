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
// The rule:
//  • the user's message carries the action's verb (delete / remove / cancel /
//    leave …), not negated ("don't delete it" is no request), AND points at
//    THIS thing: its name; a pronoun, "all" or a plural right after the verb
//    ("delete it", "remove them all", "delete my done tasks"); or a pick
//    ("delete the first one") among things the assistant's previous reply
//    named, this one included. When the
//    target has no name to check (cancel_focus: there is only the running
//    session), the verb is enough;
//  • OR the assistant's previous reply ASKED about the action (a question with
//    the verb, naming this thing or "it"/"them"/"all"), and the user's message
//    says yes ("yes", "sure", "go ahead", "do it" — a short answer) or names it
//    ("just Gym").
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

    /** What may follow the verb to point at the thing without naming it. For
     *  leave_list, "leave it" means "keep it" — only the list itself counts. */
    private val POINTER = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:it|that|this|these|those|them|both|all|everything|every|each|tasks|lists|areas|tags|items|ones)\\b")
    private val LEAVE_POINTER = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:list|lists|group|them|both|all)\\b")

    /** "delete the first one", "remove the other": the thing the assistant's
     *  previous reply named, picked without naming it. */
    private val PICK = Regex("^\\s*(?:[\\p{L}']+\\s+){0,2}?(?:one|first|second|third|last|other|former|latter)\\b")

    private val AFFIRM = Regex(
        "^(?:yes|yeah|yep|yup|yea|sure|ok|okay|go ahead|go for it|do it|please do|confirm(?:ed)?|correct|absolutely|" +
            "definitely|of course|affirmative|that'?s right|that'?s fine|y)\\b",
    )

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

    /** "delete it", "remove them all", "delete my done tasks": the verb, then a
     *  pointer within a few words. */
    private fun pointsAfterVerb(tool: String, t: String, pointer: Regex = if (tool == "leave_list") LEAVE_POINTER else POINTER): Boolean =
        verbMatches(tool, t).any { m -> pointer.containsMatchIn(t.substring(m.range.last + 1)) }

    /** A short yes ("yes", "sure, go ahead", "yep do it") — never "ok, add milk to my list". */
    fun affirms(text: String): Boolean {
        val t = norm(text).trimStart('"', '\'', '(', ' ')
        return AFFIRM.containsMatchIn(t) && words(t).size <= 4
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
        // The user asked for it this turn.
        if (asksFor(tool, user) &&
            (target == null || named || pointsAfterVerb(tool, user) || (pointsAfterVerb(tool, user, PICK) && mentions(prev, target)))
        ) return null
        // They said yes to (or named the thing in answer to) the assistant's
        // question proposing it.
        val proposed = prev.contains('?') && asksFor(tool, prev) &&
            (target == null || mentions(prev, target) || pointsAfterVerb(tool, prev))
        if (proposed && (affirms(user) || named)) return null
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
