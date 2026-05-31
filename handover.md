# Unstuck Android — Handover / Live State

Single source of truth for "where is the Android build?". Update as phases land.

> **New engineer? Start with the onboarding handbook: [`docs/handbook/`](docs/handbook/README.md)** (8 deep chapters) + the quick [`docs/APP_GUIDE.md`](docs/APP_GUIDE.md).

## Calendar + Notifications (v0.3.x–v0.4.x, 2026-05-31)

**Two-way Google Calendar sync — shipped & working (v0.3.5–v0.3.8).** Connect uses the HTTPS bounce page `https://unstuck-602.pages.dev/calendar-callback` (registered on the Google Cloud **Web** OAuth client — custom schemes are rejected). Tasks push to the user's **primary** Google calendar (selectedCalendarIds can be read-only → 403); pull sends **RFC3339** timestamps (bare dates → 0 events). Root fixes along the way: ktor `contentType(application/json)` on every `functions.invoke{setBody}`; kotlinx omits default values (made `provider` explicit); snake_case `/connections` DTO.

**Notifications — shipped (v0.4.0–v0.4.5) + backend deployed.** Pre-task reminders (Settings→Focus default + per-task override on New Task; exact AlarmManager, boot reschedule); live focus notification ("FOCUSING · LIVE" + Pause/Capture ↔ amber "Did you step away?" + Resume/Snooze/End); paused-too-long WorkManager check-in; session-end recap (+ Today card); morning brief (server cron, live); in-app slipping/follow-up nudges; per-purpose channels + lock-screen privacy; notification deep-links + Capture route through `MainActivity`→`AppGraph.pendingDeepLink`→`MainScaffold`. New files under `app/.../surface/`: NotificationChannels, NotificationRenderer, NotificationActionReceiver, FocusCommands, PausedCheckinScheduler, ReminderScheduler, ReminderReceiver (boot), ScheduleCommands.

**Start-now notifications + 3 intensity levels (v0.4.4, git e11f523, on Firebase).** Per user feedback. (1) `ReminderScheduler` now keeps up to THREE exact alarms per task block — LEAD (start−lead, A1, all levels), ATSTART (start, A2, Balanced+), DRIFTED (start+10m, A4, Coach) — each with its own intent tag (`lead:`/`atstart:`/`drifted:$blockId`); verified on-device via `dumpsys alarm`. The ATSTART/DRIFTED notification (`NotificationRenderer.postTaskStarting`) has two shade actions that work app-closed: **Start** (`unstuck://focus/{id}` activity PendingIntent → `MainScaffold` `vm.startFocus` + `focusTask`) and **Reschedule** (broadcast → `NotificationActionReceiver.ACTION_RESCHEDULE` → `ScheduleCommands.rescheduleToNextSlot`: next free slot today via `findFreeSlotsForDate` else +1h, bumpMoveCount, re-arm, shade confirmation — no app open). `ReminderReceiver` re-checks done/already-focusing before posting (goAsync). (2) `NotificationLevel { CALM, BALANCED(default), COACH }` (`SettingsStore`) is the single source of truth (`atStart`/`drifted`/`pausedCheckin`/`morningBrief`/`nudges` booleans), surfaced as a `SegRow` + live blurb in Settings→Focus. Gates `ReminderScheduler` (atstart/drifted), `PausedCheckinScheduler.arm`, `AppViewModel.nudges`, and is synced to the server (`PreferencesClient.setNotificationLevel` → `notification_preferences.morning_brief_enabled`/`paused_checkin_enabled`, owner-self RLS) so the cron brief honours Calm. **NOTE: live alarm-fire + Start/Reschedule taps not yet exercised on-device** (alarm SCHEDULING + level gating are verified via dumpsys; the render reuses the v0.4.3-proven path) — confirm by scheduling a task ~2 min out.

