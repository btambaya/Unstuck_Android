# iOS Rebuild Spec — Architecture & App Shell

**Area:** Architecture & app shell (DI, MVVM state holder, navigation, theme/auth gating, entry points, the offline-first sync engine)
**Reference client:** Android (`unstuck_android`), v0.4.29, versionCode 42
**Target:** SwiftUI rebuild from scratch. The existing `unstuck_ios` Swift app is being discarded — do **not** assume any of its code is correct; treat this spec + the Android Kotlin as the source of truth. (The Android app was itself originally transcribed from the old iOS app, so some Swift naming below intentionally mirrors what the Kotlin comments reference, e.g. `WriteThrough.swift`, `Hydrator.swift` — but rebuild, don't resurrect.)

This is the **shell** spec. It defines: process bootstrap, dependency wiring, the single app-wide state object, navigation topology, theme/auth gating, the offline-first sync engine, the data store, and the platform-surface adapters. It does **not** spec the contents of individual feature screens (Today, Tasks, Calendar, Collections, Focus, Settings, Insights, Onboarding) — those are separate area specs — but it defines every entry point, callback, and piece of state those screens hang off.

---

## 0. Backend constants (shared, do not invent)

- **Supabase project ref:** `uaxfteluwctrlgwmmfzi`
- **Default URL:** `https://uaxfteluwctrlgwmmfzi.supabase.co` (overridable)
- **Anon key:** injected at build time, not in source. Android reads it from `secrets.properties` → `BuildConfig.SUPABASE_ANON_KEY`. iOS equivalent: an `xcconfig` / Info.plist value or a gitignored `Secrets.swift`. If empty → app is "not configured" and shows the Setup screen (see §6).
- **Auth deep-link scheme:** `unstuck://auth-callback` (PKCE flow).
- **Google Calendar OAuth redirect:** `https://unstuck-602.pages.dev/calendar-callback` (HTTPS bounce page that forwards `?code&state` to `unstuck://calendar-callback`). This exact URL must be registered on the Google Cloud Console Web OAuth client. Google rejects custom schemes on a Web client, so the bounce page is mandatory.
- **Feedback flag:** `FEEDBACK_ENABLED` (Android `BuildConfig.Boolean`, currently `true` for beta). iOS: a compile-time flag.

All table names, column names, and enum string values below are **canonical across web + Android + iOS** — they must match byte-for-byte.

---

## 1. Module / package layout (Gradle → SPM)

Android uses 5 Gradle modules with strict dependency direction. Mirror them as Swift packages (SPM targets) so the pure-logic core stays headless and unit-testable without UIKit/Supabase.

| Android Gradle module | Contents | iOS SPM target |
|---|---|---|
| `:core` | Pure Kotlin/JVM. Domain models (`Models.kt`, `Enums.kt`), pure logic (`logic/` — `FocusTimer`, `VisibleTasks`, `PickStartNext`, `Analytics`, `Recurrence`, `TaskMutations`, `TaskBucket`, `FreeSlots`, `CalBlockKind`, `GoogleSyncMapping`, `AuthErrors`, `Uuid`), `time/Time.kt`. **No Android, no Supabase imports.** JUnit tests mirror web Vitest + iOS XCTest. | `UnstuckCore` — pure Swift, no UIKit/SwiftUI/Supabase. Codable models, pure functions, XCTest parity suite. |
| `:design` | Compose Material3 theme, OKLCH color tokens (`Oklch.kt`), components. | `UnstuckDesign` — SwiftUI theme env, OKLCH→Color, shared components. |
| `:data` | Room schema (`Entities.kt`, `Daos.kt`, `UnstuckDatabase.kt`), `LocalStore` facade, outbox, live_session. | `UnstuckData` — GRDB schema + `LocalStore` (see §9). |
| `:sync` | supabase-kt wiring + the offline-first engine (everything in §8). | `UnstuckSync` — supabase-swift wiring + engine. |
| `:app` | SwiftUI screens, navigation, entry points, platform surfaces. | The app target. |

**Critical constraint (cite README):** every `:core` logic port has a test mirroring the same web + iOS cases — "all three clients agree on bucketing, ranking, recurrence, the focus timer, analytics, and the Google-Calendar mapping." The iOS rebuild **must** carry the same `UnstuckCoreTests` parity suite. `:core` tests run with `TZ=UTC` for determinism (Android uses `-Duser.timezone=UTC`). Run the iOS core tests with `TZ=UTC` too (see §10 UTC gotcha).

---

## 2. Bootstrap & dependency injection (`AppGraph.kt` → `AppGraph` / `@main`)

Android uses **manual DI** — one `AppGraph` instance per process, created in `UnstuckApp.onCreate()`. **No Hilt.** iOS: a single composition-root object created in the `@main App` struct's init, held as a `@StateObject` and injected via `.environmentObject` (or passed explicitly). **Do not** reach for a heavy DI framework — manual is the spec.

### `AppGraph` responsibilities (port exactly)

```
class AppGraph(context):
  configured: Bool            = anonKey.isNotEmpty()           // gates Setup screen
  appContext                                                    // iOS: not needed (use shared singletons)
  scope: CoroutineScope(SupervisorJob + Dispatchers.Default)    // iOS: a long-lived Task / actor + structured concurrency
  pendingDeepLink: MutableStateFlow<String?>(nil)               // notification/URL deep-link inbox
  pendingPasswordRecovery: MutableStateFlow<Bool>(false)        // recovery-email landed
  db: UnstuckDatabase                                           // Room → GRDB
  store: LocalStore(db)
  provider: SupabaseClientProvider?   = configured ? build : nil
  coordinator: SyncCoordinator?       = provider.map { SyncCoordinator(it, store, ctx, scope) }
  appPrefs                            // SharedPreferences "unstuck.app" → UserDefaults suite
  onboarded: Bool  (get/set persisted)                         // key "onboarded"
  settings: SettingsStore                                       // device-local prefs
  start():  coordinator?.start()                               // begins auth observation
```

**iOS mapping:**
- `pendingDeepLink` / `pendingPasswordRecovery`: `@Published` properties on an `ObservableObject` (or `CurrentValueSubject`). These are a **mailbox**: external events (push tap, universal/custom URL open, recovery email) write a value; the router reads + consumes it.
- `scope`: replace the `CoroutineScope` with structured Swift Concurrency. The coordinator's long-running auth-observation loop is a detached `Task` owned by the graph; cancel it on teardown. Do **not** spawn unstructured `Task {}` for fire-and-forget writes without a parent owner — use a dedicated actor or a `TaskGroup` tied to the graph's lifetime so a backgrounded write completes (see the `leaveCollection` gotcha in §7).
- `appPrefs` / `settings`: `UserDefaults` (app group suite if the widget needs read access — see §11).

**Ordering (load-bearing, cite `UnstuckApp.onCreate`):**
```
graph = AppGraph(this)
graph.start()                       // 1. start auth observation FIRST
NotificationChannels.ensureAll()    // 2. iOS: request notif authorization / categories
NotificationLog.init()              // 3. restore in-app notification log
ReminderScheduler.observe()         // 4. begin watching cal_blocks → schedule local reminders
```
On iOS this maps to the `App` init + an `.onAppear`/`.task` on the root view. The auth observer must start before the UI subscribes to `authed`, or the first emission is missed.

---

## 3. Entry points (`UnstuckApp.kt` + `MainActivity.kt` → `@main App` + scene/URL handling)

Android splits this across `Application` (process-level, one-time) and `Activity` (window-level, handles intents/permissions/deep-links). iOS collapses both into the `App` struct + scene modifiers. Reproduce **all** of this behavior:

### 3.1 One-time process setup (from `UnstuckApp`)
Build the graph, start the coordinator, ensure notification categories, restore the notification log, start the reminder observer. (See §2 ordering.)

### 3.2 Window / launch handling (from `MainActivity`)

1. **Edge-to-edge** — `enableEdgeToEdge()`. iOS handles safe areas natively; respect them like Android does (`statusBarsPadding` / `navigationBarsPadding` / `systemBarsPadding` are applied per-region in MainScaffold — see §5).

2. **Auth / calendar deep-link on cold launch ONLY** (`if (savedInstanceState == null) handleAuthOrCalendar(intent)`). The guard exists because Android re-delivers the launch intent on config change (rotation/theme/locale) which would re-fire the deep link. iOS: handle the launch URL once in `.onOpenURL` / `onContinueUserActivity`; SwiftUI does not re-deliver on its own, but **guard against double-handling** the same URL (e.g. dedupe by URL string) since the calendar code-exchange is not idempotent (it would re-run the exchange / re-open the task).

3. **Notification permission** — Android requests `POST_NOTIFICATIONS` on API 33+. iOS: `UNUserNotificationCenter.requestAuthorization([.alert, .sound, .badge])`, ideally after onboarding, not on first cold launch.

4. **Exact-alarm prompt** (`maybePromptExactAlarm`) — **see §10 gotcha.** iOS has no exact-alarm permission, so this entire flow is **dropped** on iOS; replaced by the standard `UNUserNotificationCenter` scheduling, which already fires at a precise time without a special permission. Do not build any "request exact alarm" UI.

5. **FCM token registration on session** — Android collects `auth.sessionStatus`; on `Authenticated` → `registerFcmToken`; on sign-out → clear notification log + `settings.clearUserContent()`. iOS: register the **APNs** token (see §11). Same trigger: register once a session exists (covers first sign-in + relaunch-while-signed-in via the immediate StateFlow emission), and on sign-out clear the per-user device-local content so the next account on the device starts clean.

6. **Schedule periodic background sync** — `SyncWorker.schedule()`. iOS: register a **BGTaskScheduler** `BGAppRefreshTask` (see §11).

7. **Set content** — `setContent { AppRoot(graph) }`. iOS: `WindowGroup { AppRoot().environmentObject(graph) }`.

### 3.3 `handleAuthOrCalendar(intent)` URL routing (port the branch order exactly)

Given an incoming URL/intent, route in this priority:

1. `unstuck://calendar-callback?code&state` → `coordinator.completeGoogleConnect(code, state)`. Return.
2. Notification "Capture" action extra → `pendingDeepLink = "capture"`. Return.
3. URL contains `type=recovery` (case-insensitive) → `pendingPasswordRecovery = true` (then fall through so Supabase establishes the recovery session via its deep-link handler).
4. `unstuck://{host}` where host ∈ {`task`, `today`, `focus`, `collections`} → `pendingDeepLink = <full url string>`. Return.
5. Otherwise → hand to the Supabase SDK's deep-link handler (`client.handleDeeplinks(intent)`) to complete the PKCE auth callback. iOS: supabase-swift exposes session handling for the OAuth/magic-link redirect — wire the `unstuck://auth-callback` URL to it.

`onNewIntent` (warm deep-link) → re-run `handleAuthOrCalendar`. iOS: `.onOpenURL` already fires for both cold and warm; just ensure step-2 cold-launch dedupe (3.2.2) doesn't suppress a genuine warm link.

---

## 4. The single app-wide state holder (`AppViewModel.kt` → `AppModel`)

This is the heart of the shell. Android's `AppViewModel(graph): ViewModel` is **the single state object** — every screen receives it. It exposes (a) every synced collection as a reactive stream off the local store, (b) auth state, (c) device-local settings, and (d) **every write action** (which apply `:core` mutation rules, then go through the sync engine's `WriteThrough`). Screens compose these reactive collections with `:core` pure functions (`visibleTasks` / `pickStartNext` / `analytics`) **in memory** — same model as web + the old iOS app.

