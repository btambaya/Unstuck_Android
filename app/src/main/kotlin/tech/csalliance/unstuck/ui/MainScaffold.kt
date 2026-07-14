package tech.csalliance.unstuck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import tech.csalliance.unstuck.BuildConfig
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedTaskDetail
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.BottomNavBar
import tech.csalliance.unstuck.design.component.NavSpec
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.calendar.CalendarScreen
import tech.csalliance.unstuck.ui.collections.CollectionDetailScreen
import tech.csalliance.unstuck.ui.collections.CollectionsScreen
import tech.csalliance.unstuck.ui.components.AvatarMenu
import tech.csalliance.unstuck.ui.focus.FocusScreen
import tech.csalliance.unstuck.ui.sharing.SharedTaskDetailSheet
import tech.csalliance.unstuck.ui.insights.InsightsScreen
import tech.csalliance.unstuck.ui.settings.SettingsHub
import tech.csalliance.unstuck.ui.settings.SettingsSection
import tech.csalliance.unstuck.ui.tasks.NewTaskSheet
import tech.csalliance.unstuck.core.logic.taskForBlock
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
    data object Feedback : Sheet
    data object Assistant : Sheet
}

@Composable
fun MainScaffold(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf("today") }
    val stack = remember { mutableStateListOf<Route>() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var showNewTask by rememberSaveable { mutableStateOf(false) }
    var newTaskPrefill by rememberSaveable(stateSaver = listSaver(
        save = { p -> if (p == null) emptyList() else listOf(p.first, p.second) },
        restore = { l -> if (l.size == 2) l[0] to l[1] else null },
    )) { mutableStateOf<Pair<String, String>?>(null) }
    var focusTask by remember { mutableStateOf<TaskItem?>(null) }
    var focusAutoCapture by remember { mutableStateOf(false) }
    // Non-null while the focus overlay is a RECIPIENT's shared-task session (T3): the
    // level drives startSharedFocus + the co-focus presence broadcast. Cleared with
    // focusTask. sharedDetail drives the read-only "Shared with you" detail sheet (T1).
    var focusShared by remember { mutableStateOf<ShareLevel?>(null) }
    var sharedDetail by remember { mutableStateOf<SharedWithMe?>(null) }
    var activeArea by remember { mutableStateOf<String?>(null) }
    var onboarding by remember { mutableStateOf(!vm.onboarded) }
    // Return to Today whenever the app is backgrounded, so reopening lands on the
    // home tab instead of a stale detail/overlay/sheet. A live focus session is
    // preserved (its overlay reappears). Config changes (rotation, dark-mode flip)
    // also pass through ON_STOP — those must NOT reset, or the saveable new-task
    // sheet state would be cleared before it is snapshotted.
    val hostActivity = androidx.compose.ui.platform.LocalContext.current.let { ctx ->
        generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (hostActivity?.isChangingConfigurations != true) {
            tab = "today"; stack.clear(); sheet = null; showNewTask = false; newTaskPrefill = null
        }
    }
    val c = UTheme.colors
    val initials = remember(vm.currentName) {
        (vm.currentName ?: "U").split(' ', '.', '@').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "U" }
    }

    if (onboarding) {
        tech.csalliance.unstuck.ui.onboarding.OnboardingScreen(vm, onDone = { onboarding = false })
        return
    }

    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
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

    // Open the focus overlay on an OWN task. If a live SHARED session is already running
    // for this id (returning to it from Today's live card), carry its level so the focus
    // screen re-arms shared mode; otherwise it's a normal own-task focus.
    val openFocus: (TaskItem) -> Unit = { t ->
        val ls = liveSession
        focusShared = if (ls?.taskId == t.id && ls.sharedTitle != null) ShareLevel.fromWire(ls.sharedLevel) else null
        focusTask = t
    }
    // Open the focus overlay on a task shared WITH me (T3) from the read-only detail
    // sheet — synthesize a display task from the detail + carry the share level.
    val openSharedFocus: (SharedTaskDetail) -> Unit = { d ->
        sharedDetail = null
        focusShared = d.level
        focusTask = TaskItem(
            id = d.taskId, name = d.title, estimateMin = d.estimateMin,
            objectives = d.objectives.ifEmpty { null }, tags = d.tags.ifEmpty { null },
            lifeArea = d.lifeArea, dueAt = d.dueAt,
            createdAt = d.createdAt.ifEmpty { "" }, updatedAt = d.createdAt.ifEmpty { "" },
        )
    }

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
            val name = tasks.firstOrNull { it.id == live.taskId }?.name ?: live.sharedTitle ?: "Focus session"
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
                // During a session → open Focus with the capture sheet already up; otherwise
                // quick-capture as a new task. Either way an input is shown, not a dead screen.
                if (liveTask != null) { focusTask = liveTask; focusAutoCapture = true } else showNewTask = true
            }
            dl.startsWith("unstuck://focus/") -> {
                // "Start" on the starts-now notification → begin the session + open Focus.
                val id = dl.removePrefix("unstuck://focus/")
                val t = tasks.firstOrNull { it.id == id }
                when {
                    t != null -> { focusShared = null; vm.startFocus(t); focusTask = t }
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
            dl == "unstuck://collections" -> { tab = "lists"; stack.clear() }   // a shared collection
            // Today hosts the "Shared with you" + "Delegated" sections, so the sharing
            // pings (unstuck://tasks: task_share / shared_task_done / shared_session_*)
            // land there alongside unstuck://today, /recap, /brief.
            else -> { tab = "today"; stack.clear() }
        }
        vm.consumeDeepLink()
    }

    // System back, top layer wins. NewTask / Avatar ride on ModalBottomSheet which
    // intercepts back itself, so we only handle the focus overlay, the route stack,
    // and the non-Today tab fall-back. (Leaving focus keeps the live session running.)
    val sheetOpen = showNewTask || sheet != null
    BackHandler(enabled = focusTask != null) { focusTask = null; focusAutoCapture = false; focusShared = null }
    BackHandler(enabled = focusTask == null && !sheetOpen && stack.isNotEmpty()) { pop() }
    BackHandler(enabled = focusTask == null && !sheetOpen && stack.isEmpty() && tab != "today") { tab = "today" }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "today" -> TodayScreen(
                        vm,
                        onStartFocus = openFocus,
                        onOpen = { push(Route.Detail(it.id)) },
                        onAvatar = { sheet = Sheet.Avatar },
                        onSearch = { push(Route.Palette) },
                        onInsights = { push(Route.Insights(false)) },
                        onNotifications = openNotifs,
                        notifUnread = notifUnread,
                        onInbox = openInbox,
                        inboxCount = inboxCaptures.size,
                        onOpenShared = { sharedDetail = it },
                    )
                    "tasks" -> TasksScreen(vm, activeArea = activeArea, onClearArea = { activeArea = null }, onAreaPick = { activeArea = it }, onOpen = { push(Route.Detail(it.id)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, onNotifications = openNotifs, notifUnread = notifUnread, avatarInitials = initials)
                    "calendar" -> CalendarScreen(vm, onOpen = { push(Route.Detail(it.id)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, onNotifications = openNotifs, notifUnread = notifUnread, avatarInitials = initials, onCreateAt = { d, t -> newTaskPrefill = d to t; showNewTask = true })
                    "lists" -> CollectionsScreen(vm, onOpen = { push(Route.Collection(it)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Areas }, onAvatar = { sheet = Sheet.Avatar }, onNotifications = openNotifs, notifUnread = notifUnread, avatarInitials = initials)
                }
            }
            BottomNavBar(NAV, tab, onSelect = { tab = it; stack.clear() }, onFab = { newTaskPrefill = null; showNewTask = true }, modifier = Modifier.navigationBarsPadding())
        }

        // Floating assistant/feedback bubble — sits above tab content (declared after
        // the Column) but hides under any overlay / sheet / focus (the guard), so it
        // never covers a modal. Bottom-end, lifted above the nav bar to clear the FAB.
        // Opens the dual-purpose surface (Assistant chat + Feedback). Gated by the
        // build flags so it's a one-flip for a public build.
        if ((BuildConfig.ASSISTANT_ENABLED || BuildConfig.FEEDBACK_ENABLED) && stack.isEmpty() && !sheetOpen && focusTask == null && tab != "calendar") {
            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 74.dp)
                    .size(50.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(c.surface)
                    .border(1.dp, c.line, CircleShape)
                    .clickable { sheet = if (BuildConfig.ASSISTANT_ENABLED) Sheet.Assistant else Sheet.Feedback },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Assistant", tint = c.coral, modifier = Modifier.size(22.dp))
            }
        }

        // Full-screen overlays (top of the stack) — inset from both system bars.
        // The opaque background HIDES the tab content beneath, but a plain Box
        // doesn't CONSUME pointer events, so taps on the overlay's empty areas
        // (e.g. the top-right, over the tab header's inbox/bell/avatar) would
        // fall through to those still-live icons. A no-op pointerInput on the
        // layer swallows those stray taps without affecting the overlay's own
        // interactive children (they consume their touches first) — and unlike
        // clickable it adds NO semantics node, so TalkBack doesn't surface a
        // giant nameless "double tap to activate" element on every pushed screen.
        stack.lastOrNull()?.let { route ->
            Box(
                Modifier.fillMaxSize().background(c.bg)
                    .pointerInput(Unit) { detectTapGestures {} }
                    .systemBarsPadding(),
            ) {
                when (route) {
                    is Route.Detail -> {
                        // A recurring OCCURRENCE's route id is its cal_block id, not a
                        // task id — resolve it to the projected one-day task so its
                        // detail opens (and edits/complete/skip route correctly).
                        val t = tasks.firstOrNull { it.id == route.taskId }
                            ?: blocks.firstOrNull { it.id == route.taskId }?.let { b -> taskForBlock(b, tasks) }
                        // Pop in an effect, not inline — mutating the stack during
                        // composition is a no-no (and can recompose-loop).
                        if (t != null) TaskDetailScreen(vm, t, onBack = ::pop, onStartFocus = { focusTask = t; pop() })
                        else LaunchedEffect(route.taskId) { pop() }
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
                        onDeepLink = { link -> pop(); vm.openDeepLink(link) },
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
            Sheet.Feedback -> tech.csalliance.unstuck.ui.feedback.FeedbackSheet(
                vm, currentScreen = tab, onDismiss = { sheet = null },
            )
            Sheet.Assistant -> tech.csalliance.unstuck.ui.assistant.AssistantSheet(
                vm, currentScreen = tab, onDismiss = { sheet = null },
            )
            null -> {}
        }
        // Read-only detail for a task shared WITH me (T1). Its Focus action starts a
        // shared focus session (T3); Complete goes through shared_task_set_done.
        sharedDetail?.let { s ->
            SharedTaskDetailSheet(vm, s, onFocus = openSharedFocus, onDismiss = { sharedDetail = null })
        }
        focusTask?.let { t ->
            // Own tasks resolve fresh from the store; a shared-task focus keeps the
            // synthesized task (it isn't in the store). focusShared marks shared mode.
            val fresh = if (focusShared != null) t else tasks.firstOrNull { it.id == t.id } ?: t
            FocusScreen(vm, fresh, onClose = { focusTask = null; focusAutoCapture = false; focusShared = null }, autoCapture = focusAutoCapture, sharedLevel = focusShared)
        }
    }
}
