package tech.csalliance.unstuck.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Logical table names — match the Supabase table names so the sync layer can
// pass them straight through. Used as the `table` discriminator on records +
// outbox ops.
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
    /** The assistant's memory (migration 050). Rows with `active=false` are
     *  soft-delete tombstones and stay in the store (every read filters them). */
    const val PROFILE_FACTS = "profile_facts"
    /** "Unstuck calls you" bookings (migrations 051/053/058). A READ-ONLY mirror:
     *  hydrate replaces it and realtime keeps it live so `get_calls` / the task
     *  editor read locally; every write goes DIRECT to PostgREST (compare-and-set
     *  on status), never through the outbox — 053's BEFORE UPDATE guard owns
     *  status/snooze_until and a queued whole-row upsert would fight it. */
    const val CALL_REQUESTS = "call_requests"
}

/** One synced row, stored as a JSON blob of the domain model. Composite key
 *  (table, id). `updatedAt` is denormalised for cheap ordering/debug. */
@Entity(tableName = "records", primaryKeys = ["tableName", "id"])
data class RecordEntity(
    val tableName: String,
    val id: String,
    val data: String,
    val updatedAt: String? = null,
)

/** A pending write to flush to the server, FIFO by `seq`. `payload` is the
 *  encoded row JSON for an upsert (null for a delete). `dependsOn` is the id
 *  of a parent record whose op must flush first (a cal_block waits for its
 *  task), so we never reference a row the server hasn't seen. `base` (schema
 *  v2) is the server-shaped row JSON the local edit STARTED from — the last
 *  state this device had synced for that row — so the sync engine can 3-way
 *  merge a queued edit against a server row another device changed meanwhile
 *  instead of dropping the whole local op (row-level LWW). Null for a create
 *  (no prior row) or for ops queued by a pre-v2 build. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val op: String, // "upsert" | "delete" | "rpc" | "insert" | "insert_or_retime" (a mint, stage 2)
    val recordTable: String,
    val recordId: String,
    val payload: String?,
    val dependsOn: String? = null,
    val createdAt: Long,
    val base: String? = null,
)

/** An outbox op PARKED at sign-out because the bounded drain couldn't land it
 *  (offline sign-out). Keyed by the user it belongs to: the sign-out cache wipe
 *  clears `outbox` but never this table, and the next sign-in of the SAME user
 *  moves these back into the outbox ahead of the first flush — an un-pushed
 *  edit is never silently discarded. A different account never sees them. */
@Entity(tableName = "parked_outbox")
data class ParkedOutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val userId: String,
    val op: String,
    val recordTable: String,
    val recordId: String,
    val payload: String?,
    val dependsOn: String? = null,
    val createdAt: Long,
    val base: String? = null,
)

/** Device-local live focus session — single row (id = 0). */
@Entity(tableName = "live_session")
data class LiveSessionEntity(
    @PrimaryKey val id: Int = 0,
    val data: String,
)
