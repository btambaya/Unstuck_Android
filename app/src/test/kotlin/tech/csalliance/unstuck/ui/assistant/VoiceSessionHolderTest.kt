package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Talk's live-call gate. A call FROM Unstuck (CallVoiceService: a `microphone`
 * foreground service holding the engine in MODE_IN_COMMUNICATION with
 * transient-EXCLUSIVE focus, plus the voice tool scratch) is the one thing a
 * Talk session must never start over: the second engine's exclusive focus
 * request mutes the call's mic, `resetVoiceScratch` wipes the entities the
 * call's conversation is mid-way through, and the second proxy socket burns
 * the per-user session slot. The user gets told, not two assistants at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceSessionHolderTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    private fun holder(callActive: Boolean): VoiceSessionHolder =
        VoiceSessionHolder(app).apply { isCallActive = { callActive } }

    @Test fun `a live call from Unstuck refuses a Talk session and says why`() {
        val h = holder(callActive = true)
        assertTrue("the start is refused", h.refuseWhileOnCall())
        assertEquals(VoiceState.ERROR, h.state)
        assertEquals(VoiceSessionHolder.ON_A_CALL, h.note)
        assertEquals("You’re on a call with Unstuck.", h.note)
        assertFalse("nothing was dialled", h.sessionActive)
    }

    @Test fun `no call means no refusal and nothing is said`() {
        val h = holder(callActive = false)
        assertFalse(h.refuseWhileOnCall())
        assertNull(h.note)
        assertEquals("the screen stays on its connecting state", VoiceState.CONNECTING, h.state)
    }

    @Test fun `the gate reads the live call each time, not once`() {
        var live = true
        val h = VoiceSessionHolder(app).apply { isCallActive = { live } }
        assertTrue(h.refuseWhileOnCall())
        live = false   // the call ended; the next mic tap may start Talk
        assertFalse(h.refuseWhileOnCall())
    }
}
