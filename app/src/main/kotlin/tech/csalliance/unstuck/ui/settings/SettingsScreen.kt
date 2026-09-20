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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.SettingsStore
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.Density
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.ThemePref
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.theme.AccentPalette
import tech.csalliance.unstuck.sync.AuthOutcome
import tech.csalliance.unstuck.design.component.ColorChip
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdSegment
import tech.csalliance.unstuck.design.component.MdToggle
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.feedback.FeedbackSheet
import tech.csalliance.unstuck.ui.tour.TourAnchorIds
import tech.csalliance.unstuck.ui.tour.TourEvents
import tech.csalliance.unstuck.ui.tour.tourAnchor
import tech.csalliance.unstuck.ui.assistant.FactsPanelContent
import tech.csalliance.unstuck.ui.assistant.FactsPanelCopy

enum class SettingsSection(val title: String, val eyebrow: String) {
    ACCOUNT("Your account.", "SETTINGS · ACCOUNT"),
    PEOPLE("Sit with someone, not be watched.", "SETTINGS · PEOPLE"),
    FOCUS("How focus mode behaves.", "SETTINGS · FOCUS"),
    /** "Calls from Unstuck" (iOS CallSettingsView): kill-switch, allowed hours,
     *  default lead, the full-screen-intent permission row, "Test call now". */
    CALLS("Ask, and Unstuck calls you.", "SETTINGS · CALLS"),
    SOUND("Quiet by default.", "SETTINGS · SOUND"),
    A11Y("Adjust to your brain.", "SETTINGS · ACCESSIBILITY"),
    INTERFACE("How things look.", "SETTINGS · INTERFACE"),
    /** The assistant's memory — every fact it has learned, editable and
     *  deletable, plus which recurring moments it runs (web/iOS FactsPanel). */
    MEMORY(FactsPanelCopy.TITLE, "SETTINGS · MEMORY"),
    BACKUP("Your data is yours.", "SETTINGS · BACKUP"),
    AREAS("One list. The whole life.", "SETTINGS · AREAS"),
    TAGS("Your tag vocabulary.", "SETTINGS · TAGS"),
}

private val HUB = listOf(
    "Account" to SettingsSection.ACCOUNT, "People" to SettingsSection.PEOPLE, "Focus" to SettingsSection.FOCUS,
    CALLS_NAV_TITLE to SettingsSection.CALLS, "Sound" to SettingsSection.SOUND,
    "Accessibility" to SettingsSection.A11Y, "Interface" to SettingsSection.INTERFACE,
    FactsPanelCopy.NAV_TITLE to SettingsSection.MEMORY, "Backup" to SettingsSection.BACKUP,
    "Areas" to SettingsSection.AREAS, "Tags" to SettingsSection.TAGS,
)

@Composable
fun SettingsHub(vm: AppViewModel, onBack: () -> Unit, onSection: (SettingsSection) -> Unit, onInsights: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = "Settings", leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp)) {
            SectionLabel("Settings", color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
            Text("How Unstuck behaves.", style = UFont.serifItalic(28), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
                HUB.forEachIndexed { i, (label, section) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Row(Modifier.fillMaxWidth().clickable { onSection(section) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = UFont.sans(14, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.ink3, modifier = Modifier.height(18.dp))
                    }
                }
            }
            Box(Modifier.padding(24.dp)) {}
        }
    }
}

