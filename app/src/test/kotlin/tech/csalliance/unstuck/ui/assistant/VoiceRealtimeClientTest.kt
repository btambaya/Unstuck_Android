package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.SettingsStore
import java.time.Duration

/**
 * Robolectric guardrails for the realtime voice client's LIFECYCLE against a
 * fake audio engine and a fake socket factory (no AudioRecord, no network):
 *  - a synchronous capture failure (mic held elsewhere) must never dial — the
 *    proxy session would otherwise open as a "Listening…" zombie nobody can close;
 *  - a socket that opens AFTER stop() is closed on the spot, not greeted;
 *  - hold-to-talk commits only after the capture thread has drained the frame
 *    read during the press, and a too-short press's "buffer too small" is not fatal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceRealtimeClientTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** No AudioRecord/AudioTrack: records what the client asked for. */
    private class FakeEngine(ctx: Context, private val failCaptureSynchronously: Boolean = false) : VoiceAudioEngine(ctx) {
        var captureStarts = 0
        var playbackStarts = 0
        var shutdowns = 0
        val gate = mutableListOf<Boolean>()
        val drains = mutableListOf<() -> Unit>()
        var onFrame: ((ByteArray) -> Unit)? = null

        override fun startCapture(onFrame: (ByteArray) -> Unit) {
            captureStarts++
            this.onFrame = onFrame
            if (failCaptureSynchronously) onCaptureError?.invoke()
        }
        override fun startPlayback() { playbackStarts++ }
        override fun shutdown() { shutdowns++ }
        override fun forceGate(open: Boolean) { gate += open }
        override fun afterCaptureDrain(block: () -> Unit) { drains += block }
        override fun stopCapture() {}
        override fun stopPlayback() {}
        override fun duck() {}
        override fun restore() {}
        override fun flushPlayback() {}
        override fun enqueue(pcm: ByteArray) {}
        override fun playbackQueued(): Boolean = false
        override fun outputBusy(): Boolean = false
    }

    private class FakeSocket(private val req: Request) : WebSocket {
        // Tool outputs arrive from the client's IO coroutine while the test
        // thread polls — a synchronized list, read through snapshots.
        private val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sent: List<String> get() = synchronized(log) { log.toList() }
        var closed = false
        var cancelled = false
        override fun request(): Request = req
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { log += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { cancelled = true }
        fun types(): List<String> = sent.map { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "?" }
        /** Hidden user items carrying the integrity corrective. */
        fun correctives(): Int = sent.count { raw ->
            val ev = Json.parseToJsonElement(raw).jsonObject
            if (ev["type"]?.jsonPrimitive?.contentOrNull != "conversation.item.create") return@count false
            val item = ev["item"]?.jsonObject ?: return@count false
            item["role"]?.jsonPrimitive?.contentOrNull == "user" &&
                item["content"]?.jsonArray?.any { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull == VoiceIntegrityGuard.correctiveText } == true
        }
        fun toolOutputs(): Int = sent.count { raw ->
            Json.parseToJsonElement(raw).jsonObject["item"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "function_call_output"
        }
    }

    private class FakeFactory : WebSocket.Factory {
        var socket: FakeSocket? = null
        var listener: WebSocketListener? = null
        var dials = 0
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            dials++
            this.listener = listener
            return FakeSocket(request).also { socket = it }
        }
        fun open() {
            val s = socket!!
            listener!!.onOpen(s, Response.Builder().request(s.request()).protocol(Protocol.HTTP_1_1).code(101).message("ok").build())
        }
        fun message(json: String) { listener!!.onMessage(socket!!, json) }
    }

    private fun client(
        engine: FakeEngine, factory: FakeFactory, states: MutableList<VoiceState>,
        errors: MutableList<String> = mutableListOf(),
        runTool: suspend (String, kotlinx.serialization.json.JsonObject) -> String = { _, _ -> "ok" },
    ) = VoiceRealtimeClient(
        proxyUrl = "wss://voice.example/ws", token = "t", model = "m", instructions = "i",
        tools = JsonArray(emptyList()), opening = "hello", audio = engine,
        runTool = runTool,
        onState = { states += it }, onCaption = { _, _, _ -> }, onError = { errors += it },
        socketFactory = factory,
    )

    // ── voice integrity guard: wire-level helpers ──

    private fun FakeFactory.created(id: String) = message("""{"type":"response.created","response":{"id":"$id"}}""")
    private fun FakeFactory.transcript(id: String, text: String) =
        message("""{"type":"response.audio_transcript.delta","response_id":"$id","delta":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}""")
    private fun FakeFactory.done(id: String, status: String = "completed") =
        message("""{"type":"response.done","response":{"id":"$id","status":"$status"}}""")
    private fun FakeFactory.toolCall(name: String, callId: String) =
        message("""{"type":"response.function_call_arguments.done","name":"$name","call_id":"$callId","arguments":"{}"}""")

    /** The tool runs on Dispatchers.IO: wait for its output, then let the
     *  coalesced response.create fire on the (paused) main looper. */
    private fun awaitToolOutput(s: FakeSocket, expected: Int) {
        var waited = 0
        while (s.toolOutputs() < expected && waited < 5_000) { Thread.sleep(10); waited += 10 }
        assertEquals("tool output sent", expected, s.toolOutputs())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VoiceRealtimeClient.CONTINUE_DELAY_MS + 50))
    }

    private fun openSession(runTool: suspend (String, kotlinx.serialization.json.JsonObject) -> String = { _, _ -> "ok" }): Pair<FakeFactory, FakeSocket> {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val c = client(engine, factory, mutableListOf(), runTool = runTool)
        c.start()
        factory.open()
        return factory to factory.socket!!
    }

    @Test
    fun `guard - a spoken claim with no tool call gets exactly one hidden corrective`() {
        val (factory, s) = openSession()
        factory.created("r1")
        factory.transcript("r1", "I've added the dentist for you.")
        // A second claim inside the SAME response is still one response → one corrective at most.
        factory.transcript("r1", " And I've scheduled it for Tuesday.")
        assertEquals("nothing injected mid-utterance", 0, s.correctives())
        factory.done("r1")
        assertEquals(1, s.correctives())
        // The corrective is a hidden user item immediately followed by a response.create.
        val types = s.types()
        val idx = types.lastIndexOf("conversation.item.create")
        assertEquals("response.create", types[idx + 1])
        // The correction's own follow-up is NEVER scored — no loop.
        factory.created("r2")
        factory.transcript("r2", "Added it now.")
        factory.done("r2")
        assertEquals("no second corrective for the follow-up", 1, s.correctives())
    }

    @Test
    fun `guard - a claim backed by a succeeded write tool is not corrected`() {
        val (factory, s) = openSession { name, _ -> if (name == "create_task") "ok: created task id=1 name=\"x\"" else "ok" }
        factory.created("r1")
        factory.toolCall("create_task", "c1")
        awaitToolOutput(s, 1)
        assertEquals("one coalesced response.create after the tool output", "response.create", s.types().last())
        factory.transcript("r1", "Done — added it.")
        factory.done("r1")
        assertEquals(0, s.correctives())
        // The reply AFTER the tool result (the confirmation) is tool-backed too.
        factory.created("r2")
        factory.transcript("r2", "Booked — Thursday at two.")
        factory.done("r2")
        assertEquals(0, s.correctives())
        // …but the one after that is a fresh window: a bare claim is caught again.
        factory.created("r3")
        factory.transcript("r3", "I've moved it to Friday.")
        factory.done("r3")
        assertEquals(1, s.correctives())
    }

    @Test
    fun `guard - a read-only tool does not make a claim tool-backed, and a failed write does not back the next reply`() {
        val (factory, s) = openSession { name, _ -> if (name == "delete_task") "error: task not found" else "ok:\nMonday" }
        factory.created("r1")
        factory.toolCall("get_schedule", "c1")
        awaitToolOutput(s, 1)
        factory.transcript("r1", "Done — moved it.")
        factory.done("r1")
        assertEquals("get_schedule + a claim is still a fabrication", 1, s.correctives())
        // Follow-up of the corrective: not scored.
        factory.created("r2"); factory.transcript("r2", "Moved it now."); factory.done("r2")
        assertEquals(1, s.correctives())
        // A write tool that FAILED does not back the reply after it.
        factory.created("r3")
        factory.toolCall("delete_task", "c2")
        awaitToolOutput(s, 2)
        factory.done("r3")
        factory.created("r4"); factory.transcript("r4", "I've deleted it."); factory.done("r4")
        assertEquals(2, s.correctives())
    }

    @Test
    fun `guard - capped at three per session, and a cancelled response is never scored`() {
        val (factory, s) = openSession()
        var n = 0
        repeat(3) {
            factory.created("r${++n}"); factory.transcript("r$n", "I've added it."); factory.done("r$n")
            factory.created("r${++n}"); factory.transcript("r$n", "Added it now."); factory.done("r$n")   // follow-up
        }
        assertEquals(3, s.correctives())
        factory.created("r${++n}"); factory.transcript("r$n", "I've saved it again."); factory.done("r$n")
        assertEquals("capped per session", 3, s.correctives())
    }

    @Test
    fun `guard - an interrupted reply is not a claim`() {
        val (factory, s) = openSession()
        val before = s.correctives()
        factory.created("r1")
        factory.transcript("r1", "I've added the dentist")
        factory.done("r1", status = "cancelled")
        assertEquals(before, s.correctives())
        // And a fresh, honest answer afterwards is left alone.
        factory.created("r2"); factory.transcript("r2", "Your dentist is Friday at ten."); factory.done("r2")
        assertEquals(before, s.correctives())
    }

    @Test
    fun `tool calls are deduped across both event shapes and continue with ONE response create`() {
        var runs = 0
        val (factory, s) = openSession { _, _ -> runs++; "ok" }
        factory.created("r1")
        factory.toolCall("create_task", "c1")
        factory.message("""{"type":"response.output_item.done","item":{"type":"function_call","name":"create_task","call_id":"c1","arguments":"{}"}}""")
        awaitToolOutput(s, 1)
        assertEquals(1, runs)
        assertEquals(listOf("conversation.item.create", "response.create"), s.types().takeLast(2))
        // A function call delivered ONLY on output_item.done still executes.
        factory.message("""{"type":"response.output_item.done","item":{"type":"function_call","name":"complete_task","call_id":"c2","arguments":"{}"}}""")
        awaitToolOutput(s, 2)
        assertEquals(2, runs)
    }

    // ── pure guard (mirrors the iOS VoiceIntegrityGuard test) ──

    @Test
    fun `pure guard corrects a claim without a tool once and never loops`() {
        val g = VoiceIntegrityGuard()
        g.responseCreated()
        g.transcriptDelta("I've added the dentist for you.")
        assertTrue(g.shouldCorrect())
        assertEquals(2, g.correctionsLeft)
        g.responseCreated()
        g.transcriptDelta("I've added it now.")
        assertFalse("the correction's own follow-up is never scored", g.shouldCorrect())
        g.toolFinished("create_task", "ok: created task id=1 name=\"x\"")
        g.responseCreated()
        g.transcriptDelta("Done — added it.")
        assertFalse("reply right after a real write tool is tool-backed", g.shouldCorrect())
        g.toolFinished("get_schedule", "ok:\nMonday")
        g.responseCreated()
        g.toolDispatched("get_schedule")
        g.transcriptDelta("Done — moved it.")
        assertTrue("a read-only tool does not make a reply tool-backed", g.shouldCorrect())
        g.responseCreated(); assertFalse(g.shouldCorrect())
        g.responseCreated(); g.transcriptDelta("Done — saved."); assertTrue(g.shouldCorrect())
        g.responseCreated(); assertFalse(g.shouldCorrect())
        assertEquals(0, g.correctionsLeft)
        g.responseCreated(); g.transcriptDelta("Done — saved again."); assertFalse("capped per session", g.shouldCorrect())
        // Barge-in wipes the transcript so a cut-off reply can't be scored.
        g.responseCreated(); g.transcriptDelta("I've added"); g.bargeIn(); assertEquals("", g.transcript)
        assertTrue(VoiceIntegrityGuard.correctiveText.startsWith("(integrity check from the app, not the user:"))
    }

    @Test
    fun `synchronous capture failure stops the client before it dials`() {
        val engine = FakeEngine(app, failCaptureSynchronously = true)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        lateinit var c: VoiceRealtimeClient
        // What the screen does in onCaptureError.
        engine.onCaptureError = { c.stop() }
        c = client(engine, factory, states)
        c.start()
        assertEquals(1, engine.captureStarts)
        assertEquals("never dialled after stop()", 0, factory.dials)
        assertTrue(c.isStopped)
        assertFalse(c.isOpen)
        assertEquals(listOf(VoiceState.CONNECTING, VoiceState.CLOSED), states)
    }

    @Test
    fun `a socket that opens after stop() is closed, not greeted`() {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        val c = client(engine, factory, states)
        c.start()
        assertEquals(1, factory.dials)
        c.stop() // e.g. focus lost / user left while the handshake was in flight
        factory.open()
        val s = factory.socket!!
        assertTrue("late socket closed by onOpen", s.closed)
        assertTrue("no session.update / primer on a dead session", s.sent.isEmpty())
        assertEquals(0, engine.playbackStarts)
        assertFalse(states.contains(VoiceState.LISTENING))
        assertEquals(VoiceState.CLOSED, states.last())
        // The socket's own close/failure callbacks don't paint over CLOSED.
        factory.listener!!.onClosed(s, 1000, "bye")
        factory.listener!!.onFailure(s, RuntimeException("x"), null)
        assertEquals(VoiceState.CLOSED, states.last())
    }

    @Test
    fun `open session greets and reports listening`() {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        val c = client(engine, factory, states)
        c.start()
        factory.open()
        assertTrue(c.isOpen)
        assertEquals(listOf("session.update", "conversation.item.create", "response.create"), factory.socket!!.types())
        assertEquals(1, engine.playbackStarts)
        assertEquals(VoiceState.LISTENING, states.last())
    }

    @Test
    fun `hold-to-talk commits only after the capture drain, and an empty-buffer error is not fatal`() {
        SettingsStore(app).setVoiceHoldToTalk(true)
        val engine = FakeEngine(app)
        assertTrue(engine.holdToTalkPref)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val c = client(engine, factory, states, errors)
        c.start()
        factory.open()
        assertTrue(c.holdToTalk)
        val s = factory.socket!!
        val before = s.sent.size
        c.pttDown()
        assertEquals(listOf(true), engine.gate)
        c.pttUp()
        assertEquals(listOf(true, false), engine.gate)
        assertEquals(VoiceState.THINKING, states.last())
        // Nothing committed yet: the frame read during the press hasn't been appended.
        assertEquals("commit deferred to the capture drain", before, s.sent.size)
        assertEquals(1, engine.drains.size)
        // Capture thread appends the last frame, then runs the drain.
        engine.onFrame!!.invoke(ByteArray(640))
        engine.drains.single().invoke()
        assertEquals(listOf("input_audio_buffer.append", "input_audio_buffer.commit", "response.create"), s.types().drop(before))

        // Too-short press: the server rejects the commit. Hold mode stays usable.
        factory.message("""{"type":"error","error":{"message":"input_audio_buffer commit failed: buffer too small"}}""")
        assertTrue(errors.isEmpty())
        assertFalse(states.contains(VoiceState.ERROR))
        assertEquals(VoiceState.LISTENING, states.last())
        assertTrue(c.isOpen)
        // Whereas a real error still surfaces — and leaves the socket open (the screen keeps the orb).
        factory.message("""{"type":"error","error":{"message":"rate limited"}}""")
        assertEquals(listOf("rate limited"), errors)
        assertEquals(VoiceState.ERROR, states.last())
        assertTrue(c.isOpen)
    }
}
