package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.TaskItem

// Label cascades (area / tag rename + delete). Port of the "label cascades" block in
// iOS TaskMutations.swift (build 81, audit 2026-09-22 C19). Tasks carry their life
// area and tags as plain NAME strings, not row ids, so renaming or deleting the
// vocabulary row has to follow onto every task that names it. Each transform returns
// the rewritten task, or null when the task doesn't carry the label (nothing to
// write). Settings and the assistant both reach these through the one cascade in
// AppViewModel, so the two paths can no longer drift apart.

/** Area rename ([to] = the new name) or delete ([to] = null). EXACT match, like the
 *  web's cascadeRenameTaskArea and the Today / Tasks pill filter. */
fun relabelingArea(task: TaskItem, from: String, to: String?, nowIso: String): TaskItem? {
    if (task.lifeArea != from) return null
    return task.copy(lifeArea = to, updatedAt = nowIso)
}

/** Tag rename. Case-insensitive, like the tag filter; a task that already carries the
 *  new name keeps a single copy, whatever its case. (Settings used to de-dupe
 *  case-sensitively, so [x, Y] with x→y became [y, Y].) */
fun renamingTag(task: TaskItem, from: String, to: String, nowIso: String): TaskItem? {
    val tags = task.tags ?: return null
    if (tags.none { it.equals(from, ignoreCase = true) }) return null
    val renamed = tags.map { if (it.equals(from, ignoreCase = true)) to else it }.distinctBy { it.lowercase() }
    return task.copy(tags = renamed, updatedAt = nowIso)
}

/** Tag delete: strip every case-insensitive match; an emptied list is null, not []. */
fun strippingTag(task: TaskItem, name: String, nowIso: String): TaskItem? {
    val tags = task.tags ?: return null
    if (tags.none { it.equals(name, ignoreCase = true) }) return null
    return task.copy(tags = tags.filterNot { it.equals(name, ignoreCase = true) }.ifEmpty { null }, updatedAt = nowIso)
}

/** Does another row already use [name], ignoring case? The server's
 *  unique(user_id, name) rejects the exact-case twin, and the outbox then
 *  quarantines the row while the task relabels still sync. */
fun labelNameTaken(name: String, others: List<String>): Boolean =
    others.any { it.equals(name, ignoreCase = true) }

/** Where an area filter (the Today / Tasks pills, held by NAME) points after the area
 *  rows change from [old] to [new]: unchanged while an area still has that name, the
 *  same row's new name after a rename, null (All) after a delete. Once the cascade
 *  moved every task off the old name, a filter left on it matched nothing: no pill
 *  lit and "Nothing in Personal right now." The Tasks tab's [UNASSIGNED_AREA] pick (the
 *  Areas menu) is no row, so no area change moves it: it used to snap to All on any
 *  change to the area list (an add, a recolour, another device's edit). */
fun areaFilterFollowing(filter: String?, old: List<LifeArea>, new: List<LifeArea>): String? {
    if (filter == null || new.any { it.name == filter }) return filter
    val ids = old.filter { it.name == filter }.map { it.id }.toSet()
    if (ids.isEmpty() && filter == UNASSIGNED_AREA) return filter
    return new.firstOrNull { it.id in ids }?.name
}
