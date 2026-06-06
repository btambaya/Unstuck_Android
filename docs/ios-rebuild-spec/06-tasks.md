# iOS Rebuild Spec — Tasks (CRUD / Detail / Scheduling / Tags)

> Reference client: **Unstuck Android** (`unstuck_android`). The iOS app is rebuilt from scratch in SwiftUI as a 1:1 behavioral replica. This spec covers the **Tasks** area: the task list screen, task detail, the new-task sheet, tag picking, recurrence editing, and the four mutation paths (`addTask` / `updateTask` / `deleteTask` / `scheduleTask`) plus their dependents (`toggleDone`, `setLater`, `setRecurrence`, captures, tags).
>
> Android source map (everything here was read directly):
> - `app/.../ui/tasks/` — `TasksScreen.kt`, `TaskDetailSheet.kt` (file holds `TaskDetailScreen`), `NewTaskSheet.kt`, `SelectableChip.kt`
> - `app/.../ui/components/` — `TagPicker.kt`, `RecurrenceEditor.kt`
> - `app/.../ui/AppViewModel.kt` — `addTask` / `updateTask` / `deleteTask` / `scheduleTask` / `toggleDone` / `setLater` / `setRecurrence` / `saveCapture` / `promoteCapture` / `ensureTag`
> - `core/.../logic/` — `VisibleTasks.kt`, `TaskMutations.kt`, `Recurrence.kt`, `FreeSlots.kt`, `TaskBucket.kt`, `PickStartNext.kt`, `CalBlockKind.kt`, `Uuid.kt`
> - `core/.../time/Time.kt`, `core/.../model/Models.kt`, `core/.../model/Enums.kt`
> - `sync/.../` — `WriteThrough.kt`, `DbRowCodec.kt`, `RealtimeMirror.kt`
> - Tests: `core/src/test/.../{VisibleTasksTest,TaskMutationsTest,RecurrenceTest}.kt`
> - DB: `supabase/migrations/{001,003,005,008,025}*.sql`

The golden rule from the codebase: **`:core` is pure logic shared verbatim across web (TS), Android (Kotlin), and iOS (Swift).** On iOS, recreate `:core` as a pure Swift module (`UnstuckCore`) with **the same function names, signatures, and tests ported 1:1**. The Kotlin tests below are the acceptance criteria — port each one to XCTest.

---

## 1. What it does — screens, state, flows, edge cases

There are **four** UI surfaces in this area plus two embedded pickers. All read reactive state from the single app-wide store and call mutation methods that write optimistically (local-first) and enqueue a server op.

### 1.1 `TasksScreen` (the task list)

**Layout (top to bottom):**
1. `AppBar` titled "Tasks", no leading hamburger (`Leading.NONE`), with search, notifications (unread badge), and avatar (initials) trailing actions.
2. Pinned header (does **not** scroll): a serif-italic "Your tasks" title, then three rows of pills.
3. **Tab pills row** (horizontally scrollable). Tab order is fixed:
   `BACKLOG, ALL, TODAY, UPCOMING, LATER, COMPLETED`. **Default selected tab = `TODAY`.**
   - Each tab has a per-tab accent color when active, and shows a small colored dot + tinted label when inactive (only for tabs that have an accent):
     - `BACKLOG` → amber (`amberSoft`/`amberInk`)
     - `TODAY` → coral (`coralSoft`/`coralDeep`)
     - `UPCOMING` → blue (`blueSoft`/`blueInk`)
     - `LATER` → primary (`primarySoft`/`primaryDeep`)
     - `COMPLETED` → green (`greenSoft`/`greenInk`)
     - `ALL` → no accent (active = `c.ink` fill, label `c.bg`)
   - Active fill = the tab's `*Soft` color (or `c.ink` for `ALL`); active label = the `*Ink/*Deep` color (or `c.bg` for `ALL`). Inactive fill = `c.bg2`, label `c.ink2`, with a 6dp colored dot before the label.
