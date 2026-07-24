package tech.csalliance.unstuck.ui.tour

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Guided onboarding tour — step data, canned Q&A, persistence, and the pure
// placement rules. A VERBATIM port of the web components/tour/tour-data.ts
// (ids, stages, titles, body, narration, more, TOUR_QA), with the Android view
// mapping from docs/tour-mobile-spec.md.
//
// DELIBERATE DEVIATION (focus steps): the web tour navigates to /focus in its
// idle "Begin focus" state. Android's FocusScreen mounts WITH a task and
// starts a session in a LaunchedEffect (vm.startFocus) — there is no idle,
// no-session focus surface, and the tour must NEVER mint a real session. So
// the focus + capture steps stay on Today and spotlight the begin-focus
// affordance (the Start-Next hero's Focus button) instead of a focus ring.
//
// This file is deliberately Compose-free (pure data + SharedPreferences +
// shared-flow events) so the step list and phase rules stay unit-testable.

enum class TourMode { ESSENTIAL, FULL }
enum class TourMediaMode { READ, LISTEN }

/** The surfaces a step can open; the orchestrator maps these onto the real
 *  scaffold navigation (today→Today tab, captures→Inbox, insights→Insights,
 *  settings→Settings sub-screen, focus→Today per the deviation above). */
enum class TourView { TODAY, TASKS, CALENDAR, CAPTURES, COLLECTIONS, INSIGHTS, SETTINGS, FOCUS }

/* ============================================================
 * Spotlight anchor ids — the Android equivalent of the web's
 * data-tour selector set. Composables register their rects via
 * Modifier.tourAnchor(id) (see TourSpotlight.kt).
 * ============================================================ */
object TourAnchorIds {
    const val START_NEXT = "start-next"
    const val BACKLOG_POINTER = "backlog-pointer"
    const val TODAY_LIST = "today-list"
    const val FIRST_ACTION = "first-action"
    const val NEW_TASK = "new-task"
    const val ASSISTANT_LAUNCH = "assistant-launch"
    /** The Today begin-focus affordance (the hero's Focus button) — stands in
     *  for the web's focus-ring anchor (see the focus deviation above). */
    const val FOCUS_BEGIN = "focus-begin"
    const val NOTIF_BODY = "notif-body"
}

/* ============================================================
 * STEP DATA — copy ported verbatim from the web tour-data.ts.
 * ============================================================ */
data class TourStep(
    val id: String,
    val stage: String,
    val view: TourView,
    /** Settings steps deep-link a specific section (web section names). */
    val section: String? = null,
    /** Anchor id to spotlight; null = whisper-light scrim only. */
    val target: String?,
    /** Fallback anchor CHAIN, tried in order when the primary isn't on screen
     *  (e.g. an empty account has no task detail → spotlight New task).
     *  Mirrors the iOS port's fallbacks array — richer than the web's single
     *  targetFallback because phone screens change layout more (no hero, no
     *  backlog pointer, …). */
    val fallbacks: List<String> = emptyList(),
    val title: String,
    val body: String,
    val narration: String,
    val more: String? = null,
    val primary: String,
    /** Side-effect fired when the primary CTA is pressed (web onShow). */
    val onShow: TourSideEffect? = null,
)

/** Declarative side-effects (the web uses window events; the orchestrator
 *  maps these onto TourController calls). */
enum class TourSideEffect { OPEN_ASSISTANT }

