package tech.csalliance.unstuck.ui.assistant

import tech.csalliance.unstuck.core.logic.renderPeriodReview
import tech.csalliance.unstuck.core.logic.PeriodReviewArgs
import tech.csalliance.unstuck.core.logic.DEFAULT_AREAS
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.InsightsWindow
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.addDaysIso
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.clampDurationMin
import tech.csalliance.unstuck.core.logic.doneWhenLabel
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.rejectPastDate
import tech.csalliance.unstuck.core.logic.rejectPastTime
import tech.csalliance.unstuck.core.logic.renderInsights
import tech.csalliance.unstuck.core.logic.resolveListShareRequest
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.isRecurringSeriesRefusal
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Time

// The full app surface (2026-09-02: "the model should be able to do everything
// a user can do"): reopen/find/list tasks, calendar edits, focus controls,
// captures, list edits, areas + tags, unshare, settings, insights, navigation.
// 1:1 with the matching cases in lib/assistant/tools.ts / iOS
// AssistantTools+Surface.swift.
//
// 2026-09-20 tooling rewrite (docs/assistant-tooling-rules.md §1): every `ok:`
// here is earned — the seam answers whether the store took the write, a no-op
// is an `error: … nothing changed`, and a partial result names what was NOT
// done. New tools: find_tasks, finish_focus, set_task_reminder, recolor_list,
// leave_list, share_list (staged), pin_list_item, restore_capture,
// get_settings, set_theme, set_focus_defaults, set_ambient_sound,
// finish_interview (moved out of the ViewModel intercept).

/** The registry's screen vocabulary (+ the web's aliases, still accepted). */
object AssistantScreens {
    /** The `open_screen.screen` enum, straight from the registry. */
    val registry: List<String> get() = RegistryTools.enumOf("open_screen", "screen")
    val aliases: Set<String> = setOf("dashboard", "home", "collections", "inbox", "analytics")
    val known: Set<String> get() = registry.toSet() + aliases
}

/** Refusal for an OWNER-only list action attempted on a list shared WITH the
 *  user. The server accepts an editor's metadata write and discards it (the
 *  `lock_collection_metadata` trigger / an owner-only delete policy matching
 *  zero rows), so this has to be caught here or the assistant reports a change
 *  that snaps back a second later. Same wording as web + iOS. */
private fun ownerOnly(name: String, verb: String): String =
    "error: \"$name\" is shared with you by its owner — only they can $verb it. You can still add, edit and tick items."

/** The store did not take the write — never an `ok:` over it. */
private const val NOT_SAVED = "error: couldn't save that — try again"

private fun captureTagOf(raw: String): CaptureTag? = when (raw) {
    "follow-up" -> CaptureTag.FOLLOW_UP
    "idea" -> CaptureTag.IDEA
    "edit" -> CaptureTag.EDIT
    "question" -> CaptureTag.QUESTION
    "distraction" -> CaptureTag.DISTRACTION
    else -> null
}

private fun CaptureTag.wire(): String = name.lowercase().replace('_', '-')

private fun onOff(b: Boolean) = if (b) "on" else "off"

/** One task as the read tools list it: name, id, estimate, area, next slot,
 *  repeat / Later / slip / done markers. Recurring rows are OCCURRENCES whose
 *  id is the block id — the model must get the TASK id (templateId).
 *  [dated] (get_tasks only; find_tasks keeps the plain line, as on iOS) adds
 *  when it was created and when it was done (parity with iOS builds 75/76,
 *  f125845 + 3564ccc). */
