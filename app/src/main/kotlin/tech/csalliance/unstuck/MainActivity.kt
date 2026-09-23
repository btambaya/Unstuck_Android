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
import kotlinx.coroutines.CoroutineStart
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
     *  can't cancel it; undispatched, so AuthLink counts links in arrival order. Signed
     *  out, the reason goes to AuthScreen; otherwise a toast.
     *
     *  The recovery probe armed above is left alone here, even when this link fails:
     *  only an exchange's own session (SessionSource.External — auth-kt 3.0.3 emits it
     *  from exchangeCodeForSession alone) can consume it, and that session's amr
     *  decides. Clearing it for a failed or code-less copy of a link opened twice
     *  switched it off while the first copy was still exchanging a reset, which then
     *  landed on Today with the link spent (Android audit 2026-09-23, A7). */
    private fun completeAuthLink(data: android.net.Uri) {
        val client = graph.provider?.client ?: return
        val code = data.getQueryParameter("code")
        val errorCode = data.getQueryParameter("error_code")
        val errorDescription = data.getQueryParameter("error_description")
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = tech.csalliance.unstuck.ui.auth.AuthLink.complete(code, errorCode, errorDescription) {
                client.auth.exchangeCodeForSession(it)
            } ?: return@launch
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
