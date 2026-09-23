package tech.csalliance.unstuck.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings → Interface → "Clear Assistant history" (the privacy-policy
 *  control, `delete_my_assistant_turns`): the row's sub-line through each
 *  state, verbatim from iOS InterfaceSettingsView (build 78, 0f24908). */
class ClearAssistantHistoryCopyTest {

    @Test fun `idle, then clearing`() {
        assertEquals("Clear Assistant history", CLEAR_HISTORY_ROW)
        assertEquals("Delete what you've said to it (kept 90 days)", clearHistoryLine(clearing = false, result = null))
        assertEquals("Clearing…", clearHistoryLine(clearing = true, result = null))
        // A second tap while one is in flight still reads "Clearing…".
        assertEquals("Clearing…", clearHistoryLine(clearing = true, result = Result.success(3)))
    }

    @Test fun `what the server deleted`() {
        assertEquals("Nothing was stored", clearHistoryLine(false, Result.success(0)))
        assertEquals("Cleared 1 stored line", clearHistoryLine(false, Result.success(1)))
        assertEquals("Cleared 42 stored lines", clearHistoryLine(false, Result.success(42)))
    }

    @Test fun `a failure says so and invites a retry`() {
        assertEquals("Couldn't clear it — try again", clearHistoryLine(false, Result.failure(IllegalStateException("offline"))))
    }
}
