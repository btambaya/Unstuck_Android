## Feature Deep-Dives: Four Features, End-to-End

This chapter walks four flagship features from the pixel the user taps, down through the `AppViewModel`, into `:core` (pure logic) and `:sync` (the offline-first engine), and out to the Supabase Edge Functions. Read it alongside the module map: `:app` (Compose UI + Android surface), `:core` (pure, testable domain logic), `:data` (Room `LocalStore`), `:sync` (Supabase client + write-through outbox), `:design` (theme/components). The backend lives in the **web repo** under `supabase/functions/` and `supabase/migrations/`.

One mental model to hold throughout: **the UI never talks to Supabase directly.** Every write goes `Composable → AppViewModel.fun → WriteThrough → (LocalStore optimistic write) + (outbox enqueue)`. Room `Flow`s re-emit instantly so the screen updates; the `OutboxFlusher`/`SyncCoordinator` drains to the server in the background. Reads come from `AppViewModel` `StateFlow`s backed by Room (`AppViewModel.kt:60-71`).

---

### (A) Calendar + two-way Google sync

#### Where it lives
- UI: `ui/calendar/CalendarScreen.kt` (Day/Week/Month switcher + the connect bar), `ui/calendar/DayGrid.kt` (the interactive day grid, tap-to-create, drag-to-schedule/reschedule, the block-edit sheet).
- ViewModel: `ui/AppViewModel.kt` — `scheduleTask`, `moveBlock`, `resizeBlock`, `unschedule`, `blockTime`, plus the Google passthroughs `beginGoogleConnect`, `syncCalendar`, `disconnectCalendar`.
- Sync engine: `sync/SyncCoordinator.kt` (pull/push orchestration), `sync/CalendarClient.kt` (Edge-Function HTTP), `sync/WriteThrough.kt` (the push hooks).
- Pure mapping: `core/logic/GoogleSyncMapping.kt` (`externalEventToBlock`, `blockToIsoRange`), `core/logic/CalBlockKind.kt` (`isTaskBlock`), `core/logic/FreeSlots.kt` (`findFreeSlotsForDate`).
- Deep-link entry: `MainActivity.kt`.
- Backend: `supabase/functions/calendar-sync/index.ts` + `providers/google.ts`.

#### The three grids
`CalendarScreen` (CalendarScreen.kt:64) renders an `MdSegment("Day"/"Week"/"Month")` and switches between `DayGridScreen`, `WeekView`, and `MonthView`. All three read the same Room-backed flows (`vm.blocks`, `vm.tasks`, `vm.lifeAreas`, `vm.sessions`) — they are pure projections of the local cache:

- **Day** (`DayGrid.kt`): a vertically scrolling 0–24h grid, `HOUR_HEIGHT = 56.dp`. Blocks are absolutely positioned by `parseHhmm(b.startTime)`. A "NOW" coral line is drawn on today. Below the grid is the **unscheduled tray** — `tasks.filter { !it.done && it.later != true && it.id !in scheduledIds }`.
- **Week** (`CalendarScreen.kt:119`): Monday-anchored 7-column grid (`WHOUR = 44.dp`), plus three roll-up stats (focus planned / busiest / lightest) computed from `isTaskBlock` blocks per day.
- **Month** (`CalendarScreen.kt:196`): a focus-density heatmap. It does **not** read blocks — it reads `vm.sessions`, buckets `actualSec` by completed-day, and `lerp`s cell color by intensity.

Block fill color encodes **source** (DayGrid.kt:174): `EXTERNAL → blueSoft`, a task block → its life-area swatch (`areaColorFor`), placeholder → neutral. This mirrors the web's `bgFor()`.

#### Tap-to-create
The grid `Column` has a `detectTapGestures` (DayGrid.kt:145). On tap it converts the y-offset to minutes, snaps to a 15-min grid, and calls `onCreateAt(date, "HH:MM")`. That bubbles up through `MainScaffold.kt:109` to `newTaskPrefill = d to t; showNewTask = true`, opening `NewTaskSheet(prefillDate, prefillTime)`. The sheet creates the task (`vm.addTask`) and, when a time is set, calls `vm.scheduleTask(t, effectiveDate, pickedTime)` (NewTaskSheet.kt:223-230). So tap-to-create is really *create task + schedule block* in one gesture.

