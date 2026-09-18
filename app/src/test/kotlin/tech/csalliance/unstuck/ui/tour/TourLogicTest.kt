package tech.csalliance.unstuck.ui.tour

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.ui.settings.SettingsSection

/**
 * Pure-logic tests for the guided tour: step-list parity with the web
 * tour-data.ts (counts, order, shared steps), the entry/resume phase rules,
 * the canned Q&A, the ask-wire builder, the panel dock rule (non-negotiable
 * #1), the speed cycle, and the per-step navigation dispatch — including the
 * FOCUS DEVIATION (the focus/capture steps must never leave Today, because
 * FocusScreen mints a real session on entry).
 */
class TourLogicTest {

    /* ── step lists: web parity ─────────────────────────────────────────── */

    @Test
    fun essentialStepCountAndOrder() {
        assertEquals(
            listOf("welcome", "today", "first-action", "assistant", "focus", "capture", "reentry", "notifications", "finish"),
            ESSENTIAL_STEPS.map { it.id },
        )
        assertEquals(9, ESSENTIAL_STEPS.size)
        assertEquals(9, stepsForMode(TourMode.ESSENTIAL).size)
    }

    @Test
    fun fullStepCountAndOrder() {
        assertEquals(
            listOf(
                "welcome", "today", "first-action", "calendar", "captures", "collections",
                "assistant", "focus", "reentry", "sharing", "insights", "notifications", "personalization", "finish",
            ),
            FULL_STEPS.map { it.id },
        )
        assertEquals(14, FULL_STEPS.size)
        assertEquals(14, stepsForMode(TourMode.FULL).size)
    }

    @Test
    fun fullSharesEssentialStepContent() {
        for (id in listOf("welcome", "today", "first-action", "assistant", "focus", "reentry", "notifications", "finish")) {
            val e = ESSENTIAL_STEPS.first { it.id == id }
            val f = FULL_STEPS.first { it.id == id }
            assertEquals(e.title, f.title)
            assertEquals(e.body, f.body)
            assertEquals(e.narration, f.narration)
            assertEquals(e.primary, f.primary)
            assertEquals(e.target, f.target)
            assertEquals(e.fallbacks, f.fallbacks)
        }
    }

    @Test
    fun stepIdsAreUniquePerMode() {
        assertEquals(ESSENTIAL_STEPS.size, ESSENTIAL_STEPS.map { it.id }.toSet().size)
        assertEquals(FULL_STEPS.size, FULL_STEPS.map { it.id }.toSet().size)
    }

    @Test
    fun focusStepsSpotlightTheDemoSurfaceAnchors() {
        // Round-2 #6: the focus/capture steps target anchors INSIDE the
        // tour-rendered demo focus surface — the old begin-focus targeting
        // (the hero's Focus button + Today fallbacks) is gone.
        val focus = ESSENTIAL_STEPS.first { it.id == "focus" }
        assertEquals(TourAnchorIds.DEMO_FOCUS_RING, focus.target)
        assertEquals(emptyList<String>(), focus.fallbacks)
        val capture = ESSENTIAL_STEPS.first { it.id == "capture" }
        assertEquals(TourAnchorIds.DEMO_CAPTURE_HINT, capture.target)
        assertEquals(emptyList<String>(), capture.fallbacks)
    }

    @Test
    fun onlyFocusStepsShowTheDemoFocusSurface() {
        assertEquals(
            listOf("focus", "capture"),
            ESSENTIAL_STEPS.filter { tourStepShowsDemoFocus(it) }.map { it.id },
        )
        assertEquals(
            listOf("focus"),
            FULL_STEPS.filter { tourStepShowsDemoFocus(it) }.map { it.id },   // FULL has no capture step
        )
    }

    @Test
    fun demoFocusSessionIsMidProgress() {
        // 18:24 into a 40-minute estimate (spec round-2 #6).
        assertEquals(18 * 60 + 24, TOUR_DEMO_ELAPSED_SEC)
        assertEquals(40, TOUR_DEMO_ESTIMATE_MIN)
        assertEquals(1104f / 2400f, tourDemoProgress(), 1e-6f)
        assertTrue(tourDemoProgress() in 0.3f..0.7f)   // visibly mid-progress
        assertTrue(TOUR_DEMO_TASK_TITLE.isNotBlank())
        assertTrue(TOUR_DEMO_FIRST_ACTION.isNotBlank())
    }

    @Test
    fun todayAndFinishRingTheTodayList() {
        // The Start-Next hero left the home (2026-09-18): both steps target the
        // list area directly — always on screen, so no fallback chain.
        for (id in listOf("today", "finish")) {
            val s = ESSENTIAL_STEPS.first { it.id == id }
            assertEquals(TourAnchorIds.TODAY_LIST, s.target)
            assertEquals(emptyList<String>(), s.fallbacks)
        }
    }

    @Test
    fun tourCopyNoLongerDescribesTheStartNextHero() {
        // The hero (and its "Pick another" / "Add one thing" twin) is gone from
        // the home; no step may still narrate it.
        for (s in (FULL_STEPS + ESSENTIAL_STEPS).distinctBy { it.id }) {
            for (text in listOfNotNull(s.title, s.body, s.narration, s.more)) {
                assertFalse("${s.id} still mentions Start Next: $text", text.contains("Start Next", ignoreCase = true))
            }
        }
    }

    @Test
    fun firstActionFallsBackToNewTask() {
        val s = ESSENTIAL_STEPS.first { it.id == "first-action" }
        assertEquals(TourAnchorIds.FIRST_ACTION, s.target)
        assertEquals(listOf(TourAnchorIds.NEW_TASK), s.fallbacks)
    }

    @Test
    fun onlyTheAssistantStepOpensTheAssistant() {
        assertEquals(
            listOf("assistant"),
            FULL_STEPS.filter { it.onShow == TourSideEffect.OPEN_ASSISTANT }.map { it.id }.distinct(),
        )
    }

    /* ── per-step navigation dispatch (incl. the focus deviation) ───────── */

    private class RecordingNav {
        val calls = mutableListOf<String>()
        val nav = TourNav(
            resetToTab = { calls.add("tab:$it") },
            openTaskDetail = { calls.add("detail:$it") },
            openInbox = { calls.add("inbox") },
            openInsights = { calls.add("insights") },
            openSettingsSection = { calls.add("settings:${it.name}") },
            openAssistant = { calls.add("assistant") },
            firstTaskId = { firstTask },
        )
        var firstTask: String? = null
    }

    @Test
    fun focusAndCaptureStepsNeverLeaveToday() {
        for (id in listOf("focus", "capture")) {
            val r = RecordingNav()
            tourNavigate(ESSENTIAL_STEPS.first { it.id == id }, r.nav)
            assertEquals(listOf("tab:today"), r.calls)   // NEVER the focus screen
        }
    }

    @Test
    fun firstActionOpensARealTaskDetail() {
        val r = RecordingNav().apply { firstTask = "t-42" }
        tourNavigate(ESSENTIAL_STEPS.first { it.id == "first-action" }, r.nav)
        assertEquals(listOf("detail:t-42"), r.calls)
    }

    @Test
    fun firstActionOnEmptyAccountStaysOnTasksList() {
        val r = RecordingNav()   // firstTask = null
        tourNavigate(ESSENTIAL_STEPS.first { it.id == "first-action" }, r.nav)
        assertEquals(listOf("tab:tasks"), r.calls)
    }

