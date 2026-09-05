package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.IsoRange
import tech.csalliance.unstuck.core.logic.SHARED_BLOCKS_MAX_DAYS
import tech.csalliance.unstuck.core.logic.SHARED_BLOCK_ID_PREFIX
import tech.csalliance.unstuck.core.logic.ShareBucket
import tech.csalliance.unstuck.core.logic.UNASSIGNED_AREA
import tech.csalliance.unstuck.core.logic.addDaysIso
import tech.csalliance.unstuck.core.logic.asCalBlock
import tech.csalliance.unstuck.core.logic.asSharedWithMe
import tech.csalliance.unstuck.core.logic.clampSharedRange
import tech.csalliance.unstuck.core.logic.compareShareSlot
import tech.csalliance.unstuck.core.logic.daysInRange
import tech.csalliance.unstuck.core.logic.fmtDuration
import tech.csalliance.unstuck.core.logic.isSharedBlockId
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.liveSharedBlocks
import tech.csalliance.unstuck.core.logic.monthRange
import tech.csalliance.unstuck.core.logic.plannedLabel
import tech.csalliance.unstuck.core.logic.shareBucket
import tech.csalliance.unstuck.core.logic.shareFirstName
import tech.csalliance.unstuck.core.logic.shareMatchesArea
import tech.csalliance.unstuck.core.logic.shareSlotLabel
import tech.csalliance.unstuck.core.logic.sharedBlockLabel
import tech.csalliance.unstuck.core.logic.weekRangeContaining
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedBlock
import tech.csalliance.unstuck.core.model.SharedWithMe

// Port of the lib/shared-blocks.ts contract: bucketing by the owner's next block,
// the row / detail labels, the RPC window cap, and the calendar shape a shared
// block takes (never a task block → never draggable / editable by mistake).
class SharedScheduleTest {

    private val today = "2026-09-05"   // a Saturday

    private fun slot(nextDate: String?, time: String? = "04:30", dur: Int? = 45, done: Boolean = false) = SharedWithMe(
        shareId = "s", taskId = "t", ownerName = "anna@example.com", level = ShareLevel.VIEW, title = "Task",
        done = done, nextDate = nextDate, nextStartTime = time, nextDurationMinutes = dur,
    )

    private fun block(
        id: String, date: String, start: String = "09:00", skipped: Boolean = false, kind: String = "task", done: Boolean = false,
    ) = SharedBlock(
        blockId = id, taskId = "t1", shareId = "s1", level = ShareLevel.PARTNER, ownerName = "anna@example.com",
        title = "London weekend", date = date, startTime = start, durationMinutes = 45, done = done, skipped = skipped, kind = kind,
    )

    // ── bucketing ──

    @Test fun `shareBucket places a share by the owners next block`() {
        assertEquals(ShareBucket.DONE, shareBucket(slot(today, done = true), today))
        assertEquals(ShareBucket.UNSCHEDULED, shareBucket(slot(null), today))
        assertEquals(ShareBucket.TODAY, shareBucket(slot(today), today))
        assertEquals(ShareBucket.UPCOMING, shareBucket(slot("2026-09-06"), today))
        assertEquals(ShareBucket.OVERDUE, shareBucket(slot("2026-09-04"), today))
    }

    @Test fun `compareShareSlot is chronological with unscheduled rows last`() {
        val early = slot(today, "08:00")
        val late = slot(today, "16:00")
        val tomorrow = slot("2026-09-06", "01:00")
        val none = slot(null)
        assertTrue(compareShareSlot(early, late) < 0)
        assertTrue(compareShareSlot(late, tomorrow) < 0)
        assertTrue(compareShareSlot(tomorrow, none) < 0)
        assertTrue(compareShareSlot(none, early) > 0)
        assertEquals(0, compareShareSlot(none, slot(null)))
        assertEquals(0, compareShareSlot(early, slot(today, "08:00")))
    }

    @Test fun `shareMatchesArea keeps area-less shares under any filter and narrows on Unassigned`() {
        assertTrue(shareMatchesArea("Work", null))
        assertTrue(shareMatchesArea(null, null))
        assertTrue(shareMatchesArea("Work", "Work"))
        assertFalse(shareMatchesArea("Home", "Work"))
        assertTrue(shareMatchesArea(null, "Work"))            // the owner's vocabulary isn't ours
        assertTrue(shareMatchesArea(null, UNASSIGNED_AREA))
        assertFalse(shareMatchesArea("Work", UNASSIGNED_AREA))
    }

    // ── labels ──

    @Test fun `fmtDuration`() {
        assertNull(fmtDuration(null))
        assertNull(fmtDuration(0))
        assertEquals("45m", fmtDuration(45))
        assertEquals("1h", fmtDuration(60))
        assertEquals("1h 30m", fmtDuration(90))
    }