#### Drag-to-schedule and drag-to-reschedule
Both use `detectDragGesturesAfterLongPress` and a shared "ghost" overlay that follows the finger. The geometry trick is worth understanding because it recurs:

- Every draggable records its **window-space** origin via `onGloballyPositioned { origin = it.localToWindow(Offset.Zero) }`.
- `dragPos` is tracked in window coords (`origin + local`, then `+= delta`).
- `dropTimeOrNull()` (DayGrid.kt:104) checks `gridBounds.contains(dragPos)`, converts `dragPos.y − gridBounds.top + scroll.value` to minutes, and snaps to 15.
- The ghost is drawn by subtracting the root Box's window origin (`rootOrigin`) so it renders 1:1 under the finger.

Two drop paths:

```
tray task  → onDragEnd → drop()      → vm.scheduleTask(task, date, dropTime)
task block → onDragEnd → dropBlock() → vm.moveBlock(block, date, dropTime)
```

> **Gotcha:** only **task blocks** are draggable (`if (isTaskBlock(b))` at DayGrid.kt:191). EXTERNAL (`g_…`) blocks are tap-only — they mirror the remote calendar read-only, so letting you drag them would imply an edit the pull would immediately revert.

#### The block-edit sheet
Tapping any block opens `CalBlockEditSheet` (DayGrid.kt:275). It re-derives free slots live (`findFreeSlotsForDate(blocks, duration, date, now, limit=5)`), offers start-time chips (`vm.moveBlock`), duration chips 15/25/45/60/90 (`vm.resizeBlock`, clamped 15–360), and Unschedule (`vm.unschedule`). It tracks the **live** block from the flow (`blocks.firstOrNull { it.id == block.id }`) so sequential edits compose.

#### `scheduleTask` — the subtle one
`AppViewModel.scheduleTask` (AppViewModel.kt:163) mirrors the web `persistOrMove` and is the function most likely to bite you:

- **First placement** creates a `CalBlock(kind = TASK)` and does **not** bump `moveCount`.
- **Moving** an existing block updates it in place and bumps `moveCount` **only if the date/time actually changed** (so the slip-detector and the "slipping" nudge stay honest).
- **Recurring** tasks diff via `regenerateForTask` instead of re-inserting a whole horizon each tap.

#### The connect flow (the HTTPS bounce page)
This is the part new engineers always trip on. Google's Web OAuth client **rejects custom schemes** (`unstuck://`) as redirect URIs. So:

```
[Connect button] CalendarSyncBar (CalendarScreen.kt:96)
   → vm.beginGoogleConnect()
   → SyncCoordinator.beginGoogleConnect() (SyncCoordinator.kt:86)
       → calendar.authorize(CAL_REDIRECT)         POST calendar-sync/authorize
       redirectUri = "https://unstuck-602.pages.dev/calendar-callback"   ← HTTPS bounce
   ← { url, state }   (state stored in pendingCalState for CSRF)
   → CustomTabsIntent.launchUrl(url)              opens Google consent

[Google] → redirects to https://unstuck-602.pages.dev/calendar-callback?code&state
[bounce page] → forwards to unstuck://calendar-callback?code&state

[MainActivity.handleAuthOrCalendar] (MainActivity.kt:67)
   data.host == "calendar-callback" → completeGoogleConnect(code, state)
       → state == pendingCalState ?  (CSRF guard, SyncCoordinator.kt:95)
       → calendar.connectGoogle(code, CAL_REDIRECT, state)   POST calendar-sync/connect
       → hydrate()      (pulls the new calendar_connections row → bar flips to "Synced")
       → pullCalendar()
```

`CAL_REDIRECT = "https://unstuck-602.pages.dev/calendar-callback"` (SyncCoordinator.kt:237). **This exact URL must be registered as an Authorized redirect URI on the Google Web OAuth client.** The bounce page is the same one iOS uses.