val ESSENTIAL_STEPS: List<TourStep> = listOf(
    TourStep(
        id = "welcome", stage = "Welcome", view = TourView.TODAY, target = null,
        title = "This is Unstuck",
        body = "Unstuck helps when you know what needs doing but starting, switching, or coming back feels hard. It’s not another task manager — it’s an external executive-function layer.",
        narration = "Welcome to Unstuck. This helps when you already know what needs doing, but starting, switching, or coming back feels hard. It isn’t another task manager — think of it as an external executive-function layer. We’ll keep this short.",
        more = "A normal planner assumes the hard part is deciding what to do. Unstuck assumes you often already know — and the friction is beginning, sustaining, and returning. Everything here is built for that moment.",
        primary = "Continue",
    ),
    TourStep(
        id = "today", stage = "Today", view = TourView.TODAY,
        // Empty-account fallback: a brand-new account with no tasks renders NO
        // hero card at all — fall through the backlog pointer to the Today list.
        target = TourAnchorIds.START_NEXT,
        fallbacks = listOf(TourAnchorIds.BACKLOG_POINTER, TourAnchorIds.TODAY_LIST),
        title = "Today narrows it down",
        body = "Start Next offers one realistic suggestion — with a short reason, like the time it fits. It’s a recommendation, never a command. Today shows only planned work; everything else waits in Backlog.",
        narration = "This is Today. Instead of a long list, Start Next offers one realistic suggestion, with a short reason — like the gap it fits before your next meeting. It’s a suggestion, never a command. Today shows only planned work; everything else waits quietly in your Backlog.",
        more = "Usable Time (top of the page and right rail) already accounts for meetings and fragmentation, so the suggestion is grounded in the time you actually have.",
        primary = "Continue",
    ),
    TourStep(
        id = "first-action", stage = "Tasks", view = TourView.TASKS,
        target = TourAnchorIds.FIRST_ACTION, fallbacks = listOf(TourAnchorIds.NEW_TASK),
        title = "The first physical action",
        body = "The task is the outcome. The first physical action is how you begin — something concrete like “Open the document,” not “Write the report.” This is the core anti-overwhelm move.",
        narration = "Open any task and you’ll see its first physical action. The task is the outcome; the first physical action is how you actually begin. Concrete — “open the document” — not abstract like “write the report”. This one habit removes most of the friction of starting.",
        more = "You can click any field in the task detail to edit it in place, and Start, Schedule, Share, or mark Done right from here.",
        primary = "Continue",
    ),
    TourStep(
        id = "assistant", stage = "Assistant", view = TourView.TODAY, target = TourAnchorIds.ASSISTANT_LAUNCH,
        title = "Ask Unstuck to handle it",
        body = "The Assistant lives here, bottom-right. Brain-dump in plain language — “break this down”, “what should I do first?”, “schedule this” — and it performs the real action after you confirm. It operates the app; it isn’t a separate chatbot.",
        narration = "Down here is the Assistant. You can brain-dump in plain language — break this down, what should I do first, schedule this — and it carries out the real action after you confirm. It’s wired into the whole app. Action over conversation.",
        more = "The Assistant only makes high-impact changes (sharing, bulk rescheduling, deleting) after you confirm. Low-risk things like saving a capture happen directly, with Undo.",
        primary = "Open the Assistant", onShow = TourSideEffect.OPEN_ASSISTANT,
    ),
    TourStep(
        // Focus deviation (see file header): Android has no idle focus surface,
        // so this stays on Today and rings the begin-focus affordance.
        id = "focus", stage = "Focus", view = TourView.FOCUS,
        target = TourAnchorIds.FOCUS_BEGIN,
        fallbacks = listOf(TourAnchorIds.START_NEXT, TourAnchorIds.BACKLOG_POINTER, TourAnchorIds.TODAY_LIST),
        title = "Focus, and the Ring",
        body = "A session counts upward against your estimate; the screen color follows the state. Calm while you work, warm coral if you run over. No alarms — returning is always supported.",
        narration = "When you start a session you enter Focus. The ring counts upward against your estimate, and the whole screen’s colour follows the state — calm while you work, a warm coral if you run past the estimate. Never an alarm. This is where execution actually happens.",
        more = "Three looks — Ambient, Cockpit, and Monk — let you match the focus screen to how your brain settles.",
        primary = "Enter Focus",
    ),
    TourStep(
        id = "capture", stage = "Focus", view = TourView.FOCUS,
        target = TourAnchorIds.FOCUS_BEGIN,
        fallbacks = listOf(TourAnchorIds.START_NEXT, TourAnchorIds.BACKLOG_POINTER, TourAnchorIds.TODAY_LIST),
        title = "Capture without leaving",
        body = "A stray thought mid-session? Press C or tap the mic and it’s saved — “add washing liquid to Groceries” — linked to this session, without breaking your focus.",
        narration = "While focusing, thoughts will surface. Don’t chase them. Press C, or tap the microphone, and Unstuck saves it — say, add washing liquid to groceries — linked to this session. You stay in focus; the thought is safe.",
        more = "Every capture lands in your Captures inbox, still linked to the task or session it came from, so nothing gets lost.",
        primary = "Enter Focus",
    ),
    TourStep(
        id = "reentry", stage = "Recovery", view = TourView.TODAY, target = TourAnchorIds.ASSISTANT_LAUNCH,
        title = "Interruption, then re-entry",
        body = "Life interrupts. When you come back, Unstuck rebuilds the context — the task, time spent, your captures, and the next action — so you don’t start from scratch. Returning is a feature, not a failure.",
        narration = "You’ll get interrupted — that’s expected. When you return, ask the Assistant “what was I doing?” and Unstuck rebuilds the picture: the task, the time you spent, the thoughts you captured, and your next physical action. You don’t reconstruct anything. Returning is part of the design.",
        more = "Pausing asks for an optional reason, and you can Save for later instead of ending — so the thread is never dropped.",
        primary = "Continue",
    ),
    TourStep(
        id = "notifications", stage = "Trust", view = TourView.SETTINGS, section = "Notifications", target = TourAnchorIds.NOTIF_BODY,
        title = "You set how present it is",
        body = "Choose Calm, Balanced, or Coach — how much support Unstuck offers. Everything is quiet by default and nothing shouts. You can change this anytime.",
        narration = "One of the most important settings: how present Unstuck is. Calm gives you only the essentials. Balanced adds useful prompts without noise. Coach offers more active support. It’s your call, and you can change it whenever you like.",
        more = "Notifications are in-app and quiet by default — no red badges unless you ask for them.",
        primary = "Continue",
    ),
    TourStep(
        id = "finish", stage = "Begin", view = TourView.TODAY,
        target = TourAnchorIds.START_NEXT,
        fallbacks = listOf(TourAnchorIds.BACKLOG_POINTER, TourAnchorIds.TODAY_LIST),
        title = "You’re ready to begin",
        body = "That’s the loop: Today narrows things down, the first physical action gets you moving, Focus sustains it, and the Assistant helps when you’re stuck. Pick one real next step.",
        narration = "That’s the core loop. Today narrows things down. The first physical action gets you moving. Focus sustains it. And the Assistant is there when you get stuck. You don’t need to learn everything today — just choose one real next step, and begin.",
        more = "You can reopen this tour anytime from Settings → Account. Nothing you skip is lost.",
        primary = "Begin",
    ),
)

