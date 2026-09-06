package tech.csalliance.unstuck.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertEquals("", CallMeLogic.userMessage("ok: call booked 2026-09-07 09:30 \"Board prep\" (0 notes) id=c1"))
        assertEquals(CallMeLogic.BOOK_FAILED, CallMeLogic.userMessage(CallToolLogic.NETWORK))
        assertEquals(CallMeLogic.CHANGED_UNDERNEATH, CallMeLogic.userMessage(CallToolLogic.CHANGED_UNDERNEATH))
        assertEquals("Calls can only be booked between 06:00 and 23:00 — suggest a time inside that window",
            CallMeLogic.userMessage("error: calls can only be booked between 06:00 and 23:00 — suggest a time inside that window"))
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
}
