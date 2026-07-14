package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.core.model.FocusState
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.time.Time

// Ports FocusTimerTests.swift / lib/use-focus-timer.test.ts onto the pure
// engine, injecting `now` (epoch ms) so the scenarios are exact.
class FocusTimerTest {
    private val t0 = Time.parseMillis("2026-05-21T10:00:00.000Z")!!
    private fun grace(p: String? = null) = FocusTimer.overrunGraceSeconds(p)

    @Test fun formatMMSSPositive() {
        assertEquals("00:00", formatMMSS(0))
        assertEquals("01:05", formatMMSS(65))
        assertEquals("60:00", formatMMSS(3600))
    }

    @Test fun formatMMSSNegative() {
        assertEquals("-01:05", formatMMSS(-65))
    }

    @Test fun idleByDefault() {
        val live = FocusTimer.empty
        assertEquals(FocusState.IDLE, FocusTimer.deriveState(live, t0, grace()))
        assertEquals("", live.taskId)
    }

    @Test fun startTransitionsToRunning() {
        val live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 5, now = t0)
        assertEquals(FocusState.RUNNING, FocusTimer.deriveState(live, t0, grace()))
        assertEquals("task-1", live.taskId)
        assertEquals(300, FocusTimer.estimateSec(live))
    }

    // A fresh (own) start must CLEAR any prior shared marker so an own-task session
    // can't inherit the recipient-shared routing of a displaced shared session (T3).
    @Test fun startClearsAPriorSharedMarker() {
        val shared = FocusTimer.start(FocusTimer.empty, "owners-task", estimateMin = 25, now = t0)
            .copy(sharedTitle = "Ship it", sharedLevel = "partner")
        assertEquals("Ship it", shared.sharedTitle)
        val own = FocusTimer.start(shared, "my-task", estimateMin = 25, now = t0)
        assertEquals("my-task", own.taskId)
        assertNull(own.sharedTitle)
        assertNull(own.sharedLevel)
    }

    // A shared session's elapsed is CLAMPED to estimate + grace before it's credited
    // onto the OWNER, so a stale wall-clock resume can't dump hours onto their total.
    @Test fun clampSharedElapsedCapsAStaleSessionToEstimatePlusGrace() {
        // 25m estimate → cap = 25*60 + 600 (10m grace) = 2100s.
        assertEquals(2100, FocusTimer.clampSharedElapsedSec(9999, estimateMin = 25))
        // Under the cap passes through untouched.
        assertEquals(300, FocusTimer.clampSharedElapsedSec(300, estimateMin = 25))
        // Non-positive / zero-estimate degrade safely (0-estimate → default 25m cap).
        assertEquals(0, FocusTimer.clampSharedElapsedSec(0, estimateMin = 25))
        assertEquals(0, FocusTimer.clampSharedElapsedSec(-5, estimateMin = 25))
        assertEquals(2100, FocusTimer.clampSharedElapsedSec(9999, estimateMin = 0))
    }

    // Resuming a paused shared session KEEPS the marker (a paused shared stays shared).
    @Test fun resumeKeepsTheSharedMarker() {
        val shared = FocusTimer.start(FocusTimer.empty, "owners-task", estimateMin = 25, now = t0)
            .copy(sharedTitle = "Ship it", sharedLevel = "partner")
        val paused = FocusTimer.pause(shared, t0 + 60_000)
        val resumed = FocusTimer.resume(paused, t0 + 120_000)
        assertEquals("Ship it", resumed.sharedTitle)
        assertEquals("partner", resumed.sharedLevel)
    }

    @Test fun pauseThenResume() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 5, now = t0)
        live = FocusTimer.pause(live, t0 + 1000)
        assertEquals(FocusState.PAUSE, FocusTimer.deriveState(live, t0 + 1000, grace()))
        live = FocusTimer.resume(live, t0 + 2000)
        assertEquals(FocusState.RUNNING, FocusTimer.deriveState(live, t0 + 2000, grace()))
    }

    @Test fun cancelBackToIdle() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 5, now = t0)
        live = FocusTimer.cancel(live)
        assertEquals(FocusState.IDLE, FocusTimer.deriveState(live, t0, grace()))
        assertEquals("", live.taskId)
    }

    @Test fun extendAddsMinutes() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 5, now = t0)
        live = FocusTimer.extend(live, 10)
        assertEquals(15 * 60, FocusTimer.estimateSec(live))
    }

    @Test fun setTreatmentPersists() {
        val live = FocusTimer.setTreatment(FocusTimer.empty, FocusTreatment.MONK)
        assertEquals(FocusTreatment.MONK, live.treatment)
    }

    @Test fun startOnSamePausedTaskResumesWithoutResettingElapsed() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, now = t0)
        val atPauseNow = t0 + 5 * 60_000
        live = FocusTimer.pause(live, atPauseNow)
        val elapsedAtPause = FocusTimer.elapsedSec(live, atPauseNow)
        assertEquals(300, elapsedAtPause)

        val resumeNow = atPauseNow + 15 * 60_000
        live = FocusTimer.start(live, "task-1", estimateMin = 25, now = resumeNow)
        assertEquals(FocusState.RUNNING, FocusTimer.deriveState(live, resumeNow, grace()))
        assertEquals(elapsedAtPause, FocusTimer.elapsedSec(live, resumeNow))
    }

    @Test fun startOnDifferentTaskStartsFresh() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, now = t0)
        live = FocusTimer.pause(live, t0 + 3 * 60_000)
        val freshNow = t0 + 3 * 60_000
        live = FocusTimer.start(live, "task-2", estimateMin = 25, now = freshNow)
        assertEquals("task-2", live.taskId)
        assertEquals(FocusState.RUNNING, FocusTimer.deriveState(live, freshNow, grace()))
        assertTrue(FocusTimer.elapsedSec(live, freshNow) <= 1)
    }

    @Test fun startOnSameRunningTaskIsNoOp() {
        val live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, now = t0)
        val elapsedBefore = FocusTimer.elapsedSec(live, t0 + 2 * 60_000)
        val live2 = FocusTimer.start(live, "task-1", estimateMin = 25, now = t0 + 2 * 60_000)
        assertEquals(elapsedBefore, FocusTimer.elapsedSec(live2, t0 + 2 * 60_000))
        assertEquals(live.sessionStart, live2.sessionStart)
    }

    @Test fun elapsedDoesNotAdvanceWhilePausedAndSurvivesResume() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, now = t0)
        val pauseNow = t0 + 5 * 60_000
        live = FocusTimer.pause(live, pauseNow)
        val elapsedAtPause = FocusTimer.elapsedSec(live, pauseNow)
        assertEquals(300, elapsedAtPause)
        assertEquals(elapsedAtPause, FocusTimer.elapsedSec(live, pauseNow + 60_000))

        val resumeNow = pauseNow + 60_000
        live = FocusTimer.resume(live, resumeNow)
        assertEquals(elapsedAtPause, FocusTimer.elapsedSec(live, resumeNow))
        assertEquals(elapsedAtPause + 10, FocusTimer.elapsedSec(live, resumeNow + 10_000))
    }

    @Test fun startWithPriorSeedsDisplayed() {
        val live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, priorAccumulatedSec = 600, now = t0)
        assertTrue(FocusTimer.elapsedSec(live, t0) <= 1)
        assertEquals(600, FocusTimer.displayedElapsedSec(live, t0))
        assertEquals(600, live.priorAccumulatedSec)
    }

    @Test fun defaultsPriorToZero() {
        val live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, now = t0)
        assertEquals(0, live.priorAccumulatedSec)
        assertEquals(FocusTimer.elapsedSec(live, t0), FocusTimer.displayedElapsedSec(live, t0))
    }

    @Test fun pauseResumePreservesPrior() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, priorAccumulatedSec = 300, now = t0)
        live = FocusTimer.pause(live, t0 + 1000)
        assertEquals(300, live.priorAccumulatedSec)
        live = FocusTimer.resume(live, t0 + 2000)
        assertEquals(300, live.priorAccumulatedSec)
    }

    @Test fun overrunFiresOnDisplayedNotRawElapsed() {
        val g = grace("5 min")
        val live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, priorAccumulatedSec = 24 * 60, now = t0)
        assertEquals(FocusState.RUNNING, FocusTimer.deriveState(live, t0, g))
        assertEquals(FocusState.OVERRUN, FocusTimer.deriveState(live, t0 + 7 * 60_000, g))
    }

    @Test fun doneClearsSessionStartAndResetsElapsed() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, priorAccumulatedSec = 600, now = t0)
        assertTrue(FocusTimer.elapsedSec(live, t0) <= 1)
        live = FocusTimer.done(live)
        assertNull(live.sessionStart)
        assertEquals(0, FocusTimer.elapsedSec(live, t0 + 99_000))
    }

    @Test fun startOnSamePausedTaskIgnoresPrior() {
        var live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 25, now = t0)
        live = FocusTimer.pause(live, t0 + 1000)
        live = FocusTimer.start(live, "task-1", estimateMin = 25, priorAccumulatedSec = 9999, now = t0 + 2000)
        assertEquals(0, live.priorAccumulatedSec)
        assertEquals(FocusState.RUNNING, FocusTimer.deriveState(live, t0 + 2000, grace()))
    }

    @Test fun neverGraceMeansNeverOverrun() {
        val live = FocusTimer.start(FocusTimer.empty, "task-1", estimateMin = 1, now = t0)
        assertEquals(FocusState.RUNNING, FocusTimer.deriveState(live, t0 + 60 * 60_000, grace("Never")))
    }
}
