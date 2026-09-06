package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

// On-device speech for the assistant — $0 per use (OS frameworks, no cloud API).
//
//  • STT: prefer the fully on-device recognizer (Android 13+, when a language
//    pack is present) so audio never leaves the device; else the standard
//    recognizer with EXTRA_PREFER_OFFLINE (Google's free service). Streams
//    partial text into the input as you speak.
//  • TTS: TextToSpeech, preferring an offline voice. Reads assistant replies.
//    An optional per-utterance completion callback (UtteranceProgressListener)
//    lets a caller sequence work AFTER the engine has actually finished
//    speaking — the Focus Copilot opens its listen window from it so the
//    recognizer never transcribes the coach's own voice.
// Best-effort throughout: if a recognizer/permission/voice is unavailable it
// silently no-ops so the chat stays usable as text-only.

/** The on-device speech surface the Focus Copilot drives. Lets the copilot
 *  controller be unit-tested with a fake (no real TTS/STT engine), and keeps the
 *  copilot decoupled from any concrete engine. Implemented by [VoiceController]. */
interface SpeechSurface {
    val sttAvailable: Boolean
    fun startListening(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onDone: () -> Unit)
    fun stopListening()
    /**
     * Speak [text] (QUEUE_FLUSH — the latest request wins). [onDone] fires ONCE,
     * on the main thread, when THIS utterance has finished (spoken to the end,
     * failed, or could not be spoken at all because the engine is missing or
     * refused it). It is dropped WITHOUT firing when the utterance is
     * superseded by a later speak() or cut by [stopSpeaking] — "done" means the
     * words were actually delivered (or definitively won't be), never
     * "interrupted".
     */
    fun speak(text: String, onDone: (() -> Unit)? = null)
    fun stopSpeaking()
}

class VoiceController(private val context: Context) : SpeechSurface {

