package tech.csalliance.unstuck.ui.sharing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ShareAccess
import tech.csalliance.unstuck.core.logic.sharePeopleSplit
import tech.csalliance.unstuck.core.logic.sharePicksInRosterOrder
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
// sheet as userId → level, which "Add task" hands to AppViewModel.addTask(shares=)
// exactly as the old inline cards did. "Someone new" is the circle invite.

/** A transport whose share calls FAIL the test: pre-create must not send anything. */
internal class PreCreateFakeTransport(var circle: List<CircleMember>) : ShareScreenTransport {
    var inviteResult: InviteResult = InviteResult(ok = true, link = "https://unstucknow.io/circle/join?code=abc")
    val invites = mutableListOf<String?>()
    /** Who joins the roster when an existing account is invited (`added`). */
    var joinsOnInvite: CircleMember? = null

    override suspend fun listCircle(): List<CircleMember> = circle
    override suspend fun inviteToCircle(email: String?): InviteResult {
        invites += email
        if (inviteResult.added == true) joinsOnInvite?.let { circle = circle + it }
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

    private fun model(picks: Map<String, ShareLevel> = emptyMap()) =
        ShareScreenModel(ShareTarget.NewTask("Plan the Lisbon trip"), transport = fake, initialPicks = picks)

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
        val picks = m.state.value.picks
        assertEquals("keyed by USER id — what task_share takes", mapOf("u3" to ShareLevel.PARTNER, "u1" to ShareLevel.ASSIGN), picks)
        // …and the row on the New task sheet reads them back in roster order
        // ("Maya · handed over, Anna · edit" is too long for the row).
        assertEquals("Maya + 1 more", shareWithSummary(sharePicksInRosterOrder(roster, picks)))
        m.setAccess(m.row("Maya Chen"), null)
        assertEquals("Anna · can edit", shareWithSummary(sharePicksInRosterOrder(roster, m.state.value.picks)))
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

    // ── Someone new = the circle invite the sheet always had ────────────────

    @Test fun `someone new with an account joins and is picked at the chosen grade`() = runTest {
        fake.inviteResult = InviteResult(ok = true, added = true)
        fake.joinsOnInvite = preCreateMember("c4", "u4", "Zubair Kazaure")
        val m = model()
        m.load()
        m.setAccess(ShareAccess.VIEW)
        m.setEmail("  Zubair@Example.com ")
        m.shareWithEmail()
        assertEquals(listOf<String?>("zubair@example.com"), fake.invites)
        val s = m.state.value
        assertEquals(mapOf("u4" to ShareLevel.VIEW), s.picks)
        assertEquals("Added Zubair to your people. Zubair can view once you add the task.", s.result)
        assertEquals("", s.email)
        assertNull(s.busyId)
    }

    @Test fun `someone new without an account is emailed the invite`() = runTest {
        fake.inviteResult = InviteResult(ok = true, emailed = true)
        val m = model()
        m.load()
        m.setEmail("new@x.com")
        m.shareWithEmail()
        assertEquals("Invite sent to new@x.com — share the task with them once they've joined.", m.state.value.result)
        assertTrue(m.state.value.picks.isEmpty())
    }

    @Test fun `a blank field makes a join link`() = runTest {
        val m = model()
        m.load()
        m.shareWithEmail()
        assertEquals(listOf<String?>(null), fake.invites)
        assertEquals("https://unstucknow.io/circle/join?code=abc", m.state.value.lastLink)
        assertEquals("Invite link copied — send it to them; it's the only way in.", m.state.value.result)
    }

    @Test fun `a bad address or a refused invite says why`() = runTest {
        val m = model()
        m.load()
        m.setEmail("not-an-email")
        m.shareWithEmail()
        assertEquals("That doesn't look like an email address.", m.state.value.error)
        assertTrue("no round trip for a bad address", fake.invites.isEmpty())

        fake.inviteResult = InviteResult(error = "circle_full")
        m.setEmail("a@b.co")
        m.shareWithEmail()
        assertEquals("Your circle is full.", m.state.value.error)
        fake.inviteResult = InviteResult(error = "server_error")
        m.shareWithEmail()
        assertEquals("Could not create invite.", m.state.value.error)
        assertNull(m.state.value.busyId)
    }
}
