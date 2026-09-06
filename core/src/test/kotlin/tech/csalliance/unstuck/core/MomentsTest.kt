package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.Moment
import tech.csalliance.unstuck.core.logic.MomentAction
import tech.csalliance.unstuck.core.logic.MomentFact
import tech.csalliance.unstuck.core.logic.MomentKind
import tech.csalliance.unstuck.core.logic.MomentRituals
import tech.csalliance.unstuck.core.logic.MomentRun
import tech.csalliance.unstuck.core.logic.MomentState
import tech.csalliance.unstuck.core.logic.MomentTone
import tech.csalliance.unstuck.core.logic.pickMoment
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time

// 1:1 with lib/assistant/moments.test.ts (+ MomentsTests.swift).
//
// Monday 24 Aug 2026 is the base day; Friday 28th and Sunday 30th host the
// weekly rituals. Yesterday (Sun 23rd) is where how-did-it-go and quiet-win
// look. Local, timezone-less timestamps throughout so the suite passes in
// any zone (tests run with -Duser.timezone=UTC).
class MomentsTest {

    private val MON = "2026-08-24"
    private val FRI = "2026-08-28"
    private val SUN = "2026-08-30"

    private fun at(hm: String, iso: String = MON): Long = Time.parseMillis("${iso}T$hm:00")!!

    private val ALL = MomentRituals(morning = true, evening = true, friday = true, sunday = true)
    private val NONE = MomentRituals(morning = false, evening = false, friday = false, sunday = false)
    private fun only(k: String) = when (k) {
        "morning" -> NONE.copy(morning = true)
        "evening" -> NONE.copy(evening = true)
        "friday" -> NONE.copy(friday = true)
        "sunday" -> NONE.copy(sunday = true)
        else -> error(k)
    }

    private val TONES = listOf(MomentTone.GENTLE, MomentTone.HONEST, MomentTone.MINIMAL)
    private var seq = 0
    private fun nextId() = "id${++seq}"

    private fun task(
        id: String? = null, name: String = "A task", estimateMin: Int = 25, done: Boolean = false,
        moveCount: Int? = null, later: Boolean? = null, completedAt: String? = null, recurrence: Recurrence? = null,
    ) = TaskItem(
        id = id ?: nextId(), name = name, estimateMin = estimateMin, totalFocused = 0, done = done,
        moveCount = moveCount, completedAt = completedAt, later = later, recurrence = recurrence,
        createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z",
    )

    private fun block(
        id: String? = null, taskId: String? = "t", taskName: String = "A task", startTime: String = "10:00",
        durationMinutes: Int = 30, date: String = MON, done: Boolean = false,
    ) = CalBlock(
        id = id ?: nextId(), taskId = taskId, taskName = taskName, startTime = startTime,
        durationMinutes = durationMinutes, date = date, done = done,
    )

    private fun fact(text: String, id: String? = null, category: String = "person", whenIso: String? = null) =
        MomentFact(id = id ?: nextId(), fact = text, category = category, whenIso = whenIso)

    private fun session(completedAt: String, actualSec: Int = 1500) =
        Session(id = nextId(), taskName = "Deep work", actualSec = actualSec, completedAt = completedAt)

    private fun state(
        tasks: List<TaskItem> = emptyList(), blocks: List<CalBlock> = emptyList(), sessions: List<Session> = emptyList(),
        facts: List<MomentFact> = emptyList(), struggles: List<String> = emptyList(),
        todayIso: String = MON, now: Long = at("13:00"), isDismissed: (String) -> Boolean = { false },
    ) = MomentState(
        tasks = tasks, blocks = blocks, sessions = sessions, reasons = emptyList(), facts = facts,
        struggles = struggles, todayIso = todayIso, now = now, isDismissed = isDismissed,
    )

    private fun withDismissed(s: MomentState, vararg ids: String) = s.copy(isDismissed = { it in ids })

    private fun lastAction(m: Moment?): MomentAction = m!!.actions.last()

    // ── dates-that-matter — a known date 3–14 days out ──

    private fun zara(whenIso: String) = state(facts = listOf(fact("Zara — daughter, turning 8", id = "f1", whenIso = whenIso)))

