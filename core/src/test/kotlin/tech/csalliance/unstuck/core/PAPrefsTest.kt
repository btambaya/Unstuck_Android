package tech.csalliance.unstuck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.PAPrefsLogic
import tech.csalliance.unstuck.core.logic.RITUAL_LABELS
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.RitualPrefs

// Port of the pa-prefs rules the web + iOS test (parseRitualPrefs, the
// defaults, missing-key fill, the 200-cap on dismissals, the set_ritual name
// gate). The JSON shapes must match the web's localStorage / the pa_rituals
// column so every platform reads every other platform's value.
class PAPrefsTest {

    @Test fun `defaults are morning + evening on, the weekly rituals opt-in`() {
        assertEquals(RitualPrefs(morning = true, evening = true, friday = false, sunday = false), RitualPrefs.DEFAULTS)
        assertEquals(listOf("morning", "evening", "friday", "sunday"), RitualKey.entries.map { it.raw })
        assertEquals(listOf("Morning briefing", "Evening sweep", "Friday review", "Sunday runway"), RITUAL_LABELS.map { it.label })
    }

    @Test fun `parseRitualPrefs fills missing keys from the defaults and drops unknown or non-boolean ones`() {
        val partial = Json.parseToJsonElement("""{"friday":true,"bogus":1,"sunday":"yes"}""")
        assertEquals(RitualPrefs(morning = true, evening = true, friday = true, sunday = false), PAPrefsLogic.parseRitualPrefs(partial))
        assertEquals(RitualPrefs(morning = false, evening = true), PAPrefsLogic.parseRitualPrefs("""{"morning":false}"""))
    }

    @Test fun `parseRitualPrefs is null when nothing usable is there`() {
        assertNull(PAPrefsLogic.parseRitualPrefs(null as String?))
        assertNull(PAPrefsLogic.parseRitualPrefs(""))
        assertNull(PAPrefsLogic.parseRitualPrefs("{}"))
        assertNull(PAPrefsLogic.parseRitualPrefs("[true]"))
        assertNull(PAPrefsLogic.parseRitualPrefs("not json"))
        assertNull(PAPrefsLogic.parseRitualPrefs(JsonNull))
        assertNull(PAPrefsLogic.parseRitualPrefs(JsonPrimitive("morning")))
        assertNull(PAPrefsLogic.parseRitualPrefs("""{"morning":"true"}"""))
    }

    @Test fun `decodeRituals falls back to defaults`() {
        assertEquals(RitualPrefs.DEFAULTS, PAPrefsLogic.decodeRituals(null))
        assertEquals(RitualPrefs.DEFAULTS, PAPrefsLogic.decodeRituals("garbage"))
        assertEquals(RitualPrefs(sunday = true), PAPrefsLogic.decodeRituals("""{"sunday":true}"""))
    }

    @Test fun `encodeRituals always emits all four keys (never trips the default-omission gotcha)`() {
        val text = PAPrefsLogic.encodeRituals(RitualPrefs.DEFAULTS)
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("morning", "evening", "friday", "sunday"), o.keys)
        assertEquals(RitualPrefs.DEFAULTS, PAPrefsLogic.parseRitualPrefs(text))
        val flipped = RitualPrefs(morning = false, evening = false, friday = true, sunday = true)
        assertEquals(flipped, PAPrefsLogic.parseRitualPrefs(PAPrefsLogic.ritualsJson(flipped)))
    }

    @Test fun `get and with address each key`() {
        var p = RitualPrefs.DEFAULTS
        for (k in RitualKey.entries) {
            p = p.with(k, !p[k])
        }
        assertEquals(RitualPrefs(morning = false, evening = false, friday = true, sunday = true), p)
        assertTrue(p[RitualKey.FRIDAY])
        assertFalse(p[RitualKey.MORNING])
    }

    @Test fun `setRitual by name returns null for an unknown ritual`() {
        assertEquals(RitualPrefs(friday = true), PAPrefsLogic.setRitual(RitualPrefs.DEFAULTS, "friday", true))
        assertEquals(RitualPrefs(morning = false), PAPrefsLogic.setRitual(RitualPrefs.DEFAULTS, "morning", false))
        assertNull(PAPrefsLogic.setRitual(RitualPrefs.DEFAULTS, "saturday", true))
        assertNull(RitualKey.fromRaw("Morning"))
    }

    // ── dismissed moment ids ───────────────────────────────────────────────

    @Test fun `dismissals round-trip as a JSON string array`() {
        val text = PAPrefsLogic.encodeDismissed(listOf("evening-sweep:2026-08-29", "quiet-win:2026-08-29"))
        assertEquals("""["evening-sweep:2026-08-29","quiet-win:2026-08-29"]""", text)
        assertEquals(listOf("evening-sweep:2026-08-29", "quiet-win:2026-08-29"), PAPrefsLogic.parseDismissed(text))
        assertEquals(emptyList<String>(), PAPrefsLogic.parseDismissed(null))
        assertEquals(emptyList<String>(), PAPrefsLogic.parseDismissed("{}"))
        assertEquals(listOf("a"), PAPrefsLogic.parseDismissed("""["a", 1, null]"""))
    }

    @Test fun `appendDismissed is idempotent and keeps the newest 200`() {
        val one = PAPrefsLogic.appendDismissed(emptyList(), "m1")
        assertEquals(listOf("m1"), one)
        assertEquals(listOf("m1"), PAPrefsLogic.appendDismissed(one, "m1"))
        var ids = emptyList<String>()
        for (i in 0 until 250) ids = PAPrefsLogic.appendDismissed(ids, "m$i")
        assertEquals(200, ids.size)
        assertEquals("m50", ids.first())
        assertEquals("m249", ids.last())
        // The encoder caps too (an over-long list handed in from elsewhere).
        val big = (0 until 300).map { "x$it" }
        assertEquals(200, PAPrefsLogic.parseDismissed(PAPrefsLogic.encodeDismissed(big)).size)
    }
}
