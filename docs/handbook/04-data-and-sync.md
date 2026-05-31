## Persistence & the sync engine (`:data` + `:sync`)

This is the heart of the Unstuck Android app. Everything the UI shows is read from a local Room database; everything the user does is written locally *first*, then mirrored to Supabase in the background. There is no "loading spinner while we fetch" model — the app is **offline-first** and **local-canonical for reads**, **server-canonical for hydration**. This chapter walks the two modules that implement that: `:data` (the on-device store) and `:sync` (the engine that keeps it in step with Postgres + Realtime + Google Calendar).

Both modules are deliberate, line-for-line **ports of the iOS Swift code** (and, one level up, of the web app's `lib/sync/*` + `lib/use-*.ts`). When you're unsure why something is shaped a certain way, the answer is almost always "because the web/iOS version does it that way, and all three clients must produce byte-compatible rows." Keep `/Users/ahmadtambaya/Desktop/projects/unstuck` (web) open alongside — file headers cite their web counterparts (`bootstrap-listener.tsx`, `hydrate.ts`, `bridge.ts`, `use-tasks.ts`).

### The mental model in one diagram

```
        UI (Compose)                    SyncCoordinator (orchestrator)
            │                                   │ observes auth
   reads ───┤ Flows                             │
            ▼                                   ▼
        LocalStore ──────────────────────► WriteThrough ──► OutboxFlusher ─┐
            │  (typed facade)              (optimistic       (drains FIFO)  │
            ▼                               local write)                    │
     Room: records / outbox / live_session                                 ▼
            ▲                                                          SyncGateway
            │ replace()                                              (PostgREST CRUD)
            │                                                              │
        Hydrator ◄──── fetchAll ────────────────────────────────────────┤
            ▲                                                              │
        RealtimeMirror ◄──── postgres_changes ─────────────────────────── Supabase
```

- **Reads** never touch the network. The UI collects `Flow`s off `LocalStore`, which are Room queries over the `records` table.
- **Writes** go through `WriteThrough`: write the local row (UI updates instantly via the Flow), then append an op to the `outbox`.
- **`OutboxFlusher`** drains the outbox to Supabase via `SyncGateway` whenever we're online/authenticated.
- **`Hydrator`** does a full server pull and *replaces* local tables (server wins on hydrate).
- **`RealtimeMirror`** keeps local in step live while the app is open.
- **`SyncCoordinator`** ties it all to auth-state changes and owns the Google Calendar two-way sync + notification hooks.

---

### `:data` — the Room store

All of `:data` lives under `data/src/main/kotlin/tech/csalliance/unstuck/data/`. Its `build.gradle.kts` pulls in only Room (`room.runtime`, `room.ktx`, `room.compiler` via KSP) and `kotlinx.serialization.json` — no Supabase, no networking. `:data` is intentionally backend-agnostic.

#### The single-JSON `records` table design

The defining design choice: **we do not have one Room table per domain entity.** Instead there is a single generic `records` table, and every synced row — a task, a cal_block, a tag, a life_area, a calendar_connection — is stored as a **JSON blob of its `:core` domain model**, keyed by `(tableName, id)`.

`data/src/main/kotlin/tech/csalliance/unstuck/data/db/Entities.kt`:

```kotlin
@Entity(tableName = "records", primaryKeys = ["tableName", "id"])
data class RecordEntity(
    val tableName: String,   // logical table: "tasks", "cal_blocks", ...
    val id: String,          // the row id (UUID, or g_<googleId> for external blocks)
    val data: String,        // the domain model, serialized as JSON
    val updatedAt: String? = null,  // denormalised, for cheap ordering/debug
)
```

The logical table names are constants in the same file, and they **match the Supabase table names exactly** so the sync layer can pass them straight through with no mapping:

```kotlin
object Tables {
    const val TASKS = "tasks"
    const val CAL_BLOCKS = "cal_blocks"
    const val SESSIONS = "sessions"
    const val CAPTURES = "captures"
    const val REASON_LOGS = "reason_logs"
    const val COLLECTIONS = "collections"
    const val TAGS = "tags"
    const val LIFE_AREAS = "life_areas"
    const val CALENDAR_CONNECTIONS = "calendar_connections"
}
```

**Why JSON-blob-per-row instead of typed columns?** Because the app's read path is identical to web/iOS: load the *whole* collection of each entity into memory and compose it with pure `:core` logic (`visibleTasks`, `pickStartNext`, etc.). We never run a `WHERE done = false` against the DB; we filter in Kotlin. So the DB doesn't need queryable columns — it needs to faithfully round-trip the exact `:core` model. A JSON blob does that with zero schema-migration cost when a model gains a field (`ignoreUnknownKeys = true` handles forward/backward skew). The `updatedAt` column is *denormalised out of the blob* purely for cheap ordering/debugging, never as a source of truth.

There are two more entities, both special-cased:

```kotlin
// The offline write-ahead log. FIFO by autoincrement seq.
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val op: String,            // "upsert" | "delete"
    val recordTable: String,
    val recordId: String,
    val payload: String?,      // encoded server-row JSON for upsert; null for delete
    val dependsOn: String? = null,  // parent row id that must flush first
    val createdAt: Long,
)

// Device-local focus session. Exactly one row (id = 0). NOT synced.
@Entity(tableName = "live_session")
data class LiveSessionEntity(@PrimaryKey val id: Int = 0, val data: String)
```

`live_session` is the one piece of state that is deliberately **device-local and never synced** — it's the currently-running focus timer. It's a singleton row (`id = 0`), and `clearAll()` wipes it along with everything else on sign-out. (The completed `Session` rows it produces *are* synced; the in-flight live session is not.)

#### The DAOs

`data/src/main/kotlin/tech/csalliance/unstuck/data/db/Daos.kt` has three DAOs. The notable ones:

**`RecordDao`** exposes `observe(table)` (a `Flow<List<RecordEntity>>` — this is what drives the UI), `get(table)` (a one-shot snapshot), `upsert`, `deleteById`, `clearTable`, `clearAll`, and the hydrate primitive:

```kotlin
@Transaction
suspend fun replaceTable(table: String, rows: List<RecordEntity>, preserveIdsPrefix: String? = null) {
    if (preserveIdsPrefix == null) clearTable(table)
    else deleteNotPrefixed(table, "$preserveIdsPrefix%")
    upsert(rows)
}
```

This is **replace-per-table** semantics: wipe the table, insert the fetched set. The `preserveIdsPrefix` escape hatch keeps local rows whose id starts with a prefix — used for cal_blocks so that locally-cached Google `g_…` external blocks survive a hydrate (the server doesn't own them). Note it's a single `@Transaction`, so observers see the swap atomically — no flash of an empty list.

**`OutboxDao`**: `all()` ordered by `seq ASC` (FIFO), a reactive `count()` (for a "pending sync" UI badge), `enqueue` (insert with `OnConflictStrategy.REPLACE`), `remove(seq)`, `clear()`.

**`LiveSessionDao`**: `observe()`/`get()`/`set()`/`clear()` against the single `id = 0` row.

#### `LocalStore` — the typed facade

`data/src/main/kotlin/tech/csalliance/unstuck/data/LocalStore.kt` is the only thing the rest of the app (and `:sync`) talks to. It wraps the DAOs and the JSON codec so callers work in `:core` domain types, never in `RecordEntity`.

Its `Json` is configured `ignoreUnknownKeys = true`, `encodeDefaults = true`, `isLenient = true` — forgiving on decode (forward-compat), explicit on encode.

The five method families to know:

1. **`observe`** — one typed Flow per collection:
   ```kotlin
   private fun <T> observe(table: String, ser: KSerializer<T>): Flow<List<T>> =
       records.observe(table).map { rows ->
           rows.mapNotNull { runCatching { json.decodeFromString(ser, it.data) }.getOrNull() }
       }
   fun tasks(): Flow<List<TaskItem>> = observe(Tables.TASKS, TaskItem.serializer())
   fun blocks(): Flow<List<CalBlock>> = observe(Tables.CAL_BLOCKS, CalBlock.serializer())
   // … sessions(), captures(), reasonLogs(), collections(), tags(), lifeAreas(), connections()
   ```
   **Pitfall worth internalizing:** decode failures are swallowed via `runCatching{…}.getOrNull()` + `mapNotNull`. A corrupt/incompatible blob silently disappears from the collection rather than crashing — robust, but it means a model-shape bug can make rows "vanish" with no error. If rows are mysteriously missing, suspect a decode mismatch here.

2. **`snapshot(table, ser)`** — one-shot non-reactive read (used by the Hydrator to read the local external blocks before replacing).

3. **`upsert(table, model, ser, id, updatedAt)`** + **`entity(...)`** — encode a model to a `RecordEntity` and upsert it. This is the local-write primitive `WriteThrough` and `RealtimeMirror` call.

4. **`replace(table, items, ser, id, updatedAt, preservePrefix)`** — the hydrate primitive; encodes a list and calls `replaceTable`.

5. **`delete`**, **`clearAll`**, the **outbox** passthroughs (`enqueue`/`pending`/`dequeue`/`pendingCount`), and the **live session** accessors (`liveSession()` Flow, `getLiveSession()`, `setLiveSession(null)` clears).

#### DB build & migrations — read this twice

`data/src/main/kotlin/tech/csalliance/unstuck/data/db/UnstuckDatabase.kt`:

```kotlin
@Database(entities = [RecordEntity::class, OutboxEntity::class, LiveSessionEntity::class],
          version = 1, exportSchema = false)
abstract class UnstuckDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun outbox(): OutboxDao
    abstract fun liveSession(): LiveSessionDao
    companion object {
        fun build(context: Context): UnstuckDatabase =
            Room.databaseBuilder(context, UnstuckDatabase::class.java, "unstuck.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
```

**Gotcha — `fallbackToDestructiveMigration()`.** There are no Room migrations and `exportSchema = false`. If you ever change the *Room schema* (add/rename a column on one of these three entities, add a fourth entity), bumping `version` will **drop and recreate the database**, wiping the local cache. That is *safe by design here* because the cache is rebuildable: on next sign-in the `Hydrator` re-pulls everything server-canonical, and the only truly device-local state is `live_session` (an in-flight timer — acceptable to lose on a schema change). The big consequence: **adding a new synced entity does NOT require a Room migration**, because new entities are just new `tableName` values inside the existing `records` table. You almost never touch this file.

---

### `:sync` — the engine

`sync/src/main/kotlin/tech/csalliance/unstuck/sync/`. This module depends on `:data`, `:core`, and supabase-kt 3.x (`postgrest`, `auth`, `realtime`, `functions`) over the `ktor.client.okhttp` engine. The networked pieces are integration-tested against the real client; the *decision* logic (`SyncDecision`, `DbRowCodec`, the outbox dependency filter) is pure and JVM-unit-tested.

#### `SupabaseClientProvider` + `SyncConfig`

`SupabaseClientProvider.kt` builds and holds the one shared `SupabaseClient`:

```kotlin
data class SyncConfig(val url: String, val anonKey: String,
                      val authScheme: String = "unstuck", val authHost: String = "auth-callback")

class SupabaseClientProvider(config: SyncConfig) {
    val client: SupabaseClient = createSupabaseClient(config.url, config.anonKey) {
        install(Auth) {
            flowType = FlowType.PKCE              // required for OAuth / magic-link deep-link callback
            scheme = config.authScheme; host = config.authHost   // unstuck://auth-callback
            autoLoadFromStorage = true; autoSaveToStorage = true // persists the session
        }
        install(Postgrest); install(Realtime); install(Functions)
    }
}
```

The URL and anon key are injected from `BuildConfig` (sourced from `secrets.properties`, kept out of git). `AppGraph` (`app/.../AppGraph.kt`) is the manual DI container that wires this up once per process: it builds the `UnstuckDatabase`, the `LocalStore`, the `SupabaseClientProvider` (only if `BuildConfig.SUPABASE_ANON_KEY` is non-empty — otherwise the app shows a setup screen), and the `SyncCoordinator`. `AppGraph.start()` → `coordinator.start()`.

#### `DbRowCodec` — the snake_case ⇆ camelCase boundary (critical)

`DbRowCodec.kt` is where the local `:core` models become the **exact Postgres row shape** and back. This is the single most error-prone seam in the whole engine because three clients (web, iOS, Android) must all emit identical rows. Read the file header — it spells out the two non-obvious rules:

**Rule 1 — top-level columns are snake_case; JSONB blobs stay camelCase.** Each entity has a `@Serializable internal data class …Row` DTO. Top-level columns get `@SerialName("snake_case")`. Crucially, there is **no global `SnakeCase` naming strategy**. So nested types (e.g. a `Recurrence` with `daysOfWeek`, the `objectives`/`comments` lists) serialize with their *own* `:core` camelCase field names — exactly what the JSONB columns in Postgres expect. If you ever set a class-wide snake_case strategy here, you'd silently corrupt every JSONB blob (`daysOfWeek` → `days_of_week`) and break cross-client compatibility.

```kotlin
@Serializable internal data class TaskRow(
    val id: String, val name: String,
    @SerialName("estimate_min") val estimateMin: Int,
    @SerialName("move_count") val moveCount: Int,
    val objectives: List<Objective>? = emptyList(),  // JSONB, stays camelCase inside
    val recurrence: Recurrence?,                       // JSONB, daysOfWeek survives
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // …
) {
    constructor(t: TaskItem) : this(/* maps model → row, applying web defaults */
        t.moveCount ?: 0, t.tags ?: emptyList(), t.later ?: false, /* … */)
    fun toModel() = TaskItem(/* row → model */)
}
```

**Rule 2 — explicit nulls clear fields, with one exception.** `rowJson` uses `explicitNulls = true`, so a nullable optional that's now `null` serializes as an explicit JSON `null`, which an upsert writes — *clearing* a previously-set column. The lone exception, matching the web writer, is `reason_logs.duration_sec`: it's stripped post-encode when null so an upsert never clobbers a server-computed value:

```kotlin
fun encodeReasonLog(r: ReasonLog): JsonObject {
    val full = obj(ReasonLogRow.serializer(), ReasonLogRow(r))
    return if (full["duration_sec"] == JsonNull) JsonObject(full - "duration_sec") else full
}
```

Two more conventions to remember:
- **Encoders return `JsonObject`, not strings.** This lets `SyncGateway` inject `user_id` while preserving explicit JSON nulls. (If encoders returned strings, you'd have to re-parse to add `user_id`.)
- **FK columns drop to `null` when not a valid UUID** via `uuidOrNull(...)` (`core/.../logic/Uuid.kt`). E.g. a cal_block's `taskId` that points at a not-yet-persisted local id becomes a null FK rather than a constraint violation. `user_id` is **never** in these DTOs — the gateway attaches it.

#### `SyncGateway` — the PostgREST primitive

`SyncGateway.kt` is the thin CRUD layer everything builds on. It works in `JsonObject` row shapes (so explicit-null semantics from `DbRowCodec` survive end-to-end):

```kotlin
suspend fun fetchAll(table: String): List<JsonObject> =
    client.from(table).select(Columns.ALL).decodeList<JsonObject>()   // RLS auto-scopes to the user

suspend fun upsert(table: String, row: JsonObject, userId: String) =
    client.from(table).upsert(withUserId(row, userId)) { onConflict = "id" }

suspend fun delete(table: String, id: String) =
    client.from(table).delete { filter { eq("id", id) } }

private fun withUserId(row, userId) = JsonObject(row + ("user_id" to JsonPrimitive(userId)))
```

Reads don't filter by user — **Row-Level Security on Postgres scopes them automatically**. Writes inject `user_id` exactly the way the web bridge does (`{ ...row, user_id }`).

#### `WriteThrough` — optimistic local write + outbox enqueue

`WriteThrough.kt` is what the UI calls to mutate data. Every method does the same two-step dance: **write local first** (UI updates immediately because the Room Flow re-emits), then **enqueue a server op**. Example:

```kotlin
suspend fun upsertTask(t: TaskItem) {
    store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)   // local, instant
    enqueue("tasks", t.id, "upsert", DbRowCodec.encodeTask(t).toString())     // queued for server
}
```

`enqueue` appends an `OutboxEntity` with `createdAt = nowMillis()` (an injectable seam for deterministic tests). Deletes follow the same pattern via `deleteLocalAndEnqueue` (delete local, enqueue a `"delete"` op with null payload).

Three things make `WriteThrough` more than a passthrough:

**(a) `dependsOn` ordering.** A cal_block references a task. If you create a task and schedule it in one breath, the cal_block op must not hit the server before the task op (FK / referential safety). So `upsertCalBlock` sets `dependsOn = task.id` (only when that id is a real UUID):

```kotlin
val dependsOn = b.taskId?.let { if (isUuid(it)) it else null }  // wait for parent task op
enqueue("cal_blocks", b.id, "upsert", DbRowCodec.encodeCalBlock(b).toString(), dependsOn)
```

**(b) Calendar push hooks.** `WriteThrough` holds two injectable suspend seams, `pushCalBlock` and `pushCalBlockDelete`, wired by `SyncCoordinator` in its `init {}`. This keeps `:data`/`:core` Google-agnostic. On a task-block upsert, after enqueueing the Supabase op, it best-effort mirrors the block to Google; if Google mints a new event id, it **re-stamps the block** with `externalEventId` and enqueues a *second* upsert so later edits PATCH the same event and a later pull won't duplicate it:

```kotlin
val push = pushCalBlock ?: return
val eventId = push(b)
if (!eventId.isNullOrBlank() && eventId != b.externalEventId) {
    val stamped = b.copy(externalEventId = eventId)
    store.upsert(Tables.CAL_BLOCKS, stamped, CalBlock.serializer(), stamped.id)
    enqueue("cal_blocks", stamped.id, "upsert", DbRowCodec.encodeCalBlock(stamped).toString(), dependsOn)
}
```

**(c) External `g_` blocks are read-only.** `upsertCalBlock` early-returns (no enqueue, no Google push) for `kind == EXTERNAL` or `id.startsWith("g_")`. These are Google events mirrored *into* our store; their ids aren't UUIDs and their shape isn't ours, so pushing them to our `cal_blocks` table would fail forever and **stall the entire outbox** (see the flusher's stop condition below). This is a load-bearing guard.

> **Pitfall:** the notification/recap calls (session recap, paused check-in) are *not* in `WriteThrough` — they're separate clients (`NotificationsClient`) invoked from the focus/UI layer. `WriteThrough` is strictly about persisting domain rows + the Google mirror.

#### `OutboxFlusher` — draining the queue, FIFO + dependency-aware

`OutboxFlusher.kt` drains pending ops to Supabase. The loop is small but the semantics matter:

```kotlin
suspend fun flush(userId: String) {
    while (true) {
        val all = store.pending()
        if (all.isEmpty()) break
        val pendingIds = all.map { it.recordId }.toSet()
        // hold back an op whose dependsOn target still has a pending op
        val flushable = all.filter { it.dependsOn == null || it.dependsOn !in pendingIds }
        if (flushable.isEmpty()) break
        var progressed = false
        for (op in flushable) {
            val ok = runCatching { apply(op, userId) }.onFailure { /* log */ }.isSuccess
            if (ok) { store.dequeue(op.seq); progressed = true }
        }
        if (!progressed) break   // every remaining op errored — stop, retry on next reconnect/sign-in
    }
    // apply(): "delete" → gateway.delete; else → gateway.upsert(payload JSON, userId)
}
```

Key behaviors:
- **FIFO** within the flushable set (`pending()` is `ORDER BY seq ASC`).
- **Dependency gating:** an op waits while its `dependsOn` row still has a pending op. Once the parent flushes (and dequeues), the child becomes flushable on the next loop turn.
- **Stop-on-no-progress:** if a whole pass fails (offline, or a permanently-bad op), `progressed` stays false and the loop exits. Ops stay queued and are retried on the next sign-in / reconnect / 30-minute WorkManager tick. There is no exponential backoff and **no poison-pill eviction** — a permanently-failing op (e.g. an RLS-rejected row, or that `g_` block we carefully prevent enqueueing) would block everything behind it forever. That's exactly why the `g_`/external guard in `WriteThrough` exists.

#### `Hydrator` — full server pull, replace-per-table

`Hydrator.kt` pulls every synced table and replaces local (server-canonical), with **per-table error isolation** — a table whose fetch fails is left intact (mirrors `hydrate.ts`'s `if (res.ok) replace(...)`):

```kotlin
suspend fun hydrate() {
    replace(Tables.TASKS, TaskItem.serializer(), { it.id }, { it.updatedAt }) { DbRowCodec.decodeTask(it) }
    replace(Tables.SESSIONS, …) { DbRowCodec.decodeSession(it) }
    // … captures, reason_logs, collections, tags, life_areas, calendar_connections …
    hydrateCalBlocks()   // special: preserves local external g_ blocks
}

private suspend fun <T> replace(table, ser, id, updatedAt, decode) {
    runCatching {
        val models = gateway.fetchAll(table).map(decode)   // JsonObject → :core model
        store.replace(table, models, ser, id, updatedAt)    // wipe + insert, one txn
    }.onFailure { /* leave local intact */ }
}
```

`hydrateCalBlocks()` is the exception: it fetches remote cal_blocks, snapshots the *local external* blocks, and merges via `SyncDecision.mergeHydratedCalBlocks(remote, localExternal)` (remote wins on id collision; local `g_` blocks are preserved because they never round-trip to Postgres), then replaces. Note `calendar_connections` is hydrated but is **not** subscribed in Realtime (its encrypted creds must never broadcast).

#### `RealtimeMirror` — live updates while open

`RealtimeMirror.kt` opens one `postgres_changes` channel per synced table (named `unstuck_<table>_<userId>`), filtered by `user_id` (RLS enforces server-side; the filter is belt-and-suspenders + reduces traffic). `INSERT`/`UPDATE` → local upsert; `DELETE` → local delete keyed off `oldRecord["id"]`:

```kotlin
is PostgresAction.Insert -> onUpsert(action.record)
is PostgresAction.Update -> onUpsert(action.record)
is PostgresAction.Delete -> action.oldRecord["id"]?.let { onDelete(it.jsonPrimitive.content) }
```

`calendar_connections` is deliberately **not** subscribed (encrypted creds). `subscribeAll` first calls `unsubscribeAll` (cancel jobs, unsubscribe channels) so it's idempotent across re-auth.

> **Echo note:** Realtime will echo back your own writes (the flusher's upsert comes back as an UPDATE). That's harmless here because `store.upsert` is idempotent by `(tableName, id)` and `RealtimeMirror` decodes the same canonical row.

#### `SyncCoordinator` — the orchestrator

`SyncCoordinator.kt` is the brain (port of web's `bootstrap-listener.tsx`). It owns the long-lived `SupabaseClient`, constructs the gateway, and exposes the public API clients (`auth`, `write`, `calendar`, `push`, `notifications`, `preferences`) plus the internal `hydrator`/`flusher`/`realtime`. In `init {}` it wires the Google push hooks into `WriteThrough`.

`start()` launches a coroutine that collects `client.auth.sessionStatus` and routes each status through `handle()`:

```kotlin
is SessionStatus.Authenticated -> {
    val uid = status.session.user?.id ?: return
    val event = when (status.source) {                       // map SDK source → our event
        is SessionSource.SignIn, SignUp, External -> SyncAuthEvent.SIGNED_IN
        is SessionSource.Storage                  -> SyncAuthEvent.INITIAL_SESSION
        is SessionSource.UserChanged, UserIdentitiesChanged -> SyncAuthEvent.USER_UPDATED
        else -> return                                       // Refresh/Unknown → no cache action
    }
    val prev = prefs.getString(KEY_PREV_USER, null)
    if (SyncDecision.shouldWipeCache(event, prev, uid)) store.clearAll()
    prefs.edit().putString(KEY_PREV_USER, uid).apply()
    flusher.flush(uid)          // 1. push offline edits first
    hydrator.hydrate()          // 2. pull server-canonical
    realtime.subscribeAll(uid)  // 3. then mirror live
    runCatching { pullCalendar() } // 4. ingest Google events if connected
}
is SessionStatus.NotAuthenticated -> if (status.isSignOut) {
    realtime.unsubscribeAll(); store.clearAll(); prefs.edit().remove(KEY_PREV_USER).apply()
}
```

The **flush-before-hydrate** ordering is essential: any edits you made while offline must reach the server *before* you overwrite local with the server's view, or you'd lose them.

The **cache-wipe rule** is the pure `SyncDecision.shouldWipeCache` (`SyncDecision.kt`), keyed off `prevUserId` in `SharedPreferences("unstuck.sync")`:
- `SIGNED_IN` → always wipe (fresh sign-in on this device).
- `INITIAL_SESSION` (session loaded from storage at launch) → wipe only if the user changed since last run.
- `USER_UPDATED` (metadata change, same user) → never wipe.

`SyncCoordinator` also owns the **Google Calendar** surface: `beginGoogleConnect()` (returns the authorize URL for a Custom Tab; stores `pendingCalState` for CSRF), `completeGoogleConnect(code, state)` (validates state, calls the Edge Function, then immediately re-hydrates + pulls so the UI flips to "Synced" now), `pullCalendar()` (fetch `[-7d, +30d]`, map via `externalEventToBlock`, skip events we authored, reconcile deletions), `disconnectCalendar(id)`, and the push hooks `pushBlockUpsert`/`pushBlockDelete`. Two embedded gotchas to know:
- **RFC3339 bounds:** Google's `events.list` rejects bare `YYYY-MM-DD` (400, silently zero events). `pullCalendar` sends full instants (`atStartOfDay(zone).toInstant()`), then reconciles with date-only bounds locally.
- **Always push to `"primary"`:** task blocks are inserted/patched/deleted on Google's `"primary"` alias, not on `selectedCalendarIds` (those can include read-only calendars that 403 on insert).

Finally, `syncNow()` is the manual best-effort path (`flush → hydrate → pullCalendar`, no-op when signed out) called by **`SyncWorker`** (`app/.../surface/SyncWorker.kt`), a `CoroutineWorker` scheduled every 30 minutes (`ExistingPeriodicWorkPolicy.KEEP`) — the Android analog of the iOS `BGTaskScheduler` refresh.

#### The API clients

- **`AuthService`** (`AuthService.kt`): thin wrapper over supabase-kt `Auth` — email/password sign-in/up, magic link (OTP), Google OAuth, password reset/change, display-name update, `deleteAccount()` (invokes the `account-delete` Edge Function then signs out), plus accessors `currentUserId`/`currentEmail`/`currentName` and `hasPassword`. Error copy is humanized through `:core`'s `humanizeAuthError`. Returns a sealed `AuthOutcome` (`Ok` / `Error(message)`).
- **`CalendarClient`** (`CalendarClient.kt`): invokes the `calendar-sync` Edge Function (`/authorize`, `/connect`, `/disconnect`, `/connections`, `/events` GET/POST, `/events/{id}` PATCH/DELETE). Note `/connections` returns raw snake_case DB rows (decoded via a private `ConnRow` DTO), while `/connect` returns camelCase — they're intentionally different shapes.
- **`PushClient`** (`Clients.kt`): registers the device's **FCM** token via `register-push-token` (`platform = "android"` so the backend branches FCM vs APNs).
- **`NotificationsClient`** (`Clients.kt`): `sessionRecap(taskName, away)` and `pausedCheckin()` (returns whether a paused-check-in notification is allowed; defaults `false` if the server is unreachable).
- **`PreferencesClient`** (`Clients.kt`): `setAdhdStruggles(userId, struggles)` upserts `user_preferences` (PK'd on `user_id`, so it bypasses the generic `id`-keyed gateway and upserts with `onConflict = "user_id"`).

#### The ktor `Content-Type` gotcha (read before you add an Edge Function call)

Every Edge Function call that has a request **body** must set `contentType(ContentType.Application.Json)` explicitly:

```kotlin
client.functions.invoke("calendar-sync/authorize") {
    method = HttpMethod.Post
    contentType(ContentType.Application.Json)   // ← REQUIRED
    setBody(AuthorizeBody(redirectUri, "google"))
}
```

`supabase-kt`'s `functions.invoke` does **not** infer the body's content type. If you `setBody(...)` without the `contentType(...)` line, ktor sends the body with a missing/default content type and the Deno Edge Function's `await req.json()` fails — the call errors server-side even though the body is valid JSON. Every body-bearing call in `CalendarClient` and `Clients.kt` sets it; GET/DELETE calls that pass only query `parameter(...)`s (e.g. `listConnections`, `deleteEvent`) correctly omit it. Copy the pattern exactly when you add a new client method.

There's a **companion serialization gotcha** in the same area, called out in `CalendarClient`: because `kotlinx.serialization` omits default values when `encodeDefaults` is off, a field defined as `provider: String = "google"` gets **dropped from the request body**, and the server rejected it ("Only google supports authorize"). The fix was to give the request DTOs **no defaults** for fields the server requires — see the `// NOTE:` comment above `AuthorizeBody`. When you define a new request body DTO, do not rely on default values for required fields.

---

### Trace 1 — the full journey of one write (you schedule a task block)

```
UI: user drags a task onto the calendar
 └─► coordinator.write.upsertCalBlock(block)                       [WriteThrough]
      ├─ store.upsert(CAL_BLOCKS, block, …, block.id)              [LocalStore → RecordDao.upsertOne]
      │     └─ records table updated → blocks() Flow re-emits → calendar UI redraws INSTANTLY
      ├─ guard: not EXTERNAL / not g_  → continue
      ├─ dependsOn = block.taskId (only if isUuid)
      ├─ store.enqueue(OutboxEntity(op="upsert", table="cal_blocks", id, payload=encodeCalBlock(block), dependsOn))
      └─ pushCalBlock(block)  [SyncCoordinator.pushBlockUpsert]
            ├─ kind==TASK, googleConn() present →
            ├─ calendar.insertEvent("primary", name, startIso, endIso)  [CalendarClient → Edge Fn]
            └─ returns eventId → block.copy(externalEventId=eventId) → store.upsert + 2nd enqueue

… later (online / sign-in / 30-min worker) …
SyncCoordinator.handle(Authenticated) OR syncNow()
 └─► flusher.flush(uid)                                            [OutboxFlusher]
      ├─ pending() = [task upsert(seq 4), cal_block upsert(seq 5, dependsOn=task)]
      ├─ task op flushable now; cal_block held (dependsOn in pendingIds)
      ├─ apply(task) → gateway.upsert("tasks", payload, uid)       [SyncGateway → PostgREST + user_id]
      ├─ dequeue(task)  → next loop: cal_block now flushable
      └─ apply(cal_block) → gateway.upsert("cal_blocks", …) → dequeue
```

The user sees the block the instant they drop it. The server catches up asynchronously and in the right order.

### Trace 2 — the full journey of one hydrate (cold launch, returning user)

```
App launch → AppGraph.start() → coordinator.start()
 └─ collects client.auth.sessionStatus
      └─ Authenticated(source = Storage)  → event = INITIAL_SESSION
           ├─ shouldWipeCache(INITIAL_SESSION, prev==uid, uid) = false → keep cache
           ├─ flusher.flush(uid)         → push any leftover offline ops first
           ├─ hydrator.hydrate()
           │    ├─ for each table: gateway.fetchAll(t) → List<JsonObject>   [RLS-scoped read]
           │    │      → map(DbRowCodec.decodeX)  → List<:core model>
           │    │      → store.replace(t, models)  → replaceTable (clear+insert, 1 txn)
           │    │      → that table's Flow re-emits with server-canonical data
           │    └─ hydrateCalBlocks(): fetch remote + snapshot local g_ → mergeHydratedCalBlocks → replace
           ├─ realtime.subscribeAll(uid) → one channel per table, live upserts/deletes flow in
           └─ pullCalendar()             → ingest Google events as EXTERNAL g_ blocks
```

If, say, the `tasks` fetch 500s but `tags` succeeds, only `tags` is replaced; the previously-cached `tasks` stay on screen. Per-table isolation means a partial outage degrades gracefully instead of blanking the app.

---

### How to extend — adding a new synced entity end-to-end

Suppose product adds a **`habits`** table (a `Habit` domain model in `:core`). Here's the concrete checklist, in order:

1. **Backend (`unstuck` repo):** create the `habits` table + RLS policies + a Supabase migration, matching the column shape the web/iOS clients use. (Confirm `user_id` column + `id` PK + Realtime publication if you want live mirroring.)

2. **`:core`:** add the `@Serializable data class Habit(...)` domain model (camelCase fields, just like `TaskItem`).

3. **`:data` — `Tables`:** add `const val HABITS = "habits"` to the `Tables` object. **No Room migration, no entity change** — habits are just rows in the existing `records` table.

4. **`:data` — `LocalStore`:** add the reactive read + (it's generic, so just one line):
   ```kotlin
   fun habits(): Flow<List<Habit>> = observe(Tables.HABITS, Habit.serializer())
   ```

5. **`:sync` — `DbRowCodec`:** add a `@Serializable internal data class HabitRow(...)` with `@SerialName("snake_case")` on top-level columns (leave nested types camelCase — do NOT add a global naming strategy), a `constructor(h: Habit)` and `fun toModel()`, then `encodeHabit`/`decodeHabit` in the `DbRowCodec` object. Decide per nullable field whether an explicit null should clear the column (default) or be stripped (rare, like `duration_sec`).

6. **`:sync` — `WriteThrough`:** add `upsertHabit(h)` (local upsert + enqueue with `DbRowCodec.encodeHabit(h).toString()`) and `deleteHabit(id)` (`deleteLocalAndEnqueue`). Add a `dependsOn` only if a habit references another row that must flush first.

7. **`:sync` — `Hydrator.hydrate()`:** add one `replace(Tables.HABITS, Habit.serializer(), { it.id }, …) { DbRowCodec.decodeHabit(it) }` line.

8. **`:sync` — `RealtimeMirror.subscribeAll`:** add a `subscribe(Tables.HABITS, userId, { … upsert }, { … delete })` block so live changes mirror in.

That's the whole loop. You touched no Room schema, wrote no SQL on-device, and the optimistic-write + outbox + hydrate + realtime behavior all comes for free because every layer is generic over `(tableName, id, serializer)`. The only entity-specific code is the `DbRowCodec` DTO (the byte-exact row shape) — which is exactly where it should be, since that's the contract with the server and the other two clients.

---

### Gotchas checklist (keep this handy)

- **`fallbackToDestructiveMigration()`** wipes the cache on any *Room schema* version bump — fine because the cache is rebuildable, but never store unsynced critical state outside `live_session`/server.
- **Silent decode failures** in `LocalStore.observe`: a model mismatch makes rows vanish, not crash. First suspect when data goes missing.
- **The ktor `Content-Type` gotcha:** every body-bearing `functions.invoke` must `contentType(ContentType.Application.Json)`.
- **No-default request DTOs:** `encodeDefaults` is off in the function-call path; required fields with default values get dropped from the body (the `provider="google"` bug).
- **Outbox has no poison-pill eviction:** a permanently-failing op blocks everything behind it (FIFO + dependency gating + stop-on-no-progress). That's why `WriteThrough` hard-guards external `g_`/EXTERNAL blocks from ever enqueuing.
- **Don't add a global snake_case naming strategy in `DbRowCodec`** — it would corrupt every JSONB blob's camelCase keys and break cross-client compatibility.
- **`explicitNulls = true`** means an upsert *clears* a now-null field. Intentional everywhere except `reason_logs.duration_sec`.
- **`calendar_connections` is never realtime-subscribed** (encrypted creds) — refreshed only via `hydrate()`.
- **Flush before hydrate, always** — reversing it loses offline edits.
- **Google push targets `"primary"`, and `pullCalendar` needs RFC3339 instants** (not bare dates), or you get a silent 400 / zero events.

**Where things live (quick map):**
- On-device store, JSON-blob `records` design, DAOs, DB build: `data/src/main/kotlin/tech/csalliance/unstuck/data/` (`LocalStore.kt`, `db/Entities.kt`, `db/Daos.kt`, `db/UnstuckDatabase.kt`).
- Engine: `sync/src/main/kotlin/tech/csalliance/unstuck/sync/` — `SupabaseClientProvider.kt`, `WriteThrough.kt`, `OutboxFlusher.kt`, `Hydrator.kt`, `RealtimeMirror.kt`, `SyncCoordinator.kt`, `SyncGateway.kt`, `DbRowCodec.kt`, `SyncDecision.kt`, `AuthService.kt`, `CalendarClient.kt`, `Clients.kt`.
- Wiring: `app/src/main/kotlin/tech/csalliance/unstuck/AppGraph.kt` (DI) and `.../surface/SyncWorker.kt` (periodic sync).
- Pure helpers reused by the engine: `core/.../logic/Uuid.kt` (`isUuid`/`uuidOrNull`), `core/.../logic/CalBlockKind.kt` (`isExternalBlock`), `core/.../logic/GoogleSyncMapping.kt` (`externalEventToBlock`).
- Cross-reference for the "why": web `lib/sync/bootstrap-listener.tsx`, `hydrate.ts`, `bridge.ts`, `lib/use-tasks.ts` in `/Users/ahmadtambaya/Desktop/projects/unstuck`.
