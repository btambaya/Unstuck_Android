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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
    AREAS("One list. The whole life.", "SETTINGS · LIFE AREAS"),
    TAGS("Your tag vocabulary.", "SETTINGS · TAGS"),
}

private val HUB = listOf(
    "Account" to SettingsSection.ACCOUNT, "Focus" to SettingsSection.FOCUS, "Sound" to SettingsSection.SOUND,
    "Accessibility" to SettingsSection.A11Y, "Interface" to SettingsSection.INTERFACE, "Backup" to SettingsSection.BACKUP,
    "Life areas" to SettingsSection.AREAS, "Tags" to SettingsSection.TAGS,
)

@Composable
fun SettingsHub(vm: AppViewModel, onBack: () -> Unit, onSection: (SettingsSection) -> Unit, onInsights: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = "Settings", leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
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
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = section.name.lowercase().replaceFirstChar { it.uppercase() }, leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
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
                    ToggleRow("Hide right rail while focusing", s.focusCollapseRail) { v -> vm.updateSettings { it.copy(focusCollapseRail = v) } }
                    ToggleRow("Soft exit", s.focusSoftExit) { v -> vm.updateSettings { it.copy(focusSoftExit = v) } }
                    ToggleRow("Pause reasons", s.focusPauseReasons, last = true) { v -> vm.updateSettings { it.copy(focusPauseReasons = v) } }
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
                SettingsSection.BACKUP -> SettingsCard {
                    ToggleRow("Auto-export every Sunday", true) {}
                    SettingRow("Export now", "One-shot JSON.", last = true) {}
                }
            }
            Box(Modifier.padding(24.dp)) {}
        }
    }
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
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportJson().toByteArray()) } }
                .fold({ msg = "Exported." }, { msg = "Export failed." })
        }
    }

    SettingsCard {
        SettingRow("Display name", vm.currentName ?: "Set a name") { showName = true }
        SettingRow("Signed in", vm.currentEmail ?: "—") {}
        SettingRow(if (vm.hasPassword) "Change password" else "Add a password", "Update your sign-in password") { showPassword = true }
        SettingRow("Export everything", "One-shot JSON snapshot") { exporter.launch("unstuck-export.json") }
        SettingRow("Delete my account", "Permanently removes your data") { showDelete = true }
        SettingRow("Sign out", "End this session", last = true) { vm.signOut() }
    }
    msg?.let { Text(it, style = UFont.sans(12), color = c.green, modifier = Modifier.padding(top = 10.dp)) }

    if (showName) FieldDialog("Display name", "Your name", initial = vm.currentName ?: "", onSave = { scope.launch { vm.updateDisplayName(it) }; showName = false }, onDismiss = { showName = false })
    if (showPassword) FieldDialog(if (vm.hasPassword) "Change password" else "Add a password", "New password", password = true, onSave = { scope.launch { vm.changePassword(it) }; showPassword = false }, onDismiss = { showPassword = false })
    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("Delete your account?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { Text("This permanently removes your tasks, sessions and everything else. This cannot be undone.", style = UFont.sans(13), color = c.ink2) },
        confirmButton = { TextButton(onClick = { showDelete = false; scope.launch { vm.deleteAccount() } }) { Text("Delete forever", color = c.red) } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel", color = c.ink2) } },
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
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val palette = listOf("indigo", "coral", "violet", "green", "amber", "blue")
    Text("Areas filter the same list — flat on purpose.", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(bottom = 14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        areas.sortedBy { it.sortOrder }.forEach { a ->
            val open = tasks.count { it.lifeArea == a.name && !it.done }
            var menu by remember(a.id) { mutableStateOf(false) }
            var confirm by remember(a.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                ColorChip(c.areaColor(a.color), box = 30, dot = 9)
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
                    Text("Custom area.", style = UFont.sans(11), color = c.ink3)
                }
                Text("$open open", style = UFont.sans(12), color = c.ink2)
                Box {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Area options", tint = c.ink3, modifier = Modifier.size(20.dp).clickable { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                if (draft.isNotBlank()) { vm.upsertLifeArea(LifeArea(newUuid(), draft.trim(), palette[areas.size % palette.size], areas.size)); draft = "" }
            }
        }
    }
}

@Composable
private fun TagsContent(vm: AppViewModel) {
    val c = UTheme.colors
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val palette = listOf("indigo", "coral", "violet", "green", "amber", "blue")
    Text("Tags cut across areas — apply as many as you like.", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(bottom = 14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.sortedBy { it.sortOrder }.forEach { tag ->
            val uses = tasks.count { it.tags?.contains(tag.name) == true }
            var menu by remember(tag.id) { mutableStateOf(false) }
            var editing by remember(tag.id) { mutableStateOf(false) }
            var nameDraft by remember(tag.id) { mutableStateOf(tag.name) }
            var palOpen by remember(tag.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box {
                    Box(Modifier.clickable { palOpen = true }) { ColorChip(c.areaColor(tag.color), box = 26, dot = 8) }
                    DropdownMenu(expanded = palOpen, onDismissRequest = { palOpen = false }) {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            palette.forEach { col -> Box(Modifier.clickable { vm.recolorTag(tag, col); palOpen = false }) { ColorChip(c.areaColor(col), box = 26, dot = 8) } }
                        }
                    }
                }
                if (editing) {
                    BasicTextField(value = nameDraft, onValueChange = { nameDraft = it }, textStyle = UFont.sans(14, FontWeight.SemiBold).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f))
                    Text("✓", style = UFont.sans(16), color = c.green, modifier = Modifier.clickable { vm.renameTag(tag, nameDraft); editing = false }.padding(4.dp))
                } else {
                    Text("#${tag.name}", style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f).clickable { nameDraft = tag.name; editing = true })
                }
                Text("$uses", style = UFont.sans(12), color = c.ink3)
                Box {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Tag options", tint = c.ink3, modifier = Modifier.size(20.dp).clickable { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename", style = UFont.sans(14), color = c.ink) }, onClick = { menu = false; nameDraft = tag.name; editing = true })
                        DropdownMenuItem(text = { Text("Delete", style = UFont.sans(14), color = c.red) }, onClick = { menu = false; vm.deleteTag(tag.id) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                BasicTextField(value = draft, onValueChange = { draft = it }, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), decorationBox = { inner -> if (draft.isEmpty()) Text("New tag", style = UFont.sans(14), color = c.ink3); inner() })
            }
            UButton("Add", kind = ButtonKind.DARK, fill = false) {
                if (draft.isNotBlank()) { vm.upsertTag(TagRow(newUuid(), draft.trim(), palette[tags.size % palette.size], tags.size)); draft = "" }
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
private fun SettingRow(label: String, sub: String?, last: Boolean = false, onClick: () -> Unit) {
    val c = UTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
        MdToggle(value, onChange)
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
