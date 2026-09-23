package tech.csalliance.unstuck.ui.sharing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ShareAccess
import tech.csalliance.unstuck.core.logic.sharePeopleSplit
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareForTask
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.sync.CollectionMemberInfo
import tech.csalliance.unstuck.sync.CollectionShareClient
import tech.csalliance.unstuck.sync.ShareLinkOutcome
import tech.csalliance.unstuck.sync.ShareOutcome
import tech.csalliance.unstuck.sync.TaskShareOutcome
import tech.csalliance.unstuck.sync.TaskSharePendingInvite

// Unified sharing v1 — the app half (docs/unified-sharing-spec.md §4): the
// ShareScreenModel over a fake transport — section composition, the default
// grade, level mapping per backend, the honest result line per status, error
// mapping, hand-over mode, the link. Mirrors iOS UnifiedSharingScreenTests.
// Pure JVM: the model has no Android dependency.

private class FakeShareTransport : ShareScreenTransport {
    var circle: List<CircleMember> = emptyList()
    val taskSharesById = HashMap<String, MutableList<ShareForTask>>()
    var pendingTask = mutableListOf<TaskSharePendingInvite>()
    var members = mutableListOf<CollectionMemberInfo>()

    // Scripted answers.
    var shareTaskError: Exception? = null
    var emailOutcome: TaskShareOutcome = TaskShareOutcome.Invited
    var collectionOutcome: ShareOutcome = ShareOutcome.OK
    var linkOutcome: ShareLinkOutcome = ShareLinkOutcome.Ok("https://unstucknow.io/circle/join?code=xyz")
    var unshareOk = true
    var blockOk = true

    // Recorded calls.
    val sharedTask = mutableListOf<Triple<String, String, ShareLevel>>()
    val notified = mutableListOf<Pair<String, Boolean>>()
    val unshared = mutableListOf<String>()
    val emailShares = mutableListOf<Triple<String, String, ShareLevel>>()
    val collectionShares = mutableListOf<List<String?>>()
    val cancelledTaskInvites = mutableListOf<String>()
    val cancelledCollectionInvites = mutableListOf<String>()
    val linkLevels = mutableListOf<ShareLevel>()
    val linkRoles = mutableListOf<String>()
    /** Each block call's user id + how many loads had happened before it (so a
     *  test can prove the screen never reloads BEFORE the server answered). */
    val blocks = mutableListOf<Pair<String, Int>>()
    var loads = 0

