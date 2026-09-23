package tech.csalliance.unstuck.sync

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The sharing server contract after migration 075, through the REAL supabase-kt
 * client: a local HTTP server answers the way PostgREST and the edge functions
 * do, so what is pinned here is how the shipped client reads those answers —
 * not a decoder called by hand.
 *
 *  - SC-3: circle-invite's non-2xx (403 `blocked`, 429 `rate_limited`) arrives as
 *    a supabase-kt RestException; the body used to be lost ("Could not create
 *    invite") because only a ktor ResponseException was caught.
 *  - C11: the roster / shared-with-me / badge reads answer NULL on a failure (an
 *    offline [] blanked People and Shared-with-you), and circle_remove answers
 *    whether the server removed them.
 *  - C10: the 075 block / leave RPCs send `p_user` / `p_share_id` and are TRUE
 *    only on the server's `true`; a server without them (PGRST202) is false.
 *  - SC-5: a list share by user id to a stale connection is NOT_CONNECTED.
 *
 * Mirrors iOS CircleClientTests / TaskShareClientTests (build 79). No real
 * network: 127.0.0.1 on an ephemeral port.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharingServerContractTest {
    private class Reply(val status: Int, val body: String = "")

    /** A one-request-per-connection HTTP/1.1 responder on 127.0.0.1 — enough for
     *  the client's POSTs (always Content-Length bodies). The JDK's HttpServer is
     *  not on an Android module's unit-test compile classpath. */
    private class TinyHttp(private val answer: (path: String, body: String) -> Reply) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        init {
            Thread {
                while (!socket.isClosed) {
                    val c = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { c.use { serve(it) } }
                }
            }.apply { isDaemon = true; start() }
        }

        fun close() = socket.close()

        private fun serve(c: Socket) {
            val input = c.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                if (line.substringBefore(':').trim().equals("content-length", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toInt()
                }
            }
            val body = ByteArray(length)
            var off = 0
            while (off < length) { val n = input.read(body, off, length - off); if (n < 0) break; off += n }
            val r = answer(requestLine.split(' ')[1].substringBefore('?'), body.decodeToString())
            val bytes = r.body.toByteArray()
            val out = c.getOutputStream()
            out.write("HTTP/1.1 ${r.status} Scripted\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
    }

    private lateinit var server: TinyHttp
    /** path → the scripted answer; anything unscripted is PostgREST's "no such function". */
    private val routes = ConcurrentHashMap<String, Reply>()
    /** path → the last request body the client sent there. */
    private val sent = ConcurrentHashMap<String, String>()
    private lateinit var circle: CircleClient
    private lateinit var share: CollectionShareClient

    @Before fun setUp() {
        server = TinyHttp { path, body ->
            sent[path] = body
            routes[path] ?: Reply(404, """{"code":"PGRST202","message":"Could not find the function","details":null,"hint":null}""")
        }
        val client = createSupabaseClient("http://127.0.0.1:${server.port}", "test-anon-key") {
            install(Postgrest)
            install(Functions)
        }
        circle = CircleClient(client)
        share = CollectionShareClient(client)
    }

    @After fun tearDown() { server.close() }

    private fun rpc(fn: String) = "/rest/v1/rpc/$fn"
    private fun edge(fn: String) = "/functions/v1/$fn"

    // ── SC-3: circle-invite refusals keep their code ────────────────────────

    @Test fun `a 403 blocked circle invite surfaces blocked, not invite_failed`() = runBlocking {
        routes[edge("circle-invite")] = Reply(403, """{"error":"blocked"}""")
        val r = circle.circleInvite("p@x.com")
        assertEquals("blocked", r.error)
        assertEquals("""{"email":"p@x.com"}""", sent[edge("circle-invite")])
    }

    @Test fun `a 429 circle invite surfaces rate_limited and an unreadable 5xx is still a refusal`() = runBlocking {
        routes[edge("circle-invite")] = Reply(429, """{"error":"rate_limited"}""")
        assertEquals("rate_limited", circle.circleInvite("p@x.com").error)
        routes[edge("circle-invite")] = Reply(502, "<html>Bad Gateway</html>")
        assertEquals("invite_failed", circle.circleInvite("p@x.com").error)
        routes[edge("circle-invite")] = Reply(200, """{"ok":true,"emailed":true,"link":"https://unstucknow.io/circle/join?code=k"}""")
        val ok = circle.circleInvite("p@x.com")
        assertNull("a 2xx is still read as the server's answer", ok.error)
        assertEquals(true, ok.emailed)
    }

    // ── C11: reads answer null on a failure; removal answers the server ─────

    @Test fun `the roster, shared-with-me and badge reads answer null on a failure, not empty`() = runBlocking {
        routes[rpc("circle_list")] = Reply(503, """{"message":"upstream down"}""")
        routes[rpc("tasks_shared_with_me")] = Reply(500, """{"message":"boom"}""")
        routes[rpc("my_task_share_badges")] = Reply(500, """{"message":"boom"}""")
        assertNull(circle.circleList())
        assertNull(circle.tasksSharedWithMe())
        assertNull(circle.myTaskShareBadges())

        // A real empty answer is still empty — only a FAILURE is null.
        routes[rpc("circle_list")] = Reply(200, "[]")
        routes[rpc("tasks_shared_with_me")] = Reply(200, "[]")
        routes[rpc("my_task_share_badges")] = Reply(200, "[]")
        assertEquals(emptyList<Any>(), circle.circleList())
        assertEquals(emptyList<Any>(), circle.tasksSharedWithMe())
        assertEquals(emptyMap<String, Any>(), circle.myTaskShareBadges())

        routes[rpc("circle_list")] = Reply(200, """[{"id":"c1","status":"active","member_user_id":"u1","member_name":"Maya Chen","created_at":"2026-09-22"}]""")
        assertEquals(listOf("u1"), circle.circleList()?.map { it.memberUserId })
    }

    @Test fun `circle_remove answers whether the server removed them`() = runBlocking {
        routes[rpc("circle_remove")] = Reply(204)
        assertTrue(circle.circleRemove("c1"))
        assertEquals("""{"p_id":"c1"}""", sent[rpc("circle_remove")])
        routes[rpc("circle_remove")] = Reply(401, """{"code":"42501","message":"unauthorized","details":null,"hint":null}""")
        assertFalse("a refusal is never reported as removed", circle.circleRemove("c1"))
    }

    // ── C10: the 075 block / leave RPCs ─────────────────────────────────────

    @Test fun `block and leave send the 075 param names and are true only on the server's true`() = runBlocking {
        routes[rpc("block_user")] = Reply(200, "true")
        assertTrue(circle.blockUser("u9"))
        assertEquals("""{"p_user":"u9"}""", sent[rpc("block_user")])

        routes[rpc("block_task_sharer")] = Reply(200, "true")
        assertTrue(circle.blockTaskSharer("s1"))
        assertEquals("""{"p_share_id":"s1"}""", sent[rpc("block_task_sharer")])

        routes[rpc("task_share_leave")] = Reply(200, "true")
        assertTrue(circle.leaveSharedTask("s2"))
        assertEquals("""{"p_share_id":"s2"}""", sent[rpc("task_share_leave")])

        routes[rpc("unblock_user")] = Reply(200, "false")
        assertFalse("no block to lift", circle.unblockUser("u8"))
        assertEquals("""{"p_user":"u8"}""", sent[rpc("unblock_user")])
    }

    @Test fun `a server without 075 never claims a block landed`() = runBlocking {
        // Nothing scripted → PostgREST's 404 PGRST202 for every function.
        assertFalse(circle.blockUser("u9"))
        assertFalse(circle.blockTaskSharer("s1"))
        assertFalse(circle.unblockUser("u9"))
        assertFalse(circle.leaveSharedTask("s1"))
        assertNull("the Blocked list keeps what it has", circle.blockedUsers())
    }

    @Test fun `my_blocked_users decodes the server's rows in order`() = runBlocking {
        routes[rpc("my_blocked_users")] = Reply(
            200, """[{"user_id":"u9","name":"Sam Lee","created_at":"2026-09-22T10:00:00+00:00"},{"user_id":"u8","name":null,"created_at":null}]""",
        )
        assertEquals(listOf("Sam Lee", "Someone"), circle.blockedUsers()?.map { it.name })
    }

    // ── SC-5: list share by user id to a stale connection ───────────────────

    @Test fun `a list share by user id to someone no longer connected is NOT_CONNECTED`() = runBlocking {
        routes[edge("share-collection")] = Reply(200, """{"ok":false,"reason":"not_in_circle"}""")
        val r = share.shareDetailed("col1", email = null, userId = "u9", role = "editor")
        assertEquals(ShareOutcome.NOT_CONNECTED, r.outcome)
        assertTrue(sent[edge("share-collection")]!!.contains(""""userId":"u9""""))
    }
}
