package tech.csalliance.unstuck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    fun scheduleTask(task: TaskItem, date: String, startTime: String) = launchWrite {
        val recurrence = task.recurrence
        if (recurrence != null) {
            val parts = date.split("-").mapNotNull { it.toIntOrNull() }
            val startDate = if (parts.size == 3) {
                tech.csalliance.unstuck.core.time.Time.civil(parts[0], parts[1], parts[2])
            } else {
                tech.csalliance.unstuck.core.time.Time.startOfDayMillis(nowMs())
            }
            tech.csalliance.unstuck.core.logic.materializeOccurrences(recurrence, startDate, startTime).forEach { o ->
                write?.upsertCalBlock(
                    CalBlock(id = newUuid(), taskId = task.id, taskName = task.name, startTime = o.startTime, durationMinutes = task.estimateMin, date = o.date, kind = CalBlockKind.TASK),
                )
            }
        } else {
            write?.upsertCalBlock(
                CalBlock(id = newUuid(), taskId = task.id, taskName = task.name, startTime = startTime, durationMinutes = task.estimateMin, date = date, kind = CalBlockKind.TASK),
            )
        }
        // Rescheduling an existing task bumps its move count (slip detector).
        write?.upsertTask(bumpMoveCount(task, isoNow()))
    }

    fun unschedule(blockId: String) = launchWrite { write?.deleteCalBlock(blockId) }

    /** Reschedule / resize an existing block (the block-edit sheet). */
    fun moveBlock(block: CalBlock, date: String, startTime: String) = launchWrite {
        write?.upsertCalBlock(block.copy(date = date, startTime = startTime))
    }
    fun resizeBlock(block: CalBlock, durationMinutes: Int) = launchWrite {
        write?.upsertCalBlock(block.copy(durationMinutes = durationMinutes.coerceIn(15, 360)))
    }

    fun blockTime(date: String, startTime: String, durationMinutes: Int, label: String) = launchWrite {
        write?.upsertCalBlock(
            CalBlock(id = newUuid(), taskId = "placeholder", taskName = label, startTime = startTime, durationMinutes = durationMinutes, date = date, kind = CalBlockKind.PLACEHOLDER),
        )
    }

    // --- focus / live session ---

    fun startFocus(task: TaskItem) = launchWrite {
        val cur = store.getLiveSession() ?: FocusTimer.empty
        val live = FocusTimer.start(cur, task.id, estimateMin = task.estimateMin, now = nowMs())
        // Apply the persisted treatment preference on a fresh session.
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
    }

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

    /** Promote a capture into a standalone task (named from the capture body), then remove the capture. */
    fun promoteCapture(capture: Capture): TaskItem {
        val t = addTask(name = capture.body)
        deleteCapture(capture.id)
        return t
    }

    // --- collections ---

    fun upsertCollection(c: ItemCollection) = launchWrite { write?.upsertCollection(c) }
    fun deleteCollection(id: String) = launchWrite { write?.deleteCollection(id) }

    // --- tags & areas ---

    fun upsertTag(t: TagRow) = launchWrite { write?.upsertTag(t) }
    fun deleteTag(id: String) = launchWrite { write?.deleteTag(id) }

    /** Add a tag to the vocabulary if its name is new; returns the name. */
    fun ensureTag(name: String): String {
        val nm = name.trim()
        if (nm.isNotEmpty() && tags.value.none { it.name.equals(nm, ignoreCase = true) }) {
            launchWrite { write?.upsertTag(TagRow(newUuid(), nm, null, tags.value.size)) }
        }
        return nm
    }

    /** Rename a tag and cascade the change across every task that uses it. */
    fun renameTag(tag: TagRow, newName: String) = launchWrite {
        val nm = newName.trim(); if (nm.isEmpty() || nm == tag.name) return@launchWrite
        write?.upsertTag(tag.copy(name = nm))
        tasks.value.filter { it.tags?.contains(tag.name) == true }.forEach { t ->
            write?.upsertTask(t.copy(tags = t.tags?.map { if (it == tag.name) nm else it }, updatedAt = isoNow()))
        }
    }

    fun recolorTag(tag: TagRow, color: String?) = launchWrite { write?.upsertTag(tag.copy(color = color)) }
    fun upsertLifeArea(a: LifeArea) = launchWrite { write?.upsertLifeArea(a) }
    fun deleteLifeArea(id: String) = launchWrite { write?.deleteLifeArea(id) }

    // --- onboarding ---

    val onboarded: Boolean get() = graph.onboarded

    fun completeOnboarding(struggles: List<String>) = launchWrite {
        if (lifeAreas.value.isEmpty()) {
            val colors = listOf("indigo", "coral", "violet", "green")
            listOf("Work", "Personal", "Home", "Health").forEachIndexed { i, n ->
                write?.upsertLifeArea(LifeArea(id = newUuid(), name = n, color = colors[i], sortOrder = i))
            }
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
    fun signOut() = launchWrite { auth?.signOut() }

    companion object {
        private val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
