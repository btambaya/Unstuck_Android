package tech.csalliance.unstuck.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.sync.CalendarConnectOutcome

// The calendar bar's failure captions, iOS's copy word for word (CalendarFeature.swift,
// build 81): "Sync now" and a connect used to end silently on failure (audit
// 2026-09-22 C18).
class CalendarSyncCaptionTest {

    @Test fun syncNowSaysWhyItFailed() {
        assertNull(calendarSyncCaption(ok = true, backedOff = false))
        assertNull("a pull that worked says nothing, even with a partial 429 back-off", calendarSyncCaption(ok = true, backedOff = true))
        assertEquals("Google is busy right now. Try again in a few minutes.", calendarSyncCaption(ok = false, backedOff = true))
        assertEquals("Couldn't sync with Google. Check your connection and try again.", calendarSyncCaption(ok = false, backedOff = false))
    }

    /** Connecting mirrors every scheduled task, by name, onto the PRIMARY Google
     *  calendar; the connect pill went straight to consent without saying so
     *  (Android audit 2026-09-23, A19). The disclosure shown first says it plainly. */
    @Test fun connectSaysScheduledTasksGoOnTheMainGoogleCalendar() {
        assertTrue(GoogleConnectCopy.DISCLOSURE.contains("Each task you schedule becomes an event on your main Google Calendar"))
        assertTrue(GoogleConnectCopy.DISCLOSURE.contains("Anyone who can see that calendar sees the task's name."))
        assertEquals("Connect Google Calendar?", GoogleConnectCopy.title(reconnect = false))
        assertEquals("Reconnect Google Calendar?", GoogleConnectCopy.title(reconnect = true))
    }

    /** A dead Google connection used to read "Needs reconnect" in red, and the raw
     *  provider error ("invalid_grant (400)") was one step from the screen. The card
     *  says what happened and what to do, in plain words, with the account named
     *  (Ahmad 2026-09-23, parity with iOS b84 GoogleConnectCopy). */
    @Test fun aDeadConnectionIsExplainedInPlainWords() {
        assertEquals("Google Calendar stopped syncing", GoogleConnectCopy.REAUTH_TITLE)
        assertEquals(
            "Google signed Unstuck out of your calendar (maya@example.com), so new events won’t show here " +
                "and your scheduled tasks won’t reach it. Reconnect to pick up where you left off.",
            GoogleConnectCopy.reauthBody("maya@example.com"),
        )
        // No account → no empty "()" in the sentence.
        val noAccount = "Google signed Unstuck out of your calendar, so new events won’t show here " +
            "and your scheduled tasks won’t reach it. Reconnect to pick up where you left off."
        assertEquals(noAccount, GoogleConnectCopy.reauthBody(null))
        assertEquals(noAccount, GoogleConnectCopy.reauthBody(""))
        assertEquals(noAccount, GoogleConnectCopy.reauthBody("   "))
    }

    @Test fun theReconnectCardNeverSpeaksInErrorCodes() {
        val shown = listOf(GoogleConnectCopy.REAUTH_TITLE, GoogleConnectCopy.reauthBody("maya@example.com"), GoogleConnectCopy.reauthBody(null))
        val raw = listOf("invalid_grant", "needs_reauth", "unauthorized", "400", "401", "error", "token", "oauth", "_")
        shown.forEach { text ->
            raw.forEach { word -> assertFalse("\"$word\" in: $text", text.lowercase().replace("maya@example.com", "").contains(word)) }
        }
    }

    @Test fun connectSaysWhenItOrItsFirstSyncFailed() {
        assertNull(calendarConnectCaption(CalendarConnectOutcome.CONNECTED))
        assertEquals("Google is connected, but the first sync didn't finish. Tap Sync now.", calendarConnectCaption(CalendarConnectOutcome.FIRST_SYNC_FAILED))
        assertEquals("Couldn't connect. Try again.", calendarConnectCaption(CalendarConnectOutcome.FAILED))
    }
}