Server-side (`calendar-sync/index.ts`): `/authorize` (index.ts:116) signs an HMAC `state` bound to the user (`signState(userId)`) and builds the Google consent URL via `buildGoogleAuthUrl` (`google.ts:29`) with `access_type=offline` + `prompt=consent` (both required to force a refresh token). `/connect` (index.ts:133) verifies `state` server-side (`verifyState`), exchanges the `code` for a **refresh_token** server-side via `exchangeGoogleCode` (using `GOOGLE_CLIENT_SECRET`), encrypts it (`encryptToken`), and inserts the `calendar_connections` row, defaulting `selected_calendar_ids` to **all** readable calendars.

> **Why server-side code exchange?** Supabase's PKCE flow never hands provider tokens to the browser, so the client cannot read a `provider_refresh_token`. The code→token exchange must happen in the Edge Function where the client secret lives.

#### Pull (Google → Unstuck)
`SyncCoordinator.pullCalendar()` (SyncCoordinator.kt:107) runs on sign-in, after connect, on manual "Sync now", and in the periodic `SyncWorker`:

1. Window = `[today−7d, today+30d]`.
2. **Critical detail:** Google's `events.list` requires **RFC3339 instants** for `timeMin`/`timeMax`. A bare `YYYY-MM-DD` is rejected (400) and silently returns zero events. So the code sends full instants (`atStartOfDay(zone).toInstant().toString()`, SyncCoordinator.kt:118-120) but reconciles locally with date-only bounds.
3. `calendar.pullEvents(fromIso, toIso)` → `GET calendar-sync/events` → per-connection `googleAdapter.listEvents` (`google.ts:164`, with `singleEvents=true`, `orderBy=startTime`, cancelled events filtered out).
4. **Dedup against our own pushes:** events whose id is already stamped on a local TASK block's `externalEventId` are skipped — otherwise you'd get a duplicate `g_` block sitting next to the task block (SyncCoordinator.kt:124-128).
5. Remaining events → `externalEventToBlock(ev, calendarId)` → `CalBlock(id = "g_${ev.id}", kind = EXTERNAL)`. The deterministic `g_<id>` id means re-pulls overwrite the same row.
6. **Deletion reconciliation:** in-window EXTERNAL blocks Google no longer returns are deleted locally (SyncCoordinator.kt:134-136).

#### Push (Unstuck → Google)
Wired as hooks on `WriteThrough` in `SyncCoordinator.init` (SyncCoordinator.kt:56-57). Whenever any TASK block is upserted, `WriteThrough.upsertCalBlock` (WriteThrough.kt:37) calls `pushCalBlock`:

- `SyncCoordinator.pushBlockUpsert` (SyncCoordinator.kt:171): non-task → no-op; no Google connection → no-op.
- **Always writes to `"primary"`**, not `selectedCalendarIds` — those can include read-only/subscribed calendars that 403 on insert. `"primary"` is Google's always-writable alias.
- If the block already has `externalEventId` → PATCH; else INSERT and return the new id.
- Back in `WriteThrough.upsertCalBlock` (WriteThrough.kt:48-53): the returned event id is **stamped** onto the block (`b.copy(externalEventId = eventId)`) and re-persisted, so future edits PATCH the same event and pulls won't duplicate it.
- Deletes route through `pushBlockDelete` (SyncCoordinator.kt:192).

> **Gotcha — never push `g_` rows back:** `WriteThrough.upsertCalBlock` early-returns for `EXTERNAL` kind or any id starting with `g_` (WriteThrough.kt:42). Those aren't our DB rows; enqueueing them would fail forever and **stall the entire FIFO outbox**.

#### How to extend: add a "Block time" (placeholder) drag source
There's already `vm.blockTime(date, startTime, duration, label)` (AppViewModel.kt:233) creating a `PLACEHOLDER` block. To surface it as a drag chip:
1. Add a "+ Block" chip to the tray in `DayGrid.kt`, give it `onGloballyPositioned`/`detectDragGesturesAfterLongPress` like the task chips.
2. On drop, call `vm.blockTime(date, dropTimeOrNull()!!, 30, "Focus block")`.
3. No backend change: `PLACEHOLDER` blocks are local-only for Google (`pushBlockUpsert` returns null for non-TASK), and `WriteThrough` still enqueues them to our own `cal_blocks` table.

