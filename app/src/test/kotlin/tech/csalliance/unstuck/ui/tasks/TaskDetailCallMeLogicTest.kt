package tech.csalliance.unstuck.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.sync.CallRequest
import tech.csalliance.unstuck.sync.CallsClient
import tech.csalliance.unstuck.ui.assistant.CallToolLogic

/**
 * The task editor's "Call me about this" pure half (iOS CallMeSection
 * behaviour): when the toggle is bookable, where the call lands, what counts
 * as dirty, how a tool result becomes copy.
 */
class TaskDetailCallMeLogicTest {

    private fun row(id: String = "c1", notes: List<String> = listOf("bring the contract"), lead: Int? = 15, blockId: String? = "b1") =
        CallRequest(id = id, taskId = "t1", blockId = blockId, callAt = CallsClient.iso(0L), leadMin = lead, label = "Board prep", notes = notes)

    @Test fun `canBook needs a scheduled start and a task not parked in Later`() {
        assertFalse(CallMeLogic.canBook(null, null))
        assertFalse(CallMeLogic.canBook(1_000L, true))
        assertTrue(CallMeLogic.canBook(1_000L, null))
        assertTrue(CallMeLogic.canBook(1_000L, false))
    }

    @Test fun `callAt is lead minutes before the block`() {
        assertNull(CallMeLogic.callAtMs(null, 10))
        assertEquals(3_600_000L - 10 * 60_000L, CallMeLogic.callAtMs(3_600_000L, 10))
    }

    @Test fun `notes are one per line, trimmed, blanks dropped, capped like the web`() {
        assertEquals(listOf("a; b", "c"), CallMeLogic.notes("  a; b \n\n c \n   "))
        assertEquals(emptyList<String>(), CallMeLogic.notes(""))
        val long = "x".repeat(400)
        assertEquals(CallToolLogic.MAX_NOTE_LENGTH, CallMeLogic.notes(long)[0].length)
        assertEquals(CallToolLogic.MAX_NOTES, CallMeLogic.notes((1..30).joinToString("\n") { "n$it" }).size)
    }

    @Test fun `dirty when there is no row, or notes, lead or anchor changed`() {
        assertTrue(CallMeLogic.dirty(null, emptyList(), 15, "b1"))
        val r = row()
        assertFalse(CallMeLogic.dirty(r, listOf("bring the contract"), 15, "b1"))
        assertTrue(CallMeLogic.dirty(r, listOf("bring the contract", "and the deck"), 15, "b1"))
        assertTrue(CallMeLogic.dirty(r, listOf("bring the contract"), 30, "b1"))
        assertTrue(CallMeLogic.dirty(r, listOf("bring the contract"), 15, "b2"))    // the block moved → re-anchor
    }

    @Test fun `tool results become the section's copy`() {
        assertEquals("", CallMeLogic.userMessage("ok: call booked 2026-09-07 09:30 \"Board prep\" (0 notes) id=c1", ClockMode.H24))
        assertEquals(CallMeLogic.BOOK_FAILED, CallMeLogic.userMessage(CallToolLogic.NETWORK, ClockMode.H24))
        assertEquals(CallMeLogic.CHANGED_UNDERNEATH, CallMeLogic.userMessage(CallToolLogic.CHANGED_UNDERNEATH, ClockMode.H24))
        assertEquals("Calls can only be booked between 06:00 and 23:00 — suggest a time inside that window",
            CallMeLogic.userMessage("error: calls can only be booked between 06:00 and 23:00 — suggest a time inside that window", ClockMode.H24))
        assertTrue(CallMeLogic.isOk("ok"))
        assertFalse(CallMeLogic.isOk("error: x"))
        assertTrue(CallMeLogic.isAlreadyGone("error: that call is already cancelled"))
        assertTrue(CallMeLogic.isAlreadyGone("error: that call is already done — book a new one with request_call"))
        assertFalse(CallMeLogic.isAlreadyGone("error: call not found — use get_calls"))
    }

    @Test fun `rowFromResult rebuilds the booked row when the read-back is unavailable`() {
        val r = CallMeLogic.rowFromResult("ok: call booked 2026-09-07 09:30 \"Board prep\" (1 note) id=call9", "u1", "t1", "b1", 30, listOf("x"), 123L)!!
        assertEquals("call9", r.id); assertEquals("Board prep", r.label); assertEquals("t1", r.taskId); assertEquals("b1", r.blockId)
        assertEquals(30, r.leadMin); assertEquals(listOf("x"), r.notes); assertEquals(123L, r.callAtMs); assertTrue(r.isLive)
        assertNull(CallMeLogic.rowFromResult("error: nope", null, "t1", null, 5, emptyList(), 0L))
    }

