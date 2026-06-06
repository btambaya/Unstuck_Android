# Web → Android feature parity (re-scan 2026-06-06)

**Web app = source of truth.** Android re-verified against *current* code (after the shared-collections + two-way-calendar build-out and the 49-bug + 107-bug sweeps). Supersedes the 2026-05-29 scan (189 gaps).

Method: 16 parallel agents, one per feature category, each diffing the web spec against the live Android Kotlin (workflow `android-web-parity-rescan`).

## Rollup

- **64 remaining gaps** across 16 categories (was **189** on 2026-05-29 — 122 missing / 49 partial / 18 broken).
- By severity: **2 HIGH · 25 MED · 37 LOW**.  By status: 2 broken · 23 missing · 39 partial.
- **HIGH (2)** — must-do for full parity:
  - _Focus mode_ — Overrun check-in UI and Extend action missing — user cannot extend the estimate
  - _Captures & reason logs (create, inbox triage, reflection)_ — No re-entry / context-rebuild screen (web ResumeSupport / /interrupt)
- **Broken (2)**:
  - _Calendar scheduling_ — Drag-to-reschedule a block does not bump moveCount
  - _Life areas (CRUD, color tokens, rename/recolor cascade)_ — 'teal' color token renders gray on Android (incl. seeded Health area)

Android is at functional parity on the core flows; the remainder is mostly polish (per-tag colours, chart/observation richness, framing copy) plus a few feature pockets (calendar week/month drag, focus overrun-extend, re-entry screen, recurrence Until date).

---

## Focus mode: treatments, pause-reasons, captures, overrun, live session  (6 gaps · 1 HIGH)

**Parity:** Core focus loop is at parity — three treatments, pause/resume, pause-reasons, captures, save-for-later/end-for-now/done, a foreground live-session notification, and overrun state derivation all match. The notable divergence is the overrun experience: Android derives the OVERRUN state but only recolors the timer — it has no overrun check-in UI and never wires up the extend ("Add 10 min") action, so users cannot extend an estimate. Treatments are also cosmetic-only (cockpit/monk lack their distinct rails/panels).

**Now matched (closed gaps):**
- Three focus treatments (ambient/cockpit/monk) with an in-screen switcher, persisted to settings
- Pause/resume with sessionStart shifted past the pause gap (FocusTimer.kt ports the web hook 1:1)
- Pause-reasons sheet with the same 5 reasons, written to reason logs
- Capture sheet with the same 5 tags + soft-bg/dark-ink color pairs, bound to the live session id
- Overrun state derivation honoring the Soft-overrun setting (Off/5/10 min) instead of a hardcoded grace
- Save for later (pauses + exits, resumable) + End for now (records session) + Done (marks complete) — the web's three finish paths
- Foreground-service live notification (running coral / paused amber) as the live-session analog, persisting after leaving the screen
- priorAccumulatedSec continuity so reopening a task after End-for-now continues the displayed timer
- Ambient noise loop honoring the ambient (off/brown/pink) setting
- Paused-too-long check-in scheduler
- Session-end recap card + interruption-reason on Save-for-later

**Remaining gaps:**
- **[HIGH · missing] Overrun check-in UI and Extend action missing — user cannot extend the estimate**
  - Web's overrun state renders a soft check-in ('still going well?') plus three buttons: 'Add 10 min · keep going' (onExtend(10)), 'I'm in the zone' (onExtend(15)), and 'Stop here'. Android derives FocusState.OVERRUN but the ONLY thing it does with it is recolor the timer text coral (FocusScreen.kt:172). The control row is identical in every state (Capture / Pause / Done). vm.extendFocus() exists in AppViewModel.kt:362 but is dead code — no button calls it — so an Android user who goes over estimate has no way to extend the session's estimate; it just stays overrun-colored indefinitely.
  - web: `components/focus/controls.tsx:131-150` · android: `app/.../ui/focus/FocusScreen.kt:172,181-189`
- **[MED · missing] No soft-exit confirmation when leaving mid-session**
  - Web honors PREF_FOCUS_SOFT_EXIT: while a session is running, ESC/exit triggers a window.confirm('Leave focus mid-session? Your timer keeps running.'). Android persists the focusSoftExit setting (SettingsStore.kt:55, toggled in SettingsScreen.kt:142) and reads `settings` into the screen, but FocusScreen never consults it — the '← Out' button calls onClose() immediately with no confirm. The setting is effectively a no-op on Android.
  - web: `components/focus/focus-mode.tsx:64-69` · android: `app/.../ui/focus/FocusScreen.kt:122-126`
- **[MED · missing] firstPhysicalAction not surfaced in Focus**
  - All three web treatments surface the task's firstPhysicalAction prominently during focus (Ambient as a 'First physical action ·' line, Cockpit as a 'FIRST PHYSICAL ACTION' card, Monk as the body line). Android's FocusScreen only shows task.name + '{estimateMin}m estimate' and never renders firstPhysicalAction, even though the field is fully supported in task create/detail (NewTaskSheet/TaskDetailSheet). The 'smallest concrete step' nudge that anchors the web focus screen is absent.
  - web: `components/focus/treatments/cockpit.tsx:142-166` · android: `app/.../ui/focus/FocusScreen.kt:168-171`
- **[MED · partial] Treatments are cosmetic — Cockpit lacks Up-Next panel and in-session capture promotion; Monk not minimal**
  - Web Cockpit has a dedicated right rail: 'CAPTURES THIS SESSION' as tagged cards each with a 'Promote to task →' action, plus an 'UP NEXT' panel (pickStartNext) showing the next queued task. Android renders one FocusScreen for all treatments; in COCKPIT it only adds a flat 3-line 'Captures' list (CapturesRail, no tags, no promote, no Up-Next). Monk on web strips the switcher/wordmark/extra chrome; Android Monk keeps the same switcher row, controls, and secondary actions — only hiding the task name/ring. The three treatments differ mainly by which blocks are shown, not by being genuinely distinct layouts.
  - web: `components/focus/treatments/cockpit.tsx:262-424` · android: `app/.../ui/focus/FocusScreen.kt:104,175-177,249-258`
- **[LOW · missing] No calibration hint on the idle/start screen**
  - Web Ambient builds a calibration hint from past same-lifeArea sessions ('Last N {area} tasks averaged X min · estimate looks reasonable.') shown under the idle estimate. Android computes calibration data only for the Insights screen (calibrationDots/calibrationHitRate) and shows no estimate-confidence hint when entering focus.
  - web: `components/focus/treatments/ambient.tsx:16-27,150-158` · android: `—`
- **[LOW · partial] Done screen reduced — no 'Pick next task' / 'Save & continue later' choices**
  - Web's terminal 'done' state offers three actions (Mark complete / Save & continue later / Pick next task) on a dedicated summary screen with the elapsed total. Android instead writes the session immediately on Done/End-for-now, shows a ReflectSheet ('How did that land?' — a momentary, non-persisted reflection), then closes the focus screen. There's no in-flow 'pick the next task' continuation; the user returns to Today. Functionally close (session is recorded either way) but the next-task hand-off is missing on mobile.
  - web: `components/focus/controls.tsx:152-164` · android: `app/.../ui/focus/FocusScreen.kt:188,203,209`

## Captures & reason logs (create, inbox triage, reflection)  (3 gaps · 1 HIGH)

