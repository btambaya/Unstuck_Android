package tech.csalliance.unstuck

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
            override fun onStart(owner: LifecycleOwner) { graph.coordinator?.resumeRealtime() }
            override fun onStop(owner: LifecycleOwner) { graph.coordinator?.pauseRealtime() }
        })
    }
}
