# iOS Rebuild Spec — Onboarding, Start-Next Widget, Feedback, Capture Inbox, Command Palette, Background Sync

Reference client: **Unstuck Android** (`/Users/ahmadtambaya/Desktop/projects/unstuck_android`). The iOS app is a fresh SwiftUI build that must reproduce the **behavior** of these Android surfaces 1:1. Where the Android comments call out a web parity rule, treat the web + DB as the deeper source of truth and Android as the concrete reference implementation.

This area bundles five user-facing surfaces plus one background mechanism:

1. **Onboarding** — first-run 4-step flow (`ui/onboarding/OnboardingScreen.kt`)
2. **Start-Next widget** — home-screen recommendation (`surface/StartNextWidget.kt`, `surface/StartNextSnapshot.kt`)
3. **Feedback** — one-way beta feedback composer (`ui/feedback/FeedbackSheet.kt`, `sync/FeedbackClient.kt`)
4. **Capture Inbox** — triage tray for captured thoughts (`ui/inbox/InboxScreen.kt`)
5. **Command Palette** — search tasks/notes + navigation actions (`ui/palette/CommandPalette.kt`)
6. **Background Sync worker** — periodic flush + widget refresh (`surface/SyncWorker.kt`)

---

## 1. Onboarding

### 1.1 What it does

A 4-step, single-screen wizard shown on first run only. It is a **device-local, account-independent** gate (see gotcha 4.1). On `onDone` the host swaps it out for the main scaffold.

Host wiring (`MainScaffold.kt:93,105-108`): `var onboarding = !vm.onboarded`. While true, render `OnboardingScreen` and return early (nothing else mounts). On `onDone`, set `onboarding = false`.

The screen is a vertically-scrollable column (`bg` background, `imePadding` so the keyboard doesn't cover the text field). At the top: a **progress bar** of 4 pills. Pill `i` is wide (16dp) if it is the current step, otherwise a 6dp dot; filled (`ink`) if `i <= step`, else `line2`. Below it a rounded card (radius 24, `surface` fill, `line` border) holds the step content. Card header is always `SECTION LABEL` "STEP {step+1} OF 4" in `primaryDeep`.

**Steps** (`step` 0..3):

- **Step 0 — Welcome.** Serif-italic title "Welcome.", body "Unstuck is built for minds that struggle to start. Three minutes to set up.", and a centered `Orbit` glyph (size 88) — the animated logo mark.
- **Step 1 — Life areas.** Title "What parts of life share your attention?", subtitle "Pick a few. You can change these any time." A wrap/flow of chips from the fixed option list (`AREA_OPTIONS`): `Work, Personal, Home, Health, Family, Volunteering, Study, Side project`. Tapping toggles membership in `pickedAreas`. Selected chip = filled `ink` bg + `bg`-colored text; unselected = `bg2` bg + `ink2` text, pill shape. **Default selection** is `["Work", "Personal", "Home"]`.
- **Step 2 — First task.** Title "What's one thing on your mind right now?", subtitle "Just one. Small is good. We'll start there." A single-line bordered text field bound to `firstTask`, placeholder "Reply to landlord about parking".
- **Step 3 — Focus treatment.** Title "Pick how focus feels.", subtitle "You can switch any time. Most people start with Ambient." Three selectable rows, one per `FocusTreatment`:
  - `AMBIENT` → "Ambient" / "A gentle breathing ring. Calm presence."
  - `COCKPIT` → "Cockpit" / "Timer, controls visible. Tighter feedback."
  - `MONK` → "Monk" / "Just the task. Everything else hidden."
  - Selected row = `ink` bg, `bg`-colored text, trailing "✓". Unselected = `bg2`. **Default** = `AMBIENT`.

**Footer row** (every step): a `Skip` text button on the left, spacer, and a dark `UButton` on the right labeled **"Begin"** on step 3, **"Continue"** otherwise.
- "Continue" → `step++`.
- "Begin" (step 3) → `finish()`.
- "Skip" → `vm.completeOnboarding(struggles = emptyList())` (no areas, no task, no treatment persisted) then `onDone()`.

### 1.2 `finish()` semantics (exact order matters)

```
1. vm.updateSettings { it.copy(treatment = treatment) }      // persist chosen FocusTreatment
2. if firstTask is non-blank:
      vm.addTask(name = firstTask.trim(), estimateMin = 15, lifeArea = pickedAreas.firstOrNull())
3. vm.completeOnboarding(struggles = emptyList(), areas = pickedAreas.toList())
4. onDone()
```

Notes the iOS engineer must preserve:
- The first task gets **estimateMin = 15** (not the default 25) and is assigned the first picked area.
- `completeOnboarding(areas:)` is the **single source** of life-area seeding. Onboarding does NOT separately seed areas — it passes the picked list to `completeOnboarding`, which seeds only if there are currently zero areas. Do not double-seed.

### 1.3 `completeOnboarding` rules (port of `AppViewModel.kt:655-669`)

```
if lifeAreas is empty:
    palette = ["indigo","coral","green","amber","teal","blue","violet","red"]
    seed = areas.ifEmpty { ["Work","Personal","Home","Health"] }
    for (i, name) in seed:
        upsertLifeArea(LifeArea(id: newUuid(), name: name, color: palette[i % palette.count], sortOrder: i))
if uid != nil && struggles non-empty:
    coordinator.preferences.setAdhdStruggles(uid, struggles)    // best-effort; struggles always empty here
graph.onboarded = true
```

The `AREA_PALETTE` constant in `OnboardingScreen.kt` (`["indigo","coral","green","amber","violet","blue"]`) is **dead/unused** — the real palette lives in `completeOnboarding`. Use the `completeOnboarding` palette of 8.

### 1.4 State persistence

- `step` and `firstTask` are saved across recompositions (Android `rememberSaveable`). On iOS, hold them in the view's `@State`; they survive a redraw but a full process kill before finishing legitimately restarts onboarding (acceptable — onboarding isn't yet committed). `pickedAreas` and `treatment` are plain in-memory state.
- `graph.onboarded` is a single Boolean in device-local prefs (Android SharedPreferences key `"onboarded"` in file `unstuck.app`). See 4.1.

