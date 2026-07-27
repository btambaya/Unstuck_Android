package tech.csalliance.unstuck.ui.tour

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tech.csalliance.unstuck.design.theme.UTheme

// Tour Spotlight — a light scrim with a cut-out pulsing ring around the
// anchored target. Gentle, never a heavy dark overlay (web spotlight.tsx
// parity: rgba(20,18,40,0.20) dim panels, whisper 0.14 scrim when no target).
//
// Round-2 #4 (spotlight-only lockdown): with [consumeInput] the dim panels
// around the cut-out SWALLOW every pointer event (taps, scrolls, flings).
// The cut-out itself passes touches through ONLY with [cutoutInteractive]
// (the assistant/reentry steps, whose cutout is the assistant bubble); on
// every other step the cutout is DISPLAY-ONLY — a blocker covers it too, so
// the spotlight can highlight without leaking taps (no minting a focus
// session from the hero, no stale-hole leak into a takeover). No target →
// one full-screen blocker (everything except the panel). A LIVE-open settings
// surface passes consumeInput=false — the scrim reverts to drawing only, and
// just the panel claims input (tourScrimConsumesInput / tourLockdownPolicy).

/* ============================================================
 * Anchor registry — composables report their rects (root coords)
 * keyed by anchor id via Modifier.tourAnchor(id). Rects clear on
 * dispose so a navigated-away anchor never leaves a stale ring.
 * ============================================================ */
object TourAnchors {
    val rects = mutableStateMapOf<String, Rect>()

    fun report(id: String, rect: Rect) {
        val prev = rects[id]
        // Avoid a state write (→ recomposition) for sub-pixel jitter.
        if (prev == null || kotlin.math.abs(prev.left - rect.left) > 0.5f ||
            kotlin.math.abs(prev.top - rect.top) > 0.5f ||
            kotlin.math.abs(prev.width - rect.width) > 0.5f ||
            kotlin.math.abs(prev.height - rect.height) > 0.5f
        ) rects[id] = rect
    }

    fun clear(id: String) { rects.remove(id) }

    /** Primary anchor, else the first live fallback in the chain, else null
     *  (→ whisper scrim). Zero-sized rects are treated as absent. */
    fun resolve(target: String?, fallbacks: List<String> = emptyList()): Rect? {
        val primary = target?.let { rects[it] }?.takeIf { it.width > 0f && it.height > 0f }
        if (primary != null) return primary
        for (id in fallbacks) {
            val r = rects[id]?.takeIf { it.width > 0f && it.height > 0f }
            if (r != null) return r
        }
        return null
    }
}

/** Report this composable's bounds to the tour anchor registry (the Android
 *  data-tour attribute). Clears itself when the composable leaves composition
 *  so a navigated-away anchor never leaves a stale ring. */
fun Modifier.tourAnchor(id: String): Modifier = composed {
    DisposableEffect(id) { onDispose { TourAnchors.clear(id) } }
    onGloballyPositioned { TourAnchors.report(id, it.boundsInRoot()) }
}

/** Spotlight padding around the target (web pad = 8). */
val SPOTLIGHT_PAD = 8.dp

/** The input-blocking scrim rects for one frame: the four dim panels around
 *  the (screen-clamped) cut-out, or ONE full-screen rect when there's no
 *  target. Degenerate slivers are dropped. Pure — unit-tested. */
fun tourBlockerRects(cutout: Rect?, screenW: Float, screenH: Float): List<Rect> {
    if (screenW <= 0f || screenH <= 0f) return emptyList()
    if (cutout == null) return listOf(Rect(0f, 0f, screenW, screenH))
    val l = cutout.left.coerceIn(0f, screenW)
    val t = cutout.top.coerceIn(0f, screenH)
    val r = cutout.right.coerceIn(l, screenW)
    val b = cutout.bottom.coerceIn(t, screenH)
    return listOf(
        Rect(0f, 0f, screenW, t),        // above the cut-out
        Rect(0f, t, l, b),               // left of it
        Rect(r, t, screenW, b),          // right of it
        Rect(0f, b, screenW, screenH),   // below it
    ).filter { it.width > 0.5f && it.height > 0.5f }
}

