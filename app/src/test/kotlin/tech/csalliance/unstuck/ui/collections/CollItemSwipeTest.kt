package tech.csalliance.unstuck.ui.collections

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── the settle spring, run frame by frame ───────────────────────────────────

    /** A 60 Hz frame clock: every frame is 16 ms after the last. */
    private class Frames : MonotonicFrameClock {
        var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }

    /** Every frame's (ms since the settle began, card offset). */
    private fun settle(from: Float, to: Float): List<Pair<Long, Float>> {
        val clock = Frames()
        val frames = mutableListOf<Pair<Long, Float>>()
        runBlocking(clock) { collItemSettle(from, to) { frames += clock.nanos / 1_000_000L to it } }
        return frames
    }

    // A 3x phone: Pin + To task = 148 dp = 444 px, Delete = 74 dp = 222 px.
    private val pinSide = 444f
    private val deleteSide = 222f

    /** The premise, measured on the spring itself: left free, a close from the
     *  Pin side swings ~0.7 px into the Delete side — the card rounds to -1 px
     *  and the red tile underneath shows as a line — and it's still moving well
     *  after the row looks shut. */
    @Test fun leftFreeTheSpringSwingsPastTheMiddle() {
        val clock = Frames()
        var lowest = 0f
        var lastMs = 0L
        runBlocking(clock) {
            animate(pinSide, 0f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) { v, _ ->
                lowest = minOf(lowest, v); lastMs = clock.nanos / 1_000_000L
            }
        }
        assertTrue("swings past the middle by more than half a pixel: $lowest", lowest < -0.5f)
        assertTrue("still moving at ${lastMs}ms", lastMs > 450L)
    }

    @Test fun aCloseStopsAtTheMiddleInsteadOfSwingingPastIt() {
        val fromPin = settle(pinSide, 0f)
        assertTrue("never on the Delete side: ${fromPin.minOf { it.second }}", fromPin.all { it.second >= 0f })
        assertEquals(0f, fromPin.last().second, 0f)
        // Over as it reaches the middle (~0.28 s), not after the ~0.55 s tail.
        assertTrue("done at ${fromPin.last().first}ms", fromPin.last().first <= 320L)

        val fromDelete = settle(-deleteSide, 0f)
        assertTrue("never on the Pin side: ${fromDelete.maxOf { it.second }}", fromDelete.all { it.second <= 0f })
        assertEquals(0f, fromDelete.last().second, 0f)
    }

    /** Opening isn't bounded — it lands exactly on the actions' width. */
    @Test fun anOpenLandsOnItsActions() {
        assertEquals(pinSide, settle(60f, pinSide).last().second, 0f)
        assertEquals(-deleteSide, settle(-30f, -deleteSide).last().second, 0f)
    }
}
