## High-Level Architecture & Module Map

This chapter is your orientation map for the Unstuck Android codebase. By the end you should know which Gradle module owns what, why the dependency boundaries are drawn where they are, how a write makes its way from a tap to Supabase (and how a server change makes its way back to the screen), and what the app wires up at process start. Everything here is taken from the actual source — file paths are real and worth opening alongside this text.

### The big picture: a five-module, offline-first Compose app

Unstuck Android is the third client of a shared product whose **source of truth is the web app + the Supabase Postgres backend** (`/Users/ahmadtambaya/Desktop/projects/unstuck`). The Android app is a faithful port of the web's `lib/*` logic and the iOS Swift packages. You'll see that lineage everywhere: file header comments say things like "port of `bootstrap-listener.tsx`", "port of the iOS `WriteThrough.swift`". When in doubt about *intended* behavior, the web `lib/` is the canonical reference.

The app is **offline-first**: every user action writes to a local Room database *first* (so the UI updates instantly), then a background engine mirrors the change to Supabase. Reads always come off Room as reactive Flows; the server only ever *fills* Room (via an initial hydrate + realtime), never the UI directly. There is no spinner-on-every-tap; the network is an eventually-consistent replica.

```
                 ┌─────────────────────────────────────────────┐
                 │                   :app                       │
                 │  Compose screens, nav, MainActivity,         │
                 │  AppViewModel, AppGraph (manual DI),         │
                 │  device "surfaces" (notifications, widget,   │
                 │  alarms, FCM, WorkManager)                   │
                 └───────┬─────────────┬───────────────┬────────┘
                         │             │               │
              ┌──────────▼──┐   ┌──────▼──────┐   ┌────▼──────┐
              │   :sync     │   │   :design   │   │  (uses    │
              │ supabase-kt │   │ Compose     │   │  :data,   │
              │ + engine    │   │ theme +     │   │  :core    │
              └───┬──────┬──┘   │ components  │   │  too)     │
                  │      │      └─────────────┘   └───────────┘
            ┌─────▼──┐ ┌─▼──────────┐
            │ :data  │ │   :core    │
            │ Room   │ │ pure Kotlin│
            │ store  │ │ domain +   │
            └───┬────┘ │ logic      │
                │      └────▲───────┘
                └───────────┘  (:data depends on :core)
```

The module list lives in `settings.gradle.kts`:

```kotlin
rootProject.name = "Unstuck"
include(":core"); include(":design"); include(":data"); include(":sync"); include(":app")
```

### The modules, their responsibilities, and dependency boundaries

The dependency arrows are deliberately strict. Read each module's `build.gradle.kts` `dependencies {}` block to see exactly who may depend on whom — that's the enforcement mechanism.

#### `:core` — pure Kotlin/JVM domain + logic

`core/build.gradle.kts` applies only `kotlin.jvm` + `kotlin.serialization` — **no Android plugin**. This is the load-bearing constraint of the whole codebase: `:core` is a plain JVM library that runs headless under `./gradlew :core:test`. I verified there is not a single `import android.*` in `core/src/main`. Its only dependency is `kotlinx.serialization.json`.

Why pure Kotlin? Three reasons:
1. **Fast, deterministic unit tests** with no emulator/Robolectric. The test task even pins `user.timezone = "UTC"` so the date/bucket logic matches the web CI and iOS (`systemProperty("user.timezone", "UTC")`).
2. **It's a direct port of the web `lib/`** — the same logic running on three platforms must produce identical results.
3. It forces the domain rules to be free of framework noise.

What's in it (`core/src/main/.../core/`):
- `model/Models.kt`, `model/Enums.kt` — the `@Serializable` domain types: `TaskItem`, `CalBlock`, `Session`, `Capture`, `ReasonLog`, `ItemCollection`, `TagRow`, `LifeArea`, `CalendarConnection`, `LiveSession`, and enums like `CalBlockKind`, `Priority`, `FocusTreatment`. These are the lingua franca of every other module.
- `logic/` — the rules ported from web `lib/`: `TaskBucket.kt`, `VisibleTasks.kt`, `PickStartNext.kt`, `Analytics.kt`, `Recurrence.kt`, `TaskMutations.kt` (`applyCompletion`, `bumpMoveCount`, `regenerateForTask`), `FocusTimer.kt` (the pure timer state machine: `start`/`pause`/`resume`/`extend`/`elapsedSec`), `FreeSlots.kt`, `GoogleSyncMapping.kt` (`externalEventToBlock`), `Uuid.kt` (`newUuid`, `isUuid`).
- `time/Time.kt` — `Time`, `Clock` date helpers (UTC-anchored, web-parity).

