package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.INTERVIEW_QUESTIONS
import tech.csalliance.unstuck.core.logic.InterviewChip
import tech.csalliance.unstuck.core.logic.InterviewCopy
import tech.csalliance.unstuck.core.logic.InterviewFlag
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource

/**
 * The interview machine ([InterviewFlowController]) against a fake
 * [InterviewHost] — translation of the machine half of iOS InterviewTests.swift:
 * what each answer saves (source INTERVIEW), the people-answer splitting, a
 * failed save keeping the step, done-on-reaching-the-picker, the "I'm done"
 * finisher, the cross-device done hook firing once, collapse/park + resume,
 * the auto-open-once-then-pill rule, rituals through `setRituals`, and the
 * server flag pinning done underneath an open panel.
 */
class InterviewFlowTest {

    /** Captures the machine's side effects: saves as (category, fact, source)
     *  triples (`saveOK` = whether the fake store accepts the write), the
     *  persisted step, the done flag and how many times it was pushed. */
    private class FakeHost : InterviewHost {
        val saved = mutableListOf<Triple<ProfileFactCategory, String, ProfileFactSource>>()
        var saveOK = true
        var doneCalls = 0
        var parkedStep: Int? = null
        override val interviewDone = MutableStateFlow(false)
        override val rituals: MutableStateFlow<RitualPrefs> = MutableStateFlow(RitualPrefs.DEFAULTS)
        val ritualWrites = mutableListOf<RitualPrefs>()

        override fun interviewParkedStep(maxStep: Int): Int? = parkedStep?.let { InterviewFlag.parseInterviewStep(it.toString(), maxStep) }
        override fun setInterviewStep(step: Int) { parkedStep = step }
        override fun clearInterviewStep() { parkedStep = null }
        override fun markInterviewDone() { doneCalls += 1; interviewDone.value = true; parkedStep = null }
        override fun setRituals(prefs: RitualPrefs) { ritualWrites += prefs; rituals.value = prefs }
        override suspend fun saveProfileFact(category: ProfileFactCategory, fact: String, source: ProfileFactSource, whenIso: String?): ProfileFact? {
            if (!saveOK) return null
            saved += Triple(category, fact, source)
            return ProfileFact(id = "f${saved.size}", category = category, fact = fact, source = source, createdAt = "2026-09-06T10:00:00.000Z", updatedAt = "2026-09-06T10:00:00.000Z")
        }

        fun hasResumeStep(): Boolean = parkedStep != null
        fun isDone(): Boolean = interviewDone.value
    }

    private fun make(host: FakeHost = FakeHost()): Pair<InterviewFlowController, FakeHost> = InterviewFlowController(host) to host
    private fun chip(m: InterviewFlowController, label: String): InterviewChip = m.current!!.chips.first { it.label == label }
    private fun skip(m: InterviewFlowController, times: Int) = repeat(times) { m.skipQuestion() }

    // ── steps ──────────────────────────────────────────────────────────────

    @Test fun `a fresh machine starts at the greeting step`() {
        val (m, h) = make()
        assertEquals(0, m.step)
        assertTrue(m.isFirstStep)
        assertFalse(m.isPicker)
        assertEquals("rhythm", m.current?.key)
        assertEquals("1/7", m.progress)
        assertEquals("GETTING TO KNOW YOU · 1/7", m.eyebrow)
        assertTrue(h.saved.isEmpty())
        assertFalse(m.finished)
        assertNull(m.saveError)
    }

    @Test fun `a chip with a fact saves it as an INTERVIEW fact and advances`() = runTest {
        val (m, h) = make()
        m.answer(chip(m, "Morning"))
        assertEquals(1, h.saved.size)
        assertEquals(ProfileFactCategory.RHYTHM, h.saved[0].first)
        assertEquals("Mornings are the good hours — schedule the hard things early", h.saved[0].second)
        assertEquals("every interview save carries the interview source", ProfileFactSource.INTERVIEW, h.saved[0].third)
        assertEquals("Mornings are the good hours — schedule the hard things early", m.noted.last())
        assertEquals(1, m.step)
        assertEquals("work days come second, like the web", "work", m.current?.key)
        assertEquals("2/7", m.progress)
        assertFalse("the greeting bubble only shows on the first step", m.isFirstStep)
        assertEquals("each advance persists the step", 1, h.parkedStep)
    }

