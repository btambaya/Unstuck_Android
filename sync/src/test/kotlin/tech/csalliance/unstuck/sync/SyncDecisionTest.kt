package tech.csalliance.unstuck.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind

// Mirrors SyncDecisionsTests.swift — the cache-wipe rule + the cal_blocks
// hydrate merge that preserves local external (g_) blocks.
class SyncDecisionTest {

    @Test fun signedInAlwaysWipes() {
        assertTrue(SyncDecision.shouldWipeCache(SyncAuthEvent.SIGNED_IN, "u1", "u1"))
        assertTrue(SyncDecision.shouldWipeCache(SyncAuthEvent.SIGNED_IN, null, "u1"))
    }

    @Test fun initialSessionWipesOnlyIfUserChanged() {
        assertFalse(SyncDecision.shouldWipeCache(SyncAuthEvent.INITIAL_SESSION, "u1", "u1"))
        assertTrue(SyncDecision.shouldWipeCache(SyncAuthEvent.INITIAL_SESSION, "u1", "u2"))
        assertTrue(SyncDecision.shouldWipeCache(SyncAuthEvent.INITIAL_SESSION, null, "u1"))
    }

    @Test fun userUpdatedNeverWipes() {
        assertFalse(SyncDecision.shouldWipeCache(SyncAuthEvent.USER_UPDATED, "u1", "u2"))
    }

    @Test fun mergePreservesLocalExternalBlocks() {
        val remote = listOf(CalBlock(id = "r1", taskId = "t1", taskName = "Task", startTime = "09:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK))
        val localExternal = listOf(CalBlock(id = "g_abc", taskId = null, taskName = "Meeting", startTime = "10:00", durationMinutes = 30, date = "2026-05-21", externalEventId = "abc", kind = CalBlockKind.EXTERNAL))
        val merged = SyncDecision.mergeHydratedCalBlocks(remote, localExternal)
        assertEquals(setOf("r1", "g_abc"), merged.map { it.id }.toSet())
    }

    @Test fun mergeDropsLocalNonExternalAndRemoteWinsOnClash() {
        val remote = listOf(CalBlock(id = "x", taskId = "t1", taskName = "Server", startTime = "09:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK))
        val local = listOf(
            CalBlock(id = "x", taskId = "t1", taskName = "StaleLocal", startTime = "08:00", durationMinutes = 10, date = "2026-05-21", externalEventId = "e", kind = CalBlockKind.EXTERNAL),
            CalBlock(id = "localTask", taskId = "t2", taskName = "LocalOnlyTask", startTime = "11:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK),
        )
        val merged = SyncDecision.mergeHydratedCalBlocks(remote, local)
        assertEquals(setOf("x"), merged.map { it.id }.toSet())
        assertEquals("Server", merged.first { it.id == "x" }.taskName)
    }
}