    @Test fun `dates - fires for a birthday five days out, with the gift task ready to go`() {
        val m = pickMoment(zara("2026-08-29"), NONE, MomentTone.GENTLE)
        assertNotNull(m)
        assertEquals(MomentKind.RELATIONSHIP, m!!.kind)
        assertEquals(30, m.priority)
        assertEquals("Zara’s birthday is Sat 29 Aug — gift sorted?", m.text)
        assertEquals(MomentAction("Sort the gift", MomentRun.CreateTask(name = "Get Zara’s birthday gift")), m.actions[0])
        assertEquals(MomentRun.Dismiss, lastAction(m).run)
    }

    @Test fun `dates - window edges - 2 days = no, 3 = yes, 14 = yes, 15 = no`() {
        assertNull(pickMoment(zara("2026-08-26"), NONE, MomentTone.GENTLE))
        assertNotNull(pickMoment(zara("2026-08-27"), NONE, MomentTone.GENTLE))
        assertNotNull(pickMoment(zara("2026-09-07"), NONE, MomentTone.GENTLE))
        assertNull(pickMoment(zara("2026-09-08"), NONE, MomentTone.GENTLE))
    }

    @Test fun `dates - id is stable per fact+date, so one dismissal covers the whole window`() {
        val m = pickMoment(zara("2026-08-29"), NONE, MomentTone.GENTLE)!!
        assertEquals("dates-that-matter:f1:2026-08-29", m.id)
        assertNull(pickMoment(withDismissed(zara("2026-08-29"), m.id), NONE, MomentTone.GENTLE))
    }

    @Test fun `dates - facts without a whenIso never fire`() {
        assertNull(pickMoment(state(facts = listOf(fact("Zara — daughter, turning 8"))), NONE, MomentTone.GENTLE))
    }

    @Test fun `dates - an impossible date is skipped, never thrown`() {
        // The shape regex ("^\\d{4}-\\d{2}-\\d{2}") admits dates that do not
        // exist. JS rolls 30 Feb over to 2 Mar; java.time throws, and an
        // uncaught throw here would take the whole gateway card down.
        val s = state(facts = listOf(fact("Zara — daughter, turning 8", id = "f1", whenIso = "2026-02-30")))
        assertNull(pickMoment(s, NONE, MomentTone.GENTLE))
        // A real date alongside it still fires.
        val mixed = state(
            facts = listOf(
                fact("Bad date", id = "f0", whenIso = "2026-13-01"),
                fact("Zara — daughter, turning 8", id = "f1", whenIso = "2026-08-29"),
            ),
        )
        assertEquals("dates-that-matter:f1:2026-08-29", pickMoment(mixed, NONE, MomentTone.GENTLE)!!.id)
    }

    @Test fun `dates - a longer ISO stamp is read by its date prefix`() {
        val s = state(facts = listOf(fact("Zara — daughter, turning 8", id = "f1", whenIso = "2026-08-29T00:00:00Z")))
        assertEquals("dates-that-matter:f1:2026-08-29", pickMoment(s, NONE, MomentTone.GENTLE)!!.id)
    }

    @Test fun `dates - stays quiet once an open gift task for that person exists`() {
        val s = state(
            facts = listOf(fact("Zara — daughter, turning 8", whenIso = "2026-08-29")),
            tasks = listOf(task(name = "Get Zara’s birthday gift")),
        )
        assertNull(pickMoment(s, NONE, MomentTone.GENTLE))
    }

    @Test fun `dates - a dated non-birthday fact gets the generic prepare framing`() {
        val s = state(facts = listOf(fact("Visa renewal — passport expires", category = "context", whenIso = "2026-08-29")))
        val m = pickMoment(s, NONE, MomentTone.GENTLE)!!
        assertTrue(m.text.contains("Visa renewal — passport expires"))
        assertEquals("Sat 29 Aug — Visa renewal — passport expires. Want a task for it?", m.text)
        val run = m.actions[0].run
        assertTrue(run is MomentRun.CreateTask)
        assertEquals("Prepare: Visa renewal — passport expires", (run as MomentRun.CreateTask).name)
    }

    @Test fun `dates - tone shifts the phrasing - honest is direct, minimal is shortest`() {
        val texts = TONES.map { pickMoment(zara("2026-08-29"), NONE, it)!!.text }
        assertEquals(3, texts.toSet().size)
        assertTrue(texts[1].startsWith("Straight up:"))
        assertTrue(texts[2].length < texts[0].length)
        assertTrue(texts[2].length < texts[1].length)
    }

