package tech.csalliance.unstuck.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The one coral + creates the thing you're looking at.
 *
 * Today / Tasks / Calendar keep New task (unchanged — the guided tour's
 * first-action step falls back to this button on the Tasks tab, so a regression
 * here would break the tour); the Collections grid creates a collection.
 */
class FabActionTest {

    @Test
    fun todayTasksAndCalendarStillCreateATask() {
        assertEquals(FabAction.NEW_TASK, fabAction("today"))
        assertEquals(FabAction.NEW_TASK, fabAction("tasks"))
        assertEquals(FabAction.NEW_TASK, fabAction("calendar"))
    }

    @Test
    fun collectionsTabCreatesACollection() {
        assertEquals(FabAction.NEW_COLLECTION, fabAction(TAB_COLLECTIONS))
        assertEquals(FabAction.NEW_COLLECTION, fabAction("lists"))
    }

    /** An unknown / restored-garbage tab key must never leave the + inert. */
    @Test
    fun unknownTabFallsBackToNewTask() {
        assertEquals(FabAction.NEW_TASK, fabAction(""))
        assertEquals(FabAction.NEW_TASK, fabAction("nope"))
        // The tab keys are case-sensitive ids, not display labels.
        assertEquals(FabAction.NEW_TASK, fabAction("Lists"))
        assertEquals(FabAction.NEW_TASK, fabAction("collections"))
    }

    /** Every real tab in the bar resolves — nothing falls off the end. */
    @Test
    fun everyNavTabIsCovered() {
        val byKey = NAV.associate { it.key to fabAction(it.key) }
        assertEquals(
            mapOf(
                "today" to FabAction.NEW_TASK,
                "tasks" to FabAction.NEW_TASK,
                "calendar" to FabAction.NEW_TASK,
                "lists" to FabAction.NEW_COLLECTION,
            ),
            byKey,
        )
    }

    /** Renaming the Collections tab key must not silently revert the +. */
    @Test
    fun collectionsTabKeyMatchesTheNavSpec() {
        assertEquals(TAB_COLLECTIONS, NAV.first { it.label == "Collections" }.key)
    }

    @Test
    fun labelNamesWhatTheButtonCreates() {
        assertEquals("New task", fabLabel(FabAction.NEW_TASK))
        assertEquals("New collection", fabLabel(FabAction.NEW_COLLECTION))
        // TalkBack is the only place the + 's meaning is ever spoken, so the two
        // actions must not share a label.
        assertNotEquals(fabLabel(FabAction.NEW_TASK), fabLabel(FabAction.NEW_COLLECTION))
    }
}
