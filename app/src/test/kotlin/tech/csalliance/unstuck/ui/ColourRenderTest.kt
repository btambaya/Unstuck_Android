package tech.csalliance.unstuck.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
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
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.auth.AuthScreen
import tech.csalliance.unstuck.ui.focus.CaptureSheet
import tech.csalliance.unstuck.ui.onboarding.OnboardingScreen
import java.io.File
import java.time.LocalDate

/**
 * Renders every screen that carried an indigo accent or a switch, light and
 * dark, to PNGs for the colour review (owner decision 2026-09-24: ON switches
 * are coral; indigo leaves the app — links ink, pills the ink/bg selection
 * pair, eyebrows neutral). NOT an assertion suite.
 *
 * The REAL [MainScaffold], offline, on a small lived-in account, driven by the
 * same `unstuck://…` links a push sends. Opt-in like SettingsRenderTest:
 * skipped unless `UNSTUCK_RENDER_DIR` names an output directory.
 *
 *     UNSTUCK_RENDER_DIR=/tmp/shots ./gradlew :app:testDebugUnitTest \
 *         --tests '*ColourRenderTest*' --rerun
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = PHONE_C)
class ColourRenderTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** See MainScaffoldFabTest: the host activity is registered with
     *  Robolectric so no test activity reaches the shipping manifest. */
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

    private lateinit var db: UnstuckDatabase
    private lateinit var write: WriteThrough
    private lateinit var vm: AppViewModel
    private lateinit var graph: AppGraph
    private val drain = ViewModelDrain()

    @Before fun setup() {
        assumeTrue("set UNSTUCK_RENDER_DIR to write the colour PNGs", outDir != null)
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        val store = LocalStore(db)
        write = WriteThrough(store)
        graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "u-render" })
        graph.onboarded = true
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = AppViewModel(graph = graph, writeOverride = write, currentUidProvider = { "u-render" })
        graph.aiConsent.set(tech.csalliance.unstuck.core.logic.AIConsent.Cache("u-render", tech.csalliance.unstuck.core.logic.AIConsent.grant(1_790_000_000_000L), pending = false))
        val today = LocalDate.now()
        runBlocking {
            write.upsertLifeArea(LifeArea("a1", "Work", "indigo", 0))
            write.upsertLifeArea(LifeArea("a2", "Home", "green", 1))
            write.upsertLifeArea(LifeArea("a3", "Health", "teal", 2))
            write.upsertTag(TagRow("g1", "errands", "amber", 0))
            write.upsertTag(TagRow("g2", "calls", "blue", 1))
            write.upsertTask(TaskItem(id = "t1", name = "Draft the quarterly update", estimateMin = 25, lifeArea = "Work", tags = listOf("calls", "errands"), createdAt = NOW, updatedAt = NOW))
            write.upsertTask(TaskItem(id = "t2", name = "Book the dentist", estimateMin = 15, lifeArea = "Health", tags = listOf("errands"), createdAt = NOW, updatedAt = NOW))
            write.upsertTask(TaskItem(id = "t3", name = "Sort the garage shelves", estimateMin = 45, lifeArea = "Home", tags = listOf("errands"), later = true, createdAt = NOW, updatedAt = NOW))
            // Planned blocks today (so Today lists them) and across the month (the heat map).
            write.upsertCalBlock(CalBlock("b1", "t1", "Draft the quarterly update", "09:30", 50, today.toString()))
            write.upsertCalBlock(CalBlock("b2", "t2", "Book the dentist", "14:00", 25, today.toString()))
            listOf(-9 to 180, -6 to 60, -3 to 120, -2 to 240, -1 to 90, 1 to 150, 2 to 30, 4 to 200, 7 to 75).forEachIndexed { i, (d, min) ->
                val day = today.plusDays(d.toLong())
                write.upsertCalBlock(CalBlock("h$i", "t1", "Draft the quarterly update", "10:00", min, day.toString()))
            }
            write.upsertCapture(Capture("c1", taskId = "t1", tag = CaptureTag.FOLLOW_UP, body = "Ask finance for the Q3 numbers", at = NOW))
            write.upsertCapture(Capture("c2", taskId = "t1", tag = CaptureTag.IDEA, body = "Lead with the customer quote", at = NOW))
            write.upsertCapture(Capture("c3", taskId = "t2", tag = CaptureTag.QUESTION, body = "Does the plan cover a cleaning?", at = NOW))
            write.upsertCollection(ItemCollection("k1", "Trip to Lisbon", "blue", items = listOf(CollectionItem("i1", "Book the flights", at = NOW)), sortOrder = 0, ownerId = "u-render", members = listOf("u-2"), myRole = "owner"))
            write.upsertCollection(ItemCollection("k2", "Reading list", "amber", items = emptyList(), sortOrder = 1))
        }
    }

    @After fun teardown() {
        if (::vm.isInitialized) { drain.track(vm); drain.drain() }
    }

    private fun shell(dark: Boolean) {
        compose.setContent { UnstuckTheme(dark = dark) { MainScaffold(vm) } }
        compose.waitForIdle()
    }

    private fun link(url: String) {
        compose.runOnIdle { vm.openDeepLink(url) }
        compose.waitForIdle()
    }

    /** Every window, in stacking order (bottom sheets are their own window). */
    private fun shot(name: String) {
        compose.waitForIdle()
        val png = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val out = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            for (root in rootViews()) {
                if (root.width == 0 || root.height == 0 || !root.isShown) continue
                val at = IntArray(2).also { root.getLocationOnScreen(it) }
                canvas.save()
                canvas.translate(at[0].toFloat(), at[1].toFloat())
                root.draw(canvas)
                canvas.restore()
            }
            out
        }
        val dir = outDir!!.apply { mkdirs() }
        File(dir, name).outputStream().use { png.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmg.getMethod("getInstance").invoke(null)
        val views = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        return views.toList().ifEmpty { listOf(compose.activity.window.decorView) }
    }

    // ── switches: Settings → Notifications & calls (the screen the owner saw) ──
    @Config(qualifiers = TALL_C) @Test fun notificationsLight() { shell(false); link("unstuck://settings?section=Notifications"); shot("notifications-calls-light.png") }
    @Config(qualifiers = TALL_C) @Test fun notificationsDark() { shell(true); link("unstuck://settings?section=Notifications"); shot("notifications-calls-dark.png") }
    @Config(qualifiers = TALL_C) @Test fun assistantPrivacyLight() { shell(false); link("unstuck://settings?section=Assistant"); shot("assistant-privacy-light.png") }
    @Config(qualifiers = TALL_C) @Test fun assistantPrivacyDark() { shell(true); link("unstuck://settings?section=Assistant"); shot("assistant-privacy-dark.png") }

    // ── eyebrows ──
    @Test fun settingsHubLight() { shell(false); link("unstuck://settings"); shot("settings-hub-light.png") }
    @Test fun settingsHubDark() { shell(true); link("unstuck://settings"); shot("settings-hub-dark.png") }
    @Test fun todayLight() { shell(false); shot("today-light.png") }
    @Test fun todayDark() { shell(true); shot("today-dark.png") }
    @Config(qualifiers = TALL_C) @Test fun insightsLight() { shell(false); link("unstuck://insights"); shot("insights-light.png") }
    @Config(qualifiers = TALL_C) @Test fun insightsDark() { shell(true); link("unstuck://insights"); shot("insights-dark.png") }
    @Test fun authLight() { compose.setContent { UnstuckTheme(dark = false) { AuthScreen(vm) } }; shot("auth-light.png") }
    @Test fun authDark() { compose.setContent { UnstuckTheme(dark = true) { AuthScreen(vm) } }; shot("auth-dark.png") }
    @Test fun onboardingLight() { compose.setContent { UnstuckTheme(dark = false) { OnboardingScreen(vm, onDone = {}) } }; shot("onboarding-light.png") }
    @Test fun onboardingDark() { compose.setContent { UnstuckTheme(dark = true) { OnboardingScreen(vm, onDone = {}) } }; shot("onboarding-dark.png") }

    // ── pills, chips, links ──
    @Test fun tasksLight() { shell(false); link("unstuck://tasks/all"); shot("tasks-light.png") }
    @Test fun tasksDark() { shell(true); link("unstuck://tasks/all"); shot("tasks-dark.png") }
    @Test fun tasksLaterLight() { shell(false); link("unstuck://tasks/all"); tap("Later"); tap("#errands"); shot("tasks-later-tag-filter-light.png") }
    @Test fun tasksLaterDark() { shell(true); link("unstuck://tasks/all"); tap("Later"); tap("#errands"); shot("tasks-later-tag-filter-dark.png") }
    @Test fun calendarWeekLight() { shell(false); link("unstuck://calendar/week"); shot("calendar-week-light.png") }
    @Test fun calendarWeekDark() { shell(true); link("unstuck://calendar/week"); shot("calendar-week-dark.png") }
    @Test fun calendarMonthLight() { shell(false); link("unstuck://calendar/month"); shot("calendar-month-light.png") }
    @Test fun calendarMonthDark() { shell(true); link("unstuck://calendar/month"); shot("calendar-month-dark.png") }
    @Test fun inboxLight() { shell(false); link("unstuck://captures"); shot("inbox-light.png") }
    @Test fun inboxDark() { shell(true); link("unstuck://captures"); shot("inbox-dark.png") }
    @Test fun collectionsLight() { shell(false); link("unstuck://collections"); shot("collections-light.png") }
    @Test fun collectionsDark() { shell(true); link("unstuck://collections"); shot("collections-dark.png") }
    @Config(qualifiers = TALL_C) @Test fun taskDetailLight() { shell(false); link("unstuck://task/t1"); shot("task-detail-light.png") }
    @Config(qualifiers = TALL_C) @Test fun taskDetailDark() { shell(true); link("unstuck://task/t1"); shot("task-detail-dark.png") }

    /** The Focus capture sheet: follow-up (the default tag) was the indigo chip. */
    private fun captureSheet(dark: Boolean, name: String) {
        val task = TaskItem(id = "t1", name = "Draft the quarterly update", estimateMin = 25, createdAt = NOW, updatedAt = NOW)
        compose.setContent { UnstuckTheme(dark = dark) { CaptureSheet(vm, task, null, onDismiss = {}) } }
        shot(name)
    }
    @Test fun captureSheetLight() = captureSheet(false, "focus-capture-sheet-light.png")
    @Test fun captureSheetDark() = captureSheet(true, "focus-capture-sheet-dark.png")

    private fun tap(text: String) {
        runCatching { compose.onAllNodesWithText(text)[0].performClick() }
        compose.waitForIdle()
    }

    private companion object {
        const val NOW = "2026-09-24T09:00:00.000Z"
    }
}

private const val PHONE_C = "w412dp-h915dp-xxhdpi"
private const val TALL_C = "w412dp-h1700dp-xxhdpi"
