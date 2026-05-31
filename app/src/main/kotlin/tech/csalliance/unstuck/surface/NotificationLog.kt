package tech.csalliance.unstuck.surface

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.core.logic.newUuid

/**
 * A small persistent log of notifications the app has shown, so the in-app
 * notification center can list them after they're swiped from the system tray.
 * Process-wide singleton backed by SharedPreferences (client-only, capped) +
 * a StateFlow the UI observes. `lastSeen` drives the unread badge. Reminders
 * that haven't fired yet are NOT stored here — the center computes those live
 * from the scheduled blocks.
 */
object NotificationLog {
    @Serializable
    data class Entry(
        val id: String,
        val kind: String,
        val title: String,
        val body: String,
        val deepLink: String? = null,
        val at: Long,
    )

    private const val PREFS = "unstuck.notiflog"
    private const val KEY = "log"
    private const val SEEN = "lastSeen"
    private const val CAP = 60

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var prefs: SharedPreferences? = null

    private val _items = MutableStateFlow<List<Entry>>(emptyList())
    val items: StateFlow<List<Entry>> get() = _items

    private val _lastSeen = MutableStateFlow(0L)
    val lastSeen: StateFlow<Long> get() = _lastSeen

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }

    /** Load persisted state at app start (UnstuckApp.onCreate). */
    fun init(context: Context) {
        val p = prefs(context)
        _items.value = runCatching { json.decodeFromString<List<Entry>>(p.getString(KEY, "[]") ?: "[]") }.getOrDefault(emptyList())
        _lastSeen.value = p.getLong(SEEN, 0L)
    }

    /** Record a shown notification (newest first), capped. Called from the renderer. */
    fun add(context: Context, kind: String?, title: String, body: String, deepLink: String?, at: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val entry = Entry(id = newUuid(), kind = kind ?: "note", title = title, body = body, deepLink = deepLink, at = at)
        // Atomic read-modify-write so a concurrent FCM + alarm post can't drop an entry.
        val next = _items.updateAndGet { (listOf(entry) + it).take(CAP) }
        runCatching { p.edit().putString(KEY, json.encodeToString(next)).apply() }
    }

    /** Mark everything currently logged as seen (clears the unread badge). */
    fun markAllSeen() {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        _lastSeen.value = now
        p.edit().putLong(SEEN, now).apply()
    }

    /** Wipe the log + unread marker. Called on sign-out so a different account on
     *  this device never sees the previous user's notification history. */
    fun clear(context: Context) {
        _items.value = emptyList()
        _lastSeen.value = 0L
        runCatching { prefs(context).edit().clear().apply() }
    }
}
