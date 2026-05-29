package tech.csalliance.unstuck.surface

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import tech.csalliance.unstuck.R

// Looping ambient track for the Focus "ambient" treatment. Soft brown-noise
// loop (res/raw/ambient_focus.wav), played at low volume. Analog of the iOS
// AmbientAudio. Idempotent start/stop.
object AmbientAudio {
    private var player: MediaPlayer? = null

    fun start(context: Context) {
        if (player != null) return
        player = MediaPlayer.create(context.applicationContext, R.raw.ambient_focus)?.apply {
            isLooping = true
            setVolume(0.45f, 0.45f)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            start()
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }
}
