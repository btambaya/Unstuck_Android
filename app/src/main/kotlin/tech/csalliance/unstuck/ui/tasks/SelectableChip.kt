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
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/** Selectable pill chip (filters, estimate/priority/area pickers). Dark-ink
 *  active by default; pass `accent` for a colored selected fill. */
@Composable
fun SelectableChip(label: String, selected: Boolean, accent: Color? = null, onClick: () -> Unit) {
    val c = UTheme.colors
    val bg = if (selected) (accent ?: c.ink) else c.bg2
    Text(
        label,
        modifier = Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = UFont.sans(13, FontWeight.Medium),
        color = if (selected) Color.White else c.ink2,
    )
}
