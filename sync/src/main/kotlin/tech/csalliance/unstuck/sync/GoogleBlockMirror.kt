package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// GoogleBlockMirror — ONE serial worker for every Google Calendar call a task
// block makes (parity with iOS build 85's Google chain; stage 2, deterministic
// occurrence ids, Ahmad 2026-09-23 "every day, everywhere").
//
// With every minted day mirrored once its insert is confirmed (rule G), a series
// edit or a horizon top-up confirms dozens of mints at once. Pushed inline, from
// each confirmation, they ran concurrently into the Calendar API's rate limit,
// and a confirmation push racing an edit's push of the same row inserted two
// events. So:
//  • a push is queued by row id and pushes the row AS IT IS when its turn comes
//    (gone = nothing to push); a row queued twice pushes once;
//  • every queued delete runs before any queued push;
//  • rule G is checked when a push is asked for AND again at its turn: the row
//    may have been deleted and minted again while the push waited, and the new
//    incarnation's insert decides;
//  • the new event id goes back onto the row through a mapping-only stamp, and a
//    push whose stamp is refused — the row went, or was deleted and minted again,
//    during the Google call — deletes the event it just created: left alone it
//    came back on the next Google pull as a meeting beside the block.
// Callers never wait on Google: the local write is committed before a push is
// queued (as iOS saveBlockAwaiting).

/** What [WriteThrough.stampCalBlockMapping] did. */
enum class MappingStamp {
    STAMPED,
    /** The row already carries that mapping (or is a Google mirror). */
    UNCHANGED,
    /** The row is gone (deleted while the Google call ran). */
    GONE,
    /** The row has an unresolved insert-family op — deleted and minted again, or
     *  retimed by a user's mint, during the Google call. Rule G: nothing is
     *  written; the insert's outcome mirrors the row itself once confirmed. */
    INSERT_UNRESOLVED,
}

