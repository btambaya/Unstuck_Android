package tech.csalliance.unstuck.ui.auth

import io.github.jan.supabase.exceptions.HttpRequestException

/**
 * Finishing an `unstuck://auth-callback` link (magic link, password reset, sign-up
 * confirmation, Google): exchange its PKCE code for a session. supabase-kt's
 * handleDeeplinks ran that exchange on the SDK's own scope with no handler, so ANY
 * refusal — a code bound to an older verifier (a second link / reset / Google request
 * replaces it, even a rate-limited one), a code already used and re-delivered from
 * Recents, app data cleared in between — or a network drop was an uncaught exception
 * that killed the app (Android audit 2026-09-23, A7). Pure over [exchange] — unit-tested.
 */
internal object AuthLink {
    const val EXPIRED = "That link has expired or was already used — request a new one."
    const val OFFLINE = "Couldn't finish signing in — check your connection and tap the link again."

    /** Null when the link signed the user in (or carried nothing to act on); otherwise
     *  the message to show. An expired or already-used email link comes back with no
     *  code, as `error_code=otp_expired`; any other code-less error (a cancelled Google
     *  consent) shows nothing, as before. Cancellation is rethrown. */
    suspend fun complete(code: String?, errorCode: String?, errorDescription: String?, exchange: suspend (String) -> Unit): String? {
        if (code.isNullOrBlank()) {
            val expired = errorCode == "otp_expired" || errorDescription?.contains("expired", ignoreCase = true) == true
            return if (expired) EXPIRED else null
        }
        return try {
            exchange(code)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isNetwork(e)) OFFLINE else EXPIRED
        }
    }

    private fun isNetwork(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it is HttpRequestException || it is java.io.IOException }
}
