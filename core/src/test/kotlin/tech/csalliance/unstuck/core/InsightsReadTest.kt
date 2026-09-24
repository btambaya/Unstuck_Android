package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.INSIGHTS_MAX_CHARS
import tech.csalliance.unstuck.core.logic.InsightsWindow
import tech.csalliance.unstuck.core.logic.renderInsights
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.DAY_MS

// Ported from lib/assistant/insights-read.test.ts — the synthetic week,
// number for number. Local construction throughout; the suite runs TZ=UTC.
class InsightsReadTest {

    private val AUG = 8
    private val SEP = 9

    // Wed 2 Sep 2026, 18:00 local → the 'week' window is Mon 31 Aug 00:00 → now.
    private val IR_NOW: Long = localMillis(2026, 9, 2, 18, 0)
    private fun ago(days: Double): String = iso(IR_NOW - (days * DAY_MS).toLong())

    private var seq = 0
    private fun nextSeq(): Int { seq += 1; return seq }

    private fun tsk(id: String, name: String, estimateMin: Int = 30, done: Boolean = false, moveCount: Int? = null,
                    createdAt: String? = null, lifeArea: String? = null) =
        TaskItem(id = id, name = name, estimateMin = estimateMin, totalFocused = 0, done = done, lifeArea = lifeArea,
            moveCount = moveCount, createdAt = createdAt ?: ago(3.0), updatedAt = ago(3.0))

    /** A session on `t` STARTING at the local month/day/hour/minute, ending actualSec later. */
    private fun sessOn(t: TaskItem, month: Int, day: Int, hour: Int, min: Int, actualSec: Int, id: String? = null): Session {
        val start = localMillis(2026, month, day, hour, min)
        return Session(id = id ?: "s${nextSeq()}", taskId = t.id, taskName = t.name, estimateMin = t.estimateMin,
            actualSec = actualSec, completedAt = iso(start + actualSec * 1000L))
    }

    private fun log(reason: String, month: Int, day: Int, hour: Int, min: Int, action: ReasonAction = ReasonAction.PAUSE, durationSec: Int? = null) =
        ReasonLog(id = "r${nextSeq()}", reason = reason, action = action, at = iso(localMillis(2026, month, day, hour, min)), durationSec = durationSec)

    private fun capture(tag: CaptureTag, month: Int, day: Int, hour: Int, min: Int, sessionId: String? = null) =
        Capture(id = "c${nextSeq()}", sessionId = sessionId, tag = tag, body = "note", at = iso(localMillis(2026, month, day, hour, min)))

    private fun blk(date: String, durationMinutes: Int, taskId: String? = "a", taskName: String = "Write report",
                    kind: CalBlockKind? = null, externalEventId: String? = null, skipped: Boolean = false) =
        CalBlock(id = "b${nextSeq()}", taskId = taskId, taskName = taskName, startTime = "09:00", durationMinutes = durationMinutes,
            date = date, externalEventId = externalEventId, kind = kind, skipped = skipped)

    private data class Data(
        val tasks: List<TaskItem> = emptyList(),
        val sessions: List<Session> = emptyList(),
        val captures: List<Capture> = emptyList(),
        val reasons: List<ReasonLog> = emptyList(),
        val blocks: List<CalBlock> = emptyList(),
        val now: Long? = null,
    )

    private fun render(d: Data = Data(), window: InsightsWindow = InsightsWindow.WEEK): String =
        renderInsights(d.tasks, d.sessions, d.captures, d.reasons, d.blocks, d.now ?: IR_NOW, window)

    // ---- the synthetic week

    private val writeReport = tsk("a", "Write report", lifeArea = "Work")
    private val gym = tsk("b", "Gym", estimateMin = 20, lifeArea = "Health")
    private val taxReturn = tsk("c", "Tax return", estimateMin = 60, moveCount = 4, createdAt = ago(10.0))
    private val dentist = tsk("d", "Dentist", done = true, moveCount = 5, createdAt = ago(40.0))
    private val TASKS = listOf(writeReport, gym, taxReturn, dentist)

    // estimate → actual: 30→30 hit, 30→45 over, 30→10 under, 20→20 hit, 20→60 over.
    private val s1 = sessOn(writeReport, AUG, 31, 9, 0, 1800, "s1")      // Mon, ends 09:30
    private val s2 = sessOn(writeReport, SEP, 1, 9, 0, 2700, "s2")       // Tue, ends 09:45
    private val s5 = sessOn(writeReport, SEP, 1, 9, 48, 600, "s5")       // Tue, 3-min re-entry, ends 09:58
    private val s3 = sessOn(gym, SEP, 1, 14, 0, 1200, "s3")              // Tue, ends 14:20
    private val s4 = sessOn(gym, SEP, 2, 10, 0, 3600, "s4")              // Wed, ends 11:00
    private val SESSIONS = listOf(s1, s2, s3, s4, s5)

