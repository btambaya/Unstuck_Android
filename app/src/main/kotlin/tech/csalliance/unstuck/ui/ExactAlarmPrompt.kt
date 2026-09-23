package tech.csalliance.unstuck.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.surface.ExactAlarms
import tech.csalliance.unstuck.surface.ReminderScheduler
import tech.csalliance.unstuck.ui.tour.TourEntryPhase
import tech.csalliance.unstuck.ui.tour.TourEvents
import tech.csalliance.unstuck.ui.tour.TourState
import tech.csalliance.unstuck.ui.tour.TourStateStore
import tech.csalliance.unstuck.ui.tour.initialPhase

internal const val EXACT_ALARM_ASK_TITLE = "Get reminders on time"
internal const val EXACT_ALARM_ASK_BODY =
    "Android holds reminders back until Unstuck may set alarms. Turn on “Alarms & reminders” so " +
        "“Coming up” and “Time to start” arrive when they should. You can do this later in Settings › Focus."

/** Whether the one-time ask still waits on the guided tour: only for its welcome
 *  card, which can land a beat after onboarding. A PAUSED run stays paused until
 *  the user resumes or ends it, often never, so waiting on it meant a user who
 *  paused the tour was never asked (Android audit 2026-09-23, A15). The resume
 *  card, while up, locks the content (TourEvents.contentLocked), which already
 *  holds the ask. */
internal fun exactAlarmAskWaitsForTour(tour: TourState): Boolean = initialPhase(tour) == TourEntryPhase.WELCOME

/**
 * The one-time ask for exact alarms, from Today once the user is signed in and
 * onboarded ([screenFree]: Today, no pushed screen, no focus), after the guided
 * tour has had its turn. It replaces MainActivity.onCreate's prompt, which ran
 * before the session restore knew the user was onboarded, so it was skipped and
 * then fired on a later rotation (Android audit 2026-09-23, A15). Settings › Focus
 * keeps a row for it after that.
 *
 * Also re-arms reminders on every resume once the grant has landed (the user
 * coming back from the system page); the permission broadcast does the same.
 */
@Composable
fun ExactAlarmPrompt(vm: AppViewModel, screenFree: Boolean) {
    val context = LocalContext.current
    val c = UTheme.colors
    val settings by vm.settings.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        (context.applicationContext as? UnstuckApp)?.let { ReminderScheduler.resyncIfNowExact(it) }
    }
    val free = screenFree && !TourEvents.running && !TourEvents.contentLocked
    val wanted = ExactAlarms.wanted(settings)
    LaunchedEffect(free, wanted) {
        if (!free) return@LaunchedEffect
        // Let Today settle: the tour's welcome card can land a beat later, and a card
        // coming up re-keys this effect, which cancels the ask.
        delay(1_500)
        if (!exactAlarmAskWaitsForTour(TourStateStore(context).load()) && ExactAlarms.shouldAsk(ExactAlarms.granted(context), wanted, ExactAlarms.asked(context), screenFree = true)) show = true
    }
    if (show) {
        fun close() { show = false; ExactAlarms.markAsked(context) }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text(EXACT_ALARM_ASK_TITLE, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = { Text(EXACT_ALARM_ASK_BODY, style = UFont.sans(13), color = c.ink2) },
            confirmButton = { TextButton(onClick = { close(); ExactAlarms.openSystemPage(context) }) { Text("Allow", color = c.ink) } },
            dismissButton = { TextButton(onClick = { close() }) { Text("Not now", color = c.ink2) } },
            containerColor = c.surface,
        )
    }
}
