package tech.csalliance.unstuck.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** dismissKeyboardOnDrag's decision: which way a finger has started to drag. */
class DragAxisTest {
    private val slop = 18f

    @Test fun `within the touch slop it is not a drag yet`() {
        assertNull(dragAxis(0f, 0f, slop))
        assertNull(dragAxis(17f, -18f, slop))
    }

    @Test fun `up or down past the slop is a vertical drag - the keyboard goes`() {
        assertEquals(DragAxis.VERTICAL, dragAxis(0f, 19f, slop))
        assertEquals(DragAxis.VERTICAL, dragAxis(5f, -40f, slop))
    }

    @Test fun `a sideways swipe (a row's actions) is horizontal - the keyboard stays`() {
        assertEquals(DragAxis.HORIZONTAL, dragAxis(-19f, 3f, slop))
        assertEquals(DragAxis.HORIZONTAL, dragAxis(30f, 29f, slop))
    }

    @Test fun `a dead-even diagonal counts as sideways - it never drops the keyboard by accident`() {
        assertEquals(DragAxis.HORIZONTAL, dragAxis(25f, 25f, slop))
    }
}
