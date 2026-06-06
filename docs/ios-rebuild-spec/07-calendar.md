# iOS Rebuild Spec — Calendar & Google Calendar

**Reference client:** Unstuck Android (`unstuck_android`). The existing SwiftUI iOS app is discarded; this is a 1:1 behavioral replica.
**Scope of this spec:** the Calendar tab (Day / Week / Month views + the Google-sync bar), the drag-to-schedule Day grid, the block-edit sheet, the FreeSlots scheduling logic, and the Google Calendar two-way sync (`CalendarClient` + the sync-coordinator pull/push).

Android sources this spec is derived from:
- `app/.../ui/calendar/CalendarScreen.kt` — Day/Week/Month + sync bar
- `app/.../ui/calendar/DayGrid.kt` — drag-to-schedule grid, NOW line, lanes, block-edit sheet
- `core/.../logic/FreeSlots.kt` (+ `FreeSlotsTest.kt`) — free-slot finder, conflicts, time formatting
- `core/.../logic/GoogleSyncMapping.kt` (+ `GoogleSyncMappingTest.kt`) — event↔block transforms
- `core/.../logic/CalBlockKind.kt` — block-kind classification
- `sync/.../CalendarClient.kt` — Edge Function client
- `sync/.../SyncCoordinator.kt` — OAuth consent + pull/push orchestration
- `sync/.../WriteThrough.kt`, `Hydrator.kt`, `SyncDecision.kt`, `RealtimeMirror.kt`, `DbRowCodec.kt`, `OutboxFlusher.kt`
- `app/.../ui/AppViewModel.kt` — `scheduleTask` / `moveBlock` / `resizeBlock` / `unschedule`
- `app/.../MainActivity.kt` — `unstuck://calendar-callback` deep link
- `app/.../surface/SyncWorker.kt` — periodic background sync
- Supabase: `supabase/functions/calendar-sync/index.ts`, `supabase/migrations/001,006,009,013`

---

## 1. What it does — behavior, screens, states, flows

### 1.1 The Calendar screen shell

A single screen with an app bar titled "Calendar" and a 3-way segmented control: **Day / Week / Month** (default `"Day"`). Directly below the segment sits the **CalendarSyncBar** (always visible regardless of selected view). The body switches on the segment:
- `"Day"` → `DayGridScreen`
- `"Week"` → `WeekView`
- else → `MonthView`

Segment state is local screen state; it resets to "Day" when the screen is recreated. (No persistence.)

### 1.2 CalendarSyncBar (Google Calendar connect / sync / disconnect)

Reads `vm.connections` (the local cache of `calendar_connections` rows). Two states:

**No connections** (`conns.isEmpty()`):
- A single pill button: `"＋ Connect Google Calendar"`. While the authorize call is in flight it shows `"Connecting…"` and is disabled (`busy` flag).
- On tap: call `vm.beginGoogleConnect()` (suspend) → returns an authorize URL or `null`.
  - `null` → show inline error: `"Couldn't reach Google. Check your connection and try again."`
  - non-null → open the URL in an in-app browser tab. If no browser, error `"No browser available to open Google sign-in."`

**One or more connections:**
- A status line: `"Syncing…"` while busy, else `conns.joinToString(", ") { "Synced · ${accountEmail}" }` (lists **every** connected account, comma-separated — not just the first).
- `"Sync now"` action — calls `vm.syncCalendar()` (pulls events). Disabled + dimmed while busy. On failure: inline error `"Sync failed. Try again."`.
- `"Disconnect"` action — opens a confirm dialog (destructive):
  - Title: `"Disconnect Google Calendar?"`
  - Body: `"Synced events are removed from your calendar. Your tasks are unaffected."`
  - Confirm `"Disconnect"` (red) → calls `vm.disconnectCalendar(it.id)` **for every connection** in `conns`. Cancel `"Cancel"`.

Inline error text renders red below the row when set.

### 1.3 Day view (`DayGridScreen`) — the core interactive surface

