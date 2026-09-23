package tech.csalliance.unstuck.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.Receipt
import tech.csalliance.unstuck.core.logic.ReceiptArgs
import tech.csalliance.unstuck.core.logic.ReceiptIcon
import tech.csalliance.unstuck.core.logic.ReceiptUndo
import tech.csalliance.unstuck.core.logic.ReceiptUndoKind
import tech.csalliance.unstuck.core.logic.ReceiptUndoPlan
import tech.csalliance.unstuck.core.logic.ReceiptUndoRefusal
import tech.csalliance.unstuck.core.logic.UndoState
import tech.csalliance.unstuck.core.logic.deriveReceipt
import tech.csalliance.unstuck.core.logic.factSaveUndo
import tech.csalliance.unstuck.core.logic.planReceiptUndo
import tech.csalliance.unstuck.core.logic.receiptArgsFromJson
import tech.csalliance.unstuck.core.logic.receiptUndoRefusal
import tech.csalliance.unstuck.core.logic.advanceReceiptUndo
import tech.csalliance.unstuck.core.logic.stampReceiptUndo
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.TaskItem

// An assistant Undo puts back exactly what its turn did, or nothing — and
// says so. The turn's rows are stamped as it left them; a row changed since
// refuses the undo (Android audit 2026-09-23, A17).
class ExactUndoTest {

    private fun task(id: String = "t1", name: String = "Draft the lease letter", done: Boolean = false) =
        TaskItem(id = id, name = name, estimateMin = 25, done = done, createdAt = "2026-09-21T09:00:00.000Z", updatedAt = "2026-09-21T09:00:00.000Z")

    private fun block(id: String, taskId: String, start: String = "10:00") =
        CalBlock(id = id, taskId = taskId, taskName = "Draft the lease letter", startTime = start, durationMinutes = 30, date = "2026-09-21")

    private fun capture(id: String, taskId: String? = null, body: String = "ask Sam about the lease renewal") =
        Capture(id = id, taskId = taskId, tag = CaptureTag.FOLLOW_UP, body = body, at = "2026-09-20T08:00:00.000Z")

    private fun fact(id: String = "f1", text: String = "Maleek — son", source: ProfileFactSource = ProfileFactSource.INTERVIEW) =
        ProfileFact(id, ProfileFactCategory.PERSON, text, source, createdAt = "2026-08-01T10:00:00.000Z", updatedAt = "2026-08-01T10:00:00.000Z")

    private fun state(
        tasks: List<TaskItem> = emptyList(), blocks: List<CalBlock> = emptyList(), captures: List<Capture> = emptyList(),
        archived: Set<String> = emptySet(), facts: List<ProfileFact> = emptyList(),
    ) = UndoState(tasks, blocks, captures, archived, facts)

    // ── promote_capture: its own undo ──

    @Test fun `promote_capture's undo puts the capture back, never deletes it`() {
        val r = deriveReceipt("promote_capture", ReceiptArgs(captureId = "c1"), "ok: promoted capture to task id=t9 name=\"ask Sam\"", emptyList())!!
        assertEquals(ReceiptUndo.unpromoteCapture("t9", "c1"), r.undo)
        assertEquals(listOf("t9"), r.undo!!.taskIds)
        assertEquals("c1", r.undo!!.captureTarget)
        // No capture id → no Undo at all, never a DELETE_TASK that strands it.
        assertNull(deriveReceipt("promote_capture", ReceiptArgs(), "ok: promoted capture to task id=t9 name=\"ask Sam\"", emptyList())!!.undo)
        assertEquals("c1", receiptArgsFromJson("""{"captureId":"c1"}""").captureId)
        assertEquals(ReceiptUndoPlan.Unpromote("t9", "c1", true), planReceiptUndo(ReceiptUndo.unpromoteCapture("t9", "c1", unarchive = true), emptyList(), "n"))
    }

    // ── the stamp survives sync, catches real change ──

