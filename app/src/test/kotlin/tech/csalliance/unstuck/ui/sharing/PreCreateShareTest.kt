package tech.csalliance.unstuck.ui.sharing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.NewTaskShares
import tech.csalliance.unstuck.core.logic.ShareAccess
import tech.csalliance.unstuck.core.logic.newTaskSharePicks
import tech.csalliance.unstuck.core.logic.sharePeopleSplit
import tech.csalliance.unstuck.core.logic.shareWithSummary
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareForTask
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.sync.CollectionMemberInfo
import tech.csalliance.unstuck.sync.CollectionShareClient
import tech.csalliance.unstuck.sync.InviteResult
import tech.csalliance.unstuck.sync.ShareLinkOutcome
import tech.csalliance.unstuck.sync.TaskShareOutcome
import tech.csalliance.unstuck.sync.TaskSharePendingInvite

// The Share screen's PRE-CREATE mode — the New task sheet's "Share with…" row
// (Ahmad, 2026-09-24: "One row + picker"). The task doesn't exist yet, so the
// screen must never touch a share RPC: every pick is local and comes back to the
// sheet as NewTaskShares (connections by user id + HELD typed addresses), which
// "Add task" hands to AppViewModel.addTask(shares=, shareEmails=). "Someone new"
// holds an address for this task; "Invite with a link" is the connect-only link.
// One behaviour on iOS, Android and web (2026-09-24).

/** A transport whose share calls FAIL the test: pre-create must not send
 *  anything. The only call it takes is the connect-only invite link. */
internal class PreCreateFakeTransport(var circle: List<CircleMember>) : ShareScreenTransport {
    var inviteResult: InviteResult = InviteResult(ok = true, link = "https://unstucknow.io/circle/join?code=abc")
    val invites = mutableListOf<String?>()

    override suspend fun listCircle(): List<CircleMember> = circle
    override suspend fun inviteToCircle(email: String?): InviteResult {
        invites += email
        return inviteResult
    }

    private fun sent(what: String): Nothing = throw AssertionError("pre-create sent $what — nothing may leave the device before Add task")
    override suspend fun taskShares(taskId: String): List<ShareForTask> = sent("task_shares_for_task")
    override suspend fun taskPendingInvites(taskId: String): List<TaskSharePendingInvite> = sent("share-task list")
    override suspend fun shareTask(taskId: String, userId: String, level: ShareLevel, notify: Boolean) = sent("task_share")
    override suspend fun unshareTask(shareId: String): Boolean = sent("task_unshare")
    override suspend fun shareTaskByEmail(taskId: String, email: String, level: ShareLevel): TaskShareOutcome = sent("share-task add")
    override suspend fun cancelTaskInvite(taskId: String, inviteId: String): Boolean = sent("share-task remove")
    override suspend fun taskLink(taskId: String, level: ShareLevel): ShareLinkOutcome = sent("share-task link")
    override suspend fun collectionMembers(collectionId: String): List<CollectionMemberInfo> = sent("share-collection list")
    override suspend fun shareCollection(collectionId: String, email: String?, userId: String?, role: String): CollectionShareClient.ShareResult =
        sent("share-collection add")
    override suspend fun unshareCollection(collectionId: String, userId: String): Boolean = sent("share-collection remove")
    override suspend fun cancelCollectionInvite(collectionId: String, email: String): Boolean = sent("share-collection remove")
    override suspend fun collectionLink(collectionId: String, role: String): ShareLinkOutcome = sent("share-collection link")
    override suspend fun block(userId: String): Boolean = sent("block_user")
}

internal fun preCreateMember(id: String, uid: String, name: String, label: String? = null) =
    CircleMember(id, label, "view", CircleStatus.ACTIVE, null, uid, name, "2026-09-24T09:00:00Z")

class PreCreateShareTest {

    private val roster = listOf(
        preCreateMember("c1", "u1", "Maya Chen", "Coach"),
        preCreateMember("c2", "u2", "James Wilson"),
        preCreateMember("c3", "u3", "Anna"),
        CircleMember("c9", null, "view", CircleStatus.INVITED, "code", null, null, "2026-09-24T09:00:00Z", inviteeEmail = "p@x.com"),
    )
    private val fake = PreCreateFakeTransport(roster)

    private fun model(picks: Map<String, ShareLevel> = emptyMap(), emails: Map<String, ShareLevel> = emptyMap()) =
        ShareScreenModel(ShareTarget.NewTask("Plan the Lisbon trip"), transport = fake, initialShares = NewTaskShares(picks, emails))

    private fun ShareScreenModel.row(name: String) = state.value.people.first { it.name == name }

    // ── the local selection → the map "Add task" hands to addTask(shares=) ──

