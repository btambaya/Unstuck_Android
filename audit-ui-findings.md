# Android UI audit — mockup + web fidelity (auto, verified)

## HIGH (37)

### [App shell / bottom nav / FAB / overlay routing] No system back handling for overlays, sheets, or focus screen
- **kind:** bug
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/MainScaffold.kt:98-135
- **detail:** Re-verified: zero BackHandler usage anywhere in app/ or design/ (grep returns nothing). MainScaffold.kt holds overlay state in `stack` (line 61), `showNewTask` (63), `focusTask` (64), and `sheet` (62). Full-screen overlay routes are pushed onto `stack` and rendered at lines 98-119; the focus screen renders at 132-135; NewTaskSheet at 122. The custom `stack`/Box overlay model is NOT navigation-component-based, so the Android system/gesture back button has nothing wired to it — pressing back while a Detail/Collection/Insights/Settings/Palette overlay or the FocusScreen is open exits the app instead of popping. (NewTaskSheet/AvatarMenu use ModalBottomSheet which self-handles its own dismiss/back, so those two are less severe, but the stack overlays and FocusScreen are full-screen custom layers with no back handling.) This is the biggest behavior gap in the shell.
- **fix:** Add layered BackHandler calls in MainScaffold (top layer wins): `BackHandler(enabled = focusTask != null) { focusTask = null }`, then `BackHandler(enabled = focusTask == null && stack.isNotEmpty()) { pop() }`. (showNewTask/sheet ride on ModalBottomSheet which already intercepts back, so a dedicated handler for them is optional.) Optionally add a final `BackHandler(enabled = focusTask == null && stack.isEmpty() && tab != "today") { tab = "today" }` to return to the Today root tab before allowing app exit.

### [App shell / bottom nav / FAB / overlay routing] Edge-to-edge enabled but no status-bar/nav-bar inset padding — content and bottom nav collide with system bars
- **kind:** bug
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/MainScaffold.kt:77-95
- **detail:** Re-verified: MainActivity.kt:30 calls enableEdgeToEdge(). There is NO statusBarsPadding / navigationBarsPadding / systemBarsPadding / WindowInsets / safeDrawing anywhere in app/ or design/. (The only inset usage is imePadding() in AuthScreen, NewTaskSheet, and CaptureSheet — so the original finding's claim of 'no imePadding anywhere' is wrong, but the structural status/nav-bar inset gap is real.) MainScaffold's root Box+Column fill the whole screen (MainScaffold.kt:77-78) with no inset, and TodayScreen's first content only pads top=14.dp (TodayScreen.kt:86), so screen content draws under the status bar. BottomNavBar (Chrome.kt:88-99) uses padding(top=8, bottom=6) with no inset, so on gesture-nav devices the nav row/labels render behind the navigation pill. Mockup reserves this space: BottomNav paddingBottom:20 (android-screens.jsx:149) and a 40px AndroidStatusBar strip + 24px gesture nav bar (android-frame.jsx).
- **fix:** Apply window insets: add Modifier.statusBarsPadding() to the content Column (line 78) so screen content clears the status bar, and add Modifier.navigationBarsPadding() to the BottomNavBar (pass via its `modifier` param at line 94, which it already forwards to its root Box at Chrome.kt:87). imePadding is already applied in the text-entry sheets, so no change needed there.

### [Today / dashboard (+ empty state)] Completed-today tasks never appear in the Today list
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:78
- **detail:** Verified. TodayScreen.kt:78 feeds the list from visibleTasks(TaskListView.TODAY, ...). VisibleTasks.kt:71-75 hard-filters `!t.done` in the TODAY branch, so a task completed today is dropped entirely. Web today-list.tsx:153 keeps the task when `isCompletedToday(t, now)` and sorts done last (line 162); task-row StandardTaskRow renders done rows with a green check + line-through. isCompletedToday already exists in Kotlin (TaskBucket.kt:10), so the helper is available. The dashboard never surfaces today's completions, unlike web.
- **fix:** Build todayAll as `visibleTasks(TODAY,...) + tasks.filter { isCompletedToday(it, now) && it.id !in todayIds }`, keep done sorted last, and render done rows with a green check + strikethrough name. The reusable Common.kt TaskRow already does the strikethrough/CheckCircle, but TodayScreen uses its own private TaskRow (line 174) which would need the done styling added.

### [Today / dashboard (+ empty state)] PausedCard has no progress ring (flat amber circle instead)
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:160
- **detail:** Verified. PausedCard at TodayScreen.kt:160-162 draws a plain Box clipped to CircleShape filled with c.amberSoft, with the MM:SS text on top — no track, no arc. The android mockup PausedRing (android-screens.jsx:358-376) is a 30px SVG: grey track circle (stroke A_LINE, width 3) + amber arc (A_AMBER) whose strokeDashoffset = 94*(1-progress), rotated -90deg, MM:SS centered. Web RichRow (task-row.tsx:189-201) renders the identical ring.
- **fix:** Replace the filled Box with a 30dp Canvas: drawCircle stroke c.line width 3, then drawArc c.amber startAngle -90 sweep 360*min(1, elapsedSec/estimateSec), with the MM:SS label centered. FocusTimer.elapsedSec(live, now) (FocusTimer.kt:30) gives live elapsed; estimate = task.estimateMin*60.

### [Today / dashboard (+ empty state)] Live running session is mislabeled as paused on Today
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:122
- **detail:** Verified. LiveSession has a `paused: Boolean` field (Models.kt:197) and FocusTimer exposes pause/resume/elapsedSec. TodayScreen.kt:122-123 renders PausedCard whenever `live != null`, and PausedCard (lines 154-170) always shows `Paused · {name}`, a single Resume button, and amber styling, never branching on live.paused. So a running session shown on Today is labeled Paused. Web LiveTaskRow (task-row.tsx:79-103) branches: running → coral ring, `In focus · {name}`, `running for MM:SS`, Pause + Return-to-focus; paused → amber ring, `Paused · {name}`, Resume. Note the Android mockup only depicts the paused variant (android-screens.jsx:325-343), so this is a behavioral parity gap vs web rather than a mockup-visual gap; severity high is still warranted because the running label is wrong.
- **fix:** Branch on `live!!.paused`: running → coral ring, `In focus · {name}`, `running for MM:SS`, Pause + Return-to-focus actions; paused → amber ring, `Paused · {name}`, Resume. Compute displayed elapsed via FocusTimer.elapsedSec(live, vm.nowMs()) rather than only priorAccumulatedSec.

### [Task detail (full screen)] No editable fields — headline, first action, estimate, area are all read-only
- **kind:** missing
- **file:** TaskDetailSheet.kt:62,68,87,88
- **detail:** Web makes title (EditableHeadline, task-detail-pane.tsx:128), first physical action (EditableFirstAction:133), estimate (EditableEstimate:166) and area (EditableArea:175) click-to-edit inline, committing via upsert. Kotlin renders every one as static Text: title (TaskDetailSheet.kt:62), first-action (line 68), estimate MetaCell (line 87), area MetaCell (line 88). No way to rename, set first physical action, change estimate, or reassign area from the Android detail screen. Confirmed vm.updateTask(task) exists (AppViewModel.kt:108) so the plumbing is there.
- **fix:** Make the headline, first-physical-action card, estimate and area tappable to open an inline editor (or small edit sheet) that calls vm.updateTask(task.copy(...)). At minimum support renaming and editing firstPhysicalAction, matching the web click-to-edit.

### [Task detail (full screen)] Repeat / recurrence field and editor entirely absent
- **kind:** missing
- **file:** TaskDetailSheet.kt:84-95
- **detail:** Web MetaGrid includes a 'Repeat' row (task-detail-pane.tsx:174) plus an 'Add repeat'/'Edit repeat' button (line 196) that toggles a full RepeatEditor and regenerates cal_blocks. The Kotlin meta grid has only Estimate/Area/Schedule/Status (TaskDetailSheet.kt:84-95) — no Repeat field, no Add-repeat affordance, no recurrence editing. TaskItem.recurrence exists in the model (Models.kt:95) and is never surfaced here. NOTE: a reusable RecurrenceEditor composable already exists (ui/components/RecurrenceEditor.kt) and recurrenceLabel() exists (core/logic/Recurrence.kt:105) and vm.scheduleTask already materializes recurrence occurrences (AppViewModel.kt:124-136) — so the fix can reuse existing parts; RecurrenceEditor currently omits the 'until' field.
- **fix:** Add a 'Repeat' meta cell showing recurrenceLabel(task.recurrence) or '—', and an 'Add repeat'/'Edit repeat' control that opens the existing RecurrenceEditor, writes task.recurrence via vm.updateTask, and regenerates blocks (vm.scheduleTask already handles recurrence materialization).

### [Task detail (full screen)] Capture rows lack tag pill, timestamp, and Promote/Discard actions
- **kind:** missing
- **file:** TaskDetailSheet.kt:103-108
- **detail:** Both the android mockup (android-screens.jsx:518-534) and web (task-detail-pane.tsx:293-356) render each capture as a surface card with a colored tag pill (mono uppercase, e.g. 'IDEA' on amber-soft), a relative timestamp ('today'), the body, and Promote/Discard buttons (web only — the mockup shows just pill+time+body, no buttons). The Kotlin renders captures as bare bullet text '• ${cap.body}' (TaskDetailSheet.kt:105-107) — no card, no colored tag pill, no timestamp, no Promote/Discard. Theme tokens (amberSoft/coralSoft/etc.) exist; vm.saveCapture and vm.addTask exist for Promote, but there is NO removeCapture method on the VM yet (only upsertCapture/saveCapture in AppViewModel) so Discard needs a new VM method.
- **fix:** Render each capture as a surface Card (radius 14, 1px line ring) with a tag pill (CaptureTag color tokens) + relative time + body. Add 'Promote to task →' (vm.addTask seeded from body + remove capture) and 'Discard' — note a removeCapture VM method must be added since none exists today.

### [New-task sheet (+ WHEN / recurrence / first step)] Free-slot lookup passes an empty block list — scheduling ignores existing calendar and can double-book
- **kind:** bug
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:94
- **detail:** On submit for Today/Tomorrow, NewTaskSheet.kt:94 calls findFreeSlotsForDate(emptyList(), estimate, date, vm.nowMs(), limit = 1). It passes emptyList() instead of the user's real cal blocks, which are exposed as `val blocks = sf(store.blocks())` at AppViewModel.kt:53. I re-read the core function (core/.../logic/FreeSlots.kt:102-119 → findFreeSlots:53-99): with an empty block list the gap-scan finds the whole 08:00–18:00 window free, so for a fresh day it returns the 08:00 slot, and for today it returns max(08:00, now rounded up). Because the user's actual blocks are never passed, the suggested slot can land directly on top of an existing block. The web modal feeds the live `blocks` into the same finder (task-create-modal.tsx:91 `const { items: blocks } = useCalBlocks()`, :139-143 `findFreeSlotsForDate(blocks, ...)`), so its suggestions avoid conflicts. Note: the original detail said the sheet 'always returns the first 08:00 slot regardless' — that is not exactly true for today (the finder does respect `now`), but the substantive bug (real blocks ignored → possible double-book) is real.
- **fix:** Collect blocks in the composable (`val blocks by vm.blocks.collectAsStateWithLifecycle()`) and pass them: findFreeSlotsForDate(blocks, estimate, date, vm.nowMs(), limit = 1).

### [New-task sheet (+ WHEN / recurrence / first step)] WHEN is missing 'Pick date', the free-slot/time-slot chips, the manual time input, and the conflict warning
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:56-59
- **detail:** NewTaskSheet.kt:56-59 renders only listOf("Today", "Tomorrow", "Later") with nothing else. The web WHEN section (task-create-modal.tsx:474-588) renders four buttons (Today / Tomorrow / Pick date / Later), a date picker shown when 'date' is chosen (:511-523), a row of free-slot suggestion pills built from findFreeSlotsForDate(blocks,...) (:526-585), a type=time 'Or any time' manual input with a formatted preview (:560-570), and a ConflictPill driven by findConflicts (:573-574). The mockup (task-create-modal.jsx:7-8, 224-265) shows the same four WHEN_OPTS plus TIME_OPTS chips and an 'Or any time: HH:MM' input. Android users cannot pick an arbitrary date, cannot see/choose among free slots, cannot set a specific time, and get no conflict warning. The fix's named helpers exist in Kotlin: findFreeSlotsForDate and findConflicts are both defined in core/.../logic/FreeSlots.kt (:102 and :123).
- **fix:** Add a 'Pick date' chip that opens a date picker, render free-slot chips from findFreeSlotsForDate(blocks,...) as selectable pills, add a manual time picker ('Or any time'), and show a conflict pill (findConflicts in core/logic/FreeSlots.kt:123) when the chosen time overlaps an existing block.

### [New-task sheet (+ WHEN / recurrence / first step)] 'Capture a thought' section is entirely absent from the create sheet
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:78-81
- **detail:** NewTaskSheet.kt has no capture UI anywhere between the first-step field and the Add button (lines 78-98 go SectionLabel/OutlinedTextField → RecurrenceEditor → UButton). The mockup (task-create-modal.jsx:283-411) and web (task-create-modal.tsx:722-832) both show a capture stack: a dashed pill toggling between '+ Capture a thought (optional)' and '+ Add another capture', each card on bg-2 with a 'CAPTURE n' brain-icon eyebrow, a textarea, and the five tag chips (follow-up/idea/edit/question/distraction). Web persists non-empty drafts on submit via upsertCaptures (jsx:70-73 addCapture; tsx imports useCaptures at :22). The backend support exists on Android: AppViewModel.kt:186 already has `fun saveCapture(taskId, sessionId, tag, body)` and the Capture/CaptureTag models are imported (AppViewModel.kt:20-21), so this is purely missing sheet UI + a save loop.
- **fix:** Add a capture draft list + dashed add-pill matching the mockup (CAPTURE_TAGS chips, brain icon, bg-2 cards) and persist each non-empty draft on submit via vm.saveCapture(taskId = t, sessionId = null, tag, body).

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] 'Done' never marks the task complete — task.done stays false forever
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/AppViewModel.kt:167-175
- **detail:** CONFIRMED against BOTH the mockup and web. The mockup focus.jsx handleDone (focus.jsx:110-123) sets the task to {done:true, completedAt: new Date().toISOString(), totalFocused: elapsed} then endFocus()+onExit(). The web focus-mode.tsx:135-161 likewise has onMarkComplete setting done:true+completedAt. In Android the only finish path is finishFocus(task) (AppViewModel.kt:167-175): it upserts a Session and upserts task.copy(totalFocused = totalFocused + elapsed) but NEVER sets done=true or completedAt. FocusScreen.kt:137 calls vm.finishFocus then shows ReflectSheet and exits. TaskItem has both done (Models.kt:83) and completedAt (Models.kt:93). So a user can never complete a task from focus mode — it stays open. This contradicts the mockup directly. Real, user-visible bug.
- **fix:** Mirror the mockup: in finishFocus (the 'Done' path) also upsert task.copy(done=true, completedAt=isoNow(), totalFocused=totalFocused+elapsed). If you want a separate 'End for now' pending path later, add it then, but the mockup's single 'Done' button marks complete.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] '← Out' discards the live session (data loss) instead of leaving the timer running
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:88
- **detail:** CONFIRMED against the mockup. focus.jsx's '← Out' button (focus.jsx:135-145) calls only onExit — it does NOT clear live, so the session survives and the user can return; the web focus-mode.tsx:64-68 keeps the timer running and only confirms when actively running. Android FocusScreen.kt:88 wires the '← Out' pill to vm.cancelFocus() THEN onClose(), and cancelFocus (AppViewModel.kt:177) does store.setLiveSession(null), destroying all elapsed progress with no confirmation. This is a real data-loss divergence from the mockup's soft-exit model.
- **fix:** Make '← Out' call onClose() WITHOUT cancelFocus() so the live session persists, matching focus.jsx. Reserve cancelFocus for an explicit discard (optionally behind the web's confirm copy).

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] In-screen treatment-picker chips clutter the focus body — absent from the mockup
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:97-104
- **detail:** CONFIRMED. FocusScreen.kt:97-104 renders a Row of SelectableChip(ambient/cockpit/monk) directly under the eyebrow whenever treatment != MONK, calling vm.setTreatment(t). The reference mockup focus.jsx has NO treatment switcher anywhere in the focus body (the only switcher in the mockups is the onboarding step android-batch-b.jsx:493 and a Settings row). The web app uses a discreet TreatmentSwitcher overlay (focus-mode.tsx:212), not center-stage chips. Raising to high because three persistent chips in the middle of the breathing screen is a clearly wrong, prominent visual that contradicts the minimal mockup.
- **fix:** Remove the inline chip row from the focus body. Move treatment switching to Settings/onboarding (as the mockups do) or a discreet corner overlay.

### [Capture sheet + Reflection dialog] Reflection 'Carry forward' input + capture-on-save behavior is absent
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/ReflectSheet.kt:32,52
- **detail:** reflection.jsx lines 107-124 render a 'CARRY FORWARD · OPTIONAL' SectionLabel plus a free-text input, and save() (lines 26-32) writes a follow-up Capture from that text via addCapture({ taskId, sessionId:null, tag:'follow-up', body }). ReflectSheet.kt (signature line 32: ReflectSheet(elapsedSec: Int, onDismiss)) has no carry-forward label, no input, and its Save button (line 52) just calls onDismiss — it never persists anything. It cannot create a capture because the composable is never given the task or vm (FocusScreen.kt:142 calls `ReflectSheet(reflectElapsed) { showReflect = false; onClose() }`, passing only reflectElapsed). The single most important reflection behavior is missing.
- **fix:** Change the signature to ReflectSheet(task: TaskItem, elapsedSec: Int, vm: AppViewModel, onDismiss). Add a SectionLabel('CARRY FORWARD · OPTIONAL') + a BasicTextField bound to a carryForward state with placeholder 'One small thing you want to remember…' and a bottom hairline. In Save's onClick, if carryForward.isNotBlank() call vm.saveCapture(task.id, null, CaptureTag.FOLLOW_UP, carryForward.trim()) then onDismiss. Verified: saveCapture(taskId: String?, sessionId: String?, tag: CaptureTag, body: String) exists at AppViewModel.kt:186 and CaptureTag.FOLLOW_UP exists (Enums.kt:50). Update FocusScreen.kt:142 to pass task and vm.

