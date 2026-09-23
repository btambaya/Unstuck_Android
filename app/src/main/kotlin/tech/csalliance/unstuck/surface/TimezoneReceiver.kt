package tech.csalliance.unstuck.surface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.sync.liveUserId
import java.util.TimeZone

/**
 * The phone changed time zone (travel, or the user set it): push the new zone
 * to `notification_preferences.timezone` now. Every server cron — the call
 * dispatcher, the proactive calls, the morning brief — converts the user's
 * wall-clock with that column, and only a FULL hydrate or a push-token
 * registration used to write it, so a user who travelled without cold-starting
 * the app kept ringing on the old zone's clock (parity with iOS build 78,
 * 0f24908's NSSystemTimeZoneDidChange observer). TIMEZONE_CHANGED is on
 * Android's implicit-broadcast exemption list, so this manifest receiver runs
 * even when the app isn't.
 *
 * Exported (the system sends it from another UID), but it reads NO intent
 * extras: TIMEZONE_CHANGED is a protected broadcast only the system can send,
 * and the zone comes from the process's own default (which the system resets
 * before delivering it), so no other app can plant a zone. Signed out → no-op.
 * A failed push is retried by the next catch-up pull (Hydrator.pushTimezone).
 */
class TimezoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val tz = TimeZone.getDefault().id?.trim().orEmpty()
        if (tz.isEmpty()) return
        val scope = scopeFor(context.applicationContext) ?: return
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                // The WHOLE push — session wait AND the RPC — inside the broadcast
                // window: only the session wait was bounded, so a restore that had to
                // refresh followed by a slow set_timezone could outlive goAsync, and the
                // system ANRs the receiver (killing a backgrounded process). A cut push
                // is re-sent by the next pull (Hydrator.pushTimezone) (Android audit
                // 2026-09-23, A2 — second pass).
                runCatching { withTimeoutOrNull(PUSH_TIMEOUT_MS) { push(context.applicationContext, tz) } }
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        /** Where the push runs — the app graph's scope (seam for tests). */
        @Volatile internal var scopeFor: (Context) -> CoroutineScope? = { context ->
            (context as? UnstuckApp)?.let { app -> runCatching { app.graph.scope }.getOrNull() }
        }

        /** The session wait, inside [PUSH_TIMEOUT_MS]. */
        const val SESSION_TIMEOUT_MS = 6_000L
        /** The whole push, inside the broadcast's ~10 s goAsync window (a process the
         *  broadcast cold-starts has already spent some of it starting up). */
        const val PUSH_TIMEOUT_MS = 8_000L

        /** The push itself — a seam so the receiver is testable without a
         *  server. Default: `set_timezone` through the signed-in coordinator.
         *  The session comes from the gate, not `auth.currentUserId`: the zone
         *  changes while the app is in the background (a flight), when the live
         *  status reads null after supabase-kt's ON_STOP reset — or in a process
         *  the broadcast just started, still loading it — so the push was skipped
         *  every time it mattered (Android audit 2026-09-23, A2). */
        @Volatile internal var push: suspend (Context, String) -> Unit = { context, tz ->
            val coordinator = (context as? UnstuckApp)?.graph?.coordinator
            if (coordinator != null && coordinator.session.ensure(SESSION_TIMEOUT_MS).liveUserId != null) {
                coordinator.preferences.setTimezone(tz)
            }
        }
    }
}
