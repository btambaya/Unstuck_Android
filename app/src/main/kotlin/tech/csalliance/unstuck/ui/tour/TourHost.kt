package tech.csalliance.unstuck.ui.tour

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.settings.SettingsSection

// TourHost — the guided-tour orchestrator, mounted ONCE at the TOP of
// MainScaffold's root Box so it overlays every screen (tabs, pushed routes,
// even the Focus takeover). Phase machine (web onboarding-tour.tsx parity):
// welcome | running | paused (resume card) | dismissed | done — persisted
// through TourData's store (started/paused/done/index/mode/mediaMode/speed).
//
// Launch triggers:
//  • One-time auto-welcome on Today for accounts that finish onboarding AFTER
//    this shipped (TourState.eligible — armed in AppViewModel.completeOnboarding).
//    Existing accounts are never ambushed.
//  • TourEvents.requestRestart() — Settings → Account → "Product tour".
//
// FOCUS STEPS (round 2): FocusScreen mints a real session in a LaunchedEffect
// on entry, so the focus + capture steps NEVER navigate there — the tour
// renders its own DEMO focus surface (TourDemoFocus.kt) over the scrim and
// spotlights targets INSIDE it (ring / capture hint). Zero sessions.

/** How the tour drives the real scaffold — MainScaffold hands these in as
 *  closures over its own tab/stack/sheet state. */
class TourNav(
    /** Select a bottom tab + clear every overlay/sheet (incl. the focus view —
     *  leaving focus keeps a live session running, so this is non-destructive). */
    val resetToTab: (String) -> Unit,
    /** Tasks tab + push the task-detail route (existing detail mechanics). */
    val openTaskDetail: (String) -> Unit,
    val openInbox: () -> Unit,
    val openInsights: () -> Unit,
    val openSettingsSection: (SettingsSection) -> Unit,
    /** Open the Assistant sheet (the assistant step's two-tap primary). */
    val openAssistant: () -> Unit,
    /** A real task to present on the first-action step; null = empty account
     *  (the step's New-task fallback anchor takes over). */
    val firstTaskId: () -> String?,
)

/** Map the web section names onto Android's settings screens. The notification
 *  presence control (Calm/Balanced/Coach) lives in Settings → Focus. */
internal fun tourSettingsSection(section: String?): SettingsSection = when (section) {
    "Notifications" -> SettingsSection.FOCUS
    "Interface" -> SettingsSection.INTERFACE
    else -> SettingsSection.ACCOUNT
}

/** Per-step navigation (web routeFor + onShow-nudges, Android view mapping).
 *  Pure dispatch over [TourNav] so the focus deviation is unit-testable. */
internal fun tourNavigate(step: TourStep, nav: TourNav) {
    when (step.view) {
        TourView.TODAY -> nav.resetToTab("today")
        // NEVER the focus screen — it mints a session on entry (see header).
        TourView.FOCUS -> nav.resetToTab("today")
        TourView.TASKS ->
            if (step.id == "first-action") {
                val id = nav.firstTaskId()
                if (id != null) nav.openTaskDetail(id) else nav.resetToTab("tasks")
            } else {
                nav.resetToTab("tasks")
            }
        TourView.CALENDAR -> nav.resetToTab("calendar")
        TourView.CAPTURES -> nav.openInbox()
        TourView.COLLECTIONS -> nav.resetToTab("lists")
        TourView.INSIGHTS -> nav.openInsights()
        TourView.SETTINGS -> nav.openSettingsSection(tourSettingsSection(step.section))
    }
}

/** The primary CTA label — two-tap on the assistant step (iOS parity): the
 *  first tap shows the step's own label ("Open the Assistant") and opens the
 *  bubble; after that the button reads Continue. The last step always shows
 *  its own label ("Begin"). */
internal fun tourPrimaryLabel(step: TourStep, index: Int, total: Int, assistantOpenedForStep: String?): String = when {
    index >= total - 1 -> step.primary
    step.onShow == TourSideEffect.OPEN_ASSISTANT && assistantOpenedForStep != step.id -> step.primary
    else -> "Continue"
}

