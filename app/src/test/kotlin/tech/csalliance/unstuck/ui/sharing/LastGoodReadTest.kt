package tech.csalliance.unstuck.ui.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The C11 offline-blanking rule (parity with iOS build 79, audit 2026-09-22):
// People, Shared-with-you and the delegation badges keep what they show when a
// refresh fails, instead of reading empty — but one account's rows never carry
// over to the next. `next` returning null means "keep what is shown" (the flow's
// mapNotNull skips the emission). Pure JVM.
class LastGoodReadTest {

    @Test fun `a good read is shown and a failed read for the same account keeps it`() {
        val hold = LastGoodRead<List<String>>(emptyList())
        assertEquals(listOf("Maya"), hold.next("me", listOf("Maya")))
        assertNull("offline: keep the roster, never 'No one yet'", hold.next("me", null))
        assertNull(hold.next("me", null))
        assertEquals("a real empty answer still empties it", emptyList<String>(), hold.next("me", emptyList()))
    }

    @Test fun `a failed read with nothing held shows empty`() {
        val hold = LastGoodRead<List<String>>(emptyList())
        assertEquals(emptyList<String>(), hold.next("me", null))
        assertEquals(emptyList<String>(), hold.next("me", null))
    }

    @Test fun `signing out empties it and another account never inherits the rows`() {
        val hold = LastGoodRead<Map<String, Int>>(emptyMap())
        assertEquals(mapOf("t1" to 1), hold.next("a", mapOf("t1" to 1)))
        assertEquals("signed out", emptyMap<String, Int>(), hold.next(null, null))
        assertEquals("no user, even with an answer", emptyMap<String, Int>(), hold.next(null, mapOf("t1" to 1)))

        hold.next("a", mapOf("t1" to 1))
        assertEquals("B's failed first read must not show A's rows", emptyMap<String, Int>(), hold.next("b", null))
        assertEquals(emptyMap<String, Int>(), hold.next("a", null))
        assertEquals(mapOf("t2" to 2), hold.next("b", mapOf("t2" to 2)))
        assertNull(hold.next("b", null))
    }
}
