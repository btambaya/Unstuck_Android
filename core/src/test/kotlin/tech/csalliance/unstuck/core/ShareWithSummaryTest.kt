package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.NewTaskShares
import tech.csalliance.unstuck.core.logic.SHARE_SUMMARY_MAX
import tech.csalliance.unstuck.core.logic.SharePick
import tech.csalliance.unstuck.core.logic.newTaskSharePicks
import tech.csalliance.unstuck.core.logic.shareHeldEmailStatus
import tech.csalliance.unstuck.core.logic.sharePreCreateEmailLine
import tech.csalliance.unstuck.core.logic.sharePreCreateLine
import tech.csalliance.unstuck.core.logic.shareRowMonograms
import tech.csalliance.unstuck.core.logic.shareWithLabel
import tech.csalliance.unstuck.core.logic.shareWithSummary
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.ShareLevel.ASSIGN
import tech.csalliance.unstuck.core.model.ShareLevel.PARTNER
import tech.csalliance.unstuck.core.model.ShareLevel.VIEW

// The New task sheet's one "Share with…" row: the summary it shows for what
// was picked on the pre-create Share screen (ONE rule on iOS, Android and web,
// 2026-09-24), the picks behind it, the monograms, the accessibility label and
// the honest pre-create lines.
class ShareWithSummaryTest {

    private fun p(name: String, level: ShareLevel = PARTNER) = SharePick(name, level)

    // ── THE SHARED CASES — the same table on every platform ─────────────────
    // (edit = partner, view = view, hand over = assign; budget 28 characters)

    private val sharedCases: List<Pair<List<SharePick>, String>> = listOf(
        emptyList<SharePick>() to "Only you",
        listOf(p("James Wilson")) to "James · can edit",
        listOf(p("Anna", VIEW)) to "Anna · can view",
        listOf(p("Zubair", ASSIGN)) to "Zubair · handed over",
        listOf(p("James"), p("Anna")) to "James, Anna · can edit",
        listOf(p("James", VIEW), p("Anna", VIEW)) to "James, Anna · can view",
        listOf(p("James"), p("Anna", VIEW)) to "James · edit, Anna · view",
        listOf(p("Al", VIEW), p("Bo", ASSIGN)) to "Al · view, Bo · handed over",
        listOf(p("Al"), p("Bo"), p("Cy")) to "Al + 2 more · can edit",
        listOf(p("Al", VIEW), p("Bo", VIEW), p("Cy", VIEW)) to "Al + 2 more · can view",
        listOf(p("James"), p("Anna"), p("Zubair"), p("Maya")) to "James + 3 more · can edit",
        listOf(p("James"), p("Anna", VIEW), p("Zubair")) to "James + 2 more",
        listOf(p("Bartholomew-Alexandros Papadopoulos")) to "Bartholomew-Alex… · can edit",
        listOf(p("Christopher"), p("Anna", VIEW)) to "Christo… · edit, Anna · view",
        listOf(p("Bartholomew"), p("Christopher")) to "Barthol…, Christ… · can edit",
        listOf(p("Anna"), p("Maya", ASSIGN)) to "An… · edit, M… · handed over",
        listOf(p("Maximilianus-Ferdinand"), p("Anna"), p("Bo")) to "Maximil… + 2 more · can edit",
        listOf(p("maya@example.com", VIEW)) to "maya · can view",
        listOf(p("James"), p("maya@example.com")) to "James, maya · can edit",
        listOf(p("  ")) to "Someone · can edit",
    )

    @Test fun `the shared cases`() {
        for ((picks, want) in sharedCases) {
            assertEquals("for ${picks.map { "${it.name}/${it.level}" }}", want, shareWithSummary(picks))
        }
    }

    @Test fun `three or more always compact to the first name plus a count`() {
        // Even when every name is short enough to list — the form is by count.
        assertEquals("Al + 2 more · can edit", shareWithSummary(listOf(p("Al"), p("Bo"), p("Cy"))))
        // The grade only when everyone has the same one.
        assertEquals("Al + 2 more", shareWithSummary(listOf(p("Al"), p("Bo"), p("Cy", VIEW))))
    }

    @Test fun `two people are always both named`() {
        // Never "+ 1 more": a long pair is cut, the grade kept.
        val s = shareWithSummary(listOf(p("Christopher"), p("Anna", VIEW)))
        assertTrue(s, s.startsWith("Christo…") && s.endsWith("Anna · view"))
    }

    @Test fun `the grade always shows and nothing passes the budget`() {
        val names = listOf("A", "Jo", "James", "Christopher", "Bartholomew-Alexandros", "x".repeat(60), "maya@example.com")
        val levels = listOf(PARTNER, VIEW, ASSIGN)
        for (n in 1..12) for (name in names) for (lv in levels) {
            val picks = List(n) { i -> p(if (i == 0) name else names[i % names.size], levels[(i + lv.ordinal) % levels.size]) }
            val s = shareWithSummary(picks)
            assertTrue("'$s' (${s.length}) for $n × $name", s.length <= SHARE_SUMMARY_MAX)
            assertTrue("never wraps: '$s'", '\n' !in s)
            val uniform = picks.all { it.level == picks[0].level }
            if (n <= 2 || uniform) {
                val grades = listOf("can edit", "can view", "handed over", "edit", "view")
                assertTrue("the grade survives: '$s'", grades.any { s.endsWith(it) })
            }
        }
    }

