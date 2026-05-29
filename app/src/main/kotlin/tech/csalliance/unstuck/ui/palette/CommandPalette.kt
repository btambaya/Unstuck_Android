package tech.csalliance.unstuck.ui.palette

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.AreaDot
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// Command palette: fuzzy-ish search over quick actions + open tasks. Keyboard-
// first quick-switch, the web/iOS palette analog.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPalette(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onOpenTask: (TaskItem) -> Unit,
    onNewTask: () -> Unit,
    onTab: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()

    data class Action(val label: String, val run: () -> Unit)
    val actions = listOf(
        Action("New task") { onNewTask() },
        Action("Go to Today") { onTab(0) },
        Action("Go to Tasks") { onTab(1) },
        Action("Go to Calendar") { onTab(2) },
        Action("Go to Lists") { onTab(3) },
        Action("Settings") { onSettings() },
    ).filter { q.isEmpty() || it.label.lowercase().contains(q) }

    val matchingTasks = tasks
        .filter { !it.done && (q.isEmpty() || it.name.lowercase().contains(q)) }
        .take(8)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search tasks + actions") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                if (actions.isNotEmpty()) {
                    item { SectionLabel("Actions", Modifier.padding(vertical = 8.dp)) }
                    items(actions) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { a.run(); onDismiss() }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(a.label, style = UFont.sans(15, FontWeight.Medium), color = c.ink)
                            Icon(Icons.Outlined.ArrowForward, contentDescription = null, tint = c.ink3)
                        }
                    }
                }
                if (matchingTasks.isNotEmpty()) {
                    item { SectionLabel("Tasks", Modifier.padding(vertical = 8.dp)) }
                    items(matchingTasks, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenTask(t); onDismiss() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (t.lifeArea != null) AreaDot(t.lifeArea)
                            Text(t.name, style = UFont.sans(15), color = c.ink, modifier = Modifier.weight(1f))
                            Text("${t.estimateMin}m", style = UFont.mono(11), color = c.ink3)
                        }
                    }
                }
            }
        }
    }
}
