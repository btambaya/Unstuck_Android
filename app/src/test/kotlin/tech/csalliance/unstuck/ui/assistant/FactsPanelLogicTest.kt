package tech.csalliance.unstuck.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import java.time.ZoneId

/** The pure bits of "What Unstuck knows": the per-row date label (iOS
 *  `FactDate.label`) and the copy the panel must carry verbatim. */
class FactsPanelLogicTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val now = 1_788_000_000_000L   // 2026-08-29T13:20Z

    private fun fact(whenIso: String? = null, updatedAt: String = "2026-09-06T10:00:00.000Z") = ProfileFact(
        id = "f1", category = ProfileFactCategory.PERSON, fact = "Maleek — son, 9", source = ProfileFactSource.INTERVIEW,
        whenIso = whenIso, createdAt = updatedAt, updatedAt = updatedAt,
    )

    @Test fun `a fact with a date it refers to reads "for d MMM"`() {
        val label = FactDate.label(fact(whenIso = "2026-09-12"), nowMs = now, zone = zone)
        assertTrue(label, label.startsWith("for 12 Sep"))
        assertTrue("same year — no year suffix", !label.contains("2026"))
    }

    @Test fun `a date in another year carries the year`() {
        val label = FactDate.label(fact(whenIso = "2027-03-04"), nowMs = now, zone = zone)
        assertTrue(label, label.startsWith("for 4 Mar 2027"))
    }

    @Test fun `no date it refers to falls back to last updated`() {
        val label = FactDate.label(fact(updatedAt = "2026-09-06T10:00:00.000Z"), nowMs = now, zone = zone)
        assertTrue(label, label.startsWith("6 Sep"))
        assertTrue(!label.startsWith("for "))
    }

    @Test fun `an impossible whenIso is ignored, garbage timestamps give an empty label`() {
        val label = FactDate.label(fact(whenIso = "2026-02-30", updatedAt = "2026-09-06T10:00:00.000Z"), nowMs = now, zone = zone)
        assertTrue(label, label.startsWith("6 Sep"))
        assertEquals("", FactDate.label(fact(updatedAt = "garbage"), nowMs = now, zone = zone))
    }

    @Test fun `the panel's copy is iOS's and the web's verbatim`() {
        assertEquals(
            "The assistant’s memory — built from your answers and conversations. These facts are shared with our AI provider (which doesn’t train on them) so it can personalise your help. Delete anything; it forgets immediately, everywhere.",
            FactsPanelCopy.DISCLOSURE,
        )
        assertEquals("Nothing yet — it learns as you talk to it.", FactsPanelCopy.EMPTY)
        assertEquals("What Unstuck knows", FactsPanelCopy.NAV_TITLE)
        assertEquals("Forget everything the assistant has learned about you? This can’t be undone.", FactsPanelCopy.FORGET_ALL_MESSAGE)
        assertEquals("Edit · person", FactsPanelCopy.editTitle(fact()))
        assertEquals("Forget \"Maleek — son, 9\"", FactsPanelCopy.forgetA11y(fact()))
    }
}
