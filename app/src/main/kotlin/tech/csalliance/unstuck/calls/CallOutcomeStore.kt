package tech.csalliance.unstuck.calls

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.csalliance.unstuck.UnstuckApp
import tech.csalliance.unstuck.core.logic.CallNotificationCopy
import tech.csalliance.unstuck.core.logic.CallNotificationKind
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.CallOutcomeQueue
import tech.csalliance.unstuck.core.logic.CallOutcomeReceipt
import tech.csalliance.unstuck.core.logic.PendingOutcome
import tech.csalliance.unstuck.surface.NotificationChannels
import tech.csalliance.unstuck.sync.CallOutcomeRejected
import tech.csalliance.unstuck.sync.CallsClient

// CallOutcomeStore — the DURABLE half of the call-outcome reporter (the pure
// queue rules are :core CallOutcomeQueue, ported from iOS CallsOutcomeReporter).
//
//   ring UI / voice service ──▶ enqueue(): load → queue.enqueue → save → flush
//   foreground / reconnect  ──▶ flushAsync(): drain what is queued, in order
//
// The queue is ONE JSON string in SharedPreferences (`unstuck.calls.outcome`,
// key `queue`), written with commit() the moment an item is added — a report
// made by the Answer tap or the missed alarm must survive the process being
// killed a millisecond later (Doze, low memory, the user swiping the app away).
// The next foreground / hydrate / enqueue replays whatever is still queued.
//
// flush(): one drain at a time (a Mutex, so the Answer tap's flush and the
// foreground flush can't double-send the same head); IN ORDER (answered before
// done, never the reverse — `queue.next()` is always the head); a TRANSIENT
// failure (offline, 5xx, 401 refresh, 408, 429) leaves the head in place with
// attempts+1 and a backoff, and the drain STOPS for now (the next foreground /
// enqueue retries — the app never spins on a dead network); a PERMANENT refusal
// (CallOutcomeRejected: 404 the row is gone / not the caller's, 400 / 422 — any
// other 4xx) drops THAT item and the drain continues, so one dead report never
// blocks every later missed / snoozed / done behind it.
//
// THE MISSED NOTICE RIDES WITH THE REPORT (calls build-out 2026-09-20 §5): a
// `missed` item carries `notify` + the ring `payload`, persisted with it, and
// the "I called about X" notification is posted HERE — once the server takes
// the report and answers `retry: false`, or refuses it for good (no re-ring is
// coming either way) — and swallowed on `retry: true`, the server's one
// automatic ring-back 5 min later (the second miss is final and posts). The
// flag arrives asynchronously, so nobody posts it at the 30 s timeout any
// more; a process killed before the flush still posts on the next one.
//
// CONCURRENCY: the queue is one JSON blob, so EVERY read-modify-write of it —
// enqueue (FCM thread, the Answer tap, the alarm receiver, the voice service)
// and each of the drain's own markSent / markFailed — runs under [queueLock],
// and the drain RE-LOADS the queue after every suspending send instead of
// writing back a snapshot taken before it. Without that, an outcome queued
// while a send was in flight (a `snoozed` three seconds after the `answered`
// that started the flush) was overwritten by the stale in-memory copy and lost
// silently: the server never learned about the snooze, so the call-back the
// user had just been promised never came. [flushLock] still keeps the drains
// themselves one-at-a-time; it cannot be [queueLock] because enqueue is a
// blocking call from threads that may be about to die.
object CallOutcomeStore {
    const val PREFS = "unstuck.calls.outcome"
    const val KEY_QUEUE = "queue"

    /** One drain at a time. */
    private val flushLock = Mutex()
    /** Every read-modify-write of the persisted blob, from any thread (see the
     *  file header's CONCURRENCY note). A plain monitor, not a Mutex: [enqueue]
     *  is called from the FCM thread / a receiver and cannot suspend. */
    private val queueLock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The persisted queue (empty when nothing is queued or the JSON is corrupt). */
    fun load(context: Context): CallOutcomeQueue =
        CallOutcomeQueue.fromJson(prefs(context).getString(KEY_QUEUE, null))

    /** Persist [q] — commit(), not apply(): the caller may be about to die. */
    fun save(context: Context, q: CallOutcomeQueue) {
        val e = prefs(context).edit()
        if (q.isEmpty) e.remove(KEY_QUEUE) else e.putString(KEY_QUEUE, q.toJson())
        e.commit()
    }

    /** Load → [mutate] → save, atomically against every other queue writer.
     *  The block must not suspend or block on I/O other than the save. */
    private fun <T> mutate(context: Context, mutate: (CallOutcomeQueue) -> T): T = synchronized(queueLock) {
        val q = load(context)
        val result = mutate(q)
        save(context, q)
        result
    }

