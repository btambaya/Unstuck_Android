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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import tech.csalliance.unstuck.core.logic.InterviewFlag
import tech.csalliance.unstuck.core.logic.InterviewQuestion
import tech.csalliance.unstuck.core.logic.InterviewScript
import tech.csalliance.unstuck.core.logic.InterviewThreadCopy
import tech.csalliance.unstuck.core.logic.RITUAL_LABELS
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.displayLabel
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

// The get-to-know-you interview — port of iOS App/Features/Interview.swift
// (InterviewMachine) + InterviewThread.swift and components/assistant/interview.tsx.
// Scripted and ZERO-token: chip answers and free text write profile facts
// directly (source INTERVIEW) through the host; the LLM is never involved. One
// question at a time, every question skippable. Everything saved is visible
// (and deletable) in Settings → Assistant & privacy → "What Unstuck remembers".
//
// RE-HOSTED INSIDE THE ASSISTANT THREAD (Ahmad, 2026-09-17). The Today card
// that carried it (its "Personalise your assistant" pill and bottom sheet) is
// gone. A user who has not finished or skipped the interview meets it in the
// assistant itself:
//
//  • TEXT — after the FIRST message of a visit is handled the normal way (the
//    reply comes first), the client appends the questions as LOCAL assistant
//    turns, one at a time, with the script's chip answers + Skip (and the
//    free-text field where the script allows one). If the user changes the
//    subject mid-way, the assistant answers that first and the current
//    question is asked again underneath. ZERO tokens: the local turns never
//    enter the model window.
//  • VOICE — the opening primer asks the same questions aloud
//    (AssistantContext.buildVoiceOpening) and `finish_interview` closes it.
//
// [InterviewFlowController] is the machine (iOS InterviewMachine): `save` is
// its only fact side effect and the host carries the done flag + the resumable
// step, so the step/auto-done/resume/skip rules are unit-tested against a fake
// host without Compose. [InterviewThreadDriver] hosts it in the thread (pure
// enough to unit test: the thread is reached through two closures) and
// [InterviewPromptRow] draws the chips under the question being asked.

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
    /** True once THIS controller pushed the flag ([notifyDone]) — as opposed to
     *  having read it off the account. The panel watches `host.interviewDone`
     *  so a finish on ANOTHER device closes it; without this, reaching the
     *  rituals picker (which marks the account done, by design) fired that same
     *  watcher and slammed the panel shut on its own last step — the picker and
     *  "That's me set up" were unreachable. */
    var pushedDoneLocally = false
        private set

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
     *  `InterviewFlag.apply` — done never keeps the panel open.
     *
     *  Only a flag that came from ELSEWHERE closes the panel: our own
     *  [notifyDone] (reaching the picker) flips the very same host flag, and
     *  honouring that echo dismissed the sheet before the rituals step could
     *  render. @return true when this call actually finished the panel — the
     *  host dismisses only then. */
    fun applyHostDone(): Boolean {
        if (!host.interviewDone.value || finished || pushedDoneLocally) return false
        doneNotified = true
        _state.update { it.copy(finished = true) }
        return true
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
        pushedDoneLocally = true
        host.markInterviewDone()
    }
}

// ── the thread host ──────────────────────────────────────────────────────────

enum class InterviewThreadPhase {
    /** Nothing sent this visit yet. */
    IDLE,
    /** The user's first message is in flight — the reply comes first. */
    WAITING_FOR_REPLY,
    /** A question (or the rituals picker) is on screen with its chips. */
    ASKING,
    /** Finished or stood down — nothing more to ask, ever. */
    DONE,
}

data class InterviewThreadState(
    val phase: InterviewThreadPhase = InterviewThreadPhase.IDLE,
    /** The id of the thread turn carrying the live chip row (null = none). */
    val promptTurnId: String? = null,
)

/**
 * The interview inside the assistant thread (iOS InterviewThreadDriver). The
 * thread is reached through two closures: [post] appends a LOCAL assistant turn
 * and returns its id, [echo] appends the user's tap as a local user bubble.
 *
 * @param ready whether the account's memory has been read (the profile-facts
 *   hydrate + the server done-flag): a decision before that greeted a fresh
 *   install of an onboarded account as a stranger.
 * @param factCount live count of ACTIVE profile facts — the stand-down rule's input.
 */
