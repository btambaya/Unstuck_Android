package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.asSharedWithMe
import tech.csalliance.unstuck.core.logic.blockSlotText
import tech.csalliance.unstuck.core.logic.dayPeek
import tech.csalliance.unstuck.core.logic.openedFrom
import tech.csalliance.unstuck.core.logic.peekDayTitle
import tech.csalliance.unstuck.core.logic.shareFirstName
import tech.csalliance.unstuck.core.logic.taskForBlock
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// Month day peek — everything on one tapped day, and a way into each item.
//
// The month grid used to open a sheet ONLY when a shared block sat on the day;
// a day carrying the user's own planned blocks did nothing at all, so the dots
// under the day numbers behaved inconsistently (tester, 2026-09-08). Every day
// now opens this same peek, and every row that leads somewhere is tappable:
// a planned block opens THAT task (the occurrence, for a recurring template),
// a shared one its read-only detail. Calendar events are display-only, as they
// are everywhere else in the app. The old jump-to-Day-view is kept as an
// explicit action at the top rather than being the day's only outcome.
//
// 1:1 with iOS `MonthDayPeekSheet` (Calendar+Shared.swift): same sections, same
// order, same copy, same empty state.

/** One rendered row: the parts that differ per section, resolved up front. */
private data class PeekRow(
    val key: String,
    val title: String,
    val meta: String,
    val done: Boolean,
    val tint: Color,
    val dashed: Boolean,
    val onClick: (() -> Unit)?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthDayPeekSheet(
    vm: AppViewModel,
    iso: String,
    onOpen: (TaskItem) -> Unit,
    onOpenShared: (SharedWithMe) -> Unit,
    /** "Open in Day view" — the month grid's previous tap behaviour, kept reachable. */
    onOpenDay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blocksRaw by vm.blocks.collectAsStateWithLifecycle()
    val sharedRaw by vm.sharedBlocks.collectAsStateWithLifecycle()
    val sharedWithMe by vm.sharedWithMe.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val peek = remember(iso, blocksRaw, sharedRaw) { dayPeek(iso, blocksRaw, sharedRaw) }
    val clock = tech.csalliance.unstuck.ui.components.clockMode()

    // Own planned blocks. A block whose task has vanished from the local cache has
    // nowhere to go — it still lists, it just isn't tappable.
    val planned = peek.planned.map { b ->
        val task = taskForBlock(b, tasks)
        PeekRow(
            key = b.id, title = b.taskName, meta = blockSlotText(b.startTime, b.durationMinutes, clock),
            done = b.done || task?.done == true, tint = c.ink2, dashed = false,
            onClick = task?.let { t -> { onOpen(t) } },
        )
    }
    // Shared with me — the live list row pinned to THIS occurrence, so the detail
    // sheet describes the slot that was tapped (the Week/Day rule).
    val shared = peek.shared.map { sb ->
        PeekRow(
            key = sb.blockId, title = sb.title,
            meta = "${blockSlotText(sb.startTime, sb.durationMinutes, clock)} · ${shareFirstName(sb.ownerName)}",
            done = sb.done, tint = c.ink2, dashed = true,
            onClick = { onOpenShared(sharedWithMe.firstOrNull { it.taskId == sb.taskId }?.openedFrom(sb) ?: sb.asSharedWithMe()) },
        )
    }
    // Synced events + placeholders: they say what the day already costs, but there
    // is no Unstuck detail behind them.
    val events = peek.events.map { b ->
        PeekRow(
            key = b.id, title = b.taskName, meta = blockSlotText(b.startTime, b.durationMinutes, clock),
            done = false, tint = c.ink3, dashed = false, onClick = null,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 10.dp, bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(peekDayTitle(iso), style = UFont.serifItalic(22), color = c.ink, modifier = Modifier.weight(1f))
                Text(
                    "Open in Day view", style = UFont.sans(12, FontWeight.Medium), color = c.ink,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClick = onOpenDay)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (peek.isEmpty) {
                Spacer(Modifier.height(14.dp))
                Text("Nothing on this day.", style = UFont.serifItalic(20), color = c.ink)
                Text("A clear day is a fine thing.", style = UFont.sans(13), color = c.ink3, modifier = Modifier.padding(top = 6.dp))
            }
            PeekSection("Planned", planned)
            PeekSection("Shared with you", shared)
            PeekSection("In the calendar", events)
        }
    }
}

@Composable
private fun PeekSection(label: String, rows: List<PeekRow>) {
    if (rows.isEmpty()) return
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bg2)
                    .then(if (row.onClick != null) Modifier.clickable(role = Role.Button) { row.onClick.invoke() } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ● mine · ⊙ dashed = someone else's (the calendars' shared mark).
                if (row.dashed) Box(Modifier.size(9.dp).dashedBorder(row.tint, 1.dp, 4.5.dp))
                else Box(Modifier.size(8.dp).clip(CircleShape).background(row.tint))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title, style = UFont.sans(15, FontWeight.Medium),
                        color = if (row.done) c.ink3 else c.ink,
                        textDecoration = if (row.done) TextDecoration.LineThrough else null,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(row.meta, style = UFont.mono(10), color = c.ink3, modifier = Modifier.padding(top = 2.dp))
                }
                if (row.onClick != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("›", style = UFont.serifItalic(20), color = c.ink4)
                }
            }
        }
    }
}
