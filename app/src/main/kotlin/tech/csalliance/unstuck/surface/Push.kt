package tech.csalliance.unstuck.surface

import android.content.Context
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp

// FCM receive + token registration. Dormant until google-services.json is
// added + the google-services plugin applied (a manual prerequisite, the
// Android analog of the iOS APNs key). All Firebase calls are guarded so the
// app builds + runs without the config.

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
        // Prefer data fields (so the server can drive channel/copy/deep-link in all
        // app states); fall back to the notification block for legacy payloads.
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "Unstuck"
        val body = data["body"] ?: message.notification?.body ?: return
        NotificationChannels.ensureAll(this)
        NotificationRenderer.renderPush(this, kind = data["kind"], title = title, body = body, deepLink = data["deepLink"])
    }
}
