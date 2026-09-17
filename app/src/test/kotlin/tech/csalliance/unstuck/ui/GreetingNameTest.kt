package tech.csalliance.unstuck.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tech.csalliance.unstuck.ui.components.greetingLine
import tech.csalliance.unstuck.ui.components.greetingName
import java.time.ZoneId
import java.time.ZonedDateTime

// The Today-header greeting name: first whitespace-separated word of the display
// name, EXACTLY "Unstuck" when unset — so the ONE-line header reads
// "Good evening Maya." or "Good evening Unstuck." (iOS GreetingName.line).
class GreetingNameTest {

    private fun at(hour: Int): Long =
        ZonedDateTime.of(2026, 9, 17, hour, 5, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun oneLineGreetingDropsTheCommaAndKeepsTheNameOnTheSameLine() {
        assertEquals("Good evening Maya.", greetingLine(at(20), "Maya Chen"))
        assertEquals("Good afternoon Zubair.", greetingLine(at(14), "Zubair Kazaure"))
        assertEquals("Good morning Maya.", greetingLine(at(8), "  Maya "))
        assertEquals("Good evening Unstuck.", greetingLine(at(22), null))
        assertEquals("Good evening Unstuck.", greetingLine(at(22), "   "))
        assertFalse(greetingLine(at(20), "Maya Chen").contains("\n"))
        assertFalse(greetingLine(at(20), "Maya Chen").contains(","))
    }

    @Test fun firstNameOfFullName() {
        assertEquals("Maya", greetingName("Maya Chen"))
        assertEquals("Zubair", greetingName("Zubair Kazaure"))
    }

    @Test fun singleWordNamePassesThrough() {
        assertEquals("Maya", greetingName("Maya"))
    }

    @Test fun whitespaceOnlySplitsNotPunctuation() {
        // Split on whitespace ONLY — dots/hyphens inside a single token stay intact.
        assertEquals("mary-jane", greetingName("mary-jane watson"))
        assertEquals("j.r.", greetingName("j.r. ewing"))
    }

    @Test fun trimsAndCollapsesLeadingWhitespace() {
        assertEquals("Maya", greetingName("  Maya Chen "))
        assertEquals("Maya", greetingName("\tMaya\nChen"))
    }

    @Test fun unsetFallsBackToUnstuck() {
        assertEquals("Unstuck", greetingName(null))
        assertEquals("Unstuck", greetingName(""))
        assertEquals("Unstuck", greetingName("   "))
    }

    @Test fun emailLocalFallbackNameStillGreets() {
        // AuthService falls back to the email local part when no display name is
        // set — that whole token is the "first name" (same as Settings shows).
        assertEquals("maya.c", greetingName("maya.c"))
    }
}
