package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.CoFocusTimer
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.coFocusElapsedSec
import tech.csalliance.unstuck.core.model.coFocusRemainingSec
import tech.csalliance.unstuck.core.model.shareStatusLabel

// Pure sharing-level logic — 1:1 with lib/share-levels.ts (levelCanComplete,
// shareStatusLabel, shareLevelLabel) so the Android UI stays consistent with the
// RLS/RPC layer that enforces these.
class SharingTest {

    @Test fun `wire values match the web ShareLevel union`() {
        assertEquals("view", ShareLevel.VIEW.wire)
        assertEquals("partner", ShareLevel.PARTNER.wire)
        assertEquals("assign", ShareLevel.ASSIGN.wire)
    }

    @Test fun `fromWire round-trips and degrades unknown to VIEW`() {
        assertEquals(ShareLevel.VIEW, ShareLevel.fromWire("view"))
        assertEquals(ShareLevel.PARTNER, ShareLevel.fromWire("partner"))
        assertEquals(ShareLevel.ASSIGN, ShareLevel.fromWire("assign"))
        // Least-capability fallback for an unknown/legacy/absent level.
        assertEquals(ShareLevel.VIEW, ShareLevel.fromWire("co_owner"))
        assertEquals(ShareLevel.VIEW, ShareLevel.fromWire(null))
    }

    @Test fun `only partner and assign can complete`() {
        assertFalse(ShareLevel.VIEW.canComplete)
        assertTrue(ShareLevel.PARTNER.canComplete)
        assertTrue(ShareLevel.ASSIGN.canComplete)
    }

    @Test fun `owner-side label matches shareLevelLabel`() {
        assertEquals("view", ShareLevel.VIEW.ownerLabel)
        assertEquals("partner", ShareLevel.PARTNER.ownerLabel)
        assertEquals("assigned", ShareLevel.ASSIGN.ownerLabel)
    }

    @Test fun `recipient-side chip matches shareStatusLabel`() {
        // done wins over level for every level.
        assertEquals("done", shareStatusLabel(ShareLevel.VIEW, done = true))
        assertEquals("done", shareStatusLabel(ShareLevel.ASSIGN, done = true))
        assertEquals("watching", shareStatusLabel(ShareLevel.VIEW, done = false))
        assertEquals("yours", shareStatusLabel(ShareLevel.ASSIGN, done = false))
        assertEquals("partner", shareStatusLabel(ShareLevel.PARTNER, done = false))
    }

    @Test fun `circle status decodes and degrades unknown to invited`() {
        assertEquals(CircleStatus.ACTIVE, CircleStatus.fromWire("active"))
        assertEquals(CircleStatus.INVITED, CircleStatus.fromWire("invited"))
        assertEquals(CircleStatus.REVOKED, CircleStatus.fromWire("revoked"))
        assertEquals(CircleStatus.INVITED, CircleStatus.fromWire("mystery"))
    }

    // Co-focus SHARED VIEW (T1b): a focusing peer's live timer drives the SAME mm:ss on
    // both sides. elapsed = paused ? (pausedAtMs − start) : (now − start);
    // remaining = estimateMin*60 − elapsed. Identical math on web + iOS + Android.

    @Test fun `running peer elapsed is wall-clock since start and remaining counts down`() {
        val start = 1_000_000L
        val timer = CoFocusTimer(sessionStartMs = start, paused = false, pausedAtMs = null, estimateMin = 25)
        // 90s after start while running.
        assertEquals(90, coFocusElapsedSec(timer, start + 90_000))
        assertEquals(25 * 60 - 90, coFocusRemainingSec(timer, start + 90_000))
    }

    @Test fun `paused peer elapsed FREEZES at the pause point regardless of now`() {
        val start = 5_000_000L
        val timer = CoFocusTimer(sessionStartMs = start, paused = true, pausedAtMs = start + 120_000, estimateMin = 25)
        // now keeps advancing, but a paused timer holds at pausedAt − start = 120s.
        assertEquals(120, coFocusElapsedSec(timer, start + 120_000))
        assertEquals(120, coFocusElapsedSec(timer, start + 999_000))
        assertEquals(25 * 60 - 120, coFocusRemainingSec(timer, start + 999_000))
    }

    @Test fun `paused with a null pausedAt falls back to wall-clock`() {
        // Defensive: a paused flag without pausedAtMs shouldn't freeze wrongly.
        val start = 0L
        val timer = CoFocusTimer(sessionStartMs = start, paused = true, pausedAtMs = null, estimateMin = 25)
        assertEquals(30, coFocusElapsedSec(timer, 30_000))
    }

    @Test fun `elapsed clamps at zero for a start in the future`() {
        val timer = CoFocusTimer(sessionStartMs = 10_000L, paused = false, pausedAtMs = null, estimateMin = 25)
        assertEquals(0, coFocusElapsedSec(timer, 9_000L))
    }

    @Test fun `remaining goes negative on overrun`() {
        val start = 0L
        val timer = CoFocusTimer(sessionStartMs = start, paused = false, pausedAtMs = null, estimateMin = 25)
        // 30 min into a 25-min estimate → 5 min over.
        assertEquals(-5 * 60, coFocusRemainingSec(timer, 30 * 60_000L))
    }
}
