# Unstuck (Android) — App Guide

A complete walkthrough of what Unstuck is, how it's built, and how every part works. Companion to `README.md` and `handover.md`.

> **One-line:** Unstuck is a gentle, ADHD-friendly task + focus app. You capture what's on your mind, schedule it onto a calendar, run distraction-light focus sessions, and the app nudges softly (never nags). It syncs across devices via Supabase and two-way with Google Calendar.

---

## 1. Product overview

Unstuck's job is to lower the activation cost of starting. Its design voice is **soft, no shame, no counts, no streaks** — offers and questions, not commands. Core surfaces:

- **Today** — a calm home: a greeting, a "Start Next" hero (the one thing to do now), your today list / backlog, the live focus session, a session-end recap card, and quiet in-app nudges.
- **Tasks** — the full list with tabs (Backlog / All / Today / Upcoming / Later / Completed), area filters, tags.
- **Calendar** — Day / Week / Month grids; drag tasks onto time slots; two-way Google Calendar sync.
- **Focus** — a full-screen distraction-light timer with 3 treatments (Ambient / Cockpit / Monk), pause/capture, and a live ongoing notification.
- **Collections** — lightweight lists ("keep small things here").
- **Insights** — reflections (Report / Deep dive), never a score.
- **Settings** — theme/accent/density, focus prefs, sound, accessibility, areas, tags, account, notifications.
- **Notifications** — pre-task reminders, the live focus notification, paused-too-long check-ins, session recap, morning brief, and in-app nudges (see §7).

The **web app** (`/Users/ahmadtambaya/Desktop/projects/unstuck`) is the source of truth for *behavior and data*; the design mockups are a *look* cue (and a starting idea — not a literal spec).

---

## 2. Architecture at a glance

Gradle multi-module, Kotlin + Jetpack Compose + Material3. A lightweight manual DI container (`AppGraph`) rather than Hilt.

```
:core     pure Kotlin — models + logic (no Android deps): TaskItem, CalBlock, Session, FocusTimer,
          visibleTasks, pickStartNext, free-slots, recurrence, analytics (slipping/calibration/heatmap),
          Time/Clock. Heavily unit-tested.
:data     Room persistence. A single JSON-blob `records` table + an outbox + a live_session row.
          LocalStore exposes typed Flows (tasks(), blocks(), captures(), connections(), liveSession()…).
:design   Theme (UnstuckColors light+dark, oklch), Type (Instrument Serif / IBM Plex Mono / sans),
          shared Compose chrome (UButton, FilterPill, AppBar, Orbit mark, etc.).
:sync     The sync engine: SupabaseClientProvider, SyncCoordinator, WriteThrough, Hydrator,
          OutboxFlusher, RealtimeMirror, DbRowCodec, and the API clients (CalendarClient, PushClient,
          NotificationsClient, PreferencesClient, AuthService).
:app      Compose UI (screens under ui/…), AppViewModel, AppGraph DI, MainActivity, UnstuckApp,
          and the OS surfaces under surface/ (FocusTimerService, widgets, notifications).
```

### DI / process wiring
- `UnstuckApp.onCreate()` builds `AppGraph`, starts the coordinator, registers notification channels, and starts the reminder observer.
- `AppGraph` owns the `LocalStore` (Room), the `SupabaseClientProvider` + `SyncCoordinator` (null until the anon key is configured via `secrets.properties` → `BuildConfig`), the device-local `SettingsStore`, and a process `CoroutineScope`.
- `AppViewModel` is the single UI brain: it exposes StateFlows off the store + auth, and write actions that go through `coordinator.write` (a `WriteThrough`).

---

## 3. Data model & local store

All user data is modeled in `:core` (`Models.kt`) and persisted in `:data` as JSON rows in one Room table keyed by `(table, id)`. `LocalStore.observe(table, serializer)` returns a `Flow<List<T>>`; `upsert`/`delete`/`replace` mutate it.

Key entities:
- **TaskItem** — name, estimateMin, lifeArea, tags, firstPhysicalAction, recurrence, later (backlog), moveCount, done, totalFocused, createdAt/updatedAt, `reminderLeadMin?`.
- **CalBlock** — a scheduled block: date (YYYY-MM-DD) + startTime (HH:MM) + durationMinutes, taskId, taskName, kind (TASK / EXTERNAL / PLACEHOLDER), externalEventId/externalConnectionId (for Google events).
- **Session** — a completed focus session (taskId, actualSec, completedAt) → feeds Insights.
- **Capture** — a thought tagged follow-up/idea/edit/question/distraction, attached to a task/session.
- **ItemCollection**, **LifeArea**, **TagRow**, **ReasonLog**, **CalendarConnection**, **LiveSession**.

