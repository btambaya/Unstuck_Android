package tech.csalliance.unstuck.ui.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.logic.SHARE_SUMMARY_NONE
import tech.csalliance.unstuck.core.logic.shareWithLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/**
 * The New task sheet's ONE share control: "Share with…", a one-line summary of
 * who was picked ("Only you" / "James · can edit" / "James + 3 more · can
 * edit" — core `shareWithSummary`) and a chevron. A tap opens the Share screen
 * in its PRE-CREATE mode ([ShareTarget.NewTask]). It replaced a stack of
 * per-person cards, each with a full-width Off / View / Partner / Assign switch
 * (Ahmad, 2026-09-24).
 *
 * Styled as the Share screen's own link row: a `bg2` card with a `line` ring.
 * No new colours — the summary is `ink2` once someone is picked, `ink3` for
 * "Only you". One line always: the summary ellipsizes rather than wrapping.
 * TalkBack reads "Share with, Only you, Button".
 */
@Composable
fun ShareWithRow(summary: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = UTheme.colors
    val label = shareWithLabel(summary)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Share with…", style = UFont.sans(14, FontWeight.Medium), color = c.ink, maxLines = 1)
        Text(
            summary, style = UFont.sans(13), color = if (summary == SHARE_SUMMARY_NONE) c.ink3 else c.ink2,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
}
