package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.core.time.Time
import java.security.MessageDigest

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

/** What tapping Undo reverses. Cases mirror the web's `ReceiptUndo.kind` one for one,
 *  bar the two Android-only exact undos at the end (Android audit 2026-09-23, A17). */
@Serializable
enum class ReceiptUndoKind {
    DELETE_TASK, UNCOMPLETE_TASK,
    UNCOMPLETE_TASKS, DELETE_TASKS, FORGET_FACT, DELETE_CAPTURE, COMPLETE_TASK,
    /** Part B ("Unstuck calls you"): undo of `request_call` — a NETWORK write. */
    CANCEL_CALL,
    /** Undo of `promote_capture` (Android only): the task it made goes, and the
     *  capture goes back as it was — unlinked if the promote linked it, back in
     *  the inbox if the promote took it out. Its old DELETE_TASK undo cascaded
     *  into the capture itself and hard-deleted the user's note on every device
     *  (Android audit 2026-09-23, A17). */
    UNPROMOTE_CAPTURE,
    /** Undo of a fact save that REFINED an existing fact in place (Android only):
     *  the old wording comes back. Forgetting the id deleted a fact the user had
     *  long before the turn (Android audit 2026-09-23, A17). */
    RESTORE_FACT,
}

/** What tapping Undo reverses. A plain data class (not a sealed hierarchy)
 *  keeps it trivially serializable; the single-target kinds use [id], the bulk
 *  kinds ([ReceiptUndoKind.DELETE_TASKS] / [ReceiptUndoKind.UNCOMPLETE_TASKS])
 *  use [ids]. Both fields default so pre-2026-09 persisted receipts
 *  (`{kind, id}`) still decode. */
@Serializable
data class ReceiptUndo(
    val kind: ReceiptUndoKind,
    val id: String = "",
    val ids: List<String> = emptyList(),
    /** UNPROMOTE_CAPTURE: the promoted capture ([id] is the task it made). */
    val captureId: String = "",
    /** UNPROMOTE_CAPTURE: the capture was in the inbox before the promote archived it. */
    val unarchive: Boolean = false,
    /** RESTORE_FACT: the fact as it stood before the turn refined it. */
    val prior: ProfileFact? = null,
    /** Fingerprints of the rows this undo writes, as the turn LEFT them
     *  ([stampReceiptUndo]) — checked at tap time by [receiptUndoRefusal]. */
    val stamps: Map<String, String> = emptyMap(),
    /** [stamps] were taken. False on receipts persisted before exact Undo. */
    val stamped: Boolean = false,
) {
    /** The task ids this undo touches (empty for a fact / capture / call undo). */
    val taskIds: List<String>
        get() = when (kind) {
            ReceiptUndoKind.DELETE_TASK, ReceiptUndoKind.UNCOMPLETE_TASK, ReceiptUndoKind.COMPLETE_TASK,
            ReceiptUndoKind.UNPROMOTE_CAPTURE -> listOf(id)
            ReceiptUndoKind.DELETE_TASKS, ReceiptUndoKind.UNCOMPLETE_TASKS -> ids
            ReceiptUndoKind.FORGET_FACT, ReceiptUndoKind.DELETE_CAPTURE, ReceiptUndoKind.CANCEL_CALL,
            ReceiptUndoKind.RESTORE_FACT -> emptyList()
        }

    /** The capture this undo writes, if any. */
    val captureTarget: String?
        get() = when (kind) {
            ReceiptUndoKind.DELETE_CAPTURE -> id
            ReceiptUndoKind.UNPROMOTE_CAPTURE -> captureId.ifEmpty { null }
            else -> null
        }

    /** The profile fact this undo writes, if any. */
    val factTarget: String?
        get() = if (kind == ReceiptUndoKind.FORGET_FACT || kind == ReceiptUndoKind.RESTORE_FACT) id else null

    companion object {
        fun deleteTask(id: String) = ReceiptUndo(ReceiptUndoKind.DELETE_TASK, id)
        fun uncompleteTask(id: String) = ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, id)
        fun completeTask(id: String) = ReceiptUndo(ReceiptUndoKind.COMPLETE_TASK, id)
        fun forgetFact(id: String) = ReceiptUndo(ReceiptUndoKind.FORGET_FACT, id)
        fun deleteCapture(id: String) = ReceiptUndo(ReceiptUndoKind.DELETE_CAPTURE, id)
        fun cancelCall(id: String) = ReceiptUndo(ReceiptUndoKind.CANCEL_CALL, id)
        fun deleteTasks(ids: List<String>) = ReceiptUndo(ReceiptUndoKind.DELETE_TASKS, ids = ids)
        fun uncompleteTasks(ids: List<String>) = ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASKS, ids = ids)
        fun unpromoteCapture(taskId: String, captureId: String, unarchive: Boolean = false) =
            ReceiptUndo(ReceiptUndoKind.UNPROMOTE_CAPTURE, taskId, captureId = captureId, unarchive = unarchive)
        fun restoreFact(prior: ProfileFact) = ReceiptUndo(ReceiptUndoKind.RESTORE_FACT, prior.id, prior = prior)
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
    /** promote_capture's capture — its receipt's undo puts that capture back. */
    val captureId: String? = null,
)

