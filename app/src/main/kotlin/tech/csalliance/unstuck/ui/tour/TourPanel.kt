package tech.csalliance.unstuck.ui.tour

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.design.color.oklch
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.design.component.neutralPill

// TourPanel — the running step's card, plus the shared modal shell + rows the
// welcome / paused surfaces use. Ported from the web tour-panel.tsx +
// listen-bar.tsx, matching the iOS port's layout decisions. The HOST decides
// which vertical edge the panel docks to (opposite the spotlight target —
// non-negotiable #1) and whether it renders collapsed (title + controls only,
// so a both-halves-spanning target is never covered).

/* ============================================================
 * FIXED answer-bubble colors — deliberately THEME-INDEPENDENT
 * (web's dark-mode fix, iOS parity): a constant light surface
 * with constant dark ink reads correctly in BOTH themes.
 * oklch(0.93 0.005 280) / oklch(0.25 0.02 280) / oklch(0.42 0.02 280).
 * The surface was lavender (chroma 0.04) until the no-indigo
 * rule (owner, 2026-09-24); now the neutrals' own 0.005, as on web.
 * ============================================================ */
val TourAnswerBg: Color = oklch(0.93, 0.005, 280.0)
val TourAnswerInk: Color = oklch(0.25, 0.02, 280.0)
val TourThinkingInk: Color = oklch(0.42, 0.02, 280.0)

/** One Q/A bubble on screen. */
private data class TourQaBubble(val id: Int, val fromUser: Boolean, val text: String)

