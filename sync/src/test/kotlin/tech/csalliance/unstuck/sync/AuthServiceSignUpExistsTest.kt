package tech.csalliance.unstuck.sync

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.user.Identity
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.sync.ScriptedHttpServer.Reply

/**
 * A sign-up with an address that already has an account (owner, 2026-09-23: "we need
 * to notify the user the email has been used"). Measured on prod the same day: GoTrue
 * answers an already-registered, confirmed address with 200, an obfuscated user, NO
 * session, NO email, and `"identities": []`; a genuine new sign-up carries one
 * identity. supabase-kt 3.0.3's signUpWith(Email) returns that user decoded (null only
 * when the answer carried a session), so the empty list reaches the app. The decision
 * first, then through the real client against 127.0.0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthServiceSignUpExistsTest {

    private fun identity() = Identity(
        id = "u1", identityData = kotlinx.serialization.json.JsonObject(emptyMap()), identityId = "i1",
        lastSignInAt = "2026-09-23T19:00:00Z", updatedAt = "2026-09-23T19:00:00Z", createdAt = "2026-09-23T19:00:00Z",
        provider = "email", userId = "u1",
    )

    // ── the decision ──

    @Test fun emptyIdentitiesMeansTheAccountExists() {
        val r = AuthService.signUpOutcome(UserInfo(aud = "authenticated", id = "fake", identities = emptyList()), hasSession = false)
        assertEquals(AuthOutcome.Error(AuthService.ACCOUNT_EXISTS, accountExists = true), r)
        assertEquals("An account with this email already exists. Sign in instead.", AuthService.ACCOUNT_EXISTS)
    }

    @Test fun nullOrMissingIdentitiesIsNotATell() {
        assertEquals(AuthOutcome.Ok, AuthService.signUpOutcome(UserInfo(aud = "authenticated", id = "u1", identities = null), hasSession = false))
        // The SDK returns null when the answer carried a session (confirmations off).
        assertEquals(AuthOutcome.Ok, AuthService.signUpOutcome(null, hasSession = true))
        assertEquals(AuthOutcome.Ok, AuthService.signUpOutcome(null, hasSession = false))
    }

    @Test fun aGenuineNewSignUpHasOneIdentity() {
        assertEquals(AuthOutcome.Ok, AuthService.signUpOutcome(UserInfo(aud = "authenticated", id = "u1", identities = listOf(identity())), hasSession = false))
    }

    @Test fun confirmedWithoutASessionStillReadsAsExisting() {
        val confirmed = UserInfo(aud = "authenticated", id = "u1", identities = listOf(identity()), emailConfirmedAt = Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue((AuthService.signUpOutcome(confirmed, hasSession = false) as AuthOutcome.Error).accountExists)
        assertEquals(AuthOutcome.Ok, AuthService.signUpOutcome(confirmed, hasSession = true))
    }

    @Test fun userAlreadyExistsFromTheServerIsTheSameCase() {
        assertTrue(AuthService.isAlreadyRegistered(AuthRestException("user_already_exists", "User already registered", 422)))
        assertFalse(AuthService.isAlreadyRegistered(AuthRestException("weak_password", "Password should be at least 8 characters", 422)))
        assertFalse(AuthService.isAlreadyRegistered(java.io.IOException("offline")))
    }

    // ── through supabase-kt 3.0.3 ──

    private lateinit var server: ScriptedHttpServer
    @Volatile private var reply = Reply(200, "{}")
    private lateinit var auth: AuthService

    @Before fun setUp() {
        server = ScriptedHttpServer { reply }
        val client = createSupabaseClient("http://127.0.0.1:${server.port}", "test-anon-key") {
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = "unstuck"
                host = "auth-callback"
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
            }
        }
        auth = AuthService(client)
    }

    @After fun tearDown() { server.close() }

    @Test fun theObfuscatedAnswerSaysTheAccountExists() = runBlocking {
        // The prod shape for an already-registered, confirmed address.
        reply = Reply(200, """{"id":"6c1a0c8e-0000-4000-8000-000000000000","aud":"authenticated","role":"authenticated","email":"a@example.com","phone":"","confirmation_sent_at":"2026-09-23T19:00:00.000000Z","app_metadata":{"provider":"email","providers":["email"]},"user_metadata":{},"identities":[],"created_at":"2026-09-23T19:00:00.000000Z","updated_at":"2026-09-23T19:00:00.000000Z","is_anonymous":false}""")
        assertEquals(AuthOutcome.Error(AuthService.ACCOUNT_EXISTS, accountExists = true), auth.signUp("a@example.com", "pw-123456", null))
    }

    @Test fun aGenuineNewSignUpSaysCheckYourEmail() = runBlocking {
        reply = Reply(200, """{"id":"u1","aud":"authenticated","role":"authenticated","email":"new@example.com","phone":"","confirmation_sent_at":"2026-09-23T19:00:00.000000Z","app_metadata":{"provider":"email","providers":["email"]},"user_metadata":{},"identities":[{"identity_id":"i1","id":"u1","user_id":"u1","identity_data":{"email":"new@example.com","email_verified":false,"phone_verified":false,"sub":"u1"},"provider":"email","last_sign_in_at":"2026-09-23T19:00:00.000000Z","created_at":"2026-09-23T19:00:00.000000Z","updated_at":"2026-09-23T19:00:00.000000Z","email":"new@example.com"}],"created_at":"2026-09-23T19:00:00.000000Z","updated_at":"2026-09-23T19:00:00.000000Z","is_anonymous":false}""")
        assertEquals(AuthOutcome.Ok, auth.signUp("new@example.com", "pw-123456", null))
    }

    @Test fun anAnswerWithoutIdentitiesIsNotCalledExisting() = runBlocking {
        reply = Reply(200, """{"id":"u1","aud":"authenticated","email":"new@example.com"}""")
        assertEquals(AuthOutcome.Ok, auth.signUp("new@example.com", "pw-123456", null))
    }

    @Test fun aRefusalAsAlreadyRegisteredOffersTheWaysIn() = runBlocking {
        reply = Reply(422, """{"code":422,"error_code":"user_already_exists","msg":"User already registered"}""")
        val r = auth.signUp("a@example.com", "pw-123456", null)
        assertTrue("was $r", r is AuthOutcome.Error && r.accountExists && r.message == AuthService.ACCOUNT_EXISTS)
    }
}