### [Capture sheet + Reflection dialog] Capture tag chips ignore per-tag colors — every selected chip is indigo
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/CaptureSheet.kt:55-58
- **detail:** focus.jsx lines 282-288 give each tag its own active palette: follow-up=primary-soft/primary-deep, idea=amber-soft/oklch(0.45 0.13 75), edit=blue-soft/oklch(0.40 0.10 240), question=green-soft/oklch(0.40 0.10 155), distraction=coral-soft/oklch(0.45 0.16 35). CaptureSheet.kt:57-58 hardcodes c.primarySoft / c.primaryDeep for ALL selected chips, so selecting 'idea', 'edit', 'question' or 'distraction' wrongly shows an indigo chip.
- **fix:** Extend the TAGS list to carry per-tag (bg, fg) colors. Verified against Theme.kt tokens, the correct mapping is: follow-up=(primarySoft, primaryDeep); idea=(amberSoft, amberInk) — amberInk is exactly oklch(0.45,0.13,75); edit=(blueSoft, blueInk); question=(greenSoft, greenInk); distraction=(coralSoft, coralDeep). NOTE the original fix said map fg to amber/blue/green/coral base hues — that is wrong; the mockup fg values are the dark Ink/Deep variants, not the light base hues. Use the *Soft background + *Ink/coralDeep foreground when sel is true instead of the hardcoded primarySoft/primaryDeep.

### [Calendar Day/Week/Month + drag-to-schedule] Week view is a plain text list, not the 7-column grid
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/CalendarScreen.kt:60-80
- **detail:** Confirmed. CalendarScreen.kt WeekView (lines 60-80) renders a vertical column of Card rows, one per day, each containing plain Text lines '${b.startTime} · ${b.taskName}' (line 74) or 'Open' (line 73). There is no 7-day column grid, no time axis, no absolutely-positioned blocks, no drag. Mockup calendar-week.jsx WeekGrid (lines 109-246) uses gridTemplateColumns '52px repeat(7,1fr)', dashed column borders (line 210), hour ticks, per-day headers with today highlight (line 166), and absolutely-positioned blocks (topFor/heightFor lines 136-137). Web week-full.tsx confirms the same grid.
- **fix:** Replace the day-list with a 7-column grid (or horizontally scrollable week) reusing the DayGrid hour-column layout, positioning blocks by start time / height by duration as in WeekGrid / week-full.tsx.

### [Calendar Day/Week/Month + drag-to-schedule] Week sidebar rollup stats absent (Focus planned / Overload risk / Light day)
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/CalendarScreen.kt:66-67
- **detail:** Confirmed. WeekView only renders SectionLabel('Week') + serif 'Next 7 days' (CalendarScreen.kt:66-67); no stat cards or capacity math. Mockup WeekSidebar (calendar-week.jsx:34-103) shows a WEEK eyebrow + serif title plus three tonal RollupStat cards: 'Focus planned' (green), 'Overload risk' (amber/green), 'Light day' (blue). Web week-full.tsx computes these live (rollup useMemo lines 210-232: totalPlannedMin/totalUsableMin, loadPct, overload, lightDay) and renders them at lines 321-340.
- **fix:** Add a section with three tonal stat cards computing total planned vs usable load%, the overload day, and the lightest day, mirroring week-full.tsx rollup. Theme.kt confirms greenSoft/amberSoft/blueSoft exist (lines 59-61).

### [Calendar Day/Week/Month + drag-to-schedule] No 'NOW' coral line on the day timeline
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:113-148
- **detail:** Confirmed. DayGrid.kt has no now-indicator anywhere in the hour grid (lines 113-148); vm.nowMs() is referenced in CalendarScreen.kt but DayGridScreen never computes a now offset. Mockup calendar-today.jsx (lines 71-82) draws a 1.5px coral horizontal line at the current minute with a coral 'NOW' pill, and web today-timeline.tsx renders the same (nowTop state line 103, the NOW overlay lines 344-365). Note: the cited mockup line range 72-82 is off by one (the comment + div start at line 71), but the feature is real.
- **fix:** When date == Clock.todayIso(), compute topDp from current minutes-since-START_HOUR (using vm.nowMs()) and overlay a 1.5dp coral line with a 'NOW' pill, as today-timeline.tsx nowTop does.

### [Calendar Day/Week/Month + drag-to-schedule] Blocks ignore life-area color; everything is coralSoft
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:140
- **detail:** Confirmed. DayGrid.kt:140 uses 'background(if (isTaskBlock(b)) c.coralSoft else c.bg2)' — every task block is coral regardless of life area, and EXTERNAL/PLACEHOLDER kinds (CalBlockKind enum: TASK/PLACEHOLDER/EXTERNAL) are collapsed into the single else branch (c.bg2). Web colors each block by its task's life area via color-mix (today-timeline.tsx bgFor lines 82-85: AREA_COLOR[lifeArea] over surface; week-full.tsx line 81 same). Theme.kt exposes areaColor(token) (lines 36-45) and areaSwatch(color) (line 48) but DayGrid never calls them. Note: CalBlock has no lifeArea field, so the fix must resolve lifeArea via the linked task (taskId -> TaskItem.lifeArea), which exists (Models.kt line 90).
- **fix:** Resolve the block's task lifeArea (look up TaskItem by b.taskId), use c.areaSwatch(c.areaColor(lifeArea)) for TASK blocks, c.blueSoft for EXTERNAL, transparent + inset border for PLACEHOLDER, matching bgFor().

### [Calendar Day/Week/Month + drag-to-schedule] Month view lacks calendar grid header, month label, and nav
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/CalendarScreen.kt:83-117
- **detail:** Confirmed. MonthView (CalendarScreen.kt:83-117) renders a rolling 35-day heatmap: 'cells = (0..34).map { ... start = today-34 }' (lines 90-91), no weekday header row, serif title 'Focus density' instead of a month/year label (line 95), and no prev/next/This-month navigation. Mockup calendar-month.jsx MonthGrid (lines 52-143) shows a serif 'May 2026' title, prev/'This month'/next nav (lines 93-102), a Mon-Sun weekday header row (lines 108-113), and a 6x7 grid aligned to the month's first weekday with today in coral. Web month-mini.tsx confirms Monday-anchored offset = (first.getDay()+6)%7.
- **fix:** Add a Mon-Sun weekday header row, a serif month/year title, prev/This-month/next nav, and anchor the grid to the month's first weekday (Monday-anchored offset (weekday+6)%7) as month-mini.tsx makeCells does.

### [Calendar Day/Week/Month + drag-to-schedule] Month density normalization differs from web (local max vs usable-per-day)
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/CalendarScreen.kt:92
- **detail:** Confirmed. MonthView normalizes against the busiest day in the window: 'max = byDay.values.maxOrNull() ?: 1' (line 92) then 't = v.toFloat()/max' (line 102), so any single non-zero day saturates relative to the window rather than to an absolute capacity. Web month-mini.tsx normalizes each day against usable minutes per day: 'load = min(1, (byDay[day] ?? 0) / 60 / usableMinPerDay)' (verified line 38), an absolute focus-density meaning. Severity is high-on-the-edge but the visual difference (single light day appearing fully saturated) is user-visible; keeping high is defensible given the heatmap is the entire month view's content.
- **fix:** Normalize against usable minutes per day (weekday capacity), like month-mini.tsx, instead of the local max.

### [Collections grid + detail] No "New collection" entry point anywhere
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionsScreen.kt:36-75
- **detail:** Verified. CollectionsScreen.kt (lines 36-75) has only an AppBar with MENU leading + search icon, then a grid; there is no New-collection button and no NewCollectionSheet equivalent in the collections package. MainScaffold.kt:91 wires `CollectionsScreen(vm, onOpen = { push(Route.Collection(it)) }, onSearch = { push(Route.Palette) }, onMenu = { sheet = Sheet.Avatar })` — no create path. vm.upsertCollection exists (AppViewModel.kt:197), and the ItemCollection model (Models.kt:176) has the needed fields (id, name, color, subtitle?, items, sortOrder). Web collections/page.tsx:68 wires onNew -> NewCollectionSheet (name field 'What would you like to remember?' + 6-color picker indigo/coral/green/amber/blue/violet, Enter to create, then router.push to detail). A user genuinely cannot create a collection on Android.
- **fix:** Add a coral 'New collection' action (FAB or header button) that opens a sheet mirroring new-collection-sheet.tsx: name TextField (placeholder 'What would you like to remember?') + 6 color swatches, Create calls vm.upsertCollection(ItemCollection(id=newUuid(), name, color, items=emptyList(), sortOrder=collections.size)), then navigate to the new detail.

### [Collections grid + detail] Detail: no per-item pin / remove / inline-edit affordances
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:99-103
- **detail:** Verified. The shared ItemRow (design module Components.kt:182) has signature `fun ItemRow(body: String, done: Boolean, pinned: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit)` — only a 18dp coral checkbox + bullet + body text; no pin button, no remove button, no inline edit. CollectionDetailScreen.kt:99/103 calls `ItemRow(it.body, it.done == true, pinned = ...) { toggle(it) }`, passing only the done-toggle. Web collection-item-row.tsx renders a pin toggle (coral when pinned), an x remove, and click-to-edit-inline (Enter/blur commits, Escape reverts), wired via onTogglePin/onRemove/onCommitEdit. On Android you can mark done but cannot pin, delete, or rename items. CollectionItem (Models.kt:167) already carries pinned/done so the data path exists.
- **fix:** Build a collections-specific row (do not overload the shared ItemRow) taking onTogglePin/onRemove/onCommitEdit; add a coral-when-pinned pin icon and an x icon, and make the body tappable to enter an inline BasicTextField that commits on Done/blur and reverts on Escape. Wire to vm.upsertCollection updating the matching item's pinned/body, and removing it from items.

### [Collections grid + detail] Detail: no recolor, rename, or delete-collection controls
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:69-72
- **detail:** Verified. Web collection-detail.tsx exposes a Color SectionLabel + 6 swatches (lines 192-219), click-title-to-rename (lines 138-174), and a 'Delete collection' ghost button with inline confirm (lines 221-231), wired in page.tsx:60-62 to rename/update/remove. Android CollectionDetailScreen.kt:71 renders the title as a static `Text(col.name, style = UFont.serifItalic(26)...)` with no rename, there is no Color row, and no delete affordance — even though vm.deleteCollection exists (AppViewModel.kt:198) and vm.upsertCollection can recolor/rename. The finding's note is accurate: the mockup collections-detail.jsx itself omits these controls (web added them), so this rests on the 'behavior = web' rule, which is the case for the recolor/rename/delete affordances.
- **fix:** Add a 'Color' SectionLabel + 6 selectable swatches calling vm.upsertCollection(col.copy(color=...)); make the title tappable to rename via an inline field -> upsertCollection(col.copy(name=...)); add a 'Delete collection' ghost button with a confirm step calling vm.deleteCollection(col.id) then onBack().

### [Collections grid + detail] Overview: no inline search field
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionsScreen.kt:40-47
- **detail:** Verified. CollectionsScreen relies on the global AppBar search icon (onSearch -> Route.Palette per MainScaffold.kt:86/91), which opens the command palette — a different feature. There is no inline filter of the grid by name/item body. Web collections-overview.tsx:22-33 holds a query state and filters by `c.name.toLowerCase().includes(q) || c.items.some(i => i.body.toLowerCase().includes(q))` via a pill search input (lines 81-106), matching mockup collections-overview.jsx:158-175.
- **fix:** Add an inline search pill (bg2 background, pill radius, search icon + BasicTextField) in the header area, hold a query state, and filter collections.sortedBy{sortOrder} by name.contains(q, ignoreCase=true) || items.any{ it.body.contains(q, ignoreCase=true) }.

### [Insights Report + Deep dive] No Week / Month / All time range toggle
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:54-62
- **detail:** Confirmed against InsightsScreen.kt:55-62 and insights.jsx:64-68. The Android screen renders a single MdSegment (Report/Deep dive) at line 60 and hard-codes the eyebrow at line 56: SectionLabel(if (deep) "REFLECTION · ALL TIME" else "REFLECTION · WEEK SO FAR"). The mockup header (insights.jsx:60-68) renders TWO PillTracks — mode and a Week/Month/All time range — and derives the eyebrow from range (insights.jsx:20-22). Both web views accept a `window?: AnalyticsWindow` ('week'|'month'|'all') (report.tsx:291-294, deep-dive.tsx:401-405) and scope all collections via scopeSessions/scopeCaptures/scopeReasonLogs (report.tsx:302-304, deep-dive.tsx:410-412). Verified there is NO scope* helper or AnalyticsWindow anywhere in unstuck_android/core (grep returned nothing); the only equivalent is lib/analytics-window.ts on web. So Android has no range pills and no time-window scoping at all.
- **fix:** Add a range state (week/month/all) and a second MdSegment under the Report/Deep-dive segment. Port a core scopeSessions/scopeCaptures/scopeReasonLogs(window) helper (mirror lib/analytics-window.ts) and filter all collections before passing to analytics. Derive the eyebrow from range as in insights.jsx:20-22 (report: WEEK SO FAR/MONTH/ALL TIME; deep: WEEK/MONTH/ALL TIME).

### [Insights Report + Deep dive] Report view is missing nearly all of its content
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:63-70
- **detail:** Confirmed against InsightsScreen.kt:63-70. The non-deep branch renders ONLY the 3 StatCards inside a single LazyColumn item (lines 64-70), then a 24dp spacer at line 80. There is no weekday-by-area chart, no estimate-calibration scatter, no interruption histogram, and no 'WORTH NOTICING' insights section. Mockup (insights.jsx:174-264) and report.tsx render all of these: StackedBars weekday chart with the 5-area dot legend (report.tsx:395-406), EstimateCalibration scatter with a dashed perfect-prediction line (report.tsx:150-226), InterruptionShape histogram (report.tsx:228-288), and a gradient 'WORTH NOTICING' section (report.tsx:418-482). Verified the core logic already exists in Analytics.kt: weekdayAreaHours() (line 32), calibrationDots()/calibrationHitRate() (lines 55/67), interruptionBins() (line 75), topInsights() (line 189). None of these are referenced anywhere under unstuck_android/app (grep returned nothing).
- **fix:** Build the missing cards from the existing core functions: a stacked-bar weekday chart with a 5-area dot legend (weekdayAreaHours), an estimate-calibration scatter with dashed perfect-prediction line (calibrationDots/calibrationHitRate), an interruption-bins histogram (interruptionBins), and a 'WORTH NOTICING' section (topInsights). Note the Android areas list in core is DEFAULT_AREAS = Work/Personal/Home/Health/Volunteering (Analytics.kt:30).

### [Insights Report + Deep dive] Deep dive is missing most of its panels
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:71-79
- **detail:** Confirmed against InsightsScreen.kt:71-79. The deep branch renders only a 2-up StatCard Row (lines 73-76) and a bare Heatmap (line 78), then the 24dp spacer. Mockup (insights.jsx:354-423) + deep-dive.tsx render: the 4-up stat strip, 'Where focus actually lives' heatmap, 'What pauses you' (PauseAnatomy, deep-dive.tsx:462-468), 'How fast you come back' (ReentryDistribution, deep-dive.tsx:469-475), plus web-only 'The slip detector' (deep-dive.tsx:478-484), 'Captures by kind' (deep-dive.tsx:486-492), and a closing 'close the tab' note (deep-dive.tsx:494-510). PauseAnatomy, ReentryDistribution, slip detector, captures-by-kind, and the closing note are all absent in Android. Verified core has pauseAnatomy() (Analytics.kt:112), reEntryDistribution() (line 131), slipping() (line 156, already imported+used for the report friction count), and captureBreakdown() (line 173). vm.reasonLogs is exposed (AppViewModel.kt:56) but never collected in this screen (InsightsScreen.kt:44-46 collect only sessions/tasks/captures).
- **fix:** Use pauseAnatomy(reasonLogs), reEntryDistribution(sessions), slipping(tasks), captureBreakdown(captures) to build the missing panels in a title+sub+visualization+narrative card layout matching DDPanel (deep-dive.tsx:67-101). Collect vm.reasonLogs in the screen first.

### [Insights Report + Deep dive] Deep-dive stat strip wrong: only 2 of 4 cards, wrong metrics
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:72-77
- **detail:** Confirmed against InsightsScreen.kt:72-77. Android renders two cards: StatCard("Focus", "${totalMin/60}h ${totalMin%60}m", caption="across N sessions") and StatCard("On estimate", "$hit%", caption="within 5 min"). The mockup (insights.jsx:362-365) and deep-dive.tsx:441-444 show FOUR cards: 'Focus this week', 'Median session', 'Re-entry within 5m', 'Captures kept'. 'On estimate' is not a deep-dive metric in either source — it belongs to the Report 'Estimates' card. Median session, Re-entry within 5m, and Captures kept are all missing.
- **fix:** Replace with the four cards from deep-dive.tsx:441-444. Median = median of sessions.actualSec/60; Re-entry within 5m = pctFastReentry(reEntryDistribution(sessions)) = bins[0]/total*100 (deep-dive.tsx:272-276), gated to '—' below REAL_DATA_THRESHOLD; Captures kept = captures.size. Match captions 'across N sessions', 'live from your data', 'captures attached to faster re-entries', 'across all sessions'. (Note: the mockup itself hard-codes these numbers; the live formulas come from deep-dive.tsx.)

### [Insights Report + Deep dive] Heatmap missing column (time-bucket) headers and cell values
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:86-105
- **detail:** Confirmed against InsightsScreen.kt:86-105. The Heatmap composable renders a title 'Hour × day' (line 92), sub 'Brighter = more focus.' (line 93), then day rows with a 30dp day label and colored cells (lines 94-101). There is NO column-header row and NO numeric value printed inside cells. Mockup FocusHeatmap (insights.jsx:429-484) uses a '54px repeat(6,1fr)' grid with six column headers '7-9'/'9-11'/'11-1'/'1-3'/'3-5'/'5-7' (lines 431,446-453) and prints the value inside non-zero cells (line 478), white fg when intensity>0.5 (lines 466-468). deep-dive.tsx TimeHeatmap does the same with buckets '7–9'..'5–7' and prints Math.round(data[ri][ci]*60) (lines 105,147). The panel title should be 'Where focus actually lives' / 'Hour-of-day × day-of-week, last 4 weeks. Color is total minutes.' (deep-dive.tsx:448-449).
- **fix:** Add a header row with the six bucket labels (leading spacer matching the 30dp day-label column). Render (grid[d][col]*60).roundToInt() inside cells where v>0, white when intensity>0.5 else ink3. Rename title/sub to match deep-dive.tsx:448-449.

