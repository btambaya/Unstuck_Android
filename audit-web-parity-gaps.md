# Web→Android functionality gaps (verified)

## HIGH (63)

### [Task CRUD + detail (create/edi] No inline editing of task name in the detail view
- status: missing | web: components/tasks/task-detail-pane.tsx:128-131, :516-579
- web does: Detail pane renders the title via EditableHeadline — click/Enter turns it into an input; Enter or blur commits the trimmed value (empty trims ignored), Escape reverts, then upsert({...task, name}) persists.
- impl: TaskDetailSheet.kt line 62 renders a static Text(task.name). Replace with a tap-to-edit BasicTextField (or rename dialog), commit via vm.updateTask(task.copy(name = trimmed)). vm.updateTask exists (AppViewModel.kt:109).

### [Task CRUD + detail (create/edi] No editing of the first physical action in the detail view
- status: partial | web: components/tasks/task-detail-pane.tsx:133-136, :585-664
- web does: EditableFirstAction is always rendered (even when empty) so the user can add/edit the first step after creation; commit calls upsert({...task, firstPhysicalAction: text.trim() || undefined}).
- impl: TaskDetailSheet.kt:64-70 first-action card is read-only Text showing a placeholder. Make it tap-to-edit and persist via vm.updateTask(task.copy(firstPhysicalAction = text.trim().ifEmpty { null })).