    override suspend fun listCircle(): List<CircleMember> { loads++; return circle }
    override suspend fun taskShares(taskId: String): List<ShareForTask> = taskSharesById[taskId] ?: emptyList()
    override suspend fun taskPendingInvites(taskId: String): List<TaskSharePendingInvite> = pendingTask
    override suspend fun shareTask(taskId: String, userId: String, level: ShareLevel, notify: Boolean) {
        shareTaskError?.let { throw it }
        sharedTask += Triple(taskId, userId, level)
        notified += userId to notify
        // The server now holds the share — reflect it for the reload.
        val rows = taskSharesById.getOrPut(taskId) { mutableListOf() }
        rows.removeAll { it.recipientUserId == userId }
        val name = circle.firstOrNull { it.memberUserId == userId }?.memberName ?: userId
        rows += ShareForTask("s-$userId", userId, name, level)
    }
    override suspend fun unshareTask(shareId: String): Boolean {
        if (!unshareOk) return false
        unshared += shareId
        taskSharesById.values.forEach { rows -> rows.removeAll { it.shareId == shareId } }
        return true
    }
    override suspend fun shareTaskByEmail(taskId: String, email: String, level: ShareLevel): TaskShareOutcome {
        emailShares += Triple(taskId, email, level)
        return emailOutcome
    }
    override suspend fun cancelTaskInvite(taskId: String, inviteId: String): Boolean {
        cancelledTaskInvites += inviteId
        pendingTask.removeAll { it.id == inviteId }
        return true
    }
    override suspend fun taskLink(taskId: String, level: ShareLevel): ShareLinkOutcome { linkLevels += level; return linkOutcome }
    override suspend fun collectionMembers(collectionId: String): List<CollectionMemberInfo> = members
    override suspend fun shareCollection(collectionId: String, email: String?, userId: String?, role: String): CollectionShareClient.ShareResult {
        collectionShares += listOf(collectionId, email, userId, role)
        if (collectionOutcome == ShareOutcome.OK && userId != null) {
            members.removeAll { it.userId == userId }
            members += CollectionMemberInfo(userId, email ?: "", role, pending = false)
        }
        return CollectionShareClient.ShareResult(collectionOutcome, null)
    }
    override suspend fun unshareCollection(collectionId: String, userId: String): Boolean {
        if (!unshareOk) return false
        members.removeAll { it.userId == userId }
        return true
    }
    override suspend fun cancelCollectionInvite(collectionId: String, email: String): Boolean {
        cancelledCollectionInvites += email
        members.removeAll { it.pending && it.email == email }
        return true
    }
    override suspend fun collectionLink(collectionId: String, role: String): ShareLinkOutcome { linkRoles += role; return linkOutcome }
    override suspend fun block(userId: String): Boolean {
        blocks += userId to loads
        if (!blockOk) return false
        // block_user severs everything between the pair server-side (075).
        circle = circle.filterNot { it.memberUserId == userId }
        taskSharesById.values.forEach { rows -> rows.removeAll { it.recipientUserId == userId } }
        members.removeAll { it.userId == userId }
        return true
    }
}

class ShareScreenModelTest {
    private lateinit var fake: FakeShareTransport

    @Before fun setUp() {
        fake = FakeShareTransport()
        fake.circle = listOf(
            CircleMember("c1", "Coach", "view", CircleStatus.ACTIVE, null, "u1", "Maya Chen", "2026-09-17T09:00:00Z"),
            CircleMember("c2", null, "view", CircleStatus.ACTIVE, null, "u2", "Zubair", "2026-09-17T09:01:00Z"),
            CircleMember("c3", null, "view", CircleStatus.INVITED, "code", null, null, "2026-09-17T09:02:00Z", inviteeEmail = "p@x.com"),
        )
    }

    private fun task() = ShareScreenModel(ShareTarget.Task("t1", "Grocery run"), transport = fake)
    private fun list() = ShareScreenModel(ShareTarget.Collection("col1", "Lisbon trip"), transport = fake)

    // ── composition + defaults ──────────────────────────────────────────────

    @Test fun `people are the active connections and the default grade is can edit`() = runTest {
        val m = task()
        assertTrue(m.state.value.loading)
        m.load()
        val s = m.state.value
        assertFalse(s.loading)
        assertEquals(ShareAccess.EDIT, s.access)
        assertEquals(listOf("Maya Chen", "Zubair"), s.people.map { it.name })
        assertTrue(s.people.none { it.isShared })
        assertTrue(s.pinnedIds.isEmpty())
        // Nobody has it yet: the card shows only "Choose someone · 2".
        val split = sharePeopleSplit(s.people, s.pinnedIds, handOver = false)
        assertTrue(split.withAccess.isEmpty())
        assertEquals(2, split.candidates.size)
    }

    @Test fun `existing grants annotate the rows and pin them`() = runTest {
        fake.taskSharesById["t1"] = mutableListOf(ShareForTask("s1", "u2", "Zubair", ShareLevel.VIEW))
        fake.pendingTask = mutableListOf(TaskSharePendingInvite("i1", "new@x.com", ShareLevel.PARTNER))
        val m = task()
        m.load()
        val s = m.state.value
        assertEquals(ShareAccess.VIEW, s.people[1].access)
        assertEquals("s1", s.people[1].shareId)
        assertEquals(setOf("c2"), s.pinnedIds)
        assertEquals(listOf("new@x.com"), s.pending.map { it.email })
        assertEquals(ShareAccess.EDIT, s.pending[0].access)
    }