class InterviewThreadDriver(
    val controller: InterviewFlowController,
    private val host: InterviewHost,
    private val firstName: String?,
    private val ready: () -> Boolean = { true },
    private val factCount: () -> Int,
    private val post: (String) -> String,
    private val echo: (String) -> Unit,
    /** The chip as the user saw it — its clock hour the phone's way
     *  ([displayLabel]); the echoed answer reads the same as the chip tapped. */
    private val labelOf: (InterviewChip) -> String = { it.label },
) {
    private val _state = MutableStateFlow(InterviewThreadState())
    val state: StateFlow<InterviewThreadState> = _state.asStateFlow()
    val phase: InterviewThreadPhase get() = _state.value.phase
    val promptTurnId: String? get() = _state.value.promptTurnId
    /** The chip row is on screen. */
    val isAsking: Boolean get() = phase == InterviewThreadPhase.ASKING
    private var greeted = false

    // ── thread events ──

    /** The user sent a message. The FIRST one of a visit arms the interview —
     *  the reply to it comes first, the question after. Someone who already
     *  has facts from elsewhere (web, another device) with nothing parked
     *  mid-way is stood down here instead: the existing ≥1-fact rule, so a
     *  person the assistant already knows is never greeted as a stranger. */
    fun userSent() {
        if (phase != InterviewThreadPhase.IDLE || !ready()) return
        if (host.interviewDone.value) { setPhase(InterviewThreadPhase.DONE); return }
        val parked = host.interviewParkedStep(controller.questions.size)
        if (InterviewFlag.shouldAutoComplete(factCount(), isOpen = false, done = false, parkedStep = parked)) {
            controller.finish()
            setPhase(InterviewThreadPhase.DONE)
            return
        }
        setPhase(InterviewThreadPhase.WAITING_FOR_REPLY)
    }

    /** A turn finished (reply or error) — the assistant has handled what the
     *  user asked; now ask the current question. While a question is already
     *  up this is the user having changed the subject: the reply came first,
     *  the same question goes underneath it again. */
    fun turnFinished() {
        when (phase) {
            InterviewThreadPhase.WAITING_FOR_REPLY -> {
                setPhase(InterviewThreadPhase.ASKING)
                controller.markInProgress()
                ask()
            }
            InterviewThreadPhase.ASKING -> ask()
            InterviewThreadPhase.IDLE, InterviewThreadPhase.DONE -> Unit
        }
    }

    /** The account says done from ELSEWHERE (a finish on another device, a
     *  server pin) while a question is up: stand down. The controller's own
     *  push (reaching the picker) is not "elsewhere" — the picker stays. */
    fun hostDone(): Boolean {
        if (!isAsking || !controller.applyHostDone()) return false
        _state.value = InterviewThreadState(InterviewThreadPhase.DONE, null)
        return true
    }

    // ── answers ──

    /** Tap a chip: save its fact (if any) and move on. A failed save keeps
     *  the question up and says so (`controller.saveError`) — nothing is
     *  echoed that didn't land. */
    suspend fun answer(chip: InterviewChip) {
        if (!isAsking || controller.current == null) return
        val before = controller.step
        controller.answer(chip)
        if (controller.step <= before) return
        echo(labelOf(chip))
        ask()
    }

    /** Free-text answer (the questions that allow one). Empty → no-op. */
    suspend fun answerFree(text: String) {
        if (!isAsking || controller.current == null) return
        val t = text.trim()
        if (t.isEmpty()) return
        val before = controller.step
        controller.answerFree(t)
        if (controller.step <= before) return
        echo(t)
        ask()
    }

    /** Skip just this question (nothing saved). */
    fun skip() {
        if (!isAsking || controller.current == null) return
        controller.skipQuestion()
        echo(InterviewCopy.SKIP)
        ask()
    }

    /** "That's me set up" on the rituals picker — the finisher. Marks done
     *  (never re-asks, on any device) and closes with one line. */
    fun finish() {
        if (!isAsking) return
        controller.finish()
        _state.value = InterviewThreadState(InterviewThreadPhase.DONE, null)
        post(InterviewThreadCopy.CLOSING)
    }

    // ── prompts ──

    private fun ask() {
        if (controller.isFirstStep && !greeted) {
            greeted = true
            post(InterviewThreadCopy.greeting(firstName))
        }
        val q = controller.current
        val id = post(q?.question ?: InterviewThreadCopy.PICKER_QUESTION)
        _state.update { it.copy(promptTurnId = id) }
    }

    private fun setPhase(p: InterviewThreadPhase) = _state.update { it.copy(phase = p) }
}

