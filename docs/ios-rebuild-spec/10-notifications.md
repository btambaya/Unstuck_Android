# iOS Rebuild Spec — Notifications & Reminders

> **Reference client:** `unstuck_android` (Kotlin/Compose). This area is rebuilt 1:1 behaviorally in SwiftUI + the iOS frameworks listed in §5. Android is authoritative — where this doc and the old discarded iOS app disagree, follow Android.
>
> **Scope:** Everything that *notifies the user* — system notification channels/categories, on-device exact-alarm reminders (LEAD / ATSTART / DRIFTED), the live focus-session notification, paused-too-long check-ins, FCM/APNs push receipt + token registration, shade/action buttons, the in-app Notification Center screen, and the per-task reminder override. Server payload contract + Supabase tables are included so the iOS engineer knows the wire format and the gating rules.

---

## 0. Mental model (read first)

Unstuck has **seven notification "moments,"** each tuned to a different urgency. Three are *time/schedule* moments fired by **on-device exact alarms** (no server, work offline, survive reboot). One is the *live focus session* notification (Android foreground service; iOS Live Activity). One is the *paused check-in* (client-timer-scheduled, server-cap-gated). Two families are *server pushes* (FCM on Android / APNs on iOS) — session recap, morning/evening brief, collection-share.

The moments are gated by a single device-local `NotificationLevel` enum (Calm / Balanced / Coach), mirrored to the server for the cron-driven moments. Everything that the user *can* see after-the-fact lands in a local **Notification Log**, surfaced by the in-app **Notification Center** screen.

The Android moment ids (used in copy + comments) are: **A1** pre-task lead reminder, **A2** "starts now," **A4** didn't-start follow-up, **B1** live focus, **B2** paused check-in, **B3** session recap, **C1/C2** morning/evening brief.

---

## 1. What it does — behavior, screens, states, flows, edge cases

### 1.1 Notification channels → iOS categories/threads

Android registers one channel per moment (`NotificationChannels.ensureAll`, called at app start in `UnstuckApp.onCreate` and again defensively before every post). iOS has no channel-importance model, so map these to **`UNNotificationCategory` + thread identifiers + interruption levels** (see §5.1). The channels and their intended behavior:

| Android channel id | Name | Importance | Silent? | iOS interruption level |
|---|---|---|---|---|
| `unstuck_reminders` | Task reminders | HIGH (heads-up) | no | `.timeSensitive` |
| `unstuck_recap` | Session recap | DEFAULT | **yes** (no sound) | `.passive` (silent) |
| `unstuck_paused` | Paused check-ins | HIGH (heads-up) | no | `.timeSensitive` |
| `unstuck_daily` | Daily brief | LOW | yes | `.passive` |
| `unstuck_nudges` | Gentle nudges | MIN | yes | `.passive` |
| `focus_timer` | Focus session | LOW, ongoing | n/a | Live Activity (not a notification) |
| `unstuck_collab` | Shared lists | HIGH (heads-up) | no | `.timeSensitive` |

Cross-cutting channel rules (replicate as `UNNotificationContent` config):
- **All channels hide content on the lock screen** (`VISIBILITY_PRIVATE`) with a public version reading title "**unstuck**" / body "**Unlock to read**." iOS equivalent: respect the user's "Show Previews" setting; you cannot force-privatize, but do **not** put sensitive task names in a place that ignores previews. Keep the title generic-safe.
- **No badge** (`setShowBadge(false)` on every channel). iOS: do not set `badge` on these contents; leave app icon badge at 0 unless product later asks.
- All grouped under one channel group "Unstuck." iOS: use `threadIdentifier` to group, and one notification grouping summary if desired.
- Brand tint: running = **coral `#E89077`**, paused = **amber `#E0A33A`**. iOS notifications can't tint; carry these only into the Live Activity / in-app UI.

### 1.2 The three exact-alarm reminders (A1 / A2 / A4) — `ReminderScheduler`

For each upcoming calendar block (task blocks + external calendar events) within a **48-hour horizon**, up to three alarms are armed. They are **re-synced reactively** whenever blocks or tasks change (`observe()` collects `store.blocks() combine store.tasks()`), and **fully rebuilt** on boot / app-update / settings change (`reschedule()`).