### 1.5 iOS equivalents

- Compose screen → a SwiftUI `View` driven by `@State var step`, `@State var pickedAreas: Set<String>`, `@State var firstTask`, `@State var treatment: FocusTreatment`.
- `Orbit` → reuse the app's logo-mark view at size 88.
- `vm.onboarded` / `graph.onboarded` → `UserDefaults.standard.bool(forKey: "onboarded")` (NOT the App Group — this is per-device app prefs, see 4.1).
- `updateSettings`, `addTask`, `completeOnboarding` are the same store-layer methods the rest of the app uses; wire them identically.

---

## 2. Start-Next Widget

### 2.1 What it does

A home-screen widget that shows the single top "what to do next" recommendation. Tapping it opens the app. It reads a **snapshot** the app writes; it does no computation itself.

Content (`StartNextWidget.kt:42-58`), a vertically-centered column, 16dp padding:
- Header label "START NEXT" — 11sp, medium, `muted` color.
- If `name == null` → bold 18sp "All clear".
- Else → the task `name` (bold 18sp, **max 2 lines**) and a second line `"{estimate ?? 25} min"` in `muted` 12sp.

**Theming**: follow the system light/dark mode (the Android widget reads `uiMode`; on iOS, WidgetKit handles this via the environment `colorScheme`). Colors:
- Light: ink `#2A2A33`, muted `#8A8A95`, bg `#FAFAF7`.
- Dark: ink `#F0EEF5`, muted `#9A98A5`, bg `#1A1822`.

Whole widget is tappable → opens the app (Android `actionStartActivity<MainActivity>`).

### 2.2 The snapshot contract (`StartNextSnapshot.kt`)

A tiny shared key-value store named `start_next` with two keys:
- `name: String?` — the recommended task name (absent ⇒ "All clear").
- `estimate: Int` — minutes (defaults to 25 if name present but no estimate).

`writeStartNext(name, estimateMin)`:
- `name == nil` ⇒ **remove both keys**.
- else ⇒ set `name`, set `estimate = estimateMin ?? 25`.