    // ── people tap: share at the chosen grade, honest line, reload ──────────

    @Test fun `tapping a person shares at the chosen grade and reads back the server`() = runTest {
        val m = task()
        m.load()
        m.setAccess(ShareAccess.VIEW)
        m.tap(m.state.value.people[0])
        val s = m.state.value
        assertEquals(listOf(Triple("t1", "u1", ShareLevel.VIEW)), fake.sharedTask)
        assertEquals("a NEW share pings the recipient", listOf("u1" to true), fake.notified)
        assertEquals("Shared with Maya — they can view.", s.result)
        assertNull(s.error)
        assertNull(s.busyId)
        assertEquals("the reload shows the server's grant", ShareAccess.VIEW, s.people[0].access)
        assertTrue("pins are fixed at open time — the row does not move under the finger", s.pinnedIds.isEmpty())
    }

    @Test fun `a grade change is quiet and remove needs the server's yes`() = runTest {
        fake.taskSharesById["t1"] = mutableListOf(ShareForTask("s1", "u1", "Maya Chen", ShareLevel.PARTNER))
        val m = task()
        m.load()
        m.setAccess(m.state.value.people[0], ShareAccess.VIEW)
        assertEquals("Maya can now view.", m.state.value.result)
        assertEquals("a level change does not re-ping", listOf("u1" to false), fake.notified)
        // A bare tap on someone who already has it is a no-op.
        m.tap(m.state.value.people[0])
        assertEquals(1, fake.sharedTask.size)
        fake.unshareOk = false
        m.setAccess(m.state.value.people[0], null)
        assertEquals("Couldn't share — try again.", m.state.value.error)
        assertTrue(m.state.value.people[0].isShared)
        fake.unshareOk = true
        m.setAccess(m.state.value.people[0], null)
        assertEquals("Maya no longer has this.", m.state.value.result)
        assertEquals(listOf("s-u1"), fake.unshared)
        assertFalse(m.state.value.people[0].isShared)
    }

    @Test fun `a thrown rpc maps its code to the shown copy`() = runTest {
        fake.shareTaskError = IllegalStateException("""{"code":"P0001","message":"not_in_circle"}""")
        val m = task()
        m.load()
        m.tap(m.state.value.people[0])
        assertEquals("You're not connected yet — share by email or a link below.", m.state.value.error)
        assertNull(m.state.value.result)
        assertNull(m.state.value.busyId)
    }

    // ── someone new ─────────────────────────────────────────────────────────

    @Test fun `email share is honest about shared vs invited and guards the shape`() = runTest {
        val m = task()
        m.load()
        m.setEmail("nope")
        m.shareWithEmail()
        assertEquals("That doesn't look like an email address.", m.state.value.error)
        assertTrue(fake.emailShares.isEmpty())

        m.setEmail("  New@X.com ")
        m.shareWithEmail()
        assertEquals(listOf(Triple("t1", "new@x.com", ShareLevel.PARTNER)), fake.emailShares)
        assertEquals("Invite sent to new@x.com — waiting for them to sign up.", m.state.value.result)
        assertEquals("the field clears on success", "", m.state.value.email)

        fake.emailOutcome = TaskShareOutcome.Shared("u9", "Grace Hopper")
        m.setEmail("grace@x.com")
        m.shareWithEmail()
        assertEquals("Shared with Grace — they can edit.", m.state.value.result)

        fake.emailOutcome = TaskShareOutcome.Failed("self")
        m.setEmail("me@x.com")
        m.shareWithEmail()
        assertEquals("That's you.", m.state.value.error)
        assertEquals("the address stays for a retry", "me@x.com", m.state.value.email)

        fake.emailOutcome = TaskShareOutcome.Failed("rate_limited")
        m.shareWithEmail()
        assertTrue(m.state.value.error!!.startsWith("Too many invites"))
    }