    @Test fun `shareSlotLabel - Today, a weekday inside the next 6 days, else weekday + date`() {
        assertEquals("Today 04:30 · 45m", shareSlotLabel(slot(today), today))
        assertEquals("Sun 04:30 · 45m", shareSlotLabel(slot("2026-09-06"), today))
        assertEquals("Fri 04:30 · 45m", shareSlotLabel(slot("2026-09-11"), today))      // today + 6 → still a bare weekday
        assertEquals("Sat Sep 12 04:30 · 45m", shareSlotLabel(slot("2026-09-12"), today))
        assertEquals("Tue Sep 1 04:30 · 45m", shareSlotLabel(slot("2026-09-01"), today))  // past → dated
        assertEquals("Sun · 45m", shareSlotLabel(slot("2026-09-06", time = null), today))
        assertEquals("Sun 04:30", shareSlotLabel(slot("2026-09-06", dur = null), today))
        assertNull(shareSlotLabel(slot(null), today))
    }

    @Test fun `plannedLabel - the detail line, with an overdue suffix for a passed open slot`() {
        assertEquals("Planned today · 04:30 · 45m", plannedLabel(slot(today), today))
        assertEquals("Planned Sat, Sep 12 · 04:30 · 45m", plannedLabel(slot("2026-09-12"), today))
        assertEquals("Planned Tue, Sep 1 · 04:30 · 45m · overdue", plannedLabel(slot("2026-09-01"), today))
        assertEquals("Planned Tue, Sep 1 · 04:30 · 45m", plannedLabel(slot("2026-09-01", done = true), today))
        assertEquals("Planned Sun, Sep 6", plannedLabel(slot("2026-09-06", time = null, dur = null), today))
        assertNull(plannedLabel(slot(null), today))
    }

    @Test fun `shareFirstName drops the email domain and never blanks`() {
        assertEquals("anna", shareFirstName("anna@example.com"))
        assertEquals("Anna B", shareFirstName("Anna B"))
        assertEquals("Someone", shareFirstName(""))
        assertEquals("Someone", shareFirstName(null))
    }

    // ── date windows ──

    @Test fun `range helpers`() {
        assertEquals("2026-09-06", addDaysIso("2026-09-05", 1))
        assertEquals("2026-08-31", addDaysIso("2026-09-05", -5))
        assertEquals(1, daysInRange(IsoRange("2026-09-05", "2026-09-05")))
        assertEquals(7, daysInRange(IsoRange("2026-08-31", "2026-09-06")))
        assertEquals(0, daysInRange(IsoRange("2026-09-06", "2026-09-05")))
        assertEquals(IsoRange("2026-08-31", "2026-09-06"), weekRangeContaining("2026-09-05"))   // Sat → Mon..Sun
        assertEquals(IsoRange("2026-08-31", "2026-09-06"), weekRangeContaining("2026-08-31"))   // Mon → same week
        assertEquals(IsoRange("2026-09-01", "2026-09-30"), monthRange(2026, 9))
        assertEquals(IsoRange("2026-02-01", "2026-02-28"), monthRange(2026, 2))
    }

    @Test fun `clampSharedRange never exceeds the RPCs 62-day cap and fixes an inverted window`() {
        val ok = IsoRange("2026-09-01", "2026-09-30")
        assertEquals(ok, clampSharedRange(ok))
        val wide = clampSharedRange(IsoRange("2026-09-01", "2026-12-31"))
        assertEquals("2026-09-01", wide.from)
        assertEquals(SHARED_BLOCKS_MAX_DAYS, daysInRange(wide))
        assertEquals(IsoRange("2026-09-06", "2026-09-06"), clampSharedRange(IsoRange("2026-09-06", "2026-09-05")))
    }

    // ── calendar shape ──

    @Test fun `a shared block on the calendar is never a task block and carries the shared id prefix`() {
        val cb = block("b1", today, "04:30").asCalBlock()
        assertEquals(SHARED_BLOCK_ID_PREFIX + "b1", cb.id)
        assertTrue(isSharedBlockId(cb.id))
        assertFalse(isSharedBlockId("b1"))
        assertNull(cb.taskId)
        assertEquals(CalBlockKind.PLACEHOLDER, cb.kind)
        assertFalse(isTaskBlock(cb))               // → no edit / drag / focus path can ever pick it up
        assertEquals("London weekend", cb.taskName)
        assertEquals("04:30", cb.startTime)
        assertEquals(45, cb.durationMinutes)
        assertEquals(today, cb.date)
    }

    @Test fun `a tapped block seeds the detail-sheet row with its own slot`() {
        val s = block("b1", today, "04:30").asSharedWithMe()
        assertEquals("s1", s.shareId)
        assertEquals("t1", s.taskId)
        assertEquals(ShareLevel.PARTNER, s.level)
        assertEquals(today, s.nextDate)
        assertEquals("04:30", s.nextStartTime)
        assertEquals(45, s.nextDurationMinutes)
        assertEquals("b1", s.nextBlockId)
        assertEquals("Planned today · 04:30 · 45m", plannedLabel(s, today))
    }

    @Test fun `liveSharedBlocks drops skipped + external and sorts by date then start`() {
        val live = liveSharedBlocks(listOf(
            block("late", "2026-09-06", "10:00"),
            block("skip", "2026-09-05", "08:00", skipped = true),
            block("ext", "2026-09-05", "08:30", kind = "external"),
            block("early", "2026-09-05", "09:00"),
            block("earlier", "2026-09-05", "07:00"),
        ))
        assertEquals(listOf("earlier", "early", "late"), live.map { it.blockId })
    }

    @Test fun `sharedBlockLabel leads with the owner`() {
        assertEquals("anna · London weekend", sharedBlockLabel(block("b1", today)))
    }
}
