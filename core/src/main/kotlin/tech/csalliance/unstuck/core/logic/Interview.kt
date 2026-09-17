package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.ProfileFactCategory

// The get-to-know-you interview — the assistant's FIRST contact (Android port
// of components/assistant/interview.tsx via iOS App/Features/Interview.swift).
// Scripted and ZERO-token: chip answers and free text write profile facts
// directly (source INTERVIEW); the LLM is never involved. One question at a
// time, every question skippable, "I'm done" always visible — the documented
// ADHD dropout point is a long setup wizard, so this must never feel like one.
// The SEVEN questions are the web's, in the web's order with the web's copy,
// so a fact reads the same whichever device wrote it — and "done" is per
// PERSON, not per device (user_preferences.assistant_interview_done_at,
// migration 052, re-applied on every pull — see InterviewFlag).
//
// This file is the PURE half: the script, the people-splitting, the step
// arithmetic and every user-facing string. The side effects (saving a fact,
// persisting the step, marking done) live in the app's InterviewFlow, which
// drives these rules against a host interface so they stay unit-testable.

/** One tap-answer for a question. `fact == null` saves nothing ("It varies"). */
data class InterviewChip(val label: String, val fact: String?)

data class InterviewQuestion(
    val key: String,
    val category: ProfileFactCategory,
    val question: String,
    val chips: List<InterviewChip>,
    /** Prefix stitched onto a free-text answer ("Never schedule: …"). */
    val freePrefix: String? = null,
    val allowFree: Boolean = false,
    /** Free text is a comma-separated list of NAMES — each becomes its own
     *  person fact ("Maleek", "Sam" → two facts), so relationship moments can
     *  match one name per fact (moments `leadName`). A descriptor ("Maleek —
     *  son, 9") is ONE fact — see [InterviewScript.splitPeople]. */
    val splitNames: Boolean = false,
)

/** The script — the web's SEVEN questions (components/assistant/interview.tsx)
 *  in the web's order: rhythm → work → people → fixed points → commitments →
 *  never-schedule → nudge style, then the rituals picker as the final step.
 *  Keys, copy, chips, categories and free-text prefixes are the web's
 *  verbatim, so a fact reads the same whichever device wrote it (a five-
 *  question iOS variant once made the two interviews visibly different —
 *  prod tester, 2026-09-05). The one mobile extra is `splitNames` on people. */
val INTERVIEW_QUESTIONS: List<InterviewQuestion> = listOf(
    InterviewQuestion(
        key = "rhythm", category = ProfileFactCategory.RHYTHM,
        question = "When’s your head clearest?",
        chips = listOf(
            InterviewChip("Morning", "Mornings are the good hours — schedule the hard things early"),
            InterviewChip("Afternoon", "Afternoons are the good hours"),
            InterviewChip("Evening", "Evenings are the good hours — slow starter"),
            InterviewChip("It varies", null),
        ),
    ),
    InterviewQuestion(
        key = "work", category = ProfileFactCategory.CONTEXT,   // web parity — constraint here duplicated the fact across devices
        question = "What do your work days look like?",
        chips = listOf(
            InterviewChip("9–5 weekdays", "Works roughly 9–5 on weekdays"),
            InterviewChip("Shifts", "Works shifts — hours change week to week"),
            InterviewChip("Flexible / freelance", "Flexible schedule — sets their own hours"),
            InterviewChip("Studying", "Studying — timetable over office hours"),
        ),
        freePrefix = "Work", allowFree = true,
    ),
    InterviewQuestion(
        key = "people", category = ProfileFactCategory.PERSON,
        question = "Anyone whose schedule shapes yours — kids, a partner, someone you care for? Names help.",
        chips = listOf(InterviewChip("No one right now", null)),
        allowFree = true, splitNames = true,
    ),
    InterviewQuestion(
        key = "fixed", category = ProfileFactCategory.CONSTRAINT,   // the web files fixed points as a constraint
        question = "Fixed points in the week I should plan around? School runs, prayers, classes…",
        chips = listOf(InterviewChip("None", null)),
        allowFree = true,
    ),
    InterviewQuestion(
        key = "commitments", category = ProfileFactCategory.CONTEXT,
        question = "Regular commitments — gym, rehearsals, clubs, volunteering?",
        chips = listOf(InterviewChip("Not really", null)),
        allowFree = true,
    ),
    InterviewQuestion(
        key = "nogo", category = ProfileFactCategory.CONSTRAINT,
        question = "When should I never schedule anything?",
        chips = listOf(
            InterviewChip("Before 9am", "Never schedule anything before 9am"),
            InterviewChip("After 9pm", "Never schedule anything after 9pm"),
            InterviewChip("Weekends", "Keep weekends free — never schedule work there"),
            InterviewChip("No hard limits", null),
        ),
        freePrefix = "Never schedule", allowFree = true,
    ),
    InterviewQuestion(
        key = "nudge", category = ProfileFactCategory.PREFERENCE,
        question = "Last one — how should I nudge you?",
        chips = listOf(
            InterviewChip("Gently", "Prefers gentle nudges — suggest, never push"),
            InterviewChip("Keep me honest", "Wants to be kept honest — direct nudges are welcome"),
            InterviewChip("Barely at all", "Minimal nudging — only speak up when it really matters"),
        ),
    ),
)

