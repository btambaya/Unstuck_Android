package tech.csalliance.unstuck.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.InsightsSpan
import tech.csalliance.unstuck.core.logic.MAX_COUNTED_SEC
import tech.csalliance.unstuck.core.logic.PeriodData
import tech.csalliance.unstuck.core.logic.PeriodReviewArgs
import tech.csalliance.unstuck.core.logic.SeriesState
import tech.csalliance.unstuck.core.logic.countableSessions
import tech.csalliance.unstuck.core.logic.countedSec
import tech.csalliance.unstuck.core.logic.earliestActivityDay
import tech.csalliance.unstuck.core.logic.insightsComparisonLabel
import tech.csalliance.unstuck.core.logic.insightsFacts
import tech.csalliance.unstuck.core.logic.insightsPeriodLabel
import tech.csalliance.unstuck.core.logic.insightsRange
import tech.csalliance.unstuck.core.logic.insightsTrend
import tech.csalliance.unstuck.core.logic.neutralDelta
import tech.csalliance.unstuck.core.logic.neutralDurDelta
import tech.csalliance.unstuck.core.logic.renderPeriodReview
import tech.csalliance.unstuck.core.logic.sessionCapSec
import tech.csalliance.unstuck.core.logic.thisWeekFocusSec
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.Instant
import java.time.ZoneId

