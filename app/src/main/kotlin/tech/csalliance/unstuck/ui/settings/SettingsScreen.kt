package tech.csalliance.unstuck.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.design.component.AppBar
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
}

private val HUB = listOf(
    "Account" to SettingsSection.ACCOUNT, "Focus" to SettingsSection.FOCUS, "Sound" to SettingsSection.SOUND,
    "Accessibility" to SettingsSection.A11Y, "Interface" to SettingsSection.INTERFACE, "Backup" to SettingsSection.BACKUP,
    "Life areas" to SettingsSection.AREAS,
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
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = section.name.lowercase().replaceFirstChar { it.uppercase() }, leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            SectionLabel(section.eyebrow, color = c.primaryDeep, modifier = Modifier.padding(top = 4.dp))
            Text(section.title, style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            when (section) {
                SettingsSection.AREAS -> AreasContent(vm)
                SettingsSection.ACCOUNT -> AccountContent(vm)
                SettingsSection.FOCUS -> SettingsCard {
                    SegRow("Default focus length", listOf("15", "25", "45"), "25")
                    SegRow("Soft overrun", listOf("Off", "5", "10"), "5")
                    ToggleRow("Hide right rail while focusing", true)
                    ToggleRow("Soft exit", true)
                    ToggleRow("Pause reasons", false, last = true)
                }
                SettingsSection.SOUND -> SettingsCard {
                    ToggleRow("Start chime", true)
                    ToggleRow("Overrun bell", true)
                    ToggleRow("Completion sound", false)
                    SegRow("Ambient", listOf("off", "brown", "pink"), "off", last = true)
                }
                SettingsSection.A11Y -> SettingsCard {
                    ToggleRow("Reduce motion", false)
                    ToggleRow("Larger type", false)
                    ToggleRow("High contrast", false)
                    ToggleRow("Keyboard hints", true, last = true)
                }
                SettingsSection.INTERFACE -> SettingsCard {
                    SegRow("Theme", listOf("system", "light", "dark"), "system")
                    SegRow("Density", listOf("compact", "regular", "comfy"), "regular", last = true)
                }
                SettingsSection.BACKUP -> SettingsCard {
                    ToggleRow("Auto-export every Sunday", true)
                    SettingRow("Export now", "One-shot JSON.", last = true) {}
                }
            }
            Box(Modifier.padding(24.dp)) {}
        }
    }
}

@Composable
private fun AccountContent(vm: AppViewModel) {
    SettingsCard {
        SettingRow("Signed in", vm.currentEmail ?: vm.currentName ?: "—") {}
        SettingRow("Export everything", "JSON bundle") {}
        SettingRow("Sign out", "End this session", last = true) { vm.signOut() }
    }
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
private fun ToggleRow(label: String, initial: Boolean, last: Boolean = false) {
    val c = UTheme.colors
    var on by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
        MdToggle(on, { on = it })
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

@Composable
private fun SegRow(label: String, options: List<String>, initial: String, last: Boolean = false) {
    val c = UTheme.colors
    var sel by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
        MdSegment(options, sel) { sel = it }
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}