    // ── how-did-it-go — yesterday held a block naming someone we know ──

    private fun rehearsals() = state(
        facts = listOf(fact("Maleek — son, 9")),
        blocks = listOf(block(id = "b1", taskName = "Take Maleek to rehearsals", date = "2026-08-23")),
    )

    @Test fun `hdig - lifts the person AND the activity out of the block name`() {
        val m = pickMoment(rehearsals(), NONE, MomentTone.GENTLE)!!
        assertEquals(MomentKind.RELATIONSHIP, m.kind)
        assertEquals("How did Maleek’s rehearsals go yesterday?", m.text)
        assertEquals(MomentAction("Talk about it", MomentRun.Chat("Tell me how it went: Take Maleek to rehearsals")), m.actions[0])
        assertEquals("how-did-it-go:b1", m.id)
    }

    @Test fun `hdig - multi-word activities survive, filler words do not`() {
        val s = state(
            facts = listOf(fact("Amara — daughter")),
            blocks = listOf(block(taskName = "Drive Amara to the piano recital", date = "2026-08-23")),
        )
        assertEquals("How did Amara’s piano recital go yesterday?", pickMoment(s, NONE, MomentTone.GENTLE)!!.text)
    }

    @Test fun `hdig - falls back to the block title when nothing follows the name`() {
        val s = state(facts = listOf(fact("Maleek — son, 9")), blocks = listOf(block(taskName = "Call Maleek", date = "2026-08-23")))
        assertEquals("How did ‘Call Maleek’ go yesterday?", pickMoment(s, NONE, MomentTone.GENTLE)!!.text)
    }

    @Test fun `hdig - only person facts introduce names, a matching context fact stays silent`() {
        val s = state(
            facts = listOf(fact("Maleek — the client project codename", category = "context")),
            blocks = listOf(block(taskName = "Take Maleek to rehearsals", date = "2026-08-23")),
        )
        assertNull(pickMoment(s, NONE, MomentTone.GENTLE))
    }

    @Test fun `hdig - whole words only - Maleeka is not Maleek`() {
        val s = state(
            facts = listOf(fact("Maleek — son, 9")),
            blocks = listOf(block(taskName = "Email Maleeka about invoices", date = "2026-08-23")),
        )
        assertNull(pickMoment(s, NONE, MomentTone.GENTLE))
    }

    @Test fun `hdig - yesterday means yesterday - today's block does not fire it`() {
        val s = state(facts = listOf(fact("Maleek — son, 9")), blocks = listOf(block(taskName = "Take Maleek to rehearsals", date = MON)))
        assertNull(pickMoment(s, NONE, MomentTone.GENTLE))
    }

    @Test fun `hdig - dismissal sticks per block`() {
        assertNull(pickMoment(withDismissed(rehearsals(), "how-did-it-go:b1"), NONE, MomentTone.GENTLE))
    }

    // ── first-touch — the morning ritual ──

    private fun morning(
        now: Long = at("09:00"), tasks: List<TaskItem>? = null, blocks: List<CalBlock>? = null, struggles: List<String> = emptyList(),
    ) = state(
        now = now,
        tasks = tasks ?: listOf(task(id = "w", name = "Write the report", estimateMin = 60)),
        blocks = blocks ?: listOf(block(taskId = "w", taskName = "Write the report", startTime = "11:00")),
        struggles = struggles,
    )

    @Test fun `first-touch - anchors on the first upcoming block, exactly like the brief`() {
        val m = pickMoment(morning(), only("morning"), MomentTone.GENTLE)!!
        assertEquals(MomentKind.RITUAL, m.kind)
        assertEquals(20, m.priority)
        assertEquals("Morning. ‘Write the report’ at 11:00 is the anchor — want the day built around it?", m.text)
        assertEquals(MomentAction("Plan my day", MomentRun.Chat("Plan my day around the anchor")), m.actions[0])
        assertEquals("first-touch:$MON", m.id)
    }