    @Test fun `cancelling a pending invite reports it`() = runTest {
        fake.pendingTask = mutableListOf(TaskSharePendingInvite("i1", "new@x.com", ShareLevel.PARTNER))
        val m = task()
        m.load()
        m.cancelPending(m.state.value.pending[0])
        assertEquals(listOf("i1"), fake.cancelledTaskInvites)
        assertEquals("Invite to new@x.com cancelled.", m.state.value.result)
        assertTrue(m.state.value.pending.isEmpty())
    }

    // ── collections: roles, by-userId, neutral email line ───────────────────

    @Test fun `a list shares by user id at the mapped role and by email neutrally`() = runTest {
        val m = list()
        m.load()
        m.setAccess(ShareAccess.VIEW)
        m.tap(m.state.value.people[1])
        assertEquals(listOf(listOf("col1", null, "u2", "viewer")), fake.collectionShares)
        assertEquals("Shared with Zubair — they can view.", m.state.value.result)
        assertEquals(ShareAccess.VIEW, m.state.value.people[1].access)

        fake.collectionOutcome = ShareOutcome.ACCEPTED
        m.setEmail("new@x.com")
        m.shareWithEmail()
        assertEquals("Shared with new@x.com — they'll see it as soon as they're in.", m.state.value.result)

        fake.collectionOutcome = ShareOutcome.INVALID
        m.tap(m.state.value.people[0])
        assertEquals("an older deployment that resolves emails only", "Lists can't be shared by name yet — enter their email below.", m.state.value.error)

        fake.collectionOutcome = ShareOutcome.BLOCKED
        m.setEmail("b@x.com")
        m.shareWithEmail()
        assertEquals("You've blocked that person.", m.state.value.error)
    }

    @Test fun `a legacy list member outside the circle is still listed and removable`() = runTest {
        fake.members = mutableListOf(
            CollectionMemberInfo("u9", "old@member.com", "editor", pending = false),
            CollectionMemberInfo("", "wait@x.com", "viewer", pending = true),
        )
        val m = list()
        m.load()
        val s = m.state.value
        assertEquals(listOf("Maya Chen", "Zubair", "old@member.com"), s.people.map { it.name })
        assertEquals(setOf("grant:u9"), s.pinnedIds)
        assertEquals(listOf("wait@x.com"), s.pending.map { it.email })
        assertEquals(ShareAccess.VIEW, s.pending[0].access)
        m.setAccess(s.people[2], null)
        assertEquals("old no longer has this.", m.state.value.result)
        m.cancelPending(m.state.value.pending[0])
        assertEquals(listOf("wait@x.com"), fake.cancelledCollectionInvites)
    }

    /** A People tap for someone who is no longer a connection (they removed or
     *  blocked me) answers `not_in_circle` since 075 — say so, not "try again"
     *  (audit 2026-09-22 SC-5). */
    @Test fun `a list share to a stale connection says not connected`() = runTest {
        fake.collectionOutcome = ShareOutcome.NOT_CONNECTED
        val m = list()
        m.load()
        m.tap(m.state.value.people[0])
        assertEquals("You're not connected yet — share by email or a link below.", m.state.value.error)
        assertNull(m.state.value.result)
    }

    // ── Block (server-side, parity with iOS build 79, audit 2026-09-22 C10) ─

    @Test fun `blocking someone on a task goes to the server then reloads`() = runTest {
        fake.taskSharesById["t1"] = mutableListOf(ShareForTask("s1", "u1", "Maya Chen", ShareLevel.PARTNER))
        val m = task()
        m.load()
        val loads = fake.loads
        m.block(m.state.value.people.first { it.userId == "u1" })
        assertEquals("block_user takes the person's user id", listOf("u1"), fake.blocks.map { it.first })
        assertEquals("no reload BEFORE the server answered", loads, fake.blocks[0].second)
        assertTrue("reloaded after the block", fake.loads > loads)
        assertEquals("Blocked Maya — they can't share with you, and nothing is shared between you now.", m.state.value.result)
        assertNull(m.state.value.error)
        assertFalse("they are gone from the screen — the server cut them off", m.state.value.people.any { it.userId == "u1" })
        assertNull(m.state.value.busyId)
    }