### [Task CRUD + detail (create/edi] No editing of the estimate in the detail view
- status: missing | web: components/tasks/task-detail-pane.tsx:164-172, :855-920
- web does: EditableEstimate shows '{n} min · learned from N similar'; click opens a number input; commit (Enter/blur) validates n>0 and calls upsert({...task, estimateMin: n, updatedAt}).
- impl: TaskDetailSheet.kt:87 MetaCell('Estimate', '${task.estimateMin} min') is read-only. Add tap-to-edit (number input or chip picker like NewTaskSheet's 15/25/45/60/90 chips) and persist via vm.updateTask(task.copy(estimateMin = n)).

### [Task CRUD + detail (create/edi] No editing of the life area in the detail view
- status: missing | web: components/tasks/task-detail-pane.tsx:175-182, :924-1027
- web does: EditableArea shows the current area pill; clicking opens a popup listbox of every defined life area plus an 'Unassigned' option; selecting calls upsert({...task, lifeArea: name ?? undefined, updatedAt}).
- impl: TaskDetailSheet.kt:88 MetaCell('Area', task.lifeArea ?: '—') is read-only text. Add an area picker (reuse the area SelectableChip row from NewTaskSheet.kt:149-154 or a dropdown) bound to vm.lifeAreas, persisting via vm.updateTask(task.copy(lifeArea = ...)).

### [Task CRUD + detail (create/edi] Delete does not cascade to the task's cal_blocks and captures
- status: broken | web: components/tasks/task-detail-pane.tsx:92-102
- web does: deleteTask() first removes every cal_block and capture whose taskId matches the task, THEN removes the task, so realtime listeners don't pull orphans back.
- impl: AppViewModel.deleteTask (AppViewModel.kt:120) only calls write?.deleteTask(id) — no cascade. Add deletion of linked cal_blocks (blocks.filter taskId==id → write?.deleteCalBlock) and captures before the task delete. WriteThrough has deleteCalBlock (WriteThrough.kt:66) but NO deleteCapture — add one (deleteLocalAndEnqueue(Tables.CAPTURES, id)) plus a vm.deleteCapture method.

### [Task CRUD + detail (create/edi] No recurrence display or 'Add/Edit repeat' editor in detail
- status: missing | web: components/tasks/task-detail-pane.tsx:174, :194-234, :690-852
- web does: Detail shows a 'Repeat' meta cell (recurrenceLabel) plus an 'Add repeat'/'Edit repeat' button opening RepeatEditor (kind picker, weekly day toggles, until date). Saving calls upsert with the new recurrence and runs regenerateForTask to upsert/delete future cal_blocks (anchored to the task's earliest block or today 09:00).
- impl: TaskDetailSheet.kt meta grid (lines 84-95) has no Repeat cell and no repeat editor. Add a 'Repeat' MetaCell using core recurrenceLabel(task.recurrence) (Recurrence.kt:105), and an Add/Edit-repeat affordance reusing ui/components/RecurrenceEditor.kt (note: it omits the Until date — that is a separate create-modal gap). On save, vm.updateTask(task.copy(recurrence=...)) and apply core regenerateForTask(...) (Recurrence.kt:60) results via write?.upsertCalBlock/deleteCalBlock — needs a new vm method. Both core helpers exist and are unused in the app UI.

### [Tags (vocabulary, tag filter, ] TagPicker on task creation — apply tags when creating a task
- status: missing | web: components/tasks/task-create-modal.tsx:467-469; components/tasks/tag-picker.tsx:41-289
- web does: TaskCreateModal renders a TAGS section with <TagPicker value={tags} onChange={setTags} compact /> (task-create-modal.tsx:467-469). The picker (tag-picker.tsx:41-289) shows selected tags as removable '#name' chips, a dashed '+ Tag' button opens a search-filtered dropdown over the existing vocabulary (useTags().items), toggles tags on/off (checkmark on active), and creates a new tag inline ('Create "x"' / Enter). Selected names are saved onto task.tags.
- impl: app/.../ui/tasks/NewTaskSheet.kt — add a tag-picker composable reading vm.tags (TagRow vocabulary flow at AppViewModel.kt:59) + a local mutableStateList<String> of selected names; render removable chips and a '+ Tag' dropdown with search + inline create (vm.upsertTag(TagRow(newUuid(), name, null, vm.tags.value.size)) then add to selection). At submit, pass tags=selected to vm.addTask(...). addTask already accepts tags:List<String>? (AppViewModel.kt:91) but the call at NewTaskSheet.kt:182-186 never passes it. NOTE: the only 'tag' refs in NewTaskSheet today are CaptureTag (capture-note kind), an unrelated concept — no vocabulary-tag UI exists.

### [Tags (vocabulary, tag filter, ] TagPicker on task detail/edit — add/remove tags on an existing task
- status: missing | web: components/tasks/task-detail-pane.tsx:183-190
- web does: TaskDetailPane's meta grid has a 'Tags' row with an editable <TagPicker value={task.tags ?? []} onChange={next => upsert({...task, tags: next, updatedAt})} compact /> (task-detail-pane.tsx:183-190), so tags can be added/removed on an existing task and persisted immediately.
- impl: app/.../ui/tasks/TaskDetailSheet.kt (TaskDetailScreen) — the meta grid (lines 84-95) only has read-only MetaCells (Estimate/Area/Schedule/Status); zero tag references in the whole file. Add a 'Tags' section using the same picker composable as create, bound to task.tags, calling vm.updateTask(task.copy(tags = next)) on change (updateTask exists at AppViewModel.kt:109).

### [Tags (vocabulary, tag filter, ] Tag chip row on task list rows / cards (display + tap-to-filter)
- status: missing | web: components/tasks/tag-picker.tsx:294-344; components/tasks/list-row.tsx:113-119; components/dashboard/task-row.tsx:336-342
- web does: TagChipRow (tag-picker.tsx:294-344) renders a read-only row of colored '#name' chips capped at maxVisible=2 with '+N' overflow, colored from each tag's color token (colorByName lookup). It is wired onto /tasks list rows (list-row.tsx:113-119) and dashboard task rows (task-row.tsx:336-342). Each chip is clickable: onClickTag pushes router to /tasks?tag=NAME to filter by that tag.
- impl: app/.../ui/tasks/TasksScreen.kt list rows (lines 97-111 render only name + area dot + estimate), and the task rows in ui/today/TodayScreen.kt + ui/components/Common.kt — render task.tags as colored '#name' chips (look up color from vm.tags by name, mirroring web colorByName), with '+N' overflow, each chip tap setting the active tag filter. TodayScreen.kt and Common.kt have zero tag references today.

### [Tags (vocabulary, tag filter, ] Tag filter on /tasks (?tag=NAME) — slice the list by a tag
- status: partial | web: app/(product)/tasks/page.tsx:35-36,55-60,124-132; lib/visible-tasks.ts:124-128; components/tasks/task-list-pane.tsx:156-189
- web does: TasksPage reads ?tag= into activeTag (tasks/page.tsx:35-36) and threads it through visibleTasks, which filters EVERY view including Today to tasks whose tags include the active tag, case-insensitive (visible-tasks.ts:124-128). TaskListPane shows a 'Filtering by tag #NAME · clear' pill (task-list-pane.tsx:156-189) that clears by deleting ?tag (clearTag, page.tsx:55-60). Reachable by tapping any tag chip.
- impl: core/.../logic/VisibleTasks.kt ALREADY ports activeTag (param at line 56, filter at 98-99) and matchesTag() (line 30) — :core logic is done. But the UI never wires it: TasksScreen.kt:66 calls visibleTasks with activeArea only and no activeTag; it handles only activeArea (lines 82-93). Add an activeTag state (mirroring activeArea) fed by tag-chip taps, pass activeTag=... into the visibleTasks() call, and add a 'Filtering by tag #NAME · clear' pill mirroring the existing activeArea pill at TasksScreen.kt:82-92. No activeTag/matchesTag reference exists anywhere in :app UI.

### [Tags (vocabulary, tag filter, ] Tag management screen in Settings (TagPanel: add/rename/recolor/delete)
- status: missing | web: components/settings/tag-panel.tsx:1-453; app/(product)/settings/page.tsx:29-31
- web does: Settings renders <TagPanel /> below LifeAreaPanel (settings/page.tsx:29-31). TagPanel (tag-panel.tsx:1-453) lists every tag with a color dot, '#name', and a live 'N open' usage count (non-done tasks carrying that tag, usageCount at lines 17-19). A '...' menu offers inline Rename, a COLOR palette ('No color' + COLOR_TOKENS, lines 255-286) to recolor via onRecolor→update(id,{color}), and a two-step delete confirm. An 'Add tag' form (name + color picker + duplicate detection, lines 340+) creates a tag via add(name,color). NOTE: despite the stale header comment 'tags don't carry color', the panel fully supports color (onRecolor wired at line 80, add color at line 88).
- impl: app/.../ui/settings/SettingsScreen.kt — the SettingsSection enum (lines 57-65) and HUB list (lines 67-71) have no Tags entry, and only AreasContent exists (lines 161-205). Add a Tags SettingsSection + TagsContent composable (model on AreasContent) listing vm.tags with usage counts (tasks.count { !it.done && it.tags?.contains(name)==true }), inline rename (vm.upsertTag(t.copy(name=...))), color picker (vm.upsertTag(t.copy(color=...))), delete-with-confirm (vm.deleteTag(t.id)), and an add-tag form with duplicate detection. vm.upsertTag/deleteTag plumbing exists (AppViewModel.kt:216-217).

### [Today / Start-Next / Up-Next /] Up Next list is entirely absent on Android
- status: missing | web: components/dashboard/up-next.tsx:16-83; lib/pick-start-next.ts:40-59
- web does: Right-rail 'Up next' panel renders the next 3 tasks ranked by pickStartNext logic (skipping the live task and the Start-Next recommendation), each with area dot + area label + name + a 'when' label ('after this · Nm' for the first, 'today · Nm' for the rest), plus empty-state 'Nothing queued. The next start-recommendation will appear here.'
- impl: core/logic/PickStartNext.kt ALREADY ports pickUpNext() (lines 43-55) but it is never called by any UI (grep confirms zero call sites in app/). Add an 'Up next' section to ui/today/TodayScreen.kt below the Today list (single-column on mobile). Compute val upNext = pickUpNext(tasks, blocks, liveId, startNext?.id, 3) and render rows mirroring up-next.tsx (area dot + label + name + whenLabel: index 0 = 'after this · ${'$'}{m}m', else 'today · ${'$'}{m}m'). Include the empty-state card.

### [Today / Start-Next / Up-Next /] Mini-calendar / Today timeline absent on Today (no per-block 'Now ·' chip)
- status: missing | web: components/dashboard/mini-calendar.tsx:43-186
- web does: MiniCalendar lists today's cal_blocks sorted by startTime; each chip shows start time, name, end time; the block whose [start,start+duration) contains now is highlighted coral, labelled 'Now · {name}' with a pulsing dot; done linked tasks render struck-through at 0.55 opacity; external/placeholder/task blocks get distinct colors via blockKind; re-ticks every 60s; accepts task drops to schedule at next 15-min slot; empty state 'No blocks yet. Drag tasks here to plan your day.'
- impl: No today-timeline section exists on TodayScreen.kt. The calendar tab's DayGrid.kt has only a single coral 'now line' overlay (DayGrid.kt:162-167) — NOT per-block isLive 'Now ·' chips. Add a read-only Today-timeline to TodayScreen.kt: filter blocks to Clock.todayIso(), sort by startTime, compute live block (now within [start,start+duration)), render chips with start/end + coral 'Now ·' highlight + struck-through done blocks. core/logic/CalBlockKind.kt (blockKind/isExternalBlock/isPlaceholderBlock) is already ported. Drag-to-schedule can be deferred; the read-only timeline is the core gap.

### [Focus mode: treatments, pause-] Pause-reasons prompt UI never shown when pausing
- status: missing | web: components/focus/controls.tsx:84,104-107; components/focus/pause-reasons.tsx:12,19-34
- web does: In the 'pause' state, when PREF_FOCUS_PAUSE_REASONS is on (web default true, controls.tsx:84), the controls render <PauseReasons>: 'WHY ARE YOU PAUSING?' with chips Bathroom / Drink / Quick question / Stuck — need a moment / Other; picking one writes a ReasonLog (action 'pause') for analytics pattern detection.
- impl: FocusScreen.kt: when `paused`, render a reasons chip row gated on `settings.focusPauseReasons`. AppViewModel.saveReasonLog(taskId, reason, ReasonAction.PAUSE) (AppViewModel.kt:205) and the focusPauseReasons pref (SettingsStore.kt:28) both exist but are dead — no UI calls them (grep confirms zero call sites for saveReasonLog outside its definition). NOTE: Android's focusPauseReasons default is FALSE (SettingsStore.kt:28) vs web's TRUE — set the chip row to honor the pref and consider aligning the default. Add a PauseReasons composable mirroring pause-reasons.tsx; wire onPick -> vm.saveReasonLog(task.id, reason). FocusScreen does not currently receive `settings`; it must read vm.settings.

### [Focus mode: treatments, pause-] Ambient sound (brown/pink noise) is never actually played
- status: broken | web: components/focus/ambient-player.tsx:12-19; lib/use-sound.ts:139-161,106-137
- web does: AmbientPlayer mounts for the whole focus session, watches PREF_SOUND_AMBIENT ('off'|'brown'|'pink'), and calls setAmbient() which synthesizes brown vs pink noise via distinct Web Audio generators and loops at low gain (0.08). Changing the pref swaps the noise live; leaving focus stops it.
- impl: surface/AmbientAudio.kt exists (single brown-noise ambient_focus.wav MediaPlayer loop, vol 0.45) and the `ambient` pref (off/brown/pink, default 'off') is exposed in SettingsScreen, but AmbientAudio.start/stop is NEVER called from FocusScreen.kt or anywhere — grep across app/ + core/ shows only its own definition. Wire FocusScreen with a DisposableEffect keyed on settings.ambient: start(context) when != 'off', stop() on 'off'/dispose. Also there is only one (brown) asset, so brown vs pink are indistinguishable — add a pink loop asset or synthesize to match web's two timbres. NOTE: Android's ambient default is 'off' (web also 'off'), so this is opt-in.

### [Focus mode: treatments, pause-] Overrun gentle check-in prompt + Extend buttons missing
- status: missing | web: components/focus/controls.tsx:131-151; components/focus/treatments/cockpit.tsx:230-251; lib/use-focus-timer.ts:268-277
- web does: In 'overrun' state the controls show 'You've gone past your estimate... still going well?' with three actions: 'Add 10 min · keep going' (onExtend 10), 'I'm in the zone' (onExtend 15), 'Stop here · I'm losing thread' (onEndSession). Cockpit also shows an ember 'past the estimate' banner. extend() bumps sessionEstimateMin and resets overrunPromptFired.
- impl: FocusScreen.kt:129 only tints the timer coral in overrun — there is no check-in copy and no extend/keep-going/stop buttons. AppViewModel.extendFocus(minutes) (AppViewModel.kt:170, wraps FocusTimer.extend) exists but has zero UI call sites (grep confirms). Add an overrun branch to the controls area showing the check-in text + Add-10/In-the-zone(+15)/Stop buttons wired to vm.extendFocus(10), vm.extendFocus(15), vm.finishFocus(task). Depends on the overrun-grace fix above, otherwise OVERRUN state is essentially never reached with the right copy.

### [Calendar scheduling: drag-to-s] Cannot edit a scheduled block from the calendar (no CalBlockEditModal equivalent)
- status: missing | web: components/calendar/today-timeline.tsx:382 ; components/calendar/cal-block-edit-modal.tsx:106-135
- web does: Clicking any task-backed block on the day timeline opens CalBlockEditModal — a full popover editing the task name, first physical action, estimate, life area, tags, and the block's date + start time, with Save writing both the task (upsertTask) and the block (upsertBlock, durationMinutes synced to estimate). Block click is wired via onClick={clickable ? () => setEditingBlockId(b.id) : undefined} where clickable = !!b.taskId.
- impl: DayGrid.kt: the absolutely-positioned block Box (lines 148-157) has NO clickable/onClick — add .clickable { editingBlockId = b.id } and build a CalBlockEditSheet (mirror cal-block-edit-modal.tsx) bound to the block; on save call a new vm.updateCalBlock(blockId, date, startTime, durationMinutes) plus an updateTask. NOTE the week-view path (CalendarScreen.kt:101) opens TaskDetailScreen, but I re-read TaskDetailSheet.kt — its only schedule control is a 'Schedule' button that auto-picks findFreeSlots(...).firstOrNull() and calls scheduleTask (creates a NEW block); estimate is rendered read-only ('${task.estimateMin} min', line 87) and there are no date/time/duration editors. So block schedule genuinely cannot be edited anywhere on Android.

### [Calendar scheduling: drag-to-s] Cannot move/reschedule an existing block by dragging it on the grid
- status: missing | web: components/calendar/today-timeline.tsx:155-172 ; lib/dnd-task.ts:30-32
- web does: Existing focus/active blocks are draggable; on drop the grid reads the dragged block id (readDraggedBlockId), reschedules it in place (upsertBlock with new startTime/date) and bumps the linked task's moveCount (bumpMoveCount) so the slip detector counts the move.
- impl: DayGrid.kt: only tray task chips are draggable (detectDragGesturesAfterLongPress at 189-196); the block Box (148-157) has no drag gesture. Add a long-press drag to the block, track the block id, and on drop call a new vm.moveBlock(blockId, date, startTime). Re-verified AppViewModel.kt: scheduleTask (124-145) always creates a NEW CalBlock(id=newUuid()), so it cannot serve as an in-place move; no moveBlock exists.

### [Calendar scheduling: drag-to-s] Cannot resize a block to change its duration
- status: missing | web: components/calendar/today-timeline.tsx:417-423 ; components/calendar/today-timeline.tsx:447-499
- web does: Each interactive block renders a TimelineResizeHandle on its bottom edge; dragging it adjusts durationMinutes in 15-min snaps (clamped 15–360) and commits via upsertBlock({...b, durationMinutes}).
- impl: DayGrid.kt block Box (148-157) has no resize affordance. Add a bottom-edge drag handle (small Box + detectDragGestures) converting vertical delta to minutes (HOUR_HEIGHT px = 60 min), snap to 15, clamp 15–360, and call a new vm.resizeBlock(blockId, durationMinutes). No resizeBlock in AppViewModel.kt.

### [Calendar scheduling: drag-to-s] Cannot delete / unschedule a block from the calendar
- status: broken | web: components/calendar/cal-block-edit-modal.tsx:147-150 ; components/calendar/cal-block-edit-modal.tsx:412-414
- web does: CalBlockEditModal has a 'Delete block' action calling removeBlock(block.id), which unschedules the task (block disappears, task returns to the unscheduled tray).
- impl: vm.unschedule(blockId) exists in AppViewModel.kt:147 (write?.deleteCalBlock) but is DEAD CODE — grep confirms no UI caller. Surface it: a Delete action in the new block-edit sheet, or a long-press/swipe on the grid block, wired to vm.unschedule(b.id).

### [Calendar scheduling: drag-to-s] Block-time feature (create a busy block + backing task) has no UI and the VM helper is semantically wrong
- status: broken | web: components/calendar/block-time-modal.tsx:65-102 ; components/calendar/block-time-modal.tsx:41-61
- web does: BlockTimeModal (wired in app/(product)/calendar/page.tsx:90 and week/page.tsx:59) lets the user create an arbitrary time block ('Training', 'Dentist', 'Standup') with label, date, FROM/TO time. It creates BOTH a real Task and a task-shaped CalBlock pointing at it (kind 'task'), shows a live duration ('2h 30m'), warns on overlaps via findConflicts, and toasts 'Blocked … — date time.' on success.
- impl: vm.blockTime(date, startTime, durationMinutes, label) exists in AppViewModel.kt:149-153 but is DEAD CODE (no caller) AND wrong vs web: it creates CalBlock(taskId="placeholder", kind=PLACEHOLDER) with no backing Task. Re-verified core/logic/CalBlockKind.kt:12 — taskId=="placeholder" classifies the block as PLACEHOLDER, so isTaskBlock is false and DayGrid renders it as a neutral c.bg2 block (no life-area color), unlike web's task block. Build a BlockTimeSheet (label/date/from/to, duration display, findConflicts warning), rewrite vm.blockTime to upsertTask(real Task) + upsertCalBlock(kind=TASK, taskId=task.id) + success toast, and add a 'Block time' entry point on the calendar screen.

### [Google Calendar connect + exte] No Google Calendar connect / OAuth consent flow at all
- status: missing | web: components/calendar/sync-flow.tsx:157-182, lib/sync/calendar-sync.ts:95
- web does: Tapping the Google provider card calls getGoogleAuthUrl(redirectUri), stores the server-issued CSRF state in sessionStorage ('unstuck-gcal-state'), and sets window.location.href to Google's consent URL; on return the ?code=&state= is exchanged via connectGoogle() for a stored refresh token. This is the entire entry point for connecting a calendar.
- impl: CalendarClient.authorize()/connectGoogle() exist in sync/CalendarClient.kt and SyncCoordinator exposes them as `calendar`, but grep confirms nothing calls them. androidx.browser IS already a declared app dependency (app/build.gradle.kts:125) yet CustomTabsIntent is unused in all Kotlin source. Add a GoogleConnectController (iOS App/Calendar/GoogleConnectController.swift exists as the mirror): call calendar.authorize(redirectUri), launch a CustomTabsIntent to AuthorizeResponse.url with redirectUri = the web callback, then capture the unstuck:// deep-link return in MainActivity and call calendar.connectGoogle(code, redirectUri, state).

### [Google Calendar connect + exte] No deep-link handler to capture the OAuth ?code=&state= return
- status: missing | web: components/calendar/sync-flow.tsx:111-147
- web does: On return to /calendar/sync?code=&state=, verifies the saved CSRF state, strips the code from the URL BEFORE the async connectGoogle() call (so a reload can't replay the one-time code), then exchanges it; on error surfaces 'That Google connect link expired' or 'Connect failed: <error>'.
- impl: MainActivity.kt onCreate (:31) and onNewIntent (:61) ONLY call client.handleDeeplinks(intent) (Supabase auth). The manifest intent-filter (AndroidManifest.xml:32-37) is a bare scheme='unstuck' with no host/path (comment mentions 'Google calendar bounce' but no branch exists). Add: if intent.data scheme=='unstuck' and host/path is calendar-callback, extract code+state, validate against stored state, route to a connect VM calling coordinator.calendar.connectGoogle(...). Custom Tabs can't return a value like iOS ASWebAuthenticationSession, so the deep-link is the only return path.

### [Google Calendar connect + exte] External Google events are never pulled into the calendar (no pullAndIngest)
- status: missing | web: lib/sync/google-sync.ts:152-197
- web does: pullAndIngest() fetches events via pullEvents(from,to), maps each to a kind='external' CalBlock with id g_<id>, reconciles deletions in the window, and writes them to the block cache so 'usable time' and the grids show real meetings. Auto-runs on connect, on a 5-min timer, and per visible month grid.
- impl: CalendarClient.pullEvents() exists but grep confirms it is never called. GoogleSyncMapping.externalEventToBlock/diffMinutes/isoToLocalYmd/isoToLocalHHMM exist in core/logic/GoogleSyncMapping.kt with tests but no runtime consumer. Hydrator.kt:51-58 already preserves external blocks across hydrate but nothing ever CREATES them; DayGrid.kt:144 already renders kind==EXTERNAL blocks blue — so the entire render path is wired and starved of data. Add a pull-and-reconcile fn in :sync calling calendar.pullEvents, mapping via externalEventToBlock, writing to cal_blocks. Mirror iOS AppModel.pullGoogleCalendar/ingestExternalBlocks.

### [Google Calendar connect + exte] No auto-sync scheduler (immediate pull on connect + 5-minute polling)
- status: missing | web: lib/sync/google-sync.ts:327-352, lib/supabase/bootstrap-listener.tsx:133,140
- web does: startAutoSync() probes listConnections for >=1, pulls immediately, then sets a 5-min setInterval to re-pull; bootstrap-listener.tsx:133 calls it after sign-in. stopAutoSync() (:140) clears it on sign-out/disconnect.
- impl: SyncCoordinator.kt handle() drives sign-in/out and syncNow() does flush+hydrate ONLY — no Google pull. SyncWorker.kt is a 30-min periodic job that also only calls syncNow() (flush+hydrate). Add a startAutoSync equivalent: on Authenticated, if calendar.listConnections() non-empty, run an immediate pull and schedule periodic pulls (coroutine delay loop or WorkManager). Stop on sign-out.

### [Google Calendar connect + exte] Push of local task blocks to Google (insert/patch) is absent
- status: missing | web: lib/sync/google-sync.ts:250-293, lib/use-tasks.ts:244-257
- web does: useCalBlocks.upsert fires pushBlockUpsert for non-external/non-placeholder blocks: INSERTs a Google event (restamping the returned externalEventId onto the block so future edits PATCH in place) or PATCHes the existing event. Resolves target via getPushContext (primary google connection, selectedCalendarIds[0] || 'primary').
- impl: AppViewModel.scheduleTask/blockTime (AppViewModel.kt:124-153) call write?.upsertCalBlock but never calendar.insertEvent/patchEvent. WriteThrough.upsertCalBlock (WriteThrough.kt:29-33) has no Google branch. GoogleSyncMapping.blockToIsoRange() exists but is unused. Add a push step after upsertCalBlock resolving the connection from store.connections, calling insertEvent then restamping externalEventId via a second upsert, or patchEvent when set. Mirror iOS AppModel.saveBlock.

### [Google Calendar connect + exte] No connected-accounts list or disconnect UI
- status: missing | web: components/calendar/sync-flow.tsx:184-200
- web does: Lists each CalendarConnection (displayName/accountEmail/provider) with a per-account 'Disconnect' button. onDisconnect calls disconnect(id), removes the row, clearExternalBlocks(id), invalidatePushContext(), stops auto-sync if none remain, shows 'Disconnected. Its events have been removed.'
- impl: store.connections() Flow is exposed as AppViewModel.connections (AppViewModel.kt:61) and Hydrator hydrates calendar_connections read-only (Hydrator.kt:34) — but grep confirms connections has NO consumer beyond its own declaration, and no disconnect action exists. CalendarClient.disconnect() exists, uncalled. Add a connected-accounts section (Settings or Calendar) listing connections with a Disconnect button calling calendar.disconnect(id) then a clearExternalBlocks-equivalent purge.

### [Collections: create, add item,] Create a new collection (entire creation flow is absent)
- status: missing | web: components/collections/new-collection-sheet.tsx:26-159; components/collections/collections-overview.tsx:107-108; lib/use-collections.ts:152-169; app/(product)/collections/page.tsx:42-47,68
- web does: CollectionsOverview shows a 'New collection' primary button (collections-overview.tsx:107-108) that opens NewCollectionSheet — a centered modal with a 40ms auto-focused name field ('What would you like to remember?'), a 6-swatch color picker (indigo/coral/green/amber/blue/violet, default indigo), Enter-to-create / Escape-to-close, Create disabled until name.trim() non-empty, and Cancel. commitNew() calls add({name,color}) which mints a uuid, computes sortOrder = max(sortOrder)+1, creates items:[], persists via dbUpsert, and router.push navigates straight into /collections?c=<newId>.
- impl: Add a NewCollectionSheet composable under app/.../ui/collections/ (mirror ui/tasks/NewTaskSheet.kt as a ModalBottomSheet). Add `data object NewCollection : Sheet` to the Sheet sealed interface in MainScaffold.kt:58-61 and render it in the `when(sheet)` block. Critically, the FAB at MainScaffold.kt:111 unconditionally does onFab={ showNewTask = true } for ALL tabs — when tab=='lists' it must instead open the new-collection sheet. Add `fun addCollection(name: String, color: String): String` to AppViewModel (next to upsertCollection at AppViewModel.kt:211) that computes max(sortOrder)+1 over vm.collections.value, builds ItemCollection(id=newUuid(), items=emptyList(), sortOrder=...), calls upsertCollection, returns the id so CollectionsScreen can push(Route.Collection(id)).

### [Collections: create, add item,] Pin / unpin a collection item
- status: missing | web: components/collections/collection-item-row.tsx:145-166; lib/use-collections.ts:210-215; components/collections/collection-detail.tsx:292-309
- web does: CollectionItemRow shows a pin button (hover-revealed; always visible when pinned) at collection-item-row.tsx:145-166 that calls onTogglePin -> togglePin(collectionId,itemId), flipping item.pinned (use-collections.ts:210-215). Pinned items rise into a 'Pinned' section above 'All', render a larger coral bullet, and surface first in the card preview.
- impl: The design ItemRow (design/.../Components.kt:182) only exposes onToggle (done) — no pin action. CollectionDetailScreen.kt:99,103 calls ItemRow(it.body, it.done==true, pinned=...){ toggle(it) } with only the done toggle. The Pinned/All sectioning ALREADY exists (CollectionDetailScreen.kt:54-55,97-104), so only the toggle ACTION is missing: extend ItemRow with an onTogglePin callback (or trailing pin icon) and wire vm.upsertCollection(col.copy(items = col.items.map { if(it.id==item.id) it.copy(pinned = !(it.pinned ?: false)) else it })).

### [Collections: create, add item,] Edit a collection item inline
- status: missing | web: components/collections/collection-item-row.tsx:95-135,32-36; lib/use-collections.ts:196-201
- web does: Clicking an item's text (collection-item-row.tsx:118-135) turns it into an inline input pre-filled with item.body; Enter or blur commits via onCommitEdit -> updateItem(collectionId,itemId,{body}) only when trimmed & changed (collection-item-row.tsx:32-36), Escape reverts. Draft re-syncs to realtime edits via useEffect on item.body (line 30).
- impl: CollectionDetailScreen.kt:99,103 renders rows via the read-only ItemRow exposing only onToggle — no edit affordance. Add an onCommitEdit(body) path (tap-to-edit BasicTextField inside ItemRow, or long-press) that calls vm.upsertCollection(col.copy(items = col.items.map { if(it.id==id) it.copy(body=trimmed) else it })). The model field exists; only UI + the whole-row upsert call are missing.

### [Collections: create, add item,] Remove a collection item
- status: missing | web: components/collections/collection-item-row.tsx:167-185; lib/use-collections.ts:203-208
- web does: Each row has a hover-revealed X (Remove) button at collection-item-row.tsx:167-185 that calls onRemove -> removeItem(collectionId,itemId), filtering the item out of the inline items array and re-upserting the whole collection row (use-collections.ts:203-208).
- impl: No delete affordance exists in CollectionDetailScreen.kt (rows only have the done checkbox). Add a remove action (trailing X icon on ItemRow or swipe-to-dismiss) calling vm.upsertCollection(col.copy(items = col.items.filter { it.id != item.id })).

### [Collections: create, add item,] Rename a collection
- status: missing | web: components/collections/collection-detail.tsx:69-74,138-174; lib/use-collections.ts:177-181
- web does: Clicking the collection title in the detail header (collection-detail.tsx:158-174) turns it into an inline italic-serif input ('Click to rename'); Enter/blur commits via onRename -> rename(id,name) which trims, ignores empty, and skips no-op renames (use-collections.ts:177-181), Escape reverts. nameDraft re-syncs to realtime renames via useEffect on collection.name (collection-detail.tsx:66).
- impl: CollectionDetailScreen.kt:71 renders col.name as a static Text(UFont.serifItalic(26)) with no rename. Make the title tappable to swap to an editable BasicTextField, committing via vm.upsertCollection(col.copy(name = trimmed)) (guard empty/no-op).

### [Collections: create, add item,] Delete a collection (with inline confirm)
- status: missing | web: components/collections/collection-detail.tsx:221-231; lib/use-collections.ts:171-175; app/(product)/collections/page.tsx:62
- web does: The detail view has a 'Delete collection' ghost button (collection-detail.tsx:227-231); clicking swaps to an inline confirm ('Delete this collection?' with coral Delete / ghost Cancel, lines 221-226). Confirming calls onDelete -> remove(id) (dbDelete + cache filter, use-collections.ts:171-175) then back() navigates to the overview (page.tsx:62).
- impl: AppViewModel.deleteCollection(id) ALREADY exists (AppViewModel.kt:212) but is never called from any UI. Add a Delete action to CollectionDetailScreen.kt (ghost button + inline confirm state or AlertDialog) that calls vm.deleteCollection(col.id) then onBack().

### [Insights / Analytics: report +] Time-range window switching (Week / Month / All time) is entirely absent
- status: missing | web: app/(product)/analytics/page.tsx:28-36,50-58,82,104-111; lib/analytics-window.ts:17-64
- web does: analytics/page.tsx renders TWO PillTracks: Report/Deep-dive mode AND Week/Month/All-time window. Window is read from ?window= (parseWindow), drives the 'REFLECTION · {windowLabel(window)}' eyebrow, and is passed into <Report window>/<DeepDive window>. Both components then call scopeSessions/scopeCaptures/scopeReasonLogs (filter by Date.parse(ts) >= startOf(window)) on every input before computing any chart, so every number changes with the window. windowLabel maps week→'WEEK SO FAR', month→'MONTH SO FAR', all→'ALL TIME'.
- impl: No analytics-window port exists in :core (grep for AnalyticsWindow/scopeSessions/windowLabel returns nothing in core or ui). 1) Create core/logic/AnalyticsWindow.kt porting lib/analytics-window.ts: enum AnalyticsWindow{WEEK,MONTH,ALL}, startOf(window,now) (Monday-00:00 / 1st-of-month / 0), scopeSessions/scopeCaptures/scopeReasonLogs filtering Time.parseMillis(ts) >= lo, windowLabel(). 2) In InsightsScreen.kt add a second MdSegment row (Week/Month/All) with state, scope sessions/captures/reasonLogs through these helpers before passing to every analytics fn, and make the SectionLabel eyebrow use windowLabel() instead of the hardcoded 'REFLECTION · ALL TIME'/'REFLECTION · WEEK SO FAR' strings at InsightsScreen.kt:56.

### [Insights / Analytics: report +] Report: 'When focus actually happens' weekday x area stacked-bar chart missing
- status: missing | web: components/analytics/report.tsx:96-148,356-407; lib/analytics.ts:21-45
- web does: Report renders StackedBars(realBars) where realBars=weekdayAreaHours(sessions,tasks): one stacked bar per weekday Mon-Sun (column-reverse), segmented by area using AREA_COLOR[Work/Personal/Home/Volunteering], per-day total label on top ('2.3h' or '·' when zero), max scale floors at 4.5h, an area-color legend row, and a 'sample' badge when below REAL_DATA_THRESHOLD.
- impl: weekdayAreaHours() is ported (Analytics.kt:32) but NOT imported in InsightsScreen.kt (top imports are only calibrationDots/calibrationHitRate/slipping/timeOfDayHeatmap). Add a Compose stacked-bar composable to the Report branch (deep==false) calling weekdayAreaHours(scopedSessions, tasks), drawing 7 vertical column-reverse bars with AREA_COLOR segments, per-day total labels, and an area-color legend row.

### [Insights / Analytics: report +] Report: estimate-calibration scatter plot missing (only hit-rate % stat present)
- status: partial | web: components/analytics/report.tsx:150-226,414; lib/analytics.ts:51-72
- web does: Report renders EstimateCalibration scatter: x=estimate, y=actual minutes, one dot per recent session (calibrationDots, cap 24), green dot if abs(a-e)<=5 else amber, a dashed y=x diagonal line, axis labels ('actual' top-left, 'estimate →' bottom-right), task name as title/tooltip, subtitle '{n}% of recent sessions landed within 5 min of estimate', and a 'sample' badge + learning copy below threshold.
- impl: Android surfaces only the scalar hit-rate via StatCard ('$hit%') at InsightsScreen.kt:66 and 75; the scatter visualization is absent. calibrationDots() is imported and used for the hit rate (InsightsScreen.kt:47) but never plotted. Add a Canvas-based scatter composable in the Report branch plotting calibrationDots(scopedSessions, tasks) with the y=x diagonal, green/amber dot coloring by abs(a-e)<=5, and axis labels.

### [Insights / Analytics: report +] Report: 'WORTH NOTICING' insight cards missing
- status: missing | web: components/analytics/report.tsx:318,418-482; lib/analytics.ts:189-238 (Kotlin) / 218-268 (TS)
- web does: Report renders up to 3 generated insight cards (topInsights) in a gradient section: strongest-weekday card ('Mondays are your strongest day. {n} focused minutes…'), calibration card ('Estimates within 5 min {n}% of the time…' with phrase tiers at 0.75/0.5), and a slipping-task card ('"{task}" keeps slipping. rescheduled N times / N+ weeks on the list'). Empty state shows 'Insights show up once you have a few sessions logged.' with serif-italic titles.
- impl: topInsights() is fully ported (Analytics.kt:189) but never imported/called in InsightsScreen.kt. Add a 'WORTH NOTICING' section to the Report branch calling topInsights(scopedSessions, tasks, scopedCaptures, scopedReasonLogs) and rendering each Insight title (serif-italic per design)/sub as a card, plus the empty-state card when the list is empty.

### [Command palette + quick captur] No ranked fuzzy scoring — results are unordered substring matches
- status: partial | web: components/command-palette/command-palette.tsx:35-43, 105-113
- web does: score() ranks every candidate: exact==1000, prefix==500-l.length, substring==200-l.length, else 0; the unified candidate list is mapped to scores, filtered s>0, sorted descending, sliced to top 12 (command-palette.tsx:35-43,105-113). Best/exact/prefix match floats to the top; shorter labels win ties.
- impl: CommandPalette.kt:50-61. Port score(label,query) into :core (e.g. core/logic/PaletteSearch.kt) returning Int. Build ONE unified candidate list (tasks+captures+areas+routes/actions), map->score, filter s>0, sortByDescending, take(12). Replace the three separate filter/take lists and the hardcoded 'taskResults + noteResults + actions' concatenation (no ranking, fixed section order). Note Android currently does its own per-list .take (8 tasks / 4 notes) and never sorts.

### [Command palette + quick captur] Life areas are not searchable in the palette
- status: missing | web: components/command-palette/command-palette.tsx:85-93, 271
- web does: Every life area is a candidate of type 'area', label=area.name, sub='Filter tasks by area'; selecting routes to /tasks?area=<encoded name> so Tasks opens pre-filtered, rendered with an AreaDot swatch and an 'AREA' badge (command-palette.tsx:85-93, 271).
- impl: CommandPalette.kt — collect vm.lifeAreas (already exposed at AppViewModel.kt:60 and used by AreasMenu). Add Result entries badge 'AREA' whose run() filters Tasks by that area. Plumb a new onArea(area) callback through MainScaffold.kt:127-133 that sets activeArea=area; tab='tasks'; stack.clear() — mirrors the existing AreasMenu onPick at MainScaffold.kt:149.

### [Settings & preferences: every ] Sound prefs are dead toggles — no chime / bell / completion sound playback exists
- status: broken | web: lib/use-sound.ts:76-94 (playChime/playBell/playCompletion gated on readBoolPref); call sites lib/use-focus-timer.ts:172,223,259 (NOT focus-mode.tsx as originally cited); toggles components/settings/settings-panel.tsx:60-62
- web does: Settings > Sound has three toggles (Session start chime default ON, Overrun gentle bell default ON, Completion sound default OFF). Each drives Web-Audio-synthesised playback gated on its toggle via readBoolPref: playChime() fires on session start (use-focus-timer.ts:223), playBell() at the overrun escalation (use-focus-timer.ts:172), playCompletion() on finish (use-focus-timer.ts:259). Each play function is a hard no-op when its toggle is off.
- impl: SettingsStore.kt:63-65 persists soundStartChime/soundOverrunBell/soundCompletion and SettingsScreen.kt:119-121 toggles them, but NOTHING reads them to play audio — grep of app+core+design finds zero ToneGenerator/SoundPool/AudioTrack/playChime; the only MediaPlayer is AmbientAudio (a separate, unused loop). Add a SoundPlayer (e.g. ui/surface/FocusSounds.kt via ToneGenerator or short raw assets) and call it from AppViewModel.startFocus (chime), the overrun transition, and finishFocus/markComplete (completion), each gated on the corresponding settings flag, wired from FocusScreen.kt state transitions.

### [Settings & preferences: every ] Ambient focus loop pref (off/brown/pink) does not start or switch audio
- status: broken | web: components/focus/ambient-player.tsx:15-16 (setAmbient(ambient) / cleanup setAmbient('off')); lib/use-sound.ts:139-167 (setAmbient brown/pink synthesis); toggle components/settings/settings-panel.tsx (PREF_SOUND_AMBIENT)
- web does: Settings > Sound 'Ambient focus loop' (off | brown | pink) is read by AmbientPlayer (mounted in focus-mode.tsx:218), which calls setAmbient(kind) to synthesise and loop brown- or pink-noise through the AudioContext for the whole focus session and stops it on 'off' / unmount (return () => setAmbient('off')).
- impl: AmbientAudio object exists (app/.../surface/AmbientAudio.kt) but has ZERO call sites — it is never started/stopped, and it only plays one fixed R.raw.ambient_focus file regardless of brown/pink/off. Invoke AmbientAudio.start/stop from FocusScreen.kt (DisposableEffect keyed on settings.ambient): 'off' => stop, 'brown'/'pink' => start, ideally distinct tracks/synthesis.

### [Settings & preferences: every ] Overrun check-in pref (Never / 5 / 10) is ignored — grace hardcoded to 1.0 in FocusScreen
- status: broken | web: lib/use-focus-timer.ts:64-78 (overrunGraceSeconds) + 55-62 (deriveState); toggle components/settings/settings-panel.tsx:54 (OVERRUN_OPTS)
- web does: Settings > Focus 'Overrun' selects the grace seconds before the timer escalates to OVERRUN: 'Never' => Infinity (never overrun), '5 min' => 300, '10 min' => 600. useFocusTimer reads PREF_FOCUS_OVERRUN via overrunGraceSeconds() and feeds it into deriveState.
- impl: core FocusTimer.deriveState already accepts overrunGraceSec and FocusTimer.overrunGraceSeconds(pref) exists in :core, but FocusScreen.kt:82 calls deriveState(l, nowMs, 1.0) with a hardcoded 1.0 — settings.focusOverrunMin is never threaded in. Map settings.focusOverrunMin (0=>Double.POSITIVE_INFINITY, 5=>300, 10=>600) and pass it to deriveState; reuse it for overrun-bell timing.

### [Settings & preferences: every ] Account: no Add/Change password flow
- status: missing | web: components/settings/password-row.tsx; lib/auth-helpers.ts:197 verifyCurrentPassword, :221 getSignInMethods, :304 updatePassword
- web does: Settings > Account PasswordRow detects sign-in methods (getSignInMethods): if no password (Google-only) it shows 'Add a password' and skips the current-password step; if a password exists it shows 'Change' and requires the current password as a re-auth gate (verifyCurrentPassword) before updatePassword(). Validates min length + confirmation match.
- impl: No equivalent on Android. AuthService.kt only has resetPassword (email link) — no getSignInMethods / verifyCurrentPassword / updatePassword. Add those to AuthService (updatePassword => client.auth.updateUser { password }, verifyCurrentPassword => re-sign-in) and a PasswordRow composable in AccountContent (SettingsScreen.kt).

### [Settings & preferences: every ] Account: no Delete-account flow
- status: missing | web: components/settings/delete-account-modal.tsx; components/settings/settings-panel.tsx Account section
- web does: Settings > Account 'Delete my account' opens a two-step modal: type your exact email to confirm, then invokes the 'account-delete' Edge Function (server-side auth.admin.deleteUser), signs out locally, clears storage, and redirects.
- impl: No delete row/modal anywhere (grep for deleteAccount/account-delete/DeleteAccount in app+sync finds nothing). Add a DeleteAccountModal composable and a vm.deleteAccount() that calls supabase functions.invoke('account-delete'), then signs out + clears local Room/prefs. The Edge Function already exists server-side.

### [Settings & preferences: every ] Account/Backup: Export everything is a non-functional stub
- status: missing | web: lib/data-export.ts:45 exportAll, :82 exportSummary; components/settings/settings-panel.tsx Account/Backup
- web does: Settings > Account 'Export everything' calls exportAll({email, displayName}) (data-export.ts:45) producing a JSON bundle of tasks/sessions/captures/etc., and a live total-row-count sub-label from exportSummary() (data-export.ts:82).
- impl: Android has TWO dead stubs: AccountContent 'Export everything' onClick is empty {} (SettingsScreen.kt:155) and Backup 'Export now' onClick is empty {} (line 143). No exportAll/data-export builder exists in :core/:data (grep finds none). Implement a JSON serialiser over all Room collections and emit it via an Android share/SAF intent.

### [Account / Auth: sign in/up/mag] Change / Add password in Settings is entirely absent
- status: missing | web: components/settings/password-row.tsx:23-198; lib/auth-helpers.ts:197-211,221-241,304-317
- web does: Settings → Account renders <PasswordRow/>. On mount it calls getSignInMethods() to read the user's identities; if an 'email' identity exists it shows label 'Password' + a 'Change' button, otherwise (Google-only) it shows 'Add a password' + 'Add'. The expand form requires the current password (verified via verifyCurrentPassword = re-sign-in) when one already exists, validates new password >= 8 chars and new===confirm, then calls updatePassword (supabase.auth.updateUser({password})). On success it re-reads getSignInMethods so the row flips Add→Change without reload.
- impl: AuthService.kt (only 73 lines, no updatePassword/verifyCurrentPassword/getSignInMethods): add suspend updatePassword(newPassword) -> client.auth.updateUser { password = newPassword }; add verifyCurrentPassword(email, pw) (re-sign-in, map error to 'Current password is incorrect.'); add getSignInMethods() reading currentUserOrNull().identities to compute hasPassword/providers/email. AppViewModel.kt: expose updatePassword/verifyCurrentPassword/getSignInMethods (currently only signIn/signUp/magicLink/googleSignIn/signOut are exposed). SettingsScreen.kt AccountContent (currently only Signed-in/Export/Sign-out rows): add a PasswordRow composable mirroring components/settings/password-row.tsx (Add vs Change label, current-password gate, 8-char + match validation, success/error states).

### [Account / Auth: sign in/up/mag] Forgot-password / reset-password flow has no UI (logic exists in :sync but is unreachable)
- status: missing | web: app/auth/forgot/page.tsx:27-99; lib/auth-helpers.ts:287-302
- web does: Sign-in page links to /auth/forgot which collects an email and calls resetPasswordForEmail(email, {redirectTo: /auth/reset}); on success shows a 'Reset link sent' confirmation. /auth/forgot also surfaces a ?reason=expired banner. The /auth/reset page guards on a recovery session, collects new password + confirm (>=8, must match), calls updatePassword, then routes to /dashboard.
- impl: AuthService.kt already has resetPassword(email) (line 52-54) but AppViewModel never wraps it and no UI calls it. Add a 'Forgot password' link on AuthScreen.kt (sign-in mode) → new AppViewModel.resetPassword wrapper → small dialog or ForgotPasswordScreen that calls AuthService.resetPassword and shows a 'check your email' confirmation. For completing the reset, add a reset-password screen that detects the recovery session (deep link) and calls updatePassword (see prior gap).

### [Account / Auth: sign in/up/mag] Delete-account flow is completely missing
- status: missing | web: components/settings/delete-account-modal.tsx:42-81; components/settings/settings-panel.tsx:174-188
- web does: Settings → Account has a red 'Delete my account' row opening DeleteAccountModal. The modal requires the user to type their exact email (case-insensitive match, guards the empty-email case), then invokes the 'account-delete' Supabase Edge Function (server-side auth.admin.deleteUser), checks data.deleted===true, then signs out, clears localStorage, and redirects to '/'. Errors from the function are surfaced inline.
- impl: Functions is already installed in SupabaseClientProvider.kt (line 34). Add AuthService.deleteAccount() -> client.functions.invoke('account-delete') and parse { deleted: true }/{ error }. AppViewModel: expose deleteAccount() that on success calls signOut + clears local Room/prefs. SettingsScreen.kt AccountContent: add a red 'Delete my account' row that opens an AlertDialog requiring the typed email to match currentEmail before enabling the destructive confirm (mirror delete-account-modal.tsx).

### [Account / Auth: sign in/up/mag] Data export ('Export everything' / 'Export now') is a no-op stub
- status: broken | web: lib/data-export.ts:45-86; components/settings/settings-panel.tsx:165-173
- web does: exportAll() serialises every user-owned collection (tasks, sessions, cal_blocks, reason_logs, captures, life_areas, tags, collections, calendar_connections metadata, adhd_struggles, today_filter) plus user email/displayName into a schemaVersion:1 JSON bundle and downloads it as unstuck-export-YYYY-MM-DD.json. exportSummary() counts total rows shown in the row sub-label ('N rows in your account').
- impl: SettingsScreen.kt has SettingRow('Export everything','JSON bundle'){} in AccountContent (line 155) AND SettingRow('Export now','One-shot JSON.'){} in the BACKUP section (line 143) — both empty lambdas that do nothing. Port lib/data-export.ts to :core (build an ExportBundle from the Room DAOs / repositories via store) and add an AppViewModel.exportAll() that serialises with kotlinx.serialization and writes via the Android Storage Access Framework (ACTION_CREATE_DOCUMENT) or shares the file. Wire a live row count into the sub-label like exportSummary.

### [Onboarding (struggles -> user_] ADHD struggles multi-select step is entirely absent
- status: missing | web: components/onboarding/flow.tsx:20-26 (STRUGGLES def), :53-58 (toggle), :79-93 (persist), :118-186 (step 2 UI)
- web does: Step 2 ('What gets you stuck?') renders 5 selectable struggle options (Starting / Sustaining / Switching / Stopping / Recovering, each with a one-line description), multi-select via checkbox state held in a Set, and on finish persists to localStorage ADHD_STRUGGLES + dispatches an 'unstuck-storage' event AND upserts user_preferences.adhd_struggles for signed-in users.
- impl: OnboardingScreen.kt: add a dedicated struggles step backed by a mutableStateListOf<String> with a STRUGGLES list of label+description pairs matching the web's 5; render selectable rows; pass the selected list into vm.completeOnboarding(selected) instead of emptyList(). The persistence path already exists end-to-end (AppViewModel.completeOnboarding -> PreferencesClient.setAdhdStruggles, Clients.kt:59-61), so this is purely the missing collection UI.

### [Onboarding (struggles -> user_] Struggles are never collected or persisted (completeOnboarding always called with emptyList)
- status: broken | web: components/onboarding/flow.tsx:79-93
- web does: On finish the selected struggles array is always written: localStorage.setItem(ADHD_STRUGGLES, JSON) + sb.from('user_preferences').upsert({user_id, adhd_struggles}). Confirmed the web round-trips it back via hydrate.ts:239/269/299-300 (reads adhd_struggles and re-writes localStorage).
- impl: OnboardingScreen.kt:62 (finish) and :122 (Skip) both call vm.completeOnboarding(emptyList()). AppViewModel.kt:233-235 only invokes PreferencesClient.setAdhdStruggles when uid != null AND struggles.isNotEmpty(), so with emptyList() the column is NEVER written from Android. After adding the struggles UI, pass the real selected list into both call sites. Note: Android hydrate does not yet read adhd_struggles back (grep found no read path in :sync), so even once written the value is currently write-only on Android — but the immediate, verified gap is that it is never written.

### [Onboarding (struggles -> user_] Onboarding does not start a 15-minute focus session on the created task
- status: missing | web: components/onboarding/flow.tsx:60-96 (finish starts timer + routes), :281-285 + :350-433 (SessionPreview / Begin focus)
- web does: finish() builds the first Task with estimateMin:15, then calls timer.start(task.id, 15) and router.push('/focus'), dropping the user directly into a running focus session. Step 5 ('Try it now') is a full session-preview screen (READY · 15 MIN, the first action as the headline, a 15:00 clock, a 'Begin focus' button) whose Begin triggers finish() and starts the timer.
- impl: OnboardingScreen.kt finish() never starts a session, and MainScaffold.kt:78 onDone={ onboarding = false } simply lands on Today without setting focusTask. To match: finish() should capture the TaskItem returned by vm.addTask (addTask returns the item, AppViewModel.kt:97-107) and hand it to onDone so MainScaffold sets focusTask = it. Entering FocusScreen auto-starts a session (FocusScreen.kt:63 LaunchedEffect { vm.startFocus(task) }, which uses task.estimateMin), so creating the task with estimateMin=15 plus routing into focus yields the 15-minute session. Also add a session-preview step mirroring web step 5.

### [Recurrence (editor UI, materia] No way to edit a recurrence after creation (detail-pane RepeatEditor + regenerateForTask flow absent)
- status: missing | web: components/tasks/task-detail-pane.tsx:194-234, 688-852
- web does: Task detail pane shows an 'Add repeat'/'Edit repeat' ghost button toggling a RepeatEditor (kind picker + weekly day toggles + 'Until' date). On Save it upserts the task with the new recurrence, then computes a diff via regenerateForTask using the task's earliest existing cal_block as anchor (sorted by date+startTime), or today @ 09:00 fallback, and applies it: mismatched future cal_blocks deleted, missing future occurrences added, past blocks kept as history. This is the ONLY way to change/clear a repeat on an existing task.
- impl: core/.../logic/Recurrence.kt already ports regenerateForTask (lines 60-91) but has ZERO callers in app/ (only materializeOccurrences is called, in AppViewModel.kt:133 inside scheduleTask). Add an 'Edit repeat / Add repeat' button + RepeatEditor section to app/.../ui/tasks/TaskDetailSheet.kt (meta grid is lines 84-95, no repeat affordance). On save: vm.updateTask(task.copy(recurrence=...)), then a new AppViewModel method (mirror task-detail-pane.tsx onSave 210-232) that picks anchor = earliest CalBlock for the task or today/09:00, calls regenerateForTask(next, recurrence, allBlocks, Clock.dateIso(startOfDay(now)), startTime, startDate, RECURRENCE_HORIZON_DAYS), then write.upsertCalBlock per toUpsert and write.deleteCalBlock per toDelete.

### [Recurrence (editor UI, materia] No 'Until' (end-date) editor in the recurrence picker
- status: missing | web: components/tasks/task-create-modal.tsx:660-715; components/tasks/task-detail-pane.tsx:808-842
- web does: Both the create modal and the detail RepeatEditor expose an 'Until:' control when a kind is selected: create modal shows a '+ Set end date' button (defaults to 4 weeks ahead) opening a date input with an 'Open-ended' clear button, plus helper text ('Materialises through the end date' vs 'Materialises 8 weeks ahead — set an end date to bound it'); RepeatEditor shows a date input + 'Open-ended' clear. The chosen until is stored on the Recurrence and bounds materialization inclusively.
- impl: app/.../ui/components/RecurrenceEditor.kt (lines 17-49) explicitly omits until (its own doc comment, lines 18-19, says 'until is intentionally omitted'). The model Recurrence.{Daily,Weekly,Monthly} all carry an `until` field (Models.kt:39-41) and materializeOccurrences/regenerateForTask already honor it (Recurrence.kt:43,47). Add an 'Until' date row (Material3 DatePickerDialog as in NewTaskSheet.kt:196-208) when mode != NONE with a set/clear toggle, emitting Recurrence.Daily(until)/Weekly(days,until)/Monthly(until). Without this every Android-created repeat is unbounded.

### [Captures & reason logs (create] Promote-to-task action on a capture is entirely missing
- status: missing | web: lib/capture-actions.ts:18-46; components/tasks/task-detail-pane.tsx:327
- web does: promoteCapture() creates a new Task with name = capture.body.slice(0,160) || 'Untitled task', estimateMin 25, priority 'medium', lifeArea 'Work', tags ['from-capture', capture.tag], then re-attaches the capture to the new task (capture.taskId ?? newTaskId so a session-sourced capture keeps its source link) and returns the new task id. Wired into the task detail pane ('Promote to task →') and the resume-support screen. NOTE: contrary to the original gap text, web does NOT actually compute a 'promoted' analytics metric — that phrase is only a comment in capture-actions.ts; no analytics.ts / analytics component reads the 'from-capture' tag.
- impl: Add promoteCapture(capture, nowISO) to core/.../logic/TaskMutations.kt mirroring lib/capture-actions.ts: new TaskItem(name = body.take(160).ifBlank{"Untitled task"}, estimateMin = 25, priority = Priority.MEDIUM, lifeArea = "Work", tags = listOf("from-capture", <serialized tag>)). Note CaptureTag has NO .serialName accessor — it uses @SerialName annotations (Enums.kt:49-55), so map the enum to its wire string ('follow-up','idea','edit','question','distraction') via a small helper rather than capture.tag.serialName. Expose AppViewModel.promoteCapture(capture) that upserts the task then upserts the relinked capture (taskId = capture.taskId ?: newTaskId). Add a 'Promote to task' affordance in TaskDetailSheet.kt's Captures section (currently TaskDetailSheet.kt:103-108).

### [Captures & reason logs (create] Discard / delete a capture is not possible (no delete path exists at all)
- status: missing | web: components/tasks/task-detail-pane.tsx:342; lib/use-tasks.ts useCaptures().remove
- web does: removeCapture(c.id) deletes a capture; surfaced as a 'Discard' button on each capture row in the task detail pane (task-detail-pane.tsx:342). Captures are a standard collection with full remove support.
- impl: WriteThrough.kt:65-70 has deletes only for TASKS/CAL_BLOCKS/TAGS/LIFE_AREAS/COLLECTIONS/SESSIONS — NOT captures (confirmed: no deleteCapture/removeCapture anywhere in Android main source). Add `suspend fun deleteCapture(id) = deleteLocalAndEnqueue(Tables.CAPTURES, id)`, expose AppViewModel.deleteCapture(id), and add a 'Discard' control per capture row in TaskDetailSheet.kt's Captures section.

### [Captures & reason logs (create] Task detail captures are read-only bullets — no tag pill, timestamp, per-row actions, or inline add-capture composer
- status: partial | web: components/tasks/task-detail-pane.tsx:281-358, 391-512
- web does: The 'Captures' section renders each capture as a surface card with a colored TAG pill (uppercased tag), a relative 'how long ago' timestamp, the body, and per-row 'Promote to task →' / 'Discard' actions; below the list an inline AddCaptureForm attaches a NEW capture to the open task (textarea + tag chips + 'Save capture'), creating a Capture with taskId set and sessionId null. Section header shows 'N thought(s)'. Empty state hints to add one or press C in /focus.
- impl: TaskDetailSheet.kt:103-108 only renders '• ${cap.body}' bullets capped at 8 — no card, tag pill, timestamp, actions, or composer, and no empty-state row. Add the tag pill (reuse CaptureSheet.kt's tag color map at lines 58-64), a relative-time label, per-row Promote/Discard buttons (depends on the promote + deleteCapture gaps), and an inline add-capture composer wired to vm.saveCapture(task.id, null, tag, body) — vm.saveCapture already exists (AppViewModel.kt:200).

### [Captures & reason logs (create] Pause-reason picker ('Why are you pausing?') never appears in focus — the toggle is dead and no ReasonLog is ever written from the UI
- status: missing | web: components/focus/pause-reasons.tsx:12-34; components/focus/controls.tsx:84,104-107
- web does: When the focus timer is in 'pause' state and PREF_FOCUS_PAUSE_REASONS is on (web default true), FocusControls renders <PauseReasons>: a chip list of ['Bathroom','Drink','Quick question','Stuck — need a moment','Other']. Tapping one calls useReasonLogs().upsert with {reason, taskId, action:'pause', at:now}, feeding the analytics pauseAnatomy chart.
- impl: FocusScreen.kt:140 Pause button just toggles vm.pauseFocus() with no reason UI. vm.saveReasonLog exists (AppViewModel.kt:205) but is never called from ANY UI (confirmed: only call site is its own definition); core's pauseAnatomy(reasonLogs) (Analytics.kt:112) is also never used by InsightsScreen — so reason_logs are never produced on Android and the pause-anatomy view has no data. The focusPauseReasons toggle exists at SettingsScreen.kt:116 but controls nothing. Correction to original gap: Android's focusPauseReasons default is FALSE (SettingsStore.kt:28,62), not true; web default is true — so even after wiring the picker, consider aligning the default. When paused and settings.focusPauseReasons is true, show a 5-reason chip row matching pause-reasons.tsx:12 calling vm.saveReasonLog(task.id, reason).

### [Life areas (CRUD, color tokens] No way to RENAME a life area
- status: missing | web: components/settings/life-area-panel.tsx:282 (MenuItem 'Rename') + lib/use-life-areas.ts:160-174 (update)
- web does: Each area row's dot-menu has a 'Rename' item that turns the name into an inline editable input; committing calls update(id,{name}) which persists and cascades the new name onto every tagged task.
- impl: SettingsScreen.kt AreasContent: the per-row DropdownMenu (line 182-184) only has 'Delete area'. Add a 'Rename' DropdownMenuItem that swaps the row name Text for a BasicTextField (mirror the add-area field at line 198), and on commit call a new vm.renameLifeArea(a, newName). Back it with AppViewModel.upsertLifeArea(a.copy(name=...)) plus a task cascade (see cascade gap). Confirmed: no rename path exists anywhere in the Android app (grep found only upsertLifeArea on create).

### [Life areas (CRUD, color tokens] Renaming/deleting an area does NOT cascade onto tasks (orphaned area labels)
- status: missing | web: lib/use-life-areas.ts:77-98 (cascadeRenameTaskArea), :171-173 (rename cascade), :182-183 (delete cascade)
- web does: useLifeAreas.update and .remove call cascadeRenameTaskArea: every task whose lifeArea matched the old name is rewritten (to the new name on rename, to undefined on delete), persisted, and re-upserted to Supabase, so filters/badges/analytics stay consistent.
- impl: AppViewModel.deleteLifeArea (line 219) calls write?.deleteLifeArea(id), which (WriteThrough.kt:68) is a bare local-delete + outbox enqueue with NO task rewrite. After the life_areas write, iterate vm.tasks.value and for each task with lifeArea==oldName upsert task.copy(lifeArea = newNameOrNull, updatedAt=isoNow()) via write?.upsertTask. Without this, deleting 'Work' leaves every Work task still tagged 'Work' but with no matching area row, so areaColorFor (Common.kt:128) falls back to ink4 (gray) and the task vanishes from no filter but its dot loses color. Confirmed: zero cascade logic in app/sync.

### [Life areas (CRUD, color tokens] Cannot REASSIGN or UNASSIGN a task's area after creation
- status: missing | web: components/tasks/task-detail-pane.tsx:922-1027 (EditableArea picker incl. Unassigned)
- web does: Task detail pane shows the area as a click-to-edit pill (EditableArea): clicking opens a listbox of every defined area plus an explicit 'Unassigned' option, and picking one updates the task's lifeArea (including setting it to null).
- impl: TaskDetailSheet.kt line 88: the 'Area' MetaCell renders a read-only Text(task.lifeArea ?: "—"). Make it tappable to open a picker (DropdownMenu or bottom sheet) listing vm.lifeAreas + an 'Unassigned' entry; on pick call vm.updateTask(task.copy(lifeArea = name-or-null)). NewTaskSheet (line 149-154) only sets area at creation, so there is currently NO path to change a task's area afterward. Confirmed.

### [Sync / realtime / offline / da] Data export (JSON bundle download) is a non-functional stub
- status: missing | web: lib/data-export.ts:45-86
- web does: exportAll(user) builds one schemaVersion:1 JSON bundle { exportedAt, user{email,displayName}, tasks, sessions, cal_blocks, reason_logs, captures, life_areas, tags, collections, calendar_connections (metadata only), preferences{adhd_struggles, today_filter} }, JSON.stringify(...,2) into a Blob, and triggers a browser download named unstuck-export-YYYY-MM-DD.json. exportSummary() returns { totalRows } summed over ARRAY_COLLECTION_KEYS (tasks, sessions, cal_blocks, reason_logs, captures) for the Settings UI.
- impl: No export code exists anywhere in :app/:sync/:core (grep for export/ExportBundle/ACTION_CREATE_DOCUMENT found only the dead UI rows + a code comment). Add an ExportService (in :sync) that reads every LocalStore.snapshot(table, serializer) for the 9 core tables plus device settings, assembles the schemaVersion:1 envelope via kotlinx.serialization, and writes it through the Storage Access Framework (ACTION_CREATE_DOCUMENT) so the user gets a real file. Wire the empty {} lambdas at SettingsScreen.kt:143 ('Export now') and :155 ('Export everything') to call it, and add a totalRows summary mirroring exportSummary(). Note: Android cal_blocks/captures/etc. are stored as full domain JSON, not connection-metadata-stripped — match the web shape (connection creds are already never stored client-side).

### [Sync / realtime / offline / da] Google Calendar two-way sync orchestration is entirely absent (no auto-pull, no push-on-write, no reconcile, no connect flow)
- status: missing | web: lib/sync/google-sync.ts:152-352
- web does: google-sync.ts is a full PULL/PUSH engine: startAutoSync() probes listConnections and, if >=1 connection, pulls immediately then every 5 min (AUTO_PULL_INTERVAL_MS); pullAndIngest() fetches events in the default [-7d,+14d] window, maps each via externalEventToBlock to a g_<id> external CalBlock, and reconciles deletions by dropping pre-existing in-window external blocks that didn't come back. Push fires on cal-block upsert/remove: pushBlockUpsert() inserts a Google event (minting externalEventId) or patches an existing one; pushBlockDelete() removes it. getPushContext() caches the primary google connection + target calendar; invalidatePushContext() clears it on (dis)connect. Status is emitted via unstuck-google-sync-status events.
- impl: CalendarClient.kt ports every Edge-Function call (authorize/connectGoogle/disconnect/listConnections/pullEvents/insertEvent/patchEvent/deleteEvent) and :core GoogleSyncMapping.kt ports the transforms (externalEventToBlock/blockToIsoRange/diffMinutes) — but grep confirms ZERO production callers: CalendarClient is only instantiated as SyncCoordinator.kt:32 `val calendar = CalendarClient(client)` and never invoked; GoogleSyncMapping has only a test file. Build a GoogleSync orchestrator in :sync that (1) runs a pull over the default window, maps to CalBlocks, and reconciles deletions into LocalStore preserving g_ rows (LocalStore.replace already supports preservePrefix); (2) adds push hooks in AppViewModel.scheduleTask/unschedule (AppViewModel.kt:124-147) calling insert/patch/deleteEvent for task-kind blocks and persisting the returned externalEventId; (3) caches a getPushContext + invalidates on connect/disconnect. Also add a Custom Tabs OAuth connect flow — CalendarScreen.kt (Day/Week/Month views) has no connect UI at all.

## MED (71)

### [Task CRUD + detail (create/edi] Schedule button is a one-shot auto-slot, not the full Schedule modal
- status: partial | web: components/tasks/task-detail-pane.tsx:142-144, :382-386; components/tasks/schedule-task-modal.tsx
- web does: Detail 'Schedule' opens ScheduleTaskModal (date pick, free-slot suggestions, manual time, conflict detection) and lets the user choose when to schedule.
- impl: TaskDetailSheet.kt:75-78 'Schedule' just grabs findFreeSlots(...).firstOrNull() and schedules silently. Reuse the WHEN/Time/conflict UI from NewTaskSheet.kt (findFreeSlotsForDate + findConflicts, both used there) as a ScheduleTaskSheet and call vm.scheduleTask(task, date, time) with the user's chosen slot.

### [Task CRUD + detail (create/edi] No delete confirmation dialog
- status: missing | web: components/tasks/task-detail-pane.tsx:93-95
- web does: Delete shows window.confirm('Delete "name"? This also removes any scheduled time blocks and captures attached to it.') and aborts if declined.
- impl: TaskDetailSheet.kt:109 Delete UButton calls vm.deleteTask(task.id) + onBack() immediately with no confirmation. Wrap onClick in an AlertDialog confirmation naming the task before deleting.

### [Task CRUD + detail (create/edi] No 'Move out of Later' action in detail
- status: missing | web: components/tasks/task-detail-pane.tsx:198-202
- web does: When task.later is true, detail shows a 'Move out of Later' button calling upsert({...task, later: false}).
- impl: TaskDetailSheet.kt: when task.later==true, show a button calling vm.setLater(task, false). vm.setLater already exists (AppViewModel.kt:116) but is not surfaced anywhere in the detail.

### [Task CRUD + detail (create/edi] No tag display or tag editing in detail
- status: missing | web: components/tasks/task-detail-pane.tsx:183-190
- web does: Detail meta grid has a 'Tags' cell with a TagPicker (compact) — view + add/remove tags, persisting via upsert({...task, tags: next, updatedAt}).
- impl: TaskDetailSheet.kt meta grid has no Tags entry. No TagPicker composable exists anywhere in the Android app. Add a reusable tag chip row + picker bound to vm.tags, persisting via vm.updateTask(task.copy(tags=...)).

### [Task CRUD + detail (create/edi] Schedule meta cell shows only 'Scheduled/Unscheduled', not the day+time or 'Later'
- status: partial | web: components/tasks/task-detail-pane.tsx:173, :668-686
- web does: scheduleSummary returns 'Later' (if task.later), else the earliest future-or-today block formatted as 'Today/Tomorrow/Wed, Jun 3 · 9:00 AM', else 'Unscheduled'.
- impl: TaskDetailSheet.kt:91 MetaCell('Schedule', if (blocks.any{taskId==id}) 'Scheduled' else 'Unscheduled'). Port scheduleSummary: handle task.later → 'Later'; filter task blocks where date >= today, sort, format earliest with core formatTime (used in NewTaskSheet).

### [Task CRUD + detail (create/edi] Capture section is read-only — no add, no promote-to-task, no discard
- status: partial | web: components/tasks/task-detail-pane.tsx:281-359, :391-512
- web does: Detail Captures section lists captures with tag pill + relative time, an inline 'Add a capture' composer (textarea + tag picker → upsertCapture), a 'Promote to task →' action (promoteCapture), and a 'Discard' action (removeCapture).
- impl: TaskDetailSheet.kt:103-108 only renders '• body' text for the first 8 captures. Add: an add-capture composer (reuse the DraftCapture UI from NewTaskSheet.kt:163-179) calling vm.saveCapture(task.id, null, tag, body) (exists, AppViewModel.kt:200); a discard action (add WriteThrough.deleteCapture + vm.deleteCapture — neither exists today); and promote-to-task (port lib/capture-actions.ts promoteCapture into :core, then a vm method). promoteCapture has no Android equivalent.

### [Task CRUD + detail (create/edi] Task list rows omit the schedule chip (today's start time)
- status: missing | web: components/tasks/list-row.tsx:132-136; components/tasks/task-list-pane.tsx:25-36, :213-214
- web does: ListRow shows a clock + start-time chip for tasks that have a today-dated cal_block (buildScheduleMap picks the earliest today block).
- impl: TasksScreen.kt rows (lines 97-111) only show name/area/estimate. Port buildScheduleMap (earliest today task-block per taskId) and render a clock+time chip. isTaskBlock + blocks are already available; core formatTime exists.

### [Task CRUD + detail (create/edi] Task list rows omit the 'NEXT' (start-next) badge
- status: missing | web: components/tasks/task-list-pane.tsx:69-72, :213; components/tasks/list-row.tsx:90-99
- web does: ListRow renders a coral 'NEXT' pill on the task chosen by pickStartNext (skipping done + the live task, honoring area filter).
- impl: TasksScreen.kt never computes startNext. Compute via core pickStartNext(tasks, blocks, liveTaskId, activeArea) (PickStartNext.kt:31 — already used in TodayScreen, not Tasks) and render a NEXT pill on the matching open row.

### [Task CRUD + detail (create/edi] Task list rows omit the recurrence (↻), tags, and first-action preview
- status: missing | web: components/tasks/list-row.tsx:112-120
- web does: ListRow shows a ↻ glyph when task.recurrence is set, a TagChipRow (up to 2 tags, clickable to filter by tag), and a '→ {firstPhysicalAction}' preview.
- impl: TasksScreen.kt row Column (lines 102-108) shows only name + area dot. Add a ↻ marker when t.recurrence != null, render up to 2 t.tags chips (tap → set activeTag filter), and a '→ firstPhysicalAction' line when present. No tag-chip composable exists yet.

### [Task CRUD + detail (create/edi] No slip-detection / Backlog review footer (slipMode toggle)
- status: missing | web: components/tasks/task-list-pane.tsx:74, :224-253; lib/visible-tasks.ts:46-53
- web does: A footer button reads 'AI noticed: N tasks aging on the list.' (slipCount via isSlipping); clicking filters the list to slipping tasks (slipMode=true) and the button becomes 'Filtered to N slipping tasks. Clear'.
- impl: TasksScreen.kt:66 hardcodes slipMode=false and has no footer. Add a footer button computing slipCount = tasks.count{ isSlipping(it, vm.nowMs()) } (VisibleTasks.kt:35), maintain a slipMode state, and pass it into visibleTasks(..., slipMode=...). Both helpers exist; isSlipping is unused in the app UI.

### [Task CRUD + detail (create/edi] No tag filter on the task list (activeTag)
- status: missing | web: components/tasks/task-list-pane.tsx:46-53, :156-189; lib/visible-tasks.ts:97-128
- web does: TaskListPane supports an activeTag filter (from ?tag= URL / clicking a tag chip) with a 'Filtering by tag #x · clear' banner; visibleTasks AND-combines it with the area filter across all views including Today.
- impl: TasksScreen.kt:66 calls visibleTasks without activeTag (defaults to null in VisibleTasks.kt:56). Add an activeTag state set by tapping row tag chips, render a 'Filtering by tag' banner, and pass it to visibleTasks(..., activeTag=...). Core visibleTasks already supports the activeTag param and applies it to every view including Today (VisibleTasks.kt:97-102).

### [Tags (vocabulary, tag filter, ] Inline tag creation from the picker (search-or-create)
- status: missing | web: components/tasks/tag-picker.tsx:85-95,259-283
- web does: Inside TagPicker's dropdown, when the typed query has no exact match, a '+ Create "query"' row appears (tag-picker.tsx:259-283); clicking it or pressing Enter with no match calls useTags().add(trimmed) (handleCreate, lines 85-95), which creates a TagRow and immediately selects it on the task. Lets users grow vocabulary without leaving the create/edit flow.
- impl: Part of the shared tag-picker composable for NewTaskSheet/TaskDetailSheet — when the filtered vocabulary has no exact match for the search text, show a 'Create "x"' option that calls vm.upsertTag(TagRow(newUuid(), x, null, vm.tags.value.size)) then adds x to the task's selected tags. Mirror tag-picker.tsx handleCreate. Depends on the picker composable existing (gaps 1/2), which it does not yet.

### [Tags (vocabulary, tag filter, ] Rename cascade across task.tags
- status: missing | web: lib/use-tags.ts:39-69,102-124
- web does: useTags().update remaps a tag's name across every task's denormalised tags array on rename (cascadeRenameTaskTag, use-tags.ts:39-69 invoked at 111-113), dedupes, persists tasks locally + best-effort upserts touched tasks to Supabase. Delete (remove, lines 116-124) scrubs the tag name from all tasks' tags arrays via the same helper with newName=undefined.
- impl: core or AppViewModel — no cascade on Android: vm.upsertTag (AppViewModel.kt:216) just writes the TagRow and vm.deleteTag (217) just deletes the row; neither touches task.tags, and no cascade helper exists in :core (grep of core finds only matchesTag/visibleTasks tag-filter logic). Add a cascade helper (port use-tags.ts cascadeRenameTaskTag) invoked on rename-via-upsertTag and on deleteTag: iterate vm.tasks, for each task whose tags contains oldName, write task.copy(tags = remapped/removed, updatedAt) via write?.upsertTask. Without it, renamed/deleted tags leave stale names on tasks (orphaned chips, broken filter matches).

### [Today / Start-Next / Up-Next /] Start-Next AI rationale chip missing
- status: missing | web: lib/start-next-rationale.ts:25-49; components/dashboard/start-next-card.tsx:101-106; app/(product)/dashboard/page.tsx:43,77-78
- web does: StartNextCard renders an AIChip 'AI · suggested' whose tooltip is rationaleFor(task, blocks, now): <10min to next block today → 'Quick win — only N min before {block}'; fits gap → 'Best fit for a N-min gap before {block}'; doesn't fit → 'Start now — fits a N-min window before {block}'; else 'Lowest-friction option for right now.' Dashboard computes this (startNextRationale) and passes it in.
- impl: lib/start-next-rationale.ts is NOT ported to :core (grep for rationaleFor/StartNextRationale/'Quick win'/'Best fit' across core+app returns no logic, only the literal 'Low friction' string already in StartNextHero). Port rationaleFor() into core/logic (e.g. StartNextRationale.kt) reusing core/time helpers; in TodayScreen.StartNextHero add an 'AI · suggested' chip and surface the rationale inline as the supporting line (no hover tooltip on mobile).

### [Today / Start-Next / Up-Next /] Start-Next card shows task name instead of firstPhysicalAction headline + missing 'First physical action' framing
- status: partial | web: components/dashboard/start-next-card.tsx:39-41,124,135,141
- web does: StartNextCard headline = task.firstPhysicalAction || task.name (the suggested next *action* is the big text); task name is a small eyebrow 'area · name' above it; subtitle 'First physical action. Nothing else right now.'
- impl: TodayScreen.kt StartNextHero: line 164 eyebrow already = '${'$'}{task.lifeArea} · ${'$'}{task.name}' (matches web), but line 166 big Text = task.name. Change headline to task.firstPhysicalAction ?: task.name (TaskItem.firstPhysicalAction exists, Models.kt:91) and add the 'First physical action. Nothing else right now.' subtitle (the existing line 167 '${'$'}{estimateMin} min · Low friction' is the metadata row, not the subtitle).

### [Today / Start-Next / Up-Next /] 'Pick another' action missing on Start-Next card
- status: missing | web: components/dashboard/start-next-card.tsx:166; app/(product)/dashboard/page.tsx:79
- web does: StartNextCard has a 'Pick another' line button (alongside 'Start now') that navigates to /tasks.
- impl: TodayScreen.kt StartNextHero (line 168) only renders the coral 'Focus' button. Add a secondary 'Pick another' UButton (ButtonKind.LINE) that switches to the Tasks tab. Note: TodayScreen currently has no callback for tab-switch — MainScaffold.kt:98-105 wires only onStartFocus/onOpen/onAvatar/onSearch/onInsights, so a new onPickAnother (or onTasks) param must be threaded through (MainScaffold owns `tab` state and already does tab switching at lines 111,131).

### [Today / Start-Next / Up-Next /] Usable-time-left panel absent
- status: missing | web: components/dashboard/time-remaining.tsx:24-86
- web does: TimeRemaining shows 'Usable time left' = total scheduled today − external(meeting) − placeholder(buffer) minutes, formatted h/m, with a progress bar (usable/total %) and 'Of {total} scheduled · {meetings} in meetings, {buffer} buffered for transitions.'
- impl: No equivalent on Android (grep for TimeRemaining/usableMinutes returns nothing in app/). Add a card to TodayScreen.kt: filter blocks to Clock.todayIso(), usable = sum(all today) − sum(isExternalBlock) − sum(isPlaceholderBlock) using the already-ported core/logic/CalBlockKind.kt predicates. Surface h/m value + progress bar + breakdown line.

### [Today / Start-Next / Up-Next /] Backlog pill + Backlog view on Today missing
- status: missing | web: components/dashboard/today-list.tsx:91-94,126-163,182-196,368-410
- web does: TodayList header has an amber 'Backlog' pill (mutually exclusive with area pills) with a count badge; tapping swaps the list to visibleTasks(view:'Backlog') (unscheduled + overdue) and clears the area filter. In backlog mode rows show an amber age chip (Nd). Empty states differ ('Backlog is clear…' vs 'Nothing scheduled today. Tap Backlog to see what to plan.').
- impl: core visibleTasks already supports TaskListView.BACKLOG (Enums.kt:71, VisibleTasks.kt:76-83) and daysSinceCreated (VisibleTasks.kt:44-48) is ported. TodayScreen.kt has NO backlog pill/state — the only 'Backlog' reference in app/ is TasksScreen.kt:41 (the separate /tasks tab). Add an amber Backlog FilterPill (count = visibleTasks(BACKLOG,…).size) alongside the area pills at TodayScreen.kt:130-133, a backlogActive state that switches `rows` to the backlog list and clears areaFilter, the amber age chip, and the differing empty-state copy.

### [Today / Start-Next / Up-Next /] Standard Today row omits schedule time, recurrence ↻, tags, and firstPhysicalAction hint
- status: partial | web: components/dashboard/task-row.tsx:324-379; components/dashboard/today-list.tsx:31-41,357-361
- web does: StandardTaskRow shows, beyond name+area: the scheduled start time with a clock icon (from buildScheduleMap over today's isTaskBlock blocks), a ↻ glyph if task.recurrence, up to 2 tag chips (clickable → /tasks?tag=), and a '→ {firstPhysicalAction}' hint when present, plus the Nm estimate.
- impl: TodayScreen.kt TaskRow (lines 222-247) only renders name + area dot/label + '${'$'}{estimateMin}m'. Add: scheduled start time (build a today scheduleByTask map from blocks via isTaskBlock, mirroring buildScheduleMap), a ↻ when task.recurrence != null (Models.kt:95), tag chips (TaskItem.tags exists, Models.kt:85), and the '→ firstPhysicalAction' hint (Models.kt:91). All fields already on TaskItem.

### [Today / Start-Next / Up-Next /] Paused-but-not-live task rich row (resume from partial progress) missing on Today
- status: missing | web: components/dashboard/task-row.tsx:40-44,68-69,113-149
- web does: TaskRow has a PausedTaskRow variant: any non-live task with totalFocused in (0, estimate*60) renders the rich amber-ring card ('Paused · {name}', 'paused at MM:SS') with a single 'Resume' that calls timer.start(task.id, estimate, {priorAccumulatedSec: totalFocused}) and navigates to focus — continuing from accumulated time, not restarting at 0.
- impl: TodayScreen.kt renders only the single live-session card (LiveSessionCard, lines 136-145) and a plain TaskRow for everything else; no isPausedTask branch. Add isPausedTask(task) = !done && totalFocused in 1 until estimateMin*60 (port task-row.tsx:40-44) and render the amber-ring card for such tasks. IMPORTANT wiring gap: core FocusTimer.start ALREADY supports priorAccumulatedSec (FocusTimer.kt:68-89) and LiveSession.priorAccumulatedSec exists (Models.kt:203), BUT AppViewModel.startFocus(task) does NOT pass it (AppViewModel.kt:157-162) — it always calls FocusTimer.start without priorAccumulatedSec. So a resume-with-prior-progress entry point must be added to AppViewModel (e.g. startFocus(task, priorAccumulatedSec = task.totalFocused)) before this row can behave like web.

### [Focus mode: treatments, pause-] Start chime / overrun bell / completion sound not implemented
- status: missing | web: lib/use-sound.ts:76-94; lib/use-focus-timer.ts:170-175,223,259
- web does: use-sound.ts synthesizes three Web-Audio cues gated on prefs: playChime() on start (PREF_SOUND_START_CHIME default true, fired in timer.start), playBell() once on the running->overrun transition (PREF_SOUND_OVERRUN_BELL default true, fired via lastStateRef), playCompletion() on done() (PREF_SOUND_COMPLETION default false).
- impl: No sound-effect player exists in :core or :app — grep for ToneGenerator/SoundPool/playChime/playBell/playCompletion finds nothing. The three toggles (soundStartChime default true, soundOverrunBell default true, soundCompletion default false; SettingsStore.kt:29-31) are dead. Add a SoundFx helper (ToneGenerator or SoundPool) and call it: chime in startFocus, bell on the running->overrun transition (FocusScreen must track previous derived state since :core deriveState is recomputed per tick), completion in finishFocus, each gated on the matching pref.

### [Focus mode: treatments, pause-] Overrun pref (Soft overrun: Off / 5 / 10 min) is ignored
- status: broken | web: lib/use-focus-timer.ts:54-62,64-78,164
- web does: overrunGraceSeconds() reads PREF_FOCUS_OVERRUN: 'Never' => Infinity (never escalate), '5 min' => 300s, '10 min' => 600s, default 1s; deriveState uses this grace so the user can defer or disable the overrun check-in.
- impl: FocusScreen.kt:82 calls FocusTimer.deriveState(l, nowMs, 1.0) with a HARD-CODED 1.0 grace, ignoring focusOverrunMin (0=Never/5/10, SettingsStore.kt:25 default 5). FocusTimer.overrunGraceSeconds(pref) is ported in core (FocusTimer.kt:52-60) but has zero call sites. Compute grace from settings.focusOverrunMin (0 -> Double.POSITIVE_INFINITY, else min*60.0) and pass into deriveState — note the core helper takes a String pref, but the Android setting is an Int (focusOverrunMin), so compute the Double directly rather than reusing overrunGraceSeconds(String).

### [Focus mode: treatments, pause-] No idle 'Begin focus' state, estimate adjust, or calibration hint
- status: missing | web: components/focus/controls.tsx:86-95; components/focus/treatments/ambient.tsx:17-27,138-159
- web does: Before starting, treatments show an idle state: estimate clock, a 'Begin focus · N min' primary button and an 'Adjust estimate' button. Ambient additionally shows a data-driven calibration hint ('Last N <area> tasks averaged X min · estimate looks reasonable.') computed from >=3 past sessions in the same life area.
- impl: FocusScreen.kt:63 LaunchedEffect auto-fires vm.startFocus(task) on mount, so the user never sees an idle 'Begin focus' screen, can't adjust the estimate before starting, and gets no calibration hint. Add an idle phase (state==IDLE, before calling startFocus) showing the estimate + a Begin button + Adjust estimate, and port calibrationHint from ambient.tsx (vm.sessions filtered by sameArea ids, threshold >=3). FocusTimer.deriveState already returns IDLE when sessionStart==null, so simply not auto-starting is the lever.

### [Focus mode: treatments, pause-] Resume-support / re-entry screen absent
- status: missing | web: components/interruption/resume-support.tsx:49-328
- web does: ResumeSupport rebuilds context after the user has been away: 'YOU LEFT N minutes ago' (from pausedAt||sessionStart), task name, 'WHERE YOU LEFT OFF' (firstPhysicalAction), session-so-far (elapsed in focus, remaining of estimate), waiting captures with Promote-to-task, a tight-deadline warning vs the next calendar block, and actions Resume / Reschedule rest / Pick something else.
- impl: No full re-entry screen exists on Android. TodayScreen.kt:130-217 has a small ActiveSession card (running->coral ring + Pause; paused->amber ring + Resume; tap-to-return) but NOT the full context rebuild. Add a ResumeSupport composable mirroring resume-support.tsx using vm.liveSession (pausedAt/sessionStart for 'how long ago'), the resolved task (task.firstPhysicalAction exists on TaskItem, Models.kt:91), vm.captures, and vm.blocks for the next-block deadline check; wire Resume->vm.resumeFocus()+open focus, Pick-something-else->vm.cancelFocus(). Captures Promote-to-task also requires a promote port (see captures gap).

### [Focus mode: treatments, pause-] Done state is a mockup-only Reflect sheet that persists nothing; no Pick-next / Save-&-continue
- status: partial | web: components/focus/controls.tsx:152-164; components/focus/treatments/ambient.tsx:162-189; components/focus/focus-mode.tsx (onMarkComplete/onEndSession/onContinueLater/onPickNext)
- web does: After ending, treatments enter a 'done' state: 'You did it. MM:SS in focus.' summary with three actions — Mark complete (flips task.done), Save & continue later (back to dashboard, session already recorded), Pick next task (route to /tasks). The done palette/label changes (monk tag COMPLETE).
- impl: CORRECTION to the prior claim: FocusScreen.kt DOES already have a 'Mark complete' button (line 145-151, vm.finishFocus(task, markDone=true)) and a 'Done'=end-for-now button (line 142, vm.finishFocus(task)). The real gap is that both then open ReflectSheet.kt — a mockup-only 'How did that land?' (flow/okay/sticky/stopped) flow not present in the web product, whose Save AND Skip both just call onDismiss (it persists NOTHING; there is no Reflection model in :core — grep confirms). There is no 'Pick next task' action and no web-parity done summary. Also finishFocus runs BEFORE the user picks, so the done state can't branch markDone after the fact like web does. Recommend dropping/replacing ReflectSheet with a web-parity done summary that adds a 'Pick next task' action, OR making the reflection actually persist.

### [Calendar scheduling: drag-to-s] Click empty grid slot to create a task at that time
- status: missing | web: components/calendar/today-timeline.tsx:280-295
- web does: Clicking empty timeline space (target === currentTarget) opens TaskCreateModal prefilled with the anchored day's date and the snapped click time (snapY 15-min). Grid cursor is 'copy' to signal this.
- impl: DayGrid.kt grid Box (118-172) has no tap handler. Add detectTapGestures on the empty grid that converts tap.y (+ scroll.value) to a snapped HH:MM (reuse the drop() math at 94-98) and opens NewTaskSheet with prefilled date+time. Confirmed NewTaskSheet.kt supports schedule-on-create (whenSel/pickedDate/pickedTime, 83-107) but is not reachable from a grid tap; TaskCreateModal prefill props (prefillDate/prefillTime) exist on web (today-timeline.tsx:437-438).

### [Calendar scheduling: drag-to-s] Auto-sequence: pack unscheduled tasks into morning gaps
- status: missing | web: components/calendar/unscheduled-tray.tsx:35-76 ; components/calendar/unscheduled-tray.tsx:153
- web does: The unscheduled tray has an 'Auto-sequence' button packing up to 6 unscheduled tasks back-to-back into free morning slots (08:00–12:00), snapping each to 15-min duration and skipping taken intervals, creating a cal_block per task.
- impl: DayGrid.kt unscheduled tray (174-201) only lists draggable chips; no Auto-sequence action exists, and grep found no autoSequence in :core or :app. Port unscheduled-tray.tsx autoSequence (35-76) into core as a pure helper returning List<CalBlock> for a date given existing blocks + tasks, add an 'Auto-sequence' button to the tray, and upsert each returned block via vm.

### [Calendar scheduling: drag-to-s] No conflict/overlap warning when scheduling or moving on the calendar
- status: partial | web: components/calendar/cal-block-edit-modal.tsx:92-95 ; components/calendar/cal-block-edit-modal.tsx:369-391
- web does: Both CalBlockEditModal and BlockTimeModal show an amber 'Overlaps N things' warning listing conflicting blocks (findConflicts, excluding the block being edited).
- impl: findConflicts IS ported (core/logic/FreeSlots.kt:123, supports excludeBlockId) and IS wired into the UI — but only on task CREATE: NewTaskSheet.kt:106 computes conflicts and 137-139 renders an amber 'Overlaps …' callout. There is no conflict warning in any calendar editing/move/block-time path because those UIs don't exist yet. When building the block-edit/block-time/move sheets, call findConflicts(date, startTime, durationMin, blocks, excludeBlockId=b.id) and render an amber overlap callout listing blockTimeRange + taskName (blockTimeRange also exists in FreeSlots.kt:145).

### [Calendar scheduling: drag-to-s] Day timeline covers only 06:00–22:00 and never auto-scrolls; web is full 24h with auto-scroll to now
- status: partial | web: components/calendar/today-timeline.tsx:51-52 ; components/calendar/today-timeline.tsx:206-216
- web does: Web timeline spans the full 24h (FIRST_HOUR 0, LAST_HOUR 23) and on mount auto-scrolls to ~1h before the current hour (or 8 AM for non-today) so 'now' is visible; blocks before 6am or after 10pm are reachable.
- impl: DayGrid.kt hardcodes START_HOUR=6/END_HOUR=22 (54-55). Re-verified: the topMin>=0 guard at line 137 hides any block starting before 06:00, blocks after 22:00 overflow, and the NOW line is gated to 06:00–22:00 (162-164). There is no initial scroll (rememberScrollState at 81 with no LaunchedEffect). Widen to 0–23 and add a LaunchedEffect setting scroll.scrollTo((now.hour-1)*hourPx) on first composition for today.

### [Google Calendar connect + exte] No CSRF state generation/verification round-trip
- status: missing | web: components/calendar/sync-flow.tsx:120-126,176-177
- web does: Stores the SERVER-issued state from getGoogleAuthUrl in sessionStorage, and on return compares the returned state to the saved one, rejecting mismatches with an 'expired link' message, then clears it (sessionStorage.removeItem) after use.
- impl: CalendarClient.AuthorizeResponse already carries `state`; persist it (SharedPreferences or connect-VM field) when launching consent, compare against the deep-link `state`, then clear it before calling connectGoogle. Without this the CSRF binding the Edge Function expects is bypassed.

### [Google Calendar connect + exte] No 'Sync now' manual refresh action
- status: missing | web: components/calendar/sync-flow.tsx:149-155
- web does: When connected, a 'Sync now' button runs onRefreshNow → pullAndIngest and shows 'Pulled N events from Google' / 'Sync failed: <err>', plus a last-synced label.
- impl: No Android calendar screen exposes any pull/sync/refresh action — CalendarScreen.kt (Day/Week/Month) and SettingsScreen.kt have zero Google/connection refs. iOS has a toolbar arrow.clockwise calling pullGoogleCalendar. Add a manual sync button in the Android calendar UI wired to the new pull fn, surfacing count/last-synced.

### [Google Calendar connect + exte] Push of block deletions to Google is absent
- status: missing | web: lib/sync/google-sync.ts:297-320, lib/use-tasks.ts:259-269
- web does: useCalBlocks.remove fires pushBlockDelete when the removed block has an externalEventId: locally-owned blocks delete the Google event; external (Google-origin) blocks are skipped (only the local mirror drops).
- impl: AppViewModel.unschedule (AppViewModel.kt:147) calls write?.deleteCalBlock only. CalendarClient.deleteEvent exists, uncalled. Add: when deleting a block with externalEventId and kind != EXTERNAL, call calendar.deleteEvent(eventId, connectionId, calendarId). Mirror iOS AppModel.deleteBlock.

### [Google Calendar connect + exte] clearExternalBlocks (immediate purge of a disconnected account's events) is unported
- status: missing | web: lib/sync/google-sync.ts:111-119
- web does: clearExternalBlocks(connectionId?) removes kind='external' blocks (all, or just one connection's) from the cache and notifies subscribers, so the calendar stops showing those meetings immediately on disconnect.
- impl: No equivalent in :sync or :data. Add a store/WriteThrough helper deleting CalBlock rows where kind == EXTERNAL (and externalConnectionId == id when scoped) and emit the store update, invoked from the disconnect handler.

### [Google Calendar connect + exte] Pull reconciliation of deleted Google events not implemented
- status: missing | web: lib/sync/google-sync.ts:169-183
- web does: On each pull, external blocks within the pulled window whose g_<id> is not in the incoming set are dropped (survivors filter: keep external rows only if out-of-window OR present in incomingIds), so events deleted/moved-out in Google disappear locally; out-of-window blocks are preserved.
- impl: Part of the missing pull orchestration. When implementing the Android pull, include the window-scoped survivor reconciliation (google-sync.ts:171-183: build incomingIds set, keep external rows only if date<fromYmd||date>toYmd OR id in incomingIds, then upsert incoming).

### [Collections: create, add item,] Recolor a collection
- status: missing | web: components/collections/collection-detail.tsx:16,192-219; lib/use-collections.ts:183-185
- web does: The detail view has a 'Color' row (collection-detail.tsx:192-219) with 6 swatch buttons (COLLECTION_COLORS = indigo/coral/green/amber/blue/violet, line 16); tapping one calls onRecolor(color) -> update(id,{color}) (use-collections.ts:183-185), with the active swatch highlighted. Re-tints the header chip, card chip, and pinned accents.
- impl: No color row exists in CollectionDetailScreen.kt — the header only shows a static ColorChip(col.color) at line 70. Add a row of selectable swatches over the palette ['indigo','coral','green','amber','blue','violet'] (mirrors COLLECTION_COLORS; areaColor() already maps these) that call vm.upsertCollection(col.copy(color = picked)).

### [Collections: create, add item,] Fast-add refocus loop (quick capture into collection)
- status: partial | web: components/collections/collection-detail.tsx:58-62,80-88; lib/use-collections.ts:187-194
- web does: The detail fast-add input auto-focuses 60ms after open (collection-detail.tsx:59-62, keyed on collection.id) and, after each Enter (submit at lines 80-88), clears the draft then requestAnimationFrame(() => inputRef.focus()) refocuses itself so the user can rapid-fire items without re-tapping — comments call this 'the entire premise'.
- impl: CollectionDetailScreen.kt:60-64,80-87 adds an item on ImeAction.Done and clears the draft, but it neither auto-focuses on open nor keeps the keyboard up / refocuses after each add — every item requires re-tapping the field. Add a FocusRequester + LaunchedEffect(col.id) to focus on open, and re-request focus in add() after clearing the draft (keep keyboardActions onDone firing repeatedly).

### [Insights / Analytics: report +] Report: 'When interruptions happen' interruption histogram missing
- status: missing | web: components/analytics/report.tsx:228-288,415; lib/analytics.ts:79-95
- web does: Report renders InterruptionShape: a 10-bin histogram of interruption captures bucketed by minutes-into-session (interruptionBins, 3-min bins), x labels '0–3','3–6',… The peak bin is colored coral (rest primary-soft), and the narrative reads 'Most happen around the {lo}–{hi} min mark' (or 'No captures linked to sessions yet…' empty-state when no captures map to sessions), plus 'minutes into a session' caption.
- impl: interruptionBins() is ported (Analytics.kt:75) but not imported/rendered in InsightsScreen.kt. Add a bar-histogram composable to the Report branch calling interruptionBins(scopedCaptures, scopedSessions), highlight the peak bin in coral, render the 'X–Y' axis labels and the dynamic peak narrative + empty-state copy.

### [Insights / Analytics: report +] Deep Dive: missing 2 of 4 stat cards (Median session, Re-entry within 5m, Captures kept)
- status: partial | web: components/analytics/deep-dive.tsx:272-276,421-445; lib/analytics.ts:131-150 (Kotlin) / 148-169 (TS)
- web does: Deep Dive renders 4 DDStats: 'Focus this week' (Hh Mm across N sessions), 'Median session' (median actualSec→minutes, sub 'live from your data'), 'Re-entry within 5m' (pctFastReentry(reEntryDistribution(sessions)), gated to '—' below threshold), and 'Captures kept' (captures.length). All scoped by window.
- impl: Android deep branch shows only 'Focus' and 'On estimate' StatCards (InsightsScreen.kt:73-77); 'On estimate' is not one of the web deep-dive stats. Add Median session (median of scopedSessions.actualSec), Re-entry within 5m (needs a ported pctFastReentry helper — does NOT exist in core — over reEntryDistribution(scopedSessions), gated below REAL_DATA_THRESHOLD), and Captures kept (scopedCaptures.size).

### [Insights / Analytics: report +] Deep Dive: 'What pauses you' pause-anatomy chart missing
- status: missing | web: components/analytics/deep-dive.tsx:156-222,462-468; lib/analytics.ts:112-127 (Kotlin) / 120-144 (TS)
- web does: PauseAnatomy renders up to 6 horizontal bars (pauseAnatomy(reasonLogs)): reason label + proportional bar colored by minutes tier (green<5 / amber 5–10 / coral>=10) + a value label ('Nm' when any row has minutes, else '×N' count-only fallback). Empty state: 'No pause reasons logged yet…' and a 'sample' badge on the panel title when empty.
- impl: pauseAnatomy() is ported (Analytics.kt:112) and reasonLogs IS exposed on AppViewModel (AppViewModel.kt:57) but never imported/rendered in InsightsScreen.kt. Add a horizontal-bar composable to the deep branch calling pauseAnatomy(scopedReasonLogs), with the minutesAxis/count fallback logic and tier coloring.

### [Insights / Analytics: report +] Deep Dive: 'How fast you come back' re-entry distribution histogram missing
- status: missing | web: components/analytics/deep-dive.tsx:224-270,469-475; lib/analytics.ts:131-150 (Kotlin) / 148-169 (TS)
- web does: ReentryDistribution renders a histogram of minutes between consecutive same-task sessions (reEntryDistribution, 5-min bins, first 8 shown), bins 0 and 1 highlighted in primary color (rest primary-soft), x labels '0–5m','5–10m',…, plus an empty-state line. Also feeds the 'Re-entry within 5m' stat via pctFastReentry.
- impl: reEntryDistribution() is ported (Analytics.kt:131) but never imported/rendered in InsightsScreen.kt. Add a bar-histogram composable to the deep branch calling reEntryDistribution(scopedSessions) with the '0–5m' style labels, first-two-bins highlight, and empty-state copy.

### [Insights / Analytics: report +] Deep Dive: 'The slip detector' task list missing (only the count is surfaced)
- status: partial | web: components/analytics/deep-dive.tsx:278-324,478-484; lib/analytics.ts:156-169 (Kotlin) / 175-192 (TS)
- web does: SlipDetector lists each slipping task (slipping rows) as a dashed-amber row: AreaDot (resolved via a tasksByName→lifeArea map) + task name + 'moved N× · Nwk old'. Empty state explains the 3-week / 3-reschedule threshold.
- impl: slipping() is imported and Android computes slips.size for the Report-branch 'Gentle friction' StatCard (InsightsScreen.kt:49,68), but the detailed slip list (names, move count, weeks, area dot) is never rendered in the deep-dive branch. Add a list composable to the deep branch iterating slipping(tasks, vm.nowMs()) with AreaDot (resolve area via a name→lifeArea map) and the 'moved N× · Nwk old' label, plus empty-state copy.

### [Insights / Analytics: report +] Deep Dive: 'Captures by kind' capture-flow breakdown missing
- status: missing | web: components/analytics/deep-dive.tsx:326-399,486-492; lib/analytics.ts:173-180 (Kotlin) / 196-208 (TS)
- web does: CaptureFlow renders a single stacked proportion bar across capture tags (Follow-up/Idea/Edit/Question/Distraction) using per-tag count/total proportions, each band colored per TAG_BAND oklch colors, plus a legend listing each present kind with its percentage. Empty state shows a placeholder 'No captures yet' ink-3 band.
- impl: captureBreakdown() is ported (Analytics.kt:173) but never imported/rendered in InsightsScreen.kt. Add a proportion-bar + legend composable to the deep branch (note: web CaptureFlow currently reads useCaptures() unscoped, but mirror via captureBreakdown(scopedCaptures)), mapping CaptureTag to the TAG_BAND design colors and computing percentages, with the empty-state placeholder band.

### [Insights / Analytics: report +] Report observation cards use wrong data and below-threshold placeholder semantics not honored
- status: broken | web: components/analytics/report.tsx:306-353; lib/analytics.ts:17 (Kotlin) / 8 (TS)
- web does: Report gates on hasRealData = sessions.length >= REAL_DATA_THRESHOLD(5): ESTIMATES card shows '—' + 'finish 5 sessions to start tracking estimate accuracy' below threshold, the % only once 5+ sessions; RE-ENTRIES shows '—' below threshold and a 'GENTLE FRICTION' card for slips. Deltas read '{realDots.length} sessions tracked' and '{reentryWithNotes} had a capture attached' (count of sessions with a capture attached, not raw size).
- impl: InsightsScreen.kt:66-67 always shows '$hit%' and raw sessions.size regardless of the 5-session threshold, so a user with 0–4 sessions sees fabricated-looking 0% / 0 instead of the '—' / 'finish 5 sessions' guidance the web shows. Gate the StatCard values on sessions.size >= REAL_DATA_THRESHOLD (constant exists at Analytics.kt:17), match the web subtitle/delta copy, and compute the RE-ENTRIES delta as sessions.count{ s -> captures.any{ it.sessionId==s.id } }.

### [Command palette + quick captur] Capture/note results are dead — selecting one does nothing
- status: broken | web: components/command-palette/command-palette.tsx:76-84
- web does: captures (first 50) are candidates of type 'capture'; selecting one routes to /tasks (command-palette.tsx:76-84). Each shows a bolt icon and the capture tag as sub-label.
- impl: CommandPalette.kt:60 — the NOTE Result's run is { onDismiss() }, so tapping a matched note just closes the palette and navigates nowhere. Change to navigate to the Tasks tab like web, e.g. run = { onTab("tasks") } (onTab already wired at MainScaffold.kt:131).

### [Command palette + quick captur] Missing 'Start a focus session' route action
- status: missing | web: components/command-palette/command-palette.tsx:22
- web does: Web ROUTES include { 'Start a focus session' -> /focus } (command-palette.tsx:22), jumping straight into focus.
- impl: CommandPalette.kt:50-56 actions list has no focus entry. Add Result("Start a focus session", null, "ACTION"). Needs a new onStartFocus callback plumbed through MainScaffold.kt:127-133 to set focusTask (e.g. picked/next task) the way TodayScreen's onStartFocus does at MainScaffold.kt:100.

### [Command palette + quick captur] Missing Analytics/Insights route action
- status: missing | web: components/command-palette/command-palette.tsx:20
- web does: Web ROUTES include { 'Go to Analytics' -> /analytics } (command-palette.tsx:20).
- impl: CommandPalette.kt:50-56 — add Result("Go to Insights", null, "ACTION"). Plumb an onInsights callback through MainScaffold.kt:127-133 calling push(Route.Insights(false)) — same destination used at MainScaffold.kt:104/125.

### [Command palette + quick captur] promoteCapture (capture -> task) not ported anywhere on Android
- status: missing | web: lib/capture-actions.ts:18-46
- web does: lib/capture-actions.ts promoteCapture() turns a capture into a Task: name from capture.body (160 chars, fallback 'Untitled task'), estimateMin 25, priority medium, lifeArea 'Work', tags ['from-capture', capture.tag], then re-links the capture's taskId to the new task (preserves an existing taskId). Wired into 3 web surfaces: task-detail-pane.tsx:327, resume-support.tsx:240, cockpit.tsx:358 via upsertTask/upsertCapture handlers.
- impl: No Kotlin equivalent — grep for 'promote'/'from-capture' in app/ and core/ returns nothing. AppViewModel already has write.upsertTask + write.upsertCapture (AppViewModel.kt:105,202). Port promoteCapture into :core (e.g. core/logic/CaptureActions.kt) building a TaskItem with the same defaults+tags and returning the new id, plus vm.promoteCapture() that upserts the task and re-links the capture. Surface from the capture UI (task detail / focus capture review) as web does. Note: the capture-actions.ts comment claims analytics counts these as 'promoted', but neither web analytics.ts nor Android analytics actually reads 'from-capture' — so this is a missing capture action, not an analytics-data gap. It is in scope for this area since capture-actions.ts was cited.

### [Settings & preferences: every ] Soft exit confirmation pref does nothing — leaving focus is never confirmed
- status: missing | web: components/focus/focus-mode.tsx:31,64,73; toggle components/settings/settings-panel.tsx:55
- web does: Settings > Focus 'Soft exit confirmation' (default ON). focus-mode reads PREF_FOCUS_SOFT_EXIT and, when on AND the timer is running, confirms before leaving a focus session mid-way (focus-mode.tsx:31,64).
- impl: settings.focusSoftExit is persisted (SettingsStore.kt:61) and toggled (SettingsScreen.kt:115) but never read by any consumer (grep finds none). In FocusScreen.kt the '← Out' button calls onClose() unconditionally (line 92). Gate it: if settings.focusSoftExit and the session is running (not paused/idle), show an AlertDialog confirm before onClose().

### [Settings & preferences: every ] Pause-reasons pref does nothing — no pause-reason tagging UI on pause
- status: missing | web: components/focus/controls.tsx:84 (and import line 10); toggle components/settings/settings-panel.tsx:56
- web does: Settings > Focus 'Show pause reasons' (default ON). focus controls read PREF_FOCUS_PAUSE_REASONS and, when on, surface a PauseReasons picker on pause (controls.tsx:10,84) that feeds the reason_logs / pattern analytics.
- impl: settings.focusPauseReasons is persisted (SettingsStore.kt:62) and toggled (SettingsScreen.kt:116) but never read. FocusScreen.kt pause button (line 140) just calls vm.pauseFocus() with no reason capture. When focusPauseReasons is on, present a reason picker on pause and write a reason log via the existing vm.saveReasonLog(... ReasonAction.PAUSE) (AppViewModel.kt:205). (Default is also wrong — see separate gap.)

### [Settings & preferences: every ] Accessibility: Reduce motion pref has no effect
- status: missing | web: lib/use-accessibility-classes.tsx:11,19; app/globals.css:319-336; toggle components/settings/settings-panel.tsx (PREF_A11Y_REDUCE_MOTION)
- web does: Settings > Accessibility 'Reduce motion' toggles html.u-reduce-motion which forces animation/transition durations to ~0.001ms globally and also honours OS prefers-reduced-motion (globals.css:319-336).
- impl: settings.reduceMotion is persisted (SettingsStore.kt:55) and toggled (SettingsScreen.kt:125) but no theme/animation consumer reads it (grep across app+design+core finds only the store + toggle). UnstuckTheme (AppRoot.kt:39 / design/theme/Theme.kt) ignores it. Expose it via a CompositionLocal (LocalReduceMotion) from UnstuckTheme — the same pattern already used for fontScale at Theme.kt:144 — and have animation call-sites (Orbit in Mark.kt, breathing/progress arc in FocusScreen, the tween/animate* uses in Components.kt/Controls.kt/Common.kt/etc.) skip/shorten when true; optionally fall back to Settings.Global.ANIMATOR_DURATION_SCALE.

### [Settings & preferences: every ] Accessibility: High contrast pref has no effect
- status: missing | web: lib/use-accessibility-classes.tsx:13,21; app/globals.css:346-353; toggle components/settings/settings-panel.tsx (PREF_A11Y_HIGH_CONTRAST)
- web does: Settings > Accessibility 'High contrast mode' toggles html.u-high-contrast which strengthens --u-line / --u-line-2 / --u-ink-3 tokens and adds a focus outline for stronger contrast throughout (globals.css:346-353).
- impl: settings.highContrast is persisted (SettingsStore.kt:56) and toggled (SettingsScreen.kt:127) but never read by the theme (grep finds only store + toggle). Pass it into UnstuckTheme (design/theme/Theme.kt) and derive a higher-contrast color set (darker line/line2/ink3) when true, mirroring the web token overrides — threading it through the theme the same way fontScale/accent already are at AppRoot.kt:39.

### [Settings & preferences: every ] Account: no in-app Display name editing
- status: missing | web: lib/auth-helpers.ts:243 updateDisplayName; components/settings/settings-panel.tsx Account section
- web does: Settings > Account 'Display name' row shows the current name with an Edit affordance; inline input saves via updateDisplayName() (supabase user-metadata update of display_name/full_name) with validation (no blank) and inline error.
- impl: Android AccountContent (SettingsScreen.kt:152-158) only shows a read-only 'Signed in' row. AuthService.kt has signUp data set but NO updateDisplayName method. Add AuthService.updateDisplayName(name) calling client.auth.updateUser { data = {display_name, full_name} }, expose vm.updateDisplayName, and add an editable row (AppViewModel already surfaces vm.currentName at line 256).

### [Settings & preferences: every ] Backup section: live sync status / row counts / last-sync ping replaced by fabricated stubs
- status: broken | web: lib/use-sync-status.ts:12-67 (SyncStatus { lastSyncAt, rowCounts }); components/settings/settings-panel.tsx Backup section
- web does: Settings > Backup shows a live 'Sync is on / paused' banner driven by auth state, a 'Status' expander listing per-table row counts (useSyncStatus.rowCounts) and the last successful sync-ping timestamp (lastSyncAt), plus copy pointing to Account for export/delete.
- impl: Android Backup content (SettingsScreen.kt:141-144) is two fabricated rows: a hardcoded ToggleRow('Auto-export every Sunday', true){} (no such feature exists in web) and 'Export now'{} (dead). No useSyncStatus equivalent exists on Android (grep for SyncStatus/rowCounts/lastSync in app finds none). Replace with a real surface: signed-in banner, per-collection row counts (from Room counts), last-sync time; remove the invented 'Auto-export every Sunday' toggle.

### [Settings & preferences: every ] Life areas: no rename and no recolor in settings; missing weekly-hours stat
- status: partial | web: components/settings/life-area-panel.tsx:254-255 (open + hours stat), :282 Rename, :290-310 COLOR swatches, ~:311+ two-step Delete
- web does: Settings LifeAreaPanel row dot-menu offers Rename (inline edit committing update {name}), a COLOR swatch row to recolor (update {color}) with the active color highlighted, and a two-step Delete; each row shows BOTH per-area open count AND weekly focus hours ('{stats.open} open' + '{stats.hours}h this week').
- impl: Android AreasContent (SettingsScreen.kt:161-205) supports add + delete only; the MoreVert dropdown offers just 'Delete area' (line 183) and the row shows only '$open open' (line 179). vm.upsertLifeArea already exists (AppViewModel.kt:218), so add Rename (inline edit -> upsertLifeArea copy(name=...)) and a color-swatch picker (copy(color=...)) to the dropdown, and compute+show a weekly-hours stat (the :core Analytics weekdayAreaHours already produces per-area hours and can be summed).

### [Settings & preferences: every ] Tags: entire tag-vocabulary management panel is missing
- status: missing | web: components/settings/tag-panel.tsx:79-80 (onRename/onRecolor), :259 No-color option, :88 add with duplicate detection; lib/storage-keys.ts TAGS
- web does: Settings page has a full TagPanel beneath life areas: add / rename / delete / recolor (including a 'No color' option) the user-owned tag vocabulary (useTags, migration 010), with per-tag usage counts and duplicate detection on add. Tags drive /tasks?tag=NAME filtering.
- impl: Android has tags as a field on tasks plus vm.upsertTag/vm.deleteTag (AppViewModel.kt:216-217), but NO settings UI to manage the tag vocabulary — no Tags entry in the HUB list (SettingsScreen.kt:67-71) and no TagPanel composable. Add a TAGS SettingsSection + TagPanel composable backed by the vm tag list with add/rename/recolor/delete, mirroring AreasContent.

### [Account / Auth: sign in/up/mag] Display-name edit is missing
- status: missing | web: components/settings/settings-panel.tsx:402-472; lib/auth-helpers.ts:243-258
- web does: Settings → Account shows an inline-editable 'Display name' row (DisplayNameRow). Edit reveals a text field; Enter or Save calls updateDisplayName (supabase.auth.updateUser({data:{full_name, display_name}})), blank is rejected, Escape/Cancel reverts. Sub-label shows 'Currently <name>.'
- impl: AuthService.kt: add suspend updateDisplayName(name) -> client.auth.updateUser { data = buildJsonObject { put('full_name', trimmed); put('display_name', trimmed) } }, reject blank. AppViewModel: expose updateDisplayName (currentName getter already exists at line 256 but is read-only). SettingsScreen.kt AccountContent: the 'Signed in' row (line 154) is read-only; add an editable display-name row mirroring DisplayNameRow (text field + Save/Cancel + blank validation).

### [Account / Auth: sign in/up/mag] Sign-up does not handle the email-verification ('one more step') flow
- status: missing | web: app/auth/verify/page.tsx:34-67; app/auth/sign-up/page.tsx:63-79; lib/auth-helpers.ts:113-130
- web does: After a successful (non-existing) sign-up the web routes to /auth/verify, which polls Supabase getSession every 3s (5-min deadline) so the tab auto-advances once the email link is clicked, offers a 'Resend confirmation email' button (resendSignupConfirmation), and a 'Wrong email? Edit and try again' link.
- impl: AuthScreen.kt run{} (lines 59-68) treats AuthOutcome.Ok as silent success with no feedback; for email/password sign-up there is no 'check your inbox' state. Add a verify/awaiting-confirmation UI state after signUp success that polls client.auth.sessionStatus (or currentSessionOrNull) and offers resend. Add AuthService.resendSignupConfirmation(email) -> client.auth.resend(signup) and an AppViewModel wrapper (neither exists today).

### [Account / Auth: sign in/up/mag] Sign-up 'account already exists' anti-enumeration routing is unused
- status: missing | web: lib/auth-helpers.ts:164-191; app/auth/sign-up/page.tsx:69-76; core/.../logic/AuthErrors.kt:62-72
- web does: signUpWithPassword inspects the response (empty identities, email_confirmed_at without session, or last_sign_in_at without session) and returns alreadyExists; the sign-up page then routes the user to /auth/sign-in?existing=1 with a banner 'An account with this email already exists. Sign in below — or use Forgot password.'
- impl: The :core helper detectSignupAlreadyExists() is already ported (AuthErrors.kt:62-72) but is never called anywhere. AuthService.signUp (lines 34-42) swallows the result into AuthOutcome.Ok with no inspection of the returned user/session. Make signUp return whether the account already exists (read result identities / emailConfirmedAt / lastSignInAt and feed detectSignupAlreadyExists), then AuthScreen should switch to sign-in mode with an 'already exists, sign in' banner.

### [Account / Auth: sign in/up/mag] Sign-in 'email not confirmed' state + resend has no equivalent
- status: missing | web: app/auth/sign-in/page.tsx:54-107; lib/auth-helpers.ts:72-83,113-130
- web does: On password sign-in, if the error is email_not_confirmed the page sets errorIsConfirmation and renders a 'Resend confirmation email' button wired to resendSignupConfirmation, with a status message.
- impl: AuthService.signIn (lines 29-32) returns only AuthOutcome.Ok/Error(friendly) and loses the needsConfirmation distinction (the web fail() sets needsConfirmation when code===email_not_confirmed). Surface a needsConfirmation flag from signIn and, in AuthScreen.kt, show a 'Resend confirmation email' action wired to a new resendSignupConfirmation helper when the unverified-email error occurs.

### [Onboarding (struggles -> user_] 'First physical action' capture step is missing; first task is created without firstPhysicalAction or the 'onboarding' tag
- status: missing | web: components/onboarding/flow.tsx:62-74 (task fields), :224-280 (step 4 UI)
- web does: Step 4 ('The smallest physical move') captures firstAction via a textarea (plus an 'AI · break it smaller' chip and a 'Why this matters' explainer). finish() sets firstPhysicalAction = firstAction.trim() || undefined and tags:['onboarding'], lifeArea:'Personal', estimateMin:15. The session-preview headline shows firstAction || taskName.
- impl: OnboardingScreen.kt: add a first-physical-action input step (BasicTextField), and in finish() pass it via vm.addTask(..., firstPhysicalAction = ..., tags = listOf("onboarding")). addTask already accepts firstPhysicalAction and tags (AppViewModel.kt:91/94) and the model field exists (Models.kt:91). Currently finish (OnboardingScreen.kt:61) calls addTask with only name/estimateMin/lifeArea, so no firstPhysicalAction and no 'onboarding' tag are set.

### [Onboarding (struggles -> user_] Android added a life-area picker + treatment picker that the web onboarding does not have
- status: broken | web: components/onboarding/flow.tsx:60-96 (no area seed, no treatment write; lifeArea hard-coded 'Personal')
- web does: Web onboarding does NOT pick life areas (it hard-codes the first task's lifeArea:'Personal' and never seeds an area list during onboarding — areas come from useLifeAreas seed / server hydrate) and does NOT pick a focus treatment (treatment defaults to 'ambient' and is changed later in Focus/Settings). flow.tsx contains no area-seeding and no treatment write anywhere.
- impl: OnboardingScreen.kt step 1 (area picker, :81-92) and the treatment-picker step (:100-119) diverge from web and don't match its data flow. The area step actively upserts LifeAreas in finish() (:60) which web never does during onboarding; the picked treatment is discarded entirely (finish() never applies it — vm.setTreatment is never called), so that step has zero effect on the session. To reach parity, replace these with the web's struggles + first-physical-action + session-preview steps. Note: completeOnboarding (AppViewModel.kt:226-231) already seeds canonical areas when none exist, so the explicit per-area upsert in onboarding is also redundant with the seed path.

### [Recurrence (editor UI, materia] Recurrence label (recurrenceLabel) is never displayed anywhere
- status: missing | web: components/tasks/task-detail-pane.tsx:174; lib/recurrence.ts (recurrenceLabel)
- web does: Detail pane shows a 'Repeat' meta row rendering recurrenceLabel(task.recurrence) (e.g. 'Repeats Mon/Wed/Fri until Aug 1, 2026', 'Repeats weekdays', 'Repeats monthly'), falling back to '—' when none. This is how the user sees a task's repeat schedule.
- impl: core/.../logic/Recurrence.kt ports recurrenceLabel (lines 104-121) and it is fully tested, but has ZERO UI callers. Add a 'Repeat' MetaCell to the meta grid in app/.../ui/tasks/TaskDetailSheet.kt (around lines 90-93) showing recurrenceLabel(task.recurrence).ifEmpty { "—" }.

### [Recurrence (editor UI, materia] Clearing a recurrence is impossible (no edit path => regenerateForTask null-branch unreachable)
- status: missing | web: components/tasks/task-detail-pane.tsx:210-232; lib/recurrence.ts (regenerateForTask null branch)
- web does: Selecting 'Doesn’t repeat' in the detail RepeatEditor and saving passes recurrence=null to regenerateForTask, whose null branch deletes every FUTURE cal_block for the task while keeping past ones as history — letting the user stop a series without losing logged history.
- impl: Same fix as the 'Edit repeat' gap: once a detail-pane RepeatEditor + AppViewModel regen method exist, the 'Never' chip (RecurrenceEditor.kt:33 already emits onChange(null)) must route through regenerateForTask(next, null, ...) so future blocks are pruned. The :core null branch (Recurrence.kt:72-75) is correct but currently unreachable from any UI.

### [Captures & reason logs (create] deleteTask does not cascade-remove attached captures (and cal_blocks) client-side, risking orphan captures
- status: broken | web: components/tasks/task-detail-pane.tsx:92-102
- web does: Deleting a task first removes every cal_block AND every capture whose taskId matches the task, THEN removes the task — explicitly so realtime listeners don't pull them back as orphans. The confirm dialog says 'This also removes any scheduled time blocks and captures attached to it.'
- impl: AppViewModel.deleteTask (AppViewModel.kt:120) calls write?.deleteTask(id), which only deletes the task row (WriteThrough.kt:65). Before deleting the task, iterate captures.value.filter{it.taskId==id} and blocks.value.filter{it.taskId==id}, deleting each (captures need the new deleteCapture from gap 2; deleteCalBlock already exists at WriteThrough.kt:66). Mirror task-detail-pane.tsx:96-100.

### [Captures & reason logs (create] No resume / re-entry support screen — 'N captures waiting' with promote buttons is absent
- status: missing | web: components/interruption/resume-support.tsx:64-67,197-260
- web does: The ResumeSupport screen rebuilds context after an interruption: which task, where you left off, a 'N CAPTURE(S) WAITING · NO PRESSURE' list of up to 4 captures for the paused task (each with tag pill, body, and 'Promote to task →'), plus a re-estimate vs next calendar block and Resume/Reschedule/Pick-something-else actions.
- impl: No interruption/resume-support package exists in app/.../ui (confirmed: no 'interruption'/'resume.support' matches). Build a ResumeSupportScreen composable reading the paused live session's task, filtering captures by taskId (sorted desc, take 4), rendering a tag-pilled list with a promote action (reuses the promote gap), and routing Resume/Reschedule/Pick-else. Mirror components/interruption/resume-support.tsx:197-260.

### [Life areas (CRUD, color tokens] No way to RECOLOR a life area after creation
- status: missing | web: components/settings/life-area-panel.tsx:283-310 (COLOR swatch grid -> onRecolor) ; lib/use-life-areas.ts:160-167
- web does: The area dot-menu renders a COLOR row with all 8 color tokens as clickable swatches; clicking one calls update(id,{color}) and re-renders the dot/badges everywhere (left rail, task badges, calendar blocks).
- impl: SettingsScreen.kt AreasContent: in the per-row DropdownMenu (line 182) add a color-swatch row (reuse the `palette` list at line 166 + the existing ColorChip component, Components.kt:93) that calls vm.upsertLifeArea(a.copy(color=token)). Today the only place a color is set is on create (line 201); there is no recolor affordance. Confirmed real.

### [Life areas (CRUD, color tokens] Color choice missing on CREATE (auto-assigned by index instead)
- status: partial | web: components/settings/life-area-panel.tsx:398-416 (AddAreaForm color swatches) + components/layout/left-rail.tsx:412-431 (RailAddArea swatches)
- web does: Both the Settings AddAreaForm and the left-rail RailAddArea let the user pick the area color from the 8-token swatch row before saving; add(name,color) stores the picked token.
- impl: SettingsScreen.kt AreasContent line 196-202: the Add row has only a name BasicTextField + 'Add' button; color is force-assigned via palette[areas.size % palette.size] (line 201). Add a swatch row (ColorChip per token) with a selected-color state and pass it into LifeArea(...) instead of the modulo index. Confirmed: no color picker on create.

### [Life areas (CRUD, color tokens] No inline 'Add area' affordance outside Settings (areas-sheet / dashboard pills)
- status: missing | web: components/layout/left-rail.tsx:193-250 (Areas section headerAction '+' -> RailAddArea -> addLifeArea + navigate) + :363-440 (RailAddArea form)
- web does: The left rail AREAS section header has a coral '+' that opens RailAddArea (name + color), saves, and immediately navigates to /tasks?area=<new>. Areas can be created without leaving the work surface.
- impl: AreasMenu.kt (the mobile equivalent of the rail AREAS section) only renders 'All tasks', the areas, and the Unassigned bucket (line 51-59) — no add row. Add a '+ New area' row that opens an inline name+color editor or routes to Settings AREAS, calling vm.upsertLifeArea. TodayScreen.kt area pills (line 130-133) are also add-less. Confirmed: area creation only lives in SettingsScreen + onboarding.

### [Life areas (CRUD, color tokens] 'teal' color token unsupported (resolves to gray fallback)
- status: partial | web: components/ui/area-dot.tsx:31,40 (teal token + COLOR_TOKENS) + lib/use-life-areas.ts:38 (Health->teal seed)
- web does: Web defines 8 selectable tokens: indigo, coral, green, amber, teal, blue, violet, red (COLOR_TOKENS), and resolveAreaColor maps teal -> oklch(0.70 0.10 200). The seeded 'Health' area uses teal in web (COLOR_FOR.Health='teal').
- impl: Theme.kt areaColor() (lines 38-47) has no 'teal' case and there is no teal Color in UnstuckColors, so any area/task whose token is 'teal' falls through the when() to `else -> ink4` (gray). grep confirms 'teal' appears nowhere in Android Kotlin source. Add a teal Color to UnstuckColors and a 'teal' branch in areaColor(); also add teal to the SettingsScreen palette (line 166) and onboarding AREA_PALETTE (OnboardingScreen.kt:48) so it is selectable. CORRECTION to the gap's note: Android does NOT seed Health->'violet'. AppViewModel.completeOnboarding (line 227-229) seeds Work/Personal/Home/Health -> indigo/coral/violet/green, i.e. Health->'green'; OnboardingScreen assigns by user-pick index from AREA_PALETTE=[indigo,coral,green,amber,violet,blue]. Either way Android never uses teal for Health, diverging from web's teal. Impact is mainly cross-device: a teal area synced from web renders gray on Android.

### [Sync / realtime / offline / da] Sign-out / user-switch race guard (intended-user-id) is not re-checked on writes
- status: missing | web: lib/sync/bridge.ts:17-104
- web does: bridge.ts tracks intendedUserId; setIntendedUserId(userId) is set at the top of every dbUpsert/dbDelete and reset to null by cancelAllPending() on SIGNED_OUT. After each Supabase round-trip the code re-checks `if (intendedUserId !== userId)` and discards the result ('user changed mid-flight, ignoring'), so an in-flight write started as user A cannot land in user B's session.
- impl: SyncGateway.upsert/delete (SyncGateway.kt:19-25) inject user_id but never re-check the current auth user after the await; OutboxFlusher.flush(userId) (OutboxFlusher.kt:17-34) captures userId once and never re-validates against auth.currentUserId mid-loop. AuthService.currentUserId (AuthService.kt:60) is available for the check. Add an intended-user guard: re-read auth.currentUserId after each write and skip the dequeue / discard the result if it changed; clear an 'intended user' marker on the NotAuthenticated/sign-out branch (SyncCoordinator.kt:82). The Room outbox is persistent and re-flushes correctly so this is less acute than the web's fire-and-forget, but a stale upsert that resolves after a fast user-switch can still hit the new user's row before the next flush re-validates.

### [Sync / realtime / offline / da] Hydrate has no abort/cancel on sign-out (a slow fetch can repopulate after the cache wipe)
- status: missing | web: lib/sync/hydrate.ts:171-246
- web does: hydrate.ts builds a fresh AbortController per cycle, passes .abortSignal(signal) to every select, and cancelHydrate() (called from bootstrap on SIGNED_OUT) aborts in-flight selects. It also re-checks `if (signal.aborted)` post-fetch and bails, so a slow select that resolves AFTER the cache wipe cannot write the previous user's rows back into storage.
- impl: Hydrator.hydrate() (Hydrator.kt:26-36) runs sequential replace() calls with no cancellation; SyncCoordinator.handle drives it inline (SyncCoordinator.kt:79) and the NotAuthenticated/sign-out branch (:82-86) calls store.clearAll() without cancelling an in-flight hydrate. Run the hydrate in a cancellable child Job held on the coordinator, cancel it in the sign-out branch before store.clearAll(), and guard each replace() so a cancelled job skips the store write. Slightly mitigated vs web because the Authenticated branch awaits hydrate sequentially within one collect{} emission, but a sign-out arriving mid-hydrate (during a slow fetchAll) still races clearAll() and can re-land the prior user's rows.

## LOW (55)

### [Task CRUD + detail (create/edi] 'Mark done' label parity only — toggle already works both ways
- status: partial | web: components/tasks/task-detail-pane.tsx:146-152
- web does: Detail button toggles both ways: label switches between 'Mark done' and 'Mark not done', calling upsert({...task, done: !task.done}); un-completing clears completedAt so analytics re-stamp on recompletion.
- impl: TaskDetailSheet.kt:79 already calls vm.toggleDone(task); toggleDone uses applyCompletion which clears completedAt on un-complete (TaskMutations.kt:13-22) — so the un-complete clear is correct. Only the label differs: Android shows '✓ Done' vs web 'Mark not done'. Wording parity only; functionally complete.

### [Task CRUD + detail (create/edi] Status meta cell ignores the 'In progress' state
- status: partial | web: components/tasks/task-detail-pane.tsx:191
- web does: Status = 'Completed' | 'In progress' (totalFocused>0) | 'Not started'.
- impl: TaskDetailSheet.kt:92 MetaCell('Status', if (task.done) 'Completed' else 'Not started') misses the middle state. Add the task.totalFocused > 0 → 'In progress' branch.

### [Task CRUD + detail (create/edi] Sessions list lacks relative time, mm:ss, and estimate detail
- status: partial | web: components/tasks/task-detail-pane.tsx:236-279
- web does: Each session row shows 'today/yesterday/N days ago', mm:ss in focus, and '· est Nm'; the section subtitle is 'N attempts'.
- impl: TaskDetailSheet.kt:97-102 renders only '• Nm focused' (s.actualSec/60) for up to 6 sessions, no subtitle. Add howLongAgo(s.completedAt), formatMMSS of actualSec, '· est Nm', and a 'N attempts' subtitle.

### [Task CRUD + detail (create/edi] No 'Estimate history' / calibration section
- status: missing | web: components/tasks/task-detail-pane.tsx:361-381
- web does: Detail shows an Estimate-history card: 'Estimated N min after N sessions. Calibration improving.' (or 'Initial estimate — will calibrate after the first session').
- impl: TaskDetailSheet.kt has no Estimate-history card. Add one mirroring the web copy using task.estimateMin and taskSessions.size (taskSessions already computed at line 52).

### [Task CRUD + detail (create/edi] Detail header missing 'CREATED N days ago' and uses 'TASK' instead of 'UNASSIGNED'
- status: partial | web: components/tasks/task-detail-pane.tsx:116-127, :37-45
- web does: Header reads 'AREA · CREATED {today/yesterday/N days ago}' with an AreaDot; uses 'UNASSIGNED' when no area.
- impl: TaskDetailSheet.kt:60 prints '${(task.lifeArea ?: "Task").uppercase()} · CREATED' with no relative date and 'TASK' when unassigned. Add a howLongAgo(task.createdAt) helper and use 'UNASSIGNED' instead of 'TASK'.

### [Task CRUD + detail (create/edi] Backlog tab missing the per-row age chip
- status: missing | web: components/tasks/list-row.tsx:36-37, :137-152; components/tasks/task-list-pane.tsx:215
- web does: In the Backlog view, each row shows an amber 'Nd'/'today' chip from daysSinceCreated(task, now) indicating how long it has aged.
- impl: TasksScreen.kt: when view==BACKLOG, render an amber chip using core daysSinceCreated(t, vm.nowMs()) (VisibleTasks.kt:44 — exists but unused in the app UI).

### [Task CRUD + detail (create/edi] Empty-state copy is generic, not view-specific
- status: partial | web: components/tasks/task-list-pane.tsx:193-205
- web does: Empty list shows 'No completed tasks yet. They will collect here as you finish them.' for Completed, else 'Nothing here. Hit New to add a task.'
- impl: TasksScreen.kt:95 shows 'No {view} tasks.' Differentiate the Completed copy and add the 'Hit New to add a task' hint for other views.

### [Task CRUD + detail (create/edi] New-task sheet lacks tag selection and the Until/end-date repeat bound
- status: partial | web: components/tasks/task-create-modal.tsx:466-470, :593-718
- web does: TaskCreateModal has a TAGS picker (curated vocabulary, on-the-fly creation) applied at creation, and a REPEAT 'Until' end-date control with materialize-horizon copy.
- impl: NewTaskSheet.kt has no tag picker — vm.addTask (line 182) is called without tags (the param exists at AppViewModel.kt:91 but is never passed). The shared RecurrenceEditor.kt explicitly omits the Until date (see its line 18-19 comment: only mode chips + weekly day toggles, no end-date), whereas web exposes an Until control (task-create-modal.tsx:660-715). Add a tag chip picker bound to vm.tags passing tags into vm.addTask, and an Until date control to RecurrenceEditor.

### [Today / Start-Next / Up-Next /] Start-Next card is not tap-to-open-detail; only Focus
- status: missing | web: components/dashboard/start-next-card.tsx:43-55; app/(product)/dashboard/page.tsx:80-81
- web does: The entire StartNextCard is role=button: clicking the surface (or Enter/Space) selects the task into the detail pane (toggleSelect); only Start now / Pick another stop propagation. Tapping the card opens the task to inspect/edit, separate from starting focus.
- impl: TodayScreen.kt StartNextHero's outer Box (line 156) has no clickable. StartNextHero is currently called as item { StartNextHero(startNext) { onStartFocus(startNext) } } (line 126) — pass onOpen too and wire the Box's clickable to onOpen(task), keeping the Focus button's onClick separate. onOpen already exists on the screen (line 72) and routes to Route.Detail (MainScaffold:101).

### [Today / Start-Next / Up-Next /] Greeting shows weekly-focused instead of 'usable time today'
- status: partial | web: components/dashboard/greeting-header.tsx:28-41,101
- web does: GreetingHeader subtitle reads 'You have {N hours and M minutes} of usable time today.' computed from today's isTaskBlock durations (usableMinutes).
- impl: TodayScreen.kt lines 111-119 show 'This week · Xh focused' (computed from sessions over 7 days, line 96) instead of usable-time-today. Either add the web's usable-time-today line (compute sum of today blocks where isTaskBlock(b), reusing core CalBlockKind) or accept the divergence as a deliberate redesign — but the web's usable-time framing is currently absent. Severity low: it's a copy/framing divergence, not missing data.

### [Today / Start-Next / Up-Next /] NEXT badge on the Today list row missing (Start-Next task is excluded from list)
- status: missing | web: components/dashboard/today-list.tsx:238-249; components/dashboard/task-row.tsx:312-322
- web does: TodayList keeps the Start-Next task in the list and passes startNextId to TaskRow; the matching row renders a coral 'NEXT' pill so the user sees which open task is the recommendation in list context.
- impl: TodayScreen.kt line 93 actively REMOVES the startNext task from `rows` (filter `it.id != startNext?.id`). Web keeps it and flags it. To match web: keep it in rows and pass an isStartNext flag to TaskRow (lines 222-247) to render a coral 'NEXT' badge next to the name. Alternatively the current filter-out is defensible since the hero already surfaces it — hence low severity.

### [Today / Start-Next / Up-Next /] Area-filter overflow 'More ▾' menu + usage-based ranking + keep-active-visible missing
- status: partial | web: components/dashboard/today-list.tsx:29,47-72,103-107,202-217
- web does: TodayList shows at most MAX_INLINE (6) life-area pills ranked by open-task count (rankAreas); the rest collapse into a 'More ▾' radio popover. The active filter is always swapped into an inline slot so it stays visible even when it would overflow (splitInlineOverflow).
- impl: TodayScreen.kt lines 130-133 render ALL areas in a horizontalScroll Row in their raw `areas` order — no usage-based ranking, no overflow menu, no keep-active-visible. Horizontal scroll is a reasonable mobile substitute for the popover, but rankAreas (by open-task count) and the active-stays-visible behavior are absent. Port rankAreas/splitInlineOverflow from today-list.tsx:47-72 if exact parity is required; otherwise at minimum rank pills by open-task count.

### [Focus mode: treatments, pause-] Captures in focus: no Promote-to-task, no tag/timestamp, capped at 3, single-line input
- status: partial | web: components/focus/treatments/cockpit.tsx:64-71,322-374; components/focus/capture-modal.tsx:159-179,67-81
- web does: Cockpit's 'CAPTURES THIS SESSION' rail shows up to 5 task captures newest-first with tag color, timestamp, body, and a 'Promote to task →' action (promoteCapture). The capture composer is a multi-line textarea with Cmd/Ctrl+Enter to save and Esc to cancel.
- impl: FocusScreen.kt CapturesRail (167-177) lists only `takeLast(3)` bodies — no tag color, no timestamp, no Promote-to-task (grep confirms no promote/promoteCapture anywhere in app/ or core/). CaptureSheet.kt:53 uses a singleLine=true BasicTextField (web is multi-line). Add a promoteCapture port (-> vm.upsertTask + mark/remove the capture) and tag/time to the rail; sort newest-first and slice 5 instead of takeLast(3); make the capture field multi-line. AppViewModel.upsertTask exists for the promote target.

### [Focus mode: treatments, pause-] Soft-exit confirmation and right-rail collapse prefs are dead
- status: missing | web: components/focus/focus-mode.tsx:60-69; lib/storage-keys.ts:44-45
- web does: When PREF_FOCUS_SOFT_EXIT is on and a session is running, leaving focus (Esc) pops a confirm 'Leave focus mid-session? Your timer keeps running.' Cockpit's right rail can be collapsed via PREF_FOCUS_COLLAPSE.
- impl: MainScaffold.kt:90 BackHandler(focusTask != null){ focusTask=null } and FocusScreen '← Out' (line 92) close immediately with no confirm; the focusSoftExit pref (SettingsStore.kt:27 default true) and focusCollapseRail pref (SettingsStore.kt:26 default true) are stored but read nowhere in the focus UI (grep confirms no usage). Add a confirm dialog on exit when settings.focusSoftExit && running (both the BackHandler and the ← Out tap), and honor focusCollapseRail in the Cockpit captures rail.

### [Focus mode: treatments, pause-] Timer shows elapsed counting up, not the web's remaining countdown / overrun +MM:SS
- status: partial | web: components/focus/treatments/cockpit.tsx:76-79; components/focus/treatments/monk.tsx:56-60; components/focus/ring-timer.tsx:28-34
- web does: Treatments display TIME REMAINING as a countdown formatMMSS(max(0, estimateSec - elapsed)); in overrun they switch to '+MM:SS' over-estimate; idle shows the full estimate. Cockpit/ring show a 'TIME REMAINING'/'OVER ESTIMATE' label and 'of MM:SS'.
- impl: FocusScreen.kt:129 shows formatMMSS(elapsed) counting UP as the primary timer, plus a separate line 130 '${formatMMSS(remaining)} left' — inverted from web (web's primary is the countdown), and there is no '+MM:SS over estimate' overrun form (only a coral tint). Change the primary timer to the remaining countdown, and in OVERRUN show '+' + formatMMSS(elapsed - estimateSec), matching cockpit.tsx:76-79 / monk.tsx:56-60 / ring-timer.tsx:28-34. `remaining` is already computed (FocusScreen.kt:80).

### [Focus mode: treatments, pause-] Monk and Cockpit treatments lack their distinguishing content
- status: partial | web: components/focus/treatments/monk.tsx:33-48,95-127; components/focus/treatments/cockpit.tsx:142-166,377-424
- web does: Monk: minimal — a state tag (READY/IN FOCUS/PAUSED/STILL GOING/COMPLETE), a serif body line (firstPhysicalAction or task name), giant serif timer, controls; no treatment switcher. Cockpit: task + first-physical-action card, large timer with progress bar, captures rail AND an 'UP NEXT' card (pickStartNext excluding the current task).
- impl: FocusScreen.kt mostly only varies timer/label visibility: Monk hides the treatment switcher (line 101 gate) and the task name (line 125 gate) but does NOT render the firstPhysicalAction serif body line nor the READY/IN FOCUS/PAUSED/STILL-GOING/COMPLETE state tag. Cockpit shows a 3-item captures rail (line 132-134) but no 'UP NEXT' card and no first-physical-action card. Render firstPhysicalAction (TaskItem.firstPhysicalAction, Models.kt:91) and add an UP NEXT card via pickStartNext(tasks, blocks, task.id, null) (PickStartNext.kt:31 is ported and used elsewhere). Add a state-tag derivation mirroring monk.tsx tagFor.

### [Calendar scheduling: drag-to-s] No drop-position time hint during drag-to-schedule
- status: partial | web: components/calendar/today-timeline.tsx:127-138 ; components/calendar/today-timeline.tsx:320-342
- web does: While dragging over the grid, a dashed pill 'Drop here · HH:MM' follows the snapped slot (dropHintY/dropHintTime via snapY) so the user sees exactly where/when the task will land before releasing.
- impl: DayGrid.kt shows a coral drag ghost following the finger (205-215) but no snapped target line/time. In onDrag, compute the snapped HH:MM (reuse drop() math at 94-98) and render a dashed placeholder row + 'Drop here · HH:MM' label at the snapped y in the grid.

### [Calendar scheduling: drag-to-s] No 'Today' jump button and no 'Nh scheduled' total in the day header
- status: partial | web: components/calendar/today-timeline.tsx:260-271
- web does: Day header has prev/next arrows AND a dedicated 'Today' jump button, plus a running 'Nh scheduled' total (sum of block durations / 60), and friendly day labels (Today/Yesterday/Tomorrow/weekday).
- impl: DayGrid.kt day switcher (107-115) has ‹ / › arrows and a 'Today'/date label, but the label is static text (no tap to jump back to today when off-today) and there is no scheduled-hours total. Add a Today tap target that sets date = Clock.todayIso() when date != today, and a 'Nh scheduled' text from dayBlocks.sumOf { it.durationMinutes }/60. (MonthView at CalendarScreen.kt:143 has a Today button; the day view does not.)

### [Calendar scheduling: drag-to-s] No Start-now / Mark-complete / Open-in-tasks actions from a calendar block
- status: missing | web: components/calendar/cal-block-edit-modal.tsx:137-159 ; components/calendar/cal-block-edit-modal.tsx:402-410
- web does: CalBlockEditModal offers 'Start now' (timer.start + navigate to /focus), 'Mark complete' (sets task done + completedAt), and 'Open in tasks', so the user can act on a scheduled block without leaving the calendar.
- impl: No calendar-side block actions exist because day-grid blocks aren't clickable (DayGrid.kt 148-157). When building CalBlockEditSheet, add 'Start now' (vm.startFocus + navigate to focus), 'Mark complete' (vm.toggleDone), and 'Open in tasks' (onOpen). These actions exist in TaskDetailSheet.kt (Schedule/Mark done at 75-79, startFocus via onStartFocus) but are unreachable from the grid.

### [Google Calendar connect + exte] Visible-month-grid pull (events beyond default -7/+14d window) absent
- status: missing | web: components/calendar/month-full.tsx:116-126
- web does: month-full.tsx pulls events for the visible 6-week grid (gridStart..gridStart+35) whenever the anchor changes, gated on getSyncStatus().connectionCount>0, so months outside the default window still show external events.
- impl: Once the base pull exists, have the Android month/week screens (CalendarScreen.kt MonthView/WeekView) trigger pull(from,to) for the visible range on navigation, gated on a connection. CalendarClient.pullEvents already accepts arbitrary from/to.

### [Google Calendar connect + exte] Sync diagnostics + status surfacing (last pull/push result, errors) absent
- status: missing | web: components/calendar/sync-flow.tsx:485-526, lib/sync/google-sync.ts:127-147
- web does: Maintains module-level sync status (lastPull {count,error,at}, lastPush {action,ok,error,at}, connectionCount), emits an 'unstuck-google-sync-status' event, and renders a SYNC DIAGNOSTICS panel showing pull last-ran/N-events or failed, and push last insert/patch/delete succeeded/failed/skipped.
- impl: No Android equivalent of the status tracking. Add a StateFlow status holder in the pull/push layer recording last pull/push outcomes and expose it to a diagnostics/settings UI. Port google-sync.ts SyncStatus + getSyncStatus/emitStatusChange and the diagnostics section.

### [Google Calendar connect + exte] Provider picker (Apple / Microsoft / ICS rows + 'not wired' messaging) and scope/permission summary absent
- status: missing | web: components/calendar/sync-flow.tsx:157-182,236-401
- web does: Sync-flow shows a provider list (Google + Apple/Microsoft/ICS stubs that respond 'isn't wired in this build yet'), a 'what changes hands' scope summary, and a SUPABASE_CONFIGURED guard message.
- impl: No Android connect screen exists at all. When building the connect UI, optionally include the provider list + scope summary for parity. Lower priority since only Google is functional; Apple/MS/ICS are stubs even on web.

### [Collections: create, add item,] Collection subtitle display + persistence
- status: missing | web: components/collections/collection-card.tsx:72-84; components/collections/collection-detail.tsx:176-180; lib/types.ts:53-54
- web does: Collections carry an optional subtitle shown under the name on both the card (serif-italic, collection-card.tsx:72-84) and the detail header (collection-detail.tsx:176-180). Demo/seed collections ship subtitles; it round-trips through add/update and the DB row (use-collections.ts:31-39 collectionToDbRow writes subtitle).
- impl: ItemCollection.subtitle EXISTS in core (Models.kt:180) and is carried through DbRowCodec, but is never rendered or editable on Android. Render col.subtitle under the name in CollectionsScreen.kt card (after line 89) and CollectionDetailScreen.kt header (after line 71), optionally make it editable.

### [Collections: create, add item,] Card preview ordering: pinned-first + recent + 'N more' / 'Empty for now'
- status: partial | web: components/collections/collection-card.tsx:19-21,86-117
- web does: Each overview card previews up to 3 items computed as previews = [...pinned, ...nonPinned.slice(-3)].slice(0,3) (collection-card.tsx:19-21); shows 'Empty for now.' (serif italic) when previews.length===0 (lines 87-96) and a '+ N more' line when items.length > previews.length (lines 113-117).
- impl: CollectionsScreen.kt:90-94 just does col.items.take(2) — no pinned-first ordering, no empty state, no '+N more', and 2 instead of 3. Mirror the web preview computation (pinned first, then last 3 non-pinned, slice 3) and add the empty + overflow lines.

### [Collections: create, add item,] Item count pluralization in detail header
- status: missing | web: components/collections/collection-detail.tsx:182-189
- web does: Detail header shows the item count pluralized: '{n} item{n===1?"":"s"}' (collection-detail.tsx:182-189).
- impl: CollectionDetailScreen.kt header (lines 69-72) shows no item count at all (the card at CollectionsScreen.kt:87 shows a bare number, but the detail header doesn't). Add a count label like '${n} item${if(n==1)"" else "s"}' to the detail header.

### [Collections: create, add item,] Signed-out demo seed for collections
- status: missing | web: lib/use-collections.ts:42-121
- web does: useCollections seeds 6 demo collections (Groceries, Books to read, Quotes, Watch later, Recipes, Gift ideas) with items + subtitles into localStorage when auth resolved + user signed out + cache empty (use-collections.ts:42-121), so first-run / marketing demo is populated without an account. Friendly (non-uuid) ids never sync.
- impl: No equivalent demo seed exists for collections on Android — Hydrator.kt has no collection seeding, so the collections list is simply empty before sign-in/sync. Android is auth-gated (MainScaffold gates on onboarding/sign-in and the store hydrates from the server), so this is genuinely low priority; if a signed-out first-run state is ever supported, add an analogous seed in the data/sync hydrate path.

### [Insights / Analytics: report +] Deep Dive: time-of-day heatmap lacks per-cell minute values, hour-bucket column headers, and narrative
- status: partial | web: components/analytics/deep-dive.tsx:103-154,447-455
- web does: TimeHeatmap shows the 5x6 grid AND writes the minute value (Math.round(v*60)) inside each non-empty cell (white text when v>0.5), renders the hour-bucket column header row ('7–9','9–11','11–1','1–3','3–5','5–7'), and the DDPanel switches sub + narrative on heatmapHasData ('Hour-of-day × day-of-week, last 4 weeks. Color is total minutes.' / real-density narrative vs empty-state copy).
- impl: Android Heatmap (InsightsScreen.kt:85-105) draws colored cells and day rows with hardcoded 'Hour × day' / 'Brighter = more focus.' captions but omits per-cell minute numbers, the top hour-bucket header row, and the data-aware narrative. Add a bucket-header Row, render Math.round(v*60) centered in each non-empty cell, and switch the sub/narrative based on whether any cell > 0.

### [Command palette + quick captur] Captures only matched on non-empty query (web shows them on empty query too)
- status: partial | web: components/command-palette/command-palette.tsx:76, 35-36
- web does: On web captures are full candidates that hit the empty-query path: score() returns 1 for everything when query is empty, so captures (and areas/routes) appear in the initial top-12. Captures are capped to first 50 candidates (command-palette.tsx:76, 35-36).
- impl: CommandPalette.kt:59 — captures use 'q.isNotEmpty() && it.body...contains(q)', so they never surface on empty query. After unifying candidates + scoring (gap 1) captures appear on empty query; also apply captures.take(50) to match the web cap (Android currently .take(4)).

### [Command palette + quick captur] Dashboard route label mismatch (web 'Go to Dashboard' vs Android 'Go to Today')
- status: partial | web: components/command-palette/command-palette.tsx:17
- web does: Web's first route is 'Go to Dashboard' -> /dashboard (command-palette.tsx:17).
- impl: CommandPalette.kt:51 — Android labels it 'Go to Today' -> today tab. Functionally analogous (Today is Android's home surface, NavSpec 'today' at MainScaffold.kt:42). Naming-parity note only; no structural change required.

### [Command palette + quick captur] No empty-state / no-match messaging
- status: missing | web: components/command-palette/command-palette.tsx:191-200
- web does: Web shows a centered hint: 'Type to search across tasks, captures, areas, and routes.' when query empty & no results, and 'No matches.' when a query yields nothing (command-palette.tsx:191-200).
- impl: CommandPalette.kt:71-83 — the LazyColumn renders nothing when results is empty (note: Android actions always match on empty query so this is rarely visible today, but a non-matching query shows a blank list). Add an empty branch with query-dependent hint text mirroring command-palette.tsx:191-200.

### [Command palette + quick captur] No active-row highlight / keyboard-style selection affordance
- status: partial | web: components/command-palette/command-palette.tsx:167-176, 201-208, 217
- web does: Web tracks activeIdx, highlights the active row (bg-2), ArrowUp/Down move, Enter runs the active item, Esc closes, hover sets active (command-palette.tsx:167-176, 201-208, 217).
- impl: CommandPalette.kt:72-81 rows are tap-only with no highlighted state and no Enter-to-run from the field. Arrow-key nav is largely irrelevant on touch, but wiring the soft-keyboard IME 'Search'/Done action on the BasicTextField (CommandPalette.kt:67) to run results.firstOrNull()?.run() would match web Enter behavior.

### [Command palette + quick captur] Placeholder/scope copy understates searchable scope
- status: partial | web: components/command-palette/command-palette.tsx:177
- web does: Web input placeholder is 'Search tasks, captures, or jump to…' and the empty hint names tasks, captures, areas, and routes (command-palette.tsx:177, 198).
- impl: CommandPalette.kt:67 placeholder is 'Search tasks + actions' — accurate today since captures/areas aren't really searchable. Once gaps 1-3 land, update placeholder to match web scope.

### [Settings & preferences: every ] Pause-reasons default differs: web defaults ON, Android defaults OFF
- status: broken | web: components/settings/settings-panel.tsx:56; components/focus/controls.tsx:84
- web does: PREF_FOCUS_PAUSE_REASONS default is true: useLocalStorageBool(STORAGE_KEYS.PREF_FOCUS_PAUSE_REASONS, true) in settings-panel.tsx:56 and useLocalStorageBool(..., true) in controls.tsx:84.
- impl: SettingsStore.kt:28 sets focusPauseReasons default = false, and the load() getBoolean fallback on line 62 is also false. Change both to true to match web.

### [Settings & preferences: every ] Accessibility: Keyboard-only navigation / shortcut-hints pref is a pure no-op toggle
- status: missing | web: lib/use-accessibility-classes.tsx:14,22; app/globals.css:360-368; toggle components/settings/settings-panel.tsx (PREF_A11Y_KEYBOARD_HINTS)
- web does: Settings > Accessibility 'Keyboard hints' (default ON) toggles html.u-keyboard-hints which controls always-on visibility of inline keyboard-shortcut chips (.u-kbd fades to 45% opacity when off) (globals.css:360-368).
- impl: settings.keyboardHints is persisted (SettingsStore.kt:57) and toggled (SettingsScreen.kt:128) but never read. Android has no keyboard chips, so the toggle is a dead no-op. Closest parity is to either remove it from the Android A11Y card or wire it to a real hint surface; at minimum it should not present as a functional toggle that does nothing.

### [Settings & preferences: every ] Usable focus capacity (weekday/weekend minutes) + adhd_struggles never hydrated/read
- status: missing | web: lib/use-usable-pref.ts:26-37 (read + defaults 360/450); lib/sync/hydrate.ts:239 (select adhd_struggles, usable_minutes_per_day, usable_minutes_weekend) + :298-307 (mirror to localStorage)
- web does: user_preferences.usable_minutes_per_day / usable_minutes_weekend (and adhd_struggles) are server-first: hydrate.ts fetches the user_preferences row and mirrors all three into localStorage on every sign-in; useUsablePref feeds the calendar/dashboard 'load' math (defaults 360 weekday / 450 weekend).
- impl: Android Hydrator.kt fetches no user_preferences row at all (grep for usable_minutes/user_preferences in sync hydrate path returns nothing). Note adhd_struggles is partially handled: Clients.kt:59 / AppViewModel.kt:234 WRITE it during onboarding, but it is never read back. Add a user_preferences fetch to Hydrator, store usableMinutesPerDay/Weekend (+ adhd_struggles), and use them in capacity math. Caveat: Android currently has no usable-capacity/'load' display (grep finds none), so this is groundwork rather than fixing a visibly-broken surface — hence low severity.

### [Account / Auth: sign in/up/mag] Magic-link-sent confirmation screen + resend is missing
- status: missing | web: app/auth/magic-link-sent/page.tsx:26-73; app/auth/sign-in/page.tsx:75-90
- web does: After requesting a magic link, web routes to /auth/magic-link-sent showing 'Magic link on the way' addressed to the email, a 'Resend the link' button (re-calls signInWithMagicLink), and a 'try a different email' link.
- impl: AuthScreen.kt magicLink success (AuthOutcome.Ok handled by the empty branch at line 63) shows nothing — the user gets no confirmation the link was sent. Add a 'check your inbox' confirmation state after magicLink success with a resend action (re-call vm.magicLink) mirroring app/auth/magic-link-sent/page.tsx. Note: corrected androidStatus from 'partial' to 'missing' — there is no confirmation UI at all today.

### [Account / Auth: sign in/up/mag] getSignInMethods provider summary (Google + email) not shown anywhere
- status: missing | web: components/settings/password-row.tsx:41-54; lib/auth-helpers.ts:213-241
- web does: PasswordRow uses getSignInMethods().providers to tell the user which methods are configured, e.g. 'You sign in with Google. Add a password to also use email + password.' This gives users visibility into their linked auth providers.
- impl: Implement getSignInMethods() in AuthService (see password gap) and surface the provider summary text in the Settings AccountContent section, matching password-row.tsx's sub-label logic (lines 41-54). Subsumed by the PasswordRow port; track as the sub-label nuance.

### [Onboarding (struggles -> user_] First task is created with a name fallback differing from web and no always-create default
- status: partial | web: components/onboarding/flow.tsx:64 (name: taskName.trim() || 'My first Unstuck task')
- web does: If the task name is blank, web still creates a task named 'My first Unstuck task' (taskName.trim() || 'My first Unstuck task'), so onboarding always yields a starting task.
- impl: OnboardingScreen.kt:61 only creates a task when firstTask.isNotBlank(), so a user who leaves the field empty finishes onboarding with zero tasks. Mirror the web fallback: always call vm.addTask, defaulting the name to 'My first Unstuck task' when blank.

### [Onboarding (struggles -> user_] Step counter / labeling mismatch (web has 5 named steps; Android shows 4 generic 'STEP n OF 4')
- status: partial | web: components/onboarding/step-frame.tsx:16-51 (badge 'n / of' + title); components/onboarding/flow.tsx:114-285 (titles per step)
- web does: Web uses StepFrame with an 'n / of' badge and named titles per step ('Welcome', 'What gets you stuck?', "One thing you've been avoiding", 'The smallest physical move', 'Try it now') across 5 steps; steps 1 and 5 use a distinct 'dark' surface treatment.
- impl: OnboardingScreen.kt:74 hardcodes 'STEP ${step+1} OF 4' and :68 uses a 0..3 progress-dot row. Once the flow is realigned to the 5 web steps, update the counter to /5 and use the per-step titles. This is functional step labeling/navigation, not purely visual.

### [Onboarding (struggles -> user_] Confirm-then-commit 'edit the action' back-nav from the session preview is absent
- status: missing | web: components/onboarding/flow.tsx:39-51 (Enter advances), :412-424 ('edit the action' back), :337-344 ('Press ↵ anytime')
- web does: Web's session preview (step 5) offers a '← edit the action' link that returns to step 4 before starting, giving a confirm-then-commit step ('or press ↵' / ↵ advances on non-input steps via the keydown handler). The Enter-to-advance affordance is desktop-only and not applicable on touch.
- impl: When adding the session-preview step to OnboardingScreen.kt, include an 'edit the action' back action that returns to the first-physical-action step before Begin starts the session. The Enter-key behavior itself is not portable to Android touch and can be ignored.

### [Onboarding (struggles -> user_] Android onboarding has a 'Skip' that produces no task/struggles/session; web has no skip
- status: partial | web: components/onboarding/flow.tsx:435-453 (Footer Back/Continue only)
- web does: Web onboarding has no skip path — Footer is Back/Continue only — so every completed onboarding produces a first task, persists struggles, and starts a focus session.
- impl: OnboardingScreen.kt:122 'Skip' calls vm.completeOnboarding(emptyList()) and returns to Today, creating no task and no focus session, diverging from the web's guaranteed-output contract. Either remove Skip or have it still create the default first task. (Confirmed: web's Footer at flow.tsx:435-453 is Back/Continue only; the Welcome screen uses 'Let's start' — no skip anywhere.)

### [Recurrence (editor UI, materia] No '↻ Repeats' indicator on task rows
- status: missing | web: components/tasks/list-row.tsx:112; components/dashboard/task-row.tsx:231,335
- web does: List/dashboard task rows render a '↻' glyph (aria-label/title 'Repeats', fontSize 13) next to the area label when task.recurrence is set, so repeating tasks are visually distinguishable in lists.
- impl: Android task rows render only the name with no repeat glyph: TasksScreen.kt:103 (Text(t.name,...)) and TodayScreen.kt TaskRow at line 223 (name at line 234). Add a small '↻' glyph (or Material repeat icon) when task.recurrence != null beside the name/area label in both. Mirror the conditional render used in web list-row/task-row.

### [Recurrence (editor UI, materia] Weekly recurrence default day differs (Monday only vs weekdays)
- status: broken | web: components/tasks/task-create-modal.tsx:115; components/tasks/task-detail-pane.tsx:702-703
- web does: When the user first selects 'weekly', the web defaults the selected days to weekdays [1,2,3,4,5] (Mon–Fri), in both the create modal (recurrenceDays initial state, line 115) and the RepeatEditor (days initial state, lines 702-703).
- impl: app/.../ui/components/RecurrenceEditor.kt:35 seeds Weekly with listOf(1) (Monday only) when no days are set. Change the default to listOf(1,2,3,4,5) to match web so the first weekly selection materializes the same occurrences.

### [Recurrence (editor UI, materia] Repeat picker is shown even when WHEN = Later (web hides it / disallows repeat on Later)
- status: broken | web: components/tasks/task-create-modal.tsx:200-201, 593-594
- web does: The web create modal renders the entire REPEAT section only `when !== 'later'` (line 594), and buildRecurrence() returns null for Later ('Later cannot repeat', line 201). A deferred/Later task can never be given a recurrence.
- impl: In app/.../ui/tasks/NewTaskSheet.kt the RecurrenceEditor is always rendered (line 159) regardless of whenSel, and addTask passes recurrence even for Later (line 184) — AppViewModel.addTask takes recurrence and later as independent params (AppViewModel.kt:95-96), so a Later task can carry a recurrence that never materializes (scheduleTask is skipped for Later at NewTaskSheet.kt:187). Wrap RecurrenceEditor in `if (whenSel != "Later")` and reset recurrence=null when switching to Later, matching the web.

### [Captures & reason logs (create] Quick-capture composer is single-line vs web's multi-line note field
- status: partial | web: components/focus/capture-modal.tsx:159-179
- web does: The focus CaptureModal textarea is rows=4 (multi-line free text, 'The thing you don't want to lose track of…'), supporting paragraph-length thoughts; Cmd/Ctrl+Enter saves, Esc cancels.
- impl: CaptureSheet.kt:53 sets singleLine = true on the BasicTextField, truncating multi-line captures. Set singleLine = false (and a sensible maxLines) so long captures can be entered, matching capture-modal.tsx rows=4.

### [Captures & reason logs (create] Captures surfaced in the command palette are not actionable (Android-only polish, not a web→Android parity gap)
- status: partial | web: components/command-palette/command-palette.tsx (no capture results present — original webRef does not exist)
- web does: IMPORTANT CORRECTION: the web command palette does NOT surface captures at all (the cited 'capture results' do not exist in command-palette.tsx). Web only makes captures actionable in the task detail pane (promote/discard) and the resume-support screen. So Android is actually AHEAD here — it already lists matching captures as NOTE results — but those rows are dead.
- impl: CommandPalette.kt:59-60 builds NOTE results from captures but run = { onDismiss() } does nothing actionable. Make a capture result open its owning task (navigate via taskId, reusing onOpenTask) or offer promote, so a searched capture is actionable. Frame this as Android-only polish, not parity with web (web has no capture search).

### [Life areas (CRUD, color tokens] Per-area weekly focus-hours stat not shown
- status: missing | web: components/settings/life-area-panel.tsx:27-37 (statsFor hours) + :254-255 (renders open + hours)
- web does: Each settings area row shows two stats: 'N open' and 'Xh this week' — the latter sums actualSec of sessions in the last 7 days for tasks in that area (statsFor()).
- impl: SettingsScreen.kt AreasContent line 179 renders only Text("$open open"). Compute weekly hours from vm.sessions.value (filter sessions whose taskId belongs to a task in the area and whose completedAt is within 7 days, sum actualSec/3600) and render a second stat line. vm.sessions is already exposed. Confirmed.

### [Life areas (CRUD, color tokens] Per-area descriptive blurb missing (always 'Custom area.')
- status: partial | web: components/settings/life-area-panel.tsx:17-23 (AREA_BLURBS) + :81 (blurb lookup)
- web does: Area rows show a contextual blurb: seeded areas get curated text (Work='Day job · projects with deadlines', Health='Physio, gym, sleep hygiene', etc.) and custom areas get 'Custom area.'
- impl: SettingsScreen.kt AreasContent line 177 hardcodes Text("Custom area.") for every row. Add an AREA_BLURBS map keyed by name (Work/Personal/Home/Health/Volunteering) and fall back to 'Custom area.' to match web. Confirmed: every row shows the same string.

### [Life areas (CRUD, color tokens] No duplicate-name guard on add (case-insensitive)
- status: missing | web: lib/use-life-areas.ts:145-158 (add dup guard) + :105-116 (dedupeByName)
- web does: useLifeAreas.add trims the name and refuses to create an area whose name case-insensitively equals an existing one (returns early); dedupeByName also coalesces dupes on load.
- impl: SettingsScreen.kt AreasContent Add handler (line 201) calls vm.upsertLifeArea unconditionally when draft.isNotBlank(). Add: if (areas.any { it.name.equals(draft.trim(), ignoreCase=true) }) skip. Optionally dedupe in the hydrate/store path. Confirmed: only a blank check exists.

### [Life areas (CRUD, color tokens] Area color resolution has no canonical-NAME fallback for blank/unknown tokens
- status: partial | web: components/ui/area-dot.tsx:18-24,47-51,78-82 (NAME_TO_OKLCH + name fallback)
- web does: resolveAreaColor / AreaDot resolve color by token first, then by canonical NAME (Work->indigo, Personal->coral, Health->teal, Home->amber, Volunteering->green) so a seeded area whose color token is missing/empty still renders correctly.
- impl: Theme.kt areaColor(token) only matches color tokens (plus a few hardcoded collection names 'rethink','test','new feature','bug'); it has NO Work/Personal/Home/Health name fallback. areaColorFor (Common.kt:123-128) looks up the area by NAME in vm.lifeAreas and reads that row's color token, which covers tasks whose area exists with a valid token, but a row with a blank/unknown token still resolves to ink4 (gray). Add a name->token fallback map mirroring web's NAME_TO_OKLCH to complete the three-pass resolution. Confirmed real but narrow (most rows have a valid token, so impact is small).

### [Sync / realtime / offline / da] user_preferences hydrate (adhd_struggles, usable_minutes_per_day/weekend) is not pulled on sign-in
- status: partial | web: lib/sync/hydrate.ts:239-311
- web does: hydrate.ts selects user_preferences (adhd_struggles, usable_minutes_per_day, usable_minutes_weekend, limit 1) and, on success, writes adhd_struggles to ADHD_STRUGGLES and usable_minutes_per_day/weekend into USER_PREFS (consumed by useUsablePref for the calendar capacity/load math), so onboarding selections + capacity caps round-trip across devices.
- impl: PreferencesClient.setAdhdStruggles (Clients.kt:59) only WRITES struggles; nothing READS them or the usable-minutes back. Hydrator.hydrate() (Hydrator.kt:26-36) has no user_preferences fetch. Add a Hydrator step selecting user_preferences (limit 1) and persist the values. NOTE — severity lowered to low (proposal said med): there is currently NO consumer on Android. grep found no usableMinutes/capacity/adhd reading anywhere in :core or :app (the only struggles persistence is AppGraph.onboarded boolean), and there is no calendar capacity/load feature (the '360' hits are arc-drawing degrees, not minutes). So hydrating adhd_struggles would mainly let onboarding skip re-prompting on a 2nd device; the usable-minutes half has no UI to feed until a capacity feature is built.

### [Sync / realtime / offline / da] Collab/beta tables (trusted_circle, task_shares, body_double_sessions, coach_questions, feature_signals) are not hydrated or mirrored
- status: missing | web: lib/sync/hydrate.ts:234-297, lib/sync/realtime.ts:131-188
- web does: hydrate.ts pulls all five collab tables and replaceWriteArray's them (lib/sync/hydrate.ts:234-297); realtime.ts subscribes to all five so circle members / shares / coach questions / feature signals stay live across devices.
- impl: Hydrator.hydrate (Hydrator.kt:26-36) and RealtimeMirror.subscribeAll (RealtimeMirror.kt:40-66) only cover the 8 core tables; DbRowCodec.kt has no decoders for the collab rows (grep for TrustedCircle/TaskShare/BodyDouble/CoachQuestion/FeatureSignal found nothing in :sync/:core), and the Android UI surfaces none of these features (grep found zero collab usage in :app). Genuinely absent, but correctly low priority — only worth adding (models + DbRowCodec decoders + Hydrator.replace + RealtimeMirror.subscribe entries) if/when Android ships the collab UI.

### [Sync / realtime / offline / da] Last-successful-sync timestamp + per-table row counts are never recorded, so a sync-status surface can't show 'last synced'
- status: partial | web: lib/use-sync-status.ts:37-69, lib/sync/hydrate.ts:313-315
- web does: hydrate.ts writes LAST_SYNC_PING = new Date().toISOString() at the end of a successful hydrate (lib/sync/hydrate.ts:315); useSyncStatus exposes { signedIn, lastSyncAt, rowCounts } with rowCounts per ARRAY_COLLECTION_KEYS (tasks/sessions/cal_blocks/reason_logs/captures) for a Settings → Backup → Status surface to show when the last round-trip happened and how many rows are local.
- impl: AppViewModel exposes pendingCount (AppViewModel.kt:64) which is the outbox depth, but there is no lastSyncAt and no rowCounts map. Stamp a lastSyncAt into a prefs/settings store after a successful hydrate (Hydrator.hydrate or SyncCoordinator.kt:79 after hydrate returns) and expose it + a per-table count map (derivable from the existing tasks/sessions/etc. StateFlows already on AppViewModel) for a sync-status UI mirroring useSyncStatus. No sync-status UI exists yet (SettingsScreen Backup section is the stub rows), so this is genuinely low until that surface is built.

### [Sync / realtime / offline / da] Hydrate fetches every row with no ordering/limit (web caps sessions/reason_logs/captures at 500, newest-first)
- status: partial | web: lib/sync/hydrate.ts:226-229
- web does: hydrate.ts caps the high-volume history tables: sessions order('completed_at', desc).limit(500), reason_logs order('at', desc).limit(500), captures order('at', desc).limit(500). This bounds payload size and ensures the newest rows are what land locally.
- impl: SyncGateway.fetchAll (SyncGateway.kt:16-17) does an unbounded client.from(table).select(Columns.ALL) for every table; Hydrator passes no order/limit (Hydrator.kt:27-34). Add per-table order+limit support (e.g. a fetchRecent(table, orderCol, descending, limit)) and have Hydrator request the same 500-row, newest-first window for sessions (completed_at), reason_logs (at) and captures (at). Functionally correct today for typical accounts but unbounded for power users.

### [Sync / realtime / offline / da] Periodic background sync interval is 30 min vs web's 5-min Google auto-pull cadence
- status: partial | web: lib/sync/google-sync.ts:327-345
- web does: google-sync.ts auto-pulls Google events every 5 minutes (AUTO_PULL_INTERVAL_MS) while the page is open, plus immediately on connect/sign-in, so external meetings appear within ~5 min.
- impl: SyncWorker.schedule schedules a 30-minute PeriodicWork (SyncWorker.kt:25) that only flushes outbox + hydrates app tables via coordinator.syncNow() — it does NOT pull Google events at all (gated on the missing Google orchestrator gap above). Once the Google orchestrator exists, add a foreground 5-min pull tick (a coroutine loop tied to app lifecycle) to match the web cadence; WorkManager's 15-min minimum means the background path cannot hit 5 min, so a foreground ticker is the right vehicle. Strictly speaking 'partial' overstates it — Android does NO Google pull on any cadence today, so this is really a sub-item of the missing-orchestrator gap rather than just an interval mismatch.

