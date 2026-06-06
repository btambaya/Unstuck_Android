# iOS Rebuild Spec — Focus Mode (1:1 from Android)

> **Reference client:** `unstuck_android`. The existing iOS Focus implementation is discarded; this spec is the authoritative description of behavior to re-implement in SwiftUI. Android is the source of truth for behavior; the web app (`lib/use-focus-timer.ts`) is the original logic port. Where Android deviates from a literal SwiftUI mapping (foreground service, exact alarms), this spec calls out the iOS equivalent and the behavioral compromise.

---

## 0. Scope & file map (Android sources this spec covers)

| Android source | Role |
|---|---|
| `core/logic/FocusTimer.kt` | **Pure state machine** — port verbatim into a Swift struct/enum. The single most important file. |
| `core/test/FocusTimerTest.kt` | 22 tests pinning the pure logic. Port them to XCTest 1:1. |
| `ui/focus/FocusScreen.kt` | The full-screen focus UI: treatments, ring, overrun check-in, action buttons, soft-exit, pause-reasons. |
| `ui/focus/CaptureSheet.kt` | Bottom sheet to log a capture mid-session. |
| `ui/focus/ReflectSheet.kt` | End-of-session "How did that land?" modal (ephemeral, nothing stored). |
| `surface/FocusTimerService.kt` | Foreground service = ongoing live notification. iOS analog: **Live Activity (ActivityKit)** + local notification fallback. |
| `surface/FocusCommands.kt` | Process-level focus mutations shared by UI and notification actions. |
| `surface/AmbientAudio.kt` | Looping ambient audio while focusing. |
| `surface/PausedCheckinScheduler.kt` | "Did you step away?" check-in ~14 min after pause. iOS analog: `UNUserNotificationCenter` time-interval trigger. |
| `surface/NotificationActionReceiver.kt` | Handles Pause/Resume/Snooze/End/Capture from the shade. iOS analog: notification/Live-Activity action handlers. |
| `AppViewModel.kt` (focus methods) | `startFocus/pauseFocus/resumeFocus/setTreatment/extendFocus/finishFocus/saveCapture/saveReasonLog`. |

---

## 1. What it does — behavior, screens, states, flows, edge cases

### 1.1 Entry

`FocusScreen(vm, task, onClose, autoCapture)` is presented for a single `TaskItem`. On appear:

1. `vm.startFocus(task)` is called (keyed on `task.id`). See §3.4 for the resume-aware logic — re-entering the same task does **not** reset; entering a *different* task finalizes the previous session first.
2. A 1 Hz ticker (`nowMs`) drives the displayed timer. This is **display only** — it never feeds the pure logic except as the injected `now`.
3. The live notification / Live Activity is started/updated (§5.5).
4. `autoCapture == true` (arrived via the notification "Capture" action) immediately opens the Capture sheet.

