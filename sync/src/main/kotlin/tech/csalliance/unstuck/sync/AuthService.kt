package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.csalliance.unstuck.core.logic.AuthErrorInfo
import tech.csalliance.unstuck.core.logic.detectSignupAlreadyExists
import tech.csalliance.unstuck.core.logic.humanizeAuthError

// AuthService — thin wrapper over supabase-kt Auth. Email/password, magic
// link, Google OAuth, sign-out. Error copy reuses :core humanizeAuthError.
// Port of the iOS AuthService.swift.

sealed class AuthOutcome {
    data object Ok : AuthOutcome()
    /** [accountExists]: a sign-up for an address that already has an account — the
     *  sign-up screen offers Sign in / Forgot password instead of "check your email". */
    data class Error(val message: String, val accountExists: Boolean = false) : AuthOutcome()
}

class AuthService(private val client: SupabaseClient) {

    companion object {
        /** Where the sign-up and magic-link emails come back to (owner decision
         *  2026-09-23, shared with iOS): with this redirect the email templates
         *  (unstuck supabase/email-templates 01 + 02) link to
         *  https://unstucknow.io/auth/app-confirm/?token_hash=…&type=signup|magiclink —
         *  an App Link that opens this app on the phone ([verifyEmailLink]) and a web
         *  page that says "open the app" on a computer. Only these two emails:
         *  password reset and Google keep the client's default
         *  `unstuck://auth-callback` (scheme + host in [SupabaseClientProvider]), which
         *  the reset flow's recovery probe depends on. */
        const val EMAIL_LINK_REDIRECT = "unstuck://auth-confirm"

        /** Owner copy (2026-09-23) for a sign-up with an address that already has an
         *  account. No email goes out in that case, so "check your email" would strand
         *  them. */
        const val ACCOUNT_EXISTS = "An account with this email already exists. Sign in instead."

        /** What a sign-up that didn't throw means. supabase-kt 3.0.3's
         *  signUpWith(Email) imports the session and returns null when the answer
         *  carries an `access_token`, and otherwise returns the decoded [UserInfo].
         *  With confirmations on, GoTrue answers an ALREADY-registered, confirmed
         *  address with 200 and an obfuscated user: no session, NO email sent, and
         *  `"identities": []` — a genuine new sign-up carries one identity. Null or
         *  missing identities is not a tell (detectSignupAlreadyExists). Pure —
         *  unit-tested. */
        internal fun signUpOutcome(user: UserInfo?, hasSession: Boolean): AuthOutcome {
            val exists = detectSignupAlreadyExists(
                identitiesCount = user?.identities?.size,
                emailConfirmedAt = user?.emailConfirmedAt?.toString(),
                lastSignInAt = user?.lastSignInAt?.toString(),
                hasSession = hasSession,
            )
            return if (exists) AuthOutcome.Error(ACCOUNT_EXISTS, accountExists = true) else AuthOutcome.Ok
        }

        /** The same case with confirmations off: GoTrue refuses with
         *  `user_already_exists` ("User already registered"). */
        internal fun isAlreadyRegistered(e: Throwable): Boolean =
            (e as? RestException)?.error == "user_already_exists" ||
                e.message?.contains("already registered", ignoreCase = true) == true
    }

    private fun friendly(e: Throwable): String =
        humanizeAuthError(AuthErrorInfo(message = e.message ?: e.toString()))

    suspend fun signIn(email: String, password: String): AuthOutcome =
        runCatching {
            client.auth.signInWith(Email) { this.email = email; this.password = password }
        }.fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    suspend fun signUp(email: String, password: String, displayName: String?): AuthOutcome =
        runCatching {
            client.auth.signUpWith(Email, redirectUrl = EMAIL_LINK_REDIRECT) {
                this.email = email
                this.password = password
                val name = displayName?.trim()
                if (!name.isNullOrEmpty()) data = buildJsonObject { put("full_name", name); put("display_name", name) }
            }
        }.fold(
            // Supabase's anti-enumeration returns a "successful" obfuscated user for an
            // already-registered email (no session, empty identities). Surface it instead
            // of the misleading "check your email" — otherwise a returning user is stuck.
            { user -> signUpOutcome(user, hasSession = client.auth.currentSessionOrNull() != null) },
            { e ->
                if (isAlreadyRegistered(e)) AuthOutcome.Error(ACCOUNT_EXISTS, accountExists = true)
                else AuthOutcome.Error(friendly(e))
            },
        )

