package tech.csalliance.unstuck.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun connectSaysWhenItOrItsFirstSyncFailed() {
        assertNull(calendarConnectCaption(CalendarConnectOutcome.CONNECTED))
        assertEquals("Google is connected, but the first sync didn't finish. Tap Sync now.", calendarConnectCaption(CalendarConnectOutcome.FIRST_SYNC_FAILED))
        assertEquals("Couldn't connect. Try again.", calendarConnectCaption(CalendarConnectOutcome.FAILED))
    }
}