**Layout (top→bottom):**
1. **Day switcher row:** `‹` prev / center label / `›` next. Center label is `"Today"` when the viewed date equals today's ISO, else the raw ISO date (`YYYY-MM-DD`). Prev/next shift the date by ±1 day (date math, see §3).
2. **Hour grid:** a vertically-scrolling 24-hour grid, `START_HOUR=0` to `END_HOUR=24`, **`HOUR_HEIGHT = 56` pt per hour**. A left gutter (64 pt wide) shows hour labels formatted 12-hour via `formatTime("%02d:00")` (e.g. "9:00 AM"). Each hour row is a full-width box with a 0.5pt border.
3. **Unscheduled tray:** label `"Drag onto the grid to schedule"`, then a horizontally-scrolling row of chips, one per unscheduled task, capped at the **first 20** tasks. Each chip reads `"${task.name} · ${task.estimateMin}m"`.

**Initial scroll:** when the viewed date is today, on first composition the grid scrolls to `(now.hour - 1)` hours from top (clamped ≥ 0), i.e. ~1 hour before the current time.

**NOW line (today only):** a 1.5pt coral horizontal line across the grid at the current time, plus a small coral `"NOW"` pill badge at the line's left. It must **advance live** — drive it from a 30-second ticker (Android polls `LocalTime.now()` every 30 s; do not freeze it at first render). Hidden on non-today dates.

**Midnight rollover:** a separate 30-second ticker watches the system date. If the calendar date changes (crossed midnight) **and** the user is still viewing the old "today", auto-advance the viewed date to the new today so the NOW line and "Today" label don't get stuck on yesterday.

**Blocks rendering:** all `cal_blocks` whose `date == viewedDate` are absolutely positioned by start time:
- vertical offset = `HOUR_HEIGHT * (startMinutesFromMidnight / 60)`
- height = `HOUR_HEIGHT * (durationMinutes / 60)`, floored at **24 pt** (so short blocks stay tappable).
- Only render blocks with `startMinutes ≥ 0`.
- **Color by source** (mirrors web `bgFor`):
  - `kind == EXTERNAL` → blue-soft
  - task block → the owning task's **life-area swatch** color (via `areaColorFor(task.lifeArea, areas)`)
  - placeholder / other → neutral bg2
- A done task's block shows the label in muted ink with strikethrough.
- Label = `block.taskName`, single line.
- **Overlap handling (lanes):** time-overlapping blocks split the column width into side-by-side lanes via `layoutLanes` (§3.4). The available block area is `gridWidth − 82pt` (70pt left inset for the gutter + ~12pt right). Lane width = `(gridWidth − 82) / lanes`; each block offset horizontally by `laneWidth * laneIndex`; width = `laneWidth − 3pt` (min 20pt). Single-lane blocks fill the width with `start=70, end=12` insets.

