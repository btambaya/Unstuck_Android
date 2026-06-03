package tech.csalliance.unstuck.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.core.model.FocusState
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.color.oklch
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.surface.FocusTimerService
import tech.csalliance.unstuck.surface.PausedCheckinScheduler
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.tasks.SelectableChip

@Composable
fun FocusScreen(vm: AppViewModel, task: TaskItem, onClose: () -> Unit, autoCapture: Boolean = false) {
    val c = UTheme.colors
    val live by vm.liveSession.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(task.id) { vm.startFocus(task) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }

    val sessionStart = live?.sessionStart
    LaunchedEffect(sessionStart) { if (sessionStart != null) FocusTimerService.start(context, task.name, sessionStart) }
    // The ongoing notification + paused-too-long check-in track the live session,
    // so they persist (and stay correct) even after you leave the focus screen.
    // We do NOT stop the service on dispose — leaving focus keeps the session live
    // and glanceable; it's torn down only by the terminal Done / End actions.
    LaunchedEffect(live?.paused, sessionStart) {
        if (sessionStart != null) {
            FocusTimerService.update(context, paused = live?.paused == true, startMs = sessionStart)
            if (live?.paused == true) PausedCheckinScheduler.arm(context, task.name)
            else PausedCheckinScheduler.cancel(context)
        }
    }

    var showCapture by remember { mutableStateOf(autoCapture) }
    // Arriving via the notification "Capture" action opens the capture sheet straight
    // away (was landing on Focus with no input shown).
    LaunchedEffect(autoCapture) { if (autoCapture) showCapture = true }
    var showReflect by remember { mutableStateOf(false) }
    var showPauseReasons by remember { mutableStateOf(false) }
    var exitAfterReason by remember { mutableStateOf(false) }
    var reflectElapsed by remember { mutableStateOf(0) }

    val l = live
    val treatment = l?.treatment ?: FocusTreatment.AMBIENT
    val paused = l?.paused == true
    val elapsed = if (l != null) FocusTimer.displayedElapsedSec(l, nowMs) else 0
    // Use the session's FROZEN estimate so the ring + the overrun coloring agree
    // (deriveState reads sessionEstimateMin too), even after a mid-session edit.
    val estimateSec = (l?.sessionEstimateMin ?: task.estimateMin) * 60
    val remaining = (estimateSec - elapsed).coerceAtLeast(0)
    val progress = if (estimateSec > 0) (elapsed.toFloat() / estimateSec).coerceIn(0f, 1f) else 0f
    // Honor the Soft-overrun setting (minutes; 0 = Never) instead of a hardcoded 1s grace.
    val graceSec = if (settings.focusOverrunMin <= 0) Double.POSITIVE_INFINITY else settings.focusOverrunMin * 60.0
    val state = if (l != null) FocusTimer.deriveState(l, nowMs, graceSec) else FocusState.IDLE

    // Dark indigo radial background.
    val bg = Brush.radialGradient(listOf(oklch(0.30, 0.10, 280.0), oklch(0.16, 0.02, 280.0)), center = Offset(0.5f, 0f), radius = 1400f)

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // "← Out" leaves the timer RUNNING (the live session persists so you
                // can return) — it does NOT discard. Matches the web's leave-focus flow.
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.10f)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("← Out", style = UFont.sans(12), color = Color.White.copy(alpha = 0.7f))
                }
                Box {}
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(if (paused) "PAUSED" else "FOCUSING", color = Color.White.copy(alpha = 0.55f))

            // Always show the treatment switcher — including in Monk — so picking
            // Monk doesn't trap the user with no way back out.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusTreatment.entries.forEach { t ->
                    SelectableChip(t.name.lowercase(), selected = treatment == t, accent = Color.White.copy(alpha = 0.18f)) { vm.setTreatment(t) }
                }
            }

            Spacer(Modifier.weight(1f))

            if (treatment == FocusTreatment.AMBIENT) {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(200.dp)) {
                        val r = size.minDimension / 2f - 4f
                        val cen = Offset(size.width / 2f, size.height / 2f)
                        drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, Offset(cen.x - r, cen.y - r), Size(r * 2, r * 2), style = Stroke(width = 4f))
                        drawArc(if (paused) oklch(0.80, 0.13, 75.0) else Color.White, -90f, 360f * progress, false, Offset(cen.x - r, cen.y - r), Size(r * 2, r * 2), style = Stroke(width = 4f, cap = StrokeCap.Round))
                    }
                    Orbit(size = 130, white = true)
                }
            }

            Spacer(Modifier.height(if (treatment == FocusTreatment.MONK) 40.dp else 20.dp))
            if (treatment != FocusTreatment.MONK) {
                Text(task.name, style = UFont.serifItalic(24), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                Text("${task.estimateMin}m estimate", style = UFont.sans(13), color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp))
            }
            Text(formatMMSS(elapsed), style = UFont.sans(52, FontWeight.Light), color = if (state == FocusState.OVERRUN) c.coral else Color.White, modifier = Modifier.padding(top = 20.dp))
            Text("${formatMMSS(remaining)} left", style = UFont.sans(12), color = Color.White.copy(alpha = 0.5f))

            if (treatment == FocusTreatment.COCKPIT) {
                CapturesRail(vm, task)
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusBtn("Capture", soft = true) { showCapture = true }
                FocusBtn(if (paused) "Resume" else "Pause", soft = true) {
                    if (paused) vm.resumeFocus()
                    else { vm.pauseFocus(); if (settings.focusPauseReasons) showPauseReasons = true }
                }
                // "Done" marks the task complete (records the session + flips done).
                FocusBtn("Done", soft = false) { reflectElapsed = FocusTimer.elapsedSec(l ?: return@FocusBtn, nowMs); vm.finishFocus(task, markDone = true); FocusTimerService.stop(context); PausedCheckinScheduler.cancel(context); showReflect = true }
            }
            // Secondary actions: "Save for later" pauses + exits (resumable from Today,
            // prompting an interruption reason when enabled); "End for now" records the
            // session without completing the task.
            Row(Modifier.padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Save for later", style = UFont.sans(13, FontWeight.Medium), color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable {
                        vm.pauseFocus()
                        if (settings.focusPauseReasons) { exitAfterReason = true; showPauseReasons = true } else onClose()
                    }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Text(
                    "End for now", style = UFont.sans(13, FontWeight.Medium), color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { reflectElapsed = FocusTimer.elapsedSec(l ?: return@clickable, nowMs); vm.finishFocus(task, markDone = false); FocusTimerService.stop(context); PausedCheckinScheduler.cancel(context); showReflect = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        if (showCapture) CaptureSheet(vm, task, live?.id) { showCapture = false }
        if (showReflect) ReflectSheet(reflectElapsed) { showReflect = false; onClose() }
        if (showPauseReasons) {
            // Already paused; this records WHY (optional). When triggered by
            // "Save for later", we exit the focus screen after the reason.
            PauseReasons(
                onPick = { reason -> vm.saveReasonLog(task.id, reason); showPauseReasons = false; if (exitAfterReason) { exitAfterReason = false; onClose() } },
                onDismiss = { showPauseReasons = false; if (exitAfterReason) { exitAfterReason = false; onClose() } },
            )
        }
    }
}

private val PAUSE_REASONS = listOf("Bathroom", "Drink", "Quick question", "Stuck — need a moment", "Other")

@Composable
private fun PauseReasons(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC0B0B14)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A1B26)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("WHY ARE YOU PAUSING?", color = Color.White.copy(alpha = 0.55f))
            PAUSE_REASONS.forEach { r ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).clickable { onPick(r) }.padding(horizontal = 16.dp, vertical = 13.dp),
                ) { Text(r, style = UFont.sans(14, FontWeight.Medium), color = Color.White) }
            }
        }
    }
}

@Composable
private fun FocusBtn(label: String, soft: Boolean, onClick: () -> Unit) {
    val c = UTheme.colors
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (soft) Color.White.copy(alpha = 0.10f) else c.coral).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(label, style = UFont.sans(14, FontWeight.Medium), color = Color.White)
    }
}

@Composable
private fun CapturesRail(vm: AppViewModel, task: TaskItem) {
    val c = UTheme.colors
    val captures by vm.captures.collectAsStateWithLifecycle()
    val recent = captures.filter { it.taskId == task.id }.takeLast(3)
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Captures", color = Color.White.copy(alpha = 0.45f))
        recent.forEach { Text("• ${it.body}", style = UFont.sans(12), color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
    }
}
