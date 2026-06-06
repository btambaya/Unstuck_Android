# Bug sweep — v0.4.23 (versionCode 36)

A multi-agent sweep across the whole Android app (one finder per surface, each
finding adversarially verified) surfaced 49 confirmed bugs (23 P1 + 26 P2). This
release fixes them. Grouped by the remediation batch; each entry is
`file — symptom → fix`.

## A — Sync / data integrity
- `SyncDecision.kt` — SIGNED_IN always wiped the local cache, even re-signing in as the **same** user (needless full re-hydrate / transient empty UI) → wipe only when `prevUserId != currentUserId`. (`SyncDecisionTest` updated.)
- `SyncCoordinator.kt` — outbox flush captured the uid once; a mid-drain sign-out could write under the wrong user → `flusher.flush(uid) { auth.currentUserId }` re-checks identity each op. Same guard applied to `completeGoogleConnect`'s flush.
- `OutboxFlusher.kt` — a permanently-failing row (poison pill) blocked the queue forever, and a failed op let *later* ops on the same row run (breaking per-row LWW) → `FAIL_CAP=5` drop + `blockedRows` skips a row's remaining ops after a failure.
- `SyncCoordinator.kt` — Google-connect didn't flush before hydrate → added flush.

## B — Calendar
- `DayGrid.kt` — overlapping blocks stacked on top of each other (only the last visible) → `layoutLanes()` greedy interval colouring lays them side-by-side; `laneWidthDp` divides the day column.
- `DayGrid.kt` — tap-to-create fired even when tapping the time gutter → `if (off.x < gutterPx) return`.
- `DayGrid.kt` — grid didn't roll over at midnight → midnight `LaunchedEffect`.
- `DayGrid.kt` — external (non-task) blocks were draggable/clickable like tasks → gated on `isTaskBlock(b)`.
- `CalendarScreen.kt` — busiest/lightest day shown even when all days equal (`maxPlanned == minPlanned`) → null in that case. Week view also lane-aware (measured column width).

## C — Tasks / scheduling
- `Recurrence.kt` + `Time.kt` — a day-31 monthly recurrence skipped short months (no Feb/Apr/Jun/Sep/Nov occurrence) → clamp to `minOf(startDay, daysInMonth(candidate))`, recovering to 31 in long months. (`Time.daysInMonth` added; `RecurrenceTest` covers non-leap + leap Feb.)
- `NewTaskSheet.kt` — Material date picker returns UTC-midnight, reinterpreted in local zone → off-by-one date → read back via `ZoneOffset.UTC`. Auto-pick slot effect + reminder-override only when scheduled.
- `AppViewModel.kt` — recurring `scheduleTask` moveCount bumped even with no anchor change → gated on anchor change.

## D — Focus
- `FocusScreen.kt` — used `task.estimateMin` not the session estimate; overrun grace `0` meant *infinite* grace → `sessionEstimateMin ?: estimateMin`, `graceSec = ∞` only when overrun disabled; monk-mode treatment chip always shown (escape hatch).
- `AppViewModel.startFocus` — resumed save-for-later session lost prior focus time → `priorAccumulatedSec = task.totalFocused`. `finishFocus` reuses the live id and fires the collection-done push.
- `FocusCommands.kt` / `NotificationActionReceiver.kt` — shade Resume left the ongoing notification's chronometer at the stale pre-pause start → `resume` re-arms the service at the post-resume start; receiver no longer double-sets it.

## E — Auth / notifications / deep-links
- `AuthScreen.kt` — create-account / magic-link / forgot-password returned `Ok` with no session → screen looked inert → `run(success)` shows a "check your email" message (calm colour); errors stay coral.
- `AuthService.kt` — sign-up with an already-registered email showed fake success (anti-enumeration) → `detectSignupAlreadyExists` (empty identities / confirmed-no-session) surfaces "account already exists".
- `MainActivity.kt` — collection-share push tap didn't deep-link → added `collections` to the host allow-list (consumed by MainScaffold).
- `ReminderReceiver.kt` — `MY_PACKAGE_REPLACED` (app update) fell through to fire a bogus "your task is starting" **and** dropped pending alarms → handled in the reschedule branch.
- `NotificationCenterScreen.kt` — only task rows were tappable (shared-list / recap / brief rows were dead) → any row with a deep link routes via `onDeepLink` → `vm.openDeepLink`.
- `Push.kt` — server pushes shared one notif id → newer overwrote older → derive a content-hash id (0x60000 base) so distinct pushes coexist, identical retries collapse.
- `MainScaffold.kt` / `FocusScreen.kt` — the notification "Capture" action landed on Focus with no input → `autoCapture` opens the capture sheet on entry.

## F — Collections / Today / Settings / Insights / chrome
- `CollectionDetailScreen.kt` — **Leave** ran on the screen's `rememberCoroutineScope`, cancelled by the immediate `onBack()` → `leaveCollection` now fire-and-forget on viewModelScope.
- `CollectionDetailScreen.kt` — a "by" time set earlier than now was instantly overdue → rolls to tomorrow. Unparseable `dueAt` rendered a dangling "… by " → guard on the parsed `dueMs`. Tapping a revealed row opened the editor → tap now dismisses the actions first. A realtime rename wiped an in-progress title edit → `remember` keyed on `col.id` not `col.name`.
- `TodayScreen.kt` — Backlog / empty area filter showed a blank list → per-view empty note. Live-session card showed only the post-resume slice → `displayedElapsedSec`. `now` captured once → minute ticker so the date/today filters roll over at midnight. Removed the fabricated "· Low friction" label.
- `SettingsScreen.kt` — Backup section was inert ("Auto-export every Sunday" + "Export now" both no-ops) → real on-demand JSON export. Change-password with no email reported a bogus "password incorrect" → clear message. Adding a duplicate-named area corrupted filtering, and `areas.size` reused a sortOrder/colour after a delete → duplicate guard + `max+1` order + first-unused colour (same for tags).
- `InsightsScreen.kt` — "Captures by kind" drew 5 zero bars with no captures (the breakdown map always has 5 keys) → guard on `captures.isNotEmpty()`. "Re-entries" card showed the session count, overstating re-entries → relabelled "Focus sessions".
- `AvatarMenu.kt` — hardcoded `y = 64dp` overlapped the avatar on cutout / large-status-bar devices → add the measured `statusBars` inset.
- `Chrome.kt` — `BottomNavBar` indexed `items[0..3]`, IOOBE for <4 items → split around the FAB gap by computed midpoint.
- `AppViewModel.deleteCapture` — left the deleted id in the device-local archived set (slow leak) → `unarchiveCapture(id)` on delete.

## Verification
- `:app:compileDebugKotlin` + `:app:assembleRelease` — BUILD SUCCESSFUL.
- `:core:test` green (incl. new monthly-clamp + updated sync-decision tests).
- Release APK aapt2: `versionCode=36 versionName=0.4.23`; apksigner: signature verified.
- Distributed to the two test accounts only (no beta group).
