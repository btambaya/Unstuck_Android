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
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabel
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.time.Clock
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

// Tab order mirrors the web TaskListPane: Backlog first (the triage stack),
// then All / Today / Upcoming / Later / Completed. Default is Today.
private val TAB_ORDER = listOf(
    TaskListView.BACKLOG, TaskListView.ALL, TaskListView.TODAY,
    TaskListView.UPCOMING, TaskListView.LATER, TaskListView.RECURRING, TaskListView.COMPLETED,
)

@Composable
fun TasksScreen(
    vm: AppViewModel,
    activeArea: String?,
    onClearArea: () -> Unit,
    onAreaPick: (String?) -> Unit,
    onOpen: (TaskItem) -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    onAvatar: () -> Unit,
    onNotifications: () -> Unit,
    notifUnread: Int,
    avatarInitials: String,
) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
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
                    val pair: Pair<Color, Color>? = when (v) {
                        TaskListView.BACKLOG -> c.amberSoft to c.amberInk
                        TaskListView.TODAY -> c.coralSoft to c.coralDeep
                        TaskListView.UPCOMING -> c.blueSoft to c.blueInk
                        TaskListView.LATER -> c.primarySoft to c.primaryDeep
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
            }
            if (activeTag != null) {
                Row(
                    Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(999.dp)).background(c.primarySoft)
                        .clickable(onClickLabel = "Clear tag filter", role = Role.Button) { activeTag = null }
                        // One spoken label for the whole pill so the "✕" glyph isn't read literally.
                        .semantics(mergeDescendants = true) { contentDescription = "Filtering by tag #$activeTag" }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Filtering by tag ", style = UFont.sans(12), color = c.primaryDeep)
                    Text("#${activeTag}", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink)
                    Text("✕", style = UFont.sans(12), color = c.primaryDeep)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            if (list.isEmpty()) {
                item { Text("No ${view.label.lowercase()} tasks.", style = UFont.sans(14), color = c.ink3, modifier = Modifier.padding(vertical = 32.dp)) }
            } else {
                items(list, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable { onOpen(t) }.padding(horizontal = 12.dp, vertical = 11.dp),
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
                                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).clickable { activeTag = tn }.padding(horizontal = 7.dp, vertical = 2.dp)) {
                                        Text("#$tn", style = UFont.sans(10, FontWeight.Medium), color = c.primaryDeep)
                                    }
                                }
                            }
                        }
                        if (view == TaskListView.BACKLOG) {
                            // A missed recurring occurrence shows "Overdue · Fri" (the missed
                            // weekday) so it's obvious why it's here; everything else shows its
                            // age. The occurrence row's createdAt is the template's, so the age
                            // badge would be meaningless for it.
                            val overdue = overdueOccurrenceLabel(t.id, tasks, blocks, Clock.todayIso())
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
                }
                item { Text("", Modifier.padding(28.dp)) }
            }
        }
    }
}
