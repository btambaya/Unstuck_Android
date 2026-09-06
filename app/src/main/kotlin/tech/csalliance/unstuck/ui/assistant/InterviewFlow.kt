package tech.csalliance.unstuck.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.INTERVIEW_QUESTIONS
import tech.csalliance.unstuck.core.logic.InterviewChip
import tech.csalliance.unstuck.core.logic.InterviewCopy
import tech.csalliance.unstuck.core.logic.InterviewQuestion
import tech.csalliance.unstuck.core.logic.InterviewScript
import tech.csalliance.unstuck.core.logic.RITUAL_LABELS
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

// The get-to-know-you interview — port of iOS App/Features/Interview.swift
// (InterviewMachine + InterviewFlowView) and components/assistant/interview.tsx.
// Scripted and ZERO-token: chip answers and free text write profile facts
// directly (source INTERVIEW) through the host; the LLM is never involved. One
// question at a time, every question skippable, "I'm done" always visible.
// Everything saved is visible (and deletable) in Settings → "What Unstuck knows".
//
// [InterviewFlowController] is the machine (iOS InterviewMachine): `save` is
// its only fact side effect and the host carries the done flag + the resumable
// step, so the step/auto-done/resume/skip rules are unit-tested against a fake
// host without Compose. [InterviewFlow] renders it.

/** The panel's observable state — one immutable snapshot per change. */
data class InterviewFlowState(
    /** 0 until questions.size = a question; == questions.size = the rituals picker. */
    val step: Int = 0,
    /** Facts saved this run, newest last — drives the "✓ Noted: …" line. */
    val noted: List<String> = emptyList(),
    /** True once finished (or pinned done by the account) — the host stops rendering the panel. */
    val finished: Boolean = false,
    /** Set when the LAST answer's save failed: the step is kept so the user
     *  can retry, and the panel says so inline. Cleared by the next
     *  successful answer or skip. */
    val saveError: String? = null,
)

class InterviewFlowController(
    private val host: InterviewHost,
    val questions: List<InterviewQuestion> = INTERVIEW_QUESTIONS,
) {
    private val _state = MutableStateFlow(
        // Resume where the user left off (a collapse persists the step); a
        // stale/out-of-range value restarts at the greeting.
        InterviewFlowState(step = InterviewScript.resumeStep(host.interviewParkedStep(questions.size), questions.size)),
    )
    val state: StateFlow<InterviewFlowState> = _state.asStateFlow()
    /** The account flag is pushed at most once per run (finish, or reaching the picker). */
    private var doneNotified = false

    val step: Int get() = _state.value.step
    val isPicker: Boolean get() = InterviewScript.isPicker(step, questions.size)
    val current: InterviewQuestion? get() = InterviewScript.current(step, questions)
    /** "2/7" for the eyebrow. */
    val progress: String get() = InterviewScript.progress(step, questions.size)
    val eyebrow: String get() = InterviewScript.eyebrow(step, questions.size)
    val isFirstStep: Boolean get() = step == 0
    val noted: List<String> get() = _state.value.noted
    val finished: Boolean get() = _state.value.finished
    val saveError: String? get() = _state.value.saveError

    /** Tap a chip: save its fact (if any) and advance. A failed save keeps
     *  the step (and says so) — nothing is noted that didn't land. */
    suspend fun answer(chip: InterviewChip) {
        val q = current ?: return
        val fact = chip.fact
        if (fact != null && !store(q.category, fact)) return
        _state.update { it.copy(saveError = null) }
        advance()
    }

    /** Free-text answer. Empty → no-op (stays on the question). Name questions
     *  split into one person fact per name. Advances only when EVERY piece
     *  saved; the ones that did are noted, and a retry re-saves the rest
     *  (saves are refine-in-place, so nothing duplicates). */
    suspend fun answerFree(text: String) {
        val q = current ?: return
        val facts = InterviewScript.freeTextFacts(q, text)
        if (facts.isEmpty()) return
        var allSaved = true
        for (f in facts) if (!store(q.category, f)) allSaved = false
        if (!allSaved) return
        _state.update { it.copy(saveError = null) }
        advance()
    }

    /** Skip just this question (nothing saved). */
    fun skipQuestion() {
        _state.update { it.copy(saveError = null) }
        advance()
    }

    /** "That's me set up" / "I'm done": mark done — never re-asks, on ANY
     *  device (the host pushes the account flag). Facts already saved stay. */
    fun finish() {
        notifyDone()
        host.clearInterviewStep()
        _state.update { it.copy(finished = true) }
    }

    /** Park the panel WITHOUT finishing (the header chevron): the step is
     *  persisted so re-opening resumes here — also across relaunch — and,
     *  while parked, the card shows the pill instead of popping the panel
     *  open again. Nothing is marked done. */
    fun collapse() = markInProgress()

    /** Persist the current step. The host calls this the moment it OPENS the
     *  panel: without a persisted step a user with 0 facts got the panel at
     *  1/N on EVERY cold launch — the nag it exists to avoid. */
    fun markInProgress() = host.setInterviewStep(step)

    /** The picker's toggles route through the host's account-wide write. */
    fun setRitual(key: RitualKey, on: Boolean) = host.setRituals(host.rituals.value.with(key, on))

    /** The account says done (a server pin landing while the panel is open, or
     *  a finish on another device): close without pushing again. Web/iOS
     *  `InterviewFlag.apply` — done never keeps the panel open. */
    fun applyHostDone() {
        if (!host.interviewDone.value || finished) return
        doneNotified = true
        _state.update { it.copy(finished = true) }
    }

    /** True = landed (noted); false = the write failed (step kept, error shown). */
    private suspend fun store(category: ProfileFactCategory, fact: String): Boolean {
        val ok = runCatching { host.saveProfileFact(category, fact, ProfileFactSource.INTERVIEW, null) }.getOrNull() != null
        if (!ok) {
            _state.update { it.copy(saveError = InterviewCopy.SAVE_FAILED) }
            return false
        }
        _state.update { it.copy(noted = it.noted + fact) }
        return true
    }

    private fun advance() {
        val next = InterviewScript.nextStep(step, questions.size)
        _state.update { it.copy(step = next) }
        host.setInterviewStep(next)
        // Reaching the picker IS being onboarded — even when every answer was
        // a null-fact chip: the ≥1-fact auto-done can't see those, and leaving
        // before "That's me set up" used to keep them un-done and re-asked.
        // The picker still renders; `finish` is what dismisses it.
        if (InterviewScript.reachesDone(next, questions.size)) notifyDone()
    }

    private fun notifyDone() {
        if (doneNotified) return
        doneNotified = true
        host.markInterviewDone()
    }
}

