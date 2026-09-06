package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.INTERVIEW_QUESTIONS
import tech.csalliance.unstuck.core.logic.InterviewCopy
import tech.csalliance.unstuck.core.logic.InterviewFlag
import tech.csalliance.unstuck.core.logic.InterviewScript
import tech.csalliance.unstuck.core.model.ProfileFactCategory

// The pure half of the get-to-know-you interview — translation of the script /
// splitPeople / step-rule cases of iOS InterviewTests.swift and the web's
// components/assistant/interview.test.ts. The machine's side effects (saving,
// parking, marking done) are covered by the app's InterviewFlowTest.
class InterviewTest {
    private val q = INTERVIEW_QUESTIONS.associateBy { it.key }

    // ── script (web parity — components/assistant/interview.tsx) ──────────

    @Test fun `the script is the web's seven questions in the web's order`() {
        assertEquals(7, INTERVIEW_QUESTIONS.size)
        assertEquals(7, InterviewScript.questionCount)
        assertEquals(listOf("rhythm", "work", "people", "fixed", "commitments", "nogo", "nudge"), INTERVIEW_QUESTIONS.map { it.key })
        assertEquals(
            "categories are the web's: work + commitments are context, fixed points + no-go are constraints",
            listOf(
                ProfileFactCategory.RHYTHM, ProfileFactCategory.CONTEXT, ProfileFactCategory.PERSON, ProfileFactCategory.CONSTRAINT,
                ProfileFactCategory.CONTEXT, ProfileFactCategory.CONSTRAINT, ProfileFactCategory.PREFERENCE,
            ),
            INTERVIEW_QUESTIONS.map { it.category },
        )
        assertEquals(listOf(null, "Work", null, null, null, "Never schedule", null), INTERVIEW_QUESTIONS.map { it.freePrefix })
        assertEquals(listOf(false, true, true, true, true, true, false), INTERVIEW_QUESTIONS.map { it.allowFree })
        assertEquals("names are one person fact each", listOf(false, false, true, false, false, false, false), INTERVIEW_QUESTIONS.map { it.splitNames })
        assertTrue("every step is tap-answerable", INTERVIEW_QUESTIONS.all { it.chips.isNotEmpty() })
    }

    @Test fun `script copy and chips match the web verbatim`() {
        assertEquals("When’s your head clearest?", q["rhythm"]!!.question)
        assertEquals(listOf("Morning", "Afternoon", "Evening", "It varies"), q["rhythm"]!!.chips.map { it.label })
        assertEquals(
            listOf(
                "Mornings are the good hours — schedule the hard things early", "Afternoons are the good hours",
                "Evenings are the good hours — slow starter", null,
            ),
            q["rhythm"]!!.chips.map { it.fact },
        )
        assertEquals("What do your work days look like?", q["work"]!!.question)
        assertEquals(listOf("9–5 weekdays", "Shifts", "Flexible / freelance", "Studying"), q["work"]!!.chips.map { it.label })
        assertEquals(
            listOf(
                "Works roughly 9–5 on weekdays", "Works shifts — hours change week to week",
                "Flexible schedule — sets their own hours", "Studying — timetable over office hours",
            ),
            q["work"]!!.chips.map { it.fact },
        )
        assertEquals("Anyone whose schedule shapes yours — kids, a partner, someone you care for? Names help.", q["people"]!!.question)
        assertEquals(listOf("No one right now"), q["people"]!!.chips.map { it.label })
        assertEquals("Fixed points in the week I should plan around? School runs, prayers, classes…", q["fixed"]!!.question)
        assertEquals(listOf("None"), q["fixed"]!!.chips.map { it.label })
        assertEquals("Regular commitments — gym, rehearsals, clubs, volunteering?", q["commitments"]!!.question)
        assertEquals(listOf("Not really"), q["commitments"]!!.chips.map { it.label })
        assertEquals("When should I never schedule anything?", q["nogo"]!!.question)
        assertEquals(listOf("Before 9am", "After 9pm", "Weekends", "No hard limits"), q["nogo"]!!.chips.map { it.label })
        assertEquals(
            listOf(
                "Never schedule anything before 9am", "Never schedule anything after 9pm",
                "Keep weekends free — never schedule work there", null,
            ),
            q["nogo"]!!.chips.map { it.fact },
        )
        assertEquals("Last one — how should I nudge you?", q["nudge"]!!.question)
        assertEquals(listOf("Gently", "Keep me honest", "Barely at all"), q["nudge"]!!.chips.map { it.label })
        assertEquals(
            listOf(
                "Prefers gentle nudges — suggest, never push", "Wants to be kept honest — direct nudges are welcome",
                "Minimal nudging — only speak up when it really matters",
            ),
            q["nudge"]!!.chips.map { it.fact },
        )
        for (k in listOf("people", "fixed", "commitments")) {
            assertEquals("$k: the only chip is a null-fact opt-out", listOf<String?>(null), q[k]!!.chips.map { it.fact })
        }
    }

