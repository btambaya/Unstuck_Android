package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

/**
 * Keep this whole container in sight above the keyboard while a text field
 * inside it has focus, with [gap] to spare.
 *
 * With the keyboard padding outside a scroll (`.imePadding().verticalScroll(…)`)
 * Compose keeps the FOCUSED FIELD in view as the keyboard comes up, but only the
 * field itself: its line of text lands right on the keyboard's edge and the card,
 * pill or box drawn around it is cut in half underneath (the edited list row,
 * the "Add to this collection…" pill, the task's capture field — KeyboardInsetsTest).
 * This asks the scroll for the whole container instead: when the field gains
 * focus, once the keyboard has settled at a new height, and whenever [reveal]
 * changes while the field has focus (e.g. an item you just added pushed the
 * field down).
 *
 * Put it on a container about one row tall. The whole rectangle must fit above
 * the keyboard; a taller one could be edge-aligned past the field inside it.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.keepInViewWhileTyping(gap: Dp = 8.dp, reveal: Any? = null): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    // Read only by the request below, never in composition: a resize doesn't recompose.
    var size by remember { mutableStateOf(IntSize.Zero) }
    if (focused) {
        val ime = WindowInsets.ime
        val density = LocalDensity.current
        LaunchedEffect(ime, density, reveal) {
            val g = with(density) { gap.toPx() }
            snapshotFlow { ime.getBottom(density) }.collectLatest {
                // Ask only once the layout for this keyboard height has landed. A
                // request measured against the old, taller viewport counts as
                // "already in view" and is dropped on the spot. Two frames: the
                // first can still be the one whose layout is pending. While the
                // keyboard animates, each new height cancels the wait, so the ask
                // goes out once it settles (the scroll's own focus tracking keeps
                // the field's line in view meanwhile).
                withFrameNanos { }
                withFrameNanos { }
                val s = size
                requester.bringIntoView(if (s == IntSize.Zero) null else Rect(0f, -g, s.width.toFloat(), s.height + g))
            }
        }
    }
    this.onFocusChanged { focused = it.hasFocus }
        .onSizeChanged { size = it }
        .bringIntoViewRequester(requester)
}