/** Every user-facing string of the interview, verbatim from the web
 *  (interview.tsx) / iOS (Interview.swift) so the three platforms read alike. */
object InterviewCopy {
    /** The agent's FIRST words to a new user, in ONE register, the way a person
     *  opens: a hello, why it's asking, and that everything is skippable. */
    fun greeting(firstName: String?): String {
        val name = firstName?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
        return "Hey$name. A few quick questions so I can plan around your actual life — skip any you like."
    }

    /** The small-print disclosure under the greeting (iOS Interview.swift). */
    const val DISCLOSURE =
        "I’ll remember what you tell me; it stays yours — see What Unstuck knows in Settings to view or delete any of it. Facts are shared with our AI provider (which doesn’t train on them) so I can help."

    const val EYEBROW_PREFIX = "GETTING TO KNOW YOU · "
    const val EYEBROW_LAST = "LAST ONE"
    const val PANEL_LABEL = "Getting to know you"

    const val IM_DONE = "I’m done"
    const val IM_DONE_HINT = "Finishes the interview; it won’t ask again"
    const val HIDE_FOR_NOW = "Hide for now"
    const val HIDE_FOR_NOW_HINT = "Hides the questions; resume any time from the card"
    const val SKIP = "Skip"
    const val SKIP_QUESTION_LABEL = "Skip this question"
    const val SAVE = "Save"
    const val FREE_PLACEHOLDER = "…or type it"
    const val FREE_PLACEHOLDER_NAMES = "…or type names, comma-separated"
    const val FREE_LABEL = "Type an answer"
    const val NOTED_PREFIX = "✓ Noted: "
    const val SAVE_FAILED = "Couldn’t save that — try again"

    const val PICKER_QUESTION = "Which moments should I run for you? All optional, all changeable in Settings."
    const val THATS_ME_SET_UP = "That’s me set up"
}

/** Pure step / answer rules of the interview machine (iOS `InterviewMachine`
 *  minus its side effects). `step` in 0 until [questionCount] is a question;
 *  `== questionCount` is the rituals picker — the terminal step. */
object InterviewScript {
    val questionCount: Int get() = INTERVIEW_QUESTIONS.size

    /** The step to resume at: a persisted value in 0..count, else 0 (restart). */
    fun resumeStep(saved: Int?, count: Int = questionCount): Int =
        if (saved != null && saved in 0..count) saved else 0

    fun isPicker(step: Int, count: Int = questionCount): Boolean = step >= count

    /** The current question, or null on the picker. */
    fun current(step: Int, questions: List<InterviewQuestion> = INTERVIEW_QUESTIONS): InterviewQuestion? =
        if (isPicker(step, questions.size)) null else questions[step]

    /** "2/7" for the eyebrow (never past the count). */
    fun progress(step: Int, count: Int = questionCount): String = "${minOf(step + 1, count)}/$count"

    /** "GETTING TO KNOW YOU · 2/7", or "LAST ONE" on the picker. */
    fun eyebrow(step: Int, count: Int = questionCount): String =
        if (isPicker(step, count)) InterviewCopy.EYEBROW_LAST else InterviewCopy.EYEBROW_PREFIX + progress(step, count)

    /** The picker is the terminal step — never past it. */
    fun nextStep(step: Int, count: Int = questionCount): Int = minOf(step + 1, count)

