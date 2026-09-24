package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Collections

/**
 * The dial of the realtime client (Talk and calls) — parity with iOS build 81
 * VoiceCaptionTests (audit 2026-09-22 C14, and the voice-proxy contract SC-V1 /
 * SC-V2):
 *  - the token is resolved at DIAL time by the provider; no provider = the
 *    cached token at once (the legacy path);
 *  - a pre-open 401 forces ONE refresh and redials; a second 401, a failed
 *    refresh, or a refresh that hands back the refused token says "sign in
 *    again"; nothing else is retried;
 *  - the proxy's raw rejection text is never shown;
 *  - the dial watchdog bounds the token wait and the handshake (15 s), and the
 *    one redial after a 401 gets its grace;
 *  - a close the SERVER starts (1008 daily limit, 1000 session time limit) is
 *    read at once, not after a ping failure;
 *  - nothing is dialled or reported after stop().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceRealtimeClientDialTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    private class FakeEngine(ctx: Context) : VoiceAudioEngine(ctx) {
        @Volatile var shutdowns = 0
        override fun startCapture(onFrame: (ByteArray) -> Unit) {}
        override fun startPlayback() {}
        override fun shutdown() { shutdowns++ }
        override fun forceGate(open: Boolean) {}
        override fun afterCaptureDrain(block: () -> Unit) { block() }
        override fun stopCapture() {}
        override fun stopPlayback() {}
        override fun duck() {}
        override fun restore() {}
        override fun flushPlayback() {}
        override fun enqueue(pcm: ByteArray) {}
        override fun playbackQueued(): Boolean = false
        override fun outputBusy(): Boolean = false
    }

    /** [cancel] fails the socket through the listener at once, as OkHttp does
     *  for a cancelled handshake ("Canceled", on its own thread — here on the
     *  caller's, the worst case for who reports first). */
    private class FakeSocket(private val req: Request, private val listener: WebSocketListener) : WebSocket {
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var closedWith: Int? = null
        @Volatile var cancelled = false
        override fun request(): Request = req
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closedWith = code; return true }
        override fun cancel() {
            if (cancelled) return
            cancelled = true
            listener.onFailure(this, java.io.IOException("Canceled"), null)
        }
    }

    /** Every dial, in order — the Authorization header each one sent. */
    private class FakeFactory : WebSocket.Factory {
        val sockets: MutableList<FakeSocket> = Collections.synchronizedList(mutableListOf())
        @Volatile var listener: WebSocketListener? = null
        val dials: List<String> get() = synchronized(sockets) { sockets.map { it.request().header("Authorization") ?: "?" } }
        val last: FakeSocket get() = synchronized(sockets) { sockets.last() }
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return FakeSocket(request, listener).also { sockets += it }
        }
        fun open() {
            val s = last
            listener!!.onOpen(s, Response.Builder().request(s.request()).protocol(Protocol.HTTP_1_1).code(101).message("ok").build())
        }
        fun reject(status: Int, body: String = "") {
            val s = last
            val r = Response.Builder().request(s.request()).protocol(Protocol.HTTP_1_1).code(status).message("no")
                .body(body.toResponseBody()).build()
            listener!!.onFailure(s, java.net.ProtocolException("Expected HTTP 101 response but was '$status'"), r)
        }
        fun serverClose(code: Int, reason: String) = listener!!.onClosing(last, code, reason)
    }

    /** What the owner saw: onError messages, onTransportEnded calls, states. */
    private class Recorder {
        val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val ended: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<VoiceState> = Collections.synchronizedList(mutableListOf())
    }

    /** The provider: answers `normal` / `forced` (after optional delays), and
     *  records every ask. */
    private class Provider(
        val normal: String?, val forced: String? = null,
        val delayMs: Long = 0, val forcedDelayMs: Long = 0,
    ) {
        val asks: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        suspend fun get(force: Boolean): String? {
            asks += force
            if (force) { if (forcedDelayMs > 0) delay(forcedDelayMs); return forced }
            if (delayMs > 0) delay(delayMs)
            return normal
        }
    }

    private fun client(
        rec: Recorder, factory: FakeFactory, provider: Provider?, engine: FakeEngine = FakeEngine(app),
        callMode: CallMode? = null,
    ): VoiceRealtimeClient {
        val fresh: (suspend (Boolean) -> String?)? = provider?.let { p -> { force -> p.get(force) } }
        val c = VoiceRealtimeClient(
            proxyUrl = "wss://voice.example/ws", token = "cached", model = "m", instructions = "i",
            tools = JsonArray(emptyList()), opening = "hello", audio = engine,
            runTool = { _, _ -> "ok" },
            onState = { rec.states += it }, onCaption = { _, _, _ -> }, onError = { rec.errors += it },
            socketFactory = factory, callMode = callMode,
            freshToken = fresh,
        )
        c.onTransportEnded = { rec.ended += it }
        return c
    }

    private fun eventually(timeoutMs: Long = 3_000, cond: () -> Boolean): Boolean {
        var waited = 0L
        while (!cond() && waited < timeoutMs) { Thread.sleep(10); waited += 10 }
        return cond()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // ── the token at dial time ──

    @Test fun `with no provider the dial is immediate with the cached token`() {
        val rec = Recorder(); val f = FakeFactory()
        val c = client(rec, f, null)
        c.start()
        assertEquals("the legacy path is unchanged", listOf("Bearer cached"), f.dials)
        c.stop()
    }

    @Test fun `the provider's fresh token is dialled, and no answer falls back to the cached one`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh")
        val c = client(rec, f, p)
        c.start()
        assertTrue(eventually { f.dials.size == 1 })
        assertEquals(listOf("Bearer fresh"), f.dials)
        assertEquals(listOf(false), p.asks)
        c.stop()

        val rec2 = Recorder(); val f2 = FakeFactory()
        val c2 = client(rec2, f2, Provider(normal = null))
        c2.start()
        assertTrue(eventually { f2.dials.size == 1 })
        assertEquals(listOf("Bearer cached"), f2.dials)
        c2.stop()
    }

    // ── the one 401 redial ──

    @Test fun `a 401 before open refreshes once and redials, and the user is not told to sign in`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh-1", forced = "fresh-2")
        val engine = FakeEngine(app)
        val c = client(rec, f, p, engine)
        c.start()
        assertTrue(eventually { f.dials.size == 1 })
        f.reject(401, "unauthorized")
        assertTrue(eventually { f.dials.size == 2 })
        assertEquals(listOf("Bearer fresh-1", "Bearer fresh-2"), f.dials)
        assertEquals(listOf(false, true), p.asks)
        assertEquals(emptyList<String>(), rec.errors)
        assertEquals("the call is not hung up", 0, rec.ended.size)
        assertEquals("the mic keeps running for the redial", 0, engine.shutdowns)
        f.open()
        assertTrue(c.isOpen)
        c.stop()
    }

    @Test fun `a second 401 tells the user to sign in again - one forced refresh, never a loop`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh-1", forced = "fresh-2")
        val c = client(rec, f, p)
        c.start()
        assertTrue(eventually { f.dials.size == 1 })
        f.reject(401)
        assertTrue(eventually { f.dials.size == 2 })
        f.reject(401)
        assertEquals(listOf(VoiceRealtimeClient.SESSION_EXPIRED), rec.errors)
        assertEquals(listOf<String?>(VoiceRealtimeClient.SESSION_EXPIRED), rec.ended)
        assertEquals(listOf(false, true), p.asks)
        assertEquals(2, f.dials.size)
        c.stop()
    }

    @Test fun `a failed forced refresh ends without redialling`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh-1", forced = null)
        val c = client(rec, f, p)
        c.start()
        assertTrue(eventually { f.dials.size == 1 })
        f.reject(401)
        assertTrue(eventually { rec.errors.isNotEmpty() })
        assertEquals(listOf(VoiceRealtimeClient.SESSION_EXPIRED), rec.errors)
        assertEquals("no second dial with the refused token", listOf("Bearer fresh-1"), f.dials)
        c.stop()
    }

    @Test fun `a forced refresh handing back the refused token is no answer`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh-1", forced = "fresh-1")
        val c = client(rec, f, p)
        c.start()
        assertTrue(eventually { f.dials.size == 1 })
        f.reject(401)
        assertTrue(eventually { rec.errors.isNotEmpty() })
        assertEquals(listOf(VoiceRealtimeClient.SESSION_EXPIRED), rec.errors)
        assertEquals(1, f.dials.size)
        c.stop()
    }

    @Test fun `with no provider a 401 is reported at once, in words - never the proxy's body`() {
        val rec = Recorder(); val f = FakeFactory()
        val c = client(rec, f, null)
        c.start()
        f.reject(401, "unauthorized")
        assertEquals(listOf(VoiceRealtimeClient.SESSION_EXPIRED), rec.errors)
        assertEquals(1, rec.ended.size)
        assertEquals(1, f.dials.size)
        c.stop()
    }

    @Test fun `other rejections are not retried and are mapped from the status (SC-V2)`() {
        fun rejected(status: Int, body: String): Pair<List<String>, List<Boolean>> {
            val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh-1", forced = "fresh-2")
            val c = client(rec, f, p)
            c.start()
            assertTrue(eventually { f.dials.size == 1 })
            f.reject(status, body)
            c.stop()
            assertEquals(1, f.dials.size)
            return rec.errors.toList() to p.asks.toList()
        }
        assertEquals(listOf("A voice session is already running. Close it and try again in a moment.") to listOf(false),
            rejected(429, "too many concurrent voice sessions"))
        assertEquals(listOf(VoiceRealtimeClient.DAILY_LIMIT) to listOf(false), rejected(429, "daily voice limit reached"))
        assertEquals(listOf("Voice isn't available on this build.") to listOf(false), rejected(403, "model not allowed"))
        val upstream = rejected(502, "upstream connect failed (500): {\"error\":\"org-XYZ quota for model qwen\"}")
        assertEquals(listOf("The voice server is unavailable right now (502).") to listOf(false), upstream)
        assertFalse("provider text never reaches the user", upstream.first.single().contains("org-XYZ"))
    }

    @Test fun `only a pre-open 401 is retried, and only once`() {
        assertTrue(VoiceRealtimeClient.shouldRetryUnauthorized(401, openedOnce = false, retried = false))
        assertFalse(VoiceRealtimeClient.shouldRetryUnauthorized(401, openedOnce = false, retried = true))
        assertFalse(VoiceRealtimeClient.shouldRetryUnauthorized(401, openedOnce = true, retried = false))
        for (status in listOf(-1, 101, 403, 429, 500)) {
            assertFalse(VoiceRealtimeClient.shouldRetryUnauthorized(status, openedOnce = false, retried = false))
        }
    }

    // ── nothing after stop() ──

    @Test fun `stop while the token resolves never dials`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh", delayMs = 300)
        val c = client(rec, f, p)
        c.start()
        // Stop only once the provider is really resolving (the launch is async).
        assertTrue(eventually { p.asks.isNotEmpty() })
        c.stop()
        Thread.sleep(600)
        assertEquals(listOf(false), p.asks)
        assertEquals(emptyList<String>(), f.dials)
        assertEquals(emptyList<String>(), rec.errors)
        assertEquals(0, rec.ended.size)
    }

    @Test fun `stop during the forced refresh never redials`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh-1", forced = "fresh-2", forcedDelayMs = 300)
        val c = client(rec, f, p)
        c.start()
        assertTrue(eventually { f.dials.size == 1 })
        f.reject(401)
        c.stop()
        Thread.sleep(600)
        assertEquals(1, f.dials.size)
        assertEquals(emptyList<String>(), rec.errors)
        assertEquals(0, rec.ended.size)
    }

    // ── the dial watchdog ──

    @Test fun `a stalled token wait ends once at the dial timeout, and its late token is never dialled`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh", delayMs = 800)
        val c = client(rec, f, p)
        c.dialTimeoutMs = 200
        c.authRedialGraceMs = 30_000   // no 401, so no grace: the end must come at dialTimeout
        c.start()
        idle(250)
        assertEquals(listOf<String?>(VoiceRealtimeClient.UNREACHABLE), rec.ended)
        Thread.sleep(1_000)   // the provider answers meanwhile
        assertEquals(listOf<String?>(VoiceRealtimeClient.UNREACHABLE), rec.ended)
        assertEquals(listOf(VoiceRealtimeClient.UNREACHABLE), rec.errors)
        assertEquals("nothing is dialled once the dial was given up", emptyList<String>(), f.dials)
        c.stop()
    }

    @Test fun `a handshake that never upgrades is given up, and the socket is cancelled`() {
        val rec = Recorder(); val f = FakeFactory()
        val c = client(rec, f, null)
        c.start()
        idle(VoiceRealtimeClient.OPENING_WATCHDOG_MS)
        assertEquals("not before 15 s", 0, rec.ended.size)
        idle(15_000)
        assertEquals(listOf<String?>(VoiceRealtimeClient.UNREACHABLE), rec.ended)
        assertTrue(f.last.cancelled)
        // The cancel's own failure ("Canceled") came after the report and said nothing.
        assertEquals(listOf(VoiceRealtimeClient.UNREACHABLE), rec.errors)
        c.stop()
    }

    /** A redial that went out late in the shared window (after a refused dial
     *  and a forced refresh) must not be cut mid-handshake. */
    @Test fun `the redial after a 401 is not cut at the dial timeout`() {
        val rec = Recorder(); val f = FakeFactory(); val p = Provider(normal = "fresh-1", forced = "fresh-2")
        val c = client(rec, f, p)
        c.dialTimeoutMs = 300
        c.authRedialGraceMs = 2_000
        c.start()
        assertTrue(eventually { f.dials.size == 1 })
        f.reject(401)
        assertTrue(eventually { f.dials.size == 2 })
        idle(400)   // past the dial timeout
        assertEquals("the redial is still inside its grace", 0, rec.ended.size)
        idle(2_000)
        assertEquals(listOf<String?>(VoiceRealtimeClient.UNREACHABLE), rec.ended)
        assertEquals(listOf("Bearer fresh-1", "Bearer fresh-2"), f.dials)
        c.stop()
    }

    @Test fun `an opened session disarms the watchdog`() {
        val rec = Recorder(); val f = FakeFactory()
        val c = client(rec, f, null)
        c.start()
        f.open()
        idle(20_000)
        assertFalse(rec.ended.contains(VoiceRealtimeClient.UNREACHABLE))
        c.stop()
    }

    // ── closes the server starts (SC-V1) ──

    @Test fun `the proxy's daily-limit close is read at once and closed back`() {
        val rec = Recorder(); val f = FakeFactory()
        val engine = FakeEngine(app)
        val c = client(rec, f, null, engine)
        c.start(); f.open()
        f.listener!!.onMessage(f.last, """{"type":"response.created","response":{"id":"r1"}}""")
        f.serverClose(1008, "daily voice limit reached")
        assertEquals(listOf(VoiceRealtimeClient.DAILY_LIMIT), rec.errors)
        assertEquals(listOf<String?>(VoiceRealtimeClient.DAILY_LIMIT), rec.ended)
        assertEquals("we answer the close so OkHttp can finish", 1000, f.last.closedWith)
        assertFalse(c.isOpen)
        assertTrue(engine.shutdowns >= 1)
        // OkHttp's onClosed after our close must not report twice.
        f.listener!!.onClosed(f.last, 1000, "")
        assertEquals(1, rec.ended.size)
        c.stop()
    }

    @Test fun `server close codes map to what the user reads`() {
        assertEquals(VoiceRealtimeClient.DAILY_LIMIT, VoiceRealtimeClient.serverCloseMessage(1008, "daily voice limit reached"))
        assertEquals(VoiceRealtimeClient.SESSION_TIME_LIMIT, VoiceRealtimeClient.serverCloseMessage(1000, "session time limit"))
        assertNull("a clean close", VoiceRealtimeClient.serverCloseMessage(1000, "bye"))
        assertNull(VoiceRealtimeClient.serverCloseMessage(1001, ""))
        val other = VoiceRealtimeClient.serverCloseMessage(1011, "upstream: org-XYZ internal error")!!
        assertFalse("a relayed upstream reason is not shown", other.contains("org-XYZ"))
    }

    /** A clean close before ANY reply is a session that never happened: dead on
     *  arrival (Talk reconnects quietly, a call ends as Failed with its notes) —
     *  never a call reported done. iOS reads every server close as an error. */
    @Test fun `a clean server close before any reply is a failure, not a clean end`() {
        val rec = Recorder(); val f = FakeFactory()
        val c = client(rec, f, null)
        c.start(); f.open()
        f.serverClose(1000, "")
        assertEquals(listOf<String?>(VoiceRealtimeClient.SERVER_CLOSED), rec.ended)
        assertTrue(c.failedBeforeAnyReply)
        assertEquals("dead on arrival is not shown — the owner reconnects or reports", emptyList<String>(), rec.errors)
        // After an early server error (swallowed), the close still ends it as a failure.
        val rec2 = Recorder(); val f2 = FakeFactory()
        val c2 = client(rec2, f2, null)
        c2.start(); f2.open()
        f2.listener!!.onMessage(f2.last, """{"type":"error","error":{"message":"upstream busy"}}""")
        f2.serverClose(1000, "")
        assertEquals(listOf<String?>(VoiceRealtimeClient.SERVER_CLOSED), rec2.ended)
        assertTrue(c2.failedBeforeAnyReply)
        c.stop(); c2.stop()
    }

    @Test fun `a clean server close ends the session without an error`() {
        val rec = Recorder(); val f = FakeFactory()
        val c = client(rec, f, null)
        c.start(); f.open()
        f.listener!!.onMessage(f.last, """{"type":"response.created","response":{"id":"r1"}}""")
        f.serverClose(1000, "bye")
        assertEquals(emptyList<String>(), rec.errors)
        assertEquals(listOf<String?>(null), rec.ended)
        assertEquals(VoiceState.CLOSED, rec.states.last())
        c.stop()
    }

    // ── an in-call snooze outside the phone's hours (C12) ──

    @Test fun `a refused snooze is answered with the refusal and the call stays up`() {
        val rec = Recorder(); val f = FakeFactory()
        val snoozes = Collections.synchronizedList(mutableListOf<Int>())
        val refusal = "error: a call-back in 120 minutes would ring at 22:30, outside this phone's call hours (08:00–21:00), so it would be declined — ask them for a shorter wait, or for a time inside those hours to book with request_call"
        val c = client(rec, f, null, callMode = CallMode(setOf("complete_task"), onSnoozeCall = { snoozes += it },
            snoozeRefusal = { m -> if (m > 60) refusal else null }))
        c.start(); f.open()
        f.listener!!.onMessage(f.last, """{"type":"response.function_call_arguments.done","name":"snooze_call","call_id":"c1","arguments":"{\"minutes\":120}"}""")
        f.listener!!.onMessage(f.last, """{"type":"response.function_call_arguments.done","name":"snooze_call","call_id":"c2","arguments":"{\"minutes\":20}"}""")
        // A snapshot under the list's lock: the two tool outputs are appended
        // from IO coroutines while this polls (iterating the live list threw
        // ConcurrentModificationException now and then).
        fun outputs() = f.last.sent.let { l -> synchronized(l) { l.toList() } }.mapNotNull { raw ->
            val item = Json.parseToJsonElement(raw).jsonObject["item"]?.jsonObject ?: return@mapNotNull null
            if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call_output") null
            else item["call_id"]!!.jsonPrimitive.content to item["output"]!!.jsonPrimitive.content
        }.toMap()
        assertTrue(eventually { outputs().size == 2 })
        assertEquals(refusal, outputs()["c1"])
        assertEquals(CallMode.snoozeResult(20), outputs()["c2"])
        assertEquals("only the allowed call-back reaches the owner", listOf(20), snoozes.toList())
        assertTrue(c.isOpen)
        // Once one is accepted, a repeat is answered with it — even one the hours
        // would refuse — and never reaches the owner again (iOS snoozeActiveCall).
        f.listener!!.onMessage(f.last, """{"type":"response.function_call_arguments.done","name":"snooze_call","call_id":"c3","arguments":"{\"minutes\":120}"}""")
        assertTrue(eventually { outputs().size == 3 })
        assertEquals(CallMode.snoozeResult(20), outputs()["c3"])
        assertEquals(listOf(20), snoozes.toList())
        c.stop()
    }
}