**Boundary rule:** `:core` depends on *nothing internal*. Everyone depends on `:core`.

#### `:data` — the offline-first Room store

`data/build.gradle.kts` is an `android.library` with `room` + `ksp`. It depends on `:core` only. Its design (verbatim from the build file header): *"A single `records` table holds every synced row as a JSON blob keyed by (table, id); the app loads full collections and computes buckets in memory via :core. Plus local-only `outbox` (op queue) and `live_session` tables."*

Key files:
- `db/Entities.kt` — three Room entities and the `Tables` name constants (which **match the Supabase table names** so the sync layer passes them straight through):
  - `RecordEntity(tableName, id, data, updatedAt)` — composite PK `(tableName, id)`; `data` is the model serialized to JSON.
  - `OutboxEntity(seq, op, recordTable, recordId, payload, dependsOn, createdAt)` — the write-ahead queue, FIFO by autogenerated `seq`.
  - `LiveSessionEntity(id=0, data)` — single-row device-local focus session.
- `db/Daos.kt` — `RecordDao` (reactive `observe(table): Flow<List<RecordEntity>>`, plus `upsert`, `deleteById`, and the `@Transaction replaceTable(...)` used by hydrate), `OutboxDao`, `LiveSessionDao`.
- `db/UnstuckDatabase.kt` — the Room DB (`version = 1`, `fallbackToDestructiveMigration()`), built once in `AppGraph`.
- `LocalStore.kt` — **the typed facade everyone uses**. It hides Room behind domain types: `tasks(): Flow<List<TaskItem>>`, `blocks()`, `sessions()`, … plus writes `upsert`/`delete`/`replace`, the outbox API (`enqueue`/`pending`/`dequeue`/`pendingCount`), and the live-session API. It owns the `Json` config (`ignoreUnknownKeys = true`, `encodeDefaults = true`, `isLenient = true`).

The "JSON-blob-per-row" design is important to internalize: there is **no per-field schema** in Room. A new domain field on `TaskItem` needs *no migration* — it just becomes part of the JSON. Buckets, filtering, and "start next" are computed **in memory** by `:core` over the full collection, exactly like the web app holds everything in a store and derives views. That's why `version = 1` has never needed bumping.

**Boundary rule:** `:data` depends only on `:core`. It knows nothing about Supabase or networking.

#### `:design` — Compose theme + shared components

`design/build.gradle.kts` is an `android.library` with `compose`. Notably it depends on **neither `:core` nor `:data`** — it's a self-contained design system (the analog of the iOS `UnstuckDesign` package): `UnstuckTheme`, `UTheme.colors` (oklch brand tokens), `UFont`, `AccentPalette`, and shared widgets like `SectionLabel`. It has no business logic. (One small coupling note: `AccentPalette` and a couple of enums live in `:design` and are referenced by `SettingsStore` in `:app`.)

**Boundary rule:** `:design` is a leaf for the UI. `:app` depends on it; nobody else does.

#### `:sync` — supabase-kt wiring + the offline-first engine

`sync/build.gradle.kts` is an `android.library` (serialization, coroutines, supabase-kt 3.x BOM: postgrest/auth/realtime/functions + ktor-okhttp). It depends on **`:core` and `:data`**. This is the analog of the iOS `UnstuckSync` package. The header notes the pure pieces (`DbRowCodec`, `SyncDecision`) are JVM-unit-tested while the networked pieces compile against supabase-kt.

