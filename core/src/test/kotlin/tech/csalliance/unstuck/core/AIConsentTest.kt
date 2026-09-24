package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.core.logic.AIConsent.Action
import tech.csalliance.unstuck.core.logic.AIConsent.Cache
import tech.csalliance.unstuck.core.logic.AIConsent.Record
import tech.csalliance.unstuck.core.logic.AIConsent.Source
import tech.csalliance.unstuck.core.logic.CallsBlockState

/** AI data-sharing consent: the contract shared with iOS and the web — the
 *  keys, the version rule, the exact copy — plus the rules the app gates on
 *  (iOS AIConsentTests, case for case). */
class AIConsentTest {

    // ── the contract ──

    @Test fun `the contract constants`() {
        assertEquals("2026-09-24", AIConsent.VERSION)
        assertEquals("ai_consent_at", AIConsent.AT_KEY)
        assertEquals("ai_consent_version", AIConsent.VERSION_KEY)
        assertEquals("https://unstucknow.io/privacy#s9", AIConsent.PRIVACY_URL)
    }

    @Test fun `the copy is word for word`() {
        assertEquals("Your assistant uses OpenAI", AIConsent.TITLE)
        assertEquals(
            "To answer you, Unstuck sends what you type or say to the assistant — including your voice in Talk and calls — with the tasks, calendar and notes it needs, to OpenAI, our AI provider. OpenAI uses it to reply and doesn't train its models on it. You can turn this off any time in Settings.",
            AIConsent.BODY,
        )
        assertEquals("Privacy policy", AIConsent.PRIVACY_LINK_LABEL)
        assertEquals("Agree and continue", AIConsent.AGREE_LABEL)
        assertEquals("Not now", AIConsent.DECLINE_LABEL)
    }

    // ── which records count ──

    @Test fun `a matching version with a time counts`() {
        assertTrue(AIConsent.isGranted("2026-09-24T10:00:00.000Z", "2026-09-24"))
        assertTrue(Record("2026-09-24T10:00:00.000Z", AIConsent.VERSION).isGranted)
    }

    @Test fun `a missing or blank time asks`() {
        assertFalse(AIConsent.isGranted(at = null, version = AIConsent.VERSION))
        assertFalse(AIConsent.isGranted("", AIConsent.VERSION))
        assertFalse(AIConsent.isGranted("  ", AIConsent.VERSION))
        assertFalse(Record.NONE.isGranted)
    }

    @Test fun `a stale or missing version asks`() {
        // A future provider change bumps the version: yesterday's OK stops counting.
        assertFalse(AIConsent.isGranted("2026-01-01T00:00:00.000Z", "2026-01-01"))
        assertFalse(AIConsent.isGranted("2026-09-24T10:00:00.000Z", null))
        assertFalse(AIConsent.isGranted("2026-09-24T10:00:00.000Z", "2026-09-25"))
    }

    @Test fun `grant writes now in the web's ISO shape and the current version`() {
        val r = AIConsent.grant(1_790_000_000_250L)
        assertEquals("2026-09-21T14:13:20.250Z", r.at)
        assertEquals(AIConsent.VERSION, r.version)
        assertTrue(r.isGranted)
    }

    @Test fun `turning it off clears the time only`() {
        val off = AIConsent.revoked(AIConsent.grant(1_790_000_000_000L))
        assertNull(off.at)
        assertEquals(AIConsent.VERSION, off.version)
        assertFalse(off.isGranted)
    }

    // ── "Not now" ──

    @Test fun `not now on the assistant sends nothing and leaves calls alone`() {
        for (a in listOf(Action.CHAT, Action.TALK)) {
            val d = AIConsent.decline(a)
            assertFalse("$a", d.turnCallsOff)
            assertTrue("$a", d.note.contains("Nothing was sent"))
            assertTrue("$a", d.note.contains("needs your OK"))
        }
    }

    @Test fun `not now when switching calls on keeps them off`() {
        val d = AIConsent.decline(Action.CALLS_ON)
        assertFalse("nothing to turn off — the switch just doesn't move", d.turnCallsOff)
        assertTrue(d.note.contains("stay off"))
    }

    @Test fun `not now on app open with calls on turns them off and says so`() {
        val d = AIConsent.decline(Action.CALLS_ON_OPEN)
        assertTrue(d.turnCallsOff)
        assertEquals(AIConsent.CALLS_TURNED_OFF_NOTE, d.note)
        assertTrue(d.note.contains("Settings › Notifications & calls"))
    }

    @Test fun `every action has a short line`() {
        for (a in Action.entries) {
            val note = AIConsent.decline(a).note
            assertTrue("$a", note.isNotEmpty())
            assertTrue("$a: keep it short", note.length < 120)
        }
    }

    // ── app open ──

