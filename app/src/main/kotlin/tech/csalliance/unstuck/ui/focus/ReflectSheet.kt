package tech.csalliance.unstuck.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

/**
 * End-of-session acknowledgment — a calm "you finished" beat over a scrim.
 *
 * The mockup imagined a "how did it land?" mood picker, but neither the web app
 * (the feature/data source of truth) nor the Session model persists a reflection,
 * so radios here would be dead controls that imply a save that never happens. Per
 * the calm/"never surprise the user" intent, this is a momentary acknowledgment with
 * a single Done — nothing is stored, nothing pretends to be.
 */
@Composable
fun ReflectSheet(elapsedSec: Int, onDismiss: () -> Unit) {
    val c = UTheme.colors
    Box(Modifier.fillMaxSize().background(SheetScrim).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)).background(c.surface).clickable(enabled = false) {}.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("SESSION COMPLETE · ${(elapsedSec / 60.0).let { Math.round(it).toInt() }}M", color = c.primaryDeep)
            Text("Nicely done.", style = UFont.serifItalic(24), color = c.ink)
            Text(
                "That block is behind you. Take a breath before the next one.",
                style = UFont.sans(14), color = c.ink3,
            )
            Box(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(999.dp)).background(c.ink)
                    .clickable(onClick = onDismiss).padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Done", style = UFont.sans(13, FontWeight.SemiBold), color = c.bg, textAlign = TextAlign.Center)
            }
        }
    }
}