iOS: a single `final class AppModel: ObservableObject` (or `@Observable` macro on iOS 17+), created in `AppRoot` from the graph, passed down via `.environmentObject`. **One instance.** Do not split into per-feature view models that each open their own store subscriptions — the architecture is deliberately one observable object that screens read slices off.

### 4.1 Reactive synced collections (read side)

Android wraps each `LocalStore` Flow into a `StateFlow` via `stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())`. The `WhileSubscribed(5000)` means the upstream Room query stays hot for 5s after the last collector leaves (cheap re-subscribe on tab switch).

Expose all of these (`@Published` arrays, fed by GRDB `ValueObservation` — see §9):

| Property | Type | Source table |
|---|---|---|
| `tasks` | `[TaskItem]` | `tasks` |
| `blocks` | `[CalBlock]` | `cal_blocks` |
| `sessions` | `[Session]` | `sessions` |
| `captures` | `[Capture]` | `captures` |
| `reasonLogs` | `[ReasonLog]` | `reason_logs` |
| `collections` | `[ItemCollection]` | `collections` |
| `tags` | `[TagRow]` | `tags` |
| `lifeAreas` | `[LifeArea]` | `life_areas` |
| `connections` | `[CalendarConnection]` | `calendar_connections` |
| `liveSession` | `LiveSession?` | `live_session` (single row) |
| `pendingCount` | `Int` | outbox row count (sync-status badge) |

Plus derived/published state:
- `authed: Bool?` — **null until auth resolves**, then true/false. Android maps `auth.sessionStatus` to `is Authenticated`, `stateIn(Eagerly, null)`. The `null` tri-state is load-bearing: `AppRoot` shows a loading spinner while `null`. iOS: `@Published var authed: Bool? = nil`, set from the supabase-swift auth state stream.
- `configured: Bool` (from graph).
- `settings: SettingsState` (`@Published`, see §12).
- `nudges: [Nudge]` — derived from `tasks` + `captures` + dismissed set (see §4.4).
- `inboxCaptures: [Capture]` — captures not archived, newest first.
- `archivedCaptureIds: Set<String>`.
- `lastRecap: RecapState?` — most-recent session-end recap card.
- `notifications` / `notifUnread` — in-app notification log + unread badge (from `NotificationLog`, §11).
- `currentEmail` / `currentName` / `hasPassword` — auth identity getters (computed off the auth client).
- `pendingDeepLink` / `pendingPasswordRecovery` — proxied from the graph.

### 4.2 Write actions (the full surface — port every one)

Every mutator follows the same pattern: apply the relevant `:core` pure mutation, then call `WriteThrough` (optimistic local write + enqueue server op). Android wraps each in `launchWrite { ... }` (a `viewModelScope.launch`). iOS: each is an `async` method run on a serialized executor, **or** a sync method that kicks a `Task` on the model's owned scope. Use the model's structured scope, not a detached `Task`, except where noted (`leaveCollection`).

