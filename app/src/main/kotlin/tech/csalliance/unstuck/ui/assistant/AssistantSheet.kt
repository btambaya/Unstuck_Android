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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
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
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (selected) c.ink else c.bg2)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = if (selected) c.bg else c.ink2) }
}

@Composable
private fun AssistantChat(vm: AppViewModel) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val voice = rememberVoiceController()

    // Full OpenAI-shape history (mutated by assistantTurn); display derives from it.
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val shown = messages.filter { (it.role == "user" || it.role == "assistant") && !it.content.isNullOrBlank() }
    LaunchedEffect(shown.size, sending) {
        if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size) // last + the thinking row
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sending) return
        input = ""
        note = null
        messages.add(ChatMessage(role = "user", content = t))
        sending = true
        scope.launch {
            when (val turn = vm.assistantTurn(messages)) {
                is AppViewModel.AssistantTurn.Reply -> if (speakReplies) voice.speak(turn.text)
                is AppViewModel.AssistantTurn.Error -> note = friendlyError(turn.code)
            }
            sending = false
        }
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

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).imePadding()) {
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
                items(shown.size) { i ->
                    val m = shown[i]
                    MessageBubble(text = m.content!!, fromUser = m.role == "user")
                }
                if (sending) item { ThinkingRow() }
            }
        }

        note?.let {
            Text(it, style = UFont.sans(12), color = c.coralDeep, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
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
                    value = input, onValueChange = { input = it },
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
            ) { speakReplies = !speakReplies; if (!speakReplies) voice.stopSpeaking() }
            RoundIcon(
                icon = Icons.Filled.Mic,
                tint = if (listening) Color.White else c.ink2,
                bg = if (listening) c.coral else c.bg2,
                onClick = ::onMic,
            )
            RoundIcon(
                icon = Icons.AutoMirrored.Filled.Send,
                tint = if (input.isBlank() || sending) c.ink4 else Color.White,
                bg = if (input.isBlank() || sending) c.bg2 else c.coral,
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text("Thinking…", style = UFont.sans(14), color = c.ink3) }
    }
}

@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color, bg: Color, onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
}

private fun friendlyError(code: String): String = when (code) {
    "not_configured" -> "The assistant isn't set up yet."
    "network" -> "Couldn't reach the assistant — check your connection."
    "upstream" -> "The assistant had a hiccup. Try again."
    "unauthorized" -> "Please sign in to use the assistant."
    else -> "Something went wrong. Try again."
}
