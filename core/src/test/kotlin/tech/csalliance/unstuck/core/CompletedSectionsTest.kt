package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CompletedSection
import tech.csalliance.unstuck.core.logic.CompletedSection.EARLIER
import tech.csalliance.unstuck.core.logic.CompletedSection.EARLIER_THIS_WEEK
import tech.csalliance.unstuck.core.logic.CompletedSection.LAST_WEEK
import tech.csalliance.unstuck.core.logic.CompletedSection.TODAY
import tech.csalliance.unstuck.core.logic.CompletedSection.YESTERDAY
import tech.csalliance.unstuck.core.logic.completedSectionOf
import tech.csalliance.unstuck.core.logic.groupCompleted
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabel
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabels
import tech.csalliance.unstuck.core.logic.plannedLabel
import tech.csalliance.unstuck.core.logic.shareSlotLabel
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.time.ClockMode
import java.time.LocalDateTime
import java.time.ZoneId

// Tasks › Completed sections — the SHARED cross-platform cases (iOS / Android /
// web run the same list): local-midnight boundaries, Monday week start (incl.
// today = Monday), exactly 00:00, missing completedAt, newest-first, DST.
class CompletedSectionsTest {
    private val ny = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")

    private fun at(s: String, zone: ZoneId = ny) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun iso(s: String, zone: ZoneId = ny) = LocalDateTime.parse(s).atZone(zone).toInstant().toString()
    private fun sec(done: String, now: String, zone: ZoneId = ny) = completedSectionOf(at(done, zone), at(now, zone), zone)

    // Thursday 2026-09-24 14:00 — this week began Monday 09-21; last week = 09-14…09-20.
    private val thu = "2026-09-24T14:00"

    @Test fun `each section by local calendar day, weeks start Monday`() {
        assertEquals(TODAY, sec("2026-09-24T08:00", thu))
        assertEquals(TODAY, sec("2026-09-24T23:59", thu))              // later today (skew) still Today
        assertEquals(YESTERDAY, sec("2026-09-23T23:59", thu))
        assertEquals(YESTERDAY, sec("2026-09-23T00:00", thu))
        assertEquals(EARLIER_THIS_WEEK, sec("2026-09-22T12:00", thu))   // day before yesterday
        assertEquals(EARLIER_THIS_WEEK, sec("2026-09-21T00:00", thu))   // Monday 00:00 of this week
        assertEquals(LAST_WEEK, sec("2026-09-20T23:59", thu))           // Sunday before
        assertEquals(LAST_WEEK, sec("2026-09-14T00:00", thu))           // previous Monday 00:00
        assertEquals(EARLIER, sec("2026-09-13T23:59", thu))
        assertEquals(EARLIER, sec("2025-01-01T09:00", thu))
    }

    @Test fun `a task completed exactly at 00_00 belongs to the day that starts`() {
        assertEquals(TODAY, sec("2026-09-24T00:00", thu))
        assertEquals(YESTERDAY, sec("2026-09-23T23:59:59", "2026-09-24T00:00"))
        assertEquals(TODAY, sec("2026-09-24T00:00", "2026-09-24T00:00"))
    }

    @Test fun `today is Monday - Earlier this week is empty, Sunday is Yesterday, Saturday is Last week`() {
        val mon = "2026-09-21T09:00"
        assertEquals(TODAY, sec("2026-09-21T00:00", mon))
        assertEquals(YESTERDAY, sec("2026-09-20T18:00", mon))           // Sunday = the day before today
        assertEquals(LAST_WEEK, sec("2026-09-19T18:00", mon))           // Saturday
        assertEquals(LAST_WEEK, sec("2026-09-14T00:00", mon))           // last week's Monday
        assertEquals(EARLIER, sec("2026-09-13T23:59", mon))
        val groups = groupCompleted(listOf("2026-09-21T08:00", "2026-09-20T08:00", "2026-09-19T08:00"), at(mon)) { iso(it) }
        assertFalse(groups.any { it.section == EARLIER_THIS_WEEK })
    }

    @Test fun `today is Tuesday - Sunday is Last week, Monday is Yesterday`() {
        val tue = "2026-09-22T09:00"
        assertEquals(YESTERDAY, sec("2026-09-21T10:00", tue))
        assertEquals(LAST_WEEK, sec("2026-09-20T10:00", tue))
    }

    @Test fun `today is Sunday - the whole week back to Monday is this week`() {
        val sun = "2026-09-27T20:00"
        assertEquals(YESTERDAY, sec("2026-09-26T10:00", sun))
        assertEquals(EARLIER_THIS_WEEK, sec("2026-09-21T00:00", sun))
        assertEquals(LAST_WEEK, sec("2026-09-20T23:59", sun))
    }

