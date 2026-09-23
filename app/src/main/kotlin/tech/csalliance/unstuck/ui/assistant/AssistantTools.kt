package tech.csalliance.unstuck.ui.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.csalliance.unstuck.core.logic.CallSettingsLogic
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.ProfileFactsLogic
import tech.csalliance.unstuck.core.logic.WEEKDAY_NAMES_CAP
import tech.csalliance.unstuck.core.logic.hmToMin
import tech.csalliance.unstuck.core.logic.jsDayOfWeek
import tech.csalliance.unstuck.core.logic.rejectPastDate
import tech.csalliance.unstuck.core.logic.rejectPastTime
import tech.csalliance.unstuck.core.logic.ReceiptArgs
import tech.csalliance.unstuck.core.logic.ChosenDateAction
import tech.csalliance.unstuck.core.logic.RECURRENCE_HORIZON_DAYS
import tech.csalliance.unstuck.core.logic.RecurrenceStart
import tech.csalliance.unstuck.core.logic.RegenPlan
import tech.csalliance.unstuck.core.logic.applyCompletion
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.clampDurationMin
import tech.csalliance.unstuck.core.logic.clampEstimateMin
import tech.csalliance.unstuck.core.logic.clearLaterOnSchedule
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.materializeOccurrences
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.logic.recurrenceChosenDateAction
import tech.csalliance.unstuck.core.logic.recurrenceEditStart
import tech.csalliance.unstuck.core.logic.occurrencesCarryingTaskDone
import tech.csalliance.unstuck.core.logic.regenerateForTask
import tech.csalliance.unstuck.core.logic.resolveShareRequest
import tech.csalliance.unstuck.core.logic.taskAfterSettingRecurrence
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.sync.CallRequest
import tech.csalliance.unstuck.sync.CallsClient
import tech.csalliance.unstuck.sync.ProfileFactSaveError
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// Assistant tool executor — the CLIENT half of the shared assistant contract.
// The registry (lib/assistant/tool-registry.json → ToolRegistry.generated.kt)
// owns every tool's name / description / parameters; this file executes the
// calls through the same write paths the UI uses and answers in the contract's
// result style (`ok: …` / `error: …`) — the server prompt reads them. 1:1 port
// of lib/assistant/tools.ts `runAssistantTool` and iOS AssistantTools.swift
// (the base tools + the call tools live here; the app-surface tools in
// AssistantToolsSurface.kt).
//
// 2026-09-20 tooling rewrite (docs/assistant-tooling-rules.md §1): an `ok:` is
// returned ONLY after the seam confirmed the change; a no-op is `error: …
// nothing changed`; a partial result names, in one line the model can repeat,
// exactly what was and was not done (create_tasks past its cap, complete_tasks
// over done/unknown ids, carry_to_tomorrow's skipped-instead-of-moved). The
// audit that led here: "the model says it did something it didn't".
//
// Written against [AssistantApi] (not AppViewModel) so it runs unchanged in
// unit tests against an in-memory fake, and in the app against
// AppViewModelAssistantApi. Both the text harness and the realtime voice
// session dispatch through it.

/** Tools that never change anything — a success here must NOT count as "the
 *  assistant acted" for either fabrication guard (text or voice). The
 *  registry's read set, never a second copy. */
val READ_ONLY_TOOLS: Set<String> get() = ToolRegistry.READ_ONLY

/** create_tasks writes at most this many per call and NAMES every item past
 *  it (the old cap of 25 silently dropped the rest). */
const val MAX_CREATE_TASKS = 50

/** Entities created THIS turn/session, so a later call (schedule_task after
 *  create_task) can reference them by id before the optimistic write has
 *  propagated back through the store. */
class TurnScratch {
    val newTasks = HashMap<String, TaskItem>()
    val newLists = HashMap<String, ItemCollection>()
    /** Task id → the block schedule_task placed for it, which a
     *  set_task_recurrence after it takes as the series' day and time. */
    val placedBlocks = HashMap<String, String>()
    fun clear() { newTasks.clear(); newLists.clear(); placedBlocks.clear() }
}

/** Typed accessors over the model's JSON arguments. `str` treats blank as
 *  absent (matching the web helpers) and returns the raw string otherwise. */
class ToolArgs(val raw: JsonObject = JsonObject(emptyMap())) {
    companion object {
        fun parse(json: String): ToolArgs = ToolArgs(parseToolArgs(json))
    }

    val isEmpty: Boolean get() = raw.isEmpty()
    fun has(k: String): Boolean = raw.containsKey(k)
    fun isNull(k: String): Boolean = raw[k] is JsonNull