private enum class TourPhase { WELCOME, RUNNING, PAUSED, DISMISSED, DONE }

private fun entryToPhase(entry: TourEntryPhase): TourPhase = when (entry) {
    TourEntryPhase.WELCOME -> TourPhase.WELCOME
    TourEntryPhase.PAUSED -> TourPhase.PAUSED
    TourEntryPhase.HIDDEN -> TourPhase.DISMISSED
}

@Composable
fun TourHost(
    vm: AppViewModel,
    currentTab: String,
    /** A pushed route / sheet / focus overlay is up — the auto-shown welcome
     *  and resume cards wait for a quiet Today (never ambush mid-task). */
    overlayActive: Boolean,
    /** LIVE settings-surface state (the nav stack contains Settings) — the
     *  settings steps' lockdown exemption follows THIS, not the step id: the
     *  surface is interactive while open; the moment the user closes it
     *  mid-step the lockdown re-applies (panel still reachable — never a
     *  full-app unlock). */
    settingsSurfaceOpen: Boolean,
    /** The Focus takeover is up ABOVE the anchored surface (same window,
     *  never tour-driven — e.g. a notification deep link or a partner-started
     *  shared session). While RUNNING the spotlight degrades to the whisper
     *  scrim + ONE full-screen blocker so no stale hole leaks into it. */
    focusOverlayActive: Boolean,
    nav: TourNav,
) {
    val context = LocalContext.current
    val store = remember { TourStateStore(context) }
    val boot = remember { store.load() }
    val settings by vm.settings.collectAsStateWithLifecycle()

    var phase by remember { mutableStateOf(entryToPhase(initialPhase(boot))) }
    var mode by remember { mutableStateOf(boot.mode ?: TourMode.ESSENTIAL) }
    var mediaMode by remember { mutableStateOf(boot.mediaMode) }
    var speed by remember { mutableFloatStateOf(boot.speed) }
    var index by remember { mutableIntStateOf(boot.index) }
    // Explicit open (Settings restart) bypasses the quiet-Today gate.
    var explicit by remember { mutableStateOf(false) }
    // Two-tap assistant step: which step id already opened the bubble.
    var assistantOpenedForStep by remember { mutableStateOf<String?>(null) }
    // The panel's measured EXPANDED height — the collapse decision compares
    // real geometry, and keeps the last expanded measure while collapsed so
    // the decision can't oscillate (iOS parity).
    var expandedHeightPx by remember { mutableIntStateOf(0) }
    // The Ask field has focus (panel reports it): with the keyboard up, the
    // panel prefers dock=TOP and suppresses the collapse so the field + thread
    // are never hidden (iOS parity).
    var askFocused by remember { mutableStateOf(false) }
    // The welcome card was soft-dismissed by the back gesture once already —
    // the quiet-Today loop re-offers it ONCE; a second back declines for good.
    var welcomeSoftDismissed by remember { mutableStateOf(false) }
    // The PAUSED resume card was surfaced (or back-dismissed) once already
    // this process — the dormant loop's resurface is ONE-SHOT (mirror of
    // welcomeSoftDismissed), so back-dismissing the card never re-pops it.
    var pausedResurfaced by remember { mutableStateOf(false) }
    // Round-2 #5: the inline footer pause-confirm is armed (Pause tap or back
    // gesture). Reset on every step (re)presentation.
    var pauseConfirmArmed by remember { mutableStateOf(false) }
    // Round-3: WHICH step presentation opened the DEMO capture sheet (the
    // assistantOpenedForStep pattern) — visibility derives from it via
    // tourDemoSheetVisible, so a step change / pause / exit closes the sheet
    // structurally; the step effect below also clears it explicitly so a
    // RETURN to the capture step never resurrects a stale sheet.
    var demoSheetForStep by remember { mutableStateOf<String?>(null) }
    // Bumped on each demo save — TourDemoFocus flashes "Saved — demo".
    var demoSavedEpoch by remember { mutableIntStateOf(0) }
    // Bumped when the "Resume tour" chip is ✕-dismissed so the dormant
    // snapshot below re-reads the store.
    var chipEpoch by remember { mutableIntStateOf(0) }
    // Bumped on ON_START while RUNNING: MainScaffold's ON_STOP reset clears
    // tab/stack/sheets (deliberately — do not skip it) while the tour keeps
    // its phase, so on foreground the panel would narrate the old step over
    // Today. The epoch keys the navigation effect below, re-presenting the
    // current step's surface on every return to the foreground.
    var navEpoch by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (phase == TourPhase.RUNNING) navEpoch += 1
    }

    val steps = stepsForMode(mode)
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val running = phase == TourPhase.RUNNING

    val audio = remember { TourAudioController(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { audio.release() } }

    // ── persistence-mirroring actions (web saveTour patch parity) ──────────
    fun begin(m: TourMode) {
        mode = m; index = 0; assistantOpenedForStep = null
        // chipDismissed resets: a FRESH run re-earns its resume chip.
        store.patch { it.copy(mode = m, started = true, index = 0, paused = false, chipDismissed = false) }
        phase = TourPhase.RUNNING
    }
    fun advance() {
        if (index >= steps.size - 1) {
            store.patch { it.copy(done = true, paused = false) }
            phase = TourPhase.DONE
        } else {
            index += 1
            store.patch { it.copy(index = index) }
        }
    }
    fun backStep() {
        index = (index - 1).coerceAtLeast(0)
        store.patch { it.copy(index = index) }
    }
    // Confirmed pause (round-2 #5): the tour hides preserving progress, and
    // the floating "Resume tour" chip (DISMISSED branch below) takes over as
    // the primary re-entry.
    fun pause() {
        pauseConfirmArmed = false
        store.patch { it.copy(paused = true, index = index, mode = mode) }
        phase = TourPhase.DISMISSED
    }
    fun exit() {
        store.patch { it.copy(done = true, paused = false) }
        phase = TourPhase.DONE
    }
    fun declineWelcome() {
        store.patch { it.copy(done = true, started = true) }
        phase = TourPhase.DONE
    }
    fun explore() {
        store.patch { it.copy(done = true, started = true) }
        phase = TourPhase.DONE
        nav.openAssistant()
    }
    fun setMediaMode(m: TourMediaMode) {
        mediaMode = m
        store.patch { it.copy(mediaMode = m) }
        if (running) {
            if (m == TourMediaMode.LISTEN) audio.load(step.id, step.narration, autoPlay = true, speed = speed)
            else audio.release()   // Read = clean stop + reset (web parity)
        }
    }
    fun cycleSpeed() {
        speed = nextTourSpeed(speed)
        store.patch { it.copy(speed = speed) }
        audio.setSpeed(speed)   // live while playing; else applies on next play
    }
    fun primaryAction() {
        if (step.onShow == TourSideEffect.OPEN_ASSISTANT && assistantOpenedForStep != step.id) {
            assistantOpenedForStep = step.id
            nav.openAssistant()
            return
        }
        advance()
    }

    // Settings → Account → "Product tour": routed through resumeDecision —
    // a paused/unfinished run offers the RESUME card at its saved step (an
    // unconditional wipe here would eat an in-progress run); a finished tour
    // or a fresh account resets the run flags and shows the welcome.
    LaunchedEffect(Unit) {
        TourEvents.restarts.collect {
            val s = store.load()
            assistantOpenedForStep = null
            explicit = true
            when (val d = resumeDecision(s)) {
                is ResumeDecision.Running -> {
                    mode = s.mode ?: TourMode.ESSENTIAL
                    index = d.index.coerceIn(0, stepsForMode(s.mode ?: TourMode.ESSENTIAL).lastIndex)
                    // Mirror the card into the store so process death while
                    // it's up still boots into the resume offer.
                    store.patch { it.copy(paused = true, index = index) }
                    phase = TourPhase.PAUSED
                }
                ResumeDecision.Welcome -> {
                    store.patch { it.copy(done = false, paused = false, started = false) }
                    index = 0
                    phase = TourPhase.WELCOME
                }
            }
            nav.resetToTab("today")
        }
    }

    // Dormant re-check on a quiet Today — a low-frequency INDEFINITE loop (the
    // effect cancels whenever phase/tab/overlay change): surfaces the one-time
    // WELCOME (completeOnboarding arms `eligible` in an async write that may
    // land after this host mounted, or a back-gesture soft-dismissed the card)
    // AND a PAUSED run's resume card. The card is the SECONDARY re-entry:
    // while the "Resume tour" chip is visible dormantResurface stays null (the
    // modal must never fight the chip every 5s — only a ✕-dismissed chip
    // unlocks it), and it's ONE-SHOT per process (pausedResurfaced, mirroring
    // welcomeSoftDismissed) so back-dismissing it never re-pops. Delay-first,
    // so a just-paused / just-dismissed card never bounces straight back.
    // begin/decline/finish persist started/done, flipping the store to HIDDEN,
    // so those can never re-fire.
    LaunchedEffect(phase, currentTab, overlayActive) {
        if (phase != TourPhase.DISMISSED || currentTab != "today" || overlayActive) return@LaunchedEffect
        while (true) {
            delay(5_000)
            val s = store.load()
            when (dormantResurface(s, pausedResurfaced)) {
                TourEntryPhase.WELCOME -> { phase = TourPhase.WELCOME; return@LaunchedEffect }
                TourEntryPhase.PAUSED -> {
                    pausedResurfaced = true   // one-shot per process
                    mode = s.mode ?: TourMode.ESSENTIAL
                    index = s.index.coerceIn(0, stepsForMode(s.mode ?: TourMode.ESSENTIAL).lastIndex)
                    phase = TourPhase.PAUSED
                    return@LaunchedEffect
                }
                else -> {}   // stay dormant, keep listening
            }
        }
    }

    // Drive the app for the current step: navigate + side effects, persist
    // progress, (re)load narration. Re-runs on resume as well, re-presenting
    // the step's surface — and on navEpoch (foreground return), because
    // MainScaffold's ON_STOP reset put the app back on Today while the tour
    // kept RUNNING on its step.
    LaunchedEffect(running, step.id, mode, navEpoch) {
        pauseConfirmArmed = false   // a (re)presented step starts un-armed
        demoSheetForStep = null     // any step (re)presentation / pause / exit closes the demo sheet
        if (!running) {
            audio.release()   // pausing/exiting stops narration immediately
            return@LaunchedEffect
        }
        askFocused = false   // a re-presented step starts with the keyboard down
        assistantOpenedForStep = null   // re-arm the two-tap on every (re)entry
        tourNavigate(step, nav)
        store.patch { it.copy(started = true, mode = mode, mediaMode = mediaMode, index = index) }
        // Auto-play each NEW step in Listen mode; Read never touches audio.
        if (mediaMode == TourMediaMode.LISTEN) audio.load(step.id, step.narration, autoPlay = true, speed = speed)
        else audio.release()
    }

    // Light ticker for the narration progress bar.
    LaunchedEffect(audio.playing) {
        while (audio.playing) {
            audio.tick()
            delay(150)
        }
    }

    // The auto-shown cards wait for a quiet Today; explicit opens show anywhere.
    val cardGate = explicit || (currentTab == "today" && !overlayActive)

    // Back gesture: running → the inline PAUSE CONFIRM (round-2 #5 — never
    // straight to the app: the first back arms the footer confirm, a second
    // back confirms the pause); welcome → SOFT dismiss (phase only, store
    // untouched, so `eligible` survives and the quiet-Today loop re-offers
    // ONCE — a second back declines permanently; the explicit "Not now"
    // action stays permanent either way); resume card → dismiss for now
    // (stays paused, the chip + quiet-Today loop / next launch offer again).
    BackHandler(enabled = running) {
        // The DEMO capture sheet is the topmost modal — back closes ONLY it.
        when (tourBackWhileRunning(pauseConfirmArmed, demoSheetOpen = tourDemoSheetVisible(demoSheetForStep, step, running))) {
            TourBackAction.CLOSE_DEMO_SHEET -> demoSheetForStep = null
            TourBackAction.ARM_PAUSE_CONFIRM -> pauseConfirmArmed = true
            TourBackAction.CONFIRM_PAUSE -> pause()
        }
    }
    BackHandler(enabled = phase == TourPhase.WELCOME && cardGate) {
        if (welcomeSoftDismissed) {
            declineWelcome()
        } else {
            welcomeSoftDismissed = true
            explicit = false
            phase = TourPhase.DISMISSED
        }
    }
    BackHandler(enabled = phase == TourPhase.PAUSED && cardGate) {
        // Back-dismissing the resume card consumes the process's one-shot
        // resurface too — the chip (or Settings / next launch) takes over.
        explicit = false
        pausedResurfaced = true
        phase = TourPhase.DISMISSED
    }

    when (phase) {
        TourPhase.WELCOME -> if (cardGate) {
            TourWelcomeCard(
                mediaMode = mediaMode,
                onMediaMode = ::setMediaMode,
                onBegin = ::begin,
                onExplore = ::explore,
                onNotNow = ::declineWelcome,
            )
        }
        TourPhase.PAUSED -> if (cardGate) {
            TourResumeCard(
                step = step,
                onContinue = {
                    store.patch { it.copy(paused = false) }
                    phase = TourPhase.RUNNING
                },
                onStartOver = {
                    index = 0
                    // chipDismissed resets: a FRESH run re-earns its resume chip.
                    store.patch { it.copy(index = 0, paused = false, chipDismissed = false) }
                    phase = TourPhase.RUNNING
                },
                onNotNow = {
                    store.patch { it.copy(done = true, paused = false) }
                    phase = TourPhase.DONE
                },
            )
        }
        TourPhase.RUNNING -> {
            // The per-frame input policy: LIVE settings exemption + display-only
            // cutouts (interactive only on the assistant/reentry steps) + the
            // belt — a Focus takeover above the anchored surface degrades the
            // spotlight to the whisper scrim + ONE full-screen blocker (no
            // stale hole; only the panel stays interactive).
            val lockdown = tourLockdownPolicy(step, settingsOpen = settingsSurfaceOpen, overlayAboveTour = focusOverlayActive)
            val targetRect = if (lockdown.degradeToFullBlocker) null else TourAnchors.resolve(step.target, step.fallbacks)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val screenH = constraints.maxHeight.toFloat()
                // Round-2 #6: the focus/capture steps present the tour's own
                // DEMO focus surface (zero sessions, zero navigation) under
                // the spotlight/panel; its ring + capture hint register the
                // step's spotlight anchors. Round-3: on the capture step the
                // pill is LIVE (the step's cutout is interactive — the tap
                // falls through the spotlight hole INTO the demo, whose root
                // swallows everything else) and opens the DEMO capture sheet.
                if (tourStepShowsDemoFocus(step)) {
                    TourDemoFocus(
                        capturePillEnabled = tourDemoCapturePillEnabled(step),
                        onCapturePill = { demoSheetForStep = step.id },
                        savedEpoch = demoSavedEpoch,
                    )
                }
                // Round-2 #4: the dim panels consume input on all but a
                // LIVE-open settings surface; the cut-out passes touches only
                // on the assistant/reentry steps (display-only elsewhere).
                TourSpotlight(
                    targetRect,
                    reduceMotion = settings.reduceMotion,
                    consumeInput = lockdown.consumeInput,
                    cutoutInteractive = lockdown.cutoutInteractive,
                )

                // ── Non-negotiable #1: dock OPPOSITE the target, hard-capped to
                // the space outside the ring MINUS the dock-side insets (the
                // panel is laid out inside systemBars+IME padding, the ring is
                // in raw window coords). Tight side → collapse; even the
                // collapsed minimum not fitting → FLIP, never cap over the
                // ring. Reading WindowInsets.ime here re-runs the decision as
                // the keyboard shows/hides; an Ask-focused panel prefers TOP
                // with the collapse suppressed so the field never hides. ──
                val ringPadPx = with(density) { 14.dp.toPx() }   // 8dp pad + ring/glow
                val marginPx = with(density) { 12.dp.toPx() }
                val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
                // systemBarsPadding + imePadding below compose to max(bars, ime).
                val bottomInsetPx = maxOf(WindowInsets.systemBars.getBottom(density).toFloat(), imeBottomPx)
                val placement = panelPlacement(
                    ringTop = targetRect?.let { it.top - ringPadPx },
                    ringBottom = targetRect?.let { it.bottom + ringPadPx },
                    screenHeightPx = screenH,
                    topInsetPx = WindowInsets.systemBars.getTop(density).toFloat(),
                    bottomInsetPx = bottomInsetPx,
                    marginPx = marginPx,
                    expandedHeightPx = expandedHeightPx.takeIf { it > 0 }?.toFloat()
                        ?: with(density) { 360.dp.toPx() },
                    collapsedMinPx = with(density) { 150.dp.toPx() },
                    askFocused = askFocused && imeBottomPx > 0f,
                )
                val dock = placement.dock
                val collapsed = targetRect != null && placement.collapsed
                val capDp = with(density) { placement.availablePx.toDp() }

                Box(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = if (dock == PanelDock.TOP) Alignment.TopCenter else Alignment.BottomCenter,
                ) {
                    TourPanel(
                        step = step,
                        index = index.coerceIn(0, steps.lastIndex),
                        total = steps.size,
                        steps = steps,
                        mode = mode,
                        mediaMode = mediaMode,
                        onMediaMode = ::setMediaMode,
                        speed = speed,
                        onCycleSpeed = ::cycleSpeed,
                        audio = audio,
                        stepHasAudio = tourAudioRes(step.id) != 0,
                        collapsed = collapsed,
                        maxHeight = capDp,
                        onExpandedHeight = { expandedHeightPx = it },
                        onAskFocus = { askFocused = it },
                        askTransport = { msgs, s -> vm.tourAsk(msgs, s.id, s.title) },
                        primaryLabel = tourPrimaryLabel(step, index, steps.size, assistantOpenedForStep),
                        onPrimary = ::primaryAction,
                        onSkip = ::advance,
                        onBack = ::backStep,
                        pauseConfirmArmed = pauseConfirmArmed,
                        onRequestPause = { pauseConfirmArmed = true },
                        onConfirmPause = ::pause,
                        onKeepGoing = { pauseConfirmArmed = false },
                        onExit = ::exit,
                    )
                }

                // Round-3: the DEMO capture sheet — the TOPMOST layer of the
                // running tour (above the blockers AND the panel: a modal
                // moment within the tour's own composition, never a real
                // ModalBottomSheet). It carries its own imePadding so it rides
                // above the keyboard — the overlay stays un-padded by design —
                // and never touches askFocused. Saving persists NOTHING: it
                // bumps the demo's "Saved — demo" flash and closes.
                if (tourDemoSheetVisible(demoSheetForStep, step, running)) {
                    TourDemoCaptureSheet(
                        onSave = { demoSavedEpoch += 1; demoSheetForStep = null },
                        onDismiss = { demoSheetForStep = null },
                    )
                }
            }
        }
        TourPhase.DISMISSED -> {
            // Round-2 #5: the floating "Resume tour" chip — the PRIMARY
            // re-entry to an unfinished run (a confirmed pause, or a run
            // STRANDED by process death mid-run: at boot started && !paused &&
            // !done && !chipDismissed shows the chip, and a tap resumes at the
            // saved index). Shown across screens (deliberately NOT gated on a
            // quiet Today) until it's ✕-dismissed for good. The quiet-Today
            // resume-card loop above is the SECONDARY resurface — suppressed
            // while this chip shows, one-shot after its ✕.
            val chipState = remember(chipEpoch) { store.load() }
            if (showResumeChip(chipState)) {
                TourResumeChip(
                    onResume = {
                        val m = chipState.mode ?: TourMode.ESSENTIAL
                        mode = m
                        index = chipState.index.coerceIn(0, stepsForMode(m).lastIndex)
                        assistantOpenedForStep = null
                        store.patch { it.copy(paused = false) }
                        phase = TourPhase.RUNNING
                    },
                    onDismissForever = {
                        store.patch { it.copy(chipDismissed = true) }
                        chipEpoch += 1
                    },
                )
            }
        }
        TourPhase.DONE -> Unit
    }
}

