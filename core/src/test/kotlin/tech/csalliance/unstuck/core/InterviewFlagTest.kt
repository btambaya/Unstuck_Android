package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.InterviewFlag

// The cross-device interview flag + the auto-open / auto-done gates. Ported
// from lib/assistant/interview-flag.ts (interviewDoneFromServer,
// parseInterviewStep), interview.tsx (shouldAutoComplete / shouldAutoOpen) and
// iOS InterviewTests (the gate that waits for the server hydrate, the
// server-flag pin, the parked-step rules).
class InterviewFlagTest {

    // ── server flag ────────────────────────────────────────────────────────

    @Test fun `a parseable timestamp means done, anything else does not`() {
        assertTrue(InterviewFlag.interviewDoneFromServer("2026-09-05T10:00:00.000Z"))
        // PostgREST shape: +00:00 offset, microseconds.
        assertTrue(InterviewFlag.interviewDoneFromServer("2026-09-05T10:00:00.123456+00:00"))
        assertTrue(InterviewFlag.interviewDoneFromServer("  2026-09-05T10:00:00Z  "))
        assertFalse(InterviewFlag.interviewDoneFromServer(null))
        assertFalse(InterviewFlag.interviewDoneFromServer(""))
        assertFalse(InterviewFlag.interviewDoneFromServer("   "))
        assertFalse(InterviewFlag.interviewDoneFromServer("garbage"))
        assertEquals("assistant_interview_done_at", InterviewFlag.DONE_COLUMN)
    }

    @Test fun `a persisted resume step parses to a valid index or restarts at 0`() {
        assertEquals(0, InterviewFlag.parseInterviewStep(null, 7))
        assertEquals(0, InterviewFlag.parseInterviewStep("", 7))
        assertEquals(3, InterviewFlag.parseInterviewStep("3", 7))
        assertEquals(7, InterviewFlag.parseInterviewStep("7", 7))
        assertEquals(0, InterviewFlag.parseInterviewStep("8", 7))
        assertEquals(0, InterviewFlag.parseInterviewStep("-1", 7))
        assertEquals(0, InterviewFlag.parseInterviewStep("2.5", 7))
        assertEquals(0, InterviewFlag.parseInterviewStep("abc", 7))
    }

    // ── auto rules ─────────────────────────────────────────────────────────

    @Test fun `auto-done at one fact but never while open`() {
        assertTrue("one saved fact means they engaged (web parity — was three)", InterviewFlag.shouldAutoComplete(factCount = 1, isOpen = false, done = false))
        assertTrue(InterviewFlag.shouldAutoComplete(factCount = 7, isOpen = false, done = false))
        assertFalse(InterviewFlag.shouldAutoComplete(factCount = 0, isOpen = false, done = false))
        assertFalse("its own answers grow the count", InterviewFlag.shouldAutoComplete(factCount = 1, isOpen = true, done = false))
        assertFalse(InterviewFlag.shouldAutoComplete(factCount = 3, isOpen = false, done = true))
    }

    @Test fun `auto-done never while a mid-way step is parked`() {
        assertFalse("that fact may be its OWN hidden-mid-way answer", InterviewFlag.shouldAutoComplete(1, isOpen = false, done = false, parkedStep = 2))
        assertTrue("facts from elsewhere with nothing parked: stand down", InterviewFlag.shouldAutoComplete(1, isOpen = false, done = false, parkedStep = null))
        assertTrue("parked at the greeting = auto-opened, never answered", InterviewFlag.shouldAutoComplete(1, isOpen = false, done = false, parkedStep = 0))
    }

    @Test fun `auto-open only with nothing learned anywhere`() {
        assertTrue(InterviewFlag.shouldAutoOpen(factCount = 0, done = false))
        assertFalse("facts from another device / chat: the pill nudges instead", InterviewFlag.shouldAutoOpen(factCount = 1, done = false))
        assertFalse(InterviewFlag.shouldAutoOpen(factCount = 0, done = true))
        assertFalse("parked ≠ pop back open on the next launch", InterviewFlag.shouldAutoOpen(factCount = 0, done = false, hasResumeStep = true))
    }

    @Test fun `the server flag pins done and closes an open panel, and never un-does`() {
        assertEquals(InterviewFlag.State(done = true, open = false), InterviewFlag.apply(serverDone = true, done = false, open = true))
        assertEquals(InterviewFlag.State(done = true, open = false), InterviewFlag.apply(serverDone = true, done = false, open = false))
        assertEquals(InterviewFlag.State(done = false, open = true), InterviewFlag.apply(serverDone = false, done = false, open = true))
        assertEquals(InterviewFlag.State(done = true, open = false), InterviewFlag.apply(serverDone = false, done = true, open = false))
    }

    // ── the server flag vs the ≥1-fact stand-down ──────────────────────────

    @Test fun `server says done - nothing to auto-complete`() {
        val done = InterviewFlag.interviewDoneFromServer("2026-09-05T10:00:00+00:00")
        assertFalse(InterviewFlag.shouldAutoOpen(factCount = 0, done = done))
        assertFalse("already done — nothing to auto-complete", InterviewFlag.shouldAutoComplete(5, isOpen = false, done = done))
    }

    @Test fun `a parked greeting still stands down on a chat fact`() {
        assertTrue(InterviewFlag.shouldAutoComplete(factCount = 1, isOpen = false, done = false, parkedStep = 0))
        assertFalse("mid-way parked: that fact may be its own answer", InterviewFlag.shouldAutoComplete(factCount = 1, isOpen = false, done = false, parkedStep = 2))
    }
}
