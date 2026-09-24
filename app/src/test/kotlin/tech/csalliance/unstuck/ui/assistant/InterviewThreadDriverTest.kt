package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.INTERVIEW_QUESTIONS
import tech.csalliance.unstuck.core.logic.InterviewChip
import tech.csalliance.unstuck.core.logic.displayLabel
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.core.logic.InterviewCopy
import tech.csalliance.unstuck.core.logic.InterviewFlag
import tech.csalliance.unstuck.core.logic.InterviewThreadCopy
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource

/**
 * The interview inside the assistant thread ([InterviewThreadDriver]) against a
 * fake host + a fake thread — translation of iOS InterviewThreadTests: the
 * first send of a visit arms it and the REPLY comes first; the greeting once,
 * then one question per local turn; taps echo as user bubbles; a subject
 * change re-asks; a failed save keeps the question; the picker finishes with
 * one closing line; done / ≥1-fact / not-ready stand it down.
 */
class InterviewThreadDriverTest {

    private class FakeHost : InterviewHost {
        val saved = mutableListOf<Triple<ProfileFactCategory, String, ProfileFactSource>>()
        var saveOK = true
        var doneCalls = 0
        var parkedStep: Int? = null
        override val interviewDone = MutableStateFlow(false)
        override val rituals: MutableStateFlow<RitualPrefs> = MutableStateFlow(RitualPrefs.DEFAULTS)

        override fun interviewParkedStep(maxStep: Int): Int? = parkedStep?.let { InterviewFlag.parseInterviewStep(it.toString(), maxStep) }
        override fun setInterviewStep(step: Int) { parkedStep = step }
        override fun clearInterviewStep() { parkedStep = null }
        override fun markInterviewDone() { doneCalls += 1; interviewDone.value = true; parkedStep = null }
        override fun setRituals(prefs: RitualPrefs) { rituals.value = prefs }
        override suspend fun saveProfileFact(category: ProfileFactCategory, fact: String, source: ProfileFactSource, whenIso: String?): ProfileFact? {
            if (!saveOK) return null
            saved += Triple(category, fact, source)
            return ProfileFact(id = "f${saved.size}", category = category, fact = fact, source = source, createdAt = "2026-09-17T10:00:00.000Z", updatedAt = "2026-09-17T10:00:00.000Z")
        }
    }

    /** The thread: local assistant turns (with ids) + echoed user bubbles. */
    private class FakeThread {
        val posts = mutableListOf<Pair<String, String>>()   // id to text
        val echoes = mutableListOf<String>()
        fun post(text: String): String = "a${posts.size + 1}".also { posts += it to text }
        val texts: List<String> get() = posts.map { it.second }
    }

    private class Rig(
        val host: FakeHost = FakeHost(),
        val thread: FakeThread = FakeThread(),
        ready: Boolean = true,
        factCount: Int = 0,
        firstName: String? = "Maya",
        labelOf: (InterviewChip) -> String = { it.label },
    ) {
        var ready = ready
        var factCount = factCount
        val driver = InterviewThreadDriver(
            controller = InterviewFlowController(host), host = host, firstName = firstName,
            ready = { this.ready }, factCount = { this.factCount },
            post = thread::post, echo = { thread.echoes += it }, labelOf = labelOf,
        )
    }

    private fun chip(r: Rig, label: String): InterviewChip = r.driver.controller.current!!.chips.first { it.label == label }

    /** First send + its reply: the greeting and Q1 are on screen. */
    private fun armed(r: Rig = Rig()): Rig {
        r.driver.userSent()
        r.driver.turnFinished()
        return r
    }

    // ── arming ──

    @Test fun `nothing is asked before the first send, and the reply comes first`() {
        val r = Rig()
        assertEquals(InterviewThreadPhase.IDLE, r.driver.phase)
        r.driver.turnFinished()                                  // a stray finish while idle
        assertTrue(r.thread.posts.isEmpty())
        r.driver.userSent()
        assertEquals("the reply to the first message comes first", InterviewThreadPhase.WAITING_FOR_REPLY, r.driver.phase)
        assertTrue(r.thread.posts.isEmpty())
        assertNull(r.driver.promptTurnId)
        r.driver.turnFinished()
        assertEquals(InterviewThreadPhase.ASKING, r.driver.phase)
        assertTrue(r.driver.isAsking)
        assertEquals(listOf(InterviewThreadCopy.greeting("Maya"), INTERVIEW_QUESTIONS[0].question), r.thread.texts)
        assertEquals("the chip row hangs under the question, not the greeting", "a2", r.driver.promptTurnId)
        assertEquals("the step is parked the moment it starts asking", 0, r.host.parkedStep)
        assertTrue(r.thread.texts[0].startsWith("Hey Maya. A few quick questions"))
        assertTrue(r.thread.texts[0].endsWith(InterviewCopy.DISCLOSURE))
        // A second send this visit changes nothing about the phase.
        r.driver.userSent()
        assertEquals(InterviewThreadPhase.ASKING, r.driver.phase)
    }