    @Test fun `a chip without a fact saves nothing but still advances`() = runTest {
        val (m, h) = make()
        m.answer(chip(m, "It varies"))
        assertTrue(h.saved.isEmpty())
        assertTrue(m.noted.isEmpty())
        assertEquals(1, m.step)
    }

    @Test fun `work free text is a context fact with the prefix`() = runTest {
        val (m, h) = make()
        skip(m, 1)
        assertEquals("work", m.current?.key)
        m.answerFree("four days, Fridays off")
        assertEquals(ProfileFactCategory.CONTEXT, h.saved.last().first)
        assertEquals("Work: four days, Fridays off", h.saved.last().second)
        assertEquals(2, m.step)
    }

    @Test fun `people free text splits comma-separated names into person facts`() = runTest {
        val (m, h) = make()
        skip(m, 2)
        assertEquals("people", m.current?.key)
        m.answerFree(" Maleek, Sam ,, ")
        assertEquals(listOf("Maleek", "Sam"), h.saved.map { it.second })
        assertTrue(h.saved.all { it.first == ProfileFactCategory.PERSON })
        assertEquals(3, m.step)
    }

    @Test fun `a descriptor with a comma is stored as one person fact`() = runTest {
        val (m, h) = make()
        skip(m, 2)
        m.answerFree("Maleek — son, 9")
        assertEquals("the comma is part of the description", listOf("Maleek — son, 9"), h.saved.map { it.second })
        assertEquals(ProfileFactCategory.PERSON, h.saved.first().first)
        assertEquals(3, m.step)
    }

    @Test fun `fixed points and commitments are saved verbatim, no-go with the prefix`() = runTest {
        val (m, h) = make()
        skip(m, 3)
        assertEquals("fixed", m.current?.key)
        m.answerFree("school run 8:30 and 15:15")
        assertEquals(ProfileFactCategory.CONSTRAINT, h.saved.last().first)
        assertEquals("school run 8:30 and 15:15", h.saved.last().second)
        assertEquals("commitments", m.current?.key)
        m.answerFree("five-a-side Tuesdays")
        assertEquals(ProfileFactCategory.CONTEXT, h.saved.last().first)
        assertEquals("five-a-side Tuesdays", h.saved.last().second)
        assertEquals("nogo", m.current?.key)
        m.answerFree("during school runs")
        assertEquals(ProfileFactCategory.CONSTRAINT, h.saved.last().first)
        assertEquals("Never schedule: during school runs", h.saved.last().second)
        assertEquals(6, m.step)
        assertEquals("nudge", m.current?.key)
        assertEquals("7/7", m.progress)
    }

    @Test fun `empty free text is a no-op`() = runTest {
        val (m, h) = make()
        skip(m, 2)
        m.answerFree("   ")
        m.answerFree(" , , ")
        m.answerFree("9, , 42")
        assertTrue(h.saved.isEmpty())
        assertEquals("stays on the question", 2, m.step)
    }

    @Test fun `skip advances without saving`() {
        val (m, h) = make()
        m.skipQuestion()
        assertEquals(1, m.step)
        assertTrue(h.saved.isEmpty())
        assertEquals(1, h.parkedStep)
    }

    @Test fun `after the last question comes the rituals picker and that is done`() {
        val (m, h) = make()
        skip(m, 6)
        assertFalse(m.isPicker)
        assertFalse("on the last question, not done yet", h.isDone())
        m.skipQuestion()
        assertTrue(m.isPicker)
        assertNull(m.current)
        assertEquals("7/7", m.progress)
        assertEquals("LAST ONE", m.eyebrow)
        // Reaching the picker IS being onboarded — even with every answer a
        // null-fact chip or skip, which the ≥1-fact auto-done can't see.
        assertTrue("reaching the end marks done even with zero facts saved", h.isDone())
        assertEquals("the account flag is pushed once, right here", 1, h.doneCalls)
        assertFalse("the picker still renders — finish/skip dismisses it", m.finished)
        m.skipQuestion()
        assertEquals("the picker is the terminal step — never past it", INTERVIEW_QUESTIONS.size, m.step)
        m.finish()
        assertTrue(m.finished)
        assertEquals("finishing after the picker doesn't push twice", 1, h.doneCalls)
        assertFalse("no stale resume step once done", h.hasResumeStep())
    }

    // ── failed saves keep the step ────────────────────────────────────────

