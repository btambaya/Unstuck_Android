package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// InsertMirrorGate — rule G of the deterministic occurrence ids (stage 2, "same
// id for same day", Ahmad 2026-09-23; deterministic-occurrence-ids.md §3 c-bis;
// port of iOS build 85's InsertMirrorGate).
//
// A minted occurrence is written insert-if-absent. If another device already has
// that id, the server ignores this device's insert and keeps its own row. Pushing
// this device's copy to Google before knowing that would create a second Google
// event, and the stamp that follows (the new event id written back and queued)
// would overwrite the other device's row — hazard c again, through the back door.
// So:
//  • while a row has an unresolved insert-family op (queued in the outbox, or
//    being sent by the flusher), no Google push for that row goes out; the push
//    only records "mirror wanted";
//  • once the insert resolves as inserted or retimed, the wanted mirror runs
//    ONCE, from the fresh local row (Ahmad's "every day, everywhere": every
//    minted day is mirrored, but only after a confirmed insert);
//  • an ignored insert is never mirrored (the next pull brings the server's row,
//    which carries its own mapping).
//
// The flusher brackets every insert-family send with [begin] / [resolve], and
// resolve runs only after the op is dequeued, so from the moment the op is
// queued until resolve either the outbox holds it or begin has marked it: a push
// request can never slip through the gap. "Wanted" is in memory only: an app
// restart loses it, and the block then gets its event on its next edit (the
// spec's accepted residual).
//
// A confirmed push can find its row MISSING: the realtime DELETE echo of this
// device's own delete of the row's earlier incarnation ("Never" then "Daily",
// hazard d) is applied unconditionally and can land after the re-mint, just
// before the INSERT echo brings the row back. The push then waits for the row
// ([awaitRow]): the realtime INSERT/UPDATE ([rowLanded]) or the next successful
// cal_blocks pull ([sweepLandedRows]) releases it through [onAwaitedRowLanded].
//
// Every method takes one coroutine Mutex, so the outbox read in [requestMirror]
// and the flusher's [resolve] can't interleave.

/** How an insert-family op resolved on the server. */
enum class InsertOutcome {
    /** The server had no row with that id: this device's row was inserted. */
    INSERTED,
    /** Rule H: the insert was ignored and that day's OPEN occurrence was moved
     *  to the asked start and length. */
    RETIMED,
    /** The server kept its own row untouched. */
    IGNORED;

    /** The server now holds this device's intent for the day. */
    val confirmed: Boolean get() = this != IGNORED
}

/** One resolved insert-family op, reported after it is dequeued. [mirrorWanted]:
 *  a Google push of the row waited on this answer and the answer confirms the
 *  insert (rule G) — the listener mirrors the row once. */
data class InsertResolution(
    val table: String,
    val rowId: String,
    val outcome: InsertOutcome,
    val mirrorWanted: Boolean,
)

class InsertMirrorGate(private val store: LocalStore, private val table: String = Tables.CAL_BLOCKS) {
    private val lock = Mutex()
    /** Rows the flusher is sending an insert-family op for right now. */
    private val inFlight = HashSet<String>()
    /** Rows whose Google push was deferred until their insert resolves. */
    private val wanted = HashSet<String>()
    /** Confirmed mints whose push found no local row (see the header). */
    private val awaitingRow = HashSet<String>()

    /** Where a push that waited for its row goes once the row is back. */
    @Volatile var onAwaitedRowLanded: ((String) -> Unit)? = null

    /** The flusher is about to send an insert-family op for [rowId]. */
    suspend fun begin(rowId: String) = lock.withLock { inFlight.add(rowId); Unit }

    /** The send failed (transient, refused, malformed): the op is still queued,
     *  so the outbox keeps the row unresolved. */
    suspend fun abandon(rowId: String) = lock.withLock { inFlight.remove(rowId); Unit }

