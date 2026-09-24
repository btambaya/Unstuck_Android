package tech.csalliance.unstuck.soak

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.csalliance.unstuck.core.logic.PendingShare
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.logic.ShareCandidate
import tech.csalliance.unstuck.core.logic.assistantDayLabel
import tech.csalliance.unstuck.core.logic.assistantModelWindow
import tech.csalliance.unstuck.core.logic.assistantPersistWindow
import tech.csalliance.unstuck.core.logic.busyMinutesByDay
import tech.csalliance.unstuck.core.logic.isCompletedToday
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.isTemplate
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.sync.ToolCall
import tech.csalliance.unstuck.sync.ToolFunction
import tech.csalliance.unstuck.ui.assistant.AssistantApi
import tech.csalliance.unstuck.ui.assistant.AssistantSettingsSnapshot
import tech.csalliance.unstuck.ui.assistant.AssistantCallStore
import tech.csalliance.unstuck.ui.assistant.CirclePerson
import tech.csalliance.unstuck.ui.assistant.GatewayInputs
import tech.csalliance.unstuck.ui.assistant.TaskShareInfo
import tech.csalliance.unstuck.ui.assistant.buildAssistantContext
import tech.csalliance.unstuck.ui.assistant.deriveGateway
import tech.csalliance.unstuck.ui.assistant.nextLiveBlockByTask
import tech.csalliance.unstuck.ui.assistant.topByDescendingStable
import tech.csalliance.unstuck.ui.assistant.visibleAssistantTurns
import tech.csalliance.unstuck.ui.calendar.layoutLanes
import tech.csalliance.unstuck.ui.today.weekPill
import java.time.Instant

/**
 * SOAK — the :app derivations that run on Today, Calendar and the Assistant
 * sheet, against the heavy account ([SoakSeed]). Pure JVM (no Robolectric
 * runner needed: none of these touch the Android framework).
 */
class SoakAppPerfTest {

    private val tasks = SoakSeed.tasks()
    private val blocks = SoakSeed.blocks(tasks)
    private val sessions = SoakSeed.sessions(tasks)
    private val captures = SoakSeed.captures()
    private val collections = SoakSeed.collections()
    private val today = SoakSeed.today
    private val now = System.currentTimeMillis()

    // ── gateway card ────────────────────────────────────────────────────────

    private fun inputs() = GatewayInputs(
        tasks = tasks, blocks = blocks, sessions = sessions, reasons = emptyList(), facts = emptyList(),
        struggles = listOf("Starting", "Switching"), rituals = RitualPrefs.DEFAULTS, dismissed = emptySet(),
        todayIso = today, minute = GatewayInputs.minute(now),
    )

    @Test fun soak_gateway_card() {
        val key = inputs()
        Bench.run("deriveGateway (brief + pickMoment)") { deriveGateway(key, now) }
        // The memo's key equality: what a minute tick / any re-emit pays BEFORE
        // deciding whether to recompute. Distinct-instance lists (a fresh decode).
        val other = GatewayInputs(
            tasks = tasks.map { it.copy() }, blocks = blocks.map { it.copy() }, sessions = sessions.map { it.copy() },
            reasons = emptyList(), facts = emptyList(), struggles = listOf("Starting", "Switching"),
            rituals = RitualPrefs.DEFAULTS, dismissed = emptySet(), todayIso = today, minute = GatewayInputs.minute(now),
        )
        Bench.run("GatewayInputs.equals (memo key, distinct instances)", iters = 100) { key == other }
    }

    // ── Today screen: every remember{} body, in order ────────────────────────