**UI batch (v0.4.5, git 22f20c6, on Firebase).** Five on-device-feedback fixes, all verified on the emulator: (1) **Sticky headers** — `TodayScreen`/`TasksScreen` split into a fixed header (avatar/greeting + filter pills / title + tabs) above a weighted scrolling `LazyColumn`; only the list scrolls now. (2) **Bottom-anchored collection add** — the add field in `CollectionDetailScreen` moved below the items (items already appended to the bottom; web does too — `[...c.items, item]`). (3) **Custom time** — `NewTaskSheet` Time row gains a "Custom…" chip → Material3 `TimePicker` dialog (the prefilled chip is now editable; was previously a dead `{}` onClick). (4) **Account menu** — `AvatarMenu` is now a top-right `Popup` (alignment TopEnd, ~64dp down) anchored by the avatar, not a `ModalBottomSheet`. (5) **Notification center** — a bell in the AppBar + Today header (unread dot) opens `ui/notifications/NotificationCenterScreen` (Upcoming reminders computed live from blocks + Recent from `surface/NotificationLog`, a SharedPreferences-backed `StateFlow` the renderer appends to on every post). `AppBar` gained `onNotifications`/`notifUnread`; `Route.Notifications` + `openNotifs` (markSeen + push) in `MainScaffold`. **Caught + fixed in testing:** opening the center crashed on a duplicate `LazyColumn` key (test data has identical (task,time) blocks) → `distinctBy { taskId to at }`.

**Fix batch (v0.4.6, git 801bc5b, on Firebase — all 3 testers).** Investigated via a 4-agent workflow, all verified on-device: (1) **Nudge dismissal now persists** — `_dismissedNudges` was in-memory only (a retained ViewModel field) so a ✕'d capture nudge reappeared after relaunch; now persisted via `SettingsStore.load/saveDismissedNudges` (StringSet). (2) **Calendar day-grid title clip** — short blocks (height floored to 22dp, 6dp padding, top-aligned) clipped the 12sp title into the rounded clip; fixed with `Alignment.CenterStart` + `vertical = 2.dp` padding + min height 24dp (`DayGrid.kt`). (3) **Tasks hamburger removed** (`leading = Leading.NONE`) — the inline area pills already cover it; Calendar/Collections keep theirs. (4) **Branded launcher icon** — was an off-brand placeholder; now the coral Orbit (`#E89077`) on dark ink (`#1A1C26`) via `ic_launcher_foreground`/`_background` recolor + a single-colour `ic_launcher_foreground_mono` for the themed-icon layer. NOTE: the App Distribution `testers` default now includes all 3 (was owner-only on v0.4.3–v0.4.5; fixed commit 4253a30).

**Backend (deployed to Unstuck `uaxfteluwctrlgwmmfzi`):** `register-push-token`, `send-session-recap`, `send-paused-checkin`, `send-morning-brief` deployed (data-only FCM payloads + design-voice copy); `_shared/fcm.ts` gained `dataOnly`. **Morning-brief cron live** (`morning-brief-dispatch` + `wake-window-calibration`, every 15 min). `CRON_SECRET` rotated + inlined into `dispatch_morning_briefs` (the manual SQL keeps placeholders — never commit the secret). The local `supabase` CLI is already authed to this project — `functions deploy --project-ref uaxfteluwctrlgwmmfzi` + `db query --linked` (no DB password).

**Review pass (2026-05-31):** a multi-agent adversarial review (bugs + security) raised 18, confirmed 10, all fixed — notably: notification deep-links/Capture now actually route (were dead); morning-brief cron double-dispatch (HH:MM vs HH:MM:SS) → minutes-since-midnight half-open window; `try_consume_push_budget` locked to `service_role` only (was public/anon/authenticated); resumed-chronometer base; heavy/light from full open-count; high-priority data push.

