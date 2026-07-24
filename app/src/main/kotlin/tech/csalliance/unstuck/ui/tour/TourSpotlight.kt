package tech.csalliance.unstuck.ui.tour

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.theme.UTheme

// Tour Spotlight — a light scrim with a cut-out pulsing ring around the
// anchored target. Gentle, never a heavy dark overlay (web spotlight.tsx
// parity: rgba(20,18,40,0.20) dim panels, whisper 0.14 scrim when no target).
// The overlay draws only — it registers no pointer input, so the app beneath
// stays fully usable (web pointer-events: none).

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

@Composable
fun TourSpotlight(targetRect: Rect?, reduceMotion: Boolean) {
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
}
