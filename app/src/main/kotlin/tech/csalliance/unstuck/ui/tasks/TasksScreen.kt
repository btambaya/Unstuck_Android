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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.daysSinceCreated
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabels
import tech.csalliance.unstuck.core.logic.ShareViewMode
import tech.csalliance.unstuck.core.logic.visibleShares
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.FilterPill
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.areaColorFor
import tech.csalliance.unstuck.ui.sharing.SharedWithYouSection
import tech.csalliance.unstuck.ui.sharing.ShareScreen
import tech.csalliance.unstuck.ui.sharing.ShareTarget
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import tech.csalliance.unstuck.design.component.neutralPill
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.stateDescription
import tech.csalliance.unstuck.core.logic.CompletedSection
import tech.csalliance.unstuck.core.logic.groupCompleted
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.ui.sharing.SharedWithYouRow

// Tab order mirrors the web TaskListPane: Backlog first (the triage stack),
// then All / Today / Upcoming / Later / Completed. Default is Today.
private val TAB_ORDER = listOf(
    TaskListView.BACKLOG, TaskListView.ALL, TaskListView.TODAY,
    TaskListView.UPCOMING, TaskListView.LATER, TaskListView.RECURRING, TaskListView.COMPLETED,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(
    vm: AppViewModel,
    activeArea: String?,
    onClearArea: () -> Unit,
    onAreaPick: (String?) -> Unit,
    onOpen: (TaskItem) -> Unit,
    onOpenShared: (SharedWithMe) -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    onAvatar: () -> Unit,
    onNotifications: () -> Unit,
    notifUnread: Int,
    avatarInitials: String,
    /** The "Edit" pill at the end of the area filter: the Areas & tags sheet. */
    onEditAreas: () -> Unit = {},
) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    // The row whose long-press menu is open, and the row whose "Share…" opened
    // the ONE Share screen (unified sharing v1).
    var rowMenuFor by remember { mutableStateOf<String?>(null) }
    var shareTarget by remember { mutableStateOf<ShareTarget?>(null) }
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    // "Shared with you" — tasks OTHERS shared with me. They sit on my own list views
    // (web parity: task-list-pane mounts the group on All / Today / Completed only).
    val sharedWithMe by vm.sharedWithMe.collectAsStateWithLifecycle()
    // Saveable so the selected tab + tag filter survive rotation / process death
    // (TaskListView is a Serializable enum). String? saves directly.
    var view by rememberSaveable { mutableStateOf(TaskListView.TODAY) }
    var activeTag by rememberSaveable { mutableStateOf<String?>(null) }
    // Minute ticker (mirrors TodayScreen): kept open across midnight, "Today" rolls
    // over and Backlog age badges advance instead of freezing at composition time.
    var nowState by remember { mutableLongStateOf(vm.nowMs()) }
    LaunchedEffect(Unit) { while (true) { nowState = vm.nowMs(); kotlinx.coroutines.delay(60_000) } }
    // Today is area-agnostic on purpose (web parity) — the area filter applies
    // to every other view. The tag filter applies to every view.
    // Memoized so the 60s ticker (or any unrelated recomposition) doesn't re-bucket
    // the whole list every frame — only when an input that affects bucketing changes.
    val list = remember(view, tasks, blocks, nowState, activeArea, activeTag) {
        visibleTasks(view, tasks, blocks, nowState, activeArea = if (view == TaskListView.TODAY) null else activeArea, activeTag = activeTag, slipMode = false)
    }
    // A shared task behaves like my own, placed by the OWNER's next block (migration
    // 052, in MY zone since 053): Today = next block today or unplanned, Upcoming =
    // after today, Backlog = before today and still open, Later = the owner parked it
    // (053 `later` — their own bucketing rule applies to me too), All = every open one
    // incl. Later + a task whose latest block already finished (+ today's win),
    // Completed collects the finished ones (Ahmad, 2026-08-02). Null on Recurring — a
    // template of mine it is not. The group respects the active area filter (an
    // area-less share always shows).
    val shareMode = when (view) {
        TaskListView.TODAY -> ShareViewMode.TODAY
        TaskListView.UPCOMING -> ShareViewMode.UPCOMING
        TaskListView.BACKLOG -> ShareViewMode.BACKLOG
        TaskListView.LATER -> ShareViewMode.LATER
        TaskListView.ALL -> ShareViewMode.ALL
        TaskListView.COMPLETED -> ShareViewMode.COMPLETED
        else -> null
    }
    val sharedVisible = remember(sharedWithMe, shareMode, nowState, activeArea) {
        shareMode?.let { visibleShares(sharedWithMe, it, nowState, Clock.dateIso(nowState), activeArea = activeArea) } ?: emptyList()
    }
    // "Overdue · Fri" badges for the Backlog rows, resolved for the WHOLE list in
    // one indexed pass. Called per row inside the item body (as it was) this
    // rescanned every cal_block and every task for EVERY row on EVERY composition
    // — the one genuinely per-frame O(n) cost on this screen. Same label per row.
    val overdueLabels = remember(view, list, tasks, blocks, nowState) {
        // Same Clock.todayIso() the per-row call used (and the same one visibleTasks
        // buckets against); nowState is only in the key so the badges roll over at
        // the minute tick, exactly like the list and the age badges beside them.
        if (view == TaskListView.BACKLOG) overdueOccurrenceLabels(list.map { it.id }, tasks, blocks, Clock.todayIso())
        else emptyMap()
    }
    // Completed is grouped into foldable date sections (owner request 2026-09-24):
    // my rows and finished shares together, by completedAt. The minute ticker in the
    // key rolls "Today" into "Yesterday" at local midnight.
    val completedGroups = remember(view, list, sharedVisible, nowState) {
        if (view != TaskListView.COMPLETED) emptyList()
        else groupCompleted(list.map { CompletedRow.Own(it) } + sharedVisible.map { CompletedRow.Shared(it) }, nowState) { it.completedAt }
    }

    // One of MY rows — shared by the flat list and the Completed date sections.
    val taskRow: @Composable (TaskItem, Modifier) -> Unit = { t, rowModifier ->
        // Long-press → "Share…" straight from the row (unified sharing
        // v1). An occurrence row (id = a cal_block id) shares from its
        // editor, where the template is resolved.
        val isOccurrence = occurrenceBlockFor(t.id, tasks, blocks) != null
        Box(rowModifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = { onOpen(t) }, onLongClick = { if (!isOccurrence) rowMenuFor = t.id })
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = UFont.sans(14, FontWeight.Medium), color = if (t.done) c.ink3 else c.ink, maxLines = 1, textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AreaDotColor(areaColorFor(t.lifeArea, areas, c), size = 5)
                    Text(t.lifeArea ?: "—", style = UFont.sans(12), color = c.ink3)
                    if (t.recurrence != null) Text("· ↻", style = UFont.sans(12), color = c.ink3)
                    // Tags inline on the same line as the area.
                    t.tags?.take(3)?.forEach { tn ->
                        Box(Modifier.neutralPill(c).clickable { activeTag = tn }.padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text("#$tn", style = UFont.sans(10, FontWeight.Medium), color = c.ink2)
                        }
                    }
                }
            }
            if (view == TaskListView.BACKLOG) {
                // A missed recurring occurrence shows "Overdue · Fri" (the missed
                // weekday) so it's obvious why it's here; everything else shows its
                // age. The occurrence row's createdAt is the template's, so the age
                // badge would be meaningless for it.
                val overdue = overdueLabels[t.id]
                if (overdue != null) {
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.amberSoft).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text(overdue, style = UFont.sans(10, FontWeight.Medium), color = c.amberInk)
                    }
                } else {
                    val age = tech.csalliance.unstuck.ui.components.ageDays(t.createdAt, nowState)
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.amberSoft).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("${age.coerceAtLeast(1)}d", style = UFont.sans(10, FontWeight.Medium), color = c.amberInk)
                    }
                }
            }
            Text("${t.estimateMin}m", style = UFont.mono(11), color = c.ink3)
        }
        DropdownMenu(expanded = rowMenuFor == t.id, onDismissRequest = { rowMenuFor = null }) {
            DropdownMenuItem(
                text = { Text("Share…", style = UFont.sans(14), color = c.ink) },
                leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp)) },
                onClick = { rowMenuFor = null; shareTarget = ShareTarget.Task(t.id, t.name) },
            )
        }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // No leading hamburger — the area-filter pills below cover what it did.
        AppBar(title = "Tasks", leading = Leading.NONE, onSearch = onSearch, onNotifications = onNotifications, notifUnread = notifUnread, onAvatar = onAvatar, avatarInitials = avatarInitials)
        // ── Pinned: title + tab pills + area/tag filters. Only the list below scrolls. ──
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("Your tasks", style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TAB_ORDER.forEach { v ->
                    val active = v == view
                    // Per-tab accent (web parity: Backlog=amber, Completed=green, …).
                    // Later has none (like All): it was indigo, and indigo is no longer an
                    // accent (owner decision 2026-09-24) — its selected state is ink / bg.
                    val pair: Pair<Color, Color>? = when (v) {
                        TaskListView.BACKLOG -> c.amberSoft to c.amberInk
                        TaskListView.TODAY -> c.coralSoft to c.ink
                        TaskListView.UPCOMING -> c.blueSoft to c.blueInk
                        TaskListView.RECURRING -> c.blueSoft to c.blueInk
                        TaskListView.COMPLETED -> c.greenSoft to c.greenInk
                        else -> null
                    }
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (active) (pair?.first ?: c.ink) else c.bg2).selectable(selected = active, role = Role.Tab, onClick = { view = v }).minimumInteractiveComponentSize().padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (pair != null && !active) Box(Modifier.size(6.dp).clip(CircleShape).background(pair.second))
                            Text(v.label, style = UFont.sans(12, FontWeight.Medium), color = if (active) (pair?.second ?: c.bg) else c.ink2)
                        }
                    }
                }
            }
            // Area filter pills (web parity: filter the list by life area). Today
            // is area-agnostic, so the pills only bite on the other tabs.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill("All", activeArea == null) { onAreaPick(null) }
                areas.forEach { a -> FilterPill(a.name, activeArea == a.name, dotColor = c.areaColor(a.color)) { onAreaPick(if (activeArea == a.name) null else a.name) } }
                // Areas and tags are edited where they're used (slim settings,
                // 2026-09-24): one sheet for both, from the end of the filter.
                EditAreasPill(onEditAreas)
            }
            if (activeTag != null) {
                Row(
                    Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(999.dp)).background(c.ink)
                        .clickable(onClickLabel = "Clear tag filter", role = Role.Button) { activeTag = null }
                        // One spoken label for the whole pill so the "✕" glyph isn't read literally.
                        .semantics(mergeDescendants = true) { contentDescription = "Filtering by tag #$activeTag" }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Filtering by tag ", style = UFont.sans(12), color = c.bg)
                    Text("#${activeTag}", style = UFont.sans(12, FontWeight.SemiBold), color = c.bg)
                    Text("✕", style = UFont.sans(12), color = c.bg)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            // "Quiet company" above my own rows. On Completed, finished shares sit in
            // the date sections below instead, among my own completed rows.
            if (shareMode != null && shareMode != ShareViewMode.COMPLETED && sharedVisible.isNotEmpty()) item(key = "shared-with-you") {
                SharedWithYouSection(
                    vm, sharedVisible, shareMode,
                    onToggle = { taskId, done -> vm.completeSharedTask(taskId, done) },
                    onOpen = onOpenShared,
                )
            }
            if (list.isEmpty() && sharedVisible.isEmpty()) {
                item { Text("No ${view.label.lowercase()} tasks.", style = UFont.sans(14), color = c.ink3, modifier = Modifier.padding(vertical = 32.dp)) }
            } else if (view == TaskListView.COMPLETED) {
                completedGroups.forEach { g ->
                    val open = completedSectionOpen(g.section)
                    item(key = "completed-section-${g.section.name}") {
                        CompletedSectionHeader(g.section, g.items.size, open, Modifier.animateItem()) {
                            completedFolds[g.section] = !open
                        }
                    }
                    if (open) items(g.items, key = { it.key }) { r ->
                        when (r) {
                            is CompletedRow.Own -> taskRow(r.task, Modifier.animateItem())
                            is CompletedRow.Shared -> SharedWithYouRow(
                                vm, r.share, Clock.todayIso(),
                                onToggle = { taskId, done -> vm.completeSharedTask(taskId, done) },
                                onOpen = onOpenShared,
                                modifier = Modifier.animateItem().padding(vertical = 3.dp),
                            )
                        }
                    }
                }
                item { Text("", Modifier.padding(28.dp)) }
            } else {
                items(list, key = { it.id }) { t -> taskRow(t, Modifier) }
                item { Text("", Modifier.padding(28.dp)) }
            }
        }
    }

    // The ONE Share screen, opened from a row's "Share…".
    shareTarget?.let { ShareScreen(vm, it, onDismiss = { shareTarget = null }) }
}

