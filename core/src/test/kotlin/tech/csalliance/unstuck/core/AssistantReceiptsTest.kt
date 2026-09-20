package tech.csalliance.unstuck.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import tech.csalliance.unstuck.core.logic.Tone
import tech.csalliance.unstuck.core.logic.deriveReceipt
import tech.csalliance.unstuck.core.logic.planReceiptUndo
import tech.csalliance.unstuck.core.logic.receiptArgsFromJson
import tech.csalliance.unstuck.core.model.TaskItem

// Ported from lib/assistant/receipts.test.ts (+ the 2026-09-02 full-surface
// cases, via AssistantReceiptsTests.swift). A receipt is the app's claim about
// what changed, so it must come from the executor's structured result — never
// from model prose — and its Undo must target the real row (by id, never name).
class AssistantReceiptsTest {

    private fun task(id: String = "t1", name: String = "Report", done: Boolean = false, moveCount: Int? = null, completedAt: String? = null) =
        TaskItem(
            id = id, name = name, estimateMin = 25, done = done, moveCount = moveCount, completedAt = completedAt,
            createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z",
        )

    private fun r(name: String, result: String, args: ReceiptArgs = ReceiptArgs(), tasks: List<TaskItem> = emptyList(), tone: Tone = Tone.GENTLE) =
        deriveReceipt(name, args, result, tasks, tone)

    // ---- deriveReceipt

    @Test fun `create_task - receipt with delete undo carrying the created id`() {
        val r = deriveReceipt("create_task", ReceiptArgs(), "ok: created task id=abc123 name=\"Dentist\"", emptyList())
        assertEquals(Receipt(ReceiptIcon.PLUS, "Created “Dentist”", ReceiptUndo(ReceiptUndoKind.DELETE_TASK, "abc123")), r)
    }

    @Test fun `schedule_task carries date and time from args`() {
        val r = deriveReceipt(
            "schedule_task", ReceiptArgs(date = "2026-08-04", startTime = "15:00"),
            "ok: scheduled \"Dentist\" 2026-08-04 15:00", emptyList(),
        )
        assertEquals("Scheduled “Dentist” · 2026-08-04 15:00", r!!.label)
        assertNull(r.undo)
    }

