package tech.csalliance.unstuck.soak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.csalliance.unstuck.core.logic.PeriodData
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.ui.today.WeekPill
import tech.csalliance.unstuck.ui.today.weekPill
import java.time.Instant
import java.time.ZoneId

/**
 * The Today header's week pill (analytics D3, 2026-09-24): THIS week, Monday
 * 00:00 onwards, from the shared periodFacts engine over the D1-filtered
 * sessions — the same number Insights shows for This week. It used to be a
 * rolling 7 days (Ahmad's pill said 1h 35m while the page it opened said
 * "No focus sessions yet").
 */
class WeekFocusMinutesTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private fun s(id: String, sec: Int, at: String, est: Int? = 25) = Session(id = id, taskId = null, taskName = "x", estimateMin = est, actualSec = sec, completedAt = at)
    private fun data(vararg ss: Session) = PeriodData(emptyList(), emptyList(), ss.toList(), emptyList(), emptyList())

    // Thu 24 Sep 2026; the week began Mon 21 Sep.
    private val thu = ms("2026-09-24T15:30:00Z")

    @Test fun countsOnlyThisWeekSinceMonday() {
        val d = data(
            s("a", 50 * 60, "2026-09-22T10:00:00Z"),      // Tue this week
            s("b", 95 * 60, "2026-09-18T20:00:00Z"),      // last Friday — inside a rolling 7 days, not this week
        )
        assertEquals(WeekPill(50, lastWeek = false), weekPill(d, thu, utc))
    }

    @Test fun accidentalStartsDontCountAndRunawaysAreClamped() {
        val d = data(
            s("a", 5, "2026-09-22T10:00:00Z"), s("b", 19, "2026-09-22T11:00:00Z"), s("c", 37, "2026-09-22T12:00:00Z"),
            s("d", 30 * 3600, "2026-09-23T12:00:00Z", est = 25),   // forgotten timer → 85 min
        )
        assertEquals(WeekPill(85, lastWeek = false), weekPill(d, thu, utc))
    }

    @Test fun roundsLikeThePageAndTheReview() {
        assertEquals(2, weekPill(data(s("a", 90, "2026-09-22T10:00:00Z")), thu, utc)!!.minutes)   // 1m 30s → 2m
        assertEquals(1, weekPill(data(s("a", 89, "2026-09-22T10:00:00Z")), thu, utc)!!.minutes)
    }

    @Test fun hiddenAtZeroMidWeek() {
        assertNull(weekPill(data(s("b", 95 * 60, "2026-09-18T20:00:00Z")), thu, utc))
        assertNull(weekPill(data(), thu, utc))
    }

    @Test fun mondayWithNothingYetOffersLastWeek() {
        val mon = ms("2026-09-21T09:00:00Z")
        val d = data(s("b", 95 * 60, "2026-09-18T20:00:00Z", est = 90))
        assertEquals(WeekPill(95, lastWeek = true), weekPill(d, mon, utc))
        val tue = ms("2026-09-22T09:00:00Z")
        assertEquals(WeekPill(95, lastWeek = true), weekPill(d, tue, utc))
        val wed = ms("2026-09-23T09:00:00Z")
        assertNull(weekPill(d, wed, utc))
    }

    @Test fun mondayAnchorFollowsTheZone() {
        // 23:30 Sunday in New York is already Monday in UTC.
        val ny = ZoneId.of("America/New_York")
        val d = data(s("a", 30 * 60, "2026-09-21T03:30:00Z"))   // Sun 20 Sep 23:30 NY
        assertEquals(WeekPill(30, lastWeek = false), weekPill(d, ms("2026-09-22T15:00:00Z"), utc))
        assertEquals(WeekPill(30, lastWeek = true), weekPill(d, ms("2026-09-22T15:00:00Z"), ny))
    }
}
