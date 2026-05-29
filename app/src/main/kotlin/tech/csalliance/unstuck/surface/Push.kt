package tech.csalliance.unstuck.surface

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp

// FCM receive + token registration. Dormant until google-services.json is
// added + the google-services plugin applied (a manual prerequisite, the
// Android analog of the iOS APNs key). All Firebase calls are guarded so the
// app builds + runs without the config.

const val PUSH_CHANNEL = "unstuck_push"

@Suppress("HardwareIds")
fun deviceId(context: Context): String =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-device"

/** Fetch the FCM token (if Firebase is configured) and register it with the
 *  backend so morning-brief / recap pushes can reach this device. */
fun registerFcmToken(app: UnstuckApp) {
    val push = app.graph.coordinator?.push ?: return
    runCatching {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            app.graph.scope.launch {
                runCatching { push.register(deviceId = deviceId(app), fcmToken = token) }
            }
        }
    }
}

class UnstuckMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val app = application as? UnstuckApp ?: return
        val push = app.graph.coordinator?.push ?: return
        app.graph.scope.launch {
            runCatching { push.register(deviceId = deviceId(this@UnstuckMessagingService), fcmToken = token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Unstuck"
        val body = message.notification?.body ?: message.data["body"] ?: return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(PUSH_CHANNEL) == null) {
            mgr.createNotificationChannel(NotificationChannel(PUSH_CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = NotificationCompat.Builder(this, PUSH_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        mgr.notify(System.currentTimeMillis().toInt(), notification)
    }
}
