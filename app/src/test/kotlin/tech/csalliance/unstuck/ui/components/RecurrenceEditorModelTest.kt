package tech.csalliance.unstuck.ui.components

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.StartsChip
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.ui.tasks.RecurrenceSaver

/**
 * The repeat picker's every-N-weeks half (every-n-weeks spec §5/§6, Ahmad
 * 2026-09-24): chips Every week · 2 weeks · 3 weeks · 4 weeks (a 5–8 rhythm the
 * assistant set shows as a fifth), the "Starts" chips, and the week one each
 * pick writes — the create sheet's and the task sheet's.
 */
class RecurrenceEditorModelTest {
    private val m = RecurrenceEditorModel
    private val v1 = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21")

    @Test fun `interval chips are 1 to 4, plus the assistant's 5 to 8`() {
        assertEquals(listOf(1, 2, 3, 4), m.intervalChoices(1))
        assertEquals(listOf(1, 2, 3, 4), m.intervalChoices(3))
        assertEquals(listOf(1, 2, 3, 4, 6), m.intervalChoices(6))
        assertEquals(listOf("Every week", "2 weeks", "3 weeks", "4 weeks", "Every 6 weeks"),
            m.intervalChoices(6).map(m::intervalLabel))
        assertEquals(2, m.intervalOf(v1))
        assertEquals(1, m.intervalOf(Recurrence.Weekly(listOf(4))))
        assertEquals(listOf(4), m.daysOf(Recurrence.EveryNWeeks(2, listOf(4, 9, -1), "2026-09-21")))
    }

    @Test fun `every week is plain weekly`() {
        assertEquals(Recurrence.Weekly(listOf(4), "2026-12-31"), m.rule(v1, listOf(4), 1, "2026-12-31", "2026-09-24", "2026-09-24"))
    }