// The Insights page's periodFacts on the shared dataset A: the page's numbers
// must be the review's numbers for the same period (analytics plan D2/D3),
// plus the D1 session filter.
class PeriodFactsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-09-24T15:30:00.000Z").toEpochMilli()

    private fun dataA(extraSessions: List<Session> = emptyList()): PeriodData {
        val d = json.parseToJsonElement(PeriodReviewVectors.JSON).jsonObject["datasets"]!!.jsonObject["A"]!!.jsonObject
        return PeriodData(
            json.decodeFromJsonElement(ListSerializer(TaskItem.serializer()), d["tasks"]!!),
            json.decodeFromJsonElement(ListSerializer(CalBlock.serializer()), d["blocks"]!!),
            json.decodeFromJsonElement(ListSerializer(Session.serializer()), d["sessions"]!!) + extraSessions,
            json.decodeFromJsonElement(ListSerializer(Capture.serializer()), d["captures"]!!),
            json.decodeFromJsonElement(ListSerializer(ReasonLog.serializer()), d["reasons"]!!),
        )
    }

    private fun s(id: String, sec: Int, est: Int?, at: String, taskId: String? = null) =
        Session(id = id, taskId = taskId, taskName = "x", estimateMin = est, actualSec = sec, completedAt = at)

    // ── D1 ──
    @Test fun capIsThreeTimesOrPlusAnHourWithAFourHourCeiling() {
        assertEquals(85 * 60, sessionCapSec(25))          // max(75, 85)
        assertEquals(240 * 60, sessionCapSec(90))         // max(270, 150) → 4 h
        assertEquals(180 * 60, sessionCapSec(60))         // max(180, 120)
        assertEquals(MAX_COUNTED_SEC, sessionCapSec(null))
        assertEquals(MAX_COUNTED_SEC, sessionCapSec(0))
    }

    @Test fun subMinuteSessionsDropAndRunawaysClamp() {
        val raw = listOf(
            s("a", 5, 25, "2026-09-22T10:00:00Z"),
            s("b", 59, 25, "2026-09-22T10:00:00Z"),
            s("c", 60, 25, "2026-09-22T10:00:00Z"),
            s("d", 143 * 3600, 45, "2026-09-22T10:00:00Z"),
            s("e", 30 * 3600, null, "2026-09-22T10:00:00Z"),
        )
        val kept = countableSessions(raw)
        assertEquals(listOf("c", "d", "e"), kept.map { it.id })
        assertEquals(60, kept[0].actualSec)
        assertEquals(sessionCapSec(45), kept[1].actualSec)
        assertEquals(MAX_COUNTED_SEC, kept[2].actualSec)
        assertEquals(0, countedSec(raw[0]))
        assertEquals(kept, countableSessions(kept))   // idempotent
    }

    @Test fun theReviewAppliesTheFilterToo() {
        // Maya's week (App Review demo): 5 s, 19 s and 37 s of accidental starts.
        val out = renderPeriodReview(
            PeriodReviewArgs("today", null, null, null),
            emptyList(), emptyList(),
            listOf(s("a", 5, 25, "2026-09-24T09:00:00Z"), s("b", 19, 25, "2026-09-24T10:00:00Z"), s("c", 37, 25, "2026-09-24T11:00:00Z")),
            emptyList(), emptyList(), now, utc,
        )
        assertTrue(out, out.contains("nothing recorded"))
    }

    // ── the page = the review ──
    @Test fun lastWeekMatchesTheReviewVector() {
        val data = dataA()
        val r = insightsRange(InsightsSpan.WEEK, -1, "2026-09-24", null)
        assertEquals("2026-09-14", r.from); assertEquals("2026-09-20", r.end); assertFalse(r.clipped)
        val f = insightsFacts(data, r, now, utc)
        // "Done: 3 tasks … plus 6 repeating check-offs", "5 sessions, 2h 50m", "7 of 11 planned done"
        assertEquals(9, f.doneCount)
        assertEquals(170 * 60, f.focusSec)
        assertEquals(5, f.sessionCount)
        assertEquals(7, f.plan!!.doneToPlan)
        assertEquals(11, f.plan!!.planned)
        assertEquals(1, f.plan!!.skipped)
        assertEquals(1, f.plan!!.deadlines.size)
        assertEquals(6, f.showedUp); assertEquals(7, f.daysSoFar)
        // "Before that: done 9 vs 4 (+5), focus 2h 50m vs 1h 5m (+1h 45m), sessions 5 vs 2"
        assertEquals(4, f.prev!!.doneCount)
        assertEquals("+5", neutralDelta(f.doneCount - f.prev!!.doneCount))
        assertEquals("+1h 45m", neutralDurDelta(f.focusSec, f.prev!!.focusSec))
        assertEquals(listOf("Health" to 6, "Home" to 1, "Work" to 1), f.doneByArea)
        assertEquals(1, f.doneNoArea)
        // Daily rhythm: Wed 16 Sep = 3 done, 10 m focus (the review's busiest day).
        val wed = f.days.first { it.date == "2026-09-16" }
        assertEquals(3, wed.done); assertEquals(600, wed.focusSec)
        assertEquals(7, f.days.size); assertTrue(f.days.none { it.future })
        // Repeating rhythm: Stretch kept 5 of 6 (one open; the day skipped on purpose isn't owed).
        val stretch = f.series.first()
        assertEquals("Stretch", stretch.name)
        assertEquals(listOf(SeriesState.DONE, SeriesState.DONE, SeriesState.DONE, SeriesState.DONE, SeriesState.DONE, SeriesState.SKIPPED, SeriesState.OPEN), stretch.dots.map { it.state })
        assertEquals(5, stretch.kept); assertEquals(6, stretch.soFar)
        assertEquals("Walk the dog", f.series[1].name)
        assertTrue(f.wins.isEmpty())
        assertEquals("Last week", insightsPeriodLabel(r, "2026-09-24"))
        assertEquals("vs the week before", insightsComparisonLabel(r, "2026-09-24"))
    }

    @Test fun thisWeekSoFar() {
        val data = dataA()
        val r = insightsRange(InsightsSpan.WEEK, 0, "2026-09-24", null)
        assertTrue(r.clipped); assertEquals("2026-09-24", r.end); assertEquals("2026-09-27", r.to)
        val f = insightsFacts(data, r, now, utc)
        assertEquals(5, f.doneCount)
        assertEquals(65 * 60, f.focusSec)
        assertEquals(3, f.plan!!.doneToPlan); assertEquals(4, f.plan!!.planned)
        assertEquals(2, f.stillOpenToday)
        assertEquals(7, f.prev!!.doneCount)
        assertEquals(4, f.showedUp); assertEquals(4, f.daysSoFar)
        assertEquals(3, f.days.count { it.future })
        // Gym bag waited 9 days before it got done: a win.
        assertEquals(listOf("Gym bag" to 9), f.wins.map { it.task.name to it.waitedDays })
        val stretch = f.series.first { it.name == "Stretch" }
        assertEquals(listOf(SeriesState.DONE, SeriesState.DONE, SeriesState.OPEN, SeriesState.UPCOMING), stretch.dots.map { it.state })
        assertEquals("This week", insightsPeriodLabel(r, "2026-09-24"))
        assertEquals("vs the same point the week before", insightsComparisonLabel(r, "2026-09-24"))
        // The Today pill reads the same number.
        assertEquals(f.focusSec, thisWeekFocusSec(data, now, utc))
    }

    @Test fun pillIgnoresAccidentalStartsAndCapsRunaways() {
        val data = dataA(listOf(s("z1", 30, 25, "2026-09-23T10:00:00Z"), s("z2", 20 * 3600, 25, "2026-09-23T12:00:00Z")))
        assertEquals(65 * 60 + 85 * 60, thisWeekFocusSec(data, now, utc))
    }

    @Test fun monthAndAllTime() {
        val data = dataA()
        val m = insightsRange(InsightsSpan.MONTH, 0, "2026-09-24", null)
        assertEquals("2026-09-01", m.from); assertEquals("2026-08-24", m.prevTo)
        val f = insightsFacts(data, m, now, utc)
        assertEquals(18, f.doneCount)                  // 7 tasks + 11 check-offs
        assertEquals(300 * 60, f.focusSec)             // "9 sessions, 5h in all"
        assertEquals(30, f.days.size)
        assertEquals("This month", insightsPeriodLabel(m, "2026-09-24"))
        assertEquals("August", insightsPeriodLabel(insightsRange(InsightsSpan.MONTH, -1, "2026-09-24", null), "2026-09-24"))
        val first = earliestActivityDay(data, utc)
        assertEquals("2026-09-01", first)
        val all = insightsRange(InsightsSpan.ALL, 0, "2026-09-24", first)
        val fa = insightsFacts(data, all, now, utc)
        assertNull(fa.prev); assertNull(fa.plan)
        assertEquals(24, fa.daysSoFar)
        assertEquals(9, fa.sessionCount)
    }

    @Test fun trendEndsAtTheSelectedWeek() {
        val bars = insightsTrend(dataA(), InsightsSpan.WEEK, -1, now, utc)
        assertEquals(8, bars.size)
        assertEquals("2026-09-14", bars.last().from); assertTrue(bars.last().selected)
        assertEquals(9, bars.last().done); assertEquals(170 * 60, bars.last().focusSec)
        assertEquals(4, bars[6].done); assertEquals(65 * 60, bars[6].focusSec)
        assertEquals(6, insightsTrend(dataA(), InsightsSpan.MONTH, 0, now, utc).size)
    }

    @Test fun neutralDeltas() {
        assertEquals("same", neutralDelta(0))
        assertEquals("−3", neutralDelta(-3))
        assertEquals("−40m", neutralDurDelta(20 * 60, 60 * 60))
        assertEquals("same", neutralDurDelta(61, 89))
    }
}
