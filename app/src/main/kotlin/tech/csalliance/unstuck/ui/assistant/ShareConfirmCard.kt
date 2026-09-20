package tech.csalliance.unstuck.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.logic.PendingShare
import tech.csalliance.unstuck.core.logic.ShareOutcome
import tech.csalliance.unstuck.core.logic.ShareSubject
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme

// Port of components/assistant/share-confirm-card.tsx. The ONLY place an
// assistant-prepared share actually happens. The agent STAGES a request
// (core/logic/AssistantShareRequest); this card shows exactly who gets what, and
// the share RPC runs on the user's tap — "Not now" never calls it.

@Composable
fun ShareConfirmCard(
    pending: PendingShare,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = UTheme.colors
    val done = pending.outcome == ShareOutcome.SHARED
    val dismissed = pending.outcome == ShareOutcome.DISMISSED
    val failed = pending.outcome == ShareOutcome.FAILED
    // share_list (2026-09-20) stages through the same card: a list share names
    // a role (can edit / can view) instead of a task level, and hands over the
    // list's items rather than one task's title.
    val isList = pending.subject == ShareSubject.LIST
    val accessLabel = if (isList) (if (pending.role == "editor") "can edit" else "can view") else pending.level.ownerLabel

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(
            when {
                done -> "Shared"
                dismissed -> "Not shared"
                else -> "Confirm share"
            },
        )
        Text(
            buildAnnotatedString {
                append("Share ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("“${pending.taskName}”") }
                append(" with ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(pending.recipientName) }
                withStyle(SpanStyle(color = c.ink2)) { append(" — $accessLabel") }
            },
            style = UFont.sans(13), color = c.ink,
        )
        if (!done && !dismissed) {
            Text(
                if (isList) "They'll see the list and everything on it${if (pending.role == "editor") ", and can add and tick items" else ""}. Nothing else is shared."
                else "They'll see this task's title and whether it's done. Nothing else is shared.",
                style = UFont.sans(11), color = c.ink3,
            )
            if (failed) {
                Text("Could not share — try again.", style = UFont.sans(12), color = c.red)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(if (busy) c.ink4 else c.coral)
                        .clickable(enabled = !busy, role = Role.Button) { onConfirm() }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 15.dp, vertical = 7.dp),
                ) {
                    Text(
                        if (busy) "Sharing…" else "Share it",
                        style = UFont.sans(13, FontWeight.SemiBold), color = Color.White,
                    )
                }
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.line2, RoundedCornerShape(999.dp))
                        .clickable(enabled = !busy, role = Role.Button) { onDismiss() }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) { Text("Not now", style = UFont.sans(13), color = c.ink2) }
            }
        }
        if (done) {
            Text(
                if (isList) "Manage or revoke it any time from the list's share sheet."
                else "Manage or revoke it any time from the task's share menu.",
                style = UFont.sans(12), color = c.ink3,
            )
        }
    }
}
