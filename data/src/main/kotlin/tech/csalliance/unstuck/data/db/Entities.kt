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
 *  task), so we never reference a row the server hasn't seen. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val op: String, // "upsert" | "delete"
    val recordTable: String,
    val recordId: String,
    val payload: String?,
    val dependsOn: String? = null,
    val createdAt: Long,
)

/** Device-local live focus session — single row (id = 0). */
@Entity(tableName = "live_session")
data class LiveSessionEntity(
    @PrimaryKey val id: Int = 0,
    val data: String,
)
