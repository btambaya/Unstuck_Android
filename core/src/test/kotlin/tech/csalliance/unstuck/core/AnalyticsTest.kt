package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.calibrationDots
import tech.csalliance.unstuck.core.logic.calibrationHitRate
import tech.csalliance.unstuck.core.logic.captureBreakdown
import tech.csalliance.unstuck.core.logic.dayOfWeekIdx
import tech.csalliance.unstuck.core.logic.interruptionBins
import tech.csalliance.unstuck.core.logic.pauseAnatomy
import tech.csalliance.unstuck.core.logic.reEntryDistribution
import tech.csalliance.unstuck.core.logic.slipping
import tech.csalliance.unstuck.core.logic.timeOfDayHeatmap
import tech.csalliance.unstuck.core.logic.topInsights
import tech.csalliance.unstuck.core.logic.weekdayAreaHours
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.time.DAY_MS
import tech.csalliance.unstuck.core.time.Time

// Ported 1:1 from AnalyticsTests.swift / lib/analytics.test.ts.
class AnalyticsTest {
    private val eps = 1e-9

    @Test fun dayOfWeekIdxMondayAnchored() {
        assertEquals(0, dayOfWeekIdx(Time.civil(2026, 5, 18))) // Mon
        assertEquals(1, dayOfWeekIdx(Time.civil(2026, 5, 19))) // Tue
        assertEquals(6, dayOfWeekIdx(Time.civil(2026, 5, 24))) // Sun
    }

    @Test fun weekdayAreaHoursGroups() {
        val tasks = listOf(mkTask(id = "t1", lifeArea = "Work"), mkTask(id = "t2", lifeArea = "Personal"))
        val sessions = listOf(
            sess("s1", "t1", 3600, "2026-05-19T10:00:00.000Z"), // Tue
            sess("s2", "t2", 1800, "2026-05-20T14:00:00.000Z"), // Wed
        )
        val out = weekdayAreaHours(sessions, tasks)
        assertEquals(1.0, out[1].data[0], eps)
        assertEquals(0.5, out[2].data[1], eps)
    }

    @Test fun calibrationHitRateOneWithinSlack() {
        val tasks = listOf(mkTask(id = "t1", estimateMin = 25))
        val sessions = listOf(
            sess("s1", "t1", 25 * 60, "2026-05-21T10:00:00.000Z"),
            sess("s2", "t1", 27 * 60, "2026-05-21T09:59:59.000Z"),
        )
        val dots = calibrationDots(sessions, tasks)
        assertEquals(2, dots.size)
        assertEquals(1.0, calibrationHitRate(dots, 5), eps)
    }

    @Test fun calibrationHitRateZeroWhenMissBy10() {
        val tasks = listOf(mkTask(id = "t1", estimateMin = 25))
        val sessions = listOf(sess("s1", "t1", 50 * 60, "2026-05-21T10:00:00.000Z"))
        assertEquals(0.0, calibrationHitRate(calibrationDots(sessions, tasks)), eps)
    }

    @Test fun interruptionBinsCaptureLands() {
        val completedMs = Time.parseMillis("2026-01-01T10:30:00.000Z")!!
        val startMs = completedMs - 30 * 60 * 1000
        val s = sess("sess", "t", 30 * 60, "2026-01-01T10:30:00.000Z")
        val c = cap("c", sessionId = "sess", tag = CaptureTag.FOLLOW_UP, at = iso(startMs + 10 * 60_000))
        val bins = interruptionBins(listOf(c), listOf(s), 3, 10)
        assertEquals(1, bins[3])
    }