> **Critical:** leaving the focus screen does **NOT** end the session. The live session persists (it's a single-row local store entity), the notification/Live-Activity stays up, and the user can return from Today and resume. The session is torn down **only** by the terminal actions: **Done**, **End for now**, or **Stop here** (overrun). "← Out" and "Save for later" keep it alive.

### 1.2 Screen layout (top → bottom)

- **Background:** dark indigo radial gradient. From `oklch(0.30, 0.10, 280)` at top-center to `oklch(0.16, 0.02, 280)`, radius ~1400px. Implement with an OKLCH→sRGB helper (the app already uses an `oklch()` color function — port it). All foreground text is white at varying alpha.
- **Top bar:** a pill "← Out" (white @ 10% bg, white @ 70% text).
  - Tap behavior: if `focusSoftExit == true` **and** not paused **and** a session is running → show the **soft-exit confirm dialog**. Otherwise call `onClose()` directly.
- **State label:** `SectionLabel` showing `"PAUSED"` when paused else `"FOCUSING"` (white @ 55%).
- **Treatment switcher:** always shown (even in Monk — never trap the user). Three chips: `ambient`, `cockpit`, `monk` (lowercased enum names). Selected = solid white @ 92% bg, dark text `#14122A`; unselected = white @ 8% bg, white @ 70% text. Tap → `vm.setTreatment(t)` (persists to settings + live session). **Do not** reuse a light-theme chip component — colors must be explicit white-on-dark or unselected chips look selected.
- **Ambient ring (AMBIENT treatment only):** a 200dp `Canvas`. Background arc full circle white @ 10%, stroke width 4. Progress arc starts at -90° (top), sweeps `360 * progress`, `StrokeCap.Round`. Color = white when running, amber `oklch(0.80, 0.13, 75)` when paused. An `Orbit` logo (white variant, 130px) is centered inside. (Cockpit and Monk do **not** show the ring.)
- **Task block (hidden in MONK):**
  - Task name in serif-italic 24.
  - `firstPhysicalAction` (if non-blank): `"→ <action>"`, white @ 82%, max 2 lines — "the smallest concrete step".
  - `"<estimateMin>m estimate"`, white @ 65%.
- **Timer:** `formatMMSS(elapsed)` at 52pt light. Color = coral when `state == OVERRUN`, else white. Below it: `"<formatMMSS(remaining)> left"` white @ 50%.
- **Overrun check-in** (only when `state == OVERRUN && !paused`): coral text `"Past your estimate — still going well?"` plus a row of three buttons:
  - `+10 min` (soft) → `vm.extendFocus(10)`
  - `In the zone` (soft) → `vm.extendFocus(15)`
  - `Stop here` (hard/coral) → `vm.finishFocus(task, markDone=false)` + stop service + cancel paused-checkin + `onClose()`.
- **Captures rail (COCKPIT treatment only):** `CapturesRail` — the last 3 captures for this task (`it.taskId == task.id`), rendered as `"• <body>"` bullets under a "Captures" label. Hidden if empty.
- **Primary action row** (3 buttons):
  - `Capture` (soft) → open Capture sheet.
  - `Pause` / `Resume` (soft) → if paused, `vm.resumeFocus()`; else `vm.pauseFocus()` **and** if `focusPauseReasons` show the Pause-Reasons overlay.
  - `Done` (hard/coral) → captures `reflectElapsed`, `vm.finishFocus(task, markDone=true)`, stop service, cancel paused-checkin, show Reflect sheet.
- **Secondary action row** (2 text links):
  - `Save for later` → `vm.pauseFocus()`; if `focusPauseReasons` set `exitAfterReason=true` and show Pause-Reasons (close after picking), else `onClose()` immediately. (Pauses + exits; resumable from Today.)
  - `End for now` → captures `reflectElapsed`, `vm.finishFocus(task, markDone=false)`, stop service, cancel paused-checkin, show Reflect sheet. (Records session, task stays open.)

### 1.3 Button styling (`FocusBtn`)

Pill, padding 22×12. `soft=true` → white @ 10% bg; `soft=false` → coral bg. Text white, medium 14.

### 1.4 Modal flows

**Soft-exit confirm** (`AlertDialog`): title "Leave focus?", body "Your timer keeps running — you can pick it back up from Today." Buttons: **Leave** (`onClose()`) / **Stay** (dismiss). Light surface, dark ink — this dialog uses the normal app theme (not the dark focus palette).

**Pause-Reasons overlay** (full-screen scrim `#CC0B0B14`, tap-out dismisses). Card `#1A1B26`, label "WHY ARE YOU PAUSING?", then 5 tappable rows:
```
["Bathroom", "Drink", "Quick question", "Stuck — need a moment", "Other"]
```
- onPick(reason): `vm.saveReasonLog(task.id, reason)` (logs a `ReasonLog` with `action = PAUSE`); dismiss; if `exitAfterReason`, also `onClose()`.
- onDismiss: same exit behavior if `exitAfterReason`, but no reason logged.
- This overlay fires only **after** the session is already paused — it records *why* (optional).

**Capture sheet** (`ModalBottomSheet`, see `CaptureSheet.kt`): a single-line text field placeholder "What just popped up?", a row of 5 tag chips, a "Save" button.
- Tags (enum → label): `FOLLOW_UP→"follow-up"`, `IDEA→"idea"`, `EDIT→"edit"`, `QUESTION→"question"`, `DISTRACTION→"distraction"`. Default selected = `FOLLOW_UP`.
- Each tag has a soft-bg/dark-ink color pair (follow-up=primarySoft/primaryDeep, idea=amberSoft/amberInk, edit=blueSoft/blueInk, question=greenSoft/greenInk, distraction=coralSoft/coralDeep). Unselected = surface bg + 1pt line2 border.
- Header label "CAPTURE · STAYS ATTACHED" (mono 11).
- Save: only if text non-blank → `vm.saveCapture(task.id, sessionId=live?.id, tag, text.trim())`, then dismiss. `sessionId` is the **live session id** so captures join back to the eventual Session row.

**Reflect sheet** (`ReflectSheet.kt`, centered modal over scrim): header `"SESSION COMPLETE · <round(elapsedSec/60)>M"`, title "How did that land?", four radio options:
```
flow→"It flowed." (greenSoft)
okay→"It was OK." (blueSoft)
sticky→"It was sticky." (amberSoft)
stopped→"I had to stop." (coralSoft)
```
"Skip" link + "Done" button — **both just dismiss**. **Nothing is persisted** (the label is "Done" not "Save" deliberately). On dismiss → `onClose()` (leaves focus). `elapsedSec` is captured *before* `finishFocus` clears the session.

### 1.5 State machine display (`FocusState`)

Derived purely (see §3.3). UI cares about:
- `IDLE` — no session (elapsed 0).
- `RUNNING` — normal.
- `PAUSE` — paused; ring amber; no overrun UI.
- `OVERRUN` — past estimate + grace; timer turns coral; overrun check-in shown.

(`STARTING`, `DONE`, `RESUME` exist in the enum for web parity but are not produced by `deriveState`; the "done" display is handled transiently by the UI navigating to the Reflect sheet, not by a derived state.)

### 1.6 Edge cases to preserve

- Opening a **paused** (saved-for-later) session must not flash "FOCUSING" — the Live Activity/notification must be armed with `paused: live.paused` on the *initial* arm.
- Re-entering the **same task** keeps current paused/running state; it is not auto-resumed by opening the screen.
- Entering a **different task** while one is live: finalize the previous (write its Session row + accumulate focus time) before starting the new one — never silently discard elapsed time.
- The ring/overrun coloring use the session's **frozen** `sessionEstimateMin`, not `task.estimateMin`, so a mid-session estimate edit doesn't desync the ring from the overrun logic.
- `Done`/`End for now`/`Stop here` capture `elapsedSec` **before** mutating, because `finishFocus` nulls the session.

---

## 2. Data — models + Supabase tables

### 2.1 LiveSession (device-local, single row, JSON-encoded — **NOT a Supabase table**)

```kotlin
data class LiveSession(
  id: String?,                 // session uuid (reused as the Session row id at end)
  taskId: String,
  sessionStart: Long? = null,  // epoch MILLISECONDS
  paused: Boolean = false,
  pausedAt: Long? = null,      // epoch ms
  sessionEstimateMin: Int,     // FROZEN at session start; mutated by extend()
  nudge80Fired: Boolean = false,
  overrunPromptFired: Boolean = false,
  treatment: FocusTreatment,   // ambient | cockpit | monk
  priorAccumulatedSec: Int? = null,  // task.totalFocused carried in so the displayed timer continues
)
```

**iOS storage:** persist as a single JSON blob (GRDB single-row table, or a `Codable` file in the JSON store the iOS app uses). It is **device-local only** — it does not sync to Supabase and has no server row. Observe it as a publisher so the UI, the Live Activity, and the check-in scheduler all react to the same source. Mirror the LWW/whole-blob set semantics: `setLiveSession(nil)` clears; `setLiveSession(x)` overwrites the whole row.

### 2.2 Tables that DO sync (written at session end / on capture / on reason)

**`sessions`** (written on Done / End / Stop / task-switch finalize):
| col | type | from |
|---|---|---|
| `id` (uuid PK) | text | `live.id ?: newUuid()` — **reuse the live id** so captures FK-join |
| `task_id` | uuid? | |
| `task_name` | text | |
| `tags` | text[]? | (empty default) |
| `estimate_min` | int? | `live.sessionEstimateMin` (or `task.estimateMin` in viewmodel paths) |
| `actual_sec` | int | `FocusTimer.elapsedSec(live, now)` — **this session only**, excludes prior |
| `completed_at` | timestamptz (ISO-8601 string) | now |

**`captures`**:
| col | type | from |
|---|---|---|
| `id` (uuid PK) | text | new uuid |
| `task_id` | uuid? | |
| `session_id` | uuid? | the live session id (so it joins the Session row) |
| `tag` | enum text | `follow-up`/`idea`/`edit`/`question`/`distraction` |
| `body` | text | trimmed |
| `at` | timestamptz | now |

**`reason_logs`** (logged from Pause-Reasons):
| col | type | from |
|---|---|---|
| `id` (uuid PK) | text | new uuid |
| `task_id` | uuid? | |
| `reason` | text | one of the 5 reason strings |
| `action` | enum text | `pause` (Focus only uses PAUSE) / `switch` |
| `at` | timestamptz | now |
| `duration_sec` | int? | nullable — **omit from payload when null** (see §4) |

> When the task is marked done from Focus, `finishFocus` also bumps `tasks.total_focused += elapsed` and sets `done=true` via the same completion path as a normal toggle, and if the task came from a shared collection (`sourceCollectionId`/`sourceItemId` set), it fires the shared "task done" notify to members. Re-use the existing shared-task completion code path; do not duplicate it.

### 2.3 Settings consumed by Focus (local prefs, `SettingsState`)

| field | default | meaning |
|---|---|---|
| `focusDefaultMin` | 25 | default estimate |
| `focusOverrunMin` | 5 | minutes past estimate before OVERRUN; **0 = Never (∞)** |
| `focusSoftExit` | true | confirm before leaving a running session |
| `focusPauseReasons` | true | show Pause-Reasons after pausing |
| `ambient` | `"off"` | `off`/`brown`/`pink` — plays the loop when ≠ off |
| `treatment` | `AMBIENT` | last-used treatment, restored into new sessions |
| `notificationLevel` | `BALANCED` | gates the paused check-in (`pausedCheckin` is false for `CALM`) |
| `soundStartChime`/`soundOverrunBell`/`soundCompletion` | true/true/false | **declared but not yet wired to audio assets** — keep the toggles, no-op until assets exist |

---

## 3. Business rules / pure logic (`core/logic/FocusTimer.kt`) — port verbatim

This is a pure, deterministic, `now`-injected state machine. **Port it as a Swift `enum`/`struct` with static functions, with no SwiftUI/Foundation timer dependencies, and back it with an XCTest port of all 22 `FocusTimerTest` cases.**

### 3.1 Constants / defaults
- `empty`: `sessionEstimateMin = 25`, `treatment = .ambient`, everything else null/false/0.
- `estimateSec(live) = (sessionEstimateMin != 0 ? sessionEstimateMin : 25) * 60`.

### 3.2 Elapsed
```
elapsedSec(live, now):
  start = live.sessionStart ?? return 0
  elapsedMs = (live.paused && live.pausedAt != nil) ? (pausedAt - start) : (now - start)
  return max(0, Int(elapsedMs / 1000))

displayedElapsedSec(live, now) = elapsedSec(live, now) + (live.priorAccumulatedSec ?? 0)
```
- `elapsedSec` = THIS session only → written to `actual_sec`.
- `displayedElapsedSec` = what the UI shows (this session + prior task progress).
- **Paused time does not advance** (uses `pausedAt`, not `now`). Resume re-bases `sessionStart` so the gap is excluded (§3.4).

### 3.3 deriveState
```
deriveState(live, now, overrunGraceSec: Double):
  if sessionStart == nil -> IDLE
  if paused -> PAUSE
  if overrunGraceSec == +∞ -> RUNNING
  if displayedElapsedSec(live, now) >= estimateSec(live) + overrunGraceSec -> OVERRUN
  else RUNNING
```
- Overrun is computed on **displayed** elapsed (incl. prior), not raw — test `overrunFiresOnDisplayedNotRawElapsed`.
- Grace from the Int setting in the UI: `focusOverrunMin <= 0 ? +∞ : focusOverrunMin * 60`.
- There's also a legacy `overrunGraceSeconds(pref: String?)` mapping (`null/""→1`, `"Never"→∞`, `"5 min"→300`, `"10 min"→600`). The Android UI uses the Int path; port the Int path as primary, keep the string helper only if you also port the string pref (you don't need to).

