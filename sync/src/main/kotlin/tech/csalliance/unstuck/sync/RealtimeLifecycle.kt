package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes the realtime-subscription lifecycle so a rapid background→foreground
 * (or the auth flow racing the lifecycle) can never settle in the broken
 * "foregrounded but UNSUBSCRIBED with no refresh" state — the concrete live-sync
 * bug where a web edit didn't reach an already-open app until a cold relaunch.
 *
 * Pure orchestration — the side effects are injected, so this is unit-testable
 * without Supabase/Android:
 *  - [hydrate]     full REST pull (the reliable source of truth)
 *  - [subscribe]   (re)build the live realtime mirror; MUST be idempotent AND
 *                  MUST return true only when a REAL subscription actually happened
 *                  (e.g. a user exists and channels were created). Returning false
 *                  when it no-ops (no current user yet) keeps the flag honest so a
 *                  later real sign-in still establishes the mirror.
 *  - [unsubscribe] tear the mirror down
 *
 * Contract:
 *  - [resume] ALWAYS hydrates, then ensures the mirror is subscribed.
 *  - [pause] tears the mirror down.
 *  - pause vs resume are serialized (a [Mutex]) AND last-wins (cancel-previous),
 *    so a quick background→foreground settles subscribed+refreshed and a quick
 *    foreground→background settles unsubscribed — no lost final state.
 *  - [subscribe] only runs when not already subscribed (the idempotency guard
 *    that replaces the old racy early-return), but [resume] hydrates every time.
 */
internal class RealtimeLifecycle(
    private val scope: CoroutineScope,
    private val hydrate: suspend () -> Unit,
    private val subscribe: suspend () -> Boolean,
    private val unsubscribe: suspend () -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    // Thread-safe (was a plain var flipped inside scope.launch — the race).
    private val subscribed = AtomicBoolean(false)
    private val mutex = Mutex()
    @Volatile private var job: Job? = null

    val isSubscribed: Boolean get() = subscribed.get()

    /** Foreground: pull server-canonical (ALWAYS), then ensure the live mirror is
     *  up. Cancels any in-flight pause so foreground is the winning final state. */
    fun resume() {
        job?.cancel()
        job = scope.launch {
            mutex.withLock {
                runCatching {
                    hydrate()
                    ensureSubscribedLocked()
                }.onFailure(onError)
            }
        }
    }

    /** Background: drop the live mirror (channels + socket). Cancels any in-flight
     *  resume so background is the winning final state. */
    fun pause() {
        job?.cancel()
        job = scope.launch {
            mutex.withLock { unsubscribeLocked() }
        }
    }

    /** Auth-driven (sign-in / initial session): ensure the mirror is subscribed,
     *  serialized against the lifecycle transitions. Idempotent. */
    suspend fun ensureSubscribed() {
        mutex.withLock { ensureSubscribedLocked() }
    }

    /** Sign-out: force the mirror down and cancel any pending transition. */
    suspend fun forceUnsubscribe() {
        job?.cancel()
        mutex.withLock { unsubscribeLocked() }
    }

    /** Auth sign-in / initial session: force a fresh REAL (re)subscribe regardless
     *  of the current flag. A prior [resume] that ran while the session was still
     *  being restored may have called [subscribe] as a no-op (no user) — WITHOUT the
     *  Boolean return that would have kept the flag false, a stale subscribed==true
     *  could otherwise make this a skip. Reset the flag under the lock, then
     *  ensure a real subscription now that a user exists. Serialized vs pause/resume. */
    suspend fun resubscribe() {
        mutex.withLock {
            subscribed.set(false)
            ensureSubscribedLocked()
        }
    }

    private suspend fun ensureSubscribedLocked() {
        if (!subscribed.get()) {
            // Only mark subscribed when a REAL subscription actually happened.
            // [subscribe] returns false when it no-ops (e.g. a resume() racing the
            // async session restore, currentUserId still null): leaving the flag
            // false lets the later SIGNED_IN/INITIAL_SESSION establish the real
            // mirror instead of skipping it on a stale subscribed==true (the
            // "web edit doesn't reach the open app for the whole session" bug).
            subscribed.set(subscribe())
        }
    }

    private suspend fun unsubscribeLocked() {
        if (subscribed.get()) {
            runCatching { unsubscribe() }.onFailure(onError)
            subscribed.set(false)
        }
    }
}
