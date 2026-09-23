package tech.csalliance.unstuck.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.OCCURRENCE_ID_NAMESPACE
import tech.csalliance.unstuck.core.logic.occurrenceId

// The deterministic occurrence id (stage 2, audit 2026-09-22 C21). The vectors are
// deterministic-occurrence-ids.md §1.5 — iOS CoreModelsTests.testOccurrenceIdVectors
// and web lib/occurrence-id.test.ts assert the same eight rows, so the three
// platforms mint one id per task per day.
class UuidTest {
    private val vectors = listOf(
        Triple("3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60", "2026-09-24", "f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be"),
        Triple("3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60", "2026-09-25", "94be29ab-5eb6-52f4-bade-486972b834ae"),
        Triple("3F1C2A9E-5B7D-4C21-9A0E-7D2B1C4E8F60", "2026-09-24", "f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be"),
        Triple("3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60", "2028-02-29", "101e2d03-3451-56d1-83b8-5f8e3d482db3"),
        Triple("b0d8e7c6-1a2b-4c3d-8e9f-0a1b2c3d4e5f", "2026-12-31", "5bfa0f7f-0aea-502e-9b9d-40acfdf8cb3d"),
        Triple("b0d8e7c6-1a2b-4c3d-8e9f-0a1b2c3d4e5f", "2027-01-01", "72f3d112-a4f9-5197-89f8-192492309c27"),
        Triple("00000000-0000-0000-0000-000000000000", "2026-01-01", "33b3ff38-ce8b-5bfa-96a2-960ee8ea4a94"),
        // Space, tab, VT, FF before; CR LF after; upper case — pins the trim.
        Triple(" \t\u000B\u000C3F1C2A9E-5B7D-4C21-9A0E-7D2B1C4E8F60\r\n", "2026-09-24", "f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be"),
    )

    @Test fun theSharedVectorsMatch() {
        for ((i, v) in vectors.withIndex()) {
            assertEquals("vector #${i + 1}", v.third, occurrenceId(v.first, v.second))
        }
    }

    @Test fun everyIdIsVersion5WithTheRfcVariant() {
        for (v in vectors) {
            val id = occurrenceId(v.first, v.second)
            assertEquals('5', id[14])
            assertTrue(id[19] in "89ab")
            assertEquals(id, id.lowercase())
        }
    }

    @Test fun consecutiveDaysDifferAndTheTaskIdIsCaseAndSpaceInsensitive() {
        val (a, b, c) = Triple(occurrenceId(vectors[0].first, "2026-09-24"), occurrenceId(vectors[1].first, "2026-09-25"), occurrenceId(vectors[2].first, "2026-09-24"))
        assertNotEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, occurrenceId(vectors[7].first, "2026-09-24"))
    }

    /** The namespace is itself UUIDv5(NAMESPACE_URL, "https://unstucknow.io/ns/cal-block-occurrence"). */
    @Test fun theNamespaceDerivation() {
        val nsUrl = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
        val ns = ByteBuffer.allocate(16).putLong(nsUrl.mostSignificantBits).putLong(nsUrl.leastSignificantBits).array()
        val h = MessageDigest.getInstance("SHA-1").digest(ns + "https://unstucknow.io/ns/cal-block-occurrence".toByteArray()).copyOf(16)
        h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()
        val bb = ByteBuffer.wrap(h)
        assertEquals(OCCURRENCE_ID_NAMESPACE, UUID(bb.long, bb.long).toString())
    }

    /** The v3 trap (§1.1): java.util.UUID.nameUUIDFromBytes is MD5, not SHA-1. */
    @Test fun nameUuidFromBytesIsNotTheOccurrenceId() {
        val v3 = UUID.nameUUIDFromBytes("3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60|2026-09-24".toByteArray()).toString()
        assertEquals("96aa4ff5-7914-3fa1-9850-437786cf1353", v3)
        assertNotEquals(vectors[0].third, v3)
    }
}
