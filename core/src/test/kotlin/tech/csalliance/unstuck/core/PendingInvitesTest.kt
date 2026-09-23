package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.circleInviteErrorMessage
import tech.csalliance.unstuck.core.logic.composePeopleSections
import tech.csalliance.unstuck.core.logic.pendingInviteLabel
import tech.csalliance.unstuck.core.logic.removeConnectionMessage
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.PendingInvite
import tech.csalliance.unstuck.core.model.PendingInviteKind
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedTaskDetail
import tech.csalliance.unstuck.core.model.asSharedWithMe

// Unified sharing v1 §2 "One place for people" — the pure half of Settings →
// People's "Waiting to join": the row label per kind (ONE vocabulary) and the
// composition that lists every invite I sent exactly once alongside the
// roster, before and after the `my_pending_invites` RPC exists. 1:1 with iOS
// Tests/UnstuckCoreTests/PendingInvitesTests.swift.
class PendingInvitesTest {

    private fun member(
        id: String, status: CircleStatus, email: String? = null, code: String? = null,
        name: String? = null, uid: String? = null,
    ) = CircleMember(
        id = id, relationshipLabel = null, level = "view", status = status, inviteCode = code,
        memberUserId = uid, memberName = name, createdAt = "2026-09-17T09:00:00Z", inviteeEmail = email,
    )

    private fun task(id: String = "ti-1", name: String? = "Draft the deck", access: String? = "partner") =
        PendingInvite(kind = PendingInviteKind.TASK, inviteId = id, itemId = "t-1", itemName = name, email = "a@b.com", access = access)

    private fun list(id: String = "col-1:a@b.com", name: String? = "Groceries", access: String? = "editor") =
        PendingInvite(kind = PendingInviteKind.COLLECTION, inviteId = id, itemId = "col-1", itemName = name, email = "a@b.com", access = access)

    // ── row labels — "<task name> · can edit", "<list name> · can view", "your people"

    @Test fun `task labels speak the one vocabulary`() {
        assertEquals("Draft the deck · can edit", pendingInviteLabel(task(access = "partner")))
        assertEquals("Draft the deck · can view", pendingInviteLabel(task(access = "view")))
        assertEquals("Draft the deck · handed over", pendingInviteLabel(task(access = "assign")))
        assertEquals("grade is case-insensitive", "Draft the deck · can edit", pendingInviteLabel(task(access = "Partner")))
    }

    @Test fun `collection labels map the roles`() {
        assertEquals("Groceries · can edit", pendingInviteLabel(list(access = "editor")))
        assertEquals("Groceries · can view", pendingInviteLabel(list(access = "viewer")))
    }

