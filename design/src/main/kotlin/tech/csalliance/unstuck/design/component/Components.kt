package tech.csalliance.unstuck.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.csalliance.unstuck.design.theme.Radius
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

// Workhorse brand primitives, styled to the Android Mockups. Material-3 chrome
// (AppBar, BottomNav, FAB, fields, toggles) lives in Chrome.kt / Controls.kt.

enum class ButtonKind { CORAL, DARK, OUTLINED, GHOST, DANGER, TEXT, PRIMARY }

private val pill = RoundedCornerShape(999.dp)

/** Brand pill button. CORAL = accent (Focus); DARK = submit (Add/Sign-in/Save);
 *  OUTLINED / GHOST / TEXT / DANGER as named. `fill` stretches to full width. */
@Composable
fun UButton(
    title: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.CORAL,
    enabled: Boolean = true,
    fill: Boolean = true,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = UTheme.colors
    val bg = when (kind) {
        ButtonKind.CORAL, ButtonKind.PRIMARY -> c.coral
        ButtonKind.DARK -> c.ink
        ButtonKind.DANGER -> c.red
        ButtonKind.GHOST -> c.bg2
        ButtonKind.OUTLINED, ButtonKind.TEXT -> Color.Transparent
    }
    val fg = when (kind) {
        ButtonKind.CORAL, ButtonKind.PRIMARY, ButtonKind.DANGER -> Color.White
        ButtonKind.DARK -> c.bg
        ButtonKind.GHOST -> c.ink
        ButtonKind.OUTLINED -> c.ink
        ButtonKind.TEXT -> c.ink3
    }
    val base = Modifier
        .then(if (fill) Modifier.fillMaxWidth() else Modifier)
        .clip(pill)
        .then(if (kind == ButtonKind.OUTLINED) Modifier.border(1.dp, c.line2, pill) else Modifier)
        .background(bg)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 12.dp)
    Row(modifier.then(base), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(title, style = UFont.sans(14, FontWeight.SemiBold), color = fg)
    }
}

/** Small life-area / collection color dot resolved from a TOKEN. */
@Composable
fun AreaDot(token: String?, modifier: Modifier = Modifier, size: Int = 8) {
    Box(modifier.size(size.dp).clip(CircleShape).background(UTheme.colors.areaColor(token)))
}

/** Color dot from an already-resolved Color (task area names resolve upstream). */
@Composable
fun AreaDotColor(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Rounded color-chip swatch (area/collection icon): tinted square + inner dot. */
@Composable
fun ColorChip(color: Color, modifier: Modifier = Modifier, box: Int = 30, dot: Int = 9) {
    val c = UTheme.colors
    Box(
        modifier.size(box.dp).clip(RoundedCornerShape(8.dp)).background(c.areaSwatch(color)),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(dot.dp).clip(CircleShape).background(color)) }
}

/** Filter / segmented pill — dark-ink active, bg2 inactive, optional area dot. */
@Composable
fun FilterPill(label: String, selected: Boolean, modifier: Modifier = Modifier, dotColor: Color? = null, onClick: () -> Unit) {
    val c = UTheme.colors
    Row(
        modifier.clip(pill).background(if (selected) c.ink else c.bg2).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dotColor != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Text(label, style = UFont.sans(12, FontWeight.Medium), color = if (selected) c.bg else c.ink2, maxLines = 1)
    }
}

@Composable
fun Chip(title: String, selected: Boolean = false, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Box(modifier.clip(pill).background(if (selected) c.primary else c.bg2).padding(horizontal = 11.dp, vertical = 5.dp)) {
        Text(title, style = UFont.sans(13, FontWeight.Medium), color = if (selected) Color.White else c.ink2)
    }
}

/** Eyebrow / section label — IBM Plex Mono, 10.5sp, 0.08em, uppercase. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = UFont.mono(11, FontWeight.Medium).copy(letterSpacing = 0.9.sp),
        color = color ?: UTheme.colors.ink3,
    )
}

/** Flat surface card: white, 1px hairline ring, no elevation. */
@Composable
fun Card(modifier: Modifier = Modifier, radius: Int = 18, pad: Int = 16, content: @Composable () -> Unit) {
    val c = UTheme.colors
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(pad.dp)) { content() }
}

@Composable
fun LabeledRow(label: String, value: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = UFont.sans(14), color = c.ink2)
        Text(value, style = UFont.sans(14, FontWeight.Medium), color = c.ink)
    }
}

/** Insights stat card: eyebrow + big value + colored badge pill + caption. */
@Composable
fun StatCard(
    label: String,
    value: String,
    badge: String? = null,
    badgeBg: Color? = null,
    badgeFg: Color? = null,
    caption: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = UTheme.colors
    Card(modifier.fillMaxWidth(), radius = 18, pad = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(label)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(value, style = UFont.sans(28, FontWeight.SemiBold).copy(letterSpacing = (-0.5).sp), color = c.ink)
                if (badge != null) {
                    Box(Modifier.clip(pill).background(badgeBg ?: c.greenSoft).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(badge, style = UFont.sans(11, FontWeight.SemiBold), color = badgeFg ?: c.greenInk)
                    }
                }
            }
            if (caption != null) Text(caption, style = UFont.sans(13), color = c.ink2)
        }
    }
}

/** Collection item row: 18dp coral-fill checkbox + bullet dot + body (struck when done). */
@Composable
fun ItemRow(body: String, done: Boolean, pinned: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val c = UTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                .then(if (done) Modifier.background(c.coral) else Modifier.border(1.5.dp, c.line2, RoundedCornerShape(5.dp)))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", style = UFont.sans(11, FontWeight.Bold), color = Color.White)
        }
        if (!done) Box(Modifier.size(if (pinned) 6.dp else 4.dp).clip(CircleShape).background(if (pinned) c.coral else c.ink4))
        Text(
            body,
            modifier = Modifier.weight(1f),
            style = UFont.sans(14, if (done) FontWeight.Normal else FontWeight.Normal),
            color = if (done) c.ink3 else c.ink,
            textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Reflect / radio option row. */
@Composable
fun RadioOptionRow(label: String, selected: Boolean, selectedBg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = UTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) selectedBg else c.bg2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape).background(if (selected) selectedBg else c.line2)
                .then(if (selected) Modifier.border(1.5.dp, c.ink, CircleShape) else Modifier),
        )
        Text(label, style = UFont.sans(14, FontWeight.Medium), color = c.ink)
    }
}
