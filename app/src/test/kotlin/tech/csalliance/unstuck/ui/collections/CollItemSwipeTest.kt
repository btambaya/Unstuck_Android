package tech.csalliance.unstuck.ui.collections

import org.junit.Assert.assertEquals
import org.junit.Test

// The list-item row's swipe: right uncovers Pin + To task, left uncovers Delete; the
// card rubber-bands past its actions and snaps open past 45 % of them or on a fling
// (Ahmad 2026-09-23, parity with iOS b84 CollItemRow).
class CollItemSwipeTest {

    private val leading = 148f    // Pin + To task
    private val trailing = 74f    // Delete
    private val fling = 300f

    private fun snap(offset: Float, velocity: Float = 0f, lead: Float = leading) =
        collItemSnapTarget(offset, velocity, lead, trailing, fling)

    @Test fun theCardFollowsTheFingerUpToItsActionsThenRubberBands() {
        assertEquals(0f, collItemRubberBand(0f, leading, trailing), 0f)
        assertEquals(100f, collItemRubberBand(100f, leading, trailing), 0f)
        assertEquals(-50f, collItemRubberBand(-50f, leading, trailing), 0f)
        assertEquals(leading, collItemRubberBand(leading, leading, trailing), 0f)
        // 40 px past the actions moves the card a quarter of that.
        assertEquals(leading + 10f, collItemRubberBand(leading + 40f, leading, trailing), 0.001f)
        assertEquals(-trailing - 10f, collItemRubberBand(-trailing - 40f, leading, trailing), 0.001f)
    }

    @Test fun aReleasePastFortyFivePercentOpensThatSide() {
        assertEquals(leading, snap(leading * 0.46f), 0f)
        assertEquals(leading, snap(leading + 12f), 0f)         // released while rubber-banding
        assertEquals(-trailing, snap(-trailing * 0.46f), 0f)
        assertEquals(-trailing, snap(-trailing - 12f), 0f)
    }

    @Test fun aShortReleaseSnapsShut() {
        assertEquals(0f, snap(leading * 0.44f), 0f)
        assertEquals(0f, snap(-trailing * 0.44f), 0f)
        assertEquals(0f, snap(0f), 0f)
        assertEquals("a fling with the card at rest opens nothing", 0f, snap(0f, velocity = 2000f), 0f)
    }

    @Test fun aFlingOpensTheSideItHeadsFor() {
        assertEquals(leading, snap(10f, velocity = 900f), 0f)
        assertEquals(-trailing, snap(-10f, velocity = -900f), 0f)
        assertEquals("a slow drift is not a fling", 0f, snap(10f, velocity = 200f), 0f)
    }

    @Test fun aFlingBackShutsAnOpenRow() {
        assertEquals(0f, snap(leading * 0.8f, velocity = -900f), 0f)
        assertEquals(0f, snap(-trailing * 0.8f, velocity = 900f), 0f)
    }

    /** While a promotion is in flight there is no To task — the left side is one
     *  action wide, and the 45 % mark moves with it. */
    @Test fun theLeftSideShrinksToPinAloneWhileAPromotionIsInFlight() {
        val pinOnly = trailing
        assertEquals(pinOnly, snap(pinOnly * 0.5f, lead = pinOnly), 0f)
        assertEquals(0f, snap(pinOnly * 0.4f, lead = pinOnly), 0f)
    }
}
