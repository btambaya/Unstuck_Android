package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.HAND_OVER_EXPLAINER
import tech.csalliance.unstuck.core.logic.ShareAccess
import tech.csalliance.unstuck.core.logic.ShareExistingGrant
import tech.csalliance.unstuck.core.logic.ShareFailure
import tech.csalliance.unstuck.core.logic.ShareItemKind
import tech.csalliance.unstuck.core.logic.SharePersonRow
import tech.csalliance.unstuck.core.logic.ShareResult
import tech.csalliance.unstuck.core.logic.composeSharePeople
import tech.csalliance.unstuck.core.logic.isEmailLike
import tech.csalliance.unstuck.core.logic.normalizedShareEmail
import tech.csalliance.unstuck.core.logic.sharePeopleCandidates
import tech.csalliance.unstuck.core.logic.sharePeopleOrdered
import tech.csalliance.unstuck.core.logic.sharePeopleSplit
import tech.csalliance.unstuck.core.logic.sharePersonMatches
import tech.csalliance.unstuck.core.logic.shareResultLine
import tech.csalliance.unstuck.core.logic.shareShortName
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareLevel

// Unified sharing v1 (docs/unified-sharing-spec.md §2 / §4) — the pure half:
// the ONE vocabulary and its mapping onto the two backends, the People
// section composition + picker split, the honest result lines and the failure
// copy. 1:1 with iOS Tests/UnstuckCoreTests/UnifiedSharingTests.swift.
class UnifiedSharingTest {

    // ── vocabulary → backend levels ─────────────────────────────────────────

    @Test fun `can edit and can view map onto both backends`() {
        assertEquals(ShareLevel.PARTNER, ShareAccess.EDIT.taskLevel)
        assertEquals(ShareLevel.VIEW, ShareAccess.VIEW.taskLevel)
        assertEquals("editor", ShareAccess.EDIT.collectionRole)
        assertEquals("viewer", ShareAccess.VIEW.collectionRole)
        assertEquals("Can edit", ShareAccess.EDIT.label)
        assertEquals("Can view", ShareAccess.VIEW.label)
        // Default is Can edit (the first case).
        assertEquals(ShareAccess.EDIT, ShareAccess.entries.first())
    }

    @Test fun `reverse mapping treats assign as handed over not a grade`() {
        assertEquals(ShareAccess.EDIT, ShareAccess.fromTaskLevel(ShareLevel.PARTNER))
        assertEquals(ShareAccess.VIEW, ShareAccess.fromTaskLevel(ShareLevel.VIEW))
        assertNull("assign is 'Hand over to…', never a picker grade", ShareAccess.fromTaskLevel(ShareLevel.ASSIGN))
        assertEquals(ShareAccess.VIEW, ShareAccess.fromCollectionRole("viewer"))
        assertEquals(ShareAccess.EDIT, ShareAccess.fromCollectionRole("editor"))
        assertEquals("the server coerces unknown roles to editor", ShareAccess.EDIT, ShareAccess.fromCollectionRole("owner-ish"))
        assertEquals(ShareAccess.EDIT, ShareAccess.fromCollectionRole(null))
    }

    /** A list is never started or focused: the task blurb read as nonsense
     *  under a collection's name on the Share screen. */
    @Test fun `the access blurb speaks about the kind of item`() {
        for (access in ShareAccess.entries) {
            val task = access.blurb(ShareItemKind.TASK)
            val list = access.blurb(ShareItemKind.COLLECTION)
            assertTrue(task != list)
            assertFalse("a list is not focused: $list", list.contains("focus"))
            assertFalse("a list is not started: $list", list.contains("start"))
            assertTrue("the list blurb should name the list: $list", list.contains("list"))
        }
        assertEquals("task", ShareItemKind.TASK.noun)
        assertEquals("list", ShareItemKind.COLLECTION.noun)
    }

    // ── People composition ──────────────────────────────────────────────────

    private fun member(id: String, uid: String?, name: String?, status: CircleStatus = CircleStatus.ACTIVE, label: String? = null) =
        CircleMember(
            id = id, relationshipLabel = label, level = "view", status = status, inviteCode = null,
            memberUserId = uid, memberName = name, createdAt = "2026-09-17T09:00:00Z",
        )

