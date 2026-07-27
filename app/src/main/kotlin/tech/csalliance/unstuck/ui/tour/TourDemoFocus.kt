package tech.csalliance.unstuck.ui.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.design.color.oklch
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

// Round-2 #6 — the DEMO focus surface: the focus + capture steps present this
// tour-rendered replica of FocusScreen (calm indigo background, mid-progress
// ring, demo title, capture hint) instead of navigating anywhere. It mints
// ZERO sessions and performs ZERO navigation — every control is inert (the
// whole surface swallows input), and the spotlight targets live INSIDE it
// (Modifier.tourAnchor on the ring / the header capture cluster). Rendered
// by TourHost under the panel/spotlight, over the lockdown scrim.

/** Demo session facts — 18:24 into a 40-minute estimate (mid-progress ring). */
const val TOUR_DEMO_TASK_TITLE = "Write the project brief"
const val TOUR_DEMO_FIRST_ACTION = "Open the document"
const val TOUR_DEMO_ESTIMATE_MIN = 40
const val TOUR_DEMO_ELAPSED_SEC = 18 * 60 + 24

/** Ring fill for the demo session (elapsed / estimate). Pure — unit-tested. */
fun tourDemoProgress(): Float = TOUR_DEMO_ELAPSED_SEC / (TOUR_DEMO_ESTIMATE_MIN * 60f)

@Composable
fun TourDemoFocus() {
    val c = UTheme.colors
    // FocusScreen's dark indigo radial background, verbatim.
    val bg = Brush.radialGradient(
        listOf(oklch(0.30, 0.10, 280.0), oklch(0.16, 0.02, 280.0)),
        center = Offset(0.5f, 0f), radius = 1400f,
    )
    val remaining = (TOUR_DEMO_ESTIMATE_MIN * 60 - TOUR_DEMO_ELAPSED_SEC).coerceAtLeast(0)

    Box(Modifier.fillMaxSize().background(bg).tourBlockInput()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("← Out", style = UFont.sans(12), color = Color.White.copy(alpha = 0.7f))
                }
                // Header capture cluster — the CAPTURE step's spotlight target.
                Row(
                    Modifier.tourAnchor(TourAnchorIds.DEMO_CAPTURE_HINT),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("Capture", style = UFont.sans(12), color = Color.White.copy(alpha = 0.7f))
                    }
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("FOCUSING", color = Color.White.copy(alpha = 0.55f))

            // Treatment chips (ambient selected) — inert, look only.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ambient" to true, "cockpit" to false, "monk" to false).forEach { (label, sel) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (sel) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 13.dp, vertical = 6.dp),
                    ) {
                        Text(label, style = UFont.sans(12, FontWeight.Medium), color = if (sel) Color(0xFF14122A) else Color.White.copy(alpha = 0.7f))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // The mid-progress ring — the FOCUS step's spotlight target.
            Box(Modifier.size(220.dp).tourAnchor(TourAnchorIds.DEMO_FOCUS_RING), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(200.dp)) {
                    val r = size.minDimension / 2f - 4f
                    val cen = Offset(size.width / 2f, size.height / 2f)
                    drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, Offset(cen.x - r, cen.y - r), Size(r * 2, r * 2), style = Stroke(width = 4f))
                    drawArc(Color.White, -90f, 360f * tourDemoProgress(), false, Offset(cen.x - r, cen.y - r), Size(r * 2, r * 2), style = Stroke(width = 4f, cap = StrokeCap.Round))
                }
                Orbit(size = 130, white = true)
            }

            Spacer(Modifier.height(20.dp))
            Text(TOUR_DEMO_TASK_TITLE, style = UFont.serifItalic(24), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Text("→ $TOUR_DEMO_FIRST_ACTION", style = UFont.sans(13), color = Color.White.copy(alpha = 0.82f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
            Text("${TOUR_DEMO_ESTIMATE_MIN}m estimate", style = UFont.sans(13), color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp))
            Text(formatMMSS(TOUR_DEMO_ELAPSED_SEC), style = UFont.sans(52, FontWeight.Light), color = Color.White, modifier = Modifier.padding(top = 20.dp))
            Text("${formatMMSS(remaining)} left", style = UFont.sans(12), color = Color.White.copy(alpha = 0.5f))

            Spacer(Modifier.weight(1f))

            // Session controls — inert lookalikes (the surface swallows input).
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 22.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Pause", style = UFont.sans(14, FontWeight.Medium), color = Color.White) }
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.coral).padding(horizontal = 22.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Done", style = UFont.sans(14, FontWeight.Medium), color = Color.White) }
            }
            Row(Modifier.padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Save for later", style = UFont.sans(13, FontWeight.Medium), color = Color.White.copy(alpha = 0.72f), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                Text("End for now", style = UFont.sans(13, FontWeight.Medium), color = Color.White.copy(alpha = 0.72f), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}
