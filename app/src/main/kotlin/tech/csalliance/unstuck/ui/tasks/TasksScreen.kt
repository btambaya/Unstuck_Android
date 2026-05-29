package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.EmptyState
import tech.csalliance.unstuck.ui.components.ScreenHeader
import tech.csalliance.unstuck.ui.components.TaskRow

@Composable
fun TasksScreen(vm: AppViewModel, onStartFocus: (TaskItem) -> Unit, onOpen: (TaskItem) -> Unit) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf(TaskListView.ALL) }
    var area by remember { mutableStateOf<String?>(null) }
    var slip by remember { mutableStateOf(false) }

    val list = visibleTasks(view, tasks, blocks, vm.nowMs(), area, slipMode = slip)

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader("Tasks", subtitle = "${list.size} ${view.label.lowercase()}")

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskListView.entries.forEach { v ->
                SelectableChip(v.label, selected = v == view) { view = v }
            }
        }

        if (areas.isNotEmpty() || slip) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectableChip("Slipping", selected = slip, accent = c.amber) { slip = !slip }
                SelectableChip("All areas", selected = area == null) { area = null }
                areas.forEach { a -> SelectableChip(a.name, selected = area == a.name) { area = if (area == a.name) null else a.name } }
            }
        }

        if (list.isEmpty()) {
            EmptyState("No ${view.label.lowercase()} tasks.")
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                items(list, key = { it.id }) { t ->
                    TaskRow(
                        task = t, onToggleDone = { vm.toggleDone(t) }, onOpen = { onOpen(t) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        trailing = {
                            if (!t.done) {
                                Text(
                                    "Start",
                                    style = UFont.sans(13, FontWeight.Medium),
                                    color = c.coralDeep,
                                    modifier = Modifier.clip(CircleShape).clickable { onStartFocus(t) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        },
                    )
                }
                item { Text("", Modifier.padding(28.dp)) }
            }
        }
    }
}

@Composable
fun SelectableChip(label: String, selected: Boolean, accent: Color? = null, onClick: () -> Unit) {
    val c = UTheme.colors
    val bg = if (selected) (accent ?: c.primary) else c.bg2
    Text(
        label,
        modifier = Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = UFont.sans(13, FontWeight.Medium),
        color = if (selected) Color.White else c.ink2,
    )
}
