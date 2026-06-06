# iOS Rebuild SPEC — Today / Dashboard (`TodayScreen`)

Reference client: **Android** (`unstuck_android`). Target: a 1:1 behavioral SwiftUI replica. This spec covers the Today tab — the first screen the user lands on. Source files: `app/.../ui/today/TodayScreen.kt`, `core/.../logic/PickStartNext.kt`, `core/.../logic/VisibleTasks.kt`, `core/.../logic/TaskBucket.kt`, `core/.../logic/CalBlockKind.kt`, `core/.../logic/FocusTimer.kt`, plus `AppViewModel.kt`, `Common.kt`, and the `sync` row codecs.

---

## 1. What it does — behavior, every state, flows, edge cases

The Today screen is a single scrolling column with a **pinned (non-scrolling) header** and a scrolling `LazyColumn` below it. Layout top→bottom:

### 1.1 Pinned header (never scrolls)
1. **Icon row** (`top=8dp`, `start=18 / end=12`, space-between):
   - Left: `Orbit` brand mark, size 24.
   - Right cluster (2dp spacing): Inbox icon (`MoveToInbox`, 40dp tap target, 20dp glyph, `ink2`) → `onInbox`; Notifications bell (`Notifications` outline) → `onNotifications`; circular **avatar** (32dp, `greenSoft` bg, `greenInk` initials) → `onAvatar`.
   - **Unread dots**: a 7dp coral dot top-end of the Inbox icon when `inboxCount > 0`, and another on the bell when `notifUnread > 0`. These are boolean presence dots, NOT numeric badges.
   - **Initials**: derived from `currentName` — split on `' ' '.' '@'`, take first char of each token uppercased, take 2, join; fallback `"U"` if name null/empty.
2. **Date eyebrow** — `dateEyebrow(now)` → e.g. `"FRIDAY · 12:12 PM"` (uppercase weekday, 12-hour clock, local zone), in `primaryDeep`, as a `SectionLabel`.
3. **Greeting headline** — `"<greeting>\nUnstuck."` in serif-italic 28. `greeting(now)` is `"Good morning,"` (h<12), `"Good afternoon,"` (h<18), else `"Good evening,"`. Note the literal newline → two lines.
4. **"This week" pill** (tappable → `onInsights`): pill bg `bg2`, rounded 999, a 6dp coral dot, text `"This week · "`, then bold focus total, then `"→"`. The total is `weekMin` minutes formatted: `"<n>m focused"` under 60 min; `"<h>h focused"` or `"<h>h <m>m focused"` at/over 60.
   - `weekMin = sum of session.actualSec for sessions completed within the last 7 days, ÷ 60`. Window test: `(now − completedAtMs) in 0..(7×86_400_000)`. `completedAtMs` = `Time.parseMillis(session.completedAt)`, 0 if unparseable.

### 1.2 Scrolling content (`LazyColumn`)
Order of items, each conditional:

1. **Notifications-off banner** — shown when OS notifications are disabled (`!notifsEnabled`). Amber card (`amberSoft`), bell icon, title `"Notifications are off"`, subtitle `"Reminders won't reach your phone. Tap to turn them on."`, trailing `"→"`. Tapping opens the OS app-notification-settings screen.
2. **Recap card ("Just now")** — shown when `recap != null && (now − recap.at) < 6h`. Coral card (`coralSoft`), `SectionLabel("Just now")` + a `✕` (→ `dismissRecap()`), serif headline `"You did the thing."`, then a mono caption `"<minutes> MIN FOCUSED · <taskName>"` where minutes = `(focusedSec/60).coerceAtLeast(1)`, ellipsized to 1 line.
3. **Nudge card** — `nudges.firstOrNull()` (only the first is shown). `bg2` card with `line` border: the nudge `title` (max 2 lines, weight 1), an action label in `primaryDeep` (e.g. `"Open"`), and a `✕`. Tapping the action: for `SLIPPING` → open the linked task (`onOpen`); for `CAPTURE` → promote the linked capture; either way then `dismissNudge(n.id)`. The `✕` just dismisses.
4. **Empty hero** OR **content**, branched on `empty`:

   - **`empty == true`** → `EmptyHero`: gradient card, `Orbit` size 48, `SectionLabel("Nothing to start")`, serif `"You're all clear."`, body `"Nothing's missing. When something's on your mind, drop it in."`, coral button `"Add one thing"` → `onSearch` (i.e. add-task entry point).
   - **`empty == false`** → in order:
     1. **Start-Next hero** (only if `startNext != null`): gradient card. Top: a pill (`Bolt` icon + `SectionLabel("Start next")`). A row with a 6dp coral dot + `"<lifeArea|Focus> · <taskName>"` (1 line). **Headline** = `firstPhysicalAction` if non-blank, else `task.name`, bold 21, max 2 lines. Subtext `"<estimateMin> min"`. Actions: coral **"Focus"** button (play icon) → `onStartFocus(startNext)`; a text **"Pick another"** → `onSearch`.
     2. **Sticky header** (sticks to top of list on scroll): a title `"Today"` or `"Backlog"` (depending on `backlogActive`), then a horizontally-scrolling row of **filter pills**:
        - **Backlog toggle** — amber accent. When inactive shows a small amber dot + `"Backlog"`; when active uses `amberSoft`/`amberInk`. Tapping flips `backlogActive`; **entering Backlog clears the area filter** (`areaFilter = null`).
        - **"All"** pill — selected when `!backlogActive && areaFilter == null`. Tapping: `backlogActive=false; areaFilter=null`.
        - **One pill per life area** (`areas`), each with the area's dot color. Selected when `!backlogActive && areaFilter == area.name`. Tapping toggles that area (tap again to clear) and exits backlog.
     3. **Live-session card** (only if there's a live session AND its task exists) — see §1.3.
     4. **Task rows** (`displayRows`) — see §1.4.
     5. **Per-view empty note** — if `displayRows.isEmpty() && liveTask == null && (backlogActive || areaFilter != null)`: a single muted line, `"Backlog's clear — nothing waiting."` (backlog) or `"Nothing in <area> right now."` (area filter). Crucially this is only shown for a filtered/backlog view that came up empty — never for the unfiltered Today (that path is covered by the `empty` hero).
5. Trailing 24dp spacer.

### 1.3 Live-session card (in-progress focus, surfaced on Today)
Branches **running vs paused** (mirrors web `LiveTaskRow`):
- **Progress ring** (30dp canvas): a full-circle track in `line`, plus an arc from −90° sweeping `360°×progress`, rounded cap, colored `coral` (running) or `amber` (paused).
  - `elapsed = FocusTimer.displayedElapsedSec(live, nowTick)` = this-session elapsed + `priorAccumulatedSec`.
  - `estimateSec = (live.sessionEstimateMin if >0 else task.estimateMin).coerceAtLeast(1) × 60`.
  - `progress = (elapsed / estimateSec).coerceIn(0,1)`.
  - Center label: `"<h>h<MM>"` once `elapsed ≥ 3600`, else `formatMMSS(elapsed)`.
- **Text**: title `"In focus · <name>"` (running) or `"Paused · <name>"`; subtitle `"running for <MM:SS>"` (running) or `"<estimateMin>m · paused"`.
- **Border**: coral @55% alpha (running) vs `line2` (paused).
- **Button**: `"Pause"` (running, `bg2`/`ink`) → `pauseFocus()`; `"Resume"` (paused, `ink`/`bg`) → `resumeFocus()`.
- **Tapping the card body** → `onStartFocus(liveTask)` (re-enters the focus screen; does NOT auto-resume a paused session).

### 1.4 Task row
- Card (`surface` bg, `line` border, rounded 14). If `task.done`: a green `CheckCircle` (18dp) leads. Name (15, medium) — strikethrough + `ink3` when done, else `ink`, 1 line ellipsized. Meta row: area dot (resolved color) + `lifeArea ?: "—"`, then up to 3 tag chips `"#<tag>"` (`primarySoft`/`primaryDeep`).
- **Age badge** — only in Backlog view: an amber `"<n>d"` chip where `n = ageDays(createdAt, now).coerceAtLeast(1)`.
- Trailing: `"<estimateMin>m"` in mono.
- Tap → `onOpen(task)`.

### 1.5 Live timekeeping / tickers
- **Minute ticker**: `nowState` refreshes every 60s so the date eyebrow, Today filtering, and "completed today" roll over at midnight on a screen left open.
- **Second ticker** (`nowTick`): runs every 1s **only while** `live != null && !live.paused`, to animate the live-card ring/timer. Paused → frozen (no tick).
- **OS-notif recheck**: `notifsEnabled` re-evaluated on every `ON_RESUME` (user may toggle in system settings and return).

### 1.6 Key edge cases (must replicate exactly)
- **Start-Next and the live task are subtracted from the list** (`rows`/`backlogRows` filter out `startNext.id` and `liveId`) so they aren't shown twice.
- **`empty` is judged from UNFILTERED data**: `todayAll.isEmpty() && live == null && backlogAll.isEmpty() && startNext == null`. A lone overdue task becomes the start-next; if `empty` were judged from `backlogRows` (which subtracts start-next) it would falsely read empty and hide the Backlog toggle. Do NOT recompute `empty` from filtered rows.
- **Today list = open-today + completed-today**: `todayOpen = visibleTasks(TODAY, …)`, `todayDone = tasks where isCompletedToday AND not already in todayOpen`, `todayAll = todayOpen + todayDone` (completed sorted last). This keeps today's completions visible (web parity), even though `visibleTasks(TODAY)` itself excludes done tasks.
- **Today is area-agnostic** inside `visibleTasks`, but the screen re-applies the area filter on the rows: `rows = todayAll.filter { areaFilter == null || lifeArea == areaFilter }`. So the Today bucket computation ignores area, but the UI list respects it.
- **Backlog is area-agnostic** by design — entering it clears `areaFilter`.

---

## 2. Data — models + Supabase tables/columns

All domain models are camelCase (`core/model`). The snake_case PostgREST mapping lives in the sync row codecs.

### 2.1 `TaskItem` (table `tasks`)
| Swift field | type | DB column | notes |
|---|---|---|---|
| id | String | `id` (uuid) | |
| name | String | `name` | |
| estimateMin | Int | `estimate_min` | |
| totalFocused | Int (=0) | `total_focused` | seconds |
| done | Bool (=false) | `done` | |
| priority | `Priority?` | `priority` | `urgent/high/medium/low` strings; null→treated as low |
| tags | `[String]?` | `tags` | encode `?? []` |
| objectives | `[Objective]?` | `objectives` (jsonb) | |
| comments | `[Comment]?` | `comments` (jsonb) | |
| intentWhen | String? | `intent_when` | |
| intentThen | String? | `intent_then` | |
| lifeArea | String? | `life_area` | the area **name**, not id |
| firstPhysicalAction | String? | `first_physical_action` | drives Start-Next headline |
| moveCount | Int? | `move_count` | encode `?? 0` |
| completedAt | String? | `completed_at` | ISO |
| later | Bool? | `later` | encode `?? false` |
| recurrence | `Recurrence?` | `recurrence` (jsonb tagged union) | |
| sourceCollectionId | String? | `source_collection_id` | |
| sourceItemId | String? | `source_item_id` | |
| dueAt | String? | `due_at` | |
| createdAt | String | `created_at` | ISO; lexicographic sort |
| updatedAt | String | `updated_at` | LWW key |

### 2.2 `CalBlock` (table `cal_blocks`)
`id`, `task_id` (uuid?), `task_name`, `start_time` (HH:MM), `duration_minutes`, `date` (YYYY-MM-DD), `external_event_id?`, `external_connection_id?`, `kind` (`task`/`placeholder`/`external`). Used by `visibleTasks` to bucket Today/Backlog/Upcoming.

### 2.3 `Session` (table `sessions`)
`id`, `task_id?`, `task_name`, `tags?`, `estimate_min?`, `actual_sec`, `completed_at`. Drives the "This week … focused" pill.

### 2.4 `LifeArea` (table `life_areas`)
`id`, `name`, `color`, `sort_order`. The filter pills and area dots.

### 2.5 `LiveSession` — **device-local only**, never a Supabase table
`id?`, `taskId`, `sessionStart?` (epoch ms), `paused`, `pausedAt?` (epoch ms), `sessionEstimateMin`, `nudge80Fired`, `overrunPromptFired`, `treatment`, `priorAccumulatedSec?`. Persisted in local KV (web `unstuck-session`, Android local store). On iOS: a local JSON blob (e.g. `UserDefaults`/file), NOT GRDB-synced and NOT in Postgres.

### 2.6 View-model–only types (not persisted to server)
- `RecapState(taskName, focusedSec, at)` — transient, set by `finishFocus`, cleared on dismiss/6h expiry.
- `Nudge(id, kind, title, action, taskId?, captureId?)`, `NudgeKind { SLIPPING, CAPTURE }` — computed, not stored. Dismissed-ids are persisted device-local.

---

## 3. Business rules / logic (port the `core/` pure functions verbatim; their tests are the contract)

These live in `core/logic` and have ports/tests in both `core/src/test` and the existing `*.swift` test files. Re-port to Swift and keep the tests green.

### 3.1 `pickStartNext(tasks, blocks, liveTaskId, areaFilter)` → `TaskItem?`
Filter: `!done && later != true && id != liveTaskId`, then `matchesArea(lifeArea, areaFilter)`, sort by the **ranker**, take first.
- **Ranker** (stable sort): priority **desc** → `estimateMin` **asc** → `createdAt` **asc** (lexicographic on ISO string == chronological).
- **priorityRank**: urgent=4, high=3, medium=2, low=1; **null priority → low (1)**.
- `blocks` is accepted but unused here (kept for signature parity).
- Tests (`PickStartNextTest`): ranks by priority→estimate→createdAt; estimate tie broken by createdAt; missing priority = low; excludes done/later/live; honours area filter; returns nil when no candidates.

### 3.2 `pickUpNext(...)` (used elsewhere, port for parity)
Same ranker; skip set = `{liveTaskId, startNextId}`; exclude done/later; `take(limit)` (default 3). Test `upNextSkipsLiveAndStartNextAndLimits` and `upNextExcludesDoneAndLater`.

### 3.3 `visibleTasks(view, tasks, blocks, now, activeArea, activeTag?, slipMode)` → `[TaskItem]`
- Compute block sets via `isTaskBlock`: `todayTaskIds` (block.date == today), `upcomingTaskIds` (date > today), `scheduledTaskIds` (all task blocks), `pastOnlyTaskIds` (scheduled but neither today nor upcoming → overdue).
- `today = Clock.todayIso()` (local-zone YYYY-MM-DD).
- **TODAY**: `!done && later != true && (id in todayTaskIds || (isCreatedToday(t,now) && id !in upcomingTaskIds))`. (Created-today fresh arrivals show, unless the user explicitly scheduled them for a future day.)
- **BACKLOG**: `!done && later != true && !isCreatedToday && (id !in scheduledTaskIds || id in pastOnlyTaskIds)`. (Never-scheduled ≥1 day old, OR only-ever-scheduled-in-the-past/overdue.)
- **UPCOMING**: `!done && id in upcomingTaskIds && id !in todayTaskIds`.
- **LATER**: `!done && later == true`. **COMPLETED**: `done`. **ALL**: `!done || isCompletedToday`.
- **Area filter**: applied to all views **except TODAY** (Today is area-agnostic inside this function).
- **Tag filter**: applied to EVERY view including Today, case-insensitive.
- **slipMode**: keep only `isSlipping`.
- **Ordering**: open first, then completed, preserving order within each bucket (`filter{!done} + filter{done}`).
- Tests (`VisibleTasksTest`): the area-agnostic Today case, all/upcoming honour area, ordering, Today-strict (scheduled + created-today but not older-unscheduled, excludes later), Backlog inclusion/exclusion (8 cases incl. "past+today block → counts as Today not Backlog"), tag filter (incl. AND with area), slipping/daysSinceCreated.

### 3.4 Bucket helpers (`TaskBucket.kt`)
- `isCompletedToday(task, now)`: `completedAt` parses to `t`, and `startOfDay(now) ≤ t < startOfDay+DAY_MS`.
- `isCreatedToday(task, now)`: same window on `createdAt`.

### 3.5 Slip + age (`VisibleTasks.kt`)
- `isSlipping`: not done, AND (`moveCount ≥ 3` OR `now − createdAt ≥ 21 days`).
- `daysSinceCreated`: `(max(0, now−created) / DAY_MS)` floored.
- `ageDays(createdAt, now)` (UI helper): `(startOfDay(now) − startOfDay(created)) / DAY_MS`, clamped ≥0. (Note: **whole-day floor between midnights**, different from `daysSinceCreated`'s raw-ms floor — use the right one: rows use `ageDays`.)

### 3.6 `FocusTimer` (live-session math — port exactly)
- `elapsedSec(live, now)`: `start = sessionStart ?? return 0`; ms = `paused && pausedAt!=nil ? pausedAt−start : now−start`; `max(0, ms/1000)`.
- `displayedElapsedSec = elapsedSec + (priorAccumulatedSec ?? 0)`.
- `pause`: set `paused=true, pausedAt=now` (no-op if no sessionStart). `resume`: `sessionStart += (now − pausedAt)`, clear paused/pausedAt.
- `formatMMSS(sec)`: `[-]MM:SS`, zero-padded, sign for negatives.

### 3.7 `blockKind` / `isTaskBlock` (`CalBlockKind.kt`)
`kind` if present; else external if `external_event_id` set; else `placeholder` if taskId=="placeholder"; else external if taskId starts `"cal-"`; else task. `isTaskBlock = kind==task && taskId non-empty`.

### 3.8 Nudges (`computeNudges` in `AppViewModel`)
- Gated off entirely when `notificationLevel == CALM` (`NotificationLevel.nudges == false`).
- **SLIPPING** only (capture nudge intentionally removed): for each `!done` task where `ageDays ≥ 21` (computed as `(now − createdAt)/86_400_000.0`) OR `moveCount ≥ 3`, emit `Nudge("slip:<id>", SLIPPING, "\u201C<name>\u201D has been waiting a while.", "Open", taskId=id)`.
- `take(3)`, then filter out dismissed ids; the screen shows only `firstOrNull()`.
- Dismissed-nudge ids are persisted device-local and survive relaunch; cleared on sign-out.

---

## 4. Gotchas (do NOT skip these — they're the bugs that bit Android)

1. **kotlinx default-omission / explicit nulls.** The web/server JSONB shape is the contract. Row encoding sets `encodeDefaults=true, explicitNulls=true` so an upsert CLEARS removed fields, and applies web defaults: `tags ?? []`, `move_count ?? 0`, `later ?? false`. In Swift `Codable`, replicate: encode `nil`→explicit JSON null where the column allows it, and emit defaults for tags/moveCount/later. Do NOT let `Codable` silently omit keys you intend to clear. The one server-owned exception elsewhere (`reason_logs.duration_sec`) doesn't apply to Today's tables.

2. **UTC vs local dates.** `Clock.todayIso()` / `startOfDayMillis` use **`ZoneId.systemDefault()` (local)**, matching JS `Date`. `Time.parseMillis` parses ISO instants. **Tests run with `-Duser.timezone=UTC`** — port the iOS tests with `TimeZone` forced to UTC so `todayIso` and the bucket windows are deterministic. The minute ticker exists specifically so `todayIso`/eyebrow roll over at local midnight on an open screen. ISO `createdAt` strings sort lexicographically == chronologically — preserve that in the ranker (don't parse to Date for the tiebreak; string compare).

3. **LWW (last-write-wins).** Task upserts pass `updatedAt` as the LWW key to the local store (`store.upsert(TASKS, t, …, t.updatedAt)`); the server upsert is `onConflict=id`. Hydrate replaces server-canonical sets but **preserves locally-pending and local-external (`g_`) cal_blocks** (their ids aren't UUIDs, never round-trip). On iOS keep the same: every task write stamps a fresh `updatedAt`; the local cache should not overwrite a newer local row with a staler hydrate. Cache-wipe only when the user actually changes (`SyncDecision.shouldWipeCache`).

4. **Exact-alarm / notification denial.** The notifications-off banner exists because reminders can fire in-app/log but never reach the phone when OS notifications are disabled. On iOS the analog is `UNUserNotificationCenter.getNotificationSettings().authorizationStatus`. Re-check on `scenePhase == .active` (the equivalent of `ON_RESUME`), and the banner's tap should deep-link to `UIApplication.openNotificationSettingsURLString` (iOS 16+) / app settings. There is no Android exact-alarm concept on iOS, but the **denied-authorization** path is the one to surface here.

5. **Dependency ordering / start-next & live subtraction.** Two ordering traps: (a) the list must subtract `startNext.id` and `liveId` so they don't double-render; (b) `empty` must be judged from unfiltered `todayAll/backlogAll/startNext/live`, never from the already-subtracted `*Rows`. Also `cal_block` writes depend on the parent task (`dependsOn = task.id`) so the task flushes first — keep that ordering in the iOS outbox.

6. **Live-session is device-local, never synced.** Don't model it as a Supabase row. The 1s ticker must stop when paused (otherwise the paused timer drifts). `displayedElapsedSec` includes `priorAccumulatedSec` so a resumed save-for-later session shows the same running total as the Focus screen — using `elapsedSec` alone would show only the post-resume slice.

7. **`firstPhysicalAction` blank-vs-null.** Start-Next headline uses `firstPhysicalAction` only when **non-blank** (`takeIf { isNotBlank() }`), else falls back to `name`. A whitespace-only value must fall through.

---

## 5. iOS equivalents (mapping table)

| Android / web | iOS target |
|---|---|
| Jetpack Compose (`Composable`, `LazyColumn`, `stickyHeader`) | SwiftUI `View`, `ScrollView`+`LazyVStack` with a pinned `Section`/`pinnedViews: [.sectionHeaders]`, or a fixed top `VStack` + scrolling list. Sticky filter row → `LazyVStack(pinnedViews: .sectionHeaders)`. |
| `collectAsStateWithLifecycle` on `StateFlow` | `@Observable`/`ObservableObject` view model with `@Published`; SwiftUI `@State`/`@Bindable`. The flows (`tasks`, `blocks`, `lifeAreas`, `sessions`, `liveSession`, `lastRecap`, `nudges`) become observable properties. |
| `LaunchedEffect` tickers (60s minute, 1s live) | `Timer.publish(every:on:in:)` / a `Task { while … }` driven by `.task`/`.onChange`; 1s timer gated on `live != nil && !live.paused`; cancel on disappear. |
| `LifecycleEventEffect(ON_RESUME)` | `.onChange(of: scenePhase)` → re-check notif auth when `.active`. |
| `NotificationManagerCompat.areNotificationsEnabled()` | `UNUserNotificationCenter.current().notificationSettings().authorizationStatus != .denied`. Banner tap → open `UIApplication.openNotificationSettingsURLString`. |
| Room / local store + Flows | **GRDB** (preferred) or the existing JSON record-store the iOS app uses. Domain rows persisted; `liveSession`/dismissed-nudges/recap stay in a local KV (`UserDefaults`/file). |
| `kotlinx.serialization` row codec (snake_case `@SerialName`) | Swift `Codable` with `CodingKeys` mapping to snake_case; a `JSONEncoder`/`Decoder` with explicit-null + default-emission behavior matching §4.1. |
| supabase-kt (`client.from(table).upsert{onConflict="id"}` / `.select`) | **supabase-swift** `client.from(table).upsert(row, onConflict: "id")` / `.select()`. Same tables: `tasks`, `cal_blocks`, `sessions`, `life_areas`. |
| WorkManager (outbox flush) | **BGTaskScheduler** (`BGAppRefreshTask`/`BGProcessingTask`) for background outbox drain; foreground flush on app active. Preserve FIFO + `dependsOn` task-before-block ordering. |
| AlarmManager exact alarms | **UNUserNotificationCenter** with `UNCalendarNotificationTrigger`/`UNTimeIntervalNotificationTrigger`. (No exact-alarm-permission concept; the relevant guard is authorization status — §4.4.) |
| Glance widget (`StartNextWidget`, `writeStartNext`) | **WidgetKit** Start-Next widget. The VM already recomputes `pickStartNext(tasks, blocks, live?.taskId, null)` on change and pushes name+estimate to the widget store → on iOS, write to a shared App Group container and call `WidgetCenter.shared.reloadTimelines`. |
| FCM push | **APNs** (the app is already prepped with `platform='web'`/native; mirror with APNs token registration). |
| Android foreground service (focus session keep-alive) | **iOS has no general foreground service.** The live focus timer must be reconstructable from `sessionStart`/`pausedAt` wall-clock math (which `FocusTimer` already is — it's a pure function of `now`), plus optionally a Live Activity / local notification for the running session. Do NOT rely on a continuously-running background process; recompute elapsed from timestamps on foreground. |
| `Icons.*` (Material) | SF Symbols: `tray.and.arrow.down` (inbox), `bell` (notifications), `bolt.fill` (start-next), `play.fill` (Focus), `checkmark.circle.fill` (done). |
| `UFont.serifItalic/sans/mono`, `UTheme.colors` (oklch gradients) | The iOS design system's serif-italic/sans/mono fonts and the color tokens (`ink/ink2/ink3`, `coral/coralSoft/coralDeep`, `amber/amberSoft/amberInk`, `primaryDeep/primarySoft`, `greenSoft/greenInk`, `bg/bg2/surface/line/line2`). Hero gradient = lavender→pink (light) / indigo→plum (dark) — replicate the oklch stops. |

### Callbacks the screen needs from its container (parity with the Kotlin signature)
`onStartFocus(TaskItem)`, `onOpen(TaskItem)`, `onAvatar`, `onSearch` (used for both "Pick another" and empty-state "Add one thing"), `onInsights`, `onNotifications`, `onInbox`, plus inputs `notifUnread: Int`, `inboxCount: Int`. VM methods invoked: `pauseFocus()`, `resumeFocus()`, `dismissRecap()`, `dismissNudge(id)`, `promoteCapture(capture)`, and `nowMs()`.

### Acceptance
Port `PickStartNextTests.swift` and `VisibleTasksTests.swift` (1:1 with the Kotlin tests in §3) and keep them green — they ARE the behavioral contract for the data shown on this screen. The UI states to verify manually: empty hero; start-next hero present/absent; recap card (and its 6h expiry); nudge card (and CALM-level suppression); notifications-off banner toggling on resume; Today vs Backlog toggle (and area-clear on entering Backlog); area filter narrowing rows while Today stays area-agnostic in the bucket; live card running (coral, ticking, Pause) vs paused (amber, frozen, Resume); age badge only in Backlog; per-view empty note only for filtered/backlog-empty.