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
import tech.csalliance.unstuck.core.model.ThemePref
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
//
// Every mutation answers the REAL outcome (2026-09-20 tooling rewrite): the
// `…Now` entries on AppViewModel return whether the row was written, and this
// seam passes that through instead of swallowing it.

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
    override suspend fun notifyTaskCompletedIfShared(t: TaskItem) { vm.notifyTaskDoneIfShared(t) }
    override suspend fun upsertBlock(b: CalBlock) { write?.upsertCalBlock(b) ?: store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id) }
    /** The mint path the UI takes (WriteThrough.insertCalBlockIfAbsent): never over
     *  a row with the id; rule H for the user's own day; its Google push waits for
     *  the server's answer (stage 2). */
    override suspend fun insertBlockIfAbsent(b: CalBlock, retimeIfTaken: Boolean): Boolean {
        write?.let { return it.insertCalBlockIfAbsent(b, retimeIfTaken).landed }
        if (store.getOne(Tables.CAL_BLOCKS, b.id, CalBlock.serializer()) != null) return false
        store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
        return true
    }
    override suspend fun deleteBlock(id: String) { write?.deleteCalBlock(id) ?: store.delete(Tables.CAL_BLOCKS, id) }
    /** The per-task lead lives in device prefs (reminders fire from on-device
     *  alarms) — the same store the task sheet's "Remind me" chips write. */
    override fun getTaskReminder(taskId: String): Int? = vm.reminderOverride(taskId)
    override fun setTaskReminder(taskId: String, minutes: Int?): Boolean = vm.setReminderOverrideNow(taskId, minutes)

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
    // Every list write below AWAITS the local commit (the `…Now` entries). The
    // fire-and-forget UI variants only *launched* the write, so a read tool in
    // the SAME round ("add milk to the shopping list and read it back") rendered
    // the list without the change — breaking this interface's own
    // "RETURN ONLY AFTER THE LOCAL ROW IS COMMITTED" rule (review section 4).
    override suspend fun addCollectionItem(collectionId: String, body: String): String? {
        val c = collection(collectionId) ?: return null
        return vm.addCollectionItemNow(c, body)
    }
    override suspend fun promoteItemToTask(collectionId: String, itemId: String, loop: Boolean, dueAt: String?): String? {
        val c = collection(collectionId) ?: return null
        val item = c.items.firstOrNull { it.id == itemId } ?: return null
        return vm.moveItemToTaskNow(c, item, if (loop) AppViewModel.PromoteMode.LOOP else AppViewModel.PromoteMode.SELF, dueAt)?.id
    }
    override suspend fun renameCollection(id: String, name: String): Boolean {
        val c = collection(id) ?: return false
        return vm.renameCollectionNow(c, name)
    }
    override suspend fun updateCollection(id: String, archived: Boolean?, color: String?): Boolean {
        val c = collection(id) ?: return false
        var ok = true
        if (archived != null) ok = vm.archiveCollectionNow(id, archived) && ok
        if (color != null) ok = vm.recolorCollectionNow(c, color) && ok
        return ok
    }
    override suspend fun removeCollection(id: String): Boolean = vm.deleteCollectionNow(id)
    override suspend fun updateCollectionItem(collectionId: String, itemId: String, body: String?, done: Boolean?): Boolean {
        val c = collection(collectionId) ?: return false
        val item = c.items.firstOrNull { it.id == itemId } ?: return false
        var ok = true
        if (body != null) ok = vm.updateCollectionItemBodyNow(c, itemId, body) && ok
        // The UI only has a toggle — flip only when the desired state differs.
        if (done != null && (item.done ?: false) != done) ok = vm.toggleCollectionItemDoneNow(c, itemId) && ok
        return ok
    }
    override suspend fun setCollectionItemPinned(collectionId: String, itemId: String, pinned: Boolean): Boolean {
        val c = collection(collectionId) ?: return false
        if (c.items.none { it.id == itemId }) return false
        return vm.setCollectionItemPinnedNow(c, itemId, pinned)
    }
    override suspend fun removeCollectionItem(collectionId: String, itemId: String): Boolean {
        val c = collection(collectionId) ?: return false
        return vm.removeCollectionItemNow(c, itemId)
    }
    /** TRUE only when the server confirmed it (the list screen's own rule). */
    override suspend fun leaveCollection(id: String): Boolean = vm.leaveCollection(id)
    override suspend fun canEditCollection(id: String): Boolean {
        val c = collection(id) ?: return false
        return vm.canEdit(c)
    }
    /** The SAME predicate the list screen gates rename / archive / delete on. */
    override suspend fun isCollectionOwner(id: String): Boolean {
        val c = collection(id) ?: return false
        return vm.isOwner(c)
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
    /** The account-wide flag (pinned from the server on every pull). */
    override fun interviewPending(): Boolean = !vm.interviewDone.value
    /** Local flag + resume step dropped + the server — exactly what the in-thread picker does. */
    override fun markInterviewDone(): Boolean = vm.markInterviewDoneNow()

    // ── captures ──
    override suspend fun getCaptures(): List<Capture> = store.snapshot(Tables.CAPTURES, Capture.serializer())
    override fun getArchivedCaptureIds(): Set<String> = vm.archivedCaptureIds.value
    override suspend fun upsertCapture(c: Capture) { write?.upsertCapture(c) ?: store.upsert(Tables.CAPTURES, c, Capture.serializer(), c.id) }
    override suspend fun removeCapture(id: String) { vm.deleteCaptureNow(id) }
    override fun archiveCapture(id: String, archived: Boolean): Boolean = if (archived) vm.archiveCapture(id) else vm.unarchiveCapture(id)

    // ── focus (the same FocusTimer transitions the Focus screen runs) ──
    override suspend fun getLiveFocus(): LiveSession? = store.getLiveSession()
    /** JOIN-OR-MINT exactly like the Focus screen (AppViewModel.startFocus):
     *  an occurrence runs on the TEMPLATE with the day's block remembered. */
    override suspend fun startFocus(taskId: String, estimateMin: Int?, occurrenceBlockId: String?): Boolean {
        val t = getTasks().firstOrNull { it.id == taskId } ?: return false
        val row = t.copy(id = occurrenceBlockId ?: t.id, estimateMin = estimateMin ?: t.estimateMin)
        return vm.startFocusNow(row)
    }
    override suspend fun pauseFocus(): Boolean = vm.mutateLiveControl { FocusTimer.pause(it, vm.nowMs()) }
    override suspend fun resumeFocus(): Boolean = vm.resumeFocusNow()
    override suspend fun extendFocus(minutes: Int): Boolean = vm.mutateLiveControl { FocusTimer.extend(it, minutes) }
    /** The Focus screen's Done (markDone) / Stop here path: finishFocusNow logs
     *  the Session row + totalFocused, then the timer notification and the
     *  paused check-in come down as the screen's own exit would do. */
    override suspend fun finishFocus(markDone: Boolean): Boolean {
        val live = store.getLiveSession()?.takeIf { it.sessionStart != null } ?: return false
        // A session on a task shared WITH the user isn't in this store — finishFocusNow
        // takes its shared branch and never reads the row, so a stand-in carries the id.
        // So does an own task deleted elsewhere mid-session: finishFocusNow then ends
        // the session with no task write and a Session without the dead task id —
        // this used to answer "nothing was running" and leave it live (parity with
        // iOS build 81, audit 2026-09-22 C5).
        val task = getTasks().firstOrNull { it.id == live.taskId }
            ?: TaskItem(id = live.taskId, name = live.sharedTitle ?: "Focus session", estimateMin = live.sessionEstimateMin, createdAt = nowIso(), updatedAt = nowIso())
        sharedFinish = null
        if (!vm.finishFocusNow(task, markDone) { refusal -> sharedFinish = live.taskId to refusal }) return false
        vm.tearDownFocusSurfaces()
        return true
    }
    /** The last shared finish's tick outcome (task id → why it didn't land). */
    private var sharedFinish: Pair<String, String?>? = null
    override fun sharedFinishRefusal(taskId: String): String? = sharedFinish?.takeIf { it.first == taskId }?.second
    override suspend fun cancelFocus(): Boolean = vm.cancelFocusNow()

    // ── navigation ──
    override fun navigate(screen: String, id: String?) { vm.openDeepLink(assistantScreenLink(screen, id)) }

    // ── areas + tags ──
    // The rename/delete cascade onto tasks lives in AppViewModel, shared with the
    // Settings rows, so there is one serialized path (parity with iOS build 81, audit
    // 2026-09-22 C19).
    override suspend fun addArea(name: String, color: String?): Boolean {
        val next = (getAreaRows().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val a = LifeArea(newUuid(), name, color ?: "indigo", next)
        write?.upsertLifeArea(a) ?: store.upsert(Tables.LIFE_AREAS, a, LifeArea.serializer(), a.id)
        return true
    }
    override suspend fun updateArea(id: String, name: String?, color: String?): Boolean {
        if (name != null && !vm.renameLifeAreaNow(id, name)) return false
        if (color == null) return true
        // Re-read after the rename so the colour write keeps the new name.
        val row = getAreaRows().firstOrNull { it.id == id } ?: return false
        val next = row.copy(color = color)
        write?.upsertLifeArea(next) ?: store.upsert(Tables.LIFE_AREAS, next, LifeArea.serializer(), next.id)
        return true
    }
    /** "its tasks keep everything else" — the cascade just clears the label. */
    override suspend fun removeArea(id: String): Boolean = vm.deleteLifeAreaNow(id)
    override suspend fun addTag(name: String): Boolean {
        val next = (getTagRows().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val row = TagRow(newUuid(), name, null, next)
        write?.upsertTag(row) ?: store.upsert(Tables.TAGS, row, TagRow.serializer(), row.id)
        return true
    }
    override suspend fun updateTag(id: String, name: String?): Boolean =
        if (name != null) vm.renameTagNow(id, name) else getTagRows().any { it.id == id }
    override suspend fun removeTag(id: String): Boolean = vm.deleteTagNow(id)

    // ── settings ──
    /** What the Settings screen shows. Usable minutes are server-only on
     *  Android (no local read exists yet) — reported as unknown, never guessed. */
    override fun getSettings(): AssistantSettingsSnapshot {
        val s = vm.settings.value
        val r = vm.rituals.value
        return AssistantSettingsSnapshot(
            notificationLevel = s.notificationLevel.wire,
            reminderLeadMin = s.reminderLeadMin,
            usableWeekdayMin = null,
            usableWeekendMin = null,
            focusDefaultMin = s.focusDefaultMin,
            focusOverrunMin = s.focusOverrunMin,
            focusSoftExit = s.focusSoftExit,
            focusPauseReasons = s.focusPauseReasons,
            theme = s.theme.name.lowercase(),
            ambient = s.ambient,
            rituals = RitualKey.entries.associate { it.raw to r[it] },
        )
    }
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
    /** The local cache is the change (the server push is queued + retried);
     *  read back so a stale key answers false, never `ok:`. */
    override fun setRitual(ritual: String, on: Boolean): Boolean {
        val key = RitualKey.fromRaw(ritual) ?: return false
        vm.setRitual(key, on)
        return vm.rituals.value[key] == on
    }
    override fun setTheme(theme: String): Boolean {
        val t = ThemePref.entries.firstOrNull { it.name.equals(theme, ignoreCase = true) } ?: return false
        vm.updateSettings { it.copy(theme = t) }
        return vm.settings.value.theme == t
    }
    override fun setAmbientSound(sound: String): Boolean {
        vm.updateSettings { it.copy(ambient = sound) }
        return vm.settings.value.ambient == sound
    }
    override fun setFocusDefaults(defaultMinutes: Int?, overrunMinutes: Int?, softExit: Boolean?, pauseReasons: Boolean?): Boolean {
        vm.updateSettings {
            it.copy(
                focusDefaultMin = defaultMinutes ?: it.focusDefaultMin,
                focusOverrunMin = overrunMinutes ?: it.focusOverrunMin,
                focusSoftExit = softExit ?: it.focusSoftExit,
                focusPauseReasons = pauseReasons ?: it.focusPauseReasons,
            )
        }
        val s = vm.settings.value
        return (defaultMinutes == null || s.focusDefaultMin == defaultMinutes) &&
            (overrunMinutes == null || s.focusOverrunMin == overrunMinutes) &&
            (softExit == null || s.focusSoftExit == softExit) &&
            (pauseReasons == null || s.focusPauseReasons == pauseReasons)
    }

    // ── calls ──
    override fun currentUserId(): String? = vm.currentUid()
    override fun callSettings(): tech.csalliance.unstuck.core.logic.CallSettings = vm.callSettings.value
    override fun callStore(): AssistantCallStore? {
        val client = vm.assistantSupabaseClient ?: return null
        // With the local `call_requests` mirror attached, get_calls / the receipt's
        // cancel round-trip / the task editor read from Room (offline-safe, no
        // round trip) while every WRITE still goes direct to PostgREST.
        return CallsClientStore(CallsClient(client, CallRequestsMirror(vm.assistantStore)), ::newUuid)
    }
}

/** The deep link MainScaffold routes for an `open_screen` call — the registry's
 *  screens (+ the web's aliases), with the optional task / list id. */
fun assistantScreenLink(screen: String, id: String?): String = when (screen) {
    // NOT bare `unstuck://tasks` — that link is share-notify's ping, which
    // deliberately lands on Today (it hosts "Shared with you" / "Delegated").
    // The task the model named — a series opens its own editor, not a day's
    // occurrence (owner decision, audit 2026-09-22 C3).
    "tasks" -> if (id != null) tech.csalliance.unstuck.core.logic.exactTaskLink(id) else "unstuck://tasks/all"
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
