package tech.csalliance.unstuck.sync

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// SessionGate — THE one answer to "who is signed in on this phone, and can I use
// their session?" for work that runs without the UI: the call ring push, the
// outbox drain / catch-up pull / SyncWorker, the call-outcome queue, the
// time-zone push and FCM token registration (Android audit 2026-09-23, A1/A2/A3).
//
// WHY it exists: supabase-kt 3.0.3's Android lifecycle hook runs
// stopAutoRefresh() + resetLoadingState() on every ProcessLifecycle ON_STOP,
// which leaves sessionStatus at Initializing — so currentUserOrNull() is null
// for as long as the app is in the background, and nothing reloads it until the
// next ON_START. In a process started by FCM / WorkManager / a broadcast the
// stored session is still loading (and refreshing over the network when it has
// expired). Reading `currentUserId == null` as "signed out" dropped call rings
// silently and made every background sync a no-op. Initializing is NEVER
// signed out here:
//
//   Authenticated              → Live (in the background, a token about to
//                                expire is refreshed first)
//   NotAuthenticated           → SignedOut
//   RefreshFailure             → Stored (the SDK is retrying; the account is here)
//   Initializing, SDK loading  → wait for the SDK (cold start: its init load;
//                                foreground: its ON_START reload — redone here
//                                if it hasn't landed after SDK_GRACE_MS), bounded
//   Initializing, nothing due  → the ON_STOP reset: load the stored session here
//   out of time                → Stored when the phone holds a session, else SignedOut
//
// The restore / refresh is single-flight and runs on [scope], never cancelled by
// a caller's deadline: a refresh cut mid-flight can spend the refresh token
// without storing its successor (VoiceToken's rule). It is imported WITHOUT the
// SDK's auto-refresh loop and with SessionSource.Unknown: the background keeps
// the SDK's "no refresh timer while backgrounded" rule, and SyncCoordinator does
// not mistake it for a launch (INITIAL_SESSION would subscribe realtime and
// record a wake time from the background). The next ON_START reloads it from
// storage the SDK's usual way.

/** What a background entry point may assume about the account. */
sealed interface SessionCheck {
    /** A live session: requests now carry this user's JWT. */
    data class Live(val userId: String) : SessionCheck
    /** The phone holds [userId]'s stored session but it could not be made live
     *  in time (offline, a slow or failed refresh). Still signed in — a call may
     *  ring (iOS's VoIP-token proxy) — but nothing can be sent as them yet. */
    data class Stored(val userId: String) : SessionCheck
    /** No account on this phone. */
    data object SignedOut : SessionCheck
}

/** The account this phone belongs to, live or stored — null only when signed out. */
val SessionCheck.accountId: String?
    get() = when (this) {
        is SessionCheck.Live -> userId
        is SessionCheck.Stored -> userId
        SessionCheck.SignedOut -> null
    }

/** The user whose JWT requests carry right now, else null. */
val SessionCheck.liveUserId: String? get() = (this as? SessionCheck.Live)?.userId

/** The slice of supabase-kt Auth the gate uses — a seam so the decision table is
 *  unit-tested with fakes. */
interface SessionPort {
    val status: StateFlow<SessionStatus>
    /** The session the SDK persisted (what its own reload would import). */
    suspend fun stored(): UserSession?
    /** Trade [refreshToken] for a new session (does not import it). */
    suspend fun refresh(refreshToken: String): UserSession
    /** Make [session] the live one (persisted), without the auto-refresh loop. */
    suspend fun adopt(session: UserSession)
    /** The SDK's own ON_START reload: the stored session, with its refresh timer. */
    suspend fun reload()
    /** Cancel the SDK's own refresh timer for the current session. */
    fun stopAutoRefresh()
}

/** The production port over supabase-kt 3.0.3 Auth. */
class SupabaseSessionPort(private val auth: Auth) : SessionPort {
    override val status: StateFlow<SessionStatus> get() = auth.sessionStatus
    override suspend fun stored(): UserSession? = auth.sessionManager.loadSession()
    override suspend fun refresh(refreshToken: String): UserSession = auth.refreshSession(refreshToken)
    override suspend fun adopt(session: UserSession) =
        auth.importSession(session, autoRefresh = false, source = SessionSource.Unknown)
    override suspend fun reload() { auth.loadFromStorage() }
    override fun stopAutoRefresh() = auth.stopAutoRefreshForCurrentSession()
}

/**
 * @param isForeground   the app is on screen (ProcessLifecycle STARTED) — the
 *                       SDK owns the session then: its ON_START reload and its
 *                       refresh timer.
 * @param knownUserId    the account last signed in on this phone (the sync
 *                       engine's prevUserId) — only for a stored session that
 *                       carries no user object.
 */
