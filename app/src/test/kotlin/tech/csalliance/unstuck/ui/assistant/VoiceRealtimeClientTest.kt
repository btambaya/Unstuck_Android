package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
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
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.SettingsStore

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
        val sent = mutableListOf<String>()
        var closed = false
        var cancelled = false
        override fun request(): Request = req
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { cancelled = true }
        fun types(): List<String> = sent.map { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "?" }
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

    private fun client(engine: FakeEngine, factory: FakeFactory, states: MutableList<VoiceState>, errors: MutableList<String> = mutableListOf()) =
        VoiceRealtimeClient(
            proxyUrl = "wss://voice.example/ws", token = "t", model = "m", instructions = "i",
            tools = JsonArray(emptyList()), opening = "hello", audio = engine,
            runTool = { _, _ -> "ok" },
            onState = { states += it }, onCaption = { _, _, _ -> }, onError = { errors += it },
            socketFactory = factory,
        )

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
