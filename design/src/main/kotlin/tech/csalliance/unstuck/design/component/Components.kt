package tech.csalliance.unstuck.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.csalliance.unstuck.design.theme.Radius
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

// Core brand components ported from ../unstuck/components/ui/* via the iOS
// Components.swift. The Orbit Mark/Wordmark is a P7-polish item; these are the
// workhorse primitives the feature screens build on.

enum class ButtonKind { PRIMARY, GHOST, DANGER }

/** Brand button. Primary uses the AA-contrast coralDeep CTA. */
@Composable
fun UButton(
    title: String,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = UTheme.colors
    val bg = when (kind) {
        ButtonKind.PRIMARY -> c.coralDeep
        ButtonKind.DANGER -> c.red
        ButtonKind.GHOST -> c.bg2
    }
    val fg = when (kind) {
        ButtonKind.PRIMARY, ButtonKind.DANGER -> Color.White
        ButtonKind.GHOST -> c.ink
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(title, style = UFont.sans(15, FontWeight.Medium))
    }
}

/** Small life-area / collection color dot. */
@Composable
fun AreaDot(token: String?, modifier: Modifier = Modifier, size: Int = 8) {
    Box(modifier.size(size.dp).clip(CircleShape).background(UTheme.colors.areaColor(token)))
}

/** Pill / chip label (filters, tags). */
@Composable
fun Chip(title: String, selected: Boolean = false, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Box(
        modifier
            .clip(CircleShape)
            .background(if (selected) c.primary else c.bg2)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            title,
            style = UFont.sans(13, FontWeight.Medium),
            color = if (selected) Color.White else c.ink2,
        )
    }
}

/** Eyebrow / section label — mono, uppercase, tracked. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = UFont.mono(11, FontWeight.Medium).copy(letterSpacing = 0.8.sp),
        color = UTheme.colors.ink3,
    )
}

/** Surface card container. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = UTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        color = c.surface,
        border = BorderStroke(1.dp, c.line),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

/** Horizontal labelled row used in settings/detail panes. */
@Composable
fun LabeledRow(label: String, value: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = UFont.sans(14), color = c.ink2)
        Text(value, style = UFont.sans(14, FontWeight.Medium), color = c.ink)
    }
}
