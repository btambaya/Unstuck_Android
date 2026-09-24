package tech.csalliance.unstuck.ui.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.logic.SharePick
import tech.csalliance.unstuck.core.logic.shareRowMonograms
import tech.csalliance.unstuck.core.logic.shareWithLabel
import tech.csalliance.unstuck.core.logic.shareWithSummary
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/**
 * The New task sheet's ONE share control: "Share with…", up to three
 * overlapping monograms of who was picked, a one-line summary ("Only you" /
 * "James · can edit" / "James + 3 more · can edit" — core `shareWithSummary`,
 * the rule every platform uses) and a chevron. A tap opens the Share screen in
 * its PRE-CREATE mode ([ShareTarget.NewTask]). It replaced a stack of
 * per-person cards, each with a full-width Off / View / Partner / Assign switch
 * (Ahmad, 2026-09-24).
 *
 * Styled as the Share screen's own link row: a `bg2` card with a `line` ring.
 * No new colours — the monograms are the Share screen's "has it" pair (`ink`
 * disc, `bg` letter), each ringed in the card's `bg2` so the overlap reads; the
 * summary is `ink2` once someone is picked, `ink3` for "Only you". One line
 * always: the monograms drop out when they and the summary don't both fit, and
 * the summary ellipsizes as a last resort. TalkBack reads "Share with, Only
 * you, Button" (the monograms are decorative — the summary says who).
 */
@Composable
fun ShareWithRow(picks: List<SharePick>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = UTheme.colors
    val summary = shareWithSummary(picks)
    val letters = shareRowMonograms(picks)
    val label = shareWithLabel(summary)
    val summaryStyle = UFont.sans(13)
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
        BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val summaryWidth = remember(summary, summaryStyle, density) {
                with(density) { measurer.measure(summary, summaryStyle, maxLines = 1).size.width.toDp() }
            }
            val showMonograms = monogramsFit(letters.size, summaryWidth, maxWidth)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showMonograms) {
                    MonogramStack(letters, ring = c.bg2)
                    Spacer(Modifier.width(MONO_GAP))
                }
                Text(
                    summary, style = summaryStyle, color = if (picks.isEmpty()) c.ink3 else c.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
}

/** A disc's size, how far each disc tucks under the next, and the ring each is
 *  drawn with — the same on every platform (4 overlap + 1.5 ring keeps every
 *  covered letter whole: a 10sp letter, centred, ends ~7dp short of the edge). */
internal val MONO_SIZE = 22.dp
internal val MONO_OVERLAP = 4.dp
internal val MONO_RING = 1.5.dp
private val MONO_GAP = 8.dp

/** Whether [n] monograms fit beside a summary [summaryWidth] wide in [room]:
 *  they show only when both fit on the one line — otherwise the summary wins. */
internal fun monogramsFit(n: Int, summaryWidth: Dp, room: Dp): Boolean =
    n > 0 && summaryWidth + monogramStackWidth(n) + MONO_GAP <= room

/** The width of [n] overlapping discs (the ring is drawn outside the layout). */
internal fun monogramStackWidth(n: Int): Dp =
    if (n <= 0) 0.dp else MONO_SIZE + (MONO_SIZE - MONO_OVERLAP) * (n - 1)

/** Up to three overlapping monograms, the later one on top, each ringed in
 *  [ring] (the card's surface) so the edge between two discs reads. */
@Composable
private fun MonogramStack(letters: List<String>, ring: Color) {
    val c = UTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(-MONO_OVERLAP)) {
        letters.forEach { letter ->
            Box(
                Modifier.size(MONO_SIZE)
                    .drawBehind { drawCircle(ring, radius = size.minDimension / 2 + MONO_RING.toPx()) }
                    .clip(CircleShape).background(c.ink),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, style = UFont.sans(10, FontWeight.SemiBold), color = c.bg, maxLines = 1)
            }
        }
    }
}