**Tasks:** `addTask(name, estimateMin=25, priority?, lifeArea?, tags?, intentWhen?, intentThen?, firstPhysicalAction?, recurrence?, later=false, sourceCollectionId?, sourceItemId?, dueAt?) -> TaskItem` (stamps `createdAt`/`updatedAt` = `isoNow()`, fresh UUID, `name.trim()`); `updateTask`; `toggleDone` (applies `applyCompletion` from `:core`; on done-transition of a promoted shared-collection task → `share.taskDone(...)`); `setLater`; `deleteTask(id)` (cascade-deletes its cal_blocks + captures first so realtime doesn't pull orphans back — **port the cascade**); `setRecurrence(task, recurrence?)` (re-runs `regenerateForTask` and applies the resulting `toDelete`/`toUpsert` plan).

**Scheduling (cal blocks):** `scheduleTask(task, date, startTime)` — **this is intricate, port the exact branching** (see `AppViewModel.scheduleTask`, lines 177–211):
- Recurring task → `regenerateForTask` diff (apply `toDelete`/`toUpsert`), **plus** guarantee the user's chosen slot is materialized when the horizon regen skips it (today / off-pattern), **plus** bump `moveCount` only if the anchor (earliest existing block) date/time actually changed.
- Non-recurring → if a block exists, update in place + bump `moveCount` only on real change; else create a new `TASK` block, no bump.
- First-time placement never bumps `moveCount`; moves bump it (so the slip detector stays honest). This is the web `persistOrMove` contract.
- `unschedule(blockId)`; `moveBlock(block, date, startTime)` (bump owning task's `moveCount` on real move); `resizeBlock(block, durationMinutes)` (coerce `15…360`); `blockTime(date, startTime, durationMinutes, label)` (creates a `PLACEHOLDER` block with `taskId = "placeholder"`).

**Google Calendar:** `beginGoogleConnect() async -> String?` (returns the authorize URL to open in a browser/`SFSafariViewController`); `syncCalendar() async`; `disconnectCalendar(id)`.

**Reminders:** `reminderOverride(taskId) -> Int?` / `setReminderOverride(taskId, leadMin?)` — device-local per-task override of the global reminder lead.

**Deep links:** `consumeDeepLink()`; `openDeepLink(link)` (route an in-app tap through the same pipeline as a push tap).

**Password recovery:** `consumeRecovery()`; `setNewPassword(new) async -> AuthOutcome`.

**Nudges:** `dismissNudge(id)` (persisted device-local).

**Inbox / captures:** `archiveCapture(id)` / `unarchiveCapture(id)` (device-local set, cleared on sign-out); `saveCapture(taskId?, sessionId?, tag, body)`; `deleteCapture(id)`; `promoteCapture(capture) -> TaskItem` (preserves capture, seeds task with `lifeArea="Work"`, tags `["from-capture", <tag>]`); `saveReasonLog(taskId?, reason, action=PAUSE, durationSec?)`.

**Focus / live session:** `startFocus(task)`, `pauseFocus()`, `resumeFocus()`, `setTreatment(t)`, `extendFocus(minutes)`, `finishFocus(task, markDone=false)`, `cancelFocus()`. **These hold subtle finalize logic — port exactly** (lines 337–410). Highlights:
- `startFocus`: re-entering the SAME task's session keeps its state (a paused session stays paused — not auto-resumed). Switching to a DIFFERENT task with an active session **finalizes the old one first** (writes its `Session` row + accumulates `totalFocused`) so elapsed time isn't lost. Seeds `priorAccumulatedSec = task.totalFocused` so reopening after "End for now" continues the displayed timer from the accumulated total.
- `finishFocus`: reuses the live-session id as the `Session.id` (so captures taken during the session join back to it — the interruption histogram depends on it). `markDone=false` = "End for now" (record session, keep task open). `markDone=true` = also `applyCompletion`. On done + promoted shared item → `share.taskDone`. Always records an in-app recap card; the server push only fires when away (finishing in-app ⇒ away=false). Sets `lastRecap`.
- All mutate `LiveSession` via the `:core` `FocusTimer` pure functions (`start`, `pause`, `resume`, `setTreatment`, `extend`, `elapsedSec`). **The timer math lives in `:core`, not the view model** — the model only persists the resulting `LiveSession` to the store.

**Collections:** `upsertCollection`, `deleteCollection`, and the **shared-collection concurrency model** (lines 443–568) — **port carefully:**
- `isShared(c)`, `isOwner(c)`, `canEdit(c)` — guard `isShared` on a **known** current uid (a transiently-null uid must not misclassify your own list as shared, which would route edits down the RPC-only path with no outbox → silent loss).
- A `collectionMutex` (Kotlin `Mutex`) serializes every collection mutation and **re-reads the latest collection from the store first** (web's functional-update guard). iOS: an `actor` or an `AsyncSemaphore`/serial executor around the collection mutators.
- Own/unshared lists → whole-row upsert via `WriteThrough` (outbox, offline-safe).
- Shared lists → optimistic local write + an **atomic item RPC** (`addItem`/`updateItem`/`setItemFlag`/`removeItem`/`setItemPromotion`) so two concurrent editors don't clobber each other's `items` array. Metadata edits (rename/recolor/archive) on a shared list are a **partial UPDATE** of only the metadata columns (`updateCollectionFields`) so they don't ship the `items` JSONB and clobber a member's concurrent item edit.
- Item ops: `addCollectionItem`, `updateCollectionItemBody`, `toggleCollectionItemPin`, `toggleCollectionItemDone`, `removeCollectionItem`. Metadata: `renameCollection`, `recolorCollection`, `archiveCollection`.
- Move-to-task: `moveItemToTask(col, item, mode: {SELF, LOOP}, dueAtIso?)` — LOOP on a shared list links the task to the item (`sourceCollectionId`/`sourceItemId`/`dueAt`) and schedules it at the "by" time; guards against double-promoting an in-flight item; "Just me" on a shared list must NOT announce to others.
- Sharing (edge-function-backed): `shareCollection(id, email, role) async`, `unshareCollection`, `cancelCollectionInvite`, `leaveCollection` (**fire-and-forget on the model's scope, not the screen's** — the screen pops immediately, which would cancel a screen-scoped task before the leave RPC + local drop committed; on iOS this means kicking it on the `AppModel`'s owned `Task`, not a `.task` modifier tied to the popped view), `listCollectionMembers async`.

**Tags & areas:** `upsertTag`, `deleteTag` (cascade-strip name off every task, case-insensitive), `ensureTag(name) -> String`, `renameTag(tag, newName)` (cascade + de-dupe, bail on duplicate name), `recolorTag`; `upsertLifeArea`, `deleteLifeArea` (clear label off tasks), `renameLifeArea` (cascade, bail on duplicate name), `recolorLifeArea`.

**Onboarding:** `onboarded: Bool`; `completeOnboarding(struggles, areas=[])` — seeds picked areas (or canonical defaults `["Work","Personal","Home","Health"]`) **only if** `lifeAreas` is empty (single source of seeding — don't double-seed), pushes ADHD struggles to the server prefs, sets `onboarded=true`.

**Settings:** `updateSettings { $0.theme = ... }` — mutate + persist in one call; mirror the notification level to the server when it changes (best-effort, drives the cron morning brief + paused-checkin cap).

**Auth:** `signIn`, `signUp`, `magicLink`, `googleSignIn`, `resetPassword`, `changePassword`, `updateDisplayName`, `deleteAccount`, `signOut` (unregisters this device's push token while the JWT is still valid, then signs out). All return `AuthOutcome` (`.ok` | `.error(message)` with humanized copy from `:core` `humanizeAuthError`).

**Feedback:** `sendFeedback(body, category?, screen?) async -> Bool` — auto-attaches app version, `platform="ios"` (Android sends `"android"`), device string, email, screen.

**Export:** `exportJson() -> String` — serialize all user-owned collections into one pretty-printed JSON bundle (`ExportBundle`, matches web `exportAll`). Use `encodeDefaults = true` equivalent.

### 4.3 Helpers
- `nowMs() -> Long` = `System.currentTimeMillis()` → `Date().timeIntervalSince1970 * 1000` as `Int64`.
- `isoNow() -> String` — **exact format `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC.** iOS: an `ISO8601DateFormatter` with `.withFractionalSeconds`, or a fixed `DateFormatter` locked to `Locale(identifier: "en_US_POSIX")` + `TimeZone(identifier: "UTC")`. Getting this wrong breaks LWW ordering (timestamps are compared lexicographically).

### 4.4 Side-effects in the model's init (port both)
1. **Start-Next widget feed:** `combine(tasks, blocks, liveSession) { pickStartNext(...) }.distinctUntilChanged()` → write the recommendation to the widget's shared store + reload the widget. iOS: a Combine pipeline → `WidgetKit.reloadTimelines` (see §11). Android's widget DataStore was shipping frozen "All clear" because nothing wrote it — the model is the writer while alive; `SyncWorker` (BGTask on iOS) covers process-death.
2. **Nudges:** `combine(tasks, captures, dismissed)` → `computeNudges`. Nudges are OFF at the Calm notification level (read settings fresh). `computeNudges`: open tasks older than 21 days OR `moveCount >= 3` → a `SLIPPING` nudge `slip:<id>`. Take at most 3.

---

## 5. Navigation topology (`MainScaffold.kt` → `MainTabScaffold`)

Android uses a **custom, hand-rolled navigation stack** inside a single composable — **not** the Navigation-Compose graph for the primary flow. iOS: replicate with a single root view holding `@State` for the active tab, an array-backed route stack, the active sheet, and the focus overlay. Do **not** use a `NavigationStack` with `NavigationPath` as the only mechanism — the Android model is an explicit overlay stack rendered on top of tab content, with the bottom nav always present. The cleanest SwiftUI translation is a `ZStack`: tab content + bottom bar at the base, full-screen overlays pushed on top, sheets and the focus overlay above that. (You *can* implement the overlay stack with `NavigationStack` per-tab, but the cross-cutting overlays — Insights, Settings, Palette, Notifications, Inbox, task Detail — are app-level, not per-tab, and must render over the bottom bar. Match the Android layering.)

### 5.1 Tabs (4, bottom nav)
```
today    "Today"       (clock icon)
tasks    "Tasks"       (inbox icon)
calendar "Calendar"    (calendar icon)
lists    "Collections" (layers icon)
```
Default tab: `today`. Tab state survives recomposition (`rememberSaveable`) — iOS: persist the selected tab in `@SceneStorage` so it survives a state restoration, but **reset to Today on background** (see 5.5).

The bottom nav has a **center FAB** (`onFab`) that opens the New Task sheet (`newTaskPrefill = nil; showNewTask = true`). The FAB sits between the 4 tabs (2 left, 2 right).

### 5.2 Overlay route stack (full-screen, pushed over tab content)
A mutable array of `Route`:
```
enum Route:
  case detail(taskId: String)
  case collection(id: String)
  case insights(deep: Bool)
  case settings
  case settingsSub(section: SettingsSection)
  case palette
  case notifications
  case inbox
```
`push(r)` appends; `pop()` removes last. Only the **top** of the stack renders. The overlay draws an **opaque background** that hides tab content, but a plain background does not consume touches — Android adds a no-op no-ripple tap-catcher on the overlay layer so taps on empty overlay areas (e.g. over the still-live header icons of the tab beneath) don't fall through. **iOS:** put a `Color.bg.ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}` (or set the overlay as a full opaque layer that intercepts hit-testing) beneath the overlay's content so stray taps don't reach the tab header underneath.

Route rendering rules (port the edge cases):
- `detail(taskId)`: look up the task in `tasks`; if absent, `pop()` in an effect (never mutate the stack during view body — use `.onAppear`/`.task`, **not** inline). If present, render `TaskDetailScreen` with `onStartFocus` that sets `focusTask` and pops.
- `insights(deep)`: `onToggleDeep` **replaces** the top of the stack (`stack[last] = .insights(deep)`) rather than pushing.
- `palette`: `onTab` switches tab + clears the stack; `onSettings` clears stack then pushes Settings; `onOpenTask` pops then pushes Detail.
- `notifications` / `inbox`: `onOpenTask` pops then pushes Detail; notifications `onDeepLink` pops then routes through `openDeepLink`.

### 5.3 Sheets (modal bottom sheets)
```
enum Sheet: case avatar, areas, feedback
```
Plus two booleans outside the enum: `showNewTask` (New Task sheet, with optional `newTaskPrefill: (date, time)`) and the **focus overlay** `focusTask: TaskItem?` (+ `focusAutoCapture: Bool`).

iOS: `.sheet(item:)` for avatar/areas/feedback/newTask; the **Focus screen is a full-screen cover** (`.fullScreenCover`), not a sheet, because it's an immersive mode. The Avatar menu and Areas menu are bottom sheets; on iOS use `.presentationDetents` for the partial-height look.

Sheet actions:
- `avatar` → `onInsights` (push Insights), `onSettings` (push Settings).
- `areas` → `onPick(area)` sets `activeArea`, switches to `tasks` tab, clears stack.
- `feedback` → the feedback sheet, seeded with `currentScreen = <active tab>`.

### 5.4 The focus overlay
`focusTask` renders `FocusScreen` over everything. **Re-resolve the task fresh from `tasks`** each render (`tasks.first { it.id == t.id } ?? t`) so edits during a session reflect. `onClose` clears `focusTask` + `focusAutoCapture`. Leaving Focus **keeps the live session running** (the timer continues; the overlay just dismisses).

### 5.5 Lifecycle: reset to Today on background
`LifecycleEventEffect(ON_STOP)` → `tab="today"; stack.clear(); sheet=nil; showNewTask=false; newTaskPrefill=nil`. **The live focus session is preserved** (its overlay reappears via the LaunchedEffect in 5.7). iOS: observe `ScenePhase` → on `.background`, reset the same nav state but do **not** touch `focusTask`/the live session. (Note: `focusTask` is also cleared by this reset on Android — wait: Android's ON_STOP does NOT clear `focusTask`; it clears tab/stack/sheet/showNewTask/newTaskPrefill only. So on reopening, if a live session exists, the Focus overlay is re-armed by the live-session effect, not by `focusTask`. Match this: do not clear `focusTask` on background, and re-arm from `liveSession` on foreground.)

### 5.6 System back → SwiftUI navigation
Android `BackHandler` priority (top layer wins):
1. `focusTask != nil` → clear focus (leave session running).
2. else if no sheet + stack non-empty → `pop()`.
3. else if no sheet + stack empty + tab != today → `tab = today`.

Sheets (`showNewTask`/avatar) ride the system's own modal-dismiss. iOS has no hardware back, but replicate via: the interactive swipe-down on covers/sheets, an explicit back button on each overlay (already passed as `onBack`), and an edge-swipe gesture on overlays if you wrap them in `NavigationStack`. The **priority order must hold**: a focus overlay swipe-dismiss leaves the session running; an overlay back pops one route; on the last route, "back" returns to Today.

### 5.7 Deep-link consumption (port the cold-launch race fix — important)
`MainScaffold` collects `pendingDeepLink` **keyed on `tasks` too**, and `liveSession`. Logic (lines 145–178):
- `"capture"` → if a live session's task exists, open Focus with the capture sheet up (`focusTask = liveTask; focusAutoCapture = true`); else `showNewTask = true`.
- `unstuck://focus/{id}` → if task found, `startFocus(t); focusTask = t`; if `tasks` is still empty (cold launch, store not hydrated yet) → **wait** (`delay(2500)` then fall back to Today); else (loaded but absent) → Today.
- `unstuck://task/{id}` → if present, Today + clear stack + push Detail; if `tasks` empty → wait 2.5s then Today; else Today.
- `unstuck://collections` → `tab = lists`, clear stack.
- else (`unstuck://today`, `/recap`, `/brief`) → Today, clear stack.
- Then `consumeDeepLink()`.

**The cold-launch race is the crux:** on a cold launch from a notification, the local store hasn't emitted yet (`tasks` is empty), so a task/focus link can't resolve. Android does **not** consume the link in that case — the effect re-runs when `tasks` populates (it's keyed on `tasks`) and resolves correctly, with a bounded 2.5s fallback to Today. **iOS must reproduce this:** the deep-link handler observes both `pendingDeepLink` and `tasks`; if `tasks` is empty, don't consume — let the observation re-fire when the store hydrates. Implement with a Combine `combineLatest(pendingDeepLink, $tasks)` or re-evaluate in `.onChange(of: tasks)`.

### 5.8 Focus foreground-service restoration (Android-specific → iOS Live Activity)
Android re-arms a foreground service from the persisted `LiveSession` after process death (`LaunchedEffect(liveSession...)` lines 130–139), keyed on `sessionStart`/`taskId`/`paused`. **iOS has no foreground service** — replace with **Live Activities (ActivityKit)** + the local-notification "paused check-in." On foreground, if a live session exists with a `sessionStart`, (re)start/update the Live Activity showing the running/paused timer, and arm the paused-checkin notification if paused. See §11.

### 5.9 Onboarding gate
If `!vm.onboarded` → render `OnboardingScreen(onDone: { onboarding = false })` and return early (no tabs). iOS: same — show onboarding full-screen until `onboarded`.

### 5.10 Floating feedback bubble
A 50pt circular button, bottom-trailing, lifted above the nav bar (clears the center FAB), opens the feedback sheet. Visible only when: `FEEDBACK_ENABLED` AND stack empty AND no sheet AND no focus AND `tab != calendar`. Port the visibility guard exactly.

---

## 6. Theme & auth gating (`AppRoot.kt` → `AppRoot`)

`AppRoot(graph)` is the top-level view. It:
1. Creates the single `AppModel` from the graph.
2. Reads `settings` reactively; computes `dark = theme==DARK || (theme==SYSTEM && systemDark)`.
3. Wraps everything in the theme provider (`UnstuckTheme(dark, accent, fontScale)`) so any Settings change re-themes the whole app immediately. iOS: a SwiftUI `Environment` value (custom `UTheme` env key) + `.preferredColorScheme(dark ? .dark : .light)`; `fontScale` applied via a `.environment(\.uFontScale, ...)` your `UFont` reads (the design package owns this).
4. Gating state machine:
```
if !configured        -> SetupScreen
else:
  switch authed:
    nil   -> LoadingScreen (spinner, color = coralDeep)
    false -> AuthScreen
    true  -> recovery ? SetNewPasswordScreen : MainScaffold
```
- **SetupScreen** — shown when the anon key is missing. Shows "Add SUPABASE_ANON_KEY…" copy + the `supabase projects api-keys --project-ref uaxfteluwctrlgwmmfzi` hint. iOS: same dead-end screen when the key isn't compiled in.
- **LoadingScreen** — centered spinner while `authed == nil`.
- **The recovery branch is load-bearing:** a password-recovery email lands the user *authenticated* (a recovery session). `pendingPasswordRecovery` routes them to `SetNewPasswordScreen` (the only way out of a forgotten password) instead of the main app. After they set a new password, `consumeRecovery()` flips back to `MainScaffold`.

---

## 7. Concurrency & scope discipline (gotchas baked into the shell)

Several Android behaviors depend on **which scope** a coroutine runs in. Reproduce the intent in Swift Concurrency:

- **Model-owned vs screen-owned:** writes run on `viewModelScope` (lives as long as the model). `leaveCollection` is explicitly fire-and-forget on the model's scope, **not** the screen's, because the screen pops immediately and a screen-scoped task would be cancelled before the RPC + local drop commit. iOS: route such "must-survive-the-view" work through a `Task` owned by `AppModel` (or an actor), never a `.task {}` on a view that's about to disappear.
- **Serialized collection mutations:** the `collectionMutex` + re-read-latest pattern (§4.2). iOS: an `actor` guarding collection writes, or a serial `AsyncSemaphore`. Each mutation re-fetches the latest collection from the store before transforming.
- **Outbox flush guarded on the live user id:** the flush loop bails if `currentUserId()` changes mid-drain (sign-out + sign-in to a different account). Port this guard (§8.3).
- **Bounded timeouts on sign-out:** `signOutAndUnregister` drains the outbox with a 5s timeout before clearing the store (the NotAuthenticated branch wipes the outbox, so un-flushed edits would be lost forever). iOS: `withTimeout`-equivalent (`Task` + `Task.sleep` race, or a `withThrowingTaskGroup` with a timeout child) — best-effort, bounded.

---

## 8. The offline-first sync engine (`:sync` → `UnstuckSync`)

This is the architectural spine. It is a faithful port of the web `bootstrap-listener.tsx` + `lib/sync/*`. Reproduce **every** component. supabase-kt → **supabase-swift** (Auth, PostgREST, Realtime, Functions). The Android code repeatedly notes "Port of the iOS X.swift" — that old iOS code is being discarded, but the **contracts** below are canonical.

### 8.1 `SupabaseClientProvider`
Build one shared `SupabaseClient` with **PKCE flow** (required for the OAuth/magic-link deep-link callback), the custom `unstuck://auth-callback` redirect (`scheme=unstuck`, `host=auth-callback`), `autoLoadFromStorage` + `autoSaveToStorage`. Install Auth, PostgREST, Realtime, Functions. iOS: `SupabaseClient(supabaseURL:, supabaseKey:, options:)` with the auth flow type set to PKCE and the redirect URL registered. Session persistence: supabase-swift persists to the keychain by default — verify it survives relaunch (the `authed` tri-state depends on the restored session emitting).

### 8.2 `SyncCoordinator` (orchestrator — port of `bootstrap-listener.tsx`)
Observes `auth.sessionStatus` and drives the engine. Holds `auth` (AuthService), `write` (WriteThrough), `calendar`, `push`, `notifications`, `preferences`, `collectionShare`, `feedback`, `loginTracker`, and the engine pieces `hydrator`, `flusher`, `realtime`. Uses a `prevUserId` persisted in `UserDefaults` (Android: `SharedPreferences "unstuck.sync"`, key `unstuck.prevUserId`).

`start()` launches a single observation of `auth.sessionStatus`; `handle(status)`:
- **Authenticated** — map the **session source** to a `SyncAuthEvent`:
  - SignIn / SignUp / External → `SIGNED_IN`
  - Storage (restored on launch) → `INITIAL_SESSION`
  - UserChanged / UserIdentitiesChanged → `USER_UPDATED`
  - Refresh / Unknown → no cache action (return)

  Then: `if SyncDecision.shouldWipeCache(event, prev, uid) -> store.clearAll()`; persist `prevUserId = uid`; **flush outbox first** (guarded on live uid), **then hydrate** server-canonical, **then subscribe realtime**, **then pull calendar** (best-effort), **then maybeTrackLogin** (throttled to once/12h per user, best-effort). **Order is load-bearing: flush → hydrate → realtime → calendar.** Flushing before hydrate ensures local offline edits reach the server before the server-canonical replace; subscribing after hydrate avoids a race where a realtime event arrives mid-replace.
  - supabase-swift exposes auth state changes; you must map its event/source enum to the same three `SyncAuthEvent` cases. If supabase-swift doesn't distinguish "restored from storage" vs "fresh sign-in," derive it: a session present on the first emission after launch ⇒ INITIAL_SESSION; a subsequent transition from no-session ⇒ SIGNED_IN. Preserve the **shouldWipeCache** semantics regardless.
- **NotAuthenticated & isSignOut** — `realtime.unsubscribeAll()`, `store.clearAll()`, remove `prevUserId`.
- **Initializing / RefreshFailure** — no action.

Other methods: `refreshCollections()` (re-pull collections + membership after a share/unshare); `syncNow()` (flush → hydrate → pullCalendar, no-op signed out — used by the background task); `signOutAndUnregister()` (drain outbox bounded 5s → unregister push token → sign out); the full Google Calendar consent + pull + push-to-Google flow (begin/complete connect with a CSRF state guard, pull events for `[-7d, +30d]` reconciling into local `EXTERNAL` blocks, push task blocks to the user's `primary` Google calendar via insert/patch/delete, disconnect). The Google bits live in `:sync` behind the `WriteThrough.pushCalBlock`/`pushCalBlockDelete` hooks so `:data`/`:core` stay Google-agnostic — keep that seam.

### 8.3 `WriteThrough` (optimistic local write + enqueue) — port exactly
Every upsert: write the model to the local store (UI updates immediately via the reactive query), then enqueue an outbox op carrying the **PostgREST row JSON** (from `DbRowCodec`). Key behaviors:
- `cal_blocks` upserts carry `dependsOn = task.id` (only if it's a valid UUID) so the parent task flushes first.
- `captures` carry `dependsOn = session.id` (a capture taken during a session FK-references a `sessions` row only written at session end).
- External Google `g_` blocks (or `kind == EXTERNAL`) are **never** enqueued to our `cal_blocks` table (id/shape isn't ours; would fail forever and stall the outbox) and never pushed to Google.
- After a successful task-block upsert, the Google push hook may mint an event id → re-stamp the block with `externalEventId` and re-enqueue.
- **Deletes cancel pending upserts for the same row first** (`cancelPendingUpserts`) — a held-back upsert (waiting on its `dependsOn`) must not resurrect the row after the delete flushes. This is critical for the cal_block case where the delete (no `dependsOn`) can flush ahead of a held upsert.
- `reason_logs.duration_sec` is **omitted when null** so an upsert never clobbers a server-set value.

### 8.4 `OutboxFlusher` — port exactly
Drains the FIFO outbox (by `seq`) honoring dependency ordering:
- Bail if the signed-in user changed mid-drain (intended-user guard).
- An op is held back while its `dependsOn` row still has a pending op.
- Once an op for a row fails this pass, skip that row's **later** ops (preserve per-row order / LWW).
- **Poison-pill cap:** per-op consecutive-failure tally; after **5** failures, drop the op (so it can't wedge its dependents forever) **and** drop any ops that depended on it (their FK parent will never exist). The tally resets on app restart (a transient failure still gets retries). **This is a recurring Android bug class — the poison-pill drop is mandatory.**
- If no op progressed in a pass, stop (retry on next reconnect/sign-in).

iOS: a serial async drainer (an `actor`) reading the GRDB outbox, applying via the gateway. Keep the fail-count map in memory (resets on launch).

### 8.5 `Hydrator` — port exactly
Pulls every synced table and **replaces** the local store per-table (server-canonical), with **per-table error isolation** (a table whose fetch fails is left intact — mirrors `if (res.ok) replace(...)`). Special cases:
- **collections + membership:** fetch `collections` (RLS returns own + shared-with-me) and `collection_members`; enrich each collection with `members[]` + the current user's `myRole` (`"owner"` if `ownerId == userId`, else the member row's role). These client-only fields are **never** written back (the `CollectionRow` codec drops them).
- **cal_blocks:** server set is canonical, but preserve locally-cached external (`g_`) blocks (their ids aren't UUIDs, never round-trip to Postgres) via `SyncDecision.mergeHydratedCalBlocks` (remote wins on id collision), **and** preserve unsynced optimistic TASK blocks (those with a pending outbox upsert, in neither remote nor localExternal) so the replace doesn't wipe them off the UI until the next flush.

### 8.6 `SyncDecision` (pure, unit-tested — port + port the tests)
Two pure functions (cite `SyncDecisionTest`):
- `shouldWipeCache(event, prevUserId, currentUserId)`: SIGNED_IN / INITIAL_SESSION → wipe iff `prev != current` (first sign-in `prev=nil` ⇒ wipe; same-user re-auth ⇒ **don't** wipe, so a re-emit can't clobber pending offline edits + the live session); USER_UPDATED → never wipe.
- `mergeHydratedCalBlocks(remote, localExternal)`: keep external local blocks, remote wins on id collision, drop local non-external rows.

Port the 4 test cases verbatim into `UnstuckSyncTests` (Swift Testing / XCTest): `signedInWipesOnlyIfUserChanged`, `initialSessionWipesOnlyIfUserChanged`, `userUpdatedNeverWipes`, `mergePreservesLocalExternalBlocks`, `mergeDropsLocalNonExternalAndRemoteWinsOnClash`.

### 8.7 `RealtimeMirror` — port exactly
One channel per synced table, `postgres_changes`, filtered by `user_id` (RLS is the real guard; the filter is client safety). INSERT/UPDATE → local upsert; DELETE → local delete (by `oldRecord.id`). Special cases:
- **collections** subscribed **without** the user_id filter (shared rows are owned by someone else; rely on RLS) and preserves the client-only `members`/`myRole` across the incoming row (which carries neither) — port `realtime.ts mergeKeep`.
- **collection_members** for me (filtered) → on any change, **re-hydrate collections** (don't mirror the membership row itself).
- **`calendar_connections` is intentionally NOT subscribed** — its encrypted creds must never be broadcast.
- **Guard each event** in a `runCatching` so one un-decodable row (a new column/enum) doesn't permanently kill the table's live mirror — skip + keep the stream alive. iOS: wrap each realtime callback in a `do/catch` and log+skip on decode failure.

supabase-swift Realtime exposes `postgresChange` streams; subscribe per table on the coordinator's scope, tear down on sign-out.

### 8.8 `AuthService` — port the contract
Email/password sign-in & sign-up, magic link (OTP), Google OAuth, reset password, change password, update display name, delete account (calls the `account-delete` Edge Function then signs out), sign-out. `AuthOutcome` = `.ok | .error(message)`, message humanized via `:core` `humanizeAuthError`. **Sign-up anti-enumeration:** Supabase returns an obfuscated "success" for an already-registered email (no session, empty identities) — detect via `:core` `detectSignupAlreadyExists(identitiesCount, emailConfirmedAt, lastSignInAt, hasSession)` and surface a real "already exists" error. Identity getters: `currentUserId`, `currentEmail`, `currentName` (from `display_name`/`full_name` metadata, falling back to the email local-part), `hasPassword` (has an `email` identity vs Google-only).

### 8.9 `SyncGateway` — the PostgREST primitive
`fetchAll(table) -> [JsonObject]` (RLS auto-scopes); `upsert(table, row, userId)` injects `user_id` into the row and upserts `onConflict=id`; `delete(table, id)`. Works in raw JSON-object row shapes so **explicit-null semantics survive** (an upsert must CLEAR a removed field). iOS: supabase-swift PostgREST with dictionary/`AnyJSON` payloads, or `Encodable` row structs that emit explicit nulls. **Critical:** the upsert must send explicit `null` for cleared optional columns (matching `explicitNulls = true`) — see §10.

### 8.10 `DbRowCodec` — the camelCase/snake_case + explicit-null boundary
Per-entity DTOs map `:core` models ↔ the exact Supabase row shape (matching web `taskToDbRow` et al.). **Two gotchas (see §10):** top-level columns are snake_case; embedded JSONB blobs (`recurrence.daysOfWeek`, `objectives`, `comments`, `items`) stay **camelCase** (that IS the server JSONB shape). Defaults match web (`tags ?? []`, `move_count ?? 0`, `later ?? false`). FK columns drop to `null` when not a valid UUID (`uuidOrNull`). `reason_logs.duration_sec` omitted when null. Collection `ownerId` rides on the raw row's `user_id` on decode (never written back).

---

## 9. Local store (Room → GRDB / JSON store)

Android's `LocalStore` is a **typed facade over Room** that stores each synced row as a **domain-model JSON blob** keyed by `(tableName, id)`, and exposes reactive `Flow<List<T>>` per logical table. It is **not** a normalized relational schema — it's a generic key/blob store. This is deliberate: screens read whole collections and compose them with `:core` in memory.

**iOS recommendation: GRDB** with the **same generic-blob shape** (the README and the Android comments both reference a GRDB/JSON store as the iOS target). Three tables:

| Room entity | Schema | GRDB equivalent |
|---|---|---|
| `RecordEntity` | `records(tableName TEXT, id TEXT, data TEXT, updatedAt TEXT?, PK(tableName, id))` | same; `data` = JSON-encoded domain model |
| `OutboxEntity` | `outbox(seq INTEGER PK AUTOINCREMENT, op TEXT, recordTable TEXT, recordId TEXT, payload TEXT?, dependsOn TEXT?, createdAt INTEGER)` | same; FIFO by `seq` |
| `LiveSessionEntity` | `live_session(id INTEGER PK=0, data TEXT)` | single-row blob |

`LocalStore` API to port:
- Reactive reads per table → `tasks()`, `blocks()`, `sessions()`, `captures()`, `reasonLogs()`, `collections()`, `tags()`, `lifeAreas()`, `connections()`. iOS: GRDB `ValueObservation` publishing `[Model]`, decode each blob with `JSONDecoder`, **skip undecodable rows** (Android `mapNotNull { runCatching {...}.getOrNull() }`). Feed these into the `AppModel`'s `@Published` arrays.
- `snapshot(table)` — one-shot read.
- `upsert(table, model, id, updatedAt?)`, `delete(table, id)`, `entity(...)` (encode without writing).
- `replace(table, items, id, updatedAt?, preservePrefix?)` — wipe-table-then-insert in **one transaction** (`@Transaction replaceTable`); `preservePrefix` keeps rows whose id starts with it (the `g_` external cal_blocks). iOS: GRDB transaction; `DELETE ... WHERE id NOT LIKE 'g_%'` then insert.
- `clearAll()` — clears records + outbox + live_session (one shot). **Wipes the outbox** — hence the sign-out drain (§7).
- Outbox: `enqueue(op) -> seq`, `pending() -> [Outbox]`, `dequeue(seq)`, `pendingCount() -> Flow<Int>`.
- Live session: `liveSession() -> Flow<LiveSession?>`, `getLiveSession()`, `setLiveSession(live?)`.

**JSON config (match exactly):** decode with `ignoreUnknownKeys = true`, `isLenient = true`; encode with `encodeDefaults = true`. (Note: this is the **store's** JSON, camelCase domain models — distinct from the `rowJson` used by `DbRowCodec` for PostgREST, which adds `explicitNulls = true`.) iOS: a `JSONDecoder`/`JSONEncoder` pair for the store (lenient, tolerant of unknown keys) and a **separate** encoder config for the PostgREST row boundary.

**Migration safety (cite `UnstuckDatabase`):** Room uses `fallbackToDestructiveMigrationOnDowngrade()` only — an **upgrade** without a registered migration fails loudly rather than nuking the outbox (the only copy of unsynced offline writes). iOS: GRDB `DatabaseMigrator` — register explicit migrations; never auto-erase on a schema bump, because that would drop pending offline writes. Downgrade (older build) may reset.

---

## 10. Gotchas (must-port — these are the recurring bug classes)

1. **kotlinx default-omission / explicit nulls (LWW correctness).** The store/model JSON uses `encodeDefaults = true`; the **PostgREST row** JSON (`rowJson`) uses `explicitNulls = true, encodeDefaults = true`. An upsert must send explicit `null` for a cleared optional so the column is wiped server-side (not left stale). **Swift's `JSONEncoder` omits `nil` optionals by default** — this is the single biggest serialization trap. For the PostgREST row structs you **must** emit explicit `null` for cleared fields. Options: implement `encode(to:)` manually for the row DTOs encoding optionals as `encodeNil` when nil, or build the payload as an `[String: AnyJSON?]` dictionary that includes `NSNull` for cleared columns. The **lone exception**: `reason_logs.duration_sec` is **omitted** when null (so an upsert never clobbers a server value) — mirror this single carve-out.

2. **camelCase JSONB inside snake_case rows.** Top-level columns are snake_case (`estimate_min`, `start_time`, `move_count`, `created_at`…), but embedded JSONB (`recurrence.daysOfWeek`, `objectives`, `comments`, collection `items`) stays **camelCase** — that is the literal server JSONB shape. Do **not** apply a global `.convertToSnakeCase` key strategy; map top-level columns explicitly (per-field `CodingKeys`) and let the nested types keep camelCase. Android does this by `@SerialName` on row DTO fields with no global strategy. The web is the source of truth for the exact shape (`lib/types.ts`, `lib/use-tasks.ts`).

3. **UTC dates / local date math (JS-Date parity).** Timestamps are ISO strings; **date arithmetic is LOCAL** (`ZoneId.systemDefault()`, like JS `Date`); ISO strings compare lexicographically (LWW + ordering rely on this). `isoNow()` is `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in **UTC**. The `:core` `Time`/`Clock` helpers (`startOfDayMillis`, `civil`, `addDaysMillis`, `dayOfWeekJs` = 0=Sun…6=Sat, `daysInMonth`, `wholeDaysBetween`, `Clock.todayIso`) all use the **system zone** — port them with `Calendar.current` / `TimeZone.current`, **not** UTC, for date math; only the ISO *stamp* is UTC. `parseMillis` must accept full instants, offset datetimes, offset-less local datetimes, and bare `YYYY-MM-DD` (interpreting the latter two in the system zone, not returning null). **Run `:core`/`UnstuckCore` tests with `TZ=UTC`** for determinism (matches web CI + Android `-Duser.timezone=UTC`); the production app uses the device zone.

4. **LWW + cache-wipe nuance.** Same-user re-auth must NOT wipe the cache (would drop pending offline edits + the live session). The cache-wipe decision is `SyncDecision.shouldWipeCache` keyed on `prevUserId != currentUserId` (persisted across launches). Don't wipe on `USER_UPDATED` (metadata change). (§8.6)

5. **No exact-alarm permission on iOS — drop the entire AlarmManager flow.** Android prompts for `SCHEDULE_EXACT_ALARM` because inexact alarms get Doze-batched and fire late/never. iOS local notifications via `UNUserNotificationCenter` fire at the requested time with **no special permission** — so the `maybePromptExactAlarm` flow, the `exactAlarmPrompted` pref, and the "Alarms & reminders" deep-link are all **removed**. Just request notification authorization once. (But note iOS's own constraint: there's a ~64 pending-notification cap per app and background time is limited — schedule reminders lazily and cap how many you arm.)

6. **Dependency ordering & poison pills (outbox).** `cal_block` depends on its task; `capture` depends on its session. Held-back ops, per-row LWW skip, and the **5-failure poison-pill drop (+ orphan-dependent drop)** are all mandatory — this exact bug ("poison-pill outbox") recurred on Android and was explicitly fixed. (§8.4)

7. **Delete-cancels-pending-upsert.** A delete must drop any queued upsert for the same row (and `deleteTask` cascades to its cal_blocks + captures locally) so a held-back upsert can't resurrect a deleted row, and realtime doesn't pull orphans back. (§4.2, §8.3)

8. **Scope discipline.** Work that must outlive the screen (`leaveCollection`, sign-out drain) runs on the model/coordinator scope with bounded timeouts — not a view-tied task. (§7)

9. **Cold-launch deep-link race.** Don't consume a task/focus deep link while the store is still empty post-cold-launch; re-evaluate when `tasks` populates, with a 2.5s fallback to Today. (§5.7)

10. **No foreground service on iOS.** The live-focus "ongoing notification" foreground service has no iOS analog. Use a **Live Activity** (ActivityKit) for the running/paused timer on the lock screen / Dynamic Island, plus scheduled local notifications for the paused check-in and overrun bell. A killed app cannot keep a timer running in the background — the timer is reconstructed from `LiveSession.sessionStart` (epoch ms) on next foreground (the math is in `:core` `FocusTimer.elapsedSec`). (§5.8, §11)

---

## 11. Platform surface adapters (Android `surface/` → iOS frameworks)

| Android surface | Purpose | iOS replacement |
|---|---|---|
| `SyncWorker` (WorkManager, periodic 30 min, network-constrained) | background flush+hydrate + refresh widget across process death | **BGTaskScheduler** `BGAppRefreshTask` — register an identifier, schedule on background, in the handler call `coordinator.syncNow()` + recompute Start-Next + `WidgetKit.reloadAllTimelines()`. iOS won't honor a fixed 30-min cadence — it's opportunistic; reschedule on each run. |
| `FocusTimerService` (foreground service + chronometer notification) | keep the live timer visible | **ActivityKit Live Activity** (running/paused timer, Dynamic Island) + reconstruct from `sessionStart` on foreground. No true background execution. |
| `PausedCheckinScheduler` (AlarmManager) | "still paused?" nudge | scheduled `UNNotificationRequest` (cancel on resume/finish). |
| `ReminderScheduler` (observes cal_blocks → exact alarms) | pre-task "remind me N min before" + start-now + drift | observe `blocks` → schedule `UNTimeIntervalNotificationRequest`/calendar triggers, honoring `reminderLeadMin` + per-task `reminderOverride` + the `NotificationLevel` flags. Re-derive on every blocks change; cancel stale requests. |
| `NotificationChannels` | Android channels | iOS `UNNotificationCategory` + actions (e.g. "Capture", "Start", "Reschedule") registered at launch. |
| `NotificationActionReceiver` / `ReminderReceiver` | notification action taps | `UNUserNotificationCenterDelegate` — map action identifiers to `pendingDeepLink` values (`"capture"`, `unstuck://focus/{id}`, etc.). |
| `NotificationLog` | in-app notification center log + unread badge | a small persisted log (UserDefaults/JSON or a GRDB table) exposing `items` + `lastSeen`; `markAllSeen`; `clear` on sign-out. Drives `notifications`/`notifUnread`. |
| `Push` / `registerFcmToken` (FCM) | push token registration | **APNs** — `UIApplication.registerForRemoteNotifications`, get the device token, register it via `push.register(...)` to the backend (the backend already supports per-platform tokens; send `platform = "ios"`/APNs). Unregister on sign-out while JWT valid. |
| `StartNextWidget` + DataStore (Glance) | home-screen "Start Next" | **WidgetKit** widget reading a shared snapshot from an **App Group** `UserDefaults`/file. The `AppModel` (alive) and the BGTask (process-death) both write the snapshot + reload the timeline. |
| `AmbientAudio` | focus ambient sound (off/brown/pink) | `AVAudioEngine`/`AVAudioPlayer` with the background-audio capability; play when the `ambient` setting != "off". |
| OAuth via Custom Tabs + `unstuck://` intent filter | Google Calendar consent | `ASWebAuthenticationSession` (or `SFSafariViewController`) opening the authorize URL from `beginGoogleConnect()`; the `https://unstuck-602.pages.dev/calendar-callback` page bounces back to `unstuck://calendar-callback` which the app handles. |
| supabase-kt | backend SDK | **supabase-swift** (Auth/PostgREST/Realtime/Functions, PKCE). |
| Room | local store | **GRDB** (§9). |

**App Group** is required for the WidgetKit widget (and any Live Activity shared data) — set up an app group container and point the widget's snapshot read + the app's snapshot write at it.

---

## 12. Device-local settings (`SettingsStore.kt` → `SettingsStore`)

Device-local prefs persisted to `SharedPreferences "unstuck.settings"` → iOS `UserDefaults` (app-group suite if the widget reads any). Mirrors the web `theme-context` + `STORAGE_KEYS` PREF_* scalars. The model holds a `@Published SettingsState`; `updateSettings { ... }` mutates + persists + (on notification-level change) mirrors to the server.

`SettingsState` fields (port all, with defaults):
```
theme: ThemePref = .system
accent: AccentPalette = .indigoCoral
density: Density = .regular
largerType: Bool = false
reduceMotion: Bool = false
highContrast: Bool = false
keyboardHints: Bool = true
focusDefaultMin: Int = 25
focusOverrunMin: Int = 5          // 0 = Never
focusCollapseRail: Bool = true
focusSoftExit: Bool = true
focusPauseReasons: Bool = true
soundStartChime: Bool = true
soundOverrunBell: Bool = true
soundCompletion: Bool = false
ambient: String = "off"           // off | brown | pink
treatment: FocusTreatment = .ambient
reminderLeadMin: Int = 10         // 0 = Off
notificationLevel: NotificationLevel = .balanced
```
Derived `fontScale`: density (`compact 0.94`, `regular 1.0`, `comfy 1.08`) × (`largerType ? 1.15 : 1.0`). Used by the theme wrapper.

**`NotificationLevel`** (Calm / Balanced / Coach) is the **single source of truth** for which moments each level enables — read by the reminder scheduler, paused-checkin, in-app nudges, and (synced to the server) the morning brief:
```
atStart       = level != .calm    // "starts now" notif (Start/Reschedule)
drifted       = level == .coach   // follow-up ~10 min after start if not started
pausedCheckin = level != .calm
morningBrief  = level != .calm    // server-sent
nudges        = level != .calm    // quiet in-app cards on Today
```
`fromLabel(l)` maps a stored label back, defaulting to Balanced.

Device-local per-user content that must be **cleared on sign-out** (`clearUserContent`): per-task reminder overrides (`reminder.override.<taskId>`), dismissed nudges, archived capture ids. Plus `NotificationLog.clear`. (So a different account on the same device starts clean.)

---

## 13. Domain models & enums (`:core` Models/Enums — port verbatim as Codable)

These are shared with every other area spec; the shell must define them in `UnstuckCore`. **camelCase Swift properties**; the snake_case mapping lives only at the PostgREST boundary (§8.10). All `Codable`.

**Models:** `Objective(text, done?, minutes?)`; `Comment(text, at?)`; `Recurrence` (sealed/enum: `.daily(until?)`, `.weekly(daysOfWeek:[Int], until?)`, `.monthly(until?)` — encodes the tagged-union JSON `{kind, daysOfWeek?, until?}`, `daysOfWeek` 0=Sun…6=Sat, `until` inclusive `YYYY-MM-DD`; **custom Codable** — see below); `TaskItem` (id, name, estimateMin, totalFocused=0, done=false, priority?, tags?, objectives?, comments?, intentWhen?, intentThen?, lifeArea?, firstPhysicalAction?, moveCount?, completedAt?, later?, recurrence?, sourceCollectionId?, sourceItemId?, dueAt?, createdAt, updatedAt); `Session`; `CalBlock` (startTime `HH:MM`, date `YYYY-MM-DD`, externalEventId?, externalConnectionId?, kind?); `ReasonLog`; `Capture`; `CalendarConnection`; `ExternalEvent`; `CollectionItem` (id, body, pinned?, done?, at, promoted?, assignee?, promotedDone?, dueAt?); `ItemCollection` (id, name, color, subtitle?, items, sortOrder, + client-only `ownerId?`, `members=[]`, `myRole?`, `archived?` — **never written back**); `TagRow`; `LifeArea`; `LiveSession` (id?, taskId, sessionStart? epoch ms, paused=false, pausedAt? epoch ms, sessionEstimateMin, nudge80Fired=false, overrunPromptFired=false, treatment, priorAccumulatedSec?).

**Enums** (with **exact** server string values via `@SerialName` → Swift `String` raw values / custom `CodingKey`): `Priority` (urgent/high/medium/low); `FocusState` (idle/starting/running/overrun/pause/done/resume); `FocusTreatment` (ambient/cockpit/monk); `CalBlockKind` (task/placeholder/external); `ReasonAction` (pause/switch); `CaptureTag` (`follow-up`/idea/edit/question/distraction — note the **hyphen** in `follow-up`); `CalendarProvider` (google/apple/microsoft); `ThemePref` (system/light/dark); `Density` (compact/regular/comfy); `TaskListView` (label-only UI enum).

**`Recurrence` Codable (port the serializer):** encode `{kind:"daily|weekly|monthly", daysOfWeek:[…] only for weekly, until?:…}`; decode by `kind`; throw on unknown kind. This must round-trip the **camelCase** `daysOfWeek` key (it's a JSONB blob). Mirror `RecurrenceSerializer`.

**`AuthOutcome`** = `.ok | .error(message)` (§8.8). **`ExportBundle`** (§4.2). **`RecapState`** (taskName, focusedSec, at). **`Nudge`** (id, kind: {slipping, capture}, title, action, taskId?, captureId?). **`SyncAuthEvent`** (signedIn, initialSession, userUpdated).

---

## 14. Supabase tables & columns (the shell touches all of these)

RLS auto-scopes reads; every write injects `user_id`. Columns are snake_case; JSONB columns hold camelCase blobs.

- **`tasks`** — id, user_id, name, estimate_min, total_focused, done, priority, tags (jsonb `[]`), objectives (jsonb `[]`), comments (jsonb `[]`), intent_when, intent_then, life_area, first_physical_action, move_count, completed_at, later, recurrence (jsonb), source_collection_id, source_item_id, due_at, created_at, updated_at. (`late_nudged` exists server-side, cron-owned, never written by the client.)
- **`cal_blocks`** — id, user_id, task_id (nullable FK), task_name, start_time, duration_minutes, date, external_event_id, external_connection_id, kind. (`g_`-prefixed external blocks live only on-device.)
- **`sessions`** — id, user_id, task_id?, task_name, tags?, estimate_min?, actual_sec, completed_at.
- **`captures`** — id, user_id, task_id?, session_id? (FK), tag, body, at.
- **`reason_logs`** — id, user_id, task_id?, reason, action, at, duration_sec (omitted-when-null on upsert).
- **`collections`** — id, user_id (= ownerId), name, color, subtitle, items (jsonb), sort_order, archived.
- **`collection_members`** — collection_id, user_id, role (owner/editor/viewer). Drives shared `members[]` + `myRole`. Subscribed (for me) → re-hydrate collections.
- **`tags`** — id, user_id, name, color, sort_order.
- **`life_areas`** — id, user_id, name, color, sort_order.
- **`calendar_connections`** — id, user_id, provider, account_email, display_name, selected_calendar_ids, color_slot, last_sync_cursor, connected_at. **Never** realtime-subscribed (encrypted creds).
- **Edge functions / RPCs the shell calls:** `account-delete` (delete account), collection-share RPCs (`addItem`/`updateItem`/`setItemFlag`/`removeItem`/`setItemPromotion`/`updateCollectionFields`/share/unshare/leave/cancelInvite/listMembers/taskDone), calendar (`authorize`/`connectGoogle`/`listConnections`/`pullEvents`/`insertEvent`/`patchEvent`/`deleteEvent`/`disconnect`), preferences (`setAdhdStruggles`/`setNotificationLevel`), push register/unregister, feedback submit, login-tracker. (These are detailed in the sync-area spec; the shell wires the clients into the coordinator.)
- Migrations referenced: 020/022 (sharing), 025 (move-to-task back-link), 026 (archived collections).

---

## 15. Acceptance checklist (shell is "done" when)

1. Cold launch with no anon key → Setup screen; with a key but no session → Auth screen; with a restored session → Today.
2. `authed` tri-state drives a loading spinner before the auth state resolves (no flash of Auth screen for a signed-in user on relaunch).
3. Sign-in on a fresh device wipes nothing; sign-in as a different user wipes the cache; same-user re-auth/relaunch preserves pending offline edits + the live session (port `SyncDecisionTest`).
4. Offline: create/edit tasks, blocks, captures, collections → all appear instantly (optimistic); on reconnect the outbox drains in dependency order; a poison op drops after 5 failures without wedging the queue.
5. Bottom-nav tabs + center-FAB new-task; overlay stack (Detail/Collection/Insights/Settings/Palette/Notifications/Inbox) renders over tab content and the bottom bar, swallows stray taps, and backs out one route at a time; backgrounding resets to Today but keeps a live focus session.
6. Push/notification taps deep-link correctly even on cold launch (re-evaluate when the store hydrates; 2.5s fallback to Today).
7. Password-recovery email lands on the Set-New-Password screen, not the main app; after setting, returns to Today.
8. Theme/accent/density changes re-theme the whole app live.
9. Realtime edits from another device/member appear within the app; an undecodable realtime row is skipped without killing the stream; `calendar_connections` is never broadcast.
10. Sign-out drains the outbox (bounded), unregisters the push token, clears the cache + per-user device-local content, and tears down realtime.
11. The Start-Next widget updates from the live model and from the background task (App Group snapshot + `WidgetKit.reloadTimelines`).
12. `UnstuckCore`/`UnstuckSync` parity tests pass under `TZ=UTC` (sync-decision + cal-block-merge cases at minimum, plus the full `:core` suite shared with web/Android).