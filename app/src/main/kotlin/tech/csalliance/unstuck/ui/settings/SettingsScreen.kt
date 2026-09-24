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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.BuildConfig
import tech.csalliance.unstuck.TextSize
import tech.csalliance.unstuck.core.model.ThemePref
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdToggle
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.AuthOutcome
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.ui.assistant.AIConsentHost
import tech.csalliance.unstuck.ui.assistant.AIConsentNoteLine
import tech.csalliance.unstuck.ui.assistant.FactsPanelContent
import tech.csalliance.unstuck.ui.tour.TourEvents

// The slim Settings screens (plan 2026-09-24). The pure half — sections, copy,
// the settings-link parser — is SettingsModel.kt; Notifications & calls is
// NotificationsCallsContent.kt; the Areas & tags sheet is AreasTagsSheet.kt.

// ── The hub ──────────────────────────────────────────────────────────────

/**
 * Account card · the four screens · Send feedback + Replay the tour · the
 * Terms / Privacy / version footer. Every row is a pushed screen (never an
 * inline control): the tour's settings steps rely on their section being the
 * topmost route for the scoped lockdown exemption.
 */
@Composable
fun SettingsHub(vm: AppViewModel, onBack: () -> Unit, onSection: (SettingsSection) -> Unit, onFeedback: () -> Unit) {
    val c = UTheme.colors
    val name by vm.currentNameState.collectAsStateWithLifecycle()
    val email = vm.currentEmail
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = SettingsCopy.HUB_TITLE, leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                SectionLabel(SettingsCopy.HUB_TITLE, color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
                Text(SettingsCopy.HUB_HEADING, style = UFont.serifItalic(28), color = c.ink, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            }
            AccountCard(name = name, email = email) { onSection(SettingsSection.ACCOUNT) }

            SettingsCard {
                SETTINGS_HUB_ROWS.forEachIndexed { i, section ->
                    SettingRow(
                        section.row, sub = section.rowSub, last = i == SETTINGS_HUB_ROWS.lastIndex, chevron = true,
                        modifier = Modifier.testTag(section.testTag),
                    ) { onSection(section) }
                }
            }

            SettingsCard {
                SettingRow(SettingsCopy.SEND_FEEDBACK, SettingsCopy.SEND_FEEDBACK_SUB, modifier = Modifier.testTag(SettingsCopy.FEEDBACK_TAG)) { onFeedback() }
                // Locked while a run is up (the row would restart the tour under itself).
                val running = TourEvents.running
                SettingRow(
                    SettingsCopy.REPLAY_TOUR, if (running) SettingsCopy.REPLAY_TOUR_RUNNING else SettingsCopy.REPLAY_TOUR_SUB,
                    last = true, enabled = !running, lockedSub = SettingsCopy.REPLAY_TOUR_RUNNING,
                    modifier = Modifier.testTag(SettingsCopy.TOUR_TAG),
                ) { TourEvents.requestRestart() }
            }

            SettingsFooter()
            Box(Modifier.height(24.dp))
        }
    }
}

