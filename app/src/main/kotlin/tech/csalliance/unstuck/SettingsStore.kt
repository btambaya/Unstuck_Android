package tech.csalliance.unstuck

import android.content.Context
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.ThemePref

/**
 * How much Unstuck checks in (Settings → Notifications & calls). Calm = only
 * the reminders you set; Balanced = the default helpful set; Coach = a second
 * nudge too. It also sets how often the spoken focus coach talks.
 * The booleans below are the single source of truth for which moments each
 * level enables — read by ReminderScheduler, PausedCheckinScheduler, the
 * in-app nudge surface, and (synced to the server) the morning brief.
 */
enum class NotificationLevel(val label: String, val blurb: String) {
    CALM("Calm", "Only the reminders you set, and a recap."),
    BALANCED("Balanced", "Also a nudge when a task should start, a check-in if you've paused a while, and a morning summary."),
    COACH("Coach", "Also a second nudge if you haven't started 10 minutes in.");

    /** A "starts now" notification (Start / Reschedule) at the block's start time. */
    val atStart: Boolean get() = this != CALM
    /** A follow-up ~10 min after start if the task still hasn't been started. */
    val drifted: Boolean get() = this == COACH
    /** The paused-too-long check-in. */
    val pausedCheckin: Boolean get() = this != CALM
    /** The server-sent morning brief. */
    val morningBrief: Boolean get() = this != CALM
    /** Quiet in-app nudge cards on Today (no push). */
    val nudges: Boolean get() = this != CALM

    /** The pure-core copilot level this maps to (drives the spoken cadence). */
    val copilotLevel: tech.csalliance.unstuck.core.logic.CopilotLevel
        get() = when (this) {
            CALM -> tech.csalliance.unstuck.core.logic.CopilotLevel.CALM
            BALANCED -> tech.csalliance.unstuck.core.logic.CopilotLevel.BALANCED
            COACH -> tech.csalliance.unstuck.core.logic.CopilotLevel.COACH
        }

    /** The `notification_preferences.notification_level` wire value ('calm' | 'balanced' | 'coach'). */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromLabel(l: String): NotificationLevel = entries.firstOrNull { it.label == l } ?: BALANCED
        /** Null for an unknown / absent wire value (callers keep their local level). */
        fun fromWire(s: String?): NotificationLevel? = s?.let { w -> entries.firstOrNull { it.wire == w.lowercase() } }
    }
}

/** One in-app text size (Settings → Appearance), folded into the sp scale on
 *  top of the phone's own font size. It replaces Density + "Larger type",
 *  which both fed the same multiplier (slim-settings plan, Decision 1). */
enum class TextSize(val label: String, val scale: Float) {
    SMALLER("Smaller", 0.94f),
    DEFAULT("Default", 1.0f),
    LARGER("Larger", 1.15f);

    companion object {
        fun fromLabel(l: String): TextSize = entries.firstOrNull { it.label == l } ?: DEFAULT

        /** A device that never picked a text size keeps what its old Density /
         *  "Larger type" pair showed: comfy or larger type → Larger, compact →
         *  Smaller. The old keys are read for this only and never wiped. */
        fun fromLegacy(density: String?, largerType: Boolean): TextSize = when {
            largerType -> LARGER
            density.equals("COMFY", ignoreCase = true) -> LARGER
            density.equals("COMPACT", ignoreCase = true) -> SMALLER
            else -> DEFAULT
        }
    }
}

/**
 * Device-local user preferences (theme / text size / focus / sound),
 * persisted to SharedPreferences. Read once into a [SettingsState] that the
 * UI observes via AppViewModel; every setter writes straight back here so the
 * value survives relaunch.
 *
 * Retired 2026-09-24 (slim settings): accent, density, larger type, reduce
 * motion, high contrast, keyboard hints, hide-right-rail and the three focus
 * sounds. Their stored values are left where they are — nothing wipes them —
 * and nothing reads them any more (density + larger type only seed the first
 * [TextSize]). The system's own animation and font settings cover the rest.
 */