### 3.4 Transitions (pure: current → next)

```
start(cur, taskId, estimateMin?, priorAccumulatedSec?, now, newId):
  if cur.sessionStart != nil && cur.taskId == taskId && cur.paused -> resume(cur, now)   // resume in place
  if cur.sessionStart != nil && cur.taskId == taskId && !cur.paused -> cur               // no-op (double Start)
  else -> fresh: id=newId(), taskId, sessionStart=now, paused=false, pausedAt=nil,
          sessionEstimateMin = estimateMin ?? 25, nudge80Fired=false, overrunPromptFired=false,
          priorAccumulatedSec = priorAccumulatedSec ?? 0

pause(cur, now):  if sessionStart==nil return cur; else paused=true, pausedAt=now
resume(cur, now): start=sessionStart ?? return cur; gap = pausedAt!=nil ? now-pausedAt : 0;
                  paused=false, pausedAt=nil, sessionStart = start + gap   // re-base past the pause
done(cur):        id=nil, sessionStart=nil, paused=false, pausedAt=nil     // elapsed resets to 0
cancel(cur):      empty.copy(treatment = cur.treatment)
extend(cur, min): sessionEstimateMin += min, overrunPromptFired=false
setTreatment(cur,t): treatment=t
```

**Key invariants (each is a test — replicate):**
- Start on same *paused* task resumes without resetting elapsed, and **ignores** a passed `priorAccumulatedSec` (resume keeps the existing one) — `startOnSamePausedTaskIgnoresPrior`.
- Start on same *running* task is a no-op (same `sessionStart`).
- Start on a *different* task starts fresh (elapsed ≤ 1s).
- Pause→Resume preserves `priorAccumulatedSec`; elapsed frozen during pause, resumes from the same value.
- `done()` clears `sessionStart` so elapsed → 0; the Session-row writeback must have already happened with the pre-done elapsed.
- `extend` clears `overrunPromptFired` so the prompt can re-fire after the new estimate is passed.