/** A Completed-tab row: one of mine, or a share someone finished with me. */
private sealed interface CompletedRow {
    val key: String
    val completedAt: String?
    data class Own(val task: TaskItem) : CompletedRow {
        override val key: String get() = task.id
        override val completedAt: String? get() = task.completedAt
    }
    data class Shared(val share: SharedWithMe) : CompletedRow {
        override val key: String get() = "shared-${share.shareId}-${share.taskId}"
        override val completedAt: String? get() = share.completedAt
    }
}

/** Which Completed sections the user folded / unfolded — kept for the app
 *  session (in memory), so leaving the tab and coming back keeps the layout. */
private val completedFolds = mutableStateMapOf<CompletedSection, Boolean>()

private fun completedSectionOpen(s: CompletedSection): Boolean = completedFolds[s] ?: s.defaultExpanded

/** "YESTERDAY · 4 ⌄" — the section-label eyebrow (ink3, no new colour) that
 *  folds / unfolds its section. One button for TalkBack, with its state. */
@Composable
private fun CompletedSectionHeader(section: CompletedSection, count: Int, open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val c = UTheme.colors
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "completed-chevron")
    Row(
        modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .testTag("completed-section-${section.name.lowercase()}")
            .minimumInteractiveComponentSize()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("${section.label} · $count")
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = c.ink3, modifier = Modifier.size(18.dp).rotate(turn))
    }
}

/** "Edit" at the end of the area filter — an outlined pill (never a filter,
 *  so it never looks selected) that opens the Areas & tags sheet. */
@Composable
private fun EditAreasPill(onClick: () -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.line2, RoundedCornerShape(999.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .semantics(mergeDescendants = true) { contentDescription = AreasTagsCopy.EDIT_PILL_A11Y }
            .testTag("tasks-edit-areas")
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, tint = c.ink2, modifier = Modifier.size(12.dp))
        Text(AreasTagsCopy.EDIT_PILL, style = UFont.sans(12, FontWeight.Medium), color = c.ink2)
    }
}