class SessionGate(
    private val port: SessionPort,
    private val scope: CoroutineScope,
    private val isForeground: () -> Boolean,
    private val knownUserId: () -> String? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sdkGraceMs: Long = SDK_GRACE_MS,
    private val log: (String) -> Unit = { println(it) },
) {
    /** The SDK has finished loading the stored session at least once in this
     *  process: an Initializing seen after that is the ON_STOP reset, which
     *  nothing will undo until the next ON_START. */
    @Volatile private var sdkSettled = false
    private val lock = Any()
    private var inFlight: Deferred<Unit>? = null

    init {
        scope.launch {
            port.status.first { it !is SessionStatus.Initializing }
            sdkSettled = true
        }
    }

    /** Resolve the account within [timeoutMs] (see the file header for the table). */
    suspend fun ensure(timeoutMs: Long = DEFAULT_TIMEOUT_MS): SessionCheck =
        withTimeoutOrNull(timeoutMs) { settle() } ?: fallback()

    private suspend fun settle(): SessionCheck {
        var steps = 0
        while (true) {
            when (val s = port.status.value) {
                is SessionStatus.NotAuthenticated -> { sdkSettled = true; return SessionCheck.SignedOut }
                // The SDK is already retrying on its own timer; a second refresh
                // would only race it.
                is SessionStatus.RefreshFailure -> { sdkSettled = true; return fallback() }
                is SessionStatus.Authenticated -> {
                    sdkSettled = true
                    val uid = s.session.user?.id ?: return fallback()
                    if (usable(s.session)) return SessionCheck.Live(uid)
                    // A backgrounded token about to expire: the SDK stopped its timer at
                    // ON_STOP (or the timer slept through Doze). Refresh it once.
                    if (steps++ >= MAX_STEPS) return SessionCheck.Stored(uid)
                    shared { refreshIfStale() }
                    if (port.status.value == s) return SessionCheck.Stored(uid)   // the refresh failed
                }
                SessionStatus.Initializing -> {
                    if (!sdkSettled) {
                        // Cold start: the SDK's init load is in flight (refreshing over
                        // the network when the stored token expired). Wait for it rather
                        // than race it with a second refresh; the caller's deadline bounds this.
                        port.status.first { it !is SessionStatus.Initializing }
                        continue
                    }
                    if (isForeground() &&
                        withTimeoutOrNull(sdkGraceMs) { port.status.first { it !is SessionStatus.Initializing } } != null
                    ) continue   // the SDK's ON_START reload landed
                    if (steps++ >= MAX_STEPS) return fallback()
                    shared { restoreIfReset() }
                    if (port.status.value is SessionStatus.Initializing) return fallback()   // nothing stored, or offline
                }
            }
        }
    }

    /** The answer without waiting any longer. */
    private suspend fun fallback(): SessionCheck {
        when (val s = port.status.value) {
            is SessionStatus.NotAuthenticated -> return SessionCheck.SignedOut
            is SessionStatus.Authenticated -> s.session.user?.id?.let { uid ->
                return if (usable(s.session)) SessionCheck.Live(uid) else SessionCheck.Stored(uid)
            }
            else -> Unit
        }
        val stored = runCatching { port.stored() }.getOrNull() ?: return SessionCheck.SignedOut
        val uid = stored.user?.id ?: knownUserId() ?: return SessionCheck.SignedOut
        return SessionCheck.Stored(uid)
    }

    /** Requests may use this session now: the SDK owns it in the foreground,
     *  and in the background it outlives the work about to use it. */
    private fun usable(session: UserSession): Boolean =
        isForeground() || session.expiresAt.toEpochMilliseconds() - nowMs() > EXPIRY_MARGIN_MS

    private suspend fun restoreIfReset() {
        if (port.status.value !is SessionStatus.Initializing) return
        // On screen, the SDK's ON_START reload never came (its hook saw the refresh
        // timer still running and skipped, then the ON_STOP reset landed): do that
        // reload — a foreground session needs the timer, and the launch it signals.
        if (isForeground()) { port.reload(); return }
        val stored = port.stored() ?: return
        val session = if (stored.expiresAt.toEpochMilliseconds() - nowMs() > EXPIRY_MARGIN_MS) stored
            else port.refresh(stored.refreshToken)
        // The SDK's own ON_START reload may have landed while this refreshed: never
        // overwrite it (its Storage emission is what starts the foreground sync).
        if (port.status.value !is SessionStatus.Initializing) return
        port.adopt(session)
        log("[session] restored the stored session in the background")
    }

    private suspend fun refreshIfStale() {
        val s = port.status.value as? SessionStatus.Authenticated ?: return
        if (usable(s.session)) return
        // The SDK's timer (if one is left) would later spend this same refresh token.
        port.stopAutoRefresh()
        val fresh = port.refresh(s.session.refreshToken)
        if (port.status.value != s) return
        port.adopt(fresh)
        log("[session] refreshed an expiring session in the background")
    }

    /** Run [step] once for every concurrent caller, on [scope] (a caller's
     *  deadline never cancels it), and wait for it. A failure is logged; the
     *  caller re-reads the status. */
    private suspend fun shared(step: suspend () -> Unit) {
        val job = synchronized(lock) {
            inFlight?.takeIf { it.isActive } ?: scope.async {
                try {
                    step()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    log("[session] background session step failed: $t")
                }
            }.also { inFlight = it }
        }
        job.await()
    }

    companion object {
        /** Background work (sync, the outcome queue, token registration). */
        const val DEFAULT_TIMEOUT_MS = 10_000L
        /** How long a foreground caller waits for the SDK's own ON_START reload
         *  before loading the stored session itself. */
        const val SDK_GRACE_MS = 3_000L
        /** A background token with less left than this is refreshed before use. */
        const val EXPIRY_MARGIN_MS = 5 * 60_000L
        /** Refresh / restore rounds per ensure() — a step that keeps failing
         *  answers with what is known instead of looping. */
        private const val MAX_STEPS = 2
    }
}