### 3.5 formatMMSS
```
formatMMSS(sec): sign = sec<0 ? "-" : ""; a=abs(sec); "%@%02d:%02d" (sign, a/60, a%60)
```
Tests: `0→"00:00"`, `65→"01:05"`, `3600→"60:00"`, `-65→"-01:05"`.

### 3.6 ViewModel-level rules (`AppViewModel`, port into the iOS focus store/VM)
- **`startFocus(task)`**: if current live `taskId == task.id` → return (keep state). If a *different* live session is running, finalize it (write Session + `total_focused +=`) before starting. Then `FocusTimer.start(base, task.id, estimateMin=task.estimateMin, priorAccumulatedSec=task.totalFocused, now)` and apply the saved `settings.treatment`.
- **`finishFocus(task, markDone)`**: read live, `elapsed = elapsedSec(live, now)`, upsert Session (reuse `live.id`), bump `task.total_focused += elapsed`; if `markDone` apply completion (`done=true`) + shared-collection notify; clear live session; fire session-recap (away=false in-app); set a `lastRecap` card state (taskName, focusedSec, at).
- **`extendFocus(min)` / `pauseFocus` / `resumeFocus` / `setTreatment`**: thin wrappers over the pure transitions, persisting the new live session. `setTreatment` also writes `settings.treatment`.

