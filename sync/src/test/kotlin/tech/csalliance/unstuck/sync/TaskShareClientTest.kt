package tech.csalliance.unstuck.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ShareFailure
import tech.csalliance.unstuck.core.model.PendingInviteKind
import tech.csalliance.unstuck.core.model.ShareLevel

// Wire-contract tests for the unified-sharing transport (spec §3.3): the
// `share-task` bodies + decoders (TaskShareClient), the honest `share-collection
// add` verdict (by email = neutral, by userId = shared) + `link`, and the
// `my_pending_invites` / `cancel_pending_invite` RPC shapes (CircleClient).
// Pure serialization — no Supabase client / network. Mirrors the iOS
// TaskShareClientTests + CollectionShareClient decodeShareAdd tests.
class TaskShareClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── share-task bodies ───────────────────────────────────────────────────

    @Test fun `bodies carry action + taskId and omit the unused optionals`() {
        assertEquals("""{"action":"list","taskId":"t1"}""", Json.encodeToString(TaskShareClient.Body("list", "t1")))
        assertEquals(
            """{"action":"add","taskId":"t1","email":"a@b.com","level":"partner"}""",
            Json.encodeToString(TaskShareClient.Body("add", "t1", email = "a@b.com", level = "partner")),
        )
        assertEquals("""{"action":"remove","taskId":"t1","userId":"u1"}""", Json.encodeToString(TaskShareClient.Body("remove", "t1", userId = "u1")))
        assertEquals("""{"action":"remove","taskId":"t1","inviteId":"i1"}""", Json.encodeToString(TaskShareClient.Body("remove", "t1", inviteId = "i1")))
        assertEquals("""{"action":"link","taskId":"t1","level":"view"}""", Json.encodeToString(TaskShareClient.Body("link", "t1", level = "view")))
    }

    // ── add → honest outcome ────────────────────────────────────────────────

    @Test fun `add decodes shared vs invited honestly`() {
        assertEquals(
            TaskShareOutcome.Shared("u9", "Maya Chen"),
            TaskShareClient.decodeAdd("""{"ok":true,"status":"shared","userId":"u9","displayName":"Maya Chen","level":"partner"}"""),
        )
        assertEquals(TaskShareOutcome.Invited, TaskShareClient.decodeAdd("""{"ok":true,"status":"invited","email":"a@b.com","level":"partner","emailed":true}"""))
        // `status` is case-folded.
        assertEquals(TaskShareOutcome.Invited, TaskShareClient.decodeAdd("""{"ok":true,"status":"INVITED"}"""))
    }

    @Test fun `add surfaces the refusal code and never fabricates invited`() {
        assertEquals(TaskShareOutcome.Failed("self"), TaskShareClient.decodeAdd("""{"ok":false,"reason":"self"}"""))
        assertEquals(TaskShareOutcome.Failed("rate_limited"), TaskShareClient.decodeAdd("""{"error":"rate_limited"}"""))
        assertEquals(TaskShareOutcome.Failed("forbidden"), TaskShareClient.decodeAdd("""{"error":"forbidden"}"""))
        assertEquals(TaskShareOutcome.Failed("bad_response"), TaskShareClient.decodeAdd("not json"))
        assertEquals(TaskShareOutcome.Failed("bad_response"), TaskShareClient.decodeAdd("""{"ok":true}"""))
        // ok + a resolved user but no status: honest fallback on what's present.
        assertEquals(TaskShareOutcome.Shared("u1", ""), TaskShareClient.decodeAdd("""{"ok":true,"userId":"u1"}"""))
    }

    @Test fun `a non-2xx body yields its code else network`() {
        assertEquals("rate_limited", TaskShareClient.failureReason("""{"error":"rate_limited"}"""))
        assertEquals("self", TaskShareClient.failureReason("""{"ok":false,"reason":"self"}"""))
        assertEquals("network", TaskShareClient.failureReason("<html>502</html>"))
        assertEquals("network", TaskShareClient.failureReason(""))
        assertEquals("network", TaskShareClient.failureReason(null))
    }

    // ── list / link / ok ────────────────────────────────────────────────────

    @Test fun `list decodes members + pending and drops rows without a key`() {
        val r = TaskShareClient.decodeList(
            """{"ok":true,"members":[{"userId":"u1","displayName":"Maya","level":"partner"},{"userId":"","displayName":"x"},{"userId":"u2","level":"future"}],
                "pending":[{"id":"i1","email":"a@b.com","level":"view","createdAt":"2026-09-17"},{"id":"","email":"z@z.com"},{"id":"i3","email":"c@d.com"}]}""",
        )
        assertEquals(listOf("u1", "u2"), r.members.map { it.userId })
        assertEquals(ShareLevel.PARTNER, r.members[0].level)
        assertEquals("unknown levels degrade to VIEW", ShareLevel.VIEW, r.members[1].level)
        assertEquals(listOf("i1", "i3"), r.pending.map { it.id })
        assertEquals(ShareLevel.VIEW, r.pending[0].level)
        assertEquals("a missing invite level is the default grade", ShareLevel.PARTNER, r.pending[1].level)
        assertEquals(TaskShareRoster.EMPTY, TaskShareClient.decodeList("nope"))
        assertEquals(TaskShareRoster.EMPTY, TaskShareClient.decodeList("""{"error":"forbidden"}"""))
    }

    @Test fun `link yields the url or the reason`() {
        assertEquals(ShareLinkOutcome.Ok("https://unstucknow.io/circle/join?code=abc"), TaskShareClient.decodeLink("""{"ok":true,"url":"https://unstucknow.io/circle/join?code=abc","expiresAt":"x"}"""))
        assertEquals(ShareLinkOutcome.Failed("rate_limited"), TaskShareClient.decodeLink("""{"error":"rate_limited"}"""))
        assertEquals(ShareLinkOutcome.Failed("bad_response"), TaskShareClient.decodeLink("""{"ok":true}"""))
        assertEquals(ShareLinkOutcome.Failed("bad_response"), TaskShareClient.decodeLink("nope"))
    }

    @Test fun `a revoke counts only on an explicit ok with no error`() {
        assertTrue(TaskShareClient.decodeOk("""{"ok":true,"members":[],"pending":[]}"""))
        assertFalse(TaskShareClient.decodeOk("""{"ok":false,"reason":"forbidden"}"""))
        assertFalse(TaskShareClient.decodeOk("""{"error":"server_error"}"""))
        assertFalse(TaskShareClient.decodeOk("{}"))
        assertFalse(TaskShareClient.decodeOk("nope"))
    }

    // ── share-collection add: honest verdict (mirrors iOS decodeShareAdd) ──

    private fun coll(body: String) = CollectionShareClient.decodeShareAdd(json.decodeFromString<CollectionShareClient.ShareResponse>(body))

    @Test fun `collection add by email is ACCEPTED - the server will not say which branch`() {
        val r = coll("""{"ok":true,"invited":true,"email":"a@b.com","role":"editor","members":[{"user_id":"u1","email":"x@y.com","role":"editor"}],"pending":[],"isOwner":true}""")
        assertEquals(ShareOutcome.ACCEPTED, r.outcome)
        assertEquals(listOf("u1"), r.memberIds)
        assertTrue(r.outcome.isSuccess)
        assertNull(r.outcome.failureReason)
    }

    @Test fun `collection add by userId is honest and tolerates a members COUNT`() {
        val r = coll("""{"ok":true,"status":"shared","userId":"u9","displayName":"Maya","role":"viewer","members":2}""")
        assertEquals(ShareOutcome.OK, r.outcome)
        assertEquals("Maya", r.displayName)
        assertEquals("no rows sent → the added id stands in", listOf("u9"), r.memberIds)
        assertEquals(ShareOutcome.INVITED, coll("""{"ok":true,"status":"invited"}""").outcome)
    }

    @Test fun `collection add refusals map by code`() {
        assertEquals(ShareOutcome.SELF, coll("""{"ok":false,"reason":"self"}""").outcome)
        assertEquals(ShareOutcome.SELF, coll("""{"error":"self"}""").outcome)
        assertEquals(ShareOutcome.NOT_FOUND, coll("""{"error":"not_found"}""").outcome)
        assertEquals(ShareOutcome.BLOCKED, coll("""{"error":"blocked"}""").outcome)
        assertEquals(ShareOutcome.RATE_LIMITED, coll("""{"error":"rate_limited"}""").outcome)
        assertEquals(ShareOutcome.INVALID, coll("""{"error":"bad_request"}""").outcome)
        assertEquals(ShareOutcome.ERROR, coll("""{"error":"server_error"}""").outcome)
        assertEquals(ShareOutcome.ERROR, coll("""{}""").outcome)
        assertEquals("bad_request", ShareOutcome.INVALID.failureReason)
        assertEquals("network", ShareOutcome.ERROR.failureReason)
    }

    /** An add by user id for a stale People row (the other side removed or
     *  blocked me) answers `not_in_circle` since migration 075 — the Share screen
     *  must say "not connected", not "Couldn't share — try again" (audit
     *  2026-09-22 SC-5; iOS TaskShareClientTests). */
    @Test fun `collection add by a stale connection is NOT_CONNECTED`() {
        assertEquals(ShareOutcome.NOT_CONNECTED, coll("""{"ok":false,"reason":"not_in_circle"}""").outcome)
        assertEquals(ShareOutcome.NOT_CONNECTED, coll("""{"error":"not_in_circle"}""").outcome)
        assertFalse(ShareOutcome.NOT_CONNECTED.isSuccess)
        assertEquals("not_in_circle", ShareOutcome.NOT_CONNECTED.failureReason)
        assertEquals(ShareFailure.NotConnected, ShareFailure.fromReason(ShareOutcome.NOT_CONNECTED.failureReason))
        assertEquals(
            "You're not connected yet — share by email or a link below.",
            ShareFailure.fromReason(ShareOutcome.NOT_CONNECTED.failureReason).message,
        )
    }

    // ── circle_list invitee_email + pending-invite RPCs ─────────────────────

    @Test fun `circle row reads invitee_email and tolerates its absence`() {
        val with = json.decodeFromString<CircleRow>("""{"id":"c2","status":"invited","invite_code":"k","invitee_email":"p@x.com","created_at":"1"}""")
        assertEquals("p@x.com", with.inviteeEmail)
        val without = json.decodeFromString<CircleRow>("""{"id":"c1","status":"active","member_user_id":"u1","member_name":"Ada","created_at":"1"}""")
        assertNull(without.inviteeEmail)
        val explicitNull = json.decodeFromString<CircleRow>("""{"id":"c3","status":"invited","invitee_email":null,"created_at":"1"}""")
        assertNull(explicitNull.inviteeEmail)
    }

    @Test fun `cancel_pending_invite params are the snake_case the SQL expects`() {
        assertEquals("""{"p_kind":"task","p_id":"i1"}""", Json.encodeToString(CancelPendingInviteParams("task", "i1")))
    }

    @Test fun `my_pending_invites decodes the contract and drops what it cannot cancel`() {
        val rows = CircleClient.decodePendingInvites(
            """[
              {"kind":"task","id":"ti-1","itemId":"t-1","itemName":"Draft the deck","email":"a@b.com","access":"partner","createdAt":"2026-09-17T10:00:00Z"},
              {"kind":"collection","id":"ci-1","item_id":"c-1","item_name":"Groceries","email":"a@b.com","access":"viewer"},
              {"kind":"circle","id":"tc-1","itemId":null,"itemName":null,"email":"p@x.com","access":null,"createdAt":null},
              {"kind":"future","id":"x"},
              {"kind":"task","id":""},
              {"kind":"task","id":42,"email":"n@x.com"},
              "not an object"
            ]""",
        )
        assertEquals(listOf("task:ti-1", "collection:ci-1", "circle:tc-1", "task:42"), rows.map { it.id })
        assertEquals("Draft the deck", rows[0].itemName)
        assertEquals("t-1", rows[0].itemId)
        assertEquals("partner", rows[0].access)
        assertEquals("2026-09-17T10:00:00Z", rows[0].createdAt)
        assertEquals("snake_case twins are read", "Groceries", rows[1].itemName)
        assertEquals("c-1", rows[1].itemId)
        assertEquals(PendingInviteKind.CIRCLE, rows[2].kind)
        assertNull(rows[2].itemId)
        assertNull(rows[2].access)
        assertEquals("p@x.com", rows[2].email)
        assertEquals("a numeric id is read as text", "42", rows[3].inviteId)
        assertTrue(CircleClient.decodePendingInvites("nope").isEmpty())
        assertTrue(CircleClient.decodePendingInvites("""{"error":"unauthorized"}""").isEmpty())
    }

    @Test fun `cancel_pending_invite reads a scalar boolean and the reshaped forms`() {
        assertTrue(CircleClient.decodeCancelPendingInvite("true"))
        assertTrue(CircleClient.decodeCancelPendingInvite(" true\n"))
        assertTrue(CircleClient.decodeCancelPendingInvite("[true]"))
        assertTrue(CircleClient.decodeCancelPendingInvite("""{"ok":true}"""))
        assertTrue(CircleClient.decodeCancelPendingInvite("""{"cancel_pending_invite":true}"""))
        assertFalse(CircleClient.decodeCancelPendingInvite("false"))
        assertFalse(CircleClient.decodeCancelPendingInvite("[false]"))
        assertFalse(CircleClient.decodeCancelPendingInvite("null"))
        assertFalse(CircleClient.decodeCancelPendingInvite(""))
        assertFalse(CircleClient.decodeCancelPendingInvite("""{"error":"forbidden"}"""))
    }
}
