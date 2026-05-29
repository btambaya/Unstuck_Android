package tech.csalliance.unstuck.core.logic

import java.util.UUID

// Port of lib/uuid.ts + the bridge.ts UUID gate.

fun newUuid(): String = UUID.randomUUID().toString()

private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun isUuid(s: String): Boolean = UUID_RE.matches(s)

/** FK columns drop to null when not a valid UUID (web uuidOrNull). */
fun uuidOrNull(s: String?): String? = if (s != null && isUuid(s)) s else null