    @Test fun `a failed chip save keeps the step and says so`() = runTest {
        val (m, h) = make()
        h.saveOK = false
        m.answer(chip(m, "Morning"))
        assertEquals("nothing landed — stay so they can retry", 0, m.step)
        assertTrue("no ✓ Noted over a dropped write", m.noted.isEmpty())
        assertEquals(InterviewCopy.SAVE_FAILED, m.saveError)
        assertEquals("Couldn’t save that — try again", m.saveError)
        h.saveOK = true
        m.answer(chip(m, "Morning"))
        assertEquals(1, m.step)
        assertEquals(listOf("Mornings are the good hours — schedule the hard things early"), m.noted)
        assertNull("a successful retry clears the message", m.saveError)
    }

    @Test fun `a failed free-text save keeps the step`() = runTest {
        val (m, h) = make()
        skip(m, 2)
        h.saveOK = false
        m.answerFree("Maleek, Sam")
        assertEquals(2, m.step)
        assertTrue(h.saved.isEmpty())
        assertTrue(m.noted.isEmpty())
        assertNotNull(m.saveError)
        h.saveOK = true
        m.answerFree("Maleek, Sam")
        assertEquals(3, m.step)
        assertEquals(listOf("Maleek", "Sam"), h.saved.map { it.second })
        assertNull(m.saveError)
    }

    @Test fun `a null-fact chip and skip never fail and clear a stale error`() = runTest {
        val (m, h) = make()
        h.saveOK = false
        m.answer(chip(m, "Morning"))
        assertNotNull(m.saveError)
        m.answer(chip(m, "It varies"))
        assertEquals(1, m.step)
        assertNull(m.saveError)
        m.answer(chip(m, "Shifts"))
        assertNotNull(m.saveError)
        m.skipQuestion()
        assertEquals(2, m.step)
        assertNull(m.saveError)
    }

    // ── done flag / collapse / resume ─────────────────────────────────────

    @Test fun `finish marks done and never re-asks`() = runTest {
        val (m, h) = make()
        m.answer(chip(m, "Evening"))
        assertFalse(h.isDone())
        assertEquals(0, h.doneCalls)
        m.finish()
        assertTrue(m.finished)
        assertTrue(h.isDone())
        assertEquals("\"I'm done\" pushes the account flag", 1, h.doneCalls)
        assertFalse("no stale resume step once done", h.hasResumeStep())
        assertFalse(InterviewFlag.shouldAutoOpen(factCount = 0, done = h.isDone()))
        m.finish()
        assertEquals("idempotent", 1, h.doneCalls)
    }

    @Test fun `collapse parks it and a new controller resumes there`() = runTest {
        val (m, h) = make()
        m.answer(chip(m, "Morning"))
        m.collapse()
        assertFalse("the chevron is not the finisher (that's \"I'm done\", web parity)", m.finished)
        assertFalse("the pill stays — the way back in", h.isDone())
        assertEquals(0, h.doneCalls)
        assertTrue("the step is persisted", h.hasResumeStep())
        assertEquals(1, h.interviewParkedStep(INTERVIEW_QUESTIONS.size))
        assertEquals("what was answered stays saved", 1, h.saved.size)
        val (resumed, _) = make(h)
        assertEquals("re-opening resumes where they left off", 1, resumed.step)
        assertEquals("work", resumed.current?.key)
        assertFalse(resumed.isFirstStep)
        assertFalse(
            "parked ≠ pop back open on the next launch — that's the nag it exists to avoid",
            InterviewFlag.shouldAutoOpen(factCount = 0, done = false, hasResumeStep = h.hasResumeStep()),
        )
    }

    @Test fun `I'm done finishes from any step`() {
        val (m, h) = make()
        m.skipQuestion()
        m.finish()
        assertTrue(m.finished)
        assertTrue(h.isDone())
        assertFalse(h.hasResumeStep())
    }

    @Test fun `progress survives a relaunch even without an explicit collapse`() {
        val (m, h) = make()
        skip(m, 3)
        val (again, _) = make(h)
        assertEquals("each advance persists the step", 3, again.step)
    }

    @Test fun `an out-of-range parked step restarts at the greeting`() {
        val h = FakeHost().apply { parkedStep = 42 }
        val (m, _) = make(h)
        assertEquals(0, m.step)
    }

