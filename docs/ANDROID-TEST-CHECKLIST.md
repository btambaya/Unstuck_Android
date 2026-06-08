# Unstuck — Android Master QA Checklist

Master test checklist assembled from 13 domain maps (auth/onboarding, Today dashboard, tasks, calendar, focus mode, collections/sharing, settings/life-areas, analytics/insights, notifications/reminders, sync/offline/data-integrity, assistant-text, assistant-voice, cross-cutting). Deduplicated and reorganized by priority. Run the **P0 Release smoke** before every build; run priority sections top-down as time allows.

---

## Legend

| Priority | Meaning |
|---|---|
| **P0** | Release blocker. Core flow, data loss, crash, security, or auth. Must pass on every build. |
| **P1** | High. Important feature broken or degraded; ship-blocking for a feature release. |
| **P2** | Medium. Edge case, polish, secondary path. Fix before GA but not a hard blocker. |
| **P3** | Low. Cosmetic / micro-optimization / no-op guard. Nice to have. |

**Tags:** `[offline]` no-network path · `[a11y]` accessibility · `[dark]` dark/theme · `[perm]` runtime permission · `[edge]` edge/boundary case · `[regression]` state survival / config change / process death · `[perf]` performance/jank · `[security]` CSRF/data-leak.

---

## Device & environment matrix

Run the smoke on the **Pixel baseline** every build. Rotate the full matrix across a release cycle.

| Class | Device (example) | OS | Notes |
|---|---|---|---|
| **Baseline** | Pixel 6 / 7 | Android 14 | Primary CI/dev target, exact-alarm + POST_NOTIFICATIONS |
| **OEM skin** | Samsung Galaxy S21/A-series | Android 13/14 (One UI) | Aggressive Doze/battery killer, notch |
| **Budget** | Xiaomi/Redmi or OPPO (MediaTek) | Android 12/13 | Low RAM, MIUI/ColorOS alarm restrictions, punch-hole |
| **Min OS** | any | Android 12 (S) | Exact-alarm + comm-device API boundary |
| **13+ perm** | any | Android 13 (Tiramisu) | POST_NOTIFICATIONS prompt path |

