package tech.csalliance.unstuck.ui.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.logic.IsoRange
import tech.csalliance.unstuck.core.model.SharedBlock
import tech.csalliance.unstuck.core.model.ShareLevel

// Android audit 2026-09-23, A16 (review): the calendar's shared blocks now read
// again after every completed pull, about once a minute — and a pull completes
// offline too. The per-window cache used to be dropped on every tick and refilled
// with the failed read's empty answer, so the partner's blocks vanished from the
// calendar every minute while offline. A window now keeps its last good blocks
// until a read replaces them, and one account's windows never reach the next.
// Pure JVM.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedBlockWindowsTest {

    private val week = IsoRange("2026-09-21", "2026-09-27")
    private val nextWeek = IsoRange("2026-09-28", "2026-10-04")

    /** Every value [readInto] emits, in order. */
    private class Shown : FlowCollector<List<SharedBlock>> {
        val values = mutableListOf<List<SharedBlock>>()
        val last get() = values.last()
        override suspend fun emit(value: List<SharedBlock>) { values += value }
    }

    @Test fun `a pull while offline keeps the partner's blocks on the calendar`() = runTest {
        val range = MutableStateFlow<IsoRange?>(week)
        val pulled = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        var online = true
        var reads = 0
        val windows = SharedBlockWindows()
        // AppViewModel.sharedBlocks, with a completed pull as its re-read trigger.
        val sharedBlocks: StateFlow<List<SharedBlock>> =
            combine(
                range,
                pulled.onStart { emit(Unit) }.map { windows.invalidate(); System.nanoTime() },
            ) { r, _ -> r }
                .transform { r ->
                    if (r == null) emit(emptyList())
                    else windows.readInto(this, r, "me", "me") { reads++; if (online) listOf(block("b1")) else null }
                }
                .stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        runCurrent()
        assertEquals(listOf("b1"), sharedBlocks.value.map { it.blockId })

        online = false
        pulled.tryEmit(Unit)   // the floor pull completes, every table failed
        runCurrent()
        assertEquals("it did try again", 2, reads)
        assertEquals("offline: the block stays", listOf("b1"), sharedBlocks.value.map { it.blockId })

        online = true
        pulled.tryEmit(Unit)
        runCurrent()
        assertEquals(3, reads)
        assertEquals(listOf("b1"), sharedBlocks.value.map { it.blockId })
    }

    @Test fun `a window read since the last pull is served without a read, and a real empty answer empties it`() = runTest {
        val windows = SharedBlockWindows()
        val shown = Shown()
        var answer: List<SharedBlock>? = listOf(block("b1"))
        var reads = 0
        val read: suspend () -> List<SharedBlock>? = { reads++; answer }

        windows.readInto(shown, week, "me", "me", read)
        windows.readInto(shown, nextWeek, "me", "me", read)
        windows.readInto(shown, week, "me", "me", read)   // Day ↔ Week, same week
        assertEquals(2, reads)
        assertEquals(listOf("b1"), shown.last.map { it.blockId })

        answer = emptyList()   // the partner unshared it
        windows.invalidate()
        windows.readInto(shown, week, "me", "me", read)
        assertEquals(emptyList<SharedBlock>(), shown.last)
    }

    @Test fun `without the session's token nothing is read and the account's blocks stay`() = runTest {
        val windows = SharedBlockWindows()
        val shown = Shown()
        var reads = 0
        windows.readInto(shown, week, "me", "me") { reads++; listOf(block("b1")) }
        windows.invalidate()
        // A failing token refresh: no current user, the session is still mine.
        windows.readInto(shown, week, null, "me") { reads++; emptyList() }
        assertEquals("an anon call could answer empty", 1, reads)
        assertEquals(listOf("b1"), shown.last.map { it.blockId })
    }

    @Test fun `signing out empties the calendar and the next account never sees the last one's blocks`() = runTest {
        val windows = SharedBlockWindows()
        val shown = Shown()
        windows.readInto(shown, week, "a", "a") { listOf(block("a1")) }
        windows.readInto(shown, nextWeek, "a", "a") { listOf(block("a2")) }

        windows.readInto(shown, week, null, null) { error("no read while signed out") }
        assertEquals(emptyList<SharedBlock>(), shown.last)

        // B's first reads fail (offline): empty, never A's cached windows.
        windows.readInto(shown, week, "b", "b") { null }
        windows.readInto(shown, nextWeek, "b", "b") { null }
        assertEquals(emptyList<SharedBlock>(), shown.last)
    }

    @Test fun `switching straight to another account shows empty before that account's read`() = runTest {
        val windows = SharedBlockWindows()
        val shown = Shown()
        windows.readInto(shown, week, "a", "a") { listOf(block("a1")) }
        shown.values.clear()

        windows.readInto(shown, week, "b", "b") {
            assertEquals("A's blocks are gone while B's read is in flight", listOf(emptyList<SharedBlock>()), shown.values)
            listOf(block("b1"))
        }
        assertEquals(listOf("b1"), shown.last.map { it.blockId })
    }

    @Test fun `a share change during a read makes the next ask read again`() = runTest {
        val windows = SharedBlockWindows()
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val first = launch {
            windows.readInto(Shown(), week, "me", "me") { reads++; gate.await(); listOf(block("old")) }
        }
        runCurrent()
        windows.invalidate()   // the partner moved the block while that read was out
        gate.complete(Unit)
        first.join()

        val shown = Shown()
        windows.readInto(shown, week, "me", "me") { reads++; listOf(block("moved")) }
        assertEquals(2, reads)
        assertEquals(listOf("moved"), shown.last.map { it.blockId })
    }

    private fun block(id: String) = SharedBlock(
        blockId = id, taskId = "t1", shareId = "s1", level = ShareLevel.PARTNER, ownerName = "Sam",
        title = "Plan trip", date = "2026-09-22", startTime = "09:00", durationMinutes = 30,
        done = false, skipped = false, kind = "task",
    )
}