/** The profile card: initials, name, and the email as its subtitle. */
@Composable
private fun AccountCard(name: String?, email: String?, onClick: () -> Unit) {
    val c = UTheme.colors
    val shown = name?.takeIf { it.isNotBlank() }
    val initials = (shown ?: email ?: "U").split(' ', '.', '@').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "U" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .testTag(SettingsSection.ACCOUNT.testTag)
            .clickable(role = Role.Button, onClickLabel = "Open Account", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.bg2), contentAlignment = Alignment.Center) {
            Text(initials, style = UFont.sans(15, FontWeight.SemiBold), color = c.ink2, modifier = Modifier.clearAndSetSemantics {})
        }
        Column(Modifier.weight(1f)) {
            Text(shown ?: SettingsCopy.ACCOUNT_FALLBACK, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(email?.takeIf { it.isNotBlank() } ?: SettingsSection.ACCOUNT.rowSub, style = UFont.sans(12), color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
}

/** Terms · Privacy · Unstuck <version>. Terms and Privacy are real buttons
 *  (48dp hit area, spoken labels) — one tap from the hub, as the stores ask. */
@Composable
private fun SettingsFooter() {
    val c = UTheme.colors
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        FooterLink(SettingsCopy.TERMS, SettingsCopy.TERMS_A11Y, "settings-footer-terms") { open(SettingsCopy.TERMS_URL) }
        Text("·", style = UFont.sans(12), color = c.ink4, modifier = Modifier.clearAndSetSemantics {})
        FooterLink(SettingsCopy.PRIVACY, SettingsCopy.PRIVACY_A11Y, "settings-footer-privacy") { open(SettingsCopy.PRIVACY_URL) }
        Text("·", style = UFont.sans(12), color = c.ink4, modifier = Modifier.clearAndSetSemantics {})
        Text(
            SettingsCopy.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun FooterLink(label: String, a11y: String, tag: String, onClick: () -> Unit) {
    val c = UTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = a11y }
            .testTag(tag)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = UFont.sans(12, FontWeight.Medium), color = c.ink2, modifier = Modifier.clearAndSetSemantics {}) }
}

// ── A pushed screen ─────────────────────────────────────────────────────

@Composable
fun SettingsSubScreen(vm: AppViewModel, section: SettingsSection, onBack: () -> Unit, onSection: (SettingsSection) -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = section.title, leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            SectionLabel("${SettingsCopy.HUB_TITLE} · ${section.title}", color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
            Text(
                section.heading, style = UFont.serifItalic(26), color = c.ink,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).semantics { heading() },
            )
            when (section) {
                SettingsSection.ACCOUNT -> AccountContent(vm)
                SettingsSection.NOTIFICATIONS -> NotificationsCallsContent(vm, onSection)
                SettingsSection.ASSISTANT -> AssistantPrivacyContent(vm, onSection)
                SettingsSection.MEMORY -> {
                    val s by vm.settings.collectAsStateWithLifecycle()
                    FactsPanelContent(vm, canAdd = s.assistantEnabled)
                }
                SettingsSection.PEOPLE -> ConnectionsContent(vm)
                SettingsSection.APPEARANCE -> AppearanceContent(vm)
            }
            Box(Modifier.padding(24.dp)) {}
        }
    }
}

// ── Appearance ──────────────────────────────────────────────────────────

internal fun themeLabel(t: ThemePref): String = when (t) {
    ThemePref.SYSTEM -> "System"
    ThemePref.LIGHT -> "Light"
    ThemePref.DARK -> "Dark"
}

internal fun themeFromLabel(l: String): ThemePref = when (l) {
    "Light" -> ThemePref.LIGHT
    "Dark" -> ThemePref.DARK
    else -> ThemePref.SYSTEM
}

@Composable
private fun AppearanceContent(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsCard {
        SegBlock(SettingsCopy.THEME, SettingsCopy.THEME_OPTIONS, themeLabel(s.theme)) { v ->
            vm.updateSettings { it.copy(theme = themeFromLabel(v)) }
        }
        SegBlock(SettingsCopy.TEXT_SIZE, TextSize.entries.map { it.label }, s.textSize.label, sub = SettingsCopy.TEXT_SIZE_SUB, last = true) { v ->
            vm.updateSettings { it.copy(textSize = TextSize.fromLabel(v)) }
        }
    }
    SettingsNote(SettingsCopy.APPEARANCE_NOTE)
}

// ── Assistant & privacy ─────────────────────────────────────────────────

/** The whole screen stays when the AI is off (plan: only "Add a fact" hides):
 *  what it remembers and the stored history are the user's to see and delete
 *  either way. */
