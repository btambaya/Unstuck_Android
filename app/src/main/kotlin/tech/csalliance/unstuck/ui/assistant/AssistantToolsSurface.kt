package tech.csalliance.unstuck.ui.assistant

import tech.csalliance.unstuck.core.logic.InsightsWindow
import tech.csalliance.unstuck.core.logic.addDaysIso
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.rejectPastDate
import tech.csalliance.unstuck.core.logic.rejectPastTime
import tech.csalliance.unstuck.core.logic.renderInsights
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView

// The full app surface (2026-09-02: "the model should be able to do everything
// a user can do"): reopen/list tasks, calendar edits, focus controls, captures,
// list edits, areas + tags, unshare, settings, insights, navigation. 1:1 with
// the matching cases in lib/assistant/tools.ts / iOS AssistantTools+Surface.swift
// — result strings are the contract's, byte for byte.

/** The contract's screen vocabulary (+ the web's aliases). */
object AssistantScreens {
    val known: Set<String> = setOf(
        "today", "dashboard", "home", "tasks", "calendar", "day", "week", "month", "focus", "insights", "analytics",
        "lists", "collections", "captures", "inbox", "settings", "people", "notifications", "areas",
    )
}

private fun captureTagOf(raw: String): CaptureTag? = when (raw) {
    "follow-up" -> CaptureTag.FOLLOW_UP
    "idea" -> CaptureTag.IDEA
    "edit" -> CaptureTag.EDIT
    "question" -> CaptureTag.QUESTION
    "distraction" -> CaptureTag.DISTRACTION
    else -> null
}

private fun CaptureTag.wire(): String = name.lowercase().replace('_', '-')