    @Test fun `a parked picker step resumes on the picker`() {
        val h = FakeHost().apply { parkedStep = INTERVIEW_QUESTIONS.size }
        val (m, _) = make(h)
        assertTrue(m.isPicker)
        assertEquals("LAST ONE", m.eyebrow)
    }

    // ── auto-open once, then the pill ─────────────────────────────────────

    @Test fun `an auto-opened panel is parked so the next launch shows the pill`() {
        val h = FakeHost()
        assertTrue(
            "first launch, nothing anywhere: the panel opens itself",
            InterviewFlag.shouldAutoOpen(factCount = 0, done = false, hasResumeStep = h.hasResumeStep()),
        )
        val (m, _) = make(h)
        m.markInProgress()   // what the host does the moment it opens the panel
        assertTrue(h.hasResumeStep())
        assertEquals(0, h.interviewParkedStep(INTERVIEW_QUESTIONS.size))
        assertFalse(h.isDone())
        assertEquals(0, h.doneCalls)
        assertFalse(
            "second cold launch: the pill, not the panel at 1/7 again",
            InterviewFlag.shouldAutoOpen(factCount = 0, done = false, hasResumeStep = h.hasResumeStep()),
        )
        // A parked step 0 holds none of its own answers: a fact saved via chat
        // still stands the interview down.
        assertTrue(InterviewFlag.shouldAutoComplete(factCount = 1, isOpen = false, done = false, parkedStep = h.interviewParkedStep(INTERVIEW_QUESTIONS.size)))
        val (again, _) = make(h)
        assertEquals("the pill resumes at the greeting", 0, again.step)
    }

    @Test fun `hidden mid-way with three answers still reaches the picker after relaunch`() = runTest {
        val (m, h) = make()
        m.answer(chip(m, "Morning"))
        m.answer(chip(m, "Shifts"))
        m.answerFree("Maleek")
        assertEquals(3, h.saved.size)
        m.collapse()
        // Relaunch: the card sees 3 facts and a parked mid-way step → must NOT auto-complete.
        assertFalse(InterviewFlag.shouldAutoComplete(factCount = 3, isOpen = false, done = h.isDone(), parkedStep = h.interviewParkedStep(INTERVIEW_QUESTIONS.size)))
        val (again, _) = make(h)
        assertEquals(3, again.step)
        assertEquals("fixed", again.current?.key)
        skip(again, 4)
        assertTrue("the rituals picker is reachable", again.isPicker)
        assertTrue(h.isDone())
        again.finish()
        assertTrue(h.isDone())
        assertFalse(h.hasResumeStep())
    }

    // ── rituals picker ────────────────────────────────────────────────────

    @Test fun `the picker's toggles save through setRituals`() {
        val (m, h) = make()
        skip(m, 7)
        assertTrue(m.isPicker)
        m.setRitual(RitualKey.FRIDAY, true)
        assertEquals(listOf(RitualPrefs(morning = true, evening = true, friday = true, sunday = false)), h.ritualWrites)
        m.setRitual(RitualKey.MORNING, false)
        assertEquals(RitualPrefs(morning = false, evening = true, friday = true, sunday = false), h.rituals.value)
        assertEquals("each toggle is one account-wide write", 2, h.ritualWrites.size)
        m.finish()
        assertTrue(m.finished)
        assertEquals("reaching the picker already pushed done", 1, h.doneCalls)
    }

    // ── server says done (user_preferences.assistant_interview_done_at) ───

    @Test fun `the server flag pins done underneath an open panel without re-pushing`() = runTest {
        val (m, h) = make()
        m.answer(chip(m, "Morning"))
        assertFalse(m.finished)
        h.interviewDone.value = true   // the pull's applyServerAssistantPrefs pinned it (finished on the web)
        m.applyHostDone()
        assertTrue("done never keeps the panel open — someone who finished elsewhere is not asked again", m.finished)
        assertEquals("nothing pushed from here — the account already says done", 0, h.doneCalls)
        m.finish()
        assertEquals("a later finish doesn't push either", 0, h.doneCalls)
        assertFalse(h.hasResumeStep())
    }

    @Test fun `applyHostDone is a no-op while the account says not done`() {
        val (m, h) = make()
        m.skipQuestion()
        m.applyHostDone()
        assertFalse(m.finished)
        assertEquals(1, m.step)
        assertEquals(0, h.doneCalls)
    }
}