    @Test fun `an untouched row still undoes after a sync round trip reshapes it`() {
        val left = task().copy(tags = null, moveCount = null, later = null, completedAt = null)
        val undo = stampReceiptUndo(ReceiptUndo.deleteTask("t1"), state(listOf(left), listOf(block("b1", "t1"))))
        assertTrue(undo.stamped)
        // What the server echoes back: its column defaults, its own updated_at,
        // and the Google push's event id stamped on the block.
        val echoed = left.copy(tags = emptyList(), objectives = emptyList(), comments = emptyList(), moveCount = 0, later = false, updatedAt = "2026-09-21T09:00:03.412+00:00")
        val pushed = block("b1", "t1").copy(externalEventId = "evt_1", externalConnectionId = "conn")
        assertNull(receiptUndoRefusal(undo, state(listOf(echoed), listOf(pushed))))
        // A completion time in the server's offset form is the same instant.
        val doneUndo = stampReceiptUndo(ReceiptUndo.uncompleteTask("t1"), state(listOf(task(done = true).copy(completedAt = "2026-09-21T09:30:00.000Z"))))
        assertNull(receiptUndoRefusal(doneUndo, state(listOf(task(done = true).copy(completedAt = "2026-09-21T09:30:00+00:00")))))
    }

    @Test fun `a created task that was renamed, scheduled, moved or worked on since is not deleted`() {
        val undo = stampReceiptUndo(ReceiptUndo.deleteTask("t1"), state(listOf(task()), listOf(block("b1", "t1"))))
        val cases = mapOf(
            "renamed" to state(listOf(task(name = "Lease letter")), listOf(block("b1", "t1"))),
            "scheduled again" to state(listOf(task()), listOf(block("b1", "t1"), block("b2", "t1", "15:00"))),
            "moved" to state(listOf(task()), listOf(block("b1", "t1", "11:30"))),
            "focused on" to state(listOf(task().copy(totalFocused = 1500)), listOf(block("b1", "t1"))),
            "completed" to state(listOf(task(done = true)), listOf(block("b1", "t1"))),
        )
        for ((why, now) in cases) assertEquals(why, ReceiptUndoRefusal.CHANGED, receiptUndoRefusal(undo, now))
        // Gone already: nothing to write, so nothing to refuse (the caller's no-op).
        assertNull(receiptUndoRefusal(undo, state()))
    }

    @Test fun `a task that gathered notes since is not deleted`() {
        val undo = stampReceiptUndo(ReceiptUndo.deleteTask("t1"), state(listOf(task())))
        assertEquals(ReceiptUndoRefusal.NOTES, receiptUndoRefusal(undo, state(listOf(task()), captures = listOf(capture("n1", taskId = "t1")))))
        // A bulk undo says "some of these".
        val bulk = stampReceiptUndo(ReceiptUndo.deleteTasks(listOf("t1", "t2")), state(listOf(task(), task("t2"))))
        assertEquals(ReceiptUndoRefusal.NOTES_SOME, receiptUndoRefusal(bulk, state(listOf(task(), task("t2")), captures = listOf(capture("n1", taskId = "t2")))))
        assertEquals(ReceiptUndoRefusal.CHANGED_SOME, receiptUndoRefusal(bulk, state(listOf(task(), task("t2", name = "Other")))))
    }

    @Test fun `the promoted capture restored or edited since refuses the unpromote`() {
        val left = state(listOf(task("t9")), captures = listOf(capture("c1", taskId = "t9")), archived = setOf("c1"))
        val undo = stampReceiptUndo(ReceiptUndo.unpromoteCapture("t9", "c1", unarchive = true), left)
        assertNull(receiptUndoRefusal(undo, left))
        assertEquals(ReceiptUndoRefusal.CHANGED, receiptUndoRefusal(undo, left.copy(archivedCaptureIds = emptySet())))
        assertEquals(ReceiptUndoRefusal.CHANGED, receiptUndoRefusal(undo, left.copy(captures = listOf(capture("c1", taskId = "t9", body = "ask Sam — lease ends in May")))))
    }

