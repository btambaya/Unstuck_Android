package tech.csalliance.unstuck.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.LifeArea

// Android audit 2026-09-23, A8 (duplicate areas) + A9 (the onboarding gate).
class OnboardingGateTest {

    private val serverSeed = listOf(
        LifeArea("s0", "Work", "indigo", 0), LifeArea("s1", "Personal", "coral", 1),
        LifeArea("s2", "Volunteering", "green", 2), LifeArea("s3", "Home", "amber", 3),
        LifeArea("s4", "Health", "teal", 4),
    )
    private fun ids(): () -> String { var n = 0; return { "new-${n++}" } }

    // ── the gate ──────────────────────────────────────────────────────────────

    @Test fun `no account yet is the splash, never the steps`() {
        assertNull(OnboardingGate.show(uid = null, onboarded = false, resolvedFor = null))
    }

    @Test fun `an account onboarded here goes straight to the app`() {
        assertEquals(false, OnboardingGate.show("me", onboarded = true, resolvedFor = null))
    }

    @Test fun `an account not onboarded here waits until ITS answer is in`() {
        assertNull("before the server answered", OnboardingGate.show("me", onboarded = false, resolvedFor = null))
        assertNull("another account's answer doesn't count", OnboardingGate.show("me", onboarded = false, resolvedFor = "someone-else"))
        assertEquals(true, OnboardingGate.show("me", onboarded = false, resolvedFor = "me"))
    }

    // ── "onboarded elsewhere" ─────────────────────────────────────────────────

    @Test fun `server-seeded life areas are no signal - a brand-new account stays on the steps`() {
        // The only thing a brand-new account has after its first pull is the five
        // server-seeded areas; there is no parameter for them any more.
        assertFalse(OnboardingGate.onboardedElsewhere(serverStruggles = emptyList(), interviewDoneAt = null, hasTasks = false))
        assertFalse(OnboardingGate.onboardedElsewhere(serverStruggles = null, interviewDoneAt = null, hasTasks = false))
    }

    @Test fun `struggles, the interview or any task mean it onboarded on another platform`() {
        assertTrue("iOS / Android / web picks", OnboardingGate.onboardedElsewhere(listOf("Distraction"), null, false))
        assertTrue("the assistant interview", OnboardingGate.onboardedElsewhere(emptyList(), "2026-09-01T10:00:00Z", false))
        assertTrue("web always files a first task; struggles may be empty", OnboardingGate.onboardedElsewhere(emptyList(), null, true))
    }

    // ── the area seed ─────────────────────────────────────────────────────────

    @Test fun `the default picks seed nothing - the server already created them`() {
        assertEquals(emptyList<LifeArea>(), OnboardingGate.areasToSeed(listOf("Work", "Personal", "Home"), serverSeed, ids()))
        assertEquals("even before the first pull landed", emptyList<LifeArea>(), OnboardingGate.areasToSeed(listOf("Work", "Personal", "Home", "Health"), emptyList(), ids()))
    }

    @Test fun `only picks the account lacks are added, after the server's rows`() {
        val seed = OnboardingGate.areasToSeed(listOf("Work", "Family", "Study"), serverSeed, ids())
        assertEquals(listOf("Family", "Study"), seed.map { it.name })
        assertEquals(listOf(5, 6), seed.map { it.sortOrder })
        assertEquals(listOf("blue", "violet"), seed.map { it.color })
        assertEquals(listOf("new-0", "new-1"), seed.map { it.id })
    }

    @Test fun `a name the account has is skipped ignoring case, and a pick is never added twice`() {
        val existing = serverSeed + LifeArea("x", "side project", "red", 9)
        val seed = OnboardingGate.areasToSeed(listOf("Side project", " Family ", "family", "", "WORK"), existing, ids())
        assertEquals(listOf("Family"), seed.map { it.name })
        assertEquals(listOf(10), seed.map { it.sortOrder })
    }
}
