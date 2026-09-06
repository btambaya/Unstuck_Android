package tech.csalliance.unstuck.ui.assistant

import tech.csalliance.unstuck.NotificationLevel
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.PendingShare
import tech.csalliance.unstuck.core.logic.ProfileFactsLogic
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.ShareCandidate
import tech.csalliance.unstuck.core.logic.localNowHM
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.sync.CallRequestsMirror
import tech.csalliance.unstuck.sync.CallsClient
import tech.csalliance.unstuck.ui.AppViewModel

// `AssistantApi` backed by the live AppViewModel — every write goes through the
// SAME store/sync path the UI uses (WriteThrough → Room + outbox, shared-list
// RPCs, the live-session store), so the assistant can never leave the store in
// a state a tap couldn't. Reads hit the Room store directly (freshest committed
// rows) — the ViewModel's WhileSubscribed StateFlows lag the write and are
// EMPTY while nothing on screen collects them (voice mode with the sheet shut).
// Port of iOS AssistantTools+AppModel.swift.

class AppViewModelAssistantApi(private val vm: AppViewModel) : AssistantApi {
    private val store get() = vm.assistantStore
    private val write get() = vm.assistantWrite

    // ── reads ──
    override suspend fun getTasks(): List<TaskItem> = store.snapshot(Tables.TASKS, TaskItem.serializer())
    override suspend fun getBlocks(): List<CalBlock> = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer())
    override suspend fun getCollections(): List<ItemCollection> = store.snapshot(Tables.COLLECTIONS, ItemCollection.serializer())
    override suspend fun getAreaRows(): List<LifeArea> = store.snapshot(Tables.LIFE_AREAS, LifeArea.serializer()).sortedBy { it.sortOrder }
    override suspend fun getTagRows(): List<TagRow> = store.snapshot(Tables.TAGS, TagRow.serializer()).sortedBy { it.sortOrder }
    override fun currentUserName(): String = vm.currentName ?: ""
    override fun todayIso(): String = Clock.dateIso(vm.nowMs())
    override fun nowHM(): String = localNowHM(vm.nowMs())
    override fun nowMs(): Long = vm.nowMs()
    override fun nowIso(): String = vm.isoNow()

    override suspend fun getSessions(): List<Session> = store.snapshot(Tables.SESSIONS, Session.serializer())
    override suspend fun getReasonLogs(): List<ReasonLog> = store.snapshot(Tables.REASON_LOGS, ReasonLog.serializer())
    /** The account's onboarding struggles (user_preferences.adhd_struggles),
     *  canonicalised + cached per account on the ViewModel at every pull. */
    override fun getStruggles(): List<String> = vm.struggles.value

    // ── tasks + blocks (committed locally before returning) ──
    override suspend fun upsertTask(t: TaskItem) { write?.upsertTask(t) ?: store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt) }
    override suspend fun removeTask(id: String) { write?.deleteTask(id) ?: store.delete(Tables.TASKS, id) }
    override suspend fun notifyTaskReopenedIfShared(t: TaskItem) { vm.notifyTaskReopenedIfShared(t) }
    override suspend fun upsertBlock(b: CalBlock) { write?.upsertCalBlock(b) ?: store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id) }
    override suspend fun deleteBlock(id: String) { write?.deleteCalBlock(id) ?: store.delete(Tables.CAL_BLOCKS, id) }

    // ── lists ──
    private suspend fun collection(id: String): ItemCollection? = getCollections().firstOrNull { it.id == id }

    /** Committed BEFORE returning (the executor contract): a later tool in the
     *  same turn (add_to_list / rename_list on the id just returned) re-fetches
     *  the row. Same row shape as the UI path. */
    override suspend fun addCollection(name: String, color: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val nextOrder = (getCollections().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val col = ItemCollection(id = newUuid(), name = trimmed, color = color, subtitle = null, items = emptyList(), sortOrder = nextOrder, archived = false)
        write?.upsertCollection(col) ?: store.upsert(Tables.COLLECTIONS, col, ItemCollection.serializer(), col.id)
        return col.id
    }
    override suspend fun addCollectionItem(collectionId: String, body: String) {
        val c = collection(collectionId) ?: return
        vm.addCollectionItem(c, body)
    }
    override suspend fun promoteItemToTask(collectionId: String, itemId: String, loop: Boolean, dueAt: String?) {
        val c = collection(collectionId) ?: return
        val item = c.items.firstOrNull { it.id == itemId } ?: return
        vm.moveItemToTask(c, item, if (loop) AppViewModel.PromoteMode.LOOP else AppViewModel.PromoteMode.SELF, dueAt)
    }
    override suspend fun renameCollection(id: String, name: String) {
        val c = collection(id) ?: return
        vm.renameCollection(c, name)
    }
    override suspend fun updateCollection(id: String, archived: Boolean?, color: String?) {
        val c = collection(id) ?: return
        if (archived != null) vm.archiveCollection(id, archived)
        if (color != null) vm.recolorCollection(c, color)
    }
    override suspend fun removeCollection(id: String) { vm.deleteCollection(id) }
    override suspend fun updateCollectionItem(collectionId: String, itemId: String, body: String?, done: Boolean?) {
        val c = collection(collectionId) ?: return
        val item = c.items.firstOrNull { it.id == itemId } ?: return
        if (body != null) vm.updateCollectionItemBody(c, itemId, body)
        // The UI only has a toggle — flip only when the desired state differs.
        if (done != null && (item.done ?: false) != done) vm.toggleCollectionItemDone(c, itemId)
    }
    override suspend fun removeCollectionItem(collectionId: String, itemId: String) {
        val c = collection(collectionId) ?: return
        vm.removeCollectionItem(c, itemId)
    }
    override suspend fun canEditCollection(id: String): Boolean {
        val c = collection(id) ?: return false
        return vm.canEdit(c)
    }

    // ── sharing ──
    override fun getShareCandidates(): List<ShareCandidate> = vm.shareCandidates()
    override fun stageShare(p: PendingShare) { vm.stagePendingShare(p) }
    override fun getCirclePeople(): List<CirclePerson> = vm.circle.value
        .filter { it.status == CircleStatus.ACTIVE || it.status == CircleStatus.INVITED }
        .map { CirclePerson(it.memberName ?: it.relationshipLabel ?: "Someone", it.status.wire) }
    override suspend fun listTaskShares(taskId: String): List<TaskShareInfo> =
        vm.sharesForTask(taskId).map { TaskShareInfo(it.shareId, it.recipientName, it.level.wire) }
    /** The revoke RPC is best-effort inside CircleClient; re-read to know it landed. */
    override suspend fun unshareTask(shareId: String): Boolean {
        val client = vm.assistantCircleClient ?: return false
        return runCatching { client.taskUnshare(shareId) }.isSuccess.also { if (it) vm.refreshShares() }
    }

    // ── profile memory ──
    override suspend fun getProfileFacts(): List<ProfileFact> = vm.profileFactsService.all()
    override suspend fun saveProfileFact(category: String?, fact: String, whenIso: String?): ProfileFact =
        vm.profileFactsService.store(ProfileFactsLogic.category(category), fact, ProfileFactSource.CHAT, whenIso)
    override suspend fun removeProfileFact(id: String): Boolean = vm.profileFactsService.remove(id)

    // ── captures ──
    override suspend fun getCaptures(): List<Capture> = store.snapshot(Tables.CAPTURES, Capture.serializer())
    override fun getArchivedCaptureIds(): Set<String> = vm.archivedCaptureIds.value
    override suspend fun upsertCapture(c: Capture) { write?.upsertCapture(c) ?: store.upsert(Tables.CAPTURES, c, Capture.serializer(), c.id) }
    override suspend fun removeCapture(id: String) { vm.deleteCaptureNow(id) }
    override fun archiveCapture(id: String, archived: Boolean) { if (archived) vm.archiveCapture(id) else vm.unarchiveCapture(id) }

    // ── focus (the same FocusTimer transitions the Focus screen runs) ──
    override suspend fun getLiveFocus(): LiveSession? = store.getLiveSession()
    /** JOIN-OR-MINT exactly like the Focus screen (AppViewModel.startFocus):
     *  an occurrence runs on the TEMPLATE with the day's block remembered. */
    override suspend fun startFocus(taskId: String, estimateMin: Int?, occurrenceBlockId: String?) {
        val t = getTasks().firstOrNull { it.id == taskId } ?: return
        val row = t.copy(id = occurrenceBlockId ?: t.id, estimateMin = estimateMin ?: t.estimateMin)
        vm.startFocus(row)
    }
    override suspend fun pauseFocus() { vm.mutateLiveControl { FocusTimer.pause(it, vm.nowMs()) } }
    override suspend fun resumeFocus() { vm.mutateLiveControl { FocusTimer.resume(it, vm.nowMs()) } }
    override suspend fun extendFocus(minutes: Int) { vm.mutateLiveControl { FocusTimer.extend(it, minutes) } }
    override suspend fun cancelFocus() { vm.cancelFocusNow() }

    // ── navigation ──
    override fun navigate(screen: String, id: String?) { vm.openDeepLink(assistantScreenLink(screen, id)) }

    // ── areas + tags (rename/delete cascade like the web's use-life-areas / use-tags) ──
    override suspend fun addArea(name: String, color: String?) {
        val next = (getAreaRows().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val a = LifeArea(newUuid(), name, color ?: "indigo", next)
        write?.upsertLifeArea(a) ?: store.upsert(Tables.LIFE_AREAS, a, LifeArea.serializer(), a.id)
    }
    override suspend fun updateArea(id: String, name: String?, color: String?) {
        val row = getAreaRows().firstOrNull { it.id == id } ?: return
        val next = row.copy(name = name ?: row.name, color = color ?: row.color)
        write?.upsertLifeArea(next) ?: store.upsert(Tables.LIFE_AREAS, next, LifeArea.serializer(), next.id)
        if (name != null && name != row.name) {
            for (t in getTasks().filter { it.lifeArea == row.name }) upsertTask(t.copy(lifeArea = name, updatedAt = nowIso()))
        }
    }
    override suspend fun removeArea(id: String) {
        val row = getAreaRows().firstOrNull { it.id == id }
        write?.deleteLifeArea(id) ?: store.delete(Tables.LIFE_AREAS, id)
        // "its tasks keep everything else" — they just lose the label.
        if (row != null) for (t in getTasks().filter { it.lifeArea == row.name }) upsertTask(t.copy(lifeArea = null, updatedAt = nowIso()))
    }
    override suspend fun addTag(name: String) {
        val next = (getTagRows().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val row = TagRow(newUuid(), name, null, next)
        write?.upsertTag(row) ?: store.upsert(Tables.TAGS, row, TagRow.serializer(), row.id)
    }
    override suspend fun updateTag(id: String, name: String?) {
        val row = getTagRows().firstOrNull { it.id == id } ?: return
        val next = row.copy(name = name ?: row.name)
        write?.upsertTag(next) ?: store.upsert(Tables.TAGS, next, TagRow.serializer(), next.id)
        if (name != null && name != row.name) {
            for (t in getTasks().filter { t -> t.tags?.any { it.equals(row.name, ignoreCase = true) } == true }) {
                val tags = t.tags.orEmpty().map { if (it.equals(row.name, ignoreCase = true)) name else it }.distinctBy { it.lowercase() }
                upsertTask(t.copy(tags = tags, updatedAt = nowIso()))
            }
        }
    }
    override suspend fun removeTag(id: String) {
        val row = getTagRows().firstOrNull { it.id == id }
        write?.deleteTag(id) ?: store.delete(Tables.TAGS, id)
        if (row != null) {
            for (t in getTasks().filter { t -> t.tags?.any { it.equals(row.name, ignoreCase = true) } == true }) {
                upsertTask(t.copy(tags = t.tags?.filterNot { it.equals(row.name, ignoreCase = true) }?.ifEmpty { null }, updatedAt = nowIso()))
            }
        }
    }

    // ── settings ──
    /** The budget lives on the server (`user_preferences.usable_minutes_*`), so
     *  the server write IS the change — awaited; the REAL outcome is returned. */
    override suspend fun setUsableMinutes(weekday: Int?, weekend: Int?): Boolean {
        val prefs = vm.assistantPreferencesClient ?: return false
        val uid = currentUserId() ?: return false
        return runCatching { prefs.setUsableMinutes(uid, weekday, weekend) }.isSuccess
    }
    /** Local settings write (the alarms re-arm off it) + the queued server mirror
     *  AppViewModel.updateSettings already runs — the local write is the change. */
    override suspend fun setNotificationLevel(level: String): Boolean {
        val mapped = when (level) { "calm" -> NotificationLevel.CALM; "coach" -> NotificationLevel.COACH; else -> NotificationLevel.BALANCED }
        vm.updateSettings { it.copy(notificationLevel = mapped) }
        return vm.settings.value.notificationLevel == mapped
    }
    override suspend fun setReminderLead(minutes: Int): Boolean {
        vm.updateSettings { it.copy(reminderLeadMin = minutes) }
        return vm.settings.value.reminderLeadMin == minutes
    }
    override fun setRitual(ritual: String, on: Boolean) {
        val key = RitualKey.fromRaw(ritual) ?: return
        vm.setRitual(key, on)
    }

    // ── calls ──
    override fun currentUserId(): String? = vm.currentUid()
    override fun callStore(): AssistantCallStore? {
        val client = vm.assistantSupabaseClient ?: return null
        // With the local `call_requests` mirror attached, get_calls / the receipt's
        // cancel round-trip / the task editor read from Room (offline-safe, no
        // round trip) while every WRITE still goes direct to PostgREST.
        return CallsClientStore(CallsClient(client, CallRequestsMirror(vm.assistantStore)), ::newUuid)
    }
}

/** The deep link MainScaffold routes for an `open_screen` call — the contract's
 *  12 screens (+ the web's aliases), with the optional task / list id. */
fun assistantScreenLink(screen: String, id: String?): String = when (screen) {
    // NOT bare `unstuck://tasks` — that link is share-notify's ping, which
    // deliberately lands on Today (it hosts "Shared with you" / "Delegated").
    "tasks" -> if (id != null) "unstuck://task/$id" else "unstuck://tasks/all"
    "lists", "collections" -> if (id != null) "unstuck://collections/$id" else "unstuck://collections"
    "today", "dashboard", "home" -> "unstuck://today"
    "calendar", "day" -> "unstuck://calendar"
    "week" -> "unstuck://calendar/week"
    "month" -> "unstuck://calendar/month"
    "focus" -> "unstuck://focus"
    "insights", "analytics" -> "unstuck://insights"
    "captures", "inbox" -> "unstuck://captures"
    "settings" -> "unstuck://settings"
    "people" -> "unstuck://settings/people"
    "notifications" -> "unstuck://notifications"
    "areas" -> "unstuck://settings/areas"
    else -> "unstuck://today"
}