    @Test fun `receipts from before the stamps refuse to delete, still reopen`() {
        assertEquals(ReceiptUndoRefusal.TOO_OLD, receiptUndoRefusal(ReceiptUndo.deleteTask("t1"), state(listOf(task()))))
        assertEquals(ReceiptUndoRefusal.TOO_OLD, receiptUndoRefusal(ReceiptUndo(ReceiptUndoKind.DELETE_TASKS, "a,b"), state()))
        assertEquals(ReceiptUndoRefusal.TOO_OLD, receiptUndoRefusal(ReceiptUndo.forgetFact("f1"), state(facts = listOf(fact()))))
        assertEquals(ReceiptUndoRefusal.TOO_OLD, receiptUndoRefusal(ReceiptUndo.deleteCapture("c1"), state(captures = listOf(capture("c1")))))
        assertNull(receiptUndoRefusal(ReceiptUndo.uncompleteTask("t1"), state(listOf(task(done = true)))))
        assertNull(receiptUndoRefusal(ReceiptUndo.cancelCall("k1"), state()))
    }

    @Test fun `a row already in the undo's target state is skipped, not refused`() {
        val reopen = stampReceiptUndo(ReceiptUndo.uncompleteTask("t1"), state(listOf(task(done = true))))
        assertNull("reopened by hand since", receiptUndoRefusal(reopen, state(listOf(task(name = "Renamed")))))
        val recomplete = stampReceiptUndo(ReceiptUndo.completeTask("t1"), state(listOf(task())))
        assertNull("ticked by hand since", receiptUndoRefusal(recomplete, state(listOf(task(name = "Renamed", done = true)))))
        assertEquals(ReceiptUndoRefusal.CHANGED, receiptUndoRefusal(recomplete, state(listOf(task(name = "Renamed")))))
    }

    @Test fun `create then complete in one turn undo in turn, each following the turn's own writes`() {
        // "Created" is stamped right after the create; the turn's own complete
        // carries it forward, so both match the row as the turn left it.
        val made = state(listOf(task()))
        val left = state(listOf(task(done = true)))
        val created = advanceReceiptUndo(stampReceiptUndo(ReceiptUndo.deleteTask("t1"), made), before = made, after = left)
        val completed = stampReceiptUndo(ReceiptUndo.uncompleteTask("t1"), left)
        assertNull(receiptUndoRefusal(created, left))
        assertNull(receiptUndoRefusal(completed, left))
        // Undoing "Completed" reopens it; "Created" follows that revert.
        val reopened = state(listOf(task()))
        assertEquals(ReceiptUndoRefusal.CHANGED, receiptUndoRefusal(created, reopened))
        assertNull(receiptUndoRefusal(advanceReceiptUndo(created, before = left, after = reopened, keys = completed.stamps.keys), reopened))
    }

    @Test fun `an edit made between the turn's writes is never carried into an earlier receipt`() {
        // During a call the assistant creates t1, the user renames it in the app,
        // then the assistant schedules it: "Created" must not take the rename.
        val made = state(listOf(task()))
        val created = stampReceiptUndo(ReceiptUndo.deleteTask("t1"), made)
        val renamed = state(listOf(task(name = "Lease letter to Sam")))
        val scheduled = state(listOf(task(name = "Lease letter to Sam")), listOf(block("b1", "t1")))
        assertEquals(ReceiptUndoRefusal.CHANGED, receiptUndoRefusal(advanceReceiptUndo(created, before = renamed, after = scheduled), scheduled))
        // A receipt persisted before the stamps has nothing to carry.
        assertEquals(ReceiptUndo.deleteTask("t1"), advanceReceiptUndo(ReceiptUndo.deleteTask("t1"), made, scheduled))
    }