    @Test fun `first-touch - a past block loses the anchor slot to the next upcoming one`() {
        val s = morning(
            tasks = listOf(task(id = "a", name = "Early thing"), task(id = "w", name = "Write the report")),
            blocks = listOf(
                block(taskId = "a", taskName = "Early thing", startTime = "07:00"),
                block(taskId = "w", taskName = "Write the report", startTime = "11:00"),
            ),
        )
        assertTrue(pickMoment(s, only("morning"), MomentTone.GENTLE)!!.text.contains("‘Write the report’ at 11:00"))
    }

    @Test fun `first-touch - with an empty calendar, the shortest unscheduled task opens the day`() {
        val s = morning(tasks = listOf(task(name = "Big thing", estimateMin = 90), task(name = "Tiny thing", estimateMin = 15)), blocks = emptyList())
        assertTrue(pickMoment(s, only("morning"), MomentTone.GENTLE)!!.text.contains("‘Tiny thing’ (~15 min)"))
    }

    @Test fun `first-touch - gates - not after 11-59, not before 5, not without the pref, not without an open task`() {
        assertNull(pickMoment(morning(now = at("12:00")), only("morning"), MomentTone.GENTLE))
        assertNotNull(pickMoment(morning(now = at("11:59")), only("morning"), MomentTone.GENTLE))
        assertNull(pickMoment(morning(now = at("01:00")), only("morning"), MomentTone.GENTLE))   // night owl = still yesterday
        assertNull(pickMoment(morning(), NONE, MomentTone.GENTLE))
        assertNull(pickMoment(morning(tasks = listOf(task(done = true))), only("morning"), MomentTone.GENTLE))
    }

    @Test fun `first-touch - recurring templates are not open tasks for the gate`() {
        val s = morning(tasks = listOf(task(recurrence = Recurrence.Daily())), blocks = emptyList())
        assertNull(pickMoment(s, only("morning"), MomentTone.GENTLE))
    }

    @Test fun `first-touch - struggling with Starting earns the first ten minutes, in every tone`() {
        for (tone in TONES) {
            val m = pickMoment(morning(struggles = listOf("Starting")), only("morning"), tone)!!
            assertTrue(m.text, m.text.endsWith("I’ll give you the first ten minutes."))
        }
        assertFalse(pickMoment(morning(), only("morning"), MomentTone.GENTLE)!!.text.contains("ten minutes"))
    }

    @Test fun `first-touch - one per day - dismissing today's id silences it`() {
        assertNull(pickMoment(withDismissed(morning(), "first-touch:$MON"), only("morning"), MomentTone.GENTLE))
    }

    // ── evening-sweep — carry the day's misses forward ──

    private fun evening(now: Long = at("18:00"), tasks: List<TaskItem>? = null, blocks: List<CalBlock>? = null) = state(
        now = now,
        tasks = tasks ?: listOf(task(id = "t1", name = "Call the bank"), task(id = "t2", name = "Water plants")),
        blocks = blocks ?: listOf(
            block(taskId = "t1", taskName = "Call the bank", startTime = "09:00"),
            block(taskId = "t2", taskName = "Water plants", startTime = "10:00"),
        ),
    )

    @Test fun `sweep - offers to carry every task that was scheduled and missed`() {
        val m = pickMoment(evening(), only("evening"), MomentTone.GENTLE)!!
        assertEquals("Two things didn’t happen today — carry them to tomorrow?", m.text)
        assertEquals(MomentAction("Carry 2 to tomorrow", MomentRun.CarryTasks(listOf("t1", "t2"))), m.actions[0])
        assertEquals(MomentAction("Leave them", MomentRun.Dismiss), lastAction(m))
        assertEquals("evening-sweep:$MON", m.id)
    }

    @Test fun `sweep - a single miss reads singular`() {
        val s = evening(tasks = listOf(task(id = "t1", name = "Call the bank")), blocks = listOf(block(taskId = "t1", startTime = "09:00")))
        val m = pickMoment(s, only("evening"), MomentTone.GENTLE)!!
        assertEquals("One thing didn’t happen today — carry it to tomorrow?", m.text)
        assertEquals("Leave it", lastAction(m).label)
    }

    @Test fun `sweep - 17-29 is too early, 17-30 is sweep o'clock`() {
        assertNull(pickMoment(evening(now = at("17:29")), only("evening"), MomentTone.GENTLE))
        assertNotNull(pickMoment(evening(now = at("17:30")), only("evening"), MomentTone.GENTLE))
    }