/**
 * The interview panel. [firstName] greets; [onFinished] fires after "I'm done"
 * / "That's me set up" (and when the account pins done underneath it);
 * [onCollapse] hides the panel (resumable — the step is parked).
 *
 * @param factCount how many profile facts the account has RIGHT NOW. The
 *   auto-open gate decides on the hydrate flip, and the local facts flow can
 *   emit a beat later — so a panel that opened on a stale count of 0 must stand
 *   down the moment the real facts arrive (iOS `GatewayCard.factsChanged`).
 */
@Composable
fun InterviewFlow(
    host: InterviewHost,
    firstName: String?,
    onFinished: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    factCount: Int = 0,
) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val controller = remember(host) { InterviewFlowController(host) }
    val state by controller.state.collectAsStateWithLifecycle()
    val rituals by host.rituals.collectAsStateWithLifecycle()
    val hostDone by host.interviewDone.collectAsStateWithLifecycle()
    var free by rememberSaveable { mutableStateOf("") }

    // The moment the panel is shown its step is parked (auto-open once, then the pill).
    LaunchedEffect(controller) { controller.markInProgress() }
    // The server flag pins done underneath an open panel → close, no re-push.
    LaunchedEffect(hostDone) {
        if (hostDone && !state.finished) { controller.applyHostDone(); onFinished() }
    }
    // Facts that arrive from ELSEWHERE while the panel is open but UNTOUCHED
    // (another device's hydrate or a realtime row landing a beat after the
    // auto-open gate read a stale count of 0): park it — the user has clearly
    // been here before, and the host's ≥1-fact auto-done then finishes them
    // account-wide. On the first step with nothing noted, no fact here is its
    // own answer, so one is enough (iOS `GatewayCard.factsChanged`, web
    // `shouldAutoCompleteInterview`).
    LaunchedEffect(factCount) {
        if (factCount >= 1 && !state.finished && controller.isFirstStep && controller.noted.isEmpty()) {
            controller.collapse()
            onCollapse()
        }
    }

    val q = controller.current
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = InterviewCopy.PANEL_LABEL },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (controller.isFirstStep) Greeting(firstName)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                controller.eyebrow,
                style = UFont.mono(10, FontWeight.SemiBold).copy(letterSpacing = 1.2.sp), color = c.ink3,
                modifier = Modifier.weight(1f),
            )
            // The web's two controls: the per-question Skip (below) and "I'm
            // done" — the finisher that never re-asks. The chevron only PARKS
            // the panel (the pill resumes it).
            Text(
                InterviewCopy.IM_DONE, style = UFont.sans(12), color = c.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = InterviewCopy.IM_DONE_HINT) { controller.finish(); onFinished() }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 6.dp),
            )
            Icon(
                Icons.Filled.KeyboardArrowUp, contentDescription = InterviewCopy.HIDE_FOR_NOW, tint = c.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = InterviewCopy.HIDE_FOR_NOW_HINT) { controller.collapse(); onCollapse() }
                    .minimumInteractiveComponentSize()
                    .size(20.dp),
            )
        }

        if (q != null) {
            Question(
                q = q, free = free, onFree = { free = it },
                onChip = { chip -> scope.launch { controller.answer(chip); free = "" } },
                onSkip = { controller.skipQuestion(); free = "" },
                onSaveFree = {
                    val t = free
                    if (t.isNotBlank()) scope.launch { controller.answerFree(t); free = "" }
                },
            )
        } else {
            RitualsPicker(
                isOn = { rituals[it] },
                set = { key, on -> controller.setRitual(key, on) },
                onDone = { controller.finish(); onFinished() },
            )
        }

        // A dropped local write keeps the step and says so — never a
        // "✓ Noted" the store didn't take.
        state.saveError?.let { err ->
            Text(err, style = UFont.sans(12), color = c.coralDeep, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        state.noted.lastOrNull()?.let { last ->
            Text(
                InterviewCopy.NOTED_PREFIX + last, style = UFont.sans(12), color = c.ink3, maxLines = 2,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Noted: $last" },
            )
        }
    }
}

