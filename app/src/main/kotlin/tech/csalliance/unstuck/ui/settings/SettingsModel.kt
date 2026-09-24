package tech.csalliance.unstuck.ui.settings

// The slim Settings (plan 2026-09-24, Ahmad-approved): the pure half — the
// screens, the fixed test ids, the copy, and how an `unstuck://settings` link
// resolves. Compose-free so the tour data and the unit tests can use it.
//
// The hub, top to bottom:
//   Account card (name, email)            → ACCOUNT
//   Notifications & calls                 → NOTIFICATIONS
//   Assistant & privacy                   → ASSISTANT  (→ What Unstuck remembers)
//   People                                → PEOPLE
//   Appearance                            → APPEARANCE
//   Send feedback · Replay the tour       (one-tap actions)
//   Terms · Privacy · Unstuck <version>   (footer)
//
// What left Settings, and where it went:
//   Focus options          → "⋯ Options" on the Focus screen
//   Background noise       → the speaker button on the Focus screen
//   Default focus length   → the New Task sheet remembers the last estimate
//   Call lead              → "Call me about this" remembers the last pick
//   Areas, Tags            → the Tasks tab's "Edit" pill (one Areas & tags sheet)
//   Hold to talk           → the Talk screen ("Noisy room? Hold to talk")
//   Moments                → the web assistant's Routines (phones hide them)
//   Accent, density, high contrast, reduce motion, keyboard hints, hide rail,
//   the three focus sounds → deleted (stored values left alone, unread)

/** Every Settings screen. [row] + [rowSub] are the hub row, [title] the
 *  screen's app-bar title, [heading] the one plain line under it. [testTag] is
 *  fixed — it never follows the label (plan §4 "UI tests"). */
enum class SettingsSection(val row: String, val rowSub: String, val title: String, val heading: String, val testTag: String) {
    ACCOUNT("Account", "Name, password, export, sign out", "Account", "Your account.", "settings-row-account"),
    NOTIFICATIONS("Notifications & calls", "Reminders, check-ins and calls", "Notifications & calls", "How Unstuck reaches you.", "settings-row-notifications"),
    ASSISTANT("Assistant & privacy", "The AI, and what it remembers", "Assistant & privacy", "What the AI can see.", "settings-row-assistant"),
    /** Pushed from Assistant & privacy; not a hub row. */
    MEMORY("What Unstuck remembers", "See, change or forget what it has learned about you.", "What Unstuck remembers", "What Unstuck remembers about you.", "settings-row-memory"),
    PEOPLE("People", "Who you share tasks and lists with", "People you share with", "Who you share with.", "settings-row-people"),
    APPEARANCE("Appearance", "Light or dark, and text size", "Appearance", "How it looks.", "settings-row-appearance"),
}

/** The four screen rows of the hub, in order (the Account card sits above them). */
val SETTINGS_HUB_ROWS: List<SettingsSection> = listOf(
    SettingsSection.NOTIFICATIONS, SettingsSection.ASSISTANT, SettingsSection.PEOPLE, SettingsSection.APPEARANCE,
)

object SettingsCopy {
    const val HUB_TITLE = "Settings"
    const val HUB_HEADING = "How Unstuck behaves."
    const val ACCOUNT_FALLBACK = "Your account"
    const val SEND_FEEDBACK = "Send feedback"
    const val SEND_FEEDBACK_SUB = "Tell us what's working, or isn't"
    const val REPLAY_TOUR = "Replay the tour"
    const val REPLAY_TOUR_SUB = "A short walk through Unstuck"
    const val REPLAY_TOUR_RUNNING = "The tour is running now."
    const val FEEDBACK_TAG = "settings-row-feedback"
    const val TOUR_TAG = "settings-row-tour"
    const val TERMS = "Terms"
    const val PRIVACY = "Privacy"
    const val TERMS_A11Y = "Terms of Use, opens in your browser"
    const val PRIVACY_A11Y = "Privacy Policy, opens in your browser"
    const val TERMS_URL = "https://unstucknow.io/terms"
    const val PRIVACY_URL = "https://unstucknow.io/privacy"
    const val NAME_UNSET = "Set a name"

    /** "Unstuck 0.5.27 (111)" — the footer's build line. */
    fun version(name: String, code: Int): String = "Unstuck $name ($code)"

