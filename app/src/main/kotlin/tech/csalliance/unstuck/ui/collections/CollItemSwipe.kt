package tech.csalliance.unstuck.ui.collections

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
