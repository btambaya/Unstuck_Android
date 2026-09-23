package tech.csalliance.unstuck.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.InterviewFlag
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.LocalDate
import java.time.ZoneId

/**
 * The gateway card's pure half (GatewayLogic.kt): the moment-action reducer,
 * the minute-keyed memo, the brief's usable-minutes cap, the struggles
 * canonicalisation and the interview-flag gates the card relies on. Port of
 * the iOS GatewayCardTests + the web gateway reducer cases.
 */
class GatewayCardLogicTest {

    private val today = "2026-09-07"
    private val tomorrow = "2026-09-08"
    private val nowIso = "2026-09-07T08:00:00.000Z"

    private fun task(id: String, name: String = "T", estimateMin: Int = 25, moveCount: Int? = null) = TaskItem(
        id = id, name = name, estimateMin = estimateMin, moveCount = moveCount,
        createdAt = "2026-09-01T10:00:00.000Z", updatedAt = "2026-09-01T10:00:00.000Z",
    )

    private fun block(id: String, taskId: String, date: String, time: String = "10:00", done: Boolean = false, skipped: Boolean = false) =
        CalBlock(id = id, taskId = taskId, taskName = "T", startTime = time, durationMinutes = 30, date = date, kind = CalBlockKind.TASK, done = done, skipped = skipped)

    /** Local wall clock → epoch ms (the engines read LOCAL hours/minutes). */
    private fun at(date: String, h: Int, m: Int): Long =
        LocalDate.parse(date).atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ── carry_tasks ────────────────────────────────────────────────────────

    @Test fun carry_movesTodaysLiveBlockToTomorrowAndBumpsMoveCount() {
        val t = task("a", "Gym", moveCount = 2)
        val w = GatewayActions.carryTasks(listOf("a"), listOf(t), listOf(block("b1", "a", today)), today, tomorrow, nowIso)
        assertEquals("Carried 1 to tomorrow.", w.confirmation)
        assertEquals(tomorrow, w.blocks.single().date)
        assertFalse(w.blocks.single().skipped)
        assertEquals(3, w.tasks.single().moveCount)
        assertEquals(nowIso, w.tasks.single().updatedAt)
    }

    @Test fun carry_skipsTodayWhenTomorrowAlreadyHasAnOccurrence() {
        // A recurring task usually ALREADY has tomorrow's occurrence — moving
        // today's block would double it up; skip today's instead.
        val blocks = listOf(block("b1", "a", today), block("b2", "a", tomorrow))
        val w = GatewayActions.carryTasks(listOf("a"), listOf(task("a")), blocks, today, tomorrow, nowIso)
        val moved = w.blocks.single()
        assertEquals("b1", moved.id)
        assertTrue(moved.skipped)
        assertEquals(today, moved.date)
        assertEquals(1, w.tasks.single().moveCount)
    }

    @Test fun carry_ignoresDoneSkippedAndOtherDayBlocks_nothingToCarryIsNoConfirmation() {
        val blocks = listOf(block("b1", "a", today, done = true), block("b2", "b", today, skipped = true), block("b3", "c", tomorrow))
        val w = GatewayActions.carryTasks(listOf("a", "b", "c", "ghost"), listOf(task("a"), task("b"), task("c")), blocks, today, tomorrow, nowIso)
        assertNull("\"Carried 0 to tomorrow\" was a lie the host showed as ✓", w.confirmation)
        assertTrue(w.blocks.isEmpty())
        assertTrue(w.tasks.isEmpty())
    }

    // ── schedule ───────────────────────────────────────────────────────────

    @Test fun schedule_movesTheSoonestLiveUpcomingBlock_keepingItsTimeWhenNoneGiven() {
        val blocks = listOf(
            block("old", "a", "2026-09-01", "07:00"),            // historical — never the anchor
            block("skip", "a", "2026-09-09", "08:00", skipped = true),
            block("late", "a", "2026-09-12", "11:00"),
            block("soon", "a", "2026-09-10", "14:00"),
        )
        val w = GatewayActions.schedule("a", "2026-09-11", null, listOf(task("a", "Gym")), blocks, today, "new")
        val b = w.blocks.single()
        assertEquals("soon", b.id)
        assertEquals("2026-09-11", b.date)
        assertEquals("14:00", b.startTime)
        assertTrue("booking a habit gap isn't a slip", w.tasks.isEmpty())
        assertEquals("Blocked — Gym, 2026-09-11.", w.confirmation)
    }

