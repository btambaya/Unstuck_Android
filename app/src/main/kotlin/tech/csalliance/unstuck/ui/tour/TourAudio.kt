package tech.csalliance.unstuck.ui.tour

import android.content.Context
import android.media.MediaPlayer
import androidx.annotation.RawRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.csalliance.unstuck.R

// Tour Listen — the bundled Cherry narration (res/raw/tour_*.m4a, the SAME
// clips the web ships) through MediaPlayer + PlaybackParams speed (API 26+,
// pitch preserved by default). Web listen-bar.tsx parity:
//  • auto-plays each NEW step while in Listen mode;
//  • pausing a step never force-resumes THAT step (the next starts fresh);
//  • play / pause / replay; speed cycle 0.75→2 applied live;
//  • a missing/undecodable resource simply hides the Listen bar — never a
//    crash, and the captions (the body text) are always in the panel anyway.

const val TOUR_AUDIO_VOICE = "Cherry"

/** Step id → bundled raw resource (0 = none → Listen hidden for that step).
 *  Explicit map so a typo'd id fails visibly in review, not at runtime. */
@RawRes
fun tourAudioRes(stepId: String): Int = when (stepId) {
    "welcome" -> R.raw.tour_welcome
    "today" -> R.raw.tour_today
    "first-action" -> R.raw.tour_first_action
    "assistant" -> R.raw.tour_assistant
    "focus" -> R.raw.tour_focus
    "capture" -> R.raw.tour_capture
    "reentry" -> R.raw.tour_reentry
    "notifications" -> R.raw.tour_notifications
    "finish" -> R.raw.tour_finish
    "calendar" -> R.raw.tour_calendar
    "captures" -> R.raw.tour_captures
    "collections" -> R.raw.tour_collections
    "sharing" -> R.raw.tour_sharing
    "insights" -> R.raw.tour_insights
    "personalization" -> R.raw.tour_personalization
    else -> 0
}

/**
 * One narration player, reused across steps (load() per step). Compose reads
 * [playing] / [progress] / [available]; the ListenBar drives play/pause/replay
 * and the speed cycle. All MediaPlayer calls are fail-safe — a broken clip
 * degrades to available=false (Listen hidden), never a crash.
 */
class TourAudioController(private val context: Context) {
    var playing by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    /** False when the step has no bundled clip (or it failed to load). */
    var available by mutableStateOf(false)
        private set
    /** The user paused THIS step deliberately — nothing may force-resume it. */
    var userPaused by mutableStateOf(false)
        private set

    private var mp: MediaPlayer? = null
    private var loadedStep: String? = null

    /** Prepare the clip for a step; optionally auto-start (Listen mode). */
    fun load(stepId: String, autoPlay: Boolean, speed: Float) {
        release()
        loadedStep = stepId
        userPaused = false
        progress = 0f
        val res = tourAudioRes(stepId)
        if (res == 0) { available = false; return }
        val player = runCatching { MediaPlayer.create(context, res) }.getOrNull()
        if (player == null) { available = false; return }
        player.setOnCompletionListener {
            playing = false
            progress = 1f
        }
        mp = player
        available = true
        if (autoPlay) play(speed)
    }

    fun play(speed: Float) {
        val player = mp ?: return
        userPaused = false
        runCatching {
            if (progress >= 1f) { player.seekTo(0); progress = 0f }
            // setPlaybackParams on a paused player also starts it — exactly
            // what we want here (it's the documented way to set speed).
            player.playbackParams = player.playbackParams.setSpeed(speed)
            if (!player.isPlaying) player.start()
            playing = true
        }.onFailure { playing = false; available = false }
    }

    fun pause() {
        userPaused = true
        playing = false
        runCatching { mp?.takeIf { it.isPlaying }?.pause() }
    }

    fun replay(speed: Float) {
        val player = mp ?: return
        runCatching { player.seekTo(0) }
        progress = 0f
        play(speed)
    }

    /** Apply a new speed live while playing; a paused/stopped player keeps its
     *  position — the new speed applies on the next play() (setting params on
     *  a paused MediaPlayer would force-start it, violating pause-respect). */
    fun setSpeed(speed: Float) {
        if (!playing) return
        runCatching { mp?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    /** Poll for the progress bar (the panel runs a light ticker while playing). */
    fun tick() {
        val player = mp ?: return
        runCatching {
            val dur = player.duration
            if (dur > 0) progress = (player.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
        }
    }

    fun release() {
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        playing = false
        available = false
        loadedStep = null
    }
}
