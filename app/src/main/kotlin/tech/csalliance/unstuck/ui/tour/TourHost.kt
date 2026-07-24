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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
// FOCUS DEVIATION (contract): FocusScreen mints a real session in a
// LaunchedEffect on entry, so the focus + capture steps NEVER navigate there —
// they stay on Today and ring the hero's begin-focus affordance.

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
        store.patch { it.copy(mode = m, started = true, index = 0, paused = false) }
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
    fun pause() {
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
            if (m == TourMediaMode.LISTEN) audio.load(step.id, autoPlay = true, speed = speed)
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
    // AND a PAUSED run's resume card (otherwise a paused tour is unreachable
    // until process death). Delay-first, so a just-paused / just-dismissed
    // card never bounces straight back. begin/decline/finish persist
    // started/done, flipping the store to HIDDEN, so those can never re-fire.
    LaunchedEffect(phase, currentTab, overlayActive) {
        if (phase != TourPhase.DISMISSED || currentTab != "today" || overlayActive) return@LaunchedEffect
        while (true) {
            delay(5_000)
            val s = store.load()
            when (dormantResurface(s)) {
                TourEntryPhase.WELCOME -> { phase = TourPhase.WELCOME; return@LaunchedEffect }
                TourEntryPhase.PAUSED -> {
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
        if (!running) {
            audio.release()   // pausing/exiting stops narration immediately
            return@LaunchedEffect
        }
        askFocused = false   // a re-presented step starts with the keyboard down
        assistantOpenedForStep = null   // re-arm the two-tap on every (re)entry
        tourNavigate(step, nav)
        store.patch { it.copy(started = true, mode = mode, mediaMode = mediaMode, index = index) }
        // Auto-play each NEW step in Listen mode; Read never touches audio.
        if (mediaMode == TourMediaMode.LISTEN) audio.load(step.id, autoPlay = true, speed = speed)
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

    // Back gesture: running → pause preserving progress; welcome → SOFT
    // dismiss (phase only, store untouched, so `eligible` survives and the
    // quiet-Today loop re-offers ONCE — a second back declines permanently;
    // the explicit "Not now" action stays permanent either way); resume card →
    // dismiss for now (stays paused, the quiet-Today loop / next launch offer again).
    BackHandler(enabled = running) { pause() }
    BackHandler(enabled = phase == TourPhase.WELCOME && cardGate) {
        if (welcomeSoftDismissed) {
            declineWelcome()
        } else {
            welcomeSoftDismissed = true
            explicit = false
            phase = TourPhase.DISMISSED
        }
    }
    BackHandler(enabled = phase == TourPhase.PAUSED && cardGate) { explicit = false; phase = TourPhase.DISMISSED }

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
                    store.patch { it.copy(index = 0, paused = false) }
                    phase = TourPhase.RUNNING
                },
                onNotNow = {
                    store.patch { it.copy(done = true, paused = false) }
                    phase = TourPhase.DONE
                },
            )
        }
        TourPhase.RUNNING -> {
            val targetRect = TourAnchors.resolve(step.target, step.fallbacks)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val screenH = constraints.maxHeight.toFloat()
                TourSpotlight(targetRect, reduceMotion = settings.reduceMotion)

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
                        onPause = ::pause,
                        onExit = ::exit,
                    )
                }
            }
        }
        TourPhase.DISMISSED, TourPhase.DONE -> Unit
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
            Text(
                "A two-minute look at how Unstuck helps you begin, stay with it, and come back — nothing to configure. How would you like to explore?",
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
