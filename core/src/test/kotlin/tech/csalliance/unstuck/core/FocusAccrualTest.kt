package tech.csalliance.unstuck.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.accruesViaSharedLedger
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.ShareBadge
import tech.csalliance.unstuck.core.model.ShareLevel

// One-true-shared-session accrual routing. The double-accrual bug: the owner routed
// by the share-badge cache ONLY, which is empty on a cold start / offline relaunch,
// so the owner did the direct totalFocused bump while the partner's finalize of the
// SAME session id also landed in the ledger — 2× the minutes.
class FocusAccrualTest {
    private fun live(
        sharedTitle: String? = null, sharedLevel: String? = null,
        rev: Int? = null, applied: Int? = null,
    ) = LiveSession(
        id = "sid", taskId = "t1", sessionStart = 1L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT,
        sharedTitle = sharedTitle, sharedLevel = sharedLevel, sharedSessionRev = rev, lastAppliedRev = applied,
    )
    private val partnerBadge = mapOf("t1" to listOf(ShareBadge("t1", ShareLevel.PARTNER, "Sam")))

    @Test fun plainOwnSession_directBump() {
        assertFalse(accruesViaSharedLedger(live(), "t1", emptyMap()))
    }

    @Test fun badgeSaysPartner_ledger() {
        assertTrue(accruesViaSharedLedger(live(), "t1", partnerBadge))
    }

    @Test fun coldStart_emptyBadges_butTheBlobWasBroadcast_ledger() {
        // The regression: announced session (rev stamped), badges not yet resolved.
        assertTrue(accruesViaSharedLedger(live(rev = 1), "t1", emptyMap()))
        assertTrue(accruesViaSharedLedger(live(applied = 3), "t1", emptyMap()))
    }

    @Test fun recipientSession_ledger() {
        assertTrue(accruesViaSharedLedger(live(sharedTitle = "Their brief", sharedLevel = "partner"), "t1", emptyMap()))
        assertTrue(accruesViaSharedLedger(live(sharedTitle = "Their brief", sharedLevel = "assign"), "t1", emptyMap()))
    }

    @Test fun viewOrAssignBadgeOnly_directBump() {
        val assign = mapOf("t1" to listOf(ShareBadge("t1", ShareLevel.ASSIGN, "Sam")))
        assertFalse(accruesViaSharedLedger(live(), "t1", assign))
    }
}
