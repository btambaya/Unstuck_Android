package tech.csalliance.unstuck.surface

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import tech.csalliance.unstuck.MainActivity
import tech.csalliance.unstuck.R

// Foreground service backing a live focus session — the Android analog of the
// iOS Live Activity. Running: coral "FOCUSING · LIVE" + chronometer + Pause /
// Capture. Paused: amber "CHECK-IN · Did you step away?" + Resume / Snooze /
// End. Same notification id, updated in place via update().
class FocusTimerService : Service() {

    private var name: String = "Focus session"
    private var startMs: Long = System.currentTimeMillis()
    private var paused: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE -> {
                paused = intent.getBooleanExtra(EXTRA_PAUSED, paused)
                // Refresh the chronometer base on resume — FocusTimer.resume shifts
                // sessionStart past the pause gap, so the running notification counts
                // true focus time (not wall-clock incl. the pause).
                if (intent.hasExtra(EXTRA_START)) startMs = intent.getLongExtra(EXTRA_START, startMs)
            }
            else -> {
                name = intent?.getStringExtra(EXTRA_NAME) ?: name
                startMs = intent?.getLongExtra(EXTRA_START, startMs) ?: startMs
                paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) ?: false
            }
        }
        NotificationChannels.ensureAll(this)
        val notification = build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NotifIds.FOCUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotifIds.FOCUS, notification)
        }
        // NOT_STICKY: don't let the system recreate us with a null intent (which would
        // rebuild a stale/generic notification). The focus-screen live-session observer
        // re-arms the service from real state when needed.
        return START_NOT_STICKY
    }

    private fun broadcast(action: String): PendingIntent = PendingIntent.getBroadcast(
        this, action.hashCode(),
        Intent(this, NotificationActionReceiver::class.java).setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, NotificationChannels.FOCUS_ONGOING)
            .setSmallIcon(R.drawable.ic_orbit)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) {
            b.setColor(NotificationChannels.AMBER)
                .setSubText("CHECK-IN")
                .setContentTitle("Did you step away?")
                .setContentText(name)
                .addAction(0, "Resume", broadcast(NotificationActionReceiver.ACTION_RESUME))
                .addAction(0, "Snooze", broadcast(NotificationActionReceiver.ACTION_SNOOZE))
                .addAction(0, "End", broadcast(NotificationActionReceiver.ACTION_END))
        } else {
            b.setColor(NotificationChannels.CORAL)
                .setSubText("FOCUSING · LIVE")
                .setContentTitle(name)
                .setUsesChronometer(true)
                .setWhen(startMs)
                .addAction(0, "Pause", broadcast(NotificationActionReceiver.ACTION_PAUSE))
                .addAction(0, "Capture", broadcast(NotificationActionReceiver.ACTION_CAPTURE))
        }
        return b.build()
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_START = "start"
        private const val EXTRA_PAUSED = "paused"
        private const val ACTION_STOP = "tech.csalliance.unstuck.STOP_FOCUS"
        private const val ACTION_UPDATE = "tech.csalliance.unstuck.UPDATE_FOCUS"

        fun start(context: Context, taskName: String, sessionStartMs: Long, paused: Boolean = false) {
            val i = Intent(context, FocusTimerService::class.java)
                .putExtra(EXTRA_NAME, taskName).putExtra(EXTRA_START, sessionStartMs).putExtra(EXTRA_PAUSED, paused)
            context.startForegroundService(i)
        }

        /** Flip the live notification between running and paused in place. Pass the
         *  current sessionStart so the resumed chronometer counts true focus time. */
        fun update(context: Context, paused: Boolean, startMs: Long? = null) {
            val i = Intent(context, FocusTimerService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_PAUSED, paused)
            if (startMs != null) i.putExtra(EXTRA_START, startMs)
            context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FocusTimerService::class.java).setAction(ACTION_STOP))
        }
    }
}
