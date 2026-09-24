package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.InsightsWindow
import tech.csalliance.unstuck.core.logic.PeriodData
import tech.csalliance.unstuck.core.logic.PeriodRange
import tech.csalliance.unstuck.core.logic.earliestActivityDay
import tech.csalliance.unstuck.core.logic.goldenHours
import tech.csalliance.unstuck.core.logic.interruptionBins
import tech.csalliance.unstuck.core.logic.pauseAnatomy
import tech.csalliance.unstuck.core.logic.pauseBarLabel
import tech.csalliance.unstuck.core.logic.periodMinutes
import tech.csalliance.unstuck.core.logic.renderInsights
import tech.csalliance.unstuck.core.logic.seriesRhythm
import tech.csalliance.unstuck.core.logic.weekdayAreaBars
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.Instant
import java.time.ZoneId

/**
 * The analytics rules picked for ALL THREE platforms on 2026-09-24 (the
 * analytics review found web, iOS and Android disagreeing on each). Every
 * vector here is small and spelled out so the web (vitest) and iOS (XCTest)
 * copies can use the same inputs and expect the same outputs:
 *
 *  1. an area on a task that isn't in the user's area list is its own series,
 *     by name, after the user's areas — never folded into "No area" (web);
 *  2. repeating series order: kept desc, then days due so far desc, then name (web);
 *  3. (voice) the recap flag ends only when the app answers a real user turn —
 *     VoiceRealtimeClientTest / BargeInControllerTest (Android);
 *  4. a runaway session's START = completedAt − its REAL actual seconds (paused
 *     time isn't stored, so nothing more comes off), for golden hours and the
 *     interruptions chart (Android);
 *  5. "All time" starts at the earliest of a task created, a task done, a
 *     counted session's end (web);
 *  6. get_insights minutes = floor((sec + 30) / 60), the page's rounding.
 */
class CrossPlatformRulesTest {
    private val utc = ZoneId.of("UTC")

    private fun task(id: String, name: String, area: String? = null, done: Boolean = false, completedAt: String? = null,
                     createdAt: String = "2026-09-10T09:00:00Z", recurrence: Recurrence? = null, estimateMin: Int = 25) =
        TaskItem(id = id, name = name, estimateMin = estimateMin, totalFocused = 0, done = done, completedAt = completedAt,
            lifeArea = area, recurrence = recurrence, createdAt = createdAt, updatedAt = createdAt)

    private fun session(id: String, taskId: String?, sec: Int, end: String, est: Int? = null) =
        Session(id = id, taskId = taskId, taskName = "x", estimateMin = est, actualSec = sec, completedAt = end)

    private fun occ(id: String, taskId: String, date: String, done: Boolean = false, skipped: Boolean = false) =
        CalBlock(id = id, taskId = taskId, taskName = "x", startTime = "09:00", durationMinutes = 30, date = date,
            kind = CalBlockKind.TASK, done = done, skipped = skipped)

    // ── 1 ──
    @Test fun `1 an area off the user's list is its own series, by name, before No area`() {
        val tasks = listOf(task("w", "Report", "Work"), task("m", "Choir", "Music"), task("a", "Art", "Art"), task("n", "Loose end"))
        val sessions = listOf(
            session("s1", "w", 3600, "2026-09-21T10:00:00Z"),
            session("s2", "m", 1800, "2026-09-21T11:00:00Z"),
            session("s3", "a", 900, "2026-09-21T12:00:00Z"),
            session("s4", "n", 1800, "2026-09-21T13:00:00Z"),
        )
        assertEquals(listOf("Work", "Home", "Art", "Music", "No area"), weekdayAreaBars(sessions, tasks, listOf("Work", "Home")).series.map { it.name })
        val now = Instant.parse("2026-09-22T18:00:00Z").toEpochMilli()
        val out = renderInsights(tasks, sessions, emptyList(), emptyList(), emptyList(), now, InsightsWindow.WEEK, utc, areas = listOf("Work", "Home"))
        assertTrue(out, out.contains("By area: Work 1.0h, Art 0.3h, Music 0.5h, No area 0.5h."))
    }

    // ── 2 ──
    @Test fun `2 repeating series are ordered kept, then due so far, then name`() {
        val daily = Recurrence.Daily()
        val tasks = listOf(task("a", "Alpha", recurrence = daily), task("b", "Bravo", recurrence = daily), task("c", "Charlie", recurrence = daily))
        val blocks = listOf(
            // Alpha: 1 kept of 1 due (one day skipped on purpose).
            occ("a1", "a", "2026-09-14", done = true), occ("a2", "a", "2026-09-15", skipped = true),
            // Bravo: 1 kept of 2 due.
            occ("b1", "b", "2026-09-14", done = true), occ("b2", "b", "2026-09-15"),
            // Charlie: 2 kept of 2.
            occ("c1", "c", "2026-09-14", done = true), occ("c2", "c", "2026-09-15", done = true),
        )
        val r = PeriodRange("week_of", "2026-09-14", "2026-09-20", "2026-09-20", false, 7, "2026-09-07", "2026-09-13")
        val order = seriesRhythm(PeriodData(tasks, blocks, emptyList(), emptyList(), emptyList()), r).map { it.name }
        // Kept first (Charlie 2); then Alpha and Bravo tie on kept 1 — Bravo had
        // more days due, so it comes first, whatever the names say.
        assertEquals(listOf("Charlie", "Bravo", "Alpha"), order)
    }