/** Look up a shared essential step by id (throws at first access if the id
 *  ever drifts — a broken full tour should fail loudly, not render holes). */
private fun essential(id: String): TourStep =
    ESSENTIAL_STEPS.firstOrNull { it.id == id } ?: throw IllegalStateException("tour: missing essential step '$id'")

val FULL_STEPS: List<TourStep> = ESSENTIAL_STEPS.take(3) + listOf(
    TourStep(
        id = "calendar", stage = "Calendar", view = TourView.CALENDAR, target = null,
        title = "A realistic day",
        body = "Today, Week, and Month views — with a current-time line and a light focus heatmap. Drag tasks from the unscheduled tray onto real free gaps, or let Auto-sequence place them for you.",
        narration = "The Calendar gives you Today, Week, and Month. Drag tasks from the unscheduled tray onto genuine free gaps, or let Auto-sequence fit them into your open time. It reads your Google Calendar so the plan reflects a real day.",
        primary = "Continue",
    ),
    TourStep(
        id = "captures", stage = "Capture", view = TourView.CAPTURES, target = null,
        title = "Nothing gets lost",
        body = "Every captured thought lands here, still linked to where it came from. Promote it to a task, open it, or archive it — on your schedule, without pressure.",
        narration = "The Captures inbox holds every thought you dumped, still linked to its task or session. Triage when you have bandwidth — promote to a task, open, or archive.",
        primary = "Continue",
    ),
    TourStep(
        id = "collections", stage = "Collections", view = TourView.COLLECTIONS, target = null,
        title = "Things to keep, not do",
        body = "Collections are calm shelves — groceries, books, quotes — that never nag. Distinct from Tasks, which represent intended action. Share a list and updates appear live.",
        narration = "Collections are for things to keep and remember without pressure — groceries, books, quotes. They’re deliberately separate from Tasks, which represent action. Share a list and edits appear instantly for everyone on it.",
        primary = "Continue",
    ),
    essential("assistant"),
    essential("focus"),
    essential("reentry"),
    TourStep(
        id = "sharing", stage = "Together", view = TourView.TASKS, target = null,
        title = "Trusted, by invite",
        body = "Your Trusted Circle is small and private — nothing is shared by default. Share a task as View (follow along), Partner (either can complete, focus together), or Assign (it becomes theirs).",
        narration = "Sharing is invite-only and private by default. When you share a task you choose the level: View to follow along, Partner so either of you can complete it and focus together, or Assign to hand it over entirely. A shared session runs one timer both people can join.",
        primary = "Continue",
    ),
    TourStep(
        id = "insights", stage = "Insights", view = TourView.INSIGHTS, target = null,
        title = "Observations, not scores",
        body = "Insights show useful patterns — estimate accuracy, what keeps slipping, when you focus best. No productivity grade, ever.",
        narration = "Insights offer observations, never a score. Estimate accuracy, work that keeps slipping, the times of day you focus best. Useful from your very first session — and never a grade.",
        primary = "Continue",
    ),
    essential("notifications"),
    TourStep(
        id = "personalization", stage = "Personalize", view = TourView.SETTINGS, section = "Interface", target = null,
        title = "Make it yours",
        body = "Theme, accent, density, and text size; focus defaults; your areas and tags. Adjust what helps, ignore the rest.",
        narration = "Personalization covers appearance — theme, accent, density, text size — plus your focus defaults and how you manage areas and tags. Change what helps you; leave the rest.",
        primary = "Continue",
    ),
    essential("finish"),
)