| Alarm | Tag | Fires at | Gated by | What the user sees |
|---|---|---|---|---|
| **LEAD (A1)** | `lead` | `start − leadMinutes` | **all levels** (requires `lead > 0`) | "**Coming up**" + body. Tap → opens task. No actions. |
| **ATSTART (A2)** | `atstart` | exactly `start` | **Balanced + Coach** (`level.atStart`) | "**Time to start**" — "*"<name>" starts now.*" + **Start** / **Reschedule** actions. |
| **DRIFTED (A4)** | `drifted` | `start + 10 min` | **Coach only** (`level.drifted`) | "**Didn't get to it?**" — *""<name>" was set for a little while ago — want to start now?*" + **Start** / **Reschedule** actions. |

Per-block selection logic in `sync()`:
- A block is eligible if it `isTaskBlock(b)` **or** is an EXTERNAL calendar event. Placeholders are skipped.
- **Skip done tasks**: if the block's task `done == true`, schedule nothing for it.
- **Lead minutes**: external events always use the **global** `reminderLeadMin`; task blocks use the **per-task override** if present, else the global default. Only arm LEAD when `lead > 0`.
- **ATSTART / DRIFTED** are task-only (external events get only a lead reminder).
- An alarm is only armed if `fireAt` is in `(now, now + 48h]` — past or beyond-horizon times are dropped.

**LEAD body copy** (`ReminderReceiver`): `lead > 0` → "*<name> — in <lead> minutes.*"; `lead == 0` would read "*<name> is starting.*" (but lead==0 is never armed). Deep link: `unstuck://task/<taskId>` if task, else `unstuck://today`.

**Fire-time re-check (A2/A4 only):** when an ATSTART or DRIFTED alarm fires, the receiver **re-reads live state** and suppresses the notification if the task is now `done` **or** is the currently-focused live session (`getLiveSession()?.taskId == taskId`). This prevents nudging about something already handled. LEAD does **not** re-check.

**Diffing / cancellation:** the set of armed keys (`"<tag>:<blockId>"`) is persisted (Android: SharedPreferences `unstuck.reminders/scheduled`, comma-joined). On each `sync()`, `previous − current` keys are cancelled. iOS: persist the set of pending request identifiers (see §1.10) and remove the stale ones on each re-sync.

**Edge cases to preserve:**
- External calendar events have a **blank task id** → key the LEAD notification id off the **block id** instead, so two near-simultaneous events don't collide/overwrite. (Android: `NotifIds.reminder(taskId.ifBlank { blockId })`.)
- Boot/update re-arm: Android `ReminderReceiver` handles `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` to rebuild all alarms. iOS doesn't terminate scheduled `UNNotificationRequest`s on reboot/update, so this is largely **free** — but you must still re-sync on every launch and on relevant state changes (see §5.3 gotcha).

### 1.3 "Start" / "Reschedule" shade actions (A2/A4) — `NotificationRenderer.postTaskStarting`

The starts-now and drift notifications carry two action buttons that work with the app closed:
- **Start** → opens the app straight into Focus for that task and begins the timer. Deep link `unstuck://focus/<taskId>` (Android launches the Activity; iOS uses a foreground action + the deep link).
- **Reschedule** → handled in the **background without opening the app**: moves the block to the next free slot today (see §3.2 `ScheduleCommands.rescheduleToNextSlot`), bumps the task's `moveCount`, re-arms alarms, and replaces the notification with a "**Rescheduled**" confirmation ("*"<name>" moved to <12h clock>.*") that auto-dismisses after **8 s**.
- Tapping the notification body (not an action) opens `unstuck://task/<taskId>`.

> iOS note: Android's "Reschedule" runs in a `BroadcastReceiver` with no UI. iOS background notification actions run in the app's background execution context via `UNUserNotificationCenterDelegate` — replicate the no-UI reschedule there (foreground option **off**), then post the confirmation. "Start" needs `.foreground` so it launches the app into Focus.

### 1.4 Live focus-session notification (B1) — `FocusTimerService` → Live Activity

Android backs a live focus session with a **foreground service** showing an ongoing, non-dismissible notification with a chronometer:
- **Running:** coral, subtext "**FOCUSING · LIVE**", title = task name, live chronometer counting from `sessionStartMs`, actions **Pause** / **Capture**.
- **Paused:** amber, subtext "**CHECK-IN**", title "**Did you step away?**", body = task name, actions **Resume** / **Snooze** / **End**.
- Same notification id (`1001`), flipped in place via `update()`. On resume the chronometer base is shifted past the pause gap so it counts **true focus time**, not wall-clock.
- Started/updated/stopped from the Focus screen lifecycle (`FocusScreen.LaunchedEffect(sessionStart)` → `start`; paused-state changes → `update`; Done/Stop → `stop`) and from `MainScaffold` for cross-screen consistency.

