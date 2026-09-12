package tech.csalliance.unstuck.soak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.ui.today.weekFocusMinutes

/**
 * BEHAVIOUR LOCK for the Today header's 7-day focus roll-up (perf soak,
 * 2026-09-12). It used to re-parse every session's `completedAt` on every
 * minute tick; the parse is now hoisted into its own remember. The total must
 * be identical to the expression it replaced, at any `now`.
 */
class WeekFocusMinutesTest {

    private val tasks = SoakSeed.tasks()
    private val sessions = SoakSeed.sessions(tasks)

    /** The pre-optimisation expression, verbatim. */
    private fun legacy(sessions: List<Session>, now: Long): Int =
        sessions.filter { (now - (Time.parseMillis(it.completedAt) ?: 0)) in 0..(7L * 86_400_000) }
            .sumOf { it.actualSec } / 60

    private fun hoisted(sessions: List<Session>) = sessions.map { Time.parseMillis(it.completedAt) ?: 0L }

    @Test fun matchesTheLegacyRollUpAtEveryOffset() {
        val base = System.currentTimeMillis()
        val ms = hoisted(sessions)
        var nonZero = 0
        for (offsetDays in -10..10) {
            val now = base + offsetDays * 86_400_000L
            val expected = legacy(sessions, now)
            assertEquals("offset=$offsetDays", expected, weekFocusMinutes(sessions, ms, now))
            if (expected > 0) nonZero++
        }
        assertTrue("fixture must produce real totals", nonZero > 5)
    }

    @Test fun unparseableAndEmptyBehaveAsBefore() {
        val broken = sessions.take(20).mapIndexed { i, s -> if (i % 2 == 0) s.copy(completedAt = "not-a-date") else s }
        val now = System.currentTimeMillis()
        assertEquals(legacy(broken, now), weekFocusMinutes(broken, hoisted(broken), now))
        assertEquals(0, weekFocusMinutes(emptyList(), emptyList(), now))
    }
}
