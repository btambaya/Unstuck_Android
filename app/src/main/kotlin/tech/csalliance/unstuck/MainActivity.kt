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
        // Exact alarms are asked for from Today once the user is in the app
        // (ui/ExactAlarmPrompt) and in Settings › Focus, not here: onCreate ran before
        // the async session restore knew the user was onboarded, so the ask was
        // skipped, then fired on some later rotation (Android audit 2026-09-23, A15).
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
