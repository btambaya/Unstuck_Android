package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

// CollabRealtime — live cross-user CHANGE SIGNALS for sharing. Port of the web
// lib/collab-realtime.ts. The main RealtimeMirror mirrors your OWN rows into the
// local store; the sharing surfaces are RPC-backed (level-scoped projections), so
// they can't be table-mirrored — a recipient has NO RLS read on the raw task row.
// This subscribes to task_shares + trusted_circle postgres_changes (RLS scopes
// each subscriber to the rows they can see: their outgoing + incoming) and emits a
// Unit SIGNAL the later ViewModels observe to REFETCH via the CircleClient RPCs. It
// never mirrors row data itself. One channel, two table listeners, no user_id
// filter — exactly like ensureCollabRealtime() on the web.
class CollabRealtime(
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
) {
    // extraBufferCapacity + DROP_OLDEST → tryEmit never suspends, and a burst of
    // changes collapses to "at least one pending refetch" (the RPC re-reads
    // everything anyway, so coalescing is correct + cheap).
    private val _sharesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _circleChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires when any task_shares row I can see changes (my outgoing + incoming) →
     *  refetch taskSharesForTask / tasksSharedWithMe / myTaskShareBadges. */
    val sharesChanged: SharedFlow<Unit> = _sharesChanged

    /** Fires when my trusted_circle roster changes (invite accepted, connection
     *  added/removed) → refetch circleList. */
    val circleChanged: SharedFlow<Unit> = _circleChanged

    private var channel: RealtimeChannel? = null
    private val jobs = mutableListOf<Job>()

    /** Subscribe (idempotent). Collect the change flows BEFORE subscribing the
     *  channel (supabase-kt registers the postgres listeners on collection). */
    suspend fun subscribe() {
        if (channel != null) return
        val ch = client.channel("collab_rt")
        val sharesFlow = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "task_shares" }
        val circleFlow = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "trusted_circle" }
        jobs += sharesFlow.onEach { _sharesChanged.tryEmit(Unit) }.launchIn(scope)
        jobs += circleFlow.onEach { _circleChanged.tryEmit(Unit) }.launchIn(scope)
        runCatching { ch.subscribe() }
            .onFailure { println("[realtime] subscribe collab_rt failed: $it") }
        channel = ch
    }

    suspend fun unsubscribe() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        channel?.let { runCatching { it.unsubscribe() } }
        channel = null
    }
}