    @Test fun `a fresh screen lists the roster with nobody picked at can edit`() = runTest {
        val m = model()
        assertTrue(m.preCreate)
        m.load()
        val s = m.state.value
        assertEquals(ShareAccess.EDIT, s.access)
        assertEquals(listOf("Maya Chen", "James Wilson", "Anna"), s.people.map { it.name })
        assertTrue(s.picks.isEmpty())
        assertTrue(s.pending.isEmpty())
        // The card shows only "Choose someone · 3".
        val split = sharePeopleSplit(s.people, s.pinnedIds, handOver = false)
        assertTrue(split.withAccess.isEmpty())
        assertEquals(3, split.candidates.size)
    }

    @Test fun `picks at the chosen grade, change, hand over and remove map to the submit shares`() = runTest {
        val m = model()
        m.load()
        m.tap(m.row("James Wilson"))                          // default grade: Can edit
        m.setAccess(ShareAccess.VIEW)
        m.tap(m.row("Anna"))                                  // now Can view
        m.tap(m.row("Maya Chen"))
        assertEquals(mapOf("u2" to ShareLevel.PARTNER, "u3" to ShareLevel.VIEW, "u1" to ShareLevel.VIEW), m.state.value.picks)

        m.setAccess(m.row("Anna"), ShareAccess.EDIT)          // the row's menu: change grade
        m.handOver(m.row("Maya Chen"))                        // the row's menu: Hand over
        assertEquals(mapOf("u2" to ShareLevel.PARTNER, "u3" to ShareLevel.PARTNER, "u1" to ShareLevel.ASSIGN), m.state.value.picks)
        assertTrue("a hand-over reads as handed over", m.row("Maya Chen").handedOver)

        m.setAccess(m.row("James Wilson"), null)              // the row's menu: Remove
        val shares = m.state.value.shares
        assertEquals("keyed by USER id — what task_share takes", mapOf("u3" to ShareLevel.PARTNER, "u1" to ShareLevel.ASSIGN), shares.people)
        assertEquals("pick order", listOf("u3", "u1"), shares.people.keys.toList())
        // …and the row on the New task sheet reads them back in PICK order; the
        // pair is too long for the row, so the names are cut and both grades kept.
        assertEquals("An… · edit, M… · handed over", shareWithSummary(newTaskSharePicks(roster, shares)))
        m.setAccess(m.row("Maya Chen"), null)
        assertEquals("Anna · can edit", shareWithSummary(newTaskSharePicks(roster, m.state.value.shares)))
        assertNull(m.state.value.error)
    }

    @Test fun `the sheet's picks come back pinned first with their grade`() = runTest {
        val m = model(mapOf("u3" to ShareLevel.VIEW, "u2" to ShareLevel.ASSIGN))
        m.load()
        val s = m.state.value
        assertEquals(setOf("c2", "c3"), s.pinnedIds)
        val split = sharePeopleSplit(s.people, s.pinnedIds, handOver = false)
        assertEquals(listOf("James Wilson", "Anna"), split.withAccess.map { it.name })
        assertEquals(listOf("Handed over", "Can view"), split.withAccess.map { it.statusLabel })
        assertEquals(listOf("Maya Chen"), split.candidates.map { it.name })
        // Un-picking keeps the row in place (pins are fixed at open) with its Share pill.
        m.setAccess(m.row("Anna"), null)
        val after = sharePeopleSplit(m.state.value.people, m.state.value.pinnedIds, handOver = false)
        assertEquals(listOf("James Wilson", "Anna"), after.withAccess.map { it.name })
        assertEquals(mapOf("u2" to ShareLevel.ASSIGN), m.state.value.picks)
    }

    @Test fun `a bare tap on someone already picked is a no-op`() = runTest {
        val m = model(mapOf("u1" to ShareLevel.VIEW))
        m.load()
        m.tap(m.row("Maya Chen"))
        assertEquals(mapOf("u1" to ShareLevel.VIEW), m.state.value.picks)
    }

    @Test fun `the honest line says what will happen, not that it was shared`() = runTest {
        val m = model()
        m.load()
        m.tap(m.row("James Wilson"))
        assertEquals("James can edit once you add the task.", m.state.value.result)
        m.handOver(m.row("James Wilson"))
        assertEquals("James gets it as their task once you add it — you keep view.", m.state.value.result)
        m.setAccess(m.row("James Wilson"), null)
        assertEquals("James won't get this task.", m.state.value.result)
    }

    @Test fun `a pick the roster no longer lists stays visible and removable`() = runTest {
        val m = model(mapOf("gone" to ShareLevel.PARTNER))
        m.load()
        val ghost = m.state.value.people.last()
        assertEquals("Someone", ghost.name)
        assertTrue(ghost.isShared)
        m.setAccess(ghost, null)
        assertTrue(m.state.value.picks.isEmpty())
    }

    @Test fun `no link, no pending invites, no block before the task exists`() = runTest {
        val m = model(mapOf("u1" to ShareLevel.PARTNER))
        m.load()
        assertNull(m.makeLink())
        m.block(m.row("Maya Chen"))
        assertNull(m.state.value.busyId)
        assertEquals(mapOf("u1" to ShareLevel.PARTNER), m.state.value.picks)
    }

