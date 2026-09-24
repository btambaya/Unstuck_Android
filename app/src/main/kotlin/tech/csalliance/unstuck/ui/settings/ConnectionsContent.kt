package tech.csalliance.unstuck.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.circleInviteErrorMessage
import tech.csalliance.unstuck.core.logic.composePeopleSections
import tech.csalliance.unstuck.core.logic.pendingInviteLabel
import tech.csalliance.unstuck.core.logic.removeConnectionMessage
import tech.csalliance.unstuck.core.model.BlockedUser
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.PendingInvite
import tech.csalliance.unstuck.core.model.PendingInviteKind
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.InviteResult
import tech.csalliance.unstuck.ui.AppViewModel

// Connections ("People you share with") — the Android port of the web
// SharingPanel/CircleRoster and of iOS ConnectionsFeature. One unified roster:
// everyone you've shared a task or a list with lands here. Add someone by email
// (the server emails them the join link), generate a link to send yourself, or
// redeem a code someone sent you; and remove a connection. Everything goes
// through the CircleClient RPCs + the circle-invite edge fn; the roster is a
// live StateFlow (it refetches on the CollabRealtime circle-changed signal when
// someone accepts/leaves).
//
// Unified sharing v1 (spec §2 "One place for people"): the screen ALSO lists
// every email invite you sent from anywhere — a task's Share screen
// (`task_invites`), a list's (`collection_invites`) or "Add someone" here
// (`trusted_circle` invited-with-address) — under "Waiting to join", from ONE
// RPC (`my_pending_invites()`), each with a Cancel (`cancel_pending_invite`).
// The pure composition (each invite once, the roster whole) is
// composePeopleSections in :core; pending roster rows show the invitee's email.
//
// Blocks (migration 075, parity with iOS build 79, audit 2026-09-22 C10):
// "Remove and block" on a connection, and a "Blocked" section listing everyone
// I blocked (`my_blocked_users()`) with Unblock. The block lives on the server.

/** Build the same join link the web copies for a pending invite's code. */
private fun inviteLink(code: String): String = "https://unstucknow.io/circle/join?code=$code"