/** The agent's FIRST words to a new user: one plain line (mirrors the web
 *  interview.tsx greeting verbatim), then the small-print disclosure. */
@Composable
private fun Greeting(firstName: String?) {
    val c = UTheme.colors
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 6.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.bg2).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(InterviewCopy.greeting(firstName), style = UFont.sans(14).copy(lineHeight = 21.sp), color = c.ink)
        Text(InterviewCopy.DISCLOSURE, style = UFont.sans(11).copy(lineHeight = 16.sp), color = c.ink3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Question(
    q: InterviewQuestion,
    free: String,
    onFree: (String) -> Unit,
    onChip: (InterviewChip) -> Unit,
    onSkip: () -> Unit,
    onSaveFree: () -> Unit,
) {
    val c = UTheme.colors
    Text(q.question, style = UFont.serif(19).copy(lineHeight = 26.sp), color = c.ink)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        q.chips.forEach { chip ->
            Text(
                chip.label, style = UFont.sans(13), color = c.ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button) { onChip(chip) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            )
        }
        Text(
            InterviewCopy.SKIP, style = UFont.sans(12), color = c.ink3,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = InterviewCopy.SKIP_QUESTION_LABEL) { onSkip() }
                .padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
    if (q.allowFree) {
        val can = free.isNotBlank()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                BasicTextField(
                    value = free, onValueChange = onFree,
                    textStyle = UFont.sans(13).copy(color = c.ink), cursorBrush = SolidColor(c.ink), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveFree() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = InterviewCopy.FREE_LABEL },
                    decorationBox = { inner ->
                        if (free.isEmpty()) {
                            Text(if (q.splitNames) InterviewCopy.FREE_PLACEHOLDER_NAMES else InterviewCopy.FREE_PLACEHOLDER, style = UFont.sans(13), color = c.ink3)
                        }
                        inner()
                    },
                )
            }
            Text(
                InterviewCopy.SAVE, style = UFont.sans(12, FontWeight.SemiBold), color = if (can) Color.White else c.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp)).background(if (can) c.coral else c.bg2)
                    .clickable(enabled = can, role = Role.Button) { onSaveFree() }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** Final step — which recurring moments the assistant should run. The
 *  rituals themselves are a personalisation choice; all changeable in
 *  Settings → What Unstuck knows. */
@Composable
private fun RitualsPicker(isOn: (RitualKey) -> Boolean, set: (RitualKey, Boolean) -> Unit, onDone: () -> Unit) {
    val c = UTheme.colors
    Text(InterviewCopy.PICKER_QUESTION, style = UFont.serif(19).copy(lineHeight = 26.sp), color = c.ink)
    RitualChips(isOn, set)
    Text(
        InterviewCopy.THATS_ME_SET_UP, style = UFont.sans(13, FontWeight.SemiBold), color = Color.White,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp)).background(c.coral)
            .clickable(role = Role.Button) { onDone() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** The four ritual toggles as selectable chips (interview picker). Copy from
 *  RITUAL_LABELS so the interview, Settings and the web read identically. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RitualChips(isOn: (RitualKey) -> Boolean, set: (RitualKey, Boolean) -> Unit) {
    val c = UTheme.colors
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RITUAL_LABELS.forEach { r ->
            val on = isOn(r.key)
            Text(
                (if (on) "✓ " else "") + r.label,   // glyph is visual only — the semantics below carry the name
                style = UFont.sans(13), color = if (on) Color.White else c.ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) c.coral else c.surface)
                    .border(1.dp, if (on) c.coral else c.line2, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Checkbox, onClickLabel = r.sub) { set(r.key, !on) }
                    .semantics { contentDescription = r.label; selected = on }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            )
        }
    }
}
