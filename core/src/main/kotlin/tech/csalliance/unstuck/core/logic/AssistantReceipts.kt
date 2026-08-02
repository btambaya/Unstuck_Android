package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.Serializable
import tech.csalliance.unstuck.core.model.TaskItem

// Port of lib/assistant/receipts.ts. Action receipts — deterministic ✓-cards for
// what the agent ACTUALLY did. Derived client-side from each successful tool call
// (name + args + the executor's structured "ok: …" result), NEVER from model
// prose — so a receipt can't claim something that didn't happen. Undo (where
// offered) re-uses the same write paths at tap time.
//
// @Serializable because receipts ride along in the persisted conversation (they
// must survive an app restart, exactly like the turn they're attached to). They
// are stripped from the wire payload — see AssistantClient.ask.

@Serializable
enum class ReceiptIcon { PLUS, CALENDAR, CHECK, PENCIL, TRASH, LIST }

@Serializable
enum class ReceiptUndoKind { DELETE_TASK, UNCOMPLETE_TASK }

/** What tapping Undo reverses. Mirrors the web's discriminated union; a plain
 *  data class (not a sealed hierarchy) keeps it trivially serializable. */
@Serializable
data class ReceiptUndo(val kind: ReceiptUndoKind, val id: String)

@Serializable
data class Receipt(
    val icon: ReceiptIcon,
    val label: String,
    val undo: ReceiptUndo? = null,
    /** Set once Undo has been used (persisted so the button doesn't return). */
    val undone: Boolean = false,
)

/** The decoded tool-call arguments a receipt reads. The web passes its untyped
 *  `Record<string, unknown>` straight through; here the caller flattens its
 *  JsonObject into this typed carrier, so :core keeps JSON out of its public API
 *  and the tests need no JSON at all. */
data class ReceiptArgs(
    val taskId: String? = null,
    val date: String? = null,
    val startTime: String? = null,
    val later: Boolean? = null,
    val kind: String? = null,
)

private val QUOTED = Regex("\"([^\"]*)\"")
private val ID_AFTER_EQ = Regex("id=(\\S+)")

private fun quoted(s: String): String? = QUOTED.find(s)?.groupValues?.get(1)

/** Build the receipt for one SUCCESSFUL tool call (result starts "ok"). [tasks]
 *  resolves live entities for undo targets. Returns null for read-only tools and
 *  unrecognized results — no receipt beats a wrong receipt. */
fun deriveReceipt(name: String, args: ReceiptArgs, result: String, tasks: List<TaskItem>): Receipt? {
    if (!result.startsWith("ok")) return null
    return when (name) {
        "create_task" -> {
            val id = ID_AFTER_EQ.find(result)?.groupValues?.get(1)
            val nm = quoted(result) ?: "task"
            Receipt(ReceiptIcon.PLUS, "Created “$nm”", id?.let { ReceiptUndo(ReceiptUndoKind.DELETE_TASK, it) })
        }
        "schedule_task" -> {
            val nm = quoted(result) ?: "task"
            val date = args.date.orEmpty()
            val time = args.startTime.orEmpty()
            val suffix = (if (date.isNotEmpty()) " · $date" else "") + (if (time.isNotEmpty()) " $time" else "")
            Receipt(ReceiptIcon.CALENDAR, "Scheduled “$nm”$suffix")
        }
        "update_task" -> Receipt(ReceiptIcon.PENCIL, "Updated “${quoted(result) ?: "task"}”")
        "set_task_later" -> {
            val nm = args.taskId?.let { id -> tasks.firstOrNull { it.id == id }?.name }
            val toLater = args.later != false
            val what = if (toLater) "Moved to Later" else "Brought back from Later"
            Receipt(ReceiptIcon.PENCIL, what + (if (nm != null) " — “$nm”" else ""))
        }
        "set_task_recurrence" -> {
            val nm = args.taskId?.let { id -> tasks.firstOrNull { it.id == id }?.name }
            val suffix = if (nm != null) " — “$nm”" else ""
            val label = if (args.kind != null) "Repeats ${args.kind}$suffix" else "Repeat removed$suffix"
            Receipt(ReceiptIcon.CALENDAR, label)
        }
        "complete_task" -> {
            val nm = quoted(result) ?: "task"
            val t = tasks.firstOrNull { it.name == nm && it.done }
            Receipt(ReceiptIcon.CHECK, "Completed “$nm”", t?.let { ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, it.id) })
        }
        "delete_task" -> Receipt(ReceiptIcon.TRASH, "Deleted “${quoted(result) ?: "task"}”")
        "create_list" -> Receipt(ReceiptIcon.LIST, "Created list “${quoted(result) ?: "list"}”")
        "add_to_list" -> Receipt(ReceiptIcon.LIST, "Added to “${quoted(result) ?: "list"}”")
        "promote_item_to_task" -> Receipt(ReceiptIcon.PLUS, "Promoted “${quoted(result) ?: "item"}” to a task")
        else -> null
    }
}

/** The write a receipt's Undo has to perform. Pure: [planReceiptUndo] decides
 *  WHAT to do (so it's testable without a store); the ViewModel performs it.
 *  Splitting the web's single runReceiptUndo() this way keeps :core free of the
 *  suspending write layer the Android store needs. */
sealed class ReceiptUndoPlan {
    data class Remove(val taskId: String) : ReceiptUndoPlan()
    /** Write this task back with done cleared. */
    data class Restore(val task: TaskItem) : ReceiptUndoPlan()
}

/** Plan a receipt's undo against the live task list. Null when it can't be
 *  applied (the task is gone) — the caller then leaves the receipt untouched so
 *  the button doesn't falsely flip to "undone". [nowIso] stamps updatedAt. */
fun planReceiptUndo(undo: ReceiptUndo, tasks: List<TaskItem>, nowIso: String): ReceiptUndoPlan? = when (undo.kind) {
    ReceiptUndoKind.DELETE_TASK -> ReceiptUndoPlan.Remove(undo.id)
    ReceiptUndoKind.UNCOMPLETE_TASK -> tasks.firstOrNull { it.id == undo.id }
        ?.let { ReceiptUndoPlan.Restore(it.copy(done = false, completedAt = null, updatedAt = nowIso)) }
}
