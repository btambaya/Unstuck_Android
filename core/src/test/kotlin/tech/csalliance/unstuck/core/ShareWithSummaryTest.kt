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
import tech.csalliance.unstuck.core.logic.shareSummaryOrder
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

    /** A pick as the sheet builds it: a name with an "@" is a typed (held)
     *  address, anything else a connection. */
    private fun p(name: String, level: ShareLevel = PARTNER) = SharePick(name, level, address = '@' in name)

    // ── THE SHARED CASES — the same table on iOS (ShareDraftTests.sharedCases),
    // Android and web (pre-create-share.test). Picks in the order they were
    // picked, as (name, grade): e = can edit (partner), v = can view (view),
    // h = hand over (assign); a name with an "@" is a held address, anything
    // else a connection. Output at the default budget of 28 characters. The
    // summary names connections first (pick order), then held addresses (pick
    // order). ────────────────────────────────────────────────────────────────

    private val sharedCases: List<Pair<List<Pair<String, String>>, String>> = listOf(
        emptyList<Pair<String, String>>() to "Only you",
        // 1 → "<Name> · can edit|can view|handed over"
        listOf("James Wilson" to "e") to "James · can edit",
        listOf("James Wilson" to "v") to "James · can view",
        listOf("James Wilson" to "h") to "James · handed over",
        // 2, same grade → "<A>, <B> · <grade>" (an address picked first still comes second)
        listOf("James Wilson" to "e", "Anna Berg" to "e") to "James, Anna · can edit",
        listOf("anna@example.com" to "v", "James Wilson" to "v") to "James, anna · can view",
        listOf("James Wilson" to "h", "Anna Berg" to "h") to "James, Anna · handed over",
        // 2, mixed → "<A> · edit, <B> · view"
        listOf("James Wilson" to "e", "Anna Berg" to "v") to "James · edit, Anna · view",
        listOf("Anna Berg" to "v", "James Wilson" to "e") to "Anna · view, James · edit",
        // 3+ → "<First> + N more", " · <grade>" ONLY when everyone has the same one;
        // <First> is the first CONNECTION picked when there is one
        listOf("James Wilson" to "e", "Anna Berg" to "e", "Sam O'Brien" to "e") to "James + 2 more · can edit",
        listOf("sam@example.com" to "v", "kai@example.com" to "v", "James Wilson" to "v", "Anna Berg" to "v", "Maya Chen" to "v") to
            "James + 4 more · can view",
        listOf("James Wilson" to "h", "Anna Berg" to "h", "Sam" to "h") to "James + 2 more · handed over",
        listOf("James Wilson" to "e", "Anna Berg" to "v", "Sam O'Brien" to "e") to "James + 2 more",
        listOf("Ivy Park" to "e", "Sam O'Brien" to "e", "Kai Lee" to "e") to "Ivy + 2 more · can edit",
        // addresses: the part before the @, named AFTER the connections
        listOf("maya@example.com" to "v") to "maya · can view",
        listOf("maya@example.com" to "v", "James Wilson" to "e") to "James · edit, maya · view",
        // blank → "Someone"
        listOf("" to "e") to "Someone · can edit",
        // long names are cut with "…" BEFORE the grade — the grade always shows
        listOf("Bartholomew-Alexander" to "e") to "Bartholomew-Alex… · can edit",
        listOf("Wolfeschlegelsteinhausenbergerdorff" to "v") to "Wolfeschlegelste… · can view",
        listOf("Bartholomew-Alexander" to "h") to "Bartholomew-A… · handed over",
        listOf("Bartholomew-Alexander" to "e", "Anna Berg" to "e") to "Bartholome…, Anna · can edit",
        listOf("Bartholomew-Alexander" to "e", "Wolfeschlegelsteinhausen" to "e") to "Bartho…, Wolfes… · can edit",
        listOf("Bartholomew-Alexander" to "e", "Anna Berg" to "v") to "Barthol… · edit, Anna · view",
        listOf("James Wilson" to "e", "Anna Berg" to "h") to "J… · edit, A… · handed over",
        listOf("Bartholomew-Alexander" to "e", "Anna" to "e", "Sam" to "e") to "Barthol… + 2 more · can edit",
        listOf("Bartholomew-Alexander" to "e", "Anna" to "v", "Sam" to "e") to "Bartholomew-Alexan… + 2 more",
    )

    private fun level(code: String): ShareLevel = when (code) {
        "v" -> VIEW
        "h" -> ASSIGN
        else -> PARTNER
    }

    @Test fun `the shared cases`() {
        assertEquals("the same 26 cases as iOS and web", 26, sharedCases.size)
        for ((raw, want) in sharedCases) {
            val picks = raw.map { (name, code) -> p(name, level(code)) }
            val got = shareWithSummary(picks)
            assertEquals("for $raw", want, got)
            assertTrue("'$got' is over the budget", got.length <= SHARE_SUMMARY_MAX)
        }
    }

    // ── the rule's edges ────────────────────────────────────────────────────

    @Test fun `names that must be cut share one common length, a short one stays whole`() {
        // Not a half split: both long names are capped at the SAME length.
        assertEquals("Bartho…, Christ… · can edit", shareWithSummary(listOf(p("Bartholomew"), p("Christopher"))))
        // A name no longer than the cap is never cut; the long one gives way.
        assertEquals("Christo… · edit, Anna · view", shareWithSummary(listOf(p("Christopher"), p("Anna", VIEW))))
        assertEquals("Anna, Bartholome… · can edit", shareWithSummary(listOf(p("Anna"), p("Bartholomew-Alexander"))))
    }

    @Test fun `a name is never cut below one letter`() {
        // Past the floor the Text ellipsizes, never the rule.
        assertEquals(
            "B… · edit, A… · handed over",
            shareWithSummary(listOf(p("Bartholomew-Alexander"), p("Anna", ASSIGN)), max = 12),
        )
    }

    @Test fun `connections come first, then addresses, each in pick order`() {
        val picks = listOf(p("zoe@example.com"), p("James Wilson"), p("amy@example.com", VIEW), p("Anna Berg", VIEW))
        assertEquals(
            listOf("James Wilson", "Anna Berg", "zoe@example.com", "amy@example.com"),
            shareSummaryOrder(picks).map { it.name },
        )
        assertEquals("James + 3 more", shareWithSummary(picks))
        // Addresses among themselves keep pick order (never alphabetical).
        assertEquals("zoe, amy · can edit", shareWithSummary(listOf(p("zoe@example.com"), p("amy@example.com"))))
        // Already ordered → unchanged; empty stays empty.
        val ordered = shareSummaryOrder(picks)
        assertEquals(ordered, shareSummaryOrder(ordered))
        assertEquals(emptyList<SharePick>(), shareSummaryOrder(emptyList()))
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
        // Connections first, then addresses — the summary's order, not pick order.
        assertEquals(
            listOf("J", "A", "Z"),
            shareRowMonograms(listOf(p("James"), p("Anna"), p("maya@example.com"), p("Zubair"))),
        )
        assertEquals(listOf("J", "M"), shareRowMonograms(listOf(p("maya@example.com"), p("James"))))
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
                SharePick("new@example.com", VIEW, address = true),
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
        // An address: added (or re-graded) by the whole address, taken off by
        // the part before the @ — the same strings on iOS and web.
        assertEquals("maya@example.com gets it once you add the task.", sharePreCreateEmailLine("maya@example.com", PARTNER))
        assertEquals("maya@example.com gets it once you add the task.", sharePreCreateEmailLine(" Maya@Example.com ", VIEW))
        assertEquals("maya won't get this task.", sharePreCreateEmailLine("maya@example.com", null))
        assertEquals("Gets it when you add the task · can edit", shareHeldEmailStatus(PARTNER))
        assertEquals("Gets it when you add the task · can view", shareHeldEmailStatus(VIEW))
    }
}