    /** Sign-out: forget everything queued (nothing left in it can be sent with
     *  the next account's JWT — the server would answer not_found for each). */
    fun clear(context: Context) {
        synchronized(queueLock) { prefs(context).edit().remove(KEY_QUEUE).commit() }
    }

    /** Queue one outcome (persisted before this returns) and kick a flush on the
     *  app scope when a signed-in client exists. Safe from any thread — the
     *  append is atomic against a drain that is mid-send. */
    fun enqueue(
        context: Context, callId: String, outcome: CallOutcome, snoozeMin: Int? = null,
        outcomeNotes: List<String>? = null, nowMs: Long = System.currentTimeMillis(),
        notify: CallNotificationKind? = null, payload: Map<String, String>? = null,
    ): PendingOutcome {
        val item = mutate(context) { q -> q.enqueue(callId, outcome, snoozeMin, outcomeNotes, nowMs, notify, payload) }
        flushAsync(context)
        return item
    }

    /**
     * Drain the queue through [client] (see the file header for the rules).
     * Returns how many reports the server accepted. Never throws: every
     * failure is recorded in the persisted queue instead.
     */
    suspend fun flush(context: Context, client: CallsClient, nowMs: () -> Long = { System.currentTimeMillis() }): Int =
        flush(context, nowMs) { head -> client.reportOutcome(head.callId, head.outcome.wire, head.snoozeMin, head.outcomeNotes) }

    /** The drain over an injectable sender (tests). The sender answers the
     *  server's receipt; its `retry` decides whether the item's deferred
     *  notification (if any) is posted or swallowed — [settleNotification]. */
    internal suspend fun flush(
        context: Context, nowMs: () -> Long = { System.currentTimeMillis() },
        send: suspend (PendingOutcome) -> Result<CallOutcomeReceipt>,
    ): Int {
        var sent = 0
        flushLock.withLock {
            while (true) {
                // Re-read the head every round: an outcome enqueued while the last
                // send was in flight is on disk, and a snapshot taken before the
                // suspension would overwrite it on the way back out.
                val head = synchronized(queueLock) { load(context).next(nowMs()) } ?: break
                val result = send(head)
                if (result.isSuccess) {
                    mutate(context) { q -> q.markSent(head.callId) }
                    sent++
                    settleNotification(context, head, retry = result.getOrThrow().retry)
                } else {
                    val err = result.exceptionOrNull()
                    if (err is CancellationException) throw err
                    val permanent = err is CallOutcomeRejected
                    mutate(context) { q -> q.markFailed(head.callId, permanent = permanent, nowMs = nowMs()) }
                    println("[calls] outcome ${head.outcome.wire} for ${head.callId} ${if (permanent) "rejected for good — dropped" else "failed, will retry"}: $err")
                    if (!permanent) break
                    // The server will answer the same way forever: no re-ring is
                    // coming for a row it won't take a report on, so the miss
                    // notice (if any) still goes out.
                    settleNotification(context, head, retry = false)
                }
            }
        }
        return sent
    }

    /** The server settled a report: post its deferred notification unless a
     *  retry ring is coming (CallOutcomeReceipt.shouldNotify). Best-effort —
     *  a notification failure must never fail the drain. */
    private fun settleNotification(context: Context, item: PendingOutcome, retry: Boolean) {
        val kind = item.notify ?: return
        if (!CallOutcomeReceipt.shouldNotify(retry)) {
            println("[calls] ${item.outcome.wire} for ${item.callId}: server re-rings — notice withheld")
            return
        }
        val payload = item.ringPayload ?: return
        runCatching {
            NotificationChannels.ensureAll(context)
            CallNotifications.post(context, CallNotificationCopy.of(kind, payload))
        }
    }

    /** Best-effort flush on the app scope: a no-op without a configured,
     *  signed-in client (the queue stays on disk for the next foreground). */
    fun flushAsync(context: Context) {
        val app = context.applicationContext as? UnstuckApp ?: return
        val graph = runCatching { app.graph }.getOrNull() ?: return
        val client = graph.provider?.client ?: return
        if (graph.coordinator?.auth?.currentUserId == null) return
        graph.scope.launch {
            runCatching { flush(app, CallsClient(client)) }
        }
    }

    /**
     * Wire the "replay on foreground / after a pull" rule (call once from
     * UnstuckApp.onCreate): every app foreground and every completed hydrate
     * (which is also what a realtime reconnect triggers) drains the queue.
     * Idempotent per process.
     */
    fun installForegroundFlush(app: UnstuckApp) {
        if (installed) return
        installed = true
        val graph = app.graph
        graph.scope.launch { graph.foregrounds.collect { flushAsync(app) } }
        graph.coordinator?.let { c -> graph.scope.launch { c.hydrated.collect { flushAsync(app) } } }
    }

    @Volatile private var installed = false
}