    @Test fun `sweep - done blocks, done tasks, and blocks still ahead tonight are not misses`() {
        val s = evening(
            tasks = listOf(task(id = "t1"), task(id = "t2", done = true), task(id = "t3", name = "Evening yoga")),
            blocks = listOf(
                block(taskId = "t1", startTime = "09:00", done = true),
                block(taskId = "t2", startTime = "10:00"),
                block(taskId = "t3", taskName = "Evening yoga", startTime = "20:00"),
            ),
        )
        assertNull(pickMoment(s, only("evening"), MomentTone.GENTLE))
    }

    @Test fun `sweep - a chronic slipper flips the sweep to the honest variant with a shrink path`() {
        val s = evening(tasks = listOf(task(id = "t1", name = "Tax form", moveCount = 4), task(id = "t2", name = "Water plants")))
        val m = pickMoment(s, only("evening"), MomentTone.GENTLE)!!
        assertEquals("‘Tax form’ has slipped 4 times — want to carry it, shrink it, or let it go?", m.text)
        assertEquals(3, m.actions.size)
        assertEquals(MomentRun.CarryTasks(listOf("t1", "t2")), m.actions[0].run)
        assertEquals(MomentRun.Chat("Help me shrink ‘Tax form’ into a first step"), m.actions[1].run)
        assertEquals(MomentRun.Dismiss, m.actions[2].run)
    }

    @Test fun `sweep - pref gate + one-per-day dismissal`() {
        assertNull(pickMoment(evening(), NONE, MomentTone.GENTLE))
        assertNull(pickMoment(withDismissed(evening(), "evening-sweep:$MON"), only("evening"), MomentTone.GENTLE))
    }

    // ── friday-review — the week in three minutes ──

    private val week = listOf(
        session("2026-08-24T10:00:00"), session("2026-08-25T09:30:00", 3000),
        session("2026-08-26T14:00:00"), session("2026-08-27T11:00:00"), session("2026-08-28T09:00:00"),
    )

    private fun friday(todayIso: String = FRI, now: Long? = null, sessions: List<Session>? = null) =
        state(todayIso = todayIso, now = now ?: at("16:00", FRI), sessions = sessions ?: week)

    @Test fun `friday - fires with 5 sessions and names the best run's day and daypart`() {
        val m = pickMoment(friday(), only("friday"), MomentTone.GENTLE)!!
        assertEquals(MomentKind.RITUAL, m.kind)
        assertEquals("Week in three minutes? 5 focus blocks, best run Tuesday morning.", m.text)
        assertEquals(MomentAction("Review the week", MomentRun.Chat("Let’s do the week review")), m.actions[0])
        assertEquals("friday-review:$FRI", m.id)
    }

    @Test fun `friday - four sessions are not yet a week worth reviewing`() {
        assertNull(pickMoment(friday(sessions = week.take(4)), only("friday"), MomentTone.GENTLE))
    }

    @Test fun `friday - last week's sessions do not pad the count`() {
        val s = friday(sessions = week.take(4) + session("2026-08-22T10:00:00"))
        assertNull(pickMoment(s, only("friday"), MomentTone.GENTLE))
    }

    @Test fun `friday - Friday from 15-00 only - Thursday and 14-59 stay quiet`() {
        assertNull(pickMoment(friday(todayIso = "2026-08-27", now = at("16:00", "2026-08-27")), only("friday"), MomentTone.GENTLE))
        assertNull(pickMoment(friday(now = at("14:59", FRI)), only("friday"), MomentTone.GENTLE))
        assertNotNull(pickMoment(friday(now = at("15:00", FRI)), only("friday"), MomentTone.GENTLE))
    }

    // ── sunday-runway — next week at a glance ──
    // Next week from Sunday 30 Aug: Mon 31 Aug … Fri 4 Sept. Wednesday = 2 Sept.

    private fun sunday(tasks: List<TaskItem> = emptyList(), blocks: List<CalBlock> = emptyList()) =
        state(tasks = tasks, blocks = blocks, todayIso = SUN, now = at("17:00", SUN))

    private fun load(date: String, minutes: Int) = block(taskId = null, durationMinutes = minutes, date = date)