**Self-test → push-registration fix (v0.4.3, git 8272e45).** On-device testing of the v0.4.2 build found that **no server push could ever reach Android** (registration was 100% broken). Two compounding bugs in `sync/Clients.kt` `PushClient`: (1) `register-push-token` was throwing the ktor *"Fail to prepare request body … Content-Type: null"* gotcha (the distributed APK predated the `contentType()` fix now in source) — every register failed silently (`runCatching` swallows it, no log); (2) `RegisterBody.platform` had a default `= "android"`, and **kotlinx omits default-valued fields** → `platform` never serialized → server (`register-push-token` index.ts:47 `body.platform === 'android' ? … : 'ios'`) **mislabeled the row `ios`** → `send-morning-brief` filters `platform === 'android'` for FCM → never routed. Fix: `platform` is now a required field set explicitly at the call site. **Verified live:** the `device_tokens` row flipped `ios → android` (fcm_token intact), and a real data-only FCM push (Firebase Admin SA) rendered correctly on the emulator — `dumpsys` confirmed channel `unstuck_daily`, id 2003, coral, `VISIBILITY_PRIVATE` + the public "Unlock to read" version, deep-link `contentIntent`. (This is the **3rd time** kotlinx default-omission has bitten us — after `provider` and the calendar server fields. **Never give a `@Serializable` field a default if the server depends on it.**) v0.4.3 is committed + pushed to git but **NOT yet on Firebase** — the App Distribution upload was left for the owner (`./gradlew :app:appDistributionUploadRelease`).

**Known refinements (not blocking):** deep-links land on Today but don't yet scroll to the recap/brief detail; the "Capture" action opens the live focus screen (not a standalone capture sheet); per-task reminder lead is device-local (not synced); server `notification_preferences` toggles aren't surfaced in the Android settings UI yet.

**⚠ Open bug — poison-pill outbox entry (found 2026-05-31, NOT yet fixed).** Logcat shows one outbox op retrying every flush cycle for hours, never draining: `[outbox] cal_blocks#381d6605-… failed: … violates foreign key constraint "cal_blocks_task_id_fkey" (Key is not present in table "tasks")`. An orphaned cal_block references a task absent server-side, so its upsert can never succeed. `OutboxFlusher.flush` uses a `progressed` flag (no strict head-of-line block — newer ops still flush), so sync isn't stalled, but the op retries forever (log spam + redundant network). **Fix needs an owner decision** (not shipped unreviewed — it touches the data-integrity drain path): (a) add an `attempts` column to `OutboxEntity` (Room migration) + dead-letter after N failures — safest, only drops genuinely-stuck ops [**recommended**]; or (b) classify permanent FK/4xx errors in `OutboxFlusher` and quarantine immediately — no migration, but risks dropping a recoverable op (→ silent local-change loss on next hydrate).

## UI redesign → Android Mockups (in progress)

The app is being reconciled to the official **Android Mockups** (Claude-Design
bundle `unstuck-v2`; `Android Mockups.html` = 39 screens, source of truth for
look) and to the **web app** for behavior. Plan: `~/.claude/plans/streamed-juggling-book.md`.

**Done + on-device-verified:** the **design system** — exact mockup tokens (soft
coral `#E89077`, ink `#1A1C26`, …), M3 chrome (`AppBar`, `BottomNavBar` filled
pill, 56dp rounded-square `CoralFab`, `MdField`/`MdToggle`/`MdSegment`,
`FilterPill`, `StatCard`, `ItemRow`, `ColorChip`, `Orbit` mark, sheets). The
**auth screen renders pixel-faithful to mockup 16** (Orbit, serif headline,
notched outlined fields, dark-ink submit).

**Reconciled (compile-green, built into the APK):** MainScaffold (M3 nav + coral
FAB + overlay route stack), Today (gradient Start-Next hero, filter pills, paused
ring, empty hero), Tasks (app bar + tabs), full-screen Task detail, New-task +
WHEN, Focus (dark ring + Orbit + 3 treatments) + Capture + Reflect, Calendar
Day/Week/Month, Collections 2-col grid + detail, Insights report + deep,
Settings hub + 6 subpages + Areas, Onboarding 4-step, Command palette
full-screen, Avatar menu. `:design` token test added (185+ tests still green).

**Audit + fix pass (v0.2.1, 2026-05-29):** a 28-agent workflow audited every
screen/sheet/component against the mockup JSX + web behavior (37 high / 61 med /
54 low verified findings → `audit-ui-findings.md`). Fixed this pass: system **back
button** (overlays/focus/tab), **status- & nav-bar insets** (edge-to-edge no longer
collides), **completed-today tasks now show on Today** (green check + strikethrough),
**running-vs-paused live card** with a real **progress ring**, focus **"Mark complete"**
action + **"← Out" no longer discards** the session, calendar **NOW line** + **life-area
block colors**, **per-tag capture colors**, **real signed-in account** in avatar +
Settings, **collections search**, new-task **no longer double-books** (passes real
blocks), and the **priority picker removed** (web/DB has no priority UI — per the
"web wins over mockup" rule). Build green, 185 module tests green.

