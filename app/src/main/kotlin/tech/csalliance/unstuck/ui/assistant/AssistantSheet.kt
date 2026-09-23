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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import tech.csalliance.unstuck.core.logic.AssistantHarnessRules
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
/** @param handoff opened by a hand-off (a chat moment) with the message
 *  ALREADY on its way through [AppViewModel.sendAssistant]'s queue: the sheet
 *  opens onto the thread (the sent bubble + "Thinking…"), not the suggestion
 *  card parked over it.
 *  @param focusComposer opened from Today's input pill: put the keyboard in
 *  THIS composer once the sheet has settled (nothing is typed on Today). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(vm: AppViewModel, onNavigate: (AssistantDestination) -> Unit, onDismiss: () -> Unit, handoff: Boolean = false, focusComposer: Boolean = false) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        AssistantChat(vm, onNavigate = { onNavigate(it); onDismiss() }, handoff = handoff, focusComposer = focusComposer)
    }
}

/** One rendered row of the endless thread. */
private sealed interface ThreadRow {
    data class Divider(val label: String) : ThreadRow
    data class Bubble(val msg: ChatMessage) : ThreadRow
    data class ReceiptItem(val messageId: String, val index: Int, val receipt: Receipt) : ThreadRow
    /** The interview's chip row, under the question it is asking. */
    data object InterviewPrompt : ThreadRow
}

