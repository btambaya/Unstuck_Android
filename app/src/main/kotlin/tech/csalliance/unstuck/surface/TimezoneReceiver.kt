package tech.csalliance.unstuck.surface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.UnstuckApp
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
                runCatching { push(context.applicationContext, tz) }
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

        /** The push itself — a seam so the receiver is testable without a
         *  server. Default: `set_timezone` through the signed-in coordinator. */
        @Volatile internal var push: suspend (Context, String) -> Unit = { context, tz ->
            val coordinator = (context as? UnstuckApp)?.graph?.coordinator
            if (coordinator?.auth?.currentUserId != null) coordinator.preferences.setTimezone(tz)
        }
    }
}