@Composable
fun ConnectionsContent(vm: AppViewModel) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val members by vm.circle.collectAsStateWithLifecycle()

    // Waiting to join — every invite I sent that is still unclaimed, from
    // `my_pending_invites()`, re-read whenever the roster re-emits (the
    // circleChanged signal: someone joined / an invite was cancelled elsewhere).
    var pending by remember { mutableStateOf<List<PendingInvite>>(emptyList()) }
    var pendingLoaded by remember { mutableStateOf(false) }
    var waitingError by remember { mutableStateOf<String?>(null) }
    // Everyone I blocked, re-read with the roster (user_blocks is never mirrored).
    // A failed read (null) keeps the list shown. A refresh is a fresh answer, so it
    // also clears the lines a refused remove / block / unblock left.
    var blocked by remember { mutableStateOf<List<BlockedUser>>(emptyList()) }
    var blockError by remember { mutableStateOf<String?>(null) }
    var rosterError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(members) {
        pending = vm.myPendingInvites(); pendingLoaded = true; waitingError = null
        vm.blockedUsers()?.let { blocked = it }
        blockError = null; rosterError = null
    }
    val sections = remember(members, pending) { composePeopleSections(members, pending) }

    // Add-someone state (inline panel, mirrors the web CircleRoster).
    var adding by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<InviteResult?>(null) }
    var addErr by remember { mutableStateOf<String?>(null) }
    var copiedKey by remember { mutableStateOf<String?>(null) }   // which link was just copied

    // Redeem-a-code state (the field opens from "Have an invite code?").
    var redeemOpen by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var redeeming by remember { mutableStateOf(false) }
    var redeemMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Confirmations.
    var removeTarget by remember { mutableStateOf<CircleMember?>(null) }
    var cancelTarget by remember { mutableStateOf<PendingInvite?>(null) }

    fun copy(text: String, key: String) {
        clipboard.setText(AnnotatedString(text)); copiedKey = key
        scope.launch { delay(1800); if (copiedKey == key) copiedKey = null }
    }

    fun submitInvite() {
        if (busy) return
        busy = true; addErr = null
        scope.launch {
            val r = runCatching { vm.inviteToCircle(email) }.getOrNull()
            busy = false
            if (r == null || r.error != null) {
                // A 403 `blocked` / 429 now reaches here with its code (audit SC-3).
                addErr = circleInviteErrorMessage(r?.error) ?: "Could not create invite. Try again."
            } else {
                result = r
                email = ""
                r.link?.let { copy(it, "new") }
                pending = vm.myPendingInvites()
            }
        }
    }

    fun submitRedeem() {
        // Accept a bare code OR a pasted join link (…/circle/join?code=XXXX).
        val raw = code.trim()
        val parsed = if (raw.contains("code=")) raw.substringAfter("code=").substringBefore('&').substringBefore('#').trim() else raw
        if (parsed.isEmpty() || redeeming) return
        redeeming = true; redeemMsg = null
        scope.launch {
            val r = runCatching { vm.redeemCircle(parsed) }.getOrNull()
            redeeming = false
            when {
                r == null -> redeemMsg = false to "Could not join. Try again."
                r.ok -> { code = ""; redeemMsg = true to "Joined ${r.ownerName ?: "their"} connections." }
                r.error == "self" -> redeemMsg = false to "That's your own invite."
                r.error == "already_in_circle" -> redeemMsg = false to "You're already connected."
                r.error == "invalid_or_expired" -> redeemMsg = false to "That invite is invalid or expired."
                else -> redeemMsg = false to "Could not join. Try again."
            }
        }
    }

    /** Cancel a Waiting-to-join invite (`cancel_pending_invite`). Optimistic —
     *  the row leaves at once — then the refetch shows the server's truth: a
     *  refused cancel brings the row back with a line saying so. */
    fun cancelPending(p: PendingInvite) {
        waitingError = null
        pending = pending.filterNot { it.id == p.id }
        scope.launch {
            val ok = vm.cancelPendingInvite(p.kind, p.inviteId)
            pending = vm.myPendingInvites()
            if (!ok) waitingError = "Couldn't cancel that invite — try again."
        }
    }

    /** Remove someone (or cancel a pending roster row). The server also ends the
     *  task shares and list memberships between you, both ways (075). A refusal —
     *  offline, say — leaves them in place and says so (audit 2026-09-22 C11). */
    fun remove(m: CircleMember) {
        rosterError = null
        scope.launch { if (!vm.removeFromCircle(m)) rosterError = "Couldn't remove — try again." }
    }

    /** "Remove and block": block them server-side (`block_user`), which also cuts
     *  the connection, task shares and list memberships both ways and stops them
     *  sharing with me again. The roster re-reads on success; a refusal keeps the
     *  row and says so under Blocked (audit 2026-09-22 C10). */
    fun block(m: CircleMember) {
        val userId = m.memberUserId ?: return
        blockError = null
        scope.launch {
            if (vm.blockUser(userId)) vm.blockedUsers()?.let { blocked = it }
            else blockError = "Couldn't block — try again."
        }
    }

    /** Lift a block (`unblock_user`). Restores nothing the block removed. Optimistic
     *  — the row leaves at once — then the re-read shows the server's truth. */
    fun unblock(b: BlockedUser) {
        blockError = null
        blocked = blocked.filterNot { it.userId == b.userId }
        scope.launch {
            val ok = vm.unblockUser(b.userId)
            vm.blockedUsers()?.let { blocked = it }
            if (!ok) blockError = "Couldn't unblock — try again."
        }
    }

    val rosterCount = sections.roster.count { it.isActiveOrInvited }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Everyone you share a task or a list with lands here — one place, no double invites. " +
                "You share from the task itself; this is where you add or remove people.",
            style = UFont.sans(13), color = c.ink2,
        )

        // ── Roster header + Add-someone toggle ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("People · $rosterCount", modifier = Modifier.weight(1f))
            if (!adding) UButton("Add someone", kind = ButtonKind.OUTLINED, fill = false, leadingIcon = Icons.Filled.Add) {
                adding = true; result = null; addErr = null
            }
        }

        // ── Roster list ──
        if (sections.roster.isEmpty() && !adding) {
            Text(
                if (sections.waiting.isEmpty()) "No one yet. Add anyone you want to share with."
                else "No one has joined yet — your invites are below.",
                style = UFont.sans(13), color = c.ink3,
            )
        } else {
            sections.roster.forEach { m ->
                MemberRow(
                    m, copied = copiedKey == m.id,
                    onCopy = { m.inviteCode?.let { copy(inviteLink(it), m.id) } },
                    onRemove = { removeTarget = m },
                )
            }
        }
        rosterError?.let { Text(it, style = UFont.sans(12), color = c.red) }

        // ── Waiting to join: every invite I sent, whichever screen sent it ──
        if (sections.waiting.isNotEmpty() || waitingError != null) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Waiting to join · ${sections.waiting.size}")
                Text(
                    "Invites you've sent that haven't been claimed. They get in the moment they sign up with that address.",
                    style = UFont.sans(12), color = c.ink3,
                )
                waitingError?.let { Text(it, style = UFont.sans(12), color = c.red) }
                if (sections.waiting.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(12.dp))) {
                        sections.waiting.forEachIndexed { idx, p ->
                            if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                            WaitingRow(
                                p, copied = copiedKey == p.id,
                                onCopy = { p.inviteCode?.let { copy(inviteLink(it), p.id) } },
                                onCancel = { cancelTarget = p },
                            )
                        }
                    }
                }
            }
        }

        // ── Blocked: everyone I blocked, each with Unblock (audit 2026-09-22 C10).
        // Hidden while there is nothing to show. ──
        if (blocked.isNotEmpty() || blockError != null) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Blocked · ${blocked.size}")
                Text("They can't share with you or add you to lists.", style = UFont.sans(12), color = c.ink3)
                blockError?.let { Text(it, style = UFont.sans(12), color = c.red) }
                if (blocked.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(12.dp))) {
                        blocked.forEachIndexed { idx, b ->
                            if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(b.name, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(
                                    "Unblock", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { unblock(b) }.padding(horizontal = 8.dp, vertical = 6.dp)
                                        .semantics { contentDescription = "Unblock ${b.name}" },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Inline add-someone panel ──
        if (adding) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = result
                if (r != null) {
                    when {
                        r.added == true -> Text("✓ Added.", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                        r.emailed == true -> Text("✓ Invite sent.", style = UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                        r.link != null -> {
                            Text("Invite link ready${if (copiedKey == "new") " · copied!" else ""}", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                            Text(r.link!!, style = UFont.sans(12), color = c.ink2, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(horizontal = 10.dp, vertical = 8.dp))
                            Text("Send this to them however you like — it's the only way in.", style = UFont.sans(12), color = c.ink3)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.link != null) UButton("Copy link", kind = ButtonKind.DARK, fill = false) { copy(r.link!!, "new") }
                        UButton("Done", kind = ButtonKind.GHOST, fill = false) { adding = false; result = null }
                    }
                } else {
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text("name@example.com (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitInvite() }),
                    )
                    Text("We'll email them the invite. Or leave it blank for a link you send yourself.", style = UFont.sans(12), color = c.ink3)
                    addErr?.let { Text(it, style = UFont.sans(12), color = c.red) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UButton(if (busy) "…" else if (email.isBlank()) "Generate link" else "Send invite", kind = ButtonKind.DARK, fill = false, enabled = !busy) { submitInvite() }
                        UButton("Cancel", kind = ButtonKind.GHOST, fill = false) { adding = false; addErr = null }
                    }
                }
            }
        }

        // ── Redeem an invite someone sent you — behind a link (most people
        // arrive through the invite link itself) ──
        if (!redeemOpen && redeemMsg == null) {
            Text(
                "Have an invite code?", style = UFont.sans(13, FontWeight.Medium), color = c.ink2,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { redeemOpen = true }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 4.dp)
                    .testTag("people-invite-code"),
            )
        } else {
            SectionLabel("Have an invite code?", modifier = Modifier.padding(top = 4.dp))
            Text("Paste the invite link or code someone sent you to join their connections.", style = UFont.sans(12), color = c.ink3)
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("Invite link or code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submitRedeem() }),
            )
            redeemMsg?.let { (ok, text) -> Text(text, style = UFont.sans(12), color = if (ok) c.greenInk else c.red) }
            UButton(if (redeeming) "Joining…" else "Join", kind = ButtonKind.DARK, fill = false, enabled = !redeeming && code.isNotBlank()) { submitRedeem() }
        }
    }

    removeTarget?.let { m ->
        val pendingRow = m.status == CircleStatus.INVITED
        // A block is server-side (075): they also can't share with you again until
        // you unblock them under Blocked (audit 2026-09-22 C10).
        val canBlock = !pendingRow && m.memberUserId != null
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(if (pendingRow) "Cancel this invite?" else "Remove this connection?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            // Names both directions, tasks AND lists (audit 2026-09-22 C11).
            text = { Text(removeConnectionMessage(m), style = UFont.sans(13), color = c.ink2) },
            // Three actions stack (end-aligned) so none clips at large font sizes.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { removeTarget = null; remove(m) }) { Text(if (pendingRow) "Cancel invite" else "Remove", color = c.red) }
                    if (canBlock) TextButton(onClick = { removeTarget = null; block(m) }) { Text("Remove and block", color = c.red) }
                    if (canBlock) TextButton(onClick = { removeTarget = null }) { Text("Cancel", color = c.ink2) }
                }
            },
            dismissButton = if (canBlock) null else ({ TextButton(onClick = { removeTarget = null }) { Text(if (pendingRow) "Keep it" else "Cancel", color = c.ink2) } }),
            containerColor = c.surface,
        )
    }

    cancelTarget?.let { p ->
        val who = p.email.ifEmpty { "They" }
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("Cancel this invite?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = {
                Text(
                    when (p.kind) {
                        PendingInviteKind.CIRCLE -> "$who won't be added to your people when they sign up."
                        PendingInviteKind.TASK -> "$who won't get ${p.itemName?.let { "“$it”" } ?: "the task"} when they sign up."
                        PendingInviteKind.COLLECTION -> "$who won't get ${p.itemName?.let { "“$it”" } ?: "the list"} when they sign up."
                    },
                    style = UFont.sans(13), color = c.ink2,
                )
            },
            confirmButton = { TextButton(onClick = { cancelTarget = null; cancelPending(p) }) { Text("Cancel invite", color = c.red) } },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text("Keep it", color = c.ink2) } },
            containerColor = c.surface,
        )
    }
}

