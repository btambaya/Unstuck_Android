package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.MomentTone
import tech.csalliance.unstuck.core.logic.StruggleProfile
import tech.csalliance.unstuck.core.logic.Tone
import tech.csalliance.unstuck.core.logic.ToneFact
import tech.csalliance.unstuck.core.logic.factCitation
import tech.csalliance.unstuck.core.logic.goldenHours
import tech.csalliance.unstuck.core.logic.quietWinLine
import tech.csalliance.unstuck.core.logic.struggleProfile
import tech.csalliance.unstuck.core.logic.toneFromFacts
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.time.DAY_MS

// Ported from lib/assistant/insights.test.ts (via AssistantInsightsTests.swift).
class AssistantInsightsTest {

    private var seq = 0
    private fun nextSeq(): Int { seq += 1; return seq }

    // All sessions live in August 2026, local time; FIXED_NOW sits just past them so
    // everything falls inside the 60-day window unless a test says otherwise.
    private val FIXED_NOW: Long = localMillis(2026, 8, 28, 18, 0)

    /** A session STARTING at the given local day/hour/minute, ending actualSec later. */
    private fun sessAt(day: Int, hour: Int, min: Int, actualSec: Int, estimateMin: Int? = null, completedAt: String? = null): Session {
        val start = localMillis(2026, 8, day, hour, min)
        return Session(id = "s${nextSeq()}", taskName = "Deep work", estimateMin = estimateMin, actualSec = actualSec,
            completedAt = completedAt ?: iso(start + actualSec * 1000L))
    }

    private fun repeatN(n: Int, make: (Int) -> Session): List<Session> = (0 until n).map(make)

    private fun ago(days: Double): String = iso(System.currentTimeMillis() - (days * DAY_MS).toLong())

    private fun log(reason: String = "phone call", action: ReasonAction = ReasonAction.PAUSE, at: String? = null, durationSec: Int? = null) =
        ReasonLog(id = "r${nextSeq()}", reason = reason, action = action, at = at ?: ago(2.0), durationSec = durationSec)

    private fun repeat5(make: () -> ReasonLog): List<ReasonLog> = (0 until 5).map { make() }

    // ---- goldenHours

    @Test fun `finds a clear morning cluster and phrases it like a human`() {
        val sessions = repeatN(8) { sessAt(10 + it, 9, 15, 1800) } + repeatN(6) { sessAt(10 + it, 10, 5, 1800) }
        val g = goldenHours(sessions, FIXED_NOW)
        assertNotNull(g)
        assertEquals(listOf(9, 10), g!!.hours)
        assertEquals("mornings around 9–11", g.label)
        assertEquals(1.0, g.share, 1e-5)
        assertEquals("Deep focus lands best around 9–11am (from 14 real sessions)", g.factText)
    }

    @Test fun `fewer than ten qualifying sessions is null`() {
        val nine = repeatN(9) { sessAt(10 + it, 9, 0, 1800) }
        assertNull(goldenHours(nine, FIXED_NOW))
        assertNull(goldenHours(emptyList(), FIXED_NOW))
    }

    @Test fun `sessions older than 60 days never qualify`() {
        val recent = repeatN(7) { sessAt(10 + it, 9, 0, 1800) }
        // June 1 is ~88 days before FIXED_NOW — outside the window.
        val june1 = iso(localMillis(2026, 6, 1, 10, 0))
        val ancient = repeatN(5) { sessAt(10 + it, 9, 0, 1800, completedAt = june1) }
        assertNull(goldenHours(recent + ancient, FIXED_NOW))
    }

    @Test fun `share is the band's fraction of focused seconds, not of sessions`() {
        val sessions = repeatN(6) { sessAt(10 + it, 9, 0, 3600) } +
            repeatN(5) { sessAt(10 + it, 10, 0, 3600) } +
            repeatN(3) { sessAt(10 + it, 20, 0, 3600) }
        val g = goldenHours(sessions, FIXED_NOW)!!
        assertEquals(listOf(9, 10), g.hours)
        assertEquals(11.0 / 14.0, g.share, 1e-5)
        assertTrue(g.factText.contains("from 14 real sessions"))
    }

    @Test fun `weights by actualSec and the START hour drives the band`() {
        // Four 2-hour blocks starting 9:00 (completedAt lands at 11:00 — the
        // START hour must drive the band) vs ten 10-minute dabs at 20:30.
        val sessions = repeatN(4) { sessAt(10 + it, 9, 0, 7200) } + repeatN(10) { sessAt(10 + it, 20, 30, 600) }
        val g = goldenHours(sessions, FIXED_NOW)!!
        assertEquals(listOf(9, 10), g.hours)
        assertEquals(28800.0 / 34800.0, g.share, 1e-5)
    }

