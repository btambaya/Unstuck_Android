package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/** Selectable pill chip (filters, estimate/priority/area pickers). Dark-ink
 *  active by default; pass `accent` for a colored selected fill.
 *
 *  `a11yLabel` overrides the spoken label when the visible text is ambiguous
 *  (e.g. single-letter weekday chips "S"/"T" → "Sunday"/"Tuesday"). The hit
 *  target is grown to the 48dp minimum without changing the drawn pill. */
@Composable
fun SelectableChip(label: String, selected: Boolean, accent: Color? = null, a11yLabel: String? = null, onClick: () -> Unit) {
    val c = UTheme.colors
    val bg = if (selected) (accent ?: c.ink) else c.bg2
    // Selected fill is c.ink, which flips to near-white in dark mode — so the
    // label must use c.bg (the inverse) to stay legible in both themes. A custom
    // `accent` fill is a mid-tone, so white reads there.
    Text(
        label,
        // `selectable` announces the selected/unselected state via Role.Tab/checkbox
        // semantics; minimumInteractiveComponentSize grows the touch target to 48dp
        // around the (visually unchanged) pill.
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .let { m -> if (a11yLabel != null) m.semantics { contentDescription = a11yLabel } else m }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = UFont.sans(13, FontWeight.Medium),
        color = if (selected) (if (accent != null) Color.White else c.bg) else c.ink2,
    )
}

/** Vivid dot color per capture tag — lets capture-tag chips use the same
 *  dotted FilterPill aesthetic as the Area pills. */
@Composable
fun captureTagDot(tag: CaptureTag): Color {
    val c = UTheme.colors
    return when (tag) {
        CaptureTag.IDEA -> c.amber
        CaptureTag.EDIT -> c.blue
        CaptureTag.QUESTION -> c.green
        CaptureTag.DISTRACTION -> c.coral
        else -> c.primary   // follow-up
    }
}