fun stepsForMode(mode: TourMode): List<TourStep> =
    if (mode == TourMode.FULL) FULL_STEPS else ESSENTIAL_STEPS

/* ============================================================
 * Canned brief answers for "Ask a question" — the INSTANT fallback
 * when the real-assistant round-trip (TourAsk.kt) errors, times
 * out, is offline, or replies with tool calls only. Verbatim port.
 * ============================================================ */
data class TourQAEntry(val m: Regex, val a: String)

val TOUR_QA: List<TourQAEntry> = listOf(
    TourQAEntry(Regex("different|task manager|why unstuck", RegexOption.IGNORE_CASE), "A planner assumes deciding is the hard part. Unstuck assumes you already know — and helps with the part that actually breaks: starting, sustaining, and returning."),
    TourQAEntry(Regex("usable time", RegexOption.IGNORE_CASE), "The focus time you realistically have left today, after meetings and fragmentation are subtracted. Suggestions are grounded in it."),
    TourQAEntry(Regex("collections.*separate|separate.*collections|why.*collections", RegexOption.IGNORE_CASE), "Tasks are intended actions — they can nag. Collections are things to keep and remember, and never appear in Today."),
    TourQAEntry(Regex("assistant.*do|can the assistant|do this for me", RegexOption.IGNORE_CASE), "Yes — it creates, schedules, and updates real tasks and lists. It confirms before any high-impact change."),
    TourQAEntry(Regex("miss.*session|what happens if i miss", RegexOption.IGNORE_CASE), "Nothing bad. There’s no penalty or streak. When you come back, re-entry rebuilds the context for you."),
    TourQAEntry(Regex("shared focus|focus.*work.*together", RegexOption.IGNORE_CASE), "One timer per shared task. Either person can start; the other joins mid-session. Either can pause, extend, or finish, with clear labels like “Paused by Sam”."),
    TourQAEntry(Regex("who can see|my data|privacy", RegexOption.IGNORE_CASE), "Nothing is shared by default. You share per-task, per-person, and can revoke it. Your Trusted Circle is invite-only."),
    TourQAEntry(Regex("partner.*assign|difference.*partner", RegexOption.IGNORE_CASE), "Partner: either of you can complete it and focus together. Assign: it becomes their task entirely."),
    // ANDROID LOCALIZATION (everything else verbatim): the presence control
    // (Calm/Balanced/Coach) lives in Settings → Focus on Android.
    TourQAEntry(Regex("notifications off|turn.*off|can i turn", RegexOption.IGNORE_CASE), "Yes. Calm mode keeps only essentials, and you can adjust it anytime in Settings → Focus."),
    TourQAEntry(Regex("offline|lose signal", RegexOption.IGNORE_CASE), "Unstuck works offline. Your changes sync automatically when you reconnect."),
    // ANDROID LOCALIZATION: the Settings row is labelled "Product tour".
    TourQAEntry(Regex("restart|tour again|find the tour", RegexOption.IGNORE_CASE), "Reopen it anytime from Settings → Account → Product tour."),
)

