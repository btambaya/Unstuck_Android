package tech.csalliance.unstuck

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

// The device-side plumbing behind the server-backed capture archive (053) and the
// notification level / reminder lead read-back: pending-write queues round-trip,
// per-account migration markers survive sign-out, per-user caches don't.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsStoreServerMirrorTest {
    private fun store(): SettingsStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("unstuck.settings", Context.MODE_PRIVATE).edit().clear().commit()
        return SettingsStore(ctx)
    }

    @Test fun `pending capture-archive writes round-trip, including ids containing colons`() {
        val s = store()
        s.savePendingCaptureArchiveWrites(mapOf("c1" to true, "c2" to false, "weird:id" to true))
        assertEquals(mapOf("c1" to true, "c2" to false, "weird:id" to true), s.loadPendingCaptureArchiveWrites())
        s.savePendingCaptureArchiveWrites(emptyMap())
        assertTrue(s.loadPendingCaptureArchiveWrites().isEmpty())
    }

    @Test fun `sign-out clears the per-user caches + queues but keeps the per-account migration markers`() {
        val s = store()
        s.saveArchivedCaptureIds(setOf("c1"))
        s.savePendingCaptureArchiveWrites(mapOf("c1" to true))
        s.savePendingNotifPrefWrites(setOf("level"))
        s.setCaptureArchiveMigrated("u1")
        s.setNotifPrefsMigrated("u1")
        s.clearUserContent()
        assertTrue(s.loadArchivedCaptureIds().isEmpty())
        assertTrue(s.loadPendingCaptureArchiveWrites().isEmpty())
        assertTrue(s.loadPendingNotifPrefWrites().isEmpty())
        assertTrue("u1's one-time migration must not re-run on its next sign-in", s.captureArchiveMigrated("u1"))
        assertTrue(s.notifPrefsMigrated("u1"))
        assertFalse("another account starts un-migrated", s.notifPrefsMigrated("u2"))
    }

    @Test fun `notification level wire values match the server enum both ways`() {
        assertEquals("calm", NotificationLevel.CALM.wire)
        assertEquals("balanced", NotificationLevel.BALANCED.wire)
        assertEquals("coach", NotificationLevel.COACH.wire)
        assertEquals(NotificationLevel.COACH, NotificationLevel.fromWire("coach"))
        assertEquals(NotificationLevel.CALM, NotificationLevel.fromWire("CALM"))
        assertNull("unknown → keep the local level", NotificationLevel.fromWire("loud"))
        assertNull(NotificationLevel.fromWire(null))
    }
}
