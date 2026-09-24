package tech.csalliance.unstuck.calls

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tech.csalliance.unstuck.MainActivity
import tech.csalliance.unstuck.R
import tech.csalliance.unstuck.core.logic.CallNotificationCopy
import tech.csalliance.unstuck.core.logic.CallNotificationKind
import tech.csalliance.unstuck.core.logic.CallNotificationSpec
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.surface.NotifIds
import tech.csalliance.unstuck.surface.NotificationActionReceiver
import tech.csalliance.unstuck.surface.NotificationChannels
import tech.csalliance.unstuck.surface.NotificationLog

/**
 * "What happened to the call" notifications — 1:1 with iOS
 * `CallNotifications` (CallCoordinator.swift): missed / busy / outside hours /
 * voice failed — on the reminders channel, except the quiet "outside your
 * hours" / "calls are off" pair on CALL_NOTES — with the notes as the body and, when
 * a task is anchored, the same **Start** / **Reschedule** shade actions the
 * "starts now" reminder has (NotificationRenderer.postTaskStarting). The copy
 * is :core `CallNotificationCopy` (unit-tested there); one id per call
 * (NotifIds.callResult) so a later verdict replaces the earlier one.
 */
object CallNotifications {

    /** Android-only copy (no iOS twin): the kill-switch decline and the snooze ack. */
    object Copy {
        const val CALLS_OFF_HINT = "(calls are switched off — Settings › Notifications & calls)"
        fun callsOffTitle(label: String) = "I called about $label"
        fun callsOffBody(notes: List<String>) = CallNotificationCopy.body(notes) + "\n" + CALLS_OFF_HINT
        fun snoozedTitle(minutes: Int) = "I'll call back in $minutes minutes"
    }

    /** Post one of the core verdicts. An outside-hours one is always QUIET,
     *  whichever path posts it. */
    fun post(context: Context, spec: CallNotificationSpec, quiet: Boolean = spec.kind == CallNotificationKind.OUTSIDE_HOURS) =
        post(context, spec, kind = "call_" + spec.kind.name.lowercase(), quiet = quiet)

    /** Unanswered after 30 s (or the ring never showed). */
    fun missed(context: Context, p: IncomingCallPayload) = post(context, CallNotificationCopy.missed(p))

    /** A focus session was live / another call was up. */
    fun busy(context: Context, p: IncomingCallPayload) = post(context, CallNotificationCopy.busy(p))

    /** Received outside the user's Settings › Notifications & calls window. QUIET: it lands
     *  when the server rang — by definition outside the hours the user wants
     *  to hear from us, possibly 3 am (parity with iOS build 78). */
    fun outsideHours(context: Context, p: IncomingCallPayload) = post(context, CallNotificationCopy.outsideHours(p))

    /** Answered, but the voice stack could not start. */
    fun voiceFailed(context: Context, p: IncomingCallPayload) = post(context, CallNotificationCopy.voiceFailed(p))

    /** No AI-consent OK (core AIConsent): the call never connected to the
     *  assistant. Declined on receipt it is QUIET (nothing rang); [answered] =
     *  they picked up and it hung up at once (the OK was turned off while it
     *  rang) — a normal alert, so they see why (iOS noAIConsent parity). */
    fun noAIConsent(context: Context, p: IncomingCallPayload, answered: Boolean = false) =
        post(context, CallNotificationCopy.noAIConsent(p), quiet = !answered)

    /** Declined by a kill-switch (Settings › Notifications & calls off, or the AI Assistant
     *  switched off) — the Android decision for plan risk 10. */
    fun callsOff(context: Context, p: IncomingCallPayload) = post(
        context,
        CallNotificationCopy.missed(p).copy(id = "unstuck.call.off.${p.callId}", title = Copy.callsOffTitle(p.label), body = Copy.callsOffBody(p.notes)),
        kind = "call_off",
        quiet = true,   // the user switched calls off — don't buzz them for it
    )

    /** Brief confirmation after a shade / ring-screen snooze (auto-dismisses). */
    fun snoozed(context: Context, p: IncomingCallPayload, minutes: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val n = base(context)
            .setContentTitle(Copy.snoozedTitle(minutes))
            .setContentText(p.label)
            .setContentIntent(openApp(context, CallNotificationCopy.missed(p).deepLink))
            .setTimeoutAfter(8_000)
            .build()
        nm.notify(NotifIds.callResult(p.callId), n)
    }

    private fun openApp(context: Context, deepLink: String): PendingIntent = PendingIntent.getActivity(
        context, deepLink.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(Uri.parse(deepLink)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Same privacy shape as NotificationRenderer.base: private on the lock
     *  screen with an "Unlock to read" public version. [quiet] = the silent,
     *  passive [NotificationChannels.CALL_NOTES] shape (no sound, no heads-up). */
    private fun base(context: Context, quiet: Boolean = false): NotificationCompat.Builder =
        NotificationCompat.Builder(context, if (quiet) NotificationChannels.CALL_NOTES else NotificationChannels.REMINDERS)
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
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(quiet)
            .setOnlyAlertOnce(quiet)
            .setAutoCancel(true)

    private fun post(context: Context, spec: CallNotificationSpec, kind: String, quiet: Boolean = false) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return   // no "shown" log entry for a suppressed one
        val b = base(context, quiet)
            .setContentTitle(spec.title)
            .setContentText(spec.body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setContentIntent(openApp(context, spec.deepLink))
        val taskId = spec.taskId
        if (taskId != null) {
            val taskName = spec.taskName ?: ""
            // Start → straight into Focus for the task (same as the starts-now reminder).
            b.addAction(
                0, "Start",
                PendingIntent.getActivity(
                    context, ("start:$taskId").hashCode(),
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .setData(Uri.parse("unstuck://focus/$taskId")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            // Reschedule → the block moves to the next free slot in the background;
            // needs the block, so a task-only anchor gets Start alone.
            val blockId = spec.blockId
            if (blockId != null) {
                b.addAction(
                    0, "Reschedule",
                    PendingIntent.getBroadcast(
                        context, ("resched:$blockId").hashCode(),
                        Intent(context, NotificationActionReceiver::class.java)
                            .setAction(NotificationActionReceiver.ACTION_RESCHEDULE)
                            .putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
                            .putExtra(NotificationActionReceiver.EXTRA_BLOCK_ID, blockId)
                            .putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, taskName)
                            .putExtra(NotificationActionReceiver.EXTRA_DRIFTED, false),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
        nm.notify(NotifIds.callResult(spec.callId), b.build())
        NotificationLog.add(context, kind, spec.title, spec.body, spec.deepLink)
    }
}