**Remaining (tracked in `audit-ui-findings.md`):** larger build-outs not yet done —
Insights report/deep charts (weekday bars, calibration scatter, interruption bins,
heatmap headers) + Week/Month range toggle; Calendar **Week 7-col grid** + sidebar
stats + **Month grid header/nav**; Collections **new-collection / item pin·edit·remove
/ recolor·rename·delete**; **Settings persistence** (DataStore + reactive theme/
density/accent/focus/sound/a11y); Task-detail **inline edit + recurrence + capture
promote/discard**; new-task **date picker / time-slot chips / conflict warning**;
auth **surface card**; **OS-surface pixel-match** (custom RemoteViews live-focus
notification + richer Glance widget, mockups 19–21). Full **post-auth on-device
screenshot pass** is blocked on the emulator (Supabase `mailer_autoconfirm=false`,
so no session without a real sign-in) — verify on a signed-in device instead.

**Web-parity pass (2026-05-29, on emulator — NOT yet on Firebase):** Google logo
on the auth button; **settings now work** (SettingsStore + reactive `UnstuckTheme`
off theme/accent/density/larger-type — theme flips live; focus/sound/a11y persist);
**Tasks** tabs reordered to the web (Backlog·All·Today·Upcoming·Later·Completed,
default Today) + area filter; the **hamburger menu now shows Areas to filter** and
the **avatar holds account/Settings** on every screen; **new-task sheet** rebuilt to
the web create modal (WHEN incl. pick-date, free-slot chips + conflict warning,
capture drafts, no priority); **Lists → Collections**; **Calendar** Week = 7-col grid
+ rollup, Month = calendar grid + weekday headers + month nav. Build + 185 tests green.

**Web-parity batches → Firebase (v0.3.0–0.3.2, testers ahmad@/justtesting6363@/zyzkazaure@):**
Two 16-area agent sweeps (web = source of truth, mockup = cue only) drove this — full
gap+bug lists in `audit-web-parity-gaps.md` + `audit-sweep2.md`. Landed: Tags end-to-end
(picker/filter/manage + cascade rename/delete, dedup + case-insensitive), editable Task
detail (name/first-action/estimate-incl-custom/area/repeat/tags + cascade delete), capture
promote/discard/add, focus pause-reasons + Save-for-later, Collections CRUD, Account
(password+reauth/export/delete-with-email-confirm), Insights charts + Week/Month/All +
real-data threshold, Google Calendar **connect+pull** (push still TODO), colored task tabs
+ area pills, Today backlog (calendar-day age chips), 24h day grid + real Week hour-grid,
reactive theme/accent/density, area rename/recolor. **v0.3.2 bug sweep fixed:** external
Google `g_` blocks no longer enqueued (was stalling the outbox); `scheduleTask` reschedules
in place (no dup blocks) + only bumps moveCount on real moves (slip detector was inflated);
re-opening a paused focus stays paused; onboarding seeds picked areas once + persists
treatment.

**Known remaining (next batch, in `audit-sweep2.md`):** Google Calendar **push**
(insert/patch/delete local blocks) + `listConnections` snake_case decode; focus **sounds**
(chimes/bell) + **ambient** wiring + **overrun-extend** flow + soft-overrun grace at runtime;
command-palette completeness (areas/capture-jump/route shortcuts); account email-verification
flows; onboarding struggles + first-action steps; recurrence **until** date; calendar
**block-time** UI + fuller block-edit sheet; a11y toggles' runtime effects.

## Status snapshot

- **P0 — Foundation: DONE.** Gradle multi-module scaffold (wrapper 8.9, AGP
  8.7.3, version catalog), repo on `main` → `github.com/btambaya/Unstuck_Android.git`.
  `:core` fully ported from the web `lib/*` + iOS `UnstuckCore`, **157 JUnit
  tests green** (`./gradlew :core:test`). CI runs `:core:test`.

