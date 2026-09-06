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
// AppViewModel implements it through `AppViewModelAssistantApi`
// (AssistantToolsAppModel.kt); the unit tests run the SAME executor against an
// in-memory fake (AssistantToolsTest).

data class CirclePerson(val name: String, val status: String)

data class TaskShareInfo(val shareId: String, val recipientName: String, val level: String)

/** The call_requests reads/writes the call tools need — [CallsClient] in
 *  production, a fake in tests. Writes that can lose a race return null for
 *  "zero rows matched". */
interface AssistantCallStore {
    suspend fun liveCalls(): List<CallRequest>
    suspend fun call(id: String): CallRequest?
    suspend fun book(
        userId: String, taskId: String?, blockId: String?, callAtMs: Long, leadMin: Int?,
        label: String, notes: List<String>,
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
        label: String, notes: List<String>,
    ): CallRequest = client.create(
        id = newId(), userId = userId, taskId = taskId, blockId = blockId, callAtMs = callAtMs,
        leadMin = leadMin, label = label, notes = notes,
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

    // ── tasks + blocks (committed locally before returning) ──
    suspend fun upsertTask(t: TaskItem)
    suspend fun removeTask(id: String)
    /** A task that just went done → open is a loop-promoted shared-list item:
     *  un-tick the collection row for the other members (best-effort). */
    suspend fun notifyTaskReopenedIfShared(t: TaskItem)
    suspend fun upsertBlock(b: CalBlock)
    suspend fun deleteBlock(id: String)

    // ── lists ──
    /** → the new list's id (null when it could not be created). */
    suspend fun addCollection(name: String, color: String): String?
    suspend fun addCollectionItem(collectionId: String, body: String)
    /** Turn a list item into a task through the SAME path the list UI uses. */
    suspend fun promoteItemToTask(collectionId: String, itemId: String, loop: Boolean, dueAt: String?)
    suspend fun renameCollection(id: String, name: String)
    suspend fun updateCollection(id: String, archived: Boolean?, color: String?)
    suspend fun removeCollection(id: String)
    suspend fun updateCollectionItem(collectionId: String, itemId: String, body: String?, done: Boolean?)
    suspend fun removeCollectionItem(collectionId: String, itemId: String)
    /** Unknown → false (the web's use-assistant-api rule); else editable unless viewer. */
    suspend fun canEditCollection(id: String): Boolean

    // ── sharing ──
    fun getShareCandidates(): List<ShareCandidate>
    /** Stage a share for the USER to confirm on screen. Never shares. */
    fun stageShare(p: PendingShare)
    fun getCirclePeople(): List<CirclePerson>
    suspend fun listTaskShares(taskId: String): List<TaskShareInfo>
    /** False when the server did NOT revoke the share. */
    suspend fun unshareTask(shareId: String): Boolean

    // ── profile memory ──
    suspend fun getProfileFacts(): List<ProfileFact>
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
    /** Device-local cache + the queued server write. */
    fun archiveCapture(id: String, archived: Boolean)

    // ── focus ──
    suspend fun getLiveFocus(): LiveSession?
    suspend fun startFocus(taskId: String, estimateMin: Int?, occurrenceBlockId: String?)
    suspend fun pauseFocus()
    suspend fun resumeFocus()
    suspend fun extendFocus(minutes: Int)
    /** Abandon the running session WITHOUT logging it. */
    suspend fun cancelFocus()

    // ── navigation ──
    fun navigate(screen: String, id: String?)

    // ── areas + tags ──
    suspend fun addArea(name: String, color: String?)
    suspend fun updateArea(id: String, name: String?, color: String?)
    suspend fun removeArea(id: String)
    suspend fun addTag(name: String)
    suspend fun updateTag(id: String, name: String?)
    suspend fun removeTag(id: String)

    // ── settings (the REAL outcome — false makes the contract's "could not save" reachable) ──
    suspend fun setUsableMinutes(weekday: Int?, weekend: Int?): Boolean
    suspend fun setNotificationLevel(level: String): Boolean
    suspend fun setReminderLead(minutes: Int): Boolean
    fun setRitual(ritual: String, on: Boolean)

    // ── calls ("Unstuck calls you") ──
    fun currentUserId(): String?
    /** Null when signed out / not configured → the call tools say so. */
    fun callStore(): AssistantCallStore?
}