@Composable
private fun AssistantPrivacyContent(vm: AppViewModel, onSection: (SettingsSection) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val consent by vm.aiConsent.collectAsStateWithLifecycle()
    val sharing = vm.aiConsentGranted(consent)
    SettingsCard {
        // The kill-switch the privacy policy promises ("Assistant & privacy →
        // AI Assistant. Turn it off entirely"): off unmounts the launcher, so
        // nothing reaches the AI provider; calls are declined with it.
        ToggleRow(SettingsCopy.AI_ASSISTANT, s.assistantEnabled, sub = SettingsCopy.AI_ASSISTANT_SUB, modifier = Modifier.testTag("settings-ai-assistant")) { v ->
            vm.updateSettings { it.copy(assistantEnabled = v) }
        }
        // "AI data sharing" (core AIConsent; the policy's "Assistant & privacy →
        // AI data sharing"): whether the account has agreed to its words and
        // voice going to OpenAI. Off clears the OK on every device and turns
        // Calls off; on shows the consent sheet. Locked during the tour.
        ToggleRow(
            SettingsCopy.AI_DATA_SHARING, sharing,
            sub = if (sharing) SettingsCopy.AI_DATA_SHARING_ON else SettingsCopy.AI_DATA_SHARING_OFF,
            enabled = !TourEvents.running,
            modifier = Modifier.testTag(SettingsCopy.AI_DATA_SHARING_TAG),
        ) { want ->
            if (want) vm.withAIConsent(AIConsent.Action.SETTINGS, AIConsentHost.SETTINGS) {} else vm.revokeAIConsent()
        }
        AIConsentNoteLine(vm, AIConsentHost.SETTINGS, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        SettingRow(SettingsSection.MEMORY.row, SettingsSection.MEMORY.rowSub, chevron = true, modifier = Modifier.testTag(SettingsSection.MEMORY.testTag)) {
            onSection(SettingsSection.MEMORY)
        }
        ClearAssistantHistoryRow(vm)
    }
}

// ── Delete conversation history (the privacy policy's §9.5 / §17 control) ──

internal const val CLEAR_HISTORY_ROW = SettingsCopy.DELETE_HISTORY
internal const val CLEAR_HISTORY_IDLE = "What you've said to it is kept 90 days. Delete it now."
internal const val CLEAR_HISTORY_CLEARING = "Deleting…"
internal const val CLEAR_HISTORY_FAILED = "Couldn't delete it. Try again."

/** The row's sub-line: idle, deleting, or what the last delete did ([result]
 *  = rows deleted, or the failure). */
internal fun clearHistoryLine(clearing: Boolean, result: Result<Int>?): String = when {
    clearing -> CLEAR_HISTORY_CLEARING
    result == null -> CLEAR_HISTORY_IDLE
    else -> result.fold(
        onSuccess = { n -> if (n == 0) "Nothing was stored." else "Deleted $n stored line${if (n == 1) "" else "s"}." },
        onFailure = { CLEAR_HISTORY_FAILED },
    )
}

/** Conversations are kept 90 days; this deletes them now. Turning the
 *  Assistant off only stops FUTURE logging, so the row shows whether the AI is
 *  on or off; locked while the guided tour runs, like the other server-writing
 *  rows. */
@Composable
private fun ClearAssistantHistoryRow(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<Int>?>(null) }
    SettingRow(CLEAR_HISTORY_ROW, clearHistoryLine(clearing, result), last = true, enabled = !TourEvents.running, modifier = Modifier.testTag("settings-delete-history")) {
        if (clearing) return@SettingRow
        clearing = true
        result = null
        scope.launch {
            result = vm.clearAssistantHistory()
            clearing = false
        }
    }
}

// ── Account ─────────────────────────────────────────────────────────────

/** "Export everything" into the document at [uri]. The ViewModel reads and writes it
 *  off the main thread ([AppViewModel.exportTo]); the outcome goes to [show] and to a
 *  toast, since returning from the picker has usually closed this screen (the ON_STOP
 *  reset) and a message only here would never be seen (Android audit 2026-09-23, A18). */
