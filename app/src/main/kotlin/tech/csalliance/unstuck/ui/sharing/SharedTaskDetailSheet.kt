package tech.csalliance.unstuck.ui.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.RecipientShareAction
import tech.csalliance.unstuck.core.logic.plannedLabel
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.ShareSlot
import tech.csalliance.unstuck.core.model.SharedTaskDetail
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.shareStatusLabel
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.areaColorFor
import tech.csalliance.unstuck.design.component.neutralPill

// SharedTaskDetailSheet (T1) — a RECIPIENT's read-only window onto a task someone
// shared with them. A "Shared with you" row used to be just a title + status chip;
// this sheet fetches shared_task_detail(taskId) (the only RLS-safe window onto the
// owner's task) so the recipient can SEE what it is — steps, area, estimate, due —
// and act at their level. It NEVER edits the owner's task.
//
//   view          → strictly read-only (no actions; "you're watching this").
//   partner/assign → Complete (shared_task_set_done) + Focus (starts shared focus,
//                    which accrues onto the owner via log_shared_focus — T3).
//
// At EVERY level the recipient also has their own controls over the share (the
// ⋮ menu): Remove from my list / Report… / Block the owner. A recipient used to
// have none — an unwanted share sat in Today for good (parity with iOS build
// 79, audit 2026-09-22 C10). Remove and Block confirm first and close the sheet
// only once the SERVER agreed.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTaskDetailSheet(
    vm: AppViewModel,
    shared: SharedWithMe,
    onFocus: (SharedTaskDetail) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()

    // Fetch the read-only detail on open; fall back to the row's known fields (title,
    // owner, level, done) while it loads so the sheet is never blank / never wrong.
    var detail by remember(shared.taskId) { mutableStateOf<SharedTaskDetail?>(null) }
    LaunchedEffect(shared.taskId) { detail = vm.sharedTaskDetail(shared.taskId) }

    // Optimistic local completion so ticking reflects immediately (the RPC + a shares
    // refetch follow). Seeded from the freshest source (detail once loaded, else row).
    var done by remember(shared.taskId) { mutableStateOf(shared.done) }
    LaunchedEffect(detail?.done) { detail?.let { done = it.done } }

    // Whether the share repeats lives on my Shared-with-you row (the detail RPC
    // doesn't carry it); a sheet opened before that list loaded keeps its own row.
    val sharedRows by vm.sharedWithMe.collectAsStateWithLifecycle()
    val repeating = (sharedRows.firstOrNull { it.taskId == shared.taskId } ?: shared).recurring
    val context = androidx.compose.ui.platform.LocalContext.current

    val level = detail?.level ?: shared.level
    val ownerName = (detail?.ownerName ?: shared.ownerName).substringBefore('@')
    val title = detail?.title ?: shared.title
    val canAct = level.canComplete

    // The share this sheet is for — the row carries it, else (opened from a push,
    // by task id alone) my Shared-with-you row does (sharedRows, above). None → no menu.
    val shareId = shared.shareId.ifBlank { null }
        ?: sharedRows.firstOrNull { it.taskId == shared.taskId }?.shareId?.ifBlank { null }
    val owner = ownerName.ifBlank { "Someone" }
    val scope = rememberCoroutineScope()
    var menu by remember(shared.taskId) { mutableStateOf(false) }
    var confirmAction by remember(shared.taskId) { mutableStateOf<RecipientShareAction?>(null) }
    var showReport by remember(shared.taskId) { mutableStateOf(false) }
    var working by remember(shared.taskId) { mutableStateOf(false) }
    /** The line after a report, or a refused remove / block (ok → green). */
    var note by remember(shared.taskId) { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun run(action: RecipientShareAction) {
        val sid = shareId ?: return
        if (working) return
        working = true; note = null
        scope.launch {
            val ok = when (action) {
                RecipientShareAction.LEAVE -> vm.leaveSharedTask(sid)
                RecipientShareAction.BLOCK -> vm.blockTaskSharer(sid)
            }
            working = false
            if (ok) onDismiss() else note = false to "Couldn't do that — try again."
        }
    }

    fun report(reason: String) {
        note = null
        scope.launch {
            val ok = vm.reportSharedTask(shared.taskId, shareId, owner, reason)
            note = if (ok) true to "Report sent — we review every report." else false to "Couldn't do that — try again."
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Close", style = UFont.sans(14, FontWeight.Medium), color = c.ink2,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Text("Shared with you", style = UFont.serif(18, italic = true), color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                // The invisible "Close" keeps the title centred; the ⋮ sits on it.
                Box(contentAlignment = Alignment.CenterEnd) {
                    Text("Close", style = UFont.sans(14, FontWeight.Medium), color = Color.Transparent, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                    if (shareId != null) {
                        if (working) CircularProgressIndicator(Modifier.size(18.dp), color = c.ink2, strokeWidth = 2.dp)
                        else Icon(
                            Icons.Filled.MoreVert, contentDescription = "More", tint = c.ink2,
                            modifier = Modifier.size(32.dp).clip(CircleShape).clickable { menu = true }.padding(6.dp),
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Remove from my list", style = UFont.sans(14), color = c.red) },
                                onClick = { menu = false; confirmAction = RecipientShareAction.LEAVE },
                            )
                            DropdownMenuItem(text = { Text("Report…", style = UFont.sans(14), color = c.ink) }, onClick = { menu = false; showReport = true })
                            DropdownMenuItem(
                                text = { Text("Block $owner", style = UFont.sans(14), color = c.red) },
                                onClick = { menu = false; confirmAction = RecipientShareAction.BLOCK },
                            )
                        }
                    }
                }
            }

            note?.let { (ok, text) -> Text(text, style = UFont.sans(13, FontWeight.SemiBold), color = if (ok) c.greenInk else c.red) }

            // From + level chip.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("from $ownerName", style = UFont.sans(13), color = c.ink3)
                Box(Modifier.neutralPill(c).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(shareStatusLabel(level, done), style = UFont.sans(10, FontWeight.Bold), color = c.ink2)
                }
            }

            // Title.
            Text(
                title, style = UFont.serifItalic(24), color = if (done) c.ink3 else c.ink,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )

            // Meta: area · estimate · due. The row already carries area + estimate since
            // migration 052, so they render before the detail fetch lands.
            val d = detail
            val area = d?.lifeArea ?: shared.lifeArea
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (area != null) {
                    AreaDotColor(areaColorFor(area, areas, c), size = 6)
                    Text(area, style = UFont.sans(12), color = c.ink3)
                    Text("·", style = UFont.sans(12), color = c.ink3)
                }
                Text("${(d?.estimateMin ?: shared.estimateMin ?: 25)} min", style = UFont.sans(12), color = c.ink3)
                dueLabel(d?.dueAt)?.let {
                    Text("·", style = UFont.sans(12), color = c.ink3)
                    Text("due $it", style = UFont.sans(12), color = c.amberInk)
                }
            }

            // The owner's schedule (migration 052): "Planned Sat, Sep 5 · 04:30 · 45m"
            // (+ "· overdue" once the slot has passed and it's still open, "· finished"
            // when that block was already done). Opened from a CALENDAR block, the sheet
            // describes THAT occurrence (openedFrom) even after the live detail — which
            // carries the task's NEXT block — lands, as on the web. Otherwise the fresh
            // detail wins; until it lands the row seeds it. Nothing to plan → no line.
            val slot: ShareSlot = shared.openedFrom ?: d ?: shared
            plannedLabel(slot, Clock.todayIso(), tech.csalliance.unstuck.ui.components.clockMode())?.let { planned ->
                Text(planned, style = UFont.sans(12, FontWeight.Medium), color = if (planned.endsWith("overdue")) c.amberInk else c.ink2)
            }

            // Steps / subtasks (read-only).
            d?.objectives?.takeIf { it.isNotEmpty() }?.let { steps ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Steps")
                    steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.size(16.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (step.done == true) c.green else c.bg2),
                                contentAlignment = Alignment.Center,
                            ) { if (step.done == true) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp)) }
                            Text(
                                step.text, style = UFont.sans(13), color = if (step.done == true) c.ink3 else c.ink2,
                                textDecoration = if (step.done == true) TextDecoration.LineThrough else null,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Tags.
            d?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(6).forEach { tn ->
                        Box(Modifier.neutralPill(c).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text("#$tn", style = UFont.sans(11, FontWeight.Medium), color = c.ink2)
                        }
                    }
                }
            }

            // Actions — partner/assign can act; view is strictly read-only.
            if (canAct) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UButton(
                        if (level == ShareLevel.PARTNER) "Focus with them" else "Focus",
                        kind = ButtonKind.CORAL, fill = false, leadingIcon = Icons.Filled.PlayArrow,
                    ) { detail?.let(onFocus) ?: onFocus(fallbackDetail(shared)) }
                    // Hidden on an OPEN repeating share: the row is the owner's series
                    // and the server refuses the tick; Reopen stays, to recover a series
                    // the old path ended (parity with iOS build 81, audit 2026-09-22 C3).
                    if (done || !repeating) UButton(if (done) "✓ Completed" else "Complete", kind = ButtonKind.OUTLINED, fill = false) {
                        val next = !done
                        done = next   // optimistic
                        // Rolled back, with the reason, when the server refuses it — the
                        // sheet used to keep a "✓ Completed" that never landed (SC-12).
                        vm.completeSharedTask(shared.taskId, next) { refused ->
                            done = !next
                            android.widget.Toast.makeText(context, refused, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Text("You're watching this — $ownerName will start & finish it.", style = UFont.sans(12), color = c.ink3)
            }
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(action.title(owner), style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = { Text(action.message(owner), style = UFont.sans(13), color = c.ink2) },
            confirmButton = { TextButton(onClick = { confirmAction = null; run(action) }) { Text(action.confirmLabel, color = c.red) } },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel", color = c.ink2) } },
            containerColor = c.surface,
        )
    }

    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("Report this task?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Send a report about this task from $owner to the Unstuck team. We review reports and take action.", style = UFont.sans(13), color = c.ink2)
                    listOf("Objectionable content", "Spam", "Harassment", "Other").forEach { reason ->
                        TextButton(onClick = { showReport = false; report(reason) }) { Text(reason, color = c.ink) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showReport = false }) { Text("Cancel", color = c.ink2) } },
            containerColor = c.surface,
        )
    }
}


/** A YYYY-MM-DD label for an ISO due timestamp, or null when absent/unparseable. */
private fun dueLabel(dueAt: String?): String? {
    val ms = dueAt?.let { Time.parseMillis(it) } ?: return null
    return Clock.dateIso(ms)
}

/** Minimal detail when shared_task_detail hasn't resolved yet but the recipient taps
 *  Focus — enough to start a shared session (id, title, estimate default, level). */
private fun fallbackDetail(shared: SharedWithMe): SharedTaskDetail = SharedTaskDetail(
    taskId = shared.taskId, ownerName = shared.ownerName, level = shared.level, title = shared.title,
    done = shared.done, estimateMin = shared.estimateMin ?: 25, totalFocused = 0, lifeArea = shared.lifeArea, tags = emptyList(),
    objectives = emptyList(), dueAt = null, createdAt = "",
    nextBlockId = shared.nextBlockId, nextDate = shared.nextDate, nextStartTime = shared.nextStartTime,
    nextDurationMinutes = shared.nextDurationMinutes, nextDone = shared.nextDone,
)