    @Test fun `people are the active connections annotated with their grant`() {
        val circle = listOf(
            member("c1", "u1", "Maya Chen", label = "Coach"),
            member("c2", "u2", "Zubair"),
            member("c3", null, null, status = CircleStatus.INVITED),   // pending → not a person here
            member("c4", "u4", "Gone", status = CircleStatus.REVOKED),
        )
        val grants = listOf(
            ShareExistingGrant(userId = "u1", access = ShareAccess.VIEW, shareId = "s1"),
            ShareExistingGrant(userId = "u2", access = null, handedOver = true, shareId = "s2"),
        )
        val rows = composeSharePeople(circle, grants)
        assertEquals(listOf("u1", "u2"), rows.map { it.userId })
        assertEquals("Maya Chen", rows[0].name)
        assertEquals("Coach", rows[0].subtitle)
        assertEquals(ShareAccess.VIEW, rows[0].access)
        assertEquals("s1", rows[0].shareId)
        assertEquals("Can view", rows[0].statusLabel)
        assertTrue(rows[1].handedOver)
        assertNull(rows[1].access)
        assertEquals("Handed over", rows[1].statusLabel)
        assertTrue(rows[1].isShared)
    }

    @Test fun `an unshared connection has no grant and a grant outside the circle still shows`() {
        val circle = listOf(member("c1", "u1", "Maya"))
        val grants = listOf(ShareExistingGrant(userId = "u9", email = "old@member.com", access = ShareAccess.EDIT))
        val rows = composeSharePeople(circle, grants)
        assertEquals(2, rows.size)
        assertNull(rows[0].access)
        assertFalse(rows[0].isShared)
        assertNull(rows[0].statusLabel)
        // The legacy member (never a connection) is listed by their email so the
        // owner can still change / revoke what they hold.
        assertEquals("u9", rows[1].userId)
        assertEquals("old@member.com", rows[1].name)
        assertEquals(ShareAccess.EDIT, rows[1].access)
    }

    @Test fun `duplicate roster rows for one user collapse`() {
        val circle = listOf(member("c1", "u1", "Maya"), member("c2", "u1", "Maya"))
        assertEquals(1, composeSharePeople(circle, emptyList()).size)
    }

    @Test fun `a nameless connection reads Someone`() {
        val rows = composeSharePeople(listOf(member("c1", "u1", "  ")), emptyList())
        assertEquals("Someone", rows[0].name)
    }

    // ── result lines (§2 "Feedback that is true") ───────────────────────────

    @Test fun `result lines per status`() {
        assertEquals("Shared with Maya — they can edit.", shareResultLine(ShareResult.Shared("Maya Chen", ShareAccess.EDIT)))
        assertEquals("Shared with maya — they can view.", shareResultLine(ShareResult.Shared("maya@x.com", ShareAccess.VIEW)))
        assertEquals("Invite sent to x@y.com — waiting for them to sign up.", shareResultLine(ShareResult.Invited("x@y.com")))
        // A backend that won't say shared-vs-invited (share-collection add) gets
        // a neutral line that is still true.
        assertEquals("Shared with x@y.com — they'll see it as soon as they're in.", shareResultLine(ShareResult.Accepted("x@y.com")))
        assertEquals("Link copied — whoever opens it gets this task.", shareResultLine(ShareResult.LinkCopied(ShareItemKind.TASK)))
        assertEquals("Link copied — whoever opens it gets this list.", shareResultLine(ShareResult.LinkCopied(ShareItemKind.COLLECTION)))
        assertEquals("Handed over to Maya — it's their task now; you keep view.", shareResultLine(ShareResult.HandedOver("Maya")))
        assertEquals("Maya can now view.", shareResultLine(ShareResult.AccessChanged("Maya", ShareAccess.VIEW)))
        assertEquals("Maya no longer has this.", shareResultLine(ShareResult.Removed("Maya")))
        assertEquals("Invite to x@y.com cancelled.", shareResultLine(ShareResult.InviteCancelled("x@y.com")))
        assertEquals("It becomes their task to do — you keep view and hear when it's done.", HAND_OVER_EXPLAINER)
    }

