package tech.csalliance.unstuck.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.feedback.FeedbackForm

/**
 * The bubble's dual-purpose surface: an **Assistant** chat (agentic — brain-dump
 * to manage your schedule, with on-device voice) + the existing **Feedback** form,
 * switched by a top toggle. The chat drives AppViewModel.assistantTurn (which calls
 * the qwen edge fn + executes tool calls locally). `currentScreen` is the tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(vm: AppViewModel, currentScreen: String?, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(Tab.ASSISTANT) }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        // Top toggle: Assistant | Feedback.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleChip("Assistant", tab == Tab.ASSISTANT) { tab = Tab.ASSISTANT }
            ToggleChip("Feedback", tab == Tab.FEEDBACK) { tab = Tab.FEEDBACK }
        }
        when (tab) {
            Tab.ASSISTANT -> AssistantChat(vm)
            Tab.FEEDBACK -> FeedbackForm(vm, currentScreen, onDone = onDismiss)
        }
    }
}

private enum class Tab { ASSISTANT, FEEDBACK }

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = UTheme.colors
    Box(
        // selectable (not clickable) so TalkBack announces "selected, tab" state.
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (selected) c.ink else c.bg2)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = if (selected) c.bg else c.ink2) }
}

@Composable
private fun AssistantChat(vm: AppViewModel) {
    val c = UTheme.colors
    val context = LocalContext.current
    val voice = rememberVoiceController()

    // History + the in-flight turn live on the ViewModel (the turn runs on
    // viewModelScope, so dismissing the sheet mid-"Thinking…" no longer cancels
    // a multi-step turn half-applied); display derives from them.
    val messages = vm.assistantHistory
    val sending by vm.assistantSending.collectAsStateWithLifecycle()
    val errorCode by vm.assistantError.collectAsStateWithLifecycle()
    // Saveable so a half-typed brain-dump + the speaker toggle survive rotation.
    // (`listening` is transient — a config change tears down the recognizer anyway.)
    var input by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var speakReplies by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Carry each message's index in the append-only history so the LazyColumn can key
    // on a STABLE id (messages are appended, never reordered) — without that, a
    // streaming turn reuses the wrong row's animation/state as content fills in.
    val shown = messages.withIndex()
        .filter { (it.value.role == "user" || it.value.role == "assistant") && !it.value.content.isNullOrBlank() }
    LaunchedEffect(shown.size, sending) {
        // Scroll to the genuine last index; only reach one-past-end while the
        // "Thinking…" row is showing, so a tall final reply isn't clipped.
        if (shown.isNotEmpty()) listState.animateScrollToItem(if (sending) shown.size else shown.lastIndex)
    }
    // Speak replies as turns complete. Replies can land after a dismissal (the
    // turn outlives the sheet) — only an open sheet collects + speaks.
    LaunchedEffect(Unit) {
        vm.assistantReplies.collect { if (speakReplies) voice.speak(it) }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sending) return
        input = ""
        note = null
        vm.sendAssistant(t)
    }

    fun startMic() {
        listening = true
        voice.stopSpeaking()
        voice.startListening(
            onPartial = { input = it },
            onFinal = { input = it },
            onDone = {
                listening = false
                if (input.isNotBlank()) send(input)
            },
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMic() else note = "Mic permission is needed to talk to the assistant."
    }
    fun onMic() {
        if (listening) { voice.stopListening(); listening = false; return }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startMic() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    var voiceOpen by remember { mutableStateOf(false) }
    if (voiceOpen) VoiceModeScreen(vm) { voiceOpen = false }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).imePadding()) {
        // Header: a "Talk" entry into full voice mode (when configured) + "New chat".
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.voiceConfigured()) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.coral).clickable { voiceOpen = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("Talk", style = UFont.sans(12, FontWeight.SemiBold), color = Color.White)
                }
            } else { Box(Modifier) }
            if (shown.isNotEmpty()) {
                Text(
                    "New chat", style = UFont.sans(12, FontWeight.Medium), color = c.ink3,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { vm.clearAssistant() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        // Messages (or empty hint).
        if (shown.isEmpty() && !sending) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.Center) {
                Text("Brain-dump it.", style = UFont.serifItalic(24), color = c.ink)
                Text(
                    "Tell me what's on your plate and I'll sort it — \"add a dentist appt next Tue 3pm\", " +
                        "\"move my report to tomorrow morning\", \"what should I start?\". Type or tap the mic.",
                    style = UFont.sans(13), color = c.ink3, modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.index }) { (_, m) ->
                    MessageBubble(text = m.content!!, fromUser = m.role == "user")
                }
                if (sending) item { ThinkingRow() }
            }
        }

        // Local notes (mic permission) or the last turn's error off the VM (which
        // survives close/reopen). Polite live region so TalkBack announces failures.
        (note ?: errorCode?.let(::friendlyError))?.let {
            Text(
                it, style = UFont.sans(12), color = c.coralDeep,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // Input bar: text + speaker toggle + mic + send.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(c.bg2).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    // Clear the sticky error/note banner the moment the user starts a
                    // retry, so it doesn't linger through typing (it also clears on send).
                    value = input,
                    onValueChange = { input = it; if (it.isNotEmpty()) { note = null; vm.clearAssistantError() } },
                    textStyle = UFont.sans(15).copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text(if (listening) "Listening…" else "Message…", style = UFont.sans(15), color = c.ink3)
                        inner()
                    },
                )
            }
            RoundIcon(
                icon = if (speakReplies) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                tint = if (speakReplies) c.coral else c.ink3, bg = c.bg2,
                label = "Speak replies", stateDesc = if (speakReplies) "On" else "Off",
                role = Role.Switch,
            ) { speakReplies = !speakReplies; if (!speakReplies) voice.stopSpeaking() }
            RoundIcon(
                icon = Icons.Filled.Mic,
                tint = if (listening) Color.White else c.ink2,
                bg = if (listening) c.coral else c.bg2,
                label = if (listening) "Stop listening" else "Dictate message",
                onClick = ::onMic,
            )
            RoundIcon(
                icon = Icons.AutoMirrored.Filled.Send,
                tint = if (input.isBlank() || sending) c.ink4 else Color.White,
                bg = if (input.isBlank() || sending) c.bg2 else c.coral,
                label = "Send",
            ) { send(input) }
        }
    }
}

@Composable
private fun MessageBubble(text: String, fromUser: Boolean) {
    val c = UTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (fromUser) c.coral else c.surface)
                .then(if (fromUser) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(16.dp)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, style = UFont.sans(15), color = if (fromUser) Color.White else c.ink)
        }
    }
}

@Composable
private fun ThinkingRow() {
    val c = UTheme.colors
    // Polite live region so TalkBack announces that a reply is in progress.
    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text("Thinking…", style = UFont.sans(14), color = c.ink3) }
    }
}

@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color, bg: Color,
    label: String, stateDesc: String? = null, role: Role = Role.Button,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(bg).clickable(onClick = onClick)
            .semantics {
                this.role = role
                contentDescription = label
                stateDesc?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
}

private fun friendlyError(code: String): String = when (code) {
    "not_configured" -> "The assistant isn't set up yet."
    "network" -> "Couldn't reach the assistant — check your connection."
    "timeout" -> "That took too long — try again."
    "upstream" -> "The assistant had a hiccup. Try again."
    "unauthorized" -> "Please sign in to use the assistant."
    else -> "Something went wrong. Try again."
}
