package tech.csalliance.unstuck.design

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.csalliance.unstuck.design.component.FilterPill
import tech.csalliance.unstuck.design.component.MdSegment
import tech.csalliance.unstuck.design.component.MdToggle
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the shared controls to PNGs for colour review — NOT an assertion
 * suite (ColourRolesTest is that). Two boards, light and dark:
 *
 *  - `controls`: the one switch every settings row uses (MdToggle) on and off,
 *    beside the selection idiom (FilterPill, MdSegment) it sits with;
 *  - `m3-defaults`: what a stock Material3 component draws inside
 *    UnstuckTheme when a call site passes no colours — a text button, a
 *    focused outlined field, a progress ring, the date and time pickers.
 *
 * Opt-in, like BottomNavBarRenderTest: skipped (a JUnit assumption) unless
 * `UNSTUCK_RENDER_DIR` names an output directory.
 *
 *     UNSTUCK_RENDER_DIR=/tmp/shots ./gradlew :design:testDebugUnitTest \
 *         --tests '*ColourControlsRenderTest*' --rerun
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w412dp-h1900dp-xxhdpi")
class ColourControlsRenderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir: File? =
        (System.getenv("UNSTUCK_RENDER_DIR") ?: System.getProperty("unstuck.renderDir"))
            ?.takeIf { it.isNotBlank() }?.let(::File)

    private fun render(fileName: String, dark: Boolean, content: @Composable () -> Unit) {
        assumeTrue("set UNSTUCK_RENDER_DIR to write the control PNGs", outDir != null)
        compose.setContent {
            UnstuckTheme(dark = dark) {
                Column(Modifier.fillMaxWidth().background(UTheme.colors.bg).padding(20.dp).testTag(SHOT)) { content() }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(SHOT).fetchSemanticsNode().boundsInWindow
        val shot = compose.runOnIdle {
            val root = compose.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(full))
            Bitmap.createBitmap(
                full, bounds.left.roundToInt(), bounds.top.roundToInt(),
                bounds.width.roundToInt(), bounds.height.roundToInt(),
            )
        }
        val dir = outDir!!.apply { mkdirs() }
        File(dir, fileName).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun ToggleRow(label: String, on: Boolean) {
        val c = UTheme.colors
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = UFont.sans(14, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
            MdToggle(on, {})
        }
    }

    @Composable
    private fun ControlsBoard() {
        SectionLabel("Switches")
        ToggleRow("Morning brief (on)", on = true)
        ToggleRow("Evening preview (off)", on = false)
        Spacer(Modifier.padding(6.dp))
        SectionLabel("Selection")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterPill("All", selected = true) {}
            FilterPill("Work", selected = false, dotColor = UTheme.colors.blue) {}
            FilterPill("Home", selected = false, dotColor = UTheme.colors.green) {}
        }
        MdSegment(listOf("Day", "Week", "Month"), selected = "Week", modifier = Modifier.padding(top = 10.dp)) {}
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun M3DefaultsBoard() {
        val focus = remember { FocusRequester() }
        SectionLabel("Stock Material3, no colours passed")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {}) { Text("OK") }
            TextButton(onClick = {}) { Text("Cancel") }
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(progress = { 0.65f })
        }
        OutlinedTextField(
            value = "Focused field", onValueChange = {}, label = { Text("Label") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focus),
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
        DatePicker(state = rememberDatePickerState(initialSelectedDateMillis = SEPT_24_UTC))
        TimePicker(state = rememberTimePickerState(initialHour = 9, initialMinute = 30, is24Hour = false))
    }

    @Test fun controlsLight() = render("android-controls-light.png", dark = false) { ControlsBoard() }
    @Test fun controlsDark() = render("android-controls-dark.png", dark = true) { ControlsBoard() }
    @Test fun m3DefaultsLight() = render("android-m3-defaults-light.png", dark = false) { M3DefaultsBoard() }
    @Test fun m3DefaultsDark() = render("android-m3-defaults-dark.png", dark = true) { M3DefaultsBoard() }

    private companion object {
        const val SHOT = "controls-shot"
        /** 2026-09-24T00:00Z — Material3's DatePicker keys dates at UTC midnight. */
        const val SEPT_24_UTC = 1_790_208_000_000L
    }
}