**Interactions on a block:**
- **Task blocks** (`isTaskBlock`): tap → opens the **block-edit sheet** (§1.4); long-press → **drag** to reschedule.
- **External / placeholder blocks:** display-only. Taps are swallowed (must NOT fall through to the grid's create-task tap handler). They are NOT draggable and NOT editable — they mirror the remote calendar and any local edit reverts on next sync.

**Tap empty grid → create task at that time:** tapping the grid body (but NOT in the 64pt hour-label gutter — taps with `x < 64pt` are ignored) computes the snapped time and calls `onCreateAt(date, "HH:MM")`, opening the task-create flow prefilled to that date+time. Snap: `totalMin = round((y / hourPx) * 60)`, then snap down to the nearest 15 min, clamped to `[0, 24*60 − 15]`.

**Drag-to-schedule from tray:** long-press an unscheduled-task chip → a coral "drag ghost" pill (`task.name`, white text) follows the finger. On release:
- If the finger is over the grid bounds, compute the drop time (snapped to 15 min, clamped `[0, 24*60−15]`) and call `vm.scheduleTask(task, viewedDate, dropTime)`.
- If released off-grid, no-op (drag cancelled).

**Drag-to-reschedule a block:** long-press a task block → same drag ghost (`block.taskName`). On release over the grid, `vm.moveBlock(block, viewedDate, dropTime)`. Note: dropping on a different day than the viewed date is not possible from Day view — the drop always uses the currently-viewed `date`.

**Drop-time math** (`dropTimeOrNull`): `yInGrid = (dragPos.y − gridTop) + scrollOffset`; `totalMin = round((yInGrid / hourPx) * 60)`; snap down to 15 min; clamp `[0, 24*60−15]`; format `HH:MM`. Returns `nil` if the drag point isn't inside the grid bounds.

**Unscheduled-tray membership:** a task appears in the tray iff `!done && later != true && task.id ∉ scheduledIds`, where `scheduledIds` = the set of `taskId` for **all** task blocks on **any** date (not just the viewed day). This is critical: a task scheduled on another date must NOT reappear in the tray (otherwise dragging it would MOVE its existing block instead of creating a new one).

### 1.4 Block-edit sheet (`CalBlockEditSheet`)

A bottom sheet opened by tapping a task block. Tracks the **live** block (re-reads from `vm.blocks` by id so sequential edits compose and the selected chip follows). Sections:
- Header: `"Edit block"` label + the block's `taskName`.
- **Start time:** horizontally-scrolling selectable chips. Options = `[currentStartTime] + freeSlots` (deduped, current first). Free slots from `findFreeSlotsForDate(blocks, durationMinutes, date, now, limit=5, dayStartMin=0, dayEndMin=24*60)` — note the **full-day** window (0–1440), not the default 8–18, so early/late blocks can be rescheduled within their own band. Each chip label = `formatTime(t)`; selected = `live.startTime == t`. Tap → `vm.moveBlock(live, live.date, t)`.
- **Duration:** chips for `[15, 25, 45, 60, 90]` minutes. Label `"${m}m"`; selected = `live.durationMinutes == m`. Tap → `vm.resizeBlock(live, m)`.
- **Unschedule** button (danger style) → `vm.unschedule(live.id)` then dismiss.

### 1.5 Week view (`WeekView`)

Monday-anchored week, navigable with `‹` / `›` (a `weekOffset` integer, 0 = current week). A `"Today"` chip appears (and resets offset to 0) only when off the current week.

**Header rollup stats (3 cards):**
- `"Focus planned"`: total planned minutes across the week's task blocks (sum of `durationMinutes` for `isTaskBlock` blocks on each of the 7 days). Format: `"${h}h ${m}m"` if ≥ 60, else `"${m}m"`.
- `"Busiest"`: weekday abbrev (Mon..Sun) of the day with the most planned minutes — but **only if the week isn't flat** (if max == min, both Busiest and Lightest show `"—"`).
- `"Lightest"`: weekday of the least-planned day, same flat-week rule.

**Range label:** `"Mon 3–9"` (same month) or `"Mon 30 – Apr 5"` (cross-month). Section label is `"This week"` (offset 0) or `"Week"`.

**Grid:** a weekday header (single-letter day-of-week + day-of-month; today highlighted coral), then a 24-hour grid (`WSTART=0..WEND=24`, **`WHOUR=44`pt/hr**) with a 26pt time gutter (hours shown `%02d`) and 7 day columns. Each column renders its day's blocks with the same `layoutLanes` lane-splitting (lane width = `colW / lanes`). Block fill = life-area swatch for tasks (done → muted + strikethrough), blue-soft for external. Tapping a task block calls `onOpen(task)` (opens task detail — not the edit sheet). Blocks are display-only here (no drag).

### 1.6 Month view (`MonthView`)

A "Focus density" heatmap. Reads `vm.sessions` (completed focus sessions), aggregates `actualSec` per local day (`byDay[Clock.dateIso(session.completedAt)] += actualSec`). Month navigable via `‹` / `›` + `"Today"` (a `YearMonth` state). Monday-leading 7-column calendar grid; each day cell is a rounded square colored by intensity `t = clamp(daySec / maxSec, 0, 1)`, `lerp(bg2, primary, 0.2 + 0.6*t)`. Today's cell is coral. Empty days are bg2. Day-of-month number rendered, white on dark/today cells.

> Month uses **sessions**, not blocks — it is read-only analytics, no calendar interaction.

---

## 2. Data — models + Supabase tables/columns

### 2.1 Domain models (camelCase in app; snake_case at the PostgREST boundary)

**`CalBlock`** (`core/model/Models.kt`):
```
id: String
taskId: String?            // null for external/placeholder
taskName: String
startTime: String          // "HH:MM"
durationMinutes: Int
date: String               // "YYYY-MM-DD"
externalEventId: String?   // Google event id (when pushed or external)
externalConnectionId: String?
kind: CalBlockKind?        // task | placeholder | external
```

**`CalBlockKind`** enum, server strings: `"task"`, `"placeholder"`, `"external"`.

**`CalendarConnection`**:
```
id, provider (CalendarProvider), accountEmail, displayName,
selectedCalendarIds: [String], colorSlot: Int,
lastSyncCursor: String?, connectedAt: String
```

**`CalendarProvider`** enum strings: `"google"`, `"apple"`, `"microsoft"` (only Google is implemented end-to-end).

**`ExternalEvent`** (Edge Function shape): `id, connectionId, calendarId, summary, start, end` — `start`/`end` are ISO timestamps.

### 2.2 Supabase tables (migrations 001, 006, 009, 013)

**`cal_blocks`:**
| column | type | notes |
|---|---|---|
| `id` | uuid PK | `gen_random_uuid()` default |
| `user_id` | uuid NOT NULL | FK auth.users, cascade. **Attached server-side by the gateway — never in the client payload.** |
| `task_id` | uuid **nullable** (009) | FK tasks, cascade. Null for external/placeholder. |
| `task_name` | text NOT NULL | |
| `start_time` | text NOT NULL | `'HH:MM'` |
| `duration_minutes` | int NOT NULL | **CHECK between 5 and 1440** |
| `date` | date NOT NULL | |
| `external_event_id` | text | Google event id when pushed |
| `external_connection_id` | uuid | which connection owns the event |
| `kind` | text NOT NULL default `'task'` (006) | CHECK in (`'task'`,`'placeholder'`,`'external'`) |

Indexes: `(user_id)`, `(user_id, date)`.

**`calendar_connections`:**
| column | type | notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id` | uuid NOT NULL | FK auth.users |
| `provider` | text | CHECK in (google/apple/microsoft) |
| `account_email` | text NOT NULL | |
| `display_name` | text NOT NULL | |
| `credentials` | bytea NOT NULL | **AES-256-GCM encrypted refresh token — never leaves the server. The client never reads/writes this.** |
| `selected_calendar_ids` | text[] default `{primary}` | |
| `color_slot` | int default 0 | CHECK 0..5 |
| `last_sync_cursor` | timestamptz | |
| `connected_at` | timestamptz default now() | |
| unique `(user_id, provider, account_email)` | | |

**Migration 013 — `calendar_connections` is REMOVED from the realtime publication.** The encrypted `credentials` blob was being broadcast on every `last_sync_cursor` update. The client therefore must **poll** connections (via `listConnections`), never subscribe to them over realtime.

### 2.3 PostgREST row mapping (`DbRowCodec`)

The client writes `cal_blocks` directly (the gateway adds `user_id`). The `CalBlockRow` DTO maps camelCase→snake_case (`startTime`→`start_time`, etc.). On encode: `taskId`/`externalConnectionId` are coerced to null if not a valid UUID (`uuidOrNull`); `kind` defaults to `TASK` if nil.

`calendar_connections` rows are **read-only on the client** — decoded via `CalendarConnectionRow` from the Hydrator pull; never upserted by the client (the Edge Function owns inserts/deletes). The `/connections` endpoint returns snake_case raw rows; `/connect` returns camelCase — the client decodes each shape accordingly (see `ConnRow` with `@SerialName`).

---

## 3. Business rules / pure logic (with tests)

These are the **pure functions** that must be ported verbatim and unit-tested (Android has matching tests ported from the web + the original iOS).

### 3.1 `formatTime(hhmm) -> String` — 12-hour with AM/PM
`h12 = ((h + 11) % 12) + 1`, period = `h >= 12 ? "PM" : "AM"`, minutes zero-padded.
Tests (`FreeSlotsTest.formatTime12Hour`): `"09:00"→"9:00 AM"`, `"14:30"→"2:30 PM"`, `"00:15"→"12:15 AM"`, `"12:00"→"12:00 PM"`.

### 3.2 `findFreeSlots(blocks, durationMin, now, startDate?, daysToScan=4, dayStartMin=8*60, dayEndMin=18*60, limit=9) -> [Slot]`
Scans upcoming days for free windows large enough for `durationMin`. Returns up to `limit` slots chronologically. **Every block on a day is a conflict** (task, placeholder, external all consume time).
- `nowMin = now.hour*60 + now.minute`.
- For each scanned day (`startOfDay(startDate) + d days`):
  - day's blocks → `(startMin, startMin+duration)` sorted by start.
  - `startMin`: if the day is today, `max(dayStartMin, ceil((nowMin + 5)/15)*15)` — i.e. snap to the next 15-min boundary after now+5min, but no earlier than `dayStartMin`. Else `dayStartMin`.
  - `step = max(durationMin, 30)` — back-to-back placements no closer than 30 min.
  - Walk gaps: for each block (plus a sentinel `(dayEndMin, dayEndMin)`), while `cursor + duration <= min(blockStart, dayEndMin)`, emit a slot at `cursor`, advance `cursor += step`. Then `cursor = max(cursor, blockEnd)`.
- `Slot { date: "YYYY-MM-DD", label: "<dayLabel> · <formatTime>", startTime: "HH:MM" }`.
- `dayLabelFor`: diff in whole days → `"Today"` (0), `"Tomorrow"` (1), else `DOW_LABELS[dayOfWeek]` (Sun..Sat, JS indexing).

Tests (`FreeSlotsTest`): empty day starts at `dayStartMin` ("08:00"); a 08:15–09:00 block pushes the first 30-min slot to "09:00"; `limit` caps results; at now=08:07 the first 15-min slot is "08:15" (next-15 after now+5); future dates ignore `now` and start at "08:00". **Tests run under `TZ=UTC`** — the iOS test target must pin the timezone identically.

### 3.3 `findFreeSlotsForDate(blocks, duration, isoDate, now, limit=6, dayStartMin=8*60, dayEndMin=18*60)`
Single-day variant: parses `isoDate`, calls `findFreeSlots` with `daysToScan=1, startDate=thatDay`. The block-edit sheet calls it with `dayStartMin=0, dayEndMin=1440`.

### 3.4 `layoutLanes(blocks) -> [Laid]` — greedy interval coloring
Assign each block a `(lane, lanes)` within its cluster of transitively-overlapping blocks so overlaps split width instead of stacking. Mirrors the web `layoutLanes`:
- Map blocks → `Laid(block, startMin, endMin = startMin + max(1, duration))`, sort by `(startMin, endMin)`.
- Form clusters: extend `clusterEnd` while the next block's `startMin < clusterEnd`.
- Within a cluster, greedily place each block in the first lane whose last end-min `≤ block.startMin`, else a new lane. All blocks in the cluster get `lanes = totalLanesUsed`.

### 3.5 `findConflicts(date, startTime, durationMin, blocks, excludeBlockId?) -> [Conflict]`
Every block on `date` whose interval overlaps `[startMin, startMin+duration)`, sorted by start. `overlap = max(0, min(end, bEnd) − max(start, bStart))`; include only `overlap > 0`. Skips `excludeBlockId`.
Tests: overlapping blocks `a,b` returned (not the non-overlapping `c`), first overlap = 15 min; empty when none; excluded edited block omitted; other dates ignored.

### 3.6 `blockTimeRange(b)` → `"9:00 AM–10:00 AM"` (en-dash). Test confirms exact format.

### 3.7 Block-kind classification (`CalBlockKind.kt`)
```
blockKind(b): b.kind ?? (externalEventId non-empty ? EXTERNAL
              : taskId == "placeholder" ? PLACEHOLDER
              : taskId startsWith "cal-" ? EXTERNAL
              : TASK)
isTaskBlock(b) = blockKind == TASK && taskId non-empty
```

### 3.8 Google event ↔ block transforms (`GoogleSyncMapping.kt`)
- `externalEventToBlock(ev, calendarId)`: `id = "g_${ev.id}"` (stable → re-pulls overwrite the same row), `taskId = nil`, `taskName = ev.summary or "(untitled)"`, `startTime = localHHMM(ev.start)`, `durationMinutes = diffMinutes(start,end)`, `date = localYmd(ev.start)`, `externalEventId = ev.id`, `externalConnectionId = ev.connectionId`, `kind = .external`.
- `diffMinutes`: `max(15, round((end−start)/60_000))` — short/zero Google events floor at 15 min so they stay visible.
- `isoToLocalYmd` / `isoToLocalHHMM`: anchored to the **device's local timezone**.
- `blockToIsoRange(b)`: `date + HH:MM` interpreted in **local time** → UTC ISO with milliseconds (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, matching JS `toISOString()`). End = start + `durationMinutes*60s`. (The coordinator has a near-identical `blockIsoRange` using `ISO_INSTANT` without millis — both acceptable to Google; keep one helper on iOS.)

### 3.9 `scheduleTask` / `moveBlock` / `resizeBlock` (AppViewModel rules)
- **`scheduleTask(task, date, startTime)`** ("persistOrMove"):
  - Non-recurring: if the task already has a block, and the date/time changed → move it in place **and bump the task's `moveCount`** (slip detector). If unchanged → no-op. If no block → create a new `CalBlock` (`kind=.task`, `durationMinutes = task.estimateMin`, new UUID).
  - Recurring: diff via `regenerateForTask` (out of this spec's core scope but must be ported); always guarantee the user's chosen date is materialized even if off-pattern; bump moveCount only if the earliest-existing anchor block's date/time actually changed.
- **`moveBlock(block, date, startTime)`**: upsert `block.copy(date,startTime)`; bump owning task's `moveCount` only on a real change.
- **`resizeBlock(block, durationMinutes)`**: clamp duration to `[15, 360]`, upsert. (Note: DB CHECK allows 5..1440; the client narrows to 15..360.)
- **`unschedule(blockId)`**: delete the block.

---

## 4. Google Calendar sync — flows & server contract

### 4.1 Edge Function `calendar-sync` (single HTTPS function; no new OAuth client)

`CalendarClient` calls these routes via `functions.invoke`:
| Method/Path | Body / Query | Returns |
|---|---|---|
| POST `/authorize` | `{redirectUri, provider:"google"}` | `{url, state}` |
| POST `/connect` | `{code, redirectUri, state, provider:"google"}` | `{id, accountEmail, calendars:[{id,summary,primary?}], colorSlot?}` |
| POST `/disconnect` | `{connectionId}` | — (clears `external_event_id`/`external_connection_id` on cal_blocks, deletes the row) |
| GET `/connections` | — | `{connections:[ raw snake_case rows, no credentials ]}` |
| GET `/events?from=&to=&connectionId?` | RFC3339 `from`/`to` | `{events:[ExternalEvent]}` (across all connections if `connectionId` omitted) |
| POST `/events` | `{connectionId, calendarId, summary, start, end}` | `{id}` (new Google event id) |
| PATCH `/events/:id` | `{connectionId, calendarId, summary?, start?, end?}` | — |
| DELETE `/events/:id?connectionId=&calendarId=` | — | — |

### 4.2 OAuth consent flow (connect)
1. User taps "Connect" → `coordinator.beginGoogleConnect()` → `CalendarClient.authorize(CAL_REDIRECT)` → store `pendingCalState = response.state`, return `response.url`.
2. App opens `url` in an in-app browser (iOS: `ASWebAuthenticationSession` or `SFSafariViewController`).
3. Google redirects to **`https://unstuck-602.pages.dev/calendar-callback`** (the HTTPS bounce page — Google rejects custom schemes on a Web OAuth client). That page forwards `?code&state` to the app's custom scheme.
4. iOS captures the callback (deep link / `ASWebAuthenticationSession` completion). Android uses `unstuck://calendar-callback?code&state` captured in `MainActivity`; **iOS must register its own scheme** and the bounce page must forward to it (coordinate with the web bounce page — the Android scheme is `unstuck://`, confirm the iOS scheme/Universal Link the bounce page should target).
5. `completeGoogleConnect(code, state)`:
   - **CSRF guard:** if `pendingCalState != nil && != state` → log + return false (ignore).
   - Call `connectGoogle(code, CAL_REDIRECT, state)`; clear `pendingCalState`.
   - **Flush outbox first, then hydrate** (hydrate replaces cal_blocks with remote+localExternal, so an unflushed local TASK block — in neither set — would vanish until next sync). Hydrate also pulls the new `calendar_connections` row so the bar flips to "Synced" immediately.
   - Then `pullCalendar()`.

`CAL_REDIRECT = "https://unstuck-602.pages.dev/calendar-callback"` — this exact URL must be an Authorized redirect URI on the Google **Web** OAuth client.

### 4.3 Pull (`pullCalendar`)
- No-op when signed out or no connections.
- Window: `[today − 7d, today + 30d]`. **Send RFC3339 instants**, not bare dates: `fromIso = fromDate.startOfDay(localZone).instant`, `toIso = (toDate + 1d).startOfDay.instant` — Google's `events.list` rejects bare `YYYY-MM-DD` with a 400 and silently returns zero events.
- `pullEvents(fromIso, toIso)`.
- **Don't re-mirror our own pushed events:** compute `ownEventIds` = `externalEventId` of local TASK blocks that already have one. Filter those out of the pulled events (avoids a duplicate `g_` block next to the task block).
- **Skip all-day events:** only keep events whose `start` contains `'T'` (date-only all-day events would collapse to 00:00 15-min slivers). A proper all-day lane is a future follow-up.
- Map remaining → `externalEventToBlock` → `write.upsertCalBlock` each.
- **Reconcile deletions:** drop any local EXTERNAL block within `[fromYmd, toYmd]` whose id is no longer in the pulled set.

### 4.4 Push (Unstuck → Google), best-effort, wired through `WriteThrough`
- `WriteThrough.upsertCalBlock` fires `pushCalBlock` (an INSERT or PATCH) **after** the local write, for TASK blocks only (external `g_` ids never pushed). An INSERT mints a Google event id that gets **persisted back onto the block** (`block.copy(externalEventId=...)`) and re-enqueued so later edits PATCH the same event (no duplicates on next pull).
- `pushBlockUpsert`: TASK kind only; requires a Google connection; **always targets `calId = "primary"`** (selectedCalendarIds may include read-only/subscribed calendars that 403 on insert). PATCH if `externalEventId` present, else INSERT.
- `pushBlockDelete`: only if the deleted block had an `externalEventId` and isn't EXTERNAL; deletes the Google event on "primary".
- All push calls are `runCatching`/best-effort — a failed push must never break the local write or wedge the outbox.

### 4.5 Disconnect (`disconnectCalendar`)
Call `disconnect(connectionId)` (best-effort) → delete the local `calendar_connections` row (bar flips to "Connect" immediately) → delete all local EXTERNAL blocks where `externalConnectionId == connectionId`.

### 4.6 Hydrate & realtime for cal_blocks
- **Hydrate** (`hydrateCalBlocks`): remote `cal_blocks` are canonical, but locally-cached external `g_` blocks live only on-device (their ids aren't UUIDs, never round-trip to Postgres) → preserve them. `mergeHydratedCalBlocks(remote, localExternal)`: external locals first, then remote wins on id collision. **Also preserve unsynced optimistic TASK blocks** that have a pending outbox upsert (in neither remote nor localExternal) so they don't flicker off-screen until the next flush.
- **Realtime** (`RealtimeMirror.subscribeAll`): subscribe to `cal_blocks` (upsert→store.upsert, delete→store.delete). **Do NOT subscribe to `calendar_connections`** (migration 013 — encrypted creds were broadcast; poll via `listConnections` instead).

### 4.7 Periodic background sync
Android runs a `PeriodicWorkRequest` every **30 minutes** (`SyncWorker`, `KEEP` policy) that calls `coordinator.syncNow()` = flush outbox → hydrate → `pullCalendar()` (no-op signed out). See §5 for the iOS equivalent.

---

## 5. Gotchas (must replicate exactly)

1. **kotlinx default-omission / explicit nulls.** Android's outbound bodies have **no default values** because kotlinx omits defaults when `encodeDefaults` is off. The `AuthorizeBody`/`ConnectBody` carry an explicit `provider: "google"` field with NO default — a defaulted `provider` got dropped and the server rejected it ("Only google supports authorize"). On iOS (Codable): always **explicitly encode `provider: "google"`** in authorize/connect bodies; never rely on a default. For row payloads (`cal_blocks`), `DbRowCodec` uses `explicitNulls=true, encodeDefaults=true` — a null field is sent as explicit JSON `null` so an upsert **clears** removed fields. Replicate: your `CalBlockRow` encoder must emit `null` (not omit) for cleared `external_event_id`/`external_connection_id`.

2. **UTC dates vs local dates.** Google needs RFC3339 **instants** (not bare dates) for `timeMin/timeMax` — bare dates silently yield zero events. Block→event ISO is built by interpreting `date + HH:MM` in the **device's local zone** then converting to UTC (`toISOString()` parity). Event→block converts UTC→**local** Y-M-D + HH:MM. All "today"/"date" comparisons use the local-zone `dateIso`. Pure-logic **tests pin `TZ=UTC`** — set the iOS test scheme's timezone to UTC to match.

3. **LWW & merge.** Server is canonical on hydrate; remote wins on id collision; but local external (`g_`) blocks and pending optimistic TASK blocks are preserved (§4.6). cal_blocks have no `updated_at` LWW column — they're id-keyed replace + the optimistic-preservation rule. Don't introduce LWW where the reference uses last-writer-by-presence.

4. **External blocks are display-only.** `g_`-prefixed / `kind=.external` blocks must never be: dragged, edited, pushed to our `cal_blocks` table (their id/shape aren't ours — a push would fail forever and **stall the outbox**), or pushed to Google. Their taps are swallowed so they don't trigger create-task. `WriteThrough.upsertCalBlock` early-returns for `kind==EXTERNAL || id.startsWith("g_")`.

5. **Dependency ordering in the outbox.** A `cal_blocks` upsert carries `dependsOn = task.id` (only if a valid UUID) so the parent `tasks` row flushes first (FK). A `cal_blocks` delete carries **no** `dependsOn`, so on delete you must **cancel any still-queued upsert** for that block first — otherwise the upsert (held back behind the task) could flush *after* the delete and resurrect the row. Poison-pill: after N failures an op is dropped and its dependents dropped too (orphan cleanup). Replicate this ordering precisely in the iOS outbox.

6. **Disconnect is destructive + multi-account.** "Disconnect" must confirm first and apply to **all** connections; it removes synced external blocks locally. "Sync now" and the status line operate over all connected accounts, not just the first.

7. **NOW line must tick.** Don't read the clock once at render (Android originally froze it). Drive from a 30 s timer; plus a separate 30 s timer to roll "today" across midnight.

8. **Unscheduled tray uses scheduled-anywhere.** Tray excludes tasks scheduled on *any* date, not just the viewed day — otherwise dragging re-moves an existing block. Cap at 20 chips.

### iOS-specific equivalents

| Android | iOS |
|---|---|
| Jetpack Compose | SwiftUI |
| Room + Flows (`store.blocks()`) | GRDB (or a JSON document store) with Combine/`@Published` observation; `vm.blocks`/`vm.connections` as `@Published` arrays |
| `WorkManager` 30-min `PeriodicWorkRequest` (`SyncWorker`) | `BGTaskScheduler` (`BGAppRefreshTask`, ~min interval, opportunistic) calling `coordinator.syncNow()`. iOS gives **no guaranteed 30-min cadence** — schedule a refresh task and also pull on foreground/`onAppear` and after connect. |
| Custom Tabs (`CustomTabsIntent`) for consent | `ASWebAuthenticationSession` (preferred — captures the callback directly) or `SFSafariViewController` + URL-scheme/Universal-Link deep link |
| `unstuck://calendar-callback` deep link in `MainActivity` | Register a URL scheme / Universal Link; handle in `onOpenURL` (or the `ASWebAuthenticationSession` completion). **Confirm with the bounce page which scheme/host to forward to for iOS.** |
| `supabase-kt` (`functions.invoke`) | `supabase-swift` (`functions.invoke` / `FunctionsClient`) |
| Glance widget (N/A here) | WidgetKit (N/A for this area) |
| FCM (N/A here) | APNs (N/A for this area) |
| Foreground service | **No iOS equivalent** — there is no long-running foreground service. Background sync is limited to BGTaskScheduler windows + foreground triggers; do not assume a persistent background sync loop. |
| `LocalTime.now()` 30 s coroutine ticker | `Timer.publish(every: 30, ...)` / `TimelineView` for the NOW line |
| `kotlinx.serialization` + `@SerialName` | `Codable` + `CodingKeys`; preserve **camelCase in JSONB blobs** and **snake_case at the row boundary**; always explicitly encode `provider` and explicit nulls for cleared columns |

### Suggested SwiftUI structure
- `CalendarView` (segment + sync bar + switch) → `DayGridView`, `WeekView`, `MonthView`.
- `CalendarSyncBar` (observes `connections`, drives connect/sync/disconnect).
- `DayGridView`: scrollable hour grid; blocks as absolutely-positioned overlays (`GeometryReader` + offsets); drag via `DragGesture(minimumDistance:)` after a long-press for both tray chips and task blocks; a floating drag-ghost following the touch; tap-empty → create-at; `.sheet` for `CalBlockEditSheet`.
- Port `FreeSlots`, `CalBlockKind`, `GoogleSyncMapping`, `layoutLanes`, and `SyncDecision.mergeHydratedCalBlocks` as pure Swift with the **same unit tests** (FreeSlotsTests, GoogleSyncMappingTests) under a UTC-pinned test scheme.
- `CalendarClient` (supabase-swift) mirroring the 8 routes; `SyncCoordinator` mirroring `beginGoogleConnect`/`completeGoogleConnect`/`pullCalendar`/`disconnectCalendar`/`pushBlockUpsert`/`pushBlockDelete`; `WriteThrough` firing the Google push hooks after the local write.