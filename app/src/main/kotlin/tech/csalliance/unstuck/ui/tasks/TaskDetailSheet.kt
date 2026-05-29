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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.findFreeSlots
import tech.csalliance.unstuck.core.logic.formatTime
import tech.csalliance.unstuck.core.logic.recurrenceLabel
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.RecurrenceEditor
import tech.csalliance.unstuck.ui.components.TagPicker
import tech.csalliance.unstuck.ui.components.areaColorFor

/** Full-screen task detail — editable (name / first action / estimate / area /
 *  repeat / tags), with session history and capture management. */
@Composable
fun TaskDetailScreen(vm: AppViewModel, task: TaskItem, onBack: () -> Unit, onStartFocus: () -> Unit) {
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    var scheduled by remember(task.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val taskSessions = sessions.filter { it.taskId == task.id }
    val taskCaptures = captures.filter { it.taskId == task.id }
    val myBlocks = blocks.filter { it.taskId == task.id }.sortedWith(compareBy({ it.date }, { it.startTime }))
    val scheduleLabel = when {
        task.later == true -> "Later"
        myBlocks.isNotEmpty() -> "${myBlocks.first().date.takeLast(5)} ${formatTime(myBlocks.first().startTime)}"
        else -> "Unscheduled"
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AreaDotColor(areaColorFor(task.lifeArea, areas, c), size = 6)
                SectionLabel("${(task.lifeArea ?: "Task").uppercase()} · TASK")
            }

            EditableText(
                value = task.name, placeholder = "Untitled task",
                style = UFont.sans(28, FontWeight.Bold),
                color = if (task.done) c.ink3 else c.ink,
                strike = task.done,
                modifier = Modifier.padding(top = 6.dp),
            ) { vm.updateTask(task.copy(name = it)) }

            Box(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Column {
                    SectionLabel("First physical action", color = c.coral)
                    EditableText(
                        value = task.firstPhysicalAction ?: "", placeholder = "Add one — the smallest concrete step.",
                        style = UFont.sans(14).copy(fontStyle = FontStyle.Italic),
                        color = if (task.firstPhysicalAction == null) c.ink3 else c.ink,
                        modifier = Modifier.padding(top = 6.dp),
                    ) { vm.updateTask(task.copy(firstPhysicalAction = it.trim().ifEmpty { null })) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { UButton("Focus", kind = ButtonKind.CORAL, leadingIcon = Icons.Filled.PlayArrow, onClick = onStartFocus) }
                UButton("Schedule", kind = ButtonKind.OUTLINED, fill = false) {
                    val slot = findFreeSlots(blocks, task.estimateMin, vm.nowMs(), limit = 1).firstOrNull()
                    if (slot != null) { vm.scheduleTask(task, slot.date, slot.startTime); scheduled = slot.label }
                }
                UButton(if (task.done) "✓ Done" else "Mark done", kind = ButtonKind.TEXT, fill = false) { vm.toggleDone(task) }
            }
            scheduled?.let { Text("Scheduled $it", style = UFont.sans(12), color = c.green, modifier = Modifier.padding(top = 8.dp)) }
            if (task.later == true) {
                UButton("Move out of Later", kind = ButtonKind.GHOST, fill = false, modifier = Modifier.padding(top = 4.dp)) { vm.setLater(task, false) }
            }

            Card(Modifier.fillMaxWidth().padding(top = 18.dp), radius = 14) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        SectionLabel("Estimate")
                        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 25, 45, 60, 90).forEach { m ->
                                SelectableChip("${m}m", selected = task.estimateMin == m) { vm.updateTask(task.copy(estimateMin = m)) }
                            }
                        }
                    }
                    Column {
                        SectionLabel("Area")
                        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectableChip("Unassigned", selected = task.lifeArea == null) { vm.updateTask(task.copy(lifeArea = null)) }
                            areas.forEach { a -> SelectableChip(a.name, selected = task.lifeArea == a.name) { vm.updateTask(task.copy(lifeArea = a.name)) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MetaCell("Schedule", scheduleLabel, Modifier.weight(1f))
                        MetaCell("Status", if (task.done) "Completed" else "Not started", Modifier.weight(1f))
                    }
                }
            }

            SectionLabel("Repeat", Modifier.padding(top = 18.dp, bottom = 4.dp))
            Text(recurrenceLabel(task.recurrence).ifEmpty { "Does not repeat" }, style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(bottom = 6.dp))
            RecurrenceEditor(task.recurrence) { vm.setRecurrence(task, it) }

            SectionLabel("Tags", Modifier.padding(top = 18.dp, bottom = 6.dp))
            TagPicker(vm, task.tags ?: emptyList()) { vm.updateTask(task.copy(tags = it.ifEmpty { null })) }

            if (taskSessions.isNotEmpty()) {
                SectionLabel("Sessions", Modifier.padding(top = 18.dp, bottom = 6.dp))
                taskSessions.take(6).forEach { s ->
                    Text("• ${s.actualSec / 60}m focused", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            SectionLabel("Captures", Modifier.padding(top = 18.dp, bottom = 6.dp))
            taskCaptures.forEach { cap -> CaptureRow(cap, vm.nowMs(), onPromote = { vm.promoteCapture(cap) }, onDiscard = { vm.deleteCapture(cap.id) }) }
            AddCaptureRow { tag, body -> vm.saveCapture(task.id, null, tag, body) }

            UButton("Delete", kind = ButtonKind.DANGER, fill = false, modifier = Modifier.padding(top = 22.dp)) { confirmDelete = true }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this task?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { Text("Its scheduled blocks and captures are removed too.", style = UFont.sans(13), color = c.ink2) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteTask(task.id); onBack() }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )
}

@Composable
private fun EditableText(
    value: String,
    placeholder: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    strike: Boolean = false,
    onCommit: (String) -> Unit,
) {
    val c = UTheme.colors
    var editing by remember(value) { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    if (editing) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = draft, onValueChange = { draft = it }, textStyle = style.copy(color = c.ink),
                cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f),
            )
            Text("✓", style = UFont.sans(18), color = c.green, modifier = Modifier.clickable { onCommit(draft.trim()); editing = false }.padding(4.dp))
            Text("✕", style = UFont.sans(18), color = c.ink3, modifier = Modifier.clickable { draft = value; editing = false }.padding(4.dp))
        }
    } else {
        Text(
            value.ifEmpty { placeholder },
            style = style, color = color,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            modifier = modifier.clickable { draft = value; editing = true },
        )
    }
}