The **live session** (the running focus timer) is a single store row, mutated by `FocusTimer` (pure logic in `:core`).

---

## 4. Sync engine (`:sync`)

Offline-first, optimistic, multi-device. Pattern: **write locally now, reconcile with the server in the background.**

- **WriteThrough** — every mutation does an optimistic local `store.upsert/delete` (UI updates immediately via Room Flows) **and** enqueues a server op in the outbox. cal_block upserts depend on the parent task so it flushes first. (It also fires the Google-Calendar push hooks — see §6 — and the notification hooks.)
- **OutboxFlusher** — drains the outbox to Supabase (FIFO, dependency-ordered) when online.
- **Hydrator** — pulls server-canonical rows (per table, via `DbRowCodec`) and `replace`s the local set (removing stale rows). Runs on auth + manual sync.
- **RealtimeMirror** — subscribes to Supabase Realtime and mirrors remote changes into the store live.
- **SyncCoordinator** — the conductor: observes auth state; on sign-in it flushes → hydrates → subscribes realtime → pulls Google Calendar; owns the API clients; and hosts the Google connect/sync and the notification push helpers.
- **DbRowCodec** — maps domain models ⇆ the server's snake_case columns + camelCase JSONB.

**Gotcha that bit us repeatedly:** every `functions.invoke { setBody(...) }` must set `contentType(ContentType.Application.Json)`, or ktor throws "Fail to prepare request body" and the call silently fails (fixed in CalendarClient and the notification clients).

---

## 5. Auth

Supabase Auth (PKCE). Email+password, magic link, and Google sign-in, with anti-enumeration redirects. The OAuth/magic-link callback is `unstuck://auth-callback`, handled in `MainActivity` (the manifest registers the whole `unstuck` scheme). FCM device-token registration happens on first authenticated session.

---

## 6. Calendar & two-way Google sync

- **Local calendar** — Day/Week/Month grids render `cal_block`s. Day grid is 0–24h with a NOW line; you can tap a slot to create a task there, drag from the unscheduled tray (the drag ghost tracks your finger 1:1), and drag a scheduled block to reschedule. Week view is 0–24h.
- **Google connect** — `CalendarSyncBar` → `beginGoogleConnect()` (Edge fn `calendar-sync/authorize`) opens a Chrome Custom Tab. **Key:** Google rejects custom-scheme redirects for web OAuth clients, so the redirect is the HTTPS bounce page `https://unstuck-602.pages.dev/calendar-callback` (registered on the Google Cloud Console Web client), which forwards `?code&state` to `unstuck://calendar-callback` → `MainActivity` → `completeGoogleConnect` (exchanges the code server-side, hydrates the connection).
- **Pull** (Google → Unstuck) — `pullCalendar()` lists connections, pulls events for [−7d, +30d] (**must send RFC3339 timestamps**, not bare dates, or Google returns 0), maps them to `kind=EXTERNAL` blocks, and reconciles deletions. Events we pushed ourselves are de-duped.
- **Push** (Unstuck → Google) — `WriteThrough.upsertCalBlock`/`deleteCalBlock` fire `pushBlockUpsert`/`pushBlockDelete` in the coordinator: a task block INSERTs a Google event on the user's **primary** calendar (selectedCalendarIds can be read-only → 403), stamps `externalEventId` back on the block (so edits PATCH the same event), and deletes on removal.

---

## 7. Notifications (v0.4.x)

Three "moments", one voice, a server-side 3-push/day cap + device dedup (in the backend). On Android:

