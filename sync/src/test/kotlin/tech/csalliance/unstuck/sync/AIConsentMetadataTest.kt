package tech.csalliance.unstuck.sync

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.sync.ScriptedHttpServer.Reply
import tech.csalliance.unstuck.sync.ScriptedHttpServer.Sent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The AI-consent OK on the wire (iOS AIConsentMetadataTests): read from a
 * GoTrue user body, and the exact user_metadata change the app sends — the
 * same keys the web writes — through the real supabase-kt 3.0.3 client
 * against a scripted 127.0.0.1 server. No real network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AIConsentMetadataTest {
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

    private fun md(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun userBody(metadata: String) =
        """{"id":"6f0c1e0a-3b1d-4a53-9a55-0c1f2d3e4f50","aud":"authenticated","role":"authenticated","email":"maya@example.com","app_metadata":{"provider":"email"},"user_metadata":$metadata}"""

    private fun signIn() = runBlocking {
        client.auth.importSession(
            UserSession(
                accessToken = "a-token", refreshToken = "r-token", expiresIn = 3600, tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = "6f0c1e0a-3b1d-4a53-9a55-0c1f2d3e4f50"),
                expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 3_600_000),
            ),
        )
    }

    // ── reading ──

    @Test fun aGrantWrittenByTheWebIsRead() {
        val r = AuthService.aiConsent(md("""{"full_name":"Maya","ai_consent_at":"2026-09-24T08:00:00.000Z","ai_consent_version":"2026-09-24"}"""))
        assertEquals(AIConsent.Record("2026-09-24T08:00:00.000Z", "2026-09-24"), r)
        assertTrue(r.isGranted)
    }

    @Test fun noKeysIsNoConsent() {
        assertEquals(AIConsent.Record.NONE, AuthService.aiConsent(md("""{"full_name":"Maya"}""")))
        assertEquals(AIConsent.Record.NONE, AuthService.aiConsent(null as JsonObject?))
        assertEquals(AIConsent.Record.NONE, AuthService.aiConsent(null as UserInfo?))
    }

    @Test fun aClearedOrOddlyTypedValueIsNoConsent() {
        assertFalse(AuthService.aiConsent(md("""{"ai_consent_at":null,"ai_consent_version":"2026-09-24"}""")).isGranted)
        assertFalse(AuthService.aiConsent(md("""{"ai_consent_at":true,"ai_consent_version":"2026-09-24"}""")).isGranted)
        assertFalse(AuthService.aiConsent(md("""{"ai_consent_at":"2026-09-24T08:00:00.000Z","ai_consent_version":"2025-01-01"}""")).isGranted)
        assertFalse(AuthService.aiConsent(md("""{"ai_consent_at":"","ai_consent_version":"2026-09-24"}""")).isGranted)
    }

    // ── writing ──

    @Test fun agreeWritesBothKeys() {
        val r = AIConsent.Record("2026-09-24T08:00:00.000Z", AIConsent.VERSION)
        assertEquals(
            JsonObject(mapOf("ai_consent_at" to JsonPrimitive("2026-09-24T08:00:00.000Z"), "ai_consent_version" to JsonPrimitive("2026-09-24"))),
            AuthService.aiConsentData(r),
        )
    }

    @Test fun turningItOffClearsTheTime() {
        assertEquals(JsonObject(mapOf("ai_consent_at" to JsonNull)), AuthService.aiConsentData(AIConsent.Record.NONE))
        val off = AIConsent.revoked(AIConsent.Record("2026-09-24T08:00:00.000Z", AIConsent.VERSION))
        assertEquals(JsonObject(mapOf("ai_consent_at" to JsonNull)), AuthService.aiConsentData(off))
    }

    /** GoTrue deletes a user_metadata key sent as null — it must reach the wire. */
    @Test fun turningItOffPutsAnExplicitNullOnTheWire() = runBlocking {
        signIn()
        reply = Reply(200, userBody("""{"ai_consent_version":"2026-09-24"}"""))
        val landed = auth.setAIConsent(AIConsent.revoked(AIConsent.Record("2026-09-24T08:00:00.000Z", AIConsent.VERSION)))
        val put = sent.single { it.path == "/auth/v1/user" && it.method == "PUT" }
        assertTrue(put.body, put.body.contains("\"ai_consent_at\":null"))
        assertEquals(AIConsent.Record(null, "2026-09-24"), landed)
    }

    @Test fun agreeingSendsBothKeysAndReadsTheAccountBack() = runBlocking {
        signIn()
        reply = Reply(200, userBody("""{"ai_consent_at":"2026-09-24T08:00:00.000Z","ai_consent_version":"2026-09-24"}"""))
        val landed = auth.setAIConsent(AIConsent.Record("2026-09-24T08:00:00.000Z", AIConsent.VERSION))
        val put = sent.single { it.path == "/auth/v1/user" && it.method == "PUT" }
        assertTrue(put.body, put.body.contains("\"ai_consent_at\":\"2026-09-24T08:00:00.000Z\""))
        assertTrue(put.body, put.body.contains("\"ai_consent_version\":\"2026-09-24\""))
        assertTrue(landed!!.isGranted)
    }

    @Test fun aWriteThatDoesNotLandIsNull() = runBlocking {
        signIn()
        reply = Reply(500, """{"msg":"down"}""")
        assertNull(auth.setAIConsent(AIConsent.grant(System.currentTimeMillis())))
    }

    @Test fun aFreshReadAsksTheServer() = runBlocking {
        signIn()
        reply = Reply(200, userBody("""{"ai_consent_at":"2026-09-24T08:00:00.000Z","ai_consent_version":"2026-09-24"}"""))
        val snap = auth.fetchAIConsent()!!
        assertEquals("6f0c1e0a-3b1d-4a53-9a55-0c1f2d3e4f50", snap.userId)
        assertTrue(snap.record.isGranted)
        assertTrue(sent.any { it.path == "/auth/v1/user" && it.method == "GET" })
    }

    @Test fun signedOutThereIsNothingToRead() = runBlocking {
        assertNull(auth.fetchAIConsent())
        assertTrue(sent.isEmpty())
    }
}