@Composable
fun TourPanel(
    step: TourStep,
    index: Int,
    total: Int,
    steps: List<TourStep>,
    mode: TourMode,
    mediaMode: TourMediaMode,
    onMediaMode: (TourMediaMode) -> Unit,
    speed: Float,
    onCycleSpeed: () -> Unit,
    audio: TourAudioController,
    /** Whether this step HAS a bundled clip at all (Read mode keeps the audio
     *  controller released, so audio.available can't answer this). A step
     *  without a clip hides every Listen affordance — never a crash. */
    stepHasAudio: Boolean,
    collapsed: Boolean,
    /** Hard height cap from the host (dock-side space outside the ring). */
    maxHeight: Dp,
    /** Reports the Ask field's focus so the host can lift the panel above the
     *  keyboard (dock=TOP, collapse suppressed) while the user types. */
    onAskFocus: (Boolean) -> Unit = {},
    /** One tour-framed round-trip through the production assistant
     *  (AppViewModel.tourAsk); null reply → canned TOUR_QA fallback. */
    askTransport: suspend (List<ChatMessage>, TourStep) -> String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    /** Round-2 #5: the footer shows an inline pause CONFIRM (never an instant
     *  pause). Pause-tap and the back gesture ARM it; [onConfirmPause] pauses
     *  for real; [onKeepGoing] disarms. */
    pauseConfirmArmed: Boolean,
    onRequestPause: () -> Unit,
    onConfirmPause: () -> Unit,
    onKeepGoing: () -> Unit,
    onExit: () -> Unit,
) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()

    // Per-step ask/more state — remember(step.id) resets the thread, the wire
    // history, and the expansions whenever the step changes (web parity).
    var asking by remember(step.id) { mutableStateOf(false) }
    var showMore by remember(step.id) { mutableStateOf(false) }
    var question by remember(step.id) { mutableStateOf("") }
    var busy by remember(step.id) { mutableStateOf(false) }
    val qa = remember(step.id) { mutableStateListOf<TourQaBubble>() }
    val wire = remember(step.id) { mutableStateListOf<ChatMessage>() }
    var seq by remember(step.id) { mutableIntStateOf(0) }
    // …and so is the body's SCROLL OFFSET. TourHost calls TourPanel from ONE
    // stable call site with no key(), so an un-keyed rememberScrollState would
    // outlive the step: leave step N scrolled down and step N+1 — whose cap
    // may leave only a line or two of viewport — opens already scrolled past
    // its own first line. Keyed, every step opens at its title.
    val bodyScroll = rememberSaveable(step.id, saver = ScrollState.Saver) { ScrollState(0) }

    fun submitQuestion() {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        question = ""
        busy = true
        qa.add(TourQaBubble(++seq, fromUser = true, text = q))
        val outgoing = buildAskWire(step.title, step.body, wire.toList(), q)
        scope.launch {
            val res = sendTourAsk(outgoing, q) { msgs -> askTransport(msgs, step) }
            // step-change recreates all of this state (keyed on step.id), so a
            // late reply lands on the dropped snapshot — harmless by design.
            wire.clear()
            wire.addAll(outgoing + ChatMessage(role = "assistant", content = res.text))
            qa.add(TourQaBubble(++seq, fromUser = false, text = res.text))
            busy = false
        }
    }

    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            // Round-2 #5 confirm priority: while the pause confirm is armed the
            // cap is floored so the confirm's buttons + Settings line NEVER
            // clip at the collapsed floor — the weighted body gives way
            // instead (it scrolls; the unweighted footer measures before it).
            // The floor is the confirm's REAL requirement at this font scale,
            // which sits below the readable minimum, so an already-expanded
            // panel is never grown out over the ring by arming the confirm.
            // NOTE: the panel deliberately reports NO measured height. An
            // expanded panel is routinely rendered CAPPED (the body scrolls),
            // so its measurement is the cap, not its natural height — feeding
            // that back into the collapse decision made it oscillate. The
            // decision runs off panelPlacement's derived readable minimum.
            .heightIn(max = tourPanelMaxHeight(maxHeight, pauseConfirmArmed, LocalDensity.current.fontScale))
            .shadow(18.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(c.bg)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            // The panel is its own surface — taps must never fall through to
            // the app beneath it.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { contentDescription = "Product tour" },
    ) {
        // ── Header: identity + progress + exit ─────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(c.bg2), contentAlignment = Alignment.Center) {
                Orbit(size = 16)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "${step.stage} · ${index + 1} of $total ${if (mode == TourMode.ESSENTIAL) "essentials" else "steps"}".uppercase(),
                    style = UFont.mono(10).copy(letterSpacing = 1.sp),
                    color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Soft progress dots — current stretches to a pill.
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    steps.forEachIndexed { i, _ ->
                        Box(
                            Modifier
                                .width(if (i == index) 16.dp else 6.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (i == index) c.ink else if (i < index) c.ink3 else c.line2),
                        )
                    }
                }
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onExit),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = "Exit tour", tint = c.ink3, modifier = Modifier.size(16.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))

        // ── Body (scrolls under the hard cap; collapsed = title only) ──────
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(bodyScroll)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 4.dp),
        ) {
            Text(step.title, style = UFont.serifItalic(20), color = c.ink)
            if (!collapsed) {
                Text(step.body, style = UFont.sans(13).copy(lineHeight = 20.sp), color = c.ink2, modifier = Modifier.padding(top = 8.dp))

                if (showMore && step.more != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(c.line))
                    Text(step.more, style = UFont.sans(12).copy(lineHeight = 18.sp), color = c.ink3, modifier = Modifier.padding(top = 10.dp))
                }

                if (mediaMode == TourMediaMode.LISTEN && audio.available) {
                    TourListenBar(audio, speed, onCycleSpeed)
                }

                if (asking && (qa.isNotEmpty() || busy)) {
                    val qaScroll = rememberScrollState()
                    LaunchedEffect(qa.size, busy) { qaScroll.animateScrollTo(qaScroll.maxValue) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(max = 180.dp)
                            .verticalScroll(qaScroll),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        qa.forEach { m ->
                            Box(
                                Modifier
                                    .align(if (m.fromUser) Alignment.End else Alignment.Start)
                                    .widthIn(max = 300.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (m.fromUser) c.bg2 else TourAnswerBg)
                                    .padding(horizontal = if (m.fromUser) 11.dp else 12.dp, vertical = if (m.fromUser) 6.dp else 10.dp),
                            ) {
                                Text(m.text, style = UFont.sans(12).copy(lineHeight = 18.sp), color = if (m.fromUser) c.ink else TourAnswerInk)
                            }
                        }
                        if (busy) ThinkingBubble()
                    }
                }

                if (asking) {
                    // The field can leave composition while focused (Hide
                    // questions / step change) without a focus-lost callback —
                    // never leave the host thinking the keyboard is still up.
                    DisposableEffect(Unit) { onDispose { onAskFocus(false) } }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.surface)
                                .border(1.dp, c.line2, RoundedCornerShape(999.dp))
                                .padding(horizontal = 13.dp, vertical = 9.dp),
                        ) {
                            BasicTextField(
                                value = question,
                                onValueChange = { question = it },
                                modifier = Modifier.fillMaxWidth().onFocusChanged { onAskFocus(it.isFocused) },
                                textStyle = UFont.sans(13).copy(color = c.ink),
                                singleLine = true,
                                cursorBrush = SolidColor(c.ink),
                                decorationBox = { inner ->
                                    if (question.isEmpty()) {
                                        Text(
                                            if (qa.isEmpty()) "Ask anything — the Assistant answers…" else "Follow up…",
                                            style = UFont.sans(13), color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    inner()
                                },
                            )
                        }
                        val canSend = question.isNotBlank() && !busy
                        Box(
                            Modifier
                                .size(34.dp)
                                .alpha(if (canSend) 1f else 0.5f)
                                .clip(CircleShape)
                                .background(c.ink)
                                .clickable(enabled = canSend) { submitQuestion() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Send question", tint = c.bg, modifier = Modifier.size(14.dp)) }
                    }
                }

                // Secondary link row.
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TourMiniLink(if (asking) "Hide questions" else "Ask a question") { asking = !asking }
                    // Round-2 #3: in Listen mode, expanding Tell-me-more pauses
                    // the narration and plays the step's more clip (captioned);
                    // collapsing stops the clip and the narration picks back up.
                    // Read mode is unchanged (text only).
                    if (step.more != null) TourMiniLink(if (showMore) "Less" else "Tell me more") {
                        val expanding = !showMore
                        showMore = expanding
                        if (mediaMode == TourMediaMode.LISTEN) {
                            if (expanding) audio.startMore(step.id, step.more, speed)
                            else audio.stopMore()
                        }
                    }
                    if (stepHasAudio) {
                        TourMiniLink(if (mediaMode == TourMediaMode.LISTEN) "Read instead" else "Listen") {
                            onMediaMode(if (mediaMode == TourMediaMode.LISTEN) TourMediaMode.READ else TourMediaMode.LISTEN)
                        }
                    }
                }
            }
        }

        // ── Footer controls (or the inline pause confirm — round-2 #5) ─────
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(c.line))
        if (pauseConfirmArmed) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(TOUR_PAUSE_CONFIRM_TITLE, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.ink)
                            .clickable(onClick = onConfirmPause)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) { Text("Pause", style = UFont.sans(13, FontWeight.SemiBold), color = c.bg) }
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface)
                            .border(1.dp, c.line2, RoundedCornerShape(999.dp))
                            .clickable(onClick = onKeepGoing)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) { Text("Keep going", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink) }
                }
                // The confirm also names the Settings path (spec round-2 #5).
                Text(TOUR_PAUSE_SETTINGS_PATH, style = UFont.sans(11).copy(lineHeight = 16.sp), color = c.ink4, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TourGhostLink("Pause", onRequestPause)
                TourGhostLink("Skip", onSkip)
                Spacer(Modifier.weight(1f))
                if (index > 0) TourGhostLink("Back", onBack)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.ink)
                        .clickable(onClick = onPrimary)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(primaryLabel, style = UFont.sans(13, FontWeight.SemiBold), color = c.bg)
                    if (index < total - 1) Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = c.bg, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

