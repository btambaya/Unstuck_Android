package tech.csalliance.unstuck.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.NotificationLevel
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.core.logic.CallSettingsLogic
import tech.csalliance.unstuck.core.logic.CallsBlockState
import tech.csalliance.unstuck.ui.assistant.AIConsentHost
import tech.csalliance.unstuck.ui.assistant.AIConsentNoteLine
import tech.csalliance.unstuck.core.time.WireTime
import tech.csalliance.unstuck.design.component.MdToggle
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.surface.ExactAlarms
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.tour.TourAnchorIds
import tech.csalliance.unstuck.ui.tour.TourEvents
import tech.csalliance.unstuck.ui.tour.tourAnchor

// Settings → Notifications & calls (section id NOTIFICATIONS, the tour's
// "Notifications" step). Reminders first — the level (the tour's spotlight)
// and the reminder lead — then Calls. Status lines appear ONLY when something
// is wrong (notifications off, late reminders, a call that can't ring or
// can't hear), each with its one-tap fix.

// ── pure helpers (unit-tested) ──────────────────────────────────────────

/** The ring channel CallRinger posts on (calls/CallNotifications, C1 ring-ui). */
internal const val CALLS_CHANNEL_ID = "unstuck_calls"

/** "Try a test call" without the microphone (iOS build 78). */
internal const val CALLS_TEST_MIC_REFUSED = "Calls need microphone access — turn it on for Unstuck in Android Settings."

/** The test call's state — iOS `TestState`. */
internal sealed class TestCallState {
    data object Idle : TestCallState()
    data object Booking : TestCallState()
    data class Booked(val at: String) : TestCallState()
    data class Failed(val why: String) : TestCallState()
}

/** Pure mapping of the request_call result → the row's state (iOS
 *  bookTestCall): an `ok:` carries the time the row landed on. */
internal fun testCallStateFrom(result: String): TestCallState {
    val m = Regex("^ok: call booked \\S+ (\\d{2}:\\d{2})").find(result)
    return if (m != null) TestCallState.Booked(m.groupValues[1])
    else TestCallState.Failed(tech.csalliance.unstuck.ui.tasks.CallMeLogic.userMessage(result).let { if (it.endsWith(".")) it else "$it." })
}

/** iOS "Booked — ringing at HH:MM. Lock your phone and wait." */
internal fun testCallBookedLine(at: String) = "Booked — ringing at $at. Lock your phone and wait."

/** The test-call row's sub-line through each state. */
internal fun testCallLine(state: TestCallState): String = when (state) {
    TestCallState.Idle -> SettingsCopy.CALLS_TEST_SUB
    TestCallState.Booking -> "Booking…"
    is TestCallState.Booked -> testCallBookedLine(state.at)
    is TestCallState.Failed -> state.why
}

/** The mic fix-it line and what a tap on it does. */
internal enum class MicHint { NONE, ASK, OPEN_SETTINGS }

/** iOS shows the line whenever the microphone is denied, from the moment the
 *  screen opens. Android reports a refusal only while it still offers the
 *  prompt ([canAskAgain] = shouldShowRequestPermissionRationale) — then a tap
 *  asks again. After "don't ask again" it can't be told from never asked, so
 *  the line shows once refused here, and a tap opens the app's system page. */
internal fun callsMicHint(granted: Boolean, canAskAgain: Boolean, refusedHere: Boolean): MicHint = when {
    granted -> MicHint.NONE
    canAskAgain -> MicHint.ASK
    refusedHere -> MicHint.OPEN_SETTINGS
    else -> MicHint.NONE
}

/** Do Not Disturb silences the ring: DND is on right now and the Calls
 *  channel isn't allowed through it. Only then is the fix-it line shown. */
internal fun callsSilencedByDnd(interruptionFilterAll: Boolean, channelBypassesDnd: Boolean): Boolean =
    !interruptionFilterAll && !channelBypassesDnd