    // ── Someone new: the address is HELD for this task until "Add task" ────

    @Test fun `someone new holds the address at the chosen grade and sends nothing`() = runTest {
        val m = model()
        m.load()
        m.setAccess(ShareAccess.VIEW)
        m.setEmail("  Maya@Example.com ")
        m.shareWithEmail()
        val s = m.state.value
        assertEquals(mapOf("maya@example.com" to ShareLevel.VIEW), s.shares.emails)
        assertTrue("connections untouched", s.shares.people.isEmpty())
        assertEquals("", s.email)
        assertNull(s.busyId)
        assertEquals("maya@example.com gets it when you add the task — they can view.", s.result)
        // Listed where the pending invites would be — held, not sent.
        assertEquals(listOf("email:maya@example.com"), s.pending.map { it.id })
        assertEquals(ShareAccess.VIEW, s.pending.single().access)
        assertTrue("no round trip before Add task", fake.invites.isEmpty())
        // …and counted in the row's summary as the part before the @.
        assertEquals("maya · can view", shareWithSummary(newTaskSharePicks(roster, s.shares)))
    }

    @Test fun `adding the same address again changes its grade, not the count`() = runTest {
        val m = model()
        m.load()
        m.setEmail("a@b.co"); m.shareWithEmail()
        m.setAccess(ShareAccess.VIEW)
        m.setEmail("A@B.co"); m.shareWithEmail()
        assertEquals(mapOf("a@b.co" to ShareLevel.VIEW), m.state.value.shares.emails)
        assertEquals(1, m.state.value.pending.size)
    }

    @Test fun `a held address comes off with its cross`() = runTest {
        val m = model(emails = mapOf("a@b.co" to ShareLevel.PARTNER, "c@d.co" to ShareLevel.VIEW))
        m.load()
        assertEquals(listOf("a@b.co", "c@d.co"), m.state.value.pending.map { it.email })
        m.cancelPending(m.state.value.pending.first())
        assertEquals(mapOf("c@d.co" to ShareLevel.VIEW), m.state.value.shares.emails)
        assertEquals(listOf("c@d.co"), m.state.value.pending.map { it.email })
        assertEquals("a@b.co won't get this task.", m.state.value.result)
    }

    @Test fun `people and addresses come back together to the sheet`() = runTest {
        val m = model(picks = mapOf("u2" to ShareLevel.PARTNER), emails = mapOf("x@y.io" to ShareLevel.VIEW))
        m.load()
        m.tap(m.row("Anna"))
        m.setEmail("z@y.io"); m.shareWithEmail()
        val shares = m.state.value.shares
        assertEquals(mapOf("u2" to ShareLevel.PARTNER, "u3" to ShareLevel.PARTNER), shares.people)
        assertEquals(listOf("x@y.io", "z@y.io"), shares.emails.keys.toList())
        assertEquals("James + 3 more", shareWithSummary(newTaskSharePicks(roster, shares)))
    }

    @Test fun `a blank field adds nothing and a bad address says why`() = runTest {
        val m = model()
        m.load()
        m.shareWithEmail()
        assertTrue(m.state.value.shares.isEmpty)
        assertNull(m.state.value.error)
        m.setEmail("not-an-email")
        m.shareWithEmail()
        assertEquals("That doesn't look like an email address.", m.state.value.error)
        assertTrue(m.state.value.shares.isEmpty)
        assertEquals("the field keeps what was typed", "not-an-email", m.state.value.email)
        assertTrue("no round trip for a bad address", fake.invites.isEmpty())
    }

    // ── Invite with a link: the connect-only join link ──────────────────────

    @Test fun `invite with a link makes a connect-only link`() = runTest {
        val m = model()
        m.load()
        assertEquals("https://unstucknow.io/circle/join?code=abc", m.makeInviteLink())
        assertEquals("no address — a link I send myself", listOf<String?>(null), fake.invites)
        assertEquals("https://unstucknow.io/circle/join?code=abc", m.state.value.lastLink)
        assertEquals(ShareScreenModel.INVITE_LINK_COPIED, m.state.value.result)
        assertNull(m.state.value.busyId)
        assertTrue("the link carries nothing about the task", m.state.value.shares.isEmpty)
    }

    @Test fun `a refused invite link says why`() = runTest {
        val m = model()
        m.load()
        fake.inviteResult = InviteResult(error = "circle_full")
        assertNull(m.makeInviteLink())
        assertEquals("Your circle is full.", m.state.value.error)
        fake.inviteResult = InviteResult(error = "server_error")
        assertNull(m.makeInviteLink())
        assertEquals("Couldn't make a link — try again.", m.state.value.error)
        assertNull(m.state.value.busyId)
    }

    @Test fun `a real task's screen never makes an invite link`() = runTest {
        val m = ShareScreenModel(ShareTarget.Task("t1", "x"), transport = fake)
        assertNull(m.makeInviteLink())
        assertTrue(fake.invites.isEmpty())
    }
}
