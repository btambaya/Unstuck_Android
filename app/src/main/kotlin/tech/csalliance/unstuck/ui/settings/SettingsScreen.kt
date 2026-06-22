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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

enum class SettingsSection(val title: String, val eyebrow: String) {
    ACCOUNT("Your account.", "SETTINGS · ACCOUNT"),
    FOCUS("How focus mode behaves.", "SETTINGS · FOCUS"),
    SOUND("Quiet by default.", "SETTINGS · SOUND"),
    A11Y("Adjust to your brain.", "SETTINGS · ACCESSIBILITY"),
    INTERFACE("How things look.", "SETTINGS · INTERFACE"),
    BACKUP("Your data is yours.", "SETTINGS · BACKUP"),
    AREAS("One list. The whole life.", "SETTINGS · AREAS"),
    TAGS("Your tag vocabulary.", "SETTINGS · TAGS"),
}

private val HUB = listOf(
    "Account" to SettingsSection.ACCOUNT, "Focus" to SettingsSection.FOCUS, "Sound" to SettingsSection.SOUND,
    "Accessibility" to SettingsSection.A11Y, "Interface" to SettingsSection.INTERFACE, "Backup" to SettingsSection.BACKUP,
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
    SettingsCard {
        SettingRow("Export everything", "A full JSON snapshot of your data.", last = true) { exporter.launch("unstuck-export.json") }
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
    var msg by remember { mutableStateOf<String?>(null) }
    var msgErr by remember { mutableStateOf(false) }   // render failures in red, not success-green
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching { (context.contentResolver.openOutputStream(uri) ?: error("no output stream")).use { it.write(vm.exportJson().toByteArray()) } }
                .fold({ msg = "Exported."; msgErr = false }, { msg = "Export failed."; msgErr = true })
        }
    }

    SettingsCard {
        SettingRow("Display name", vm.currentName ?: "Set a name") { showName = true }
        SettingRow("Signed in", vm.currentEmail ?: "—")   // static info — no tap
        SettingRow(if (vm.hasPassword) "Change password" else "Add a password", "Update your sign-in password") { showPassword = true }
        SettingRow("Export everything", "One-shot JSON snapshot") { exporter.launch("unstuck-export.json") }
        SettingRow("Delete my account", "Permanently removes your data") { showDelete = true }
        SettingRow("Sign out", "End this session", last = true) { vm.signOut() }
    }
    msg?.let { Text(it, style = UFont.sans(12), color = if (msgErr) c.red else c.green, modifier = Modifier.padding(top = 10.dp)) }

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

@Composable
private fun SettingRow(label: String, sub: String?, last: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = UTheme.colors
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
            if (sub != null) Text(sub, style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

@Composable
private fun ToggleRow(label: String, value: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = UTheme.colors
    // The whole row is the switch for TalkBack ("<label>, switch, on") — the inner
    // pill is decorative so it doesn't surface as a second nameless toggle.
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
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