**Cross every device, exercise these axes:**
- Theme: **light** + **dark** (system toggle mid-session) + each accent palette.
- Network: **online** + **offline (airplane)** + **flaky/reconnect**.
- Voice audio route: **wired headset** + **Bluetooth headset** + **built-in speaker** (half-duplex echo path).
- Font/density: **default** + **Larger Type (1.15x)** + **Compact/Comfy density**.
- Lifecycle: foreground, background, **process death** (Don't-keep-activities), boot, app-update.
- Display cutout: **notch** + **punch-hole**.

---

## P0 Release smoke (run EVERY build)

The must-pass set. If any item fails, do not ship.

- [ ] **Auth — sign in:** valid email/password signs in; closing/reopening lands on Today with the user's email in the avatar menu. `[regression]`
- [ ] **Auth — sign up:** new email shows "Check your email to confirm…", no session created (anti-enumeration), verification email sent.
- [ ] **Auth — session persists:** after force-close + reopen, app bypasses AuthScreen straight to Today (SessionStatus.Authenticated). `[regression]`
- [ ] **Auth — sign out:** clears session, unregisters FCM, clears notification log + user-content settings, returns to AuthScreen, resets scaffold (tab=today).
- [ ] **Onboarding gate:** shown only on first launch; after completion + relaunch, MainScaffold renders (not Onboarding). `[regression]`
- [ ] **Task — create minimal:** new task (name only) persists, default estimate from settings, appears in correct view, no crash.
- [ ] **Task — schedule:** native date+time pickers create a cal_block at the chosen snapped time; task lands on Today/calendar.
- [ ] **Task — complete:** Mark done stamps completedAt on first flip, strikethrough + green check, moves to Completed; undo clears timestamp.
- [ ] **Task — delete cascades:** deleting removes the task + all its cal_blocks + captures; no orphaned data; UI updates immediately.
- [ ] **Today — Start-Next hero:** shows correctly-ranked task (priority DESC → estimate ASC → createdAt ASC); Focus + Pick another respond.
- [ ] **Today — empty state:** with zero tasks/live/backlog/startNext, EmptyHero renders with working "Add one thing".
- [ ] **Focus — start/run:** start focus creates live session, FocusScreen shows FOCUSING + ticking MM:SS, RUNNING state, ring at 0%.
- [ ] **Focus — pause/resume:** Pause freezes elapsed (amber ring), Resume shifts sessionStart past the gap so only true focus time counts (white ring).
- [ ] **Focus — Done:** records Session with elapsed, flips task.done=true, shows ReflectSheet, closes; recap surfaces.
- [ ] **Live session card:** appears on Today when session active (correct accent: coral running / amber paused), tappable back into focus; hidden from main rows.
- [ ] **Notifications — reminder fires:** pre-task lead reminder fires at the exact (start − lead) time; tapping opens task detail.
- [ ] **Notifications — starts-now:** at start time (Balanced+), "Time to start" fires with Start + Reschedule actions.
- [ ] **Notifications — actions work from shade:** Pause/Resume/End/Capture/Reschedule execute without opening app; notification updates in place.
- [ ] **Sync — offline create persists:** offline task creation shows immediately, pendingCount>0, survives app restart. `[offline]`
- [ ] **Sync — no data loss on flush:** reconnect drains outbox FIFO; server reflects final state; nothing lost or resurrected. `[offline]`
- [ ] **Sync — same-user re-auth does NOT wipe cache:** SIGNED_IN with same uid keeps store + pending offline edits. `[regression]`
- [ ] **Assistant text — create task:** "add a 30-min task: review PRs, work area" calls create_task; new task visible.
- [ ] **Assistant voice — connects:** Talk + grant mic → Connecting → Listening (orb), full round-trip works on at least one audio route. `[perm]`
- [ ] **Analytics offline:** Insights computes all stats from local Room with no network, no crash. `[offline]`
- [ ] **No crash on core screens:** Today, Tasks, Calendar (day/week/month), Collections, Focus, Settings, Insights all open without crashing.
- [ ] **Process death recovery:** kill from Recents/ADB → reopen restores to Today, live focus session + foreground service re-armed from persisted LiveSession. `[regression]`
- [ ] **Password recovery deep link:** `type=recovery` link shows SetNewPasswordScreen (not MainScaffold); after save, drops into app signed in.

---

## P0 — Full coverage

### Auth & Onboarding
- [ ] Email-already-exists on sign-up shows "An account with that email already exists…" (detects obfuscated anti-enumeration), no session. `[edge]`
- [ ] Account deletion (email): case-insensitive email confirm → account-delete edge fn wipes all remote data + signs out; cannot sign back in.
- [ ] Onboarding skip: completeOnboarding(emptyList()) sets flag, jumps to MainScaffold, seeds no areas, creates no task. `[edge]`

### Today Dashboard
- [ ] Today list membership: tasks in todayTaskIds OR (createdToday AND not in upcoming) appear; tomorrow-only tasks excluded.
- [ ] Live session card timer shows total elapsed incl. priorAccumulatedSec (cumulative across pause/resume across days).
- [ ] Pause/Resume on live card toggles state immediately (amber/coral ring, timer freeze/resume) via vm.pauseFocus/resumeFocus.
- [ ] Midnight rollover: 60s refresh + isCompletedToday/isCreatedToday roll tasks to new day bucket; eyebrow updates. `[regression]`
- [ ] Start-Next + live task appear only once — excluded from main rows (it.id != startNext?.id && != liveId), rendered in dedicated slots.

### Tasks
- [ ] Toggle done stamps completedAt on first flip only; retoggle preserves original timestamp; undone clears it.
- [ ] Shared-collection task completion: with sourceCollectionId+sourceItemId set, Done calls share.taskDone RPC; no crash if RPC fails.

### Calendar
- [ ] Day view "Today" label + coral NOW line at local time, advances ~every 30s, vanishes on past/future dates.
- [ ] Midnight rollover on day view (foreground): when clock crosses 00:00 and viewing today, date auto-increments, NOW line + grid sync. `[regression]`
- [ ] Drag unscheduled task from tray → grid schedules at dropped 15-min-snapped time; task leaves tray.
- [ ] Drag scheduled block to new time reschedules in place; moveCount bumps only if date/startTime actually changed.
- [ ] Overlapping blocks render side-by-side in lanes (layoutLanes greedy colouring); non-overlapping blocks revert to full width.

### Focus Mode
- [ ] Done on shared-collection task: finishFocus(markDone=true) also fires share.taskDone(collectionId,itemId,…); members see it done. *(merged w/ Tasks)*

### Collections & Sharing
- [ ] Create collection: name + palette color → appears on 2-col grid, 0 items, not SHARED.
- [ ] Add items via text input: appear newest-first in All, empty-state message clears, input refocuses.
- [ ] Share by email: existing user → "Shared with [email]" + member added; unregistered → "Invited… when they sign up" + pending row; list refreshes.
- [ ] Move-to-task on shared list: dialog LOOP vs SELF; LOOP → time picker, task linked (source ids), assignee + due shown to all; SELF → no announce.
- [ ] Concurrent edits on shared item don't clobber: routed through atomic `collection_update_item` JSONB RPC (not whole-row outbox); realtime merge preserves members/myRole. `[regression]`

### Analytics
- [ ] New synced sessions appear in Insights without restart (recalculate on recompose/scroll). `[regression]`

### Notifications
- [ ] Drift (A4) suppressed if task already done — ReminderReceiver checks task.done.
- [ ] Drift (A4) suppressed if task is currently being focused — checks getLiveSession().taskId == taskId.
- [ ] Reschedule from start-now moves block to next free slot + "Rescheduled · moved to [time]" confirmation (8s timeout). *(see smoke shade actions)*
- [ ] Reminders re-armed on boot (ACTION_BOOT_COMPLETED). `[regression]`
- [ ] Reminders re-armed on app update (ACTION_MY_PACKAGE_REPLACED) without firing a spurious "starting" notification. `[regression]`
- [ ] Notification log cleared on sign-out — next user sees empty Notification Center (NotificationLog.clear()).
- [ ] POST_NOTIFICATIONS permission requested on first launch (Android 13+) via RequestPermission. `[perm]`

### Sync / Offline / Data Integrity
- [ ] Offline edit queues each change as a new upsert; outbox drains in seq order; server reflects final edit, none skipped. `[offline]`
- [ ] Offline delete-before-sync cancels prior upserts (cancelPendingUpserts); only a delete remains; task never resurrects. `[offline]`
- [ ] cal_block upsert respects dependsOn=taskId — parent task flushes before child block, no server FK error. `[offline]`
- [ ] Sign-out flushes outbox (withTimeoutOrNull 5000) BEFORE auth.signOut + store.clearAll. `[regression]`
- [ ] User switch A→B: shouldWipeCache=true → clearAll wipes A's records+outbox, B hydrates fresh, A's task absent. `[regression]`
- [ ] Realtime Insert/Update→upsert, Delete→delete; each decodes to a model and emits; stream survives. `[regression]`
- [ ] Voice/text assistant create + schedule + delete tools execute end-to-end. *(see Assistant smoke)*

### Assistant (text & voice)
- [ ] schedule_task tool: resolves task by id, schedules with date YYYY-MM-DD + 24h HH:MM; block appears on calendar.
- [ ] delete_task tool: removes task from view, assistant confirms "deleted '[name]'".
- [ ] Tool-call loop cap: max 5 iterations; returns "Done." rather than looping/timing out. `[edge]`
- [ ] Network timeout: retries once after 800ms; both fail → "That took too long — try again." `[offline]`
- [ ] Network error: after retry → "Couldn't reach the assistant — check your connection."; input not cleared. `[offline]`
- [ ] Unauthorized (signed out): "Please sign in to use the assistant."; input stays usable. `[perm]`
- [ ] History persists across close/reopen (SharedPrefs unstuck.assistant key 'history'). `[regression]`
- [ ] History survives full app restart; last 40 messages, window starts at a user turn (no orphaned tool_calls). `[regression]`
- [ ] Concurrent-send prevention: second send blocked while sending=true; only first processes. `[edge]`
- [ ] Mic permission request on mic button; denied → "Mic permission is needed to talk to the assistant." `[perm]`
- [ ] Voice confirms aloud before any delete ("Are you sure?" before delete_task).
- [ ] Voice: not signed in → ERROR "Please sign in to use voice." (no WS attempt). `[perm]`
- [ ] Voice: VOICE_PROXY_URL blank → ERROR "Voice isn't set up yet." (no client/WS). `[perm]`
- [ ] Voice: mic permission denied → ERROR "Microphone access is needed for voice." (coral). `[perm]`
- [ ] Voice: granted → Connecting (pulsing orb) → Listening after session.update. `[perm]`
- [ ] Voice barge-in on headset: server VAD speech_started → muted=false, flushPlayback, → LISTENING (full-duplex). *(audio matrix)*
- [ ] Voice half-duplex on built-in speaker: mic frames dropped while outputBusy (400ms tail); barge-in needs Interrupt button. `[edge]`
- [ ] Voice Interrupt button: muted=true, flushPlayback, response.cancel sent, late audio.delta dropped. `[edge]`
- [ ] Voice tool call runs async, result fed back via function_call_output + response.create, model resumes.

### Cross-cutting
- [ ] Dark mode applies theme-wide on every screen instantly + survives rotation; bg = oklch(0.205,0.025,270.0), AA contrast. `[dark]` `[regression]`
- [ ] Config change (rotate/locale/night) preserves tab, route stack, sheets via rememberSaveable/savedStateHandle; does NOT re-fire deep link. `[regression]`
- [ ] Notification deep links (task/today/focus/capture/collections) open the right destination; cold-start waits ≤2.5s for Room then falls back to Today.
- [ ] Notification shade actions execute without opening app (goAsync + finish); single-tap opens app. *(see smoke)*
- [ ] Onboarding shown once; onboarded flag persists; relaunch skips it. `[regression]`
- [ ] Focus session survives backgrounding; foreground-service notification stays live with chronometer + actions; re-arms idempotently on resume. `[regression]`

---

## P1 — Full coverage

### Auth & Onboarding
- [ ] Invalid credentials (wrong password) → "That email and password don't match…", no session. `[edge]`
- [ ] Empty email → "Enter your email first." (client-side, no API call). `[edge]`
- [ ] Empty password → "Enter your password." (client-side, no API call). `[edge]`
- [ ] Password < 8 chars on sign-up → "Password needs at least 8 characters." (server-side, humanized), no account. `[edge]`
- [ ] Magic-link sign-in: "Check your email for a one-tap sign-in link."; email sent; no session yet.
- [ ] Forgot-password: "Check your email for a password reset link."; reset email sent; deep link → SetNewPasswordScreen.
- [ ] Set-new-password (recovery): validates ≥8 + match, changePassword, consumeRecovery clears flag, signs in.
- [ ] Google sign-in: OAuth via unstuck://auth-callback, session saved, lands on Today with Google email/name.
- [ ] Account deletion (Google-only, no email): "Type DELETE to confirm" (case-insensitive) → deletes server-side, signs out. `[edge]`
- [ ] Full onboarding flow: 4 steps, rememberSaveable survives rotation; finish seeds areas w/ palette colors + creates first task; flag clears. `[regression]`
- [ ] Network error during sign-in: humanizeAuthError → "Couldn't reach the server…", busy=false, retryable. `[offline]`
- [ ] Rate limit (over_email_send_rate_limit): "We can only send a few sign-up emails per hour…". `[edge]`

### Today Dashboard
- [ ] Pick another navigates to task selection/new-task flow.
- [ ] Completed-today tasks stay visible at bottom of Today list (after open tasks), not filtered out.
- [ ] Backlog toggle shows unplanned/past-only tasks, hides today-scheduled; toggling clears area filter.
- [ ] Area filter pills filter Today's in-memory rows (cal_block view is area-agnostic but rows respect filter); active state shows.
- [ ] Live card timer ticks every 1s (LaunchedEffect nowTick), shows displayedElapsedSec.
- [ ] Date eyebrow + greeting: morning/afternoon/evening by hour, DAY · H:MM AM/PM, local tz.
- [ ] Just-now recap card after completing focus: "You did the thing", X MIN FOCUSED, task name; Close dismisses.
- [ ] Notifications-off banner appears when system notifications disabled; tap opens ACTION_APP_NOTIFICATION_SETTINGS.
- [ ] Task row metadata: area dot, name, area label, ≤3 tags, age badge (backlog), estimate; tap opens detail.
- [ ] Completed row: strikethrough, green CheckCircle, ink3 dimmed text. `visual`
- [ ] Live card carries priorAccumulatedSec on resume so ring + display show cumulative time.
- [ ] Filter pills scroll horizontally; All/area toggle highlight + filter correctly.

### Tasks
- [ ] Create with all fields persists everything; recurrence (↻) + tags show on row; detail matches input.
- [ ] "Later" defers scheduling: no cal_block, schedule label "Later", "Move out of Later" visible.
- [ ] Schedule modal: native date picker (local tz) + 12h time picker → "Scheduled MM-DD HH:MM AM/PM", cal_block created.
- [ ] Free-slot chips appear; estimate change re-picks first free (autoTime); manual selection sticks (autoTime cleared).
- [ ] Conflict warning: overlapping block → amber "Overlaps [task]" banner; not blocked, proceeds.
- [ ] Recurrence daily: 56-day horizon of daily blocks, no gaps, persists across sync.
- [ ] Recurrence weekly Mon/Wed/Fri: only on-pattern occurrences; label "Repeats Mon/Wed/Fri".
- [ ] Recurrence until-date: inclusive last occurrence; "Repeats daily until [date]"; clearable to open-ended.
- [ ] Edit recurrence diffs future blocks only (delete non-matching, add missing); past preserved, no full regen.
- [ ] Completed task drops from All/Today after midnight (isCompletedToday boundary). `[regression]`
- [ ] Set Later / Move out of Later toggles later flag + tab placement.
- [ ] Backlog = !done && !later && !createdToday && (never scheduled || only past blocks).
- [ ] Area filter applies to all tabs except Today; tag filter applies to all views incl. Today; clearable.
- [ ] Capture drafts saved with task on Add (empty drafts ignored; tags + body preserved).
- [ ] Promote capture → new task: body=name, tags include 'from-capture' + capture tag, area=Work, estimate=25; original persists.
- [ ] Estimate change updates auto-picked slot (LaunchedEffect on deps); switching date regenerates slots.
- [ ] Inline edit task name in detail: ✓ commits, ✕ cancels, updatedAt set.
- [ ] First physical action: free text persists, italic coral label, editable, empty=null.
- [ ] Schedule modal on Later task: setLater(false) + scheduleTask; leaves Later tab.
- [ ] moveCount increments only on actual date/time change (no false move from re-tapping same slot).
- [ ] Recurrence scheduling forces chosen off-pattern slot to materialize (no disappearing task).
- [ ] Offline create+schedule+capture enqueue outbox; list shows immediately; sync on reconnect. `[offline]`

### Calendar
- [ ] Day view auto-scrolls to ~1h before now on load (coerceAtLeast 0).
- [ ] Tap empty hour gutter creates task at 15-min-snapped time; gutter (left of labels) ignored.
- [ ] Drag cancel (outside gridBounds) cancels scheduling; no upsert.
- [ ] Block edit: full-day window (00:00–23:45) free-slot chips; morning/evening blocks editable, not clamped to business hours.
- [ ] Block edit: resize duration (15/25/45/60/90), height scales, moveCount unchanged.
- [ ] Block edit: Unschedule removes block; task returns to unscheduled tray.
- [ ] External/Google events render blue (blueSoft), read-only — no tap/drag, grid behind not tappable.
- [ ] Google Calendar connect/sync/disconnect flow with busy state + offline error ("Couldn't reach Google…").
- [ ] Week view: 7 cols Mon–Sun, today coral; side-by-side lanes; ‹/› nav + Today jump (hidden at offset 0).
- [ ] Month view: focus-density heatmap (empty→light→teal by actualSec), today coral; ‹/› + Today nav.
- [ ] Recurrence scheduling materializes blocks across 56-day horizon, visible in week/month.
- [ ] Recurrence edit start time → all future matching blocks move; past unchanged.

### Focus Mode
- [ ] Treatment switching: Ambient ring+info / Cockpit captures-rail / Monk minimal; selection persists to settings.
- [ ] Overrun prompt at elapsed > estimate+grace (uses displayedElapsedSec): "Past your estimate…", coral, +10/In the zone/Stop here, fires once.
- [ ] Overrun +10 / +15 extends estimate, resets promptFired, returns to RUNNING.
- [ ] Overrun "Stop here": stop service, finishFocus(markDone=false), Session recorded, task stays open, screen closes.
- [ ] focusOverrunMin=0 disables prompt forever (grace=∞).
- [ ] Soft-exit confirm on RUNNING (focusSoftExit=true): "Leave focus? Your timer keeps running" → Stay/Leave; live session persists.
- [ ] Capture sheet saves with tag (follow-up/idea/edit/question/distraction), correct sessionId; shows in cockpit rail.
- [ ] Capture from notification action opens focus with CaptureSheet already open (autoCapture=true).
- [ ] Ambient audio plays only when ambient≠off + focus active; loops at low volume; continues in background; stops on dispose.
- [ ] Switch task mid-session: prior session recorded + totalFocused incremented; new live session fresh w/ correct priorAccumulatedSec.
- [ ] Save for later: pause + optional reason modal + close; session persists paused; resumable from Today.
- [ ] End for now: records session, task stays open, ReflectSheet, totalFocused incremented.
- [ ] Paused-check-in Resume action: FocusCommands.resume, cancel scheduler, clear paused notif, session resumes.
- [ ] Paused-check-in End action: records session, totalFocused++, clears live session + notif, stops service.

### Collections & Sharing
- [ ] Long-press item reveals action bar (pin / move-to-task / remove); long-press or tap dismisses.
- [ ] Pin/unpin: icon outline↔coral; items move between Pinned/All; section labels appear/vanish.
- [ ] Check/uncheck item: coral checkbox + checkmark, strikethrough ink3 on done, restores on undo.
- [ ] Inline-edit item: tap → BasicTextField + green check; another member's collection rename does NOT wipe draft (keyed on item.id).
- [ ] Remove item via action bar: disappears immediately, count decrements, outbox enqueues delete.
- [ ] Edit collection name (owner): inline + checkmark, updates header + grid; non-owner read-only.
- [ ] Change collection color (owner): palette updates header + grid; non-owner sees no palette.
- [ ] Archive/restore collection; archived hidden from grid until "Archived (N)" toggle; unarchive restores.
- [ ] Delete collection: confirm dialog with name + count → pops + removes; cancel keeps it.
- [ ] Remove member (owner) optimistic; cancel pending invite removes the email.
- [ ] Member list shows email + role badge (EDIT/VIEW) + PENDING (yellow).
- [ ] Non-owner: "Shared with you · can edit/view" note; editor can add/edit; Leave removes + pops.
- [ ] Move item to task (solo list): no dialog → task (estimate 25, tag 'from-collection'); item shows struck-through "Promoted".
- [ ] Keep-in-loop overdue warning ("⚠ overdue · due [time]" red); assignee completion flips item to "done by [name] ✓" green.
- [ ] Viewer role read-only: add pill hidden, no long-press/edit, checkboxes disabled, status chips static.
- [ ] Concurrent edit on shared list synced to all members via realtime; atomic item RPCs prevent clobbering. `[regression]`
- [ ] Collection deleted by owner: realtime delete drops members' local copy; detail screen pops back. `[edge]`
- [ ] Share sheet member/pending list loads + refreshes after share/unshare/cancel; no stale data.

### Settings & Life Areas
- [ ] Areas list: color circle, name, open-count, sorted by sortOrder (not alpha); palette colors correct.
- [ ] Create area: auto-assigns first unused palette color (cycles size%8), sortOrder=max+1, draft clears.
- [ ] Duplicate area name rejected case-insensitively (silent, draft kept). `[edge]`
- [ ] Rename area via menu: cascades to all tasks tagged with old name; sortOrder + color unchanged.
- [ ] Change area color via palette dropdown: updates immediately, persists across launch.
- [ ] Delete area: removed; tasks keep data, lifeArea→null (no loss).
- [ ] Export includes all lifeAreas {id,name,color,sortOrder} in JSON.
- [ ] Export success → green "Exported."; only on confirmed file write.
- [ ] Default reminder lead = 10 min; change persists + reschedules (ReminderScheduler.reschedule).
- [ ] Soft-exit toggle persists + affects focus exit behavior.
- [ ] Ambient setting off/brown/pink persists.
- [ ] Notifications level Calm/Balanced/Coach: blurb describes level; change reschedules + persists.
- [ ] Signed-in row is static (no onClick, no action).
- [ ] Account content rows present + correct order (name, signed-in, password, export, delete, sign out); last has no divider.
- [ ] Tags section mirrors areas (color/rename/delete, 8-color palette, case-insensitive dup reject).

### Analytics
- [ ] Time range Week/Month/All filters by correct cutoff (Mon 00:00 / 1st 00:00 / all).
- [ ] Threshold note when <5 sessions: "Patterns appear after a few sessions" + "N of 5".
- [ ] Stats show "—" when <5 sessions (estimates, sessions, friction).
- [ ] Session count stat correct (e.g., 10 + "10 sessions" caption).
- [ ] Calibration hit-rate rounds to nearest integer % (e.g., 80% for 4/5 within ±5m); 0% when all missed.
- [ ] Stacked bars render only ≥5 sessions; use custom areas, fall back to DEFAULT_AREAS when none; null-area tasks ignored. `[edge]`
- [ ] Interruptions histogram bins captures within session window (3-min bins); negative-time captures skipped. `[edge]`
- [ ] "Worth noticing" insights only ≥5 sessions (≤3 shown).
- [ ] Best-weekday insight surfaces strongest day + minutes.
- [ ] Calibration insight ≥3 dots; phrasing changes by hit rate (nailing / still settling).
- [ ] Slipping-task insight surfaces by age (≥21d → "4+ weeks") and by move count (≥3 → "rescheduled N times").
- [ ] Deep dive median rounds (121s → 2m); re-entry <5m % correct; 0% with one session. `[edge]`
- [ ] Pause anatomy aggregates minutes+count by reason, blank→"Other", caps at 6 rows. `[edge]`
- [ ] Capture breakdown only when captures exist; counts all 5 tag types incl. zeros.
- [ ] Time-of-day heatmap: Mon–Fri only, hours clamped 7am–7pm, weekends skipped.
- [ ] Percentages always integers; minutes floored (89.5→89m).
- [ ] Dark mode: chart colors + stat cards legible, AA contrast, no cutoff. `[dark]` `visual`
- [ ] Rotation: Insights reflows charts + cards, no overlap. `visual` `[regression]`

### Notifications
- [ ] Pre-task reminder skipped when lead=Off (not scheduled at all).
- [ ] Starts-now suppressed on Calm (lead A1 still fires if lead>0).
- [ ] Drift (A4) fires 10 min after start on Coach only ("Didn't get to it?…").
- [ ] Paused check-in fires ~14 min after pause (Balanced/Coach): "Did you step away?" + Resume/Snooze/End (amber).
- [ ] Resume from paused check-in cancels future check-ins (scheduler.cancel).
- [ ] End from paused check-in finalizes session + cancels check-ins + stops service.
- [ ] Notification Center Upcoming: next 48h, de-duped, max 20, tap opens detail; excludes >48h.
- [ ] Notification Center Recent: persists after swiping system notif; max 60; color dot by kind.
- [ ] Deep links route correctly (task→detail, today/recap/collections→scaffold, unknown→Today); non-task rows inert.
- [ ] Notifications-off banner: "Notifications are off…", tap → system notification settings.
- [ ] Exact-alarm prompt shown once when needed (Android 12+) → Alarms & reminders settings; skipped if Calm+lead=0. `[perm]`
- [ ] Exact-alarm fallback to inexact (am.set) if permission denied — reminder still fires (may be late).
- [ ] Back-to-back tasks each get distinct lead reminders (NotifIds from task hashCode); no overwrite.
- [ ] FCM token registered on sign-in (registerFcmToken on Authenticated).
- [ ] Per-task reminder override beats global lead; external events use global lead.

### Sync / Offline / Data Integrity
- [ ] Poison-pill: op hitting FAIL_CAP (5) is dropped + its orphaned dependents dropped; pendingCount→0; app doesn't hang. `[edge]`
- [ ] Per-row pause on failure (blockedRows): later upserts for that row skipped; sync stops; retries next sync; all eventually flush. `[edge]`
- [ ] Hydrate per-table isolation: one table fetch failure (runCatching) leaves others intact; old data stays. `[edge]`
- [ ] Hydrate preserves local external g_ blocks (mergeHydratedCalBlocks) — not re-pushed on next Google sync.
- [ ] Hydrate preserves unsynced pending TASK blocks across replace (pendingIds + localPending).
- [ ] Realtime collection_members change triggers hydrateCollections → new shared collection appears.
- [ ] Google connect CSRF: state mismatch → log + return false, connectGoogle NOT called, no server change. `[security]`
- [ ] Google connect: flush before hydrate preserves local pending TASK blocks.
- [ ] Google pull reconciles external blocks: adds new, drops out-of-window, skips own task-block events (ownEventIds).
- [ ] Google disconnect: confirm dialog → calendar.disconnect, delete connection + its external blocks, UI flips to Connect.
- [ ] Google OAuth redirect = exact HTTPS bounce (`unstuck-602.pages.dev/calendar-callback`), no redirect_uri_mismatch, forwards to app.
- [ ] Google push upsert: new TASK block inserts on 'primary', returns eventId, block re-upserted w/ externalEventId.
- [ ] Google push patch: existing block (eventId) patched on edit, no new eventId.
- [ ] Google push delete: block delete reads eventId first, deletes Google event, removes server row.

### Assistant (text & voice)
- [ ] Empty-state hint: "Brain-dump it." + example prompts; no New chat button without history.
- [ ] Text send via keyboard + via send button (disabled when blank); user bubble + Thinking row + reply.
- [ ] Multiple consecutive turns keep order + auto-scroll to latest.
- [ ] update_task tool modifies estimate + tags, UI reflects immediately.
- [ ] complete_task tool toggles done; task → completed.
- [ ] create_list then add_to_list ×2 makes a list with 2 items.
- [ ] promote_item_to_task with dueAt creates task + marks item done.
- [ ] set_task_later (later=true) moves task to Later.
- [ ] set_task_recurrence (weekly, daysOfWeek) adds ↻ + calendar repeats.
- [ ] Ambiguous "add a task" → clarifying question, not empty task. `[edge]`
- [ ] not_configured (null client) → "The assistant isn't set up yet."
- [ ] New chat clears history + SharedPrefs, returns to empty state.
- [ ] Speaker toggle reads replies aloud when ON; OFF silent + stops active speech.
- [ ] Voice input: onPartial live updates field, onFinal sets transcript, onDone auto-sends non-blank, listening resets.
- [ ] Mic listening-state toggle: first tap start, second tap stop; icon color changes.
- [ ] Tool result error ("error: item not found") added to history; assistant acknowledges. `[edge]`
- [ ] Malformed tool args JSON → empty JsonObject, safe execution, "error:…" result. `[edge]`
- [ ] Assistant context includes today/now ISO + areas list (e.g., 'Health' present).
- [ ] Feedback tab toggle (Assistant↔Feedback, mutually exclusive) + submit success "Thanks — we got it" → close.
- [ ] Voice live captions stream from response.audio_transcript.delta; finalized on .done.
- [ ] Voice user caption shows on input_audio_transcription.completed; cleared on next user turn.
- [ ] Voice headset plug-in mid-session → route switches, echoProne=false, full-duplex enabled seamlessly. *(audio matrix)*
- [ ] Voice headset unplug mid-session → falls back to BUILTIN_SPEAKER, echoProne=true, half-duplex (400ms tail). *(audio matrix)*
- [ ] Voice WS HTTP error (401/403): onFailure reads code/body → ERROR, orb removed.
- [ ] Voice network unreachable mid-session: onFailure → "Couldn't reach the voice server", audio.shutdown, ERROR. `[offline]`
- [ ] Voice server error event (5xx) message surfaced (≤160 chars) → ERROR; X closes.
- [ ] Voice tool failure → "error:<msg>" in function_call_output, no crash, model continues. `[edge]`

### Cross-cutting
- [ ] Dark mode applies to home-screen widget (#FAFAF7→#1A1822) on theme change. `[dark]` `visual`
- [ ] Larger Type (1.15x fontScale) scales all text app-wide, no cutoff/overflow; survives rotation. `[a11y]` `visual`
- [ ] Content descriptions present + meaningful on all interactive elements (icons, buttons, toggles, tabs, focus controls) for TalkBack. `[a11y]`
- [ ] Touch targets ≥44dp for buttons/toggles/checkboxes/notification actions (flag UButton height if sub-spec). `[a11y]`
- [ ] Notification action buttons (Pause/Resume/Snooze/End/Capture/Reschedule) act from shade; Capture opens focus w/ sheet open. *(see P0 smoke)*
- [ ] Network loss never crashes; UI updates instantly (Room write-ahead); outbox flushes FIFO w/ dependency order on reconnect. `[offline]`
- [ ] Accent palette (Indigo/Coral, Periwinkle/Rose, Forest/Amber) applies consistently across all screens; persists. `[dark]` `visual`
- [ ] Auth screens render correctly in dark + with TalkBack + Larger Type (no overflow). `[a11y]` `[dark]` `visual`
- [ ] List scroll perf: 100+ tasks/captures scroll at ~60fps; LazyColumn keyed, no O(n) work in composables. `[perf]`
- [ ] Exact-alarm prompt only once, not on un-onboarded users, flag persists (no re-prompt). `[perm]` `[regression]`
- [ ] FCM token registers once per sign-in, survives restart, re-registers on account switch. `[regression]`
- [ ] POST_NOTIFICATIONS requested once per install (Android 13+); re-prompt only after revoke. `[perm]`

---

## P2 — Full coverage

### Auth & Onboarding
- [ ] Sign-up with blank name: account created, display name defaults to email local-part. `[edge]`
- [ ] Magic-link with empty email → "Enter your email first.", no email. `[edge]`
- [ ] Forgot-password with empty email → "Enter your email first.", no email. `[edge]`
- [ ] Recovery password mismatch → "Passwords don't match.", Save disabled. `[edge]`
- [ ] Rate limit (over_request_rate_limit / HTTP 429) → "You hit a rate limit. Slow down…". `[edge]`
- [ ] Mode toggle (sign-in↔sign-up) clears error message (message=null).
- [ ] Onboarding empty first task → addTask not called, no initial task. `[edge]`
- [ ] Onboarding area multi-select toggles in/out; final selection seeded with palette colors.

### Today Dashboard
- [ ] In-app nudge for slipping task (age≥21d or moveCount≥3) appears if nudges enabled; Open → detail.
- [ ] Nudge dismissal (✕) removes card immediately; task stays in list.
- [ ] Recap card auto-clears after 6h.
- [ ] Backlog/area empty state messages: "Backlog's clear…" / "Nothing in [area] right now."
- [ ] Progress ring fills to estimate, caps at 100% past estimate. `visual`
- [ ] Week summary pill: focused minutes over last 7 days, Xh Ym format, leads to Insights.
- [ ] Inbox + notifications coral badge dots when unread; disappear when cleared. `visual`

### Tasks
- [ ] Long task name truncates to 1 line w/ ellipsis in rows; full name in detail. `visual`
- [ ] Estimate custom value (1–9999): saved, non-digits filtered, zero/empty rejected; presets always available.
- [ ] Per-view empty messages ("No [view] tasks.").
- [ ] Reminder override default/off/5/10/15; only for scheduled (not Later).
- [ ] Date picker seeded from prefill, preserved on cancel, UTC read/write (no off-by-one).
- [ ] Prefill date/time from deep link: time chip selected, autoTime=false.

### Calendar
- [ ] Tap on hour label itself does not create task (gutter filter). `[edge]`
- [ ] Drag ghost follows finger with density-aware centering (70dp/18dp). `visual`
- [ ] Unscheduled tray shows max 20 tasks, horizontally scrollable.
- [ ] Task block names: done → strikethrough + ink3; updates live from tasks flow. `visual`
- [ ] Google all-day / odd-time events convert: floored 15-min min block, all-day→00:00. `[edge]`
- [ ] Week view rollup: Focus planned sum, Busiest/Lightest days; uniform → "—".
- [ ] Week view 44dp hour height, 0–24h range (1056dp). `visual`
- [ ] Month view ‹/› + Today nav.
- [ ] Month view grid: lead padding, week rows, 1:1 aspect cells, 7 cols. `visual`
- [ ] Monthly recurrence on 31st clamps to Feb 28/29, recovers to 31. `[edge]`

### Focus Mode
- [ ] Overrun +15 ("In the zone") extends by 15, returns RUNNING. *(merged w/ +10 P1)*
- [ ] Soft-exit disabled (focusSoftExit=false): Out closes immediately, session persists.
- [ ] Out with paused session exits without confirm (soft-exit only on RUNNING).
- [ ] Ambient audio off when setting='off' (stop immediately).
- [ ] First physical action shown below task name ("→ action") in Ambient/Cockpit, hidden in Monk.
- [ ] Pause reason modal when focusPauseReasons=true (5 options) → saveReasonLog; skipped when false.
- [ ] Re-entering paused session keeps it paused (no auto-resume); explicit Resume needed.
- [ ] Paused check-in fires only on Balanced/Coach (suppressed Calm); snooze re-arms 14 min.
- [ ] Cockpit captures rail shows last 3, updates live; hidden when empty. `[edge]`
- [ ] Focus layout stable on rotation (status/nav bar padding honored). `[regression]`

### Collections & Sharing
- [ ] Share error handling: self → "That's you."; invalid email → disabled/rejected; network → "Could not share." `[edge]`
- [ ] Search/filter collections by name AND item body (case-insensitive), live; empty query shows all.
- [ ] Grid card: color chip, name, count, ≤2 item previews, SHARED badge appears/disappears. `visual`
- [ ] Collection rename from another member doesn't wipe my item-edit draft (keyed on item.id). `[edge]`
- [ ] Offline collection add/pin/edit/delete optimistic; outbox flushes FIFO on reconnect; realtime merges. `[offline]`

### Settings & Life Areas
- [ ] Density Compact/Regular/Comfy adjusts spacing; touch targets stay ≥44dp; survives rotation. `visual`
- [ ] Reject empty name on rename (silent, original kept). `[edge]`
- [ ] Reject duplicate name on rename case-insensitively (revert). `[edge]`
- [ ] Choosing unused color first when multiple unused exist; explicit choice honored. `[edge]`
- [ ] Delete area dialog text: 'Delete "[name]"?' + "Tasks keep their data…" + Delete(red)/Cancel.
- [ ] Export failure → red "Export failed." (not green). `[edge]`
- [ ] Soft-exit toggle defaults ON (focusSoftExit=true).
- [ ] Account menu on notched/punch-hole phones: AppBar spacing correct, imePadding respects insets. `visual`
- [ ] IME padding on all settings screens; input visible above keyboard.
- [ ] sortOrder increments as max+1 after deletions (delete B → new D gets 3, sorts after C). `[edge]`

### Analytics
- [ ] Report subtitle "Observations, not a score."
- [ ] Deep dive subtitle "Let's look closer. Calmly."
- [ ] Empty-state text differs by view (area filter vs backlog).
- [ ] Week summary banner: "1h 5m focused" (≥60m) vs "65m" (<60m).

### Notifications
- [ ] Paused check-in suppressed on Calm; snooze resets 14-min timer. *(see Focus P2)*
- [ ] Notifications-off banner disappears on resume after re-enabling. `[edge]`
- [ ] External event lead reminder fires (taskId blank, opens today, id keyed off blockId).
- [ ] External vs per-task override leads applied correctly.
- [ ] Non-task-linked notification tap (session_recap) does nothing (onClick=null).
- [ ] Morning brief / evening preview → DAILY channel (LOW silent), tap → today.
- [ ] Session recap → RECAP channel (silent), green dot; away=true if ended from shade.
- [ ] collection_share → COLLAB channel (HIGH heads-up).
- [ ] Reminder override per-task persists across restart (SharedPreferences). `[regression]`

### Sync / Offline / Data Integrity
- [ ] Realtime skips un-decodable rows (extra column/null required), logs, continues streaming. `[edge]`
- [ ] Google all-day events skipped (no 'T' in start), no block created.
- [ ] Google push errors best-effort: 403 → log, null eventId, block still written locally, user not blocked. `[edge]`

### Assistant (text & voice)
- [ ] Context payload to edge fn: today/now ISO, currentName, areas, tags, tasks (non-done ≤60), lists (≤40 items).
- [ ] Empty message validation: spaces trimmed, rejected, input cleared only on success. `[edge]`
- [ ] Thinking row visible while sending; auto-scroll; disappears on reply. `visual`
- [ ] History capped at 40, window starts at user turn (no orphaned tool_calls). `[edge]`
- [ ] upstream error → "The assistant had a hiccup. Try again."
- [ ] empty response (no assistant/no error) → "Something went wrong. Try again." `[edge]`
- [ ] Display filter shows only user/assistant non-blank; tool role hidden but persisted.
- [ ] Multiline input (shift+enter) preserved; sends on ImeAction.Send/button. `visual`
- [ ] Send icon disabled (bg2/ink4) when blank/sending; enabled (coral/white) otherwise. `visual`
- [ ] Voice JSON parse failure on incoming frame: silent skip, no ERROR, stream continues. `[edge]`
- [ ] Voice AEC/NS/AGC enabled best-effort; skip silently if platform-unavailable.
- [ ] Voice comm-mode entered on start (MODE_IN_COMMUNICATION, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) before capture/playback.
- [ ] Voice comm-mode exited on end (clearCommunicationDevice, mode restored, focus abandoned).
- [ ] Voice capture 100ms frames @ 16kHz PCM16 (3200 bytes); playback 24kHz PCM16 from queue.
- [ ] Voice pulsing orb 120dp, 900ms scale 1.0→1.15 loop; blue listening / coral speaking. `visual`
- [ ] Voice Interrupt button only in LISTENING/SPEAKING (`live`); hidden in CONNECTING/ERROR/CLOSED.
- [ ] Voice caption empty when not speaking, cleared on user turn, accumulates on assistant turn. `visual`
- [ ] Voice CLOSED state: "Ended", static orb, dialog stays until X/End. `[edge]`
- [ ] Voice concurrent interrupt + late delta: muted=true early-returns, no stale audio. `[edge]`
- [ ] Voice tool args missing/malformed → empty object, handler validates, "error:…", no crash. `[edge]`
- [ ] Voice close (X) graceful: client.stop, audio.shutdown, WS close 1000, no dangling threads.
- [ ] Voice End button saves voiceNewTasks (no data loss on later persist).

### Cross-cutting
- [ ] Focus navigation order logical (top→bottom, left→right), no loops, visible focus indicator. `[a11y]`
- [ ] Low-memory: process kill recovers gracefully, no OOM crash, focus + Room restored from disk. `[regression]`
- [ ] SyncWorker (30 min, CONNECTED constraint) skips with no network, runs on reconnect, updates Start Next widget. `[perf]`
- [ ] Home-screen widget truncates long name (maxLines=2) + null estimate defaults to 25; fits small + large screens. `visual`
- [ ] Notched/punch-hole: status/nav/systemBars padding protect AppBar + overlays + focus buttons. `visual`
- [ ] Keyboard navigation: Tab/Shift+Tab through fields/buttons, Enter submits, focus visible. `[a11y]`
- [ ] Empty states graceful: zero tasks/captures/collections show messages (Inbox zero, etc.), no broken layout. `[edge]`

---

## P3 — Full coverage

### Settings & Life Areas
- [ ] Rename area with identical text (incl. case) → no DB write (nm==area.name guard). `[edge]`

### Cross-cutting (documented limitations — confirm flag stored, behavior may be TODO)
- [ ] Reduce Motion (a11y): flag persisted; animations skip/shorten where coded (FocusScreen ring redrawn per frame may be unaffected — document if not implemented). `[a11y]`
- [ ] High Contrast (a11y): flag persisted; ink2→ink / line→line2 swap where coded — document if composables don't yet consume the flag. `[a11y]`

---

## Known risk hotspots

> Pay extra attention here — these are the recurring gotchas that have bitten this codebase before. Re-test them on every release even if they passed last build.

- **Outbox poison-pill / orphaned dependents** — verify FAIL_CAP=5 drops the bad op AND its dependents; pendingCount returns to 0; app never hangs. Already fixed once — guard against regression. `[edge]`
- **Whole-row upsert race on shared collections** — shared-collection item edits MUST go through atomic `collection_update_item` JSONB RPC, never whole-row outbox. Concurrent edits must not clobber; realtime merge must preserve members/myRole. `[regression]`
- **kotlinx default-value omission** — fields equal to their default are omitted from serialized JSON; confirm round-trip of tasks/blocks/captures doesn't silently drop default-valued fields after sync.
- **Voice speaker echo / barge-in gating** — on built-in speaker (echoProne=true), half-duplex must gate mic for 400ms tail so the model doesn't hear itself; on headset, full-duplex barge-in must work. Test route swaps mid-session. `[edge]`
- **Exact-alarm permission (Android 12+)** — prompt once, never on un-onboarded users, flag persisted; denied path must fall back to inexact alarms (reminders still fire, possibly late). OEM (Xiaomi/Samsung) battery managers can still kill alarms — verify on budget device. `[perm]`
- **Google OAuth redirect** — must be the exact HTTPS bounce `unstuck-602.pages.dev/calendar-callback` registered in Google Cloud Console (not unstuck://, not Supabase). Mismatch → 400 redirect_uri_mismatch and silent connect failure. CSRF state must match or connect is rejected. `[security]`
- **g_-prefix external blocks on hydrate** — local Google external blocks must survive cal_blocks hydrate (mergeHydratedCalBlocks) and NOT be re-pushed; pending TASK blocks must survive replace via pendingIds/localPending.
- **Same-user re-auth cache wipe** — SIGNED_IN with the same uid must NOT wipe the store/outbox (only A→B switch wipes). Offline edits must survive a re-auth round-trip.

---

## Sign-off

| Field | Value |
|---|---|
| **Build / version** | v________ (vc____) |
| **Channel** | Firebase / TestFlight / internal |
| **Device(s)** | __________________ |
| **OS version(s)** | __________________ |
| **Theme tested** | light ☐  dark ☐ |
| **Network tested** | online ☐  offline ☐  reconnect ☐ |
| **Voice route tested** | wired ☐  BT ☐  speaker ☐ |
| **Font/density** | default ☐  Larger Type ☐  Compact/Comfy ☐ |
| **Tester** | __________________ |
| **Date** | __________________ |
| **P0 smoke** | PASS ☐   FAIL ☐ |
| **Overall** | PASS ☐   FAIL ☐   PASS-WITH-NOTES ☐ |
| **Blocking issues / notes** | __________________________________________ |

> Rule: any **P0** failure ⇒ overall FAIL, do not ship. P1 failures ⇒ release-manager judgment. P2/P3 ⇒ log as follow-ups.
