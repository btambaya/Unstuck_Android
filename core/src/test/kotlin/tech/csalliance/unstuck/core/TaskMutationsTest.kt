package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.applyCompletion
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.isCompletedToday

// Ports TaskMutationsTests.swift: completion-stamp rules (lib/use-tasks.ts) +
// the isCompletedToday boundary cases (lib/task-completion.test.ts).
class TaskMutationsTest {
    private val now = "2026-05-21T12:00:00.000Z"

    @Test fun firstDoneFlipStampsNow() {
        val t = mkTask(id = "t", done = true, completedAt = null)
        val out = applyCompletion(t, mkTask(id = "t"), now)
        assertEquals(now, out.completedAt)
        assertEquals(now, out.updatedAt)
    }

    @Test fun keepsIncomingCompletedAt() {
        val explicit = "2026-05-20T08:00:00.000Z"
        val t = mkTask(id = "t", done = true, completedAt = explicit)
        assertEquals(explicit, applyCompletion(t, null, now).completedAt)
    }

    @Test fun preservesPriorTimestampOnRetoggle() {
        val original = "2026-05-19T09:00:00.000Z"
        val t = mkTask(id = "t", done = true, completedAt = null)
        val prior = mkTask(id = "t", done = true, completedAt = original)
        assertEquals(original, applyCompletion(t, prior, now).completedAt)
    }

    @Test fun uncompleteClearsTimestamp() {
        val t = mkTask(id = "t", done = false, completedAt = "2026-05-19T09:00:00.000Z")
        val prior = mkTask(id = "t", done = true, completedAt = "2026-05-19T09:00:00.000Z")
        assertNull(applyCompletion(t, prior, now).completedAt)
    }

    @Test fun bumpMoveCountIncrementsFromNull() {
        assertEquals(1, bumpMoveCount(mkTask(moveCount = null), now).moveCount)
    }

    @Test fun bumpMoveCountIncrementsExisting() {
        assertEquals(3, bumpMoveCount(mkTask(moveCount = 2), now).moveCount)
    }

    @Test fun bumpMoveCountSetsUpdatedAt() {
        assertEquals(now, bumpMoveCount(mkTask(moveCount = 0), now).updatedAt)
    }

    // isCompletedToday boundary — Wed 2026-05-20 10:00 local (UTC in CI).
    private val nowMs = localMillis(2026, 5, 20, 10, 0)

    @Test fun isCompletedTodayFalseWhenMissing() {
        assertFalse(isCompletedToday(mkTask(completedAt = null), nowMs))
    }

    @Test fun isCompletedTodayTrueAtMidnightToday() {
        assertTrue(isCompletedToday(mkTask(completedAt = localIso(2026, 5, 20, 0, 0)), nowMs))
    }

    @Test fun isCompletedTodayTrueJustBeforeMidnightTomorrow() {
        assertTrue(isCompletedToday(mkTask(completedAt = localIso(2026, 5, 20, 23, 59, 59)), nowMs))
    }

    @Test fun isCompletedTodayFalseLateYesterday() {
        assertFalse(isCompletedToday(mkTask(completedAt = localIso(2026, 5, 19, 23, 59, 59)), nowMs))
    }

    @Test fun isCompletedTodayFalseTheMomentTomorrowStarts() {
        assertFalse(isCompletedToday(mkTask(completedAt = localIso(2026, 5, 21, 0, 0)), nowMs))
    }
}
