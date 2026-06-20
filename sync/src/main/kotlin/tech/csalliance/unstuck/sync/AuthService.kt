package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
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
    data class Error(val message: String) : AuthOutcome()
}

class AuthService(private val client: SupabaseClient) {

    private fun friendly(e: Throwable): String =
        humanizeAuthError(AuthErrorInfo(message = e.message ?: e.toString()))

    suspend fun signIn(email: String, password: String): AuthOutcome =
        runCatching {
            client.auth.signInWith(Email) { this.email = email; this.password = password }
        }.fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    suspend fun signUp(email: String, password: String, displayName: String?): AuthOutcome =
        runCatching {
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                val name = displayName?.trim()
                if (!name.isNullOrEmpty()) data = buildJsonObject { put("full_name", name); put("display_name", name) }
            }
        }.fold(
            { user ->
                // Supabase's anti-enumeration returns a "successful" obfuscated user for an
                // already-registered email (no session, empty identities). Surface it instead
                // of the misleading "check your email" — otherwise a returning user is stuck.
                val exists = detectSignupAlreadyExists(
                    identitiesCount = user?.identities?.size,
                    emailConfirmedAt = user?.emailConfirmedAt?.toString(),
                    lastSignInAt = user?.lastSignInAt?.toString(),
                    hasSession = client.auth.currentSessionOrNull() != null,
                )
                if (exists) AuthOutcome.Error(humanizeAuthError(AuthErrorInfo(code = "user_already_exists")))
                else AuthOutcome.Ok
            },
            { AuthOutcome.Error(friendly(it)) },
        )

    suspend fun sendMagicLink(email: String): AuthOutcome =
        runCatching { client.auth.signInWith(OTP) { this.email = email } }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

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

    suspend fun signOut() {
        runCatching { client.auth.signOut() }
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