    @Test fun schedule_createsAFreshBlockWhenNothingLiveExists_defaultingTo0900() {
        val w = GatewayActions.schedule("a", "2026-09-11", null, listOf(task("a", "Gym", estimateMin = 45)), listOf(block("old", "a", "2026-09-01", done = true)), today, "new")
        val b = w.blocks.single()
        assertEquals("new", b.id)
        assertEquals("09:00", b.startTime)
        assertEquals(45, b.durationMinutes)
        assertEquals(CalBlockKind.TASK, b.kind)
        assertEquals("Gym", b.taskName)
    }

    @Test fun schedule_withATimeReportsIt() {
        val w = GatewayActions.schedule("a", "2026-09-11", "18:30", listOf(task("a", "Gym")), emptyList(), today, "new")
        assertEquals("18:30", w.blocks.single().startTime)
        assertEquals("Blocked — Gym, 2026-09-11 18:30.", w.confirmation)
    }

    /** Stage 2 (same id for same day, Ahmad 2026-09-23): a series' first placement
     *  from a moment mints the day's deterministic occurrence — written
     *  insert-if-absent, never a random-id twin of another device's day. */
    @Test fun schedule_aSeriesFirstPlacementMintsTheDaysOccurrence() {
        val series = task("a", "Gym").copy(recurrence = tech.csalliance.unstuck.core.model.Recurrence.Daily())
        val w = GatewayActions.schedule("a", "2026-09-11", "07:30", listOf(series), emptyList(), today, "new")
        val minted = w.inserts.single()
        assertEquals(tech.csalliance.unstuck.core.logic.occurrenceId("a", "2026-09-11"), minted.id)
        assertEquals("07:30", minted.startTime)
        assertTrue(w.blocks.isEmpty())
        assertEquals("Blocked — Gym, 2026-09-11 07:30.", w.confirmation)
        // The day's id lives on elsewhere (moved): a block of its own, the moved row untouched.
        val moved = block(tech.csalliance.unstuck.core.logic.occurrenceId("a", "2026-09-11"), "a", "2026-09-01", done = true)
        val again = GatewayActions.schedule("a", "2026-09-11", "07:30", listOf(series), listOf(moved), today, "new")
        assertTrue(again.inserts.isEmpty())
        assertTrue(again.blocks.single().id != moved.id)
        assertEquals("2026-09-11", again.blocks.single().date)
    }

    @Test fun schedule_vanishedTaskWritesNothing() {
        val w = GatewayActions.schedule("ghost", "2026-09-11", null, listOf(task("a")), listOf(block("b1", "ghost", tomorrow)), today, "new")
        assertNull(w.confirmation)
        assertTrue(w.blocks.isEmpty())
    }

    // ── create_task ────────────────────────────────────────────────────────

    @Test fun createTask_defaultsTheEstimateTo25() {
        val w = GatewayActions.createTask("Call mum", null, "id1", nowIso)
        val t = w.tasks.single()
        assertEquals("id1", t.id)
        assertEquals(25, t.estimateMin)
        assertFalse(t.done)
        assertEquals(nowIso, t.createdAt)
        assertEquals("Added “Call mum”.", w.confirmation)
        assertEquals(40, GatewayActions.createTask("X", 40, "id2", nowIso).tasks.single().estimateMin)
    }

    // ── memo ───────────────────────────────────────────────────────────────

    private fun inputs(minute: Long, dismissed: Set<String> = emptySet()) = GatewayInputs(
        tasks = listOf(task("a")), blocks = emptyList(), sessions = emptyList(), reasons = emptyList(),
        facts = emptyList(), struggles = emptyList(), rituals = RitualPrefs.DEFAULTS, dismissed = dismissed,
        todayIso = today, minute = minute,
    )

    @Test fun memo_recomputesOnlyWhenAnInputChanges() {
        val memo = GatewayMemo<GatewayInputs, Int>()
        var runs = 0
        val k1 = inputs(minute = 100)
        assertEquals(1, memo.value(k1) { ++runs })
        assertEquals(1, memo.value(inputs(minute = 100)) { ++runs })   // equal key, different instance → cached
        assertEquals(1, memo.computeCount)
        assertEquals(2, memo.value(inputs(minute = 101)) { ++runs })   // the minute moved → recompute
        assertEquals(3, memo.value(inputs(minute = 101, dismissed = setOf("x"))) { ++runs })
        assertEquals(3, memo.computeCount)
    }

    @Test fun memo_keyIsTheMinuteNotTheInstant() {
        val base = 1_700_000_040_000L   // on a minute boundary
        assertEquals(GatewayInputs.minute(base), GatewayInputs.minute(base + 59_999))
        assertEquals(GatewayInputs.minute(base) + 1, GatewayInputs.minute(base + 60_000))
    }

