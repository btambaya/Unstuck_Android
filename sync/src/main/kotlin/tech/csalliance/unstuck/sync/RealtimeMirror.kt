package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// RealtimeMirror — subscribes to postgres_changes per synced table and applies
// INSERT/UPDATE (upsert local) + DELETE (remove local). One channel per table,
// filtered by user_id (RLS enforces server-side; the filter is client safety).
// calendar_connections is intentionally NOT subscribed — its encrypted creds
// must never be broadcast. Port of the iOS RealtimeMirror.swift.

class RealtimeMirror(
    private val client: SupabaseClient,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    // Invoked when a channel that HAD been SUBSCRIBED drops to UNSUBSCRIBED while the
    // socket stays connected (a server-side channel close). The socket-status observer
    // never fires for this, so without a per-channel heal only the catch-up pull
    // recovers it. The callback must be cheap/non-blocking (it launches the heal
    // itself) and is expected to coalesce (several channels can close at once).
    private val onChannelClosed: () -> Unit = {},
    // EVERY realtime event, any table, before it is applied. The freshness owner is
    // the only consumer: time-since-last-event is the one honest signal that the
    // socket is actually delivering (channel status is not — a channel can report
    // SUBSCRIBED and be permanently deaf).
    private val onEvent: () -> Unit = {},
    // A channel reached SUBSCRIBED. Everything written while it was down was never
    // broadcast (postgres_changes has no replay), so the owner pulls.
    private val onSubscribed: () -> Unit = {},
    // A row of user_preferences / notification_preferences changed on another
    // device. Those tables are not row-mirrored into the local store (the app reads
    // them through PreferencesClient), so the mirror just says "re-read them".
    private val onPreferencesChanged: () -> Unit = {},
) {
    private val channels = mutableListOf<RealtimeChannel>()
    private val jobs = mutableListOf<Job>()

    suspend fun subscribeAll(userId: String, onMembersChanged: suspend () -> Unit = {}) {
        unsubscribeAll()
        // How an incoming row is applied lives in ONE place — RowApply — shared with
        // the catch-up pull, so the live path and the recovery path can never drift
        // apart on decode or on the last-write-wins rules.
        for (table in MIRRORED_TABLES) {
            subscribe(table, userId, noUserFilter = table == Tables.COLLECTIONS)
        }
        // Membership changes for ME — a new share or a revocation. Re-hydrate
        // collections so the freshly-shared list appears / the revoked one drops.
        subscribeMembers(userId, onMembersChanged)
        // SETTINGS ARE LIVE (migration 063: notification_preferences joined the
        // publication and every published table is on replica identity FULL). A
        // notification level / reminder lead / timezone change, or a struggles /
        // rituals / interview-flag change, now reaches this device while it is open
        // instead of waiting for the next process launch.
        subscribePreferences(userId)
    }

    private suspend fun subscribe(
        tableName: String,
        userId: String,
        noUserFilter: Boolean = false,
    ) {
        val channel = client.channel("unstuck_${tableName}_$userId")
        val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = tableName
            if (!noUserFilter) filter("user_id", FilterOperator.EQ, userId)
        }
        // Guard each event: one un-decodable row (a new column/enum, a null in a
        // required field) must NOT throw out of onEach and permanently kill this
        // table's live mirror — skip it and keep the stream alive (iOS does the same).
        val job = flow.onEach { action ->
            onEvent()
            runCatching {
                when (action) {
                    is PostgresAction.Insert -> RowApply.apply(tableName, action.record, store, userId)
                    is PostgresAction.Update -> RowApply.apply(tableName, action.record, store, userId)
                    is PostgresAction.Delete -> action.oldRecord["id"]?.let { store.delete(tableName, it.jsonPrimitive.content) }
                    else -> {}
                }
            }.onFailure { println("[realtime] $tableName event skipped: $it") }
        }.launchIn(scope)
        runCatching { channel.subscribe() }
            .onFailure { println("[realtime] subscribe $tableName failed: $it") }
        // Observability + self-heal: surface the channel's status transitions AND
        // trigger a re-subscribe if it closes server-side while the socket stays up.
        // Tracked in jobs so it's cancelled on unsubscribeAll.
        val statusJob = observeChannelStatus(channel, tableName)
        channels += channel
        jobs += job
        jobs += statusJob
    }

    /** user_preferences + notification_preferences (migration 063). Neither is
     *  row-mirrored into the local store — the app reads them through
     *  PreferencesClient — so an event just asks the app layer to re-read. Both are
     *  keyed by user_id (no `id` column), which is also the filter column. */
    private suspend fun subscribePreferences(userId: String) {
        for (table in PREFERENCE_TABLES) {
            val channel = client.channel("unstuck_${table}_$userId")
            val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                this.table = table
                filter("user_id", FilterOperator.EQ, userId)
            }
            val job = flow.onEach {
                onEvent()
                runCatching { onPreferencesChanged() }
                    .onFailure { println("[realtime] $table refresh failed: $it") }
            }.launchIn(scope)
            runCatching { channel.subscribe() }
                .onFailure { println("[realtime] subscribe $table failed: $it") }
            val statusJob = observeChannelStatus(channel, table)
            channels += channel
            jobs += job
            jobs += statusJob
        }
    }

    /** Observe one channel's status: log every transition (a silent (re)subscribe
     *  failure must be visible) AND self-heal (BUG 4). If the channel had reached
     *  SUBSCRIBED and then drops to UNSUBSCRIBED (a server-side close while the socket
     *  is still CONNECTED — the socket observer won't fire), invoke [onChannelClosed]
     *  once to trigger a coalesced re-subscribe + hydrate. Intentional teardown is
     *  NOT seen here: [unsubscribeAll] cancels these jobs BEFORE unsubscribing the
     *  channels, so we never observe the teardown transition. */
    private fun observeChannelStatus(channel: RealtimeChannel, label: String): Job {
        var wasSubscribed = false
        return channel.status.onEach { status ->
            println("[realtime] $label channel status: $status")
            when (status) {
                RealtimeChannel.Status.SUBSCRIBED -> {
                    wasSubscribed = true
                    // Everything written while this channel was down was never
                    // broadcast — the owner decides what to pull.
                    onSubscribed()
                }
                RealtimeChannel.Status.UNSUBSCRIBED -> if (wasSubscribed) {
                    wasSubscribed = false   // fire once per close; the rebuild starts fresh
                    println("[realtime] $label channel closed while socket up — requesting heal")
                    onChannelClosed()
                }
                else -> {}
            }
        }.launchIn(scope)
    }

    /** collection_members changes I can SEE — as a member (my own row: a new share
     *  or a revocation) AND as an OWNER (someone joined / left / was removed from a
     *  list I own). Deliberately UNFILTERED, like the collections channel: RLS
     *  ("visible to member or owner") scopes INSERT/UPDATE delivery. The old
     *  `user_id = me` filter never fired for the owner, so the owner's client didn't
     *  learn its list was shared until the next full hydrate and kept whole-row
     *  upserting the items JSONB over members' atomic RPC edits. Any event →
     *  re-hydrate collections via [onChanged]; the membership lives in
     *  members[]/myRole, refreshed by the hydrate. Known cost: Realtime can't apply
     *  RLS to a DELETE (only the old row's PK is in the WAL), so a leave/remove
     *  anywhere reaches every subscriber as one extra cheap collections pull — rare
     *  (a membership change, not an edit) and harmless (RLS scopes the pull itself).
     *  Belt-and-braces: migration 056 also bumps collections.updated_at on every
     *  membership change, so the collections echo carries the owner's row too.
     *  A burst of events (a list deleted with N members, an account deletion's
     *  cascade) used to run N collections pulls back to back; now it costs one run
     *  plus at most one trailing run ([coalescedSignal]). */
    private suspend fun subscribeMembers(userId: String, onChanged: suspend () -> Unit) {
        val channel = client.channel("unstuck_collection_members_$userId")
        val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "collection_members"
        }
        val (signal, consumer) = coalescedSignal(scope, onChanged)
        val job = flow.onEach {
            onEvent()
            signal()
        }.launchIn(scope)
        runCatching { channel.subscribe() }
            .onFailure { println("[realtime] subscribe collection_members failed: $it") }
        val statusJob = observeChannelStatus(channel, "collection_members")
        channels += channel
        jobs += consumer
        jobs += job
        jobs += statusJob
    }

    suspend fun unsubscribeAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        channels.forEach { runCatching { it.unsubscribe() } }
        channels.clear()
    }

    companion object {
        /** `signal()` never waits. [onChanged] runs once for every signal that
         *  arrived before that run started: a burst costs one run plus at most one
         *  trailing run (a CONFLATED channel holds at most one pending signal).
         *  Cancelling the returned job stops the consumer. Parity with iOS
         *  coalescedSignal (build 81, audit 2026-09-22 C8). */
        internal fun coalescedSignal(scope: CoroutineScope, onChanged: suspend () -> Unit): Pair<() -> Unit, Job> {
            val signals = Channel<Unit>(Channel.CONFLATED)
            val consumer = scope.launch {
                for (s in signals) {
                    try {
                        onChanged()
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        println("[realtime] collection_members refresh failed: $t")
                    }
                }
            }
            consumer.invokeOnCompletion { signals.close() }
            return { signals.trySend(Unit); Unit } to consumer
        }

        /** The row tables mirrored into the local store. calendar_connections is
         *  deliberately absent — its encrypted credentials must never be broadcast. */
        internal val MIRRORED_TABLES: List<String> = listOf(
            Tables.TASKS,
            Tables.SESSIONS,
            Tables.CAL_BLOCKS,
            Tables.CAPTURES,
            Tables.REASON_LOGS,
            // Shared collections are owned by someone else, so this one subscribes
            // WITHOUT the user_id filter and lets RLS decide delivery (members get
            // the owner's edits).
            Tables.COLLECTIONS,
            Tables.TAGS,
            Tables.LIFE_AREAS,
            Tables.PROFILE_FACTS,
            Tables.CALL_REQUESTS,
        )

        /** Account-wide settings tables (migration 063). Not row-mirrored: the app
         *  reads them through PreferencesClient, so an event means "re-read".
         *  sharing_preferences is published too but nothing on Android reads it. */
        internal val PREFERENCE_TABLES: List<String> = listOf("user_preferences", "notification_preferences")
    }
}