/** Swallow EVERY pointer event in this region — taps, scrolls, and flings
 *  never reach the app beneath (the region owns the hit path). */
internal fun Modifier.tourBlockInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}

/** The lockdown layer: one hit-testable blocker per scrim rect. The parent
 *  box registers NO pointer input itself, so the cut-out passes through to
 *  the spotlighted element. */
@Composable
private fun TourInputLockdown(cutout: Rect?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        tourBlockerRects(cutout, w, h).forEach { r ->
            Box(
                Modifier
                    .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                    .tourBlockInput(),
            )
        }
    }
}

@Composable
fun TourSpotlight(
    targetRect: Rect?,
    reduceMotion: Boolean,
    consumeInput: Boolean = false,
    cutoutInteractive: Boolean = false,
) {
    val c = UTheme.colors
    val density = LocalDensity.current
    val padPx = with(density) { SPOTLIGHT_PAD.toPx() }
    val cornerPx = with(density) { 16.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }
    val glowPx = with(density) { 6.dp.toPx() }

    // Gentle ring pulse (web u-tour-ring); a static ring under reduce-motion.
    val pulse = if (reduceMotion) 1f else rememberInfiniteTransition(label = "tour-ring")
        .animateFloat(
            initialValue = 0.55f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
            label = "tour-ring-alpha",
        ).value

    val dim = Color(0x33141228)      // rgba(20,18,40,0.20)
    val whisper = Color(0x24141228)  // rgba(20,18,40,0.14)
    val primary = c.primary

    Canvas(Modifier.fillMaxSize()) {
        if (targetRect == null) {
            // No target — either the step has none by design, or the anchor
            // isn't (yet) mounted. Both degrade to the whisper-light scrim so
            // the tour never renders a broken hole.
            drawRect(whisper)
            return@Canvas
        }
        val x = targetRect.left - padPx
        val y = targetRect.top - padPx
        val w = targetRect.width + padPx * 2
        val h = targetRect.height + padPx * 2
        // Four dim panels around the target — light, not black.
        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, y.coerceAtLeast(0f)))
        drawRect(dim, topLeft = Offset(0f, y), size = Size(x.coerceAtLeast(0f), h))
        drawRect(dim, topLeft = Offset(x + w, y), size = Size((size.width - (x + w)).coerceAtLeast(0f), h))
        drawRect(dim, topLeft = Offset(0f, y + h), size = Size(size.width, (size.height - (y + h)).coerceAtLeast(0f)))
        // Soft outer glow + crisp ring (web box-shadow 2px primary + 6px 22%).
        drawRoundRect(
            color = primary.copy(alpha = 0.22f * pulse),
            topLeft = Offset(x - glowPx / 2f, y - glowPx / 2f),
            size = Size(w + glowPx, h + glowPx),
            cornerRadius = CornerRadius(cornerPx + glowPx / 2f),
            style = Stroke(width = glowPx),
        )
        drawRoundRect(
            color = primary.copy(alpha = 0.6f + 0.4f * pulse),
            topLeft = Offset(x, y),
            size = Size(w, h),
            cornerRadius = CornerRadius(cornerPx),
            style = Stroke(width = strokePx),
        )
    }

    // Round-2 #4: the dim panels consume input. The cut-out passes touches
    // through ONLY on the assistant/reentry steps (cutoutInteractive) — on
    // every other step it's display-only: null here means ONE full-screen
    // blocker, covering the ring region too (the panel above stays
    // interactive; the ring still draws, so the highlight is intact).
    if (consumeInput) {
        val cutout = targetRect?.takeIf { cutoutInteractive }?.let {
            Rect(it.left - padPx, it.top - padPx, it.right + padPx, it.bottom + padPx)
        }
        TourInputLockdown(cutout)
    }
}
