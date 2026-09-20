package tech.csalliance.unstuck.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tech.csalliance.unstuck.core.logic.CallNotificationKind
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.surface.NotificationChannels

/**
 * The ring's background outcomes — the ones that need no screen:
 *  - [ACTION_MISSED]  the 30 s alarm CallRinger armed (fires through Doze; also
 *                     fires if our process was killed mid-ring) → `missed` once;
 *                     the "I called about …" notification rides with the queued
 *                     report and is posted only when call-outcome answers
 *                     `retry: false` (a first miss is re-rung 5 min later).
 *  - [ACTION_DECLINE] the shade's Decline → `declined`, no notification (they saw it).
 *  - [ACTION_SNOOZE]  the shade's "Snooze 10" → `snoozed` (10 min; the server
 *                     re-rings when due) + a brief confirmation.
 * Every path goes through [CallRinger.settle]: the first outcome for a call
 * wins, so a Decline racing the alarm reports exactly one of the two.
 *
 * NOT exported: the PendingIntents target this class by explicit component
 * from this app — no other app can settle a call under our identity.
 */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(CallRinger.EXTRA_CALL_ID)?.takeIf { it.isNotBlank() } ?: return
        NotificationChannels.ensureAll(context)
        // Read the payload BEFORE settling (settle clears the ring's "ringing" view).
        val payload = CallRinger.ringing(context)?.takeIf { it.callId == callId }
        when (intent.action) {
            ACTION_MISSED -> CallRinger.settle(context, callId, CallOutcome.MISSED, notifyUnlessRetry = CallNotificationKind.MISSED)
            ACTION_DECLINE -> CallRinger.settle(context, callId, CallOutcome.DECLINED)
            ACTION_SNOOZE -> {
                if (CallRinger.settle(context, callId, CallOutcome.SNOOZED, snoozeMin = SNOOZE_MIN) && payload != null) {
                    CallNotifications.snoozed(context, payload, SNOOZE_MIN)
                }
            }
        }
    }

    companion object {
        const val ACTION_MISSED = "tech.csalliance.unstuck.call.MISSED"
        const val ACTION_DECLINE = "tech.csalliance.unstuck.call.DECLINE"
        const val ACTION_SNOOZE = "tech.csalliance.unstuck.call.SNOOZE"
        /** The one-tap snooze (iOS fallback B "Snooze 10"; server default 10). */
        const val SNOOZE_MIN = 10

        fun intent(context: Context, action: String, callId: String): Intent =
            Intent(context, MissedCallReceiver::class.java).setAction(action).putExtra(CallRinger.EXTRA_CALL_ID, callId)
    }
}