    // ── Account ──
    const val DISPLAY_NAME = "Display name"
    const val CHANGE_PASSWORD = "Change password"
    const val ADD_PASSWORD = "Add a password"
    const val CHANGE_PASSWORD_SUB = "Update the password you sign in with"
    const val ADD_PASSWORD_SUB = "Sign in with a password as well"
    /** Label unchanged so the privacy policy's "Export everything" line holds. */
    const val EXPORT = "Export everything"
    const val EXPORT_SUB = "Download a copy of everything you've put in Unstuck"
    const val SIGN_OUT = "Sign out"
    const val SIGN_OUT_SUB = "End this session on this phone"
    const val DELETE_ACCOUNT = "Delete my account"
    const val DELETE_ACCOUNT_SUB = "Permanently removes your data"

    // ── Notifications & calls ──
    const val REMINDERS = "Reminders"
    const val LEVEL_ROW = "How much Unstuck checks in"
    const val LEVEL_COACH_NOTE = "This also sets how often Unstuck talks you through a focus session."
    const val LEAD_ROW = "Remind me before a task"
    const val LEAD_SUB = "Reminders work even offline. Any task can have its own time."
    const val NOTIFS_OFF = "Notifications are off for Unstuck, so reminders can't reach you."
    const val NOTIFS_OFF_FIX = "Turn on"
    const val EXACT_ALARM = "Reminders may arrive late."
    const val EXACT_ALARM_FIX = "Fix"
    const val CALLS = "Calls"
    const val CALLS_INTRO = "Unstuck can ring your phone to plan, check in or go over your notes. It only rings when you ask, or for the calls you turn on below."
    const val CALLS_SWITCH = "Let Unstuck call this phone"
    const val CALLS_SWITCH_OFF_SUB = "Off: this phone won't ring. You'll get a notification with the notes instead."
    const val CALLS_HOURS = "Only call between"
    const val CALLS_HOURS_AND = "and"
    const val CALLS_HOURS_SUB = "Outside these hours it won't ring. You'll get a notification with the notes instead."
    const val CALLS_MORNING = "Morning call"
    const val CALLS_MORNING_SUB = "Rings to plan the day with you."
    const val CALLS_EVENING = "Evening call"
    const val CALLS_EVENING_SUB = "Rings to go over what got done and what moves to tomorrow."
    const val CALLS_AFTER_BLOCK = "Call me after a focus block"
    const val CALLS_AFTER_BLOCK_SUB = "Rings when a block ends and its task isn't done yet."
    const val CALLS_AT = "at"
    const val CALLS_TEST = "Try a test call"
    const val CALLS_TEST_SUB = "We'll ring you in about a minute."
    const val CALLS_TEST_BOOKING = "Booking…"
    /** The whole Calls block while the Assistant or AI data sharing is off is
     *  ONE line (core CallsBlockState.needsLine) with this fix. */
    const val CALLS_NEED_ASSISTANT_FIX = "Turn on"
    const val CALLS_FULL_SCREEN = "Calls can't ring over the lock screen yet."
    const val CALLS_FULL_SCREEN_FIX = "Allow"
    const val CALLS_MIC = "Calls can't hear you: microphone access is off."
    const val CALLS_MIC_FIX = "Allow"
    const val CALLS_DND = "Do Not Disturb is on, and it silences calls from Unstuck."
    const val CALLS_DND_FIX = "Let them ring"

    // ── Assistant & privacy ──
    const val AI_ASSISTANT = "AI Assistant"
    const val AI_ASSISTANT_SUB = "Off hides the Assistant, Talk and calls."
    /** The account's OK to share with OpenAI (core AIConsent) — iOS's words. */
    const val AI_DATA_SHARING = "AI data sharing"
    const val AI_DATA_SHARING_ON = "On. What you ask the Assistant goes to OpenAI so it can answer."
    const val AI_DATA_SHARING_OFF = "Off. The Assistant asks before anything is sent."
    const val AI_DATA_SHARING_TAG = "settings-ai-data-sharing"
    const val MEMORY_ROW = "What Unstuck remembers"
    const val DELETE_HISTORY = "Delete conversation history"

    // ── Appearance ──
    const val THEME = "Theme"
    val THEME_OPTIONS = listOf("System", "Light", "Dark")
    const val TEXT_SIZE = "Text size"
    const val TEXT_SIZE_SUB = "On top of your phone's own text size."
    const val APPEARANCE_NOTE = "Areas and tags live on Tasks. Focus options live on the Focus screen."
}

