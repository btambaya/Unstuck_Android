package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.DIVERGENCE_SLACK_MS
import tech.csalliance.unstuck.core.logic.DivergenceResolution
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.SHARED_SESSION_MAX_AGE_MS
import tech.csalliance.unstuck.core.logic.SHARED_SESSION_SKEW_MS
import tech.csalliance.unstuck.core.logic.SharedSessionState
import tech.csalliance.unstuck.core.logic.adoptable
import tech.csalliance.unstuck.core.logic.canonicalElapsedSec
import tech.csalliance.unstuck.core.logic.remotePaused
import tech.csalliance.unstuck.core.logic.resolveDivergence
import tech.csalliance.unstuck.core.logic.sharedRevFloor
import tech.csalliance.unstuck.core.logic.sharedSessionStep
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.time.Time

// One-true-shared-session reducer (docs/shared-session-spec.md) — the pure LWW
// apply-iff-newer step, adoption sanity, canonical elapsed, and the local-vs-remote
// pause classifier. Ported 1:1 across web + iOS + Android, so these scenarios are
// the cross-platform contract.
class SharedSessionTest {
    private val t0 = Time.parseMillis("2026-07-16T10:00:00.000Z")!!

    private fun state(
        sessionId: String = "s1",
        startMs: Long = t0,
        paused: Boolean = false,
        pausedAtMs: Long? = null,
        estimateMin: Int = 25,
        rev: Int = 1,
        atMs: Long = t0,
        ended: Boolean = false,
    ) = SharedSessionState(sessionId, startMs, paused, pausedAtMs, estimateMin, rev, atMs, ended)

    // --- sharedSessionStep: apply-iff-newer LWW ---

    @Test fun `applies a strictly newer rev and returns the incoming snapshot`() {
        val local = state(rev = 1, atMs = t0)
        val incoming = state(rev = 2, atMs = t0 + 5_000, paused = true, pausedAtMs = t0 + 5_000)
        val step = sharedSessionStep(local, incoming)
        assertTrue(step.apply)
        assertEquals(incoming, step.next)
    }

    @Test fun `same rev with a newer atMs wins the tie`() {
        val local = state(rev = 3, atMs = t0)
        val incoming = state(rev = 3, atMs = t0 + 1, paused = true, pausedAtMs = t0 + 1)
        assertTrue(sharedSessionStep(local, incoming).apply)
    }

    @Test fun `rejects an equal or older (rev, atMs) — a replayed hello re-announce is a no-op`() {
        val local = state(rev = 3, atMs = t0 + 10_000)
        assertFalse(sharedSessionStep(local, state(rev = 3, atMs = t0 + 10_000)).apply)   // exact echo
        assertFalse(sharedSessionStep(local, state(rev = 3, atMs = t0 + 9_000)).apply)    // older tie
        assertFalse(sharedSessionStep(local, state(rev = 2, atMs = t0 + 60_000)).apply)   // older rev, newer clock
        assertEquals(local, sharedSessionStep(local, state(rev = 2, atMs = t0 + 60_000)).next)
    }

    @Test fun `a higher rev wins even with an older atMs (rev orders first)`() {
        val local = state(rev = 2, atMs = t0 + 60_000)
        assertTrue(sharedSessionStep(local, state(rev = 3, atMs = t0 + 1_000)).apply)
    }

    @Test fun `never applies across DIFFERENT session ids`() {
        val local = state(sessionId = "s1", rev = 1)
        assertFalse(sharedSessionStep(local, state(sessionId = "s2", rev = 99, atMs = t0 + 99_000)).apply)
    }

    @Test fun `never re-applies onto a locally ENDED session (finalize is terminal)`() {
        val local = state(rev = 5, ended = true)
        assertFalse(sharedSessionStep(local, state(rev = 6, atMs = t0 + 5_000)).apply)
        // …not even another ended (the terminal bypass still requires !local.ended).
        assertFalse(sharedSessionStep(local, state(rev = 6, atMs = t0 + 5_000, ended = true)).apply)
    }

    @Test fun `an incoming ended control applies like any newer control`() {
        val local = state(rev = 2, atMs = t0)
        val step = sharedSessionStep(local, state(rev = 3, atMs = t0 + 30_000, ended = true))
        assertTrue(step.apply)
        assertTrue(step.next.ended)
    }