    @Test fun `missing or unparseable completedAt goes to Earlier`() {
        assertEquals(EARLIER, completedSectionOf(null, at(thu), ny))
        val groups = groupCompleted(listOf("a" to null, "b" to "not a date"), at(thu), ny) { it.second }
        assertEquals(listOf(EARLIER), groups.map { it.section })
        assertEquals(listOf("a", "b"), groups.single().items.map { it.first })
    }

    @Test fun `groups in section order, empty sections omitted, newest first inside`() {
        val rows = listOf(
            "old" to "2026-08-01T10:00",
            "today-early" to "2026-09-24T08:00",
            "none" to null,
            "yday" to "2026-09-23T10:00",
            "today-late" to "2026-09-24T13:00",
            "oldest" to "2026-07-01T10:00",
            "lastwk" to "2026-09-16T10:00",
        )
        val groups = groupCompleted(rows, at(thu), ny) { r -> r.second?.let { iso(it) } }
        assertEquals(listOf(TODAY, YESTERDAY, LAST_WEEK, EARLIER), groups.map { it.section })
        assertEquals(listOf("today-late", "today-early"), groups[0].items.map { it.first })
        assertEquals(listOf("yday"), groups[1].items.map { it.first })
        assertEquals(listOf("lastwk"), groups[2].items.map { it.first })
        assertEquals(listOf("old", "oldest", "none"), groups[3].items.map { it.first })   // no stamp → last
        assertTrue(groupCompleted(emptyList<String>(), at(thu), ny) { it }.isEmpty())
    }

    @Test fun `DST - spring-forward 23h day still breaks at local midnight`() {
        // London springs forward Sun 2026-03-29 (a 23-hour day). Now = Mon 03-30 00:30 BST.
        // "Start of today − 24h" would land on Sat 23:00 and wrongly call Saturday 23:30
        // "Yesterday"; calendar-day math keeps it in Last week.
        val now = "2026-03-30T00:30"
        assertEquals(LAST_WEEK, sec("2026-03-28T23:30", now, london))
        assertEquals(YESTERDAY, sec("2026-03-29T00:10", now, london))
        assertEquals(YESTERDAY, sec("2026-03-29T23:59", now, london))
        assertEquals(TODAY, sec("2026-03-30T00:00", now, london))
    }

    @Test fun `DST - fall-back 25h day`() {
        // New York falls back Sun 2026-11-01 (a 25-hour day). Now = Mon 11-02 00:30.
        val now = "2026-11-02T00:30"
        assertEquals(YESTERDAY, sec("2026-11-01T00:30", now))
        assertEquals(YESTERDAY, sec("2026-11-01T23:30", now))
        assertEquals(LAST_WEEK, sec("2026-10-31T23:30", now))
    }

    @Test fun `Today and Yesterday start open, the rest folded`() {
        assertEquals(listOf(TODAY, YESTERDAY), CompletedSection.entries.filter { it.defaultExpanded })
    }

    // ── A completed task never reads "Overdue" (owner bug report 2026-09-24) ──

    private fun share(done: Boolean, nextDate: String) = SharedWithMe(
        shareId = "s", taskId = "t", ownerName = "James", level = ShareLevel.PARTNER, title = "Hike",
        done = done, nextDate = nextDate, nextStartTime = "08:45", nextDurationMinutes = 25,
    )

    @Test fun `a completed share with a past slot shows its day and time, never overdue`() {
        val today = "2026-09-24"
        val row = shareSlotLabel(share(done = true, nextDate = "2026-09-18"), today, ClockMode.H12, java.util.Locale.US)!!
        assertFalse(row, row.contains("overdue", ignoreCase = true))
        assertTrue(row, row.startsWith("Fri Sep 18"))
        // Detail line: the task is done even when the tapped slot (a calendar block) is not.
        val openSlot = share(done = false, nextDate = "2026-09-18")
        assertTrue(plannedLabel(openSlot, today, ClockMode.H24)!!.endsWith("overdue"))
        assertEquals("Planned Fri, Sep 18 · 08:45 · 25m", plannedLabel(openSlot, today, ClockMode.H24, taskDone = true))
    }

    @Test fun `a ticked recurring occurrence gets no Overdue badge`() {
        val weekly = mkTask(id = "t1", name = "Hike").copy(recurrence = Recurrence.Weekly(listOf(5)))
        val ticked = mkBlock(id = "b1", taskId = "t1", date = "2026-09-18").copy(done = true, completedAt = "2026-09-18T09:10:00Z")
        assertNull(overdueOccurrenceLabel("b1", listOf(weekly), listOf(ticked), "2026-09-24"))
        assertTrue(overdueOccurrenceLabels(listOf("b1"), listOf(weekly), listOf(ticked), "2026-09-24").isEmpty())
        assertEquals("Overdue · Fri", overdueOccurrenceLabel("b1", listOf(weekly), listOf(ticked.copy(done = false)), "2026-09-24"))
    }
}