### [Settings hub + subpages + Areas] All Focus/Sound/Accessibility/Interface/Backup controls are inert local state — nothing persists or is wired
- **kind:** bug
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:97-124, 189-208
- **detail:** Confirmed against SettingsScreen.kt. ToggleRow (line 191) and SegRow (line 202) both hold `var on/sel by remember { mutableStateOf(initial) }` seeded with hardcoded literals (e.g. ToggleRow("Soft exit", true) line 101; SegRow("Theme", listOf("system","light","dark"), "system") line 117). There is no STORAGE_KEYS/DataStore/vm read or write for any of these. The only DataStore in the app module is the StartNext widget (surface/StartNextSnapshot.kt) and SyncCoordinator's SharedPreferences holds only prevUserId — no PREF_* settings store exists. Two concrete consequences I verified: (1) FocusScreen.kt:80 hardcodes `FocusTimer.deriveState(l, nowMs, 1.0)`, so the Soft-overrun setting is never consumed even though FocusTimer.overrunGraceSeconds() exists to map it; (2) treatment in Android lives only on the transient LiveSession (FocusScreen.kt:74 reads l?.treatment), whereas web persists it via STORAGE_KEYS.FOCUS_TREATMENT — so even if Interface exposed it, it wouldn't survive between sessions without a persisted pref. Every change is lost on navigation and affects nothing.
- **fix:** Add a persisted preferences layer (DataStore in the app module, or AppViewModel-exposed StateFlows) keyed to the same semantics as web STORAGE_KEYS, and have FocusScreen (pass the real overrun grace into deriveState instead of 1.0), the right-rail collapse, theme/density, and a persisted treatment actually consume them. For treatment specifically, add a persisted pref rather than relying on LiveSession.treatment, then feed it into FocusTimer.empty / startFocus.

### [Settings hub + subpages + Areas] Account section is missing real email, display-name edit, password, export action, and delete-account
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:131-137
- **detail:** Confirmed. AccountContent (SettingsScreen.kt:131-137) renders only three flat SettingRow entries: 'Signed in / Your account', 'Export everything / JSON bundle' with an empty onClick {}, and 'Sign out / End this session' (the only wired one, calling vm.signOut()). Web Account (settings-panel.tsx:131-189) shows real me.email ('Signed in as'), a DisplayNameRow, a PasswordRow, 'Export everything' with a live row count calling exportAll(), and a red 'Delete my account' opening DeleteAccountModal. Mockup settings-account.jsx mirrors Signed-in-as / Display name Edit / Export / coral Delete. AuthService.kt exposes only `currentUserId` (line 58) via currentUserOrNull()?.id — there is no email or userMetadata accessor, so the real email/display name are not even reachable from the UI today.
- **fix:** Add email/displayName accessors to AuthService (client.auth.currentUserOrNull()?.email and userMetadata['display_name']/'full_name') and expose them on AppViewModel. Render 'Signed in as <email>' with a Sign out button, an inline-editable Display name row, an Export everything row that runs a real JSON export with a live row count, and a coral 'Delete my account' row that opens a confirm dialog. Drop the placeholder 'Your account' / 'End this session' sub-copy.

### [Settings hub + subpages + Areas] Areas row: tapping the '$open open' stats label permanently deletes the area (no confirm, no kebab/rename/recolor)
- **kind:** behavior
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:156
- **detail:** Confirmed. SettingsScreen.kt:156 attaches `.clickable { vm.deleteLifeArea(a.id) }` directly to the '$open open' Text, so tapping the stats label deletes the area with no confirmation — accidental data loss. vm.deleteLifeArea exists (AppViewModel.kt:205). Web AreaRow (life-area-panel.tsx:257-322) and mockup settings-areas.jsx:40-52 put deletion behind a 3-dot kebab menu offering Rename, a COLOR swatch row, and a two-step 'Delete area' -> 'Delete forever?' confirm. Kotlin has no kebab, no rename, no recolor, no confirm.
- **fix:** Remove the delete-on-stats-tap. Add a 3-dot menu button (vertical dots, matching mockup) opening a DropdownMenu with Rename (inline edit -> vm.upsertLifeArea with new name), a color swatch row (recolor via vm.upsertLifeArea), and a two-step confirm Delete -> Delete forever.

### [Auth (sign in / sign up) + Onboarding stepper] Auth form is missing the white surface card that wraps the fields
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:77-91
- **detail:** Mockup onboarding-auth.jsx lines 39-56: the EMAIL + PASSWORD inputs and the submit button live inside a <form> styled as a card — background var(--u-surface) (white), borderRadius 16, boxShadow var(--u-shadow), padding '22px 22px 18px', gap 14. In AuthScreen.kt the fields (MdField) and the Sign in / Google / magic-link controls sit bare directly on the page Column (lines 77-99) with no clip/background/border/shadow surrounding them — no card at all. Confirmed against both files.
- **fix:** Wrap the name/email/password MdFields plus the submit/Google buttons in a Column styled like the design-system Card: Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, ...).padding(22.dp) with verticalArrangement spacedBy 14.dp. (A Card composable already exists in Components.kt.)

### [Auth (sign in / sign up) + Onboarding stepper] Headline serif size is too small (30sp vs 44px mockup)
- **kind:** visual
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:67-70
- **detail:** Mockup lines 28-32 set the auth h1 to fontSize 44, lineHeight 1.05, letterSpacing -0.018em, italic serif, centered. AuthScreen.kt:67-69 renders it at UFont.serifItalic(30). 44px is the dominant visual element; 30sp reads markedly smaller. Confirmed in both files. Note: 44px CSS != 44sp exactly, so ~40-42sp is the right target rather than a literal 44.
- **fix:** Bump to UFont.serifItalic(40) (or ~42) to approximate the 44px mockup, keep textAlign Center, and tighten line height toward 1.05.

### [Command palette + Avatar menu (Android)] Avatar menu shows hardcoded fake identity, never the real signed-in user
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/components/AvatarMenu.kt:38-43
- **detail:** AvatarMenu.kt:38-43 hardcodes initials "UN", name "Unstuck", and the placeholder email "unstuck@kazaure.com". The email line is vm.auth?.currentUserId?.let { "Signed in" } ?: "unstuck@kazaure.com" — so when signed in it shows the literal word "Signed in" and otherwise the placeholder email; the user's real email is never shown. Verified AuthService.kt only exposes currentUserId (val currentUserId: String? get() = client.auth.currentUserOrNull()?.id) — there is currently NO email/name accessor, so the data genuinely is not available to bind. Web refs confirmed: settings-panel.tsx:135-136 shows "Signed in as" + me.email, and left-rail.tsx:312-327 shows me.displayName + me.email with me.initials.
- **fix:** Add email/name accessors to AuthService (client.auth.currentUserOrNull()?.email and userMetadata full_name/display_name), expose them via AppViewModel, then bind the header name, email line, and initials to the real account; fall back to a placeholder only when signed out. Matches web me.displayName / me.email.

## MED (61)

### [Design system (tokens/buttons/chrome/controls/Orbit)] areaColor() has no 'teal' branch — teal-colored areas/collections synced from web render gray
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/theme/Theme.kt:36
- **detail:** Confirmed against Theme.kt:36-45: areaColor() handles indigo/coral/violet/blue/green/amber/red but has no 'teal' branch, so token "teal" falls to `else -> ink4` (neutral gray). 'teal' appears nowhere in the Android codebase (verified by grep). The web (area-dot.tsx:31 and COLOR_TOKENS line 40) and the mockup (primitives.jsx:46) both define teal=oklch(0.70 0.10 200) as a first-class selectable token. Caveat that lowers severity from the original 'high': the Android color pickers (SettingsScreen.kt:145 = indigo/coral/violet/green/amber/blue; OnboardingScreen.kt:48 = indigo/coral/green/amber/violet/blue) do NOT offer teal locally, so an Android user cannot create a teal area on-device. The real failure is cross-device: an area/collection colored teal on web syncs down and renders gray in AreaDot/ColorChip/FilterPill on Android (areaColor is consumed by SettingsScreen ColorChip, CollectionsScreen, TodayScreen filter pills, etc.). Note 'red' has the same picker gap but IS handled by areaColor.
- **fix:** Add a teal color to UnstuckColors (teal = oklch(0.70, 0.10, 200.0); dark variant raised lightness like the other accents) and a `"teal" -> teal` branch in areaColor(), matching the mockup's oklch(0.70 0.10 200). Optionally also add teal (and red) to the Android pickers for parity.

### [Design system (tokens/buttons/chrome/controls/Orbit)] Area-token dots reuse theme accents instead of the dedicated higher-chroma mockup palette (violet, red, indigo, coral most visible)
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/theme/Theme.kt:36
- **detail:** Confirmed. Android areaColor() resolves tokens to the THEME accents (Theme.kt:56-62): primary/indigo 0.58/0.13/280, violet 0.55/0.13/300, red 0.66/0.13/25, amber 0.80/0.13/75, blue 0.70/0.10/240, green 0.72/0.10/155, coral = hex #E89077. The mockup/web area palette (primitives.jsx:41-50, area-dot.tsx:26-34) is intentionally separate and higher-chroma: indigo 0.58/0.16/280, violet 0.62/0.18/300, red 0.66/0.18/25, amber 0.78/0.12/75, blue 0.72/0.13/240, green 0.70/0.10/155, coral 0.72/0.13/35, teal 0.70/0.10/200. The biggest visible deltas: violet (0.55/0.13 vs 0.62/0.18) and red/indigo chroma (0.13 vs 0.18/0.16) read duller/darker than the web dots; coral is a flat hex vs oklch. Severity med (dots are small; affects every area dot but subtly).
- **fix:** Resolve area tokens against a dedicated map mirroring the mockup COLOR_TOKEN_TO_OKLCH (indigo 0.58/0.16/280, violet 0.62/0.18/300, red 0.66/0.18/25, amber 0.78/0.12/75, blue 0.72/0.13/240, green 0.70/0.10/155, coral 0.72/0.13/35, teal 0.70/0.10/200) instead of the theme accents, so area dots match the web exactly.

### [App shell / bottom nav / FAB / overlay routing] FAB has no drop shadow (mockup floats it with elevation)
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Chrome.kt:128-134
- **detail:** Re-verified: mockup FAB sets boxShadow '0 4px 8px rgba(0,0,0,0.18)' (android-screens.jsx:158) so the coral square floats above the nav bar. CoralFab (Chrome.kt:128-134) draws a flat size(56).clip(RoundedCornerShape(16)).background(c.coral) box with NO .shadow() modifier; grep confirms zero `.shadow(` calls in the entire app/ or design/ modules. On the #FAFAF7 bg the FAB reads flat/pasted-on.
- **fix:** Add a shadow before the clip: `modifier.size(56.dp).shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f)).clip(RoundedCornerShape(16.dp)).background(c.coral).clickable(onClick = onClick)`. Keep the 16dp radius. (Note the FAB sits inside the BottomNavBar Box, offset y=-28; ensure the parent doesn't clip the shadow.)

### [Today / dashboard (+ empty state)] Start Next card is missing the 'Pick another' button
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:147
- **detail:** Verified. StartNextHero (TodayScreen.kt:147) renders only a single coral Focus UButton inside a Box. The android mockup (android-screens.jsx:288-293) places a coral mdFilledBtn Focus plus an outlined mdOutlinedBtn 'Pick another'; web start-next-card.tsx:166-168 has a 'Pick another' line button beside the coral 'Start now'.
- **fix:** Wrap Focus in a Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) and add an OUTLINED 'Pick another' UButton. Wire it to advance pickStartNext past the current id (e.g. exclude startNext.id) or navigate to Tasks, mirroring web onPickAnother.

### [Today / dashboard (+ empty state)] PausedCard secondary text drops the actual paused-at time
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:165
- **detail:** Verified. TodayScreen.kt:165 renders `${task.estimateMin}m · paused` with no time. The android mockup shows `25m · paused at 09:30` (android-screens.jsx:334-336) and web shows `paused at MM:SS` (task-row.tsx:141). The `elapsed` MM:SS string is already passed into PausedCard at line 123 (formatMMSS(live!!.priorAccumulatedSec ?: 0)) and bound to the ring label at line 161 but omitted from the secondary line.
- **fix:** Render `${task.estimateMin}m · paused at $elapsed` using the elapsed param already in scope. Ideally compute elapsed from FocusTimer.elapsedSec(live, now) for a live value rather than only priorAccumulatedSec.

### [Today / dashboard (+ empty state)] Backlog pill absent from the Today filter strip
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:116
- **detail:** Verified. The filter Row at TodayScreen.kt:116-119 renders only an 'All' FilterPill plus one per area; there is no Backlog affordance. Web TodayList renders a BacklogPill before All (today-list.tsx:182-196); tapping it drives the list from the Backlog view (visibleTasks 'Backlog') and clears the area filter (line 194). The Kotlin BACKLOG view already exists in VisibleTasks.kt:76-83, so the data path is available — only the UI entry point is missing.
- **fix:** Add a Backlog pill (amber dot, c.amberSoft background when active, count = visibleTasks(BACKLOG, tasks, blocks, now, activeArea=null, slipMode=false).size) as the first chip; when active drive `rows` from the Backlog view and reset areaFilter to null, matching web.

### [Today / dashboard (+ empty state)] 'NEXT' badge not shown on the recommended task in the Today list
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:79
- **detail:** Verified. TodayScreen.kt:79 excludes the start-next task from `rows` (`it.id != startNext?.id`), and the private TaskRow (lines 174-189) has no NEXT badge. The dashboard mockup TaskRow (dashboard.jsx:251-258) and web StandardTaskRow (task-row.tsx:312-322) render a coral-soft 'NEXT' pill (background var(--u-coral-soft), color var(--u-coral), fontSize 10, weight 700) when task.id === startNextId, and keep the task visible in the list as well as the hero. Note the android mockup's own Today list does not actually depict a NEXT pill in the list rows (android-screens.jsx:344-351), so this is parity with web/dashboard.jsx rather than the android mockup; med is reasonable.
- **fix:** Stop excluding startNext from `rows` (or document the intentional dedup), and add an `isNext` flag to TaskRow that renders a coral-soft 'NEXT' pill (c.coralSoft bg, c.coral text, 10sp, weight 700) after the task name.