// ── the chip row ─────────────────────────────────────────────────────────────

/**
 * The answers for the prompt the driver is asking, drawn under that turn's
 * bubble: chips + Skip (+ the free-text field where the script allows one),
 * or the rituals picker + "That's me set up". The eyebrow keeps the web's
 * "GETTING TO KNOW YOU · n/7". Chip + button styling is the interview's own
 * (the old panel) — nothing new.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterviewPromptRow(driver: InterviewThreadDriver, ritualIsOn: (RitualKey) -> Boolean, setRitual: (RitualKey, Boolean) -> Unit) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val controller = driver.controller
    val flow by controller.state.collectAsStateWithLifecycle()
    var free by rememberSaveable(driver.promptTurnId) { mutableStateOf("") }
    val q = controller.current
    val clock = tech.csalliance.unstuck.ui.components.clockMode()
    Column(
        Modifier.fillMaxWidth().padding(start = 4.dp).semantics { contentDescription = InterviewCopy.PANEL_LABEL },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(controller.eyebrow, style = UFont.mono(10, FontWeight.SemiBold).copy(letterSpacing = 1.2.sp), color = c.ink3)
        if (q != null) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                q.chips.forEach { chip ->
                    Text(
                        // "Before 09:00" / "Before 9am" — the phone's 12/24-hour setting.
                        chip.displayLabel(clock), style = UFont.sans(13), color = c.ink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(999.dp))
                            .clickable(role = Role.Button) { scope.launch { driver.answer(chip); free = "" } }
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                            .wrapContentHeight(),
                    )
                }
                Text(
                    InterviewCopy.SKIP, style = UFont.sans(12), color = c.ink3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = InterviewCopy.SKIP_QUESTION_LABEL) { driver.skip(); free = "" }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .wrapContentHeight(),
                )
            }
            if (q.allowFree) {
                val can = free.isNotBlank()
                fun saveFree() { val t = free; if (t.isNotBlank()) scope.launch { driver.answerFree(t); free = "" } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        BasicTextField(
                            value = free, onValueChange = { free = it },
                            textStyle = UFont.sans(13).copy(color = c.ink), cursorBrush = SolidColor(c.ink), singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { saveFree() }),
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
                            .clickable(enabled = can, role = Role.Button) { saveFree() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        } else if (controller.isPicker) {
            // Final step — which recurring moments the assistant should run. The
            // rituals themselves are a personalisation choice; all changeable in
            // Settings → Assistant & privacy → What Unstuck remembers.
            RitualChips(ritualIsOn, setRitual)
            Text(
                InterviewCopy.THATS_ME_SET_UP, style = UFont.sans(13, FontWeight.SemiBold), color = Color.White,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(999.dp)).background(c.coral)
                    .clickable(role = Role.Button) { driver.finish() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .wrapContentHeight(),
            )
        }
        // A dropped local write keeps the step and says so — never an echoed
        // answer the store didn't take.
        flow.saveError?.let { err ->
            Text(err, style = UFont.sans(12), color = c.red, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** The four ritual toggles as selectable chips (the interview's last step, now
 *  inside the assistant thread). Copy from RITUAL_LABELS so the interview,
 *  Settings and the web read identically. Selection is the app's black-and-
 *  white pair (ink fill, bg text) — the same idiom as every other chip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RitualChips(isOn: (RitualKey) -> Boolean, set: (RitualKey, Boolean) -> Unit) {
    val c = UTheme.colors
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RITUAL_LABELS.forEach { r ->
            val on = isOn(r.key)
            Text(
                (if (on) "✓ " else "") + r.label,   // glyph is visual only — the semantics below carry the name
                style = UFont.sans(13), color = if (on) c.bg else c.ink2,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) c.ink else c.bg2)
                    .border(1.dp, if (on) Color.Transparent else c.line2, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Checkbox, onClickLabel = r.sub) { set(r.key, !on) }
                    .semantics { contentDescription = r.label; selected = on }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 13.dp, vertical = 8.dp)
                    .wrapContentHeight(),
            )
        }
    }
}
