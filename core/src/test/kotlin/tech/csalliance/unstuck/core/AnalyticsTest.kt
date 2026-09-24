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
import tech.csalliance.unstuck.core.logic.comebackBins
import tech.csalliance.unstuck.core.logic.hourDayHeatmap
import tech.csalliance.unstuck.core.logic.NO_AREA_LABEL
import tech.csalliance.unstuck.core.logic.INTERRUPTIONS_MIN_LINKED
import tech.csalliance.unstuck.core.logic.hourLabel
import tech.csalliance.unstuck.core.logic.usableToday
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.logic.slipping
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

    @Test fun interruptionBinsZeroBinCountIsEmptyNotCrash() {
        // binCount <= 0 must not throw (NegativeArraySize / AIOOBE on bins[-1]).
        val completedMs = Time.parseMillis("2026-01-01T10:30:00.000Z")!!
        val startMs = completedMs - 30 * 60 * 1000
        val s = sess("sess", "t", 30 * 60, "2026-01-01T10:30:00.000Z")
        val c = cap("c", sessionId = "sess", tag = CaptureTag.FOLLOW_UP, at = iso(startMs + 10 * 60_000))
        assertEquals(emptyList<Int>(), interruptionBins(listOf(c), listOf(s), 3, 0))
        assertEquals(emptyList<Int>(), interruptionBins(listOf(c), listOf(s), 3, -1))
    }

    // D5 — "How fast you come back" = pause → resume, from reason-log lengths.
    @Test fun comebackBinsFromPauseLengths() {
        fun r(id: String, sec: Int?) = ReasonLog(id, reason = "Drink", action = ReasonAction.PAUSE, at = "2026-05-21T10:00:00.000Z", durationSec = sec)
        val bins = comebackBins(listOf(r("a", 120), r("b", 299), r("c", 300), r("d", 20 * 60), r("e", 2 * 3600), r("f", null), r("g", 0)))
        assertEquals(listOf(2, 1, 0, 1, 0, 1), bins)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), comebackBins(emptyList()))
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

    // P0-4: a repeating series is never "slipping" (it is never done), nor is a
    // task parked in Later on purpose; and the list isn't capped at 6.
    @Test fun slippingSkipsRepeatingSeriesAndLaterAndIsNotCapped() {
        val now = System.currentTimeMillis()
        val old = iso(now - 40 * DAY_MS)
        val series = mkTask(id = "r", name = "Stretch", createdAt = old).copy(recurrence = Recurrence.Daily())
        val later = mkTask(id = "l", name = "Someday", createdAt = old, later = true)
        val real = (1..8).map { mkTask(id = "t$it", name = "Real $it", createdAt = old) }
        val out = slipping(listOf(series, later) + real, now)
        assertEquals(8, out.size)
        assertTrue(out.none { it.name == "Stretch" || it.name == "Someday" })
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

    // P0-2 — 7 days × 24 hours by the hours the session spanned (weekends and
    // evenings included), minutes, counted length only.
    @Test fun hourDayHeatmapSpansHoursAndCoversWeekendsAndNights() {
        val utc = java.time.ZoneId.of("UTC")
        val grid = hourDayHeatmap(listOf(
            sess("s1", "t", 3600, "2026-05-23T22:30:00.000Z"),   // Sat 21:30–22:30
            sess("s2", "t", 1800, "2026-05-19T08:00:00.000Z"),   // Tue 07:30–08:00
            sess("s3", "t", 30, "2026-05-19T09:00:00.000Z"),     // accidental — ignored
        ), utc)
        assertEquals(7, grid.size); assertEquals(24, grid[0].size)
        assertEquals(30.0, grid[5][21], eps)
        assertEquals(30.0, grid[5][22], eps)
        assertEquals(30.0, grid[1][7], eps)
        assertEquals(90.0, grid.sumOf { it.sum() }, eps)
    }

    @Test fun hourDayHeatmapPlacesARunawayFromItsStartForItsCountedLength() {
        val utc = java.time.ZoneId.of("UTC")
        // Started Mon 09:00, left running 30 h (est 25 → counts 85 min).
        val grid = hourDayHeatmap(listOf(sess("s", "t", 30 * 3600, "2026-05-19T15:00:00.000Z").copy(estimateMin = 25)), utc)
        assertEquals(60.0, grid[0][9], eps)
        assertEquals(25.0, grid[0][10], eps)
        assertEquals(85.0, grid.sumOf { it.sum() }, eps)
    }

    @Test fun hourLabels() {
        assertEquals(listOf("12am", "6am", "12pm", "6pm", "11pm"), listOf(0, 6, 12, 18, 23).map { hourLabel(it) })
    }

    // P0-5 — the "No area" series keeps no-task and no-area focus on the chart.
    @Test fun weekdayAreaHoursNoAreaSeries() {
        val tasks = listOf(mkTask(id = "t1", lifeArea = "Work"), mkTask(id = "t2"), mkTask(id = "t3", lifeArea = "Gone"))
        val sessions = listOf(
            sess("s1", "t1", 3600, "2026-05-19T10:00:00.000Z"),   // Tue, Work
            sess("s2", "t2", 1800, "2026-05-19T11:00:00.000Z"),   // Tue, no area
            sess("s3", null, 1800, "2026-05-19T12:00:00.000Z"),   // Tue, no task
            sess("s4", "t3", 1800, "2026-05-19T13:00:00.000Z"),   // Tue, a removed area
        )
        val out = weekdayAreaHours(sessions, tasks, listOf("Work"), withNoArea = true)
        assertEquals(2, out[1].data.size)
        assertEquals(1.0, out[1].data[0], eps)
        assertEquals(1.5, out[1].data[1], eps)
        assertEquals("No area", NO_AREA_LABEL)
        // Without the series those minutes drop out (old behaviour, kept for callers that want it).
        assertEquals(1, weekdayAreaHours(sessions, tasks, listOf("Work")).first().data.size)
    }

    // P0-7 — sub-minute sessions never count anywhere.
    @Test fun accidentalStartsDontReachTheCharts() {
        val tasks = listOf(mkTask(id = "t1", estimateMin = 25, lifeArea = "Work"))
        val sessions = (0 until 5).map { sess("s$it", "t1", 20, "2026-05-18T10:0$it:00.000Z") }
        assertTrue(calibrationDots(sessions, tasks).isEmpty())
        assertEquals(0.0, weekdayAreaHours(sessions, tasks, listOf("Work")).sumOf { it.data.sum() }, eps)
        assertTrue(topInsights(sessions, tasks, emptyList(), emptyList()).none { it.title.contains("strongest") })
    }

    // P0-11 — a capture from before a pause clamps into the first bin.
    @Test fun interruptionBinsClampNegativeOffsets() {
        val s = sess("sess", "t", 10 * 60, "2026-01-01T10:30:00.000Z")        // inferred start 10:20
        val c = cap("c", sessionId = "sess", tag = CaptureTag.IDEA, at = "2026-01-01T10:05:00.000Z")
        assertEquals(1, interruptionBins(listOf(c), listOf(s), 3, 10)[0])
        assertEquals(3, INTERRUPTIONS_MIN_LINKED)
    }

    // A forgotten timer (30 h, 25-min plan → counts 85 min) places its captures
    // from its REAL start, like the heatmap — not from its clamped length, which
    // put a capture 20 min in "hours before the start" and into bin 0.
    @Test fun interruptionBinsUseTheRealStartOfARunaway() {
        val end = Time.parseMillis("2026-01-02T16:00:00.000Z")!!
        val s = sess("run", "t", 30 * 3600, iso(end)).copy(estimateMin = 25)
        val c = cap("c", sessionId = "run", tag = CaptureTag.IDEA, at = iso(end - 30 * 3600_000L + 20 * 60_000))
        val bins = interruptionBins(listOf(c), listOf(s), 3, 10)
        assertEquals(1, bins[6])
        assertEquals(0, bins[0])
        // An accidental start links nothing.
        val tiny = sess("tiny", "t", 30, "2026-01-01T10:30:00.000Z")
        assertEquals(0, interruptionBins(listOf(cap("c2", sessionId = "tiny", tag = CaptureTag.IDEA, at = "2026-01-01T10:30:00.000Z")), listOf(tiny), 3, 10).sum())
    }

    // P0-10 — usable time excludes blocks already done or skipped.
    @Test fun usableExcludesDoneAndSkipped() {
        fun b(id: String, mins: Int, done: Boolean = false, skipped: Boolean = false) =
            CalBlock(id = id, taskId = "t", taskName = "x", startTime = "09:00", durationMinutes = mins, date = "2026-05-21", done = done, skipped = skipped)
        assertEquals(20, usableToday(listOf(b("a", 20), b("b", 30, done = true), b("c", 30, skipped = true)), "2026-05-21").usableMins)
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
