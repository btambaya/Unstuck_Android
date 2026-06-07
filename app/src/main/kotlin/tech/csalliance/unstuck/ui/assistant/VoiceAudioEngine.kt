package tech.csalliance.unstuck.ui.assistant

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

// Raw-PCM audio for realtime voice. Capture: 16 kHz mono PCM16 (what Qwen-Omni
// expects), delivered in ~100ms frames. Playback: 24 kHz mono PCM16 streamed
// from a queue (the model's output rate), with a flush for barge-in. No codecs,
// no files — just the mic in and the model's voice out. Caller must hold
// RECORD_AUDIO before startCapture().
class VoiceAudioEngine {
    companion object { const val IN_RATE = 16_000; const val OUT_RATE = 24_000 }

    // ── capture ──
    private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

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
        capturing = true
        runCatching { rec.startRecording() }
        captureThread = thread(name = "voice-capture") {
            val buf = ByteArray(frameBytes)
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onFrame(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
            }
        }
    }

    fun stopCapture() {
        capturing = false
        captureThread?.join(300); captureThread = null
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
    }

    // ── playback ──
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var playing = false
    private var playThread: Thread? = null
    private val poison = ByteArray(0)

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
                }
            }
        }
    }

    fun enqueue(pcm: ByteArray) { if (playing && pcm.isNotEmpty()) queue.offer(pcm) }

    /** Barge-in: drop queued audio + cut current playback immediately. */
    fun flushPlayback() {
        queue.clear()
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
