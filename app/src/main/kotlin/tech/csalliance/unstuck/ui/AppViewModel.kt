package tech.csalliance.unstuck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.applyCompletion
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.sync.AuthOutcome
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// The single app-wide state holder. Exposes every synced collection as a
// reactive StateFlow off the Room store, the auth state, and every write
// action (which apply the :core mutation rules then go through the sync
// engine's WriteThrough). Screens compose these with :core (visibleTasks /
// pickStartNext / analytics) in memory — same model as web + iOS.
class AppViewModel(private val graph: AppGraph) : ViewModel() {

    private val store = graph.store
    private val write get() = graph.coordinator?.write
    val auth get() = graph.coordinator?.auth

    private fun <T> sf(flow: kotlinx.coroutines.flow.Flow<List<T>>): StateFlow<List<T>> =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks = sf(store.tasks())
    val blocks = sf(store.blocks())
    val sessions = sf(store.sessions())
    val captures = sf(store.captures())
    val reasonLogs = sf(store.reasonLogs())
    val collections = sf(store.collections())
    val tags = sf(store.tags())
    val lifeAreas = sf(store.lifeAreas())
    val connections = sf(store.connections())
    val liveSession: StateFlow<LiveSession?> =
        store.liveSession().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pendingCount = store.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val configured = graph.configured

