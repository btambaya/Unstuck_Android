package tech.csalliance.unstuck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.calendar.CalendarScreen
import tech.csalliance.unstuck.ui.collections.CollectionsScreen
import tech.csalliance.unstuck.ui.focus.FocusScreen
import tech.csalliance.unstuck.ui.settings.SettingsScreen
import tech.csalliance.unstuck.ui.tasks.NewTaskSheet
import tech.csalliance.unstuck.ui.tasks.TaskDetailSheet
import tech.csalliance.unstuck.ui.tasks.TasksScreen
import tech.csalliance.unstuck.ui.today.TodayScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Outlined.WbSunny),
    TASKS("Tasks", Icons.Outlined.CheckCircle),
    CALENDAR("Calendar", Icons.Outlined.CalendarMonth),
    LISTS("Lists", Icons.Outlined.FolderOpen),
}

@Composable
fun MainScaffold(vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    var showNewTask by remember { mutableStateOf(false) }
    var focusTask by remember { mutableStateOf<TaskItem?>(null) }
    var detailTask by remember { mutableStateOf<TaskItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var onboarding by remember { mutableStateOf(!vm.onboarded) }
    val c = UTheme.colors

    if (onboarding) {
        tech.csalliance.unstuck.ui.onboarding.OnboardingScreen(vm, onDone = { onboarding = false })
        return
    }

    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val live by vm.liveSession.collectAsStateWithLifecycle()

    // Keep the live-session card / resume in sync with the latest task data.
    val liveTask = live?.let { l -> tasks.firstOrNull { it.id == l.taskId } }

    Scaffold(
        containerColor = c.bg,
        bottomBar = {
            BottomBar(
                current = tab,
                onSelect = { tab = it },
                onFab = { showNewTask = true },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.TODAY -> TodayScreen(vm, onStartFocus = { focusTask = it }, onOpen = { detailTask = it }, onSettings = { showSettings = true }, onSearch = { showPalette = true })
                Tab.TASKS -> TasksScreen(vm, onStartFocus = { focusTask = it }, onOpen = { detailTask = it })
                Tab.CALENDAR -> CalendarScreen(vm, onOpen = { detailTask = it })
                Tab.LISTS -> CollectionsScreen(vm)
            }
        }
    }

    if (showPalette) {
        tech.csalliance.unstuck.ui.palette.CommandPalette(
            vm,
            onDismiss = { showPalette = false },
            onOpenTask = { detailTask = it },
            onNewTask = { showNewTask = true },
            onTab = { i -> tab = Tab.entries[i] },
            onSettings = { showSettings = true },
        )
    }

    if (showNewTask) NewTaskSheet(vm, onDismiss = { showNewTask = false })
    detailTask?.let { t ->
        // Re-resolve to the freshest copy each recomposition.
        val fresh = tasks.firstOrNull { it.id == t.id } ?: t
        TaskDetailSheet(vm, fresh, onDismiss = { detailTask = null }, onStartFocus = { focusTask = fresh; detailTask = null })
    }
    focusTask?.let { t ->
        val fresh = tasks.firstOrNull { it.id == t.id } ?: t
        FocusScreen(vm, fresh, onClose = { focusTask = null })
    }
    if (showSettings) SettingsScreen(vm, onClose = { showSettings = false })

    // Surface a resume affordance handled inside Today; nothing else needed here.
    @Suppress("UNUSED_EXPRESSION") liveTask
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, onFab: () -> Unit) {
    val c = UTheme.colors
    NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
        NavBarItem(Tab.TODAY, current, onSelect)
        NavBarItem(Tab.TASKS, current, onSelect)
        // Center FAB slot.
        NavigationBarItem(
            selected = false,
            onClick = onFab,
            icon = {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(c.coralDeep),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = "New task", tint = Color.White) }
            },
        )
        NavBarItem(Tab.CALENDAR, current, onSelect)
        NavBarItem(Tab.LISTS, current, onSelect)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavBarItem(tab: Tab, current: Tab, onSelect: (Tab) -> Unit) {
    val c = UTheme.colors
    NavigationBarItem(
        selected = current == tab,
        onClick = { onSelect(tab) },
        icon = { Icon(tab.icon, contentDescription = tab.label) },
        label = { Text(tab.label, style = UFont.sans(11)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = c.coralDeep,
            selectedTextColor = c.coralDeep,
            unselectedIconColor = c.ink3,
            unselectedTextColor = c.ink3,
            indicatorColor = c.coralSoft,
        ),
    )
}
