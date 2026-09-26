package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.abs

/**
 * Let the keyboard go when the user drags this scroll up or down: the platform
 * habit of "scrolling the list puts the keyboard away" (iOS's
 * `.scrollDismissesKeyboard`).
 *
 * Put it on a scroll container, BEFORE its `verticalScroll(…)` (or on a lazy
 * list's own modifier), so it sees the finger over the whole viewport.
 *
 * Why (Ahmad 2026-09-26): a list scrolls the focused field into view above the
 * keyboard (`.imePadding().verticalScroll(…)` + [keepInViewWhileTyping]). Dragged
 * back up to the top, nothing let go of that field: it stayed focused below the
 * screen, so the keyboard stayed up over a list whose add row was now out of
 * sight (KeyboardInsetsTest). Here a vertical drag clears focus, which ends the
 * text session and takes the keyboard down. Clearing focus, not just hiding the
 * keyboard, is what matters: a field that kept focus would be scrolled back into
 * view by [keepInViewWhileTyping] as the keyboard went — the list yanked back to
 * where the user just dragged it from.
 *
 * Only a finger dragging mostly up or down counts. A row's sideways swipe (a
 * collection item's actions), a tap, a hold, and the list's own scrolling (to the
 * focused field, after an add, accessibility scrolls) keep the keyboard. Nothing
 * is consumed: the scroll and the rows see every event as before.
 */
fun Modifier.dismissKeyboardOnDrag(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss by rememberUpdatedState {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    pointerInput(Unit) {
        awaitEachGesture {
            // The Initial pass sees the finger before the rows and the scroll do,
            // whatever they go on to consume; positions are relative to this
            // (non-scrolling) container, so the list moving doesn't skew them.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val moved = change.position - down.position
                when (dragAxis(moved.x, moved.y, slop)) {
                    DragAxis.VERTICAL -> { dismiss(); break }
                    DragAxis.HORIZONTAL -> break
                    null -> Unit
                }
            }
        }
    }
}

internal enum class DragAxis { VERTICAL, HORIZONTAL }

/** The axis a drag has locked onto once it has moved [slop] in either
 *  direction (null = not yet a drag). Whichever way it went further wins, as
 *  the scroll and a row's sideways swipe decide between themselves. */
internal fun dragAxis(dx: Float, dy: Float, slop: Float): DragAxis? = when {
    abs(dx) <= slop && abs(dy) <= slop -> null
    abs(dy) > abs(dx) -> DragAxis.VERTICAL
    else -> DragAxis.HORIZONTAL
}