/** The "Thinking…" pulse — fixed-lavender bubble, blinking ellipsis. */
@Composable
private fun ThinkingBubble() {
    val blink = rememberInfiniteTransition(label = "tour-thinking")
        .animateFloat(
            initialValue = 1f, targetValue = 0.15f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
            label = "tour-thinking-alpha",
        ).value
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TourAnswerBg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "Thinking" },
    ) {
        Text("Thinking", style = UFont.sans(12), color = TourThinkingInk)
        Text("…", style = UFont.sans(12), color = TourThinkingInk, modifier = Modifier.alpha(blink))
    }
}

/* ============================================================
 * Listen bar — play/pause · replay · progress · speed, then the
 * LIVE caption (the sentence being spoken right now — round-2
 * #2, narration and Tell-me-more clips alike) + "Voice · Cherry".
 * ============================================================ */
@Composable
private fun TourListenBar(audio: TourAudioController, speed: Float, onCycleSpeed: () -> Unit) {
    val c = UTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(c.ink)
                    .clickable { if (audio.playing) audio.pause() else audio.play(speed) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (audio.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (audio.playing) "Pause narration" else "Play narration",
                    tint = c.bg, modifier = Modifier.size(15.dp),
                )
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { audio.replay(speed) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Replay, contentDescription = "Replay step", tint = c.ink3, modifier = Modifier.size(15.dp)) }
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(999.dp)).background(c.line2)) {
                Box(Modifier.fillMaxWidth(audio.progress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(999.dp)).background(c.ink))
            }
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onCycleSpeed).padding(horizontal = 9.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Narration speed" },
            ) { Text("${formatTourSpeed(speed)}×", style = UFont.sans(11, FontWeight.Bold), color = c.ink2) }
        }
        Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Live caption — subtitle-style. TWO lines with a FIXED reserved
            // height (minLines == maxLines): long sentences get a second line
            // before the ellipsis, and the panel never grows/shrinks as the
            // spoken sentence changes.
            Text(
                audio.caption ?: "",
                style = UFont.sans(11).copy(lineHeight = 15.sp), color = c.ink2,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Voice · $TOUR_AUDIO_VOICE", style = UFont.sans(10, FontWeight.SemiBold), color = c.ink3)
        }
    }
}

/** "1×" not "1.0×" (web parity for whole speeds). */
fun formatTourSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')