---

## 4. Gotchas (must-handle)

1. **kotlinx default-omission ⇒ Swift `Codable` parity.** Android's wire codec omits default-valued fields. The known land-mine here is `reason_logs.duration_sec`: it is **explicitly stripped from the upsert payload when null** so an upsert never clobbers a server value. In Swift, encode `reason_logs` so that a `nil` `duration_sec` is **omitted**, not sent as JSON `null`. For the local LiveSession JSON blob, be tolerant on decode (missing fields → defaults) since the schema may evolve.
2. **Epoch milliseconds, not seconds, not Dates.** `sessionStart`/`pausedAt` are epoch **ms** (`Int64`). All elapsed math is integer ms→sec. Inject `now` as `Int64(Date().timeIntervalSince1970 * 1000)`. Do **not** use `Date` arithmetic inside the pure logic.
3. **UTC ISO-8601 for synced timestamps.** `completed_at`/`at` are ISO-8601 strings (Android uses `Instant.now().toString()`, i.e. UTC `Z`). Use an `ISO8601DateFormatter` with fractional seconds + UTC. `total_focused` accumulation and `updated_at` likewise.
4. **LWW everywhere on synced rows.** Sessions/captures/reason_logs are append-style upserts keyed by uuid with an `updatedAt`/timestamp tiebreak. Re-use the existing iOS outbox/LWW path; don't invent a new write path for Focus. Reuse the **live-session id** as the Session id (so captures' `session_id` FK resolves).
5. **Capture→Session FK ordering.** A capture is written *during* the session (with a `session_id`) but the `sessions` row is only written *at session end*. Android enqueues the capture with `dependsOn = session_id` so the outbox flushes the session first; otherwise the capture hits an FK violation and gets poison-dropped. The iOS outbox must support the same dependency/ordering (capture depends on its session), and orphan-drop the capture if the session is abandoned.
6. **Exact-alarm / background denial.** The paused check-in (§5.4) and the "live" timer are best-effort on iOS. Unlike Android's `WorkManager` one-shot, iOS background execution is not guaranteed — schedule via `UNUserNotificationCenter` time-interval trigger (fires even if the app is suspended) and **re-validate state when it would fire / when the app foregrounds** (only notify if still paused). Gate on notification authorization; if denied, the check-in simply doesn't post (parity with Android's WorkManager-skipped case).
7. **Dependency ordering on init.** The focus store, settings store, outbox/write coordinator, and the Live-Activity/notification surfaces must be wired before the Focus screen mutates. Android routes shade-action writes through a shared `FocusCommands` so UI and background act identically — mirror this: a single `FocusCommands`-equivalent the SwiftUI view *and* the Live-Activity/notification handlers both call, never two divergent write paths.
8. **Frozen estimate.** Use `live.sessionEstimateMin` for ring/overrun, never the (possibly-edited) `task.estimateMin`.
9. **Notification level gating.** Don't schedule the paused check-in when `notificationLevel == CALM` (`.pausedCheckin == false`).

---

## 5. iOS equivalents (mapping table + behavioral notes)

| Android | iOS | Notes |
|---|---|---|
| Jetpack Compose `FocusScreen` | SwiftUI `FocusView` | Full-screen, dark radial bg, `Canvas`-drawn progress ring → SwiftUI `Canvas`/`Path` with `trim`. OKLCH colors via a port of the app's `oklch()` helper. |
| Compose `Canvas` arc | SwiftUI `Circle().trim(0, progress).stroke(...)` or `Canvas` | -90° start = trim from top; round line cap. |
| Room single-row `live_session` JSON entity | **GRDB single-row table** or the JSON store the app already uses | Device-local; observe via Combine/`AsyncSequence`. Do **not** sync. |
| `ModalBottomSheet` (Capture) | `.sheet` with `.presentationDetents([.medium])` / a custom bottom sheet | Single-line `TextField`, tag chips, Save. |
| `AlertDialog` (soft-exit) | `.alert("Leave focus?")` | Leave / Stay. |
| Full-screen scrim overlays (Pause-Reasons, Reflect) | `.fullScreenCover` or `ZStack` overlay | Tap-out dismiss; ephemeral. |
| **`FocusTimerService` foreground service** + ongoing notification | **ActivityKit Live Activity** (preferred) + `UNNotificationRequest` fallback | iOS has no foreground service. A Live Activity gives the always-visible "FOCUSING · LIVE" surface on Lock Screen / Dynamic Island. Running state shows a ticking timer (use `Text(timerInterval:)` so it counts without a running process). Paused state flips to amber "CHECK-IN · Did you step away?" with Resume/Snooze/End. The chronometer base = `sessionStart` (re-based on resume — see §3.4 — so it never counts the pause gap). **Constraint:** there is no background process; the "live" timer is rendered by the system from the start date, and mutations (pause/resume/end) come from in-app actions or notification/Live-Activity action intents (App Intents). |
| `setUsesChronometer(true)` + `setWhen(startMs)` | `Text(timerInterval: startDate...future, countsDown: false)` in the Live Activity | Pass the **re-based** `sessionStart` on resume. |
| Notification actions Pause/Capture (running), Resume/Snooze/End (paused) | Live Activity / notification **App Intents** | Each calls the shared `FocusCommands` equivalent. Capture deep-links into the app and opens the Capture sheet (`autoCapture`/`OPEN_CAPTURE` flag → present `FocusView` with capture open). |
| `AmbientAudio` (`MediaPlayer`, looping wav @ 0.45 vol, USAGE_MEDIA) | `AVAudioPlayer` (loop, `volume=0.45`, category `.playback` with `.mixWithOthers`) | Idempotent start/stop; start when `settings.ambient != "off"` && focusing; stop on leave/dispose. Bundle the `ambient_focus` loop asset. |
| **`PausedCheckinScheduler`** (`WorkManager` one-shot, ~14 min, `enqueueUniqueWork(REPLACE)`) | `UNUserNotificationCenter` time-interval trigger (~14 min) with a fixed identifier (replace by re-add) | Arm on pause, cancel on resume/end/done, snooze = re-arm. On fire: re-check live is still paused; ask the server (`send-paused-checkin` edge fn) if allowed (daily cap + preference + self-mute, default false on failure); only then present the local "Did you step away?" notification. Gate on `notificationLevel.pausedCheckin`. |
| `FocusCommands` (process-level shared writes) | A shared `FocusCommands` actor/struct | Single implementation of pause/resume/end live-session writes, called by the view and by Live-Activity/notification intents. `end` writes the Session, accumulates focus time, clears live, and fires the away-recap. |
| `supabase-kt` (`functions.invoke("send-session-recap"|"send-paused-checkin")`) | **supabase-swift** `functions.invoke(...)` | Same edge-function names + bodies. `send-paused-checkin` returns `{ allowed: Bool }`; default `false` on error. `send-session-recap` takes `{ taskName, away }`; `away=true` from a shade/notification-driven end, `away=false` when finishing in-app. |
| FCM (push) | **APNs** | Server-driven recap/check-in pushes target APNs for the web→iOS path; the local check-in is a local notification, not push. |
| Glance widget | **WidgetKit** | Focus has no dedicated Glance widget in Android; the "live" surface is the foreground-service notification → maps to the **Live Activity**, not a home-screen widget. No WidgetKit work required for Focus specifically beyond the Live Activity. |
| `WorkManager` flush / outbox | existing iOS outbox (`BGTaskScheduler` for opportunistic flush) | Captures/sessions/reason_logs ride the normal outbox with the FK-dependency from §4.5. |

### 5.1 Treatments (must match)
- **AMBIENT:** progress ring + Orbit + task block + timer. Ambient audio plays if the setting is on.
- **COCKPIT:** no ring; task block + timer + **Captures rail** (last 3 captures for this task).
- **MONK:** no ring, **no task block** (name/action/estimate hidden), bigger top spacer (40 vs 20). Timer still shown. Treatment switcher still shown so the user can leave Monk.

### 5.2 Sounds
`soundStartChime`/`soundOverrunBell`/`soundCompletion` toggles exist but are **not wired to audio** in Android (assets pending). Re-create the toggles in settings; leave the playback unimplemented (or stub) until assets land — do not invent new sounds.

---

## 6. Test port checklist (XCTest, 1:1 from `FocusTimerTest.kt`)

Use a fixed `t0 = parseMillis("2026-05-21T10:00:00.000Z")` and inject `now`:
`formatMMSSPositive`, `formatMMSSNegative`, `idleByDefault`, `startTransitionsToRunning`, `pauseThenResume`, `cancelBackToIdle`, `extendAddsMinutes`, `setTreatmentPersists`, `startOnSamePausedTaskResumesWithoutResettingElapsed`, `startOnDifferentTaskStartsFresh`, `startOnSameRunningTaskIsNoOp`, `elapsedDoesNotAdvanceWhilePausedAndSurvivesResume`, `startWithPriorSeedsDisplayed`, `defaultsPriorToZero`, `pauseResumePreservesPrior`, `overrunFiresOnDisplayedNotRawElapsed`, `doneClearsSessionStartAndResetsElapsed`, `startOnSamePausedTaskIgnoresPrior`, `neverGraceMeansNeverOverrun`. All must pass before any UI is wired — the pure engine is the contract.

---

## 7. Acceptance criteria (behavioral)

1. Opening Focus on a task starts/continues per §3.4; re-opening the same task never resets; switching tasks finalizes the old session's Session row + focus time.
2. Pause freezes the timer and ring; Resume re-bases so paused time is excluded; the Live Activity reflects both states (coral/amber) with correct actions.
3. Overrun appears only after `estimate + grace` on **displayed** elapsed (grace from `focusOverrunMin`, 0=Never), timer turns coral, +10/In-the-zone(+15)/Stop-here work; extend re-arms the prompt.
4. Done writes a Session (reusing live id), bumps focus time, marks the task done (+ shared notify if applicable), shows the (ephemeral) Reflect sheet, then leaves; End-for-now does the same minus done; both fire the in-app recap (away=false).
5. Captures save with the live `session_id` and FK-flush after the session; Cockpit shows the last 3.
6. Pause-Reasons (when enabled) logs a `reason_logs` row with `action=pause`; Save-for-later pauses + exits.
7. Soft-exit confirm only blocks leaving a **running** session and only when enabled; leaving keeps the session live and the Live Activity up.
8. Ambient audio loops while focusing iff the setting ≠ off; stops on leave.
9. Paused check-in posts ~14 min after pause iff still paused, server-allowed, and `notificationLevel != CALM`; cancelled on resume/end/done.
10. `reason_logs.duration_sec` is omitted (not `null`) on the wire; all timestamps UTC ISO-8601; live session never syncs.