> **iOS:** this maps to a **Live Activity (ActivityKit)** + Dynamic Island, *not* a notification (iOS forbids persistent foreground-service notifications). The Pause/Capture/Resume/Snooze/End buttons become Live Activity interactive buttons (App Intents). See §5.4 for the constraint and the fallback. The action **semantics** (pause arms the paused check-in, resume cancels it, etc.) are identical — see §1.6.

### 1.5 Action handling — `NotificationActionReceiver`

All shade actions route through one handler that mutates via shared `FocusCommands` (same writes as the in-app view model). Actions and their effects:

| Action | Effect |
|---|---|
| **Pause** | `FocusCommands.pause` → flip live notif to paused (`FocusTimerService.update(paused=true)`), **arm the paused check-in** (`PausedCheckinScheduler.arm`). |
| **Resume** | `FocusCommands.resume` (re-arms chronometer with post-resume start), **cancel** the paused check-in, dismiss the paused notif (id `2002`). |
| **Snooze** | dismiss paused notif, **re-arm** the check-in (`snooze` == `arm` again, ~14 min out). |
| **End** | `FocusCommands.end`, cancel check-in, dismiss paused notif, stop the live notif. |
| **Capture** | open app into quick-capture (`MainActivity` extra → `pendingDeepLink = "capture"`). |
| **Reschedule** | dismiss the start-now/drift notif, run `ScheduleCommands.rescheduleToNextSlot` in background. |

> **Critical correctness rule (Android `goAsync()`):** the write-performing actions keep the receiver alive until the async store write commits — otherwise tapping from the shade on a cold/low-priority process truncates the coroutine and leaves the live session inconsistent. **iOS equivalent:** in `userNotificationCenter(_:didReceive:withCompletionHandler:)`, do **not** call the completion handler until the GRDB write + server round-trip (where applicable) finishes. Use a background task assertion (`beginBackgroundTask`) around the work.

### 1.6 Paused-too-long check-in (B2) — `PausedCheckinScheduler`

When a session is paused, arm a **one-shot ~14-minute** delayed check. On fire:
1. Bail if the level disables paused check-ins (Calm). (`level.pausedCheckin`)
2. Bail if the session is no longer paused (resume/end cancel the work, but guard the race).
3. **Ask the server** whether a push is allowed (`NotificationsClient.pausedCheckin()` → `send-paused-checkin`, which enforces the shared daily cap + the `paused_checkin_muted_until` mute + the disabled-pref). Only if `allowed == true` do we post.
4. Post the local check-in (`postPausedCheckin`): amber, title "**Did you step away?**", body = task name, actions **Resume** / **Snooze** / **End**, deep link `unstuck://today`. Logs to the Notification Center as kind `paused_checkin`.

Snooze re-arms the same 14-min check. Resume/End cancel it.

> Android uses `WorkManager` (`OneTimeWork`, `ExistingWorkPolicy.REPLACE`, survives process death, no 15-min floor). iOS: schedule a **`UNTimeIntervalNotificationTrigger` at 14 min is wrong** because the server-allow check must run first. Instead schedule a **silent local check** — see §5.5: the cleanest iOS port is a 14-min `UNTimeIntervalNotificationTrigger` that posts the check-in directly, BUT gate it by doing the server `pausedCheckin()` call at *arm* time isn't possible (state may change). Recommended: use a **BGAppRefreshTask / background-delivered silent push**, or pre-fetch allowance and re-validate on app foreground. Document this divergence explicitly; the *user-visible behavior* (a check-in appears ~14 min into a forgotten pause, respecting the cap+mute+level) must hold.

### 1.7 Push receipt (FCM → APNs) — `Push.kt` / `UnstuckMessagingService`

Incoming server pushes are **data-driven**: the server sends `data.kind`, `data.title`, `data.body`, `data.deepLink`. The client routes `kind` → channel + stable id (`NotificationRenderer.channelFor`):

| `kind` | Channel | Notes |
|---|---|---|
| `session_recap` | recap | silent |
| `paused_checkin` | paused | (server-driven variant) |
| `morning_brief`, `evening_preview`, `daily_nudge` | daily | brief; deep link `unstuck://today/brief` |
| `reminder`, `event_soon` | reminders | |
| `collection_share` | collab | shared list shared/done/late |
| (unknown / null) | recap (fallback) | |

**Coexistence id rule:** incoming pushes get a content-derived id `0x60000 + hash(kind|deepLink|title|body) & 0xFFFF`, so two distinct pushes coexist but an FCM retry of the identical payload collapses to one. iOS: set the `UNMutableNotificationContent` request **identifier** to the same content hash (or use APNs `apns-collapse-id` = that hash) so retries coalesce and distinct pushes don't overwrite.