const val TOUR_FALLBACK_ANSWER =
    "Good question — the short version: Unstuck reduces the friction of starting and returning. Ask the Assistant anytime for specifics."

fun answerFor(q: String): String =
    TOUR_QA.firstOrNull { it.m.containsMatchIn(q) }?.a ?: TOUR_FALLBACK_ANSWER

/* ============================================================
 * Persistence — SharedPreferences with the same field semantics
 * as the web's unstuck.tour.v1 localStorage blob.
 * ============================================================ */
data class TourState(
    /** Set once the user has begun (or explicitly declined) the tour. */
    val started: Boolean = false,
    val done: Boolean = false,
    val paused: Boolean = false,
    /** Armed when onboarding finishes — gates the one-time auto-welcome so
     *  EXISTING accounts are never ambushed by the tour. */
    val eligible: Boolean = false,
    val mode: TourMode? = null,
    val mediaMode: TourMediaMode = TourMediaMode.READ,
    val speed: Float = 1f,
    val index: Int = 0,
)

class TourStateStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("unstuck.tour", Context.MODE_PRIVATE)

    fun load(): TourState = TourState(
        started = p.getBoolean("started", false),
        done = p.getBoolean("done", false),
        paused = p.getBoolean("paused", false),
        eligible = p.getBoolean("eligible", false),
        mode = p.getString("mode", null)?.let { runCatching { TourMode.valueOf(it) }.getOrNull() },
        mediaMode = p.getString("mediaMode", null)?.let { runCatching { TourMediaMode.valueOf(it) }.getOrNull() } ?: TourMediaMode.READ,
        speed = p.getFloat("speed", 1f),
        index = p.getInt("index", 0),
    )

    fun save(s: TourState) {
        p.edit()
            .putBoolean("started", s.started)
            .putBoolean("done", s.done)
            .putBoolean("paused", s.paused)
            .putBoolean("eligible", s.eligible)
            .apply { if (s.mode == null) remove("mode") else putString("mode", s.mode.name) }
            .putString("mediaMode", s.mediaMode.name)
            .putFloat("speed", s.speed)
            .putInt("index", s.index)
            .apply()
    }

    /** Load–modify–save in one call (the web saveTour(patch) equivalent). */
    fun patch(block: (TourState) -> TourState): TourState {
        val next = block(load())
        save(next)
        return next
    }

    /** Wipe everything — called on sign-out (MainActivity, next to
     *  NotificationLog.clear + settings.clearUserContent) so tour state is
     *  effectively per-account: a switch never ambushes an existing account
     *  with a stranger's paused run, and never eats a new user's one-time
     *  offer (their own onboarding re-arms `eligible`). */
    fun clear() { p.edit().clear().apply() }
}

