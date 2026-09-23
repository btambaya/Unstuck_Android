package tech.csalliance.unstuck.surface

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import tech.csalliance.unstuck.NotificationLevel
import tech.csalliance.unstuck.SettingsState
import tech.csalliance.unstuck.ui.exactAlarmAskWaitsForTour
import tech.csalliance.unstuck.ui.tour.TourMode
import tech.csalliance.unstuck.ui.tour.TourState

/**
 * Reminders on a new Android 14+ install, where SCHEDULE_EXACT_ALARM is denied by
 * default (Android audit 2026-09-23, A15): the fallback alarm must still fire in
 * Doze, a later grant re-arms everything, the ask happens once from a quiet screen,
 * and a "Coming up" delivered late never claims "in 10 minutes".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExactAlarmsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val savedRearm = ExactAlarmPermissionReceiver.rearm

    @After fun restore() {
        ExactAlarmPermissionReceiver.rearm = savedRearm
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun pi() = PendingIntent.getBroadcast(context, 1, Intent("t"), PendingIntent.FLAG_IMMUTABLE)

    @Test fun withoutExactAccess_theFallbackStillFiresInDoze() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val am = context.getSystemService(AlarmManager::class.java)
        assertFalse(ExactAlarms.granted(context))
        ReminderScheduler.arm(am, 5_000_000L, pi(), exact = ExactAlarms.granted(context))
        val alarm = shadowOf(am).scheduledAlarms.single()
        assertTrue("plain set() is deferred by Doze", alarm.isAllowWhileIdle)
        assertEquals(5_000_000L, alarm.triggerAtMs)
    }

    @Test fun withExactAccess_itIsExact() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val am = context.getSystemService(AlarmManager::class.java)
        ReminderScheduler.arm(am, 5_000_000L, pi(), exact = ExactAlarms.granted(context))
        val alarm = shadowOf(am).scheduledAlarms.single()
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
    }

    @Test fun aLaterGrantReArmsWhatWasArmedInexact() {
        assertTrue(ReminderScheduler.needsExactResync(exactNow = true, armedExact = false))
        assertFalse(ReminderScheduler.needsExactResync(exactNow = true, armedExact = true))
        assertFalse(ReminderScheduler.needsExactResync(exactNow = false, armedExact = false))
    }

    @Test fun thePermissionBroadcastReArmsEveryReminder() {
        var rearmed = 0
        ExactAlarmPermissionReceiver.rearm = { rearmed++ }
        ExactAlarmPermissionReceiver().onReceive(context, Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
        ExactAlarmPermissionReceiver().onReceive(context, Intent("tech.csalliance.unstuck.FAKE"))
        assertEquals(1, rearmed)
    }

    @Test fun theReceiverIsDeclaredForThePermissionBroadcast() {
        val found = context.packageManager.queryBroadcastReceivers(
            Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).setPackage(context.packageName), 0,
        ).map { it.activityInfo.name }
        assertTrue(found.toString(), found.any { it.endsWith(".surface.ExactAlarmPermissionReceiver") })
    }

    @Test fun theAskIsOneTimeAndOnlyWhenItChangesSomething() {
        assertTrue(ExactAlarms.shouldAsk(granted = false, wanted = true, asked = false, screenFree = true))
        assertFalse("already allowed", ExactAlarms.shouldAsk(granted = true, wanted = true, asked = false, screenFree = true))
        assertFalse("no reminder is on", ExactAlarms.shouldAsk(granted = false, wanted = false, asked = false, screenFree = true))
        assertFalse("asked once already", ExactAlarms.shouldAsk(granted = false, wanted = true, asked = true, screenFree = true))
        assertFalse("something else holds the screen", ExactAlarms.shouldAsk(granted = false, wanted = true, asked = false, screenFree = false))
        assertTrue(ExactAlarms.wanted(SettingsState(reminderLeadMin = 0, notificationLevel = NotificationLevel.BALANCED)))
        assertTrue(ExactAlarms.wanted(SettingsState(reminderLeadMin = 10, notificationLevel = NotificationLevel.CALM)))
        assertFalse(ExactAlarms.wanted(SettingsState(reminderLeadMin = 0, notificationLevel = NotificationLevel.CALM)))
    }

    // Waiting on the tour meant waiting on any PAUSED run, which lasts until the user
    // resumes or ends it: a user who paused the tour was never asked (A15). Only the
    // welcome card holds the ask; a resume card on screen already locks the content.
    @Test fun theAskWaitsForTheTourWelcomeOnly_neverForAPausedRun() {
        assertTrue("the welcome card can land a beat after onboarding", exactAlarmAskWaitsForTour(TourState(eligible = true)))
        assertFalse("paused mid-run", exactAlarmAskWaitsForTour(TourState(started = true, paused = true, mode = TourMode.ESSENTIAL, index = 3)))
        assertFalse("paused, resume chip dismissed", exactAlarmAskWaitsForTour(TourState(started = true, paused = true, mode = TourMode.ESSENTIAL, chipDismissed = true)))
        assertFalse("tour finished", exactAlarmAskWaitsForTour(TourState(started = true, done = true, eligible = true)))
        assertFalse("existing account, no tour", exactAlarmAskWaitsForTour(TourState()))
    }

    private val start = 1_800_000_000_000L
    private fun lead(now: Long, covers: Boolean = false) =
        ReminderReceiver.leadCopy("Pay rent", 10, start, now, covers) { "2:00 PM" }

    @Test fun aLeadReminderCountsTheMinutesActuallyLeft() {
        assertEquals("Coming up" to "Pay rent — in 10 minutes.", lead(start - 10 * 60_000L))
        assertEquals("Coming up" to "Pay rent — in 4 minutes.", lead(start - 4 * 60_000L + 5_000))
        assertEquals("Coming up" to "Pay rent — in 1 minute.", lead(start - 20_000))
    }

    @Test fun aLeadReminderDeliveredAfterTheStartNeverSaysComingUp() {
        // Balanced+: the start-now reminder is due for the same moment — drop this one.
        assertNull(lead(start + 70 * 60_000L, covers = true))
        // Calm (or a calendar event): the only reminder, so say when it was set for.
        assertEquals("Time to start" to "Pay rent was set for 2:00 PM.", lead(start + 70 * 60_000L))
        assertEquals("Coming up" to "Pay rent is starting.", lead(start + 30_000))
    }

    @Test fun anAlarmArmedBeforeTheStartWasSentKeepsItsOldCopy() {
        assertEquals("Coming up" to "Pay rent — in 10 minutes.", ReminderReceiver.leadCopy("Pay rent", 10, 0L, start, false) { "" })
    }
}
