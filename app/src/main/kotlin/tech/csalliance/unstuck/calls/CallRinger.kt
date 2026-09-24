package tech.csalliance.unstuck.calls

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.R
import tech.csalliance.unstuck.core.logic.CallCoordinatorLogic
import tech.csalliance.unstuck.core.logic.CallNotificationKind
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.surface.NotifIds
import tech.csalliance.unstuck.surface.NotificationChannels
import tech.csalliance.unstuck.surface.NotificationLog

// CallRinger — the Android ring for "Unstuck calls you" (C1-android; the
// activity + CallStyle shape the iOS plan calls fallback B, since there is no
// CallKit here — Telecom's ConnectionService is C3's evaluation).
//
//   FCM (kind=call) ─▶ Push.kt decides ─▶ ring(): persist the ring, post the
//   CallStyle notification (full-screen intent → IncomingCallActivity, the
//   `unstuck_calls` ringtone channel), arm the 30 s missed alarm — ALL of it
//   synchronously inside onMessageReceived (a high-priority data push that
//   posts nothing promptly eats the app's Doze quota; risk 2 of the plan).
//   Answer / Decline / Snooze (activity or shade) and the missed alarm each
//   go through settle(): the FIRST outcome for the call wins, the rest are
//   no-ops (iOS CallCoordinator's one-end-per-call rule), and the winner is
//   what clears the ring. The ring state lives in SharedPreferences so a
//   process killed mid-ring still reports `missed` when the alarm fires.
//   A `missed` settle hands the "I called about X" notice to the OUTCOME
//   QUEUE (settle(notifyUnlessRetry)) rather than posting it: call-outcome
//   answers `retry: true` on a first miss (the server re-rings in 5 min) and
//   the notice must stay quiet then — CallOutcomeStore posts it only on
//   `retry: false` (calls build-out 2026-09-20 §5).
//
//   That persisted record is also BOUNDED (recover / staleOutcome): a reboot
//   drops the 30 s alarm with every other alarm, and a process kill mid-call
//   leaves nobody to end the conversation — an unsettled record with no
//   staleness rule would make `activeCallId` non-null for ever, and every
//   future call would then be reported `busy` without ever ringing. A RINGING
//   record past 30 s + grace retires as `missed`, an ANSWERED one whose voice
//   service is gone as `done`, reported into the durable CallOutcomeStore.
//
// The ringtone + vibration are the CHANNEL's (system-managed: they follow the
// ringer volume, silent/vibrate mode, DND, and keep going if our process dies
// — see NotificationChannels.CALLS), looped for the whole ring by
// FLAG_INSISTENT until the notification is cancelled; nothing here touches
// the microphone: the voice foreground service starts ONLY from the user's
// Answer tap (risk 3).
object CallRinger {

    /** Ring for this long before giving up (iOS CallCoordinator.ringTimeout). */
    const val MISSED_AFTER_MS: Long = CallCoordinatorLogic.MISSED_AFTER_MS

    /**
     * How long past the 30 s missed alarm a RINGING record may sit unsettled
     * before it counts as STALE. The alarm fires at +30 s (Doze-tolerant), so a
     * record still ringing after this never got its alarm at all — the device
     * rebooted (AlarmManager alarms do not survive one) or the process was
     * killed mid-ring. See [staleOutcome] / [recover].
     */
    const val RING_STALE_GRACE_MS: Long = 15_000

    /**
     * The longest an ANSWERED record may hold the phone "busy" while a live
     * [CallVoiceService] still claims it. Deliberately ABOVE that service's own
     * [CallVoiceService.MAX_CALL_MS] watchdog, which ends every real call first —
     * this is the backstop for a service that somehow outlived it.
     */
    const val MAX_CALL_MS: Long = 20 * 60_000

    /**
     * Grace after the Answer tap before an ACTIVE record with no live
     * [CallVoiceService] counts as abandoned: `settle(ANSWERED)` runs in the ring
     * screen and the FGS publishes `CallVoiceService.activeCallId` a moment
     * later, so the hand-off must not look stale in between.
     */
    const val ACTIVE_HANDOFF_GRACE_MS: Long = 60_000

    private const val PREFS = "unstuck.calls.ring"
    private const val K_CALL_ID = "callId"
    private const val K_STARTED = "startedMs"
    private const val K_SETTLED = "settled"
    private const val K_PAYLOAD = "payload"
    private const val K_FSI_DENIED = "fullScreenIntentDenied"
    private const val K_PHASE = "phase"
    /** When the CURRENT phase began (the ring, or the Answer tap). */
    private const val K_PHASE_AT = "phaseAtMs"
    private const val PHASE_RINGING = "ringing"
    private const val PHASE_ACTIVE = "active"