@Composable
private fun CaptureRow(cap: Capture, now: Long, onPromote: () -> Unit, onDiscard: () -> Unit) {
    val c = UTheme.colors
    val (bg, fg) = captureTagColors(cap.tag)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(cap.tag.name.lowercase().replace('_', '-'), style = UFont.sans(10, FontWeight.Medium), color = fg)
            }
            Text(relativeTime(cap.at, now), style = UFont.mono(10), color = c.ink3)
        }
        Text(cap.body, style = UFont.sans(14), color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Promote to task →", style = UFont.sans(12, FontWeight.Medium), color = c.primaryDeep, modifier = Modifier.clickable(onClick = onPromote))
            Text("Discard", style = UFont.sans(12), color = c.ink3, modifier = Modifier.clickable(onClick = onDiscard))
        }
    }
}

@Composable
private fun AddCaptureRow(onAdd: (CaptureTag, String) -> Unit) {
    val c = UTheme.colors
    var body by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf(CaptureTag.FOLLOW_UP) }
    val tags = listOf(
        CaptureTag.FOLLOW_UP to "follow-up", CaptureTag.IDEA to "idea", CaptureTag.EDIT to "edit",
        CaptureTag.QUESTION to "question", CaptureTag.DISTRACTION to "distraction",
    )
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = body, onValueChange = { body = it }, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner -> if (body.isEmpty()) Text("Capture a thought…", style = UFont.sans(14), color = c.ink3); inner() },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            tags.forEach { (t, label) -> SelectableChip(label, selected = tag == t) { tag = t } }
            if (body.isNotBlank()) {
                Text("Add", style = UFont.sans(12, FontWeight.SemiBold), color = c.primaryDeep, modifier = Modifier.clickable { onAdd(tag, body.trim()); body = "" }.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
}

private fun captureTagKey(tag: CaptureTag): Int = when (tag) {
    CaptureTag.IDEA -> 1
    CaptureTag.EDIT -> 2
    CaptureTag.QUESTION -> 3
    CaptureTag.DISTRACTION -> 4
    else -> 0
}

@Composable
private fun captureTagColors(tag: CaptureTag): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val c = UTheme.colors
    return when (captureTagKey(tag)) {
        1 -> c.amberSoft to c.amberInk
        2 -> c.blueSoft to c.blueInk
        3 -> c.greenSoft to c.greenInk
        4 -> c.coralSoft to c.coralDeep
        else -> c.primarySoft to c.primaryDeep
    }
}

private fun relativeTime(iso: String, now: Long): String {
    val ms = Time.parseMillis(iso) ?: return ""
    val diff = (now - ms).coerceAtLeast(0)
    val min = diff / 60000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 1440 -> "${min / 60}h ago"
        else -> "${min / 1440}d ago"
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Column(modifier) {
        SectionLabel(label)
        Text(value, style = UFont.sans(13), color = c.ink, modifier = Modifier.padding(top = 3.dp))
    }
}