    private var recognizer: SpeechRecognizer? = null
    /** The live listen window's onDone — invoked at most once (see [startListening]). */
    private var activeListenDone: (() -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // TTS init is async, so the first speak() of a session lands while the engine
    // is still warming up — buffer it (with its completion) and flush from the
    // init callback.
    private var pendingSpeak: Pair<String, (() -> Unit)?>? = null
    // The utterance currently in flight (id → its completion). QUEUE_FLUSH means
    // there is at most one; a newer speak() replaces it and DROPS the old
    // completion (the interrupted utterance never counts as "done").
    private var utteranceSeq = 0
    private var inFlightId: String? = null
    private var inFlightDone: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    override val sttAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    // --- speech-to-text ---

    /** Start listening. [onPartial] streams live transcript; [onFinal] fires with
     *  the best result; [onDone] always fires when listening ends (ok or error).
     *  Opening a new window while one is live closes the old one AND fires ITS
     *  onDone (a replaced window must never leave its caller waiting). */
    override fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        // Replace, don't strand: the previous window's completion runs so its
        // owner's flags/ducking can never stick on a recognizer that just got
        // destroyed under it.
        val previousDone = activeListenDone
        activeListenDone = null
        stopListening()
        previousDone?.invoke()

        var fired = false
        val done: () -> Unit = {
            if (!fired) { fired = true; activeListenDone = null; onDone() }
        }
        if (!sttAvailable) { done(); return }
        val onDevice = Build.VERSION.SDK_INT >= 33 &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val rec = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: run { done(); return }
        recognizer = rec
        activeListenDone = done

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Prefer offline even on the standard recognizer (best-effort).
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(p: Bundle) {
                p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(p: Bundle) {
                val text = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onFinal(text)
                done()
            }
            override fun onError(error: Int) { done() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { rec.startListening(intent) }.onFailure { done() }
    }

    /** Close the live window WITHOUT firing its onDone — the explicit-cancel
     *  contract (the assistant sheet keeps the partial transcript in the field
     *  instead of auto-sending it). Owners that cancel reset their own state. */
    override fun stopListening() {
        recognizer?.let { runCatching { it.stopListening() }; runCatching { it.destroy() } }
        recognizer = null
        activeListenDone = null
    }

    // --- text-to-speech ---

    override fun speak(text: String, onDone: (() -> Unit)?) {
        if (text.isBlank()) { onDone?.let { main.post(it) }; return }
        // A newer request supersedes whatever was pending/in flight — the old
        // completion is dropped (never fired for an interrupted utterance).
        pendingSpeak = null
        inFlightId = null
        inFlightDone = null
        val engine = tts ?: TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            val queued = pendingSpeak
            pendingSpeak = null
            if (ttsReady) {
                installProgressListener()
                preferOfflineVoice()
                queued?.let { (t, done) -> enqueue(t, done) }
            } else {
                // No engine — nothing will ever be spoken: complete now so a
                // caller sequencing on "done" (the copilot's listen window)
                // isn't left waiting on a fallback timer. Drop the dead engine
                // so the next speak() retries creation instead of buffering
                // into a callback that will never come.
                runCatching { tts?.shutdown() }
                tts = null
                queued?.second?.let { main.post(it) }
            }
        }.also { tts = it }
        if (ttsReady) {
            preferOfflineVoice()
            enqueue(text, onDone, engine)
        } else {
            pendingSpeak = text to onDone // engine still initializing — latest reply wins (QUEUE_FLUSH semantics)
        }
    }

    /** Hand one utterance to a READY engine, tracking its completion. */
    private fun enqueue(text: String, onDone: (() -> Unit)?, engine: TextToSpeech? = tts) {
        val e = engine ?: run { onDone?.let { main.post(it) }; return }
        val id = "assistant-${++utteranceSeq}"
        inFlightId = id
        inFlightDone = onDone
        val result = runCatching { e.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            // Refused outright — no progress callbacks will come for it.
            inFlightId = null
            inFlightDone = null
            onDone?.let { main.post(it) }
        }
    }

    /** UtteranceProgressListener callbacks arrive on a binder thread — settle
     *  the in-flight completion on main, and only for the utterance that is
     *  still current (a flushed one was already dropped by the newer speak()). */
    private fun installProgressListener() {
        val engine = tts ?: return
        runCatching {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = settle(utteranceId, completed = true)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = settle(utteranceId, completed = true)
                override fun onError(utteranceId: String?, errorCode: Int) = settle(utteranceId, completed = true)
                // Interrupted (stopSpeaking / a newer QUEUE_FLUSH): NOT done.
                override fun onStop(utteranceId: String?, interrupted: Boolean) = settle(utteranceId, completed = !interrupted)
            })
        }
    }

    private fun settle(utteranceId: String?, completed: Boolean) {
        main.post {
            if (utteranceId == null || utteranceId != inFlightId) return@post
            val done = inFlightDone
            inFlightId = null
            inFlightDone = null
            if (completed) done?.invoke()
        }
    }

    private fun preferOfflineVoice() {
        val engine = tts ?: return
        runCatching {
            engine.language = Locale.getDefault()
            engine.voices
                ?.firstOrNull { v -> v.locale == Locale.getDefault() && !v.isNetworkConnectionRequired && Voice.QUALITY_NORMAL <= v.quality }
                ?.let { engine.voice = it }
        }
    }

    /** Cut speech. The in-flight completion is DROPPED (an interrupted
     *  utterance is not "done" — the caller asked for silence). */
    override fun stopSpeaking() {
        pendingSpeak = null
        inFlightId = null
        inFlightDone = null
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stopListening()
        pendingSpeak = null
        inFlightId = null
        inFlightDone = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ttsReady = false
    }
}

/** Remember a [VoiceController] tied to the composition; shuts it down on exit. */
@Composable
fun rememberVoiceController(): VoiceController {
    val context = LocalContext.current
    val controller = remember { VoiceController(context) }
    DisposableEffect(Unit) { onDispose { controller.shutdown() } }
    return controller
}