/** One roster row: monogram + name/status, plus copy-link (pending) + remove.
 *  A pending invite shows WHO was invited (`circle_list.invitee_email`);
 *  link-only invites have no address. */
@Composable
private fun MemberRow(m: CircleMember, copied: Boolean, onCopy: () -> Unit, onRemove: () -> Unit) {
    val c = UTheme.colors
    val pending = m.status == CircleStatus.INVITED
    val title = if (pending) (m.inviteeEmail ?: "Invite pending") else (m.memberName ?: "Member")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // Monogram in the app's neutral pair (ink2 on surface, line2 ring) —
        // never an accent colour (memory brand-colour-coral-only).
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line2, CircleShape), contentAlignment = Alignment.Center) {
            Text((title.trim().firstOrNull() ?: '?').uppercase(), style = UFont.sans(13, FontWeight.SemiBold), color = c.ink2)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                m.relationshipLabel ?: if (pending) (if (m.inviteeEmail == null) "waiting to be accepted" else "invited · waiting for them to sign up") else "connected",
                style = UFont.sans(12), color = c.ink3, maxLines = 1,
            )
        }
        if (pending && m.inviteCode != null) {
            Text(
                if (copied) "Copied!" else "Copy link", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCopy() }.padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { contentDescription = copyInviteLinkLabel(m.inviteeEmail, copied) },
            )
        }
        Icon(
            Icons.Filled.Close, contentDescription = if (pending) "Cancel invite" else "Remove ${m.memberName ?: "member"}", tint = c.ink3,
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onRemove() }.padding(5.dp),
        )
    }
}

/** One Waiting-to-join row: the address, what the invite is for ("Draft the
 *  deck · can edit" / "Groceries · can view" / "your people"), Copy link when
 *  the invite has a join code, and Cancel. */
@Composable
private fun WaitingRow(p: PendingInvite, copied: Boolean, onCopy: () -> Unit, onCancel: () -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.email.ifEmpty { "Invite pending" }, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pendingInviteLabel(p), style = UFont.sans(12), color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.inviteCode != null) {
            Text(
                if (copied) "Copied!" else "Copy link", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCopy() }.padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { contentDescription = copyInviteLinkLabel(p.email, copied) },
            )
        }
        Icon(
            Icons.Filled.Close, contentDescription = "Cancel invite${if (p.email.isEmpty()) "" else " to ${p.email}"}", tint = c.ink3,
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onCancel() }.padding(5.dp),
        )
    }
}

/** The screen-reader label of a "Copy link" button — naming the address makes
 *  each of several identical buttons unambiguous (mirrors iOS). */
private fun copyInviteLinkLabel(email: String?, copied: Boolean): String {
    val base = if (copied) "Copied invite link" else "Copy invite link"
    return if (email.isNullOrEmpty()) base else "$base for $email"
}
