package tech.csalliance.unstuck.ui.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The ONE tool registry — every tool the executor runs, with its schema. The
// realtime voice session is configured CLIENT-side (session.update), so the
// schemas live here (names / params / descriptions mirror lib/assistant/tools.ts
// VOICE_TOOLS + iOS AssistantContext.swift VOICE_TOOLS verbatim). The text
// path gets its schemas from the server; ContractDiffTest checks this registry
// against the vendored contract so the two can't drift.

/** One property of a tool schema. */
data class ToolProp(val name: String, val type: String, val description: String, val items: JsonObject? = null)

data class ToolSpec(
    val name: String,
    val description: String,
    val required: List<String>,
    val props: List<ToolProp>,
    /** `write` tools count for the fabrication guards; `read` tools never do. */
    val readOnly: Boolean = name in READ_ONLY_TOOLS,
)

private fun p(name: String, type: String, desc: String) = ToolProp(name, type, desc)
private fun arr(name: String, itemType: String, desc: String) =
    ToolProp(name, "array", desc, buildJsonObject { put("type", itemType) })

/** The 57 contract tools (base + profile + voice surfaces), in contract order. */
val ASSISTANT_TOOL_SPECS: List<ToolSpec> = listOf(
    ToolSpec("create_task", "Create a task.", listOf("name"), listOf(
        p("name", "string", "Task title."),
        p("estimateMin", "integer", "Estimated minutes (default 25)."),
        p("lifeArea", "string", "A life-area name from context, else omit."),
        p("dueAt", "string", "Optional ISO 'by' time."),
        p("later", "boolean", "true to park in Later."),
    )),
    ToolSpec("schedule_task", "Place or MOVE a task on the calendar. Omit startTime to keep its current time. If the task has never had a time, the tool will tell you to ASK the user (suggest one) — never invent a time.", listOf("taskId", "date"), listOf(
        p("taskId", "string", "Existing task id."), p("date", "string", "YYYY-MM-DD."),
        p("startTime", "string", "24h HH:MM — only when the user gave a time."),
    )),
    ToolSpec("update_task", "Edit a task's name/estimate/area ONLY — it can NOT change the schedule; use schedule_task to move a task.", listOf("taskId"), listOf(
        p("taskId", "string", "Task id."), p("name", "string", "New title."), p("estimateMin", "integer", "Minutes."), p("lifeArea", "string", "Area name."),
    )),
    ToolSpec("set_task_later", "Park in Later or bring back.", listOf("taskId", "later"), listOf(
        p("taskId", "string", "Task id."), p("later", "boolean", "true=Later."),
    )),
    ToolSpec("set_task_recurrence", "Repeat a task or stop (kind=none).", listOf("taskId", "kind"), listOf(
        p("taskId", "string", "Task id."), p("kind", "string", "daily | weekly | monthly | none."),
        p("until", "string", "Optional end date YYYY-MM-DD."),
        arr("daysOfWeek", "integer", "Weekly: 0=Sun..6=Sat."),
    )),
    ToolSpec("complete_task", "Mark a task done.", listOf("taskId"), listOf(p("taskId", "string", "Task id."))),
    ToolSpec("create_tasks", "Create SEVERAL tasks in one call — always use this for a brain-dump of more than one item. Each may carry date+startTime to schedule it too.", listOf("tasks"), listOf(
        ToolProp("tasks", "array", "One entry per item the user mentioned.", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("estimateMin") { put("type", "integer") }
                putJsonObject("lifeArea") { put("type", "string") }
                putJsonObject("date") { put("type", "string"); put("description", "YYYY-MM-DD, only when the user gave a day.") }
                putJsonObject("startTime") { put("type", "string"); put("description", "24h HH:MM, only when the user gave a time.") }
            }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("name")) }
        }),
    )),
    ToolSpec("complete_tasks", "Mark SEVERAL tasks done in one call — always use this for \"all my tasks\" / \"everything\".", listOf("taskIds"), listOf(
        arr("taskIds", "string", "Every task id to complete."),
    )),
    ToolSpec("delete_task", "Delete a task — only after the user confirms aloud.", listOf("taskId"), listOf(p("taskId", "string", "Task id."))),
    ToolSpec("create_list", "Create a new list.", listOf("name"), listOf(p("name", "string", "List name."), p("color", "string", "Optional palette token."))),
    ToolSpec("add_to_list", "Add an item to a list.", listOf("listId", "body"), listOf(p("listId", "string", "List id."), p("body", "string", "Item text."))),
    ToolSpec("promote_item_to_task", "Turn a list item into a task.", listOf("listId", "itemId", "mode"), listOf(
        p("listId", "string", "List id."), p("itemId", "string", "Item id."), p("mode", "string", "self | loop."), p("dueAt", "string", "ISO 'by' time (loop)."),
    )),
    ToolSpec("save_profile_fact", "Remember a durable fact about the user (a person, rhythm, constraint, preference, or context). Short, third-person.", listOf("category", "fact"), listOf(
        p("category", "string", "person | rhythm | constraint | preference | context."),
        p("fact", "string", "One short sentence, e.g. \"Sam — partner, works night shifts\"."),
        p("whenIso", "string", "YYYY-MM-DD when the fact is about a date (birthday, show, deadline) — enables reminders."),
    )),
    ToolSpec("get_schedule", "Read the schedule before answering any what's-on question.", listOf("range"), listOf(p("range", "string", "today | tomorrow | week | next_week."))),
    ToolSpec("share_task", "Prepare sharing a task with someone in the user's trusted circle — stages a request they confirm on screen; never shares directly. Levels: view, partner, assign.", listOf("person"), listOf(
        p("taskId", "string", "Preferred: the task id."), p("taskName", "string", "Fallback when the id is unknown."),
        p("person", "string", "Who to share with, as the user named them."), p("level", "string", "view | partner | assign (default view)."),
    )),
    // ── full app surface (2026-09-02) ──
    ToolSpec("uncomplete_task", "Reopen a task that was marked done.", listOf("taskId"), listOf(p("taskId", "string", "Task id."))),
    ToolSpec("get_tasks", "List tasks by view — use before answering \"what is in my backlog / what did I finish\".", listOf("view"), listOf(
        p("view", "string", "today | upcoming | backlog | later | recurring | completed | slipping | all."),
        p("area", "string", "Optional life-area filter."), p("tag", "string", "Optional tag filter."),
    )),
    ToolSpec("unschedule_task", "Take a task OFF the calendar but keep it.", listOf("taskId"), listOf(p("taskId", "string", "Task id."))),
    ToolSpec("skip_occurrence", "Skip one day of a task ('not today') — the task and other days stay.", listOf("taskId"), listOf(p("taskId", "string", "Task id."), p("date", "string", "YYYY-MM-DD, default today."))),
    ToolSpec("complete_occurrence", "Mark just today's (or a given day's) instance of a recurring task done.", listOf("taskId"), listOf(p("taskId", "string", "Task id."), p("date", "string", "YYYY-MM-DD, default today."))),
    ToolSpec("block_time", "Block time on the calendar for a commitment (dentist, meeting).", listOf("name", "date", "startTime"), listOf(
        p("name", "string", "What it is."), p("date", "string", "YYYY-MM-DD."), p("startTime", "string", "HH:MM."), p("durationMin", "integer", "Minutes, default 60."),
    )),
    ToolSpec("carry_to_tomorrow", "Move today's unfinished scheduled tasks to tomorrow.", emptyList(), listOf(arr("taskIds", "string", "Optional subset; default all of today's unfinished."))),
    ToolSpec("start_focus", "Start a focus session on a task (opens the focus screen).", listOf("taskId"), listOf(p("taskId", "string", "Task id."), p("estimateMin", "integer", "Minutes, default the task estimate."))),
    ToolSpec("pause_focus", "Pause the running focus session.", emptyList(), emptyList()),
    ToolSpec("resume_focus", "Resume the paused focus session.", emptyList(), emptyList()),
    ToolSpec("extend_focus", "Add minutes to the running focus session.", listOf("minutes"), listOf(p("minutes", "integer", "Minutes to add."))),
    ToolSpec("cancel_focus", "Abandon the running focus session without logging it.", emptyList(), emptyList()),
    ToolSpec("add_capture", "Save a capture (a passing thought) to the inbox — 'capture', NOT 'captcha'.", listOf("body"), listOf(
        p("body", "string", "The thought, verbatim."), p("tag", "string", "follow-up | idea | edit | question | distraction (default idea)."), p("taskId", "string", "Optional task it belongs to."),
    )),
    ToolSpec("get_captures", "List open captures in the inbox.", emptyList(), listOf(p("tag", "string", "Optional tag filter."))),
    ToolSpec("get_lists", "Read the user's lists with their items and ids — use before answering \"what's in my lists\".", emptyList(), listOf(
        p("listId", "string", "Optional list id to read in full."), p("includeArchived", "boolean", "Include archived lists (default false)."),
    )),
    ToolSpec("promote_capture", "Turn a capture into a task.", listOf("captureId"), listOf(p("captureId", "string", "Capture id."))),
    ToolSpec("resolve_capture", "Mark a capture handled (leaves the inbox).", listOf("captureId"), listOf(p("captureId", "string", "Capture id."))),
    ToolSpec("delete_capture", "Delete a capture.", listOf("captureId"), listOf(p("captureId", "string", "Capture id."))),
    ToolSpec("rename_list", "Rename a list.", listOf("listId", "name"), listOf(p("listId", "string", "List id."), p("name", "string", "New name."))),
    ToolSpec("archive_list", "Archive (or unarchive) a list.", listOf("listId"), listOf(p("listId", "string", "List id."), p("archived", "boolean", "Default true."))),
    ToolSpec("delete_list", "Delete a list — only after the user confirms aloud.", listOf("listId"), listOf(p("listId", "string", "List id."))),
    ToolSpec("edit_list_item", "Change a list item's text.", listOf("listId", "itemId", "body"), listOf(p("listId", "string", "List id."), p("itemId", "string", "Item id."), p("body", "string", "New text."))),
    ToolSpec("remove_list_item", "Remove an item from a list.", listOf("listId", "itemId"), listOf(p("listId", "string", "List id."), p("itemId", "string", "Item id."))),
    ToolSpec("set_list_item_done", "Tick or untick a list item.", listOf("listId", "itemId"), listOf(p("listId", "string", "List id."), p("itemId", "string", "Item id."), p("done", "boolean", "Default true."))),
    ToolSpec("create_area", "Create a life area.", listOf("name"), listOf(p("name", "string", "Area name."), p("color", "string", "Optional palette token."))),
    ToolSpec("rename_area", "Rename a life area (tasks follow).", listOf("name", "newName"), listOf(p("name", "string", "Current name."), p("newName", "string", "New name."))),
    ToolSpec("delete_area", "Delete a life area — only after the user confirms.", listOf("name"), listOf(p("name", "string", "Area name."))),
    ToolSpec("create_tag", "Create a tag.", listOf("name"), listOf(p("name", "string", "Tag name."))),
    ToolSpec("rename_tag", "Rename a tag everywhere.", listOf("name", "newName"), listOf(p("name", "string", "Current name."), p("newName", "string", "New name."))),
    ToolSpec("delete_tag", "Delete a tag everywhere — only after the user confirms.", listOf("name"), listOf(p("name", "string", "Tag name."))),
    ToolSpec("unshare_task", "Stop sharing a task with someone.", listOf("taskId"), listOf(p("taskId", "string", "Task id."), p("person", "string", "Who, as the user named them."))),
    ToolSpec("set_usable_minutes", "Set how many minutes a day they have for focus.", emptyList(), listOf(p("weekdayMin", "integer", "Weekday minutes."), p("weekendMin", "integer", "Weekend-day minutes."))),
    ToolSpec("set_notification_level", "Set notification style.", listOf("level"), listOf(p("level", "string", "calm | balanced | coach."))),
    ToolSpec("set_reminder_lead", "How many minutes before a task to remind.", listOf("minutes"), listOf(p("minutes", "integer", "0 (off), 5, 10, or 15."))),
    ToolSpec("set_ritual", "Turn a recurring assistant moment on or off.", listOf("ritual"), listOf(p("ritual", "string", "morning | evening | friday | sunday."), p("on", "boolean", "Default true."))),
    ToolSpec("forget_fact", "Forget something you remembered about them.", emptyList(), listOf(p("factId", "string", "Fact id if known."), p("match", "string", "Or words from the fact."))),
    ToolSpec("get_insights", "How their focus is going — totals, estimate accuracy, why they pause, what is slipping.", emptyList(), listOf(p("window", "string", "week | month | all (default week)."))),
    ToolSpec("open_screen", "Open a screen in the app.", listOf("screen"), listOf(
        p("screen", "string", "today | tasks | calendar | week | month | focus | insights | lists | captures | settings | people | notifications."),
        p("id", "string", "Optional task/list id to open."),
    )),
    // ── calls ("Unstuck calls you") ──
    ToolSpec("request_call", "Book a phone call from Unstuck ONLY when the user asks for one (\"call me at 3 about James\"). Give when (standalone time) OR taskId with leadMin (rings before its slot). Notes are read back VERBATIM when the call opens — one item each. Never book unasked; you may offer one.", listOf("label"), listOf(
        p("when", "string", "Local 'YYYY-MM-DD HH:MM' — only when the user gave a time."),
        p("taskId", "string", "Task to ring before (uses its next scheduled slot)."),
        p("leadMin", "integer", "Minutes before the slot (default 15)."),
        p("label", "string", "What the call is about, in a few words: \"speak to James\"."),
        arr("notes", "string", "The user's reminders, verbatim, one per item."),
    )),
    ToolSpec("cancel_call", "Cancel a booked call.", listOf("callId"), listOf(p("callId", "string", "Call id from get_calls."))),
    ToolSpec("update_call", "Change a booked call's notes, time, or label.", listOf("callId"), listOf(
        p("callId", "string", "Call id from get_calls / request_call."), p("when", "string", "New local 'YYYY-MM-DD HH:MM'."),
        p("label", "string", "New label."), arr("notes", "string", "Replaces the notes, verbatim."),
    )),
    ToolSpec("get_calls", "List the calls booked from Unstuck (with ids).", emptyList(), emptyList()),
)

/** Every tool name the executor accepts. */
val ASSISTANT_TOOL_NAMES: Set<String> = ASSISTANT_TOOL_SPECS.map { it.name }.toSet()

/** Tool schemas for the realtime session (OpenAI/DashScope function shape —
 *  name/description/parameters at the top level). */
fun voiceToolsJson(specs: List<ToolSpec> = ASSISTANT_TOOL_SPECS): JsonArray = buildJsonArray {
    for (spec in specs) {
        add(buildJsonObject {
            put("type", "function")
            put("name", spec.name)
            put("description", spec.description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    for (prop in spec.props) {
                        putJsonObject(prop.name) {
                            put("type", prop.type)
                            put("description", prop.description)
                            prop.items?.let { put("items", it) }
                        }
                    }
                }
                putJsonArray("required") { spec.required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            }
        })
    }
}