4. **Area-filter pills row** (horizontally scrollable): an "All" pill plus one pill per `LifeArea` (with the area's colored dot). Tapping an area toggles it (tap the active area again → clears to "All"). This filter is **lifted to a parent** via `onAreaPick`/`activeArea` (shared with the rest of the app via the global area filter).
5. **Active tag-filter chip** (only shown when a tag filter is active): a pill reading "Filtering by tag #<tag> ✕". Tapping it clears the tag filter. The tag filter is **screen-local state** (`activeTag`), set by tapping a tag chip on any task row.
6. **The list** (`LazyColumn`, only this scrolls). Empty state: "No <view> tasks." (lowercased view label). Each row:
   - Task name (1 line, `Medium`). If `done`: strikethrough + `ink3` color.
   - Sub-line: area dot + area name (or "—"), a "· ↻" marker if `recurrence != null`, then up to **3** tag chips ("#<tag>", tappable → sets `activeTag` to that tag).
   - In the `BACKLOG` tab only: an amber age chip "<N>d" where N = `ageDays(createdAt, now)` clamped to at least 1.
   - Trailing: "<estimateMin>m" in mono.
   - Tapping a row → `onOpen(task)` (navigates to `TaskDetailScreen`).

**The list content is computed by `visibleTasks(...)` from `:core`** — see §3.1. Key behavior: `view`, `activeArea` (forced `null` when `view == TODAY` — Today is area-agnostic), `activeTag`, `slipMode = false`.

**Edge cases:**
- Today tab ignores the area filter entirely (passes `activeArea = null`).
- Tag filter applies to **every** view including Today.
- An empty filtered list shows the empty-state line, not a blank screen.

### 1.2 `TaskDetailScreen` (full-screen, editable)

`AppBar` with a BACK leading and no search. Scrollable, keyboard-aware (`imePadding`). Sections top to bottom:

1. **Eyebrow**: area dot + "`<AREA-or-"Task">` · TASK" (uppercased).
2. **Title** — inline-editable (`EditableText`): tap to edit, ✓ commits (only if non-blank and changed → `updateTask(task.copy(name=...))`), ✕ cancels. If `done`, render strikethrough + `ink3`.
3. **First physical action** card (bg2): label "First physical action" (coral). Inline-editable italic text; placeholder "Add one — the smallest concrete step." Commit trims → `null` if empty; only writes if changed.
4. **Action row**:
   - "Focus" button (coral, play icon) → `onStartFocus()` (starts a focus session — out of this spec's scope but the entry point is here).
   - "Schedule" button (outlined) → opens a native date-then-time picker (`pickSchedule()`), see below.
   - "Mark done" / "✓ Done" (text) → `toggleDone(task)`.
   - A transient "Scheduled <MM-DD h:mm AM/PM>" line in green appears after scheduling.
   - If `task.later == true`: a "Move out of Later" ghost button → `setLater(task, false)`.
5. **Estimate** (in a card): preset chips `15, 25, 45, 60, 90` (selected when `==estimateMin`); a chip for the current non-preset value if applicable; a "Custom…" chip opening a number-entry dialog. Each tap → `updateTask(task.copy(estimateMin=...))`. Custom dialog: digits only, max 4 chars, must be `> 0`.
6. **Area** (in the same card): "Unassigned" pill + one pill per life area. Tap → `updateTask(task.copy(lifeArea = ... or null))` (tapping the active area clears it to null).
7. **Meta cells row** (in the card): "Schedule" cell (label = `scheduleLabel`, tappable → `pickSchedule()`) and "Status" cell. `scheduleLabel`:
   - `"Later"` if `task.later == true`
   - else `"<earliest block date MM-DD> <formatTime(startTime)>"` if the task has task-kind blocks
   - else `"Unscheduled"`.
   Status: `"Completed"` if done, `"In progress"` if `totalFocused > 0`, else `"Not started"`.
8. **Repeat**: a label `recurrenceLabel(task.recurrence)` (or "Does not repeat"), then the `RecurrenceEditor` → `setRecurrence(task, it)`.
9. **Tags**: `TagPicker` → `updateTask(task.copy(tags = it.ifEmpty { null }))`.
10. **Sessions** (only if any): up to 6 lines "• <actualSec/60>m focused".
11. **Captures**: list of `CaptureRow`s for this task's captures, then an `AddCaptureRow`.
    - `CaptureRow`: tag pill (colored by tag), relative time, body, "Promote to task →" (`promoteCapture`) and "Discard" (`deleteCapture`).
    - `AddCaptureRow`: text field + "Add" (appears only when non-blank) + tag chips. Add → `saveCapture(task.id, null, tag, body)`.
12. **Delete** (danger button) → confirm dialog "Delete this task? Its scheduled blocks and captures are removed too." → `deleteTask(task.id)` then `onBack()`.

`pickSchedule()` on detail uses **native local-zone** date+time pickers (no UTC off-by-one — see gotchas), with `minDate = now - 60s` (no past days). On confirm: `scheduleTask(task, dateIso, timeIso)`, and if `task.later == true` also `setLater(task, false)`.

### 1.3 `NewTaskSheet` (create — scheduler-first modal)

A modal bottom sheet (full height), scrollable + keyboard-aware. Mirrors the web `task-create-modal`. Optionally prefilled with `prefillDate` / `prefillTime` (e.g. when created from a calendar slot). **No priority picker** — the web + DB don't surface one (see memory: redesign-source-of-truth).

Fields top to bottom:
1. **Name** (`OutlinedTextField`, single line): "What's the next thing on your mind?" — **required** (submit disabled while blank).
2. **When** chips: `Today`, `Tomorrow`, `Pick date`, `Later`.
   - Initial selection derived from `prefillDate`: `null`/today → "Today", tomorrow → "Tomorrow", else → "Pick date".
   - "Pick date" opens a `DatePicker` dialog and only commits the selection on **OK** (cancel keeps the previous `whenSel`). The chip's label shows the picked date's last 5 chars (MM-DD) once committed.
   - Selecting any When re-enables auto-time (`autoTime = true`).
3. **Time** (hidden when "Later"): free-slot chips computed by `findFreeSlotsForDate(blocks, estimate, effectiveDate, now, limit=4)`. Plus:
   - If a `pickedTime` exists that isn't among the slots, a selected chip showing `formatTime(pickedTime)` (tap → time picker).
   - Each slot chip selected when `pickedTime == slot.startTime`.
   - A "Custom…" chip opening a `TimePicker` dialog.
   - If no slots and no time: helper text "No free slots that day — pick a custom time, or it'll be added without one."
   - **Conflict warning**: if `effectiveDate != null && pickedTime != null`, compute `findConflicts(...)`; if non-empty show an amber box "Overlaps <first conflict's taskName>".
4. **Estimate** chips: presets `15, 25, 45, 60, 90` + a current-non-preset chip + "Custom…" dialog. Default estimate = `settings.focusDefaultMin`.
5. **Remind me** (hidden when "Later"): `Default` (null = use global lead), `Off` (0), `5m before`, `10m before`, `15m before`. Stored as a per-task override on submit.
6. **Area** (only if any life areas exist): "Unassigned" + one pill per area; toggle behavior like detail.
7. **First step** (coral label): `OutlinedTextField`, "The smallest concrete step…".
8. **Tags**: `TagPicker`.
9. **Recurrence**: `RecurrenceEditor`.
10. **Capture a thought** (primaryDeep label): zero or more draft cards, each with a body field + 5 capture-tag pills + a ✕ to remove. A "+ Capture" button adds a draft. Drafts **auto-save** on submit (no per-draft Add).

**`effectiveDate`** = `null` if "Later", else todayIso/tmrwIso/pickedDate.

**Auto-time logic** (`LaunchedEffect` on `effectiveDate, estimate, whenSel, slots`): if "Later" → `pickedTime = null`; else if `autoTime` → `pickedTime = slots.firstOrNull()?.startTime`. `autoTime` becomes `false` the moment the user picks a specific slot/custom time or a prefilled time exists.

**Submit ("Add task", coral when valid, disabled while name blank):**
```
val t = addTask(name, estimateMin=estimate, lifeArea=area, tags=tags.ifEmpty{null},
                firstPhysicalAction=firstMove.trim().ifEmpty{null}, recurrence=recurrence,
                later = (whenSel=="Later"))
if (whenSel != "Later") reminderLead?.let { setReminderOverride(t.id, it) }
if (whenSel != "Later" && effectiveDate != null && pickedTime != null)
    scheduleTask(t, effectiveDate, pickedTime)
drafts.filter { it.body.isNotBlank() }.forEach { saveCapture(t.id, null, it.tag, it.body.trim()) }
dismiss()
```
Order matters: `addTask` first (mints the task id + enqueues the task op), then schedule (its cal_block op `dependsOn` the task), then captures.

### 1.4 `TagPicker` (embedded)

Mirrors web `TagPicker`. A horizontally-scrollable row of selected "#name ✕" chips (tap a chip → remove) followed by a "+ Tag" button that opens a dropdown:
- A search field ("Search or create…").
- The full tag vocabulary (`vm.tags`) filtered by query (case-insensitive `contains`); each row toggles membership (✓ when on). Toggling calls `onChange(selected ± name)`.
- If the trimmed query is non-empty and matches no existing tag (case-insensitive), a "Create "<q>"" row → `onChange(selected + ensureTag(q))` (creates the tag in the vocabulary if new) and clears the query.

`onChange` is wired to `updateTask(task.copy(tags=...))` in detail, or to local `tags` state in the new-task sheet.

### 1.5 `RecurrenceEditor` (embedded)

Emits `Recurrence?` (null = "does not repeat"). Section "Repeat" with chips: `Never`, `Daily`, `Weekly`, `Monthly`.
- Tapping Daily → `Recurrence.Daily(until)`, Monthly → `Recurrence.Monthly(until)`, Weekly → `Recurrence.Weekly(days.ifEmpty{[1]}, until)`.
- **Weekly** shows a row of single-letter day chips `S M T W T F S` (index 0=Sun … 6=Sat). Tapping toggles the day in `daysOfWeek`; if you'd empty it, it falls back to just the tapped day. Days are kept sorted.
- For any non-Never mode: an "Ends" row — chip showing "by <MM-DD>" (selected) or "Open-ended". Tapping opens a native date picker (min = now-60s) → sets `until`. If `until` is set, a "Clear" action removes it.

`until` is preserved across mode changes (`withUntil`).

---

## 2. Data — models & Supabase schema

### 2.1 Domain models (port to Swift structs, **camelCase** in app code)

From `core/model/Models.kt` and `Enums.kt`. The fields relevant to Tasks:

**`TaskItem`** (the central model):
| field | type | notes |
|---|---|---|
| `id` | String (UUID) | client-minted (`newUuid()`) |
| `name` | String | |
| `estimateMin` | Int | DB default 25, check 1..1440 |
| `totalFocused` | Int = 0 | server/focus-owned |
| `done` | Bool = false | |
| `priority` | `Priority?` | **never set by Tasks UI** (no picker); DB default 'medium' |
| `tags` | `[String]?` | null in-model ↔ `'{}'` array in DB |
| `objectives` | `[Objective]?` | JSONB; unused by Tasks UI |
| `comments` | `[Comment]?` | JSONB; unused by Tasks UI |
| `intentWhen` / `intentThen` | String? | unused by Tasks UI |
| `lifeArea` | String? | the area **name** string, not an id |
| `firstPhysicalAction` | String? | |
| `moveCount` | Int? | bumped by reschedules; feeds slip detector |
| `completedAt` | String? (ISO) | stamped/cleared on done-flip |
| `later` | Bool? | deferred flag |
| `recurrence` | `Recurrence?` | tagged-union JSONB |
| `sourceCollectionId` | String? | promote-from-collection back-link (migration 025) |
| `sourceItemId` | String? | |
| `dueAt` | String? (ISO) | accountability "by" time |
| `createdAt` / `updatedAt` | String (ISO) | required; drive LWW + Today/Backlog logic |

> Note: `late_nudged` is a server-owned column the client **never writes** — it is absent from the model entirely. Don't add it.

**`Recurrence`** — a Swift enum mirroring the Kotlin sealed class, with an `until: String?` on every case:
- `.daily(until: String?)`
- `.weekly(daysOfWeek: [Int], until: String?)` — `daysOfWeek` is **0=Sun … 6=Sat**
- `.monthly(until: String?)`

JSON shape (this **is** the server JSONB, camelCase keys): `{ "kind": "daily" | "weekly" | "monthly", "daysOfWeek"?: [Int], "until"?: "YYYY-MM-DD" }`. `until` is **omitted when null** (the serializer only puts it when present). See §4 for the custom codec requirement.

**`CalBlock`** (scheduling rows — Tasks creates these via `scheduleTask`):
`id`, `taskId: String?`, `taskName`, `startTime: "HH:MM"`, `durationMinutes`, `date: "YYYY-MM-DD"`, `externalEventId?`, `externalConnectionId?`, `kind: CalBlockKind?`.

**`Capture`**: `id`, `taskId?`, `sessionId?`, `tag: CaptureTag`, `body`, `at: ISO`.

**`TagRow`**: `id`, `name`, `color: String?`, `sortOrder: Int`.
**`LifeArea`**: `id`, `name`, `color`, `sortOrder`.

**Enums** (`@SerialName` = the on-wire string; replicate exactly in Swift `Codable`):
- `Priority`: `urgent/high/medium/low`
- `CalBlockKind`: `task/placeholder/external`
- `CaptureTag`: `follow-up/idea/edit/question/distraction` (note the **hyphen** in `follow-up`)
- `TaskListView` (UI-only, not serialized): `ALL("All"), BACKLOG("Backlog"), TODAY("Today"), UPCOMING("Upcoming"), LATER("Later"), COMPLETED("Completed")`.

### 2.2 Supabase tables (PostgREST; snake_case columns)

**`public.tasks`** (cumulative across migrations 001/003/005/008/025):
```
id uuid pk default gen_random_uuid()
user_id uuid not null  -- attached by the gateway, NOT in the model
name text not null
estimate_min int not null default 25 check (1..1440)
total_focused int not null default 0 check (>=0)
done boolean not null default false
priority text check in ('urgent','high','medium','low') default 'medium'
tags text[] not null default '{}'
objectives jsonb not null default '[]'
comments jsonb not null default '[]'
intent_when text
intent_then text
life_area text
first_physical_action text
move_count int not null default 0 check (>=0)          -- 003
completed_at timestamptz                                -- 005
later boolean not null default false                    -- 008
recurrence jsonb                                        -- 008
source_collection_id uuid                               -- 025
source_item_id text                                     -- 025
due_at timestamptz                                       -- 025
late_nudged boolean not null default false              -- 025 (server-owned)
created_at timestamptz not null default now()
updated_at timestamptz not null default now()
indexes: (user_id), (user_id, done), partial (due_at) where source_collection_id is not null and done=false
```

**`public.cal_blocks`**: `id`, `user_id`, `task_id uuid` (FK → tasks, was `not null` in 001, made nullable in migration 009), `task_name`, `start_time text 'HH:MM'`, `duration_minutes int check 5..1440`, `date`, plus `external_event_id`, `external_connection_id`, `kind`. RLS scopes every table by `user_id`.

The **snake_case ↔ camelCase mapping** lives in `DbRowCodec` (`TaskRow`, `CalBlockRow`, etc.) with `@SerialName`. On iOS this is `DbRowCodec.swift` (port from `unstuck_ios`'s prior version — the codebase notes "Port of the iOS DbRowCodec.swift", so a reference shape exists). JSONB blobs (`recurrence`, `objectives`, `comments`) keep **camelCase** keys — only top-level columns are snake_case.

---

## 3. Business rules / pure logic (`:core`)

Recreate as `UnstuckCore` (pure Swift). Port the Kotlin tests 1:1. These are deterministic — inject `now` (epoch ms) and `nowISO`.

### 3.1 `visibleTasks` — the list filter (`VisibleTasks.kt`, test `VisibleTasksTest.kt`)

Signature: `visibleTasks(view, tasks, blocks, now, activeArea, activeTag=nil, slipMode) -> [TaskItem]`.

Precompute from blocks (only **task-kind** blocks count — `isTaskBlock`):
- `todayTaskIds` = blocks with `date == todayIso`
- `upcomingTaskIds` = blocks with `date > todayIso`
- `scheduledTaskIds` = any task-kind block
- `pastOnlyTaskIds` = scheduled but not in today/upcoming (overdue)

Per view:
- **TODAY**: `!done && later != true && (id ∈ todayTaskIds || (createdToday && id ∉ upcomingTaskIds))`. Created-today fresh tasks surface; a task explicitly scheduled for a future day does not.
- **BACKLOG**: `!done && later != true && !createdToday && (id ∉ scheduledTaskIds || id ∈ pastOnlyTaskIds)`. Open work ≥1 day old, never scheduled or only past-scheduled.
- **UPCOMING**: `!done && id ∈ upcomingTaskIds && id ∉ todayTaskIds`.
- **LATER**: `!done && later == true`.
- **COMPLETED**: `done`.
- **ALL**: `!done || isCompletedToday`.

Then: **area filter** (skipped for TODAY — area-agnostic), via `matchesArea`; **tag filter** (applies to all views), case-insensitive; **slip filter** if `slipMode`; finally **open tasks first, then completed**, preserving order within each (`filter{!done} + filter{done}`).

Helpers (also in `:core`):
- `matchesArea(taskArea, activeArea)`: empty/null filter → true; `"Unassigned"` sentinel → matches null/empty area; else equality.
- `matchesTag`: case-insensitive `any`.
- `isSlipping(task, now)`: `false` if done; true if `moveCount >= 3` (`SLIP_MOVE_THRESHOLD`) or `now - createdAt >= 21 days` (`SLIP_AGE_MS`).
- `daysSinceCreated` / `isCreatedToday` / `isCompletedToday` (`TaskBucket.kt`): local-midnight window math.

### 3.2 Completion + move-count (`TaskMutations.kt`, test `TaskMutationsTest.kt`)
- `stampCompletion(isDone, incoming, prior, nowISO)`: if done → `incoming ?? prior ?? nowISO`; else `null`. **First flip stamps now; re-toggles preserve the original; un-completing clears.**
- `applyCompletion(item, prior, nowISO)` = `item.copy(completedAt = stampCompletion(...), updatedAt = nowISO)`.
- `bumpMoveCount(task, nowISO)` = `moveCount = (moveCount ?? 0) + 1, updatedAt = nowISO`.

### 3.3 Free slots & conflicts (`FreeSlots.kt`)
- `formatTime("HH:MM")` → 12-hour "h:mm AM/PM" (`12:15 AM` at hour 0). Used everywhere a time is shown.
- `findFreeSlots(blocks, durationMin, now, startDate?, daysToScan=4, dayStartMin=8*60, dayEndMin=18*60, limit=9)`: scans days for gaps; on **today**, starts at `max(8:00, ceil((nowMin+5)/15)*15)` (next quarter-hour ≥5min out). Step between candidate starts = `max(durationMin, 30)`. **Every** block (task/placeholder/external) is a conflict. Slot label = "<Today|Tomorrow|Weekday> · <formatTime>".
- `findFreeSlotsForDate(blocks, durationMin, isoDate, now, limit=6, ...)`: single-day variant.
- `findConflicts(date, startTime, durationMin, blocks, excludeBlockId?)`: overlap-minute computation, sorted by start. New-task sheet shows the first conflict's `taskName`.

### 3.4 Recurrence materialization (`Recurrence.kt`, test `RecurrenceTest.kt`)
- `RECURRENCE_HORIZON_DAYS = 56` (8 weeks).
- `materializeOccurrences(recurrence, startDate, startTime, horizonDays=56) -> [(date, startTime)]`: iterate `i in 0..<horizonDays`, stop early if `iso > until` (until is **inclusive**). Match:
  - Daily: every day.
  - Weekly: `dayOfWeekJs(candidate) ∈ daysOfWeek`.
  - Monthly: `dayOfMonth(candidate) == min(dayOfMonth(start), daysInMonth(candidate))` — **clamps** day-29/30/31 to the month's last day, then recovers in long months (Jan 31 → Feb 28/29 → Mar 31).
- `regenerateForTask(task, recurrence, existingBlocks, todayIso, startTime, startDate, horizonDays=56) -> RegenPlan(toUpsert, toDelete)`: keeps **past** occurrences, deletes mismatched future ones, adds missing future ones. If `recurrence == null`: delete all future, keep history. Diff keyed by `"date|startTime"`.
- `recurrenceLabel(recurrence)` for the detail pane: "Repeats daily/monthly", "Repeats Mon/Wed/Fri", "weekdays" (Mon–Fri), "weekends" (Sun+Sat), all-7 collapses to "Repeats daily", optional " until Mon DD, YYYY".

### 3.5 `pickStartNext` (`PickStartNext.kt`) — referenced by detail's "Focus" indirectly
Ranks open, non-Later, non-focused tasks by **priority desc → estimateMin asc → createdAt asc** (ISO strings compare lexicographically == chronologically; stable sort). Needed so the home-screen widget / Up Next agree. Port even though Tasks UI doesn't directly render it (the ViewModel recomputes it on task/block change).

---

## 4. Mutations (ViewModel → WriteThrough → outbox), and the gotchas

Each mutation in `AppViewModel` runs the `:core` rule, writes **optimistically to the local store**, and **enqueues a server outbox op**. On iOS, replicate the optimistic-local-then-enqueue pattern (`WriteThrough.swift` exists in the prior iOS app as a reference shape).

### 4.1 `addTask(...) -> TaskItem`
Mint `id = newUuid()`, `createdAt = updatedAt = isoNow()`, `name.trim()`. **Returns the task synchronously** (the sheet uses the id immediately to schedule + attach captures); the actual write is dispatched async. Default `estimateMin = 25`, `later = false`. **Never pass a priority** from the Tasks UI.

### 4.2 `updateTask(task)`
`upsertTask(task.copy(updatedAt = isoNow()))`. Used for every in-place edit (name, first action, estimate, area, tags). **Always re-stamp `updatedAt`** — this drives LWW.

### 4.3 `toggleDone(task)`
Flip `done`, then `applyCompletion(flipped, prior=task, nowISO=isoNow())`. If the flip is a completion **and** the task has `sourceCollectionId`+`sourceItemId`, also notify the shared collection (`share.taskDone(...)`) — best-effort, no-op when not shared.

### 4.4 `setLater(task, later)` / `setRecurrence(task, recurrence)`
- `setLater`: `upsertTask(task.copy(later=later, updatedAt=isoNow()))`.
- `setRecurrence`: upsert the task with new recurrence, then realign future cal_blocks: find the earliest existing **task-kind** block as the anchor (`startTime` else `"09:00"`, `startDate` else local-midnight today), run `regenerateForTask`, then **delete `toDelete`, upsert `toUpsert`**.

### 4.5 `deleteTask(id)`
Cascade **client-side**: delete this task's cal_blocks and captures first (so realtime listeners don't re-pull orphans), then delete the task. Each is its own outbox op.

### 4.6 `scheduleTask(task, date, startTime)` — the subtle one (`persistOrMove` parity)
- **Non-recurring**:
  - If an existing block: only if date/time **changed**, update the block in place **and** `bumpMoveCount`. Re-tapping the same slot is a no-op (keeps the slip detector honest).
  - Else: insert a new `CalBlock(kind=TASK)` and **do not** bump moveCount (first placement isn't a "move").
- **Recurring**: run `regenerateForTask` for the new anchor, apply the delete/upsert diff, **then guarantee the user's chosen date is materialized** — if neither existing nor planned blocks cover `date` (e.g. an off-pattern pick, or today which the horizon skips), insert an explicit block for it. Bump moveCount only if the anchor actually changed.

### Gotchas (replicate exactly)

1. **kotlinx default-omission → iOS encoding parity.** The Android `DbRowCodec` uses `explicitNulls = true, encodeDefaults = true`: a nullable field that becomes null serializes as **explicit `null`** so an upsert **clears** the removed value on the server. On iOS, your encoder must emit explicit nulls for cleared optionals (don't let Swift `Codable` drop `nil` keys, or removing a `lifeArea`/`firstPhysicalAction`/`recurrence`/`until` won't persist the clear). The **one exception** in the codebase is `reason_logs.duration_sec` (server-set, stripped post-encode) — not in the Tasks path, but follow the same "strip server-owned columns" discipline (and never send `late_nudged`). Defaults applied at the row boundary: `tags ?? []`, `move_count ?? 0`, `later ?? false`, `objectives ?? []`, `comments ?? []`.

2. **UTC vs local dates — the Material DatePicker trap.** The `NewTaskSheet`'s "Pick date" uses Material3's date picker whose `selectedDateMillis` is **UTC-midnight**; Android explicitly seeds/reads it in `ZoneOffset.UTC` to avoid west-of-UTC users landing a day early. The **detail** + recurrence pickers use native (local-zone) dialogs and don't need the correction. On iOS, `DatePicker` is local-zone by default — to match behavior: derive the ISO `yyyy-MM-dd` from the picked date **using the user's local calendar components**, never via a UTC offset. All `:core` date math (`Time.swift`) must use **local time** (`Calendar.current` / system zone), matching JS `Date` semantics — `startOfDayMillis`, `civil`, `addDaysMillis`, `dayOfWeekJs` (0=Sun…6=Sat), `dayOfMonth`, `daysInMonth`. Tests run pinned to UTC; port that harness.

3. **LWW (last-write-wins).** The store tracks `updatedAt` per record. Always re-stamp `updatedAt = isoNow()` on every write (the mutations above do). Realtime/hydrate apply incoming rows by id with their `updatedAt`; conflicting concurrent edits resolve newest-wins. **Don't forget the `updatedAt` bump** — a missing bump makes a local edit look stale and get clobbered by an older server row. ISO timestamps must be lexicographically comparable (use a fixed `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` formatter — port `isoNow()`).

4. **Dependency ordering in the outbox.** A `cal_blocks` upsert carries `dependsOn = task.id` so the parent **task row flushes first** (FK safety) — critical because `addTask` returns synchronously and `scheduleTask` enqueues the block in the same tick. Replicate FIFO + `dependsOn` ordering. Also: a delete cancels any still-queued upsert for the same row (so a held-back upsert can't resurrect a deleted row), and external `g_`-prefixed cal_blocks are never pushed (not ours). `taskId` that isn't a valid UUID drops to null on the wire (`uuidOrNull`) — Tasks always mints real UUIDs, but the codec must still guard.

5. **Exact-alarm / reminder denial → iOS notification model.** The "Remind me" lead on the new-task sheet stores a **device-local per-task override** (`setReminderOverride(taskId, leadMin)`), not a server field. On Android this drives an `AlarmManager` exact alarm before the block; exact-alarm permission can be denied. On **iOS**: schedule a local `UNUserNotificationCenter` `UNCalendarNotificationTrigger`/`UNTimeIntervalNotificationTrigger` at `blockStart - lead`. There is **no exact-alarm permission**, but you must request `UNUserNotificationCenter.requestAuthorization`; if denied, the reminder silently doesn't fire (match Android's graceful degradation — no crash, no UI error). `Default` = global lead, `Off` = 0 = no reminder. Reschedule the local notification whenever the task is rescheduled or its lead changes; cancel it on delete/complete.

---

## 5. iOS equivalents (mapping table)

| Android / Kotlin | iOS / Swift |
|---|---|
| Jetpack Compose (`TasksScreen`, sheets) | SwiftUI `View`s; `ModalBottomSheet` → `.sheet`/`.presentationDetents([.large])`; `LazyColumn` → `List`/`LazyVStack`; `horizontalScroll` chip rows → `ScrollView(.horizontal)` |
| `AppViewModel` (`androidx.lifecycle.ViewModel`) + `StateFlow` | `@Observable`/`ObservableObject` store with `@Published`; `collectAsStateWithLifecycle` → `@State`/`@ObservedObject` bindings |
| `:core` Kotlin pure module | `UnstuckCore` pure Swift module — **same function names/signatures**, tests ported 1:1 to XCTest |
| Room (`records`/`outbox` tables, `@Dao`) | GRDB (recommended) or a JSON document store; mirror the single `records(table,id,data,updatedAt)` + `outbox` shape |
| `kotlinx.serialization` + custom `RecurrenceSerializer` | Swift `Codable` + a **custom `Recurrence` `Codable`** emitting `{kind, daysOfWeek?, until?}` with `until` omitted when nil; **explicit-null** encoder for cleared optionals (`encodeNil`) |
| `WriteThrough` / `OutboxFlusher` / `DbRowCodec` / `RealtimeMirror` | Port the same-named Swift files (prior iOS app had them — codebase says "Port of the iOS WriteThrough.swift / DbRowCodec.swift / RealtimeMirror.swift") |
| supabase-kt (`SupabaseClient`, realtime channels, PostgREST) | **supabase-swift**: `SupabaseClient`, `.from("tasks").upsert/delete`, `.channel(...).onPostgresChange` |
| `System.currentTimeMillis()` / `ZoneId.systemDefault()` | `Date().timeIntervalSince1970 * 1000` / `Calendar.current`, `TimeZone.current` |
| Material `DatePicker` (UTC-millis) | SwiftUI `DatePicker` (local) — derive ISO via local `DateComponents`, **not** a UTC offset |
| `AlarmManager` exact alarms (reminders) | `UNUserNotificationCenter` local notifications (no exact-alarm permission; request authorization, degrade gracefully if denied) |
| WorkManager (background sync drain) | `BGTaskScheduler` (`BGAppRefreshTask`/`BGProcessingTask`) to drain the outbox; plus drain on foreground |
| Glance "Start Next" widget | WidgetKit; `pickStartNext` feeds a shared App Group store the widget reads |
| FCM push | APNs (the backend already has `platform`-aware token handling; Tasks itself only uses local reminders) |
| Android foreground service (focus timer) | **iOS has no foreground-service equivalent** — focus-session timing is out of this spec, but note: the detail "Focus" button must hand off to a model that uses background-safe timing (Live Activity / local-notification fallback), not a long-running service |

---

## 6. Acceptance criteria

1. **Port these XCTest suites 1:1** from Kotlin: `VisibleTasksTest` (24 cases), `TaskMutationsTest` (completion-stamp + `isCompletedToday` boundaries), `RecurrenceTest` (materialize/regen/label, incl. Jan-31 clamp & leap-Feb), plus `FreeSlots`/`formatTime` cases. All must pass pinned to UTC.
2. **`visibleTasks` behavioral parity**: Today area-agnostic; tag filter on all views; open-before-completed ordering; Backlog/Today created-today and past-only rules.
3. **`scheduleTask` parity**: first placement doesn't bump moveCount; same-slot re-tap is a no-op; real move bumps; recurring tasks diff + guarantee the chosen date is materialized.
4. **Optimistic write + outbox**: every mutation updates the UI immediately and enqueues a server op; cal_block ops `dependsOn` their task; deletes cancel queued upserts; cleared optionals serialize as explicit null (the clear persists round-trip).
5. **Date correctness**: scheduling near midnight in a west-of-UTC zone lands on the intended calendar day (no off-by-one).
6. **No priority UI, no `late_nudged` writes** — both are deliberately absent (web/DB parity).
7. The new-task submit sequence (`addTask` → `setReminderOverride` → `scheduleTask` → `saveCapture` → dismiss) and the detail edit/delete/complete/schedule flows behave identically to Android.