private fun startExport(vm: AppViewModel, context: android.content.Context, uri: android.net.Uri, show: (String, Boolean) -> Unit) {
    val app = context.applicationContext
    vm.exportTo(uri) { message, failed ->
        show(message, failed)
        android.widget.Toast.makeText(app, message, if (failed) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AccountContent(vm: AppViewModel) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val name by vm.currentNameState.collectAsStateWithLifecycle()
    var showName by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgErr by remember { mutableStateOf(false) }   // render failures in red, not success-green
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) startExport(vm, context, uri) { m, failed -> msg = m; msgErr = failed }
    }

    // Guided tour lockdown: while a run is up, the account edits and the
    // danger rows (Export / Delete / Sign out) are DISABLED — the tour's
    // settings-step exemption is scoped to the spotlighted section, and even a
    // path into Account (hub → Account, a deep link) must never expose a real
    // destructive action from inside a guided demo.
    val tourRunning = TourEvents.running
    SettingsCard {
        SettingRow(SettingsCopy.DISPLAY_NAME, name?.takeIf { it.isNotBlank() } ?: SettingsCopy.NAME_UNSET, enabled = !tourRunning) { showName = true }
        SettingRow(
            if (vm.hasPassword) SettingsCopy.CHANGE_PASSWORD else SettingsCopy.ADD_PASSWORD,
            if (vm.hasPassword) SettingsCopy.CHANGE_PASSWORD_SUB else SettingsCopy.ADD_PASSWORD_SUB,
            enabled = !tourRunning,
        ) { showPassword = true }
        SettingRow(SettingsCopy.EXPORT, SettingsCopy.EXPORT_SUB, enabled = !tourRunning, modifier = Modifier.testTag("settings-export")) { exporter.launch("unstuck-export.json") }
        SettingRow(SettingsCopy.SIGN_OUT, SettingsCopy.SIGN_OUT_SUB, enabled = !tourRunning, modifier = Modifier.testTag("settings-sign-out")) { vm.signOut() }
        SettingRow(
            SettingsCopy.DELETE_ACCOUNT, SettingsCopy.DELETE_ACCOUNT_SUB, last = true, enabled = !tourRunning, danger = true,
            modifier = Modifier.testTag("settings-delete-account"),
        ) { showDelete = true }
    }
    msg?.let { Text(it, style = UFont.sans(12), color = if (msgErr) c.red else c.green, modifier = Modifier.padding(top = 10.dp)) }

    if (showName) FieldDialog(SettingsCopy.DISPLAY_NAME, "Your name", initial = name ?: "", onSave = { showName = false; scope.launch { val r = vm.updateDisplayName(it); msgErr = r is AuthOutcome.Error; msg = if (r is AuthOutcome.Error) r.message else "Name updated." } }, onDismiss = { showName = false })
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
                TextButton(enabled = deleteAccountConfirmed(typed, email), onClick = {
                    showDelete = false; scope.launch { val r = vm.deleteAccount(); if (r is AuthOutcome.Error) { msgErr = true; msg = r.message } }
                }) { Text("Delete forever", color = c.red) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel", color = c.ink2) } },
            containerColor = c.surface,
        )
    }
}

/** Delete my account's confirm: the account's email typed back (any case,
 *  stray spaces ignored) — or DELETE when the account has no email, so an
 *  email-less account is never trapped behind a button it can't enable. */
internal fun deleteAccountConfirmed(typed: String, email: String?): Boolean =
    typed.trim().equals(email?.takeIf { it.isNotBlank() } ?: "DELETE", ignoreCase = true)

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
        title = { Text(if (hasPassword) SettingsCopy.CHANGE_PASSWORD else SettingsCopy.ADD_PASSWORD, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
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
private fun FieldDialog(title: String, label: String, initial: String = "", onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Save", color = c.primaryDeep) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )
}

// ── Shared rows ─────────────────────────────────────────────────────────

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = UTheme.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) { content() }
}

@Composable
internal fun CardDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(UTheme.colors.line))
}

