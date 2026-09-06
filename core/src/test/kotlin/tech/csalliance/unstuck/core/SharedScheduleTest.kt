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
import tech.csalliance.unstuck.core.logic.openedFrom
import tech.csalliance.unstuck.core.logic.plannedLabel
import tech.csalliance.unstuck.core.logic.resolveSharedSlot
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
import java.time.ZoneId

// Port of the lib/shared-blocks.ts contract: bucketing by the owner's next block,
// the row / detail labels, the RPC window cap, and the calendar shape a shared
// block takes (never a task block → never draggable / editable by mistake).
class SharedScheduleTest {

    private val today = "2026-09-05"   // a Saturday

    private fun slot(
        nextDate: String?, time: String? = "04:30", dur: Int? = 45, done: Boolean = false,
        nextDone: Boolean? = null, later: Boolean = false,
    ) = SharedWithMe(
        shareId = "s", taskId = "t", ownerName = "anna@example.com", level = ShareLevel.VIEW, title = "Task",
        done = done, nextDate = nextDate, nextStartTime = time, nextDurationMinutes = dur, nextDone = nextDone, later = later,
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

    // ── migration 053 / the 2026-09 cross-platform rules ──

    @Test fun `a share the owner parked in Later never lands in Today, Upcoming or Backlog`() {
        assertEquals(ShareBucket.LATER, shareBucket(slot(today, later = true), today))
        assertEquals(ShareBucket.LATER, shareBucket(slot(null, later = true), today))
        assertEquals(ShareBucket.LATER, shareBucket(slot("2026-09-01", later = true), today))
        assertEquals(ShareBucket.DONE, shareBucket(slot(today, later = true, done = true), today))   // done still wins
    }

    @Test fun `a past block that already FINISHED is not overdue - it sits in All only`() {
        assertEquals(ShareBucket.FINISHED, shareBucket(slot("2026-09-04", nextDone = true), today))
        assertEquals(ShareBucket.OVERDUE, shareBucket(slot("2026-09-04", nextDone = false), today))
        assertEquals(ShareBucket.OVERDUE, shareBucket(slot("2026-09-04"), today))                     // unknown → overdue (pre-052 tolerance)
        // A finished block TODAY still reads as today's slot (the literal web rule: past only).
        assertEquals(ShareBucket.TODAY, shareBucket(slot(today, nextDone = true), today))
        assertEquals(ShareBucket.UPCOMING, shareBucket(slot("2026-09-06", nextDone = true), today))
    }

    @Test fun `resolveSharedSlot re-expresses the owners instant in the recipients zone`() {
        // Owner in London (BST, UTC+1): 2026-09-05 09:00 → instant 08:00Z.
        val startAt = "2026-09-05T08:00:00+00:00"
        assertEquals(LocalSlotPair("2026-09-05", "10:00"), resolveSharedSlot(startAt, "2026-09-05", "09:00", ZoneId.of("Europe/Berlin")).pair())
        assertEquals(LocalSlotPair("2026-09-05", "04:00"), resolveSharedSlot(startAt, "2026-09-05", "09:00", ZoneId.of("America/New_York")).pair())
        // Across midnight: owner's late-evening block is the NEXT day in Tokyo.
        assertEquals(LocalSlotPair("2026-09-06", "07:30"), resolveSharedSlot("2026-09-05T22:30:00Z", "2026-09-05", "23:30", ZoneId.of("Asia/Tokyo")).pair())
        // The web's fractional-second / Z variants both parse.
        assertEquals(LocalSlotPair("2026-09-05", "08:00"), resolveSharedSlot("2026-09-05T08:00:00.000Z", "x", "y", ZoneId.of("UTC")).pair())
    }

    @Test fun `resolveSharedSlot is forgiving - no or garbage instant keeps the owners raw date + time`() {
        assertEquals(LocalSlotPair("2026-09-05", "09:00"), resolveSharedSlot(null, "2026-09-05", "09:00", ZoneId.of("Asia/Tokyo")).pair())
        assertEquals(LocalSlotPair("2026-09-05", "09:00"), resolveSharedSlot("", "2026-09-05", "09:00", ZoneId.of("Asia/Tokyo")).pair())
        assertEquals(LocalSlotPair("2026-09-05", "09:00"), resolveSharedSlot("not-a-time", "2026-09-05", "09:00", ZoneId.of("Asia/Tokyo")).pair())
        assertEquals(LocalSlotPair(null, null), resolveSharedSlot(null, null, null, ZoneId.of("UTC")).pair())
    }

    private data class LocalSlotPair(val date: String?, val time: String?)
    private fun tech.csalliance.unstuck.core.logic.LocalSlot.pair() = LocalSlotPair(date, time)

    @Test fun `a block is itself a slot and a live row can be pinned to the tapped occurrence`() {
        val friday = block("fri", "2026-09-11", "07:00")
        assertEquals("2026-09-11", friday.nextDate)
        assertEquals("07:00", friday.nextStartTime)
        assertEquals(45, friday.nextDurationMinutes)
        assertEquals("Planned Fri, Sep 11 · 07:00 · 45m", plannedLabel(friday, today))
        // The list row for the same task says Monday is next; opened from Friday's
        // block it must describe Friday.
        val live = slot("2026-09-07", "07:00")
        val pinned = live.openedFrom(friday)
        assertEquals("2026-09-07", pinned.nextDate)          // the row's own slot is untouched …
        assertEquals(friday, pinned.openedFrom)              // … the tapped occurrence rides along
        assertEquals(friday, block("fri", "2026-09-11", "07:00").asSharedWithMe().openedFrom)   // the fallback path pins too
        assertNull(live.openedFrom)                          // list rows never carry one
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
        // A past block the owner already FINISHED (task still open) reads "finished",
        // not "overdue" — the web wording; nothing is due.
        assertEquals("Planned Tue, Sep 1 · 04:30 · 45m · finished", plannedLabel(slot("2026-09-01", nextDone = true), today))
        assertEquals("Planned today · 04:30 · 45m", plannedLabel(slot(today, nextDone = true), today))
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

    @Test fun `sharedBlockLabel leads with the task and the owner is the suffix`() {
        assertEquals("London weekend · anna", sharedBlockLabel(block("b1", today)))
        // A narrow chip can ask for the task alone; a blank title never yields an empty chip.
        assertEquals("London weekend", sharedBlockLabel(block("b1", today), compact = true))
        assertEquals("Shared task · anna", sharedBlockLabel(block("b1", today).copy(title = "  ")))
    }
}
