package tech.csalliance.unstuck.ui.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareForTask
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.InviteResult
import tech.csalliance.unstuck.ui.AppViewModel

/** ShareTaskSheet — share ONE task with circle members at a graded level
 *  (Off / View / Partner / Assign). Android port of components/sharing/share-sheet.tsx:
 *  no blanket access — each share is one task, one person, one level. You can also
 *  INVITE a new person right here (reuses the circle invite), so sharing + adding
 *  people both happen on the task. Full roster management lives in Connections.
 *
 *  Levels go through the task_share / task_unshare RPCs (via AppViewModel.shareTask /
 *  unshareTask); a share fires share-notify (task_share) best-effort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTaskSheet(vm: AppViewModel, taskId: String, taskName: String, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val members by vm.circle.collectAsStateWithLifecycle()

    // Current shares on this task (drives each member's selected level). Reloaded
    // after every level change so the segmented control reflects server truth.
    var shares by remember { mutableStateOf<List<ShareForTask>>(emptyList()) }
    var busyUser by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(taskId) { shares = vm.sharesForTask(taskId) }

    // Invite-a-new-person state (inline, so you never leave the task).
    var inviting by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteBusy by remember { mutableStateOf(false) }
    var inviteResult by remember { mutableStateOf<InviteResult?>(null) }
    var inviteErr by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    // Only active connections with a resolved user id can receive a per-task share.
    val active = members.filter { it.status == CircleStatus.ACTIVE && it.memberUserId != null }

    fun setLevel(userId: String, next: ShareLevel?) {
        if (busyUser != null) return
        busyUser = userId
        scope.launch {
            runCatching {
                if (next == null) {
                    shares.firstOrNull { it.recipientUserId == userId }?.let { vm.unshareTask(it.shareId) }
                } else {
                    vm.shareTask(taskId, userId, next)
                }
                shares = vm.sharesForTask(taskId)
            }
            busyUser = null
        }
    }

    fun copyLink(text: String) {
        clipboard.setText(AnnotatedString(text)); copied = true
        scope.launch { delay(1800); copied = false }
    }

    fun generateInvite() {
        if (inviteBusy) return
        inviteBusy = true; inviteErr = null
        scope.launch {
            val r = runCatching { vm.inviteToCircle(inviteEmail) }.getOrNull()
            inviteBusy = false
            if (r == null || r.error != null) {
                inviteErr = "Could not create invite."
            } else {
                inviteResult = r; inviteEmail = ""
                r.link?.let { copyLink(it) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Share this task")
            Text(taskName, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink)

            active.forEach { m ->
                val userId = m.memberUserId!!
                val cur = shares.firstOrNull { it.recipientUserId == userId }?.level
                MemberLevelRow(
                    name = m.memberName ?: "Member",
                    relationship = m.relationshipLabel,
                    cur = cur,
                    busy = busyUser == userId,
                    onPick = { setLevel(userId, it) },
                )
            }

            // Invite a new person inline (no separate Connections page).
            if (inviting) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val r = inviteResult
                    if (r != null) {
                        when {
                            r.added == true -> Text("✓ Added — pick their level above.", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                            r.emailed == true -> Text("✓ Invite sent. Pick their level once they accept.", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                            r.link != null -> {
                                Text("Invite link ready${if (copied) " · copied!" else ""}", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                                Text(r.link!!, style = UFont.sans(12), color = c.ink2, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(horizontal = 10.dp, vertical = 8.dp))
                                Text("Send it to them — it's the only way in.", style = UFont.sans(12), color = c.ink3)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (r.link != null) UButton("Copy link", kind = ButtonKind.DARK, fill = false) { copyLink(r.link!!) }
                            UButton("Done", kind = ButtonKind.GHOST, fill = false) { inviting = false; inviteResult = null }
                        }
                    } else {
                        OutlinedTextField(
                            value = inviteEmail, onValueChange = { inviteEmail = it },
                            label = { Text("name@example.com (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { generateInvite() }),
                        )
                        Text("We'll email them the invite. Or leave it blank for a link you send yourself.", style = UFont.sans(12), color = c.ink3)
                        inviteErr?.let { Text(it, style = UFont.sans(12), color = c.coralDeep) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UButton(if (inviteBusy) "…" else if (inviteEmail.isBlank()) "Generate link" else "Send invite", kind = ButtonKind.DARK, fill = false, enabled = !inviteBusy) { generateInvite() }
                            UButton("Cancel", kind = ButtonKind.GHOST, fill = false) { inviting = false; inviteErr = null }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).clickable { inviting = true; inviteResult = null; inviteErr = null }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = c.primaryDeep, modifier = Modifier.padding(start = 2.dp))
                    Text("Add someone", style = UFont.sans(13, FontWeight.SemiBold), color = c.primaryDeep)
                }
            }

            if (active.isEmpty() && !inviting) {
                Text("You haven't shared with anyone yet. Add someone above to share this with them.", style = UFont.sans(13), color = c.ink2)
            }

            // Explainer — 1:1 with the web share sheet.
            Text(
                buildAnnotatedString {
                    fun b(word: String) = withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink2)) { append(word) }
                    b("View"); append(" — they see it + get pinged when you start & finish. ")
                    b("Partner"); append(" — either of you can start/complete & focus together. ")
                    b("Assign"); append(" — it becomes their task; you keep view.")
                },
                style = UFont.sans(12), color = c.ink3,
            )

            UButton("Done", kind = ButtonKind.GHOST, modifier = Modifier.padding(top = 2.dp)) { onDismiss() }
        }
    }
}

/** One circle member + a full-width Off/View/Partner/Assign segmented control. */
@Composable
private fun MemberLevelRow(name: String, relationship: String?, cur: ShareLevel?, busy: Boolean, onPick: (ShareLevel?) -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(0.dp)) { Icon(Icons.Filled.Person, contentDescription = null, tint = c.primaryDeep, modifier = Modifier.padding(0.dp)) }
            Column(Modifier) {
                Text(name, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1)
                Text(relationship ?: "connected", style = UFont.sans(11), color = c.ink3, maxLines = 1)
            }
        }
        val opts: List<Pair<ShareLevel?, String>> = listOf(
            null to "Off", ShareLevel.VIEW to "View", ShareLevel.PARTNER to "Partner", ShareLevel.ASSIGN to "Assign",
        )
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(c.bg).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            opts.forEach { (value, label) ->
                val selected = cur == value
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (selected) c.ink else Color.Transparent)
                        .clickable(enabled = !busy) { onPick(value) }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = UFont.sans(11, FontWeight.SemiBold), color = if (selected) c.bg else c.ink2) }
            }
        }
    }
}
