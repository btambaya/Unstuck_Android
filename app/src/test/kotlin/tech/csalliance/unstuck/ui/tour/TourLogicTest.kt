package tech.csalliance.unstuck.ui.tour

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
    fun focusStepsRingTheBeginAffordanceWithTodayFallbacks() {
        for (id in listOf("focus", "capture")) {
            val s = ESSENTIAL_STEPS.first { it.id == id }
            assertEquals(TourAnchorIds.FOCUS_BEGIN, s.target)
            assertEquals(listOf(TourAnchorIds.START_NEXT, TourAnchorIds.BACKLOG_POINTER, TourAnchorIds.TODAY_LIST), s.fallbacks)
        }
    }

    @Test
    fun todayAndFinishFallbackChain() {
        for (id in listOf("today", "finish")) {
            val s = ESSENTIAL_STEPS.first { it.id == id }
            assertEquals(TourAnchorIds.START_NEXT, s.target)
            assertEquals(listOf(TourAnchorIds.BACKLOG_POINTER, TourAnchorIds.TODAY_LIST), s.fallbacks)
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
        // …and a paused run's resume card (a paused tour must not be
        // unreachable until process death).
        assertEquals(TourEntryPhase.PAUSED, dormantResurface(TourState(started = true, paused = true, mode = TourMode.ESSENTIAL)))
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

    @Test
    fun placementSubtractsDockSideInsetsBeforeTheCollapseDecision() {
        // Ring 100..600 on 2000px, docked BOTTOM: raw space below = 1400. The
        // panel is laid out inside the bottom inset (100) + margins (24), so
        // only 1276 is really available — with a 1300px expanded panel that
        // MUST collapse (the raw 1376 would have said "expanded" and overlapped
        // the ring by the inset height).
        val p = panelPlacement(
            ringTop = 100f, ringBottom = 600f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 100f, marginPx = 12f,
            expandedHeightPx = 1300f, collapsedMinPx = 400f,
        )
        assertEquals(PanelDock.BOTTOM, p.dock)
        assertEquals(1276f, p.availablePx)
        assertTrue(p.collapsed)
    }

    @Test
    fun keyboardUpRerunsTheDecisionAndCanCollapse() {
        // Same ring, keyboard down: plenty of space below → expanded BOTTOM.
        val down = panelPlacement(
            ringTop = 200f, ringBottom = 700f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 60f, marginPx = 12f,
            expandedHeightPx = 360f, collapsedMinPx = 150f,
        )
        assertEquals(PanelDock.BOTTOM, down.dock)
        assertFalse(down.collapsed)
        // Keyboard up (bottom inset includes the 1000px IME): the same dock
        // decision re-runs and collapses instead of covering the ring.
        val up = panelPlacement(
            ringTop = 200f, ringBottom = 700f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 1000f, marginPx = 12f,
            expandedHeightPx = 360f, collapsedMinPx = 150f,
        )
        assertEquals(PanelDock.BOTTOM, up.dock)
        assertEquals(276f, up.availablePx)
        assertTrue(up.collapsed)
    }

    @Test
    fun flipNotCapWhenEvenTheCollapsedMinimumCannotFit() {
        // Ring 200..1500 (center in the top half → prefer BOTTOM), but the IME
        // leaves only 76px below — less than the 150px collapsed minimum. The
        // old 150dp floor would have capped OVER the ring; the rule now FLIPS
        // to the top, where 176px fits the collapsed panel outside the ring.
        val p = panelPlacement(
            ringTop = 200f, ringBottom = 1500f, screenHeightPx = 2000f,
            topInsetPx = 0f, bottomInsetPx = 400f, marginPx = 12f,
            expandedHeightPx = 360f, collapsedMinPx = 150f,
        )
        assertEquals(PanelDock.TOP, p.dock)
        assertEquals(176f, p.availablePx)
        assertTrue(p.collapsed)
        // The cap never exceeds the space outside the ring on the flipped side.
        assertTrue(p.availablePx <= 200f - 12f * 2)
    }

    @Test
    fun impossibleOnBothSidesTakesTheRoomierSideWithTheUsabilityFloor() {
        // A ring spanning nearly the whole usable screen: neither side fits
        // even the collapsed minimum — the roomier side wins, floored so the
        // panel stays usable (the single unavoidable-overlap case).
        val p = panelPlacement(
            ringTop = 60f, ringBottom = 1800f, screenHeightPx = 2000f,
            topInsetPx = 0f, bottomInsetPx = 150f, marginPx = 12f,
            expandedHeightPx = 360f, collapsedMinPx = 150f,
        )
        assertEquals(PanelDock.TOP, p.dock)   // 36px above beats 26px below
        assertEquals(150f, p.availablePx)     // usability floor
        assertTrue(p.collapsed)
    }

    @Test
    fun askFocusedPrefersTopAndSuppressesTheCollapse() {
        // Target in the TOP half would normally dock BOTTOM — but with the Ask
        // field focused (keyboard up) the panel prefers TOP and must never
        // collapse (the field lives in the body).
        val p = panelPlacement(
            ringTop = 300f, ringBottom = 700f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 900f, marginPx = 12f,
            expandedHeightPx = 360f, collapsedMinPx = 150f,
            askFocused = true,
        )
        assertEquals(PanelDock.TOP, p.dock)
        assertFalse(p.collapsed)
        // Even in a tight top the collapse stays suppressed (floored, usable).
        val tight = panelPlacement(
            ringTop = 100f, ringBottom = 700f, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 900f, marginPx = 12f,
            expandedHeightPx = 360f, collapsedMinPx = 150f,
            askFocused = true,
        )
        assertEquals(PanelDock.TOP, tight.dock)
        assertFalse(tight.collapsed)
        assertEquals(150f, tight.availablePx)
    }

    @Test
    fun noRingPlacementUsesTheInsetAdjustedScreen() {
        val p = panelPlacement(
            ringTop = null, ringBottom = null, screenHeightPx = 2000f,
            topInsetPx = 80f, bottomInsetPx = 100f, marginPx = 12f,
            expandedHeightPx = 360f, collapsedMinPx = 150f,
        )
        assertEquals(PanelDock.BOTTOM, p.dock)
        assertEquals(2000f - 100f - 24f, p.availablePx)
        assertFalse(p.collapsed)
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
        // Every step in both modes maps to a name in the bundled set.
        val bundled = setOf(
            "tour_welcome", "tour_today", "tour_first_action", "tour_assistant", "tour_focus",
            "tour_capture", "tour_reentry", "tour_notifications", "tour_finish", "tour_calendar",
            "tour_captures", "tour_collections", "tour_sharing", "tour_insights", "tour_personalization",
        )
        for (s in FULL_STEPS + ESSENTIAL_STEPS) assertTrue("missing clip for ${s.id}", tourAudioResName(s.id) in bundled)
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
            assertTrue("no raw resource wired for ${s.id}", tourAudioRes(s.id) != 0)
        }
        assertEquals(0, tourAudioRes("nope"))
    }
}