    // ── failure mapping ─────────────────────────────────────────────────────

    @Test fun `server reason codes map to the shown copy`() {
        assertEquals(ShareFailure.SelfShare, ShareFailure.fromReason("self"))
        assertEquals("That's you.", ShareFailure.fromReason("self").message)
        assertEquals("You've blocked that person.", ShareFailure.fromReason("blocked").message)
        assertEquals(ShareFailure.RateLimited, ShareFailure.fromReason("rate_limited"))
        assertTrue(ShareFailure.fromReason("rate_limited").message.contains("Too many"))
        assertEquals(ShareFailure.RateLimited, ShareFailure.fromReason("circle_full"))
        assertEquals(ShareFailure.NotFound, ShareFailure.fromReason("not_found"))
        assertEquals(ShareFailure.NotAllowed, ShareFailure.fromReason("forbidden"))
        assertEquals(ShareFailure.NotAllowed, ShareFailure.fromReason("not_your_task"))
        assertEquals(ShareFailure.InvalidEmail, ShareFailure.fromReason("invalid_email"))
        assertEquals(ShareFailure.InvalidEmail, ShareFailure.fromReason("bad_request"))
        assertEquals(ShareFailure.NotConnected, ShareFailure.fromReason("not_in_circle"))
        assertEquals("Lists can't be shared by name yet — enter their email below.", ShareFailure.ListNeedsEmail.message)
        assertEquals(ShareFailure.NotSignedIn, ShareFailure.fromReason("not_configured"))
        assertEquals(ShareFailure.Network, ShareFailure.fromReason(null))
        assertEquals("Couldn't share — try again.", ShareFailure.fromReason("network").message)
        assertEquals("codes are trimmed + case-folded", ShareFailure.SelfShare, ShareFailure.fromReason("SELF "))
        assertEquals(ShareFailure.Server("something_new"), ShareFailure.fromReason("something_new"))
        assertEquals("Couldn't share — try again.", ShareFailure.fromReason("something_new").message)
    }