    @Test fun `undoing a later receipt never carries a row it did not check into an earlier one`() {
        // One turn: create_tasks [t1, t2], then complete_tasks [t1, t2].
        val left = state(listOf(task(done = true), task("t2", done = true)))
        val created = stampReceiptUndo(ReceiptUndo.deleteTasks(listOf("t1", "t2")), left)
        val completed = stampReceiptUndo(ReceiptUndo.uncompleteTasks(listOf("t1", "t2")), left)
        // The user reopens t2 by hand and renames it. Undo "Completed" only
        // writes (and checks) t1, so it goes ahead...
        val before = state(listOf(task(done = true), task("t2", name = "Book the dentist for May")))
        assertNull(receiptUndoRefusal(completed, before))
        val after = state(listOf(task(), task("t2", name = "Book the dentist for May")))
        // ...but "Created" keeps t2's old stamp: its Undo must not delete it.
        val follows = advanceReceiptUndo(created, before, after, keys = completed.stamps.keys)
        assertEquals(ReceiptUndoRefusal.CHANGED_SOME, receiptUndoRefusal(follows, after))

        // Same with a note filed since: reopening doesn't look at notes, deleting does.
        val one = state(listOf(task(done = true)))
        val made = stampReceiptUndo(ReceiptUndo.deleteTask("t1"), one)
        val done = stampReceiptUndo(ReceiptUndo.uncompleteTask("t1"), one)
        val noted = one.copy(captures = listOf(capture("n1", taskId = "t1")))
        assertNull(receiptUndoRefusal(done, noted))
        val reopened = noted.copy(tasks = listOf(task()))
        assertEquals(ReceiptUndoRefusal.NOTES, receiptUndoRefusal(advanceReceiptUndo(made, noted, reopened, keys = done.stamps.keys), reopened))
    }

    // ── facts: a refine restores, a no-change save offers nothing ──

    @Test fun `a save that refined an existing fact restores its old wording instead of forgetting it`() {
        val before = fact()
        val refined = before.copy(fact = "Maleek — son, 9", source = ProfileFactSource.CHAT, updatedAt = "2026-09-23T07:00:00.000Z")
        val undo = factSaveUndo(ReceiptUndo.forgetFact("f1"), before, refined)!!
        assertEquals(ReceiptUndoKind.RESTORE_FACT, undo.kind)
        assertEquals(before, undo.prior)
        assertEquals(ReceiptUndoPlan.RestoreFact(before), planReceiptUndo(undo, emptyList(), "n"))
        // Nothing changed (the same words again) → no Undo that could forget it.
        assertNull(factSaveUndo(ReceiptUndo.forgetFact("f1"), before, before.copy(updatedAt = "2026-09-23T07:00:00.000Z")))
        // A brand-new fact keeps its forget.
        assertEquals(ReceiptUndo.forgetFact("f9"), factSaveUndo(ReceiptUndo.forgetFact("f9"), null, fact("f9")))
    }

    @Test fun `a fact edited in Settings since refuses its undo`() {
        val saved = fact(text = "Maleek — son, 9", source = ProfileFactSource.CHAT)
        val undo = stampReceiptUndo(ReceiptUndo.forgetFact("f1"), state(facts = listOf(saved)))
        assertNull(receiptUndoRefusal(undo, state(facts = listOf(saved.copy(updatedAt = "2026-09-24T00:00:00.000Z")))))
        assertEquals(ReceiptUndoRefusal.CHANGED, receiptUndoRefusal(undo, state(facts = listOf(saved.copy(fact = "Maleek — son, 10", source = ProfileFactSource.SETTINGS)))))
    }

    @Test fun `stamps and the prior fact survive the thread persistence`() {
        val json = Json { encodeDefaults = false }
        val left = state(listOf(task("t9")), captures = listOf(capture("c1", taskId = "t9")), archived = setOf("c1"), facts = listOf(fact()))
        for (undo in listOf(
            stampReceiptUndo(ReceiptUndo.unpromoteCapture("t9", "c1", unarchive = true), left),
            stampReceiptUndo(ReceiptUndo.restoreFact(fact()), left),
        )) {
            val back = json.decodeFromString<Receipt>(json.encodeToString(Receipt(ReceiptIcon.PLUS, "x", undo))).undo!!
            assertEquals(undo, back)
            assertNull(receiptUndoRefusal(back, left))
        }
        // A pre-stamp persisted receipt decodes unstamped.
        assertFalse(json.decodeFromString<Receipt>("""{"icon":"PLUS","label":"x","undo":{"kind":"DELETE_TASK","id":"a"}}""").undo!!.stamped)
    }
}