    @Test fun `ended is TERMINAL - applies even with an OLDER (rev, atMs) than the local floor`() {
        // A local control racing the partner's finish can out-rev the ended message;
        // the finish must still win (the ledger dedups, both sides converge on ended).
        val local = state(rev = 7, atMs = t0 + 60_000)
        val step = sharedSessionStep(local, state(rev = 3, atMs = t0 + 1_000, ended = true))
        assertTrue(step.apply)
        assertTrue(step.next.ended)
        // Same-rev/same-atMs echo of an ended: still applies (idempotent downstream).
        assertTrue(sharedSessionStep(local, state(rev = 7, atMs = t0 + 60_000, ended = true)).apply)
        // But never across session ids.
        assertFalse(sharedSessionStep(local, state(sessionId = "other", rev = 99, ended = true)).apply)
    }

    // --- adoptable: join-or-mint's "join" guard ---

    @Test fun `a live in-window session is adoptable`() {
        assertTrue(adoptable(state(startMs = t0 - 120_000), now = t0))
    }

    @Test fun `null, blank id, ended and stale sessions are not adoptable`() {
        assertFalse(adoptable(null, now = t0))
        assertFalse(adoptable(state(sessionId = ""), now = t0))
        assertFalse(adoptable(state(ended = true), now = t0))
        assertFalse(adoptable(state(startMs = t0 - SHARED_SESSION_MAX_AGE_MS), now = t0))       // ≥ 12h → stale
        assertTrue(adoptable(state(startMs = t0 - SHARED_SESSION_MAX_AGE_MS + 1_000), now = t0))
    }

    @Test fun `a slightly future-started session is adoptable (clock skew), a far-future one is not`() {
        // The peer's clock may run ahead of ours — rejecting would fork a 2nd session.
        assertTrue(adoptable(state(startMs = t0 + 60_000), now = t0))                            // 1 min ahead
        assertTrue(adoptable(state(startMs = t0 + SHARED_SESSION_SKEW_MS), now = t0))            // boundary
        assertFalse(adoptable(state(startMs = t0 + SHARED_SESSION_SKEW_MS + 1_000), now = t0))   // beyond skew
    }

    // --- canonicalElapsedSec: both sides write ~the same number ---

    @Test fun `running elapsed is wall-clock since the resume-adjusted start`() {
        assertEquals(600, canonicalElapsedSec(state(startMs = t0 - 600_000), now = t0))
    }

    @Test fun `paused elapsed is frozen at the pause point`() {
        val s = state(startMs = t0 - 600_000, paused = true, pausedAtMs = t0 - 300_000)
        assertEquals(300, canonicalElapsedSec(s, now = t0))
        assertEquals(300, canonicalElapsedSec(s, now = t0 + 3_600_000))   // still frozen an hour later
    }

    @Test fun `elapsed clamps at zero (skewed clocks never go negative)`() {
        assertEquals(0, canonicalElapsedSec(state(startMs = t0 + 5_000), now = t0))
    }

    // --- remotePaused: the pause-nag / reason-sheet gate (ordered by (rev, atMs)) ---

    @Test fun `a pause applied from a remote control reads as remote`() {
        // Remote pause: apply stamps lastAppliedRev past the local rev.
        assertTrue(remotePaused(paused = true, sharedSessionRev = 1, sharedSessionAtMs = t0, lastAppliedRev = 2, lastAppliedAtMs = t0 + 1_000))
        // Adopted-paused (both cursor pairs seeded equal) is remote too — this user
        // didn't pause (ties go to remote).
        assertTrue(remotePaused(paused = true, sharedSessionRev = 3, sharedSessionAtMs = t0, lastAppliedRev = 3, lastAppliedAtMs = t0))
    }

    @Test fun `a local pause (stamped past every applied cursor) reads as local`() {
        assertFalse(remotePaused(paused = true, sharedSessionRev = 3, sharedSessionAtMs = t0 + 2_000, lastAppliedRev = 2, lastAppliedAtMs = t0))
        // Never-shared session: no cursors at all.
        assertFalse(remotePaused(paused = true, sharedSessionRev = null, sharedSessionAtMs = null, lastAppliedRev = null, lastAppliedAtMs = null))
        // Not paused: never remote-paused.
        assertFalse(remotePaused(paused = false, sharedSessionRev = 1, sharedSessionAtMs = t0, lastAppliedRev = 2, lastAppliedAtMs = t0 + 1_000))
    }

