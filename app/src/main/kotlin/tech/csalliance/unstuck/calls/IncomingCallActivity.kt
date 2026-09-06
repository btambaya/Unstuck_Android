package tech.csalliance.unstuck.calls

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.surface.NotificationChannels

/**
 * The full-screen ring: "Unstuck is calling · <label>" with the notes, and
 * **Decline / Snooze 10 / Answer**. Shown over the keyguard with the screen
 * turned on (the CallStyle notification's full-screen intent), excluded from
 * recents, never exported; the notes are hidden behind "N notes · unlock to
 * read" while the device is locked (the ring channel's lock-screen line shows
 * only the label, like a caller id).
 *
 * Every button goes through [CallRinger.settle] — the first outcome for the
 * call wins — and the activity finishes. Answer is the ONLY place the
 * microphone foreground service (calls/CallVoiceService) starts: an
 * Activity in the foreground is the user-interaction exemption Android 14
 * needs for a `microphone` FGS (plan risk 3). The shade's "Answer" action
 * routes here too ([CallRinger.ACTION_ANSWER]) so the same rule holds.
 *
 * Rotation / a recreate: the payload rides in the intent extras
 * (IncomingCallPayload.toData), and the 30 s ring clock is CallRinger's
 * persisted start — so a recreated activity keeps counting from the ORIGINAL
 * ring, not from zero. Whoever fires first — this countdown or the alarm —
 * reports `missed`; the other is a no-op.
 *
 * Plain Views, on purpose: this screen has to be on the glass within a
 * second of a cold-start full-screen intent, ahead of any Compose runtime.
 */
class IncomingCallActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var payload: IncomingCallPayload? = null
    private var notesView: TextView? = null
    private val missedTick = Runnable { onRingTimedOut() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverKeyguard()
        val p = payloadFrom(intent) ?: run { finish(); return }
        payload = p
        // Opened late (from the shade after the alarm settled it as missed, or a
        // stale full-screen intent): nothing to answer.
        if (CallRinger.activeCallId(this) != p.callId) {
            Toast.makeText(this, "This call already ended", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        NotificationChannels.ensureAll(this)
        if (intent.action == CallRinger.ACTION_ANSWER) { answer(); return }
        setContentView(buildUi(p))
        armCountdown()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val p = payloadFrom(intent) ?: return
        if (p.callId != payload?.callId) {
            // A different call rang while this screen was up (the ringer treats
            // the second as busy, so this is a late intent): show the current one.
            payload = p
            setContentView(buildUi(p))
        }
        if (intent.action == CallRinger.ACTION_ANSWER) answer()
    }

    override fun onResume() {
        super.onResume()
        // The ring may have been settled from the shade while we were paused.
        val p = payload ?: return
        if (CallRinger.activeCallId(this) != p.callId) finish()
        notesView?.text = notesText(p)
    }

    override fun onDestroy() {
        handler.removeCallbacks(missedTick)
        super.onDestroy()
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private fun answer() {
        val p = payload ?: return
        handler.removeCallbacks(missedTick)
        if (CallRinger.settle(this, p.callId, CallOutcome.ANSWERED)) {
            // The user tapped Answer on a foreground Activity: the one path
            // allowed to start the microphone foreground service.
            runCatching { voiceStarter(this, p) }.onFailure { e ->
                // iOS: `done` with a "voice failed: …" note + the what-it-was-about notice.
                CallRinger.settle(this, p.callId, CallOutcome.DONE, outcomeNotes = listOf("voice failed: ${e.message ?: e.javaClass.simpleName}"))
                CallNotifications.voiceFailed(this, p)
            }
        }
        finish()
    }

    private fun decline() {
        val p = payload ?: return
        handler.removeCallbacks(missedTick)
        CallRinger.settle(this, p.callId, CallOutcome.DECLINED)
        finish()
    }

    private fun snooze() {
        val p = payload ?: return
        handler.removeCallbacks(missedTick)
        if (CallRinger.settle(this, p.callId, CallOutcome.SNOOZED, snoozeMin = MissedCallReceiver.SNOOZE_MIN)) {
            CallNotifications.snoozed(this, p, MissedCallReceiver.SNOOZE_MIN)
        }
        finish()
    }

    private fun onRingTimedOut() {
        val p = payload ?: return
        if (CallRinger.settle(this, p.callId, CallOutcome.MISSED)) CallNotifications.missed(this, p)
        finish()
    }

    /** Count from the ORIGINAL ring start (a recreated activity must not add 30 s). */
    private fun armCountdown() {
        val started = CallRinger.ringStartedMs(this) ?: System.currentTimeMillis()
        val remaining = (started + CallRinger.MISSED_AFTER_MS - System.currentTimeMillis()).coerceAtLeast(0)
        handler.postDelayed(missedTick, remaining)
    }

    // ── window ───────────────────────────────────────────────────────────────

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    private fun locked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    // ── ui ───────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun notesText(p: IncomingCallPayload): String = when {
        p.notes.isEmpty() -> "No notes on this one."
        locked() -> "${p.notes.size} ${if (p.notes.size == 1) "note" else "notes"} · unlock to read"
        else -> p.notes.joinToString("\n") { "• $it" }
    }

    private fun buildUi(p: IncomingCallPayload): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(72), dp(28), dp(40))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(TextView(this).apply {
            id = ID_EYEBROW
            text = "UNSTUCK IS CALLING"
            setTextColor(CORAL)
            letterSpacing = 0.12f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            id = ID_LABEL
            text = p.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        })
        p.taskName?.takeIf { it != p.label }?.let { name ->
            root.addView(TextView(this).apply {
                id = ID_TASK
                text = name
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            })
        }
        val notes = TextView(this).apply {
            id = ID_NOTES
            text = notesText(p)
            setTextColor(Color.argb(230, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(0f, 1.25f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(28), dp(8), dp(28))
        }
        notesView = notes
        root.addView(ScrollView(this).apply {
            addView(notes)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(pill(ID_DECLINE, "Decline", DECLINE_RED) { decline() })
        row.addView(pill(ID_SNOOZE, "Snooze 10", SNOOZE_GREY) { snooze() })
        row.addView(pill(ID_ANSWER, "Answer", ANSWER_GREEN) { answer() })
        root.addView(row)
        return root
    }

    private fun pill(id: Int, label: String, color: Int, onTap: () -> Unit): View = Button(this).apply {
        this.id = id
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(color) }
        stateListAnimator = null
        minimumHeight = dp(56)
        minHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(5); marginEnd = dp(5) }
        setOnClickListener { onTap() }
    }

    companion object {
        // Stable view ids (tests + accessibility); the values only need to be unique.
        const val ID_EYEBROW = 0x0C0A0001
        const val ID_LABEL = 0x0C0A0002
        const val ID_TASK = 0x0C0A0003
        const val ID_NOTES = 0x0C0A0004
        const val ID_DECLINE = 0x0C0A0005
        const val ID_SNOOZE = 0x0C0A0006
        const val ID_ANSWER = 0x0C0A0007

        private const val BG = 0xFF14151A.toInt()
        private const val CORAL = 0xFFE89077.toInt()
        private const val MUTED = 0xFFA7A9B3.toInt()
        private const val DECLINE_RED = 0xFFD64545.toInt()
        private const val SNOOZE_GREY = 0xFF3A3D47.toInt()
        private const val ANSWER_GREEN = 0xFF2FA35B.toInt()

        /** Starts the in-call voice foreground service. A test seam: production
         *  is [CallVoiceService.start], which MUST only be called from here. */
        var voiceStarter: (Context, IncomingCallPayload) -> Unit = { ctx, p -> CallVoiceService.start(ctx, p) }

        /** The payload the ring / answer intents carry (one String extra per key). */
        fun payloadFrom(intent: Intent?): IncomingCallPayload? {
            val extras = intent?.extras ?: return null
            val map = HashMap<String, String>()
            for (k in extras.keySet()) {
                @Suppress("DEPRECATION")
                (extras.get(k) as? String)?.let { map[k] = it }
            }
            return IncomingCallPayload.fromData(map)
        }
    }
}
