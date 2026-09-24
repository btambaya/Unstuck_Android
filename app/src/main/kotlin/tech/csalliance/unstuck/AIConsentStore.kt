package tech.csalliance.unstuck

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.sync.AIConsentSnapshot
import tech.csalliance.unstuck.sync.AuthService
import java.util.concurrent.atomic.AtomicInteger

/**
 * This device's copy of the account's AI data-sharing OK (core [AIConsent]) —
 * ONE process-wide flow, persisted, so the gate, Settings and a call ringing
 * before the app's UI is up all read the same answer. Sign-out wipes it
 * (AppGraph → coordinator.onSignedOut). The Android twin of iOS AIConsentStore
 * + AppModel.aiConsentCache.
 */
class AIConsentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _cache = MutableStateFlow(runCatching { AIConsent.decode(prefs.getString(KEY, null)) }.getOrNull())
    val cache: StateFlow<AIConsent.Cache?> = _cache.asStateFlow()
    val value: AIConsent.Cache? get() = _cache.value

    fun set(next: AIConsent.Cache?) {
        if (next == _cache.value) return
        _cache.value = next
        runCatching {
            if (next == null) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, AIConsent.encode(next)).apply()
        }
    }

    fun clear() = set(null)

    companion object {
        const val PREFS = "unstuck.aiConsent"
        const val KEY = "unstuck.aiConsent"
    }
}

/**
 * Keeps [AIConsentStore] in step with the account's user_metadata, off any
 * screen: what the session carries lands on sign-in, a fresh /user read
 * settles it (at most once a minute unless forced), and a change made here
 * goes up — kept pending until it lands, so an answer given offline is sent
 * again instead of the account's older one winning (core AIConsent.merge).
 * AppViewModel drives it from the UI; the call path asks it for a fresh read
 * while a call rings.
 */
class AIConsentSync(
    private val store: AIConsentStore,
    private val auth: () -> AuthService?,
    private val uid: () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** The /user read — a seam for the tests. */
    private val fetch: suspend () -> AIConsentSnapshot? = { auth()?.fetchAIConsent() },
    /** The user_metadata write — a seam for the tests. */
    private val write: suspend (AIConsent.Record) -> AIConsent.Record? = { r -> auth()?.setAIConsent(r) },
) {
    private val pushGen = AtomicInteger(0)
    private val refreshLock = Mutex()
    @Volatile private var lastFetchMs: Long? = null

    /** The signed-in account's OK counts right now. */
    val granted: Boolean get() = AIConsent.grantedFor(store.value, uid())

    /** The account's answer landed (a session, or a fresh read). */
    fun adopt(record: AIConsent.Record, userId: String, source: AIConsent.Source) {
        store.set(AIConsent.merge(store.value, record, userId, source))
    }

    /** Record [record] on this device at once — pending, so it holds offline
     *  and wins over the account's older answer until [push] lands it. */
    fun recordLocally(record: AIConsent.Record): AIConsent.Cache {
        val cache = AIConsent.Cache(userId = uid() ?: store.value?.userId ?: "", record = record, pending = true)
        store.set(cache)
        return cache
    }

    /** Record [record] here and send it up. */
    suspend fun set(record: AIConsent.Record) = push(recordLocally(record))

    /** Send a change made here to user_metadata. One that doesn't land stays
     *  pending and goes again on the next refresh; a newer change supersedes it. */
    suspend fun push(cache: AIConsent.Cache) {
        val gen = pushGen.incrementAndGet()
        val landed = runCatching { write(cache.record) }.getOrNull() ?: return
        if (gen != pushGen.get() || store.value?.userId != cache.userId) return
        store.set(AIConsent.Cache(cache.userId, landed, pending = false))
    }

    /**
     * Bring the copy up to date: a change made here that hasn't landed is sent
     * again (it wins); otherwise the account is read fresh — at most once a
     * minute unless [force]d. A gate bounds its wait with [withTimeoutOrNull]
     * around this. Returns false only when nobody is signed in.
     */
    suspend fun refresh(force: Boolean): Boolean = refreshLock.withLock {
        val me = uid() ?: return@withLock false
        val cache = store.value
        if (cache != null && cache.pending && cache.userId == me) {
            push(cache)
            return@withLock true
        }
        val last = lastFetchMs
        if (!force && last != null && nowMs() - last < MIN_FETCH_GAP_MS) return@withLock true
        lastFetchMs = nowMs()
        // A change made here while the read was out (Agree tapped) beats an
        // answer the server gave before it landed.
        val changeGen = pushGen.get()
        val snapshot = runCatching { fetch() }.getOrNull()
        if (snapshot != null && changeGen == pushGen.get() && snapshot.userId == (uid() ?: snapshot.userId)) {
            adopt(snapshot.record, snapshot.userId, AIConsent.Source.FRESH)
        }
        true
    }

    /** Sign-out: forget when the account was last read. */
    fun reset() { lastFetchMs = null; pushGen.incrementAndGet() }

    companion object {
        const val MIN_FETCH_GAP_MS = 60_000L
    }
}