/** The reminder-lead picker's labels ↔ minutes. */
internal val LEAD_LABELS: List<Pair<String, Int>> = listOf("Off" to 0, "5 min" to 5, "10 min" to 10, "15 min" to 15)
internal fun leadLabel(min: Int): String = LEAD_LABELS.firstOrNull { it.second == min }?.first ?: "$min min"

// ── device checks ───────────────────────────────────────────────────────

/** API 34+: has the user (or Play's calling-app classification) allowed
 *  USE_FULL_SCREEN_INTENT? Below 34 the manifest permission is enough. */
private fun canUseFullScreenIntent(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 34) return true
    val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return true
    return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
}

private fun dndSilencesCalls(context: android.content.Context): Boolean {
    val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return false
    return runCatching {
        val all = nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL ||
            nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val bypass = nm.getNotificationChannel(CALLS_CHANNEL_ID)?.canBypassDnd() ?: false
        callsSilencedByDnd(all, bypass)
    }.getOrDefault(false)
}

private fun notificationsEnabled(context: android.content.Context): Boolean =
    runCatching { androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true)

private fun openAppNotificationSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// ── the screen ──────────────────────────────────────────────────────────

@Composable
internal fun NotificationsCallsContent(vm: AppViewModel, onSection: (SettingsSection) -> Unit) {
    val c = UTheme.colors
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()

    // Everything the status lines read, re-checked whenever we come back from
    // a system page (notification settings, Alarms & reminders, full-screen…).
    var recheck by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recheck++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifsOn = remember(recheck) { notificationsEnabled(context) }
    val exactOk = remember(recheck) { ExactAlarms.granted(context) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!notifsOn) FixItLine(SettingsCopy.NOTIFS_OFF, SettingsCopy.NOTIFS_OFF_FIX, modifier = Modifier.testTag("settings-notifs-off")) { openAppNotificationSettings(context) }
        // The way back to "Alarms & reminders" after the one-time ask on Today
        // (ui/ExactAlarmPrompt): shown while reminders are on and Android 14+
        // still withholds exact alarms (Android audit 2026-09-23, A15).
        if (!exactOk && ExactAlarms.wanted(s)) {
            FixItLine("${SettingsCopy.EXACT_ALARM} ${EXACT_ALARM_ROW_SUB}", SettingsCopy.EXACT_ALARM_FIX, modifier = Modifier.testTag("settings-exact-alarm")) { ExactAlarms.openSystemPage(context) }
        }

        SettingsGroupLabel(SettingsCopy.REMINDERS, Modifier.padding(top = 4.dp))
        SettingsCard {
            // tourAnchor: the guided tour's "You set how present it is" step
            // rings this block (the level + its plain lines).
            Column(Modifier.tourAnchor(TourAnchorIds.NOTIF_BODY)) {
                LevelPicker(s.notificationLevel) { level ->
                    // updateSettings mirrors it to the server and re-arms the alarms.
                    vm.updateSettings { it.copy(notificationLevel = level) }
                }
            }
            CardDivider()
            SegBlock(SettingsCopy.LEAD_ROW, LEAD_LABELS.map { it.first }, leadLabel(s.reminderLeadMin), last = true) { v ->
                val min = LEAD_LABELS.firstOrNull { it.first == v }?.second ?: 0
                vm.updateSettings { it.copy(reminderLeadMin = min) }
            }
        }

        SettingsGroupLabel(SettingsCopy.CALLS, Modifier.padding(top = 14.dp))
        val consent by vm.aiConsent.collectAsStateWithLifecycle()
        val aiOn = vm.aiConsentGranted(consent)
        if (!s.assistantEnabled || !aiOn) {
            // A call is a conversation with the assistant: without the Assistant
            // or AI data sharing the whole block is one line, and "Turn on"
            // switches on what's missing (the sharing OK asks with the usual sheet).
            FixItLine(
                CallsBlockState.needsLine(assistantOn = s.assistantEnabled, aiSharingOn = aiOn), SettingsCopy.CALLS_NEED_ASSISTANT_FIX,
                tint = c.ink2, modifier = Modifier.testTag("settings-calls-need-assistant"),
            ) {
                if (!vm.settings.value.assistantEnabled) vm.updateSettings { it.copy(assistantEnabled = true) }
                if (!vm.aiConsentGranted) vm.withAIConsent(AIConsent.Action.CALLS_ON, AIConsentHost.CALL_SETTINGS) {}
            }
        } else {
            CallsBlock(vm, recheck)
        }
        AIConsentNoteLine(vm, AIConsentHost.CALL_SETTINGS, Modifier.padding(horizontal = 4.dp))
    }
}

