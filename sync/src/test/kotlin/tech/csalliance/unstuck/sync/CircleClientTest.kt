package tech.csalliance.unstuck.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Wire-contract tests for the CircleClient DTOs. The RPC param objects must
// serialize to the EXACT snake_case names the SQL functions expect (migrations
// 036/037/044), and the no-email invite must omit the field (kotlinx drops null
// defaults → `{}`), which is how circle-invite signals "just give me a link".
// Row DTOs must decode the server's snake_case columns + tolerate absent fields.
// Pure serialization — no Supabase client / network.
class CircleClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `rpc param names are the snake_case the SQL functions expect`() {
        assertEquals("""{"p_code":"abc"}""", Json.encodeToString(CodeParam("abc")))
        assertEquals("""{"p_id":"i1"}""", Json.encodeToString(IdParam("i1")))
        assertEquals("""{"p_task_id":"t1"}""", Json.encodeToString(TaskIdParam("t1")))
        assertEquals("""{"p_task_id":"t1","p_user":"u1","p_level":"partner"}""", Json.encodeToString(ShareParams("t1", "u1", "partner")))
        assertEquals("""{"p_task_id":"t1","p_done":true}""", Json.encodeToString(SetDoneParams("t1", true)))
    }

    @Test fun `invite body omits a null email so the no-email case sends an empty object`() {
        assertEquals("{}", Json.encodeToString(InviteBody(email = null)))
        assertEquals("""{"email":"a@b.com"}""", Json.encodeToString(InviteBody(email = "a@b.com")))
    }

    @Test fun `notify body carries kind + taskId and omits a null recipient`() {
        assertEquals("""{"kind":"task_done","taskId":"t1"}""", Json.encodeToString(NotifyBody("task_done", "t1")))
        assertEquals("""{"kind":"task_share","taskId":"t1","recipientId":"r1"}""", Json.encodeToString(NotifyBody("task_share", "t1", "r1")))
    }

    @Test fun `circle row decodes server snake_case columns`() {
        val row = json.decodeFromString<CircleRow>(
            """{"id":"c1","relationship_label":"friend","level":"view","status":"active","member_user_id":"u9","member_name":"Ada","created_at":"2026-01-01"}""",
        )
        assertEquals("c1", row.id)
        assertEquals("Ada", row.memberName)
        assertEquals("u9", row.memberUserId)
        assertEquals("friend", row.relationshipLabel)
    }

    @Test fun `redeem result decodes the owner_name column`() {
        val r = json.decodeFromString<RedeemResult>("""{"ok":true,"owner_name":"Grace"}""")
        assertTrue(r.ok)
        assertEquals("Grace", r.ownerName)
    }

    @Test fun `shared-with-me row tolerates an absent done`() {
        val r = json.decodeFromString<SharedWithMeRow>("""{"share_id":"s1","task_id":"t1","level":"view","title":"Write"}""")
        assertEquals("s1", r.shareId)
        assertEquals("Write", r.title)
        assertNull(r.done)
    }
}
