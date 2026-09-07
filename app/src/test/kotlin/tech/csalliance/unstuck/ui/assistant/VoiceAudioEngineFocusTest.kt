package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine's audio-focus contract: the KIND of loss reaches the owner.
 *
 * A call from Unstuck MUTES its mic on a focus loss and waits for
 * AUDIOFOCUS_GAIN to un-mute. That gain never arrives after AUDIOFOCUS_LOSS
 * (permanent — a real phone call answered, say), so treating the two losses
 * alike left the call deaf for the rest of the session: playback kept talking
 * into the other call, nothing ended it, and ~15 minutes later the proxy's cap
 * closed the socket and the row was reported `done` — a call the user never
 * had, marked as handled. The owner can only tell the two apart if the engine
 * says which one it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceAudioEngineFocusTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** The engine's private listener — what the AudioManager calls back into. */
    private fun listenerOf(e: VoiceAudioEngine): AudioManager.OnAudioFocusChangeListener {
        val f = VoiceAudioEngine::class.java.getDeclaredField("focusListener")
        f.isAccessible = true
        return f.get(e) as AudioManager.OnAudioFocusChangeListener
    }

    @Test fun `a permanent loss is reported as permanent, a transient one is not`() {
        val e = VoiceAudioEngine(app)
        val losses = mutableListOf<Boolean>()
        e.onFocusLost = { permanent -> losses += permanent }
        val l = listenerOf(e)
        l.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        l.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(
            "the owner decides mute-and-wait vs end the call — it needs the kind",
            listOf(false, true), losses,
        )
    }

    @Test fun `a gain is only reported as a gain, and neither loss fires on it`() {
        val e = VoiceAudioEngine(app)
        var gains = 0
        val losses = mutableListOf<Boolean>()
        e.onFocusLost = { permanent -> losses += permanent }
        e.onFocusGained = { gains += 1 }
        val l = listenerOf(e)
        l.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        l.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(false), losses)
        assertEquals("the transient loser is un-muted by the gain that follows it", 1, gains)
    }

    /** The exact wedge from the audit: mute-and-wait on a PERMANENT loss waits
     *  for ever, because no gain is ever delivered after one. */
    @Test fun `nothing follows a permanent loss - mute and wait would never un-mute`() {
        val e = VoiceAudioEngine(app)
        var muted = false
        var gains = 0
        // A call-mode owner that ignores the kind (the old behaviour).
        e.onFocusLost = { muted = true }
        e.onFocusGained = { gains += 1; muted = false }
        val l = listenerOf(e)
        l.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(muted)
        assertEquals("AUDIOFOCUS_GAIN never follows AUDIOFOCUS_LOSS", 0, gains)
        assertTrue("…so the mic stays muted for the rest of the call", muted)
    }

    @Test fun `an unrelated focus change is ignored`() {
        val e = VoiceAudioEngine(app)
        var events = 0
        e.onFocusLost = { events += 1 }
        e.onFocusGained = { events += 1 }
        listenerOf(e).onAudioFocusChange(AudioManager.AUDIOFOCUS_NONE)
        assertEquals(0, events)
    }
}
