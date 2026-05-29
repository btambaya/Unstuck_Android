package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind

// Port of lib/cal-block-kind.ts.

fun blockKind(b: CalBlock): CalBlockKind {
    b.kind?.let { return it }
    if (!b.externalEventId.isNullOrEmpty()) return CalBlockKind.EXTERNAL
    val id = b.taskId ?: ""
    if (id == "placeholder") return CalBlockKind.PLACEHOLDER
    if (id.startsWith("cal-")) return CalBlockKind.EXTERNAL
    return CalBlockKind.TASK
}

fun isTaskBlock(b: CalBlock): Boolean = blockKind(b) == CalBlockKind.TASK && !(b.taskId ?: "").isEmpty()
fun isPlaceholderBlock(b: CalBlock): Boolean = blockKind(b) == CalBlockKind.PLACEHOLDER
fun isExternalBlock(b: CalBlock): Boolean = blockKind(b) == CalBlockKind.EXTERNAL
