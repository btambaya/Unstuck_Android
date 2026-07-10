package tech.csalliance.unstuck.core.logic

// Pure transition for the "start / finish" pings sent to the people you've shared
// a task with — the View-level promise ("they get notified when you start &
// finish it"). 1:1 port of the reducer in lib/use-share-session-signals.ts.
//
// The observer (AppViewModel) feeds it the live focus session's SESSION id + the
// shared taskId; the reducer decides what to announce:
//  • Keyed on the SESSION id, so a genuinely-new session is told apart from a
//    mid-session reload — the reloaded session is "adopted" and its start is
//    never re-announced (but its end still fires).
//  • Shared-status is an input, so if share badges resolve AFTER a session began
//    (RPC still in flight at start), the start still fires once they load.
//  • Pausing is not an edge (sid + startedTask stay set through a pause); only a
//    real start, a task switch, or done/cancel move the needle.

/** Immutable transition state carried across observations. Mirrors the web SigState. */
data class SigState(
    val inited: Boolean = false,
    val adoptedSid: String? = null,   // session alive at mount (reload) — never announce its start
    val curSid: String? = null,       // session id currently tracked
    val startedTask: String? = null,  // shared taskId we announced a start for (pairs the end)
)

fun initSigState(): SigState = SigState()

enum class SessionSignalKind { START, END }

/** One start/end ping to fire for [taskId]. Mirrors the web SigFire. */
data class SigFire(val kind: SessionSignalKind, val taskId: String)

/** The reducer result: the next state + any signals to fire. */
data class SigStep(val state: SigState, val fires: List<SigFire>)

/** Pure transition: given the prior state and the current (session id, shared
 *  taskId), return the next state + any start/end signals to fire. */
fun sessionSignalStep(s: SigState, sid: String?, shared: String?): SigStep {
    val fires = mutableListOf<SigFire>()

    // First observation: adopt the current session without announcing a start (a
    // reload mid-session must not re-fire). Remember it (if shared) for its end.
    if (!s.inited) {
        return SigStep(SigState(inited = true, adoptedSid = sid, curSid = sid, startedTask = shared), fires)
    }

    // Session boundary: end the previous, maybe start the new.
    if (sid != s.curSid) {
        if (s.startedTask != null) fires.add(SigFire(SessionSignalKind.END, s.startedTask))
        var startedTask: String? = null
        if (shared != null) {
            if (sid != s.adoptedSid) fires.add(SigFire(SessionSignalKind.START, shared))
            startedTask = shared   // track for the future end either way
        }
        return SigStep(s.copy(curSid = sid, startedTask = startedTask), fires)
    }

    // Same session continuing — badges may have just resolved.
    if (sid != null && s.startedTask == null && shared != null) {
        if (sid != s.adoptedSid) fires.add(SigFire(SessionSignalKind.START, shared))
        return SigStep(s.copy(startedTask = shared), fires)
    }

    return SigStep(s, fires)
}
