package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.AreaDot
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = UFont.serifItalic(28), color = c.ink)
            subtitle?.let { Text(it, style = UFont.sans(13), color = c.ink3) }
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, style = UFont.sans(14), color = UTheme.colors.ink3)
    }
}

@Composable
fun PriorityChip(priority: Priority?) {
    if (priority == null) return
    val c = UTheme.colors
    val color = when (priority) {
        Priority.URGENT -> c.red
        Priority.HIGH -> c.amber
        Priority.MEDIUM -> c.blue
        Priority.LOW -> c.ink3
    }
    Text(
        priority.name.lowercase(),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.bg2).padding(horizontal = 7.dp, vertical = 2.dp),
        style = UFont.mono(10, FontWeight.Medium),
        color = color,
    )
}

/** A task row: done toggle + area dot + name/meta + tap to open. */
@Composable
fun TaskRow(
    task: TaskItem,
    onToggleDone: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = UTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onToggleDone, modifier = Modifier.size(28.dp)) {
            if (task.done) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = c.green)
            } else {
                Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = "Mark done", tint = c.ink3)
            }
        }
        if (task.lifeArea != null) AreaDot(task.lifeArea)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                task.name,
                style = UFont.sans(15, FontWeight.Medium),
                color = if (task.done) c.ink3 else c.ink,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${task.estimateMin}m", style = UFont.mono(11), color = c.ink3)
                PriorityChip(task.priority)
            }
        }
        trailing?.invoke()
    }
}