    @Test fun `a rev TIE classifies by the controls' wall clocks (concurrent controls can't swap sides)`() {
        // Both sides stamped rev 3 concurrently: whoever's clock is newer WON the LWW
        // apply — the local pause stays local when its atMs is newer…
        assertFalse(remotePaused(paused = true, sharedSessionRev = 3, sharedSessionAtMs = t0 + 5_000, lastAppliedRev = 3, lastAppliedAtMs = t0 + 1_000))
        // …and reads remote when the applied control's clock is newer.
        assertTrue(remotePaused(paused = true, sharedSessionRev = 3, sharedSessionAtMs = t0 + 1_000, lastAppliedRev = 3, lastAppliedAtMs = t0 + 5_000))
    }

    // --- sharedRevFloor: the reducer's local floor is the newest (rev, atMs) PAIR ---

    @Test fun `floor picks the lexicographically newest of local stamp vs last applied`() {
        assertEquals(3 to t0 + 5_000, sharedRevFloor(3, t0 + 5_000, 2, t0 + 9_000))   // higher rev wins
        assertEquals(4 to t0 + 1_000, sharedRevFloor(2, t0 + 9_000, 4, t0 + 1_000))   // higher rev wins
        assertEquals(3 to t0 + 9_000, sharedRevFloor(3, t0 + 2_000, 3, t0 + 9_000))   // rev tie → newer atMs
        assertEquals(3 to t0 + 9_000, sharedRevFloor(3, t0 + 9_000, 3, t0 + 2_000))
        assertEquals(0 to 0L, sharedRevFloor(null, null, null, null))
    }

    @Test fun `a peer re-announce of OUR OWN control is not newer than the floor`() {
        // Local pause stamped (rev 3, atMs) in the same write. A peer's hello
        // re-announce replays exactly that state — with the local atMs IN the floor
        // it ties and is rejected. (The old floor mixed the max rev with a STALE
        // applied atMs, so the echo read as newer, re-applied, and flipped
        // remotePaused for a genuinely local pause.)
        val (floorRev, floorAt) = sharedRevFloor(3, t0 + 5_000, 2, t0 + 1_000)
        val local = state(rev = floorRev, atMs = floorAt, paused = true, pausedAtMs = t0 + 5_000)
        val echo = state(rev = 3, atMs = t0 + 5_000, paused = true, pausedAtMs = t0 + 5_000)
        assertFalse(sharedSessionStep(local, echo).apply)
    }

    // --- FocusTimer.adopt: join keeps THE session, not a new one ---

    @Test fun `adopt seeds the live session from the shared state without minting`() {
        val shared = state(sessionId = "owner-sid", startMs = t0 - 176_000, paused = false, estimateMin = 45, rev = 4, atMs = t0 - 1_000)
        val live = FocusTimer.adopt(FocusTimer.empty, "task-1", shared, now = t0, priorAccumulatedSec = 120)
        assertEquals("owner-sid", live.id)                       // SAME session id — the ledger dedups on it
        assertEquals("task-1", live.taskId)
        assertEquals(t0 - 176_000, live.sessionStart)            // joins mid-clock (02:56)
        assertEquals(45, live.sessionEstimateMin)
        assertEquals(120, live.priorAccumulatedSec)
        assertFalse(live.paused)
        assertNull(live.pausedAt)
        // Both LWW cursor pairs seed at the adopted (rev, atMs): only a STRICTLY
        // newer control re-applies, and the floor carries the adopted clock.
        assertEquals(4, live.sharedSessionRev)
        assertEquals(t0 - 1_000, live.sharedSessionAtMs)
        assertEquals(4, live.lastAppliedRev)
        assertEquals(t0 - 1_000, live.lastAppliedAtMs)
        assertNull(live.sharedSessionEndedBy)
        assertNull(live.sharedTitle)                              // markers are per caller
        assertNull(live.sharedLevel)
    }

