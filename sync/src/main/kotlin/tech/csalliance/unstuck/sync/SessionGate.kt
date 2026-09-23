package tech.csalliance.unstuck.sync

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
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
//                                expire is refreshed first; on screen, an
//                                expired one is the SDK's to refresh — awaited)
//   NotAuthenticated           → SignedOut
//   RefreshFailure             → Stored (the SDK is retrying; the account is here)
//   Initializing, SDK loading  → wait for the SDK (cold start: its init load;
//                                on screen: its ON_START reload), bounded by the caller
//   Initializing, nothing due  → the ON_STOP reset, in the background: load the
//                                stored session here
//   refresh refused (4xx)      → SignedOut: the session was revoked (a sign-out
//                                elsewhere); the SDK clears it at its next load
//   out of time                → Stored when the phone holds a session, else SignedOut
//
// On screen the gate never loads or refreshes the session itself: the SDK's
// ON_START reload (or its refresh timer) is already doing it, and with the
// client's 90 s request timeout that can legitimately take far longer than any
// grace. A second load there spent the same refresh token twice (GoTrue revokes
// the family when the two land >10 s apart → the SDK signs out and wipes the
// outbox), and the SDK's loadFromStorage retries a network error for ever
// (Android audit 2026-09-23, A1 — second pass).
//
// The background restore / refresh is single-flight and runs on [scope], never
// cancelled by a caller's deadline: a refresh cut mid-flight can spend the
// refresh token without storing its successor (VoiceToken's rule). It is imported
// WITHOUT the SDK's auto-refresh loop and with SessionSource.Unknown: the
// background keeps the SDK's "no refresh timer while backgrounded" rule, and
// SyncCoordinator does not mistake it for a launch (INITIAL_SESSION would
// subscribe realtime and record a wake time from the background). The next
// ON_START reloads it from storage the SDK's usual way.

