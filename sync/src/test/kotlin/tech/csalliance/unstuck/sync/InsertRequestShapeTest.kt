package tech.csalliance.unstuck.sync

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import java.util.concurrent.CopyOnWriteArrayList
import tech.csalliance.unstuck.sync.ScriptedHttpServer.Reply
import tech.csalliance.unstuck.sync.ScriptedHttpServer.Sent

/**
 * The two stage-2 requests exactly as supabase-kt 3.0.3 puts them on the wire
 * (deterministic occurrence ids, Ahmad 2026-09-23). Step 0 checked the SERVER
 * side on prod (DECISIONS.md "Stage 2 step 0": `Prefer: resolution=
 * ignore-duplicates,return=representation` answers [row] for a new id and [] for
 * an existing one; the filtered PATCH answers [row] only for an open row on that
 * date) — with supabase-js. What is pinned here is that THIS client sends the same
 * request, through the real SyncGateway and a local HTTP server: were the SDK to
 * drop ignore-duplicates or the select, a mint would silently overwrite another
 * device's row (hazard c) or read every answer as "ignored". Parity with iOS build
 * 85's URLProtocol-stubbed SyncGateway tests. No real network: 127.0.0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsertRequestShapeTest {
    private lateinit var server: ScriptedHttpServer
    private val sent = CopyOnWriteArrayList<Sent>()
    @Volatile private var reply = Reply(201, "[]")
    private lateinit var gateway: SyncGateway

    @Before fun setUp() {
        server = ScriptedHttpServer { s -> sent += s; reply }
        val client = createSupabaseClient("http://127.0.0.1:${server.port}", "test-anon-key") { install(Postgrest) }
        gateway = SyncGateway(client)
    }

    @After fun tearDown() { server.close() }

    private val row = JsonObject(
        mapOf(
            "id" to JsonPrimitive("f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be"),
            "task_id" to JsonPrimitive("3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60"),
            "date" to JsonPrimitive("2026-09-24"),
            "start_time" to JsonPrimitive("07:00"),
            "duration_minutes" to JsonPrimitive(25),
        ),
    )

    private fun prefer(s: Sent): Set<String> = s.headers["prefer"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @Test fun insertIfAbsentIsAnIgnoreDuplicatesUpsertThatReturnsTheInsertedIds() = runBlocking {
        reply = Reply(201, """[{"id":"f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be"}]""")
        assertTrue(gateway.insertIfAbsent("cal_blocks", row, "user-1"))
        val s = sent.single()
        assertEquals("POST", s.method)
        assertEquals("/rest/v1/cal_blocks", s.path)
        assertEquals("id", s.query["on_conflict"])
        assertEquals("id", s.query["select"])
        val p = prefer(s)
        assertTrue("ignore-duplicates, not merge: $p", "resolution=ignore-duplicates" in p)
        assertFalse(p.any { it.startsWith("resolution=merge") })
        assertTrue("the answer carries the inserted rows: $p", "return=representation" in p)
        val body = Json.parseToJsonElement(s.body).let { if (it is kotlinx.serialization.json.JsonArray) it.single().jsonObject else it.jsonObject }
        assertEquals("user-1", (body["user_id"] as JsonPrimitive).content)
        assertEquals("f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be", (body["id"] as JsonPrimitive).content)
    }

    @Test fun anEmptyAnswerMeansTheServerAlreadyHadTheId() = runBlocking {
        reply = Reply(201, "[]")
        assertFalse(gateway.insertIfAbsent("cal_blocks", row, "user-1"))
    }

    @Test fun retimeIfOpenIsAColumnScopedPatchFilteredOnTheOpenDay() = runBlocking {
        reply = Reply(200, """[{"id":"f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be","start_time":"16:00","duration_minutes":45,"external_event_id":"their-evt"}]""")
        val back = gateway.retimeIfOpen("cal_blocks", "f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be", "2026-09-24", "16:00", 45)
        assertEquals("their-evt", (back!!["external_event_id"] as JsonPrimitive).content)
        val s = sent.single()
        assertEquals("PATCH", s.method)
        assertEquals("/rest/v1/cal_blocks", s.path)
        assertEquals("eq.f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be", s.query["id"])
        assertEquals("eq.2026-09-24", s.query["date"])
        assertEquals("eq.false", s.query["done"])
        assertEquals("eq.false", s.query["skipped"])
        assertTrue("select=${s.query["select"]}", s.query["select"] == "*")
        assertTrue("return=representation" in prefer(s))
        // Only the two columns: the mapping, name, date and done state are never sent.
        assertEquals(Json.parseToJsonElement("""{"start_time":"16:00","duration_minutes":45}"""), Json.parseToJsonElement(s.body))
    }

    @Test fun aRetimeThatMatchesNothingAnswersNull() = runBlocking {
        reply = Reply(200, "[]")
        assertNull(gateway.retimeIfOpen("cal_blocks", "x", "2026-09-24", "16:00", 45))
    }

    /** A refusal (RLS 403 / FK 409) arrives as supabase-kt's RestException, which the
     *  flusher classifies by status — never as an "ignored" answer. */
    @Test fun aRefusedInsertThrowsTheSdksRestException() = runBlocking {
        reply = Reply(409, """{"code":"23503","message":"insert or update on table \"cal_blocks\" violates foreign key constraint","details":null,"hint":null}""")
        val e = runCatching { gateway.insertIfAbsent("cal_blocks", row, "user-1") }.exceptionOrNull()
        assertTrue("was $e", e is RestException)
        assertEquals(OutboxFlusher.FlushFailure.REJECTED, OutboxFlusher.classifyFailure(e!!))
    }
}