`:core` ports (each mirrors the web Vitest + iOS XCTest cases):
- Models + enums (`TaskItem`, `CalBlock`, `Session`, `Capture`, `ReasonLog`,
  `CalendarConnection`, `ExternalEvent`, `ItemCollection`/`CollectionItem`,
  `TagRow`, `LifeArea`, `LiveSession`, `Recurrence` sealed + custom serializer).
- `Time`/`Clock` (java.time, `ZoneId.systemDefault()` = JS Date LOCAL semantics).
- `Uuid`, `CalBlockKind`, `TaskBucket`, `VisibleTasks` (+slip), `PickStartNext`,
  `Recurrence` (materialise/regen/label), `FreeSlots`, `FocusTimer`, `Analytics`
  (H1–H7 + insights), `AuthErrors`, `GoogleSyncMapping`, `TaskMutations`.

## Roadmap (powering through P0→P7)

1. **P0 — Foundation** ✅
2. **`:design`** ✅ — oklch→sRGB converter (7 tests), brand `UnstuckColors` light/dark,
   `UnstuckTheme` (CompositionLocal + M3), type scale, components (UButton/Chip/Card/
   SectionLabel/AreaDot). Builds to AAR.
2b. **P1 — `:data` + `:sync`** ✅
   - `:data` — Room single `records` table (JSON blob per row) + `outbox` + `live_session`;
     `LocalStore` typed Flows; **6 Robolectric round-trip tests** (incl. JSONB shape +
     external `g_` preservation + outbox FIFO).
   - `:sync` — `DbRowCodec` (PostgREST boundary, **10 tests**: snake_case top-level /
     camelCase JSONB / explicit-null clear / duration_sec omit / uuid-or-null / round-trip),
     `SyncDecision` (**5 tests**), `SupabaseClientProvider` (PKCE, `unstuck://auth-callback`),
     `AuthService`, `SyncGateway`, `Hydrator`, `WriteThrough`, `OutboxFlusher`,
     `RealtimeMirror`, `CalendarClient`, `PushClient` (FCM), `NotificationsClient`,
     `PreferencesClient`, `SyncCoordinator` (sessionStatus → wipe-rule → flush → hydrate →
     subscribe). Compiles against supabase-kt 3.0.3.

   Total tests green so far: **157 (:core) + 7 (:design) + 6 (:data) + 15 (:sync) = 185.**
3. **P1b — backend FCM delta** (in the `unstuck` repo: migration 018 `fcm_token`,
   `_shared/fcm.ts`, platform branches in the senders, deploy)
4. **P2 — Tasks + Today** ✅ — Today (Start Next / Up Next / in-progress resume),
   Tasks (visibleTasks filter views + area + slip toggle + create/edit + schedule-next-free-slot).
5. **P3 — Focus** ✅ (core) — full-screen timer on FocusTimer + 1s ticker, 3 treatments,
   pause reasons → reason_logs, mid-session captures, extend-on-overrun. *Pending: FocusTimerService
   foreground notification + ambient audio (surfaces phase).*
6. **P4 — Calendar** ✅ (agenda) — upcoming blocks grouped by day, unschedule, external g_ blocks,
   schedule via free-slot. *Pending: drag-to-schedule day grid + Google connect UI (Custom Tabs).*
7. **P5 — Collections / Tags & Areas** ✅ — lists with items (add/toggle/delete), areas + tags CRUD in Settings.
8. **P6 — Insights / Settings / Auth** ✅ — Insights (topInsights + stats + pause anatomy + slipping),
   Settings (areas/tags/sync/sign-out), Auth (email/password + sign-up + magic link + Google).
   *Pending: onboarding struggles flow, command palette, recurrence editor UI (model + label done).*