    /** Create: week one is the first series day on or after the picked day. */
    @Test fun `create defaults to the first Starts chip`() {
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21"), m.rule(null, listOf(4), 2, null, "2026-09-24", "2026-09-24"))
        // A Friday pick for a Thursday rule: the next Thursday's week, not N weeks out.
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28"), m.rule(null, listOf(4), 2, null, "2026-09-24", "2026-09-25"))
        val chips = m.starts(null, Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28"), "2026-09-24", "2026-09-25", create = true)
        assertEquals(listOf(StartsChip("2026-10-01", "2026-09-28") to true, StartsChip("2026-10-08", "2026-10-05") to false), chips)
    }

    /** Edit keeping N: the stored weeks (days, time or until changed). */
    @Test fun `an edit that keeps N keeps the stored weeks, and pre-selects them`() {
        assertEquals(Recurrence.EveryNWeeks(2, listOf(5), "2026-09-21"), m.rule(v1, listOf(5), 2, null, "2026-09-30", "2026-10-08"))
        val chips = m.starts(v1, v1, "2026-09-30", "2026-10-08")
        assertEquals(listOf("2026-10-08", "2026-10-15"), chips.map { it.first.date })
        assertEquals(listOf(true, false), chips.map { it.second })
    }

    /** Web review fix 2 (17181ed): the same N with NEW days keeps the stored
     *  anchor, so the chips count on the new days. Thu → Mon on Wed 30 Sep: the
     *  pre-selected chip is Mon 5 Oct, the series' real first Monday — counted
     *  on the old Thursday it read "Mon 19 Oct". */
    @Test fun `an edit that changes only the days pre-selects the real first date`() {
        val edited = m.rule(v1, listOf(1), 2, null, "2026-09-30", "2026-10-08") as Recurrence.EveryNWeeks
        assertEquals(Recurrence.EveryNWeeks(2, listOf(1), "2026-09-21"), edited)
        val chips = m.starts(v1, edited, "2026-09-30", "2026-10-08")
        assertEquals(listOf("2026-10-05", "2026-10-12"), chips.map { it.first.date })
        assertEquals(listOf(true, false), chips.map { it.second })
    }

    /** Week one from no repeat on the task sheet: the series' block day only
     *  when it is ahead (web). A past block (Mon 14 Sep, today Thu 24 Sep) gives
     *  this week's chips and week one; the create sheet counts from its picked
     *  day as it is. */
    @Test fun `from no repeat a past block day counts from today, the create sheet from its day`() {
        val r = m.rule(null, listOf(4), 2, null, "2026-09-24", "2026-09-14") as Recurrence.EveryNWeeks
        assertEquals("2026-09-21", r.anchor)
        assertEquals(listOf("2026-09-24", "2026-10-01"), m.starts(null, r, "2026-09-24", "2026-09-14").map { it.first.date })
        assertEquals(listOf("2026-09-17", "2026-09-24"), m.starts(null, r, "2026-09-24", "2026-09-14", create = true).map { it.first.date })
    }

    /** Edit changing N (E3) and weekly → every 2 weeks: the current rule's next
     *  date's week stays week one. */
    @Test fun `an edit changing N keeps the next occurrence`() {
        assertEquals(Recurrence.EveryNWeeks(3, listOf(4), "2026-10-05"), m.rule(v1, listOf(4), 3, null, "2026-09-30", "2026-09-30"))
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28"), m.rule(Recurrence.Weekly(listOf(4)), listOf(4), 2, null, "2026-09-30", "2026-09-30"))
    }

    /** A Starts pick writes that week's Monday. */
    @Test fun `a Starts pick writes its week`() {
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-10-05"), m.rule(v1, listOf(4), 2, null, "2026-09-30", "2026-09-30", anchor = "2026-10-05"))
    }

    /** The create sheet schedules the first chip on the picked day (a one-off on
     *  an off weekday, as for weekly), a later chip on its own day — the weeks
     *  the Schedule step's re-anchor lands on are the chip's either way. */
    @Test fun `create starts on the picked day, or on a later chip's day`() {
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28") to "2026-09-25",
            m.createStart(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28"), "2026-09-25"))
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-10-05") to "2026-10-08",
            m.createStart(Recurrence.EveryNWeeks(2, listOf(4), "2026-10-05"), "2026-09-25"))
        // A stale anchor with the same weeks is written as the chip's Monday.
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28") to "2026-09-25",
            m.createStart(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-14"), "2026-09-25"))
        assertEquals(Recurrence.Weekly(listOf(4)) to "2026-09-25", m.createStart(Recurrence.Weekly(listOf(4)), "2026-09-25"))
    }

    /** Create, the rhythm picked first and the day changed after (Today, Thu 24
     *  Sep → 2 weeks → Tomorrow): week one is re-derived from the day now shown,
     *  so the default is still the FIRST chip (Thu 1 Oct) and the series starts
     *  on the day picked. With the week one computed when "2 weeks" was tapped
     *  (21 Sep), the second chip read as picked and the task was scheduled from
     *  Thu 8 Oct instead, 1 Oct left out. */
    @Test fun `create re-derives week one when the day changes after the rhythm`() {
        val tappedOnThursday = m.rule(null, listOf(4), 2, null, "2026-09-24", "2026-09-24")
        assertEquals("2026-09-21", (tappedOnThursday as Recurrence.EveryNWeeks).anchor)
        // The regression: the stale rule on its own.
        assertEquals("2026-10-08", m.createStart(tappedOnThursday, "2026-09-25").second)
        // The sheet shows and saves createRule's rule: the first chip of the new day.
        val shown = m.createRule(tappedOnThursday, null, "2026-09-25") as Recurrence.EveryNWeeks
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28"), shown)
        assertEquals(listOf(true, false), m.starts(null, shown, "2026-09-24", "2026-09-25", create = true).map { it.second })
        assertEquals(shown to "2026-09-25", m.createStart(shown, "2026-09-25"))
    }

    /** A tapped Starts chip holds while it is still a chip for the day and days
     *  shown; once it is not (the day moved past its week), the first chip. */
    @Test fun `a Starts pick holds while it is still a chip`() {
        val v = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21")
        assertEquals("2026-10-05", (m.createRule(v, "2026-10-05", "2026-09-25") as Recurrence.EveryNWeeks).anchor)
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-10-05") to "2026-10-08",
            m.createStart(m.createRule(v, "2026-10-05", "2026-09-25"), "2026-09-25"))
        // A day toggle keeps it (Mon + Thu: the chips' weeks are the same).
        assertEquals("2026-10-05", (m.createRule(v.copy(daysOfWeek = listOf(1, 4)), "2026-10-05", "2026-09-25") as Recurrence.EveryNWeeks).anchor)
        // Picked the week of 28 Sep, then the day moved to Fri 2 Oct: no longer a chip.
        assertEquals("2026-10-05", (m.createRule(v, "2026-09-28", "2026-10-02") as Recurrence.EveryNWeeks).anchor)
        // Anything but every N weeks is untouched.
        assertEquals(Recurrence.Weekly(listOf(4)), m.createRule(Recurrence.Weekly(listOf(4)), "2026-10-05", "2026-09-25"))
        assertEquals(null, m.createRule(null, "2026-10-05", "2026-09-25"))
    }

    /** The create sheet's draft survives a rotation with its rhythm and weeks. */
    @Test fun `the draft saver round-trips every N weeks`() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val r = Recurrence.EveryNWeeks(3, listOf(1, 4), "2026-09-21", "2026-12-31")
        val saved = with(RecurrenceSaver) { scope.save(r) }!!
        assertEquals(r, RecurrenceSaver.restore(saved))
        assertTrue(RecurrenceSaver.restore(with(RecurrenceSaver) { scope.save(Recurrence.Weekly(listOf(2))) }!!) is Recurrence.Weekly)
    }
}