    @Test fun `blocking someone on a list blocks their account`() = runTest {
        fake.members = mutableListOf(CollectionMemberInfo("u2", "z@x.com", "editor", pending = false))
        val m = list()
        m.load()
        m.block(m.state.value.people.first { it.userId == "u2" })
        assertEquals(listOf("u2"), fake.blocks.map { it.first })
        assertEquals("Blocked Zubair — they can't share with you, and nothing is shared between you now.", m.state.value.result)
        assertFalse(m.state.value.people.any { it.userId == "u2" })
    }

    @Test fun `a refused block is shown and never claims success`() = runTest {
        fake.taskSharesById["t1"] = mutableListOf(ShareForTask("s1", "u1", "Maya Chen", ShareLevel.PARTNER))
        fake.blockOk = false
        val m = task()
        m.load()
        m.block(m.state.value.people.first { it.userId == "u1" })
        // The refusal names the BLOCK — "Couldn't share" read as though a share
        // had failed, on a safety action whose failure matters.
        assertEquals("Couldn't block Maya — try again.", m.state.value.error)
        assertNull(m.state.value.result)
        assertEquals("they still have it", ShareAccess.EDIT, m.state.value.people.first { it.userId == "u1" }.access)
        assertNull(m.state.value.busyId)
    }

    // ── hand-over mode ──────────────────────────────────────────────────────

    @Test fun `hand-over shares at assign and pins only the holder`() = runTest {
        fake.taskSharesById["t1"] = mutableListOf(ShareForTask("s1", "u1", "Maya Chen", ShareLevel.PARTNER))
        val m = ShareScreenModel(ShareTarget.Task("t1", "Grocery run"), ShareMode.HAND_OVER, fake)
        m.load()
        assertTrue("an editor is not a holder", m.state.value.pinnedIds.isEmpty())
        m.tap(m.state.value.people[1])
        assertEquals(Triple("t1", "u2", ShareLevel.ASSIGN), fake.sharedTask.last())
        assertEquals("Handed over to Zubair — it's their task now; you keep view.", m.state.value.result)
        assertTrue(m.state.value.people[1].handedOver)
        assertEquals("Handed over", m.state.value.people[1].statusLabel)
        val split = sharePeopleSplit(m.state.value.people, m.state.value.pinnedIds, handOver = true)
        assertEquals(listOf("u2"), split.withAccess.map { it.userId })
        assertEquals(listOf("u1"), split.candidates.map { it.userId })
    }

    // ── link ────────────────────────────────────────────────────────────────

    @Test fun `the link is minted at the chosen grade and copied`() = runTest {
        val m = task()
        m.load()
        m.setAccess(ShareAccess.VIEW)
        assertEquals("https://unstucknow.io/circle/join?code=xyz", m.makeLink())
        assertEquals(listOf(ShareLevel.VIEW), fake.linkLevels)
        assertEquals("Link copied — whoever opens it gets this task.", m.state.value.result)
        assertEquals("https://unstucknow.io/circle/join?code=xyz", m.state.value.lastLink)

        val l = list()
        l.load()
        l.makeLink()
        assertEquals(listOf("editor"), fake.linkRoles)
        assertEquals("Link copied — whoever opens it gets this list.", l.state.value.result)

        fake.linkOutcome = ShareLinkOutcome.Failed("rate_limited")
        assertNull(m.makeLink())
        assertTrue(m.state.value.error!!.startsWith("Too many invites"))
        assertNull(m.state.value.busyId)
    }
}