    fun str(k: String): String? = (raw[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    fun int(k: String): Int? {
        val p = raw[k] as? JsonPrimitive ?: return null
        if (p.isString) return p.content.trim().toIntOrNull()
        return p.intOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.let { Math.round(it).toInt() }
    }
    fun bool(k: String): Boolean? = (raw[k] as? JsonPrimitive)?.booleanOrNull
    fun strList(k: String): List<String>? = (raw[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
    fun intList(k: String): List<Int>? = (raw[k] as? JsonArray)?.mapNotNull { e ->
        (e as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.let { d -> Math.round(d).toInt() } }
    }
    fun objList(k: String): List<ToolArgs>? = (raw[k] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::ToolArgs) }

    /** The slice the receipt derivation reads. */
    val receiptArgs: ReceiptArgs
        get() = ReceiptArgs(taskId = str("taskId"), date = str("date"), startTime = str("startTime"), later = bool("later"), kind = str("kind"))
}

fun parseToolArgs(s: String): JsonObject =
    runCatching { Json.parseToJsonElement(s).jsonObject }.getOrDefault(JsonObject(emptyMap()))

// ── shared helpers (tools.ts module-private functions) ──

/** Resolve a task id: the committed store first, the turn's scratch copy only
 *  for a row the store doesn't have.
 *
 *  Store writes through [AssistantApi] are committed before they return, so the
 *  stored row is always at least as fresh as scratch — and scratch went STALE
 *  behind writes that never refresh it (scheduleTask's un-park and move-count
 *  bump, finish_focus, carry_to_tomorrow, an edit from the web). Every write
 *  tool upserts the whole row it gets back, and scratch lives for a whole Talk
 *  or call session, so a rename after "finish it" reopened the finished task
 *  and wiped its focus time (parity with iOS build 81, audit 2026-09-22 C5). */
suspend fun findTask(id: String?, api: AssistantApi, scratch: TurnScratch): TaskItem? {
    if (id == null) return null
    api.getTasks().firstOrNull { it.id == id }?.let { return it }
    return scratch.newTasks[id]
}

/** [findTask]'s rule for a whole list: the committed rows, plus scratch only for
 *  the ids the store lacks — a stale scratch copy (done=false after finish_focus
 *  completed it) listed a finished task as still open (parity with iOS build 81
 *  find_tasks, audit 2026-09-22 C5). */
fun storeFirst(stored: List<TaskItem>, scratch: TurnScratch): List<TaskItem> {
    val ids = stored.mapTo(HashSet()) { it.id }
    return stored + scratch.newTasks.values.filter { it.id !in ids }
}

suspend fun findList(id: String?, api: AssistantApi, scratch: TurnScratch): ItemCollection? {
    if (id == null) return null
    scratch.newLists[id]?.let { return it }
    return api.getCollections().firstOrNull { it.id == id }
}

/** The task's NEXT live block (today or later, not done/skipped), by date+time
 *  — the same anchor scheduleTask moves. */
fun nextLiveBlock(blocks: List<CalBlock>, today: String, taskId: String): CalBlock? =
    blocks.filter { it.taskId == taskId && !it.done && !it.skipped && it.date >= today }
        .sortedBy { it.date + it.startTime }
        .firstOrNull()

suspend fun nextLiveBlock(api: AssistantApi, taskId: String): CalBlock? = nextLiveBlock(api.getBlocks(), api.todayIso(), taskId)

/** Complete a task the way the UI's toggleDone does: completedAt stamped
 *  (applyCompletion) and the shared-list `done` sent. Only the done + completedAt
 *  delta is applied, to the COMMITTED row — a write in between (scheduleTask's
 *  un-park) must not be reverted by the caller's earlier copy — and a row the
 *  store already has done is returned as it is: no write, no second shared-list
 *  notice (parity with iOS build 81, audit 2026-09-22 C6). */
suspend fun markTaskDone(t: TaskItem, api: AssistantApi, scratch: TurnScratch): TaskItem {
    val prior = api.getTasks().firstOrNull { it.id == t.id } ?: t
    if (prior.done) return prior
    val stamped = applyCompletion(prior.copy(done = true), prior = prior, nowISO = api.nowIso())
    api.upsertTask(stamped)
    api.notifyTaskCompletedIfShared(stamped)
    scratch.newTasks[stamped.id] = stamped
    return stamped
}

/** Tick one day's occurrence block the way the UI's toggleDone does: un-skipped
 *  and completion-stamped, so the day counts as a done-today win (C6). */
suspend fun markOccurrenceDone(b: CalBlock, api: AssistantApi) {
    api.upsertBlock(b.copy(done = true, skipped = false, completedAt = api.nowIso()))
}

/** What [completeSeriesToday] did with a repeating task's day. */
enum class SeriesDayResult { TICKED, NOTHING_TODAY, ALREADY_DONE }

/** "I did X" for a repeating series ticks TODAY's occurrence — never the series.
 *  The model sees a series only by its TEMPLATE id (the context, get_tasks,
 *  find_tasks), and complete_task / complete_tasks set the template's done, which
 *  ENDS the series: every reminder stopped, the server's calls skip a done task,
 *  and today's row stayed open. The earliest open occurrence today is ticked the
 *  way the UI ticks one; the template is never written (parity with iOS build 81,
 *  audit 2026-09-22 C3). */
suspend fun completeSeriesToday(t: TaskItem, api: AssistantApi): SeriesDayResult {
    val today = api.todayIso()
    val day = api.getBlocks().filter { it.taskId == t.id && it.date == today && !it.skipped }
    if (day.isEmpty()) return SeriesDayResult.NOTHING_TODAY
    val open = day.filter { !it.done }.minByOrNull { it.startTime } ?: return SeriesDayResult.ALREADY_DONE
    markOccurrenceDone(open, api)
    return SeriesDayResult.TICKED
}

/** A task with this exact name (case- and space-insensitive), still open, made
 *  within the last [withinMs] — including one created earlier in THIS turn
 *  (parity with iOS build 79, 2c4b723). Judged on the committed rows only, so
 *  a task finished since never blocks a new one (audit 2026-09-22 C5). Unlike
 *  iOS there is no scratch fallback: [AssistantApi.upsertTask] commits to the
 *  store before it returns, so this turn's creations are already there, and a
 *  scratch-only id is a task deleted since (in the app or on the web, mid
 *  Talk/call session) — pointing the model at it would schedule a block for a
 *  task that no longer exists. */
suspend fun recentDuplicateTask(name: String, api: AssistantApi, nowMs: Long, withinMs: Long = 600_000L): TaskItem? {
    val key = name.trim().lowercase()
    if (key.isEmpty()) return null
    return api.getTasks().firstOrNull { t ->
        if (t.done || t.name.trim().lowercase() != key) return@firstOrNull false
        // Local rows stamp `…Z`, server rows `…+00:00` with microseconds.
        val made = Time.parseMillis(t.createdAt) ?: return@firstOrNull false
        kotlin.math.abs(nowMs - made) <= withinMs
    }
}

private suspend fun rejectPastDate(api: AssistantApi, date: String): String? = rejectPastDate(api.todayIso(), date)

private suspend fun rejectPastTime(api: AssistantApi, date: String, startTime: String?): String? =
    rejectPastTime(api.getBlocks(), api.todayIso(), date, startTime, api.nowHM())

private fun localMidnightMs(iso: String): Long =
    runCatching { LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        .getOrDefault(Time.startOfDayMillis(System.currentTimeMillis()))

/** Place (or move) the anchor block for a task at date+time, materialising the
 *  recurrence horizon when a repeating task is being placed. Returns the time
 *  the block landed on (callers report it honestly), or null when a repeating
 *  task's occurrence on `date` is already done — nothing was placed. The placed
 *  block is noted in `scratch.placedBlocks`. */
private suspend fun scheduleTask(api: AssistantApi, task: TaskItem, date: String, startTime: String?, scratch: TurnScratch): String? {
    val blocks = api.getBlocks()
    val today = api.todayIso()
    // Scheduling ends a task's "Later" parking — the same rule AppViewModel
    // .scheduleTaskNow applies to every in-app scheduling surface. Without it the
    // assistant could put a parked task on the calendar and leave it invisible to
    // Today, Backlog and Start-next, still reporting "Schedule: Later".
    clearLaterOnSchedule(task, api.nowIso())?.let { api.upsertTask(it) }
    // The anchor to move is the task's NEXT LIVE block — first-in-array grabbed
    // an old done/skipped occurrence on real accounts (tester round, 2026-09-01).
    // A repeating task moves its occurrence ON the target date: "move tomorrow's
    // gym to 7pm" took TODAY's occurrence to tomorrow, so today lost it and
    // tomorrow showed Gym twice (parity with iOS build 81, audit 2026-09-22 C1).
    val live = blocks.filter { it.taskId == task.id && !it.done && !it.skipped }.sortedBy { it.date + it.startTime }
    val anchor = (if (task.recurrence != null) live.firstOrNull { it.date == date } else null)
        ?: live.firstOrNull { it.date >= today } ?: live.lastOrNull()
    // Moving keeps the task's current time; a FIRST-EVER scheduling with no
    // time is refused upstream (the caller asks the user instead of guessing).
    val anchorTime = anchor?.startTime?.takeIf { it.isNotEmpty() }
    val time = startTime ?: anchorTime ?: "09:00"
    // A series with nothing live after today (a first placement, or one that
    // lapsed) is being placed, so the time given sets the series' time.
    val placesSeries = live.none { it.date > today }
    // The target day of a series: an occurrence already there is retimed (and
    // un-skipped), a done one leaves the day alone; only an empty day moves the
    // next occurrence onto it (parity with iOS build 81, audit 2026-09-22 C7).
    val action = if (task.recurrence == null) ChosenDateAction.Mint
        else recurrenceChosenDateAction(blocks.filter { it.taskId == task.id }, RegenPlan(emptyList(), emptyList()), date, time)
    when (action) {
        ChosenDateAction.Covered -> {
            val open = blocks.firstOrNull { it.taskId == task.id && isTaskBlock(it) && it.date == date && !it.done && !it.skipped }
                ?: return null
            scratch.placedBlocks[task.id] = open.id
        }
        is ChosenDateAction.Retime -> {
            val moved = action.block.copy(startTime = time, skipped = false)
            api.upsertBlock(moved)
            scratch.placedBlocks[task.id] = moved.id
        }
        ChosenDateAction.Mint -> if (anchor != null) {
            val moved = anchor.copy(date = date, startTime = time)
            api.upsertBlock(moved)
            scratch.placedBlocks[task.id] = moved.id
            // Every UI reschedule bumps move_count (the slip detector's input).
            if (anchor.date != date) {
                val fresh = api.getTasks().firstOrNull { it.id == task.id } ?: task
                api.upsertTask(bumpMoveCount(fresh, api.nowIso()))
            }
        } else {
            // Clamped to the server's 5…1440 (audit 2026-09-22, C4) — also enforced
            // in WriteThrough; here so the receipts match what is stored.
            val placed = CalBlock(id = newUuid(), taskId = task.id, taskName = task.name, startTime = time,
                durationMinutes = clampDurationMin(task.estimateMin), date = date, kind = CalBlockKind.TASK)
            api.upsertBlock(placed)
            scratch.placedBlocks[task.id] = placed.id
        }
    }
    val rec = task.recurrence
    if (rec != null && placesSeries) {
        // Only a series being PLACED is materialised, from the placed date at the
        // placed time up to the horizon, on dates that don't already carry one of
        // its blocks. Refilling a live series from the moved date at the moved
        // time brought back occurrences the user had deleted and stretched the
        // series at a one-off time (parity with iOS build 81, audit 2026-09-22 C1).
        val taken = blocks.filter { it.taskId == task.id }.map { it.date }.toSet()
        val lastIso = IsoDate.addDays(today, RECURRENCE_HORIZON_DAYS - 1)
        for (occ in materializeOccurrences(rec, localMidnightMs(date), time, IsoDate.daysUntil(date, lastIso) + 1)) {
            if (occ.date <= date || occ.date in taken) continue
            api.upsertBlock(CalBlock(id = newUuid(), taskId = task.id, taskName = task.name, startTime = occ.startTime,
                durationMinutes = clampDurationMin(task.estimateMin), date = occ.date, kind = CalBlockKind.TASK))
        }
    }
    return time
}

/** Read tool: the schedule for a range, as text the model can quote from. */
private suspend fun renderSchedule(api: AssistantApi, range: String): String {
    val today = api.todayIso()
    val monday = IsoDate.mondayOf(today)
    val (from, to) = when (range) {   // inclusive from, exclusive to
        "today" -> today to tech.csalliance.unstuck.core.logic.addDaysIso(today, 1)
        "tomorrow" -> tech.csalliance.unstuck.core.logic.addDaysIso(today, 1) to tech.csalliance.unstuck.core.logic.addDaysIso(today, 2)
        "next_week" -> tech.csalliance.unstuck.core.logic.addDaysIso(monday, 7) to tech.csalliance.unstuck.core.logic.addDaysIso(monday, 14)
        else -> monday to tech.csalliance.unstuck.core.logic.addDaysIso(monday, 7)
    }
    val tasks = api.getTasks().associateBy { it.id }
    val blocks = api.getBlocks().filter { it.date >= from && it.date < to }.sortedBy { it.date + it.startTime }
    val lines = ArrayList<String>()
    var d = from
    while (d < to) {
        val day = WEEKDAY_NAMES_CAP[jsDayOfWeek(d)]
        val items = blocks.filter { it.date == d }.map { b ->
            val t = b.taskId?.let { tasks[it] }
            val done = if (t?.done == true || b.done) " (done)" else if (b.skipped) " (skipped)" else ""
            val ext = if (b.kind == CalBlockKind.EXTERNAL) " [calendar event — not movable here]" else ""
            val name = b.taskName.ifEmpty { t?.name ?: "?" }
            "${b.startTime.ifEmpty { "anytime" }} $name$done$ext"
        }
        lines += "$day $d${if (d == today) " (TODAY)" else ""}: ${if (items.isEmpty()) "—" else items.joinToString("; ")}"
        d = tech.csalliance.unstuck.core.logic.addDaysIso(d, 1)
    }
    return "ok:\n" + lines.joinToString("\n")
}

// ── the executor ──

/** Execute one tool call. Returns a short result string the model reads on the
 *  next turn ("ok: created task id=… name=…" / "error: …"). Never throws for a
 *  bad argument — the harness turns a thrown executor into `error: …`. */
suspend fun runAssistantTool(name: String, args: ToolArgs, api: AssistantApi, scratch: TurnScratch): String {
    runCoreTool(name, args, api, scratch)?.let { return it }
    runSurfaceTool(name, args, api, scratch)?.let { return it }
    runCallTool(name, args, api, scratch)?.let { return it }
    return unknownToolResult(name)
}

/** Every tool the executor knows is exactly the registry (ToolRegistryParityTest
 *  pins both directions), so an unknown-tool result names the real options —
 *  the model picks one next round instead of guessing again (three narrated
 *  guesses at a list-reading tool, tester round 2026-09-06). Wording from
 *  docs/assistant-tooling-rules.md §1, the same on web + iOS. */
fun unknownToolResult(name: String): String =
    "error: unknown tool \"$name\". The tools are: ${ToolRegistry.NAMES.joinToString(", ")}"

/** Tags as the task stores them: trimmed, blanks dropped, case-insensitively
 *  unique, null when nothing is left (the row's own convention). */
private fun cleanTags(raw: List<String>?): List<String>? =
    raw?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinctBy { it.lowercase() }?.ifEmpty { null }

/** "none" (or a JSON null) clears an optional text field — the registry's
 *  convention for update_task's lifeArea / firstPhysicalAction / dueAt. */
private fun ToolArgs.clearableStr(k: String): Pair<Boolean, String?> {
    val v = str(k)
    val cleared = isNull(k) || v == null || v.equals("none", ignoreCase = true)
    return cleared to (if (cleared) null else v)
}

/** The base (pre-2026-09-02) tools: tasks, schedule, lists, profile, sharing. */
private suspend fun runCoreTool(name: String, args: ToolArgs, api: AssistantApi, scratch: TurnScratch): String? {
    val now = api::nowIso

    return when (name) {
        "create_task" -> {
            val nm = args.str("name") ?: return "error: name required"
            val date = args.str("date")
            val startTime = args.str("startTime")
            if (startTime != null && date == null) return "error: startTime needs a date — give date (YYYY-MM-DD) as well"
            if (date != null) {
                rejectPastDate(api, date)?.let { return it }
                if (startTime != null) rejectPastTime(api, date, startTime)?.let { return it }
            }
            // A task by this exact name made minutes ago is almost certainly the
            // same one, not a second one. A tester ended up with FOUR identical
            // "Office" tasks: the model could not see the task it had just made,
            // so a nudge to "call the right tool now" made another (audit
            // 2026-09-21). Point the model at the existing one instead (parity
            // with iOS build 79, 2c4b723; create_tasks stays unguarded, as there).
            recentDuplicateTask(nm, api, api.nowMs())?.let { dupe ->
                return "error: \"${dupe.name}\" already exists (id=${dupe.id}, created just now) — use schedule_task or update_task on it rather than making another. Only create a second one if the user asks for a separate task."
            }
            val later = args.bool("later") ?: false
            val t = TaskItem(
                id = newUuid(), name = nm, estimateMin = clampEstimateMin(args.int("estimateMin")), totalFocused = 0, done = false,
                tags = cleanTags(args.strList("tags")), lifeArea = args.str("lifeArea"),
                firstPhysicalAction = args.str("firstPhysicalAction"),
                // A dated task is on the calendar, not parked — the two exclude each other.
                later = later && date == null,
                dueAt = args.str("dueAt"), createdAt = now(), updatedAt = now(),
            )
            api.upsertTask(t)
            scratch.newTasks[t.id] = t
            val sb = StringBuilder("ok: created task id=${t.id} name=\"${t.name}\"")
            when {
                date != null && startTime != null -> {
                    val landed = scheduleTask(api, t, date, startTime, scratch) ?: startTime
                    sb.append(" — scheduled $date $landed")
                    if (later) sb.append(" (not parked in Later: it has a date)")
                }
                // A day WITHOUT a time: never invent 09:00 — create it unscheduled and ask.
                date != null -> sb.append(". NOTE: it has a day ($date) but no time — left unscheduled. Ask ONE question suggesting a time, then schedule_task it.")
                later -> sb.append(" — parked in Later")
            }
            sb.toString()
        }

        "schedule_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val date = args.str("date") ?: return "error: date required"
            val startTime = args.str("startTime")
            rejectPastDate(api, date)?.let { return it }
            // No time given AND the task has never had one: don't guess — ask,
            // suggesting a slot (Ahmad, 2026-09-01: "when confused, prompt").
            val own = api.getBlocks().firstOrNull { it.taskId == t.id && !it.done && !it.skipped && it.startTime.isNotEmpty() }
            if (startTime == null && own == null) {
                return "error: needs a time — \"${t.name}\" has no time yet and the user gave none. Do NOT pick one: ask ONE short question offering a suggestion (e.g. \"Friday — 9am, or a time you prefer?\"), then schedule when they answer."
            }
            rejectPastTime(api, date, startTime ?: own?.startTime)?.let { return it }
            val landed = scheduleTask(api, t, date, startTime, scratch)
                ?: return "error: \"${t.name}\" is already done on $date — nothing changed"
            "ok: scheduled \"${t.name}\" $date $landed${if (startTime == null) " (kept its existing time — say so)" else ""}"
        }

        "update_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            // Scheduling args used to be SILENTLY dropped while still returning ok
            // (harness audit, 2026-09-01). Refuse loudly instead.
            if (args.has("date") || args.has("startTime") || args.has("scheduledDate") || args.has("scheduledTime")) {
                return "error: update_task cannot change the schedule — use schedule_task(taskId, date, startTime?) instead"
            }
            // Only what actually changes is written, and the result names it —
            // an "ok: updated" over a no-op call read as a change (rules §1).
            val changed = ArrayList<String>()
            var upd = t
            args.str("name")?.let { if (it != t.name) { upd = upd.copy(name = it); changed += "name" } }
            // Clamped BEFORE the no-op test (audit 2026-09-22, C4): the store holds
            // 1…1440, so "5000" twice was two "updated" receipts over one stored value.
            args.int("estimateMin")?.let(::clampEstimateMin)?.let { if (it != t.estimateMin) { upd = upd.copy(estimateMin = it); changed += "estimate ${it}m" } }
            if (args.has("lifeArea")) {
                val (cleared, v) = args.clearableStr("lifeArea")
                if (v != t.lifeArea) { upd = upd.copy(lifeArea = v); changed += if (cleared) "area cleared" else "area $v" }
            }
            if (args.has("tags")) {
                val v = cleanTags(args.strList("tags"))
                if (v != t.tags) { upd = upd.copy(tags = v); changed += if (v == null) "tags cleared" else "tags ${v.joinToString(", ")}" }
            }
            if (args.has("firstPhysicalAction")) {
                val (cleared, v) = args.clearableStr("firstPhysicalAction")
                if (v != t.firstPhysicalAction) { upd = upd.copy(firstPhysicalAction = v); changed += if (cleared) "first step cleared" else "first step" }
            }
            if (args.has("dueAt")) {
                // "make that due Friday" was unreachable (inventory 2026-09-02)
                val (cleared, v) = args.clearableStr("dueAt")
                if (v != t.dueAt) { upd = upd.copy(dueAt = v); changed += if (cleared) "deadline cleared" else "due $v" }
            }
            args.bool("later")?.let { if (it != (t.later ?: false)) { upd = upd.copy(later = it); changed += if (it) "parked in Later" else "back from Later" } }
            if (changed.isEmpty()) return "error: nothing to change on \"${t.name}\" — every field given already has that value"
            upd = upd.copy(updatedAt = now())
            api.upsertTask(upd)
            scratch.newTasks[upd.id] = upd
            // A new estimate resizes the live block, like the calendar editor does.
            if (upd.estimateMin != t.estimateMin) {
                nextLiveBlock(api, t.id)?.let { api.upsertBlock(it.copy(durationMinutes = clampDurationMin(upd.estimateMin))) }
            }
            "ok: updated \"${upd.name}\" — ${changed.joinToString(", ")}"
        }

        "set_task_later" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val wantLater = args.bool("later") ?: true
            // Never ok: for a no-op — the receipt ("Moved to Later") would describe
            // a change that didn't happen (web parity).
            val isLater = t.later ?: false
            if (wantLater && isLater) return "error: \"${t.name}\" is already in Later — nothing changed"
            if (!wantLater && !isLater) return "error: \"${t.name}\" is not in Later — nothing changed"
            val upd = t.copy(later = wantLater, updatedAt = now())
            api.upsertTask(upd)
            scratch.newTasks[t.id] = upd
            if (wantLater) "ok: parked \"${t.name}\" in Later" else "ok: brought \"${t.name}\" back from Later"
        }

        "set_task_recurrence" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            val kind = args.str("kind")?.lowercase() ?: return "error: kind required — daily, weekly, monthly, or none"
            // F1: an unrecognized kind used to silently CLEAR the recurrence and report ok.
            if (kind !in listOf("daily", "weekly", "monthly", "none")) {
                return "error: unknown recurrence kind \"$kind\" — use daily, weekly, monthly, or none"
            }
            val until = args.str("until")
            val days = args.intList("daysOfWeek")?.distinct()?.sorted()
            if (kind == "weekly") {
                // Weekly with no days generated NOTHING and still said ok (rules §1).
                if (days.isNullOrEmpty()) return "error: weekly needs daysOfWeek (0=Sunday … 6=Saturday) — ask which days"
                if (days.any { it !in 0..6 }) return "error: daysOfWeek must be 0=Sunday … 6=Saturday"
            }
            if (kind == "none" && t.recurrence == null) return "error: \"${t.name}\" doesn't repeat — nothing changed"
            val rec: Recurrence? = when (kind) {
                "daily" -> Recurrence.Daily(until)
                "weekly" -> Recurrence.Weekly(days ?: emptyList(), until)
                "monthly" -> Recurrence.Monthly(until)
                else -> null
            }
            // The editor's rule (AppViewModel.setRecurrence): "stop repeating" carries
            // a ticked today onto the task, and a repeat turned on never leaves a
            // DONE template — an ended series (parity with iOS build 81, audit
            // 2026-09-22 C3).
            val upd = taskAfterSettingRecurrence(t, rec, api.getBlocks(), api.todayIso(), now()).copy(updatedAt = now())
            api.upsertTask(upd)
            scratch.newTasks[t.id] = upd
            // Regenerate future blocks off the existing anchor, if scheduled.
            val blocks = api.getBlocks()
            val today = api.todayIso()
            // An occurrence schedule_task placed earlier in this turn sets the
            // series' day and time: "make Office every Monday at 11" on a series at
            // 09:15 is schedule_task(Mon, 11:00) then this call, and the vote below
            // would keep 09:15 and put the new 11:00 back while replying ok.
            val placed = scratch.placedBlocks[t.id]?.let { id ->
                blocks.firstOrNull { it.id == id && !it.done && !it.skipped && it.startTime.isNotEmpty() && it.date >= today }
            }
            // Otherwise the earliest LIVE timed block — never the oldest block of any
            // kind, which rebuilt the series at a history time and, on a series
            // started 56+ days ago, deleted its whole future — at the series' own
            // time and day, never a one-off moved occurrence's (parity with iOS
            // builds 79 and 81, audit 2026-09-22 B79.1 C1).
            val start = placed?.let { RecurrenceStart(it.date, it.startTime, RECURRENCE_HORIZON_DAYS) }
                ?: recurrenceEditStart(t.id, rec, blocks, today)
            if (start != null) {
                val plan = regenerateForTask(upd, rec, blocks, today, start.startTime, localMidnightMs(start.date), start.horizonDays)
                for (b in plan.toUpsert) api.upsertBlock(b)
                // This month's occurrence moved later off a series day that has
                // passed stays (see RecurrenceStart.keepId).
                for (id in plan.toDelete) if (id != start.keepId) api.deleteBlock(id)
            }
            // A done task made to repeat keeps the day it was done ticked, and the
            // done flip reaches a loop-promoted task's shared-list row — as in the editor.
            for (b in occurrencesCarryingTaskDone(t, rec, blocks, api.todayIso(), now())) api.upsertBlock(b)
            if (t.done != upd.done) {
                if (upd.done) api.notifyTaskCompletedIfShared(upd) else api.notifyTaskReopenedIfShared(upd)
            }
            val todays = api.getBlocks().filter { it.taskId == t.id && isTaskBlock(it) && it.date == api.todayIso() && !it.skipped }
            val doneNote = when {
                !t.done && upd.done -> " — today's occurrence was already done, so the task is now marked done"
                // Never "open again" over a day that stays ticked: the user would be
                // told to do today's again.
                t.done && !upd.done ->
                    if (todays.isNotEmpty() && todays.all { it.done }) " (it was done — today's occurrence stays done)" else " (it was done — now open again)"
                else -> ""
            }
            // Only a TIMED block anchors a series: a timeless one used to read as
            // "regenerated" over a rule that materialised nothing (audit 2026-09-22, C7).
            val anchored = start != null
            // Worded as on web (lib/assistant/tools.ts) and iOS build 81.
            if (rec == null) {
                "ok: \"${t.name}\" no longer repeats${if (anchored) " (future occurrences removed)" else ""}$doneNote"
            } else {
                val how = when (kind) {
                    "weekly" -> "weekly on " + (days ?: emptyList()).joinToString(", ") { WEEKDAY_NAMES_CAP[it].take(3) }
                    else -> kind
                }
                // The time the series now runs at, so the reply can't claim a
                // re-time that didn't happen (audit 2026-09-22, C1).
                "ok: \"${t.name}\" now repeats $how${start?.let { " at ${it.startTime}" } ?: ""}${if (until != null) " until $until" else ""}$doneNote" +
                    if (anchored) "" else " — it has no calendar slot yet; schedule_task it to place the first one"
            }
        }

        "complete_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            // A repeating series: today's occurrence, never the series (C3). The
            // result is complete_occurrence's line, with no id= — so no Undo, whose
            // reopen/complete would land on the series itself.
            if (t.recurrence != null) {
                val day = api.todayIso()
                return when (completeSeriesToday(t, api)) {
                    SeriesDayResult.TICKED -> "ok: marked \"${t.name}\" done for $day (series continues)"
                    SeriesDayResult.ALREADY_DONE -> "error: \"${t.name}\" is already done on $day — nothing changed"
                    SeriesDayResult.NOTHING_TODAY -> "error: \"${t.name}\" repeats and has nothing on $day — nothing changed. complete_task only ticks TODAY's occurrence of a repeating task and never ends the series; use complete_occurrence with the day, or set_task_recurrence kind none to stop it repeating"
                }
            }
            // Already done → error, not ok: an "ok: completed" receipt's Undo would
            // REOPEN something the user finished earlier (web parity).
            if (t.done) return "error: \"${t.name}\" is already done — nothing changed"
            // Stamped + the shared-list notice, like the UI's tick (C6).
            markTaskDone(t, api, scratch)
            // F8: id in the result — the receipt's undo must target THIS task.
            "ok: completed \"${t.name}\" id=${t.id}"
        }

