package tech.csalliance.unstuck.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.focus.FocusOptionsSheet
import java.io.File

/**
 * Renders the slim Settings to PNGs for design review — NOT an assertion suite
 * (MainScaffoldSettingsTest is that). The REAL [MainScaffold], offline, driven
 * through the same `unstuck://settings…` links a push or the assistant sends.
 *
 * Opt-in, like design's BottomNavBarRenderTest: every case is skipped (a JUnit
 * assumption) unless `UNSTUCK_RENDER_DIR` (or an `unstuck.renderDir` system
 * property on the TEST JVM) names an output directory, so a normal
 * `:app:testDebugUnitTest` run never writes files:
 *
 *     UNSTUCK_RENDER_DIR=/tmp/shots ./gradlew :app:testDebugUnitTest \
 *         --tests '*SettingsRenderTest*' --rerun
 *
 * The hub renders at a real phone size (412 × 915 dp, xxhdpi) — the question
 * it answers is whether the hub reads in one glance. The sub-screens render
 * on a tall canvas so each shot holds the whole screen. Every window is drawn
 * (bottom sheets live in their own), in stacking order.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = PHONE)
class SettingsRenderTest {

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
        assumeTrue("set UNSTUCK_RENDER_DIR to write the Settings PNGs", outDir != null)
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        val store = LocalStore(db)
        write = WriteThrough(store)
        graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "u-render" })
        graph.onboarded = true
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = AppViewModel(graph = graph, writeOverride = write, currentUidProvider = { "u-render" })
        // The account has agreed to AI data sharing (the consent shots clear it).
        graph.aiConsent.set(tech.csalliance.unstuck.core.logic.AIConsent.Cache("u-render", tech.csalliance.unstuck.core.logic.AIConsent.grant(1_790_000_000_000L), pending = false))
        // A lived-in account: three areas, two tags, a few remembered facts.
        runBlocking {
            write.upsertLifeArea(LifeArea("a1", "Work", "indigo", 0))
            write.upsertLifeArea(LifeArea("a2", "Home", "green", 1))
            write.upsertLifeArea(LifeArea("a3", "Health", "teal", 2))
            write.upsertTag(TagRow("g1", "errands", "amber", 0))
            write.upsertTag(TagRow("g2", "calls", "blue", 1))
            write.upsertTask(TaskItem(id = "t1", name = "Draft the quarterly update", estimateMin = 25, lifeArea = "Work", tags = listOf("calls"), createdAt = NOW, updatedAt = NOW))
            write.upsertTask(TaskItem(id = "t2", name = "Book the dentist", estimateMin = 15, lifeArea = "Health", tags = listOf("errands"), createdAt = NOW, updatedAt = NOW))
            vm.saveProfileFact(ProfileFactCategory.RHYTHM, "Clearest head in the morning", ProfileFactSource.INTERVIEW, null)
            vm.saveProfileFact(ProfileFactCategory.CONSTRAINT, "School run at 15:00 on weekdays", ProfileFactSource.INTERVIEW, null)
            vm.saveProfileFact(ProfileFactCategory.PREFERENCE, "Prefers gentle nudges", ProfileFactSource.SETTINGS, null)
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

    /** Every window (the activity, then any sheet / dialog over it), drawn in
     *  stacking order at its place on screen. Compose's captureToImage() times
     *  out under Robolectric (see BottomNavBarRenderTest), and a bottom sheet
     *  is its own window the activity's decor view never draws. */
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

    // ── the hub, at a real phone size ──

    @Test fun hubLight() { shell(dark = false); link("unstuck://settings"); shot("android-hub-light.png") }
    @Test fun hubDark() { shell(dark = true); link("unstuck://settings"); shot("android-hub-dark.png") }

    // ── each screen, whole ──

    @Config(qualifiers = TALL)
    @Test fun notificationsLight() { shell(dark = false); link("unstuck://settings?section=Notifications"); shot("android-notifications-calls-light.png") }

    @Config(qualifiers = TALL)
    @Test fun notificationsDark() { shell(dark = true); link("unstuck://settings?section=Notifications"); shot("android-notifications-calls-dark.png") }

    @Config(qualifiers = TALL)
    @Test fun notificationsAssistantOff() {
        vm.updateSettings { it.copy(assistantEnabled = false) }
        shell(dark = false); link("unstuck://settings?section=Notifications"); shot("android-notifications-calls-ai-off.png")
    }

    @Config(qualifiers = TALL)
    @Test fun assistantAndPrivacy() { shell(dark = false); link("unstuck://settings?section=Assistant"); shot("android-assistant-privacy.png") }

    @Config(qualifiers = TALL)
    @Test fun whatUnstuckRemembers() { shell(dark = false); link("unstuck://settings?section=remembers"); shot("android-what-unstuck-remembers.png") }

    @Config(qualifiers = TALL)
    @Test fun people() { shell(dark = false); link("unstuck://settings?section=People"); shot("android-people.png") }

    @Config(qualifiers = TALL)
    @Test fun appearance() { shell(dark = false); link("unstuck://settings?section=Appearance"); shot("android-appearance.png") }

    @Config(qualifiers = TALL)
    @Test fun appearanceDark() { shell(dark = true); link("unstuck://settings?section=Appearance"); shot("android-appearance-dark.png") }

    @Config(qualifiers = TALL)
    @Test fun account() { shell(dark = false); link("unstuck://settings?section=Account"); shot("android-account.png") }

    // ── AI data sharing (core AIConsent) ──

    @Config(qualifiers = TALL)
    @Test fun notificationsWithoutSharing() {
        graph.aiConsent.clear()
        shell(dark = false); link("unstuck://settings?section=Notifications"); shot("android-notifications-calls-no-sharing.png")
    }

    /** Sharing off, then the switch tapped: the one consent sheet asks. */
    @Test fun consentSheet() {
        graph.aiConsent.clear()
        shell(dark = false); link("unstuck://settings?section=Assistant")
        compose.onNodeWithTag("settings-ai-data-sharing").performClick()
        shot("android-ai-consent-sheet.png")
    }

    // ── what moved out of Settings ──

    @Test fun tasksEditPill() { shell(dark = false); link("unstuck://tasks/all"); shot("android-tasks-edit-pill.png") }

    @Test fun areasAndTagsSheet() { shell(dark = false); link("unstuck://settings?section=areas"); shot("android-areas-tags-sheet.png") }

    /** The Focus screen's header: the speaker button (background noise) and ⋯ Options. */
    @Test fun focusScreenHeader() {
        vm.updateSettings { it.copy(ambient = "brown") }
        shell(dark = false); link("unstuck://focus/t1"); shot("android-focus-screen.png")
    }

    @Test fun focusOptionsSheet() {
        compose.setContent { UnstuckTheme(dark = true) { FocusOptionsSheet(vm, onDismiss = {}) } }
        compose.waitForIdle()
        shot("android-focus-options-sheet.png")
    }

    private companion object {
        const val NOW = "2026-09-24T09:00:00.000Z"
    }
}

private const val PHONE = "w412dp-h915dp-xxhdpi"
private const val TALL = "w412dp-h1700dp-xxhdpi"
