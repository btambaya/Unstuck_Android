package tech.csalliance.unstuck.sync

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.sync.ScriptedHttpServer.Reply
import tech.csalliance.unstuck.sync.ScriptedHttpServer.Sent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which redirect each email request carries, on the wire, through the real
 * supabase-kt 3.0.3 Auth client configured like SupabaseClientProvider (PKCE, the
 * `unstuck://auth-callback` default). Owner decision 2026-09-23 (same contract as
 * iOS): sign-up and magic link ask for `unstuck://auth-confirm`, which the email
 * templates turn into the https://unstucknow.io/auth/app-confirm App Link; the
 * password reset keeps `auth-callback` (its recovery probe depends on the PKCE code
 * link). And the App Link's token hash is verified with POST /verify {type,
 * token_hash}. No real network: 127.0.0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthServiceEmailLinkTest {
    private lateinit var server: ScriptedHttpServer
    private val sent = CopyOnWriteArrayList<Sent>()
    @Volatile private var reply = Reply(200, "{}")
    private lateinit var client: io.github.jan.supabase.SupabaseClient
    private lateinit var auth: AuthService

    @Before fun setUp() {
        server = ScriptedHttpServer { s -> sent += s; reply }
        val defaults = SyncConfig(url = "http://127.0.0.1:${server.port}", anonKey = "test-anon-key")
        client = createSupabaseClient(defaults.url, defaults.anonKey) {
            install(Auth) {
                // As SupabaseClientProvider, minus the device storage and lifecycle hooks.
                flowType = FlowType.PKCE
                scheme = defaults.authScheme
                host = defaults.authHost
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

    private fun only(path: String): Sent = sent.single { it.path == path }

    @Test fun signUpAsksForTheAppConfirmLink() = runBlocking {
        reply = Reply(200, """{"id":"u1","aud":"authenticated","email":"a@example.com"}""")
        auth.signUp("a@example.com", "pw-123456", "Ada")
        val s = only("/auth/v1/signup")
        assertEquals("unstuck://auth-confirm", s.query["redirect_to"])
        assertEquals(AuthService.EMAIL_LINK_REDIRECT, s.query["redirect_to"])
    }

    @Test fun aMagicLinkAsksForTheAppConfirmLink() = runBlocking {
        auth.sendMagicLink("a@example.com")
        assertEquals("unstuck://auth-confirm", only("/auth/v1/otp").query["redirect_to"])
    }

    @Test fun aPasswordResetKeepsTheAuthCallback() = runBlocking {
        auth.resetPassword("a@example.com")
        assertEquals("unstuck://auth-callback", only("/auth/v1/recover").query["redirect_to"])
    }

    @Test fun theLinkIsVerifiedByTokenHashAndSignsIn() = runBlocking {
        reply = Reply(200, """{"access_token":"at","token_type":"bearer","expires_in":3600,"refresh_token":"rt","user":{"id":"u1","aud":"authenticated","email":"a@example.com"}}""")
        auth.verifyEmailLink("pkce_abc", OtpType.Email.SIGNUP)
        val s = only("/auth/v1/verify")
        assertEquals("POST", s.method)
        val body = Json.parseToJsonElement(s.body).jsonObject
        assertEquals("signup", body["type"]?.jsonPrimitive?.content)
        assertEquals("pkce_abc", body["token_hash"]?.jsonPrimitive?.content)
        assertFalse("no email/OTP-code verification", body.containsKey("token") || body.containsKey("email"))
        assertEquals("u1", client.auth.currentSessionOrNull()?.user?.id)
    }

    @Test fun aMagicLinkIsVerifiedAsMagiclink() = runBlocking {
        reply = Reply(200, """{"access_token":"at","token_type":"bearer","expires_in":3600,"refresh_token":"rt","user":{"id":"u2","aud":"authenticated"}}""")
        auth.verifyEmailLink("pkce_m", OtpType.Email.MAGIC_LINK)
        assertEquals("magiclink", Json.parseToJsonElement(only("/auth/v1/verify").body).jsonObject["type"]?.jsonPrimitive?.content)
    }

    /** What AppConfirmLink reads: a used / expired link is a 4xx RestException. */
    @Test fun aUsedLinkThrowsA403() = runBlocking {
        reply = Reply(403, """{"code":403,"error_code":"otp_expired","msg":"Email link is invalid or has expired"}""")
        val e = runCatching { auth.verifyEmailLink("pkce_used", OtpType.Email.SIGNUP) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue("was $e", e is RestException && e.statusCode == 403)
        assertEquals(null, client.auth.currentSessionOrNull())
    }
}