---

### (B) Focus sessions

#### Where it lives
- Pure state machine: `core/logic/FocusTimer.kt` (port of `lib/use-focus-timer.ts`), model `core/model/Models.kt:193` (`LiveSession`), `core/model/Enums.kt:29` (`FocusTreatment`).
- UI: `ui/focus/FocusScreen.kt`, `ui/focus/CaptureSheet.kt`, `ui/focus/ReflectSheet.kt`.
- ViewModel: `AppViewModel.startFocus/pauseFocus/resumeFocus/setTreatment/extendFocus/finishFocus/cancelFocus` (AppViewModel.kt:241-289).
- Surface: `surface/FocusTimerService.kt` (foreground/ongoing notification), `surface/FocusCommands.kt` (process-level mutations shared with notification actions).

#### The state machine is pure
`FocusTimer` (FocusTimer.kt) is an `object` of pure functions over `LiveSession + now: Long`. Nothing here touches Room, Compose, or clocks — that's why `FocusTimerTest.kt` can drive it deterministically. Key derivations:

- `elapsedSec(live, now)` — seconds of *this* session (excludes `priorAccumulatedSec`); freezes at `pausedAt` when paused.
- `displayedElapsedSec` — `elapsedSec + priorAccumulatedSec` (what the UI shows).
- `deriveState(live, now, overrunGraceSec)` → `IDLE / PAUSE / RUNNING / OVERRUN`.

Transitions return a new `LiveSession`:

