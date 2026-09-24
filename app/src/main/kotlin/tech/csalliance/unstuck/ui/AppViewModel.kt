package tech.csalliance.unstuck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.WireTime
import tech.csalliance.unstuck.sync.AssistantResult
import tech.csalliance.unstuck.sync.PreferencesClient
import tech.csalliance.unstuck.sync.ProfileFactsService
import tech.csalliance.unstuck.core.logic.InterviewFlag
import tech.csalliance.unstuck.core.logic.labelNameTaken
import tech.csalliance.unstuck.ui.onboarding.OnboardingGate
import tech.csalliance.unstuck.surface.FocusCommands
import tech.csalliance.unstuck.core.logic.relabelingArea
import tech.csalliance.unstuck.core.logic.renamingTag
import tech.csalliance.unstuck.core.logic.strippingTag
import tech.csalliance.unstuck.core.logic.PAPrefsLogic
import tech.csalliance.unstuck.core.logic.ProfileFactsLogic
import tech.csalliance.unstuck.core.logic.ReceiptIcon
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.sync.ToolCall
import tech.csalliance.unstuck.sync.ToolFunction
import tech.csalliance.unstuck.core.logic.ReceiptUndo
import tech.csalliance.unstuck.ui.assistant.AppViewModelAssistantApi
import tech.csalliance.unstuck.ui.assistant.AssistantApi
import tech.csalliance.unstuck.ui.assistant.InterviewHost
import tech.csalliance.unstuck.ui.assistant.FactsHost
import tech.csalliance.unstuck.ui.assistant.GatewayActions
import tech.csalliance.unstuck.ui.assistant.GatewayDerived
import tech.csalliance.unstuck.ui.assistant.GatewayInputs
import tech.csalliance.unstuck.ui.assistant.GatewayMemo
import tech.csalliance.unstuck.ui.assistant.GatewayWrites
import tech.csalliance.unstuck.ui.assistant.canonicalStruggles
import tech.csalliance.unstuck.ui.assistant.deriveGateway
import tech.csalliance.unstuck.core.logic.Moment
import tech.csalliance.unstuck.core.logic.MomentAction
import tech.csalliance.unstuck.core.logic.MomentRun
import tech.csalliance.unstuck.core.logic.addDaysIso
import tech.csalliance.unstuck.core.logic.AssistantHarness
import tech.csalliance.unstuck.core.logic.AssistantHarnessRules
import tech.csalliance.unstuck.core.logic.HarnessAsk
import tech.csalliance.unstuck.core.logic.HarnessAskFailed
import tech.csalliance.unstuck.core.logic.HarnessMessage
import tech.csalliance.unstuck.core.logic.HarnessReply
import tech.csalliance.unstuck.core.logic.HarnessToolCall
import tech.csalliance.unstuck.core.logic.HarnessToolRunner
import tech.csalliance.unstuck.ui.assistant.ToolArgs
import tech.csalliance.unstuck.ui.assistant.TurnScratch
import tech.csalliance.unstuck.ui.assistant.buildAssistantContext
import tech.csalliance.unstuck.ui.assistant.buildTextRequestContext
import tech.csalliance.unstuck.ui.assistant.buildVoiceInstructions
import tech.csalliance.unstuck.ui.assistant.buildVoiceOpening
import tech.csalliance.unstuck.ui.assistant.runAssistantTool
import tech.csalliance.unstuck.ui.assistant.confirmTargetName
import tech.csalliance.unstuck.ui.assistant.talkVoiceToolsJson
import tech.csalliance.unstuck.ui.assistant.callVoiceToolsJson
import tech.csalliance.unstuck.ui.assistant.CallToolLogic
import tech.csalliance.unstuck.calls.CallSettingsStore
import tech.csalliance.unstuck.core.logic.CallScript
import tech.csalliance.unstuck.core.logic.CallProactivePrefs
import tech.csalliance.unstuck.core.logic.CallProactiveSync
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.core.logic.CallSettingsLogic
import tech.csalliance.unstuck.core.logic.TestCallLogic
import tech.csalliance.unstuck.sync.CallRequest
import tech.csalliance.unstuck.sync.CallRequestsMirror
import tech.csalliance.unstuck.sync.CallsClient
import tech.csalliance.unstuck.sync.CalendarConnectOutcome
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
import tech.csalliance.unstuck.core.logic.DivergenceResolution
import tech.csalliance.unstuck.core.logic.PendingShare
import tech.csalliance.unstuck.core.logic.Receipt
import tech.csalliance.unstuck.core.logic.ShareCandidate
import tech.csalliance.unstuck.core.logic.ShareOutcome
import tech.csalliance.unstuck.core.logic.assistantModelWindow
import tech.csalliance.unstuck.core.logic.assistantPersistWindow
import tech.csalliance.unstuck.core.logic.deriveReceipt
import tech.csalliance.unstuck.core.logic.ReceiptUndoKind
import tech.csalliance.unstuck.core.logic.UndoState
import tech.csalliance.unstuck.core.logic.factSaveUndo
import tech.csalliance.unstuck.core.logic.receiptUndoRefusal
import tech.csalliance.unstuck.core.logic.advanceReceiptUndo
import tech.csalliance.unstuck.core.logic.stampReceiptUndo
import tech.csalliance.unstuck.core.logic.resolveShareRequest
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.SharedSessionState
import tech.csalliance.unstuck.core.logic.accruesViaSharedLedger
import tech.csalliance.unstuck.core.logic.adoptable
import tech.csalliance.unstuck.core.logic.applyCompletion
import tech.csalliance.unstuck.core.logic.bumpMoveCount
import tech.csalliance.unstuck.core.logic.canonicalElapsedSec
import tech.csalliance.unstuck.core.logic.clearLaterOnSchedule
import tech.csalliance.unstuck.core.logic.liveOccurrenceBlockForTemplate
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.occurrencesCarryingTaskDone
import tech.csalliance.unstuck.core.logic.taskAfterSettingRecurrence
import tech.csalliance.unstuck.core.logic.resolveDivergence
import tech.csalliance.unstuck.core.logic.sharedRevFloor
import tech.csalliance.unstuck.core.logic.sharedSessionStep
import tech.csalliance.unstuck.SharedFocusLedger
import tech.csalliance.unstuck.sync.SharedFocusLogResult
import tech.csalliance.unstuck.core.model.CoFocusPeer
import tech.csalliance.unstuck.core.model.CoFocusState
import tech.csalliance.unstuck.core.model.CoFocusTimer
import tech.csalliance.unstuck.core.model.coFocusFirstName
import tech.csalliance.unstuck.sync.CoFocusControl
import tech.csalliance.unstuck.sync.CollectionRpcs
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.RECURRING_SERIES_REFUSAL
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.ShareBadge
import tech.csalliance.unstuck.core.model.ShareForTask
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedBlock
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.logic.IsoRange
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.shareCanTickDone
import tech.csalliance.unstuck.core.model.shareTickErrorText
import tech.csalliance.unstuck.sync.AuthOutcome
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// The single app-wide state holder. Exposes every synced collection as a
// reactive StateFlow off the Room store, the auth state, and every write
// action (which apply the :core mutation rules then go through the sync
// engine's WriteThrough). Screens compose these with :core (visibleTasks /
// pickStartNext / analytics) in memory — same model as web + iOS.
@OptIn(kotlinx.coroutines.FlowPreview::class)   // debounce() on the widget input flow
class AppViewModel(
    private val graph: AppGraph,
    // --- TEST SEAMS (additive, optional) ---
    // All default to null → production `AppViewModel(graph)` is byte-identical to
    // before (every getter below falls through to the original graph/clock expression).
    // A unit test can inject a real WriteThrough over an in-memory LocalStore plus a
    // controllable identity + clock to exercise the orchestration paths offline +
    // deterministically, WITHOUT a Supabase client / network.
    private val writeOverride: tech.csalliance.unstuck.sync.WriteThrough? = null,
    private val currentUidProvider: (() -> String?)? = null,
    private val currentNameProvider: (() -> String?)? = null,
    private val nowProvider: (() -> Long)? = null,
    // Fake co-focus channel factory: when set, the session-lifetime channel comes
    // from here instead of cofocus.open() — the offline/reconnect convergence
    // tests observe broadcasts/hellos without a Supabase client. Null in prod.
    private val coFocusChannelFactory: ((taskId: String) -> tech.csalliance.unstuck.sync.CoFocusChannel?)? = null,
) : ViewModel(), InterviewHost, FactsHost {

    private val store = graph.store
    private val write get() = writeOverride ?: graph.coordinator?.write
    val auth get() = graph.coordinator?.auth
    private val share get() = graph.coordinator?.collectionShare
    private val feedback get() = graph.coordinator?.feedback
    private val assistant get() = graph.coordinator?.assistant

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

    // --- the assistant's memory (gateway A0) ---
    // Local Room is the always-on source of truth; the server `profile_facts` table
    // syncs through the outbox (push), the Hydrator (pull + tombstones) and the
    // RealtimeMirror (live). An unconfigured graph (no coordinator) runs local-only.
    val profileFactsService: ProfileFactsService by lazy { ProfileFactsService(store, write) }
    /** Active facts, newest first — fed from the store, so the assistant's saves, a
     *  hydrate from another device, a realtime tombstone and a Settings forget all
     *  land here. */
    override val profileFacts: StateFlow<List<ProfileFact>> by lazy { sf(profileFactsService.observeAll()) }
    // Gateway per-account caches — declared up here, BEFORE the session-status
    // collector's init block: a StateFlow collect on Main.immediate can run its
    // lambda synchronously during construction, and that lambda reloads these.
    private val paPrefs by lazy {
        graph.appContext.getSharedPreferences("unstuck.pa", android.content.Context.MODE_PRIVATE)
    }
    private fun paKey(base: String, uid: String) = "$base.$uid"
    private val _callSettings = MutableStateFlow(tech.csalliance.unstuck.core.logic.CallSettings())
    /** "Calls from Unstuck" — this account's device-local kill-switch, allowed
     *  hours and default lead (calls/CallSettingsStore, same per-uid file as the
     *  rituals; scrubbed at sign-out). Push.kt's decide() reads it on receipt. */
    val callSettings: StateFlow<tech.csalliance.unstuck.core.logic.CallSettings> = _callSettings.asStateFlow()

    /** The account's proactive calls (morning plan / evening wrap-up / check-in
     *  after a block) — `notification_preferences.call_*`, cached per uid
     *  (CallSettingsStore.loadProactive) and reconciled after every pull. */
    private val _callProactive = MutableStateFlow(CallProactivePrefs.DEFAULTS)
    val callProactivePrefs: StateFlow<CallProactivePrefs> = _callProactive.asStateFlow()

    /** The one-time Settings › Calls "allow full-screen calls" nudge was dismissed. */
    private val _ringNudgeDismissed = MutableStateFlow(false)
    val ringNudgeDismissed: StateFlow<Boolean> = _ringNudgeDismissed.asStateFlow()
    private val _receiptUndosInFlight = MutableStateFlow<Set<String>>(emptySet())
    /** Receipt undos whose round-trip is still running (keyed
     *  [tech.csalliance.unstuck.ui.assistant.receiptUndoKey]) — the CANCEL_CALL
     *  undo is a network write, so its control reads "cancelling…" meanwhile
     *  ([tech.csalliance.unstuck.ui.assistant.receiptUndoLabel]). */
    val receiptUndosInFlight: StateFlow<Set<String>> = _receiptUndosInFlight.asStateFlow()
    private val _receiptUndoNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Why an Undo was refused, keyed [tech.csalliance.unstuck.ui.assistant.receiptUndoKey]
     *  — the receipt card says it instead of the Undo control (Android audit
     *  2026-09-23, A17). In memory only: the check runs again on every tap. */
    val receiptUndoNotes: StateFlow<Map<String, String>> = _receiptUndoNotes.asStateFlow()
    private val _rituals = MutableStateFlow(RitualPrefs.DEFAULTS)
    /** Which recurring PA moments run (Settings / interview picker / moments engine). */
    override val rituals: StateFlow<RitualPrefs> = _rituals.asStateFlow()
    private val _dismissedMoments = MutableStateFlow<List<String>>(emptyList())
    /** Moment ids dismissed on THIS device (newest 200). */
    val dismissedMoments: StateFlow<List<String>> = _dismissedMoments.asStateFlow()
    private val _interviewDone = MutableStateFlow(false)
    /** The get-to-know-you interview is done for this account (local flag, pinned
     *  from the server after every pull). */
    override val interviewDone: StateFlow<Boolean> = _interviewDone.asStateFlow()
    private val _profileFactsHydrated = MutableStateFlow(false)
    /** "Profile facts hydrated once" — flips after the FIRST completed pull of this
     *  sign-in, once the server's interview flag + rituals have been applied. The
     *  gateway's auto-open gate waits on it before an empty memory means "never met". */
    val profileFactsHydrated: StateFlow<Boolean> = _profileFactsHydrated.asStateFlow()
    private var ritualsPushGen = 0
    private val _struggles = MutableStateFlow<List<String>>(emptyList())
    /** The account's onboarding struggles, canonical ("Starting", …) — the
     *  moments engine + the assistant context read these. */
    val struggles: StateFlow<List<String>> = _struggles.asStateFlow()
    private val _momentDone = MutableStateFlow<String?>(null)
    /** The ✓ confirmation after a moment action — a moment, not a mute button:
     *  the card clears it after ~8 s so the NEXT undismissed moment can surface. */
    val momentDone: StateFlow<String?> = _momentDone.asStateFlow()

    init { reloadAssistantUserState() }
    val pendingCount = store.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val configured = graph.configured

    // --- sharing projections (M2/M3) ---
    // Both RPC-backed (a recipient has NO RLS read on the raw task row): tasks OTHERS
    // shared WITH me (tasksSharedWithMe) and the badges on MY OWN outgoing shares
    // (myTaskShareBadges → row chips + the Delegated group). Each refetches on the
    // CollabRealtime `sharesChanged` signal (a task_shares row I can see changed — my
    // outgoing OR incoming) AND after my own writes (the manual pulse), exactly like
    // `circle` on the circleChanged signal — and on session edges and completed pulls
    // ([shareRereads], Android audit 2026-09-23, A16). Declared HERE (above the widget init that
    // reads assignedOut) so property init order is safe. circleClient/collab are
    // custom getters (no backing field) → safe to reference before their textual decl.
    private val _sharesRefresh = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** taskId → the ISO time I ticked a shared task done on THIS device. The
     *  tasks_shared_with_me projection only carries completed_at from migration 049,
     *  so without this a share I just completed would drop straight out of "All"
     *  instead of lingering as today's win. Overlaid ONLY onto rows the server
     *  already reports as done, so a failed write can never fake a completion.
     *  Mirrors the optimistic stamp in the web useSharedWithMe.setDone. */
    private val _sharedCompletedAt = MutableStateFlow<Map<String, String>>(emptyMap())

    // A failed read keeps what is on screen (for the same account) instead of
    // blanking it — an offline refresh used to empty Shared-with-you and the
    // badges (parity with iOS build 79, audit 2026-09-22 C11). Declared above
    // the flows that use them (property init order).
    private val sharedWithMeHold = tech.csalliance.unstuck.ui.sharing.LastGoodRead<List<SharedWithMe>>(emptyList())
    private val shareBadgesHold = tech.csalliance.unstuck.ui.sharing.LastGoodRead<Map<String, List<ShareBadge>>>(emptyMap())

    /** The session's account the holds key on: the signed-in user, kept through a
     *  failed token refresh, where currentUid() reads null although [authed] still
     *  says signed in (see sessionAccount). Eager, so it has seen the last
     *  Authenticated before any refresh fails. */
    private val heldAccount: StateFlow<String?> =
        graph.provider?.client?.auth?.sessionStatus
            ?.runningFold(null as String?) { prev, status -> tech.csalliance.unstuck.ui.sharing.sessionAccount(status, prev) }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, null)
            ?: MutableStateFlow(null)

    /** Re-reads for the sharing projections beyond their realtime signal and my own
     *  writes: every session edge ([sessionRereads]: sign-in, cold-start restore,
     *  the return from the SDK's background reset, a token that works again,
     *  sign-out → empty) and every completed pull, which also lands after each
     *  resume. Without them the badges read once, before the session had loaded,
     *  and stayed empty for the whole process (Android audit 2026-09-23, A16).
     *  Declared above the flows that use it (property init order). */
    private val shareRereads: kotlinx.coroutines.flow.Flow<Unit> = merge(
        graph.provider?.client?.auth?.sessionStatus
            ?.let { tech.csalliance.unstuck.ui.sharing.sessionRereads(it, heldAccount) }
            ?: kotlinx.coroutines.flow.emptyFlow(),
        graph.coordinator?.hydrated ?: kotlinx.coroutines.flow.emptyFlow(),
    )

    /** Tasks other people have shared WITH me — the "Shared with you" group. Read via
     *  the tasks_shared_with_me projection (raw task rows are RLS-forbidden). */
    val sharedWithMe: StateFlow<List<SharedWithMe>> =
        merge(_sharesRefresh, flow { graph.coordinator?.collab?.sharesChanged?.let { emitAll(it) } }, shareRereads)
            .onStart { emit(Unit) }
            .transform { sharedWithMeHold.refreshInto(this, currentUid(), heldAccount.value) { graph.coordinator?.circle?.tasksSharedWithMe() } }
            .combine(_sharedCompletedAt) { rows, stamps ->
                if (stamps.isEmpty()) rows else rows.map { s ->
                    if (s.done && s.completedAt == null) s.copy(completedAt = stamps[s.taskId]) else s
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** My outgoing shares grouped by taskId → the row badges (mirrors the web
     *  useShareBadges().byTask). Drives the on-row "shared" chips + the Delegated group. */
    val shareBadges: StateFlow<Map<String, List<ShareBadge>>> =
        merge(_sharesRefresh, flow { graph.coordinator?.collab?.sharesChanged?.let { emitAll(it) } }, shareRereads)
            .onStart { emit(Unit) }
            .transform { shareBadgesHold.refreshInto(this, currentUid(), heldAccount.value) { graph.coordinator?.circle?.myTaskShareBadges() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** taskId → assignee name for tasks I've assigned away ('assign' level). These
     *  LEAVE my active list (→ Delegated group) and are excluded from Start-Next. */
    val assignedOut: StateFlow<Map<String, String>> =
        shareBadges.map { tech.csalliance.unstuck.core.model.assignedOutMap(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // --- shared-task calendar blocks (migration 052 shared_task_blocks) ---
    /** The calendar's visible window (inclusive ISO dates). Each calendar view points
     *  it at what it paints — Day/Week the Monday-anchored week, Month the month — via
     *  [setSharedBlockRange]; null until a calendar is on screen. */
    private val _sharedBlockRange = MutableStateFlow<IsoRange?>(null)

    /** Per-window cache so flipping Day↔Week (same week) or paging back to a month
     *  already seen is free. Invalidated on every shares-changed tick — a share
     *  added/removed/moved anywhere invalidates every window — but a window keeps its
     *  last good blocks until a read replaces them, so a failed re-read no longer
     *  blanks the calendar (Android audit 2026-09-23, A16). Only touched from the
     *  [sharedBlocks] pipeline (viewModelScope → main thread), never elsewhere. */
    private val sharedBlockWindows = tech.csalliance.unstuck.ui.sharing.SharedBlockWindows()

    /** Every block of every task shared WITH me inside the visible window — the
     *  read-only "shared" blocks on the calendars (Day / Week grids, the Month planned
     *  indicator). Never merged into [blocks]: nothing that schedules, moves, resizes,
     *  unschedules or starts focus can see them. Refetches on the CollabRealtime
     *  `sharesChanged` signal + the manual pulse (like [sharedWithMe]) AND whenever the
     *  window changes; a window already fetched since the last change is served from
     *  the cache. Empty on a pre-052 server (the RPC read degrades to empty). */
    val sharedBlocks: StateFlow<List<SharedBlock>> =
        combine(
            _sharedBlockRange,
            merge(_sharesRefresh, flow { graph.coordinator?.collab?.sharesChanged?.let { emitAll(it) } }, shareRereads)
                .onStart { emit(Unit) }
                .map { sharedBlockWindows.invalidate(); System.nanoTime() },   // a distinct value per tick → combine re-emits
        ) { range, _ -> range }
            .transform { range ->
                if (range == null) emit(emptyList())
                else sharedBlockWindows.readInto(this, range, currentUid(), heldAccount.value) {
                    graph.coordinator?.circle?.sharedTaskBlocks(range.from, range.to)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Point [sharedBlocks] at the window a calendar view paints (inclusive ISO dates;
     *  the client clamps to the RPC's 62-day cap). */
    fun setSharedBlockRange(from: String, to: String) { _sharedBlockRange.value = IsoRange(from, to) }

    /** null until the auth state resolves; true/false once known. */
    // Tri-state so AppRoot shows the splash (null) — NOT the sign-in screen —
    // while supabase restores the session from storage. Mapping every
    // non-Authenticated status to `false` made `Initializing` (the
    // load-from-storage state that fires before Authenticated on a cold start)
    // render AuthScreen for a frame before flipping to the dashboard — the
    // "sign-in flashes for a split second" bug. Keep `Initializing` as null.
    val authed: StateFlow<Boolean?> = run {
        val client = graph.provider?.client
        if (client == null) MutableStateFlow<Boolean?>(false)
        else client.auth.sessionStatus
            .map { status ->
                when (status) {
                    is SessionStatus.Authenticated -> true
                    is SessionStatus.NotAuthenticated -> false
                    // A refresh failure is transient — almost always OFFLINE with an
                    // expired access token. supabase-kt keeps the cached session and
                    // retries when connectivity returns, so treat it as still-signed-in
                    // rather than bouncing to the login screen (the iOS counterpart of
                    // this bug was emitLocalSessionAsInitialSession=false). A genuine
                    // logout / revoked session arrives as NotAuthenticated instead.
                    is SessionStatus.RefreshFailure -> true
                    is SessionStatus.Initializing -> null      // still loading from storage → stay on splash
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    /** Manual pulse for [currentNameState] — fired after a Settings rename so the
     *  greeting updates immediately (belt-and-braces beside the sessionStatus tick). */
    private val _nameRefresh = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Reactive display name — the SAME source Settings → Account reads
     *  (auth user metadata display_name/full_name via [currentName], with its
     *  email-local fallback), re-resolved on every auth session change so it
     *  fills in after cold-start hydration / sign-in and updates on rename.
     *  Null while signed out (the Today greeting then falls back to "Unstuck."). */
    val currentNameState: StateFlow<String?> = run {
        val sessionTicks: kotlinx.coroutines.flow.Flow<Unit> =
            graph.provider?.client?.auth?.sessionStatus?.map { } ?: kotlinx.coroutines.flow.emptyFlow()
        merge(sessionTicks, _nameRefresh)
            .map { currentName }
            .stateIn(viewModelScope, SharingStarted.Eagerly, currentName)
    }

    // --- helpers ---

    fun nowMs(): Long = nowProvider?.invoke() ?: System.currentTimeMillis()

    /** The phone's 12/24-hour setting, for the times this ViewModel writes into
     *  text the user sees (receipts, the harness's own closing line, the test
     *  call's refusal). Read fresh: the setting can change while we're alive. */
    internal fun clockMode(): tech.csalliance.unstuck.core.time.ClockMode =
        tech.csalliance.unstuck.ui.components.DeviceClock.mode(graph.appContext)

    // Insights: a one-shot period to open on — the Today pill's "Last week"
    // variant sets it just before navigating; the screen consumes it once.
    private var insightsOpenAt: Pair<tech.csalliance.unstuck.core.logic.InsightsSpan, Int>? = null
    fun openInsightsAt(span: tech.csalliance.unstuck.core.logic.InsightsSpan, offset: Int) { insightsOpenAt = span to offset }
    fun consumeInsightsOpenAt(): Pair<tech.csalliance.unstuck.core.logic.InsightsSpan, Int>? = insightsOpenAt.also { insightsOpenAt = null }

    /** The last cal_blocks pull hit the server's row cap (get_period_review's note). */
    internal fun calBlocksMayBeTruncated(): Boolean = graph.coordinator?.calBlocksMayBeTruncated() == true
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
        sourceCollectionId: String? = null,
        sourceItemId: String? = null,
        dueAt: String? = null,
        // Opt-in per-task shares picked in the create sheet (userId → level). Applied
        // AFTER the task row lands on the server, IN THE SAME coroutine as the upsert,
        // so task_share (which validates ownership server-side) can't race the insert
        // and drop the share (T2). Empty for callers that don't share (e.g. onboarding).
        shares: Map<String, ShareLevel> = emptyMap(),
    ): TaskItem {
        val t = newTaskRow(
            name = name, estimateMin = estimateMin, priority = priority, lifeArea = lifeArea, tags = tags,
            intentWhen = intentWhen, intentThen = intentThen, firstPhysicalAction = firstPhysicalAction,
            recurrence = recurrence, later = later, sourceCollectionId = sourceCollectionId,
            sourceItemId = sourceItemId, dueAt = dueAt,
        )
        launchWrite {
            write?.upsertTask(t)                                   // local write + enqueue (this coroutine)
            if (shares.isNotEmpty()) applyCreatedShares(t.id, shares)   // then flush + share, ordered
        }
        return t
    }

    /** The row [addTask] writes — shared with the awaited promote path
     *  ([moveItemToTaskNow]) so the two can never drift on defaults. */
    private fun newTaskRow(
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
        sourceCollectionId: String? = null,
        sourceItemId: String? = null,
        dueAt: String? = null,
    ): TaskItem {
        val now = isoNow()
        return TaskItem(
            id = newUuid(), name = name.trim(), estimateMin = estimateMin, priority = priority,
            lifeArea = lifeArea, tags = tags, intentWhen = intentWhen, intentThen = intentThen,
            firstPhysicalAction = firstPhysicalAction, recurrence = recurrence, later = later,
            sourceCollectionId = sourceCollectionId, sourceItemId = sourceItemId, dueAt = dueAt,
            createdAt = now, updatedAt = now,
        )
    }

    /** Apply the create-sheet's opt-in shares AFTER the task row has landed on the
     *  server. A task_share RPC that races the not-yet-flushed task upsert raises
     *  not_your_task and the share is silently dropped (T2, the live bug). Flush the
     *  outbox so the insert commits, verify it's no longer pending, THEN share each —
     *  logging failures instead of swallowing them. Mirrors the web create-modal
     *  (awaitPendingUpsert → pendingIdsForTable guard → task_share). */
    private suspend fun applyCreatedShares(taskId: String, picks: Map<String, ShareLevel>) {
        flushOutbox()
        // If the upsert failed (offline / 5xx), its op is still queued and the row never
        // committed server-side — firing task_share now just raises not_your_task. Bail;
        // the row lands on the next outbox replay and the user can re-share then.
        if (store.pending().any { it.recordTable == "tasks" && it.recordId == taskId && it.op == "upsert" }) {
            println("[share] task $taskId not committed yet (queued for retry) — skipping ${picks.size} share(s)")
            return
        }
        picks.forEach { (userId, level) ->
            runCatching {
                circleClient?.taskShare(taskId, userId, level)
                circleClient?.notifyTaskShare(taskId, userId)
            }.onFailure { println("[share] task_share failed for $userId (${level.wire}) on $taskId: ${it.message}") }
        }
        refreshShares()
    }

    /** Push queued writes to the server now (best-effort) — used to land a just-created
     *  task row before a share RPC. No-op when the coordinator isn't wired (tests). */
    private suspend fun flushOutbox() { runCatching { graph.coordinator?.flushOutbox() } }

    fun updateTask(task: TaskItem) = launchWrite { write?.upsertTask(task.copy(updatedAt = isoNow())) }

    fun toggleDone(task: TaskItem) = launchWrite { toggleDoneNow(task) }

    /** [toggleDone], committed before returning. */
    internal suspend fun toggleDoneNow(task: TaskItem) {
        // Defense in depth: a task the owner ASSIGNED OUT is view-only — never flip its
        // completion, even via a deep-link / command that bypasses the hidden button.
        // (Recurring occurrences are never assigned out, so their block id won't match.)
        if (assignedOut.value.containsKey(task.id)) return
        // A recurring OCCURRENCE's id is its cal_block id — complete the block,
        // never the template (which would end the whole series). Resolved from the
        // store, not the WhileSubscribed caches, which lag a remote edit or sit
        // empty with nothing on screen collecting them.
        val block = store.getOne(Tables.CAL_BLOCKS, task.id, CalBlock.serializer())
        val occ = block?.let { b ->
            occurrenceBlockFor(task.id, listOfNotNull(b.taskId?.let { store.getOne(Tables.TASKS, it, TaskItem.serializer()) }), listOf(b))
        }
        if (occ != null) {
            val nextDone = !occ.done
            write?.upsertCalBlock(occ.copy(done = nextDone, skipped = false, completedAt = if (nextDone) isoNow() else null))
            return
        }
        // Flip what the CALLER showed onto the STORED row. The caller's copy was
        // composed earlier, and writing it whole went out as a fresh edit (the
        // outbox base is the current row) that reverted a rename or a first step
        // synced in meanwhile. Only done + completedAt are this tap's change. A row
        // already in the target state needs no write (and no second shared-list
        // notice), and a row that is gone is never re-created from the copy
        // (parity with iOS build 81, audit 2026-09-22 C5).
        val prior = store.getOne(Tables.TASKS, task.id, TaskItem.serializer()) ?: return
        val target = !task.done
        // A repeating series' TEMPLATE never takes a done flip: it ENDS the series —
        // the on-device reminders and the server's calls and reminders all skip a
        // done task.
        // Judged on the STORED row so a stale copy can't slip past. A template that
        // is already done may still be reopened, to recover a series the old path
        // ended (parity with iOS build 81, audit 2026-09-22 C3).
        if (prior.recurrence != null && target) return
        if (prior.done == target) return
        write?.upsertTask(applyCompletion(prior.copy(done = target), prior = prior, nowISO = isoNow()))
        // Completing a task promoted from a shared collection item → flip the
        // shared item to "done by <name>" + notify the other members (best-effort).
        // UN-completing it → 'reopen': the item goes back to "<name>'s on it"
        // (collection-task-done, contract 2026-09) instead of staying "done by ✓".
        if (prior.sourceCollectionId != null && prior.sourceItemId != null) {
            share?.taskDone(prior.sourceCollectionId!!, prior.sourceItemId!!, prior.name, currentName ?: "Someone", action = if (target) "done" else "reopen")
        }
    }

    /** Skip ("cancel today") one recurring occurrence — hides just this day; the
     *  series keeps generating. blockId == the occurrence row's id. */
    fun skipOccurrence(blockId: String) = launchWrite {
        val b = blocks.value.firstOrNull { it.id == blockId } ?: return@launchWrite
        write?.upsertCalBlock(b.copy(skipped = true, done = false, completedAt = null))
    }

    fun setLater(task: TaskItem, later: Boolean) = launchWrite {
        write?.upsertTask(task.copy(later = later, updatedAt = isoNow()))
    }

    /** Delete a task and cascade to its cal_blocks + captures (so realtime
     *  listeners don't pull orphans back), mirroring the web deleteTask. */
    fun deleteTask(id: String) = launchWrite {
        // A loop-promoted task being deleted releases its shared item ('reopen') so
        // the list doesn't show "<name>'s on it" forever with nobody able to re-promote.
        tasks.value.firstOrNull { it.id == id }?.let { t ->
            if (t.sourceCollectionId != null && t.sourceItemId != null) {
                share?.taskDone(t.sourceCollectionId!!, t.sourceItemId!!, t.name, currentName ?: "Someone", action = "reopen")
            }
        }
        blocks.value.filter { it.taskId == id }.forEach { write?.deleteCalBlock(it.id) }
        captures.value.filter { it.taskId == id }.forEach { write?.deleteCapture(it.id) }
        write?.deleteTask(id)
    }

    /**
     * Set/clear a task's recurrence and realign its future cal_blocks, from the
     * series' OWN time and day (recurrenceEditStart: its earliest LIVE timed block,
     * the time most of its live occurrences share). The old anchor was the task's
     * OLDEST block of any kind: "every Monday at 11" rebuilt the series at a
     * history time, and on a series started 56+ days ago any repeat edit deleted
     * every future occurrence and added none (parity with iOS builds 79 and 81,
     * audit 2026-09-22 B79.1 C1).
     *
     * Returns false and writes NOTHING when a repeat is set on a task with no timed
     * block: a series needs a time of day, and the old 09:00 fallback built it from
     * TOMORROW (regenerate skips today), so the task left Today and came back at a
     * time the user never chose. The caller asks for a day and a time and starts
     * the series with [startRepeating] (parity with iOS build 81, audit 2026-09-22 C7).
     *
     * The done state crosses over with the repeat (parity with iOS build 81, audit
     * 2026-09-22 C3): "Never" on a day whose occurrences are all ticked carries the
     * tick onto the task, and a repeat turned on never leaves a DONE template — an
     * ended series. Built on the STORED row, like toggleDone (C5).
     */
    fun setRecurrence(task: TaskItem, recurrence: Recurrence?): Boolean {
        val existing = blocks.value
        val today = Clock.todayIso()
        val start = tech.csalliance.unstuck.core.logic.recurrenceEditStart(task.id, recurrence, existing, today)
        if (recurrence != null && start == null) return false
        launchWrite {
            val base = store.getOne(Tables.TASKS, task.id, TaskItem.serializer()) ?: task
            val now = isoNow()
            val updated = taskAfterSettingRecurrence(base, recurrence, existing, today, now).copy(updatedAt = now)
            write?.upsertTask(updated)
            // Without a start we are clearing the repeat, where regenerateForTask
            // ignores the time and date (it only deletes the future).
            val startDate = start?.date?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
                ?.let { tech.csalliance.unstuck.core.time.Time.civil(it[0], it[1], it[2]) }
                ?: tech.csalliance.unstuck.core.time.Time.startOfDayMillis(nowMs())
            // This month's occurrence moved later off a series day that has passed
            // stays (see RecurrenceStart.keepId) — it goes INTO the plan as kept, so
            // rule B can't move it either (stage 2).
            val plan = tech.csalliance.unstuck.core.logic.regenerateForTask(
                updated, recurrence, existing, today, start?.startTime ?: "09:00", startDate,
                start?.horizonDays ?: tech.csalliance.unstuck.core.logic.RECURRENCE_HORIZON_DAYS,
                keepIds = setOfNotNull(start?.keepId),
            )
            // The lists are disjoint (stage 2, "same id for same day", Ahmad
            // 2026-09-23): rewrites are plain saves, new occurrences are MINTS —
            // insert-if-absent with rule H, never over another device's row, and
            // mirrored to Google once the server confirms them.
            writeSeriesPlan(plan)
            // A done task made to repeat keeps the day it was done ticked, on that day's
            // occurrence — else it came back open in Today, or overdue in Backlog.
            occurrencesCarryingTaskDone(base, recurrence, existing, today, now).forEach { write?.upsertCalBlock(it) }
            // The done flip travels to a loop-promoted task's shared-list row, as the
            // UI's toggle does — else it stays ticked over an open series.
            if (updated.done != base.done && base.sourceCollectionId != null && base.sourceItemId != null) {
                share?.taskDone(base.sourceCollectionId!!, base.sourceItemId!!, base.name, currentName ?: "Someone", action = if (updated.done) "done" else "reopen")
            }
        }
        return true
    }

    /** "Start repeating" — the task editor's answer when [setRecurrence] refused a
     *  task with no timed block: the rule is saved FIRST, then [scheduleTaskNow]
     *  builds the series plus the chosen day's occurrence, today's too when today
     *  is picked. The Later un-park rides in the same row, because scheduling
     *  never clears Later on a repeating task, and a done task reopens — a series
     *  never starts as a done template (parity with iOS build 81, audit 2026-09-22
     *  C7, C3). */
    fun startRepeating(task: TaskItem, recurrence: Recurrence, date: String, startTime: String) = launchWrite {
        val now = isoNow()
        val next = taskAfterSettingRecurrence(task, recurrence, blocks.value, Clock.todayIso(), now)
            .copy(later = if (task.later == true) false else task.later, updatedAt = now)
        write?.upsertTask(next)
        scheduleTaskNow(next, date, startTime)
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
    fun scheduleTask(task: TaskItem, date: String, startTime: String, reanchor: Boolean = true) =
        launchWrite { scheduleTaskNow(task, date, startTime, reanchor) }

    /** [scheduleTask], committed before returning. [reanchor] = false is the
     *  create sheet's: its rule already carries the week one picked in
     *  "Starts", and the picked day is placed as it is (a later chip keeps it
     *  as a one-off before its weeks — web's create modal, canonical;
     *  RecurrenceEditorModel.createStart). Every other caller schedules a
     *  series "from here" and re-anchors (spec §5). */
    private suspend fun scheduleTaskNow(caller: TaskItem, date: String, startTime: String, reanchor: Boolean = true) {
        // The row as STORED, not the caller's copy: the task sheet hands over the row
        // it had when the date dialog opened, and the whole-row writes below reverted
        // an edit synced in while the pickers were up (parity with iOS build 81,
        // audit 2026-09-22 C5). A row the store doesn't have yet (the create sheet's
        // new task) or an occurrence row (a block id) keeps the caller's copy.
        val original = store.getOne(Tables.TASKS, caller.id, TaskItem.serializer()) ?: caller
        // Giving a task a real slot ends its "Later" parking — done HERE, the one
        // choke point every scheduling surface goes through (the task-detail sheet,
        // the create sheet, a calendar drop, promote-to-loop), instead of only at
        // the detail sheet's own call site as before: a task scheduled from the
        // calendar or by the assistant stayed parked, sitting on the calendar at
        // the chosen time while Today, Backlog and Start-next all filtered it out.
        // The cleared row then REPLACES `task` below, so the move-count bump's
        // whole-row upsert can't write later=true straight back.
        val cleared = clearLaterOnSchedule(original, isoNow())
        // Scheduling a series on a day means "the series starts here" (every-n-weeks
        // spec §5, Ahmad 2026-09-24): an every-N-weeks rule takes the chosen day's
        // week as week one. Only an off-week choice changes anything; the row is
        // written (with the un-park, in one write) BEFORE the plan and any top-up
        // read the rule, and every whole-row write below builds on it.
        val reanchored = if (!reanchor) null
            else tech.csalliance.unstuck.core.logic.reanchorForSchedule((cleared ?: original).recurrence, date)
        val rowWrite = reanchored?.let { (cleared ?: original).copy(recurrence = it, updatedAt = isoNow()) } ?: cleared
        rowWrite?.let { write?.upsertTask(it) }
        val task = rowWrite ?: original
        val recurrence = task.recurrence
        val existing = blocks.value.filter { it.taskId == task.id && tech.csalliance.unstuck.core.logic.isTaskBlock(it) }
        if (recurrence != null) {
            val today = tech.csalliance.unstuck.core.time.Clock.todayIso()
            val parts = date.split("-").mapNotNull { it.toIntOrNull() }
            val startDate = if (parts.size == 3) tech.csalliance.unstuck.core.time.Time.civil(parts[0], parts[1], parts[2])
            else tech.csalliance.unstuck.core.time.Time.startOfDayMillis(nowMs())
            val regen = tech.csalliance.unstuck.core.logic.regenerateForTask(task, recurrence, blocks.value, today, startTime, startDate)
            // Guarantee the user's CHOSEN slot is materialized. The horizon regen skips
            // the chosen date when it's today or off-pattern (e.g. a Tue pick on a
            // Mon/Wed/Fri weekly), so without this the task vanishes from that date —
            // despite the "Scheduled" confirmation. Decided POST-plan (a block the plan
            // is about to delete or move must not count) by recurrenceChosenDateWrite:
            // an open occurrence at another time (today's — regenerate never touches
            // it) is moved, a skipped one is moved and un-skipped, a done one leaves
            // the day alone (parity with iOS build 81, audit 2026-09-22 C7), and an
            // empty day gets its deterministic occurrence — or a block of its own when
            // that id lives on elsewhere (§3b′, stage 2, parity with iOS build 85).
            // Computed before any of the plan's writes go out; the four are disjoint.
            val (plan, chosen) = tech.csalliance.unstuck.core.logic.recurrenceChosenDateWrite(task, existing, regen, date, startTime)
            writeSeriesPlan(plan)
            writeChosenDay(chosen, task, date, startTime)
            // Only count a "move" if the series' next occurrence actually changed —
            // re-tapping Schedule at the same date/time shouldn't inflate moveCount +
            // falsely trip the slip detector. Compared with recurrenceAnchor, not the
            // earliest block: a template's earliest block is weeks-old history, so
            // every re-schedule, even a no-op, bumped it (parity with iOS build 81,
            // audit 2026-09-22 C7).
            val anchor = tech.csalliance.unstuck.core.logic.recurrenceAnchor(task.id, existing, today)
            if (anchor != null && (anchor.date != date || anchor.startTime != startTime)) write?.upsertTask(bumpMoveCount(task, isoNow()))
        } else {
            val cur = existing.minWithOrNull(compareBy({ it.date }, { it.startTime }))
            if (cur != null) {
                if (cur.date != date || cur.startTime != startTime) {
                    write?.upsertCalBlock(cur.copy(date = date, startTime = startTime))
                    write?.upsertTask(bumpMoveCount(task, isoNow()))
                }
            } else {
                write?.upsertCalBlock(CalBlock(id = newUuid(), taskId = task.id, taskName = task.name, startTime = startTime, durationMinutes = tech.csalliance.unstuck.core.logic.clampDurationMin(task.estimateMin), date = date, kind = CalBlockKind.TASK))
            }
        }
    }

    /** A series plan's writes (stage 2, "same id for same day", Ahmad 2026-09-23).
     *  Its lists are disjoint, so the order is free: deletes, in-place rewrites
     *  (plain saves), then the new occurrences as MINTS — insert-if-absent with
     *  rule H (a user's edit), never over a row with the id. */
    private suspend fun writeSeriesPlan(plan: tech.csalliance.unstuck.core.logic.RegenPlan) {
        val w = write ?: return
        plan.toDelete.forEach { w.deleteCalBlock(it) }
        plan.toRetime.forEach { w.upsertCalBlock(it) }
        plan.toUpsert.forEach { w.insertCalBlockIfAbsent(it, retimeIfTaken = true) }
    }

    /** The chosen day's write. A mint whose id turned out to be taken by the time it
     *  was written — the row appeared after the plan read the blocks (a top-up,
     *  another device's occurrence) and it is not that day's open occurrence (the
     *  local rule-H retime covers that one) — is decided again from the store as it
     *  is now: the user asked for THIS day, so it still gets its block (parity with
     *  iOS build 85's writeChosenDay, stage 2 review). */
    private suspend fun writeChosenDay(chosen: tech.csalliance.unstuck.core.logic.ChosenDateWrite, task: TaskItem, date: String, startTime: String) {
        val w = write ?: return
        when (chosen) {
            tech.csalliance.unstuck.core.logic.ChosenDateWrite.None -> Unit
            is tech.csalliance.unstuck.core.logic.ChosenDateWrite.Upsert -> w.upsertCalBlock(chosen.block)
            is tech.csalliance.unstuck.core.logic.ChosenDateWrite.Insert -> {
                if (w.insertCalBlockIfAbsent(chosen.block, retimeIfTaken = true) != tech.csalliance.unstuck.sync.WriteThrough.MintOutcome.HELD) return
                val now = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer())
                    .filter { it.taskId == task.id && tech.csalliance.unstuck.core.logic.isTaskBlock(it) }
                val empty = tech.csalliance.unstuck.core.logic.RegenPlan(emptyList(), emptyList())
                when (val again = tech.csalliance.unstuck.core.logic.recurrenceChosenDateWrite(task, now, empty, date, startTime).second) {
                    tech.csalliance.unstuck.core.logic.ChosenDateWrite.None -> Unit
                    is tech.csalliance.unstuck.core.logic.ChosenDateWrite.Upsert -> w.upsertCalBlock(again.block)
                    is tech.csalliance.unstuck.core.logic.ChosenDateWrite.Insert -> w.insertCalBlockIfAbsent(again.block, retimeIfTaken = true)
                }
            }
        }
    }

    fun unschedule(blockId: String) = launchWrite { write?.deleteCalBlock(blockId) }

    /** Reschedule / resize an existing block (drag or the block-edit sheet). */
    fun moveBlock(block: CalBlock, date: String, startTime: String) = launchWrite {
        val moved = block.copy(date = date, startTime = startTime)
        write?.upsertCalBlock(moved)
        // Dragging a task onto a slot is scheduling it, so it ENDS the "Later"
        // parking here too — scheduleTaskNow is not the only scheduling surface,
        // and a parked task dragged on the calendar used to sit there while
        // Today, Backlog and Start-next all filtered it out as deferred.
        val owner = block.taskId?.let { id -> tasks.value.firstOrNull { it.id == id } }
        val unparked = owner?.let { clearLaterOnSchedule(it, isoNow()) }
        // Bump the owning task's moveCount on a real move (web parity) so the slip
        // detector / analytics count every drag-reschedule. Built from the CLEARED
        // row: the bump is a whole-row upsert, so bumping the pre-un-park snapshot
        // would write later=true straight back over it.
        val bumping = (block.date != date || block.startTime != startTime) && owner != null
        when {
            bumping -> write?.upsertTask(bumpMoveCount(unparked ?: owner!!, isoNow()))
            unparked != null -> write?.upsertTask(unparked)
        }
    }
    fun resizeBlock(block: CalBlock, durationMinutes: Int) = launchWrite {
        write?.upsertCalBlock(block.copy(durationMinutes = durationMinutes.coerceIn(15, 360)))
    }

    // --- google calendar ---
    /** Begin OAuth consent — returns the authorize URL to open in a Custom Tab. */
    suspend fun beginGoogleConnect(): String? = graph.coordinator?.beginGoogleConnect()
    /** Pull external events now ("Sync now"; forgets any 429 back-off first). False =
     *  the server or Google could not be read — the bar says so instead of ending
     *  silently (parity with iOS build 81, audit 2026-09-22 C18). */
    suspend fun syncCalendar(): Boolean = graph.coordinator?.pullCalendar(manual = true) ?: false
    /** A Google 429 is being waited out: a failed "Sync now" reads "busy", not offline. */
    val calendarBackedOff: Boolean get() = graph.coordinator?.calendarBackedOff ?: false
    /** The in-app connect's result, held until the calendar bar shows it. */
    val calendarConnectOutcome: StateFlow<CalendarConnectOutcome?> =
        graph.coordinator?.calendarConnectOutcome ?: MutableStateFlow(null)
    fun consumeCalendarConnectOutcome() { graph.coordinator?.consumeCalendarConnectOutcome() }
    fun disconnectCalendar(id: String) = launchWrite { graph.coordinator?.disconnectCalendar(id) }

    // --- reminders (pre-task "remind me N min before"; device-local) ---
    /** Per-task reminder lead override in minutes, or null to use the global default. */
    fun reminderOverride(taskId: String): Int? = graph.settings.reminderOverride(taskId)
    fun setReminderOverride(taskId: String, leadMin: Int?) = graph.settings.setReminderOverride(taskId, leadMin)
    /** The assistant's set_task_reminder (2026-09-20): the same prefs write the
     *  task sheet's "Remind me" chips make, then the alarms re-armed the way the
     *  sheet does it. TRUE when the override reads back as set. */
    internal fun setReminderOverrideNow(taskId: String, leadMin: Int?): Boolean {
        graph.settings.setReminderOverride(taskId, leadMin)
        (graph.appContext as? tech.csalliance.unstuck.UnstuckApp)?.let { app ->
            runCatching { tech.csalliance.unstuck.surface.ReminderScheduler.reschedule(app) }
        }
        return graph.settings.reminderOverride(taskId) == leadMin
    }

    // --- notification deep links (set by MainActivity from the launch intent) ---
    val pendingDeepLink: StateFlow<String?> get() = graph.pendingDeepLink
    fun consumeDeepLink() { graph.pendingDeepLink.value = null }
    /** Route an in-app tap (e.g. a non-task notification-center row) through the same
     *  pendingDeepLink handler MainScaffold uses for push taps. */
    fun openDeepLink(link: String) { graph.pendingDeepLink.value = link }

    // --- password recovery (from a "forgot password" email deep link) ---
    val pendingPasswordRecovery: StateFlow<Boolean> get() = graph.pendingPasswordRecovery
    fun consumeRecovery() { graph.pendingPasswordRecovery.value = false }
    /** Why the last auth-callback link couldn't sign in (MainActivity; AuthScreen shows it once). */
    val authLinkError: StateFlow<String?> get() = graph.authLinkError
    fun consumeAuthLinkError() { graph.authLinkError.value = null }
    /** A forgot-password session carries amr method "recovery" (GoTrue stamps it on
     *  the recovery verification). PKCE recovery deep links have no `type=recovery` in
     *  the URL, so this token read is how we tell a reset apart from a magic-link /
     *  OAuth sign-in that lands on the same `auth-callback` host. */
    private fun isRecoverySession(jwt: String): Boolean = runCatching {
        val payload = jwt.split(".").getOrNull(1) ?: return@runCatching false
        val decoded = String(
            android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            ),
        )
        val amr = Json.parseToJsonElement(decoded).jsonObject["amr"]?.jsonArray ?: return@runCatching false
        amr.any { it.jsonObject["method"]?.jsonPrimitive?.contentOrNull == "recovery" }
    }.getOrDefault(false)
    /** Set a new password on the recovery session — no current password needed. */
    suspend fun setNewPassword(newPassword: String): AuthOutcome =
        auth?.changePassword(newPassword) ?: AuthOutcome.Error("Not configured")

    // --- in-app nudges (things slipping / follow-ups) — surfaced quietly on Today, no push ---
    // Persisted (device-local) so a dismissed nudge stays dismissed across relaunch.
    private val _dismissedNudges = MutableStateFlow(graph.settings.loadDismissedNudges())
    fun dismissNudge(id: String) {
        val next = _dismissedNudges.value + id
        _dismissedNudges.value = next
        graph.settings.saveDismissedNudges(next)
    }
    val nudges: StateFlow<List<Nudge>> =
        combine(tasks, captures, _dismissedNudges) { ts, cs, dismissed ->
            // Quiet in-app nudges are off at the Calm level. (Read fresh so a level
            // change is reflected the next time Today re-subscribes.)
            if (!graph.settings.load().notificationLevel.nudges) emptyList()
            else computeNudges(ts, cs, nowMs()).filterNot { it.id in dismissed }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- capture Inbox: triage captures (promote / open / archive / discard) ---
    // "Archived" lives on the SERVER since migration 053 (captures.archived_at:
    // archive = now(), unarchive = null) so every platform shows the same inbox. The
    // device-local id set is a CACHE: it keeps the inbox right offline, migrates up
    // once (pre-053 installs), and is overwritten by the server after every pull —
    // except for writes that haven't landed yet, which stay local until they do.
    private val _archivedCaptureIds = MutableStateFlow(graph.settings.loadArchivedCaptureIds())
    val archivedCaptureIds: StateFlow<Set<String>> = _archivedCaptureIds
    private val capturesClient get() = graph.coordinator?.captures
    private val captureArchiveMutex = Mutex()
    fun archiveCapture(id: String) = setCaptureArchived(id, true)
    fun unarchiveCapture(id: String) = setCaptureArchived(id, false)
    /** TRUE once the device cache reflects it (the queued server write follows) —
     *  the assistant's resolve_capture / restore_capture read this back. */
    private fun setCaptureArchived(id: String, archived: Boolean): Boolean {
        applyArchivedCaptureIds(if (archived) _archivedCaptureIds.value + id else _archivedCaptureIds.value - id)
        // Queue the server write durably, then try to land it now. A failure (offline)
        // leaves it queued; every pull retries (reconcileCaptureArchive).
        graph.settings.savePendingCaptureArchiveWrites(graph.settings.loadPendingCaptureArchiveWrites() + (id to archived))
        viewModelScope.launch { runCatching { drainCaptureArchiveWrites() } }
        return (id in _archivedCaptureIds.value) == archived
    }
    private fun applyArchivedCaptureIds(next: Set<String>) {
        _archivedCaptureIds.value = next
        graph.settings.saveArchivedCaptureIds(next)
    }
    /** Push queued archive writes in order; stop at the first failure (offline) and
     *  keep the rest queued. */
    private suspend fun drainCaptureArchiveWrites() = captureArchiveMutex.withLock {
        val client = capturesClient ?: return@withLock
        val pending = graph.settings.loadPendingCaptureArchiveWrites()
        if (pending.isEmpty()) return@withLock
        val remaining = pending.toMutableMap()
        for ((id, archived) in pending) {
            val ok = runCatching { client.setArchived(id, archived) }.isSuccess
            if (!ok) break
            remaining.remove(id)
        }
        graph.settings.savePendingCaptureArchiveWrites(remaining)
    }
    /** After every pull: land what's queued, then take the server's word for the
     *  archive — keeping any still-unlanded local write. A pre-053 server (no column)
     *  or a transport failure leaves the local cache untouched. The FIRST successful
     *  reconcile per account pushes the pre-053 device-local archive UP instead. */
    private suspend fun reconcileCaptureArchive(uid: String) {
        val client = capturesClient ?: return
        drainCaptureArchiveWrites()
        val server = runCatching { client.archivedIds() }.getOrNull() ?: return
        if (!graph.settings.captureArchiveMigrated(uid)) {
            val toPush = _archivedCaptureIds.value - server
            var allLanded = true
            for (id in toPush) if (runCatching { client.setArchived(id, true) }.isFailure) allLanded = false
            if (allLanded) graph.settings.setCaptureArchiveMigrated(uid)
            applyArchivedCaptureIds(server + toPush)   // local stays authoritative until it has landed
            return
        }
        val stillPending = graph.settings.loadPendingCaptureArchiveWrites()
        val next = (server + stillPending.filterValues { it }.keys) - stillPending.filterValues { !it }.keys
        if (next != _archivedCaptureIds.value) applyArchivedCaptureIds(next)
    }
    /** Captures still needing triage (not archived), newest first. */
    val inboxCaptures: StateFlow<List<Capture>> =
        combine(captures, _archivedCaptureIds) { cs, archived ->
            cs.filter { it.id !in archived }.sortedByDescending { it.at }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Keep the Glance "Start Next" home-screen widget current. Its DataStore
        // was never written, so the widget shipped frozen on "All clear". Recompute
        // the recommendation on task/block/live-session change and push it.
        viewModelScope.launch {
            // distinctUntilChanged is now UPSTREAM of pickStartNext: only re-run the
            // recommendation when an INPUT actually changes (the live mirror re-emits
            // full collections on any write — see LocalStore — so this dropped a lot of
            // redundant picks). debounce coalesces rapid input flips (e.g. a burst of
            // hydrate upserts) into one recompute. A downstream distinct on the RESULT
            // still suppresses a no-op widget write when the pick is unchanged.
            combine(tasks, blocks, liveSession, assignedOut) { ts, bs, live, assigned -> WidgetInputs(ts, bs, live?.taskId, assigned.keys) }
                .distinctUntilChanged()
                .debounce(300)
                // excludeIds: a task assigned away is someone else's now — never
                // recommend it in the home-screen widget (same rule as the Today list).
                .map { (ts, bs, liveId, assigned) -> tech.csalliance.unstuck.core.logic.pickStartNext(ts, bs, liveId, null, assigned) }
                .distinctUntilChanged()
                .collect { rec ->
                    runCatching {
                        tech.csalliance.unstuck.surface.writeStartNext(graph.appContext, rec?.name, rec?.estimateMin)
                        tech.csalliance.unstuck.surface.StartNextWidget().updateAll(graph.appContext)
                    }
                }
        }
    }

    // --- M4: session-signal observer (start/finish pings to people I've shared with) ---
    // Port of lib/use-share-session-signals. Watches the live focus session + my
    // outgoing shareBadges; on the EDGES of a SHARED task's session it pings
    // share-notify (session_start / session_end). The pure sessionSignalStep
    // (unit-tested in :core) decides: a start fires once, a mid-session reload is
    // adopted (never re-announced), the paired end fires on done / cancel / switch.
    // Fed from the RAW store.liveSession() flow (NOT the null-seeded StateFlow) so a
    // cold start MID-session sees the restored session as its FIRST observation and
    // adopts it — exactly the web's session-id reload guard. distinctUntilChanged
    // collapses pause/resume ticks (same sid, same shared) so they stay quiet.
    private var sigState = tech.csalliance.unstuck.core.logic.initSigState()

    init {
        viewModelScope.launch {
            combine(store.liveSession(), shareBadges) { live, byTask ->
                val active = live?.sessionStart != null
                // Session id (matches the web live.id ?? live.taskId); null when idle.
                val sid = if (active) (live?.id ?: live?.taskId) else null
                val taskId = if (active) live?.taskId else null
                // Shared iff I have at least one outgoing share on this task.
                val shared = if (taskId != null && !byTask[taskId].isNullOrEmpty()) taskId else null
                sid to shared
            }
                .distinctUntilChanged()
                .collect { (sid, shared) ->
                    val (next, fires) = tech.csalliance.unstuck.core.logic.sessionSignalStep(sigState, sid, shared)
                    sigState = next
                    for (f in fires) {
                        val started = f.kind == tech.csalliance.unstuck.core.logic.SessionSignalKind.START
                        // Best-effort, server-revalidated fan-out; don't block the collector.
                        launch { circleClient?.notifySession(f.taskId, started) }
                    }
                }
        }
    }

    // --- M5: co-focus presence (body-doubling on a partner-shared task) ---
    private val cofocus get() = graph.coordinator?.cofocus

    /** Open a co-focus presence session on `cofocus:<taskId>` with the given initial
     *  [track] (null = observe only) and, when focusing, an initial [timer] to broadcast
     *  so a partner sees the SAME live mm:ss. Returns null when signed out / not
     *  configured. The caller (a screen) MUST close() it on dispose. */
    fun openCoFocus(
        taskId: String,
        track: tech.csalliance.unstuck.core.model.CoFocusState?,
        timer: tech.csalliance.unstuck.core.model.CoFocusTimer? = null,
    ): tech.csalliance.unstuck.sync.CoFocusSession? =
        cofocus?.open(taskId, track, timer)

    // --- One true shared session (partner co-focus v2, docs/shared-session-spec.md) ---
    // The co-focus channel for a partner-shared LIVE session is owned HERE, keyed on
    // the live-session flow — NOT on the focus screen's composition — so controls
    // arrive (and ours ship) while the user is on Today or the screen is closed.
    // Full-state snapshots, LWW by (rev, atMs) via the pure sharedSessionStep reducer.

    /** The last co-focus fields we BROADCAST or APPLIED — the echo guard: a Room
     *  re-emission whose fields match is never re-broadcast. */
    private data class CoFocusFields(val sessionStartMs: Long, val paused: Boolean, val pausedAtMs: Long?, val estimateMin: Int)

    private var coFocusSession: tech.csalliance.unstuck.sync.CoFocusChannel? = null
    private var coFocusChannelTaskId: String? = null
    /** The signed-in user WHEN the channel opened — a live→null edge observed under a
     *  DIFFERENT (or no) user is a sign-out / user-switch cache wipe, not a finish,
     *  and must not broadcast a spurious `ended` to the partner. */
    private var coFocusChannelUid: String? = null
    private var coFocusPeersJob: Job? = null
    private var coFocusControlsJob: Job? = null
    private var coFocusRejoinJob: Job? = null
    private var coFocusSocketDropJob: Job? = null
    private var coFocusLastSent: Pair<Int, CoFocusFields>? = null   // (rev, fields)
    /** Rejoin reconciliation v2 (spec §Rejoin reconciliation v2): TRANSIENT, per
     *  channel. Armed on every rejoin signal and foreground re-exchange; cleared by
     *  the first SAME-session state received (or the bounded grace concluding we're
     *  alone). While pending: the automatic announces stay suppressed (rule 1 — no
     *  rev-authoritative re-announce on a rejoin; our state is SUSPECT until the
     *  first exchange), and the most-ahead gate in [applyRemoteControl] widens to
     *  `divergedOffline || rejoinPending` — the undetected-socket-death window
     *  (sends fire-and-forget "delivered", offline controls bump rev un-flagged, a
     *  late DISCONNECT observation) reconciles on the first exchange, flag or no
     *  flag. */
    private var coFocusRejoinPending = false
    /** Session id whose remote `ended` we already applied — the teardown observer must
     *  not re-broadcast ended for it (the ender's device already did). */
    private var coFocusRemoteEndedSid: String? = null
    /** The previous emission's candidate session — the `ended` edge detector. */
    private var coFocusPrevLive: LiveSession? = null
    /** Diverged re-exchange grace (spec amendments): after a diverged hello, wait
     *  ~5s for a same-session answer. On expiry: a focusing peer is present →
     *  re-hello + re-arm (≤ [DIVERGENCE_GRACE_MAX_TRIES]); presence empty → we're
     *  ALONE, nobody holds newer state — clear the flag and re-announce. The
     *  peer-independent un-diverge: without it a failed MINT broadcast leaves the
     *  session diverged from t=0 and invisible to the partner forever (they mint
     *  their own → fork + double accrual). */
    private var coFocusGraceJob: Job? = null
    private var coFocusGraceTries = 0

    /** Peers on the session-lifetime channel — FocusScreen renders these instead of
     *  opening a SECOND channel on the same task (a duplicate track double-counted us). */
    private val _coFocusPeers = MutableStateFlow<List<CoFocusPeer>>(emptyList())
    val coFocusPeers: StateFlow<List<CoFocusPeer>> = _coFocusPeers.asStateFlow()

    /** Calm attribution line for a REMOTE control ("Paused by Sam" / "Sam resumed") —
     *  no modal interruptions. Cleared on local controls / session end. */
    private val _coFocusAttribution = MutableStateFlow<String?>(null)
    val coFocusAttribution: StateFlow<String?> = _coFocusAttribution.asStateFlow()

    init {
        viewModelScope.launch {
            // Raw store flow (same reasoning as the session-signal observer): a cold
            // start MID-session must see the restored session as its first observation.
            combine(store.liveSession(), shareBadges) { live, badges -> live to badges }
                .collect { (live, badges) -> onCoFocusLiveChanged(live, badges) }
        }
        viewModelScope.launch {
            // Belt-and-braces reconnect signal (docs/shared-session-spec.md, "Offline
            // & reconnect"): on every app FOREGROUND re-exchange the shared state —
            // a socket that died while backgrounded can take the SDK a heartbeat
            // (~15s) to notice, and a send in that window silently vanishes. The
            // nudge (best-effort) pokes the wire first: a broken socket fails the
            // write → the SDK reconnects → the rejoins signal re-exchanges again
            // over the FRESH join (the retry-after-next-CONNECTED-edge path).
            graph.foregrounds.collect {
                runCatching { coFocusSession?.nudgeSocket() }
                runCatching { reExchangeCoFocus() }
            }
        }
    }

    /** Is this live session a partner co-focus candidate? Recipient partner sessions
     *  carry the marker; owner sessions match an outgoing partner badge; rev stamps
     *  bridge a cold start where the badges RPC hasn't resolved yet. */
    private fun isPartnerCoFocus(live: LiveSession, badges: Map<String, List<ShareBadge>>): Boolean =
        live.sessionStart != null && (
            live.sharedLevel == ShareLevel.PARTNER.wire ||
                badges[live.taskId].orEmpty().any { it.level == ShareLevel.PARTNER } ||
                (live.sharedTitle == null && (live.sharedSessionRev != null || live.lastAppliedRev != null))
            )

    private fun sharedStateOf(live: LiveSession, rev: Int, atMs: Long, ended: Boolean = false): SharedSessionState? {
        val id = live.id ?: return null
        val start = live.sessionStart ?: return null
        return SharedSessionState(id, start, live.paused, live.pausedAt, live.sessionEstimateMin, rev, atMs, ended)
    }

    private suspend fun onCoFocusLiveChanged(live: LiveSession?, badges: Map<String, List<ShareBadge>>) {
        val prev = coFocusPrevLive
        val candidate = live != null && live.sessionStart != null && isPartnerCoFocus(live, badges)
        // A candidate session ENDED (finish / cancel / shade-End) or was DISPLACED →
        // broadcast `ended` (rev+1, best-effort) so the partner finalizes too — unless
        // the end CAME from the partner (their device already announced it), or the
        // edge is a sign-out / user-switch CACHE WIPE (SyncCoordinator.clearAll →
        // live→null looks like a finish but the session didn't end — broadcasting
        // `ended` would finalize the partner's still-running session).
        if (prev?.id != null && (live == null || live.id != prev.id)) {
            val uidNow = currentUid()
            val wipe = uidNow == null || (coFocusChannelUid != null && uidNow != coFocusChannelUid)
            if (coFocusRemoteEndedSid == prev.id) {
                coFocusRemoteEndedSid = null
            } else if (!wipe) {
                val rev = maxOf(prev.sharedSessionRev ?: 0, prev.lastAppliedRev ?: 0, coFocusLastSent?.first ?: 0) + 1
                sharedStateOf(prev, rev, nowMs(), ended = true)?.let { st ->
                    runCatching { coFocusSession?.broadcastShared(st) }
                }
            }
            coFocusLastSent = null   // a NEW session on the same task starts a fresh rev line
        }
        if (!candidate) {
            coFocusPrevLive = null
            closeCoFocusChannel()
            return
        }
        live!!
        ensureCoFocusChannel(live)
        val session = coFocusSession
        if (session == null) {   // signed out / not configured — nothing to sync
            coFocusPrevLive = null
            return
        }
        val start = live.sessionStart
        val id = live.id
        if (start == null || id == null) { coFocusPrevLive = live; return }
        // Mirror the persisted diverged flag — OR the transient rejoin-pending window
        // (rejoin v2 rule 2) — into the channel's announce gate on EVERY observation
        // (covers a channel opened mid-diverged after a process restart): while
        // either holds, hello replies / re-tracks must not re-announce suspect state.
        session.setSuppressAnnounce(live.divergedOffline == true || coFocusRejoinPending)
        val fields = CoFocusFields(start, live.paused, live.pausedAt, live.sessionEstimateMin)
        val last = coFocusLastSent
        val lastApplied = live.lastAppliedRev
        if (last == null && lastApplied != null && (live.sharedSessionRev ?: 0) <= lastApplied) {
            // An ADOPTED (or restored-from-disk) remote state we haven't changed: seed
            // the echo guard + the hello re-announce snapshot, but don't announce — the
            // focuser who owns this rev already broadcast it.
            coFocusLastSent = lastApplied to fields
            session.setSharedCurrent(SharedSessionState(id, start, live.paused, live.pausedAt, live.sessionEstimateMin, lastApplied, live.lastAppliedAtMs ?: 0L, ended = false))
        } else if (last == null || last.second != fields) {
            if (live.divergedOffline == true) {
                // DIVERGED: the local-change broadcaster is SUPPRESSED (spec, "Offline
                // & reconnect") — offline controls keep applying locally (mutateLive /
                // FocusCommands still stamp rev+atMs), but nothing ships until the
                // first same-session state after reconnect resolves the divergence in
                // applyRemoteControl. coFocusLastSent is deliberately left stale so
                // the post-convergence re-emission can catch up the partner when the
                // resolution itself didn't broadcast (the plain-LWW outcome).
                coFocusPrevLive = live
                return
            }
            // A LOCAL state change (mint / pause / resume / extend): broadcast the FULL
            // state at the next rev. Local controls pre-stamp sharedSessionRev AND
            // sharedSessionAtMs in the same write (mutateLive / FocusCommands) — the
            // wire carries THAT clock so the persisted floor matches what peers echo
            // back (a re-announce of our own control must never read as newer). A
            // mint / un-stamped path stamps both here and persists them (the
            // re-emission is echo-guarded: fields unchanged).
            val eff = maxOf(live.sharedSessionRev ?: 0, live.lastAppliedRev ?: 0)
            val rev = if (eff > (last?.first ?: 0)) eff else eff + 1
            val at = (if (rev == live.sharedSessionRev) live.sharedSessionAtMs else null) ?: nowMs()
            if (rev != live.sharedSessionRev || at != live.sharedSessionAtMs) {
                val cur = store.getLiveSession()
                if (cur?.id == id) store.setLiveSession(cur.copy(sharedSessionRev = rev, sharedSessionAtMs = at))
            }
            coFocusLastSent = rev to fields
            _coFocusAttribution.value = null   // acting locally clears the remote line
            val delivered = session.broadcastShared(SharedSessionState(id, start, live.paused, live.pausedAt, live.sessionEstimateMin, rev, at, ended = false))
            if (!delivered) {
                // Delivery failed (send error / socket down / channel not joined):
                // this side has DIVERGED from the channel. Persist the flag (it must
                // survive process death) and gate the automatic re-announces; the
                // local session itself is untouched — offline behavior is unchanged
                // (criteria 1–2: the timer runs / pauses locally as normal).
                // ROLL THE ECHO GUARD BACK (spec amendments, echo-guard integrity):
                // the channel never saw this state — leaving the guard claiming it
                // did would swallow the post-convergence catch-up re-emission as an
                // "echo" (an offline EXTEND would be permanently lost: estimate
                // changes don't move elapsed → within slack → Lww arm → re-emit
                // suppressed by the poisoned guard).
                coFocusLastSent = last
                val curNow = store.getLiveSession()
                if (curNow?.id == id && curNow.divergedOffline != true) {
                    store.setLiveSession(curNow.copy(divergedOffline = true))
                }
                session.setSuppressAnnounce(true)
            }
        }
        coFocusPrevLive = live
    }

    private fun ensureCoFocusChannel(live: LiveSession) {
        if (coFocusSession != null && coFocusChannelTaskId == live.taskId) return
        closeCoFocusChannel()
        val s: tech.csalliance.unstuck.sync.CoFocusChannel? = if (coFocusChannelFactory != null) {
            coFocusChannelFactory.invoke(live.taskId)
        } else {
            val timer = live.sessionStart?.let { CoFocusTimer(it, live.paused, live.pausedAt, live.sessionEstimateMin) }
            cofocus?.open(live.taskId, CoFocusState.FOCUSING, timer)
        }
        if (s == null) return
        coFocusSession = s
        coFocusChannelTaskId = live.taskId
        coFocusChannelUid = currentUid()
        coFocusLastSent = null
        coFocusRejoinPending = false   // per-channel transient (rejoin v2 rule 2)
        coFocusPeersJob = viewModelScope.launch { s.peers.collect { _coFocusPeers.value = it } }
        coFocusControlsJob = viewModelScope.launch {
            s.controls.collect { ctl -> runCatching { applyRemoteControl(ctl) } }
        }
        coFocusRejoinJob = viewModelScope.launch {
            // The SDK auto-rejoined the channel after a socket drop — hello was only
            // ever sent at the FIRST subscribe, so re-exchange now (spec: "Offline &
            // reconnect convergence" + "Rejoin reconciliation v2": hello-ONLY). The
            // foreground collector (init) is the belt-and-braces twin of this signal.
            s.rejoins.collect { runCatching { reExchangeCoFocus() } }
        }
        coFocusSocketDropJob = viewModelScope.launch {
            // Undetected-drop belt (rejoin v2): the socket visibly left CONNECTED —
            // mark the live partner-shared session diverged even though no control
            // send failed (the silent-window sends were fire-and-forget "delivered").
            s.socketDrops.collect { runCatching { onCoFocusSocketDropped() } }
        }
    }

    /** Re-exchange after a channel rejoin / app foreground — HELLO-ONLY (rejoin
     *  reconciliation v2, superseding the vc71 idempotent re-announce): a dead
     *  socket goes unnoticed for up to ~2 heartbeats, sends in that window are
     *  fire-and-forget "delivered", an offline control bumps rev UN-flagged — and
     *  the old rejoin re-announce then imposed that stale state on the healthy
     *  partner by rev authority (reproduced live: web rewound onto a frozen clock).
     *  So every re-join sends ONLY `hello{diverged: <flag>}` (any focuser replies
     *  with its full state; a diverged hello is answered even by a diverged
     *  focuser — the deadlock breaker), arms [coFocusRejoinPending] so the FIRST
     *  same-session reply reconciles most-ahead (flag or no flag), suppresses the
     *  automatic announces while pending, and arms the bounded grace fallback.
     *  The genuine FIRST subscribe still announces (mint/adopt needs it) — that
     *  path is the live-observer broadcast + the channel's own subscribe hello,
     *  not this method. */
    private suspend fun reExchangeCoFocus() {
        val session = coFocusSession ?: return
        val live = store.getLiveSession() ?: return
        if (live.taskId != coFocusChannelTaskId) return
        val id = live.id
        val start = live.sessionStart
        if (id == null || start == null || !isPartnerCoFocus(live, shareBadges.value)) return
        coFocusRejoinPending = true
        session.setSuppressAnnounce(true)
        session.sendHello(diverged = live.divergedOffline == true)
        // Bounded grace (spec amendments + rejoin v2 rule 4): a fresh trigger
        // (rejoin / foreground) restarts the cycle. For a DIVERGED session it is
        // the peer-independent un-diverge; for a merely-PENDING one it bounds the
        // pending window (a probe hello must not go unanswered forever — a fork).
        coFocusGraceTries = 0
        armDivergenceGrace()
    }

    /** Arm (or re-arm) the diverged re-exchange grace: on expiry with the session
     *  still diverged, either re-ask a visibly-focusing peer (bounded) or conclude
     *  we're alone and un-diverge unilaterally. See [onDivergenceGraceExpired]. */
    private fun armDivergenceGrace() {
        coFocusGraceJob?.cancel()
        coFocusGraceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(DIVERGENCE_REEXCHANGE_GRACE_MS)
            runCatching { onDivergenceGraceExpired() }
        }
    }

    /** The re-exchange hello went unanswered for the grace window. "ALONE" needs
     *  PROOF (rejoin v2, rule 4): a presence map that hasn't SYNCED since the last
     *  rejoin is stale pre-drop data — it counts as a focusing peer, never as
     *  alone. A focusing peer (or an unsynced map) → re-hello and re-arm (≤3
     *  tries; unilaterally announcing would fight their state). Presence SYNCED
     *  and empty of focusers → we're genuinely alone: our state IS the session —
     *  close the pending window, and if DIVERGED clear the flag and re-announce at
     *  the local floor (rev already monotonic; the failed-MINT case — without it
     *  the partner would mint a second session → fork + double accrual). A
     *  PENDING-only session at the retry cap fails OPEN (pending cleared, announces
     *  un-suppressed): its state was never suspect-by-evidence, and a suppressed
     *  hello reply would hide the session from a probing partner — a fork. A
     *  DIVERGED session at the cap stays diverged (the peer's next control or the
     *  next re-exchange trigger resolves it). */
    private suspend fun onDivergenceGraceExpired() {
        val session = coFocusSession ?: return
        val live = store.getLiveSession() ?: return
        if (live.taskId != coFocusChannelTaskId) return
        val diverged = live.divergedOffline == true
        if (!diverged && !coFocusRejoinPending) return   // window already closed
        val id = live.id ?: return
        if (live.sessionStart == null) return
        val unsynced = !session.presenceSyncedSinceRejoin()
        if (unsynced || _coFocusPeers.value.any { it.state == CoFocusState.FOCUSING }) {
            if (coFocusGraceTries < DIVERGENCE_GRACE_MAX_TRIES) {
                coFocusGraceTries += 1
                session.sendHello(diverged = diverged)
                armDivergenceGrace()
                return
            }
            // Cap reached. Pending-only: fail open (see doc). Diverged: stay
            // diverged — announces remain suppressed; the diverged-hello bypass
            // still answers the partner, so no fork risk on this arm.
            if (!diverged && coFocusRejoinPending) {
                coFocusRejoinPending = false
                session.setSuppressAnnounce(false)
            }
            return
        }
        coFocusGraceTries = 0
        coFocusRejoinPending = false
        if (!diverged) {
            // Merely pending and provably alone — nothing to converge with and
            // nothing suspect to announce; just lift the suppression.
            session.setSuppressAnnounce(false)
            return
        }
        val (rev, at) = sharedRevFloor(live.sharedSessionRev, live.sharedSessionAtMs, live.lastAppliedRev, live.lastAppliedAtMs)
        store.setLiveSession(live.copy(divergedOffline = null))
        session.setSuppressAnnounce(false)
        // Re-announce explicitly (rev > 0 whenever a control/mint ever stamped; a
        // never-stamped session simply re-emits through the now-unsuppressed
        // broadcaster). The helper VERIFIES delivery: a failed catch-up send rolls
        // the echo guard back and RE-MARKS the divergence (rejoin v2, rule 4).
        if (rev > 0) broadcastLocalAtFloor(id, live, rev, at)
    }

    /** Undetected-drop belt (rejoin v2, "socket-down alone marks divergence"): the
     *  channel observed the socket LEAVING connected while a partner-shared session
     *  is live — flag it diverged even though no control send failed. Criterion 3's
     *  offline RUNNER (no local control at all) must not be rewound by the
     *  partner's mid-outage pause on rejoin: the flag routes the first
     *  post-reconnect exchange through most-ahead — with KeepAndBroadcast rights,
     *  since this outage is DETECTED. Session-guarded like every co-focus path. */
    private suspend fun onCoFocusSocketDropped() {
        val live = store.getLiveSession() ?: return
        if (live.taskId != coFocusChannelTaskId) return
        if (live.id == null || live.sessionStart == null || !isPartnerCoFocus(live, shareBadges.value)) return
        if (live.divergedOffline != true) {
            store.setLiveSession(live.copy(divergedOffline = true))
        }
        coFocusSession?.setSuppressAnnounce(true)
    }

    private fun closeCoFocusChannel() {
        coFocusPeersJob?.cancel(); coFocusPeersJob = null
        coFocusControlsJob?.cancel(); coFocusControlsJob = null
        coFocusRejoinJob?.cancel(); coFocusRejoinJob = null
        coFocusSocketDropJob?.cancel(); coFocusSocketDropJob = null
        coFocusGraceJob?.cancel(); coFocusGraceJob = null
        coFocusGraceTries = 0
        coFocusRejoinPending = false
        coFocusSession?.close(); coFocusSession = null
        coFocusChannelTaskId = null
        coFocusChannelUid = null
        coFocusLastSent = null
        _coFocusPeers.value = emptyList()
        _coFocusAttribution.value = null
    }

    /** The VM can die (activity finished, process trim) while a shared session keeps
     *  running — release the realtime channel instead of leaking it; the next VM
     *  re-opens it off the live-session flow. */
    override fun onCleared() {
        closeCoFocusChannel()
        // A LIVE call keeps the client it already dialled; this only stops a
        // future Answer from dialling through a dead ViewModel.
        runCatching { tech.csalliance.unstuck.calls.CallVoiceService.unbind(callVoiceDeps) }
        super.onCleared()
    }

    /** Apply an incoming full-state control via the pure reducer: REPLACE the shared
     *  fields (never bump our own rev), advance the LWW cursor, and drive the local
     *  side effects (FGS notification, paused check-in, recap) — WITHOUT opening the
     *  pause-reason sheet or arming the pause nag for a control the partner made.
     *
     *  When THIS side is offline-DIVERGED (a control's broadcast failed to deliver,
     *  or the socket visibly dropped) OR rejoin-PENDING (rejoin v2: the first
     *  exchange after ANY rejoin reconciles, flag or no flag), the first
     *  same-session live state resolves by MOST-AHEAD convergence
     *  (core.logic.resolveDivergence) instead of plain LWW — the tester's acceptance
     *  rule: on regaining internet, everyone ends on the timer that's most ahead.
     *  The resolution is ASYMMETRIC (rejoin v2 rule 3): Adopt is always allowed,
     *  but KeepAndBroadcast needs the genuine diverged FLAG — an un-flagged
     *  local-ahead falls back to plain LWW, so a trivial blip can never bulldoze
     *  the partner's genuine online pause. Fully-live receivers are COMPLETELY
     *  unchanged: live controls stay plain LWW (a stale running re-announce must
     *  never un-pause an online pause), and `ended` stays terminal throughout.
     *
     *  Internal (not private) purely as a test seam — production calls arrive only
     *  via the session-lifetime controls collector. */
    internal suspend fun applyRemoteControl(ctl: CoFocusControl) {
        // Sampled HERE, before the first suspension: the rejoin window as it stood
        // when the control ARRIVED. It used to be read further down, after the
        // live-session load below — and the grace timer that closes the window
        // ([armDivergenceGrace]) runs on this same scope, so it could expire DURING
        // that read and pull the gate out from under a control that had already
        // landed inside the window. The reply then lost to plain LWW, both clocks
        // kept their own time, and the session forked — the exact outcome rejoin
        // v2 exists to prevent. A partner answering our hello IS the evidence the
        // grace is waiting for; a timer that fires mid-read must not discard it.
        //
        // In production that window is one Room read inside five seconds. In the
        // unit suite, where `delay` is virtual and skips the moment the scheduler
        // idles — which is exactly while this read is parked on Room's real
        // executor — the two raced on thread scheduling and the grace won about one
        // run in five: rejoinPending_unflagged_incomingAheadAdopts_despiteLosingPlainLww
        // then waited for an adopt that never came and died on runTest's 60s
        // timeout, which is what made the suite look order-dependent. Traced
        // live: "applyRemoteControl entry pending=true" → "grace expiry CLOSED the
        // pending window" → "afterRead pending=false".
        val rejoinPending = coFocusRejoinPending
        val cur = store.getLiveSession() ?: return
        val id = cur.id
        val start = cur.sessionStart
        if (id == null || start == null) return
        // The floor is the newest (rev, atMs) PAIR this device knows — the local
        // stamp vs the last applied remote, compared lexicographically. Mixing the
        // max rev with only the applied atMs (the old floor) let a peer re-announce
        // of OUR OWN control read as newer, and a rev-tie race SWAP the two sides.
        val (floorRev, floorAt) = sharedRevFloor(cur.sharedSessionRev, cur.sharedSessionAtMs, cur.lastAppliedRev, cur.lastAppliedAtMs)
        val local = SharedSessionState(
            sessionId = id, sessionStartMs = start, paused = cur.paused, pausedAtMs = cur.pausedAt,
            estimateMin = cur.sessionEstimateMin,
            rev = floorRev,
            atMs = floorAt,
            ended = cur.sharedSessionEndedBy != null,
        )
        val msg = ctl.state
        val name = ctl.name?.let { coFocusFirstName(it) } ?: "Your partner"
        // The first SAME-session exchange closes the rejoin-pending window (rejoin
        // v2 rule 2) — the flag was captured at entry (above) so THIS control still
        // reconciles through the widened gate below. Suppression lifts unless the
        // diverged flag holds it.
        if (rejoinPending && msg.sessionId == id) {
            coFocusRejoinPending = false
            if (cur.divergedOffline != true) coFocusSession?.setSuppressAnnounce(false)
        }
        // Reconnect reconciliation (spec, "Offline & reconnect" + "Rejoin
        // reconciliation v2"): diverged-flagged OR rejoin-pending. Only for the
        // SAME session, only while not locally ended, and never for an incoming
        // `ended` (terminal — the plain path below finalizes it regardless of clocks).
        val flagged = cur.divergedOffline == true
        var lwwConverged = false
        if ((flagged || rejoinPending) && msg.sessionId == id && !local.ended && !msg.ended) {
            when (val res = resolveDivergence(local, msg, nowMs(), flagged = flagged)) {
                DivergenceResolution.Adopt -> {
                    // Incoming is most-ahead: adopt it WHOLESALE (clears the flag and
                    // drives the FGS notification via the shared apply body). Allowed
                    // flag or no flag — adopting an ahead state never harms the peer.
                    applyIncomingShared(cur, msg, name)
                    return
                }
                is DivergenceResolution.KeepAndBroadcast -> {
                    // Only reachable when FLAGGED (rejoin v2 rule 3): a detected
                    // outage's ahead local state genuinely wins criteria 3/4.
                    keepDivergedLocalAndBroadcast(cur, res.rev)
                    return
                }
                DivergenceResolution.Lww -> {
                    // Within slack (or un-flagged local-ahead): plain (rev, atMs)
                    // LWW below decides whether the incoming applies. A FLAGGED
                    // divergence is resolved by the exchange — drop the flag; the
                    // catch-up broadcast on LWW-reject is flag-only too (an
                    // un-flagged rejoiner holds no proven-newer state to impose).
                    if (flagged) {
                        store.setLiveSession(cur.copy(divergedOffline = null))
                        coFocusSession?.setSuppressAnnounce(false)
                        lwwConverged = true
                    }
                }
            }
        }
        val step = sharedSessionStep(local, msg)
        if (!step.apply) {
            // Post-convergence catch-up (spec amendments, echo-guard integrity):
            // the divergence resolved within slack and the incoming LOST plain LWW
            // — our local state is the channel's newest, so broadcast it
            // EXPLICITLY at the local floor cursor (an idempotent replay for a
            // peer that already has it; THE catch-up for one that never did).
            // Relying on the flag-clear re-emission alone was fragile: an echo
            // guard poisoned by a failed send silently swallowed it, and the
            // offline control (e.g. an EXTEND) never reached the partner.
            if (lwwConverged) broadcastLocalAtFloor(id, cur, floorRev, floorAt)
            return
        }
        if (msg.ended) { applyRemoteEnded(cur, msg, name); return }
        applyIncomingShared(cur, msg, name)
    }

    /** Broadcast the LOCAL state at its floor cursor — the within-slack convergence
     *  catch-up and the diverged-and-alone grace re-announce. Sets the echo guard
     *  BEFORE the send (the Room re-emission's fields match → no duplicate), and on
     *  a failed send rolls the guard back AND re-marks the divergence (the channel
     *  never saw the state; the next trigger retries). */
    private suspend fun broadcastLocalAtFloor(id: String, cur: LiveSession, rev: Int, at: Long) {
        val start = cur.sessionStart ?: return
        val session = coFocusSession ?: return
        val prevSent = coFocusLastSent
        coFocusLastSent = maxOf(rev, prevSent?.first ?: 0) to
            CoFocusFields(start, cur.paused, cur.pausedAt, cur.sessionEstimateMin)
        val delivered = session.broadcastShared(
            SharedSessionState(id, start, cur.paused, cur.pausedAt, cur.sessionEstimateMin, rev, at, ended = false),
        )
        if (!delivered) {
            coFocusLastSent = prevSent
            val curNow = store.getLiveSession()
            if (curNow?.id == id && curNow.divergedOffline != true) {
                store.setLiveSession(curNow.copy(divergedOffline = true))
            }
            session.setSuppressAnnounce(true)
        }
    }

    /** REPLACE the local session's shared fields with an APPLIED incoming snapshot and
     *  drive the side effects (FGS notification / paused check-in / attribution) —
     *  shared by the plain LWW apply and the divergence Adopt arm, so converging to
     *  an adopted running/paused state updates the FGS notification through the same
     *  code as any remote control. Always clears [LiveSession.divergedOffline]: an
     *  applied same-session state means this side has re-exchanged with the channel. */
    private suspend fun applyIncomingShared(cur: LiveSession, msg: SharedSessionState, name: String) {
        val start = cur.sessionStart
        // Display clamp (same as FocusTimer.adopt): a partner clock up to 2 min AHEAD
        // (adoptable tolerates it) would otherwise put sessionStart in OUR future after
        // their resume — the ring froze at 00:00 for the skew and the local finalize
        // under-counted by it. The wire state (setSharedCurrent) stays as sent.
        val startMs = minOf(msg.sessionStartMs, nowMs())
        // Echo guard BEFORE the write: the Room re-emission's fields will match.
        coFocusLastSent = maxOf(msg.rev, coFocusLastSent?.first ?: 0) to
            CoFocusFields(startMs, msg.paused, msg.pausedAtMs, msg.estimateMin)
        coFocusSession?.setSharedCurrent(msg)
        coFocusSession?.setSuppressAnnounce(false)
        store.setLiveSession(
            cur.copy(
                sessionStart = startMs, paused = msg.paused, pausedAt = msg.pausedAtMs,
                sessionEstimateMin = msg.estimateMin,
                // Cursor coherence (spec amendments, "Adopt fixes the cursors"): the
                // LOCAL stamp pair follows the applied control too — on the Adopt arm
                // a diverged client's INFLATED local rev (offline bumps nobody saw)
                // must not out-floor the partner's post-convergence controls, and an
                // adopted REMOTE pause must classify as remote (remotePaused ties the
                // equal pairs to REMOTE) so the pause nag / paused check-in can never
                // arm for the partner's pause. On the plain-LWW path the incoming won
                // the floor, so this only ever moves the pair forward.
                sharedSessionRev = msg.rev, sharedSessionAtMs = msg.atMs,
                lastAppliedRev = msg.rev, lastAppliedAtMs = msg.atMs,
                divergedOffline = null,
            ),
        )
        val ctx = graph.appContext
        when {
            !cur.paused && msg.paused -> {
                // Remote pause: freeze the FGS notification; deliberately do NOT arm the
                // paused check-in (they stepped away, not you) or open the reason sheet.
                tech.csalliance.unstuck.surface.FocusTimerService.update(ctx, paused = true)
                _coFocusAttribution.value = "Paused by $name"
            }
            cur.paused && !msg.paused -> {
                // Remote resume: rebase the chronometer at the shifted start.
                tech.csalliance.unstuck.surface.FocusTimerService.update(ctx, paused = false, startMs = startMs)
                tech.csalliance.unstuck.surface.PausedCheckinScheduler.cancel(ctx)
                setTransientAttribution("$name resumed")
            }
            msg.estimateMin != cur.sessionEstimateMin -> setTransientAttribution("$name extended the session")
            startMs != start && !msg.paused ->
                tech.csalliance.unstuck.surface.FocusTimerService.update(ctx, paused = false, startMs = startMs)
        }
    }

    /** Divergence resolution, LOCAL-AHEAD arm: keep the local state, clear the flag,
     *  and broadcast it at rev = max(local, incoming) + 1 — a GENUINE convergence
     *  control the partner applies via its normal LWW (criterion 4: the online side
     *  needs no special logic). A failed convergence send re-marks the divergence;
     *  the next rejoin/foreground re-exchange retries. */
    private suspend fun keepDivergedLocalAndBroadcast(cur: LiveSession, rev: Int) {
        val id = cur.id ?: return
        val start = cur.sessionStart ?: return
        val at = nowMs()
        // Echo guard BEFORE the write (the Room re-emission's fields match).
        val prevSent = coFocusLastSent
        coFocusLastSent = rev to CoFocusFields(start, cur.paused, cur.pausedAt, cur.sessionEstimateMin)
        store.setLiveSession(cur.copy(sharedSessionRev = rev, sharedSessionAtMs = at, divergedOffline = null))
        val session = coFocusSession ?: return
        session.setSuppressAnnounce(false)
        val delivered = session.broadcastShared(
            SharedSessionState(id, start, cur.paused, cur.pausedAt, cur.sessionEstimateMin, rev, at, ended = false),
        )
        if (!delivered) {
            // Echo-guard integrity (spec amendments): the channel never saw the
            // convergence control — roll the guard back so the NEXT resolution's
            // catch-up isn't swallowed as an "echo", and re-mark the divergence.
            coFocusLastSent = prevSent
            val curNow = store.getLiveSession()
            if (curNow?.id == id && curNow.divergedOffline != true) {
                store.setLiveSession(curNow.copy(divergedOffline = true))
            }
            session.setSuppressAnnounce(true)
        }
    }

    private fun setTransientAttribution(line: String) {
        _coFocusAttribution.value = line
        viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            if (_coFocusAttribution.value == line) _coFocusAttribution.value = null
        }
    }

    /** A REMOTE `ended` finalizes this side too: accrue via the log_shared_focus
     *  ledger (exactly-once per session id — both sides finalize the SAME id), keep
     *  the owner's Session row for insights, fire NO session_end ping (the ender's
     *  device already did), and show the recap with attribution.
     *
     *  Runs NonCancellable: this executes inside coFocusControlsJob's collect, and
     *  setLiveSession(null) below flips co-focus candidacy → the live observer calls
     *  closeCoFocusChannel() → coFocusControlsJob.cancel() — which would cancel THIS
     *  coroutine mid-finalize (Session row, ledger accrual, FGS stop and the recap
     *  could all be skipped at the next suspension point). */
    private suspend fun applyRemoteEnded(cur: LiveSession, msg: SharedSessionState, name: String) = withContext(kotlinx.coroutines.NonCancellable) {
        val sid = cur.id ?: return@withContext
        coFocusRemoteEndedSid = sid                       // don't re-broadcast their end
        sigState = sigState.copy(startedTask = null)      // suppress the session_end ping
        // Mark locally-ended (blocks any late re-apply) before the suspending RPCs.
        store.setLiveSession(cur.copy(sharedSessionEndedBy = name))
        // Elapsed from the SHARED timestamps at the ender's clock — both sides write
        // ~the same number; the ledger dedups whichever lands second.
        val elapsed = canonicalElapsedSec(msg, msg.atMs)
        val title = cur.sharedTitle ?: tasks.value.firstOrNull { it.id == cur.taskId }?.name ?: "Focus session"
        store.setLiveSession(null)
        if (cur.sharedTitle == null) {
            // Owner: still writes its own Session row (insights; single writer — the
            // shared session id), but the total accrues ONLY via the ledger below.
            val prev = store.tasks().first().firstOrNull { it.id == cur.taskId }
            if (prev != null) {
                write?.upsertSession(Session(id = sid, taskId = prev.id, taskName = prev.name, estimateMin = cur.sessionEstimateMin, actualSec = elapsed, completedAt = isoNow()))
                flushOutbox()
            } else {
                // Task gone: no Session row, and a capture naming it would be refused (A14).
                write?.unlinkCapturesFromTask(cur.taskId)
                releaseCapturesOf(sid)
            }
        } else {
            releaseCapturesOf(sid)   // a task shared with me: no own Session row (A14)
        }
        accrueSharedFocus(cur.taskId, elapsed, sid, msg.estimateMin, ownerFallback = cur.sharedTitle == null)
        refreshShares()
        val ctx = graph.appContext
        tech.csalliance.unstuck.surface.FocusTimerService.stop(ctx)
        tech.csalliance.unstuck.surface.PausedCheckinScheduler.cancel(ctx)
        _coFocusAttribution.value = null
        runCatching { graph.coordinator?.notifications?.sessionRecap(title, away = false) }
        _lastRecap.value = RecapState(taskName = title, focusedSec = elapsed, at = nowMs(), endedBy = name)
    }

    /** Durable ledger accrual (the EXCLUSIVE total_focused path for partner-shared
     *  sessions): a transient failure queues a persisted retry (drained on every
     *  foreground — see SharedFocusLedger); a terminal NOT_ALLOWED (share revoked
     *  mid-session) falls back to the durable direct bump when the task is OURS
     *  ([ownerFallback]) so the minutes aren't lost either way. */
    private suspend fun accrueSharedFocus(taskId: String, elapsedSec: Int, sessionId: String, estimateMin: Int, ownerFallback: Boolean) {
        val r = runCatching {
            SharedFocusLedger.logOrQueue(graph.settings, circleClient, taskId, elapsedSec, sessionId, estimateMin)
        }.getOrElse { SharedFocusLogResult.FAILED }
        if (r == SharedFocusLogResult.NOT_ALLOWED && ownerFallback) {
            val t = store.tasks().first().firstOrNull { it.id == taskId } ?: return
            val add = FocusTimer.clampSharedElapsedSec(elapsedSec, estimateMin)
            if (add > 0) write?.upsertTask(t.copy(totalFocused = t.totalFocused + add, updatedAt = isoNow()))
        }
    }

    /** Register a just-ADOPTED shared session with the session-signal reducer so no
     *  session_start ping fires for it — only the MINTER announces (spec §5). */
    private fun registerAdoptedSession(sid: String) {
        sigState = sigState.copy(adoptedSid = sid)
    }

    /** Probe the co-focus channel for a live session to ADOPT before minting one
     *  (join-or-mint). Owner side only probes when the task has an outgoing partner
     *  badge; callers gate the recipient side on level == partner.
     *
     *  When the VM's session-lifetime channel ALREADY holds this task's topic, a
     *  probe would open a SECOND instance on the same topic and EVICT the live one
     *  (supabase-kt keys the dispatch map by topic — last-subscribed wins). Answer
     *  join-or-mint from what the owned channel already knows instead: its current
     *  shared snapshot, else the latest replayed control. */
    private suspend fun probeCoFocusAdoption(taskId: String): SharedSessionState? {
        val owned = coFocusSession
        if (owned != null && coFocusChannelTaskId == taskId) {
            val now = nowMs()
            return listOfNotNull(owned.sharedCurrent(), owned.latestControl()?.state)
                .firstOrNull { adoptable(it, now) }
        }
        return runCatching { cofocus?.probe(taskId) }.getOrNull()
    }

    // --- in-app notification center: the log of shown notifications + an unread badge ---
    val notifications: StateFlow<List<tech.csalliance.unstuck.surface.NotificationLog.Entry>>
        get() = tech.csalliance.unstuck.surface.NotificationLog.items
    val notifUnread: StateFlow<Int> =
        combine(tech.csalliance.unstuck.surface.NotificationLog.items, tech.csalliance.unstuck.surface.NotificationLog.lastSeen) { items, seen ->
            items.count { it.at > seen }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun markNotificationsSeen() = tech.csalliance.unstuck.surface.NotificationLog.markAllSeen()

    private fun computeNudges(tasks: List<TaskItem>, captures: List<Capture>, now: Long): List<Nudge> {
        val out = mutableListOf<Nudge>()
        // D1 — slipping: open tasks older than 3 weeks or rescheduled 3+ times.
        // (recurrence == null: a hidden recurring template never "slips".)
        tasks.asSequence().filter { !it.done && it.recurrence == null }.forEach { t ->
            val ageDays = tech.csalliance.unstuck.core.time.Time.parseMillis(t.createdAt)?.let { (now - it) / 86_400_000.0 } ?: 0.0
            if (ageDays >= 21 || (t.moveCount ?: 0) >= 3) {
                out.add(Nudge("slip:${t.id}", NudgeKind.SLIPPING, "“${t.name}” has been waiting a while.", "Open", taskId = t.id))
            }
        }
        // (No capture nudge: the Inbox surfaces captures for triage, and a
        // nudge for a thought you just wrote was redundant + naggy.)
        return out.take(3)
    }

    fun blockTime(date: String, startTime: String, durationMinutes: Int, label: String) = launchWrite {
        write?.upsertCalBlock(
            CalBlock(id = newUuid(), taskId = "placeholder", taskName = label, startTime = startTime, durationMinutes = durationMinutes, date = date, kind = CalBlockKind.PLACEHOLDER),
        )
    }

    // --- focus / live session ---

    fun startFocus(task: TaskItem) = launchWrite { startFocusNow(task) }

    /** [startFocus], committed before returning — the assistant's start_focus
     *  reads the live session back in the same turn (2026-09-20: a seam that
     *  launched and answered `ok:` before the row existed). TRUE when a live
     *  session on this task is set (already running counts); FALSE when refused. */
    internal suspend fun startFocusNow(task: TaskItem): Boolean {
        // Defense in depth: a task the owner ASSIGNED OUT is view-only — never open a live
        // session on it, even via a deep-link / command that bypasses the hidden button.
        // (Recurring occurrences are never assigned out, so their block id won't match.)
        if (assignedOut.value.containsKey(task.id)) return false
        val cur = store.getLiveSession()
        // Focusing a recurring OCCURRENCE: run the session on the TEMPLATE (so
        // totalFocused accrues on the series) but remember the occurrence block so
        // completion marks just this day. Resolve before the same-task guard.
        //
        // Two shapes reach here, and BOTH must end up bound to (template, day's
        // block). Every in-app Start hands us the projected occurrence row, whose id
        // is the block id (occurrenceBlockFor). The reminder notification's "Start"
        // action instead deep-links `unstuck://focus/<block.task_id>` — the hidden
        // TEMPLATE — and that path used to open a session on the template with NO
        // occurrence attached: the minutes accrued on the series but "Done" flipped
        // `done` on the template (a row no list shows, which keeps generating) while
        // today's occurrence stayed open. liveOccurrenceBlockForTemplate closes that
        // at the same choke point.
        val tapped = occurrenceBlockFor(task.id, tasks.value, blocks.value)
        val occ = tapped
            ?: liveOccurrenceBlockForTemplate(task.id, tasks.value, blocks.value, Clock.todayIso())
        if (occ != null) {
            val tpl = tasks.value.firstOrNull { it.id == occ.taskId }
            if (tpl != null) {
                if (cur?.taskId == tpl.id) {
                    // Re-entering the SAME template's live session keeps it exactly as
                    // the non-occurrence path does — never re-probe/re-mint on re-entry
                    // (a re-mint replaced THE shared session and broadcast a spurious
                    // ended). A tapped occurrence row re-points the completion target
                    // at that day. Coming back through the TEMPLATE (Today's live card,
                    // the assistant's PAUSED chip, the capture link, a reminder's
                    // Start) keeps the session's OWN day while its block exists, ticked
                    // or not:
                    // re-pointing it at today's first open block made Done tick today
                    // and left the overdue day it was started on open (parity with
                    // iOS build 81 reopenLiveFocus, audit 2026-09-22 C3).
                    val ownDay = cur.occurrenceBlockId?.takeIf { id -> tapped == null && blocks.value.any { it.id == id && it.taskId == tpl.id } }
                    val target = ownDay ?: occ.id
                    if (cur.sessionStart != null && cur.occurrenceBlockId != target) {
                        store.setLiveSession(cur.copy(occurrenceBlockId = target))
                    }
                    return true
                }
                // Displacing a DIFFERENT task's live session (own OR shared): finalize it
                // first so its elapsed isn't silently discarded — same guard the
                // non-occurrence path uses. Without this, starting a recurring occurrence
                // over a live shared session dropped the partner's minutes (owner never
                // credited). Matches web, which finalizes all displacements.
                if (cur != null && cur.sessionStart != null && cur.taskId != tpl.id) finalizeDisplaced(cur)
                val base = cur ?: FocusTimer.empty
                // Join-or-mint (one-true-shared-session): a partner-shared template may
                // already have a LIVE session (the partner started) — adopt it (same
                // sessionId + clock) instead of minting a second one. Partner-shared
                // sessions run on the SESSION clock (priorAccumulatedSec = 0, minted or
                // adopted) so every device's ring shows the same number.
                // An occurrence seeds NO prior either: the template's totalFocused is the
                // series' LIFETIME focus, not progress on this day's slice. Seeded into the
                // ring, day 4 of a 25-min habit opened at 75:00, overrun from the first
                // second with the coach saying "That's your block" (Android audit
                // 2026-09-23, A13; web W10).
                val partnerSharedOcc = shareBadges.value[tpl.id].orEmpty().any { it.level == ShareLevel.PARTNER }
                val adoptedOcc = if (partnerSharedOcc) probeCoFocusAdoption(tpl.id) else null
                val live = if (adoptedOcc != null) {
                    registerAdoptedSession(adoptedOcc.sessionId)
                    FocusTimer.adopt(base, tpl.id, adoptedOcc, now = nowMs(), priorAccumulatedSec = 0, occurrenceBlockId = occ.id)
                } else {
                    FocusTimer.start(base, tpl.id, estimateMin = occ.durationMinutes, priorAccumulatedSec = 0, now = nowMs(), occurrenceBlockId = occ.id)
                }
                store.setLiveSession(FocusTimer.setTreatment(live, _settings.value.treatment))
                return true
            }
        }
        // Re-entering the SAME task's live session keeps its current state — a
        // paused session stays paused (the user resumes explicitly), it isn't
        // auto-resumed just by opening the focus screen.
        if (cur?.taskId == task.id) return true
        // Replacing a DIFFERENT task's live session: finalize it first (own → write its
        // Session row + accumulate focus; shared → accrue onto the owner) so the elapsed
        // time isn't silently discarded — same finalize as finishFocus(markDone=false).
        if (cur != null && cur.sessionStart != null && cur.taskId != task.id) finalizeDisplaced(cur)
        val base = cur ?: FocusTimer.empty
        // Join-or-mint (one-true-shared-session): if a partner already runs THE
        // session on this task, adopt it — same sessionId, same clock, no new session,
        // and no session_start ping (only the minter announces). Partner-shared
        // sessions run on the SESSION clock (priorAccumulatedSec = 0, minted or
        // adopted, owner included) so every device's ring shows the same number.
        val partnerShared = shareBadges.value[task.id].orEmpty().any { it.level == ShareLevel.PARTNER }
        val adopted = if (partnerShared) probeCoFocusAdoption(task.id) else null
        if (adopted != null) {
            registerAdoptedSession(adopted.sessionId)
            val live = FocusTimer.adopt(base, task.id, adopted, now = nowMs(), priorAccumulatedSec = 0)
            store.setLiveSession(FocusTimer.setTreatment(live, _settings.value.treatment))
            return true
        }
        // Seed prior focus so reopening after "End for now" continues from the
        // accumulated total instead of restarting the displayed timer at 0. Not for
        // a repeating series with no open day to attach (none today, or the blocks
        // not loaded yet on a cold notification Start): its total is the series'
        // lifetime focus, never progress on this sitting (Android audit 2026-09-23, A13).
        val seedsPrior = !partnerShared && task.recurrence == null
        val live = FocusTimer.start(base, task.id, estimateMin = task.estimateMin, priorAccumulatedSec = if (seedsPrior) task.totalFocused else 0, now = nowMs())
        store.setLiveSession(FocusTimer.setTreatment(live, _settings.value.treatment))
        return true
    }

    /** Start a REAL focus session on a task someone shared WITH me (T3, Option B). The
     *  task is NOT in my store, so the session carries a shared marker ([sharedTitle] +
     *  [level]); finish / cancel / displace then accrue the time onto the OWNER's task
     *  via log_shared_focus instead of writing an own Session row / totalFocused. Gated
     *  to partner/assign (view can't act; the RPC rejects it server-side too). */
    fun startSharedFocus(taskId: String, title: String, estimateMin: Int, level: ShareLevel) = launchWrite {
        if (!level.canComplete) return@launchWrite   // view is read-only company
        val cur = store.getLiveSession()
        // Already focusing this shared task (e.g. returning from Today) — keep its state.
        if (cur?.taskId == taskId) return@launchWrite
        // Replacing a different live session: finalize it first (own OR shared).
        if (cur != null && cur.sessionStart != null && cur.taskId != taskId) finalizeDisplaced(cur)
        val base = cur ?: FocusTimer.empty
        // Join-or-mint (one-true-shared-session, partner level only): adopt the owner's
        // live session when there is one — the same sessionId finalizes exactly once
        // via the ledger regardless of who finishes.
        val adopted = if (level == ShareLevel.PARTNER) probeCoFocusAdoption(taskId) else null
        // priorAccumulatedSec = 0: the recipient's session is standalone; the owner's
        // running total is reflected server-side via log_shared_focus on finish.
        val live = (
            if (adopted != null) FocusTimer.adopt(base, taskId, adopted, now = nowMs(), priorAccumulatedSec = 0)
            else FocusTimer.start(base, taskId, estimateMin = estimateMin, priorAccumulatedSec = 0, now = nowMs())
            ).copy(sharedTitle = title, sharedLevel = level.wire)
        store.setLiveSession(FocusTimer.setTreatment(live, _settings.value.treatment))
    }

    /** Finalize a live session being DISPLACED by starting another one. Own → write the
     *  Session row + accrue totalFocused; shared → accrue onto the owner via
     *  log_shared_focus (never mint an own-store row for a task that isn't mine). */
    private suspend fun finalizeDisplaced(cur: LiveSession) {
        val elapsed = FocusTimer.elapsedSec(cur, nowMs())
        if (cur.sharedTitle != null) {
            // Snapshot the id + clear the live session BEFORE the suspending RPC so a
            // concurrent finalize can't observe a still-live shared session (server
            // idempotency also guards, but this is cleaner). The caller installs the
            // replacement session after we return.
            val sid = cur.id ?: newUuid()
            store.setLiveSession(null)
            releaseCapturesOf(cur.id)   // no own Session row for a task shared with me (A14)
            accrueSharedFocus(cur.taskId, elapsed, sid, cur.sessionEstimateMin, ownerFallback = false)
            refreshShares()
            return
        }
        // The task was deleted meanwhile: no Session row, so its captures go up without
        // one, and without the task, which the server would refuse (A14).
        val prev = store.tasks().first().firstOrNull { it.id == cur.taskId } ?: run {
            write?.unlinkCapturesFromTask(cur.taskId)
            releaseCapturesOf(cur.id)
            return
        }
        val sid = cur.id ?: newUuid()
        // estimateMin = the session's OWN plan (the live estimate, extends included),
        // as the web and the notification End write it — the D1 clamp reads it
        // (analytics P1-13, 2026-09-24).
        write?.upsertSession(Session(id = sid, taskId = prev.id, taskName = prev.name, estimateMin = cur.sessionEstimateMin, actualSec = elapsed, completedAt = isoNow()))
        if (accruesViaSharedLedger(cur, prev.id, shareBadges.value)) {
            // One-true-shared-session accrual: a partner-shared task's total accrues
            // EXCLUSIVELY via the ledger (exactly-once per session id — the partner may
            // finalize the SAME session). Routed by the live blob's own markers (rev
            // stamps) as well as the badge cache: on a cold start the badges RPC may
            // not have resolved, and a direct bump here + the partner's ledger write
            // credited the task twice. Land the row writes first so the whole-row
            // upsert can't clobber the server-side accrual, then log (durably: an
            // offline failure queues a persisted retry; a revoked share falls back to
            // the direct bump — see accrueSharedFocus).
            flushOutbox()
            accrueSharedFocus(prev.id, elapsed, sid, cur.sessionEstimateMin, ownerFallback = true)
        } else {
            write?.upsertTask(prev.copy(totalFocused = prev.totalFocused + elapsed, updatedAt = isoNow()))
        }
    }

    fun pauseFocus() = launchWrite { mutateLive(control = true) { FocusTimer.pause(it, nowMs()) } }
    fun resumeFocus() = launchWrite { resumeFocusNow() }

    /** Resume, and write the pause's length onto the reason log picked for it
     *  (analytics P0-3 / D5). FALSE when there is no live session. */
    internal suspend fun resumeFocusNow(): Boolean {
        val before = store.getLiveSession() ?: return false
        val now = nowMs()
        if (!mutateLive(control = true) { FocusTimer.resume(it, now) }) return false
        FocusCommands.recordPauseLength(store, write, before, now)
        return true
    }
    fun setTreatment(t: FocusTreatment) = launchWrite {
        mutateLive { FocusTimer.setTreatment(it, t) }
        updateSettings { it.copy(treatment = t) }
    }
    fun extendFocus(minutes: Int) = launchWrite { mutateLive(control = true) { FocusTimer.extend(it, minutes) } }

    /**
     * End the focus session. Mirrors the web's two finish actions:
     * - markDone = false → "End for now": record the session, keep the task open
     *   (returning later resumes at the accumulated total). This is the safe default.
     * - markDone = true → "Mark complete / Done early": also flip the task done.
     */
    fun finishFocus(task: TaskItem, markDone: Boolean = false) = launchWrite { finishFocusNow(task, markDone) }

    /** [finishFocus], committed before returning — the assistant's finish_focus
     *  (2026-09-20) runs the SAME path the Focus screen's Done / Stop here
     *  buttons do. FALSE when no session was running (nothing logged).
     *  [onSharedTick] hears, for a markDone on a task shared WITH me, why the
     *  owner's task was NOT ticked (null = it was). */
    internal suspend fun finishFocusNow(task: TaskItem, markDone: Boolean = false, onSharedTick: ((refusal: String?) -> Unit)? = null): Boolean {
        val live = store.getLiveSession() ?: return false
        val elapsed = FocusTimer.elapsedSec(live, nowMs())
        // Finishing while paused: the pause's reason log gets its length too.
        FocusCommands.recordPauseLength(store, write, live, nowMs())
        // Shared focus (T3, Option B): the task isn't in MY store — reflect the time
        // onto the OWNER's task via log_shared_focus (partner/assign only) instead of
        // writing an own Session row / totalFocused, and complete it via
        // shared_task_set_done. The recipient still gets a normal local recap.
        val sharedTitle = live.sharedTitle
        if (sharedTitle != null) {
            // Snapshot the id + clear the live session BEFORE the suspending RPCs so a
            // concurrent finalize can't observe a still-live shared session (server
            // idempotency also guards, but this is cleaner). elapsed is snapshotted above.
            val sid = live.id ?: newUuid()
            store.setLiveSession(null)
            releaseCapturesOf(live.id)   // no own Session row for a task shared with me (A14)
            accrueSharedFocus(live.taskId, elapsed, sid, live.sessionEstimateMin, ownerFallback = false)
            // Never a repeating share: the server refuses that tick ('recurring_series',
            // 075 §1) and the owner ticks each day (parity with iOS build 81, audit
            // 2026-09-22 C3). A refused tick pings nobody (SC-12). The outcome goes
            // back to the caller: this pre-check reads the Shared-with-you list, a
            // WhileSubscribed cache that is empty while no screen collects it (a call
            // with the app in the background), so a repeating share can still reach
            // the RPC, and its refusal must not be reported as a tick.
            if (markDone) {
                val refusal =
                    if (sharedTaskAllowsTick(live.taskId)) setSharedTaskDone(live.taskId, true)?.let { it.message ?: "failed" }
                    else RECURRING_SERIES_REFUSAL
                onSharedTick?.invoke(refusal)
            }
            refreshShares()
            runCatching { graph.coordinator?.notifications?.sessionRecap(sharedTitle, away = false) }
            _lastRecap.value = RecapState(taskName = sharedTitle, focusedSec = elapsed, at = nowMs())
            return true
        }
        // Resolve a recurring OCCURRENCE robustly — via live.occurrenceBlockId OR
        // (defensively) the passed task's id being a cal_block id. The session +
        // totalFocused always accrue on the TEMPLATE; completion marks the DAY's
        // block. This guarantees we never upsert a task whose id is a block id
        // (which would mint a phantom occurrence-as-task).
        // The last fallback is the BACKSTOP for a session minted before startFocus
        // resolved a TEMPLATE (the notification "Start" path, incl. one persisted
        // across that upgrade): without it, finishing with Done flipped the hidden
        // template's own `done` and today's occurrence never ticked.
        // Everything below lands on the rows as STORED, never the caller's copy
        // (FocusScreen's snapshot, the assistant's scratch) or the WhileSubscribed
        // caches: writing a copy whole reverted edits made during the session, and
        // re-created a task deleted meanwhile (parity with iOS build 81, audit
        // 2026-09-22 C5).
        val storedTasks = store.tasks().first()
        val storedBlocks = store.blocks().first()
        val occBlock = live.occurrenceBlockId?.let { id -> storedBlocks.firstOrNull { it.id == id } }
            ?: occurrenceBlockFor(task.id, storedTasks, storedBlocks)
            ?: liveOccurrenceBlockForTemplate(task.id, storedTasks, storedBlocks, Clock.todayIso())
        // The row this finish lands on — the TEMPLATE for an occurrence. Null when it
        // was deleted elsewhere mid-session: then no task (or block) is written, and
        // the Session goes up without the dead task id — sessions.task_id references
        // tasks(id), so that insert would fail and sit quarantined in the outbox
        // (the minutes still count in insights).
        val stored = storedTasks.firstOrNull { it.id == (occBlock?.taskId ?: task.id) }
        val realTask = stored ?: task
        // One-true-shared-session accrual (owner side): EVERY session on a partner-
        // shared task accrues total_focused EXCLUSIVELY via the log_shared_focus ledger
        // (exactly-once by session id — the partner finalizes the SAME id), so the
        // direct += bump is SKIPPED here. The Session row (insights) is still written.
        // Decided from the live blob's own markers (rev stamps prove it was shared-
        // broadcast) AND the badge cache — the cache alone is empty on a cold start /
        // offline relaunch, which made the owner double-credit the task.
        val partnerShared = accruesViaSharedLedger(live, realTask.id, shareBadges.value)
        val sid = live.id ?: newUuid()
        // A task deleted elsewhere: its captures go up without the dead id too, as the
        // Session does. captures.task_id references tasks(id) as well, so each was
        // refused and quarantined (Android audit 2026-09-23, A14).
        if (stored == null && storedTasks.none { it.id == live.taskId }) write?.unlinkCapturesFromTask(live.taskId)
        // Reuse the live-session id so captures taken during the session join back
        // to this Session row (the interruption histogram depends on it).
        write?.upsertSession(
            // estimateMin = the session's own plan (live estimate incl. extends) — web
            // and the notification End already wrote this; the D1 clamp reads it (P1-13).
            Session(id = sid, taskId = stored?.id, taskName = realTask.name, estimateMin = live.sessionEstimateMin, actualSec = elapsed, completedAt = isoNow()),
        )
        // NEVER flip `done` on a recurring TEMPLATE: that ends the whole series
        // (the template stops generating and shows in no list), which is not what
        // "I finished this session" means. Reachable from the starts-now
        // notification's "Start" when today's occurrence was ticked or skipped
        // between the notification and the tap, so no block resolves here. The
        // time still accrues on the series; no day is falsely marked off. Judged on
        // the stored row: a repeat set elsewhere mid-session is respected, and a
        // task already completed elsewhere is neither re-stamped nor announced to
        // its shared list a second time.
        val completes = markDone && occBlock == null && stored != null && stored.recurrence == null && !stored.done
        if (stored != null && occBlock != null) {
            if (!partnerShared) write?.upsertTask(stored.copy(totalFocused = stored.totalFocused + elapsed, updatedAt = isoNow()))
            if (markDone) write?.upsertCalBlock(occBlock.copy(done = true, skipped = false, completedAt = isoNow()))
        } else if (stored != null) {
            val focused =
                if (partnerShared) stored.copy(updatedAt = isoNow())   // total via the ledger
                else stored.copy(totalFocused = stored.totalFocused + elapsed, updatedAt = isoNow())
            if (completes) {
                write?.upsertTask(applyCompletion(focused.copy(done = true), prior = stored, nowISO = isoNow()))
            } else if (!partnerShared) {
                write?.upsertTask(focused)
            }
            // partner-shared + end-for-now: no task write at all — nothing changed on
            // the row; the total accrues server-side (realtime/hydrate brings it back).
        }
        store.setLiveSession(null)
        if (partnerShared && stored != null) {
            // Land the row writes first (the whole-row upsert must not clobber the
            // server-side accrual), then log DURABLY: the RPC clamps + dedups on
            // session id; a transient failure queues a persisted retry and a revoked
            // share falls back to the direct bump (accrueSharedFocus).
            flushOutbox()
            accrueSharedFocus(realTask.id, elapsed, sid, live.sessionEstimateMin, ownerFallback = true)
        }
        // Completing a promoted shared-collection task from Focus must also flip the
        // shared item + notify members (same as toggleDone).
        // (…and only when the row was ACTUALLY completed above — a template is not.)
        if (completes && stored?.sourceCollectionId != null && stored.sourceItemId != null) {
            share?.taskDone(stored.sourceCollectionId!!, stored.sourceItemId!!, stored.name, currentName ?: "Someone")
        }
        // Session-end recap (design moment B3): records an in-app card always; the
        // server only pushes when away — finishing in-app means away = false.
        runCatching { graph.coordinator?.notifications?.sessionRecap(realTask.name, away = false) }
        _lastRecap.value = RecapState(taskName = realTask.name, focusedSec = elapsed, at = nowMs())
        return true
    }

    // The most recent session-end recap, surfaced as a dismissible card on Today
    // (kept alongside ReflectSheet). Cleared when dismissed.
    private val _lastRecap = MutableStateFlow<RecapState?>(null)
    val lastRecap: StateFlow<RecapState?> = _lastRecap
    fun dismissRecap() { _lastRecap.value = null }

    /** FALSE when there is no live session to change (the assistant's pause /
     *  resume / extend answer `error:` off that, never `ok:` — 2026-09-20). */
    private suspend fun mutateLive(control: Boolean = false, transform: (LiveSession) -> LiveSession): Boolean {
        val cur = store.getLiveSession() ?: return false
        var next = transform(cur)
        // One-true-shared-session: a LOCAL control (pause/resume/extend) on a partner
        // co-focus session stamps the next (rev, atMs) ATOMICALLY (same Room write),
        // so a single consistent blob classifies local-vs-remote
        // (core.logic.remotePaused) and the reducer floor carries this control's wall
        // clock (core.logic.sharedRevFloor — without it a rev-tie race could swap the
        // two sides, and a peer re-announce could flip a genuinely local pause to
        // remote). The broadcaster ships exactly this (rev, atMs). setTreatment stays
        // unstamped (local-only).
        if (control && next != cur && isPartnerCoFocus(cur, shareBadges.value)) {
            next = next.copy(
                sharedSessionRev = maxOf(cur.sharedSessionRev ?: 0, cur.lastAppliedRev ?: 0) + 1,
                sharedSessionAtMs = nowMs(),
            )
        }
        store.setLiveSession(next)
        return true
    }

    // --- captures / reasons ---

    fun saveCapture(taskId: String?, sessionId: String?, tag: CaptureTag, body: String) = launchWrite {
        val text = body.trim(); if (text.isEmpty()) return@launchWrite
        write?.upsertCapture(Capture(id = newUuid(), taskId = ownTaskIdFor(taskId), sessionId = sessionId, tag = tag, body = text, at = isoNow()))
    }

    fun saveReasonLog(taskId: String?, reason: String, action: ReasonAction = ReasonAction.PAUSE, durationSec: Int? = null) = launchWrite {
        val id = newUuid()
        write?.upsertReasonLog(ReasonLog(id = id, taskId = ownTaskIdFor(taskId), reason = reason, action = action, at = isoNow(), durationSec = durationSec))
        // A reason picked for the CURRENT pause: remember it on the live session so
        // the resume (or a finish while paused) writes the pause's length back onto
        // it (analytics P0-3 / D5). Device-local; not a shared control. After the
        // row is stored, so a resume always finds the row it updates; the read and
        // write are back to back and only touch a still-paused session.
        if (action == ReasonAction.PAUSE && durationSec == null) {
            val cur = store.getLiveSession()
            if (cur != null && cur.paused && cur.pausedAt != null) store.setLiveSession(cur.copy(pendingReasonId = id))
        }
    }

    /** The task a capture or pause reason filed on row [rowId] belongs to. Focus on
     *  a repeating task's day runs on a row whose id is the day's cal_block id
     *  (taskForBlock), which is no task: captures.task_id references tasks(id), so
     *  every capture made there was refused on each flush and never left the phone.
     *  It belongs to the series template, the task the live session runs on, as on
     *  iOS (Android audit 2026-09-23, A14). */
    private suspend fun ownTaskIdFor(rowId: String?): String? {
        if (rowId == null) return null
        return occurrenceBlockFor(rowId, store.tasks().first(), store.blocks().first())?.taskId ?: rowId
    }

    // When this model was made, on the outbox's clock (OutboxEntity.createdAt is wall
    // time, not the nowProvider seam): captures queued before it are an earlier run's.
    private val startedAtMs = System.currentTimeMillis()

    init {
        // Captures an earlier build left stuck in the outbox: filed on a repeating
        // task's day id, or held behind a session that never wrote its row. Heal them
        // once per start (idempotent; see WriteThrough.healStrandedCaptures) (Android
        // audit 2026-09-23, A14).
        viewModelScope.launch { runCatching { write?.healStrandedCaptures(queuedBefore = startedAtMs) } }
    }

    /** A live session ended without writing its Session row: release the captures
     *  queued behind it (see WriteThrough.detachCapturesFromSession). */
    private suspend fun releaseCapturesOf(sessionId: String?) {
        if (sessionId != null) write?.detachCapturesFromSession(sessionId)
    }

    fun deleteCapture(id: String) = launchWrite { deleteCaptureNow(id) }

    /** [deleteCapture], committed before returning (the assistant executor reads
     *  between its own writes). */
    internal suspend fun deleteCaptureNow(id: String) {
        write?.deleteCapture(id)
        // Drop the cached archived flag so the set doesn't leak ids — locally only:
        // the row is gone, there is nothing to un-archive on the server.
        applyArchivedCaptureIds(_archivedCaptureIds.value - id)
        graph.settings.savePendingCaptureArchiveWrites(graph.settings.loadPendingCaptureArchiveWrites() - id)
    }

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

    /**
     * Suspend until [id] is READABLE in [collections] — i.e. the local write has
     * landed in Room AND the read flow has published it.
     *
     * Anything that NAVIGATES to a just-created collection needs this.
     * [upsertCollection] is fire-and-forget (`launchWrite` → Room → a `flowOn`
     * Room flow → the StateFlow), so the row is NOT resolvable in the frame the
     * caller gets the id back, and CollectionDetailScreen treats an id it cannot
     * resolve as deleted — it calls `onBack()`. Pushing straight after creating
     * therefore bounced the user back to the grid whenever the write lost the
     * race, which an emulator run reproduced readily — a majority of repeated
     * create-and-open attempts bounced. With the wait, none did.
     *
     * Deliberately UNBOUNDED. A timeout here would only convert a slow device
     * into a silently different outcome (create, then no navigation, for no
     * reason the user can see); the caller bounds it instead by running this in
     * its own composition scope, so the wait dies with the screen that wants the
     * navigation. If the write never lands there is nothing to navigate to
     * anyway — the user keeps the surface they are on.
     */
    suspend fun awaitCollectionReadable(id: String) {
        collections.first { list -> list.any { it.id == id } }
    }
    fun deleteCollection(id: String) = launchWrite { deleteCollectionNow(id) }
    /** [deleteCollection], committed before returning (the assistant executor
     *  reads the lists back in the same round). */
    suspend fun deleteCollectionNow(id: String): Boolean {
        // No write layer (demo / tests) used to make this a silent no-op that
        // still answered "deleted" — fall back to the local store like the
        // other row deletes do (2026-09-20).
        write?.deleteCollection(id) ?: store.delete(tech.csalliance.unstuck.data.db.Tables.COLLECTIONS, id)
        return true
    }

    // --- shared-collection helpers (migration 020/022) ---
    internal fun currentUid(): String? = currentUidProvider?.invoke() ?: auth?.currentUserId
    /** A collection is shared if it has members, or it's owned by someone else. Guard on
     *  a KNOWN current uid — a transiently-null uid must not mis-classify your OWN list as
     *  shared (that routes edits down the RPC-only path with no outbox → silent loss). */
    fun isShared(c: ItemCollection): Boolean {
        val uid = currentUid()
        return c.members.isNotEmpty() || (c.ownerId != null && uid != null && c.ownerId != uid)
    }
    /** Owner (or a local/demo row with no ownerId). Gates rename/recolor/delete/share. */
    fun isOwner(c: ItemCollection): Boolean { val uid = currentUid(); return c.ownerId == null || c.ownerId == uid }
    /** A view-only member can't edit items; owner + editor + local can. */
    fun canEdit(c: ItemCollection): Boolean = c.myRole != "viewer"

    // Collection item ops. For OWN/unshared lists, whole-row upsert via the
    // outbox (handles brand-new rows + offline). For SHARED lists, an optimistic
    // local write + an atomic item RPC so two people editing concurrently don't
    // clobber each other's items array. Each mutation is serialized + re-resolves
    // the LATEST collection from Room first (web's functional-update guard).
    private val collectionMutex = Mutex()
    private fun mutateCollection(id: String, transform: (ItemCollection) -> ItemCollection) = launchWrite { mutateCollectionNow(id, transform) }
    /** [mutateCollection], COMMITTED before returning. The assistant executor
     *  reads its own writes back inside a single turn (rename_list then get_lists
     *  in one round), so its entry points must await the local commit — the
     *  fire-and-forget launch made those reads miss the change (review section 4). */
    private suspend fun mutateCollectionNow(id: String, transform: (ItemCollection) -> ItemCollection): Boolean =
        collectionMutex.withLock {
            // FALSE when the row is gone: the assistant maps it to `error:` instead
            // of reporting a rename / recolour / archive that never happened.
            val latest = store.collections().first().firstOrNull { it.id == id } ?: return@withLock false
            val next = transform(latest)
            if (isShared(latest) && share != null) {
                // Shared list: rename/recolor/archive update ONLY the metadata columns
                // (a partial UPDATE) so we don't ship the items JSONB and clobber a
                // member's concurrent item edit. Optimistic local write first.
                store.upsert(tech.csalliance.unstuck.data.db.Tables.COLLECTIONS, next, ItemCollection.serializer(), next.id)
                share?.updateCollectionFields(id, next.name, next.color, next.subtitle ?: "", next.archived ?: false)
            } else {
                write?.upsertCollection(next)
            }
            true
        }
    private fun mutateCollectionItem(
        id: String,
        transform: (ItemCollection) -> ItemCollection,
        rpc: () -> tech.csalliance.unstuck.sync.CollectionRpc,
    ) = launchWrite { mutateCollectionItemNow(id, transform, rpc) }
    /** [mutateCollectionItem], COMMITTED before returning (see [mutateCollectionNow]). */
    private suspend fun mutateCollectionItemNow(
        id: String,
        transform: (ItemCollection) -> ItemCollection,
        rpc: () -> tech.csalliance.unstuck.sync.CollectionRpc,
    ): Boolean =
        collectionMutex.withLock {
            val latest = store.collections().first().firstOrNull { it.id == id } ?: return@withLock false
            val next = transform(latest)
            if (isShared(latest)) {
                // Optimistic local write, then the atomic item RPC goes through the
                // OUTBOX as an idempotent `rpc` op (offline / 5xx → retried on the next
                // drain; a server refusal → the row is re-pulled and the user told —
                // SyncCoordinator.collectionSyncErrors). It used to be fire-and-forget:
                // a failed RPC left the optimistic row locally until the next echo /
                // hydrate silently deleted it.
                store.upsert(tech.csalliance.unstuck.data.db.Tables.COLLECTIONS, next, ItemCollection.serializer(), next.id)
                write?.enqueueCollectionRpc(id, rpc())
            } else {
                write?.upsertCollection(next)
            }
            true
        }
    fun addCollectionItem(col: ItemCollection, body: String) = launchWrite { addCollectionItemNow(col, body) }
    /** [addCollectionItem], committed before returning (the assistant executor).
     *  → the new item's id, or null when nothing was written (blank body, list gone). */
    suspend fun addCollectionItemNow(col: ItemCollection, body: String): String? {
        val text = body.trim(); if (text.isEmpty()) return null
        val item = tech.csalliance.unstuck.core.model.CollectionItem(newUuid(), text, at = isoNow())
        val written = mutateCollectionItemNow(col.id,
            { it.copy(items = it.items + item) },
            { CollectionRpcs.addItem(col.id, item.id, item.body, item.at) })
        return if (written) item.id else null
    }
    fun updateCollectionItemBody(col: ItemCollection, itemId: String, body: String) = launchWrite { updateCollectionItemBodyNow(col, itemId, body) }
    /** [updateCollectionItemBody], committed before returning. */
    suspend fun updateCollectionItemBodyNow(col: ItemCollection, itemId: String, body: String): Boolean {
        val text = body.trim()
        return mutateCollectionItemNow(col.id,
            { c -> c.copy(items = c.items.map { if (it.id == itemId) it.copy(body = text) else it }) },
            { CollectionRpcs.updateItem(col.id, itemId, text) })
    }
    fun toggleCollectionItemPin(col: ItemCollection, itemId: String) {
        var nextVal = false
        mutateCollectionItem(col.id,
            { c -> c.copy(items = c.items.map { if (it.id == itemId) { nextVal = !(it.pinned ?: false); it.copy(pinned = nextVal) } else it }) },
            { CollectionRpcs.setItemFlag(col.id, itemId, "pinned", nextVal) })
    }
    /** The assistant's pin_list_item (2026-09-20): the same flag write as the
     *  list row's pin, but to an explicit state and committed before returning. */
    suspend fun setCollectionItemPinnedNow(col: ItemCollection, itemId: String, pinned: Boolean): Boolean =
        mutateCollectionItemNow(col.id,
            { c -> c.copy(items = c.items.map { if (it.id == itemId) it.copy(pinned = pinned) else it }) },
            { CollectionRpcs.setItemFlag(col.id, itemId, "pinned", pinned) })
    fun toggleCollectionItemDone(col: ItemCollection, itemId: String) = launchWrite { toggleCollectionItemDoneNow(col, itemId) }
    /** [toggleCollectionItemDone], committed before returning. */
    suspend fun toggleCollectionItemDoneNow(col: ItemCollection, itemId: String): Boolean {
        var nextVal = false
        return mutateCollectionItemNow(col.id,
            { c -> c.copy(items = c.items.map { if (it.id == itemId) { nextVal = !(it.done ?: false); it.copy(done = nextVal) } else it }) },
            { CollectionRpcs.setItemFlag(col.id, itemId, "done", nextVal) })
    }
    fun removeCollectionItem(col: ItemCollection, itemId: String) = launchWrite { removeCollectionItemNow(col, itemId) }
    /** [removeCollectionItem], committed before returning. */
    suspend fun removeCollectionItemNow(col: ItemCollection, itemId: String): Boolean =
        mutateCollectionItemNow(col.id,
            { c -> c.copy(items = c.items.filterNot { it.id == itemId }) },
            { CollectionRpcs.removeItem(col.id, itemId) })

    /** Shared-list edits the server refused (rolled back already) — the detail
     *  screen shows them. Empty flow when there's no sync engine (tests / demo). */
    val collectionSyncErrors: kotlinx.coroutines.flow.Flow<String>
        get() = graph.coordinator?.collectionSyncErrors ?: kotlinx.coroutines.flow.emptyFlow()
    fun renameCollection(col: ItemCollection, name: String) = launchWrite { renameCollectionNow(col, name) }
    /** [renameCollection], committed before returning. */
    suspend fun renameCollectionNow(col: ItemCollection, name: String): Boolean {
        val nm = name.trim()
        return nm.isNotEmpty() && mutateCollectionNow(col.id) { it.copy(name = nm) }
    }
    fun recolorCollection(col: ItemCollection, color: String) = mutateCollection(col.id) { it.copy(color = color) }
    /** [recolorCollection], committed before returning. */
    suspend fun recolorCollectionNow(col: ItemCollection, color: String) = mutateCollectionNow(col.id) { it.copy(color = color) }
    fun archiveCollection(id: String, archived: Boolean) = mutateCollection(id) { it.copy(archived = archived) }
    /** [archiveCollection], committed before returning. */
    suspend fun archiveCollectionNow(id: String, archived: Boolean) = mutateCollectionNow(id) { it.copy(archived = archived) }

    // --- Move to task (promote a collection item to a task) ---
    enum class PromoteMode { SELF, LOOP }   // LOOP = keep everyone in the loop (shared accountability)

    /** Mark an item as promoted (struck + status chip), synced to all members on
     *  a shared list. done = false → "on it", null → static "Promoted". */
    private suspend fun markItemPromotedNow(col: ItemCollection, itemId: String, assignee: String, done: Boolean?, dueAt: String?) {
        mutateCollectionItemNow(col.id,
            { c -> c.copy(items = c.items.map { if (it.id == itemId) it.copy(promoted = true, assignee = assignee, promotedDone = done, dueAt = dueAt) else it }) },
            { CollectionRpcs.setItemPromotion(col.id, itemId, assignee, done, dueAt) })
    }

    /** Turn a collection item into a task. LOOP on a shared list links the task to
     *  the item (so completion/lateness flows back to everyone) + sets a "by" time. */
    fun moveItemToTask(col: ItemCollection, item: CollectionItem, mode: PromoteMode, dueAtIso: String? = null) =
        launchWrite { moveItemToTaskNow(col, item, mode, dueAtIso) }

    /** [moveItemToTask], every write COMMITTED before returning — the assistant's
     *  `promote_item_to_task` reads the task and the list back in the same turn.
     *  Returns the new task, or null when the guard refused (already in flight). */
    suspend fun moveItemToTaskNow(col: ItemCollection, item: CollectionItem, mode: PromoteMode, dueAtIso: String? = null): TaskItem? {
        // Guard: don't duplicate a task for an item that's already promoted + in
        // flight (a completed one may be re-promoted for a fresh cycle).
        if (item.promoted == true && item.promotedDone != true) return null
        val loop = mode == PromoteMode.LOOP && isShared(col)
        val task = newTaskRow(
            name = item.body, estimateMin = 25, tags = listOf("from-collection"),
            sourceCollectionId = if (loop) col.id else null,
            sourceItemId = if (loop) item.id else null,
            dueAt = if (loop) dueAtIso else null,
        )
        write?.upsertTask(task) ?: store.upsert(tech.csalliance.unstuck.data.db.Tables.TASKS, task, TaskItem.serializer(), task.id, task.updatedAt)
        // Schedule keep-in-loop tasks at the "by" time so they show on the calendar.
        if (loop && dueAtIso != null) {
            runCatching { java.time.Instant.parse(dueAtIso).atZone(java.time.ZoneId.systemDefault()) }.getOrNull()?.let { z ->
                scheduleTaskNow(task, z.toLocalDate().toString(), WireTime.hm(z.hour, z.minute))
            }
        }
        // "Just me" on a SHARED list must NOT announce to the others (it would mark the
        // shared item "<you>'s on it" for everyone with no way to clear). Only mark when
        // keeping-in-loop, or on a solo list (where it's a local-only "Promoted" chip).
        if (loop || !isShared(col)) {
            markItemPromotedNow(col, item.id, assignee = currentName ?: "Someone",
                done = if (loop) false else null, dueAt = if (loop) dueAtIso else null)
        }
        return task
    }

    // --- collection sharing (edge function-backed) ---
    suspend fun shareCollection(collectionId: String, email: String, role: String): tech.csalliance.unstuck.sync.ShareOutcome =
        shareCollectionDetailed(collectionId, email = email, userId = null, role = role).outcome

    /** Share a list by email (Someone new) OR by user id (a connection tapped in
     *  People — unified sharing v1). Answers what the SERVER did (by userId the
     *  answer is honest: `shared`; by email it is deliberately neutral). */
    suspend fun shareCollectionDetailed(
        collectionId: String, email: String?, userId: String?, role: String,
    ): tech.csalliance.unstuck.sync.CollectionShareClient.ShareResult {
        val result = share?.shareDetailed(collectionId, email = email, userId = userId, role = role)
            ?: tech.csalliance.unstuck.sync.CollectionShareClient.ShareResult(tech.csalliance.unstuck.sync.ShareOutcome.ERROR, null)
        // The owner's own client must learn it is shared NOW: the server returns the
        // membership rows, so set members[] immediately (isShared flips → item edits
        // switch to the atomic RPCs instead of whole-row upserts that clobber members'
        // edits), then refresh unconditionally — a pending INVITE changes the sheet too.
        result.memberIds?.let { ids -> setCollectionMembersLocally(collectionId, ids) }
        if (result.outcome != tech.csalliance.unstuck.sync.ShareOutcome.SELF) graph.coordinator?.refreshCollections()
        return result
    }

    /** A one-shot join link carrying this list (`share-collection link`). */
    suspend fun collectionShareLink(collectionId: String, role: String): tech.csalliance.unstuck.sync.ShareLinkOutcome =
        share?.link(collectionId, role) ?: tech.csalliance.unstuck.sync.ShareLinkOutcome.Failed("not_configured")

    // --- unified sharing v1: the `share-task` edge fn (email share / roster / link) ---
    private val taskShareClient get() = graph.coordinator?.taskShare

    /** Share a task I own with an EMAIL: an existing account is shared with at
     *  once (the server pushes them), anyone else gets an invite email that is
     *  claimed when they sign up. Answers honestly (shared / invited / a reason). */
    suspend fun shareTaskByEmail(taskId: String, email: String, level: ShareLevel): tech.csalliance.unstuck.sync.TaskShareOutcome {
        val r = taskShareClient?.add(taskId, email, level) ?: tech.csalliance.unstuck.sync.TaskShareOutcome.Failed("not_configured")
        if (r is tech.csalliance.unstuck.sync.TaskShareOutcome.Shared) refreshShares()
        return r
    }

    /** Pending email invites on a task I own (`share-task list`). Tolerant → []. */
    suspend fun taskPendingInvites(taskId: String): List<tech.csalliance.unstuck.sync.TaskSharePendingInvite> =
        taskShareClient?.list(taskId)?.pending ?: emptyList()

    /** Cancel a pending email invite on a task. TRUE only when the server confirmed. */
    suspend fun cancelTaskInvite(taskId: String, inviteId: String): Boolean =
        taskShareClient?.cancelInvite(taskId, inviteId) ?: false

    /** A one-shot join link carrying this task at [level] (`share-task link`). */
    suspend fun taskShareLink(taskId: String, level: ShareLevel): tech.csalliance.unstuck.sync.ShareLinkOutcome =
        taskShareClient?.link(taskId, level) ?: tech.csalliance.unstuck.sync.ShareLinkOutcome.Failed("not_configured")

    /** Every invite I sent that is still waiting (Settings → People "Waiting to
     *  join"): task / list / circle email invites from ONE RPC. Tolerant → []. */
    suspend fun myPendingInvites(): List<tech.csalliance.unstuck.core.model.PendingInvite> =
        circleClient?.myPendingInvites() ?: emptyList()

    /** Cancel one of them. TRUE only when a row was deleted; refetches the roster. */
    suspend fun cancelPendingInvite(kind: tech.csalliance.unstuck.core.model.PendingInviteKind, id: String): Boolean {
        val ok = circleClient?.cancelPendingInvite(kind, id) ?: false
        if (ok) refreshCircle()
        return ok
    }

    /** "Report…" on a person who holds a shared item (App Store / Play safety) —
     *  lands in the feedback table under `report` for triage. */
    suspend fun reportShareConcern(kind: String, itemId: String, about: String, reason: String): Boolean =
        sendFeedback(body = "⚠️ REPORT — shared $kind $itemId, recipient $about: $reason", category = "report", screen = "share-$kind")

    /** "Report…" on a task someone shared WITH me — a recipient could only report
     *  from a Share screen they owned (audit 2026-09-22 C10). Same channel; the body
     *  and screen match iOS so the dashboard triages both alike. False = not sent. */
    suspend fun reportSharedTask(taskId: String, shareId: String?, ownerName: String, reason: String): Boolean =
        sendFeedback(
            body = tech.csalliance.unstuck.core.logic.sharedTaskReportBody(taskId, shareId, ownerName, reason),
            category = "report", screen = "shared-with-me",
        )

    /** "Report…" by a MEMBER of a list shared with me (about its owner) — a member
     *  used to have only Leave (audit 2026-09-22 C10). Body and screen match iOS
     *  reportConcern. False = not sent. */
    suspend fun reportSharedList(collectionId: String, about: String, reason: String): Boolean =
        sendFeedback(body = "⚠️ REPORT — shared collection $collectionId, member $about: $reason", category = "report", screen = "shared-collection")
    private suspend fun setCollectionMembersLocally(collectionId: String, memberIds: List<String>) {
        collectionMutex.withLock {
            val cur = store.collections().first().firstOrNull { it.id == collectionId } ?: return@withLock
            val uid = currentUid()
            val next = cur.copy(
                members = memberIds.filter { it != cur.ownerId },
                myRole = cur.myRole ?: if (cur.ownerId == null || cur.ownerId == uid) "owner" else null,
            )
            if (next != cur) store.upsert(tech.csalliance.unstuck.data.db.Tables.COLLECTIONS, next, ItemCollection.serializer(), next.id)
        }
    }
    // Revoking access ANSWERS whether the server did it. A refusal (not the owner,
    // 5xx, offline) used to be swallowed and reported as a successful revocation —
    // the member kept full access while the sheet said they were removed. False =
    // nothing changed; the caller says so and leaves the row in place.
    suspend fun unshareCollection(collectionId: String, userId: String): Boolean {
        val ok = share?.unshare(collectionId, userId) ?: false
        if (ok) graph.coordinator?.refreshCollections()
        return ok
    }
    suspend fun cancelCollectionInvite(collectionId: String, email: String): Boolean {
        val ok = share?.cancelInvite(collectionId, email) ?: false
        if (ok) graph.coordinator?.refreshCollections()
        return ok
    }
    /**
     * Leave a list shared WITH me. TRUE only when the server confirmed it — the
     * local row is dropped (I lost access) ONLY then. Dropping it on a refused or
     * offline leave looked like it worked and the list came straight back on the
     * next hydrate, so the screen now stays put and says so instead.
     *
     * The work runs on viewModelScope, not the caller's: the screen pops itself the
     * moment this answers true, and a screen-scoped coroutine would be cancelled
     * mid-RPC (before the local drop committed) if the user backed out first.
     */
    suspend fun leaveCollection(collectionId: String): Boolean =
        viewModelScope.async {
            if (share?.leave(collectionId) != true) return@async false
            store.delete(tech.csalliance.unstuck.data.db.Tables.COLLECTIONS, collectionId)   // lost access → drop locally
            graph.coordinator?.refreshCollections()   // membership changed server-side — resync the rest
            true
        }.await()
    suspend fun listCollectionMembers(collectionId: String): List<tech.csalliance.unstuck.sync.CollectionMemberInfo> =
        share?.listMembers(collectionId) ?: emptyList()

    // --- connections / trusted circle (M1) ---
    // The unified "people you share with" roster. All reads/writes go through the
    // SECURITY DEFINER RPCs + circle-invite edge fn (CircleClient). The roster is a
    // reactive StateFlow that refetches on the CollabRealtime circle-changed signal
    // (another user accepts an invite / leaves — RLS-scoped postgres_changes) AND
    // after each of my own writes (the manual pulse). Both getters read the (nullable)
    // coordinator lazily so the flow is safe before it's wired / in tests.
    private val circleClient get() = graph.coordinator?.circle
    private val collab get() = graph.coordinator?.collab

    private val _circleRefresh = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** A failed roster read keeps the roster on screen instead of "No one yet"
     *  (parity with iOS build 79, audit 2026-09-22 C11). */
    private val circleHold = tech.csalliance.unstuck.ui.sharing.LastGoodRead<List<CircleMember>>(emptyList())

    /** My connections: active members (resolved names) + pending invites (with their
     *  code, so the link can be re-copied). Empty until first collected — the
     *  Connections screen drives it (WhileSubscribed, so it stops when off-screen). */
    val circle: StateFlow<List<CircleMember>> =
        merge(_circleRefresh, flow { collab?.circleChanged?.let { emitAll(it) } }, shareRereads)
            .onStart { emit(Unit) }
            .transform { circleHold.refreshInto(this, currentUid(), heldAccount.value) { circleClient?.circleList() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Force a roster refetch now (after a write). */
    fun refreshCircle() { _circleRefresh.tryEmit(Unit) }

    /** One-shot roster read (non-reactive callers / tests). */
    suspend fun listCircle(): List<CircleMember> = circleClient?.circleList() ?: emptyList()

    /** Invite to my circle. With an email the server reaches them (adds an existing
     *  Unstuck user directly, or emails a new person the join link); blank → a
     *  one-time link I share myself. Returns what happened; refetches the roster. */
    suspend fun inviteToCircle(email: String?): tech.csalliance.unstuck.sync.InviteResult {
        val r = circleClient?.circleInvite(email) ?: tech.csalliance.unstuck.sync.InviteResult(error = "not_configured")
        refreshCircle()
        return r
    }

    /** Redeem an invite code → join that owner's circle. Refetches on success. */
    suspend fun redeemCircle(code: String): tech.csalliance.unstuck.sync.RedeemResult {
        val r = circleClient?.circleRedeem(code) ?: tech.csalliance.unstuck.sync.RedeemResult(ok = false, error = "not_configured")
        if (r.ok) refreshCircle()
        return r
    }

    /** Remove a connection (or cancel a pending roster invite). Server-side this also
     *  drops the task shares AND the list memberships between the two of us, both
     *  ways (migration 075). TRUE only when the server accepted it. Only a confirmed
     *  removal of a real connection re-reads the shared state — a pending row changes
     *  no list, and an offline re-read must never run on a removal that didn't happen
     *  (parity with iOS build 79, audit 2026-09-22 C11). Refetches the roster. Runs
     *  on viewModelScope so dismissing the dialog can't cancel it mid-call. */
    suspend fun removeFromCircle(m: CircleMember): Boolean =
        viewModelScope.async {
            val wasConnection = m.memberUserId != null
            val ok = circleClient?.circleRemove(m.id) ?: false
            refreshCircle()   // first: the roster re-read needn't wait for the lists
            if (ok && wasConnection) refreshAfterSevering()
            ok
        }.await()

    // --- blocks + recipient-side removal (migration 075, audit 2026-09-22 C10) ---
    // Android had no Block at all: not on the Share screen, a shared task, a shared
    // list or People. Every write answers what the SERVER did; the shared state is
    // re-read only on a confirmed true (parity with iOS build 79). Each runs on
    // viewModelScope, like leaveCollection, so the screen popping itself on success
    // (or the user backing out) can't cancel the RPC or the re-read.

    /** Block someone by user id — a Share-screen row, a People row, the owner of a
     *  list shared with me. The server also cuts the connection, the task shares and
     *  the list memberships between us both ways, so all three are re-read. */
    suspend fun blockUser(userId: String): Boolean {
        if (userId.isBlank()) return false
        return viewModelScope.async {
            val ok = circleClient?.blockUser(userId) ?: false
            if (ok) { refreshCircle(); refreshAfterSevering() }
            ok
        }.await()
    }

    /** Block the owner of a task shared WITH me (a recipient knows only the share id). */
    suspend fun blockTaskSharer(shareId: String): Boolean {
        if (shareId.isBlank()) return false
        return viewModelScope.async {
            val ok = circleClient?.blockTaskSharer(shareId) ?: false
            if (ok) { refreshCircle(); refreshAfterSevering() }
            ok
        }.await()
    }

    /** Lift a block. Restores nothing the block removed. */
    suspend fun unblockUser(userId: String): Boolean =
        viewModelScope.async { circleClient?.unblockUser(userId) ?: false }.await()

    /** Remove a task shared WITH me from my list (the owner isn't told). */
    suspend fun leaveSharedTask(shareId: String): Boolean {
        if (shareId.isBlank()) return false
        return viewModelScope.async {
            val ok = circleClient?.leaveSharedTask(shareId) ?: false
            if (ok) refreshShares()
            ok
        }.await()
    }

    /** Everyone I blocked (Settings → People "Blocked"). Null when it couldn't be
     *  read — the screen keeps the list it has. Never mirrored: user_blocks is not in
     *  the realtime publication, so it is re-read when People refreshes. */
    suspend fun blockedUsers(): List<tech.csalliance.unstuck.core.model.BlockedUser>? = circleClient?.blockedUsers()

    /** A block or a removed connection took task shares and list memberships away
     *  server-side: re-read both. Realtime usually gets there first here (Android's
     *  collection_members channel is unfiltered), so this is the backstop for a
     *  missed event. */
    private suspend fun refreshAfterSevering() {
        refreshShares()
        graph.coordinator?.refreshCollections()
    }

    // --- per-task sharing (M2) + shared-with-you / delegated groups (M3) ---
    // The reactive projections (sharedWithMe / shareBadges / assignedOut) are declared
    // NEAR THE TOP (before the widget init that reads assignedOut) so they're already
    // initialized when that init's coroutine starts. The write methods live here.

    /** Force a shares refetch now (after a share/unshare/complete write). */
    fun refreshShares() { _sharesRefresh.tryEmit(Unit) }

    /** Who a task I own is shared with — drives the share sheet's current state. */
    suspend fun sharesForTask(taskId: String): List<ShareForTask> =
        circleClient?.taskSharesForTask(taskId) ?: emptyList()

    /** Share a task I own with a circle member at [level]. THROWS on error (the sheet
     *  needs to know), then pings the recipient (best-effort; [notify] false for a
     *  quiet level change — a grade change is not a new share) + refetches. */
    suspend fun shareTask(taskId: String, userId: String, level: ShareLevel, notify: Boolean = true) {
        circleClient?.taskShare(taskId, userId, level)
        if (notify) circleClient?.notifyTaskShare(taskId, userId)
        refreshShares()
    }

    /** Remove a share by its id (owner-only, RPC-enforced). TRUE only when the
     *  server confirmed; refetches either way. */
    suspend fun unshareTask(shareId: String): Boolean {
        val ok = circleClient?.taskUnshareConfirmed(shareId) ?: false
        refreshShares()
        return ok
    }

    /** Read-only detail for a task shared WITH me (any level) — drives the shared-task
     *  detail sheet (T1). RLS forbids the raw task row; this SECURITY DEFINER projection
     *  is the only window. Null on error / no such share. */
    suspend fun sharedTaskDetail(taskId: String): tech.csalliance.unstuck.core.model.SharedTaskDetail? =
        circleClient?.sharedTaskDetail(taskId)

    // --- assistant-staged shares (the ONE agent action that reaches another
    // person, so it never runs on the model's say-so) ---

    /** Share requests the agent PREPARED. The panel renders a confirm card for
     *  each; nothing leaves this device until the user taps "Share it". */
    private val _pendingShares = MutableStateFlow<List<PendingShare>>(emptyList())
    val pendingShares: StateFlow<List<PendingShare>> = _pendingShares.asStateFlow()

    /** Trusted-circle members who can actually receive a share — ACTIVE only
     *  (a pending invite has no user id to share to). */
    internal fun shareCandidates(): List<ShareCandidate> = circle.value
        .filter { it.status == CircleStatus.ACTIVE && !it.memberUserId.isNullOrBlank() }
        .map { ShareCandidate(it.memberUserId!!, it.memberName ?: it.relationshipLabel ?: "Someone") }

    /** Perform a staged share — ONLY ever called from the confirm card's tap.
     *  Runs the same task_share RPC + share-notify ping the share sheet uses. */
    fun confirmPendingShare(id: String) = launchWrite {
        val p = _pendingShares.value.firstOrNull { it.id == id && it.outcome == null } ?: return@launchWrite
        val ok = if (p.subject == tech.csalliance.unstuck.core.logic.ShareSubject.LIST) {
            // share_list (2026-09-20): the SAME edge-fn path the list share sheet
            // takes — by member user id, or by the email the user named.
            val r = shareCollectionDetailed(p.taskId, email = p.recipientEmail, userId = p.recipientUserId.ifBlank { null }, role = p.role ?: "viewer")
            r.outcome.failureReason == null
        } else {
            val shared = runCatching { circleClient?.taskShare(p.taskId, p.recipientUserId, p.level) }.isSuccess
            if (shared) {
                runCatching { circleClient?.notifyTaskShare(p.taskId, p.recipientUserId) }
                refreshShares()
            }
            shared
        }
        _pendingShares.value = _pendingShares.value.map {
            if (it.id == id) it.copy(outcome = if (ok) ShareOutcome.SHARED else ShareOutcome.FAILED) else it
        }
    }

    /** "Not now" — resolves the card WITHOUT calling the RPC. */
    fun dismissPendingShare(id: String) {
        _pendingShares.value = _pendingShares.value.map {
            if (it.id == id) it.copy(outcome = ShareOutcome.DISMISSED) else it
        }
    }

    /** Complete/uncomplete a task shared WITH me — partner OR assign only (the RPC
     *  rejects view). Pings the owner on completion (best-effort), then refetches.
     *  Stamps [_sharedCompletedAt] so the row moves like any other completed task the
     *  instant it's ticked — gone from Today, still today's win in All, permanently in
     *  Completed — even against a server whose projection has no completed_at yet. */
    fun completeSharedTask(taskId: String, done: Boolean, onRefused: ((String) -> Unit)? = null) = launchWrite {
        val refused = setSharedTaskDone(taskId, done)
        refreshShares()
        // The detail sheet rolls its optimistic tick back and says why — it used
        // to keep a "✓ Completed" that never landed (audit 2026-09-22 SC-12).
        if (refused != null) onRefused?.invoke(shareTickErrorText(refused.message))
    }

    /** shared_task_set_done with the optimistic stamp, the owner's "Done" ping only
     *  once the server took it, and the stamp dropped again on a refusal — which
     *  used to be swallowed while the ping still went out: shared_task_set_done now
     *  refuses a tick on a repeating task ('recurring_series', 075 §1). Null = saved,
     *  else what refused it (parity with iOS build 81, audit 2026-09-22 SC-12). */
    private suspend fun setSharedTaskDone(taskId: String, done: Boolean): Throwable? {
        val client = circleClient ?: return IllegalStateException("not_configured")
        _sharedCompletedAt.value =
            if (done) _sharedCompletedAt.value + (taskId to isoNow()) else _sharedCompletedAt.value - taskId
        val err = runCatching { client.sharedTaskSetDone(taskId, done) }.exceptionOrNull()
        if (err != null) {
            if (done) _sharedCompletedAt.value = _sharedCompletedAt.value - taskId
            println("[share] shared_task_set_done($taskId, $done) refused: ${err.message}")
            return err
        }
        if (done) client.notifyTaskDone(taskId)
        return null
    }

    /** May a finished shared session tick the owner's task done? Not a repeating
     *  share: its row is the owner's series and the server refuses the tick, so the
     *  Focus finish and the assistant must not claim a completion. A share the list
     *  hasn't loaded falls back to true — every caller has already checked the
     *  level, and the server still refuses a series (parity with iOS build 81,
     *  audit 2026-09-22 C3). */
    fun sharedTaskAllowsTick(taskId: String): Boolean =
        sharedWithMe.value.firstOrNull { it.taskId == taskId }?.let(::shareCanTickDone) ?: true

    // --- tags & areas ---

    fun upsertTag(t: TagRow) = launchWrite { write?.upsertTag(t) }

    /** Delete a tag and strip its name from every task (case-insensitive cascade). */
    fun deleteTag(id: String) = launchWrite { deleteTagNow(id) }

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
    fun renameTag(tag: TagRow, newName: String) = launchWrite { renameTagNow(tag.id, newName) }

    fun recolorTag(tag: TagRow, color: String?) = launchWrite { write?.upsertTag(tag.copy(color = color)) }
    fun upsertLifeArea(a: LifeArea) = launchWrite { write?.upsertLifeArea(a) }

    /** Delete an area and clear its label off every task (no dangling lifeArea). */
    fun deleteLifeArea(id: String) = launchWrite { deleteLifeAreaNow(id) }

    /** Rename an area + cascade the new name onto its tasks (web parity). */
    fun renameLifeArea(area: LifeArea, newName: String) = launchWrite { renameLifeAreaNow(area.id, newName) }

    fun recolorLifeArea(area: LifeArea, color: String) = launchWrite { write?.upsertLifeArea(area.copy(color = color)) }

    // THE label cascade. Tasks key areas and tags by NAME, so a rename or delete of the
    // row rewrites every task that carries it. Settings (above) and the assistant
    // (AppViewModelAssistantApi) both come through here: one path, where there were two
    // copies that had drifted (the Settings tag rename de-duped case-sensitively). Rows
    // are read from the store, never the WhileSubscribed flows, which are empty while
    // nothing on screen collects them (parity with iOS build 81, audit 2026-09-22 C19).
    //
    // One cascade at a time: each yields at every task write, so a rename A→B still
    // relabelling while a delete of B (or a rename B→C) reads the task list would skip
    // the tasks not yet moved, and the first cascade would then park them on a name no
    // area has.
    private val labelCascadeMutex = Mutex()

    /** Rename an area and move its tasks. A blank or unchanged name, or one another area
     *  already has (ignoring case — the server's unique(user_id, name) would quarantine
     *  the row while the task relabels still synced), is refused: FALSE, nothing written.
     *  TRUE once the row and its tasks are committed. */
    suspend fun renameLifeAreaNow(id: String, newName: String): Boolean = labelCascadeMutex.withLock {
        val w = write ?: return@withLock false
        val name = newName.trim()
        val rows = store.snapshot(Tables.LIFE_AREAS, LifeArea.serializer())
        val row = rows.firstOrNull { it.id == id }
        val others = rows.filter { it.id != id }
        if (name.isEmpty() || row == null || name == row.name || labelNameTaken(name, others.map { it.name })) return@withLock false
        w.upsertLifeArea(row.copy(name = name))
        // A same-named twin (left by the assistant's old unchecked rename) still owns
        // the old name: its tasks stay where they are.
        if (others.none { it.name == row.name }) relabelTasks(w) { relabelingArea(it, row.name, name, isoNow()) }
        true
    }

    /** Delete an area and clear its label off its tasks — what the Settings alert
     *  promises ("they just lose this area label"). FALSE when there is no such row. */
    suspend fun deleteLifeAreaNow(id: String): Boolean = labelCascadeMutex.withLock {
        val w = write ?: return@withLock false
        val rows = store.snapshot(Tables.LIFE_AREAS, LifeArea.serializer())
        val row = rows.firstOrNull { it.id == id } ?: return@withLock false
        w.deleteLifeArea(id)
        if (rows.none { it.id != id && it.name == row.name }) relabelTasks(w) { relabelingArea(it, row.name, null, isoNow()) }
        true
    }

    /** Rename a tag and carry the new name onto its tasks (case-insensitive, one copy
     *  per task). Refused like [renameLifeAreaNow]; TRUE once committed. */
    suspend fun renameTagNow(id: String, newName: String): Boolean = labelCascadeMutex.withLock {
        val w = write ?: return@withLock false
        val name = newName.trim()
        val rows = store.snapshot(Tables.TAGS, TagRow.serializer())
        val row = rows.firstOrNull { it.id == id }
        val others = rows.filter { it.id != id }.map { it.name }
        if (name.isEmpty() || row == null || name == row.name || labelNameTaken(name, others)) return@withLock false
        w.upsertTag(row.copy(name = name))
        // Tags match ignoring case, so any remaining "Quick"/"QUICK" twin keeps the tasks.
        if (!labelNameTaken(row.name, others)) relabelTasks(w) { renamingTag(it, row.name, name, isoNow()) }
        true
    }

    /** Delete a tag and strip it from its tasks. FALSE when there is no such row. */
    suspend fun deleteTagNow(id: String): Boolean = labelCascadeMutex.withLock {
        val w = write ?: return@withLock false
        val rows = store.snapshot(Tables.TAGS, TagRow.serializer())
        val row = rows.firstOrNull { it.id == id } ?: return@withLock false
        w.deleteTag(id)
        if (!labelNameTaken(row.name, rows.filter { it.id != id }.map { it.name })) relabelTasks(w) { strippingTag(it, row.name, isoNow()) }
        true
    }

    /** Rewrite every task [transform] touches. Each match is RE-READ right before its
     *  write: the upsert sends the whole row, so a snapshot taken before an earlier
     *  write would revert a realtime / catch-up edit that landed in between and push it
     *  as a fresh local change. */
    private suspend fun relabelTasks(w: tech.csalliance.unstuck.sync.WriteThrough, transform: (TaskItem) -> TaskItem?) {
        for (t in store.snapshot(Tables.TASKS, TaskItem.serializer())) {
            if (transform(t) == null) continue
            val fresh = store.getOne(Tables.TASKS, t.id, TaskItem.serializer()) ?: continue
            w.upsertTask(transform(fresh) ?: continue)
        }
    }

    // --- onboarding ---

    // The gate MainScaffold follows. Per account (see AppGraph.onboarded), keyed on the
    // session's RESOLVED account, and reactive: it used to be read once, as soon as the
    // session was authed — before the first pull on a new phone (a web / iOS account was
    // walked through setup again) and with no uid during an offline RefreshFailure (a
    // long-time user got the steps). An account not onboarded here now waits on a splash
    // until the server has answered (the early read, or the reconcile after the first
    // pull), or the deadline passed — then the local flag decides, like iOS's
    // onboardingResolved (Android audit 2026-09-23, A9).
    private val onboardingUid = MutableStateFlow(graph.onboardedUid)
    private val onboardingResolvedFor = MutableStateFlow<String?>(null)
    private val onboardedChanged = MutableStateFlow(0)
    private var onboardingArmedFor: String? = null

    /** null = not known yet (splash) · true = the onboarding steps · false = the app. */
    val showOnboarding: StateFlow<Boolean?> =
        combine(onboardingUid, onboardingResolvedFor, onboardedChanged) { uid, resolvedFor, _ ->
            OnboardingGate.show(uid, graph.isOnboarded(uid), resolvedFor)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingGate.show(graph.onboardedUid, graph.onboarded, null))

    init {
        followOnboardingAccount()
        graph.provider?.client?.let { client ->
            viewModelScope.launch { client.auth.sessionStatus.collect { followOnboardingAccount() } }
        }
    }

    /** The session changed: key the gate on its account, and start resolving one this
     *  device doesn't know as onboarded — an early read of its prefs row and task count
     *  (the gate resolves before the full first pull lands) plus the deadline. */
    private fun followOnboardingAccount() {
        val uid = graph.onboardedUid
        onboardingUid.value = uid
        if (uid == null) { onboardingArmedFor = null; return }
        if (uid == onboardingArmedFor || graph.isOnboarded(uid)) return
        onboardingArmedFor = uid
        viewModelScope.launch { runCatching { reconcileOnboarded(uid, afterPull = false) } }
        viewModelScope.launch {
            kotlinx.coroutines.delay(ONBOARDING_RESOLVE_DEADLINE_MS)
            if (onboardingUid.value == uid) onboardingResolvedFor.value = uid
        }
    }

    private fun markOnboarded() {
        graph.onboarded = true
        onboardedChanged.value++
    }

    /** Ask the SERVER whether this account onboarded on another platform, and pin the
     *  local flag if it did — without re-arming the tour or touching its struggles.
     *  [afterPull]: the store holds the server's rows. Before the pull, a head count of
     *  the account's tasks goes out beside the prefs read (web's FirstRunGate), so that
     *  early read is a whole answer too: it used to be able only to pin, and every new
     *  sign-up — or, on a slow link, a web account with tasks but no struggles — waited
     *  on the splash for the full first pull and the reconciles queued ahead of this one
     *  (Android audit 2026-09-23, A9). Either way a "no" is an answer and the gate
     *  resolves to the steps. A read that fails changes nothing (the deadline lets the
     *  local flag decide). */
    private suspend fun reconcileOnboarded(uid: String, afterPull: Boolean) {
        if (graph.isOnboarded(uid)) return
        val prefs = graph.coordinator?.preferences ?: return
        val (server, serverTasks) = try {
            kotlinx.coroutines.coroutineScope {
                val tasks = if (afterPull) null else async { prefs.countOwnTasks(uid) }
                prefs.fetchUserPrefs(uid) to tasks?.await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        applyOnboardingAnswer(uid, server?.adhd_struggles, server?.assistant_interview_done_at, afterPull, serverTasks?.let { it > 0 })
    }

    /** The server's answer for [uid] (null fields: no row, or nothing saved).
     *  [serverHasTasks]: the early read's head count (null = not asked, or no count came
     *  back — then the early read can only pin). Life areas are no signal — the server
     *  seeds them for every new account, so they marked a brand-new user onboarded after
     *  one pull and a rotation mid-setup skipped it. */
    internal suspend fun applyOnboardingAnswer(
        uid: String, serverStruggles: List<String>?, interviewDoneAt: String?, afterPull: Boolean, serverHasTasks: Boolean? = null,
    ) {
        // A raw row count, not a decode: this only asks "any task?", and decoding every
        // task on the main thread stalled the splash for a large account (A9).
        val hasTasks = serverHasTasks == true || store.countRows(Tables.TASKS) > 0
        if (graph.onboardedUid != uid) return   // the account changed while we asked
        if (!graph.isOnboarded(uid) && OnboardingGate.onboardedElsewhere(serverStruggles, interviewDoneAt, hasTasks)) markOnboarded()
        if (afterPull || serverHasTasks != null) onboardingResolvedFor.value = uid
    }

    fun completeOnboarding(struggles: List<String>, areas: List<String> = emptyList()) {
        // Arm the ONE-TIME guided-tour auto-offer (next Today arrival) FIRST —
        // nothing after it depends on the server, and arming after the network
        // write below could delay it past TourHost's mount (a lost/late offer).
        // Only accounts that complete onboarding AFTER this ships get it —
        // existing accounts reach the tour via Settings → Account → Product tour.
        runCatching {
            tech.csalliance.unstuck.ui.tour.TourStateStore(graph.appContext).patch { it.copy(eligible = true) }
        }
        // The gate follows the flag, so set it before anything below suspends.
        markOnboarded()
        launchWrite {
            // Seed only the PICKED areas this account doesn't have. It used to check
            // lifeAreas.value, a WhileSubscribed flow nothing collects during onboarding
            // (always empty), and re-create the server-seeded Work / Personal / Home
            // with fresh ids: shown twice in every picker, and refused by the server's
            // unique(user_id, name) on every flush (Android audit 2026-09-23, A8).
            val existing = store.snapshot(Tables.LIFE_AREAS, LifeArea.serializer())
            OnboardingGate.areasToSeed(areas, existing, ::newUuid).forEach { write?.upsertLifeArea(it) }
            val uid = auth?.currentUserId
            if (uid != null && struggles.isNotEmpty()) {
                // Cached locally first (the gateway + assistant context read them at
                // once); the server row is the account's copy for every other device.
                applyStruggles(uid, struggles)
                runCatching { graph.coordinator?.preferences?.setAdhdStruggles(uid, struggles) }
            }
        }
    }

    // --- settings (device-local prefs: theme / focus / sound / a11y) ---

    private val settingsStore = graph.settings
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<tech.csalliance.unstuck.SettingsState> = _settings.asStateFlow()

    /** Mutate + persist settings in one call: `updateSettings { it.copy(theme = …) }`. */
    fun updateSettings(transform: (tech.csalliance.unstuck.SettingsState) -> tech.csalliance.unstuck.SettingsState) {
        val prev = _settings.value
        val next = transform(prev)
        _settings.value = next
        settingsStore.save(next)
        // Mirror the notification level + reminder lead to notification_preferences
        // (the server source of truth: web Settings + the server crons read them).
        // Queued durably so an offline change lands on the next pull, and the
        // server-wins read-back skips a field while its write is pending.
        val changed = buildSet {
            if (next.notificationLevel != prev.notificationLevel) add(NOTIF_PREF_LEVEL)
            if (next.reminderLeadMin != prev.reminderLeadMin) add(NOTIF_PREF_LEAD)
        }
        if (changed.isNotEmpty()) {
            settingsStore.savePendingNotifPrefWrites(settingsStore.loadPendingNotifPrefWrites() + changed)
            viewModelScope.launch { auth?.currentUserId?.let { uid -> runCatching { drainNotifPrefWrites(uid) } } }
        }
    }

    private val notifPrefsMutex = Mutex()

    /** Push the queued level / lead writes; a failure leaves the field queued. */
    private suspend fun drainNotifPrefWrites(uid: String) = notifPrefsMutex.withLock {
        val prefsClient = graph.coordinator?.preferences ?: return@withLock
        val pending = settingsStore.loadPendingNotifPrefWrites()
        if (pending.isEmpty()) return@withLock
        val s = _settings.value
        val remaining = pending.toMutableSet()
        if (NOTIF_PREF_LEVEL in pending) {
            val level = s.notificationLevel
            runCatching { prefsClient.setNotificationLevel(uid, level.wire, morningBrief = level.morningBrief, pausedCheckin = level.pausedCheckin) }
                .onSuccess { remaining.remove(NOTIF_PREF_LEVEL) }
        }
        if (NOTIF_PREF_LEAD in pending) {
            runCatching { prefsClient.setReminderLead(uid, s.reminderLeadMin) }
                .onSuccess { remaining.remove(NOTIF_PREF_LEAD) }
        }
        settingsStore.savePendingNotifPrefWrites(remaining)
    }

    /** After every pull: land queued writes, then READ BACK notification_level +
     *  reminder_lead_min — the server wins (a level picked on the web reaches this
     *  phone's alarms), except for a field whose own write hasn't landed yet. The
     *  FIRST reconcile per account pushes this device's local values UP instead
     *  (see SettingsStore.notifPrefsMigrated for why). Re-arms the local alarms
     *  when anything changed. */
    private suspend fun reconcileNotificationPrefs(uid: String) {
        val prefsClient = graph.coordinator?.preferences ?: return
        if (!settingsStore.notifPrefsMigrated(uid)) {
            settingsStore.savePendingNotifPrefWrites(settingsStore.loadPendingNotifPrefWrites() + setOf(NOTIF_PREF_LEVEL, NOTIF_PREF_LEAD))
            drainNotifPrefWrites(uid)
            if (settingsStore.loadPendingNotifPrefWrites().isEmpty()) settingsStore.setNotifPrefsMigrated(uid)
            return
        }
        drainNotifPrefWrites(uid)
        val pending = settingsStore.loadPendingNotifPrefWrites()
        val server = runCatching { prefsClient.fetchNotificationPrefs(uid) }.getOrNull() ?: return
        val cur = _settings.value
        val level = if (NOTIF_PREF_LEVEL in pending) cur.notificationLevel
            else tech.csalliance.unstuck.NotificationLevel.fromWire(server.notification_level) ?: cur.notificationLevel
        val lead = if (NOTIF_PREF_LEAD in pending) cur.reminderLeadMin else (server.reminder_lead_min ?: cur.reminderLeadMin)
        if (level == cur.notificationLevel && lead == cur.reminderLeadMin) return
        // Apply WITHOUT re-mirroring (it came from the server) and re-arm the alarms.
        val next = cur.copy(notificationLevel = level, reminderLeadMin = lead)
        _settings.value = next
        settingsStore.save(next)
        (graph.appContext as? tech.csalliance.unstuck.UnstuckApp)?.let { app ->
            runCatching { tech.csalliance.unstuck.surface.ReminderScheduler.reschedule(app) }
        }
    }

    // --- auth ---

    /** Signed-in identity for the avatar / account UI (null when signed out). */
    val currentEmail: String? get() = auth?.currentEmail
    val currentName: String? get() = currentNameProvider?.invoke() ?: auth?.currentName

    /** Send one-way beta feedback with auto-attached context. Returns false on
     *  failure (offline / not configured) so the sheet can offer a retry. */
    suspend fun sendFeedback(body: String, category: String?, screen: String?): Boolean {
        val fb = feedback ?: return false
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        return fb.submit(
            id = newUuid(), body = body.trim(), category = category, email = auth?.currentEmail,
            appVersion = tech.csalliance.unstuck.BuildConfig.VERSION_NAME, platform = "android",
            device = device, screen = screen,
        )
    }

    // --- assistant (agentic chat) ---
    // The edge fn reasons (system prompt + tool schemas + qwen); WE execute the
    // tool calls through the same write methods the UI uses. One turn: ask →
    // run tools locally → append results → re-ask, until a plain-text reply
    // (capped). `history` is mutated so the caller keeps full context.

    sealed interface AssistantTurn {
        data class Reply(val text: String) : AssistantTurn
        /** "not_configured" | "network" | "timeout" | "upstream" | … — UI shows a note. */
        data class Error(val code: String) : AssistantTurn
    }

    // Conversation lives on the ViewModel (survives closing/reopening the bubble)
    // and is persisted to disk (survives an accidental app close). Capped so it
    // can't grow unbounded; the window starts at a user turn so we never re-send
    // an orphaned tool_call.
    val assistantHistory = mutableStateListOf<ChatMessage>()
    private val assistantPrefs by lazy {
        graph.appContext.getSharedPreferences("unstuck.assistant", android.content.Context.MODE_PRIVATE)
    }
    // Bumped by clearAssistant() so an in-flight async history load can't
    // resurrect a conversation cleared (e.g. by a sign-out) while it was reading.
    private var assistantEpoch = 0

    // The in-flight turn runs on viewModelScope — NOT the sheet's composition
    // scope — so dismissing the sheet (or MainScaffold's ON_STOP sheet reset)
    // can't cancel a multi-step agentic turn mid-flight and leave tool actions
    // half-applied with no reply. State lives here so reopening the sheet shows it.
    private var assistantJob: Job? = null
    private val _assistantSending = MutableStateFlow(false)
    val assistantSending: StateFlow<Boolean> = _assistantSending.asStateFlow()
    /** Error code of the last failed turn (null = none); survives sheet reopen. */
    private val _assistantError = MutableStateFlow<String?>(null)
    val assistantError: StateFlow<String?> = _assistantError.asStateFlow()
    /** Consecutive turns the upstream rejected (`upstream`). Two in a row on one
     *  thread is the poisoned-history signature (a persisted tool_call with
     *  non-object arguments made DashScope 400 every later turn, 2026-09-06):
     *  the sheet then offers "Start a fresh thread" next to the error. Reset by
     *  a good turn or [clearAssistant]. */
    private var assistantUpstreamStreak = 0
    private val _assistantOffersFreshThread = MutableStateFlow(false)
    val assistantOffersFreshThread: StateFlow<Boolean> = _assistantOffersFreshThread.asStateFlow()
    private fun noteAssistantOutcome(errorCode: String?) {
        assistantUpstreamStreak = if (errorCode == "upstream") assistantUpstreamStreak + 1 else 0
        _assistantOffersFreshThread.value = assistantUpstreamStreak >= 2
    }
    /** Reply texts as turns complete — an OPEN sheet collects to speak them. */
    private val _assistantReplies = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val assistantReplies: SharedFlow<String> = _assistantReplies.asSharedFlow()
    /** Messages sent while a turn was in flight — shown as faded pending bubbles,
     *  sent one per idle moment, never dropped (contract §8). */
    private val _assistantQueued = MutableStateFlow<List<QueuedSend>>(emptyList())
    val assistantQueued: StateFlow<List<QueuedSend>> = _assistantQueued.asStateFlow()

    init {
        // Load the persisted history OFF the main thread — the synchronous prefs
        // read + JSON decode of up to 40 (possibly long) messages was cold-start
        // main-thread disk IO inside the first composition. The sheet is never
        // visible at t=0, so the deferred load is invisible to the user.
        viewModelScope.launch {
            val epoch = assistantEpoch
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    assistantPrefs.getString("history", null)
                        ?.let { Json.decodeFromString<List<ChatMessage>>(it) }
                }.getOrNull()
            }
            // assistantHistory is a Compose SnapshotStateList — mutate it ONLY on the
            // main thread (it's read during recomposition; cross-thread mutation risks a
            // ConcurrentModificationException / dropped updates). Main.immediate is a
            // no-op hop when we're already on Main.
            if (!loaded.isNullOrEmpty() && epoch == assistantEpoch) {
                withContext(Dispatchers.Main.immediate) { assistantHistory.addAll(0, loaded) }
            }
        }
        // Scrub the conversation on sign-out — same cross-account leak class as
        // the notification log: the next account on a shared device must not see
        // the previous user's brain-dump / created tasks. (Account deletion ends
        // in the same auth signOut, so it's covered too.)
        graph.provider?.client?.let { client ->
            viewModelScope.launch {
                client.auth.sessionStatus.collect { status ->
                    // collect resumes on the SDK's emit dispatcher (not guaranteed Main);
                    // clearAssistant() mutates assistantHistory (a SnapshotStateList) which
                    // must only be touched on the main thread. Hop explicitly.
                    if (status is SessionStatus.NotAuthenticated && status.isSignOut) {
                        withContext(Dispatchers.Main.immediate) { clearAssistant() }
                        scrubAssistantUserState()
                    }
                    // A (re)sign-in: this account's cached rituals / dismissals /
                    // interview flag replace whatever the previous one left in memory.
                    if (status is SessionStatus.Authenticated) reloadAssistantUserState()
                    // A just-exchanged auth-callback session: classify it. A "recovery"
                    // session (forgot-password link) routes to set-new-password; magic-
                    // link / OAuth fall through to the normal app. One-shot probe so a
                    // later relaunch (Storage source) never re-triggers the screen.
                    // ONLY the code exchange itself (SessionSource.External) may consume
                    // the probe: a link tapped while the app was killed with a session
                    // stored arms the probe BEFORE the storage-restored session emits,
                    // and that emission used to spend the probe against the OLD token —
                    // the recovery session then landed on Today and the single-use link
                    // was burned (RecoveryProbe.consumes).
                    if (status is SessionStatus.Authenticated && graph.pendingRecoveryProbe.value &&
                        RecoveryProbe.consumes(status.source)
                    ) {
                        graph.pendingRecoveryProbe.value = false
                        if (isRecoverySession(status.session.accessToken)) {
                            graph.pendingPasswordRecovery.value = true
                        }
                    }
                }
            }
        }
    }

    // ── gateway per-account state: rituals, moment dismissals, interview flag ──
    // Same vocabulary as the web's localStorage / iOS UserDefaults keys
    // (PAPrefsLogic.*_KEY), suffixed PER ACCOUNT (`<key>.<uid>`, the
    // captureArchiveMigrated(uid) pattern) so a second account on this phone never
    // inherits the first one's setup; the whole file is scrubbed at sign-out on top.
    // Rituals are account-wide (`user_preferences.pa_rituals`, migration 053): the
    // local copy is a cache that wins until the first hydrate, after which the
    // server wins — unless a toggle made here hasn't been pushed yet (pending), in
    // which case the hydrate re-pushes it instead of pulling the older value over
    // it. Moment DISMISSALS stay device-local by design. The interview flag
    // (`assistant_interview_done_at`, migration 052) is pinned from the server on
    // every pull and pushed up when finished here.

    /** Re-read this account's cached gateway state (construction, a (re)sign-in,
     *  after a scrub). No account → defaults. */
    internal fun reloadAssistantUserState() {
        val uid = currentUid()
        if (uid == null) {
            _rituals.value = RitualPrefs.DEFAULTS
            _dismissedMoments.value = emptyList()
            _interviewDone.value = false
            _struggles.value = emptyList()
            _callSettings.value = tech.csalliance.unstuck.core.logic.CallSettings()
            _callProactive.value = CallProactivePrefs.DEFAULTS
            _ringNudgeDismissed.value = false
            return
        }
        _callSettings.value = runCatching { tech.csalliance.unstuck.calls.CallSettingsStore.load(graph.appContext, uid) }
            .getOrDefault(tech.csalliance.unstuck.core.logic.CallSettings())
        _callProactive.value = runCatching { CallSettingsStore.loadProactive(graph.appContext, uid) }.getOrDefault(CallProactivePrefs.DEFAULTS)
        _ringNudgeDismissed.value = runCatching { CallSettingsStore.ringNudgeDismissed(graph.appContext, uid) }.getOrDefault(false)
        _rituals.value = PAPrefsLogic.decodeRituals(paPrefs.getString(paKey(PAPrefsLogic.RITUALS_KEY, uid), null))
        _dismissedMoments.value = PAPrefsLogic.parseDismissed(paPrefs.getString(paKey(PAPrefsLogic.DISMISSED_KEY, uid), null))
        _interviewDone.value = paPrefs.getBoolean(paKey(INTERVIEW_DONE_KEY, uid), false)
        _struggles.value = decodeStruggles(paPrefs.getString(paKey(STRUGGLES_KEY, uid), null))
    }

    // rituals

    override fun setRitual(key: RitualKey, on: Boolean) = setRituals(_rituals.value.with(key, on))

    /** A USER change (Settings toggle / the interview picker / the set_ritual tool):
     *  cache locally, flag pending, push to the account. */
    override fun setRituals(prefs: RitualPrefs) {
        val uid = currentUid()
        _rituals.value = prefs
        if (uid == null) return
        paPrefs.edit()
            .putString(paKey(PAPrefsLogic.RITUALS_KEY, uid), PAPrefsLogic.encodeRituals(prefs))
            .putBoolean(paKey(PAPrefsLogic.PENDING_PUSH_KEY, uid), true)
            .apply()
        pushRituals(uid, prefs)
    }

    /** True while a ritual toggle made here hasn't reached `pa_rituals` yet. */
    internal fun ritualsPendingPush(uid: String): Boolean = paPrefs.getBoolean(paKey(PAPrefsLogic.PENDING_PUSH_KEY, uid), false)

    private fun pushRituals(uid: String, prefs: RitualPrefs) {
        val prefsClient = graph.coordinator?.preferences ?: return
        ritualsPushGen += 1
        val gen = ritualsPushGen
        viewModelScope.launch {
            val ok = runCatching { prefsClient.setRituals(uid, prefs) }.isSuccess
            if (ok && currentUid() == uid && ritualsPushGen == gen) {
                paPrefs.edit().remove(paKey(PAPrefsLogic.PENDING_PUSH_KEY, uid)).apply()
            }
        }
    }

    /** The account's rituals as the server has them (hydrate): replace the cache
     *  without firing the push, and clear any pending-push flag — the server is the
     *  truth from here on. */
    internal fun applyServerRituals(uid: String, prefs: RitualPrefs) {
        _rituals.value = prefs
        paPrefs.edit()
            .putString(paKey(PAPrefsLogic.RITUALS_KEY, uid), PAPrefsLogic.encodeRituals(prefs))
            .remove(paKey(PAPrefsLogic.PENDING_PUSH_KEY, uid))
            .apply()
    }

    // moment dismissals (device-local)

    fun dismissMoment(momentId: String) {
        val next = PAPrefsLogic.appendDismissed(_dismissedMoments.value, momentId)
        if (next === _dismissedMoments.value) return
        _dismissedMoments.value = next
        val uid = currentUid() ?: return
        paPrefs.edit().putString(paKey(PAPrefsLogic.DISMISSED_KEY, uid), PAPrefsLogic.encodeDismissed(next)).apply()
    }

    fun isMomentDismissed(momentId: String): Boolean = momentId in _dismissedMoments.value

    // interview flag + resume step

    /** The parked resume step, or null when nothing is persisted (iOS
     *  `InterviewMachine.parkedStep`). */
    override fun interviewParkedStep(maxStep: Int): Int? {
        val uid = currentUid() ?: return null
        val raw = paPrefs.getString(paKey(INTERVIEW_STEP_KEY, uid), null) ?: return null
        return InterviewFlag.parseInterviewStep(raw, maxStep)
    }

    fun hasInterviewResumeStep(): Boolean {
        val uid = currentUid() ?: return false
        return paPrefs.contains(paKey(INTERVIEW_STEP_KEY, uid))
    }

    override fun setInterviewStep(step: Int) {
        val uid = currentUid() ?: return
        paPrefs.edit().putString(paKey(INTERVIEW_STEP_KEY, uid), step.toString()).apply()
    }

    override fun clearInterviewStep() {
        val uid = currentUid() ?: return
        paPrefs.edit().remove(paKey(INTERVIEW_STEP_KEY, uid)).apply()
    }

    /** Every "the interview is done" path (I'm done, the rituals picker, reaching
     *  the end, the ≥1-fact auto-done): local flag + resume step dropped + the
     *  account (best-effort; a failed push is re-pushed by the next pull). */
    override fun markInterviewDone() { markInterviewDoneNow() }

    /** [markInterviewDone] answering whether there was an account to mark —
     *  the assistant's finish_interview reports the real outcome (2026-09-20). */
    internal fun markInterviewDoneNow(): Boolean {
        val uid = currentUid() ?: return false
        markInterviewDoneLocal(uid)
        pushInterviewDone(uid)
        return true
    }

    private fun markInterviewDoneLocal(uid: String) {
        _interviewDone.value = true
        paPrefs.edit()
            .putBoolean(paKey(INTERVIEW_DONE_KEY, uid), true)
            .remove(paKey(INTERVIEW_STEP_KEY, uid))
            .apply()
    }

    private fun pushInterviewDone(uid: String) {
        val prefsClient = graph.coordinator?.preferences ?: return
        val at = isoNow()
        viewModelScope.launch { runCatching { prefsClient.markInterviewDone(uid, at) } }
    }

    /** After every completed pull: read the account's interview flag + rituals and
     *  pin the local caches. Best-effort — offline / the column not deployed yet
     *  leave everything untouched (unknown ≠ "not done"), retried on the next pull. */
    private suspend fun reconcileAssistantPrefs(uid: String) {
        val prefsClient = graph.coordinator?.preferences ?: return
        val server = runCatching { prefsClient.fetchUserPrefs(uid) }.getOrElse { return }
        if (currentUid() != uid) return   // account changed mid-flight
        applyServerAssistantPrefs(uid, server)
    }

    // ── gateway (the AI card on Today): brief + one moment + composer hand-off ──
    // Port of iOS GatewayCard's engine wiring: the derived brief/moment is
    // memoised on a MINUTE-keyed input (plan risk 11 — pickMoment runs
    // derivePatterns over every block), moment actions write through the
    // assistant's AssistantApi seam (the SAME path the tools take), and the
    // composer hands its text to the Assistant sheet, which owns the thread.

    /** True once the local profile-facts read has emitted at least once —
     *  "no facts yet" and "nothing loaded yet" must not look the same to the
     *  interview's auto-open gate. WhileSubscribed, like [profileFacts]. */
    val profileFactsLoaded: StateFlow<Boolean> by lazy {
        profileFactsService.observeAll().map { true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

    private val gatewayMemo = GatewayMemo<GatewayInputs, GatewayDerived>()
    /** Re-ticks each minute while collected: the brief ("…is the anchor") and
     *  the time-gated moments (evening sweep at 17:30) must appear/refresh while
     *  Today just sits open. */
    private val gatewayMinute = flow { while (true) { emit(nowMs()); kotlinx.coroutines.delay(60_000) } }

    private data class GatewayRows(
        val tasks: List<TaskItem>, val blocks: List<CalBlock>, val sessions: List<Session>,
        val reasons: List<ReasonLog>, val facts: List<ProfileFact>,
    )
    private data class GatewayPrefs(val struggles: List<String>, val rituals: RitualPrefs, val dismissed: List<String>)

    /** The brief + the one moment for right now — memoised, recomputed only when
     *  an input the engines read (or the minute) changes. */
    val gateway: StateFlow<GatewayDerived> by lazy {
        val rows = combine(tasks, blocks, sessions, reasonLogs, profileFacts) { t, b, s, r, f -> GatewayRows(t, b, s, r, f) }
        val prefs = combine(_struggles, _rituals, _dismissedMoments) { s, r, d -> GatewayPrefs(s, r, d) }
        combine(rows, prefs, gatewayMinute) { r, p, _ ->
            val now = nowMs()
            val key = GatewayInputs(
                tasks = r.tasks, blocks = r.blocks, sessions = r.sessions, reasons = r.reasons, facts = r.facts,
                struggles = p.struggles, rituals = p.rituals, dismissed = p.dismissed.toSet(),
                todayIso = Clock.dateIso(now), minute = GatewayInputs.minute(now),
            )
            gatewayMemo.value(key) { deriveGateway(key, now) }
        }
            // composeBrief + pickMoment (derivePatterns over every block) is ~3 ms of
            // pure computation and stateIn(viewModelScope) would run it on
            // Dispatchers.Main.immediate — on the main thread, on every row emission
            // AND every 60 s minute tick while Today sits open. The derivation is
            // pure; only the resulting StateFlow needs to reach the UI thread, and
            // stateIn still publishes it there.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GatewayDerived.EMPTY)
    }

    /** How many times the gateway derivation actually ran (memo diagnostics). */
    internal val gatewayComputeCount: Int get() = gatewayMemo.computeCount

    /** Clear the confirmation — only if it's still the one we set (a newer
     *  action's ✓ must not be wiped by an older timer). */
    fun clearMomentDone(confirmation: String) {
        if (_momentDone.value == confirmation) _momentDone.value = null
    }

    private fun settleMoment(id: String, confirmation: String?) {
        dismissMoment(id)
        _momentDone.value = confirmation
    }

    /** Run one moment action. Dismiss/chat settle at once; the writing actions
     *  (carry / schedule / create) reduce purely ([GatewayActions]) and apply
     *  through [assistantApi] — committed before the ✓ shows. */
    fun runMomentAction(moment: Moment, action: MomentAction) {
        when (val run = action.run) {
            MomentRun.Dismiss -> settleMoment(moment.id, null)
            is MomentRun.Chat -> { settleMoment(moment.id, null); openAssistantWith(run.message) }
            is MomentRun.Schedule -> launchWrite {
                val api = assistantApi
                val w = GatewayActions.schedule(run.taskId, run.date, run.time, api.getTasks(), api.getBlocks(), api.todayIso(), newUuid(), api.nowIso())
                // A vanished task → no writes and no ✓; the moment is stale, so it
                // retires quietly instead of fabricating a block for a ghost.
                applyGatewayWrites(api, w)
                settleMoment(moment.id, w.confirmation)
            }
            is MomentRun.CreateTask -> launchWrite {
                val api = assistantApi
                val w = GatewayActions.createTask(run.name, run.estimateMin, newUuid(), api.nowIso())
                applyGatewayWrites(api, w)
                settleMoment(moment.id, w.confirmation)
            }
            is MomentRun.CarryTasks -> launchWrite {
                val api = assistantApi
                val today = api.todayIso()
                val w = GatewayActions.carryTasks(run.taskIds, api.getTasks(), api.getBlocks(), today, addDaysIso(today, 1), api.nowIso())
                // Nothing was on today to carry: no ✓, and the moment stays up
                // (it wasn't acted on).
                val confirmation = w.confirmation ?: return@launchWrite
                applyGatewayWrites(api, w)
                settleMoment(moment.id, confirmation)
            }
        }
    }

    private suspend fun applyGatewayWrites(api: AssistantApi, w: GatewayWrites) {
        // Task rows FIRST: a series' first placement re-anchors an every-N-weeks
        // rule, and writes reach the server in call order — the placed block
        // arriving before its rule let another device's top-up mint the old
        // weeks beside it (web review fix 1, 17181ed). A carry's move-count
        // bump doesn't care about the order.
        for (t in w.tasks) api.upsertTask(t)
        for (b in w.blocks) api.upsertBlock(b)
        // A series' first placement: its deterministic occurrence, minted
        // insert-if-absent with rule H (stage 2).
        for (b in w.inserts) api.insertBlockIfAbsent(b, retimeIfTaken = true)
    }

    /** "Open the Assistant sheet" requests (Today's input pill, a chat moment)
     *  — MainScaffold presents the sheet. [handoff] = a message is already on
     *  its way through [sendAssistant]'s queue (open onto the thread);
     *  [focusComposer] = put the keyboard in the sheet's composer. */
    data class AssistantOpenRequest(val handoff: Boolean, val focusComposer: Boolean)
    private val _assistantOpenRequests = MutableSharedFlow<AssistantOpenRequest>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val assistantOpenRequests: SharedFlow<AssistantOpenRequest> = _assistantOpenRequests.asSharedFlow()

    /** The hand-off: the sheet owns the thread, so open it and send the text
     *  through the same queue a typed message takes (never dropped). */
    fun openAssistantWith(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _assistantOpenRequests.tryEmit(AssistantOpenRequest(handoff = true, focusComposer = false))
        sendAssistant(t)
    }

    /** Today's input pill: open the sheet with focus in ITS composer (nothing
     *  is typed on Today — iOS AppModel.openAssistant(focusComposer:)). */
    fun openAssistant(focusComposer: Boolean = true) {
        _assistantOpenRequests.tryEmit(AssistantOpenRequest(handoff = false, focusComposer = focusComposer))
    }

    /** The in-thread interview has a question up (its chip row is on screen).
     *  The ≥1-fact stand-down must never fire while it is — its own answers
     *  grow the count (the old sheet's `isOpen`). Set by the assistant sheet. */
    private val _interviewThreadAsking = MutableStateFlow(false)
    val interviewThreadAsking: StateFlow<Boolean> = _interviewThreadAsking.asStateFlow()
    fun setInterviewThreadAsking(asking: Boolean) { _interviewThreadAsking.value = asking }

    /** The pure-ish half of [reconcileAssistantPrefs] (tested directly): [server] =
     *  the row as read (null = no row: never onboarded anywhere). */
    internal fun applyServerAssistantPrefs(uid: String, server: PreferencesClient.ServerUserPrefs?) {
        // Rituals: a pending local toggle re-pushes; otherwise the server wins.
        if (ritualsPendingPush(uid)) {
            pushRituals(uid, _rituals.value)
        } else {
            server?.rituals?.let { applyServerRituals(uid, it) }
        }
        // Interview flag: server done ⇒ pin local (and drop a half-way resume step —
        // they finished elsewhere); local done but server not ⇒ push it up.
        if (InterviewFlag.interviewDoneFromServer(server?.assistant_interview_done_at)) {
            markInterviewDoneLocal(uid)
        } else if (_interviewDone.value) {
            pushInterviewDone(uid)
        }
        // Onboarding struggles: the server row is the account's truth (picked on
        // any platform); canonicalised on the way in, cached per account so the
        // moments engine + the assistant context read them offline too.
        server?.adhd_struggles?.let { applyStruggles(uid, it) }
    }

    /** The whole hydrate hand-off, in order: the account's prefs land, THEN
     *  [profileFactsHydrated] flips — the gateway's auto-open gate decides on that
     *  flip (plan F9). One entry for the pull collector and the tests. */
    internal fun completeAssistantHydrate(uid: String, server: PreferencesClient.ServerUserPrefs?) {
        applyServerAssistantPrefs(uid, server)
        _profileFactsHydrated.value = true
    }

    /** The pull collector's version of the same hand-off: fetch + apply
     *  (best-effort — offline leaves the caches untouched), THEN flip. */
    private suspend fun hydrateAssistantPrefs(uid: String) {
        runCatching { reconcileAssistantPrefs(uid) }
        _profileFactsHydrated.value = true
    }

    private fun applyStruggles(uid: String, raw: List<String>) {
        val canonical = canonicalStruggles(raw)
        _struggles.value = canonical
        paPrefs.edit().putString(paKey(STRUGGLES_KEY, uid), canonical.joinToString("\n")).apply()
    }

    private fun decodeStruggles(raw: String?): List<String> =
        raw?.split('\n')?.filter { it.isNotBlank() }?.let(::canonicalStruggles) ?: emptyList()

    /** Sign-out: the assistant's memory is personal by definition — wipe the local
     *  rows (the server keeps the account's facts) and every gateway cache, so the
     *  next account on this device is greeted, not silently skipped. Same breath as
     *  [clearAssistant]. */
    internal suspend fun scrubAssistantUserState() {
        runCatching { profileFactsService.wipeLocal() }
        // The call settings share the file (calls.<field>.<uid>) — cleared with it
        // (risk 9); the explicit per-uid clear covers a uid still known here.
        currentUid()?.let { uid -> runCatching { tech.csalliance.unstuck.calls.CallSettingsStore.clear(graph.appContext, uid) } }
        // A LIVE call must not outlive the account either: the microphone
        // foreground service, its AudioRecord and the realtime socket keep
        // streaming under the JWT captured at dial time, and its outcome would
        // land in the queue for the NEXT account's token. Torn down FIRST (before
        // the ring state is cleared) and with NO outcome reported — the token is
        // already gone. iOS signedOut parity.
        runCatching { tech.csalliance.unstuck.calls.CallVoiceService.signedOut(graph.appContext) }
        // A ring / an unsent outcome must never survive into the NEXT account
        // (iOS signedOut parity): the queue would be replayed with the new JWT
        // (call-outcome answers not_found for every item) and the ring would
        // show the previous account's notes. No report — the JWT is already gone.
        runCatching { tech.csalliance.unstuck.calls.CallRinger.clear(graph.appContext) }
        runCatching { tech.csalliance.unstuck.calls.CallOutcomeStore.clear(graph.appContext) }
        runCatching { paPrefs.edit().clear().apply() }
        _callSettings.value = tech.csalliance.unstuck.core.logic.CallSettings()
        _callProactive.value = CallProactivePrefs.DEFAULTS
        _ringNudgeDismissed.value = false
        _rituals.value = RitualPrefs.DEFAULTS
        _dismissedMoments.value = emptyList()
        _interviewDone.value = false
        _struggles.value = emptyList()
        _profileFactsHydrated.value = false
        _momentDone.value = null
        _interviewThreadAsking.value = false
        // The voice session's un-landed receipts point at the PREVIOUS account's
        // rows, and their Undo would write them. `signedOut` abandons a live call
        // WITHOUT calling [endVoiceSession] (nothing may be reported under a dead
        // JWT), so nothing else drops them until the next session dials — and
        // `voiceReceipts` is public, so a surface rendering it would be showing
        // the last account's edits to the new one.
        dropVoiceSession()
    }

    // profile facts (app-facing sugar over the service)

    override suspend fun saveProfileFact(category: ProfileFactCategory, fact: String, source: ProfileFactSource, whenIso: String?): ProfileFact? =
        profileFactsService.save(category, fact, source, whenIso)

    /** Edit one remembered fact IN PLACE (Settings → "What Unstuck knows"): the
     *  same row and id, `updatedAt` bumped; a fresh save only when the row has
     *  vanished. The Result carries the [ProfileFactSaveError] so the panel can
     *  tell the user why nothing changed instead of silently reverting. */
    override suspend fun updateProfileFact(id: String, fact: String, whenIso: String?): Result<ProfileFact> =
        try {
            Result.success(profileFactsService.update(id, fact, whenIso))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    override suspend fun forgetProfileFact(id: String): Boolean = profileFactsService.remove(id)

    override suspend fun forgetAllProfileFacts() = profileFactsService.clear()

    /** The name they asked to be called, if any (beats the account name). */
    fun preferredName(): String? = ProfileFactsLogic.preferredName(profileFacts.value)

    /** True when they've asked not to be addressed by name. */
    fun noNamePreference(): Boolean = ProfileFactsLogic.noNamePreference(profileFacts.value)

    /** "Don't use my name" / "call me X" saved deterministically from the user's own
     *  words before the model sees them; the receipt is attached to the closing turn. */
    internal suspend fun saveStylePreference(userText: String): Receipt? {
        val pref = ProfileFactsLogic.detectStylePreference(userText) ?: return null
        // The facts BEFORE the save: repeating "don't use my name" refines the
        // preference saved weeks ago in place (same id), and a "forget" Undo
        // would delete THAT (Android audit 2026-09-23, A17). A failed read would
        // fail the save the same way (store() reads them too).
        val before = runCatching { profileFactsService.all() }.getOrElse { return null }
        val stored = profileFactsService.saveStylePreference(pref) ?: return null
        // The receipt IS the consent UX for a fact that then rides in every
        // future prompt — so it carries the same one-tap "forget" web and iOS
        // attach (review section 4); without it the only way back was Settings.
        val undo = factSaveUndo(ReceiptUndo.forgetFact(stored.id), before.firstOrNull { it.id == stored.id }, stored)
        return Receipt(ReceiptIcon.PENCIL, "Noted: ${stored.fact}", undo)
    }

    // ONE endless thread (redesign 2026-08-02): DISPLAY history persists long
    // (200 turns, receipts + local check-ins included) while the MODEL window
    // stays short (40, aligned to a user turn) — see assistantModelWindow. The
    // old code capped BOTH at 40 via the persist path only, so the in-session
    // wire payload actually grew unbounded; the window is now applied per ask.
    private fun persistAssistant() {
        runCatching {
            val window = assistantPersistWindow(assistantHistory.toList())
            assistantPrefs.edit().putString("history", Json.encodeToString(window)).apply()
        }
    }

    /** Stamp a fresh turn with its identity + landing time (day dividers). */
    private fun turn(
        role: String,
        content: String? = null,
        toolCalls: List<ToolCall>? = null,
        toolCallId: String? = null,
        name: String? = null,
        local: Boolean = false,
    ) = ChatMessage(
        role = role, content = content, toolCalls = toolCalls, toolCallId = toolCallId,
        name = name, id = newUuid(), at = nowMs(), local = local,
    )

    /** Inject a LOCAL display-only assistant turn (the daily check-in, a voice
     *  session's receipts, an interview question). Never enters the model
     *  window; persists like any other display turn — receipts included, so
     *  their Undo keeps working. @return the turn's id (the interview draws
     *  its chip row under the question it is asking), null for a blank. */
    fun appendLocalAssistant(content: String, receipts: List<Receipt>? = null): String? {
        if (content.isBlank()) return null
        val t = turn("assistant", content = content, local = true).copy(receipts = receipts?.takeIf { it.isNotEmpty() })
        assistantHistory.add(t)
        persistAssistant()
        return t.id
    }

    /** Inject a LOCAL user bubble — the interview echoing a tapped chip so the
     *  thread reads as a conversation. Never enters the model window. */
    fun appendLocalUser(content: String) {
        if (content.isBlank()) return
        assistantHistory.add(turn("user", content = content, local = true))
        persistAssistant()
    }

    /** "YYYY-MM-DD" of the last daily check-in on this device (null = never). */
    fun lastCheckinDay(): String? = runCatching { assistantPrefs.getString("checkin", null) }.getOrNull()

    fun markCheckinDay(dayIso: String) {
        runCatching { assistantPrefs.edit().putString("checkin", dayIso).apply() }
    }

    /** Undo one receipt on a persisted assistant turn; flips `undone` so the
     *  button doesn't come back. No-op when the receipt is gone / already used or
     *  its target no longer exists (then the label stays actionable-looking
     *  rather than lying about a revert that didn't happen). REFUSED — and the
     *  card says why ([receiptUndoNotes]) — when a row it would write changed
     *  since its turn (Android audit 2026-09-23, A17). Runs on viewModelScope:
     *  some undos (cancel_call) are a network round-trip. */
    fun undoAssistantReceipt(messageId: String, index: Int) {
        viewModelScope.launch { undoReceiptNow(messageId, index) }
    }

    /** "Undo all N changes" on [messageId]: the receipts at [indices] — exactly
     *  the ones its confirmation named, never one it left out (a refused Undo
     *  that has since become possible again) — newest first, ONE AT A TIME.
     *  Launched side by side they interleaved, and a "Completed X" undo could
     *  re-write a task its "Created X" undo had just deleted (Android audit
     *  2026-09-23, A17). Each one is checked on its own. */
    fun undoAllAssistantReceipts(messageId: String, indices: List<Int>) {
        viewModelScope.launch {
            for (i in indices.distinct().sortedDescending()) undoReceiptNow(messageId, i)
        }
    }

    private suspend fun undoReceiptNow(messageId: String, index: Int) {
        val receipt = assistantHistory.firstOrNull { it.id == messageId }?.receipts?.getOrNull(index) ?: return
        if (receipt.undone) return
        val undo = receipt.undo ?: return
        // One round-trip per receipt: a second tap while the CANCEL_CALL network
        // undo is running is ignored (its control reads "cancelling…" meanwhile).
        val key = tech.csalliance.unstuck.ui.assistant.receiptUndoKey(messageId, index)
        if (key in _receiptUndosInFlight.value) return
        _receiptUndosInFlight.value = _receiptUndosInFlight.value + key
        val outcome = try { runCatching { performReceiptUndo(undo) }.getOrDefault(UndoOutcome.NoOp) } finally {
            _receiptUndosInFlight.value = _receiptUndosInFlight.value - key
        }
        val before = when (outcome) {
            is UndoOutcome.Refused -> { _receiptUndoNotes.value = _receiptUndoNotes.value + (key to outcome.reason); return }
            UndoOutcome.NoOp -> return
            is UndoOutcome.Done -> { _receiptUndoNotes.value = _receiptUndoNotes.value - key; outcome.before }
        }
        // An EARLIER receipt of this turn on the same rows was stamped with the
        // state this undo just reverted — it follows, but only for rows still as
        // it last saw them. This undo checks only the rows it writes, so one the
        // user reopened and renamed, or noted, since must keep its old stamp and
        // refuse (Android audit 2026-09-23, A17).
        val after = if (before == null || undo.stamps.isEmpty()) null else runCatching { undoState() }.getOrNull()
        val cur = assistantHistory.indexOfFirst { it.id == messageId }
        if (cur < 0) return
        val m = assistantHistory[cur]
        val receipts = m.receipts?.mapIndexed { i, r ->
            val u = r.undo
            when {
                i == index -> r.copy(undone = true)
                i < index && before != null && after != null && u != null && r.isUndoable && u.stamps.keys.any { it in undo.stamps } ->
                    r.copy(undo = advanceReceiptUndo(u, before, after, keys = undo.stamps.keys))
                else -> r
            }
        } ?: return
        assistantHistory[cur] = m.copy(receipts = receipts)
        persistAssistant()
    }

    private sealed interface UndoOutcome {
        /** Reverted; [before] = the rows it was checked against (null for a call cancel). */
        data class Done(val before: UndoState?) : UndoOutcome
        /** Nothing to revert (the target is gone / already reverted) — the receipt stays live. */
        data object NoOp : UndoOutcome
        data class Refused(val reason: String) : UndoOutcome
    }

    /** The rows an Undo is checked against, read fresh (decoded off Main). */
    private suspend fun undoState(): UndoState = withContext(Dispatchers.Default) {
        val api = assistantApi
        UndoState(api.getTasks(), api.getBlocks(), api.getCaptures(), api.getArchivedCaptureIds(), api.getProfileFacts())
    }

    /** A tool that can change a row an Undo covers — not a read or a navigation. */
    private fun writesRows(name: String): Boolean =
        name !in AssistantHarnessRules.READ_ONLY_TOOLS && name !in AssistantHarnessRules.NAVIGATION_TOOLS

    /** Serialises [stampedWrite]: a voice session's tool calls run side by side
     *  (VoiceRealtimeClient launches each), and one's write landing between
     *  another's before / after reads would be taken for that one's. */
    private val receiptStampLock = Mutex()

    /** Run one assistant write — a tool of a text turn or voice session, or the
     *  turn's style-preference save — and keep [session]'s receipts EXACT
     *  (Android audit 2026-09-23, A17). The new receipt is stamped with the rows
     *  as THIS write left them; each earlier receipt follows the write only for
     *  rows still as it last saw them ([advanceReceiptUndo]). Stamping when the
     *  session ENDED baked in whatever the user did meanwhile — a task renamed or
     *  noted in the app during a call — and that Undo then deleted it. A
     *  read-only tool ([writes] false) writes nothing to follow. Unstamped on a
     *  failed read: its destructive Undo then refuses rather than guesses. */
    private suspend fun <T> stampedWrite(
        session: MutableStateFlow<List<Receipt>>, writes: Boolean = true, write: suspend () -> Pair<T, Receipt?>,
    ): T = receiptStampLock.withLock {
        val follow = writes && session.value.any { it.isUndoable && it.undo?.stamped == true }
        val before = if (follow) runCatching { undoState() }.getOrNull() else null
        val (value, receipt) = write()
        if (receipt?.undo == null && before == null) {
            receipt?.let { r -> session.update { it + r } }
            return@withLock value
        }
        val after = runCatching { undoState() }.getOrNull()
        val stamped = receipt?.let { r -> val u = r.undo; if (u != null && after != null) r.copy(undo = stampReceiptUndo(u, after)) else r }
        // update{}: endVoiceSession may land (and clear) the session meanwhile —
        // a late receipt then waits for the next one, as it always has.
        session.update { cur ->
            val advanced = if (before == null || after == null) cur else cur.map { r ->
                val u = r.undo
                if (u != null && r.isUndoable) r.copy(undo = advanceReceiptUndo(u, before, after)) else r
            }
            advanced + listOfNotNull(stamped)
        }
        value
    }

    /** Run one tool and derive its receipt — tool name + args + the executor's
     *  own result, scratch rows first so a task made earlier in the same turn
     *  resolves its undo target — with the pre-call state an EXACT Undo needs
     *  (Android audit 2026-09-23, A17): a fact save that refined an existing
     *  fact in place (same id) undoes by restoring the old wording, never by
     *  forgetting a fact the user had before, and offers no Undo when nothing
     *  changed; a promote remembers whether its capture was in the inbox. */
    private suspend fun runToolForReceipt(name: String, args: ToolArgs, api: AssistantApi, scratch: TurnScratch): Pair<String, Receipt?> {
        // Null = the facts couldn't be read, so a refine can't be told from a new fact.
        val factsBefore = if (name == "save_profile_fact") runCatching { api.getProfileFacts() }.getOrNull() else emptyList()
        val inboxBefore = name == "promote_capture" && args.str("captureId")?.let { it !in api.getArchivedCaptureIds() } == true
        val result = runAssistantTool(name, args, api, scratch)
        val receipt = deriveReceipt(name, args.receiptArgs, result, scratch.newTasks.values.toList() + api.getTasks(), clock = clockMode())
        val undo = receipt?.undo ?: return result to receipt
        val exact = when (undo.kind) {
            ReceiptUndoKind.FORGET_FACT -> factsBefore?.let { before ->
                factSaveUndo(undo, before.firstOrNull { it.id == undo.id }, runCatching { api.getProfileFacts() }.getOrNull()?.firstOrNull { it.id == undo.id })
            }
            ReceiptUndoKind.UNPROMOTE_CAPTURE -> undo.copy(unarchive = inboxBefore)
            else -> undo
        }
        return result to receipt.copy(undo = exact)
    }

    /** The write a receipt's Undo performs — every undo kind the contract lists,
     *  through the SAME executor state the tools use. Keyed on the kind's NAME so
     *  the kinds :core adds (DELETE_TASKS / UNCOMPLETE_TASKS / FORGET_FACT /
     *  DELETE_CAPTURE / COMPLETE_TASK / CANCEL_CALL) light up without a
     *  compile-time dependency. The bulk kinds carry their targets in `ids`
     *  (ReceiptUndo.deleteTasks / uncompleteTasks leave `id` empty) — reading
     *  only `id` made "Undo" on a create_tasks / complete_tasks receipt a
     *  silent no-op. The comma-split is the fallback for receipts persisted
     *  before `ids` existed. Nothing is written when a row it would write has
     *  changed since its turn (Android audit 2026-09-23, A17). */
    private suspend fun performReceiptUndo(undo: ReceiptUndo): UndoOutcome {
        val api = assistantApi
        val ids = undo.ids.ifEmpty { undo.id.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return UndoOutcome.NoOp
        // A call cancel is a network write with no local row to check.
        val before = if (undo.kind == ReceiptUndoKind.CANCEL_CALL) null else undoState()
        if (before != null) receiptUndoRefusal(undo, before)?.let { return UndoOutcome.Refused(it) }
        val done = when (undo.kind.name) {
            "DELETE_TASK", "DELETE_TASKS" -> {
                var any = false
                for (id in ids) {
                    if (api.getTasks().none { it.id == id }) continue
                    // The task and its blocks, as iOS removeTaskAndBlocks. Its
                    // captures are the user's notes: the old cascade hard-deleted
                    // them on every device (Android audit 2026-09-23, A17).
                    for (b in api.getBlocks().filter { it.taskId == id }) api.deleteBlock(b.id)
                    api.removeTask(id)
                    any = true
                }
                any
            }
            "UNPROMOTE_CAPTURE" -> {
                var any = false
                api.getCaptures().firstOrNull { it.id == undo.captureId }?.let { c ->
                    // Unlinked only when the promote linked it (it links a capture
                    // that had no task); one filed on another task stays filed there.
                    // Before the task goes, so its delete never reaches the link.
                    if (c.taskId == undo.id) { api.upsertCapture(c.copy(taskId = null)); any = true }
                    if (undo.unarchive && c.id in api.getArchivedCaptureIds()) { api.archiveCapture(c.id, false); any = true }
                }
                if (api.getTasks().any { it.id == undo.id }) {
                    for (b in api.getBlocks().filter { it.taskId == undo.id }) api.deleteBlock(b.id)
                    api.removeTask(undo.id)
                    any = true
                }
                any
            }
            "RESTORE_FACT" -> undo.prior?.let { profileFactsService.restore(it) } ?: false
            "UNCOMPLETE_TASK", "UNCOMPLETE_TASKS" -> {
                var any = false
                for (id in ids) {
                    val t = api.getTasks().firstOrNull { it.id == id && it.done } ?: continue
                    api.upsertTask(t.copy(done = false, completedAt = null, updatedAt = isoNow()))
                    api.notifyTaskReopenedIfShared(t)
                    any = true
                }
                any
            }
            "COMPLETE_TASK" -> {
                // Never a series' template: its done ENDS the series (audit
                // 2026-09-22 C3 — only an older persisted "Reopened" receipt names one).
                val t = api.getTasks().firstOrNull { it.id == ids[0] && it.recurrence == null } ?: return UndoOutcome.NoOp
                // Re-ticked by hand meanwhile: the undo's target state already holds
                // and that tick sent its own `done` — re-writing would only re-stamp
                // the real completion time (parity with iOS build 81, audit 2026-09-22 C6).
                if (t.done) return UndoOutcome.Done(before)
                api.upsertTask(applyCompletion(t.copy(done = true), prior = t, nowISO = isoNow()))
                // Undoing a "Reopened" re-completes — the shared-list `done` travels
                // as the UI's tick sends it, since the reopen already went out
                // (parity with iOS build 81, audit 2026-09-22 C6).
                api.notifyTaskCompletedIfShared(t)
                true
            }
            "FORGET_FACT" -> api.removeProfileFact(ids[0])
            "DELETE_CAPTURE" -> {
                if (api.getCaptures().none { it.id == ids[0] }) return UndoOutcome.NoOp
                api.removeCapture(ids[0]); true
            }
            "CANCEL_CALL" -> api.callStore()?.cancelCall(ids[0]) != null
            else -> false
        }
        return if (done) UndoOutcome.Done(before) else UndoOutcome.NoOp
    }

    /** Clear the conversation — the ⋯ menu's "Clear conversation" and the
     *  sign-out scrub. (There is no "new chat": the thread is endless, so this
     *  is a deliberate erase, not a way to start a second conversation.) The
     *  epoch bump cancels an in-flight turn AND drops the send queue. */
    fun clearAssistant() {
        assistantEpoch++
        assistantJob?.cancel()
        assistantJob = null
        _assistantSending.value = false
        _assistantError.value = null
        noteAssistantOutcome(null)
        _assistantQueued.value = emptyList()
        _receiptUndoNotes.value = emptyMap()
        assistantHistory.clear()
        // Staged-but-unconfirmed shares belong to the conversation that proposed
        // them; they must not outlive it (nor leak to the next account).
        _pendingShares.value = emptyList()
        runCatching { assistantPrefs.edit().clear().apply() }
    }

    /** Dismiss the last turn's error banner (e.g. the user starts typing a retry) so it
     *  doesn't linger through the retry or across reopening the sheet. */
    fun clearAssistantError() { _assistantError.value = null }

    /** Append a user message + run the agentic turn on viewModelScope, persisting.
     *  Fire-and-forget for the caller: progress/result surface via
     *  [assistantSending], [assistantError] and [assistantReplies]. A send while a
     *  turn is in flight is QUEUED (visible as a pending bubble — [assistantQueued])
     *  and sent when the reply lands — never dropped (contract §8, F3). */
    fun sendAssistant(userText: String) {
        val t = userText.trim()
        if (t.isEmpty()) return
        if (_assistantSending.value) {
            _assistantQueued.value = _assistantQueued.value + QueuedSend(newUuid(), t)
            return
        }
        startAssistantTurn(t)
    }

    private fun startAssistantTurn(text: String) {
        _assistantSending.value = true
        _assistantError.value = null
        val epoch = assistantEpoch
        assistantJob = viewModelScope.launch {
            try {
                when (val result = runAssistantTurn(text, epoch)) {
                    is AssistantTurn.Reply -> if (epoch == assistantEpoch) {
                        _assistantReplies.tryEmit(result.text)
                        noteAssistantOutcome(null)
                    }
                    is AssistantTurn.Error -> if (epoch == assistantEpoch) {
                        _assistantError.value = result.code
                        noteAssistantOutcome(result.code)
                    }
                }
                if (epoch == assistantEpoch) persistAssistant()
            } finally {
                // A cleared conversation (epoch bumped) drops its queue too.
                if (epoch == assistantEpoch) {
                    _assistantSending.value = false
                    drainAssistantQueue()
                }
            }
        }
    }

    /** Drain the queue one message per idle moment. */
    private fun drainAssistantQueue() {
        if (_assistantSending.value) return
        val next = _assistantQueued.value.firstOrNull() ?: return
        _assistantQueued.value = _assistantQueued.value.drop(1)
        startAssistantTurn(next.text)
    }

    /** The executor's seam over this ViewModel (AssistantToolsAppModel.kt) — the
     *  text harness, the voice session, receipts' Undo and the tour all run
     *  against it. */
    internal val assistantApi: AssistantApi by lazy { AppViewModelAssistantApi(this) }

    /** Transport failure inside a harness round — the code the UI banner shows. */
    private class AssistantAskException(val code: String) : Exception(code)

    /** One agentic turn through the :core [AssistantHarness]: up to 5 model
     *  rounds, every tool call executed here, the fabrication guard's single
     *  hidden bounce, the cut-off hint, honest fallbacks — and NEVER a
     *  synthesised "Done." (F2/F5). The user turn is shown at once; the turn's
     *  new messages (tool rounds included) are appended when it lands, receipts
     *  attached to the closing assistant turn. */
    private suspend fun runAssistantTurn(text: String, epoch: Int): AssistantTurn {
        withContext(Dispatchers.Main.immediate) { assistantHistory.add(turn("user", content = text)) }
        persistAssistant()
        // "Don't use my name" / "call me X" are saved by the APP before the
        // model sees the message (web + iOS parity: the model kept promising
        // to remember without saving); the receipt makes it visible.
        // Each receipt is stamped as its own write left the rows (A17).
        val receipts = MutableStateFlow<List<Receipt>>(emptyList())
        stampedWrite(receipts) { Unit to saveStylePreference(text) }
        val a = assistant ?: return AssistantTurn.Error("not_configured")
        val api = assistantApi
        val scratch = TurnScratch()

        // Only the trimmed, user-aligned, local-free window ever goes over the
        // wire — the on-screen thread is far longer than what we send. The
        // harness appends the user turn itself, so hand it the window WITHOUT it.
        val window = assistantModelWindow(assistantHistory.toList())
        val base = window.dropLast(1).map { it.toHarness() }

        val ask = HarnessAsk { messages ->
            // The context snapshot decodes ~9 whole Room tables to JSON. The turn
            // runs on viewModelScope (Dispatchers.Main.immediate) and never hopped
            // dispatcher, so that decode ran on the UI thread once per model round
            // — the one place the codebase's own "decode off the main thread" rule
            // was missed (review section 4). Off Main now.
            val wire = messages.map { it.toChat() }
            // The TEXT request reports the gated tools this build can run
            // (toolCaps, week-review-spec D9); the voice + tour contexts never do.
            val context = withContext(Dispatchers.Default) { buildTextRequestContext(api) }
            when (val r = a.ask(wire, context)) {
                is AssistantResult.Ok -> HarnessReply(
                    text = r.reply.content,
                    toolCalls = r.reply.toolCalls.orEmpty().map { HarnessToolCall(it.id, it.function.name, it.function.arguments) },
                    finishReason = r.reply.finishReason,
                )
                is AssistantResult.Err -> throw AssistantAskException(r.code)
            }
        }
        // Tool execution decodes the same tables again (create_tasks → scheduleTask
        // reads blocks + tasks per item) — also off Main. Nothing the executor
        // touches is Compose state; only the assistantHistory mutations below
        // stay on Main.immediate.
        val runner = HarnessToolRunner { call ->
            withContext(Dispatchers.Default) {
                val args = ToolArgs.parse(call.argumentsJson)
                // Deterministic receipts for everything this turn actually changed —
                // from the tool name + args + the executor's own result string, never
                // from the model's prose. Scratch rows first so an in-flight
                // completion resolves its undo target.
                stampedWrite(receipts, writes = writesRows(call.name)) { runToolForReceipt(call.name, args, api, scratch) }
            }
        }
        val outcome = try {
            // Confirm-first reads the name of what a destructive call points at,
            // so "delete Gym" can't delete another task (James, build 51).
            val confirmTarget: suspend (HarnessToolCall) -> String? = { call ->
                withContext(Dispatchers.Default) { confirmTargetName(call.name, ToolArgs.parse(call.argumentsJson), api, scratch) }
            }
            AssistantHarness(ask, runner, confirmTarget, clock = clockMode()).turn(base, text)
        } catch (e: HarnessAskFailed) {
            val code = (e.cause as? AssistantAskException)?.code ?: "network"
            // A failed FIRST round keeps the user's turn (the panel shows the
            // error inline). A later round persists the partial exchange — the
            // tool rounds that already ran — closed with the harness's honest
            // "dropped mid-reply" line carrying their receipts (never re-run).
            val partial = e.partial
            if (partial != null && epoch == assistantEpoch) {
                val fresh = partial.messages.drop(base.size + 1).map { it.toChat(stamp = true) }.toMutableList()
                val last = fresh.indexOfLast { it.role == "assistant" && it.toolCalls.isNullOrEmpty() }
                if (last >= 0 && receipts.value.isNotEmpty()) fresh[last] = fresh[last].copy(receipts = receipts.value)
                withContext(Dispatchers.Main.immediate) { assistantHistory.addAll(fresh) }
            }
            return AssistantTurn.Error(code)
        }
        if (epoch != assistantEpoch) return AssistantTurn.Error("cancelled")

        // The harness already polished the model's FINAL text (and never an
        // honest fallback or the hidden bounce) — show it as it came back.
        val closing = outcome.text
        val fresh = outcome.messages.drop(base.size + 1).map { it.toChat(stamp = true) }.toMutableList()
        val last = fresh.indexOfLast { it.role == "assistant" }
        if (last >= 0) fresh[last] = fresh[last].copy(content = closing, receipts = receipts.value.takeIf { it.isNotEmpty() })
        withContext(Dispatchers.Main.immediate) { assistantHistory.addAll(fresh) }
        return AssistantTurn.Reply(closing)
    }

    /** A persisted turn on its way back INTO the harness. The `arguments` string
     *  is re-normalised here, not just when a fresh reply is stored: a thread
     *  poisoned before the hygiene fix (a `finish_reason=length` tool_call whose
     *  arguments are "" or cut-off JSON) is replayed verbatim on every later
     *  turn, and DashScope 400s the WHOLE request ("function.arguments … must be
     *  in JSON format") — every turn failed forever and the only escape erased
     *  the thread. Normalising on replay repairs the old conversation instead
     *  (review section 4). A valid object is passed through untouched. */
    internal fun ChatMessage.toHarness() = HarnessMessage(
        role = role, content = content,
        toolCalls = toolCalls.orEmpty().map {
            HarnessToolCall(it.id, it.function.name, AssistantHarnessRules.argumentsAsObjectJson(it.function.arguments))
        },
        toolCallId = toolCallId, name = name,
    )

    /** A harness message as a display/persistence turn ([stamp] = mint id + time). */
    private fun HarnessMessage.toChat(stamp: Boolean = false) = ChatMessage(
        role = role, content = content,
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map { ToolCall(it.id, "function", ToolFunction(it.name, it.argumentsJson)) },
        toolCallId = toolCallId, name = name,
        id = if (stamp) newUuid() else null, at = if (stamp) nowMs() else null,
    )

    /** Compatibility entry (the pre-harness signature the ViewModel tests call):
     *  run ONE tool against this ViewModel's state with the caller's scratch maps. */
    internal suspend fun runAssistantTool(
        name: String, args: JsonObject,
        newTasks: HashMap<String, TaskItem>, newLists: HashMap<String, ItemCollection>,
    ): String {
        val scratch = TurnScratch().apply { this.newTasks.putAll(newTasks); this.newLists.putAll(newLists) }
        val result = runAssistantTool(name, ToolArgs(args), assistantApi, scratch)
        newTasks.clear(); newTasks.putAll(scratch.newTasks)
        newLists.clear(); newLists.putAll(scratch.newLists)
        return result
    }

    /** One tour-mode Q&A round-trip through the SAME assistant transport.
     *  `context.tour = {step, title}` flags the server's product-tour mode
     *  (no tools, product-Q&A only — enforced server-side). Any tool_calls in
     *  the reply are IGNORED (the tour only ever speaks); returns the text
     *  reply, or null on any error → the caller answers from canned TOUR_QA. */
    suspend fun tourAsk(messages: List<ChatMessage>, stepId: String, stepTitle: String): String? {
        val a = assistant ?: return null
        val context = buildJsonObject {
            buildAssistantContext(assistantApi).forEach { (k, v) -> put(k, v) }
            putJsonObject("tour") { put("step", stepId); put("title", stepTitle) }
        }
        return when (val r = a.ask(messages, context)) {
            is AssistantResult.Ok -> r.reply.content?.trim()?.takeIf { it.isNotEmpty() }
            is AssistantResult.Err -> null
        }
    }

    // --- voice (realtime, Qwen-Omni via the Cloudflare proxy) ---
    // The realtime session is configured CLIENT-side (session.update), so the
    // instructions + tool schemas live here (ui/assistant/AssistantContext.kt +
    // VoiceToolSchema.kt). Tool execution reuses the same executor as text mode,
    // with a session scratch for mid-call entities.

    val voiceProxyUrl: String get() = tech.csalliance.unstuck.BuildConfig.VOICE_PROXY_URL
    val voiceModel: String get() = "qwen3.5-omni-flash-realtime"
    /** Realtime voice is available only when a proxy is configured AND the user
     *  hasn't switched AI off (Settings → Interface → AI Assistant). The
     *  kill-switch has to reach EVERY assistant surface, voice included — the
     *  published privacy policy promises exactly that. */
    fun voiceConfigured(): Boolean = voiceProxyUrl.isNotBlank() && settings.value.assistantEnabled
    /** The stored access token. It may be EXPIRED (supabase-kt refreshes only
     *  while the app is in the foreground), so it is only the "signed in?" gate
     *  and the fallback — a dial goes through [freshVoiceAccessToken]. */
    fun voiceAccessToken(): String? = graph.provider?.client?.auth?.currentSessionOrNull()?.accessToken

    /** The token a voice dial sends: refreshed when it is expired OR would
     *  expire before the session can end — a lock-screen call on an app idle
     *  overnight otherwise dialled with a dead token and the proxy's 401 hung
     *  it up. `forceRefresh` follows that 401 and never answers with the token
     *  the proxy refused (parity with iOS build 81, audit 2026-09-22 C14/C15). */
    suspend fun freshVoiceAccessToken(forceRefresh: Boolean = false): String? {
        val auth = graph.provider?.client?.auth
        val fresh = if (auth == null) null else tech.csalliance.unstuck.ui.assistant.VoiceToken.resolve(
            forceRefresh = forceRefresh,
            nowMs = { System.currentTimeMillis() },
            stored = {
                auth.currentSessionOrNull()?.let {
                    tech.csalliance.unstuck.ui.assistant.VoiceToken.Stored(it.accessToken, it.expiresAt.toEpochMilliseconds())
                }
            },
            // The refresh token is read at call time by the SDK; the new session
            // is imported (and persisted) before this reads it back.
            refresh = { auth.refreshCurrentSession(); auth.currentSessionOrNull()?.accessToken },
            // Never viewModelScope: a refresh cut mid-flight can spend the refresh
            // token without storing its successor.
            scope = graph.scope,
        )
        return tech.csalliance.unstuck.ui.assistant.VoiceToken.dialToken(fresh, voiceAccessToken(), forceRefresh)
    }

    private val voiceScratch = TurnScratch()

    /** Receipts for what THIS voice session actually changed (oldest first). The
     *  overlay can show them live; [endVoiceSession] lands the un-undone ones in
     *  the shared thread so nothing the voice assistant wrote is invisible or
     *  un-undoable (iOS `voiceReceipts` / web voice-mode parity — the receipt IS
     *  the consent UX; review section 4). */
    private val _voiceReceipts = MutableStateFlow<List<Receipt>>(emptyList())
    val voiceReceipts: StateFlow<List<Receipt>> = _voiceReceipts.asStateFlow()

    /**
     * Start of a session: drop the mid-session entities, and LAND — never
     * discard — anything the previous session left un-landed.
     *
     * The two voice surfaces do not hand over cleanly. A call ringing while the
     * Talk overlay is up stops Talk's client through audio focus, but the
     * holder only lands its receipts when the SCREEN is dismissed — which is
     * usually after the answered call's `sessionWillStart` has run this. A plain
     * `_voiceReceipts.value = emptyList()` here threw away everything the Talk
     * session had just written, Undo and all. [endVoiceSession] is idempotent
     * and a no-op for a session that wrote nothing, so this costs nothing in
     * the normal case. ([dropVoiceSession] is the sign-out path, where the
     * thread itself is being erased.)
     */
    fun resetVoiceScratch() {
        endVoiceSession()
        voiceScratch.clear()
    }

    /** Sign-out: forget the voice session WITHOUT landing its receipts — they
     *  point at the previous account's rows and the thread is being erased in
     *  the same breath. */
    private fun dropVoiceSession() {
        voiceScratch.clear()
        _voiceReceipts.value = emptyList()
    }

    suspend fun runVoiceTool(name: String, args: JsonObject): String {
        // finish_interview used to be intercepted here; since the 2026-09-20
        // tooling rewrite it is a registry tool the executor answers through
        // AssistantApi.markInterviewDone (same local flag + server push).
        val parsed = ToolArgs(args)
        // Derived exactly like the text harness: tool name + args + the executor's
        // own result string, never model prose. Scratch rows first so a task
        // created earlier in the SAME session resolves its undo target. Stamped
        // as each tool returns (A17): the app stays usable during a call.
        return stampedWrite(_voiceReceipts, writes = writesRows(name)) { runToolForReceipt(name, parsed, assistantApi, voiceScratch) }
    }

    /** The session ended (overlay closed / call hung up): its receipts — and
     *  their Undo — must not vanish with it. They land in the shared thread as
     *  ONE local turn ("While we talked:"), which persists like any other.
     *  Safe from any thread (a call's `sessionDidEnd` fires off Main). */
    fun endVoiceSession() {
        // Taken and cleared in one step: a tool still returning (stampedWrite)
        // either lands in this batch or waits for the next — never wiped.
        val rs = _voiceReceipts.getAndUpdate { emptyList() }.filter { !it.undone }
        if (rs.isEmpty()) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            // Already stamped, tool by tool (runVoiceTool). Never re-stamped here:
            // that baked in whatever the user changed in the app during the call,
            // and the Undo then deleted the edited task (Android audit 2026-09-23, A17).
            appendLocalAssistant(VOICE_SESSION_RECEIPTS, rs)
        }
    }

    /** What the voice assistant should do the instant a session opens — the
     *  interview branch on first contact, else a by-name hello (web parity). */
    fun voiceOpening(): String = kotlinx.coroutines.runBlocking { buildVoiceOpening(assistantApi) }
    suspend fun voiceOpeningAsync(): String = buildVoiceOpening(assistantApi)

    /** Voice (realtime) system prompt + live context — mirrors web voiceInstructions(),
     *  profile + interview lines included. Built once per session; the Room reads
     *  behind it block the caller briefly (VoiceModeScreen.ensureStarted is not
     *  suspending yet — prefer [voiceInstructionsAsync] from a coroutine). */
    fun voiceInstructions(): String = kotlinx.coroutines.runBlocking { buildVoiceInstructions(assistantApi) }
    suspend fun voiceInstructionsAsync(): String = buildVoiceInstructions(assistantApi)

    /** Tool schemas for the realtime TALK session — the generated registry's
     *  voice surface (VoiceToolSchema.kt parses ToolRegistry.JSON), so voice can
     *  never advertise a tool the executor lacks (ToolRegistryParityTest). */
    fun voiceTools(): JsonArray = talkVoiceToolsJson()

    /** Tool schemas for a CALL session: EVERY voice tool plus the call-only
     *  snooze_call — the call is the full assistant (core `CallScript.callToolNames`). */
    fun callVoiceTools(): JsonArray = callVoiceToolsJson()

    /** The app-side seams an ANSWERED call's conversation runs on (instructions,
     *  tools, the executor, the access token). CallVoiceService owns the socket
     *  and the mic but has no ViewModel of its own, so it waits up to
     *  LAUNCHER_GRACE_MS for these to be bound — unbound, EVERY answered call
     *  degrades to the "couldn't start the call" notification. */
    private val callVoiceDeps: tech.csalliance.unstuck.calls.CallVoiceService.Deps =
        tech.csalliance.unstuck.calls.CallVoiceService.Deps.live(this)

    init { tech.csalliance.unstuck.calls.CallVoiceService.bind(callVoiceDeps) }

    // --- "Calls from Unstuck": the task editor's "Call me about this" + Settings
    // book / update / cancel through the SAME executor path as the assistant's
    // request_call / update_call / cancel_call (guards, duplicate rule, wording),
    // so the editor can never book what the assistant would refuse. Results are
    // the contract strings ("ok: …" / "error: …"); CallMeLogic.userMessage turns
    // them into copy. Writes go DIRECT (never the outbox — 053's guard owns status). ---

    /** Calls need a signed-in Supabase client (the tools say so otherwise). */
    fun callsAvailable(): Boolean = currentUid() != null && assistantApi.callStore() != null

    /** A USER change from Settings → Calls: cache for this account (+ the in-memory
     *  value Push.kt's decide() reads). Signed out → in-memory only. */
    fun updateCallSettings(transform: (CallSettings) -> CallSettings) {
        val next = transform(_callSettings.value)
        _callSettings.value = next
        val uid = currentUid() ?: return
        runCatching { CallSettingsStore.save(graph.appContext, uid, next) }
    }

    /** A USER change from Settings › Calls to the proactive calls: cache it for
     *  this account, mark it pending, and push it to `notification_preferences`
     *  now (best-effort — a failure leaves it pending for the next pull, which
     *  re-pushes rather than pulling the server's older value over it). */
    fun setCallProactivePrefs(p: CallProactivePrefs) {
        _callProactive.value = p
        val uid = currentUid() ?: return
        runCatching {
            CallSettingsStore.saveProactive(graph.appContext, uid, p)
            CallSettingsStore.setPendingProactivePush(graph.appContext, uid, true)
        }
        viewModelScope.launch { runCatching { pushCallProactivePrefs(uid) } }
    }

    /** Settings › Calls opened: pull the account's proactive prefs (a toggle
     *  made on the web / iPhone reaches this phone). */
    fun refreshCallProactivePrefs() {
        val uid = currentUid() ?: return
        viewModelScope.launch { runCatching { reconcileCallProactivePrefs(uid) } }
    }

    private val callProactiveMutex = Mutex()

    /** Push the cached copy up; clears the pending flag on success. */
    private suspend fun pushCallProactivePrefs(uid: String) = callProactiveMutex.withLock {
        val prefsClient = graph.coordinator?.preferences ?: return@withLock
        if (!CallSettingsStore.pendingProactivePush(graph.appContext, uid)) return@withLock
        val local = CallSettingsStore.loadProactive(graph.appContext, uid)
        runCatching { prefsClient.setCallProactivePrefs(uid, local) }
            .onSuccess { CallSettingsStore.setPendingProactivePush(graph.appContext, uid, false) }
    }

    /** After every pull (and on a prefs realtime event): land a pending
     *  toggle first, then READ BACK — the server wins unless this device's own
     *  write hasn't landed yet (core CallProactiveSync.resolve). */
    private suspend fun reconcileCallProactivePrefs(uid: String) {
        val prefsClient = graph.coordinator?.preferences ?: return
        pushCallProactivePrefs(uid)
        val pending = CallSettingsStore.pendingProactivePush(graph.appContext, uid)
        val server = if (pending) null else runCatching { prefsClient.fetchCallProactivePrefs(uid) }.getOrNull()
        val local = CallSettingsStore.loadProactive(graph.appContext, uid)
        val next = CallProactiveSync.resolve(local, server, pending)
        if (next != local) CallSettingsStore.saveProactive(graph.appContext, uid, next)
        if (currentUid() == uid) _callProactive.value = next
    }

    /** "Not now" on the full-screen-calls nudge: never shown again on this
     *  install for this account (the status line keeps saying calls degrade). */
    fun dismissRingNudge() {
        _ringNudgeDismissed.value = true
        val uid = currentUid() ?: return
        runCatching { CallSettingsStore.setRingNudgeDismissed(graph.appContext, uid, true) }
    }

    /** The live call anchored to a task (the editor's initial state). Reads the
     *  server (`CallsClient.forTask`); null = none, signed out, or offline. The
     *  sync layer's read-only `call_requests` mirror can replace this read once it
     *  exposes a per-task query — the editor only needs the row. */
    suspend fun callForTask(taskId: String): CallRequest? {
        val client = assistantSupabaseClient ?: return null
        if (currentUid() == null) return null
        return runCatching { CallsClient(client, CallRequestsMirror(store)).forTask(taskId) }.getOrNull()
    }

    /** The live call anchored to [taskId] as the local `call_requests` mirror
     *  changes (hydrate, realtime, catch-up, a booking's own row) — what the
     *  task editor's "Call me about this" follows while it is open (parity with
     *  iOS build 72). */
    fun observeCallForTask(taskId: String): kotlinx.coroutines.flow.Flow<CallRequest?> =
        CallRequestsMirror(store).observe()
            .map { rows -> tech.csalliance.unstuck.ui.tasks.CallMeLogic.liveForTask(rows, taskId) }
            .distinctUntilChanged()

    /** The bell's "Unstuck called you about X" cards (notification_queue,
     *  moment `call`), matched to the local call mirror. Null = couldn't read
     *  (offline / signed out) — the bell keeps what it had (parity with iOS
     *  build 72). */
    suspend fun callQueueCards(): List<tech.csalliance.unstuck.surface.NotificationLog.Entry>? {
        val n = graph.coordinator?.notifications ?: return null
        if (currentUid() == null) return null
        val cards = runCatching { n.queueCards(tech.csalliance.unstuck.ui.notifications.NotificationQueueCards.CALL_MOMENT) }.getOrNull() ?: return null
        val calls = runCatching { CallRequestsMirror(store).all() }.getOrDefault(emptyList())
        return cards.map { tech.csalliance.unstuck.ui.notifications.NotificationQueueCards.entry(it, calls) }
    }

    /** One call row by id (after a book, to show what the server stored). */
    suspend fun callRequest(callId: String): CallRequest? =
        runCatching { assistantApi.callStore()?.call(callId) }.getOrNull()

    private suspend fun runEditorCallTool(name: String, args: JsonObject): String =
        runCatching { runAssistantTool(name, ToolArgs(args), assistantApi, TurnScratch()) }.getOrElse { CallToolLogic.NETWORK }

    private fun notesJson(notes: List<String>) = JsonArray(notes.map { JsonPrimitive(it) })

    /** "Call me about this" → request_call(taskId, leadMin, notes): rings `leadMin`
     *  minutes before the task's NEXT live block (the server follows the block). */
    suspend fun bookTaskCall(taskId: String, leadMin: Int, notes: List<String>): String =
        runEditorCallTool("request_call", buildJsonObject { put("taskId", taskId); put("leadMin", leadMin); put("notes", notesJson(notes)) })

    /** Update the task's call: update_call(callId, leadMin, notes) — re-anchors on
     *  the task's current next block, replaces the notes verbatim. */
    suspend fun updateTaskCall(callId: String, leadMin: Int, notes: List<String>): String =
        runEditorCallTool("update_call", buildJsonObject { put("callId", callId); put("leadMin", leadMin); put("notes", notesJson(notes)) })

    /** cancel_call(callId) — "error: that call is already <status>" when it rang /
     *  was cancelled elsewhere (gone either way for the editor). */
    suspend fun cancelCallRequest(callId: String): String =
        runEditorCallTool("cancel_call", buildJsonObject { put("callId", callId) })

    /** Settings → "Test call now": a REAL `call_requests` row of kind `test` one
     *  minute from now, so the whole server → FCM → ring path is exercised (a
     *  test call is never re-rung after a miss — the server's retry rule skips
     *  kind `test`). The assistant's guards apply (server window 06:00–23:00,
     *  past-time — CallToolLogic.timeGuard) PLUS the user's own allowed hours —
     *  otherwise the phone would decline it quietly and "Booked — ringing at …"
     *  would be a lie. Every live earlier test row (kind `test`, or the test
     *  label from an older build) is cancelled first: two would ring twice, and
     *  the one-live-call-per-label rule would refuse the retry. Returns the
     *  request_call contract string ("ok: call booked …" / "error: …"). */
    suspend fun bookTestCall(): String {
        val at = nowMs() + 60_000L
        val s = _callSettings.value
        val hm = CallToolLogic.hhmm(at)
        if (!CallSettingsLogic.withinHours(hm, s)) return TEST_CALL_OUTSIDE_HOURS(hm, s)
        if (!s.enabled) return TEST_CALL_CALLS_OFF
        val store = assistantApi.callStore() ?: return CallToolLogic.UNAVAILABLE
        val uid = assistantApi.currentUserId() ?: return CallToolLogic.UNAVAILABLE
        return try {
            CallToolLogic.timeGuard(at, assistantApi.todayIso(), assistantApi.nowHM(), assistantApi.getBlocks())?.let { return it }
            for (stale in TestCallLogic.previousTestCalls(store.liveCalls())) {
                runCatching { store.cancelCall(stale.id) }
            }
            val notes = listOf(TEST_CALL_NOTE)
            val row = store.book(uid, null, null, at, null, TEST_CALL_LABEL, notes, kind = TestCallLogic.KIND)
            "ok: call booked ${CallToolLogic.fmt(at)} \"$TEST_CALL_LABEL\" (${CallToolLogic.notesCount(notes.size)}) id=${row.id}"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            CallToolLogic.NETWORK
        }
    }

    // --- seams for the executor's AppViewModel adapter (AssistantToolsAppModel.kt) ---

    internal val assistantStore: tech.csalliance.unstuck.data.LocalStore get() = store
    internal val assistantWrite: tech.csalliance.unstuck.sync.WriteThrough? get() = write
    internal val assistantCircleClient get() = circleClient
    internal val assistantPreferencesClient get() = graph.coordinator?.preferences
    internal val assistantSupabaseClient get() = graph.provider?.client

    /** Stage an assistant-prepared share for the confirm card (never shares). */
    internal fun stagePendingShare(p: PendingShare) { _pendingShares.value = _pendingShares.value + p }

    /** The UI's un-complete hook: a loop-promoted shared-list task reopened →
     *  un-tick the collection row for the other members (best-effort). Launched,
     *  never awaited: the assistant's tools answer once the local write is saved —
     *  awaiting the edge function held a voice reply for up to the 90 s request
     *  timeout on a stalled network (parity with iOS build 81's fire-and-forget
     *  seam, audit 2026-09-22 C6). */
    internal fun notifyTaskReopenedIfShared(t: TaskItem) {
        if (t.sourceCollectionId != null && t.sourceItemId != null) {
            viewModelScope.launch { runCatching { share?.taskDone(t.sourceCollectionId!!, t.sourceItemId!!, t.name, currentName ?: "Someone", action = "reopen") } }
        }
    }

    /** The mirror image: a loop-promoted task that just went open → done ticks the
     *  collection row for the other members — what toggleDone and finishFocus send.
     *  The assistant's completions never sent it, so a voice completion left every
     *  member seeing the item open or overdue (parity with iOS build 81, audit
     *  2026-09-22 C6). Launched like the reopen hook above. */
    internal fun notifyTaskDoneIfShared(t: TaskItem) {
        if (t.sourceCollectionId != null && t.sourceItemId != null) {
            viewModelScope.launch { runCatching { share?.taskDone(t.sourceCollectionId!!, t.sourceItemId!!, t.name, currentName ?: "Someone", action = "done") } }
        }
    }

    /** A LOCAL focus control (pause / resume / extend) — the same transition the
     *  Focus screen runs, committed before returning. */
    internal suspend fun mutateLiveControl(transform: (LiveSession) -> LiveSession): Boolean = mutateLive(control = true, transform)

    /** Abandon the running focus session WITHOUT logging it (the assistant's
     *  cancel_focus). Nothing is written to Sessions / totalFocused; the timer
     *  service + paused check-in are torn down like the Focus screen's exit. */
    fun cancelFocus() = launchWrite { cancelFocusNow() }

    internal suspend fun cancelFocusNow(): Boolean {
        val cur = store.getLiveSession() ?: return false
        if (cur.sessionStart == null) return false
        store.setLiveSession(null)
        // No Session row: a capture taken during it would wait on one for ever (A14).
        releaseCapturesOf(cur.id)
        tearDownFocusSurfaces()
        _coFocusAttribution.value = null
        return true
    }

    /** The timer notification + the paused check-in the Focus screen tears down
     *  on its own exit — the assistant's cancel_focus / finish_focus (2026-09-20)
     *  end a session with no screen to do it. */
    internal fun tearDownFocusSurfaces() {
        val ctx = graph.appContext
        runCatching { tech.csalliance.unstuck.surface.FocusTimerService.stop(ctx) }
        runCatching { tech.csalliance.unstuck.surface.PausedCheckinScheduler.cancel(ctx) }
    }

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
        (auth?.updateDisplayName(name) ?: AuthOutcome.Error("Not configured"))
            // Nudge the reactive name so the Today greeting/avatar refresh at once
            // (supabase-kt re-emits sessionStatus on updateUser, but don't rely on it).
            .also { if (it is AuthOutcome.Ok) _nameRefresh.tryEmit(Unit) }
    // Route through the coordinator so the delete ALSO unregisters this device's push
    // token (+ always signs out even if the server invoke timed out post-deletion).
    // Falls back to AuthService when no coordinator is wired.
    suspend fun deleteAccount(): AuthOutcome =
        graph.coordinator?.deleteAccount() ?: auth?.deleteAccount() ?: AuthOutcome.Error("Not configured")
    // Default FALSE when auth isn't wired (mirrors AuthService.hasPassword) — never
    // offer "Change password" to a Google-only / not-yet-known account.
    val hasPassword: Boolean get() = auth?.hasPassword ?: false
    /** Writes that could not be pushed before the last sign-out (offline) and were
     *  PARKED for that account — they sync on its next sign-in, never discarded.
     *  Non-zero right after a sign-out; a surface may tell the user. */
    private val _parkedOnSignOut = MutableStateFlow(0)
    val parkedOnSignOut: StateFlow<Int> = _parkedOnSignOut.asStateFlow()

    // Unregister this device's push token (while the JWT is still valid) then
    // sign out — prevents the previous user's pushes reaching the next user.
    fun signOut() = launchWrite {
        // A running OWN focus session is finalized FIRST (Session row + totalFocused
        // into the outbox, which the coordinator's bounded drain lands — or parks under
        // this user for the next sign-in). The sign-out cache wipe (store.clearAll)
        // used to discard the live blob outright, losing the elapsed minutes. Partner
        // co-focus sessions are deliberately NOT ended here: the partner keeps the one
        // true session and finalizes it via the ledger (same session id), and a local
        // finalize would broadcast `ended` and cut them off.
        finalizeOwnLiveSessionForSignOut()
        val c = graph.coordinator
        if (c == null) { auth?.signOut(); return@launchWrite }
        _parkedOnSignOut.value = c.signOutAndUnregister()
    }

    private suspend fun finalizeOwnLiveSessionForSignOut() {
        val live = store.getLiveSession() ?: return
        if (live.sessionStart == null) return
        if (live.sharedTitle != null || accruesViaSharedLedger(live, live.taskId, shareBadges.value)) {
            // Left running for the partner, and this phone writes no Session row for it
            // (the live blob goes with the sign-out wipe): the captures queued behind it
            // go up without one rather than wait for ever (Android audit 2026-09-23, A14).
            runCatching { releaseCapturesOf(live.id) }
            return
        }
        runCatching { finalizeDisplaced(live) }
        store.setLiveSession(null)
        val ctx = graph.appContext
        runCatching { tech.csalliance.unstuck.surface.FocusTimerService.stop(ctx) }
        runCatching { tech.csalliance.unstuck.surface.PausedCheckinScheduler.cancel(ctx) }
    }

    /** Settings › Interface › "Clear Assistant history": the server-side delete
     *  of this user's stored conversations (`delete_my_assistant_turns`,
     *  migration 074) — the control the privacy policy promises. The rows
     *  deleted, or a failure (signed out, offline). Local chat threads are
     *  untouched: this is the copy the backend keeps (parity with iOS build 78,
     *  0f24908). */
    suspend fun clearAssistantHistory(): Result<Int> {
        val prefsClient = graph.coordinator?.preferences ?: return Result.failure(IllegalStateException("not signed in"))
        return runCatching { prefsClient.deleteAssistantHistory() }
    }

    /** Serialise every user-owned table into one JSON bundle (matches web exportAll).
     *  Every table is read from the store, off the main thread — never the
     *  WhileSubscribed flows, which read empty (or a stale snapshot) for any table no
     *  screen is collecting: lists and tags went out as [] under an "Exported."
     *  (Android audit 2026-09-23, A18). A table that can't be read, or rows of it that
     *  won't decode, are left out AND named — in the file (`incomplete`) and in
     *  [DataExport.missing], so the screen says so. */
    suspend fun exportJson(): DataExport = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()
        suspend fun <T> read(table: String, label: String, ser: kotlinx.serialization.KSerializer<T>): List<T> = try {
            // Counted in the same read: a pull deleting rows between a count and
            // the read made a complete table look short (Android audit 2026-09-23, A18).
            store.snapshotChecked(table, ser).also { if (it.undecodable > 0) missing += label }.rows
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("UnstuckExport", "couldn't read $table", e)
            missing += label
            emptyList()
        }
        val tasks = read(Tables.TASKS, "tasks", TaskItem.serializer())
        val sessions = read(Tables.SESSIONS, "focus sessions", Session.serializer())
        val calBlocks = read(Tables.CAL_BLOCKS, "calendar blocks", CalBlock.serializer())
        val captures = read(Tables.CAPTURES, "captures", Capture.serializer())
        val reasonLogs = read(Tables.REASON_LOGS, "stuck reasons", ReasonLog.serializer())
        val collections = read(Tables.COLLECTIONS, "lists", ItemCollection.serializer())
        val tags = read(Tables.TAGS, "tags", TagRow.serializer())
        val lifeAreas = read(Tables.LIFE_AREAS, "areas", LifeArea.serializer())
        val calendarConnections = read(Tables.CALENDAR_CONNECTIONS, "calendar connections", tech.csalliance.unstuck.core.model.CalendarConnection.serializer())
        // Forgotten facts stay in the store as tombstones; only what Settings › What
        // Unstuck knows shows goes out.
        val profileFacts = read(Tables.PROFILE_FACTS, "what Unstuck knows", ProfileFact.serializer()).filter { it.active }
        val callRequests = read(Tables.CALL_REQUESTS, "calls", CallRequest.serializer())
        val bundle = ExportBundle(
            exportedAt = isoNow(), email = currentEmail,
            tasks = tasks, sessions = sessions, calBlocks = calBlocks,
            captures = captures, reasonLogs = reasonLogs,
            collections = collections, tags = tags, lifeAreas = lifeAreas,
            calendarConnections = calendarConnections, profileFacts = profileFacts, callRequests = callRequests,
            incomplete = missing.toList(),
        )
        DataExport(EXPORT_JSON.encodeToString(bundle), missing.toList())
    }

    /** "Export everything" into [uri], the document the user just created. Runs on the
     *  ViewModel's scope, not the screen's: the picker stops the activity and the
     *  ON_STOP reset closes Settings, so a Settings coroutine would be cancelled before
     *  the file is written (Android audit 2026-09-23, A18). [onDone] gets, on the main
     *  thread, what to tell the user and whether it reports a failure. */
    fun exportTo(uri: android.net.Uri, onDone: (message: String, failed: Boolean) -> Unit) {
        viewModelScope.launch {
            val missing = try {
                val export = exportJson()
                withContext(Dispatchers.IO) {
                    (graph.appContext.contentResolver.openOutputStream(uri) ?: error("no output stream"))
                        .use { it.write(export.json.toByteArray()) }
                }
                export.missing
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("UnstuckExport", "export failed", e)
                onDone(EXPORT_FAILED, true)
                return@launch
            }
            onDone(exportOutcomeMessage(missing), missing.isNotEmpty())
        }
    }

    init {
        // Server-backed state that the engine can't own: after every completed pull,
        // reconcile the notification level / reminder lead, the capture archive and the
        // onboarding flag against the server (each best-effort + independent).
        graph.coordinator?.let { c ->
            viewModelScope.launch {
                // Two sources, one handler: after every completed pull, AND the
                // moment another device changes a settings row (user_preferences /
                // notification_preferences are live since migration 063 — before
                // this, a notification level picked on the web could not reach an
                // open phone at all).
                merge(c.hydrated, c.preferencesChanged).collect {
                    val uid = auth?.currentUserId ?: return@collect
                    // Duplicate Work / Personal / Home rows (and their refused writes)
                    // that onboarding on builds up to vc100 left behind (A8).
                    runCatching { tech.csalliance.unstuck.sync.RefusedLifeAreas.heal(store) }
                    runCatching { reconcileNotificationPrefs(uid) }
                    runCatching { reconcileCallProactivePrefs(uid) }
                    runCatching { reconcileCaptureArchive(uid) }
                    // The account's interview flag + rituals land BEFORE
                    // profileFactsHydrated flips: the gateway's auto-open gate
                    // decides on that flip, and an already-onboarded user (done on
                    // the web, few synced facts) must never be greeted as a stranger.
                    hydrateAssistantPrefs(uid)
                    runCatching { reconcileOnboarded(uid, afterPull = true) }
                }
            }
            // The recurrence horizon top-up (stage 2 — "same id for same day", Ahmad
            // 2026-09-23; parity with iOS build 85 and web): after every completed
            // pull, extend each repeating task's tail with its deterministic ids —
            // before this, a series last edited on Android ran out 8 weeks later. The
            // coordinator's gate decides whether this pull allows it (its cal_blocks
            // read succeeded and was complete, once per local day); launched, so a
            // run never holds up the reconciliations above.
            viewModelScope.launch {
                c.hydrated.collect { launch { runCatching { c.topUpRecurrenceHorizon() } } }
            }
        }
    }

    companion object {
        /** How long a not-yet-onboarded account waits on the splash for the server's
         *  answer before the local flag decides. The early read normally answers in one
         *  round trip; this bounds the wait when it failed and the first pull is the next
         *  chance (a whole pull — hence longer than iOS's 6 s). */
        internal const val ONBOARDING_RESOLVE_DEADLINE_MS = 10_000L
        private const val NOTIF_PREF_LEVEL = "level"
        private const val NOTIF_PREF_LEAD = "lead"
        /** Gateway interview keys (web STORAGE_KEYS.GATEWAY_INTERVIEW_DONE / _STEP vocabulary). */
        internal const val INTERVIEW_DONE_KEY = "unstuck-gateway-interview-done"
        internal const val INTERVIEW_STEP_KEY = "unstuck-gateway-interview-step"
        /** Per-account cache of `user_preferences.adhd_struggles` (canonical, newline-joined). */
        internal const val STRUGGLES_KEY = "unstuck-adhd-struggles"
        private val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        private val EXPORT_JSON = Json { prettyPrint = true; encodeDefaults = true }
        // Offline convergence (docs/shared-session-spec.md, amendments): how long a
        // DIVERGED client waits after its hello for a same-session answer before the
        // grace fallback fires, and how many re-hellos it sends while a focusing
        // peer is visibly present. Values shared 1:1 with web + iOS.
        private const val DIVERGENCE_REEXCHANGE_GRACE_MS = 5_000L
        private const val DIVERGENCE_GRACE_MAX_TRIES = 3
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
    // Every other table this device holds (Android audit 2026-09-23, A18).
    val calendarConnections: List<tech.csalliance.unstuck.core.model.CalendarConnection> = emptyList(),
    val profileFacts: List<ProfileFact> = emptyList(),
    val callRequests: List<CallRequest> = emptyList(),
    /** What couldn't be read and is missing from this file (empty = complete). */
    val incomplete: List<String> = emptyList(),
)

/** A built "Export everything" file and what, if anything, is missing from it. */
data class DataExport(val json: String, val missing: List<String>)

internal const val EXPORT_FAILED = "Export failed."

/** What "Export everything" tells the user — a missing part is named, never
 *  reported as a plain success (Android audit 2026-09-23, A18). */
internal fun exportOutcomeMessage(missing: List<String>): String =
    if (missing.isEmpty()) "Exported."
    else "Exported, but some data couldn't be read and isn't in the file: ${missing.joinToString(", ")}."

/** A just-finished focus session, surfaced as the Today recap card (B3).
 *  [endedBy] carries the partner's name when a REMOTE `ended` finalized a shared
 *  session, so the card can attribute it calmly ("<name> ended the session"). */
data class RecapState(val taskName: String, val focusedSec: Int, val at: Long = 0L, val endedBy: String? = null)

/** A message typed while a turn was in flight — queued, visible, sent when the reply lands. */
data class QueuedSend(val id: String, val text: String)

/** The debounced inputs for the home-screen Start-Next widget recomputation. */
private data class WidgetInputs(
    val tasks: List<TaskItem>,
    val blocks: List<CalBlock>,
    val liveTaskId: String?,
    val excludeIds: Set<String>,
)

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

/** The local turn a finished voice session's receipts land under (iOS + web
 *  use the same words). */
const val VOICE_SESSION_RECEIPTS = "While we talked:"

// ── "Test call now" (Settings → Calls) — copy from iOS CallSettingsView ──
/** The label of the row "Test call now" books (a live one is cancelled before a retry). */
const val TEST_CALL_LABEL = TestCallLogic.LABEL
const val TEST_CALL_NOTE = TestCallLogic.NOTE
/** Calls are switched off on this phone — the test would be declined quietly (iOS wording). */
const val TEST_CALL_CALLS_OFF = "error: calls are off on this phone — switch them on above to try it"
/** The user's own hours refuse the test — widen them (iOS wording). The end
 *  minute itself names the last one that rings (hoursLabel, audit 2026-09-22 C12). */
@Suppress("FunctionName")
fun TEST_CALL_OUTSIDE_HOURS(hm: String, s: CallSettings): String {
    // Contract-shaped 'HH:MM' like every other bookTestCall result: the card shows
    // it through CallMeLogic.userMessage, which puts its times the phone's way.
    val hours = CallSettingsLogic.hoursLabel(s.hoursStart, s.hoursEnd, CallSettingsLogic.minutesOfDay(hm) ?: -1, tech.csalliance.unstuck.core.time.ClockMode.H24)
    return "error: $hm is outside your allowed hours ($hours) — the phone would decline it quietly. Widen the hours above to try it now."
}