    @Test fun `a forgotten timer weighs its counted length at the hour it really started`() {
        // Ten 20-min sessions at 9:00 (200 min) vs one timer started 22:00 on the
        // 20th and stopped 34 h later (no plan → counts 4 h). Its start is 22:00 —
        // the hour the heatmap shows it — not 4 h before it was stopped (04:00).
        val tens = repeatN(10) { sessAt(10 + it, 9, 0, 1200) }
        val runaway = sessAt(20, 22, 0, 34 * 3600)
        val g = goldenHours(tens + runaway, FIXED_NOW)!!
        assertEquals(listOf(22, 23), g.hours)
        assertEquals(240.0 / 440.0, g.share, 1e-5)
    }

    @Test fun `grows to a three-hour band when the neighbouring hour carries weight`() {
        val sessions = repeatN(4) { sessAt(10 + it, 13, 0, 1800) } +
            repeatN(4) { sessAt(10 + it, 14, 0, 1800) } +
            repeatN(4) { sessAt(10 + it, 15, 0, 1800) }
        val g = goldenHours(sessions, FIXED_NOW)!!
        assertEquals(listOf(13, 14, 15), g.hours)
        assertEquals("early afternoons around 13–16", g.label)
        assertTrue(g.factText.contains("1–4pm"))
        assertEquals(1.0, g.share, 1e-5)
    }

    @Test fun `sessions with no estimateMin still count`() {
        val sessions = repeatN(7) { sessAt(10 + it, 9, 0, 1800) } + repeatN(7) { sessAt(10 + it, 10, 0, 1800, estimateMin = 30) }
        val g = goldenHours(sessions, FIXED_NOW)
        assertNotNull(g)
        assertTrue(g!!.factText.contains("from 14 real sessions"))
    }

    // ---- struggleProfile

    @Test fun `no declared struggles is an empty honest profile`() {
        val p = struggleProfile(emptyList(), listOf(log(), log()))
        assertEquals(StruggleProfile(primary = null, confirmed = false, line = null, offerFirstStep = false), p)
    }

    @Test fun `a declared struggle always yields its warm line, unconfirmed without evidence`() {
        val p = struggleProfile(listOf("Starting"), emptyList())
        assertEquals("Starting", p.primary)
        assertFalse(p.confirmed)
        assertEquals("Their hard part is Starting — offer a tiny first step before anything else.", p.line)
        assertTrue(p.offerFirstStep)
    }

    @Test fun `Switching confirms on five recent switch logs and not on four`() {
        val distracted = { log(reason = "got distracted by email", action = ReasonAction.SWITCH) }
        assertTrue(struggleProfile(listOf("Switching"), repeat5(distracted)).confirmed)
        assertFalse(struggleProfile(listOf("Switching"), listOf(distracted(), distracted(), distracted(), distracted())).confirmed)
    }

    @Test fun `evidence older than 30 days does not confirm`() {
        val old = repeat5 { log(reason = "got distracted", action = ReasonAction.SWITCH, at = ago(45.0)) }
        assertFalse(struggleProfile(listOf("Switching"), old).confirmed)
    }

    @Test fun `Sustaining confirms via long pauses, not quick breaks`() {
        val longPauses = repeat5 { log(action = ReasonAction.PAUSE, durationSec = 900) }
        assertTrue(struggleProfile(listOf("Sustaining"), longPauses).confirmed)
        val quick = repeat5 { log(action = ReasonAction.PAUSE, durationSec = 60) }
        assertFalse(struggleProfile(listOf("Sustaining"), quick).confirmed)
    }

    @Test fun `Starting confirms on its own vocabulary, not on someone else's logs`() {
        val stuck = repeat5 { log(reason = "couldn’t get started, kept avoiding it") }
        assertTrue(struggleProfile(listOf("Starting"), stuck).confirmed)
        // A pile of switch logs is Switching evidence — it must NOT confirm Starting.
        val switchy = repeat5 { log(reason = "got distracted", action = ReasonAction.SWITCH) }
        assertFalse(struggleProfile(listOf("Starting"), switchy).confirmed)
    }

    @Test fun `offerFirstStep fires whenever Starting is declared, even behind another primary`() {
        val p = struggleProfile(listOf("Switching", "Starting"), emptyList())
        assertEquals("Switching", p.primary)
        assertTrue(p.offerFirstStep)
    }

