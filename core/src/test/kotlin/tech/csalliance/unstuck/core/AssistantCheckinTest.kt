package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.logic.buildCheckin

// 1:1 with lib/assistant/checkin.test.ts. Grounded, zero-token morning line —
// built entirely from local data and injected as a LOCAL turn, never sent.
class AssistantCheckinTest {

    @Test fun `greets by first name with counts and usable time`() {
        assertEquals(
            "Morning, Maya. 3 things on today, 2h 40m usable. Want me to sequence them, or take something off the list?",
            buildCheckin(firstName = "Maya", openTodayCount = 3, usableLabel = "2h 40m", hour = 9),
        )
    }

    @Test fun `singular grammar and no usable label`() {
        assertEquals(
            "Afternoon. 1 thing on today. Want me to sequence them, or take something off the list?",
            buildCheckin(firstName = null, openTodayCount = 1, usableLabel = null, hour = 14),
        )
    }

    @Test fun `empty day offers planning instead`() {
        assertEquals(
            "Evening, Maya. Nothing scheduled yet — want me to help plan today?",
            buildCheckin(firstName = "Maya", openTodayCount = 0, usableLabel = null, hour = 20),
        )
    }

    @Test fun `the greeting word tracks the local hour`() {
        assertEquals("Morning.", buildCheckin(null, 0, null, 0).takeWhile { it != ' ' })
        assertEquals("Morning.", buildCheckin(null, 0, null, 11).takeWhile { it != ' ' })
        assertEquals("Afternoon.", buildCheckin(null, 0, null, 12).takeWhile { it != ' ' })
        assertEquals("Afternoon.", buildCheckin(null, 0, null, 17).takeWhile { it != ' ' })
        assertEquals("Evening.", buildCheckin(null, 0, null, 18).takeWhile { it != ' ' })
        assertEquals("Evening.", buildCheckin(null, 0, null, 23).takeWhile { it != ' ' })
    }

    @Test fun `a blank name is treated as no name`() {
        assertEquals(
            "Morning. Nothing scheduled yet — want me to help plan today?",
            buildCheckin(firstName = "   ", openTodayCount = 0, usableLabel = null, hour = 8),
        )
    }
}
