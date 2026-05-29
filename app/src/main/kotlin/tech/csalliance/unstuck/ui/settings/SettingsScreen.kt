package tech.csalliance.unstuck.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.design.component.AreaDot
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.insights.InsightsScreen

private val AREA_COLORS = listOf("indigo", "coral", "violet", "blue", "green", "amber", "red")

@Composable
fun SettingsScreen(vm: AppViewModel, onClose: () -> Unit) {
    val c = UTheme.colors
    var showInsights by remember { mutableStateOf(false) }
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    var newArea by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf("") }

    if (showInsights) {
        InsightsScreen(vm, onClose = { showInsights = false })
        return
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = c.ink) }
                Text("Settings", style = UFont.serifItalic(24), color = c.ink)
            }

            Section("Insights") {
                Text("Focus patterns, calibration, slipping tasks.", style = UFont.sans(13), color = c.ink2)
                UButton("Open insights", kind = ButtonKind.GHOST) { showInsights = true }
            }

            Section("Life areas") {
                areas.sortedBy { it.sortOrder }.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AreaDot(a.color)
                        Text(a.name, style = UFont.sans(14), color = c.ink, modifier = Modifier.weight(1f))
                        Text("Remove", style = UFont.sans(12), color = c.coralDeep, modifier = Modifier.clickable { vm.deleteLifeArea(a.id) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newArea, onValueChange = { newArea = it }, label = { Text("New area") }, singleLine = true, modifier = Modifier.weight(1f))
                    Text("Add", style = UFont.sans(14), color = c.primary, modifier = Modifier.clickable {
                        if (newArea.isNotBlank()) {
                            val color = AREA_COLORS[areas.size % AREA_COLORS.size]
                            vm.upsertLifeArea(LifeArea(id = newUuid(), name = newArea.trim(), color = color, sortOrder = areas.size))
                            newArea = ""
                        }
                    }.padding(8.dp))
                }
            }

            Section("Tags") {
                tags.sortedBy { it.sortOrder }.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${t.name}", style = UFont.sans(14), color = c.ink, modifier = Modifier.weight(1f))
                        Text("Remove", style = UFont.sans(12), color = c.coralDeep, modifier = Modifier.clickable { vm.deleteTag(t.id) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newTag, onValueChange = { newTag = it }, label = { Text("New tag") }, singleLine = true, modifier = Modifier.weight(1f))
                    Text("Add", style = UFont.sans(14), color = c.primary, modifier = Modifier.clickable {
                        if (newTag.isNotBlank()) {
                            vm.upsertTag(TagRow(id = newUuid(), name = newTag.trim().removePrefix("#"), color = null, sortOrder = tags.size))
                            newTag = ""
                        }
                    }.padding(8.dp))
                }
            }

            Section("Sync") {
                Text(if (pending == 0) "All changes synced." else "$pending change(s) pending upload.", style = UFont.sans(13), color = c.ink2)
            }

            Section("Account") {
                UButton("Sign out", kind = ButtonKind.DANGER) { vm.signOut() }
            }
            Text("", Modifier.padding(28.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        content()
    }
}
