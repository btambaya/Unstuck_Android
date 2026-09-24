package tech.csalliance.unstuck.design

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import tech.csalliance.unstuck.design.component.BottomNavBar
import tech.csalliance.unstuck.design.component.NavSpec
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the bottom bar to PNGs for design review — NOT an assertion suite.
 *
 * Opt-in: every case is skipped (JUnit assumption) unless the
 * `UNSTUCK_RENDER_DIR` env var (or an `unstuck.renderDir` system property set
 * on the TEST JVM — a `-D` on the gradlew command line does not reach it, as
 * the build doesn't forward it) names an output directory, so a normal
 * `:design:test` run never writes files. Run with, e.g.:
 *
 *     UNSTUCK_RENDER_DIR=/tmp/shots ./gradlew :design:testDebugUnitTest \
 *         --tests '*BottomNavBarRenderTest*' --rerun
 *
 * 430 dp wide at density 3 (xxhdpi) — a large-phone width, matching the iOS
 * shots. A stand-in "last task row" sits above the bar so the picture shows
 * whether the + covers list content (the old lifted + did).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w430dp-h932dp-xxhdpi")
class BottomNavBarRenderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val items = listOf(
        NavSpec("today", "Today", Icons.Outlined.Schedule),
        NavSpec("tasks", "Tasks", Icons.Outlined.Inbox),
        NavSpec("calendar", "Calendar", Icons.Outlined.CalendarMonth),
        NavSpec("lists", "Collections", Icons.Outlined.Layers),
    )

    private val outDir: File? =
        (System.getenv("UNSTUCK_RENDER_DIR") ?: System.getProperty("unstuck.renderDir"))
            ?.takeIf { it.isNotBlank() }?.let(::File)

    private fun render(fileName: String, dark: Boolean, activeKey: String) {
        assumeTrue("set UNSTUCK_RENDER_DIR to write the bar PNGs", outDir != null)
        compose.setContent {
            UnstuckTheme(dark = dark) {
                val c = UTheme.colors
                Column(Modifier.fillMaxWidth().background(c.bg).testTag(SHOT)) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp)
                            .clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text("Last task in the list", style = UFont.sans(15, FontWeight.Medium), color = c.ink) }
                    BottomNavBar(items, activeKey, onSelect = {}, onFab = {}, fabLabel = "New task")
                }
            }
        }
        compose.waitForIdle()
        // Software-draw the window and crop to the tagged node. (Compose's own
        // captureToImage() times out under Robolectric: its forceRedraw waits
        // on a frame-commit callback the paused main looper never delivers.)
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

    @Test fun lightToday() = render("android-light-today.png", dark = false, activeKey = "today")
    @Test fun darkToday() = render("android-dark-today.png", dark = true, activeKey = "today")
    @Test fun lightCollections() = render("android-light-lists.png", dark = false, activeKey = "lists")
    @Test fun darkCollections() = render("android-dark-lists.png", dark = true, activeKey = "lists")

    private companion object { const val SHOT = "bar-shot" }
}