    /** Reaching the picker IS being onboarded — even when every answer was a
     *  null-fact chip ("It varies", "No one right now"): the ≥1-fact auto-done
     *  can't see those, and leaving before "That's me set up" used to keep
     *  them un-done and re-asked (web parity, 2026-09-05). */
    fun reachesDone(nextStep: Int, count: Int = questionCount): Boolean = isPicker(nextStep, count)

    /** The facts a free-text answer saves for [q]: name questions split into
     *  one person fact per name ([splitPeople]); the rest are one fact, with
     *  the question's prefix stitched on ("Work: four days"). Empty / blank
     *  text — or names with nothing nameable ("9, , 42") — yields nothing, and
     *  the caller stays on the question. */
    fun freeTextFacts(q: InterviewQuestion, text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        return if (q.splitNames) splitPeople(t)
        else listOf(q.freePrefix?.let { "$it: $t" } ?: t)
    }

    /** The people answer → person facts. Commas separate NAMES ("Maleek, Sam")
     *  — unless the text carries a descriptor dash ("Maleek — son, 9"), where
     *  the comma is part of the description and the whole line is one fact
     *  (profile convention: leading name, dash, detail). Pieces without a
     *  letter ("9") are dropped, and a name only ever yields one fact. */
    fun splitPeople(text: String): List<String> {
        val t = text.trim()
        val pieces = if (hasDescriptorDash(t)) listOf(t) else t.split(',')
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in pieces) {
            val p = raw.trim()
            if (p.none { it.isLetter() }) continue
            val key = leadName(p).lowercase()
            if (key.isEmpty() || !seen.add(key)) continue
            out.add(p)
        }
        return out
    }

    /** An em/en dash anywhere, or a hyphen next to whitespace ("Maleek - son");
     *  a hyphen INSIDE a word ("Mary-Jane") is part of the name. */
    private fun hasDescriptorDash(s: String): Boolean {
        if ('—' in s || '–' in s) return true
        for (i in s.indices) {
            if (s[i] != '-') continue
            val before = if (i > 0) s[i - 1] else ' '
            val after = if (i + 1 < s.length) s[i + 1] else ' '
            if (before.isWhitespace() || after.isWhitespace()) return true
        }
        return false
    }

    /** "Maleek — son, 9" → "Maleek" (moments `leadName`): the leading token up
     *  to whitespace or a separator — the refine-in-place key. */
    private fun leadName(fact: String): String {
        val seps = setOf('—', '–', '-', ',')
        return fact.takeWhile { !it.isWhitespace() && it !in seps }
    }
}

/** The in-thread host's lines (iOS InterviewThreadDriver, 2026-09-17): the
 *  interview is asked INSIDE the assistant thread — the greeting once, one
 *  question per local assistant turn, the rituals picker last, one closing
 *  line. Verbatim from iOS so the two platforms read alike. */
object InterviewThreadCopy {
    /** The first local turn: the web interview's greeting, then the disclosure. */
    fun greeting(firstName: String?): String = InterviewCopy.greeting(firstName) + "\n\n" + InterviewCopy.DISCLOSURE

    const val PICKER_QUESTION = "Last one — which moments should I run for you? All optional, all changeable in Settings."
    const val CLOSING = "That’s everything — I’ll plan around it. Change any of it in Settings → What Unstuck knows."
}

/** The same seven questions as one spoken list for the voice opening primer
 *  (buildVoiceOpening) — keyed by the script's keys so the two hosts can never
 *  drift apart (a unit test checks every key has a line). iOS InterviewVoice. */
object InterviewVoice {
    val spoken: Map<String, String> = mapOf(
        "rhythm" to "when their head's clearest — mornings, afternoons or evenings",
        "work" to "what their work days look like — the hours and days",
        "people" to "anyone whose schedule shapes theirs — kids, a partner, someone they care for (names help)",
        "fixed" to "fixed points in the week to plan around — school runs, prayers, classes",
        "commitments" to "regular commitments — gym, rehearsals, clubs, volunteering",
        "nogo" to "times to never schedule anything",
        "nudge" to "how they'd like to be nudged — gently, kept honest, or barely at all",
    )

    /** "; "-joined spoken lines for the questions from [from] on (the first is
     *  spoken verbatim in the primer's greeting, so the list starts at 1). */
    fun questionList(from: Int = 1, questions: List<InterviewQuestion> = INTERVIEW_QUESTIONS): String =
        questions.drop(from).mapNotNull { spoken[it.key] }.joinToString("; ")
}
