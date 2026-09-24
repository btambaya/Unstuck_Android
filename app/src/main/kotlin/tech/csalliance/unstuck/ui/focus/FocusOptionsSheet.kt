package tech.csalliance.unstuck.ui.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.settings.SegBlock
import tech.csalliance.unstuck.ui.settings.SettingsCard
import tech.csalliance.unstuck.ui.settings.ToggleRow

// Focus "⋯ Options" — the focus controls that used to be Settings → Focus
// (slim settings, 2026-09-24), on the screen they change. Same keys as before,
// so the assistant's set_focus_defaults still reaches them.

internal object FocusOptionsCopy {
    const val BUTTON_A11Y = "Focus options"
    const val TITLE = "Focus options"
    const val DONE = "Done"
    const val OVERRUN = "Check in when I run over"
    val OVERRUN_OPTIONS = listOf("Never" to 0, "5 min" to 5, "10 min" to 10)
    const val OVERRUN_SUB = "How long after the timer runs out before Unstuck asks how it's going."
    const val SOFT_EXIT = "Ask before I leave a session"
    const val SOFT_EXIT_SUB = "Leaving keeps the timer running either way."
    const val PAUSE_REASONS = "Ask why I'm pausing"
    const val PAUSE_REASONS_SUB = "One tap, and it helps you pick back up."
    const val COACH = "Talk me through the session"
    const val COACH_SUB = "Short spoken check-ins: halfway, five to go, time's up. How often follows your check-in level in Settings."
    const val VOICE = "Voice replies"
    const val VOICE_SUB = "After a spoken question it listens for a few seconds (“add five”, “stop”, “keep going”). Nothing is recorded or sent."

    /** The leave / pause questions' opt-out. */
    const val DONT_ASK = "Don't ask again"

    fun overrunLabel(min: Int): String = OVERRUN_OPTIONS.firstOrNull { it.second == min }?.first ?: "$min min"
    fun overrunMinutes(label: String): Int = OVERRUN_OPTIONS.firstOrNull { it.first == label }?.second ?: 5

    /** The speaker button's spoken label. */
    fun speakerA11y(on: Boolean) = if (on) "Background noise on. Turn it off" else "Background noise off. Turn it on"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusOptionsSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp)
                .testTag("focus-options-sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(FocusOptionsCopy.TITLE, style = UFont.serifItalic(22), color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                Text(
                    FocusOptionsCopy.DONE, style = UFont.sans(14, FontWeight.Medium), color = c.ink2,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button, onClick = onDismiss)
                        .minimumInteractiveComponentSize().padding(horizontal = 8.dp),
                )
            }
            SettingsCard {
                SegBlock(
                    FocusOptionsCopy.OVERRUN, FocusOptionsCopy.OVERRUN_OPTIONS.map { it.first }, FocusOptionsCopy.overrunLabel(s.focusOverrunMin),
                    sub = FocusOptionsCopy.OVERRUN_SUB,
                ) { v -> vm.updateSettings { it.copy(focusOverrunMin = FocusOptionsCopy.overrunMinutes(v)) } }
                ToggleRow(FocusOptionsCopy.SOFT_EXIT, s.focusSoftExit, sub = FocusOptionsCopy.SOFT_EXIT_SUB, modifier = Modifier.testTag("focus-options-soft-exit")) { v ->
                    vm.updateSettings { it.copy(focusSoftExit = v) }
                }
                ToggleRow(FocusOptionsCopy.PAUSE_REASONS, s.focusPauseReasons, sub = FocusOptionsCopy.PAUSE_REASONS_SUB, last = true, modifier = Modifier.testTag("focus-options-pause-reasons")) { v ->
                    vm.updateSettings { it.copy(focusPauseReasons = v) }
                }
            }
            // Hands-Free Focus Copilot (Phase 1, on-device, no LLM/network).
            SettingsCard {
                ToggleRow(FocusOptionsCopy.COACH, s.focusCopilotSpeak, sub = FocusOptionsCopy.COACH_SUB, last = !s.focusCopilotSpeak, modifier = Modifier.testTag("focus-options-coach")) { v ->
                    vm.updateSettings { it.copy(focusCopilotSpeak = v) }
                }
                // Only meaningful while the spoken coach is on (it adds the mic).
                if (s.focusCopilotSpeak) {
                    ToggleRow(FocusOptionsCopy.VOICE, s.focusCopilotVoice, sub = FocusOptionsCopy.VOICE_SUB, last = true, modifier = Modifier.testTag("focus-options-voice")) { v ->
                        vm.updateSettings { it.copy(focusCopilotVoice = v) }
                    }
                }
            }
        }
    }
}
