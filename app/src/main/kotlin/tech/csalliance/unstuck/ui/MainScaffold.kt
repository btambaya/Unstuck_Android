package tech.csalliance.unstuck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.BottomNavBar
import tech.csalliance.unstuck.design.component.NavSpec
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.calendar.CalendarScreen
import tech.csalliance.unstuck.ui.collections.CollectionDetailScreen
import tech.csalliance.unstuck.ui.collections.CollectionsScreen
import tech.csalliance.unstuck.ui.components.AvatarMenu
import tech.csalliance.unstuck.ui.focus.FocusScreen
import tech.csalliance.unstuck.ui.insights.InsightsScreen
import tech.csalliance.unstuck.ui.settings.SettingsHub
import tech.csalliance.unstuck.ui.settings.SettingsSection
import tech.csalliance.unstuck.ui.tasks.NewTaskSheet
import tech.csalliance.unstuck.ui.tasks.TaskDetailScreen
import tech.csalliance.unstuck.ui.tasks.TasksScreen
import tech.csalliance.unstuck.ui.today.TodayScreen

private val NAV = listOf(
    NavSpec("today", "Today", Icons.Outlined.Schedule),
    NavSpec("tasks", "Tasks", Icons.Outlined.Inbox),
    NavSpec("calendar", "Calendar", Icons.Outlined.CalendarMonth),
    NavSpec("lists", "Collections", Icons.Outlined.Layers),
)

/** Full-screen overlay routes (pushed on top of the tab content). */
private sealed interface Route {
    data class Detail(val taskId: String) : Route
    data class Collection(val id: String) : Route
    data class Insights(val deep: Boolean) : Route
    data object Settings : Route
    data class SettingsSub(val section: SettingsSection) : Route
    data object Palette : Route
}

private sealed interface Sheet {
    data object Avatar : Sheet
    data object Areas : Sheet
}

@Composable
fun MainScaffold(vm: AppViewModel) {
    var tab by remember { mutableStateOf("today") }
    val stack = remember { mutableStateListOf<Route>() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var showNewTask by remember { mutableStateOf(false) }
    var focusTask by remember { mutableStateOf<TaskItem?>(null) }
    var activeArea by remember { mutableStateOf<String?>(null) }
    var onboarding by remember { mutableStateOf(!vm.onboarded) }
    val c = UTheme.colors
    val initials = remember(vm.currentName) {
        (vm.currentName ?: "U").split(' ', '.', '@').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "U" }
    }

    if (onboarding) {
        tech.csalliance.unstuck.ui.onboarding.OnboardingScreen(vm, onDone = { onboarding = false })
        return
    }

    val tasks by vm.tasks.collectAsStateWithLifecycle()
    fun push(r: Route) = stack.add(r)
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    // System back, top layer wins. NewTask / Avatar ride on ModalBottomSheet which
    // intercepts back itself, so we only handle the focus overlay, the route stack,
    // and the non-Today tab fall-back. (Leaving focus keeps the live session running.)
    val sheetOpen = showNewTask || sheet != null
    BackHandler(enabled = focusTask != null) { focusTask = null }
    BackHandler(enabled = focusTask == null && !sheetOpen && stack.isNotEmpty()) { pop() }
    BackHandler(enabled = focusTask == null && !sheetOpen && stack.isEmpty() && tab != "today") { tab = "today" }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "today" -> TodayScreen(
                        vm,
                        onStartFocus = { focusTask = it },
                        onOpen = { push(Route.Detail(it.id)) },
                        onAvatar = { sheet = Sheet.Avatar },
                        onSearch = { push(Route.Palette) },
                        onInsights = { push(Route.Insights(false)) },
                    )
                    "tasks" -> TasksScreen(vm, activeArea = activeArea, onClearArea = { activeArea = null }, onOpen = { push(Route.Detail(it.id)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, avatarInitials = initials)
                    "calendar" -> CalendarScreen(vm, onOpen = { push(Route.Detail(it.id)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, avatarInitials = initials)
                    "lists" -> CollectionsScreen(vm, onOpen = { push(Route.Collection(it)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, avatarInitials = initials)
                }
            }
            BottomNavBar(NAV, tab, onSelect = { tab = it; stack.clear() }, onFab = { showNewTask = true }, modifier = Modifier.navigationBarsPadding())
        }

        // Full-screen overlays (top of the stack) — inset from both system bars.
        stack.lastOrNull()?.let { route ->
            Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
                when (route) {
                    is Route.Detail -> {
                        val t = tasks.firstOrNull { it.id == route.taskId }
                        if (t != null) TaskDetailScreen(vm, t, onBack = ::pop, onStartFocus = { focusTask = t; pop() })
                        else pop()
                    }
                    is Route.Collection -> CollectionDetailScreen(vm, route.id, onBack = ::pop)
                    is Route.Insights -> InsightsScreen(vm, deep = route.deep, onBack = ::pop, onToggleDeep = { stack[stack.lastIndex] = Route.Insights(it) })
                    Route.Settings -> SettingsHub(vm, onBack = ::pop, onSection = { push(Route.SettingsSub(it)) }, onInsights = { push(Route.Insights(false)) })
                    is Route.SettingsSub -> tech.csalliance.unstuck.ui.settings.SettingsSubScreen(vm, route.section, onBack = ::pop)
                    Route.Palette -> tech.csalliance.unstuck.ui.palette.CommandPalette(
                        vm,
                        onDismiss = ::pop,
                        onOpenTask = { pop(); push(Route.Detail(it.id)) },
                        onTab = { tab = it; stack.clear() },
                        onSettings = { stack.clear(); push(Route.Settings) },
                    )
                }
            }
        }

        // Sheets.
        if (showNewTask) NewTaskSheet(vm, onDismiss = { showNewTask = false })
        when (sheet) {
            Sheet.Avatar -> AvatarMenu(
                vm,
                onInsights = { sheet = null; push(Route.Insights(false)) },
                onSettings = { sheet = null; push(Route.Settings) },
                onDismiss = { sheet = null },
            )
            Sheet.Areas -> tech.csalliance.unstuck.ui.components.AreasMenu(
                vm,
                onPick = { area -> activeArea = area; tab = "tasks"; stack.clear(); sheet = null },
                onDismiss = { sheet = null },
            )
            null -> {}
        }
        focusTask?.let { t ->
            val fresh = tasks.firstOrNull { it.id == t.id } ?: t
            FocusScreen(vm, fresh, onClose = { focusTask = null })
        }
    }
}