/* ── The panel's height minima ──────────────────────────────────────────────
 * All three are summed in TourData.kt (tourPanelCollapsedMinDp /
 * tourPanelReadableMinDp / tourPauseConfirmMinDp) from the literal paddings,
 * sizes and line heights of the composables ABOVE — change a padding here and
 * the matching term there. They live next to panelPlacement because the
 * placement rule is what decides with them, and only a pure function can be
 * unit-tested; the two Dp values below are just the fontScale-1 readings, for
 * docs and for callers with no Density to hand. */

/** Round-2 #5 confirm priority: the panel's height floor while the pause
 *  confirm is armed, at fontScale 1 — header + title + the confirm's buttons
 *  + the Settings-path line. */
val TOUR_PAUSE_CONFIRM_MIN_PANEL_HEIGHT: Dp = tourPauseConfirmMinDp().dp

/** The READABLE minimum at fontScale 1 — the height at or above which the
 *  panel keeps the step's body copy and lets it scroll under the dock-side cap
 *  instead of collapsing to title-only. */
val TOUR_PANEL_READABLE_MIN_HEIGHT: Dp = tourPanelReadableMinDp().dp

/** The panel's effective height cap: the host's dock-side geometric cap,
 *  floored while the pause confirm is armed so the confirm's buttons and
 *  Settings line never clip at the collapsed floor. (heightIn can't exceed the
 *  parent's incoming constraints, so the floor is still screen-safe; a
 *  transient ring overlap during the confirm beats clipped actions.)
 *
 *  The floor is exactly what the confirm NEEDS and never more — it sits below
 *  [tourPanelReadableMinDp], so a panel that the placement rule already sized
 *  EXPANDED (cap ≥ readable) is never grown by arming the confirm. A flat
 *  floor above the readable minimum pushed such a panel out over the ring the
 *  moment Pause was tapped. Pure — unit-tested. */
fun tourPanelMaxHeight(cap: Dp, pauseConfirmArmed: Boolean, fontScale: Float = 1f): Dp =
    if (pauseConfirmArmed) maxOf(cap, tourPauseConfirmMinDp(fontScale).dp) else cap

/* ============================================================
 * Shared shells for the welcome + paused cards.
 * ============================================================ */

/** Centered modal card over an indigo-tinted blur-ish scrim. The scrim
 *  swallows taps (never falls through), but does NOT dismiss — the card's own
 *  actions decide (web TourCard parity). */
@Composable
fun TourCardShell(maxWidth: Dp = 460.dp, content: @Composable () -> Unit) {
    val c = UTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x47141228))   // rgba(20,18,40,0.28)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(20.dp)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .shadow(24.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(c.bg)
                .border(1.dp, c.line, RoundedCornerShape(22.dp))
                .padding(horizontal = 26.dp)
                .padding(top = 28.dp, bottom = 24.dp),
        ) { content() }
    }
}

/** One welcome-card mode option (Essential / Full / Explore). */
@Composable
fun TourModeRow(title: String, sub: String, meta: String? = null, recommended: Boolean = false, onClick: () -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(if (recommended) 1.5.dp else 1.dp, if (recommended) c.ink else c.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = UFont.sans(14, FontWeight.Bold), color = c.ink)
                if (recommended) {
                    Box(Modifier.neutralPill(c).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("SUGGESTED", style = UFont.mono(9, FontWeight.Medium).copy(letterSpacing = 0.6.sp), color = c.ink2)
                    }
                }
            }
            Text(sub, style = UFont.sans(12).copy(lineHeight = 17.sp), color = c.ink3, modifier = Modifier.padding(top = 3.dp))
        }
        if (meta != null) Text(meta, style = UFont.mono(11), color = c.ink3)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = c.ink3, modifier = Modifier.size(15.dp))
    }
}

/** Read ↔ Listen segmented toggle (welcome card footer). */
@Composable
fun TourMediaToggle(mediaMode: TourMediaMode, onMediaMode: (TourMediaMode) -> Unit) {
    val c = UTheme.colors
    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2).padding(2.dp)) {
        listOf(TourMediaMode.READ to "Read", TourMediaMode.LISTEN to "Listen").forEach { (m, label) ->
            val active = mediaMode == m
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) c.surface else Color.Transparent)
                    .clickable { onMediaMode(m) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text(label, style = UFont.sans(12, FontWeight.SemiBold), color = if (active) c.ink else c.ink3) }
        }
    }
}

/** Small pill link (Ask a question / Tell me more / Listen). */
@Composable
fun TourMiniLink(label: String, onClick: () -> Unit) {
    val c = UTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface)
            .border(1.dp, c.line2, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) { Text(label, style = UFont.sans(12, FontWeight.Medium), color = c.ink2) }
}

/** Quiet text action (Pause / Skip / Back / Not now). */
@Composable
fun TourGhostLink(label: String, onClick: () -> Unit) {
    val c = UTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    ) { Text(label, style = UFont.sans(12, FontWeight.SemiBold), color = c.ink3) }
}