        "create_tasks" -> {
            // Bulk brain-dump — ten spoken tasks must land as ONE call. Past the
            // cap, every item is NAMED as not created (the old cap dropped them
            // and reported the count that fit).
            val items = args.objList("tasks")?.takeIf { it.isNotEmpty() } ?: return "error: tasks required"
            val made = ArrayList<Pair<String, String>>()
            val needsTime = ArrayList<String>()
            val notCreated = ArrayList<String>()
            var nameless = 0
            for (it in items) {
                val nm = it.str("name")
                if (nm == null) { nameless += 1; continue }
                if (made.size >= MAX_CREATE_TASKS) { notCreated += "\"$nm\""; continue }
                val date = it.str("date")
                val startTime = it.str("startTime")
                val later = it.bool("later") ?: false
                val t = TaskItem(id = newUuid(), name = nm, estimateMin = clampEstimateMin(it.int("estimateMin")), totalFocused = 0, done = false,
                    tags = cleanTags(it.strList("tags")), lifeArea = it.str("lifeArea"), firstPhysicalAction = it.str("firstPhysicalAction"),
                    later = later && date == null, dueAt = it.str("dueAt"), createdAt = now(), updatedAt = now())
                api.upsertTask(t)
                scratch.newTasks[t.id] = t
                // Date + time → schedule. Date WITHOUT time → do NOT invent 09:00:
                // create it unscheduled and tell the model to ask ONE question.
                val past = date?.let { d -> rejectPastDate(api, d) ?: rejectPastTime(api, d, startTime) }
                when {
                    past != null -> needsTime += "\"${t.name}\" — " + past.removePrefix("error: ")
                    date != null && startTime != null -> scheduleTask(api, t, date, startTime, scratch)
                    date != null -> needsTime += "\"${t.name}\" ($date)"
                }
                made += t.id to t.name
            }
            if (made.isEmpty()) return "error: no valid tasks in the list — every entry needs a name"
            val partial = notCreated.isNotEmpty() || nameless > 0
            val sb = StringBuilder(
                "ok: created ${made.size}${if (partial) " of ${items.size}" else ""} tasks ids=${made.joinToString(",") { it.first }} — ${made.joinToString(", ") { "\"${it.second}\"" }}",
            )
            if (notCreated.isNotEmpty()) sb.append(" — not created: ${notCreated.joinToString(", ")} (limit $MAX_CREATE_TASKS per call — call create_tasks again for them)")
            if (nameless > 0) sb.append(" — not created: $nameless item${if (nameless == 1) "" else "s"} with no name")
            sb.append(".")
            if (needsTime.isNotEmpty()) {
                sb.append(" NOTE: ${needsTime.joinToString(", ")} ${if (needsTime.size == 1) "has" else "have"} a day but no time — left unscheduled. Ask ONE question suggesting a time for them, then schedule_task each.")
            }
            sb.toString()
        }

        "complete_tasks" -> {
            // Bulk close — "close all my tasks" must be ONE reliable call, and
            // the result says which were NOT done and why (rules §1).
            val ids = args.strList("taskIds")?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
            if (ids.isEmpty()) return "error: taskIds required"
            // Report only the ids we ACTUALLY flipped — the receipt's undo re-opens exactly these.
            val flipped = ArrayList<Pair<String, String>>()
            // Repeating series whose TODAY was ticked (C3) — named, but kept out of
            // ids=, so the receipt's Undo reopens only plain tasks, never a series.
            val ticked = ArrayList<String>()
            val skipped = ArrayList<String>()
            for (id in ids) {
                val t = findTask(id, api, scratch)
                when {
                    t == null -> skipped += "\"$id\" (not found)"
                    t.recurrence != null -> when (completeSeriesToday(t, api)) {
                        SeriesDayResult.TICKED -> ticked += t.name
                        SeriesDayResult.ALREADY_DONE -> skipped += "\"${t.name}\" (already done today)"
                        SeriesDayResult.NOTHING_TODAY -> skipped += "\"${t.name}\" (repeats — nothing today)"
                    }
                    t.done -> skipped += "\"${t.name}\" (already done)"
                    else -> {
                        // Stamped + the shared-list notice, like the UI's tick (C6).
                        markTaskDone(t, api, scratch)
                        flipped += t.id to t.name
                    }
                }
            }
            if (flipped.isEmpty() && ticked.isEmpty()) return "error: none completed — ${skipped.joinToString(", ")}"
            val names = flipped.map { "\"${it.second}\"" } + ticked.map { "\"$it\" (today — series continues)" }
            "ok: completed ${flipped.size + ticked.size} tasks ids=${flipped.joinToString(",") { it.first }} — ${names.joinToString(", ")}" +
                if (skipped.isEmpty()) "" else " — not done: ${skipped.joinToString(", ")}"
        }

        "delete_task" -> {
            val t = findTask(args.str("taskId"), api, scratch) ?: return "error: task not found"
            for (b in api.getBlocks().filter { it.taskId == t.id }) api.deleteBlock(b.id)
            // Mirror the UI: a deleted task takes its captures with it (else orphans).
            for (c in api.getCaptures().filter { it.taskId == t.id }) api.removeCapture(c.id)
            api.removeTask(t.id)
            scratch.newTasks.remove(t.id)
            "ok: deleted \"${t.name}\""
        }

        "create_list" -> {
            val nm = args.str("name") ?: return "error: name required"
            val colors = RegistryTools.enumOf("create_list", "color")
            val color = args.str("color")?.lowercase() ?: "indigo"
            if (colors.isNotEmpty() && color !in colors) return "error: unknown colour \"$color\" — use ${colors.joinToString(", ")}"
            val id = api.addCollection(nm, color) ?: return "error: could not create list"
            scratch.newLists[id] = ItemCollection(id = id, name = nm.trim(), color = color, subtitle = null, items = emptyList(), sortOrder = 0)
            "ok: created list id=$id name=\"${nm.trim()}\""
        }

        "add_to_list" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            if (scratch.newLists[c.id] == null && !api.canEditCollection(c.id)) {
                return "error: you only have view access to \"${c.name}\" — can't add to it"
            }
            val body = args.str("body") ?: return "error: body required"
            val id = api.addCollectionItem(c.id, body) ?: return "error: couldn't add to \"${c.name}\" — try again"
            "ok: added \"${body.trim()}\" to \"${c.name}\" id=$id"
        }

        "promote_item_to_task" -> {
            val c = findList(args.str("listId"), api, scratch) ?: return "error: list not found"
            val itemId = args.str("itemId")
            val item = c.items.firstOrNull { it.id == itemId } ?: return "error: item not found"
            // The list UI skips an item whose task is already in flight — saying
            // "ok: promoted" over that no-op would be a lie.
            if (item.promoted == true && item.promotedDone != true) {
                return "error: \"${item.body}\" is already promoted — its task is still in flight"
            }
            val shared = c.members.isNotEmpty() || c.myRole == "editor" || c.myRole == "viewer"
            val wantLoop = args.str("mode") == "loop"
            val loop = wantLoop && shared
            val dueAt = if (loop) args.str("dueAt") else null
            val taskId = api.promoteItemToTask(c.id, item.id, loop, dueAt)
                ?: return "error: couldn't promote \"${item.body}\" — get_lists and try again"
            // The loop → self downgrade on an unshared list is SAID, not silent.
            val how = when {
                loop -> " — the list's members are in the loop${if (dueAt != null) " (by $dueAt)" else ""}"
                wantLoop -> " — this list isn't shared, so it is just their task (loop mode not applied)"
                else -> " (just theirs)"
            }
            "ok: promoted \"${item.body}\" to a task id=$taskId$how"
        }

        "save_profile_fact" -> {
            val fact = args.str("fact") ?: return "error: fact required"
            // The injection filter guards MODEL-written saves — a planted
            // instruction here would live in every future prompt.
            if (ProfileFactsLogic.isInstructionLike(fact)) {
                return "error: that does not look like a fact I can store — only durable notes about you, not instructions"
            }
            try {
                val stored = api.saveProfileFact(args.str("category"), fact, args.str("whenIso"))
                "ok: remembered id=${stored.id} [${stored.category.raw}] \"${stored.fact}\""
            } catch (e: ProfileFactSaveError.Empty) {
                "error: that does not look like a fact I can store — only durable notes about you, not instructions"
            } catch (e: ProfileFactSaveError.InstructionLike) {
                "error: that does not look like a fact I can store — only durable notes about you, not instructions"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A store failure (or no store yet) is NOT "not a fact" — the model
                // should retry, not rephrase.
                "error: couldn't save that just now — try again"
            }
        }

        "get_schedule" -> renderSchedule(api, args.str("range") ?: "week")

        "share_task" -> {
            // NEVER shares here: sharing sends the user's content to another
            // person, so it always waits for an on-screen confirm tap.
            val res = resolveShareRequest(
                taskId = args.str("taskId"), taskName = args.str("taskName"),
                person = args.str("person"), level = args.str("level"),
                tasks = storeFirst(api.getTasks(), scratch), people = api.getShareCandidates(), newId = ::newUuid,
            )
            res.pending?.let { api.stageShare(it) }
            res.message
        }

        else -> null
    }
}

