package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/** Selectable pill chip (filters, estimate/priority/area pickers). Dark-ink
 *  active by default; pass `accent` for a colored selected fill. */
@Composable
fun SelectableChip(label: String, selected: Boolean, accent: Color? = null, onClick: () -> Unit) {
    val c = UTheme.colors
    val bg = if (selected) (accent ?: c.ink) else c.bg2
    // Selected fill is c.ink, which flips to near-white in dark mode — so the
    // label must use c.bg (the inverse) to stay legible in both themes. A custom
    // `accent` fill is a mid-tone, so white reads there.
    Text(
        label,
        modifier = Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
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
