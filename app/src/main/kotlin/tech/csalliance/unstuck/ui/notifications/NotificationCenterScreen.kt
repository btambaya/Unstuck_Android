package tech.csalliance.unstuck.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import java.time.LocalDate
import java.time.ZoneId

private data class Upcoming(val taskId: String, val name: String, val at: Long)

/**
 * In-app notification center — the bell next to the avatar. Two sections:
 * "Upcoming" (scheduled task reminders in the next 2 days, computed live from
 * the blocks) and "Recent" (the log of notifications already shown, newest
 * first). Tapping a task-linked row opens that task.
 */
@Composable
fun NotificationCenterScreen(vm: AppViewModel, onBack: () -> Unit, onOpenTask: (String) -> Unit, onDeepLink: (String) -> Unit = {}) {
    val c = UTheme.colors
    val notifs by vm.notifications.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val now = remember { vm.nowMs() }  // stable for this screen open (not a per-frame key)

    val upcoming = remember(blocks, tasks) {
        blocks.asSequence()
            .filter { isTaskBlock(it) }
            .mapNotNull { b ->
                val ms = blockStartMs(b) ?: return@mapNotNull null
                val t = tasks.firstOrNull { it.id == b.taskId }
                if (ms in now..(now + 2L * 86_400_000) && t?.done != true) Upcoming(b.taskId ?: "", b.taskName, ms) else null
            }
            // De-dupe so identical (task, time) blocks don't collide as LazyColumn keys.
            .distinctBy { it.taskId to it.at }
            .sortedBy { it.at }.take(20).toList()
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = "Notifications", leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            if (upcoming.isNotEmpty()) {
                item { SectionLabel("Upcoming", Modifier.padding(top = 4.dp, bottom = 8.dp)) }
                items(upcoming, key = { "up:${it.taskId}:${it.at}" }) { u ->
                    Card(c.coral, u.name, relFuture(u.at - now), onClick = { if (u.taskId.isNotBlank()) onOpenTask(u.taskId) })
                }
            }
            item { SectionLabel("Recent", Modifier.padding(top = if (upcoming.isEmpty()) 4.dp else 18.dp, bottom = 8.dp)) }
            if (notifs.isEmpty()) {
                item { Text("Nothing yet. Reminders and recaps will show up here.", style = UFont.sans(13), color = c.ink3, modifier = Modifier.padding(vertical = 24.dp)) }
            } else {
                items(notifs, key = { it.id }) { n ->
                    val dl = n.deepLink
                    val taskId = dl?.takeIf { it.startsWith("unstuck://task/") }?.removePrefix("unstuck://task/")
                    Card(
                        dotColor = accentFor(n.kind, c),
                        title = n.title,
                        meta = "${n.body}  ·  ${relPast(now - n.at)}",
                        // Task links open the task; any other deep link (collection share,
                        // recap, brief) routes through MainScaffold instead of being dead.
                        onClick = when {
                            taskId != null -> ({ onOpenTask(taskId) })
                            !dl.isNullOrBlank() -> ({ onDeepLink(dl) })
                            else -> null
                        },
                    )
                }
            }
            item { Box(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun Card(dotColor: androidx.compose.ui.graphics.Color, title: String, meta: String, onClick: (() -> Unit)?) {
    val c = UTheme.colors
    val base = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.weight(1f)) {
            Text(title, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(meta, style = UFont.sans(12), color = c.ink3, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

private fun accentFor(kind: String, c: tech.csalliance.unstuck.design.theme.UnstuckColors) = when (kind) {
    "paused_checkin", "atstart", "drifted" -> c.amber
    "session_recap" -> c.green
    "morning_brief", "evening_preview", "daily_nudge" -> c.primaryDeep
    else -> c.coral
}

private fun blockStartMs(b: CalBlock): Long? {
    val d = b.date.split("-").mapNotNull { it.toIntOrNull() }
    val t = b.startTime.split(":").mapNotNull { it.toIntOrNull() }
    if (d.size != 3 || t.size < 2) return null
    return runCatching {
        LocalDate.of(d[0], d[1], d[2]).atTime(t[0], t[1]).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun relFuture(deltaMs: Long): String {
    val m = (deltaMs / 60_000).coerceAtLeast(0)
    return when {
        m < 60 -> "in ${m}m"
        m < 1440 -> "in ${m / 60}h"
        else -> "in ${m / 1440}d"
    }
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
