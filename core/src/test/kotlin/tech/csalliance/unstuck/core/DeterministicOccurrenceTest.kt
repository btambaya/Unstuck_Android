package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ChosenDateAction
import tech.csalliance.unstuck.core.logic.ChosenDateWrite
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.RegenPlan
import tech.csalliance.unstuck.core.logic.isUuid
import tech.csalliance.unstuck.core.logic.occurrenceId
import tech.csalliance.unstuck.core.logic.recurrenceChosenDateAction
import tech.csalliance.unstuck.core.logic.recurrenceChosenDateWrite
import tech.csalliance.unstuck.core.logic.recurrenceEditStart
import tech.csalliance.unstuck.core.logic.recurrenceTopUp
import tech.csalliance.unstuck.core.logic.regenerateForTask
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time

// Stage 2 — "same id for same day" (Ahmad 2026-09-23; deterministic-occurrence-
// ids.md §3). Every occurrence a series mints carries occurrenceId(task, date),
// so the pure plans must never mint an id a kept row holds (rule A), never delete
// and mint the same id (rule B), never count a row the plan moves as the chosen
// day's (rule B′), and the chosen day's own write follows §3b′. Ported from iOS
// build 85's DeterministicOccurrenceTests and web's recurrence.test.ts — with
// Android's (and web's) rule that a done or skipped future occurrence is history,
// never deleted or rewritten.
class DeterministicOccurrenceTest {
    private val taskId = "3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60"
    private val today = "2026-09-23"

    private fun series(r: Recurrence, estimateMin: Int = 30): TaskItem =
        mkTask(id = taskId, name = "Gym", estimateMin = estimateMin).copy(recurrence = r)
    private fun day(offset: Int, from: String = today) = IsoDate.addDays(from, offset)
    private fun ms(iso: String): Long = IsoDate.parse(iso)!!.let { Time.civil(it.year, it.monthValue, it.dayOfMonth) }

    /** The occurrence minted FOR [date], sitting on [on] (moved when they differ). */
    private fun occ(date: String, on: String? = null, time: String = "07:00", done: Boolean = false, skipped: Boolean = false, event: String? = null) =
        CalBlock(
            id = occurrenceId(taskId, date), taskId = taskId, taskName = "Gym", startTime = time, durationMinutes = 30,
            date = on ?: date, kind = CalBlockKind.TASK, done = done, skipped = skipped,
            completedAt = if (done) "2026-09-23T08:00:00.000Z" else null, externalEventId = event,
        )

    private fun ids(p: RegenPlan) = p.toUpsert.map { it.id } + p.toRetime.map { it.id } + p.toDelete

