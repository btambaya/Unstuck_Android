package tech.csalliance.unstuck.ui.assistant

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

// Realtime voice client for Qwen-Omni (via the Cloudflare proxy). Streams mic
// PCM16/16k up, plays the model's PCM16/24k speech back, shows live captions,
// and runs the agent's tool calls through the SAME dispatcher as text mode.
// Protocol verified against the live DashScope endpoint:
//   session.update {modalities,instructions,input/output_audio_format:pcm16,
//                   turn_detection:server_vad, tools, tool_choice}
//   client → input_audio_buffer.append {audio: base64}
//   server → response.audio.delta {delta: base64}  (24k speech)
//          → response.audio_transcript.delta {delta}  (captions)
//          → input_audio_buffer.speech_started  (→ barge-in)
//          → response.function_call_arguments.done {name, call_id, arguments}
//   client → conversation.item.create {function_call_output, call_id, output}
//          → response.create

enum class VoiceState { CONNECTING, LISTENING, SPEAKING, ERROR, CLOSED }

class VoiceRealtimeClient(
    private val proxyUrl: String,          // wss://…workers.dev   (token added per-connect)
    private val token: String,             // Supabase access token (Worker validates it)
    private val model: String,
    private val instructions: String,      // system prompt + live context
    private val tools: JsonArray,          // tool schemas (OpenAI/DashScope shape)
    private val audio: VoiceAudioEngine,
    private val runTool: suspend (name: String, args: JsonObject) -> String,
    private val onState: (VoiceState) -> Unit,
    private val onCaption: (role: String, text: String, done: Boolean) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var ws: WebSocket? = null
    @Volatile private var open = false

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        .build()

    fun start() {
        onState(VoiceState.CONNECTING)
        val req = Request.Builder()
            .url(proxyUrl.replace(Regex("\\?.*$"), "") + "?model=" + model)
            .header("Authorization", "Bearer $token")
            .build()
        ws = http.newWebSocket(req, listener)
    }

    fun stop() {
        open = false
        audio.shutdown()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        onState(VoiceState.CLOSED)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            open = true
            webSocket.send(sessionUpdate())
            audio.startPlayback()
            audio.startCapture { frame ->
                if (!open) return@startCapture
                webSocket.send(buildJsonObject {
                    put("type", "input_audio_buffer.append")
                    put("audio", Base64.encodeToString(frame, Base64.NO_WRAP))
                }.toString())
            }
            onState(VoiceState.LISTENING)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val ev = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "input_audio_buffer.speech_started" -> { audio.flushPlayback(); onState(VoiceState.LISTENING) }
                "response.audio.delta" -> {
                    ev["delta"]?.jsonPrimitive?.contentOrNull?.let {
                        audio.enqueue(Base64.decode(it, Base64.NO_WRAP)); onState(VoiceState.SPEAKING)
                    }
                }
                "response.audio_transcript.delta" ->
                    ev["delta"]?.jsonPrimitive?.contentOrNull?.let { onCaption("assistant", it, false) }
                "response.audio_transcript.done" -> onCaption("assistant", "", true)
                "conversation.item.input_audio_transcription.completed" ->
                    ev["transcript"]?.jsonPrimitive?.contentOrNull?.let { onCaption("user", it, true) }
                "response.audio.done", "response.done" -> onState(VoiceState.LISTENING)
                "response.function_call_arguments.done" -> handleToolCall(webSocket, ev)
                "error" -> {
                    val m = ev["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: ev["error"]?.jsonPrimitive?.contentOrNull
                    if (!m.isNullOrBlank()) onError(m.take(160)); onState(VoiceState.ERROR)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open = false; audio.shutdown()
            val code = response?.code
            val body = runCatching { response?.body?.string() }.getOrNull()
            val msg = when {
                !body.isNullOrBlank() -> body.take(160)
                code != null -> "Voice server error (HTTP $code)"
                else -> t.message?.take(160) ?: "Couldn't reach the voice server"
            }
            onError(msg); onState(VoiceState.ERROR)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false; audio.shutdown(); onState(VoiceState.CLOSED)
        }
    }

    private fun handleToolCall(webSocket: WebSocket, ev: JsonObject) {
        val name = ev["name"]?.jsonPrimitive?.contentOrNull ?: return
        val callId = ev["call_id"]?.jsonPrimitive?.contentOrNull ?: return
        val args = runCatching {
            Json.parseToJsonElement(ev["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}").jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        scope.launch {
            val result = runCatching { runTool(name, args) }.getOrElse { "error: ${it.message ?: "failed"}" }
            // Feed the tool result back + ask the model to continue (speak).
            webSocket.send(buildJsonObject {
                put("type", "conversation.item.create")
                putJsonObject("item") {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result)
                }
            }.toString())
            webSocket.send(buildJsonObject { put("type", "response.create") }.toString())
        }
    }

    private fun sessionUpdate(): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            putJsonArray("modalities") { add("text"); add("audio") }
            put("instructions", instructions)
            put("input_audio_format", "pcm16")
            put("output_audio_format", "pcm16")
            putJsonObject("turn_detection") { put("type", "server_vad") }
            put("tools", tools)
            put("tool_choice", "auto")
        }
    }.toString()
}
