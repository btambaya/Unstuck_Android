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
import tech.csalliance.unstuck.SettingsStore
import tech.csalliance.unstuck.core.logic.BargeInController
import tech.csalliance.unstuck.core.logic.BargeInProfile
import tech.csalliance.unstuck.core.logic.RmsGate
import tech.csalliance.unstuck.core.logic.VoiceRoute
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Raw-PCM audio for realtime voice. Capture: 16 kHz mono PCM16 (what Qwen-Omni
// expects), delivered in ~100ms frames. Playback: 24 kHz mono PCM16 streamed
// from a queue (the model's output rate). Caller must hold RECORD_AUDIO before
// startCapture().
//
// ECHO / BARGE-IN STRATEGY (spec: bargein.md §3–§5; pure logic in :core BargeIn.kt)
// 1. Communication mode: we put AudioManager into MODE_IN_COMMUNICATION, take
//    transient-exclusive audio focus, and route to a communication device
//    BEFORE creating the streams. This is what designates our AudioTrack as the
//    call "downlink", giving the hardware AcousticEchoCanceler an actual echo
//    reference — without it, VOICE_COMMUNICATION + AEC are a near no-op on a
//    loudspeaker. Reverted fully on shutdown (global audio policy).
// 2. RMS noise gate in the capture thread (RmsGate, 20 ms sub-frames): calibrated
//    floor, hysteresis, 300 ms pre-roll, DIGITAL SILENCE while closed. Gate
//    open/close are barge-in events for the controller (duck → confirm → cancel).
// 3. Route profiles: headset/BT = LOW_ECHO; built-in speaker = SPEAKER "assisted
//    duplex" — frames still flow through the gate while the model plays, at a
//    +9 dB margin, so the server VAD can barge in. The old hard half-duplex
//    (drop mic frames while playing) stays available as the SPEAKER_HALF_DUPLEX
//    fallback profile for devices whose AEC leaves enough echo to self-trigger.
// 4. Playback drain is measured from the track's playbackHeadPosition (frames
//    written − head ≤ 0, then a 300 ms tail) — NOT from write() returning, which
//    only means "buffered". That is the playback_drained event.
// 5. Duck = track.setVolume(0.25) (-12 dB) with a short ramp; restore = 1.0.
class VoiceAudioEngine(private val context: Context) {
    companion object {
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        /** Extra time after the head position catches up with the frames written
         *  before we call playback drained (room decay + head-position granularity). */
        const val OUTPUT_TAIL_MS = 300L
        private const val SUB_FRAME_BYTES = RmsGate.SUB_FRAME_SAMPLES * 2 // 640
        /** A margin no real signal reaches — keeps the gate shut (but pre-rolling) in hold-to-talk. */
        private const val NEVER_OPEN_MARGIN_DB = 1_000.0
        // Output routes that are worn/close-coupled → no speaker→mic echo.
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

    /** Settings → voice.holdToTalk (default off); read once per engine (= per session). */
    val holdToTalkPref: Boolean = runCatching { SettingsStore(context).voiceHoldToTalk() }.getOrDefault(false)

    // Session-level callbacks, set by the owner of the engine (voice screen / client).
    /** Another app (most importantly telephony) took audio focus — end the session. */
    @Volatile var onFocusLost: (() -> Unit)? = null
    /** Mic capture couldn't start or died mid-session (mic held elsewhere). */
    @Volatile var onCaptureError: (() -> Unit)? = null
    /** Output route changed mid-session (headset plugged/unplugged) — re-profile. Main thread. */
    @Volatile var onRouteChanged: ((VoiceRoute) -> Unit)? = null
    /** RMS gate opened (user energy above floor+margin) / closed. Capture thread. */
    @Volatile var onGateOpen: (() -> Unit)? = null
    @Volatile var onGateClose: (() -> Unit)? = null
    /** All written audio has actually left the speaker (+ tail). Playback thread. */
    @Volatile var onPlaybackDrained: (() -> Unit)? = null

    // Barge-in inputs owned by the client (set from the controller after each event).
    /** Current route profile — gate margin + assisted-duplex flag for the capture path. */
    @Volatile var profile: BargeInProfile = BargeInProfile.SPEAKER
    /** A reply is in flight (blocks gate floor adaptation). */
    @Volatile var responseActive = false

    // ── communication mode + routing ──
    @Volatile private var commActive = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null
    // true ⇒ output is the built-in speaker (echo risk).
    @Volatile var echoProne = true
        private set
    val route: VoiceRoute get() = if (echoProne) VoiceRoute.SPEAKER else VoiceRoute.LOW_ECHO

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
        applyRoute(initial = true)
        registerRouteCallback()
        return true
    }

    // Prefer a connected headset; else hands-free on the built-in speaker.
    private fun applyRoute(initial: Boolean = false) {
        val before = echoProne
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
        if (!initial && before != echoProne) {
            recalibrate = true // the mic's acoustic floor changed with the route
            onRouteChanged?.invoke(route)
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
    @Volatile private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    // Gate control flags, written by the owner, consumed on the capture thread
    // (the RmsGate itself is only ever touched there).
    @Volatile private var recalibrate = false
    @Volatile private var gateForced = false
    /** Hold-to-talk: when true, frames are appended ONLY while the orb is pressed. */
    @Volatile var holdToTalk = false
    @Volatile private var pttPressed = false

    /** Hold-to-talk press/release: force the gate open (flushes the 300 ms pre-roll)
     *  or release it. While released in hold mode nothing is appended at all. */
    fun forceGate(open: Boolean) { pttPressed = open; gateForced = open }

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
            val sub = ShortArray(RmsGate.SUB_FRAME_SAMPLES)
            val gate = RmsGate()
            // Worst case per 100 ms read: 5 live sub-frames + the 300 ms pre-roll.
            val out = ByteBuffer.allocate((RmsGate.PRE_ROLL_SUB_FRAMES + 6) * SUB_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { // recorder died (e.g. mic stolen) — fatal, don't spin
                    if (capturing) onCaptureError?.invoke()
                    break
                }
                if (n <= 0) continue
                if (recalibrate) { recalibrate = false; gate.reset() }
                if (gate.forcedOpen != gateForced) gate.forceOpen(gateForced)

                val prof = profile
                val playing = playbackQueued()
                // Hard half-duplex fallback profile: drop mic frames while the model
                // plays (+tail) and swallow gate events — residual echo must reach
                // neither the server nor the barge-in controller on this route.
                val dropForEcho = !prof.assistedDuplex && echoProne && playing
                // Hold-to-talk while released: append nothing (null VAD needs no
                // silence) and never let energy open the gate — the press does that,
                // flushing the pre-roll the gate keeps filling meanwhile.
                val dropReleased = holdToTalk && !pttPressed
                val margin = if (dropReleased) NEVER_OPEN_MARGIN_DB else prof.gateMargin(playing)
                val respActive = responseActive

                out.clear()
                var opened = false
                var closed = false
                var off = 0
                while (off + SUB_FRAME_BYTES <= n) {
                    ByteBuffer.wrap(buf, off, SUB_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sub)
                    val g = gate.process(sub, margin, playing, respActive)
                    // Serialize NOW: an open gate hands back `sub` itself, which the
                    // next iteration overwrites (pre-roll entries are private copies).
                    for (f in g.emit) for (s in f) out.putShort(s)
                    opened = opened || g.opened
                    closed = closed || g.closed
                    off += SUB_FRAME_BYTES
                }
                if (dropForEcho || dropReleased) continue
                if (!holdToTalk) {
                    if (opened) onGateOpen?.invoke()
                    if (closed) onGateClose?.invoke()
                }
                if (out.position() == 0) continue // still calibrating (first 500 ms)
                onFrame(out.array().copyOf(out.position()))
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
    @Volatile private var track: AudioTrack? = null
    // Guards all AudioTrack control-vs-write contention: the playback thread writes
    // inside this lock while the WS reader thread (flushPlayback) pauses/flushes/plays
    // inside it too. Concurrent control + write() on one AudioTrack is undefined.
    private val trackLock = Any()
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var playing = false
    private var playThread: Thread? = null
    private val poison = ByteArray(0)
    // Drain tracking. framesWritten/headCaughtUpAt are playback-thread state
    // (framesWritten also reset under trackLock by flushPlayback).
    private var framesWritten = 0L
    @Volatile private var lastWriteMs = 0L
    /** Audio was enqueued/written since the last drain or flush — a drain is owed. */
    @Volatile private var drainPending = false
    private var headCaughtUpAt = 0L
    private var bufferMs = 500L
    @Volatile private var gainTarget = 1f

    /** Model audio still queued, buffered in the track, or inside the post-drain tail. */
    fun playbackQueued(): Boolean = playing && (queue.isNotEmpty() || drainPending)

    /** True while the model's audio is (or just was) playing — gates the mic on the
     *  hard half-duplex fallback profile. */
    fun outputBusy(): Boolean = playbackQueued()

    fun startPlayback() {
        if (playing) return
        if (!enterCommMode()) return // capture's bail surfaces the error
        val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, OUT_RATE) // ~0.5s headroom
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
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrNull() ?: return
        bufferMs = bufBytes * 1000L / (OUT_RATE * 2)
        track = t
        playing = true
        framesWritten = 0L
        drainPending = false
        headCaughtUpAt = 0L
        gainTarget = 1f
        runCatching { t.setVolume(1f) }
        runCatching { t.play() }
        playThread = thread(name = "voice-playback") {
            while (playing) {
                val chunk = try { queue.poll(20, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { break }
                if (chunk == null) { checkDrained(t); continue }
                if (chunk === poison || !playing) continue
                var off = 0
                while (off < chunk.size && playing) {
                    // Serialize each write against flush/pause/play from the WS reader
                    // thread (block-level lock, not held across queue.poll, so a barge-in
                    // flush isn't blocked waiting for the next chunk).
                    val w = synchronized(trackLock) {
                        if (!playing) -1
                        else t.write(chunk, off, chunk.size - off).also { if (it > 0) framesWritten += it / 2 }
                    }
                    if (w <= 0) break
                    off += w
                    lastWriteMs = SystemClock.uptimeMillis()
                    drainPending = true
                    headCaughtUpAt = 0L
                }
            }
        }
    }

    /** Playback thread, queue idle: has everything written actually been played?
     *  Head position is the truth; a wall-clock fallback (buffer + tail since the
     *  last write) covers devices whose head stalls a few frames short. */
    private fun checkDrained(t: AudioTrack) {
        if (!drainPending || queue.isNotEmpty()) return
        val now = SystemClock.uptimeMillis()
        val caughtUp = synchronized(trackLock) {
            if (!playing) return
            val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }.getOrDefault(0L)
            head >= framesWritten || now - lastWriteMs > bufferMs + OUTPUT_TAIL_MS
        }
        if (!caughtUp) { headCaughtUpAt = 0L; return }
        if (headCaughtUpAt == 0L) { headCaughtUpAt = now; return }
        if (now - headCaughtUpAt >= OUTPUT_TAIL_MS) {
            drainPending = false
            headCaughtUpAt = 0L
            onPlaybackDrained?.invoke()
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (playing && pcm.isNotEmpty()) { drainPending = true; queue.offer(pcm) }
    }

    /** Barge-in: drop queued audio + cut current playback immediately. Called from the
     *  WS reader thread; serialized against the playback thread's write() via trackLock. */
    fun flushPlayback() {
        queue.clear()
        synchronized(trackLock) {
            // flush() resets the head position to 0 — restart our frame count with it.
            track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.play() } }
            framesWritten = 0L
            drainPending = false
            headCaughtUpAt = 0L
        }
    }

    /** Duck the reply to -12 dB (×0.25) while a possible barge-in is confirmed. */
    fun duck() = rampGain(BargeInController.DUCK_GAIN, BargeInController.DUCK_RAMP_MS)

    /** Back to unity for the rest of this reply / the next one. */
    fun restore() = rampGain(1f, BargeInController.RESTORE_RAMP_MS)

    // AudioTrack has no built-in ramp; 4 evenly spaced steps on the main handler
    // (setVolume is thread-safe). A newer target cancels an in-flight ramp.
    private fun rampGain(target: Float, ms: Long) {
        val t = track ?: return
        val from = gainTarget
        if (from == target) return
        gainTarget = target
        val steps = 4
        for (i in 1..steps) {
            val v = from + (target - from) * i / steps
            mainHandler.postDelayed({ if (gainTarget == target && track === t) runCatching { t.setVolume(v) } }, ms * i / steps)
        }
    }

    fun stopPlayback() {
        playing = false
        queue.offer(poison)
        playThread?.interrupt(); playThread?.join(300); playThread = null
        synchronized(trackLock) {
            track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.release() } }
            track = null
            drainPending = false
        }
        queue.clear()
    }

    fun shutdown() { stopCapture(); stopPlayback(); exitCommMode() }
}