suspend fun runSurfaceTool(name: String, args: ToolArgs, api: AssistantApi, scratch: TurnScratch): String? {
    val now = api::nowIso

    return when (name) {
        // ── TASKS ──
        "uncomplete_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            // Already open → error, never "ok: reopened": that receipt's Undo would
            // COMPLETE a task the user never finished (web parity).
            if (!t.done) return "error: \"${t.name}\" is already open — nothing changed"
            val upd = t.copy(done = false, completedAt = null, updatedAt = now())
            api.upsertTask(upd)
            api.notifyTaskReopenedIfShared(upd)
            scratch.newTasks[t.id] = upd
            "ok: reopened \"${t.name}\" id=${t.id}"
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
            val lines = rows.take(30).map { t ->
                // Recurring rows are OCCURRENCES: their id is the block id, the
                // real task id is templateId — the model must get the task id.
                val occ = occurrenceBlockFor(t.id, tasks, blocks)
                val taskId = occ?.taskId ?: t.id
                val b = occ ?: nextLiveBlock(blocks, api.todayIso(), taskId)
                val sb = StringBuilder("- ${t.name} [id=$taskId] ${t.estimateMin}m")
                t.lifeArea?.takeIf { it.isNotEmpty() }?.let { sb.append(" · $it") }
                if (b != null) sb.append(" · ${b.date} ${b.startTime}")
                if (occ != null) sb.append(" · repeats")
                if (t.later == true) sb.append(" · Later")
                if ((t.moveCount ?: 0) >= 3) sb.append(" · slipped ${t.moveCount}×")
                if (t.done) sb.append(" · done")
                sb.toString()
            }
            "ok: ${view.label} (${rows.size})${if (rows.size > 30) ", first 30" else ""}:\n${if (lines.isEmpty()) "(none)" else lines.joinToString("\n")}"
        }

        // ── CALENDAR ──
        "unschedule_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
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
            api.upsertBlock(b.copy(done = true, completedAt = now()))
            if (t.recurrence == null) {
                val upd = t.copy(done = true, completedAt = now(), updatedAt = now())
                api.upsertTask(upd)
                scratch.newTasks[t.id] = upd
            }
            "ok: marked \"${t.name}\" done for $date${if (t.recurrence != null) " (series continues)" else ""}"
        }

        "block_time" -> {
            val nm = args.str("name")
            val date = args.str("date")
            val startTime = args.str("startTime")
            val dur = args.int("durationMin") ?: 60
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
            val todays = api.getBlocks().filter { b ->
                b.taskId != null && b.date == today && !b.done && !b.skipped && isTaskBlock(b) &&
                    (wanted == null || (b.taskId ?: "") in wanted)
            }
            if (todays.isEmpty()) return "error: nothing left on today to carry"
            val names = ArrayList<String>()
            for (b in todays) {
                val t = api.getTasks().firstOrNull { it.id == b.taskId }
                val tomorrowTaken = api.getBlocks().any { it.taskId == b.taskId && it.date == tomorrow && !it.skipped }
                api.upsertBlock(if (tomorrowTaken) b.copy(skipped = true) else b.copy(date = tomorrow))
                if (t != null) api.upsertTask(bumpMoveCount(t, now()))
                names += t?.name ?: b.taskName
            }
            "ok: carried ${names.size} to $tomorrow — ${names.joinToString(", ") { "\"$it\"" }}"
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
            api.startFocus(t.id, est, occ?.id)
            api.navigate("focus", null)
            "ok: focus started on \"${t.name}\" (${est}m) — the user is now on the focus screen"
        }

        "pause_focus" -> {
            val live = api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            if (live.paused) return "error: it is already paused"
            api.pauseFocus()
            "ok: paused the focus session"
        }

        "resume_focus" -> {
            val live = api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            if (!live.paused) return "error: it is not paused"
            api.resumeFocus()
            "ok: resumed the focus session"
        }

        "extend_focus" -> {
            api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            val mins = args.int("minutes") ?: 10
            if (mins < 1 || mins > 180) return "error: minutes must be between 1 and 180"
            api.extendFocus(mins)
            "ok: extended the session by ${mins}m"
        }

        "cancel_focus" -> {
            api.getLiveFocus()?.takeIf { it.sessionStart != null } ?: return "error: no focus session is running"
            api.cancelFocus()
            "ok: cancelled the focus session (nothing logged). To finish and LOG a session, the user taps Done on the focus screen."
        }

        // ── CAPTURES ──
        "add_capture" -> {
            val body = args.str("body") ?: return "error: body required"
            val tag = captureTagOf((args.str("tag") ?: "idea").lowercase()) ?: CaptureTag.IDEA
            val t = findTask(args.str("taskId"), api, scratch)
            val live = api.getLiveFocus()
            val c = Capture(id = newUuid(), taskId = t?.id, sessionId = if (live?.sessionStart != null) live.id else null,
                tag = tag, body = body.take(500), at = now())
            api.upsertCapture(c)
            "ok: captured id=${c.id} [${tag.wire()}] \"${c.body}\"${if (t != null) " on \"${t.name}\"" else ""}"
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

        "promote_capture" -> {
            val id = args.str("captureId")
            val c = api.getCaptures().firstOrNull { it.id == id } ?: return "error: capture not found"
            // lib/capture-actions promoteCapture: task from the body, link the capture.
            val newId = newUuid()
            val nm = c.body.take(160)
            val made = TaskItem(id = newId, name = nm.ifEmpty { "Untitled task" }, estimateMin = 25, totalFocused = 0, done = false,
                priority = Priority.MEDIUM, tags = listOf("from-capture", c.tag.wire()), objectives = emptyList(), comments = emptyList(),
                lifeArea = "Work", createdAt = now(), updatedAt = now())
            api.upsertTask(made)
            api.upsertCapture(c.copy(taskId = c.taskId ?: newId))
            api.archiveCapture(c.id, true)
            scratch.newTasks[made.id] = made
            "ok: promoted capture to task id=$newId name=\"${c.body}\""
        }

        "resolve_capture" -> {
            val id = args.str("captureId")
            val c = api.getCaptures().firstOrNull { it.id == id } ?: return "error: capture not found"
            api.archiveCapture(c.id, true)
            "ok: resolved capture \"${c.body}\""
        }

        "delete_capture" -> {
            val id = args.str("captureId")
            val c = api.getCaptures().firstOrNull { it.id == id } ?: return "error: capture not found"
            api.removeCapture(c.id)
            "ok: deleted capture \"${c.body}\""
        }

        // ── LISTS ──
        "rename_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            val nm = args.str("name") ?: return "error: name required"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            api.renameCollection(c.id, nm)
            "ok: renamed list \"${c.name}\" → \"$nm\""
        }

        "archive_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            val archived = args.bool("archived") ?: true
            api.updateCollection(c.id, archived, null)
            "ok: ${if (archived) "archived" else "unarchived"} list \"${c.name}\""
        }

        "delete_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            api.removeCollection(c.id)
            scratch.newLists.remove(c.id)
            "ok: deleted list \"${c.name}\""
        }

        "edit_list_item" -> {
            val c = findList(args.str("listId"), api, scratch)
            val item = c?.items?.firstOrNull { it.id == args.str("itemId") }
            if (c == null || item == null) return "error: list item not found"
            val body = args.str("body") ?: return "error: body required"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            api.updateCollectionItem(c.id, item.id, body, null)
            "ok: edited item in \"${c.name}\" → \"$body\""
        }

        "remove_list_item" -> {
            val c = findList(args.str("listId"), api, scratch)
            val item = c?.items?.firstOrNull { it.id == args.str("itemId") }
            if (c == null || item == null) return "error: list item not found"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            api.removeCollectionItem(c.id, item.id)
            "ok: removed \"${item.body}\" from \"${c.name}\""
        }

        "set_list_item_done" -> {
            val c = findList(args.str("listId"), api, scratch)
            val item = c?.items?.firstOrNull { it.id == args.str("itemId") }
            if (c == null || item == null) return "error: list item not found"
            if (!api.canEditCollection(c.id)) return "error: you can't edit \"${c.name}\""
            val done = args.bool("done") ?: true
            api.updateCollectionItem(c.id, item.id, null, done)
            "ok: ${if (done) "ticked" else "unticked"} \"${item.body}\" in \"${c.name}\""
        }

        // ── AREAS & TAGS ──
        "create_area" -> {
            val nm = args.str("name") ?: return "error: name required"
            if (api.getAreaRows().any { it.name.equals(nm, ignoreCase = true) }) return "error: area \"$nm\" already exists"
            api.addArea(nm, args.str("color"))
            "ok: created area \"$nm\""
        }

        "rename_area" -> {
            val from = args.str("name")
            val to = args.str("newName")
            val row = api.getAreaRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no area named \"${from ?: ""}\" — areas: ${api.getAreas().joinToString(", ")}"
            if (to == null) return "error: newName required"
            api.updateArea(row.id, to, null)
            "ok: renamed area \"${from ?: ""}\" → \"$to\" (tasks updated)"
        }

        "delete_area" -> {
            val from = args.str("name")
            val row = api.getAreaRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no area named \"${from ?: ""}\""
            api.removeArea(row.id)
            "ok: deleted area \"${from ?: ""}\" (its tasks keep everything else)"
        }

        "create_tag" -> {
            val nm = args.str("name") ?: return "error: name required"
            api.addTag(nm)
            "ok: tag \"$nm\" ready"
        }

        "rename_tag" -> {
            val from = args.str("name")
            val to = args.str("newName")
            val row = api.getTagRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no tag named \"${from ?: ""}\""
            if (to == null) return "error: newName required"
            api.updateTag(row.id, to)
            "ok: renamed tag \"${from ?: ""}\" → \"$to\""
        }

        "delete_tag" -> {
            val from = args.str("name")
            val row = api.getTagRows().firstOrNull { it.name.equals(from ?: "", ignoreCase = true) }
                ?: return "error: no tag named \"${from ?: ""}\""
            api.removeTag(row.id)
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
            api.setRitual(r, on)
            "ok: $r moment ${if (on) "on" else "off"}"
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
            api.removeProfileFact(target.id)
            "ok: forgot \"${target.fact}\""
        }

        // ── INSIGHTS ──
        "get_insights" -> {
            val w = InsightsWindow.fromWire((args.str("window") ?: "week").lowercase())
                ?: return "error: window must be week, month, or all"
            renderInsights(api.getTasks(), api.getSessions(), api.getCaptures(), api.getReasonLogs(), api.getBlocks(), api.nowMs(), w)
        }

        // ── NAVIGATE ──
        "open_screen" -> {
            val s = (args.str("screen") ?: "").lowercase()
            val id = args.str("id")
            if (s !in AssistantScreens.known) {
                return "error: unknown screen \"$s\" — try today, tasks, calendar, week, month, focus, insights, lists, captures, settings, people, notifications"
            }
            val withId = id != null && (s == "tasks" || s == "lists" || s == "collections")
            api.navigate(s, if (withId) id else null)
            "ok: opened $s"
        }

        else -> null
    }
}
