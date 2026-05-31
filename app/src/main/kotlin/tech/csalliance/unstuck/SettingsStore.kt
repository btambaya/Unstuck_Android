package tech.csalliance.unstuck

import android.content.Context
import tech.csalliance.unstuck.core.model.Density
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.ThemePref
import tech.csalliance.unstuck.design.theme.AccentPalette

/**
 * Device-local user preferences (theme / density / accent / focus / sound /
 * accessibility), persisted to SharedPreferences. Mirrors the web
 * `theme-context` + `STORAGE_KEYS` PREF_* scalars. Read once into a
 * [SettingsState] that the UI observes via AppViewModel; every setter writes
 * straight back here so the value survives relaunch.
 */
data class SettingsState(
    val theme: ThemePref = ThemePref.SYSTEM,
    val accent: AccentPalette = AccentPalette.INDIGO_CORAL,
    val density: Density = Density.REGULAR,
    val largerType: Boolean = false,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
    val keyboardHints: Boolean = true,
    val focusDefaultMin: Int = 25,
    val focusOverrunMin: Int = 5,          // 0 = Never
    val focusCollapseRail: Boolean = true,
    val focusSoftExit: Boolean = true,
    val focusPauseReasons: Boolean = true,
    val soundStartChime: Boolean = true,
    val soundOverrunBell: Boolean = true,
    val soundCompletion: Boolean = false,
    val ambient: String = "off",           // off | brown | pink
    val treatment: FocusTreatment = FocusTreatment.AMBIENT,
    val reminderLeadMin: Int = 10,         // default "remind me N min before a scheduled task"; 0 = Off
) {
    /** density + larger-type folded into one sp multiplier (web parity). */
    val fontScale: Float
        get() {
            val d = when (density) {
                Density.COMPACT -> 0.94f
                Density.REGULAR -> 1.0f
                Density.COMFY -> 1.08f
            }
            return d * (if (largerType) 1.15f else 1.0f)
        }
}

class SettingsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("unstuck.settings", Context.MODE_PRIVATE)

    fun load(): SettingsState = SettingsState(
        theme = enumOf(p.getString("theme", null), ThemePref.SYSTEM),
        accent = enumOf(p.getString("accent", null), AccentPalette.INDIGO_CORAL),
        density = enumOf(p.getString("density", null), Density.REGULAR),
        largerType = p.getBoolean("largerType", false),
        reduceMotion = p.getBoolean("reduceMotion", false),
        highContrast = p.getBoolean("highContrast", false),
        keyboardHints = p.getBoolean("keyboardHints", true),
        focusDefaultMin = p.getInt("focusDefaultMin", 25),
        focusOverrunMin = p.getInt("focusOverrunMin", 5),
        focusCollapseRail = p.getBoolean("focusCollapseRail", true),
        focusSoftExit = p.getBoolean("focusSoftExit", true),
        focusPauseReasons = p.getBoolean("focusPauseReasons", true),
        soundStartChime = p.getBoolean("soundStartChime", true),
        soundOverrunBell = p.getBoolean("soundOverrunBell", true),
        soundCompletion = p.getBoolean("soundCompletion", false),
        ambient = p.getString("ambient", "off") ?: "off",
        treatment = enumOf(p.getString("treatment", null), FocusTreatment.AMBIENT),
        reminderLeadMin = p.getInt("reminderLeadMin", 10),
    )

    fun save(s: SettingsState) {
        p.edit()
            .putString("theme", s.theme.name)
            .putString("accent", s.accent.name)
            .putString("density", s.density.name)
            .putBoolean("largerType", s.largerType)
            .putBoolean("reduceMotion", s.reduceMotion)
            .putBoolean("highContrast", s.highContrast)
            .putBoolean("keyboardHints", s.keyboardHints)
            .putInt("focusDefaultMin", s.focusDefaultMin)
            .putInt("focusOverrunMin", s.focusOverrunMin)
            .putBoolean("focusCollapseRail", s.focusCollapseRail)
            .putBoolean("focusSoftExit", s.focusSoftExit)
            .putBoolean("focusPauseReasons", s.focusPauseReasons)
            .putBoolean("soundStartChime", s.soundStartChime)
            .putBoolean("soundOverrunBell", s.soundOverrunBell)
            .putBoolean("soundCompletion", s.soundCompletion)
            .putString("ambient", s.ambient)
            .putString("treatment", s.treatment.name)
            .putInt("reminderLeadMin", s.reminderLeadMin)
            .apply()
    }

    /** Per-task reminder lead override (minutes), or null to use the global default.
     *  Stored device-locally — reminders fire from on-device alarms. */
    fun reminderOverride(taskId: String): Int? =
        if (p.contains("reminder.override.$taskId")) p.getInt("reminder.override.$taskId", -1).takeIf { it >= 0 } else null

    fun setReminderOverride(taskId: String, leadMin: Int?) {
        p.edit().apply { if (leadMin == null) remove("reminder.override.$taskId") else putInt("reminder.override.$taskId", leadMin) }.apply()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