@Composable
private fun AssistantChat(vm: AppViewModel, onNavigate: (AssistantDestination) -> Unit, handoff: Boolean = false, focusComposer: Boolean = false) {
    val c = UTheme.colors
    val context = LocalContext.current
    val voice = rememberVoiceController()

    // History + the in-flight turn live on the ViewModel (the turn runs on
    // viewModelScope, so dismissing the sheet mid-"Thinking…" no longer cancels
    // a multi-step turn half-applied); display derives from them.
    val messages = vm.assistantHistory
    val sending by vm.assistantSending.collectAsStateWithLifecycle()
    val errorCode by vm.assistantError.collectAsStateWithLifecycle()
    // Two upstream rejections in a row → the error row offers "Start a fresh thread".
    val offersFreshThread by vm.assistantOffersFreshThread.collectAsStateWithLifecycle()
    val pendingShares by vm.pendingShares.collectAsStateWithLifecycle()
    // Messages typed while a turn was in flight: queued, shown faded, sent when
    // the reply lands (contract §8) — never silently dropped.
    val queued by vm.assistantQueued.collectAsStateWithLifecycle()
    // Receipts whose undo is a NETWORK round trip (cancel_call): the control reads
    // "cancelling…" while it's in flight instead of inviting a second tap.
    val undosInFlight by vm.receiptUndosInFlight.collectAsStateWithLifecycle()
    // Undos that were refused (a row changed since its turn): the card says why.
    val undoNotes by vm.receiptUndoNotes.collectAsStateWithLifecycle()

    // Coarse clock for the day dividers + the context strip: a sheet left open
    // across midnight must roll "Today" over rather than freeze.
    var nowMs by remember { mutableLongStateOf(vm.nowMs()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); nowMs = vm.nowMs() } }
    val ctx = rememberAssistantContext(vm, nowMs)

    // The get-to-know-you interview rides in THIS thread until the account is
    // done with it (InterviewThreadDriver): it arms on the first send of a
    // visit (the reply comes first), then asks one question per local turn
    // with its chips under it. Same machine / facts store / done flag as
    // before — only the host changed (the Today card is gone, 2026-09-17).
    val interviewDone by vm.interviewDone.collectAsStateWithLifecycle()
    val rituals by vm.rituals.collectAsStateWithLifecycle()
    val interview = remember {
        InterviewThreadDriver(
            controller = InterviewFlowController(vm),
            host = vm,
            firstName = vm.currentName?.trim()?.split(' ', '\t', '\n')?.firstOrNull { it.isNotBlank() },
            ready = { vm.profileFactsHydrated.value },
            factCount = { vm.profileFacts.value.size },
            post = { text -> vm.appendLocalAssistant(text) ?: "" },
            echo = { text -> vm.appendLocalUser(text) },
        )
    }
    val interviewState by interview.state.collectAsStateWithLifecycle()
    // The ≥1-fact stand-down (MainScaffold) must not fire while a question is up.
    LaunchedEffect(interviewState.phase) { vm.setInterviewThreadAsking(interviewState.phase == InterviewThreadPhase.ASKING) }
    DisposableEffect(Unit) { onDispose { vm.setInterviewThreadAsking(false) } }
    // Done from ELSEWHERE (another device, a server pin) while asking: stand down.
    LaunchedEffect(interviewDone) { if (interviewDone) interview.hostDone() }
    // The interview asks its question only once the reply (or error) has landed.
    LaunchedEffect(sending) { if (!sending) interview.turnFinished() }

    var input by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var speakReplies by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var sharingId by remember { mutableStateOf<String?>(null) }
    // The suggestion card sits at the thread tail until the user engages this
    // visit; the ✦ button re-summons it. A gateway hand-off IS the engagement —
    // the user just sent something from Today, so the thread shows at once.
    var showChips by rememberSaveable { mutableStateOf(!handoff) }
    val listState = rememberLazyListState()

    // Tool steps, the harness's hidden guard bounce (the fabricated claim + the
    // corrective it answers), the cut-off hint and the model's per-round
    // narration on tool_calls rounds never render (see visibleAssistantTurns).
    val display = visibleAssistantTurns(messages)
    val hasHistory = display.isNotEmpty() || queued.isNotEmpty()

    // Flatten to rows so day dividers and receipts are first-class list items
    // (a divider must not scroll as part of the bubble above it).
    val promptTurnId = interviewState.promptTurnId
    val rows = remember(display, nowMs, promptTurnId) {
        buildList {
            var lastLabel: String? = null
            display.forEach { m ->
                val label = assistantDayLabel(m.at, nowMs)
                if (label != null && label != lastLabel) { add(ThreadRow.Divider(label)); lastLabel = label }
                add(ThreadRow.Bubble(m))
                // The interview's chips, under the question it is asking (older
                // prompts — a relaunch, a re-ask — render as plain text).
                if (promptTurnId != null && m.id == promptTurnId) add(ThreadRow.InterviewPrompt)
                m.receipts?.forEachIndexed { i, r -> add(ThreadRow.ReceiptItem(m.id.orEmpty(), i, r)) }
            }
        }
    }

    // Revert of the turn that JUST finished, named and confirmed first. It used
    // to reach back to the last turn with any unused Undo, however old — one tap
    // on Thursday deleted Monday's tasks (Android audit 2026-09-23, A17).
    val undoAllTurn = undoAllTarget(display, nowMs, undoNotes.keys)
    val undoAllItems = undoAllTurn?.let { undoAllReceipts(it, undoNotes.keys) }.orEmpty()
    val undoAllCount = undoAllItems.size
    var confirmUndoAll by remember { mutableStateOf(false) }

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
    val chipsIndex = rows.size + thinkingCount + queued.size + pendingShares.size
    LaunchedEffect(rows.size, sending, showChips, pendingShares, queued.size) {
        val unresolved = pendingShares.indexOfLast { it.outcome == null }
        when {
            // A staged share is the thing that needs an answer — go to it.
            unresolved >= 0 -> listState.animateScrollToItem(rows.size + thinkingCount + queued.size + unresolved)
            showChips -> listState.scrollToItem(chipsIndex)
            chipsIndex > 0 -> listState.animateScrollToItem(chipsIndex - 1)
        }
    }

    // Speak replies as turns complete. Replies can land after a dismissal (the
    // turn outlives the sheet) — only an open sheet collects + speaks.
    LaunchedEffect(Unit) { vm.assistantReplies.collect { if (speakReplies) voice.speak(it) } }

    val keyboard = LocalSoftwareKeyboardController.current
    // Today's input pill asked for the keyboard: honour it once the sheet has
    // settled (the field only exists after the first frame).
    val composerFocus = remember { FocusRequester() }
    LaunchedEffect(focusComposer) {
        if (!focusComposer) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        runCatching { composerFocus.requestFocus() }
        keyboard?.show()
    }

    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        input = ""
        note = null
        showChips = false
        // Drop the keyboard on send. It used to stay up for the whole
        // exchange, so the reply you just asked for landed behind it (found on
        // iOS while capturing marketing shots; Android had the same gap).
        // Tap the field again to keep typing.
        keyboard?.hide()
        vm.sendAssistant(t)
        interview.userSent()
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

    // Names every change it will revert before anything is deleted (A17).
    val undoAllId = undoAllTurn?.id
    if (confirmUndoAll && undoAllId != null && undoAllItems.isNotEmpty()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmUndoAll = false },
        title = {
            Text(
                if (undoAllCount == 1) "Undo this change?" else "Undo these $undoAllCount changes?",
                style = UFont.sans(16, FontWeight.SemiBold), color = c.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                undoAllItems.forEach { Text("• ${it.label}", style = UFont.sans(13), color = c.ink2) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { confirmUndoAll = false; vm.undoAllAssistantReceipts(undoAllId) }) {
                Text("Undo", color = c.red)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { confirmUndoAll = false }) { Text("Keep", color = c.ink2) }
        },
        containerColor = c.surface,
    )

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
                            text = { Text("Clear conversation", style = UFont.sans(13), color = c.red) },
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
                        onUndoAll = { confirmUndoAll = true },
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
                                ThreadRow.InterviewPrompt -> "interview:$promptTurnId"
                            }
                        },
                    ) { i ->
                        when (val r = rows[i]) {
                            is ThreadRow.Divider -> DayDivider(r.label)
                            is ThreadRow.Bubble -> MessageBubble(r.msg)
                            is ThreadRow.ReceiptItem -> ReceiptCard(
                                r,
                                inFlight = receiptUndoKey(r.messageId, r.index) in undosInFlight,
                                note = undoNotes[receiptUndoKey(r.messageId, r.index)],
                            ) { vm.undoAssistantReceipt(r.messageId, r.index) }
                            ThreadRow.InterviewPrompt -> InterviewPromptRow(
                                interview,
                                ritualIsOn = { rituals[it] },
                                setRitual = { key, on -> vm.setRitual(key, on) },
                            )
                        }
                    }
                    if (sending) item(key = "thinking") { ThinkingRow() }
                    items(count = queued.size, key = { i -> "queued:${queued[i].id}" }) { i -> PendingBubble(queued[i].text) }
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
                                onUndoAll = { confirmUndoAll = true },
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    it, style = UFont.sans(12), color = c.red,
                    modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                )
                // Two upstream rejections in a row: the thread itself is the
                // likely cause (a poisoned replayed tool_call, 2026-09-06) —
                // offer the way out right where it hurts.
                if (note == null && errorCode != null && offersFreshThread) {
                    Text(
                        "Start a fresh thread", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink,
                        modifier = Modifier.clickable(role = Role.Button) { showChips = true; vm.clearAssistant() },
                    )
                }
            }
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
                    modifier = Modifier.fillMaxWidth().focusRequester(composerFocus),
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
            // Sending stays live mid-turn: the message queues (a faded bubble) instead of vanishing.
            RoundIcon(
                icon = Icons.AutoMirrored.Filled.Send,
                tint = if (input.isBlank()) c.ink4 else Color.White,
                bg = if (input.isBlank()) c.bg2 else c.coral,
                label = if (sending) "Queue message" else "Send",
            ) { ask(input) }
        }
    }
}

