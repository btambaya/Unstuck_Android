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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.Receipt
import tech.csalliance.unstuck.core.logic.assistantDayLabel
import tech.csalliance.unstuck.core.logic.shouldCheckIn
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.ui.AppViewModel

/**
 * The assistant "cockpit" (redesign 2026-08-02, port of
 * components/assistant/assistant-bubble.tsx): a header with the ask-Unstuck
 * eyebrow, a live tappable context strip (NEXT / USABLE / PAUSED), ONE endless
 * thread with day dividers + deterministic action receipts, and a dynamic,
 * data-driven suggestion card that the sheet opens onto.
 *
 * There is no "new chat" — the conversation continues forever (⋯ still offers a
 * deliberate "Clear conversation"). Feedback moved to Settings → Send feedback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(vm: AppViewModel, onNavigate: (AssistantDestination) -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        AssistantChat(vm, onNavigate = { onNavigate(it); onDismiss() })
    }
}

/** One rendered row of the endless thread. */
private sealed interface ThreadRow {
    data class Divider(val label: String) : ThreadRow
    data class Bubble(val msg: ChatMessage) : ThreadRow
    data class ReceiptItem(val messageId: String, val index: Int, val receipt: Receipt) : ThreadRow
}

@Composable
private fun AssistantChat(vm: AppViewModel, onNavigate: (AssistantDestination) -> Unit) {
    val c = UTheme.colors
    val context = LocalContext.current
    val voice = rememberVoiceController()

    // History + the in-flight turn live on the ViewModel (the turn runs on
    // viewModelScope, so dismissing the sheet mid-"Thinking…" no longer cancels
    // a multi-step turn half-applied); display derives from them.
    val messages = vm.assistantHistory
    val sending by vm.assistantSending.collectAsStateWithLifecycle()
    val errorCode by vm.assistantError.collectAsStateWithLifecycle()
    val pendingShares by vm.pendingShares.collectAsStateWithLifecycle()

    // Coarse clock for the day dividers + the context strip: a sheet left open
    // across midnight must roll "Today" over rather than freeze.
    var nowMs by remember { mutableLongStateOf(vm.nowMs()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); nowMs = vm.nowMs() } }
    val ctx = rememberAssistantContext(vm, nowMs)

    var input by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var speakReplies by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var sharingId by remember { mutableStateOf<String?>(null) }
    // The suggestion card sits at the thread tail until the user engages this
    // visit; the ✦ button re-summons it.
    var showChips by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val display = messages.filter {
        (it.role == "user" || it.role == "assistant") && !it.content.isNullOrBlank()
    }
    val hasHistory = display.isNotEmpty()

    // Flatten to rows so day dividers and receipts are first-class list items
    // (a divider must not scroll as part of the bubble above it).
    val rows = remember(display, nowMs) {
        buildList {
            var lastLabel: String? = null
            display.forEach { m ->
                val label = assistantDayLabel(m.at, nowMs)
                if (label != null && label != lastLabel) { add(ThreadRow.Divider(label)); lastLabel = label }
                add(ThreadRow.Bubble(m))
                m.receipts?.forEachIndexed { i, r -> add(ThreadRow.ReceiptItem(m.id.orEmpty(), i, r)) }
            }
        }
    }

    // One-tap revert of the LAST turn's changes (only while still undoable —
    // receipts flip to `undone` as they're used).
    val lastUndoable = display.lastOrNull { m -> m.receipts?.any { it.undo != null && !it.undone } == true }
    val undoAllCount = lastUndoable?.receipts?.count { it.undo != null && !it.undone } ?: 0

    // Daily check-in: once per day, and ONLY when the sheet opens onto an
    // existing conversation (a fresh account gets the full hero instead). A send
    // mid-visit must never summon it retroactively, so this runs once, on open.
    LaunchedEffect(Unit) {
        if (!vm.assistantHistory.any { it.role == "user" }) return@LaunchedEffect
        val today = Clock.dateIso(vm.nowMs())
        if (shouldCheckIn(vm.lastCheckinDay(), today)) {
            vm.markCheckinDay(today)
            vm.appendLocalAssistant(ctx.checkinLine)
        }
    }

    // Ahmad (2026-08-02): opening the assistant should show ONLY the
    // suggestions — history starts off-screen above. The chips item is at least
    // a full viewport tall, so aligning its top with the viewport top hides
    // everything before it; scrolling up reveals the history.
    val thinkingCount = if (sending) 1 else 0
    val chipsIndex = rows.size + thinkingCount + pendingShares.size
    LaunchedEffect(rows.size, sending, showChips, pendingShares) {
        val unresolved = pendingShares.indexOfLast { it.outcome == null }
        when {
            // A staged share is the thing that needs an answer — go to it.
            unresolved >= 0 -> listState.animateScrollToItem(rows.size + thinkingCount + unresolved)
            showChips -> listState.scrollToItem(chipsIndex)
            chipsIndex > 0 -> listState.animateScrollToItem(chipsIndex - 1)
        }
    }

    // Speak replies as turns complete. Replies can land after a dismissal (the
    // turn outlives the sheet) — only an open sheet collects + speaks.
    LaunchedEffect(Unit) { vm.assistantReplies.collect { if (speakReplies) voice.speak(it) } }

    val keyboard = LocalSoftwareKeyboardController.current

    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sending) return
        input = ""
        note = null
        showChips = false
        // Drop the keyboard on send. It used to stay up for the whole
        // exchange, so the reply you just asked for landed behind it (found on
        // iOS while capturing marketing shots; Android had the same gap).
        // Tap the field again to keep typing.
        keyboard?.hide()
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
                if (input.isNotBlank()) ask(input)
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

    // Saveable so a configuration change (rotation / fold / theme / locale)
    // brings the voice screen back onto its still-live session (VoiceSessionHolder).
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    if (voiceOpen) VoiceModeScreen(vm) { voiceOpen = false }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).imePadding()) {
        // ── Header: title + eyebrow, Talk, ⋯ ─────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Assistant", style = UFont.sans(15, FontWeight.SemiBold), color = c.ink)
                SectionLabel("Ask Unstuck to handle it", modifier = Modifier.padding(top = 1.dp))
            }
            if (vm.voiceConfigured()) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.coral)
                        .clickable(role = Role.Button, onClickLabel = "Talk to the assistant") { voiceOpen = true }
                        .minimumInteractiveComponentSize()
                        // One spoken label for the icon+text pill.
                        .semantics(mergeDescendants = true) { contentDescription = "Talk" }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Voice glyph on the Talk pill — sparkles mark the AI LAUNCHER;
                    // Talk keeps a mic so the voice affordance reads (matches the
                    // iOS Talk pill's waveform and web's Talk button).
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("Talk", style = UFont.sans(12, FontWeight.SemiBold), color = Color.White)
                }
            }
            if (hasHistory) {
                Box {
                    RoundIcon(
                        icon = Icons.Filled.MoreHoriz, tint = c.ink3, bg = Color.Transparent,
                        label = "Conversation options",
                    ) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Clear conversation", style = UFont.sans(13), color = c.coralDeep) },
                            onClick = { menuOpen = false; showChips = true; vm.clearAssistant() },
                        )
                    }
                }
            }
        }

        // ── Live context strip — pinned, in BOTH modes ───────────────────────
        ContextStrip(ctx, onNavigate)

        if (!hasHistory && !sending) {
            // Brand-new conversation — the full "first page".
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item {
                    AssistantHome(
                        ctx, onAsk = ::ask,
                        undoAllCount = undoAllCount,
                        onUndoAll = { undoAll(vm, lastUndoable) },
                    )
                }
            }
        } else {
            // The ONE endless thread: history above (day dividers + receipts),
            // the suggestion card as the newest thing at the tail.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewportHeight = maxHeight
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        count = rows.size,
                        key = { i ->
                            when (val r = rows[i]) {
                                is ThreadRow.Divider -> "div:$i:${r.label}"
                                is ThreadRow.Bubble -> "msg:${r.msg.id ?: i}"
                                is ThreadRow.ReceiptItem -> "rcpt:${r.messageId}:${r.index}"
                            }
                        },
                    ) { i ->
                        when (val r = rows[i]) {
                            is ThreadRow.Divider -> DayDivider(r.label)
                            is ThreadRow.Bubble -> MessageBubble(r.msg)
                            is ThreadRow.ReceiptItem -> ReceiptCard(r) { vm.undoAssistantReceipt(r.messageId, r.index) }
                        }
                    }
                    if (sending) item(key = "thinking") { ThinkingRow() }
                    items(count = pendingShares.size, key = { i -> "share:${pendingShares[i].id}" }) { i ->
                        val p = pendingShares[i]
                        ShareConfirmCard(
                            pending = p,
                            busy = sharingId == p.id && p.outcome == null,
                            onConfirm = { sharingId = p.id; vm.confirmPendingShare(p.id) },
                            onDismiss = { vm.dismissPendingShare(p.id) },
                        )
                    }
                    if (showChips && !sending) {
                        item(key = "chips") {
                            AssistantHome(
                                ctx, onAsk = ::ask, compact = true,
                                undoAllCount = undoAllCount,
                                onUndoAll = { undoAll(vm, lastUndoable) },
                                // At least a full viewport tall, so opening the
                                // sheet parks this card at the top with the whole
                                // conversation above the fold.
                                modifier = Modifier.heightIn(min = viewportHeight),
                            )
                        }
                    }
                }
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

        // ── Input bar: ✦ re-summon + text + speaker + mic + send ─────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasHistory && !showChips) {
                RoundIcon(
                    icon = Icons.Filled.AutoAwesome, tint = c.ink2, bg = c.bg2,
                    label = "Show suggestions",
                ) { showChips = true }
            }
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
                    keyboardActions = KeyboardActions(onSend = { ask(input) }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                if (listening) "Listening…" else "Ask Unstuck to handle something…",
                                style = UFont.sans(15), color = c.ink3,
                            )
                        }
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
            ) { ask(input) }
        }
    }
}

