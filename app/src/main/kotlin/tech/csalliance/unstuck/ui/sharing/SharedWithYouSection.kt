package tech.csalliance.unstuck.ui.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.logic.ShareBucket
import tech.csalliance.unstuck.core.logic.ShareViewMode
import tech.csalliance.unstuck.core.logic.fmtDuration
import tech.csalliance.unstuck.core.logic.shareBucket
import tech.csalliance.unstuck.core.logic.shareFirstName
import tech.csalliance.unstuck.core.logic.shareSlotLabel
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.shareStatusLabel
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

/** "Shared with you" — the quiet-company section: tasks other people in your circle
 *  shared WITH you, at the top of Today and of the Tasks list. view = read-only
 *  company; partner + assign add a completion checkbox (either side can tick it).
 *  Tapping a row OPENS a read-only detail (T1) so the recipient can see what the task
 *  IS (steps, area, estimate, due), not just a title + status chip. Port of
 *  shared-with-me-group.tsx. Partner rows also carry live co-focus (PartnerPresence:
 *  a "focusing now" pulse + a "Sit with them").
 *
 *  [items] must already be narrowed by
 *  [tech.csalliance.unstuck.core.logic.visibleShares] for [mode], so a completed share
 *  moves out of the active views exactly like the user's own completed tasks; [mode]
 *  only decides the header wording here. */
@Composable
fun SharedWithYouSection(
    vm: AppViewModel,
    items: List<SharedWithMe>,
    mode: ShareViewMode,
    onToggle: (taskId: String, done: Boolean) -> Unit,
    onOpen: (SharedWithMe) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val c = UTheme.colors
    val todayIso = Clock.todayIso()
    Column(Modifier.fillMaxWidth().then(modifier).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 2.dp)) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = c.ink3, modifier = Modifier.size(12.dp))
            // Same wording as the web group header; the date buckets say which slice this is.
            SectionLabel(
                when (mode) {
                    ShareViewMode.COMPLETED -> "Shared with you · completed"
                    ShareViewMode.BACKLOG -> "Shared with you · overdue"
                    ShareViewMode.UPCOMING -> "Shared with you · upcoming"
                    else -> "Shared with you"
                },
            )
        }
        items.forEach { s ->
            val done = s.done
            val canComplete = s.level.canComplete
            Row(
                // Row opens the read-only detail; the checkbox (below) has its own
                // clickable that consumes the tap, so ticking never opens the sheet.
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.primarySoft, RoundedCornerShape(12.dp)).clickable { onOpen(s) }.padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (canComplete) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(if (done) c.green else Color.Transparent)
                            .border(if (done) 0.dp else 1.5.dp, if (done) Color.Transparent else c.line2, RoundedCornerShape(6.dp))
                            .clickable { onToggle(s.taskId, !done) },
                        contentAlignment = Alignment.Center,
                    ) { if (done) Icon(Icons.Filled.Check, contentDescription = "Mark done", tint = Color.White, modifier = Modifier.size(12.dp)) }
                } else {
                    Box(Modifier.size(18.dp))   // spacer keeps view-only rows title-aligned
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title, style = UFont.sans(14, FontWeight.Medium),
                        color = if (done) c.ink3 else c.ink,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // The owner's slot leads — "Sat 04:30 · 45m · from anna" — so a shared
                    // task reads like one of your own rows (migration 052). Unscheduled falls
                    // back to the estimate; an overdue slot is tinted like a backlog age badge.
                    val slot = shareSlotLabel(s, todayIso)
                    val overdue = !done && shareBucket(s, todayIso) == ShareBucket.OVERDUE
                    val meta = listOfNotNull(slot ?: fmtDuration(s.estimateMin), "from ${shareFirstName(s.ownerName)}").joinToString(" · ")
                    Text(meta, style = UFont.sans(12), color = if (overdue) c.amberInk else c.ink3, modifier = Modifier.padding(top = 2.dp))
                    // Partner rows: live co-focus — "focusing now" + "Sit with them".
                    if (s.level == ShareLevel.PARTNER && !done) {
                        PartnerPresence(vm, s.taskId, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).padding(horizontal = 9.dp, vertical = 2.dp)) {
                    Text(shareStatusLabel(s.level, done), style = UFont.sans(10, FontWeight.Bold), color = c.primaryDeep)
                }
            }
        }
    }
}
