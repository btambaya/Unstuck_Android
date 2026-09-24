package tech.csalliance.unstuck.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.NotificationLevel
import tech.csalliance.unstuck.SettingsState
import tech.csalliance.unstuck.SettingsStore
import tech.csalliance.unstuck.TextSize
import tech.csalliance.unstuck.core.model.ThemePref
import tech.csalliance.unstuck.ui.settings.SettingsLinkTarget.AreasAndTags
import tech.csalliance.unstuck.ui.settings.SettingsLinkTarget.Feedback
import tech.csalliance.unstuck.ui.settings.SettingsLinkTarget.Focus
import tech.csalliance.unstuck.ui.settings.SettingsLinkTarget.Hub
import tech.csalliance.unstuck.ui.settings.SettingsLinkTarget.Section

/** The slim Settings (plan 2026-09-24): the hub, the settings links (every
 *  old name still lands), the fixed test ids, Text size and the plain copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SlimSettingsTest {

    // ── the hub ──

    @Test fun `the hub is Account, four screens, two actions`() {
        assertEquals(
            listOf("Notifications & calls", "Assistant & privacy", "People", "Appearance"),
            SETTINGS_HUB_ROWS.map { it.row },
        )
        assertEquals("Send feedback", SettingsCopy.SEND_FEEDBACK)
        assertEquals("Replay the tour", SettingsCopy.REPLAY_TOUR)
        assertEquals("Unstuck 0.5.27 (111)", SettingsCopy.version("0.5.27", 111))
        assertEquals("https://unstucknow.io/terms", SettingsCopy.TERMS_URL)
        assertEquals("https://unstucknow.io/privacy", SettingsCopy.PRIVACY_URL)
    }

    /** Fixed ids, never derived from a label (plan §4 "UI tests"). */
    @Test fun `test ids are fixed`() {
        assertEquals("settings-row-account", SettingsSection.ACCOUNT.testTag)
        assertEquals("settings-row-notifications", SettingsSection.NOTIFICATIONS.testTag)
        assertEquals("settings-row-assistant", SettingsSection.ASSISTANT.testTag)
        assertEquals("settings-row-people", SettingsSection.PEOPLE.testTag)
        assertEquals("settings-row-appearance", SettingsSection.APPEARANCE.testTag)
        assertEquals("settings-row-feedback", SettingsCopy.FEEDBACK_TAG)
        assertEquals("settings-row-tour", SettingsCopy.TOUR_TAG)
    }

    /** No jargon titles ("A11y", "Memory") — every app-bar title is plain words. */
    @Test fun `screen titles are plain words`() {
        assertEquals(
            listOf("Account", "Notifications & calls", "Assistant & privacy", "What Unstuck remembers", "People you share with", "Appearance"),
            SettingsSection.entries.map { it.title },
        )
        for (s in SettingsSection.entries) assertFalse(s.title, s.title.contains("A11y") || s.title == "Memory" || s.title.contains("_"))
    }

    // ── settings links ──

    @Test fun `the bare link is the hub — the server's invite and share pushes rely on it`() {
        assertEquals(Hub, settingsLinkTarget("unstuck://settings"))
        assertEquals(Hub, settingsLinkTarget("unstuck://settings/"))
        assertEquals(Hub, settingsLinkTarget("unstuck://settings?"))
        assertEquals(Hub, settingsLinkTarget("unstuck://settings?section="))
        assertEquals(Hub, settingsLinkTarget("unstuck://settings?section=nonsense"))
    }

    @Test fun `not a settings link`() {
        assertNull(settingsLinkTarget("unstuck://today"))
        assertNull(settingsLinkTarget("unstuck://settingsfoo"))
        assertNull(settingsLinkTarget("unstuck://notifications"))
    }

    @Test fun `the path forms older builds and the assistant sent still work`() {
        assertEquals(Section(SettingsSection.PEOPLE), settingsLinkTarget("unstuck://settings/people"))
        assertEquals(AreasAndTags, settingsLinkTarget("unstuck://settings/areas"))
    }

    @Test fun `the query form reads section case-insensitively`() {
        assertEquals(Section(SettingsSection.PEOPLE), settingsLinkTarget("unstuck://settings?section=People"))
        assertEquals(Section(SettingsSection.PEOPLE), settingsLinkTarget("unstuck://settings?section=people"))
        assertEquals(Section(SettingsSection.NOTIFICATIONS), settingsLinkTarget("unstuck://settings?section=Notifications"))
        assertEquals(Section(SettingsSection.NOTIFICATIONS), settingsLinkTarget("unstuck://settings?foo=1&SECTION=NOTIFICATIONS#x"))
        assertEquals(AreasAndTags, settingsLinkTarget("unstuck://settings?section=Areas%20%26%20tags"))
        assertEquals(AreasAndTags, settingsLinkTarget("unstuck://settings?section=areas-and-tags"))
    }

    /** Every name a section ever had lands somewhere sensible (plan §4). */
    @Test fun `old section names are aliases`() {
        for (k in listOf("notifications", "notification", "calls", "Calls", "Notifications & calls")) {
            assertEquals(k, Section(SettingsSection.NOTIFICATIONS), settingsSectionAlias(k))
        }
        for (k in listOf("assistant", "ai", "AI", "memory", "knows", "Assistant & privacy", "privacy")) {
            assertEquals(k, Section(SettingsSection.ASSISTANT), settingsSectionAlias(k))
        }
        for (k in listOf("interface", "Interface", "appearance", "accessibility", "theme")) {
            assertEquals(k, Section(SettingsSection.APPEARANCE), settingsSectionAlias(k))
        }
        for (k in listOf("people", "connections", "circle")) {
            assertEquals(k, Section(SettingsSection.PEOPLE), settingsSectionAlias(k))
        }
        for (k in listOf("backup", "export", "account", "Account")) {
            assertEquals(k, Section(SettingsSection.ACCOUNT), settingsSectionAlias(k))
        }
        assertEquals(Feedback, settingsSectionAlias("feedback"))
        for (k in listOf("focus", "sound")) assertEquals(k, Focus, settingsSectionAlias(k))
        for (k in listOf("areas", "tags", "Areas & tags", "areas and tags", "areas-tags")) assertEquals(k, AreasAndTags, settingsSectionAlias(k))
        assertEquals(Hub, settingsSectionAlias("insights"))
    }

    // ── appearance: one text size ──

    @Test fun `text size replaces density and larger type`() {
        assertEquals(listOf("Smaller", "Default", "Larger"), TextSize.entries.map { it.label })
        assertEquals(TextSize.LARGER, TextSize.fromLegacy("REGULAR", largerType = true))
        assertEquals(TextSize.LARGER, TextSize.fromLegacy("COMPACT", largerType = true))
        assertEquals(TextSize.LARGER, TextSize.fromLegacy("COMFY", largerType = false))
        assertEquals(TextSize.SMALLER, TextSize.fromLegacy("COMPACT", largerType = false))
        assertEquals(TextSize.DEFAULT, TextSize.fromLegacy("REGULAR", largerType = false))
        assertEquals(TextSize.DEFAULT, TextSize.fromLegacy(null, largerType = false))
        assertEquals(1.0f, SettingsState().fontScale)
        assertTrue(SettingsState(textSize = TextSize.LARGER).fontScale > 1f)
        assertTrue(SettingsState(textSize = TextSize.SMALLER).fontScale < 1f)
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("unstuck.settings", Context.MODE_PRIVATE)
    private fun store(): SettingsStore {
        prefs().edit().clear().commit()
        return SettingsStore(ApplicationProvider.getApplicationContext())
    }

    /** A device that set the old controls keeps its size; the old keys are
     *  read for that alone and are never wiped (plan: stop reading, don't wipe). */
    @Test fun `the first text size comes from the old controls, which are left alone`() {
        val s = store()
        prefs().edit().putString("density", "COMFY").putBoolean("largerType", false)
            .putString("accent", "PERIWINKLE_ROSE").putBoolean("highContrast", true).putBoolean("reduceMotion", true).commit()
        assertEquals(TextSize.LARGER, s.load().textSize)
        s.save(s.load().copy(textSize = TextSize.SMALLER))
        assertEquals(TextSize.SMALLER, s.load().textSize)
        // Nothing wiped.
        assertEquals("COMFY", prefs().getString("density", null))
        assertEquals("PERIWINKLE_ROSE", prefs().getString("accent", null))
        assertTrue(prefs().getBoolean("highContrast", false))
        assertTrue(prefs().getBoolean("reduceMotion", false))
    }

    @Test fun `theme labels round-trip`() {
        for (t in ThemePref.entries) assertEquals(t, themeFromLabel(themeLabel(t)))
        assertEquals(listOf("System", "Light", "Dark"), SettingsCopy.THEME_OPTIONS)
    }

    // ── background noise = the speaker button ──

    @Test fun `background noise is on or off, and an old pink reads as on`() {
        assertFalse(SettingsState(ambient = "off").ambientOn)
        assertTrue(SettingsState(ambient = "brown").ambientOn)
        assertTrue(SettingsState(ambient = "pink").ambientOn)
        assertEquals("brown", SettingsState.AMBIENT_ON)
    }

    // ── notifications & calls copy ──

    @Test fun `each level has one plain line`() {
        assertEquals("Only the reminders you set, and a recap.", NotificationLevel.CALM.blurb)
        assertEquals("Also a nudge when a task should start, a check-in if you've paused a while, and a morning summary.", NotificationLevel.BALANCED.blurb)
        assertEquals("Also a second nudge if you haven't started 10 minutes in.", NotificationLevel.COACH.blurb)
        assertEquals("How much Unstuck checks in", SettingsCopy.LEVEL_ROW)
        assertEquals("This also sets how often the focus coach talks during a session.", SettingsCopy.LEVEL_COACH_NOTE)
        assertEquals("Remind me before a task", SettingsCopy.LEAD_ROW)
    }

    /** "Export everything" keeps its label: the privacy policy names it. */
    @Test fun `account keeps the policy's words`() {
        assertEquals("Export everything", SettingsCopy.EXPORT)
        assertEquals("Download a copy of everything you've put in Unstuck", SettingsCopy.EXPORT_SUB)
        assertEquals("Delete my account", SettingsCopy.DELETE_ACCOUNT)
    }

    /** Delete my account deletes only once the account is typed back. */
    @Test fun `delete my account needs the email typed back`() {
        assertFalse(deleteAccountConfirmed("", "maya@example.com"))
        assertFalse(deleteAccountConfirmed("maya@", "maya@example.com"))
        assertTrue(deleteAccountConfirmed(" Maya@Example.com ", "maya@example.com"))
        assertFalse(deleteAccountConfirmed("DELETE", "maya@example.com"))
        // No email on the account: DELETE, never a dead button.
        assertTrue(deleteAccountConfirmed("delete", null))
        assertTrue(deleteAccountConfirmed("DELETE", " "))
        assertFalse(deleteAccountConfirmed("", null))
    }
}
