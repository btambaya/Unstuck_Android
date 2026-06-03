package tech.csalliance.unstuck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    data object Notifications : Route
    data object Inbox : Route
}

private sealed interface Sheet {
    data object Avatar : Sheet
    data object Areas : Sheet
}

@Composable
fun MainScaffold(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf("today") }
    val stack = remember { mutableStateListOf<Route>() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var showNewTask by remember { mutableStateOf(false) }
    var newTaskPrefill by remember { mutableStateOf<Pair<String, String>?>(null) }
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
    val notifUnread by vm.notifUnread.collectAsStateWithLifecycle()
    val inboxCaptures by vm.inboxCaptures.collectAsStateWithLifecycle()
    fun push(r: Route) = stack.add(r)
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val openNotifs: () -> Unit = { vm.markNotificationsSeen(); push(Route.Notifications) }
    val openInbox: () -> Unit = { push(Route.Inbox) }

    // Consume notification deep links set by MainActivity: route to the task / today /
    // recap / brief, or open quick-capture (the live focus screen, else a new task).
    val deepLink by vm.pendingDeepLink.collectAsStateWithLifecycle()
    val liveSession by vm.liveSession.collectAsStateWithLifecycle()

    // Restore the live-focus foreground service from the persisted session after
    // process death — independent of whether the user is on the Focus screen.
    // The service is START_NOT_STICKY and was only armed from FocusScreen, so a
    // kill left an "active" session with no ongoing notification / paused
    // check-in. This runs while the Activity is foreground (allowed FGS start);
    // re-arming the single FOCUS notif id is idempotent.
    val fgsContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(liveSession?.sessionStart, liveSession?.taskId, liveSession?.paused) {
        val live = liveSession
        val start = live?.sessionStart
        if (live != null && start != null) {
            val name = tasks.firstOrNull { it.id == live.taskId }?.name ?: "Focus session"
            tech.csalliance.unstuck.surface.FocusTimerService.start(fgsContext, name, start, paused = live.paused)
            tech.csalliance.unstuck.surface.FocusTimerService.update(fgsContext, paused = live.paused, startMs = start)
            if (live.paused) tech.csalliance.unstuck.surface.PausedCheckinScheduler.arm(fgsContext, name)
        }
    }
    // Keyed on `tasks` too: on a COLD launch from a notification, Room hasn't emitted
    // yet (tasks is still empty), so a task/focus deep link can't resolve. Instead of
    // dumping the user on Today, we DON'T consume — the effect re-runs when tasks
    // populates and routes correctly. A bounded delay falls back to Today if the list
    // genuinely stays empty (e.g. a stale link / zero tasks).
    LaunchedEffect(deepLink, tasks) {
        val dl = deepLink ?: return@LaunchedEffect
        when {
            dl == "capture" -> {
                val liveTask = tasks.firstOrNull { it.id == liveSession?.taskId }
                if (liveTask != null) focusTask = liveTask else showNewTask = true
            }
            dl.startsWith("unstuck://focus/") -> {
                // "Start" on the starts-now notification → begin the session + open Focus.
                val id = dl.removePrefix("unstuck://focus/")
                val t = tasks.firstOrNull { it.id == id }
                when {
                    t != null -> { vm.startFocus(t); focusTask = t }
                    // No Room data yet: wait. If tasks emits, this effect cancels + re-runs
                    // (re-keyed on tasks) and resolves; otherwise fall back to Today.
                    tasks.isEmpty() -> { kotlinx.coroutines.delay(2500); tab = "today"; stack.clear() }
                    else -> { tab = "today"; stack.clear() }   // loaded but absent
                }
            }
            dl.startsWith("unstuck://task/") -> {
                val id = dl.removePrefix("unstuck://task/")
                when {
                    tasks.any { it.id == id } -> { tab = "today"; stack.clear(); push(Route.Detail(id)) }
                    tasks.isEmpty() -> { kotlinx.coroutines.delay(2500); tab = "today"; stack.clear() }
                    else -> { tab = "today"; stack.clear() }
                }
            }
            else -> { tab = "today"; stack.clear() }   // unstuck://today, /recap, /brief
        }
        vm.consumeDeepLink()
    }

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
                        onNotifications = openNotifs,
                        notifUnread = notifUnread,
                        onInbox = openInbox,
                        inboxCount = inboxCaptures.size,
                    )
                    "tasks" -> TasksScreen(vm, activeArea = activeArea, onClearArea = { activeArea = null }, onAreaPick = { activeArea = it }, onOpen = { push(Route.Detail(it.id)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, onNotifications = openNotifs, notifUnread = notifUnread, avatarInitials = initials)
                    "calendar" -> CalendarScreen(vm, onOpen = { push(Route.Detail(it.id)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, onNotifications = openNotifs, notifUnread = notifUnread, avatarInitials = initials, onCreateAt = { d, t -> newTaskPrefill = d to t; showNewTask = true })
                    "lists" -> CollectionsScreen(vm, onOpen = { push(Route.Collection(it)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, onNotifications = openNotifs, notifUnread = notifUnread, avatarInitials = initials)
                }
            }
            BottomNavBar(NAV, tab, onSelect = { tab = it; stack.clear() }, onFab = { showNewTask = true }, modifier = Modifier.navigationBarsPadding())
        }

        // Full-screen overlays (top of the stack) — inset from both system bars.
        // The opaque background HIDES the tab content beneath, but a plain Box
        // doesn't CONSUME pointer events, so taps on the overlay's empty areas
        // (e.g. the top-right, over the tab header's inbox/bell/avatar) would
        // fall through to those still-live icons. A no-op, no-ripple clickable
        // on the layer swallows those stray taps without affecting the overlay's
        // own interactive children (they consume their touches first).
        stack.lastOrNull()?.let { route ->
            Box(
                Modifier.fillMaxSize().background(c.bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .systemBarsPadding(),
            ) {
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
                    Route.Notifications -> tech.csalliance.unstuck.ui.notifications.NotificationCenterScreen(
                        vm, onBack = ::pop, onOpenTask = { id -> pop(); push(Route.Detail(id)) },
                    )
                    Route.Inbox -> tech.csalliance.unstuck.ui.inbox.InboxScreen(
                        vm, onBack = ::pop, onOpenTask = { id -> pop(); push(Route.Detail(id)) },
                    )
                }
            }
        }

        // Sheets.
        if (showNewTask) NewTaskSheet(vm, prefillDate = newTaskPrefill?.first, prefillTime = newTaskPrefill?.second, onDismiss = { showNewTask = false; newTaskPrefill = null })
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