    @Test fun soak_today_screen_derivation() {
        val open = Bench.run("Today: visibleTasks(TODAY)") { visibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false) }
        val todayOpen = visibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false)
        Bench.run("Today: todayDone (filter + projectOccurrences)") {
            (tasks.filter { !isTemplate(it) } + projectOccurrences(tasks, blocks, today))
                .filter { isCompletedToday(it, now) && todayOpen.none { o -> o.id == it.id } }
        }
        val backlog = Bench.run("Today: visibleTasks(BACKLOG)") { visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, null, null, false) }
        Bench.run("Today: weekMin roll-up over sessions") {
            sessions.filter { (now - (java.time.Instant.parse(it.completedAt).toEpochMilli())) in 0..(7L * 86_400_000) }.sumOf { it.actualSec } / 60
        }
        Bench.run("Today: WHOLE screen derivation (all of the above)") {
            val o = visibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false)
            val d = (tasks.filter { !isTemplate(it) } + projectOccurrences(tasks, blocks, today))
                .filter { isCompletedToday(it, now) && o.none { x -> x.id == it.id } }
            val b = visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, null, null, false)
            o.size + d.size + b.size
        }
        println("PERF | (todayOpen=${todayOpen.size}, backlog rows=${visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, null, null, false).size}) open-bench=${open.medianMs}")
    }

    // ── calendar lanes ──────────────────────────────────────────────────────

    @Test fun soak_calendar_lanes() {
        val live = blocks.filter { !it.skipped }
        val days = (0..6).map { SoakSeed.dayIso(it) }
        Bench.run("Calendar: blocks.filter{!skipped} (4000)", iters = 50) { blocks.filter { !it.skipped } }
        val todayBlocks = live.filter { it.date == today }
        println("PERF | blocks on today = ${todayBlocks.size}; live blocks = ${live.size}")
        Bench.run("Calendar DAY: layoutLanes(one day)", iters = 100) { layoutLanes(live.filter { it.date == today }) }
        Bench.run("Calendar WEEK: laidByDay (7 x filter + layoutLanes)", iters = 50) {
            days.associate { iso -> iso to layoutLanes(live.filter { it.date == iso }) }
        }
        Bench.run("Calendar WEEK: plannedByDay (7 x filter + sum)", iters = 50) {
            days.map { d -> live.filter { it.date == d && isTaskBlock(it) }.sumOf { it.durationMinutes } }
        }
        Bench.run("Calendar MONTH: busyMinutesByDay(4000)", iters = 50) { busyMinutesByDay(blocks, emptyList()) }
        Bench.run("Calendar MONTH: ownPlannedDays set (4000)", iters = 50) {
            blocks.filter { isTaskBlock(it) && !it.skipped }.map { it.date }.toSet()
        }
        Bench.run("Calendar DAY: scheduledIds + unscheduled tray", iters = 50) {
            val ids = live.filter { isTaskBlock(it) }.mapNotNull { it.taskId }.toSet()
            tasks.filter { !it.done && it.later != true && it.recurrence == null && it.id !in ids }
        }
    }

    // ── assistant: 200-turn thread + context build ──────────────────────────

    private fun thread(n: Int = 200): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(n)
        var at = now - n * 3_600_000L
        for (i in 0 until n) {
            at += 3_600_000L
            when (i % 4) {
                0 -> out += ChatMessage(role = "user", content = "Can you plan my afternoon around the gym? (turn $i)", id = "m$i", at = at)
                1 -> out += ChatMessage(
                    role = "assistant", content = "Let me look at your schedule.",
                    toolCalls = listOf(ToolCall("call$i", "function", ToolFunction("get_tasks", "{\"view\":\"today\"}"))),
                    id = "m$i", at = at,
                )
                2 -> out += ChatMessage(role = "tool", content = "ok: 14 tasks", toolCallId = "call${i - 1}", name = "get_tasks", id = "m$i", at = at)
                else -> out += ChatMessage(role = "assistant", content = "Booked — Thursday at two, forty-five minutes.", id = "m$i", at = at)
            }
        }
        return out
    }

    @Test fun soak_assistant_thread() {
        val history = thread(200)
        println("PERF | thread turns = ${history.size}, visible = ${visibleAssistantTurns(history).size}")
        Bench.run("AssistantSheet: visibleAssistantTurns(200) [per recomposition]", iters = 200) { visibleAssistantTurns(history) }
        val display = visibleAssistantTurns(history)
        Bench.run("AssistantSheet: rows build (dividers + receipts)", iters = 200) {
            val rows = ArrayList<Any>()
            var lastLabel: String? = null
            display.forEach { m ->
                val label = assistantDayLabel(m.at, now)
                if (label != null && label != lastLabel) { rows.add(label); lastLabel = label }
                rows.add(m)
            }
            rows
        }
        Bench.run("assistantModelWindow(200 -> 40)", iters = 200) { assistantModelWindow(history) }
        Bench.run("persistAssistant: Json.encodeToString(200 turns)", iters = 50) {
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), assistantPersistWindow(history))
        }
        val encoded = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), assistantPersistWindow(history))
        println("PERF | persisted thread JSON = ${encoded.length} chars")
        Bench.run("assistant history: decode 200 turns (cold start)", iters = 50) {
            Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), encoded)
        }
    }

    @Test fun soak_assistant_context() {
        val api = SoakApi(tasks, blocks, sessions, captures, collections)
        Bench.run("buildAssistantContext (per assistant turn)", iters = 20) { runBlocking { buildAssistantContext(api) } }
        val json = runBlocking { buildAssistantContext(api) }.toString()
        println("PERF | assistant context payload = ${json.length} chars")
    }

    // ── a stand-in for the app's AssistantApi (reads only) ──────────────────

    internal class SoakApi(
        val tasksL: List<TaskItem>, val blocksL: List<CalBlock>, val sessionsL: List<Session>,
        val capturesL: List<Capture>, val collectionsL: List<ItemCollection>,
    ) : AssistantApi {
        override suspend fun getTasks() = tasksL
        override suspend fun getBlocks() = blocksL
        override suspend fun getCollections() = collectionsL
        override suspend fun getAreaRows(): List<LifeArea> = SoakSeed.areas()
        override suspend fun getTagRows(): List<TagRow> = SoakSeed.tags()
        override fun currentUserName() = "Maya"
        override fun todayIso() = SoakSeed.today
        override fun nowHM() = "09:30"
        override fun nowMs() = System.currentTimeMillis()
        override fun nowIso(): String = Instant.ofEpochMilli(nowMs()).toString()
        override suspend fun upsertTask(t: TaskItem) = Unit
        override suspend fun removeTask(id: String) = Unit
        override suspend fun notifyTaskReopenedIfShared(t: TaskItem) = Unit
        override suspend fun notifyTaskCompletedIfShared(t: TaskItem) = Unit
        override suspend fun upsertBlock(b: CalBlock) = Unit
        override suspend fun insertBlockIfAbsent(b: CalBlock, retimeIfTaken: Boolean) = false
        override suspend fun deleteBlock(id: String) = Unit
        override fun getTaskReminder(taskId: String): Int? = null
        override fun setTaskReminder(taskId: String, minutes: Int?) = false
        override suspend fun addCollection(name: String, color: String): String? = null
        override suspend fun addCollectionItem(collectionId: String, body: String): String? = null
        override suspend fun promoteItemToTask(collectionId: String, itemId: String, loop: Boolean, dueAt: String?): String? = null
        override suspend fun renameCollection(id: String, name: String) = false
        override suspend fun updateCollection(id: String, archived: Boolean?, color: String?) = false
        override suspend fun removeCollection(id: String) = false
        override suspend fun updateCollectionItem(collectionId: String, itemId: String, body: String?, done: Boolean?) = false
        override suspend fun setCollectionItemPinned(collectionId: String, itemId: String, pinned: Boolean) = false
        override suspend fun removeCollectionItem(collectionId: String, itemId: String) = false
        override suspend fun leaveCollection(id: String) = false
        override suspend fun canEditCollection(id: String) = true
        override suspend fun isCollectionOwner(id: String) = true
        override fun getShareCandidates(): List<ShareCandidate> = emptyList()
        override fun stageShare(p: PendingShare) = Unit
        override fun getCirclePeople(): List<CirclePerson> = listOf(CirclePerson("A", "active"), CirclePerson("B", "pending"))
        override suspend fun listTaskShares(taskId: String): List<TaskShareInfo> = emptyList()
        override suspend fun unshareTask(shareId: String) = true
        override suspend fun getProfileFacts(): List<ProfileFact> = emptyList()
        override suspend fun saveProfileFact(category: String?, fact: String, whenIso: String?): ProfileFact = throw UnsupportedOperationException()
        override suspend fun removeProfileFact(id: String) = false
        override suspend fun getSessions(): List<Session> = sessionsL
        override suspend fun getReasonLogs(): List<ReasonLog> = emptyList()
        override fun getStruggles(): List<String> = listOf("Starting")
        override suspend fun getCaptures(): List<Capture> = capturesL
        override fun getArchivedCaptureIds(): Set<String> = emptySet()
        override suspend fun upsertCapture(c: Capture) = Unit
        override suspend fun removeCapture(id: String) = Unit
        override fun archiveCapture(id: String, archived: Boolean) = false
        override suspend fun getLiveFocus(): LiveSession? = null
        override suspend fun startFocus(taskId: String, estimateMin: Int?, occurrenceBlockId: String?) = false
        override suspend fun pauseFocus() = false
        override suspend fun resumeFocus() = false
        override suspend fun extendFocus(minutes: Int) = false
        override suspend fun finishFocus(markDone: Boolean) = false
        override suspend fun cancelFocus() = false
        override fun navigate(screen: String, id: String?) = Unit
        override suspend fun addArea(name: String, color: String?) = false
        override suspend fun updateArea(id: String, name: String?, color: String?) = false
        override suspend fun removeArea(id: String) = false
        override suspend fun addTag(name: String) = false
        override suspend fun updateTag(id: String, name: String?) = false
        override suspend fun removeTag(id: String) = false
        override fun getSettings() = AssistantSettingsSnapshot("balanced", 10, null, null, 25, 5, true, true, "system", "off", emptyMap())
        override suspend fun setUsableMinutes(weekday: Int?, weekend: Int?) = true
        override suspend fun setNotificationLevel(level: String) = true
        override suspend fun setReminderLead(minutes: Int) = true
        override fun setRitual(ritual: String, on: Boolean) = false
        override fun setTheme(theme: String) = false
        override fun setAmbientSound(sound: String) = false
        override fun setFocusDefaults(defaultMinutes: Int?, overrunMinutes: Int?, softExit: Boolean?, pauseReasons: Boolean?) = false
        override fun currentUserId(): String? = "me"
        override fun callStore(): AssistantCallStore? = null
    }

    @Test fun soak_assistant_context_breakdown() {
        val today = SoakSeed.today
        val nowMs = System.currentTimeMillis()
        Bench.run("ctx: blocks.sortedBy{date+startTime} (4000)", iters = 40) {
            blocks.sortedBy { it.date + it.startTime }
        }
        Bench.run("ctx: blocksByTask next-live map (sort + scan)", iters = 40) {
            val m = HashMap<String, CalBlock>()
            for (b in blocks.sortedBy { it.date + it.startTime }) {
                val tid = b.taskId?.takeIf { it.isNotEmpty() } ?: continue
                if (b.done || b.skipped || b.date < today) continue
                if (m[tid] == null) m[tid] = b
            }
            m
        }
        Bench.run("ctx: derivePatterns(800,4000)", iters = 40) {
            tech.csalliance.unstuck.core.logic.derivePatterns(tasks, blocks, today)
        }
        val pats = tech.csalliance.unstuck.core.logic.derivePatterns(tasks, blocks, today)
        Bench.run("ctx: patternGaps", iters = 40) { tech.csalliance.unstuck.core.logic.patternGaps(pats, blocks, today) }
        Bench.run("ctx: goldenHours(1500 sessions)", iters = 40) { tech.csalliance.unstuck.core.logic.goldenHours(sessions, nowMs) }
        Bench.run("ctx: freeWindowsToday(4000)", iters = 40) { tech.csalliance.unstuck.core.logic.freeWindowsToday(blocks, today, "09:30") }
        val weekFrom = tech.csalliance.unstuck.core.logic.IsoDate.mondayOf(today)
        val weekTo = tech.csalliance.unstuck.core.logic.addDaysIso(weekFrom, 7)
        val week = blocks.filter { it.date >= weekFrom && it.date < weekTo }.sortedBy { it.date + it.startTime }.take(60)
        println("PERF | week blocks = ${week.size}")
        Bench.run("ctx: week name lookup (60 x tasks.firstOrNull over 800)", iters = 100) {
            week.map { b -> b.taskId?.let { id -> tasks.firstOrNull { it.id == id } }?.name }
        }
        Bench.run("ctx: captures sortedByDescending + take(12)", iters = 100) {
            captures.sortedByDescending { it.at }.take(12)
        }
    }

    /**
     * SAME-RUN A/B for the perf-soak fixes (2026-09-12) — cross-run medians of
     * these micro-benches are too noisy to compare, so BEFORE (the expression
     * each change replaced, verbatim) is timed against AFTER in one process.
     * AssistantContextParityTest / WeekFocusMinutesTest assert the two agree.
     */
    @Test fun soak_ab_optimisations() {
        // 1. next-live block per task: full sort vs one min-pass.
        Bench.run("A/B ctx next-live map BEFORE: sort 4000 + scan", iters = 40) {
            val m = HashMap<String, CalBlock>()
            for (b in blocks.sortedBy { it.date + it.startTime }) {
                val tid = b.taskId?.takeIf { it.isNotEmpty() } ?: continue
                if (b.done || b.skipped || b.date < today) continue
                if (m[tid] == null) m[tid] = b
            }
            m
        }
        Bench.run("A/B ctx next-live map AFTER : one min-pass", iters = 40) { nextLiveBlockByTask(blocks, today) }

        // 2. newest-12 captures: sort-then-truncate vs stable selection.
        Bench.run("A/B ctx captures BEFORE: sortedByDescending.take(12)", iters = 100) {
            captures.sortedByDescending { it.at }.take(12)
        }
        Bench.run("A/B ctx captures AFTER : top-12 selection", iters = 100) {
            topByDescendingStable(captures, 12) { it.at }
        }

        // 3. week[].name + live focus: firstOrNull per row vs a task map.
        val weekFrom = tech.csalliance.unstuck.core.logic.IsoDate.mondayOf(today)
        val week = blocks.filter { it.date >= weekFrom && it.date < tech.csalliance.unstuck.core.logic.addDaysIso(weekFrom, 7) }
            .sortedBy { it.date + it.startTime }.take(60)
        Bench.run("A/B ctx week names BEFORE: firstOrNull over 800/row", iters = 100) {
            week.map { b -> b.taskId?.let { id -> tasks.firstOrNull { it.id == id } }?.name }
        }
        Bench.run("A/B ctx week names AFTER : task map", iters = 100) {
            val byId = HashMap<String, TaskItem>()
            for (t in tasks) if (!byId.containsKey(t.id)) byId[t.id] = t
            week.map { b -> b.taskId?.let { byId[it] }?.name }
        }

        // 4. the Today header's 7-day focus roll-up, per minute tick.
        val completedMs = sessions.map { tech.csalliance.unstuck.core.time.Time.parseMillis(it.completedAt) ?: 0L }
        Bench.run("A/B weekMin BEFORE: parse every session per tick") {
            sessions.filter { (now - (tech.csalliance.unstuck.core.time.Time.parseMillis(it.completedAt) ?: 0)) in 0..(7L * 86_400_000) }
                .sumOf { it.actualSec } / 60
        }
        val pillData = tech.csalliance.unstuck.core.logic.PeriodData(emptyList(), emptyList(), sessions, emptyList(), emptyList())
        Bench.run("A/B weekMin AFTER : periodFacts week pill per tick (${completedMs.size} sessions)") { weekPill(pillData, now) }

        // 5. the whole context build.
        val api = SoakApi(tasks, blocks, sessions, captures, collections)
        Bench.run("A/B buildAssistantContext AFTER (per turn)", iters = 20) { runBlocking { buildAssistantContext(api) } }
    }

}