Key files (the engine):
- `SupabaseClientProvider.kt` — builds the shared `SupabaseClient`: PKCE auth flow, `scheme = "unstuck"`, `host = "auth-callback"`, `autoLoadFromStorage`/`autoSaveToStorage`, plus Postgrest/Realtime/Functions.
- `SyncGateway.kt` — the thin PostgREST CRUD primitive: `fetchAll(table)`, `upsert(table, row, userId)` (injects `user_id` like the web bridge: `{ ...row, user_id }`, `onConflict = "id"`), `delete(table, id)`. Reads rely on RLS to auto-scope.
- `DbRowCodec.kt` — converts between domain models and the *server* JSON row shape (snake_case columns, explicit nulls). This is the boundary translator.
- `WriteThrough.kt` — optimistic local write + outbox enqueue (detailed below).
- `OutboxFlusher.kt` — drains the outbox to Supabase, FIFO + dependency-ordered.
- `Hydrator.kt` — pulls every table once and replaces local (server-canonical).
- `RealtimeMirror.kt` — one realtime channel per table, applies inbound changes to the store.
- `SyncCoordinator.kt` — the orchestrator that ties auth state to the engine.
- `SyncDecision.kt` — pure, unit-tested decisions (cache-wipe rule, cal_blocks merge).
- `Clients.kt`, `AuthService.kt`, `CalendarClient.kt` — feature clients (`PushClient`, `NotificationsClient`, `PreferencesClient`, auth, Google Calendar).

**Boundary rule:** `:sync` is the *only* module that talks to Supabase. `:app` orchestrates it but `:data`/`:core`/`:design` never see it.

#### `:app` — the Compose application + device surfaces

`app/build.gradle.kts` depends on **all four** other modules (`:core`, `:design`, `:data`, `:sync`). It's the only `android.application`. It contains:
- Entry points: `UnstuckApp` (the `Application`), `MainActivity`.
- The manual DI container: `AppGraph.kt`.
- The state holder: `ui/AppViewModel.kt`.
- The Compose UI: `ui/` (screens, nav `MainScaffold`, `AppRoot`).
- Device-local settings: `SettingsStore.kt`.
- The `surface/` package — everything that touches Android system services: `FocusTimerService` (foreground chronometer), `NotificationChannels`/`NotificationRenderer`/`NotificationActionReceiver`, `ReminderScheduler`/`ReminderReceiver` (exact alarms), `SyncWorker` (WorkManager), `Push`/FCM, `StartNextWidget` (Glance).

A useful mental model: **`:app` is where Android-the-platform meets the cross-platform core.** All the `android.*` / Compose / WorkManager / AlarmManager noise is concentrated here and in the design system.

### Manual DI: `AppGraph` + `UnstuckApp` (and why *not* Hilt)

There is no Hilt. The Hilt plugin is even declared `apply false` in the root `build.gradle.kts` (a vestige / option held open), but the app uses a hand-written container. The rationale is stated directly in `app/build.gradle.kts`:

> *"Uses a lightweight manual DI container (AppGraph) rather than Hilt — fewer codegen moving parts, adequate for one app process."*

`AppGraph` (`app/.../AppGraph.kt`) is **one instance per process**, constructed in `UnstuckApp.onCreate`. It is the composition root. Read it top to bottom — it's only ~40 lines and it tells you the entire object graph:

```kotlin
class AppGraph(context: Context) {
    val configured = BuildConfig.SUPABASE_ANON_KEY.isNotEmpty()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)   // app-lifetime scope
    val db = UnstuckDatabase.build(context.applicationContext)
    val store = LocalStore(db)
    val provider: SupabaseClientProvider? =
        if (configured) SupabaseClientProvider(SyncConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)) else null
    val coordinator: SyncCoordinator? =
        provider?.let { SyncCoordinator(it, store, context.applicationContext, scope) }
    val settings = SettingsStore(context.applicationContext)   // device-local prefs
    var onboarded: Boolean  // backed by SharedPreferences "unstuck.app"
    fun start() { coordinator?.start() }
}
```

Things to notice:
- **`configured` gates everything networked.** The anon key flows from `secrets.properties` → `BuildConfig` (see `app/build.gradle.kts`). If it's empty, `provider` and `coordinator` are `null`, and `AppRoot` shows a `SetupScreen()` instead of the app — exactly like iOS. This is why `coordinator` and `write` are nullable throughout `AppViewModel` (`private val write get() = graph.coordinator?.write`).
- **`scope`** is a `SupervisorJob` on `Dispatchers.Default` that lives for the whole process. The sync engine, realtime subscriptions, and the `ReminderScheduler` observer all run on it.
- **Wiring lives in `SyncCoordinator.init`**, not in `AppGraph`: the coordinator hooks `WriteThrough`'s Google-push seams (`write.pushCalBlock = { pushBlockUpsert(it) }`). This keeps `:data`/`:core` Google-agnostic — the seam is filled at the app/sync layer.

