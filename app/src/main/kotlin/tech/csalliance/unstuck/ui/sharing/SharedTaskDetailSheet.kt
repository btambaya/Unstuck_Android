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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedTaskDetail
import tech.csalliance.unstuck.core.model.SharedWithMe
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

// SharedTaskDetailSheet (T1) — a RECIPIENT's read-only window onto a task someone
// shared with them. A "Shared with you" row used to be just a title + status chip;
// this sheet fetches shared_task_detail(taskId) (the only RLS-safe window onto the
// owner's task) so the recipient can SEE what it is — steps, area, estimate, due —
// and act at their level. It NEVER edits the owner's task.
//
//   view          → strictly read-only (no actions; "you're watching this").
//   partner/assign → Complete (shared_task_set_done) + Focus (starts shared focus,
//                    which accrues onto the owner via log_shared_focus — T3).

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

    val level = detail?.level ?: shared.level
    val ownerName = (detail?.ownerName ?: shared.ownerName).substringBefore('@')
    val title = detail?.title ?: shared.title
    val canAct = level.canComplete

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
                Text("Close", style = UFont.sans(14, FontWeight.Medium), color = Color.Transparent, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            }

            // From + level chip.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("from $ownerName", style = UFont.sans(13), color = c.ink3)
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(level.recipientLabel, style = UFont.sans(10, FontWeight.Bold), color = c.primaryDeep)
                }
            }

            // Title.
            Text(
                title, style = UFont.serifItalic(24), color = if (done) c.ink3 else c.ink,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )

            // Meta: area · estimate · due.
            val d = detail
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d?.lifeArea != null) {
                    AreaDotColor(areaColorFor(d.lifeArea, areas, c), size = 6)
                    Text(d.lifeArea!!, style = UFont.sans(12), color = c.ink3)
                    Text("·", style = UFont.sans(12), color = c.ink3)
                }
                Text("${(d?.estimateMin ?: 25)} min", style = UFont.sans(12), color = c.ink3)
                dueLabel(d?.dueAt)?.let {
                    Text("·", style = UFont.sans(12), color = c.ink3)
                    Text("due $it", style = UFont.sans(12), color = c.amberInk)
                }
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
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text("#$tn", style = UFont.sans(11, FontWeight.Medium), color = c.primaryDeep)
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
                    UButton(if (done) "✓ Completed" else "Complete", kind = ButtonKind.OUTLINED, fill = false) {
                        val next = !done
                        done = next
                        vm.completeSharedTask(shared.taskId, next)
                    }
                }
            } else {
                Text("You're watching this — $ownerName will start & finish it.", style = UFont.sans(12), color = c.ink3)
            }
        }
    }
}

/** Recipient-side wire label for a share level (the chip on THIS sheet). Distinct
 *  from the owner-side [ShareLevel.ownerLabel]. */
private val ShareLevel.recipientLabel: String
    get() = when (this) {
        ShareLevel.VIEW -> "watching"
        ShareLevel.PARTNER -> "partner"
        ShareLevel.ASSIGN -> "yours"
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
    done = shared.done, estimateMin = 25, totalFocused = 0, lifeArea = null, tags = emptyList(),
    objectives = emptyList(), dueAt = null, createdAt = "",
)
