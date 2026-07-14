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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import tech.csalliance.unstuck.core.logic.externalEventToBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import java.time.LocalDate

// SyncCoordinator — the orchestrator (port of bootstrap-listener.tsx). Observes
// auth state and drives the engine: on sign-in / initial-session / user-updated
// it applies the cache-wipe rule, flushes any offline outbox, hydrates
// server-canonical, then subscribes to realtime. On sign-out it tears down
// realtime + wipes the local cache. prevUserId (SharedPreferences) distinguishes
// a same-user reload from a user switch.

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
    val collectionShare = CollectionShareClient(client)
    val circle = CircleClient(client)
    val feedback = FeedbackClient(client)
    val assistant = AssistantClient(client)
    private val loginTracker = LoginTrackerClient(client)

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

    /** Full server-canonical pull, coalesced: at most one runs at a time. A caller
     *  arriving while a pull is in flight is DROPPED (coalesced to the running one —
     *  its fresh snapshot already reflects whatever prompted this call). The Mutex
     *  serializes the actual pull so no two overlap. Deadlock-free: the mutex is only
     *  ever held around [Hydrator.hydrate], never across an unrelated suspend. */
    private suspend fun coalescedHydrate(uid: String) {
        if (!hydrateInFlight.compareAndSet(false, true)) return
        try {
            hydrateMutex.withLock { hydrator.hydrate(uid) }
        } finally {
            hydrateInFlight.set(false)
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
    suspend fun signOutAndUnregister() {
        // Drain any queued offline writes BEFORE signing out — the NotAuthenticated
        // branch calls store.clearAll() which also wipes the outbox, so un-flushed
        // edits would be lost forever. Best-effort + bounded so a flaky network can't
        // hang sign-out.
        auth.currentUserId?.let { uid ->
            runCatching { kotlinx.coroutines.withTimeoutOrNull(5_000) { flusher.flush(uid) { auth.currentUserId } } }
        }
        runCatching { push.unregister(thisDeviceId()) }
        // Narrow the sign-out-vs-write race: a write landing AFTER the first flush
        // window (or during the push unregister) would otherwise be wiped by clearAll.
        // The JWT is still valid here, so a final bounded re-flush captures it. (Writes
        // after THIS flush but before clearAll remain a residual hair — acceptable.)
        auth.currentUserId?.let { uid ->
            runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { flusher.flush(uid) { auth.currentUserId } } }
        }
        auth.signOut()
    }

    /** Delete the account, then ALWAYS unregister this device's push token (while the
     *  JWT may still be valid) and sign out — even if the server invoke timed out after
     *  it already deleted the account. Otherwise a dead local session + a lingering
     *  push-token row survive (the previous owner's pushes could reach the next user).
     *  No outbox flush: the account is being destroyed, so queued writes are moot. */
    suspend fun deleteAccount(): AuthOutcome {
        val invoke = auth.deleteAccountInvoke()
        runCatching { push.unregister(thisDeviceId()) }
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
    suspend fun flushOutbox() {
        val uid = auth.currentUserId ?: return
        flusher.flush(uid) { auth.currentUserId }
    }

    /** Manual best-effort sync (flush outbox → hydrate) for the periodic
     *  WorkManager job. No-op when signed out. */
    suspend fun syncNow() {
        val uid = auth.currentUserId ?: return
        // Drop stale local task ops the server already superseded BEFORE flushing,
        // so a queued done=false can't clobber a completion made on another platform
        // (which the hydrate would then pull back). Then push + pull.
        hydrator.pruneStaleTaskOps()
        flusher.flush(uid) { auth.currentUserId }
        hydrator.hydrate(uid)
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
        // Flush first: hydrate replaces cal_blocks with (remote + localExternal), so an
        // unflushed local TASK block (in neither set) would vanish until the next sync.
        auth.currentUserId?.let { flusher.flush(it) { auth.currentUserId }; hydrator.hydrate(it) }   // pull the new calendar_connections row so the UI flips to "Synced" now, not on next launch
        pullCalendar()
        true
    }.getOrElse { Log.w(TAG, "calendar connect failed", it); false }

    /** Pull external events for [-7d, +30d] and reconcile them into local EXTERNAL blocks. */
    suspend fun pullCalendar() {
        auth.currentUserId ?: return
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
        val events = runCatching { calendar.pullEvents(fromIso, toIso) }
            .onFailure { Log.w(TAG, "calendar pullEvents failed", it) }.getOrNull() ?: return
        // Don't mirror events we pushed ourselves — the originating task block already
        // represents them (avoids a duplicate g_ block sitting next to the task block).
        val ownEventIds = store.blocks().first()
            .filter { it.kind == CalBlockKind.TASK && !it.externalEventId.isNullOrBlank() }
            .mapNotNull { it.externalEventId }.toSet()
        val blocks = events
            .filter { it.id !in ownEventIds }
            // Skip all-day events (date-only start, no 'T') — they'd collapse to 15-min
            // 00:00 slivers stacked on the time grid. (A proper all-day lane is a follow-up.)
            .filter { it.start.contains('T') }
            .map { externalEventToBlock(it, it.calendarId) }
        val keep = blocks.map { it.id }.toSet()
        blocks.forEach { write.upsertCalBlock(it) }
        // Reconcile deletions: drop in-window EXTERNAL blocks Google no longer returns.
        val fromYmd = fromDate.toString()
        val toYmd = toDate.toString()
        store.blocks().first()
            .filter { it.kind == CalBlockKind.EXTERNAL && it.date >= fromYmd && it.date <= toYmd && it.id !in keep }
            .forEach { write.deleteCalBlock(it.id) }
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

    /** PATCH if the block already has a Google event id, else INSERT and return
     *  the new id (which WriteThrough persists on the block). No-op without a
     *  Google connection or for non-task blocks. */
    suspend fun pushBlockUpsert(block: CalBlock): String? {
        if (block.kind != CalBlockKind.TASK) return null
        val conn = googleConn() ?: return null
        // Always write task blocks to the user's PRIMARY calendar — selectedCalendarIds
        // can include read-only/subscribed calendars (which 403 on insert). "primary" is
        // Google's alias for the main, always-writable calendar.
        val calId = "primary"
        val (start, end) = blockIsoRange(block)
        if (start.isEmpty()) return null
        val existing = block.externalEventId
        return if (!existing.isNullOrBlank()) {
            runCatching { calendar.patchEvent(existing, conn.id, calId, block.taskName, start, end) }
                .onFailure { Log.w(TAG, "calendar push patch failed", it) }
            existing
        } else {
            runCatching { calendar.insertEvent(conn.id, calId, block.taskName, start, end) }
                .onFailure { Log.w(TAG, "calendar push insert failed", it) }.getOrNull()
        }
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
                // Prune stale task ops (server already newer) so they can't clobber
                // another platform's change, then push offline edits, pull
                // server-canonical, and mirror live. Guard the drain on the LIVE user id
                // so a sign-out + switch mid-flush doesn't stamp ops with the prior user.
                // Wrap the whole body in runCatching: an uncaught throw here (transient
                // REST/decode error) would cancel observeJob, and start()'s
                // `if (observeJob != null) return` means sync would NEVER restart for the
                // rest of the process — a transient failure must not permanently kill sync.
                runCatching {
                    hydrator.pruneStaleTaskOps()
                    flusher.flush(uid) { auth.currentUserId }
                    coalescedHydrate(uid)
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
                }.onFailure { Log.w(TAG, "sync authenticated-branch step failed; sync stays alive", it) }
            }
            is SessionStatus.NotAuthenticated -> if (status.isSignOut) {
                stopForegroundNets()
                realtimeLifecycle.forceUnsubscribe()
                store.clearAll()
                prefs.edit().remove(KEY_PREV_USER).apply()
            }
            else -> {} // Initializing / RefreshFailure — no action
        }
    }

    companion object {
        private const val TAG = "UnstuckSync"
        private const val KEY_PREV_USER = "unstuck.prevUserId"
        // Foreground backstop pull cadence — cheap full hydrate; catches the
        // continuously-foregrounded case where no realtime socket event fires.
        private const val FOREGROUND_PULL_INTERVAL_MS = 60_000L
        // Google rejects custom schemes (unstuck://) on a Web OAuth client, so the
        // redirect_uri we hand Google is the HTTPS bounce page the web app serves
        // (the same one iOS uses). That page forwards ?code&state to
        // unstuck://calendar-callback, which MainActivity captures. This EXACT URL
        // must be registered as an Authorized redirect URI on the Google Web OAuth client.
        private const val CAL_REDIRECT = "https://unstuck-602.pages.dev/calendar-callback"
    }
}