    private val REASONS = listOf(
        log("phone call", SEP, 1, 10, 0, durationSec = 300),
        log("phone call", SEP, 2, 11, 0, durationSec = 420),
        log("snack", SEP, 1, 14, 5, durationSec = 180),
        log("email", AUG, 31, 9, 10, action = ReasonAction.SWITCH),        // legacy row: no duration
    )

    private val CAPTURES = listOf(
        capture(CaptureTag.DISTRACTION, SEP, 2, 10, 7, "s4"),           // 7 min into s4
        capture(CaptureTag.FOLLOW_UP, SEP, 1, 9, 8, "s2"),              // 8 min into s2
        capture(CaptureTag.IDEA, SEP, 1, 16, 0),                        // not tied to a session
    )

    private val BLOCKS = listOf(
        blk("2026-09-01", 60),
        blk("2026-09-02", 30, taskId = "b", taskName = "Gym"),
        blk("2026-08-28", 45),                              // before Monday
        blk("2026-09-03", 30),                              // tomorrow
        blk("2026-09-01", 60, kind = CalBlockKind.EXTERNAL, externalEventId = "ev1"),
        blk("2026-09-02", 15, skipped = true),
    )

    private val WEEK = Data(tasks = TASKS, sessions = SESSIONS, captures = CAPTURES, reasons = REASONS, blocks = BLOCKS)

    @Test fun `renders the known report`() {
        assertEquals(
            listOf(
                "ok: Insights, week so far (Mon 31 Aug – Wed 2 Sep).",
                "Focus: 2h 45m across 5 sessions, median 30m.",
                "By area: Work 1.4h, Health 1.3h.",
                "Peak slot: Wed 10am–12pm (60 min).",
                "Estimates: 40% of 5 estimated sessions landed within 5 min; 2 ran over, 1 ran under; actual vs estimate averages +7 min. Verdict: underestimating (things take longer than you plan).",
                "Pauses: 4 reasons logged; top: phone call 2x (12m), snack 1x (3m), email 1x.",
                "Coming back: 33% of 3 timed pauses ended within 5 min.",
                "Slipping: 1 task — \"Tax return\" (moved 4x, 1wk on list).",
                "Captures: 3 — follow-up 1, idea 1, distraction 1.",
                "Planned: 2 calendar blocks (1h 30m) dated in this window.",
                "Worth noticing:",
                "- Tuesdays are your strongest day. 75 focused minutes — more than any other day this window. Stack harder work here.",
                "- Estimates within 5 min 40% of the time. 5 recent sessions tracked — estimates are still settling. The calibration card shows where outliers landed.",
                "- \"Tax return\" keeps slipping. rescheduled 4 times. Remove it, or break it down differently?",
            ).joinToString("\n"),
            render(WEEK),
        )
    }

    @Test fun `is plain text within budget, no markdown, no NaN leaks`() {
        val out = render(WEEK)
        assertTrue(out.startsWith("ok:"))
        assertTrue(out.length <= INSIGHTS_MAX_CHARS)
        assertFalse(out.contains("**"))
        assertFalse(out.contains("\n#"))
        assertFalse(out.contains("NaN"))
        assertFalse(out.contains("null"))
    }

    // ---- empty data

    @Test fun `says so in one line per section, nothing fabricated`() {
        assertEquals(
            listOf(
                "ok: Insights, week so far (Mon 31 Aug – Wed 2 Sep).",
                "Focus: no focus sessions in this window.",
                "Pauses: none logged in this window.",
                "Slipping: none.",
                "Captures: none.",
            ).joinToString("\n"),
            render(),
        )
    }

    @Test fun `sessions with no task link get an honest estimates line`() {
        val orphan = Session(id = "x", taskName = "Ad hoc", actualSec = 900, completedAt = ago(1.0))
        val out = render(Data(sessions = listOf(orphan)))
        assertTrue(out.contains("Focus: 15m across 1 session, median 15m."))
        assertTrue(out.contains("Estimates: no sessions linked to an estimated task yet."))
        assertFalse(out.contains("Re-entry"))
        assertFalse(out.contains("Worth noticing"))
    }

    // ---- windows