    @Test fun `adopting a skew-ahead session clamps the displayed start to now`() {
        // adoptable() tolerates a peer clock up to 2 min ahead; the display must not
        // render a negative elapsed, so the adopted start clamps to now.
        val shared = state(startMs = t0 + 60_000, rev = 1)
        val live = FocusTimer.adopt(FocusTimer.empty, "task-1", shared, now = t0)
        assertEquals(t0, live.sessionStart)
        assertEquals(0, FocusTimer.elapsedSec(live, t0))
        // A past start is untouched (min(start, now) == start).
        assertEquals(t0 - 60_000, FocusTimer.adopt(FocusTimer.empty, "task-1", state(startMs = t0 - 60_000), now = t0).sessionStart)
    }

    @Test fun `adopting a PAUSED session lands paused and frozen`() {
        val shared = state(startMs = t0 - 600_000, paused = true, pausedAtMs = t0 - 60_000, rev = 2)
        val live = FocusTimer.adopt(FocusTimer.empty, "task-1", shared, now = t0)
        assertTrue(live.paused)
        assertEquals(t0 - 60_000, live.pausedAt)
        assertEquals(540, FocusTimer.elapsedSec(live, t0))
        // Adopted-paused classifies as REMOTE (no nag on the joiner).
        assertTrue(remotePaused(live.paused, live.sharedSessionRev, live.sharedSessionAtMs, live.lastAppliedRev, live.lastAppliedAtMs))
    }

    @Test fun `adopt keeps the caller's treatment and clears occurrence unless passed`() {
        val cur = FocusTimer.empty.copy(treatment = FocusTreatment.MONK, occurrenceBlockId = "stale")
        val live = FocusTimer.adopt(cur, "task-1", state(), now = t0)
        assertEquals(FocusTreatment.MONK, live.treatment)
        assertNull(live.occurrenceBlockId)
        val withOcc = FocusTimer.adopt(cur, "tpl", state(), now = t0, occurrenceBlockId = "occ9")
        assertEquals("occ9", withOcc.occurrenceBlockId)
    }

    // --- resolveDivergence: offline & reconnect convergence (spec acceptance
    // criteria, tester card 2026-07-17). All comparisons run at the RECEIVER's
    // clock; a paused side is frozen at its pause point, so "most ahead" is
    // exactly the timer the tester sees. Mirrored 1:1 on web + iOS. ---

    @Test fun `criterion 1 - offline with NO controls diverges nothing - equal clocks fall back to LWW`() {
        // Both sides kept running from the same start: elapsed identical → within
        // slack → plain (rev, atMs) LWW — so the partner's harmless re-announce of
        // the same state is simply a no-op through the normal reducer.
        val local = state(startMs = t0 - 100_000, rev = 2, atMs = t0 - 100_000)
        val incoming = state(startMs = t0 - 100_000, rev = 2, atMs = t0 - 100_000)
        assertEquals(DivergenceResolution.Lww, resolveDivergence(local, incoming, nowMs = t0))
        assertFalse(sharedSessionStep(local, incoming).apply)
    }

    @Test fun `criteria 2+3 - paused offline, partner ran on - the incoming running state is most-ahead and ADOPTS`() {
        // I lost internet and paused (criterion 2: it paused FOR ME — frozen at 60s).
        // The partner never saw the pause and ran to 600s. On reconnect their state
        // is THE MOST AHEAD (criterion 3) → adopt it wholesale, even though my local
        // pause out-revs it (the divergence path bypasses plain LWW by design).
        val local = state(startMs = t0 - 600_000, paused = true, pausedAtMs = t0 - 540_000, rev = 5, atMs = t0 - 540_000)
        val incoming = state(startMs = t0 - 600_000, rev = 3, atMs = t0 - 590_000)
        assertEquals(DivergenceResolution.Adopt, resolveDivergence(local, incoming, nowMs = t0))
    }

    @Test fun `criterion 3 flipped - I ran on while the partner's state is frozen behind - KEEP local and broadcast at max rev + 1`() {
        // My diverged side ran to 600s; the incoming state is a stale pause frozen at
        // 60s. Local is most-ahead → keep it, and broadcast at max(local, incoming)+1
        // so the convergence control beats every rev either side has seen.
        val local = state(startMs = t0 - 600_000, rev = 4, atMs = t0 - 60_000)
        val incoming = state(startMs = t0 - 600_000, paused = true, pausedAtMs = t0 - 540_000, rev = 9, atMs = t0 - 540_000)
        assertEquals(DivergenceResolution.KeepAndBroadcast(rev = 10), resolveDivergence(local, incoming, nowMs = t0))
        // …and with the LOCAL side holding the higher rev, it still bumps past it.
        val incomingLowRev = incoming.copy(rev = 2)
        assertEquals(DivergenceResolution.KeepAndBroadcast(rev = 5), resolveDivergence(local, incomingLowRev, nowMs = t0))
    }