/** The sub-line a tour-locked row shows in place of its own. */
internal const val TOUR_LOCKED_ROW_SUB = "Paused while the guided tour is running"

/** One tappable settings row. [enabled]=false renders it inert — no click
 *  handler at all (not a swallowed one), dimmed, marked disabled for screen
 *  readers, with an honest sub-line saying why ([lockedSub]; the guided tour
 *  by default). [danger] paints the label red (Delete my account). */
@Composable
internal fun SettingRow(
    label: String,
    sub: String?,
    last: Boolean = false,
    enabled: Boolean = true,
    chevron: Boolean = false,
    danger: Boolean = false,
    lockedSub: String = TOUR_LOCKED_ROW_SUB,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = UTheme.colors
    val active = enabled && onClick != null
    val shownSub = if (!enabled && onClick != null) lockedSub else sub
    Row(
        modifier.fillMaxWidth()
            .then(if (active) Modifier.clickable(role = Role.Button, onClick = onClick!!) else Modifier)
            .then(if (!enabled) Modifier.semantics { disabled() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = UFont.sans(14, FontWeight.Medium), color = when { !enabled -> c.ink3; danger -> c.red; else -> c.ink })
            if (shownSub != null) Text(shownSub, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 3.dp))
        }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
    if (!last) CardDivider()
}

@Composable
internal fun ToggleRow(
    label: String,
    value: Boolean,
    last: Boolean = false,
    sub: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    val c = UTheme.colors
    // The whole row is the switch for TalkBack ("<label>, switch, on") — the inner
    // pill is decorative so it doesn't surface as a second nameless toggle.
    Row(
        modifier.fillMaxWidth()
            .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = UFont.sans(14, FontWeight.Medium), color = if (enabled) c.ink else c.ink3)
            if (sub != null) Text(sub, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 3.dp))
        }
        MdToggle(value, { if (enabled) onChange(it) }, Modifier.clearAndSetSemantics {})
    }
    if (!last) CardDivider()
}

/** A label (and optional plain line) over a full-width segmented picker —
 *  stacked, so three options never squeeze the label on a narrow phone. */
@Composable
internal fun SegBlock(label: String, options: List<String>, selected: String, sub: String? = null, last: Boolean = false, onSelect: (String) -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = UFont.sans(14, FontWeight.Medium), color = c.ink)
        EvenSegment(options, selected, label, onSelect)
        if (sub != null) Text(sub, style = UFont.sans(12), color = c.ink3)
    }
    if (!last) CardDivider()
}

/** The design system's segmented look (bg2 track, ink/bg selection) with the
 *  options sharing the width evenly. */
@Composable
internal fun EvenSegment(options: List<String>, selected: String, groupLabel: String, onSelect: (String) -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bg2).selectableGroup().padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { opt ->
            val active = opt == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (active) c.ink else Color.Transparent)
                    .selectable(selected = active, role = Role.RadioButton) { onSelect(opt) }
                    .semantics { contentDescription = "$groupLabel: $opt" }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(opt, style = UFont.sans(13, FontWeight.SemiBold), color = if (active) c.bg else c.ink2, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/** A small plain line under a card. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = UFont.sans(12), color = UTheme.colors.ink3, modifier = modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp))
}

/** A section label over a card (N&C's "Reminders" / "Calls"). */
@Composable
internal fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    SectionLabel(text, color = UTheme.colors.primaryDeep, modifier = modifier.padding(top = 4.dp, bottom = 8.dp).semantics { heading() })
}

/** A plain status line with a fix (only shown when something is wrong). */
@Composable
internal fun FixItLine(text: String, fix: String, tint: Color = UTheme.colors.amberInk, modifier: Modifier = Modifier, onFix: () -> Unit) {
    val c = UTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = UFont.sans(12), color = tint, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = fix, onClick = onFix)
                .minimumInteractiveComponentSize()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(fix, style = UFont.sans(12, FontWeight.SemiBold), color = c.ink) }
    }
}