    /** A new address gets the sign-up email (01) instead, with the same redirect. */
    suspend fun sendMagicLink(email: String): AuthOutcome =
        runCatching { client.auth.signInWith(OTP, redirectUrl = EMAIL_LINK_REDIRECT) { this.email = email } }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    /** Finish an /auth/app-confirm email link: trade its token hash for a session
     *  (POST /verify {type, token_hash}). Works whichever device asked for the email
     *  — no PKCE verifier involved. supabase-kt imports the session as
     *  SessionSource.SignIn, which lands like any sign-in (SyncCoordinator: SIGNED_IN).
     *  Throws on failure: the caller tells a used link from a network drop. */
    suspend fun verifyEmailLink(tokenHash: String, type: OtpType.Email) {
        client.auth.verifyEmailOtp(type = type, tokenHash = tokenHash)
    }

    suspend fun signInWithGoogle(): AuthOutcome =
        runCatching { client.auth.signInWith(Google) }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    suspend fun resetPassword(email: String): AuthOutcome =
        runCatching { client.auth.resetPasswordForEmail(email) }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    /** Change / add the account password (auth.updateUser). */
    suspend fun changePassword(newPassword: String): AuthOutcome =
        runCatching { client.auth.updateUser { password = newPassword } }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    /** Update the display name in user metadata. */
    suspend fun updateDisplayName(name: String): AuthOutcome =
        runCatching {
            client.auth.updateUser { data = buildJsonObject { put("full_name", name); put("display_name", name) } }
        }.fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    /** Invoke the server-side `account-delete` Edge Function ONLY (no sign-out). The
     *  coordinator calls this so it can ALWAYS run push-unregister + signOut afterwards
     *  regardless of the invoke result (a timeout AFTER the server deleted the account
     *  must not strand a dead local session / lingering push row). */
    suspend fun deleteAccountInvoke(): AuthOutcome =
        runCatching { client.functions.invoke("account-delete") }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    /** Delete the account via the server-side `account-delete` Edge Function, then sign
     *  out. The invoke and the sign-out are guarded SEPARATELY: a timeout AFTER the
     *  server completed the deletion would otherwise skip signOut() and strand a dead
     *  local session. We ALWAYS best-effort sign out so local state is cleared either
     *  way, and only surface the invoke error to the caller. (The coordinator's
     *  deleteAccount() is preferred — it also unregisters this device's push token.) */
    suspend fun deleteAccount(): AuthOutcome {
        val invoke = deleteAccountInvoke()
        signOut()   // best-effort, regardless of the invoke outcome (own runCatching)
        return invoke
    }

    /** True if the account has an email/password identity (vs Google-only). Defaults
     *  FALSE when signed out / identities are null — never offer "Change password" to a
     *  Google-only account; we only claim a password when a real `email` identity exists. */
    val hasPassword: Boolean
        get() = client.auth.currentUserOrNull()?.identities?.any { it.provider == "email" } ?: false

    /** Sign THIS device out only. The SDK default is scope=global, which revoked the
     *  account's sessions on EVERY device — and the reactive sign-out on those devices
     *  destroyed their in-progress focus session while the Settings row promises
     *  "End this session". A "sign out everywhere" action would pass GLOBAL explicitly. */
    suspend fun signOut(scope: SignOutScope = SignOutScope.LOCAL) {
        runCatching { client.auth.signOut(scope) }
    }

    val currentUserId: String? get() = client.auth.currentUserOrNull()?.id

    /** The signed-in user's email, or null when signed out. */
    val currentEmail: String? get() = client.auth.currentUserOrNull()?.email

    /** display_name / full_name from user metadata, falling back to the email's local part. */
    val currentName: String? get() {
        val u = client.auth.currentUserOrNull() ?: return null
        val md = u.userMetadata
        val named = md?.get("display_name")?.jsonPrimitive?.contentOrNull
            ?: md?.get("full_name")?.jsonPrimitive?.contentOrNull
        return named?.takeIf { it.isNotBlank() } ?: u.email?.substringBefore('@')
    }
}
