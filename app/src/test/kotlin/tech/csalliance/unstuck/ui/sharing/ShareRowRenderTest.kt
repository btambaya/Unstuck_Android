package tech.csalliance.unstuck.ui.sharing

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.logic.SharePick
import tech.csalliance.unstuck.core.logic.sharePeopleSplit
import tech.csalliance.unstuck.core.logic.shareWithSummary
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.ShareLevel.PARTNER
import tech.csalliance.unstuck.core.model.ShareLevel.VIEW
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain
import tech.csalliance.unstuck.ui.tasks.NewTaskSheet
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the New task sheet's "Share with…" row and the pre-create Share
 * screen to PNGs for design review — NOT an assertion suite.
 *
 * Opt-in like design's BottomNavBarRenderTest: every case is skipped unless
 * `UNSTUCK_RENDER_DIR` (or an `unstuck.renderDir` system property on the test
 * JVM) names an output directory, so a normal `:app:testDebugUnitTest` run
 * writes nothing. Run with, e.g.:
 *
 *     UNSTUCK_RENDER_DIR=/tmp/shots ./gradlew :app:testDebugUnitTest \
 *         --tests '*ShareRowRenderTest*' --rerun
 *
 * The sheet contents are drawn outside their ModalBottomSheet (ShareScreenBody
 * / PeoplePickerBody on the sheet's `surface`); the in-context New task shot
 * and the open person menu composite every window (dialog + popup) instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h1400dp-xxhdpi")
class ShareRowRenderTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

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

    private val roster = listOf(
        preCreateMember("c1", "u1", "Maya Chen", "Coach"),
        preCreateMember("c2", "u2", "James Wilson"),
        preCreateMember("c3", "u3", "Anna Okafor", "Sister"),
        preCreateMember("c4", "u4", "Zubair Kazaure"),
        preCreateMember("c5", "u5", "Sam Lee"),
    )

    private var vm: AppViewModel? = null
    private var db: UnstuckDatabase? = null
    private val drain = ViewModelDrain()

    @After fun teardown() {
        vm?.let { drain.track(it); drain.drain() }
        db?.close()
    }

    private fun precreate(picks: Map<String, ShareLevel>) =
        ShareScreenModel(ShareTarget.NewTask("Plan the Lisbon trip"), transport = PreCreateFakeTransport(roster), initialPicks = picks)
            .also { runBlocking { it.load() } }

    // ── the row ─────────────────────────────────────────────────────────────

    private val rowStates: List<List<SharePick>> = listOf(
        emptyList(),
        listOf(SharePick("James Wilson", PARTNER)),
        listOf(SharePick("James Wilson", PARTNER), SharePick("Anna Okafor", PARTNER)),
        listOf(SharePick("James Wilson", PARTNER), SharePick("Anna Okafor", VIEW)),
        listOf(SharePick("James Wilson", PARTNER), SharePick("Anna Okafor", PARTNER), SharePick("Zubair", PARTNER), SharePick("Sam", PARTNER)),
        // 3 picked, mixed grades: too long in full, no single grade to show.
        listOf(SharePick("James Wilson", PARTNER), SharePick("Anna Okafor", VIEW), SharePick("Zubair Kazaure", PARTNER)),
        listOf(SharePick("Bartholomew-Alexandros Papadopoulos", PARTNER)),
    )

    private fun rows(file: String, dark: Boolean) = shoot(file, dark) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Share")
            rowStates.forEach { ShareWithRow(shareWithSummary(it)) {} }
        }
    }

    @Test fun rowStatesLight() = rows("share-row-states-light.png", dark = false)
    @Test fun rowStatesDark() = rows("share-row-states-dark.png", dark = true)

    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    @Test fun rowStatesSmallPhone() = rows("share-row-states-360dp-light.png", dark = false)

    // ── the pre-create Share screen ─────────────────────────────────────────

    private fun screen(file: String, dark: Boolean, model: ShareScreenModel) = shoot(file, dark) {
        val s by model.state.collectAsState()
        ShareScreenBody(model, s, onDone = {}, onChoose = {}, onReport = {}, onBlock = {}, modifier = Modifier.padding(top = 20.dp))
    }

    /** Opened from a sheet that already holds James (edit) + Anna (view), then
     *  Zubair picked from "Choose someone". */
    private fun pickedModel() = precreate(mapOf("u2" to PARTNER, "u3" to VIEW)).also { m ->
        runBlocking { m.tap(m.state.value.people.first { it.name == "Zubair Kazaure" }) }
    }

    @Test fun screenPickedLight() = screen("share-precreate-picked-light.png", dark = false, model = pickedModel())
    @Test fun screenPickedDark() = screen("share-precreate-picked-dark.png", dark = true, model = pickedModel())
    @Test fun screenFreshLight() = screen("share-precreate-fresh-light.png", dark = false, model = precreate(emptyMap()))

    private fun picker(file: String, dark: Boolean) {
        val m = precreate(mapOf("u2" to PARTNER, "u3" to VIEW))
        val split = sharePeopleSplit(m.state.value.people, m.state.value.pinnedIds, handOver = false)
        shoot(file, dark) {
            PeoplePickerBody("Share with", "Share", split.candidates, query = "", onQuery = {}, onPick = {}, onCancel = {})
        }
    }

    @Test fun pickerLight() = picker("share-precreate-choose-someone-light.png", dark = false)
    @Test fun pickerDark() = picker("share-precreate-choose-someone-dark.png", dark = true)

    /** A picked person's menu: Can edit ✓ / Can view / Hand over / Remove. */
    @Test fun personMenuLight() {
        assumeTrue("set UNSTUCK_RENDER_DIR to write the PNGs", outDir != null)
        val m = precreate(mapOf("u2" to PARTNER, "u3" to VIEW))
        setShot(dark = false) {
            val s by m.state.collectAsState()
            ShareScreenBody(m, s, onDone = {}, onChoose = {}, onReport = {}, onBlock = {}, modifier = Modifier.padding(top = 20.dp))
        }
        compose.onNodeWithContentDescription("James Wilson, Can edit. Change access").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        save("share-precreate-person-menu-light.png", crop(captureAllWindows(), tagBounds()))
    }

    // ── in context: the real New task sheet, More options open ──────────────

    private fun sheetInContext(file: String, dark: Boolean) {
        assumeTrue("set UNSTUCK_RENDER_DIR to write the PNGs", outDir != null)
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val d = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build().also { db = it }
        val store = LocalStore(d)
        val graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { UID })
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        val v = AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { UID }).also { vm = it }
        compose.setContent {
            UnstuckTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(UTheme.colors.bg))
                NewTaskSheet(v, onDismiss = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("More options ▾").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Share with, Only you").performScrollTo()
        compose.waitForIdle()
        save(file, captureAllWindows())
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun newTaskSheetLight() = sheetInContext("new-task-sheet-share-row-light.png", dark = false)

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun newTaskSheetDark() = sheetInContext("new-task-sheet-share-row-dark.png", dark = true)

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun setShot(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            UnstuckTheme(dark = dark) {
                Box(Modifier.fillMaxWidth().background(UTheme.colors.surface).testTag(SHOT)) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(file: String, dark: Boolean, content: @Composable () -> Unit) {
        assumeTrue("set UNSTUCK_RENDER_DIR to write the PNGs", outDir != null)
        setShot(dark, content)
        // Software-draw the window and crop to the tagged node (Compose's own
        // captureToImage() times out under Robolectric — BottomNavBarRenderTest).
        save(file, crop(compose.runOnIdle { drawView(compose.activity.window.decorView) }, tagBounds()))
    }

    private fun tagBounds(): Rect = compose.onNodeWithTag(SHOT).fetchSemanticsNode().boundsInWindow

    private fun drawView(root: View): Bitmap =
        Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }

    /** Every window (the activity, a bottom-sheet dialog, a dropdown popup),
     *  drawn in z-order at its position. */
    @Suppress("UNCHECKED_CAST")
    private fun captureAllWindows(): Bitmap = compose.runOnIdle {
        val decor = compose.activity.window.decorView
        val out = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        fun <T> list(name: String) = wmg.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(wmg) as List<T>
        val views = list<View>("mViews")
        val params = list<WindowManager.LayoutParams>("mParams")
        views.forEachIndexed { i, v ->
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            val p = params.getOrNull(i)
            // A popup's window may report (0,0); its LayoutParams carry the spot.
            val dx = if (loc[0] == 0 && p != null && p.width != WindowManager.LayoutParams.MATCH_PARENT) p.x else loc[0]
            val dy = if (loc[1] == 0 && p != null && p.height != WindowManager.LayoutParams.MATCH_PARENT) p.y else loc[1]
            canvas.save()
            canvas.translate(dx.toFloat(), dy.toFloat())
            v.draw(canvas)
            canvas.restore()
        }
        out
    }

    private fun crop(full: Bitmap, r: Rect): Bitmap = Bitmap.createBitmap(
        full, r.left.roundToInt(), r.top.roundToInt(),
        r.width.roundToInt().coerceAtMost(full.width - r.left.roundToInt()),
        r.height.roundToInt().coerceAtMost(full.height - r.top.roundToInt()),
    )

    private fun save(file: String, bmp: Bitmap) {
        val dir = outDir!!.apply { mkdirs() }
        File(dir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SHOT = "share-shot"
        const val UID = "00000000-0000-0000-0000-00000000c0de"
    }
}
