package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.Receipt
import tech.csalliance.unstuck.core.logic.ReceiptArgs
import tech.csalliance.unstuck.core.logic.ReceiptIcon
import tech.csalliance.unstuck.core.logic.ReceiptUndo
import tech.csalliance.unstuck.core.logic.ReceiptUndoKind
import tech.csalliance.unstuck.core.logic.ReceiptUndoPlan
import tech.csalliance.unstuck.core.logic.deriveReceipt
import tech.csalliance.unstuck.core.logic.planReceiptUndo
import tech.csalliance.unstuck.core.model.TaskItem

// 1:1 with lib/assistant/receipts.test.ts. A receipt is derived from the tool
// name + args + the executor's structured result — NEVER from model prose — so
// it can't claim something that didn't happen.
class AssistantReceiptsTest {

    private fun task(id: String = "t1", name: String = "Report", done: Boolean = false, completedAt: String? = null) =
        TaskItem(
            id = id, name = name, estimateMin = 25, done = done, completedAt = completedAt,
            createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z",
        )

    @Test fun `create_task - receipt with delete undo carrying the created id`() {
        val r = deriveReceipt("create_task", ReceiptArgs(), "ok: created task id=abc123 name=\"Dentist\"", emptyList())
        assertEquals(
            Receipt(ReceiptIcon.PLUS, "Created “Dentist”", ReceiptUndo(ReceiptUndoKind.DELETE_TASK, "abc123")),
            r,
        )
    }

    @Test fun `schedule_task carries date and time from args`() {
        val r = deriveReceipt(
            "schedule_task", ReceiptArgs(date = "2026-08-04", startTime = "15:00"),
            "ok: scheduled \"Dentist\" 2026-08-04 15:00", emptyList(),
        )
        assertEquals("Scheduled “Dentist” · 2026-08-04 15:00", r!!.label)
        assertNull(r.undo)
    }

    @Test fun `complete_task finds the completed task for the uncomplete undo`() {
        val done = task(id = "x9", name = "Report", done = true)
        val r = deriveReceipt("complete_task", ReceiptArgs(), "ok: completed \"Report\"", listOf(done))
        assertEquals(ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, "x9"), r!!.undo)
    }

    @Test fun `failed results produce NO receipt`() {
        assertNull(deriveReceipt("create_task", ReceiptArgs(), "error: nope", emptyList()))
    }

    @Test fun `read-only and unknown tools produce NO receipt`() {
        assertNull(deriveReceipt("list_tasks", ReceiptArgs(), "ok: 3 tasks", emptyList()))
    }

    @Test fun `set_task_later names the task and distinguishes both directions`() {
        val t = task(id = "t7", name = "Taxes")
        assertEquals(
            "Moved to Later — “Taxes”",
            deriveReceipt("set_task_later", ReceiptArgs(taskId = "t7"), "ok", listOf(t))!!.label,
        )
        assertEquals(
            "Brought back from Later — “Taxes”",
            deriveReceipt("set_task_later", ReceiptArgs(taskId = "t7", later = false), "ok", listOf(t))!!.label,
        )
    }

    @Test fun `set_task_recurrence reports the kind, or its removal`() {
        val t = task(id = "t7", name = "Taxes")
        assertEquals(
            "Repeats weekly — “Taxes”",
            deriveReceipt("set_task_recurrence", ReceiptArgs(taskId = "t7", kind = "weekly"), "ok", listOf(t))!!.label,
        )
        assertEquals(
            "Repeat removed — “Taxes”",
            deriveReceipt("set_task_recurrence", ReceiptArgs(taskId = "t7"), "ok", listOf(t))!!.label,
        )
    }

    @Test fun `every write tool that reports ok yields a receipt`() {
        val names = listOf("update_task", "delete_task", "create_list", "add_to_list", "promote_item_to_task")
        names.forEach { n ->
            assertNotNull(n, deriveReceipt(n, ReceiptArgs(), "ok: did \"Thing\"", emptyList()))
        }
    }

    @Test fun `planReceiptUndo - delete undo removes the created task`() {
        val plan = planReceiptUndo(ReceiptUndo(ReceiptUndoKind.DELETE_TASK, "abc"), emptyList(), "2026-08-02T12:00:00Z")
        assertEquals(ReceiptUndoPlan.Remove("abc"), plan)
    }

    @Test fun `planReceiptUndo - uncomplete flips done back off, missing task plans nothing`() {
        val t = task(id = "x9", done = true, completedAt = "2026-08-02T10:00:00Z")
        val plan = planReceiptUndo(
            ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, "x9"), listOf(t), "2026-08-02T12:00:00Z",
        ) as ReceiptUndoPlan.Restore
        assertTrue(!plan.task.done)
        assertNull(plan.task.completedAt)
        assertEquals("2026-08-02T12:00:00Z", plan.task.updatedAt)

        assertNull(planReceiptUndo(ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, "gone"), emptyList(), "x"))
    }
}