    @Test fun `the greeting, disclosure and picker copy are the web's and iOS's`() {
        assertEquals("Hey Ada. A few quick questions so I can plan around your actual life — skip any you like.", InterviewCopy.greeting("Ada"))
        assertEquals("Hey. A few quick questions so I can plan around your actual life — skip any you like.", InterviewCopy.greeting(null))
        assertEquals("no name = no dangling space", InterviewCopy.greeting(null), InterviewCopy.greeting("   "))
        assertEquals("Which moments should I run for you? All optional, all changeable in Settings.", InterviewCopy.PICKER_QUESTION)
        assertEquals("That’s me set up", InterviewCopy.THATS_ME_SET_UP)
        assertEquals("I’m done", InterviewCopy.IM_DONE)
        assertEquals("Couldn’t save that — try again", InterviewCopy.SAVE_FAILED)
        assertTrue(InterviewCopy.DISCLOSURE.startsWith("I’ll remember what you tell me; it stays yours"))
        assertTrue(InterviewCopy.DISCLOSURE.contains("which doesn’t train on them"))
    }

    // ── steps ──────────────────────────────────────────────────────────────

    @Test fun `a fresh machine starts at the greeting step`() {
        assertEquals(0, InterviewScript.resumeStep(null))
        assertFalse(InterviewScript.isPicker(0))
        assertEquals("rhythm", InterviewScript.current(0)?.key)
        assertEquals("the eyebrow reads GETTING TO KNOW YOU · 1/7 — the web's count", "1/7", InterviewScript.progress(0))
        assertEquals("GETTING TO KNOW YOU · 1/7", InterviewScript.eyebrow(0))
    }

    @Test fun `advancing walks the script and the picker is the terminal step`() {
        assertEquals(1, InterviewScript.nextStep(0))
        assertEquals("work days come second, like the web", "work", InterviewScript.current(1)?.key)
        assertEquals("2/7", InterviewScript.progress(1))
        assertEquals("nudge", InterviewScript.current(6)?.key)
        assertEquals("7/7", InterviewScript.progress(6))
        assertFalse(InterviewScript.isPicker(6))
        assertFalse("on the last question, not done yet", InterviewScript.reachesDone(6))
        assertEquals(7, InterviewScript.nextStep(6))
        assertTrue(InterviewScript.isPicker(7))
        assertNull(InterviewScript.current(7))
        assertEquals("7/7", InterviewScript.progress(7))
        assertEquals("LAST ONE", InterviewScript.eyebrow(7))
        assertTrue("reaching the end marks done even with zero facts saved", InterviewScript.reachesDone(7))
        assertEquals("the picker is the terminal step — never past it", 7, InterviewScript.nextStep(7))
    }

    @Test fun `a persisted step resumes, including the picker step, and out-of-range restarts`() {
        assertEquals(3, InterviewScript.resumeStep(3))
        assertEquals(7, InterviewScript.resumeStep(7))
        assertEquals(0, InterviewScript.resumeStep(42))
        assertEquals(0, InterviewScript.resumeStep(-1))
        assertEquals("the persisted-string parse agrees", 0, InterviewFlag.parseInterviewStep("8", 7))
        assertEquals(7, InterviewFlag.parseInterviewStep("7", 7))
    }

    // ── answers → facts ───────────────────────────────────────────────────

    @Test fun `work free text is a context fact with the prefix`() {
        assertEquals(listOf("Work: four days, Fridays off"), InterviewScript.freeTextFacts(q["work"]!!, "four days, Fridays off"))
        assertEquals(ProfileFactCategory.CONTEXT, q["work"]!!.category)
    }

    @Test fun `people free text splits comma-separated names into person facts`() {
        assertEquals(listOf("Maleek", "Sam"), InterviewScript.freeTextFacts(q["people"]!!, " Maleek, Sam ,, "))
        assertEquals(ProfileFactCategory.PERSON, q["people"]!!.category)
    }

