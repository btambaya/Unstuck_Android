package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The access token a voice dial sends (Talk and calls from Unstuck) — parity
 * with iOS build 81 (AuthService.freshAccessToken + AppModel.voiceDialToken,
 * audit 2026-09-22 C14/C15).
 *
 * `currentSessionOrNull()` hands back whatever token was stored last, with no
 * expiry check, and supabase-kt only auto-refreshes while the process is in the
 * foreground (its ProcessLifecycleOwner hook stops the refresh on ON_STOP). A
 * call answered from the lock screen of an app idle overnight therefore dialled
 * with last night's token and the proxy's 401 hung it up; and the proxy keeps
 * the connect-time token for the reply budget + turn log all session, so a token
 * that expired mid-session cut it short. So a dial resolves a token that is
 * valid for at least [MIN_VALIDITY_MS], refreshing when it isn't.
 *
 * Pure over injected seams, so the rules are unit-tested without a server.
 */
object VoiceToken {
    /** The proxy hard-closes a session after 15 min and keeps the connect-time
     *  token until then; 16 min = that cap plus a minute for the connect. */
    const val MIN_VALIDITY_MS = 16 * 60_000L
    /** How long a dial waits for a refresh when the stored token is EXPIRED —
     *  well inside the client's 15 s dial watchdog. */
    const val DEADLINE_MS = 5_000L
    /** A still-VALID token only short of [MIN_VALIDITY_MS] waits this long for
     *  its top-up, then dials as it is. */
    const val TOP_UP_DEADLINE_MS = 1_500L

    /** The stored session as far as a dial cares. */
    data class Stored(val token: String, val expiresAtMs: Long)

    /**
     * The token to dial with, or null (no session / the forced refresh failed).
     *  - [forceRefresh] (after the proxy's 401): only a refreshed token counts —
     *    the stored one is exactly what the proxy just refused.
     *  - otherwise: the stored token when it outlives the session; else a refresh,
     *    bounded by [DEADLINE_MS] when it is already expired and
     *    [TOP_UP_DEADLINE_MS] when it is only short; a refresh that fails or runs
     *    late falls back to the stored token (a 401 then forces one refresh).
     * [refresh] runs in [scope] and is NEVER cancelled by the deadline — an SDK
     * refresh cut mid-flight can rotate the refresh token server-side without
     * storing the new one (iOS `firstWithin` for the same reason); a late answer
     * is simply dropped.
     */
    suspend fun resolve(
        forceRefresh: Boolean,
        nowMs: () -> Long,
        stored: () -> Stored?,
        refresh: suspend () -> String?,
        scope: CoroutineScope,
    ): String? {
        if (forceRefresh) return firstWithin(DEADLINE_MS, scope, refresh)?.takeIf { it.isNotEmpty() }
        val cur = stored()?.takeIf { it.token.isNotEmpty() } ?: return null
        val left = cur.expiresAtMs - nowMs()
        if (left >= MIN_VALIDITY_MS) return cur.token
        val deadline = if (left <= 0) DEADLINE_MS else TOP_UP_DEADLINE_MS
        return firstWithin(deadline, scope, refresh)?.takeIf { it.isNotEmpty() } ?: cur.token
    }

    /** The fallback rule (iOS `voiceDialToken`): the resolved token, else the
     *  cached one — but never after a FORCED refresh, which follows the proxy
     *  refusing exactly that cached token. */
    fun dialToken(fresh: String?, cached: String?, forceRefresh: Boolean): String? {
        if (!fresh.isNullOrEmpty()) return fresh
        if (forceRefresh) return null
        return cached?.takeIf { it.isNotEmpty() }
    }

    /** [op]'s answer, or null once [ms] pass — whichever comes first. The op
     *  keeps running in [scope] when it loses; its result is dropped. */
    suspend fun <T> firstWithin(ms: Long, scope: CoroutineScope, op: suspend () -> T?): T? {
        val job = scope.async { runCatching { op() }.getOrNull() }
        return withTimeoutOrNull(ms) { job.await() }
    }
}
