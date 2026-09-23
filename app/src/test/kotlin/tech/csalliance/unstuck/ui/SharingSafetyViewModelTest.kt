package tech.csalliance.unstuck.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.WriteThrough

/**
 * The block / leave / removal writes on [AppViewModel] (migration 075, parity
 * with iOS build 79, audit 2026-09-22 C10/C11): with no server behind them —
 * signed out, unconfigured — none of them claims success, so no screen closes on
 * a block or a removal that never happened. (The client's own reading of the
 * server's answers is pinned in :sync SharingServerContractTest.) Same SUT shape
 * as AppViewModelTest: in-memory Room, no coordinator, viewModelScope on a
 * StandardTestDispatcher. Mirrors iOS RecipientShareControlsTests
 * .testWithoutAServerNothingClaimsSuccessOrDropsTheRow.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SharingSafetyViewModelTest {

    private lateinit var store: LocalStore
    private lateinit var graph: AppGraph
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = store)
    }

    @After fun teardown() {
        // Same teardown as AppViewModelTest: run what the VM posted to the main
        // looper, then finish this test's ViewModels before Main is handed back.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        drain.drain()
        Dispatchers.resetMain()
    }

    private val drain = ViewModelDrain(dispatcher.scheduler)

    private fun kotlinx.coroutines.test.TestScope.vm() =
        AppViewModel(graph = graph, writeOverride = WriteThrough(graph.store), currentUidProvider = { "me" }, currentNameProvider = { "Ada" })
            .also { created ->
                drain.track(created)
                backgroundScope.coroutineContext.job.invokeOnCompletion {
                    runCatching { created.viewModelScope.cancel() }
                }
            }

    @Test fun `without a server no block, leave or removal claims success`() = runTest(dispatcher) {
        val vm = vm()
        assertFalse(vm.blockUser("u1"))
        assertFalse(vm.blockTaskSharer("s1"))
        assertFalse(vm.unblockUser("u1"))
        assertFalse(vm.leaveSharedTask("s1"))
        val maya = CircleMember("c1", null, "view", CircleStatus.ACTIVE, null, "u1", "Maya Chen", "2026-09-22T09:00:00Z")
        assertFalse("the People row stays and says so", vm.removeFromCircle(maya))
        assertNull("the Blocked list keeps what it shows", vm.blockedUsers())
        // Blank ids never reach the server at all.
        assertFalse(vm.blockUser(""))
        assertFalse(vm.blockTaskSharer(" "))
        assertFalse(vm.leaveSharedTask(""))
    }
}
