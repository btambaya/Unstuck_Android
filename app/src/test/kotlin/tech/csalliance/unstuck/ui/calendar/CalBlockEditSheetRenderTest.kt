package tech.csalliance.unstuck.ui.calendar

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.csalliance.unstuck.core.logic.CalBlockSheetActions
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the calendar Edit-block sheet's states to PNGs for design review —
 * NOT an assertion suite (CalBlockEditSheetTest is).
 *
 * Opt-in: every case is skipped (JUnit assumption) unless the
 * `UNSTUCK_RENDER_DIR` env var (or an `unstuck.renderDir` system property set on
 * the TEST JVM) names an output directory, so a normal `:app:testDebugUnitTest`
 * run never writes files. Run with, e.g.:
 *
 *     UNSTUCK_RENDER_DIR=/tmp/shots ./gradlew :app:testDebugUnitTest \
 *         --tests '*CalBlockEditSheetRenderTest*' --rerun
 *
 * The body is drawn inside a stand-in sheet (surface, rounded top, handle): the
 * real ModalBottomSheet opens its own window, which a window capture misses.
 * 411 dp wide at density 3, like the other :app screen tests. Native graphics
 * get their own Robolectric sandbox, away from the legacy-graphics Compose tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class CalBlockEditSheetRenderTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // Declares the compose rule's host activity first (see MainScaffoldFabTest).
    private val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
                shadowOf(ctx.packageManager).addActivityIfNotPresent(ComponentName(ctx, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    private val outDir: File? =
        (System.getenv("UNSTUCK_RENDER_DIR") ?: System.getProperty("unstuck.renderDir"))
            ?.takeIf { it.isNotBlank() }?.let(::File)

    private val row = TaskItem(
        id = "t1", name = "Write the quarterly report", estimateMin = 45,
        createdAt = "2026-09-20T10:00:00.000Z", updatedAt = "2026-09-20T10:00:00.000Z",
    )

    private fun render(fileName: String, dark: Boolean, actions: CalBlockSheetActions) {
        assumeTrue("set UNSTUCK_RENDER_DIR to write the sheet PNGs", outDir != null)
        compose.setContent {
            UnstuckTheme(dark = dark) {
                val c = UTheme.colors
                Box(Modifier.fillMaxWidth().background(c.bg).padding(top = 24.dp).testTag(SHOT)) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface)) {
                        Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp), contentAlignment = Alignment.Center) { SheetHandle() }
                        CalBlockEditSheetBody(
                            taskName = row.name, actions = actions, times = listOf("09:00", "10:30", "13:00", "15:15", "16:45"),
                            startTime = "09:00", durationMinutes = 45, clock = ClockMode.H12,
                            onToggleDone = {}, onStartFocus = {}, onOpenTask = {}, onPickTime = {}, onPickDuration = {}, onUnschedule = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // Software-draw the window and crop to the tagged node (captureToImage
        // times out under Robolectric; see BottomNavBarRenderTest).
        val bounds = compose.onNodeWithTag(SHOT).fetchSemanticsNode().boundsInWindow
        val shot = compose.runOnIdle {
            val root = compose.activity.window.decorView
            val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(full))
            Bitmap.createBitmap(full, bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt())
        }
        val dir = outDir!!.apply { mkdirs() }
        File(dir, fileName).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val open get() = CalBlockSheetActions(row = row, canComplete = true, canFocus = true)
    private val done get() = CalBlockSheetActions(row = row.copy(done = true), canComplete = true, canFocus = true)
    private val assigned get() = CalBlockSheetActions(row = row, canComplete = false, canFocus = false, assignedTo = "zubair@example.com")

    @Test fun lightOpen() = render("android-light-open.png", dark = false, actions = open)
    @Test fun lightDone() = render("android-light-done.png", dark = false, actions = done)
    @Test fun lightAssignedOut() = render("android-light-assigned-out.png", dark = false, actions = assigned)
    @Test fun lightNoTask() = render("android-light-no-task.png", dark = false, actions = CalBlockSheetActions.NONE)
    @Test fun darkOpen() = render("android-dark-open.png", dark = true, actions = open)
    @Test fun darkDone() = render("android-dark-done.png", dark = true, actions = done)

    private companion object { const val SHOT = "calblock-sheet-shot" }
}
