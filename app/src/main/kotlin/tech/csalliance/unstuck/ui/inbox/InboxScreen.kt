package tech.csalliance.unstuck.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.design.theme.UnstuckColors
import tech.csalliance.unstuck.ui.AppViewModel

/**
 * Capture Inbox — the triage tray reached from the Today header. Every
 * thought you've captured (during focus, from a task, or on the fly) lands
 * here so it can be turned into a task, opened in context, or cleared.
 * "Done" archives a capture out of the inbox (device-local, no delete);
 * "Discard" removes it. Mirrors the web /inbox.
 */
@Composable
fun InboxScreen(vm: AppViewModel, onBack: () -> Unit, onOpenTask: (String) -> Unit) {
    val c = UTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val inbox by vm.inboxCaptures.collectAsStateWithLifecycle()
    val allCaptures by vm.captures.collectAsStateWithLifecycle()
    val archivedIds by vm.archivedCaptureIds.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    // Refresh ~every 30s so the "Xm ago" ages don't freeze at screen-open time.
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(vm.nowMs()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { now = vm.nowMs(); kotlinx.coroutines.delay(30_000) } }

    var showArchived by remember { mutableStateOf(false) }
    // Discard is irreversible (deletes the capture) → confirm first, mirroring the
    // iOS/web confirm. Holds the capture pending deletion.
    var confirmDiscard by remember { mutableStateOf<Capture?>(null) }
    val archived = remember(allCaptures, archivedIds) {
        allCaptures.filter { it.id in archivedIds }.sortedByDescending { it.at }
    }
    val visible = if (showArchived) archived else inbox

    fun taskName(id: String?): String? = id?.let { tid -> tasks.firstOrNull { it.id == tid }?.name }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = "Captures", leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(if (showArchived) "Archived" else "To process")
                    Spacer(Modifier.weight(1f))
                    if (showArchived || archived.isNotEmpty()) {
                        Text(
                            if (showArchived) "← Back to captures" else "Archived (${archived.size})",
                            style = UFont.sans(12, FontWeight.SemiBold),
                            color = c.ink3,
                            modifier = Modifier.clickable { showArchived = !showArchived }.padding(4.dp),
                        )
                    }
                }
            }

            if (visible.isEmpty()) {
                item {
                    Text(
                        if (showArchived) "No archived captures."
                        else "All clear. Capture a thought during a focus session — it keeps the task it came from.",
                        style = UFont.sans(13), color = c.ink3, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(visible, key = { it.id }) { cap ->
                    InboxCard(
                        cap = cap,
                        sourceTaskName = taskName(cap.taskId),
                        relTime = relPast(now - (Time.parseMillis(cap.at) ?: now)),
                        archivedView = showArchived,
                        onPromote = {
                            // Promote creates a task (capture preserved + archived). Confirm it
                            // happened — it used to silently archive with no feedback.
                            vm.promoteCapture(cap); vm.archiveCapture(cap.id)
                            android.widget.Toast.makeText(context, "Added to your tasks.", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onOpen = cap.taskId?.let { tid -> { onOpenTask(tid) } },
                        onArchive = { if (showArchived) vm.unarchiveCapture(cap.id) else vm.archiveCapture(cap.id) },
                        onDiscard = { confirmDiscard = cap },
                    )
                }
            }
            item { Box(Modifier.size(24.dp)) }
        }
    }

    confirmDiscard?.let { cap ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this capture?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = { Text("\"${cap.body}\" will be deleted. This can't be undone.", style = UFont.sans(13), color = c.ink2) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.deleteCapture(cap.id); vm.unarchiveCapture(cap.id); confirmDiscard = null
                }) { Text("Discard", color = c.red) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDiscard = null }) { Text("Cancel", color = c.ink2) }
            },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun InboxCard(
    cap: Capture,
    sourceTaskName: String?,
    relTime: String,
    archivedView: Boolean,
    onPromote: () -> Unit,
    onOpen: (() -> Unit)?,
    onArchive: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = UTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tagColor(cap.tag, c)))
            Text(cap.tag.name.replace('_', '-'), style = UFont.mono(10, FontWeight.Bold), color = tagColor(cap.tag, c))
            Text(relTime, style = UFont.mono(10), color = c.ink3)
            if (sourceTaskName != null) {
                Text("· from $sourceTaskName", style = UFont.sans(11), color = c.ink3, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Text(cap.body, style = UFont.sans(14), color = c.ink2, modifier = Modifier.padding(top = 5.dp))
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!archivedView) {
                Action("Promote →", c.primaryDeep, FontWeight.Bold, onPromote)
                if (onOpen != null) { Spacer(Modifier.width(16.dp)); Action("Open", c.ink2, FontWeight.SemiBold, onOpen) }
            } else if (onOpen != null) {
                Action("Open", c.ink2, FontWeight.SemiBold, onOpen)
            }
            Spacer(Modifier.weight(1f))
            Action(if (archivedView) "Restore" else "Done", c.ink2, FontWeight.SemiBold, onArchive)
            Spacer(Modifier.width(16.dp))
            Action("Discard", c.ink3, FontWeight.Normal, onDiscard)
        }
    }
}

@Composable
private fun Action(label: String, color: Color, weight: FontWeight, onClick: () -> Unit) {
    // Spoken label drops any trailing arrow glyph ("Promote →" → "Promote"); the
    // touch target is grown to the 48dp minimum without changing the drawn text.
    val spoken = label.replace("→", "").trim()
    Text(
        label, style = UFont.sans(12, weight), color = color,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = spoken }
            .padding(vertical = 2.dp),
    )
}

private fun tagColor(tag: CaptureTag, c: UnstuckColors): Color = when (tag) {
    CaptureTag.FOLLOW_UP -> c.primaryDeep
    CaptureTag.IDEA -> c.amber
    CaptureTag.EDIT -> c.blue
    CaptureTag.QUESTION -> c.green
    CaptureTag.DISTRACTION -> c.coral
}

private fun relPast(deltaMs: Long): String {
    val m = (deltaMs / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else -> "${m / 1440}d ago"
    }
}
