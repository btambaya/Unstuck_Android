package tech.csalliance.unstuck.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough

/**
 * The REAL MainScaffold follows the onboarding gate as it changes (Android audit
 * 2026-09-23, A9). It used to read the flag once — `remember { !vm.onboarded }` — the
 * moment the session was authed, so a web / iOS account whose answer arrived a second
 * later stayed on the steps. Harness as in [MainScaffoldFabTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class MainScaffoldOnboardingGateTest {

    private val compose = createComposeRule()

    // Register the compose rule's host activity first (see MainScaffoldFabTest.hostActivity).
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

    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel
    private val drain = ViewModelDrain()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = LocalStore(Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build())
        // A signed-in account this device has never onboarded.
        graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { UID })
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { UID })
    }

    @After fun teardown() {
        drain.track(vm)
        drain.drain()
    }

    private fun shell() {
        compose.setContent { UnstuckTheme(dark = false) { MainScaffold(vm) } }
        compose.waitForIdle()
    }

    private fun todayTab() = compose.onNode(hasText("Today") and isSelectable())

    @Test
    fun anAccountOnboardedElsewhere_neverSeesTheSteps_andLandsInTheAppWhenTheAnswerArrives() {
        shell()
        compose.onNodeWithText(WELCOME).assertDoesNotExist()
        todayTab().assertDoesNotExist()

        // The server answers: struggles saved on iOS.
        runBlocking { vm.applyOnboardingAnswer(UID, serverStruggles = listOf("Distraction"), interviewDoneAt = null, afterPull = false) }
        compose.waitForIdle()

        compose.onNodeWithText(WELCOME).assertDoesNotExist()
        todayTab().assertIsDisplayed()
    }

    @Test
    fun aNewAccountGetsTheSteps_andSkipLandsInTheApp() {
        shell()
        runBlocking { vm.applyOnboardingAnswer(UID, serverStruggles = emptyList(), interviewDoneAt = null, afterPull = true) }
        compose.waitForIdle()
        compose.onNodeWithText(WELCOME).assertIsDisplayed()

        compose.onNodeWithText("Skip").performClick()
        compose.waitForIdle()
        // The steps are gone for good (Skip also arms the one-time guided tour, which
        // then takes over Today — so the app itself is asserted in the test above).
        compose.onNodeWithText(WELCOME).assertDoesNotExist()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        assertEquals(false, vm.showOnboarding.value)
    }

    private companion object {
        const val UID = "me"
        const val WELCOME = "Welcome."
    }
}
