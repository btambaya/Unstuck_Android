package tech.csalliance.unstuck.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.design.color.oklchToRgb

// Verify the oklch→sRGB conversion against known anchors so the brand palette
// renders the intended colors. Ported from OKLCHTests.swift.
class OklchTest {
    private fun rgb(l: Double, c: Double, h: Double) = oklchToRgb(l, c, h)

    @Test fun white() {
        val (r, g, b) = rgb(1.0, 0.0, 0.0)
        assertEquals(1.0, r, 0.01); assertEquals(1.0, g, 0.01); assertEquals(1.0, b, 0.01)
    }

    @Test fun black() {
        val (r, g, b) = rgb(0.0, 0.0, 0.0)
        assertEquals(0.0, r, 0.01); assertEquals(0.0, g, 0.01); assertEquals(0.0, b, 0.01)
    }

    @Test fun midGrayIsNeutral() {
        val (r, g, b) = rgb(0.6, 0.0, 0.0)
        assertEquals(r, g, 0.005); assertEquals(g, b, 0.005)
        assertTrue(r > 0.4); assertTrue(r < 0.75)
    }

    @Test fun inkIsDarkBluish() {
        val (r, g, b) = rgb(0.22, 0.02, 280.0)
        assertTrue(r < 0.35); assertTrue(g < 0.35); assertTrue(b < 0.4)
    }

    @Test fun coralIsWarm() {
        val (r, g, b) = rgb(0.72, 0.13, 35.0)
        assertTrue(r > g); assertTrue(g > b)
    }

    @Test fun primaryIndigoIsBlueDominant() {
        val (r, g, b) = rgb(0.58, 0.13, 280.0)
        assertTrue(b > r); assertTrue(b > g)
    }

    @Test fun clampStaysInGamut() {
        val (r, g, b) = rgb(0.5, 0.4, 120.0)
        for (v in listOf(r, g, b)) { assertTrue(v >= 0.0); assertTrue(v <= 1.0) }
    }
}