- **Channels** (`NotificationChannels`) — per-purpose: reminders (HIGH), recap (DEFAULT-silent), paused (HIGH), daily (LOW), focus (ongoing LOW), all lock-screen-private with an "Unlock to read" public version. Created at app start.
- **Live focus notification** (`FocusTimerService`, ongoing FGS) — running shows "FOCUSING · LIVE" (coral) + Pause/Capture; paused flips to amber "Did you step away?" + Resume/Snooze/End, same notification updated in place. Actions route through `NotificationActionReceiver` → `FocusCommands` (shared with the UI). Persists after you leave the focus screen; torn down by Done/End.
- **Pre-task reminders** (`ReminderScheduler` + `ReminderReceiver`) — observes blocks and sets exact `AlarmManager` alarms at (start − lead) for task + Google-event blocks. Lead = per-task override (New Task sheet) ?: global default (Settings → Focus → "Remind me before tasks"). Rescheduled on boot.
- **Paused-too-long** (`PausedCheckinScheduler`, WorkManager +14 min) — asks the server (`send-paused-checkin`, cap/pref) then posts the local check-in.
- **Session-end recap** — `finishFocus` calls `send-session-recap` (in-app card always; push only when away) + shows a "You did the thing." card on Today (alongside the Reflect sheet).
- **Morning brief** — server cron (`dispatch_morning_briefs`, every 15 min, fires at each user's wake window) → `send-morning-brief` → push "Want to glance at today?".
- **In-app nudges** (`AppViewModel.nudges`, Today) — quiet, dismissible, no push: a slipping task (>3 wks old or moved 3×) or a follow-up capture worth promoting; one card at a time.
- **Incoming FCM** (`Push.kt` → `NotificationRenderer`) — routes by `data.kind` to the right channel + deep-link; the backend sends **data-only** payloads (`_shared/fcm.ts` `dataOnly`) so Android renders correctly in every app state.

Backend lives in the `unstuck` repo's `supabase/functions/` (Edge functions + `_shared/{fcm,apns}.ts`) and `supabase/manual/notification_cron.sql`. Tables: `device_tokens`, `notification_preferences`, `notification_ledger` (the cap), `notification_queue`.

---

## 8. Focus sessions

`FocusScreen` drives `AppViewModel.startFocus/pauseFocus/resumeFocus/finishFocus`. The live session is a `:core FocusTimer` state machine (start/pause/resume/overrun/extend). Three treatments (Ambient ring, Cockpit captures-rail, Monk minimal) on a dark immersive background. "Done" = mark complete; "End for now" = record the session, keep the task open; "Save for later" / "← Out" = leave it running/paused (resumable from Today). Reflect sheet ("How did that land?") and a recap card follow.

---

## 9. Insights

`:core/Analytics.kt` computes reflections from sessions/tasks/captures: calibration (estimate accuracy), the time-of-day heatmap, capture breakdown, slip detection, pause anatomy. Report = stat cards; Deep dive = a stat grid + hour×day heatmap. Gated behind a few sessions; framed as observations, never a score.

---

## 10. Settings & preferences

Device-local prefs (`SettingsStore` → SharedPreferences, mirrors the web `theme-context`): theme (system/light/dark), accent, density, larger type, focus length/overrun/soft-exit/pause-reasons, sound toggles, accessibility, focus treatment, **reminderLeadMin** (+ per-task reminder overrides). `UnstuckTheme` reacts to theme/accent/density. Server-side `notification_preferences` (wake window, quiet hours, cap, per-moment toggles) exist in the backend; surfacing them in the Android settings UI is a known follow-up.

---

## 11. Build, run, deploy

- **Build:** `./gradlew :app:assembleDebug` (or `assembleRelease`). Tests: `./gradlew :core:test :sync:test`.
- **Secrets:** `secrets.properties` (gitignored) supplies `SUPABASE_URL` / `SUPABASE_ANON_KEY` → `BuildConfig`. `keystore.properties` for release signing. `google-services.json` (gitignored) enables FCM.
- **Firebase distribution:** `./gradlew :app:appDistributionUploadRelease -PappDistTesters="a@x,b@y"` (build `assembleRelease` first — it uploads the on-disk APK).
- **Supabase backend:** the CLI on this machine is authed to the Unstuck project (`uaxfteluwctrlgwmmfzi`). `supabase functions deploy <fns> --project-ref uaxfteluwctrlgwmmfzi`; run SQL with `supabase db query --linked` (Management API, no DB password). Secrets are already set.
- **Versioning:** `app/build.gradle.kts` `versionCode`/`versionName`; bump per release; release notes in the `firebaseAppDistribution { releaseNotes = "…" }` block (keep it a single clean string — internal escaped quotes have broken builds).

---

## 12. Known gotchas (learned the hard way)

- supabase-kt `functions.invoke { setBody }` needs `contentType(application/json)` or it silently fails.
- kotlinx.serialization omits **default** values — a defaulted `provider = "google"` was dropped from a request body and the server rejected it. Make server-required fields explicit (no default).
- A server endpoint returning **snake_case** rows won't decode into a camelCase model — add a snake_case DTO (e.g. `calendar-sync/connections`).
- Google `events.list` needs RFC3339 timestamps; bare dates yield 0 events.
- Google blocks custom-scheme redirects for Web OAuth clients → use the HTTPS bounce page.
- Channel importance is immutable after creation → use new channel ids when changing importance.
- The emulator screenshot is downscaled; map tap coordinates to the real 1080-wide device, and a hardware keyboard suppresses the soft IME.
- Notification action receivers are `exported=false` (good) → you can't trigger them with `adb am broadcast`; test via the UI or the PendingIntent.
