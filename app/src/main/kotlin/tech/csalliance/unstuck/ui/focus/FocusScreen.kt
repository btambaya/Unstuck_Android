package tech.csalliance.unstuck.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.FocusState
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.surface.FocusTimerService
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.tasks.SelectableChip

private val REASONS = listOf("Bathroom", "Distracted", "Switching tasks", "Quick break", "Interrupted")

@Composable
fun FocusScreen(vm: AppViewModel, task: TaskItem, onClose: () -> Unit) {
    val c = UTheme.colors
    val live by vm.liveSession.collectAsStateWithLifecycle()

    // Resume-aware start (the engine no-ops if already running for this task).
    LaunchedEffect(task.id) { vm.startFocus(task) }

    // Foreground chronometer notification while focusing.
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionStart = live?.sessionStart
    LaunchedEffect(sessionStart) {
        if (sessionStart != null) FocusTimerService.start(context, task.name, sessionStart)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { FocusTimerService.stop(context) }
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(1000) }
    }

    var showReasons by remember { mutableStateOf(false) }
    var showCapture by remember { mutableStateOf(false) }
    var captureText by remember { mutableStateOf("") }
    var soundOn by remember { mutableStateOf(true) }

    // Ambient loop while focusing on the "ambient" treatment with sound on.
    val ambientOn = soundOn && live?.treatment == FocusTreatment.AMBIENT && live?.paused == false
    LaunchedEffect(ambientOn) {
        if (ambientOn) tech.csalliance.unstuck.surface.AmbientAudio.start(context)
        else tech.csalliance.unstuck.surface.AmbientAudio.stop()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { tech.csalliance.unstuck.surface.AmbientAudio.stop() }
    }

    val l = live
    Box(Modifier.fillMaxSize().background(if (l?.treatment == FocusTreatment.COCKPIT) c.bg2 else c.bg)) {
        if (l == null) {
            Text("Starting…", Modifier.align(Alignment.Center), color = c.ink3, style = UFont.sans(14))
            return@Box
        }
        val elapsed = FocusTimer.displayedElapsedSec(l, nowMs)
        val state = FocusTimer.deriveState(l, nowMs, overrunGraceSec = 1.0)

        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.cancelFocus(); onClose() }) { Icon(Icons.Outlined.Close, contentDescription = "Cancel", tint = c.ink3) }
                SectionLabel("Focus")
                Row {
                    IconButton(onClick = { soundOn = !soundOn }) {
                        Icon(
                            if (soundOn) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            contentDescription = "Toggle sound", tint = c.ink3,
                        )
                    }
                    IconButton(onClick = { showCapture = true }) { Icon(Icons.Outlined.EditNote, contentDescription = "Capture", tint = c.ink3) }
                }
            }

            if (l.treatment != FocusTreatment.MONK) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusTreatment.entries.forEach { t ->
                        SelectableChip(t.name.lowercase(), selected = l.treatment == t) { vm.setTreatment(t) }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (l.treatment != FocusTreatment.MONK) {
                Text(task.name, style = UFont.serifItalic(24), color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(20.dp))
            }

            Text(
                formatMMSS(elapsed),
                style = UFont.mono(56, FontWeight.Medium),
                color = if (state == FocusState.OVERRUN) c.coralDeep else c.ink,
            )
            Text(stateLabel(state), style = UFont.mono(12), color = c.ink3)
            if (l.treatment == FocusTreatment.COCKPIT) {
                Text("Estimate ${task.estimateMin}m", style = UFont.mono(11), color = c.ink3)
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (l.paused) {
                    UButton("Resume") { vm.resumeFocus() }
                } else {
                    UButton("Pause", kind = ButtonKind.GHOST) { showReasons = true }
                }
                if (state == FocusState.OVERRUN) {
                    UButton("Add 5 min", kind = ButtonKind.GHOST) { vm.extendFocus(5) }
                }
                UButton("Done") { vm.finishFocus(task); onClose() }
            }
            if (l.treatment == FocusTreatment.MONK) {
                TextButton(onClick = { vm.setTreatment(FocusTreatment.AMBIENT) }) {
                    Text("Treatments", color = c.ink3, style = UFont.sans(12))
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }

    if (showReasons) {
        AlertDialog(
            onDismissRequest = { showReasons = false },
            confirmButton = {
                TextButton(onClick = { vm.pauseFocus(); showReasons = false }) { Text("Just pause") }
            },
            title = { Text("Why are you pausing?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    REASONS.forEach { r ->
                        TextButton(onClick = { vm.saveReasonLog(task.id, r); vm.pauseFocus(); showReasons = false }) { Text(r) }
                    }
                }
            },
        )
    }

    if (showCapture) {
        AlertDialog(
            onDismissRequest = { showCapture = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveCapture(task.id, l?.id, CaptureTag.IDEA, captureText)
                    captureText = ""; showCapture = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCapture = false }) { Text("Cancel") } },
            title = { Text("Park a thought") },
            text = {
                OutlinedTextField(value = captureText, onValueChange = { captureText = it }, label = { Text("Note to self…") }, modifier = Modifier.fillMaxWidth())
            },
        )
    }
}

private fun stateLabel(state: FocusState): String = when (state) {
    FocusState.RUNNING -> "FOCUSING"
    FocusState.PAUSE -> "PAUSED"
    FocusState.OVERRUN -> "OVERTIME"
    FocusState.DONE -> "DONE"
    else -> ""
}