    // ── 4 ──
    @Test fun `4 a runaway's start is its end minus its real length, for the interruptions chart`() {
        // Stopped at 08:00 on the 21st after 34 h (a 25-min plan → counts 85 min):
        // it STARTED at 22:00 on the 19th, not 85 min before it was stopped.
        val runaway = session("run", "t", 34 * 3600, "2026-09-21T08:00:00Z", est = 25)
        // A capture 20 min after the REAL start sits in the 18–21 min bin.
        val capture = cap("c", sessionId = "run", tag = CaptureTag.IDEA, at = "2026-09-19T22:20:00Z")
        val bins = interruptionBins(listOf(capture), listOf(runaway), 3, 10)
        assertEquals(1, bins[6])
        assertEquals(0, bins[0])
    }

    @Test fun `4b golden hours weigh a runaway's counted length at the hour it really started`() {
        // Nine 2-minute sessions starting 22:00 plus the runaway (started 22:00
        // on the 19th, counts 85 min) make the 10. From its clamped length it
        // would have "started" at 06:35 and outvoted 22:00.
        val runaway = session("run", "t", 34 * 3600, "2026-09-21T08:00:00Z", est = 25)
        val few = (0 until 9).map { session("n$it", "t", 120, "2026-09-%02dT22:02:00Z".format(10 + it)) }
        val g = goldenHours(few + runaway, Instant.parse("2026-09-22T12:00:00Z").toEpochMilli(), utc)!!
        assertEquals(listOf(22, 23), g.hours)
    }

    // ── 5 ──
    @Test fun `5 All time starts at the earliest task created, task done, or counted session`() {
        val base = listOf(task("t", "Later task", createdAt = "2026-09-10T09:00:00Z"))
        fun first(tasks: List<TaskItem>, sessions: List<Session>) = earliestActivityDay(PeriodData(tasks, emptyList(), sessions, emptyList(), emptyList()), utc)
        assertNull(first(emptyList(), emptyList()))
        assertEquals("2026-09-10", first(base, emptyList()))
        // A task finished before the oldest creation date we hold (a row created
        // on another device, its stamps skewed) moves the start back.
        val doneEarly = task("d", "Imported", done = true, completedAt = "2026-09-05T12:00:00Z", createdAt = "2026-09-12T09:00:00Z")
        assertEquals("2026-09-05", first(base + doneEarly, emptyList()))
        // A completedAt left on a task that's open again is not activity.
        assertEquals("2026-09-10", first(base + doneEarly.copy(done = false), emptyList()))
        // A counted session counts; an accidental sub-minute start doesn't.
        assertEquals("2026-09-08", first(base, listOf(session("s", null, 600, "2026-09-08T10:00:00Z"))))
        assertEquals("2026-09-10", first(base, listOf(session("s", null, 30, "2026-09-01T10:00:00Z"))))
        assertNull(first(emptyList(), listOf(session("s", null, 30, "2026-09-01T10:00:00Z"))))
    }

    // ── 6 ──
    @Test fun `6 get_insights minutes are floor((sec + 30) div 60)`() {
        assertEquals(0, periodMinutes(29)); assertEquals(1, periodMinutes(30)); assertEquals(1, periodMinutes(89)); assertEquals(2, periodMinutes(90))
        assertEquals(0, periodMinutes(-40))
        // Pause minutes come from the SUMMED seconds: 1 s + 69 s + 20 s = 90 s →
        // 2m. Summing per-pause fractions (1/60 + 69/60 + 20/60) gives
        // 1.4999999999999998 on doubles, which rounds to 1m.
        val logs = listOf(1, 69, 20).mapIndexed { i, d ->
            ReasonLog("p$i", reason = "phone", action = ReasonAction.PAUSE, at = "2026-09-21T1$i:00:00Z", durationSec = d)
        }
        val now = Instant.parse("2026-09-22T18:00:00Z").toEpochMilli()
        val out = renderInsights(emptyList(), emptyList(), emptyList(), logs, emptyList(), now, InsightsWindow.WEEK, utc)
        assertTrue(out, out.contains("Pauses: 3 reasons logged; top: phone 3x (2m)."))
        // The "What pauses you" card reads the same seconds with the same rule.
        val bar = pauseAnatomy(logs).single()
        assertEquals(90, bar.sec)
        assertEquals("2m · 3×", pauseBarLabel(bar, byMinutes = true))
        // Focus 99 min 30 s reads 1h 40m, like the page's Focused card.
        val t = task("w", "Report", "Work")
        val focus = renderInsights(listOf(t), listOf(session("a", "w", 5970, "2026-09-21T10:00:00Z")), emptyList(), emptyList(), emptyList(), now, InsightsWindow.WEEK, utc, areas = listOf("Work"))
        assertTrue(focus, focus.contains("Focus: 1h 40m across 1 session, median 1h 40m."))
    }
}
