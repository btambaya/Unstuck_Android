package tech.csalliance.unstuck.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.csalliance.unstuck.core.logic.Receipt
import tech.csalliance.unstuck.core.logic.ReceiptIcon
import tech.csalliance.unstuck.core.logic.ReceiptUndo
import tech.csalliance.unstuck.sync.ChatMessage

/** "Undo all N changes" reverts only the turn that JUST finished. It reached
 *  back to the last turn with any unused Undo, however old: on Thursday, one
 *  tap deleted the tasks Monday's turn created (Android audit 2026-09-23, A17). */
class UndoAllTargetTest {
    private val now = 1_790_000_000_000L
    private val minute = 60_000L

    private fun created(name: String, id: String) = Receipt(ReceiptIcon.PLUS, "Created “$name”", ReceiptUndo.deleteTask(id))
    private fun turn(id: String, at: Long, vararg receipts: Receipt) =
        ChatMessage("assistant", "Done.", id = id, at = at, receipts = receipts.toList().ifEmpty { null })
    private fun user(text: String, at: Long) = ChatMessage("user", text, id = "u$at", at = at)

    @Test fun `the turn that just finished is the target, with what it will revert`() {
        val t = turn("m1", now - 2 * minute, created("Call mum", "a"), Receipt(ReceiptIcon.CALENDAR, "Scheduled “Call mum”"), created("Bins", "b"))
        val display = listOf(user("add call mum and bins", now - 3 * minute), t)
        assertEquals(t, undoAllTarget(display, now, emptySet()))
        assertEquals(listOf("Created “Call mum”", "Created “Bins”"), undoAllReceipts(t, emptySet()).map { it.label })
        // The sheet's clock ticks once a minute: a turn that landed after the
        // last tick still counts as just finished.
        val justNow = t.copy(at = now + 30_000)
        assertEquals(justNow, undoAllTarget(listOf(justNow), now, emptySet()))
    }

    @Test fun `an old turn is never reached`() {
        val monday = turn("m1", now - 3 * 24 * 60 * minute, created("Call mum", "a"))
        assertNull(undoAllTarget(listOf(monday), now, emptySet()))
        assertNull("15 minutes is the limit", undoAllTarget(listOf(monday.copy(at = now - 16 * minute)), now, emptySet()))
        // A later turn that changed nothing does not hand the chip back to an older one.
        val later = listOf(monday.copy(at = now - 5 * minute), user("thanks", now - 2 * minute), turn("m2", now - minute))
        assertNull(undoAllTarget(later, now, emptySet()))
        // Nor does a missing landing time.
        assertNull(undoAllTarget(listOf(monday.copy(at = null)), now, emptySet()))
    }

    @Test fun `used and refused undos are not counted`() {
        val t = turn("m1", now - minute, created("Call mum", "a").copy(undone = true), created("Bins", "b"))
        assertEquals(listOf("Created “Bins”"), undoAllReceipts(t, emptySet()).map { it.label })
        assertNull("its one live undo was refused", undoAllTarget(listOf(t), now, setOf(receiptUndoKey("m1", 1))))
    }
}