    @Test fun `sunday - an overbooked weekday (over 4h) gets named for thinning`() {
        val m = pickMoment(sunday(blocks = listOf(load("2026-09-02", 270))), only("sunday"), MomentTone.GENTLE)!!
        assertEquals("Wednesday looks wall-to-wall — want to thin it out?", m.text)
        assertEquals(MomentAction("Thin out Wednesday", MomentRun.Chat("Help me thin out Wednesday")), m.actions[0])
        assertEquals("sunday-runway:$SUN", m.id)
    }

    @Test fun `sunday - honest and minimal variants quote the load in hours like JS`() {
        assertEquals(
            "Straight up: Wednesday has 4.5h scheduled. Thin it out?",
            pickMoment(sunday(blocks = listOf(load("2026-09-02", 270))), only("sunday"), MomentTone.HONEST)!!.text,
        )
        assertEquals(
            "Wednesday: 5h. Thin it?",
            pickMoment(sunday(blocks = listOf(load("2026-09-02", 300))), only("sunday"), MomentTone.MINIMAL)!!.text,
        )
    }

    @Test fun `sunday - the heaviest day wins when several are loaded`() {
        val s = sunday(blocks = listOf(load("2026-09-01", 250), load("2026-09-02", 300)))
        assertTrue(pickMoment(s, only("sunday"), MomentTone.GENTLE)!!.text.contains("Wednesday"))
    }

    @Test fun `sunday - exactly 4h is not wall-to-wall, 3 unscheduled tasks earn the rough-out offer instead`() {
        val s = sunday(blocks = listOf(load("2026-09-02", 240)), tasks = listOf(task(), task(), task()))
        val m = pickMoment(s, only("sunday"), MomentTone.GENTLE)!!
        assertEquals("Rough out next week? 3 tasks are still unscheduled.", m.text)
        assertTrue(m.actions[0].run is MomentRun.Chat)
    }

    @Test fun `sunday - two unscheduled tasks and a light week = a quiet Sunday`() {
        assertNull(pickMoment(sunday(tasks = listOf(task(), task())), only("sunday"), MomentTone.GENTLE))
    }

    @Test fun `sunday - Sunday from 16-00 only`() {
        val s = sunday(blocks = listOf(load("2026-09-02", 270)))
        assertNull(pickMoment(s.copy(now = at("15:59", SUN)), only("sunday"), MomentTone.GENTLE))
        assertNull(pickMoment(s.copy(todayIso = MON, now = at("17:00", MON)), only("sunday"), MomentTone.GENTLE))
    }

    // ── slip-radar — the task that keeps moving ──

    private fun slipping(struggles: List<String> = emptyList()) =
        state(tasks = listOf(task(id = "tax", name = "Tax form", moveCount = 4)), struggles = struggles)

    @Test fun `slip - fires as a notice with the shrink-park-let-go fork`() {
        val m = pickMoment(slipping(), NONE, MomentTone.GENTLE)!!
        assertEquals(MomentKind.NOTICE, m.kind)
        assertEquals(10, m.priority)
        assertEquals("‘Tax form’ has moved 4 times. Shrink it to a 10-minute step, park it, or let it go?", m.text)
        assertEquals(MomentAction("Shrink it", MomentRun.Chat("Help me shrink ‘Tax form’ into a first step")), m.actions[0])
        assertEquals("slip-radar:tax:4", m.id)
    }

    @Test fun `slip - two moves are life, three are a pattern`() {
        assertNull(pickMoment(state(tasks = listOf(task(moveCount = 2))), NONE, MomentTone.GENTLE))
        assertNotNull(pickMoment(state(tasks = listOf(task(moveCount = 3))), NONE, MomentTone.GENTLE))
    }

    @Test fun `slip - done and parked (Later) tasks are off the radar`() {
        assertNull(pickMoment(state(tasks = listOf(task(moveCount = 5, done = true))), NONE, MomentTone.GENTLE))
        assertNull(pickMoment(state(tasks = listOf(task(moveCount = 5, later = true))), NONE, MomentTone.GENTLE))
    }

    @Test fun `slip - the highest count wins, dismissing it promotes the next slipper`() {
        val s = state(tasks = listOf(task(id = "a", name = "Lesser slip", moveCount = 3), task(id = "b", name = "Worst slip", moveCount = 5)))
        assertEquals("slip-radar:b:5", pickMoment(s, NONE, MomentTone.GENTLE)!!.id)
        assertEquals("slip-radar:a:3", pickMoment(withDismissed(s, "slip-radar:b:5"), NONE, MomentTone.GENTLE)!!.id)
    }

