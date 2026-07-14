package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.FocusState
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.LiveSession
import kotlin.math.abs

// FocusTimer — the pure core of the live-session state machine. Port of the
// testable logic in lib/use-focus-timer.ts. The Compose layer owns the
// ticking clock, persistence, sound, and the transient forced "done" state;
// everything here is a pure function of a LiveSession + an injected `now`
// (epoch ms), so it's deterministic and unit-testable like the web hook.

object FocusTimer {

    /** The empty / idle live session (mirrors the web EMPTY default). */
    val empty = LiveSession(
        id = null, taskId = "", sessionStart = null, paused = false, pausedAt = null,
        sessionEstimateMin = 25, nudge80Fired = false, overrunPromptFired = false,
        treatment = FocusTreatment.AMBIENT, priorAccumulatedSec = 0,
    )

    // Derivations

    fun estimateSec(live: LiveSession): Int =
        (if (live.sessionEstimateMin != 0) live.sessionEstimateMin else 25) * 60

    /** Seconds of THIS session (excludes priorAccumulatedSec). Written to the
     *  Session row's actualSec. */
    fun elapsedSec(live: LiveSession, now: Long): Int {
        val start = live.sessionStart ?: return 0
        val elapsedMs = if (live.paused && live.pausedAt != null) live.pausedAt - start else now - start
        return max(0, (elapsedMs / 1000).toInt())
    }

    /** Seconds the UI displays: session elapsed + prior task progress. */
    fun displayedElapsedSec(live: LiveSession, now: Long): Int =
        elapsedSec(live, now) + (live.priorAccumulatedSec ?: 0)

    /** Grace window PAST a shared session's estimate before extra elapsed is treated as
     *  away-time (a backgrounded / orphaned session) and dropped — so a stale wall-clock
     *  resume can't dump hours onto the OWNER's total_focused. Mirrors web's
     *  SHARED_FOCUS_GRACE_SEC (lib/shared-focus.ts). */
    const val SHARED_FOCUS_GRACE_SEC = 10 * 60

    /** Cap a shared session's elapsed seconds before they're reflected onto the OWNER's
     *  task. A recipient's timer is wall-clock (now − sessionStart), so a session left
     *  running for hours would otherwise pour all of it onto the owner. Clamp to the
     *  session estimate + a grace; the overflow is away-time, not focus. Returns 0 for
     *  non-positive input. Pure — mirrors web's clampSharedElapsedSec. */
    fun clampSharedElapsedSec(elapsedSec: Int, estimateMin: Int): Int {
        if (elapsedSec <= 0) return 0
        val est = if (estimateMin > 0) estimateMin else 25
        return minOf(elapsedSec, est * 60 + SHARED_FOCUS_GRACE_SEC)
    }

    /** `overrunGraceSec` is seconds, or Double.POSITIVE_INFINITY to never escalate. */
    fun deriveState(live: LiveSession, now: Long, overrunGraceSec: Double): FocusState {
        if (live.sessionStart == null) return FocusState.IDLE
        if (live.paused) return FocusState.PAUSE
        if (overrunGraceSec == Double.POSITIVE_INFINITY) return FocusState.RUNNING
        val disp = displayedElapsedSec(live, now).toDouble()
        if (disp >= estimateSec(live) + overrunGraceSec) return FocusState.OVERRUN
        return FocusState.RUNNING
    }

    /** Map the PREF_FOCUS_OVERRUN string to grace seconds. null/unknown → 1;
     *  "Never" → ∞; "5 min" → 300; "10 min" → 600. */
    fun overrunGraceSeconds(pref: String?): Double {
        if (pref.isNullOrEmpty()) return 1.0
        return when (pref) {
            "Never" -> Double.POSITIVE_INFINITY
            "5 min" -> 5 * 60.0
            "10 min" -> 10 * 60.0
            else -> 1.0
        }
    }

    // Transitions (pure: current live → next live)

    /** Resume-aware start (the Save-for-later flow):
     *  - same task + paused → resume (shift sessionStart by the pause gap)
     *  - same task + running → no-op (don't reset on a double Start)
     *  - otherwise → fresh session seeded with priorAccumulatedSec */
    fun start(
        cur: LiveSession,
        taskId: String,
        estimateMin: Int? = null,
        priorAccumulatedSec: Int? = null,
        now: Long,
        occurrenceBlockId: String? = null,
        newId: () -> String = ::newUuid,
    ): LiveSession {
        if (cur.sessionStart != null && cur.taskId == taskId && cur.paused) return resume(cur, now)
        if (cur.sessionStart != null && cur.taskId == taskId && !cur.paused) return cur
        return cur.copy(
            id = newId(),
            taskId = taskId,
            sessionStart = now,
            paused = false,
            pausedAt = null,
            sessionEstimateMin = estimateMin ?: 25,
            nudge80Fired = false,
            overrunPromptFired = false,
            priorAccumulatedSec = priorAccumulatedSec ?: 0,
            occurrenceBlockId = occurrenceBlockId,
            // A fresh (own) session always clears any prior shared marker so it can't
            // leak from a displaced recipient-shared session. startSharedFocus re-applies
            // it via copy() after this. resume() keeps it (a paused shared stays shared).
            sharedTitle = null,
            sharedLevel = null,
        )
    }

    fun pause(cur: LiveSession, now: Long): LiveSession {
        if (cur.sessionStart == null) return cur
        return cur.copy(paused = true, pausedAt = now)
    }

    fun resume(cur: LiveSession, now: Long): LiveSession {
        val start = cur.sessionStart ?: return cur
        val pausedDuration = if (cur.pausedAt != null) now - cur.pausedAt else 0L
        return cur.copy(paused = false, pausedAt = null, sessionStart = start + pausedDuration)
    }

    /** Ends the session: clears sessionStart so elapsed resets to 0 (the
     *  Session-row writeback already happened with the pre-done elapsed). The
     *  UI separately forces a transient `done` display state. */
    fun done(cur: LiveSession): LiveSession =
        cur.copy(id = null, sessionStart = null, paused = false, pausedAt = null)

    fun cancel(cur: LiveSession): LiveSession = empty.copy(treatment = cur.treatment)

    fun extend(cur: LiveSession, minutes: Int): LiveSession =
        cur.copy(sessionEstimateMin = cur.sessionEstimateMin + minutes, overrunPromptFired = false)

    fun setTreatment(cur: LiveSession, t: FocusTreatment): LiveSession = cur.copy(treatment = t)
}

private fun max(a: Int, b: Int): Int = if (a > b) a else b

/** Seconds as MM:SS (or -MM:SS for negatives). Port of formatMMSS. */
fun formatMMSS(sec: Int): String {
    val sign = if (sec < 0) "-" else ""
    val a = abs(sec)
    return "$sign${"%02d".format(a / 60)}:${"%02d".format(a % 60)}"
}