`UnstuckApp.onCreate` (`app/.../UnstuckApp.kt`) is the process bootstrap and it does exactly four things:

```kotlin
override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)             // build the whole object graph
    graph.start()                      // SyncCoordinator starts observing auth
    NotificationChannels.ensureAll(this)   // register notification channels
    ReminderScheduler.observe(this)    // keep pre-task alarms in sync with blocks
}
```

`MainActivity` retrieves the graph via `(application as UnstuckApp).graph` and hands it to `AppRoot(graph)`. The `AppViewModel` is created with a tiny manual factory that closes over the graph (`AppRoot.kt`):

```kotlin
val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(graph) } })
```

So the dependency-injection story is: **construct everything once in `AppGraph`, pass `graph` to the `ViewModel`, expose everything else as Compose state off the `ViewModel`.** No annotations, no generated components.

### The offline-first philosophy in one sentence

**Writes go local-then-remote; reads come local-only; the server fills local in the background.** Three consequences fall out of this and are worth holding in your head:
1. The UI never awaits the network on a write — it awaits Room, which is effectively instant.
2. The outbox makes writes durable across app death and offline periods; the flush is idempotent (PostgREST upsert on `id`).
3. Conflict resolution is last-write-wins, mediated by `updatedAt`/timestamps carried on the rows and the `replace`/`upsert` ordering in the store.

### Data-flow: an outbound write (UI → Supabase)

Take "create a task" as the worked example. The call sequence:

```
TodayScreen / NewTaskSheet  (Compose)
   │  vm.addTask("Email Dana", ...)
   ▼
AppViewModel.addTask                       (app/ui/AppViewModel.kt)
   │  builds TaskItem(id=newUuid(), createdAt/updatedAt=isoNow(), ...)   ← :core newUuid
   │  launchWrite { write?.upsertTask(t) }   (viewModelScope coroutine)
   ▼
WriteThrough.upsertTask                     (sync/WriteThrough.kt)
   │  ① store.upsert(Tables.TASKS, t, serializer, t.id, t.updatedAt)   ← LOCAL write
   │  ② enqueue("tasks", t.id, "upsert", DbRowCodec.encodeTask(t))     ← OUTBOX row
   ▼
LocalStore (Room)                            (data/LocalStore.kt)
   │  RecordDao.upsertOne(RecordEntity)  +  OutboxDao.enqueue(OutboxEntity)
   │
   ├─► RecordDao.observe("tasks") Flow RE-EMITS  ──►  AppViewModel.tasks StateFlow
   │                                              ──►  Compose recomposes (instant)
   │
   ▼  (later, async, when signed in / on reconnect)
OutboxFlusher.flush(userId)                  (sync/OutboxFlusher.kt)
   │  reads store.pending() FIFO, dependency-ordered
   │  per op → SyncGateway.upsert(table, payloadJson, userId)
   ▼
SyncGateway → supabase-kt Postgrest → Supabase  (RLS-scoped, user_id injected)
   │  on success → store.dequeue(op.seq)
```

Critical details from the code:

- **The local write is what makes the UI move.** `LocalStore.upsert` writes a `RecordEntity`; `RecordDao.observe(table)` is a Room `Flow`, so it re-emits, `AppViewModel`'s `StateFlow` (built via `flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`) updates, and Compose recomposes. The network is not in this path at all.

- **The outbox decouples durability from delivery.** `WriteThrough.enqueue` writes an `OutboxEntity` with `createdAt = nowMillis()`. The flush happens elsewhere (on sign-in/reconnect via `SyncCoordinator.handle`, and every 30 min via `SyncWorker`).

- **Dependency ordering prevents FK orphans.** A `cal_block` upsert carries `dependsOn = task.id` (only when that id is a UUID — `isUuid(it)`). `OutboxFlusher` holds an op back while its `dependsOn` rowId still has a pending op:

  ```kotlin
  val pendingIds = all.map { it.recordId }.toSet()
  val flushable = all.filter { it.dependsOn == null || it.dependsOn !in pendingIds }
  ```
  So the parent `tasks` row always reaches the server before the child `cal_blocks` row that references it.

