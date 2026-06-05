package tech.csalliance.unstuck.sync

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    val feedback = FeedbackClient(client)
    private val loginTracker = LoginTrackerClient(client)

    private val hydrator = Hydrator(gateway, store)
    private val flusher = OutboxFlusher(gateway, store)
    private val realtime = RealtimeMirror(client, store, scope)

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("unstuck.sync", Context.MODE_PRIVATE)
    private var observeJob: Job? = null

    /** Sign out, first deleting this device's push-token row WHILE the JWT is
     *  still valid (RLS: user_id = auth.uid()). Stops the previous user's
     *  morning brief / pushes from reaching whoever signs in next on this
     *  device. The server single-owner pre-delete is the other half. */
    suspend fun signOutAndUnregister() {
        runCatching { push.unregister(thisDeviceId()) }
        auth.signOut()
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

    /** Re-pull collections + membership (after the owner shares/unshares — their
     *  own collection_members realtime channel doesn't fire for a member's row). */
    suspend fun refreshCollections() {
        auth.currentUserId?.let { hydrator.hydrateCollections(it) }
    }

    /** Manual best-effort sync (flush outbox → hydrate) for the periodic
     *  WorkManager job. No-op when signed out. */
    suspend fun syncNow() {
        val uid = auth.currentUserId ?: return
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
        val expected = pendingCalState
        if (expected != null && expected != state) {                 // CSRF guard
            Log.w(TAG, "calendar connect: state mismatch — ignoring callback")
            return false
        }
        calendar.connectGoogle(code, CAL_REDIRECT, state)
        pendingCalState = null
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
        val blocks = events.filter { it.id !in ownEventIds }.map { externalEventToBlock(it, it.calendarId) }
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
        val start = LocalDate.of(d[0], d[1], d[2]).atTime(t[0], t[1]).atZone(java.time.ZoneId.systemDefault())
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
                // Push offline edits first, then pull server-canonical, then mirror live.
                // Guard the drain on the LIVE user id so a sign-out + switch mid-flush
                // doesn't keep stamping queued ops with the prior user.
                flusher.flush(uid) { auth.currentUserId }
                hydrator.hydrate(uid)
                realtime.subscribeAll(uid) { hydrator.hydrateCollections(uid) }
                runCatching { pullCalendar() }   // ingest Google events if connected
                maybeTrackLogin(uid)             // best-effort usage analytics (throttled)
            }
            is SessionStatus.NotAuthenticated -> if (status.isSignOut) {
                realtime.unsubscribeAll()
                store.clearAll()
                prefs.edit().remove(KEY_PREV_USER).apply()
            }
            else -> {} // Initializing / RefreshFailure — no action
        }
    }

    companion object {
        private const val TAG = "UnstuckSync"
        private const val KEY_PREV_USER = "unstuck.prevUserId"
        // Google rejects custom schemes (unstuck://) on a Web OAuth client, so the
        // redirect_uri we hand Google is the HTTPS bounce page the web app serves
        // (the same one iOS uses). That page forwards ?code&state to
        // unstuck://calendar-callback, which MainActivity captures. This EXACT URL
        // must be registered as an Authorized redirect URI on the Google Web OAuth client.
        private const val CAL_REDIRECT = "https://unstuck-602.pages.dev/calendar-callback"
    }
}