/** Revert every still-undoable receipt on the last turn that made changes.
 *  Walks BACKWARDS so earlier indices stay valid as receipts flip to `undone`. */
private fun undoAll(vm: AppViewModel, msg: ChatMessage?) {
    val id = msg?.id ?: return
    val receipts = msg.receipts ?: return
    for (i in receipts.indices.reversed()) {
        val r = receipts[i]
        if (r.undo != null && !r.undone) vm.undoAssistantReceipt(id, i)
    }
}

@Composable
private fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        SectionLabel(label)
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val c = UTheme.colors
    val fromUser = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (fromUser) c.coral else c.surface)
                .then(if (fromUser) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(16.dp)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // A locally-injected turn (the daily check-in) is marked ✦ so it
            // never reads as something the model said.
            Text(
                (if (m.local) "✦ " else "") + m.content.orEmpty(),
                style = UFont.sans(15), color = if (fromUser) Color.White else c.ink,
            )
        }
    }
}

/** A deterministic ✓ card for one thing the agent actually did. */
@Composable
private fun ReceiptCard(row: ThreadRow.ReceiptItem, onUndo: () -> Unit) {
    val c = UTheme.colors
    val r = row.receipt
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = c.green, modifier = Modifier.size(14.dp))
        Text(
            r.label, style = UFont.sans(12), color = c.ink2, modifier = Modifier.weight(1f),
            textDecoration = if (r.undone) TextDecoration.LineThrough else null,
        )
        when {
            r.undone -> Text("undone", style = UFont.sans(11), color = c.ink3)
            r.undo != null -> Text(
                "Undo", style = UFont.sans(12, FontWeight.SemiBold), color = c.coralDeep,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button, onClick = onUndo)
                    .minimumInteractiveComponentSize().padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ThinkingRow() {
    val c = UTheme.colors
    // Polite live region so TalkBack announces that a reply is in progress.
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text("Thinking…", style = UFont.sans(14), color = c.ink3) }
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    tint: Color, bg: Color,
    label: String, stateDesc: String? = null, role: Role = Role.Button,
    onClick: () -> Unit,
) {
    Box(
        // 40dp visual circle, but minimumInteractiveComponentSize grows the hit
        // target to the 48dp minimum (drawn size unchanged).
        Modifier.size(40.dp).clip(RoundedCornerShape(999.dp)).background(bg).clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .semantics {
                this.role = role
                contentDescription = label
                stateDesc?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
}

internal fun friendlyError(code: String): String = when (code) {
    "not_configured" -> "The assistant isn't set up yet."
    "network" -> "Couldn't reach the assistant — check your connection."
    "timeout" -> "That took too long — try again."
    "upstream" -> "The assistant had a hiccup. Try again."
    "unauthorized" -> "Please sign in to use the assistant."
    "rate_limited" -> "You've asked a lot just now — give it a minute."
    "payload_too_large" -> "That was too much to send at once — try a shorter message."
    else -> "Something went wrong. Try again."
}