/** Calm / Balanced / Coach, each with its one plain line — all three visible,
 *  so the choice is made on what each one does. */
@Composable
private fun LevelPicker(selected: NotificationLevel, onPick: (NotificationLevel) -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
        Text(SettingsCopy.LEVEL_ROW, style = UFont.sans(14, FontWeight.Medium), color = c.ink, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).selectableGroup()) {
            NotificationLevel.entries.forEach { level ->
                val on = level == selected
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = on, role = Role.RadioButton) { onPick(level) }
                        .testTag("settings-level-${level.wire}")
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The app's selection pair: ink with a bg check when chosen.
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .background(if (on) c.ink else c.bg2)
                            .border(1.dp, if (on) c.ink else c.line2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = c.bg, modifier = Modifier.size(14.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(level.label, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
                        Text(level.blurb, style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        Text(SettingsCopy.LEVEL_COACH_NOTE, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
    }
}

@Composable
private fun CallsBlock(vm: AppViewModel, recheck: Int) {
    val c = UTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs by vm.callSettings.collectAsStateWithLifecycle()
    val proactive by vm.callProactivePrefs.collectAsStateWithLifecycle()
    var testState by remember { mutableStateOf<TestCallState>(TestCallState.Idle) }
    // Ask for the microphone while they're looking at this screen: the first
    // prompt otherwise lands mid-ring, over the lock screen (parity with iOS
    // build 78, 0f24908; the Answer-time request stays as the backstop).
    fun micGranted() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    fun micCanAskAgain(): Boolean {
        var host: android.content.Context? = context
        while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
        val activity = host as? android.app.Activity ?: return false
        return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.RECORD_AUDIO)
    }
    var micRefusedHere by remember { mutableStateOf(false) }
    var micCheck by remember { mutableIntStateOf(0) }
    val micHint = remember(micCheck, micRefusedHere, recheck) { callsMicHint(micGranted(), micCanAskAgain(), micRefusedHere) }
    var testAfterMic by remember { mutableStateOf(false) }
    fun bookTest() {
        testState = TestCallState.Booking
        scope.launch { testState = testCallStateFrom(vm.bookTestCall()) }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micRefusedHere = !granted
        micCheck++
        if (testAfterMic) {
            testAfterMic = false
            if (granted) bookTest() else testState = TestCallState.Failed(CALLS_TEST_MIC_REFUSED)
        }
    }
    fun ensureMicrophone() { if (!micGranted()) runCatching { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) } }
    // The account's proactive calls: a toggle made on the web / iPhone reaches this screen.
    LaunchedEffect(Unit) { vm.refreshCallProactivePrefs() }
    val fullScreenOk = remember(recheck) { canUseFullScreenIntent(context) }
    val dndSilences = remember(recheck) { dndSilencesCalls(context) }

    fun pickTime(current: String, commit: (String) -> Unit) {
        val parts = current.split(":").mapNotNull { it.toIntOrNull() }
        android.app.TimePickerDialog(context, { _, h, m -> commit(WireTime.hm(h, m)) }, parts.getOrNull(0) ?: 8, parts.getOrNull(1) ?: 0, true).show()
    }
    fun proactiveOn(v: Boolean) { if (v && cs.enabled) ensureMicrophone() }
    /** A proactive call switched on asks for the AI-consent OK first; off never does. */
    fun setProactive(on: Boolean, apply: () -> Unit) {
        if (!on) { apply(); return }
        vm.withAIConsent(AIConsent.Action.CALLS_ON, AIConsentHost.CALL_SETTINGS) { apply(); proactiveOn(true) }
    }
    val tourRunning = TourEvents.running

    Text(SettingsCopy.CALLS_INTRO, style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(horizontal = 4.dp))

    // Fix-it lines — only when this phone can't ring or can't hear.
    if (cs.enabled) {
        if (!fullScreenOk) FixItLine(SettingsCopy.CALLS_FULL_SCREEN, SettingsCopy.CALLS_FULL_SCREEN_FIX, modifier = Modifier.testTag("settings-calls-full-screen")) {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { openAppNotificationSettings(context) }
        }
        if (micHint != MicHint.NONE) FixItLine(SettingsCopy.CALLS_MIC, SettingsCopy.CALLS_MIC_FIX, tint = c.red, modifier = Modifier.testTag("settings-calls-mic")) {
            if (micHint == MicHint.ASK) ensureMicrophone()
            else runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        if (dndSilences) FixItLine(SettingsCopy.CALLS_DND, SettingsCopy.CALLS_DND_FIX, modifier = Modifier.testTag("settings-calls-dnd")) {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CALLS_CHANNEL_ID)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    SettingsCard {
        ToggleRow(
            SettingsCopy.CALLS_SWITCH, cs.enabled, last = !cs.enabled,
            sub = if (cs.enabled) null else SettingsCopy.CALLS_SWITCH_OFF_SUB,
            modifier = Modifier.testTag("settings-calls-switch"),
        ) { v ->
            if (!v) vm.updateCallSettings { it.copy(enabled = false) }
            // On asks for the AI-consent OK first; "Not now" leaves it off.
            else vm.withAIConsent(AIConsent.Action.CALLS_ON, AIConsentHost.CALL_SETTINGS) {
                vm.updateCallSettings { it.copy(enabled = true) }
                ensureMicrophone()
            }
        }
        if (cs.enabled) {
            // The one guard on when a loud call can ring.
            HoursRow(cs.hoursStart, cs.hoursEnd,
                onStart = { pickTime(cs.hoursStart) { hm -> vm.updateCallSettings { it.copy(hoursStart = hm) } } },
                onEnd = { pickTime(cs.hoursEnd) { hm -> vm.updateCallSettings { it.copy(hoursEnd = hm) } } },
            )
            CardDivider()
            // The three proactive calls are ACCOUNT-wide, off by default
            // (notification_preferences.call_*; AppViewModel.setCallProactivePrefs).
            ProactiveRow(SettingsCopy.CALLS_MORNING, SettingsCopy.CALLS_MORNING_SUB, proactive.morningEnabled, proactive.morningTime, "settings-calls-morning",
                onToggle = { v -> setProactive(v) { vm.setCallProactivePrefs(vm.callProactivePrefs.value.copy(morningEnabled = v)) } },
                onTime = { pickTime(proactive.morningTime) { hm -> vm.setCallProactivePrefs(vm.callProactivePrefs.value.copy(morningTime = hm)) } },
                warning = if (proactive.morningEnabled) CallSettingsLogic.proactiveTimeWarning(proactive.morningTime, cs.enabled, cs.hoursStart, cs.hoursEnd) else null,
            )
            CardDivider()
            ProactiveRow(SettingsCopy.CALLS_EVENING, SettingsCopy.CALLS_EVENING_SUB, proactive.eveningEnabled, proactive.eveningTime, "settings-calls-evening",
                onToggle = { v -> setProactive(v) { vm.setCallProactivePrefs(vm.callProactivePrefs.value.copy(eveningEnabled = v)) } },
                onTime = { pickTime(proactive.eveningTime) { hm -> vm.setCallProactivePrefs(vm.callProactivePrefs.value.copy(eveningTime = hm)) } },
                warning = if (proactive.eveningEnabled) CallSettingsLogic.proactiveTimeWarning(proactive.eveningTime, cs.enabled, cs.hoursStart, cs.hoursEnd) else null,
            )
            CardDivider()
            ProactiveRow(SettingsCopy.CALLS_AFTER_BLOCK, SettingsCopy.CALLS_AFTER_BLOCK_SUB, proactive.afterBlockEnabled, time = null, tag = "settings-calls-after-block",
                onToggle = { v -> setProactive(v) { vm.setCallProactivePrefs(vm.callProactivePrefs.value.copy(afterBlockEnabled = v)) } },
                onTime = {},
                warning = if (proactive.afterBlockEnabled) CallSettingsLogic.afterBlockWarning(cs.enabled, cs.hoursStart, cs.hoursEnd) else null,
            )
            CardDivider()
            // A real booking through the real path: never from inside the guided tour.
            val booking = testState == TestCallState.Booking
            SettingRow(
                SettingsCopy.CALLS_TEST, testCallLine(testState), last = true,
                enabled = !tourRunning && !booking && vm.callsAvailable(),
                lockedSub = if (tourRunning) TOUR_LOCKED_ROW_SUB else testCallLine(testState),
                modifier = Modifier.testTag("settings-calls-test"),
            ) {
                // A call is a conversation with the assistant: the AI-consent OK first.
                vm.withAIConsent(AIConsent.Action.CALLS_ON, AIConsentHost.CALL_SETTINGS) {
                    // A test call that rings and then can't hear them is worse than none:
                    // ask first, while the app is in front of them (iOS build 78).
                    if (!micGranted()) {
                        testAfterMic = true
                        runCatching { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
                            .onFailure { testAfterMic = false; testState = TestCallState.Failed(CALLS_TEST_MIC_REFUSED) }
                    } else bookTest()
                }
            }
        }
    }
}

/** "Only call between [06:00] and [23:00]" — one row, two tappable times,
 *  and what happens outside them. */
@Composable
private fun HoursRow(start: String, end: String, onStart: () -> Unit, onEnd: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).testTag("settings-calls-hours")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SettingsCopy.CALLS_HOURS, style = UFont.sans(14, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
            TimeChip(start, "Calls from $start. Change") { onStart() }
            Text(SettingsCopy.CALLS_HOURS_AND, style = UFont.sans(13), color = c.ink2, modifier = Modifier.clearAndSetSemantics {})
            TimeChip(end, "Calls until $end. Change") { onEnd() }
        }
        Text(SettingsCopy.CALLS_HOURS_SUB, style = UFont.sans(12), color = c.ink3)
    }
}

@Composable
private fun TimeChip(time: String, a11y: String, onClick: () -> Unit) {
    val c = UTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = a11y },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            time, style = UFont.mono(13, FontWeight.Medium), color = c.ink,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.bg2).padding(horizontal = 10.dp, vertical = 6.dp).clearAndSetSemantics {},
        )
    }
}

/** A proactive call: label + plain line, the time (while on) and the switch;
 *  the amber "won't ring here" line under it when this phone would decline. */
@Composable
private fun ProactiveRow(
    label: String, sub: String, on: Boolean, time: String?, tag: String,
    onToggle: (Boolean) -> Unit, onTime: () -> Unit, warning: String?,
) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = UFont.sans(14, FontWeight.Medium), color = c.ink)
            Text(sub, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 2.dp))
        }
        if (on && time != null) {
            Text(SettingsCopy.CALLS_AT, style = UFont.sans(12), color = c.ink3, modifier = Modifier.clearAndSetSemantics {})
            TimeChip(time, "$label at $time. Change", onTime)
        }
        MdToggle(on, onToggle, Modifier.testTag(tag).semantics { contentDescription = label })
    }
    warning?.let { Text(it, style = UFont.sans(12), color = c.amberInk, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) }
}

internal const val EXACT_ALARM_ROW_SUB = "Allow “Alarms & reminders” so they arrive on time."