    /** null until the auth state resolves; true/false once known. */
    val authed: StateFlow<Boolean?> = run {
        val client = graph.provider?.client
        if (client == null) MutableStateFlow<Boolean?>(false)
        else client.auth.sessionStatus
            .map { it is SessionStatus.Authenticated }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    // --- helpers ---

    fun nowMs(): Long = System.currentTimeMillis()
    fun isoNow(): String = ISO.format(Instant.now())

    private fun launchWrite(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    // --- tasks ---

    fun addTask(
        name: String,
        estimateMin: Int = 25,
        priority: Priority? = null,
        lifeArea: String? = null,
        tags: List<String>? = null,
        intentWhen: String? = null,
        intentThen: String? = null,
        firstPhysicalAction: String? = null,
        recurrence: Recurrence? = null,
        later: Boolean = false,
    ): TaskItem {
        val now = isoNow()
        val t = TaskItem(
            id = newUuid(), name = name.trim(), estimateMin = estimateMin, priority = priority,
            lifeArea = lifeArea, tags = tags, intentWhen = intentWhen, intentThen = intentThen,
            firstPhysicalAction = firstPhysicalAction, recurrence = recurrence, later = later,
            createdAt = now, updatedAt = now,
        )
        launchWrite { write?.upsertTask(t) }
        return t
    }

    fun updateTask(task: TaskItem) = launchWrite { write?.upsertTask(task.copy(updatedAt = isoNow())) }

    fun toggleDone(task: TaskItem) = launchWrite {
        val flipped = task.copy(done = !task.done)
        write?.upsertTask(applyCompletion(flipped, prior = task, nowISO = isoNow()))
    }

    fun setLater(task: TaskItem, later: Boolean) = launchWrite {
        write?.upsertTask(task.copy(later = later, updatedAt = isoNow()))
    }

    /** Delete a task and cascade to its cal_blocks + captures (so realtime
     *  listeners don't pull orphans back), mirroring the web deleteTask. */
    fun deleteTask(id: String) = launchWrite {
        blocks.value.filter { it.taskId == id }.forEach { write?.deleteCalBlock(it.id) }
        captures.value.filter { it.taskId == id }.forEach { write?.deleteCapture(it.id) }
        write?.deleteTask(id)
    }

    /** Set/clear a task's recurrence and realign its future cal_blocks. */
    fun setRecurrence(task: TaskItem, recurrence: Recurrence?) = launchWrite {
        val updated = task.copy(recurrence = recurrence, updatedAt = isoNow())
        write?.upsertTask(updated)
        val existing = blocks.value
        val anchor = existing.filter { it.taskId == task.id && tech.csalliance.unstuck.core.logic.isTaskBlock(it) }
            .minWithOrNull(compareBy({ it.date }, { it.startTime }))
        val startTime = anchor?.startTime ?: "09:00"
        val startDate = anchor?.date?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
            ?.let { tech.csalliance.unstuck.core.time.Time.civil(it[0], it[1], it[2]) }
            ?: tech.csalliance.unstuck.core.time.Time.startOfDayMillis(nowMs())
        val plan = tech.csalliance.unstuck.core.logic.regenerateForTask(
            updated, recurrence, existing, tech.csalliance.unstuck.core.time.Clock.todayIso(), startTime, startDate,
        )
        plan.toDelete.forEach { write?.deleteCalBlock(it) }
        plan.toUpsert.forEach { write?.upsertCalBlock(it) }
    }

    // --- scheduling (cal blocks) ---

    /**
     * Schedule or RE-schedule a task. Mirrors the web persistOrMove:
     * - first-time placement creates a block and does NOT bump moveCount;
     * - moving an existing block updates it in place and bumps moveCount only
     *   when the date/time actually changed (so the slip detector stays honest);
     * - recurring tasks diff via regenerateForTask instead of blindly inserting a
     *   whole new horizon every tap.
     */
    fun scheduleTask(task: TaskItem, date: String, startTime: String) = launchWrite {
        val recurrence = task.recurrence
        val existing = blocks.value.filter { it.taskId == task.id && tech.csalliance.unstuck.core.logic.isTaskBlock(it) }
        if (recurrence != null) {
            val parts = date.split("-").mapNotNull { it.toIntOrNull() }
            val startDate = if (parts.size == 3) tech.csalliance.unstuck.core.time.Time.civil(parts[0], parts[1], parts[2])
            else tech.csalliance.unstuck.core.time.Time.startOfDayMillis(nowMs())
            val plan = tech.csalliance.unstuck.core.logic.regenerateForTask(task, recurrence, blocks.value, tech.csalliance.unstuck.core.time.Clock.todayIso(), startTime, startDate)
            plan.toDelete.forEach { write?.deleteCalBlock(it) }
            plan.toUpsert.forEach { write?.upsertCalBlock(it) }
            if (existing.isNotEmpty()) write?.upsertTask(bumpMoveCount(task, isoNow()))
        } else {
            val cur = existing.minWithOrNull(compareBy({ it.date }, { it.startTime }))
            if (cur != null) {
                if (cur.date != date || cur.startTime != startTime) {
                    write?.upsertCalBlock(cur.copy(date = date, startTime = startTime))
                    write?.upsertTask(bumpMoveCount(task, isoNow()))
                }
            } else {
                write?.upsertCalBlock(CalBlock(id = newUuid(), taskId = task.id, taskName = task.name, startTime = startTime, durationMinutes = task.estimateMin, date = date, kind = CalBlockKind.TASK))
            }
        }
    }

    fun unschedule(blockId: String) = launchWrite { write?.deleteCalBlock(blockId) }

    /** Reschedule / resize an existing block (the block-edit sheet). */
    fun moveBlock(block: CalBlock, date: String, startTime: String) = launchWrite {
        write?.upsertCalBlock(block.copy(date = date, startTime = startTime))
    }
    fun resizeBlock(block: CalBlock, durationMinutes: Int) = launchWrite {
        write?.upsertCalBlock(block.copy(durationMinutes = durationMinutes.coerceIn(15, 360)))
    }

    // --- google calendar ---
    /** Begin OAuth consent — returns the authorize URL to open in a Custom Tab. */
    suspend fun beginGoogleConnect(): String? = graph.coordinator?.beginGoogleConnect()
    /** Pull external events now (manual refresh). */
    suspend fun syncCalendar() { graph.coordinator?.pullCalendar() }
    fun disconnectCalendar(id: String) = launchWrite { graph.coordinator?.disconnectCalendar(id) }

    // --- reminders (pre-task "remind me N min before"; device-local) ---
    /** Per-task reminder lead override in minutes, or null to use the global default. */
    fun reminderOverride(taskId: String): Int? = graph.settings.reminderOverride(taskId)
    fun setReminderOverride(taskId: String, leadMin: Int?) = graph.settings.setReminderOverride(taskId, leadMin)

    // --- notification deep links (set by MainActivity from the launch intent) ---
    val pendingDeepLink: StateFlow<String?> get() = graph.pendingDeepLink
    fun consumeDeepLink() { graph.pendingDeepLink.value = null }

    // --- in-app nudges (things slipping / follow-ups) — surfaced quietly on Today, no push ---
    private val _dismissedNudges = MutableStateFlow<Set<String>>(emptySet())
    fun dismissNudge(id: String) { _dismissedNudges.value = _dismissedNudges.value + id }
    val nudges: StateFlow<List<Nudge>> =
        combine(tasks, captures, _dismissedNudges) { ts, cs, dismissed ->
            computeNudges(ts, cs, nowMs()).filterNot { it.id in dismissed }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun computeNudges(tasks: List<TaskItem>, captures: List<Capture>, now: Long): List<Nudge> {
        val out = mutableListOf<Nudge>()
        // D1 — slipping: open tasks older than 3 weeks or rescheduled 3+ times.
        tasks.asSequence().filter { !it.done }.forEach { t ->
            val ageDays = tech.csalliance.unstuck.core.time.Time.parseMillis(t.createdAt)?.let { (now - it) / 86_400_000.0 } ?: 0.0
            if (ageDays >= 21 || (t.moveCount ?: 0) >= 3) {
                out.add(Nudge("slip:${t.id}", NudgeKind.SLIPPING, "“${t.name}” has been waiting a while.", "Open", taskId = t.id))
            }
        }
        // E1 — a follow-up capture worth turning into a task.
        captures.asSequence().filter { it.tag == CaptureTag.FOLLOW_UP }.sortedByDescending { it.at }.take(1).forEach { cap ->
            out.add(Nudge("cap:${cap.id}", NudgeKind.CAPTURE, "You noted “${cap.body}”.", "Make a task", captureId = cap.id))
        }
        return out.take(3)
    }

    fun blockTime(date: String, startTime: String, durationMinutes: Int, label: String) = launchWrite {
        write?.upsertCalBlock(
            CalBlock(id = newUuid(), taskId = "placeholder", taskName = label, startTime = startTime, durationMinutes = durationMinutes, date = date, kind = CalBlockKind.PLACEHOLDER),
        )
    }

    // --- focus / live session ---

    fun startFocus(task: TaskItem) = launchWrite {
        val cur = store.getLiveSession()
        // Re-entering the SAME task's live session keeps its current state — a
        // paused session stays paused (the user resumes explicitly), it isn't
        // auto-resumed just by opening the focus screen.
        if (cur?.taskId == task.id) return@launchWrite
        val base = cur ?: FocusTimer.empty
        val live = FocusTimer.start(base, task.id, estimateMin = task.estimateMin, now = nowMs())
        store.setLiveSession(FocusTimer.setTreatment(live, _settings.value.treatment))
    }

    fun pauseFocus() = launchWrite { mutateLive { FocusTimer.pause(it, nowMs()) } }
    fun resumeFocus() = launchWrite { mutateLive { FocusTimer.resume(it, nowMs()) } }
    fun setTreatment(t: FocusTreatment) = launchWrite {
        mutateLive { FocusTimer.setTreatment(it, t) }
        updateSettings { it.copy(treatment = t) }
    }
    fun extendFocus(minutes: Int) = launchWrite { mutateLive { FocusTimer.extend(it, minutes) } }

    /**
     * End the focus session. Mirrors the web's two finish actions:
     * - markDone = false → "End for now": record the session, keep the task open
     *   (returning later resumes at the accumulated total). This is the safe default.
     * - markDone = true → "Mark complete / Done early": also flip the task done.
     */
    fun finishFocus(task: TaskItem, markDone: Boolean = false) = launchWrite {
        val live = store.getLiveSession() ?: return@launchWrite
        val elapsed = FocusTimer.elapsedSec(live, nowMs())
        write?.upsertSession(
            Session(id = newUuid(), taskId = task.id, taskName = task.name, estimateMin = task.estimateMin, actualSec = elapsed, completedAt = isoNow()),
        )
        val focused = task.copy(totalFocused = task.totalFocused + elapsed, updatedAt = isoNow())
        write?.upsertTask(
            if (markDone) applyCompletion(focused.copy(done = true), prior = task, nowISO = isoNow()) else focused,
        )
        store.setLiveSession(null)
        // Session-end recap (design moment B3): records an in-app card always; the
        // server only pushes when away — finishing in-app means away = false.
        runCatching { graph.coordinator?.notifications?.sessionRecap(task.name, away = false) }
        _lastRecap.value = RecapState(taskName = task.name, focusedSec = elapsed)
    }

    // The most recent session-end recap, surfaced as a dismissible card on Today
    // (kept alongside ReflectSheet). Cleared when dismissed.
    private val _lastRecap = MutableStateFlow<RecapState?>(null)
    val lastRecap: StateFlow<RecapState?> = _lastRecap
    fun dismissRecap() { _lastRecap.value = null }

    fun cancelFocus() = launchWrite { store.setLiveSession(null) }

    private suspend fun mutateLive(transform: (LiveSession) -> LiveSession) {
        val cur = store.getLiveSession() ?: return
        store.setLiveSession(transform(cur))
    }

    // --- captures / reasons ---

    fun saveCapture(taskId: String?, sessionId: String?, tag: CaptureTag, body: String) = launchWrite {
        val text = body.trim(); if (text.isEmpty()) return@launchWrite
        write?.upsertCapture(Capture(id = newUuid(), taskId = taskId, sessionId = sessionId, tag = tag, body = text, at = isoNow()))
    }

    fun saveReasonLog(taskId: String?, reason: String, action: ReasonAction = ReasonAction.PAUSE, durationSec: Int? = null) = launchWrite {
        write?.upsertReasonLog(ReasonLog(id = newUuid(), taskId = taskId, reason = reason, action = action, at = isoNow(), durationSec = durationSec))
    }

    fun deleteCapture(id: String) = launchWrite { write?.deleteCapture(id) }

    /**
     * Promote a capture into a standalone task. Mirrors the web capture-actions:
     * the capture is PRESERVED (not deleted), and the new task is seeded with
     * lifeArea "Work" + tags ["from-capture", <captureTag>].
     */
    fun promoteCapture(capture: Capture): TaskItem {
        val tagName = capture.tag.name.lowercase().replace('_', '-')
        return addTask(name = capture.body, estimateMin = 25, lifeArea = "Work", tags = listOf("from-capture", tagName))
    }

    // --- collections ---

    fun upsertCollection(c: ItemCollection) = launchWrite { write?.upsertCollection(c) }
    fun deleteCollection(id: String) = launchWrite { write?.deleteCollection(id) }

    // Collection item ops — whole-row upserts (the JSONB row carries its items).
    // Each mutation is serialized + re-resolves the LATEST collection from Room
    // first, so rapid fast-add / pin bursts can't persist a stale snapshot and
    // drop items typed in between (matches the web's functional-update guard).
    private val collectionMutex = Mutex()
    private fun mutateCollection(id: String, transform: (ItemCollection) -> ItemCollection) = launchWrite {
        collectionMutex.withLock {
            val latest = store.collections().first().firstOrNull { it.id == id } ?: return@withLock
            write?.upsertCollection(transform(latest))
        }
    }
    fun addCollectionItem(col: ItemCollection, body: String) {
        val text = body.trim(); if (text.isEmpty()) return
        mutateCollection(col.id) { it.copy(items = it.items + tech.csalliance.unstuck.core.model.CollectionItem(newUuid(), text, at = isoNow())) }
    }
    fun updateCollectionItemBody(col: ItemCollection, itemId: String, body: String) =
        mutateCollection(col.id) { c -> c.copy(items = c.items.map { if (it.id == itemId) it.copy(body = body.trim()) else it }) }
    fun toggleCollectionItemPin(col: ItemCollection, itemId: String) =
        mutateCollection(col.id) { c -> c.copy(items = c.items.map { if (it.id == itemId) it.copy(pinned = !(it.pinned ?: false)) else it }) }
    fun toggleCollectionItemDone(col: ItemCollection, itemId: String) =
        mutateCollection(col.id) { c -> c.copy(items = c.items.map { if (it.id == itemId) it.copy(done = !(it.done ?: false)) else it }) }
    fun removeCollectionItem(col: ItemCollection, itemId: String) =
        mutateCollection(col.id) { c -> c.copy(items = c.items.filterNot { it.id == itemId }) }
    fun renameCollection(col: ItemCollection, name: String) {
        val nm = name.trim(); if (nm.isNotEmpty()) mutateCollection(col.id) { it.copy(name = nm) }
    }
    fun recolorCollection(col: ItemCollection, color: String) = mutateCollection(col.id) { it.copy(color = color) }

    // --- tags & areas ---

    fun upsertTag(t: TagRow) = launchWrite { write?.upsertTag(t) }

    /** Delete a tag and strip its name from every task (case-insensitive cascade). */
    fun deleteTag(id: String) = launchWrite {
        val name = tags.value.firstOrNull { it.id == id }?.name
        write?.deleteTag(id)
        if (name != null) tasks.value.filter { t -> t.tags?.any { it.equals(name, ignoreCase = true) } == true }.forEach { t ->
            write?.upsertTask(t.copy(tags = t.tags?.filterNot { it.equals(name, ignoreCase = true) }?.ifEmpty { null }, updatedAt = isoNow()))
        }
    }

    /** Add a tag to the vocabulary if its name is new; returns the name. */
    fun ensureTag(name: String): String {
        val nm = name.trim()
        if (nm.isNotEmpty() && tags.value.none { it.name.equals(nm, ignoreCase = true) }) {
            launchWrite { write?.upsertTag(TagRow(newUuid(), nm, null, tags.value.size)) }
        }
        return nm
    }

    /** Rename a tag and cascade across every task that uses it — case-insensitive
     *  match + de-dupe so renaming A→B on a task tagged [A,B] yields [B], not [B,B]. */
    fun renameTag(tag: TagRow, newName: String) = launchWrite {
        val nm = newName.trim(); if (nm.isEmpty() || nm == tag.name) return@launchWrite
        write?.upsertTag(tag.copy(name = nm))
        tasks.value.filter { t -> t.tags?.any { it.equals(tag.name, ignoreCase = true) } == true }.forEach { t ->
            write?.upsertTask(t.copy(tags = t.tags?.map { if (it.equals(tag.name, ignoreCase = true)) nm else it }?.distinct(), updatedAt = isoNow()))
        }
    }

    fun recolorTag(tag: TagRow, color: String?) = launchWrite { write?.upsertTag(tag.copy(color = color)) }
    fun upsertLifeArea(a: LifeArea) = launchWrite { write?.upsertLifeArea(a) }

    /** Delete an area and clear its label off every task (no dangling lifeArea). */
    fun deleteLifeArea(id: String) = launchWrite {
        val name = lifeAreas.value.firstOrNull { it.id == id }?.name
        write?.deleteLifeArea(id)
        if (name != null) tasks.value.filter { it.lifeArea == name }.forEach { t ->
            write?.upsertTask(t.copy(lifeArea = null, updatedAt = isoNow()))
        }
    }

    /** Rename an area + cascade the new name onto its tasks (web parity). */
    fun renameLifeArea(area: LifeArea, newName: String) = launchWrite {
        val nm = newName.trim(); if (nm.isEmpty() || nm == area.name) return@launchWrite
        write?.upsertLifeArea(area.copy(name = nm))
        tasks.value.filter { it.lifeArea == area.name }.forEach { t -> write?.upsertTask(t.copy(lifeArea = nm, updatedAt = isoNow())) }
    }

    fun recolorLifeArea(area: LifeArea, color: String) = launchWrite { write?.upsertLifeArea(area.copy(color = color)) }

    // --- onboarding ---

    val onboarded: Boolean get() = graph.onboarded

    fun completeOnboarding(struggles: List<String>, areas: List<String> = emptyList()) = launchWrite {
        // Seed the user's PICKED areas (or canonical defaults if they picked none).
        // Single source of seeding — onboarding no longer also writes areas itself,
        // so we don't double-seed (picked + defaults).
        if (lifeAreas.value.isEmpty()) {
            val palette = listOf("indigo", "coral", "violet", "green", "amber", "blue")
            val seed = areas.ifEmpty { listOf("Work", "Personal", "Home", "Health") }
            seed.forEachIndexed { i, n -> write?.upsertLifeArea(LifeArea(id = newUuid(), name = n, color = palette[i % palette.size], sortOrder = i)) }
        }
        val uid = auth?.currentUserId
        if (uid != null && struggles.isNotEmpty()) {
            runCatching { graph.coordinator?.preferences?.setAdhdStruggles(uid, struggles) }
        }
        graph.onboarded = true
    }

    // --- settings (device-local prefs: theme / focus / sound / a11y) ---

    private val settingsStore = graph.settings
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<tech.csalliance.unstuck.SettingsState> = _settings.asStateFlow()

    /** Mutate + persist settings in one call: `updateSettings { it.copy(theme = …) }`. */
    fun updateSettings(transform: (tech.csalliance.unstuck.SettingsState) -> tech.csalliance.unstuck.SettingsState) {
        val next = transform(_settings.value)
        _settings.value = next
        settingsStore.save(next)
    }

    // --- auth ---

    /** Signed-in identity for the avatar / account UI (null when signed out). */
    val currentEmail: String? get() = auth?.currentEmail
    val currentName: String? get() = auth?.currentName

    suspend fun signIn(email: String, password: String): AuthOutcome =
        auth?.signIn(email, password) ?: AuthOutcome.Error("Not configured")
    suspend fun signUp(email: String, password: String, name: String?): AuthOutcome =
        auth?.signUp(email, password, name) ?: AuthOutcome.Error("Not configured")
    suspend fun magicLink(email: String): AuthOutcome =
        auth?.sendMagicLink(email) ?: AuthOutcome.Error("Not configured")
    suspend fun googleSignIn(): AuthOutcome =
        auth?.signInWithGoogle() ?: AuthOutcome.Error("Not configured")
    suspend fun resetPassword(email: String): AuthOutcome =
        auth?.resetPassword(email) ?: AuthOutcome.Error("Not configured")
    suspend fun changePassword(password: String): AuthOutcome =
        auth?.changePassword(password) ?: AuthOutcome.Error("Not configured")
    suspend fun updateDisplayName(name: String): AuthOutcome =
        auth?.updateDisplayName(name) ?: AuthOutcome.Error("Not configured")
    suspend fun deleteAccount(): AuthOutcome =
        auth?.deleteAccount() ?: AuthOutcome.Error("Not configured")
    val hasPassword: Boolean get() = auth?.hasPassword ?: true
    fun signOut() = launchWrite { auth?.signOut() }

    /** Serialise every user-owned collection into one JSON bundle (matches web exportAll). */
    fun exportJson(): String = EXPORT_JSON.encodeToString(
        ExportBundle(
            exportedAt = isoNow(), email = currentEmail,
            tasks = tasks.value, sessions = sessions.value, calBlocks = blocks.value,
            captures = captures.value, reasonLogs = reasonLogs.value,
            collections = collections.value, tags = tags.value, lifeAreas = lifeAreas.value,
        ),
    )

    companion object {
        private val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        private val EXPORT_JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}

/** One-shot JSON snapshot of all user-owned data (matches the web export bundle). */
@Serializable
data class ExportBundle(
    val exportedAt: String,
    val email: String?,
    val tasks: List<TaskItem>,
    val sessions: List<Session>,
    val calBlocks: List<CalBlock>,
    val captures: List<Capture>,
    val reasonLogs: List<ReasonLog>,
    val collections: List<ItemCollection>,
    val tags: List<TagRow>,
    val lifeAreas: List<LifeArea>,
)

/** A just-finished focus session, surfaced as the Today recap card (B3). */
data class RecapState(val taskName: String, val focusedSec: Int)

/** A quiet, in-app nudge surfaced on Today (no push) — see the notifications catalog. */
enum class NudgeKind { SLIPPING, CAPTURE }
data class Nudge(
    val id: String,
    val kind: NudgeKind,
    val title: String,
    val action: String,
    val taskId: String? = null,
    val captureId: String? = null,
)
