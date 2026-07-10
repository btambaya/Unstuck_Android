package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.ShareBadge
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.assignedOutIds
import tech.csalliance.unstuck.core.model.assignedOutMap

// Mirrors components/tasks/delegated-group.test.ts — the assignedOut derivations
// that drive the Delegated group (assign-level shares leave the active list) and
// the Start-Next exclusion (an assigned-away task is someone else's now).
class DelegatedTest {

    private fun badge(taskId: String, level: ShareLevel, name: String) = ShareBadge(taskId, level, name)

    // Share badges keyed by taskId, as produced by myTaskShareBadges (my_task_share_badges).
    private val badges: Map<String, List<ShareBadge>> = mapOf(
        "t1" to listOf(badge("t1", ShareLevel.ASSIGN, "Bob")),
        "t2" to listOf(badge("t2", ShareLevel.VIEW, "Cara")),
        "t3" to listOf(badge("t3", ShareLevel.PARTNER, "Dee"), badge("t3", ShareLevel.ASSIGN, "Eve")),
    )

    @Test fun `assignedOutMap maps only assign-level tasks to their assignee name`() {
        assertEquals(mapOf("t1" to "Bob", "t3" to "Eve"), assignedOutMap(badges))
    }

    @Test fun `assignedOutMap ignores view and partner-only tasks`() {
        assertEquals(emptyMap<String, String>(), assignedOutMap(mapOf("t2" to listOf(badge("t2", ShareLevel.VIEW, "Cara")))))
    }

    @Test fun `assignedOutMap is empty for no badges`() {
        assertEquals(emptyMap<String, String>(), assignedOutMap(emptyMap()))
    }

    @Test fun `assignedOutIds returns the set of task ids assigned away`() {
        val ids = assignedOutIds(badges)
        assertTrue(ids.contains("t1"))
        assertTrue(ids.contains("t3"))
        assertFalse(ids.contains("t2"))
        assertEquals(2, ids.size)
    }

    @Test fun `assignedOutIds excludes tasks shared only at view or partner`() {
        val ids = assignedOutIds(
            mapOf(
                "a" to listOf(badge("a", ShareLevel.VIEW, "X")),
                "b" to listOf(badge("b", ShareLevel.PARTNER, "Y")),
            ),
        )
        assertEquals(0, ids.size)
    }
}
