package tech.csalliance.unstuck.calls

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tech.csalliance.unstuck.BuildConfig
import tech.csalliance.unstuck.UnstuckApp
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.core.logic.CallEnv
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.core.logic.CallSettingsLogic
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.sync.accountId
import java.time.ZoneId

/**
 * Builds the core `CallEnv` (what CallCoordinatorLogic.decide reads) from the
 * AppGraph singletons — the Android port of iOS `AppCallEnvironment`:
 *  - signedIn        this phone holds an account's session, live or stored
 *                    (SessionGate — a call queued before a sign-out lands
 *                    silent: no JWT, and never the previous account's notes
 *                    as a notification);
 *  - assistantEnabled the build flag AND the AI Assistant setting — off means
 *                    the call is declined with a notification (plan risk 10);
 *  - callsEnabled    the per-account "Calls from Unstuck" toggle (Settings › Notifications & calls);
 *  - aiConsent       the account's AI data-sharing OK (core AIConsent), this
 *                    phone's copy — without it a call never connects;
 *  - withinHours     the user's Settings › Notifications & calls window (CallSettingsLogic:
 *                    start inclusive, end exclusive, overnight when end < start);
 *  - focusLive       a focus session is running (the live-session store);
 *  - anchorExists    for a task-anchored call: false only for what this phone
 *                    KNOWS — the task is done or its delete is queued here, or
 *                    the block (when anchored to one) is done / skipped / being
 *                    deleted. NULL when there is nothing to check (no task, no
 *                    graph, the store read did not answer in time) or the row
 *                    isn't in this phone's store yet — the decision treats null
 *                    as "ring rather than drop" (iOS: no store yet → ring).
 *
 * Called on the FCM thread inside the ~10 s onMessageReceived window, so the
 * session and store reads are bounded (SESSION_TIMEOUT_MS / READ_TIMEOUT_MS)
 * and fall back to "unknown".
 */
object AppCallEnvironment {
    const val READ_TIMEOUT_MS = 2_500L
    /** The session wait — the stored session answers once it runs out. */
    const val SESSION_TIMEOUT_MS = 3_000L

    fun env(context: Context, payload: IncomingCallPayload, nowMs: Long = System.currentTimeMillis()): CallEnv {
        val graph = (context.applicationContext as? UnstuckApp)?.graph
        // Not `auth.currentUserId`: it is null whenever the app is in the background
        // (supabase-kt resets the session at every ON_STOP) and while a process the
        // push cold-started is still loading it, so every ring outside the app was
        // dropped as "not signed in" (Android audit 2026-09-23, A1). The gate waits for
        // the session (restoring it when nothing else will) and, out of time, lets the
        // stored session speak for the account — iOS's VoIP-token proxy. The restore it
        // starts carries on, so an answer finds a live session for the dial.
        val uid = graph?.coordinator?.session?.let { gate ->
            runCatching { runBlocking { gate.ensure(SESSION_TIMEOUT_MS) } }.getOrNull()?.accountId
        }
        val callSettings = if (uid != null) CallSettingsStore.load(context, uid) else CallSettings()
        val assistantOn = BuildConfig.ASSISTANT_ENABLED && (graph?.settings?.load()?.assistantEnabled ?: true)
        return CallEnv(
            signedIn = uid != null,
            assistantEnabled = assistantOn,
            withinHours = CallSettingsLogic.withinHours(hhmm(nowMs), callSettings),
            focusLive = focusLive(graph),
            anchorExists = anchorExists(graph, payload),
            callsEnabled = callSettings.enabled,
            // The device copy of the account's OK (sign-out wipes it). No graph
            // (can't happen in the app) → no OK: fail closed.
            aiConsent = hasAIConsent(graph, uid),
        )
    }

    /** The account's AI-consent OK as this phone holds it (core AIConsent). */
    internal fun hasAIConsent(graph: tech.csalliance.unstuck.AppGraph?, uid: String?): Boolean =
        graph != null && AIConsent.grantedFor(graph.aiConsent.value, uid)

    /** A call passed the receipt rules and is ringing: re-read the account's
     *  OK (off the FCM thread), so one turned off on the web or another phone
     *  since this device last looked is known before the answer connects. */
    fun callWillRing(context: Context) {
        val graph = (context.applicationContext as? UnstuckApp)?.graph ?: return
        graph.scope.launch { runCatching { withTimeoutOrNull(RING_REREAD_TIMEOUT_MS) { graph.aiConsentSync.refresh(force = true) } } }
    }

    /** The ring lasts 30 s; the read has until then. */
    const val RING_REREAD_TIMEOUT_MS = 25_000L

    /** "HH:MM" in the device zone — the window is the user's local clock. */
    fun hhmm(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = CallSettingsLogic.hhmm(nowMs, zone)

    private fun focusLive(graph: tech.csalliance.unstuck.AppGraph?): Boolean {
        val g = graph ?: return false
        return bounded { g.store.getLiveSession()?.sessionStart != null } ?: false
    }

    /**
     * A row this phone has not synced yet is UNKNOWN, not gone (Android audit
     * 2026-09-23, A6): a backgrounded app has no realtime and syncs every 30 min
     * at best, so a task or block made on the web or the iPhone just before its
     * call is usually missing here — and reading that as `false` reported the
     * call `stale` (terminal, silent: no ring, no retry, no notice). send-call
     * already checked the anchor against the database before it rang, so a
     * missing row rings with the payload's own label (null). Only what THIS
     * device knows retires it: the row is here and done (task) / done or skipped
     * (block), or its delete is still queued (removed here, not yet on the server).
     */
    internal fun anchorExists(graph: tech.csalliance.unstuck.AppGraph?, p: IncomingCallPayload): Boolean? {
        val taskId = p.taskId ?: return null
        val g = graph ?: return null
        return bounded {
            val task = g.store.getOne(Tables.TASKS, taskId, TaskItem.serializer())
            if (task == null && g.store.hasPendingDelete(Tables.TASKS, taskId)) return@bounded false
            if (task?.done == true) return@bounded false
            val blockId = p.blockId
            val block = blockId?.let { g.store.getOne(Tables.CAL_BLOCKS, it, CalBlock.serializer()) }
            if (blockId != null && block == null && g.store.hasPendingDelete(Tables.CAL_BLOCKS, blockId)) return@bounded false
            if (block != null && (block.done || block.skipped)) return@bounded false
            if (task == null || (blockId != null && block == null)) null else true
        }
    }

    /** A bounded blocking read on the FCM thread; null on timeout or error. */
    private fun <T> bounded(read: suspend () -> T): T? =
        runCatching { runBlocking { withTimeoutOrNull(READ_TIMEOUT_MS) { read() } } }.getOrNull()
}