    @Test fun `an account that is done is never asked`() {
        val r = Rig()
        r.host.interviewDone.value = true
        r.driver.userSent()
        assertEquals(InterviewThreadPhase.DONE, r.driver.phase)
        r.driver.turnFinished()
        assertTrue(r.thread.posts.isEmpty())
        assertEquals(0, r.host.doneCalls)
    }

    @Test fun `someone the assistant already knows is stood down, not greeted as a stranger`() {
        val r = Rig(factCount = 2)
        r.driver.userSent()
        assertEquals(InterviewThreadPhase.DONE, r.driver.phase)
        assertEquals("the ≥1-fact rule finishes the interview account-wide", 1, r.host.doneCalls)
        assertTrue(r.host.interviewDone.value)
        r.driver.turnFinished()
        assertTrue(r.thread.posts.isEmpty())
    }

    @Test fun `a step parked mid-way resumes there even with facts, and skips the greeting`() {
        val host = FakeHost().apply { parkedStep = 2 }
        val r = Rig(host = host, factCount = 1)
        r.driver.userSent()
        assertEquals("that fact may be its own answer — never stood down mid-way", InterviewThreadPhase.WAITING_FOR_REPLY, r.driver.phase)
        r.driver.turnFinished()
        assertEquals(listOf(INTERVIEW_QUESTIONS[2].question), r.thread.texts)
        assertEquals(0, host.doneCalls)
    }

    @Test fun `not ready yet - the decision waits for the memory to be read`() {
        val r = Rig(ready = false, factCount = 3)
        r.driver.userSent()
        assertEquals(InterviewThreadPhase.IDLE, r.driver.phase)
        r.driver.turnFinished()
        assertTrue(r.thread.posts.isEmpty())
        r.ready = true
        r.driver.userSent()
        assertEquals("now it can decide — and stands down on the facts", InterviewThreadPhase.DONE, r.driver.phase)
    }

    // ── answering ──

    @Test fun `a chip saves its fact, echoes the label and asks the next question - the greeting only once`() = runTest {
        val r = armed()
        r.driver.answer(chip(r, "Morning"))
        assertEquals(1, r.host.saved.size)
        assertEquals(ProfileFactCategory.RHYTHM, r.host.saved[0].first)
        assertEquals(ProfileFactSource.INTERVIEW, r.host.saved[0].third)
        assertEquals(listOf("Morning"), r.thread.echoes)
        assertEquals(INTERVIEW_QUESTIONS[1].question, r.thread.texts.last())
        assertEquals(3, r.thread.posts.size)                     // greeting, Q1, Q2 — no second greeting
        assertEquals("a3", r.driver.promptTurnId)
        assertEquals(1, r.host.parkedStep)
    }

    @Test fun `a chip naming an hour echoes it the phone's way, the fact keeps the web's words`() = runTest {
        // Ahmad, 2026-09-24: one clock app-wide — a 24-hour phone reads "Before 09:00".
        val r = armed(Rig(labelOf = { it.displayLabel(ClockMode.H24, java.util.Locale.US) }))
        repeat(5) { r.driver.skip() }                            // rhythm … commitments → nogo
        assertEquals("nogo", r.driver.controller.current!!.key)
        r.driver.answer(chip(r, "Before 9am"))
        assertEquals("Before 09:00", r.thread.echoes.last())
        assertEquals("Never schedule anything before 9am", r.host.saved.last().second)
    }

    @Test fun `a null-fact chip saves nothing but still moves on`() = runTest {
        val r = armed()
        r.driver.answer(chip(r, "It varies"))
        assertTrue(r.host.saved.isEmpty())
        assertEquals(listOf("It varies"), r.thread.echoes)
        assertEquals(INTERVIEW_QUESTIONS[1].question, r.thread.texts.last())
    }

