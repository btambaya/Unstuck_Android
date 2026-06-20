package tech.csalliance.unstuck.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

private data class Result(val title: String, val meta: String?, val badge: String, val run: () -> Unit)

@Composable
fun CommandPalette(vm: AppViewModel, onDismiss: () -> Unit, onOpenTask: (TaskItem) -> Unit, onTab: (String) -> Unit, onSettings: () -> Unit) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()

    val allActions = listOf(
        Result("Go to Today", null, "ACTION") { onTab("today") },
        Result("Go to Tasks", null, "ACTION") { onTab("tasks") },
        Result("Go to Calendar", null, "ACTION") { onTab("calendar") },
        Result("Go to Lists", null, "ACTION") { onTab("lists") },
        Result("Settings", null, "ACTION") { onSettings() },
    )
    val actions = allActions.filter { q.isEmpty() || it.title.lowercase().contains(q) }
    val taskResults = tasks.filter { !it.done && (q.isEmpty() || it.name.lowercase().contains(q)) }
        .take(8).map { t -> Result(t.name, t.lifeArea ?: "—", "TASK") { onOpenTask(t) } }
    // Note rows route to their owning task (was a dead end that just closed
    // the palette). Drop notes with no resolvable owning task.
    val noteResults = captures
        .filter { q.isNotEmpty() && it.body.lowercase().contains(q) && it.taskId != null }
        .mapNotNull { cap -> tasks.find { it.id == cap.taskId }?.let { t -> Result(cap.body, cap.tag.name.lowercase(), "NOTE") { onOpenTask(t) } } }
        .take(4)
    val results = taskResults + noteResults + actions
    // A no-match query left a blank screen (dead end). Surface a calm empty state and
    // keep the navigation actions reachable so the palette is never a trap.
    val noMatches = q.isNotEmpty() && results.isEmpty()

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = c.ink3, modifier = Modifier.size(15.dp))
                BasicTextField(value = query, onValueChange = { query = it }, textStyle = UFont.sans(15).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f), decorationBox = { inner -> if (query.isEmpty()) Text("Search tasks + actions", style = UFont.sans(15), color = c.ink3); inner() })
            }
            Text("Cancel", style = UFont.sans(14, FontWeight.SemiBold), color = c.primaryDeep, modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss).minimumInteractiveComponentSize())
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            LazyColumn {
                if (noMatches) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp)) {
                            Text("No matches for “${query.trim()}”.", style = UFont.sans(14, FontWeight.Medium), color = c.ink2)
                            Text("Try a different word, or jump somewhere:", style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                // When the query matched nothing, keep navigation reachable: fall back to
                // the full action list so the palette is never a dead end.
                items(if (noMatches) allActions else results) { r ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { r.run() }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = UFont.sans(14, FontWeight.Medium), color = c.ink, maxLines = 1)
                            if (r.meta != null) Text(r.meta, style = UFont.sans(11), color = c.ink3, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(r.badge, style = UFont.mono(10).copy(letterSpacing = 1.sp), color = c.ink3)
                    }
                }
            }
        }
    }
}
