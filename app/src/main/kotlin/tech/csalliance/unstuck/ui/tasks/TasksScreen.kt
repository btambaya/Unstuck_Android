package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.areaColorFor

@Composable
fun TasksScreen(vm: AppViewModel, onOpen: (TaskItem) -> Unit, onSearch: () -> Unit, onMenu: () -> Unit) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf(TaskListView.TODAY) }
    val list = visibleTasks(view, tasks, blocks, vm.nowMs(), activeArea = null, slipMode = false)

    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Tasks", leading = Leading.MENU, onLeading = onMenu, onSearch = onSearch)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            item { Text("Your tasks", style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskListView.entries.forEach { v ->
                        val active = v == view
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (active) c.ink else c.bg2).clickable { view = v }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text(v.label, style = UFont.sans(12, FontWeight.Medium), color = if (active) c.bg else c.ink2)
                        }
                    }
                }
            }
            if (list.isEmpty()) {
                item { Text("No ${view.label.lowercase()} tasks.", style = UFont.sans(14), color = c.ink3, modifier = Modifier.padding(vertical = 32.dp)) }
            } else {
                items(list, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable { onOpen(t) }.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = UFont.sans(14, FontWeight.Medium), color = if (t.done) c.ink3 else c.ink, maxLines = 1, textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                AreaDotColor(areaColorFor(t.lifeArea, areas, c), size = 5)
                                Text(t.lifeArea ?: "—", style = UFont.sans(12), color = c.ink3)
                            }
                        }
                        Text("${t.estimateMin}m", style = UFont.mono(11), color = c.ink3)
                    }
                }
                item { Text("", Modifier.padding(28.dp)) }
            }
        }
    }
}
