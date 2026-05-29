package tech.csalliance.unstuck.core.logic

import java.net.URLDecoder

// Pure auth helpers. Port of the testable parts of lib/auth-helpers.ts: the
// humanizeAuthError mapping table, the nextSafePath open-redirect guard, and
// the pure "already exists" anti-enumeration detector. The networked
// sign-in/up/out calls (Supabase) live in :sync.

/** Minimal shape of a Supabase AuthError for the mapping table. */
data class AuthErrorInfo(val code: String? = null, val message: String? = null, val status: Int? = null)

/** Map Supabase's technical error messages to copy a human can act on. */
fun humanizeAuthError(err: AuthErrorInfo?): String {
    if (err == null) return "Something went wrong. Try again in a moment."
    val code = err.code ?: ""
    val message = (err.message ?: "").lowercase()
    val status = err.status

    if (code == "over_email_send_rate_limit" || message.contains("rate limit")) {
        return "We can only send a few sign-up emails per hour. Wait ~30 min and try again — or use a different email."
    }
    if (code == "invalid_credentials" || message.contains("invalid login credentials") || message.contains("invalid_credentials")) {
        return "That email and password don't match. Try again, or use Forgot password."
    }
    if (code == "user_already_exists" || message.contains("already registered") || message.contains("user already")) {
        return "An account with that email already exists. Try signing in instead."
    }
    if (code == "email_not_confirmed" || message.contains("email not confirmed")) {
        return "Your email isn't confirmed yet. Check your inbox for the verification link."
    }
    if (code == "weak_password" || message.contains("password should be")) {
        return "Password needs at least 8 characters."
    }
    if (code == "over_request_rate_limit" || status == 429) {
        return "You hit a rate limit. Slow down for a minute and try again."
    }
    if (message.contains("network") || message.contains("failed to fetch") || message.contains("timed out")) {
        return "Couldn't reach the server. Check your connection and try again."
    }
    if (message.contains("invalid email") || code == "validation_failed") {
        return "That email address looks off. Double-check it."
    }
    // Fallback — capitalise the first letter, keep the rest readable.
    val raw = err.message ?: "Unknown error"
    if (raw.isEmpty()) return raw
    return raw.first().uppercase() + raw.drop(1)
}

/** Validate a `?next=` redirect: allow only same-origin paths starting with a
 *  single `/` (open-redirect guard). Mirrors nextSafePath. */
fun nextSafePath(raw: String?, fallback: String = "/dashboard"): String {
    if (raw.isNullOrEmpty()) return fallback
    val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { return fallback }
    if (!decoded.startsWith("/")) return fallback
    if (decoded.startsWith("//")) return fallback
    return decoded
}

/** Supabase's sign-up anti-enumeration response is "successful" even for an
 *  already-registered email; these are the tells (any one ⇒ exists). */
fun detectSignupAlreadyExists(
    identitiesCount: Int?,
    emailConfirmedAt: String?,
    lastSignInAt: String?,
    hasSession: Boolean,
): Boolean {
    val emptyIdentities = identitiesCount == 0
    val confirmedNoSession = emailConfirmedAt != null && !hasSession
    val previouslySignedInNoSession = lastSignInAt != null && !hasSession
    return emptyIdentities || confirmedNoSession || previouslySignedInNoSession
}