    @Test fun `normalizes casing to the canonical label`() {
        val p = struggleProfile(listOf("starting"), emptyList())
        assertEquals("Starting", p.primary)
        assertTrue(p.offerFirstStep)
    }

    @Test fun `an unknown struggle gets the generic line`() {
        assertEquals("Their hard part is Finishing — meet them there before anything else.", struggleProfile(listOf("Finishing"), emptyList()).line)
    }

    // ---- toneFromFacts

    private fun f(category: String, fact: String) = ToneFact(category, fact)

    @Test fun `gentle`() {
        assertEquals(Tone.GENTLE, toneFromFacts(listOf(f("style", "Prefers gentle nudges — suggest, never push"))))
    }

    @Test fun `honest or direct`() {
        assertEquals(Tone.HONEST, toneFromFacts(listOf(f("style", "Wants to be kept honest — direct nudges are welcome"))))
        assertEquals(Tone.HONEST, toneFromFacts(listOf(f("style", "Be direct with nudges"))))
    }

    @Test fun `minimal or barely`() {
        assertEquals(Tone.MINIMAL, toneFromFacts(listOf(f("style", "Minimal nudging — only speak up when it really matters"))))
        assertEquals(Tone.MINIMAL, toneFromFacts(listOf(f("style", "Barely any nudging, please"))))
    }

    @Test fun `defaults to gentle with no facts or no style fact`() {
        assertEquals(Tone.GENTLE, toneFromFacts(emptyList<ToneFact>()))
        assertEquals(Tone.GENTLE, toneFromFacts(listOf(f("life", "Has two kids"))))
    }

    @Test fun `an unrelated fact mentioning direct cannot hijack the tone`() {
        assertEquals(Tone.GENTLE, toneFromFacts(listOf(f("work", "Direct reports sync on Mondays"))))
    }

    @Test fun `profile facts route through their category name`() {
        val honest = ProfileFact(id = "f1", category = ProfileFactCategory.PREFERENCE, fact = "Wants to be kept honest — direct nudges are welcome",
            source = ProfileFactSource.INTERVIEW, createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z")
        assertEquals(Tone.HONEST, toneFromFacts(listOf(honest)))
        val person = ProfileFact(id = "f2", category = ProfileFactCategory.PERSON, fact = "Sam — direct manager", source = ProfileFactSource.CHAT,
            createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z")
        assertEquals(Tone.GENTLE, toneFromFacts(listOf(person)))
    }

    @Test fun `tone maps onto the moments engine's tone and the wire`() {
        assertEquals(MomentTone.HONEST, Tone.HONEST.toMomentTone())
        assertEquals(Tone.MINIMAL, Tone.fromWire("minimal"))
        assertEquals(Tone.GENTLE, Tone.fromWire("bogus"))
    }

    // ---- quietWinLine

    @Test fun `under three moves is null`() {
        assertNull(quietWinLine("Tax return", 0, Tone.GENTLE))
        assertNull(quietWinLine("Tax return", 2, Tone.HONEST))
    }

    @Test fun `each tone gets its own line, dodges acknowledged without shame`() {
        assertEquals("“Tax return” finally happened — it dodged you 4 times, and you got it anyway.", quietWinLine("Tax return", 4, Tone.GENTLE))
        assertEquals("That’s “Tax return” done after 4 dodges. The hard kind of done.", quietWinLine("Tax return", 4, Tone.HONEST))
        assertEquals("“Tax return” — done, after 4 tries.", quietWinLine("Tax return", 4, Tone.MINIMAL))
    }

    // ---- factCitation

    @Test fun `date-only createdAt renders as a short local date`() {
        assertEquals("“Mornings are the good hours” (you told me 12 Aug)", factCitation("Mornings are the good hours", "2026-08-12"))
    }

    @Test fun `full timestamps render day plus short month, no zero padding`() {
        // No trailing Z → parsed as local time, deterministic in any zone.
        assertEquals("“Fridays are for admin” (you told me 3 Jan)", factCitation("Fridays are for admin", "2026-01-03T09:30:00"))
    }

    @Test fun `unparseable dates fall back to the bare quote`() {
        assertEquals("“Loves tea”", factCitation("Loves tea", "whenever"))
        val f = ProfileFact(id = "f", category = ProfileFactCategory.CONTEXT, fact = "Loves tea", source = ProfileFactSource.CHAT,
            createdAt = "2026-08-12T10:00:00Z", updatedAt = "2026-08-12T10:00:00Z")
        assertEquals("“Loves tea” (you told me 12 Aug)", factCitation(f))
    }
}