    /** Extra on the full-screen / Answer intents: the payload as a String map
     *  (IncomingCallPayload.toData), one extra per key. */
    const val EXTRA_CALL_ID = "callId"
    const val ACTION_ANSWER = "tech.csalliance.unstuck.call.ANSWER"

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), String.serializer())

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── state ────────────────────────────────────────────────────────────────

    /** The call that is up right now (ringing, or answered and in the voice
     *  service) — null once its outcome has been settled, and null for a STALE
     *  record ([staleOutcome]): an unsettled ring left behind by a reboot or a
     *  process kill must never make every FUTURE call report `busy` (Push.kt)
     *  for the life of the install. [recover] reports what it left pending.
     *
     *  [nowMs] is the clock the staleness bound is measured against: ONE decision
     *  must run on ONE clock. The FCM path stamps the record with the `nowMs` it
     *  was handed, so it reads it back with the same one (Push.kt passes its own
     *  here and to [recover]); everything else takes the system clock default. */
    fun activeCallId(context: Context, nowMs: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        val p = prefs(context)
        if (p.getBoolean(K_SETTLED, true)) null
        else if (staleOutcome(p, nowMs) != null) null
        else p.getString(K_CALL_ID, null)
    }

    /** Whether the persisted ring record is still [callId]'s — live, settled or
     *  stale alike; false once it was cleared or a later ring replaced it. The
     *  voice service reports straight into the queue only when it is not
     *  (Android audit 2026-09-23, A5). */
    fun recordIs(context: Context, callId: String): Boolean = synchronized(lock) {
        prefs(context).getString(K_CALL_ID, null) == callId
    }

    /** The payload of the call that is still RINGING (unsettled, not stale), for
     *  the activity to restore itself from / a deep link to resume into. */
    fun ringing(context: Context, nowMs: Long = System.currentTimeMillis()): IncomingCallPayload? = synchronized(lock) {
        val p = prefs(context)
        if (p.getBoolean(K_SETTLED, true) || p.getString(K_PHASE, PHASE_RINGING) != PHASE_RINGING) return null
        if (staleOutcome(p, nowMs) != null) return null
        decodePayload(p.getString(K_PAYLOAD, null))
    }

    /** When the current ring started (epoch ms), or null when nothing rings. */
    fun ringStartedMs(context: Context, nowMs: Long = System.currentTimeMillis()): Long? = synchronized(lock) {
        val p = prefs(context)
        if (p.getBoolean(K_SETTLED, true)) null
        else if (staleOutcome(p, nowMs) != null) null
        else p.getLong(K_STARTED, 0L).takeIf { it > 0 }
    }

    /**
     * The outcome an UNSETTLED record has gone stale owing, or null while it is
     * still genuinely live. Callers hold [lock].
     *
     *  - RINGING past 30 s + [RING_STALE_GRACE_MS] → `missed`: the alarm that
     *    should have settled it is gone (a reboot drops AlarmManager alarms; a
     *    process kill before the alarm was armed loses it too).
     *  - ACTIVE with no live [CallVoiceService] for this call after
     *    [ACTIVE_HANDOFF_GRACE_MS] → `done`: the conversation's process died, so
     *    nobody is left to end it. A record a live service still owns retires
     *    only at [MAX_CALL_MS], as a backstop behind that service's watchdog.
     */
    private fun staleOutcome(p: android.content.SharedPreferences, nowMs: Long): CallOutcome? {
        if (p.getBoolean(K_SETTLED, true)) return null
        val callId = p.getString(K_CALL_ID, null) ?: return null
        val active = p.getString(K_PHASE, PHASE_RINGING) == PHASE_ACTIVE
        val since = p.getLong(K_PHASE_AT, 0L).takeIf { it > 0 } ?: p.getLong(K_STARTED, 0L)
        // A record from an older build carries neither stamp: with no age to
        // measure, treat it as arbitrarily old — nothing in THIS process is
        // ringing or talking, so it can only be a leftover.
        val age = if (since > 0) nowMs - since else Long.MAX_VALUE
        if (!active) return if (age > MISSED_AFTER_MS + RING_STALE_GRACE_MS) CallOutcome.MISSED else null
        // A conversation this process is actually running is never stale, whatever
        // the wall clock has done (CallVoiceService.MAX_CALL_MS ends it); only a
        // record whose service is GONE — the process died — retires here.
        val owned = CallVoiceService.activeCallId == callId
        if (!owned) return if (age > ACTIVE_HANDOFF_GRACE_MS) CallOutcome.DONE else null
        return if (age > MAX_CALL_MS) CallOutcome.DONE else null
    }

    /**
     * Retire a ring nothing will ever settle: an unsettled record left by a
     * reboot or a process kill (see [staleOutcome]) is cleared and its pending
     * outcome reported into the durable [CallOutcomeStore] — a `missed` the
     * server would otherwise age out ten minutes late, or a `done` for an
     * answered row the cron never touches at all. Returns what it reported.
     *
     * Called on every launch (UnstuckApp.onCreate), on BOOT_COMPLETED /
     * MY_PACKAGE_REPLACED (BootReceiver) and before every ring decision
     * (CallPushHandler) — cheap, idempotent, and a no-op while a call is live.
     */
    fun recover(context: Context, nowMs: Long = System.currentTimeMillis()): CallOutcome? {
        val callId: String
        val outcome: CallOutcome
        val payload: IncomingCallPayload?
        synchronized(lock) {
            val p = prefs(context)
            outcome = staleOutcome(p, nowMs) ?: return null
            callId = p.getString(K_CALL_ID, null) ?: return null
            payload = decodePayload(p.getString(K_PAYLOAD, null))
            p.edit().putBoolean(K_SETTLED, true).putString(K_PHASE, PHASE_RINGING).commit()
        }
        dismissRing(context, callId)
        // The user was rung and never answered: they still get the notes, exactly
        // as the missed alarm would have — deferred with the report, posted once
        // the server says no re-ring is coming. An abandoned ACTIVE call already
        // had its conversation — nothing to say.
        val notify = if (outcome == CallOutcome.MISSED && payload != null) CallNotificationKind.MISSED else null
        CallOutcomeStore.enqueue(context, callId, outcome, nowMs = nowMs, notify = notify, payload = notify?.let { payload?.toData() })
        return outcome
    }

    private fun decodePayload(raw: String?): IncomingCallPayload? {
        val s = raw ?: return null
        val map = runCatching { json.decodeFromString(mapSer, s) }.getOrNull() ?: return null
        return IncomingCallPayload.fromData(map)
    }

    /** Whether the OS lets us show the full-screen ring (API 34+: the
     *  USE_FULL_SCREEN_INTENT special access; pre-granted only to apps Play
     *  classifies as calling/alarm). Older APIs: always. */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return true
        return runCatching { mgr.canUseFullScreenIntent() }.getOrDefault(true)
    }

    /** Set when the LAST ring had to degrade to a heads-up because the
     *  full-screen intent permission was missing — the Settings › Notifications & calls row
     *  reads this to show the "allow full-screen calls" prompt. */
    fun fullScreenIntentDenied(context: Context): Boolean = prefs(context).getBoolean(K_FSI_DENIED, false)

    // ── ring ─────────────────────────────────────────────────────────────────

    /**
     * Ring for [payload]: persist, post the CallStyle notification, arm the
     * missed alarm. Called synchronously from the FCM window. A retried /
     * duplicated push for the call ALREADY ringing touches NOTHING (iOS) — not
     * the 30 s clock, and not the notification: the ring is INSISTENT, and the
     * system stops an insistent ringtone when its notification is re-posted
     * (a muted update clears it: only-alert-once mutes every update, and API
     * 31-32 mute any update of a looping ringtone) — Android audit 2026-09-23, A4.
     */
    fun ring(context: Context, payload: IncomingCallPayload, nowMs: Long = System.currentTimeMillis()) {
        val duplicate: Boolean
        synchronized(lock) {
            val p = prefs(context)
            // A record that has gone STALE (a reboot / process kill left it
            // unsettled) is not a live ring: the server re-ringing the same call
            // starts a FRESH one rather than re-posting over a dead record.
            duplicate = !p.getBoolean(K_SETTLED, true) &&
                p.getString(K_CALL_ID, null) == payload.callId &&
                staleOutcome(p, nowMs) == null
            // A retry for a call that was already ANSWERED: the voice service owns
            // it now — re-posting the ring over the conversation would be wrong.
            if (duplicate && p.getString(K_PHASE, PHASE_RINGING) == PHASE_ACTIVE) return
            if (!duplicate) {
                p.edit()
                    .putString(K_CALL_ID, payload.callId)
                    .putLong(K_STARTED, nowMs)
                    .putLong(K_PHASE_AT, nowMs)
                    .putBoolean(K_SETTLED, false)
                    .putString(K_PHASE, PHASE_RINGING)
                    .putString(K_PAYLOAD, json.encodeToString(mapSer, payload.toData()))
                    .putBoolean(K_FSI_DENIED, !canUseFullScreenIntent(context))
                    .commit()   // commit, not apply: the alarm + activity read this next
            }
        }
        if (duplicate) return
        postRing(context, payload)
        armMissedAlarm(context, payload.callId, nowMs + MISSED_AFTER_MS)
    }

    /** The intent the full-screen ring / a tap / the shade "Answer" open. */
    fun activityIntent(context: Context, payload: IncomingCallPayload, action: String? = null): Intent =
        Intent(context, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .apply {
                if (action != null) setAction(action)
                payload.toData().forEach { (k, v) -> putExtra(k, v) }
            }

    private fun postRing(context: Context, payload: IncomingCallPayload) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val rc = payload.callId.hashCode()
        val fullScreen = PendingIntent.getActivity(context, rc, activityIntent(context, payload), flags)
        val answer = PendingIntent.getActivity(context, rc + 1, activityIntent(context, payload, ACTION_ANSWER), flags)
        val decline = PendingIntent.getBroadcast(
            context, rc + 2,
            MissedCallReceiver.intent(context, MissedCallReceiver.ACTION_DECLINE, payload.callId), flags,
        )
        val snooze = PendingIntent.getBroadcast(
            context, rc + 3,
            MissedCallReceiver.intent(context, MissedCallReceiver.ACTION_SNOOZE, payload.callId), flags,
        )
        val caller = Person.Builder().setName("Unstuck · ${payload.label}").setImportant(true).build()
        val n = NotificationCompat.Builder(context, NotificationChannels.CALLS)
            .setSmallIcon(R.drawable.ic_orbit)
            .setColor(NotificationChannels.CORAL)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setContentTitle("Unstuck · ${payload.label}")
            .setContentText("Unstuck is calling")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The ring is what the user is asked to answer: sticky, not swipe-away,
            // and the full-screen intent turns the screen on over the keyguard. On
            // API 34 without the special access the system silently shows a
            // heads-up instead (fullScreenIntentDenied flags it for Settings).
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            // Belt and braces behind the missed alarm: a ring that somehow outlives
            // its alarm (Doze delayed it) must not sit in the shade for ever.
            .setTimeoutAfter(MISSED_AFTER_MS + 15_000)
            .addAction(0, "Snooze 10", snooze)
            .build()
        // Ring for the WHOLE ring (Android audit 2026-09-23, A4): without it the
        // system plays the channel's ringtone once and its vibration pattern once
        // (~5 s, three buzzes), and the rest of the 30 s is silent — a phone on
        // vibrate in a pocket simply misses the call. INSISTENT loops both until
        // the notification goes: every settle / recover / clear cancels it, and
        // setTimeoutAfter bounds it if our process is gone.
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(NotifIds.CALL, n)
        NotificationLog.add(context, "call", "Unstuck is calling", "About ${payload.label}", payload.deepLink)
    }

    private fun missedPendingIntent(context: Context, callId: String): PendingIntent = PendingIntent.getBroadcast(
        context, ("missed:$callId").hashCode(),
        MissedCallReceiver.intent(context, MissedCallReceiver.ACTION_MISSED, callId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun armMissedAlarm(context: Context, callId: String, fireAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = missedPendingIntent(context, callId)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        // AllowWhileIdle either way: a ring that arrived through Doze must also
        // time out through Doze, or the row sits in `calling` until the server ages it.
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
    }

    /** How long the Answer tap's microphone prompt may stay up before the call
     *  counts as missed after all. */
    const val PERMISSION_HOLD_MS: Long = 30_000

    /**
     * The user tapped Answer and the system microphone prompt is up. The 30 s
     * missed alarm armed at ring time kept running under it, so a slow "Allow"
     * found the call already settled `missed` and the answer was lost (audit
     * 2026-09-22 C13, the Android-only race). Push the alarm out
     * [PERMISSION_HOLD_MS] from now and restart the ringing record's clock (so
     * [recover] doesn't retire it as stale meanwhile). True when the RINGING
     * call is held; false when it is not this call, or is settled / answered.
     */
    fun holdForPermission(context: Context, callId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val p = prefs(context)
            if (p.getString(K_CALL_ID, null) != callId || p.getBoolean(K_SETTLED, true)) return false
            if (p.getString(K_PHASE, PHASE_RINGING) != PHASE_RINGING) return false
            p.edit().putLong(K_PHASE_AT, nowMs).commit()
        }
        armMissedAlarm(context, callId, nowMs + PERMISSION_HOLD_MS)
        // They tapped Answer: the INSISTENT ring must not go on ringing under the
        // microphone prompt (Android audit 2026-09-23, A4). Only the notification
        // goes — the record stays RINGING, so the prompt's answer (or the held
        // alarm) still settles the call exactly as before.
        NotificationManagerCompat.from(context).cancel(NotifIds.CALL)
        return true
    }

    /**
     * Silence the RINGING [callId] without answering it: the power / volume key
     * on the ring screen, the phone's own "not now" (CallKit's side button on
     * iOS). Once the ring loops for the whole 30 s (A4) there was otherwise no
     * way to quieten it short of deciding (Android audit 2026-09-23, A4 review).
     * Only the notification goes, as in [holdForPermission] — the record stays
     * RINGING, so the screen's buttons and the 30 s alarm still settle the call.
     * True when this call was ringing.
     */
    fun silence(context: Context, callId: String): Boolean {
        synchronized(lock) {
            val p = prefs(context)
            if (p.getString(K_CALL_ID, null) != callId || p.getBoolean(K_SETTLED, true)) return false
            if (p.getString(K_PHASE, PHASE_RINGING) != PHASE_RINGING) return false
        }
        NotificationManagerCompat.from(context).cancel(NotifIds.CALL)
        return true
    }

    private fun disarmMissedAlarm(context: Context, callId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(missedPendingIntent(context, callId))
    }

    /** Take the ring notification down (the outcome is settled, or the user
     *  answered and the voice service now owns the notification slot). */
    fun dismissRing(context: Context, callId: String) {
        NotificationManagerCompat.from(context).cancel(NotifIds.CALL)
        disarmMissedAlarm(context, callId)
    }

    // ── outcomes: first one wins ─────────────────────────────────────────────

    /**
     * Record how [callId] ended. True when THIS call settled it: the ring is
     * cleared (notification + alarm) and the outcome is queued for
     * call-outcome (durable, flushed now and on every foreground). False when
     * the call is not the active one or was settled already — the caller must
     * then do nothing (no notification, no service start).
     *
     * `answered` keeps the call ACTIVE (activeCallId stays set, so a second
     * ring meanwhile ends as busy) until the voice service settles it as
     * `done` / `snoozed`; every other outcome ends it.
     *
     * [notifyUnlessRetry]: a local notification to post for this outcome ONCE
     * the server has settled the report without a retry (the persisted ring
     * payload renders it) — the missed path's "I called about X". Null = nothing.
     */
    fun settle(context: Context, callId: String, outcome: CallOutcome, snoozeMin: Int? = null,
               outcomeNotes: List<String>? = null, nowMs: Long = System.currentTimeMillis(),
               notifyUnlessRetry: CallNotificationKind? = null): Boolean {
        val payloadData: Map<String, String>?
        synchronized(lock) {
            val p = prefs(context)
            if (p.getString(K_CALL_ID, null) != callId) return false
            val phase = p.getString(K_PHASE, PHASE_RINGING)
            if (p.getBoolean(K_SETTLED, true)) return false
            payloadData = notifyUnlessRetry?.let { decodePayload(p.getString(K_PAYLOAD, null))?.toData() }
            when (outcome) {
                CallOutcome.ANSWERED -> {
                    if (phase != PHASE_RINGING) return false
                    // phaseAt restarts with the conversation: the ACTIVE record is
                    // bounded from the ANSWER, not from when the phone started ringing.
                    p.edit().putString(K_PHASE, PHASE_ACTIVE).putLong(K_PHASE_AT, nowMs).commit()
                }
                CallOutcome.DECLINED, CallOutcome.MISSED -> {
                    // Only a RINGING call can be declined / missed; an answered one
                    // ends as done / snoozed from the voice service.
                    if (phase != PHASE_RINGING) return false
                    p.edit().putBoolean(K_SETTLED, true).putString(K_PHASE, PHASE_RINGING).commit()
                }
                else -> p.edit().putBoolean(K_SETTLED, true).putString(K_PHASE, PHASE_RINGING).commit()
            }
        }
        dismissRing(context, callId)
        // Durable + flushed now and on every foreground / reconnect (CallOutcomeStore).
        CallOutcomeStore.enqueue(
            context, callId, outcome, snoozeMin, outcomeNotes, nowMs,
            notify = payloadData?.let { notifyUnlessRetry }, payload = payloadData,
        )
        return true
    }

    /** Sign-out / the voice service tearing down without an outcome: forget
     *  the ring (no report — the JWT is gone / the service reported already). */
    fun clear(context: Context) {
        val callId = synchronized(lock) {
            val p = prefs(context)
            val id = p.getString(K_CALL_ID, null)
            p.edit().clear().commit()
            id
        }
        if (callId != null) dismissRing(context, callId)
    }
}