    @Test fun `slip - with Starting in the struggles, the framing blames the start, not the task`() {
        val m = pickMoment(slipping(struggles = listOf("Starting")), NONE, MomentTone.GENTLE)!!
        assertTrue(m.text.contains("starting is the hard part, not the task"))
    }

    @Test fun `slip - tone variants - three distinct texts, honest leads with Straight up`() {
        val texts = TONES.map { pickMoment(slipping(), NONE, it)!!.text }
        assertEquals(3, texts.toSet().size)
        assertTrue(texts[1].startsWith("Straight up:"))
        assertTrue(texts[2].length < texts[0].length)
    }

    // ── quiet-win — a chronic slipper finally landed ──

    private fun win() = state(tasks = listOf(task(id = "tax", name = "Tax form", moveCount = 3, done = true, completedAt = "2026-08-23T18:00:00")))

    @Test fun `win - acknowledges the win and asks for nothing - dismiss is the only action`() {
        val m = pickMoment(win(), NONE, MomentTone.GENTLE)!!
        assertEquals(MomentKind.NOTICE, m.kind)
        assertEquals("‘Tax form’ finally happened after 3 dodges. That’s the hard kind of done.", m.text)
        assertEquals(listOf(MomentAction("Noted", MomentRun.Dismiss)), m.actions)
        assertEquals("quiet-win:tax:2026-08-23", m.id)
    }

    @Test fun `win - only YESTERDAY's completions count, and only after 3 or more moves`() {
        val today = state(tasks = listOf(task(moveCount = 3, done = true, completedAt = "${MON}T09:00:00")))
        val older = state(tasks = listOf(task(moveCount = 3, done = true, completedAt = "2026-08-22T09:00:00")))
        val smooth = state(tasks = listOf(task(moveCount = 2, done = true, completedAt = "2026-08-23T09:00:00")))
        assertNull(pickMoment(today, NONE, MomentTone.GENTLE))
        assertNull(pickMoment(older, NONE, MomentTone.GENTLE))
        assertNull(pickMoment(smooth, NONE, MomentTone.GENTLE))
    }

    @Test fun `win - a UTC completion stamp resolves to the local calendar day`() {
        // Tests run in UTC, so a 'Z' stamp on the 23rd IS yesterday.
        val s = state(tasks = listOf(task(id = "tax", moveCount = 3, done = true, completedAt = "2026-08-23T18:00:00.000Z")))
        assertEquals("quiet-win:tax:2026-08-23", pickMoment(s, NONE, MomentTone.GENTLE)!!.id)
    }

    // ── habit-gap — Patterns.kt finds the missing Wednesday ──
    // Three history Wednesdays before Mon 24 Aug → gym pattern; next
    // occurrence Wed 26 Aug is uncovered.

    private fun gym() = state(
        tasks = listOf(task(id = "gym", name = "Gym", estimateMin = 60)),
        blocks = listOf("2026-08-19", "2026-08-12", "2026-08-05").map {
            block(taskId = "gym", taskName = "Gym", startTime = "07:00", durationMinutes = 60, date = it)
        },
    )

    @Test fun `habit - offers to book the usual slot as a real schedule action`() {
        val m = pickMoment(gym(), NONE, MomentTone.GENTLE)!!
        assertEquals(MomentKind.NOTICE, m.kind)
        assertEquals("You usually do ‘Gym’ on Wednesdays — still on for Wednesday 26 Aug?", m.text)
        assertEquals(
            MomentAction("Book Wednesday 07:00", MomentRun.Schedule(taskId = "gym", date = "2026-08-26", time = "07:00")),
            m.actions[0],
        )
        assertEquals("habit-gap:gym:2026-08-26", m.id)
    }

    @Test fun `habit - a block already covering the slot means no gap, no moment`() {
        val s = gym()
        val covered = s.copy(blocks = s.blocks + block(taskId = "gym", taskName = "Gym", startTime = "07:00", date = "2026-08-26"))
        assertNull(pickMoment(covered, NONE, MomentTone.GENTLE))
    }

    // ── selection — one calm thing at a time ──
    // Monday 18:00 with everything primed: a birthday five days out (30),
    // an evening sweep with a chronic slipper (20), and slip-radar (10).