### [Today / dashboard (+ empty state)] Empty state missing the secondary 'Quick capture' button and truncates body copy
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:203
- **detail:** Verified. EmptyHero (TodayScreen.kt:203) renders only one coral 'Add one thing' UButton, and the body copy at line 202 is `Nothing's missing. When something's on your mind, drop it in.` — truncated. The dashboard mockup EmptyToday (dashboard.jsx:421-430) shows a coral 'Add one thing' plus a line/outlined 'Quick capture', and the full line is `Nothing's missing. When something's on your mind, drop it in — small is good.` (dashboard.jsx:419). Eyebrow/headline ('Nothing to start' / 'You're all clear.') already match.
- **fix:** Add an OUTLINED 'Quick capture' UButton beside 'Add one thing' (wired to quick-capture) and restore the full body copy ending '— small is good.'

### [Tasks list (tabs/filters/rows)] NEXT badge not rendered on the start-next task row
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:72
- **detail:** Verified: TasksScreen.kt computes no startNext (the import/call to pickStartNext is absent; it is only used in TodayScreen.kt:77) and the name Row (lines 72-77) renders only the task name with no pill. Desktop web renders a coral-soft NEXT pill (list-row.tsx lines 90-99: fontSize 10, weight 700, padding '2px 7px', radius 999, bg --u-coral-soft, fg oklch(0.45 0.16 35)) gated on `isStartNext = !t.done && t.id === startNext?.id` (task-list-pane.tsx line 213). pickStartNext exists in core (PickStartNext.kt:31, signature `pickStartNext(tasks, blocks, liveTaskId, areaFilter)`) and color tokens c.coralSoft + c.coralDeep both exist (Theme.kt:58). NOTE: the android-screens.jsx mockup (the Android-specific design) shows NO NEXT badge on any Tasks row — its ATaskRow is name/area/mins only. So this is a web-parity gap, not an android-mockup gap; whether it should ship depends on whether Android targets web parity (which the 6-pill enum and ported pickStartNext suggest) or the simplified android mockup. Kept at med because the web treats it as a meaningful affordance.
- **fix:** If targeting web parity: compute `val liveId = vm.liveSession.value?.taskId` (or collect it) and `val startNext = pickStartNext(tasks, blocks, liveId, areaFilter=null)`, then in the name Row render a coral-soft pill (c.coralSoft bg, c.coralDeep text, sans 10 Bold, RoundedCornerShape(999.dp), padding horizontal 7 vertical 2) when `!t.done && t.id == startNext?.id`. If targeting the android mockup, leave as-is.

### [Tasks list (tabs/filters/rows)] Backlog age chip and slip-review footer absent
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:48
- **detail:** Verified the core logic is ported (VisibleTasks.kt:35-48 define isSlipping + daysSinceCreated) and TaskListView includes BACKLOG + COMPLETED (Enums.kt:71), but TasksScreen renders neither the per-row amber age chip nor a slip footer, and calls visibleTasks with `slipMode = false` hardcoded (TasksScreen.kt:48). Web shows a per-row amber 'today'/'1d'/'Nd' chip when Backlog is active (list-row.tsx lines 137-152, gated by showAge=view==='Backlog') and a bottom slip-review footer button toggling slipMode (task-list-pane.tsx lines 224-253). c.amberSoft + c.amberInk exist (Theme.kt:61). So with the Backlog pill present on Android, the Backlog tab currently has no aging affordance, which is a real behavioral gap relative to web.
- **fix:** When `view == TaskListView.BACKLOG`, render an amber age pill (c.amberSoft bg, c.amberInk text, using daysSinceCreated(t, now)) in the trailing meta area, and add a footer item (LazyColumn item after the rows, or a pinned Row) showing the slip count with a Review/Clear toggle wired to a `slipMode` state passed into visibleTasks. Alternatively, if Android intentionally follows the simpler android-screens mockup (All/Today/Upcoming/Later only), drop BACKLOG + COMPLETED from the rendered pill set instead.

### [Tasks list (tabs/filters/rows)] Filter pill set differs from android mockup (renders 6 pills incl. Backlog + Completed)
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:56
- **detail:** Verified TasksScreen.kt:56 iterates `TaskListView.entries`, which (Enums.kt:71) yields six entries: ALL, BACKLOG, TODAY, UPCOMING, LATER, COMPLETED. The android-screens.jsx ATasksScreen (line 440) renders exactly four pills `['All','Today','Upcoming','Later']` with Today (index 1) active by default. So Android shows two extra pills the android mockup omits. Android does default to TODAY (line 47), matching the mockup's active index. The Backlog pill on Android also lacks the web's amber accent (web BacklogPill, task-list-pane.tsx lines 262-296: amber dot prefix + amber-soft active fill + amber-ink text); Android renders every pill with the generic ink/bg2 style (lines 57-59). Raised to med because the pill row is the most prominent control on the screen and visibly diverges from the android mockup. Note the discrepancy cuts both ways — Android's enum matches the full web VIEWS set (web includes Backlog+Completed), so this is the unresolved 'which mockup wins' decision.
- **fix:** Decide the target. If following the android mockup: render only ALL/TODAY/UPCOMING/LATER (e.g. `listOf(ALL, TODAY, UPCOMING, LATER)` instead of `TaskListView.entries`). If following web: keep all six but give the Backlog pill the amber accent (amber dot + c.amberSoft active bg + c.amberInk text) per the web BacklogPill, and also ship the Backlog age chip + slip footer (see related finding).

### [Task detail (full screen)] Tags field missing from the meta grid
- **kind:** missing
- **file:** TaskDetailSheet.kt:84-95
- **detail:** Web MetaGrid has a 'Tags' row backed by a TagPicker (task-detail-pane.tsx:183-190) editing task.tags via upsert. The android mockup screen 03 omits Tags from its 4-field grid (android-screens.jsx:506-510), so this is web-behavior-only, not in the Android visual target. TaskItem.tags exists (Models.kt:85). The Kotlin grid (TaskDetailSheet.kt:86-93) has no Tags cell, so tags can neither be viewed nor edited from the detail screen. Downgraded to med since the Android mockup itself does not show Tags.
- **fix:** Add a Tags meta cell that renders current tags as pills and opens a tag picker on tap, persisting via vm.updateTask(task.copy(tags = ...)).

### [Task detail (full screen)] No 'Add a capture' composer on the detail screen
- **kind:** missing
- **file:** TaskDetailSheet.kt:103-108
- **detail:** Web provides an inline AddCaptureForm — a dashed '+ Add a capture' pill that expands to a textarea + tag chooser + Save (task-detail-pane.tsx:358, 391-512); the android mockup screen 03 does NOT show this affordance (android-screens.jsx:518-534 ends after the capture card), and the web tasks.jsx mockup shows the dashed pill (tasks.jsx:318-326). Kotlin has nothing to add a capture from the detail screen. vm.saveCapture(taskId, ...) already exists (AppViewModel.kt:186). Downgraded to med since the Android screen-03 mockup omits it.
- **fix:** Add a dashed '+ Add a capture' pill that opens a small composer (body + tag pills + Save) calling vm.saveCapture(task.id, null, tag, body), matching the web AddCaptureForm.

### [Task detail (full screen)] Sessions section has no empty state, no 'N attempts' sub-count, no per-session detail
- **kind:** missing
- **file:** TaskDetailSheet.kt:97-102
- **detail:** Web shows a Sessions section with sub '{n} attempts' (task-detail-pane.tsx:236), an empty-state card 'No sessions yet…' (lines 237-246), and per-session cards showing relative time + 'MM:SS in focus' + 'est Xm' (lines 250-277). Kotlin only renders the Sessions block when taskSessions is non-empty (TaskDetailSheet.kt:97) — no empty state — and each row is a bare '• Xm focused' bullet (line 100) with no attempt count, no timestamp, no estimate. Session.completedAt/actualSec/estimateMin all exist (Models.kt:106-108).
- **fix:** Always render the Sessions section with a '{n} attempts' sub. Show an empty-state card when none, else per-session surface cards with relative time and 'MM:SS in focus · est Xm'.

### [Task detail (full screen)] Estimate history section absent
- **kind:** missing
- **file:** TaskDetailSheet.kt:103-109
- **detail:** Both web (task-detail-pane.tsx:361-381) and the web mockup (tasks.jsx:329-340) render a final 'Estimate history' section: a bg-2 card reading 'Estimated N min — Initial estimate — will calibrate after the first session.' (or '…after N sessions. Calibration improving.'). The Kotlin has no Estimate history section. The android mockup screen 03 does not show this section, but the web is the behavior source of truth.
- **fix:** Add an 'Estimate history' section (SectionLabel + bg2 card) with the calibration copy, branching on taskSessions count like the web.

### [Task detail (full screen)] Schedule silently auto-picks a slot instead of opening a schedule picker
- **kind:** behavior
- **file:** TaskDetailSheet.kt:75-78
- **detail:** Web 'Schedule' opens ScheduleTaskModal (task-detail-pane.tsx:142,382-386) so the user chooses date/time; the web mockup wires Schedule to setScheduleOpen(true) (tasks.jsx:253,271). The Kotlin instead calls findFreeSlots(...).firstOrNull() and immediately schedules into the first free slot with no user choice (TaskDetailSheet.kt:75-78), then shows 'Scheduled <label>'. There is NO ScheduleTaskModal/sheet anywhere in the Android app (confirmed by grep), so this would be a new component. Note the android mockup screen 03 button is just 'Schedule' with no modal shown, so the auto-pick is a behavior divergence rather than a visual one.
- **fix:** Open a schedule sheet (date + free-slot picker) on tap so the user chooses when, instead of auto-committing the first free slot. Requires building a schedule picker (none exists today).

### [Task detail (full screen)] Action row: 'Delete' is a full DANGER pill far down the screen, not a right-aligned ghost link; no 'Move out of Later'
- **kind:** missing
- **file:** TaskDetailSheet.kt:73-80,109
- **detail:** Web keeps Start now / Schedule / Mark done together and pushes a ghost 'Delete' (red text, kind=ghost) to the far right via marginLeft:auto (task-detail-pane.tsx:153-161). It also shows a 'Move out of Later' ghost button when task.later is true (lines 198-202). Kotlin renders Delete as a separate full-width DANGER pill at the very bottom after captures (TaskDetailSheet.kt:109) and has no 'Move out of Later'. vm.setLater(task, later) exists (AppViewModel.kt:115). The android mockup screen 03 shows only Focus + Schedule (no Delete/Mark done), so this is web-behavior-driven.
- **fix:** Render Delete as a right-aligned ghost (red text) within/near the action row, and add a 'Move out of Later' ghost action (vm.setLater(task,false)) when task.later == true.

### [New-task sheet (+ WHEN / recurrence / first step)] FIRST STEP missing helper line and the 'AI · suggest a smaller step' chip
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:78-79
- **detail:** NewTaskSheet.kt:78-79 renders only SectionLabel("First step", coral) + a single-line OutlinedTextField with no helper text and no AI chip. The mockup (task-create-modal.jsx:160-167) and web (task-create-modal.tsx:392-403) both show ink-3 helper copy 'The single physical move you'll make first. Smaller is better.' and an AIChip reading 'AI · suggest a smaller step'.
- **fix:** Add the ink-3 helper text under the first-step field and an AIChip-styled pill 'AI · suggest a smaller step'.

### [New-task sheet (+ WHEN / recurrence / first step)] Header subtitle 'feels like 30 seconds, not 3 minutes' is missing
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:53
- **detail:** NewTaskSheet.kt:53 renders only SectionLabel("New task") with no subtitle. Both mockup (task-create-modal.jsx:114-123) and web (task-create-modal.tsx:336-347) show the eyebrow 'NEW TASK' followed by an ink-3 '· feels like 30 seconds, not 3 minutes' span on the same baseline-aligned row.
- **fix:** Replace the lone SectionLabel with a baseline-aligned Row: SectionLabel("New task") + ink-3 ~13sp text '· feels like 30 seconds, not 3 minutes'.

### [New-task sheet (+ WHEN / recurrence / first step)] ESTIMATE uses fixed chips instead of a numeric input — arbitrary minutes can't be entered
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:61-64
- **detail:** NewTaskSheet.kt:61-64 renders a chip row listOf(15, 25, 45, 60, 90). The mockup (task-create-modal.jsx:177-193) and web (task-create-modal.tsx, the ESTIMATE input with type=number min=1 width 88 + 'min' suffix) both use a free numeric input, so a user can enter e.g. 30 or 50. With chips the Android user is locked to five preset values.
- **fix:** Replace the chip row with a small bordered numeric field (rounded ~10, 'min' suffix) matching the mockup; optionally keep a couple of quick presets but allow free entry.

### [New-task sheet (+ WHEN / recurrence / first step)] Priority picker added that exists in neither the mockup nor the web create modal
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:66-69
- **detail:** NewTaskSheet.kt:66-69 renders a 'Priority' section with low/medium/high chips (Priority.entries). I scanned the mockup (task-create-modal.jsx) and the web create modal (task-create-modal.tsx) — neither exposes a priority field. The web silently passes no priority at create time (addTask call at tsx:53-58 omits priority). This is an Android-invented field. (Minor caveat: the web omits priority entirely rather than explicitly setting 'medium' at this call site, but the conclusion — no priority UI on web/mockup — holds.)
- **fix:** Remove the Priority section from the create sheet (let priority default like web) to match the mockup; if priority editing is desired, surface it on the task detail screen instead.

### [New-task sheet (+ WHEN / recurrence / first step)] Footer lacks a Cancel button and the trailing arrow on 'Add task'
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:83-98
- **detail:** NewTaskSheet.kt:83-98 renders a single UButton("Add task", DARK) with no icon and no Cancel affordance. The mockup (task-create-modal.jsx:313-334) and web (task-create-modal.tsx:830-851) footers both have a ghost 'Cancel' button on the left and a primary 'Add task' button with a trailing Icon.arrow; the web also shows a 'Press ⌘ ↵' hint. The ⌘↵ kbd hint is desktop-only and reasonably dropped on mobile, but the explicit Cancel and the trailing arrow are part of the design and missing.
- **fix:** Add a ghost 'Cancel' button (calls onDismiss) and a trailing arrow icon on the 'Add task' button to match the footer; the ⌘↵ hint can be omitted on mobile.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Cockpit and Monk are not distinct layouts — all three treatments reuse the Ambient column
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:97-138
- **detail:** CONFIRMED in part, with an important correction. The designated reference mockup /tmp/unstuck_mockups/unstuck-v2/project/focus.jsx is a SINGLE focus layout — it has NO Cockpit/Monk/Ambient distinction and NO treatment switching. The three distinct layouts the finding describes (cockpit.tsx grid '1fr 320px' with right aside + UP NEXT + CAPTURES THIS SESSION + 96px timer; monk.tsx centered 128px Instrument-Serif italic timer + READY/IN FOCUS/STILL GOING eyebrow + Soft-exit) live in the full WEB app at /Users/ahmadtambaya/Desktop/projects/unstuck/components/focus/treatments/{cockpit,monk,ambient}.tsx, which I re-read and they DO match the descriptions (cockpit.tsx:90 gridTemplateColumns '1fr 320px', :156 FIRST PHYSICAL ACTION, :187 OVER ESTIMATE/TIME REMAINING, :284 CAPTURES THIS SESSION, :370 Promote to task; monk.tsx:118 fontSize 128, :34-37 READY/IN FOCUS/STILL GOING). In Android FocusScreen.kt:97-138 all three FocusTreatment values render the same Column; cockpit only appends CapturesRail (line 128-129) and monk only hides the orbit+task name (lines 97,121). So the discrepancy is real RELATIVE TO THE WEB APP, but the cited mockup (focus.jsx) does not itself ship three layouts, so severity is med not high. The Android domain model does define FocusTreatment {AMBIENT,COCKPIT,MONK} (Enums.kt:29-33) and the Android onboarding/settings mockup (android-batch-b.jsx:493-497) lets users pick a treatment, so treatments are intended.
- **fix:** If matching the web app: split into FocusAmbient/FocusCockpit/FocusMonk selected by treatment. If matching only the focus.jsx mockup: a single Ambient-style layout is sufficient and the in-screen treatment chips should be removed. Decide which spec is authoritative before investing in three layouts.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Focus screen ignores the OVERRUN/DONE/RESUME states the model already supports — only Pause/Resume/Done buttons shown
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:134-138
- **detail:** PARTIALLY CONFIRMED, with corrections. The claim 'state machine collapsed to running/paused — no idle/begin, overrun, done, or resume states' is WRONG about the model: FocusState (Enums.kt:18-26) DOES define IDLE, STARTING, RUNNING, OVERRUN, PAUSE, DONE, RESUME, and FocusTimer.deriveState (FocusTimer.kt:41-48) computes them. The real issue is the SCREEN: FocusScreen.kt:134-138 always renders exactly three buttons (Capture / Pause|Resume / Done) regardless of state. OVERRUN is derived (line 80) but only recolors the timer (line 125). extendFocus() exists (AppViewModel.kt:165) but is wired to nothing. CORRECTION: the 'six distinct control sets' (idle/running/pause/overrun/done/resume) come from the WEB app's controls.tsx, NOT the mockup — focus.jsx renders only three buttons too (Capture/Pause|Resume/Done, focus.jsx:222-245) and has no overrun check-in row, no idle Begin entry, and no done summary. So against the mockup the three-button layout is actually correct; only the overrun affordances are arguably missing. NOTE the original detail's claim 'enum collapsed' is the inaccurate part — drop that framing.
- **fix:** At minimum wire an OVERRUN check-in affordance (Add 10 min -> vm.extendFocus(10)) since extendFocus is otherwise dead code. Do NOT build a full six-state control machine unless matching the web app rather than focus.jsx.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Eyebrow never shows the overrun message
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:95
- **detail:** CONFIRMED. Mockup focus.jsx:153 sets the eyebrow to timer.paused ? 'PAUSED' : isOverrun ? 'OVERRUN · STILL HERE' : 'FOCUSING'. Android FocusScreen.kt:95 only does SectionLabel(if (paused) "PAUSED" else "FOCUSING"). When past estimate the user sees 'FOCUSING' instead of the supportive 'OVERRUN · STILL HERE'. The state value to gate on already exists (state == FocusState.OVERRUN at line 80/125).
- **fix:** Render: paused -> 'PAUSED', state==OVERRUN -> 'OVERRUN · STILL HERE', else 'FOCUSING'.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Sub-timer label freezes at '00:00 left' instead of counting up '+MM:SS over'
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:126
- **detail:** CONFIRMED. Mockup focus.jsx:218 renders isOverrun ? `+${fmtMMSS(elapsed - estimateSec)} over` : `${fmtMMSS(remaining)} left`. Android FocusScreen.kt:126 always renders '${formatMMSS(remaining)} left', and remaining is coerceAtLeast(0) (line 78), so during overrun it freezes at '00:00 left' rather than counting up the overage. Real visual bug.
- **fix:** When state == FocusState.OVERRUN render '+${formatMMSS(elapsed - estimateSec)} over' (coral); else '${formatMMSS(remaining)} left'.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Task meta line omits the life area
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:123
- **detail:** CONFIRMED. Mockup focus.jsx:201 renders `{task.lifeArea ?? '—'} · {task.estimateMin}m estimate`. Android FocusScreen.kt:123 renders only '${task.estimateMin}m estimate'. TaskItem.lifeArea exists (Models.kt:90) but is unused here, so the area context is missing.
- **fix:** Render '${task.lifeArea ?: "—"} · ${task.estimateMin}m estimate'.

### [Capture sheet + Reflection dialog] Capture sheet uses a bottom ModalBottomSheet instead of the top-anchored centered dialog
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/CaptureSheet.kt:50
- **detail:** focus.jsx lines 298-314 render the capture as a dialog over a blurred scrim anchored near the top (alignItems:'flex-start', padding:'18vh 20px 20px'), width min(540px), borderRadius 16, gap 12, padding '18px 20px'. CaptureSheet.kt:50 uses Material3 ModalBottomSheet (slides up from the bottom with a SheetHandle drag handle). The capture is meant to drop in over the focus content near the top, not as a bottom sheet.
- **fix:** Replace ModalBottomSheet with a Box(fillMaxSize, background=SheetScrim, clickable=onDismiss) containing a top-aligned Column (e.g. padding(top ≈ 18% of height) or a fixed top offset) with a width-capped (~540dp/fillMaxWidth) surface card at RoundedCornerShape(16.dp), padding 18/20; keep imePadding for the keyboard.

### [Capture sheet + Reflection dialog] Capture footer hint + Cancel replaced by a Save button; reflection subtitle missing
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/CaptureSheet.kt:62-66
- **detail:** focus.jsx lines 351-361: the capture footer is a hairline-topped row (borderTop 1px var(--u-line)) with hint 'Press ↵ to save · stays attached to this session' (fontSize 11.5, ink-3) on the left and a ghost Cancel button on the right — there is NO Save button (Enter submits). CaptureSheet.kt:62-66 instead renders a DARK 'Save' UButton and no footer hint or Cancel. Separately, reflection.jsx lines 68-70 show a subtitle 'No score. Just a quick note for next time.' (fontSize 13.5, ink-3) that ReflectSheet.kt omits entirely.
- **fix:** Capture: add a top-hairline footer Row with the hint text (ink3, ~11.5sp) on the left and a GHOST 'Cancel' on the right; trigger save on the keyboard 'Done' action (ImeAction.Done) — or keep a Save button as a platform concession but add the Cancel/hint row. Reflection: add a Text subtitle 'No score. Just a quick note for next time.' (ink3, ~13.5sp) under the 'How did that land?' headline.

### [Capture sheet + Reflection dialog] Reflection headline undersized and container metrics off
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/ReflectSheet.kt:43,44,47
- **detail:** reflection.jsx: h2 'How did that land?' is fontSize 30 serif italic (lines 63-67); container borderRadius 22 (line 53), padding '28px 30px 24px' (line 55), section gap 18 (line 56). ReflectSheet.kt:47 uses serifItalic(24) (too small), line 43 uses RoundedCornerShape(28.dp) (too round), padding horizontal 22 / vertical 24 (line 43, narrower than 30 horizontal), and Arrangement.spacedBy(12.dp) (line 44, gap too tight vs 18).
- **fix:** serifItalic(30); RoundedCornerShape(22.dp); padding(horizontal = 30.dp, vertical = 24.dp) with extra top so it reads ~28/30/24; verticalArrangement = Arrangement.spacedBy(18.dp).

### [Calendar Day/Week/Month + drag-to-schedule] Month 'Busiest / Lightest' sidebar cards missing
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/CalendarScreen.kt:83-117
- **detail:** Confirmed. MonthView (CalendarScreen.kt:83-117) has only the heatmap card — no busiest/lightest summary and no 'worth noticing' callout. Mockup MonthSidebar (calendar-month.jsx:6-47) shows two cards: 'BUSIEST' ('Thu, May 21', '10m focused') and 'LIGHTEST' ('Fri, May 1', 'No focus logged'). The byDay map needed for this is already computed (line 88).
- **fix:** Add Busiest/Lightest (or a 'WORTH NOTICING / Heaviest focus this month' callout) computed from byDay, mirroring MonthSidebar / month-mini.tsx headline logic.

### [Calendar Day/Week/Month + drag-to-schedule] Day header missing eyebrow, serif title, and 'Xh scheduled'
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:103-111
- **detail:** Confirmed. DayGrid.kt's day switcher (lines 102-111) is only '‹  Today  ›' — a serif-italic chevron, sans 'Today'/date text, serif-italic chevron — with no eyebrow, no 'What realistically fits?' title, and no scheduled-hours total. Mockup calendar-today.jsx header (lines 21-53) shows eyebrow 'TODAY', serif-italic h3 'What realistically fits?', and a right-aligned 'Nh scheduled'. Web today-timeline.tsx computes the scheduled total as sum of durationMinutes/60 (line 268).
- **fix:** Add an eyebrow SectionLabel('TODAY'), a serif-italic title 'What realistically fits?', and a 'Nh scheduled' figure = sum of dayBlocks.durationMinutes/60.

### [Calendar Day/Week/Month + drag-to-schedule] No block resize, click-to-edit, or click-to-create on the day grid
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:131-147
- **detail:** Confirmed. DayGrid.kt blocks (lines 136-145) are plain non-interactive Boxes with no clickable and no resize handle; the only interaction is long-press-drag from the tray (lines 165-172). There is no empty-slot tap-to-create. Web today-timeline.tsx supports empty-slot click opening TaskCreateModal at a snapped time (snapY at lines 135/152/293, modal import line 20), block click opening CalBlockEditModal (import line 19, render line 430), and a TimelineResizeHandle with 15-min snap committing durationMinutes (lines 418-461).
- **fix:** Make blocks clickable to open an edit sheet, add an empty-grid tap handler that creates/opens-create at the snapped time, and add a bottom drag-resize affordance committing durationMinutes.

### [Calendar Day/Week/Month + drag-to-schedule] Day grid hour window 6-22 differs from web's full 24h
- **kind:** behavior
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:51-52
- **detail:** Confirmed. DayGrid.kt clamps START_HOUR=6 / END_HOUR=22 (lines 51-52), the drop is clamped to that range (line 93), and blocks with topMin < 0 (before 06:00) are dropped via the 'if (topMin >= 0)' guard (line 133). Web today-timeline.tsx uses FIRST_HOUR=0 / LAST_HOUR=23 (verified lines 51-52) and auto-scrolls to ~current hour (anchorHour logic lines 211-213). Note: the mockup calendar-today.jsx itself only shows 8-16h, so the 'full 24h' target comes from the web component, not the mockup — the finding correctly cites the web behavior. There is also no auto-scroll on the Android grid.
- **fix:** Use a full 0-23 hour range (or at least stop silently hiding early-morning/late-night blocks and allow scheduling outside 06:00-21:45) and auto-scroll to the current hour on open, matching today-timeline.tsx.

### [Calendar Day/Week/Month + drag-to-schedule] Unscheduled tray missing AreaDot, estimate styling, drag-handle, header count, and Auto-sequence
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:151-177
- **detail:** Confirmed. Android tray (DayGrid.kt:151-177) is a horizontal scroll Row of plain bordered chips showing '${t.name} · ${t.estimateMin}m' (line 174), no AreaDot, no ⋮⋮ grip, label 'Drag onto the grid to schedule' (line 151), no unscheduled count, and no Auto-sequence button. Mockup/web tray (calendar-shared.jsx UnscheduledTray lines 51-137; unscheduled-tray.tsx) renders each task with a ⋮⋮ grip (line 74), an AreaDot colored by life area (line 75), name, and tabular estimate, under header 'DRAG INTO YOUR DAY · N UNSCHEDULED' (line 104 / unscheduled-tray.tsx line 97), plus an amber Auto-sequence callout that packs tasks into morning gaps (lines 100-132).
- **fix:** Render tray items with an AreaDot (areaColor(t.lifeArea)) + grip + name + estimate; set the header to 'DRAG INTO YOUR DAY · ${unscheduled.size} UNSCHEDULED'; add an Auto-sequence action that packs tasks into morning gaps like the mockup/web autoSequence.

### [Calendar Day/Week/Month + drag-to-schedule] Day blocks omit start-time label and done strikethrough/opacity
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:136-145
- **detail:** Confirmed. DayGrid.kt block (line 144) shows only b.taskName (single Text, maxLines 1), with no start-time line and no done styling. Web today-timeline.tsx block applies line-through when done (kind.done ? 'line-through' : 'none', line 409) and shows a time alongside the name; week-full.tsx blocks show name + time with done strikethrough. Note: applying 'done' on Android requires resolving the linked task's done state (CalBlock has no done field), via taskId.
- **fix:** Add a secondary start-time Text and apply ~0.55 alpha + line-through when the linked task (by taskId) is done.

### [Collections grid + detail] Overview: missing eyebrow, subhead copy, and empty-state
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionsScreen.kt:48-53
- **detail:** Verified. The mockup/web header has an eyebrow 'COLLECTIONS' (mono/uppercase) above the serif H1 and the full subhead 'A calm shelf for groceries, books, quotes, anything. Nothing here is a task.' (overview.jsx:142-155, overview.tsx:51-79). Android CollectionsScreen.kt:50-51 drops the eyebrow entirely and shortens the subhead to 'A calm shelf. Nothing here is a task.' The mockup/web also show a dashed-border bg2 empty card 'This can hold things for later.' / 'You don't need to remember everything.' (overview.jsx:182-199); Android renders an empty grid with no empty-state.
- **fix:** Add a SectionLabel('COLLECTIONS') above the serif title; restore the full subhead string; and when collections is empty (or filter is empty) render a dashed bg2 card with serif-italic 'This can hold things for later.' + ink3 'You don't need to remember everything.'

### [Collections grid + detail] Detail: missing item count and back-button label
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:67-72
- **detail:** Verified. Mockup/web detail header shows '{n} item{s}' on the trailing edge (collections-detail.jsx:99-105; collection-detail.tsx:182-189) and a back button labelled 'Collections' with a left-arrow (jsx:57-73; tsx:100-123). Android CollectionDetailScreen.kt:67 uses a bare-icon AppBar BACK (Leading.BACK) with no 'Collections' label, and the header row (lines 69-72) shows only the color chip + title — no item count.
- **fix:** Render the item count ('N items', singular when 1) in ink3 ~12sp on the header row's trailing edge; and add the 'Collections' affordance (label the back button or a text row).

### [Collections grid + detail] Overview card: wrong radius, padding, preview count, and missing subtitle + '+N more'
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionsScreen.kt:54-70
- **detail:** Verified. Mockup CollectionCard (collections-overview.jsx:16,27,32,79-107): borderRadius 16, padding '20px 22px', gap 14, minHeight 168; previews = [...pinned, ...nonPinned.slice(-3)].slice(0,3); italic-serif subtitle when present; 'Empty for now.' serif-italic line when no items; '+ N more' line when items exceed previews. Android card (CollectionsScreen.kt:54-70): radius 18, padding 14, minHeight 130, `col.items.take(2)` (no pinned-first ordering, only 2), renders no subtitle, no 'Empty for now.', no '+N more'. ItemCollection.subtitle exists (Models.kt:180).
- **fix:** Set card radius 16, padding ~20h x 22v, minHeight ~168; compute previews = (pinned + nonPinned.takeLast(3)).take(3); render subtitle (serif italic ink3) when non-null; show 'Empty for now.' (serif italic ink3) when no items; append '+ N more' (ink3 ~11.5sp) when items.size > previews.size.

### [Collections grid + detail] Add-item does not auto-focus or refocus, and uses ImeAction.Done
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:80-86
- **detail:** Verified. Web/mockup fast-add auto-focuses on open and refocuses after each submit via requestAnimationFrame (detail.tsx:58-62,86-88; jsx:14-17,42-44) — 'the entire premise.' Android CollectionDetailScreen.kt never creates a FocusRequester, never requests focus on open, and add() (lines 60-64) only trims/upserts/clears draft with no refocus. keyboardOptions uses ImeAction.Done (line 83), so submitting also collapses the keyboard, breaking rapid capture.
- **fix:** Add a FocusRequester on the BasicTextField, LaunchedEffect(collectionId){ focusRequester.requestFocus() } on open, and call focusRequester.requestFocus() after add(). Consider ImeAction.Send/None and keeping the keyboard up so submitting does not dismiss it.

### [Collections grid + detail] Detail: fast-add 'Press Enter' (Kbd) hint is absent
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:75-87
- **detail:** Verified. Mockup/web fast-add field shows a trailing 'Press' label + a Kbd chip with the return glyph (collections-detail.jsx:130-133; collection-detail.tsx:262-265). Android's add pill (CollectionDetailScreen.kt:75-87) has only the leading Add icon and the BasicTextField — no trailing hint.
- **fix:** Add a trailing Row with 'Press' (ink3 ~11.5sp) + a small Kbd-style chip showing the return glyph after the text field.

### [Insights Report + Deep dive] StatCard badges/captions diverge from mockup/web copy and skip the empty-state gate
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:66-68
- **detail:** Confirmed against InsightsScreen.kt:66-68. Android uses abbreviated copy: StatCard("Estimates", "$hit%", "${sessions.size} sessions", ..., "of sessions landed within 5 min."); StatCard("Re-entries", "${sessions.size}", "${captures.size} captures", ..., "focus sessions completed"); StatCard("Gentle friction", "${slips.size} tasks", ...). Web report.tsx:327-353 uses badge 'N sessions tracked', caption 'of recent sessions landed within 5 min of estimate'; 'N had a capture attached'; 'slipping — aged 21d+ or rescheduled 3+ times'. Critically, Android never gates on REAL_DATA_THRESHOLD: it computes hit=0 when dots is empty (InsightsScreen.kt:48) and shows a raw '0%', whereas report.tsx:329-333 shows value '—' + 'finish 5 sessions to start tracking estimate accuracy' below threshold. REAL_DATA_THRESHOLD=5 already exists in Analytics.kt:17.
- **fix:** Mirror report.tsx: when sessions.size < REAL_DATA_THRESHOLD show value '—' and the 'finish 5 sessions...' subtitle; otherwise use the full-length badge/caption strings from report.tsx:329-353. The const is already in core.

### [Insights Report + Deep dive] Header title text and headline size don't match mockup
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:57-58
- **detail:** Confirmed against InsightsScreen.kt:57-58. Android headline uses UFont.serifItalic(28) (Type.kt:41 defines serifItalic(size)); the mockup h1 is fontSize 42 Instrument Serif italic (insights.jsx:43-48). Subtitle copy is shortened: Android deep sub 'All your patterns, one screen.' and report sub 'No score. Observations to calibrate, not perform.' (line 58) vs mockup/web full strings: deep 'All your patterns, one screen. Read what's useful. Skip what isn't. You're allowed to close the tab.' and report 'We don't score you. These are observations about how you work — so you can calibrate, not perform.' (insights.jsx:17-19). The titles ('This is a quiet week, on purpose.' / 'Let's look closer. Calmly.') do match.
- **fix:** Increase the headline toward the mockup scale (e.g. serifItalic(34-40) given mobile width) and use the full subtitle strings from insights.jsx:17-19.

### [Insights Report + Deep dive] Amber 'sample' badges on chart titles are absent
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:55-62
- **detail:** Confirmed. Both web views append an amber 'sample' pill (sampleBadge style, report.tsx:84-94 / deep-dive.tsx:23-33) to section titles when data is below threshold/empty: report.tsx:377 ('When focus actually happens'), deep-dive.tsx:463 ('What pauses you'), :470 ('How fast you come back'), :479 ('The slip detector'). Android InsightsScreen has no sample/threshold concept at all, so empty-state charts cannot be labeled. NOTE: this finding's title also references the range toggle, which fully duplicates finding #1 — only the sample-badge half is distinct. Treat this as the sample-badge finding.
- **fix:** Add an amber 'sample' pill (c.amberSoft bg / c.amberInk fg) appended to chart titles when the relevant data set is empty/below REAL_DATA_THRESHOLD, matching the web behavior. (The range-toggle portion of this finding is covered by finding #1.)

### [Settings hub + subpages + Areas] Areas stats render only 'open count' — missing 'h this week' and the open count is the only stat computed
- **kind:** behavior
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:149-157
- **detail:** Confirmed. SettingsScreen.kt:149 computes `open = tasks.count { it.lifeArea == a.name && !it.done }` and line 156 renders only '$open open'. There is no weekly-hours line, even though vm.sessions is available on AppViewModel (line 54). Web statsFor (life-area-panel.tsx:27-37) and mockup settings-areas.jsx:31-39 show a right-aligned two-line column: '<n> open' over '<h>h this week', where hours = sum of session.actualSec for tasks in that area with completedAt within the last 7 days.
- **fix:** Render a right-aligned 2-line stat: '$open open' (ink2, semibold) over '${hours}h this week' (ink3), computing hours from vm.sessions filtered to task ids whose lifeArea == a.name and completedAt within the last 7 days (mirror statsFor). Move it off the clickable delete handler.

### [Settings hub + subpages + Areas] Segmented-control active state is inverted vs mockup (dark fill instead of raised white chip)
- **kind:** visual
- **file:** design/src/main/kotlin/tech/csalliance/unstuck/design/component/Controls.kt:91-99
- **detail:** Confirmed. MdSegment (Controls.kt:94-97): active option background = c.ink (dark) with text = c.bg; inactive = c.ink3 on transparent; track = c.bg2 (line 88). Mockup SetSegment (settings-extra.jsx:60-63): active = background var(--u-surface) (white) + color var(--u-ink) + subtle shadow; inactive = transparent + ink-3; track var(--u-bg-2). So the selected segment reads as a dark pill rather than a raised white chip.
- **fix:** Make the active segment background c.surface with c.ink text (add a small elevation/shadow); keep inactive transparent with c.ink3; track stays c.bg2.

### [Settings hub + subpages + Areas] Toggle ON color and size differ from mockup (green 44x26/22px thumb vs ink 34x20/16px thumb)
- **kind:** visual
- **file:** design/src/main/kotlin/tech/csalliance/unstuck/design/component/Controls.kt:71-80
- **detail:** Confirmed. MdToggle (Controls.kt:71-80) is 44dp x 26dp, ON background = c.green, 22dp white thumb translating 18dp. Mockup SetToggle (settings-extra.jsx:30-42): 34x20 pill, ON background var(--u-ink) (dark ink), 16x16 white thumb translating 14px. Wrong ON color (green vs ink) and larger dimensions.
- **fix:** Set the ON track to c.ink (not c.green). Optionally tighten to ~34x20 with a 16dp thumb and 14dp travel; at minimum fix the color. Note MdToggle is shared, so verify other call sites don't depend on the green ON state.

### [Settings hub + subpages + Areas] Per-row sub/description copy is dropped on every Focus/Sound/A11y/Interface control
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:189-208
- **detail:** Confirmed. ToggleRow (SettingsScreen.kt:189-197) and SegRow (200-208) accept only label/options and render the label only — no sub line. SettingRow (177-186) does support a sub param but Account passes placeholder text. Mockup SetRow (settings-extra.jsx:9-23) renders a sub line under every label, and web rows carry rich copy (e.g. 'The session length suggested when you click Focus', 'Removes the Today panel when a session is running', 'The breathing ring becomes a still ring'). Result: Focus/Sound/A11y/Interface rows are bare labels with none of the explanatory copy.
- **fix:** Add a `sub: String?` param to ToggleRow and SegRow, rendered ink3 ~12sp under the label (matching SettingRow), and pass the mockup/web copy for each control.

### [Settings hub + subpages + Areas] Backup section is missing iCloud row, Delete-account row, sync-status card, and the Account pointer note
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:120-123
- **detail:** Confirmed. Backup (SettingsScreen.kt:120-123) renders only 'Auto-export every Sunday' toggle and an inert 'Export now' SettingRow with empty onClick {}. Mockup SettingsBackupBody (settings-extra.jsx:253-280) has 4 rows: Auto-export every Sunday, Export everything now (Export btn), iCloud sync (em-dash, coming soon), Delete account (coral Delete). Web Backup (settings-panel.tsx:289-391) adds a Sync-is-on/paused status card with a Status toggle that expands row counts, plus a note pointing to the Account section. Missing iCloud, Delete, status card, and pointer.
- **fix:** Add an iCloud-sync row (disabled, em-dash 'Coming soon') and a coral Delete-account row, plus a sync-status card mirroring web (signed-in/paused with row counts from vm flows) and the 'Export and account deletion live under Account' pointer. Wire Export now to a real export action.

### [Settings hub + subpages + Areas] Interface section is missing the Accent palette and Focus-mode treatment controls
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:116-119
- **detail:** Confirmed. Interface (SettingsScreen.kt:116-119) renders only Theme and Density segments. Mockup SettingsInterfaceBody (settings-extra.jsx:212-245) adds an Accent palette of three two-swatch pill buttons (indigo-coral / periwinkle-rose / forest-amber). Web (settings-panel.tsx) additionally has a Focus-mode-treatment control wired to STORAGE_KEYS.FOCUS_TREATMENT. Note: the mockup itself does NOT show a treatment segment under Interface (only Theme/Density/Accent) — the treatment control is a web-only addition — so the accent palette is the clearer mockup-fidelity gap; the treatment control is a web-parity gap.
- **fix:** Add an Accent palette row (three multi-swatch pills matching the mockup). For web parity, also add a Focus-mode-treatment segment (ambient/cockpit/monk); since Android treatment currently lives only on the transient LiveSession (FocusScreen.kt:74) and vm.setTreatment mutates that, back it with a persisted pref so it survives between sessions.

### [Auth (sign in / sign up) + Onboarding stepper] Hardcoded line break in sign-in headline differs from mockup single string
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:68
- **detail:** Mockup line 31 sign-in title is the single string 'Pick up where you left off.' (CSS wraps it). AuthScreen.kt:68 hardcodes 'Pick up where\nyou left off.' forcing a fixed two-line break regardless of width/font metrics. Confirmed. Downgraded from high to med: it is a single short cosmetic phrase, not broken behavior, but the forced break can look wrong at the larger serif size and on wider layouts.
- **fix:** Use the plain string 'Pick up where you left off.' and let Text wrap naturally with textAlign Center; remove the embedded \n.

### [Auth (sign in / sign up) + Onboarding stepper] Brand lockup shows only the Orbit ring, not the 'unstuck' wordmark, and at the wrong size
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:64
- **detail:** Mockup line 21 renders <Wordmark size={20} /> centered — the Mark PLUS the 'unstuck' wordmark text (sans, weight 500, letterSpacing -0.015em, gap 8, with the mark forced to max(24, size+8)=28px). AuthScreen.kt:64 shows only Orbit(size = 36) with no 'unstuck' text. Verified there is NO Wordmark composable anywhere in unstuck_android (Mark.kt only defines Orbit; grep across the repo finds Wordmark only in handover.md prose). The web equivalent components/ui/wordmark.tsx exists. Note the detail's '~24px = Wordmark size+4' math was wrong: the mockup mark is max(24, size+8)=28px, so Android's 36 is oversized but the gap is 8px not exact.
- **fix:** Add a Wordmark composable to the design module (Orbit + a 'unstuck' Text, sans Medium, letterSpacing -0.015em, gap 8.dp, mark size ~28) and use it on the auth screen at size ~20, matching components/ui/wordmark.tsx and primitives.jsx. Onboarding step 0 correctly uses the bare Orbit (mockup uses <Mark size={96}/>, Android Orbit(88)).

### [Auth (sign in / sign up) + Onboarding stepper] Submit/Continue/Begin buttons are missing the trailing arrow icon
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:87
- **detail:** Mockup auth submit (lines 53-55) is 'Create account|Sign in <Icon.arrow />' and onboarding Continue/Begin (lines 198-199) are 'Continue <Icon.arrow />' / 'Begin <Icon.arrow />', where Icon.arrow is a 14px right-arrow (primitives.jsx:19). Verified UButton in Components.kt:42-74 only supports leadingIcon (no trailingIcon param), and AuthScreen.kt:87 / OnboardingScreen.kt:124 pass no icon, so no arrow renders on either screen.
- **fix:** Add a trailingIcon: ImageVector? param to UButton (render after the label) and pass a right-arrow vector on the auth submit and onboarding Continue/Begin buttons.

### [Auth (sign in / sign up) + Onboarding stepper] Sign-up missing required-name and password-length client validation (web behavior)
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:78-89
- **detail:** Web sign-up (app/auth/sign-up/page.tsx:48-60) requires non-empty name ('Your name helps us greet you. Even a first name is fine.'), requires email+password ('Email and password both required.'), and enforces password.length >= 8 ('Password must be at least 8 characters.') before calling the backend. AuthScreen.kt:78 labels the field 'Name (optional)' and lines 87-89 just trim and submit with no length/empty checks. Confirmed in both files. Mockup also marks password minLength={8} with placeholder 'At least 8 characters' (lines 49-51).
- **fix:** Match the web: on sign-up make name required (label 'Your name', validate non-blank), validate email+password presence and password length >= 8 with the same inline error copy before invoking the VM.

### [Auth (sign in / sign up) + Onboarding stepper] No 'Forgot password' affordance (present in web sign-in)
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:94-99
- **detail:** Web sign-in offers 'Forgot password' linking to /auth/forgot in two places (app/auth/sign-in/page.tsx:128-129 in the existing-account banner and lines 234-235 in the footer). AuthScreen.kt has no forgot-password entry point; the only secondary actions are the mode switch (line 96) and magic link (line 99). Verified the VM (AppViewModel.kt) also has no resetPassword method. Note: the mockup itself does NOT include a forgot-password link, so this is web-parity, not mockup-fidelity.
- **fix:** Add a 'Forgot password' text action (web uses ink3) in sign-in mode that triggers a reset-password flow; this also requires adding a resetPassword/resetPasswordForEmail method to AppViewModel since none exists.

### [Auth (sign in / sign up) + Onboarding stepper] Onboarding does not persist the chosen focus treatment
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:59-64
- **detail:** Mockup onboarding finish (lines 91-94) returns { areas, firstTask, treatment } — the treatment selection (step 3, lines 157-189) is the screen's payload. OnboardingScreen.kt tracks treatment state (line 57, set at line 109-110) but finish() (lines 59-64) only upserts life areas, optionally adds the first task, and calls vm.completeOnboarding(emptyList()) — the selected FocusTreatment is never written. Verified AppViewModel has no setDefaultTreatment/defaultTreatment method, so the step-4 choice is fully discarded.
- **fix:** Persist the chosen treatment in finish() (add a vm.setDefaultTreatment(treatment) / settings write to AppViewModel) so the onboarding selection actually takes effect.

### [Command palette + Avatar menu (Android)] Sign-out divider 'inset' padding is a no-op; the two dividers are inconsistent
- **kind:** bug
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/components/AvatarMenu.kt:49
- **detail:** AvatarMenu.kt:49 builds the divider above Sign out as Box(Modifier.fillMaxWidth().height(1.dp).background(c.line).padding(horizontal = 20.dp)). Because .padding follows .fillMaxWidth().height().background(), the 1dp line is painted full-width and the trailing horizontal padding only reserves empty outer space — so the intended 20dp inset never appears on the visible line. The first divider (line 46) has no padding at all, so the two are stylistically inconsistent. Confirmed by reading both lines. Downgraded from high to med: it is a subtle visual inconsistency, not broken behavior.
- **fix:** Apply padding before background so the inset renders: Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(c.line)), and pick one divider style for both line 46 and line 49 consistently.

### [Command palette + Avatar menu (Android)] Palette has no empty / no-results state on a non-matching query
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:72-83
- **detail:** CommandPalette.kt:72-83 renders only a LazyColumn of results with no fallback text. Note the blank-query case is NOT blank: the 5 actions are filtered with q.isEmpty() || contains(q) (line 56) and tasks with q.isEmpty() || contains(q) (line 57), so an empty query always shows actions+tasks. The gap is a non-matching query (e.g. 'zzz'), which yields an empty list and a blank area. Mockup cmdk.jsx:113-118 shows "Nothing matches. Try fewer words."; web command-palette.tsx:191-200 shows "No matches." (query) or a type-to-search prompt (empty). Downgraded high->med and corrected detail: the empty-query prompt does not apply to Android since actions are always present.
- **fix:** When results.isEmpty(), render a centered c.ink3 message "Nothing matches. Try fewer words." (mirrors mockup). An empty-query prompt is optional since the action rows already fill that case.

### [Command palette + Avatar menu (Android)] Palette omits the life-area filter items present in the web
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:50-61
- **detail:** Web command-palette.tsx (loop building items, verified ~lines 85-92) adds one item per life area: label = area name, sub = 'Filter tasks by area', onSelect routes to /tasks?area=<name>. Android CommandPalette.kt:50-61 builds only 5 navigation actions, task results, and note results — no area-filter entries. vm.lifeAreas IS available on AppViewModel (line 59), so the data exists. NOTE: the cmdk.jsx mockup does NOT include area items (it lists actions+tasks+collections), so this gap is relative to the WEB only, which the finding already states correctly.
- **fix:** Collect vm.lifeAreas and add a Result per area (badge e.g. "AREA", meta "Filter tasks by area") whose run navigates to Tasks pre-filtered by that area, matching web. Requires a tasks-by-area entry point since onTab("tasks") currently carries no area filter.

### [Command palette + Avatar menu (Android)] Palette is missing Insights and Start-focus / Quick-capture actions
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:50-55
- **detail:** Android action list (CommandPalette.kt:50-55) is only: Today, Tasks, Calendar, Lists, Settings. Web ROUTES (command-palette.tsx:15-22) include 'Go to Analytics' and 'Start a focus session'; mockup cmdk.jsx:24/29 include 'Go to Insights' and 'Quick capture'. Route.Insights exists and is reachable (MainScaffold.kt:107), and focus is launched via focusTask in MainScaffold, but the CommandPalette composable currently only accepts onDismiss/onOpenTask/onTab/onSettings callbacks (line 43) — so adding these actions requires new callbacks wired from MainScaffold.kt:110-116. Confirmed.
- **fix:** Add onInsights / onStartFocus (and/or quick-capture) callbacks to CommandPalette, wire them in MainScaffold (push(Route.Insights(false)) and the focusTask path), and add the matching Result actions so the palette can reach those flows like web/mockup.

### [Command palette + Avatar menu (Android)] Palette layout diverges from the cmdk card mockup (full-screen pill+Cancel vs centered card)
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:63-84
- **detail:** cmdk.jsx:82-108 and web command-palette.tsx:121-189 render a centered surface card (radius 16, shadow, scrim rgba(20,18,40,~0.22-0.36) with blur, ~12vh top offset), a borderless search row (leading search icon + trailing 'esc' Kbd), a 1px divider, and a results list with radius-8/10 rows where the active row gets bg-2. CommandPalette.kt:63-84 fills the screen (the composable sets background(c.bg) and MainScaffold.kt:99 also wraps it in a full-screen c.bg Box), wraps the input in a RoundedCornerShape(999.dp) bordered pill with a 'Cancel' text button, lists results below with no card, no scrim, and no active-row highlight. Confirmed. Note: android-screens.jsx contains NO palette screen, so there is no Android-specific mockup mandating the card; this is judged against web/cmdk.
- **fix:** If keeping the full-screen mobile pattern, at minimum drop the pill for a flat search row (leading search icon + borderless input on surface) divided from the list by a 1px c.line, and add the empty state. To match the mockup, present a centered surface(16dp)/shadow card over a blurred scrim with active-row bg highlight.

## LOW (54)

### [Design system (tokens/buttons/chrome/controls/Orbit)] CoralFab is missing its drop shadow — it does not read as floating above the nav bar
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Chrome.kt:128
- **detail:** Confirmed. CoralFab (Chrome.kt:128-134) chains size(56)/clip(RoundedCornerShape(16))/background(coral)/clickable with NO shadow or elevation; grep confirms `.shadow(` is used nowhere in the Android codebase. The mockup FAB (android-prototype.jsx:207-213) is a 56x56 radius-16 coral square with boxShadow 0 4px 8px rgba(0,0,0,0.18), floating top:-28 above the bottom-nav edge. BottomNavBar offsets the FAB y=-28dp (Chrome.kt:103) but with no shadow it sits flat. Severity lowered from med to low: the FAB still floats geometrically; only the soft separation shadow is missing (cosmetic).
- **fix:** Add a shadow before the clip: `.shadow(8.dp, RoundedCornerShape(16.dp))` (import androidx.compose.ui.draw.shadow) to approximate the mockup's 0 4px 8px rgba(0,0,0,0.18).

### [Design system (tokens/buttons/chrome/controls/Orbit)] Orbit gap is centered symmetrically on 3 o'clock instead of sitting just below it with the satellite capping the top
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Mark.kt:32
- **detail:** Confirmed, with a correction to the proposed fix. Mark.kt:31-39 draws startAngle=28, sweepAngle=304 (Compose: 0deg=3 o'clock, +sweep=clockwise), so the drawn arc runs 28..332 and the GAP is 332..360..28 — a 56deg gap centered symmetrically on 3 o'clock — and the satellite is placed at exactly 3 o'clock (center.x+ringR), i.e. the MIDDLE of the gap. The brand SVG (primitives.jsx:81-88 and android-prototype.jsx) is `M 26.5 16 A 10.5 10.5 0 1 0 22.6 24.1`: start (26.5,16)=0deg (3 o'clock), end (22.6,24.1)=~50.8deg below 3 o'clock, large-arc=1 sweep=0 (drawn the long way counterclockwise), leaving a ~50.8deg gap that spans 0..50.8deg (entirely BELOW 3 o'clock); the coral dot at (26.5,16)=3 o'clock caps the TOP of that gap. CORRECTION to the finding's fix: starting at 0 and sweeping clockwise ~310 puts the gap ABOVE 3 o'clock (310..360), the wrong side. To match the SVG, draw startAngle≈51, sweepAngle≈309 (clockwise), so the drawn arc is 51..360 and re-enters at 0deg/3 o'clock where the satellite (kept at center.x+ringR, center.y) caps the top of the 0..51deg gap. Severity low (only perceptible at larger mark sizes).
- **fix:** Set startAngle≈51f, sweepAngle≈309f so the gap occupies 0..~51deg below 3 o'clock and the arc re-enters at 0deg/3 o'clock; keep the satellite at Offset(center.x+ringR, center.y) so it caps the top edge of the gap (matching the SVG endpoint at (22.6,24.1)). Note: the finding's 'start 0, sweep clockwise 310' would mirror the gap to the wrong side.

### [Design system (tokens/buttons/chrome/controls/Orbit)] MdToggle thumb is missing the drop shadow from the mockup
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Controls.kt:79
- **detail:** Confirmed. MdToggle thumb (Controls.kt:79) is a plain white circle: offset/size(22)/clip(CircleShape)/background(White), no shadow. The mockup thumb (android-batch-b.jsx:756-760) has boxShadow 0 2px 6px rgba(0,0,0,0.15). Track 44x26 (line 752), thumb 22, and 18dp travel (Controls.kt:73) all match — only the thumb elevation is missing.
- **fix:** Add `.shadow(2.dp, CircleShape)` to the thumb Box before the clip (import androidx.compose.ui.draw.shadow) to approximate the mockup's 0 2px 6px rgba(0,0,0,0.15).

### [Design system (tokens/buttons/chrome/controls/Orbit)] UButton CORAL Focus CTAs use bright coral (#E89077) under white text instead of the WCAG-safe coral-deep used by the web
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Components.kt:53
- **detail:** Confirmed with a real-world nuance. UButton kind=CORAL/PRIMARY fills with c.coral (#E89077) and sets fg=White (Components.kt:53,60). The 'Focus' CTAs use ButtonKind.CORAL with white text (TodayScreen.kt:147, TaskDetailSheet.kt:74). The web Btn 'coral' variant (btn.tsx:25) deliberately uses --u-coral-deep, and globals.css:33-35 documents that bright coral fails WCAG AA (~2.6:1) under white while coral-deep clears it (~5:1). coralDeep is already defined (Theme.kt:58) and used elsewhere (AppRoot, DayGrid, AuthScreen). NUANCE: the Android mockup itself (android-prototype.jsx, C_CORAL=#E89077) renders these CTAs in bright coral, so this diverges from the literal Android mockup; the finding's argument is that the web's AA-safe deep coral is the behavioral source of truth for text-on-coral. Defensible accessibility fix; kept at low.
- **fix:** Fill white-text CORAL/PRIMARY/DANGER text buttons with c.coralDeep (matching web Btn 'coral'), reserving bright c.coral for the non-text FAB accent. Trade-off: this departs from the literal Android mockup color but gains WCAG AA contrast.

### [Design system (tokens/buttons/chrome/controls/Orbit)] SectionLabel is 11sp vs the mockup eyebrow's 10.5px (its own doc comment says 10.5)
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Components.kt:130
- **detail:** Confirmed. SectionLabel (Components.kt:126-132) uses UFont.mono(11, FontWeight.Medium) with letterSpacing=0.9.sp, while its own doc comment (line 124) and the StatCard label spec say 10.5sp / 0.08em. The prototype eyebrow (android-prototype.jsx:14-18, the `eb` helper) is IBM Plex Mono fontSize 10.5, fontWeight 500, letterSpacing 0.08em (=~0.84sp at 10.5), uppercase, ink3. Real but ~0.5sp larger + ~0.06sp wider tracking — cosmetic. Implementation note: UFont.mono(size: Int) only accepts an Int, so the fix needs a direct TextStyle with 10.5.sp rather than UFont.mono(10.5).
- **fix:** Build the style with a float size, e.g. TextStyle(fontFamily=UFont.mono, fontWeight=Medium, fontSize=10.5.sp, letterSpacing=0.84.sp) (UFont.mono(Int) can't take 10.5), or change the doc comment to 11 if the larger size is intentional.

### [Design system (tokens/buttons/chrome/controls/Orbit)] Bottom-nav label font is 11sp vs mockup 10.5px and lacks the tight tracking
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Chrome.kt:122
- **detail:** Confirmed. NavCell (Chrome.kt:122) uses UFont.sans(11, SemiBold/Medium) with no letterSpacing. The mockup nav labels (android-prototype.jsx:243-247) are fontSize 10.5, fontWeight 600 active / 500 inactive, letterSpacing -0.005em (=~-0.05sp). 0.5sp larger and missing the slight negative tracking — cosmetic. Implementation note: UFont.sans(size: Int) only accepts Int, so 10.5sp requires a direct TextStyle.
- **fix:** Render the label with fontSize=10.5.sp and letterSpacing=(-0.05).sp via a direct TextStyle (UFont.sans(Int) can't take 10.5), keeping SemiBold/Medium by active state.

### [App shell / bottom nav / FAB / overlay routing] Bottom nav top divider is 1dp, mockup is 0.5px hairline
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Chrome.kt:101
- **detail:** Re-verified: mockup borderTop is '0.5px solid #EAE7DD' (android-screens.jsx:171). Kotlin draws Box(Modifier.fillMaxWidth().height(1.dp).background(c.line)) at Chrome.kt:101. c.line maps to #EAE7DD (Theme.kt:55, confirmed by TokensTest.kt:20). 1dp is ~2x the intended hairline on most densities, slightly heavier than designed.
- **fix:** Use Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(c.line)) or height((0.5).dp). c.line is already #EAE7DD so no color change needed.

### [App shell / bottom nav / FAB / overlay routing] Nav row uses SpaceAround over 5 children — matches the mockup, not a true discrepancy
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Chrome.kt:88-99
- **detail:** Re-verified the code: Chrome.kt:88-99 uses Arrangement.SpaceAround over [NavCell(weight 1f), NavCell(weight 1f), Box(width 56dp, no weight), NavCell(weight 1f), NavCell(weight 1f)], and the FAB is centered independently via Modifier.align(Alignment.TopCenter) at Chrome.kt:103. The finding's description of SpaceAround distributing free space around all five items is accurate, BUT the mockup itself uses the SAME approach: android-screens.jsx:170 sets justifyContent:'space-around' over five children where the four tabs are flex:1 and the FAB slot is a fixed `<span width:56>` (line 174). So the Kotlin implementation MATCHES the mockup rather than diverging from it. The FAB is centered by TopCenter in both, independent of tab spacing. Recommend dropping this as a fidelity finding; the proposed weights-only rewrite would change spacing AWAY from the mockup, not toward it.
- **fix:** No change required — current SpaceAround layout matches the mockup's space-around row. If perfectly symmetric tab centering is desired beyond the mockup, optionally switch to default Arrangement.Start with each NavCell at weight(1f) and the 56dp gap Box unweighted, but this would deviate from the reference, so leave as-is.

### [App shell / bottom nav / FAB / overlay routing] Nav label font size 11sp vs mockup 10.5; proposed fix won't compile as written
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Chrome.kt:122
- **detail:** Re-verified: mockup nav label is fontSize:10.5 with letterSpacing:'-0.005em' (android-screens.jsx:190-191). Kotlin uses UFont.sans(11, ...) at Chrome.kt:122 with no negative letter spacing. The 0.5sp difference and missing tightening are real but cosmetic. IMPORTANT: the original proposed fix `UFont.sans(10.5, ...)` will NOT compile — UFont.sans is declared `fun sans(size: Int, ...)` (Type.kt:43) and takes an Int, not a Float. Also -0.005em at 10.5sp ≈ -0.05sp, not the -0.05sp-of-something the original implied; the magnitude is right but apply it via .copy.
- **fix:** Since UFont.sans only accepts Int, override the size with copy: `UFont.sans(11, if (active) FontWeight.SemiBold else FontWeight.Medium).copy(fontSize = 10.5.sp, letterSpacing = (-0.05).sp)`. (Or widen UFont.sans to accept a Float/TextUnit.) Cosmetic and optional.

### [Today / dashboard (+ empty state)] StartNext headline lacks the mockup's negative letter-spacing
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:145
- **detail:** Verified, but narrower than written. TodayScreen.kt:145 headline is sans(21, Bold) with no letterSpacing; android mockup h3 is fontSize 21, weight 700, letterSpacing -0.018em (android-screens.jsx:281-284). That tracking is absent. The finding's other sub-claims are NOT real: the eyebrow chip background is correctly Color.White.copy(alpha=0.7f) (line 137) matching rgba(255,255,255,0.7), and the 'Start next' SectionLabel is explicitly passed color = c.primaryDeep (line 139), not the ink3 default — the finding itself concedes both. So only the missing letter-spacing is a genuine (minor) gap.
- **fix:** Apply letterSpacing ≈ -0.38sp (-0.018em of 21sp) to the headline Text to match the mockup's tighter tracking. Cosmetic only.

### [Today / dashboard (+ empty state)] TaskRow estimate uses mono font vs mockup's sans
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/today/TodayScreen.kt:187
- **detail:** Verified. TodayScreen.kt:187 renders `${task.estimateMin}m` in UFont.mono(11). The android mockup ATaskRow renders the 'Nm' estimate in plain sans 11.5px (android-screens.jsx:402-404). The area dot size (AreaDotColor size = 5, line 183) and the '—' fallback (line 184) already match the mockup (5px dot, '—' fallback). Minor mono-vs-sans deviation only.
- **fix:** Render the '{n}m' estimate in UFont.sans (tabular) instead of mono to match the android mockup ATaskRow. Low priority.

### [Tasks list (tabs/filters/rows)] Estimate minutes rendered in monospace; both mockups use sans tabular
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:79
- **detail:** TasksScreen.kt:79 renders `Text("${t.estimateMin}m", style = UFont.mono(11), color = c.ink3)` — IBM Plex Mono. The android-screens.jsx ATaskRow (lines 402-404) renders the mins inheriting `fontFamily: sans` at fontSize 11.5 with `fontVariantNumeric: 'tabular-nums'`, and desktop tasks.jsx ListRow (lines 56-62) likewise inherits sans at 11.5. So the family is genuinely wrong (mono vs sans). The Today screen's ATaskRow in the same mockup is identical sans. Verified UFont.mono and UFont.sans are distinct families (Type.kt:31-39, PlexMono vs GeistSans). Downgraded from med to low: this is a small trailing-meta glyph, not a primary surface, but the discrepancy is real.
- **fix:** Change to `UFont.sans(12, ...)` (11.5 rounds to 12) so the family matches both mockups. Compose has no per-Text tabular-nums toggle without font-feature settings; the Geist sans figures already align acceptably at this size.

### [Tasks list (tabs/filters/rows)] Secondary-text sizes inconsistent: area label sans(12) vs minutes mono(11)
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:76
- **detail:** Verified TasksScreen.kt:76 uses `UFont.sans(12)` for the area label and line 79 uses size 11 for the minutes. The android mockup sets both the area sub-row (line 393) and the mins (line 403) to fontSize 11.5. So Android's two secondary-text elements are inconsistent with each other (12 vs 11) and neither exactly matches 11.5. The name (sans 14 Medium, line 73) and the 3.dp top padding on the area row (line 74) both already match the mockup (ATaskRow name fontSize 14/weight 500, area marginTop 3) — those parts of the original finding are correct and need no change.
- **fix:** Set both the area-label and the minutes Text to size 12 (mockup 11.5 rounds to 12) for a consistent secondary-text size.

### [Tasks list (tabs/filters/rows)] Row omits schedule chip, recurrence glyph, tag chips, and first-physical-action hint (web-only affordances)
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:74
- **detail:** Verified the Android row (TasksScreen.kt:72-79) shows only name, area dot+label, and minutes. Web list-row.tsx additionally renders a clock+schedule chip when the task has a today block (lines 132-136, fed by buildScheduleMap in task-list-pane.tsx:25-36), a recurrence '↻' glyph (line 112), tag chips (lines 113-119), and a '→ {firstPhysicalAction}' hint (line 120). Crucially, the android-screens.jsx ATaskRow is also minimal — name/area/mins only, no schedule/recurrence/tags/hint — so the Android row MATCHES the android mockup and only diverges from the richer web row. This is an accepted simplification unless full web parity is mandated; kept at low.
- **fix:** If full web parity is required, build a today-block schedule map (blocks are already collected) and render a clock chip, plus a recurrence glyph when `t.recurrence != null` and the firstPhysicalAction hint. If matching the android mockup, leave as-is.

### [Tasks list (tabs/filters/rows)] No selected-row highlight (acceptable: phone uses push navigation)
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:69
- **detail:** Verified the Android Tasks list navigates to a full-screen detail (MainScaffold.kt:89 `TasksScreen(..., onOpen = { push(Route.Detail(it.id)) })`, Route.Detail at line 46), so there is no persistent left-pane selection like the desktop split view. Every row uses the same 1.dp c.line border (TasksScreen.kt:69). Web list-row.tsx applies a stronger ring (`0 0 0 1px var(--u-line-2)` + shadow-sm) when selected (lines 54-56), but that is a split-pane concept the phone does not use. This is an intended platform difference, not a defect.
- **fix:** No change needed — push-navigation phone pattern has no persistent selection. Only relevant if a master-detail layout is ever introduced.

### [Tasks list (tabs/filters/rows)] Empty-state styling simpler than web (no dashed card, no Completed-specific copy)
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TasksScreen.kt:65
- **detail:** Verified TasksScreen.kt:65 renders the empty state as a plain left-aligned `Text("No ${view.label.lowercase()} tasks.", style = UFont.sans(14), color = c.ink3, modifier = padding(vertical=32))` — no card, no border. Web task-list-pane.tsx (lines 193-205) renders a centered dashed-border card (1px dashed --u-line-2, radius 14, padding 32, ink3, fontSize 13) with view-specific copy: 'No completed tasks yet. They will collect here as you finish them.' for Completed vs 'Nothing here. Hit New to add a task.' otherwise. The android-screens.jsx mockup does not depict an empty Tasks state, so this is judged against web. c.line2 exists (Theme.kt:55). Real but minor.
- **fix:** Render the empty state as a centered Box/Column with a 1.dp dashed c.line2 border, RoundedCornerShape(14.dp), ink3 text, and matching copy ('Nothing here. Tap + to add a task.' / a Completed-specific message). Compose dashed borders need a PathEffect.dashPathEffect stroke.

### [Task detail (full screen)] Meta grid: card pad is 16dp uniform vs mockup 14px-vertical / 16px-horizontal inset; gaps already match
- **kind:** visual
- **file:** TaskDetailSheet.kt:84-95
- **detail:** The android mockup renders the meta block as one A_SURFACE card (radius 14, boxShadow 0 0 0 1px A_LINE) with display:grid '1fr 1fr', gap '14px 16px', padding '14px 16px', marginTop 18 (android-screens.jsx:499-505). The Kotlin uses Card(radius=14) (good) with two manual Rows: horizontal Arrangement.spacedBy 16dp and vertical spacedBy 14dp (TaskDetailSheet.kt:84-95) — these gaps DO match the mockup (14 row / 16 col). The only deviation is the Card default pad=16dp (Components.kt:137) vs the mockup's 14px vertical inset. Minor; functionally fine.
- **fix:** Pass pad to match the mockup's 14px vertical / 16px horizontal inset (the Card default is 16dp uniform). Gaps already match (14dp row / 16dp col) — no change needed there.

### [Task detail (full screen)] Eyebrow says 'CREATED' with no relative time; uses 'TASK' instead of 'UNASSIGNED' when no area
- **kind:** visual
- **file:** TaskDetailSheet.kt:60
- **detail:** The android mockup hardcodes 'RETHINK · CREATED TODAY' (android-screens.jsx:479) and the web computes 'CREATED {howLongAgo(createdAt)}' e.g. 'CREATED 3 DAYS AGO' (task-detail-pane.tsx:126,37-45). The Kotlin eyebrow is '${(task.lifeArea ?: "Task").uppercase()} · CREATED' (TaskDetailSheet.kt:60) — no time suffix, and shows 'TASK' when lifeArea is null whereas the web shows 'UNASSIGNED'. TaskItem.createdAt exists (Models.kt:96).
- **fix:** Append a relative-created suffix (TODAY / YESTERDAY / N DAYS AGO) computed from task.createdAt, and use 'UNASSIGNED' (not 'TASK') when lifeArea is null.

### [Task detail (full screen)] First-action card omits the coral-soft styling when an action is set
- **kind:** visual
- **file:** TaskDetailSheet.kt:65-69
- **detail:** Web styles the first-action card with coral-soft background + coral eyebrow + ink (non-italic, medium) body when firstPhysicalAction is present, vs bg-2 + ink-3 italic when empty (task-detail-pane.tsx:607-660, tasks.jsx:223-242). The android mockup screen 03 only shows the empty (bg2 italic) state (android-screens.jsx:485-493). The Kotlin always uses c.bg2 background, neutral SectionLabel, and ink3 italic text even when an action is set (TaskDetailSheet.kt:65-68) — so a filled first action never gets the coral treatment and renders italic/ink3 as if empty. coral/coralSoft tokens exist in theme (Theme.kt:28).
- **fix:** When firstPhysicalAction is non-null use coralSoft background, coral SectionLabel color, and ink (non-italic, medium) body text; keep bg2/ink3/italic only for the empty placeholder.

### [Task detail (full screen)] Captures not sorted; Status ignores in-progress; sessions not newest-first
- **kind:** bug
- **file:** TaskDetailSheet.kt:52-53,92,99,105
- **detail:** Web sorts captures newest-first by .at (task-detail-pane.tsx:67) and sessions by completedAt desc (line 248); Kotlin filters captures (TaskDetailSheet.kt:53) and sessions (line 52) with no ordering, so display order is arbitrary. Also web Status is 'Completed' / 'In progress' (totalFocused>0) / 'Not started' (line 191), while Kotlin Status only branches done vs 'Not started' (line 92) — never 'In progress' despite TaskItem.totalFocused existing (Models.kt:82).
- **fix:** Sort taskCaptures by at desc and taskSessions by completedAt desc; compute Status as Completed / In progress (totalFocused>0) / Not started.

### [New-task sheet (+ WHEN / recurrence / first step)] REPEAT chip labels differ from mockup/web
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/components/RecurrenceEditor.kt:33-36
- **detail:** RecurrenceEditor.kt:33-36 labels the chips 'Never' / 'Daily' / 'Weekly' / 'Monthly'. The mockup REPEAT_OPTS (task-create-modal.jsx:9) is ["Doesn't repeat", 'Every day', 'Every week', 'Every month'] and the web (task-create-modal.tsx:603-607) uses 'Doesn’t repeat' / 'Every day' / 'Every week' / 'Every month'.
- **fix:** Relabel chips to "Doesn’t repeat" / "Every day" / "Every week" / "Every month".

### [New-task sheet (+ WHEN / recurrence / first step)] Recurrence 'Until' end-date control is absent
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/components/RecurrenceEditor.kt:38-47
- **detail:** RecurrenceEditor.kt:17-19 documents that `until` is intentionally omitted from the editor (open-ended only), and the editor (lines 30-48) renders no end-date affordance. The web (task-create-modal.tsx:662-715) exposes a '+ Set end date' button that seeds a default 4-weeks-out date, an 'Open-ended' clear button, and explanatory copy ('Materialises 8 weeks ahead — set an end date to bound it.'). So a user cannot bound a series on Android even though the model carries `until`. (Minor: the web shows Until for any recurrence kind, not just weekly — the original finding's 'Weekly recurrence' qualifier is too narrow.)
- **fix:** Add an optional 'Set end date' affordance (shown for any non-None recurrence) that sets Recurrence.until, mirroring the web's default-4-weeks suggestion and 'Open-ended' clear.

### [New-task sheet (+ WHEN / recurrence / first step)] WHAT/FIRST STEP fields are Material OutlinedTextFields rather than the borderless large-type inputs
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/NewTaskSheet.kt:54
- **detail:** NewTaskSheet.kt:54 (WHAT) and :79 (FIRST STEP) both use Material3 OutlinedTextField with a boxed border and floating label. The mockup (task-create-modal.jsx:127-142, 147-159) and web (task-create-modal.tsx:349-368 WHAT, :376-390 FIRST STEP) use borderless transparent inputs — WHAT at fontSize ~20-22 weight 500-600, FIRST STEP ~15-18 — with the placeholder in ink-3 and a thin divider beneath. The boxed Material look reads as a generic form field and breaks the editorial style.
- **fix:** Use a borderless BasicTextField with an ink-3 placeholder and a thin `line` Divider beneath, sized ~22sp for WHAT and ~18sp for FIRST STEP.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] First physical action is never surfaced in focus
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:121-124
- **detail:** PARTIALLY CONFIRMED — correcting the cited mockup. The reference mockup focus.jsx does NOT render task.firstPhysicalAction anywhere (its task block is just name + lifeArea/estimate at focus.jsx:191-202). The first-physical-action UI lives in the WEB app: ambient.tsx:124-134 ('First physical action · ...') and cockpit.tsx:156 ('FIRST PHYSICAL ACTION' card). Android TaskItem.firstPhysicalAction exists (Models.kt:91) but FocusScreen never reads it. So against focus.jsx this is NOT a discrepancy; against the web app it is. Lowered to low and reframed because the original claim that the mockup shows it is inaccurate.
- **fix:** Only if matching the web app: render a 'First physical action · {value}' hint under the task name when firstPhysicalAction is non-null and state != DONE. Not required to match focus.jsx.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Capture button missing the mono 'C' shortcut badge
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:135
- **detail:** CONFIRMED but cosmetic on mobile. Mockup focus.jsx:228-234 renders the Capture button with an inline mono 'C' badge (background rgba(255,255,255,0.10), borderRadius 5, 10px mono). Android FocusBtn for Capture (FocusScreen.kt:135) renders plain 'Capture'. Since Android has no keyboard shortcut this is purely a look mismatch; correctly low.
- **fix:** Optional: add a small rounded mono 'C' chip inside Capture to match, or deliberately drop it for touch.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] No breathing radial glow behind the ring
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:108-117
- **detail:** CONFIRMED. Mockup focus.jsx:163-168 places a radial coral glow behind the orbit animating 'u-breathe 6s ease-in-out infinite' (paused -> 'none'); the web ambient.tsx:89-90 animates a breathing radial field while running. Android FocusScreen.kt:108-117 draws only the static progress arc + Orbit (no animated glow), so the 'breathing session' character is lost. Low (subtle).
- **fix:** Add a rememberInfiniteTransition alpha/scale animation on a coral radial-gradient Box behind the ring (disabled when paused), ~6s ease-in-out, matching u-breathe.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Hardcoded overrun grace (1.0) ignores the user's overrun preference
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:80
- **detail:** CONFIRMED as a latent bug. FocusScreen.kt:80 calls FocusTimer.deriveState(l, nowMs, 1.0) with a hardcoded 1.0s grace, while FocusTimer.overrunGraceSeconds(pref) (FocusTimer.kt:52-60) maps the PREF_FOCUS_OVERRUN pref ('Never' -> infinity, '5 min' -> 300, '10 min' -> 600). So a user who set 'Never go to overrun' or a 10-min grace still flips to OVERRUN after 1s past estimate. This was bundled into the original state-machine finding; surfacing separately. Low only because the screen currently uses OVERRUN merely for timer color.
- **fix:** Read the PREF_FOCUS_OVERRUN pref and pass FocusTimer.overrunGraceSeconds(pref) instead of the literal 1.0.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Pause state omits a 'why are you pausing?' reasons UI
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:136
- **detail:** PARTIALLY CONFIRMED — correcting the cited spec. The reference mockup focus.jsx has NO pause-reasons UI; pausing in focus.jsx:236-239 just swaps the button label, exactly like Android FocusScreen.kt:136. The PauseReasons card is a WEB-app feature (pause-reasons.tsx / controls.tsx). Android's VM does expose saveReasonLog (AppViewModel.kt:191) but it is unused by FocusScreen. So this is NOT a discrepancy vs the mockup; only vs the web app. Reframed and kept low.
- **fix:** Only if matching the web app: render a reasons chip row in the paused state gated on the pause-reasons pref, calling vm.saveReasonLog(task.id, reason). Not required to match focus.jsx.

### [Focus mode + 3 treatments (Ambient/Cockpit/Monk)] Cockpit captures rail lacks tag colors, timestamps, and promote-to-task
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/FocusScreen.kt:154-163
- **detail:** PARTIALLY CONFIRMED — wrong reference. The promote-to-task + per-tag colors are a WEB cockpit.tsx feature (cockpit.tsx:284 CAPTURES THIS SESSION, :370 'Promote to task →'); the reference mockup focus.jsx has no captures rail at all in the focus screen. Android CapturesRail (FocusScreen.kt:154-163) renders captures as plain '• body' lines, no tag color/time/promote, and uses takeLast(3). Since focus.jsx doesn't show this rail, the discrepancy only exists against the web app, and the rail itself is an Android extra. Kept low.
- **fix:** Only if matching the web cockpit: render each capture with a colored tag label + time, newest-first, take 5, and add a promote-to-task affordance. Not required to match focus.jsx.

### [Capture sheet + Reflection dialog] Reflection radio rows: size/radius/dot differ from mockup
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/design/src/main/kotlin/tech/csalliance/unstuck/design/component/Components.kt:211-224
- **detail:** reflection.jsx lines 84-100: each option is borderRadius 12, padding '12px 16px', label fontSize 15 weight 500, gap between rows 6, and the dot is 16px with an active ring of 'inset 0 0 0 4px {bg}, 0 0 0 1.5px var(--u-ink)'. RadioOptionRow (Components.kt:211-224) uses RoundedCornerShape(14.dp), padding 14h/11v, label UFont.sans(14, Medium), dot 14dp with only a 1.5dp ink border (no inset fill ring). The selected dot therefore lacks the donut/ringed look.
- **fix:** Set RoundedCornerShape(12.dp), padding(horizontal = 16.dp, vertical = 12.dp), label UFont.sans(15, FontWeight.Medium). For the dot, render a 16dp circle filled with selectedBg, an inner inset (~4dp padding) and a 1.5dp ink outer ring when selected to mirror the inset ring; keep rows gap at 6dp in ReflectSheet. Note: RadioOptionRow is shared, so confirm no other caller depends on the current 14.dp metrics before changing in place.

### [Capture sheet + Reflection dialog] Reflection 'sel' state is captured but unused — Save discards the chosen option silently
- **kind:** bug
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/focus/ReflectSheet.kt:34,52
- **detail:** ReflectSheet.kt:34 declares 'sel' and line 48 toggles it, but neither Skip nor Save reads it (lines 50 and 52 both just call onDismiss). The web also does not persist howItWent (save() at reflection.jsx:26-32 only writes the carry-forward note), so dropping howItWent is faithful — but as written 'sel' is dead state and the Save button is behaviorally indistinguishable from Skip. This is effectively a corollary of finding 1: once carry-forward is wired, Save becomes meaningful.
- **fix:** After adding the carry-forward capture (finding 1), Save persists the carry-forward note, making it distinct from Skip. howItWent need not be stored (faithful no-score behavior). 'sel' can remain as pure UI selection feedback, but ensure Save actually saves the carry-forward text.

### [Calendar Day/Week/Month + drag-to-schedule] 'Connect' calendar button absent on Day view header
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/CalendarScreen.kt:46-57
- **detail:** Confirmed. The Calendar header (CalendarScreen.kt:46-50) is only an AppBar + Day/Week/Month MdSegment; there is no Connect affordance anywhere. Mockup calendar-shared.jsx CalendarHeader (lines 38-42) renders a line-style 'Connect' button with a calendar icon only when subview==='Today'. Downgraded severity from high to low: this is a single missing secondary action on one subview, not a broken or visually-wrong layout. (Also verify a calendar-connect flow actually exists on Android before wiring the button; if connect is unsupported on Android this becomes a no-op / scope question.)
- **fix:** If a calendar-sync/connect flow exists on Android, add a 'Connect' outlined button (calendar icon) on the Day view header to launch it, matching CalendarHeader; otherwise drop.

### [Calendar Day/Week/Month + drag-to-schedule] Week column starts 3 days before today instead of Monday-anchored ISO week
- **kind:** behavior
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/CalendarScreen.kt:64
- **detail:** Confirmed. WeekView builds days as 'Time.addDaysMillis(startOfDay, it - 3)' for it in 0..6 (CalendarScreen.kt:64), i.e. a rolling window today-3 .. today+3, not aligned to Mon-Sun. Mockup/web use a Monday-anchored ISO week (week-full.tsx isoWeekDates lines 48-52: monday = anchor.getDate() - dow). This is somewhat moot until the week grid itself is built (finding #1), but the anchoring logic is genuinely wrong.
- **fix:** Anchor the week to Monday (subtract (weekday+6)%7 days) and render Mon..Sun, as isoWeekDates does.

### [Calendar Day/Week/Month + drag-to-schedule] Day grid blocks are full-width with a uniform border, lacking corner-radius and per-kind inset/external styling
- **kind:** behavior
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/calendar/DayGrid.kt:139-141
- **detail:** Confirmed. DayGrid.kt blocks use RoundedCornerShape(8.dp) (line 139) plus a 1dp c.line border on every block (line 141), with fill branched only on isTaskBlock (line 140) — so EXTERNAL (meeting) and PLACEHOLDER blocks render identically to focus blocks. Mockup day/week blocks use radius 9 (today) / 6 (week) and distinct fills per kind (week THU blocks use color-mix area tint; external would be blue-soft). This overlaps with findings #4 and #1 (it is essentially the per-kind styling consequence on the day grid); kept as a low-severity styling note. CalBlockKind enum confirms TASK/PLACEHOLDER/EXTERNAL exist.
- **fix:** Branch fill/border on CalBlockKind: EXTERNAL -> blueSoft, no border; PLACEHOLDER -> transparent + inset line border; TASK -> areaSwatch fill, radius ~9 per the day mockup.

### [Collections grid + detail] Detail add-pill radius/border token differs from mockup card
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:76
- **detail:** Verified. Mockup/web fast-add is a 14-radius card with a line-2 ring + shadow and padding '14px 18px' (collections-detail.jsx:109-115; collection-detail.tsx:235-243). Android renders it as RoundedCornerShape(28.dp) with a line2 border and 16h x 12v padding (CollectionDetailScreen.kt:76) — a full pill, not the soft rounded card.
- **fix:** Change RoundedCornerShape(28.dp) to ~14.dp and padding to ~18h x 14v to match the mockup's fast-add card.

### [Collections grid + detail] Detail empty-state copy and radius differ
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:90-95
- **detail:** Verified. Mockup/web detail empty-state: serif-italic 'Keep small things here.' + 'Type above. Hit enter. Done.' in a radius-14 dashed bg2 card (collections-detail.jsx:137-153; collection-detail.tsx:269-289). Android (CollectionDetailScreen.kt:89-95) uses RoundedCornerShape(18.dp) and copy 'Type above. Hit return. Done.' ('return' vs 'enter').
- **fix:** Use radius 14 for the empty card and align the secondary copy with web ('Hit enter.'), or deliberately keep 'return' for Android — but match the radius.

### [Collections grid + detail] onBack() called during composition when collection is missing
- **kind:** bug
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionDetailScreen.kt:52
- **detail:** Verified. CollectionDetailScreen.kt:52 is `if (col == null) { onBack(); return }` — a navigation side-effect invoked directly in the composable body. If the collection is briefly absent during a realtime/hydrate refresh this can fire a back-nav mid-recomposition and is a composition-side-effect smell. Web renders a 'Collection not found.' message with a Back button (collection-detail.jsx:19-30 in the mockup; page.tsx/detail render the active collection conditionally) instead of auto-popping.
- **fix:** Move the nav into LaunchedEffect(col == null) { if (col == null) onBack() } and render a placeholder ('Collection not found.' + Back) in the body, so navigation runs as an effect, not during composition.

### [Collections grid + detail] Overview grid uses fixed 2 columns + 10dp gaps vs adaptive minmax(280) + 14 gap
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionsScreen.kt:42-46
- **detail:** Verified. Mockup/web grid is `repeat(auto-fill, minmax(280px, 1fr))` with gap 14 (collections-overview.jsx:201-205; collections-overview.tsx:136-139). Android hardcodes GridCells.Fixed(2) with 10dp horizontal+vertical arrangement (CollectionsScreen.kt:43,45-46). On a phone 2 columns is reasonable but Adaptive better matches intent, and the gap should be ~14dp.
- **fix:** Use GridCells.Adaptive(minSize ≈ 160-170.dp) and bump horizontal/vertical arrangement to 14.dp.

### [Collections grid + detail] Overview serif title size smaller than mockup
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionsScreen.kt:50
- **detail:** Verified. Mockup/web overview H1 is 36px serif italic (collections-overview.jsx:148; collections-overview.tsx:64); Android uses UFont.serifItalic(26) (CollectionsScreen.kt:50). Detail H1 is 34px in mockup/web (collections-detail.jsx:88; collection-detail.tsx:165) vs Android serifItalic(26) (CollectionDetailScreen.kt:71). Both headlines read notably smaller than design intent (some downscaling for mobile is expected, but 26 vs 36/34 is a large gap).
- **fix:** Increase the overview headline toward ~30-34sp and the detail headline toward ~28-30sp (scaled for mobile) rather than 26sp.

### [Insights Report + Deep dive] Heatmap cell aspect ratio and corner radius differ from mockup
- **kind:** visual
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:99
- **detail:** Confirmed against InsightsScreen.kt:99: Box(...aspectRatio(1f).clip(RoundedCornerShape(6.dp))...background(lerp(c.bg2, c.green, 0.2f + 0.7f*t))). The mockup mixes green at 20%+intensity*60% of bg-2 (insights.jsx:465) with aspectRatio '1.15 / 1' and borderRadius 12 (lines 471-473). The Android green ramp (0.2..0.9 lerp toward green) closely matches the mockup's 20-80% green mix; the visible divergence is the 1:1 vs 1.15:1 aspect ratio and 6dp vs 12dp radius. (Web TimeHeatmap differs again — aspectRatio '1.2 / 1', radius 6, mixes primary/coral at v*70%, deep-dive.tsx:135-145 — but the finding correctly treats the mockup as the source of truth.)
- **fix:** Use aspectRatio(1.15f) and RoundedCornerShape(12.dp) to match insights.jsx:471-473; keep the green ramp.

### [Insights Report + Deep dive] reasonLogs collected by ViewModel but never read in this screen — pause analytics never shown
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt:44-46
- **detail:** Confirmed. AppViewModel exposes reasonLogs (AppViewModel.kt:56: val reasonLogs = sf(store.reasonLogs())) and core pauseAnatomy(reasonLogs) exists (Analytics.kt:112). InsightsScreen.kt:44-46 collect only vm.sessions, vm.tasks, vm.captures — reasonLogs is never collected, so the 'What pauses you' panel cannot render. This is the data dependency behind the missing deep-dive pause panel (finding #3) and overlaps it; keeping it as the explicit root-cause note.
- **fix:** Collect vm.reasonLogs via collectAsStateWithLifecycle and feed pauseAnatomy(reasonLogs) into a 'What pauses you' panel as in deep-dive.tsx:467.

### [Settings hub + subpages + Areas] Areas: missing inline-add helper card and the 'Why no nested projects?' explainer; intro sentence is shortened
- **kind:** missing
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:140-168
- **detail:** Confirmed. AreasContent (SettingsScreen.kt:140-168) shows only the intro text, the area list, and a bare text field + 'Add' button. It omits both bg2 info cards present in the mockup (settings-areas.jsx:93-115) and web (life-area-panel.tsx:94-135): 'Add new areas inline from your dashboard — the + next to the area pills. Add one here instead' and 'Why no nested projects? ADHD memory works better with shallow hierarchies...'. The intro is also shortened to 'Areas filter the same list — flat on purpose.' (line 146) versus the fuller 'Areas filter the same list — not folders, not projects. Flat on purpose.'
- **fix:** Add the two bg2 explainer cards below the add control and restore the full intro sentence.

### [Settings hub + subpages + Areas] Area row blurb is hardcoded 'Custom area.' and color-tinted chip is smaller than the mockup
- **kind:** visual
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:151-154
- **detail:** Confirmed with a caveat. SettingsScreen.kt:154 hardcodes 'Custom area.' for every area. The mockup settings-areas.jsx itself also shows 'Custom area.' for all rows, so this is NOT a mockup-fidelity gap — it is only a WEB-parity gap (life-area-panel.tsx:17-23 maps seeded areas Work/Personal/Home/Health/Volunteering to descriptive blurbs and falls back to 'Custom area.' only for user-created ones). The chip-size half IS a real mockup gap: mockup icon box is 42x42 / radius 11 with a 14px dot at 28% color-mix (settings-areas.jsx:12-20); Kotlin uses ColorChip(box=30, dot=9) (line 151), so the tile is noticeably smaller.
- **fix:** Bump the color chip toward ~42dp box / radius 11 with a ~14dp dot to match the mockup. Optionally add an AREA_BLURBS map for seeded areas (web parity) with 'Custom area.' fallback, but note the static mockup uses 'Custom area.' everywhere.

### [Settings hub + subpages + Areas] Sub-screen AppBar title surfaces the raw enum name ('A11y' instead of 'Accessibility')
- **kind:** visual
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:90
- **detail:** Confirmed. SettingsSubScreen AppBar title (SettingsScreen.kt:90) is `section.name.lowercase().replaceFirstChar { it.uppercase() }`, so the A11Y section renders 'A11y' in the title bar. The eyebrow is correct ('SETTINGS · ACCESSIBILITY', from section.eyebrow). All other sections happen to read fine; only A11Y is wrong. The hub list (HUB, lines 58-62) already uses the friendly label 'Accessibility', so the data exists.
- **fix:** Use a friendly label for the AppBar title (e.g. derive from section.eyebrow by stripping the 'SETTINGS · ' prefix and title-casing, or reuse the HUB label / add a displayName field) instead of section.name.

### [Settings hub + subpages + Areas] Focus 'Soft overrun' uses 'Off' and bare numbers instead of the mockup's 'Never / 5 min / 10 min'
- **kind:** visual
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:98-99
- **detail:** Confirmed and stronger than stated. SettingsScreen.kt:99 uses listOf("Off", "5", "10") and line 98 uses listOf("15", "25", "45"). Mockup OVERRUN options are ['Never','5 min','10 min'] (settings-extra.jsx:115) and lengths ['15 min','25 min','45 min'] (line 111); web OVERRUN_OPTS/FOCUS_LENGTHS match (settings-panel.tsx:26-27). Additionally, FocusTimer.overrunGraceSeconds (core/FocusTimer.kt:50-60) maps exactly 'Never'/'5 min'/'10 min' -> infinity/300/600, so the current 'Off'/'5'/'10' labels would also fail that mapping if/when the pref is ever wired.
- **fix:** Match labels exactly: Default length ['15 min','25 min','45 min'] and Overrun ['Never','5 min','10 min'] — this also makes the values compatible with FocusTimer.overrunGraceSeconds.

### [Auth (sign in / sign up) + Onboarding stepper] Auth field labels/inputs don't match the mockup's stacked-eyebrow labeled-field style
- **kind:** visual
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:80-82
- **detail:** Mockup auth inputs (lines 42-52, authInputStyle 76-81) use an external mono eyebrow label above each input ('EMAIL'/'PASSWORD', 11px, ink3, 0.08em) with the input below at radius 10, 12x14 padding, fontSize 15. Android uses MdField — an M3-style bordered field with a floating notched label at radius 6 and 14sp text (Controls.kt:48,54,63-64). Different label placement (notch vs stacked mono eyebrow) and radius (6 vs 10). Confirmed against Controls.kt. Genuinely low — MdField is an accepted design-system component.
- **fix:** For pixel fidelity, render a SectionLabel ('EMAIL'/'PASSWORD') above each field and use a plain bordered input at radius 10 matching authInputStyle, instead of MdField's floating-notch style. Low priority.

### [Auth (sign in / sign up) + Onboarding stepper] Magic-link has no 'link sent' confirmation feedback
- **kind:** behavior
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:99
- **detail:** AuthScreen.kt:99 magic-link is an ink3 text link that calls run { vm.magicLink(...) }. run() (lines 49-58) sets busy and on AuthOutcome.Ok does nothing (empty branch line 53), surfacing only errors — there is no positive 'link sent' confirmation, unlike the web magic-link-sent route. Confirmed in AuthScreen.kt. The 'busy …' indicator only appears on the main submit button, not the magic-link link, so the magic-link tap gives essentially no feedback on success.
- **fix:** After a successful magicLink AuthOutcome.Ok, set message to a confirmation (e.g. 'Magic link sent to {email}') and render it in a non-error color, rather than the empty Ok branch silently returning to the form.

### [Auth (sign in / sign up) + Onboarding stepper] Onboarding first task is hardcoded to lifeArea 'Personal' even if Personal was deselected
- **kind:** bug
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:61
- **detail:** finish() calls vm.addTask(name = firstTask.trim(), estimateMin = 15, lifeArea = "Personal") (OnboardingScreen.kt:61) unconditionally. The user can remove 'Personal' from pickedAreas in step 1 (line 87 toggles removal), so the task can be filed under a life area absent from their set. Verified addTask signature has lifeArea: String? = null (AppViewModel.kt:89), so null/unset is valid. The mockup addTask (line 92) assigns no life area at all. (Also minor: mockup uses estimateMin 25, Android 15 — not part of this finding.)
- **fix:** Default the first task's lifeArea to pickedAreas.firstOrNull() or leave it null, rather than hardcoding 'Personal'.

### [Auth (sign in / sign up) + Onboarding stepper] Onboarding card radius and step titles diverge from mockup OnbStep spec
- **kind:** visual
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:73-101
- **detail:** Mockup OnbStep (lines 206-217) uses borderRadius 18, boxShadow, padding '28px 30px', and a uniform title fontSize 30 for all steps (h2, line 216). OnboardingScreen.kt:73 uses RoundedCornerShape(24.dp) with padding 22h/24v, and inconsistent serif sizes: step 0 serifItalic(32) (line 77) but steps 1/2/else serifItalic(26) (lines 82,94,101). Confirmed: mockup is uniform 30, Android is 32/26 and uses radius 24 with no shadow.
- **fix:** Use a single serifItalic(~30) for all step titles, set the card radius to 18 and padding ~28/30 to match OnbStep.

### [Auth (sign in / sign up) + Onboarding stepper] Onboarding treatment row radius/padding off, and uses a literal '✓' glyph
- **kind:** visual
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:110-115
- **detail:** Mockup treatment rows (lines 168-185): radius 12, padding '14px 18px', active background var(--u-ink), inactive desc var(--u-ink-3), active desc rgba(255,255,255,0.7), check via Icon.check vector. OnboardingScreen.kt:110 uses RoundedCornerShape(14.dp) with padding 16h/13v, and line 115 uses a literal Text('✓') instead of a check vector. Colors/opacity (line 113, alpha 0.7) match. Confirmed; minor.
- **fix:** Set treatment row radius to 12 and padding to ~18h/14v; optionally replace the literal '✓' glyph with a check ImageVector for consistent weight.

### [Auth (sign in / sign up) + Onboarding stepper] Web vs Android/mockup onboarding flows differ (informational)
- **kind:** behavior
- **file:** app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:50-128
- **detail:** This finding states the real web onboarding (components/onboarding/flow.tsx) is a different ADHD-struggle 5-step flow, while the mockup onboarding-auth.jsx and Android implement a 4-step Welcome/areas/first-task/treatment flow. I confirmed Android faithfully follows the mockup's 4-step structure (OnboardingScreen.kt steps 0-3 match mockup OnbStep eyebrows 'STEP 1..4 OF 4'). I did NOT independently open components/onboarding/flow.tsx, so the specifics of the web flow are unverified — but the actionable point (Android matches the mockup; completeOnboarding is called with emptyList() at lines 62 and 122, discarding any struggles capture the API supports) is confirmed against AppViewModel.kt:211 which accepts a struggles list.
- **fix:** Decide which flow is canonical. If the mockup wins (which Android follows), no change. If web behavior is desired, capture struggles (completeOnboarding already accepts a list but is passed emptyList() at lines 62/122) and start a focus session at finish.

### [Command palette + Avatar menu (Android)] Result ordering and cap differ from web/mockup
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:57-61
- **detail:** cmdk.jsx:42-48 builds [actions, ...tasks, ...collections] then slices to 12 total; web command-palette.tsx:104-112 scores all candidates (score()) by relevance and slices to 12. Android CommandPalette.kt:57-61 caps tasks at .take(8) and notes at .take(4) independently, builds results as taskResults + noteResults + actions (actions pinned last, no relevance sort, no unified cap), so e.g. typing 'settings' buries the matching action below tasks/notes. Confirmed.
- **fix:** Combine all candidate kinds, rank by a relevance score (exact > prefix > contains), then take the top 12 instead of fixed per-kind caps with actions pinned last.

### [Command palette + Avatar menu (Android)] No 'esc' key-hint chip; and system back does not dismiss the palette
- **kind:** missing
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:69
- **detail:** cmdk.jsx:107 (<Kbd>esc</Kbd>) and web command-palette.tsx:188 (span.u-kbd 'esc') render a trailing key hint. Android uses a 'Cancel' text link (CommandPalette.kt:69). Verified there is NO BackHandler anywhere in the ui/ tree (grep found none) and MainScaffold has no back handling for the route stack, so only the Cancel tap dismisses the palette — the hardware back button currently does not. The Kbd chip itself is a fine touch adaptation; the back-dismiss gap is the more useful part of the fix.
- **fix:** Keep the 'Cancel' affordance, but add a BackHandler (in the palette or the MainScaffold overlay stack) so the system back button also pops/dismisses, matching the esc-to-close behavior on web.

### [Command palette + Avatar menu (Android)] Sign out lacks a pending/disabled state
- **kind:** behavior
- **file:** /Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/components/AvatarMenu.kt:50
- **detail:** Web shows 'Signing out…' and disables the button while the async signOut runs (settings-panel.tsx:140-149, setSigningOut). AvatarMenu.kt:50 calls vm.signOut() then immediately onDismiss(); vm.signOut() (AppViewModel.kt:235) is fire-and-forget via launchWrite, so there is no pending text or disabled state and a slow network gives no feedback. Confirmed.
- **fix:** Track a signingOut flag, show 'Signing out…' on the row and disable it until sign-out completes. Minor: the menu is a bottom sheet dismissed immediately, so this is low priority on mobile.