Every rendered push is appended to the Notification Log.

### 1.8 Token registration

- On every transition to **Authenticated** (and on launch while signed in, since the session StateFlow replays its current value), fetch the device push token and `register()` it with the `register-push-token` edge function. Android sends `{ deviceId, fcmToken, platform: "android", timezone }`. **iOS sends `{ deviceId, apnsToken, platform: "ios", apnsEnvironment, timezone }`** (the function already has the iOS branch; see §2.3).
- `deviceId`: Android uses `Settings.Secure.ANDROID_ID`. iOS: use `identifierForVendor` (stable per-vendor) — persist it so it survives.
- On token **refresh**, re-register.
- On **sign-out**: `PushClient.unregister(deviceId)` deletes this device's `device_tokens` row **while the signing-out user's JWT is still valid** (RLS = `user_id = auth.uid()`), so the next user on this device never gets the previous user's brief. Also wipe the local Notification Log + per-user device-local content (reminder overrides, dismissed nudges, archived captures) — see §1.11.

### 1.9 Notification Center screen — `NotificationCenterScreen`

In-app screen reached from the bell icon next to the avatar. Two sections:

**Upcoming** — scheduled task reminders in the next **2 days**, computed **live** from the blocks (not from the log). For each task block whose start time is within `[now, now+48h]` and whose task isn't done, show a card with the task name + a relative-future label ("in 35m" / "in 4h" / "in 2d"). De-duped by `(taskId, at)`, sorted ascending, capped at **20**. Tapping opens that task. Hidden entirely if empty.

**Recent** — the persisted Notification Log, newest first. Each row: a colored dot (accent by kind — see below), title, and "`<body>  ·  <relative-past>`" ("just now" / "5m ago" / "3h ago" / "2d ago"). Tap routing:
- deep link starts with `unstuck://task/` → open that task;
- any other non-blank deep link → route through the app's deep-link handler;
- no deep link → not tappable.

Empty state: "*Nothing yet. Reminders and recaps will show up here.*"

**Accent-by-kind** (`accentFor`): `paused_checkin / atstart / drifted` → **amber**; `session_recap` → **green**; `morning_brief / evening_preview / daily_nudge` → **primaryDeep** (indigo); everything else → **coral**.

The bell shows an **unread badge** driven by `NotificationLog.lastSeen` vs the newest entry's `at`. Opening the center marks all seen (`markAllSeen`).

### 1.10 Notification Log — `NotificationLog`

Process-wide singleton persisting the **last 60** shown notifications to a small key-value store (Android: SharedPreferences `unstuck.notiflog/log` as JSON), exposed as an observable list + a `lastSeen` long. Each `Entry`: `{ id (uuid), kind, title, body, deepLink?, at (epoch ms) }`. Adds are **atomic read-modify-write** (`updateAndGet`) so a concurrent FCM + alarm post can't drop an entry. `init()` loads at app start; `markAllSeen()` stamps `lastSeen`; `clear()` wipes both on sign-out. **Reminders that haven't fired are NOT stored** — the "Upcoming" section computes those live.

### 1.11 Per-task reminder override

In the task-create / schedule sheet, a "**Remind me**" row of chips: **Default** (null → use global), **Off** (0), **5m / 10m / 15m before**. Persisted device-locally as `reminder.override.<taskId>` (only for scheduled, non-"Later" tasks — a Later task has no block to fire on). Read by `ReminderScheduler` for the LEAD alarm. Cleared per-user on sign-out (`clearUserContent` removes all `reminder.override.*`).

### 1.12 NotificationLevel UI + server mirror

`Settings → Focus → Notification level`: **Calm / Balanced / Coach** with blurbs (verbatim copy in §3.1). Default **Balanced**. Changing it: (a) re-syncs reminder alarms (`ReminderScheduler.reschedule`); (b) **mirrors** to the server (`PreferencesClient.setNotificationLevel(uid, morningBrief, pausedCheckin)`) so the cron morning-brief + server paused-checkin cap honor it — **best-effort, only when the value actually changed and a user id exists.**

Global "remind me N min before" (`reminderLeadMin`, default 10, 0=Off) also lives in Settings and re-syncs alarms on change.

---

## 2. Data — models + Supabase tables/columns

### 2.1 Client models (core/model — keep identical field names/JSON for sync parity)

