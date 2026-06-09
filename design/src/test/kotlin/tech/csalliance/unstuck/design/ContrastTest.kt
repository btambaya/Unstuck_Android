package tech.csalliance.unstuck.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.design.theme.UnstuckColors

// Guard the neutral-ramp contrast: ink3 colors 11-13sp meta text app-wide, so it
// must clear the WCAG AA 4.5:1 threshold on the white cards it usually sits on.
// (The mockup's original #7B7D8E measured ~4.07:1 and failed.)
class ContrastTest {
    private fun ratio(fg: Color, bg: Color): Double {
        val lf = fg.luminance().toDouble()
        val lb = bg.luminance().toDouble()
        return (maxOf(lf, lb) + 0.05) / (minOf(lf, lb) + 0.05)
    }

    @Test fun lightInk3MeetsAaOnWhiteSurface() {
        val l = UnstuckColors.light
        assertTrue("light ink3 on surface must be >= 4.5:1", ratio(l.ink3, l.surface) >= 4.5)
    }

    @Test fun lightInk2MeetsAaOnTintedBg2() {
        // MdSegment's inactive 11sp labels render ink2 on the bg2 track.
        val l = UnstuckColors.light
        assertTrue("light ink2 on bg2 must be >= 4.5:1", ratio(l.ink2, l.bg2) >= 4.5)
    }
}
