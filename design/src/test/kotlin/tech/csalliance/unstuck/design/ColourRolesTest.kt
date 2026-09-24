package tech.csalliance.unstuck.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.design.component.toggleTrackColor
import tech.csalliance.unstuck.design.theme.UnstuckColors
import tech.csalliance.unstuck.design.theme.unstuckColorScheme

// Owner decision 2026-09-24: every ON switch is the brand coral; indigo is no
// longer an accent anywhere — selection is the black-and-white pair.
class ColourRolesTest {

    private val palettes = listOf(UnstuckColors.light, UnstuckColors.dark)

    @Test fun anOnSwitchIsCoralAndAnOffOneIsTheHairlineGrey() {
        for (p in palettes) {
            assertEquals(p.coral, toggleTrackColor(p, checked = true))
            assertEquals(p.line2, toggleTrackColor(p, checked = false))
            // Never the rust coralDeep, never the old green.
            assertNotEquals(p.coralDeep, toggleTrackColor(p, checked = true))
            assertNotEquals(p.green, toggleTrackColor(p, checked = true))
        }
    }

    /** A stock Material3 component (TextButton, OutlinedTextField, the date and
     *  time pickers, a menu, a dialog) draws from these roles when its call site
     *  passes no colours. Each must be one of the app's own colours — never the
     *  indigo ramp, never Material's baseline lavender / pink. */
    @Test fun materialRolesAreTheSelectionPairAndTheAppsNeutrals() {
        for (p in palettes) {
            val s = unstuckColorScheme(p)
            val allowed = setOf(p.ink, p.ink2, p.bg, p.bg2, p.surface, p.line, p.line2, p.coral, p.red, Color.White)
            val indigo = setOf(p.primary, p.primarySoft, p.primaryDeep, p.violet)
            val roles = mapOf(
                "primary" to s.primary, "onPrimary" to s.onPrimary,
                "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
                "inversePrimary" to s.inversePrimary,
                "secondary" to s.secondary, "secondaryContainer" to s.secondaryContainer,
                "onSecondaryContainer" to s.onSecondaryContainer,
                "tertiary" to s.tertiary, "tertiaryContainer" to s.tertiaryContainer,
                "onTertiaryContainer" to s.onTertiaryContainer,
                "background" to s.background, "surface" to s.surface, "onSurface" to s.onSurface,
                "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
                "surfaceTint" to s.surfaceTint, "inverseSurface" to s.inverseSurface,
                "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
                "outline" to s.outline, "outlineVariant" to s.outlineVariant,
            )
            for ((name, color) in roles) {
                assertTrue("${if (p.isDark) "dark" else "light"} $name is $color — not an Unstuck colour", color in allowed)
                assertTrue("${if (p.isDark) "dark" else "light"} $name is indigo", color !in indigo)
            }
            // Selection is ink fill with bg text.
            assertEquals(p.ink, s.primary)
            assertEquals(p.bg, s.onPrimary)
        }
    }

    /** Repainting the USES must not repaint the palette: an area a user coloured
     *  "indigo" still draws indigo. */
    @Test fun thePaletteKeepsItsIndigoForUserPickedAreaColours() {
        for (p in palettes) assertEquals(p.primary, p.areaColor("indigo"))
    }
}
