package tech.csalliance.unstuck.sync

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import tech.csalliance.unstuck.core.logic.externalEventToBlock
import tech.csalliance.unstuck.core.logic.incomingEventsToMirror
import tech.csalliance.unstuck.core.logic.staleExternalBlockIds
import tech.csalliance.unstuck.core.logic.wakeWindowSample
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import java.time.LocalDate

// SyncCoordinator — the orchestrator (port of bootstrap-listener.tsx). Observes
// auth state and drives the engine: on sign-in / initial-session / user-updated
// it applies the cache-wipe rule, restores any writes parked at an offline
// sign-out, flushes the outbox, hydrates server-canonical, then subscribes to
// realtime. On sign-out it drains (bounded), PARKS what couldn't land under the
// user id, tears down realtime + wipes the local cache. prevUserId
// (SharedPreferences) distinguishes a same-user reload from a user switch.
//
// Engine invariants (2026-09 sync sweep):
//  - every local write arms a debounced (~1.5 s) drain (flush-on-enqueue);
//  - every full pull is preceded by a drain and both run under one mutex, so a
//    pull can never overwrite an edit that is being pushed (push-then-pull);
//  - the Hydrator keeps every local row with a still-pending upsert, whether or
//    not the server also returned that id;
//  - a queued task edit that lost the LWW race is 3-way MERGED per field against
//    the newer server row, not dropped whole (Hydrator.pruneStaleTaskOps).