**`CalBlock`** (`Models.kt`): `id`, `taskId: String?`, `taskName`, `startTime: "HH:MM"`, `durationMinutes: Int`, `date: "YYYY-MM-DD"`, `externalEventId: String?`, `externalConnectionId: String?`, `kind: CalBlockKind?` (`task`/`placeholder`/`external`). The scheduler reads `date`+`startTime` to compute `startMs` in the **device's local zone**.

**`TaskItem`** (relevant fields): `id`, `name`, `estimateMin`, `done: Bool`, `moveCount: Int?`, plus `dueAt`, `sourceCollectionId/sourceItemId` (for late-nudge escalation), `updatedAt` (LWW key). `moveCount` is bumped on reschedule.

**`NotificationLog.Entry`** (§1.10): `id, kind, title, body, deepLink?, at`. Client-only, never synced.

**`SettingsState`** scalars relevant here: `reminderLeadMin: Int = 10`, `notificationLevel: NotificationLevel = BALANCED`. Plus per-task `reminder.override.<id>: Int`. All device-local key-value (never synced; reminders fire locally).

**`NotificationLevel`** (Calm/Balanced/Coach) with derived booleans: `atStart` (≠Calm), `drifted` (==Coach), `pausedCheckin` (≠Calm), `morningBrief` (≠Calm), `nudges` (≠Calm).

### 2.2 Supabase tables (read; this area only **writes** device_tokens + notification_preferences; reads happen server-side in crons)

**`device_tokens`** (migration 014 + 017): `id, user_id, device_id, platform ('ios'|'android'|'web'), apns_token, fcm_token, apns_environment ('production'|'sandbox'), live_activity_push_to_start_token, timezone (IANA), is_primary, last_active_at, created_at, updated_at`. Unique `(user_id, device_id)`. **Owner-only RLS; NOT in the realtime publication** (tokens are credentials). iOS writes `apns_token` + `apns_environment` + leaves `fcm_token` null.

**`live_activity_tokens`** (014): per-running-activity push token — `(user_id, device_id, activity_id, push_token, session_id)`. Used if you wire server-pushed Live Activity updates (optional; Android has no equivalent — it drives the live notif locally).

**`notification_preferences`** (015): `user_id (PK)`, `session_recap_enabled`, `paused_checkin_enabled`, `morning_brief_enabled`, `sound_enabled`, `haptics_enabled`, wake-window fields, `quiet_hours_start/end`, `skip_weekends`, `timezone`, `daily_push_cap (default 3)`, `paused_checkin_muted_until`, `ignore_count`. Seeded on signup. The client's `setNotificationLevel` upserts only `morning_brief_enabled` + `paused_checkin_enabled` (onConflict `user_id`).

**`notification_ledger`** (016): `(user_id, local_date, moment, sent_at)` — the 3/day push cap counter. Enforced by `try_consume_push_budget(p_user, p_date, p_moment)` (security-definer; returns true + records a send if under cap). The client never writes this directly; the edge functions do.

**`notification_queue`** (016): audit/retry + always-on in-app recap card.

