package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.PendingShare
import tech.csalliance.unstuck.core.logic.looksLikeEmail
import tech.csalliance.unstuck.core.logic.resolveListShareRequest
import tech.csalliance.unstuck.core.logic.ShareSubject
import tech.csalliance.unstuck.core.logic.ShareCandidate
import tech.csalliance.unstuck.core.logic.matchCandidate
import tech.csalliance.unstuck.core.logic.normalizeLevel
import tech.csalliance.unstuck.core.logic.resolveShareRequest
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.TaskItem

// 1:1 with lib/assistant/share-request.test.ts. Sharing is the ONE agent action
// that sends a user's content to another person, so the tool only ever STAGES a
// request — the share RPC runs on the user's tap, never here.
class AssistantShareRequestTest {

    private fun task(name: String, id: String = name.lowercase().replace(Regex("\\s+"), "-")) = TaskItem(
        id = id, name = name, estimateMin = 25,
        createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z",
    )

    private val people = listOf(
        ShareCandidate("u1", "Zubair Kazaure"),
        ShareCandidate("u2", "Ana Silva"),
    )
    private val ids = { "fixed-id" }
    private val tasks = listOf(task("Write the project update"), task("Grocery run"))

    @Test fun `matchCandidate matches full name, first name and unique prefixes, case-insensitively`() {
        assertEquals("u1", matchCandidate("zubair kazaure", people)?.userId)
        assertEquals("u1", matchCandidate("Zubair", people)?.userId)
        assertEquals("u2", matchCandidate("an", people)?.userId)
    }

    @Test fun `matchCandidate refuses ambiguous or unknown names rather than guessing`() {
        val twoAnas = people + ShareCandidate("u3", "Ana Bell")
        assertNull(matchCandidate("ana", twoAnas))   // ambiguous prefix
        assertNull(matchCandidate("sam", people))
        assertNull(matchCandidate("", people))
        assertNull(matchCandidate("   ", people))
    }

    @Test fun `normalizeLevel accepts the three real levels and defaults everything else to view`() {
        assertEquals(ShareLevel.PARTNER, normalizeLevel("partner"))
        assertEquals(ShareLevel.ASSIGN, normalizeLevel("ASSIGN"))
        assertEquals(ShareLevel.VIEW, normalizeLevel("owner"))
        assertEquals(ShareLevel.VIEW, normalizeLevel(null))
    }

    @Test fun `stages a request with the resolved task, person and level`() {
        val r = resolveShareRequest(
            taskId = null, taskName = "Grocery run", person = "Ana", level = "partner",
            tasks = tasks, people = people, newId = ids,
        )
        assertEquals(
            PendingShare(
                id = "fixed-id", taskId = "grocery-run", taskName = "Grocery run",
                recipientUserId = "u2", recipientName = "Ana Silva", level = ShareLevel.PARTNER,
            ),
            r.pending,
        )
        // The model is explicitly told NOT to claim it happened.
        assertTrue(r.message.contains("CONFIRM"))
        assertTrue(r.message.contains("do not claim it is shared"))
    }

    @Test fun `resolves the task by id, and by fuzzy name when the id is unknown`() {
        assertEquals(
            "grocery-run",
            resolveShareRequest("grocery-run", null, "Ana", null, tasks, people, ids).pending?.taskId,
        )
        assertEquals(
            "Write the project update",
            resolveShareRequest(null, "project update", "Ana", null, tasks, people, ids).pending?.taskName,
        )
    }

    @Test fun `stages nothing when the task cannot be found`() {
        val r = resolveShareRequest(null, "nonexistent", "Ana", null, tasks, people, ids)
        assertNull(r.pending)
        assertTrue(r.message.contains("task not found"))
    }

    @Test fun `stages nothing when the circle is empty, and says what to do`() {
        val r = resolveShareRequest(null, "Grocery run", "Ana", null, tasks, emptyList(), ids)
        assertNull(r.pending)
        assertTrue(r.message.contains("trusted circle"))
    }

    @Test fun `stages nothing for an unknown person, and lists who IS available`() {
        val r = resolveShareRequest(null, "Grocery run", "Sam", null, tasks, people, ids)
        assertNull(r.pending)
        assertTrue(r.message.contains("Zubair Kazaure, Ana Silva"))
    }

    @Test fun `defaults an unrecognised level to the least-permissive view`() {
        val r = resolveShareRequest(null, "Grocery run", "Ana", "everything", tasks, people, ids)
        assertEquals(ShareLevel.VIEW, r.pending?.level)
    }

    @Test fun `no task reference at all stages nothing`() {
        val r = resolveShareRequest(null, null, "Ana", null, tasks, people, ids)
        assertNull(r.pending)
        assertTrue(r.message.contains("task not found"))
    }

    // ── share_list (2026-09-20): staged like a task, plus the email path ──

    @Test fun `a list share stages a LIST subject with a role, never a task level`() {
        val r = resolveListShareRequest("l1", "Groceries", "Ana", "editor", people, ids)
        assertEquals(
            PendingShare(
                id = "fixed-id", taskId = "l1", taskName = "Groceries", recipientUserId = "u2", recipientName = "Ana Silva",
                level = ShareLevel.VIEW, subject = ShareSubject.LIST, role = "editor",
            ),
            r.pending,
        )
        assertEquals("ok: prepared a share of list \"Groceries\" with Ana Silva (editor). The user must CONFIRM it on screen — tell them it's ready to confirm, and do not claim it is shared.", r.message)
        assertEquals("viewer", resolveListShareRequest("l1", "Groceries", "Ana", "boss", people, ids).pending!!.role)
        assertEquals("viewer", resolveListShareRequest("l1", "Groceries", "Ana", null, people, ids).pending!!.role)
    }

    @Test fun `a list share to an email address stages an invite even with an empty circle`() {
        val r = resolveListShareRequest("l1", "Groceries", " Sam@Example.com ", "viewer", emptyList(), ids)
        val p = r.pending!!
        assertEquals("sam@example.com", p.recipientEmail)
        assertEquals("sam@example.com", p.recipientName)
        assertEquals("", p.recipientUserId)
        assertEquals(ShareSubject.LIST, p.subject)
        assertTrue(r.message.startsWith("ok: prepared a share of list \"Groceries\" with sam@example.com (viewer)."))
        assertTrue(looksLikeEmail("a@b.co"))
        assertFalse(looksLikeEmail("Ana"))
        assertFalse(looksLikeEmail("a@b"))
    }

    @Test fun `a list share refuses a missing person, an empty circle and an ambiguous name without staging`() {
        assertNull(resolveListShareRequest("l1", "Groceries", null, null, people, ids).pending)
        assertTrue(resolveListShareRequest("l1", "Groceries", " ", null, people, ids).message.startsWith("error: needs a person"))
        val empty = resolveListShareRequest("l1", "Groceries", "Ana", null, emptyList(), ids)
        assertNull(empty.pending)
        assertTrue(empty.message.contains("share with an email address instead"))
        val unknown = resolveListShareRequest("l1", "Groceries", "Zed", null, people, ids)
        assertNull(unknown.pending)
        assertTrue(unknown.message.startsWith("error: no circle member matches \"Zed\""))
    }
}