class SyncCoordinator(
    provider: SupabaseClientProvider,
    private val store: LocalStore,
    context: Context,
    private val scope: CoroutineScope,
) {
    private val client: SupabaseClient = provider.client
    private val gateway = SyncGateway(client)

    val auth = AuthService(client)
    val write = WriteThrough(store)
    val calendar = CalendarClient(client)
    val push = PushClient(client)
    val notifications = NotificationsClient(client)
    val preferences = PreferencesClient(client)
    val captures = CapturesClient(client)
    val collectionShare = CollectionShareClient(client)
    val circle = CircleClient(client)
    val feedback = FeedbackClient(client)
    val assistant = AssistantClient(client)
    private val loginTracker = LoginTrackerClient(client)
    private val wakeWindow = WakeWindowClient(client)

    private val hydrator = Hydrator(gateway, store)
    private val flusher = OutboxFlusher(gateway, store)
    // onChannelClosed: a single channel closing server-side while the socket stays
    // CONNECTED (so startHealthObserver never fires) — self-heal by rebuilding the
    // mirror + a coalesced backfill hydrate. See healClosedChannel (BUG 4).
    private val realtime = RealtimeMirror(client, store, scope, onChannelClosed = { healClosedChannel() })
    // Live change SIGNALS for sharing (RPC-backed surfaces can't be table-mirrored;
    // recipients have no RLS read on raw task rows). ViewModels observe
    // collab.sharesChanged / .circleChanged and refetch via `circle`.
    val collab = CollabRealtime(client, scope)
    // Co-focus / body-doubling presence (M5): opens per-task Realtime PRESENCE
    // channels (`cofocus:<taskId>`) on demand from Focus / the shared-with-you rows.
    val cofocus = CoFocusPresence(client, auth, scope)

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("unstuck.sync", Context.MODE_PRIVATE)
    private var observeJob: Job? = null
    // Foreground safety nets — started on foreground, cancelled on background.
    private var healthJob: Job? = null        // realtime socket-reconnect self-heal
    private var periodicPullJob: Job? = null  // ~60s backstop full pull
    // Serializes the realtime subscription lifecycle so the auth flow and the
    // foreground/background lifecycle can't race into the broken "foregrounded but
    // UNSUBSCRIBED with no refresh" state (the live-sync bug). Owns the thread-safe
    // subscribed flag; the injected callbacks are the real engine (un)subscribe +
    // hydrate calls. See RealtimeLifecycle.
    private val realtimeLifecycle = RealtimeLifecycle(
        scope = scope,
        hydrate = { auth.currentUserId?.let { coalescedHydrate(it) } },
        // Return TRUE only when a REAL subscribe happened (a user exists). A null
        // user (resume() racing the async session restore) must NOT leave the
        // lifecycle flag set, or the later SIGNED_IN would skip the real subscribe
        // and the session would have no live mirror (BUG 1).
        subscribe = { auth.currentUserId?.let { doSubscribeRealtime(it); true } ?: false },
        unsubscribe = { doUnsubscribeRealtime() },
        onError = { Log.w(TAG, "realtime lifecycle step failed; will retry", it) },
    )

    // --- Hydrate coalescer (BUG 3). The socket-reconnect heal, the ~60s periodic
    // pull, the resume hydrate, and the auth-branch hydrate all issue the same full
    // pull. Serialize them behind a Mutex + drop-if-in-flight coalescer so they can't
    // run concurrently (redundant full-table replaces + a TOCTOU window in the
    // non-transactional read-modify-write paths). ---
    private val hydrateMutex = Mutex()
    private val hydrateInFlight = AtomicBoolean(false)
    // Coalesces channel-close self-heals (BUG 4) so several channels dropping at
    // once collapse to a single mirror rebuild.
    private val healInFlight = AtomicBoolean(false)
    // Serializes every outbox DRAIN against every full PULL. A drain that dequeues an
    // op while a pull (whose server snapshot predates that push) is mid-replace would
    // let the stale server row overwrite the just-pushed edit until the next pull;
    // holding this across "flush, then hydrate" closes that window. Never re-entered:
    // the locked helpers below call only the *Unlocked variants.
    private val engineMutex = Mutex()

    /** Fires after every completed server-canonical pull. App-level reconciliations
     *  that need the server's word (the notification level / reminder lead, the
     *  capture archive, the onboarding flag) hang here rather than polling. */
    private val _hydrated = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hydrated: SharedFlow<Unit> = _hydrated

    /** Invoked (on the coordinator scope) right after the sign-out cache wipe, so the
     *  app layer can drop its own per-user device state in the same breath. */
    var onSignedOut: (() -> Unit)? = null

    /** A shared-collection item edit the server REFUSED (queued `rpc` op → 4xx). The
     *  optimistic local row has already been rolled back to the server's copy by the
     *  time this emits; the UI shows the message. */
    private val _collectionSyncErrors = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val collectionSyncErrors: SharedFlow<String> = _collectionSyncErrors

    // FLUSH-ON-ENQUEUE (the iOS `setOnEnqueue` seam). Every local write schedules a
    // debounced drain: a mid-session edit reaches the server within ~1.5 s instead of
    // waiting for the next auth event / the 30-min worker, and the foreground pulls
    // find an empty outbox. Cancelled at sign-out (the bounded drains take over).
    private val enqueueFlush = EnqueueFlushScheduler(scope, ENQUEUE_FLUSH_DEBOUNCE_MS, onError = { Log.w(TAG, "enqueue flush failed; will retry on the next write / pull", it) }) {
        flushNow()
    }

    /** Reconcile stale task ops against the server, then drain the outbox. The one
     *  drain primitive every path uses. No-op when signed out. */
    private suspend fun flushNow() {
        val uid = auth.currentUserId ?: return
        engineMutex.withLock { flushUnlocked(uid) }
    }

    private suspend fun flushUnlocked(uid: String) {
        // Drop / 3-way-merge local task ops the server already superseded BEFORE
        // pushing, so a queued done=false can't clobber a completion made on another
        // platform (which the following hydrate would then pull back).
        hydrator.pruneStaleTaskOps()
        flusher.flush(uid) { auth.currentUserId }
    }

    /** Full server-canonical pull, ALWAYS preceded by an outbox drain (push before
     *  pull — otherwise an unflushed edit is compared against, and for non-pending
     *  rows overwritten by, a stale server snapshot). Serialized behind
     *  [hydrateMutex] + [engineMutex] so no two overlap. Coalesced by default: a
     *  caller arriving while a pull is in flight is DROPPED (the running one's fresh
     *  snapshot already reflects whatever prompted this call). [waitIfBusy] callers
     *  (the auth branch, the worker, calendar connect — the ones whose caller expects
     *  a pull to have HAPPENED) queue behind the running pull instead. Deadlock-free:
     *  the mutexes are only ever held around the engine calls, never across an
     *  unrelated suspend. */
    private suspend fun coalescedHydrate(uid: String, waitIfBusy: Boolean = false) {
        val claimed = hydrateInFlight.compareAndSet(false, true)
        if (!claimed && !waitIfBusy) return
        try {
            hydrateMutex.withLock {
                engineMutex.withLock {
                    // A failed drain (offline) must not block the pull: the pending
                    // rows survive the replace regardless (Hydrator keeps them).
                    runCatching { flushUnlocked(uid) }
                        .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "pre-pull flush failed; pulling anyway", it) }
                    hydrator.hydrate(uid)
                }
            }
            _hydrated.tryEmit(Unit)
        } finally {
            if (claimed) hydrateInFlight.set(false)
        }
    }

    /** BUG-4 self-heal: rebuild the whole mirror (a fresh real subscribe) + a
     *  coalesced backfill hydrate after a channel closed server-side. Runs on its own
     *  coroutine so the resubscribe's teardown — which cancels the very status job
     *  that triggered this — can't cancel the heal itself. Coalesced via [healInFlight]. */
    private fun healClosedChannel() {
        val uid = auth.currentUserId ?: return
        if (!healInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                Log.i(TAG, "realtime channel closed while socket up — rebuilding mirror + backfilling")
                realtimeLifecycle.resubscribe()
                coalescedHydrate(uid)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "channel-close self-heal failed", t)
            } finally {
                healInFlight.set(false)
            }
        }
    }

    /** Sign out, first deleting this device's push-token row WHILE the JWT is
     *  still valid (RLS: user_id = auth.uid()). Stops the previous user's
     *  morning brief / pushes from reaching whoever signs in next on this
     *  device. The server single-owner pre-delete is the other half. */
    /** Returns how many queued writes could NOT be landed and were PARKED under this
     *  user (0 in the normal case) — the caller may tell the user they'll sync on
     *  their next sign-in. */
    suspend fun signOutAndUnregister(): Int {
        val uid = auth.currentUserId
        enqueueFlush.cancel()
        // Drain any queued offline writes BEFORE signing out — the NotAuthenticated
        // branch calls store.clearAll() which also wipes the outbox. Best-effort +
        // bounded so a flaky network can't hang sign-out.
        if (uid != null) {
            runCatching { kotlinx.coroutines.withTimeoutOrNull(5_000) { engineMutex.withLock { flushUnlocked(uid) } } }
        }
        runCatching { push.unregister(thisDeviceId()) }
        // Narrow the sign-out-vs-write race: a write landing AFTER the first flush
        // window (or during the push unregister) would otherwise be wiped by clearAll.
        // The JWT is still valid here, so a final bounded re-flush captures it.
        if (uid != null) {
            runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { engineMutex.withLock { flushUnlocked(uid) } } }
        }
        // Whatever the bounded drains could NOT land (offline sign-out) is PARKED
        // under this user id: the wipe below never touches the parking lot, and the
        // same user's next sign-in re-queues it ahead of the first flush. An un-pushed
        // edit is never silently discarded. (A write racing between THIS park and
        // clearAll remains a residual hair — acceptable.)
        // Park under the engine mutex when it can be had quickly (so a drain still in
        // flight can't dequeue an op we're moving); a wedged drain must not block
        // sign-out, so after a short wait park anyway (a doubly-pushed upsert is
        // idempotent server-side — a lost one is not).
        val parked = if (uid != null) {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(2_000) { engineMutex.withLock { store.parkOutbox(uid) } }
                    ?: store.parkOutbox(uid)
            }.getOrDefault(0)
        } else 0
        if (parked > 0) Log.w(TAG, "sign-out with $parked un-pushed writes — parked for $uid until next sign-in")
        auth.signOut()
        return parked
    }

    /** Delete the account, then ALWAYS unregister this device's push token (while the
     *  JWT may still be valid) and sign out — even if the server invoke timed out after
     *  it already deleted the account. Otherwise a dead local session + a lingering
     *  push-token row survive (the previous owner's pushes could reach the next user).
     *  No outbox flush: the account is being destroyed, so queued writes are moot —
     *  including any parked from an earlier offline sign-out. */
    suspend fun deleteAccount(): AuthOutcome {
        enqueueFlush.cancel()
        val uid = auth.currentUserId
        val invoke = auth.deleteAccountInvoke()
        runCatching { push.unregister(thisDeviceId()) }
        if (uid != null) runCatching { store.clearParked(uid) }
        auth.signOut()
        return invoke
    }

    private fun thisDeviceId(): String =
        android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android-device"

    /** Best-effort sign-in analytics — at most once per 12h per user (the server
     *  derives country/city from IP). Never blocks or fails sign-in. */
    private suspend fun maybeTrackLogin(uid: String) {
        val now = System.currentTimeMillis()
        val key = "loginPing.$uid"
        if (now - prefs.getLong(key, 0L) < 12 * 60 * 60 * 1000L) return
        prefs.edit().putLong(key, now).apply()
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        loginTracker.track(device)
    }

    init {
        // Two-way Google sync: WriteThrough fires these when a task block is
        // written/removed so the change mirrors to Google (best-effort).
        write.pushCalBlock = { pushBlockUpsert(it) }
        write.pushCalBlockDelete = { pushBlockDelete(it) }
        // Flush-on-enqueue: every queued op arms the debounced drain.
        write.onEnqueue = { enqueueFlush.schedule() }
        // A refused shared-collection RPC: ROLL BACK the optimistic row by re-pulling
        // the collection from the server (its copy never had the edit), then surface
        // it. The op itself is already dequeued (terminal).
        flusher.onRpcRejected = { op, err ->
            auth.currentUserId?.let { uid -> runCatching { hydrator.hydrateCollections(uid) } }
            Log.w(TAG, "shared-list edit refused for ${op.recordId}: ${err.message}")
            _collectionSyncErrors.tryEmit(
                if (err.status == 403 || err.status == 401) "That change wasn't saved — you no longer have access to this list."
                else "That change wasn't saved — the list may have been removed or changed.",
            )
        }
    }

    fun start() {
        if (observeJob != null) return
        observeJob = scope.launch {
            client.auth.sessionStatus.collect { handle(it) }
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
    }

    /** Build the realtime mirror for [uid]. Idempotent: subscribeAll() tears down
     *  any existing channels first, so a repeat call cleanly rebuilds (no double-
     *  subscribe / channel leak). Invoked only via RealtimeLifecycle (serialized). */
    private suspend fun doSubscribeRealtime(uid: String) {
        realtime.subscribeAll(uid) { hydrator.hydrateCollections(uid) }
        collab.subscribe()   // sharing change-signals (task_shares + trusted_circle)
    }

    /** Drop the realtime channels + websocket. Invoked only via RealtimeLifecycle. */
    private suspend fun doUnsubscribeRealtime() {
        realtime.unsubscribeAll()
        collab.unsubscribe()
    }

    /** Drop the realtime channels + websocket while the app is backgrounded — they
     *  otherwise stay subscribed (and the socket alive) indefinitely. The auth
     *  observer keeps running; a missed change is caught by the next hydrate on
     *  resume / the periodic SyncWorker. Also stops the foreground safety nets. */
    fun pauseRealtime() {
        stopForegroundNets()
        realtimeLifecycle.pause()
    }

    /** Foreground: ALWAYS re-hydrate (pull anything missed while backgrounded) AND
     *  ensure the live mirror is subscribed — never early-returns on a stale flag,
     *  and serialized against pause so a quick background→foreground can't settle
     *  unsubscribed with no refresh. Also (re)arms the foreground safety nets: the
     *  socket-reconnect self-heal + the ~60s backstop pull. */
    fun resumeRealtime() {
        realtimeLifecycle.resume()
        startForegroundNets()
        auth.currentUserId?.let { uid -> scope.launch { maybeRecordWakeWindow(uid) } }
    }

    /** Wake-window calibration: the FIRST foreground of each local day writes one
     *  wake_window_history row (upsert on (user_id, local_date), first-sample-wins).
     *  Throttled per user + day in prefs so it's one cheap write a day; best-effort
     *  (a failed write retries on the next foreground the same day). Nothing wrote
     *  this table before, so the morning brief's auto mode was pinned to 08:00. */
    internal suspend fun maybeRecordWakeWindow(uid: String, nowMs: Long = System.currentTimeMillis()) {
        val sample = wakeWindowSample(nowMs, java.time.ZoneId.systemDefault())
        val key = "wakeWindow.$uid"
        if (prefs.getString(key, null) == sample.localDate) return
        runCatching { wakeWindow.record(uid, sample) }
            .onSuccess { prefs.edit().putString(key, sample.localDate).apply() }
            .onFailure { Log.w(TAG, "wake_window_history write failed; will retry on next foreground", it) }
    }

    // --- Foreground safety nets: only run while the app is foregrounded. ---

    private fun startForegroundNets() {
        startHealthObserver()
        startPeriodicPull()
    }

    private fun stopForegroundNets() {
        healthJob?.cancel(); healthJob = null
        periodicPullJob?.cancel(); periodicPullJob = null
    }

    /** REALTIME SELF-HEAL. Watch the socket status; when it RE-connects after a
     *  drop (a network blip / doze), supabase-kt auto-rejoins the channels but any
     *  change made while offline was missed — so pull server-canonical to backfill.
     *  The first CONNECTED after (re)start is our own intentional subscribe and is
     *  ignored (wasConnected starts false). Restarted fresh on each foreground. */
    private fun startHealthObserver() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            var wasConnected = false
            client.realtime.status.collect { status ->
                Log.d(TAG, "realtime socket status: $status")
                if (status == Realtime.Status.CONNECTED) {
                    if (wasConnected) {
                        auth.currentUserId?.let { uid ->
                            Log.i(TAG, "realtime reconnected after a drop — backfilling via hydrate")
                            runCatching { coalescedHydrate(uid) }
                                .onFailure { Log.w(TAG, "post-reconnect hydrate failed", it) }
                        }
                    }
                    wasConnected = true
                }
            }
        }
    }

    /** FOREGROUND SAFETY-NET PULL. A lightweight periodic full pull (~60s) as a
     *  backstop for the continuously-foregrounded case (user watching this device
     *  while editing on another) where no socket event ever fires. It's the same
     *  full hydrate — cheap at this cadence. Cancelled on background. */
    private fun startPeriodicPull() {
        if (periodicPullJob?.isActive == true) return
        periodicPullJob = scope.launch {
            while (isActive) {
                delay(FOREGROUND_PULL_INTERVAL_MS)
                val uid = auth.currentUserId ?: continue
                runCatching { coalescedHydrate(uid) }
                    .onFailure { Log.w(TAG, "foreground safety-net pull failed", it) }
            }
        }
    }

    /** Re-pull collections + membership (after the owner shares/unshares — their
     *  own collection_members realtime channel doesn't fire for a member's row). */
    suspend fun refreshCollections() {
        auth.currentUserId?.let { hydrator.hydrateCollections(it) }
    }

    /** Push queued writes to the server NOW (outbox drain only, no hydrate). Used to
     *  land a just-created task row before a task_share RPC that validates ownership
     *  server-side — otherwise the share races the not-yet-flushed insert and the
     *  server raises not_your_task (T2). No-op when signed out. */
    suspend fun flushOutbox() = flushNow()

    /** Manual best-effort sync (flush outbox → hydrate) for the periodic
     *  WorkManager job. Goes through the same serialized push-then-pull as every
     *  other hydrate path (waits for an in-flight pull rather than racing it). No-op
     *  when signed out. */
    suspend fun syncNow() {
        val uid = auth.currentUserId ?: return
        coalescedHydrate(uid, waitIfBusy = true)
        runCatching { pullCalendar() }
    }

    // --- Google Calendar (consent + pull). Push of local blocks is a later step. ---

    private var pendingCalState: String? = null

    /** Start the OAuth consent: returns the Google authorize URL to open in a Custom Tab. */
    suspend fun beginGoogleConnect(): String? = runCatching {
        val r = calendar.authorize(CAL_REDIRECT)
        pendingCalState = r.state
        r.url
    }.onFailure { Log.w(TAG, "calendar authorize failed", it) }.getOrNull()

    /** Finish consent from the `unstuck://calendar-callback?code&state` deep link. */
    suspend fun completeGoogleConnect(code: String, state: String): Boolean = runCatching {
        // CSRF guard: only honor a callback when WE initiated the consent flow AND
        // the returned state matches the one minted for it. A null pendingCalState
        // means no connect is in flight — an unsolicited deep link (the callback is
        // BROWSABLE, reachable from any web page/app) must be rejected, not processed.
        val expected = pendingCalState
        if (expected == null || expected != state) {
            Log.w(TAG, "calendar connect: no pending consent or state mismatch — ignoring callback")
            return false
        }
        pendingCalState = null   // single-use: a replayed deep link can't be honored twice
        calendar.connectGoogle(code, CAL_REDIRECT, state)
        // Push-then-pull through the serialized path: pulls the new calendar_connections
        // row so the UI flips to "Synced" now, not on next launch (and never races the
        // foreground pull into a double replace).
        auth.currentUserId?.let { coalescedHydrate(it, waitIfBusy = true) }
        pullCalendar()
        true
    }.getOrElse { Log.w(TAG, "calendar connect failed", it); false }

    // 429 back-off: no pulls until this instant (the provider / function rate-limited us).
    @Volatile private var calendarBackoffUntilMs: Long = 0L

    /** Pull external events for [-7d, +30d] and reconcile them into local EXTERNAL blocks.
     *  The server reports per-connection FAILURES (`failures[]`, contract 2026-09) and
     *  marks a connection `needs_reauth` on 401 / invalid_grant: a failed connection's
     *  missing events are "unknown", never "deleted" — its blocks are kept — and on
     *  429 we back off. (Before, a lapsed refresh token came back as `events: []` and
     *  every meeting was reconciled away.) */
    suspend fun pullCalendar() {
        auth.currentUserId ?: return
        if (System.currentTimeMillis() < calendarBackoffUntilMs) {
            Log.i(TAG, "calendar pull skipped — backing off after a 429")
            return
        }
        val conns = runCatching { calendar.listConnections() }
            .onFailure { Log.w(TAG, "calendar listConnections failed", it) }.getOrNull() ?: return
        if (conns.isEmpty()) return
        val today = LocalDate.now()
        val fromDate = today.minusDays(7)
        val toDate = today.plusDays(30)
        // Google's events.list requires RFC3339 timestamps for timeMin/timeMax — a bare
        // YYYY-MM-DD is rejected (400) and silently yields zero events. Send full instants
        // (like the web's .toISOString()); reconcile locally with the date-only bounds.
        val zone = java.time.ZoneId.systemDefault()
        val fromIso = fromDate.atStartOfDay(zone).toInstant().toString()
        val toIso = toDate.plusDays(1).atStartOfDay(zone).toInstant().toString()
        val resp = try {
            calendar.pullEvents(fromIso, toIso)
        } catch (e: CalendarRateLimited) {
            backOffCalendar(); return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "calendar pullEvents failed", e); return
        }
        val failedConnIds = resp.failures.map { it.connectionId }.toSet()
        if (resp.failures.any { it.status == 429 }) backOffCalendar()
        if (resp.failures.isNotEmpty()) {
            Log.w(TAG, "calendar pull: ${resp.failures.size} connection(s) failed — keeping their blocks: ${resp.failures}")
            // Surface needs_reauth NOW (the bar offers "Reconnect Google") rather than
            // on the next full hydrate: re-read the connection rows the server just stamped.
            runCatching { calendar.listConnections() }.getOrNull()?.forEach { c ->
                store.upsert(Tables.CALENDAR_CONNECTIONS, c, CalendarConnection.serializer(), c.id, c.connectedAt)
            }
        }
        // Don't mirror events we pushed ourselves (the originating task block already
        // represents them) nor all-day events (no lane on the time grid yet). The
        // server stamps `allDay: true` — the old `contains('T')` check alone was dead
        // because the server normalises all-day starts to an ISO instant.
        val ownEventIds = store.blocks().first()
            .filter { it.kind == CalBlockKind.TASK && !it.externalEventId.isNullOrBlank() }
            .mapNotNull { it.externalEventId }.toSet()
        val blocks = incomingEventsToMirror(resp.events, ownEventIds).map { externalEventToBlock(it, it.calendarId) }
        val keep = blocks.map { it.id }.toSet()
        blocks.forEach { write.upsertCalBlock(it) }
        // Reconcile deletions: drop in-window EXTERNAL blocks Google no longer returns —
        // EXCEPT for connections whose fetch failed (unknown ≠ deleted).
        staleExternalBlockIds(store.blocks().first(), keep, fromDate.toString(), toDate.toString(), failedConnIds)
            .forEach { write.deleteCalBlock(it) }
    }

    private fun backOffCalendar() {
        calendarBackoffUntilMs = System.currentTimeMillis() + CALENDAR_BACKOFF_MS
        Log.w(TAG, "calendar rate-limited (429) — backing off ${CALENDAR_BACKOFF_MS / 1000}s")
    }

    /** Disconnect an account and immediately purge its connection row + external blocks. */
    suspend fun disconnectCalendar(connectionId: String) {
        runCatching { calendar.disconnect(connectionId) }
            .onFailure { Log.w(TAG, "calendar disconnect failed", it) }
        // Drop the connection row locally so the bar flips back to "Connect" now
        // (the server row is gone; a later hydrate would reach the same state).
        store.delete(Tables.CALENDAR_CONNECTIONS, connectionId)
        store.blocks().first()
            .filter { it.kind == CalBlockKind.EXTERNAL && it.externalConnectionId == connectionId }
            .forEach { write.deleteCalBlock(it.id) }
    }

    // --- Push (Unstuck → Google). Best-effort; mirrors web lib/sync/google-sync. ---

    private suspend fun googleConn(): CalendarConnection? =
        store.connections().first().firstOrNull { it.provider == CalendarProvider.GOOGLE }

    /** A task block's date+HH:MM → UTC ISO start/end (anchored in the device's
     *  local zone, like the web's new Date(...).toISOString()). */
    private fun blockIsoRange(b: CalBlock): Pair<String, String> {
        val d = b.date.split("-").mapNotNull { it.toIntOrNull() }
        val t = b.startTime.split(":").mapNotNull { it.toIntOrNull() }
        if (d.size != 3 || t.size < 2) return "" to ""
        // Clamp clock components + guard the date: a corrupt "24:00" / month 13 would
        // otherwise throw an uncaught DateTimeException on the Google push path.
        val hour = t[0].coerceIn(0, 23)
        val minute = t[1].coerceIn(0, 59)
        val start = runCatching {
            LocalDate.of(d[0], d[1], d[2]).atTime(hour, minute).atZone(java.time.ZoneId.systemDefault())
        }.getOrNull() ?: return "" to ""
        val end = start.plusMinutes(b.durationMinutes.coerceAtLeast(1).toLong())
        val fmt = java.time.format.DateTimeFormatter.ISO_INSTANT
        return fmt.format(start.toInstant()) to fmt.format(end.toInstant())
    }

    /** PATCH if the block already has a Google event id, else INSERT. Returns the block
     *  RE-STAMPED with the mapping (external_event_id + external_connection_id) when it
     *  changed, else null. The connection id is stamped on every INSERT (contract
     *  2026-09): the server's /disconnect cleanup and event_gone nulling select pushed
     *  rows by it, so an unstamped block was invisible to them (a reconnect then pulled
     *  every pushed block back as a duplicate `g_` meeting). A PATCH that comes back
     *  404 / `event_gone` (deleted in Google, removed at disconnect) clears the stale id
     *  and falls through to INSERT instead of patching a ghost forever. No-op without a
     *  Google connection, for non-task blocks, or while the connection needs re-auth. */
    suspend fun pushBlockUpsert(block: CalBlock): CalBlock? {
        if (block.kind != CalBlockKind.TASK) return null
        val conn = googleConn() ?: return null
        if (conn.needsReauth) return null   // every call would 401 until the user reconnects
        if (System.currentTimeMillis() < calendarBackoffUntilMs) return null
        // Always write task blocks to the user's PRIMARY calendar — selectedCalendarIds
        // can include read-only/subscribed calendars (which 403 on insert). "primary" is
        // Google's alias for the main, always-writable calendar.
        val calId = "primary"
        val (start, end) = blockIsoRange(block)
        if (start.isEmpty()) return null
        val existing = block.externalEventId
        if (!existing.isNullOrBlank()) {
            try {
                calendar.patchEvent(existing, conn.id, calId, block.taskName, start, end)
                // Backfill the connection stamp on a block pushed by an older build.
                return if (block.externalConnectionId != conn.id) block.copy(externalConnectionId = conn.id) else null
            } catch (e: CalendarEventGone) {
                Log.i(TAG, "calendar push: event $existing is gone — re-inserting")
                // fall through to INSERT below
            } catch (e: CalendarRateLimited) {
                backOffCalendar(); return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "calendar push patch failed", e); return null
            }
        }
        val id = runCatching { calendar.insertEvent(conn.id, calId, block.taskName, start, end) }
            .onFailure { Log.w(TAG, "calendar push insert failed", it) }.getOrNull() ?: return null
        return block.copy(externalEventId = id, externalConnectionId = conn.id)
    }

    /** Delete the Google event a locally-removed task block mapped to. */
    suspend fun pushBlockDelete(block: CalBlock) {
        val eventId = block.externalEventId?.takeIf { it.isNotBlank() } ?: return
        if (block.kind == CalBlockKind.EXTERNAL) return
        val conn = googleConn() ?: return
        val calId = "primary"   // task blocks are inserted on "primary" — patch/delete there too
        runCatching { calendar.deleteEvent(eventId, conn.id, calId) }
            .onFailure { Log.w(TAG, "calendar push delete failed", it) }
    }

    private suspend fun handle(status: SessionStatus) {
        when (status) {
            is SessionStatus.Authenticated -> {
                val uid = status.session.user?.id ?: return
                val event = when (status.source) {
                    is SessionSource.SignIn, is SessionSource.SignUp, is SessionSource.External -> SyncAuthEvent.SIGNED_IN
                    is SessionSource.Storage -> SyncAuthEvent.INITIAL_SESSION
                    is SessionSource.UserChanged, is SessionSource.UserIdentitiesChanged -> SyncAuthEvent.USER_UPDATED
                    else -> return // Refresh / Unknown — no cache action
                }
                val prev = prefs.getString(KEY_PREV_USER, null)
                if (SyncDecision.shouldWipeCache(event, prev, uid)) store.clearAll()
                prefs.edit().putString(KEY_PREV_USER, uid).apply()
                // Push offline edits (stale task ops pruned / merged first so they can't
                // clobber another platform's change), pull server-canonical, and mirror
                // live — all through the one serialized push-then-pull path, WAITING
                // for any in-flight pull (a sign-in must actually land + pull). The
                // drain is guarded on the LIVE user id so a sign-out + switch mid-flush
                // doesn't stamp ops with the prior user.
                // Wrap the whole body in runCatching: an uncaught throw here (transient
                // REST/decode error) would cancel observeJob, and start()'s
                // `if (observeJob != null) return` means sync would NEVER restart for the
                // rest of the process — a transient failure must not permanently kill sync.
                runCatching {
                    // Writes parked at an earlier OFFLINE sign-out of THIS user come back
                    // first, ahead of the first drain. (Another account's stay parked.)
                    val restored = runCatching { store.restoreParkedOutbox(uid) }.getOrDefault(0)
                    if (restored > 0) Log.i(TAG, "restored $restored parked writes for $uid")
                    coalescedHydrate(uid, waitIfBusy = true)
                    // Parked child ops (a cal_block whose parent task row was wiped with
                    // the cache) are held by the flusher until the parent exists locally —
                    // it does now, after the pull — so drain once more.
                    if (restored > 0) runCatching { flushNow() }
                    // A user now definitively exists. On sign-in / initial session force
                    // a fresh REAL (re)subscribe regardless of the lifecycle flag — a
                    // resume() that ran before the session was restored may have flipped
                    // the flag without creating channels (BUG 1). USER_UPDATED (same user,
                    // metadata) just ensures the mirror is up (idempotent).
                    if (event == SyncAuthEvent.SIGNED_IN || event == SyncAuthEvent.INITIAL_SESSION) {
                        realtimeLifecycle.resubscribe()
                    } else {
                        realtimeLifecycle.ensureSubscribed()   // idempotent + serialized vs pause/resume
                    }
                    // Re-arm the foreground safety nets. A sign-out→sign-in WITHIN one
                    // continuous foreground cancels them via stopForegroundNets() and
                    // would otherwise never restart them until the next onStart (BUG 2).
                    // Idempotent (isActive guards), so redundant with resumeRealtime's call.
                    startForegroundNets()
                    runCatching { pullCalendar() }   // ingest Google events if connected
                    maybeTrackLogin(uid)             // best-effort usage analytics (throttled)
                    maybeRecordWakeWindow(uid)       // first input of the local day → wake-window calibration
                }.onFailure { Log.w(TAG, "sync authenticated-branch step failed; sync stays alive", it) }
            }
            is SessionStatus.NotAuthenticated -> if (status.isSignOut) {
                enqueueFlush.cancel()
                stopForegroundNets()
                realtimeLifecycle.forceUnsubscribe()
                store.clearAll()   // leaves parked_outbox alone (per-user, see LocalStore)
                prefs.edit().remove(KEY_PREV_USER).apply()
                runCatching { onSignedOut?.invoke() }.onFailure { Log.w(TAG, "onSignedOut hook failed", it) }
            }
            else -> {} // Initializing / RefreshFailure — no action
        }
    }

    companion object {
        private const val TAG = "UnstuckSync"
        private const val KEY_PREV_USER = "unstuck.prevUserId"
        // After a 429 from the calendar function / provider, no pulls or pushes for this long.
        private const val CALENDAR_BACKOFF_MS = 5 * 60_000L
        // Foreground backstop pull cadence — cheap full hydrate; catches the
        // continuously-foregrounded case where no realtime socket event fires.
        private const val FOREGROUND_PULL_INTERVAL_MS = 60_000L
        // Flush-on-enqueue debounce: a burst of edits (typing, a drag) collapses to one
        // drain ~1.5 s after the last one (same window as iOS).
        internal const val ENQUEUE_FLUSH_DEBOUNCE_MS = 1_500L
        // Google rejects custom schemes (unstuck://) on a Web OAuth client, so the
        // redirect_uri we hand Google is the HTTPS bounce page the web app serves
        // (the same one iOS uses). That page forwards ?code&state to
        // unstuck://calendar-callback, which MainActivity captures. This EXACT URL
        // must be registered as an Authorized redirect URI on the Google Web OAuth client.
        private const val CAL_REDIRECT = "https://unstuck-602.pages.dev/calendar-callback"
    }
}