- `start` (FocusTimer.kt:68) is **resume-aware**: same task + paused → `resume`; same task + running → no-op (a double "Start" won't reset); otherwise a fresh session seeded with `priorAccumulatedSec`.
- `pause` stamps `pausedAt`; `resume` shifts `sessionStart` forward by the pause gap (so elapsed math stays a simple `now − sessionStart`).
- `done` clears `sessionStart`; `extend` adds minutes and clears `overrunPromptFired`.

`LiveSession` is persisted in Room as a single-row state (`store.liveSession()` / `getLiveSession()`), so it survives process death and is glanceable cross-screen.

#### The three treatments
There is **no** separate `Ambient.kt`/`Cockpit.kt`/`Monk.kt` — the treatments are rendered inline in `FocusScreen.kt` by branching on `live.treatment` (the file comment in the prompt notwithstanding):

- **AMBIENT** (default): draws the progress ring + `Orbit` (FocusScreen.kt:126-136). Ring goes amber when paused, coral on overrun.
- **COCKPIT**: same chrome plus a `CapturesRail` (FocusScreen.kt:146) showing the last 3 captures for the task — the "instrument panel" treatment.
- **MONK**: strips everything — no treatment chips, no task name, no ring; just the timer. The treatment switcher itself is hidden in MONK (`if (treatment != FocusTreatment.MONK)`, FocusScreen.kt:115).

Switching treatment calls `vm.setTreatment(t)` which both mutates the live session **and** persists the choice as the default (AppViewModel.kt:254-257), so your last treatment is remembered next session.

#### Lifecycle: start / pause / resume / finish
```
Open FocusScreen
  LaunchedEffect(task.id) → vm.startFocus(task)        (resume-aware, sets treatment)
  LaunchedEffect → 1s ticker drives nowMs (display only)
  LaunchedEffect(sessionStart) → FocusTimerService.start(...)   foreground ongoing notif
  LaunchedEffect(paused) → FocusTimerService.update(paused) + arm/cancel PausedCheckin

Pause  → vm.pauseFocus() ; if focusPauseReasons → PauseReasons overlay → vm.saveReasonLog
Resume → vm.resumeFocus()
"← Out" → onClose() — leaves the timer RUNNING (session persists; NOT discarded)

Done       → vm.finishFocus(task, markDone=true)  + service.stop + ReflectSheet
End for now→ vm.finishFocus(task, markDone=false) + service.stop + ReflectSheet
Save for later → vm.pauseFocus() (+ optional reason) → onClose()  (resumable from Today)
```

`finishFocus` (AppViewModel.kt:266) is the terminal write:
1. computes `elapsed = FocusTimer.elapsedSec(live, now)`,
2. writes a `Session` row (`taskId`, `estimateMin`, `actualSec`, `completedAt`),
3. updates the task's `totalFocused += elapsed` (and flips `done` via `applyCompletion` when `markDone`),
4. clears the live session,
5. fires `notifications.sessionRecap(task.name, away = false)` (in-app finishing ⇒ `away=false`, so no push — see section C),
6. sets `_lastRecap` → the Today "You did the thing." card.

> **Gotcha — two finish actions, not one.** "End for now" (`markDone=false`) is the safe default: it records the session but leaves the task open, and because `FocusTimer.start` is resume-aware with `priorAccumulatedSec`, returning later continues the accumulated total rather than restarting. "Done" marks complete. Don't collapse them.

> **Gotcha — leaving ≠ ending.** "← Out" only calls `onClose()`. The `FocusTimerService` is deliberately **not** stopped on `DisposableEffect` dispose — the session stays live and the ongoing notification keeps ticking. It's torn down only by Done/End/cancel.

#### Capture and Reflect
- **Capture** (`CaptureSheet.kt`): a quick note attached to the task (`vm.saveCapture(task.id, sessionId, tag, body)`), tagged FOLLOW_UP/IDEA/EDIT/QUESTION/DISTRACTION. Captures are preserved across sessions and feed the COCKPIT rail and the "promote to task" nudge.
- **Reflect** (`ReflectSheet.kt`): shown after Done/End. Currently a UI-only "How did that land?" prompt (flow/okay/sticky/stopped) — note it does **not** persist the choice yet (Save just dismisses). A natural first task for a new engineer is to wire it to `vm.saveReasonLog` with a `ReasonAction.REFLECT`.

#### How to extend: add a "Breathing" treatment
1. Add `@SerialName("breathing") BREATHING` to `FocusTreatment` (`Enums.kt:29`). The web/iOS enums must match the `@SerialName` strings, since this serializes into `live_sessions`.
2. In `FocusScreen.kt`, add a `treatment == FocusTreatment.BREATHING` branch where the AMBIENT ring is drawn.
3. Because the treatment chips iterate `FocusTreatment.entries`, the new chip appears automatically.
4. No backend or `:sync` change — `LiveSession` is local-only state.

---

### (C) Notifications

This is the largest surface. The guiding principle: **the device decides *what* to render and *when* (locally); the server decides *whether* a push is allowed** (cap + preference) and sends remote pushes for moments the app can't time itself.

#### Channels and ids
`surface/NotificationChannels.kt` registers one channel per "moment", created once at app start (`UnstuckApp.onCreate → ensureAll`). Importance is immutable after first creation, so ids are new+stable:

| Channel id | Moment | Importance |
|---|---|---|
| `unstuck_reminders` | A1/A2/F1 pre-task + event-soon | HIGH (heads-up) |
| `unstuck_recap` | B3 session recap | DEFAULT, silent |
| `unstuck_paused` | B2 paused-too-long | HIGH (heads-up) |
| `unstuck_daily` | C1/C2 morning/evening | LOW, silent |
| `unstuck_nudges` | overflow/gentle | MIN, silent |
| `focus_timer` | B1 ongoing focus | LOW, ongoing |

All default to `VISIBILITY_PRIVATE` ("Unlock to read"). Stable notification ids are in `NotifIds` so re-issuing updates in place.

#### Pre-task reminders (local, exact alarms)
`surface/ReminderScheduler.kt` keeps one **exact** `AlarmManager` alarm per upcoming block at `start − leadMinutes`:

- `observe()` (called from `UnstuckApp`) combines `store.blocks()` + `store.tasks()` and re-`sync`s on any change.
- `sync()` (ReminderScheduler.kt:48) iterates blocks in a 48h horizon. **Lead resolution:** per-task override (`settingsStore.reminderOverride(taskId)`) else the global default (`reminderLeadMin`, default 10; `0 = off`). EXTERNAL blocks use the global lead (the F1 "event soon"). Skips done tasks.
- It diffs against a `SharedPreferences` set of previously-scheduled block ids and cancels removed ones.
- `setAlarm` (ReminderScheduler.kt:82): `setExactAndAllowWhileIdle` if `canScheduleExactAlarms()`, else falls back to inexact `set`. Manifest declares `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM`.
- On fire, `ReminderReceiver` (ReminderReceiver.kt) renders via `NotificationRenderer.renderPush(kind="reminder", deepLink="unstuck://task/$id")`.
- **Boot reschedule:** the same receiver also handles `ACTION_BOOT_COMPLETED` (alarms don't survive reboot) → `ReminderScheduler.reschedule(app)` (ReminderReceiver.kt:11, manifest `RECEIVE_BOOT_COMPLETED`).

#### Paused-too-long (WorkManager + server cap)
`surface/PausedCheckinScheduler.kt`: when a session is paused, `FocusScreen`/`NotificationActionReceiver` calls `arm()` → a unique `OneTimeWorkRequest` ~14 min out (`OneTimeWork` has no 15-min floor and survives process death). On fire, `PausedCheckinWorker.doWork`:
1. re-checks the live session is still paused (guards races),
2. calls `notifications.pausedCheckin()` → `POST send-paused-checkin` → returns `{ allowed }`,
3. **only if allowed**, posts the local check-in (`NotificationRenderer.postPausedCheckin` with Resume/Snooze/End actions).

The server function (`send-paused-checkin/index.ts`) does **not** send a push — it's pure cap-coordination. It respects `paused_checkin_enabled` + `paused_checkin_muted_until`, then calls `try_consume_push_budget` so the paused check-in shares the same daily budget as remote pushes. Returns `false` ⇒ client suppresses.

#### Session recap (push + Today card)
`finishFocus` always sets `_lastRecap` (the in-app coral "You did the thing." card, rendered in `TodayScreen.kt:142`) and calls `sessionRecap(taskName, away)`. Server (`send-session-recap/index.ts`):
1. **Always** inserts an `in_app` `notification_queue` row (no cap).
2. If `away == false` or `session_recap_enabled == false` → stop (no push). Finishing in-app ⇒ `away=false`; ending from the **notification shade** (`FocusCommands.end`) ⇒ `away=true`.
3. Else `try_consume_push_budget('session_recap')`, pick the most-recently-active device, route FCM for Android / APNs for iOS.

#### Morning brief (server cron)
There is **no** client trigger. `supabase/manual/notification_cron.sql` registers a `pg_cron` job `morning-brief-dispatch` every 15 min (UTC), pivoting on each user's IANA `timezone` (`now() at time zone tz`). When local time hits the wake window, `dispatch_morning_briefs` calls `send-morning-brief` per user via `pg_net` with an `x-cron-secret` header. That function (`verify_jwt = false`, guarded by the secret) ranks the top-3 tasks (a Deno port of `pick-start-next`), applies the cap, and sends a data-only FCM push (`kind: morning_brief`, `deepLink: unstuck://today/brief`).

> **Gotcha:** this cron file lives in `supabase/manual/`, **not** `migrations/`, so `db push` never auto-applies it. It must be run by hand after deploying the function, enabling `pg_cron`+`pg_net`, and setting `CRON_SECRET`.

#### In-app nudges (no push)
`AppViewModel.nudges` (AppViewModel.kt:212) computes up to 3 quiet, in-app-only nudges (slipping tasks ≥21 days old or `moveCount ≥ 3`; a recent FOLLOW_UP capture worth promoting), surfaced on Today (`TodayScreen.kt:161`) and dismissible. These never become OS notifications.

#### FCM routing (data-only payloads)
`surface/Push.kt`: `UnstuckMessagingService.onMessageReceived` prefers **data fields** over the `notification` block, then routes by `data["kind"]`:

```
onMessageReceived → NotificationRenderer.renderPush(kind = data["kind"], title, body, deepLink)
   channelFor(kind):  session_recap→RECAP, paused_checkin→PAUSED,
                      morning_brief/evening_preview/daily_nudge→DAILY,
                      reminder/event_soon→REMINDERS, else→RECAP
```

The server sends **data-only** messages (`fcm.ts` `dataOnly: true`, folding `title`/`body` into `data`). This is deliberate: data-only lets the Android client pick the correct channel, deep link, and actions in **every** app state (foreground/background/killed), instead of the OS auto-posting a notification block to a default channel. Token registration happens on every authenticated session in `MainActivity` (`registerFcmToken`) and on `onNewToken`, persisted server-side by `register-push-token` (keyed `user_id,device_id`, with `platform="android"` + timezone).

#### The 3/day cap
`migrations/016_notification_ledger.sql` defines `try_consume_push_budget(p_user, p_date, p_moment)` — a `security definer` count-then-insert against `notification_ledger`. Cap = `notification_preferences.daily_push_cap` (default 3). It's **atomic** (claim a slot or return false) and **shared across all moments and devices**, so recap + paused + brief together can't exceed 3 pushes/day.

#### How to extend: add an "evening preview" push
1. `channelFor` already maps `evening_preview → DAILY` — no client change needed to render it.
2. Add a `send-evening-preview` Edge Function modeled on `send-morning-brief`, calling `try_consume_push_budget('evening_preview')` and `sendFcmPush({ kind: 'evening_preview', dataOnly: true })`.
3. Add a second `cron.schedule` block in `notification_cron.sql`.
4. If you want a deep link target, handle `unstuck://today/preview` in `MainActivity` routing.

---

### (D) Auth

#### Where it lives
- Client config: `sync/SupabaseClientProvider.kt` (PKCE + scheme), `sync/AuthService.kt`.
- ViewModel passthroughs: `AppViewModel.kt:444-461`.
- UI: `ui/auth/AuthScreen.kt`.
- Deep link: `MainActivity.kt` (`handleDeeplinks`).
- Error/anti-enumeration logic (pure): `core/logic/AuthErrors.kt`.
- Backend: `supabase/functions/account-delete`, plus `_shared/jwt.ts` (the trust model every function relies on).

#### PKCE configuration
`SupabaseClientProvider` (SupabaseClientProvider.kt:24) installs Auth with `flowType = FlowType.PKCE`, `scheme = "unstuck"`, `host = "auth-callback"`, and auto load/save from storage. PKCE is required so the OAuth/magic-link redirect returns to a custom-scheme deep link the app can complete without a client secret.

#### The methods (all via `AuthService`)
`AuthService` (AuthService.kt) is a thin wrapper over supabase-kt Auth returning a sealed `AuthOutcome` (`Ok`/`Error`). Error copy always goes through `humanizeAuthError` (`:core`). Supported:

- **Password**: `signIn` (`signInWith(Email)`), `signUp` (`signUpWith(Email)`, seeding `full_name`/`display_name` metadata).
- **Magic link**: `sendMagicLink` (`signInWith(OTP)`).
- **Google**: `signInWithGoogle` (`signInWith(Google)`).
- **Account management**: `resetPassword`, `changePassword`, `updateDisplayName`, `deleteAccount` (calls the `account-delete` Edge Function then signs out), plus `hasPassword` (true iff an `email` identity exists — distinguishes Google-only accounts).

`AuthScreen.kt` is a single screen that toggles between sign-in and sign-up, with Google, magic-link, forgot-password, and inline error rendering.

#### The deep-link callback
```
MainActivity.handleAuthOrCalendar(intent)  (onCreate + onNewIntent)
   if host == "calendar-callback" → completeGoogleConnect  (section A)
   else → graph.provider.client.handleDeeplinks(intent)   ← supabase-kt PKCE handler
```

`unstuck://auth-callback?...` is consumed by supabase-kt's `handleDeeplinks`, which exchanges the PKCE code and emits a `SessionStatus.Authenticated`. The manifest declares the `unstuck` scheme on `MainActivity` (`launchMode="singleTask"`, so the callback reuses the existing activity via `onNewIntent`).

#### What sign-in actually triggers
The session change is the **engine's ignition**. `SyncCoordinator.start()` collects `client.auth.sessionStatus`; on `Authenticated` (SyncCoordinator.kt:201) it: classifies the event (SignIn/SignUp/External → `SIGNED_IN`; Storage → `INITIAL_SESSION`; UserChanged → `USER_UPDATED`), applies the **cache-wipe rule** (`SyncDecision.shouldWipeCache` — wipe on a *different* user, keep on same-user reload, using `prevUserId` in `SharedPreferences`), then `flusher.flush(uid)` → `hydrator.hydrate()` → `realtime.subscribeAll(uid)` → `pullCalendar()`. On `NotAuthenticated.isSignOut`, it unsubscribes realtime, `store.clearAll()`, and clears `prevUserId`. `AppViewModel.authed` (AppViewModel.kt:76) exposes the same status as a nullable `Boolean?` for the splash/gate.

#### Anti-enumeration
`core/logic/AuthErrors.kt` is the privacy-preserving layer:

- `humanizeAuthError` maps codes to gentle copy and notably **does not** distinguish "wrong password" from "no such user" — both surface as a generic invalid-credentials message, so an attacker can't enumerate accounts.
- `detectSignupAlreadyExists` (AuthErrors.kt:62): Supabase's sign-up returns a "successful" response even for an already-registered email (anti-enumeration by design). This pure helper detects the tells — empty `identities`, or a confirmed/previously-signed-in user with no session — so the UI can show the right "try signing in" hint without the server leaking existence.
- `nextSafePath` (AuthErrors.kt:52) is an open-redirect guard for `?next=` (same-origin single-slash paths only).

#### The server-side JWT trust model
Every authenticated Edge Function uses `_shared/jwt.ts` `decodeJwt` — it **base64-decodes** the JWT payload to get `sub`, it does **not** verify. That's safe **because** `config.toml` sets `verify_jwt = true` for those functions, so Supabase validates the signature *before* the function runs. The comment documents the lesson: `auth.getUser()` inside a function adds a round-trip to the auth service that can wedge under load. (`send-morning-brief` is the exception: `verify_jwt = false`, guarded by `x-cron-secret`.)

#### How to extend: add Apple sign-in
1. Add `signInWithApple()` to `AuthService` mirroring `signInWithGoogle` (supabase-kt has an `Apple` provider).
2. Add a passthrough in `AppViewModel` and a button in `AuthScreen.kt`.
3. The redirect already works — Apple's OAuth returns to the same `unstuck://auth-callback` PKCE handler in `MainActivity`; no new deep-link routing needed.
4. `hasPassword` already handles non-password identities, so account-settings UI degrades correctly.

---

### Cross-cutting gotchas to internalize early
- **The outbox is FIFO and dependency-ordered.** A row that can never POST (e.g. a `g_` external block, or a cal_block whose `dependsOn` task never flushes) stalls everything behind it. This is why `WriteThrough` is so careful to skip `g_`/EXTERNAL rows.
- **`scheduleTask` bumps `moveCount` only on a *real* date/time change.** Don't "simplify" it to always bump — you'll break the slip nudge.
- **Google needs RFC3339 instants, not dates**, for `events.list` (silent 400 → zero events).
- **Task blocks push to `"primary"`, never `selectedCalendarIds`** (read-only calendars 403 on insert).
- **`finishFocus(markDone=false)` is the safe default**, and `FocusTimer.start` is resume-aware via `priorAccumulatedSec` — sessions accumulate, they don't restart.
- **Data-only FCM payloads** are intentional; don't switch the server to `notification`-block sends or you lose channel/deep-link/action control in background/killed states.
- **Same writes from two entry points**: `FocusCommands` (notification-shade actions) and `AppViewModel` (UI) must stay behaviorally identical — they both drive the same `LocalStore` + `WriteThrough`. Change one, change both.

Key files to bookmark: `SyncCoordinator.kt`, `WriteThrough.kt`, `AppViewModel.kt`, `FocusTimer.kt`, `DayGrid.kt`, `NotificationRenderer.kt`, `ReminderScheduler.kt`, and on the backend `supabase/functions/calendar-sync/index.ts` + `_shared/fcm.ts` + `_shared/jwt.ts`.
