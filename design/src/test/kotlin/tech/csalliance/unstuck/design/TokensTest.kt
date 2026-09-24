package tech.csalliance.unstuck.design

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.design.color.hexColor
import tech.csalliance.unstuck.design.color.oklch
import tech.csalliance.unstuck.design.theme.UnstuckColors

// Pin the mockup neutral tokens so a future edit can't silently drift them.
class TokensTest {
    private val l = UnstuckColors.light

    @Test fun neutralsMatchMockupHex() {
        assertEquals(hexColor("#FAFAF7"), l.bg)
        assertEquals(hexColor("#F4F2EC"), l.bg2)
        assertEquals(hexColor("#FFFFFF"), l.surface)
        assertEquals(hexColor("#1A1C26"), l.ink)
        assertEquals(hexColor("#414252"), l.ink2)
        assertEquals(hexColor("#6C6E7E"), l.ink3)   // darkened from mockup #7B7D8E for WCAG AA
        assertEquals(hexColor("#B5B6C0"), l.ink4)
        assertEquals(hexColor("#EAE7DD"), l.line)
        assertEquals(hexColor("#D9D5CA"), l.line2)
    }

    @Test fun coralIsTheSoftSalmon() {
        assertEquals(hexColor("#E89077"), l.coral)
        assertEquals(hexColor("#E89077"), UnstuckColors.dark.coral)
    }

    // The accent choice was retired (slim settings, 2026-09-24): the app has ONE
    // palette, and retiring the choice must not repaint it — the primary ramp
    // is still the indigo it always was.
    @Test fun theOnePaletteKeepsTheIndigoPrimary() {
        assertEquals(oklch(0.58, 0.13, 280.0), l.primary)
        assertEquals(oklch(0.72, 0.13, 280.0), UnstuckColors.dark.primary)
    }

    @Test fun areaColorResolvesTokensAndNames() {
        assertEquals(l.coral, l.areaColor("coral"))
        assertEquals(l.coral, l.areaColor("Rethink"))
        assertEquals(l.primary, l.areaColor("indigo"))
        assertEquals(l.green, l.areaColor("Bug"))
        assertEquals(l.ink4, l.areaColor(null))
        assertEquals(l.ink4, l.areaColor("nonsense"))
    }
}
