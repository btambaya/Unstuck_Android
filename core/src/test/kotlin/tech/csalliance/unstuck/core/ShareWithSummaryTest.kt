package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.SHARE_SUMMARY_MAX
import tech.csalliance.unstuck.core.logic.SharePick
import tech.csalliance.unstuck.core.logic.shareWithLabel
import tech.csalliance.unstuck.core.logic.sharePicksInRosterOrder
import tech.csalliance.unstuck.core.logic.sharePreCreateLine
import tech.csalliance.unstuck.core.logic.shareWithSummary
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.ShareLevel.ASSIGN
import tech.csalliance.unstuck.core.model.ShareLevel.PARTNER
import tech.csalliance.unstuck.core.model.ShareLevel.VIEW

// The New task sheet's one "Share with…" row: the summary it shows for what
// was picked on the pre-create Share screen, the roster ordering behind it, the
// accessibility label and the honest pre-create lines.
class ShareWithSummaryTest {

    private fun p(name: String, level: ShareLevel = PARTNER) = SharePick(name, level)

    // ── 0 / 1 / 2 / many ────────────────────────────────────────────────────

    @Test fun `nothing picked reads only you`() {
        assertEquals("Only you", shareWithSummary(emptyList()))
    }

    @Test fun `one person shows their first name and grade`() {
        assertEquals("James · can edit", shareWithSummary(listOf(p("James Wilson"))))
        assertEquals("Anna · can view", shareWithSummary(listOf(p("Anna", VIEW))))
        assertEquals("Zubair · handed over", shareWithSummary(listOf(p("Zubair", ASSIGN))))
    }

    @Test fun `two people at one grade share the grade`() {
        assertEquals("James, Anna · can edit", shareWithSummary(listOf(p("James"), p("Anna"))))
        assertEquals("James, Anna · can view", shareWithSummary(listOf(p("James", VIEW), p("Anna", VIEW))))
    }

    @Test fun `mixed grades name each person's grade`() {
        assertEquals("James · edit, Anna · view", shareWithSummary(listOf(p("James"), p("Anna", VIEW))))
        assertEquals("Al · view, Bo · handed over", shareWithSummary(listOf(p("Al", VIEW), p("Bo", ASSIGN))))
    }

    @Test fun `three short names still fit in full`() {
        assertEquals("Al, Bo, Cy · can edit", shareWithSummary(listOf(p("Al"), p("Bo"), p("Cy"))))
    }

    @Test fun `many people compact to the first name plus a count`() {
        val four = listOf(p("James"), p("Anna"), p("Zubair"), p("Maya"))
        assertEquals("James + 3 more · can edit", shareWithSummary(four))
        val mixed = listOf(p("James"), p("Anna", VIEW), p("Zubair"), p("Maya"))
        assertEquals("grades differ → no single grade to show", "James + 3 more", shareWithSummary(mixed))
        val twoMixedLong = listOf(p("Christopher"), p("Anna", VIEW))
        assertEquals("Christopher + 1 more", shareWithSummary(twoMixedLong))
    }

    // ── long names never wrap ───────────────────────────────────────────────

    @Test fun `a long single name is clipped with an ellipsis and keeps the grade`() {
        val s = shareWithSummary(listOf(p("Bartholomew-Alexandros Papadopoulos")))
        assertEquals("Bartholomew-Alex… · can edit", s)
        assertEquals(SHARE_SUMMARY_MAX, s.length)
    }

    @Test fun `a long first name among many is clipped before the count`() {
        val s = shareWithSummary(listOf(p("Maximilianus-Ferdinand"), p("Anna"), p("Bo")))
        assertEquals("Maximil… + 2 more · can edit", s)
        assertTrue(s.length <= SHARE_SUMMARY_MAX)
    }

    @Test fun `every summary stays within the row's budget`() {
        val names = listOf("A", "Jo", "James", "Christopher", "Bartholomew-Alexandros", "x".repeat(60))
        val levels = listOf(PARTNER, VIEW, ASSIGN)
        for (n in 1..12) for (name in names) for (lv in levels) {
            val picks = List(n) { i -> p(if (i == 0) name else names[i % names.size], levels[(i + lv.ordinal) % levels.size]) }
            val s = shareWithSummary(picks)
            assertTrue("'$s' (${s.length}) for $n × $name", s.length <= SHARE_SUMMARY_MAX)
            assertTrue("never wraps: '$s'", '\n' !in s)
        }
    }

    @Test fun `an email-shaped or blank name degrades gracefully`() {
        assertEquals("maya · can view", shareWithSummary(listOf(p("maya@example.com", VIEW))))
        assertEquals("Someone · can edit", shareWithSummary(listOf(p("  "))))
    }

    // ── roster order + names ────────────────────────────────────────────────

    private fun member(id: String, uid: String?, name: String?, status: CircleStatus = CircleStatus.ACTIVE) =
        CircleMember(id, null, "view", status, null, uid, name, "2026-09-24T09:00:00Z")

    @Test fun `picks follow the roster order and unknown picks go last as someone`() {
        val roster = listOf(
            member("c1", "u1", "Maya Chen"),
            member("c2", "u2", "Zubair"),
            member("c3", null, null, CircleStatus.INVITED),
            member("c4", "u4", "James Wilson"),
        )
        val picks = linkedMapOf("gone" to VIEW, "u4" to PARTNER, "u1" to PARTNER)
        assertEquals(
            listOf(SharePick("Maya Chen", PARTNER), SharePick("James Wilson", PARTNER), SharePick("Someone", VIEW)),
            sharePicksInRosterOrder(roster, picks),
        )
        // "Maya · edit, James · edit, Someone · view" is too long for the row.
        assertEquals("Maya + 2 more", shareWithSummary(sharePicksInRosterOrder(roster, picks)))
        assertEquals(emptyList<SharePick>(), sharePicksInRosterOrder(roster, emptyMap()))
    }

    // ── accessibility + honest lines ────────────────────────────────────────

    @Test fun `the row's accessibility label names the summary`() {
        assertEquals("Share with, Only you", shareWithLabel(shareWithSummary(emptyList())))
        assertEquals("Share with, James · can edit", shareWithLabel(shareWithSummary(listOf(p("James")))))
    }

    @Test fun `pre-create lines say what will happen, never that it was shared`() {
        assertEquals("Maya can edit once you add the task.", sharePreCreateLine("Maya Chen", PARTNER))
        assertEquals("Maya can view once you add the task.", sharePreCreateLine("Maya Chen", VIEW))
        assertEquals("Maya gets it as their task once you add it — you keep view.", sharePreCreateLine("Maya Chen", ASSIGN))
        assertEquals("Maya won't get this task.", sharePreCreateLine("Maya Chen", null))
        for (lv in listOf(PARTNER, VIEW, ASSIGN, null)) assertTrue(!sharePreCreateLine("Maya", lv).startsWith("Shared"))
    }
}
