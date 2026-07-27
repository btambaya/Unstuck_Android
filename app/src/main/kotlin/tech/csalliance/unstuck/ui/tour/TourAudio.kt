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
//    crash.
//
// Round 2:
//  • LIVE CAPTIONS: [caption] is the sentence currently being spoken —
//    tick() maps the active clip's position onto char-weighted sentence
//    spans (TourCaptions.kt). Narration and Tell-me-more clips alike.
//  • TELL-ME-MORE AUDIO: every `more` text has its own Cherry clip
//    (tour_<id>_more.m4a). startMore() pauses the narration and plays the
//    more clip; when it finishes (or the section collapses via stopMore),
//    the interrupted narration resumes — unless the user had deliberately
//    paused it (never force-resume).

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

/** Round-2 #4 handback (pure): only a genuinely-FINISHED narration may
 *  auto-rewind on play(). The shared progress state also tracks the more clip
 *  while it's active, so a stale ≥1 left by a finished more clip must never
 *  restart a mid-step narration from 0:00 on the more→narration hand-back. */
fun tourNarrationShouldRewind(moreActive: Boolean, narrationFinished: Boolean): Boolean =
    !moreActive && narrationFinished

/** Step id → bundled Tell-me-more clip (0 = none — exactly the steps whose
 *  `more` is null; the section still expands, just silently). */
@RawRes
fun tourMoreAudioRes(stepId: String): Int = when (stepId) {
    "welcome" -> R.raw.tour_welcome_more
    "today" -> R.raw.tour_today_more
    "first-action" -> R.raw.tour_first_action_more
    "assistant" -> R.raw.tour_assistant_more
    "focus" -> R.raw.tour_focus_more
    "capture" -> R.raw.tour_capture_more
    "reentry" -> R.raw.tour_reentry_more
    "notifications" -> R.raw.tour_notifications_more
    "finish" -> R.raw.tour_finish_more
    else -> 0
}

/**
 * One narration player (plus, transiently, a Tell-me-more player), reused
 * across steps (load() per step). Compose reads [playing] / [progress] /
 * [available] / [caption] / [moreActive]; the ListenBar drives
 * play/pause/replay and the speed cycle against the ACTIVE clip. All
 * MediaPlayer calls are fail-safe — a broken clip degrades to
 * available=false (Listen hidden), never a crash.
 */
class TourAudioController(private val context: Context) {
    /** The ACTIVE clip (more clip while [moreActive], else narration) is playing. */
    var playing by mutableStateOf(false)
        private set
    /** Progress 0..1 of the ACTIVE clip. */
    var progress by mutableFloatStateOf(0f)
        private set
    /** False when the step has no bundled clip (or it failed to load). */
    var available by mutableStateOf(false)
        private set
    /** The user paused THIS step deliberately — nothing may force-resume it. */
    var userPaused by mutableStateOf(false)
        private set
    /** Round-2 #2: the sentence currently being spoken (active clip). */
    var caption by mutableStateOf<String?>(null)
        private set
    /** Round-2 #3: the Tell-me-more clip is the active clip. */
    var moreActive by mutableStateOf(false)
        private set

    private var mp: MediaPlayer? = null       // narration
    private var moreMp: MediaPlayer? = null   // Tell-me-more clip
    /** The NARRATION clip ran to completion (its own listener) — the ONLY
     *  state allowed to auto-rewind play(). The shared [progress] also tracks
     *  the more clip, so a stale ≥1 from a finished more clip must never
     *  restart the narration from 0:00 on the hand-back (round-2 #4). */
    private var narrationFinished = false
    private var narrationSpans: List<TourSentenceSpan> = emptyList()
    private var moreSpans: List<TourSentenceSpan> = emptyList()
    /** Narration was playing when the more clip interrupted it → resume after. */
    private var resumeAfterMore = false
    private var loadedStep: String? = null
    /** Last requested speed — used when the more clip hands back to narration. */
    private var lastSpeed = 1f

    /** Prepare the clip for a step; optionally auto-start (Listen mode).
     *  [narration] feeds the live-caption sentence spans. */
    fun load(stepId: String, narration: String, autoPlay: Boolean, speed: Float) {
        release()
        loadedStep = stepId
        userPaused = false
        progress = 0f
        lastSpeed = speed
        val res = tourAudioRes(stepId)
        if (res == 0) { available = false; return }
        val player = runCatching { MediaPlayer.create(context, res) }.getOrNull()
        if (player == null) { available = false; return }
        narrationFinished = false
        narrationSpans = tourSentenceSpans(narration)
        player.setOnCompletionListener {
            playing = false
            progress = 1f
            narrationFinished = true
        }
        mp = player
        available = true
        caption = tourCaptionAt(narrationSpans, 0f)
        if (autoPlay) play(speed)
    }

