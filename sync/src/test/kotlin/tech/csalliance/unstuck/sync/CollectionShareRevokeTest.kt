package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revoking access must never be REPORTED as done when the server didn't do it.
 *
 * `unshare` / `cancelInvite` / `leave` used to wrap the edge-function call in a
 * bare `runCatching {}` and return Unit, so a 403 ("not the owner any more"), a
 * 5xx, a rate-limit or being offline all reached the UI as success: the share
 * sheet dropped the member row while they kept full access, and Leave deleted
 * the list from this device while the membership stood (it came back on the
 * next hydrate). All three now answer [CollectionShareClient.revoked] of the
 * decoded response, and this pins that decision — the same rule the web fix
 * (964a7a9, lib/use-collections.ts `invokeShare`) applies.
 */
class CollectionShareRevokeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(body: String) = json.decodeFromString<CollectionShareClient.ShareResponse>(body)

    @Test fun `only an explicit ok with no error counts as revoked`() {
        // Every success branch of the share-collection function answers {ok:true,…}.
        assertTrue(CollectionShareClient.revoked(decode("""{"ok":true,"released":0}""")))
        assertTrue(CollectionShareClient.revoked(decode("""{"ok":true,"members":[],"pending":[]}""")))
    }

    @Test fun `a server refusal is not a revocation`() {
        // 4xx bodies carry {error:…} — callOrError decodes them off the thrown
        // ResponseException, which is exactly the case that used to read as success.
        assertFalse(CollectionShareClient.revoked(decode("""{"error":"forbidden"}""")))
        assertFalse(CollectionShareClient.revoked(decode("""{"ok":false,"error":"not_owner"}""")))
        // ok AND error together (defensive): an error wins.
        assertFalse(CollectionShareClient.revoked(decode("""{"ok":true,"error":"not_owner"}""")))
    }

    @Test fun `an answer with no ok at all is not a revocation`() {
        assertFalse(CollectionShareClient.revoked(decode("{}")))
        assertFalse(CollectionShareClient.revoked(decode("""{"members":[]}""")))
    }

    @Test fun `no response at all - offline, DNS, timeout - is not a revocation`() {
        // callOrError returns null when there was no HTTP response to decode.
        assertFalse(CollectionShareClient.revoked(null))
    }
}