    @Test fun pauseAnatomyAggregatesMinutesFallsBackToCount() {
        val logs = listOf(
            ReasonLog("1", reason = "Bathroom", action = ReasonAction.PAUSE, at = "2026-05-21T10:00:00.000Z", durationSec = 120),
            ReasonLog("2", reason = "Bathroom", action = ReasonAction.PAUSE, at = "2026-05-21T10:00:00.000Z", durationSec = 240),
            ReasonLog("3", reason = "Drink", action = ReasonAction.PAUSE, at = "2026-05-21T10:00:00.000Z"),
        )
        val rows = pauseAnatomy(logs)
        assertEquals("Bathroom", rows[0].reason)
        assertEquals(2, rows[0].count)
        assertEquals(6.0, rows[0].minutes, eps)
        assertEquals("Drink", rows[1].reason)
        assertEquals(1, rows[1].count)
        assertEquals(0.0, rows[1].minutes, eps)
    }

    @Test fun pauseAnatomyCapsAt6() {
        val logs = (0 until 10).map {
            ReasonLog("$it", reason = "R$it", action = ReasonAction.PAUSE, at = "2026-05-21T10:00:00.000Z", durationSec = 60)
        }
        assertEquals(6, pauseAnatomy(logs).size)
    }

    @Test fun reEntryDistributionTenMinGap() {
        val startMs = Time.parseMillis("2026-01-01T10:00:00.000Z")!!
        val sessions = listOf(
            sess("s1", "t", 25 * 60, iso(startMs + 25 * 60_000)),
            sess("s2", "t", 25 * 60, iso(startMs + (25 + 35) * 60_000)),
        )
        assertEquals(1, reEntryDistribution(sessions, 5, 12)[2])
    }

    @Test fun slippingFlagsOlderThan21Days() {
        val now = System.currentTimeMillis()
        val old = iso(now - 30 * DAY_MS)
        assertEquals(1, slipping(listOf(mkTask(id = "t1", name = "Old", createdAt = old))).size)
    }

    @Test fun slippingFlagsRescheduled3Plus() {
        assertEquals(1, slipping(listOf(mkTask(id = "t1", name = "Moved", moveCount = 4))).size)
    }

    @Test fun slippingIgnoresDone() {
        assertEquals(0, slipping(listOf(mkTask(id = "t1", done = true, moveCount = 5))).size)
    }

    @Test fun captureBreakdownCountsByTag() {
        val captures = listOf(
            cap("1", tag = CaptureTag.FOLLOW_UP, at = "2026-05-21T10:00:00.000Z"),
            cap("2", tag = CaptureTag.FOLLOW_UP, at = "2026-05-21T10:00:00.000Z"),
            cap("3", tag = CaptureTag.DISTRACTION, at = "2026-05-21T10:00:00.000Z"),
        )
        val out = captureBreakdown(captures)
        assertEquals(2, out[CaptureTag.FOLLOW_UP])
        assertEquals(1, out[CaptureTag.DISTRACTION])
        assertEquals(0, out[CaptureTag.IDEA])
    }

    @Test fun timeOfDayHeatmapSkipsWeekendsAndClamps() {
        val sessions = listOf(
            sess("s1", "t", 3600, "2026-05-23T10:00:00.000Z"), // Sat — skipped
            sess("s2", "t", 1800, "2026-05-19T08:00:00.000Z"), // Tue 8am bucket 0
        )
        val grid = timeOfDayHeatmap(sessions)
        assertEquals(0.5, grid[1][0], eps)
        assertEquals(5, grid.size)
    }

    @Test fun topInsightsEmptyDataNoFallbacks() {
        assertEquals(emptyList<Any>(), topInsights(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test fun topInsightsSurfacesSlippingTask() {
        val out = topInsights(emptyList(), listOf(mkTask(name = "Old slip", moveCount = 5)), emptyList(), emptyList())
        assertTrue(out.any { it.title.contains("Old slip") })
    }

    @Test fun topInsightsRichDataSurfacesWeekdayAndCalibration() {
        val tasks = listOf(mkTask(id = "t1", estimateMin = 25))
        val sessions = (0 until 5).map { sess("s$it", "t1", 25 * 60, "2026-05-18T10:0$it:00.000Z") } // Mon
        val out = topInsights(sessions, tasks, emptyList(), emptyList())
        assertTrue(out.any { it.title.contains("strongest day") })
        assertTrue(out.any { it.title.contains("Estimates within 5 min") })
    }
}