    @Test fun `a failed save keeps the question up and echoes nothing`() = runTest {
        val r = armed()
        r.host.saveOK = false
        r.driver.answer(chip(r, "Morning"))
        assertTrue(r.thread.echoes.isEmpty())
        assertEquals("a2", r.driver.promptTurnId)
        assertEquals(2, r.thread.posts.size)
        assertEquals(InterviewCopy.SAVE_FAILED, r.driver.controller.saveError)
        assertEquals(0, r.driver.controller.step)
        // The retry lands.
        r.host.saveOK = true
        r.driver.answer(chip(r, "Morning"))
        assertEquals(listOf("Morning"), r.thread.echoes)
        assertNull(r.driver.controller.saveError)
    }

    @Test fun `skip echoes Skip and moves on without saving`() {
        val r = armed()
        r.driver.skip()
        assertTrue(r.host.saved.isEmpty())
        assertEquals(listOf(InterviewCopy.SKIP), r.thread.echoes)
        assertEquals(INTERVIEW_QUESTIONS[1].question, r.thread.texts.last())
    }

    @Test fun `free text echoes what was typed, and blank text is a no-op`() = runTest {
        val r = armed()
        r.driver.skip()                                          // → work (allows free text)
        r.driver.answerFree("   ")
        assertEquals(listOf(InterviewCopy.SKIP), r.thread.echoes)
        r.driver.answerFree(" four days, Fridays off ")
        assertEquals("Work: four days, Fridays off", r.host.saved.last().second)
        assertEquals(listOf(InterviewCopy.SKIP, "four days, Fridays off"), r.thread.echoes)
        assertEquals(INTERVIEW_QUESTIONS[2].question, r.thread.texts.last())
    }

    @Test fun `changing the subject gets the reply first, then the same question again`() {
        val r = armed()
        r.driver.userSent()                                      // an unrelated message
        r.driver.turnFinished()                                  // its reply landed
        assertEquals(listOf(InterviewThreadCopy.greeting("Maya"), INTERVIEW_QUESTIONS[0].question, INTERVIEW_QUESTIONS[0].question), r.thread.texts)
        assertEquals("the chips move to the fresh copy of the question", "a3", r.driver.promptTurnId)
        assertEquals(0, r.driver.controller.step)
    }

    // ── finishing ──

    @Test fun `through every question - the picker, then That's me set up closes with one line and marks done once`() = runTest {
        val r = armed()
        repeat(INTERVIEW_QUESTIONS.size) { r.driver.skip() }
        assertTrue(r.driver.controller.isPicker)
        assertEquals(InterviewThreadCopy.PICKER_QUESTION, r.thread.texts.last())
        assertNotNull(r.driver.promptTurnId)
        assertEquals("reaching the picker IS being onboarded", 1, r.host.doneCalls)
        assertTrue(r.host.interviewDone.value)
        assertFalse("the controller's own push is not 'done from elsewhere' — the picker stays", r.driver.hostDone())
        assertEquals(InterviewThreadPhase.ASKING, r.driver.phase)
        r.driver.finish()
        assertEquals(InterviewThreadPhase.DONE, r.driver.phase)
        assertNull(r.driver.promptTurnId)
        assertEquals(InterviewThreadCopy.CLOSING, r.thread.texts.last())
        assertEquals("done is pushed once", 1, r.host.doneCalls)
        assertNull(r.host.parkedStep)
        // Nothing more, ever.
        r.driver.turnFinished(); r.driver.skip(); r.driver.finish()
        assertEquals(InterviewThreadCopy.CLOSING, r.thread.texts.last())
        assertEquals(INTERVIEW_QUESTIONS.size + 3, r.thread.posts.size)   // greeting + 7 questions + picker + closing
    }

    @Test fun `done from another device while asking stands the thread down`() {
        val r = armed()
        r.host.interviewDone.value = true                        // a finish elsewhere / a server pin
        assertTrue(r.driver.hostDone())
        assertEquals(InterviewThreadPhase.DONE, r.driver.phase)
        assertNull(r.driver.promptTurnId)
        assertEquals(0, r.host.doneCalls)
        assertFalse("already stood down", r.driver.hostDone())
    }

    @Test fun `answers, skips and finish are no-ops while nothing is being asked`() = runTest {
        val r = Rig()
        r.driver.skip()
        r.driver.answer(INTERVIEW_QUESTIONS[0].chips[0])
        r.driver.answerFree("x")
        r.driver.finish()
        assertTrue(r.thread.posts.isEmpty()); assertTrue(r.thread.echoes.isEmpty()); assertTrue(r.host.saved.isEmpty())
        assertFalse(r.driver.hostDone())
    }

    @Test fun `no name - the greeting still reads as a person`() {
        val r = armed(Rig(firstName = null))
        assertTrue(r.thread.texts[0].startsWith("Hey. A few quick questions"))
    }
}
