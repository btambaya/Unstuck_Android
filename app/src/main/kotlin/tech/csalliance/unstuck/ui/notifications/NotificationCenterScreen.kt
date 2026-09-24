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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.isExactTaskLink
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import java.time.LocalDate
import java.time.ZoneId

internal data class Upcoming(val taskId: String, val name: String, val at: Long)

/** Scheduled task reminders in the next 2 days, computed live from the blocks:
 *  task blocks starting within [now, now + 48h] whose task isn't done and whose
 *  block (a repeating task's day) isn't done or skipped, de-duped by (task, time),
 *  soonest first, at most 20. The block check runs BEFORE the de-dupe so a skipped
 *  twin at the same (task, time) can't hide the live one (parity with iOS build 81,
 *  audit 2026-09-22 C2). */
internal fun upcomingReminders(blocks: List<CalBlock>, tasks: List<TaskItem>, now: Long): List<Upcoming> =
    blocks.asSequence()
        .filter { isTaskBlock(it) && !it.done && !it.skipped }
        .mapNotNull { b ->
            val ms = blockStartMs(b) ?: return@mapNotNull null
            val t = tasks.firstOrNull { it.id == b.taskId }
            if (ms in now..(now + 2L * 86_400_000) && t?.done != true) Upcoming(b.taskId ?: "", b.taskName, ms) else null
        }
        // De-dupe so identical (task, time) blocks don't collide as LazyColumn keys.
        .distinctBy { it.taskId to it.at }
        .sortedBy { it.at }.take(20).toList()

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
    // Tick ~every 30s so the "Xm ago" / "in Xm" labels don't freeze at screen-open time.
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(vm.nowMs()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { now = vm.nowMs(); kotlinx.coroutines.delay(30_000) } }
    // The server's cards — a call rung on another device or one this phone
    // couldn't take, and every sharing/collaboration event whose push went
    // elsewhere, was held back, or never rang (parity with iOS and the web).
    // Read on open and on every return to the foreground while open (no
    // realtime channel here, and postgres_changes has no replay). Best-effort:
    // offline keeps what it had.
    var serverCards by remember { androidx.compose.runtime.mutableStateOf(emptyList<tech.csalliance.unstuck.surface.NotificationLog.Entry>()) }
    var cardsTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { cardsTick++ }
    androidx.compose.runtime.LaunchedEffect(cardsTick) { vm.bellQueueCards()?.let { serverCards = it } }
    val recent = remember(notifs, serverCards) { NotificationQueueCards.mergeRecent(notifs, serverCards) }

    val upcoming = remember(blocks, tasks) { upcomingReminders(blocks, tasks, now) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(title = "Notifications", leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            if (upcoming.isNotEmpty()) {
                item { SectionLabel("Upcoming", Modifier.padding(top = 4.dp, bottom = 8.dp)) }
                items(upcoming, key = { "up:${it.taskId}:${it.at}" }) { u ->
                    Card(c.coral, u.name, relFuture(u.at - now), onClick = { if (u.taskId.isNotBlank()) onOpenTask(u.taskId) }, kindLabel = "Upcoming reminder")
                }
            }
            item { SectionLabel("Recent", Modifier.padding(top = if (upcoming.isEmpty()) 4.dp else 18.dp, bottom = 8.dp)) }
            if (recent.isEmpty()) {
                item { Text("Nothing yet. Reminders and recaps will show up here.", style = UFont.sans(13), color = c.ink3, modifier = Modifier.padding(vertical = 24.dp)) }
            } else {
                items(recent, key = { it.id }) { n ->
                    val dl = n.deepLink
                    // An exact link (a call's notification — the series itself, audit
                    // 2026-09-22 C3) goes through the router, which keeps it exact.
                    val taskId = dl?.takeIf { it.startsWith("unstuck://task/") && !isExactTaskLink(it) }?.removePrefix("unstuck://task/")
                    Card(
                        dotColor = accentFor(n.kind, c),
                        kindLabel = kindLabel(n.kind),
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
private fun Card(dotColor: androidx.compose.ui.graphics.Color, title: String, meta: String, onClick: (() -> Unit)?, kindLabel: String? = null) {
    val c = UTheme.colors
    val base = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
    // The colored dot is the ONLY signal of the notification kind — fold a textual
    // kind into the row's spoken label so the meaning isn't color-only.
    val rowSemantics = if (kindLabel != null) {
        Modifier.semantics(mergeDescendants = true) { contentDescription = "$kindLabel. $title. $meta" }
    } else Modifier
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).then(rowSemantics).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.weight(1f)) {
            Text(title, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(meta, style = UFont.sans(12), color = c.ink3, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Human-readable name for a notification kind (the dot's color encodes this). */
private fun kindLabel(kind: String): String = when (kind) {
    "paused_checkin" -> "Paused check-in"
    "atstart" -> "Start reminder"
    "drifted" -> "Drift alert"
    "session_recap" -> "Session recap"
    "morning_brief" -> "Morning brief"
    "evening_preview" -> "Evening preview"
    "daily_nudge" -> "Daily nudge"
    // Every call entry this phone logs (the ring, and each call result) and
    // the server's call cards — they used to read "Reminder" (parity with iOS
    // build 72).
    "call", "call_missed", "call_busy", "call_outside_hours", "call_voice_failed", "call_off" -> "Call from Unstuck"
    // Sharing + collaboration (pushes, and the server's cards for them).
    "task_share", "shared_task_done" -> "Shared task"
    "shared_session_start", "shared_session_end" -> "Shared session"
    "collection_share" -> "Shared list"
    "circle_invite", "invite_claimed" -> "People"
    else -> "Reminder"
}

private fun accentFor(kind: String, c: tech.csalliance.unstuck.design.theme.UnstuckColors) = when (kind) {
    "paused_checkin", "atstart", "drifted" -> c.amber
    "session_recap" -> c.green
    // Neutral: these were indigo (owner decision 2026-09-24).
    "morning_brief", "evening_preview", "daily_nudge" -> c.ink2
    // Neutral too: coral is kept for what needs you now, not for chatter.
    "task_share", "shared_task_done", "shared_session_start", "shared_session_end",
    "collection_share", "circle_invite", "invite_claimed" -> c.ink2
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