class GoogleBlockMirror(
    private val scope: CoroutineScope,
    private val store: LocalStore,
    private val gate: InsertMirrorGate,
    private val stamp: suspend (id: String, eventId: String?, connectionId: String?) -> MappingStamp,
) {
    /** The Google call for a task block: PATCH its event, or INSERT one. Returns
     *  the block re-stamped with the mapping when that changed, else null. Wired
     *  by SyncCoordinator (pushBlockUpsert); null = no Google mirroring. */
    @Volatile var push: (suspend (CalBlock) -> CalBlock?)? = null

    /** Delete the Google event a block mapped to (pushBlockDelete). */
    @Volatile var deleteEvent: (suspend (CalBlock) -> Unit)? = null

    private sealed class Call {
        class Delete(val block: CalBlock) : Call()
        class Push(val id: String, val awaitRowIfMissing: Boolean) : Call()
    }

    private val lock = Any()
    private val deletes = ArrayDeque<CalBlock>()
    /** id → a confirmed insert's push (a missing row is waited for, not dropped). */
    private val pushes = LinkedHashMap<String, Boolean>()
    private var worker: Job? = null

    /** An edit of [id] was committed: push it, unless its insert is unresolved —
     *  then rule G records "mirror wanted" and the insert's outcome decides. */
    suspend fun requestPush(id: String) {
        if (push == null) return
        if (!gate.requestMirror(id)) return
        enqueue(id, awaitRowIfMissing = false)
    }

    /** The server confirmed [id]'s insert and a push waited on it: push once,
     *  from the row as it is then. */
    fun queueConfirmed(id: String) = enqueue(id, awaitRowIfMissing = true)

    /** A push that waited for its row: the row is back. */
    fun queueLanded(id: String) = enqueue(id, awaitRowIfMissing = false)

    /** [block] was deleted locally: delete its event (ahead of every queued push)
     *  and drop a push still queued for it — there is nothing left to push. */
    fun requestDelete(block: CalBlock) {
        if (deleteEvent == null) return
        synchronized(lock) {
            pushes.remove(block.id)
            deletes.addLast(block)
        }
        ensureWorker()
    }

    /** A mint just queued an insert for [id]: a push queued before it belonged
     *  to the row before this insert (an earlier incarnation, or the row this
     *  mint retimed), and sent after an IGNORED outcome it would stamp this
     *  device's copy over another device's row. The insert's outcome pushes. */
    fun dropQueuedPush(id: String) = synchronized(lock) { pushes.remove(id); Unit }

    /** Sign-out: nothing queued for the previous account goes out. */
    fun reset() = synchronized(lock) { deletes.clear(); pushes.clear() }

    /** Every queued call has run. A caller whose process may be frozen as soon as
     *  it returns (a notification action's goAsync window, the background sync
     *  worker) waits here, bounded, as it used to wait on the inline push. */
    suspend fun awaitIdle() {
        while (true) {
            val w = synchronized(lock) { worker } ?: return
            w.join()
        }
    }

    private fun enqueue(id: String, awaitRowIfMissing: Boolean) {
        if (push == null) return
        synchronized(lock) { pushes[id] = (pushes[id] ?: false) || awaitRowIfMissing }
        ensureWorker()
    }

    private fun ensureWorker() = synchronized(lock) {
        if (worker?.isActive == true) return@synchronized
        worker = scope.launch { drain() }
    }

    /** The next call, deletes first. Clears the worker when both queues are
     *  empty (under the lock, so a call queued right after starts a new one). */
    private fun next(): Call? = synchronized(lock) {
        deletes.removeFirstOrNull()?.let { return@synchronized Call.Delete(it) }
        val first = pushes.entries.firstOrNull()
        if (first != null) {
            pushes.remove(first.key)
            return@synchronized Call.Push(first.key, first.value)
        }
        worker = null
        null
    }

    private suspend fun drain() {
        while (true) {
            val call = next() ?: return
            try {
                when (call) {
                    is Call.Delete -> deleteEvent?.invoke(call.block)
                    is Call.Push -> pushNow(call.id, call.awaitRowIfMissing)
                }
            } catch (e: CancellationException) {
                synchronized(lock) { worker = null }
                throw e
            } catch (e: Throwable) {
                println("[google] block mirror call failed: $e")
            }
        }
    }

    private suspend fun pushNow(id: String, awaitRowIfMissing: Boolean) {
        val push = push ?: return
        // Rule G again at DISPATCH: the row may have been deleted and minted again
        // while this push waited; the new incarnation's insert decides.
        if (!gate.requestMirror(id)) return
        val fresh = store.getOne(Tables.CAL_BLOCKS, id, CalBlock.serializer())
        if (fresh == null) {
            // A confirmed mint whose row is missing for a moment: its own delete's
            // realtime echo landed after the re-mint (hazard d). Wait for the
            // INSERT echo / the next pull instead of dropping the push.
            if (awaitRowIfMissing && gate.awaitRow(id)) enqueue(id, awaitRowIfMissing = false)
            return
        }
        if (!isTaskBlock(fresh)) return
        // Its delete is the newest queued op (a stale realtime echo put the row back
        // for a moment): the delete's own Google call handles the event.
        if (store.isBeingDeleted(Tables.CAL_BLOCKS, id)) return
        val stamped = push(fresh) ?: return
        if (stamped.externalEventId == fresh.externalEventId && stamped.externalConnectionId == fresh.externalConnectionId) return
        val result = stamp(id, stamped.externalEventId, stamped.externalConnectionId)
        val created = !stamped.externalEventId.isNullOrBlank() && stamped.externalEventId != fresh.externalEventId
        if (created && (result == MappingStamp.GONE || result == MappingStamp.INSERT_UNRESOLVED)) {
            // Nothing holds this event: the row it was made for is gone, or belongs
            // to a newer insert that mirrors itself once confirmed.
            deleteEvent?.invoke(stamped)
        }
    }
}
