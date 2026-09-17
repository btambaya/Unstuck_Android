package tech.csalliance.unstuck.design

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.design.color.hexColor
import tech.csalliance.unstuck.design.color.oklch
import tech.csalliance.unstuck.design.theme.AccentPalette
import tech.csalliance.unstuck.design.theme.UnstuckColors
import tech.csalliance.unstuck.design.theme.withAccent

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

    // The accent remap is per SCHEME (web globals.css `.u-dark[data-u-accent]`):
    // the dark ramps must be the web's DARK values, never the light ones laid
    // over the dark palette (a primaryDeep of L 0.42 on a 0.205 background).
    @Test fun accentRampsFollowTheSchemeLikeTheWeb() {
        val d = UnstuckColors.dark
        for (accent in listOf(AccentPalette.PERIWINKLE_ROSE, AccentPalette.FOREST_AMBER)) {
            val light = l.withAccent(accent)
            val dark = d.withAccent(accent)
            assertNotEquals("$accent: dark primary is its own value", light.primary, dark.primary)
            assertNotEquals("$accent: dark primaryDeep is its own value", light.primaryDeep, dark.primaryDeep)
            assertNotEquals("$accent: dark primarySoft is its own value", light.primarySoft, dark.primarySoft)
            assertNotEquals("$accent: dark coralSoft is its own value", light.coralSoft, dark.coralSoft)
            // coral + coralDeep keep the light accent values in dark (the web overrides neither).
            assertEquals(light.coral, dark.coral)
            assertEquals(light.coralDeep, dark.coralDeep)
            // Dark ramps are LIGHTER than the light ones (they sit on a dark ground).
            assertTrue("$accent: dark primaryDeep reads on a dark ground", dark.primaryDeep.luminance() > light.primaryDeep.luminance())
            assertTrue("$accent: dark primarySoft is a dark capsule", dark.primarySoft.luminance() < light.primarySoft.luminance())
            assertTrue(dark.isDark); assertFalse(light.isDark)
        }
        // The rose dark ramp is the web's verbatim.
        assertEquals(oklch(0.74, 0.13, 265.0), d.withAccent(AccentPalette.PERIWINKLE_ROSE).primary)
        assertEquals(oklch(0.82, 0.12, 265.0), d.withAccent(AccentPalette.PERIWINKLE_ROSE).primaryDeep)
        assertEquals(oklch(0.32, 0.07, 265.0), d.withAccent(AccentPalette.PERIWINKLE_ROSE).primarySoft)
        assertEquals(oklch(0.36, 0.08, 15.0), d.withAccent(AccentPalette.PERIWINKLE_ROSE).coralSoft)
        assertEquals(oklch(0.70, 0.11, 170.0), d.withAccent(AccentPalette.FOREST_AMBER).primary)
        assertEquals(oklch(0.80, 0.10, 170.0), d.withAccent(AccentPalette.FOREST_AMBER).primaryDeep)
        // Indigo/coral is the base palette untouched.
        assertEquals(d, d.withAccent(AccentPalette.INDIGO_CORAL))
        assertEquals(l, l.withAccent(AccentPalette.INDIGO_CORAL))
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
