package tech.csalliance.unstuck.ui.assistant

import tech.csalliance.unstuck.core.logic.PendingShare
import tech.csalliance.unstuck.core.logic.ShareCandidate
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
import tech.csalliance.unstuck.sync.CallRequest
import tech.csalliance.unstuck.sync.CallsClient

// The app-state seam the assistant executor runs against — mirror of the web
// `AssistantApi` / iOS `AssistantAppState`. Reads return the FRESHEST committed
// state (the Room store, not a lagging StateFlow); writes go through the same
// store/sync path the UI uses (cascades, outbox, realtime) and RETURN ONLY
// AFTER THE LOCAL ROW IS COMMITTED — the executor reads between its own writes
// (create_task → schedule_task → delete_task in one turn), and a fire-and-forget
// write behind a read produced "capture not found" and ghost blocks on iOS.
//
// EVERY WRITE REPORTS ITS REAL OUTCOME (2026-09-20 tooling rewrite,
// docs/assistant-tooling-rules.md §1): a seam that returns Unit can silently
// no-op (the row vanished between the executor's read and the write, a blank
// body, a missing write layer) while the executor still answered `ok:` — and
// the model repeated the lie to the user. So a mutation returns Boolean / the
// id it created / null for "nothing happened", and the executor maps every
// false to `error:`. The base row writes (upsertTask & co.) stay Unit because
// they THROW on failure, which the harness turns into `error:` as well.
//
// AppViewModel implements it through `AppViewModelAssistantApi`
// (AssistantToolsAppModel.kt); the unit tests run the SAME executor against an
// in-memory fake (AssistantToolsTest).

data class CirclePerson(val name: String, val status: String)

data class TaskShareInfo(val shareId: String, val recipientName: String, val level: String)

/** What `get_settings` reads back — the same values the Settings screen shows.
 *  Usable minutes live on the server (`user_preferences`) and are null until
 *  the account has set them. Rituals are keyed by the tool's names. */
data class AssistantSettingsSnapshot(
    /** calm | balanced | coach */
    val notificationLevel: String,
    /** Default minutes before a scheduled task; 0 = off. */
    val reminderLeadMin: Int,
    val usableWeekdayMin: Int?,
    val usableWeekendMin: Int?,
    val focusDefaultMin: Int,
    val focusOverrunMin: Int,
    val focusSoftExit: Boolean,
    val focusPauseReasons: Boolean,
    /** system | light | dark */
    val theme: String,
    /** off | brown | pink */
    val ambient: String,
    /** morning / evening / friday / sunday → on? */
    val rituals: Map<String, Boolean>,
)

/** The call_requests reads/writes the call tools need — [CallsClient] in
 *  production, a fake in tests. Writes that can lose a race return null for
 *  "zero rows matched". */
interface AssistantCallStore {
    suspend fun liveCalls(): List<CallRequest>
    suspend fun call(id: String): CallRequest?
    /** [kind] is `requested` (the assistant / the task editor — the default) or
     *  `test` (Settings' test button). The proactive kinds are the server's alone. */
    suspend fun book(
        userId: String, taskId: String?, blockId: String?, callAtMs: Long, leadMin: Int?,
        label: String, notes: List<String>,
        kind: String = tech.csalliance.unstuck.core.model.CallKind.REQUESTED.wire,
    ): CallRequest
    suspend fun patch(
        id: String, callAtMs: Long?, blockId: CallsClient.Patch<String?>?, leadMin: CallsClient.Patch<Int?>?,
        label: String?, notes: List<String>?,
    ): CallRequest?
    suspend fun cancelCall(id: String): CallRequest?
}

/** [CallsClient] as an [AssistantCallStore]. */
class CallsClientStore(private val client: CallsClient, private val newId: () -> String) : AssistantCallStore {
    override suspend fun liveCalls(): List<CallRequest> = client.list(upcoming = true)
    override suspend fun call(id: String): CallRequest? = client.get(id)
    override suspend fun book(
        userId: String, taskId: String?, blockId: String?, callAtMs: Long, leadMin: Int?,
        label: String, notes: List<String>, kind: String,
    ): CallRequest = client.create(
        id = newId(), userId = userId, taskId = taskId, blockId = blockId, callAtMs = callAtMs,
        leadMin = leadMin, label = label, notes = notes, kind = kind,
    )
    override suspend fun patch(
        id: String, callAtMs: Long?, blockId: CallsClient.Patch<String?>?, leadMin: CallsClient.Patch<Int?>?,
        label: String?, notes: List<String>?,
    ): CallRequest? = client.update(id, callAtMs, blockId, leadMin, label, notes)
    override suspend fun cancelCall(id: String): CallRequest? = client.cancel(id)
}