    @Test fun `fixed points and commitments are saved verbatim`() {
        assertEquals("no prefix on the web either", listOf("school run 8:30 and 15:15"), InterviewScript.freeTextFacts(q["fixed"]!!, "school run 8:30 and 15:15"))
        assertEquals(ProfileFactCategory.CONSTRAINT, q["fixed"]!!.category)
        assertEquals(listOf("five-a-side Tuesdays"), InterviewScript.freeTextFacts(q["commitments"]!!, "five-a-side Tuesdays"))
        assertEquals(ProfileFactCategory.CONTEXT, q["commitments"]!!.category)
    }

    @Test fun `free text uses the prefix on the no-go step`() {
        assertEquals(listOf("Never schedule: during school runs"), InterviewScript.freeTextFacts(q["nogo"]!!, "during school runs"))
        assertEquals(ProfileFactCategory.CONSTRAINT, q["nogo"]!!.category)
    }

    @Test fun `empty free text yields nothing`() {
        assertEquals(emptyList<String>(), InterviewScript.freeTextFacts(q["people"]!!, "   "))
        assertEquals(emptyList<String>(), InterviewScript.freeTextFacts(q["people"]!!, " , , "))
        assertEquals(emptyList<String>(), InterviewScript.freeTextFacts(q["work"]!!, ""))
        assertEquals("the people answer with nothing nameable stays on the question", emptyList<String>(), InterviewScript.freeTextFacts(q["people"]!!, "9, , 42"))
    }

    // ── people splitting (C6) ─────────────────────────────────────────────

    @Test fun `a descriptor with a comma is one person fact`() {
        assertEquals(listOf("Maleek — son, 9"), InterviewScript.splitPeople("Maleek — son, 9"))
        assertEquals(listOf("Maleek - son, 9"), InterviewScript.splitPeople("Maleek - son, 9"))
        assertEquals(listOf("Zara – daughter, turning 8"), InterviewScript.splitPeople("Zara – daughter, turning 8"))
    }

    @Test fun `plain names split on commas`() {
        assertEquals(listOf("Maleek", "Sam"), InterviewScript.splitPeople("Maleek, Sam"))
        assertEquals("a hyphen inside a name is not a descriptor dash", listOf("Mary-Jane", "Sam"), InterviewScript.splitPeople("Mary-Jane, Sam"))
    }

    @Test fun `pieces without a letter are dropped`() {
        assertEquals(listOf("Maleek"), InterviewScript.splitPeople("Maleek, 9"))
        assertEquals(emptyList<String>(), InterviewScript.splitPeople("9, , 42"))
    }

    @Test fun `one fact per name, case-insensitively`() {
        assertEquals(listOf("Maleek", "Sam"), InterviewScript.splitPeople("Maleek, maleek, Sam"))
    }

    // ── auto rules (shared with InterviewFlag — asserted here for the reader) ──

    @Test fun `auto-done at one fact but never while open or parked mid-way`() {
        assertTrue("one saved fact means they engaged (web parity — was three)", InterviewFlag.shouldAutoComplete(factCount = 1, isOpen = false, done = false))
        assertFalse("its own answers grow the count — auto-closing mid-interview looks like a crash", InterviewFlag.shouldAutoComplete(1, isOpen = true, done = false))
        assertFalse(InterviewFlag.shouldAutoComplete(3, isOpen = false, done = true))
        assertFalse("that fact may be its OWN hidden-mid-way answer", InterviewFlag.shouldAutoComplete(1, isOpen = false, done = false, parkedStep = 2))
        assertTrue("parked at the greeting = auto-opened, never answered", InterviewFlag.shouldAutoComplete(1, isOpen = false, done = false, parkedStep = 0))
    }

    @Test fun `auto-open only with nothing learned anywhere`() {
        assertTrue(InterviewFlag.shouldAutoOpen(factCount = 0, done = false))
        assertFalse("facts from another device / chat: the pill nudges instead", InterviewFlag.shouldAutoOpen(1, done = false))
        assertFalse(InterviewFlag.shouldAutoOpen(0, done = true))
        assertFalse("parked ≠ pop back open on the next launch", InterviewFlag.shouldAutoOpen(0, done = false, hasResumeStep = true))
    }
}
