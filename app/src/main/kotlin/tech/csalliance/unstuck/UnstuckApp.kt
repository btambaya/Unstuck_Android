package tech.csalliance.unstuck

import android.app.Application
import tech.csalliance.unstuck.surface.NotificationChannels
import tech.csalliance.unstuck.surface.ReminderScheduler

class UnstuckApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
        NotificationChannels.ensureAll(this)
        // Keep pre-task reminder alarms in sync with the scheduled blocks.
        ReminderScheduler.observe(this)
    }
}
