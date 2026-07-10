package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareLevel
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
}