**Parity:** At near-complete parity: capture create, reason logs, inbox triage, per-task captures, and promote all match web (Android even adds a persisted reflect sheet). The one material divergence is the missing dedicated re-entry / context-rebuild screen (web's resume-support.tsx / /interrupt); two other gaps are minor (inbox tag filter, single-line capture field).

**Now matched (closed gaps):**
- Capture composer with the same 5 tags + colors (follow-up/idea/edit/question/distraction), session-linked via sessionId (CaptureSheet.kt + saveCapture)
- Reason logs fully wired: PauseReasons sheet with the same 5 reasons, persisted via saveReasonLog → ReasonLog/ReasonAction model, plus a Settings 'Pause reasons' toggle (focusPauseReasons)
- Capture Inbox triage surface: promote / open / archive (device-local archivedCaptureIds) / discard, with an Archived view — mirrors web /inbox
- Per-task Captures section in the task detail pane: list + Promote + Discard + inline Add (TaskDetailSheet.kt), matching web task-detail-pane
- In-session captures rail in the cockpit treatment (FocusScreen CapturesRail) like web cockpit sessionCaptures
- promoteCapture semantics match web: capture preserved, new task seeded lifeArea 'Work' + tags ['from-capture', <tag>]
- End-of-session ReflectSheet (Android extra; web stores nothing for reflection)
- Notification 'Capture' action deep-links straight into the capture sheet (autoCapture)

**Remaining gaps:**
- **[HIGH · missing] No re-entry / context-rebuild screen (web ResumeSupport / /interrupt)**
  - Web has a dedicated re-entry surface (its self-described 'differentiator'): 'you left X ago', 'where you left off' from firstPhysicalAction, a 'N captures waiting · no pressure' list with Promote, a tight-deadline warning comparing remaining estimate vs the next calendar block, and Resume / Reschedule rest / Pick something else actions. Android resumes a paused session only via a compact amber 'Paused + Resume' card on Today that taps back into Focus — it rebuilds none of that context, despite having all the underlying data (firstPhysicalAction, captures, blocks).
  - web: `components/interruption/resume-support.tsx:55; app/interrupt/page.tsx:11` · android: `app/.../ui/today/TodayScreen.kt:328 (LiveSessionCard only)`
- **[LOW · partial] Inbox lacks tag filter chips**
  - Web inbox offers an 'All' chip plus one filter chip per capture tag (follow-up/idea/edit/question/distraction) to narrow the triage list. Android InboxScreen shows only the To-process/Archived toggle — no per-tag filtering.
  - web: `app/(product)/inbox/page.tsx:79-82 (FilterChip: All + per-tag)` · android: `app/.../ui/inbox/InboxScreen.kt:72-89 (only an archived toggle)`
- **[LOW · partial] Capture composer is single-line vs web multi-line textarea**
  - Web capture field is a 4-row textarea built for multi-line thoughts (with ⌘↵ to save / Esc to cancel hints). Android's CaptureSheet BasicTextField is singleLine = true, so longer captures can't wrap to multiple lines during entry.
  - web: `components/focus/capture-modal.tsx:159 (textarea rows=4, Cmd/Ctrl+Enter to save)` · android: `app/.../ui/focus/CaptureSheet.kt:53 (BasicTextField singleLine = true)`

## Calendar scheduling: day/week/month, drag-to-schedule, block edit, unscheduled tray  (9 gaps)

**Parity:** The day view is at near-parity (drag-to-schedule, drag-to-reschedule with lanes, NOW line, click-to-create, block-edit sheet, unschedule, two-way Google sync all present). The real remaining divergences are concentrated in the week/month views (display-only, no drag-to-schedule), a thinner block-edit sheet vs the web modal, a missing Auto-sequence, and a drag-reschedule that doesn't bump moveCount.

**Now matched (closed gaps):**
- Day grid with absolute-positioned blocks + overlapping-lane layout (layoutLanes mirrors web)
- Drag-to-schedule: long-press an unscheduled task and drop on an hour slot (15-min snap)
- Drag-to-reschedule an existing block to a new slot/day with a follow-finger ghost
- NOW line ticking on today's grid + auto-scroll to ~1h before now
- Click/tap empty grid to create a task prefilled with that date+time (onCreateAt -> NewTaskSheet)
- Block-edit bottom sheet: change start time, resize duration, unschedule
- Per-day-view day navigation (prev/next/Today) with cross-midnight roll-forward
- Week view with Monday-anchored 7-col grid, planned/busiest/lightest rollups, lane layout
- Month view with focus-density heat cells from real sessions
- Connect / Sync now / Disconnect Google Calendar with external (blue) blocks shown read-only
- Recurrence-aware scheduling (regenerateForTask) on schedule/reschedule
- moveCount bump on schedule/reschedule via scheduleTask (drag-from-tray path)

**Remaining gaps:**
- **[MED · broken] Drag-to-reschedule a block does not bump moveCount**
  - On web, dragging an existing block to a new slot/day bumps the owning task's move_count so the slip/analytics detector counts every reschedule. Android's drag path calls vm.moveBlock, which does a plain upsertCalBlock with NO bumpMoveCount, so calendar drag-reschedules (and the edit-sheet's start-time chips) silently undercount moves vs web. Note scheduleTask (tray-drag and re-schedule) DOES bump moveCount, so this only affects the moveBlock path.
  - web: `components/calendar/today-timeline.tsx:161-170 and week-full.tsx:253-263 (bumpMoveCount on drag move); month-full.tsx:176-184` · android: `app/.../ui/calendar/DayGrid.kt:172-176 (dropBlock -> vm.moveBlock); AppViewModel.kt:216-218 (moveBlock = plain upsertCalBlock)`
- **[MED · missing] Week view is display-only — no drag-to-schedule or drag-to-reschedule**
  - Web week grid accepts drops from the tray to schedule and drag-moves existing blocks between days plus bottom-edge resize. Android WeekView only renders blocks; tapping a task block opens the task detail (onOpen) but there is no drop target, no block drag, no resize. Scheduling/rescheduling in week view is impossible on Android.
  - web: `components/calendar/week-full.tsx:543-642 (onDrop/onDragOver per day column, tomDrop), :631-637 (ResizeHandle)` · android: `app/.../ui/calendar/CalendarScreen.kt:194-225 (WeekView blocks)`
- **[MED · missing] Month view is density-only — no drag-to-schedule onto a day**
  - Web month cells are drop targets: dropping a tray task schedules it at 09:00 on that date, and dropping an existing block moves it to that date (bumping moveCount). Android month cells are non-interactive heat squares (not even clickable to open that day); you cannot schedule or move anything from the month view.
  - web: `components/calendar/month-full.tsx:262-265, :169-198 (onCellDrop schedules dropped task at 09:00 / moves block to that date)` · android: `app/.../ui/calendar/CalendarScreen.kt:271-288 (MonthView cells)`
- **[MED · partial] Block-edit sheet is far thinner than the web block-edit modal**
  - Web's in-calendar editor edits name, first physical action, area, estimate, tags, an explicit date AND time input, shows a live conflict pill, and offers Start now / Mark complete / Open in tasks / Delete. Android's sheet only offers start-time chips, duration chips (15/25/45/60/90), and Unschedule — no name/area/tags/first-action editing, no conflict warning, no Start-now/Mark-complete. (Those edits exist elsewhere via TaskDetailSheet, but not from the calendar block.)
  - web: `components/calendar/cal-block-edit-modal.tsx:211-415 (name, first physical action, estimate, area picker, TagPicker, date+time, conflict warning, Start now, Mark complete, Open in tasks, Delete block)` · android: `app/.../ui/calendar/DayGrid.kt:346-379 (CalBlockEditSheet)`
- **[MED · partial] Block-edit sheet cannot move a block to a different day, and start times are limited to suggested free slots**
  - From the web modal the user can retarget a block to any date and any clock time. The Android sheet's start-time chips are derived from findFreeSlotsForDate for live.date only, so the sheet can never move a block to another day and only offers a handful of algorithmic time slots (plus the current one). Cross-day moves are only possible via long-press drag, not via the edit sheet.
  - web: `components/calendar/cal-block-edit-modal.tsx:349-360 (free type=date + type=time inputs — any day, any time)` · android: `app/.../ui/calendar/DayGrid.kt:356-368 (findFreeSlotsForDate(...live.date...) -> moveBlock(live, live.date, t))`
- **[LOW · missing] Week view has no per-day unscheduled tray / 'drag into your week'**
  - Web week sidebar lists up to 6 unscheduled tasks as draggable chips. Android WeekView shows only the 3 rollup stats (Focus planned / Busiest / Lightest); there is no tray and no way to plan from the week view.
  - web: `components/calendar/week-full.tsx:351-411 (DRAG INTO YOUR WEEK list)` · android: `—`
- **[LOW · missing] No Auto-sequence in the unscheduled tray**
  - Web's tray has an amber Auto-sequence button that packs up to 6 unscheduled tasks into 08:00–12:00 gaps avoiding existing blocks. Android's tray is a horizontal chip strip ('name · Nm') with drag only — no Auto-sequence, no unscheduled count, no AreaDot/grip styling, no morning-sequencing nudge.
  - web: `components/calendar/unscheduled-tray.tsx:35-76, :153 (Auto-sequence packs tasks into morning gaps)` · android: `app/.../ui/calendar/DayGrid.kt:292-318 (tray)`
- **[LOW · missing] No drop hint / 'Drop here · HH:MM' affordance while dragging**
  - Web shows a dashed 'Drop here · 9:15' target row that previews the snapped landing time as you drag. Android shows a coral label ghost following the finger but no in-grid snapped-time preview, so the user doesn't see the target time until after the drop.
  - web: `components/calendar/today-timeline.tsx:127-138, :332-354 (dropHint ghost with snapped time)` · android: `app/.../ui/calendar/DayGrid.kt:321-338 (drag ghost is just the task label)`
- **[LOW · missing] Day view shows no scheduled-hours total / 'What realistically fits?' framing**
  - Web's day header shows the realistic-capacity framing plus a running 'Nh scheduled' total for the day. Android's day view header is just ‹ / date / › with no scheduled-hours summary.
  - web: `components/calendar/today-timeline.tsx:263-282 (heading + 'Nh scheduled' total)` · android: `app/.../ui/calendar/DayGrid.kt:181-189 (day switcher only)`

## Life areas (CRUD, color tokens, rename/recolor cascade)  (2 gaps)

**Parity:** At functional parity: Android does full life-area CRUD with the same rename- and delete-cascade onto tasks, server-persisted via the outbox, and a full id/name/color/sort_order round-trip. The only real divergences are in the color palette — Android offers 6 of web's 8 color tokens (no teal, no red), and its token resolver has no teal case, so any area carrying web's seeded teal color renders gray on Android.

**Now matched (closed gaps):**
- Add area in Settings with inline name field + color picker (dedupe by name, sortOrder = max+1, first-unused color)
- Rename area with cascade: renames the new name onto every task tagged with the old area name (AppViewModel.renameLifeArea, web cascadeRenameTaskArea parity)
- Delete area with cascade: clears the area label off every task (sets lifeArea=null) + confirm dialog
- Recolor area via per-row color-chip dropdown, persisted to server
- Duplicate-name guard (case-insensitive) on both add and rename
- Per-area open-task count shown on each row
- Full server persistence + realtime: upsertLifeArea/deleteLifeArea write through to life_areas with id/name/color/sort_order codec
- Canonical area seeding on first run / empty-areas fallback (Work/Personal/Home/Health)

**Remaining gaps:**
- **[MED · broken] 'teal' color token renders gray on Android (incl. seeded Health area)**
  - Web seeds the canonical 'Health' area with color 'teal' (use-life-areas.ts COLOR_FOR.Health) and resolveAreaColor maps teal → oklch(0.70 0.10 200). Android's UTheme.colors.areaColor() has no 'teal' branch, so it falls through to the gray ink4 fallback. Any area created/seeded as teal on web shows a gray dot/chip everywhere on Android (areas list, dashboard area pills, task badges), instead of the teal it has on web.
  - web: `components/ui/area-dot.tsx:31` · android: `design/src/main/kotlin/tech/csalliance/unstuck/design/theme/Theme.kt:42`
- **[MED · partial] Area color palette missing 2 of web's 8 tokens (teal, red)**
  - Web exposes 8 selectable color tokens (COLOR_TOKENS = indigo, coral, green, amber, teal, blue, violet, red) in both the add form and the per-area recolor menu. Android's Areas screen palette is only 6 (indigo, coral, violet, green, amber, blue) for both add and recolor — teal and red can never be chosen on Android. A user who picks teal or red for an area on web can't reproduce or re-select it on Android.
  - web: `components/ui/area-dot.tsx:40` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:322`

## Today / Start-Next / Up-Next / dashboard  (6 gaps)

**Parity:** Core selection/visibility logic (pickStartNext, visibleTasks, task-bucket, resume-aware start) is a faithful, at-parity port; the remaining divergences are all presentation-layer: the Start-Next hero and Today rows surface less per-task context than web (no firstPhysicalAction headline/hint, no rationale chip, no recurrence/schedule glyphs, no rich paused-row), and the desktop-only Up-Next list has no Android surface.

**Now matched (closed gaps):**
- pickStartNext ranker ported exactly (done+later+live excluded, area filter, priority→estimate→createdAt)
- visibleTasks Today/Backlog buckets ported (scheduled-or-created-today, overdue/past-only backlog, area-agnostic Today)
- Backlog pill with amber accent + count + clears area filter on enter (web parity)
- Today shows completed-today tasks sorted last; minute ticker rolls over at midnight
- Live focus session rendered as rich card on Today with progress ring, running/paused branch, Pause/Resume + Return-to-focus
- Start-Next card start is authoritative + resume-aware (startFocus seeds priorAccumulatedSec=totalFocused)
- Per-task age chip ('Nd') in Backlog view
- Tags rendered inline on Today rows
- Greeting header, week-focused stat, recap card (6h expiry), and nudge card all present

**Remaining gaps:**
- **[MED · missing] Start-Next hero missing 'Pick another' action**
  - Web hero has both 'Pick another' (routes to /tasks) and 'Start now'. Android hero has only the 'Focus' button — no way to reject the suggestion and pick a different task from the card itself.
  - web: `components/dashboard/start-next-card.tsx:170, app/(product)/dashboard/page.tsx:100` · android: `app/.../ui/today/TodayScreen.kt:305-325`
- **[MED · partial] Start-Next hero ignores firstPhysicalAction as the headline**
  - Web headline = firstPhysicalAction || name (the smallest concrete step is the calming focal point) with an 'area · name' eyebrow above it. Android StartNextHero renders task.name as the big headline and never surfaces firstPhysicalAction, so the whole 'do this one small thing' framing is lost even though the field exists on the Android TaskItem.
  - web: `components/dashboard/start-next-card.tsx:39-41,128-139` · android: `app/.../ui/today/TodayScreen.kt:317-319`
- **[MED · partial] No rich 'Paused' row for a non-live task with partial progress**
  - Web renders a dedicated rich card (progress ring + 'Paused · {name} · paused at MM:SS' + inline Resume button) for ANY non-live task whose totalFocused is between 0 and estimate. Android only renders the rich card for the actual live session; a paused-but-not-live task appears as a plain standard row with no progress ring and no inline Resume — the user must open the task and tap Focus. Resume itself is correct (startFocus is resume-aware), so this is a visibility/affordance gap, not data loss. Minor related nit: the live paused-card secondary text reads '{estimateMin}m · paused' on Android vs web's 'paused at MM:SS'.
  - web: `components/dashboard/task-row.tsx:40-44,68-69,113-149` · android: `app/.../ui/today/TodayScreen.kt:268-277,380-416`
- **[LOW · missing] Start-Next hero missing AI rationale chip + 'low friction' / 'nothing else right now' framing**
  - Web shows an 'AI · suggested' chip carrying rationaleFor() (e.g. 'Best fit for a 25-min gap before Standup'), a 'Low friction' indicator, and the 'First physical action. Nothing else right now.' subtext. Android hero shows none of these; there is no Kotlin port of start-next-rationale and no rationale surfaced anywhere.
  - web: `components/dashboard/start-next-card.tsx:105-109,145-167; lib/start-next-rationale.ts` · android: `app/.../ui/today/TodayScreen.kt:305-325`
- **[LOW · missing] No 'Up next' queued-tasks list**
  - Web surfaces the next ~3 ranked tasks (pickUpNext) in the right-rail context zone with 'after this · Nm' / 'today · Nm' labels. Android ported pickUpNext into core but no UI calls it — no Up-Next list on Today, focus, or anywhere. Low severity because web's Up Next lives only in the desktop right rail (hidden on mobile), which has no phone equivalent.
  - web: `components/dashboard/up-next.tsx:21-83, components/layout/right-rail.tsx:8-15` · android: `core/.../logic/PickStartNext.kt:43 (pickUpNext, unused)`
- **[LOW · partial] Today rows omit recurrence glyph, scheduled time, and firstPhysicalAction hint**
  - Web standard Today row shows the ↻ repeats glyph for recurring tasks, the task's scheduled start time (clock icon, from a today-only schedule map), and a '→ {firstPhysicalAction}' hint. Android TaskRow shows area + tags + estimate + optional age chip only — no recurrence indicator, no scheduled-time, no first-action hint, even though recurrence/firstPhysicalAction exist on the model.
  - web: `components/dashboard/task-row.tsx:335,343-345,357-361; today-list.tsx:32-42,250` · android: `app/.../ui/today/TodayScreen.kt:380-416`

## Task CRUD + detail (create/edit/delete/detail pane)  (5 gaps)

**Parity:** Android is at functional parity with web on the core Task CRUD + detail flow (create, full inline edit, delete-with-cascade, captures, recurrence, tags, scheduling) — it even adds a per-task reminder picker web lacks. The remaining gaps are detail-pane informational sections (status granularity, sessions/estimate-history detail, created-ago header) plus the recurrence editor's missing end-date control.

**Now matched (closed gaps):**
- Scheduler-first create: mandatory WHEN (Today/Tomorrow/Pick date/Later), free-slot time chips, conflict warning, capture drafts
- Inline-editable detail: name, first physical action, estimate (presets + custom dialog), area (incl. Unassigned), all via vm.updateTask
- Inline schedule date/time picker on detail (Schedule button + tappable Schedule cell), moves task out of Later
- Mark done / not done + Move out of Later
- Recurrence editor (daily/weekly + day toggles/monthly) wired to setRecurrence with cal_block regeneration plan (matches web RepeatEditor)
- Tag picker with curated vocabulary, search, toggle, and inline create (mirrors web TagPicker)
- Captures section: list with promote-to-task + discard, plus inline add-capture with tag chips
- Delete with confirm + cascade to cal_blocks and captures (deleteTask matches web deleteTask)
- Task list rows show area dot, recurrence indicator, tag chips, estimate, backlog-age chip (parity with web ListRow)
- Per-task pre-task reminder lead override in create sheet (Android-only addition beyond web)

**Remaining gaps:**
- **[MED · partial] Recurrence editor has no end-date (until) control**
  - Web lets you bound a recurrence with an 'Until' date in BOTH the create modal (task-create-modal.tsx recurrenceUntil) and the detail RepeatEditor (task-detail-pane.tsx), so materialization stops at the chosen date. Android's RecurrenceEditor explicitly omits until (comment: 'until is intentionally omitted from the editor (open-ended series)') and always emits Recurrence.Daily()/Weekly()/Monthly() with no until, so every recurrence created/edited on Android is open-ended. The model + recurrenceLabel still carry until for web/iOS-synced data, but an Android user cannot set or change it.
  - web: `components/tasks/task-create-modal.tsx:679-734 / components/tasks/task-detail-pane.tsx:644-781` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/components/RecurrenceEditor.kt:17-48`
- **[LOW · missing] 'Estimate history' calibration section missing**
  - Web detail has a dedicated 'Estimate history' card ('Estimated N min after K sessions. Calibration improving.' / initial-estimate copy). Android has no equivalent section.
  - web: `components/tasks/task-detail-pane.tsx:300-320` · android: `—`
- **[LOW · partial] Detail Status never shows 'In progress'**
  - Web shows three status states: 'Completed', 'In progress' when totalFocused > 0, else 'Not started'. Android's MetaCell shows only 'Completed' or 'Not started' — a partially-focused task reads as 'Not started'.
  - web: `components/tasks/task-detail-pane.tsx:186` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TaskDetailSheet.kt:162`
- **[LOW · partial] Sessions section is thinner and hidden when empty**
  - Web always renders a Sessions section with an 'N attempts' subtitle, an empty-state card, and per-session detail (time-ago label + 'MM:SS in focus' + 'est Nm'). Android only renders the section when sessions exist, with no count subtitle, no empty state, and a bare '• Nm focused' line (no estimate, no relative time), capped at 6.
  - web: `components/tasks/task-detail-pane.tsx:231-274` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TaskDetailSheet.kt:174-179`
- **[LOW · partial] Detail header omits 'CREATED <ago>' and estimate 'learned from N similar'**
  - Web detail header reads 'AREA · CREATED today/yesterday/N days ago' and the Estimate cell shows a 'learned from N similar' subtitle. Android header reads 'AREA · TASK' (no created-ago) and the estimate chips carry no learned-from subtitle.
  - web: `components/tasks/task-detail-pane.tsx:121,165-166` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TaskDetailSheet.kt:106,142-150`

## Insights / Analytics: report + deep dive  (5 gaps)

**Parity:** Near parity: the entire analytics derivation layer (Analytics.kt) is a verified 1:1 port of lib/analytics.ts with full test coverage, and both Report and Deep Dive screens render the same charts (stacked bars, interruption histogram, worth-noticing insights, heatmap, pause anatomy, re-entry, captures-by-kind, slip detector) with web-anchored calendar windows. The only real divergences are presentation-layer: Android omits the estimate-vs-actual calibration scatter chart entirely, gates Report charts behind the 5-session threshold instead of showing them as "sample", and drops a few cosmetic touches.

**Now matched (closed gaps):**
- Full Analytics.kt port of lib/analytics.ts (weekdayAreaHours, calibrationDots/hitRate, interruptionBins, timeOfDayHeatmap, pauseAnatomy, reEntryDistribution, slipping, captureBreakdown, topInsights) with 1:1 unit tests
- Week / Month / All window switcher with calendar-anchored cutoffs (Monday 00:00, 1st of month) matching analytics-window.ts
- Report vs Deep dive segmented toggle with matching REFLECTION eyebrow + window label
- Stacked weekday-by-area bars driven from the user's own life areas (custom/renamed areas included, unassigned dropped)
- Interruption-time histogram, worth-noticing insight cards, and 5-session real-data threshold gating
- Deep Dive: time-of-day heatmap, pause anatomy, re-entry distribution, captures-by-kind, slip detector, and median/on-estimate/re-entry/captures stat strip
- Median session computed over raw seconds (web-parity rounding fix) and re-entry <5m percentage

**Remaining gaps:**
- **[MED · missing] Estimate-calibration scatter chart is missing on Android**
  - Web renders a dedicated EstimateCalibration scatter (estimate-x vs actual-y, diagonal reference line, green/amber dots colored by within-5min, per-dot task-name tooltips) in the Report. Android computes calibrationDots() but only uses it for the hit-rate percentage StatCard ('On estimate' / 'Estimates') and never plots the dots — the entire visual chart and its outlier story are absent.
  - web: `components/analytics/report.tsx:153-229 (EstimateCalibration), :417` · android: `app/.../ui/insights/InsightsScreen.kt:79-80,99,139`
- **[MED · partial] Report charts hidden below threshold instead of shown as 'sample'**
  - Web always renders the stacked bars, calibration, and interruption charts even with <5 sessions — labeled with a 'sample' badge and guidance copy, so new users still see the shapes. Android gates all three Report charts behind `if (enough)` (5 sessions), so below threshold the user sees only the ThresholdNote + stat cards and no charts at all. No 'sample' badge concept exists on Android.
  - web: `components/analytics/report.tsx:309-419` · android: `app/.../ui/insights/InsightsScreen.kt:95-126`
- **[LOW · partial] Observation stat cards lack delta/tone-pill subtext parity**
  - Web's three observation cards carry a colored delta pill (e.g. 'N sessions tracked', 'M had a capture attached', 'Worth noticing.' / 'All clear.') with green/blue/amber tone. Android's StatCards convey similar numbers but use a flatter caption layout and slightly different labels ('Focus sessions' vs 'RE-ENTRIES'); the distinct colored delta-pill treatment and 'sessions resumed after interruption' framing are not fully reproduced.
  - web: `components/analytics/report.tsx:330-356` · android: `app/.../ui/insights/InsightsScreen.kt:99-104`
- **[LOW · partial] Slip-detector rows omit the life-area dot**
  - Web's Deep Dive slip detector shows an AreaDot (life-area color) next to each slipping task name plus a dashed amber border. Android's slip rows show name + 'Nx · Nw' but no area dot and no dashed-amber styling, so the area-at-a-glance cue is lost.
  - web: `components/analytics/deep-dive.tsx:278-324` · android: `app/.../ui/insights/InsightsScreen.kt:174-186`
- **[LOW · partial] Narrative sentences under Deep Dive panels not ported**
  - Web pairs every Deep Dive chart with an explanatory narrative line (the DDPanel right column: e.g. 'Walking-away pauses resume in under 3 min...', 'Half your re-entries happen within the first bin...', the heatmap density sentence, and the closing 'You've spent a few minutes reading this...' wave note). Android renders the charts with only a section label and no accompanying narrative copy.
  - web: `components/analytics/deep-dive.tsx:447-510` · android: `app/.../ui/insights/InsightsScreen.kt:147-187`

## Settings & preferences (every toggle/section)  (4 gaps)

**Parity:** Settings is at near-full parity: every web section (Account, Focus, Sound, Accessibility, Interface, Backup) plus Areas/Tags management is present, and Android actually exceeds web on notifications (a 3-level Calm/Balanced/Coach model + reminder lead with real OS scheduling vs web's single push toggle). The few remaining divergences are the Focus-mode-treatment picker (live-only, not in Settings), no standalone nudges toggle, and a thinner Backup section.

**Now matched (closed gaps):**
- Account: display-name edit, password add/change with current-password re-auth, sign out, export JSON, delete-account with type-to-confirm
- Focus section: default length (15/25/45), soft overrun (Off/5/10), collapse-rail / soft-exit / pause-reasons toggles
- Sound section: start chime, overrun bell, completion sound, ambient (off/brown/pink)
- Accessibility: reduce motion, larger type, high contrast, keyboard hints (all wired to real font-scale/theme)
- Interface: theme (system/light/dark), accent palette (indigo/rose/forest), density (compact/regular/comfy)
- Backup: real on-demand full-JSON export (replaced the old inert auto-export card)
- Areas + Tags management (add / rename / recolor / delete with usage counts) co-located in Settings
- Notifications: deeper than web — Calm/Balanced/Coach level + per-task reminder lead, driving OS reminders, paused check-ins and the morning brief

**Remaining gaps:**
- **[MED · missing] Focus-mode treatment not selectable in Settings**
  - Web exposes a dedicated 'Focus mode treatment' segment (ambient/cockpit/monk) in Settings -> Interface, so users can set their default look without entering focus. Android persists `treatment` and switches it live on the focus screen + in onboarding, but the Settings screen's Interface section only shows Theme/Accent/Density — there is no way to change the default treatment from Settings.
  - web: `components/settings/settings-panel.tsx:311-321` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:157-167`
- **[LOW · partial] No standalone 'Show gentle nudges' toggle**
  - Web has an independent 'Show gentle nudges' toggle (PREF_NUDGES) in Focus that gates the Today in-app nudge cards separately from push. Android has the nudge cards but no dedicated toggle — nudges are bundled into NotificationLevel (only off at 'Calm'), so a user can't silence in-app nudges while keeping pre-task push reminders, or vice-versa.
  - web: `components/settings/settings-panel.tsx:244-248` · android: `app/src/main/kotlin/tech/csalliance/unstuck/SettingsStore.kt:25-30`
- **[LOW · partial] Backup section lacks sync-status detail**
  - Web's Backup section shows a Sync on/paused banner plus an expandable status panel (session signed-in state, last-sync ping, per-collection row counts). Android's Backup section is just the export row + a one-line 'Your data is yours' blurb — no sync status, last-sync time, or row counts surfaced.
  - web: `components/settings/settings-panel.tsx:325-427` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/settings/SettingsScreen.kt:176-193`
- **[LOW · partial] No notification-permission re-enable path in Settings**
  - Web has a Notifications section with a Push toggle that re-requests browser permission and explains the denied/blocked state. Android requests POST_NOTIFICATIONS once at launch (MainActivity) and there's no Settings control to re-request or check OS permission — if a user denies it, the Notifications level segment in Focus still appears active but no system notifications will fire, with no in-Settings indication or re-enable affordance.
  - web: `components/settings/notifications-settings.tsx:25-58` · android: `app/src/main/kotlin/tech/csalliance/unstuck/MainActivity.kt`

## Account / Auth: sign in/up/magic/reset/google, account mgmt  (4 gaps)

**Parity:** At functional parity on every core auth flow — email/password sign-in & sign-up, magic link, Google OAuth, password reset (with recovery deep-link + set-new-password screen), change/add password with re-auth gate, display-name edit, server-side account deletion, export, and sign-out are all implemented on Android with the same humanized error table and anti-enumeration detection ported from the web. The only remaining divergences are secondary recovery-UX niceties: Android has no "resend confirmation email" action and doesn't auto-route an already-registered sign-up to the sign-in screen.

**Now matched (closed gaps):**
- Email + password sign-in and sign-up (AuthService.signIn/signUp)
- Magic-link / OTP sign-in (sendMagicLink)
- Continue with Google OAuth (signInWithGoogle, official G button)
- Forgot password → reset email (resetPassword)
- Password-recovery deep link (type=recovery) → dedicated SetNewPasswordScreen with no-current-password flow
- Change / add password with current-password re-auth gate (PasswordDialog + signIn re-auth)
- Add-a-password vs Change-password labeling based on hasPassword (email identity check)
- Edit display name (updateDisplayName writing full_name/display_name metadata)
- Delete account via server-side account-delete Edge Function + sign-out, with type-to-confirm
- Export everything (JSON snapshot)
- Sign out
- Shared humanizeAuthError mapping table + nextSafePath open-redirect guard + detectSignupAlreadyExists (ported to :core)

**Remaining gaps:**
- **[MED · missing] No "resend confirmation email" action**
  - Web exposes resendSignupConfirmation (sb.auth.resend type='signup') in two places: on the /auth/verify poll screen and inline on /auth/sign-in when password sign-in returns email_not_confirmed (errorIsConfirmation branch). Android's AuthService has no resend method at all — a user who never received or lost the confirmation email has no in-app way to get a fresh one; they must retry sign-up. AppViewModel/AuthService expose signUp/magicLink/resetPassword but nothing for resend.
  - web: `lib/auth-helpers.ts:123 / app/auth/sign-in/page.tsx:100-107,181-197 / app/auth/verify/page.tsx:60-67` · android: `sync/src/main/kotlin/tech/csalliance/unstuck/sync/AuthService.kt:61`
- **[LOW · partial] Sign-up of an already-registered email shows a flat error instead of routing to sign-in**
  - Both detect the anti-enumeration case (detectSignupAlreadyExists). Web then router.push('/auth/sign-in?email=…&existing=1'), pre-filling the email and showing a friendly 'An account with this email already exists — sign in below, or use Forgot password' banner. Android just sets the red error string 'An account with that email already exists. Try signing in instead.' in the same sign-up form — the user must manually tap 'Already have an account? Sign in' and re-enter their email.
  - web: `app/auth/sign-up/page.tsx:69-76` · android: `sync/src/main/kotlin/tech/csalliance/unstuck/sync/AuthService.kt:49-56`
- **[LOW · partial] No post-sign-up session-poll / auto-advance screen**
  - Web has a dedicated /auth/verify screen that polls sb.auth.getSession() every 3s (5-min deadline) so the original tab auto-routes to the dashboard the moment the user clicks the email link elsewhere. Android shows a static success string ('Check your email to confirm your account, then sign in.') and stays on the auth form; sign-in completion relies solely on the user returning to the app (the type=recovery/PKCE deep link covers the link-click path, but there is no in-app 'waiting / auto-continue' affordance after plain sign-up).
  - web: `app/auth/verify/page.tsx:34-58` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:109`
- **[LOW · partial] Sign-up name is required on web but optional on Android**
  - Web sign-up validates a non-empty name before submit ('Your name helps us greet you…') and always passes full_name/display_name metadata. Android labels the field 'Name (optional)' and allows blank, in which case no display_name metadata is written and currentName falls back to the email's local-part. Minor copy/validation divergence, not a data-loss issue.
  - web: `app/auth/sign-up/page.tsx:48-51` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/auth/AuthScreen.kt:95`

## Sync / realtime / offline / data integrity  (4 gaps)

**Parity:** At parity on the core engine — Android fully ports web's optimistic write-through, durable dependency-ordered outbox, server-canonical per-table hydrate with error isolation, per-table realtime mirror, user-change cache-wipe, and sign-out token/outbox cleanup (and is in several places more robust than web). The only remaining divergences are defense-in-depth/edge: a narrower offline-write preservation net on hydrate, no instant on-reconnect outbox replay, and a few web-beta tables that aren't synced because they have no Android UI.

**Now matched (closed gaps):**
- Durable offline outbox with FIFO seq order + dependsOn dependency ordering (cal_block waits for parent task; capture waits for session)
- Poison-pill cap (FAIL_CAP=5) that dead-letters a permanently-failing op AND drops its orphaned dependents — matches web MAX_TRIES dead-lettering
- intendedUser guard: outbox flush bails if the signed-in user changes mid-drain (mirrors web bridge intendedUserId)
- Server-canonical hydrate with per-table error isolation (a failed table fetch leaves local cache intact, never blanks it)
- cal_blocks hydrate preserves local external g_ Google blocks across the canonical replace (SyncDecision.mergeHydratedCalBlocks)
- Cache-wipe on user-change only (prevUserId in SharedPreferences) — protects same-user re-auth pending edits + live session; explicitly safer than web's always-wipe-on-SIGNED_IN, unit-tested
- Realtime postgres_changes per table (tasks, sessions, cal_blocks, captures, reason_logs, collections, tags, life_areas) with per-event runCatching so one bad row can't kill a table's live stream
- calendar_connections intentionally NOT subscribed in realtime (encrypted creds never broadcast) — matches web
- Shared collections: subscribe without user_id filter + rely on RLS; mergeKeep preserves client-only members/myRole across realtime updates (port of realtime.ts)
- collection_members realtime channel re-hydrates collections on a new share/revoke
- Sign-out deletes this device's push-token row while JWT still valid AND drains the outbox first so un-flushed edits aren't lost
- UUID/FK guarding (uuidOrNull) so mock/non-UUID FK refs drop to null instead of failing the upsert forever
- Cancel-pending-upserts on delete so a held-back upsert can't resurrect a row server-side after its delete flushes
- gateway injects user_id on every write (payload = { ...row, user_id }) exactly like the web bridge
- Two-way Google Calendar pull+push wired through WriteThrough hooks (insert mints + persists event id; patch/delete reuse it)
- Periodic background sync via WorkManager (flush outbox + hydrate + widget refresh) with NetworkType.CONNECTED constraint

**Remaining gaps:**
- **[MED · partial] Pending-outbox merge on hydrate only covers cal_blocks, not all tables**
  - Web's replaceWriteArray merges back any row that still has a pending outbox entry for EVERY synced table (via TABLE_FOR_KEY/pendingIdsForTable) — defense-in-depth so an offline/in-flight write isn't destroyed by the canonical replace. Android applies this pending-merge ONLY in hydrateCalBlocks; the generic replace() for tasks/sessions/captures/reason_logs/tags/life_areas/collections does a plain wipe+insert with no pending-row survival. Flush-before-hydrate ordering plus 'offline means the table fetch also fails' makes the window narrow, but on partial connectivity (flush failed/incomplete yet that table's GET succeeded) an offline-created task/tag/area/collection can be dropped from the UI until the next successful flush.
  - web: `lib/sync/hydrate.ts:406-419` · android: `sync/.../Hydrator.kt:64-93`
- **[MED · partial] No instant outbox replay when connectivity returns**
  - Web registers a window 'online' listener (initOutboxReplay) that drains the outbox the moment the network comes back. Android has no ConnectivityManager/NetworkCallback and no foreground/ON_RESUME-triggered flush; the only drain triggers are sign-in, calendar-connect, sign-out, and the 30-min periodic WorkManager (which is NetworkType.CONNECTED-constrained, so a deferred run does fire on reconnect but coarsely — up to ~30 min later, not immediately). Queued offline edits therefore land server-side noticeably later than on web.
  - web: `lib/sync/outbox.ts:127-135` · android: `app/src/main/kotlin/tech/csalliance/unstuck/surface/SyncWorker.kt:39-47`
- **[LOW · missing] Collab/beta tables not hydrated or mirrored in realtime**
  - Web hydrates AND realtime-subscribes trusted_circle, task_shares, body_double_sessions, coach_questions, feature_signals (Phase 16A). Android's Hydrator and RealtimeMirror cover none of them. These are web-beta collaboration/feature-signal surfaces without corresponding Android UI, so it's a feature-scope gap rather than a broken sync path — flagged for completeness.
  - web: `lib/sync/hydrate.ts:271-275` · android: `—`
- **[LOW · partial] user_preferences (adhd_struggles) is write-only on Android, never hydrated back**
  - Web hydrate pulls user_preferences (adhd_struggles + usable_minutes_per_day/weekend) and writes them into local prefs each sign-in, so onboarding selections + capacity propagate across devices. Android's PreferencesClient only UPSERTS adhd_struggles at onboarding and never reads them back during hydrate; usable-minutes capacity isn't modeled on Android at all. Low impact: struggles drive web-only nudge copy and the capacity feature is absent from Android, so the missing read-back is largely inconsequential today — but struggles set on web won't reflect on Android.
  - web: `lib/sync/hydrate.ts:276,324-367` · android: `sync/.../Clients.kt:92-99`

## Tags (vocabulary, tag filter, chips on rows, picker on create/detail)  (3 gaps)

**Parity:** Tags are at functional parity: Android has the full curated-vocabulary model (add/rename/recolor/delete with task cascade), a search-or-create TagPicker on both create + detail, the #tag filter on /tasks (banner + clickable row chips), and full server sync of the tags table (id/name/color/sort_order). The only remaining divergences are cosmetic: Android renders tag chips and the picker dropdown in a single flat accent color, ignoring each tag's per-tag color, whereas the web tints chips and shows a color dot per tag.

**Now matched (closed gaps):**
- Curated tag vocabulary with add / rename / recolor / delete in Settings (TagsContent mirrors web TagPanel, including usage count and per-tag color palette)
- Rename + delete cascade across every task's tags array, case-insensitive + de-duped (AppViewModel.renameTag/deleteTag matches useTags cascadeRenameTaskTag)
- Search-or-create TagPicker as removable #chips on both New Task sheet and Task Detail sheet
- Tag filter on Tasks screen: 'Filtering by tag #X' banner with clear, applies to every view incl. Today (VisibleTasks.matchesTag parity)
- Clickable inline #tag chips on task rows that set the active tag filter
- Full server sync of tags table (id, name, color, sort_order) via Hydrator/RealtimeMirror/WriteThrough + DbRowCodec, plus tags[] persisted on tasks
- Inline 'Create "q"' in the picker when the query matches no existing tag (ensureTag dedups case-insensitively)

**Remaining gaps:**
- **[LOW · partial] Tag chips ignore the per-tag color (flat accent instead of color tint)**
  - Web tints each chip's background/text/border from the tag's color token (resolveAreaColor) on both the picker and the list-row TagChipRow. Android renders every chip with a single flat c.primarySoft/c.primaryDeep regardless of the tag's stored color, so the color the user picks in Settings only appears as the settings dot — never on task rows or in the picker.
  - web: `components/tasks/tag-picker.tsx:14 (chipColorStyles) + components/tasks/list-row.tsx:114 (TagChipRow)` · android: `app/.../ui/tasks/TasksScreen.kt:137 + app/.../ui/components/TagPicker.kt:51`
- **[LOW · partial] Picker dropdown omits the per-tag color dot**
  - Web's TagPicker dropdown shows a small colored dot (the tag's color, or neutral) next to each tag name. Android's dropdown rows show only a checkmark + '#name' with no color dot, so users can't distinguish tags by color while picking.
  - web: `components/tasks/tag-picker.tsx:223 (dotColor per row)` · android: `app/.../ui/components/TagPicker.kt:73`
- **[LOW · partial] Picker dropdown does not sort tags by sort_order**
  - Web hydrates tags ordered by sort_order and the picker preserves that order; Settings on Android sorts by sortOrder, but the TagPicker dropdown iterates store.tags() which is unordered (LocalStore.observe has no sort), so picker order can differ from the curated/settings order. Cosmetic only.
  - web: `lib/sync/hydrate.ts:269 (order('sort_order')) consumed in tag-picker.tsx` · android: `app/.../ui/components/TagPicker.kt:67 (vocab used as-is) / data/.../LocalStore.kt:50`

## Google Calendar connect + external events (pull AND push)  (3 gaps)

**Parity:** At functional parity: Android does the full Google OAuth connect (Custom Tab → unstuck://calendar-callback), pull-and-reconcile of external events, two-way push (insert/patch/delete of task blocks via WriteThrough hooks), disconnect-with-purge, and multi-account display — all against the same shared calendar-sync Edge Function. Remaining divergences are about pull freshness/window and the absence of a sync-diagnostics surface, not core data flow.

**Now matched (closed gaps):**
- Full Google OAuth consent via Custom Tab + HTTPS bounce page, server-issued HMAC state, deep-link callback (unstuck://calendar-callback) — matches web's authorize/connect handshake
- Pull external events and reconcile into local EXTERNAL (g_) blocks, including in-window deletion reconciliation (drops blocks Google no longer returns)
- Two-way push: WriteThrough mirrors task-block create/edit (INSERT→stamp externalEventId→PATCH) and delete to Google, best-effort and non-blocking, always targeting 'primary' calendar
- Skips pushing EXTERNAL and PLACEHOLDER blocks (kind != TASK), and never re-pushes g_ rows to our cal_blocks table — matches web's external/placeholder skip and own-event dedupe
- Disconnect immediately purges the connection row + that account's external blocks locally, with a destructive-action confirm dialog
- Shows all connected accounts (not just the first), a Connect / Sync now / Disconnect bar, and busy state — matches web's connected-accounts list + Sync now button
- Pull on sign-in, on connect completion, and via a periodic background WorkManager job (server canonical hydrate + calendar pull)
- Identical block mapping (g_<id>, HH:MM local, diffMinutes floored at 15, local-ymd anchoring) and identical block→ISO push range via shared GoogleSyncMapping port

**Remaining gaps:**
- **[MED · partial] No foreground auto-refresh of external events while the app is open**
  - Web's startAutoSync polls pullAndIngest every 5 min while the page is open (plus on connect), so an event added on another device shows up within minutes. Android only pulls on sign-in, on connect, and via a 30-min background WorkManager (SyncWorker.syncNow → pullCalendar); there is no foreground interval. An already-open Android session won't see new/changed Google events until the user taps 'Sync now' or the 30-min worker fires.
  - web: `lib/sync/google-sync.ts:326-344 (AUTO_PULL_INTERVAL_MS = 5*60*1000, startAutoSync)` · android: `app/.../surface/SyncWorker.kt:42 (PeriodicWorkRequestBuilder<SyncWorker>(30, MINUTES)); AppViewModel.kt:227 (syncCalendar only on tap)`
- **[LOW · missing] No sync-diagnostics / last-synced surface for pull or push**
  - Web surfaces per-cycle status: 'last synced HH:MM · N events', a sync-error state, and a SYNC DIAGNOSTICS panel showing the last push action (insert/patch/delete/skip) with the error string. Android records nothing user-visible — pull/push failures only go to Logcat (Log.w). The bar shows 'Synced · email' / 'Syncing…' but no timestamp, event count, or push outcome, so a user can't tell that a push to Google silently failed.
  - web: `components/calendar/sync-flow.tsx:419-526 (last-synced label + SYNC DIAGNOSTICS pull/push panel) backed by getSyncStatus/recordPush in google-sync.ts:241-244` · android: `sync/.../SyncCoordinator.kt:230,234,245 (Log.w only, no status emit)`
- **[LOW · partial] No visible-window pull when navigating beyond the fixed pull window**
  - Web's month view re-pulls the exact visible 6-week grid (pullAndIngest({from: gridStart, to: gridEnd})) so events outside the default window appear when you page months. Android uses a fixed [-7d, +30d] window for every pull and never widens it on navigation; the Week view paged ~5+ weeks ahead (beyond +30d) shows no external events. Mitigated by Android's window being wider than web's default (-7/+14) and by the Android Month view being a focus-density heatmap that doesn't render events at all.
  - web: `components/calendar/month-full.tsx:122-133 (pulls gridStart→gridEnd on anchor change)` · android: `sync/.../SyncCoordinator.kt:154-162 (today.minusDays(7) .. today.plusDays(30), fixed)`

## Command palette + quick capture  (3 gaps)

**Parity:** Near parity: Android's CommandPalette mirrors the web ⌘K modal (live task + capture search routing to task detail, nav-jump actions, type badges) and quick-capture during focus is fully implemented with tags. The remaining divergences are that the Android palette omits two web candidate types — life-area filter jumps and a couple of nav targets (Analytics/Insights, Start-focus) — and uses plain substring matching instead of web's ranked fuzzy scoring.

**Now matched (closed gaps):**
- Global search modal with live task results (filters out done tasks), routing to task detail
- Capture/note search that routes to the owning task and skips orphan captures with no taskId
- Type badges per result (TASK / NOTE / ACTION) and life-area meta sublabel on task rows
- Nav-jump actions from the palette (Today/Tasks/Calendar/Lists/Settings)
- Palette reachable from a search affordance on every primary tab
- Quick-capture flow during focus (CaptureSheet) with the full tag set (follow-up/idea/edit/question/distraction) attaching to the active task + session

**Remaining gaps:**
- **[MED · missing] Palette doesn't surface life-area filter jumps**
  - Web adds one palette candidate per life area ('<Area>' → 'Filter tasks by area', routes to /tasks?area=<name>), so the user can jump straight into an area-filtered task list from ⌘K. Android's palette builds only task + note + 5 fixed action rows; it never lists areas. The Tasks screen does support area filtering via FilterPills, so the capability exists — it's just not reachable from the palette.
  - web: `components/command-palette/command-palette.tsx:91-98` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:51-66`
- **[LOW · partial] Palette is missing Analytics/Insights and Start-focus nav actions**
  - Web's ROUTES include 'Go to Analytics' and 'Start a focus session' alongside the standard tabs. Android's action list only has Today/Tasks/Calendar/Lists/Settings — no jump to Insights/Analytics and no 'start a focus session' command (Android opens focus per-task, so a generic start-focus may not map, but the Insights jump is a clean gap since Insights exists as Route.Insights).
  - web: `components/command-palette/command-palette.tsx:16-23` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:51-57`
- **[LOW · partial] Substring match instead of ranked fuzzy scoring**
  - Web scores candidates (exact > prefix > contains, shorter labels rank higher) and sorts by relevance before taking the top 12. Android uses a plain lowercase contains() filter with no ranking — results appear in fixed group order (tasks, then notes, then actions) regardless of match quality, so a prefix/exact hit isn't promoted above a mid-string match.
  - web: `components/command-palette/command-palette.tsx:35-43` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/palette/CommandPalette.kt:57-66`

## Onboarding (struggles → user prefs, first task, areas)  (3 gaps)

**Parity:** Both platforms run a short multi-step onboarding that creates a first task and marks the user onboarded, but Android diverges on the actual content of the steps: it never collects the web's "struggles" preference (it swapped that step for an area-picker), and it doesn't capture the first-physical-action or launch the closing live focus session. Core "complete onboarding + seed areas + first task" is at parity; the struggles-capture step is the main real gap.

**Now matched (closed gaps):**
- Multi-step onboarding with progress indicator and Skip/Back/Continue navigation
- Creates a first task (estimateMin 15) and marks the device onboarded (graph.onboarded)
- Seeds default life areas on completion (canonical palette, single-source seed, no double-seed)
- Full struggles persistence plumbing exists and works (PreferencesClient.setAdhdStruggles → user_preferences.adhd_struggles), and completeOnboarding accepts a struggles list
- State survives rotation/theme change (rememberSaveable on step + first task)
- Persists chosen focus treatment as part of finishing onboarding

**Remaining gaps:**
- **[MED · missing] Onboarding never collects the ADHD 'struggles' preference**
  - Web step 2 'What gets you stuck?' presents 5 struggle options (Starting/Sustaining/Switching/Stopping/Recovering), multi-select, and persists them to localStorage + user_preferences.adhd_struggles. Android has the full backend plumbing (PreferencesClient.setAdhdStruggles, completeOnboarding(struggles=...)) but the OnboardingScreen has NO struggles step — it replaced that slot with an area-picker and always calls completeOnboarding(struggles = emptyList()) / completeOnboarding(emptyList()), so adhd_struggles is never written from Android. (Severity capped at MED: the web app currently only persists/hydrates/exports struggles and doesn't yet drive runtime coaching from them.)
  - web: `components/onboarding/flow.tsx:20-26,118-186,79-93` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:66,85-96`
- **[MED · partial] Onboarding doesn't end in a live focus session**
  - Web's closing step is a 'Try it now' screen that calls timer.start(task.id, 15) and routes to /focus, so the user immediately experiences a 15-minute focus session — the signature first-run moment. Android's finish() persists the treatment + task and just dismisses onboarding to Today (onDone()); it never calls startFocus, so the new user lands on the home screen instead of in a focus session. Android substitutes a focus-treatment picker step (not present in web onboarding), so it's a deliberate UX reshuffle, but the 'start your first session' flow is not reproduced.
  - web: `components/onboarding/flow.tsx:94-96,281-285,350-433` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:62-68,128`
- **[LOW · partial] First task omits the 'first physical action' captured in web onboarding**
  - Web step 4 asks for 'the smallest physical move' and stores it on the first task (firstPhysicalAction) along with tags:['onboarding'] and lifeArea:'Personal'. Android's onboarding first task is created with name + estimateMin 15 + lifeArea = first picked area only — no firstPhysicalAction prompt and no onboarding tag. The field is fully supported in addTask(...firstPhysicalAction...), it's just never collected here.
  - web: `components/onboarding/flow.tsx:60-74,224-280` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/onboarding/OnboardingScreen.kt:62-68`

## Recurrence (editor UI incl. Until date, materialization)  (2 gaps)

**Parity:** At near-full parity: the core recurrence logic (materialization, regenerate-on-edit diff, monthly day clamping, until-stop, labels) is an exact Kotlin port of lib/recurrence.ts, fully wired through setRecurrence/scheduleTask, and the data model round-trips `until`. The single real divergence is that the Android editor UI cannot SET or EDIT an Until date — that control is deliberately omitted, so on-device series are open-ended only.

**Now matched (closed gaps):**
- Daily / Weekly (per-day toggles) / Monthly recurrence kinds in the editor
- Materialization of cal_blocks 56-day horizon via materializeOccurrences (exact port)
- regenerateForTask diff applied through sync on recurrence change (setRecurrence mirrors web onSave: same earliest-block anchor, 09:00 fallback, keep-past/regen-future)
- Monthly day-of-month clamping to short months (Feb 28/29) — matches web
- Recurrence honors `until` during materialization (stops at until inclusive)
- recurrenceLabel with weekdays/weekends/abbrev-days collapse AND 'until <Mon D, YYYY>' suffix rendering
- Recurrence model serializes to the same {kind, daysOfWeek?, until?} JSONB shape including `until`
- Recurring tasks diff via regenerateForTask in scheduleTask instead of re-inserting whole horizon

**Remaining gaps:**
- **[MED · missing] Editor cannot set/edit the recurrence Until (end) date**
  - Web's RepeatEditor shows an 'Until:' date input (min=today) plus an 'Open-ended' clear button, so a user can bound a series to an end date. Android's RecurrenceEditor deliberately omits this (comment lines 17-19: 'until is intentionally omitted from the editor (open-ended series)') and only emits Recurrence.Daily()/Weekly(days)/Monthly() with until=null. A user on Android cannot set a new until date nor edit/remove one set on web/iOS. The until value still displays in recurrenceLabel and is honored by materialization, and survives round-trip — only the editing affordance is absent.
  - web: `components/tasks/task-detail-pane.tsx:747-781` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/components/RecurrenceEditor.kt:17-49`
- **[LOW · partial] Editor auto-applies per tap instead of a draft Save/Cancel flow**
  - Web's RepeatEditor holds draft state (kind/days/until) and only commits + regenerates cal_blocks on an explicit Save button (with Cancel to discard). Android calls vm.setRecurrence on every chip/day tap, so each interaction immediately writes the task and regenerates the horizon (e.g. toggling weekly days fires repeated regen passes). Same end state, but no batched commit / cancel; minor interaction and write-amplification difference, not a data gap.
  - web: `components/tasks/task-detail-pane.tsx:659-666,785-788` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/tasks/TaskDetailSheet.kt:169`

## Collections: create, add/edit/remove items, archive, sharing + accountability  (2 gaps)

**Parity:** Android is at full parity with the web on every core Collections flow (create, item add/edit/pin/soft-done/remove, rename/recolor/delete, and the complete share-by-email + editor/viewer roles + pending invites + leave/remove plumbing), and actually exceeds web on archive and move-to-task accountability — both of which exist on web only as DB columns / backend functions with no triggering UI, but ship as real UI on Android. The only remaining divergences are cosmetic overview-card preview details and an unrendered subtitle that no signed-in user ever has.

**Now matched (closed gaps):**
- Create collection (name + 6-color palette, sortOrder = max+1) — NewCollectionSheet + upsertCollection
- Fast-add item input (auto-focused, refocuses after each return), inline edit, pin, soft-done tick, remove
- Shared-vs-own write routing: atomic JSONB item RPCs (collection_add_item / _update_item / _remove_item / _set_item_flag) for shared lists, whole-row upsert for own; serialized via mutex + re-reads latest row (web's functional-update guard)
- Rename / recolor / delete-with-confirm, all owner-gated
- Share by email with editor/viewer role toggle via share-collection edge function; member list + pending email invites + cancel invite + remove member + leave
- Shared-with-N / 'shared with you · view only' badges on card + detail; view-only members get add field and item actions hidden (canEdit gate)
- myRole / members / ownerId hydrated and used identically for owner/canEdit/isShared gating
- Archive / unarchive collections with an 'Archived (N)' filter toggle on the overview — ANDROID-ONLY UI for web's migration-026 column (web has no archive UI)
- Move-to-task accountability: promote an item to a task, 'keep everyone in the loop' with a 'by' time picker, live '<name>'s on it · by 6:00' / 'overdue' / 'done by <name>' chips, plus collection-task-done + check-collection-late round trip — ANDROID-ONLY UI (web has only the backend functions)

**Remaining gaps:**
- **[LOW · partial] Overview card item-preview ordering & '+N more' line not matched**
  - Web's CollectionCard previews up to 3 items with PINNED items surfaced first then most-recent, and shows a '+ N more' line when there are extra items. Android's card renders only col.items.take(2) in raw array order, with no pinned-first ordering and no '+N more' overflow line. Cosmetic; both show the live item count badge.
  - web: `components/collections/collection-card.tsx:20-22,129-133` · android: `app/src/main/kotlin/tech/csalliance/unstuck/ui/collections/CollectionsScreen.kt:118-122`
- **[LOW · partial] Collection subtitle never rendered on Android**
  - Web shows the optional serif-italic subtitle on both the overview card and the detail header. Android stores/round-trips the subtitle field (model + metadata update ship it) but never displays it on the card or detail. Zero user impact in practice: no UI on EITHER platform lets a signed-in user set a subtitle (web's NewCollectionSheet/detail only edit name+color too), so subtitles only ever exist on web's signed-out demo seed rows, which Android has no equivalent of.
  - web: `components/collections/collection-detail.tsx:184-188` · android: `—`
