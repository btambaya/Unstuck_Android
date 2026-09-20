package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.core.model.TaskItem

// Port of lib/assistant/receipts.ts (+ AssistantReceipts.swift). Action
// receipts — deterministic ✓-cards for what the agent ACTUALLY did. Derived
// client-side from each successful tool call (name + args + the executor's
// structured "ok: …" result), NEVER from model prose — so a receipt can't
// claim something that didn't happen. Undo (where offered) is PLANNED here
// (`planReceiptUndo`) and applied by the app layer's write paths at tap time.
//
// Undo targets resolve by the `id=` the executor puts in its result string —
// never by name (two "Call mum"s, one done last week: a name lookup reopened
// the wrong one — Android defect F8, web flow review 2026-08-30).
//
// @Serializable because receipts ride along in the persisted conversation (they
// must survive an app restart, exactly like the turn they're attached to). They
// are stripped from the wire payload — see AssistantClient.ask.

@Serializable
enum class ReceiptIcon { PLUS, CALENDAR, CHECK, PENCIL, TRASH, LIST }

/** What tapping Undo reverses. Cases mirror the web's `ReceiptUndo.kind` one for one. */
@Serializable
enum class ReceiptUndoKind {
    DELETE_TASK, UNCOMPLETE_TASK,
    UNCOMPLETE_TASKS, DELETE_TASKS, FORGET_FACT, DELETE_CAPTURE, COMPLETE_TASK,
    /** Part B ("Unstuck calls you"): undo of `request_call` — a NETWORK write. */
    CANCEL_CALL,
}

/** What tapping Undo reverses. A plain data class (not a sealed hierarchy)
 *  keeps it trivially serializable; the single-target kinds use [id], the bulk
 *  kinds ([ReceiptUndoKind.DELETE_TASKS] / [ReceiptUndoKind.UNCOMPLETE_TASKS])
 *  use [ids]. Both fields default so pre-2026-09 persisted receipts
 *  (`{kind, id}`) still decode. */
@Serializable
data class ReceiptUndo(val kind: ReceiptUndoKind, val id: String = "", val ids: List<String> = emptyList()) {
    /** The task ids this undo touches (empty for a fact / capture / call undo). */
    val taskIds: List<String>
        get() = when (kind) {
            ReceiptUndoKind.DELETE_TASK, ReceiptUndoKind.UNCOMPLETE_TASK, ReceiptUndoKind.COMPLETE_TASK -> listOf(id)
            ReceiptUndoKind.DELETE_TASKS, ReceiptUndoKind.UNCOMPLETE_TASKS -> ids
            ReceiptUndoKind.FORGET_FACT, ReceiptUndoKind.DELETE_CAPTURE, ReceiptUndoKind.CANCEL_CALL -> emptyList()
        }

    companion object {
        fun deleteTask(id: String) = ReceiptUndo(ReceiptUndoKind.DELETE_TASK, id)
        fun uncompleteTask(id: String) = ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, id)
        fun completeTask(id: String) = ReceiptUndo(ReceiptUndoKind.COMPLETE_TASK, id)
        fun forgetFact(id: String) = ReceiptUndo(ReceiptUndoKind.FORGET_FACT, id)
        fun deleteCapture(id: String) = ReceiptUndo(ReceiptUndoKind.DELETE_CAPTURE, id)
        fun cancelCall(id: String) = ReceiptUndo(ReceiptUndoKind.CANCEL_CALL, id)
        fun deleteTasks(ids: List<String>) = ReceiptUndo(ReceiptUndoKind.DELETE_TASKS, ids = ids)
        fun uncompleteTasks(ids: List<String>) = ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASKS, ids = ids)
    }
}

@Serializable
data class Receipt(
    val icon: ReceiptIcon,
    val label: String,
    val undo: ReceiptUndo? = null,
    /** Set once Undo has been used (persisted so the button doesn't return). */
    val undone: Boolean = false,
) {
    /** True while this receipt still offers a working Undo. */
    val isUndoable: Boolean get() = undo != null && !undone
}