/* ============================================================
 * Resume chip — a small floating pill (orbit mark + ✕), docked
 * bottom-corner across screens while a paused run has progress.
 * Tap = resume at the saved step; ✕ = gone for good (the
 * Settings → Account → Product tour path remains).
 * ============================================================ */
@Composable
private fun TourResumeChip(onResume: () -> Unit, onDismissForever: () -> Unit) {
    val c = UTheme.colors
    Box(
        Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, bottom = 74.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            Modifier
                .shadow(10.dp, RoundedCornerShape(999.dp))
                .clip(RoundedCornerShape(999.dp))
                .background(c.bg)
                .border(1.dp, c.line, RoundedCornerShape(999.dp))
                .padding(end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onResume)
                    .semantics { contentDescription = "Resume tour" }
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(c.primarySoft), contentAlignment = Alignment.Center) {
                    Orbit(size = 14)
                }
                Text("Resume tour", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink)
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDismissForever),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss resume chip", tint = c.ink3, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/* ============================================================
 * Welcome card — Essential / Full / Explore + Not now + Read↔Listen.
 * ============================================================ */
@Composable
private fun TourWelcomeCard(
    mediaMode: TourMediaMode,
    onMediaMode: (TourMediaMode) -> Unit,
    onBegin: (TourMode) -> Unit,
    onExplore: () -> Unit,
    onNotNow: () -> Unit,
) {
    val c = UTheme.colors
    TourCardShell {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.primarySoft), contentAlignment = Alignment.Center) {
                Orbit(size = 26)
            }
            Text("Welcome to Unstuck", style = UFont.serifItalic(30), color = c.ink, modifier = Modifier.padding(top = 16.dp))
            // Round-2 #1 copy — no more two-minute vs 3–5-min contradiction.
            Text(
                TOUR_WELCOME_INTRO,
                style = UFont.sans(14).copy(lineHeight = 22.sp), color = c.ink2, modifier = Modifier.padding(top = 8.dp),
            )
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TourModeRow(
                    title = "Essential tour",
                    sub = "The core loop: Today, first step, Focus, and the Assistant.",
                    meta = "3–5 min", recommended = true,
                ) { onBegin(TourMode.ESSENTIAL) }
                TourModeRow(
                    title = "Full guided tour",
                    sub = "Every major area, start to finish.",
                    meta = "10–15 min",
                ) { onBegin(TourMode.FULL) }
                TourModeRow(
                    title = "Explore with the Assistant",
                    sub = "No fixed path — ask about any screen you open.",
                    onClick = onExplore,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                TourGhostLink("Not now", onNotNow)
                Spacer(Modifier.weight(1f))
                TourMediaToggle(mediaMode, onMediaMode)
            }
            // Round-2 #1: quiet footer — the tour is always recoverable.
            Text(
                TOUR_WELCOME_FOOTER,
                style = UFont.sans(11).copy(lineHeight = 16.sp), color = c.ink4,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/* ============================================================
 * Resume card — Continue at the saved step / Start over / Not now.
 * ============================================================ */
@Composable
private fun TourResumeCard(step: TourStep, onContinue: () -> Unit, onStartOver: () -> Unit, onNotNow: () -> Unit) {
    val c = UTheme.colors
    TourCardShell(maxWidth = 400.dp) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.primarySoft), contentAlignment = Alignment.Center) {
                Orbit(size = 22)
            }
            Text("Continue your tour?", style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 14.dp))
            Text(
                buildAnnotatedString {
                    append("You stopped at ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink)) { append(step.stage) }
                    append(" — ${step.title.lowercase()}. Pick up where you left off, or start fresh.")
                },
                style = UFont.sans(13).copy(lineHeight = 20.sp), color = c.ink2, modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.ink).clickable(onClick = onContinue)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("Continue", style = UFont.sans(13, FontWeight.SemiBold), color = c.bg)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = c.bg, modifier = Modifier.size(13.dp))
                }
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.surface)
                        .border(1.dp, c.line2, RoundedCornerShape(999.dp))
                        .clickable(onClick = onStartOver)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text("Start over", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink) }
                Spacer(Modifier.weight(1f))
                TourGhostLink("Not now", onNotNow)
            }
        }
    }
}