    @Test
    fun viewsRouteToTheirSurfaces() {
        fun callsFor(id: String, firstTask: String? = null): List<String> {
            val r = RecordingNav().apply { this.firstTask = firstTask }
            tourNavigate(FULL_STEPS.first { it.id == id }, r.nav)
            return r.calls
        }
        assertEquals(listOf("tab:today"), callsFor("welcome"))
        assertEquals(listOf("tab:today"), callsFor("today"))
        assertEquals(listOf("tab:calendar"), callsFor("calendar"))
        assertEquals(listOf("inbox"), callsFor("captures"))
        assertEquals(listOf("tab:lists"), callsFor("collections"))
        assertEquals(listOf("tab:today"), callsFor("assistant"))
        assertEquals(listOf("tab:tasks"), callsFor("sharing"))
        assertEquals(listOf("insights"), callsFor("insights"))
        assertEquals(listOf("settings:FOCUS"), callsFor("notifications"))
        assertEquals(listOf("settings:INTERFACE"), callsFor("personalization"))
        assertEquals(listOf("tab:today"), callsFor("finish"))
    }

    @Test
    fun settingsSectionMapping() {
        // Android keeps the Calm/Balanced/Coach presence control in Settings→Focus.
        assertEquals(SettingsSection.FOCUS, tourSettingsSection("Notifications"))
        assertEquals(SettingsSection.INTERFACE, tourSettingsSection("Interface"))
        assertEquals(SettingsSection.ACCOUNT, tourSettingsSection(null))
    }

    /* ── two-tap assistant primary ──────────────────────────────────────── */

    @Test
    fun assistantPrimaryIsTwoTap() {
        val step = ESSENTIAL_STEPS.first { it.id == "assistant" }
        // First tap: the step's own label (it opens the bubble).
        assertEquals("Open the Assistant", tourPrimaryLabel(step, 3, 9, assistantOpenedForStep = null))
        // After the bubble opened for THIS step: Continue advances.
        assertEquals("Continue", tourPrimaryLabel(step, 3, 9, assistantOpenedForStep = "assistant"))
        // A stale flag from another step does not swallow the first tap.
        assertEquals("Open the Assistant", tourPrimaryLabel(step, 3, 9, assistantOpenedForStep = "reentry"))
    }

    @Test
    fun plainAndLastStepPrimaryLabels() {
        val today = ESSENTIAL_STEPS.first { it.id == "today" }
        assertEquals("Continue", tourPrimaryLabel(today, 1, 9, null))
        val finish = ESSENTIAL_STEPS.last()
        assertEquals("Begin", tourPrimaryLabel(finish, 8, 9, null))
    }

    /* ── canned Q&A ─────────────────────────────────────────────────────── */

    @Test
    fun answerForMatchesKnownQuestions() {
        assertTrue(answerFor("What is usable time?").contains("focus time you realistically have"))
        assertTrue(answerFor("does it work offline?").contains("works offline"))
        assertTrue(answerFor("How do I restart the tour later?").contains("Settings → Account"))
        assertTrue(answerFor("who can see my data?").contains("Nothing is shared by default"))
    }

    @Test
    fun answerForFallsBackOnUnknown() {
        assertEquals(TOUR_FALLBACK_ANSWER, answerFor("what's the meaning of life?"))
    }

    /* ── entry-phase / resume rules ─────────────────────────────────────── */