@Composable
fun SettingsSubScreen(vm: AppViewModel, section: SettingsSection, onBack: () -> Unit) {
    val c = UTheme.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = section.name.lowercase().replaceFirstChar { it.uppercase() }, leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp)) {
            SectionLabel(section.eyebrow, color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
            Text(section.title, style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            when (section) {
                SettingsSection.AREAS -> AreasContent(vm)
                SettingsSection.TAGS -> TagsContent(vm)
                SettingsSection.ACCOUNT -> AccountContent(vm)
                SettingsSection.PEOPLE -> ConnectionsContent(vm)
                SettingsSection.MEMORY -> FactsPanelContent(vm)
                SettingsSection.CALLS -> CallsContent(vm)
                SettingsSection.FOCUS -> SettingsCard {
                    SegRow("Default focus length", listOf("15", "25", "45"), s.focusDefaultMin.toString()) { v ->
                        vm.updateSettings { it.copy(focusDefaultMin = v.toIntOrNull() ?: 25) }
                    }
                    SegRow("Soft overrun", listOf("Off", "5", "10"), if (s.focusOverrunMin == 0) "Off" else s.focusOverrunMin.toString()) { v ->
                        vm.updateSettings { it.copy(focusOverrunMin = v.toIntOrNull() ?: 0) }
                    }
                    SegRow("Remind me before tasks", listOf("Off", "5", "10", "15"), if (s.reminderLeadMin == 0) "Off" else s.reminderLeadMin.toString()) { v ->
                        vm.updateSettings { it.copy(reminderLeadMin = v.toIntOrNull() ?: 0) }
                        runCatching { tech.csalliance.unstuck.surface.ReminderScheduler.reschedule(context.applicationContext as tech.csalliance.unstuck.UnstuckApp) }
                    }
                    // tourAnchor: the guided tour's "You set how present it is"
                    // step rings this notification-presence block (Android's
                    // Calm/Balanced/Coach control lives here, not on a
                    // dedicated Notifications screen).
                    Column(Modifier.tourAnchor(TourAnchorIds.NOTIF_BODY)) {
                        SegRow("Notifications", listOf("Calm", "Balanced", "Coach"), s.notificationLevel.label) { v ->
                            vm.updateSettings { it.copy(notificationLevel = tech.csalliance.unstuck.NotificationLevel.fromLabel(v)) }
                            runCatching { tech.csalliance.unstuck.surface.ReminderScheduler.reschedule(context.applicationContext as tech.csalliance.unstuck.UnstuckApp) }
                        }
                        Text(
                            s.notificationLevel.blurb,
                            style = UFont.sans(12, FontWeight.Normal),
                            color = c.ink2,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                    ToggleRow("Hide right rail while focusing", s.focusCollapseRail) { v -> vm.updateSettings { it.copy(focusCollapseRail = v) } }
                    ToggleRow("Soft exit", s.focusSoftExit) { v -> vm.updateSettings { it.copy(focusSoftExit = v) } }
                    ToggleRow("Pause reasons", s.focusPauseReasons) { v -> vm.updateSettings { it.copy(focusPauseReasons = v) } }
                    // Hands-Free Focus Copilot (Phase 1, on-device, no LLM/network).
                    ToggleRow("Spoken focus coach", s.focusCopilotSpeak) { v -> vm.updateSettings { it.copy(focusCopilotSpeak = v) } }
                    Text(
                        "Speaks short progress check-ins out loud during a focus block (halfway, five-to-go, time's up). On-device.",
                        style = UFont.sans(12, FontWeight.Normal), color = c.ink2,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (s.focusCopilotSpeak) 0.dp else 12.dp),
                    )
                    // Sub-toggle: only meaningful when the spoken coach is on (it adds the mic).
                    if (s.focusCopilotSpeak) {
                        ToggleRow("Voice replies", s.focusCopilotVoice, last = true) { v -> vm.updateSettings { it.copy(focusCopilotVoice = v) } }
                        Text(
                            "After a spoken question, listen for a hands-free reply (\"add five\", \"stop\", \"keep going\", \"note …\"). Uses the mic only for a few seconds; nothing is recorded or sent.",
                            style = UFont.sans(12, FontWeight.Normal), color = c.ink2,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
                SettingsSection.SOUND -> SettingsCard {
                    ToggleRow("Start chime", s.soundStartChime) { v -> vm.updateSettings { it.copy(soundStartChime = v) } }
                    ToggleRow("Overrun bell", s.soundOverrunBell) { v -> vm.updateSettings { it.copy(soundOverrunBell = v) } }
                    ToggleRow("Completion sound", s.soundCompletion) { v -> vm.updateSettings { it.copy(soundCompletion = v) } }
                    SegRow("Ambient", listOf("off", "brown", "pink"), s.ambient, last = true) { v -> vm.updateSettings { it.copy(ambient = v) } }
                }
                SettingsSection.A11Y -> SettingsCard {
                    ToggleRow("Reduce motion", s.reduceMotion) { v -> vm.updateSettings { it.copy(reduceMotion = v) } }
                    ToggleRow("Larger type", s.largerType) { v -> vm.updateSettings { it.copy(largerType = v) } }
                    ToggleRow("High contrast", s.highContrast) { v -> vm.updateSettings { it.copy(highContrast = v) } }
                    ToggleRow("Keyboard hints", s.keyboardHints, last = true) { v -> vm.updateSettings { it.copy(keyboardHints = v) } }
                }
                SettingsSection.INTERFACE -> SettingsCard {
                    SegRow("Theme", listOf("system", "light", "dark"), s.theme.name.lowercase()) { v ->
                        vm.updateSettings { it.copy(theme = ThemePref.valueOf(v.uppercase())) }
                    }
                    // The AI Assistant kill-switch the privacy policy promises
                    // ("Settings → Interface → AI Assistant. Turn it off entirely").
                    // Same slot as web (directly under Theme); off unmounts the
                    // launcher so nothing reaches the AI provider.
                    ToggleRow("AI Assistant", s.assistantEnabled) { v -> vm.updateSettings { it.copy(assistantEnabled = v) } }
                    // Realtime voice fallback (bargein.md §8): press-and-hold instead of
                    // an open mic. Device-local (SettingsStore key voice.holdToTalk, same
                    // slot as web "Voice: hold to talk"); a voice session reads it once
                    // at start, and the in-call "Noisy room?" chip flips it too.
                    if (s.assistantEnabled) {
                        val voiceStore = remember { SettingsStore(context) }
                        var holdToTalk by remember { mutableStateOf(voiceStore.voiceHoldToTalk()) }
                        ToggleRow("Hold to talk", holdToTalk) { v -> holdToTalk = v; voiceStore.setVoiceHoldToTalk(v) }
                        Text(
                            "For noisy rooms: press and hold the orb to speak; release to send",
                            style = UFont.sans(12, FontWeight.Normal), color = c.ink2,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                    SegRow("Accent", listOf("indigo", "rose", "forest"), accentKey(s.accent)) { v ->
                        vm.updateSettings { it.copy(accent = accentFromKey(v)) }
                    }
                    SegRow("Density", listOf("compact", "regular", "comfy"), s.density.name.lowercase(), last = true) { v ->
                        vm.updateSettings { it.copy(density = Density.valueOf(v.uppercase())) }
                    }
                }
                SettingsSection.BACKUP -> BackupContent(vm)
            }
            Box(Modifier.padding(24.dp)) {}
        }
    }
}

// ── Calls from Unstuck (iOS App/Calls/CallSettingsView.swift, copy verbatim) ──

/** The hub row / nav title. */
internal const val CALLS_NAV_TITLE = "Calls from Unstuck"
internal const val CALLS_EXPLAINER_TITLE = "Ask, and Unstuck calls you"
internal const val CALLS_EXPLAINER_BODY = "Say \"call me at three about the James meeting — remind me about A, B and C\", or tick \"Call me about this\" on a task. Your phone rings like a normal call, the notes are read back, then you can tick things off, add a thought, start a timer, or ask for a call-back — all by voice. Nothing is booked unless you ask."
internal const val CALLS_DEVICE_READY = "This phone can take calls."
/** Android's analogue of the iOS "waiting for the call token" line: the API 34
 *  full-screen-intent grant is what lets the ring take the lock screen. */
internal const val CALLS_FULL_SCREEN_OFF = "Full-screen calls are off for Unstuck — a call shows as a notification until you allow them."
internal const val CALLS_FULL_SCREEN_ROW = "Allow full-screen calls"
internal const val CALLS_FULL_SCREEN_ROW_SUB = "Lets a call take over the lock screen, like the phone app"
/** The one-time nudge's explainer (Android's twin of the iOS VoIP-registration
 *  nudge): without the grant a call arrives as a notification you tap. */
internal const val CALLS_FULL_SCREEN_NUDGE = "Calls need the full-screen permission on this phone — without it a call arrives as a notification you tap instead of a ring."
internal const val CALLS_FULL_SCREEN_DISMISS = "Not now"
internal const val CALLS_ENABLED_ROW = "Calls from Unstuck"
internal const val CALLS_ENABLED_OFF_HINT = "Calls are declined quietly — you get the notes as a notification instead."
internal const val CALLS_ASSISTANT_OFF_HINT = "Calls are part of the AI Assistant — turn it on under Settings → Interface to receive them."
internal const val CALLS_HOURS_HINT_SUFFIX = "A call outside these hours is declined quietly and you get the notes as a notification instead."
internal const val CALLS_LEAD_HINT = "\"Call me about this\" on a scheduled task rings this many minutes before it starts."
internal const val CALLS_TEST_BODY = "Book a test call for one minute from now. Lock your phone — it rings through the real path (server → push → call screen)."
// The three OPT-IN proactive calls (calls build-out 2026-09-20; iOS CallSettingsView.proactiveCard, copy verbatim).
internal const val CALLS_PROACTIVE_SECTION = "Calls Unstuck can make on its own"
internal const val CALLS_PROACTIVE_MORNING = "Morning planning call"
internal const val CALLS_PROACTIVE_MORNING_SUB = "Rings to walk through the day and plan it with you."
internal const val CALLS_PROACTIVE_EVENING = "Evening wrap-up call"
internal const val CALLS_PROACTIVE_EVENING_SUB = "Rings to go over what got done and what moves to tomorrow."
internal const val CALLS_PROACTIVE_AFTER_BLOCK = "Check in after a block"
internal const val CALLS_PROACTIVE_AFTER_BLOCK_SUB = "Rings when a block ends without its task marked done — how did it go?"
internal const val CALLS_PROACTIVE_AT = "At"
internal const val CALLS_PROACTIVE_HINT = "All off unless you switch them on. They ring within your allowed hours, on every phone where calls are on."
internal const val CALLS_TEST_BUTTON = "Test call now"
internal const val CALLS_TEST_BOOKING = "Booking…"
internal const val CALLS_DND_HINT = "Under Do Not Disturb, a call only rings if Unstuck's Calls notifications are allowed to interrupt."
internal const val CALLS_DND_ROW = "Calls notification channel"
internal const val CALLS_DND_ROW_SUB = "Sound, vibration and Do Not Disturb for the ring"
/** The ring channel CallRinger posts on (calls/CallNotifications, C1 ring-ui). */
internal const val CALLS_CHANNEL_ID = "unstuck_calls"

/** "Test call now" outcome — iOS `TestState`. */
internal sealed class TestCallState {
    data object Idle : TestCallState()
    data object Booking : TestCallState()
    data class Booked(val at: String) : TestCallState()
    data class Failed(val why: String) : TestCallState()
}

/** Pure mapping of the request_call result → the card's state (iOS
 *  bookTestCall): an `ok:` carries the time the row landed on. */
internal fun testCallStateFrom(result: String): TestCallState {
    val m = Regex("^ok: call booked \\S+ (\\d{2}:\\d{2})").find(result)
    return if (m != null) TestCallState.Booked(m.groupValues[1])
    else TestCallState.Failed(tech.csalliance.unstuck.ui.tasks.CallMeLogic.userMessage(result).let { if (it.endsWith(".")) it else "$it." })
}

/** iOS "Booked — ringing at HH:MM. Lock your phone and wait." */
internal fun testCallBookedLine(at: String) = "Booked — ringing at $at. Lock your phone and wait."

/** Allowed-hours sub-line: the user's hint + the server window. */
internal fun callsHoursHint(): String {
    val w = tech.csalliance.unstuck.core.logic.CallSettingsLogic.SERVER_WINDOW
    return "$CALLS_HOURS_HINT_SUFFIX Calls can only be booked between ${w.start} and ${w.endInclusive}."
}

/** API 34+: has the user (or Play's calling-app classification) allowed
 *  USE_FULL_SCREEN_INTENT? Below 34 the manifest permission is enough. */
private fun canUseFullScreenIntent(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 34) return true
    val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return true
    return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
}

@Composable
private fun CallsContent(vm: AppViewModel) {
    val c = UTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val cs by vm.callSettings.collectAsStateWithLifecycle()
    val proactive by vm.callProactivePrefs.collectAsStateWithLifecycle()
    val nudgeDismissed by vm.ringNudgeDismissed.collectAsStateWithLifecycle()
    var testState by remember { mutableStateOf<TestCallState>(TestCallState.Idle) }
    // The account's proactive calls: a toggle made on the web / iPhone reaches this screen.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshCallProactivePrefs() }
    // Re-check the full-screen grant whenever we come back from the system page.
    var fullScreenOk by remember { mutableStateOf(canUseFullScreenIntent(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) fullScreenOk = canUseFullScreenIntent(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun pickHour(current: String, commit: (String) -> Unit) {
        val parts = current.split(":").mapNotNull { it.toIntOrNull() }
        val h0 = parts.getOrNull(0) ?: 8
        val m0 = parts.getOrNull(1) ?: 0
        android.app.TimePickerDialog(context, { _, h, m -> commit("%02d:%02d".format(h, m)) }, h0, m0, true).show()
    }

    // Explainer (iOS `explainer` + `deviceStatus`)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bg2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Call, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
            Text(CALLS_EXPLAINER_TITLE, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink)
        }
        Text(CALLS_EXPLAINER_BODY, style = UFont.sans(13), color = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(if (fullScreenOk) c.green else c.ink3))
            Text(if (fullScreenOk) CALLS_DEVICE_READY else CALLS_FULL_SCREEN_OFF, style = UFont.sans(12), color = c.ink3)
        }
    }

    // Risk 1: on API 34 USE_FULL_SCREEN_INTENT is pre-granted only to apps Play
    // classifies as calling/alarm; otherwise the ring degrades to a heads-up.
    // The ONE-TIME nudge (iOS's VoIP-registration nudge, Android's shape): shown
    // until the grant lands or the user says "Not now" — the status line above
    // keeps saying calls degrade either way. Deep-links the per-app system page.
    if (tech.csalliance.unstuck.core.logic.CallRingNudge.shouldShow(canRing = fullScreenOk, dismissed = nudgeDismissed)) {
        SectionLabel("Permission", color = c.primaryDeep, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
        SettingsCard {
            Text(CALLS_FULL_SCREEN_NUDGE, style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
            SettingRow(CALLS_FULL_SCREEN_ROW, CALLS_FULL_SCREEN_ROW_SUB) {
                runCatching {
                    val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }.onFailure {
                    runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            SettingRow(CALLS_FULL_SCREEN_DISMISS, null, last = true) { vm.dismissRingNudge() }
        }
    }

    // Kill-switch (risk 10: the assistant switch governs calls too).
    SectionLabel("Calls", color = c.primaryDeep, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    SettingsCard {
        if (s.assistantEnabled) {
            ToggleRow(CALLS_ENABLED_ROW, cs.enabled, last = true) { v -> vm.updateCallSettings { it.copy(enabled = v) } }
        } else {
            Text(CALLS_ASSISTANT_OFF_HINT, style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(16.dp))
        }
    }
    if (s.assistantEnabled && !cs.enabled) {
        Text(CALLS_ENABLED_OFF_HINT, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 10.dp))
    }

    // Allowed hours
    SectionLabel("Allowed hours", color = c.primaryDeep, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    SettingsCard {
        SettingRow("From", cs.hoursStart) { pickHour(cs.hoursStart) { hm -> vm.updateCallSettings { it.copy(hoursStart = hm) } } }
        SettingRow("Until", cs.hoursEnd, last = true) { pickHour(cs.hoursEnd) { hm -> vm.updateCallSettings { it.copy(hoursEnd = hm) } } }
    }
    Text(callsHoursHint(), style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 10.dp))

    // Default lead
    SectionLabel("Default lead for task calls", color = c.primaryDeep, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tech.csalliance.unstuck.calls.CallSettingsStore.LEAD_OPTIONS.forEach { m ->
            tech.csalliance.unstuck.ui.tasks.SelectableChip("${m}m", selected = cs.defaultLeadMin == m, accent = c.primary) {
                vm.updateCallSettings { it.copy(defaultLeadMin = m) }
            }
        }
    }
    Text(CALLS_LEAD_HINT, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 10.dp))

    // Calls Unstuck can make on its own — ACCOUNT-wide, off by default
    // (notification_preferences.call_*; AppViewModel.setCallProactivePrefs).
    SectionLabel(CALLS_PROACTIVE_SECTION, color = c.primaryDeep, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    SettingsCard {
        ToggleRow(CALLS_PROACTIVE_MORNING, proactive.morningEnabled, sub = CALLS_PROACTIVE_MORNING_SUB, last = !proactive.morningEnabled) { v ->
            vm.setCallProactivePrefs(proactive.copy(morningEnabled = v))
        }
        if (proactive.morningEnabled) {
            SettingRow(CALLS_PROACTIVE_AT, proactive.morningTime, last = true) {
                pickHour(proactive.morningTime) { hm -> vm.setCallProactivePrefs(vm.callProactivePrefs.value.copy(morningTime = hm)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        ToggleRow(CALLS_PROACTIVE_EVENING, proactive.eveningEnabled, sub = CALLS_PROACTIVE_EVENING_SUB, last = !proactive.eveningEnabled) { v ->
            vm.setCallProactivePrefs(proactive.copy(eveningEnabled = v))
        }
        if (proactive.eveningEnabled) {
            SettingRow(CALLS_PROACTIVE_AT, proactive.eveningTime, last = true) {
                pickHour(proactive.eveningTime) { hm -> vm.setCallProactivePrefs(vm.callProactivePrefs.value.copy(eveningTime = hm)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        ToggleRow(CALLS_PROACTIVE_AFTER_BLOCK, proactive.afterBlockEnabled, sub = CALLS_PROACTIVE_AFTER_BLOCK_SUB, last = true) { v ->
            vm.setCallProactivePrefs(proactive.copy(afterBlockEnabled = v))
        }
    }
    Text(CALLS_PROACTIVE_HINT, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 10.dp))

    // Try it
    SectionLabel("Try it", color = c.primaryDeep, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(CALLS_TEST_BODY, style = UFont.sans(13), color = c.ink2)
        val booking = testState == TestCallState.Booking
        UButton(
            if (booking) CALLS_TEST_BOOKING else CALLS_TEST_BUTTON, kind = ButtonKind.PRIMARY, leadingIcon = Icons.Filled.Call,
            enabled = !booking && vm.callsAvailable(),
        ) {
            testState = TestCallState.Booking
            scope.launch { testState = testCallStateFrom(vm.bookTestCall()) }
        }
        when (val t = testState) {
            is TestCallState.Booked -> Text(testCallBookedLine(t.at), style = UFont.sans(12), color = c.green)
            is TestCallState.Failed -> Text(t.why, style = UFont.sans(12), color = c.red)
            else -> {}
        }
    }

    // DND (risk 7): full-screen rings are suppressed under DND unless the channel may interrupt.
    SectionLabel("Do Not Disturb", color = c.primaryDeep, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    SettingsCard {
        SettingRow(CALLS_DND_ROW, CALLS_DND_ROW_SUB, last = true) {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CALLS_CHANNEL_ID)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    Text(CALLS_DND_HINT, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun BackupContent(vm: AppViewModel) {
    val c = UTheme.colors
    val context = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var msgErr by remember { mutableStateOf(false) }
    // Real export — the previous Backup card was inert ("Auto-export every Sunday"
    // toggle + "Export now" both no-ops). There's no scheduled-backup backend, so we
    // surface the one thing that actually works: an on-demand full JSON snapshot.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { (context.contentResolver.openOutputStream(uri) ?: error("no output stream")).use { it.write(vm.exportJson().toByteArray()) } }
            .fold({ msg = "Exported."; msgErr = false }, { msg = "Export failed."; msgErr = true })
    }
    // Guided tour: never reachable mid-run (the lockdown exemption is scoped to
    // the step's own section, and this row stays disabled even so).
    val tourRunning = TourEvents.running
    SettingsCard {
        SettingRow("Export everything", "A full JSON snapshot of your data.", last = true, enabled = !tourRunning) { exporter.launch("unstuck-export.json") }
    }
    Text("Your data is yours — export a complete copy any time.", style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 10.dp))
    msg?.let { Text(it, style = UFont.sans(12), color = if (msgErr) c.red else c.green, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun AccountContent(vm: AppViewModel) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showName by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    // Feedback used to be a tab inside the assistant sheet; it lives here now so
    // it isn't gated behind (or lost with) the AI Assistant. Self-contained —
    // the composer sheet is opened from this row alone.
    var feedbackOpen by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgErr by remember { mutableStateOf(false) }   // render failures in red, not success-green
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching { (context.contentResolver.openOutputStream(uri) ?: error("no output stream")).use { it.write(vm.exportJson().toByteArray()) } }
                .fold({ msg = "Exported."; msgErr = false }, { msg = "Export failed."; msgErr = true })
        }
    }

    // Guided tour lockdown: while a run is up, the account edits and the
    // danger rows (Export / Delete / Sign out) are DISABLED — the tour's
    // settings-step exemption is scoped to the spotlighted section, and even a
    // path into Account (hub → Account, a deep link) must never expose a real
    // destructive action from inside a guided demo. "Product tour" stays live.
    val tourRunning = TourEvents.running
    SettingsCard {
        SettingRow("Display name", vm.currentName ?: "Set a name", enabled = !tourRunning) { showName = true }
        SettingRow("Signed in", vm.currentEmail ?: "—")   // static info — no tap
        SettingRow(if (vm.hasPassword) "Change password" else "Add a password", "Update your sign-in password", enabled = !tourRunning) { showPassword = true }
        // Guided tour re-entry for EVERY account (the auto-offer only arms for
        // accounts that onboard after the tour shipped). TourHost routes this
        // through resumeDecision: a paused/unfinished run offers the resume
        // card at its saved step; a finished (or fresh) tour resets to the
        // welcome card.
        SettingRow("Product tour", "Resume or replay the guided tour") { TourEvents.requestRestart() }
        SettingRow("Send feedback", "Bugs, ideas, anything — straight to the team.") { feedbackOpen = true }
        SettingRow("Export everything", "One-shot JSON snapshot", enabled = !tourRunning) { exporter.launch("unstuck-export.json") }
        SettingRow("Delete my account", "Permanently removes your data", enabled = !tourRunning) { showDelete = true }
        SettingRow("Sign out", "End this session", last = true, enabled = !tourRunning) { vm.signOut() }
    }
    msg?.let { Text(it, style = UFont.sans(12), color = if (msgErr) c.red else c.green, modifier = Modifier.padding(top = 10.dp)) }

    if (feedbackOpen) FeedbackSheet(vm, currentScreen = "settings", onDismiss = { feedbackOpen = false })

    if (showName) FieldDialog("Display name", "Your name", initial = vm.currentName ?: "", onSave = { showName = false; scope.launch { val r = vm.updateDisplayName(it); msgErr = r is AuthOutcome.Error; msg = if (r is AuthOutcome.Error) r.message else "Name updated." } }, onDismiss = { showName = false })
    if (showPassword) PasswordDialog(
        hasPassword = vm.hasPassword,
        onSave = { current, newPw ->
            showPassword = false
            scope.launch {
                if (vm.hasPassword) {
                    val email = vm.currentEmail
                    // Without an email we can't re-auth — say so plainly instead of
                    // signing in with "" and reporting a bogus "password incorrect".
                    if (email.isNullOrBlank()) { msgErr = true; msg = "Can't verify your current password — no email is set on this account."; return@launch }
                    val reauth = vm.signIn(email, current)
                    if (reauth is AuthOutcome.Error) { msgErr = true; msg = "Current password incorrect."; return@launch }
                }
                val r = vm.changePassword(newPw)
                msgErr = r is AuthOutcome.Error; msg = if (r is AuthOutcome.Error) r.message else "Password updated."
            }
        },
        onDismiss = { showPassword = false },
    )
    if (showDelete) {
        var typed by remember { mutableStateOf("") }
        val email = vm.currentEmail ?: ""
        // Fall back to typing DELETE when there's no email — otherwise the confirm button
        // is permanently un-clickable for an email-less account, trapping the user.
        val confirmWord = email.ifBlank { "DELETE" }
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete your account?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This permanently removes everything and cannot be undone. Type ${if (email.isBlank()) "DELETE" else "your email"} to confirm.", style = UFont.sans(13), color = c.ink2)
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text(if (email.isBlank()) "Confirm" else "Email") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = typed.trim().equals(confirmWord, ignoreCase = true), onClick = {
                    showDelete = false; scope.launch { val r = vm.deleteAccount(); if (r is AuthOutcome.Error) { msgErr = true; msg = r.message } }
                }) { Text("Delete forever", color = c.red) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel", color = c.ink2) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun PasswordDialog(hasPassword: Boolean, onSave: (current: String, newPw: String) -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    var current by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val error = when {
        pw.isNotEmpty() && pw.length < 8 -> "At least 8 characters."
        confirm.isNotEmpty() && confirm != pw -> "Passwords don't match."
        else -> null
    }
    val canSave = pw.length >= 8 && pw == confirm && (!hasPassword || current.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPassword) "Change password" else "Add a password", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasPassword) OutlinedTextField(value = current, onValueChange = { current = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = pw, onValueChange = { pw = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, style = UFont.sans(12), color = c.red) }
            }
        },
        confirmButton = { TextButton(enabled = canSave, onClick = { onSave(current, pw) }) { Text("Save", color = c.primaryDeep) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )
}

@Composable
private fun FieldDialog(title: String, label: String, initial: String = "", password: Boolean = false, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Save", color = c.primaryDeep) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )
}

@Composable
private fun AreasContent(vm: AppViewModel) {
    val c = UTheme.colors
    val context = LocalContext.current
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val palette = listOf("indigo", "coral", "green", "amber", "teal", "blue", "violet", "red")
    Text("Areas filter the same list — flat on purpose.", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(bottom = 14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        areas.sortedBy { it.sortOrder }.forEach { a ->
            val open = tasks.count { it.lifeArea == a.name && !it.done && it.recurrence == null }
            var menu by remember(a.id) { mutableStateOf(false) }
            var confirm by remember(a.id) { mutableStateOf(false) }
            var editing by remember(a.id) { mutableStateOf(false) }
            var nameDraft by remember(a.id) { mutableStateOf(a.name) }
            var palOpen by remember(a.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box {
                    Box(
                        Modifier.clickable(role = Role.Button, onClickLabel = "Change color") { palOpen = true }
                            .minimumInteractiveComponentSize()
                            .semantics { contentDescription = "Area color: ${a.color}" },
                        contentAlignment = Alignment.Center,
                    ) { ColorChip(c.areaColor(a.color), box = 30, dot = 9) }
                    DropdownMenu(expanded = palOpen, onDismissRequest = { palOpen = false }) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            palette.forEach { col ->
                                Box(
                                    Modifier.clickable(role = Role.Button) { vm.recolorLifeArea(a, col); palOpen = false }
                                        .minimumInteractiveComponentSize()
                                        .semantics { contentDescription = col; selected = (col == a.color) },
                                    contentAlignment = Alignment.Center,
                                ) { ColorChip(c.areaColor(col), box = 26, dot = 8) }
                            }
                        }
                    }
                }
                if (editing) {
                    BasicTextField(value = nameDraft, onValueChange = { nameDraft = it }, textStyle = UFont.sans(14, FontWeight.SemiBold).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f))
                    Text("✓", style = UFont.sans(16), color = c.green, modifier = Modifier.clickable(role = Role.Button) {
                        val nm = nameDraft.trim()
                        val dup = areas.any { it.id != a.id && it.name.equals(nm, ignoreCase = true) }
                        when {
                            // Blank / unchanged → just close the editor (no-op, no error noise).
                            nm.isEmpty() -> { nameDraft = a.name; editing = false }
                            nm == a.name -> editing = false
                            dup -> android.widget.Toast.makeText(context, "An area named \"$nm\" already exists.", android.widget.Toast.LENGTH_SHORT).show()
                            else -> { vm.renameLifeArea(a, nm); editing = false }
                        }
                    }.minimumInteractiveComponentSize().semantics { contentDescription = "Save name" }.padding(4.dp))
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
                        Text("$open open", style = UFont.sans(11), color = c.ink3)
                    }
                }
                Box {
                    // Wrap the 20dp glyph in a 48dp clickable box so the hit target meets
                    // the minimum without enlarging the drawn icon.
                    Box(
                        Modifier.clickable(role = Role.Button, onClickLabel = "Area options") { menu = true }.minimumInteractiveComponentSize(),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.MoreVert, contentDescription = "Area options", tint = c.ink3, modifier = Modifier.size(20.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename", style = UFont.sans(14), color = c.ink) }, onClick = { menu = false; nameDraft = a.name; editing = true })
                        DropdownMenuItem(text = { Text("Delete area", style = UFont.sans(14), color = c.red) }, onClick = { menu = false; confirm = true })
                    }
                }
            }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Delete \"${a.name}\"?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
                text = { Text("Tasks keep their data — they just lose this area label.", style = UFont.sans(13), color = c.ink2) },
                confirmButton = { TextButton(onClick = { confirm = false; vm.deleteLifeArea(a.id) }) { Text("Delete", color = c.red) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = c.ink2) } },
                containerColor = c.surface,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                BasicTextField(value = draft, onValueChange = { draft = it }, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), decorationBox = { inner -> if (draft.isEmpty()) Text("New area", style = UFont.sans(14), color = c.ink3); inner() })
            }
            UButton("Add", kind = ButtonKind.DARK, fill = false) {
                val name = draft.trim()
                // Skip a duplicate name (areas key tasks by name string → two same-named
                // areas make filtering ambiguous). sortOrder = max+1 and color = first
                // unused both avoid collisions after a delete shrinks `areas.size`.
                if (name.isNotBlank() && areas.none { it.name.equals(name, ignoreCase = true) }) {
                    val color = palette.firstOrNull { col -> areas.none { it.color == col } } ?: palette[areas.size % palette.size]
                    val order = (areas.maxOfOrNull { it.sortOrder } ?: -1) + 1
                    vm.upsertLifeArea(LifeArea(newUuid(), name, color, order)); draft = ""
                }
            }
        }
    }
}

@Composable
private fun TagsContent(vm: AppViewModel) {
    val c = UTheme.colors
    val context = LocalContext.current
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val palette = listOf("indigo", "coral", "green", "amber", "teal", "blue", "violet", "red")
    Text("Tags cut across areas — apply as many as you like.", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(bottom = 14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.sortedBy { it.sortOrder }.forEach { tag ->
            val uses = tasks.count { it.tags?.contains(tag.name) == true }
            var menu by remember(tag.id) { mutableStateOf(false) }
            var confirm by remember(tag.id) { mutableStateOf(false) }
            var editing by remember(tag.id) { mutableStateOf(false) }
            var nameDraft by remember(tag.id) { mutableStateOf(tag.name) }
            var palOpen by remember(tag.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box {
                    Box(
                        Modifier.clickable(role = Role.Button, onClickLabel = "Change color") { palOpen = true }
                            .minimumInteractiveComponentSize()
                            .semantics { contentDescription = "Tag color: ${tag.color}" },
                        contentAlignment = Alignment.Center,
                    ) { ColorChip(c.areaColor(tag.color), box = 26, dot = 8) }
                    DropdownMenu(expanded = palOpen, onDismissRequest = { palOpen = false }) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            palette.forEach { col ->
                                Box(
                                    Modifier.clickable(role = Role.Button) { vm.recolorTag(tag, col); palOpen = false }
                                        .minimumInteractiveComponentSize()
                                        .semantics { contentDescription = col; selected = (col == tag.color) },
                                    contentAlignment = Alignment.Center,
                                ) { ColorChip(c.areaColor(col), box = 26, dot = 8) }
                            }
                        }
                    }
                }
                if (editing) {
                    BasicTextField(value = nameDraft, onValueChange = { nameDraft = it }, textStyle = UFont.sans(14, FontWeight.SemiBold).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f))
                    Text("✓", style = UFont.sans(16), color = c.green, modifier = Modifier.clickable(role = Role.Button) {
                        val nm = nameDraft.trim()
                        val dup = tags.any { it.id != tag.id && it.name.equals(nm, ignoreCase = true) }
                        when {
                            nm.isEmpty() -> { nameDraft = tag.name; editing = false }
                            nm == tag.name -> editing = false
                            dup -> android.widget.Toast.makeText(context, "A tag named \"$nm\" already exists.", android.widget.Toast.LENGTH_SHORT).show()
                            else -> { vm.renameTag(tag, nm); editing = false }
                        }
                    }.minimumInteractiveComponentSize().semantics { contentDescription = "Save name" }.padding(4.dp))
                } else {
                    Text("#${tag.name}", style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f).clickable { nameDraft = tag.name; editing = true })
                }
                Text("$uses", style = UFont.sans(12), color = c.ink3)
                Box {
                    Box(
                        Modifier.clickable(role = Role.Button, onClickLabel = "Tag options") { menu = true }.minimumInteractiveComponentSize(),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.MoreVert, contentDescription = "Tag options", tint = c.ink3, modifier = Modifier.size(20.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename", style = UFont.sans(14), color = c.ink) }, onClick = { menu = false; nameDraft = tag.name; editing = true })
                        DropdownMenuItem(text = { Text("Delete", style = UFont.sans(14), color = c.red) }, onClick = { menu = false; confirm = true })
                    }
                }
            }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Delete #${tag.name}?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
                text = { Text("It's removed from every task that uses it. This can't be undone.", style = UFont.sans(13), color = c.ink2) },
                confirmButton = { TextButton(onClick = { confirm = false; vm.deleteTag(tag.id) }) { Text("Delete", color = c.red) } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = c.ink2) } },
                containerColor = c.surface,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                BasicTextField(value = draft, onValueChange = { draft = it }, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), decorationBox = { inner -> if (draft.isEmpty()) Text("New tag", style = UFont.sans(14), color = c.ink3); inner() })
            }
            UButton("Add", kind = ButtonKind.DARK, fill = false) {
                val nm = draft.trim()
                if (nm.isNotBlank() && tags.none { it.name.equals(nm, ignoreCase = true) }) {
                    // max+1 / first-unused — same anti-collision as areas (tags.size reused
                    // an existing sortOrder/color after a delete).
                    val color = palette.firstOrNull { col -> tags.none { it.color == col } } ?: palette[tags.size % palette.size]
                    val order = (tags.maxOfOrNull { it.sortOrder } ?: -1) + 1
                    vm.upsertTag(TagRow(newUuid(), nm, color, order))
                    draft = ""   // clear only on a real add — a duplicate keeps the text
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) { content() }
}

/** One tappable settings row. [enabled]=false renders it inert — no click
 *  handler at all (not a swallowed one), dimmed, marked disabled for screen
 *  readers, with an honest sub-line saying why (the guided tour is running). */
@Composable
private fun SettingRow(label: String, sub: String?, last: Boolean = false, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val c = UTheme.colors
    val active = enabled && onClick != null
    val shownSub = if (!enabled && onClick != null) TOUR_LOCKED_ROW_SUB else sub
    Row(
        Modifier.fillMaxWidth()
            .then(if (active) Modifier.clickable(onClick = onClick!!) else Modifier)
            .then(if (!enabled) Modifier.semantics { disabled() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = if (enabled) c.ink else c.ink3)
            if (shownSub != null) Text(shownSub, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** The sub-line a tour-locked row shows in place of its own. */
internal const val TOUR_LOCKED_ROW_SUB = "Paused while the guided tour is running"

@Composable
private fun ToggleRow(label: String, value: Boolean, last: Boolean = false, sub: String? = null, onChange: (Boolean) -> Unit) {
    val c = UTheme.colors
    // The whole row is the switch for TalkBack ("<label>, switch, on") — the inner
    // pill is decorative so it doesn't surface as a second nameless toggle.
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            if (sub != null) Text(sub, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 4.dp))
        }
        MdToggle(value, onChange, Modifier.clearAndSetSemantics {})
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

@Composable
private fun SegRow(label: String, options: List<String>, selected: String, last: Boolean = false, onSelect: (String) -> Unit) {
    val c = UTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
        MdSegment(options, selected) { onSelect(it) }
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

private fun accentKey(a: AccentPalette): String = when (a) {
    AccentPalette.INDIGO_CORAL -> "indigo"
    AccentPalette.PERIWINKLE_ROSE -> "rose"
    AccentPalette.FOREST_AMBER -> "forest"
}

private fun accentFromKey(k: String): AccentPalette = when (k) {
    "rose" -> AccentPalette.PERIWINKLE_ROSE
    "forest" -> AccentPalette.FOREST_AMBER
    else -> AccentPalette.INDIGO_CORAL
}