/**
 * Debounced flush-on-enqueue. [schedule] (called on EVERY outbox enqueue) arms one
 * [action] run [debounceMs] after the LAST call — a burst of edits collapses to a
 * single drain. A schedule() that lands while the action is RUNNING never cancels
 * it (an aborted drain would burn a round-trip and re-push later); it marks a
 * re-run, so the ops queued during the drain go out right after, again debounced.
 * [cancel] drops an armed (not-yet-running) run — sign-out. Pure orchestration,
 * unit-tested without Supabase/Android.
 */
internal class EnqueueFlushScheduler(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val onError: (Throwable) -> Unit = {},
    private val action: suspend () -> Unit,
) {
    private val lock = Any()
    private var armed: Job? = null      // the delay-phase job (cancellable)
    private var running = false
    private var rerun = false

    fun schedule() {
        synchronized(lock) {
            if (running) { rerun = true; return }
            armed?.cancel()
            armed = scope.launch {
                delay(debounceMs)
                run()
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            armed?.cancel(); armed = null
            rerun = false
        }
    }

    private suspend fun run() {
        synchronized(lock) { running = true; rerun = false; armed = null }
        try {
            while (true) {
                try {
                    action()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    onError(t)
                }
                val again = synchronized(lock) { rerun.also { rerun = false } }
                if (!again) break
                delay(debounceMs)   // coalesce whatever queued while we drained
            }
        } finally {
            synchronized(lock) { running = false }
        }
    }
}