- **The flush is resilient.** It loops until the queue is empty or no op made progress; if every remaining op errors, it stops and retries on the next trigger (`if (!progressed) break`). Successful ops are dequeued by `seq`.

- **Two-way Google Calendar is a best-effort seam layered on top.** `WriteThrough.upsertCalBlock` additionally calls the `pushCalBlock` hook (wired by `SyncCoordinator`) to mirror task blocks into Google, and stamps the returned `externalEventId` back on the block. External `g_`-id blocks are explicitly *never* pushed to our `cal_blocks` table (their shape isn't ours; doing so would "fail forever and stall the outbox").

### Data-flow: inbound sync (server → screen)

Two inbound mechanisms feed the store: a one-shot **Hydrator** (catch-up) and a continuous **RealtimeMirror** (live). Both end at the same place — `LocalStore`, whose Flows drive Compose.

```
                          ┌──────────────── Supabase (Postgres + Realtime) ────────────────┐
                          │                                                                 │
            (on sign-in / launch / periodic)                    (live, while subscribed)
                          ▼                                                  ▼
        Hydrator.hydrate()                              RealtimeMirror.subscribeAll(userId)
        (sync/Hydrator.kt)                              (sync/RealtimeMirror.kt)
          per table:                                      one channel per table:
            gateway.fetchAll(table)                         postgresChangeFlow filter user_id
            → DbRowCodec.decode*                             Insert/Update → DbRowCodec.decode → store.upsert
            → store.replace(table, …)                        Delete        → store.delete(id)
          (per-table error isolation;                        (calendar_connections NOT subscribed —
           cal_blocks preserves local g_ blocks)              encrypted creds never broadcast)
                          │                                                  │
                          └───────────────────────┬──────────────────────────┘
                                                   ▼
                                         LocalStore (Room)
                                                   │  RecordDao.observe(table) Flows re-emit
                                                   ▼
                                       AppViewModel StateFlows  →  Compose recomposition
```

Details:

- **Hydrate replaces; realtime upserts/deletes.** `Hydrator.replace(...)` calls `store.replace(table, models, …)` which is a Room `@Transaction` (`replaceTable`): wipe the table, insert the fetched rows. **Per-table error isolation** is deliberate — `runCatching { … }.onFailure { …leaving local intact… }` mirrors the web `hydrate.ts`'s "only replace if the fetch succeeded".

- **`cal_blocks` is special.** Local Google external blocks (`g_…` ids) live only on-device — their ids aren't UUIDs so they never round-trip to Postgres. `hydrateCalBlocks()` therefore merges remote (canonical) with locally-cached external blocks via `SyncDecision.mergeHydratedCalBlocks(remote, localExternal)` (remote wins on id collision) before replacing.

- **Realtime is per-table, filtered by `user_id`.** Channel name `unstuck_${table}_$userId`; the filter is client-side safety on top of server RLS. `calendar_connections` is intentionally *not* subscribed (it carries encrypted creds). Each subscription `launchIn(scope)` — that's `AppGraph.scope`, the process-lifetime scope.

- **Compose never reads the server.** Screens read `vm.tasks`, `vm.blocks`, etc., and compose them with `:core` derivations (`visibleTasks`, `pickStartNext`, `analytics`) in memory. The same model as web and iOS.

### The orchestrator: how auth state drives the whole engine

`SyncCoordinator` (`sync/SyncCoordinator.kt`) is the conductor — the port of the web `bootstrap-listener.tsx`. `start()` launches a single coroutine that collects `client.auth.sessionStatus` and calls `handle(status)`. This is the heart of the lifecycle:

```kotlin
is SessionStatus.Authenticated -> {
    val uid = status.session.user?.id ?: return
    val event = when (status.source) {        // map SDK source → our SyncAuthEvent
        SignIn / SignUp / External       -> SIGNED_IN
        Storage                          -> INITIAL_SESSION
        UserChanged / UserIdentitiesChanged -> USER_UPDATED
        else -> return                          // Refresh/Unknown: no cache action
    }
    val prev = prefs.getString(KEY_PREV_USER, null)
    if (SyncDecision.shouldWipeCache(event, prev, uid)) store.clearAll()   // pure rule
    prefs.edit().putString(KEY_PREV_USER, uid).apply()
    flusher.flush(uid)            // 1) push offline edits
    hydrator.hydrate()            // 2) pull server-canonical
    realtime.subscribeAll(uid)    // 3) mirror live
    runCatching { pullCalendar() }// 4) ingest Google events if connected
}
is SessionStatus.NotAuthenticated -> if (status.isSignOut) {
    realtime.unsubscribeAll(); store.clearAll(); prefs.edit().remove(KEY_PREV_USER).apply()
}
```

The order — **flush → hydrate → subscribe** — matters: you push your offline edits *before* you overwrite local with server-canonical, so you don't lose them. The cache-wipe decision is extracted into the pure, testable `SyncDecision.shouldWipeCache` (SIGNED_IN always wipes; INITIAL_SESSION wipes only on a user switch via `prevUserId`; USER_UPDATED never wipes). `syncNow()` is the leaner version (flush + hydrate + calendar) used by the periodic `SyncWorker`.

### Process & app lifecycle: what starts when

Putting the timeline together from `UnstuckApp`, `MainActivity`, and the `surface/` package:

```
Process start
  └─ UnstuckApp.onCreate
       ├─ AppGraph(this)                     build Room DB, LocalStore, SupabaseClient, SyncCoordinator
       ├─ graph.start()                      SyncCoordinator collects auth.sessionStatus on graph.scope
       │      └─ on first emission (Storage → INITIAL_SESSION if already signed in):
       │             flush → hydrate → subscribeAll → pullCalendar
       ├─ NotificationChannels.ensureAll     register all notification channels
       └─ ReminderScheduler.observe(this)    combine store.blocks()+tasks(); keep exact alarms in sync

Activity start
  └─ MainActivity.onCreate
       ├─ enableEdgeToEdge()
       ├─ handleAuthOrCalendar(intent)       route unstuck:// deep links:
       │      ├─ host "calendar-callback" → coordinator.completeGoogleConnect(code,state)
       │      └─ else → supabase client.handleDeeplinks(intent)   (PKCE auth/magic-link)
       ├─ request POST_NOTIFICATIONS (Tiramisu+)
       ├─ collect auth.sessionStatus → registerFcmToken on Authenticated
       ├─ SyncWorker.schedule(this)          enqueue unique 30-min periodic sync (KEEP)
       └─ setContent { AppRoot(graph) }
                 └─ AppRoot: UnstuckTheme(reactive to settings)
                      ├─ !configured        → SetupScreen   (no anon key)
                      └─ else → when(authed) null→Loading / false→AuthScreen / true→MainScaffold
```

Note `MainActivity` is `launchMode="singleTask"` (manifest), so deep-link returns come through `onNewIntent` (which re-calls `handleAuthOrCalendar`) rather than a fresh activity — important for OAuth/magic-link round-trips.

Background & device surfaces (all in `:app/surface`, all reaching back into `graph`):
- **`SyncWorker`** — `CoroutineWorker`, unique periodic 30-min job, calls `graph.coordinator?.syncNow()`. The Android analog of the iOS `BGTaskScheduler` refresh.
- **`ReminderScheduler`** — observes `store.blocks() combine store.tasks()` on `graph.scope` and maintains an `AlarmManager` exact alarm per upcoming block at `(start − leadMinutes)`. Reschedules on `BOOT_COMPLETED` via `ReminderReceiver`. Uses exact alarms because reminders must be punctual; falls back to inexact if exact-alarm permission isn't granted.
- **`FocusTimerService`** — foreground service (`specialUse`) rendering the live focus chronometer with Pause/Capture actions, started via `FocusTimerService.start(context, taskName, sessionStartMs, …)`.
- **FCM** (`Push.kt`, `UnstuckMessagingService`) — dormant until `google-services.json` is dropped in; `MainActivity` registers the token once a session exists.
- **Glance widget** (`StartNextWidget`) — home-screen "Start Next".

### Pitfalls & gotchas to internalize early

- **Everything networked is nullable.** `graph.coordinator`, `graph.provider`, and thus `AppViewModel.write`/`auth` are `null` when `configured == false` (no anon key) — that's why you see `write?.upsertTask(...)` and `auth?.signIn(...) ?: AuthOutcome.Error("Not configured")` everywhere. Don't strip the `?`.

- **Don't push `g_` external blocks.** `WriteThrough.upsertCalBlock` and `deleteCalBlock` early-return for `kind == EXTERNAL` or ids starting `g_`. These rows aren't ours; enqueueing them would error forever and **stall the entire outbox** (the flusher stops when nothing progresses). This is called out explicitly in the code comments.

- **`cal_block` writes must depend on their task.** When adding a new write path that creates a block referencing a task, preserve `dependsOn = task.id` (UUID-guarded). Skipping it can let a block reach the server before its task (FK/RLS failure).

- **Collection item edits go through a `Mutex` + re-read.** `AppViewModel.mutateCollection` serializes mutations and re-resolves the latest collection from Room (`store.collections().first()`) before writing, because a collection is one JSONB row carrying all its items — rapid fast-add/pin bursts would otherwise persist a stale snapshot and drop items. If you add a new collection mutation, route it through `mutateCollection`, don't write the row directly.

- **`updatedAt`/timestamps are the conflict resolver.** `WriteThrough` passes a timestamp into `store.upsert` (e.g. `t.updatedAt`); most write actions in `AppViewModel` bump `updatedAt = isoNow()`. Forgetting to bump it can make a server echo "win" over your local edit. Use the `isoNow()` helper (UTC, millisecond, `…'Z'`) — it matches the web/iOS ISO format.

- **`:core` must stay pure.** If you add an Android type (`Context`, `android.*`, a Compose import) into `:core`, the `kotlin.jvm` build breaks and you lose the headless tests. Keep platform code in `:app`/`:design` and inject seams (like the Google-push lambdas) from above.

- **No Room migrations needed for model fields, but enums/JSON shape still matter.** Adding a field to a `@Serializable` model is migration-free (it's just JSON, and `ignoreUnknownKeys = true` tolerates drift). But removing/renaming a field, or changing an enum, must stay consistent with `DbRowCodec` and the server column shape, or hydrate/realtime decode will silently drop rows (`observe` uses `mapNotNull { runCatching { decode }.getOrNull() }`).

### How to extend: adding a new synced collection ("reminders" table example)

Suppose you want a new synced entity. Follow the existing entity pattern end-to-end — the modules tell you the order:

1. **`:core` — model + logic.** Add `Reminder` to `core/model/Models.kt` (`@Serializable`, with `id`, timestamps, etc.). Put any derivation rules in `core/logic/`. Write a JVM unit test.

2. **`:data` — register the table name.** Add `const val REMINDERS = "reminders"` to `Tables` in `db/Entities.kt` (must match the Supabase table name). Add a `reminders(): Flow<List<Reminder>>` reader to `LocalStore` via the `observe(...)` helper:
   ```kotlin
   fun reminders(): Flow<List<Reminder>> = observe(Tables.REMINDERS, Reminder.serializer())
   ```
   No Room migration, no DAO change — `records`/`outbox` already handle it generically.

3. **`:sync` — codec + write/hydrate/realtime.**
   - `DbRowCodec`: add `encodeReminder` / `decodeReminder` (domain ↔ server JSON shape).
   - `WriteThrough`: add `upsertReminder(r)` (`store.upsert` + `enqueue("reminders", r.id, "upsert", DbRowCodec.encodeReminder(r))`) and `deleteReminder(id)` via `deleteLocalAndEnqueue`.
   - `Hydrator.hydrate()`: add `replace(Tables.REMINDERS, Reminder.serializer(), { it.id }) { DbRowCodec.decodeReminder(it) }`.
   - `RealtimeMirror.subscribeAll()`: add a `subscribe(Tables.REMINDERS, userId, onUpsert = …, onDelete = …)` block.

4. **`:app` — expose on the ViewModel + UI.** In `AppViewModel`: `val reminders = sf(store.reminders())` and write actions `fun addReminder(...) = launchWrite { write?.upsertReminder(...) }` (build the model with `newUuid()` + `isoNow()`, bump `updatedAt` on edits). Screens read `vm.reminders` and compose with `:core`.

5. **Backend.** Create the `reminders` table in Supabase with matching columns, RLS policies scoping by `user_id`, and enable Realtime on it. (The web repo's migrations are the canonical reference for column shapes/policies.)

That five-step shape — **model in `:core` → name in `:data` → codec+write+hydrate+realtime in `:sync` → flow+action+screen in `:app` → table+RLS in Supabase** — is the spine of every synced feature in this app. If you can trace `TaskItem` through all five layers, you understand the architecture.
