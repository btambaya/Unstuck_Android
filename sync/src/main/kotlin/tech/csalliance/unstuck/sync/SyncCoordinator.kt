package tech.csalliance.unstuck.sync

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.data.LocalStore

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
    }
}
