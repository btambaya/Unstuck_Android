package tech.csalliance.unstuck.surface

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.TimeZone

/**
 * A time-zone change re-pushes the zone to the server at once (parity with iOS
 * build 78): a user who travelled without cold-starting the app used to keep
 * ringing on the old zone's clock. Only the system's TIMEZONE_CHANGED counts,
 * and the zone is the process default — never an intent extra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TimezoneReceiverTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pushed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val savedPush = TimezoneReceiver.push
    private val savedScope = TimezoneReceiver.scopeFor
    private val savedZone = TimeZone.getDefault()

    @Before fun seams() {
        TimezoneReceiver.push = { _, tz -> pushed += tz }
        TimezoneReceiver.scopeFor = { CoroutineScope(Dispatchers.Unconfined) }
    }

    @After fun restore() {
        TimezoneReceiver.push = savedPush
        TimezoneReceiver.scopeFor = savedScope
        TimeZone.setDefault(savedZone)
    }

    @Test fun `a zone change pushes the new zone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        TimezoneReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(listOf("Asia/Tokyo"), pushed.toList())
    }

    @Test fun `an extra naming another zone is ignored - the process default is the truth`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        TimezoneReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, "Pacific/Kiritimati"))
        assertEquals(listOf("Europe/London"), pushed.toList())
    }

    @Test fun `any other action is a no-op`() {
        TimezoneReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        TimezoneReceiver().onReceive(context, Intent("tech.csalliance.unstuck.FAKE"))
        assertEquals(emptyList<String>(), pushed.toList())
    }

    @Test fun `with no app graph (not our Application) nothing runs`() {
        TimezoneReceiver.scopeFor = savedScope
        TimezoneReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(emptyList<String>(), pushed.toList())
    }

    // Second pass (R4): only the session wait was bounded. A restore that had to refresh
    // (a phone landing on a weak network) then a slow set_timezone outlived goAsync's
    // ~10 s window, and the system ANRs the receiver — killing a backgrounded process.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a push that never returns is cut inside the broadcast window`() = runTest {
        var cancelled = false
        val job = Job()
        TimezoneReceiver.scopeFor = { CoroutineScope(StandardTestDispatcher(testScheduler) + job) }
        TimezoneReceiver.push = { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        TimezoneReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        advanceTimeBy(TimezoneReceiver.PUSH_TIMEOUT_MS - 1)
        assertFalse(cancelled)
        advanceTimeBy(2)
        assertTrue("the push is cut at the budget", cancelled)
        assertTrue("and the broadcast released", job.children.none { it.isActive })
    }
}
