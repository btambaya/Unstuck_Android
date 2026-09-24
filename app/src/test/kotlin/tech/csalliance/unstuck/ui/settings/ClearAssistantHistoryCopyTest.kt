package tech.csalliance.unstuck.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings → Assistant & privacy → "Delete conversation history" (the
 *  privacy-policy control, `delete_my_assistant_turns`): the row's sub-line
 *  through each state, in the iOS slim-settings words. */
class ClearAssistantHistoryCopyTest {

    @Test fun `idle, then deleting`() {
        assertEquals("Delete conversation history", CLEAR_HISTORY_ROW)
        assertEquals("What you've said to it is kept 90 days. Delete it now.", clearHistoryLine(clearing = false, result = null))
        assertEquals("Deleting…", clearHistoryLine(clearing = true, result = null))
        // A second tap while one is in flight still reads "Deleting…".
        assertEquals("Deleting…", clearHistoryLine(clearing = true, result = Result.success(3)))
    }

    @Test fun `what the server deleted`() {
        assertEquals("Nothing was stored.", clearHistoryLine(false, Result.success(0)))
        assertEquals("Deleted 1 stored line.", clearHistoryLine(false, Result.success(1)))
        assertEquals("Deleted 42 stored lines.", clearHistoryLine(false, Result.success(42)))
    }

    @Test fun `a failure says so and invites a retry`() {
        assertEquals("Couldn't delete it. Try again.", clearHistoryLine(false, Result.failure(IllegalStateException("offline"))))
    }
}
