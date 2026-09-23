package tech.csalliance.unstuck.sync

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.Functions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * How CalendarClient reads calendar-sync's failures through the real supabase-kt
 * 3.0.3 Functions client (stage 2 review, Ahmad 2026-09-23). The SDK reports a
 * non-2xx answer as a RestException (a 404 as NotFoundRestException, a 429 as
 * UnauthorizedRestException), never ktor's ResponseException, so the old catch
 * never ran: a PATCH of an event deleted in Google failed on every push instead
 * of re-inserting, and a 429 never backed off. A series edit now rewrites its days
 * in place with their mapping, so that PATCH is the common path. Parity with iOS
 * CalendarClient.classify. No real network: 127.0.0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarClientErrorTest {
    private lateinit var server: ScriptedHttpServer
    private val sent = CopyOnWriteArrayList<Sent>()
    @Volatile private var reply = Reply(200, "{}")
    private lateinit var calendar: CalendarClient

    @Before fun setUp() {
        server = ScriptedHttpServer { s -> sent += s; reply }
        calendar = CalendarClient(createSupabaseClient("http://127.0.0.1:${server.port}", "test-anon-key") { install(Functions) })
    }

    @After fun tearDown() { server.close() }

    private suspend fun patch() = calendar.patchEvent("evt-1", "conn-1", "primary", "Stretch", "2026-09-24T07:00:00Z", "2026-09-24T07:25:00Z")

    @Test fun aPatchOfAnEventGoneFromGoogleSaysSo() = runBlocking {
        reply = Reply(404, """{"error":"event_gone"}""")
        val e = runCatching { patch() }.exceptionOrNull()
        assertTrue("was $e", e is CalendarEventGone)
        val s = sent.single()
        assertEquals("PATCH", s.method)
        assertEquals("/functions/v1/calendar-sync/events/evt-1", s.path)
    }

    /** Any other 404 (a missing connection or route) is not a deleted event: a
     *  re-insert would leave the old event beside the new one. */
    @Test fun anyOther404IsNotAGoneEvent() = runBlocking {
        reply = Reply(404, """{"error":"Connection not found"}""")
        val e = runCatching { patch() }.exceptionOrNull()
        assertTrue("was $e", e is RestException)
    }

    @Test fun a429BacksOffOnEveryCall() = runBlocking {
        reply = Reply(429, """{"error":"rate_limited"}""")
        assertTrue(runCatching { patch() }.exceptionOrNull() is CalendarRateLimited)
        assertTrue(runCatching { calendar.insertEvent("conn-1", "primary", "Stretch", "a", "b") }.exceptionOrNull() is CalendarRateLimited)
        assertTrue(runCatching { calendar.pullEvents("2026-09-01", "2026-10-01") }.exceptionOrNull() is CalendarRateLimited)
    }

    @Test fun aSuccessIsUntouched() = runBlocking {
        reply = Reply(200, """{"id":"evt-9"}""")
        assertEquals("evt-9", calendar.insertEvent("conn-1", "primary", "Stretch", "a", "b"))
        patch()
    }

    @Test fun theClassifierIsIosParity() {
        assertTrue(CalendarClient.classify(404, """{"error":"event_gone"}""") is CalendarEventGone)
        assertNull(CalendarClient.classify(404, """{"error":"Connection not found"}"""))
        assertTrue(CalendarClient.classify(429, "") is CalendarRateLimited)
        assertNull(CalendarClient.classify(500, "event_gone"))
    }
}