private fun taskLine(t: TaskItem, tasks: List<TaskItem>, blocks: List<CalBlock>, today: String, dated: Boolean = false): String {
    val occ = occurrenceBlockFor(t.id, tasks, blocks)
    val taskId = occ?.taskId ?: t.id
    val b = occ ?: nextLiveBlock(blocks, today, taskId)
    val sb = StringBuilder("- ${t.name} [id=$taskId] ${t.estimateMin}m")
    t.lifeArea?.takeIf { it.isNotEmpty() }?.let { sb.append(" · $it") }
    t.tags?.takeIf { it.isNotEmpty() }?.let { sb.append(" · #${it.joinToString(" #")}") }
    if (b != null) sb.append(" · ${b.date} ${b.startTime}")
    if (occ != null || t.recurrence != null) sb.append(" · repeats")
    if (t.later == true) sb.append(" · Later")
    if ((t.moveCount ?: 0) >= 3) sb.append(" · slipped ${t.moveCount}×")
    // When it was created — "the ones I created last week" (Ahmad, 2026-09-20:
    // the model rightly said the list didn't show it).
    if (dated) doneWhenLabel(t.createdAt, today)?.let { sb.append(" · created $it") }
    if (t.done) {
        sb.append(" · done")
        if (dated) doneWhenLabel(t.completedAt ?: occ?.completedAt, today)?.let { sb.append(" $it") }
    }
    return sb.toString()
}

suspend fun runSurfaceTool(name: String, args: ToolArgs, api: AssistantApi, scratch: TurnScratch): String? {
    val now = api::nowIso

    return when (name) {
        // ── TASKS ──
        "uncomplete_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            // An open repeating series: "untick that" means TODAY's occurrence — the
            // day complete_task now ticks. No id= in the result, so the receipt offers
            // no Undo: its complete would set the series' own done and end it (parity
            // with iOS build 81, audit 2026-09-22 C3).
            if (t.recurrence != null && !t.done) {
                val today = api.todayIso()
                val b = api.getBlocks().filter { it.taskId == t.id && it.date == today && !it.skipped && it.done }.maxByOrNull { it.startTime }
                    ?: return "error: \"${t.name}\" repeats and isn't done on $today — nothing changed"
                api.upsertBlock(b.copy(done = false, completedAt = null))
                return "ok: reopened \"${t.name}\" for $today (series continues)"
            }
            // Already open → error, never "ok: reopened": that receipt's Undo would
            // COMPLETE a task the user never finished (web parity).
            if (!t.done) return "error: \"${t.name}\" is already open — nothing changed"
            val upd = t.copy(done = false, completedAt = null, updatedAt = now())
            api.upsertTask(upd)
            api.notifyTaskReopenedIfShared(upd)
            scratch.newTasks[t.id] = upd
            // A series the old path ended runs again — no id= either: its Undo would
            // end it once more (C3).
            if (t.recurrence != null) "ok: reopened \"${t.name}\" — its repeating series runs again"
            else "ok: reopened \"${t.name}\" id=${t.id}"
        }

        "get_tasks" -> {
            val viewMap = mapOf(
                "today" to TaskListView.TODAY, "upcoming" to TaskListView.UPCOMING, "backlog" to TaskListView.BACKLOG,
                "later" to TaskListView.LATER, "recurring" to TaskListView.RECURRING, "completed" to TaskListView.COMPLETED,
                "all" to TaskListView.ALL, "slipping" to TaskListView.ALL,
            )
            val v = (args.str("view") ?: "all").lowercase()
            val view = viewMap[v]
                ?: return "error: unknown view \"$v\" — use today, upcoming, backlog, later, recurring, completed, slipping, or all"
            val area = args.str("area")
            val tasks = api.getTasks()
            if (area != null) {
                val known = ArrayList<String>()
                for (a in api.getAreas() + tasks.map { it.lifeArea ?: "" }) {
                    if (a.isEmpty()) continue
                    val l = a.lowercase()
                    if (l !in known) known += l
                }
                if (area.lowercase() !in known) {
                    return "error: no area named \"$area\" — areas: ${if (known.isEmpty()) "(none yet)" else known.joinToString(", ")}"
                }
            }
            val tag = args.str("tag")?.lowercase()
            val blocks = api.getBlocks()
            var rows = visibleTasks(view, tasks, blocks, api.nowMs(), area, null, slipMode = v == "slipping")
            if (tag != null) rows = rows.filter { t -> t.tags?.any { it.lowercase() == tag } == true }
            // The completed view is DATED and newest first: an undated all-time
            // list was read back as "today" (Zubair's evening call, 2026-09-20 —
            // weeks-old tasks said as done today). Sorted on the parsed instant:
            // local rows stamp `…Z`, server rows `…+00:00` with microseconds
            // (parity with iOS build 75, f125845).
            if (view == TaskListView.COMPLETED) rows = rows.sortedByDescending { t -> t.completedAt?.let { Time.parseMillis(it) } ?: Long.MIN_VALUE }
            val today = api.todayIso()
            val lines = rows.take(30).map { taskLine(it, tasks, blocks, today, dated = true) }
            val order = if (view == TaskListView.COMPLETED && rows.size > 1) ", newest first" else ""
            "ok: ${view.label} (${rows.size})$order${if (rows.size > 30) ", first 30" else ""}:\n${if (lines.isEmpty()) "(none)" else lines.joinToString("\n")}"
        }

        "find_tasks" -> {
            // READ: the model names a task it has no id for. Whole-phrase match
            // first, else every word somewhere in the title (partial words match);
            // several hits → the model asks which, never picks.
            val q = args.str("query")?.trim()?.lowercase() ?: return "error: query required — words from the task's title"
            val includeDone = args.bool("includeDone") ?: false
            val tasks = api.getTasks()
            val blocks = api.getBlocks()
            val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val pool = storeFirst(tasks, scratch).filter { includeDone || !it.done }
            val hits = pool
                .filter { t -> val n = t.name.lowercase(); n.contains(q) || words.all { w -> n.contains(w) } }
                .sortedWith(compareBy({ !it.name.lowercase().contains(q) }, { it.name.lowercase() }))
            if (hits.isEmpty()) {
                return "ok: no ${if (includeDone) "" else "open "}task matches \"$q\"" +
                    if (includeDone) "" else " — includeDone=true searches finished ones too"
            }
            val lines = hits.take(20).map { taskLine(it, tasks, blocks, api.todayIso()) }
            "ok: ${hits.size} match${if (hits.size == 1) "" else "es"} for \"$q\"${if (hits.size > 20) ", first 20" else ""}" +
                "${if (hits.size > 1) " — more than one: ask which, never pick" else ""}:\n${lines.joinToString("\n")}"
        }

        "set_task_reminder" -> {
            // Android keeps a per-task lead override in device prefs (the task
            // sheet's "Remind me" chips) — the same store, the same re-arm.
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val given = args.has("minutes") && !args.isNull("minutes")
            val bad = "error: minutes must be 0 (off), 5, 10, or 15 — or omit it for the default"
            val m: Int? = if (given) (args.int("minutes") ?: return bad) else null
            if (m != null && m !in listOf(0, 5, 10, 15)) return bad
            val what = when (m) { null -> "back to the default"; 0 -> "off"; else -> "set to $m minutes before" }
            if (api.getTaskReminder(t.id) == m) return "error: reminder for \"${t.name}\" is already ${if (m == null) "the default" else what} — nothing changed"
            if (!api.setTaskReminder(t.id, m)) return "error: couldn't save the reminder — try again"
            val slot = nextLiveBlock(api, t.id)
            "ok: reminder for \"${t.name}\" $what" + if (slot == null) " (no upcoming slot yet — it applies once the task is scheduled)" else ""
        }

        // ── CALENDAR ──
        "unschedule_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            // A repeating task is refused, even with no upcoming slot left: with the
            // repeat still on, the slots came back (a horizon top-up on another
            // device rebuilt every removed one) although the result said they were
            // gone. Which one the user means is theirs to say (owner decision;
            // parity with iOS build 81, audit 2026-09-22 C1).
            if (t.recurrence != null) {
                return "error: \"${t.name}\" repeats — nothing changed. Ask the user which they mean: stop the whole series (set_task_recurrence kind none) or skip just one day (skip_occurrence with the date)."
            }
            val today = api.todayIso()
            val live = api.getBlocks().filter { it.taskId == t.id && !it.done && !it.skipped && it.date >= today }
            if (live.isEmpty()) return "error: \"${t.name}\" has no upcoming slot to remove"
            for (b in live) api.deleteBlock(b.id)
            "ok: unscheduled \"${t.name}\" (task kept, ${live.size} slot${if (live.size == 1) "" else "s"} removed)"
        }

        "skip_occurrence" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val date = args.str("date") ?: api.todayIso()
            val b = api.getBlocks().firstOrNull { it.taskId == t.id && it.date == date && !it.done }
                ?: return "error: \"${t.name}\" has nothing on $date to skip"
            // Re-skipping is a no-op — say so instead of a second "Skipped" receipt.
            if (b.skipped) return "error: \"${t.name}\" is already skipped on $date — nothing changed"
            api.upsertBlock(b.copy(skipped = true))
            "ok: skipped \"${t.name}\" on $date (the task and its other days stay)"
        }

        "complete_occurrence" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val date = args.str("date") ?: api.todayIso()
            val b = api.getBlocks().firstOrNull { it.taskId == t.id && it.date == date && !it.skipped }
                ?: return "error: \"${t.name}\" has nothing on $date"
            // Already done that day → error, not a second "Done for today" receipt.
            if (b.done) return "error: \"${t.name}\" is already done on $date — nothing changed"
            // …and for a ONE-OFF task, done can live on the task rather than the
            // block (toggleDone flips only the task row): ticking its open block
            // moved completedAt to today and answered ok over a no-op (parity
            // with iOS build 79, 2c4b723).
            if (t.recurrence == null && t.done) return "error: \"${t.name}\" is already done — nothing changed"
            // Stamped like the UI's tick: the block un-skipped with its completedAt,
            // and a one-off task its completedAt + the shared-list notice. The
            // template of a series is never touched (parity with iOS build 81, audit
            // 2026-09-22 C6).
            markOccurrenceDone(b, api)
            if (t.recurrence == null) markTaskDone(t, api, scratch)
            "ok: marked \"${t.name}\" done for $date${if (t.recurrence != null) " (series continues)" else ""}"
        }

        "block_time" -> {
            val nm = args.str("name")
            val date = args.str("date")
            val startTime = args.str("startTime")
            // Clamped to the server's CHECK (5…1440, audit 2026-09-22 C4): an
            // out-of-range block was accepted here, refused on flush and quarantined.
            val dur = clampDurationMin(args.int("durationMin"), fallback = 60)
            if (nm == null || date == null || startTime == null) return "error: name, date and startTime are all required for block_time"
            (rejectPastDate(api.todayIso(), date)
                ?: rejectPastTime(api.getBlocks(), api.todayIso(), date, startTime, api.nowHM()))?.let { return it }
            // The block-time task + its block: the placeholder block id rides on
            // the task (id=…) so the receipt's undo can delete both.
            val t = TaskItem(id = newUuid(), name = nm, estimateMin = dur, totalFocused = 0, done = false, priority = Priority.MEDIUM,
                tags = emptyList(), objectives = emptyList(), comments = emptyList(), later = false, createdAt = now(), updatedAt = now())
            api.upsertTask(t)
            scratch.newTasks[t.id] = t
            api.upsertBlock(CalBlock(id = newUuid(), taskId = t.id, taskName = nm, startTime = startTime, durationMinutes = dur, date = date, kind = CalBlockKind.TASK))
            "ok: blocked \"$nm\" $date $startTime for ${dur}m id=${t.id}"
        }

        "carry_to_tomorrow" -> {
            val today = api.todayIso()
            val tomorrow = addDaysIso(today, 1)
            val wanted = args.strList("taskIds")
            // Task blocks only (web: `b.taskId && …`) — a block with no task has
            // nothing to carry and nothing to bump.
            // "Unfinished" = neither the block nor its TASK is done: a one-off
            // ticked on the task row kept an open block and was carried with the
            // open ones (Zubair's evening call, 2026-09-21: "moved 4 — Project
            // Check-in, …", done at noon) (parity with iOS build 77, 2c79212).
            val doneTaskIds = api.getTasks().filter { it.done }.map { it.id }.toSet()
            val todays = api.getBlocks().filter { b ->
                b.taskId != null && b.date == today && !b.done && !b.skipped && isTaskBlock(b) &&
                    (b.taskId ?: "") !in doneTaskIds &&
                    (wanted == null || (b.taskId ?: "") in wanted)
            }
            if (todays.isEmpty()) return "error: nothing left on today to carry"
            // A task tomorrow already has is SKIPPED today, not moved — the old
            // line counted both as "carried", so the model told the user a task
            // had moved when it had been dropped for the day (rules §1).
            val moved = ArrayList<String>()
            val skipped = ArrayList<String>()
            for (b in todays) {
                val t = api.getTasks().firstOrNull { it.id == b.taskId }
                val nm = t?.name ?: b.taskName
                val tomorrowTaken = api.getBlocks().any { it.taskId == b.taskId && it.date == tomorrow && !it.skipped }
                api.upsertBlock(if (tomorrowTaken) b.copy(skipped = true) else b.copy(date = tomorrow))
                if (t != null) api.upsertTask(bumpMoveCount(t, now()))
                if (tomorrowTaken) skipped += "\"$nm\"" else moved += "\"$nm\""
            }
            val notMoved = if (skipped.isEmpty()) "" else " — not moved: ${skipped.joinToString(", ")} (tomorrow already has ${if (skipped.size == 1) "it" else "them"}; skipped today instead)"
            if (moved.isEmpty()) "ok: skipped today's ${skipped.joinToString(", ")} (tomorrow already has ${if (skipped.size == 1) "it" else "them"}) — nothing moved"
            else "ok: carried ${moved.size} to $tomorrow — ${moved.joinToString(", ")}$notMoved"
        }

        // ── FOCUS ──
        "start_focus" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val live = api.getLiveFocus()
            if (live?.sessionStart != null) {
                val cur = api.getTasks().firstOrNull { it.id == live.taskId }
                return "error: a focus session is already running on \"${cur?.name ?: "a task"}\" — pause or cancel it first, or ask the user"
            }
            val occ = if (t.recurrence != null) nextLiveBlock(api, t.id) else null
            val est = args.int("estimateMin") ?: t.estimateMin
            if (!api.startFocus(t.id, est, occ?.id)) return "error: couldn't start a session on \"${t.name}\" — it may be assigned out; ask the user"
            api.navigate("focus", null)
            "ok: focus started on \"${t.name}\" (${est}m) — the user is now on the focus screen"
        }

        "pause_focus" -> {
            val live = api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            if (live.paused) return "error: it is already paused"
            if (!api.pauseFocus()) return "error: couldn't pause — the session ended underneath; get the state again"
            "ok: paused the focus session"
        }

        "resume_focus" -> {
            val live = api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            if (!live.paused) return "error: it is not paused"
            if (!api.resumeFocus()) return "error: couldn't resume — the session ended underneath; get the state again"
            "ok: resumed the focus session"
        }

        "extend_focus" -> {
            api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            val mins = args.int("minutes") ?: 10
            if (mins < 1 || mins > 180) return "error: minutes must be between 1 and 180"
            if (!api.extendFocus(mins)) return "error: couldn't extend — the session ended underneath; get the state again"
            "ok: extended the session by ${mins}m"
        }

        "finish_focus" -> {
            // The Focus screen's Done (markDone) / Stop here path: the session is
            // LOGGED (Session row + totalFocused) — cancel_focus is the one that
            // isn't. The elapsed minutes are read BEFORE the seam clears the row.
            val live = api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            val markDone = args.bool("markDone") ?: false
            val t = api.getTasks().firstOrNull { it.id == live.taskId }
            val nm = t?.name ?: live.sharedTitle ?: "the task"
            val mins = Math.round(FocusTimer.elapsedSec(live, api.nowMs()) / 60.0).toInt()
            if (!api.finishFocus(markDone)) return "error: couldn't end the session — try again"
            val done = when {
                !markDone -> ""
                t?.recurrence != null -> ", today's occurrence marked done"
                // A task deleted elsewhere mid-session: the minutes are logged, nothing
                // is marked (audit 2026-09-22 C5).
                t == null && live.sharedTitle == null -> ""
                // A task shared WITH the user: say what the owner's row got. A
                // repeating share is never ticked by its recipient — the server
                // refuses it — and a failed tick is no tick either (parity with iOS
                // build 81, audit 2026-09-22 C3, SC-12).
                t == null -> when (val why = api.sharedFinishRefusal(live.taskId)) {
                    null -> ", task marked done"
                    else ->
                        if (isRecurringSeriesRefusal(why)) ", the task stays open (only its owner ticks off a repeating task)"
                        else ", the task stays open (the tick didn't go through — the user can tick it in Shared with you)"
                }
                else -> ", task marked done"
            }
            "ok: finished focus on \"$nm\" — logged ${mins}m$done"
        }

        "cancel_focus" -> {
            api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            if (!api.cancelFocus()) return "error: couldn't cancel — the session ended underneath; get the state again"
            "ok: cancelled the focus session (nothing logged) — to finish and LOG a session use finish_focus"
        }

        // ── CAPTURES ──
        "add_capture" -> {
            val body = args.str("body") ?: return "error: body required"
            val tag = captureTagOf((args.str("tag") ?: "idea").lowercase()) ?: CaptureTag.IDEA
            val t = findTask(args.str("taskId"), api, scratch)
            val live = api.getLiveFocus()
            // A cut body is reported, not silently dropped (rules §1).
            val cut = body.length > 500
            // A session on a task shared WITH the user never writes an own Session row,
            // so a capture tied to it waited on one for ever (Android audit 2026-09-23, A14).
            val c = Capture(id = newUuid(), taskId = t?.id, sessionId = if (live?.sessionStart != null && live.sharedTitle == null) live.id else null,
                tag = tag, body = body.take(500), at = now())
            api.upsertCapture(c)
            "ok: captured id=${c.id} [${tag.wire()}] \"${c.body}\"${if (t != null) " on \"${t.name}\"" else ""}" +
                if (cut) " (cut to 500 characters — the rest was not saved)" else ""
        }

        "get_captures" -> {
            val archived = api.getArchivedCaptureIds()
            val tag = args.str("tag")?.lowercase()
            val tasks = api.getTasks()
            val open = api.getCaptures()
                .filter { it.id !in archived && (tag == null || it.tag.wire() == tag) }
                .sortedByDescending { it.at }
            val lines = open.take(25).map { c ->
                val t = c.taskId?.let { id -> tasks.firstOrNull { it.id == id } }
                "- [${c.tag.wire()}] ${c.body} (id=${c.id}${if (t != null) ", on \"${t.name}\"" else ""})"
            }
            "ok: ${open.size} open capture${if (open.size == 1) "" else "s"}:\n${if (lines.isEmpty()) "(inbox empty)" else lines.joinToString("\n")}"
        }

        "get_lists" -> {
            // READ — the full set (context.lists carries only the first 12
            // unarchived), one list in full, or the archived ones. There was no
            // list-reading tool at all: the model guessed names for three rounds
            // (tester round, iOS 2026-09-06). Result text 1:1 with tools.ts.
            val wantId = args.str("listId")
            val one = wantId?.let { findList(it, api, scratch) }
            if (wantId != null && one == null) return "error: list not found"
            val includeArchived = args.bool("includeArchived") ?: false
            val lists = if (one != null) listOf(one) else api.getCollections().filter { includeArchived || it.archived != true }
            if (lists.isEmpty()) return "ok: no lists yet"
            val itemCap = if (one != null) 100 else 10
            val lines = ArrayList<String>()
            for (c in lists.take(20)) {
                val done = c.items.count { it.done == true }
                lines += "- \"${c.name}\" [id=${c.id}] — ${c.items.size - done} open${if (done > 0) ", $done done" else ""}${if (c.archived == true) " · archived" else ""}"
                if (c.items.isEmpty()) { lines += "  (empty)"; continue }
                for (i in c.items.take(itemCap)) lines += "  - ${i.body}${if (i.done == true) " (done)" else ""}${if (i.pinned == true) " (pinned)" else ""} [id=${i.id}]"
                if (c.items.size > itemCap) lines += "  … and ${c.items.size - itemCap} more — get_lists listId=${c.id} for all"
            }
            if (lists.size > 20) lines += "… and ${lists.size - 20} more lists"
            "ok: ${lists.size} list${if (lists.size == 1) "" else "s"}:\n${lines.joinToString("\n")}"
        }

        "promote_capture" -> {
            val id = args.str("captureId")
            val c = api.getCaptures().firstOrNull { it.id == id } ?: return "error: capture not found"
            // lib/capture-actions promoteCapture: task from the body, link the
            // capture. No area is invented for it (a hard-coded "Work" used to be
            // stamped on every promoted capture — 2026-09-20).
            val newId = newUuid()
            val nm = c.body.take(160)
            val made = TaskItem(id = newId, name = nm.ifEmpty { "Untitled task" }, estimateMin = 25, totalFocused = 0, done = false,
                priority = Priority.MEDIUM, tags = listOf("from-capture", c.tag.wire()), objectives = emptyList(), comments = emptyList(),
                createdAt = now(), updatedAt = now())
            api.upsertTask(made)
            api.upsertCapture(c.copy(taskId = c.taskId ?: newId))
            scratch.newTasks[made.id] = made
            if (!api.archiveCapture(c.id, true)) return "ok: promoted capture to task id=$newId name=\"${c.body}\" — but it could not leave the inbox (resolve_capture it)"
            "ok: promoted capture to task id=$newId name=\"${c.body}\""
        }

        "resolve_capture" -> {
            val id = args.str("captureId")
            val c = api.getCaptures().firstOrNull { it.id == id } ?: return "error: capture not found"
            if (c.id in api.getArchivedCaptureIds()) return "error: \"${c.body}\" is already resolved — nothing changed"
            if (!api.archiveCapture(c.id, true)) return NOT_SAVED
            "ok: resolved capture \"${c.body}\""
        }

        "restore_capture" -> {
            val id = args.str("captureId")
            val c = api.getCaptures().firstOrNull { it.id == id } ?: return "error: capture not found"
            if (c.id !in api.getArchivedCaptureIds()) return "error: \"${c.body}\" is already in the inbox — nothing changed"
            if (!api.archiveCapture(c.id, false)) return NOT_SAVED
            "ok: restored capture \"${c.body}\" to the inbox"
        }

        "delete_capture" -> {
            val id = args.str("captureId")
            val c = api.getCaptures().firstOrNull { it.id == id } ?: return "error: capture not found"
            api.removeCapture(c.id)
            "ok: deleted capture \"${c.body}\""
        }

        // ── LISTS ──
        // Rename / recolour / archive / delete / share are OWNER-only (the list
        // screen shows those affordances to the owner alone, and the server
        // discards an editor's metadata write) — gating them on canEditCollection
        // let an EDITOR be told a shared list was renamed / archived / deleted,
        // seconds before it reverted. A list created earlier in THIS turn is the
        // user's own, so it never needs the ownership round-trip.
        "rename_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            val nm = args.str("name") ?: return "error: name required"
            if (scratch.newLists[c.id] == null && !api.isCollectionOwner(c.id)) return ownerOnly(c.name, "rename")
            if (nm.trim() == c.name) return "error: \"${c.name}\" is already called that — nothing changed"
            if (!api.renameCollection(c.id, nm)) return NOT_SAVED
            scratch.newLists[c.id]?.let { scratch.newLists[c.id] = it.copy(name = nm.trim()) }
            "ok: renamed list \"${c.name}\" → \"${nm.trim()}\""
        }

        "recolor_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            val colors = RegistryTools.enumOf("recolor_list", "color")
            val color = args.str("color")?.lowercase() ?: return "error: color required — one of ${colors.joinToString(", ")}"
            if (color !in colors) return "error: unknown colour \"$color\" — use ${colors.joinToString(", ")}"
            if (scratch.newLists[c.id] == null && !api.isCollectionOwner(c.id)) return ownerOnly(c.name, "recolour")
            if (c.color == color) return "error: \"${c.name}\" is already $color — nothing changed"
            if (!api.updateCollection(c.id, null, color)) return NOT_SAVED
            scratch.newLists[c.id]?.let { scratch.newLists[c.id] = it.copy(color = color) }
            "ok: recoloured list \"${c.name}\" → $color"
        }

        "archive_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            val archived = args.bool("archived") ?: true
            if (scratch.newLists[c.id] == null && !api.isCollectionOwner(c.id)) return ownerOnly(c.name, if (archived) "archive" else "unarchive")
            if ((c.archived ?: false) == archived) return "error: \"${c.name}\" is already ${if (archived) "archived" else "unarchived"} — nothing changed"
            if (!api.updateCollection(c.id, archived, null)) return NOT_SAVED
            "ok: ${if (archived) "archived" else "unarchived"} list \"${c.name}\""
        }

        "delete_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            if (scratch.newLists[c.id] == null && !api.isCollectionOwner(c.id)) return ownerOnly(c.name, "delete")
            if (!api.removeCollection(c.id)) return NOT_SAVED
            scratch.newLists.remove(c.id)
            "ok: deleted list \"${c.name}\""
        }

        "leave_list" -> {
            // Only a list someone ELSE shared with the user can be left; the
            // user's own list has delete_list / archive_list. TRUE from the seam
            // means the server confirmed — the local row is dropped only then.
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            val sharedWithMe = c.myRole != null && c.myRole != "owner"
            if (!sharedWithMe || scratch.newLists[c.id] != null || api.isCollectionOwner(c.id)) {
                return "error: \"${c.name}\" is the user's own list — there is nothing to leave; delete_list or archive_list it instead"
            }
            if (!api.leaveCollection(c.id)) return "error: couldn't leave \"${c.name}\" — try again"
            "ok: left list \"${c.name}\" — the owner keeps it"
        }

        "share_list" -> {
            // NEVER shares here: sharing sends the user's content to another
            // person, so it always waits for an on-screen confirm tap.
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            if (scratch.newLists[c.id] == null && !api.isCollectionOwner(c.id)) return "error: only its owner can share \"${c.name}\""
            val res = resolveListShareRequest(
                listId = c.id, listName = c.name, person = args.str("person"), role = args.str("role"),
                people = api.getShareCandidates(), newId = ::newUuid,
            )
            res.pending?.let { api.stageShare(it) }
            res.message
        }

        "edit_list_item" -> {
            val c = findList(args.str("listId"), api, scratch)
            val item = c?.items?.firstOrNull { it.id == args.str("itemId") }
            if (c == null || item == null) return "error: list item not found"
            val body = args.str("body") ?: return "error: body required"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            if (body.trim() == item.body) return "error: that item already says \"${item.body}\" — nothing changed"
            if (!api.updateCollectionItem(c.id, item.id, body, null)) return NOT_SAVED
            "ok: edited item in \"${c.name}\" → \"${body.trim()}\""
        }

        "remove_list_item" -> {
            val c = findList(args.str("listId"), api, scratch)
            val item = c?.items?.firstOrNull { it.id == args.str("itemId") }
            if (c == null || item == null) return "error: list item not found"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            if (!api.removeCollectionItem(c.id, item.id)) return NOT_SAVED
            "ok: removed \"${item.body}\" from \"${c.name}\""
        }

        "set_list_item_done" -> {
            val c = findList(args.str("listId"), api, scratch)
            val item = c?.items?.firstOrNull { it.id == args.str("itemId") }
            if (c == null || item == null) return "error: list item not found"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            val done = args.bool("done") ?: true
            if ((item.done ?: false) == done) return "error: \"${item.body}\" is already ${if (done) "ticked" else "unticked"} — nothing changed"
            if (!api.updateCollectionItem(c.id, item.id, null, done)) return NOT_SAVED
            "ok: ${if (done) "ticked" else "unticked"} \"${item.body}\" in \"${c.name}\""
        }

        "pin_list_item" -> {
            val c = findList(args.str("listId"), api, scratch)
            val item = c?.items?.firstOrNull { it.id == args.str("itemId") }
            if (c == null || item == null) return "error: list item not found"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            val pinned = args.bool("pinned") ?: true
            if ((item.pinned ?: false) == pinned) return "error: \"${item.body}\" is already ${if (pinned) "pinned" else "unpinned"} — nothing changed"
            if (!api.setCollectionItemPinned(c.id, item.id, pinned)) return NOT_SAVED
            "ok: ${if (pinned) "pinned" else "unpinned"} \"${item.body}\" in \"${c.name}\""
        }

        // ── AREAS & TAGS ──
        "create_area" -> {
            // `str` hands back the untrimmed text: " Home " slipped past the duplicate
            // check below (parity with iOS build 81, audit 2026-09-22 C19).
            val nm = args.str("name")?.trim() ?: return "error: name required"
            if (api.getAreaRows().any { it.name.equals(nm, ignoreCase = true) }) return "error: area \"$nm\" already exists"
            if (!api.addArea(nm, args.str("color"))) return NOT_SAVED
            "ok: created area \"$nm\""
        }

        "rename_area" -> {
            val from = args.str("name")?.trim()
            val to = args.str("newName")?.trim()
            val row = api.getAreaRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no area named \"${from ?: ""}\" — areas: ${api.getAreas().joinToString(", ")}"
            if (to == null) return "error: newName required"
            // Tasks key areas by name and the server has unique(user_id, name): a rename
            // onto another area's name was quarantined by the outbox while the task
            // relabels synced, and the tool still said ok. Refuse rather than merge; a
            // case-only rename of the same area is fine (parity with iOS build 81, audit
            // 2026-09-22 C19).
            if (to == row.name) return "error: area \"${row.name}\" already has that name — nothing changed"
            api.getAreaRows().firstOrNull { it.id != row.id && it.name.equals(to, ignoreCase = true) }
                ?.let { return "error: area \"${it.name}\" already exists — nothing changed" }
            if (!api.updateArea(row.id, to, null)) return NOT_SAVED
            "ok: renamed area \"${from ?: ""}\" → \"$to\" (tasks updated)"
        }

        "delete_area" -> {
            val from = args.str("name")
            val row = api.getAreaRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no area named \"${from ?: ""}\""
            if (!api.removeArea(row.id)) return NOT_SAVED
            "ok: deleted area \"${from ?: ""}\" (its tasks keep everything else)"
        }

        "create_tag" -> {
            val nm = args.str("name")?.trim() ?: return "error: name required"
            // "tag ready" over an existing one used to read as created (rules §1).
            if (api.getTagRows().any { it.name.equals(nm, ignoreCase = true) }) return "error: tag \"$nm\" already exists"
            if (!api.addTag(nm)) return NOT_SAVED
            "ok: created tag \"$nm\""
        }

        "rename_tag" -> {
            val from = args.str("name")?.trim()
            val to = args.str("newName")?.trim()
            val row = api.getTagRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no tag named \"${from ?: ""}\""
            if (to == null) return "error: newName required"
            // Same unique(user_id, name) quarantine as rename_area (audit 2026-09-22 C19).
            if (to == row.name) return "error: tag \"${row.name}\" already has that name — nothing changed"
            api.getTagRows().firstOrNull { it.id != row.id && it.name.equals(to, ignoreCase = true) }
                ?.let { return "error: tag \"${it.name}\" already exists — nothing changed" }
            if (!api.updateTag(row.id, to)) return NOT_SAVED
            "ok: renamed tag \"${from ?: ""}\" → \"$to\""
        }

        "delete_tag" -> {
            val from = args.str("name")
            val row = api.getTagRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no tag named \"${from ?: ""}\""
            if (!api.removeTag(row.id)) return NOT_SAVED
            "ok: deleted tag \"${from ?: ""}\" (removed from tasks)"
        }

        // ── PEOPLE ──
        "unshare_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val who = (args.str("person") ?: "").lowercase()
            val shares = api.listTaskShares(t.id)
            if (shares.isEmpty()) return "error: \"${t.name}\" isn't shared with anyone"
            val hits = if (who.isEmpty()) shares else shares.filter { it.recipientName.lowercase().contains(who) }
            if (hits.size != 1) {
                val why = if (who.isEmpty()) "say who" else if (hits.isEmpty()) "nobody matches \"$who\"" else "more than one person matches \"$who\""
                return "error: $why — shared with: ${shares.joinToString(", ") { "${it.recipientName} (${it.level})" }}"
            }
            // The revoke is a server RPC: "stopped sharing" over a failed call
            // would leave the person with access while the user believes otherwise.
            if (!api.unshareTask(hits[0].shareId)) return "error: couldn't revoke the share — try again"
            "ok: stopped sharing \"${t.name}\" with ${hits[0].recipientName}"
        }

        // ── SETTINGS ──
        "get_settings" -> {
            val s = api.getSettings()
            val usable = if (s.usableWeekdayMin == null && s.usableWeekendMin == null) "not readable in this app (set_usable_minutes still sets them)"
            else "weekdays ${s.usableWeekdayMin ?: "?"}m, weekend days ${s.usableWeekendMin ?: "?"}m"
            "ok: settings:\n" +
                "- notifications: ${s.notificationLevel}\n" +
                "- reminders: ${if (s.reminderLeadMin == 0) "off" else "${s.reminderLeadMin} minutes before"} (the default for every task)\n" +
                "- usable minutes: $usable\n" +
                "- focus defaults: ${s.focusDefaultMin}m sessions, overrun ${if (s.focusOverrunMin == 0) "off" else "${s.focusOverrunMin}m"}, " +
                "soft exit ${onOff(s.focusSoftExit)}, pause reasons ${onOff(s.focusPauseReasons)}\n" +
                "- theme: ${s.theme}\n" +
                "- ambient sound: ${s.ambient}\n" +
                "- rituals: ${listOf("morning", "evening", "friday", "sunday").joinToString(", ") { "$it ${onOff(s.rituals[it] ?: false)}" }}"
        }

        "set_usable_minutes" -> {
            val wd = args.int("weekdayMin")
            val we = args.int("weekendMin")
            if (wd == null && we == null) return "error: give weekdayMin and/or weekendMin"
            if (wd != null && (wd < 15 || wd > 1440)) return "error: minutes must be between 15 and 1440"
            if (we != null && (we < 15 || we > 1440)) return "error: minutes must be between 15 and 1440"
            if (api.setUsableMinutes(wd, we))
                "ok: usable time set${if (wd != null) " — weekdays ${wd}m" else ""}${if (we != null) " — weekends ${we}m" else ""}"
            else "error: could not save usable minutes (offline?)"
        }

        "set_notification_level" -> {
            val lvl = (args.str("level") ?: "").lowercase()
            if (lvl !in listOf("calm", "balanced", "coach")) return "error: level must be calm, balanced, or coach"
            if (api.setNotificationLevel(lvl)) "ok: notifications set to $lvl" else "error: could not save the notification level (offline?)"
        }

        "set_reminder_lead" -> {
            val m = args.int("minutes")
            if (m == null || m !in listOf(0, 5, 10, 15)) return "error: minutes must be 0 (off), 5, 10, or 15"
            if (api.setReminderLead(m)) "ok: task reminders ${if (m == 0) "off" else "$m minutes before"}" else "error: could not save (offline?)"
        }

        "set_ritual" -> {
            val r = (args.str("ritual") ?: "").lowercase()
            if (r !in listOf("morning", "evening", "friday", "sunday")) return "error: ritual must be morning, evening, friday, or sunday"
            val on = args.bool("on") ?: true
            if (api.getSettings().rituals[r] == on) return "error: the $r moment is already ${onOff(on)} — nothing changed"
            if (!api.setRitual(r, on)) return "error: could not save the $r moment (offline?)"
            "ok: $r moment ${onOff(on)}"
        }

        "set_theme" -> {
            val themes = RegistryTools.enumOf("set_theme", "theme")
            val t = (args.str("theme") ?: "").lowercase()
            if (t !in themes) return "error: theme must be ${themes.joinToString(", ")}"
            if (api.getSettings().theme == t) return "error: the theme is already $t — nothing changed"
            if (!api.setTheme(t)) return "error: could not save the theme"
            "ok: theme set to $t"
        }

        "set_ambient_sound" -> {
            val sounds = RegistryTools.enumOf("set_ambient_sound", "sound")
            val snd = (args.str("sound") ?: "").lowercase()
            if (snd !in sounds) return "error: sound must be ${sounds.joinToString(", ")}"
            if (api.getSettings().ambient == snd) return "error: ambient sound is already $snd — nothing changed"
            if (!api.setAmbientSound(snd)) return "error: could not save the ambient sound"
            "ok: ambient sound ${if (snd == "off") "off" else "set to $snd noise"}"
        }

        "set_focus_defaults" -> {
            val lengths = RegistryTools.enumOf("set_focus_defaults", "defaultMinutes").mapNotNull { it.toIntOrNull() }
            val overruns = RegistryTools.enumOf("set_focus_defaults", "overrunMinutes").mapNotNull { it.toIntOrNull() }
            val len = args.int("defaultMinutes")
            val over = args.int("overrunMinutes")
            val soft = args.bool("softExit")
            val reasons = args.bool("pauseReasons")
            if (len == null && over == null && soft == null && reasons == null) return "error: give at least one of defaultMinutes, overrunMinutes, softExit, pauseReasons"
            if (len != null && len !in lengths) return "error: defaultMinutes must be ${lengths.joinToString(", ")}"
            if (over != null && over !in overruns) return "error: overrunMinutes must be ${overruns.joinToString(", ")} (0 = none)"
            val cur = api.getSettings()
            val parts = ArrayList<String>()
            if (len != null && len != cur.focusDefaultMin) parts += "length ${len}m"
            if (over != null && over != cur.focusOverrunMin) parts += "overrun ${if (over == 0) "off" else "${over}m"}"
            if (soft != null && soft != cur.focusSoftExit) parts += "soft exit ${onOff(soft)}"
            if (reasons != null && reasons != cur.focusPauseReasons) parts += "pause reasons ${onOff(reasons)}"
            if (parts.isEmpty()) return "error: the focus defaults are already set that way — nothing changed"
            if (!api.setFocusDefaults(len, over, soft, reasons)) return "error: could not save the focus defaults"
            "ok: focus defaults — ${parts.joinToString(", ")}"
        }

        "forget_fact" -> {
            val id = args.str("factId")
            val text = (args.str("match") ?: "").lowercase()
            val facts = api.getProfileFacts()
            val target = if (id != null) {
                facts.firstOrNull { it.id == id }
            } else {
                val hits = if (text.isEmpty()) emptyList() else facts.filter { it.fact.lowercase().contains(text) }
                if (hits.size > 1) {
                    return "error: ${hits.size} facts match \"$text\" — be more specific: ${hits.joinToString("; ") { "\"${it.fact}\"" }}"
                }
                hits.firstOrNull()
            } ?: return "error: no matching fact"
            if (!api.removeProfileFact(target.id)) return "error: couldn't forget that just now — try again"
            "ok: forgot \"${target.fact}\""
        }

        "finish_interview" -> {
            // The talk-level intro closer: the account flag + the server, exactly
            // what the in-thread picker does. Closing a closed intro changes nothing.
            if (!api.interviewPending()) return FinishInterviewTool.ALREADY
            if (!api.markInterviewDone()) return "error: couldn't mark the intro finished — the user isn't signed in"
            FinishInterviewTool.OK
        }

        // ── INSIGHTS ──
        "get_insights" -> {
            val w = InsightsWindow.fromWire((args.str("window") ?: "week").lowercase())
                ?: return "error: window must be week, month, or all"
            // By area over the user's OWN areas in their order — the list the
            // Insights screen draws (analytics P1-1, 2026-09-24).
            val areas = api.getAreaRows().sortedBy { it.sortOrder }.map { it.name }.ifEmpty { DEFAULT_AREAS }
            val out = renderInsights(api.getTasks(), api.getSessions(), api.getCaptures(), api.getReasonLogs(), api.getBlocks(), api.nowMs(), w, areas = areas)
            // The week window starts on Monday: early in the week it is a day or
            // two of data. "How was my last week?" on a Monday was answered from
            // it as if it were the week before (Ahmad, 2026-09-20) (parity with
            // iOS build 76, 3564ccc).
            if (w != InsightsWindow.WEEK) return out
            val dow = IsoDate.dayOfWeek(api.todayIso())   // 0 = Sunday … 1 = Monday
            val daysIn = if (dow == 0) 7 else dow
            if (daysIn > 2) out
            else out + "\nnote: this is the CURRENT week, ${if (daysIn == 1) "today only" else "two days"} so far — it says nothing about last week. If they asked about last week, call get_period_review with period=last_week — or, if you don't have that tool, say this window can't show last week and offer the month (window: month)."
        }

        // How a past day / week / month went (week-review-spec). The pure
        // renderer reads the same D1-filtered engine as the Insights page, so
        // its numbers are the page's. historyFloor is null on Android: a capped
        // hydrate is merged, and sessions / captures / reason logs are cursor
        // tables that page their whole history (§3.6).
        "get_period_review" -> renderPeriodReview(
            PeriodReviewArgs(args.str("period"), args.str("date"), args.str("from"), args.str("to")),
            api.getTasks(), api.getBlocks(), api.getSessions(), api.getCaptures(), api.getReasonLogs(),
            api.nowMs(),
            historyFloor = null,
            blocksPartial = api.calBlocksMayBeTruncated(),
        )

        // ── NAVIGATE ──
        "open_screen" -> {
            val s = (args.str("screen") ?: "").lowercase()
            val id = args.str("id")
            if (s !in AssistantScreens.known) {
                return "error: unknown screen \"$s\" — try ${AssistantScreens.registry.joinToString(", ")}"
            }
            val withId = id != null && (s == "tasks" || s == "lists" || s == "collections")
            api.navigate(s, if (withId) id else null)
            "ok: opened $s"
        }

        else -> null
    }
}