interface AssistantApi {
    // ── reads ──
    suspend fun getTasks(): List<TaskItem>
    suspend fun getBlocks(): List<CalBlock>
    suspend fun getCollections(): List<ItemCollection>
    suspend fun getAreaRows(): List<LifeArea>
    suspend fun getTagRows(): List<TagRow>
    suspend fun getAreas(): List<String> = getAreaRows().map { it.name }
    suspend fun getTags(): List<String> = getTagRows().map { it.name }
    fun currentUserName(): String
    /** 'YYYY-MM-DD' in the user's local tz. */
    fun todayIso(): String
    /** LOCAL wall-clock 'HH:MM' — injectable so the time guards are testable. */
    fun nowHM(): String
    /** Epoch ms (injectable clock). */
    fun nowMs(): Long
    /** ISO-8601 instant for `createdAt` / `updatedAt`. */
    fun nowIso(): String

    // ── tasks + blocks (committed locally before returning; they throw on failure) ──
    suspend fun upsertTask(t: TaskItem)
    suspend fun removeTask(id: String)
    /** A task that just went done → open is a loop-promoted shared-list item:
     *  un-tick the collection row for the other members (best-effort). */
    suspend fun notifyTaskReopenedIfShared(t: TaskItem)
    /** The mirror image: a task that just went open → done and was promoted from
     *  a shared list ticks the collection row for the other members — the hook
     *  the UI's toggleDone and finishFocus fire (audit 2026-09-22 C6). Both
     *  notices return at once and go out in the background, so a tool's reply
     *  never waits on the network. */
    suspend fun notifyTaskCompletedIfShared(t: TaskItem)
    suspend fun upsertBlock(b: CalBlock)
    suspend fun deleteBlock(id: String)
    /** Per-task reminder lead override (minutes; 0 = off), null = the default. */
    fun getTaskReminder(taskId: String): Int?
    /** Set / clear (null) the per-task reminder override and re-arm the alarms. False = not saved. */
    fun setTaskReminder(taskId: String, minutes: Int?): Boolean

    // ── lists (every write answers whether the row was really written) ──
    /** → the new list's id (null when it could not be created). */
    suspend fun addCollection(name: String, color: String): String?
    /** → the new item's id (null when the list is gone or the body is blank). */
    suspend fun addCollectionItem(collectionId: String, body: String): String?
    /** Turn a list item into a task through the SAME path the list UI uses.
     *  → the new task's id (null when the list/item is gone or the guard refused). */
    suspend fun promoteItemToTask(collectionId: String, itemId: String, loop: Boolean, dueAt: String?): String?
    suspend fun renameCollection(id: String, name: String): Boolean
    suspend fun updateCollection(id: String, archived: Boolean?, color: String?): Boolean
    suspend fun removeCollection(id: String): Boolean
    suspend fun updateCollectionItem(collectionId: String, itemId: String, body: String?, done: Boolean?): Boolean
    suspend fun setCollectionItemPinned(collectionId: String, itemId: String, pinned: Boolean): Boolean
    suspend fun removeCollectionItem(collectionId: String, itemId: String): Boolean
    /** Leave a list someone else shared with the user. TRUE only when the server confirmed it. */
    suspend fun leaveCollection(id: String): Boolean
    /** Unknown → false (the web's use-assistant-api rule); else editable unless viewer. */
    suspend fun canEditCollection(id: String): Boolean
    /** Rename / archive / delete are OWNER-only — in the list UI (the pencil,
     *  archive and delete affordances only render for the owner) and on the
     *  server (`lock_collection_metadata` + the owner-only delete policy), which
     *  accept an editor's write and silently discard it. Gating those three tools
     *  on [canEditCollection] let the assistant tell an EDITOR the list was
     *  renamed / archived / deleted a moment before it snapped back. */
    suspend fun isCollectionOwner(id: String): Boolean

    // ── sharing ──
    fun getShareCandidates(): List<ShareCandidate>
    /** Stage a share (task or list) for the USER to confirm on screen. Never shares. */
    fun stageShare(p: PendingShare)
    fun getCirclePeople(): List<CirclePerson>
    suspend fun listTaskShares(taskId: String): List<TaskShareInfo>
    /** False when the server did NOT revoke the share. */
    suspend fun unshareTask(shareId: String): Boolean