    // ── monograms ───────────────────────────────────────────────────────────

    @Test fun `up to three monograms in the summary's order`() {
        assertEquals(emptyList<String>(), shareRowMonograms(emptyList()))
        assertEquals(listOf("J"), shareRowMonograms(listOf(p("james"))))
        assertEquals(
            listOf("J", "A", "M"),
            shareRowMonograms(listOf(p("James"), p("Anna"), p("maya@example.com"), p("Zubair"))),
        )
        assertEquals(listOf("S"), shareRowMonograms(listOf(p(" "))))
    }

    // ── the picks: connections then held addresses, pick order ──────────────

    private fun member(id: String, uid: String?, name: String?, status: CircleStatus = CircleStatus.ACTIVE) =
        CircleMember(id, null, "view", status, null, uid, name, "2026-09-24T09:00:00Z")

    private val roster = listOf(
        member("c1", "u1", "Maya Chen"),
        member("c2", "u2", "Zubair"),
        member("c3", null, null, CircleStatus.INVITED),
        member("c4", "u4", "James Wilson"),
    )

    @Test fun `picks read in pick order, people then addresses, an unknown pick as someone`() {
        val shares = NewTaskShares()
            .withPerson("u4", PARTNER)
            .withEmail("New@Example.com ", VIEW)
            .withPerson("gone", VIEW)
            .withPerson("u1", PARTNER)
        assertEquals(
            listOf(
                SharePick("James Wilson", PARTNER), SharePick("Someone", VIEW), SharePick("Maya Chen", PARTNER),
                SharePick("new@example.com", VIEW),
            ),
            newTaskSharePicks(roster, shares),
        )
        assertEquals("James + 3 more", shareWithSummary(newTaskSharePicks(roster, shares)))
        assertEquals(emptyList<SharePick>(), newTaskSharePicks(roster, NewTaskShares()))
    }

    @Test fun `a held address counts in the summary as the part before the at`() {
        val shares = NewTaskShares().withEmail("maya@example.com", VIEW)
        assertEquals("maya · can view", shareWithSummary(newTaskSharePicks(roster, shares)))
        val two = shares.withPerson("u4", PARTNER)
        assertEquals("James · edit, maya · view", shareWithSummary(newTaskSharePicks(roster, two)))
    }

    @Test fun `a new grade keeps the pick's place, removing drops it`() {
        var s = NewTaskShares().withPerson("u1", PARTNER).withPerson("u2", VIEW).withPerson("u4", PARTNER)
        s = s.withPerson("u1", ASSIGN)
        assertEquals(listOf("u1", "u2", "u4"), s.people.keys.toList())
        assertEquals(ASSIGN, s.people["u1"])
        s = s.withPerson("u2", null).withPerson("", PARTNER)
        assertEquals(listOf("u1", "u4"), s.people.keys.toList())
    }

    @Test fun `addresses are normalised, deduplicated, never a hand-over, and bad ones ignored`() {
        var s = NewTaskShares().withEmail("  Maya@Example.com ", PARTNER).withEmail("maya@example.com", VIEW)
        assertEquals(mapOf("maya@example.com" to VIEW), s.emails)
        s = s.withEmail("b@x.co", ASSIGN)
        assertEquals(PARTNER, s.emails["b@x.co"])
        assertEquals(s, s.withEmail("not-an-email", PARTNER))
        s = s.withEmail("MAYA@example.com", null)
        assertEquals(mapOf("b@x.co" to PARTNER), s.emails)
        assertTrue(NewTaskShares().isEmpty)
        assertTrue(!s.isEmpty)
    }

    @Test fun `the sheet's saved state round-trips in pick order`() {
        val s = NewTaskShares().withPerson("u2", VIEW).withEmail("a@b.co", PARTNER).withPerson("u1", ASSIGN).withEmail("z|x@y.io", VIEW)
        val back = NewTaskShares.fromSaved(s.toSaved())
        assertEquals(s, back)
        assertEquals(listOf("u2", "u1"), back.people.keys.toList())
        assertEquals(listOf("a@b.co", "z|x@y.io"), back.emails.keys.toList())
        assertEquals(NewTaskShares(), NewTaskShares.fromSaved(listOf("", "x", "p|u1|BOGUS", "q|u|VIEW")))
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
        assertEquals("m@x.co gets it when you add the task — they can edit.", sharePreCreateEmailLine("m@x.co", PARTNER))
        assertEquals("m@x.co gets it when you add the task — they can view.", sharePreCreateEmailLine("m@x.co", VIEW))
        assertEquals("m@x.co won't get this task.", sharePreCreateEmailLine("m@x.co", null))
        assertEquals("Gets it when you add the task · can edit", shareHeldEmailStatus(PARTNER))
        assertEquals("Gets it when you add the task · can view", shareHeldEmailStatus(VIEW))
    }
}