    private fun loaded() = state(
        now = at("18:00"),
        facts = listOf(fact("Zara — daughter, turning 8", id = "f1", whenIso = "2026-08-29")),
        tasks = listOf(task(id = "tax", name = "Tax form", moveCount = 4)),
        blocks = listOf(block(taskId = "tax", taskName = "Tax form", startTime = "09:00")),
    )

    private fun gymPlusSlipper() = state(
        tasks = listOf(task(id = "gym", name = "Gym", estimateMin = 60), task(id = "tax", name = "Tax form", moveCount = 4)),
        blocks = listOf("2026-08-19", "2026-08-12", "2026-08-05").map {
            block(taskId = "gym", taskName = "Gym", startTime = "07:00", date = it)
        },
    )

    @Test fun `select - relationship beats ritual beats notice, and dismissals cascade down`() {
        val first = pickMoment(loaded(), ALL, MomentTone.GENTLE)!!
        assertEquals("dates-that-matter:f1:2026-08-29", first.id)

        val second = pickMoment(withDismissed(loaded(), first.id), ALL, MomentTone.GENTLE)!!
        assertEquals("evening-sweep:$MON", second.id)

        val third = pickMoment(withDismissed(loaded(), first.id, second.id), ALL, MomentTone.GENTLE)!!
        assertEquals("slip-radar:tax:4", third.id)

        assertNull(pickMoment(withDismissed(loaded(), first.id, second.id, third.id), ALL, MomentTone.GENTLE))
    }

    @Test fun `select - how-did-it-go (one-day window) outranks a birthday still days away`() {
        val s = state(
            facts = listOf(fact("Zara — daughter, turning 8", whenIso = "2026-08-29"), fact("Maleek — son, 9")),
            blocks = listOf(block(taskName = "Take Maleek to rehearsals", date = "2026-08-23")),
        )
        assertTrue(pickMoment(s, NONE, MomentTone.GENTLE)!!.id.startsWith("how-did-it-go:"))
    }

    @Test fun `select - among notices only the strongest surfaces - slip-radar over habit-gap`() {
        val s = gymPlusSlipper()
        assertEquals("slip-radar:tax:4", pickMoment(s, NONE, MomentTone.GENTLE)!!.id)
        assertEquals("habit-gap:gym:2026-08-26", pickMoment(withDismissed(s, "slip-radar:tax:4"), NONE, MomentTone.GENTLE)!!.id)
    }

    @Test fun `select - an empty account is a quiet gateway`() {
        assertNull(pickMoment(state(), ALL, MomentTone.GENTLE))
    }

    @Test fun `select - deterministic - the same state always yields the same moment`() {
        assertEquals(pickMoment(loaded(), ALL, MomentTone.HONEST), pickMoment(loaded(), ALL, MomentTone.HONEST))
    }

    @Test fun `select - every moment's last action is a dismiss`() {
        val states = listOf(
            loaded() to ALL,
            gymPlusSlipper() to NONE,
            state(now = at("09:00"), tasks = listOf(task())) to only("morning"),
        )
        for ((s, p) in states) {
            for (tone in TONES) {
                val m = pickMoment(s, p, tone)
                assertNotNull(m)
                assertEquals(MomentRun.Dismiss, lastAction(m).run)
            }
        }
    }

    @Test fun `select - salience orders same-priority rituals`() {
        // Monday 18:00: first-touch is gated off, evening sweep (400) is the only ritual.
        val m = pickMoment(withDismissed(loaded(), "dates-that-matter:f1:2026-08-29"), ALL, MomentTone.GENTLE)!!
        assertEquals(400, m.salience)
    }

    // ── MomentRituals / MomentTone — the small input types ──

    @Test fun `rituals - defaults - morning + evening on, weeklies off`() {
        assertEquals(MomentRituals(morning = true, evening = true, friday = false, sunday = false), MomentRituals.DEFAULTS)
        assertEquals(MomentRituals(), MomentRituals.DEFAULTS)
    }

    @Test fun `tone - wire round-trip, unknown falls back to gentle`() {
        for (t in TONES) assertEquals(t, MomentTone.fromWire(t.wire))
        assertEquals(MomentTone.GENTLE, MomentTone.fromWire("shouty"))
        assertEquals(MomentTone.GENTLE, MomentTone.fromWire(null))
    }
}