    // Sat 29 Aug: before this week's Monday AND before the 1st of September.
    private val saturday = sessOn(writeReport, AUG, 29, 10, 0, 1800, "sat")
    private val oldLog = log("old thing", AUG, 20, 9, 0, durationSec = 600)
    private val windowData = WEEK.copy(sessions = SESSIONS + saturday, reasons = REASONS + oldLog)

    @Test fun `week excludes a session and a reason log from last Saturday`() {
        val out = render(windowData, InsightsWindow.WEEK)
        assertTrue(out.contains("Focus: 2h 45m across 5 sessions"))
        assertTrue(out.contains("Pauses: 4 reasons logged"))
        assertFalse(out.contains("old thing"))
    }

    @Test fun `month excludes them too`() {
        val out = render(windowData, InsightsWindow.MONTH)
        assertTrue(out.contains("ok: Insights, month so far (Tue 1 Sep – Wed 2 Sep)."))
        // Monday 31 Aug is still August → its session AND its reason log drop
        // out of the month window along with Saturday's.
        assertTrue(out.contains("Focus: 2h 15m across 4 sessions"))
        assertTrue(out.contains("Pauses: 3 reasons logged"))
        assertFalse(out.contains("email"))
        assertFalse(out.contains("old thing"))
    }

    @Test fun `all includes everything and counts every block up to today`() {
        val out = render(windowData, InsightsWindow.ALL)
        assertTrue(out.contains("ok: Insights, all time (to Wed 2 Sep)."))
        assertTrue(out.contains("Focus: 3h 15m across 6 sessions"))
        // pauseAnatomy orders by real minutes, then count.
        assertTrue(out.contains("Pauses: 5 reasons logged; top: phone call 2x (12m), old thing 1x (10m), snack 1x (3m)."))
        assertTrue(out.contains("Planned: 3 calendar blocks (2h 15m)"))
    }

    // ---- slipping

    @Test fun `surfaces move count and age, skips done tasks and two moves`() {
        val tasks = listOf(
            tsk("x", "Renew passport", moveCount = 3, createdAt = ago(1.0)),
            tsk("y", "Water plants", moveCount = 2, createdAt = ago(1.0)),
            tsk("z", "Old but done", done = true, moveCount = 6, createdAt = ago(60.0)),
            tsk("w", "Sort the garage", createdAt = ago(30.0)),
        )
        val out = render(Data(tasks = tasks))
        assertTrue(out, out.contains("Slipping: 2 tasks — \"Renew passport\" (moved 3x, 0wk on list); \"Sort the garage\" (moved 0x, 4wk on list)."))
        assertFalse(out.contains("Water plants"))
        assertFalse(out.contains("Old but done"))
        // The narrative card names the worst offender even with zero sessions.
        assertTrue(out.contains("- \"Renew passport\" keeps slipping. rescheduled 3 times."))
    }

    @Test fun `lists at most five names and counts the rest`() {
        val tasks = (0 until 8).map { i -> tsk("t$i", "Chore $i", moveCount = 3 + i, createdAt = ago(1.0)) }
        val out = render(Data(tasks = tasks))
        // The TRUE count (slipping() no longer caps at 6) — the same count the Report card shows.
        assertTrue(out, out.contains("Slipping: 8 tasks — \"Chore 7\" (moved 10x, 0wk on list); \"Chore 6\""))
        assertTrue(out, out.contains("\"Chore 3\" (moved 6x, 0wk on list) +3 more."))
        assertFalse(out.contains("\"Chore 2\""))
    }

    // ---- analytics fixes 2026-09-24

    @Test fun `by area uses the user's own areas plus No area, like the screen`() {
        val custom = tsk("k", "Choir", lifeArea = "Music")
        val none = tsk("n", "Loose end")
        val sessions = listOf(
            sessOn(custom, SEP, 1, 9, 0, 3600, "m1"),
            sessOn(none, SEP, 1, 11, 0, 1800, "m2"),
            Session(id = "m3", taskName = "Ad hoc", actualSec = 1800, completedAt = iso(localMillis(2026, 9, 1, 13, 30))),
        )
        val out = renderInsights(listOf(custom, none), sessions, emptyList(), emptyList(), emptyList(), IR_NOW, InsightsWindow.WEEK, areas = listOf("Music", "Work"))
        assertTrue(out, out.contains("By area: Music 1.0h, No area 1.0h."))
    }

