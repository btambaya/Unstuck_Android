package tech.csalliance.unstuck.ui.assistant

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

// Raw-PCM audio for realtime voice. Capture: 16 kHz mono PCM16 (what Qwen-Omni
// expects), delivered in ~100ms frames. Playback: 24 kHz mono PCM16 streamed
// from a queue (the model's output rate). Caller must hold RECORD_AUDIO before
// startCapture().
//
// HALF-DUPLEX: phone loudspeakers leak the model's own voice back into the mic;
// the realtime server's VAD then hears that as the user "interrupting", cuts the
// sentence, and starts a new turn — a self-feedback loop. So while the model is
// actively playing (plus a short tail to cover trailing echo) we DON'T forward
// mic frames upstream. Reinforced with hardware AEC/NS/AGC when available.
class VoiceAudioEngine {
    companion object {
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        // Keep the mic muted for this long after the last audio was written, so
        // the tail of the model's speech (still ringing out the speaker) can't
        // trip the server VAD.
        const val OUTPUT_TAIL_MS = 400L
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
        capturing = true
        runCatching { rec.startRecording() }
        captureThread = thread(name = "voice-capture") {
            val buf = ByteArray(frameBytes)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                // Drop frames while the model is speaking (anti-echo half-duplex).
                if (n > 0 && !outputBusy()) onFrame(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
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

    fun shutdown() { stopCapture(); stopPlayback() }
}
