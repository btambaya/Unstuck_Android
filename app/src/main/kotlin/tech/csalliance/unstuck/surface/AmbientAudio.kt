package tech.csalliance.unstuck.surface

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import tech.csalliance.unstuck.R

// Looping ambient track for the Focus "ambient" treatment. Soft brown-noise
// loop (res/raw/ambient_focus.wav), played at low volume. Analog of the iOS
// AmbientAudio. Idempotent start/stop. Holds audio focus so it pauses for
// phone calls / other apps' playback and resumes when focus comes back.
object AmbientAudio {
    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // Pause when another app (most importantly telephony) takes focus, resume on
    // regain. Transient ducking (CAN_DUCK) is auto-handled by the system on O+.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                runCatching { player?.takeIf { it.isPlaying }?.pause() }
            AudioManager.AUDIOFOCUS_GAIN ->
                runCatching { player?.takeIf { !it.isPlaying }?.start() }
        }
    }

    fun start(context: Context) {
        if (player != null) return
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()
        if (am.requestAudioFocus(fr) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return // e.g. mid-call
        audioManager = am
        focusRequest = fr
        player = MediaPlayer.create(app, R.raw.ambient_focus)?.apply {
            isLooping = true
            setVolume(0.45f, 0.45f)
            setAudioAttributes(attrs)
            start()
        }
        if (player == null) abandonFocus()
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        abandonFocus()
    }

    private fun abandonFocus() {
        val am = audioManager
        val fr = focusRequest
        if (am != null && fr != null) runCatching { am.abandonAudioFocusRequest(fr) }
        audioManager = null
        focusRequest = null
    }
}
