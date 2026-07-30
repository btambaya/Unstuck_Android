package tech.csalliance.unstuck.ui.tour

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.design.color.oklch
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

// Round-2 #6 — the DEMO focus surface: the focus + capture steps present this
// tour-rendered replica of FocusScreen (calm indigo background, mid-progress
// ring, demo title, capture hint) instead of navigating anywhere. It mints
// ZERO sessions and performs ZERO navigation — every control is inert (the
// whole surface swallows input), and the spotlight targets live INSIDE it
// (Modifier.tourAnchor on the ring / the header capture cluster). Rendered
// by TourHost under the panel/spotlight, over the lockdown scrim.
//
// Round-3 exception: on the CAPTURE step the Capture pill is LIVE
// ([capturePillEnabled]) — tapping it opens the DEMO capture sheet
// (TourDemoCaptureSheet below, rendered topmost by TourHost). Everything else
// stays inert, and a demo save flashes the auto-fading "Saved — demo" line.

/** Demo session facts — 18:24 into a 40-minute estimate (mid-progress ring). */
const val TOUR_DEMO_TASK_TITLE = "Write the project brief"
const val TOUR_DEMO_FIRST_ACTION = "Open the document"
const val TOUR_DEMO_ESTIMATE_MIN = 40
const val TOUR_DEMO_ELAPSED_SEC = 18 * 60 + 24

/** Ring fill for the demo session (elapsed / estimate). Pure — unit-tested. */
fun tourDemoProgress(): Float = TOUR_DEMO_ELAPSED_SEC / (TOUR_DEMO_ESTIMATE_MIN * 60f)

@Composable
fun TourDemoFocus(
    /** Round-3: the Capture pill is tappable (capture step only) — it opens
     *  the DEMO capture sheet via [onCapturePill]. */
    capturePillEnabled: Boolean = false,
    onCapturePill: () -> Unit = {},
    /** Bumped by the host on each demo save — flashes "Saved — demo". */
    savedEpoch: Int = 0,
) {
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
                    // Round-3: LIVE on the capture step (the step's cutout is
                    // interactive there, so the tap reaches the pill) — opens
                    // the DEMO capture sheet. Inert on the focus step.
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.10f))
                            .then(if (capturePillEnabled) Modifier.clickable(onClick = onCapturePill) else Modifier)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
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

        // Round-3: auto-fading demo-save confirmation — an overlay chip under
        // the header (never shifts the demo layout), keyed on savedEpoch so
        // every save re-flashes. Not hit-testable; purely visual.
        // Flash only when the epoch INCREASES after mount: the host never
        // resets the counter, so comparing against the mount-time baseline
        // (not > 0) keeps a remounted demo (revisit via Back / resume chip /
        // same-process restart) from re-flashing a save that already showed.
        var savedVisible by remember { mutableStateOf(false) }
        val savedBaseline = remember { savedEpoch }
        LaunchedEffect(savedEpoch) {
            if (savedEpoch > savedBaseline) {
                savedVisible = true
                delay(1600)
                savedVisible = false
            }
        }
        AnimatedVisibility(
            visible = savedVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
        ) {
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Saved — demo", style = UFont.sans(12, FontWeight.Medium), color = Color.White)
            }
        }
    }
}

/* ============================================================
 * Round-3: the DEMO capture sheet — a visually-faithful lookalike
 * of the real focus CaptureSheet (ui/focus/CaptureSheet.kt: handle,
 * "CAPTURE · STAYS ATTACHED", the one-line thought field, the five
 * tag pills, the dark Save) rendered INSIDE the tour's own
 * composition — deliberately NOT a real ModalBottomSheet, so the
 * tour's input layering stays intact (TourHost mounts it as the
 * TOPMOST layer of the RUNNING branch, above blockers + panel).
 *
 * Typing is allowed; Save persists NOTHING — no store, no network —
 * it only closes (the demo surface flashes "Saved — demo"). The
 * sheet carries its OWN imePadding so it rides above the keyboard
 * (the tour overlay itself is deliberately un-padded — round-2 Ask
 * lesson), and its field never touches the panel's askFocused path.
 * ============================================================ */
private val DEMO_CAPTURE_TAGS = listOf("follow-up", "idea", "edit", "question", "distraction")

@Composable
fun TourDemoCaptureSheet(onSave: () -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    var text by remember { mutableStateOf("") }
    var tagIdx by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        // Scrim — swallows everything beneath; a tap dismisses (parity with
        // the real modal sheet's scrim-dismiss). No ripple over a scrim.
        Box(
            Modifier.fillMaxSize().background(SheetScrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.surface)
                // Card taps never fall through to the scrim's dismiss (the
                // inner field/pills/Save still get every event first — the
                // Main pass runs leaf-up).
                .tourBlockInput()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CAPTURE · STAYS ATTACHED", style = UFont.mono(11, FontWeight.Medium), color = c.ink3)
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = UFont.sans(19, FontWeight.Medium).copy(color = c.ink),
                    singleLine = true, cursorBrush = SolidColor(c.ink),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    decorationBox = { inner -> if (text.isEmpty()) Text("What just popped up?", style = UFont.sans(19, FontWeight.Medium), color = c.ink4); inner() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    DEMO_CAPTURE_TAGS.forEachIndexed { i, label ->
                        val sel = tagIdx == i
                        // The real sheet's per-tag soft-bg / dark-ink pairs.
                        val (selBg, selFg) = when (label) {
                            "idea" -> c.amberSoft to c.amberInk
                            "edit" -> c.blueSoft to c.blueInk
                            "question" -> c.greenSoft to c.greenInk
                            "distraction" -> c.coralSoft to c.coralDeep
                            else -> c.primarySoft to c.primaryDeep
                        }
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) selBg else c.surface).then(if (sel) Modifier else Modifier.border(1.dp, c.line2, RoundedCornerShape(999.dp))).clickable { tagIdx = i }.padding(horizontal = 11.dp, vertical = 5.dp)) {
                            Text(label, style = UFont.sans(12, FontWeight.Medium), color = if (sel) selFg else c.ink3)
                        }
                    }
                }
                Box(Modifier.padding(top = 4.dp)) {
                    UButton("Save", kind = ButtonKind.DARK, fill = false, enabled = text.isNotBlank()) {
                        // A DEMO capture is never stored — saving only closes.
                        onSave()
                    }
                }
            }
        }
    }
}
