package tech.csalliance.unstuck.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.pickStartNext
import tech.csalliance.unstuck.core.logic.pickUpNext
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.EmptyState
import tech.csalliance.unstuck.ui.components.ScreenHeader
import tech.csalliance.unstuck.ui.components.TaskRow

@Composable
fun TodayScreen(vm: AppViewModel, onStartFocus: (TaskItem) -> Unit, onOpen: (TaskItem) -> Unit, onSettings: () -> Unit) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val live by vm.liveSession.collectAsStateWithLifecycle()
    val now = vm.nowMs()
    val liveId = live?.taskId

    val startNext = pickStartNext(tasks, blocks, liveId)
    val upNext = pickUpNext(tasks, blocks, liveId, startNext?.id, limit = 4)
    val todayTasks = visibleTasks(TaskListView.TODAY, tasks, blocks, now, activeArea = null, slipMode = false)
    val liveTask = liveId?.let { id -> tasks.firstOrNull { it.id == id } }

    // Keep the Glance Start-Next widget in sync with the recommendation.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(startNext?.id, startNext?.name) {
        tech.csalliance.unstuck.surface.writeStartNext(context, startNext?.name, startNext?.estimateMin)
        runCatching { tech.csalliance.unstuck.surface.StartNextWidget().updateAll(context) }
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            ScreenHeader("Today", subtitle = todaySubtitle(todayTasks.size)) {
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = c.ink3) }
            }
        }

        if (liveTask != null) {
            item {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
                    SectionLabel("In progress")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp))
                            .background(c.primarySoft).clickable { onStartFocus(liveTask) }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(liveTask.name, style = UFont.sans(15, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                        Text("Resume", style = UFont.sans(13, FontWeight.Medium), color = c.primaryDeep)
                    }
                }
            }
        }

        if (startNext != null) {
            item { StartNextCard(startNext, onStart = { onStartFocus(startNext) }) }
        }

        if (upNext.isNotEmpty()) {
            item { SectionLabel("Up next", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)) }
            items(upNext, key = { it.id }) { t ->
                TaskRow(
                    task = t, onToggleDone = { vm.toggleDone(t) }, onOpen = { onOpen(t) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    trailing = { StartLink { onStartFocus(t) } },
                )
            }
        }

        if (startNext == null && upNext.isEmpty()) {
            item { EmptyState("Nothing queued. Add a task to get unstuck.") }
        }

        item { Text("", Modifier.padding(24.dp)) }
    }
}

@Composable
private fun StartNextCard(task: TaskItem, onStart: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
        SectionLabel("Start next")
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(task.name, style = UFont.serifItalic(22), color = c.ink)
            task.firstPhysicalAction?.let { Text("First move: $it", style = UFont.sans(13), color = c.ink2) }
            Text("${task.estimateMin} min", style = UFont.mono(12), color = c.ink3)
            UButton("Start focus", onClick = onStart)
        }
    }
}

@Composable
private fun StartLink(onClick: () -> Unit) {
    Text(
        "Start",
        style = UFont.sans(13, FontWeight.Medium),
        color = UTheme.colors.coralDeep,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun todaySubtitle(count: Int): String = if (count == 0) "A clear slate" else "$count on deck"
