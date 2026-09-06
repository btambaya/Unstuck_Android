package tech.csalliance.unstuck.surface

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tech.csalliance.unstuck.MainActivity
import tech.csalliance.unstuck.R
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

/**
 * "Unstuck calls you" — the ring push (server `send-call`, C0-android in
 * unstuck/docs/ios-gateway-plan.md). Data-only FCM message, android priority
 * HIGH, ttl 120 s:
 *
 *   data = { kind:"call", callId, taskId?, title, notes? (JSON string array),
 *            scheduledAt (ISO), deepLink:"unstuck://call/<callId>" }
 *
 * plus the iOS VoIP payload's extras (label, blockId, taskName, startTime,
 * firstAction, estimateMin, captures, name) — ignored here, consumed by the
 * C1 call UI. Until C1 lands the ring is a high-priority, actionable
 * notification whose tap opens the deep link. Pure value + parser so the
 * contract is unit-tested without Firebase.
 */
data class CallPush(
    val callId: String,
    /** What the call is about (the request's label), e.g. "speak to James". */
    val title: String,
    val notes: List<String>,
    val taskId: String?,
    val scheduledAt: String?,
    val deepLink: String,
) {
    val notificationTitle: String get() = "Unstuck is calling"
    val notificationText: String get() = "About $title"

    /** The expanded text: the label line + up to three notes, then "… and N more". */
    val notificationBigText: String
        get() {
            val preview = notes.take(NOTES_PREVIEW).map { "• $it" }
            val more = if (notes.size > NOTES_PREVIEW) listOf("… and ${notes.size - NOTES_PREVIEW} more") else emptyList()
            return (listOf(notificationText) + preview + more).joinToString("\n")
        }

    /** Stable per-call id: an FCM retry of the same ring updates in place; two
     *  calls coexist. 0x70000 keeps clear of the reminder/atstart/drift/push families. */
    val notifId: Int get() = NOTIF_BASE + (callId.hashCode() and 0xFFFF)

    companion object {
        const val KIND = "call"
        const val NOTIF_BASE = 0x70000
        const val NOTES_PREVIEW = 3
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Tolerant decode mirroring iOS `IncomingCallPayload`: `callId` + a
         *  non-empty `title` (or `label`) are REQUIRED — null means "not a valid
         *  call push" and the caller falls back to the generic renderer. */
        fun fromData(data: Map<String, String>): CallPush? {
            if (data["kind"] != KIND) return null
            val callId = data["callId"]?.trim().orEmpty()
            val title = (data["title"]?.takeIf { it.isNotBlank() } ?: data["label"])?.trim().orEmpty()
            if (callId.isEmpty() || title.isEmpty()) return null
            return CallPush(
                callId = callId,
                title = title,
                notes = parseNotes(data["notes"]),
                taskId = data["taskId"]?.trim()?.takeIf { it.isNotEmpty() },
                scheduledAt = data["scheduledAt"]?.trim()?.takeIf { it.isNotEmpty() },
                deepLink = data["deepLink"]?.trim()?.takeIf { it.isNotEmpty() } ?: "unstuck://call/$callId",
            )
        }

        /** `notes` rides as a JSON string array; a bare non-JSON string is kept
         *  as a single note (never lose what the user asked to be reminded of). */
        fun parseNotes(raw: String?): List<String> {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return emptyList()
            val parsed = runCatching {
                json.parseToJsonElement(s).jsonArray.map { it.jsonPrimitive.content }
            }.getOrNull()
            return (parsed ?: listOf(s)).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

/** Posts the ring notification: REMINDERS channel (IMPORTANCE_HIGH, sound),
 *  PRIORITY_HIGH + CATEGORY_CALL for the pre-O heads-up path, tap → the deep
 *  link. Same privacy shape as NotificationRenderer.base (private on the lock
 *  screen, "Unlock to read"). No full-screen intent / telecom yet — C1. */
object CallRing {
    fun post(context: Context, push: CallPush) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse(push.deepLink))
        val tap = PendingIntent.getActivity(
            context, push.deepLink.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_orbit)
            .setColor(NotificationChannels.CORAL)
            .setGroup(NotificationChannels.GROUP)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
                    .setSmallIcon(R.drawable.ic_orbit)
                    .setContentTitle("unstuck")
                    .setContentText("Unlock to read")
                    .build(),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentTitle(push.notificationTitle)
            .setContentText(push.notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(push.notificationBigText))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(push.notifId, n)
        NotificationLog.add(context, CallPush.KIND, push.notificationTitle, push.notificationBigText, push.deepLink)
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
        NotificationChannels.ensureAll(this)
        // A ring (kind=call) is posted synchronously inside the FCM window — a
        // high-priority data message that posts nothing promptly eats the app's
        // Doze quota. An invalid call payload still lands as a generic push below
        // (the server folds a readable title/body in), so it is never silent.
        CallPush.fromData(data)?.let { CallRing.post(this, it); return }
        val title = data["title"] ?: message.notification?.title ?: "Unstuck"
        val body = data["body"] ?: message.notification?.body ?: return
        // Derive a stable-but-distinct id from the content so two different pushes
        // (e.g. a reminder for task A and one for task B, or "Sarah finished milk" vs
        // "milk isn't started") COEXIST instead of overwriting each other, while an
        // FCM retry of the identical payload still collapses to one. 0x60000 base keeps
        // it clear of the local reminder/atstart/drift families.
        val key = data["kind"].orEmpty() + "|" + data["deepLink"].orEmpty() + "|" + title + "|" + body
        val notifId = 0x60000 + (key.hashCode() and 0xFFFF)
        NotificationRenderer.renderPush(this, kind = data["kind"], title = title, body = body, deepLink = data["deepLink"], notifId = notifId)
    }
}
