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
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Route `unstuck://calendar-callback?code&state` to the calendar connect flow and
     *  `unstuck://auth-callback?code` to the PKCE code exchange ([completeAuthLink]). */
    private fun handleAuthOrCalendar(intent: Intent?) {
        val data = intent?.data
        // A Recents relaunch re-delivers the ORIGINAL launch intent: its auth code was
        // spent when first tapped, so exchanging it again can only fail (A7 below).
        if (data?.scheme == "unstuck" && data.host == "auth-callback" &&
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        ) return
        if (data?.scheme == "unstuck" && data.host == "calendar-callback") {
            // With or without a code: a denied or cancelled consent comes back as
            // `?error=access_denied&state=…` and used to end here with no message. The
            // coordinator reports it to the calendar bar and exchanges only a code whose
            // state it minted (parity with iOS build 81, audit 2026-09-22 C18).
            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")
            lifecycleScope.launch { graph.coordinator?.completeGoogleConnect(code, state) }
            return
        }
        // Notification "Capture" action → open quick capture.
        if (intent?.getBooleanExtra(tech.csalliance.unstuck.surface.NotificationActionReceiver.EXTRA_OPEN_CAPTURE, false) == true) {
            graph.pendingDeepLink.value = "capture"
            return
        }
        // Password-recovery link → flag it so AppRoot shows the set-new-password screen
        // once Supabase establishes the recovery session (completeAuthLink below). The
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
        if (data?.scheme == "unstuck" && data.host == "auth-callback") completeAuthLink(data)
    }

    /** Exchange an auth-callback link's PKCE code for a session HERE, not through
     *  supabase-kt's handleDeeplinks: that ran the exchange on the SDK's own scope with
     *  no handler, so a refused code (an older email after a second link / reset
     *  request, a reused code, cleared app data) or a network drop crashed the app
     *  (Android audit 2026-09-23, A7). On the process scope, so a rotation mid-exchange
     *  can't cancel it. Signed out, the reason goes to AuthScreen; otherwise a toast. */
    private fun completeAuthLink(data: android.net.Uri) {
        val client = graph.provider?.client ?: return
        val code = data.getQueryParameter("code")
        val errorCode = data.getQueryParameter("error_code")
        val errorDescription = data.getQueryParameter("error_description")
        graph.scope.launch {
            val failure = tech.csalliance.unstuck.ui.auth.AuthLink.complete(code, errorCode, errorDescription) {
                client.auth.exchangeCodeForSession(it)
            }
            // No session came from this link, so it can't be the one the probe waits for.
            if (failure != null || code.isNullOrBlank()) graph.pendingRecoveryProbe.value = false
            if (failure == null) return@launch
            if (client.auth.sessionStatus.value is SessionStatus.NotAuthenticated) {
                graph.authLinkError.value = failure
            } else {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(graph.appContext, failure, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