    @Test fun `copy is the iOS section verbatim`() {
        assertEquals("Call me about this", CallMeLogic.SECTION_TITLE)
        assertEquals("Schedule it first — the call rings a few minutes before the task starts.", CallMeLogic.SCHEDULE_FIRST)
        assertEquals("Your phone rings before it starts and reads your notes back.", CallMeLogic.OFF_HINT)
        assertEquals("Notes to read back — one per line", CallMeLogic.NOTES_LABEL)
        assertEquals("10m before", CallMeLogic.chip(10))
        assertEquals("Couldn't book the call — check your connection and try again.", CallMeLogic.BOOK_FAILED)
        assertEquals("Couldn't cancel the call — try again.", CallMeLogic.CANCEL_FAILED)
        assertEquals("That call changed underneath you — reloaded.", CallMeLogic.CHANGED_UNDERNEATH)
    }

    // ── this phone's switch + hours (parity with iOS build 81, audit 2026-09-22 C12) ──

    private fun localMs(hm: String): Long =
        java.time.LocalDate.now().plusDays(1).atTime(java.time.LocalTime.parse(hm)).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `the hours hint names why this phone would decline, and is null when it rings`() {
        val narrow = tech.csalliance.unstuck.core.logic.CallSettings(hoursStart = "08:00", hoursEnd = "21:00")
        assertNull(CallMeLogic.hoursHint(localMs("20:00"), narrow, ClockMode.H24))
        assertNull(CallMeLogic.hoursHint(null, narrow, ClockMode.H24))
        assertEquals(
            "21:00 is outside this phone's call hours (08:00–21:00; the latest it rings is 20:59), so it would decline this call. Pick another lead, move the task, or widen the hours in Settings › Notifications & calls.",
            CallMeLogic.hoursHint(localMs("21:00"), narrow, ClockMode.H24),
        )
        assertEquals(
            "07:45 is outside this phone's call hours (08:00–21:00), so it would decline this call. Pick another lead, move the task, or widen the hours in Settings › Notifications & calls.",
            CallMeLogic.hoursHint(localMs("07:45"), narrow, ClockMode.H24),
        )
        assertEquals(
            "Calls are off on this phone, so it would decline this call. Switch them on in Settings › Notifications & calls.",
            CallMeLogic.hoursHint(localMs("12:00"), tech.csalliance.unstuck.core.logic.CallSettings(enabled = false), ClockMode.H24),
        )
    }

    @Test fun `a 12-hour phone reads the section's times its own way`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val narrow = tech.csalliance.unstuck.core.logic.CallSettings(hoursStart = "08:00", hoursEnd = "21:00")
            assertEquals(
                "7:45 AM is outside this phone's call hours (8:00 AM–9:00 PM), so it would decline this call. Pick another lead, move the task, or widen the hours in Settings › Notifications & calls.",
                CallMeLogic.hoursHint(localMs("07:45"), narrow, ClockMode.H12),
            )
            val utc = java.time.ZoneId.of("UTC")
            val at = java.time.Instant.parse("2026-09-24T14:05:00Z").toEpochMilli()
            assertEquals("Rings 2026-09-24 2:05 PM", CallMeLogic.ringsLine(at, ClockMode.H12, utc))
            assertEquals("Rings 2026-09-24 14:05", CallMeLogic.ringsLine(at, ClockMode.H24, utc))
            assertEquals("Calls can only be booked between 6:00 AM and 11:00 PM — suggest a time inside that window",
                CallMeLogic.userMessage("error: calls can only be booked between 06:00 and 23:00 — suggest a time inside that window", ClockMode.H12))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test fun `booking or a new ring time meets the hint, a notes-only edit does not`() {
        assertTrue("a booking", CallMeLogic.changesTime(null, 15, "b1"))
        assertFalse("notes only", CallMeLogic.changesTime(row(), 15, "b1"))
        assertTrue("another lead", CallMeLogic.changesTime(row(), 30, "b1"))
        assertTrue("the task moved to another slot", CallMeLogic.changesTime(row(), 15, "b2"))
    }

    // ── following the mirror (parity with iOS build 72, observeMirror) ──

    @Test fun `the section follows the task's live row in the mirror`() {
        val a = row(id = "a").copy(callAt = CallsClient.iso(2_000L))
        val b = row(id = "b").copy(callAt = CallsClient.iso(1_000L))
        val gone = row(id = "g").copy(status = "cancelled")
        val other = row(id = "o").copy(taskId = "t2")
        assertEquals("soonest live row for the task", "b", CallMeLogic.liveForTask(listOf(a, b, gone, other), "t1")?.id)
        assertNull(CallMeLogic.liveForTask(listOf(gone, other), "t1"))
        // Changes that move the toggle / the fields, and the ones that don't.
        assertFalse(CallMeLogic.mirrorChanged(null, null))
        assertFalse(CallMeLogic.mirrorChanged(row(), row()))
        assertTrue("rang or cancelled elsewhere", CallMeLogic.mirrorChanged(null, row()))
        assertTrue("booked from the web / the assistant", CallMeLogic.mirrorChanged(row(), null))
        assertTrue(CallMeLogic.mirrorChanged(row(notes = listOf("new")), row()))
        assertTrue(CallMeLogic.mirrorChanged(row(lead = 30), row()))
        assertTrue(CallMeLogic.mirrorChanged(row(blockId = "b2"), row()))
        assertTrue(CallMeLogic.mirrorChanged(row().copy(status = "snoozed"), row()))
    }
}
