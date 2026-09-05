package tech.csalliance.unstuck.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

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

    // completed_at only exists once migration 049 widens the projection: an older
    // server omits the key entirely, a migrated one may send an explicit null, and
    // a completed row sends the timestamp. All three must decode.
    @Test fun `shared-with-me row tolerates absent, null and present completed_at`() {
        val absent = json.decodeFromString<SharedWithMeRow>("""{"share_id":"s1","task_id":"t1","done":true}""")
        assertNull(absent.completedAt)
        val explicitNull = json.decodeFromString<SharedWithMeRow>("""{"share_id":"s1","task_id":"t1","done":true,"completed_at":null}""")
        assertNull(explicitNull.completedAt)
        val present = json.decodeFromString<SharedWithMeRow>("""{"share_id":"s1","task_id":"t1","done":true,"completed_at":"2026-08-02T09:30:00Z"}""")
        assertEquals("2026-08-02T09:30:00Z", present.completedAt)
    }

    // ── migration 052: the owner's schedule reaches the recipient ──

    @Test fun `shared-with-me row decodes the migration-052 schedule columns`() {
        val r = json.decodeFromString<SharedWithMeRow>(
            """{"share_id":"s1","task_id":"t1","owner_name":"Anna","level":"partner","title":"London weekend",
                "done":false,"completed_at":null,"estimate_min":45,"life_area":"Family",
                "next_block_id":"b9","next_date":"2026-09-06","next_start_time":"04:30",
                "next_duration_minutes":45,"next_done":false}""",
        )
        assertEquals(45, r.estimateMin)
        assertEquals("Family", r.lifeArea)
        assertEquals("b9", r.nextBlockId)
        assertEquals("2026-09-06", r.nextDate)
        assertEquals("04:30", r.nextStartTime)
        assertEquals(45, r.nextDurationMinutes)
        assertEquals(false, r.nextDone)
    }

    @Test fun `shared-with-me row tolerates a pre-052 server (schedule keys absent) and an unscheduled task (explicit nulls)`() {
        val pre052 = json.decodeFromString<SharedWithMeRow>("""{"share_id":"s1","task_id":"t1","level":"view","title":"Write","done":false}""")
        assertNull(pre052.estimateMin); assertNull(pre052.lifeArea); assertNull(pre052.nextBlockId)
        assertNull(pre052.nextDate); assertNull(pre052.nextStartTime); assertNull(pre052.nextDurationMinutes); assertNull(pre052.nextDone)
        val unscheduled = json.decodeFromString<SharedWithMeRow>(
            """{"share_id":"s1","task_id":"t1","level":"view","title":"Write","done":false,"estimate_min":25,"life_area":null,
                "next_block_id":null,"next_date":null,"next_start_time":null,"next_duration_minutes":null,"next_done":null}""",
        )
        assertEquals(25, unscheduled.estimateMin)
        assertNull(unscheduled.lifeArea)
        assertNull(unscheduled.nextDate)
        assertNull(unscheduled.nextDone)
    }

    @Test fun `shared-task-detail row decodes + tolerates the migration-052 next columns`() {
        val with = json.decodeFromString<SharedTaskDetailRow>(
            """{"task_id":"t1","level":"view","name":"Simple","done":false,"estimate_min":25,"total_focused":0,
                "created_at":"2026-07-14T00:00:00Z","next_block_id":"b1","next_date":"2026-09-05",
                "next_start_time":"09:15","next_duration_minutes":30,"next_done":true}""",
        )
        assertEquals("b1", with.nextBlockId)
        assertEquals("2026-09-05", with.nextDate)
        assertEquals("09:15", with.nextStartTime)
        assertEquals(30, with.nextDurationMinutes)
        assertEquals(true, with.nextDone)
        val without = json.decodeFromString<SharedTaskDetailRow>("""{"task_id":"t1","level":"view","name":"Simple","done":false}""")
        assertNull(without.nextDate)
        assertNull(without.nextBlockId)
    }

    @Test fun `shared_task_blocks params are the two REQUIRED snake_case date bounds`() {
        // No defaults on purpose: an omitted bound would make the RPC raise bad_range.
        assertEquals("""{"p_from":"2026-09-01","p_to":"2026-09-30"}""", Json.encodeToString(RangeParams("2026-09-01", "2026-09-30")))
    }

    @Test fun `shared-block row decodes the migration-052 columns and tolerates absent flags`() {
        val full = json.decodeFromString<SharedBlockRow>(
            """{"block_id":"b1","task_id":"t1","share_id":"s1","level":"assign","owner_name":"Anna","title":"London weekend",
                "date":"2026-09-05","start_time":"04:30","duration_minutes":45,"done":false,"skipped":false,"kind":"task"}""",
        )
        assertEquals("b1", full.blockId)
        assertEquals("t1", full.taskId)
        assertEquals("s1", full.shareId)
        assertEquals("assign", full.level)
        assertEquals("Anna", full.ownerName)
        assertEquals("2026-09-05", full.date)
        assertEquals("04:30", full.startTime)
        assertEquals(45, full.durationMinutes)
        assertEquals(false, full.done)
        assertEquals("task", full.kind)
        val sparse = json.decodeFromString<SharedBlockRow>("""{"block_id":"b2","task_id":"t1","date":"2026-09-06","done":null,"skipped":null,"kind":null}""")
        assertEquals("b2", sparse.blockId)
        assertNull(sparse.done)
        assertNull(sparse.skipped)
        assertNull(sparse.kind)
        assertEquals("", sparse.shareId)
    }

    // ── migration 053: next_start_at / start_at / later / recurrence ──

    @Test fun `shared-with-me row decodes the 053 columns and lands the slot in the RECIPIENT's zone`() {
        // Owner in London (BST): 2026-09-05 09:00 local → 08:00Z. Recipient in Berlin sees 10:00.
        val r = json.decodeFromString<SharedWithMeRow>(
            """{"share_id":"s1","task_id":"t1","level":"partner","title":"Gym","done":false,
                "next_block_id":"b1","next_date":"2026-09-05","next_start_time":"09:00","next_duration_minutes":45,"next_done":false,
                "next_start_at":"2026-09-05T08:00:00+00:00","later":false,"recurrence":{"kind":"weekly","daysOfWeek":[1,3,5]}}""",
        )
        assertEquals("2026-09-05T08:00:00+00:00", r.nextStartAt)
        assertFalse(r.isLater)
        assertTrue(r.isRecurring)
        val berlin = r.toModel(ZoneId.of("Europe/Berlin"))
        assertEquals("2026-09-05", berlin.nextDate)
        assertEquals("10:00", berlin.nextStartTime)
        assertTrue(berlin.recurring)
        assertFalse(berlin.later)
        assertEquals("2026-09-05T08:00:00+00:00", berlin.nextStartAt)
        // Across midnight for a far-east recipient: the date itself moves.
        val tokyo = json.decodeFromString<SharedWithMeRow>(
            """{"share_id":"s1","task_id":"t1","next_date":"2026-09-05","next_start_time":"23:30","next_start_at":"2026-09-05T22:30:00Z"}""",
        ).toModel(ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-09-06", tokyo.nextDate)
        assertEquals("07:30", tokyo.nextStartTime)
    }

    @Test fun `shared-with-me row is FORGIVING - pre-053 keys absent, explicit nulls, garbage instant + odd recurrence all decode`() {
        val pre053 = json.decodeFromString<SharedWithMeRow>(
            """{"share_id":"s1","task_id":"t1","level":"view","title":"Write","done":false,"next_date":"2026-09-05","next_start_time":"09:00"}""",
        ).toModel(ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-09-05", pre053.nextDate)      // the owner's raw values stay
        assertEquals("09:00", pre053.nextStartTime)
        assertFalse(pre053.later); assertFalse(pre053.recurring); assertNull(pre053.nextStartAt)

        val nulls = json.decodeFromString<SharedWithMeRow>(
            """{"share_id":"s1","task_id":"t1","next_start_at":null,"later":null,"recurrence":null}""",
        )
        assertFalse(nulls.isLater); assertFalse(nulls.isRecurring)

        val garbage = json.decodeFromString<SharedWithMeRow>(
            """{"share_id":"s1","task_id":"t1","next_date":"2026-09-05","next_start_time":"09:00","next_start_at":"not-a-time","later":true,"recurrence":"weekly"}""",
        )
        val m = garbage.toModel(ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-09-05", m.nextDate)           // unparseable instant → owner's raw slot, never blank
        assertEquals("09:00", m.nextStartTime)
        assertTrue(m.later)
        assertFalse("a non-object recurrence is not a template", m.recurring)
        assertFalse("an EMPTY recurrence object is not a template", json.decodeFromString<SharedWithMeRow>("""{"share_id":"s","task_id":"t","recurrence":{}}""").isRecurring)
    }

    @Test fun `shared-block row decodes start_at and paints the block on the recipient's day + time`() {
        val row = json.decodeFromString<SharedBlockRow>(
            """{"block_id":"b1","task_id":"t1","date":"2026-09-05","start_time":"23:30","duration_minutes":45,"start_at":"2026-09-05T22:30:00Z"}""",
        )
        assertEquals("2026-09-05T22:30:00Z", row.startAt)
        val ny = row.toModel(ZoneId.of("America/New_York"))
        assertEquals("2026-09-05", ny.date)
        assertEquals("18:30", ny.startTime)
        assertEquals("2026-09-05T22:30:00Z", ny.startAt)
        val tokyo = row.toModel(ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-09-06", tokyo.date)
        assertEquals("07:30", tokyo.startTime)
        // Pre-053 (no start_at): the owner's wall-clock is painted as-is.
        val pre = json.decodeFromString<SharedBlockRow>("""{"block_id":"b2","task_id":"t1","date":"2026-09-05","start_time":"09:00"}""").toModel(ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-09-05", pre.date); assertEquals("09:00", pre.startTime); assertNull(pre.startAt)
    }

    @Test fun `shared-task-detail row decodes next_start_at + later and resolves the slot in the recipient's zone`() {
        val row = json.decodeFromString<SharedTaskDetailRow>(
            """{"task_id":"t1","level":"view","name":"Simple","done":false,"next_date":"2026-09-05","next_start_time":"09:00",
                "next_start_at":"2026-09-05T08:00:00Z","later":true}""",
        )
        val d = row.toModel(ZoneId.of("America/New_York"))
        assertEquals("2026-09-05", d.nextDate)
        assertEquals("04:00", d.nextStartTime)
        assertTrue(d.later)
        assertEquals("2026-09-05T08:00:00Z", d.nextStartAt)
        val pre = json.decodeFromString<SharedTaskDetailRow>("""{"task_id":"t1","level":"view","name":"Simple","done":false,"next_date":"2026-09-05"}""").toModel(ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-09-05", pre.nextDate)
        assertFalse(pre.later)
    }
}
