package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.csalliance.unstuck.core.logic.FocusTimer

// P0-3 / D5 (2026-09-24): the pause's length goes back onto the reason log
// picked for it, so "What pauses you" and "How fast you come back" have real
// durations. No client wrote reason_logs.duration_sec before.
class PauseLengthTest {
    private val t0 = 1_000_000L
    private val running = FocusTimer.empty.copy(id = "s", taskId = "t", sessionStart = t0)

    @Test fun lengthIsResumeMinusPauseForThePickedReason() {
        val paused = FocusTimer.pause(running, t0 + 60_000).copy(pendingReasonId = "r1")
        assertEquals("r1" to 185, FocusTimer.pendingPauseLength(paused, t0 + 60_000 + 185_400))
    }

    @Test fun noReasonPickedNothingToWrite() {
        val paused = FocusTimer.pause(running, t0 + 60_000)
        assertNull(FocusTimer.pendingPauseLength(paused, t0 + 120_000))
        assertNull(FocusTimer.pendingPauseLength(running.copy(pendingReasonId = "r1"), t0 + 120_000))   // not paused
    }

    @Test fun resumeAndDoneClearTheReasonAndANewPauseStartsClean() {
        val paused = FocusTimer.pause(running, t0 + 60_000).copy(pendingReasonId = "r1")
        val resumed = FocusTimer.resume(paused, t0 + 120_000)
        assertNull(resumed.pendingReasonId)
        assertNull(FocusTimer.done(paused).pendingReasonId)
        // Pausing an already-paused session keeps its reason; a fresh pause starts without one.
        assertEquals("r1", FocusTimer.pause(paused, t0 + 90_000).pendingReasonId)
        assertNull(FocusTimer.pause(resumed.copy(pendingReasonId = "stale"), t0 + 200_000).pendingReasonId)
    }
}