    @Test fun `criterion 4 - the convergence broadcast wins the partner's PLAIN LWW (no special logic online)`() {
        // The online partner is non-diverged: it applies the convergence control via
        // the ordinary reducer. The KeepAndBroadcast rev is strictly greater than any
        // rev the partner holds, so the normal (rev, atMs) order applies it.
        val local = state(startMs = t0 - 600_000, rev = 4, atMs = t0 - 60_000)
        val partner = state(startMs = t0 - 600_000, paused = true, pausedAtMs = t0 - 540_000, rev = 9, atMs = t0 - 540_000)
        val res = resolveDivergence(local, partner, nowMs = t0) as DivergenceResolution.KeepAndBroadcast
        val convergence = local.copy(rev = res.rev, atMs = t0)
        assertTrue(res.rev > partner.rev)
        assertTrue(sharedSessionStep(partner, convergence).apply)
    }

    @Test fun `slack boundary - a gap of exactly 3s is LWW, one second beyond adopts or keeps`() {
        val slackSec = (DIVERGENCE_SLACK_MS / 1000).toInt()
        val local = state(startMs = t0 - 100_000, rev = 1, atMs = t0 - 100_000)
        // Incoming exactly slack AHEAD (103s vs 100s) → within slack → LWW…
        assertEquals(
            DivergenceResolution.Lww,
            resolveDivergence(local, state(startMs = t0 - 100_000 - slackSec * 1_000L), nowMs = t0),
        )
        // …one second beyond → genuinely ahead → adopt.
        assertEquals(
            DivergenceResolution.Adopt,
            resolveDivergence(local, state(startMs = t0 - 100_000 - (slackSec + 1) * 1_000L), nowMs = t0),
        )
        // Incoming exactly slack BEHIND → LWW; one second beyond → keep local.
        assertEquals(
            DivergenceResolution.Lww,
            resolveDivergence(local, state(startMs = t0 - 100_000 + slackSec * 1_000L), nowMs = t0),
        )
        assertEquals(
            DivergenceResolution.KeepAndBroadcast(rev = 2),
            resolveDivergence(local, state(startMs = t0 - 100_000 + (slackSec + 1) * 1_000L), nowMs = t0),
        )
    }

    @Test fun `resolveDivergence(ended) returns Lww on every platform - the reducer owns ended-terminality`() {
        // Parity amendment: callers PRE-FILTER ended before entering the divergence
        // path (the terminal finalize never routes through most-ahead), so an ended
        // incoming falls back to Lww here — and the STEP reducer's terminal bypass
        // is what finalizes it regardless of cursors (the ledger dedups the accrual).
        val local = state(startMs = t0 - 600_000, rev = 4, atMs = t0 - 60_000)
        val endedBehind = state(startMs = t0 - 600_000, paused = true, pausedAtMs = t0 - 540_000, rev = 2, atMs = t0 - 540_000, ended = true)
        assertEquals(DivergenceResolution.Lww, resolveDivergence(local, endedBehind, nowMs = t0))
        // The plain reducer still applies the finish even though its (rev, atMs)
        // is far behind the local floor — ended is terminal THERE.
        assertTrue(sharedSessionStep(local, endedBehind).apply)
    }

    @Test fun `a fresh MINT clears every shared-session cursor (no leak from a displaced co-focus)`() {
        val displaced = FocusTimer.adopt(FocusTimer.empty, "shared-task", state(rev = 7), now = t0).copy(sharedSessionEndedBy = "Sam")
        val own = FocusTimer.start(displaced, "my-task", estimateMin = 25, now = t0)
        assertNull(own.sharedSessionRev)
        assertNull(own.sharedSessionAtMs)
        assertNull(own.lastAppliedRev)
        assertNull(own.lastAppliedAtMs)
        assertNull(own.sharedSessionEndedBy)
    }
}
