package tech.csalliance.unstuck

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.surface.NotificationChannels
import tech.csalliance.unstuck.surface.NotificationLog
import tech.csalliance.unstuck.surface.ReminderScheduler

class UnstuckApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
        NotificationChannels.ensureAll(this)
        NotificationLog.init(this)
        // Keep pre-task reminder alarms in sync with the scheduled blocks.
        ReminderScheduler.observe(this)
        // Drop the realtime channels + websocket while the whole app is backgrounded
        // (was kept alive on the process-global scope indefinitely), and re-subscribe
        // on foreground. The auth observer stays alive; resume hydrates first so a
        // change missed while backgrounded is still pulled.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.coordinator?.resumeRealtime()
                // Drain pending log_shared_focus retries on every foreground (covers
                // relaunch): a partner-shared session finalized OFFLINE queued its
                // accrual — the ledger is the exclusive accrual path, so this drain is
                // what eventually lands the minutes. Idempotent per sessionId.
                graph.scope.launch { SharedFocusLedger.drain(graph) }
                // Co-focus reconnect re-exchange, belt-and-braces alongside the
                // realtime status flow: AppViewModel re-sends hello (+ an idempotent
                // same-rev re-announce when not diverged) for a live partner-shared
                // session on every foreground (docs/shared-session-spec.md).
                graph.foregrounds.tryEmit(Unit)
            }
            override fun onStop(owner: LifecycleOwner) { graph.coordinator?.pauseRealtime() }
        })
    }
}