data class SettingsState(
    val theme: ThemePref = ThemePref.SYSTEM,
    val textSize: TextSize = TextSize.DEFAULT,
    /** Also the New Task sheet's last-picked estimate (it remembers it here, so
     *  the assistant's set_focus_defaults keeps working on the same key). */
    val focusDefaultMin: Int = 25,
    val focusOverrunMin: Int = 5,          // 0 = Never
    val focusSoftExit: Boolean = true,
    val focusPauseReasons: Boolean = true,
    /** Background noise during focus — the speaker button on the Focus screen.
     *  Stored as "off" | "brown"; an old "pink" reads as on. */
    val ambient: String = "off",
    val treatment: FocusTreatment = FocusTreatment.AMBIENT,
    val reminderLeadMin: Int = 10,         // default "remind me N min before a scheduled task"; 0 = Off
    val notificationLevel: NotificationLevel = NotificationLevel.BALANCED,
    // Hands-free Focus Copilot (Phase 1, on-device, no LLM):
    val focusCopilotSpeak: Boolean = true, // spoken progress coach during a block (speak-only)
    val focusCopilotVoice: Boolean = false, // also LISTEN for a hands-free reply (mic) — opt-in
    /**
     * AI Assistant kill-switch (Settings → Assistant & privacy → AI Assistant). The
     * published privacy policy promises this on every platform: turning it off
     * unmounts the assistant launcher and ignores open-assistant events, so nothing
     * is ever sent to the AI provider. Device-local (mirrors web's
     * `use-assistant-enabled`), and DEFAULT ON — an existing install must not
     * silently lose the assistant.
     */
    val assistantEnabled: Boolean = true,
) {
    /** The in-app text size as one sp multiplier. */
    val fontScale: Float get() = textSize.scale

    /** The Focus screen's speaker button is on. */
    val ambientOn: Boolean get() = ambient != AMBIENT_OFF

    companion object {
        const val AMBIENT_OFF = "off"
        /** What "on" stores (the one bundled noise bed). */
        const val AMBIENT_ON = "brown"
    }
}

class SettingsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("unstuck.settings", Context.MODE_PRIVATE)

    fun load(): SettingsState = SettingsState(
        theme = enumOf(p.getString("theme", null), ThemePref.SYSTEM),
        textSize = enumOrNull<TextSize>(p.getString("textSize", null))
            ?: TextSize.fromLegacy(p.getString("density", null), p.getBoolean("largerType", false)),
        focusDefaultMin = p.getInt("focusDefaultMin", 25),
        focusOverrunMin = p.getInt("focusOverrunMin", 5),
        focusSoftExit = p.getBoolean("focusSoftExit", true),
        focusPauseReasons = p.getBoolean("focusPauseReasons", true),
        ambient = p.getString("ambient", "off") ?: "off",
        treatment = enumOf(p.getString("treatment", null), FocusTreatment.AMBIENT),
        reminderLeadMin = p.getInt("reminderLeadMin", 10),
        notificationLevel = enumOf(p.getString("notificationLevel", null), NotificationLevel.BALANCED),
        focusCopilotSpeak = p.getBoolean("focusCopilotSpeak", true),
        focusCopilotVoice = p.getBoolean("focusCopilotVoice", false),
        assistantEnabled = p.getBoolean("assistantEnabled", true),
    )

    /** Writes only the live keys: the retired ones (accent, density, …) keep
     *  whatever they held, unread. */
    fun save(s: SettingsState) {
        p.edit()
            .putString("theme", s.theme.name)
            .putString("textSize", s.textSize.name)
            .putInt("focusDefaultMin", s.focusDefaultMin)
            .putInt("focusOverrunMin", s.focusOverrunMin)
            .putBoolean("focusSoftExit", s.focusSoftExit)
            .putBoolean("focusPauseReasons", s.focusPauseReasons)
            .putString("ambient", s.ambient)
            .putString("treatment", s.treatment.name)
            .putInt("reminderLeadMin", s.reminderLeadMin)
            .putString("notificationLevel", s.notificationLevel.name)
            .putBoolean("focusCopilotSpeak", s.focusCopilotSpeak)
            .putBoolean("focusCopilotVoice", s.focusCopilotVoice)
            .putBoolean("assistantEnabled", s.assistantEnabled)
            .apply()
    }

    /**
     * Realtime voice: hold-to-talk fallback (spec §8, key `voice.holdToTalk`,
     * default OFF). When on, the session runs with turn_detection = null and the
     * orb is press-and-hold; a noisy room that keeps false-triggering the server
     * VAD is the reason to switch. Device-local (mirrors web localStorage /
     * iOS @AppStorage("voiceHoldToTalk")).
     */
    fun voiceHoldToTalk(): Boolean = p.getBoolean("voice.holdToTalk", false)
    fun setVoiceHoldToTalk(on: Boolean) { p.edit().putBoolean("voice.holdToTalk", on).apply() }

    /** Per-task reminder lead override (minutes), or null to use the global default.
     *  Stored device-locally — reminders fire from on-device alarms. */
    fun reminderOverride(taskId: String): Int? =
        if (p.contains("reminder.override.$taskId")) p.getInt("reminder.override.$taskId", -1).takeIf { it >= 0 } else null

    fun setReminderOverride(taskId: String, leadMin: Int?) {
        p.edit().apply { if (leadMin == null) remove("reminder.override.$taskId") else putInt("reminder.override.$taskId", leadMin) }.apply()
    }

    /** Dismissed in-app nudge ids (e.g. "cap:<id>"/"slip:<id>") — persisted so a
     *  dismissed nudge stays dismissed across relaunch (was in-memory, so it
     *  reappeared on next launch). Copied out of the SharedPreferences set since
     *  the returned instance must not be mutated. */
    fun loadDismissedNudges(): Set<String> = (p.getStringSet("dismissedNudges", emptySet()) ?: emptySet()).toSet()

    fun saveDismissedNudges(ids: Set<String>) {
        p.edit().putStringSet("dismissedNudges", ids).apply()
    }

    /** Capture ids the user has archived from the Inbox (triaged without deleting).
     *  Since migration 053 the SERVER owns this (`captures.archived_at`); this set is
     *  the device CACHE — keeps the inbox right offline, migrates up once (see
     *  [captureArchiveMigrated]) and is overwritten by the server after each pull.
     *  Cleared on sign-out. */
    fun loadArchivedCaptureIds(): Set<String> = (p.getStringSet("archivedCaptureIds", emptySet()) ?: emptySet()).toSet()

    fun saveArchivedCaptureIds(ids: Set<String>) {
        p.edit().putStringSet("archivedCaptureIds", ids).apply()
    }

    /** Archive writes not yet landed on the server (id → archived?). Retried on every
     *  pull; the server-wins reconcile keeps these local values until they land, so an
     *  offline archive never flips back. Cleared on sign-out. */
    fun loadPendingCaptureArchiveWrites(): Map<String, Boolean> =
        (p.getStringSet("captureArchivePending", emptySet()) ?: emptySet()).mapNotNull { raw ->
            val i = raw.lastIndexOf(':'); if (i <= 0) null else raw.substring(0, i) to (raw.substring(i + 1) == "1")
        }.toMap()

    fun savePendingCaptureArchiveWrites(pending: Map<String, Boolean>) {
        p.edit().putStringSet("captureArchivePending", pending.map { (id, a) -> "$id:${if (a) 1 else 0}" }.toSet()).apply()
    }

    /** True once this device's pre-053 local archive has been pushed up for [uid]
     *  (one-time). Keyed per account, so it survives sign-out. */
    fun captureArchiveMigrated(uid: String): Boolean = p.getBoolean("captureArchiveMigrated.$uid", false)
    fun setCaptureArchiveMigrated(uid: String) { p.edit().putBoolean("captureArchiveMigrated.$uid", true).apply() }

    // ── notification level + reminder lead: server-backed since 2026-09 ──
    // notification_preferences.notification_level / reminder_lead_min are the source
    // of truth (what web Settings shows, what the server crons read). The local
    // SettingsState fields are the cache the alarms run from.

    /** True once this device has pushed its local level + lead up for [uid] (one-time:
     *  before this build Android never wrote `notification_level`, and the server row
     *  register-push-token creates carries `reminder_lead_min = 0` = reminders OFF — so
     *  taking the server's word first would silently switch an existing user's
     *  reminders off). Afterwards the server wins on every pull. Keyed per account. */
    fun notifPrefsMigrated(uid: String): Boolean = p.getBoolean("notifPrefsMigrated.$uid", false)
    fun setNotifPrefsMigrated(uid: String) { p.edit().putBoolean("notifPrefsMigrated.$uid", true).apply() }

    /** Server writes not yet landed: "level" and/or "lead". The server-wins reconcile
     *  skips a field while its write is pending (an offline choice must not revert
     *  before it's pushed). Cleared on sign-out. */
    fun loadPendingNotifPrefWrites(): Set<String> = (p.getStringSet("notifPrefsPending", emptySet()) ?: emptySet()).toSet()
    fun savePendingNotifPrefWrites(fields: Set<String>) { p.edit().putStringSet("notifPrefsPending", fields).apply() }

    /** Pending log_shared_focus retries (a JSON array, encoded/decoded by
     *  SharedFocusLedger). A partner-shared session's accrual is LEDGER-EXCLUSIVE,
     *  so an offline finish must persist the record and drain later — otherwise the
     *  minutes are silently lost. Idempotent server-side per sessionId, so keeping
     *  a record too long is safe; losing one is not. */
    fun loadPendingSharedFocusRaw(): String? = p.getString("pendingSharedFocus", null)

    fun savePendingSharedFocusRaw(rawJson: String?) {
        p.edit().apply {
            if (rawJson == null) remove("pendingSharedFocus") else putString("pendingSharedFocus", rawJson)
        }.apply()
    }

    /** Remove per-user device-local content (reminder overrides + dismissed
     *  nudges + the archived-capture cache + un-landed server writes) on sign-out so
     *  a different account on this device starts clean. Per-ACCOUNT keys
     *  (`*.<uid>` migration markers) are inert for anyone else and stay. */
    fun clearUserContent() {
        p.edit().apply {
            p.all.keys.filter { it.startsWith("reminder.override.") }.forEach { remove(it) }
            remove("dismissedNudges")
            remove("archivedCaptureIds")
            remove("captureArchivePending")
            remove("notifPrefsPending")
            // Pending shared-focus retries are the signed-out user's — a different
            // account must not try (and fail) to accrue them.
            remove("pendingSharedFocus")
        }.apply()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        enumOrNull<T>(name) ?: fallback

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