    @Test fun `a thrown rpc message yields its raise-exception code`() {
        // PostgREST renders `raise exception 'not_in_circle'` inside the message.
        assertEquals("not_in_circle", ShareFailure.reasonFromMessage("""{"code":"P0001","message":"not_in_circle"}"""))
        assertEquals("not_your_task", ShareFailure.reasonFromMessage("ERROR: not_your_task"))
        assertEquals("network", ShareFailure.reasonFromMessage("Unable to resolve host"))
        assertEquals("network", ShareFailure.reasonFromMessage(null))
        assertEquals(ShareFailure.NotConnected, ShareFailure.fromReason(ShareFailure.reasonFromMessage("not_in_circle")))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @Test fun `email shape guard`() {
        assertTrue(isEmailLike("maya@example.com"))
        assertTrue(isEmailLike("  Maya.Chen+x@sub.example.co  "))
        assertFalse(isEmailLike("Maya"))
        assertFalse(isEmailLike("@example.com"))
        assertFalse(isEmailLike("maya@"))
        assertFalse(isEmailLike("maya@localhost"))
        assertFalse(isEmailLike("maya@@x.com"))
        assertFalse(isEmailLike("maya @x.com"))
        assertEquals("maya@example.com", normalizedShareEmail("  Maya@Example.COM "))
    }

    @Test fun `short name for result lines`() {
        assertEquals("Maya", shareShortName("Maya Chen"))
        assertEquals("maya", shareShortName("maya@x.com"))
        assertEquals("them", shareShortName("   "))
    }

    // ── People card — shared-first order + search ───────────────────────────

    private fun person(
        id: String, name: String, access: ShareAccess? = null, handedOver: Boolean = false,
        subtitle: String? = null, email: String? = null,
    ) = SharePersonRow(
        id = id, userId = id, name = name, subtitle = subtitle, email = email, access = access,
        handedOver = handedOver, shareId = if (access == null) null else "s-$id",
    )

    @Test fun `shared people come first and roster order is kept inside each half`() {
        val people = listOf(
            person("a", "A"), person("b", "B", ShareAccess.EDIT), person("c", "C"),
            person("d", "D", ShareAccess.EDIT), person("e", "E"),
        )
        assertEquals(listOf("b", "d", "a", "c", "e"), sharePeopleOrdered(people, setOf("b", "d")).map { it.id })
        // Pinning is by id at OPEN time — a share made while the sheet is up
        // does not reorder (the row must not move under the finger).
        assertEquals(listOf("d", "a", "b", "c", "e"), sharePeopleOrdered(people, setOf("d")).map { it.id })
        assertEquals(listOf("a", "b", "c", "d", "e"), sharePeopleOrdered(people, emptySet()).map { it.id })
    }

    @Test fun `search is diacritic and case insensitive and matches name relationship and email`() {
        val zoe = person("z", "Zoë Müller", subtitle = "Coach", email = "zoe.m@example.com")
        assertTrue(sharePersonMatches(zoe, "zoe"))
        assertTrue(sharePersonMatches(zoe, "ZOË"))
        assertTrue("diacritics fold: mül → mul", sharePersonMatches(zoe, "mul"))
        assertTrue("the relationship label", sharePersonMatches(zoe, "coa"))
        assertTrue("the email's domain word", sharePersonMatches(zoe, "example"))
        assertTrue("every term must prefix some word", sharePersonMatches(zoe, "zoe mul"))
        assertFalse("prefix-of-word, not substring", sharePersonMatches(zoe, "oë"))
        assertFalse(sharePersonMatches(zoe, "zoe x"))
        assertTrue("an empty query matches everyone", sharePersonMatches(zoe, ""))
        val plain = person("p", "Zubair")
        assertTrue(sharePersonMatches(plain, "zu"))
        assertFalse("no subtitle / email → nothing to match", sharePersonMatches(plain, "coach"))
    }

    // ── picker split (2026-09-17: never list the whole roster) ──────────────

    @Test fun `split puts only people with access inline`() {
        val people = listOf(
            person("a", "Amara"), person("b", "Bola", ShareAccess.EDIT), person("c", "Chidi"), person("d", "Dara", ShareAccess.VIEW),
        ) + (0 until 6).map { person("x$it", "Extra $it") }
        val split = sharePeopleSplit(people, pinned = emptySet(), handOver = false)
        assertEquals("shared first, in roster order", listOf("Bola", "Dara"), split.withAccess.map { it.name })
        assertEquals(8, split.candidates.size)
        assertFalse(split.candidates.any { it.isShared })
    }

    @Test fun `split keeps a pinned row inline through a reload`() {
        val people = listOf(person("a", "Amara"), person("b", "Bola"))
        val split = sharePeopleSplit(people, pinned = setOf("b"), handOver = false)
        assertEquals("the row just tapped stays put until the reload lands", listOf("b"), split.withAccess.map { it.id })
        assertEquals(listOf("a"), split.candidates.map { it.id })
    }

    @Test fun `split in hand-over mode is about the holder`() {
        val people = listOf(person("a", "Amara", ShareAccess.EDIT), person("b", "Bola", handedOver = true))
        val split = sharePeopleSplit(people, pinned = emptySet(), handOver = true)
        assertEquals("only the holder is inline; an editor is still a candidate to hand it to", listOf("b"), split.withAccess.map { it.id })
        assertEquals(listOf("a"), split.candidates.map { it.id })
    }

    @Test fun `candidates filter by name label and email case-insensitively`() {
        val people = listOf(
            person("a", "Amara Okafor", subtitle = "Coach"), person("b", "Bola", email = "bola@example.com"), person("c", "Chidi"),
        )
        assertEquals(3, sharePeopleCandidates(people, "").size)
        assertEquals("blank query is no filter", 3, sharePeopleCandidates(people, "  ").size)
        assertEquals(listOf("a"), sharePeopleCandidates(people, "oka").map { it.id })
        assertEquals(listOf("a"), sharePeopleCandidates(people, "COACH").map { it.id })
        assertEquals(listOf("b"), sharePeopleCandidates(people, "example").map { it.id })
        assertTrue(sharePeopleCandidates(people, "zzz").isEmpty())
    }
}