/* ============================================================
 * Events — the Android stand-in for the web's window events.
 * Settings → Account → "Product tour" emits a restart; TourHost
 * collects it wherever it's mounted.
 * ============================================================ */
object TourEvents {
    private val _restarts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restarts: SharedFlow<Unit> = _restarts.asSharedFlow()
    fun requestRestart() { _restarts.tryEmit(Unit) }
}

/* ============================================================
 * Pure phase logic — kept out of the orchestrator so the resume /
 * auto-show rules are unit-testable. Verbatim web semantics.
 * ============================================================ */
enum class TourEntryPhase { HIDDEN, PAUSED, WELCOME }

/** Where the tour surfaces on app load. A finished (or previously started /
 *  declined) tour stays hidden; a paused run offers the resume card; a fresh
 *  account that just completed onboarding (eligible) gets the one-time
 *  welcome. Everyone else — existing accounts — sees nothing unless they
 *  open the tour explicitly. */
fun initialPhase(state: TourState): TourEntryPhase = when {
    state.done -> TourEntryPhase.HIDDEN
    state.paused && state.mode != null -> TourEntryPhase.PAUSED
    state.started -> TourEntryPhase.HIDDEN
    state.eligible -> TourEntryPhase.WELCOME
    else -> TourEntryPhase.HIDDEN
}

sealed interface ResumeDecision {
    data class Running(val index: Int) : ResumeDecision
    data object Welcome : ResumeDecision
}

/** Explicit open (Settings → Account → "Product tour"): a paused/UNFINISHED
 *  run offers the resume card at its saved step (never an unconditional wipe —
 *  Settings must be able to rescue a paused or stranded run); a FINISHED tour
 *  (or a fresh account) resets and shows the welcome. */
fun resumeDecision(state: TourState): ResumeDecision =
    if (state.started && !state.done) ResumeDecision.Running(state.index) else ResumeDecision.Welcome

/** What the DORMANT (dismissed) quiet-Today re-check may surface: the one-time
 *  welcome (completeOnboarding's async `eligible` arming, or a soft-dismissed
 *  welcome card) — or a PAUSED run's resume card, so a paused tour is
 *  reachable again without waiting for process death. Null = stay dormant. */
fun dormantResurface(state: TourState): TourEntryPhase? =
    when (initialPhase(state)) {
        TourEntryPhase.WELCOME -> TourEntryPhase.WELCOME
        TourEntryPhase.PAUSED -> TourEntryPhase.PAUSED
        TourEntryPhase.HIDDEN -> null
    }

/* ============================================================
 * Pure panel-placement rules (non-negotiable #1): the panel docks
 * OPPOSITE the target vertically and NEVER covers the ring.
 * ============================================================ */
enum class PanelDock { TOP, BOTTOM }

/** Target center in the TOP half → panel at the BOTTOM; bottom half → TOP;
 *  no target → BOTTOM. Re-evaluated on every rect change. */
fun panelDock(targetCenterY: Float?, screenHeightPx: Float): PanelDock = when {
    targetCenterY == null -> PanelDock.BOTTOM
    targetCenterY < screenHeightPx / 2f -> PanelDock.BOTTOM
    else -> PanelDock.TOP
}

/** Vertical space available to the panel on its dock side, outside the ring
 *  (ringTop/ringBottom include the spotlight padding). The panel is hard-
 *  capped to this, so it can never intersect the ring. */
fun panelAvailablePx(ringTop: Float?, ringBottom: Float?, screenHeightPx: Float, dock: PanelDock): Float =
    when {
        ringTop == null || ringBottom == null -> screenHeightPx
        dock == PanelDock.BOTTOM -> (screenHeightPx - ringBottom).coerceAtLeast(0f)
        else -> ringTop.coerceAtLeast(0f)
    }

/** A target so tall it spans both halves leaves little room on either side —
 *  collapse the panel to title + controls rather than cover the ring. */
fun panelCollapsed(availablePx: Float, collapseThresholdPx: Float): Boolean =
    availablePx < collapseThresholdPx

