package tech.csalliance.unstuck.surface

import android.content.Context
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
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
import tech.csalliance.unstuck.sync.liveUserId

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
                    .onSuccess { PendingPushToken.clearIf(app, token) }
            }
        }
    }
}

/**
 * A rotated FCM token that is not registered yet. onNewToken runs while the app
 * is in the background — where supabase-kt's session reads null after its
 * ON_STOP reset, or is still loading in a process FCM just started — so the
 * register went out with the anon key, got a 401 and was swallowed: the server
 * kept the dead token and calls / briefs stopped reaching the phone until the
 * app was next opened (Android audit 2026-09-23, A2). The token is kept here
 * until a register as a live user succeeds; the SyncWorker retries it.
 */
internal object PendingPushToken {
    const val PREFS = "unstuck.push"
    const val KEY = "pendingFcmToken"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): String? = prefs(context).getString(KEY, null)

    fun set(context: Context, token: String) {
        synchronized(this) { prefs(context).edit().putString(KEY, token).commit() }
    }

    /** Forget [token] once it is registered — never a newer one that arrived meanwhile. */
    fun clearIf(context: Context, token: String) {
        synchronized(this) {
            if (get(context) == token) prefs(context).edit().remove(KEY).commit()
        }
    }

    /** Register [token] once [liveUser] answers with a live session, clearing it
     *  from pending when the server took it (true). */
    suspend fun register(context: Context, token: String, liveUser: suspend () -> String?, send: suspend (String) -> Unit): Boolean {
        if (liveUser() == null) return false
        try {
            send(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[push] token register failed, kept for the next background sync: $e")
            return false
        }
        clearIf(context, token)
        return true
    }
}

/**
 * "Unstuck calls you" — the ring push (server `send-call`, C0-android in
 * unstuck/docs/ios-gateway-plan.md). Data-only FCM message, android priority
 * HIGH, ttl 120 s:
 *
 *   data = { kind:"call", callKind?, callId, taskId?, title, notes? (JSON string array),
 *            scheduledAt (ISO), deepLink:"unstuck://call/<callId>",
 *            label, blockId?, taskName?, startTime?, endTime?, firstAction?, estimateMin?,
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
    /** The push-type discriminator send-call ALWAYS sets; the call's own kind
     *  (requested / test / morning / evening / after_block) rides as `callKind`. */
    const val KIND = "call"

    /** True when [data] was a call push and has been handled. False → not a
     *  call, or not a VALID one: the caller falls back to the generic renderer.
     *  Keys on `kind == "call"` (a server that wrote the row's kind into that
     *  slot instead is tolerated — IncomingCallPayload.isCallPush). */
    fun handle(
        context: Context,
        data: Map<String, String>,
        nowMs: Long = System.currentTimeMillis(),
        env: (IncomingCallPayload) -> CallEnv = { AppCallEnvironment.env(context, it, nowMs) },
    ): Boolean {
        if (!IncomingCallPayload.isCallPush(data["kind"])) return false
        val payload = IncomingCallPayload.fromData(data) ?: return false

        // Retire a ring nothing will ever settle BEFORE asking whether one is up: a
        // reboot / process kill mid-ring leaves an unsettled record behind (the 30 s
        // alarm does not survive a reboot), and without this EVERY later call would
        // report `busy` for the life of the install — the phone would simply stop
        // ringing. recover() reports the pending missed / done as it clears.
        CallRinger.recover(context, nowMs)

        // A retried / duplicated push for the call that is ALREADY up: re-post the
        // ring in place (CallRinger ignores it once answered), and touch nothing —
        // not the 30 s clock, not the outcome. A push for ANOTHER call while one is
        // up ends as busy (one call at a time, like CallKit's maximumCallGroups=1).
        // Same clock as recover() above and as CallRinger.ring() below stamps the
        // record with — asking the system clock here instead would let one decision
        // straddle two clocks (the record "live" to recover() and "stale" to this).
        val active = CallRinger.activeCallId(context, nowMs)
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
        val coordinator = app.graph.coordinator ?: return
        PendingPushToken.set(app, token)
        app.graph.scope.launch {
            PendingPushToken.register(app, token, liveUser = { coordinator.session.ensure().liveUserId }) {
                coordinator.push.register(deviceId = deviceId(app), fcmToken = it)
            }
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
