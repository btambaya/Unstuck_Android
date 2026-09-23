package tech.csalliance.unstuck.ui.collections

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

// The list-item row's swipe geometry (CollectionDetailScreen's CollItemRow), kept
// pure so the feel is pinned by tests. Swipe RIGHT uncovers Pin + To task on the
// left, swipe LEFT uncovers Delete on the right (Ahmad 2026-09-23, parity with
// iOS b84 CollItemRow). Offsets are px; > 0 = the left actions are showing.

/** One action's width under the card (iOS actionWidth 74 pt). */
internal val COLL_ITEM_ACTION_WIDTH = 74.dp

/** A release (or a fling) this far towards a side's actions opens them — below it
 *  the row snaps shut. iOS: 0.45 of that side's actions. */
internal const val COLL_ITEM_OPEN_FRACTION = 0.45f

/** A release faster than this (per second) counts as a fling. iOS opens on a
 *  predicted end translation past 160 pt, which a ~300 pt/s flick reaches. */
internal val COLL_ITEM_FLING_VELOCITY = 300.dp

/** Where the card sits for a finger travel of [raw]: 1:1 up to a side's actions,
 *  then a quarter speed (a rubber band), so a long drag can't run off the edge
 *  and still feels attached to the finger. */
internal fun collItemRubberBand(raw: Float, leading: Float, trailing: Float): Float = when {
    raw > leading -> leading + (raw - leading) * 0.25f
    raw < -trailing -> -trailing + (raw + trailing) * 0.25f
    else -> raw
}

/** Where the card settles when the finger lifts at [offset] moving at [velocity]
 *  (px/s, + = rightwards): open on the side it was dragged towards once past
 *  [COLL_ITEM_OPEN_FRACTION] of that side's actions or on a fling that way, shut
 *  otherwise. A fling back towards the middle shuts an open row (iOS keeps it
 *  open when it is still past the fraction; on Android the flick back reads as
 *  "close", which is what the finger meant). */
internal fun collItemSnapTarget(offset: Float, velocity: Float, leading: Float, trailing: Float, fling: Float): Float = when {
    offset > 0f -> when {
        velocity < -fling -> 0f
        velocity > fling || offset > leading * COLL_ITEM_OPEN_FRACTION -> leading
        else -> 0f
    }
    offset < 0f -> when {
        velocity > fling -> 0f
        velocity < -fling || offset < -trailing * COLL_ITEM_OPEN_FRACTION -> -trailing
        else -> 0f
    }
    else -> 0f
}

/** Slide the card from [from] to [to], reporting each frame to [onFrame]. iOS
 *  spring(response 0.28, damping 0.9): stiffness (2π/0.28)² ≈ 500.
 *
 *  A close stops dead at the middle. That spring swings ~0.15 % past its target
 *  before it comes back — nothing on the way open, but on the way shut it carries
 *  the card over to the OTHER side for a few frames: the opposite side's tile gets
 *  drawn underneath and the card rounds to -1 px, so a closing Pin row flashed a
 *  1 px red Delete line. Bounding the close at the middle ends it the frame it
 *  arrives (~0.28 s) instead of after the half-second tail (Ahmad 2026-09-23,
 *  parity with iOS b84 — found in the port's review). */
internal suspend fun collItemSettle(from: Float, to: Float, onFrame: (Float) -> Unit) {
    val card = Animatable(from)
    if (to == 0f) card.updateBounds(lowerBound = minOf(from, 0f), upperBound = maxOf(from, 0f))
    card.animateTo(to, spring(dampingRatio = 0.9f, stiffness = 500f)) { onFrame(value) }
}
