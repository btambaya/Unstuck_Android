package tech.csalliance.unstuck.surface

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

// Foreground service backing a live focus session — an ongoing chronometer
// notification (Android's nearest analog to the iOS Live Activity). Keeps the
// timer visible + the process alive while focusing.
class FocusTimerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "Focus session"
        val startMs = intent?.getLongExtra(EXTRA_START, System.currentTimeMillis()) ?: System.currentTimeMillis()
        ensureChannel()
        val notification = build(name, startMs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Focus session", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows the running focus timer"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun build(name: String, startMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(name)
            .setContentText("Focusing")
            .setUsesChronometer(true)
            .setWhen(startMs)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "focus_timer"
        private const val NOTIF_ID = 1001
        private const val EXTRA_NAME = "name"
        private const val EXTRA_START = "start"
        private const val ACTION_STOP = "tech.csalliance.unstuck.STOP_FOCUS"

        fun start(context: Context, taskName: String, sessionStartMs: Long) {
            val i = Intent(context, FocusTimerService::class.java)
                .putExtra(EXTRA_NAME, taskName).putExtra(EXTRA_START, sessionStartMs)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FocusTimerService::class.java).setAction(ACTION_STOP))
        }
    }
}
