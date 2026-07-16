package tech.csalliance.unstuck.core.logic

// One true shared session (partner co-focus v2) — the pure reducer shared 1:1 by
// web + iOS + Android (docs/shared-session-spec.md). A partner-shared task has AT
// MOST ONE live session, identified by a client-minted sessionId; pause / resume /
// extend / finish from either side broadcast the FULL state and the other side
// REPLACES its local session's shared fields. Conflicts resolve last-writer-wins
// by (rev, atMs): rev is a monotonic per-session control revision (bumped on every
// local control), atMs the sender's wall clock at the control. Everything here is
// a pure function of values + an injected `now`, so it's unit-testable like
// FocusTimer / sessionSignalStep.

/** The full shared state of a live co-focus session — exactly the `timer`
 *  broadcast payload (minus the sender's userId). All epoch-ms are WHOLE numbers
 *  on the wire (Long here); receivers stay tolerant of decimals when decoding. */
data class SharedSessionState(
    val sessionId: String,
    val sessionStartMs: Long,   // resume-adjusted: elapsed = now − start while running
    val paused: Boolean,
    val pausedAtMs: Long?,      // epoch ms when paused, else null
    val estimateMin: Int,
    val rev: Int,               // monotonic control revision (bumped on every control)
    val atMs: Long,             // sender wall clock at the control
    val ended: Boolean,         // true exactly once, on finish/cancel
)

/** The reducer result: whether to apply the incoming state, and the next local
 *  state (the incoming snapshot when applied, the unchanged local otherwise). */
data class SharedSessionStep(val apply: Boolean, val next: SharedSessionState)

/** Apply an incoming `timer` control to the local live session iff it is the SAME
 *  session, the local side hasn't already ended it, and `(rev, atMs)` is STRICTLY
 *  newer than the last state the local side knows (its own latest control or the
 *  last applied remote one — the caller passes that as [local]). Receivers REPLACE
 *  the shared fields with the incoming ones; local-only fields (treatment, prior
 *  progress, nudge flags, shared markers) are kept by the caller. */
fun sharedSessionStep(local: SharedSessionState, incoming: SharedSessionState): SharedSessionStep {
    if (incoming.sessionId != local.sessionId) return SharedSessionStep(false, local)
    if (local.ended) return SharedSessionStep(false, local)
    // `ended` is TERMINAL: the partner's finish/cancel applies regardless of (rev,
    // atMs) — a concurrent local control racing the finalize must never outrank it
    // (the accrual ledger dedups; both sides converge on ended exactly once).
    if (incoming.ended) return SharedSessionStep(true, incoming)
    val newer = incoming.rev > local.rev || (incoming.rev == local.rev && incoming.atMs > local.atMs)
    if (!newer) return SharedSessionStep(false, local)
    return SharedSessionStep(true, incoming)
}

/** The newest control cursor this device knows — its own LOCAL stamp vs the last
 *  APPLIED remote — ordered by (rev, atMs), ties to the LOCAL side. Feeds the
 *  reducer's `local` floor. Both halves must carry a wall clock: when the local
 *  stamp had none, the floor used to fall back to a stale applied atMs, so a peer
 *  re-announcing OUR OWN control (same rev, our atMs) read as "newer" and
 *  re-applied — flipping a genuinely local pause to remote. */
fun sharedRevFloor(localRev: Int?, localAtMs: Long?, appliedRev: Int?, appliedAtMs: Long?): Pair<Int, Long> {
    val lr = localRev ?: 0
    val la = localAtMs ?: 0L
    val ar = appliedRev ?: 0
    val aa = appliedAtMs ?: 0L
    return if (lr > ar || (lr == ar && la >= aa)) lr to la else ar to aa
}

/** Hard sanity bound on a shared session's age: a broadcast whose sessionStart is
 *  older than this is a stale announcement from a dead client, never adopted.
 *  Matches the server-side clamp in log_shared_focus (migration 047). */
const val SHARED_SESSION_MAX_AGE_MS: Long = 12L * 60 * 60 * 1000

/** Tolerated clock skew on a peer's `sessionStartMs`: a session that "started" up to
 *  2 min in OUR future is still adoptable (the sender's clock simply runs ahead) —
 *  rejecting it would fork a second session on the same task. Display clamps the
 *  adopted start to `now` (FocusTimer.adopt) so elapsed never renders negative. */
const val SHARED_SESSION_SKEW_MS: Long = 120_000

/** Is a received `timer` state a live session this device may ADOPT (join-or-mint's
 *  "join")? It must carry a session id, not be ended, and have started within a sane
 *  window (−2 min skew ≤ now − start < 12h — guards a stale broadcast from a dead
 *  client while tolerating a peer clock slightly ahead of ours). */
fun adoptable(msg: SharedSessionState?, now: Long): Boolean {
    if (msg == null || msg.sessionId.isBlank() || msg.ended) return false
    val age = now - msg.sessionStartMs
    return age >= -SHARED_SESSION_SKEW_MS && age < SHARED_SESSION_MAX_AGE_MS
}

/** Seconds elapsed of the SHARED session, from the shared timestamps — identical on
 *  every participant up to clock skew, so whichever side finalizes the ledger writes
 *  ~the same number. Frozen at the pause point while paused; clamped at ≥ 0. */
fun canonicalElapsedSec(state: SharedSessionState, now: Long): Int {
    val end = if (state.paused && state.pausedAtMs != null) state.pausedAtMs else now
    return ((end - state.sessionStartMs) / 1000L).coerceAtLeast(0L).toInt()
}

/** Was the CURRENT pause applied from a REMOTE control (the partner paused), rather
 *  than a local tap? A local control stamps `sharedSessionRev` + `sharedSessionAtMs`
 *  past every applied cursor in the SAME write (AppViewModel.mutateLive /
 *  FocusCommands), so a paused blob whose newest (rev, atMs) came from an APPLY was
 *  paused remotely — the pause nag / reason sheet must NOT fire on this participant.
 *  Ordered by (rev, atMs) with ties to REMOTE (an adopted-paused state seeds the two
 *  cursors equal, and the joiner didn't pause). */
fun remotePaused(
    paused: Boolean,
    sharedSessionRev: Int?,
    sharedSessionAtMs: Long?,
    lastAppliedRev: Int?,
    lastAppliedAtMs: Long?,
): Boolean {
    if (!paused || lastAppliedRev == null) return false
    val lr = sharedSessionRev ?: 0
    val la = sharedSessionAtMs ?: 0L
    val aa = lastAppliedAtMs ?: 0L
    return lastAppliedRev > lr || (lastAppliedRev == lr && aa >= la)
}