/** The host's full placement decision for one frame. [availablePx] is the
 *  hard height cap the panel is laid out with. */
data class PanelPlacement(val dock: PanelDock, val availablePx: Float, val collapsed: Boolean)

/**
 * Non-negotiable #1, inset-aware. The panel is laid out INSIDE the system-bar
 * (+ IME) padding plus a margin, but the ring rect is in raw window coords —
 * so the dock-side insets are subtracted from the raw outside-the-ring space
 * BEFORE the collapse/cap decision (otherwise the cap can overlap the ring by
 * the inset height). Rules, in order:
 *
 *  1. Preferred dock = opposite the target (no target → bottom); while the
 *     Ask field is focused with the keyboard up, prefer TOP and suppress the
 *     collapse so the field + thread never hide (iOS parity).
 *  2. If the preferred side fits the expanded panel → expanded there.
 *  3. If it at least fits the collapsed minimum → collapsed there.
 *  4. Otherwise FLIP to the other side (expanded/collapsed by its space) —
 *     never cap over the ring just because the preferred side is tight.
 *  5. Only when NEITHER side fits the collapsed minimum (a ring nearly the
 *     whole usable screen) take the roomier side with the minimum as a
 *     usability floor — the single, physically-unavoidable overlap case.
 *
 * [bottomInsetPx] must already include the IME while it's up, so this whole
 * decision re-runs as the keyboard shows/hides.
 */
fun panelPlacement(
    ringTop: Float?,
    ringBottom: Float?,
    screenHeightPx: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    marginPx: Float,
    expandedHeightPx: Float,
    collapsedMinPx: Float,
    askFocused: Boolean = false,
): PanelPlacement {
    fun availOn(dock: PanelDock): Float {
        val raw = panelAvailablePx(ringTop, ringBottom, screenHeightPx, dock)
        val inset = if (dock == PanelDock.TOP) topInsetPx else bottomInsetPx
        return (raw - inset - marginPx * 2).coerceAtLeast(0f)
    }
    val hasRing = ringTop != null && ringBottom != null
    val preferred = when {
        askFocused -> PanelDock.TOP
        !hasRing -> PanelDock.BOTTOM
        else -> panelDock((ringTop!! + ringBottom!!) / 2f, screenHeightPx)
    }
    val avail = availOn(preferred)
    // Ask-focused: TOP + never collapsed (the field lives in the body). The
    // floor may overlap a top-docked ring in the extreme — but the keyboard is
    // up and the user is typing; geometry restores the moment focus/IME drop.
    if (askFocused) return PanelPlacement(PanelDock.TOP, maxOf(avail, collapsedMinPx), collapsed = false)
    if (!hasRing) return PanelPlacement(preferred, avail, collapsed = false)
    if (avail >= collapsedMinPx) return PanelPlacement(preferred, avail, collapsed = avail < expandedHeightPx)
    val other = if (preferred == PanelDock.TOP) PanelDock.BOTTOM else PanelDock.TOP
    val otherAvail = availOn(other)
    if (otherAvail >= collapsedMinPx) return PanelPlacement(other, otherAvail, collapsed = otherAvail < expandedHeightPx)
    // Neither side fits even the collapsed minimum — roomier side + floor.
    return if (otherAvail > avail) PanelPlacement(other, collapsedMinPx, collapsed = true)
    else PanelPlacement(preferred, collapsedMinPx, collapsed = true)
}

/* ============================================================
 * Listen — speed cycle + audio resource naming (pure, testable).
 * ============================================================ */
/** 0.75 → 1 → 1.25 → 1.5 → 1.75 → 2 → 0.75 (web cycleSpeed). */
fun nextTourSpeed(speed: Float): Float =
    if (speed >= 2f) 0.75f else ((speed + 0.25f) * 100).toInt() / 100f

/** Step id → bundled raw resource name ("first-action" → "tour_first_action"). */
fun tourAudioResName(stepId: String): String = "tour_" + stepId.replace('-', '_')
