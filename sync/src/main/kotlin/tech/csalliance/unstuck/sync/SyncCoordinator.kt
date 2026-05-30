package tech.csalliance.unstuck.sync

import android.content.Context
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
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.data.LocalStore
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

    private val hydrator = Hydrator(gateway, store)
    private val flusher = OutboxFlusher(gateway, store)
    private val realtime = RealtimeMirror(client, store, scope)

    private val prefs = context.getSharedPreferences("unstuck.sync", Context.MODE_PRIVATE)
    private var observeJob: Job? = null

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

    /** Manual best-effort sync (flush outbox → hydrate) for the periodic
     *  WorkManager job. No-op when signed out. */
    suspend fun syncNow() {
        val uid = auth.currentUserId ?: return
        flusher.flush(uid)
        hydrator.hydrate()
        runCatching { pullCalendar() }
    }

    // --- Google Calendar (consent + pull). Push of local blocks is a later step. ---

    private var pendingCalState: String? = null

    /** Start the OAuth consent: returns the Google authorize URL to open in a Custom Tab. */
    suspend fun beginGoogleConnect(): String? = runCatching {
        val r = calendar.authorize(CAL_REDIRECT)
        pendingCalState = r.state
        r.url
    }.getOrNull()

    /** Finish consent from the `unstuck://calendar-callback?code&state` deep link. */
    suspend fun completeGoogleConnect(code: String, state: String): Boolean = runCatching {
        val expected = pendingCalState
        if (expected != null && expected != state) return false  // CSRF guard
        calendar.connectGoogle(code, CAL_REDIRECT, state)
        pendingCalState = null
        pullCalendar()
        true
    }.getOrElse { false }

    /** Pull external events for [-7d, +30d] and reconcile them into local EXTERNAL blocks. */
    suspend fun pullCalendar() {
        auth.currentUserId ?: return
        val conns = runCatching { calendar.listConnections() }.getOrNull() ?: return
        if (conns.isEmpty()) return
        val today = LocalDate.now()
        val from = today.minusDays(7).toString()
        val to = today.plusDays(30).toString()
        val events = runCatching { calendar.pullEvents(from, to) }.getOrNull() ?: return
        val blocks = events.map { externalEventToBlock(it, it.calendarId) }
        val keep = blocks.map { it.id }.toSet()
        blocks.forEach { write.upsertCalBlock(it) }
        // Reconcile deletions: drop in-window EXTERNAL blocks Google no longer returns.
        store.blocks().first()
            .filter { it.kind == CalBlockKind.EXTERNAL && it.date >= from && it.date <= to && it.id !in keep }
            .forEach { write.deleteCalBlock(it.id) }
    }

    /** Disconnect an account and immediately purge its external blocks. */
    suspend fun disconnectCalendar(connectionId: String) {
        runCatching { calendar.disconnect(connectionId) }
        store.blocks().first()
            .filter { it.kind == CalBlockKind.EXTERNAL && it.externalConnectionId == connectionId }
            .forEach { write.deleteCalBlock(it.id) }
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
                flusher.flush(uid)
                hydrator.hydrate()
                realtime.subscribeAll(uid)
                runCatching { pullCalendar() }   // ingest Google events if connected
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
        private const val KEY_PREV_USER = "unstuck.prevUserId"
        private const val CAL_REDIRECT = "unstuck://calendar-callback"
    }
}