/** What a background entry point may assume about the account. */
sealed interface SessionCheck {
    /** A live session: requests now carry this user's JWT. */
    data class Live(val userId: String) : SessionCheck
    /** The phone holds [userId]'s stored session but it could not be made live
     *  in time (offline, a slow or failed refresh). Still signed in — a call may
     *  ring (iOS's VoIP-token proxy) — but nothing can be sent as them yet. */
    data class Stored(val userId: String) : SessionCheck
    /** No account on this phone (or only a session the server has revoked). */
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

/** The server refused a refresh token outright: it was revoked (a sign-out on
 *  another device, or GoTrue's reuse detection), so no retry can ever make it
 *  work. Offline, a 5xx, a timeout or a rate limit throw anything else. */
class RefreshRefused(val status: Int, cause: Throwable? = null) : Exception("refresh refused ($status)", cause)

/** The HTTP status when [t] is such a refusal: a 4xx other than 401 / 408 / 429
 *  (an API-key problem, a timeout and a rate limit are not the token's fault). */
internal fun refreshRefusalStatus(t: Throwable): Int? =
    (t as? RestException)?.statusCode?.takeIf { it in 400..499 && it != 401 && it != 408 && it != 429 }

/** The slice of supabase-kt Auth the gate uses — a seam so the decision table is
 *  unit-tested with fakes. */
interface SessionPort {
    val status: StateFlow<SessionStatus>
    /** The session the SDK persisted (what its own reload would import). */
    suspend fun stored(): UserSession?
    /** Trade [refreshToken] for a new session (does not import it). Throws
     *  [RefreshRefused] when the server refused the token outright. */
    suspend fun refresh(refreshToken: String): UserSession
    /** Make [session] the live one (persisted), without the auto-refresh loop. */
    suspend fun adopt(session: UserSession)
    /** Cancel the SDK's own refresh timer for the current session. */
    fun stopAutoRefresh()
}

/** The production port over supabase-kt 3.0.3 Auth. */
class SupabaseSessionPort(private val auth: Auth) : SessionPort {
    override val status: StateFlow<SessionStatus> get() = auth.sessionStatus
    override suspend fun stored(): UserSession? = auth.sessionManager.loadSession()
    override suspend fun refresh(refreshToken: String): UserSession = try {
        auth.refreshSession(refreshToken)
    } catch (e: RestException) {
        throw refreshRefusalStatus(e)?.let { RefreshRefused(it, e) } ?: e
    }
    override suspend fun adopt(session: UserSession) =
        auth.importSession(session, autoRefresh = false, source = SessionSource.Unknown)
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
    private val log: (String) -> Unit = { println(it) },
) {
    /** The SDK has finished loading the stored session at least once in this
     *  process: an Initializing seen after that is the ON_STOP reset, which
     *  nothing will undo until the next ON_START. */
    @Volatile private var sdkSettled = false
    /** The last refresh token the server refused. Its session is over: every later
     *  refresh would be refused again, and a SyncWorker answered "not live yet" kept
     *  retrying it with backoff until the app was opened (Android audit 2026-09-23,
     *  A2 — second pass). A new sign-in brings a new token. */
    @Volatile private var refusedRefreshToken: String? = null
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

    /** The live user right now, without waiting — null while the session is loading
     *  or its token has expired (the SDK is refreshing it). For on-screen reads that
     *  the SDK's own emission re-runs anyway. */
    fun liveNow(): String? =
        (port.status.value as? SessionStatus.Authenticated)?.session?.takeIf { usable(it) }?.user?.id

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
                    if (isForeground()) {
                        // On screen an expired token is the SDK's to refresh: its ON_START
                        // reload (a background restore leaves no timer) or its timer is doing
                        // it. Wait for that rather than send the dead JWT (a round of 401s
                        // that backs off a queued call outcome) or spend the same refresh
                        // token a second time.
                        port.status.first { it != s }
                        continue
                    }
                    // A backgrounded token about to expire: the SDK stopped its timer at
                    // ON_STOP (or the timer slept through Doze). Refresh it once.
                    if (steps++ >= MAX_STEPS) return fallback()
                    shared { refreshIfStale() }
                    if (port.status.value == s) return fallback()   // the refresh failed or was refused
                }
                SessionStatus.Initializing -> {
                    if (!sdkSettled || isForeground()) {
                        // The SDK's own load is in flight — its init load on a cold start,
                        // its ON_START reload on screen — refreshing over the network when
                        // the stored token expired. Wait for it rather than race it with a
                        // second refresh; the caller's deadline bounds this.
                        port.status.first { it !is SessionStatus.Initializing }
                        continue
                    }
                    if (steps++ >= MAX_STEPS) return fallback()
                    shared { restoreIfReset() }
                    if (port.status.value is SessionStatus.Initializing) return fallback()   // nothing stored, refused, or offline
                }
            }
        }
    }

    /** The answer without waiting any longer. */
    private suspend fun fallback(): SessionCheck {
        when (val s = port.status.value) {
            is SessionStatus.NotAuthenticated -> return SessionCheck.SignedOut
            is SessionStatus.Authenticated -> s.session.user?.id?.let { uid ->
                return when {
                    usable(s.session) -> SessionCheck.Live(uid)
                    refused(s.session) -> SessionCheck.SignedOut
                    else -> SessionCheck.Stored(uid)
                }
            }
            else -> Unit
        }
        val stored = runCatching { port.stored() }.getOrNull() ?: return SessionCheck.SignedOut
        if (refused(stored)) return SessionCheck.SignedOut
        val uid = stored.user?.id ?: knownUserId() ?: return SessionCheck.SignedOut
        return SessionCheck.Stored(uid)
    }

    /** Requests may use this session now: it has not expired, and in the background
     *  it also outlives the work about to use it. */
    private fun usable(session: UserSession): Boolean {
        val left = session.expiresAt.toEpochMilliseconds() - nowMs()
        return left > 0 && (isForeground() || left > EXPIRY_MARGIN_MS)
    }

    private fun refused(session: UserSession): Boolean = session.refreshToken == refusedRefreshToken

    private suspend fun restoreIfReset() {
        // On screen the SDK's ON_START reload owns it (see the file header).
        if (isForeground() || port.status.value !is SessionStatus.Initializing) return
        val stored = port.stored() ?: return
        if (refused(stored)) return
        val session = if (stored.expiresAt.toEpochMilliseconds() - nowMs() > EXPIRY_MARGIN_MS) stored
            else refreshOrNull(stored.refreshToken) ?: return
        // The SDK's own ON_START reload may have landed while this refreshed: never
        // overwrite it (its Storage emission is what starts the foreground sync).
        if (port.status.value !is SessionStatus.Initializing) return
        port.adopt(session)
        log("[session] restored the stored session in the background")
    }

    private suspend fun refreshIfStale() {
        val s = port.status.value as? SessionStatus.Authenticated ?: return
        if (usable(s.session) || isForeground() || refused(s.session)) return
        // The SDK's timer (if one is left) would later spend this same refresh token.
        port.stopAutoRefresh()
        val fresh = refreshOrNull(s.session.refreshToken) ?: return
        if (port.status.value != s) return
        port.adopt(fresh)
        log("[session] refreshed an expiring session in the background")
    }

    /** [SessionPort.refresh], remembering a refusal (null) so nothing asks again. */
    private suspend fun refreshOrNull(refreshToken: String): UserSession? = try {
        port.refresh(refreshToken)
    } catch (e: RefreshRefused) {
        refusedRefreshToken = refreshToken
        log("[session] the server refused the stored session's refresh (${e.status}) — signed out elsewhere; treated as signed out")
        null
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
        /** A background token with less left than this is refreshed before use. */
        const val EXPIRY_MARGIN_MS = 5 * 60_000L
        /** Refresh / restore rounds per ensure() — a step that keeps failing
         *  answers with what is known instead of looping. */
        private const val MAX_STEPS = 2
    }
}