    @Test
    fun initialPhaseRules() {
        // Done stays hidden forever.
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(TourState(done = true, paused = true, eligible = true, mode = TourMode.FULL)))
        // A paused run (with a mode) offers the resume card.
        assertEquals(TourEntryPhase.PAUSED, initialPhase(TourState(started = true, paused = true, mode = TourMode.ESSENTIAL)))
        // Started (or declined) but not paused: hidden.
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(TourState(started = true)))
        // Fresh account that just onboarded: the one-time welcome.
        assertEquals(TourEntryPhase.WELCOME, initialPhase(TourState(eligible = true)))
        // Existing accounts are NEVER ambushed.
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(TourState()))
    }

    @Test
    fun resumeDecisionRules() {
        // An unfinished run (paused, or stranded by process death mid-run)
        // resumes at its saved step.
        assertEquals(ResumeDecision.Running(5), resumeDecision(TourState(started = true, index = 5)))
        assertEquals(ResumeDecision.Running(3), resumeDecision(TourState(started = true, paused = true, mode = TourMode.FULL, index = 3)))
        // Fresh accounts and FINISHED tours get the (reset) welcome — the
        // Settings row must never "resume" past the end of a done run.
        assertEquals(ResumeDecision.Welcome, resumeDecision(TourState()))
        assertEquals(ResumeDecision.Welcome, resumeDecision(TourState(eligible = true)))
        assertEquals(ResumeDecision.Welcome, resumeDecision(TourState(started = true, done = true, index = 8)))
    }

    @Test
    fun dormantRecheckSurfacesWelcomeAndPaused() {
        // The quiet-Today loop may surface the one-time welcome…
        assertEquals(TourEntryPhase.WELCOME, dormantResurface(TourState(eligible = true)))
        val paused = TourState(started = true, paused = true, mode = TourMode.ESSENTIAL)
        // …but a paused run's MODAL stays dormant while the resume CHIP is
        // visible — the chip is the primary re-entry, and the card must never
        // fight it every 5s.
        assertNull(dormantResurface(paused))
        // The card becomes the SECONDARY path only after the chip's ✕…
        assertEquals(TourEntryPhase.PAUSED, dormantResurface(paused.copy(chipDismissed = true)))
        // …and it's ONE-SHOT per process (mirror welcomeSoftDismissed): once
        // surfaced or back-dismissed it never re-pops.
        assertNull(dormantResurface(paused.copy(chipDismissed = true), pausedResurfaced = true))
        // The welcome resurface is untouched by the paused one-shot flag.
        assertEquals(TourEntryPhase.WELCOME, dormantResurface(TourState(eligible = true), pausedResurfaced = true))
        // A STRANDED run (process death mid-run) stays dormant here too — the
        // chip is its re-entry, never the modal.
        assertNull(dormantResurface(TourState(started = true, mode = TourMode.ESSENTIAL, index = 3)))
        // Everything else stays dormant.
        assertNull(dormantResurface(TourState()))
        assertNull(dormantResurface(TourState(started = true)))
        assertNull(dormantResurface(TourState(done = true, eligible = true, paused = true, mode = TourMode.FULL)))
    }

    /* ── dock rule (non-negotiable #1) ──────────────────────────────────── */

    @Test
    fun targetInTopHalfDocksPanelBottom() {
        assertEquals(PanelDock.BOTTOM, panelDock(targetCenterY = 200f, screenHeightPx = 2000f))
    }

    @Test
    fun targetInBottomHalfDocksPanelTop() {
        assertEquals(PanelDock.TOP, panelDock(targetCenterY = 1800f, screenHeightPx = 2000f))
    }

    @Test
    fun noTargetDocksBottom() {
        assertEquals(PanelDock.BOTTOM, panelDock(targetCenterY = null, screenHeightPx = 2000f))
    }

    @Test
    fun availableSpaceIsOutsideTheRing() {
        // Ring at 100..600 on a 2000px screen, panel docked BOTTOM → space below.
        assertEquals(1400f, panelAvailablePx(ringTop = 100f, ringBottom = 600f, screenHeightPx = 2000f, dock = PanelDock.BOTTOM))
        // Docked TOP → space above the ring.
        assertEquals(100f, panelAvailablePx(ringTop = 100f, ringBottom = 600f, screenHeightPx = 2000f, dock = PanelDock.TOP))
        // No ring → the whole screen.
        assertEquals(2000f, panelAvailablePx(null, null, 2000f, PanelDock.BOTTOM))
    }

    @Test
    fun tallTargetSpanningBothHalvesCollapsesThePanel() {
        // Target rings 50..1950 on a 2000px screen: center in the bottom half
        // → dock TOP, with only 50px there — the panel must collapse (title +
        // controls) rather than cover the ring.
        val dock = panelDock(targetCenterY = 1000.1f, screenHeightPx = 2000f)
        assertEquals(PanelDock.TOP, dock)
        val avail = panelAvailablePx(ringTop = 50f, ringBottom = 1950f, screenHeightPx = 2000f, dock = dock)
        assertEquals(50f, avail)
        assertTrue(panelCollapsed(availablePx = avail, collapseThresholdPx = 900f))
        // A short target leaves room — expanded.
        assertFalse(panelCollapsed(availablePx = 1400f, collapseThresholdPx = 900f))
    }

    /* ── inset-aware placement (dock w/ IME + flip-not-cap) ─────────────── */
    /* panelPlacement derives its OWN minima from density + font scale, so the
     * tests below hand it physical facts only — exactly what TourHost passes.
     * At fontScale 1 they come out at: collapsed 172dp, readable 240dp. */

    private val minCollapsed1 get() = tourPanelCollapsedMinDp()   // 172dp
    private val minReadable1 get() = tourPanelReadableMinDp()     // 240dp

    @Test
    fun placementMinimaAreSummedFromThePanelsOwnMetrics() {
        // header 55 + title-only body 42 + footer 64 + 11 slack.
        assertEquals(172f, tourPanelCollapsedMinDp(1f), 0.01f)
        // …+ the 8dp gap and three 20sp lines of step copy.
        assertEquals(240f, tourPanelReadableMinDp(1f), 0.01f)
        // …and the confirm swaps those three lines for its own footer.
        assertEquals(237f, tourPauseConfirmMinDp(1f), 0.01f)
    }

    @Test
    fun placementSubtractsDockSideInsetsBeforeTheCollapseDecision() {
        // Ring 100..1450 on 2000px @2x, docked BOTTOM: raw space below = 550,
        // comfortably over the 480px (240dp) readable minimum. But the panel is
        // laid out inside the bottom inset (100) + margins (24), so only 426 is
        // really available — it MUST collapse (the raw 550 would have said
        // "expanded" and overlapped the ring by the inset height).
        val p = panelPlacement(
            ringTop = 100f, ringBottom = 1450f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 100f, marginPx = 12f,
            densityPx = 2f,
        )
        assertEquals(PanelDock.BOTTOM, p.dock)
        assertEquals(426f, p.availablePx)
        assertTrue(p.collapsed)
        // The point of the test: the RAW space would have read as expanded.
        assertTrue(550f >= minReadable1 * 2f)
    }

    @Test
    fun keyboardUpRerunsTheDecisionAndCanCollapse() {
        // Same ring, keyboard down: plenty of space below → expanded BOTTOM.
        val down = panelPlacement(
            ringTop = 200f, ringBottom = 700f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 60f, marginPx = 12f,
            densityPx = 2f,
        )
        assertEquals(PanelDock.BOTTOM, down.dock)
        assertEquals(1216f, down.availablePx)
        assertFalse(down.collapsed)
        // Keyboard up (bottom inset includes the 800px IME): the same dock
        // decision re-runs and collapses instead of covering the ring.
        val up = panelPlacement(
            ringTop = 200f, ringBottom = 700f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 800f, marginPx = 12f,
            densityPx = 2f,
        )
        assertEquals(PanelDock.BOTTOM, up.dock)
        assertEquals(476f, up.availablePx)
        assertTrue(up.collapsed)
    }

    @Test
    fun flipNotCapWhenEvenTheCollapsedMinimumCannotFit() {
        // Ring 400..1500 (center in the top half → prefer BOTTOM), but the IME
        // leaves only 76px below — less than the 344px (172dp) collapsed
        // minimum. The old flat floor would have capped OVER the ring; the rule
        // FLIPS to the top, where 376px fits the collapsed panel outside it.
        val p = panelPlacement(
            ringTop = 400f, ringBottom = 1500f, screenHeightPx = 2000f,
            topInsetPx = 0f, bottomInsetPx = 400f, marginPx = 12f,
            densityPx = 2f,
        )
        assertEquals(PanelDock.TOP, p.dock)
        assertEquals(376f, p.availablePx)
        assertTrue(p.collapsed)
        // The cap never exceeds the space outside the ring on the flipped side.
        assertTrue(p.availablePx <= 400f - 12f * 2)
    }

    @Test
    fun impossibleOnBothSidesTakesTheRoomierSideWithTheUsabilityFloor() {
        // A ring spanning nearly the whole usable screen: neither side fits
        // even the collapsed minimum — the roomier side wins, floored so the
        // panel stays usable (the single unavoidable-overlap case).
        val p = panelPlacement(
            ringTop = 60f, ringBottom = 1800f, screenHeightPx = 2000f,
            topInsetPx = 0f, bottomInsetPx = 150f, marginPx = 12f,
            densityPx = 2f,
        )
        assertEquals(PanelDock.TOP, p.dock)              // 36px above beats 26px below
        assertEquals(minCollapsed1 * 2f, p.availablePx)  // usability floor
        assertTrue(p.collapsed)
    }

    @Test
    fun askFocusedPrefersTopAndSuppressesTheCollapse() {
        // Target in the TOP half would normally dock BOTTOM — but with the Ask
        // field focused (keyboard up) the panel prefers TOP and must never
        // collapse (the field lives in the body).
        val p = panelPlacement(
            ringTop = 700f, ringBottom = 900f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 900f, marginPx = 12f,
            densityPx = 2f, askFocused = true,
        )
        assertEquals(PanelDock.TOP, p.dock)
        assertEquals(596f, p.availablePx)
        assertFalse(p.collapsed)
        // Even with NO room above, the collapse stays suppressed — and the
        // floor is the READABLE minimum, not the collapsed one: at the
        // collapsed floor the body viewport is a few dp tall and the field the
        // user is typing into has nowhere to scroll into view.
        val tight = panelPlacement(
            ringTop = 100f, ringBottom = 700f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 900f, marginPx = 12f,
            densityPx = 2f, askFocused = true,
        )
        assertEquals(PanelDock.TOP, tight.dock)
        assertFalse(tight.collapsed)
        assertEquals(minReadable1 * 2f, tight.availablePx)
        assertTrue(tight.availablePx > minCollapsed1 * 2f)
    }

    @Test
    fun noRingPlacementUsesTheInsetAdjustedScreen() {
        val p = panelPlacement(
            ringTop = null, ringBottom = null, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 100f, marginPx = 12f,
            densityPx = 2f,
        )
        assertEquals(PanelDock.BOTTOM, p.dock)
        assertEquals(2000f - 100f - 24f, p.availablePx)
        assertFalse(p.collapsed)
    }

    /* ── readable minimum: cap-and-scroll instead of throwing the body away ─
     * Real 6.3"-class geometry (Pixel 8/9: 1080×2400px, 411×914dp @2.625).
     * Before the readable minimum the collapse test was `avail < FULL panel
     * height`, so any side that could not hold the whole panel at once lost
     * the step's copy — even with room for the header, title, three lines and
     * the footer. These pin the three bands: readable → expanded + capped,
     * between the two minima → collapsed, neither side → the floor.
     */

    private val density = 2.625f                 // Pixel 8/9
    private fun dp(v: Float) = v * density
    private val screen63 get() = dp(914f)

    /** Pixel-8 geometry with the host's real inset/margin numbers. */
    private fun placeOn63(ringTopDp: Float, ringBottomDp: Float, fontScale: Float = 1f) = panelPlacement(
        ringTop = dp(ringTopDp), ringBottom = dp(ringBottomDp), screenHeightPx = screen63,
        topInsetPx = dp(24f),           // status bar
        bottomInsetPx = dp(48f),        // 3-button nav bar
        marginPx = dp(12f),
        densityPx = density, fontScale = fontScale,
    )

    @Test
    fun readablePanelStaysExpandedAndScrollsUnderTheCap() {
        // A task-list spotlight covering ~2/3 of a Pixel 8 (300dp down to the
        // bottom edge): dock TOP with 252dp above it. That is more than the
        // readable minimum but LESS than the panel's ~360dp natural height —
        // the old `avail < expandedHeight` test dropped the body here.
        val p = placeOn63(ringTopDp = 300f, ringBottomDp = 914f)
        assertEquals(PanelDock.TOP, p.dock)
        assertEquals(dp(252f), p.availablePx)
        assertFalse(p.collapsed)
        // …and it really is a CAPPED render (the body scrolls): the cap is
        // below the panel's natural height, which must no longer matter.
        assertTrue(p.availablePx < dp(360f))
    }

    @Test
    fun belowTheReadableMinimumStillCollapses() {
        // Same shape, 50dp taller ring: 202dp above it — past the 172dp
        // collapsed floor but under the readable minimum, so title-only.
        val p = placeOn63(ringTopDp = 250f, ringBottomDp = 914f)
        assertEquals(PanelDock.TOP, p.dock)
        assertEquals(dp(202f), p.availablePx)
        assertTrue(p.availablePx > dp(minCollapsed1))
        assertTrue(p.collapsed)
    }

    @Test
    fun nearlyFullScreenRingStillCollapsesToTheFloor() {
        // A ring from 120dp to 880dp on a 914dp screen: 72dp above, nothing
        // below once the nav bar + margins come off — neither side fits even
        // the collapsed minimum, so the roomier side takes the floor.
        val p = placeOn63(ringTopDp = 120f, ringBottomDp = 880f)
        assertEquals(PanelDock.TOP, p.dock)
        assertEquals(dp(minCollapsed1), p.availablePx)
        assertTrue(p.collapsed)
    }

    @Test
    fun theReadableMinimumScalesWithTheSystemFontSize() {
        // The three body lines the readable minimum buys are 20sp, so they
        // grow with the system font size. The SAME 252dp cap that reads as
        // readable at fontScale 1 cannot show three lines at fontScale 2 —
        // it must collapse instead of pretending.
        val big = placeOn63(ringTopDp = 300f, ringBottomDp = 914f, fontScale = 2f)
        assertEquals(dp(252f), big.availablePx)
        assertTrue(big.collapsed)
        // …and it is the READABLE minimum that moved, not the floor: 252dp is
        // still well clear of the (also scaled) collapsed minimum.
        assertTrue(dp(252f) > dp(tourPanelCollapsedMinDp(2f)))
        assertTrue(tourPanelReadableMinDp(2f) > tourPanelReadableMinDp(1f))
        assertTrue(tourPanelCollapsedMinDp(2f) > tourPanelCollapsedMinDp(1f))
        // Past Android's largest setting the requirement stops growing — a
        // scrolling body beats collapsing every step on the phone.
        assertEquals(tourPanelReadableMinDp(2f), tourPanelReadableMinDp(3.5f), 0.01f)
    }

    @Test
    fun theCollapsedFloorFitsAWholeTitleLine() {
        // The old floor was a hand-picked 150dp — ~11dp SHORT of the 161dp a
        // collapsed panel actually needs (header 55 + 16dp body insets + one
        // 26dp title line + footer 64), so the step title clipped at the floor.
        assertTrue(tourPanelCollapsedMinDp(1f) >= 161f)
        assertTrue(tourPanelCollapsedMinDp(1f) > 150f)
        // …and it scales too: a 26sp title needs more room than a 13sp one.
        assertTrue(tourPanelCollapsedMinDp(2f) >= 2f * 26f + 55f + 16f + 64f)
    }

    @Test
    fun readableMinimumSitsBetweenTheCollapsedFloorAndAFullPanel() {
        // The constant is the whole point of the rule: below the panel's
        // natural height (else it collapses everything that has to scroll),
        // above the title-only floor, and never BELOW the pause-confirm floor
        // (which may only grow a panel that is already collapsed).
        assertTrue(TOUR_PANEL_READABLE_MIN_HEIGHT > minCollapsed1.dp)
        assertTrue(TOUR_PANEL_READABLE_MIN_HEIGHT < 360.dp)
        assertTrue(TOUR_PANEL_READABLE_MIN_HEIGHT >= TOUR_PAUSE_CONFIRM_MIN_PANEL_HEIGHT)
    }

    /* ── Android-localized canned answers ───────────────────────────────── */

    @Test
    fun androidLocalizedCannedAnswers() {
        // The presence control lives in Settings → Focus on Android…
        assertTrue(answerFor("can I turn notifications off?").contains("Settings → Focus"))
        // …and the restart row is Settings → Account → Product tour.
        assertTrue(answerFor("how do I restart the tour?").contains("Settings → Account → Product tour"))
    }

    /* ── listen: speed cycle + audio resource naming ────────────────────── */

    @Test
    fun speedCycles075To2AndWraps() {
        val seen = mutableListOf(0.75f)
        repeat(6) { seen.add(nextTourSpeed(seen.last())) }
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f), seen)
    }

    @Test
    fun speedLabelDropsTrailingZeros() {
        assertEquals("1", formatTourSpeed(1f))
        assertEquals("2", formatTourSpeed(2f))
        assertEquals("0.75", formatTourSpeed(0.75f))
        assertEquals("1.5", formatTourSpeed(1.5f))
    }

    @Test
    fun audioResourceNamesMatchBundledClips() {
        assertEquals("tour_first_action", tourAudioResName("first-action"))
        assertEquals("tour_welcome", tourAudioResName("welcome"))
        // Every step in both modes maps to a name in the bundled set (bar the
        // steps whose clips are awaiting a re-record — see TourAudio.kt).
        val bundled = setOf(
            "tour_welcome", "tour_today", "tour_first_action", "tour_assistant", "tour_focus",
            "tour_capture", "tour_reentry", "tour_notifications", "tour_finish", "tour_calendar",
            "tour_captures", "tour_collections", "tour_sharing", "tour_insights", "tour_personalization",
        )
        for (s in FULL_STEPS + ESSENTIAL_STEPS) {
            if (s.id in TOUR_STEPS_AWAITING_NARRATION) continue
            assertTrue("missing clip for ${s.id}", tourAudioResName(s.id) in bundled)
        }
    }

    /** The regenerated today clips (2026-09-18, DashScope qwen3-tts-flash,
     *  voice "Cherry") speak EXACTLY these strings — the same copy iOS ships.
     *  Editing the step's copy without re-recording would put the captions
     *  and the audio out of step; this pin makes that a test failure. */
    @Test
    fun todayClipsMatchTheStepCopy() {
        val today = ESSENTIAL_STEPS.first { it.id == "today" }
        assertEquals(
            "This is Today. Up top: a greeting, how much you’ve focused this week, and the assistant pill — ask, plan, or brain-dump. Say it or type it, and it does it. Under that, the list shows only what’s planned for today, filtered by area; everything else waits quietly in Backlog. Focus starts from any task row, or from inside the task.",
            today.narration,
        )
        assertEquals(
            "Filter Today by area with the pills above the list, or switch to Backlog to see what’s waiting. Any row can start Focus — so can the task itself. Nothing unplanned is lost; it just isn’t in the way.",
            today.more,
        )
        // Both modes carry the one today step.
        val inFull = FULL_STEPS.first { it.id == "today" }
        assertEquals(today.narration, inFull.narration)
        assertEquals(today.more, inFull.more)
        assertEquals(today.body, inFull.body)
    }

    /** The two regenerated clips are really in res/raw (a wired R.raw id
     *  proves the file existed at compile time; this proves it is the file we
     *  mean, next to the rest of the Cherry set). Gradle runs unit tests with
     *  the module dir as cwd; fall back to the repo root for IDE runs. */
    @Test
    fun todayClipsArePresentInResRaw() {
        val raw = listOf("src/main/res/raw", "app/src/main/res/raw")
            .map { java.io.File(it) }
            .firstOrNull { it.isDirectory }
        assertTrue("res/raw not found from ${java.io.File(".").absolutePath}", raw != null)
        for (name in listOf("tour_today.m4a", "tour_today_more.m4a", "tour_welcome.m4a")) {
            val f = java.io.File(raw, name)
            assertTrue("$name missing from res/raw", f.isFile && f.length() > 10_000)
        }
    }

    /* ── ask wire + fallback ────────────────────────────────────────────── */

    @Test
    fun buildTourPromptEmbedsMarkTitleAndQuestion() {
        val p = buildTourPrompt("Focus, and the Ring", "Body text.", "why a ring?")
        assertTrue(p.contains(TOUR_CONTEXT_MARK))
        assertTrue(p.contains("Focus, and the Ring"))
        assertTrue(p.endsWith("Question: why a ring?"))
    }

    @Test
    fun buildAskWireFramesTheFirstQuestion() {
        val wire = buildAskWire("Title", "Body", emptyList(), "  q1  ")
        assertEquals(1, wire.size)
        assertEquals("user", wire[0].role)
        assertTrue(wire[0].content!!.contains(TOUR_CONTEXT_MARK))
        assertTrue(wire[0].content!!.endsWith("Question: q1"))
    }

    @Test
    fun buildAskWireKeepsFramedHistoryUnframed() {
        val thread = listOf(
            ChatMessage(role = "user", content = buildTourPrompt("T", "B", "q1")),
            ChatMessage(role = "assistant", content = "a1"),
        )
        val wire = buildAskWire("T", "B", thread, "q2")
        assertEquals(3, wire.size)
        assertEquals("q2", wire.last().content)   // framing already present upstream
    }

    @Test
    fun buildAskWireCapsHistoryAndReframesWhenTheMarkDropsOff() {
        val thread = listOf(
            ChatMessage(role = "user", content = buildTourPrompt("T", "B", "q1")),   // will be capped away
            ChatMessage(role = "assistant", content = "a1"),
            ChatMessage(role = "user", content = "q2"),
            ChatMessage(role = "assistant", content = "a2"),
            ChatMessage(role = "user", content = "q3"),
            ChatMessage(role = "assistant", content = "a3"),
        )
        val wire = buildAskWire("T", "B", thread, "q4")
        // Cap = last 4 + the new question; must start at a user turn.
        assertEquals(5, wire.size)
        assertEquals("user", wire.first().role)
        // The framed original fell off → the new question re-embeds the framing.
        assertTrue(wire.last().content!!.contains(TOUR_CONTEXT_MARK))
    }

    @Test
    fun resolveAskReplyPassesRealTextThrough() {
        val r = resolveAskReply("  A real answer.  ", "whatever")
        assertTrue(r.fromAssistant)
        assertEquals("A real answer.", r.text)
    }

    @Test
    fun resolveAskReplyFallsBackToCanned() {
        val r = resolveAskReply(null, "does it work offline?")
        assertFalse(r.fromAssistant)
        assertTrue(r.text.contains("works offline"))
        // Tool-calls-only reply ⇒ blank content ⇒ canned too.
        assertFalse(resolveAskReply("   ", "x").fromAssistant)
    }

    @Test
    fun sendTourAskNeverThrows() = runTest {
        val boom = sendTourAsk(emptyList(), "does it work offline?") { throw IllegalStateException("network") }
        assertFalse(boom.fromAssistant)
        assertTrue(boom.text.contains("works offline"))
        val ok = sendTourAsk(emptyList(), "q") { "real" }
        assertTrue(ok.fromAssistant)
        assertEquals("real", ok.text)
    }

    /* ── audio resource ids resolve for every step (compile-time map) ───── */

    @Test
    fun everyStepHasABundledAudioResource() {
        for (s in FULL_STEPS + ESSENTIAL_STEPS) {
            if (s.id in TOUR_STEPS_AWAITING_NARRATION) {
                // Copy changed after the clip was recorded: Listen is hidden on
                // that step rather than narrating a surface that no longer exists.
                assertEquals("stale clip still wired for ${s.id}", 0, tourAudioRes(s.id))
            } else {
                assertTrue("no raw resource wired for ${s.id}", tourAudioRes(s.id) != 0)
            }
        }
        // The today clips were re-recorded for the hero-less home (2026-09-18):
        // nothing is parked any more — every step narrates, including today.
        assertEquals(emptySet<String>(), TOUR_STEPS_AWAITING_NARRATION)
        assertTrue(tourAudioRes("today") != 0)
        assertTrue(tourMoreAudioRes("today") != 0)
        assertEquals(0, tourAudioRes("nope"))
    }

    /* ── round-2 #3: Tell-me-more clips ─────────────────────────────────── */

    @Test
    fun everyStepWithMoreTextHasABundledMoreClip() {
        // Exactly the steps carrying `more` text have a more clip; the rest
        // resolve to 0 (the section expands silently — never a crash).
        for (s in (FULL_STEPS + ESSENTIAL_STEPS).distinctBy { it.id }) {
            if (s.more != null && s.id !in TOUR_STEPS_AWAITING_NARRATION) {
                assertTrue("no more-clip wired for ${s.id}", tourMoreAudioRes(s.id) != 0)
            } else {
                assertEquals("unexpected more-clip for ${s.id}", 0, tourMoreAudioRes(s.id))
            }
        }
        assertEquals(0, tourMoreAudioRes("nope"))
    }

    @Test
    fun moreAudioResourceNaming() {
        assertEquals("tour_first_action_more", tourMoreAudioResName("first-action"))
        assertEquals("tour_welcome_more", tourMoreAudioResName("welcome"))
    }

    /* ── round-2 #1: copy ───────────────────────────────────────────────── */

    @Test
    fun welcomeCopyKillsTheDurationContradiction() {
        assertEquals(
            "A quick look at how Unstuck helps you begin, stay with it, and come back — about three minutes for the essentials.",
            TOUR_WELCOME_INTRO,
        )
        assertFalse(TOUR_WELCOME_INTRO.contains("two-minute"))
        assertEquals(
            "Pause anytime — pick it back up from Settings → Account → Product tour.",
            TOUR_WELCOME_FOOTER,
        )
    }

    @Test
    fun pauseConfirmNamesTheSettingsPath() {
        assertEquals("Pause the tour? Your progress is saved.", TOUR_PAUSE_CONFIRM_TITLE)
        assertTrue(TOUR_PAUSE_SETTINGS_PATH.contains("Settings → Account → Product tour"))
    }

    /* ── round-2 #2: live captions (sentence split + char-weighted spans) ── */

    @Test
    fun splitSentencesMatchesWebSemantics() {
        assertEquals(listOf("One.", "Two!", "Three?"), splitTourSentences("One. Two! Three?"))
        assertEquals(listOf("First sentence.", "and then"), splitTourSentences("First sentence. and then"))
        assertEquals(listOf("Just the one."), splitTourSentences("Just the one."))
        assertEquals(emptyList<String>(), splitTourSentences(""))
        assertEquals(emptyList<String>(), splitTourSentences("   "))
        // Closing quotes stay with their sentence.
        assertEquals(
            listOf("He said “open the document.”", "Then he began."),
            splitTourSentences("He said “open the document.” Then he began."),
        )
    }

    @Test
    fun splitSentencesFoldsStrayFragments() {
        // A sub-4-char fragment merges into the previous sentence (web parity).
        val out = splitTourSentences("A full sentence. ok")
        assertEquals(1, out.size)
        assertTrue(out[0].endsWith("ok"))
    }

    @Test
    fun realNarrationSplitsAndCoversTheText() {
        for (s in ESSENTIAL_STEPS) {
            val sentences = splitTourSentences(s.narration)
            assertTrue("narration of ${s.id} should split", sentences.size >= 2)
            // Nothing is lost: every sentence appears in the source text.
            for (sent in sentences) assertTrue(s.narration.contains(sent.trim().take(20)))
        }
    }

    @Test
    fun sentenceSpansAreCharWeightedAndContiguous() {
        // "Aaaaaaa." (8 chars) + "Bbb." (4 chars) → windows [0, 2/3) and [2/3, 1]:
        // the longer sentence owns proportionally more of the clip.
        val spans = tourSentenceSpans("Aaaaaaa. Bbb.")
        assertEquals(2, spans.size)
        assertEquals(0f, spans[0].start, 1e-6f)
        assertEquals(2f / 3f, spans[0].end, 1e-6f)
        assertEquals(2f / 3f, spans[1].start, 1e-6f)
        assertEquals(1f, spans[1].end, 1e-6f)
    }

    @Test
    fun captionPickerFollowsProgress() {
        val spans = tourSentenceSpans("Aaaaaaa. Bbb.")
        assertEquals("Aaaaaaa.", tourCaptionAt(spans, 0f))
        assertEquals("Aaaaaaa.", tourCaptionAt(spans, 0.5f))
        assertEquals("Bbb.", tourCaptionAt(spans, 0.7f))
        assertEquals("Bbb.", tourCaptionAt(spans, 1f))       // finished → last sentence
        assertEquals("Bbb.", tourCaptionAt(spans, 1.7f))     // overshoot clamps
        assertEquals("Aaaaaaa.", tourCaptionAt(spans, -0.5f))  // undershoot clamps
        assertNull(tourCaptionAt(emptyList(), 0.5f))
    }

    /* ── round-2 #4: spotlight-only lockdown ────────────────────────────── */

    @Test
    fun scrimConsumesInputWithLiveOpenedSurfaceExemption() {
        fun step(id: String) = FULL_STEPS.first { it.id == id }
        // Targeted + no-target steps: lockdown regardless of the settings state.
        for (id in listOf("today", "first-action", "focus", "reentry", "welcome", "calendar", "captures")) {
            for (open in listOf(null) + SettingsSection.values()) assertTrue(tourScrimConsumesInput(step(id), openSection = open))
        }
        assertTrue(tourScrimConsumesInput(ESSENTIAL_STEPS.first { it.id == "capture" }, openSection = null))
        // The assistant step CONSUMES input: its sheet renders in its OWN
        // window ABOVE the blockers, so it stays interactive while open —
        // same as the reentry step; the app beneath stays locked.
        assertTrue(tourScrimConsumesInput(step("assistant"), openSection = null))
        assertTrue(tourScrimConsumesInput(step("assistant"), openSection = SettingsSection.FOCUS))
        // Settings steps: the exemption is LIVE, not per-step, and SCOPED to
        // the step's OWN section — interactive only while that SettingsSub
        // route is topmost; closing it mid-step re-applies the lockdown.
        assertFalse(tourScrimConsumesInput(step("notifications"), openSection = SettingsSection.FOCUS))
        assertFalse(tourScrimConsumesInput(step("personalization"), openSection = SettingsSection.INTERFACE))
        assertTrue(tourScrimConsumesInput(step("notifications"), openSection = null))
        assertTrue(tourScrimConsumesInput(step("personalization"), openSection = null))
    }

    @Test
    fun settingsExemptionNeverUnlocksTheAccountOrAnyOtherSection() {
        // The lockdown hole: with the whole Settings surface exempt, one back
        // tap from the spotlighted section reached the hub → Account → Sign
        // out / Delete my account / Export from inside a guided demo. Now ONLY
        // the step's own section is exempt; the hub (openSection = null) and
        // every other section stay locked.
        val notifications = FULL_STEPS.first { it.id == "notifications" }
        val personalization = FULL_STEPS.first { it.id == "personalization" }
        assertEquals(SettingsSection.FOCUS, tourSettingsSection(notifications.section))
        assertEquals(SettingsSection.INTERFACE, tourSettingsSection(personalization.section))
        for (section in SettingsSection.values()) {
            assertEquals(
                "notifications step: only FOCUS is exempt (got $section)",
                section != SettingsSection.FOCUS,
                tourScrimConsumesInput(notifications, openSection = section),
            )
            assertEquals(
                "personalization step: only INTERFACE is exempt (got $section)",
                section != SettingsSection.INTERFACE,
                tourScrimConsumesInput(personalization, openSection = section),
            )
        }
        // The danger section is locked on BOTH settings steps, explicitly.
        assertTrue(tourScrimConsumesInput(notifications, openSection = SettingsSection.ACCOUNT))
        assertTrue(tourScrimConsumesInput(personalization, openSection = SettingsSection.ACCOUNT))
        assertTrue(tourScrimConsumesInput(notifications, openSection = SettingsSection.BACKUP))
        // The hub itself (no SettingsSub topmost) is locked too.
        assertTrue(tourScrimConsumesInput(notifications, openSection = null))
    }

    @Test
    fun appContentIsHiddenFromAccessibilityWheneverTheTourHoldsTheLock() {
        val today = ESSENTIAL_STEPS.first { it.id == "today" }
        val assistant = ESSENTIAL_STEPS.first { it.id == "assistant" }
        val notifications = ESSENTIAL_STEPS.first { it.id == "notifications" }
        // A welcome/resume card up → hidden regardless of the running policy.
        assertTrue(tourHidesAppContent(cardUp = true, running = false, policy = null))
        // Not running, no card → nothing hidden.
        assertFalse(tourHidesAppContent(cardUp = false, running = false, policy = null))
        // Running with a consuming scrim (display-only cutout) → hidden: a
        // screen reader must not be able to activate the spotlighted rows.
        assertTrue(tourHidesAppContent(false, true, tourLockdownPolicy(today, openSection = null, overlayAboveTour = false)))
        // The assistant/reentry cutout is interactive by touch, but the
        // content beneath is still hidden (the panel's CTA opens the sheet).
        assertTrue(tourHidesAppContent(false, true, tourLockdownPolicy(assistant, openSection = null, overlayAboveTour = false)))
        // A focus takeover above the tour → full blocker → hidden.
        assertTrue(tourHidesAppContent(false, true, tourLockdownPolicy(today, openSection = null, overlayAboveTour = true)))
        // The ONE reachable frame — the step's own settings section, live and
        // exempt — stays reachable by screen reader too.
        assertFalse(tourHidesAppContent(false, true, tourLockdownPolicy(notifications, openSection = SettingsSection.FOCUS, overlayAboveTour = false)))
        // …but not when the user has popped to the hub / another section.
        assertTrue(tourHidesAppContent(false, true, tourLockdownPolicy(notifications, openSection = null, overlayAboveTour = false)))
        assertTrue(tourHidesAppContent(false, true, tourLockdownPolicy(notifications, openSection = SettingsSection.ACCOUNT, overlayAboveTour = false)))
    }

    @Test
    fun foregroundReturnRepresentsTheSurfaceButNeverRestartsNarration() {
        // A step change does everything: navigate, reset per-step state, and
        // (re)load the narration.
        assertEquals(
            TourPresentation(navigate = true, resetStepState = true, reloadAudio = true),
            tourPresentation(TourPresentTrigger.STEP_CHANGE),
        )
        // Returning to the foreground (navEpoch) only re-navigates: a paused
        // or finished narration must not blare again from 0:00, and the demo
        // capture sheet keeps its half-typed text.
        assertEquals(
            TourPresentation(navigate = true, resetStepState = false, reloadAudio = false),
            tourPresentation(TourPresentTrigger.FOREGROUND_RETURN),
        )
    }

    @Test
    fun cutoutIsInteractiveOnlyOnAssistantReentryAndCaptureSteps() {
        // Cross-platform cutout policy: the two bubble-targeting steps keep a
        // touch-through cut-out (the bubble opens an own-window sheet), and —
        // round 3 — so does the capture step: its cutout is the DEMO surface's
        // capture cluster, where taps land inside the tour's own demo (whose
        // root swallows input), so nothing real is ever reachable.
        assertEquals(
            setOf("assistant", "reentry", "capture"),
            (FULL_STEPS + ESSENTIAL_STEPS).filter { tourCutoutInteractive(it) }.map { it.id }.toSet(),
        )
        // Display-only everywhere else — the list-targeting today/finish steps
        // can't open a task through the spotlight hole.
        assertFalse(tourCutoutInteractive(ESSENTIAL_STEPS.first { it.id == "today" }))
        assertFalse(tourCutoutInteractive(ESSENTIAL_STEPS.first { it.id == "finish" }))
        assertFalse(tourCutoutInteractive(ESSENTIAL_STEPS.first { it.id == "focus" }))
        assertFalse(tourCutoutInteractive(ESSENTIAL_STEPS.first { it.id == "first-action" }))
    }

    @Test
    fun lockdownPolicyDegradesToTheFullBlockerUnderAFocusTakeover() {
        val today = ESSENTIAL_STEPS.first { it.id == "today" }
        val assistant = ESSENTIAL_STEPS.first { it.id == "assistant" }
        val notifications = ESSENTIAL_STEPS.first { it.id == "notifications" }
        // Normal frames follow the per-step rules — no degrade.
        assertEquals(
            TourLockdownPolicy(consumeInput = true, cutoutInteractive = false, degradeToFullBlocker = false),
            tourLockdownPolicy(today, openSection = null, overlayAboveTour = false),
        )
        assertEquals(
            TourLockdownPolicy(consumeInput = true, cutoutInteractive = true, degradeToFullBlocker = false),
            tourLockdownPolicy(assistant, openSection = null, overlayAboveTour = false),
        )
        assertEquals(
            TourLockdownPolicy(consumeInput = false, cutoutInteractive = false, degradeToFullBlocker = false),
            tourLockdownPolicy(notifications, openSection = SettingsSection.FOCUS, overlayAboveTour = false),
        )
        // A Focus takeover above the anchored surface: EVERY step degrades to
        // the whisper scrim + one full-screen blocker — no stale hole can leak
        // taps into the takeover; only the panel stays interactive.
        for (s in FULL_STEPS + ESSENTIAL_STEPS) {
            assertEquals(
                TourLockdownPolicy(consumeInput = true, cutoutInteractive = false, degradeToFullBlocker = true),
                tourLockdownPolicy(s, openSection = SettingsSection.FOCUS, overlayAboveTour = true),
            )
        }
    }

    @Test
    fun blockerRectsSurroundTheCutout() {
        val cut = androidx.compose.ui.geometry.Rect(100f, 200f, 300f, 400f)
        val rects = tourBlockerRects(cut, screenW = 1000f, screenH = 2000f)
        assertEquals(4, rects.size)
        val (above, left, right, below) = rects
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 1000f, 200f), above)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 200f, 100f, 400f), left)
        assertEquals(androidx.compose.ui.geometry.Rect(300f, 200f, 1000f, 400f), right)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 400f, 1000f, 2000f), below)
        // Full coverage outside the cut-out; the cut-out itself stays open.
        assertTrue(rects.none { it.overlaps(androidx.compose.ui.geometry.Rect(101f, 201f, 299f, 399f)) })
    }

    @Test
    fun noTargetBlocksTheWholeScreen() {
        val rects = tourBlockerRects(null, screenW = 1000f, screenH = 2000f)
        assertEquals(listOf(androidx.compose.ui.geometry.Rect(0f, 0f, 1000f, 2000f)), rects)
    }

    @Test
    fun blockerRectsClampOffscreenCutoutsAndDropSlivers() {
        // A cut-out flush with the left edge → no zero-width left sliver.
        val flush = tourBlockerRects(androidx.compose.ui.geometry.Rect(-10f, 200f, 300f, 400f), 1000f, 2000f)
        assertEquals(3, flush.size)
        // A cut-out spanning the whole screen → nothing to block.
        assertEquals(0, tourBlockerRects(androidx.compose.ui.geometry.Rect(-5f, -5f, 1005f, 2005f), 1000f, 2000f).size)
        // Degenerate screen → no blockers (never a crash).
        assertEquals(0, tourBlockerRects(null, 0f, 0f).size)
    }

    /* ── round-2 #5: pause confirm + resume chip ────────────────────────── */

    @Test
    fun backWhileRunningArmsThenConfirms() {
        // First back arms the inline confirm — never straight to the app…
        assertEquals(TourBackAction.ARM_PAUSE_CONFIRM, tourBackWhileRunning(confirmArmed = false))
        // …a second back (confirm up) confirms the pause.
        assertEquals(TourBackAction.CONFIRM_PAUSE, tourBackWhileRunning(confirmArmed = true))
    }

    @Test
    fun resumeChipShowsForPausedAndStrandedRuns() {
        val paused = TourState(started = true, paused = true, mode = TourMode.ESSENTIAL, index = 3)
        assertTrue(showResumeChip(paused))
        // A run STRANDED by process death mid-run (started, not paused, not
        // done) is chip-eligible at boot — tap resumes at the saved index.
        // (The chip renders only in the host's DISMISSED phase, so a LIVE
        // RUNNING tour can never show it.)
        assertTrue(showResumeChip(TourState(started = true, mode = TourMode.ESSENTIAL, index = 3)))
        // ✕-dismissed → gone for good.
        assertFalse(showResumeChip(paused.copy(chipDismissed = true)))
        assertFalse(showResumeChip(TourState(started = true, mode = TourMode.ESSENTIAL, chipDismissed = true)))
        // Finished tour → no chip.
        assertFalse(showResumeChip(paused.copy(done = true)))
        // A soft-dismissed welcome (never started) → no chip.
        assertFalse(showResumeChip(TourState(eligible = true)))
        // No real run to resume (no mode / never started) → no chip.
        assertFalse(showResumeChip(TourState(paused = true)))
        assertFalse(showResumeChip(TourState(started = true, paused = true, mode = null)))
    }

    /* ── round-3: the DEMO capture sheet ────────────────────────────────── */

    @Test
    fun demoCapturePillIsLiveOnlyOnTheCaptureStep() {
        // The pill opens the DEMO sheet ONLY on the capture step — on every
        // other step (including focus, which shows the SAME demo surface) it
        // stays inert.
        assertEquals(
            listOf("capture"),
            (FULL_STEPS + ESSENTIAL_STEPS).filter { tourDemoCapturePillEnabled(it) }.map { it.id },
        )
        assertFalse(tourDemoCapturePillEnabled(ESSENTIAL_STEPS.first { it.id == "focus" }))
    }

    @Test
    fun pillTapOpensTheSheetOnlyWhileRunningOnCapture() {
        val capture = ESSENTIAL_STEPS.first { it.id == "capture" }
        // Closed until the pill records the presenting step…
        assertFalse(tourDemoSheetVisible(openedForStep = null, step = capture, running = true))
        // …then a pill tap (openedForStep = the capture step) shows it.
        assertTrue(tourDemoSheetVisible(openedForStep = "capture", step = capture, running = true))
        // Pause / exit (tour no longer running) closes it.
        assertFalse(tourDemoSheetVisible(openedForStep = "capture", step = capture, running = false))
    }

    @Test
    fun stepChangeClosesTheDemoSheet() {
        val focus = ESSENTIAL_STEPS.first { it.id == "focus" }
        val reentry = ESSENTIAL_STEPS.first { it.id == "reentry" }
        // Advancing (or stepping back) off the capture step derives it closed…
        assertFalse(tourDemoSheetVisible(openedForStep = "capture", step = reentry, running = true))
        assertFalse(tourDemoSheetVisible(openedForStep = "capture", step = focus, running = true))
        // …and no non-capture step can ever present it, even with a matching id.
        assertFalse(tourDemoSheetVisible(openedForStep = "reentry", step = reentry, running = true))
    }

    @Test
    fun saveClosesTheSheetWithoutPersisting() {
        // The host's save handler ONLY clears openedForStep (plus the demo
        // "Saved — demo" flash) — TourDemoCaptureSheet has no store / view-model
        // / network access by construction, so there is nothing TO persist.
        val capture = ESSENTIAL_STEPS.first { it.id == "capture" }
        assertTrue(tourDemoSheetVisible(openedForStep = "capture", step = capture, running = true))
        val afterSave: String? = null   // onSave → demoSheetForStep = null
        assertFalse(tourDemoSheetVisible(openedForStep = afterSave, step = capture, running = true))
    }

    @Test
    fun backClosesTheDemoSheetNotTheTour() {
        // Sheet open: back closes ONLY the sheet — never arms/confirms a pause.
        assertEquals(TourBackAction.CLOSE_DEMO_SHEET, tourBackWhileRunning(confirmArmed = false, demoSheetOpen = true))
        // Even an armed pause-confirm yields — the sheet is the topmost modal.
        assertEquals(TourBackAction.CLOSE_DEMO_SHEET, tourBackWhileRunning(confirmArmed = true, demoSheetOpen = true))
        // Sheet closed: the round-2 arm → confirm semantics are untouched.
        assertEquals(TourBackAction.ARM_PAUSE_CONFIRM, tourBackWhileRunning(confirmArmed = false, demoSheetOpen = false))
        assertEquals(TourBackAction.CONFIRM_PAUSE, tourBackWhileRunning(confirmArmed = true, demoSheetOpen = false))
    }

    /* ── Tell-me-more → narration hand-back (#4) ────────────────────────── */

    @Test
    fun handbackNeverRewindsOffStaleMoreProgress() {
        // The MORE clip finished (the shared progress hit ≥1) but the
        // NARRATION didn't: the hand-back resumes mid-position — never an
        // auto-rewind restart from 0:00.
        assertFalse(tourNarrationShouldRewind(moreActive = false, narrationFinished = false))
        // While the more clip is the active clip the narration never rewinds.
        assertFalse(tourNarrationShouldRewind(moreActive = true, narrationFinished = true))
        assertFalse(tourNarrationShouldRewind(moreActive = true, narrationFinished = false))
        // Only a genuinely finished narration replays from the top.
        assertTrue(tourNarrationShouldRewind(moreActive = false, narrationFinished = true))
    }

    /* ── pause-confirm footer priority (#7) ─────────────────────────────── */

    @Test
    fun pauseConfirmFloorsThePanelHeightCap() {
        // At the collapsed floor the ARMED confirm raises the cap so its
        // buttons + Settings-path line never clip…
        assertEquals(TOUR_PAUSE_CONFIRM_MIN_PANEL_HEIGHT, tourPanelMaxHeight(minCollapsed1.dp, pauseConfirmArmed = true))
        // …a roomy cap is untouched…
        assertEquals(600.dp, tourPanelMaxHeight(600.dp, pauseConfirmArmed = true))
        // …and the un-armed footer keeps the host's geometric cap exactly.
        assertEquals(minCollapsed1.dp, tourPanelMaxHeight(minCollapsed1.dp, pauseConfirmArmed = false))
        // The floor clears header (55dp) + a title line + the confirm footer's
        // intrinsic (~129dp).
        assertTrue(TOUR_PAUSE_CONFIRM_MIN_PANEL_HEIGHT >= 226.dp)
    }

    @Test
    fun pauseConfirmNeverGrowsAnAlreadyExpandedPanelOverTheRing() {
        // Worked example: a ring whose top leaves EXACTLY the readable minimum
        // above it. The panel is expanded there, its body scrolling under the
        // cap — and arming the confirm (a Pause tap, or the back gesture) must
        // NOT push the cap past that, or the panel grows out over the ring.
        // A flat 280dp floor did exactly that from every cap in [240, 280).
        val cap = TOUR_PANEL_READABLE_MIN_HEIGHT
        assertEquals(cap, tourPanelMaxHeight(cap, pauseConfirmArmed = true))
        // The invariant behind it, at every font scale the panel can see: the
        // confirm needs LESS than a readable body (it trades three lines of
        // step copy for its own buttons), so the floor can only ever lift a
        // panel that is already collapsed.
        for (fs in listOf(1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f, 3f)) {
            assertTrue("fontScale $fs", tourPauseConfirmMinDp(fs) <= tourPanelReadableMinDp(fs))
            assertTrue("fontScale $fs", tourPauseConfirmMinDp(fs) > tourPanelCollapsedMinDp(fs))
        }
        // …and it still lifts a collapsed one: at the floor the confirm fits.
        assertTrue(tourPanelMaxHeight(minCollapsed1.dp, pauseConfirmArmed = true) > minCollapsed1.dp)
    }
}
