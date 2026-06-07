package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

// On-device speech for the assistant — $0 per use (OS frameworks, no cloud API).
//
//  • STT: prefer the fully on-device recognizer (Android 13+, when a language
//    pack is present) so audio never leaves the device; else the standard
//    recognizer with EXTRA_PREFER_OFFLINE (Google's free service). Streams
//    partial text into the input as you speak.
//  • TTS: TextToSpeech, preferring an offline voice. Reads assistant replies.
// Best-effort throughout: if a recognizer/permission/voice is unavailable it
// silently no-ops so the chat stays usable as text-only.

class VoiceController(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    val sttAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    // --- speech-to-text ---

    /** Start listening. [onPartial] streams live transcript; [onFinal] fires with
     *  the best result; [onDone] always fires when listening ends (ok or error). */
    fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        stopListening()
        if (!sttAvailable) { onDone(); return }
        val onDevice = Build.VERSION.SDK_INT >= 33 &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val rec = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: run { onDone(); return }
        recognizer = rec

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
                onDone()
            }
            override fun onError(error: Int) { onDone() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { rec.startListening(intent) }.onFailure { onDone() }
    }

    fun stopListening() {
        recognizer?.let { runCatching { it.stopListening() }; runCatching { it.destroy() } }
        recognizer = null
    }

    // --- text-to-speech ---

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) preferOfflineVoice()
        }.also { tts = it }
        if (ttsReady) {
            preferOfflineVoice()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant")
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

    fun stopSpeaking() { runCatching { tts?.stop() } }

    fun shutdown() {
        stopListening()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
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
