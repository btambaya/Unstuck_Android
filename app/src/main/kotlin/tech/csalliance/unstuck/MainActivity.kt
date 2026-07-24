package tech.csalliance.unstuck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.surface.SyncWorker
import tech.csalliance.unstuck.surface.registerFcmToken
import tech.csalliance.unstuck.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val graph get() = (application as UnstuckApp).graph
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // OAuth / magic-link PKCE callback (unstuck://auth-callback) + Google
        // Calendar consent return (unstuck://calendar-callback). ONLY on a fresh
        // create — a config change (rotation / theme / locale) recreates the Activity
        // with the same launch Intent, which would otherwise re-fire the deep link
        // (re-open the task, re-run the calendar exchange). onNewIntent covers later ones.
        if (savedInstanceState == null) handleAuthOrCalendar(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Exact alarms — without this, task reminders fall back to INEXACT alarms,
        // which Android batches/Doze-delays so they fire late or not at all. On
        // Android 12+ it can be denied (and is denied-by-default for apps targeting
        // 14+). Prompt once so scheduled-task / promote reminders actually fire.
        maybePromptExactAlarm()
        // Register the FCM token once a session exists (so it lands on first
        // sign-in, and re-registers on a later sign-in / token refresh). The
        // StateFlow emits its current value immediately, covering relaunches
        // while already signed in.
        graph.provider?.client?.let { client ->
            lifecycleScope.launch {
                client.auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> registerFcmToken(application as UnstuckApp)
                        is SessionStatus.NotAuthenticated -> if (status.isSignOut) {
                            // Don't leak the previous user's notification history / reminder
                            // settings to a different account on this device.
                            tech.csalliance.unstuck.surface.NotificationLog.clear(this@MainActivity)
                            graph.settings.clearUserContent()
                            // Guided-tour state is per-account: the next account must
                            // not inherit this one's paused run / done flag (ambush,
                            // or an eaten one-time offer). Their own onboarding
                            // re-arms `eligible`.
                            tech.csalliance.unstuck.ui.tour.TourStateStore(this@MainActivity).clear()
                        }
                        else -> {}
                    }
                }
            }
        }
        SyncWorker.schedule(this)

        setContent {
            // AppRoot owns UnstuckTheme so it reacts to the persisted
            // theme / accent / density settings.
            AppRoot(graph)
        }
    }

    /** One-time nudge to the system "Alarms & reminders" toggle so exact alarms
     *  (and thus reliable reminders) work. Only when reminders are on + not yet
     *  granted + not asked before. */
    private fun maybePromptExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Don't bounce a brand-new user to the system Alarms-&-reminders screen before
        // they've even seen onboarding — wait until they're in the app.
        if (!graph.onboarded) return
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return
        if (am.canScheduleExactAlarms()) return
        // Skip only when NO exact-alarm-driven moment is enabled. Lead reminders off is
        // not enough — Balanced/Coach still schedule start-now & drift alarms.
        val s = graph.settings.load()
        if (s.reminderLeadMin <= 0 && s.notificationLevel == tech.csalliance.unstuck.NotificationLevel.CALM) return
        val prefs = getSharedPreferences("unstuck.app", MODE_PRIVATE)
        if (prefs.getBoolean("exactAlarmPrompted", false)) return
        // Only mark as prompted if the Settings screen actually launched — a failed
        // launch can then be retried on a later cold start (was set unconditionally).
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")),
            )
        }.onSuccess { prefs.edit().putBoolean("exactAlarmPrompted", true).apply() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthOrCalendar(intent)
    }

    /** Route `unstuck://calendar-callback?code&state` to the calendar connect flow;
     *  everything else goes to Supabase's PKCE deep-link handler. */
    private fun handleAuthOrCalendar(intent: Intent?) {
        val data = intent?.data
        if (data?.scheme == "unstuck" && data.host == "calendar-callback") {
            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")
            if (code != null && state != null) {
                lifecycleScope.launch { graph.coordinator?.completeGoogleConnect(code, state) }
                return
            }
        }
        // Notification "Capture" action → open quick capture.
        if (intent?.getBooleanExtra(tech.csalliance.unstuck.surface.NotificationActionReceiver.EXTRA_OPEN_CAPTURE, false) == true) {
            graph.pendingDeepLink.value = "capture"
            return
        }
        // Password-recovery link → flag it so AppRoot shows the set-new-password screen
        // once Supabase establishes the recovery session (handleDeeplinks below). The
        // implicit/token flow carries `type=recovery` in the URL; the PKCE flow does
        // NOT (it comes back as `unstuck://auth-callback?code=…`), so for auth-callback
        // links we instead ARM a probe and let the session observer classify the
        // exchanged session by its token `amr` (recovery vs magic-link / OAuth).
        if (data?.toString()?.contains("type=recovery", ignoreCase = true) == true) {
            graph.pendingPasswordRecovery.value = true
        } else if (data?.scheme == "unstuck" && data.host == "auth-callback") {
            graph.pendingRecoveryProbe.value = true
        }
        // Notification taps → route to the task / today / tasks / recap / brief / focus / collections (consumed by MainScaffold).
        // `tasks` is what share-notify sends (unstuck://tasks) for shared/session/done pings.
        if (data?.scheme == "unstuck" && (data.host == "task" || data.host == "tasks" || data.host == "today" || data.host == "focus" || data.host == "collections")) {
            graph.pendingDeepLink.value = data.toString()
            return
        }
        intent?.let { graph.provider?.client?.handleDeeplinks(it) }
    }
}
