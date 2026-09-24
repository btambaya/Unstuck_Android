package tech.csalliance.unstuck.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.design.component.MdToggle
import tech.csalliance.unstuck.design.component.TOGGLE_DISABLED_ALPHA
import tech.csalliance.unstuck.design.theme.UnstuckTheme

/**
 * A switch that can't be changed right now (Focus options → Voice replies while
 * the spoken coach is off; AI data sharing during the tour) used to draw exactly
 * like a live one — an ON one full coral next to a greyed label. Disabled, it
 * dims and ignores taps; enabled, it is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MdToggleTest {

    @get:Rule val compose = createComposeRule()

    @Test fun aDisabledSwitchIgnoresTapsAndSaysSo() {
        var changes = 0
        compose.setContent { UnstuckTheme(dark = false) { MdToggle(true, { changes++ }, Modifier.testTag("t"), enabled = false) } }
        compose.onNodeWithTag("t").assertIsNotEnabled().assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(0, changes)
    }

    @Test fun anEnabledSwitchStillToggles() {
        var last: Boolean? = null
        compose.setContent { UnstuckTheme(dark = true) { MdToggle(false, { last = it }, Modifier.testTag("t")) } }
        compose.onNodeWithTag("t").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(true, last)
    }

    @Test fun theDimIsVisibleButLegible() {
        assertTrue(TOGGLE_DISABLED_ALPHA in 0.3f..0.6f)
    }
}