    /** The op resolved and has been dequeued. True iff a push was deferred for
     *  this row AND the outcome confirms the insert — the caller then mirrors it
     *  once, from the fresh local row. The wanted mark is consumed either way (an
     *  ignored insert is never mirrored). */
    suspend fun resolve(rowId: String, outcome: InsertOutcome): Boolean = lock.withLock {
        inFlight.remove(rowId)
        wanted.remove(rowId) && outcome.confirmed
    }

    /** A Google push of [rowId]: true = push now; false = the row's insert is
     *  unresolved, so the push is deferred ("mirror wanted" recorded). */
    suspend fun requestMirror(rowId: String): Boolean = lock.withLock {
        if (!unresolvedLocked(rowId)) return@withLock true
        wanted.add(rowId)
        false
    }

    /** A MINT is about to be written and wants its push once the insert
     *  resolves. Recorded BEFORE the op is queued (the iOS build 85 fix), so a
     *  flush that resolves the insert before the writer gets back can't slip past
     *  the gate (it finds the mark and consumes it). True when the mark is new —
     *  the caller undoes it with [forget] if nothing was queued. */
    suspend fun expectMirror(rowId: String): Boolean = lock.withLock { wanted.add(rowId) }

    /** True while an insert-family op for [rowId] is queued or being sent. */
    suspend fun isUnresolved(rowId: String): Boolean = lock.withLock { unresolvedLocked(rowId) }

    /** The row was deleted (its queued insert cancelled with it), or its mint
     *  queued nothing: no mirror is owed. */
    suspend fun forget(rowId: String) = lock.withLock {
        wanted.remove(rowId)
        awaitingRow.remove(rowId)
        Unit
    }

    /** A CONFIRMED insert's push found no local row. True = the row is back
     *  already (push now); false = the push waits for [rowLanded] or
     *  [sweepLandedRows]. Marked before the store is read, so a row landing in
     *  between is never missed. */
    suspend fun awaitRow(rowId: String): Boolean = lock.withLock {
        awaitingRow.add(rowId)
        if (!rowExists(rowId)) return@withLock false
        awaitingRow.remove(rowId)
        true
    }

    /** A cal_blocks row was just written from the server (a realtime INSERT or
     *  UPDATE). Called AFTER the write, so with [awaitRow]'s mark-then-read
     *  either side sees the other. */
    suspend fun rowLanded(rowId: String) {
        val released = lock.withLock { awaitingRow.remove(rowId) }
        if (released) onAwaitedRowLanded?.invoke(rowId)
    }

    /** A cal_blocks pull succeeded: release every awaited row it brought back. */
    suspend fun sweepLandedRows() {
        val landed = lock.withLock {
            if (awaitingRow.isEmpty()) return@withLock emptyList()
            val back = awaitingRow.filter { rowExists(it) }
            awaitingRow.removeAll(back.toSet())
            back
        }
        val hook = onAwaitedRowLanded ?: return
        for (id in landed) hook(id)
    }

    /** Sign-out: nothing of the previous account is owed a mirror. */
    suspend fun reset() = lock.withLock {
        inFlight.clear(); wanted.clear(); awaitingRow.clear()
    }

    /** Test seam: is a mirror waiting on this row's insert? */
    suspend fun isMirrorWanted(rowId: String): Boolean = lock.withLock { rowId in wanted }

    /** Test seam: is a confirmed push waiting for this row to come back? */
    suspend fun isAwaitingRow(rowId: String): Boolean = lock.withLock { rowId in awaitingRow }

    private suspend fun rowExists(rowId: String): Boolean = store.getOne(table, rowId, CalBlock.serializer()) != null

    private suspend fun unresolvedLocked(rowId: String): Boolean {
        if (rowId in inFlight) return true
        // A failed read counts as unresolved: deferring a push is recoverable (the
        // next edit pushes), a push over another device's row is not.
        return try {
            store.hasInsertFamilyOp(table, rowId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            true
        }
    }
}