    // ── brief + moment derivation ──────────────────────────────────────────

    @Test fun brief_usableMinutesIsTheGapToTheAnchorCappedByUsableTime() {
        // 09:00 now; a 30-min block at 11:00 → usable today = 30 (one task block),
        // gap = 120 → the line reports min(30, 120) = 30, not the day's total.
        val blocks = listOf(block("b1", "a", today, "11:00"))
        val line = gatewayBriefLine(listOf(task("a", "Write the update")), blocks, today, at(today, 9, 0))
        assertEquals("One thing scheduled today — ‘Write the update’ at 11:00 is the anchor. About 30 usable minutes before it.", line)
    }

    @Test fun brief_noRunwaySentenceOnceTheAnchorIsBehindUs() {
        val blocks = listOf(block("b1", "a", today, "11:00"))
        val line = gatewayBriefLine(listOf(task("a", "Write the update")), blocks, today, at(today, 12, 0))
        assertEquals("One thing scheduled today — ‘Write the update’ at 11:00 is the anchor.", line)
    }

    @Test fun derive_mapsFactsAndRitualsOntoTheEngineAndNeverThrows() {
        val fact = ProfileFact(id = "f1", category = ProfileFactCategory.PREFERENCE, fact = "Wants to be kept honest — direct nudges are welcome",
            source = ProfileFactSource.INTERVIEW, createdAt = nowIso, updatedAt = nowIso)
        val tombstone = fact.copy(id = "f2", active = false, fact = "gone")
        val key = inputs(minute = 1).copy(facts = listOf(fact, tombstone))
        val state = gatewayMomentState(key, at(today, 9, 0))
        assertEquals(listOf("f1"), state.facts.map { it.id })
        assertEquals("preference", state.facts.single().category)
        assertEquals(today, state.todayIso)
        assertFalse(state.isDismissed("anything"))
        assertTrue(gatewayMomentState(key.copy(dismissed = setOf("m1")), 0L).isDismissed("m1"))
        val r = RitualPrefs(morning = false, evening = true, friday = true, sunday = false).toMomentRituals()
        assertFalse(r.morning); assertTrue(r.evening); assertTrue(r.friday); assertFalse(r.sunday)
        val d = deriveGateway(key, at(today, 9, 0))
        assertEquals("Nothing on the calendar today — 1 open task if you want to pull one in.", d.brief)
        assertNotNull(d)   // a moment (or none) — never an exception out of the card
    }

    // ── struggles ──────────────────────────────────────────────────────────

    @Test fun canonicalStruggles_mapsAndroidsLegacyPickerLabelsKeepingOrderAndDroppingUnknowns() {
        assertEquals(
            listOf("Starting", "Switching", "Sustaining", "Stopping"),
            canonicalStruggles(listOf("Getting started", "Switching tasks", "Distraction", "Time blindness", "Overwhelm", "Something else", " starting ")),
        )
        assertEquals(listOf("Recovering"), canonicalStruggles(listOf("recovering", "RECOVERING")))
        assertTrue(canonicalStruggles(emptyList()).isEmpty())
    }

    // ── interview flag + auto-open gate ────────────────────────────────────

    @Test fun interviewFlag_apply_serverDoneWinsAndClosesAnOpenPanel() {
        assertEquals(InterviewFlag.State(done = true, open = false), InterviewFlag.apply(serverDone = true, done = false, open = true))
        assertEquals(InterviewFlag.State(done = false, open = true), InterviewFlag.apply(serverDone = false, done = false, open = true))
        assertEquals(InterviewFlag.State(done = true, open = false), InterviewFlag.apply(serverDone = false, done = true, open = false))
    }

    @Test fun chips_andCopy_areTheReferenceStrings() {
        assertEquals(listOf("✦ Plan my day", "Brain-dump", "What’s this week?"), GATEWAY_CHIPS.map { it.label })
        assertEquals("Plan my day — what should I start with and what order makes sense?", GATEWAY_CHIPS[0].message)
        assertEquals("I want to brain-dump everything on my mind — ready?", GATEWAY_CHIPS[1].message)
        assertEquals("What have I got coming up this week?", GATEWAY_CHIPS[2].message)
        assertEquals("Ask me anything — or hand me your whole day…", GATEWAY_PLACEHOLDER)
        assertEquals("✦ Personalise your assistant — two minutes, skip anything", GATEWAY_INTERVIEW_PILL)
    }
}