/** How long "Undo all" stays on offer after its turn lands. */
internal const val UNDO_ALL_WINDOW_MS = 15 * 60_000L

/** The turn "Undo all" reverts: the one that JUST finished — the newest turn
 *  with receipts, with no request of the user's after it, landed within
 *  [UNDO_ALL_WINDOW_MS] — while it still has an Undo that wasn't refused
 *  ([refused]: receiptUndoKey). Never an older turn: the chip reached back
 *  days to any unused Undo (Android audit 2026-09-23, A17). */
internal fun undoAllTarget(display: List<ChatMessage>, nowMs: Long, refused: Set<String>): ChatMessage? {
    val i = display.indexOfLast { !it.receipts.isNullOrEmpty() }
    if (i < 0) return null
    val turn = display[i]
    if (display.drop(i + 1).any { it.role == "user" }) return null
    val at = turn.at ?: return null
    // Only an upper bound: the sheet's clock ticks once a minute, so a turn
    // that just landed can read as slightly in the future.
    if (nowMs - at > UNDO_ALL_WINDOW_MS) return null
    return turn.takeIf { undoAllReceipts(it, refused).isNotEmpty() }
}

/** The receipts "Undo all" on [turn] would revert (what its confirmation names). */
internal fun undoAllReceipts(turn: ChatMessage, refused: Set<String>): List<Receipt> =
    turn.receipts.orEmpty().filterIndexed { i, r -> r.isUndoable && receiptUndoKey(turn.id.orEmpty(), i) !in refused }

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
            // A locally-injected assistant turn (the daily check-in, an interview
            // question) is marked ✦ so it never reads as something the model
            // said; a local USER bubble (the interview echoing a tapped chip)
            // reads as the user's own words.
            Text(
                (if (m.local && !fromUser) "✦ " else "") + m.content.orEmpty(),
                style = UFont.sans(15), color = if (fromUser) Color.White else c.ink,
            )
        }
    }
}