### 2.3 Who writes the snapshot and when

Two writers, intentionally redundant (the comments at `AppViewModel.kt:290-303` and `SyncWorker.kt:25-32` explain why):

1. **In-app, while the app is alive** (`AppViewModel.init`): a reactive pipeline combines `tasks + blocks + liveSession`, runs `pickStartNext(tasks, blocks, liveSession.taskId, areaFilter = null)`, `distinctUntilChanged()`, and on each change calls `writeStartNext(rec?.name, rec?.estimateMin)` then `StartNextWidget().updateAll()`. This keeps the widget live during use.
2. **Background, across process death** (`SyncWorker.doWork`): the in-app updater only runs while the view-model is alive, so the periodic worker recomputes `pickStartNext(store.tasks, store.blocks, liveSession?.taskId, null)` from the latest local store, writes the snapshot, and forces a widget reload. Without this the widget freezes when the app is killed.

Both pass `areaFilter = null` (widget always reflects the unfiltered global recommendation).

### 2.4 `pickStartNext` — the ranking rule (port `core/logic/PickStartNext.kt`)

Pure function, port of web `lib/pick-start-next.ts`:

```
pickStartNext(tasks, blocks, liveTaskId, areaFilter = null) =
    tasks
      .filter { !done && later != true && id != liveTaskId }
      .filter { matchesArea(lifeArea, areaFilter) }
      .sortedWith(ranker)
      .firstOrNull()
```