// ── calls ("Unstuck calls you") — request_call / cancel_call / update_call / get_calls ──
// Result strings are the WEB contract byte-for-byte (lib/assistant/tools.ts +
// docs/assistant-tool-contract.md); the past-date / past-time refusals are the
// SHARED strings (rejectPastDate / rejectPastTime). Port of iOS CallTools.swift.

object CallToolLogic {
    val names: Set<String> = setOf("request_call", "cancel_call", "update_call", "get_calls")
    const val MAX_NOTES = 20
    const val MAX_NOTE_LENGTH = 300
    const val DEFAULT_LEAD_MIN = 15
    private const val WINDOW_START_MIN = 6 * 60
    private const val WINDOW_END_MIN = 23 * 60

    /** Compare-and-set miss: the row was cancelled / rang / finished between the read and the write. */
    const val CHANGED_UNDERNEATH = "error: that call changed underneath me — get_calls and try again"
    const val IN_PROGRESS = "error: that call is in progress right now — I can change its notes or label, but not its time; snooze_call or book another with request_call"
    const val UNAVAILABLE = "error: calls aren't available right now — sign in on the phone first"
    const val NETWORK = "error: couldn't reach the server — try again"

    /** notes: an array of strings, or one string split on NEWLINES only (a
     *  note may contain ";"). Trimmed, blanks dropped, 300 chars × 20 (web). */
    fun notes(args: ToolArgs, k: String): List<String> {
        val raw: List<String> = args.strList(k) ?: args.str(k)?.lines() ?: emptyList()
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(MAX_NOTE_LENGTH) }.take(MAX_NOTES)
    }

    fun notesCount(n: Int): String = "$n note${if (n == 1) "" else "s"}"

    /** schedule_task-style split args → "YYYY-MM-DD HH:MM". */
    fun joinDateTime(args: ToolArgs): String? {
        val time = args.str("startTime") ?: args.str("time")
        val date = args.str("date")
        return when {
            date != null && time != null -> "$date $time"
            date == null && time != null -> time
            else -> null
        }
    }

    /** 'YYYY-MM-DD HH:MM' (or with a 'T'), a bare 'HH:MM' (today), or an ISO
     *  instant — in the user's LOCAL time → epoch ms. Null when unparseable. */
    fun parseWhen(raw: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val s = raw.trim()
        Regex("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2})$").find(s)?.let { m ->
            return localToMs(m.groupValues[1], m.groupValues[2], zone)
        }
        Regex("^(\\d{2}:\\d{2})$").find(s)?.let { m ->
            return localToMs(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString(), m.groupValues[1], zone)
        }
        return Time.parseMillis(s)
    }

    private fun localToMs(date: String, hm: String, zone: ZoneId): Long? = runCatching {
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.parse(hm)).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** "YYYY-MM-DD" local. */
    fun ymd(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    /** "HH:MM" local. */
    fun hhmm(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
    /** "YYYY-MM-DD HH:MM" local. */
    fun fmt(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): String = if (ms == null) "?" else "${ymd(ms, zone)} ${hhmm(ms, zone)}"

    fun blockStartMs(b: CalBlock, zone: ZoneId = ZoneId.systemDefault()): Long? =
        if (b.startTime.isBlank()) null else localToMs(b.date, b.startTime, zone)

    /** Past-date / past-time (the SHARED refusals) then the server window →
     *  the error string, or null when fine. */
    fun timeGuard(callAtMs: Long, today: String, nowHM: String, blocks: List<CalBlock>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val date = ymd(callAtMs, zone)
        rejectPastDate(today, date)?.let { return it }
        rejectPastTime(blocks, today, date, hhmm(callAtMs, zone), nowHM)?.let { return it }
        val min = hmToMin(hhmm(callAtMs, zone))
        if (min < WINDOW_START_MIN || min > WINDOW_END_MIN) {
            return "error: calls can only be booked between 06:00 and 23:00 — suggest a time inside that window"
        }
        return null
    }

    /** One live call per anchor — the web rule exactly: with a task, any live
     *  call for that task; without, a case-insensitive label match. */
    fun duplicate(live: List<CallRequest>, taskId: String?, label: String?): CallRequest? {
        val rows = live.filter { it.isLive }
        if (taskId != null) return rows.firstOrNull { it.taskId == taskId }
        val l = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return rows.firstOrNull { it.label.trim().lowercase() == l }
    }

    /** get_calls (web format): one line per call — soonest first, ≤20 lines —
     *  showing the EFFECTIVE time, the task it rings for, and its state. */
    fun formatCalls(rows: List<CallRequest>, taskName: (String) -> String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (rows.isEmpty()) return "ok: no calls booked"
        val lines = rows.take(20).map { r ->
            val sb = StringBuilder("- ${fmt(r.effectiveAtMs, zone)} \"${r.label}\" (${notesCount(r.notes.size)})")
            r.taskId?.let(taskName)?.let { sb.append(" for \"$it\"") }
            if (r.status == "snoozed") sb.append(" · snoozed") else if (r.status == "calling") sb.append(" · ringing now")
            sb.append(" [id=${r.id}]").toString()
        }
        return "ok: ${rows.size} upcoming call${if (rows.size == 1) "" else "s"}:\n" + lines.joinToString("\n")
    }
}

// ── snooze_call — the CALL-LEVEL tool ("call me back in ten") ──
// Voice-only, call mode only (iOS CallTools.names includes it; CallScript.callTools
// lists it). CallVoiceService owns the ACTION — it intercepts the name BEFORE the
// executor (iOS RealtimeCallVoiceLauncher.runCallTool → deps.snooze), hangs up
// and reports outcome `snoozed` + minutes ONCE — and answers the model with
// [ok]. The executor only ever sees it OUTSIDE a call (text chat / plain Talk),
// where the honest answer is [NO_ACTIVE] (iOS CallCoordinator.snoozeActiveCall
// with no active call). Strings are the iOS contract byte-for-byte.
object SnoozeCallTool {
    const val NAME = "snooze_call"
    const val DEFAULT_MINUTES = 10
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 180

    /** No call is up (or it is still ringing) — iOS `snoozeActiveCall` guard. */
    const val NO_ACTIVE = "error: no call is active"
    /** The session is gone by the time the tool ran (iOS launcher). */
    const val CALL_ENDED = "error: the call has ended"

    /** The tool result the model reads: it says the minutes, then a quick goodbye;
     *  the service ends the call (iOS CallCoordinator.snoozeActiveCall). */
    fun ok(minutes: Int): String = "ok: I'll call back in ${clamp(minutes)} minutes — say a quick goodbye; the call ends now"

    /** A tool outside `CallScript.callTools` asked for during a call. */
    fun notAvailableDuringCall(name: String): String = "error: $name isn't available during a call"

    /** iOS clampSnooze: 1…180. */
    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** `{minutes}` from the model's arguments; default 10 (iOS snoozeMinutes). */
    fun minutes(args: ToolArgs): Int = args.int("minutes") ?: DEFAULT_MINUTES
    fun minutes(argsJson: String): Int = minutes(ToolArgs.parse(argsJson))
}

/** What a receipt's Undo control should read: "Undo", or — while a NETWORK undo
 *  (CANCEL_CALL, the request_call receipt's cancel round-trip) is in flight —
 *  "cancelling…" so the tap isn't repeated and the row isn't lied about. The
 *  in-flight set is [tech.csalliance.unstuck.ui.AppViewModel.receiptUndosInFlight],
 *  keyed [receiptUndoKey]. */
fun receiptUndoLabel(kind: tech.csalliance.unstuck.core.logic.ReceiptUndoKind, inFlight: Boolean): String =
    if (inFlight && kind == tech.csalliance.unstuck.core.logic.ReceiptUndoKind.CANCEL_CALL) "cancelling…" else "Undo"

/** The in-flight key for one receipt on one persisted turn. */
fun receiptUndoKey(messageId: String, index: Int): String = "$messageId:$index"

/** Dispatch a call tool through the executor's state + scratch. Null ⇒ not a call tool. */
suspend fun runCallTool(name: String, args: ToolArgs, api: AssistantApi, scratch: TurnScratch): String? {
    // Outside a call session there is nothing to snooze (the service answers
    // it in call mode before we're reached).
    if (name == SnoozeCallTool.NAME) return SnoozeCallTool.NO_ACTIVE
    if (name !in CallToolLogic.names) return null
    val store = api.callStore() ?: return CallToolLogic.UNAVAILABLE
    val userId = api.currentUserId() ?: return CallToolLogic.UNAVAILABLE
    return try {
        when (name) {
            "request_call" -> requestCall(args, api, scratch, store, userId)
            "cancel_call" -> cancelCall(args, store)
            "update_call" -> updateCall(args, api, store)
            else -> getCalls(api, scratch, store)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        CallToolLogic.NETWORK
    }
}

private suspend fun requestCall(args: ToolArgs, api: AssistantApi, scratch: TurnScratch, store: AssistantCallStore, userId: String): String {
    val nowMs = api.nowMs()
    val taskId = args.str("taskId")
    val whenRaw = args.str("when") ?: CallToolLogic.joinDateTime(args)
    val notes = CallToolLogic.notes(args, "notes")
    var label = args.str("label")?.take(120)

    var task: TaskItem? = null
    if (taskId != null) {
        val t = findTask(taskId, api, scratch) ?: return "error: task not found"
        task = t
        if (label == null) label = t.name.take(120)
    }
    if (label.isNullOrEmpty()) return "error: label required — say what the call is about (e.g. \"speak to James\")"

    val callAt: Long
    var blockId: String? = null
    var leadMin: Int? = null
    if (whenRaw != null) {
        callAt = CallToolLogic.parseWhen(whenRaw, nowMs)
            ?: return "error: when must be 'YYYY-MM-DD HH:MM' in the user's local time (got \"$whenRaw\")"
    } else if (task != null) {
        val lead = (args.int("leadMin") ?: CallToolLogic.DEFAULT_LEAD_MIN).coerceIn(0, 1440)
        val block = nextLiveBlock(api, task.id)
        val start = block?.let { CallToolLogic.blockStartMs(it) }
        if (block == null || start == null) return "error: \"${task.name}\" has no upcoming slot — schedule_task it first, or give a time with when"
        callAt = start - lead * 60_000L
        if (callAt - nowMs < -30_000L) {
            return "error: $lead min before \"${task.name}\" (${CallToolLogic.fmt(callAt)}) is already past — give a time with when instead"
        }
        blockId = block.id
        leadMin = lead
    } else {
        return "error: needs a time — ask ONE short question suggesting one (e.g. \"3pm today, or a time you prefer?\"), then book when they answer"
    }

    CallToolLogic.timeGuard(callAt, api.todayIso(), api.nowHM(), api.getBlocks())?.let { return it }
    // This phone declines on receipt outside its switch + hours — refuse here
    // rather than answer "ok: call booked" (parity with iOS build 81, audit 2026-09-22 C12).
    CallSettingsLogic.deviceGuard(callAt, api.callSettings())?.let { return it }

    val live = store.liveCalls()
    CallToolLogic.duplicate(live, task?.id, label)?.let { dup ->
        return "error: a call is already booked for \"${dup.label}\" at ${CallToolLogic.fmt(dup.callAtMs ?: callAt)} id=${dup.id} — update_call or cancel_call it"
    }
    val row = store.book(userId, task?.id, blockId, callAt, leadMin, label, notes)
    return "ok: call booked ${CallToolLogic.fmt(callAt)} \"$label\" (${CallToolLogic.notesCount(notes.size)}) id=${row.id}"
}

private suspend fun cancelCall(args: ToolArgs, store: AssistantCallStore): String {
    val id = args.str("callId") ?: args.str("id") ?: return "error: callId required — use get_calls to find it"
    val row = store.call(id) ?: return "error: call not found — use get_calls"
    if (!row.isLive) return "error: that call is already ${row.status}"
    val cancelled = store.cancelCall(id) ?: return CallToolLogic.CHANGED_UNDERNEATH
    return "ok: cancelled the call about \"${cancelled.label}\" (${CallToolLogic.fmt(cancelled.callAtMs)})"
}

private suspend fun updateCall(args: ToolArgs, api: AssistantApi, store: AssistantCallStore): String {
    val nowMs = api.nowMs()
    val id = args.str("callId") ?: args.str("id") ?: return "error: callId required — use get_calls to find it"
    val row = store.call(id) ?: return "error: call not found — use get_calls"
    if (!row.isEditable) return "error: that call is already ${row.status} — book a new one with request_call"
    // A JSON null or an absent key leaves the notes untouched; only a real array/string replaces them.
    val notes: List<String>? = if (args.has("notes") && !args.isNull("notes")) CallToolLogic.notes(args, "notes") else null
    val label = args.str("label")?.take(120)
    val whenRaw = args.str("when") ?: CallToolLogic.joinDateTime(args)
    val lead = args.int("leadMin")
    if (notes == null && label == null && whenRaw == null && lead == null) return "error: nothing to change — give notes and/or when"
    if (row.isInProgress && (whenRaw != null || lead != null)) return CallToolLogic.IN_PROGRESS

    var callAt: Long? = null
    var leadPatch: CallsClient.Patch<Int?>? = null
    var blockPatch: CallsClient.Patch<String?>? = null
    if (whenRaw != null) {
        callAt = CallToolLogic.parseWhen(whenRaw, nowMs)
            ?: return "error: when must be 'YYYY-MM-DD HH:MM' in the user's local time (got \"$whenRaw\")"
        leadPatch = CallsClient.Patch(null)    // a standalone time drops the block anchor
        blockPatch = CallsClient.Patch(null)
    } else if (lead != null) {
        val taskId = row.taskId ?: return "error: this call isn't anchored to a task — give a time instead"
        val block = nextLiveBlock(api, taskId)
        val start = block?.let { CallToolLogic.blockStartMs(it) }
        if (block == null || start == null) return "error: the task has no scheduled time any more — schedule_task it first"
        callAt = start - lead * 60_000L
        leadPatch = CallsClient.Patch(lead)
        blockPatch = CallsClient.Patch(block.id)
    }
    if (callAt != null) CallToolLogic.timeGuard(callAt, api.todayIso(), api.nowHM(), api.getBlocks())?.let { return it }
    // Only a NEW time meets this phone's switch and hours; a notes- or
    // label-only edit is never refused here (iOS build 81, audit 2026-09-22 C12).
    // The task editor sends its lead with every Update, so a lead that lands on
    // the time the row already has is no new time either — with Calls off
    // here, a notes-only edit from the editor was refused.
    if (callAt != null && callAt != row.callAtMs) CallSettingsLogic.deviceGuard(callAt, api.callSettings())?.let { return it }

    val r = store.patch(id, callAt, blockPatch, leadPatch, label, notes) ?: return CallToolLogic.CHANGED_UNDERNEATH
    return "ok: updated call \"${r.label}\" — ${CallToolLogic.fmt(r.callAtMs)}, ${CallToolLogic.notesCount(r.notes.size)} id=${r.id}"
}

private suspend fun getCalls(api: AssistantApi, scratch: TurnScratch, store: AssistantCallStore): String {
    val rows = store.liveCalls().sortedBy { it.callAtMs ?: Long.MAX_VALUE }
    if (rows.isEmpty()) return "ok: no calls booked"
    val tasks = storeFirst(api.getTasks(), scratch)
    return CallToolLogic.formatCalls(rows, taskName = { id -> tasks.firstOrNull { it.id == id }?.name })
}