**`:app` builds — `./gradlew :app:assembleDebug` → 20 MB debug APK.** Manual DI
(`AppGraph`) instead of Hilt (fewer codegen moving parts). Central `AppViewModel`
exposes every collection as a StateFlow off the Room store + all write actions
through the sync engine. Bottom nav Today · Tasks · [+] · Calendar · Lists, with
Focus / task-detail / settings as overlays.
9. **Surfaces** ✅ — `StartNextSnapshot` (DataStore) → Glance `StartNextWidget`
   (updates when the recommendation changes), `FocusTimerService` (foreground
   chronometer notification, started/stopped by FocusScreen), `UnstuckMessagingService`
   + `registerFcmToken` (FCM — dormant until google-services.json), `SyncWorker`
   (30-min periodic flush+hydrate via `SyncCoordinator.syncNow()`),
   POST_NOTIFICATIONS runtime request. Widget/snapshot live in :app (no :shared module).
10. **P6/P7 polish** — ✅ recurrence editor (`RecurrenceEditor` in new/edit
    sheets; `scheduleTask` materialises the horizon series), ✅ first-run
    onboarding (struggles → user_preferences + seeds canonical life areas),
    ✅ command palette (search tasks + actions, from the Today header),
    ✅ adaptive launcher icon (Orbit mark), ✅ dark via
    `UnstuckTheme(isSystemInDarkTheme())`, ✅ **bundled brand fonts** (Geist
    variable + Instrument Serif + IBM Plex Mono in `:design/res/font`, wired in
    Type.kt — verified rendering on-device), ✅ **ambient focus audio**
    (`res/raw/ambient_focus.wav` brown-noise loop + `AmbientAudio` MediaPlayer,
    speaker toggle in Focus), ✅ **drag-to-schedule day grid** (`DayGrid` —
    long-press a tray task, drop on an hour slot → schedules; Calendar tab has
    an Agenda/Grid toggle), ✅ **release signing** (`signingConfigs.release`
    reads gitignored `keystore.properties`; `./gradlew :app:assembleRelease`
    produces a signed APK — verified v1+v2).

### Release builds
`keystore.properties` + `unstuck-release.keystore` exist locally (gitignored;
dev passphrase `unstuck-dev`). `./gradlew :app:assembleRelease` → signed
`app/build/outputs/apk/release/app-release.apk`. For Play, swap in your own
upload key (enable Play App Signing): regenerate the keystore + update
`keystore.properties`.

**Validated on-device** (Pixel_Fold emulator, API 35): installs + launches with
no crashes; Supabase client initialises (`SupabaseClient created!`); Room + DI +
Compose theme + oklch brand colours render — the auth screen shows the serif
wordmark + coralDeep CTA. With `secrets.properties` (anon key) it reaches the
live auth screen; without it, the setup screen.

## Critical gotchas (inherited from the iOS build)

- **DbRowCodec:** top-level columns are snake_case but JSONB internals are
  camelCase (`recurrence.daysOfWeek`). Do NOT apply a global SnakeCase naming
  strategy — `@SerialName` per top-level row field, leave nested camelCase.
- **Null clearing:** kotlinx `explicitNulls=true` sends explicit `null` so an
  upsert clears a removed field (matches the web `?? null`); `duration_sec` is
  the one omit-when-nil (default + `encodeDefaults=false`).
- **Stable sort:** Kotlin's `sortedWith` IS stable (unlike Swift) — open-before-
  done partition is still explicit for parity.
- **Dates:** java.time + `ZoneId.systemDefault()` reproduce JS Date LOCAL math;
  ISO strings compare lexicographically; tests run `TZ=UTC`.

## Manual prerequisites (owner)

1. ✅ **DONE** — Firebase/FCM configured: project `unstuck-46e8c`, Android app
   (`tech.csalliance.unstuck`), `app/google-services.json` in place (gitignored;
   plugin auto-applies), Supabase secrets `FCM_PROJECT_ID` + `FCM_SERVICE_ACCOUNT`
   set. FCM is live end-to-end (token registers on sign-in; senders route
   Android→FCM / iOS→APNs).
2. ✅ `SUPABASE_ANON_KEY` → `secrets.properties` (gitignored) — set for local runs.
3. ✅ Google OAuth `/calendar-callback` redirect registered (shared w/ iOS).
4. **Remaining (optional, store only):** Play upload key — release builds sign
   with the gitignored dev keystore today; swap in your own upload key in
   `keystore.properties` + Play Console for distribution.
