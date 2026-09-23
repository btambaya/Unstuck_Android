package tech.csalliance.unstuck.core.logic

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

// Port of lib/uuid.ts + the bridge.ts UUID gate.

fun newUuid(): String = UUID.randomUUID().toString()

private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun isUuid(s: String): Boolean = UUID_RE.matches(s)

/** FK columns drop to null when not a valid UUID (web uuidOrNull). */
fun uuidOrNull(s: String?): String? = if (s != null && isUuid(s)) s else null

/** UUIDv5(NAMESPACE_URL, "https://unstucknow.io/ns/cal-block-occurrence") — fixed for ever. */
const val OCCURRENCE_ID_NAMESPACE = "acd13342-1379-568a-9f73-6acb660047d5"

private val OCCURRENCE_NS: UUID = UUID.fromString(OCCURRENCE_ID_NAMESPACE)

/**
 * A repeating task's occurrence id for one civil date: the same on every
 * device, so two devices minting the same day land on one row instead of twins
 * (audit 2026-09-22 C21 — "same id for same day", Ahmad 2026-09-23; parity with
 * iOS build 85 and web stage 2).
 *
 * RFC 4122 §4.3 UUIDv5 of "<task id, trimmed + lowercased>|<YYYY-MM-DD>" under
 * [OCCURRENCE_ID_NAMESPACE]. iOS `occurrenceId(taskId:date:)` and web
 * `lib/occurrence-id.ts` compute the same string; the shared vectors live in
 * unstuck_ios audit/parity-2026-09-23/deterministic-occurrence-ids.md §1.5.
 * NOT `UUID.nameUUIDFromBytes` — that is v3 (MD5) and gives another id.
 * Kotlin's trim() drops the same whitespace as Swift's .whitespacesAndNewlines
 * for anything a task id can carry.
 */
fun occurrenceId(taskId: String, date: String): String {
    val ns = ByteBuffer.allocate(16)
        .putLong(OCCURRENCE_NS.mostSignificantBits)
        .putLong(OCCURRENCE_NS.leastSignificantBits)
        .array()
    val name = "${taskId.trim().lowercase()}|$date".toByteArray(Charsets.UTF_8)
    val h = MessageDigest.getInstance("SHA-1").digest(ns + name).copyOf(16)
    h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte()
    h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()
    val bb = ByteBuffer.wrap(h)
    return UUID(bb.long, bb.long).toString()
}