`ranker` (descending priority, then shortest first, then oldest first):
1. priorityRank desc — URGENT=4, HIGH=3, MEDIUM=2, LOW=1, **null ⇒ LOW(1)**.
2. `estimateMin` ascending.
3. `createdAt` ascending — **ISO-8601 strings compared lexicographically** (equivalent to chronological because the strings are UTC ISO; this matches the web's `localeCompare` tiebreak). The sort must be **stable**.

`blocks` is currently unused by `pickStartNext` but is part of the signature for parity — keep the parameter. `matchesArea(lifeArea, null)` returns true for all; reuse the existing area-match helper.

### 2.5 iOS equivalents

- Glance widget → **WidgetKit** widget in a widget extension. Tap action → `widgetURL` / `Link` deep-linking into the app.
- DataStore `start_next` → **App Group `UserDefaults`** (the comments explicitly call this "Analog of the iOS UnstuckShared App Group snapshot"). Write keys `name` (String, remove when nil) and `estimate` (Int). After writing, call `WidgetCenter.shared.reloadTimelines(ofKind:)` (the analog of `updateAll`).
- `updateAll()` from both the in-app reactive pipeline and the background task.
- The in-app reactive pipeline → a Combine/`AsyncSequence` over the same task/block/live-session publishers, debounced with `removeDuplicates()`, recomputing `pickStartNext`.
- **`pickStartNext` must be in shared code reachable by both the app and the widget extension** (the widget reads only the snapshot, but the BG task that writes it lives app-side; ensure the logic module is linked into both targets if the widget ever needs to recompute).

---

## 3. Feedback

### 3.1 What it does

A one-way beta-feedback composer presented as a **bottom sheet** (Android `ModalBottomSheet`, `skipPartiallyExpanded`). No threads, no replies — submissions land in the `feedback` table and the team triages in the Supabase dashboard. Opened from a host action; `currentScreen` is the current tab name (`MainScaffold.kt:284` passes `tab`).

**Compose state** (`FeedbackSheet.kt`):
- `body: String` (the only required field).
- `category: String` — default `"bug"`.
- `sending`, `sent`, `error` booleans/optional.

**Layout** when not yet sent:
- Mono label "SEND FEEDBACK", subtitle "Bugs, ideas, anything — straight to the team."
- **Category chips** row from `CATEGORIES = [("bug","Bug"), ("idea","Idea"), ("praise","Praise"), ("other","Other")]`. Tap selects; selected = `ink` bg + `bg` text; unselected = `surface` bg + `line2` border + `ink3` text. The **key** (`bug`/`idea`/…) is sent to the server; the label is UI only.
- **Message field** — multi-line, min height 88dp, `bg2` bg, placeholder "What's on your mind?". Editing is blocked while `sending`.
- **Context line** (mono, `ink4`): `"Attaches v{VERSION_NAME}" + (currentScreen ? " · {screen}" : "") + " · {Build.MODEL}"`. This is a *preview* string; the *actual* attached device string is richer (see 3.3).
- `error` shown in `coralDeep` if present.
- **Send button** (coral `UButton`): label "Sending…" while in-flight, else "Send". **Enabled only when `body` is non-blank and not sending.** On tap: `sending = true; error = nil`, call `vm.sendFeedback(body, category, currentScreen)`, then `sending = false`; on success `sent = true`, on failure `error = "Couldn't send — check your connection and try again."`.

**Sent state**: replaces the body with a centered thank-you — serif-italic "Thanks — we got it 🙏" and "It goes straight to the team." A `LaunchedEffect` runs `delay(1100ms)` then auto-dismisses. (iOS: schedule a 1.1s dismissal task when entering the sent state; cancel it if the view disappears first.)

### 3.2 `sendFeedback` (port `AppViewModel.kt:707-715`)

```
sendFeedback(body, category, screen) -> Bool:
    fb = feedback ?? return false                          // not configured / signed out path
    device = "{Build.MANUFACTURER} {Build.MODEL} · Android {VERSION.RELEASE}"
    return fb.submit(
        id: newUuid(),
        body: body.trim(),
        category: category,
        email: auth.currentEmail,            // nil if unknown
        appVersion: BuildConfig.VERSION_NAME,
        platform: "android",                 // → "ios" on iOS
        device: device,
        screen: screen,
    )
```

On iOS:
- `platform = "ios"`.
- `device` example: `"Apple iPhone15,3 · iOS 18.2"` — manufacturer "Apple", model identifier (e.g. `UIDevice` model identifier), `· iOS {systemVersion}`. Mirror the Android shape `"{maker} {model} · {OS} {ver}"`.
- `appVersion` = `CFBundleShortVersionString`.

### 3.3 `FeedbackClient.submit` (port `sync/FeedbackClient.kt`)

Online-only. Inserts one row into the `feedback` table and returns `true`; **any** failure (including signed-out) returns `false` so the UI can offer a retry. There is **no offline outbox** — feedback is low-volume and the user is normally online.

```
submit(id, body, category, email, appVersion, platform, device, screen) -> Bool:
    uid = auth.currentUser?.id ?? return false
    supabase.from("feedback").insert(Row(
        id, body, category, user_id: uid, email, app_version: appVersion,
        platform, device, screen
    ))
    return true     // false on any thrown error (runCatching → getOrDefault(false))
```

**Row JSON field mapping** (exact column names — see table below): `user_id`, `app_version` are snake-cased via `@SerialName`; the rest match. `id` is a client-generated UUID.

### 3.4 Supabase table — `public.feedback` (migration `027_feedback.sql`)

| column | type | notes |
|---|---|---|
| `id` | uuid PK | default `gen_random_uuid()`; client supplies its own UUID |
| `user_id` | uuid NOT NULL | FK → `auth.users(id)` `on delete cascade` |
| `email` | text | denormalized at submit time for easy triage |
| `category` | text | `'bug' | 'idea' | 'praise' | 'other'` |
| `body` | text NOT NULL | the message |
| `app_version` | text | |
| `platform` | text | `'android' | 'ios' | 'web'` |
| `device` | text | e.g. `'Google Pixel 7 · Android 15'` |
| `screen` | text | tab: `today/tasks/calendar/lists` |
| `created_at` | timestamptz NOT NULL | default `now()` |

- RLS: single owner policy `feedback_own` — `for all using (user_id = auth.uid()) with check (user_id = auth.uid())`. The own-select half also lets the insert's `returning=representation` read the row back. No realtime, no `updated_at` (immutable rows). Team reads all rows via `service_role` in the dashboard.

### 3.5 iOS equivalents

- `ModalBottomSheet` → SwiftUI `.sheet` with `.presentationDetents([.large])` (mirror `skipPartiallyExpanded` = no half-detent), or a custom sheet matching the design.
- `supabase.from("feedback").insert(...)` → **supabase-swift** `client.from("feedback").insert(row).execute()`, wrapped so any error → `false`.
- The `Row` struct: `Codable` with `CodingKeys` mapping `userId → user_id`, `appVersion → app_version`. **Encode every field explicitly** (see gotcha 4.2) — do not let `nil`/default-omission drop `platform`.

---

## 4. Gotchas (read before coding any of the above)

### 4.1 Onboarding is per-device, not per-account
`graph.onboarded` lives in **app-local prefs** (Android SharedPreferences `unstuck.app`), independent of the signed-in user. It is NOT cleared on sign-out and NOT synced. Consequence: a returning user who signs into a new account on the same device does **not** re-see onboarding; a fresh install does. On iOS use plain `UserDefaults.standard` (the app sandbox), **not** the App Group (that's only for the widget snapshot). Replicate this exactly — onboarding seeds areas only when `lifeAreas` is empty, so a signed-in user with synced areas is harmless.

### 4.2 kotlinx default-omission → encode every field (the `platform` trap)
`FeedbackClient` has a top-of-file warning: kotlinx serialization omits default-valued fields from JSON (`encodeDefaults` off), so a defaulted field would silently never be sent — the recurring `platform` gotcha. **On iOS the equivalent risk is `JSONEncoder` dropping `nil` optionals or you adding Swift default values.** `platform`, `body`, `user_id`, `id` are non-null in the DB; ensure they are always serialized. Set every field explicitly; do not give `platform`/`body` Swift defaults that could be elided.

### 4.3 UTC ISO strings, lexicographic sort
`pickStartNext`'s `createdAt` tiebreak relies on ISO-8601 UTC strings sorting lexicographically == chronologically. `Capture.at` and the inbox sort (`sortedByDescending { it.at }`) rely on the same. **All timestamps are UTC ISO strings**, not locale-formatted. The `relPast` ages are computed from `now - parseMillis(at)`; if `at` fails to parse, the code falls back to `now` (delta 0 → "just now"). Use a single UTC ISO parser/formatter; never local time.

### 4.4 LWW / sync model
Captures, tasks, and life areas flow through the same last-write-wins sync the rest of the app uses (whole-row upsert via outbox, hydrate, realtime). **Archived-capture ids and `onboarded` are device-local and never sync.** Feedback is online-only with no outbox. The Start-Next snapshot is a derived projection (never synced) — only the underlying tasks/blocks sync.

### 4.5 Background worker timing is best-effort (no exact alarms here)
`SyncWorker` is a periodic 30-min best-effort job with a network constraint — **not** an exact alarm. None of this area uses `AlarmManager`/exact alarms, so the exact-alarm-denial gotcha doesn't apply *here* (it applies to the notifications area). The iOS analog (`BGTaskScheduler`) is *more* throttled than Android WorkManager: iOS decides when (or whether) to run. Do not assume a guaranteed 30-minute cadence.

### 4.6 Dependency ordering in `finish()` and `completeOnboarding`
Order is load-bearing: persist treatment → add first task (assigned to first picked area) → `completeOnboarding(areas)` (seeds areas only if empty, then flips `onboarded`). Adding the first task **before** areas are seeded is fine because the task references the area *by name string* (`lifeArea` is a free-text name, not a FK). Keep the order.

### 4.7 Capture promote preserves the capture
`promoteCapture` does **not** delete the capture — it creates a new task and the InboxScreen then *archives* the capture (`onPromote = { promoteCapture(cap); archiveCapture(cap.id) }`). Web parity: capture is preserved.

### 4.8 Widget recompute happens in two places
Don't drop either writer (in-app reactive + background). The in-app one alone leaves the widget frozen after process death; the background one alone leaves it laggy during active use. Both call `writeStartNext` + a widget reload.

### 4.9 Feedback `feedback` getter may be null
`vm.sendFeedback` returns `false` immediately if the feedback client is unavailable (`graph.coordinator?.feedback` null — e.g. unconfigured Supabase or signed-out). The sheet treats this as a normal "couldn't send, retry" error. Don't crash or assume a client exists.

---

## 5. Capture Inbox

### 5.1 What it does

The triage tray for captured thoughts (notes taken during focus, from a task, or on the fly). Reached from the Today header (`MainScaffold.kt:263`, pushed as a route → full-screen with a back `AppBar` titled "Inbox"). Each capture can be **Promoted** to a task, **Opened** in its source task, **archived** ("Done"/"Restore"), or **Discarded** (deleted). Mirrors web `/inbox`.

**Data sources** (`InboxScreen.kt:54-66`):
- `inboxCaptures` — captures not in `archivedCaptureIds`, newest first.
- `captures` — all captures.
- `archivedCaptureIds` — device-local set.
- `tasks` — to resolve `taskName(taskId)`.
- `now` — refreshed every **30s** so the "Xm ago" ages don't freeze (`LaunchedEffect` loop).

**View toggle**: `showArchived` flips between the "To process" list (`inbox`) and the "Archived" list (`archived` = all captures whose id ∈ archived, newest first). Header shows a `SectionLabel` ("To process" / "Archived") and a right-aligned toggle: `"Archived ({count})"` (shown when there are archived items or already in archived view) ⇄ `"← Back to inbox"`.

**Empty states**:
- Inbox empty: "Inbox zero. Capture a thought with the capture action during a focus session."
- Archived empty: "No archived captures."

### 5.2 The card (`InboxCard`)

Per capture, a rounded `surface` card with `line` border:
- Header row: a 7dp dot colored by tag, the **tag name** rendered as `tag.name.replace('_','-')` in mono-bold colored by tag, the relative time (mono, `ink3`), and — if the capture has a resolvable source task — `"· from {taskName}"` (truncated, single line).
- Body: `cap.body`, `ink2`.
- Actions row:
  - **Not archived view**: `"Promote →"` (bold, `primaryDeep`) → promote + archive; then `"Open"` (only if `cap.taskId != nil`) → open task; spacer; `"Done"` → archive; `"Discard"` → delete.
  - **Archived view**: `"Open"` (if taskId) on the left; spacer; `"Restore"` → unarchive; `"Discard"` → delete.

**Tag → color** (`tagColor`):
- `FOLLOW_UP → primaryDeep`, `IDEA → amber`, `EDIT → blue`, `QUESTION → green`, `DISTRACTION → coral`.

**Relative time** (`relPast(deltaMs)`): `m = max(0, deltaMs/60000)`; `< 1 → "just now"`, `< 60 → "{m}m ago"`, `< 1440 → "{m/60}h ago"`, else `"{m/1440}d ago"`.

### 5.3 Actions (AppViewModel)

- `archiveCapture(id)` / `unarchiveCapture(id)`: add/remove from the device-local `_archivedCaptureIds` set and persist it (`saveArchivedCaptureIds`). The set is loaded at startup (`loadArchivedCaptureIds`) and cleared on sign-out (like dismissed nudges).
- `promoteCapture(capture) -> TaskItem` (`AppViewModel.kt:433-436`): `addTask(name = capture.body, estimateMin = 25, lifeArea = "Work", tags = ["from-capture", tagName])` where `tagName = capture.tag.name.lowercase().replace('_','-')`. The capture is **preserved** (not deleted). The Inbox then archives it.
- `deleteCapture(id)` (`AppViewModel.kt:423-426`): `write.deleteCapture(id)` then `unarchiveCapture(id)` (drop any device-local archived flag so the id set doesn't leak).

### 5.4 Capture model (`core/model/Models.kt:142`)

```
Capture(
    id: String,
    taskId: String? = null,
    sessionId: String? = null,
    tag: CaptureTag,
    body: String,
    at: String,           // UTC ISO-8601
)
```

`CaptureTag` (`Enums.kt:49`, `@SerialName` wire values):
`FOLLOW_UP="follow-up"`, `IDEA="idea"`, `EDIT="edit"`, `QUESTION="question"`, `DISTRACTION="distraction"`.

The Supabase `captures` table is the same one the rest of the app syncs (id, task_id, session_id, tag, body, at). The serialized `tag` uses the hyphenated wire value; the *displayed* tag uses `name.replace('_','-')` (e.g. enum `FOLLOW_UP` → "FOLLOW-UP" shown).

### 5.5 iOS equivalents

- `LazyColumn` → `List`/`LazyVStack` in a `ScrollView`. Full-screen route with a back button.
- 30s `now` refresh → a `Timer.publish(every: 30)` or a `Task` loop updating `@State now`.
- `archivedCaptureIds` → device-local `UserDefaults` set of strings, loaded at launch, cleared on sign-out. Not synced.
- `collectAsStateWithLifecycle` → `@Published`/`@StateObject` view-model streams.

---

## 6. Command Palette

### 6.1 What it does

A full-screen search overlay (`MainScaffold.kt:252`, pushed as `Route.Palette`) for jumping to tasks, notes, and app sections. Top bar: a pill search field (search icon + placeholder "Search tasks + actions") and a `"Cancel"` text button (`primaryDeep`) that dismisses.

**Result computation** (`CommandPalette.kt:51-66`), where `q = query.trim().lowercase()`:

1. **Actions** (always present, filtered by query): `"Go to Today"`, `"Go to Tasks"`, `"Go to Calendar"`, `"Go to Lists"`, `"Settings"` — badge `"ACTION"`. Kept when `q` is empty or the title contains `q`.
2. **Task results**: open, non-done tasks matching `q` (or all if `q` empty), **take 8**. Title = task name, meta = `lifeArea ?? "—"`, badge `"TASK"`, action = open the task.
3. **Note results**: only when `q` is non-empty — captures whose body contains `q` **and** that have a resolvable owning task (`taskId != null` and the task exists). **take 4**. Title = capture body, meta = `tag.name.lowercase()`, badge `"NOTE"`, action = open the owning task. (Per the comment, notes without a resolvable task are dropped — they used to be a dead end.)

**Final order**: `taskResults + noteResults + actions`.

Each result row: title (medium, `ink`, single line), optional meta line (`ink3`), and a right-aligned mono badge with letter-spacing. Tapping runs `r.run()`.

**Navigation callbacks** (from host):
- `onOpenTask(task)` → `pop(); push(Route.Detail(task.id))`.
- `onTab(tab)` → set tab, clear the nav stack.
- `onSettings()` → clear stack, push Settings.
- `onDismiss` → `pop()`.

### 6.2 iOS equivalents

- Full-screen overlay → a `.fullScreenCover` or a pushed view with a `TextField` (auto-focused) + a `List` of results. `imePadding` → keyboard-avoidance.
- Reuse the same task/capture streams; the filtering and `take` limits are pure and must match exactly (8 tasks, 4 notes).
- `onTab` clears the navigation stack; replicate by resetting the nav path and switching the selected tab.

---

## 7. Background Sync Worker

### 7.1 What it does (`SyncWorker.kt`)

A periodic, best-effort background job that (a) flushes the outbox + hydrates via `coordinator.syncNow()`, and (b) recomputes and writes the Start-Next widget snapshot from the latest local store, then forces a widget reload. Both steps are wrapped in `runCatching` (a failure never fails the worker — it always returns `success`).

**Scheduling** (`SyncWorker.schedule`, called from `MainActivity.onCreate` at `MainActivity.kt:67`):
- Periodic, **every 30 minutes**, with a **`NetworkType.CONNECTED` constraint** ("a sync with no connection just wakes the device to fail").
- Unique work name `"unstuck_periodic_sync"`, `ExistingPeriodicWorkPolicy.KEEP` (don't reschedule/replace if already enqueued).

### 7.2 iOS equivalents

- WorkManager periodic worker → **`BGTaskScheduler`** with a `BGAppRefreshTask` (and/or `BGProcessingTask` with `requiresNetworkConnectivity = true` to mirror the network constraint). Register a unique identifier (analog of `"unstuck_periodic_sync"`); reschedule the next task at the end of each run.
- **Constraint note**: iOS does not honor a fixed 30-min cadence — `setEarliestBeginDate(30 min)` is a *floor*, not a guarantee. Document that the widget's background freshness is best-effort and the in-app reactive writer is the primary path.
- Inside the task: `try? coordinator.syncNow()`, then recompute `pickStartNext(store.tasks, store.blocks, liveSession?.taskId, nil)`, `writeStartNext(...)`, `WidgetCenter.shared.reloadTimelines(ofKind:)`. Always complete the task with `success` even on partial failure (mirror `runCatching` → `Result.success()`), then schedule the next run.
- There is **no foreground-service analog needed here** — this is background refresh, not a long-running foreground task. (The focus-session/notifications area is where the iOS foreground-execution constraint actually bites; this worker is fine as a BG task.)

---

## 8. Shared models & helpers to port (this area's dependencies)

| Android | Purpose | iOS target |
|---|---|---|
| `core/model/Models.kt` `Capture`, `LifeArea`, `TaskItem` | data | `Codable` structs, shared module |
| `core/model/Enums.kt` `FocusTreatment`, `CaptureTag` | enums w/ wire `@SerialName` values | `String`-raw `Codable` enums |
| `core/logic/PickStartNext.kt` `pickStartNext`/`pickUpNext` + `ranker`/`priorityRank` | pure ranker | shared logic module (app + widget) |
| `matchesArea(lifeArea, filter)` | area filter | shared helper |
| `core/time/Time.parseMillis` | UTC ISO → millis | shared UTC parser |
| `newUuid()`, `isoNow()` | id/time gen | `UUID().uuidString.lowercased()`, ISO-8601 UTC formatter |
| `StartNextSnapshot.writeStartNext` | App Group snapshot writer | App Group `UserDefaults` writer |
| `FeedbackClient.submit` + `Row` | feedback insert | supabase-swift insert |

### 8.1 Tests to mirror
There is no dedicated test file for these UI surfaces, but the **`pickStartNext` ranker is the shared, test-covered core** (web `lib/pick-start-next.test.ts` is the canonical spec; Android relies on the same rules). Port `pickStartNext` and `pickUpNext` with unit tests asserting: done/Later/live-task exclusion, area filtering, and the priority→estimate→createdAt(lexicographic, stable) ordering. Also unit-test `relPast` (boundaries at 1/60/1440 minutes), the feedback `Row` JSON field names (`user_id`, `app_version`, never-omitted `platform`), and `promoteCapture` seeding (`estimateMin=25`, `lifeArea="Work"`, tags `["from-capture", <hyphenated-tag>]`, capture preserved).

---

## 9. Acceptance checklist

- [ ] First run shows the 4-step onboarding; subsequent launches don't. Onboarding is per-device (`UserDefaults`, not App Group, not account-synced).
- [ ] Default area picks are Work/Personal/Home; default treatment Ambient; first task seeded at 15 min with the first picked area.
- [ ] "Skip" calls `completeOnboarding([])` (no task, no areas, no treatment) and exits; areas seed only if none exist.
- [ ] Widget reads the App Group snapshot: "All clear" when empty, else task name (≤2 lines) + "{est} min"; follows system light/dark; tap opens app.
- [ ] Snapshot is rewritten both in-app (reactive on task/block/live-session change) and from the BG task; `pickStartNext` always passed `areaFilter = nil`.
- [ ] Feedback sheet: category chips (bug default), required non-blank body, context line, Send disabled until valid, "Sending…"/error/retry, 1.1s auto-dismiss thank-you; row inserts with `platform="ios"`, real device string, client UUID, all fields explicitly encoded.
- [ ] Inbox: To-process vs Archived toggle, 30s age refresh, tag dots/colors, "· from {task}", Promote (preserves capture + archives), Open (only with taskId), Done/Restore, Discard (deletes + clears archived flag). Archived ids are device-local, cleared on sign-out.
- [ ] Palette: actions always shown (filtered), ≤8 task results, ≤4 note results (query-only, resolvable task only), order tasks→notes→actions; correct nav callbacks.
- [ ] BG refresh registered as a `BGTaskScheduler` task with network requirement; never throws out of the task; reschedules next run; treated as best-effort.