private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** [ReceiptArgs] from the raw tool-call `arguments` JSON; empty on malformed input. */
fun receiptArgsFromJson(argumentsJson: String): ReceiptArgs {
    val obj = runCatching { LENIENT_JSON.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return ReceiptArgs()
    fun str(k: String): String? = (obj[k]?.takeIf { it !is JsonNull } as? JsonPrimitive)?.contentOrNull
    return ReceiptArgs(
        taskId = str("taskId"), date = str("date"), startTime = str("startTime"),
        later = (obj["later"] as? JsonPrimitive)?.booleanOrNull, kind = str("kind"), captureId = str("captureId"),
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

/** The tail of an `ok:` line whose wish was ALREADY true, so nothing was
 *  written: stopping a repeat on a task that doesn't repeat, scheduling a task
 *  into the very slot it already has (AssistantTools, 2026-09-24 — Zubair's
 *  call). A success for the model to say plainly — and no receipt, which
 *  would claim a change (web receipts.ts `NOTHING_TO_CHANGE`). */
const val NOTHING_TO_CHANGE = " — nothing to change"

/** set_task_recurrence's ok line for an every-N-weeks rule: "now repeats every 2 weeks on …". */
private val EVERY_N_WEEKS_RESULT = Regex("\\bnow repeats every (\\d+) weeks\\b")

/** Build the receipt for one SUCCESSFUL tool call (result starts "ok").
 *  [tasks] resolves live entities for undo targets and quiet-win move counts
 *  (include this turn's scratch rows — the store lags the optimistic write);
 *  [tone] (from [toneFromFacts]) phrases the quiet-win line; [clock] is the
 *  phone's 12/24-hour preference for the times a card shows (the tool's own
 *  'HH:MM' is the 24-hour form). Returns null for read-only tools and
 *  unrecognized results — no receipt beats a wrong receipt. */
fun deriveReceipt(
    name: String,
    args: ReceiptArgs,
    result: String,
    tasks: List<TaskItem>,
    tone: Tone = Tone.GENTLE,
    clock: ClockMode = ClockMode.H24,
): Receipt? {
    if (!result.startsWith("ok")) return null
    if (result.endsWith(NOTHING_TO_CHANGE)) return null
    return when (name) {
        "create_task" -> {
            val nm = quotedFragment(result) ?: "task"
            Receipt(ReceiptIcon.PLUS, "Created “$nm”", idFragment(result)?.let { ReceiptUndo.deleteTask(it) })
        }
        "schedule_task" -> {
            val nm = quotedFragment(result) ?: "task"
            val date = args.date.orEmpty()
            val time = args.startTime.orEmpty().let { if (it.isEmpty()) it else ClockFormat.time(it, clock) }
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
            // kind "none" is the stop: it read "Repeats none" on the card (web parity, 2026-09-24).
            val kind = args.kind?.takeIf { it != "none" }
            // The rhythm comes from the RESULT, never the args: an omitted
            // intervalWeeks keeps a fortnightly series fortnightly (every-n-weeks
            // spec §7.3), so "weekly" in the args can still save every 2 weeks.
            val everyN = EVERY_N_WEEKS_RESULT.find(result)?.groupValues?.get(1)
            val label = when {
                kind != null && everyN != null -> "Repeats every $everyN weeks$suffix"
                kind != null -> "Repeats $kind$suffix"
                else -> "Repeat removed$suffix"
            }
            Receipt(ReceiptIcon.CALENDAR, label)
        }
        "complete_task" -> {
            val nm = quotedFragment(result) ?: "task"
            // A repeating task: complete_task ticked TODAY's occurrence —
            // complete_occurrence's card, with no Undo (the result carries no id=)
            // (parity with iOS build 81, audit 2026-09-22 C3).
            if (result.contains("(series continues)")) return Receipt(ReceiptIcon.CHECK, "Done for today: $nm")
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
        "promote_capture" -> {
            // Its own undo, never DELETE_TASK: that left the capture archived out
            // of the inbox (and the old cascade deleted it outright). Without the
            // capture's id there is no Undo, never a wrong one (Android audit
            // 2026-09-23, A17).
            val taskId = idFragment(result)
            val capId = args.captureId
            Receipt(
                ReceiptIcon.PLUS, "Task from capture: ${quotedFragment(result) ?: ""}",
                if (taskId != null && capId != null) ReceiptUndo.unpromoteCapture(taskId, capId) else null,
            )
        }
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
            Receipt(ReceiptIcon.CALENDAR, "Call booked ${shortWeekday(date)} ${ClockFormat.time(hm, clock)} — $label · $n note${if (n == "1") "" else "s"}", ReceiptUndo.cancelCall(id))
        }
        "update_call" -> {
            val m = CALL_UPDATED_RE.find(result) ?: return null
            val (label, date, hm, n) = m.destructured
            Receipt(ReceiptIcon.PENCIL, "Call updated ${shortWeekday(date)} ${ClockFormat.time(hm, clock)} — $label · $n note${if (n == "1") "" else "s"}")
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
    /** Remove the promoted task (+ blocks) and put its capture back — undo of `promote_capture`. */
    data class Unpromote(val taskId: String, val captureId: String, val unarchive: Boolean) : ReceiptUndoPlan()
    /** Put the refined fact's old wording back — undo of a refine-in-place save. */
    data class RestoreFact(val prior: ProfileFact) : ReceiptUndoPlan()
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
        // Never onto a repeating series' template: its done ENDS the series. New
        // results for a series carry no id=, so only an older persisted "Reopened"
        // receipt can land here (parity with iOS build 81, audit 2026-09-22 C3).
        ReceiptUndoKind.COMPLETE_TASK -> tasks.firstOrNull { it.id == undo.id && it.recurrence == null }
            ?.let { ReceiptUndoPlan.Complete(it.copy(done = true, completedAt = nowIso, updatedAt = nowIso)) }
        ReceiptUndoKind.CANCEL_CALL -> ReceiptUndoPlan.CancelCall(undo.id)
        ReceiptUndoKind.UNPROMOTE_CAPTURE -> ReceiptUndoPlan.Unpromote(undo.id, undo.captureId, undo.unarchive)
        ReceiptUndoKind.RESTORE_FACT -> undo.prior?.let { ReceiptUndoPlan.RestoreFact(it) }
    }
}

// ── Exact Undo (Android audit 2026-09-23, A17) ──────────────────────────────
// An Undo puts back EXACTLY what its turn did, or nothing — and says so. Each
// receipt's undo is stamped with fingerprints of the rows it would write, as
// the turn's own writes left them; at tap time a row that no longer matches
// (renamed, scheduled, worked on, noted, edited in Settings, changed on another
// device) refuses the undo. `updatedAt` is never part of a fingerprint: the
// server re-stamps it on every UPDATE (touch_updated_at), so the echo of the
// turn's own write would read as a change. The server's column defaults are
// folded in and timestamps compared as instants for the same reason.

/** The store rows an Undo is checked against, read fresh at that moment.
 *  [facts] are the ACTIVE facts; [archivedCaptureIds] the captures out of the inbox. */
data class UndoState(
    val tasks: List<TaskItem>,
    val blocks: List<CalBlock>,
    val captures: List<Capture>,
    val archivedCaptureIds: Set<String>,
    val facts: List<ProfileFact>,
)

/** What the receipt card says when an Undo can't be exact. */
object ReceiptUndoRefusal {
    const val CHANGED = "Not undone — it's changed since, so it was left as it is."
    const val CHANGED_SOME = "Not undone — some of these have changed since, so they were left as they are."
    const val NOTES = "Not undone — it has notes you've added since, so it was left as it is."
    const val NOTES_SOME = "Not undone — some of these have notes you've added since, so they were left as they are."
    /** Persisted before exact Undo existed: there is nothing to check it against. */
    const val TOO_OLD = "Not undone — it's too old to check safely, so it was left as it is."
}

/** The undos that remove or overwrite something — never run without a stamp. */
private val DESTRUCTIVE_UNDOS = setOf(
    ReceiptUndoKind.DELETE_TASK, ReceiptUndoKind.DELETE_TASKS, ReceiptUndoKind.UNPROMOTE_CAPTURE,
    ReceiptUndoKind.DELETE_CAPTURE, ReceiptUndoKind.FORGET_FACT, ReceiptUndoKind.RESTORE_FACT,
)

private fun digest(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

private fun instantKey(iso: String): String = Time.parseMillis(iso)?.toString() ?: iso

/** A task's content and its calendar slots as the user sees them. The server
 *  writes `[]` / `0` / `false` where the phone may hold null (DbRowCodec.TaskRow),
 *  so those are folded in; Google's event ids on a block are the push's, not the user's. */
fun taskUndoStamp(t: TaskItem, blocks: List<CalBlock>): String {
    val row = t.copy(
        tags = t.tags.orEmpty(), objectives = t.objectives.orEmpty(), comments = t.comments.orEmpty(),
        moveCount = t.moveCount ?: 0, later = t.later ?: false,
        completedAt = t.completedAt?.let(::instantKey), dueAt = t.dueAt?.let(::instantKey),
        createdAt = "", updatedAt = "",
    )
    val slots = blocks.filter { it.taskId == t.id }.sortedBy { it.id }
        .joinToString(";") { "${it.id}|${it.date}|${it.startTime}|${it.durationMinutes}|${it.done}|${it.skipped}" }
    return digest("$row#$slots")
}

/** The captures (the user's notes) filed on a task. */
fun notesUndoStamp(taskId: String, captures: List<Capture>): String =
    digest(captures.filter { it.taskId == taskId }.map { it.id }.sorted().joinToString(","))

fun captureUndoStamp(c: Capture, archived: Boolean): String = digest("${c.body}|${c.tag}|${c.taskId}|$archived")

fun factUndoStamp(f: ProfileFact): String = digest("${f.category}|${f.fact}|${f.source}|${f.whenIso}")

/** [undo] with the fingerprints of every row it would write, as they stand in
 *  [s] — taken right after the write that made its receipt; the turn's own
 *  later writes (create_task, then schedule_task on it) carry it forward
 *  ([advanceReceiptUndo]). */
fun stampReceiptUndo(undo: ReceiptUndo, s: UndoState): ReceiptUndo {
    val stamps = HashMap<String, String>()
    for (id in undo.taskIds) s.tasks.firstOrNull { it.id == id }?.let {
        stamps["t:$id"] = taskUndoStamp(it, s.blocks)
        stamps["n:$id"] = notesUndoStamp(id, s.captures)
    }
    undo.captureTarget?.let { id -> s.captures.firstOrNull { it.id == id } }
        ?.let { stamps["c:${it.id}"] = captureUndoStamp(it, it.id in s.archivedCaptureIds) }
    undo.factTarget?.let { id -> s.facts.firstOrNull { it.id == id } }?.let { stamps["f:${it.id}"] = factUndoStamp(it) }
    return undo.copy(stamps = stamps, stamped = true)
}

/** [undo] carried across a write made by its OWN turn or voice session — a
 *  later tool of it, or the Undo of a later receipt of it ("Created X" +
 *  "Completed X" undo in turn) — given the rows read [before] and [after]
 *  that write. Of [keys], only a row that read in [before] exactly as [undo]
 *  last saw it takes its state in [after]. One changed in between by anyone
 *  else (renamed or noted in the app during a call, edited on another device)
 *  keeps its old stamp, so this Undo refuses it: re-stamping every key baked
 *  such an edit in, and the Undo then deleted the edited task (Android audit
 *  2026-09-23, A17). */
fun advanceReceiptUndo(undo: ReceiptUndo, before: UndoState, after: UndoState, keys: Set<String> = undo.stamps.keys): ReceiptUndo {
    if (!undo.stamped) return undo
    val seen = stampReceiptUndo(undo, before).stamps
    val follow = keys.filter { k -> undo.stamps[k] != null && seen[k] == undo.stamps[k] }.toSet()
    if (follow.isEmpty()) return undo
    val fresh = stampReceiptUndo(undo, after).stamps
    return undo.copy(stamps = undo.stamps.filterKeys { it !in follow } + fresh.filterKeys { it in follow })
}

/** Why [undo] can't put back exactly what its turn did (the card's line), or
 *  null to go ahead. Only rows the undo would WRITE are checked — one already
 *  gone, or already in the undo's target state, is the caller's no-op. All or
 *  nothing: one changed row refuses the whole receipt. */
fun receiptUndoRefusal(undo: ReceiptUndo, s: UndoState): String? {
    if (undo.kind == ReceiptUndoKind.CANCEL_CALL) return null
    val destructive = undo.kind in DESTRUCTIVE_UNDOS
    if (!undo.stamped) return if (destructive) ReceiptUndoRefusal.TOO_OLD else null
    val many = undo.taskIds.size > 1
    val written = undo.taskIds.mapNotNull { id -> s.tasks.firstOrNull { it.id == id } }.filter { t ->
        when (undo.kind) {
            ReceiptUndoKind.UNCOMPLETE_TASK, ReceiptUndoKind.UNCOMPLETE_TASKS -> t.done
            ReceiptUndoKind.COMPLETE_TASK -> !t.done && t.recurrence == null
            else -> true
        }
    }
    if (written.any { undo.stamps["t:${it.id}"] != taskUndoStamp(it, s.blocks) }) {
        return if (many) ReceiptUndoRefusal.CHANGED_SOME else ReceiptUndoRefusal.CHANGED
    }
    // Notes filed on a task since: deleting it would strand them.
    if (destructive && written.any { undo.stamps["n:${it.id}"] != notesUndoStamp(it.id, s.captures) }) {
        return if (many) ReceiptUndoRefusal.NOTES_SOME else ReceiptUndoRefusal.NOTES
    }
    val capture = undo.captureTarget?.let { id -> s.captures.firstOrNull { it.id == id } }
    if (capture != null && undo.stamps["c:${capture.id}"] != captureUndoStamp(capture, capture.id in s.archivedCaptureIds)) {
        return ReceiptUndoRefusal.CHANGED
    }
    val fact = undo.factTarget?.let { id -> s.facts.firstOrNull { it.id == id } }
    if (fact != null && undo.stamps["f:${fact.id}"] != factUndoStamp(fact)) return ReceiptUndoRefusal.CHANGED
    return null
}

/** A fact save's receipt undo, given the fact as it stood BEFORE the save
 *  ([prior], null when the save created it) and after it ([saved]). A save
 *  that refined an existing fact keeps its id, so FORGET_FACT would delete a
 *  fact the user had before this turn: the undo restores [prior] instead, and
 *  a save that changed nothing offers no Undo at all. */
fun factSaveUndo(undo: ReceiptUndo, prior: ProfileFact?, saved: ProfileFact?): ReceiptUndo? {
    if (undo.kind != ReceiptUndoKind.FORGET_FACT || prior == null || prior.id != undo.id) return undo
    if (saved != null && factUndoStamp(saved) == factUndoStamp(prior)) return null
    return ReceiptUndo.restoreFact(prior)
}
