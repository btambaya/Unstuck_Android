@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package tech.csalliance.unstuck.ui

import android.os.Looper
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.robolectric.Shadows.shadowOf

/**
 * Make an [AppViewModel] built by hand in a unit test DIE WITH THAT TEST —
 * cancelled *and finished*, not merely cancelled.
 *
 * ## The flake this exists to kill
 * Nothing clears a hand-built ViewModel: `onCleared()` only runs under a real
 * ViewModelStore. The suites here already cancel `viewModelScope` when the test
 * body ends — but **cancellation is a request, not an ending**. A cancelled
 * child that is parked inside a real-thread call (LocalStore's Room flows are
 * `flowOn(Dispatchers.Default)`; a `launchWrite` sits on Room's own executor)
 * keeps unwinding on that thread *afterwards*, and its last act is to resume on
 * `Dispatchers.Main`. Measured on this suite before the fix: 17 of
 * AppViewModelTest's tests reached `@After` with their `viewModelScope` job
 * still alive (`[MEASURE] LEFTOVER 1/1 in …`, one line per test, the set
 * differing run to run).
 *
 * `Dispatchers.Main` is a single `TestMainDispatcher` for the whole JVM, so
 * those survivors land on WHOEVER owns Main next — and that is the suite's
 * wandering order-dependent flake, in both of its observed shapes:
 *
 *  - a leftover resuming exactly as `@After`/`@Before` swap Main →
 *    `IllegalStateException: Dispatchers.Main is used concurrently with setting
 *    it` (TestMainDispatcher.kt:67), reported against whichever innocent test
 *    was next — e.g.
 *    `gateway_scheduleMoment_forAVanishedTaskWritesNothingButRetiresTheMoment`.
 *  - a leftover surviving the swap and being adopted by the NEXT test's
 *    scheduler, putting foreign tasks — and foreign `delay`s, like the widget
 *    observer's `debounce(300)` — inside that test's `advanceUntilIdle()`.
 *    A test whose assertions depend on a grace window not having expired (e.g.
 *    `rejoinPending_unflagged_incomingAheadAdopts_despiteLosingPlainLww`) then
 *    fails for something it never did.
 *
 * Both shapes are the same defect: a test that ends before its own coroutines
 * do. So end them. [drain] cancels, then PUMPS — the test scheduler, the main
 * looper, and a real-time yield for the Room/Default threads — until every
 * tracked scope's Job reports `isCompleted`. After it returns, `resetMain()` is
 * safe because nothing of this test is still running.
 *
 * Deliberately NOT an execution order, a retry, or an exclusion: those hide the
 * leak. This removes it, and [drain] throws if a ViewModel will not finish —
 * a hang is then a real bug in the code under test, reported where it happens.
 *
 * @param scheduler the suite's [TestCoroutineScheduler] when it swaps Main for
 *   a test dispatcher (`Dispatchers.setMain`) — the survivors' continuations are
 *   queued there and only run when it is advanced. Null for a suite that leaves
 *   `Dispatchers.Main` alone (a Robolectric Compose test), where the main looper
 *   is the only pump needed.
 */
internal class ViewModelDrain(private val scheduler: TestCoroutineScheduler? = null) {

    private val built = mutableListOf<AppViewModel>()

    /** Track [vm] so [drain] will finish it. Returns it, for use at the call site. */
    fun track(vm: AppViewModel): AppViewModel = vm.also { built += it }

    /**
     * Cancel every tracked ViewModel and block until their coroutines have
     * actually completed. Call it from `@After` BEFORE `Dispatchers.resetMain()`.
     */
    fun drain(timeoutMs: Long = 10_000) {
        built.forEach { runCatching { it.viewModelScope.cancel() } }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // The survivors resume through Main: with a test dispatcher that is
            // the scheduler, and a Handler post (AppViewModel.init → CallVoiceService
            // .bind) is the looper. Pump both, then let the real Room/Default
            // threads get on with their half.
            scheduler?.advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            val alive = built.count { !it.viewModelScope.coroutineContext.job.isCompleted }
            if (alive == 0) break
            check(System.currentTimeMillis() < deadline) {
                "$alive of ${built.size} ViewModel scope(s) did not finish within ${timeoutMs}ms — " +
                    "something in AppViewModel is ignoring cancellation, and it would otherwise " +
                    "have leaked into the next test."
            }
            Thread.sleep(1)
        }
        built.clear()
    }
}