/** The decoded tool-call arguments a receipt reads. The web passes its untyped
 *  `Record<string, unknown>` straight through; here the caller flattens its
 *  JsonObject into this typed carrier (or uses [receiptArgsFromJson]), so :core
 *  keeps JSON out of its public API and the tests need no JSON at all. */
data class ReceiptArgs(
    val taskId: String? = null,
    val date: String? = null,
    val startTime: String? = null,
    val later: Boolean? = null,
    val kind: String? = null,
)

private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** [ReceiptArgs] from the raw tool-call `arguments` JSON; empty on malformed input. */
fun receiptArgsFromJson(argumentsJson: String): ReceiptArgs {
    val obj = runCatching { LENIENT_JSON.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return ReceiptArgs()
    fun str(k: String): String? = (obj[k]?.takeIf { it !is JsonNull } as? JsonPrimitive)?.contentOrNull
    return ReceiptArgs(
        taskId = str("taskId"), date = str("date"), startTime = str("startTime"),
        later = (obj["later"] as? JsonPrimitive)?.booleanOrNull, kind = str("kind"),
    )
}

/** The OUTERMOST `"…"` span in a result string — how the executor names the
 *  entity. The name-bearing results each carry exactly ONE quoted segment, so
 *  spanning the outermost quotes keeps names that contain their own quote
 *  chars intact (web `quoted`, audit 2026-08-29). */
internal fun quotedFragment(s: String): String? {
    val open = s.indexOf('"')
    val close = s.lastIndexOf('"')
    if (open < 0 || close <= open) return null
    return s.substring(open + 1, close)
}

/** The `id=…` token in a result string (up to the next whitespace). */
private fun idFragment(s: String): String? {
    val i = s.indexOf("id=")
    if (i < 0) return null
    val rest = s.substring(i + 3).takeWhile { !it.isWhitespace() }
    return rest.ifEmpty { null }
}

/** The `ids=a,b,c` token in a bulk result, split and emptied of blanks. */
private fun idsFragment(s: String): List<String> {
    val i = s.indexOf("ids=")
    if (i < 0) return emptyList()
    return s.substring(i + 4).takeWhile { !it.isWhitespace() }.split(",").filter { it.isNotEmpty() }
}

/** The integer right after `"<word> "` in a result ("created 3 tasks" → "3"), as the web's `/created (\d+)/`. */
private fun countAfter(word: String, s: String): String {
    val i = s.indexOf("$word ")
    if (i < 0) return "?"
    val digits = s.substring(i + word.length + 1).takeWhile { it.isDigit() }
    return digits.ifEmpty { "?" }
}

/** `result` minus its leading "ok: ". */
private fun stripOk(s: String): String = if (s.startsWith("ok: ")) s.substring(4) else s

/** `result` minus a trailing " (…)" parenthetical — the web's `/\s*\(.*\)$/`
 *  (greedy: from the FIRST "(" when the string ends in ")"). */
private fun stripTrailingParenthetical(s: String): String {
    if (!s.endsWith(")")) return s
    val open = s.indexOf('(')
    if (open < 0) return s
    return s.substring(0, open).trimEnd()
}

/** `result` minus everything from the first " — " (the web's `/ — .*$/`). */
private fun stripAfterDash(s: String): String {
    val i = s.indexOf(" — ")
    return if (i < 0) s else s.substring(0, i)
}

private val ECHO_TOOLS = setOf(
    "rename_list", "archive_list", "delete_list", "edit_list_item", "remove_list_item", "set_list_item_done",
    "create_area", "rename_area", "delete_area", "create_tag", "rename_tag", "delete_tag",
    "unshare_task", "set_usable_minutes", "set_notification_level", "set_reminder_lead", "set_ritual",
    // 2026-09-20 tooling rewrite (docs/assistant-tooling-rules.md §4): the new
    // write tools whose `ok:` line already reads as a card.
    "recolor_list", "pin_list_item", "leave_list", "set_task_reminder", "set_theme", "set_focus_defaults", "set_ambient_sound",
)

/** add_to_list names BOTH the item and the list (`ok: added "Milk" to "Groceries"`)
 *  since 2026-09-20; the pre-rewrite `ok: added to "Groceries"` still decodes. */
private val ADDED_TO_LIST_RE = Regex("^ok: added \"(.*)\" to \"(.*)\"")

/** Build the receipt for one SUCCESSFUL tool call (result starts "ok").
 *  [tasks] resolves live entities for undo targets and quiet-win move counts
 *  (include this turn's scratch rows — the store lags the optimistic write);
 *  [tone] (from [toneFromFacts]) phrases the quiet-win line. Returns null for
 *  read-only tools and unrecognized results — no receipt beats a wrong receipt. */
fun deriveReceipt(name: String, args: ReceiptArgs, result: String, tasks: List<TaskItem>, tone: Tone = Tone.GENTLE): Receipt? {
    if (!result.startsWith("ok")) return null
    return when (name) {
        "create_task" -> {
            val nm = quotedFragment(result) ?: "task"
            Receipt(ReceiptIcon.PLUS, "Created “$nm”", idFragment(result)?.let { ReceiptUndo.deleteTask(it) })
        }
        "schedule_task" -> {
            val nm = quotedFragment(result) ?: "task"
            val date = args.date.orEmpty()
            val time = args.startTime.orEmpty()
            val suffix = (if (date.isNotEmpty()) " · $date" else "") + (if (time.isNotEmpty()) " $time" else "")
            Receipt(ReceiptIcon.CALENDAR, "Scheduled “$nm”$suffix")
        }
        "update_task" -> Receipt(ReceiptIcon.PENCIL, "Updated “${quotedFragment(result) ?: "task"}”")
        "set_task_later" -> {
            val nm = args.taskId?.let { id -> tasks.firstOrNull { it.id == id }?.name }
            // Web: `args.later !== false` — anything but an explicit false is "to Later".
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
            val nm = quotedFragment(result) ?: "task"
            // The executor's id ONLY — resolving by NAME un-completed the wrong
            // duplicate (F8). No id → a receipt without Undo, never a wrong Undo.
            val t = idFragment(result)?.let { id -> tasks.firstOrNull { it.id == id } }
            // Quiet win: a task that dodged them 3+ times deserves more than a
            // checkmark — the assistant KNOWS this one was the hard kind of done.
            val win = t?.let { quietWinLine(nm, it.moveCount ?: 0, tone) }
            Receipt(ReceiptIcon.CHECK, win ?: "Completed “$nm”", t?.let { ReceiptUndo.uncompleteTask(it.id) })
        }
        "create_tasks" -> {
            val ids = idsFragment(result)
            Receipt(ReceiptIcon.PLUS, "Created ${countAfter("created", result)} tasks", if (ids.isEmpty()) null else ReceiptUndo.deleteTasks(ids))
        }
        "complete_tasks" -> {
            val ids = idsFragment(result)
            Receipt(ReceiptIcon.CHECK, "Completed ${countAfter("completed", result)} tasks", if (ids.isEmpty()) null else ReceiptUndo.uncompleteTasks(ids))
        }
        "delete_task" -> Receipt(ReceiptIcon.TRASH, "Deleted “${quotedFragment(result) ?: "task"}”")
        "save_profile_fact" -> {
            // The learning receipt IS the consent UX: every remembered fact is
            // visible the moment it's saved, with a one-tap forget.
            Receipt(ReceiptIcon.PENCIL, "Noted: ${quotedFragment(result) ?: "that"}", idFragment(result)?.let { ReceiptUndo.forgetFact(it) })
        }

        // ── full app surface (2026-09-02) ──
        "uncomplete_task" ->
            Receipt(ReceiptIcon.CHECK, "Reopened ${quotedFragment(result) ?: "task"}", idFragment(result)?.let { ReceiptUndo.completeTask(it) })
        "unschedule_task" -> Receipt(ReceiptIcon.CALENDAR, "Unscheduled ${quotedFragment(result) ?: "task"}")
        "skip_occurrence" -> Receipt(ReceiptIcon.CALENDAR, "Skipped ${quotedFragment(result) ?: "task"} today")
        "complete_occurrence" -> Receipt(ReceiptIcon.CHECK, "Done for today: ${quotedFragment(result) ?: "task"}")
        "block_time" ->
            Receipt(ReceiptIcon.CALENDAR, "Blocked ${quotedFragment(result) ?: "time"}", idFragment(result)?.let { ReceiptUndo.deleteTask(it) })
        "carry_to_tomorrow" -> Receipt(ReceiptIcon.CALENDAR, stripAfterDash(stripOk(result)))
        "start_focus" -> Receipt(ReceiptIcon.CHECK, "Focus started: ${quotedFragment(result) ?: "task"}")
        "pause_focus" -> Receipt(ReceiptIcon.PENCIL, "Focus paused")
        "resume_focus" -> Receipt(ReceiptIcon.PENCIL, "Focus resumed")
        "extend_focus" -> Receipt(ReceiptIcon.PENCIL, stripOk(result))
        "cancel_focus" -> Receipt(ReceiptIcon.PENCIL, "Focus cancelled")
        "add_capture" ->
            Receipt(ReceiptIcon.PLUS, "Captured: ${quotedFragment(result) ?: ""}", idFragment(result)?.let { ReceiptUndo.deleteCapture(it) })
        "promote_capture" ->
            Receipt(ReceiptIcon.PLUS, "Task from capture: ${quotedFragment(result) ?: ""}", idFragment(result)?.let { ReceiptUndo.deleteTask(it) })
        "resolve_capture" -> Receipt(ReceiptIcon.CHECK, "Resolved: ${quotedFragment(result) ?: "capture"}")
        "delete_capture" -> Receipt(ReceiptIcon.PENCIL, "Deleted capture")
        in ECHO_TOOLS -> {
            val icon = when {
                name.startsWith("create") -> ReceiptIcon.PLUS
                name.contains("done") -> ReceiptIcon.CHECK
                else -> ReceiptIcon.PENCIL
            }
            Receipt(icon, stripTrailingParenthetical(stripOk(result)))
        }
        "forget_fact" -> Receipt(ReceiptIcon.PENCIL, "Forgot: ${quotedFragment(result) ?: "that"}")
        "create_list" -> Receipt(ReceiptIcon.LIST, "Created list “${quotedFragment(result) ?: "list"}”")
        "add_to_list" -> {
            val m = ADDED_TO_LIST_RE.find(result)
            if (m != null) Receipt(ReceiptIcon.LIST, "Added “${m.groupValues[1]}” to “${m.groupValues[2]}”")
            else Receipt(ReceiptIcon.LIST, "Added to “${quotedFragment(result) ?: "list"}”")
        }
        // ── 2026-09-20 tooling rewrite ──
        "restore_capture" -> Receipt(ReceiptIcon.PLUS, "Restored: ${quotedFragment(result) ?: "capture"}")
        // `ok: finished focus on "X" — logged 12m[, task marked done]`: the card
        // says whether the task was closed, since that is the part Undo can't reach.
        "finish_focus" -> Receipt(ReceiptIcon.CHECK, "Focus finished: ${quotedFragment(result) ?: "task"}${if (result.contains("marked done")) " · done" else ""}")
        "promote_item_to_task" -> Receipt(ReceiptIcon.PLUS, "Promoted “${quotedFragment(result) ?: "item"}” to a task")

        // ── calls ("Unstuck calls you", Part B) — "Call booked Thu 14:45 — speak to James · 4 notes" ──
        "request_call" -> {
            val m = CALL_BOOKED_RE.find(result) ?: return null
            val (date, hm, label, n, id) = m.destructured
            Receipt(ReceiptIcon.CALENDAR, "Call booked ${shortWeekday(date)} $hm — $label · $n note${if (n == "1") "" else "s"}", ReceiptUndo.cancelCall(id))
        }
        "update_call" -> {
            val m = CALL_UPDATED_RE.find(result) ?: return null
            val (label, date, hm, n) = m.destructured
            Receipt(ReceiptIcon.PENCIL, "Call updated ${shortWeekday(date)} $hm — $label · $n note${if (n == "1") "" else "s"}")
        }
        "cancel_call" -> Receipt(ReceiptIcon.TRASH, "Call cancelled — ${quotedFragment(result) ?: "call"}")
        else -> null
    }
}

/** 'YYYY-MM-DD' → 'Thu' (local calendar day, no timezone shift). */
private fun shortWeekday(date: String): String =
    listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[IsoDate.dayOfWeek(date)]

private val CALL_BOOKED_RE = Regex("^ok: call booked (\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}) \"(.*)\" \\((\\d+) notes?\\) id=(\\S+)")
private val CALL_UPDATED_RE = Regex("^ok: updated call \"(.*)\" — (\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}), (\\d+) notes?")

/** The write a receipt's Undo has to perform. Pure: [planReceiptUndo] decides
 *  WHAT to do (so it's testable without a store); the ViewModel performs it
 *  through the same write paths the UI uses (delete / save), so sync + the
 *  outbox are honoured. Splitting the web's single runReceiptUndo() this way
 *  keeps :core free of the suspending write layer the Android store needs. */
sealed class ReceiptUndoPlan {
    /** Remove the task AND its calendar blocks (the executor's delete_task
     *  cascade — ghost blocks were a confirmed flow bug, 2026-08-30). */
    data class Remove(val taskId: String) : ReceiptUndoPlan()
    /** Write this task back with done cleared. */
    data class Restore(val task: TaskItem) : ReceiptUndoPlan()
    /** Several tasks (+ their blocks) to remove — undo of `create_tasks`. */
    data class RemoveMany(val taskIds: List<String>) : ReceiptUndoPlan()
    /** Several tasks flipped back open — undo of `complete_tasks` (only the ones still present). */
    data class RestoreMany(val tasks: List<TaskItem>) : ReceiptUndoPlan()
    /** Soft-delete the remembered fact — undo of `save_profile_fact`. */
    data class ForgetFact(val factId: String) : ReceiptUndoPlan()
    /** Remove the capture — undo of `add_capture`. */
    data class DeleteCapture(val captureId: String) : ReceiptUndoPlan()
    /** The task with `done` set (stamped nowIso) — undo of `uncomplete_task`. */
    data class Complete(val task: TaskItem) : ReceiptUndoPlan()
    /** Cancel the booked call — undo of `request_call` (a NETWORK write:
     *  the app marks the receipt undone only once the server accepted). */
    data class CancelCall(val callId: String) : ReceiptUndoPlan()
}

/** Plan a receipt's undo against the live task list. Null when it can't be
 *  applied (the task is gone) — the caller then leaves the receipt untouched so
 *  the button doesn't falsely flip to "undone". [nowIso] stamps updatedAt. */
fun planReceiptUndo(undo: ReceiptUndo, tasks: List<TaskItem>, nowIso: String): ReceiptUndoPlan? {
    fun reopened(t: TaskItem) = t.copy(done = false, completedAt = null, updatedAt = nowIso)
    return when (undo.kind) {
        // Deleting an id that no longer exists is a harmless no-op, and the web
        // reports success unconditionally here — keep the receipt struck through.
        ReceiptUndoKind.DELETE_TASK -> ReceiptUndoPlan.Remove(undo.id)
        ReceiptUndoKind.UNCOMPLETE_TASK -> tasks.firstOrNull { it.id == undo.id }?.let { ReceiptUndoPlan.Restore(reopened(it)) }
        ReceiptUndoKind.DELETE_TASKS -> ReceiptUndoPlan.RemoveMany(undo.ids)
        ReceiptUndoKind.UNCOMPLETE_TASKS -> {
            val present = undo.ids.mapNotNull { id -> tasks.firstOrNull { it.id == id } }.map(::reopened)
            if (present.isEmpty()) null else ReceiptUndoPlan.RestoreMany(present)
        }
        ReceiptUndoKind.FORGET_FACT -> ReceiptUndoPlan.ForgetFact(undo.id)
        ReceiptUndoKind.DELETE_CAPTURE -> ReceiptUndoPlan.DeleteCapture(undo.id)
        ReceiptUndoKind.COMPLETE_TASK -> tasks.firstOrNull { it.id == undo.id }
            ?.let { ReceiptUndoPlan.Complete(it.copy(done = true, completedAt = nowIso, updatedAt = nowIso)) }
        ReceiptUndoKind.CANCEL_CALL -> ReceiptUndoPlan.CancelCall(undo.id)
    }
}
