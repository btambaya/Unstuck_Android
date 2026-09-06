package tech.csalliance.unstuck.surface

import android.content.Context
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.calls.AppCallEnvironment
import tech.csalliance.unstuck.calls.CallNotifications
import tech.csalliance.unstuck.calls.CallOutcomeStore
import tech.csalliance.unstuck.calls.CallRinger
import tech.csalliance.unstuck.core.logic.CallCoordinatorLogic
import tech.csalliance.unstuck.core.logic.CallDecision
import tech.csalliance.unstuck.core.logic.CallEnv
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.IncomingCallPayload

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
 *            scheduledAt (ISO), deepLink:"unstuck://call/<callId>",
 *            label, blockId?, taskName?, startTime?, firstAction?, estimateMin?,
 *            captures? (JSON array), name?, body }
 *
 * The Android port of iOS `CallCoordinator.reportIncoming` (C1-android), run
 * SYNCHRONOUSLY inside the FCM window:
 *
 *   kind=call ─▶ IncomingCallPayload.fromData ─▶ invalid → false (the generic
 *                renderer shows the server's fallback body; never silent)
 *     ├─ same callId as the ring that is up   → duplicate push: re-post, touch nothing
 *     ├─ another call is up (ringing / in-call) → outcome busy + notify
 *     └─ CallCoordinatorLogic.decide(payload, AppCallEnvironment):
 *          Ring      → CallRinger.ring (CallStyle + full-screen + 30 s missed alarm) —
 *                      unless the ring is LATE (due > 10 min ago: a Doze-delayed
 *                      push; CallCoordinatorLogic.isLate) → outcome stale, silent
 *          Silent    → nobody signed in: drop (no outcome — no JWT; no notes shown)
 *          Declined  → outcome declined + "outside your call hours" / "calls are off"
 *          Busy      → outcome busy + notify (a focus session is live)
 *          Stale     → outcome stale, silent (the task / block is gone or done)
 *
 * Outcomes go through the durable CallOutcomeStore queue (flushed now and on
 * every foreground / reconnect), never a fire-and-forget request. `env` is a
 * seam so the whole decision table runs under Robolectric without a graph.
 */
object CallPushHandler {
    const val KIND = "call"

    /** True when [data] was a call push and has been handled. False → not a
     *  call, or not a VALID one: the caller falls back to the generic renderer. */
    fun handle(
        context: Context,
        data: Map<String, String>,
        nowMs: Long = System.currentTimeMillis(),
        env: (IncomingCallPayload) -> CallEnv = { AppCallEnvironment.env(context, it, nowMs) },
    ): Boolean {
        if (data["kind"] != KIND) return false
        val payload = IncomingCallPayload.fromData(data) ?: return false

        // A retried / duplicated push for the call that is ALREADY up: re-post the
        // ring in place (CallRinger ignores it once answered), and touch nothing —
        // not the 30 s clock, not the outcome. A push for ANOTHER call while one is
        // up ends as busy (one call at a time, like CallKit's maximumCallGroups=1).
        val active = CallRinger.activeCallId(context)
        if (active != null) {
            if (active == payload.callId) {
                CallRinger.ring(context, payload, nowMs)
            } else {
                CallOutcomeStore.enqueue(context, payload.callId, CallOutcome.BUSY, nowMs = nowMs)
                CallNotifications.busy(context, payload)
            }
            return true
        }

        val e = env(payload)
        when (CallCoordinatorLogic.decide(payload, e)) {
            is CallDecision.Ring ->
                if (CallCoordinatorLogic.isLate(payload, nowMs)) CallOutcomeStore.enqueue(context, payload.callId, CallOutcome.STALE, nowMs = nowMs)
                else CallRinger.ring(context, payload, nowMs)
            is CallDecision.Silent -> Unit
            is CallDecision.Declined -> {
                CallOutcomeStore.enqueue(context, payload.callId, CallOutcome.DECLINED, nowMs = nowMs)
                // The kill-switch and the hours window both decline; tell the user which.
                if (!e.withinHours) CallNotifications.outsideHours(context, payload)
                else CallNotifications.callsOff(context, payload)
            }
            is CallDecision.Busy -> {
                CallOutcomeStore.enqueue(context, payload.callId, CallOutcome.BUSY, nowMs = nowMs)
                CallNotifications.busy(context, payload)
            }
            is CallDecision.Stale -> CallOutcomeStore.enqueue(context, payload.callId, CallOutcome.STALE, nowMs = nowMs)
        }
        return true
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
        // A ring (kind=call) is decided + posted synchronously inside the FCM window —
        // a high-priority data message that posts nothing promptly eats the app's
        // Doze quota. An INVALID call payload still lands as a generic push below
        // (the server folds a readable title/body in), so it is never silent.
        if (CallPushHandler.handle(this, data)) return
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
