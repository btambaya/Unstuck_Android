package tech.csalliance.unstuck.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.design.theme.UnstuckColors

// The month view's heat ramp went neutral (bg2 → ink3) when indigo stopped
// being an accent (owner decision 2026-09-24). The day number picks bg or ink2
// by contrast on its cell, rather than flipping at the ramp's midpoint — the
// midpoint flip left a mid-grey day at ~2:1.
class MonthHeatTest {

    private val palettes = listOf(UnstuckColors.light, UnstuckColors.dark)

    @Test fun theRampIsNeutralAndNeverIndigo() {
        for (c in palettes) {
            for (i in 0..20) {
                val fill = monthHeat(c, i / 20f)
                assertTrue(fill != c.primary && fill != c.primarySoft && fill != c.primaryDeep)
            }
            // Ends: a light day is a step off bg2, the busiest is most of the way to ink3.
            assertEquals(androidx.compose.ui.graphics.lerp(c.bg2, c.ink3, 0.2f), monthHeat(c, 0f))
            assertEquals(androidx.compose.ui.graphics.lerp(c.bg2, c.ink3, 0.8f), monthHeat(c, 1f))
        }
    }

    @Test fun theDayNumberReadsOnEveryCell() {
        for (c in palettes) {
            for (i in 0..100) {
                val fill = monthHeat(c, i / 100f)
                val best = maxOf(contrastRatio(c.bg, fill), contrastRatio(c.ink2, fill))
                assertTrue("${if (c.isDark) "dark" else "light"} t=${i / 100f}: $best", best >= 3f)
            }
        }
    }
}
