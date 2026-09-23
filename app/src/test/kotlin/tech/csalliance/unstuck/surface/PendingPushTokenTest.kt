package tech.csalliance.unstuck.surface

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A rotated FCM token reaches the server as the signed-in user (Android audit
 * 2026-09-23, A2): onNewToken runs in the background, where the session used to
 * read null, so the register went out with the anon key, 401'd and was dropped —
 * the server kept a dead token until the app was opened. It stays pending until
 * a register as a live user succeeds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PendingPushTokenTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        context.getSharedPreferences(PendingPushToken.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `a token that can't go yet stays pending, and goes on the next try`() = runTest {
        val sent = mutableListOf<String>()
        PendingPushToken.set(context, "t1")
        assertFalse(PendingPushToken.register(context, "t1", liveUser = { null }) { sent += it })
        assertEquals("t1", PendingPushToken.get(context))
        assertTrue(PendingPushToken.register(context, "t1", liveUser = { "u1" }) { sent += it })
        assertEquals(listOf("t1"), sent)
        assertNull(PendingPushToken.get(context))
    }

    @Test fun `a failed register keeps the token for the next background sync`() = runTest {
        PendingPushToken.set(context, "t1")
        assertFalse(PendingPushToken.register(context, "t1", liveUser = { "u1" }) { throw java.io.IOException("offline") })
        assertEquals("t1", PendingPushToken.get(context))
    }

    @Test fun `registering an older token never clears a newer one`() = runTest {
        PendingPushToken.set(context, "t1")
        assertTrue(PendingPushToken.register(context, "t1", liveUser = { "u1" }) { PendingPushToken.set(context, "t2") })
        assertEquals("t2", PendingPushToken.get(context))
    }
}
