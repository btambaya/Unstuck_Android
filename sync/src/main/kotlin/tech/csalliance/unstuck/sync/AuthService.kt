package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.csalliance.unstuck.core.logic.AuthErrorInfo
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
        }.fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    suspend fun sendMagicLink(email: String): AuthOutcome =
        runCatching { client.auth.signInWith(OTP) { this.email = email } }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    suspend fun signInWithGoogle(): AuthOutcome =
        runCatching { client.auth.signInWith(Google) }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

    suspend fun resetPassword(email: String): AuthOutcome =
        runCatching { client.auth.resetPasswordForEmail(email) }
            .fold({ AuthOutcome.Ok }, { AuthOutcome.Error(friendly(it)) })

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
