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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.Role
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
    // Pass the paused state on the initial arm so reopening a paused (saved-for-later)
    // session doesn't flash "FOCUSING" for a frame before the paused update lands.
    LaunchedEffect(sessionStart) { if (sessionStart != null) FocusTimerService.start(context, task.name, sessionStart, paused = live?.paused == true) }
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

    // Ambient focus audio — play the loop while focusing when the Ambient setting is on
    // (was a dead toggle). Stops on leave. (Start chime / overrun bell / completion sound
    // still need their own audio assets before they can be wired.)
    androidx.compose.runtime.DisposableEffect(settings.ambient) {
        if (settings.ambient != "off") tech.csalliance.unstuck.surface.AmbientAudio.start(context)
        else tech.csalliance.unstuck.surface.AmbientAudio.stop()
        onDispose { tech.csalliance.unstuck.surface.AmbientAudio.stop() }
    }

    // --- Hands-Free Focus Copilot (Phase 1: spoken progress + optional voice replies) ---
    // The on-device VoiceController (TTS/STT) is reused; the controller drives the
    // pure FocusCopilot brain off the per-second tick below. Effects route to the VM.
    val copilotVoice = tech.csalliance.unstuck.ui.assistant.rememberVoiceController()
    val copilot = remember(task.id) {
        FocusCopilotController(
            context = context,
            voice = copilotVoice,
            onExtend = { min -> vm.extendFocus(min) },
            onKeepGoing = { /* sets no-re-nag in the controller; nothing to persist */ },
            onStop = { vm.finishFocus(task, markDone = false) },
            onCapture = { text -> vm.saveCapture(task.id, vm.liveSession.value?.id, tech.csalliance.unstuck.core.model.CaptureTag.FOLLOW_UP, text) },
        )
    }
    // Ask for the mic ONCE when "Voice replies" is enabled (speak-only needs no mic).
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* if denied, the listen window simply no-ops (sttAvailable/startListening fail-safe) */ }
    LaunchedEffect(settings.focusCopilotVoice, settings.focusCopilotSpeak) {
        if (settings.focusCopilotSpeak && settings.focusCopilotVoice) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) runCatching { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
        }
    }
    // Reset per session; tear the copilot down on leave (no speech leaks past focus).
    DisposableEffect(task.id) { copilot.reset(); onDispose { copilot.stopAll() } }

    var confirmExit by remember { mutableStateOf(false) }
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

    // Copilot tick: feed ACCUMULATED FOCUS seconds (paused time excluded) only while
    // the session is actively running. Pausing/ending tears the copilot down so it
    // never speaks over a paused screen. Whole call is fail-safe inside the controller.
    val focusSec = if (l != null && sessionStart != null && !paused) FocusTimer.displayedElapsedSec(l, nowMs) else -1
    LaunchedEffect(focusSec, paused) {
        if (paused || sessionStart == null) { copilot.stopAll(); return@LaunchedEffect }
        if (focusSec >= 0) {
            copilot.onTick(
                estimateMin = l?.sessionEstimateMin ?: task.estimateMin,
                level = settings.notificationLevel.copilotLevel,
                focusedSec = focusSec,
                enabled = settings.focusCopilotSpeak,
                voiceReplies = settings.focusCopilotVoice,
            )
        }
    }

    // Dark indigo radial background.
    val bg = Brush.radialGradient(listOf(oklch(0.30, 0.10, 280.0), oklch(0.16, 0.02, 280.0)), center = Offset(0.5f, 0f), radius = 1400f)

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // "← Out" leaves the timer RUNNING (the live session persists so you
                // can return) — it does NOT discard. Matches the web's leave-focus flow.
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.10f)).clickable {
                    // Soft-exit confirm (web parity) when leaving a RUNNING session.
                    if (settings.focusSoftExit && !paused && sessionStart != null) confirmExit = true else onClose()
                }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("← Out", style = UFont.sans(12), color = Color.White.copy(alpha = 0.7f))
                }
                Box {}
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(if (paused) "PAUSED" else "FOCUSING", color = Color.White.copy(alpha = 0.55f))

            // Hands-free "Listening…" pill — only while the copilot mic window is live
            // (a few seconds after a spoken question). Makes mic use unmistakable.
            if (copilot.listening) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.coral.copy(alpha = 0.22f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(c.coral))
                    Text("Listening…", style = UFont.sans(12, FontWeight.Medium), color = Color.White.copy(alpha = 0.9f))
                }
            }

            // Always show the treatment switcher — including in Monk — so picking
            // Monk doesn't trap the user with no way back out.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusTreatment.entries.forEach { t ->
                    // Explicit white-on-dark chips (not SelectableChip, whose unselected
                    // state uses the light theme bg2 — bright on the dark focus bg, which
                    // made the UNSELECTED chips look selected). Selected = solid white.
                    val sel = treatment == t
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (sel) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.08f))
                            .clickable { vm.setTreatment(t) }
                            .padding(horizontal = 13.dp, vertical = 6.dp),
                    ) {
                        Text(t.name.lowercase(), style = UFont.sans(12, FontWeight.Medium), color = if (sel) Color(0xFF14122A) else Color.White.copy(alpha = 0.7f))
                    }
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
                // The smallest concrete step, surfaced during focus (web parity).
                task.firstPhysicalAction?.takeIf { it.isNotBlank() }?.let {
                    Text("→ $it", style = UFont.sans(13), color = Color.White.copy(alpha = 0.82f), textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
                }
                Text("${task.estimateMin}m estimate", style = UFont.sans(13), color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp))
            }
            Text(formatMMSS(elapsed), style = UFont.sans(52, FontWeight.Light), color = if (state == FocusState.OVERRUN) c.coral else Color.White, modifier = Modifier.padding(top = 20.dp))
            Text("${formatMMSS(remaining)} left", style = UFont.sans(12), color = Color.White.copy(alpha = 0.5f))

            // Overrun check-in (web parity): past the estimate, offer to extend or stop —
            // not just a recolored timer. Add 10 / "in the zone" (+15) / stop here.
            if (state == FocusState.OVERRUN && !paused) {
                Text("Past your estimate — still going well?", style = UFont.sans(12), color = c.coral.copy(alpha = 0.9f), modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusBtn("+10 min", soft = true) { vm.extendFocus(10) }
                    FocusBtn("In the zone", soft = true) { vm.extendFocus(15) }
                    FocusBtn("Stop here", soft = false) { vm.finishFocus(task, markDone = false); FocusTimerService.stop(context); PausedCheckinScheduler.cancel(context); onClose() }
                }
            }

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
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button) {
                        vm.pauseFocus()
                        if (settings.focusPauseReasons) { exitAfterReason = true; showPauseReasons = true } else onClose()
                    }.minimumInteractiveComponentSize().padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Text(
                    "End for now", style = UFont.sans(13, FontWeight.Medium), color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button) { reflectElapsed = FocusTimer.elapsedSec(l ?: return@clickable, nowMs); vm.finishFocus(task, markDone = false); FocusTimerService.stop(context); PausedCheckinScheduler.cancel(context); showReflect = true }.minimumInteractiveComponentSize().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        if (confirmExit) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Leave focus?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = { Text("Your timer keeps running — you can pick it back up from Today.", style = UFont.sans(13), color = c.ink2) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmExit = false; onClose() }) { Text("Leave", color = c.primaryDeep) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmExit = false }) { Text("Stay", color = c.ink2) } },
            containerColor = c.surface,
        )
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
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (soft) Color.White.copy(alpha = 0.10f) else c.coral)
            .clickable(role = Role.Button, onClick = onClick).minimumInteractiveComponentSize()
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
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