    @Test fun regenerateMintsDeterministicIds() {
        val t = series(Recurrence.Weekly(listOf(1, 3)))
        val plan = regenerateForTask(t, t.recurrence, emptyList(), today, "07:00", ms(today))
        assertTrue(plan.toUpsert.isNotEmpty())
        for (b in plan.toUpsert) assertEquals(occurrenceId(t.id, b.date), b.id)
        assertTrue(plan.toRetime.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
    }

    /** Rule B: 07:00 → 09:00. Each future day's row is rewritten IN PLACE — never
     *  deleted and minted again with the same id (set_task_recurrence wrote the
     *  mints first and its deletes then cancelled them: the days were lost). */
    @Test fun aTimeChangeRewritesEachRowInPlace() {
        val t = series(Recurrence.Daily())
        val blocks = (1..55).map { occ(day(it), event = if (it % 2 == 0) "evt$it" else null) }.toMutableList()
        blocks[4] = blocks[4].copy(done = true, completedAt = "2026-09-23T08:00:00.000Z")   // +5 ticked early
        blocks += occ(day(-1), done = true)                                                  // history
        val plan = regenerateForTask(t, t.recurrence, blocks, today, "09:00", ms(day(1)))
        val retimed = plan.toRetime.associateBy { it.id }
        for (o in 1..55) {
            val id = occurrenceId(t.id, day(o))
            assertFalse("day $o is not deleted", id in plan.toDelete)
            assertFalse("day $o is not minted again", plan.toUpsert.any { it.id == id })
            if (o == 5) {
                // A ticked future day stays as it is (history): not rewritten, not twinned.
                assertFalse(id in retimed)
                continue
            }
            val r = retimed[id]
            assertNotNull("day $o is rewritten", r)
            assertEquals(day(o), r!!.date)
            assertEquals("09:00", r.startTime)
            assertFalse(r.done)
            assertNull(r.completedAt)
            assertEquals("the Google mapping is kept", if (o % 2 == 0) "evt$o" else null, r.externalEventId)
        }
        assertEquals("only the day nobody had is minted", listOf(day(56)), plan.toUpsert.map { it.date })
        assertTrue(plan.toDelete.isEmpty())
        assertEquals("the lists are disjoint", ids(plan).size, ids(plan).toSet().size)
    }

    /** Rule A: id(D) sits on E (still desired there, same time) — D is not minted. */
    @Test fun aMovedOccurrenceHoldsItsDay() {
        val t = series(Recurrence.Daily())
        val blocks = listOf(1, 2, 4, 6, 7, 8, 9, 10).map { occ(day(it)) } + occ(day(3), on = day(5))
        val plan = regenerateForTask(t, t.recurrence, blocks, today, "07:00", ms(day(1)), 10)
        assertEquals(RegenPlan(emptyList(), emptyList()), plan)
    }

    /** Rule A with Android's history rule: a done or skipped future day keeps its
     *  row, so it gets no open copy at the new time. */
    @Test fun aDoneOrSkippedFutureDayGetsNoOpenCopy() {
        val t = series(Recurrence.Daily())
        val blocks = listOf(occ(day(1), done = true), occ(day(2), skipped = true), occ(day(3)))
        val plan = regenerateForTask(t, t.recurrence, blocks, today, "09:00", ms(day(1)), 3)
        assertTrue(plan.toUpsert.isEmpty())
        assertEquals(listOf(day(3)), plan.toRetime.map { it.date })
        assertTrue(plan.toDelete.isEmpty())
    }

    /** Rule A in the top-up: id(D) was moved before the frontier, outside
     *  occurrenceReach (daily: 0 days). The tail mint for D is dropped. */
    @Test fun theTopUpSkipsAnIdHeldByAMovedOccurrence() {
        val t = series(Recurrence.Daily())
        val blocks = (1..52).map { occ(day(it)) } + occ(day(54), on = day(10), time = "18:00")
        val tail = recurrenceTopUp(t, blocks, today)
        assertEquals("+54's occurrence lives on at +10", listOf(day(53), day(55)), tail.map { it.date })
        assertEquals(listOf(occurrenceId(t.id, day(53)), occurrenceId(t.id, day(55))), tail.map { it.id })
        assertTrue(tail.all { it.startTime == "07:00" && it.durationMinutes == 30 && it.kind == CalBlockKind.TASK })
    }

    @Test fun theTopUpIdsAreDeterministic() {
        val t = series(Recurrence.Weekly(listOf(1, 3, 5)))
        val blocks = (1..20).map { day(it) }.filter { IsoDate.dayOfWeek(it) in listOf(1, 3, 5) }.map { occ(it) }
        val a = recurrenceTopUp(t, blocks, today)
        val b = recurrenceTopUp(t, blocks, today)
        assertTrue(a.isNotEmpty())
        assertEquals("two devices topping up the same store mint the same ids", a.map { it.id }, b.map { it.id })
        assertTrue(a.all { it.id == occurrenceId(t.id, it.date) })
        assertTrue("the tail only", a.all { it.date > blocks.maxOf { b2 -> b2.date } })
        assertTrue(a.all { IsoDate.dayOfWeek(it.date) in listOf(1, 3, 5) })
    }

    /** The top-up (tail only, iOS build 81's C1 rules) — a series with no future
     *  block in the horizon but one past it was re-planned later on purpose. */
    @Test fun theTopUpLeavesARePlannedSeriesAloneAndNeverMintsToday() {
        val t = series(Recurrence.Daily())
        assertTrue(recurrenceTopUp(t, listOf(occ(day(70))), today).isEmpty())
        val tail = recurrenceTopUp(t, listOf(occ(day(-3))), today)
        assertEquals("an idle series comes back from tomorrow", day(1), tail.first().date)
        assertEquals(day(55), tail.last().date)
        assertTrue(recurrenceTopUp(t.copy(recurrence = null), listOf(occ(day(1))), today).isEmpty())
    }

    /** The kept row (RecurrenceStart.keepId) goes INTO the plan: it is in none of
     *  the three lists, and the day whose id it carries is not minted. */
    @Test fun aKeptIdNeverCollidesWithAMint() {
        // (a) The C1 fixture: Oct 15's rent pushed to Oct 20 18:00; on Oct 16 only
        // the end date changes.
        val rent = series(Recurrence.Monthly("2027-06-30"))
        val a = listOf(occ("2026-08-15", done = true), occ("2026-09-15", done = true), occ("2026-10-15", on = "2026-10-20", time = "18:00"), occ("2026-11-15"))
        val startA = recurrenceEditStart(taskId, rent.recurrence, a, "2026-10-16")!!
        val keptA = startA.keepId!!
        assertEquals(occurrenceId(taskId, "2026-10-15"), keptA)
        val planA = regenerateForTask(rent, rent.recurrence, a, "2026-10-16", startA.startTime, ms(startA.date), startA.horizonDays, setOf(keptA))
        assertFalse(keptA in ids(planA))
        assertEquals(RegenPlan(emptyList(), emptyList()), planA)

        // (b) Monthly on the 15th, today Sep 20: NEXT month's id(Oct 15) dragged to
        // Sep 25 is within 14 days of the passed Sep 15, so keepId == id(Oct 15).
        val monthly = series(Recurrence.Monthly())
        val b = listOf(
            occ("2026-07-15", done = true), occ("2026-08-15", done = true), occ("2026-09-15", done = true),
            occ("2026-10-15", on = "2026-09-25"), occ("2026-11-15"),
        )
        val startB = recurrenceEditStart(taskId, monthly.recurrence, b, "2026-09-20")!!
        val keptB = startB.keepId!!
        assertEquals(occurrenceId(taskId, "2026-10-15"), keptB)
        val planB = regenerateForTask(monthly, monthly.recurrence, b, "2026-09-20", startB.startTime, ms(startB.date), startB.horizonDays, setOf(keptB))
        assertFalse("the kept row is in none of the lists", keptB in ids(planB))
        assertFalse("Oct 15 is not minted: its occurrence lives on", planB.toUpsert.any { it.date == "2026-10-15" })
        // Filtering afterwards (the old callers) cannot undo the rewrite.
        val unkept = regenerateForTask(monthly, monthly.recurrence, b, "2026-09-20", startB.startTime, ms(startB.date), startB.horizonDays)
        assertTrue("the trap keepIds closes", unkept.toRetime.any { it.id == keptB && it.date == "2026-10-15" })
    }

    /** Rule B′, the exact example: weekly Mon/Wed at 07:00, today Tue Sep 29; Wed
     *  Oct 7's occurrence was moved to Fri Oct 2, then the series is scheduled on
     *  Fri Oct 2 at 09:00. The plan moves id(Oct 7) home; Oct 2 gets its own mint. */
    @Test fun theChosenDayIgnoresARowTheRetimeMovesAway() {
        val t = series(Recurrence.Weekly(listOf(1, 3)))
        val today = "2026-09-29"
        val dates = (1..56).map { day(it, from = today) }.filter { IsoDate.dayOfWeek(it) in listOf(1, 3) }
        val existing = dates.map { if (it == "2026-10-07") occ(it, on = "2026-10-02") else occ(it) }
        val plan0 = regenerateForTask(t, t.recurrence, existing, today, "09:00", ms("2026-10-02"))
        val oct7 = occurrenceId(taskId, "2026-10-07")
        assertEquals("moved home, at the new time", "2026-10-07", plan0.toRetime.first { it.id == oct7 }.date)
        assertEquals(ChosenDateAction.Mint, recurrenceChosenDateAction(existing, plan0, "2026-10-02", "09:00"))
        val (plan, write) = recurrenceChosenDateWrite(t, existing, plan0, "2026-10-02", "09:00")
        val minted = (write as ChosenDateWrite.Insert).block
        assertEquals(occurrenceId(taskId, "2026-10-02"), minted.id)
        assertEquals("2026-10-02", minted.date)
        assertEquals("09:00", minted.startTime)
        val written = ids(plan) + minted.id
        assertEquals("no id is written twice", written.size, written.toSet().size)
    }

    /** §3b′ case by case. */
    @Test fun theChosenDaysWrite() {
        val t = series(Recurrence.Weekly(listOf(1, 3)), estimateMin = 2)
        val d = "2026-10-02"
        val idD = occurrenceId(taskId, d)
        val none = RegenPlan(emptyList(), emptyList())

        // Covered → nothing.
        assertEquals(ChosenDateWrite.None, recurrenceChosenDateWrite(t, listOf(occ(d, time = "09:00")), none, d, "09:00").second)

        // Retime → the day's row at the time, un-skipped.
        val skipped = occ(d, skipped = true)
        assertEquals(ChosenDateWrite.Upsert(skipped.copy(startTime = "09:00", skipped = false)), recurrenceChosenDateWrite(t, listOf(skipped), none, d, "09:00").second)

        // Mint with the day's id in toDelete → that row, taken out of the delete and
        // rewritten in place: it keeps its Google mapping.
        val doomed = occ(d, done = true, event = "evt-d")
        val (planA, writeA) = recurrenceChosenDateWrite(t, listOf(doomed), RegenPlan(emptyList(), listOf(idD)), d, "09:00")
        assertTrue("no longer deleted", planA.toDelete.isEmpty())
        val rewritten = (writeA as ChosenDateWrite.Upsert).block
        assertEquals(idD, rewritten.id)
        assertEquals("built from the existing row", "evt-d", rewritten.externalEventId)
        assertEquals("09:00", rewritten.startTime)
        assertEquals("clamped to the server's 5…1440", 5, rewritten.durationMinutes)
        assertFalse(rewritten.done)
        assertNull(rewritten.completedAt)

        // Mint with the id held elsewhere (moved) → a RANDOM-id block; the surviving
        // row is never taken over.
        val (planB, writeB) = recurrenceChosenDateWrite(t, listOf(occ(d, on = "2026-10-05")), none, d, "09:00")
        assertEquals(none, planB)
        val extra = (writeB as ChosenDateWrite.Upsert).block
        assertNotEquals(idD, extra.id)
        assertTrue(isUuid(extra.id))
        assertEquals(d, extra.date)
        assertEquals("09:00", extra.startTime)

        // Mint with nothing held → the deterministic insert.
        val (planC, writeC) = recurrenceChosenDateWrite(t, emptyList(), none, d, "09:00")
        assertEquals(none, planC)
        assertEquals(
            ChosenDateWrite.Insert(CalBlock(id = idD, taskId = taskId, taskName = "Gym", startTime = "09:00", durationMinutes = 5, date = d, kind = CalBlockKind.TASK)),
            writeC,
        )
    }

    /** A rewritten row covers the chosen day by its NEW date, never the day it leaves. */
    @Test fun aRetimeCoversTheDayItMovesTo() {
        val d = "2026-10-07"
        val moved = occ(d, on = "2026-10-02")
        val plan = RegenPlan(emptyList(), emptyList(), toRetime = listOf(moved.copy(date = d, startTime = "09:00")))
        assertEquals(ChosenDateAction.Covered, recurrenceChosenDateAction(listOf(moved), plan, d, "09:00"))
        assertEquals(ChosenDateAction.Mint, recurrenceChosenDateAction(listOf(moved), plan, "2026-10-02", "09:00"))
    }

    /** Clearing the repeat never deletes a kept row. */
    @Test fun stoppingTheRepeatKeepsTheKeptRow() {
        val t = series(Recurrence.Daily())
        val blocks = listOf(occ(day(1)), occ(day(2)))
        val plan = regenerateForTask(t, null, blocks, today, "07:00", ms(today), keepIds = setOf(occurrenceId(taskId, day(2))))
        assertEquals(listOf(occurrenceId(taskId, day(1))), plan.toDelete)
    }
}