    /** Play/resume the ACTIVE clip (the more clip while it's up, else narration). */
    fun play(speed: Float) {
        lastSpeed = speed
        val player = (if (moreActive) moreMp else mp) ?: return
        userPaused = false
        runCatching {
            // #4: rewind ONLY off the narration's own completion flag — the
            // shared progress may still hold a finished MORE clip's ≥1.
            if (tourNarrationShouldRewind(moreActive, narrationFinished)) {
                player.seekTo(0); progress = 0f; narrationFinished = false
            }
            // setPlaybackParams on a paused player also starts it — exactly
            // what we want here (it's the documented way to set speed).
            player.playbackParams = player.playbackParams.setSpeed(speed)
            if (!player.isPlaying) player.start()
            playing = true
            tick()
        }.onFailure { playing = false; if (!moreActive) available = false }
    }

    /** Pause the ACTIVE clip. Deliberate — nothing may force-resume it. */
    fun pause() {
        userPaused = true
        playing = false
        runCatching { (if (moreActive) moreMp else mp)?.takeIf { it.isPlaying }?.pause() }
    }

    /** Replay the step's NARRATION from the top (dropping any live more clip). */
    fun replay(speed: Float) {
        if (moreActive) endMore(resume = false)
        val player = mp ?: return
        runCatching { player.seekTo(0) }
        progress = 0f
        narrationFinished = false
        caption = tourCaptionAt(narrationSpans, 0f)
        play(speed)
    }

    /**
     * Round-2 #3 — expanding Tell-me-more in Listen mode: pause the narration
     * and play the step's more clip (captioned like narration). Returns false
     * when the step has no bundled more clip (nothing changes). When the clip
     * finishes on its own the interrupted narration resumes.
     */
    fun startMore(stepId: String, moreText: String?, speed: Float): Boolean {
        if (moreActive) endMore(resume = false)
        lastSpeed = speed
        val res = tourMoreAudioRes(stepId)
        if (res == 0) return false
        val player = runCatching { MediaPlayer.create(context, res) }.getOrNull() ?: return false
        // Interrupt the narration; remember whether to resume it afterwards.
        resumeAfterMore = playing
        runCatching { mp?.takeIf { it.isPlaying }?.pause() }
        moreSpans = moreText?.let { tourSentenceSpans(it) } ?: emptyList()
        player.setOnCompletionListener { endMore(resume = true) }
        moreMp = player
        moreActive = true
        progress = 0f
        caption = tourCaptionAt(moreSpans, 0f)
        runCatching {
            player.playbackParams = player.playbackParams.setSpeed(speed)
            if (!player.isPlaying) player.start()
            playing = true
        }.onFailure { endMore(resume = resumeAfterMore) }
        return moreActive
    }

    /** Collapsing Tell-me-more stops its clip; the interrupted narration picks
     *  back up (an early finish — same hand-back as the natural one). */
    fun stopMore() {
        if (moreActive) endMore(resume = true)
    }

    private fun endMore(resume: Boolean) {
        runCatching { moreMp?.stop() }
        runCatching { moreMp?.release() }
        moreMp = null
        moreActive = false
        moreSpans = emptyList()
        playing = false
        if (resume && resumeAfterMore && !userPaused) {
            resumeAfterMore = false
            // #4: the shared progress still holds the finished more clip's ≥1
            // — recompute it from the NARRATION player (moreActive is already
            // false) BEFORE play(), so the bar/caption pick up mid-position
            // and the auto-rewind can never restart the step from 0:00.
            tick()
            play(lastSpeed)   // hands the captions back to the narration spans
        } else {
            resumeAfterMore = false
            // Restore the narration's caption/progress for the idle bar.
            tick()
        }
    }

    /** Apply a new speed live while playing; a paused/stopped player keeps its
     *  position — the new speed applies on the next play() (setting params on
     *  a paused MediaPlayer would force-start it, violating pause-respect). */
    fun setSpeed(speed: Float) {
        lastSpeed = speed
        if (!playing) return
        runCatching { (if (moreActive) moreMp else mp)?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    /** Poll progress + live caption for the ACTIVE clip (the panel runs a
     *  light ticker while playing). */
    fun tick() {
        val player = (if (moreActive) moreMp else mp) ?: return
        runCatching {
            val dur = player.duration
            if (dur > 0) progress = (player.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
            caption = tourCaptionAt(if (moreActive) moreSpans else narrationSpans, progress)
        }
    }

    fun release() {
        runCatching { moreMp?.stop() }
        runCatching { moreMp?.release() }
        moreMp = null
        moreActive = false
        moreSpans = emptyList()
        resumeAfterMore = false
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        playing = false
        available = false
        caption = null
        narrationFinished = false
        narrationSpans = emptyList()
        loadedStep = null
    }
}
