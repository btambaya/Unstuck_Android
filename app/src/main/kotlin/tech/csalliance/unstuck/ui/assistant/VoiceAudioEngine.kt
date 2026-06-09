package tech.csalliance.unstuck.ui.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

// Raw-PCM audio for realtime voice. Capture: 16 kHz mono PCM16 (what Qwen-Omni
// expects), delivered in ~100ms frames. Playback: 24 kHz mono PCM16 streamed
// from a queue (the model's output rate). Caller must hold RECORD_AUDIO before
// startCapture().
//
// ECHO / BARGE-IN STRATEGY
// 1. Communication mode: we put AudioManager into MODE_IN_COMMUNICATION, take
//    transient-exclusive audio focus, and route to a communication device
//    BEFORE creating the streams. This is what designates our AudioTrack as the
//    call "downlink", giving the hardware AcousticEchoCanceler an actual echo
//    reference — without it, VOICE_COMMUNICATION + AEC are a near no-op on a
//    loudspeaker. Reverted fully on shutdown (global audio policy).
// 2. Route-aware duplex: on a headset/BT (no acoustic echo) we run FULL-DUPLEX —
//    mic frames flow even while the model speaks, so the server VAD gives true
//    talk-over barge-in. On the built-in speaker we stay HALF-DUPLEX (drop mic
//    frames while playing, +tail) because residual echo would still self-trigger
//    the VAD; the manual Interrupt button covers that case.
class VoiceAudioEngine(private val context: Context) {
    companion object {
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        // Keep the mic muted for this long after the last audio was written, so
        // the tail of the model's speech (still ringing out the speaker) can't
        // trip the server VAD. Only used on echo-prone (loudspeaker) routes.
        const val OUTPUT_TAIL_MS = 400L
        // Output routes that are worn/close-coupled → no speaker→mic echo →
        // safe to run full-duplex barge-in.
        private val HEADSET_OUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Session-level callbacks, set by the owner of the engine (voice screen).
    /** Another app (most importantly telephony) took audio focus — end the session. */
    @Volatile var onFocusLost: (() -> Unit)? = null
    /** Mic capture couldn't start or died mid-session (mic held elsewhere). */
    @Volatile var onCaptureError: (() -> Unit)? = null

    // ── communication mode + routing ──
    @Volatile private var commActive = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null
    // true ⇒ output is the built-in speaker (echo risk) ⇒ run half-duplex.
    @Volatile var echoProne = true
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onFocusLost?.invoke()
        }
    }

    /** @return false when audio focus was denied (e.g. an active phone call) — don't start streams. */
    private fun enterCommMode(): Boolean {
        if (commActive) return true
        commActive = true
        runCatching { savedMode = am.mode; am.mode = AudioManager.MODE_IN_COMMUNICATION }
        val granted = runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
            focusRequest = fr
            am.requestAudioFocus(fr) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }.getOrDefault(false)
        if (!granted) { exitCommMode(); return false }
        applyRoute()
        registerRouteCallback()
        return true
    }

    // Prefer a connected headset; else hands-free on the built-in speaker.
    private fun applyRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val devices = am.availableCommunicationDevices
                val headset = devices.firstOrNull { it.type in HEADSET_OUT_TYPES }
                val target = headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (target != null) am.setCommunicationDevice(target)
                echoProne = target == null || target.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                val onHeadset = am.isWiredHeadsetOn || am.isBluetoothScoOn || am.isBluetoothA2dpOn
                if (!onHeadset) am.isSpeakerphoneOn = true
                echoProne = !onHeadset
            }
        }
    }

    private fun registerRouteCallback() {
        if (deviceCallback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { if (commActive) applyRoute() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { if (commActive) applyRoute() }
        }
        runCatching { am.registerAudioDeviceCallback(cb, mainHandler) }
        deviceCallback = cb
    }

    private fun exitCommMode() {
        if (!commActive) return
        commActive = false
        deviceCallback?.let { runCatching { am.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am.clearCommunicationDevice() }
        else @Suppress("DEPRECATION") runCatching { am.isSpeakerphoneOn = false }
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
        runCatching { am.mode = savedMode }
        echoProne = true
    }

    // ── capture ──
    private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun startCapture(onFrame: (ByteArray) -> Unit) {
        if (capturing) return
        if (!enterCommMode()) { onCaptureError?.invoke(); return }
        val frameBytes = IN_RATE / 10 * 2 // 100ms mono pcm16 = 3200 bytes
        val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameBytes * 2),
            )
        }.getOrNull() ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) { runCatching { rec.release() }; return }
        record = rec
        enableEffects(rec.audioSessionId)
        runCatching { rec.startRecording() }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // Mic is held by another app (or start failed): bail instead of letting
            // the read loop spin on error codes at full CPU.
            releaseEffects()
            runCatching { rec.release() }
            record = null
            onCaptureError?.invoke()
            return
        }
        capturing = true
        captureThread = thread(name = "voice-capture") {
            val buf = ByteArray(frameBytes)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { // recorder died (e.g. mic stolen) — fatal, don't spin
                    if (capturing) onCaptureError?.invoke()
                    break
                }
                // Half-duplex ONLY on an echo-prone (loudspeaker) route. On a
                // headset we always forward → true server-VAD barge-in.
                if (n > 0 && !(echoProne && outputBusy())) onFrame(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
            }
        }
    }

    // Best-effort platform DSP: echo cancellation, noise suppression, auto gain.
    private fun enableEffects(sessionId: Int) {
        runCatching { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }
        runCatching { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true } }
        runCatching { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true } }
    }

    private fun releaseEffects() {
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
        runCatching { agc?.release() }; agc = null
    }

    fun stopCapture() {
        capturing = false
        captureThread?.join(300); captureThread = null
        releaseEffects()
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
    }

    // ── playback ──
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var playing = false
    @Volatile private var lastOutputMs = 0L
    private var playThread: Thread? = null
    private val poison = ByteArray(0)

    /** True while the model's audio is (or just was) playing — used to gate the mic. */
    fun outputBusy(): Boolean {
        if (!playing) return false
        if (queue.isNotEmpty()) return true
        return SystemClock.uptimeMillis() - lastOutputMs < OUTPUT_TAIL_MS
    }

    fun startPlayback() {
        if (playing) return
        if (!enterCommMode()) return // capture's bail surfaces the error
        val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, OUT_RATE)) // ~0.5s headroom
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        track = t
        playing = true
        runCatching { t.play() }
        playThread = thread(name = "voice-playback") {
            while (playing) {
                val chunk = try { queue.take() } catch (e: InterruptedException) { break }
                if (chunk === poison || !playing) continue
                var off = 0
                while (off < chunk.size && playing) {
                    val w = t.write(chunk, off, chunk.size - off)
                    if (w <= 0) break
                    off += w
                    lastOutputMs = SystemClock.uptimeMillis()
                }
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (playing && pcm.isNotEmpty()) { lastOutputMs = SystemClock.uptimeMillis(); queue.offer(pcm) }
    }

    /** Barge-in: drop queued audio + cut current playback immediately. */
    fun flushPlayback() {
        queue.clear()
        lastOutputMs = 0L
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.play() } }
    }

    fun stopPlayback() {
        playing = false
        queue.offer(poison)
        playThread?.interrupt(); playThread?.join(300); playThread = null
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.release() } }
        track = null
        queue.clear()
    }

    fun shutdown() { stopCapture(); stopPlayback(); exitCommMode() }
}
