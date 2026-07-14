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
        // migration 045 shared_task_detail(p_task_id) + migration 046's 3-arg,
        // per-session-idempotent log_shared_focus(p_task_id, p_actual_sec, p_session_id)
        assertEquals("""{"p_task_id":"t1","p_actual_sec":900,"p_session_id":"s1"}""", Json.encodeToString(LogFocusParams("t1", 900, "s1")))
    }

    @Test fun `shared-task-detail row decodes the migration-045 columns incl objectives + tags`() {
        val row = json.decodeFromString<SharedTaskDetailRow>(
            """{"task_id":"t1","owner_name":"Grace","level":"partner","name":"Write brief","done":false,
                "estimate_min":45,"total_focused":600,"life_area":"Work","priority":"high",
                "tags":["deep","writing"],
                "objectives":[{"text":"Outline","done":true},{"text":"Draft"}],
                "due_at":"2026-07-20T09:00:00Z","created_at":"2026-07-14T00:00:00Z"}""",
        )
        assertEquals("t1", row.taskId)
        assertEquals("Grace", row.ownerName)
        assertEquals("partner", row.level)
        assertEquals(45, row.estimateMin)
        assertEquals(2, row.objectives?.size)
        assertEquals("Outline", row.objectives?.first()?.text)
        assertEquals(listOf("deep", "writing"), row.tags)
    }

    @Test fun `shared-task-detail row tolerates absent + explicit-null nullable columns`() {
        // A task with no area / tags / steps / due: those columns come back null or absent.
        val row = json.decodeFromString<SharedTaskDetailRow>(
            """{"task_id":"t1","level":"view","name":"Simple","done":false,"estimate_min":25,
                "total_focused":0,"life_area":null,"priority":null,"tags":null,"objectives":null,
                "due_at":null,"created_at":"2026-07-14T00:00:00Z"}""",
        )
        assertEquals("t1", row.taskId)
        assertNull(row.lifeArea)
        assertNull(row.tags)
        assertNull(row.objectives)
        assertEquals("", row.ownerName ?: "")
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