/** A deterministic ✓ card for one thing the agent actually did. [note] says
 *  why its Undo was refused, in place of the control (A17). */
@Composable
private fun ReceiptCard(row: ThreadRow.ReceiptItem, inFlight: Boolean = false, note: String? = null, onUndo: () -> Unit) {
    val c = UTheme.colors
    val r = row.receipt
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = c.green, modifier = Modifier.size(14.dp))
            Text(
                r.label, style = UFont.sans(12), color = c.ink2, modifier = Modifier.weight(1f),
                textDecoration = if (r.undone) TextDecoration.LineThrough else null,
            )
            when {
                r.undone -> Text("undone", style = UFont.sans(11), color = c.ink3)
                note != null -> {}
                r.undo != null -> Text(
                    receiptUndoLabel(r.undo!!.kind, inFlight),
                    style = UFont.sans(12, FontWeight.SemiBold),
                    color = if (inFlight) c.ink3 else c.coral,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        // In flight the tap is a no-op (the VM ignores a repeat for the
                        // same key anyway) — no second cancel round trip, no lie.
                        .clickable(role = Role.Button, enabled = !inFlight, onClick = onUndo)
                        .minimumInteractiveComponentSize().padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (note != null && !r.undone) {
            // Polite live region: TalkBack reads why nothing changed.
            Text(
                note, style = UFont.sans(11), color = c.ink3,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The DISPLAY filter (1:1 with web lib/assistant/display.ts + iOS
 *  AssistantModel.displayTurns). The persisted thread keeps every round — the
 *  model needs its own tool_calls narration and the hidden bounces next
 *  request — but a person sees only their bubbles, each turn's FINAL reply
 *  (with its receipts) and the local check-in lines. Hidden: tool turns, the
 *  guard bounce + the claim it answers, the cut-off hint, empty turns, and
 *  every assistant round that CARRIES tool_calls — its text ("I'll get your
 *  lists…", "Let me try the correct tool:") is the model narrating its next
 *  step, which rendered as one bubble per round (tester round, iOS
 *  2026-09-06: four bubbles for one question). */
internal fun visibleAssistantTurns(messages: List<ChatMessage>): List<ChatMessage> =
    messages.filterIndexed { i, m ->
        (m.role == "user" || m.role == "assistant") && !m.content.isNullOrBlank() &&
            m.toolCalls.isNullOrEmpty() && !isHiddenHarnessTurn(messages, i)
    }

/** The harness's hidden turns: the corrective bounce / cut-off hint (user role)
 *  and the fabricated claim the corrective answers (the assistant turn right
 *  before it). Persisted for the model window, never shown. */
internal fun isHiddenHarnessTurn(messages: List<ChatMessage>, i: Int): Boolean {
    val m = messages[i]
    val hiddenUser = setOf(AssistantHarnessRules.CORRECTIVE, AssistantHarnessRules.CUT_OFF_HINT)
    if (m.role == "user" && m.content in hiddenUser) return true
    if (m.role == "assistant") {
        val next = messages.getOrNull(i + 1)
        if (next?.role == "user" && next.content == AssistantHarnessRules.CORRECTIVE) return true
    }
    return false
}

/** A queued send — the user's bubble, faded, until its turn starts. */
@Composable
private fun PendingBubble(text: String) {
    val c = UTheme.colors
    Row(Modifier.fillMaxWidth().alpha(0.55f).semantics { contentDescription = "Queued: $text" }, horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(c.coral)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text(text, style = UFont.sans(15), color = Color.White) }
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