/** Where an `unstuck://settings` link lands. */
sealed interface SettingsLinkTarget {
    /** The hub (the bare link, and any section this build doesn't know). */
    data object Hub : SettingsLinkTarget
    data class Section(val section: SettingsSection) : SettingsLinkTarget
    /** The feedback sheet, over the hub. */
    data object Feedback : SettingsLinkTarget
    /** The Tasks tab with the Areas & tags sheet open. */
    data object AreasAndTags : SettingsLinkTarget
    /** Focus options live on the Focus screen: the live session's, else the hub. */
    data object Focus : SettingsLinkTarget
}

/**
 * Resolve an `unstuck://settings…` link, or null when it isn't one.
 *
 * Accepts the bare link, the path form (`unstuck://settings/people`, which
 * older builds and the assistant sent) and the query form
 * (`unstuck://settings?section=People`, what iOS and the web already route —
 * once this build is the floor on the Play store the server can send it).
 * The section is matched case-insensitively through [settingsSectionAlias],
 * so every old section name still lands somewhere sensible.
 */
fun settingsLinkTarget(link: String): SettingsLinkTarget? {
    val prefix = "unstuck://settings"
    val trimmed = link.trim()
    if (!trimmed.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
    val rest = trimmed.substring(prefix.length)
    if (rest.isNotEmpty() && rest[0] !in "/?#") return null     // e.g. unstuck://settingsfoo
    val beforeFragment = rest.substringBefore('#')
    val path = beforeFragment.substringBefore('?').trim('/')
    val query = beforeFragment.substringAfter('?', "")
    val fromQuery = query.split('&').firstNotNullOfOrNull { pair ->
        if (pair.substringBefore('=').equals("section", ignoreCase = true)) decodeLinkPart(pair.substringAfter('=', "")) else null
    }?.trim()?.takeIf { it.isNotEmpty() }
    val key = fromQuery ?: decodeLinkPart(path).trim().takeIf { it.isNotEmpty() } ?: return SettingsLinkTarget.Hub
    return settingsSectionAlias(key)
}

/**
 * Every name a section has ever had (plan §4 "Deep links"). One matching rule
 * on all three platforms (copy canon §5): lowercase, "&" → "and", then drop
 * everything that isn't a letter or a digit — so spaces, dashes, underscores
 * and "+" all fall out, and "Areas & tags", "areas-and-tags", "areas_tags" and
 * "AreasTags" are one key. The names below are written in that reduced form
 * (anything else never matches). Unknown → the hub.
 */
fun settingsSectionAlias(raw: String): SettingsLinkTarget {
    return when (settingsAliasKey(raw)) {
        "notifications", "notification", "notificationsandcalls", "notificationscalls",
        "calls", "call", "callsfromunstuck", "reminders", "reminder" ->
            SettingsLinkTarget.Section(SettingsSection.NOTIFICATIONS)
        "assistant", "assistantandprivacy", "assistantprivacy", "ai", "aiassistant", "aidatasharing",
        "privacy", "memory", "knows", "whatunstuckknows" ->
            SettingsLinkTarget.Section(SettingsSection.ASSISTANT)
        // One level deeper than the other platforms: straight onto the screen.
        "remembers", "whatunstuckremembers", "facts" ->
            SettingsLinkTarget.Section(SettingsSection.MEMORY)
        "interface", "appearance", "accessibility", "a11y", "theme", "textsize", "display" ->
            SettingsLinkTarget.Section(SettingsSection.APPEARANCE)
        "people", "connections", "circle", "trustedcircle", "peopleyousharewith", "sharing" ->
            SettingsLinkTarget.Section(SettingsSection.PEOPLE)
        "account", "backup", "export", "sync", "profile", "password", "delete" ->
            SettingsLinkTarget.Section(SettingsSection.ACCOUNT)
        "feedback", "sendfeedback" -> SettingsLinkTarget.Feedback
        "areas", "area", "tags", "tag", "areasandtags", "areastags" -> SettingsLinkTarget.AreasAndTags
        "focus", "sound", "sounds" -> SettingsLinkTarget.Focus
        else -> SettingsLinkTarget.Hub
    }
}

/** The reduced form a section name is matched in (see [settingsSectionAlias]). */
internal fun settingsAliasKey(raw: String): String =
    raw.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

private fun decodeLinkPart(s: String): String =
    runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