**`wake_window_history`** (015): rolling first-input times the wake-window cron medians (not written by this area, but registration's `timezone` feeds it).

### 2.3 Server payload contract (what the iOS push handler must parse)

All sends are **data-only** pushes with `data: { kind, title, body, deepLink }`. Known `(kind, deepLink)`:
- `morning_brief` → `unstuck://today/brief`
- `session_recap` → (recap deep link; silent channel)
- `collection_share` → collections deep link
- `reminder` / `event_soon` → `unstuck://task/<id>` or `unstuck://today`
- `evening_preview`, `daily_nudge` → today/brief

`send-paused-checkin` returns `{ allowed: boolean, reason? }` — **the client must suppress its local check-in when `allowed != true`.** `register-push-token` returns `{ ok: true }` and reclaims any other user's row sharing the same token (shared-device leak defense).

---

## 3. Business rules / logic (cite pure-logic + tests)

### 3.1 NotificationLevel — single source of truth (`SettingsStore.kt`)

Exact verbatim labels + blurbs (use these strings in iOS):
- **Calm** — "*Only the essentials — pre-task reminders and your session recap.*"
- **Balanced** — "*Reminders, a start-now nudge with Start/Reschedule, paused check-ins, the morning brief, and quiet in-app nudges.*"
- **Coach** — "*Everything in Balanced, plus a nudge if you haven't started on time and more proactive prompts.*"

Derived gates (port exactly): `atStart = self != .calm`; `drifted = self == .coach`; `pausedCheckin = self != .calm`; `morningBrief = self != .calm`; `nudges = self != .calm`. `fromLabel` falls back to Balanced.

### 3.2 `ScheduleCommands.rescheduleToNextSlot` (pure-logic backed)

Steps: find the block + task; estimate = `task.estimateMin ?? block.durationMinutes`; `slot = findFreeSlotsForDate(blocks, estimate, today, now, limit=1).firstOrNull()`; new date = slot.date ?? today; new time = slot.startTime ?? `plusHour(block.startTime)`; upsert the moved block; `bumpMoveCount(task)`; re-arm alarms; post the "Rescheduled" confirmation. `plusHour` adds 60 min clamped to `23:59`.

- **`findFreeSlots` / `findFreeSlotsForDate`** (`core/logic/FreeSlots.kt`, port of `lib/free-slots.ts`) — day window **08:00–18:00**, step = `max(durationMin, 30)`, today snaps to `ceil((nowMin+5)/15)*15`, every block (task/placeholder/external) is a conflict. Tests: `FreeSlotsTest.kt` — `emptyDayStartsAtDayStart`, `skipsTooShortWindows`, `todaySnapsToNext15MinAfterNowPlus5`, `futureDateIndependentOfNow`, `limitsResults`. **Port these tests to Swift verbatim** — the slot math must match the web/Android byte-for-byte or reschedule lands on different times across clients.
- **`bumpMoveCount(task, nowISO)`** (`TaskMutations.kt`): `moveCount = (moveCount ?? 0) + 1`, `updatedAt = nowISO`. Tests: `TaskMutationsTest.kt` — `bumpMoveCountIncrementsFromNull`, `bumpMoveCountIncrementsExisting`, `bumpMoveCountSetsUpdatedAt`.
- **`isTaskBlock(b)`** (`CalBlockKind.kt`, port of `lib/cal-block-kind.ts`): `blockKind(b) == TASK && taskId not empty`. `blockKind` precedence: explicit `kind` → `externalEventId` non-empty ⇒ EXTERNAL → `taskId == "placeholder"` ⇒ PLACEHOLDER → `taskId startsWith "cal-"` ⇒ EXTERNAL → else TASK. The scheduler/center both depend on this — port it and its `CoreModelsTest` cases.

### 3.3 Server gating (don't re-implement, just honor)

`send-paused-checkin` returns `allowed=false` if `paused_checkin_enabled == false`, if `paused_checkin_muted_until > now`, or if `try_consume_push_budget` says the daily cap (default 3) is hit (counted in the user's local-date timezone). The client treats **any failure / unreachable server as `allowed=false`** (`getOrDefault(false)`). The morning-brief cron similarly checks `morning_brief_enabled` + the cap and pivots on each user's `timezone`. iOS must keep `setNotificationLevel` mirroring accurate so these gates reflect the user's choice.

---

## 4. Gotchas (do not regress these)

1. **kotlinx default-omission → Codable parity.** Android leaves `platform` (and `LoginTracker`'s `platform`) **without a default** because kotlinx omits default-valued fields when `encodeDefaults` is off — a default would drop `platform` from the JSON body and the server falls through to its `'ios'` branch, mislabeling the row so FCM never routes. **For iOS:** the analogous risk is `Encodable` silently encoding the wrong/optional fields or `nil` apns/fcm tokens. Always send `platform: "ios"` **explicitly and non-optionally**, and never send `fcmToken` for an iOS row. Verify the actual JSON on the wire.
2. **UTC date pickers.** Material3's `selectedDateMillis` is UTC-midnight; Android reads/seeds it in UTC to avoid west-of-UTC users landing a day early. SwiftUI date pickers are local by default — but when you convert a picked date to the `"YYYY-MM-DD"` block date, compute it in the **device's local calendar**, and compute `startMs` via local-zone `DateComponents` (Android uses `ZoneId.systemDefault()`), matching `blockStartMs`. A mismatch fires reminders an hour or a day off.
3. **LWW / `updatedAt`.** Reschedule writes go through the same sync path as everything else; the last-writer-wins key is `updatedAt`. `bumpMoveCount` stamps `updatedAt = Instant.now()` — ensure the iOS reschedule sets `updatedAt` to a fresh ISO-8601 UTC instant so the move isn't lost to a stale remote row. Reminder overrides + the log are **device-local and never synced** — don't accidentally push them.
4. **Exact-alarm denial (Android-specific, but has an iOS analog).** Android prompts once for `SCHEDULE_EXACT_ALARM` and falls back to inexact alarms if denied (delayed by Doze). It deliberately does **not** request the Play-restricted `USE_EXACT_ALARM`. **iOS analog:** local-notification scheduling is exact and needs no special permission, *but* you must request **notification authorization** (`UNUserNotificationCenter.requestAuthorization`) and request the **time-sensitive interruption-level entitlement** for the heads-up moments. If the user denies notifications, scheduling silently no-ops — surface this gracefully (Android keeps re-prompting on a later cold start only if a reminder-driven moment is enabled; mirror with a settings nudge, don't nag every launch).
5. **Dependency ordering / `goAsync()` parity.** Shade actions that write must not let the process die mid-write (§1.5). On iOS, hold the action's completion handler + a background-task assertion until the GRDB write (and the server allow-check for paused) completes.
6. **Channel-importance immutability.** Android can never change a channel's importance after first creation (hence the new stable ids and the "never reuse `unstuck_push`" comment). iOS has no equivalent lock-in, but **keep category identifiers + thread ids stable** across releases so grouping/relaunch behavior doesn't reset, and so the action handlers keep matching.
7. **Coexistence vs collapse ids.** Per-task families are spaced `0x10000` apart (`REMINDER_BASE 0x30000`, `ATSTART_BASE 0x40000`, `DRIFTED_BASE 0x50000`, push `0x60000`) and offset by a 16-bit hash of the id — so a lead, a starts-now, and a drift for the same task **coexist**, while a re-issue of the same one updates in place. External events key off the **block** id (blank task id). On iOS, build the request `identifier` from the same `(family, id)` scheme so updates replace and distinct items coexist; for retried server pushes use `apns-collapse-id`.
8. **Re-check at fire time (A2/A4).** Don't render a starts-now/drift if the task is now done or actively focused. iOS local notifications can't run code before display, so the cleanest port is: at fire time you can't re-check, therefore **proactively cancel** the pending ATSTART/DRIFTED request the moment the task is completed or the live session for it starts (hook into the same store observers `ReminderScheduler.observe` uses). Document this inversion — the user-visible guarantee ("no nudge for a handled task") must hold.
9. **Best-effort, never throw on auxiliary calls.** Token register, level mirror, login track, and `pausedCheckin()` are all `runCatching`/best-effort and must never block sign-in or crash the app.
10. **Sign-out cleanup ordering.** `unregister(deviceId)` must run **before** the JWT is invalidated (RLS), and the local log + per-user overrides must be wiped so a second account on the device starts clean.

---

## 5. iOS equivalents — concrete mapping

### 5.1 Channels → `UNNotificationCategory` + interruption levels
Create one `UNNotificationCategory` per actionable moment (paused check-in, starts-now/drift, collab) carrying its `UNNotificationAction`s; use `threadIdentifier` for grouping and `interruptionLevel` per the table in §1.1 (`.timeSensitive` for reminders/paused/collab — requires the **Time Sensitive Notifications** capability/entitlement; `.passive` for recap/daily/nudges). No badge. Generic-safe titles for lock-screen privacy.

### 5.2 Compose → SwiftUI
`NotificationCenterScreen` → a SwiftUI `List`/`LazyVStack` with two sections (Upcoming/Recent), driven by `@Published` arrays. Reuse the relative-time formatters (`relFuture`/`relPast`) verbatim — minute/hour/day buckets with the exact strings. Accent dots per §1.9.

### 5.3 Room/store + WorkManager → GRDB / BGTaskScheduler
- `store.blocks()`/`store.tasks()` Flows → GRDB `ValueObservation` (or your JSON store's Combine publishers). `ReminderScheduler.observe` → a long-lived subscription that re-syncs on every emission.
- **Re-sync triggers:** app launch (`applicationDidFinishLaunching`), `willEnterForeground`, and every store change. iOS keeps scheduled `UNNotificationRequest`s across reboot/app-update (so no `BOOT_COMPLETED` receiver needed) — but the 48h horizon means you must re-extend on each foreground; use a `BGAppRefreshTask` to re-sync when the app is backgrounded long enough.

### 5.4 AlarmManager exact alarms → `UNUserNotificationCenter`
Each LEAD/ATSTART/DRIFTED alarm → a `UNNotificationRequest` with a `UNCalendarNotificationTrigger` (non-repeating) at the computed local fire date, identifier per the family scheme (§4.7). Diff against `getPendingNotificationRequests` and `removePendingNotificationRequests(withIdentifiers:)` for the stale set, mirroring the `prev − now` cancellation. Persist nothing extra — the pending requests *are* the source of truth, but mirror Android's persisted key set if you need fast diffing.

### 5.5 Foreground service / Live Activity (B1) — the iOS constraint
iOS has **no foreground-service persistent notification**. Use **ActivityKit Live Activity** for the running/paused focus session (Dynamic Island + lock-screen), with App Intent buttons for Pause/Capture/Resume/Snooze/End wired to the same `FocusCommands` writes. The chronometer uses `Text(timerInterval:)`; on resume, shift the interval start past the pause gap (matches `update(startMs:)`). The paused-check-in scheduling (§1.6) is the part that genuinely diverges: prefer a 14-min local check that, on fire, is **pre-validated** by having cancelled it on resume/end, plus a foreground re-validation; or drive it server-side via a scheduled silent push if you want exact cap parity. **Call this out in the handover as the one non-1:1 area.**

### 5.6 Glance widget → WidgetKit
Out of scope for *this* area (the Start-Next widget is separate), but note the live focus session may also surface in a WidgetKit Live Activity rather than a Glance ongoing notification.

### 5.7 FCM → APNs
Replace `FirebaseMessaging.token` with `UNUserNotificationCenter` authorization + `UIApplication.registerForRemoteNotifications()` → `didRegisterForRemoteNotificationsWithDeviceToken` (hex-encode the token) → `PushClient.register(apnsToken:, platform:"ios", apnsEnvironment:)`. `didReceiveRemoteNotification` / the `UNUserNotificationCenterDelegate` parses `data.kind/title/body/deepLink` exactly as §1.7 and renders + logs. Use `apns-collapse-id` for retry collapse.

### 5.8 supabase-kt → supabase-swift
`PushClient`, `NotificationsClient`, `PreferencesClient`, `LoginTrackerClient` (`sync/Clients.kt`) port directly: `functions.invoke("register-push-token" | "send-session-recap" | "send-paused-checkin" | "track-login")` and `from("device_tokens").delete()` / `from("notification_preferences").upsert(onConflict:"user_id")`. Keep `Content-Type: application/json` set explicitly on every function invoke (Android sets `contentType(ContentType.Application.Json)` deliberately), and **set `platform` explicitly** (§4.1).

---

## 6. Acceptance checklist for the iOS engineer

- [ ] Notification authorization + **Time Sensitive** entitlement requested; denial handled gracefully (no per-launch nagging).
- [ ] LEAD/ATSTART/DRIFTED scheduled exactly per `NotificationLevel`, 48h horizon, 08–18 window math via ported `FreeSlots` tests; stale requests removed on re-sync; external events keyed off block id.
- [ ] ATSTART/DRIFTED suppressed when task done or live (cancel-on-state-change inversion documented).
- [ ] "Start" launches Focus; "Reschedule" reschedules in background (no UI), bumps `moveCount`, re-arms, posts 8 s confirmation.
- [ ] Live Activity for running/paused focus with all five action buttons → `FocusCommands`; chronometer counts true focus time post-resume.
- [ ] Paused check-in at ~14 min, gated by level + server `allowed`, with Resume/Snooze/End; snooze re-arms.
- [ ] APNs token registered on auth + refresh; `platform:"ios"` explicit; unregister on sign-out before JWT invalidation; log + overrides wiped.
- [ ] Push receipt routes `kind`→category, coexist/collapse ids correct, appends to log.
- [ ] Notification Center: Upcoming (live, 48h, ≤20, de-duped) + Recent (log, ≤60, newest-first) with correct accents, relative-time strings, tap routing, unread badge, empty state.
- [ ] Per-task reminder override chips (Default/Off/5/10/15m) persisted device-locally, read by the scheduler, cleared on sign-out.
- [ ] NotificationLevel UI with verbatim copy; change re-syncs alarms + mirrors `morning_brief_enabled`/`paused_checkin_enabled` to the server (best-effort, only on change).
- [ ] Ported core tests (`FreeSlotsTest`, `TaskMutationsTest` move-count cases, `CalBlockKind`) pass in Swift.

**Key Android source files** (read these against your Swift): `surface/NotificationChannels.kt`, `NotificationRenderer.kt`, `ReminderScheduler.kt`, `ReminderReceiver.kt`, `NotificationActionReceiver.kt`, `FocusTimerService.kt`, `PausedCheckinScheduler.kt`, `ScheduleCommands.kt`, `Push.kt`, `NotificationLog.kt`; `ui/notifications/NotificationCenterScreen.kt`; `SettingsStore.kt` (NotificationLevel + override); `sync/Clients.kt`; `core/logic/{FreeSlots,TaskMutations,CalBlockKind}.kt` + their tests; `supabase/migrations/{014,015,016,017}` + `functions/{register-push-token,send-paused-checkin,send-session-recap,send-morning-brief}`.