    @Test fun `complete_task resolves the uncomplete undo by the executor's id`() {
        val done = task(id = "x9", name = "Report", done = true)
        val r = deriveReceipt("complete_task", ReceiptArgs(), "ok: completed \"Report\" id=x9", listOf(done))
        assertEquals(ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, "x9"), r!!.undo)
        assertTrue(r.isUndoable)
    }

    @Test fun `complete_task never resolves by name (F8) - two duplicates, the id picks the right one`() {
        val a = task(id = "a", name = "Report", done = true)
        val b = task(id = "b", name = "Report", done = true)
        assertEquals(ReceiptUndo.uncompleteTask("b"), r("complete_task", "ok: completed \"Report\" id=b", tasks = listOf(a, b))!!.undo)
        // No id in the result → a receipt WITHOUT undo, never a guessed one.
        val noId = r("complete_task", "ok: completed \"Report\"", tasks = listOf(a, b))!!
        assertEquals("Completed “Report”", noId.label)
        assertNull(noId.undo)
        assertFalse(noId.isUndoable)
    }

    @Test fun `complete_task celebrates a quiet win in the user's tone`() {
        val dodger = task(id = "x9", name = "Tax return", done = true, moveCount = 4)
        assertEquals(
            "“Tax return” finally happened — it dodged you 4 times, and you got it anyway.",
            r("complete_task", "ok: completed \"Tax return\" id=x9", tasks = listOf(dodger))!!.label,
        )
        assertEquals(
            "That’s “Tax return” done after 4 dodges. The hard kind of done.",
            r("complete_task", "ok: completed \"Tax return\" id=x9", tasks = listOf(dodger), tone = Tone.HONEST)!!.label,
        )
        // An ordinary completion keeps the plain label.
        assertEquals("Completed “Report”", r("complete_task", "ok: completed \"Report\" id=t1", tasks = listOf(task(done = true)))!!.label)
    }

    @Test fun `failed results produce NO receipt`() {
        assertNull(deriveReceipt("create_task", ReceiptArgs(), "error: nope", emptyList()))
    }

    @Test fun `read-only, navigation, staging and unknown tools produce NO receipt`() {
        assertNull(r("list_tasks", "ok: 3 tasks"))
        assertNull(r("get_schedule", "ok:\nMonday 2026-09-07 (TODAY): —"))
        assertNull(r("get_tasks", "ok: 2 tasks"))
        assertNull(r("get_captures", "ok: 0 open captures"))
        assertNull(r("get_insights", "ok: Insights, week so far"))
        assertNull(r("get_calls", "ok: 1 call"))
        assertNull(r("open_screen", "ok: opened today"))
        assertNull(r("share_task", "ok: staged"))
    }

    @Test fun `set_task_later names the task and distinguishes both directions`() {
        val t = task(id = "t7", name = "Taxes")
        assertEquals("Moved to Later — “Taxes”", deriveReceipt("set_task_later", ReceiptArgs(taskId = "t7"), "ok", listOf(t))!!.label)
        assertEquals("Moved to Later — “Taxes”", deriveReceipt("set_task_later", ReceiptArgs(taskId = "t7", later = true), "ok", listOf(t))!!.label)
        assertEquals("Brought back from Later — “Taxes”", deriveReceipt("set_task_later", ReceiptArgs(taskId = "t7", later = false), "ok", listOf(t))!!.label)
    }

    @Test fun `set_task_recurrence reports the kind, or its removal`() {
        val t = task(id = "t7", name = "Taxes")
        assertEquals("Repeats weekly — “Taxes”", deriveReceipt("set_task_recurrence", ReceiptArgs(taskId = "t7", kind = "weekly"), "ok", listOf(t))!!.label)
        assertEquals("Repeat removed — “Taxes”", deriveReceipt("set_task_recurrence", ReceiptArgs(taskId = "t7"), "ok", listOf(t))!!.label)
    }

    @Test fun `the remaining base write tools each get their own glyph`() {
        assertEquals(ReceiptIcon.PENCIL, r("update_task", "ok: updated \"Report\"")!!.icon)
        assertEquals(ReceiptIcon.TRASH, r("delete_task", "ok: deleted \"Report\"")!!.icon)
        assertEquals("Created list “Groceries”", r("create_list", "ok: created list id=l1 name=\"Groceries\"")!!.label)
        assertEquals("Added to “Groceries”", r("add_to_list", "ok: added to \"Groceries\"")!!.label)
        assertEquals("Promoted “Milk” to a task", r("promote_item_to_task", "ok: promoted \"Milk\"")!!.label)
    }

    @Test fun `an unnamed result falls back to a generic noun rather than lying`() {
        assertEquals("Updated “task”", r("update_task", "ok")!!.label)
    }

    @Test fun `quoted names span the outermost quotes`() {
        assertEquals("Updated “Say \"hi\" to Sam”", r("update_task", "ok: updated \"Say \"hi\" to Sam\"")!!.label)
    }

    // ---- bulk + profile

    @Test fun `bulk create and complete carry their ids for undo`() {
        assertEquals(
            Receipt(ReceiptIcon.PLUS, "Created 3 tasks", ReceiptUndo.deleteTasks(listOf("a", "b", "c"))),
            r("create_tasks", "ok: created 3 tasks ids=a,b,c — \"One\", \"Two\", \"Three\""),
        )
        assertEquals(
            Receipt(ReceiptIcon.CHECK, "Completed 2 tasks", ReceiptUndo.uncompleteTasks(listOf("x", "y"))),
            r("complete_tasks", "ok: completed 2 tasks ids=x,y"),
        )
        // No ids → no undo, and an unparseable count reads "?".
        assertEquals(Receipt(ReceiptIcon.PLUS, "Created ? tasks"), r("create_tasks", "ok: created tasks"))
        assertEquals(listOf("a", "b", "c"), ReceiptUndo.deleteTasks(listOf("a", "b", "c")).taskIds)
        assertEquals(listOf("x"), ReceiptUndo.deleteTask("x").taskIds)
        assertEquals(emptyList<String>(), ReceiptUndo.forgetFact("f").taskIds)
    }

    @Test fun `save_profile_fact is the consent receipt with a forget undo`() {
        assertEquals(
            Receipt(ReceiptIcon.PENCIL, "Noted: Sam — partner, works night shifts", ReceiptUndo.forgetFact("f1")),
            r("save_profile_fact", "ok: remembered id=f1 \"Sam — partner, works night shifts\""),
        )
        assertEquals("Forgot: Sam — partner", r("forget_fact", "ok: forgot \"Sam — partner\"")!!.label)
    }

    // ---- full app surface (2026-09-02)

    @Test fun `task and occurrence tools`() {
        assertEquals(Receipt(ReceiptIcon.CHECK, "Reopened Report", ReceiptUndo.completeTask("t1")), r("uncomplete_task", "ok: reopened \"Report\" id=t1"))
        assertEquals("Unscheduled Report", r("unschedule_task", "ok: unscheduled \"Report\" (task kept, 1 slot removed)")!!.label)
        assertEquals("Skipped Gym today", r("skip_occurrence", "ok: skipped \"Gym\" on 2026-09-02 (the task and its other days stay)")!!.label)
        assertEquals("Done for today: Gym", r("complete_occurrence", "ok: marked \"Gym\" done for 2026-09-02 (series continues)")!!.label)
        assertEquals(
            Receipt(ReceiptIcon.CALENDAR, "Blocked Dentist", ReceiptUndo.deleteTask("bt1")),
            r("block_time", "ok: blocked \"Dentist\" 2026-09-03 10:00 for 60m id=bt1"),
        )
        assertEquals("carried 2 to 2026-09-03", r("carry_to_tomorrow", "ok: carried 2 to 2026-09-03 — \"Report\", \"Gym\"")!!.label)
    }

    @Test fun `focus tools`() {
        assertEquals(
            Receipt(ReceiptIcon.CHECK, "Focus started: Report"),
            r("start_focus", "ok: focus started on \"Report\" (25m) — the user is now on the focus screen"),
        )
        assertEquals("Focus paused", r("pause_focus", "ok: paused the focus session")!!.label)
        assertEquals("Focus resumed", r("resume_focus", "ok: resumed the focus session")!!.label)
        assertEquals("extended the session by 10m", r("extend_focus", "ok: extended the session by 10m")!!.label)
        assertEquals("Focus cancelled", r("cancel_focus", "ok: cancelled the focus session (nothing logged).")!!.label)
    }

    @Test fun `capture tools`() {
        assertEquals(Receipt(ReceiptIcon.PLUS, "Captured: ask Sam", ReceiptUndo.deleteCapture("c1")), r("add_capture", "ok: captured id=c1 [idea] \"ask Sam\""))
        assertEquals(
            Receipt(ReceiptIcon.PLUS, "Task from capture: ask Sam", ReceiptUndo.deleteTask("t9")),
            r("promote_capture", "ok: promoted capture to task id=t9 name=\"ask Sam\""),
        )
        assertEquals("Resolved: ask Sam", r("resolve_capture", "ok: resolved capture \"ask Sam\"")!!.label)
        assertEquals(Receipt(ReceiptIcon.PENCIL, "Deleted capture"), r("delete_capture", "ok: deleted capture \"ask Sam\""))
    }

    @Test fun `list, area, tag and settings tools echo the result minus the parenthetical`() {
        assertEquals(Receipt(ReceiptIcon.PENCIL, "renamed list \"Groceries\" → \"Shopping\""), r("rename_list", "ok: renamed list \"Groceries\" → \"Shopping\""))
        assertEquals(ReceiptIcon.PENCIL, r("archive_list", "ok: archived list \"Old\"")!!.icon)
        assertEquals("deleted list \"Old\"", r("delete_list", "ok: deleted list \"Old\"")!!.label)
        assertEquals(ReceiptIcon.PENCIL, r("edit_list_item", "ok: edited item in \"Groceries\" → \"Oat milk\"")!!.icon)
        assertEquals("removed \"Milk\" from \"Groceries\"", r("remove_list_item", "ok: removed \"Milk\" from \"Groceries\"")!!.label)
        assertEquals(Receipt(ReceiptIcon.CHECK, "ticked \"Milk\" in \"Groceries\""), r("set_list_item_done", "ok: ticked \"Milk\" in \"Groceries\""))
        assertEquals(Receipt(ReceiptIcon.PLUS, "created area \"Studio\""), r("create_area", "ok: created area \"Studio\""))
        assertEquals("renamed area \"Work\" → \"Studio\"", r("rename_area", "ok: renamed area \"Work\" → \"Studio\" (tasks updated)")!!.label)
        assertEquals("deleted area \"Studio\"", r("delete_area", "ok: deleted area \"Studio\" (its tasks keep everything else)")!!.label)
        assertEquals(Receipt(ReceiptIcon.PLUS, "tag \"deep\" ready"), r("create_tag", "ok: tag \"deep\" ready"))
        assertEquals(ReceiptIcon.PENCIL, r("rename_tag", "ok: renamed tag \"deep\" → \"focus\"")!!.icon)
        assertEquals("deleted tag \"deep\"", r("delete_tag", "ok: deleted tag \"deep\" (removed from tasks)")!!.label)
        assertEquals("stopped sharing \"Report\" with Sam", r("unshare_task", "ok: stopped sharing \"Report\" with Sam")!!.label)
        assertEquals("usable time set — weekdays 240 min", r("set_usable_minutes", "ok: usable time set — weekdays 240 min")!!.label)
        assertEquals(ReceiptIcon.PENCIL, r("set_notification_level", "ok: notifications set to calm")!!.icon)
        assertEquals("reminders 10 min before", r("set_reminder_lead", "ok: reminders 10 min before")!!.label)
        assertEquals(Receipt(ReceiptIcon.PENCIL, "morning moment on"), r("set_ritual", "ok: morning moment on"))
    }

    @Test fun `call tools (Part B)`() {
        assertEquals(
            Receipt(ReceiptIcon.CALENDAR, "Call booked Thu 14:45 — speak to James · 4 notes", ReceiptUndo.cancelCall("cr1")),
            r("request_call", "ok: call booked 2026-09-03 14:45 \"speak to James\" (4 notes) id=cr1"),
        )
        assertEquals("Call booked Thu 14:45 — speak to James · 1 note", r("request_call", "ok: call booked 2026-09-03 14:45 \"speak to James\" (1 note) id=cr1")!!.label)
        assertNull(r("request_call", "ok: booked"))
        assertEquals(
            Receipt(ReceiptIcon.PENCIL, "Call updated Fri 09:00 — speak to James · 2 notes"),
            r("update_call", "ok: updated call \"speak to James\" — 2026-09-04 09:00, 2 notes"),
        )
        assertEquals(Receipt(ReceiptIcon.TRASH, "Call cancelled — speak to James"), r("cancel_call", "ok: cancelled call \"speak to James\""))
        assertEquals(ReceiptUndoPlan.CancelCall("cr1"), planReceiptUndo(ReceiptUndo.cancelCall("cr1"), emptyList(), "n"))
    }

    @Test fun `every write tool in the contract yields a receipt`() {
        val writes = listOf(
            "create_task", "schedule_task", "update_task", "set_task_later", "set_task_recurrence", "complete_task",
            "create_tasks", "complete_tasks", "delete_task", "create_list", "add_to_list", "promote_item_to_task",
            "save_profile_fact", "uncomplete_task", "unschedule_task", "skip_occurrence", "complete_occurrence",
            "block_time", "carry_to_tomorrow", "start_focus", "pause_focus", "resume_focus", "extend_focus",
            "cancel_focus", "add_capture", "promote_capture", "resolve_capture", "delete_capture", "rename_list",
            "archive_list", "delete_list", "edit_list_item", "remove_list_item", "set_list_item_done", "create_area",
            "rename_area", "delete_area", "create_tag", "rename_tag", "delete_tag", "unshare_task",
            "set_usable_minutes", "set_notification_level", "set_reminder_lead", "set_ritual", "forget_fact",
        )
        for (name in writes) assertNotNull("$name should produce a receipt", r(name, "ok: something \"X\" id=1"))
        assertEquals(46, writes.size)
        // The 6 read/stage tools stay receipt-less: get_schedule, share_task, get_tasks, get_captures, get_insights, open_screen.
    }

    @Test fun `receipt args decode from the raw tool-call JSON`() {
        assertEquals(
            ReceiptArgs(taskId = "t7", date = "2026-09-04", startTime = "09:00", later = false, kind = "weekly"),
            receiptArgsFromJson("""{"taskId":"t7","date":"2026-09-04","startTime":"09:00","later":false,"kind":"weekly","extra":1}"""),
        )
        assertEquals(ReceiptArgs(), receiptArgsFromJson("""{"tasks":[{"name":"a"""))
        assertEquals(ReceiptArgs(), receiptArgsFromJson("""{"taskId":null}"""))
    }

    // ---- planReceiptUndo

    @Test fun `delete undo removes the created task`() {
        assertEquals(ReceiptUndoPlan.Remove("abc"), planReceiptUndo(ReceiptUndo(ReceiptUndoKind.DELETE_TASK, "abc"), emptyList(), "2026-08-02T12:00:00Z"))
    }

    @Test fun `uncomplete flips done back off, missing task plans nothing`() {
        val t = task(id = "x9", done = true, completedAt = "2026-08-02T10:00:00Z")
        val plan = planReceiptUndo(ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, "x9"), listOf(t), "2026-08-02T12:00:00Z") as ReceiptUndoPlan.Restore
        assertFalse(plan.task.done)
        assertNull(plan.task.completedAt)
        assertEquals("2026-08-02T12:00:00Z", plan.task.updatedAt)
        assertNull(planReceiptUndo(ReceiptUndo(ReceiptUndoKind.UNCOMPLETE_TASK, "gone"), emptyList(), "x"))
    }

    @Test fun `bulk undos plan every present task`() {
        assertEquals(ReceiptUndoPlan.RemoveMany(listOf("a", "b")), planReceiptUndo(ReceiptUndo.deleteTasks(listOf("a", "b")), emptyList(), "n"))
        val a = task(id = "a", done = true, completedAt = "2026-08-02T10:00:00Z")
        val plan = planReceiptUndo(ReceiptUndo.uncompleteTasks(listOf("a", "gone")), listOf(a), "2026-08-02T11:00:00Z") as ReceiptUndoPlan.RestoreMany
        assertEquals(listOf("a"), plan.tasks.map { it.id })
        assertFalse(plan.tasks[0].done)
        assertNull(planReceiptUndo(ReceiptUndo.uncompleteTasks(listOf("gone")), emptyList(), "n"))
    }

    @Test fun `fact, capture and complete undos`() {
        assertEquals(ReceiptUndoPlan.ForgetFact("f1"), planReceiptUndo(ReceiptUndo.forgetFact("f1"), emptyList(), "n"))
        assertEquals(ReceiptUndoPlan.DeleteCapture("c1"), planReceiptUndo(ReceiptUndo.deleteCapture("c1"), emptyList(), "n"))
        val t = task(id = "t1")
        val plan = planReceiptUndo(ReceiptUndo.completeTask("t1"), listOf(t), "2026-08-02T11:00:00Z") as ReceiptUndoPlan.Complete
        assertTrue(plan.task.done)
        assertEquals("2026-08-02T11:00:00Z", plan.task.completedAt)
        assertEquals("2026-08-02T11:00:00Z", plan.task.updatedAt)
        assertNull(planReceiptUndo(ReceiptUndo.completeTask("gone"), emptyList(), "n"))
    }

    @Test fun `receipts round-trip through the thread persistence, including legacy undo shapes`() {
        val json = Json { encodeDefaults = false }
        val r = Receipt(ReceiptIcon.CHECK, "Completed “Report”", ReceiptUndo.uncompleteTask("x9"), undone = true)
        val back = json.decodeFromString<Receipt>(json.encodeToString(r))
        assertEquals(r, back)
        assertFalse("an already-used undo must not come back after a relaunch", back.isUndoable)
        for (undo in listOf(
            ReceiptUndo.deleteTasks(listOf("a")), ReceiptUndo.uncompleteTasks(listOf("a", "b")), ReceiptUndo.forgetFact("f"),
            ReceiptUndo.deleteCapture("c"), ReceiptUndo.completeTask("t"), ReceiptUndo.cancelCall("k"),
        )) {
            val rt = json.decodeFromString<Receipt>(json.encodeToString(Receipt(ReceiptIcon.PLUS, "x", undo)))
            assertEquals(undo, rt.undo)
        }
        // A pre-2026-09 persisted receipt (`{kind, id}` only) still decodes.
        val legacy = json.decodeFromString<Receipt>("""{"icon":"PLUS","label":"Created “X”","undo":{"kind":"DELETE_TASK","id":"abc"}}""")
        assertEquals(ReceiptUndo.deleteTask("abc"), legacy.undo)
        assertTrue(legacy.isUndoable)
    }

    // ── 2026-09-20 tooling rewrite: cards for the new write tools (rules §4) ──

    @Test fun `add_to_list names the item and the list, and still reads the old shape`() {
        assertEquals("Added “Milk” to “Groceries”", r("add_to_list", "ok: added \"Milk\" to \"Groceries\" id=i9")!!.label)
        assertEquals(ReceiptIcon.LIST, r("add_to_list", "ok: added \"Milk\" to \"Groceries\" id=i9")!!.icon)
        assertEquals("Added to “Groceries”", r("add_to_list", "ok: added to \"Groceries\"")!!.label)
    }

    @Test fun `the new write tools each get a card and no undo`() {
        // Echo cards are the `ok:` line itself (straight quotes and all), minus a trailing parenthetical.
        assertEquals("recoloured list \"Groceries\" → green", r("recolor_list", "ok: recoloured list \"Groceries\" → green")!!.label)
        assertEquals("pinned \"Milk\" in \"Groceries\"", r("pin_list_item", "ok: pinned \"Milk\" in \"Groceries\"")!!.label)
        assertEquals("left list \"Team\" — the owner keeps it", r("leave_list", "ok: left list \"Team\" — the owner keeps it")!!.label)
        assertEquals("reminder for \"Dentist\" set to 10 minutes before", r("set_task_reminder", "ok: reminder for \"Dentist\" set to 10 minutes before (no upcoming slot yet — it applies once the task is scheduled)")!!.label)
        assertEquals("theme set to dark", r("set_theme", "ok: theme set to dark")!!.label)
        assertEquals("focus defaults — length 45m, soft exit off", r("set_focus_defaults", "ok: focus defaults — length 45m, soft exit off")!!.label)
        assertEquals("ambient sound set to brown noise", r("set_ambient_sound", "ok: ambient sound set to brown noise")!!.label)
        assertEquals(Receipt(ReceiptIcon.PLUS, "Restored: Call the plumber"), r("restore_capture", "ok: restored capture \"Call the plumber\" to the inbox"))
        assertEquals(Receipt(ReceiptIcon.CHECK, "Focus finished: Report"), r("finish_focus", "ok: finished focus on \"Report\" — logged 12m"))
        assertEquals(Receipt(ReceiptIcon.CHECK, "Focus finished: Report · done"), r("finish_focus", "ok: finished focus on \"Report\" — logged 12m, task marked done"))
        for (name in listOf("recolor_list", "pin_list_item", "leave_list", "set_task_reminder", "set_theme", "set_focus_defaults", "set_ambient_sound")) {
            assertNull("$name has no undo", r(name, "ok: x")!!.undo)
            assertEquals(ReceiptIcon.PENCIL, r(name, "ok: x")!!.icon)
        }
    }

    @Test fun `staged shares and reads never earn a card`() {
        assertNull(r("share_list", "ok: prepared a share of list \"Groceries\" with Sam (viewer)."))
        assertNull(r("share_task", "ok: prepared a share of \"Report\" with Sam (view)."))
        assertNull(r("find_tasks", "ok: 1 match for \"milk\":\n- Buy milk [id=a] 25m"))
        assertNull(r("get_settings", "ok: settings:\n- theme: dark"))
        assertNull(r("finish_interview", "ok: intro finished — it won't be asked again"))
        assertNull(r("recolor_list", "error: unknown colour \"pink\""))
    }
}