    @Test fun `circle label is your people whatever the row carries`() {
        assertEquals("your people", pendingInviteLabel(PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "tc-1", email = "a@b.com")))
        assertEquals(
            "your people",
            pendingInviteLabel(PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "tc-1", itemName = "x", email = "a@b.com", access = "view")),
        )
    }

    @Test fun `missing fields degrade instead of inventing`() {
        assertEquals("no title → the noun", "a task · can edit", pendingInviteLabel(task(name = null)))
        assertEquals("blank title + no grade", "a task", pendingInviteLabel(task(name = "  ", access = null)))
        assertEquals("an unknown grade drops the suffix", "Draft the deck", pendingInviteLabel(task(access = "future")))
        assertEquals("a list", pendingInviteLabel(list(name = null, access = null)))
        assertEquals("Groceries", pendingInviteLabel(list(access = "owner")))
    }

    @Test fun `the row id is kind-scoped`() {
        assertEquals("task:x", task("x").id)
        assertNotEquals(task("x").id, PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "x", email = "").id)
        assertEquals("the RPC's kind strings", listOf("task", "collection", "circle"), PendingInviteKind.entries.map { it.wire })
        assertEquals(PendingInviteKind.TASK, PendingInviteKind.fromWire(" Task "))
        assertNull(PendingInviteKind.fromWire("future"))
        assertNull(PendingInviteKind.fromWire(null))
    }

    // ── composition — each invite once, the roster whole ────────────────────

    @Test fun `a circle invite the RPC reports is listed once under waiting`() {
        val circle = listOf(
            member("c1", CircleStatus.ACTIVE, name = "Maya", uid = "u1"),
            member("c2", CircleStatus.INVITED, email = "p@x.com", code = "code2"),
        )
        val pending = listOf(PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "c2", email = "p@x.com", createdAt = "1"))
        val s = composePeopleSections(circle, pending)
        assertEquals("the pending roster row moved under Waiting to join", listOf("c1"), s.roster.map { it.id })
        assertEquals(listOf("circle:c2"), s.waiting.map { it.id })
        assertEquals("Copy link survives the move", "code2", s.waiting[0].inviteCode)
    }

    @Test fun `a circle invite matches by address when the ids differ`() {
        val circle = listOf(member("row-9", CircleStatus.INVITED, email = "P@X.com", code = "k"))
        val pending = listOf(PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "other-id", email = "p@x.com"))
        val s = composePeopleSections(circle, pending)
        assertTrue(s.roster.isEmpty())
        assertEquals(1, s.waiting.size)
        assertEquals("k", s.waiting[0].inviteCode)
    }

    @Test fun `link-only and unreported pending rows stay in the roster`() {
        val circle = listOf(
            member("c1", CircleStatus.ACTIVE, name = "Maya", uid = "u1"),
            member("link", CircleStatus.INVITED, code = "lnk"),                 // no address → never in the RPC
            member("mail", CircleStatus.INVITED, email = "q@x.com", code = "m"),
        )
        // A server without the RPC: the transport answers [] — nothing moves.
        val none = composePeopleSections(circle, emptyList())
        assertEquals(listOf("c1", "link", "mail"), none.roster.map { it.id })
        assertTrue(none.waiting.isEmpty())
        // The RPC reports a task invite only: the roster is untouched, the task row waits.
        val t = composePeopleSections(circle, listOf(task()))
        assertEquals(listOf("c1", "link", "mail"), t.roster.map { it.id })
        assertEquals(listOf("task:ti-1"), t.waiting.map { it.id })
    }

    @Test fun `RPC order is kept and duplicates collapse to the first`() {
        val pending = listOf(task(), list(access = "viewer"), PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "c9", email = "a@b.com"), task())
        val s = composePeopleSections(emptyList(), pending)
        assertEquals(listOf("task:ti-1", "collection:col-1:a@b.com", "circle:c9"), s.waiting.map { it.id })
    }

    @Test fun `an active member is never dropped even when an id or address collides`() {
        val circle = listOf(member("c1", CircleStatus.ACTIVE, email = "p@x.com", name = "Maya", uid = "u1"))
        val s = composePeopleSections(circle, listOf(PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "c1", email = "p@x.com")))
        assertEquals("only INVITED roster rows are replaced", listOf("c1"), s.roster.map { it.id })
        assertEquals(1, s.waiting.size)
    }

    @Test fun `a waiting circle row with no address borrows the rosters`() {
        val circle = listOf(member("c2", CircleStatus.INVITED, email = "p@x.com", code = "k"))
        val s = composePeopleSections(circle, listOf(PendingInvite(kind = PendingInviteKind.CIRCLE, inviteId = "c2", email = "")))
        assertEquals("p@x.com", s.waiting[0].email)
        assertTrue(s.roster.isEmpty())
    }

    // ── the push deep link: a shared task by id alone ───────────────────────

    @Test fun `a shared-task detail becomes a shared-with-me row the sheet can open`() {
        val d = SharedTaskDetail(
            taskId = "t1", ownerName = "Grace", level = ShareLevel.PARTNER, title = "Write brief", done = false,
            estimateMin = 45, totalFocused = 0, lifeArea = "Work", tags = emptyList(), objectives = emptyList(),
            dueAt = null, createdAt = "2026-07-14T00:00:00Z", nextDate = "2026-07-20", nextStartTime = "09:00",
            nextDurationMinutes = 45, nextDone = false,
        )
        val s = d.asSharedWithMe()
        assertEquals("t1", s.taskId)
        assertEquals("Grace", s.ownerName)
        assertEquals(ShareLevel.PARTNER, s.level)
        assertEquals("Write brief", s.title)
        assertEquals(45, s.estimateMin)
        assertEquals("Work", s.lifeArea)
        assertEquals("2026-07-20", s.nextDate)
        assertEquals("09:00", s.nextStartTime)
        assertEquals("", s.shareId)
    }

    // ── removal says what it does (parity with iOS build 79, audit 2026-09-22 C11)

    /** The dialog used to promise "will no longer see anything you've shared"
     *  while every shared list stayed shared. */
    @Test fun `the remove dialog says what removal does`() {
        val maya = member("c1", CircleStatus.ACTIVE, name = "Maya Chen", uid = "u1")
        assertEquals(
            "Maya Chen will no longer see the tasks and lists you've shared with them, and you'll lose access to the ones they shared with you.",
            removeConnectionMessage(maya),
        )
        assertEquals(
            "They will no longer see the tasks and lists you've shared with them, and you'll lose access to the ones they shared with you.",
            removeConnectionMessage(maya.copy(memberName = null)),
        )
        assertEquals("Cancels this pending invite to p@x.com.", removeConnectionMessage(member("c3", CircleStatus.INVITED, email = "p@x.com", code = "k")))
        assertEquals("Cancels this pending invite.", removeConnectionMessage(member("c4", CircleStatus.INVITED, code = "k")))
        assertTrue(removeConnectionMessage(maya).contains("lists"))
        assertFalse("the C11 overclaim is gone", removeConnectionMessage(maya).contains("anything you've shared"))
    }

    // ── circle-invite refusals reach the user (audit 2026-09-22 SC-3)

    @Test fun `a refused circle invite names the reason`() {
        assertEquals("You've blocked that person.", circleInviteErrorMessage("blocked"))
        assertEquals("Too many invites right now — try again in a few minutes.", circleInviteErrorMessage("rate_limited"))
        assertEquals("Your circle is full.", circleInviteErrorMessage("circle_full"))
        assertEquals("That doesn't look like an email address.", circleInviteErrorMessage("invalid_email"))
        assertEquals("Sign in to invite people.", circleInviteErrorMessage("not_configured"))
        assertEquals("codes are trimmed + case-folded", "You've blocked that person.", circleInviteErrorMessage(" Blocked "))
        // Anything else keeps the caller's generic line.
        assertNull(circleInviteErrorMessage("invite_failed"))
        assertNull(circleInviteErrorMessage("server_error"))
        assertNull(circleInviteErrorMessage(null))
    }
}
