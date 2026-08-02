package tech.csalliance.unstuck.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.ui.components.greetingName

// The Today-header greeting name: first whitespace-separated word of the display
// name, EXACTLY "Unstuck" when unset — so the header reads "Good evening,\nMaya."
// or "Good evening,\nUnstuck." (web parity).
class GreetingNameTest {

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