    @Test fun `calls are on only when this phone takes them and something can ring`() {
        assertTrue(AIConsent.callsAreOn(deviceSwitch = true, proactiveOn = true, hasLiveCall = false))
        assertTrue(AIConsent.callsAreOn(deviceSwitch = true, proactiveOn = false, hasLiveCall = true))
        // The switch is on by default — alone it rings nothing.
        assertFalse(AIConsent.callsAreOn(deviceSwitch = true, proactiveOn = false, hasLiveCall = false))
        // Off here: every call is declined anyway.
        assertFalse(AIConsent.callsAreOn(deviceSwitch = false, proactiveOn = true, hasLiveCall = true))
    }

    @Test fun `app open asks once when calls are on without consent`() {
        assertTrue(AIConsent.asksOnOpen(granted = false, callsOn = true, askedThisLaunch = false))
        assertFalse("once", AIConsent.asksOnOpen(granted = false, callsOn = true, askedThisLaunch = true))
        assertFalse(AIConsent.asksOnOpen(granted = true, callsOn = true, askedThisLaunch = false))
        assertFalse("nothing can ring — the first real use asks instead", AIConsent.asksOnOpen(granted = false, callsOn = false, askedThisLaunch = false))
    }

    // ── the device copy ──

    private val granted = Record("2026-09-24T10:00:00.000Z", AIConsent.VERSION)

    @Test fun `the account's answer fills an empty copy`() {
        assertEquals(Cache("u1", granted, pending = false), AIConsent.merge(null, granted, "u1", Source.STORED))
        assertFalse(AIConsent.merge(null, Record.NONE, "u1", Source.FRESH).record.isGranted)
    }

    @Test fun `a fresh answer replaces the copy`() {
        // Turned off on the web: the next /user read turns it off here too.
        val mine = Cache("u1", granted, pending = false)
        val c = AIConsent.merge(mine, AIConsent.revoked(granted), "u1", Source.FRESH)
        assertFalse(c.record.isGranted)
        assertFalse(c.pending)
    }

    @Test fun `a saved session never overrides the copy`() {
        val mine = Cache("u1", granted, pending = false)
        assertEquals(mine, AIConsent.merge(mine, Record.NONE, "u1", Source.STORED))
    }

    @Test fun `a change still on its way wins`() {
        // Agreed offline: the account's older "no" must not undo it.
        val mine = Cache("u1", granted, pending = true)
        assertEquals(mine, AIConsent.merge(mine, Record.NONE, "u1", Source.FRESH))
        // …and turned off offline: an older "yes" must not bring it back.
        val off = Cache("u1", AIConsent.revoked(granted), pending = true)
        assertEquals(off, AIConsent.merge(off, granted, "u1", Source.FRESH))
    }

    @Test fun `another account's copy is replaced`() {
        val theirs = Cache("u0", granted, pending = true)
        assertEquals(Cache("u1", Record.NONE, pending = false), AIConsent.merge(theirs, Record.NONE, "u1", Source.STORED))
    }

    @Test fun `the copy answers only for its account`() {
        val mine = Cache("u1", granted, pending = false)
        assertTrue(AIConsent.grantedFor(mine, "u1"))
        assertFalse(AIConsent.grantedFor(mine, "u2"))
        assertTrue("a call ringing before the app is up trusts it", AIConsent.grantedFor(mine, null))
        assertFalse(AIConsent.grantedFor(null, "u1"))
        assertFalse(AIConsent.grantedFor(null, null))
        val stale = Cache("u1", Record("2026-01-01T00:00:00Z", "2026-01-01"), pending = false)
        assertFalse(AIConsent.grantedFor(stale, "u1"))
    }

    @Test fun `the copy round-trips`() {
        val c = Cache("u1", granted, pending = true)
        assertEquals(c, AIConsent.decode(AIConsent.encode(c)))
        assertEquals(Cache("u1", Record.NONE, pending = false), AIConsent.decode(AIConsent.encode(Cache("u1", Record.NONE, false))))
        assertNull(AIConsent.decode(null))
        assertNull(AIConsent.decode("nope"))
    }

    // ── the Calls block in Notifications & calls ──

    @Test fun `the calls block is one line without the assistant or the OK`() {
        assertEquals(CallsBlockState.NEEDS_ASSISTANT, CallsBlockState.resolve(assistantOn = false, aiSharingOn = true, phoneSwitchOn = true))
        assertEquals(CallsBlockState.NEEDS_ASSISTANT, CallsBlockState.resolve(assistantOn = true, aiSharingOn = false, phoneSwitchOn = true))
        assertEquals(CallsBlockState.OFF, CallsBlockState.resolve(assistantOn = true, aiSharingOn = true, phoneSwitchOn = false))
        assertEquals(CallsBlockState.ON, CallsBlockState.resolve(assistantOn = true, aiSharingOn = true, phoneSwitchOn = true))
        assertEquals("Calls need the Assistant and AI data sharing.", CallsBlockState.needsLine(assistantOn = false, aiSharingOn = false))
        assertEquals("Calls need the Assistant, which is off.", CallsBlockState.needsLine(assistantOn = false, aiSharingOn = true))
        assertEquals("Calls need AI data sharing, which is off.", CallsBlockState.needsLine(assistantOn = true, aiSharingOn = false))
    }
}