    // ── profile memory ──
    suspend fun getProfileFacts(): List<ProfileFact>
    /** The get-to-know-you interview is still PENDING on this account (not
     *  finished or skipped — the same flag the in-thread interview keeps).
     *  Gates the voice opening primer's intro; `finish_interview` clears it. */
    fun interviewPending(): Boolean = false
    /** Close the intro for good (local flag + the server). False = no account to mark. */
    fun markInterviewDone(): Boolean = false
    /** Throws `ProfileFactSaveError` — the executor tells a text rejection
     *  (Empty / InstructionLike) from a store failure (StoreFailed). */
    suspend fun saveProfileFact(category: String?, fact: String, whenIso: String?): ProfileFact
    suspend fun removeProfileFact(id: String): Boolean

    // ── behavioural history ──
    suspend fun getSessions(): List<Session>
    suspend fun getReasonLogs(): List<ReasonLog>
    fun getStruggles(): List<String>

    // ── captures ──
    suspend fun getCaptures(): List<Capture>
    fun getArchivedCaptureIds(): Set<String>
    suspend fun upsertCapture(c: Capture)
    suspend fun removeCapture(id: String)
    /** Device-local cache + the queued server write. False when the cache did not take it. */
    fun archiveCapture(id: String, archived: Boolean): Boolean

    // ── focus (false = there was no session to act on / nothing was written) ──
    suspend fun getLiveFocus(): LiveSession?
    suspend fun startFocus(taskId: String, estimateMin: Int?, occurrenceBlockId: String?): Boolean
    suspend fun pauseFocus(): Boolean
    suspend fun resumeFocus(): Boolean
    suspend fun extendFocus(minutes: Int): Boolean
    /** End the running session and LOG it — the Focus screen's Done / Stop here
     *  path; markDone also closes the task (today's occurrence for a repeat). */
    suspend fun finishFocus(markDone: Boolean): Boolean
    /** After [finishFocus] with markDone on a session shared WITH the user: why the
     *  owner's task was NOT ticked, or null when it was. A repeating share answers
     *  'recurring_series' — only its owner ticks it off (075 §1, audit 2026-09-22
     *  C3) — and any other text is a tick that failed. The reply is worded off what
     *  happened, never off a guess (SC-12). */
    fun sharedFinishRefusal(taskId: String): String? = null
    /** Abandon the running session WITHOUT logging it. */
    suspend fun cancelFocus(): Boolean

    // ── navigation ──
    fun navigate(screen: String, id: String?)

    // ── areas + tags (false = the row was gone) ──
    suspend fun addArea(name: String, color: String?): Boolean
    suspend fun updateArea(id: String, name: String?, color: String?): Boolean
    suspend fun removeArea(id: String): Boolean
    suspend fun addTag(name: String): Boolean
    suspend fun updateTag(id: String, name: String?): Boolean
    suspend fun removeTag(id: String): Boolean

    // ── settings (the REAL outcome — false makes the contract's "could not save" reachable) ──
    fun getSettings(): AssistantSettingsSnapshot
    suspend fun setUsableMinutes(weekday: Int?, weekend: Int?): Boolean
    suspend fun setNotificationLevel(level: String): Boolean
    suspend fun setReminderLead(minutes: Int): Boolean
    fun setRitual(ritual: String, on: Boolean): Boolean
    /** system | light | dark */
    fun setTheme(theme: String): Boolean
    /** off | brown | pink */
    fun setAmbientSound(sound: String): Boolean
    /** Only the non-null fields change. */
    fun setFocusDefaults(defaultMinutes: Int?, overrunMinutes: Int?, softExit: Boolean?, pauseReasons: Boolean?): Boolean

    // ── calls ("Unstuck calls you") ──
    fun currentUserId(): String?
    /** Null when signed out / not configured → the call tools say so. */
    fun callStore(): AssistantCallStore?
    /** THIS phone's Calls switch + allowed hours (Settings › Calls) — what
     *  decides on receipt, so request_call / update_call refuse a time it would
     *  decline (CallSettingsLogic.deviceGuard; parity with iOS build 81, audit
     *  2026-09-22 C12). The defaults (on, 06:00–23:00) when not wired. */
    fun callSettings(): tech.csalliance.unstuck.core.logic.CallSettings = tech.csalliance.unstuck.core.logic.CallSettings.DEFAULTS
}