    @Test fun `focus total and median round like the page's Focused card`() {
        // 49m 45s + 49m 50s = 99m 35s: the page (and get_period_review) says
        // 1h 40m; the old floor said "1h 39m", one minute off the screen.
        val t = tsk("a", "Write report", estimateMin = 45, lifeArea = "Work")
        val out = render(Data(tasks = listOf(t), sessions = listOf(sessOn(t, SEP, 1, 9, 0, 49 * 60 + 45, "r1"), sessOn(t, SEP, 1, 11, 0, 49 * 60 + 50, "r2"))))
        assertTrue(out, out.contains("Focus: 1h 40m across 2 sessions, median 50m."))
    }

    @Test fun `accidental starts and forgotten timers are filtered like the screen`() {
        val t = tsk("a", "Write report", lifeArea = "Work")
        val sessions = listOf(
            sessOn(t, SEP, 1, 9, 0, 20, "tiny"),                  // 20 s — never counts
            sessOn(t, SEP, 1, 10, 0, 30 * 3600, "runaway"),      // 30 h, est 30 → counts 90 min
        )
        val out = render(Data(tasks = listOf(t), sessions = sessions))
        assertTrue(out, out.contains("Focus: 1h 30m across 1 session, median 1h 30m."))
    }

    @Test fun `repeating series and Later tasks never slip`() {
        val series = tsk("r", "Stretch", createdAt = ago(40.0)).copy(recurrence = tech.csalliance.unstuck.core.model.Recurrence.Daily())
        val parked = tsk("p", "Someday", createdAt = ago(40.0)).copy(later = true)
        val out = render(Data(tasks = listOf(series, parked)))
        assertTrue(out, out.contains("Slipping: none."))
    }

    // ---- calibration verdict

    @Test fun `overestimating when sessions keep finishing early`() {
        val t = tsk("a", "Write report")
        val sessions = listOf(
            sessOn(t, SEP, 1, 9, 0, 600),   // 30 → 10
            sessOn(t, SEP, 1, 11, 0, 900),  // 30 → 15
            sessOn(t, SEP, 2, 9, 0, 1800),  // 30 → 30
        )
        val out = render(Data(tasks = listOf(t), sessions = sessions))
        assertTrue(out, out.contains("Estimates: 33% of 3 estimated sessions landed within 5 min; 0 ran over, 2 ran under; actual vs estimate averages -12 min. Verdict: overestimating (things finish sooner than you plan)."))
    }

    @Test fun `about right when misses are few and balanced`() {
        val t = tsk("a", "Write report")
        val sessions = listOf(
            sessOn(t, SEP, 1, 9, 0, 1800),
            sessOn(t, SEP, 1, 11, 0, 1980),  // +3, inside slack
            sessOn(t, SEP, 2, 9, 0, 1620),   // −3, inside slack
            sessOn(t, SEP, 2, 12, 0, 2400),  // +10, the lone miss
        )
        val out = render(Data(tasks = listOf(t), sessions = sessions))
        // mean delta (0 + 3 − 3 + 10) / 4 = 2.5 → rounds to +3
        assertTrue(out, out.contains("75% of 4 estimated sessions landed within 5 min; 1 ran over, 0 ran under; actual vs estimate averages +3 min. Verdict: about right."))
    }

    // ---- dates and budget

    @Test fun `block dates compare as local calendar days even late at night`() {
        val lateNight = localMillis(2026, 9, 2, 23, 30)
        val out = render(Data(blocks = listOf(blk("2026-09-02", 45), blk("2026-09-03", 45)), now = lateNight))
        assertTrue(out, out.contains("Planned: 1 calendar block (45m) dated in this window."))
    }

    @Test fun `stays under the cap with long names, dropping insight detail first`() {
        val longName = "A truly enormous task name that keeps going and going well past forty characters"
        val tasks = (0 until 6).map { i -> tsk("t$i", "$longName $i", moveCount = 3, createdAt = ago(1.0)) }
        val out = render(WEEK.copy(tasks = TASKS + tasks))
        assertTrue(out.length <= INSIGHTS_MAX_CHARS)
        assertTrue(out.startsWith("ok:"))
        assertTrue(out, out.contains("\"A truly enormous task name that keeps g…\""))
        // Titles survive; the sub-lines were the first thing to go.
        assertTrue(out.contains("- Tuesdays are your strongest day."))
        assertFalse(out.contains("Stack harder work here."))
    }

    @Test fun `the window wire names round-trip`() {
        assertEquals(InsightsWindow.MONTH, InsightsWindow.fromWire("month"))
        assertEquals(null, InsightsWindow.fromWire("year"))
    }
}
