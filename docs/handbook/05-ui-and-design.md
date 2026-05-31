## UI Layer & Design System (`:app/ui` + `:design`)

This chapter is your map to everything the user actually sees and touches: the single `AppViewModel` that every screen reads from and writes through, the navigation shell (`MainActivity` → `AppRoot` → `MainScaffold`), the brand `:design` system (color, type, components), and a per-screen tour of every file under `app/src/main/kotlin/.../ui/**`. The mental model to hold from the start: **Compose screens are pure renderers**. They observe `StateFlow`s off the local Room store and call `vm.fn(...)` write actions; all business rules and persistence live in `:core` and `:sync`. Same model as the web and iOS apps.

### Where everything lives

```
app/src/main/kotlin/tech/csalliance/unstuck/
  MainActivity.kt          Activity: theme entry, deep-links, FCM, sync scheduling
  AppGraph.kt              manual DI container (store, provider, coordinator, settings, onboarded)
  ui/
    AppViewModel.kt        the ONE state holder + every write action
    AppRoot.kt             theme owner + top-level state machine (setup/loading/auth/main)
    MainScaffold.kt        tab + overlay-stack + sheet navigation, FAB, back handling
    today/  tasks/  calendar/  focus/  collections/  insights/
    settings/  onboarding/  auth/  palette/  components/
design/src/main/kotlin/tech/csalliance/unstuck/design/
  color/Oklch.kt           oklch→sRGB converter + hex parser
  theme/Theme.kt           UnstuckColors (light/dark), accents, UnstuckTheme, Radius
  theme/Type.kt            UFont (Geist / Instrument Serif / IBM Plex Mono), Typography
  component/Chrome.kt      AppBar, BottomNavBar, CoralFab, SheetHandle, SheetScrim
  component/Components.kt  UButton, FilterPill, Chip, SectionLabel, Card, StatCard, …
  component/Controls.kt    MdField, MdToggle, MdSegment
  component/Mark.kt        Orbit (the brand mark)
design/src/main/res/font/  the bundled .ttf files
```

The `:design` module is a standalone library with **no dependency on `:app` or `:core`** — it only knows Compose. `:app` depends on `:design`, `:core` (model/logic/time), and `:sync`.

---

### `AppViewModel` — the single state holder

`ui/AppViewModel.kt` is the heart of the UI layer. There is exactly one instance, created in `AppRoot` and threaded into every screen as `vm`. It is constructed from the `AppGraph` (the process-wide DI container):

```kotlin
class AppViewModel(private val graph: AppGraph) : ViewModel() {
    private val store = graph.store            // Room-backed LocalStore
    private val write get() = graph.coordinator?.write   // WriteThrough (nullable!)
    val auth get() = graph.coordinator?.auth
```

Note `write` and `auth` are **nullable** — they are null when the app isn't `configured` (no Supabase anon key). Every write action calls `write?.…`, so on an unconfigured build the actions are silent no-ops rather than crashes.

#### Exposing collections as StateFlows

A tiny helper turns each `Flow<List<T>>` from the store into a lifecycle-friendly `StateFlow`:

```kotlin
private fun <T> sf(flow: Flow<List<T>>): StateFlow<List<T>> =
    flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

val tasks       = sf(store.tasks())
val blocks      = sf(store.blocks())
val sessions    = sf(store.sessions())
val captures    = sf(store.captures())
val reasonLogs  = sf(store.reasonLogs())
val collections = sf(store.collections())
val tags        = sf(store.tags())
val lifeAreas   = sf(store.lifeAreas())
val connections = sf(store.connections())
val liveSession : StateFlow<LiveSession?> = store.liveSession().stateIn(...)
val pendingCount = store.pendingCount().stateIn(...)
```

`SharingStarted.WhileSubscribed(5_000)` means the underlying Room query is collected only while a composable is observing it, with a 5-second grace window across config changes/navigation. Screens collect these with `collectAsStateWithLifecycle()`.

`authed` is the one flow that gates everything else:

```kotlin
val authed: StateFlow<Boolean?> = run {
    val client = graph.provider?.client
    if (client == null) MutableStateFlow<Boolean?>(false)
    else client.auth.sessionStatus
        .map { it is SessionStatus.Authenticated }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
```

It starts `null` (auth status unknown → show a spinner), then resolves to `true`/`false`. `SharingStarted.Eagerly` so it starts resolving immediately, not lazily.

#### The `launchWrite` pattern

Every mutation funnels through one helper:

```kotlin
private fun launchWrite(block: suspend () -> Unit) { viewModelScope.launch { block() } }
```

So a write action is just: compute the new domain object (often via a `:core` rule), then call the suspend `write?.upsertX(...)`. Example:

```kotlin
fun toggleDone(task: TaskItem) = launchWrite {
    val flipped = task.copy(done = !task.done)
    write?.upsertTask(applyCompletion(flipped, prior = task, nowISO = isoNow()))
}
```

The actions never block the caller; the screen fires `vm.toggleDone(t)` and the resulting Room change flows back through the `tasks` StateFlow, re-rendering the list. This is the **unidirectional data flow** you'll see everywhere: `UI event → vm action → :core rule → WriteThrough → Room → StateFlow → recompose`.

#### Categories of write actions (what's available)

- **Tasks**: `addTask(...)` (returns the new `TaskItem`), `updateTask`, `toggleDone`, `setLater`, `deleteTask` (cascades to its cal_blocks + captures so realtime doesn't re-pull orphans), `setRecurrence` (re-runs `regenerateForTask` to realign future blocks).
- **Scheduling**: `scheduleTask` (the important one — see below), `unschedule`, `moveBlock`, `resizeBlock` (clamped 15–360 min), `blockTime` (a placeholder block).
- **Google Calendar**: `beginGoogleConnect()` (returns an OAuth URL to open in a Custom Tab), `syncCalendar()`, `disconnectCalendar(id)`.
- **Reminders** (device-local): `reminderOverride(taskId)` / `setReminderOverride`.
- **Focus / live session**: `startFocus`, `pauseFocus`, `resumeFocus`, `setTreatment`, `extendFocus`, `finishFocus(task, markDone)`, `cancelFocus`. These mutate the `LiveSession` in the store via `:core`'s `FocusTimer`.
- **Captures / reasons**: `saveCapture`, `saveReasonLog`, `deleteCapture`, `promoteCapture` (turns a capture into a task, preserving the capture).
- **Collections**: `upsertCollection`, `deleteCollection`, and item ops (`addCollectionItem`, `toggleCollectionItemPin/Done`, `updateCollectionItemBody`, `removeCollectionItem`, `renameCollection`, `recolorCollection`).
- **Tags & areas**: `upsertTag`/`deleteTag`/`ensureTag`/`renameTag`/`recolorTag`, `upsertLifeArea`/`deleteLifeArea`/`renameLifeArea`/`recolorLifeArea` (renames/deletes cascade across tasks).
- **Onboarding**: `onboarded` (getter off `graph`), `completeOnboarding(struggles, areas)`.
- **Settings**: `settings` StateFlow + `updateSettings { it.copy(...) }`.
- **Auth**: `signIn`/`signUp`/`magicLink`/`googleSignIn`/`resetPassword`/`changePassword`/`updateDisplayName`/`deleteAccount`/`signOut`, plus `currentEmail`/`currentName`/`hasPassword`.
- **Export**: `exportJson()` serialises an `ExportBundle` of every collection.

#### Three subtle behaviors worth internalizing

1. **`scheduleTask` is move-aware** (mirrors web `persistOrMove`):
   - First-time placement creates a block and does **not** bump `moveCount`.
   - Moving an existing block bumps `moveCount` **only if** date/time actually changed (keeps the slip detector honest).
   - Recurring tasks diff via `regenerateForTask` rather than blindly inserting a new horizon every tap.

2. **Collection item edits are serialized through a `Mutex`** and re-resolve the latest row first:
   ```kotlin
   private val collectionMutex = Mutex()
   private fun mutateCollection(id, transform) = launchWrite {
       collectionMutex.withLock {
           val latest = store.collections().first().firstOrNull { it.id == id } ?: return@withLock
           write?.upsertCollection(transform(latest))
       }
   }
   ```
   Because a collection is one JSONB row carrying all its items, two fast taps could otherwise persist a stale snapshot and drop items. **Gotcha**: if you add a new collection-level field, add it inside `transform` here, not via a separate upsert.

3. **In-VM derived state** that isn't persisted: `nudges` (combines tasks + captures + a dismissed set), `lastRecap` (the just-finished-session card on Today). These are local `MutableStateFlow`s, not Room.

#### Pitfall: `liveSession` and `settings.treatment`

`startFocus` re-enters the **same** task's live session without resetting it (a paused session stays paused), and seeds the treatment from persisted settings. `setTreatment` writes both the live session and `updateSettings`. If you add focus behavior, remember the live session lives in the store (synced) while UI prefs live in `SettingsStore` (device-local) — two different homes.

---

### `MainActivity` → `AppRoot` → `MainScaffold`

#### `MainActivity` (the only Activity)

`MainActivity.kt` is thin. In `onCreate` it:
1. `enableEdgeToEdge()`,
2. handles the launch intent's deep link (`handleAuthOrCalendar`),
3. requests `POST_NOTIFICATIONS` on Android 13+,
4. registers the FCM token whenever a session becomes `Authenticated`,
5. schedules the background `SyncWorker`,
6. `setContent { AppRoot(graph) }`.

**Deep-link routing** is the load-bearing detail. `handleAuthOrCalendar` is called from both `onCreate` and `onNewIntent`:

```kotlin
private fun handleAuthOrCalendar(intent: Intent?) {
    val data = intent?.data
    if (data?.scheme == "unstuck" && data.host == "calendar-callback") {
        val code = data.getQueryParameter("code"); val state = data.getQueryParameter("state")
        if (code != null && state != null) {
            lifecycleScope.launch { graph.coordinator?.completeGoogleConnect(code, state) }
            return
        }
    }
    intent?.let { graph.provider?.client?.handleDeeplinks(it) }   // Supabase PKCE / magic-link
}
```

So `unstuck://calendar-callback?code&state` completes the Google Calendar OAuth, and everything else (`unstuck://auth-callback`) is handed to Supabase's PKCE handler. The Custom Tab that opens the Google consent URL is launched from `CalendarScreen` (see below).

#### `AppRoot` — theme owner + top-level state machine

`AppRoot.kt` does two jobs. First, it **owns the theme** so any Settings change re-themes the whole app instantly:

```kotlin
val settings by vm.settings.collectAsStateWithLifecycle()
val dark = settings.theme == ThemePref.DARK ||
           (settings.theme == ThemePref.SYSTEM && isSystemInDarkTheme())
UnstuckTheme(dark = dark, accent = settings.accent, fontScale = settings.fontScale) {
    ...
}
```

Second, it is the top-level **state machine**:

```
!vm.configured                    → SetupScreen (asks for SUPABASE_ANON_KEY)
else → when (vm.authed) {
    null  → LoadingScreen (spinner)
    false → AuthScreen(vm)
    true  → MainScaffold(vm)
}
```

`fontScale` from settings is multiplied into `LocalDensity` inside `UnstuckTheme`, so "Larger type" / "Density" affect every `sp` size globally.

#### `MainScaffold` — the navigation model

`MainScaffold.kt` is a **hand-rolled navigation system** — there is no Jetpack Navigation library, no `NavHost`. State is plain Compose state:

```kotlin
var tab by rememberSaveable { mutableStateOf("today") }        // 4 bottom tabs
val stack = remember { mutableStateListOf<Route>() }           // full-screen overlays
var sheet by remember { mutableStateOf<Sheet?>(null) }         // Avatar / Areas menus
var showNewTask by remember { mutableStateOf(false) }          // FAB sheet
var focusTask by remember { mutableStateOf<TaskItem?>(null) }  // focus overlay
var onboarding by remember { mutableStateOf(!vm.onboarded) }
```

There are three independent navigation layers, drawn bottom-to-top:

```
┌─────────────────────────────────────────────┐
│  focusTask overlay (FocusScreen)              │ ← topmost
├─────────────────────────────────────────────┤
│  sheet (AvatarMenu / AreasMenu) + NewTaskSheet│
├─────────────────────────────────────────────┤
│  stack.last() overlay  (Detail/Collection/    │
│      Insights/Settings/SettingsSub/Palette)   │
├─────────────────────────────────────────────┤
│  tab content (Today/Tasks/Calendar/Lists)     │ ← base
│  + BottomNavBar (with CoralFab)               │
└─────────────────────────────────────────────┘
```

- **Tabs** are defined as `NavSpec` list (`NAV`): today, tasks, calendar, lists. `BottomNavBar` renders them with a center FAB gap; `onFab` opens `NewTaskSheet`. Switching tab clears the overlay stack.
- **Routes** are a `sealed interface Route` (`Detail(taskId)`, `Collection(id)`, `Insights(deep)`, `Settings`, `SettingsSub(section)`, `Palette`). `push(r)`/`pop()` mutate the stack; only `stack.lastOrNull()` is rendered.
- **Sheets** (`Sheet.Avatar`, `Sheet.Areas`) and the FAB's `NewTaskSheet` ride on `ModalBottomSheet`.
- **Focus** is its own overlay keyed by `focusTask`; `FocusScreen` is always given the **fresh** task (`tasks.firstOrNull { it.id == t.id } ?: t`) so edits propagate.

**Back handling** is explicit and layered — read it carefully before changing navigation:

```kotlin
BackHandler(enabled = focusTask != null) { focusTask = null }
BackHandler(enabled = focusTask == null && !sheetOpen && stack.isNotEmpty()) { pop() }
BackHandler(enabled = focusTask == null && !sheetOpen && stack.isEmpty() && tab != "today") { tab = "today" }
```

Top layer wins. Modal sheets intercept their own back, so `MainScaffold` doesn't handle them. **Gotcha**: leaving focus via back (or "← Out") keeps the live session **running** — it's not a discard.

If `!vm.onboarded`, `OnboardingScreen` short-circuits the whole scaffold and renders alone until `onDone`.

---

### The `:design` system

#### Color: oklch → sRGB (`color/Oklch.kt`, `theme/Theme.kt`)

The web brand tokens are authored in `oklch` (which Compose can't represent natively), so `Oklch.kt` ports the standard Björn Ottosson oklab matrix to produce exact `Color`s:

```kotlin
fun oklch(l: Double, c: Double, h: Double, alpha: Double = 1.0): Color   // l 0–1, c chroma, h degrees
fun hexColor(hex: String): Color                                          // for the few exact-hex tokens
```

`UnstuckColors` (an `@Immutable data class`) is the palette. Two full instances, `light` and `dark`, are defined as companion vals. Backgrounds/ink/lines are exact hex in light mode; accents and the whole dark palette are oklch. Key tokens:

- Surfaces: `bg`, `bg2`, `surface`
- Text: `ink` (primary) → `ink2` → `ink3` → `ink4` (faintest)
- Lines: `line`, `line2`
- Accent ramps: `primary`/`primarySoft`/`primaryDeep` (indigo), `coral`/`coralSoft`/`coralDeep`, `green`/`blue`/`amber` (each with `Soft` + `Ink`), `violet`, `red`
- `isDark` flag

Two resolver helpers live on the palette:
```kotlin
fun areaColor(token: String?): Color   // "indigo"→primary, "coral"→coral, etc.; else ink4
fun areaSwatch(color: Color): Color    // lerp(surface, color, 0.32f) — soft chip fill
```
`areaColor` resolves a **token string** (stored on areas/collections/tags). To resolve a task's life-area **name** to a color you go through `ui/components/Common.kt`'s `areaColorFor(name, areas, colors)`, which finds the area by name then resolves its token.

**Access pattern inside composables**: `val c = UTheme.colors` then `c.coral`, `c.ink2`, etc. `UTheme.colors` reads `LocalUnstuckColors.current`, which `UnstuckTheme` provides.

**Accents**: three palettes (`AccentPalette.INDIGO_CORAL` default, `PERIWINKLE_ROSE`, `FOREST_AMBER`). `withAccent` overrides the primary/coral ramps. Settings → Interface → Accent maps keys (`indigo`/`rose`/`forest`) to these.

`Radius` object: `sm 8 / md 14 / lg 18 / xl 24 / pill 999`.

#### Type (`theme/Type.kt`)

Three bundled font families, ported from web:
- `UFont.sans` = **Geist** (variable weight, registered at 400/500/600/700)
- `UFont.serif` = **Instrument Serif** (display headings, used italic)
- `UFont.mono` = **IBM Plex Mono** (timers, eyebrow labels)

Convenience builders return `TextStyle`s on the fly:
```kotlin
UFont.sans(14, FontWeight.SemiBold)
UFont.serifItalic(28)      // the big serif headers you see on every screen
UFont.mono(11, FontWeight.Medium)
```
A Material `UnstuckTypography` is also defined and handed to `MaterialTheme`, but in practice screens call `UFont.*` directly far more than they use Material text styles.

#### Shared components

**`component/Components.kt`** (brand primitives):
- `UButton(title, kind, enabled, fill, leadingIcon, onClick)` — the pill button. `enum ButtonKind { CORAL, DARK, OUTLINED, GHOST, DANGER, TEXT, PRIMARY }`. Convention across the app: **CORAL = the focus/primary accent action**, **DARK = submit (Add / Sign in / Save)**, `DANGER` red for destructive, `OUTLINED`/`GHOST`/`TEXT` as named. `PRIMARY` is an alias of CORAL. `fill = true` stretches full width.
- `FilterPill(label, selected, dotColor?, onClick)` — segmented filter pill, dark-ink when selected, optional leading color dot. Used for area/tag filters everywhere.
- `Chip`, `SectionLabel` (the uppercase mono eyebrow — `SECTION · LABEL`), `Card` (flat white surface, 1px hairline, no elevation), `LabeledRow`, `StatCard` (Insights), `ItemRow` (collection item), `RadioOptionRow` (Reflect), `AreaDot`/`AreaDotColor`/`ColorChip` (the color swatches).

**`component/Chrome.kt`** (Material-3 chrome):
- `AppBar(title, leading, trailingSearch, onLeading, onSearch, avatarInitials, onAvatar)` — `enum Leading { MENU, BACK, NONE }`. Transparent over `bg`, 40dp round icon buttons.
- `NavSpec(key, label, icon)` + `BottomNavBar(items, activeKey, onSelect, onFab)` — bottom nav with a `bg2` pill active indicator and the FAB floating in a center gap.
- `CoralFab(onClick)` — 56×56 rounded-square coral FAB with a `+`.
- `SheetHandle` (32×4 drag handle) and `SheetScrim` (the indigo-tinted `0x4D141228` modal scrim — pass it as `scrimColor` on every `ModalBottomSheet`).

**`component/Controls.kt`**: `MdField` (outlined field with a notched floating label), `MdToggle` (green-when-on switch), `MdSegment` (segmented control — used for Day/Week/Month, Theme, etc.).

**`component/Mark.kt`**: `Orbit(size, white, coral)` — the brand mark drawn on a `Canvas`: an ink anchor dot + a ~270° ring with a gap at 3 o'clock + a coral satellite in the gap. Appears on Today's header, Auth, Onboarding, the focus ring, and empty states.

`ui/tasks/SelectableChip.kt` lives in `:app` (not `:design`) because it predates the split, but it's a general primitive used by recurrence/estimate/treatment pickers. **Gotcha to know**: its selected fill is `c.ink`, which inverts to near-white in dark mode, so the label uses `c.bg` (the inverse) to stay legible. Pass an `accent` color for a mid-tone fill where white text reads.

---

### Per-screen tour

Every screen takes `vm: AppViewModel` plus navigation callbacks from `MainScaffold`, collects the flows it needs with `collectAsStateWithLifecycle()`, computes derived view-state in memory using `:core` helpers (`visibleTasks`, `pickStartNext`, analytics functions), and renders.

#### `today/TodayScreen.kt`
The home tab. One `LazyColumn`. Header = `Orbit` + avatar; a serif greeting (`greeting(now)` + "Unstuck."); a "This week · Xm focused" pill linking to Insights. Then optional cards in order: **recap** (`vm.lastRecap`, the "You did the thing." card with `vm.dismissRecap()`), **nudges** (`vm.nudges`, slip/capture prompts with `vm.dismissNudge`/`vm.promoteCapture`). The body shows a **Start-Next hero** (`pickStartNext(tasks, blocks, liveId, areaFilter)` → tap fires `onStartFocus`), a Today/Backlog filter-pill row (area pills + an amber Backlog toggle), an inline **LiveSessionCard** when a session is running/paused (with `vm.pauseFocus`/`vm.resumeFocus` and a 1-second ticker `LaunchedEffect` to animate the ring), and the task rows. Lists come from `visibleTasks(TaskListView.TODAY/BACKLOG, …)`.

#### `tasks/TasksScreen.kt`
The triage list. `AppBar` (menu opens `AreasMenu`). A tab row over `TAB_ORDER = [BACKLOG, ALL, TODAY, UPCOMING, LATER, COMPLETED]`, each tab a per-view accent pill. An area filter pill row (`activeArea` is owned by `MainScaffold` and passed in, so the Areas menu can deep-link a filter), and a removable tag filter. The list is `visibleTasks(view, tasks, blocks, now, activeArea=…, activeTag=…)`. **Note**: TODAY is intentionally area-agnostic (web parity) — the area filter only bites on other tabs. Rows tap → `onOpen(task)` → `Route.Detail`.

#### `tasks/NewTaskSheet.kt`
The FAB sheet. Scheduler-first, scrollable + `imePadding`. Fields: name; **When** (Today/Tomorrow/Pick date/Later); **Time** (free-slot chips from `findFreeSlotsForDate`, auto-picks the first slot unless the user/prefill chose one; shows a conflict warning via `findConflicts`); **Estimate** presets + custom dialog; **Remind me** lead; **Area** pills; **First step**; **Tags** (`TagPicker`); **Recurrence** (`RecurrenceEditor`); and **Capture drafts** that auto-save on submit. Submit calls `vm.addTask(...)`, then `vm.setReminderOverride`, `vm.scheduleTask`, and `vm.saveCapture` per draft. It can be prefilled (`prefillDate`/`prefillTime`) from a calendar tap. **No priority picker** — the web/DB don't surface one (see the redesign-source-of-truth memory).

#### `tasks/TaskDetailSheet.kt` (`TaskDetailScreen`)
Full-screen `Route.Detail`. Inline-editable name + first physical action (`EditableText` toggles to a `BasicTextField` and commits on ✓). Focus/Schedule/Mark-done buttons (`onStartFocus`, `findFreeSlots`+`scheduleTask`, `toggleDone`). Cards for estimate/area/schedule/status, recurrence (`setRecurrence`), tags (`TagPicker`), session history, and capture management (`promoteCapture`/`deleteCapture`/add). Delete confirms then cascades.

#### `calendar/CalendarScreen.kt` + `calendar/DayGrid.kt`
`CalendarScreen` is `MdSegment(Day/Week/Month)` + a `CalendarSyncBar` (connect/sync/disconnect Google — connect calls `vm.beginGoogleConnect()` and opens the returned URL in a `CustomTabsIntent`; the callback is handled in `MainActivity`). **Week** and **Month** views are in this file (a positioned hour grid; a focus-density heatmap). **Day** is the rich one in `DayGrid.kt`: `DayGridScreen` renders an absolutely-positioned hour grid with a "NOW" line, a bottom **unscheduled tray**, and **drag-to-schedule** (`detectDragGesturesAfterLongPress` → window-coord math → snapped HH:MM → `vm.scheduleTask` for tray items, `vm.moveBlock` for existing blocks). Tapping empty space fires `onCreateAt(date, time)` → prefilled `NewTaskSheet`. Tapping a block opens `CalBlockEditSheet` (reschedule via free-slot chips → `moveBlock`, resize → `resizeBlock`, `unschedule`).

#### `focus/FocusScreen.kt` + `CaptureSheet.kt` + `ReflectSheet.kt`
The dark-indigo focus overlay. `LaunchedEffect(task.id) { vm.startFocus(task) }`, a 1-second ticker drives the timer, and `LaunchedEffect`s bind the `FocusTimerService` (ongoing notification) + `PausedCheckinScheduler` to the live session — these persist after you leave the screen and are torn down only by terminal Done/End actions. Treatment chips switch `AMBIENT`/`COCKPIT`/`MONK` (`vm.setTreatment`). Controls: Capture (→ `CaptureSheet`), Pause/Resume (`vm.pauseFocus`/`resumeFocus`, optionally `PauseReasons` → `vm.saveReasonLog`), Done (`vm.finishFocus(markDone=true)`), plus "Save for later" (pause + exit) and "End for now" (`finishFocus(markDone=false)`). Both end paths show `ReflectSheet` (a centered "How did that land?" radio dialog). `CaptureSheet` is a `ModalBottomSheet` with tag chips → `vm.saveCapture`.

#### `collections/`
`CollectionsScreen` is a 2-column `LazyVerticalGrid` of collection cards with search and a `+ New` button (`NewCollectionSheet` → `vm.upsertCollection` → opens the new collection). `CollectionDetailScreen` is `Route.Collection`: a `ColorChip` + rename, recolor swatches, an autofocused add-item field (Enter keeps adding), pinned/all item sections (`CollItemRow` with done/pin/remove/edit → the `vm.*CollectionItem*` actions), and delete-with-confirm. `NewCollectionSheet` is the create sheet.

#### `insights/InsightsScreen.kt`
`Route.Insights(deep)`. Two modes via `MdSegment` (Report / Deep dive) and a Week/Month/All range. It pulls `sessions`/`tasks`/`lifeAreas`/`captures`/`reasonLogs`, filters to a calendar-anchored window, and renders almost entirely from `:core/logic` analytics functions (`calibrationDots`, `calibrationHitRate`, `slipping`, `weekdayAreaHours`, `interruptionBins`, `topInsights`, `pauseAnatomy`, `reEntryDistribution`, `captureBreakdown`, `timeOfDayHeatmap`). Below a `REAL_DATA_THRESHOLD` of 5 sessions it shows `—` placeholders + a `ThresholdNote`. Local chart composables: `StackedBars`, `Histogram`, `LabeledBar`, `Heatmap`, plus `StatCard` from `:design`.

#### `settings/SettingsScreen.kt`
`SettingsHub` (`Route.Settings`) is a list of sections → `Route.SettingsSub`. `SettingsSubScreen` renders one `SettingsSection` (`ACCOUNT/FOCUS/SOUND/A11Y/INTERFACE/BACKUP/AREAS/TAGS`). Toggle/segment rows write through `vm.updateSettings { it.copy(...) }`. `AccountContent` handles name/password/export (via `CreateDocument` launcher writing `vm.exportJson()`)/delete/sign-out, with reauth before password change. `AreasContent` and `TagsContent` are full CRUD with inline rename, recolor dropdowns, and add fields, all backed by the `vm.*LifeArea`/`*Tag` actions.

#### `onboarding/OnboardingScreen.kt`
A 4-step card flow (welcome → pick areas → first task → pick focus treatment). On finish: persists the treatment, optionally `addTask`, then `vm.completeOnboarding(struggles, areas)` (which seeds areas — single source, no double-seed) and `onDone()`. Skip calls `completeOnboarding(emptyList())`.

#### `auth/AuthScreen.kt`
Shown when `authed == false`. Toggles sign-in/sign-up; `MdField`s for name/email/password; a Google button with the official "G". A local `run { }` helper drives `busy`/`message` and calls the `vm.signIn/signUp/googleSignIn/magicLink/resetPassword` suspend actions, surfacing `AuthOutcome.Error.message`.

#### `palette/CommandPalette.kt`
`Route.Palette`, opened from the AppBar search icon. A search field over an in-memory result list: navigation actions, matching open tasks (→ `onOpenTask`), and matching captures. Selecting routes via the callbacks `MainScaffold` provides.

#### `components/`
Shared `:app`-level helpers and sheets used by multiple screens:
- `Common.kt` — `areaColorFor`, `ageDays`, `dateEyebrow`, `greeting`, plus a reusable `TaskRow`/`PriorityChip`/`EmptyState`/`ScreenHeader`.
- `AvatarMenu.kt` — the account `ModalBottomSheet` (Insights / Settings / Sign out).
- `AreasMenu.kt` — the mobile equivalent of the web left-rail Areas section; picking an area sets the Tasks filter.
- `TagPicker.kt` — selected `#tag` chips + a search-filtered dropdown with inline "Create" (`vm.ensureTag`).
- `RecurrenceEditor.kt` — Never/Daily/Weekly/Monthly with a weekday picker; emits `Recurrence?`.

---

### How to add a new screen (worked example: a "Streaks" overlay)

Say you want a full-screen Streaks view reachable from the avatar menu. The work is mechanical because navigation is centralized in `MainScaffold`.

1. **Create the screen** `ui/streaks/StreaksScreen.kt`, following the house style:
   ```kotlin
   @Composable
   fun StreaksScreen(vm: AppViewModel, onBack: () -> Unit) {
       val c = UTheme.colors
       val sessions by vm.sessions.collectAsStateWithLifecycle()
       Column(Modifier.fillMaxSize().background(c.bg)) {
           AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
           // ... compute a streak from sessions in-memory, render with StatCard / SectionLabel
       }
   }
   ```
   Read flows off `vm`, never construct your own ViewModel or touch the store directly. If you need new derived logic, add a pure function to `:core` and call it here (don't bake business rules into the composable).

2. **Add a `Route`** in `MainScaffold.kt`:
   ```kotlin
   private sealed interface Route { /* … */ data object Streaks : Route }
   ```

3. **Render it** in the overlay `when (route)` block:
   ```kotlin
   Route.Streaks -> StreaksScreen(vm, onBack = ::pop)
   ```

4. **Push it** from wherever — e.g. add a row to `AvatarMenu` and wire `MainScaffold`'s `Sheet.Avatar` branch to `{ sheet = null; push(Route.Streaks) }`.

That's it — back handling already covers it (the second `BackHandler` pops any non-empty stack), and the overlay is automatically inset with `systemBarsPadding()` and drawn above the tab content. If instead you want a **new bottom tab**, add a `NavSpec` to `NAV` (and a `"streaks" -> StreaksScreen(...)` branch in the tab `when`), but note `BottomNavBar` hard-codes exactly four cells around the FAB gap — adding a fifth tab requires editing `BottomNavBar` itself.

If you need a new write, add a `fun …() = launchWrite { write?.… }` method to `AppViewModel` (apply any rule from `:core` first), and the new state flows back automatically.

### General gotchas to keep in mind

- **Never instantiate `AppViewModel` yourself** — it's created once in `AppRoot` and passed down. New screens take `vm` as a parameter.
- **`write`/`auth`/`coordinator` are nullable.** On unconfigured builds writes are no-ops and you'll see `SetupScreen`. Don't `!!` them.
- **Navigation is plain state, not a `NavController`.** Routes are in-memory and not deep-link addressable; only `tab` is `rememberSaveable`. The overlay `stack` is `remember` (lost on process death) — fine because everything is re-derivable from the store.
- **Leaving Focus keeps the session alive.** The notification + paused check-in are owned by the live session, not the screen. Only Done/End/cancel tears them down.
- **Today is area-agnostic by design** (both `TodayScreen` and `TasksScreen` TODAY view). Don't "fix" it by applying the area filter.
- **Collection writes go through `mutateCollection`'s mutex** — bypassing it risks dropping items typed during a burst.
- **Color resolution has two paths**: `c.areaColor(token)` for stored tokens, `areaColorFor(name, areas, c)` for a task's life-area name. Use the right one.
- **`SelectableChip` selected text uses `c.bg`** to survive the ink-inverts-in-dark-mode problem; pass `accent` for mid-tone fills.
